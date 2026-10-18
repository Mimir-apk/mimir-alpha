package com.gruning.mimir.tweaker

import java.util.Collections

/**
 * Inert arm-to-READY admission seam.  This class deliberately has no hardware-command port:
 * a future coordinator can obtain [GuardianArmAdmission.Ready] only after all of these gates
 * have passed, but this slice cannot apply or restore a profile.
 */
internal class RestoreGuardianController(
    private val armStore: RestoreGuardianArmStore,
    private val pinnedVerifier: PinnedRestoreGuardianVerifier,
    private val apkAuthority: PinnedTweakerApkAuthority,
    private val liveBootIdentity: () -> String?,
    private val elapsedRealtimeMs: () -> Long,
    private val installedApk: GuardianInstalledApkPort,
    private val stageDispatcher: GuardianSynchronousDispatcher,
    private val guardianDispatcher: GuardianDetachedDispatcher,
    private val readyReceipts: GuardianReadyReceiptPort,
    private val readyChannelExpectation: GuardianReadyChannelExpectation,
    private val heartbeatDispatcher: GuardianHeartbeatDispatchPort,
    private val terminalReceipts: GuardianTerminalReceiptPort = GuardianTerminalReceiptPort { GuardianTerminalLoad.Unavailable },
) {
    private var active: ActiveGuardianReady? = null
    private var arming = false
    /** This is in-memory only; publication never changes durable arm truth or the hard deadline. */
    private var heartbeatState = GuardianHeartbeatState()

    /**
     * Arms exactly once.  No dispatch happens before signature, exact identity, boot, deadline,
     * and PackageManager-derived installed-APK authority are all accepted.
     */
    @Synchronized
    fun armToReady(
        signed: SignedRestoreGuardianEnvelope,
        expected: RestoreGuardianExpectedIdentity,
    ): GuardianArmAdmission {
        if (arming || active != null) return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.CONCURRENT_ARM)
        val preflight = runCatching {
            GuardianArmPreflight(signed.envelope, elapsedRealtimeMs(), installedApk.resolve())
        }.getOrElse { return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.PREARM_PORT_FAILURE) }
        val envelope = preflight.envelope
        val now = preflight.nowElapsedRealtimeMs
        if (!expected.matches(envelope)) return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.IDENTITY_MISMATCH)
        if (!runCatching { RestoreGuardianCrypto.verifyEnvelope(signed, pinnedVerifier) }.getOrDefault(false)) return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.INVALID_ENVELOPE_SIGNATURE)
        if (runCatching { liveBootIdentity()?.trim() }.getOrNull() != envelope.bootIdentity) return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.BOOT_MISMATCH)
        if (now < 0 || now >= envelope.hardDeadlineElapsedRealtimeMs) return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.DEADLINE_EXPIRED)

        val source = preflight.source ?: return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.INSTALLED_APK_UNAVAILABLE)
        if (!apkAuthority.accepts(source) || source.actualSigningCertificateSha256 != envelope.tweakerCertificateSha256) {
            return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.INSTALLED_APK_UNTRUSTED)
        }
        val stage = runCatching { RestoreGuardianDirectLauncher.renderOrNull(source, apkAuthority, RestoreGuardianRunnerMode.STAGE) }.getOrNull()
            ?: return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.STAGE_RENDER_REJECTED)
        val watch = runCatching { RestoreGuardianDirectLauncher.renderOrNull(source, apkAuthority, RestoreGuardianRunnerMode.GUARDIAN_WATCH) }.getOrNull()
            ?: return GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.GUARDIAN_RENDER_REJECTED)

        arming = true
        try {
            if (runCatching { armStore.arm(signed) }.getOrNull() != GuardianArmStoreWrite.Armed) {
                // A throwing arm implementation may have durably staged partial truth.
                return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.ARM_STORE_AMBIGUOUS)
            }
            val loaded = runCatching { armStore.load(expected) }.getOrNull()
            if (loaded !is GuardianArmStoreLoad.LoadedStaged || !loaded.signed.envelope.canonicalBytes().contentEquals(envelope.canonicalBytes())) {
                return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.ARM_RECORD_UNREADABLE)
            }

            when (runCatching { stageDispatcher.dispatch(stage) }.getOrNull()) {
                GuardianDispatchResult.CONFIRMED -> Unit
                GuardianDispatchResult.REJECTED -> {
                    // A synchronous rejection proves this stage was never delivered; exact clear is safe.
                    return if (runCatching { armStore.clear() }.getOrDefault(false)) {
                        GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.STAGE_DISPATCH_REJECTED)
                    } else {
                        GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.ARM_RECORD_UNREADABLE)
                    }
                }
                GuardianDispatchResult.AMBIGUOUS, null -> return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.STAGE_DISPATCH_AMBIGUOUS)
            }
            when (runCatching { guardianDispatcher.dispatch(watch) }.getOrNull()) {
                GuardianDispatchResult.CONFIRMED -> Unit
                GuardianDispatchResult.REJECTED, GuardianDispatchResult.AMBIGUOUS, null -> {
                    // Stage already executed. Without an authenticated cleanup acknowledgement, retain truth.
                    return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.GUARDIAN_LAUNCH_AMBIGUOUS)
                }
            }

            val readyDeadline = minOf(envelope.hardDeadlineElapsedRealtimeMs, saturatingAdd(now, READY_TIMEOUT_MS))
            val ready = runCatching { readyReceipts.loadUntil(readyDeadline) }.getOrNull()
            if (ready !is GuardianReadyLoad.Received) return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.READY_UNCONFIRMED)
            val receiptNow = runCatching { elapsedRealtimeMs() }.getOrNull()
            if (!GuardianReadyChannelPolicy.matches(ready.evidence, readyChannelExpectation)) {
                return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.READY_CHANNEL_REJECTED)
            }
            val reflectedAppKey = runCatching {
                pinnedVerifier.matchesSessionSec1(ready.signed.receipt.sessionPublicKey.sec1Bytes())
            }.getOrDefault(true)
            if (reflectedAppKey) return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.READY_KEY_REJECTED)
            val exactReady =
                receiptNow != null && receiptNow >= 0 && receiptNow < readyDeadline &&
                    runCatching { liveBootIdentity()?.trim() }.getOrNull() == envelope.bootIdentity &&
                    runCatching { RestoreGuardianCrypto.verifyReadyReceipt(ready.signed, envelope) }.getOrDefault(false) &&
                    RestoreGuardianCrypto.receiptMatches(envelope, ready.signed.receipt) &&
                    ready.signed.receipt.issuedElapsedRealtimeMs in now..receiptNow
            if (!exactReady) return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.READY_UNCONFIRMED)

            // A store exception has no authoritative disposition after both launches. Retain the
            // staged record and force a later reload instead of letting an exception escape.
            when (runCatching {
                armStore.pinSessionKey(expected, ready.signed.receipt.sessionPublicKey, ready.signed, receiptNow)
            }.getOrDefault(GuardianSessionPinWrite.Ambiguous)) {
                GuardianSessionPinWrite.Pinned -> Unit
                GuardianSessionPinWrite.Ambiguous -> return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.SESSION_PIN_AMBIGUOUS)
                GuardianSessionPinWrite.Rejected -> return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.SESSION_PIN_REJECTED)
            }
            val reloaded = runCatching { armStore.load(expected) }.getOrNull() as? GuardianArmStoreLoad.LoadedReady
                ?: return GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.SESSION_PIN_UNREADABLE)

            // The durable codec decoded fresh canonical bytes. Never retain caller-owned envelope,
            // entry-list, or signature arrays after READY verification.
            val pinnedSigned = reloaded.signed.guardianSnapshot()
            val handle = GuardianReadyHandle(expected, pinnedSigned, reloaded.sessionKey.sha256())
            active = ActiveGuardianReady(handle, expected, pinnedSigned, reloaded.sessionKey, receiptNow)
            return GuardianArmAdmission.Ready(handle)
        } finally {
            arming = false
        }
    }

    /**
     * Publishes one authenticated liveness receipt for the exact object returned by [armToReady].
     *
     * This is deliberately an inert transport seam: it cannot clear an arm record, change the
     * immutable deadline, or invoke any hardware/profile operation. A validated attempted
     * heartbeat consumes its counter before dispatch so rejection or transport ambiguity cannot
     * be replayed. A later strictly higher counter remains valid for the detached guardian.
     */
    @Synchronized
    fun publishHeartbeat(
        handle: GuardianReadyHandle,
        signed: SignedRestoreGuardianHeartbeat,
    ): GuardianHeartbeatPublication {
        val exactActive = active
        if (exactActive === null || exactActive.handle !== handle) {
            return GuardianHeartbeatPublication.Rejected(GuardianHeartbeatRejectReason.INACTIVE_HANDLE)
        }
        val envelope = exactActive.signedEnvelope.envelope
        if (!exactActive.expectedIdentity.matches(envelope)) {
            return GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.ACTIVE_IDENTITY_MISMATCH)
        }
        val now = runCatching { elapsedRealtimeMs() }.getOrNull()
            ?: return GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.CLOCK_UNAVAILABLE)
        if (now < 0) return GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.CLOCK_REGRESSION)
        if (runCatching { liveBootIdentity()?.trim() }.getOrNull() != envelope.bootIdentity) {
            return GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.BOOT_MISMATCH)
        }
        val signatureValid = runCatching {
            RestoreGuardianCrypto.verifyHeartbeat(signed, pinnedVerifier, envelope)
        }.getOrDefault(false)
        when (val decision = RestoreGuardianHeartbeatPolicy.evaluate(
            envelope = envelope,
            current = heartbeatState,
            heartbeat = signed.heartbeat,
            nowElapsedRealtimeMs = now,
            signatureValid = signatureValid,
        )) {
            HeartbeatDecision.ACCEPT -> Unit
            else -> return GuardianHeartbeatPublication.RestoreRequired(decision.toRestoreReason())
        }

        // Consume before the side-effecting transport. Nothing below may roll this state back.
        heartbeatState = RestoreGuardianHeartbeatPolicy.advanced(heartbeatState, signed.heartbeat)
        return when (runCatching { heartbeatDispatcher.dispatch(signed) }.getOrDefault(GuardianDispatchResult.AMBIGUOUS)) {
            GuardianDispatchResult.CONFIRMED -> GuardianHeartbeatPublication.Confirmed
            GuardianDispatchResult.REJECTED -> GuardianHeartbeatPublication.Rejected(GuardianHeartbeatRejectReason.DISPATCH_REJECTED)
            GuardianDispatchResult.AMBIGUOUS -> GuardianHeartbeatPublication.Ambiguous
        }
    }

    /**
     * Accepts the two distinct authenticated terminal boundaries. Stock is irreversible for this
     * controller instance even when EXITED or durable clear is unavailable, so heartbeats cannot
     * revive a guardian after Stock has been confirmed.
     */
    @Synchronized
    fun acknowledgeTerminal(handle: GuardianReadyHandle): GuardianTerminalAcknowledgement {
        return when (val verified = verifyTerminal(handle)) {
            is GuardianTerminalVerification.Rejected ->
                GuardianTerminalAcknowledgement.Rejected(verified.reason)
            is GuardianTerminalVerification.Retained ->
                GuardianTerminalAcknowledgement.Retained(verified.reason)
            is GuardianTerminalVerification.Verified -> if (disarm(handle)) {
                GuardianTerminalAcknowledgement.Cleared
            } else {
                GuardianTerminalAcknowledgement.Retained(GuardianTerminalRetainedReason.CLEAR_UNCONFIRMED)
            }
        }
    }

    /** Verifies and pins terminal evidence without clearing either durable store. */
    @Synchronized
    fun verifyTerminal(handle: GuardianReadyHandle): GuardianTerminalVerification {
        val exactActive = active
        if (exactActive === null || exactActive.handle !== handle) return GuardianTerminalVerification.Rejected(GuardianTerminalRejectReason.INACTIVE_HANDLE)
        val envelope = exactActive.signedEnvelope.envelope
        if (!exactActive.expectedIdentity.matches(envelope)) return GuardianTerminalVerification.Rejected(GuardianTerminalRejectReason.IDENTITY_MISMATCH)
        val now = runCatching { elapsedRealtimeMs() }.getOrNull()
            ?: return GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.CLOCK_UNAVAILABLE)
        if (now < 0) return GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.CLOCK_INVALID)
        if (runCatching { liveBootIdentity()?.trim() }.getOrNull() != envelope.bootIdentity) return GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.BOOT_MISMATCH)
        val terminalDeadline = saturatingAdd(now, TERMINAL_RECEIPT_TIMEOUT_MS)
        val loaded = runCatching { terminalReceipts.loadUntil(terminalDeadline) }.getOrNull()
            ?: return GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE)
        if (loaded !is GuardianTerminalLoad.Received) return GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE)
        val stock = loaded.stock
        if (!runCatching { RestoreGuardianCrypto.verifyStockConfirmedReceipt(stock, exactActive.sessionKey, envelope) }.getOrDefault(false)) return GuardianTerminalVerification.Rejected(GuardianTerminalRejectReason.INVALID_STOCK)
        val s = stock.receipt
        val exactStock = s.firstReadbackElapsedRealtimeMs >= exactActive.readyVerifiedElapsedRealtimeMs &&
            s.secondReadbackElapsedRealtimeMs - s.firstReadbackElapsedRealtimeMs >= GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS &&
            s.issuedElapsedRealtimeMs >= s.secondReadbackElapsedRealtimeMs && s.issuedElapsedRealtimeMs <= now &&
            s.firstReadbackElapsedRealtimeMs <= now && s.secondReadbackElapsedRealtimeMs <= now
        if (!exactStock) return GuardianTerminalVerification.Rejected(GuardianTerminalRejectReason.INVALID_STOCK)
        heartbeatState = heartbeatState.copy(terminalState = GuardianTerminalState.TERMINAL)
        val exited = loaded.exited ?: return GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.EXITED_UNCONFIRMED)
        if (!runCatching { RestoreGuardianCrypto.verifyExitedReceipt(exited, exactActive.sessionKey, envelope) }.getOrDefault(false)) return GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.EXITED_UNCONFIRMED)
        val e = exited.receipt
        val exactExited = RestoreGuardianCrypto.terminalReceiptMatches(envelope, e.helperBuildIdentity, e.bootIdentity, e.generation, e.requestId, e.transactionId, e.taskNonce, e.envelopeSha256) &&
            e.stockConfirmedReceiptSha256 == s.digestSha256() && e.issuedElapsedRealtimeMs >= s.issuedElapsedRealtimeMs && e.issuedElapsedRealtimeMs <= now
        if (!exactExited) return GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.EXITED_UNCONFIRMED)
        return GuardianTerminalVerification.Verified(s.digestSha256())
    }

    /** Clears only the exact active arm after terminal evidence is already journal-durable. */
    @Synchronized
    fun disarm(handle: GuardianReadyHandle): Boolean {
        val exactActive = active
        if (exactActive === null || exactActive.handle !== handle) return false
        if (!runCatching { armStore.clear() }.getOrDefault(false)) return false
        active = null
        heartbeatState = GuardianHeartbeatState()
        return true
    }

    private data class GuardianArmPreflight(
        val envelope: RestoreGuardianEnvelope,
        val nowElapsedRealtimeMs: Long,
        val source: InstalledTweakerApkSource?,
    )

    /** Private active truth is never obtained through the externally returned handle projection. */
    private data class ActiveGuardianReady(
        val handle: GuardianReadyHandle,
        val expectedIdentity: RestoreGuardianExpectedIdentity,
        val signedEnvelope: SignedRestoreGuardianEnvelope,
        val sessionKey: GuardianSessionPublicKey,
        val readyVerifiedElapsedRealtimeMs: Long = 0L,
    )

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    companion object {
        internal const val READY_TIMEOUT_MS = 5_000L
        private const val TERMINAL_RECEIPT_TIMEOUT_MS = 5_000L
    }
}

