package com.gruning.mimir.tweaker

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * The signed, in-memory truth consumed by the detached restore guardian.
 *
 * This deliberately contains no command text, temporary-file path, or public key. A guardian
 * may act only on these typed entries after [RestoreGuardianCrypto] has authenticated this
 * object against an independently pinned verifier key.
 */
internal data class RestoreGuardianEnvelope(
    val schemaVersion: Int = RESTORE_GUARDIAN_ENVELOPE_SCHEMA,
    val helperBuildIdentity: String,
    val tweakerCertificateSha256: String,
    val hardwareFingerprint: String,
    val deviceProfile: String,
    val bootIdentity: String,
    val generation: Long,
    val requestId: String,
    val transactionId: String,
    val taskNonce: String,
    val topologySha256: String,
    val snapshotSha256: String,
    val planSha256: String,
    /** Immutable elapsed-realtime deadline. Heartbeats must repeat, never replace, this value. */
    val hardDeadlineElapsedRealtimeMs: Long,
    /** Literal restore execution order, which is the strict reverse of apply ordinals. */
    val restoreEntries: List<RestoreGuardianRestoreEntry>,
) {
    init {
        require(schemaVersion == RESTORE_GUARDIAN_ENVELOPE_SCHEMA) { "unsupported guardian schema" }
        GuardianEnvelopeValidation.requireToken(helperBuildIdentity, "helper build")
        GuardianEnvelopeValidation.requireSha256(tweakerCertificateSha256, "certificate")
        GuardianEnvelopeValidation.requireToken(hardwareFingerprint, "hardware fingerprint")
        GuardianEnvelopeValidation.requireToken(deviceProfile, "device profile")
        GuardianEnvelopeValidation.requireToken(bootIdentity, "boot identity")
        require(generation > 0) { "generation must be positive" }
        GuardianEnvelopeValidation.requireToken(requestId, "request")
        GuardianEnvelopeValidation.requireToken(transactionId, "transaction")
        GuardianEnvelopeValidation.requireNonce(taskNonce)
        GuardianEnvelopeValidation.requireSha256(topologySha256, "topology")
        GuardianEnvelopeValidation.requireSha256(snapshotSha256, "snapshot")
        GuardianEnvelopeValidation.requireSha256(planSha256, "plan")
        require(hardDeadlineElapsedRealtimeMs > 0) { "hard deadline must be positive" }
        GuardianEnvelopeValidation.requireRestoreOrder(restoreEntries)
    }

    fun canonicalBytes(): ByteArray = GuardianCanonicalCodec.envelope(this)

    fun digestSha256(): String = GuardianCanonicalCodec.sha256(canonicalBytes())
}

/** Expected Stock state plus the finite, typed renderer states that may precede restoration. */
internal sealed interface RestoreGuardianRestoreEntry {
    val applyOrdinal: Int
    val logicalId: String
    val expectedStock: RestoreGuardianStockState
    val rendererAppliedIntermediates: List<Int>

    fun validate()
}

internal data class RestoreGuardianSysfsEntry(
    override val applyOrdinal: Int,
    override val logicalId: String,
    val canonicalSysfsPath: String,
    override val expectedStock: RestoreGuardianStockState.PresentSysfs,
    override val rendererAppliedIntermediates: List<Int>,
) : RestoreGuardianRestoreEntry {
    init { validate() }

    override fun validate() {
        GuardianEnvelopeValidation.requireOrdinal(applyOrdinal)
        GuardianEnvelopeValidation.requireToken(logicalId, "logical id")
        val canonical = SysfsPath.canonicalize(canonicalSysfsPath)
        require(canonical == canonicalSysfsPath) { "noncanonical sysfs path" }
        expectedStock.validate()
        GuardianEnvelopeValidation.requireRendererIntermediates(rendererAppliedIntermediates)
    }
}

/** Captured vendor fan mode existed at Stock, so restore is a typed Settings put. */
internal data class RestoreGuardianFanPutEntry(
    override val applyOrdinal: Int,
    override val logicalId: String = HardwareSnapshot.FAN_LOGICAL_ID,
    override val expectedStock: RestoreGuardianStockState.PresentFanMode,
    override val rendererAppliedIntermediates: List<Int>,
) : RestoreGuardianRestoreEntry {
    init { validate() }

    override fun validate() {
        GuardianEnvelopeValidation.requireOrdinal(applyOrdinal)
        require(logicalId == HardwareSnapshot.FAN_LOGICAL_ID) { "fan logical id mismatch" }
        expectedStock.validate()
        GuardianEnvelopeValidation.requireRendererIntermediates(rendererAppliedIntermediates)
    }
}

