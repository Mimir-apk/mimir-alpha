package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

/**
 * The trackpad plates' tap: fires only on a release that is INSIDE the plate's bounds and whose
 * finger stayed within the touch slop the whole time. Pressing a plate, dragging out of it and letting go does nothing; a cancel
 * (a consumed release) does nothing; a plain tap fires once. Touch-only, never a focus node.
 *
 * [pressed] (pad8) is true while a finger is down on the plate, for the plate's brighter ember glow; it is read in a draw block,
 * so a press recomposes nothing. [onPress] runs at the touch-down (the plates re-arm their ember life with it).
 */
internal fun Modifier.mimirPlateTap(onTap: () -> Unit, pressed: MutableState<Boolean>? = null, onPress: (() -> Unit)? = null): Modifier = pointerInput(onTap) {
    val slop = viewConfiguration.touchSlop
    awaitPointerEventScope {
        while (true) {
            val first = awaitPointerEvent(PointerEventPass.Initial)
            val down = first.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
            val id = down.id
            val start = down.position
            var withinSlop = true
            pressed?.value = true
            onPress?.invoke()
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == id } ?: break
                    if (hypot(change.position.x - start.x, change.position.y - start.y) > slop) withinSlop = false
                    if (!change.pressed) {
                        val inside = change.position.x >= 0f && change.position.y >= 0f &&
                            change.position.x <= size.width && change.position.y <= size.height
                        if (withinSlop && inside && !change.isConsumed) {
                            change.consume()
                            onTap()
                        }
                        break
                    }
                }
            } finally {
                pressed?.value = false
            }
        }
    }
}

/** Where the gauge's bars sit inside the gauge, measured at layout and read at touch time (plain fields, never state). */
internal class GaugeBars {
    var gaugeRootX = 0f
    var barsRootX = 0f
    var width = 0f

    /** The bars' left edge in the gauge's own coordinates. */
    val left: Float get() = barsRootX - gaugeRootX
}

/** The step (1..[steps]) under [x], for a bars area [width] wide starting at [left]: the bars' equal columns, clamped at both ends. */
internal fun gaugeStepAt(x: Float, left: Float, width: Float, steps: Int): Int {
    if (width <= 0f || steps <= 1) return 1
    return kotlin.math.floor((x - left) / (width / steps)).toInt().coerceIn(0, steps - 1) + 1
}

internal fun gaugeOnBars(x: Float, left: Float, width: Float): Boolean = width > 0f && x >= left && x <= left + width

/**
 * The step a swipe has reached: [startLevel], moved by one step for every `bar width / steps` the finger has travelled sideways ([dx], right is
 * positive and faster), clamped to 1..[steps]. A partial step counts for nothing, in either direction. With no measured bar the level stays where it was.
 */
internal fun gaugeSlideStep(startLevel: Int, dx: Float, width: Float, steps: Int): Int {
    val top = steps.coerceAtLeast(1)
    if (width <= 0f || steps <= 1) return startLevel.coerceIn(1, top)
    return (startLevel + (dx / (width / steps)).toInt()).coerceIn(1, top)
}

/**
 * The Slow-to-Fast gauge's touch. A tap
 * that starts over the bar's width (the whole 48 dp height of the row, not only the slim drawn bar) and releases inside the gauge within the touch slop
 * sets the step under that point, exactly as the plates' tap rule does. A horizontal drag past the slop, started ANYWHERE on the gauge, is a swipe: it moves
 * the level from where it was when the finger went down, one step for each `bar width / steps` the finger travels ([gaugeSlideStep]), right faster and left
 * slower (only a change is reported), and every change of it is CONSUMED, so it never reaches the pad field or the header. A mostly-vertical move, or a
 * release outside, sets nothing.
 *
 * Keyed on nothing: [onSet] and [level] must read their latest through a state, because the level they set recomposes the gauge mid-swipe, and a restarted
 * pointer loop would lose the finger. [pressed] and [onPress] are as in [mimirPlateTap].
 */
internal fun Modifier.mimirGaugeSlide(
    bars: GaugeBars,
    steps: Int,
    level: () -> Int,
    onSet: (Int) -> Unit,
    pressed: MutableState<Boolean>? = null,
    onPress: (() -> Unit)? = null,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    awaitPointerEventScope {
        while (true) {
            val first = awaitPointerEvent(PointerEventPass.Initial)
            val down = first.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
            val id = down.id
            val start = down.position
            val startsOnBars = gaugeOnBars(start.x, bars.left, bars.width)
            val startLevel = level()
            var sliding = false
            var withinSlop = true
            var last = startLevel
            pressed?.value = true
            onPress?.invoke()
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == id } ?: break
                    val dx = change.position.x - start.x
                    val dy = change.position.y - start.y
                    if (hypot(dx, dy) > slop) withinSlop = false
                    if (!sliding && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) >= kotlin.math.abs(dy)) sliding = true
                    if (sliding) {
                        val step = gaugeSlideStep(startLevel, dx, bars.width, steps)
                        if (step != last) {
                            last = step
                            onSet(step)
                        }
                        change.consume()
                    }
                    if (!change.pressed) {
                        val inside = change.position.x >= 0f && change.position.y >= 0f &&
                            change.position.x <= size.width && change.position.y <= size.height
                        if (!sliding && withinSlop && startsOnBars && inside && !change.isConsumed) {
                            change.consume()
                            onSet(gaugeStepAt(start.x, bars.left, bars.width, steps))
                        }
                        break
                    }
                }
            } finally {
                pressed?.value = false
            }
        }
    }
}