/** PackageManager-only source lookup. Implementations must never accept caller-supplied paths. */
internal fun interface GuardianInstalledApkPort { fun resolve(): InstalledTweakerApkSource? }

/** Stage is synchronous; future PServer integration must preserve that disposition. */
internal fun interface GuardianSynchronousDispatcher { fun dispatch(invocation: RestoreGuardianRunnerInvocation): GuardianDispatchResult }

/** The watcher launch is the sole detached dispatch shape. */
internal fun interface GuardianDetachedDispatcher { fun dispatch(invocation: RestoreGuardianRunnerInvocation): GuardianDispatchResult }

/** A single bounded receipt load. Implementations must not spin, sleep, or manufacture a receipt. */
internal fun interface GuardianReadyReceiptPort { fun loadUntil(deadlineElapsedRealtimeMs: Long): GuardianReadyLoad }

/** A future runner may accept only a signed typed heartbeat, never an arbitrary command string. */
internal fun interface GuardianHeartbeatDispatchPort { fun dispatch(signed: SignedRestoreGuardianHeartbeat): GuardianDispatchResult }

/** One bounded typed transport read; it must not retry or synthesize terminal truth. */
internal fun interface GuardianTerminalReceiptPort { fun loadUntil(deadlineElapsedRealtimeMs: Long): GuardianTerminalLoad }
internal sealed interface GuardianTerminalLoad {
    data class Received(val stock: SignedRestoreGuardianStockConfirmedReceipt, val exited: SignedRestoreGuardianExitedReceipt?) : GuardianTerminalLoad
    data object Unavailable : GuardianTerminalLoad
    data object TimedOut : GuardianTerminalLoad
    data object Ambiguous : GuardianTerminalLoad
}
internal sealed interface GuardianTerminalAcknowledgement {
    data object Cleared : GuardianTerminalAcknowledgement
    data class Rejected(val reason: GuardianTerminalRejectReason) : GuardianTerminalAcknowledgement
    data class Retained(val reason: GuardianTerminalRetainedReason) : GuardianTerminalAcknowledgement
}
internal sealed interface GuardianTerminalVerification {
    data class Verified(val stockReceiptSha256: String) : GuardianTerminalVerification {
        init { GuardianEnvelopeValidation.requireSha256(stockReceiptSha256, "Stock receipt") }
    }
    data class Rejected(val reason: GuardianTerminalRejectReason) : GuardianTerminalVerification
    data class Retained(val reason: GuardianTerminalRetainedReason) : GuardianTerminalVerification
}
internal enum class GuardianTerminalRejectReason { INACTIVE_HANDLE, IDENTITY_MISMATCH, INVALID_STOCK }
internal enum class GuardianTerminalRetainedReason { CLOCK_UNAVAILABLE, CLOCK_INVALID, BOOT_MISMATCH, TRANSPORT_UNAVAILABLE, EXITED_UNCONFIRMED, CLEAR_UNCONFIRMED }

