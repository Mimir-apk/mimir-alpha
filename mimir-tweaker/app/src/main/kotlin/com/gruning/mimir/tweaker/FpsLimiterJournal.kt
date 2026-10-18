package com.gruning.mimir.tweaker

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

internal enum class FpsLimiterJournalPhase {
    PREPARED,
    APPLIED,
    RESTORING,
    RESTORE_FAILED,
}

internal data class FpsLimiterJournalRecord(
    val schemaVersion: Int = FPS_LIMITER_JOURNAL_SCHEMA,
    val generation: Long,
    val requestId: String,
    val transactionId: String,
    val ownerUid: Int,
    val packageIdentity: GameModeFpsPackageIdentity,
    val targetFps: Int,
    val phase: FpsLimiterJournalPhase,
    val createdAtElapsedRealtimeMs: Long,
    val hardDeadlineElapsedRealtimeMs: Long,
    val bootIdentity: String,
    val lastFailure: String? = null,
) {
    init {
        require(schemaVersion == FPS_LIMITER_JOURNAL_SCHEMA)
        require(generation > 0L && ownerUid >= 0)
        require(FPS_LIMITER_ID.matches(requestId) && FPS_LIMITER_ID.matches(transactionId))
        require(targetFps in FPS_LIMITER_TARGETS)
        require(createdAtElapsedRealtimeMs >= 0L)
        require(hardDeadlineElapsedRealtimeMs > createdAtElapsedRealtimeMs)
        require(FPS_LIMITER_ID.matches(bootIdentity))
        require(lastFailure == null || lastFailure.length in 1..240)
    }

    fun sameImmutableIdentity(other: FpsLimiterJournalRecord): Boolean = copy(
        phase = other.phase,
        lastFailure = other.lastFailure,
    ) == other
}

internal sealed interface FpsLimiterJournalLoad {
    data object Empty : FpsLimiterJournalLoad
    data class Loaded(val record: FpsLimiterJournalRecord) : FpsLimiterJournalLoad
    data class Corrupt(val reason: String) : FpsLimiterJournalLoad
}

internal sealed interface FpsLimiterGeneration {
    data class Ok(val value: Long) : FpsLimiterGeneration
    data class Failed(val reason: String) : FpsLimiterGeneration
}

internal interface FpsLimiterJournal {
    fun load(): FpsLimiterJournalLoad
    fun nextGeneration(): FpsLimiterGeneration
    fun persist(record: FpsLimiterJournalRecord): Boolean
    fun clear(expectedTransactionId: String): Boolean
}

internal class InMemoryFpsLimiterJournal : FpsLimiterJournal {
    private var record: FpsLimiterJournalRecord? = null
    private var generation = 0L

    @Synchronized
    override fun load(): FpsLimiterJournalLoad =
        record?.let(FpsLimiterJournalLoad::Loaded) ?: FpsLimiterJournalLoad.Empty

    @Synchronized
    override fun nextGeneration(): FpsLimiterGeneration {
        if (generation == Long.MAX_VALUE) return FpsLimiterGeneration.Failed("Generation overflow")
        generation++
        return FpsLimiterGeneration.Ok(generation)
    }

    @Synchronized
    override fun persist(record: FpsLimiterJournalRecord): Boolean {
        if (record.generation != generation) return false
        val prior = this.record
        if (prior != null && (!prior.sameImmutableIdentity(record) || !validFpsJournalTransition(prior.phase, record.phase))) {
            return false
        }
        this.record = record
        return true
    }

    @Synchronized
    override fun clear(expectedTransactionId: String): Boolean {
        val current = record ?: return true
        if (current.transactionId != expectedTransactionId) return false
        record = null
        return true
    }
}

internal fun validFpsJournalTransition(
    from: FpsLimiterJournalPhase,
    to: FpsLimiterJournalPhase,
): Boolean = from == to || when (from) {
    FpsLimiterJournalPhase.PREPARED -> to in setOf(
        FpsLimiterJournalPhase.APPLIED,
        FpsLimiterJournalPhase.RESTORING,
        FpsLimiterJournalPhase.RESTORE_FAILED,
    )
    FpsLimiterJournalPhase.APPLIED -> to == FpsLimiterJournalPhase.RESTORING
    FpsLimiterJournalPhase.RESTORING -> to == FpsLimiterJournalPhase.RESTORE_FAILED
    FpsLimiterJournalPhase.RESTORE_FAILED -> to == FpsLimiterJournalPhase.RESTORING
}

