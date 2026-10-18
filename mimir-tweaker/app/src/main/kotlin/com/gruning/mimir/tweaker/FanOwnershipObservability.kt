package com.gruning.mimir.tweaker

import android.util.Log

/**
 * Always-compiled seam (every posture, including Production) for fan-ownership renewal/reassert:
 * vendor fan changes during an active Mimir session trigger renewal of Mimir control.
 *
 * **Why this exists.** Previously, `HardwareSessionSupervisor.execute`'s `is RepairFanTarget` branch ran
 * with no `onFanReassertAttempt` callback wired at its only call site
 * (`MimirTweakerService`'s `monitorCycle`), and the `RepairFanTarget` decision itself was never
 * observed at all. `TransactionCoordinator.reassertAppliedFanBound` was silently refusing every
 * tick during the 02:39->02:58 neither-owner window
 * with nothing in logcat to show
 * it. This is the fourth-plus instance of that same defect class this codebase keeps finding --
 * see [GuardianTerminalLoadObservability]'s own kdoc for the earlier three -- and the narrowest
 * kind: the daemon computes the answer (a `SupervisorDecision.RepairFanTarget`, a reassert
 * `TransactionResult`) and drops it before logcat.
 *
 * Same register as [GuardianTerminalLoadObservability]: always compiled, every posture, every
 * `Log` call wrapped in `runCatching` because a diagnostic must never throw or change behaviour,
 * and consecutive identical rendered lines are deduplicated so a call site sitting under a bounded
 * retry (the fan-reassert budget is 4 per 30s / 2 per 5s, `SupervisorPolicy.fanReassert*`) cannot
 * flood logcat -- while [lastRecorded] still updates on every call, including suppressed ones, so
 * a host test can prove the seam saw the event without depending on `Log` succeeding.
 *
 * The dedup state is shared across all three `on*` functions rather than kept per-function: the
 * rendered lines already carry a discriminating prefix (`reassert `, `renew `, `ownership `), so
 * two different event kinds in a row are never mistaken for a repeat -- exactly how
 * [GuardianTerminalLoadObservability] shares one `lastEmitted` across its five causes. One
 * consequence worth naming for a later reader: on a tick that alternates a renew decision and a
 * reassert attempt every cycle, the two lines differ from each other and so NEITHER dedups against
 * itself -- the fan-reassert admission budget above is what actually bounds that volume, not this
 * seam.
 *
 * Tag reuses [FAN_CONTROL_TAG]'s own literal (`"MimirFanControl"`, see
 * `MimirTweakerService.FAN_CONTROL_TAG`) so these lines land under the existing tag used
 * for every other fan decline/decision line in this file, rather than opening a new tag nobody is
 * grepping yet.
 */
internal object FanOwnershipObservability {
    private const val TAG = "MimirFanControl"

    /** The most recent rendered line, set on every call -- including suppressed duplicates. */
    @Volatile
    var lastRecorded: String? = null
        private set

    private var lastEmitted: String? = null

    @Synchronized
    private fun emit(line: String): Boolean {
        lastRecorded = line
        if (line == lastEmitted) return false
        lastEmitted = line
        runCatching { Log.w(TAG, line) }
        return true
    }

    /** Pure formatting for a reassert attempt's own [TransactionResult]. Unit-testable without [Log]. */
    fun describeReassertAttempt(generation: Long, transactionId: String, code: String, message: String): String =
        "reassert generation=$generation transactionId=$transactionId code=$code message=$message"

    /**
     * Pure formatting for the `SupervisorDecision.RepairFanTarget` decision itself, logged
     * separately from (and before, in `monitorCycle`) the attempt it produces -- the decision can
     * fire even when [describeReassertAttempt] never does (e.g. the attempt callback is never
     * invoked for a decision kind other than `RepairFanTarget`).
     */
    fun describeRenewDecision(reason: String, liveFanMode: Int?, contractFan: Int?): String =
        "renew decision=RepairFanTarget reason=$reason liveFanMode=$liveFanMode contractFan=$contractFan"

    /**
     * Pure formatting for [MimirTweakerService.settleMimirFanOwnershipAfterProfileApplyOnWorker]'s
     * `SCHEDULE_RENEWAL` deferral (D3, spec §5; formerly the `TRANSITION_TO_MANUAL` degrade): the
     * select path's own MANUAL entry on this apply failed or was ineligible, the apply is kept
     * (I4 -- never lose the profile over the fan) as vendor SMART, and renewal is left pending for
     * a later tick to retry.
     */
    fun describeSettleDeferred(code: String, message: String): String =
        "ownership settle=deferred code=$code renewal=pending message=$message"

