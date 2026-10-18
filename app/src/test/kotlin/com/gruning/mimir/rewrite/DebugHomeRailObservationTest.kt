package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugHomeRailObservationTest {
    @Test
    fun `disarmed session ignores events without invoking its sink`() {
        val events = mutableListOf<HomeRailObservationEvent>()
        val session = HomeRailObservationSession(nowMs = { 0L }, sink = events::add)

        session.record(HomeRailObservationEvent("test", "ignored"))

        assertTrue(events.isEmpty())
        assertFalse(session.isArmed())
    }

    @Test
    fun `session accepts at most one hundred events and then closes`() {
        var nowMs = 1_000L
        val events = mutableListOf<HomeRailObservationEvent>()
        val session = HomeRailObservationSession(nowMs = { nowMs }, sink = events::add)

        session.arm()
        repeat(HomeRailObservationSession.MaxEvents) {
            session.record(HomeRailObservationEvent("test", "event-$it"))
        }
        session.record(HomeRailObservationEvent("test", "over-budget"))

        assertEquals(HomeRailObservationSession.MaxEvents, events.size)
        assertEquals("event-99", events.last().detail)
        assertFalse(session.isArmed())
    }

    @Test
    fun `session expires at the thirty second deadline before recording`() {
        var nowMs = 2_000L
        val events = mutableListOf<HomeRailObservationEvent>()
        val session = HomeRailObservationSession(nowMs = { nowMs }, sink = events::add)

        session.arm()
        nowMs += HomeRailObservationSession.DurationMs
        session.record(HomeRailObservationEvent("test", "expired"))

        assertTrue(events.isEmpty())
        assertFalse(session.isArmed())
    }

    @Test
    fun `rearming starts a fresh bounded session`() {
        var nowMs = 3_000L
        val events = mutableListOf<HomeRailObservationEvent>()
        val session = HomeRailObservationSession(nowMs = { nowMs }, sink = events::add)

        session.arm()
        repeat(HomeRailObservationSession.MaxEvents) {
            session.record(HomeRailObservationEvent("test", "first-$it"))
        }
        nowMs += 1L
        session.arm()
        session.record(HomeRailObservationEvent("test", "second-0"))

        assertEquals(HomeRailObservationSession.MaxEvents + 1, events.size)
        assertEquals("second-0", events.last().detail)
        assertTrue(session.isArmed())
    }

    /** Source wiring pin only: variant compilation supplies null to the feature callback in release. */
    @Test
    fun `release app wiring does not pass a rail observer`() {
        val source = sourceFile("MimirAppHost.kt")

        assertTrue(source.contains("onQuickRailTouch = if (BuildConfig.DEBUG)"))
        assertTrue(source.contains("DebugHomeRailObservation::recordRailTouch"))
        assertTrue(source.contains("} else {\n                            null"))
    }

    /** Source wiring pin only: the arm entry point remains inside the shared debug intent gate. */
    @Test
    fun `arm hook remains inside the debug intent gate`() {
        val source = sourceFile("MainActivity.kt")
        val hookBlock = source.substringAfter("private fun runDebugIntentHooks()")
            .substringBefore("/** Kept at the original post-companion-mount")

        assertTrue(hookBlock.contains("debugIntentHookGate.run"))
        assertTrue(hookBlock.contains("maybeObserveHomeRail()"))
        assertTrue(source.contains("intent?.getBooleanExtra(DebugObserveHomeRailExtra, false) == true"))
    }

    /** Source wiring pin only: nav/controller snapshots are guarded before touching the recorder object. */
    @Test
    fun `release callers guard navigation and controller observation`() {
        val navigation = sourceFile("MimirNavigationCoordinator.kt")
        val controller = sourceFile("MimirAppInputDispatch.kt")

        assertTrue(navigation.contains("BuildConfig.DEBUG && DebugHomeRailObservation.isArmed()"))
        assertTrue(controller.contains("BuildConfig.DEBUG && DebugHomeRailObservation.isArmed()"))
    }

    private fun sourceFile(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).firstOrNull(File::isFile)?.readText() ?: error("$name source not found")
}
