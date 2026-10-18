package com.gruning.mimir.rewrite

/**
 * The M/macro Quick Launch command, done as the policy actually implied: the old navigate
 * path's STATE-SETTLING minus only the navigation. The first in-place branch copied the
 * Utility/Tweaker overlay contract and was too thin — the old path's clearing did two jobs (going
 * Home, and settling state so the panel could open safely) and only the first was supposed to go.
 * The 2026-08-11 xhigh review confirmed eight integration defects from that thinness; each settle
 * step below names the defect it closes.
 *
 * Pure orchestration over narrow lambdas so the sequence, ordering, and refusal semantics are
 * JVM-testable — the previous inline branch lived in a `LaunchedEffect` no host test could drive.
 */
internal enum class QuickLaunchInPlaceOutcome {
    /** Panel opened over the live surface with state settled. */
    OPENED,

    /** Panel was already open: its picker was closed and obstructions swept — never re-settled. */
    RECOVERED_EXISTING,

    /**
     * An overlay that outranks the panel refused to yield (validation modal, migration, a
     * still-armed restoration cover). Honest refusal: the panel must never open buried beneath an
     * input-swallowing surface only to pop up unprompted later (review defect: buried-open).
     * The press is consumed. Only the restoration cover clears by itself; every other refusal
     * stands until the player finishes or closes what is on screen, which is what
     * [quickLaunchObstructionCopy] tells them. A kept surface (the folder browser, a media flow —
     * [mimirOverlayStaysUnderQuickLaunch]) is never an obstruction: the panel opens over it.
     */
    REFUSED_OBSTRUCTED,

    /**
     * A cold-start origin restore is still in flight. Opening the panel's scope mid-restore
     * permanently strands `MimirStartupOriginEffect` (review defect: stranded startup origin), so
     * the press is refused during that one startup window.
     */
    REFUSED_STARTUP_RESTORE,
}

/**
 * The one refusal a retry cures: the cold-start restore is still running, or the return cover is still up (it clears
 * itself). Proposed copy, pending design review.
 */
internal const val QuickLaunchNotReadyCopy = "Quick launch isn’t ready yet. Try again in a moment."

/**
 * Every other refusal: something on screen outranks the panel and will not go away by waiting. The refusal leaves that
 * surface up, so "this" is what the player is looking at. Proposed copy, pending design review.
 */
internal const val QuickLaunchBlockedCopy = "Finish or close this first, then open Quick launch."

/**
 * What the toast says for a refused press. [obstruction] is the overlay that refused, read after the press (a refusal
 * leaves it on top); null, or the restoration cover, is the transient case.
 */
internal fun quickLaunchObstructionCopy(obstruction: MimirOverlayKind?): String = when (obstruction) {
    null, MimirOverlayKind.RETURN_COVER -> QuickLaunchNotReadyCopy
    else -> QuickLaunchBlockedCopy
}

internal fun executeQuickLaunchInPlace(
    panelOpen: Boolean,
    startupOriginApplied: Boolean,
    /** Close the slot-assign picker if it is up — the panel's own child, not a foreign modal. */
    closePicker: () -> Unit,
    /**
     * The pre-open sweep: CANCEL each overlay above the panel's seat, top-down, through its own Back path. The caller's
     * loop stops on RETURN_COVER, on the panel itself, on a kept surface ([mimirOverlayStaysUnderQuickLaunch], which
     * it never sends CANCEL), and on no-progress.
     */
    sweepConflictingOverlays: () -> Unit,
    /** The overlay that would still outrank the panel after the sweep, or null when clear. */
    remainingObstruction: () -> MimirOverlayKind?,
    /**
     * Whether a kept surface (the folder browser, a media flow) is live anywhere in the stack, read once before any step
     * runs. The panel then opens OVER it, and the two steps that would alter what lies beneath it are skipped: the
     * transient Home menus and the Utility panel are part of the stack the player returns to. Closing the panel leaves
     * them as they were, so a Back out of the browser still lands on the Utility it was opened from.
     */
    keptSurfaceLive: () -> Boolean,
    /** Hard-close the transient Home menus the old path nulled (review defect: phase-stepped menus). */
    hardCloseTransientMenus: () -> Unit,
    /** Cancel a pending Tome return so its deferred presses cannot replay into the panel. */
    cancelTomeReturn: () -> Unit,
    /**
     * The 2026-07-20 ruling, kept for this explicit command: disarm the library anchor, clear the
     * ROM-side pending target, supersede the native shelf return (review defect: scope popped
     * under the panel / panel dead beneath the return cover).
     */
    disarmLaunchReturns: () -> Unit,
    closeUtilityPanel: () -> Unit,
    openPanel: () -> Unit,
): QuickLaunchInPlaceOutcome {
    if (!startupOriginApplied) return QuickLaunchInPlaceOutcome.REFUSED_STARTUP_RESTORE

    if (panelOpen) {
        // Re-press recovers the panel it is dedicated to (review defect: buried/picker no-op):
        // close its own picker, sweep foreign obstructions, and leave the settled state alone.
        closePicker()
        sweepConflictingOverlays()
        return QuickLaunchInPlaceOutcome.RECOVERED_EXISTING
    }

    val overKeptSurface = keptSurfaceLive()
    if (!overKeptSurface) hardCloseTransientMenus()
    cancelTomeReturn()
    disarmLaunchReturns()
    if (!overKeptSurface) closeUtilityPanel()
    sweepConflictingOverlays()
    if (remainingObstruction() != null) return QuickLaunchInPlaceOutcome.REFUSED_OBSTRUCTED
    openPanel()
    return QuickLaunchInPlaceOutcome.OPENED
}
