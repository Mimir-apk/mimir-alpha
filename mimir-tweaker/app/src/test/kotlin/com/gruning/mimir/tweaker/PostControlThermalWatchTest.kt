package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure decision coverage for [PostControlThermalWatch] --
 * see that class's own kdoc for the mechanism this closes. No Android dependency; every input is
 * an explicit [PostControlThermalSample]. [currentNowMs] is mutated between calls on the SAME
 * watch instance to exercise the bounded budget across time, exactly like
 * [HardwareSessionSupervisorTest]'s own `Env.now` pattern for the sibling repair/reassert budgets.
 */
class PostControlThermalWatchTest {

    private var currentNowMs: Long = 0L

    @Before
    fun resetClock() {
        currentNowMs = 0L
    }

    private fun newWatch(disarmDebounceTicks: Int = PostControlThermalWatch.DISARM_DEBOUNCE_TICKS) =
        PostControlThermalWatch(
            clock = PostControlClock { currentNowMs },
            disarmDebounceTicks = disarmDebounceTicks,
        )

    private fun hotGpuSample(duty: Int = 0, period: Int = 50_000) = PostControlThermalSample(
        cpuSafetyTempC = 40,
        gpuTempC = PostControlThermalWatch.GPU_EMERGENCY_C,
        vendorFanMode = TuningPlanner.FAN_SMART,
        duty = duty,
        period = period,
    )

    private fun hotPackageSample(duty: Int = 0, period: Int = 50_000) = PostControlThermalSample(
        cpuSafetyTempC = PostControlThermalWatch.PACKAGE_EMERGENCY_C,
        gpuTempC = 40,
        vendorFanMode = TuningPlanner.FAN_SMART,
        duty = duty,
        period = period,
    )

    private fun coolSample(duty: Int = 0, period: Int = 50_000) = PostControlThermalSample(
        cpuSafetyTempC = PostControlThermalWatch.PACKAGE_EMERGENCY_C - 1,
        gpuTempC = PostControlThermalWatch.GPU_EMERGENCY_C - 1,
        vendorFanMode = TuningPlanner.FAN_SMART,
        duty = duty,
        period = period,
    )

    // --- Decision coverage (unchanged behavior, now read off .decision) ---

    @Test
    fun `idle when vendor fan mode is not Smart -- Mimir or something else already owns the fan`() {
        val w = newWatch()
        listOf(null, TuningPlanner.FAN_SPORT, TuningPlanner.FAN_MANUAL, TuningPlanner.FAN_SILENT).forEach { mode ->
            val decision = w.evaluate(hotGpuSample().copy(vendorFanMode = mode)).decision
            assertEquals("mode=$mode", PostControlThermalDecision.Idle, decision)
        }
    }

    @Test
    fun `idle when duty or period telemetry is missing or out of range`() {
        val w = newWatch()
        assertEquals(PostControlThermalDecision.Idle, w.evaluate(hotGpuSample().copy(duty = null)).decision)
        assertEquals(PostControlThermalDecision.Idle, w.evaluate(hotGpuSample().copy(period = null)).decision)
        assertEquals(PostControlThermalDecision.Idle, w.evaluate(hotGpuSample().copy(period = 0)).decision)
        assertEquals(
            PostControlThermalDecision.Idle,
            w.evaluate(hotGpuSample().copy(duty = 60_000, period = 50_000)).decision, // duty > period
        )
    }

    @Test
    fun `idle when duty already reads full -- vendor Smart is doing its job`() {
        val decision = newWatch().evaluate(hotGpuSample(duty = 50_000, period = 50_000)).decision
        assertEquals(PostControlThermalDecision.Idle, decision)
    }

    @Test
    fun `idle when neither package nor GPU has reached its emergency threshold`() {
        val decision = newWatch().evaluate(coolSample()).decision
        assertEquals(PostControlThermalDecision.Idle, decision)
    }

    @Test
    fun `idle when a temperature channel is missing -- fails closed, never invents a reading`() {
        val decision = newWatch().evaluate(hotGpuSample().copy(gpuTempC = null, cpuSafetyTempC = null)).decision
        assertEquals(PostControlThermalDecision.Idle, decision)
    }

    @Test
    fun `force cooling fires for a GPU emergency reading with an unresponsive fan`() {
        val decision = newWatch().evaluate(hotGpuSample()).decision
        assertEquals(
            PostControlThermalDecision.ForceCooling("POST_CONTROL_GPU_HOT_FAN_UNRESPONSIVE"),
            decision,
        )
    }

