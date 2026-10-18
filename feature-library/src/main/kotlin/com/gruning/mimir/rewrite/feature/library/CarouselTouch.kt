package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Touch movement for the Main carousels: Home shelf and Library gamelist share this gesture grammar.
//
// One grammar for every row the controller moves:
//  - a horizontal drag moves the row 1:1 with the finger; the finger owns the row's centre;
//  - on lift the row settles on an item chosen by the SAME rounding the controller's glide ends on, with a
//    fling projected by friction, and only THEN is focus moved, through the one `FocusAuthority.requestFocus`
//    that a tap and the pad both reach. The hero, the title and the details follow the focus exactly as for a
//    D-pad move; there is no second selection state;
//  - the axis is locked at the touch slop, so a vertical gesture is never taken;
//  - a tap is untouched: touch is activate-at-a-point , the tile's own press
//    does what it did before.
//
// Every per-frame value lives in a state that is read in a layout, draw or effect lambda, never in
// composition: fifty moves of a drag recompose a row at most when the drag starts and when it ends, plus
// once for each tile the mounted window crosses.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Where a pointer's travel has locked: still inside the slop, a drag along the row, or a gesture that is not ours. */
internal enum class CarouselAxisLock { PENDING, HORIZONTAL, VERTICAL }

/**
 * The axis a gesture locks to once its travel leaves the touch slop: horizontal when the travel is mostly along the
 * row, vertical (not ours, and left alone for whoever scrolls) when it is not. A tie goes to vertical, so a diagonal
 * never steals a scroll.
 */
internal fun carouselAxisLock(totalDx: Float, totalDy: Float, touchSlopPx: Float): CarouselAxisLock {
    val across = abs(totalDx)
    val down = abs(totalDy)
    if (maxOf(across, down) < touchSlopPx) return CarouselAxisLock.PENDING
    return if (across > down) CarouselAxisLock.HORIZONTAL else CarouselAxisLock.VERTICAL
}

/** Exponential-decay friction of a fling, per second: the distance a release travels is its velocity over this. */
internal const val CarouselFlingFrictionPerSec = 4.2f

/** A fling never carries the row further than this many items past where the finger lifted. */
internal const val CarouselFlingMaxTiles = 5f

/** Below this release speed (items per second) the row settles on the nearest item: a slow drag is not a fling. */
internal const val CarouselFlingMinTilesPerSec = 1.5f

/** How far past either end of a finite row the finger may pull it, in items. It settles back on lift. */
internal const val CarouselOverscrollTiles = 0.35f

/** The glide a release starts at never exceeds this many items per second, however hard the flick. */
internal const val CarouselReleaseSpeedMaxTilesPerSec = 24f

/** How far the finger's release velocity (items per second, the row's centre moving up the index line) carries it. */
internal fun carouselFlingProjectionTiles(velocityTilesPerSec: Float): Float {
    if (abs(velocityTilesPerSec) < CarouselFlingMinTilesPerSec) return 0f
    return (velocityTilesPerSec / CarouselFlingFrictionPerSec).coerceIn(-CarouselFlingMaxTiles, CarouselFlingMaxTiles)
}

/** The item index the row settles on: where the finger lifted, plus the fling, rounded to a whole item. */
internal fun carouselSettleIndex(centerTiles: Float, velocityTilesPerSec: Float): Int =
    (centerTiles + carouselFlingProjectionTiles(velocityTilesPerSec)).roundToInt()

/** A finite row's centre while the finger holds it: free between the first and last item, a little elastic beyond. */
internal fun carouselClampCenter(center: Float, itemCount: Int): Float =
    center.coerceIn(-CarouselOverscrollTiles, (itemCount - 1).coerceAtLeast(0) + CarouselOverscrollTiles)

/**
 * The inverse of `coverflowSlotOffsetDp`: the seat position whose centre sits [offsetDp] from the hero seat, for a
 * row whose first neighbour is [firstNeighborDp] away and whose later seats are [restPitchDp] apart. Dragging a
 * coverflow by a finger distance moves its centre by exactly this many seats, so the tile that was in the hero seat
 * follows the finger one for one.
 */
