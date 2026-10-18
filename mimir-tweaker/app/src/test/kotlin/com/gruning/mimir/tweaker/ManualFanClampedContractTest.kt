package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual-fan regression, 2026-08-25: the CPU floor clamp had not been applied to the
 * fan gate.
 *
 * The plan that gets JOURNALLED is not the plan the planner derives: `TransactionCoordinator`
 * rewrites `cpuMaxKhz` from `capture.admittedCpuMaxKhz` before anything is written, clamping any
 * target below its policy's live floor UP to that floor. The manual-fan gate compared a pre-clamp re-derivation to that post-clamp
 * contract with `==`, so on any chassis whose live floor sits above a derived ceiling it was
 * permanently false.
 *
 * Every number below is device truth read off the Retroid Pocket Nova on 2026-08-25: the
 * three real OPP tables, the real `cpuinfo_max_freq` values, and the two vectors the instrumented
 * daemon printed in one line — `cpuPlan={0=1017600, 3=1286400, 7=1478400}` against
 * `cpuContract={0=1017600, 3=1651200, 7=1843200}`. The first test derives the plan half rather than
 * asserting it, so this stays a test of the pipeline and not of a copied constant.
 */
class ManualFanClampedContractTest {

    @Test
    fun `the planner really does derive the pre-clamp vector the device printed`() {
        val derived = requireNotNull(
            TuningPlanner().plan("quiet", "example.game", novaCapabilities()),
        )

        assertEquals(
            "if this moves, the fixture below stops describing the device and the whole file " +
                "becomes a test of its own constants",
            mapOf(0 to 1_017_600, 3 to 1_286_400, 7 to 1_478_400),
            derived.cpuMaxKhz,
        )
    }

    @Test
    fun `a floor-clamped contract is the same envelope and must be admitted`() {
        val nova = novaCapabilities()

        assertTrue(
            "policies 3 and 7 were clamped up to the Nova's live floors at capture; that IS the " +
                "applied envelope, and manual fan asks to change the fan axis only",
            manualFanHandoffPlanAdmitted(manualPlan(nova), novaClampedContract(), nova),
        )
    }

    @Test
    fun `the pre-change equality refuses exactly the contract this fix admits`() {
        val nova = novaCapabilities()
        val manual = manualPlan(nova)

        assertFalse(
            "REPLAY: `derivedManualPlan.cpuMaxKhz == contract.cpuQosCeilingKhz` -- the expression " +
                "that stood until 2026-08-25 -- must be false here, or this fixture does not " +
                "reproduce what the Nova printed",
            manual.cpuMaxKhz == novaClampedContract().cpuQosCeilingKhz,
        )
        assertTrue(manualFanPlanCpuMatchesContract(manual, novaClampedContract(), nova))
    }

    @Test
    fun `the relation still refuses a contract that is not this envelope`() {
        val nova = novaCapabilities()
        val manual = manualPlan(nova)
        val clamped = novaClampedContract()

        assertFalse(
            "a contract BELOW the current derivation is stale or another profile's -- the clamp " +
                "only ever raises",
            manualFanPlanCpuMatchesContract(
                manual,
                clamped.copy(cpuQosCeilingKhz = clamped.cpuQosCeilingKhz + (7 to 998_400)),
                nova,
            ),
        )
        assertFalse(
            "a ceiling above the policy's own stock maximum is a raise, never a clamp",
            manualFanPlanCpuMatchesContract(
                manual,
                clamped.copy(cpuQosCeilingKhz = clamped.cpuQosCeilingKhz + (7 to 3_264_000)),
                nova,
            ),
        )
        assertFalse(
            "a contract over a topology this device no longer has must refuse on the key set",
            manualFanPlanCpuMatchesContract(
                manual,
                clamped.copy(cpuQosCeilingKhz = sortedMapOf(0 to 1_017_600, 4 to 1_651_200, 7 to 1_843_200)),
                nova,
            ),
        )
        assertFalse(
            "an empty derivation is not an envelope",
            manualFanPlanCpuMatchesContract(manual.copy(cpuMaxKhz = emptyMap()), clamped, nova),
        )
    }

