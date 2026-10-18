package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanThermalControlTest {
    private val controller = SmartFanThermalController()

    @Test
    fun `profiles retain approved temperature intent without claiming hard watt enforcement`() {
        val quiet = profile("quiet")
        val balanced = profile("balanced")
        val performance = profile("performance")

        assertEquals(55, quiet.targetTempC)
        assertEquals(8_000, quiet.calibrationPowerReferenceMw)
        assertEquals(PowerEnvelopeIntent.AT_OR_BELOW, quiet.powerEnvelopeIntent)
        assertEquals(65, balanced.targetTempC)
        assertEquals(11_250, balanced.calibrationPowerReferenceMw)
        assertEquals(PowerEnvelopeIntent.AT_OR_BELOW, balanced.powerEnvelopeIntent)
        assertEquals(70, performance.targetTempC)
        assertEquals(14_000, performance.calibrationPowerReferenceMw)
        assertEquals(PowerEnvelopeIntent.ABOVE_REFERENCE, performance.powerEnvelopeIntent)
        assertEquals(0, performance.minSpinPercent)
        assertEquals(35, performance.riseSlewPercentPerSecond)
        assertEquals(8.0, performance.kp, 0.0)
    }

    // forAppliedProfile widened from an exact-Odin gate to every
    // profile id this table defines, regardless of device. Before this, a fan-bearing non-Odin
    // session (the Nova, once wired) got null here and its caller rolled the whole apply back for
    // the mere absence of a table row -- see FanThermalProfiles.forAppliedProfile's own KDoc.
    // Differential: reverting the `.takeIf { deviceProfileId == ODIN_3_PROFILE }` gate back in
    // makes every non-Odin assertion below return null instead.
    @Test
    fun `forAppliedProfile resolves for every device profile, not only the exact Odin id`() {
        listOf(
            "retroid-qcs8550-pserver",
            "ayn-qcs8550-pserver",
            "rp5",
            CpuCeilingTransport.ODIN_3_PROFILE,
            "some-future-device-id",
        ).forEach { deviceProfileId ->
            listOf("quiet", "balanced", "performance").forEach { profileId ->
                assertEquals(
                    "$profileId on $deviceProfileId",
                    profile(profileId),
                    FanThermalProfiles.forAppliedProfile(profileId, deviceProfileId),
                )
            }
            // An unknown profile id still resolves to null on every device -- the table's own
            // membership is the only gate left, not the device.
            assertEquals(
                null,
                FanThermalProfiles.forAppliedProfile("stock", deviceProfileId),
            )
        }
    }

    @Test
    fun `at target is idempotent and rising temperature ramps up from a full stop`() {
        // with the 20% floor retired, "at target" for a HoldTarget
        // profile is now a genuine complete stop (duty 0), not a pinned 20% (duty 10_000).
        val quiet = profile("quiet")
        assertEquals(
            FanControlDecision.Hold,
            controller.tick(quiet, sample(tempC = 55, duty = 0), 1_000),
        )

        controller.reset()
        val rising = controller.tick(quiet, sample(tempC = 60, duty = 0), 1_000)
        assertTrue(rising is FanControlDecision.WriteDuty)
        rising as FanControlDecision.WriteDuty
        // No floor to jump to: the very first write is a single slew step up from zero, not a
        // snap to a bounded minimum.
        assertEquals(1, rising.percent)
        assertEquals(500, rising.duty)
    }

    @Test
    fun `inflated CPU hotspot is ignored while hotter validated GPU drives the SoC fan curve`() {
        val quiet = profile("quiet")
        val gpuLed = controller.tick(
            quiet,
            sample(tempC = 55, hottestCpuC = 100, gpuC = 74, duty = 10_000),
            1_000,
        )
        assertTrue(gpuLed is FanControlDecision.WriteDuty)
        gpuLed as FanControlDecision.WriteDuty
        assertEquals(21, gpuLed.percent)
        assertEquals("FAN_TARGET_TEMP", gpuLed.reason)

        controller.reset()
        assertEquals(
            FanControlDecision.Hold,
            controller.tick(
                quiet,
                sample(tempC = 55, duty = 0).copy(
                    hottestCpuTempC = null,
                    gpuTempC = null,
                ),
                1_000,
            ),
        )
    }

    @Test
    fun `GPU witness commands full fan before the independent supervisor trip`() {
        val decision = controller.tick(
            profile("quiet"),
            sample(tempC = 50, hottestCpuC = 55, gpuC = 85, duty = 0),
            1_000,
        ) as FanControlDecision.WriteDuty

        assertEquals(100, decision.percent)
        assertEquals(50_000, decision.duty)
        assertEquals("FAN_GPU_THERMAL_OVERRIDE", decision.reason)
    }

    @Test
    fun `GPU emergency preempts an older pending duty reconciliation`() {
        val quiet = profile("quiet")
        assertTrue(
            controller.tick(
                quiet,
                sample(tempC = 65, gpuC = 65, duty = 0),
                1_000,
            ) is FanControlDecision.WriteDuty,
        )

        val emergency = controller.tick(
            quiet,
            sample(tempC = 65, gpuC = 85, duty = 0),
            2_000,
        ) as FanControlDecision.WriteDuty
        assertEquals(100, emergency.percent)
        assertEquals(50_000, emergency.duty)
        assertEquals("FAN_GPU_THERMAL_OVERRIDE", emergency.reason)
    }

    @Test
    fun `validated package temperature alone commands the local full fan override`() {
        val decision = controller.tick(
            profile("performance"),
            sample(tempC = 84, hottestCpuC = 55, gpuC = 55, duty = 20_000),
            1_000,
        ) as FanControlDecision.WriteDuty
        assertEquals(100, decision.percent)
        assertEquals(50_000, decision.duty)
        assertEquals("FAN_THERMAL_OVERRIDE", decision.reason)
    }

    @Test
    fun `ownership loss missing temperature sensors and stalled cadence fail closed`() {
        val quiet = profile("quiet")
        assertFail("FAN_OWNERSHIP_LOST") {
            controller.tick(quiet, sample(tempC = 55).copy(vendorFanMode = 4), 1_000)
        }
        // Renamed with the field 2026-08-23: this witness is the hottest classified CPU zone,
        // not the user-facing package mean, and the reason code now says so.
        assertFail("FAN_CPU_SAFETY_TEMP_UNAVAILABLE") {
            controller.tick(quiet, sample(tempC = 55).copy(cpuSafetyTempC = null), 1_000)
        }
        controller.reset()
        controller.tick(quiet, sample(tempC = 55), 1_000)
        assertFail("FAN_CONTROLLER_STALLED") {
            controller.tick(quiet, sample(tempC = 55), 7_001)
        }
    }

    // Controller reset regression: pins the mechanism behind
    // the P4 fan re-entry liveness defect and the reset() semantics that fix it. The controller
    // ITSELF already behaves this way at 809e7ff0 -- this test is expected GREEN on arrival; the
    // defect was that MimirTweakerService's FAN_REASSERTED callback never called reset() after a
    // re-entry, so the stale tick clock below was never cleared on the device path (see the
    // source-text pin in ManualFanMonitorLivenessTest for the red-first half of this fix).
    @Test
    fun `a stall is sticky until reset, and reset lets the very next tick reseed instead of failing`() {
        val quiet = profile("quiet")
        // t=0: seed.
        controller.tick(quiet, sample(tempC = 55, duty = 0), 0)
        // t=1_000: normal cadence, well inside MAX_CONTROL_GAP_MS (6_000).
        assertEquals(
            FanControlDecision.Hold,
            controller.tick(quiet, sample(tempC = 55, duty = 0), 1_000),
        )
        // t=8_000: a 7 s gap since the t=1_000 tick exceeds MAX_CONTROL_GAP_MS -- fails closed.
        assertFail("FAN_CONTROLLER_STALLED") {
            controller.tick(quiet, sample(tempC = 55, duty = 0), 8_000)
        }
        // t=9_000: STICKY -- the t=8_000 tick returned FailSafe BEFORE writing lastTickMs, so
        // this tick still measures its gap from the t=1_000 clock and remains stalled.
        assertFail("FAN_CONTROLLER_STALLED") {
            controller.tick(quiet, sample(tempC = 55, duty = 0), 9_000)
        }
        // reset() then the very next tick: a fresh start reseeds via resetForProfile against
        // ITS OWN nowElapsedRealtimeMs (gap 0), so it must not be a FailSafe of any kind.
        controller.reset()
        val reseeded = controller.tick(quiet, sample(tempC = 55, duty = 0), 10_000)
        assertTrue(
            "expected a live decision (Hold/WriteDuty) after reset(), got $reseeded",
            reseeded !is FanControlDecision.FailSafe,
        )
    }

    // S4.2/S6 test 2 (see TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES): device evidence shows the
    // vendor's duty loop only fights mode SMART(4) -- SPORT(5) and MANUAL(6) both stop it and
    // both persist a direct duty write. The controller must run under either and refuse only
    // SMART (and a missing reading,
    // which fails closed exactly as before). Differential: reverting the FanThermalControl.kt:279
    // guard back to `!= FAN_MANUAL` makes the SPORT(5) assertion below FailSafe instead of Hold.
    @Test
    fun `controller runs under mode 5 and mode 6, refuses only under mode 4 SMART`() {
        val quiet = profile("quiet")
        assertEquals(
            FanControlDecision.Hold,
            controller.tick(
                quiet,
                sample(tempC = 55, duty = 0).copy(vendorFanMode = TuningPlanner.FAN_SPORT),
                1_000,
            ),
        )
        controller.reset()
        assertEquals(
            FanControlDecision.Hold,
            controller.tick(
                quiet,
                sample(tempC = 55, duty = 0).copy(vendorFanMode = TuningPlanner.FAN_MANUAL),
                1_000,
            ),
        )
        controller.reset()
        assertFail("FAN_OWNERSHIP_LOST") {
            controller.tick(
                quiet,
                sample(tempC = 55, duty = 0).copy(vendorFanMode = TuningPlanner.FAN_SMART),
                1_000,
            )
        }
        assertFail("FAN_OWNERSHIP_LOST") {
            controller.tick(
                quiet,
                sample(tempC = 55, duty = 0).copy(vendorFanMode = null),
                1_000,
            )
        }
    }

    @Test
    fun `unreliable runtime tach does not override confirmed PWM and thermal truth`() {
        val quiet = profile("quiet")
        assertEquals(
            FanControlDecision.Hold,
            controller.tick(quiet, sample(tempC = 55, duty = 0).copy(rpm = 0), 1_000),
        )
        assertEquals(
            FanControlDecision.Hold,
            controller.tick(quiet, sample(tempC = 55, duty = 0).copy(rpm = null), 2_000),
        )
    }

    // this test used to be
    // named "two consecutive duty readback mismatches fail closed" and pinned a PERMANENT latch
    // (FAN_DUTY_READBACK_MISMATCH, never cleared short of controller.reset()) as deliberate,
    // tested behavior. That was a real defect on hardware: an app-switch-triggered vendor duty
    // write tripped this latch and the fan stayed pinned at an elevated duty for minutes with no
    // switching happening at all . Mimir must retain fan authority through sustained contention. This test now pins the REPLACEMENT behavior: a rolling reassert-write budget
    // (see [SmartFanThermalController.admitDutyReassert]) that bounds how often Mimir fights a
    // disagreeing readback -- so a fast/persistent contest degrades to a temporary, named backoff
    // instead of audibly rewriting the PWM node every tick forever -- but the backoff is NEVER
    // terminal: continued in the next test, where the very same drift resumes on its own once the
    // burst window rolls forward, with no call to reset().
    @Test
    fun `a duty readback mismatch backs off after its burst budget, never latching permanently`() {
        val quiet = profile("quiet")
        // t=1000: the initial control-loop write (curve target 65 C vs quiet's 55 C target),
        // unrelated to the reassert budget -- it never touches [reassertAttempts].
        assertTrue(controller.tick(quiet, sample(tempC = 65, duty = 0), 1_000) is FanControlDecision.WriteDuty)
        // t=2000, t=3000: the vendor readback still disagrees with what Mimir just wrote. Both
        // are admitted reassert writes -- burst budget allows 2 attempts per 5 s window, and this
        // is the 1st and 2nd.
        val reassert1 = controller.tick(quiet, sample(tempC = 65, duty = 0), 2_000)
        assertEquals("FAN_DUTY_REASSERT", (reassert1 as FanControlDecision.WriteDuty).reason)
        val reassert2 = controller.tick(quiet, sample(tempC = 65, duty = 0), 3_000)
        assertEquals("FAN_DUTY_REASSERT", (reassert2 as FanControlDecision.WriteDuty).reason)
        // t=4000: a 3rd reassert attempt within the same 5 s burst window exceeds the budget
        // (max 2 per burst). This is the bound from design constraint #2 ("do not thrash") --
        // Mimir stops rewriting the node every tick against a fast, active second writer.
        assertFail("FAN_DUTY_REASSERT_BACKOFF") {
            controller.tick(quiet, sample(tempC = 65, duty = 0), 4_000)
        }
        // t=5000: still inside the 5 s burst window opened by the t=2000/t=3000 attempts, so the
        // backoff holds here too -- this is not a one-tick blip, it is a genuine bounded window.
        assertFail("FAN_DUTY_REASSERT_BACKOFF") {
            controller.tick(quiet, sample(tempC = 65, duty = 0), 5_000)
        }
    }

    @Test
    fun `vendor duty drift resumes reasserting on its own once the burst window rolls forward, no reset() needed`() {
        val quiet = profile("quiet")
        assertEquals(
            FanControlDecision.Hold,
            controller.tick(quiet, sample(tempC = 55, duty = 0), 1_000),
        )

        val drift = controller.tick(quiet, sample(tempC = 55, duty = 25_000), 2_000)
        assertTrue(drift is FanControlDecision.WriteDuty)
        drift as FanControlDecision.WriteDuty
        // the controller reasserts its own expected duty (now 0, not
        // the retired 20% floor) against the drifted vendor readback.
        assertEquals(0, drift.duty)
        assertEquals("FAN_DUTY_REASSERT", drift.reason)

        val secondAttempt = controller.tick(quiet, sample(tempC = 55, duty = 25_000), 3_000)
        assertEquals("FAN_DUTY_REASSERT", (secondAttempt as FanControlDecision.WriteDuty).reason)

        // t=4000: burst budget (2 per 5 s) is now spent -- backoff, not a permanent latch.
        assertFail("FAN_DUTY_REASSERT_BACKOFF") {
            controller.tick(quiet, sample(tempC = 55, duty = 25_000), 4_000)
        }

        // t=7001: more than 5 s after the t=2000 attempt, so it has aged out of the burst window
        // and there is budget again -- THE PROOF this is not a one-way door. Nothing here calls
        // controller.reset(); the drift is still live (duty is still 25_000) and Mimir tries
        // again on its own.
        val resumed = controller.tick(quiet, sample(tempC = 55, duty = 25_000), 7_001)
        assertEquals("FAN_DUTY_REASSERT", (resumed as FanControlDecision.WriteDuty).reason)
        assertEquals(0, resumed.duty)
    }

    @Test
    fun `thermal emergency override still forces full fan while a duty backoff is active`() {
        // Design constraint from the O-6 brief: a design that holds the fan LOW against a real
        // thermal event is unacceptable. safetyFull (package/GPU emergency) is computed and acted
        // on before this class's reassert-budget bookkeeping is ever consulted, so it must fire
        // identically whether the controller is mid-backoff or not.
        val quiet = profile("quiet")
        assertTrue(controller.tick(quiet, sample(tempC = 65, duty = 0), 1_000) is FanControlDecision.WriteDuty)
        controller.tick(quiet, sample(tempC = 65, duty = 0), 2_000)
        controller.tick(quiet, sample(tempC = 65, duty = 0), 3_000)
        // t=4000: drive the controller into backoff (same sequence as the test above).
        assertFail("FAN_DUTY_REASSERT_BACKOFF") {
            controller.tick(quiet, sample(tempC = 65, duty = 0), 4_000)
        }
        // t=5000: a genuine GPU thermal witness arrives while still backed off. The override must
        // still win -- full fan, unconditionally, ignoring the reassert budget entirely.
        val emergency = controller.tick(
            quiet,
            sample(tempC = 65, gpuC = 85, duty = 0),
            5_000,
        ) as FanControlDecision.WriteDuty
        assertEquals(100, emergency.percent)
        assertEquals(50_000, emergency.duty)
        assertEquals("FAN_GPU_THERMAL_OVERRIDE", emergency.reason)
    }

    @Test
    fun `auto curve interpolates bias and bounded draggable knees monotonically`() {
        val curve = FanCurveDefaults.SMART_BASELINE
        val neutral = curve.percentAt(62.0)
        val cooler = curve.percentAt(62.0, FanCurveBias(-100))
        val quieter = curve.percentAt(62.0, FanCurveBias(100))

        assertTrue(cooler > neutral)
        assertTrue(neutral > quieter)
        val moved = curve.movedKnee(index = 3, tempC = 200, percent = -1)
        assertEquals(curve.knees[4].tempC - 1, moved.knees[3].tempC)
        assertEquals(curve.knees[2].percent, moved.knees[3].percent)
        assertTrue(moved.knees.zipWithNext().all { (left, right) ->
            left.tempC < right.tempC && left.percent <= right.percent
        })
    }

    @Test
    fun `low-temp ramp is zero at 20 C and lands the configured 10-15 percent band by 30-33 C`() {
        // continuous gentle airflow from 20 C, reaching 10-15% by 30-33 C
        // to normalize temperature delta and reach thermal balance point earlier.
        val curve = FanCurveDefaults.SMART_BASELINE
        assertEquals(0, curve.percentAt(20.0))
        val at30 = curve.percentAt(30.0)
        val at33 = curve.percentAt(33.0)
        assertTrue("30 C should land in the 10-15% band, was $at30", at30 in 10..15)
        assertTrue("33 C should land in the 10-15% band, was $at33", at33 in 10..15)
    }

    @Test
    fun `low-temp ramp lands the configured 30-35 percent band by 48 C`() {
        val curve = FanCurveDefaults.SMART_BASELINE
        val at48 = curve.percentAt(48.0)
        assertTrue("48 C should land in the 30-35% band, was $at48", at48 in 30..35)
    }

    @Test
    fun `low-temp ramp is continuous -- every degree above 20 C is already non-zero, not a step`() {
        val curve = FanCurveDefaults.SMART_BASELINE
        assertEquals(0, curve.percentAt(20.0))
        (21..29).forEach { tempC ->
            val percent = curve.percentAt(tempC.toDouble())
            assertTrue("expected a non-zero ramp at $tempC C, was $percent", percent > 0)
        }
    }

    @Test
    fun `validated mid-to-high curve knees are unchanged by the 2026-08-08 low-temp retune`() {
        val curve = FanCurveDefaults.SMART_BASELINE
        assertEquals(38, curve.percentAt(62.0))
        assertEquals(60, curve.percentAt(72.0))
        assertEquals(80, curve.percentAt(79.0))
    }

    @Test
    fun `auto curve controller remains slewed and package override outranks curve`() {
        val quiet = profile("quiet")
        val strategy = FanThermalStrategy.AutoCurve()
        val first = controller.tick(quiet, sample(tempC = 72, duty = 0), 1_000, strategy)
        assertTrue(first is FanControlDecision.WriteDuty)
        // with no floor to snap to, the very first write off a full
        // stop is a single slewed step toward the curve's 60% target at 72 C, not a jump to a
        // bounded minimum.
        assertEquals(
            1,
            (first as FanControlDecision.WriteDuty).percent,
        )
        controller.reset()
        val override = controller.tick(
            quiet,
            sample(tempC = 84, hottestCpuC = 50, gpuC = 50, duty = 10_000),
            1_000,
            strategy,
        )
        assertEquals(100, (override as FanControlDecision.WriteDuty).percent)
    }

    @Test
    fun `authored curve follows GPU while package temperature remains emergency only`() {
        val quiet = profile("quiet")
        val strategy = FanThermalStrategy.AutoCurve()
        // GPU at 15 C is below the curve's lowest legal knee (20 C, 0%), so the curve still
        // reports a genuine 0% here (only added a ramp starting at
        // 20 C -- it did not touch the flat-zero region at/below 20 C). Package at 72 C would
        // demand a much higher percent if it were the curve's authority; Hold below proves it
        // is not.
        val gpuLed = controller.tick(
            quiet,
            sample(tempC = 72, gpuC = 15, duty = 0),
            1_000,
            strategy,
        )
        assertEquals(FanControlDecision.Hold, gpuLed)

        controller.reset()
        assertFail("FAN_GPU_TEMP_UNAVAILABLE") {
            controller.tick(
                quiet,
                sample(tempC = 55, duty = 10_000).copy(gpuTempC = null),
                1_000,
                strategy,
            )
        }
    }

    // WAVE1D: WAVE1C (94a6d574) moved SupervisorPolicy.gpuTripCExclusive 85 -> 95 without
    // touching this file, silently widening GPU_WITNESS_FULL_FAN_C's documented "ten degrees
    // below the supervisor's exclusive GPU trip" margin from 10 C to 20 C. This test pins the
    // RELATIONSHIP -- not the bare constant -- so the next trip recalibration fails loudly here
    // instead of drifting unnoticed again.
    @Test
    fun `GPU witness full fan threshold stays at least 10 C below the supervisor's exclusive GPU trip`() {
        // The property this guards is MARGIN, not a fixed offset: the fan must reach full before
        // the supervisor trips, with at least 10 C of headroom. It was written as exact equality when
        // both values happened to move together (trip 95 / witness 85).
        //
        // WAVE 2 raises gpuTripCExclusive to 105. The witness stays at 85 --  an ABSOLUTE temperature at which the fan
        // should go full, not an offset from the trip. That widens the margin to 20 C, which is
        // strictly more conservative: the fan commits earlier relative to the trip, never later.
        // Exact equality would have forced the witness up to 95 and silently made the fan LAZIER
        // than the fixed emergency threshold, so the assertion is relaxed to the real invariant.
        val gpuTripCExclusive = SupervisorPolicy().gpuTripCExclusive
        assertTrue(
            "fan must reach full at least 10 C before the supervisor's GPU trip",
            SmartFanThermalController.GPU_WITNESS_FULL_FAN_C <= gpuTripCExclusive - 10,
        )
    }

    @Test
    fun `a loop held at the floor or at full fan keeps no extra history and leaves on the first step after the error reverses`() {
        // Performance: target 70 C, rise 35 %/s, fall 12 %/s. One tick per second, with each written
        // duty fed straight back as the fan's readback.
        val performance = profile("performance")
        var duty = 0
        var nowMs = 1_000L
        fun step(tempC: Int): FanControlDecision {
            val decision = controller.tick(performance, sample(tempC = tempC, duty = duty), nowMs)
            nowMs += 1_000L
            if (decision is FanControlDecision.WriteDuty) duty = decision.duty
            return decision
        }

        // Floor: 2 C under the target is the edge of the band where a stopped fan still takes part
        // in the loop, and the error keeps pushing the output down into the floor.
        repeat(20) { assertEquals(FanControlDecision.Hold, step(68)) }
        assertEquals(0, duty)
        // A 1 C blip upward (0.35 C once filtered) must lift the fan on that very step; if the
        // twenty held ticks had stored anything below the floor it would stay stopped.
        val offTheFloor = step(69)
        assertTrue(offTheFloor is FanControlDecision.WriteDuty)
        assertTrue((offTheFloor as FanControlDecision.WriteDuty).percent > 0)

        // Full fan: 10 C over the target (below every emergency trip) drives the output to 100%
        // and the error keeps pushing it further into that limit.
        repeat(25) { step(80) }
        assertEquals(50_000, duty)
        repeat(3) { assertEquals(FanControlDecision.Hold, step(80)) }
        // Now the error reverses (3 C of cooling, about 1 C once filtered). Anything accumulated
        // past 100% during the hold would keep the fan pinned; with none, it backs off at once.
        val offTheCeiling = step(77)
        assertTrue(offTheCeiling is FanControlDecision.WriteDuty)
        assertTrue((offTheCeiling as FanControlDecision.WriteDuty).percent < 100)
    }

    // the velocity-form loop has no stored integral to
    // bound, so a sustained error drives even the quiet profile all the way to full fan. The earlier
    // loop's bound held quiet and balanced near 65%. Do not reinstate a cap to make this test pass.
    @Test
    fun `sustained error on quiet reaches full fan`() {
        val quiet = profile("quiet") // target 55 C
        var duty = 0
        var nowMs = 1_000L
        var highestPercent = 0
        // 5 C over the target, held for 300 s of one-second ticks, with the written duty fed back.
        repeat(300) {
            val decision = controller.tick(quiet, sample(tempC = 60, duty = duty), nowMs)
            nowMs += 1_000L
            if (decision is FanControlDecision.WriteDuty) {
                duty = decision.duty
                highestPercent = maxOf(highestPercent, decision.percent)
            }
        }
        assertEquals(100, highestPercent)
        assertEquals(50_000, duty)
    }

    @Test
    fun `auto calibration learns first repeatable fan range only from complete sweep`() {
        val sweep = FanAutoCalibration.SWEEP_PERCENTAGES.map { percent ->
            FanCalibrationObservation(
                percent = percent,
                rpm = if (percent < 20) 0 else percent * 50,
            )
        }
        assertEquals(LearnedFanRange(20, 5_000), FanAutoCalibration.learn(sweep))
        assertEquals(null, FanAutoCalibration.learn(sweep.dropLast(1)))
        assertEquals(
            null,
            FanAutoCalibration.learn(sweep.map { it.copy(rpm = 0) }),
        )
    }

    private fun profile(id: String) = requireNotNull(
        FanThermalProfiles.forAppliedProfile(id, CpuCeilingTransport.ODIN_3_PROFILE),
    )

    private fun sample(
        tempC: Int,
        hottestCpuC: Int = tempC,
        gpuC: Int = tempC,
        duty: Int = 10_000,
    ) = FanControlSample(
        cpuSafetyTempC = tempC,
        hottestCpuTempC = hottestCpuC,
        gpuTempC = gpuC,
        vendorFanMode = TuningPlanner.FAN_MANUAL,
        duty = duty,
        period = 50_000,
        rpm = 2_000,
    )

    private fun assertFail(reason: String, block: () -> FanControlDecision) {
        val decision = block()
        assertTrue(decision is FanControlDecision.FailSafe)
        assertEquals(reason, (decision as FanControlDecision.FailSafe).reason)
    }
}