/** Stock had no fan_mode, so restore is the sole allowed typed Settings delete. */
internal data class RestoreGuardianFanDeleteEntry(
    override val applyOrdinal: Int,
    override val logicalId: String = HardwareSnapshot.FAN_LOGICAL_ID,
    override val expectedStock: RestoreGuardianStockState.AbsentFanMode = RestoreGuardianStockState.AbsentFanMode,
    override val rendererAppliedIntermediates: List<Int> = emptyList(),
) : RestoreGuardianRestoreEntry {
    init { validate() }

    override fun validate() {
        GuardianEnvelopeValidation.requireOrdinal(applyOrdinal)
        require(logicalId == HardwareSnapshot.FAN_LOGICAL_ID) { "fan logical id mismatch" }
        expectedStock.validate()
        require(rendererAppliedIntermediates.isEmpty()) { "fan delete has no numeric intermediate" }
    }
}

/**
 * The vendor `performance_mode` (a `settings system` key that, unlike every other axis here, SURVIVES
 * A REBOOT and belongs to the player). Carries BOTH values the three-way restore rule needs
 * ([VendorModeOriginPolicy.decideRestore]):
 *
 * - [origin]: the player's mode before Mimir first changed the key -- the honest origin from the
 *   persisted [VendorModeOriginRecord] (never the live read at capture, which after a frozen daemon is
 *   Mimir's own value). It is the "Stock" this entry restores to.
 * - [applied]: the mode Mimir wrote. It is the only live value at which a restore may write [origin];
 *   any other live value is the player's own choice and is left alone.
 *
 * Persistent ([isPersistentGuardianAxis]) so the cross-boot replay scope covers it: after a reboot the
 * key still reads Mimir's value and nothing else will ever put it back.
 *
 * [origin] == [applied] is legal and common -- the plan targets the mode the player is already on
 * (Quiet at stock mode 0), the apply writes nothing and holds no record, but the snapshot still has
 * the node and the envelope must still name an entry for it. Such an entry can only ever be
 * "already at origin" or "the player moved it"; it never asks for a write.
 *
 * The values ride the existing typed slots ([expectedStock] and [rendererAppliedIntermediates]) so
 * the canonical codec, the digest and every generic validator treat this like the fan put entry.
 */
internal data class RestoreGuardianVendorModePutEntry(
    override val applyOrdinal: Int,
    override val logicalId: String = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
    val origin: Int,
    val applied: Int,
) : RestoreGuardianRestoreEntry {
    init { validate() }

    override val expectedStock: RestoreGuardianStockState.PresentVendorMode
        get() = RestoreGuardianStockState.PresentVendorMode(origin)
    override val rendererAppliedIntermediates: List<Int>
        get() = listOf(applied)

    override fun validate() {
        GuardianEnvelopeValidation.requireOrdinal(applyOrdinal)
        require(logicalId == HardwareSnapshot.VENDOR_MODE_LOGICAL_ID) { "vendor mode logical id mismatch" }
        expectedStock.validate()
        require(applied in TuningPlanner.KNOWN_VENDOR_MODES) { "invalid applied vendor mode" }
    }
}

internal sealed interface RestoreGuardianStockState {
    fun validate()

    data class PresentSysfs(
        val value: Int,
        val mode: String,
        val uid: Int,
        val gid: Int,
    ) : RestoreGuardianStockState {
        override fun validate() {
            require(MODE.matches(mode)) { "invalid sysfs mode" }
            require(uid >= 0) { "uid must be nonnegative" }
            require(gid >= 0) { "gid must be nonnegative" }
        }

        private companion object { val MODE = Regex("[0-7]{3,4}") }
    }

    data class PresentFanMode(val value: Int) : RestoreGuardianStockState {
        override fun validate() {
            require(TuningPlanner.isCapturedVendorFanMode(value)) { "invalid captured fan mode" }
        }
    }

    data object AbsentFanMode : RestoreGuardianStockState {
        override fun validate() = Unit
    }

    /** The player's `performance_mode` before Mimir changed it ([RestoreGuardianVendorModePutEntry.origin]). */
    data class PresentVendorMode(val value: Int) : RestoreGuardianStockState {
        override fun validate() {
            require(value in TuningPlanner.KNOWN_VENDOR_MODES) { "invalid captured vendor mode" }
        }
    }
}