internal data class SignedFpsLimiterJournalRecord(
    val keyIdentity: String,
    val encodedPayload: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(FPS_LIMITER_KEY_ID.matches(keyIdentity))
        require(encodedPayload.size in 1..FpsLimiterJournalCodec.MAX_PAYLOAD_BYTES)
        require(signature.size in 1..512)
    }

    fun canonicalBytes(): ByteArray = FpsLimiterJournalCodec.canonicalBytes(keyIdentity, encodedPayload)
}

internal object FpsLimiterJournalCodec {
    internal const val MAX_PAYLOAD_BYTES = 16 * 1024
    private const val PAYLOAD_MAGIC = 0x4D465053 // MFPS
    private const val SIGNED_MAGIC = 0x4D465341 // MFSA
    private const val CODEC_VERSION = 1
    private const val SIGNATURE_TAG = "MIMIR_FPS_INTERVENTION_JOURNAL"

    fun encodePayload(record: FpsLimiterJournalRecord): ByteArray = ByteArrayOutputStream().also { sink ->
        DataOutputStream(sink).use { out ->
            out.writeInt(PAYLOAD_MAGIC)
            out.writeInt(CODEC_VERSION)
            out.writeInt(record.schemaVersion)
            out.writeLong(record.generation)
            out.string(record.requestId)
            out.string(record.transactionId)
            out.writeInt(record.ownerUid)
            out.string(record.packageIdentity.packageName)
            out.writeInt(record.packageIdentity.userId)
            out.writeInt(record.packageIdentity.uid)
            out.writeLong(record.packageIdentity.versionCode)
            out.string(record.packageIdentity.signingCertificateSha256)
            out.writeInt(record.targetFps)
            out.string(record.phase.name)
            out.writeLong(record.createdAtElapsedRealtimeMs)
            out.writeLong(record.hardDeadlineElapsedRealtimeMs)
            out.string(record.bootIdentity)
            out.nullableString(record.lastFailure)
        }
    }.toByteArray().also { require(it.size <= MAX_PAYLOAD_BYTES) }

    fun decodePayload(bytes: ByteArray): FpsLimiterJournalRecord? = runCatching {
        require(bytes.size in 1..MAX_PAYLOAD_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PAYLOAD_MAGIC && input.readInt() == CODEC_VERSION)
            val record = FpsLimiterJournalRecord(
                schemaVersion = input.readInt(),
                generation = input.readLong(),
                requestId = input.string(128),
                transactionId = input.string(128),
                ownerUid = input.readInt(),
                packageIdentity = GameModeFpsPackageIdentity(
                    packageName = input.string(180),
                    userId = input.readInt(),
                    uid = input.readInt(),
                    versionCode = input.readLong(),
                    signingCertificateSha256 = input.string(64),
                ),
                targetFps = input.readInt(),
                phase = FpsLimiterJournalPhase.valueOf(input.string(32)),
                createdAtElapsedRealtimeMs = input.readLong(),
                hardDeadlineElapsedRealtimeMs = input.readLong(),
                bootIdentity = input.string(128),
                lastFailure = input.nullableString(240),
            )
            require(input.read() == -1)
            record
        }
    }.getOrNull()

    fun encodeSigned(record: SignedFpsLimiterJournalRecord): ByteArray = ByteArrayOutputStream().also { sink ->
        DataOutputStream(sink).use { out ->
            out.writeInt(SIGNED_MAGIC)
            out.writeInt(CODEC_VERSION)
            out.string(record.keyIdentity)
            out.bytes(record.encodedPayload)
            out.bytes(record.signature)
        }
    }.toByteArray()

    fun decodeSigned(bytes: ByteArray): SignedFpsLimiterJournalRecord? = runCatching {
        require(bytes.size in 1..(MAX_PAYLOAD_BYTES + 1024))
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == SIGNED_MAGIC && input.readInt() == CODEC_VERSION)
            val signed = SignedFpsLimiterJournalRecord(
                keyIdentity = input.string(128),
                encodedPayload = input.bytes(MAX_PAYLOAD_BYTES),
                signature = input.bytes(512),
            )
            require(input.read() == -1)
            signed
        }
    }.getOrNull()

    fun canonicalBytes(keyIdentity: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { out ->
                out.string(SIGNATURE_TAG)
                out.writeInt(CODEC_VERSION)
                out.string(keyIdentity)
                out.bytes(payload)
            }
        }.toByteArray()

    private fun DataOutputStream.string(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
    private fun DataOutputStream.nullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) string(value)
    }
    private fun DataOutputStream.bytes(value: ByteArray) {
        require(value.isNotEmpty())
        writeInt(value.size)
        write(value)
    }
    private fun DataInputStream.string(maxBytes: Int): String = bytes(maxBytes).toString(Charsets.UTF_8).also {
        require(it.toByteArray(Charsets.UTF_8).size <= maxBytes)
    }
    private fun DataInputStream.nullableString(maxBytes: Int): String? =
        if (readBoolean()) string(maxBytes) else null
    private fun DataInputStream.bytes(maxBytes: Int): ByteArray {
        val size = readInt()
        require(size in 1..maxBytes)
        return ByteArray(size).also(::readFully)
    }
}

