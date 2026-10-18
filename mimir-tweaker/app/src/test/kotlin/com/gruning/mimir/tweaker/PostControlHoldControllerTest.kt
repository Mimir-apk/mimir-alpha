package com.gruning.mimir.tweaker

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PostControlHoldController] over fakes: the marker's ordering around the entry write, the
 * readback-driven marker on a failed entry, the session yield, the stale release on a fresh
 * controller, and the heartbeat. The real port runs in `PostControlHoldDeviceTest`.
 */
class PostControlHoldControllerTest {

    private var now = 0L
    private val period = 50_000
    private var mode: Int? = TuningPlanner.FAN_SMART
    private var duty: Int? = 23_500
    private val lines = mutableListOf<String>()
    private val calls = mutableListOf<String>()
    private val markerFile = File(Files.createTempDirectory("pch").toFile(), PostControlHoldMarker.FILE_NAME)
    private val marker = PostControlHoldMarker(markerFile)
    private val watch = PostControlThermalWatch(clock = PostControlClock { now })

    private var forceResult = CheckedCommandResult(CheckedCommandStatus.CONFIRMED, detail = "forced")
    private var enterResult = CheckedCommandResult(CheckedCommandStatus.CONFIRMED, detail = "held")
    private var releaseResult = CheckedCommandResult(CheckedCommandStatus.CONFIRMED, detail = "released")
    private var enterLeavesMode: Int? = TuningPlanner.FAN_MANUAL
    private var releaseLeavesMode: Int? = TuningPlanner.FAN_SMART
    private val events = mutableListOf<String>()

    private fun controller(w: PostControlThermalWatch = watch) = PostControlHoldController(
        watch = w,
        marker = marker,
        live = { PostControlHoldLive(mode, duty, period) },
        force = {
            calls += "force marker=${marker.exists()}"
            duty = period
            forceResult
        },
        enter = {
            calls += "enter marker=${marker.exists()}"
            mode = enterLeavesMode
            duty = if (enterLeavesMode == TuningPlanner.FAN_MANUAL) period else 23_500
            enterResult
        },
        correct = {
            calls += "correct"
            duty = period
            CheckedCommandResult(CheckedCommandStatus.CONFIRMED, detail = "corrected")
        },
        release = {
            calls += "release marker=${marker.exists()}"
            mode = releaseLeavesMode
            duty = 23_500
            releaseResult
        },
        onFanWrite = { calls += "stamp" },
        now = { now },
        log = { lines += it },
        heartbeatMs = 60_000L,
        onHoldObligation = { events += "obligation marker=${marker.exists()} enters=${calls.count { it.startsWith("enter") }}" },
        onObligationCleared = { events += "cleared marker=${marker.exists()}" },
    )

    private fun hot(m: Int? = mode, d: Int? = duty) =
        PostControlThermalSample(cpuSafetyTempC = 103, gpuTempC = 67, vendorFanMode = m, duty = d, period = period)

    private fun cool(m: Int? = mode, d: Int? = duty) =
        PostControlThermalSample(cpuSafetyTempC = 70, gpuTempC = 60, vendorFanMode = m, duty = d, period = period)

    /** The vendor's Smart loop between ticks: duty back to the curve while the fan is Smart. */
    private fun vendorLoop() {
        if (mode == TuningPlanner.FAN_SMART) duty = 23_500
        now += 1_000L
    }

    private fun holdFromScratch(c: PostControlHoldController) {
        c.tick(hot())
        vendorLoop()
        c.tick(hot())
        assertTrue(watch.isHolding())
    }

    @Test
    fun `the marker is written before the entry write and the enter line names mode and duty`() {
        val c = controller()
        holdFromScratch(c)
        assertEquals(listOf("force marker=false", "stamp", "enter marker=true", "stamp"), calls)
        assertTrue(marker.exists())
        assertEquals(
            listOf("POST_CONTROL_HOLD enter reason=POST_CONTROL_PACKAGE_HOT_FAN_UNRESPONSIVE mode=6 duty=50000 period=50000"),
            lines,
        )
    }

