package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * LAB rung verification: [TransactionCoordinator.apply]'s `rungOverride` parameter is the LAB
 * diagnostic's transport -- the SAME transaction/journal apply-then-restore path an ordinary
 * profile apply uses, just fed a compiled-in [ThermalDerateRung] instead of a tier-derived plan.
 * This is deliberately NOT [TransactionCoordinator.applyDerate] (see
 * [TransactionCoordinatorDerateTest]), which requires a signed, already-APPLIED session and an
 * active Guardian handle -- exactly the machinery the current schema-2 manifest cannot supply.
 */
@RunWith(RobolectricTestRunner::class)
class ThermalDerateRungApplyTest {

    @Test
    fun `apply writes the exact rung clamps for every profile and restore returns exact nominal`() {
        listOf("performance", "balanced", "quiet").forEach { profileId ->
            val ladder = ThermalDerateLadder.forProfile(profileId)
            val rung = ladder.at(1)
            val rig = OdinRungRig(nominal = ladder.nominal)
            val journal = MemoryTransactionJournal()
            val coordinator = TransactionCoordinator(
                planner = TuningPlanner { _, _ -> true },
                state = rig,
                commands = rig,
                journal = journal,
                hardwareWritesCompiled = true,
            )
            assertTrue(profileId, rig.isExactStock())

            val applied = coordinator.apply(
                profileId,
                "example.game",
                rig.caps,
                dryRun = false,
                rungOverride = rung,
            )

            assertEquals(profileId, "APPLIED", applied.code)
            assertTrue(profileId, applied.success)
            assertTrue(profileId, applied.applied)
            assertEquals(profileId, mapOf(0 to rung.policy0MaxKhz, 6 to rung.policy6MaxKhz), applied.plan?.cpuMaxKhz)
            assertEquals(profileId, rung.gpuMaxPwrLevel, applied.plan?.gpuMaxPowerLevel)
            assertEquals(profileId, rung.policy0MaxKhz, rig.cpuMaxKhz(0))
            assertEquals(profileId, rung.policy6MaxKhz, rig.cpuMaxKhz(6))
            assertEquals(profileId, rung.gpuMaxPwrLevel, rig.gpuMaxLevel())
            assertFalse(profileId, rig.isExactStock())

            val restored = coordinator.restore()

            assertEquals(profileId, "RESTORED", restored.code)
            assertTrue(profileId, restored.success)
            assertTrue(profileId, restored.restored)
            assertTrue(profileId, rig.isExactStock())
            assertEquals(profileId, ladder.nominal.policy0MaxKhz, rig.cpuMaxKhz(0))
            assertEquals(profileId, ladder.nominal.policy6MaxKhz, rig.cpuMaxKhz(6))
        }
    }

