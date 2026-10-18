package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The instrument that is supposed to end the 1.13-1.14s cadence hunt. Its whole value is that it
 * fires on a real episode and stays silent otherwise, so both halves are pinned.
 */
class CapabilityCallCadenceWatchTest {

    private val pid = 4242

    @Test
    fun `the Nova's measured cadence is caught, and names its caller`() {
        val watch = CapabilityCallCadenceWatch()
        // The exact deltas measured on the device: 1.13, 1.13, 1.14, 1.14, 1.13 ...
        val intervals = listOf(1130L, 1130L, 1140L, 1140L, 1130L, 1140L)
        var now = 10_000L
        val events = intervals.map { step ->
            now += step
            watch.onCall(now, pid)
        }
        val entered = events.filterIsInstance<CapabilityCallCadenceWatch.Event.Entered>()
        assertEquals("exactly one Entered edge for one episode", 1, entered.size)
        assertEquals(pid, entered.single().callingPid)
        assertTrue("the reported interval must be the real one", entered.single().intervalMs in 1130L..1140L)
    }

    @Test
    fun `an ordinary burst is silent — this is what stops the instrument becoming noise`() {
        // A panel opening, a profile apply, a launch handshake: a few fast calls then quiet.
        //
        // THE COUNT IS HARDCODED ON PURPOSE. The first version of this test looped
        // `SUSTAINED_CALLS - 1` times, so setting that constant to 1 made the loop run ZERO times
        // and the test passed against an instrument that fired on every single fast call. A test
        // that reads the constant it is pinning cannot pin it — caught by replaying R-CAD-2.
        val watch = CapabilityCallCadenceWatch()
        var now = 0L
        val events = buildList {
            repeat(3) {
                now += 200L
                add(watch.onCall(now, pid))
            }
        }
        assertTrue("a 3-call burst must be silent, got $events", events.all { it == null })
    }

    @Test
    fun `the threshold stays in a band where a burst is not an episode`() {
        // Stated as a band so re-tuning stays legal, and hardcoded so it survives the constant it
        // describes. Below 3 an ordinary burst becomes an episode and the instrument becomes the
        // noise source it exists to avoid; above ~10 a real loop runs ten seconds before it is seen.
        assertTrue(
            "SUSTAINED_CALLS=${CapabilityCallCadenceWatch.SUSTAINED_CALLS} must be at least 3",
            CapabilityCallCadenceWatch.SUSTAINED_CALLS >= 3,
        )
        assertTrue(
            "SUSTAINED_CALLS=${CapabilityCallCadenceWatch.SUSTAINED_CALLS} must be at most 10",
            CapabilityCallCadenceWatch.SUSTAINED_CALLS <= 10,
        )
        // The cadence under investigation was 1.13-1.14s; the fastest sanctioned poller is 15s.
        assertTrue(
            "FAST_INTERVAL_MS=${CapabilityCallCadenceWatch.FAST_INTERVAL_MS} must sit above the " +
                "1.14s cadence it hunts and below the 15s rearm watch",
            CapabilityCallCadenceWatch.FAST_INTERVAL_MS in 1_500L..10_000L,
        )
    }

    @Test
    fun `the sanctioned pollers never trip it`() {
        // The standing rearm watch: 15s recovering, 60s confirming. Neither is "fast".
        for (cadence in listOf(15_000L, 60_000L, 5_000L, 2_000L)) {
            val watch = CapabilityCallCadenceWatch()
            var now = 0L
            repeat(40) {
                now += cadence
                assertNull("cadence ${cadence}ms must never trip the watch", watch.onCall(now, pid))
            }
        }
    }

    @Test
    fun `an episode closes exactly once, and reports what it saw`() {
        val watch = CapabilityCallCadenceWatch()
        var now = 0L
        repeat(20) { now += 1_000L; watch.onCall(now, pid) }
        now += 30_000L
        val settled = watch.onCall(now, pid)
        assertTrue("the slow call must close the episode", settled is CapabilityCallCadenceWatch.Event.Settled)
        settled as CapabilityCallCadenceWatch.Event.Settled
        assertEquals(pid, settled.callingPid)
        assertEquals(1_000L, settled.fastestIntervalMs)
        assertTrue("episode must cover the fast run, got ${settled.episodeMs}", settled.episodeMs >= 18_000L)
        assertTrue("must count the whole run, got ${settled.calls}", settled.calls >= 19)
        // ...and it must not close twice.
        now += 30_000L
        assertNull(watch.onCall(now, pid))
    }

    @Test
    fun `a second client during an episode is reported, because two callers is a different bug`() {
        val watch = CapabilityCallCadenceWatch()
        var now = 0L
        repeat(8) { now += 900L; watch.onCall(now, pid) }
        repeat(4) { now += 900L; watch.onCall(now, 9999) }
        now += 10_000L
        val settled = watch.onCall(now, pid) as CapabilityCallCadenceWatch.Event.Settled
        assertTrue("a mid-episode pid change must be surfaced", settled.multiplePids)
    }

    @Test
    fun `a backwards clock resets rather than reporting nonsense`() {
        val watch = CapabilityCallCadenceWatch()
        var now = 100_000L
        repeat(10) { now += 500L; watch.onCall(now, pid) }
        // elapsedRealtime should never go backwards. A watch that would mis-report if it did is a
        // watch nobody can trust the one time it fires.
        assertNull(watch.onCall(1_000L, pid))
    }

    @Test
    fun `it logs on edges only and never per call`() {
        // Source contract, comment-stripped: a per-call log on this door is the exact cost RC-47
        // removed from HardwareProbe.probe(), and this session already shipped a per-beat probe once.
        val src = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker/MimirTweakerService.kt"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/MimirTweakerService.kt"),
        ).firstOrNull(File::isFile) ?: throw AssertionError("MimirTweakerService.kt not found")
        val code = src.readText().lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertTrue(
            "getCapabilities must call the watch",
            code.lineSequence().any { it.trim() == "noteCapabilityCall()" },
        )
        val body = code.substringAfter("private fun noteCapabilityCall()").substringBefore("private fun capabilities()")
        assertTrue("the watch must decide; the call site must not log unconditionally", body.contains("?: return"))
        assertTrue("must report the real caller", body.contains("Binder.getCallingPid()"))
        assertTrue("must be edge-typed, not a raw counter dump", body.contains("Event.Entered") && body.contains("Event.Settled"))
    }
}
