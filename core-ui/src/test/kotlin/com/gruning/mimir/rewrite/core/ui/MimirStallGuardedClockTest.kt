package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thor drive 3 (combined45): the Settings pickers' one 180 ms swap with no lead opened in 30 ms, in one frame and in 50 ms on the device, because a long first frame moves Compose's frame clock by its whole length
 * and the fade has nothing to absorb it ([withMimirStallGuard]). A paused-clock test's frames are always 16 ms, so this one scripts the frames: the Choreographer's times, with the stalls the device's own frame stats show
 * (a 213 ms first draw, then a second long frame once the effects have run).
 */
class MimirStallGuardedClockTest {

    /** A frame clock that hands out the scripted frame times in order, and says so when the animation asks for more than the script has. */
    private class ScriptedClock(private val timesMs: List<Long>) : MonotonicFrameClock {
        private var next = 0
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            check(next < timesMs.size) { "the animation outlived the scripted frames" }
            return onFrame(timesMs[next++] * 1_000_000L)
        }
    }

    /** [steps] frames [healthyMs] apart from t0, with [stalls] (frame index to extra ms) added before that frame is delivered. */
    private fun script(frames: Int, healthyMs: Long, stalls: Map<Int, Long> = emptyMap()): List<Long> {
        var t = 1_000L
        return (0 until frames).map { i ->
            t += healthyMs + (stalls[i] ?: 0L)
            t
        }
    }

    /** Runs the 180 ms swap on the scripted frames, guarded by [capMs] (0 = ambient), and returns the alpha each frame left on screen. */
    private fun fade(times: List<Long>, capMs: Int, leadMs: Int = 0): List<Float> = runBlocking(ScriptedClock(times)) {
        val alpha = Animatable(0f)
        val seen = ArrayList<Float>()
        withMimirStallGuard(capMs) {
            alpha.animateTo(1f, tween(MimirMotion.OverlayMs, delayMillis = leadMs, easing = MimirMotion.Easing)) { seen += value }
        }
        seen
    }

    private fun List<Float>.largestStep(): Float = zipWithNext { a, b -> b - a }.max()

    @Test
    fun `healthy frames are not slowed - the guarded fade is the unguarded fade frame for frame`() {
        for (frameMs in listOf(8L, 17L)) {
            val times = script(60, frameMs)
            val ambient = fade(times, capMs = 0)
            val guarded = fade(times, capMs = MimirArrivalFrameCapMs)
            assertEquals("$frameMs ms frames: the same number of frames", ambient.size, guarded.size)
            ambient.indices.forEach { i -> assertEquals("$frameMs ms frames: frame $i", ambient[i], guarded[i], 1e-6f) }
        }
    }

    @Test
    fun `a 125 ms stall right after the clock starts is a cut unguarded and a fade guarded`() {
        // The Background picker on the device: the clock starts, the next frame lands 125 ms later, then the frames run at the display's 8 ms.
        val times = script(80, 8, stalls = mapOf(2 to 125L))
        val ambient = fade(times, capMs = 0)
        val guarded = fade(times, capMs = MimirArrivalFrameCapMs)
        assertTrue("unguarded the stalled frame jumps over most of the fade (${ambient.largestStep()})", ambient.largestStep() > 0.6f)
        assertTrue("guarded no frame moves the page by more than a quarter (${guarded.largestStep()})", guarded.largestStep() <= 0.25f)
        assertTrue("and the swap is drawn across at least ten frames above 5% and below 95% (${guarded.count { it > 0.05f && it < 0.95f }})", guarded.count { it > 0.05f && it < 0.95f } >= 10)
        assertEquals("it still arrives whole", 1f, guarded.last(), 0f)
    }

    @Test
    fun `a run of long frames moves the fade a capped step each, never a cut`() {
        // A cold process: every third frame is a 90 ms one.
        val times = script(200, 8, stalls = (0 until 200 step 3).associateWith { 90L })
        val guarded = fade(times, capMs = MimirArrivalFrameCapMs)
        assertTrue("no frame steps by more than a quarter (${guarded.largestStep()})", guarded.largestStep() <= 0.25f)
        assertEquals(1f, guarded.last(), 0f)
        val ambient = fade(times, capMs = 0)
        assertTrue("the same frames cut it unguarded (${ambient.largestStep()})", ambient.largestStep() > 0.4f)
    }

    @Test
    fun `a stall inside the lead moves the lead by a capped step too`() {
        val times = script(120, 8, stalls = mapOf(3 to 200L))
        val guarded = fade(times, capMs = MimirArrivalFrameCapMs, leadMs = MimirMotion.OverlayDissolveCoverMs)
        assertTrue("the page does not begin on the stalled frame (${guarded.take(10)})", guarded.take(10).all { it < 0.05f })
        assertEquals(1f, guarded.last(), 0f)
    }

    @Test
    fun `a cap of zero, or no frame clock, leaves the animation on the ambient clock`() {
        val times = script(60, 8, stalls = mapOf(2 to 125L))
        val seen = fade(times, capMs = 0)
        val direct = runBlocking(ScriptedClock(times)) {
            val alpha = Animatable(0f)
            val frames = ArrayList<Float>()
            alpha.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)) { frames += value }
            frames
        }
        assertEquals(direct.size, seen.size)
        direct.indices.forEach { i -> assertTrue(abs(direct[i] - seen[i]) < 1e-6f) }
        // No MonotonicFrameClock in the context: the block runs as it is.
        assertEquals(7, runBlocking { withMimirStallGuard(MimirArrivalFrameCapMs) { 7 } })
    }
}
