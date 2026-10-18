package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Retroid Pocket Nova could never apply a profile. Every attempt died at
 * `CPU_TARGET_BELOW_LIVE_FLOOR`, and the reason was a genuine derivation defect, not a tuning
 * table that wanted different numbers:
 *
 * > `MimirCpuWindowAdmission: profile=retroid-qcs8550-pserver code=CPU_TARGET_BELOW_LIVE_FLOOR`
 * > `cpu policy 3 target 1401600 is below settled live floor 1651200`
 *
 * `CpuPolicy.minKhz` is `cpuinfo_min_freq` -- the immutable hardware floor -- and it is what
 * [TuningPlanner.liveOpps] filtered by. Apply-time admission compares against live
 * `scaling_min_freq` instead. On a chassis where the vendor holds that floor high, the planner
 * could pick an OPP that genuinely exists in the table and that the device will nevertheless refuse
 * to accept as a ceiling. Admission was right to refuse: a ceiling below the floor is not a
 * writable state. The plan was wrong to ask.
 *
 * **Device provenance for every number below.** Read off the Nova (`test device`) on
 * 2026-08-23: the policy tables via `scaling_available_frequencies`, and the floor from the daemon's
 * own refusal, which reproduced identically over five hours **and at both `performance_mode` 0 and
 * 1**. That last check is what makes the floor persistent chassis truth rather than a transient
 * boost, and therefore something derivation may legitimately be taught to respect.
 *
 * Every test states its fail-closed twin, because the repair's whole risk is the other direction:
 * a floor that is wrong or unknown must never silently raise a ceiling.
 */
class NovaCpuFloorRepairTest {

    /** The measured floor: policy 3's live `scaling_min_freq` on the device. */
    private val novaPolicy3Floor = 1_651_200

    @Test
    fun `the value that was refused on device is exactly what the planner derives without the floor`() {
        // If this ever stops being 1401600, the rest of this file is testing a defect that no
        // longer exists in the form the device reported -- so it is asserted first, literally.
        val derived = TuningPlanner().plan("balanced", PKG, novaCapabilities())
        assertNotNull(derived)
        assertEquals(
            "the pre-repair derivation must still reproduce the exact target the Nova refused",
            1_401_600,
            derived!!.cpuMaxKhz.getValue(3),
        )
        assertTrue(
            "and that value must be genuinely below the measured live floor, or this file's " +
                "premise is wrong",
            derived.cpuMaxKhz.getValue(3) < novaPolicy3Floor,
        )
    }

    @Test
    fun `the settled floor does not steer derivation, and that withdrawal is deliberate`() {
        // WITHDRAWN 2026-08-23 after device measurements. The repair worked
        // -- the Nova applied `balanced` for the first time, policy3 written at 1651200, zero
        // admission refusals -- and then the device showed the rest of the story: it does not HOLD a
        // Mimir ceiling. Every policy reverted to its stock maximum within seconds, the supervisor
        // logged TARGET_CONTRACT_DRIFT_CPU, and the bridge re-applied: 21 restores in four minutes,
        // each re-arming the Guardian foreground service. The observed symptoms were the fan row
        // vanishing, brightness and joystick colour failing, and profile changes reporting busy or
        // denied. The Odin, same code, logged zero drift events.
        //
        // So the flag is false and the derived plan is byte-identical to what it was before the
        // repair. Refusing is stable and leaves every other control working; applying is not.
        assertFalse(
            "re-arming this is not a config tweak -- read the constant's KDoc first",
            TuningPlanner.CPU_SETTLED_FLOOR_STEERS_DERIVATION,
        )
        val withFloor = TuningPlanner().plan(
            "balanced",
            PKG,
            novaCapabilities(settledFloors = mapOf(3 to novaPolicy3Floor)),
        )
        val withoutFloor = TuningPlanner().plan("balanced", PKG, novaCapabilities())
        assertEquals(
            "while withdrawn, a known floor must change nothing at all",
            withoutFloor,
            withFloor,
        )
        assertEquals(1_401_600, requireNotNull(withFloor).cpuMaxKhz.getValue(3))
    }