/** A signed liveness receipt. The signature covers the complete identity, not a file name. */
internal data class RestoreGuardianHeartbeat(
    val schemaVersion: Int = RESTORE_GUARDIAN_ENVELOPE_SCHEMA,
    val helperBuildIdentity: String,
    val bootIdentity: String,
    val generation: Long,
    val requestId: String,
    val transactionId: String,
    val taskNonce: String,
    val envelopeSha256: String,
    val counter: Long,
    val elapsedRealtimeMs: Long,
    val hardDeadlineElapsedRealtimeMs: Long,
) {
    init {
        require(schemaVersion == RESTORE_GUARDIAN_ENVELOPE_SCHEMA) { "unsupported heartbeat schema" }
        GuardianEnvelopeValidation.requireToken(helperBuildIdentity, "helper build")
        GuardianEnvelopeValidation.requireToken(bootIdentity, "boot identity")
        require(generation > 0) { "generation must be positive" }
        GuardianEnvelopeValidation.requireToken(requestId, "request")
        GuardianEnvelopeValidation.requireToken(transactionId, "transaction")
        GuardianEnvelopeValidation.requireNonce(taskNonce)
        GuardianEnvelopeValidation.requireSha256(envelopeSha256, "envelope")
        require(counter > 0) { "heartbeat counter must be positive" }
        require(elapsedRealtimeMs >= 0) { "heartbeat elapsed realtime must be nonnegative" }
        require(hardDeadlineElapsedRealtimeMs > 0) { "hard deadline must be positive" }
    }

    fun canonicalBytes(): ByteArray = GuardianCanonicalCodec.heartbeat(this)
}

/** Signed READY acknowledgement, needed before an arm gate may advance to APPLYING. */
internal data class RestoreGuardianReadyReceipt(
    val schemaVersion: Int = RESTORE_GUARDIAN_ENVELOPE_SCHEMA,
    val helperBuildIdentity: String,
    val bootIdentity: String,
    val generation: Long,
    val requestId: String,
    val transactionId: String,
    val taskNonce: String,
    val envelopeSha256: String,
    val sessionPublicKey: GuardianSessionPublicKey,
    val issuedElapsedRealtimeMs: Long,
) {
    init {
        require(schemaVersion == RESTORE_GUARDIAN_ENVELOPE_SCHEMA) { "unsupported receipt schema" }
        GuardianEnvelopeValidation.requireToken(helperBuildIdentity, "helper build")
        GuardianEnvelopeValidation.requireToken(bootIdentity, "boot identity")
        require(generation > 0) { "generation must be positive" }
        GuardianEnvelopeValidation.requireToken(requestId, "request")
        GuardianEnvelopeValidation.requireToken(transactionId, "transaction")
        GuardianEnvelopeValidation.requireNonce(taskNonce)
        GuardianEnvelopeValidation.requireSha256(envelopeSha256, "envelope")
        require(issuedElapsedRealtimeMs >= 0) { "receipt elapsed realtime must be nonnegative" }
    }

    fun canonicalBytes(): ByteArray = GuardianCanonicalCodec.readyReceipt(this)
}

internal class SignedRestoreGuardianEnvelope(
    envelope: RestoreGuardianEnvelope,
    signature: ByteArray,
) {
    private val pinnedEnvelope = snapshotGuardianEnvelope(envelope)
    val envelope: RestoreGuardianEnvelope get() = snapshotGuardianEnvelope(pinnedEnvelope)
    private val pinnedSignature = boundedSignature(signature, "envelope")
    val signature: ByteArray get() = pinnedSignature.copyOf()
    fun copy(envelope: RestoreGuardianEnvelope = this.envelope, signature: ByteArray = this.signature) = SignedRestoreGuardianEnvelope(envelope, signature)
}

private fun snapshotGuardianEnvelope(envelope: RestoreGuardianEnvelope): RestoreGuardianEnvelope =
    envelope.copy(restoreEntries = envelope.restoreEntries.map { it.snapshotGuardianEntry() }.toList())

private fun RestoreGuardianRestoreEntry.snapshotGuardianEntry(): RestoreGuardianRestoreEntry = when (this) {
    is RestoreGuardianSysfsEntry -> copy(rendererAppliedIntermediates = rendererAppliedIntermediates.toList())
    is RestoreGuardianFanPutEntry -> copy(rendererAppliedIntermediates = rendererAppliedIntermediates.toList())
    is RestoreGuardianFanDeleteEntry -> copy(rendererAppliedIntermediates = rendererAppliedIntermediates.toList())
    is RestoreGuardianVendorModePutEntry -> copy()
}

internal class SignedRestoreGuardianHeartbeat(
    val heartbeat: RestoreGuardianHeartbeat,
    signature: ByteArray,
) {
    private val pinnedSignature = boundedSignature(signature, "heartbeat")
    val signature: ByteArray get() = pinnedSignature.copyOf()
    fun copy(heartbeat: RestoreGuardianHeartbeat = this.heartbeat, signature: ByteArray = this.signature) = SignedRestoreGuardianHeartbeat(heartbeat, signature)
}

internal class SignedRestoreGuardianReadyReceipt(
    val receipt: RestoreGuardianReadyReceipt,
    signature: ByteArray,
) {
    private val pinnedSignature = boundedSignature(signature, "READY")
    val signature: ByteArray get() = pinnedSignature.copyOf()
    fun copy(receipt: RestoreGuardianReadyReceipt = this.receipt, signature: ByteArray = this.signature) = SignedRestoreGuardianReadyReceipt(receipt, signature)
}

