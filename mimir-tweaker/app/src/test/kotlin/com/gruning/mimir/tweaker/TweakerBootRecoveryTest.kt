package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakerBootRecoveryTest {
    @Test
    fun `reboot with a system profile journal selects restore and never profile replay`() {
        val systemRecord = journalRecord().copy(
            profileId = "quiet",
            targetPackage = "",
        )

        assertEquals(
            RecoverySelection.START_FOREGROUND_RECOVERY,
            TweakerRecoveryPolicy.selectRecovery(
                RecoveryTrigger.BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Loaded(systemRecord),
            ),
        )
        assertEquals(
            MimirTweakerService.ACTION_RECOVERY_RESTORE,
            TweakerBootDispatchPolicy.plan(
                RecoveryTrigger.BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Loaded(systemRecord),
            )?.serviceAction,
        )
    }

    @Test
    fun `recovery predicate preserves durable truth in every posture`() {
        assertTrue(TweakerRecoveryPolicy.shouldStartRecovery(hardwareWritesCompiled = false, hasActiveJournal = true))
        assertFalse(TweakerRecoveryPolicy.shouldStartRecovery(hardwareWritesCompiled = true, hasActiveJournal = false))
        assertTrue(TweakerRecoveryPolicy.shouldStartRecovery(hardwareWritesCompiled = true, hasActiveJournal = true))
    }

    @Test
    fun `direct boot and package triggers preserve loaded and corrupt truth in every posture`() {
        for (writesCompiled in listOf(false, true)) {
            assertEquals(
                RecoverySelection.START_FOREGROUND_RECOVERY,
                TweakerRecoveryPolicy.selectRecovery(
                    RecoveryTrigger.LOCKED_BOOT_COMPLETED,
                    hardwareWritesCompiled = writesCompiled,
                    load = JournalLoadResult.Loaded(journalRecord()),
                ),
            )
            assertEquals(
                RecoverySelection.START_FOREGROUND_RECOVERY,
                TweakerRecoveryPolicy.selectRecovery(
                    RecoveryTrigger.USER_UNLOCKED,
                    hardwareWritesCompiled = writesCompiled,
                    load = JournalLoadResult.Loaded(journalRecord()),
                ),
            )
            assertEquals(
                RecoverySelection.HOLD_CORRUPT_JOURNAL,
                TweakerRecoveryPolicy.selectRecovery(
                    RecoveryTrigger.MY_PACKAGE_REPLACED,
                    hardwareWritesCompiled = writesCompiled,
                    load = JournalLoadResult.Corrupt("bad"),
                ),
            )
        }
    }

    @Test
    fun `schema v5 direct boot recovery holds instead of entering the retry loop`() {
        val decision = TweakerRecoveryLifecycle.decideAfterPass(
            RecoverySelection.START_FOREGROUND_RECOVERY,
            TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "CROSS_STORE_RECONCILIATION_REQUIRED",
                message = "Guardian-bound journal retained",
            ),
        )

        assertEquals(RecoveryLifecycleDecision.HoldCrossStore, decision)
        assertFalse(TweakerRecoveryLifecycle.requiresTerminalSettlement(decision))
    }

    @Test
    fun `receiver dispatch starts foreground hold for durable truth in every posture`() {
        for (writesCompiled in listOf(false, true)) {
            assertEquals(
                MimirTweakerService.ACTION_RECOVERY_RESTORE,
                TweakerBootDispatchPolicy.plan(
                    trigger = RecoveryTrigger.BOOT_COMPLETED,
                    hardwareWritesCompiled = writesCompiled,
                    load = JournalLoadResult.Loaded(journalRecord()),
                )?.serviceAction,
            )
            assertEquals(
                MimirTweakerService.ACTION_RECOVERY_RESTORE,
                TweakerBootDispatchPolicy.plan(
                    trigger = RecoveryTrigger.MY_PACKAGE_REPLACED,
                    hardwareWritesCompiled = writesCompiled,
                    load = JournalLoadResult.Corrupt("bad"),
                )?.serviceAction,
            )
        }
    }

    @Test
    fun `receiver dispatch does not start for empty journal in any posture`() {
        for (writesCompiled in listOf(false, true)) {
            assertNull(
                TweakerBootDispatchPolicy.plan(
                    trigger = RecoveryTrigger.BOOT_COMPLETED,
                    hardwareWritesCompiled = writesCompiled,
                    load = JournalLoadResult.Empty,
                ),
            )
        }
    }

    @Test
    fun `fps debt starts its own foreground recovery without Guardian authority`() {
        for (fpsLoad in listOf(
            FpsLimiterJournalLoad.Loaded(fpsRecord()),
            FpsLimiterJournalLoad.Corrupt("bad"),
        )) {
            val plan = TweakerBootDispatchPolicy.plan(
                trigger = RecoveryTrigger.USER_UNLOCKED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Empty,
                fpsLoad = fpsLoad,
            )

            assertEquals(MimirTweakerService.ACTION_FPS_RECOVERY_RESTORE, plan?.serviceAction)
            assertFalse(plan?.guardianRecoveryRequired == true)
        }
    }

    @Test
    fun `a game audio-focus journal alone starts the daemon on its own recovery action in every posture`() {
        // The app-op it records survives the reboot, so with no hardware or FPS journal the boot still has work to do.
        for (writesCompiled in listOf(false, true)) {
            for (trigger in listOf(
                RecoveryTrigger.LOCKED_BOOT_COMPLETED,
                RecoveryTrigger.BOOT_COMPLETED,
                RecoveryTrigger.USER_UNLOCKED,
                RecoveryTrigger.MY_PACKAGE_REPLACED,
            )) {
                val plan = TweakerBootDispatchPolicy.plan(
                    trigger = trigger,
                    hardwareWritesCompiled = writesCompiled,
                    load = JournalLoadResult.Empty,
                    audioJournalPresent = true,
                )

                assertEquals(
                    "$trigger writes=$writesCompiled",
                    MimirTweakerService.ACTION_GAME_AUDIO_FOCUS_RECOVERY_RESTORE,
                    plan?.serviceAction,
                )
                assertFalse("no Guardian authority", plan?.guardianRecoveryRequired == true)
            }
        }
    }

    @Test
    fun `no audio journal and nothing else still starts nothing, and a hardware journal still wins the action`() {
        assertNull(
            TweakerBootDispatchPolicy.plan(
                trigger = RecoveryTrigger.BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Empty,
                audioJournalPresent = false,
            ),
        )
        assertEquals(
            MimirTweakerService.ACTION_RECOVERY_RESTORE,
            TweakerBootDispatchPolicy.plan(
                trigger = RecoveryTrigger.BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Loaded(journalRecord()),
                audioJournalPresent = true,
            )?.serviceAction,
        )
        assertEquals(
            MimirTweakerService.ACTION_FPS_RECOVERY_RESTORE,
            TweakerBootDispatchPolicy.plan(
                trigger = RecoveryTrigger.BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Empty,
                fpsLoad = FpsLimiterJournalLoad.Loaded(fpsRecord()),
                audioJournalPresent = true,
            )?.serviceAction,
        )
    }

    @Test
    fun `active build flavor preserves durable receiver dispatch without authorizing profiles`() {
        val plan = TweakerBootDispatchPolicy.plan(
            trigger = RecoveryTrigger.MY_PACKAGE_REPLACED,
            hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
            load = JournalLoadResult.Loaded(journalRecord()),
        )

        assertEquals(MimirTweakerService.ACTION_RECOVERY_RESTORE, plan?.serviceAction)
        assertEquals(emptySet<String>(), TweakerPostureTruth.productQualifiedProfileIds())
    }

    @Test
    fun `schema v5 write posture starts Guardian before host recovery`() {
        val v5 = journalRecord().copy(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5,
            guardianBinding = GuardianJournalBinding(
                envelopeSha256 = "a".repeat(64),
                taskNonce = "b".repeat(32),
                helperBuildIdentity = "helper.1",
                tweakerCertificateSha256 = "c".repeat(64),
                hardDeadlineElapsedRealtimeMs = 10_000,
            ),
        )

        assertTrue(
            TweakerBootDispatchPolicy.plan(
                trigger = RecoveryTrigger.LOCKED_BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Loaded(v5),
            )?.guardianRecoveryRequired == true,
        )
        assertFalse(
            TweakerBootDispatchPolicy.plan(
                trigger = RecoveryTrigger.LOCKED_BOOT_COMPLETED,
                hardwareWritesCompiled = false,
                load = JournalLoadResult.Loaded(v5),
            )?.guardianRecoveryRequired == true,
        )
    }

    @Test
    fun `idempotent restore clears journal on success`() {
        val state = FakeState()
        val commands = FakeCommands()
        val journal = FakeJournal()
        val coordinator = TransactionCoordinator(
            planner = TuningPlanner { _, _ -> true },
            state = state,
            commands = commands,
            journal = journal,
            hardwareWritesCompiled = true,
            idFactory = { "tx-1" },
        )
        val applied = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "req")
        assertEquals("APPLIED", applied.code)
        assertTrue(journal.record != null)

        val restored = coordinator.restore()
        assertTrue(restored.restored)
        assertNull(journal.record)
        val again = coordinator.restore()
        assertEquals("NO_ACTIVE_SESSION", again.code)
        assertFalse(again.restored)
    }

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "Moorechip",
        model = "Retroid Pocket 5",
        socModel = "SM8250",
        buildFingerprint = "rp5/test",
        deviceProfileId = "rp5",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", listOf(0, 1, 2, 3), listOf(300_000, 1_804_800), 300_000, 1_804_800, 1_804_800),
            CpuPolicy(4, "/sys/devices/system/cpu/cpufreq/policy4", listOf(4, 5, 6), listOf(710_400, 2_419_200), 710_400, 2_419_200, 2_419_200),
            CpuPolicy(7, "/sys/devices/system/cpu/cpufreq/policy7", listOf(7), listOf(844_800, 2_841_600), 844_800, 2_841_600, 2_841_600),
        ),
        gpuFrequenciesKhz = listOf(305_000, 905_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    private fun journalRecord() = TransactionJournalRecord(
        schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V1,
        generation = 1,
        requestId = "r",
        transactionId = "t",
        hardwareFingerprint = "fp",
        deviceProfile = "rp5",
        planHash = "ph",
        profileId = "balanced",
        targetPackage = "example.game",
        phase = TransactionPhase.APPLIED,
        creationTime = 1,
        requestDeadline = 2,
        leaseDeadline = 3,
        bootIdentity = "boot",
        snapshot = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = "fp",
            bootIdentity = "boot",
            nodes = emptyList(),
        ),
        expectedTopologySha256 = "e",
        snapshotSha256 = "s",
        lastCompletedMutationBoundary = 1,
    )

    private fun fpsRecord() = FpsLimiterJournalRecord(
        generation = 1L,
        requestId = "mimir.fps.request",
        transactionId = "mimir.fps.tx",
        ownerUid = 1_000,
        packageIdentity = GameModeFpsPackageIdentity(
            packageName = "dev.eden.eden_emulator",
            userId = 0,
            uid = 10_123,
            versionCode = 7L,
            signingCertificateSha256 = "a".repeat(64),
        ),
        targetFps = 60,
        phase = FpsLimiterJournalPhase.APPLIED,
        createdAtElapsedRealtimeMs = 10L,
        hardDeadlineElapsedRealtimeMs = 9_000L,
        bootIdentity = "boot-1",
    )

    private class FakeState : HardwareStatePort {
        override fun captureForPlan(plan: TuningPlan, capabilities: HardwareCapabilities): CaptureOutcome {
            val nodes = mutableListOf<TopologyNodeState>()
            plan.cpuMaxKhz.keys.forEach { policyId ->
                val policy = capabilities.cpuPolicies.first { it.id == policyId }
                nodes += TopologyNodeState(
                    logicalId = "cpu:$policyId:scaling_max_freq",
                    axis = TuningAxis.CPU,
                    canonicalPath = "${policy.path}/scaling_max_freq",
                    originalValue = policy.maxKhz,
                    originalMode = "644",
                    originalUid = 0,
                    originalGid = 0,
                    present = true,
                )
            }
            if (plan.gpuMaxPowerLevel != null) {
                nodes += TopologyNodeState(
                    logicalId = HardwareSnapshot.GPU_MIN_LOGICAL_ID,
                    axis = TuningAxis.GPU,
                    canonicalPath = HardwareProbe.GPU_MIN_LEVEL,
                    originalValue = 0,
                    originalMode = "644",
                    originalUid = 0,
                    originalGid = 0,
                    present = true,
                )
                nodes += TopologyNodeState(
                    logicalId = HardwareSnapshot.GPU_MAX_LOGICAL_ID,
                    axis = TuningAxis.GPU,
                    canonicalPath = HardwareProbe.GPU_MAX_LEVEL,
                    originalValue = 0,
                    originalMode = "644",
                    originalUid = 0,
                    originalGid = 0,
                    present = true,
                )
            }
            if (plan.fanMode != null) {
                nodes += TopologyNodeState(
                    logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                    axis = TuningAxis.FAN,
                    canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                    originalValue = 4,
                    originalMode = null,
                    originalUid = null,
                    originalGid = null,
                    present = true,
                )
            }
            return CaptureOutcome(
                HardwareSnapshot(
                    schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
                    hardwareFingerprint = HardwareFingerprint.create(capabilities),
                    bootIdentity = "boot-test",
                    nodes = nodes,
                ),
            )
        }
        override fun verify(plan: TuningPlan): Boolean = true
        override fun verifyPlan(plan: TuningPlan): PlanVerification = PlanVerification(true)
        override fun verifyAppliedPlan(
            plan: TuningPlan,
            mutations: List<LogicalMutation>,
        ): PlanVerification = PlanVerification(true)
        override fun verifySnapshot(snapshot: HardwareSnapshot): Boolean = true
        override fun verifySnapshotPlan(snapshot: HardwareSnapshot): PlanVerification = PlanVerification(true)
        override fun admitCpuTargetsAfterCooling(plan: TuningPlan): PlanVerification = PlanVerification(true)
        override fun readback(mutation: LogicalMutation): Boolean = true
    }

    private class FakeCommands : HardwareCommandPort {
        override fun plannedMutations(plan: TuningPlan, snapshot: HardwareSnapshot): List<LogicalMutation> =
            snapshot.nodes.map { node ->
                val value = when (node.axis) {
                    TuningAxis.CPU -> {
                        val id = node.logicalId.removePrefix("cpu:").substringBefore(':').toInt()
                        plan.cpuMaxKhz.getValue(id)
                    }
                    TuningAxis.GPU -> when (node.logicalId) {
                        HardwareSnapshot.GPU_MIN_LOGICAL_ID -> 1
                        HardwareSnapshot.GPU_MAX_LOGICAL_ID -> plan.gpuMaxPowerLevel
                        else -> error("unknown GPU logical ID")
                    }
                    TuningAxis.FAN -> plan.fanMode
                    TuningAxis.VENDOR_MODE -> plan.vendorMode
                }
                LogicalMutation(
                    logicalId = node.logicalId,
                    axis = node.axis,
                    canonicalPath = node.canonicalPath,
                    kind = if (node.axis == TuningAxis.FAN) {
                        LogicalMutationKind.SETTINGS_PUT
                    } else {
                        LogicalMutationKind.SYSFS_WRITE
                    },
                    value = value,
                    restoreMode = node.originalMode,
                    restoreUid = node.originalUid,
                    restoreGid = node.originalGid,
                    expectPresent = true,
                )
            }

        override fun restoreMutations(record: TransactionJournalRecord): List<LogicalMutation> =
            plannedMutations(
                record.appliedPlan?.toTuningPlan()
                    ?: TuningPlan(record.profileId, "pkg", emptyMap(), null, null, true),
                record.snapshot,
            ).let { planned ->
                val order = HistoricApplyOrder.resolveLogicalIds(record).ifEmpty {
                    planned.map { it.logicalId }
                }
                order.asReversed().map { logicalId ->
                    val node = record.snapshot.nodes.first { it.logicalId == logicalId }
                    LogicalMutation(
                        logicalId = node.logicalId,
                        axis = node.axis,
                        canonicalPath = node.canonicalPath,
                        kind = if (node.axis == TuningAxis.FAN) {
                            if (node.present) LogicalMutationKind.SETTINGS_PUT else LogicalMutationKind.SETTINGS_DELETE
                        } else {
                            LogicalMutationKind.SYSFS_WRITE
                        },
                        value = node.originalValue,
                        restoreMode = node.originalMode,
                        restoreUid = node.originalUid,
                        restoreGid = node.originalGid,
                        expectPresent = node.present,
                        capturedSnapshotAuthority = node.axis == TuningAxis.FAN,
                    )
                }
            }

        override fun execute(mutation: LogicalMutation): CheckedCommandResult =
            CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0)

        override fun validateRestore(record: TransactionJournalRecord): PlanVerification =
            PlanVerification(true)
    }

    private class FakeJournal : TransactionJournal {
        var record: TransactionJournalRecord? = null
        private var generation = 0L
        override fun load(): JournalLoadResult =
            record?.let { JournalLoadResult.Loaded(it) } ?: JournalLoadResult.Empty
        override fun nextGeneration(): GenerationResult {
            generation += 1
            return GenerationResult.Ok(generation)
        }
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

    @Test
    fun `a post-control hold marker alone starts a writes-compiled daemon on its own release action, once unlocked`() {
        // the hold's fan_mode 6 is a persistent setting, so a reboot mid-hold
        // must start the daemon that releases it -- a daemon that CAN release it, after the user has
        // unlocked (the service reads nothing before unlock; USER_UNLOCKED comes on its own).
        for (trigger in listOf(
            RecoveryTrigger.BOOT_COMPLETED,
            RecoveryTrigger.USER_UNLOCKED,
            RecoveryTrigger.MY_PACKAGE_REPLACED,
        )) {
            val plan = TweakerBootDispatchPolicy.plan(
                trigger = trigger,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Empty,
                postControlHoldPresent = true,
            )
            assertEquals("$trigger", MimirTweakerService.ACTION_POST_CONTROL_HOLD_RELEASE, plan?.serviceAction)
            assertFalse("no Guardian authority", plan?.guardianRecoveryRequired == true)
            // A build that cannot write the release is not started over the marker at all.
            assertNull(
                "$trigger read-only",
                TweakerBootDispatchPolicy.plan(
                    trigger = trigger,
                    hardwareWritesCompiled = false,
                    load = JournalLoadResult.Empty,
                    postControlHoldPresent = true,
                ),
            )
        }
        assertNull(
            "before unlock the marker defers to USER_UNLOCKED",
            TweakerBootDispatchPolicy.plan(
                trigger = RecoveryTrigger.LOCKED_BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Empty,
                postControlHoldPresent = true,
            ),
        )
        // Any other obligation starts the daemon anyway and its create releases the hold: those actions keep precedence.
        assertEquals(
            MimirTweakerService.ACTION_GAME_AUDIO_FOCUS_RECOVERY_RESTORE,
            TweakerBootDispatchPolicy.plan(
                trigger = RecoveryTrigger.BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Empty,
                audioJournalPresent = true,
                postControlHoldPresent = true,
            )?.serviceAction,
        )
        assertEquals(
            MimirTweakerService.ACTION_RECOVERY_RESTORE,
            TweakerBootDispatchPolicy.plan(
                trigger = RecoveryTrigger.BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Loaded(journalRecord()),
                postControlHoldPresent = true,
            )?.serviceAction,
        )
        assertNull(
            TweakerBootDispatchPolicy.plan(
                trigger = RecoveryTrigger.BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Empty,
                postControlHoldPresent = false,
            ),
        )
    }
}
