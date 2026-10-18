package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAVE2 Stage B (spec S2/S5): [TransactionCoordinator.applyDerate] is "select a different,
 * already qualified plan," never "compute a more conservative write." The vectors here are the
 * real signed Performance rung 1 from [ThermalDerateLadder] (`policy6=2,841,600` / GPU L2),
 * device-verified 2026-08-06 -- this test exercises the exact mechanism a device session uses,
 * not an arbitrary synthetic pair.
 */
class TransactionCoordinatorDerateTest {
    @Test
    fun `exact signed rung applies CPU and GPU together and never touches fan`() {
        val fixture = fixture(withActiveGuardian = true)
        val before = fixture.journal.record

        val result = fixture.coordinator.applyDerate(7L, "tx-7", RUNG_PLAN, ODIN_CAPABILITIES) { true }

        assertEquals(TransactionCoordinator.THERMAL_DERATE_APPLIED, result.code)
        assertTrue(result.success)
        assertTrue(result.applied)
        assertEquals(RUNG_PLAN.cpuMaxKhz, result.plan?.cpuMaxKhz)
        assertEquals(RUNG_PLAN.gpuMaxPowerLevel, result.plan?.gpuMaxPowerLevel)
        // Carried forward from the record already durable before this call, not from RUNG_PLAN.
        assertEquals(NOMINAL_PLAN.fanMode, result.plan?.fanMode)

        assertEquals(
            listOf("cpu:0:scaling_max_freq", "cpu:6:scaling_max_freq", HardwareSnapshot.GPU_MAX_LOGICAL_ID),
            fixture.rig.executedLogicalIds,
        )
        assertFalse(fixture.rig.executedLogicalIds.contains(HardwareSnapshot.FAN_LOGICAL_ID))
        assertEquals(1_996_800, fixture.rig.values["cpu:0:scaling_max_freq"])
        assertEquals(2_841_600, fixture.rig.values["cpu:6:scaling_max_freq"])
        assertEquals(2, fixture.rig.values[HardwareSnapshot.GPU_MAX_LOGICAL_ID])
        assertEquals(TuningPlanner.FAN_SMART, fixture.rig.values[HardwareSnapshot.FAN_LOGICAL_ID])

        // Through the amendment lane, NOT persist. The durable journal refuses a changed
        // applied projection via persist, so routing a rung there produced
        // THERMAL_DERATE_JOURNAL_PERSIST_FAILED on device while this suite stayed green
        // against an accept-everything double. Asserting the lane is what keeps that honest.
        assertEquals(1, fixture.journal.amends)
        assertEquals(0, fixture.journal.persists)
        assertEquals(0, fixture.journal.clears)
        val after = fixture.journal.record
        assertTrue(after != before)
        assertEquals(mapOf(0 to 1_996_800, 6 to 2_841_600), after?.appliedPlan?.cpuMaxKhz)
        assertEquals(2, after?.appliedPlan?.gpuMaxPowerLevel)
        assertEquals(TuningPlanner.FAN_SMART, after?.appliedPlan?.fanMode)
        assertEquals(before?.generation, after?.generation)
        assertEquals(before?.transactionId, after?.transactionId)
        assertEquals(before?.snapshot, after?.snapshot)
        assertEquals(PlanHash.ofPersisted(checkNotNull(after?.appliedPlan)), after?.planHash)
        assertTrue(AppliedMutationProjectionValidator.validate(checkNotNull(after)).isEmpty())
    }

