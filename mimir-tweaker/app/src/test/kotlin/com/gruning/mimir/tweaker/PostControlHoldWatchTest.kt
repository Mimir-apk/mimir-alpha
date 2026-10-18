package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full-fan hold until cooling completes: the hold half of [PostControlThermalWatch],
 * pure. The observed Odin case is the first test -- a confirmed force whose duty is pulled back by
 * the vendor's loop escalates to exactly one hold, after which nothing is written until the device
 * is cool. The existing force/budget/disarm coverage stays in [PostControlThermalWatchTest].
 */
class PostControlHoldWatchTest {

    private var now = 0L

    private fun watch(
        maxHoldCorrections: Int = PostControlThermalWatch.MAX_HOLD_CORRECTIONS,
        maxReentries: Int = PostControlThermalWatch.MAX_HOLD_REENTRIES_PER_WINDOW,
    ) = PostControlThermalWatch(
        clock = PostControlClock { now },
        maxHoldCorrections = maxHoldCorrections,
        maxHoldReentriesPerWindow = maxReentries,
    )

    private val period = 50_000
    private val curve = 23_500

    private fun sample(mode: Int?, duty: Int?, cpu: Int? = 103, gpu: Int? = 67) =
        PostControlThermalSample(cpuSafetyTempC = cpu, gpuTempC = gpu, vendorFanMode = mode, duty = duty, period = period)

    private fun hotSmart(duty: Int = curve) = sample(TuningPlanner.FAN_SMART, duty)
    private fun hotHeld(duty: Int = period) = sample(TuningPlanner.FAN_MANUAL, duty)
    private fun coolHeld(cpu: Int = 70, gpu: Int = 60) = sample(TuningPlanner.FAN_MANUAL, period, cpu, gpu)

    private fun tick(w: PostControlThermalWatch, s: PostControlThermalSample, advanceMs: Long = 1_000L): PostControlThermalEvaluation {
        now += advanceMs
        return w.evaluate(s)
    }

    /** The Odin at 03:19: force, the vendor pulls duty back, the next hot tick holds. */
    private fun enterHold(w: PostControlThermalWatch) {
        val force = tick(w, hotSmart()).decision
        assertTrue("$force", force is PostControlThermalDecision.ForceCooling)
        w.acknowledge(force, confirmed = true)
        val enter = tick(w, hotSmart()).decision
        assertEquals(PostControlThermalDecision.EnterHold("POST_CONTROL_PACKAGE_HOT_FAN_UNRESPONSIVE"), enter)
        w.acknowledge(enter, confirmed = true)
        assertTrue(w.isHolding())
    }

    @Test
    fun `a confirmed force whose duty is pulled back escalates to exactly one hold and then writes nothing`() {
        val w = watch()
        enterHold(w)
        repeat(120) {
            val e = tick(w, hotHeld())
            assertEquals(PostControlThermalDecision.Holding, e.decision)
            assertFalse(e.shouldDisarm)
        }
        assertEquals(120_000L, w.heldMs())
    }

    @Test
    fun `shouldDisarm is false for every tick while holding -- even cool and at full duty`() {
        val w = watch()
        enterHold(w)
        repeat(20) {
            val e = tick(w, coolHeld())
            assertFalse("tick $it: ${e.decision}", e.shouldDisarm)
        }
    }

    @Test
    fun `an unconfirmed force is retried rather than held`() {
        val w = watch()
        val force = tick(w, hotSmart()).decision
        w.acknowledge(force, confirmed = false)
        assertTrue(tick(w, hotSmart()).decision is PostControlThermalDecision.ForceCooling)
        assertFalse(w.isHolding())
    }

