package com.gruning.mimir.rewrite.core.companionnative

import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OR-11: the primary M menu takes D-pad focus while it is shown.
 *
 * The focus is one index the controller keeps (see [QuickMenuFocus]), drawn as a ring above the coins. It is not View focus
 * and it does not touch the app's FocusAuthority: the menu is a native overlay window that already owns key input while it is
 * open, so this only decides what those keys mean. Everything here is plain Int/Float logic so it runs on the JVM.
 */

/**
 * True: the ring is lit on the first coin the moment the menu opens, so "the current item" is always visible and A acts on it.
 * False: the ring stays dark until the first key (that key lights the first coin and does not move or activate). One
 * constant, both behaviours tested.
 */
internal const val QuickMenuRingLitOnOpen = true

/** A hat or stick axis counts as pressed at half travel, the same threshold the app's own controller reading uses. */
internal const val QuickMenuAxisThreshold = 0.5f

/** The ring is the product's dense key ring: one bright stroke, 3 dp wide, at 95 percent, hugging the coin's disc. */
internal const val QuickMenuRingStrokeDp = 3f

/** The coin's own metal rim, in dp: its disc ends this far inside the coin's box, and the rim is stroked on that edge. */
internal const val CompanionCoinRimDp = 1.25f

/**
 * Where the ring's centre line stands outside the coin's box edge. A focused coin hands its rim to the ring ([CompanionMenuCoinView.ringed]: it draws its ground only), so the ring is
 * the coin's ONE edge: its inner edge is the disc's edge, one rim inside the box. At the 2.5 dp this was, the ring stood off the rim with a dark gap between the two, two outlines
 * (a focused element hands its rim to the ring; sweep 10-09, the Bottom's menu).
 */
internal const val QuickMenuRingOutsetDp = QuickMenuRingStrokeDp / 2f - CompanionCoinRimDp
internal const val QuickMenuRingAlpha = 242

/** The ring's vertical light (`drawMimirFocusTileRing`): top lifted toward white, bottom shaded toward black. */
internal const val QuickMenuRingLift = 0.45f
internal const val QuickMenuRingShade = 0.22f

internal enum class QuickMenuNavKey { UP, DOWN, LEFT, RIGHT, CONFIRM }

/**
 * The keys the open primary menu reads as focus movement or confirm. Back and B are not here on purpose: they keep their
 * dismiss-after-release route in [primaryQuickMenuKeyResult]. The confirm set is the app's own FACE_A group (A, D-pad
 * centre, Enter); the menu uses the factory layout, as its B and Back handling already does.
 */
internal fun quickMenuNavKeyFor(keyCode: Int): QuickMenuNavKey? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> QuickMenuNavKey.UP
    KeyEvent.KEYCODE_DPAD_DOWN -> QuickMenuNavKey.DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> QuickMenuNavKey.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> QuickMenuNavKey.RIGHT
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    -> QuickMenuNavKey.CONFIRM
    else -> null
}

/**
 * The menu's whole key-focus state. [litIndex] is the coin wearing the ring, or null while it is dark. [confirmDownOn] is
 * the coin a confirm press went down on, so the release can activate exactly that coin and only if the press began here.
 */
internal data class QuickMenuFocus(
    val itemCount: Int,
    val litIndex: Int?,
    val confirmDownOn: Int? = null,
) {
    companion object {
        fun opened(itemCount: Int, litOnOpen: Boolean = QuickMenuRingLitOnOpen): QuickMenuFocus =
            QuickMenuFocus(itemCount, if (litOnOpen && itemCount > 0) 0 else null)
    }
}

internal data class QuickMenuFocusStep(val state: QuickMenuFocus, val activate: Int? = null)

/**
 * One key event against the focus state.
 *
 * Navigation acts on DOWN (a held key repeats, and the grid clamps, so repeats are harmless). Confirm arms on DOWN and
 * activates on UP, and only for the coin it armed on: a confirm whose DOWN never reached this menu, or one that a move
 * interrupted, activates nothing. Activating on release matters because the menu window is removed after its command, and
 * removing it on DOWN would hand the matching UP to the covered app (the same reason Back dismisses after release).
 *
 * With the ring dark, the first navigation or confirm DOWN lights the first coin and does nothing else.
 */
internal fun reduceQuickMenuFocus(
    state: QuickMenuFocus,
    key: QuickMenuNavKey,
    action: Int,
    repeatCount: Int = 0,
): QuickMenuFocusStep {
    if (state.itemCount <= 0) return QuickMenuFocusStep(state)
    val lit = state.litIndex
    return when (action) {
        KeyEvent.ACTION_DOWN -> when {
            lit == null -> QuickMenuFocusStep(state.copy(litIndex = 0, confirmDownOn = null))
            key == QuickMenuNavKey.CONFIRM ->
                if (repeatCount > 0) QuickMenuFocusStep(state) else QuickMenuFocusStep(state.copy(confirmDownOn = lit))
            else -> QuickMenuFocusStep(
                state.copy(litIndex = quickMenuFocusNeighbour(lit, state.itemCount, key), confirmDownOn = null),
            )
        }
        KeyEvent.ACTION_UP ->
            if (key == QuickMenuNavKey.CONFIRM) {
                QuickMenuFocusStep(state.copy(confirmDownOn = null), activate = state.confirmDownOn)
            } else {
                QuickMenuFocusStep(state)
            }
        else -> QuickMenuFocusStep(state)
    }
}