internal interface FpsLimiterJournalAuthentication {
    fun protect(payload: ByteArray): ByteArray?
    fun verifyAndDecode(bytes: ByteArray): FpsLimiterJournalLoad
}

internal class DirectBootFpsLimiterJournalAuthentication(
    private val authority: DirectBootKeyAuthority,
) : FpsLimiterJournalAuthentication {
    override fun protect(payload: ByteArray): ByteArray? = runCatching {
        val available = when (val truth = authority.inspect()) {
            DirectBootKeyAuthorityTruth.Fresh -> authority.provisionFresh()
            else -> truth
        } as? DirectBootKeyAuthorityTruth.Available ?: return null
        FpsLimiterJournalCodec.encodeSigned(available.signFpsJournal(payload))
    }.getOrNull()

    override fun verifyAndDecode(bytes: ByteArray): FpsLimiterJournalLoad {
        val available = when (val truth = runCatching(authority::inspect).getOrNull()) {
            is DirectBootKeyAuthorityTruth.Available -> truth
            DirectBootKeyAuthorityTruth.Fresh ->
                return FpsLimiterJournalLoad.Corrupt("FPS journal key is not provisioned")
            is DirectBootKeyAuthorityTruth.Unavailable ->
                return FpsLimiterJournalLoad.Corrupt("FPS journal key unavailable: ${truth.reason}")
            is DirectBootKeyAuthorityTruth.Corrupt ->
                return FpsLimiterJournalLoad.Corrupt("FPS journal key corrupt: ${truth.reason}")
            is DirectBootKeyAuthorityTruth.CertificateChanged ->
                return FpsLimiterJournalLoad.Corrupt("FPS journal signing identity changed: ${truth.reason}")
            null -> return FpsLimiterJournalLoad.Corrupt("FPS journal key inspection failed")
        }
        val signed = FpsLimiterJournalCodec.decodeSigned(bytes)
            ?: return FpsLimiterJournalLoad.Corrupt("FPS journal envelope malformed")
        if (!available.verifiesFpsJournal(signed)) {
            return FpsLimiterJournalLoad.Corrupt("FPS journal signature invalid")
        }
        val record = FpsLimiterJournalCodec.decodePayload(signed.encodedPayload)
            ?: return FpsLimiterJournalLoad.Corrupt("FPS journal payload malformed")
        return FpsLimiterJournalLoad.Loaded(record)
    }
}

