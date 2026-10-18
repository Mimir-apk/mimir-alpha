package com.gruning.mimir.rewrite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.ui.MimirPointerPosition
import com.gruning.mimir.rewrite.core.ui.TrackpadSpeed
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * pad6 (device review, 2026-10-06): the pointer reaches all of Main, the Back plate is gated like every other
 * action and has one route, and the speed step persists beside natural scroll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class MimirTrackpadPad6Test {

    private class RecordingStrokes : TrackpadStrokes<Int> {
        var made = 0
        override fun stroke(previous: Int?, fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long, willContinue: Boolean): Int = ++made
        override fun dispatch(stroke: Int, what: String, done: (completed: Boolean) -> Unit) = done(true)
    }

    // ---- item 1: the pointer reaches all of display 0 ----------------------------------------------------------------

    private val main = 1920 to 1080
    private val bottom = 1240 to 1080

    @Test
    fun theWindowBoundsOfDisplayZeroWinOverTheOlderRealSize() {
        val size = mimirTrackpadDisplaySize(TrackpadDisplayReading(windowBounds = main, realSize = bottom), lastGood = null)
        assertEquals("Main's whole display, never the Bottom's", main, size)
    }

    @Test
    fun withNoWindowBoundsTheRealSizeThenTheLastGoodSizeAreUsedAndNeverAOneByOne() {
        assertEquals(main, mimirTrackpadDisplaySize(TrackpadDisplayReading(null, main), lastGood = bottom))
        assertEquals("a platform that says nothing keeps the last good size", main, mimirTrackpadDisplaySize(TrackpadDisplayReading(null, null), lastGood = main))
        assertEquals("a 1 by 1 reading is not a display", main, mimirTrackpadDisplaySize(TrackpadDisplayReading(1 to 1, 0 to 0), lastGood = main))
        val nothing = mimirTrackpadDisplaySize(TrackpadDisplayReading(null, null), lastGood = null)
        assertTrue("never a 1 by 1 pointer cage: $nothing", nothing.first > 100 && nothing.second > 100)
    }

    @Test
    fun withAMainOf1920x1080AndABottomOf1240x1080ThePointerReachesBothCornersOfMain() {
        var reading = TrackpadDisplayReading(windowBounds = main, realSize = main)
        var lastGood: Pair<Int, Int>? = null
        val injector = MimirTrackpadInjector(
            strokes = RecordingStrokes(),
            displaySize = { mimirTrackpadDisplaySize(reading, lastGood).also { lastGood = it } },
            screenOn = { true },
            naturalScroll = { true },
        )
        val token = injector.activate()
        // Far more finger travel than the pad (1240 wide) can supply in one swipe: the pointer is clamped, never stopped short.
        repeat(10) { injector.move(token, 4_000f, 4_000f) }
        assertEquals(MimirPointerPosition(1919f, 1079f), injector.pointer)
        repeat(10) { injector.move(token, -4_000f, -4_000f) }
        assertEquals(MimirPointerPosition(0f, 0f), injector.pointer)
        // Rotated to portrait: the pointer is re-clamped to the new, taller display at once and then reaches its corner.
        reading = TrackpadDisplayReading(windowBounds = 1080 to 1920, realSize = 1080 to 1920)
        injector.move(token, 100_000f, 100_000f)
        assertEquals(MimirPointerPosition(1079f, 1919f), injector.pointer)
    }

    @Test
    fun theHostReadsDisplayZeroOnlyAndLogsItsSizeAtEachActivation() {
        val service = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirTrackpadAccessibilityService.kt").readText()
        val readers = service.substringAfter("private fun displaySize()").substringBefore("private fun screenOn()")
        assertTrue("window bounds of a display 0 window context", readers.contains("maximumWindowMetrics") && readers.contains("Display.DEFAULT_DISPLAY"))
        assertFalse("never another display", Regex("getDisplay\\((?!Display\\.DEFAULT_DISPLAY)").containsMatchIn(readers))
        assertTrue("the size is logged at each activation, for the device proof", service.contains("activated display0="))
    }

    // ---- item 5: Back ------------------------------------------------------------------------------------------------

    private class Harness {
        var screenOn = true
        var locked = false
        var backs = 0
        val injector = MimirTrackpadInjector(
            strokes = RecordingStrokes(),
            displaySize = { 1920 to 1080 },
            screenOn = { screenOn },
            naturalScroll = { true },
            keyguardLocked = { locked },
            performBack = { backs++; true },
        )
    }

    @Test
    fun theBackPlateGoesThroughTheSessionTokenAndLockGate() {
        val h = Harness()
        assertFalse("before any activation nothing is sent", h.injector.back(1L))
        val token = h.injector.activate()
        assertTrue(h.injector.back(token))
        assertEquals(1, h.backs)
        assertFalse("a stale token is dropped", h.injector.back(token + 1))
        h.locked = true
        assertFalse("a keyguard blocks it exactly as it blocks a click", h.injector.back(token))
        h.locked = false
        h.screenOn = false
        assertFalse("a screen that is off blocks it", h.injector.back(token))
        h.screenOn = true
        h.injector.end(token)
        assertFalse("an ended session sends nothing", h.injector.back(token))
        assertEquals("only the live, unlocked call acted", 1, h.backs)
    }

    @Test
    fun backHasOneRouteTheGlobalActionAndNoKeyInjectionToAnyDisplay() {
        val route = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirTrackpadBackRoute.kt").readText()
        assertTrue(route.contains("performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)"))
        listOf("KEYCODE_BACK", "injectInputEvent", "dispatchKeyEvent", "GLOBAL_ACTION_HOME", "GLOBAL_ACTION_RECENTS", "GLOBAL_ACTION_NOTIFICATIONS", "setDisplayId").forEach {
            assertFalse("the Back route must not use $it", route.substringAfter("internal fun mimirTrackpadPerformBack").contains(it))
        }
        val service = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirTrackpadAccessibilityService.kt").readText()
        assertFalse("the service file itself still has no global action", service.contains("performGlobalAction"))
        assertTrue("the route logs a fixed line that says only that Back was sent", route.contains("\"back sent performed=\$done\""))
        assertFalse("and never claims to know the focused display", route.contains("focusedDisplay="))
        assertTrue("the activation's Back is gated by its own token", service.contains("injector.back(token)"))
        val window = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirTrackpadWindows.kt").readText()
        assertTrue("the pad is not focusable, so touching it leaves focus on Main", window.contains("FLAG_NOT_FOCUSABLE"))
    }

    // ---- item 6: the speed step ---------------------------------------------------------------------------------------

    @Test
    fun theSpeedStepCyclesPersistsAndLoadsBesideNaturalScroll() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MimirTrackpadSettings.setSpeedStep(context, 2)
        assertEquals(2, MimirTrackpadSettings.speedStep.value)
        assertEquals("stored", 2, context.getSharedPreferences("mimir_trackpad", Context.MODE_PRIVATE).getInt("speed_step", -1))
        assertEquals(3, MimirTrackpadSettings.cycleSpeedStep(context))
        assertEquals(4, MimirTrackpadSettings.cycleSpeedStep(context))
        assertEquals(5, MimirTrackpadSettings.cycleSpeedStep(context))
        assertEquals("5 wraps to 1", 1, MimirTrackpadSettings.cycleSpeedStep(context))
        // Settings and the pad read the same store: a value written to it is what load() brings back.
        context.getSharedPreferences("mimir_trackpad", Context.MODE_PRIVATE).edit().putInt("speed_step", 5).apply()
        MimirTrackpadSettings.load(context)
        assertEquals(5, MimirTrackpadSettings.speedStep.value)
        assertEquals("and the factor it applies is step 5's", 1.7f, TrackpadSpeed.factor(MimirTrackpadSettings.speedStep.value), 0f)
        // Natural scroll is untouched by it.
        MimirTrackpadSettings.setNaturalScroll(context, false)
        MimirTrackpadSettings.setSpeedStep(context, 3)
        MimirTrackpadSettings.load(context)
        assertFalse(MimirTrackpadSettings.naturalScroll.value)
        assertEquals(3, MimirTrackpadSettings.speedStep.value)
        MimirTrackpadSettings.setNaturalScroll(context, true)
    }

    @Test
    fun aGarbageStoredStepIsClampedAndTheMenuRouteLoadsTheStoreToo() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("mimir_trackpad", Context.MODE_PRIVATE).edit().putInt("speed_step", 99).apply()
        MimirTrackpadSettings.load(context)
        assertEquals(5, MimirTrackpadSettings.speedStep.value)
        val service = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirTrackpadAccessibilityService.kt").readText()
        val host = service.substringAfter("private fun accessibilityHost(").substringBefore("/** Whether the service is switched on")
        assertTrue("every route to the host loads the store, not only the keyboard's", host.contains("MimirTrackpadSettings.load(appContext)"))
    }

    @Test
    fun settingsShowsTheSameStepAndCyclesItThroughTheSameStore() {
        val coordinator = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirSettingsCoordinator.kt").readText()
        assertTrue(coordinator.contains("canonicalRowId == TrackpadSpeedRowId -> MimirTrackpadSettings.cycleSpeedStep(context)"))
        val read = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirSettingsReadCoordinator.kt").readText()
        assertTrue(read.contains("speed = trackpadSpeed"))
    }

    @Test
    fun theGaugeSetsALevelDirectlyThroughTheSameStoreWithNoNewAuthority() {
        // pad8 : the pad's Slow-to-Fast gauge sets a level; Settings' row still cycles. Both land in the one store.
        val service = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirTrackpadAccessibilityService.kt").readText()
        val set = service.substringAfter("override fun setSpeed(step: Int): Int {").substringBefore("/** One Back on Main")
        assertTrue("the activation's setSpeed stores through the settings object", set.contains("MimirTrackpadSettings.setSpeedStep(appContext, step)"))
        assertTrue("and returns the clamped step in force", set.contains("MimirTrackpadSettings.speedStep.value"))
        assertFalse("the host no longer cycles for the pad", service.contains("override fun cycleSpeed"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        MimirTrackpadSettings.setSpeedStep(context, 5)
        assertEquals(5, MimirTrackpadSettings.speedStep.value)
        MimirTrackpadSettings.setSpeedStep(context, 1)
        assertEquals("a direct set, not a cycle", 1, MimirTrackpadSettings.speedStep.value)
        MimirTrackpadSettings.setSpeedStep(context, 99)
        assertEquals("and clamped", 5, MimirTrackpadSettings.speedStep.value)
        MimirTrackpadSettings.setSpeedStep(context, 3)
    }
}
