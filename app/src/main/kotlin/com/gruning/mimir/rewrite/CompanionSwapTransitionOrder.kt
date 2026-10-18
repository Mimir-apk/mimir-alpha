package com.gruning.mimir.rewrite

/**
 * On SWAP, Main cuts the way the Bottom does. Round 5b (Thor, build b27866a1,
 * 2026-09-12 01:09) showed that a no-animation start alone does not cut SWAP up on Android 13, because the transition
 * that plays on Main is not the start's own.
 *
 * SWAP up, AOSP 13 (android13-release):
 *  - The Bottom's restore guard finishes while resumed, which prepares a close transition on display 4
 *    (ActivityRecord.java:3407). The app under it becomes visible again, so it joins display 4's opening apps while that
 *    transition waits for the app to draw (ActivityRecord.java:5136-5138).
 *  - The transfer host's start then moves the app's task to display 0. `ActivityRecord.onDisplayChanged` moves an opening
 *    app into the new display's opening apps and transfers the old display's pending transition
 *    (ActivityRecord.java:1734-1738); `AppTransition.transferFrom` appends the old display's requests.
 *  - The start's FLAG_ACTIVITY_NO_ANIMATION only adds TRANSIT_NONE (Task.java:5577). `AppTransition.getFirstAppTransition`
 *    skips TRANSIT_NONE, and `AppTransitionController.getTransitCompatType` returns TRANSIT_OLD_TASK_CLOSE for the
 *    transferred CLOSE with a Task closing container: the ~150 ms slide recorded on Main.
 *  - No app-side override reaches it. `AppTransition.loadAnimation` applies an override only when
 *    `container.canCustomizeAppTransition()`, false for a Task (WindowContainer) and true for an ActivityRecord
 *    (ActivityRecord.java:9350-9352). ActivityOptions passed with the start are aborted, by the no-animation move
 *    (Task.java:5579) or, without the flag, because the moved app is already resumed (Task.java:5516-5519).
 *
 * The fix is order. The guard of a swap reclaim finishes only once the transfer host has been paused, which happens when
 * the start has moved the app off the Bottom. The app is then no opening app of display 4 when its task moves, nothing is
 * transferred, and display 0 holds only the start's TRANSIT_NONE, for which getTransitCompatType returns TRANSIT_OLD_NONE.
 *
 * SWAP back has no display change, so nothing is transferred; its hazard is the covered transfer host closing inside the
 * front step's transition (see PrimaryTransferActivity.closeWhenTransferCardClears and bringCompanionMainForward).
 */
internal const val CompanionSwapUpReclaimReason = "overlay_swap_to_primary"

/** Bound on the hold. A transfer host that never starts its target must not keep the guard up. */
internal const val CompanionSwapUpGuardHoldMs = 1_000L

/** Whether a Bottom return host must hold its finish until the SWAP up transfer host has been paused by the move. */
internal fun companionSwapUpGuardHoldsForHost(restoreGuard: Boolean, restoreReason: String?): Boolean =
    restoreGuard && restoreReason == CompanionSwapUpReclaimReason
