package com.gruning.mimir.tweaker

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.PublicKey

/**
 * Authentication seam for the durable transaction journal. A SHA digest inside the journal is
 * only an integrity value; this codec accepts a record only after a signature over its complete
 * encoded payload has verified against a separately provisioned, pinned key.
 *
 * The live journal routes through this codec and the device-local direct-boot key authority.
 * Unsigned active records are deliberately rejected rather than migrated or blessed in place.
 */
/** Log tag for journal-authentication failures. Android caps tags at 23 characters. */
private const val AUTH_TAG = "MimirJournalAuth"

internal fun interface TransactionJournalAuthenticator {
    fun sign(canonicalRecord: ByteArray): ByteArray
}

internal fun interface TransactionJournalAuthenticationVerifier {
    fun verify(canonicalRecord: ByteArray, signature: ByteArray): Boolean
}

internal data class TransactionJournalExpectedIdentity(
    val bootIdentity: String,
    val generation: Long,
    val requestId: String,
    val transactionId: String,
) {
    init {
        GuardianEnvelopeValidation.requireToken(bootIdentity, "boot identity")
        require(generation > 0) { "generation must be positive" }
        GuardianEnvelopeValidation.requireToken(requestId, "request")
        GuardianEnvelopeValidation.requireToken(transactionId, "transaction")
    }

    fun matches(record: TransactionJournalRecord): Boolean =
        bootIdentity == record.bootIdentity &&
            generation == record.generation &&
            requestId == record.requestId &&
            transactionId == record.transactionId
}

internal data class SignedTransactionJournalRecord(
    val journalSchemaVersion: Int,
    val keyIdentity: String,
    /** Exact UTF-8 payload consumed by the existing journal decoder; no reconstructed JSON is signed. */
    val encodedJournalPayload: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(
            journalSchemaVersion == TRANSACTION_JOURNAL_SCHEMA ||
                journalSchemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5 ||
                journalSchemaVersion == TRANSACTION_JOURNAL_SCHEMA_V3 ||
                journalSchemaVersion == TRANSACTION_JOURNAL_SCHEMA_V2,
        ) { "unsupported authenticated journal schema" }
        JournalAuthenticationValidation.requireKeyIdentity(keyIdentity)
        require(encodedJournalPayload.isNotEmpty()) { "journal payload missing" }
        require(signature.isNotEmpty()) { "journal signature missing" }
    }

    fun canonicalBytes(): ByteArray = AuthenticatedTransactionJournalCodec.canonicalBytes(
        journalSchemaVersion,
        keyIdentity,
        encodedJournalPayload,
    )
}

/** Strict, length-delimited storage codec. Any parse, trust, signature, or identity failure is Corrupt. */
internal object AuthenticatedTransactionJournalCodec {
    private const val MAGIC = 0x4D544A41 // MTJA
    private const val VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 256 * 1024
    private const val MAX_SIGNATURE_BYTES = 512
    private const val MAX_RECORD_BYTES = MAX_PAYLOAD_BYTES + MAX_SIGNATURE_BYTES + 1024
    private const val SIGNATURE_TAG = "MIMIR_AUTHENTICATED_TRANSACTION_JOURNAL"

