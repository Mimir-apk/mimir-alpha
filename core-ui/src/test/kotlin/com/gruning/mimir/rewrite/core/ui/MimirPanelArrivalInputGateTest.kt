package com.gruning.mimir.rewrite.core.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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

/**
 * Overlays on the page lead: a glass panel arriving cold holds
 * [MimirTransitionInputGate] until its first painted frame, and never leaves it held when the arrival is cancelled or
 * snapped. A panel re-shown mid-flight is already on screen and holds nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimirPanelArrivalInputGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val reduced = mutableStateOf(false)
    private var panel: () -> Float = { 0f }

    @Before
    fun pinGateClock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
    }

    @After
    fun resetGate() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { SystemClock.uptimeMillis() }
    }

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
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
    fun `a cold panel holds presses through the lead and lets go on its first painted frame`() {
        host()
        assertEquals(0f, panel(), 0f)
        assertTrue("presses wait while the panel is unpainted", MimirTransitionInputGate.holding)
        var releasedAt = -1L
        run(400) { t ->
            val holding = MimirTransitionInputGate.holding
            if (panel() <= 0f) assertTrue("held while the panel is at alpha 0 ($t ms)", holding)
            if (releasedAt < 0 && !holding) {
                assertTrue("released only once the panel paints (${panel()} at $t ms)", panel() > 0f)
                releasedAt = t
            }
        }
        val lead = MimirMotion.OverlayDissolveCoverMs.toLong()
        assertTrue("released just after the lead: $releasedAt", releasedAt in (lead - 32L)..(lead + 96L))
    }

    @Test
    fun `closing during the lead never leaves the pad deaf`() {
        host()
        assertTrue(MimirTransitionInputGate.holding)
        visible.value = false
        Snapshot.sendApplyNotifications()
        run(48)
        assertFalse(MimirTransitionInputGate.holding)
        assertEquals(0f, panel(), 0f)
    }

    @Test
    fun `under reduced motion the panel snaps and the gate lets go at once`() {
        reduced.value = true
        host()
        run(48)
        assertEquals(1f, panel(), 0f)
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `a panel re-shown mid-flight is already seen and holds nothing`() {
        host()
        run(400)
        assertFalse(MimirTransitionInputGate.holding)
        visible.value = false
        Snapshot.sendApplyNotifications()
        run(48)
        assertTrue("still partly painted: ${panel()}", panel() > 0f)
        visible.value = true
        Snapshot.sendApplyNotifications()
        run(64) { t -> assertFalse("no hold on a warm re-show ($t ms)", MimirTransitionInputGate.holding) }
    }
}