    @Test
    fun `force cooling fires for a package emergency reading with an unresponsive fan`() {
        val decision = newWatch().evaluate(hotPackageSample()).decision
        assertEquals(
            PostControlThermalDecision.ForceCooling("POST_CONTROL_PACKAGE_HOT_FAN_UNRESPONSIVE"),
            decision,
        )
    }

    @Test
    fun `GPU reason takes precedence when both channels are simultaneously in emergency`() {
        val decision = newWatch().evaluate(
            hotGpuSample().copy(cpuSafetyTempC = PostControlThermalWatch.PACKAGE_EMERGENCY_C),
        ).decision
        assertEquals(
            PostControlThermalDecision.ForceCooling("POST_CONTROL_GPU_HOT_FAN_UNRESPONSIVE"),
            decision,
        )
    }

    @Test
    fun `bounded burst budget exhausts to Escalate after two attempts within 5s`() {
        val w = newWatch()
        // Burst cap is 2 per 5s -- same constants as admitTargetRepair / admitDutyReassert.
        currentNowMs = 0L
        assertTrue(w.evaluate(hotGpuSample()).decision is PostControlThermalDecision.ForceCooling)
        currentNowMs = 1_000L
        assertTrue(w.evaluate(hotGpuSample()).decision is PostControlThermalDecision.ForceCooling)
        currentNowMs = 2_000L
        val third = w.evaluate(hotGpuSample()).decision
        assertEquals(PostControlThermalDecision.Escalate("POST_CONTROL_GPU_HOT_FAN_UNRESPONSIVE"), third)
    }

    @Test
    fun `budget rolls forward on its own -- a later tick outside the burst window force-cools again, no reset needed`() {
        val w = newWatch()
        currentNowMs = 0L
        w.evaluate(hotGpuSample())
        currentNowMs = 1_000L
        w.evaluate(hotGpuSample())
        currentNowMs = 2_000L
        val exhausted = w.evaluate(hotGpuSample()).decision
        assertTrue(exhausted is PostControlThermalDecision.Escalate)

        currentNowMs = 2_000L + PostControlThermalWatch.BURST_WINDOW_MS + 1L
        val afterBurstWindow = w.evaluate(hotGpuSample()).decision
        assertTrue(
            "expected ForceCooling once the 5s burst window rolls forward",
            afterBurstWindow is PostControlThermalDecision.ForceCooling,
        )
    }

    @Test
    fun `window cap of four blocks a fifth attempt even outside every burst window`() {
        val w = newWatch()
        listOf(0L, 8_000L, 16_000L, 24_000L).forEach { t ->
            currentNowMs = t
            val decision = w.evaluate(hotGpuSample()).decision
            assertTrue("t=$t should force-cool", decision is PostControlThermalDecision.ForceCooling)
        }
        currentNowMs = 29_000L
        val fifth = w.evaluate(hotGpuSample()).decision
        assertEquals(PostControlThermalDecision.Escalate("POST_CONTROL_GPU_HOT_FAN_UNRESPONSIVE"), fifth)
    }

    @Test
    fun `reset clears the budget so a fresh session leaves this watch ready to arm again`() {
        val w = newWatch()
        currentNowMs = 0L
        w.evaluate(hotGpuSample())
        currentNowMs = 1_000L
        w.evaluate(hotGpuSample())
        w.reset()
        currentNowMs = 1_500L
        val afterReset = w.evaluate(hotGpuSample()).decision
        assertTrue(afterReset is PostControlThermalDecision.ForceCooling)
    }

    @Test
    fun `emergency thresholds are the same absolute bar an active session already enforces, not a new number`() {
        assertEquals(FanThermalProfile.FULL_FAN_TEMP_C, PostControlThermalWatch.PACKAGE_EMERGENCY_C)
        assertEquals(
            SmartFanThermalController.GPU_WITNESS_FULL_FAN_C,
            PostControlThermalWatch.GPU_EMERGENCY_C,
        )
    }

    @Test
    fun `budget shape matches the existing sibling mechanisms exactly, not an independently tuned number`() {
        assertEquals(30_000L, PostControlThermalWatch.WINDOW_MS)
        assertEquals(4, PostControlThermalWatch.MAX_ATTEMPTS_PER_WINDOW)
        assertEquals(5_000L, PostControlThermalWatch.BURST_WINDOW_MS)
        assertEquals(2, PostControlThermalWatch.MAX_ATTEMPTS_PER_BURST)
    }