    fun encode(signed: SignedTransactionJournalRecord): ByteArray = ByteArrayOutputStream().also { sink ->
        DataOutputStream(sink).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(signed.journalSchemaVersion)
            out.string(signed.keyIdentity)
            out.bytes(signed.encodedJournalPayload)
            out.bytes(signed.signature)
        }
    }.toByteArray().also { require(it.size <= MAX_RECORD_BYTES) { "journal record too large" } }

    fun decodeAndVerify(
        bytes: ByteArray,
        expected: TransactionJournalExpectedIdentity,
        pinned: PinnedTransactionJournalVerifier,
    ): JournalLoadResult {
        val loaded = decodeAndVerify(bytes, pinned)
        if (loaded !is JournalLoadResult.Loaded) return loaded
        return if (expected.matches(loaded.record)) {
            loaded
        } else {
            JournalLoadResult.Corrupt("Authenticated journal rejected: replayed or wrong journal identity")
        }
    }

    /**
     * Verifies the complete MTJA envelope before exposing the decoded inner record.
     * Callers that own additional live identity (boot/generation/request) must bind it
     * after this boundary; unverified payload bytes never become journal truth.
     */
    fun decodeAndVerify(
        bytes: ByteArray,
        pinned: PinnedTransactionJournalVerifier,
    ): JournalLoadResult = runCatching {
        require(bytes.size in 1..MAX_RECORD_BYTES) { "journal record size invalid" }
        val signed = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION) { "unknown journal record" }
            val schema = input.readInt()
            val keyIdentity = input.string(128)
            val payload = input.bytes(MAX_PAYLOAD_BYTES)
            val signature = input.bytes(MAX_SIGNATURE_BYTES)
            require(input.read() == -1) { "trailing journal record data" }
            SignedTransactionJournalRecord(schema, keyIdentity, payload, signature)
        }
        require(pinned.matchesKeyIdentity(signed.keyIdentity)) { "wrong journal key identity" }
        require(pinned.verifier().verify(signed.canonicalBytes(), signed.signature)) { "journal signature invalid" }
        val payload = signed.encodedJournalPayload.toString(Charsets.UTF_8)
        require(payload.toByteArray(Charsets.UTF_8).contentEquals(signed.encodedJournalPayload)) { "journal payload is not UTF-8" }
        val loaded = AtomicFileTransactionJournal.decodeJournal(payload)
        require(loaded is JournalLoadResult.Loaded) { "journal payload invalid" }
        require(loaded.record.schemaVersion == signed.journalSchemaVersion) { "journal schema mismatch" }
        loaded
    }.getOrElse { JournalLoadResult.Corrupt("Authenticated journal rejected: ${it.message ?: "invalid"}") }

    internal fun canonicalBytes(schema: Int, keyIdentity: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { out ->
                out.string(SIGNATURE_TAG)
                out.writeInt(VERSION)
                out.writeInt(schema)
                out.string(keyIdentity)
                out.bytes(payload)
            }
        }.toByteArray()

    private fun DataOutputStream.bytes(value: ByteArray) {
        require(value.isNotEmpty()) { "empty binary field" }
        writeInt(value.size)
        write(value)
    }

    private fun DataOutputStream.string(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
    private fun DataInputStream.bytes(max: Int): ByteArray {
        val size = readInt()
        require(size in 1..max) { "binary field size invalid" }
        return ByteArray(size).also(::readFully)
    }
    private fun DataInputStream.string(max: Int): String = bytes(max).toString(Charsets.UTF_8).also {
        require(it.toByteArray(Charsets.UTF_8).size <= max) { "string field invalid" }
    }
}

/**
 * Narrow persistence adapter used by [AtomicFileTransactionJournal]. It has no
 * unsigned fallback: a non-empty active record either verifies through the
 * device-local direct-boot authority or loads as [JournalLoadResult.Corrupt].
 */
internal interface TransactionJournalAuthentication {
    fun protect(encodedJournalPayload: ByteArray): ByteArray?
    fun verifyAndDecode(authenticatedRecord: ByteArray): JournalLoadResult
}

/**
 * Device-local app-origin authentication for the live journal. Reading never
 * provisions a missing key, so an unsigned/legacy active journal cannot be
 * silently blessed during upgrade. Fresh provisioning is allowed only while
 * creating a new signed record, before PREPARED can be reported durable.
 */