    @Test
    fun `the premise the repair rested on was falsified by the device`() {
        // Recorded as a test rather than only a comment, because the failure was in the MEASUREMENT,
        // not the code, and that is the part a future session will be tempted to repeat.
        //
        // The floor was read at 1651200: stable across five hours, identical at performance_mode 0
        // and 1. Every one of those readings was taken with the device IDLE AT STOCK. Under a live
        // Mimir session the derived vector moved to {3=1920000}, so the floor moves precisely when
        // it matters. A measurement that never varied the condition the value depends on is not a
        // measurement of that value.
        //
        // Both numbers are real OPPs on this chassis, which is why nothing failed closed and why the
        // churn looked like a working feature for the first few minutes.
        val policy3 = novaCapabilities().cpuPolicies.single { it.id == 3 }
        listOf(1_651_200, 1_920_000).forEach { observedFloor ->
            assertTrue(
                "floor $observedFloor was observed on device and must be a real OPP",
                observedFloor in policy3.availableFrequenciesKhz,
            )
        }
        // The assertion that used to close this test was `assertTrue(1_651_200 != 1_920_000)` --
        // two integer literals, constant-folded true regardless of any code, fixture or device
        // value, and it was the ONLY recorded artefact of the falsification. Deleted rather than
        // rewritten: the two membership assertions above are real (they read the fixture's own OPP
        // table), and a test should not carry a line that cannot participate in its own verdict.
    }

    @Test
    fun `an unknown floor changes nothing at all`() {
        // Fail-closed twin #1. Omission means "not settled", and the old behaviour must stand
        // exactly -- an unreadable floor must never become a guessed one.
        val withoutFloor = TuningPlanner().plan("balanced", PKG, novaCapabilities())
        val emptyMapFloor = TuningPlanner().plan(
            "balanced",
            PKG,
            novaCapabilities(settledFloors = emptyMap()),
        )
        assertEquals(withoutFloor, emptyMapFloor)
    }

    @Test
    fun `a floor at or below the hardware floor is byte-identical to no floor at all`() {
        // Fail-closed twin #2, and the no-regression proof for every device that already worked:
        // the Odin's live floor has never been observed above its hardware floor, so its plans must
        // come out of this change unchanged.
        val policy3 = novaCapabilities().cpuPolicies.single { it.id == 3 }
        val baseline = TuningPlanner().plan("balanced", PKG, novaCapabilities())
        listOf(policy3.minKhz, policy3.minKhz - 1, 1).forEach { floor ->
            assertEquals(
                "a floor of $floor is at or below the hardware floor and must not move the plan",
                baseline,
                TuningPlanner().plan("balanced", PKG, novaCapabilities(settledFloors = mapOf(3 to floor))),
            )
        }
    }

    @Test
    fun `derivation stays pure, because eight call sites compare a fresh plan against a stored one`() {
        // admitsPlan, manualFanControlAvailable, manualFanHandoffPlanAdmitted, CertificationStore,
        // CalibrationModels, ProductQualificationRuntime and the supervisor all compare a FRESH
        // derivation against a stored vector. If the floor made planning non-reproducible, the
        // applied contract would stop matching its own re-derivation and manual fan would break on
        // the Nova -- the very device this repair exists for -- the day its first profile applied.
        val caps = novaCapabilities(settledFloors = mapOf(3 to novaPolicy3Floor))
        val first = TuningPlanner().plan("balanced", PKG, caps)
        val second = TuningPlanner().plan("balanced", PKG, caps)
        assertEquals(first, second)

        // ...and the manual-fan equality specifically, which is the one that would fail silently.
        val manual = requireNotNull(
            TuningPlanner().plan("balanced", PKG, caps, TuningPlanner.FanIntent.MANUAL_OVERRIDE),
        )
        assertEquals(
            "the manual derivation must still match the automatic one on CPU",
            requireNotNull(first).cpuMaxKhz,
            manual.cpuMaxKhz,
        )
        assertEquals(
            "and on GPU -- manual fan changes the fan axis and nothing else",
            first.gpuMaxPowerLevel,
            manual.gpuMaxPowerLevel,
        )
    }

