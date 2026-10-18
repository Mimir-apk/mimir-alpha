package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual-fan regression, 2026-08-25: devfreq GPU transport was not handled by the fan gate.
 *
 * Nova showed the fan section as unavailable while the daemon simultaneously reported `fan=true axes=[CPU, GPU, FAN]`. The
 * daemon was right about the hardware and still refused the section, because the GPU ceiling had
 * grown a second transport underneath the fan gate: `GpuCeilingTransport` writes
 * `devfreq/max_freq` in HERTZ instead of `max_pwrlevel` on any chassis that exposes it, and
 * [AppliedTargetContract.fromAuthenticatedAppliedRecord] fills `gpuMaxPowerLevel` ONLY from a
 * `gpu:max_pwrlevel` projection. So a devfreq-applied contract carries a null index, both manual-fan
 * gates compared a live index against that null, and the row rendered UNAVAILABLE.
 *
 * The first test below pins the PREMISE (the null index) rather than only the fix, because that is
 * the fact a future transport change would break again. The last one is the differential replay
 * check: it asserts the pre-change comparison is FALSE on
 * exactly the fixture the new predicate admits, so reverting to an index comparison reddens here
 * instead of shipping.
 */
class ManualFanDevfreqCeilingTest {

    @Test
    fun `a devfreq-applied contract carries hertz and no power-level index`() {
        val contract = requireNotNull(
            AppliedTargetContract.fromAuthenticatedAppliedRecord(devfreqAppliedRecord()),
        )

        assertNull(
            "the devfreq transport writes no max_pwrlevel, so the index must stay absent -- " +
                "populating it here would re-arm the drift comparator against a node the vendor " +
                "stack reasserts, which is the restore storm devfreq was introduced to end",
            contract.gpuMaxPowerLevel,
        )
        assertEquals(DEVFREQ_CEILING_HZ, contract.gpuDevfreqMaxHz)
    }

    @Test
    fun `the manual handoff admits a devfreq contract at the ceiling the plan asks for`() {
        val nova = novaCapabilities()
        val manual = manualPlan(nova)

        assertTrue(
            "manual fan changes the fan axis only; the GPU vector is the same ceiling, " +
                "expressed in the units the transport that applied it uses",
            manualFanHandoffPlanAdmitted(manual, devfreqContract(nova), nova),
        )
    }

    @Test
    fun `a devfreq contract at a different ceiling still refuses`() {
        val nova = novaCapabilities()
        val manual = manualPlan(nova)
        val elsewhere = devfreqContract(nova).let { it.copy(gpuDevfreqMaxHz = it.gpuDevfreqMaxHz!! - 1_000) }

        assertFalse(
            "the predicate must still be able to FAIL -- a gate that admits every devfreq " +
                "contract is not a gate",
            manualFanHandoffPlanAdmitted(manual, elsewhere, nova),
        )
        assertFalse(
            "a plan with no GPU target is not the applied vector either, and must not pass by a " +
                "null-versus-null accident one field over",
            manualFanHandoffPlanAdmitted(manual.copy(gpuMaxPowerLevel = null), devfreqContract(nova), nova),
        )
    }

    @Test
    fun `a power-level contract is still compared as an index`() {
        val nova = novaCapabilities()
        val manual = manualPlan(nova)
        val pwrlevel = devfreqContract(nova).copy(
            gpuDevfreqMaxHz = null,
            gpuMaxPowerLevel = manual.gpuMaxPowerLevel,
        )

        assertTrue(
            "the pwrlevel chassis (the Odin's shape) must be untouched by this fix",
            manualFanHandoffPlanAdmitted(manual, pwrlevel, nova),
        )
        assertFalse(
            manualFanHandoffPlanAdmitted(manual.copy(gpuMaxPowerLevel = 99), pwrlevel, nova),
        )
    }

    @Test
    fun `the pre-change index comparison refuses exactly the contract this fix admits`() {
        val nova = novaCapabilities()
        val manual = manualPlan(nova)
        val contract = devfreqContract(nova)

        assertNotNull("the fixture must actually derive a GPU target", manual.gpuMaxPowerLevel)
        assertFalse(
            "REPLAY: `manualPlan.gpuMaxPowerLevel == contract.gpuMaxPowerLevel` -- the exact " +
                "expression that stood at MimirTweakerService and AdvancedFanControl until " +
                "2026-08-25 -- must be false here, or this fixture does not reproduce the defect",
            manual.gpuMaxPowerLevel == contract.gpuMaxPowerLevel,
        )
        assertTrue(
            "and the transport-aware comparison must admit it",
            manualFanPlanGpuMatchesContract(manual, contract, nova),
        )
    }

    // ---------------------------------------------------------------- fixtures