    @Test
    fun `a failed entry that was handed back clears the marker -- one that left MANUAL keeps it and the next tick releases it`() {
        val c = controller()
        enterResult = CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "settle miss")
        enterLeavesMode = TuningPlanner.FAN_SMART
        holdOrNot(c)
        assertFalse(watch.isHolding())
        assertFalse("hand-back confirmed by readback: nothing is held", marker.exists())
        assertTrue(lines.last(), lines.last().startsWith("POST_CONTROL_HOLD enter_failed reason=POST_CONTROL_PACKAGE_HOT_FAN_UNRESPONSIVE mode=4"))

        // The other direction: the port's own hand-back failed too and the fan reads MANUAL.
        calls.clear(); lines.clear()
        now += 30_000L
        enterLeavesMode = TuningPlanner.FAN_MANUAL
        holdOrNot(c)
        assertFalse(watch.isHolding())
        assertTrue("MANUAL with no hold: the marker stays", marker.exists())
        vendorLoop()
        c.tick(hot())
        assertEquals("release marker=true", calls.last { it.startsWith("release") })
        assertFalse(marker.exists())
        assertTrue(lines.last(), lines.last().startsWith("POST_CONTROL_HOLD exit reason=stale_owner mode=4"))
    }

    private fun holdOrNot(c: PostControlHoldController) {
        c.tick(hot())
        vendorLoop()
        c.tick(hot())
    }

    @Test
    fun `a live hold yields to a session -- released, marker cleared, watch no longer holding`() {
        val c = controller()
        holdFromScratch(c)
        assertEquals(PostControlHoldYield.RELEASED, c.yieldToSession())
        assertFalse(watch.isHolding())
        assertFalse(marker.exists())
        assertEquals(TuningPlanner.FAN_SMART, mode)
        assertTrue(lines.last(), lines.last().startsWith("POST_CONTROL_HOLD exit reason=session mode=4 duty=23500 period=50000 heldMs="))
        assertEquals(PostControlHoldYield.NOT_HELD, c.yieldToSession())
    }

    /** the apply admission asks this before any telemetry; it must read only. */
    @Test
    fun `isHeld reads only -- false with nothing held, true while holding, fail-closed on an unreadable mode, false once lost`() {
        val c = controller()
        assertFalse(c.isHeld())
        holdFromScratch(c)
        assertTrue(c.isHeld())
        val callsBefore = calls.size
        val linesBefore = lines.size
        mode = null
        assertTrue("an unreadable mode may still be held", c.isHeld())
        mode = TuningPlanner.FAN_SPORT
        assertFalse("a known other value is not ours", c.isHeld())
        assertEquals("reads only: no write, no release", callsBefore, calls.size)
        assertEquals("reads only: nothing logged", linesBefore, lines.size)
        assertTrue("reads only: the marker is left for the tick to settle", marker.exists())
        assertTrue(watch.isHolding())
    }

    /**
     * Policy A, the re-check's leftover: a hold that enters between the apply admission and the
     * worker step must still win. The body asks [PostControlHoldController.isHeld] again right before
     * the capture and refuses; the hold is left exactly as it was.
     */
    @Test
    fun `a hold that starts after admission is seen again before the capture, and stays`() {
        val c = controller()
        assertFalse("admission: nothing held, the apply is admitted", c.isHeld())
        holdFromScratch(c)
        val callsBefore = calls.size
        assertTrue("the body re-checks right before the capture and refuses", c.isHeld())
        assertTrue("the hold stays: still holding", watch.isHolding())
        assertTrue("the hold stays: marker kept", marker.exists())
        assertEquals("the hold stays: no release, no write", callsBefore, calls.size)
        assertEquals(TuningPlanner.FAN_MANUAL, mode)
        mode = null
        assertTrue("an unreadable mode refuses too", c.isHeld())
    }

    @Test
    fun `a hold that cannot be released fails the yield closed, and a hold already lost is not held`() {
        val c = controller()
        holdFromScratch(c)
        releaseResult = CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "no authority")
        releaseLeavesMode = TuningPlanner.FAN_MANUAL
        assertEquals(PostControlHoldYield.RELEASE_FAILED, c.yieldToSession())
        assertTrue(watch.isHolding())
        assertTrue(marker.exists())
        assertTrue(lines.last(), lines.last().startsWith("POST_CONTROL_HOLD exit_failed reason=session"))

        // The player's finger took the fan meanwhile: nothing to hand back.
        mode = TuningPlanner.FAN_SPORT
        assertEquals(PostControlHoldYield.NOT_HELD, c.yieldToSession())
        assertFalse(watch.isHolding())
        assertFalse(marker.exists())
        assertTrue(lines.last(), lines.last().startsWith("POST_CONTROL_HOLD exit reason=lost mode=5"))
    }

    @Test
    fun `a fresh controller over a stale marker releases first and only then evaluates`() {
        marker.write(0L)
        mode = TuningPlanner.FAN_MANUAL
        duty = period
        val fresh = PostControlThermalWatch(clock = PostControlClock { now })
        val c = controller(fresh)
        val e = c.tick(cool())
        assertEquals(listOf("release marker=true", "stamp"), calls)
        assertFalse(marker.exists())
        assertTrue(lines.first(), lines.first().startsWith("POST_CONTROL_HOLD exit reason=stale_owner mode=4 duty=23500"))
        assertEquals(PostControlThermalDecision.Idle, e.decision)
    }

    @Test
    fun `a stale marker whose fan is not MANUAL is dropped without a write`() {
        marker.write(0L)
        mode = TuningPlanner.FAN_SMART
        val c = controller()
        c.tick(cool())
        assertTrue(calls.isEmpty())
        assertFalse(marker.exists())
        assertTrue(lines.single(), lines.single().startsWith("POST_CONTROL_HOLD exit reason=stale_owner_lost mode=4"))
    }

    @Test
    fun `a stale release that fails backs off before trying again`() {
        marker.write(0L)
        mode = TuningPlanner.FAN_MANUAL
        releaseResult = CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "busy")
        releaseLeavesMode = TuningPlanner.FAN_MANUAL
        val c = controller()
        c.tick(cool()); now += 1_000L
        c.tick(cool()); now += 1_000L
        assertEquals(1, calls.count { it.startsWith("release") })
        now += PostControlHoldController.STALE_RETRY_BACKOFF_MS
        c.tick(cool())
        assertEquals(2, calls.count { it.startsWith("release") })
        assertTrue(marker.exists())
    }

    @Test
    fun `holding logs one heartbeat a minute and the cool release logs its exit with the held time`() {
        val c = controller()
        holdFromScratch(c)
        lines.clear()
        repeat(130) { now += 1_000L; c.tick(hot()) }
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("POST_CONTROL_HOLD holding cpu=103 gpu=67 mode=6 duty=50000 period=50000 heldMs="))
        lines.clear()
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS) { now += 1_000L; c.tick(cool()) }
        assertFalse(watch.isHolding())
        assertFalse(marker.exists())
        assertEquals("release marker=true", calls.last { it.startsWith("release") })
        assertTrue(lines.last(), lines.last().startsWith("POST_CONTROL_HOLD exit reason=cool mode=4 duty=23500 period=50000 heldMs="))
    }

    @Test
    fun `a lost hold clears the marker and names the mode that took the fan`() {
        val c = controller()
        holdFromScratch(c)
        mode = TuningPlanner.FAN_SILENT
        now += 1_000L
        c.tick(hot())
        assertFalse(watch.isHolding())
        assertFalse(marker.exists())
        assertEquals("POST_CONTROL_HOLD exit reason=lost mode=1 duty=50000 period=50000", lines.last())
        assertFalse(calls.any { it.startsWith("release") })
    }

    // ---- the fix round, 2026-10-14: null fails closed, the session release backs off, the obligation callbacks ----

    @Test
    fun `an unreadable fan mode fails the session yield closed without a write`() {
        val c = controller()
        holdFromScratch(c)
        mode = null
        assertEquals(PostControlHoldYield.RELEASE_FAILED, c.yieldToSession())
        assertTrue(watch.isHolding())
        assertTrue(marker.exists())
        assertFalse(calls.any { it.startsWith("release") })
        assertTrue(lines.last(), lines.last().startsWith("POST_CONTROL_HOLD exit_failed reason=session mode=null"))
    }

    @Test
    fun `an unreadable fan mode over a stale marker keeps the marker, writes nothing, and backs off`() {
        marker.write(0L)
        mode = null
        val c = controller()
        c.tick(cool()); now += 1_000L
        c.tick(cool()); now += 1_000L
        assertTrue(calls.toString(), calls.isEmpty())
        assertTrue(marker.exists())
        assertEquals(1, lines.count { it.startsWith("POST_CONTROL_HOLD stale_release_deferred reason=mode_unreadable") })
        now += PostControlHoldController.STALE_RETRY_BACKOFF_MS
        mode = TuningPlanner.FAN_MANUAL
        c.tick(cool())
        assertEquals("release marker=true", calls.first())
        assertFalse(marker.exists())
    }

    @Test
    fun `a failed entry whose readback is unreadable keeps the marker`() {
        val c = controller()
        enterResult = CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "settle miss")
        enterLeavesMode = null
        holdOrNot(c)
        assertFalse(watch.isHolding())
        assertTrue("unreadable: it may still be held", marker.exists())
        assertTrue(lines.last(), lines.last().startsWith("POST_CONTROL_HOLD enter_failed reason=POST_CONTROL_PACKAGE_HOT_FAN_UNRESPONSIVE mode=null"))
        assertTrue(events.none { it.startsWith("cleared") })
    }

    @Test
    fun `a failed session release is not re-issued on every apply -- the next yield inside the backoff writes nothing`() {
        val c = controller()
        holdFromScratch(c)
        releaseResult = CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "no authority")
        releaseLeavesMode = TuningPlanner.FAN_MANUAL
        assertEquals(PostControlHoldYield.RELEASE_FAILED, c.yieldToSession())
        val writes = calls.count { it.startsWith("release") }
        now += 1_000L
        assertEquals(PostControlHoldYield.RELEASE_FAILED, c.yieldToSession())
        assertEquals(writes, calls.count { it.startsWith("release") })
        assertTrue(lines.last(), lines.last().startsWith("POST_CONTROL_HOLD exit_deferred reason=session remainingMs="))
        now += PostControlHoldController.STALE_RETRY_BACKOFF_MS
        releaseResult = CheckedCommandResult(CheckedCommandStatus.CONFIRMED, detail = "released")
        releaseLeavesMode = TuningPlanner.FAN_SMART
        assertEquals(PostControlHoldYield.RELEASED, c.yieldToSession())
        assertEquals(writes + 1, calls.count { it.startsWith("release") })
    }

    @Test
    fun `the obligation callbacks bracket the hold -- raised after the marker and before the entry write, cleared after the confirmed release`() {
        val c = controller()
        holdFromScratch(c)
        assertEquals(listOf("obligation marker=true enters=0"), events)
        assertEquals(PostControlHoldYield.RELEASED, c.yieldToSession())
        assertEquals(listOf("obligation marker=true enters=0", "cleared marker=false"), events)
        // A drop (the fan taken by something else) clears the obligation too.
        events.clear()
        marker.write(0L)
        mode = TuningPlanner.FAN_SPORT
        c.tick(cool())
        assertEquals(listOf("cleared marker=false"), events)
    }

    @Test
    fun `a marker that will not delete is never reported cleared, backs off, and logs once per backoff`() {
        var deletes = 0
        val stubborn = PostControlHoldMarker(markerFile) { deletes++; false }
        stubborn.write(0L)
        mode = TuningPlanner.FAN_SMART
        val c = PostControlHoldController(
            watch = watch,
            marker = stubborn,
            live = { PostControlHoldLive(mode, duty, period) },
            force = { calls += "force"; forceResult },
            enter = { calls += "enter"; enterResult },
            correct = { calls += "correct"; CheckedCommandResult(CheckedCommandStatus.CONFIRMED, detail = "corrected") },
            release = { calls += "release"; releaseResult },
            onFanWrite = {},
            now = { now },
            log = { lines += it },
            onObligationCleared = { events += "cleared" },
        )
        repeat(10) { c.tick(cool()); now += 1_000L }
        assertEquals("one delete attempt per backoff", 1, deletes)
        assertEquals(1, lines.count { it.startsWith("POST_CONTROL_HOLD exit reason=stale_owner_lost") })
        assertEquals(1, lines.count { it.startsWith("POST_CONTROL_HOLD marker_undeletable") })
        assertTrue("never cleared", events.isEmpty())
        assertTrue(calls.isEmpty())
        now += PostControlHoldController.STALE_RETRY_BACKOFF_MS
        c.tick(cool())
        assertEquals(2, deletes)
        assertEquals(2, lines.count { it.startsWith("POST_CONTROL_HOLD marker_undeletable") })
        assertTrue(events.isEmpty())
    }
}