    @Test
    fun `the withdrawn floor mechanism still has coverage in both positions`() {
        // The withdrawal deleted the tests that exercised the steering branch, which left a
        // deliberately re-armable mechanism with zero coverage -- flipping the flag would have
        // meant flipping into untested code. A const cannot be varied from a test, so the decision
        // is pinned as a pure function of its inputs instead.
        val planner = TuningPlanner()

        // OFF (today): the settled floor is ignored entirely.
        assertEquals(
            499_200,
            planner.effectiveCpuFloorKhz(policyMinKhz = 499_200, settledFloorKhz = 1_920_000, steers = false),
        )

        // ON (re-armed): the higher of the two bounds, never the lower -- it can only remove OPPs
        // from consideration, so a device whose live floor sits at or below its hardware floor is
        // unaffected.
        assertEquals(
            1_920_000,
            planner.effectiveCpuFloorKhz(policyMinKhz = 499_200, settledFloorKhz = 1_920_000, steers = true),
        )
        assertEquals(
            499_200,
            planner.effectiveCpuFloorKhz(policyMinKhz = 499_200, settledFloorKhz = 400_000, steers = true),
        )
        assertEquals(
            "an unknown floor must behave exactly as OFF does",
            499_200,
            planner.effectiveCpuFloorKhz(policyMinKhz = 499_200, settledFloorKhz = null, steers = true),
        )
    }

    private companion object {
        const val PKG = "example.game"
    }

    /**
     * The Nova, as measured. `minKhz` stands in for `cpuinfo_min_freq`, which the adb shell
     * cannot read on this device (SELinux) -- the production reader falls back to the first entry of
     * the OPP table in exactly that case, so the fixture uses the same fallback rather than
     * inventing a number nobody observed.
     */
    private fun novaCapabilities(settledFloors: Map<Int, Int> = emptyMap()) = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "Moorechip",
        model = "Retroid Pocket Nova",
        socModel = "QCS8550",
        buildFingerprint = "kalama/test",
        deviceProfileId = "retroid-qcs8550-pserver",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(
                0, "/sys/devices/system/cpu/cpufreq/policy0", listOf(0, 1, 2),
                listOf(307_200, 441_600, 556_800, 672_000, 787_200, 902_400, 1_017_600, 1_113_600,
                    1_228_800, 1_344_000, 1_459_200, 1_555_200, 1_670_400, 1_785_600, 1_900_800, 2_016_000),
                307_200, 2_016_000, 2_016_000,
            ),
            CpuPolicy(
                3, "/sys/devices/system/cpu/cpufreq/policy3", listOf(3, 4, 5, 6),
                listOf(499_200, 614_400, 729_600, 844_800, 940_800, 1_056_000, 1_171_200, 1_286_400,
                    1_401_600, 1_536_000, 1_651_200, 1_785_600, 1_920_000, 2_054_400, 2_188_800,
                    2_323_200, 2_457_600, 2_592_000, 2_707_200),
                499_200, 2_803_200, 2_803_200,
            ),
            CpuPolicy(
                7, "/sys/devices/system/cpu/cpufreq/policy7", listOf(7),
                listOf(595_200, 729_600, 864_000, 998_400, 1_132_800, 1_248_000, 1_363_200, 1_478_400,
                    1_593_600, 1_708_800, 1_843_200, 1_977_600, 2_092_800, 2_227_200, 2_342_400,
                    2_476_800, 2_592_000, 2_726_400, 2_841_600, 2_956_800),
                595_200, 3_187_200, 3_187_200,
            ),
        ),
        gpuFrequenciesKhz = listOf(305_000, 525_000, 719_000, 818_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
        cpuSettledFloorsKhz = settledFloors,
    )
}
