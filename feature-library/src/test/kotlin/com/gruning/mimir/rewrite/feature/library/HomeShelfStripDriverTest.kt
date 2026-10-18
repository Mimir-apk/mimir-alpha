package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Home strip's painted position and what moves it ([HomeShelfStripDriver]), on a paused clock without the rest of the
 * shelf. The pad moves the logical window start; one shared spring chases it. A held D-pad changes the window many times a
 * second, so each new target must carry on from the speed the spring has: a spring that is cancelled and restarted begins
 * from rest every time, which the old per-tile springs (each new target interrupting the running animation) never did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeShelfStripDriverTest {

    @get:Rule
    val compose = createComposeRule()

    private val windowStart = mutableStateOf(0)
    private val touch = CarouselStripTouch()
    private val windowPos = Animatable(0f)

    /** One tile's seat as the old mechanism ran it: an animateFloatAsState on the same spring, aimed at (item 0 - window start). */
    private var oldSeat = 0f

    private fun host(reducedMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HomeShelfStripDriver(touch = touch, windowPos = windowPos, windowStart = windowStart.value, reducedMotion = reducedMotion)
            val seat by animateFloatAsState(
                targetValue = -windowStart.value.toFloat(),
                animationSpec = CoverflowSeatSpring,
                label = "old-per-tile-seat",
            )
            oldSeat = seat
        }
        compose.touchFrames(3)
    }

    /** A held D-pad: the window start goes on by one every six frames (about 100 ms), [changes] times. Returns the spring's speed each frame. */
    private fun heldRight(changes: Int): List<Float> {
        val speeds = mutableListOf<Float>()
        repeat(changes) {
            windowStart.value += 1
            repeat(6) {
                compose.touchFrames()
                speeds += windowPos.velocity
            }
        }
        return speeds
    }

    @Test
    fun `a held pad keeps the speed of the spring through six window changes`() {
        host()

        val speeds = heldRight(changes = 6)

        // From the first change (which starts at rest) on, the spring is always moving on: a retarget that restarted it
        // from rest would drop the speed to nothing at each of the five that follow.
        val after = speeds.drop(8)
        assertTrue("the spring moves on through every retarget: $after", after.all { it > 0.5f })
        assertEquals("and arrives where the last change aimed it", 6f, windowPos.value, 1.5f)
    }

    @Test
    fun `after a held pad the strip rests within three frames of the old per-tile spring`() {
        host()
        heldRight(changes = 6)

        var mine = -1
        var old = -1
        repeat(90) { frame ->
            compose.touchFrames()
            if (mine < 0 && abs(windowPos.value - 6f) < 0.01f) mine = frame
            if (old < 0 && abs(oldSeat + 6f) < 0.01f) old = frame
        }

        assertTrue("both must come to rest (mine=$mine, old=$old)", mine >= 0 && old >= 0)
        assertTrue("the strip rests at frame $mine, the old spring at $old", abs(mine - old) <= 3)
    }

    @Test
    fun `a single window change from rest is the same spring the tiles ran`() {
        host()

        windowStart.value = 1
        var mine = -1
        var old = -1
        repeat(90) { frame ->
            compose.touchFrames()
            if (mine < 0 && abs(windowPos.value - 1f) < 0.01f) mine = frame
            if (old < 0 && abs(oldSeat + 1f) < 0.01f) old = frame
        }

        assertTrue("both must come to rest (mine=$mine, old=$old)", mine >= 0 && old >= 0)
        assertTrue("the strip rests at frame $mine, the old spring at $old", abs(mine - old) <= 3)
        assertFalse("nothing is left engaged", touch.engaged)
    }

    @Test
    fun `a finger moves the position one for one, and its lift settles it on the window with the engagement cleared`() {
        host()

        touch.begin(windowPos.value)
        compose.touchFrames(2)
        touch.move(1.4f)
        compose.touchFrames(3)
        assertEquals("the position is where the finger asked", 1.4f, windowPos.value, 0.001f)
        assertTrue("engaged while it is held", touch.engaged)

        windowStart.value = 1
        touch.release(0f)
        compose.touchSettle(1_500L)

        assertEquals("settled on the window the lift chose", 1f, windowPos.value, 0.001f)
        assertFalse("and nothing is left engaged", touch.engaged)
    }

    @Test
    fun `with motion reduced a window change is a snap`() {
        host(reducedMotion = true)

        windowStart.value = 5
        compose.touchFrames(3)

        assertEquals(5f, windowPos.value, 0.0001f)
        assertFalse(touch.engaged)
    }
}