/** App-originated durable acceptance of one channel-authenticated session receipt. */
internal data class RestoreGuardianSessionPin(
    val schemaVersion: Int = RESTORE_GUARDIAN_ENVELOPE_SCHEMA,
    val envelopeSha256: String,
    val sessionKeySha256: String,
    val readyReceiptSha256: String,
    val readyIssuedElapsedRealtimeMs: Long,
    val pinnedElapsedRealtimeMs: Long,
) {
    init {
        require(schemaVersion == RESTORE_GUARDIAN_ENVELOPE_SCHEMA)
        GuardianEnvelopeValidation.requireSha256(envelopeSha256, "envelope")
        GuardianEnvelopeValidation.requireSha256(sessionKeySha256, "session key")
        GuardianEnvelopeValidation.requireSha256(readyReceiptSha256, "READY receipt")
        require(readyIssuedElapsedRealtimeMs >= 0 && pinnedElapsedRealtimeMs >= readyIssuedElapsedRealtimeMs)
    }
    fun canonicalBytes(): ByteArray = GuardianCanonicalCodec.sessionPin(this)
}
internal class SignedRestoreGuardianSessionPin(val pin: RestoreGuardianSessionPin, signature: ByteArray) {
    private val pinnedSignature = boundedSignature(signature, "session pin")
    val signature get() = pinnedSignature.copyOf()
}

internal enum class GuardianTerminalRestoreScope {
    SAME_BOOT_FULL,
    CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK,
}

/** Authenticated evidence that two complete Stock readbacks have settled. */
internal data class RestoreGuardianStockConfirmedReceipt(
    val schemaVersion: Int = RESTORE_GUARDIAN_TERMINAL_RECEIPT_SCHEMA,
    val helperBuildIdentity: String,
    /** Boot that created and app-signed the immutable envelope. */
    val bootIdentity: String,
    /** Boot in which restore/readback actually completed. */
    val recoveryBootIdentity: String = bootIdentity,
    val restoreScope: GuardianTerminalRestoreScope = GuardianTerminalRestoreScope.SAME_BOOT_FULL,
    val generation: Long,
    val requestId: String,
    val transactionId: String,
    val taskNonce: String,
    val envelopeSha256: String,
    val sessionKeySha256: String,
    /** Canonical digest of every captured Stock entry verified by both readbacks. */
    val verifiedStockEntrySetSha256: String = envelopeSha256,
    val firstReadbackElapsedRealtimeMs: Long,
    val secondReadbackElapsedRealtimeMs: Long,
    val issuedElapsedRealtimeMs: Long,
) {
    init {
        require(schemaVersion == RESTORE_GUARDIAN_TERMINAL_RECEIPT_SCHEMA)
        GuardianEnvelopeValidation.requireToken(helperBuildIdentity, "helper build")
        GuardianEnvelopeValidation.requireToken(bootIdentity, "boot identity")
        GuardianEnvelopeValidation.requireToken(recoveryBootIdentity, "recovery boot identity")
        when (restoreScope) {
            GuardianTerminalRestoreScope.SAME_BOOT_FULL -> require(recoveryBootIdentity == bootIdentity)
            GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK ->
                require(recoveryBootIdentity != bootIdentity)
        }
        require(generation > 0); GuardianEnvelopeValidation.requireToken(requestId, "request")
        GuardianEnvelopeValidation.requireToken(transactionId, "transaction"); GuardianEnvelopeValidation.requireNonce(taskNonce)
        GuardianEnvelopeValidation.requireSha256(envelopeSha256, "envelope")
        GuardianEnvelopeValidation.requireSha256(sessionKeySha256, "session key")
        GuardianEnvelopeValidation.requireSha256(verifiedStockEntrySetSha256, "verified Stock entry set")
        require(firstReadbackElapsedRealtimeMs >= 0 && secondReadbackElapsedRealtimeMs >= firstReadbackElapsedRealtimeMs)
        require(issuedElapsedRealtimeMs >= secondReadbackElapsedRealtimeMs)
    }
    fun canonicalBytes(): ByteArray = GuardianCanonicalCodec.stockConfirmedReceipt(this)
    fun digestSha256(): String = GuardianCanonicalCodec.sha256(canonicalBytes())
}