internal fun coverflowPositionForOffsetDp(offsetDp: Float, firstNeighborDp: Float, restPitchDp: Float): Float {
    if (offsetDp == 0f || firstNeighborDp <= 0f || restPitchDp <= 0f) return 0f
    val distance = abs(offsetDp)
    val position = if (distance <= firstNeighborDp) {
        distance / firstNeighborDp
    } else {
        1f + (distance - firstNeighborDp) / restPitchDp
    }
    return if (offsetDp < 0f) -position else position
}

/** The undamped frequency of [CoverflowSeatSpring] (critically damped, stiffness 500, mass 1): sqrt(stiffness). */
private const val CarouselSeatSpringOmega = 22.36068f

/**
 * A release velocity safe to hand a critically damped seat spring: toward the target only, and no faster than the
 * spring's own frequency times the distance, which is the speed above which the spring would overshoot its seat. A
 * flick away from the target hands it none.
 */
internal fun carouselSafeSettleVelocity(velocitySlotsPerSec: Float, distanceSlots: Float): Float {
    if (velocitySlotsPerSec == 0f || distanceSlots == 0f) return 0f
    if ((velocitySlotsPerSec > 0f) != (distanceSlots > 0f)) return 0f
    val limit = CarouselSeatSpringOmega * abs(distanceSlots)
    val magnitude = minOf(abs(velocitySlotsPerSec), limit)
    return if (velocitySlotsPerSec > 0f) magnitude else -magnitude
}

/**
 * Horizontal drag of a carousel, direction-locked: nothing is taken until the travel leaves the touch slop, and a
 * gesture that leaves it mostly vertical is let go untouched, so a vertical scroll, a pull-down panel or an edge back
 * further up the tree gets it whole. Once locked the drag is consumed (which cancels a tile's pending tap) and
 * reported without suspending: [onDragStart] once, [onDrag] for each move in pixels (the first carrying only the
 * travel beyond the slop, so the row does not jump), and [onDragEnd] when the finger lifts or the gesture is lost,
 * with the release velocity in pixels per second (the framework reports a cancelled touch as a lift, so it carries
 * the speed the finger had; a gesture ended any other way carries 0).
 *
 * [startRegion] limits where a touch may begin (in this node's coordinates); [enabled] gates the whole gesture and is
 * read live, so toggling it never restarts the pointer input. A down that arrives already consumed by an ancestor's
 * shield is still seen here, and its first move, consumed too, ends the gesture before it starts.
 *
 * [gestureKey] is the identity of what the drag moves (the row's motion state). The callbacks are read live, which is
 * right while that identity holds; when it changes under a finger (the list was rebuilt and the row was made afresh),
 * the gesture is ABANDONED: [onDragEnd] is not called, because its callbacks now belong to a row the drag never
 * started on, and the rest of that touch is ignored.
 */
