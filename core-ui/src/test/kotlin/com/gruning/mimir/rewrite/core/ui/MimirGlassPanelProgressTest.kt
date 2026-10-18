package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A glass panel's own arrival: alpha and lift over ShineMs from the
 * 130 ms lead, while the blur beneath keeps its OverlayMs ramp. Reduced motion is a settled state from the first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimirGlassPanelProgressTest {

    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val reduced = mutableStateOf(false)
    private var panel: () -> Float = { 0f }
    private var blur: () -> Float = { 0f }

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            blur = rememberMimirGlassProgress(visible = visible.value)
            panel = rememberMimirGlassPanelProgress(visible = visible.value, reducedMotion = reduced.value)
            Box(Modifier.size(10.dp))
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun run(ms: Long, each: (Long) -> Unit = {}) {
        val start = compose.mainClock.currentTime
        while (compose.mainClock.currentTime - start < ms) {
            compose.mainClock.advanceTimeByFrame()
            each(compose.mainClock.currentTime - start)
        }
    }

    @Test
    fun `the panel waits out the lead while the blur ramps, then arrives over the slow clock`() {
        host()
        var blurSettledAt = -1L
        var panelSettledAt = -1L
        run(800) { t ->
            if (t < MimirMotion.OverlayDissolveCoverMs) assertEquals("panel hidden at $t ms", 0f, panel(), 0.001f)
            if (blurSettledAt < 0 && blur() >= 1f) blurSettledAt = t
            if (panelSettledAt < 0 && panel() >= 1f) panelSettledAt = t
        }
        assertTrue("the blur settles on OverlayMs: $blurSettledAt", blurSettledAt in 180L..240L)
        assertTrue("the panel settles at the lead plus ShineMs: $panelSettledAt", panelSettledAt in 670L..730L)
    }

    @Test
    fun `under reduced motion the panel is a settled state from its first frame`() {
        reduced.value = true
        host()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, panel(), 0f)
    }

    @Test
    fun `a panel re-shown mid-flight carries on without the lead`() {
        host()
        run(400)
        val midway = panel()
        assertTrue("part way there: $midway", midway in 0.05f..0.95f)
        visible.value = false
        Snapshot.sendApplyNotifications()
        run(48)
        visible.value = true
        Snapshot.sendApplyNotifications()
        val resumedFrom = panel()
        run(64)
        val early = panel()
        run(32)
        // Six frames after the re-show it is climbing: well inside the 130 ms a cold arrival would still be waiting.
        assertTrue("moving up again, no 130 ms lead: $resumedFrom -> $early -> ${panel()}", panel() > early && panel() > resumedFrom)
    }
}