/** Authenticated later exit boundary, chained to one exact canonical Stock receipt. */
internal data class RestoreGuardianExitedReceipt(
    val schemaVersion: Int = RESTORE_GUARDIAN_TERMINAL_RECEIPT_SCHEMA,
    val helperBuildIdentity: String,
    val bootIdentity: String,
    val recoveryBootIdentity: String = bootIdentity,
    val restoreScope: GuardianTerminalRestoreScope = GuardianTerminalRestoreScope.SAME_BOOT_FULL,
    val generation: Long,
    val requestId: String,
    val transactionId: String,
    val taskNonce: String,
    val envelopeSha256: String,
    val sessionKeySha256: String,
    val verifiedStockEntrySetSha256: String = envelopeSha256,
    val stockConfirmedReceiptSha256: String,
    val issuedElapsedRealtimeMs: Long,
) {
    init {
        require(schemaVersion == RESTORE_GUARDIAN_TERMINAL_RECEIPT_SCHEMA)
        GuardianEnvelopeValidation.requireToken(helperBuildIdentity, "helper build")
        GuardianEnvelopeValidation.requireToken(bootIdentity, "boot identity")
        GuardianEnvelopeValidation.requireToken(recoveryBootIdentity, "recovery boot identity")
        when (restoreScope) {
            GuardianTerminalRestoreScope.SAME_BOOT_FULL -> require(recoveryBootIdentity == bootIdentity)
            GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK ->
                require(recoveryBootIdentity != bootIdentity)
        }
        require(generation > 0); GuardianEnvelopeValidation.requireToken(requestId, "request")
        GuardianEnvelopeValidation.requireToken(transactionId, "transaction"); GuardianEnvelopeValidation.requireNonce(taskNonce)
        GuardianEnvelopeValidation.requireSha256(envelopeSha256, "envelope")
        GuardianEnvelopeValidation.requireSha256(sessionKeySha256, "session key")
        GuardianEnvelopeValidation.requireSha256(verifiedStockEntrySetSha256, "verified Stock entry set")
        GuardianEnvelopeValidation.requireSha256(stockConfirmedReceiptSha256, "Stock receipt")
        require(issuedElapsedRealtimeMs >= 0)
    }
    fun canonicalBytes(): ByteArray = GuardianCanonicalCodec.exitedReceipt(this)
}

/** Receipt bytes are never exposed with mutable backing storage. */
internal class SignedRestoreGuardianStockConfirmedReceipt(val receipt: RestoreGuardianStockConfirmedReceipt, signature: ByteArray) {
    private val pinnedSignature = boundedSignature(signature, "Stock")
    val signature: ByteArray get() = pinnedSignature.copyOf()
}
internal class SignedRestoreGuardianExitedReceipt(val receipt: RestoreGuardianExitedReceipt, signature: ByteArray) {
    private val pinnedSignature = boundedSignature(signature, "EXITED")
    val signature: ByteArray get() = pinnedSignature.copyOf()
}

private fun boundedSignature(signature: ByteArray, kind: String): ByteArray =
    signature.copyOf().also { require(it.size in 1..512) { "$kind signature must be 1..512 bytes" } }

internal enum class GuardianTerminalState { WATCHING, RESTORING, TERMINAL }

internal data class GuardianHeartbeatState(
    val terminalState: GuardianTerminalState = GuardianTerminalState.WATCHING,
    val lastCounter: Long = 0,
    val lastElapsedRealtimeMs: Long = -1,
)

internal enum class HeartbeatDecision {
    ACCEPT,
    TERMINAL,
    IDENTITY_MISMATCH,
    REPLAY_OR_REORDER,
    CLOCK_REGRESSION,
    DEADLINE_MISMATCH,
    EXPIRED,
    INVALID_SIGNATURE,
}

internal object RestoreGuardianHeartbeatPolicy {
    /** The caller must transition to RESTORING for every non-[HeartbeatDecision.ACCEPT] result. */
    fun evaluate(
        envelope: RestoreGuardianEnvelope,
        current: GuardianHeartbeatState,
        heartbeat: RestoreGuardianHeartbeat,
        nowElapsedRealtimeMs: Long,
        signatureValid: Boolean,
    ): HeartbeatDecision = when {
        current.terminalState != GuardianTerminalState.WATCHING -> HeartbeatDecision.TERMINAL
        !signatureValid -> HeartbeatDecision.INVALID_SIGNATURE
        nowElapsedRealtimeMs >= envelope.hardDeadlineElapsedRealtimeMs -> HeartbeatDecision.EXPIRED
        heartbeat.helperBuildIdentity != envelope.helperBuildIdentity ||
            heartbeat.bootIdentity != envelope.bootIdentity ||
            heartbeat.generation != envelope.generation ||
            heartbeat.requestId != envelope.requestId ||
            heartbeat.transactionId != envelope.transactionId ||
            heartbeat.taskNonce != envelope.taskNonce ||
            heartbeat.envelopeSha256 != envelope.digestSha256() -> HeartbeatDecision.IDENTITY_MISMATCH
        heartbeat.hardDeadlineElapsedRealtimeMs != envelope.hardDeadlineElapsedRealtimeMs -> HeartbeatDecision.DEADLINE_MISMATCH
        heartbeat.counter <= current.lastCounter -> HeartbeatDecision.REPLAY_OR_REORDER
        heartbeat.elapsedRealtimeMs <= current.lastElapsedRealtimeMs -> HeartbeatDecision.CLOCK_REGRESSION
        heartbeat.elapsedRealtimeMs > nowElapsedRealtimeMs -> HeartbeatDecision.CLOCK_REGRESSION
        else -> HeartbeatDecision.ACCEPT
    }

