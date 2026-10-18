package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.OverlayScreenActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirScreenIdleControllerTest {
    @Test
    fun `resume focus surrogate with nonzero owns keep-screen-on dims then wake-only interaction rearms`() {
        var seconds = 60
        val dimmed = mutableListOf<Boolean>()
        val keepOn = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { seconds }, dimmed = dimmed, keepOn = keepOn, scheduler = scheduler)

        controller.onResume()
        assertEquals(listOf(true), keepOn)
        assertEquals(60_000L, scheduler.active.single().delayMs)

        scheduler.fireActive()
        assertTrue(controller.isDimmed)
        assertEquals(listOf(true), dimmed)
        assertEquals(listOf(true), keepOn)

        assertTrue(controller.onUserInteraction())
        assertFalse(controller.isDimmed)
        assertEquals(listOf(true, false), dimmed)
        // Wake rearm must not reapply keep-screen-on when already true.
        assertEquals(listOf(true), keepOn)
        assertEquals(60_000L, scheduler.active.single().delayMs)
    }

    @Test
    fun `resume with zero delegates to system and never arms dim`() {
        val dimmed = mutableListOf<Boolean>()
        val keepOn = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { 0 }, dimmed = dimmed, keepOn = keepOn, scheduler = scheduler)

        controller.onResume()
        // First false reconcile still clears a possibly stale host flag.
        assertEquals(listOf(false), keepOn)
        assertTrue(scheduler.active.isEmpty())
        assertTrue(dimmed.isEmpty())
        assertFalse(controller.onUserInteraction())
        assertEquals(listOf(false), keepOn)
    }

    @Test
    fun `pause clears keep-screen-on cancels deadline and restores dim`() {
        val dimmed = mutableListOf<Boolean>()
        val keepOn = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { 30 }, dimmed = dimmed, keepOn = keepOn, scheduler = scheduler)

        controller.onResume()
        scheduler.fireActive()
        assertTrue(controller.isDimmed)

        controller.onPause()
        assertFalse(controller.isDimmed)
        assertEquals(listOf(true, false), dimmed)
        assertEquals(listOf(true, false), keepOn)
        assertTrue(scheduler.active.isEmpty())
        assertFalse(controller.onUserInteraction())
    }

    @Test
    fun `runtime nonzero-to-zero clears keep-screen-on and cancels while resumed`() {
        var seconds = 30
        val dimmed = mutableListOf<Boolean>()
        val keepOn = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { seconds }, dimmed = dimmed, keepOn = keepOn, scheduler = scheduler)

        controller.onResume()
        scheduler.fireActive()
        assertTrue(controller.isDimmed)

        seconds = 0
        controller.onTimeoutChanged()
        assertFalse(controller.isDimmed)
        assertEquals(listOf(true, false), dimmed)
        assertEquals(listOf(true, false), keepOn)
        assertTrue(scheduler.active.isEmpty())
    }

    @Test
    fun `runtime zero-to-nonzero while resumed owns keep-screen-on and arms once`() {
        var seconds = 0
        val keepOn = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { seconds }, keepOn = keepOn, scheduler = scheduler)

        controller.onResume()
        assertEquals(listOf(false), keepOn)
        assertTrue(scheduler.active.isEmpty())

        seconds = 15
        controller.onTimeoutChanged()
        assertEquals(listOf(false, true), keepOn)
        assertEquals(15_000L, scheduler.active.single().delayMs)
    }

    @Test
    fun `timeout change while paused still reconciles keep-screen-on false`() {
        var seconds = 30
        val keepOn = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { seconds }, keepOn = keepOn, scheduler = scheduler)

        controller.onResume()
        controller.onPause()
        assertEquals(listOf(true, false), keepOn)

        seconds = 0
        controller.onTimeoutChanged()
        // Already false — idempotent; do not reapply.
        assertEquals(listOf(true, false), keepOn)
        assertTrue(scheduler.active.isEmpty())
    }

    @Test
    fun `a cancelled stale deadline cannot dim a newer session`() {
        val dimmed = mutableListOf<Boolean>()
        val keepOn = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { 30 }, dimmed = dimmed, keepOn = keepOn, scheduler = scheduler)

        controller.onResume()
        val stale = scheduler.tasks.single()
        controller.onUserInteraction()
        stale.action()

        assertFalse(controller.isDimmed)
        assertTrue(dimmed.isEmpty())
        assertEquals(1, scheduler.active.size)
        assertEquals(listOf(true), keepOn)
    }

    @Test
    fun `ordinary interaction and nonzero-to-nonzero timeout rearm do not reapply keep-screen-on`() {
        var seconds = 30
        val keepOn = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { seconds }, keepOn = keepOn, scheduler = scheduler)

        controller.onResume()
        assertEquals(listOf(true), keepOn)

        assertFalse(controller.onUserInteraction())
        assertEquals(1, scheduler.active.size)
        assertEquals(2, scheduler.tasks.size)
        assertEquals(listOf(true), keepOn)

        seconds = 60
        controller.onTimeoutChanged()
        assertEquals(60_000L, scheduler.active.single().delayMs)
        assertEquals(3, scheduler.tasks.size)
        assertEquals(listOf(true), keepOn)
    }

    @Test
    fun `duplicate lifecycle signals do not reschedule or reapply wake ownership`() {
        val keepOn = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { 30 }, keepOn = keepOn, scheduler = scheduler)

        controller.onResume()
        controller.onResume()
        assertEquals(listOf(true), keepOn)
        assertEquals(1, scheduler.tasks.size)
        assertEquals(1, scheduler.active.size)

        controller.onPause()
        controller.onPause()
        assertEquals(listOf(true, false), keepOn)
        assertEquals(1, scheduler.tasks.size)
        assertTrue(scheduler.active.isEmpty())
    }

    /**
     * Round-5 triage B2: a touch Mimir only observes (the ACTION_OUTSIDE copy of a touch in a foreign app on the Bottom)
     * keeps an awake Main from dimming, and never wakes a dimmed one. The same copy arrives for a touch on Mimir's own
     * Presentation, whose first touch after a dim is ruled wake-only (QA D10).
     */
    @Test
    fun `passive activity rearms while awake and never wakes a dimmed session`() {
        val dimmed = mutableListOf<Boolean>()
        val keepOn = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { 60 }, dimmed = dimmed, keepOn = keepOn, scheduler = scheduler)

        controller.onResume()
        scheduler.advanceBy(59_000L)
        controller.onPassiveActivity()
        scheduler.advanceBy(59_000L)
        assertFalse("an observed touch at 59 s must push the dim past 60 s", controller.isDimmed)
        assertTrue(dimmed.isEmpty())
        assertEquals("a passive re-arm never reapplies keep-screen-on", listOf(true), keepOn)

        scheduler.advanceBy(1_000L)
        assertTrue("the re-armed deadline still dims", controller.isDimmed)
        assertEquals(listOf(true), dimmed)

        // Stage two exists as of the 2026-09-21 ruling ("Dim > suspend"), so a dimmed session is no
        // longer quiescent: the dim arms one sleep deadline. This used to assert nothing at all was
        // scheduled, which was only ever a proxy for what it actually cares about -- that a PASSIVE
        // touch arms nothing of its own. Compare the count instead, so the proxy cannot drift again.
        assertEquals("the dim arms exactly one deadline: stage two's sleep", 1, scheduler.active.size)
        val scheduledWhileDimmed = scheduler.active.size

        controller.onPassiveActivity()
        assertTrue("an observed touch never wakes", controller.isDimmed)
        assertEquals("no applyDimmed(false)", listOf(true), dimmed)
        assertEquals(
            "and a passive touch while dimmed arms nothing of its own",
            scheduledWhileDimmed,
            scheduler.active.size,
        )
        assertTrue("the next direct touch is still the wake-only one", controller.onUserInteraction())
        assertEquals(listOf(true, false), dimmed)
    }

    @Test
    fun `passive activity while paused does nothing`() {
        val keepOn = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { 30 }, keepOn = keepOn, scheduler = scheduler)

        controller.onResume()
        controller.onPause()
        controller.onPassiveActivity()
        assertEquals(listOf(true, false), keepOn)
        assertEquals(1, scheduler.tasks.size)
        assertTrue(scheduler.active.isEmpty())
    }

    /** Round-5 triage B2: how MainActivity's overlay observer routes. DIRECT is interaction; OUTSIDE is passive. */
    @Test
    fun `a direct overlay touch is interaction and an outside copy is passive`() {
        val dimmed = mutableListOf<Boolean>()
        val scheduler = FakeIdleScheduler()
        val controller = controller(seconds = { 60 }, dimmed = dimmed, scheduler = scheduler)

        controller.onResume()
        scheduler.advanceBy(59_000L)
        controller.onOverlayScreenActivity(OverlayScreenActivity.DIRECT)
        scheduler.advanceBy(59_000L)
        assertFalse("a touch on the M at 59 s re-arms", controller.isDimmed)

        scheduler.advanceBy(1_000L)
        assertTrue(controller.isDimmed)
        controller.onOverlayScreenActivity(OverlayScreenActivity.OUTSIDE)
        assertTrue("an outside copy never wakes", controller.isDimmed)
        controller.onOverlayScreenActivity(OverlayScreenActivity.DIRECT)
        assertFalse("a touch on the M, its menu or the drawer wakes a dimmed Main", controller.isDimmed)
        assertEquals(listOf(true, false), dimmed)
        assertEquals("and arms a fresh interval", 60_000L, scheduler.active.single().delayMs)
    }

    private fun controller(
        seconds: () -> Int,
        dimmed: MutableList<Boolean> = mutableListOf(),
        keepOn: MutableList<Boolean> = mutableListOf(),
        scheduler: FakeIdleScheduler,
    ) = MimirScreenIdleController(
        readTimeoutSeconds = seconds,
        applyDimmed = dimmed::add,
        applyKeepScreenOn = keepOn::add,
        scheduler = scheduler,
    )

    private class FakeIdleScheduler : ScreenIdleSchedule {
        data class Task(
            val delayMs: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
            val dueAtMs: Long = 0L,
        )

        var nowMs = 0L
            private set
        val tasks = mutableListOf<Task>()
        val active: List<Task> get() = tasks.filterNot(Task::cancelled)

        override fun schedule(delayMs: Long, onDeadline: () -> Unit): () -> Unit {
            val task = Task(delayMs, onDeadline, dueAtMs = nowMs + delayMs)
            tasks += task
            return { task.cancelled = true }
        }

        fun fireActive() {
            val task = active.single()
            task.cancelled = true
            task.action()
        }

        /** Moves the virtual clock, firing each deadline that falls due on the way, in due order. */
        fun advanceBy(ms: Long) {
            val target = nowMs + ms
            while (true) {
                val next = active.filter { it.dueAtMs <= target }.minByOrNull(Task::dueAtMs) ?: break
                nowMs = next.dueAtMs
                next.cancelled = true
                next.action()
            }
            nowMs = target
        }
    }
}