/**
 * Where a direction takes the ring in the menu's own grid ([companionOverlayMenuGridColumns] across, balanced rows, a short
 * last line centred half a column in from each side, exactly as the menu draws it).
 *
 * Left and right stay inside their line and clamp at its ends; they do not wrap onto the next line. Up and down land on
 * the coin in the next line whose centre is nearest, a tie going to the left one, and clamp at the first and last line.
 * A move that has nowhere to go returns [index] unchanged.
 */
internal fun quickMenuFocusNeighbour(index: Int, itemCount: Int, key: QuickMenuNavKey): Int {
    if (itemCount <= 0 || index !in 0 until itemCount) return index
    val columns = companionOverlayMenuGridColumns(itemCount)
    if (columns <= 0) return index
    val line = index / columns
    val first = line * columns
    val end = min(itemCount, first + columns)
    return when (key) {
        QuickMenuNavKey.LEFT -> if (index > first) index - 1 else index
        QuickMenuNavKey.RIGHT -> if (index + 1 < end) index + 1 else index
        QuickMenuNavKey.UP -> nearestInLine(line - 1, index, itemCount, columns)
        QuickMenuNavKey.DOWN -> nearestInLine(line + 1, index, itemCount, columns)
        QuickMenuNavKey.CONFIRM -> index
    }
}

/** A coin's centre across the menu, in columns from the left edge; a short line is centred like the menu draws it. */
internal fun quickMenuItemCenterX(index: Int, itemCount: Int, columns: Int): Float {
    val first = (index / columns) * columns
    val lineSize = min(itemCount - first, columns)
    val slack = (columns - lineSize) / 2f
    return slack + (index - first) + 0.5f
}

private fun nearestInLine(targetLine: Int, from: Int, itemCount: Int, columns: Int): Int {
    val start = targetLine * columns
    if (targetLine < 0 || start >= itemCount) return from
    val fromX = quickMenuItemCenterX(from, itemCount, columns)
    var best = start
    var bestGap = abs(quickMenuItemCenterX(start, itemCount, columns) - fromX)
    for (candidate in start + 1 until min(itemCount, start + columns)) {
        val gap = abs(quickMenuItemCenterX(candidate, itemCount, columns) - fromX)
        if (gap < bestGap) {
            best = candidate
            bestGap = gap
        }
    }
    return best
}

/**
 * The app reads the D-pad as a KEYCODE_DPAD key or as AXIS_HAT_X/Y, and the left stick as AXIS_X/Y. A joystick sample is
 * handed to the menu as motion, and the menu root used to swallow it, so a pad that reports its D-pad as a hat did nothing.
 * Returns -1, 0 or 1 for one axis: the hat wins when it is past [threshold], else the stick.
 */
internal fun quickMenuAxisDirection(hat: Float, stick: Float, threshold: Float = QuickMenuAxisThreshold): Int = when {
    abs(hat) >= threshold -> if (hat < 0f) -1 else 1
    abs(stick) >= threshold -> if (stick < 0f) -1 else 1
    else -> 0
}

/**
 * Turns held-direction samples into key presses: a direction reports once when it is entered and again only after it was
 * released or reversed. X is reported before Y, so a diagonal is two moves.
 */
internal class QuickMenuAxisEdges {
    private var x = 0
    private var y = 0

    fun step(directionX: Int, directionY: Int): List<QuickMenuNavKey> {
        val pressed = ArrayList<QuickMenuNavKey>(2)
        if (directionX != x) {
            x = directionX
            if (directionX < 0) pressed += QuickMenuNavKey.LEFT else if (directionX > 0) pressed += QuickMenuNavKey.RIGHT
        }
        if (directionY != y) {
            y = directionY
            if (directionY < 0) pressed += QuickMenuNavKey.UP else if (directionY > 0) pressed += QuickMenuNavKey.DOWN
        }
        return pressed
    }
}

/** One press the pad hands the coin reducer, already in press order. */
internal data class QuickMenuPadKey(val key: QuickMenuNavKey, val action: Int, val repeatCount: Int = 0)

