package com.gruning.mimir.rewrite.feature.library

import android.animation.ValueAnimator
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput

// A finger on a paused clock, for the CarouselTouch*Test classes. The pointer's coroutine writes state from the injection, and
// nothing recomposes until the write is published and a frame has run, so every step here ends with both: a drag is a sequence
// of (move, frame) pairs, as it is on a device, and the lift happens between frames like any other event.

/** Publishes whatever a pointer coroutine wrote, then runs [count] frames of the paused clock. */
internal fun ComposeContentTestRule.touchFrames(count: Int = 1) {
    repeat(count) {
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()
    }
}

/** Lets the clock run for [ms], frame by frame, with the state published first. */
internal fun ComposeContentTestRule.touchSettle(ms: Long) {
    Snapshot.sendApplyNotifications()
    mainClock.advanceTimeBy(ms)
}

/** A finger lands at [at] (root coordinates). */
internal fun ComposeContentTestRule.touchDown(at: Offset) {
    onRoot().performTouchInput { down(at) }
    touchFrames()
}

/** The finger moves by ([dx], [dy]) over one 16 ms frame. */
internal fun ComposeContentTestRule.touchMove(dx: Float, dy: Float = 0f) {
    onRoot().performTouchInput { moveBy(Offset(dx, dy), delayMillis = 16L) }
    touchFrames()
}

/** The finger stays where it is for [ms], so a lift after it carries no speed. */
internal fun ComposeContentTestRule.touchRest(ms: Long = 120L) {
    onRoot().performTouchInput { move(ms) }
    touchFrames()
}

/** The finger lifts. */
internal fun ComposeContentTestRule.touchUp() {
    onRoot().performTouchInput { up() }
    touchFrames()
}

/** The gesture is lost (an ancestor took it, the window changed): no lift, no speed. */
internal fun ComposeContentTestRule.touchCancel() {
    onRoot().performTouchInput { cancel() }
    touchFrames()
}

/** A tap: down and up at [at] with nothing between. */
internal fun ComposeContentTestRule.touchTap(at: Offset) {
    onRoot().performTouchInput {
        down(at)
        up()
    }
    touchFrames()
}

/** The device's animator scale (Developer options): 0 is reduced motion. The setter is hidden, so by reflection. */
internal fun setCarouselAnimatorScale(scale: Float) {
    ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
}