internal enum class GuardianDispatchResult { CONFIRMED, REJECTED, AMBIGUOUS }

internal sealed interface GuardianHeartbeatPublication {
    data object Confirmed : GuardianHeartbeatPublication
    data object Ambiguous : GuardianHeartbeatPublication
    data class Rejected(val reason: GuardianHeartbeatRejectReason) : GuardianHeartbeatPublication
    data class RestoreRequired(val reason: GuardianHeartbeatRestoreReason) : GuardianHeartbeatPublication
}

internal enum class GuardianHeartbeatRejectReason {
    INACTIVE_HANDLE,
    DISPATCH_REJECTED,
}

internal enum class GuardianHeartbeatRestoreReason {
    ACTIVE_IDENTITY_MISMATCH,
    BOOT_MISMATCH,
    CLOCK_UNAVAILABLE,
    CLOCK_REGRESSION,
    TERMINAL,
    INVALID_SIGNATURE,
    IDENTITY_MISMATCH,
    REPLAY_OR_REORDER,
    DEADLINE_MISMATCH,
    EXPIRED,
}

private fun HeartbeatDecision.toRestoreReason(): GuardianHeartbeatRestoreReason = when (this) {
    HeartbeatDecision.TERMINAL -> GuardianHeartbeatRestoreReason.TERMINAL
    HeartbeatDecision.IDENTITY_MISMATCH -> GuardianHeartbeatRestoreReason.IDENTITY_MISMATCH
    HeartbeatDecision.REPLAY_OR_REORDER -> GuardianHeartbeatRestoreReason.REPLAY_OR_REORDER
    HeartbeatDecision.CLOCK_REGRESSION -> GuardianHeartbeatRestoreReason.CLOCK_REGRESSION
    HeartbeatDecision.DEADLINE_MISMATCH -> GuardianHeartbeatRestoreReason.DEADLINE_MISMATCH
    HeartbeatDecision.EXPIRED -> GuardianHeartbeatRestoreReason.EXPIRED
    HeartbeatDecision.INVALID_SIGNATURE -> GuardianHeartbeatRestoreReason.INVALID_SIGNATURE
    HeartbeatDecision.ACCEPT -> error("accepted heartbeat has no restore reason")
}

