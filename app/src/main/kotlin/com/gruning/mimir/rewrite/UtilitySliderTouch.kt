package com.gruning.mimir.rewrite

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/** What a finger on a slider's track turned out to be. */
internal enum class SliderTouchKind {
    /** Still deciding: the finger is down and has not left touch slop. */
    UNDECIDED,

    /** Down and up inside touch slop. */
    TAP,

    /** Moved past touch slop more along the track than across it: the thumb follows the finger. */
    DRAG,

    /** Moved past touch slop more across the track than along it (a scroll), or something above took the gesture. */
    SCROLL,
}

/**
 * Decides what a finger on a slider is doing from the travel so far: [dx] and [dy] since the finger went down, with [slop] the touch
 * slop in pixels. Within slop it is undecided; past slop the larger axis wins, and a diagonal that is not clearly along the track
 * is a scroll. Pure, so the rule is a JVM test.
 */
internal fun sliderTouchKind(dx: Float, dy: Float, slop: Float): SliderTouchKind {
    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
    if (distance <= slop) return SliderTouchKind.UNDECIDED
    return if (abs(dx) > abs(dy)) SliderTouchKind.DRAG else SliderTouchKind.SCROLL
}

/**
 * The one slider touch grammar (Utility, the music panel, the equalizer editor, and the Bottom's seek lanes): a value changes only on a tap (down and up
 * within touch slop) or once a drag along the track has passed touch slop. A drag across the track is not the slider's: it is left
 * unconsumed for the list the slider sits in to scroll, and a finger that lands on a slider to scroll no longer moves it, or the
 * focus ring, on the way past.
 *
 * [emitAt] gets the finger's x in the track's own pixels; [onEngaged] fires once when the gesture becomes a tap or a drag (the
 * moment to take focus); [onEnd] fires at the end of every gesture, engaged or not. The controller's own steps never come here.
 *
 * The seek lanes on the Bottom (F13, Thor re-check on combined16, 2026-10-08: a tap on the track did not seek, a drag did) read the same grammar
 * with the three options below; the defaults are the Utility sliders' own. [pass] is the pointer pass the gesture is read in. [claimDown] consumes
 * the finger's DOWN as it lands, so a page-level swipe detector that needs an unconsumed down (the workspace pan, the player's drag down) never
 * starts on the lane, even for a swipe across it; it is read in [PointerEventPass.Initial] with it. [enabled] is asked after the down, and again on every
 * move of a drag: false after the down leaves the whole gesture alone, consuming nothing, and false mid-drag ends the drag there (onEnd still fires).
 */
internal suspend fun PointerInputScope.detectSliderTouch(
    onEngaged: () -> Unit,
    emitAt: (Float) -> Unit,
    onEnd: () -> Unit,
    pass: PointerEventPass = PointerEventPass.Main,
    claimDown: Boolean = false,
    enabled: () -> Boolean = { true },
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
        if (!enabled()) return@awaitEachGesture
        if (claimDown) down.consume()
        val slop = viewConfiguration.touchSlop
        var travel = Offset.Zero
        var kind = SliderTouchKind.UNDECIDED
        while (kind != SliderTouchKind.SCROLL) {
            val event = awaitPointerEvent(pass)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            when (kind) {
                SliderTouchKind.UNDECIDED -> {
                    // A parent that already took the gesture (a scroll under way) is not ours to answer.
                    if (change.isConsumed) {
                        kind = SliderTouchKind.SCROLL
                    } else if (!change.pressed) {
                        kind = SliderTouchKind.TAP
                        onEngaged()
                        emitAt(down.position.x)
                        change.consume()
                        break
                    } else {
                        travel += change.positionChange()
                        kind = sliderTouchKind(travel.x, travel.y, slop)
                        if (kind == SliderTouchKind.DRAG) {
                            onEngaged()
                            emitAt(change.position.x)
                            change.consume()
                        }
                    }
                }
                SliderTouchKind.DRAG -> {
                    // A lane that was switched off mid-drag (a page parked, a pane swiped away) stops following the finger; onEnd still fires.
                    if (!change.pressed || !enabled()) break
                    emitAt(change.position.x)
                    change.consume()
                }
                else -> break
            }
        }
        onEnd()
    }
}