    /**
     * Pure formatting for [MimirTweakerService.settleMimirFanOwnershipAfterProfileApplyOnWorker]'s
     * `FanOwnershipSettleOutcome.SESSION_LOST` case: the transition failed AFTER
     * `transaction.restoreBound(...)` + `supervisor.clearSession()` already ran, so the hardware is
     * at Stock with no active session carrying the staged apply -- this is the honest failure the
     * caller reports instead of the `describeSettleDeferred` degrade above.
     */
    fun describeSettleLost(code: String, message: String): String =
        "ownership settle=lost code=$code message=$message"

    /**
     * Pure formatting for [MimirTweakerService.renewMimirFanOwnershipOnWorker]'s per-attempt
     * outcome (P3, tick-driven renewal of a SMART-staged contract under an active session).
     * [attempt] is [FanOwnershipRenewalState.attempts] AFTER this attempt is accounted for, so a
     * device log reads "attempt=1" for the first retry, not "attempt=0".
     */
    fun describeRenewAttempt(generation: Long, transactionId: String, attempt: Int, code: String): String =
        "renew attempt=$attempt generation=$generation transactionId=$transactionId code=$code"

    /** Records and, unless a duplicate, emits one reassert attempt's outcome at `warn`. */
    fun onReassertAttempt(generation: Long, transactionId: String, code: String, message: String): Boolean =
        emit(describeReassertAttempt(generation, transactionId, code, message))

    /** Records and, unless a duplicate, emits one `RepairFanTarget` renew decision at `warn`. */
    fun onRenewDecision(reason: String, liveFanMode: Int?, contractFan: Int?): Boolean =
        emit(describeRenewDecision(reason, liveFanMode, contractFan))

    /** Records and, unless a duplicate, emits one settle-worker degrade-to-SMART at `warn`. */
    fun onSettleDeferred(code: String, message: String): Boolean =
        emit(describeSettleDeferred(code, message))

    /** Records and, unless a duplicate, emits one settle-worker session-lost failure at `warn`. */
    fun onSettleLost(code: String, message: String): Boolean =
        emit(describeSettleLost(code, message))

    /** Records and, unless a duplicate, emits one tick-driven renewal attempt's outcome at `warn`. */
    fun onRenewAttempt(generation: Long, transactionId: String, attempt: Int, code: String): Boolean =
        emit(describeRenewAttempt(generation, transactionId, attempt, code))

    /**
     * Pure formatting for [MimirTweakerService.prepareManualFanReentryForReassert]'s own decline: the MANUAL re-entry
     * preparation itself (hand-to-vendor-Smart, telemetry sample, handoff capture/prepare) refused
     * BEFORE `TransactionCoordinator.reassertAppliedFanBound` ever ran. [code] and [detail] name
     * which of those steps declined and why -- distinct from, and logged in addition to,
     * [describeReassertAttempt]'s own line for whatever [TransactionResult] this function's caller
     * (the `fanReasserter` lambda) returns, since [detail] carries the stage-specific context
     * (`stage=handToVendorSmart liveFanMode=...`, `stage=telemetrySample`, `stage=entryDutySettle`,
     * `stage=handoffPrepare`) that the generic reassert line's `message` does not structure the same
     * way.
     */
    fun describeReentryDeclined(generation: Long, transactionId: String, code: String, detail: String): String =
        "reentry generation=$generation transactionId=$transactionId code=$code detail=$detail"

    /** Records and, unless a duplicate, emits one MANUAL re-entry-preparation decline at `warn`. */
    fun onReentryDeclined(generation: Long, transactionId: String, code: String, detail: String): Boolean =
        emit(describeReentryDeclined(generation, transactionId, code, detail))

    /**
     * Pure formatting for [MimirTweakerService.renewMimirFanOwnershipOnWorker]'s transition into
     * [FanOwnershipRenewalState.exhausted] (F4, spec §5 review "consequence"): logged once, on the
     * attempt whose [FanOwnershipRenewalPolicy.afterAttempt] call first marks the state exhausted
     * -- the caller gates on the transition itself (`retried.exhausted && !rekeyed.exhausted`), not
     * on this seam's own dedup, since a later exhausted attempt legitimately carries a different
     * `attempts` count and would otherwise emit again.
     */
    fun describeRenewalExhausted(generation: Long, transactionId: String, attempts: Int, code: String): String =
        "renewal exhausted attempts=$attempts generation=$generation transactionId=$transactionId code=$code"

    /** Records and, unless a duplicate, emits one renewal-exhausted transition at `warn`. */
    fun onRenewalExhausted(generation: Long, transactionId: String, attempts: Int, code: String): Boolean =
        emit(describeRenewalExhausted(generation, transactionId, attempts, code))
}