    @Test
    fun `a force that sticks never holds -- full duty is idle, cool Smart disarms as before`() {
        val w = watch()
        val force = tick(w, hotSmart()).decision
        w.acknowledge(force, confirmed = true)
        assertEquals(PostControlThermalDecision.Idle, tick(w, hotSmart(duty = period)).decision)
        var disarmed = false
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS) {
            disarmed = tick(w, sample(TuningPlanner.FAN_SMART, curve, cpu = 60, gpu = 50)).shouldDisarm
        }
        assertTrue(disarmed)
        assertFalse(w.isHolding())
    }

    @Test
    fun `an unconfirmed entry is retried once inside the budget -- a second failure leaves the window to the vendor`() {
        val w = watch()
        val force = tick(w, hotSmart()).decision
        w.acknowledge(force, confirmed = true)
        val enter = tick(w, hotSmart()).decision
        assertTrue(enter is PostControlThermalDecision.EnterHold)
        w.acknowledge(enter, confirmed = false)
        assertFalse(w.isHolding())
        // Force + entry spent the 2-per-5-s burst: the next tick only escalates.
        assertTrue(tick(w, hotSmart()).decision is PostControlThermalDecision.Escalate)
        // Past the burst window (still inside the 30 s force window) the entry is retried once more.
        val retry = tick(w, hotSmart(), advanceMs = 5_000L).decision
        assertTrue("$retry", retry is PostControlThermalDecision.EnterHold)
        w.acknowledge(retry, confirmed = false)
        // Two entries that did not land count like two lost holds: left to the vendor for the rest
        // of the window . One line, then nothing -- no force either.
        val left = tick(w, hotSmart()).decision
        assertTrue("$left", left is PostControlThermalDecision.LeftToVendor)
        val remaining = (left as PostControlThermalDecision.LeftToVendor).remainingMs
        assertTrue("$remaining", remaining in 1..PostControlThermalWatch.HOLD_REENTRY_WINDOW_MS)
        var ticks = 0
        while ((ticks + 1) * 1_000L < remaining) {
            val e = tick(w, hotSmart())
            assertEquals("tick $ticks", PostControlThermalDecision.Idle, e.decision)
            assertFalse(e.shouldDisarm)
            ticks++
        }
        assertFalse(w.isHolding())
        // The window ended: the watch may act again, and the first act is a force.
        assertTrue(tick(w, hotSmart(), advanceMs = 1_000L).decision is PostControlThermalDecision.ForceCooling)
    }

    @Test
    fun `the hold releases for cool only below the hysteresis for the debounce count, and ignorance resets it`() {
        val w = watch()
        enterHold(w)
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS - 1) {
            assertEquals(PostControlThermalDecision.Holding, tick(w, coolHeld()).decision)
        }
        // Below the emergency bar but not below the hysteresis: not cool enough, and the count restarts.
        assertEquals(PostControlThermalDecision.Holding, tick(w, coolHeld(cpu = PostControlThermalWatch.PACKAGE_EMERGENCY_C - 1)).decision)
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS - 1) {
            assertEquals(PostControlThermalDecision.Holding, tick(w, coolHeld()).decision)
        }
        // A missing channel never counts and restarts the count.
        assertEquals(PostControlThermalDecision.Holding, tick(w, coolHeld().copy(gpuTempC = null)).decision)
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS - 1) {
            assertEquals(PostControlThermalDecision.Holding, tick(w, coolHeld()).decision)
        }
        val release = tick(w, coolHeld()).decision
        assertEquals(PostControlThermalDecision.ReleaseHold(PostControlThermalDecision.ReleaseHold.REASON_COOL), release)
        // Unconfirmed: still holding, asked again next tick.
        w.acknowledge(release, confirmed = false)
        assertTrue(w.isHolding())
        assertEquals(release, tick(w, coolHeld()).decision)
        w.acknowledge(release, confirmed = true)
        assertFalse(w.isHolding())
    }

    @Test
    fun `after a cool release the watch disarms on Smart and holds again if the device reheats -- cool exits never count`() {
        val w = watch(maxReentries = 1)
        repeat(3) { round ->
            enterHold(w)
            repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS - 1) { tick(w, coolHeld()) }
            val release = tick(w, coolHeld()).decision
            assertTrue("round $round: $release", release is PostControlThermalDecision.ReleaseHold)
            w.acknowledge(release, confirmed = true)
            now += 60_000L // past the force window and the burst/window budget
        }
        var disarmed = false
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS) {
            disarmed = tick(w, sample(TuningPlanner.FAN_SMART, curve, cpu = 60, gpu = 50)).shouldDisarm
        }
        assertTrue(disarmed)
    }

    @Test
    fun `a lost hold is dropped without a write, and the third loss inside the window leaves the fan to the vendor`() {
        val w = watch(maxReentries = 2)
        repeat(2) {
            enterHold(w)
            val lost = tick(w, sample(TuningPlanner.FAN_SPORT, curve)).decision
            assertEquals(PostControlThermalDecision.HoldLost(TuningPlanner.FAN_SPORT), lost)
            assertFalse(w.isHolding())
            now += 31_000L
        }
        // Third time inside the 10-minute window: not even the force goes out.
        val left = tick(w, hotSmart()).decision
        assertTrue("$left", left is PostControlThermalDecision.LeftToVendor)
        repeat(30) { assertEquals(PostControlThermalDecision.Idle, tick(w, hotSmart()).decision) }
        assertFalse(w.isHolding())
        // The window rolls and the watch acts again, force first.
        now += PostControlThermalWatch.HOLD_REENTRY_WINDOW_MS
        assertTrue(tick(w, hotSmart()).decision is PostControlThermalDecision.ForceCooling)
    }

    @Test
    fun `a hold entered on the CPU alone releases on the CPU alone -- a channel absent at entry never gates the exit`() {
        val w = watch()
        val force = tick(w, hotSmart().copy(gpuTempC = null)).decision
        assertTrue("$force", force is PostControlThermalDecision.ForceCooling)
        w.acknowledge(force, confirmed = true)
        val enter = tick(w, hotSmart().copy(gpuTempC = null)).decision
        assertTrue("$enter", enter is PostControlThermalDecision.EnterHold)
        w.acknowledge(enter, confirmed = true)
        // The GPU unreadable, or reading hot: neither matters once the CPU is cool.
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS - 1) {
            val gpu = if (it % 2 == 0) null else 95
            assertEquals(PostControlThermalDecision.Holding, tick(w, coolHeld().copy(gpuTempC = gpu)).decision)
        }
        assertEquals(
            PostControlThermalDecision.ReleaseHold(PostControlThermalDecision.ReleaseHold.REASON_COOL),
            tick(w, coolHeld().copy(gpuTempC = null)).decision,
        )
    }

    @Test
    fun `a channel witnessed at entry that goes unreadable releases the hold as telemetry_lost after the bounded streak`() {
        val w = watch()
        enterHold(w) // both channels present at entry
        repeat(PostControlThermalWatch.HOLD_TELEMETRY_LOST_TICKS - 1) {
            assertEquals(PostControlThermalDecision.Holding, tick(w, hotHeld().copy(cpuSafetyTempC = null)).decision)
        }
        // One readable tick resets the streak...
        assertEquals(PostControlThermalDecision.Holding, tick(w, hotHeld()).decision)
        repeat(PostControlThermalWatch.HOLD_TELEMETRY_LOST_TICKS - 1) {
            assertEquals(PostControlThermalDecision.Holding, tick(w, hotHeld().copy(cpuSafetyTempC = null)).decision)
        }
        // ...and the full streak hands the fan to the vendor's own regulation.
        val release = tick(w, hotHeld().copy(cpuSafetyTempC = null)).decision
        assertEquals(PostControlThermalDecision.ReleaseHold(PostControlThermalDecision.ReleaseHold.REASON_TELEMETRY_LOST), release)
        w.acknowledge(release, confirmed = true)
        assertFalse(w.isHolding())
    }

    @Test
    fun `a witnessed channel still at emergency keeps the hold through the lost streak, and a telemetry_lost release counts toward the cap`() {
        val w = watch(maxReentries = 1)
        enterHold(w) // both channels present at entry
        // The CPU reads null for the whole streak and well past it while the GPU, also witnessed, is at emergency.
        repeat(PostControlThermalWatch.HOLD_TELEMETRY_LOST_TICKS + 30) {
            val e = tick(w, sample(TuningPlanner.FAN_MANUAL, period, cpu = null, gpu = PostControlThermalWatch.GPU_EMERGENCY_C))
            assertEquals("tick $it", PostControlThermalDecision.Holding, e.decision)
            assertFalse(e.shouldDisarm)
        }
        assertTrue(w.isHolding())
        // The GPU drops below emergency (not yet cool) with the CPU still blind: the streak was running, so this releases.
        val release = tick(w, sample(TuningPlanner.FAN_MANUAL, period, cpu = null, gpu = PostControlThermalWatch.GPU_EMERGENCY_C - 1)).decision
        assertEquals(PostControlThermalDecision.ReleaseHold(PostControlThermalDecision.ReleaseHold.REASON_TELEMETRY_LOST), release)
        w.acknowledge(release, confirmed = true)
        assertFalse(w.isHolding())
        // It counted: with the cap at one, the next hot tick is left to the vendor rather than forced.
        now += 31_000L
        assertTrue(tick(w, hotSmart()).decision is PostControlThermalDecision.LeftToVendor)
    }

    @Test
    fun `a witnessed channel reading null resets the cool count -- ignorance never counts toward cool`() {
        val w = watch()
        enterHold(w)
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS - 1) { tick(w, coolHeld()) }
        assertEquals(PostControlThermalDecision.Holding, tick(w, coolHeld().copy(cpuSafetyTempC = null)).decision)
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS - 1) {
            assertEquals(PostControlThermalDecision.Holding, tick(w, coolHeld()).decision)
        }
        assertTrue(tick(w, coolHeld()).decision is PostControlThermalDecision.ReleaseHold)
    }

    @Test
    fun `held duty drift is corrected a bounded number of times, then the hold releases for drift`() {
        val w = watch(maxHoldCorrections = 2)
        enterHold(w)
        now += 5_000L // clear the burst the entry used
        val first = tick(w, hotHeld(duty = period / 2)).decision
        assertEquals(PostControlThermalDecision.CorrectHold("POST_CONTROL_HOLD_DUTY_DRIFT"), first)
        w.acknowledge(first, confirmed = true)
        assertEquals(PostControlThermalDecision.Holding, tick(w, hotHeld()).decision)
        now += 5_000L
        val second = tick(w, hotHeld(duty = period / 2)).decision
        assertTrue(second is PostControlThermalDecision.CorrectHold)
        w.acknowledge(second, confirmed = false) // attempts count, confirmed or not
        now += 20_000L // the entry's two attempts leave the 30 s window; the release fits the budget
        val release = tick(w, hotHeld(duty = period / 2)).decision
        assertEquals(PostControlThermalDecision.ReleaseHold(PostControlThermalDecision.ReleaseHold.REASON_DRIFT), release)
        w.acknowledge(release, confirmed = true)
        assertFalse(w.isHolding())
    }

    @Test
    fun `a null mode under the hold acts on nothing and a Smart-looking sample while watching stays idle`() {
        val w = watch()
        enterHold(w)
        assertEquals(PostControlThermalDecision.Holding, tick(w, sample(null, null)).decision)
        assertTrue(w.isHolding())
        w.holdReleasedExternally()
        assertFalse(w.isHolding())
        assertEquals(PostControlThermalDecision.Idle, tick(w, hotHeld()).decision)
    }

    @Test
    fun `reset clears the hold phase and every counter`() {
        val w = watch()
        enterHold(w)
        w.reset()
        assertFalse(w.isHolding())
        assertEquals(null, w.heldMs())
        assertTrue(tick(w, hotSmart()).decision is PostControlThermalDecision.ForceCooling)
    }

    @Test
    fun `post-control hold constants preserve the thermal safety bounds`() {
        assertEquals(5, PostControlThermalWatch.HOLD_EXIT_HYSTERESIS_C)
        assertEquals(2, PostControlThermalWatch.MAX_HOLD_CORRECTIONS)
        assertEquals(2, PostControlThermalWatch.MAX_HOLD_REENTRIES_PER_WINDOW)
        assertEquals(10 * 60_000L, PostControlThermalWatch.HOLD_REENTRY_WINDOW_MS)
        assertEquals(60, PostControlThermalWatch.HOLD_TELEMETRY_LOST_TICKS)
    }
}
