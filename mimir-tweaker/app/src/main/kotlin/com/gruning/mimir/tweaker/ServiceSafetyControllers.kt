package com.gruning.mimir.tweaker

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class CancelToken(
    val identity: String,
    private val cancelled: AtomicBoolean = AtomicBoolean(false),
) {
    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()
}

/**
 * LAB_APPLY_HOLD liveness distinct from Binder client count. Successful LAB hold remains
 * live after synthetic client release until explicit restore, supervisor termination, or
 * lease expiry. Failure never sets it. Not a public renewal/qualification surface.
 */
internal class LabApplyHoldLiveness {
    private enum class State { IDLE, PENDING, ACTIVE }

    private val state = AtomicReference(State.IDLE)

    fun tryBeginPendingHold(): Boolean = state.compareAndSet(State.IDLE, State.PENDING)

    fun commitPendingHold(): Boolean = state.compareAndSet(State.PENDING, State.ACTIVE)

    fun clear() {
        state.set(State.IDLE)
    }

    fun isActive(): Boolean = state.get() == State.ACTIVE

    fun isPendingOrActive(): Boolean = state.get() != State.IDLE
}

/**
 * Diagnostics-only activation seam for launch-owned profiles. The target must remain foreground for
 * one bounded vendor-quiescence window before any Guardian/journal mutation begins. This prevents an
 * Odin/AYN Activity-launch boost from replacing a just-verified CPU QoS target.
 */
internal object LabOwnerValidationActivationGate {
    const val FOREGROUND_TIMEOUT_MS = 10_000L
    const val QUIESCENCE_MS = 750L
    const val POLL_MS = 75L