    @Test
    fun `an unclamped contract is still admitted by plain equality`() {
        val nova = novaCapabilities()
        val manual = manualPlan(nova)
        val unclamped = novaClampedContract().copy(
            cpuQosCeilingKhz = manual.cpuMaxKhz.toSortedMap(),
        )

        assertTrue(
            "the Odin's shape -- live floors below every derived target, so nothing is clamped -- " +
                "must be untouched by this fix",
            manualFanPlanCpuMatchesContract(manual, unclamped, nova),
        )
    }

    // ---------------------------------------------------------------- fixtures

    private fun manualPlan(nova: HardwareCapabilities): TuningPlan = requireNotNull(
        TuningPlanner().plan(
            profileId = "quiet",
            packageName = "example.game",
            capabilities = nova,
            fanIntent = TuningPlanner.FanIntent.MANUAL_OVERRIDE,
        ),
    )

    /**
     * The journalled vector, verbatim from the device: policy 0 unclamped (its live floor 902400
     * sits below the derived 1017600), policies 3 and 7 clamped up to live floors of 1651200 and
     * 1843200. `CpuWindowAdmissionPolicy.targetAdmission`'s own KDoc records the same two numbers
     * as this chassis's battery-state floors.
     */
    private fun novaClampedContract() = AppliedTargetContract(
        deviceProfile = "retroid-qcs8550-pserver",
        bootId = "boot",
        cpuQosCeilingKhz = sortedMapOf(0 to 1_017_600, 3 to 1_651_200, 7 to 1_843_200),
        cpuQosTransport = CpuQosTransport.SHARED_QOS,
        gpuMinPowerLevel = null,
        gpuMaxPowerLevel = null,
        gpuDevfreqMaxHz = requireNotNull(
            GpuCeilingTransport.targetHzFor(
                novaCapabilities().gpuFrequenciesKhz,
                requireNotNull(
                    TuningPlanner().plan("quiet", "example.game", novaCapabilities())?.gpuMaxPowerLevel,
                ),
            ),
        ).toInt(),
        fanMode = TuningPlanner.FAN_SMART,
        profileId = "quiet",
    )

    /** Read off the device 2026-08-25: three policies (0/3/7), their real OPP tables and maxima. */
    private fun novaCapabilities() = HardwareCapabilities(
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
                listOf(
                    307_200, 441_600, 556_800, 672_000, 787_200, 902_400, 1_017_600, 1_113_600,
                    1_228_800, 1_344_000, 1_459_200, 1_555_200, 1_670_400, 1_785_600, 1_900_800,
                    2_016_000,
                ),
                307_200, 2_016_000, 1_017_600,
            ),
            CpuPolicy(
                3, "/sys/devices/system/cpu/cpufreq/policy3", listOf(3, 4, 5, 6),
                listOf(
                    499_200, 614_400, 729_600, 844_800, 940_800, 1_056_000, 1_171_200, 1_286_400,
                    1_401_600, 1_536_000, 1_651_200, 1_785_600, 1_920_000, 2_054_400, 2_188_800,
                    2_323_200, 2_457_600, 2_592_000, 2_707_200,
                ),
                499_200, 2_803_200, 1_651_200,
            ),
            CpuPolicy(
                7, "/sys/devices/system/cpu/cpufreq/policy7", listOf(7),
                listOf(
                    595_200, 729_600, 864_000, 998_400, 1_132_800, 1_248_000, 1_363_200, 1_478_400,
                    1_593_600, 1_708_800, 1_843_200, 1_977_600, 2_092_800, 2_227_200, 2_342_400,
                    2_476_800, 2_592_000, 2_726_400, 2_841_600, 2_956_800,
                ),
                595_200, 3_187_200, 1_843_200,
            ),
        ),
        gpuFrequenciesKhz = listOf(
            124_800, 220_000, 295_000, 348_000, 401_000, 475_000, 550_000, 615_000, 680_000,
        ),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60, 120),
        notes = emptyList(),
    )
}