    @Test
    fun `a floor rung is applied identically to any other rung and still restores exactly`() {
        val ladder = ThermalDerateLadder.forProfile("balanced")
        val floor = ladder.at(ladder.maxRung)
        val rig = OdinRungRig(nominal = ladder.nominal)
        val journal = MemoryTransactionJournal()
        val coordinator = TransactionCoordinator(
            planner = TuningPlanner { _, _ -> true },
            state = rig,
            commands = rig,
            journal = journal,
            hardwareWritesCompiled = true,
        )

        val applied = coordinator.apply("balanced", "example.game", rig.caps, dryRun = false, rungOverride = floor)
        assertEquals("APPLIED", applied.code)
        assertEquals(floor.policy6MaxKhz, rig.cpuMaxKhz(6))
        assertEquals(floor.gpuMaxPwrLevel, rig.gpuMaxLevel())

        val restored = coordinator.restore()
        assertEquals("RESTORED", restored.code)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `rungOverride null is byte-identical to the ordinary apply it replaces`() {
        val rig = OdinRungRig()
        val journal = MemoryTransactionJournal()
        val coordinator = TransactionCoordinator(
            planner = TuningPlanner { _, _ -> true },
            state = rig,
            commands = rig,
            journal = journal,
            hardwareWritesCompiled = true,
        )

        val applied = coordinator.apply("performance", "example.game", rig.caps, dryRun = false)

        assertEquals("APPLIED", applied.code)
        assertEquals(1_996_800, rig.cpuMaxKhz(0))
        assertEquals(3_072_000, rig.cpuMaxKhz(6))
        assertEquals(0, rig.gpuMaxLevel())
    }

    /**
     * Minimal Odin 3-shaped [HardwareStatePort] + [HardwareCommandPort] fake: exactly the two real
     * policy IDs ([ThermalDerateRung] targets), no FAN axis (keeps this focused on the CPU/GPU
     * clamps a rung actually carries; [TuningPlannerTest] separately proves fan mode is
     * unaffected). Starts at Performance's real nominal so a rung apply is a genuine derate, not a
     * no-op.
     */
    private class OdinRungRig(
        nominal: ThermalDerateRung = ThermalDerateLadder.forProfile("performance").nominal,
    ) : HardwareStatePort, HardwareCommandPort {
        val caps = HardwareCapabilities(
            status = CapabilityStatus.SUPPORTED,
            manufacturer = "ayn",
            model = "Odin3",
            socModel = "CQ8725S",
            buildFingerprint = "ayn/sun/rung-apply-test",
            deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            pServerAvailable = true,
            cpuPolicies = listOf(
                CpuPolicy(
                    id = 0,
                    path = "/sys/devices/system/cpu/cpufreq/policy0",
                    cpuIds = listOf(0, 1, 2, 3, 4, 5),
                    availableFrequenciesKhz = listOf(
                        384_000, 556_800, 748_800, 960_000, 1_152_000, 1_363_200, 1_555_200,
                        1_785_600, 1_996_800, 2_227_200, 2_400_000, 2_745_600, 2_918_400,
                        3_072_000, 3_321_600, 3_532_800,
                    ),
                    minKhz = 384_000,
                    maxKhz = 3_532_800,
                    currentKhz = 1_996_800,
                ),
                CpuPolicy(
                    id = 6,
                    path = "/sys/devices/system/cpu/cpufreq/policy6",
                    cpuIds = listOf(6, 7),
                    availableFrequenciesKhz = listOf(
                        1_017_600, 1_209_600, 1_401_600, 1_689_600, 1_958_400, 2_246_400,
                        2_438_400, 2_649_600, 2_841_600, 3_072_000, 3_283_200, 3_513_600,
                        3_801_600, 4_089_600, 4_204_800, 4_320_000,
                    ),
                    minKhz = 1_017_600,
                    maxKhz = 4_320_000,
                    currentKhz = 3_072_000,
                ),
            ),
            gpuFrequenciesKhz = List(14) { index -> 200_000 + index * 50_000 },
            gpuNodeAvailable = true,
            fanNodeAvailable = false,
            refreshRatesHz = listOf(60, 120),
            notes = emptyList(),
        )

        private data class Node(var value: Int?, val stockValue: Int?)

        private val cpu0Path = "${caps.cpuPolicies[0].path}/scaling_max_freq"
        private val cpu6Path = "${caps.cpuPolicies[1].path}/scaling_max_freq"

        // Starts at the CALLER'S OWN profile nominal (ThermalDerateLadder.kt) so a rung apply is a
        // genuine step down for that specific profile, and restore's "back to nominal" claim is
        // checked against the SAME profile's nominal, never a different profile's.
        private val sysfs = linkedMapOf(
            cpu0Path to Node(nominal.policy0MaxKhz, nominal.policy0MaxKhz),
            cpu6Path to Node(nominal.policy6MaxKhz, nominal.policy6MaxKhz),
            HardwareProbe.GPU_MIN_LEVEL to Node(13, 13),
            HardwareProbe.GPU_MAX_LEVEL to Node(nominal.gpuMaxPwrLevel, nominal.gpuMaxPwrLevel),
        )

        fun isExactStock(): Boolean = sysfs.values.all { it.value == it.stockValue }
        fun cpuMaxKhz(policyId: Int): Int? = sysfs[if (policyId == 0) cpu0Path else cpu6Path]?.value
        fun gpuMaxLevel(): Int? = sysfs[HardwareProbe.GPU_MAX_LEVEL]?.value

        override fun captureForPlan(plan: TuningPlan, capabilities: HardwareCapabilities): CaptureOutcome {
            val nodes = mutableListOf<TopologyNodeState>()
            plan.cpuMaxKhz.keys.sorted().forEach { policyId ->
                val path = if (policyId == 0) cpu0Path else cpu6Path
                val node = sysfs.getValue(path)
                nodes += TopologyNodeState(
                    logicalId = "cpu:$policyId:scaling_max_freq",
                    axis = TuningAxis.CPU,
                    canonicalPath = path,
                    originalValue = node.value,
                    originalMode = "644",
                    originalUid = 0,
                    originalGid = 0,
                    present = true,
                )
            }
            if (plan.gpuMaxPowerLevel != null) {
                listOf(
                    HardwareSnapshot.GPU_MIN_LOGICAL_ID to HardwareProbe.GPU_MIN_LEVEL,
                    HardwareSnapshot.GPU_MAX_LOGICAL_ID to HardwareProbe.GPU_MAX_LEVEL,
                ).forEach { (logicalId, path) ->
                    val node = sysfs.getValue(path)
                    nodes += TopologyNodeState(
                        logicalId = logicalId,
                        axis = TuningAxis.GPU,
                        canonicalPath = path,
                        originalValue = node.value,
                        originalMode = "644",
                        originalUid = 0,
                        originalGid = 0,
                        present = true,
                    )
                }
            }
            return CaptureOutcome(
                snapshot = HardwareSnapshot(
                    schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
                    hardwareFingerprint = HardwareFingerprint.create(capabilities),
                    bootIdentity = "boot-odin-rung-apply-test",
                    nodes = nodes,
                ),
            )
        }

        override fun verify(plan: TuningPlan): Boolean = verifyPlan(plan).ok
        override fun verifyPlan(plan: TuningPlan): PlanVerification {
            val failures = mutableListOf<String>()
            plan.cpuMaxKhz.forEach { (id, target) ->
                if (cpuMaxKhz(id) != target) failures += "cpu $id"
            }
            plan.gpuMaxPowerLevel?.let { target ->
                if (gpuMaxLevel() != target) failures += "gpu"
            }
            return PlanVerification(failures.isEmpty(), failures)
        }

        override fun verifyAppliedPlan(plan: TuningPlan, mutations: List<LogicalMutation>): PlanVerification {
            val failures = verifyPlan(plan).failures.toMutableList()
            mutations.forEach { mutation -> if (!readback(mutation)) failures += "projected ${mutation.logicalId}" }
            return PlanVerification(failures.isEmpty(), failures)
        }

        override fun verifySnapshot(snapshot: HardwareSnapshot): Boolean = verifySnapshotPlan(snapshot).ok
        override fun verifySnapshotPlan(snapshot: HardwareSnapshot): PlanVerification {
            val failures = mutableListOf<String>()
            snapshot.nodes.forEach { node ->
                val live = sysfs[node.canonicalPath]
                if (live == null || live.value != node.originalValue) failures += node.logicalId
            }
            return PlanVerification(failures.isEmpty(), failures)
        }

        override fun admitCpuTargetsAfterCooling(plan: TuningPlan): PlanVerification = PlanVerification(true)

        override fun readback(mutation: LogicalMutation): Boolean = sysfs[mutation.canonicalPath]?.value == mutation.value

        override fun plannedMutations(plan: TuningPlan, snapshot: HardwareSnapshot): List<LogicalMutation> =
            snapshot.nodes.map { node ->
                val value = when (node.axis) {
                    TuningAxis.CPU -> plan.cpuMaxKhz.getValue(
                        node.logicalId.removePrefix("cpu:").substringBefore(":").toInt(),
                    )
                    TuningAxis.GPU -> if (node.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID) {
                        caps.gpuFrequenciesKhz.size - 1
                    } else {
                        plan.gpuMaxPowerLevel
                    }
                    TuningAxis.FAN -> null
                    TuningAxis.VENDOR_MODE -> error("vendor mode is absent in this rig")
                }
                LogicalMutation(
                    logicalId = node.logicalId,
                    axis = node.axis,
                    canonicalPath = node.canonicalPath,
                    kind = LogicalMutationKind.SYSFS_WRITE,
                    value = value,
                    restoreMode = node.originalMode,
                    restoreUid = node.originalUid,
                    restoreGid = node.originalGid,
                    expectPresent = true,
                )
            }

        override fun restoreMutations(record: TransactionJournalRecord): List<LogicalMutation> {
            val plan = checkNotNull(record.appliedPlan).toTuningPlan()
            return plannedMutations(plan, record.snapshot).asReversed().map { mutation ->
                val node = record.snapshot.nodes.first { it.logicalId == mutation.logicalId }
                mutation.copy(value = node.originalValue)
            }
        }

        override fun validateRestore(record: TransactionJournalRecord): PlanVerification {
            if (record.hardwareFingerprint != HardwareFingerprint.create(caps)) {
                return PlanVerification(false, listOf("hardwareFingerprint mismatch"))
            }
            return RestoreContextValidator.validate(record, caps)
        }

        override fun execute(mutation: LogicalMutation): CheckedCommandResult {
            sysfs.getValue(mutation.canonicalPath).value = mutation.value
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0, detail = "ok")
        }
    }

    private class MemoryTransactionJournal : TransactionJournal {
        private var record: TransactionJournalRecord? = null

        override fun load(): JournalLoadResult = record?.let(JournalLoadResult::Loaded) ?: JournalLoadResult.Empty
        override fun nextGeneration(): GenerationResult = GenerationResult.Ok(1L)
        override fun persist(record: TransactionJournalRecord): Boolean {
            this.record = record
            return true
        }
        override fun clear(expectedTransactionId: String): Boolean {
            if (record?.transactionId != expectedTransactionId) return false
            record = null
            return true
        }
    }
}
