package com.gruning.mimir.rewrite

/**
 * Mimir being resumed on the primary is the evidence that runtime recovery may proceed. A persisted
 * non-mirror root is kept user-admitted and always requires an explicit Return action; observing its
 * display only makes that action available. A yielded state still has a live screen-2 host and must
 * remain covered until explicit return evidence settles it.
 *
 * **this no longer requires the resume to follow a STOP.** It used to
 * take `resumedAfterStop`, on the reasoning that a real stop/resume is authoritative user intent. The
 * reasoning was sound and the premise was false: `resumedAfterStop` is false precisely when this
 * predicate is needed. It was MEASURED on the Thor (`b687f3b5`) that **both displays hold a resumed
 * activity simultaneously** — `Display #4` and `Display #0` at once — which is the ordinary posture of
 * a dual-screen handheld, not an edge case. The primary is never stopped, so the one automatic way out
 * of ordinary runtime `RecoveryNeeded` never fired and the state was absorbing in the normal case. The
 * 2026-09-09 process-kill capture narrows that automatic route for persisted non-mirror recovery: the
 * surviving foreign task can remain resumed under a fresh Mimir Presentation, so its cold root now needs
 * an observed display plus an explicit Return action.
 *
 * Aggravating, and fixed by the same change for cold recovery: its monitor demand must not retain the
 * 100 ms UsageStats task-evidence loop when there is no lease to observe. Other recovery failures retain
 * the existing evidence cadence.
 *
 * Every other guard is untouched. An active mirror still blocks, and only `RecoveryNeeded` reclaims —
 * `Yielded`, `Presenting`, `LaunchPending` and `MirrorPresented` are all live work that a resume must
 * never steal.
 */
internal fun shouldReclaimCompanionAfterPrimaryResume(
    snapshot: CompanionRuntimeSnapshot,
): Boolean {
    if (snapshot.mirrorActive) return false
    val recovery = snapshot.disposition as? SecondaryDisplayDisposition.RecoveryNeeded ?: return false
    return recovery.failure.code != FailureCode.PROCESS_RECOVERED_NON_MIRROR
}

/** Cold recovery is actionable only after the current display selector has observed the persisted display. */
internal fun isColdProcessRecoveryAvailable(snapshot: CompanionRuntimeSnapshot): Boolean {
    val recovery = snapshot.disposition as? SecondaryDisplayDisposition.RecoveryNeeded ?: return false
    return recovery.failure.code == FailureCode.PROCESS_RECOVERED_NON_MIRROR &&
        !snapshot.mirrorActive &&
        snapshot.displayId == recovery.displayId &&
        snapshot.availableDisplayId == recovery.displayId
}

/** One policy for the M-button row at both Activity setup and live runtime updates. */
internal fun companionSecondaryReturnAvailable(snapshot: CompanionRuntimeSnapshot): Boolean =
    (snapshot.disposition as? SecondaryDisplayDisposition.Yielded)?.lease != null ||
        isColdProcessRecoveryAvailable(snapshot)

/**
 * True only when this resume follows a stop that actually carried a return operation, i.e. when there
 * is a session of our own to settle back to Stock.
 *
 * The ledger already encodes that truth: [PrimaryStopResumeLedger.noteStopped] retains the ticket only
 * `takeIf(PrimaryStopResumeTicket::hasReturnOperation)`, so a non-null ticket *is* the evidence that a
 * game or primary-app session went away and is now coming back.
 *
 * A freshly started process has no ticket, because it never launched anything. It must not settle: a
 * system-wide profile the player selected outlives the app process by design, and tearing it down on
 * startup is a rollback the player never asked for. Device-proven on Odin 2026-08-10 — with Efficiency
 * applied, force-stopping Mimir left the profile fully applied (clocks held with the process gone), and
 * exact Stock arrived only ~4s after the replacement process came up. Guardian never fired and no
 * `SUPERVISOR restore reason=` was logged, confirming a requested settlement rather than a safety trip.
 */
internal fun shouldSettleTweakerAfterPrimaryResume(returnTicket: PrimaryStopResumeTicket?): Boolean =
    returnTicket != null
