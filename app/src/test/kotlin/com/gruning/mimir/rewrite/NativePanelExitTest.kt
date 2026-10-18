package com.gruning.mimir.rewrite

import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * RC-38 (audit 2026-10-14): a native panel window leaves in two steps, not on the frame it is told to. This is the sequencing
 * ([NativePanelExit]); what the window draws between the steps is [NativePanelExitFade], proved in [NativePanelExitFadeRenderTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class NativePanelExitTest {

    private val handler = Handler(Looper.getMainLooper())
    private var finished = 0
    private val exit = NativePanelExit(handler) { finished++ }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `beginning an exit marks it exiting and finishes nothing`() {
        assertFalse(exit.exiting)
        assertTrue(exit.begin())
        assertTrue("the fade host reads this", exit.exiting)
        assertEquals("the window is still up: nothing is finished on the frame hide() is called", 0, finished)
        idle(0)
        assertEquals(0, finished)
    }

    @Test
    fun `a second begin changes nothing`() {
        assertTrue(exit.begin())
        assertFalse(exit.begin())
        exit.onFullyHidden()
        idle(0)
        assertEquals(1, finished)
    }

    @Test
    fun `the fade's report finishes once, posted and never inline`() {
        exit.begin()
        exit.onFullyHidden()
        assertEquals("posted: a composition is never disposed from inside its own frame", 0, finished)
        idle(0)
        assertEquals(1, finished)
        exit.onFullyHidden()
        idle(NativePanelExitBackstopMs + 100L)
        assertEquals("the guard that is still pending does not finish it a second time", 1, finished)
    }

    @Test
    fun `a report before any exit began is ignored`() {
        exit.onFullyHidden()
        idle(0)
        assertEquals(0, finished)
    }

    @Test
    fun `with no frame to report the leak guard takes the window down`() {
        exit.begin()
        idle(NativePanelExitBackstopMs - 1L)
        assertEquals(0, finished)
        idle(1L)
        assertEquals(1, finished)
    }

    @Test
    fun `a panel disposed by another road is never finished afterwards`() {
        exit.begin()
        exit.cancel()
        exit.onFullyHidden()
        idle(NativePanelExitBackstopMs + 100L)
        assertEquals(0, finished)
        assertFalse("and it cannot begin again", exit.begin())
    }

    @Test
    fun `the guard is ten overlay clocks, so a slow-motion check never loses its window early`() {
        assertEquals(1_800L, NativePanelExitBackstopMs)
    }
}