internal sealed interface GuardianReadyLoad {
    data class Received(val signed: SignedRestoreGuardianReadyReceipt, val evidence: GuardianReadyChannelEvidence) : GuardianReadyLoad
    data object TimedOut : GuardianReadyLoad
    data object Unavailable : GuardianReadyLoad
    data object Ambiguous : GuardianReadyLoad
}

/**
 * The only object a later apply gate may accept. Its projections are snapshots: callers cannot
 * mutate the active signature bytes or restore-entry backing retained by the controller.
 */
internal class GuardianReadyHandle(
    val expectedIdentity: RestoreGuardianExpectedIdentity,
    signedEnvelope: SignedRestoreGuardianEnvelope,
    val readySessionSha256: String,
) {
    init { GuardianEnvelopeValidation.requireSha256(readySessionSha256, "READY session") }
    private val pinnedSignedEnvelope = signedEnvelope.guardianSnapshot()

    val signedEnvelope: SignedRestoreGuardianEnvelope
        get() = pinnedSignedEnvelope.guardianSnapshot()

    val mutationAuthority: Boolean get() = true
}

private fun SignedRestoreGuardianEnvelope.guardianSnapshot(): SignedRestoreGuardianEnvelope =
    SignedRestoreGuardianEnvelope(
        envelope = envelope.copy(restoreEntries = immutableGuardianList(envelope.restoreEntries.map { it.guardianSnapshot() })),
        signature = signature.copyOf(),
    )