    // --- Disarm coverage (D2 gap closed after advisor review: the watch must not poll a cool,
    // idle device forever after every ordinary non-thermal Restore). ---

    @Test
    fun `does not disarm before the debounce count is reached`() {
        val w = newWatch(disarmDebounceTicks = 3)
        repeat(2) { i ->
            currentNowMs = i * 1_000L
            val evaluation = w.evaluate(coolSample())
            assertFalse("tick $i should not yet disarm", evaluation.shouldDisarm)
        }
    }

    @Test
    fun `disarms after N consecutive confirmed-cool ticks`() {
        val w = newWatch(disarmDebounceTicks = 3)
        var last = w.evaluate(coolSample())
        currentNowMs = 1_000L
        last = w.evaluate(coolSample())
        assertFalse(last.shouldDisarm)
        currentNowMs = 2_000L
        last = w.evaluate(coolSample())
        assertTrue("third consecutive confirmed-cool tick should disarm", last.shouldDisarm)
    }

    @Test
    fun `disarms after N consecutive ticks where duty already reads full, even while still hot`() {
        // Full duty means SOMETHING is actively driving the fan already -- this watch's job
        // (detect an unresponsive fan) is satisfied even though the temperature itself has not
        // yet dropped below the emergency bar.
        val w = newWatch(disarmDebounceTicks = 2)
        currentNowMs = 0L
        assertFalse(w.evaluate(hotGpuSample(duty = 50_000, period = 50_000)).shouldDisarm)
        currentNowMs = 1_000L
        assertTrue(w.evaluate(hotGpuSample(duty = 50_000, period = 50_000)).shouldDisarm)
    }

    @Test
    fun `a single hot unresponsive tick resets the consecutive-safe counter to zero`() {
        val w = newWatch(disarmDebounceTicks = 3)
        currentNowMs = 0L
        w.evaluate(coolSample())
        currentNowMs = 1_000L
        w.evaluate(coolSample())
        // Two consecutive cool ticks banked; a hot tick must zero the counter, not merely pause it.
        currentNowMs = 2_000L
        val hot = w.evaluate(hotGpuSample())
        assertFalse(hot.shouldDisarm)
        currentNowMs = 3_000L
        w.evaluate(coolSample())
        currentNowMs = 4_000L
        val secondAfterHot = w.evaluate(coolSample())
        assertFalse("only two consecutive cool ticks since the reset -- must not yet disarm", secondAfterHot.shouldDisarm)
        currentNowMs = 5_000L
        val thirdAfterHot = w.evaluate(coolSample())
        assertTrue(thirdAfterHot.shouldDisarm)
    }

    @Test
    fun `missing telemetry never counts toward confirmed-safe -- ignorance must not disarm this watch`() {
        val w = newWatch(disarmDebounceTicks = 2)
        currentNowMs = 0L
        assertFalse(w.evaluate(coolSample().copy(gpuTempC = null)).shouldDisarm)
        currentNowMs = 1_000L
        assertFalse(w.evaluate(coolSample().copy(gpuTempC = null)).shouldDisarm)
        currentNowMs = 2_000L
        assertFalse(w.evaluate(coolSample().copy(duty = null)).shouldDisarm)
        currentNowMs = 3_000L
        assertFalse(w.evaluate(coolSample().copy(vendorFanMode = TuningPlanner.FAN_SPORT)).shouldDisarm)
    }

    @Test
    fun `a tick that force-cools or escalates never disarms, even after prior cool ticks`() {
        val w = newWatch(disarmDebounceTicks = 2)
        currentNowMs = 0L
        w.evaluate(coolSample())
        currentNowMs = 1_000L
        val forceCooling = w.evaluate(hotGpuSample())
        assertTrue(forceCooling.decision is PostControlThermalDecision.ForceCooling)
        assertFalse(forceCooling.shouldDisarm)
    }

    @Test
    fun `reset also clears the disarm debounce counter`() {
        val w = newWatch(disarmDebounceTicks = 2)
        currentNowMs = 0L
        w.evaluate(coolSample())
        w.reset()
        currentNowMs = 1_000L
        // Only one confirmed-cool tick since reset -- must not disarm yet.
        assertFalse(w.evaluate(coolSample()).shouldDisarm)
    }
}
