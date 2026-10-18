package com.gruning.mimir.tweaker

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * count events, not attempts.
 *
 * Device evidence from a picker interaction trace: six separate vendor excursions in fourteen seconds, the
 * first two repaired in ~800 ms, then the 2-per-5s burst cap was spent and the sixth sat at 50% for
 * TEN SECONDS with the controller forbidden from touching it. The cap was written to stop a
 * controller thrashing against a fan that will not take the value -- not to ration repairs that work.
 */
class ConfirmedRepairBudgetTest {
    private val profile = requireNotNull(
        FanThermalProfiles.forAppliedProfile("balanced", CpuCeilingTransport.ODIN_3_PROFILE),
    )

    @Test
    fun `repairs that the fan confirms do not exhaust the burst budget`() {
        val controller = SmartFanThermalController()
        var now = 1_000L
        var commanded = command(controller, now)
        // Six excursion-and-recovery cycles inside one 5s burst window: far past the old cap of 2.
        repeat(6) { cycle ->
            now += 200
            val repair = controller.tick(profile, sample(duty = 25_000, now = now), now)
            assertTrue(
                "cycle $cycle: a fresh vendor excursion must still be repairable -- this is the " +
                    "observed ten-second fan burst",
                repair is FanControlDecision.WriteDuty,
            )
            now += 200
            // The hardware took it: the next tick observes the commanded duty.
            controller.tick(profile, sample(duty = (repair as FanControlDecision.WriteDuty).duty, now = now), now)
            commanded = repair.duty
        }
        assertTrue("sanity: a duty was commanded", commanded > 0)
    }

    @Test
    fun `repairs the fan refuses still burn the budget`() {
        val controller = SmartFanThermalController()
        var now = 1_000L
        command(controller, now)
        // Same unresolved excursion, never confirmed: this IS the thrash the cap exists for.
        now += 200
        val first = controller.tick(profile, sample(duty = 25_000, now = now), now)
        assertTrue("first attempt admitted", first is FanControlDecision.WriteDuty)
        now += 200
        val second = controller.tick(profile, sample(duty = 25_000, now = now), now)
        assertTrue("second attempt admitted", second is FanControlDecision.WriteDuty)
        now += 200
        val third = controller.tick(profile, sample(duty = 25_000, now = now), now)
        assertTrue(
            "a third unconfirmed retry inside the burst window must still be refused",
            third is FanControlDecision.FailSafe,
        )
    }

    @Test
    fun `a reset restores a full budget rather than leaving credit owed`() {
        val controller = SmartFanThermalController()
        var now = 1_000L
        command(controller, now)
        // Burn the burst on an excursion the fan never takes.
        now += 200
        controller.tick(profile, sample(duty = 25_000, now = now), now)
        now += 200
        controller.tick(profile, sample(duty = 25_000, now = now), now)
        now += 200
        assertTrue(
            "precondition: the burst is spent",
            controller.tick(profile, sample(duty = 25_000, now = now), now) is FanControlDecision.FailSafe,
        )

        controller.reset()
        now += 200
        command(controller, now)
        now += 200
        assertTrue(
            "after a reset the controller must be able to repair again immediately",
            controller.tick(profile, sample(duty = 25_000, now = now), now) is FanControlDecision.WriteDuty,
        )
    }

    private fun command(controller: SmartFanThermalController, now: Long): Int {
        val decision = controller.tick(profile, sample(duty = 5_000, now = now), now)
        assertTrue("setup must command a duty", decision is FanControlDecision.WriteDuty)
        return (decision as FanControlDecision.WriteDuty).duty
    }

    private fun sample(duty: Int, now: Long) = FanControlSample(
        cpuSafetyTempC = 70,
        hottestCpuTempC = 70,
        gpuTempC = 70,
        vendorFanMode = TuningPlanner.FAN_SPORT,
        duty = duty,
        period = 50_000,
        rpm = 2_000,
    )
}
