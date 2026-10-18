package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Tells a finger that LIFTED from a gesture that was TAKEN AWAY, for a `draggable` that has to roll back on the second and commit on the first.
 *
 * `draggable`'s `onDragStopped` is called for both: a system `ACTION_CANCEL` arrives there as a stop with zero velocity, the same as a slow lift, and a second
 * pointer going down is not reported at all. This watches the stream from an ancestor's Initial pass (it consumes nothing, so it cannot change what any
 * node sees) and keeps the two facts a stop needs: whether the tracked finger's own release was seen, and whether a second pointer went down since the
 * gesture began.
 *
 * **A cancel is told from a lift by `isConsumed` on the Release at the Initial pass, in either order.** Read from the ui 1.7.3 bytecode
 * (`SuspendingPointerInputModifierNodeImpl.onCancelPointerInput`): the release it synthesises is built as
 * `PointerInputChange(id, uptime, position, pressed = false, pressure, previousUptime = uptime, previousPosition = position, previousPressed = it.pressed, isInitiallyConsumed = it.pressed)`,
 * so for a finger that was down the synthetic release is CONSUMED from the start, and it is dispatched through the Initial pass first. A real release is built by the
 * MotionEvent adapter unconsumed, and an ancestor's Initial pass (this watch sits outside what it watches) reads it before anything below can consume it. Timestamps tell
 * nothing (a lift can share its millisecond with the last move; the cancel's release always does), and the native MotionEvent that would is `internal` to ui. So a release
 * that arrives consumed is a cancel whether it reaches the watch before or after `onDragStopped`. The older rule is kept as the fallback for a stop that has seen no release at
 * all: measured under Robolectric, a real lift's release reaches an ancestor BEFORE the stop and a cancel's stop comes FIRST, so a stop with no release yet is a cancel.
 *
 * Read [takenAway] from `onDragStopped`. [onSecondPointer] is called at the moment the second pointer goes down, so a host can roll back at once.
 */
class MimirGestureWatch(private val onSecondPointer: () -> Unit = {}) {
    private var released = false
    private var second = false
    private var cancelRelease = false

    /** A second pointer went down since this gesture's first one did. */
    val secondPointer: Boolean get() = second

    /** True when the gesture that is stopping did not end in its own finger's release: a system cancel, or a second pointer took part. */
    val takenAway: Boolean get() = second || cancelRelease || !released

    internal fun down(pressedCount: Int) {
        if (pressedCount <= 1) {
            released = false
            second = false
            cancelRelease = false
        } else if (!second) {
            second = true
            onSecondPointer()
        }
    }

    /** [consumed]: the release was already consumed when it reached the Initial pass, which only a cancel's synthesised release is. */
    internal fun up(consumed: Boolean = false) {
        released = true
        if (consumed) cancelRelease = true
    }
}

/** Observes the pointer stream for [watch], consuming nothing. Place it before (outside) the `draggable` it reports for. */
fun Modifier.mimirGestureWatch(watch: MimirGestureWatch): Modifier = pointerInput(watch) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            event.changes.forEach { change ->
                if (change.changedToDownIgnoreConsumed()) watch.down(pressed)
                else if (change.previousPressed && !change.pressed) watch.up(consumed = change.isConsumed)
            }
        }
    }
}
