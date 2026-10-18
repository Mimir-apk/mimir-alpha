package com.gruning.mimir.rewrite

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/*
 * How Main's badge grid follows the pad's ring.
 *
 * Two scrollers used to answer one focus move on the same grid: each lit tile asked its scroll parent to bring it into view (an animation per press), and a lit badge the lazy
 * grid had not composed was jumped to with `scrollToItem` (which also cancels the animation in flight and puts that row at the very top). A held or fast-repeated down outran
 * the first, so the second fired: the whole viewport leapt several rows. Now ONE scroller owns the grid's position for the pad. It measures what to reveal from the grid's own
 * layout (every row is the same height, so a row that is not composed has a known place), and chases it on a first-order glide that has no state of its own: each frame it
 * moves a share of the distance still to go, read from the NEWEST lit badge.
 *
 * ONE glide runs for as long as there is anything to follow, and a press that lands mid-glide is not a new glide: it only changes the distance the next frame reads (a retarget).
 * Nothing restarts, so no frame is ever moved twice, and the frame length the steps are sized by is measured across retargets, not guessed again after each press. The glide's
 * first step is taken on a frame, never in the frame the follower woke in. While the lit TILE is outside the viewport (more than its ring's air from showing) the glide runs on a
 * faster time constant, so it is back inside within a few frames and in steps well short of a row (a one-frame catch-up here was a visible jump of three quarters of a row, even on a
 * single press); the air that is left is glided on the slower one.
 */

/** The glide's time constant: about 95% of a one-row reveal is done in three of these, the pace the grid's own bring-into-view had for a single press. */
internal const val MAIN_GRID_GLIDE_TAU_MS: Float = 70f

/**
 * The glide's time constant while the lit tile is still outside the viewport: a row (149 px on the Thor) comes back inside the ring's air in two 16 ms frames (four at 8 ms), the
 * first step is 61 px (35 at 8 ms), and a held repeat's largest step stays under 80 px. 25 ms would be 85 px at a held repeat's usual lag.
 */
internal const val MAIN_GRID_GLIDE_FAST_TAU_MS: Float = 30f

/** A frame longer than this is treated as this long: a hitch is not a reason to lunge across several rows. */
internal const val MAIN_GRID_GLIDE_MAX_FRAME_MS: Float = 40f

/** The frame length a glide assumes until it has measured one. */
internal const val MAIN_GRID_GLIDE_NOMINAL_FRAME_MS: Float = 16f

/** Within this many pixels of the target the glide is done. */
internal const val MAIN_GRID_GLIDE_SETTLE_PX: Float = 0.5f

/** The glide's smallest step, so its exponential tail reaches the target in a few frames instead of approaching it forever. */
internal const val MAIN_GRID_GLIDE_MIN_STEP_PX: Float = 1f

/** How many times a follow that a finger refused is tried again once the finger lets go. */
private const val MAIN_GRID_FOLLOW_RETRIES: Int = 3

/**
 * How far to scroll (positive: forward, toward later rows) to bring a row at [rowTop] ([rowHeight] tall) wholly into the viewport [viewportStart]..[viewportEnd], with [outset]
 * of clear air past each of its edges (the ring's paint), or 0 when it already shows. All in the grid's own pixel space. A row that cannot fit with its outset is aligned by
 * its start.
 */
internal fun mainGridRevealDelta(rowTop: Float, rowHeight: Float, viewportStart: Float, viewportEnd: Float, outset: Float): Float {
    val start = rowTop - outset
    val end = rowTop + rowHeight + outset
    return when {
        end - start > viewportEnd - viewportStart -> start - viewportStart
        start < viewportStart -> start - viewportStart
        end > viewportEnd -> end - viewportEnd
        else -> 0f
    }
}

/**
 * One frame of the glide: the share of [remaining] to move in a frame of [frameMs] (a first-order approach with time constant [tauMs]), at least [MAIN_GRID_GLIDE_MIN_STEP_PX]
 * and never more than what remains, in the direction of [remaining]. A step never overshoots and never reverses.
 */