/**
 * The router's press-order rule (`InputRouter.confirmWaitsForPadFrame`), for the native coins.
 *
 * The defect. The Thor's pad reports its D-pad as hat axes on a joystick source. Android holds joystick motion until the
 * next frame reads it, and hands a button over the moment it arrives. So RIGHT then A, pressed a few milliseconds apart,
 * can reach the menu as A-down, A-up, then RIGHT. Applied in arrival order, the A acts on the coin that was lit BEFORE the
 * RIGHT, and the ring then moves onto the coin the player meant. (The emulator's adb D-pad arrives as keys and can never
 * show it.)
 *
 * The rule, as built. A confirm DOWN is held, together with its UP, until the frame's input has been read ([frame]); a second A pressed meanwhile queues behind it, in order; every
 * hat or D-pad press is stamped with its own event time. A press stamped BEFORE the held confirm's DOWN is applied at once,
 * ahead of it, so the confirm then resolves against the coin the player had really moved to. A press stamped at or after
 * the DOWN releases the held events stamped up to it first (the A went down first, so it acts on the old coin) and then
 * moves; a confirm UP stamped after that press stays held, so A-down / RIGHT / A-up still fails safe and activates
 * nothing, exactly as [reduceQuickMenuFocus] has it. The frame then releases whatever is left, in order.
 *
 * What it does not do, said plainly: a hat sample the platform delivers more than one frame after the confirm still lands
 * after it, which is today's behaviour, bounded to one frame; the router has the same gap. A press with no stamp
 * (time <= 0) never holds and releases everything ahead of it.
 *
 * Pure: the clock and the frame are the caller's. The controller calls [frame] from a Choreographer callback.
 */
internal class QuickMenuPadOrder {
    private class Held(val key: QuickMenuPadKey, val timeMs: Long)

    private val held = ArrayList<Held>(2)

    /** True while a confirm is waiting for its frame, so the caller knows to schedule one. */
    val holding: Boolean get() = held.isNotEmpty()

    /** A confirm key edge at [timeMs]. [canHold] false (no frame scheduler) hands everything straight through. */
    fun confirm(action: Int, repeatCount: Int, timeMs: Long, canHold: Boolean): List<QuickMenuPadKey> {
        val key = QuickMenuPadKey(QuickMenuNavKey.CONFIRM, action, repeatCount)
        return when {
            action == KeyEvent.ACTION_DOWN && repeatCount > 0 ->
                // A held key's auto-repeat arms nothing (the reducer ignores it); behind a held A it must not reorder.
                if (holding) emptyList() else listOf(key)
            action == KeyEvent.ACTION_DOWN ->
                // An earlier A still waiting stays buffered with this one, in arrival order: it waits for the same pending hat, so
                // draining it here would run it on the coin the hat has not yet moved the ring off. Only an A that cannot be held
                // (no frame scheduler, no stamp) releases everything ahead of it, still in order.
                if (!canHold || timeMs <= 0L) drain() + key else emptyList<QuickMenuPadKey>().also { held += Held(key, timeMs) }
            action == KeyEvent.ACTION_UP ->
                // Behind a held DOWN the UP waits with it; it is the same press.
                if (holding) emptyList<QuickMenuPadKey>().also { held += Held(key, timeMs) } else listOf(key)
            else -> listOf(key)
        }
    }

    /** A direction press (hat edge or D-pad key) stamped [timeMs]. */
    fun move(key: QuickMenuNavKey, repeatCount: Int, timeMs: Long): List<QuickMenuPadKey> {
        val press = QuickMenuPadKey(key, KeyEvent.ACTION_DOWN, repeatCount)
        if (!holding) return listOf(press)
        val out = ArrayList<QuickMenuPadKey>(held.size + 1)
        // Held events are in time order; a press stamped before the first of them overtakes the lot.
        while (held.isNotEmpty() && (timeMs <= 0L || held[0].timeMs <= timeMs)) out += held.removeAt(0).key
        out += press
        return out
    }

    /** The frame has read its pad input: release everything still held, in order. */
    fun frame(): List<QuickMenuPadKey> = drain()

    /** Drop whatever is held without applying it (the menu closed, or its window went away). */
    fun reset() {
        held.clear()
    }

    private fun drain(): List<QuickMenuPadKey> {
        if (held.isEmpty()) return emptyList()
        val out = held.map { it.key }
        held.clear()
        return out
    }
}

internal data class QuickMenuRingStops(val top: Int, val base: Int, val bottom: Int)

/** The ring's three vertical stops from the player's ring colour. Opaque; the view applies [QuickMenuRingAlpha]. */
internal fun quickMenuRingStops(ringArgb: Int): QuickMenuRingStops {
    val base = argbWithAlpha(ringArgb, 255)
    return QuickMenuRingStops(
        top = mixArgb(base, 0xffffffff.toInt(), QuickMenuRingLift),
        base = base,
        bottom = mixArgb(base, 0xff000000.toInt(), QuickMenuRingShade),
    )
}

/** Channel-wise blend of two ARGB colours. Close to the app's own colour lerp for a light or shade this small. */
internal fun mixArgb(from: Int, to: Int, fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    fun channel(shift: Int): Int {
        val a = (from ushr shift) and 0xff
        val b = (to ushr shift) and 0xff
        return (a + (b - a) * f).roundToInt().coerceIn(0, 255)
    }
    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
