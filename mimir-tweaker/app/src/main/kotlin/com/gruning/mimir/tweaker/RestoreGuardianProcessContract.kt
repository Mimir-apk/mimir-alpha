package com.gruning.mimir.tweaker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import javax.security.auth.Destroyable

internal object GuardianProcessCallerPolicy {
    fun allows(callingUid: Int, packageUid: Int): Boolean =
        callingUid >= 0 && packageUid >= 0 && callingUid == packageUid
}

/** Receipt signer owned only by the remote Guardian process for one armed generation. */
internal class GuardianProcessSessionAuthority private constructor(
    private var keyPair: KeyPair?,
    private val pinnedPublicKey: GuardianSessionPublicKey,
) : GuardianReceiptAuthenticationStrategy, AutoCloseable {
    val sessionPublicKey: GuardianSessionPublicKey
        get() = GuardianSessionPublicKey.fromSec1(pinnedPublicKey.sec1Bytes())

    @Synchronized
    override fun authenticate(type: GuardianReceiptType, canonicalPayload: ByteArray): ByteArray? {
        val pair = keyPair ?: return null
        val maxBytes = when (type) {
            GuardianReceiptType.READY -> GuardianArmRecordCodec.MAX_READY
            GuardianReceiptType.STOCK_CONFIRMED,
            GuardianReceiptType.EXITED,
            -> GuardianTerminalWireCodec.MAX_CANONICAL_BYTES
        }
        if (canonicalPayload.size !in 1..maxBytes) return null
        return runCatching {
            EcdsaP256GuardianSessionSigner(pair.private).sign(canonicalPayload)
                .takeIf { it.size in 1..GuardianArmRecordCodec.MAX_SIGNATURE }
        }.getOrNull()
    }

    @Synchronized
    override fun close() {
        val retired = keyPair ?: return
        keyPair = null
        (retired.private as? Destroyable)?.let { destroyable ->
            runCatching { if (!destroyable.isDestroyed) destroyable.destroy() }
        }
    }

    companion object {
        fun generate(
            generator: () -> KeyPairGenerator = { KeyPairGenerator.getInstance("EC") },
        ): GuardianProcessSessionAuthority? = runCatching {
            val keyPairGenerator = generator()
            keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
            val pair = keyPairGenerator.generateKeyPair()
            val publicKey = GuardianSessionPublicKey.fromPublicKey(pair.public)
            EcdsaP256GuardianSessionSigner(pair.private)
            GuardianProcessSessionAuthority(pair, publicKey)
        }.getOrNull()

        internal fun fromKeyPair(pair: KeyPair): GuardianProcessSessionAuthority? = runCatching {
            require(pair.private.encoded == null) { "Guardian private key must be non-exportable" }
            val publicKey = GuardianSessionPublicKey.fromPublicKey(pair.public)
            val signer = EcdsaP256GuardianSessionSigner(pair.private)
            val challenge = "MIMIR_GUARDIAN_PROCESS_SESSION_BINDING_V1".toByteArray(Charsets.UTF_8)
            require(publicKey.verifier().verify(challenge, signer.sign(challenge)))
            GuardianProcessSessionAuthority(pair, publicKey)
        }.getOrNull()
    }
}

/** Strict frame for the two terminal receipts. Neither receipt is accepted alone. */
internal object GuardianTerminalWireCodec {
    private const val MAGIC = 0x4D475452 // MGTR
    private const val VERSION = 2
    internal const val MAX_CANONICAL_BYTES = 2 * 1024
    private const val MAX_FRAME_BYTES = 8 * 1024

    fun encode(
        stock: SignedRestoreGuardianStockConfirmedReceipt,
        exited: SignedRestoreGuardianExitedReceipt,
    ): ByteArray? = runCatching {
        require(exited.receipt.stockConfirmedReceiptSha256 == stock.receipt.digestSha256())
        frame(
            stock.receipt.canonicalBytes(),
            stock.signature,
            exited.receipt.canonicalBytes(),
            exited.signature,
        )
    }.getOrNull()

