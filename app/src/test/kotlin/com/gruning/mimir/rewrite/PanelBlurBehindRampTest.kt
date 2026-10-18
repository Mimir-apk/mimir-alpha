package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Q14  and "dissolve, then clear" : the music panel's glass, the scrim and the window's blur,
 * is one value that rises with the panel, is HELD at full strength while the card dissolves, and only then clears; and the window is
 * told it may go only when that glass is gone. The blur is the compositor's, so what is observable on the JVM is the fraction the host
 * hands the controller every frame: pinned here as a progress timeline over the real [NativePanelGlassExitFade], sampled every 16 ms.
 *
 * Opening: nothing for the frame beat, then a rise on [MimirMotion.OverlayMs] and [MimirMotion.Easing] that arrives exactly at 1.
 * Closing: no movement for [MimirMotion.OverlayDissolveCoverMs], then the same clock back to exactly 0 with no snap, and the end of the
 * exit is reported once, after that, not when the 180 ms body fade ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelBlurBehindRampTest {

    @get:Rule
    val compose = createComposeRule()

    private val exiting = mutableStateOf(false)
    private val applied = mutableListOf<Float>()
    private var hidden = 0

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize()) {
                    NativePanelGlassExitFade(
                        exiting = exiting.value,
                        onFullyHidden = { hidden++ },
                        applyBlur = { applied += it },
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.White))
                    }
                }
            }
        }
        step(0L)
    }

    /** Runs the clock in 16 ms frames for [ms], returning the fraction as of the end of each frame. */
    private fun run(ms: Long): List<Float> {
        val out = mutableListOf<Float>()
        var seen = applied.lastOrNull() ?: 0f
        var t = 0L
        while (t < ms) {
            val before = applied.size
            step(16L)
            t += 16L
            // A frame that changes nothing emits nothing (the frame beat before the tween, the hold): the fraction is where it was.
            if (applied.size > before) seen = applied.last()
            out += seen
        }
        return out
    }

    private fun settleOpen() = run(MimirMotion.OverlayMs.toLong() + 64L)

    @Test
    fun `the glass rises with the panel on the overlay clock and arrives exactly at full`() {
        mount()
        assertEquals("nothing is blurred before the first frame", 0f, applied.last(), 0f)
        val rise = settleOpen()
        assertTrue("never outside 0..1: $rise", rise.all { it in 0f..1f })
        assertEquals("monotone up", rise.sorted(), rise)
        assertTrue("still clear on the first frame (the frame beat): ${rise.first()}", rise.first() < 0.05f)
        val atMiddle = rise[(90L / 16L).toInt()]
        // Two frames pass before the tween moves (the frame beat, then its first frame), so frame k has run (k * 16 - 32) ms of it.
        val expected = MimirMotion.Easing.transform((16f * ((90L / 16L).toInt() + 1) - 32f) / MimirMotion.OverlayMs)
        assertTrue("at about 90 ms it is on the house curve ($atMiddle vs $expected)", abs(atMiddle - expected) < 0.12f)
        assertTrue("and it is between clear and full ($atMiddle)", atMiddle in 0.2f..0.95f)
        assertEquals("exactly full once the clock has run", 1f, rise.last(), 0f)
        assertEquals("an open panel is not reported as gone", 0, hidden)
    }

    @Test
    fun `on the exit the glass holds while the card dissolves, then clears to exactly nothing without a snap`() {
        mount()
        settleOpen()
        assertEquals(1f, applied.last(), 0f)

        Snapshot.withMutableSnapshot { exiting.value = true }
        val fall = run(MimirMotion.OverlayDissolveCoverMs.toLong() + MimirMotion.OverlayMs.toLong() + 96L)
        assertTrue("never outside 0..1: $fall", fall.all { it in 0f..1f })
        assertEquals("monotone down", fall.sortedDescending(), fall)

        // The hold: through the first 112 ms (seven frames) the glass has not moved, while the card is dissolving over it.
        val held = fall.take((MimirMotion.OverlayDissolveCoverMs / 16) - 1)
        assertTrue("held at full strength for the dissolve: $held", held.all { it == 1f })

        val steps = (listOf(1f) + fall).zipWithNext { a, b -> a - b }
        assertTrue("no frame drops more than the curve allows (no snap): ${steps.max()}", steps.max() < 0.3f)
        // The hold ends on the first frame at or after 130 ms, then the clear takes its frame beat: it is moving by about 190 ms.
        assertTrue("it is clearing by 260 ms (${fall[15]})", fall[15] < 0.9f)
        assertEquals("exactly clear once the clock has run", 0f, fall.last(), 0f)
    }

    @Test
    fun `the exit is reported only when the glass is gone, not when the 180 ms body fade ends`() {
        mount()
        settleOpen()
        Snapshot.withMutableSnapshot { exiting.value = true }

        run(MimirMotion.OverlayMs.toLong() + 32L)
        assertTrue("the body fade (180 ms) is over but the glass is still clearing: ${applied.last()}", applied.last() > 0f)
        assertEquals("so the window is not told to go", 0, hidden)

        run(MimirMotion.OverlayDissolveCoverMs.toLong() + MimirMotion.OverlayMs.toLong() + 96L)
        assertEquals("the glass is clear", 0f, applied.last(), 0f)
        assertEquals("and the end of the exit is reported once", 1, hidden)
        run(64L)
        assertEquals("never twice", 1, hidden)
    }

    @Test
    fun `a leave that begins mid-rise lets the glass finish its rise under the hold, then clears`() {
        mount()
        run(64L)
        val turning = applied.last()
        assertTrue("part-way up: $turning", turning in 0.05f..0.95f)
        Snapshot.withMutableSnapshot { exiting.value = true }
        val next = run(16L).first()
        assertTrue("the next frame does not dip below where it was ($turning to $next)", next >= turning)
        val rest = run(MimirMotion.OverlayDissolveCoverMs.toLong() + MimirMotion.OverlayMs.toLong() + 128L)
        assertEquals(0f, rest.last(), 0f)
        assertEquals(1, hidden)
    }
}