    fun advanced(current: GuardianHeartbeatState, heartbeat: RestoreGuardianHeartbeat): GuardianHeartbeatState =
        current.copy(lastCounter = heartbeat.counter, lastElapsedRealtimeMs = heartbeat.elapsedRealtimeMs)
}

internal object GuardianEnvelopeValidation {
    /** Mirrors the durable MGRA decoder bound; no caller may construct an unpersistable arm. */
    const val MAX_RESTORE_ENTRIES = 32
    private val TOKEN = Regex("[A-Za-z0-9._:-]{1,256}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val NONCE = Regex("[0-9a-f]{32,128}")

    fun requireToken(value: String, label: String) {
        require(TOKEN.matches(value)) { "invalid $label" }
    }

    fun requireSha256(value: String, label: String) {
        require(SHA256.matches(value)) { "invalid $label digest" }
    }

    fun requireNonce(value: String) {
        require(NONCE.matches(value)) { "invalid task nonce" }
    }

    fun requireOrdinal(value: Int) {
        require(value >= 0) { "apply ordinal must be nonnegative" }
    }

    fun requireRendererIntermediates(values: List<Int>) {
        require(values.isNotEmpty()) { "renderer intermediate state missing" }
        require(values.size <= 32) { "too many renderer intermediate states" }
    }

    fun requireRestoreOrder(entries: List<RestoreGuardianRestoreEntry>) {
        require(entries.isNotEmpty()) { "restore entries missing" }
        require(entries.size <= MAX_RESTORE_ENTRIES) { "too many restore entries" }
        val logicalIds = HashSet<String>()
        val ordinals = HashSet<Int>()
        entries.forEach { entry ->
            entry.validate()
            require(logicalIds.add(entry.logicalId)) { "duplicate restore logical id" }
            require(ordinals.add(entry.applyOrdinal)) { "duplicate apply ordinal" }
        }
        fun strictlyReverse(list: List<RestoreGuardianRestoreEntry>): Boolean =
            list.zipWithNext().all { (left, right) -> left.applyOrdinal > right.applyOrdinal }
        val strictReverse = strictlyReverse(entries)
        // The vendor performance mode sits immediately BEFORE the fan put (RestoreMutationOrder,
        // policy 2026-10-13): with the mode applied first and the fan second, plain reversal
        // would restore the fan before the mode and SystemUI's coupled rewrite would undo it. The
        // shape is exact: strict reverse everywhere except that one vendor-mode entry, which has
        // moved from behind the fan to just in front of it. (A tw06 envelope, applied fan -> mode,
        // already reverses to mode-before-fan and stays strict reverse.)
        val vendorBeforeFan = run {
            // Pinned to the one producer shape: the renderer applies the mode at ordinal 0 and the fan
            // at ordinal 1 (vendor + cooling + compute), and only a fan Mimir does NOT duty-drive is
            // restored behind the mode this way -- a duty-owned fan takes the release-first shape below.
            // By logical id -- a fan PUT or a fan DELETE -- exactly as RestoreMutationOrder moves it.
            val fanIndex = entries.indexOfFirst { it.logicalId == HardwareSnapshot.FAN_LOGICAL_ID }
            val vendorIndex = entries.indexOfFirst { it is RestoreGuardianVendorModePutEntry }
            fanIndex > 0 && vendorIndex == fanIndex - 1 &&
                entries[vendorIndex].applyOrdinal == 0 &&
                entries[fanIndex].applyOrdinal == 1 &&
                !entries[fanIndex].mayOwnManualFan() &&
                strictlyReverse(entries.filterIndexed { index, _ -> index != vendorIndex })
        }
        // Fan-first settlement covers a single duty-owned target or the exact signed Smart /
        // Manual ownership pair. Arbitrary multi-target lists do not gain this exception, and the
        // fan's ordinal is bounded to what a renderer can produce: 0 (tw06's fan-first apply, or no
        // vendor mode in the plan), or 1 with the vendor mode at 0 behind it (since 2026-10-13).
        // Every other entry was applied AFTER the fan and the rest must be strictly reverse on its own.
        val manualFanFirst = entries.firstOrNull().let { first ->
            val rest = entries.drop(1)
            first is RestoreGuardianFanPutEntry &&
                first.mayOwnManualFan() &&
                strictlyReverse(rest) &&
                when (first.applyOrdinal) {
                    0 -> rest.all { it.applyOrdinal > 0 }
                    1 -> rest.any { it is RestoreGuardianVendorModePutEntry && it.applyOrdinal == 0 } &&
                        rest.all { it.applyOrdinal > 1 || (it is RestoreGuardianVendorModePutEntry && it.applyOrdinal == 0) }
                    else -> false
                }
        }
        require(strictReverse || vendorBeforeFan || manualFanFirst) {
            "restore entries must be reverse apply order, vendor-mode-before-fan order or " +
                "authenticated manual-fan-first order"
        }
    }
}

/** Length-prefixed binary canonicalization prevents delimiter ambiguity and keeps SHA/signature bytes stable. */
internal object GuardianCanonicalCodec {
    private const val ENVELOPE_TAG = "MIMIR_RESTORE_GUARDIAN_ENVELOPE"
    private const val HEARTBEAT_TAG = "MIMIR_RESTORE_GUARDIAN_HEARTBEAT"
    private const val READY_TAG = "MIMIR_RESTORE_GUARDIAN_READY"
    private const val STOCK_CONFIRMED_TAG = "MIMIR_RESTORE_GUARDIAN_STOCK_CONFIRMED"
    private const val EXITED_TAG = "MIMIR_RESTORE_GUARDIAN_EXITED"
    private const val SESSION_PIN_TAG = "MIMIR_RESTORE_GUARDIAN_SESSION_PIN"

