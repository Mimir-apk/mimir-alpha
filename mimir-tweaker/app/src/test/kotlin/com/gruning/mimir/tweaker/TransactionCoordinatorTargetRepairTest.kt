package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionCoordinatorTargetRepairTest {
    @Test
    fun `exact Odin CPU repair is Guardian fenced and journal silent`() {
        val fixture = fixture(withActiveGuardian = true)
        val before = fixture.journal.record
        val persistsBefore = fixture.journal.persists

        fixture.rig.driftCpu()
        val result = fixture.coordinator.repairAppliedCpuQosBound(7L, "tx-7") { true }

        assertEquals(TransactionCoordinator.TARGET_REPAIRED, result.code)
        assertTrue(result.success)
        assertFalse(result.applied)
        assertEquals(
            listOf("cpu:0:scaling_max_freq", "cpu:6:scaling_max_freq"),
            fixture.rig.executedLogicalIds,
        )
        assertEquals(
            listOf(
                CpuCeilingTransport.ACTIVE_SHARED_QOS_MODE,
                CpuCeilingTransport.ACTIVE_SHARED_QOS_MODE,
            ),
            fixture.rig.executedModes,
        )
        assertEquals(persistsBefore, fixture.journal.persists)
        assertEquals(0, fixture.journal.clears)
        assertEquals(before, fixture.journal.record)
    }

    @Test
    fun `genuine POLICY_SCALING_MAX fallback CPU drift repairs per-policy ceilings under the same fence`() {
        val fixture = fixture(withActiveGuardian = true, perPolicy = true)
        val before = fixture.journal.record
        val persistsBefore = fixture.journal.persists

        fixture.rig.driftCpu()
        val result = fixture.coordinator.repairAppliedCpuQosBound(7L, "tx-7") { true }

        assertEquals(TransactionCoordinator.TARGET_REPAIRED, result.code)
        assertTrue(result.success)
        assertEquals(
            listOf("cpu:0:scaling_max_freq", "cpu:6:scaling_max_freq"),
            fixture.rig.executedLogicalIds,
        )
        // Per-policy nodes keep the CAPTURED mode; only the shared node is held read-only.
        assertEquals(listOf("644", "644"), fixture.rig.executedModes)
        assertEquals(persistsBefore, fixture.journal.persists)
        assertEquals(before, fixture.journal.record)
    }

    @Test
    fun `missing Guardian stale identity and lease margin execute zero repair commands`() {
        val noGuardian = fixture(withActiveGuardian = false)
        noGuardian.rig.driftCpu()
        assertEquals(
            "TARGET_REPAIR_CONTEXT_REJECTED",
            noGuardian.coordinator.repairAppliedCpuQosBound(7L, "tx-7") { true }.code,
        )
        assertTrue(noGuardian.rig.executedLogicalIds.isEmpty())

        val stale = fixture(withActiveGuardian = true)
        stale.rig.driftCpu()
        assertEquals(
            "STALE_GENERATION",
            stale.coordinator.repairAppliedCpuQosBound(8L, "tx-8") { true }.code,
        )
        assertTrue(stale.rig.executedLogicalIds.isEmpty())

        val expiring = fixture(withActiveGuardian = true, nowMs = 88_000L)
        expiring.rig.driftCpu()
        assertEquals(
            "TARGET_REPAIR_CONTEXT_REJECTED",
            expiring.coordinator.repairAppliedCpuQosBound(7L, "tx-7") { true }.code,
        )
        assertTrue(expiring.rig.executedLogicalIds.isEmpty())
    }

    @Test
    fun `repair command failure preserves authenticated APPLIED truth`() {
        val fixture = fixture(withActiveGuardian = true)
        val before = fixture.journal.record
        fixture.rig.driftCpu()
        fixture.rig.failExecute = true

        val result = fixture.coordinator.repairAppliedCpuQosBound(7L, "tx-7") { true }

        assertEquals("TARGET_REPAIR_FAILED", result.code)
        assertFalse(result.success)
        assertEquals(before, fixture.journal.record)
        assertEquals(0, fixture.journal.persists)
        assertEquals(0, fixture.journal.clears)
    }

    @Test
    fun `mid repair owner loss blocks the second write and supervisor no longer restores`() {
        val fixture = fixture(withActiveGuardian = true)
        val record = checkNotNull(fixture.journal.record)
        val contract = checkNotNull(AppliedTargetContract.fromAuthenticatedAppliedRecord(record))
        var authorized = true
        var restores = 0
        fixture.rig.driftCpu()
        fixture.rig.afterExecute = { authorized = false }
        val supervisor = HardwareSessionSupervisor(
            policy = SupervisorPolicy(),
            clock = SupervisorClock { fixture.clock.nowMs },
            workload = WorkloadObservationPort { WorkloadTruth.TARGET_FOREGROUND },
            telemetry = ThermalTelemetryPort {
                ThermalTelemetrySample(
                    androidThermalStatus = android.os.PowerManager.THERMAL_STATUS_NONE,
                    cpuTempC = 45,
                    gpuTempC = 42,
                    batteryTempDeciC = 330,
                    cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
                    cpuQosTransport = CpuQosTransport.SHARED_QOS,
                    gpuMaxPowerLevel = contract.gpuMaxPowerLevel,
                    bootId = contract.bootId,
                )
            },
            ownerLiveness = OwnerLivenessPort { true },
            restorer = GenerationBoundRestorer { generation, transactionId ->
                restores++
                fixture.rig.restoreStock()
                TransactionResult(
                    success = true,
                    applied = false,
                    restored = true,
                    code = "RESTORED",
                    message = "Stock restored",
                    generation = generation,
                    transactionId = transactionId,
                )
            },
            targetRepairer = GenerationBoundTargetRepairer { generation, transactionId ->
                fixture.coordinator.repairAppliedCpuQosBound(generation, transactionId) { authorized }
            },
        )
        supervisor.adoptAppliedSession(
            ActiveSessionIdentity(
                generation = record.generation,
                transactionId = record.transactionId,
                requestId = record.requestId,
                ownerUid = 42,
                targetPackage = record.targetPackage,
                activationDeadlineMs = 10_000L,
                leaseDeadlineMs = record.leaseDeadline,
                targetEverForeground = true,
                ownerTokenId = 1L,
                hardLeaseDeadlineMs = record.leaseDeadline,
                appliedTargetContract = contract,
                scope = ActiveSessionScope.SYSTEM,
            ),
        )

        var attempt: TransactionResult? = null
        val settlement = supervisor.tickAndExecute(onRepairAttempt = { attempt = it })

        // Policy 2026-08-13/14 (P0 thermal safety): a repair failure of any kind --
        // including a mid-repair fence loss, as here -- no longer falls back to restore. The
        // settlement is the fence-loss result itself; the device is left exactly where the
        // partial repair stopped (one axis re-asserted, not Stock), not reverted, and a later
        // tick's own bounded repair budget is the retry path.
        assertEquals("TARGET_REPAIR_FENCE_LOST", attempt?.code)
        assertEquals("TARGET_REPAIR_FENCE_LOST", settlement?.code)
        assertEquals(1, fixture.rig.executedLogicalIds.size)
        assertEquals(0, restores)
        assertFalse(fixture.rig.isExactStock())
    }

    @Test
    fun `mid repair lease or Guardian loss stops further commands`() {
        val lease = fixture(withActiveGuardian = true)
        lease.rig.driftCpu()
        lease.rig.afterExecute = { lease.clock.nowMs = 90_000L }
        assertEquals(
            "TARGET_REPAIR_FENCE_LOST",
            lease.coordinator.repairAppliedCpuQosBound(7L, "tx-7") { true }.code,
        )
        assertEquals(1, lease.rig.executedLogicalIds.size)

        val guardian = fixture(withActiveGuardian = true)
        guardian.rig.driftCpu()
        guardian.rig.afterExecute = guardian::clearGuardian
        assertEquals(
            "TARGET_REPAIR_FENCE_LOST",
            guardian.coordinator.repairAppliedCpuQosBound(7L, "tx-7") { true }.code,
        )
        assertEquals(1, guardian.rig.executedLogicalIds.size)
    }

    private fun fixture(
        withActiveGuardian: Boolean,
        nowMs: Long = 1_000L,
        perPolicy: Boolean = false,
    ): Fixture {
        val capabilities = odinCapabilities(perPolicy)
        val plan = TuningPlan(
            profileId = "quiet",
            packageName = SystemWideProfileTransition.TARGET_PACKAGE_ID,
            cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 2_246_400),
            gpuMaxPowerLevel = 0,
            fanMode = null,
            calibrated = true,
        )
        val fingerprint = HardwareFingerprint.create(capabilities)
        val nodes = listOf(
            cpuNode(0, Int.MAX_VALUE, perPolicy),
            cpuNode(6, Int.MAX_VALUE, perPolicy),
            TopologyNodeState(
                logicalId = HardwareSnapshot.GPU_MAX_LOGICAL_ID,
                axis = TuningAxis.GPU,
                canonicalPath = HardwareProbe.GPU_MAX_LEVEL,
                originalValue = 0,
                originalMode = "644",
                originalUid = 0,
                originalGid = 0,
                present = true,
            ),
        )
        val snapshot = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = fingerprint,
            bootIdentity = "boot-odin",
            nodes = nodes,
        )
        val hashes = checkNotNull(SnapshotIntegrity.bindHashes(plan, capabilities, snapshot))
        val mutations = listOf(
            mutation(nodes[0], plan.cpuMaxKhz.getValue(0)),
            mutation(nodes[1], plan.cpuMaxKhz.getValue(6)),
            mutation(nodes[2], 0),
        )
        val unsignedRecord = TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5,
            generation = 7L,
            requestId = "req-7",
            transactionId = "tx-7",
            hardwareFingerprint = fingerprint,
            deviceProfile = CpuCeilingTransport.ODIN_3_PROFILE,
            planHash = PlanHash.of(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.APPLIED,
            creationTime = 1_000L,
            requestDeadline = 31_000L,
            leaseDeadline = 90_000L,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = hashes.first,
            snapshotSha256 = hashes.second,
            lastCompletedMutationBoundary = mutations.size,
            appliedPlan = PersistedTuningPlan.from(plan),
            applyOrderLogicalIds = mutations.map { it.logicalId },
            appliedMutationProjection = mutations.map(PersistedAppliedMutation::from),
        )
        val restoreEntries = mutations.mapIndexed { ordinal, mutation ->
            val node = nodes.single { it.logicalId == mutation.logicalId }
            RestoreGuardianSysfsEntry(
                applyOrdinal = ordinal,
                logicalId = mutation.logicalId,
                canonicalSysfsPath = mutation.canonicalPath,
                expectedStock = RestoreGuardianStockState.PresentSysfs(
                    value = checkNotNull(node.originalValue),
                    mode = checkNotNull(node.originalMode),
                    uid = checkNotNull(node.originalUid),
                    gid = checkNotNull(node.originalGid),
                ),
                rendererAppliedIntermediates = listOf(checkNotNull(mutation.value)),
            )
        }.asReversed()
        val envelope = RestoreGuardianEnvelope(
            helperBuildIdentity = "helper-build",
            tweakerCertificateSha256 = "c".repeat(64),
            hardwareFingerprint = fingerprint,
            deviceProfile = unsignedRecord.deviceProfile,
            bootIdentity = unsignedRecord.bootIdentity,
            generation = unsignedRecord.generation,
            requestId = unsignedRecord.requestId,
            transactionId = unsignedRecord.transactionId,
            taskNonce = "b".repeat(32),
            topologySha256 = unsignedRecord.expectedTopologySha256,
            snapshotSha256 = unsignedRecord.snapshotSha256,
            planSha256 = unsignedRecord.planHash,
            hardDeadlineElapsedRealtimeMs = unsignedRecord.leaseDeadline,
            restoreEntries = restoreEntries,
        )
        val record = unsignedRecord.copy(
            guardianBinding = GuardianJournalBinding(
                envelopeSha256 = envelope.digestSha256(),
                taskNonce = envelope.taskNonce,
                helperBuildIdentity = envelope.helperBuildIdentity,
                tweakerCertificateSha256 = envelope.tweakerCertificateSha256,
                hardDeadlineElapsedRealtimeMs = envelope.hardDeadlineElapsedRealtimeMs,
                readySessionSha256 = "d".repeat(64),
            ),
        )
        check(AppliedMutationProjectionValidator.validate(record).isEmpty())
        check(GuardianJournalBindingValidator.validate(record).isEmpty())
        val journal = MemoryJournal(record)
        val rig = RepairRig(record)
        val clock = MutableClock(nowMs)
        val coordinator = TransactionCoordinator(
            planner = TuningPlanner { _, _ -> true },
            state = rig,
            commands = rig,
            journal = journal,
            hardwareWritesCompiled = true,
            clock = { clock.nowMs },
            requiresGuardianAuthority = true,
        )
        if (withActiveGuardian) {
            val expected = checkNotNull(GuardianArmExpectedIdentityDerivation.from(record))
            val handle = GuardianReadyHandle(
                expectedIdentity = expected,
                signedEnvelope = SignedRestoreGuardianEnvelope(envelope, byteArrayOf(1)),
                readySessionSha256 = "d".repeat(64),
            )
            TransactionCoordinator::class.java.getDeclaredField("activeGuardianHandle")
                .apply { isAccessible = true }
                .set(coordinator, handle)
        }
        return Fixture(coordinator, rig, journal, clock)
    }

    private fun cpuNode(policyId: Int, original: Int, perPolicy: Boolean = false) = TopologyNodeState(
        logicalId = "cpu:$policyId:scaling_max_freq",
        axis = TuningAxis.CPU,
        canonicalPath = if (perPolicy) {
            "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_max_freq"
        } else {
            HardwareProbe.CPU_QOS_MAX_PATH
        },
        originalValue = original,
        originalMode = "644",
        originalUid = 0,
        originalGid = 0,
        present = true,
    )

    private fun mutation(node: TopologyNodeState, value: Int) = LogicalMutation(
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

    private fun odinCapabilities(perPolicy: Boolean = false) = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "ayn",
        model = "Odin3",
        socModel = "CQ8725S",
        buildFingerprint = "qti/sun/test",
        deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", (0..5).toList(), listOf(1_996_800, 3_532_800), 998_400, 3_532_800, 3_532_800),
            CpuPolicy(6, "/sys/devices/system/cpu/cpufreq/policy6", listOf(6, 7), listOf(2_246_400, 4_320_000), 1_075_200, 4_320_000, 4_320_000),
        ),
        gpuFrequenciesKhz = listOf(200_000, 400_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = false,
        refreshRatesHz = listOf(60, 120),
        notes = emptyList(),
        cpuCeilingPath = if (perPolicy) null else HardwareProbe.CPU_QOS_MAX_PATH,
    )

    private data class Fixture(
        val coordinator: TransactionCoordinator,
        val rig: RepairRig,
        val journal: MemoryJournal,
        val clock: MutableClock,
    ) {
        fun clearGuardian() {
            TransactionCoordinator::class.java.getDeclaredField("activeGuardianHandle")
                .apply { isAccessible = true }
                .set(coordinator, null)
        }
    }

    private data class MutableClock(var nowMs: Long)

    private class MemoryJournal(initial: TransactionJournalRecord) : TransactionJournal {
        var record: TransactionJournalRecord? = initial
        var persists = 0
        var clears = 0
        override fun load(): JournalLoadResult = record?.let(JournalLoadResult::Loaded) ?: JournalLoadResult.Empty
        override fun nextGeneration() = GenerationResult.Ok(8L)
        override fun persist(record: TransactionJournalRecord): Boolean {
            persists++
            this.record = record
            return true
        }
        override fun clear(expectedTransactionId: String): Boolean {
            clears++
            record = null
            return true
        }
    }

    private class RepairRig(record: TransactionJournalRecord) : HardwareStatePort, HardwareCommandPort {
        private val stockValues = record.snapshot.nodes.associate { it.logicalId to it.originalValue }
        private val values = record.appliedMutationProjection.associate { it.logicalId to it.value }.toMutableMap()
        val executedLogicalIds = mutableListOf<String>()
        val executedModes = mutableListOf<String?>()
        var failExecute = false
        var afterExecute: (() -> Unit)? = null

        fun driftCpu() {
            values.keys.filter { it.startsWith("cpu:") }.forEach { values[it] = Int.MAX_VALUE }
        }

        fun restoreStock() {
            values.putAll(stockValues)
        }

        fun isExactStock(): Boolean = values == stockValues

        override fun execute(mutation: LogicalMutation): CheckedCommandResult {
            executedLogicalIds += mutation.logicalId
            executedModes += mutation.restoreMode
            if (failExecute) return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "injected")
            values[mutation.logicalId] = mutation.value
            afterExecute?.invoke()
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0, detail = "ok")
        }

        override fun readback(mutation: LogicalMutation): Boolean = values[mutation.logicalId] == mutation.value
        override fun verifyAppliedPlan(plan: TuningPlan, mutations: List<LogicalMutation>) =
            PlanVerification(mutations.all(::readback))
        override fun validateRestore(record: TransactionJournalRecord) = PlanVerification(true)
        override fun captureForPlan(plan: TuningPlan, capabilities: HardwareCapabilities) = error("unused")
        override fun verify(plan: TuningPlan) = true
        override fun verifyPlan(plan: TuningPlan) = PlanVerification(true)
        override fun verifySnapshot(snapshot: HardwareSnapshot) = true
        override fun verifySnapshotPlan(snapshot: HardwareSnapshot) = PlanVerification(true)
        override fun admitCpuTargetsAfterCooling(plan: TuningPlan) = PlanVerification(true)
        override fun plannedMutations(plan: TuningPlan, snapshot: HardwareSnapshot) = emptyList<LogicalMutation>()
        override fun restoreMutations(record: TransactionJournalRecord) = emptyList<LogicalMutation>()
    }
}