    fun decode(bytes: ByteArray): GuardianTerminalLoad.Received? = runCatching {
        require(bytes.size in 1..MAX_FRAME_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            require(input.readInt() == VERSION)
            val stockCanonical = input.bytes(MAX_CANONICAL_BYTES)
            val stockSignature = input.bytes(GuardianArmRecordCodec.MAX_SIGNATURE)
            val exitedCanonical = input.bytes(MAX_CANONICAL_BYTES)
            val exitedSignature = input.bytes(GuardianArmRecordCodec.MAX_SIGNATURE)
            require(input.read() == -1)
            val stock = decodeStock(stockCanonical)
            val exited = decodeExited(exitedCanonical)
            require(stock.canonicalBytes().contentEquals(stockCanonical))
            require(exited.canonicalBytes().contentEquals(exitedCanonical))
            require(exited.stockConfirmedReceiptSha256 == stock.digestSha256())
            GuardianTerminalLoad.Received(
                SignedRestoreGuardianStockConfirmedReceipt(stock, stockSignature),
                SignedRestoreGuardianExitedReceipt(exited, exitedSignature),
            )
        }
    }.getOrNull()

    fun signedStock(canonical: ByteArray, signature: ByteArray): SignedRestoreGuardianStockConfirmedReceipt? =
        runCatching {
            require(canonical.size in 1..MAX_CANONICAL_BYTES)
            require(signature.size in 1..GuardianArmRecordCodec.MAX_SIGNATURE)
            decodeStock(canonical).also { require(it.canonicalBytes().contentEquals(canonical)) }
                .let { SignedRestoreGuardianStockConfirmedReceipt(it, signature) }
        }.getOrNull()

    fun signedExited(canonical: ByteArray, signature: ByteArray): SignedRestoreGuardianExitedReceipt? =
        runCatching {
            require(canonical.size in 1..MAX_CANONICAL_BYTES)
            require(signature.size in 1..GuardianArmRecordCodec.MAX_SIGNATURE)
            decodeExited(canonical).also { require(it.canonicalBytes().contentEquals(canonical)) }
                .let { SignedRestoreGuardianExitedReceipt(it, signature) }
        }.getOrNull()

    private fun frame(vararg fields: ByteArray): ByteArray =
        ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                fields.forEach { field -> output.bytes(field) }
            }
        }.toByteArray().also { require(it.size in 1..MAX_FRAME_BYTES) }

    private fun decodeStock(bytes: ByteArray): RestoreGuardianStockConfirmedReceipt =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.string(64) == "MIMIR_RESTORE_GUARDIAN_STOCK_CONFIRMED")
            RestoreGuardianStockConfirmedReceipt(
                schemaVersion = input.readInt(),
                helperBuildIdentity = input.string(256),
                bootIdentity = input.string(256),
                recoveryBootIdentity = input.string(256),
                restoreScope = GuardianTerminalRestoreScope.valueOf(input.string(64)),
                generation = input.readLong(),
                requestId = input.string(256),
                transactionId = input.string(256),
                taskNonce = input.string(128),
                envelopeSha256 = input.string(64),
                sessionKeySha256 = input.string(64),
                verifiedStockEntrySetSha256 = input.string(64),
                firstReadbackElapsedRealtimeMs = input.readLong(),
                secondReadbackElapsedRealtimeMs = input.readLong(),
                issuedElapsedRealtimeMs = input.readLong(),
            ).also { require(input.read() == -1) }
        }

    private fun decodeExited(bytes: ByteArray): RestoreGuardianExitedReceipt =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.string(64) == "MIMIR_RESTORE_GUARDIAN_EXITED")
            RestoreGuardianExitedReceipt(
                schemaVersion = input.readInt(),
                helperBuildIdentity = input.string(256),
                bootIdentity = input.string(256),
                recoveryBootIdentity = input.string(256),
                restoreScope = GuardianTerminalRestoreScope.valueOf(input.string(64)),
                generation = input.readLong(),
                requestId = input.string(256),
                transactionId = input.string(256),
                taskNonce = input.string(128),
                envelopeSha256 = input.string(64),
                sessionKeySha256 = input.string(64),
                verifiedStockEntrySetSha256 = input.string(64),
                stockConfirmedReceiptSha256 = input.string(64),
                issuedElapsedRealtimeMs = input.readLong(),
            ).also { require(input.read() == -1) }
        }

    private fun DataOutputStream.bytes(value: ByteArray) {
        require(value.isNotEmpty())
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.bytes(maxBytes: Int): ByteArray {
        val size = readInt()
        require(size in 1..maxBytes)
        return ByteArray(size).also(::readFully)
    }

    private fun DataInputStream.string(maxBytes: Int): String {
        val raw = bytes(maxBytes)
        return raw.toString(Charsets.UTF_8).also {
            require(it.toByteArray(Charsets.UTF_8).contentEquals(raw))
        }
    }
}

