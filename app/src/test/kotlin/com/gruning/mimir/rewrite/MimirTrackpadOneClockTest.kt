package com.gruning.mimir.rewrite

import android.os.Looper
import androidx.compose.runtime.Composable
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTrackpadHost
import com.gruning.mimir.rewrite.core.ui.MimirTrackpadPadRampMs
import java.io.File
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
 * RC-22 (audit 2026-10-14, X-71 / X-71a): the Bottom trackpad opened 110 ms later than it closed (Thor), and its pad enter, exit and
 * legend had no easing. The pad rises on one named clock and leaves on exactly that one, on the house easing, and the window goes
 * from the pad's own report that the leave has ended, not from a wall-clock OverlayMs + 60 ms of its own. Open and close durations
 * are equal because they are the same constant; the window's removal has no literal of its own left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class MimirTrackpadOneClockTest {

    private class FakeSurface : PadSurface {
        var mounted = false
        var unmounts = 0
        override val isMounted: Boolean get() = mounted
        override fun mount(content: @Composable () -> Unit): Boolean {
            mounted = true
            return true
        }

        override fun unmount() {
            if (mounted) unmounts++
            mounted = false
        }
    }

    private class Activation : MimirTrackpadHost {
        override val serviceConnected = true
        override fun begin(): MimirTrackpadHost = this
        override fun pointerMove(dx: Float, dy: Float) = Unit
        override fun click() = Unit
        override fun doubleClick() = Unit
        override fun scrollBy(dx: Float, dy: Float) = Unit
        override fun scrollEnd() = Unit
        override fun scrollCancel() = Unit
        override fun holdBegin() = Unit
        override fun holdMove(dx: Float, dy: Float) = Unit
        override fun holdEnd() = Unit
        override fun openAccessibilitySettings() = Unit
        override fun end() = Unit
    }

    private val surface = FakeSurface()
    private val pad = MimirTrackpadPadWindow(begin = { Activation() }, surface = surface, serviceConnected = { true })

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun source(path: String): String {
        val file = listOf(File(path), File("../$path")).first(File::isFile)
        return file.readText().lineSequence().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun `the pad's ramp is the overlay clock, one constant for both directions`() {
        assertEquals(MimirMotion.OverlayMs, MimirTrackpadPadRampMs)
        val pad = source("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirTrackpadPad.kt")
        val ramp = pad.substringAfter("LaunchedEffect(closing) {").substringBefore("\n    }\n")
        assertTrue(
            "entering and leaving are ONE animateTo, toward 1 or 0, on the one ramp and the house easing",
            ramp.contains("enter.animateTo(if (closing) 0f else 1f, tween(MimirTrackpadPadRampMs, easing = MimirMotion.Easing))"),
        )
        assertTrue("the leave reports its own end", ramp.contains("if (closing) currentOnClosed()"))
        assertTrue(
            "the legend's fade takes the house easing too",
            pad.contains("legendAlpha.animateTo(0f, tween(MimirMotion.SceneMs, easing = MimirMotion.Easing))"),
        )
        assertFalse("no pad ramp is left without an easing", Regex("""tween\(MimirMotion\.\w+\)""").containsMatchIn(pad.substringBefore("internal const val LegendGestures")))
    }

    @Test
    fun `the window has no clock of its own, no slack constant and no OverlayMs plus a literal`() {
        val window = source("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirTrackpadWindows.kt")
        assertFalse(window.contains("RemovalSlackMs"))
        assertFalse(window.contains("MimirMotion.OverlayMs"))
        assertTrue("it hears the pad's own report", window.contains("onClosed = ::padLeft"))
    }

    @Test
    fun `the window goes on the pad's report of its leave, before any timer could`() {
        assertTrue(pad.open())
        pad.close()
        assertTrue("closing: the pad is still up while it leaves", surface.mounted)
        idle(MimirMotion.OverlayMs.toLong() + 60L)
        assertTrue("no timer of the old OverlayMs + 60 ms takes it down", surface.mounted)
        pad.padLeft()
        idle(0)
        assertFalse("the report takes it down", surface.mounted)
        assertEquals(1, surface.unmounts)
    }

    @Test
    fun `a report from a pad that is not leaving changes nothing`() {
        assertTrue(pad.open())
        pad.padLeft()
        idle(0)
        assertTrue(surface.mounted)
        assertEquals(0, surface.unmounts)
    }

    @Test
    fun `the leak guard still takes a pad down when no frame ever reports`() {
        assertTrue(pad.open())
        pad.close()
        idle(NativePanelExitBackstopMs + 100L)
        assertFalse(surface.mounted)
        assertEquals(1, surface.unmounts)
    }

    @Test
    fun `a report arriving after the window went changes nothing`() {
        pad.open()
        pad.close()
        pad.padLeft()
        idle(0)
        pad.padLeft()
        idle(0)
        assertEquals(1, surface.unmounts)
    }
}