    fun envelope(value: RestoreGuardianEnvelope): ByteArray = canonical { out ->
        out.string(ENVELOPE_TAG); out.int(value.schemaVersion); out.string(value.helperBuildIdentity)
        out.string(value.tweakerCertificateSha256); out.string(value.hardwareFingerprint); out.string(value.deviceProfile)
        out.string(value.bootIdentity); out.long(value.generation); out.string(value.requestId); out.string(value.transactionId)
        out.string(value.taskNonce); out.string(value.topologySha256); out.string(value.snapshotSha256); out.string(value.planSha256)
        out.long(value.hardDeadlineElapsedRealtimeMs); out.int(value.restoreEntries.size)
        value.restoreEntries.forEach { entry -> out.restoreEntry(entry) }
    }

    fun heartbeat(value: RestoreGuardianHeartbeat): ByteArray = canonical { out ->
        out.string(HEARTBEAT_TAG); out.int(value.schemaVersion); out.string(value.helperBuildIdentity); out.string(value.bootIdentity)
        out.long(value.generation); out.string(value.requestId); out.string(value.transactionId); out.string(value.taskNonce)
        out.string(value.envelopeSha256); out.long(value.counter); out.long(value.elapsedRealtimeMs); out.long(value.hardDeadlineElapsedRealtimeMs)
    }

    fun readyReceipt(value: RestoreGuardianReadyReceipt): ByteArray = canonical { out ->
        out.string(READY_TAG); out.int(value.schemaVersion); out.string(value.helperBuildIdentity); out.string(value.bootIdentity)
        out.long(value.generation); out.string(value.requestId); out.string(value.transactionId); out.string(value.taskNonce)
        out.string(value.envelopeSha256); out.bytes(value.sessionPublicKey.sec1Bytes()); out.long(value.issuedElapsedRealtimeMs)
    }

    fun stockConfirmedReceipt(value: RestoreGuardianStockConfirmedReceipt): ByteArray = canonical { out ->
        out.string(STOCK_CONFIRMED_TAG); out.int(value.schemaVersion); out.string(value.helperBuildIdentity); out.string(value.bootIdentity)
        out.string(value.recoveryBootIdentity); out.string(value.restoreScope.name)
        out.long(value.generation); out.string(value.requestId); out.string(value.transactionId); out.string(value.taskNonce)
        out.string(value.envelopeSha256); out.string(value.sessionKeySha256); out.string(value.verifiedStockEntrySetSha256)
        out.long(value.firstReadbackElapsedRealtimeMs); out.long(value.secondReadbackElapsedRealtimeMs); out.long(value.issuedElapsedRealtimeMs)
    }

    fun exitedReceipt(value: RestoreGuardianExitedReceipt): ByteArray = canonical { out ->
        out.string(EXITED_TAG); out.int(value.schemaVersion); out.string(value.helperBuildIdentity); out.string(value.bootIdentity)
        out.string(value.recoveryBootIdentity); out.string(value.restoreScope.name)
        out.long(value.generation); out.string(value.requestId); out.string(value.transactionId); out.string(value.taskNonce)
        out.string(value.envelopeSha256); out.string(value.sessionKeySha256); out.string(value.verifiedStockEntrySetSha256)
        out.string(value.stockConfirmedReceiptSha256); out.long(value.issuedElapsedRealtimeMs)
    }

    fun restoreEntrySet(entries: List<RestoreGuardianRestoreEntry>): ByteArray = canonical { out ->
        out.string("MIMIR_RESTORE_GUARDIAN_VERIFIED_STOCK_ENTRY_SET")
        out.int(entries.size)
        entries.forEach(out::restoreEntry)
    }

