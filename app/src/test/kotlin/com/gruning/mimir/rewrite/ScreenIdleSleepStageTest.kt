package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The dim is a warning, not an end state** —
 *
 * Before this, a non-zero Mimir screen timeout took `FLAG_KEEP_SCREEN_ON` for the session, dimmed to
 * 1% at the deadline, and never let go. Nothing released the flag — not the deadline callback, not
 * `armDeadline`, not `cancelDeadline`. Only `onPause` did, by way of `resumed` going false. So on the
 * app that is foreground by default, the display wakelock never dropped, the platform's suspend
 * counter never reached zero, and the device could not sleep at all. At 600 s the setting read "dim
 * after ten minutes" and behaved as "dim after ten minutes, then stay awake indefinitely".
 */
class ScreenIdleSleepStageTest {

    /** A scheduler that runs deadlines on demand, so the two stages can be stepped individually. */
    private class ManualSchedule : ScreenIdleSchedule {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        var cancellations = 0

        override fun schedule(delayMs: Long, onDeadline: () -> Unit): () -> Unit {
            pending += delayMs to onDeadline
            return { cancellations++ }
        }

        /** Fires the most recently armed deadline, which is the one the controller is waiting on. */
        fun fireLatest(): Long {
            val (delay, action) = pending.removeAt(pending.lastIndex)
            action()
            return delay
        }
    }

    private class Recorder {
        val dimmed = mutableListOf<Boolean>()
        val keepScreenOn = mutableListOf<Boolean>()
        var sleepCalls = 0
        var sleepAnswers = true
    }

    private fun controller(
        seconds: Int,
        recorder: Recorder,
        schedule: ManualSchedule,
    ) = MimirScreenIdleController(
        readTimeoutSeconds = { seconds },
        applyDimmed = { recorder.dimmed += it },
        applyKeepScreenOn = { recorder.keepScreenOn += it },
        scheduler = schedule,
        enforceDeviceSleep = {
            recorder.sleepCalls++
            recorder.sleepAnswers
        },
    )

    @Test
    fun `the dim no longer holds the screen on forever`() {
        val recorder = Recorder()
        val schedule = ManualSchedule()
        val idle = controller(seconds = 60, recorder = recorder, schedule = schedule)

        idle.onResume()
        assertEquals("a non-zero timeout takes the screen", listOf(true), recorder.keepScreenOn)

        val dimAfter = schedule.fireLatest()
        assertEquals(60_000L, dimAfter)
        assertTrue(idle.isDimmed)
        assertEquals(listOf(true), recorder.dimmed)
        assertEquals(
            "the dim alone must not hand the screen back -- it is a warning, and the session continues",
            listOf(true),
            recorder.keepScreenOn,
        )
        assertEquals("and nothing sleeps at the dim", 0, recorder.sleepCalls)

        val sleepAfter = schedule.fireLatest()
        assertEquals("stage two reuses the configured interval: dim at N, sleep N later", 60_000L, sleepAfter)
        assertEquals(
            "the hold is released, which is the whole point -- the display wakelock has to drop " +
                "before the platform can suspend",
            listOf(true, false),
            recorder.keepScreenOn,
        )
        assertEquals(1, recorder.sleepCalls)
        assertTrue("the dim itself is untouched by stage two", idle.isDimmed)
    }

    /**
     * **Releasing is not a privilege; enforcing is.** A chassis without the vendor privilege, or with
     * a revoked grant or a poisoned transport, still gets its screen back — the platform's own
     * timeout then takes it. Ordered release-then-enforce because a sleep landing while Mimir still
     * holds keep-screen-on is a race the window flag can win.
     */
    @Test
    fun `the screen is handed back even when nothing can enforce the sleep`() {
        val recorder = Recorder().apply { sleepAnswers = false }
        val schedule = ManualSchedule()
        val idle = controller(seconds = 15, recorder = recorder, schedule = schedule)

        idle.onResume()
        schedule.fireLatest()
        schedule.fireLatest()

        assertEquals(listOf(true, false), recorder.keepScreenOn)
        assertEquals("it still asks", 1, recorder.sleepCalls)
        assertTrue("and the dim stands", idle.isDimmed)
    }

    /** A wake between the two stages must cancel the sleep, not merely undim and let it fire. */
    @Test
    fun `waking before the sleep deadline cancels it`() {
        val recorder = Recorder()
        val schedule = ManualSchedule()
        val idle = controller(seconds = 30, recorder = recorder, schedule = schedule)

        idle.onResume()
        schedule.fireLatest()
        assertTrue(idle.isDimmed)

        assertTrue("the waking input is consumed as wake-only", idle.onUserInteraction())
        assertFalse(idle.isDimmed)
        assertEquals(listOf(true, false), recorder.dimmed)
        assertEquals("and the screen is held again for the fresh session", listOf(true), recorder.keepScreenOn)

        // The stale sleep deadline fires late. Its generation no longer matches, so it must do
        // nothing at all -- releasing the screen here would undo the wake the user just performed.
        schedule.pending.firstOrNull()?.second?.invoke()
        assertEquals("a stale stage two must not sleep a woken device", 0, recorder.sleepCalls)
        assertEquals(listOf(true), recorder.keepScreenOn)
    }

    /** A zero timeout is "leave it to Android": no hold, no dim, and certainly no enforced sleep. */
    @Test
    fun `a zero timeout delegates entirely to the platform`() {
        val recorder = Recorder()
        val schedule = ManualSchedule()
        val idle = controller(seconds = 0, recorder = recorder, schedule = schedule)

        idle.onResume()
        assertEquals(listOf(false), recorder.keepScreenOn)
        assertTrue("nothing is armed", schedule.pending.isEmpty())
        assertEquals(0, recorder.sleepCalls)
        assertFalse(idle.isDimmed)
    }

    /**
     * **`KEYCODE_SLEEP`, never `KEYCODE_POWER`.** POWER toggles: arriving at a device that is already
     * asleep it WAKES it, so a duplicate deadline or a retry turns the screen back on and the idle
     * session flaps. SLEEP is idempotent. The operation is also enumerated with no interpolation, like
     * every other command on that transport.
     */
    @Test
    fun `the enforced sleep is idempotent and not steerable`() {
        val rendered = VendorPServerOperation.EnforceDeviceSleep.render()

        assertTrue("223 is KEYCODE_SLEEP", rendered.contains("input keyevent 223"))
        assertFalse("26 is KEYCODE_POWER, which would wake a sleeping device", rendered.contains("keyevent 26"))
        assertEquals(
            "a data object with no parameters renders one constant string -- there is nothing a " +
                "caller could steer, which is the discipline every operation on this transport keeps",
            rendered,
            VendorPServerOperation.EnforceDeviceSleep.render(),
        )
    }
}