    @Test
    fun `a derate carries the vendor mode forward unchanged and never writes it, even after the player moved it`() {
        // The Guardian envelope validates /sys paths against the host filesystem, so this fixture can only be
        // built on a POSIX host (the sibling derate tests are red on Windows for the same reason).
        org.junit.Assume.assumeTrue(SysfsPath.canonicalize(HardwareProbe.GPU_MIN_LEVEL) != null)
        val applied = NOMINAL_PLAN.copy(vendorMode = TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE)
        val fixture = fixture(withActiveGuardian = true, appliedPlan = applied)
        // The player has since moved the mode off the session's 2 (the supervisor yields to that).
        fixture.rig.values[HardwareSnapshot.VENDOR_MODE_LOGICAL_ID] = TuningPlanner.VENDOR_MODE_STANDARD
        val before = checkNotNull(fixture.journal.record)

        val result = fixture.coordinator.applyDerate(7L, "tx-7", RUNG_PLAN, ODIN_CAPABILITIES) { true }

        assertEquals(result.message, TransactionCoordinator.THERMAL_DERATE_APPLIED, result.code)
        assertTrue(result.success)
        // Only the CPU/GPU ceilings moved; the vendor mode was neither executed nor put back.
        assertEquals(
            listOf("cpu:0:scaling_max_freq", "cpu:6:scaling_max_freq", HardwareSnapshot.GPU_MAX_LOGICAL_ID),
            fixture.rig.executedLogicalIds,
        )
        assertEquals(
            TuningPlanner.VENDOR_MODE_STANDARD,
            fixture.rig.values[HardwareSnapshot.VENDOR_MODE_LOGICAL_ID],
        )
        // The amended record still names the session's mode: dropping it would have changed the plan
        // hash and failed the projection validator on the very rung that exists to protect hardware.
        val after = checkNotNull(fixture.journal.record)
        assertEquals(TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE, after.appliedPlan?.vendorMode)
        assertEquals(PlanHash.ofPersisted(checkNotNull(after.appliedPlan)), after.planHash)
        assertEquals(
            before.appliedMutationProjection.single { it.axis == TuningAxis.VENDOR_MODE },
            after.appliedMutationProjection.single { it.axis == TuningAxis.VENDOR_MODE },
        )
        assertTrue(AppliedMutationProjectionValidator.validate(after).isEmpty())
        assertEquals(before.snapshot, after.snapshot)
    }

    @Test
    fun `a rung plan claiming a different fan mode still never touches the fan axis or its persisted value`() {
        val fixture = fixture(withActiveGuardian = true)
        // Defense in depth: even if a caller-supplied plan somehow diverged on fan (it never
        // should -- QualifiedDerateRung admission forces fanMode identical to the base plan --
        // this proves the coordinator structurally ignores rungPlan.fanMode rather than trusting
        // it, exactly as the applyDerate kdoc claims.
        val divergentFanRung = RUNG_PLAN.copy(fanMode = TuningPlanner.FAN_MANUAL)

        val result = fixture.coordinator.applyDerate(7L, "tx-7", divergentFanRung, ODIN_CAPABILITIES) { true }

        assertEquals(TransactionCoordinator.THERMAL_DERATE_APPLIED, result.code)
        assertFalse(fixture.rig.executedLogicalIds.contains(HardwareSnapshot.FAN_LOGICAL_ID))
        assertEquals(TuningPlanner.FAN_SMART, fixture.rig.values[HardwareSnapshot.FAN_LOGICAL_ID])
        assertEquals(TuningPlanner.FAN_SMART, fixture.journal.record?.appliedPlan?.fanMode)
        assertEquals(TuningPlanner.FAN_SMART, result.plan?.fanMode)
    }

    @Test
    fun `an uncalibrated rung plan is refused with zero commands exactly like apply's own gate`() {
        val fixture = fixture(withActiveGuardian = true)
        val before = fixture.journal.record

        val result = fixture.coordinator.applyDerate(
            7L,
            "tx-7",
            RUNG_PLAN.copy(calibrated = false),
            ODIN_CAPABILITIES,
        ) { true }

        assertEquals("PLAN_NOT_ADMITTED", result.code)
        assertFalse(result.success)
        assertTrue(fixture.rig.executedLogicalIds.isEmpty())
        assertEquals(0, fixture.journal.writes)
        assertEquals(before, fixture.journal.record)
    }

    @Test
    fun `derate refuses a CPU target faster than the currently applied ceiling`() {
        val fixture = fixture(withActiveGuardian = true)
        val fasterCpuRung = RUNG_PLAN.copy(cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 3_072_000 + 1))

        val result = fixture.coordinator.applyDerate(7L, "tx-7", fasterCpuRung, ODIN_CAPABILITIES) { true }