internal class AtomicFileFpsLimiterJournal private constructor(
    private val filesDir: File,
    private val authentication: FpsLimiterJournalAuthentication,
    private val directoryDurability: DirectoryDurability,
) : FpsLimiterJournal {
    private val atomic = AtomicFile(File(filesDir, FPS_JOURNAL_FILE))
    private val generationAtomic = AtomicFile(File(filesDir, FPS_GENERATION_FILE))

    constructor(
        context: Context,
        authentication: FpsLimiterJournalAuthentication,
        directoryDurability: DirectoryDurability = OsDirectoryDurability,
    ) : this(
        filesDir = context.applicationContext.createDeviceProtectedStorageContext().filesDir,
        authentication = authentication,
        directoryDurability = directoryDurability,
    )

    constructor(
        filesDir: File,
        authentication: FpsLimiterJournalAuthentication,
        @Suppress("UNUSED_PARAMETER") testSeam: Boolean,
        directoryDurability: DirectoryDurability = NoopDirectoryDurability,
    ) : this(filesDir, authentication, directoryDurability)

    @Synchronized
    override fun load(): FpsLimiterJournalLoad {
        if (!atomic.baseFile.exists()) return FpsLimiterJournalLoad.Empty
        val bytes = runCatching(atomic::readFully).getOrElse {
            return FpsLimiterJournalLoad.Corrupt("Unreadable FPS journal: ${it.message}")
        }
        if (bytes.isEmpty()) return FpsLimiterJournalLoad.Corrupt("Empty FPS journal")
        val loaded = authentication.verifyAndDecode(bytes)
        if (loaded !is FpsLimiterJournalLoad.Loaded) return loaded
        val generation = readGeneration()
            ?: return FpsLimiterJournalLoad.Corrupt("FPS generation is missing or corrupt")
        return if (generation == loaded.record.generation) loaded else {
            FpsLimiterJournalLoad.Corrupt("FPS generation mismatch")
        }
    }

    @Synchronized
    override fun nextGeneration(): FpsLimiterGeneration {
        val current = if (!generationAtomic.baseFile.exists()) 0L else {
            readGeneration() ?: return FpsLimiterGeneration.Failed("FPS generation is corrupt")
        }
        if (current == Long.MAX_VALUE) return FpsLimiterGeneration.Failed("FPS generation overflow")
        val next = current + 1L
        return if (writeAtomic(generationAtomic, next.toString().toByteArray())) {
            FpsLimiterGeneration.Ok(next)
        } else {
            FpsLimiterGeneration.Failed("FPS generation could not be persisted")
        }
    }

    @Synchronized
    override fun persist(record: FpsLimiterJournalRecord): Boolean {
        if (readGeneration() != record.generation) return false
        val prior = when (val load = load()) {
            FpsLimiterJournalLoad.Empty -> null
            is FpsLimiterJournalLoad.Corrupt -> return false
            is FpsLimiterJournalLoad.Loaded -> load.record
        }
        if (prior != null && (!prior.sameImmutableIdentity(record) || !validFpsJournalTransition(prior.phase, record.phase))) {
            return false
        }
        val signed = authentication.protect(FpsLimiterJournalCodec.encodePayload(record)) ?: return false
        return writeAtomic(atomic, signed)
    }

    @Synchronized
    override fun clear(expectedTransactionId: String): Boolean {
        return when (val load = load()) {
            FpsLimiterJournalLoad.Empty -> true
            is FpsLimiterJournalLoad.Corrupt -> false
            is FpsLimiterJournalLoad.Loaded -> {
                if (load.record.transactionId != expectedTransactionId) return false
                runCatching {
                    atomic.delete()
                    directoryDurability.sync(filesDir)
                    !atomic.baseFile.exists()
                }.getOrDefault(false)
            }
        }
    }

    private fun readGeneration(): Long? {
        if (!generationAtomic.baseFile.exists()) return null
        return runCatching { generationAtomic.readFully().toString(Charsets.UTF_8).trim().toLong() }
            .getOrNull()?.takeIf { it > 0L }
    }

    private fun writeAtomic(target: AtomicFile, bytes: ByteArray): Boolean {
        var stream: FileOutputStream? = null
        return try {
            stream = target.startWrite()
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            target.finishWrite(stream)
            stream = null
            directoryDurability.sync(filesDir)
            true
        } catch (_: Throwable) {
            stream?.let { runCatching { target.failWrite(it) } }
            false
        }
    }
}

internal fun createAuthenticatedFpsLimiterJournal(context: Context): FpsLimiterJournal {
    val protected = context.applicationContext.createDeviceProtectedStorageContext()
    return AtomicFileFpsLimiterJournal(
        context = protected,
        authentication = DirectBootFpsLimiterJournalAuthentication(DirectBootKeyAuthority(protected)),
    )
}

internal const val FPS_LIMITER_JOURNAL_SCHEMA = 1
private val FPS_LIMITER_ID = Regex("[A-Za-z0-9._:-]{1,128}")
private val FPS_LIMITER_KEY_ID = Regex("[A-Za-z0-9._:-]{1,128}")
private val FPS_LIMITER_TARGETS = setOf(30, 40, 60, 90, 120)
private const val FPS_JOURNAL_FILE = "mimir_fps_intervention.journal"
private const val FPS_GENERATION_FILE = "mimir_fps_intervention.generation"