internal fun mainGridGlideStep(remaining: Float, frameMs: Float, tauMs: Float = MAIN_GRID_GLIDE_TAU_MS): Float {
    val distance = abs(remaining)
    if (distance == 0f) return 0f
    val share = distance * (1f - exp(-frameMs / tauMs))
    val step = min(distance, max(share, MAIN_GRID_GLIDE_MIN_STEP_PX))
    return if (remaining < 0f) -step else step
}

/**
 * What one frame moves the grid by when the lit row is [delta] from showing with [airPx] of air around it: the glide, on [MAIN_GRID_GLIDE_TAU_MS] while the lit TILE is whole
 * inside the viewport (the air is all that is left to go), and on the faster [MAIN_GRID_GLIDE_FAST_TAU_MS] while the tile is still outside it (more than the air to go), so it comes
 * back in within a few frames - and in steps well short of a row, never in one frame's jump. Never more than what remains, never against it.
 */
internal fun mainGridFrameStep(delta: Float, frameMs: Float, airPx: Float): Float =
    mainGridGlideStep(delta, frameMs, if (abs(delta) > airPx) MAIN_GRID_GLIDE_FAST_TAU_MS else MAIN_GRID_GLIDE_TAU_MS)

/**
 * The frame length the glide's steps are sized by. It is measured between consecutive frames the glide waited for and kept across retargets; after the follower has rested (its
 * last frame is long gone) the first frame is not a measurement, and the length last measured is used, so a step is never sized by the idle gap or by a guess made afresh.
 * [awaitFrameNanos] is the frame clock (a test's is a model).
 */
internal class MainGridFrameClock(private val awaitFrameNanos: suspend () -> Long) {
    private var lastFrameNanos = -1L
    private var frameMs = MAIN_GRID_GLIDE_NOMINAL_FRAME_MS

    /** Waits for the next frame and returns the frame length to size its step by, in milliseconds. */
    suspend fun nextFrameMs(): Float {
        val now = awaitFrameNanos()
        if (lastFrameNanos >= 0L) {
            val gapMs = (now - lastFrameNanos) / 1_000_000f
            if (gapMs <= MAIN_GRID_GLIDE_MAX_FRAME_MS * 2f) frameMs = gapMs.coerceIn(1f, MAIN_GRID_GLIDE_MAX_FRAME_MS)
        }
        lastFrameNanos = now
        return frameMs
    }

    /** The follower is idle: the next frame it waits for starts a measurement afresh. */
    fun rest() {
        lastFrameNanos = -1L
    }
}

/**
 * Where the grid must scroll for the badge at [litIndex] (an index into the grid's items, [columns] to a row) to show whole with [outsetPx] of air around it: positive forward,
 * negative back, 0 when it already does, null when there is nothing to measure from (no such badge, or the grid has not been laid out yet). The place of a row that is not
 * composed is read from the nearest row that is: the grid's rows are all one height ([gapPx] apart).
 */
internal fun LazyGridState.litBadgeRevealDelta(litIndex: Int, columns: Int, gapPx: Int, outsetPx: Float): Float? {
    if (litIndex < 0 || columns < 1) return null
    val info = layoutInfo
    if (litIndex >= info.totalItemsCount) return null
    val litRow = litIndex / columns
    val nearest = info.visibleItemsInfo.minByOrNull { abs(it.row - litRow) } ?: return null
    val height = nearest.size.height.toFloat()
    val top = nearest.offset.y + (litRow - nearest.row) * (height + gapPx)
    return mainGridRevealDelta(top, height, info.viewportStartOffset.toFloat(), info.viewportEndOffset.toFloat(), outsetPx)
}