    fun await(
        observe: () -> WorkloadTruth,
        sampleMutableAxes: () -> LabOwnerValidationMutableAxisVector?,
        nowElapsedRealtimeMs: () -> Long,
        sleepMs: (Long) -> Unit,
    ): Boolean {
        val startedAt = nowElapsedRealtimeMs()
        if (startedAt < 0L || startedAt > Long.MAX_VALUE - FOREGROUND_TIMEOUT_MS) return false
        val deadline = startedAt + FOREGROUND_TIMEOUT_MS
        var foregroundSince: Long? = null
        var stableVector: LabOwnerValidationMutableAxisVector? = null
        while (true) {
            val now = nowElapsedRealtimeMs()
            if (now < startedAt || now >= deadline) return false
            when (observe()) {
                WorkloadTruth.TARGET_FOREGROUND -> {
                    val vector = sampleMutableAxes() ?: return false
                    if (vector != stableVector) {
                        stableVector = vector
                        foregroundSince = now
                    }
                    val since = foregroundSince ?: now.also { foregroundSince = it }
                    if (now - since >= QUIESCENCE_MS) return true
                }
                WorkloadTruth.ACCESS_DENIED,
                WorkloadTruth.QUERY_FAILED,
                -> return false
                WorkloadTruth.TARGET_PAUSED_OR_STOPPED -> {
                    // A pre-dispatch action normally begins with the prior emulator Activity's
                    // PAUSED/STOPPED event in the ledger. It is only terminal after this launch has
                    // actually established foreground once.
                    if (foregroundSince != null) return false
                }
                WorkloadTruth.OTHER_FOREGROUND,
                WorkloadTruth.INDETERMINATE,
                -> {
                    foregroundSince = null
                    stableVector = null
                }
            }
            try {
                sleepMs(POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }
}

internal data class LabOwnerValidationMutableAxisVector(
    val bootId: String,
    val cpuQosCeilingKhz: Map<Int, Int>,
    val cpuQosTransport: CpuQosTransport,
    val gpuMinPowerLevel: Int?,
    val gpuMaxPowerLevel: Int?,
    val vendorFanMode: Int?,
) {
    companion object {
        fun from(snapshot: TelemetrySnapshot): LabOwnerValidationMutableAxisVector? {
            val bootId = snapshot.bootId?.takeIf { it.isNotBlank() } ?: return null
            if (snapshot.cpuQosCeilingKhz.isEmpty() ||
                snapshot.cpuQosTransport == CpuQosTransport.UNKNOWN
            ) {
                return null
            }
            return LabOwnerValidationMutableAxisVector(
                bootId = bootId,
                cpuQosCeilingKhz = snapshot.cpuQosCeilingKhz.toSortedMap(),
                cpuQosTransport = snapshot.cpuQosTransport,
                gpuMinPowerLevel = snapshot.gpuMinPowerLevel,
                gpuMaxPowerLevel = snapshot.gpuMaxPowerLevel,
                vendorFanMode = snapshot.vendorFanMode,
            )
        }
    }
}

/**
 * One diagnostics-only six-minute cap for a five-minute workload observation plus bounded setup and
 * settlement margin while a LAB profile is active. The duration is deliberately not caller-selected:
 * LAB_APPLY_HOLD always receives this exact window, and the immutable deadline is signed into
 * Guardian's envelope before the first hardware command. Public API-V4 owner leases, SAFE, and
 * Production never use this policy.
 */
internal object DiagnosticsCalibrationHoldLeasePolicy {
    const val HARD_LEASE_MS = 360_000L

    fun hardDeadlineElapsedRealtimeMs(
        nowElapsedRealtimeMs: Long,
        diagnosticsCompiled: Boolean,
    ): Long? {
        if (!diagnosticsCompiled || nowElapsedRealtimeMs < 0L) return null
        if (nowElapsedRealtimeMs > Long.MAX_VALUE - HARD_LEASE_MS) return null
        return nowElapsedRealtimeMs + HARD_LEASE_MS
    }
}

/** A live profile FGS is reused; an empty second start would be a recovery signal. */
internal object CalibrationForegroundStartPolicy {
    fun requiresServiceStart(foregroundEstablished: Boolean): Boolean = !foregroundEstablished
}

/**
 * Pure post-transaction FGS disposition from apply/restore result + journal load.
 * Never leave FGS solely because result.applied is false while durable recovery remains.
 */
internal sealed class PostTransactionDisposition {
    /** Journal empty / settled — leave FGS. */
    data object LeaveForegroundSettled : PostTransactionDisposition()
    /** Loaded journal — retain FGS, retain/adopt exact recovery identity, arm bounded retry. */
    data class RetainRecovery(val record: TransactionJournalRecord) : PostTransactionDisposition()
    /** Corrupt journal — retain FGS, corrupt hold, zero commands / no tight loop. */
    data object HoldCorrupt : PostTransactionDisposition()
    /** Successful adopted APPLIED — keep active FGS. */
    data object KeepActiveApplied : PostTransactionDisposition()
}

internal object PostTransactionGate {
    fun decide(
        result: TransactionResult,
        journal: JournalLoadResult,
    ): PostTransactionDisposition {
        // KeepActiveApplied only for exact matching Loaded APPLIED truth.
        // result.applied with Empty/Corrupt/mismatch must not keep active.
        if (result.applied) {
            return when (journal) {
                is JournalLoadResult.Loaded -> {
                    if (isExactActiveApplied(result, journal.record)) {
                        PostTransactionDisposition.KeepActiveApplied
                    } else {
                        PostTransactionDisposition.RetainRecovery(journal.record)
                    }
                }
                JournalLoadResult.Empty -> PostTransactionDisposition.LeaveForegroundSettled
                is JournalLoadResult.Corrupt -> PostTransactionDisposition.HoldCorrupt
            }
        }
        return when (journal) {
            JournalLoadResult.Empty -> PostTransactionDisposition.LeaveForegroundSettled
            is JournalLoadResult.Corrupt -> PostTransactionDisposition.HoldCorrupt
            is JournalLoadResult.Loaded -> PostTransactionDisposition.RetainRecovery(journal.record)
        }
    }

    /** Loaded APPLIED whose transaction identity matches the apply result. */
    private fun isExactActiveApplied(
        result: TransactionResult,
        record: TransactionJournalRecord,
    ): Boolean {
        val txId = result.transactionId ?: return false
        return record.phase == TransactionPhase.APPLIED && record.transactionId == txId
    }
}

/**
 * FGS-gated recovery begin. Recovery may execute only after foreground establishment
 * succeeds. Failed FGS retains journal truth and schedules bounded FGS-only retry with
 * zero hardware commands.
 */
internal sealed class ForegroundRecoveryBegin {
    /** FGS established; proceed with journal recovery on the owned worker. */
    data object ProceedRecovery : ForegroundRecoveryBegin()
    /** Empty/no-work selection; leave FGS / do not strand. */
    data object SettledLeave : ForegroundRecoveryBegin()
    /** Corrupt hold: FGS established, no restore commands. */
    data object HoldCorrupt : ForegroundRecoveryBegin()
    /** FGS failed; retain journal, retry FGS with backoff, zero HW commands. */
    data object RetryForegroundOnly : ForegroundRecoveryBegin()
}

internal object ForegroundRecoveryGate {
    fun begin(
        selection: RecoverySelection,
        establishFgs: () -> Boolean,
    ): ForegroundRecoveryBegin {
        return when (selection) {
            RecoverySelection.NONE -> ForegroundRecoveryBegin.SettledLeave
            RecoverySelection.HOLD_CORRUPT_JOURNAL -> {
                if (!establishFgs()) ForegroundRecoveryBegin.RetryForegroundOnly
                else ForegroundRecoveryBegin.HoldCorrupt
            }
            RecoverySelection.START_FOREGROUND_RECOVERY -> {
                if (!establishFgs()) ForegroundRecoveryBegin.RetryForegroundOnly
                else ForegroundRecoveryBegin.ProceedRecovery
            }
        }
    }
}

/** Exact durable identity carried by a Guardian terminal-settlement signal. */
internal data class RecoveryJournalIdentity(
    val generation: Long,
    val transactionId: String,
) {
    init {
        require(generation > 0L)
        require(transactionId.isNotBlank())
    }
}

internal fun recoveryJournalIdentityOrNull(
    generation: Long,
    transactionId: String?,
): RecoveryJournalIdentity? = transactionId
    ?.takeIf { generation > 0L && it.isNotBlank() }
    ?.let { RecoveryJournalIdentity(generation, it) }

internal enum class GuardianSettlementIngressResult {
    REJECTED_MALFORMED,
    RETRY_FOREGROUND_ONLY,
    QUEUED_SERIALIZED,
}

/** Prompt-FGS and identity gate before any Guardian signal can reach recovery work. */
internal object GuardianSettlementIngress {
    fun dispatch(
        identity: RecoveryJournalIdentity?,
        establishForeground: () -> Boolean,
        retainMalformedHold: () -> Unit,
        retryForegroundOnly: (RecoveryJournalIdentity) -> Unit,
        enqueueSerialized: (RecoveryJournalIdentity) -> Unit,
    ): GuardianSettlementIngressResult {
        if (identity == null) {
            retainMalformedHold()
            return GuardianSettlementIngressResult.REJECTED_MALFORMED
        }
        if (!establishForeground()) {
            retryForegroundOnly(identity)
            return GuardianSettlementIngressResult.RETRY_FOREGROUND_ONLY
        }
        enqueueSerialized(identity)
        return GuardianSettlementIngressResult.QUEUED_SERIALIZED
    }
}

/**
 * Deduplicates recovery ownership across onCreate + onStart. One owner, one restore
 * attempt. Explicit stop-on-settle/startId obligations merge into the current owner.
 * No forceReselect bypass.
 */
internal class RecoveryOwnership {
    private val lock = Any()
    private var owned = false
    private var stopWhenSettled = false
    private var stopStartId = -1
    private var journalIdentity: RecoveryJournalIdentity? = null
    private val pendingJournalIdentities = mutableListOf<RecoveryJournalIdentity?>()

    data class ClaimResult(
        val isOwner: Boolean,
        val stopWhenSettled: Boolean,
        val stopStartId: Int,
        val journalIdentity: RecoveryJournalIdentity?,
    )

    /**
     * First caller becomes owner. Later callers merge stop obligation and return
     * [ClaimResult.isOwner] = false so they do not start a second restore pass.
     */
    fun claimOrMerge(
        stopWhenSettled: Boolean,
        startId: Int = -1,
        journalIdentity: RecoveryJournalIdentity? = null,
    ): ClaimResult {
        synchronized(lock) {
            if (!owned) {
                owned = true
                this.stopWhenSettled = this.stopWhenSettled || stopWhenSettled
                if (startId >= 0) this.stopStartId = startId
                this.journalIdentity = journalIdentity
                return ClaimResult(
                    isOwner = true,
                    stopWhenSettled = this.stopWhenSettled,
                    stopStartId = this.stopStartId,
                    journalIdentity = this.journalIdentity,
                )
            }
            if (stopWhenSettled) {
                this.stopWhenSettled = true
                if (startId >= 0) {
                    this.stopStartId = startId
                }
            }
            if (journalIdentity != this.journalIdentity &&
                pendingJournalIdentities.none { it == journalIdentity }
            ) {
                // Do not let an old Guardian turn consume a later boot/current-journal
                // obligation or a terminal signal for a replacement generation.
                pendingJournalIdentities += journalIdentity
            }
            return ClaimResult(
                isOwner = false,
                stopWhenSettled = this.stopWhenSettled,
                stopStartId = this.stopStartId,
                journalIdentity = this.journalIdentity,
            )
        }
    }

    fun obligation(): ClaimResult = synchronized(lock) {
        ClaimResult(
            isOwner = owned,
            stopWhenSettled = stopWhenSettled,
            stopStartId = stopStartId,
            journalIdentity = journalIdentity,
        )
    }

    /** Bound Guardian work may act only on the journal generation that emitted its terminal signal. */
    fun ownsJournal(generation: Long?, transactionId: String?): Boolean = synchronized(lock) {
        if (!owned) return@synchronized false
        val expected = journalIdentity ?: return@synchronized true
        generation == expected.generation && transactionId == expected.transactionId
    }

    fun ownsUnscopedJournal(): Boolean = synchronized(lock) {
        owned && journalIdentity == null
    }

    /**
     * Completes the current turn and atomically promotes the next distinct request. When a stale
     * signal exposes orphan durable truth, [reselectCurrentIfNoPending] creates one unscoped turn
     * without opening a gap in ownership.
     */
    fun completeAndTakeNext(
        reselectCurrentIfNoPending: Boolean = false,
    ): ClaimResult? = synchronized(lock) {
        val hasPending = pendingJournalIdentities.isNotEmpty()
        if (!hasPending && !reselectCurrentIfNoPending) {
            clearLocked()
            return@synchronized null
        }
        journalIdentity = if (hasPending) pendingJournalIdentities.removeAt(0) else null
        owned = true
        ClaimResult(
            isOwner = true,
            stopWhenSettled = stopWhenSettled,
            stopStartId = stopStartId,
            journalIdentity = journalIdentity,
        )
    }

    fun release() {
        synchronized(lock) {
            clearLocked()
        }
    }

    /**
     * A retained Guardian turn has finished executing but still owns durable truth. Yielding
     * lets the Guardian's later terminal signal run the next exact turn while preserving the
     * merged stop obligation. It does not schedule or authorize a retry by itself.
     */
    fun yieldForGuardianSignal(): ClaimResult? = synchronized(lock) {
        if (pendingJournalIdentities.isNotEmpty()) {
            journalIdentity = pendingJournalIdentities.removeAt(0)
            return@synchronized ClaimResult(
                isOwner = true,
                stopWhenSettled = stopWhenSettled,
                stopStartId = stopStartId,
                journalIdentity = journalIdentity,
            )
        }
        owned = false
        journalIdentity = null
        null
    }

    fun isOwned(): Boolean = synchronized(lock) { owned }

    private fun clearLocked() {
        owned = false
        stopWhenSettled = false
        stopStartId = -1
        journalIdentity = null
        pendingJournalIdentities.clear()
    }
}

/**
 * Explicit restore commit: clear supervisor only after verified restore. Failed restore
 * retains/re-adopts exact identity and arms bounded retry.
 */
internal sealed class ExplicitRestoreCommit {
    data class Cleared(val result: TransactionResult) : ExplicitRestoreCommit()
    /** Guardian-bound truth remains durable; caller must hold without legacy retry. */
    data class RetainedHold(val result: TransactionResult, val record: TransactionJournalRecord?) :
        ExplicitRestoreCommit()
    data class RetainedRetry(val result: TransactionResult, val record: TransactionJournalRecord?) :
        ExplicitRestoreCommit()
}

internal object ExplicitRestoreGate {
    fun afterRestoreAttempt(
        result: TransactionResult,
        loadJournal: () -> JournalLoadResult,
    ): ExplicitRestoreCommit {
        if (result.restored || result.code == "NO_ACTIVE_SESSION") {
            return ExplicitRestoreCommit.Cleared(result)
        }
        val record = (loadJournal() as? JournalLoadResult.Loaded)?.record
        if (result.code == "CROSS_STORE_RECONCILIATION_REQUIRED" || result.code == "GUARDIAN_ARM_RETAINED") {
            return ExplicitRestoreCommit.RetainedHold(result, record)
        }
        return ExplicitRestoreCommit.RetainedRetry(result, record)
    }
}

/**
 * Admits process-memory cleanup only after Guardian has durably removed the exact dead
 * owner's transaction. Loaded/corrupt truth and any replacement owner/session stay fail-closed.
 */
internal sealed interface EmptyJournalDeadOwnerSettlement {
    data class ExactDeadOwner(val tokenId: Long) : EmptyJournalDeadOwnerSettlement

    data object AlreadyAbsent : EmptyJournalDeadOwnerSettlement

    data object Rejected : EmptyJournalDeadOwnerSettlement
}

internal object EmptyJournalDeadOwnerSettlementGate {
    fun evaluate(
        journal: JournalLoadResult,
        deadOwner: PublicOwnerSnapshot,
        currentOwner: PublicOwnerSnapshot?,
        activeSession: ActiveSessionIdentity?,
    ): EmptyJournalDeadOwnerSettlement {
        if (journal !== JournalLoadResult.Empty || deadOwner.alive || !deadOwner.cancelled ||
            deadOwner.inFlight
        ) {
            return EmptyJournalDeadOwnerSettlement.Rejected
        }
        val generation = deadOwner.generation ?: return EmptyJournalDeadOwnerSettlement.Rejected
        val transactionId = deadOwner.transactionId ?: return EmptyJournalDeadOwnerSettlement.Rejected
        if (currentOwner == null || activeSession == null) {
            return if (currentOwner == null && activeSession == null) {
                EmptyJournalDeadOwnerSettlement.AlreadyAbsent
            } else {
                EmptyJournalDeadOwnerSettlement.Rejected
            }
        }
        val registered = currentOwner
        val active = activeSession
        val exactDeadOwner = !registered.alive && registered.cancelled && !registered.inFlight &&
            registered.tokenId == deadOwner.tokenId &&
            registered.ownerUid == deadOwner.ownerUid &&
            registered.requestId == deadOwner.requestId &&
            registered.generation == generation &&
            registered.transactionId == transactionId &&
            active.ownerTokenId == deadOwner.tokenId &&
            active.ownerUid == deadOwner.ownerUid &&
            active.requestId == deadOwner.requestId &&
            active.generation == generation &&
            active.transactionId == transactionId
        return if (exactDeadOwner) {
            EmptyJournalDeadOwnerSettlement.ExactDeadOwner(deadOwner.tokenId)
        } else {
            EmptyJournalDeadOwnerSettlement.Rejected
        }
    }
}

/** Bounded FGS-only retry delays mirror restore backoff: 1/2/4/8/16/30/30 seconds. */
internal class ForegroundEstablishBackoff(
    private val initialMs: Long = 1_000L,
    private val maxMs: Long = 30_000L,
    private val multiplier: Int = 2,
) {
    private val pendingMs = AtomicReference<Long?>(null)

    fun nextDelayMs(): Long {
        val next = when (val pending = pendingMs.get()) {
            null -> initialMs
            else -> (pending * multiplier).coerceAtMost(maxMs)
        }
        pendingMs.set(next)
        return next
    }

    fun pendingDelayMs(): Long? = pendingMs.get()

    fun reset() {
        pendingMs.set(null)
    }
}