private fun RestoreGuardianRestoreEntry.guardianSnapshot(): RestoreGuardianRestoreEntry = when (this) {
    is RestoreGuardianSysfsEntry -> copy(rendererAppliedIntermediates = immutableGuardianList(rendererAppliedIntermediates))
    is RestoreGuardianFanPutEntry -> copy(rendererAppliedIntermediates = immutableGuardianList(rendererAppliedIntermediates))
    is RestoreGuardianFanDeleteEntry -> copy(rendererAppliedIntermediates = immutableGuardianList(rendererAppliedIntermediates))
    is RestoreGuardianVendorModePutEntry -> copy()
}

private fun <T> immutableGuardianList(values: List<T>): List<T> =
    Collections.unmodifiableList(values.toList())

internal sealed interface GuardianArmAdmission {
    data class ArmRejected(val reason: GuardianArmRejectReason) : GuardianArmAdmission {
        val mutationAuthority: Boolean get() = false
    }
    data class ArmRetained(val reason: GuardianArmRetainedReason) : GuardianArmAdmission {
        val mutationAuthority: Boolean get() = false
    }
    data class Ready(val handle: GuardianReadyHandle) : GuardianArmAdmission {
        val mutationAuthority: Boolean get() = handle.mutationAuthority
    }
}

/** Testable gate truth for a future coordinator; this controller still cannot mutate hardware. */
internal val GuardianArmAdmission.mutationAuthority: Boolean
    get() = when (this) {
        is GuardianArmAdmission.ArmRejected -> false
        is GuardianArmAdmission.ArmRetained -> false
        is GuardianArmAdmission.Ready -> handle.mutationAuthority
    }

internal enum class GuardianArmRejectReason {
    CONCURRENT_ARM,
    IDENTITY_MISMATCH,
    INVALID_ENVELOPE_SIGNATURE,
    BOOT_MISMATCH,
    DEADLINE_EXPIRED,
    PREARM_PORT_FAILURE,
    INSTALLED_APK_UNAVAILABLE,
    INSTALLED_APK_UNTRUSTED,
    STAGE_RENDER_REJECTED,
    GUARDIAN_RENDER_REJECTED,
    ARM_STORE_REJECTED,
    STAGE_DISPATCH_REJECTED,
}

internal enum class GuardianArmRetainedReason {
    ARM_RECORD_UNREADABLE,
    ARM_STORE_AMBIGUOUS,
    STAGE_DISPATCH_AMBIGUOUS,
    GUARDIAN_LAUNCH_AMBIGUOUS,
    READY_UNCONFIRMED,
    READY_CHANNEL_REJECTED,
    READY_KEY_REJECTED,
    SESSION_PIN_REJECTED,
    SESSION_PIN_AMBIGUOUS,
    SESSION_PIN_UNREADABLE,
}