        assertEquals("THERMAL_DERATE_CONTEXT_REJECTED", result.code)
        assertTrue(fixture.rig.executedLogicalIds.isEmpty())
        assertEquals(0, fixture.journal.writes)
    }

    @Test
    fun `derate refuses a GPU target faster than an already derated ceiling`() {
        // Start from a session already sitting at rung 1 (GPU level 2), then attempt a "rung"
        // that is faster (level 1) than what is CURRENTLY applied -- this is the defensive
        // second line of defense, independent of the manifest's own decode-time dominance.
        val fixture = fixture(withActiveGuardian = true, appliedPlan = RUNG_PLAN)
        val fasterThanCurrent = RUNG_PLAN.copy(gpuMaxPowerLevel = 1)

        val result = fixture.coordinator.applyDerate(7L, "tx-7", fasterThanCurrent, ODIN_CAPABILITIES) { true }

        assertEquals("THERMAL_DERATE_CONTEXT_REJECTED", result.code)
        assertTrue(fixture.rig.executedLogicalIds.isEmpty())
        assertEquals(0, fixture.journal.writes)
    }

    @Test
    fun `missing Guardian stale identity and profile mismatch execute zero derate commands`() {
        val noGuardian = fixture(withActiveGuardian = false)
        assertEquals(
            "THERMAL_DERATE_CONTEXT_REJECTED",
            noGuardian.coordinator.applyDerate(7L, "tx-7", RUNG_PLAN, ODIN_CAPABILITIES) { true }.code,
        )
        assertTrue(noGuardian.rig.executedLogicalIds.isEmpty())

        val stale = fixture(withActiveGuardian = true)
        assertEquals(
            "STALE_GENERATION",
            stale.coordinator.applyDerate(8L, "tx-8", RUNG_PLAN, ODIN_CAPABILITIES) { true }.code,
        )
        assertTrue(stale.rig.executedLogicalIds.isEmpty())

        val wrongProfile = fixture(withActiveGuardian = true)
        assertEquals(
            "THERMAL_DERATE_CONTEXT_REJECTED",
            wrongProfile.coordinator.applyDerate(
                7L,
                "tx-7",
                RUNG_PLAN.copy(profileId = "balanced"),
                ODIN_CAPABILITIES,
            ) { true }.code,
        )
        assertTrue(wrongProfile.rig.executedLogicalIds.isEmpty())
    }

    @Test
    fun `derate command failure preserves authenticated APPLIED truth and never restores`() {
        val fixture = fixture(withActiveGuardian = true)
        val before = fixture.journal.record
        fixture.rig.failExecute = true

        val result = fixture.coordinator.applyDerate(7L, "tx-7", RUNG_PLAN, ODIN_CAPABILITIES) { true }

        assertEquals("THERMAL_DERATE_FAILED", result.code)
        assertFalse(result.success)
        assertFalse(result.restored)
        assertEquals(before, fixture.journal.record)
        assertEquals(0, fixture.journal.writes)
        assertEquals(0, fixture.journal.clears)
    }

    @Test
    fun `mid derate authorization loss stops further commands without restoring`() {
        val fixture = fixture(withActiveGuardian = true)
        var authorized = true
        fixture.rig.afterExecute = { authorized = false }

        val result = fixture.coordinator.applyDerate(7L, "tx-7", RUNG_PLAN, ODIN_CAPABILITIES) { authorized }

        assertEquals("THERMAL_DERATE_FENCE_LOST", result.code)
        assertFalse(result.success)
        assertFalse(result.restored)
        assertEquals(1, fixture.rig.executedLogicalIds.size)
        assertEquals(0, fixture.journal.writes)
    }

    private companion object {
        val ODIN_CAPABILITIES = HardwareCapabilities(
            status = CapabilityStatus.SUPPORTED,
            manufacturer = "ayn",
            model = "Odin3",
            socModel = "CQ8725S",
            buildFingerprint = "qti/sun/test",
            deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
            pServerAvailable = true,
            cpuPolicies = listOf(
                CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", (0..5).toList(), listOf(1_996_800, 3_532_800), 998_400, 3_532_800, 3_532_800),
                // Real Odin 3 policy6 OPP steps around the Performance nominal/rung1 vectors
                // (ThermalDerateLadder.kt), not just the two
                // endpoints -- PlanTargetValidator requires exact OPP membership.
                CpuPolicy(
                    6,
                    "/sys/devices/system/cpu/cpufreq/policy6",
                    listOf(6, 7),
                    listOf(1_958_400, 2_246_400, 2_438_400, 2_649_600, 2_841_600, 3_072_000, 4_320_000),
                    1_075_200,
                    4_320_000,
                    4_320_000,
                ),
            ),
            gpuFrequenciesKhz = listOf(1_100_000, 1_050_000, 967_000, 900_000, 832_000, 734_000),
            gpuNodeAvailable = true,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60, 120),
            notes = emptyList(),
            cpuCeilingPath = HardwareProbe.CPU_QOS_MAX_PATH,
        )

        // Real Performance nominal / rung 1 vectors from ThermalDerateLadder.kt.
        val NOMINAL_PLAN = TuningPlan(
            profileId = "performance",
            packageName = SystemWideProfileTransition.TARGET_PACKAGE_ID,
            cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 3_072_000),
            gpuMaxPowerLevel = 0,
            fanMode = TuningPlanner.FAN_SMART,
            calibrated = true,
        )
        val RUNG_PLAN = TuningPlan(
            profileId = "performance",
            packageName = SystemWideProfileTransition.TARGET_PACKAGE_ID,
            cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 2_841_600),
            gpuMaxPowerLevel = 2,
            fanMode = TuningPlanner.FAN_SMART,
            calibrated = true,
        )

        fun cpuNode(policyId: Int, original: Int) = TopologyNodeState(
            logicalId = "cpu:$policyId:scaling_max_freq",
            axis = TuningAxis.CPU,
            canonicalPath = HardwareProbe.CPU_QOS_MAX_PATH,
            originalValue = original,
            originalMode = "644",
            originalUid = 0,
            originalGid = 0,
            present = true,
        )

        fun mutation(node: TopologyNodeState, value: Int) = LogicalMutation(
            logicalId = node.logicalId,
            axis = node.axis,
            canonicalPath = node.canonicalPath,
            kind = if (node.axis == TuningAxis.FAN || node.axis == TuningAxis.VENDOR_MODE) {
                LogicalMutationKind.SETTINGS_PUT
            } else {
                LogicalMutationKind.SYSFS_WRITE
            },
            value = value,
            restoreMode = if (node.axis == TuningAxis.FAN || node.axis == TuningAxis.VENDOR_MODE) null else node.originalMode,
            restoreUid = if (node.axis == TuningAxis.FAN || node.axis == TuningAxis.VENDOR_MODE) null else node.originalUid,
            restoreGid = if (node.axis == TuningAxis.FAN || node.axis == TuningAxis.VENDOR_MODE) null else node.originalGid,
            expectPresent = true,
        )

        fun fixture(
            withActiveGuardian: Boolean,
            appliedPlan: TuningPlan = NOMINAL_PLAN,
        ): Fixture {
            val fingerprint = HardwareFingerprint.create(ODIN_CAPABILITIES)
            val nodes = listOf(
                cpuNode(0, Int.MAX_VALUE),
                cpuNode(6, Int.MAX_VALUE),
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
                TopologyNodeState(
                    logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                    axis = TuningAxis.FAN,
                    canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                    originalValue = 0,
                    originalMode = null,
                    originalUid = null,
                    originalGid = null,
                    present = true,
                ),
            ) + listOfNotNull(
                appliedPlan.vendorMode?.let {
                    TopologyNodeState(
                        logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
                        axis = TuningAxis.VENDOR_MODE,
                        canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
                        originalValue = 0,
                        originalMode = null,
                        originalUid = null,
                        originalGid = null,
                        present = true,
                    )
                },
            )
            val snapshot = HardwareSnapshot(
                schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
                hardwareFingerprint = fingerprint,
                bootIdentity = "boot-odin",
                nodes = nodes,
            )
            val hashes = checkNotNull(SnapshotIntegrity.bindHashes(appliedPlan, ODIN_CAPABILITIES, snapshot))
            val mutations = listOf(
                mutation(nodes[0], appliedPlan.cpuMaxKhz.getValue(0)),
                mutation(nodes[1], appliedPlan.cpuMaxKhz.getValue(6)),
                mutation(nodes[2], checkNotNull(appliedPlan.gpuMaxPowerLevel)),
                mutation(nodes[3], checkNotNull(appliedPlan.fanMode)),
            ) + listOfNotNull(nodes.getOrNull(4)?.let { mutation(it, checkNotNull(appliedPlan.vendorMode)) })
            val unsignedRecord = TransactionJournalRecord(
                schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5,
                generation = 7L,
                requestId = "req-7",
                transactionId = "tx-7",
                hardwareFingerprint = fingerprint,
                deviceProfile = CpuCeilingTransport.ODIN_3_PROFILE,
                planHash = PlanHash.of(appliedPlan),
                profileId = appliedPlan.profileId,
                targetPackage = appliedPlan.packageName,
                phase = TransactionPhase.APPLIED,
                creationTime = 1_000L,
                requestDeadline = 31_000L,
                leaseDeadline = 90_000L,
                bootIdentity = snapshot.bootIdentity,
                snapshot = snapshot,
                expectedTopologySha256 = hashes.first,
                snapshotSha256 = hashes.second,
                lastCompletedMutationBoundary = mutations.size,
                appliedPlan = PersistedTuningPlan.from(appliedPlan),
                applyOrderLogicalIds = mutations.map { it.logicalId },
                appliedMutationProjection = mutations.map(PersistedAppliedMutation::from),
            )
            // The Guardian has no vendor-mode entry kind yet (vmode-guardian lane); the derate flow does not
            // read entry coverage, so the fixture leaves that mutation out of the envelope.
            val restoreEntries = mutations.filter { it.axis != TuningAxis.VENDOR_MODE }.mapIndexed { ordinal, mutation ->
                val node = nodes.single { it.logicalId == mutation.logicalId }
                if (mutation.axis == TuningAxis.FAN) {
                    RestoreGuardianFanPutEntry(
                        applyOrdinal = ordinal,
                        expectedStock = RestoreGuardianStockState.PresentFanMode(
                            value = checkNotNull(node.originalValue),
                        ),
                        rendererAppliedIntermediates = listOf(checkNotNull(mutation.value)),
                    )
                } else {
                    RestoreGuardianSysfsEntry(
                        applyOrdinal = ordinal,
                        logicalId = mutation.logicalId,
                        canonicalSysfsPath = mutation.canonicalPath,
                        expectedStock = RestoreGuardianStockState.PresentSysfs(
                            value = checkNotNull(node.originalValue),
                            mode = node.originalMode ?: "644",
                            uid = node.originalUid ?: 0,
                            gid = node.originalGid ?: 0,
                        ),
                        rendererAppliedIntermediates = listOf(checkNotNull(mutation.value)),
                    )
                }
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
            val rig = DerateRig(record)
            val coordinator = TransactionCoordinator(
                planner = TuningPlanner { _, _ -> true },
                state = rig,
                commands = rig,
                journal = journal,
                hardwareWritesCompiled = true,
                clock = { 1_000L },
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
            return Fixture(coordinator, rig, journal)
        }
    }

    private data class Fixture(
        val coordinator: TransactionCoordinator,
        val rig: DerateRig,
        val journal: MemoryJournal,
    )

    private class MemoryJournal(initial: TransactionJournalRecord) : TransactionJournal {
        var record: TransactionJournalRecord? = initial
        var persists = 0
        var amends = 0
        var clears = 0

        /**
         * Either durable write. The counters are kept apart because WHICH lane a derate takes
         * is load-bearing: the durable journal refuses a changed applied projection through
         * [persist] and admits it only through [amendApplied], so a derate routed at persist
         * fails on device while passing against this accept-everything double.
         */
        val writes: Int get() = persists + amends

        override fun load(): JournalLoadResult = record?.let(JournalLoadResult::Loaded) ?: JournalLoadResult.Empty
        override fun nextGeneration() = GenerationResult.Ok(8L)
        override fun persist(record: TransactionJournalRecord): Boolean {
            persists++
            this.record = record
            return true
        }
        override fun amendApplied(record: TransactionJournalRecord): Boolean {
            amends++
            this.record = record
            return true
        }
        override fun clear(expectedTransactionId: String): Boolean {
            clears++
            record = null
            return true
        }
    }

    private class DerateRig(record: TransactionJournalRecord) : HardwareStatePort, HardwareCommandPort {
        val values = record.appliedMutationProjection.associate { it.logicalId to it.value }.toMutableMap()
        val executedLogicalIds = mutableListOf<String>()
        var failExecute = false
        var afterExecute: (() -> Unit)? = null

        override fun execute(mutation: LogicalMutation): CheckedCommandResult {
            executedLogicalIds += mutation.logicalId
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