internal class GuardianTerminalReceiptStore(
    directory: File,
) : GuardianTerminalReceiptPort {
    private val root = directory.absoluteFile
    private val record = File(root, RECORD_NAME)
    private val staging = File(root, ".$RECORD_NAME.tmp")

    init { require(root.isAbsolute) }

    @Synchronized
    fun persist(
        stock: SignedRestoreGuardianStockConfirmedReceipt,
        exited: SignedRestoreGuardianExitedReceipt,
    ): Boolean = runCatching {
        val encoded = GuardianTerminalWireCodec.encode(stock, exited) ?: return false
        if (!root.exists() && !root.mkdirs()) return false
        require(root.isDirectory)
        if (staging.exists() && !staging.delete()) return false
        FileOutputStream(staging).use { output ->
            output.write(encoded)
            output.flush()
            output.fd.sync()
        }
        Files.move(
            staging.toPath(),
            record.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        syncDirectoryBestEffort(root)
        loadOnce() is GuardianTerminalLoad.Received
    }.getOrDefault(false)

    /**
     * Persist one collector-owned terminal pair without replacing unrelated truth. The byte frame
     * is the ownership token: a foreign, malformed, or interrupted frame is retained.
     */
    @Synchronized
    fun persistOwnedPending(
        stock: SignedRestoreGuardianStockConfirmedReceipt,
        exited: SignedRestoreGuardianExitedReceipt,
    ): GuardianTerminalPersistDisposition {
        val encoded = GuardianTerminalWireCodec.encode(stock, exited)
            ?: return GuardianTerminalPersistDisposition.RETAINED
        if (record.exists()) {
            return if (readBytes(record)?.contentEquals(encoded) == true) {
                GuardianTerminalPersistDisposition.CONFIRMED
            } else {
                GuardianTerminalPersistDisposition.RETAINED
            }
        }
        if (staging.exists()) {
            if (readBytes(staging)?.contentEquals(encoded) != true) {
                return GuardianTerminalPersistDisposition.RETAINED
            }
            if (!staging.delete()) return GuardianTerminalPersistDisposition.RETRYABLE
        }
        return if (persist(stock, exited)) {
            GuardianTerminalPersistDisposition.CONFIRMED
        } else {
            GuardianTerminalPersistDisposition.RETRYABLE
        }
    }

    @Synchronized
    fun containsExact(
        stock: SignedRestoreGuardianStockConfirmedReceipt,
        exited: SignedRestoreGuardianExitedReceipt,
    ): Boolean {
        val encoded = GuardianTerminalWireCodec.encode(stock, exited) ?: return false
        return record.exists() && readBytes(record)?.contentEquals(encoded) == true
    }

    override fun loadUntil(deadlineElapsedRealtimeMs: Long): GuardianTerminalLoad = loadOnce()

    @Synchronized
    fun clear(): Boolean = !record.exists() || record.delete()

    /**
     * Unchanged in behaviour -- every exit returns exactly what it returned before. The only
     * addition is that the cause [resolveOnce] already computed is now reported instead of being
     * dropped; see [GuardianTerminalLoadObservability] for why all five causes are named, why the
     * success is logged too, and why it is deduplicated on change rather than emitted per call.
     */
    private fun loadOnce(): GuardianTerminalLoad {
        val resolved = resolveOnce()
        GuardianTerminalLoadObservability.onLoadResolved(resolved.cause, resolved.detail)
        return resolved.load
    }

    /**
     * The five exits, each paired with the one datum that discriminates it.
     *
     * Split out of [loadOnce] so the cause travels with the load rather than being re-derived by a
     * caller that can no longer see which branch ran -- re-deriving it is what
     * `GuardianTransactionReconciler.ready()` is stuck doing today, and it cannot: `Ambiguous`
     * arrives from four different places.
     *
     * The non-local `return`s exit this function directly, exactly as they did when this body was
     * [loadOnce] itself, so [loadOnce] still observes and reports every one of them.
     */
    private fun resolveOnce(): TerminalLoadResolution = runCatching {
        if (staging.exists()) {
            return TerminalLoadResolution(
                GuardianTerminalLoad.Ambiguous,
                GuardianTerminalLoadObservability.CAUSE_STAGING_PRESENT,
                null,
            )
        }
        if (!record.exists()) {
            return TerminalLoadResolution(
                GuardianTerminalLoad.Unavailable,
                GuardianTerminalLoadObservability.CAUSE_RECORD_ABSENT,
                null,
            )
        }
        val length = record.length()
        if (length !in 1..(8 * 1024L)) {
            return TerminalLoadResolution(
                GuardianTerminalLoad.Ambiguous,
                GuardianTerminalLoadObservability.CAUSE_LENGTH_OUT_OF_RANGE,
                length.toString(),
            )
        }
        val bytes = FileInputStream(record).use { it.readBytes() }
        val decoded = GuardianTerminalWireCodec.decode(bytes)
        TerminalLoadResolution(
            decoded ?: GuardianTerminalLoad.Ambiguous,
            if (decoded == null) {
                GuardianTerminalLoadObservability.CAUSE_DECODE_NULL
            } else {
                GuardianTerminalLoadObservability.CAUSE_RECEIVED
            },
            bytes.size.toString(),
        )
    }.getOrElse {
        TerminalLoadResolution(
            GuardianTerminalLoad.Ambiguous,
            GuardianTerminalLoadObservability.CAUSE_THREW,
            it.javaClass.simpleName,
        )
    }

    /** Reasons and sizes only -- [detail] never carries a path, a package, or receipt content. */
    private data class TerminalLoadResolution(
        val load: GuardianTerminalLoad,
        val cause: String,
        val detail: String?,
    )

    private fun readBytes(file: File): ByteArray? = runCatching {
        if (!file.isFile) return null
        FileInputStream(file).use { it.readBytes() }
    }.getOrNull()

    private companion object { const val RECORD_NAME = "mimir_guardian_terminal_receipts.v1" }
}

internal enum class GuardianTerminalPersistDisposition {
    CONFIRMED,
    RETRYABLE,
    RETAINED,
}

internal enum class GuardianFenceDisposition {
    CONFIRMED,
    REJECTED,
    AMBIGUOUS,
}

/**
 * App-private cross-process flock. Terminalization is persisted while holding the same lock used
 * by every host mutation, so process death releases an in-flight apply before restore proceeds.
 */
internal class GuardianProcessMutationFence(rootDirectory: File) {
    private val root = rootDirectory.absoluteFile

    init { require(root.isAbsolute) }

    fun stage(identity: GenerationFenceIdentity): GuardianFenceDisposition = withLock(identity) { directory ->
        val session = File(directory, SESSION_MARKER)
        val terminal = File(directory, TERMINAL_MARKER)
        val canonical = identity.canonicalBytes()
        when {
            terminal.exists() -> GuardianFenceDisposition.REJECTED
            session.exists() -> if (readExact(session)?.contentEquals(canonical) == true) {
                GuardianFenceDisposition.CONFIRMED
            } else {
                GuardianFenceDisposition.REJECTED
            }
            writeFresh(session, canonical) -> GuardianFenceDisposition.CONFIRMED
            else -> GuardianFenceDisposition.AMBIGUOUS
        }
    }

    fun <T> withApply(
        identity: GenerationFenceIdentity,
        block: () -> T,
    ): GuardianFencedResult<T> = withLockResult(identity) { directory ->
        val exact = exactSession(directory, identity)
        when {
            !exact -> GuardianFencedResult.Rejected
            File(directory, TERMINAL_MARKER).exists() -> GuardianFencedResult.Terminal
            else -> GuardianFencedResult.Executed(block())
        }
    }

    fun <T> terminalizeAndRestore(
        identity: GenerationFenceIdentity,
        block: () -> T,
    ): GuardianFencedResult<T> = terminalizeAndRestoreWithPrior(identity) { block() }

    fun <T> terminalizeAndRestoreWithPrior(
        identity: GenerationFenceIdentity,
        block: (terminalWasAbsent: Boolean) -> T,
    ): GuardianFencedResult<T> = withLockResult(identity) { directory ->
        if (!exactSession(directory, identity)) return@withLockResult GuardianFencedResult.Rejected
        val terminal = File(directory, TERMINAL_MARKER)
        val canonical = identity.canonicalBytes()
        val terminalWasAbsent = !terminal.exists()
        val terminalExact = when {
            terminal.exists() -> readExact(terminal)?.contentEquals(canonical) == true
            else -> writeFresh(terminal, canonical)
        }
        if (!terminalExact) {
            GuardianFencedResult.Ambiguous
        } else {
            GuardianFencedResult.Executed(block(terminalWasAbsent))
        }
    }

    fun state(identity: GenerationFenceIdentity): FenceDiskState {
        val directory = directory(identity)
        if (!directory.exists()) return FenceDiskState(false, null, null, false)
        return runCatching {
            RandomAccessFile(File(directory, LOCK_FILE), "rw").channel.use { channel ->
                channel.lock().use {
                    FenceDiskState(
                        directoryPresent = true,
                        sessionMarkerIdentity = identity.takeIf { exactSession(directory, identity) },
                        terminalMarkerIdentity = identity.takeIf {
                            readExact(File(directory, TERMINAL_MARKER))?.contentEquals(identity.canonicalBytes()) == true
                        },
                        lockAcquired = true,
                    )
                }
            }
        }.getOrElse { FenceDiskState(true, null, null, false) }
    }

    fun clearTerminal(identity: GenerationFenceIdentity): Boolean = when (
        withLockResult(identity) { directory ->
            val terminal = File(directory, TERMINAL_MARKER)
            val session = File(directory, SESSION_MARKER)
            if (!terminal.exists() || readExact(terminal)?.contentEquals(identity.canonicalBytes()) != true) {
                return@withLockResult GuardianFencedResult.Rejected
            }
            val terminalGone = terminal.delete()
            val sessionGone = !session.exists() || session.delete()
            if (terminalGone && sessionGone) {
                GuardianFencedResult.Executed(Unit)
            } else {
                GuardianFencedResult.Ambiguous
            }
        }
    ) {
        is GuardianFencedResult.Executed -> true
        GuardianFencedResult.Terminal,
        GuardianFencedResult.Rejected,
        GuardianFencedResult.Ambiguous,
        -> false
    }

    private fun <T> withLockResult(
        identity: GenerationFenceIdentity,
        block: (File) -> GuardianFencedResult<T>,
    ): GuardianFencedResult<T> = runCatching {
        val directory = directory(identity)
        if (!directory.exists() && !directory.mkdirs()) return GuardianFencedResult.Ambiguous
        require(directory.isDirectory)
        RandomAccessFile(File(directory, LOCK_FILE), "rw").channel.use { channel ->
            channel.lock().use { block(directory) }
        }
    }.getOrDefault(GuardianFencedResult.Ambiguous)

    private fun withLock(
        identity: GenerationFenceIdentity,
        block: (File) -> GuardianFenceDisposition,
    ): GuardianFenceDisposition = runCatching {
        val directory = directory(identity)
        if (!directory.exists() && !directory.mkdirs()) return GuardianFenceDisposition.AMBIGUOUS
        require(directory.isDirectory)
        RandomAccessFile(File(directory, LOCK_FILE), "rw").channel.use { channel ->
            channel.lock().use { block(directory) }
        }
    }.getOrDefault(GuardianFenceDisposition.AMBIGUOUS)

    private fun directory(identity: GenerationFenceIdentity): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.canonicalBytes())
            .joinToString("") { "%02x".format(it) }
        return File(root, digest)
    }

    private fun exactSession(directory: File, identity: GenerationFenceIdentity): Boolean =
        readExact(File(directory, SESSION_MARKER))?.contentEquals(identity.canonicalBytes()) == true

    private fun writeFresh(file: File, bytes: ByteArray): Boolean = runCatching {
        if (file.exists()) return false
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        syncDirectoryBestEffort(checkNotNull(file.parentFile))
        readExact(file)?.contentEquals(bytes) == true
    }.getOrDefault(false)

    private fun readExact(file: File): ByteArray? = runCatching {
        if (!file.exists() || file.length() !in 1..512) return null
        FileInputStream(file).use { it.readBytes() }
    }.getOrNull()

    private companion object {
        const val SESSION_MARKER = "session.marker"
        const val TERMINAL_MARKER = "terminal.marker"
        const val LOCK_FILE = "mutation.lock"
    }
}

internal sealed interface GuardianFencedResult<out T> {
    data class Executed<T>(val value: T) : GuardianFencedResult<T>
    data object Terminal : GuardianFencedResult<Nothing>
    data object Rejected : GuardianFencedResult<Nothing>
    data object Ambiguous : GuardianFencedResult<Nothing>
}

/**
 * A process can die after creating the terminal marker but before the first observer
 * confirms its durable bytes. Retry only that ambiguous fence boundary. Rejected,
 * terminal, and executed results are authoritative and are never replayed.
 */
internal object GuardianFenceAmbiguityRetry {
    const val MAX_RETRIES = 4
    const val RETRY_INTERVAL_MS = 200L

    fun <T> execute(
        sleeper: (Long) -> Unit,
        operation: () -> GuardianFencedResult<T>,
    ): GuardianFencedResult<T> {
        var result = operation()
        var retries = 0
        while (result is GuardianFencedResult.Ambiguous && retries < MAX_RETRIES) {
            sleeper(RETRY_INTERVAL_MS)
            retries += 1
            result = operation()
        }
        return result
    }
}

private fun syncDirectoryBestEffort(directory: File) {
    runCatching { FileInputStream(directory).use { it.fd.sync() } }
}