    private fun manualPlan(nova: HardwareCapabilities): TuningPlan = requireNotNull(
        TuningPlanner().plan(
            profileId = "balanced",
            packageName = "example.game",
            capabilities = nova,
            fanIntent = TuningPlanner.FanIntent.MANUAL_OVERRIDE,
        ),
    )

    /**
     * Built FROM the planner's own automatic derivation and the transport's own translation, never
     * a hand-picked pair that happens to agree -- the same discipline the family-widening suite
     * uses one file over.
     */
    private fun devfreqContract(nova: HardwareCapabilities): AppliedTargetContract {
        val auto = requireNotNull(TuningPlanner().plan("balanced", "example.game", nova))
        val hz = requireNotNull(
            GpuCeilingTransport.targetHzFor(nova.gpuFrequenciesKhz, requireNotNull(auto.gpuMaxPowerLevel)),
        )
        return AppliedTargetContract(
            deviceProfile = nova.deviceProfileId,
            bootId = "boot",
            cpuQosCeilingKhz = auto.cpuMaxKhz,
            cpuQosTransport = CpuQosTransport.SHARED_QOS,
            gpuMinPowerLevel = null,
            gpuMaxPowerLevel = null,
            gpuDevfreqMaxHz = hz.toInt(),
            fanMode = TuningPlanner.FAN_SMART,
            profileId = "balanced",
        )
    }

    private fun devfreqAppliedRecord(): TransactionJournalRecord {
        val plan = PersistedTuningPlan(
            profileId = "quiet",
            packageName = "example.game",
            cpuMaxKhz = mapOf(0 to 1_996_800),
            gpuMaxPowerLevel = 2,
            fanMode = null,
            calibrated = false,
        )
        val projection = listOf(
            PersistedAppliedMutation(
                logicalId = "cpu:0:scaling_max_freq",
                axis = TuningAxis.CPU,
                canonicalPath = HardwareProbe.CPU_QOS_MAX_PATH,
                kind = LogicalMutationKind.SYSFS_WRITE,
                value = 1_996_800,
                expectPresent = true,
            ),
            PersistedAppliedMutation(
                logicalId = HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID,
                axis = TuningAxis.GPU,
                canonicalPath = HardwareProbe.GPU_DEVFREQ_MAX_FREQ,
                kind = LogicalMutationKind.SYSFS_WRITE,
                value = DEVFREQ_CEILING_HZ,
                expectPresent = true,
            ),
        )
        val snapshot = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = "hardware-nova",
            bootIdentity = "boot-nova",
            nodes = projection.map { mutation ->
                TopologyNodeState(
                    logicalId = mutation.logicalId,
                    axis = mutation.axis,
                    canonicalPath = mutation.canonicalPath,
                    originalValue = if (mutation.axis == TuningAxis.GPU) 818_000_000 else Int.MAX_VALUE,
                    originalMode = "644",
                    originalUid = 0,
                    originalGid = 0,
                    present = true,
                )
            },
        )
        return TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V4,
            generation = 7L,
            requestId = "req-nova-7",
            transactionId = "tx-nova-7",
            hardwareFingerprint = "hardware-nova",
            deviceProfile = "retroid-qcs8550-pserver",
            planHash = PlanHash.ofPersisted(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.APPLIED,
            creationTime = 1L,
            requestDeadline = 120_000L,
            leaseDeadline = 120_000L,
            bootIdentity = "boot-nova",
            snapshot = snapshot,
            expectedTopologySha256 = "topology",
            snapshotSha256 = "snapshot",
            lastCompletedMutationBoundary = projection.size,
            lastCompletedRestoreBoundary = 0,
            appliedPlan = plan,
            applyOrderLogicalIds = projection.map { it.logicalId },
            appliedMutationProjection = projection,
        )
    }

    /** Device truth, read off the Retroid Pocket Nova -- same fixture as the family suite. */
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
            CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", listOf(0, 1, 2, 3), listOf(300_000, 1_516_800, 2_016_000), 300_000, 2_016_000, 2_016_000),
            CpuPolicy(4, "/sys/devices/system/cpu/cpufreq/policy4", listOf(4, 5, 6), listOf(633_600, 2_150_400, 2_803_200), 633_600, 2_803_200, 2_803_200),
            CpuPolicy(7, "/sys/devices/system/cpu/cpufreq/policy7", listOf(7), listOf(825_600, 2_476_800, 3_187_200), 825_600, 3_187_200, 3_187_200),
        ),
        gpuFrequenciesKhz = listOf(305_000, 525_000, 719_000, 818_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    private companion object {
        /** 719 MHz -- the Nova's own third table row, in the Hz the devfreq node speaks. */
        const val DEVFREQ_CEILING_HZ = 719_000_000
    }
}
