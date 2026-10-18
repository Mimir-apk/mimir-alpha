package com.gruning.mimir.rewrite

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * The Activity achievement page's vertical paging : the cards stack top to bottom, but the page's own count made players think a swipe LEFT goes to the
 * next one, and a swipe back up left the card below cut off. Two small pieces, shared by nothing else: a quiet "more below" cue, and a settle rule that leaves a card
 * whole and aligned whichever way the list was flung.
 */

internal const val AchievementMoreBelowCueTag = "achievement-more-below-cue"

/** What a screen reader says for the cue. */
internal const val ActivityMoreBelowDescription = "More games below. Swipe up for the next game."

/** The wait after the page is shown before the cue's first play: the page arrives first, then the hint (one beat on the house overlay clock, twice over). */
internal const val ACTIVITY_CUE_OPEN_DELAY_MS: Long = MimirMotion.OverlayMs * 2L

/** How long the page must sit idle before the cue plays again. Long enough to stay out of the way, short enough to still be there for a player who stopped. */
internal const val ACTIVITY_CUE_IDLE_REPLAY_MS: Long = 8_000L

/** A play is this many down-and-back beats. */
internal const val ACTIVITY_CUE_BEATS: Int = 2

/** The cue shows while there is a card below this one: never on the last card, never with a single card. */
internal fun activityCueVisible(page: Int, pages: Int): Boolean = pages > 1 && page < pages - 1

/**
 * A small chevron at the bottom of the card saying "more below": a short downward motion (one beat down on [MimirMotion.OverlayMs], back on twice that, [ACTIVITY_CUE_BEATS]
 * times) played once as the page opens and again each time the page has idled [ACTIVITY_CUE_IDLE_REPLAY_MS] (a swipe starts the wait over). Under reduced motion it never
 * moves: a still chevron. It does not play while its pane is parked ([LocalMimirMotionLive]). It takes no touch, so a finger on it lands on the card under it. The motion is read in
 * the draw phase only, so playing it recomposes nothing. [page] only restarts the idle wait.
 *
 * The idle wait is the PLAYER's: [activity] is a count the page bumps on any input it takes (a finger landing or lifting, a pad press it consumed), and [busy] is true while a
 * finger is down or the list is scrolling. Any change of either starts the wait over, the cue never plays while [busy], and a play under way when input arrives is cut short
 * (the chevron rests). So it replays only after [ACTIVITY_CUE_IDLE_REPLAY_MS] of quiet. [onPlay] is called as each play begins (a test seam).
 */
@Composable
internal fun ActivityMoreBelowCue(
    visible: Boolean,
    page: Int,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    activity: () -> Int = { 0 },
    busy: () -> Boolean = { false },
    onPlay: () -> Unit = {},
) {
    val bob = remember { Animatable(0f) }
    val live = LocalMimirMotionLive.current
    val latestActivity by rememberUpdatedState(activity)
    val latestBusy by rememberUpdatedState(busy)
    val latestOnPlay by rememberUpdatedState(onPlay)
    // The open play happens once for the page, however often the cue is hidden and shown again by turning to the last card and back.
    val openPlayed = remember { booleanArrayOf(false) }
    LaunchedEffect(visible, page, reducedMotion) {
        bob.snapTo(0f)
        if (!visible || reducedMotion) return@LaunchedEffect
        // Whatever changes in this triple - the pane going live or dark, input arriving, a finger or a scroll starting or ending - restarts the wait from nothing.
        snapshotFlow { Triple(live.value, latestActivity(), latestBusy()) }.collectLatest { (isLive, _, isBusy) ->
            bob.snapTo(0f)
            if (!isLive || isBusy) return@collectLatest
            if (!openPlayed[0]) {
                delay(ACTIVITY_CUE_OPEN_DELAY_MS)
                openPlayed[0] = true
                playActivityCue(bob) { latestOnPlay() }
            }
            while (true) {
                delay(ACTIVITY_CUE_IDLE_REPLAY_MS)
                playActivityCue(bob) { latestOnPlay() }
            }
        }
    }
    if (visible) ActivityMoreBelowGlyph(bob = bob, modifier = modifier)
}

private suspend fun playActivityCue(bob: Animatable<Float, AnimationVector1D>, onPlay: () -> Unit) {
    onPlay()
    repeat(ACTIVITY_CUE_BEATS) {
        bob.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
        bob.animateTo(0f, tween(MimirMotion.OverlayMs * 2, easing = MimirMotion.Easing))
    }
}

