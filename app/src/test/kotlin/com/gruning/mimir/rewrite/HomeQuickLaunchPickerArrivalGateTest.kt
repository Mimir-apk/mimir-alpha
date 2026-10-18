package com.gruning.mimir.rewrite

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Alpha audit F3 (2026-09-30), the 15%-visibility input threshold for Quick Launch's picker: the picker cut in at alpha 1 and
 * its focus is seeded at mount, so it now arrives on the step tier (the 130 ms lead at alpha 0, then 180 ms) and the pad and
 * the touch wait until it is above [MimirPageMotion.ShownAlpha].
 *
 * Runs the real [HomeQuickLaunchPickerFadeHost], the picker's whole presentation, around a stand-in for its content: a
 * scrim behind a tile, both clickable, like the picker's own outside-tap scrim and its grid tiles. (The picker's content
 * takes the app's bindings, which the JVM cannot build.) The host's probe reads the alpha it paints, so every check is
 * against the picture and not a guessed time. The router is not stood up, so "a press does nothing" is proved as
 * "[MimirTransitionInputGate] is held", which the router honours (PageChangeInputGateTest); a touch is a real hit-tested tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeQuickLaunchPickerArrivalGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val mode = mutableStateOf<HomeQuickLaunchPickerMode?>(null)
    private val probe = HomeQuickLaunchPickerFadeProbe()

    /** What a tap reached, in order. */
    private val taps = mutableListOf<String>()

    private val general = HomeQuickLaunchPickerMode.General
    private val assign = HomeQuickLaunchPickerMode.Assign(slotIndex = 1)

    /** One frame of the arrival: when the clock was read, what the picker was painted at, and whether the pad was held. */
    private data class Frame(val ms: Long, val alpha: Float, val holding: Boolean)

    @Before
    fun gateClock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    @Test
    fun `a closed picker holds nothing`() {
        composeHost()
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `an opening holds the pad through the lead and lets go only once the picker is above 15 percent`() {
        composeHost()
        val opened = setMode(general)
        assertTrue("held on the frame the picker mounts", MimirTransitionInputGate.holding)
        val trace = framesUntilSettled(opened)

        assertTrue(
            "the pad is never free while the picker is at or below 15%",
            trace.all { it.holding || it.alpha > MimirPageMotion.ShownAlpha },
        )
        assertTrue(
            "the lead: nothing is painted for the first 130 ms, and the pad is held through it",
            trace.filter { it.ms < MimirMotion.OverlayDissolveCoverMs }.all { it.alpha == 0f && it.holding },
        )
        val freed = trace.first { !it.holding }
        val readable = trace.first { it.alpha > MimirPageMotion.ShownAlpha }
        assertTrue("it is freed after the lead, not at the first trace: $freed", freed.ms > MimirMotion.OverlayDissolveCoverMs)
        assertTrue("at a readable alpha: $freed", freed.alpha > MimirPageMotion.ShownAlpha)
        assertTrue(
            "and within a few frames of the fade crossing the line, not held on to it: crossed at $readable, freed at $freed",
            freed.ms - readable.ms <= 3 * FrameMs,
        )
        assertEquals("and the picker settles at 1", 1f, trace.last().alpha, 0f)
        assertTrue(
            "the whole arrival is the step tier's 310 ms, from the lead, within a frame or two of it",
            trace.first { it.alpha >= 1f }.ms <= MimirMotion.OverlayDissolveCoverMs + MimirMotion.OverlayMs + 3 * FrameMs,
        )
    }

    @Test
    fun `a tap in the lead reaches neither the tile nor the scrim`() {
        composeHost()
        setMode(general)
        advance(60L)
        assertEquals("the picker is at alpha 0", 0f, probe.incomingAlpha(), 0f)
        assertEquals(emptyList<String>(), tap("tile"))
        assertEquals("a stray tap must not dismiss a picker the player has not seen", emptyList<String>(), tap("scrim"))
    }

    @Test
    fun `a tap on the first painted frame, a trace, reaches the tile no more than one in the lead does`() {
        composeHost()
        setMode(general)
        stepUntil { probe.incomingAlpha() > 0f }
        assertTrue("a trace: ${probe.incomingAlpha()}", probe.incomingAlpha() <= MimirPageMotion.ShownAlpha)
        assertEquals(emptyList<String>(), tap("tile"))
    }

    @Test
    fun `a tap on the scrim at the first painted frame does not dismiss a trace`() {
        composeHost()
        setMode(general)
        stepUntil { probe.incomingAlpha() > 0f }
        assertTrue("a trace: ${probe.incomingAlpha()}", probe.incomingAlpha() <= MimirPageMotion.ShownAlpha)
        assertEquals(emptyList<String>(), tap("scrim"))
    }

    @Test
    fun `once the picker is above 15 percent the tile and the scrim take taps`() {
        composeHost()
        setMode(general)
        stepUntil { probe.incomingAlpha() > MimirPageMotion.ShownAlpha }
        stepUntil { !MimirTransitionInputGate.holding }
        // The shield leaves on the recomposition after the fade crosses the line: a frame's grace, no more.
        advance(2 * FrameMs)
        assertEquals(listOf("tile"), tap("tile"))
        assertEquals(listOf("scrim"), tap("scrim"))
    }

    @Test
    fun `B inside the lead frees the pad at once`() {
        composeHost()
        setMode(general)
        advance(60L)
        assertTrue(MimirTransitionInputGate.holding)
        setMode(null)
        compose.mainClock.advanceTimeByFrame()
        assertFalse("Back must never leave the pad deaf", MimirTransitionInputGate.holding)
    }

    @Test
    fun `reduced motion keeps the cut, with no hold and a tile that takes a tap at once`() {
        composeHost(reducedMotion = true)
        setMode(general)
        advance(2 * FrameMs)
        assertEquals("the picker is there at once", 1f, probe.incomingAlpha(), 0f)
        assertFalse("nothing arrives, so nothing holds", MimirTransitionInputGate.holding)
        assertEquals(listOf("tile"), tap("tile"))
    }

    @Test
    fun `one mode replacing another holds again until the new one is above 15 percent`() {
        composeHost()
        setMode(general)
        settle()
        assertFalse(MimirTransitionInputGate.holding)

        val swapped = setMode(assign)
        assertTrue("the new mode's focus is seeded at mount too", MimirTransitionInputGate.holding)
        val trace = framesUntilSettled(swapped)
        assertTrue(
            "the pad is never free while the new mode is at or below 15%",
            trace.all { it.holding || it.alpha > MimirPageMotion.ShownAlpha },
        )
        assertTrue("and it does let go", trace.any { !it.holding })
        assertEquals(listOf("tile:assign"), tap("tile"))
    }

    @Test
    fun `a swap holds a tap on the new mode until it is above 15 percent`() {
        composeHost()
        setMode(general)
        settle()
        setMode(assign)
        advance(60L)
        assertEquals("the old mode's dissolving pixels and the new one at alpha 0 take no tap", emptyList<String>(), tap("tile"))
    }

    @Test
    fun `a picker that closes holds nothing and is not shielded`() {
        composeHost()
        setMode(general)
        settle()
        setMode(null)
        advance(2 * FrameMs)
        assertFalse(MimirTransitionInputGate.holding)
    }

    private fun composeHost(reducedMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize()) {
                    HomeQuickLaunchPickerFadeHost(activeMode = mode.value, reducedMotion = reducedMotion, probe = probe) { active ->
                        PickerStandIn(active)
                    }
                }
            }
        }
        settle()
    }

    @Composable
    private fun PickerStandIn(active: HomeQuickLaunchPickerMode) {
        val assigning = active is HomeQuickLaunchPickerMode.Assign
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("scrim")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { taps += "scrim" },
            )
            Box(
                Modifier
                    .size(160.dp)
                    .align(Alignment.Center)
                    .testTag("tile")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        taps += if (assigning) "tile:assign" else "tile"
                    },
            )
        }
    }

    /** Sets the picker's mode and lets one frame run, so its effect has started. Returns the clock reading before it. */
    private fun setMode(value: HomeQuickLaunchPickerMode?): Long {
        val before = compose.mainClock.currentTime
        // A plain write from the test thread is not seen by a paused-clock recomposer until something idles.
        Snapshot.withMutableSnapshot { mode.value = value }
        compose.mainClock.advanceTimeByFrame()
        return before
    }

    /** Runs frames until the arrival is over, noting each one. [since] is the clock reading its times count from. */
    private fun framesUntilSettled(since: Long): List<Frame> {
        val frames = mutableListOf<Frame>()
        repeat(60) {
            frames += Frame(compose.mainClock.currentTime - since, probe.incomingAlpha(), MimirTransitionInputGate.holding)
            compose.mainClock.advanceTimeByFrame()
        }
        return frames
    }

    private fun stepUntil(done: () -> Boolean) {
        repeat(80) {
            if (done()) return
            compose.mainClock.advanceTimeByFrame()
        }
        throw AssertionError("the arrival never got there")
    }

    /** A tap on the tile's centre, or the scrim's corner, and whatever it reached. */
    private fun tap(tag: String): List<String> {
        val before = taps.size
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput {
            if (tag == "scrim") click(Offset(4f, 4f)) else click()
        }
        compose.mainClock.advanceTimeByFrame()
        return taps.drop(before)
    }

    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    private fun settle() = advance(MimirMotion.OverlayMs * 4L)

    private companion object {
        const val FrameMs = 16L
    }
}