/**
 * The glide itself, inside a scroll session: follows whatever [revealDelta] reads NOW, frame after frame, until it shows (or the grid cannot move further). It reads the target
 * afresh every frame, so a retarget is a changed number and nothing more; the first step waits for a frame ([clock]); [snap] (reduced motion) moves straight to the target.
 * One [scrollBy] per frame at most. Leaves [clock] resting.
 */
internal suspend fun followLitBadge(
    clock: MainGridFrameClock,
    revealDelta: () -> Float?,
    scrollBy: (Float) -> Float,
    airPx: Float,
    snap: () -> Boolean,
) {
    fun pending(): Float? = revealDelta()?.takeIf { abs(it) > MAIN_GRID_GLIDE_SETTLE_PX }
    try {
        val first = pending() ?: return
        if (snap()) {
            scrollBy(first)
            return
        }
        while (true) {
            val frameMs = clock.nextFrameMs()
            val delta = pending() ?: return
            if (scrollBy(mainGridFrameStep(delta, frameMs, airPx)) == 0f) return
        }
    } finally {
        clock.rest()
    }
}

/**
 * The pad's ring leads the grid: whenever the lit badge changes ([litIndex], -1 for none: a dark ring follows nothing), or the grid is laid out afresh (its columns, or the height
 * of its viewport), the grid glides until that badge shows whole with [outsetPx] of air around it, and does nothing at all when it already does. The ONE scroller for the pad on
 * this grid: the tiles do not ask to be brought into view themselves ([ActivityBadge] `revealOnFocus = false`). A newer lit badge retargets the glide in flight; a finger on
 * the grid takes it over, and a move the pad made while a finger held the grid is followed once the finger lets go. Under reduced motion the grid moves straight to the target.
 */
@Composable
internal fun FollowLitBadge(
    state: LazyGridState,
    litIndex: Int,
    columns: Int,
    gapPx: Int,
    outsetPx: Float,
    reducedMotion: Boolean,
) {
    val wanted = rememberUpdatedState(litIndex)
    val across = rememberUpdatedState(columns)
    val still = rememberUpdatedState(reducedMotion)
    LaunchedEffect(state, gapPx, outsetPx) {
        // Lives as long as the effect: the frame length measured in one glide serves the next.
        val clock = MainGridFrameClock { withFrameNanos { it } }
        snapshotFlow { Triple(wanted.value, across.value, state.layoutInfo.viewportSize.height) }.collect { (lit, _, _) ->
            if (lit >= 0) followWhenTheGridIsFree(state, clock, wanted, across, still, gapPx, outsetPx)
        }
    }
}

/**
 * One follow, inside the grid's scroll session. A finger that has the grid refuses the session (or takes it from a glide under way); the follow then waits for the finger to let go.
 * A follow that never began is tried again then (the pad moved while the finger held the grid: the lit badge must still be brought into view). A glide the finger cut short is not:
 * the finger meant to leave the grid where it left it, and only a NEW move of the pad (a value the collector has waiting) follows again.
 */
private suspend fun followWhenTheGridIsFree(
    state: LazyGridState,
    clock: MainGridFrameClock,
    wanted: State<Int>,
    across: State<Int>,
    still: State<Boolean>,
    gapPx: Int,
    outsetPx: Float,
) {
    repeat(MAIN_GRID_FOLLOW_RETRIES) {
        var began = false
        try {
            state.scroll {
                began = true
                followLitBadge(
                    clock = clock,
                    revealDelta = { state.litBadgeRevealDelta(wanted.value, across.value, gapPx, outsetPx) },
                    scrollBy = { pixels -> scrollBy(pixels) },
                    airPx = outsetPx,
                    snap = { still.value },
                )
            }
            return
        } catch (interrupted: CancellationException) {
            // A finger has the grid: not an error. Cancelled for real (the page left): carry on cancelling.
            coroutineContext.ensureActive()
            snapshotFlow { state.isScrollInProgress }.first { !it }
            if (began) return
        }
    }
}