/** The chevron: two round-capped strokes, inside a box that leaves room for its travel; it stands in the card's own 16 dp foot padding, so it never covers the detail text. Its own function so the condition above has a single-call arm. */
@Composable
private fun ActivityMoreBelowGlyph(bob: Animatable<Float, AnimationVector1D>, modifier: Modifier) {
    val color = LocalMimirPalette.current.accentBright
    Canvas(
        modifier
            .size(width = MimirSpacing.xl, height = MimirSpacing.lg)
            .semantics { contentDescription = ActivityMoreBelowDescription }
            .testTag(AchievementMoreBelowCueTag),
    ) {
        val t = bob.value
        val stroke = MimirSpacing.focusRingWidth.toPx()
        val left = size.width * 0.18f
        val middle = size.width * 0.5f
        val right = size.width * 0.82f
        val top = size.height * 0.15f + MimirSpacing.xs.toPx() * t
        val point = top + size.height * 0.45f
        val alpha = 0.7f + 0.3f * t
        drawLine(color = color, start = Offset(left, top), end = Offset(middle, point), strokeWidth = stroke, cap = StrokeCap.Round, alpha = alpha)
        drawLine(color = color, start = Offset(middle, point), end = Offset(right, top), strokeWidth = stroke, cap = StrokeCap.Round, alpha = alpha)
    }
}

/**
 * Where a list of whole-page cards must settle, or null when it already rests aligned: [firstIndex] is the first visible card and [offsetPx] how far it is scrolled past
 * its start (the list's own `firstVisibleItemScrollOffset`), [itemSizePx] a card's height. A card rests whole when its start is the viewport's start; anything else
 * goes to the NEAREST card start, the same way up and down (past halfway is the next card, short of it is this one). Within a pixel of a start counts as there.
 */
internal fun activityCardSettleTarget(firstIndex: Int, offsetPx: Int, itemSizePx: Int, itemCount: Int): Int? {
    if (itemCount <= 0 || itemSizePx <= 0) return null
    val into = offsetPx.coerceAtLeast(0)
    if (into <= 1 || itemSizePx - into <= 1) return null
    val target = if (into * 2 >= itemSizePx) firstIndex + 1 else firstIndex
    return target.coerceIn(0, itemCount - 1)
}

/** How long the list must have stood still, off a card start and with no finger on it, before it glides home. */
internal const val ACTIVITY_SETTLE_QUIET_MS: Long = 80L

/**
 * Settles the pager on a whole card whichever way it was flung. The snap fling is the first line; this is the guarantee behind it, and it does not wait to SEE a scroll end
 * (a scroll a nested child hands the list in raw deltas, or one that begins and ends between two looks, is never "in progress" when looked at): it watches the state the list
 * is in. Whenever the list stands off a card start with nothing scrolling it and no finger on it ([touching]), it waits [ACTIVITY_SETTLE_QUIET_MS], looks again, and glides to
 * the nearest card start - the same way up as down. A finger that lands on the glide takes over; a glide that did not move the list is made good with a jump. Reads the list's
 * state off the composition, so it recomposes nothing.
 */
@Composable
internal fun ActivityCardSettleEffect(listState: LazyListState, enabled: Boolean, touching: () -> Boolean = { false }) {
    val latestTouching by rememberUpdatedState(touching)
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        // The card start to go to, or null when there is nothing to do: the list rests on a card, is being scrolled, or has a finger on it.
        fun pending(): Int? {
            if (listState.isScrollInProgress || latestTouching()) return null
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull() ?: return null
            return activityCardSettleTarget(first.index, listState.firstVisibleItemScrollOffset, first.size, info.totalItemsCount)
        }
        while (true) {
            snapshotFlow { pending() }.first { it != null }
            delay(ACTIVITY_SETTLE_QUIET_MS)
            val target = pending() ?: continue
            val before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            // The glide is its own job, watched while it runs: the moment a finger lands it is cancelled, so the list stops where it is and the finger has it (a touch the list
            // itself never sees, one a child handles, would otherwise leave it moving under the hand).
            coroutineScope {
                val glide = launch {
                    listState.animateScrollToItem(target)
                    if (pending() != null && before == (listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)) listState.scrollToItem(target)
                }
                val watch = launch {
                    snapshotFlow { latestTouching() }.first { it }
                    glide.cancel()
                }
                glide.join()
                watch.cancel()
            }
        }
    }
}