    fun sessionPin(value: RestoreGuardianSessionPin): ByteArray = canonical { out ->
        out.string(SESSION_PIN_TAG); out.int(value.schemaVersion); out.string(value.envelopeSha256)
        out.string(value.sessionKeySha256); out.string(value.readyReceiptSha256)
        out.long(value.readyIssuedElapsedRealtimeMs); out.long(value.pinnedElapsedRealtimeMs)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun canonical(block: (CanonicalWriter) -> Unit): ByteArray {
        val sink = ByteArrayOutputStream()
        DataOutputStream(sink).use { stream -> block(CanonicalWriter(stream)) }
        return sink.toByteArray()
    }

    private class CanonicalWriter(private val out: DataOutputStream) {
        fun int(value: Int) = out.writeInt(value)
        fun long(value: Long) = out.writeLong(value)
        fun string(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); int(bytes.size); out.write(bytes) }
        fun bytes(value: ByteArray) { int(value.size); out.write(value) }
        fun restoreEntry(entry: RestoreGuardianRestoreEntry) {
            when (entry) {
                is RestoreGuardianSysfsEntry -> { string("sysfs"); common(entry); string(entry.canonicalSysfsPath); stock(entry.expectedStock) }
                is RestoreGuardianFanPutEntry -> { string("fan_put"); common(entry); stock(entry.expectedStock) }
                is RestoreGuardianFanDeleteEntry -> { string("fan_delete"); common(entry); stock(entry.expectedStock) }
                is RestoreGuardianVendorModePutEntry -> { string("vendor_put"); common(entry); stock(entry.expectedStock) }
            }
        }
        private fun common(entry: RestoreGuardianRestoreEntry) {
            int(entry.applyOrdinal); string(entry.logicalId); int(entry.rendererAppliedIntermediates.size)
            entry.rendererAppliedIntermediates.forEach(::int)
        }
        private fun stock(stock: RestoreGuardianStockState) = when (stock) {
            is RestoreGuardianStockState.PresentSysfs -> { string("present_sysfs"); int(stock.value); string(stock.mode); int(stock.uid); int(stock.gid) }
            is RestoreGuardianStockState.PresentFanMode -> { string("present_fan"); int(stock.value) }
            RestoreGuardianStockState.AbsentFanMode -> string("absent_fan")
            is RestoreGuardianStockState.PresentVendorMode -> { string("present_vendor"); int(stock.value) }
        }
    }
}

internal const val RESTORE_GUARDIAN_ENVELOPE_SCHEMA = 2
internal const val RESTORE_GUARDIAN_TERMINAL_RECEIPT_SCHEMA = 3

internal object GuardianRestoreEntrySetDigest {
    fun of(entries: List<RestoreGuardianRestoreEntry>): String {
        GuardianEnvelopeValidation.requireRestoreOrder(entries)
        return GuardianCanonicalCodec.sha256(GuardianCanonicalCodec.restoreEntrySet(entries))
    }
}

/**
 * Which entries a given terminal restore scope covers -- the ONE definition shared by every
 * producer and verifier of `verifiedStockEntrySetSha256`, so the signed digest and its
 * recomputation can never independently drift.
 *
 * Found stranding the Odin, 2026-09-05, device-proved with a temporary probe before this
 * fix: [RestoreGuardianRunnerEngine.confirmStock] fed `verifiedStockEntrySetSha256` a digest of
 * ONLY the axes a cross-boot restore was permitted to touch (- a
 * volatile axis is reinitialised by the kernel itself, so the boot already discharged it and
 * confirming it is neither possible nor meaningful). `RestoreGuardianCrypto.terminalScopeRefusal`
 * recomputed the SAME field over the complete, unfiltered envelope regardless of scope. The two
 * never had a chance to agree: a cross-boot receipt digesting 1 persistent entry was compared
 * against a fresh digest of all 4 captured entries. Live values from the strand: receipt digest
 * `5352b46f…` equalled a fresh digest of the persistent-only entries and never the full-set digest
 * `0659f867…`.
 *
 * SAME_BOOT_FULL covers the complete captured set -- on that path [confirmStock] never filters,
 * so this is a no-op and the two scopes cannot diverge for same-boot restores, which is exactly
 * why this bug was invisible until a device reboot happened mid-restore.
 * CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK covers only the persistent axes.
 */
internal fun GuardianTerminalRestoreScope.eligibleRestoreEntries(
    entries: List<RestoreGuardianRestoreEntry>,
): List<RestoreGuardianRestoreEntry> = when (this) {
    GuardianTerminalRestoreScope.SAME_BOOT_FULL -> entries
    GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK ->
        entries.filter { it.isPersistentGuardianAxis() }
}