@Composable
internal fun Modifier.carouselTouchDrag(
    gestureKey: Any?,
    enabled: Boolean = true,
    startRegion: ((Offset) -> Boolean)? = null,
    onDragStart: () -> Unit,
    onDrag: (dxPx: Float) -> Unit,
    onDragEnd: (velocityXPxPerSec: Float) -> Unit,
): Modifier {
    val latestEnabled = rememberUpdatedState(enabled)
    val latestRegion = rememberUpdatedState(startRegion)
    val latestStart = rememberUpdatedState(onDragStart)
    val latestDrag = rememberUpdatedState(onDrag)
    val latestEnd = rememberUpdatedState(onDragEnd)
    val latestKey = rememberUpdatedState(gestureKey)
    return this.pointerInput(gestureKey) {
        awaitEachGesture {
            // A tile's own tap consumes the down on its way (children run first), so the down is taken as it is.
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!latestEnabled.value) return@awaitEachGesture
            val region = latestRegion.value
            if (region != null && !region(down.position)) return@awaitEachGesture

            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            val slop = viewConfiguration.touchSlop
            var totalX = 0f
            var totalY = 0f
            var locked = false
            while (!locked) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                // Someone ahead of us took it (a shield, a scroller): it is theirs.
                if (change.isConsumed) return@awaitEachGesture
                // A lift inside the slop is a tap, and the tile's own tap handles it.
                if (change.changedToUpIgnoreConsumed()) return@awaitEachGesture
                tracker.addPointerInputChange(change)
                val travel = change.positionChange()
                totalX += travel.x
                totalY += travel.y
                when (carouselAxisLock(totalX, totalY, slop)) {
                    CarouselAxisLock.PENDING -> Unit
                    CarouselAxisLock.VERTICAL -> return@awaitEachGesture
                    CarouselAxisLock.HORIZONTAL -> {
                        change.consume()
                        locked = true
                    }
                }
            }

            var velocityX = 0f
            val startedOn = latestKey.value
            latestStart.value()
            try {
                latestDrag.value(totalX - (if (totalX > 0f) slop else -slop))
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        tracker.addPointerInputChange(change)
                        velocityX = tracker.calculateVelocity().x
                        break
                    }
                    if (change.isConsumed) break
                    tracker.addPointerInputChange(change)
                    val dx = change.positionChange().x
                    if (dx != 0f) latestDrag.value(dx)
                    change.consume()
                }
            } finally {
                // Cancelled because the row it drives was replaced: nothing to settle, and the new row's callbacks
                // are not this drag's to call. Any other ending (a lift, a lost touch, a pointer that went) is reported.
                if (latestKey.value == startedOn) latestEnd.value(velocityX)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Coverflow rows: the Library's platform shelf, the Android shelf and the gamelist carousel. Each owns ONE
// continuous centre (CoverflowRingMotionState / CoverflowLinearMotionState); the finger drives that centre
// directly, and a tile's pose, seat and shadow already follow it.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** The measured spacing of a coverflow, written by the row that solves it and read by its drag. Plain memory. */
@Stable
internal class CoverflowTouchSpacing {
    @JvmField var firstNeighborDp: Float = 0f
    @JvmField var restPitchDp: Float = 0f
}

/** Where a coverflow drag began: its centre then, and how far the finger has travelled since. Plain memory. */
private class CoverflowTouchStart {
    @JvmField var center: Float = 0f
    @JvmField var travelPx: Float = 0f
}

/** The centre, in items, that follows a finger [travelPx] from the centre [startCenter] it began at. */
private fun coverflowDraggedCenter(
    startCenter: Float,
    travelPx: Float,
    density: Float,
    firstNeighborDp: Float,
    restPitchDp: Float,
): Float = startCenter - coverflowPositionForOffsetDp(travelPx / density, firstNeighborDp, restPitchDp)

/** The release velocity of a finger, as the row's centre moving up the index line, in items per second. */
private fun coverflowReleaseTilesPerSec(velocityXPxPerSec: Float, density: Float, restPitchDp: Float): Float =
    if (restPitchDp <= 0f) 0f else -velocityXPxPerSec / density / restPitchDp

/**
 * Touch for a finite, windowed coverflow ([CoverflowLinearMotionState]): the gamelist carousel and the Android shelf.
 * [onSelect] receives the item the row settles on; it is called once, at the lift, and must reach the authority. It
 * returns whether the authority now holds that item: when it does not, [focusedIndex] (the item the row is keyed to)
 * is where the row settles.
 */
@Composable
internal fun Modifier.coverflowLinearTouch(
    motion: CoverflowLinearMotionState,
    itemCount: Int,
    focusedIndex: Int,
    firstNeighborDp: Float,
    restPitchDp: Float,
    enabled: Boolean,
    onSelect: (Int) -> Boolean,
): Modifier {
    val density = LocalDensity.current.density
    val reducedMotion = mimirReducedMotion()
    val start = remember(motion) { CoverflowTouchStart() }
    return carouselTouchDrag(
        gestureKey = motion,
        enabled = enabled && itemCount > 1,
        onDragStart = {
            start.center = motion.center.value
            start.travelPx = 0f
            motion.touchCenter = start.center
            motion.touchEngaged = true
            motion.touchHeld = true
        },
        onDrag = { dx ->
            start.travelPx += dx
            motion.touchCenter = carouselClampCenter(
                coverflowDraggedCenter(start.center, start.travelPx, density, firstNeighborDp, restPitchDp),
                itemCount,
            )
        },
        onDragEnd = { velocityX ->
            val tilesPerSec = coverflowReleaseTilesPerSec(velocityX, density, restPitchDp)
            val target = carouselSettleIndex(motion.touchCenter, tilesPerSec).coerceIn(0, itemCount - 1)
            motion.releaseSpeedTilesPerSec = abs(tilesPerSec).coerceAtMost(CarouselReleaseSpeedMaxTilesPerSec)
            motion.longGlide = true
            motion.instantSettle = reducedMotion
            motion.targetCenter = target.toFloat()
            motion.touchHeld = false
            // The authority may refuse the selection (another scope took input mid-drag). The row is then still keyed to the
            // item it was on, so it settles back there instead of staying on an item focus never reached.
            if (!onSelect(target)) motion.targetCenter = focusedIndex.coerceIn(0, itemCount - 1).toFloat()
        },
    )
}

/**
 * Touch for a cyclic coverflow ([CoverflowRingMotionState]): the Library's platform shelf. The centre is free to run
 * round the ring; [onSelect] receives the CANONICAL index (0 until [itemCount]) the row settles on and returns true
 * only when it moved focus onto that item. [focusedIndex] is the index the ring is keyed to: a release that moves
 * nothing (the same item, or a refusal) settles the ring back on it.
 */
@Composable
internal fun Modifier.coverflowRingTouch(
    motion: CoverflowRingMotionState,
    itemCount: Int,
    focusedIndex: Int,
    spacing: CoverflowTouchSpacing,
    enabled: Boolean,
    onSelect: (Int) -> Boolean,
): Modifier {
    val density = LocalDensity.current.density
    val reducedMotion = mimirReducedMotion()
    val start = remember(motion) { CoverflowTouchStart() }
    return carouselTouchDrag(
        gestureKey = motion,
        enabled = enabled && itemCount > 1,
        onDragStart = {
            start.center = motion.center.value
            start.travelPx = 0f
            motion.targetCenter = start.center
            motion.touchCenter = start.center
            motion.touchFlush = true
            // An even ring's far seam is a seat at rest; while a finger runs the ring round it is masked.
            if (itemCount >= 4 && itemCount % 2 == 0) {
                motion.evenSeamActive = true
                motion.evenSeamPrepared = true
                motion.evenSeamDeadlineNanos = 0L
            }
            motion.touchEngaged = true
            motion.touchHeld = true
        },
        onDrag = { dx ->
            start.travelPx += dx
            val dragged = coverflowDraggedCenter(
                start.center,
                start.travelPx,
                density,
                spacing.firstNeighborDp,
                spacing.restPitchDp,
            )
            // One or two items never wrap: they keep their canonical seats, like the finite rows.
            motion.touchCenter = if (itemCount <= 2) carouselClampCenter(dragged, itemCount) else dragged
            motion.targetCenter = motion.touchCenter
        },
        onDragEnd = { velocityX ->
            val tilesPerSec = coverflowReleaseTilesPerSec(velocityX, density, spacing.restPitchDp)
            val settled = carouselSettleIndex(motion.touchCenter, tilesPerSec)
            val unwrapped = if (itemCount <= 2) settled.coerceIn(0, itemCount - 1) else settled
            val canonical = Math.floorMod(unwrapped, itemCount)
            motion.releaseSpeedTilesPerSec = abs(tilesPerSec).coerceAtMost(CarouselReleaseSpeedMaxTilesPerSec)
            motion.instantSettle = reducedMotion
            motion.targetCenter = unwrapped.toFloat()
            motion.touchHeld = false
            // A release that moves focus re-keys the glide through the focus change. One that does not (it landed on the item
            // already focused, or the authority refused because another scope took input mid-drag) would leave the ring where
            // the finger put it and engaged for good, so it re-keys the glide itself and the ring settles on the focused item.
            val moved = onSelect(canonical)
            if (!moved || canonical == focusedIndex) motion.touchReleaseGeneration += 1
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The Home shelf: a left-anchored finite strip. Its position is the window start (the item at the first
// seat), a float while a finger or a settle moves it. A release picks a whole window start and the focused
// item is the one now at the ring's seat, kept inside the seats the controller's own hysteresis allows, so
// the pad finds the strip exactly as it would have left it.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The state of a touch on the Home strip. [held] is the finger down; [engaged] runs from the first move until the
 * settle has finished, and is the one flag the shelf reads in composition (it mounts around where the strip is
 * painted while it is set); [position] is the window start the finger asks for; [settleGeneration] re-keys the settle.
 */
@Stable
internal class CarouselStripTouch {
    var held by mutableStateOf(false)
    var engaged by mutableStateOf(false)
    var position by mutableFloatStateOf(0f)
    var settleGeneration by mutableIntStateOf(0)

    /** Slots per second the strip was moving at on lift, handed to the settle spring once; null for a pad-driven settle. */
    @JvmField var settleVelocity: Float? = null

    /** True from the first move until the settle has written the finger's last position to the strip. */
    @JvmField var flushPending: Boolean = false

    fun begin(from: Float) {
        position = from
        flushPending = true
        engaged = true
        held = true
    }

    fun move(to: Float) {
        position = to
    }

    fun release(velocitySlotsPerSec: Float) {
        settleVelocity = velocitySlotsPerSec
        held = false
        settleGeneration += 1
    }

    /** The lift's velocity, once: a settle that a pad move re-keys must not inherit it. */
    fun takeSettleVelocity(): Float? {
        val velocity = settleVelocity
        settleVelocity = null
        return velocity
    }
}

/**
 * The two movers of the Home strip's painted position [windowPos] (the window start, as a float).
 *
 * A finger moves it directly: while the strip is held, the position follows the one the gesture asks for. The logical
 * [windowStart] (the pad's rule, or a lift's choice) moves it by the shared seat spring. The spring is RETARGETED, never
 * cancelled and restarted: cancelling an animation resets its velocity, and a held D-pad runs the strip through many
 * window changes a second, each of which must carry on from the speed the last one reached (the old per-tile springs
 * did, by the same mechanism: each new target interrupts the running animation and starts from its velocity). A lift
 * hands the spring the finger's speed, held to what a critically damped spring can take toward its seat without
 * passing it; a release re-keys the settle through [CarouselStripTouch.settleGeneration] even when the window did not move.
 */
@Composable
internal fun HomeShelfStripDriver(
    touch: CarouselStripTouch,
    windowPos: Animatable<Float, AnimationVector1D>,
    windowStart: Int,
    reducedMotion: Boolean,
) {
    val latestWindowStart = rememberUpdatedState(windowStart)
    // The finger's side: the write is made here, from a coroutine, like every other write to the position; the gesture
    // only states where it wants it.
    LaunchedEffect(touch, windowPos) {
        snapshotFlow { touch.position }.collect { wanted ->
            if (touch.held) windowPos.snapTo(wanted)
        }
    }
    // The window's side: one long-lived collector retargets the spring.
    LaunchedEffect(touch, windowPos, reducedMotion) {
        snapshotFlow { latestWindowStart.value to touch.settleGeneration }.collect {
            if (touch.held) return@collect
            // The last place the finger asked for may not have reached the strip yet: it settles from where it was lifted.
            if (touch.flushPending) {
                touch.flushPending = false
                windowPos.snapTo(touch.position)
            }
            val target = latestWindowStart.value.toFloat()
            val lift = touch.takeSettleVelocity()
            if (reducedMotion) {
                windowPos.snapTo(target)
                touch.engaged = false
            } else {
                launch {
                    // Read as the animation starts, while the one it replaces is still running: that is the velocity it carries on from.
                    val velocity = if (lift == null) {
                        windowPos.velocity
                    } else {
                        carouselSafeSettleVelocity(lift, target - windowPos.value)
                    }
                    windowPos.animateTo(targetValue = target, animationSpec = CoverflowSeatSpring, initialVelocity = velocity)
                    // Settled: the shelf goes back to mounting around the logical window.
                    touch.engaged = false
                }
            }
        }
    }
}

/** The window start the finger asks for after dragging by [dxPx]: the strip follows the finger, so it runs the other way. */
internal fun homeShelfDraggedWindowPosition(position: Float, dxPx: Float, stridePx: Float, maxWindowStart: Int): Float {
    if (stridePx <= 0f) return position
    return (position - dxPx / stridePx).coerceIn(-CarouselOverscrollTiles, maxWindowStart + CarouselOverscrollTiles)
}

/** The whole window start a strip released at [position] settles on, with its fling, kept inside the strip. */
internal fun homeShelfSettleWindowStart(
    position: Float,
    velocitySlotsPerSec: Float,
    itemCount: Int,
    visibleSeatCount: Int,
): Int {
    val maxStart = (itemCount - visibleSeatCount).coerceAtLeast(0)
    return carouselSettleIndex(position, velocitySlotsPerSec).coerceIn(0, maxStart)
}

/**
 * The item indices the controller's own window rule ([homeShelfWindowStart]) leaves a window starting at
 * [windowStart] standing still for: the first and last seat give way to the next item (the look-ahead seat), except
 * at the true ends of the list. A focus inside this range keeps the window where a touch put it.
 */
internal fun homeShelfFocusRangeForWindow(windowStart: Int, itemCount: Int, visibleSeatCount: Int): IntRange {
    if (itemCount <= 0 || visibleSeatCount <= 0) return IntRange.EMPTY
    if (itemCount <= visibleSeatCount) return 0 until itemCount
    val maxStart = itemCount - visibleSeatCount
    val start = windowStart.coerceIn(0, maxStart)
    val first = if (start == 0) 0 else start + 1
    val last = if (start == maxStart) itemCount - 1 else start + visibleSeatCount - 2
    return first..maxOf(first, last)
}

/** The item a strip settled at [windowStart] focuses: the one at the ring's [currentSeat], held inside the allowed range. */
internal fun homeShelfFocusForWindow(currentSeat: Int, windowStart: Int, itemCount: Int, visibleSeatCount: Int): Int {
    val range = homeShelfFocusRangeForWindow(windowStart, itemCount, visibleSeatCount)
    if (range.isEmpty()) return 0
    return (windowStart + currentSeat).coerceIn(range.first, range.last)
}

/**
 * The mounted window of a strip a finger or a settle has carried away from the logical window: the union of the
 * window at [windowStart] and the window at [mountStart] (where the strip is painted), each with its one-item buffer.
 * Seats are measured from the LOGICAL start, so the ring and the roles keep their meaning; the shelf decides a
 * tile's visibility from where it is painted.
 */
internal fun <T> homeShelfTouchMountedWindow(
    items: List<T>,
    windowStart: Int,
    mountStart: Int,
    visibleSeatCount: Int,
): List<HomeShelfVisibleSlot<T>> {
    val logical = homeShelfBufferedWindow(items, windowStart, visibleSeatCount)
    if (logical.isEmpty() || mountStart == windowStart) return logical
    val mounted = homeShelfBufferedWindow(items, mountStart, visibleSeatCount)
    val first = minOf(logical.first().itemIndex, mounted.first().itemIndex)
    val last = maxOf(logical.last().itemIndex, mounted.last().itemIndex)
    return (first..last).map { index ->
        HomeShelfVisibleSlot(item = items[index], itemIndex = index, slotOffset = index - windowStart)
    }
}
