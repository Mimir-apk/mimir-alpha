package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/*
 * An overlay that opens over nothing and leaves to nothing, as an `AnimatedContent` transform: the Bottom's pickers, dialogs and instruments host, whose target is `null` while they are closed.
 *
 * Two things this shape needs that [mimirSwapTransform] (a page replacing a page) does not give it:
 *
 * - **A close that takes one clock.** [mimirSwapTransform] makes the arriving page wait [MimirMotion.OverlayDissolveCoverMs], and
 *   when the arriving page is the empty one (`null`) that wait is a 130 ms lead on nothing: the transition would not settle until
 *   310 ms, though the overlay was gone at 180. The empty page that arrives in a close therefore runs the SAME leave clock as the
 *   page that leaves. That is also what the receipts read: a [BottomUiTransitionProbe] stands under the current target, which on a
 *   close is the empty page, so its own enter spec is the clock the receipt reports.
 * - **No resize.** The container holds the overlay's whole screen. An overlay is laid out at its full size and faded, so the size
 *   change a default `AnimatedContent` runs (and a swap runs on the arrival's clock, delayed by the lead) would hold a dying page
 *   composed after it has gone, or an arriving one at zero size through its lead. The resize is a snap on both edges.
 */
private fun noResize(): SizeTransform = SizeTransform(clip = false) { _, _ -> snap() }

/**
 * On the swap tier: an arrival waits the lead then fades in ([mimirSwapEnterSpec], 130 + 180 = 310 ms); a close is [MimirMotion.OverlayMs]
 * (180 ms). [arriving] is whether the new target is an overlay (`targetState != null`); a switch from one overlay to another is an
 * arrival (the departing one fades on the same exit clock).
 */
fun mimirSwapOverlayTransform(reducedMotion: Boolean, arriving: Boolean): ContentTransform {
    if (arriving) {
        val swap = mimirSwapTransform(reducedMotion)
        return ContentTransform(swap.targetContentEnter, swap.initialContentExit, swap.targetContentZIndex, noResize())
    }
    val leave = mimirSwapExitSpec<Float>(reducedMotion)
    return ContentTransform(
        targetContentEnter = fadeIn(leave),
        initialContentExit = fadeOut(leave),
        sizeTransform = noResize(),
    )
}

/**
 * On the page tier's overlay clocks ([mimirOverlayArriveSpec]: the 130 ms lead, then [MimirMotion.ShineMs], about 670 ms from the
 * press; [mimirOverlayLeaveSpec]: [MimirMotion.OverlayMs]), the clocks Main's Utility and Tweaker host and its dock run. The Bottom's
 * instruments host is this; Main's pixel-fade host is not used because it holds the process-wide pad gate on a cold arrival.
 */
fun mimirOverlayContentTransform(reducedMotion: Boolean, arriving: Boolean): ContentTransform {
    val leave = mimirOverlayLeaveSpec()
    return ContentTransform(
        targetContentEnter = fadeIn(if (arriving) mimirOverlayArriveSpec(reducedMotion) else leave),
        initialContentExit = fadeOut(leave),
        sizeTransform = noResize(),
    )
}