internal class DirectBootTransactionJournalAuthentication internal constructor(
    private val inspectAuthority: () -> DirectBootKeyAuthorityTruth,
    private val provisionFreshAuthority: () -> DirectBootKeyAuthorityTruth,
    private val liveBootIdentity: () -> String?,
) : TransactionJournalAuthentication {
    constructor(
        authority: DirectBootKeyAuthority,
        liveBootIdentity: () -> String?,
    ) : this(
        inspectAuthority = authority::inspect,
        provisionFreshAuthority = authority::provisionFresh,
        liveBootIdentity = liveBootIdentity,
    )

    override fun protect(encodedJournalPayload: ByteArray): ByteArray? = runCatching {
        val truth = inspectAuthority()
        val resolved = when (truth) {
            DirectBootKeyAuthorityTruth.Fresh -> provisionFreshAuthority()
            else -> truth
        }
        val available = resolved as? DirectBootKeyAuthorityTruth.Available
            ?: return reportUnprotectable(describe(resolved))
        AuthenticatedTransactionJournalCodec.encode(
            available.signJournal(encodedJournalPayload),
        )
    }.getOrElse { error ->
        // signJournal goes through AndroidKeyStore, so a sick KeyMint HAL lands here.
        reportUnprotectable("signing threw: ${error.javaClass.simpleName}: ${error.message}")
    }

    /**
     * A failure here surfaces to the caller as a bare `false` from `TransactionJournal.persist`,
     * which for a derate becomes THERMAL_DERATE_JOURNAL_PERSIST_FAILED -- a code shared with the
     * projection guard and with file I/O. The reason was previously discarded at this exact point
     * (`as? Available ?: return null`), leaving a keystore outage indistinguishable from the other
     * two in the field, on a Production build whose journal adb cannot read without root. Logging
     * it costs nothing and is the only diagnosis that survives.
     */
    private fun reportUnprotectable(reason: String): ByteArray? {
        runCatching { android.util.Log.w(AUTH_TAG, "journal payload not protected: $reason") }
        return null
    }

    private fun describe(truth: DirectBootKeyAuthorityTruth): String = when (truth) {
        is DirectBootKeyAuthorityTruth.Unavailable -> "authority unavailable: ${truth.reason}"
        is DirectBootKeyAuthorityTruth.Corrupt -> "authority corrupt: ${truth.reason}"
        is DirectBootKeyAuthorityTruth.CertificateChanged -> "authority signing identity changed: ${truth.reason}"
        DirectBootKeyAuthorityTruth.Fresh -> "authority still Fresh after provisioning"
        is DirectBootKeyAuthorityTruth.Available -> "authority available"
    }

    override fun verifyAndDecode(authenticatedRecord: ByteArray): JournalLoadResult {
        val available = when (val truth = runCatching(inspectAuthority).getOrNull()) {
            is DirectBootKeyAuthorityTruth.Available -> truth
            DirectBootKeyAuthorityTruth.Fresh ->
                return JournalLoadResult.Corrupt("Authenticated journal key is not provisioned")
            is DirectBootKeyAuthorityTruth.Unavailable ->
                return JournalLoadResult.Corrupt("Authenticated journal key unavailable: ${truth.reason}")
            is DirectBootKeyAuthorityTruth.Corrupt ->
                return JournalLoadResult.Corrupt("Authenticated journal key corrupt: ${truth.reason}")
            is DirectBootKeyAuthorityTruth.CertificateChanged ->
                // Distinct from Corrupt above on purpose (DirectBootKeyAuthorityTruth.CertificateChanged
                // KDoc): a re-signed install is not evidence the journal is corrupt, only that a
                // different certificate produced this install. Behaviour is identical either way --
                // the journal is still unreadable under this authority -- only the reason is legible.
                return JournalLoadResult.Corrupt("Authenticated journal signing identity changed: ${truth.reason}")
            null -> return JournalLoadResult.Corrupt("Authenticated journal key inspection failed")
        }
        val loaded = AuthenticatedTransactionJournalCodec.decodeAndVerify(
            authenticatedRecord,
            available.journalVerifier(),
        )
        if (loaded !is JournalLoadResult.Loaded) return loaded
        val boot = runCatching { liveBootIdentity()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return JournalLoadResult.Corrupt("Live boot identity unavailable")
        return if (
            loaded.record.bootIdentity == boot ||
            loaded.record.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5
        ) {
            loaded
        } else {
            JournalLoadResult.Corrupt("Authenticated journal boot identity mismatch")
        }
    }
}

/** Every production journal constructor routes through this fail-closed factory. */
internal fun createAuthenticatedTransactionJournal(context: Context): TransactionJournal {
    val protectedContext = context.applicationContext.createDeviceProtectedStorageContext()
    val authority = DirectBootKeyAuthority(protectedContext)
    val authentication = DirectBootTransactionJournalAuthentication(
        authority = authority,
        liveBootIdentity = {
            runCatching {
                File(LIVE_BOOT_ID_PATH).readText().trim().takeIf(String::isNotEmpty)
            }.getOrNull()
        },
    )
    return AtomicFileTransactionJournal(
        context = protectedContext,
        authentication = authentication,
    )
}

private const val LIVE_BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"

internal object JournalAuthenticationValidation {
    private val KEY_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    fun requireKeyIdentity(value: String) = require(KEY_ID.matches(value)) { "invalid journal key identity" }
}
