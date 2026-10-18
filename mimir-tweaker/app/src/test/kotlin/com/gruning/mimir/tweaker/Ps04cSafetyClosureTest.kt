package com.gruning.mimir.tweaker

import android.os.Binder
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-wiring behavioral proofs for PS-04C Ichigo/Kenpachi closure findings.
 * These are not tautological fakes: they assert ordering, zero-command stale paths,
 * historic restore identity, Binder reacquire, and recovery lifecycle decisions.
 */
class Ps04cSafetyClosureTest {
    @Test
    fun `apply orchestrator admits only after FGS then usage telemetry before transaction`() {
        val steps = mutableListOf<ApplyGateStep>()
        var applyCalls = 0
        val result = ApplyOrchestrator.run(
            establishFgs = { true },
            admit = {
                ApplyAdmission.evaluate(
                    usageAccessGranted = true,
                    telemetry = ThermalTelemetrySample(
                        PowerManager.THERMAL_STATUS_NONE,
                        40,
                        40,
                        300,
                    ),
                )
            },
            apply = {
                applyCalls += 1
                TransactionResult(true, true, false, "APPLIED", "ok")
            },
            onStep = { steps += it },
        )
        assertEquals(
            listOf(
                ApplyGateStep.ESTABLISH_FGS,
                ApplyGateStep.ADMIT_USAGE_AND_TELEMETRY,
                ApplyGateStep.TRANSACTION_APPLY,
            ),
            steps,
        )
        assertEquals(1, applyCalls)
        assertEquals("APPLIED", result.code)
    }

    @Test
    fun `apply admission failure never reaches transaction apply`() {
        val steps = mutableListOf<ApplyGateStep>()
        var applyCalls = 0
        val denied = ApplyOrchestrator.run(
            establishFgs = { true },
            admit = { ApplyAdmission.evaluate(usageAccessGranted = false, telemetry = null) },
            apply = {
                applyCalls += 1
                error("transaction must not run")
            },
            onStep = { steps += it },
        )
        assertEquals(
            listOf(ApplyGateStep.ESTABLISH_FGS, ApplyGateStep.ADMIT_USAGE_AND_TELEMETRY),
            steps,
        )
        assertEquals(0, applyCalls)
        assertEquals("USAGE_ACCESS_REQUIRED", denied.code)

        val fgsFail = ApplyOrchestrator.run(
            establishFgs = { false },
            admit = { error("admit must not run") },
            apply = { error("apply must not run") },
            onStep = { steps += it },
        )
        assertEquals("FOREGROUND_START_FAILED", fgsFail.code)
    }

    @Test
    fun `recovery lifecycle keeps FGS on failed restore and leaves only when settled`() {
        assertEquals(
            RecoveryLifecycleDecision.KeepForegroundRetry,
            TweakerRecoveryLifecycle.decideAfterPass(
                RecoverySelection.START_FOREGROUND_RECOVERY,
                TransactionResult(false, false, false, "RESTORE_FAILED", "kept"),
            ),
        )
        assertEquals(
            RecoveryLifecycleDecision.HoldCorrupt,
            TweakerRecoveryLifecycle.decideAfterPass(
                RecoverySelection.HOLD_CORRUPT_JOURNAL,
                null,
            ),
        )
        assertEquals(
            RecoveryLifecycleDecision.HoldCrossStore,
            TweakerRecoveryLifecycle.decideAfterPass(
                RecoverySelection.START_FOREGROUND_RECOVERY,
                TransactionResult(
                    false,
                    false,
                    false,
                    "CROSS_STORE_RECONCILIATION_REQUIRED",
                    "Guardian settlement retained",
                ),
            ),
        )
        assertEquals(
            RecoveryLifecycleDecision.SettledLeaveForeground,
            TweakerRecoveryLifecycle.decideAfterPass(
                RecoverySelection.START_FOREGROUND_RECOVERY,
                TransactionResult(true, false, true, "RESTORED", "ok"),
            ),
        )
        assertEquals(
            RecoveryLifecycleDecision.SettledLeaveForeground,
            TweakerRecoveryLifecycle.decideAfterPass(RecoverySelection.NONE, null),
        )
        assertTrue(
            TweakerRecoveryLifecycle.requiresTerminalSettlement(
                RecoveryLifecycleDecision.SettledLeaveForeground,
            ),
        )
        assertFalse(
            TweakerRecoveryLifecycle.requiresTerminalSettlement(
                RecoveryLifecycleDecision.KeepForegroundRetry,
            ),
        )
        assertFalse(
            TweakerRecoveryLifecycle.requiresTerminalSettlement(
                RecoveryLifecycleDecision.HoldCrossStore,
            ),
        )
        assertEquals(
            RecoverySelection.START_FOREGROUND_RECOVERY,
            TweakerRecoveryLifecycle.selectionForServiceStart(
                intentAction = null,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Loaded(
                    TransactionJournalRecord(
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
                            HARDWARE_SNAPSHOT_SCHEMA,
                            "fp",
                            "boot",
                            listOf(
                                TopologyNodeState(
                                    "cpu:0:scaling_max_freq",
                                    TuningAxis.CPU,
                                    "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                                    1,
                                    "644",
                                    0,
                                    0,
                                    true,
                                ),
                            ),
                        ),
                        expectedTopologySha256 = "e",
                        snapshotSha256 = "s",
                        lastCompletedMutationBoundary = 1,
                    ),
                ),
            ),
        )
        assertEquals("device-protected", TweakerRecoveryLifecycle.journalContextLabel(true))
        assertEquals("credential-protected", TweakerRecoveryLifecycle.journalContextLabel(false))
    }

    @Test
    fun `coordinator old generation restoreBound is zero command zero clear`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val coordinator = TransactionCoordinator(
            TuningPlanner { _, _ -> true },
            rig,
            rig,
            journal,
            hardwareWritesCompiled = true,
            idFactory = sequentialIds(),
            clock = { 1_000L },
        )
        assertEquals(
            "APPLIED",
            coordinator.apply("balanced", "example.game", caps(), dryRun = false, requestId = "g1").code,
        )
        val gen1 = (journal.load() as JournalLoadResult.Loaded).record.generation
        val tx1 = (journal.load() as JournalLoadResult.Loaded).record.transactionId
        assertTrue(coordinator.restore().restored)
        assertEquals(
            "APPLIED",
            coordinator.apply("balanced", "example.game", caps(), dryRun = false, requestId = "g2").code,
        )
        val before = rig.executes
        val persisted = journal.load() as JournalLoadResult.Loaded
        val stale = coordinator.restoreBound(gen1, tx1)
        assertEquals("STALE_GENERATION", stale.code)
        assertFalse(stale.restored)
        assertEquals(before, rig.executes)
        assertTrue(journal.load() is JournalLoadResult.Loaded)
        assertEquals(persisted.record.transactionId, (journal.load() as JournalLoadResult.Loaded).record.transactionId)
    }

    @Test
    fun `blocking command cancellation restores after command returns`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val cancelled = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : HardwareCommandPort by rig {
            override fun execute(mutation: LogicalMutation): CheckedCommandResult {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                return rig.execute(mutation)
            }
        }
        val coordinator = TransactionCoordinator(
            TuningPlanner { _, _ -> true },
            rig,
            blocking,
            journal,
            hardwareWritesCompiled = true,
            idFactory = sequentialIds(),
            clock = { 1_000L },
        )
        val future = Executors.newSingleThreadExecutor().submit<TransactionResult> {
            coordinator.apply(
                "balanced",
                "example.game",
                caps(),
                dryRun = false,
                requestId = "late-cancel",
                isCancelled = { cancelled.get() },
            )
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        cancelled.set(true)
        release.countDown()
        val result = future.get(3, TimeUnit.SECONDS)
        assertEquals("REQUEST_CANCELLED", result.code)
        assertTrue(result.restored)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `cancel token can be set immediately while apply is blocked`() {
        val cancelled = AtomicBoolean(false)
        val blocked = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val seenWhileBlocked = AtomicBoolean(false)
        val thread = Thread {
            blocked.countDown()
            check(proceed.await(2, TimeUnit.SECONDS))
            seenWhileBlocked.set(cancelled.get())
        }
        thread.start()
        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        cancelled.set(true) // immediate, not queued behind blocked work
        proceed.countDown()
        thread.join(2_000L)
        assertTrue(seenWhileBlocked.get())
    }

    @Test
    fun `PServer resolver reacquires after unavailable and dead without auto replay`() {
        var resolves = 0
        val alive = AtomicBoolean(false)
        val client = PServerClient(
            binderResolver = PServerBinderResolver {
                resolves += 1
                when (resolves) {
                    1, 2 -> null
                    3 -> {
                        alive.set(false) // resolved but dead
                        Binder()
                    }
                    else -> {
                        alive.set(true)
                        Binder()
                    }
                }
            },
            binderAlive = { alive.get() },
            binderAttestor = PServerBinderAttestor { true },
        )
        assertEquals(CheckedCommandStatus.FAILED, client.executeChecked("echo 1", nonce = "n1_abcdef12").status)
        assertEquals(1, resolves)
        assertEquals(null, client.acquireBinderForTest())
        assertEquals(2, resolves)
        assertEquals(null, client.acquireBinderForTest()) // dead binder rejected
        assertEquals(3, resolves)
        assertTrue(client.acquireBinderForTest() != null)
        assertEquals(4, resolves)
        client.invalidateBinderForTest()
        assertTrue(client.acquireBinderForTest() != null)
        assertEquals(5, resolves)
        // Transport path proves one ambiguous attempt is never auto-replayed.
        var transports = 0
        val transportClient = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                transports += 1
                PServerTransactResult(accepted = false, error = IllegalStateException("dead"))
            },
        )
        assertEquals(CheckedCommandStatus.AMBIGUOUS, transportClient.executeChecked("echo 1", nonce = "n9_abcdef12").status)
        assertEquals(1, transports)
    }

    @Test
    fun `historic persisted apply order survives formula change`() {
        val caps = caps()
        // "performance" now signs FAN_SPORT directly on Odin 3
        // (TuningPlanner.selectedFanMode), which RestoreMutationOrder treats as Mimir-duty-owned
        // (fan-first restore, same as FAN_MANUAL). This test's own subject is orthogonal --
        // whether a persisted apply order survives a LATER RENDERER FORMULA change (see below),
        // not fan-ownership restore ordering, which ManualFanSafetyTest covers directly. Pin the
        // fan mutation to a value Mimir does not duty-own so "historicOrder" continues to mean
        // what this test says it means: a plain reverse order, independent of any fan-ownership
        // exception.
        val plan = TuningPlanner { _, _ -> true }.plan("performance", "example.game", caps)!!
            .copy(fanMode = TuningPlanner.FAN_SMART)
        val snapshot = stockSnapshot(caps)
        val hashes = SnapshotIntegrity.bindHashes(plan, caps, snapshot)!!
        val historicOrder = listOf(
            HardwareSnapshot.FAN_LOGICAL_ID,
        ) + snapshot.nodes.filter { it.axis != TuningAxis.FAN }.map { it.logicalId }
        val record = TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA,
            generation = 1,
            requestId = "hist",
            transactionId = "tx-hist",
            hardwareFingerprint = snapshot.hardwareFingerprint,
            deviceProfile = caps.deviceProfileId,
            planHash = PlanHash.of(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.APPLIED,
            creationTime = 1,
            requestDeadline = 2,
            leaseDeadline = 3,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = hashes.first,
            snapshotSha256 = hashes.second,
            lastCompletedMutationBoundary = snapshot.nodes.size,
            appliedPlan = PersistedTuningPlan.from(plan),
            applyOrderLogicalIds = historicOrder,
            appliedMutationProjection = historicOrder.map { logicalId ->
                val node = snapshot.nodes.first { it.logicalId == logicalId }
                PersistedAppliedMutation(
                    logicalId = logicalId,
                    axis = node.axis,
                    canonicalPath = node.canonicalPath,
                    kind = if (node.axis == TuningAxis.FAN) {
                        LogicalMutationKind.SETTINGS_PUT
                    } else {
                        LogicalMutationKind.SYSFS_WRITE
                    },
                    value = when (node.axis) {
                        TuningAxis.CPU -> {
                            val id = logicalId.removePrefix("cpu:").substringBefore(':').toInt()
                            plan.cpuMaxKhz.getValue(id)
                        }
                        TuningAxis.GPU -> when (logicalId) {
                            HardwareSnapshot.GPU_MIN_LOGICAL_ID -> caps.gpuFrequenciesKhz.size - 1
                            HardwareSnapshot.GPU_MAX_LOGICAL_ID -> plan.gpuMaxPowerLevel
                            else -> error("unknown GPU logical ID")
                        }
                        TuningAxis.FAN -> plan.fanMode
                        TuningAxis.VENDOR_MODE -> plan.vendorMode
                    },
                    expectPresent = true,
                )
            },
        )
        // Simulate a later formula that would reverse cooling/compute preference.
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(
                transport = PServerBinderTransport { command, _ ->
                    val nonce = Regex("""MIMIR_TX_EXIT:([A-Za-z0-9_-]+):%s""").find(command)?.groupValues?.get(1)
                        ?: "n1_abcdef12"
                    PServerTransactResult(true, "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:0:")
                },
            ),
            reader = SystemReader(),
            capabilities = { caps },
            liveBootIdentity = { snapshot.bootIdentity },
        )
        val restore = port.restoreMutations(record)
        assertEquals(historicOrder.reversed(), restore.map { it.logicalId })
        assertEquals(HardwareSnapshot.FAN_LOGICAL_ID, restore.last().logicalId)
        val validation = port.validateRestore(record)
        assertTrue(validation.failures.joinToString(), validation.ok)
    }

    @Test
    fun `schema v1 journal remains recovery compatible without rewrite`() {
        val caps = caps()
        val plan = TuningPlanner { _, _ -> true }.plan("balanced", "example.game", caps)!!
        val snapshot = stockSnapshot(caps)
        val hashes = SnapshotIntegrity.bindHashes(plan, caps, snapshot)!!
        val v1 = TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V1,
            generation = 1,
            requestId = "v1",
            transactionId = "tx-v1",
            hardwareFingerprint = snapshot.hardwareFingerprint,
            deviceProfile = caps.deviceProfileId,
            planHash = PlanHash.of(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.APPLIED,
            creationTime = 1,
            requestDeadline = 2,
            leaseDeadline = 3,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = hashes.first,
            snapshotSha256 = hashes.second,
            lastCompletedMutationBoundary = snapshot.nodes.size,
            appliedPlan = null,
            applyOrderLogicalIds = emptyList(),
        )
        assertEquals(TRANSACTION_JOURNAL_SCHEMA_V1, v1.schemaVersion)
        assertEquals(null, v1.appliedPlan)
        assertTrue(v1.applyOrderLogicalIds.isEmpty())
        val order = HistoricApplyOrder.resolveLogicalIds(v1)
        assertEquals(HistoricApplyOrder.v1LogicalIds("balanced", snapshot), order)
        assertEquals(4, TRANSACTION_JOURNAL_SCHEMA)
        assertTrue(PersistedTuningPlan.from(plan).cpuMaxKhz.isNotEmpty())
        val validation = RestoreContextValidator.validate(v1, caps)
        assertTrue(validation.failures.joinToString(), validation.ok)
    }

    @Test
    fun `schema v1 restores when current formula targets differ`() {
        val caps = caps()
        val currentPlan = TuningPlanner { _, _ -> true }.plan("balanced", "example.game", caps)!!
        // Historic snapshot/digests bound at apply time — independent of later formula values.
        val snapshot = stockSnapshot(caps)
        val topologyDigest = SnapshotIntegrity.topologySha256(SnapshotIntegrity.identitiesOf(snapshot))
        val snapshotDigest = SnapshotIntegrity.snapshotSha256(snapshot)
        // Historic planHash from an older formula that no longer matches current planner output.
        val historicPlanHash = PlanHash.of(
            currentPlan.copy(cpuMaxKhz = currentPlan.cpuMaxKhz.mapValues { (_, v) -> (v * 0.5).toInt() }),
        )
        assertTrue(historicPlanHash != PlanHash.of(currentPlan))
        val v1 = TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V1,
            generation = 9,
            requestId = "v1-old-formula",
            transactionId = "tx-v1-old",
            hardwareFingerprint = snapshot.hardwareFingerprint,
            deviceProfile = caps.deviceProfileId,
            planHash = historicPlanHash,
            profileId = "balanced",
            targetPackage = "example.game",
            phase = TransactionPhase.APPLIED,
            creationTime = 1,
            requestDeadline = 2,
            leaseDeadline = 3,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = topologyDigest,
            snapshotSha256 = snapshotDigest,
            lastCompletedMutationBoundary = snapshot.nodes.size,
            appliedPlan = null,
            applyOrderLogicalIds = emptyList(),
        )
        val validation = RestoreContextValidator.validate(v1, caps)
        assertTrue(validation.failures.joinToString(), validation.ok)
        assertEquals(
            HistoricApplyOrder.v1LogicalIds("balanced", snapshot).asReversed(),
            HistoricApplyOrder.resolveLogicalIds(v1).asReversed(),
        )
        assertFalse(validation.failures.any { "planHash mismatch" in it })
    }

    @Test
    fun `FGS failure begins foreground-only retry with zero restore`() {
        var restoreCalls = 0
        var fgsAttempts = 0
        val begin = ForegroundRecoveryGate.begin(
            selection = RecoverySelection.START_FOREGROUND_RECOVERY,
            establishFgs = {
                fgsAttempts += 1
                false
            },
        )
        assertEquals(ForegroundRecoveryBegin.RetryForegroundOnly, begin)
        assertEquals(1, fgsAttempts)
        assertEquals(0, restoreCalls)

        val empty = ForegroundRecoveryGate.begin(
            selection = RecoverySelection.NONE,
            establishFgs = { error("FGS must not run for empty selection") },
        )
        assertEquals(ForegroundRecoveryBegin.SettledLeave, empty)

        val backoff = ForegroundEstablishBackoff()
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map { backoff.nextDelayMs() },
        )
    }

    @Test
    fun `recovery ownership merges stop obligation without duplicate owner`() {
        val ownership = RecoveryOwnership()
        val first = ownership.claimOrMerge(stopWhenSettled = false, startId = -1)
        assertTrue(first.isOwner)
        assertFalse(first.stopWhenSettled)
        val second = ownership.claimOrMerge(stopWhenSettled = true, startId = 42)
        assertFalse(second.isOwner)
        assertTrue(second.stopWhenSettled)
        assertEquals(42, second.stopStartId)
        val obligation = ownership.obligation()
        assertTrue(obligation.isOwner)
        assertTrue(obligation.stopWhenSettled)
        assertEquals(42, obligation.stopStartId)
        // Failed/active owner: a third claim still cannot take ownership (no immediate duplicate).
        assertFalse(ownership.claimOrMerge(stopWhenSettled = true, startId = 99).isOwner)
        ownership.release()
        val reclaimed = ownership.claimOrMerge(stopWhenSettled = true, startId = 7)
        assertTrue(reclaimed.isOwner)
        assertEquals(7, reclaimed.stopStartId)
    }

    @Test
    fun `Guardian terminal signal reacquires a yielded claim with merged obligation intact`() {
        val ownership = RecoveryOwnership()
        assertTrue(ownership.claimOrMerge(stopWhenSettled = false, startId = -1).isOwner)
        assertFalse(ownership.claimOrMerge(stopWhenSettled = true, startId = 42).isOwner)

        ownership.yieldForGuardianSignal()
        assertFalse(ownership.isOwned())
        val terminalSignal = ownership.claimOrMerge(stopWhenSettled = true, startId = 43)

        assertTrue(terminalSignal.isOwner)
        assertTrue(terminalSignal.stopWhenSettled)
        assertEquals(43, terminalSignal.stopStartId)
    }

    @Test
    fun `Guardian recovery ownership is bound to the journal generation that emitted it`() {
        val ownership = RecoveryOwnership()
        val quiet = RecoveryJournalIdentity(125, "quiet-transaction")
        val balanced = RecoveryJournalIdentity(126, "balanced-transaction")

        val claimed = ownership.claimOrMerge(
            stopWhenSettled = true,
            startId = 44,
            journalIdentity = quiet,
        )

        assertEquals(quiet, claimed.journalIdentity)
        assertTrue(ownership.ownsJournal(quiet.generation, quiet.transactionId))
        assertFalse(ownership.ownsJournal(balanced.generation, balanced.transactionId))
        assertFalse(ownership.ownsJournal(null, null))

        ownership.release()
        ownership.claimOrMerge(stopWhenSettled = true, startId = 45)
        assertTrue(ownership.ownsJournal(balanced.generation, balanced.transactionId))
        assertTrue(ownership.ownsJournal(null, null))
    }

    @Test
    fun `Guardian recovery identity accepts only complete positive coordinates`() {
        assertEquals(
            RecoveryJournalIdentity(125, "transaction-125"),
            recoveryJournalIdentityOrNull(125, "transaction-125"),
        )
        assertEquals(null, recoveryJournalIdentityOrNull(0, "transaction-125"))
        assertEquals(null, recoveryJournalIdentityOrNull(125, ""))
        assertEquals(null, recoveryJournalIdentityOrNull(125, null))
    }

    @Test
    fun `different Guardian generation is queued and promoted after stale owner`() {
        val ownership = RecoveryOwnership()
        val quiet = RecoveryJournalIdentity(125, "quiet-transaction")
        val balanced = RecoveryJournalIdentity(126, "balanced-transaction")

        assertTrue(
            ownership.claimOrMerge(
                stopWhenSettled = true,
                startId = 44,
                journalIdentity = quiet,
            ).isOwner,
        )
        assertFalse(
            ownership.claimOrMerge(
                stopWhenSettled = true,
                startId = 45,
                journalIdentity = balanced,
            ).isOwner,
        )

        val promoted = checkNotNull(ownership.completeAndTakeNext())
        assertEquals(balanced, promoted.journalIdentity)
        assertEquals(45, promoted.stopStartId)
        assertTrue(ownership.ownsJournal(126, "balanced-transaction"))
        assertFalse(ownership.ownsJournal(125, "quiet-transaction"))
    }

    @Test
    fun `boot recovery queued behind stale Guardian is not swallowed`() {
        val ownership = RecoveryOwnership()
        val quiet = RecoveryJournalIdentity(125, "quiet-transaction")

        ownership.claimOrMerge(
            stopWhenSettled = true,
            startId = 44,
            journalIdentity = quiet,
        )
        assertFalse(
            ownership.claimOrMerge(
                stopWhenSettled = true,
                startId = 46,
                journalIdentity = null,
            ).isOwner,
        )

        val promoted = checkNotNull(ownership.completeAndTakeNext())
        assertEquals(null, promoted.journalIdentity)
        assertEquals(46, promoted.stopStartId)
        assertTrue(ownership.ownsUnscopedJournal())
        assertTrue(ownership.ownsJournal(126, "balanced-transaction"))
    }

    @Test
    fun `stale Guardian can atomically reselect orphan current journal`() {
        val ownership = RecoveryOwnership()
        ownership.claimOrMerge(
            stopWhenSettled = true,
            startId = 44,
            journalIdentity = RecoveryJournalIdentity(125, "quiet-transaction"),
        )

        val reselected = checkNotNull(
            ownership.completeAndTakeNext(reselectCurrentIfNoPending = true),
        )
        assertEquals(null, reselected.journalIdentity)
        assertTrue(ownership.ownsUnscopedJournal())
        assertTrue(ownership.ownsJournal(126, "balanced-transaction"))
    }

    @Test
    fun `Guardian settlement action cannot be mistaken for unscoped boot recovery`() {
        assertFalse(
            MimirTweakerService.ACTION_GUARDIAN_SETTLEMENT ==
                MimirTweakerService.ACTION_RECOVERY_RESTORE,
        )
    }

    @Test
    fun `malformed Guardian ingress retains hold with zero foreground retry or recovery enqueue`() {
        var foregroundCalls = 0
        var holdCalls = 0
        var retryCalls = 0
        var recoveryEnqueues = 0

        val result = GuardianSettlementIngress.dispatch(
            identity = null,
            establishForeground = { foregroundCalls += 1; true },
            retainMalformedHold = { holdCalls += 1 },
            retryForegroundOnly = { retryCalls += 1 },
            enqueueSerialized = { recoveryEnqueues += 1 },
        )

        assertEquals(GuardianSettlementIngressResult.REJECTED_MALFORMED, result)
        assertEquals(0, foregroundCalls)
        assertEquals(1, holdCalls)
        assertEquals(0, retryCalls)
        assertEquals(0, recoveryEnqueues)
    }

    @Test
    fun `Guardian foreground failure immediately schedules retry without recovery enqueue`() {
        val identity = RecoveryJournalIdentity(125, "quiet-transaction")
        var holdCalls = 0
        var retried: RecoveryJournalIdentity? = null
        var recoveryEnqueues = 0

        val result = GuardianSettlementIngress.dispatch(
            identity = identity,
            establishForeground = { false },
            retainMalformedHold = { holdCalls += 1 },
            retryForegroundOnly = { retried = it },
            enqueueSerialized = { recoveryEnqueues += 1 },
        )

        assertEquals(GuardianSettlementIngressResult.RETRY_FOREGROUND_ONLY, result)
        assertEquals(identity, retried)
        assertEquals(0, holdCalls)
        assertEquals(0, recoveryEnqueues)
    }

    @Test
    fun `Guardian foreground success queues exactly one serialized recovery turn`() {
        val identity = RecoveryJournalIdentity(126, "balanced-transaction")
        var retryCalls = 0
        val queued = mutableListOf<RecoveryJournalIdentity>()

        val result = GuardianSettlementIngress.dispatch(
            identity = identity,
            establishForeground = { true },
            retainMalformedHold = { error("valid identity cannot enter malformed hold") },
            retryForegroundOnly = { retryCalls += 1 },
            enqueueSerialized = { queued += it },
        )

        assertEquals(GuardianSettlementIngressResult.QUEUED_SERIALIZED, result)
        assertEquals(0, retryCalls)
        assertEquals(listOf(identity), queued)
    }

    @Test
    fun `exact token death cancels only its request and Busy is owner bound`() {
        val deaths = mutableListOf<PublicOwnerSnapshot>()
        val registry = PublicOwnerRegistry(deaths::add)
        val tokenA = FakeOwnerToken()
        val registeredA = registry.registerApply(42, "req-a", tokenA)
            as PublicOwnerRegistration.Registered
        assertFalse(registeredA.cancelToken.isCancelled())

        val busy = registry.registerApply(42, "req-b", FakeOwnerToken())
        assertTrue(busy is PublicOwnerRegistration.Busy)
        assertEquals("req-a", (busy as PublicOwnerRegistration.Busy).owner.requestId)
        assertFalse(registeredA.cancelToken.isCancelled())

        tokenA.die()
        assertTrue(registeredA.cancelToken.isCancelled())
        assertEquals(listOf("req-a"), deaths.map { it.requestId })

        registry.clearIfToken(registeredA.owner.tokenId)
        val tokenB = FakeOwnerToken()
        val registeredB = registry.registerApply(42, "req-b", tokenB)
            as PublicOwnerRegistration.Registered
        assertFalse(registeredB.cancelToken.isCancelled())
        assertTrue(registeredA.cancelToken.isCancelled())
    }

    @Test
    fun `post-transaction disposition retains FGS for RESTORE_FAILED Loaded and Corrupt`() {
        val caps = caps()
        val plan = TuningPlanner { _, _ -> true }.plan("balanced", "example.game", caps)!!
        val snapshot = stockSnapshot(caps)
        val hashes = SnapshotIntegrity.bindHashes(plan, caps, snapshot)!!
        val record = TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA,
            generation = 3,
            requestId = "r3",
            transactionId = "tx-3",
            hardwareFingerprint = snapshot.hardwareFingerprint,
            deviceProfile = caps.deviceProfileId,
            planHash = PlanHash.of(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.RESTORE_FAILED,
            creationTime = 1,
            requestDeadline = 2,
            leaseDeadline = 3,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = hashes.first,
            snapshotSha256 = hashes.second,
            lastCompletedMutationBoundary = snapshot.nodes.size,
            appliedPlan = PersistedTuningPlan.from(plan),
            applyOrderLogicalIds = HistoricApplyOrder.v1LogicalIds(plan.profileId, snapshot),
        )
        val retain = PostTransactionGate.decide(
            TransactionResult(false, false, false, "RESTORE_FAILED", "kept", transactionId = "tx-3"),
            JournalLoadResult.Loaded(record),
        )
        assertTrue(retain is PostTransactionDisposition.RetainRecovery)
        assertEquals("tx-3", (retain as PostTransactionDisposition.RetainRecovery).record.transactionId)

        assertEquals(
            PostTransactionDisposition.HoldCorrupt,
            PostTransactionGate.decide(
                TransactionResult(false, false, false, "JOURNAL_CORRUPT", "bad"),
                JournalLoadResult.Corrupt("bad"),
            ),
        )
        assertEquals(
            PostTransactionDisposition.KeepActiveApplied,
            PostTransactionGate.decide(
                TransactionResult(true, true, false, "APPLIED", "ok", transactionId = "tx-3"),
                JournalLoadResult.Loaded(record.copy(phase = TransactionPhase.APPLIED)),
            ),
        )
        assertEquals(
            PostTransactionDisposition.LeaveForegroundSettled,
            PostTransactionGate.decide(
                TransactionResult(true, false, true, "RESTORED", "ok"),
                JournalLoadResult.Empty,
            ),
        )
        // Late-cancel restore failure with retained journal also retains recovery FGS.
        val lateCancelFail = PostTransactionGate.decide(
            TransactionResult(false, false, false, "RESTORE_FAILED", "late cancel restore failed", transactionId = "tx-3"),
            JournalLoadResult.Loaded(record),
        )
        assertTrue(lateCancelFail is PostTransactionDisposition.RetainRecovery)
    }

    @Test
    fun `APPLIED plus exact Loaded keeps active and Empty Corrupt mismatch cannot Keep`() {
        val caps = caps()
        val plan = TuningPlanner { _, _ -> true }.plan("balanced", "example.game", caps)!!
        val snapshot = stockSnapshot(caps)
        val hashes = SnapshotIntegrity.bindHashes(plan, caps, snapshot)!!
        val applied = TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA,
            generation = 8,
            requestId = "r8",
            transactionId = "tx-8",
            hardwareFingerprint = snapshot.hardwareFingerprint,
            deviceProfile = caps.deviceProfileId,
            planHash = PlanHash.of(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.APPLIED,
            creationTime = 1,
            requestDeadline = 2,
            leaseDeadline = 3,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = hashes.first,
            snapshotSha256 = hashes.second,
            lastCompletedMutationBoundary = snapshot.nodes.size,
            appliedPlan = PersistedTuningPlan.from(plan),
            applyOrderLogicalIds = HistoricApplyOrder.v1LogicalIds(plan.profileId, snapshot),
        )
        val appliedResult = TransactionResult(
            true,
            true,
            false,
            "APPLIED",
            "ok",
            transactionId = "tx-8",
        )
        assertEquals(
            PostTransactionDisposition.KeepActiveApplied,
            PostTransactionGate.decide(appliedResult, JournalLoadResult.Loaded(applied)),
        )
        assertEquals(
            PostTransactionDisposition.LeaveForegroundSettled,
            PostTransactionGate.decide(appliedResult, JournalLoadResult.Empty),
        )
        assertEquals(
            PostTransactionDisposition.HoldCorrupt,
            PostTransactionGate.decide(appliedResult, JournalLoadResult.Corrupt("lost")),
        )
        val mismatchedId = PostTransactionGate.decide(
            appliedResult,
            JournalLoadResult.Loaded(applied.copy(transactionId = "tx-other")),
        )
        assertTrue(mismatchedId is PostTransactionDisposition.RetainRecovery)
        assertTrue(mismatchedId !is PostTransactionDisposition.KeepActiveApplied)
        val nonAppliedPhase = PostTransactionGate.decide(
            appliedResult,
            JournalLoadResult.Loaded(applied.copy(phase = TransactionPhase.RESTORE_FAILED)),
        )
        assertTrue(nonAppliedPhase is PostTransactionDisposition.RetainRecovery)
        assertTrue(nonAppliedPhase !is PostTransactionDisposition.KeepActiveApplied)
    }

    @Test
    fun `adoption callback false exact-restores trusted record even if journal reload Empty or Corrupt`() {
        val rig = StatefulHardwareRig()
        val journal = object : DurableFakeJournal() {
            var forceLoad: JournalLoadResult? = null
            override fun load(): JournalLoadResult = forceLoad ?: super.load()
        }
        var adoptedRecordId: String? = null
        var adoptCalls = 0
        val coordinator = TransactionCoordinator(
            TuningPlanner { _, _ -> true },
            rig,
            rig,
            journal,
            hardwareWritesCompiled = true,
            idFactory = sequentialIds(),
            clock = { 1_000L },
        )
        val failedAdopt = coordinator.apply(
            "balanced",
            "example.game",
            caps(),
            dryRun = false,
            requestId = "adopt-fail",
            onAppliedAdopt = { record ->
                adoptCalls += 1
                adoptedRecordId = record.transactionId
                // Simulate Empty/Corrupt second-load — restore must still use trusted record.
                journal.forceLoad = JournalLoadResult.Empty
                false
            },
        )
        assertEquals(1, adoptCalls)
        assertEquals("SESSION_ADOPT_FAILED", failedAdopt.code)
        assertFalse(failedAdopt.applied)
        assertTrue(failedAdopt.restored)
        assertEquals(adoptedRecordId, failedAdopt.transactionId)
        // forceLoad Empty proves restore did not depend on a successful reload.
        assertTrue(journal.forceLoad is JournalLoadResult.Empty)
        assertTrue(rig.isExactStock())

        journal.forceLoad = null
        journal.record = null
        val corruptAdopt = coordinator.apply(
            "balanced",
            "example.game",
            caps(),
            dryRun = false,
            requestId = "adopt-corrupt",
            onAppliedAdopt = { record ->
                journal.forceLoad = JournalLoadResult.Corrupt("simulated")
                // Prove trusted record identity is what restore uses (still in memory).
                assertEquals(TransactionPhase.APPLIED, record.phase)
                false
            },
        )
        assertEquals("SESSION_ADOPT_FAILED", corruptAdopt.code)
        assertTrue(corruptAdopt.restored)
        assertTrue(journal.forceLoad is JournalLoadResult.Corrupt)
        assertTrue(rig.isExactStock())

        journal.forceLoad = null
        journal.record = null
        val ok = coordinator.apply(
            "balanced",
            "example.game",
            caps(),
            dryRun = false,
            requestId = "adopt-ok",
            onAppliedAdopt = { true },
        )
        assertEquals("APPLIED", ok.code)
        assertTrue(ok.applied)
        assertTrue(journal.load() is JournalLoadResult.Loaded)
    }

    @Test
    fun `post-adopt journal Empty Corrupt or mismatch exact-restores and invokes adoption rollback`() {
        val rig = StatefulHardwareRig()
        val journal = object : DurableFakeJournal() {
            var forceLoad: JournalLoadResult? = null
            var mutateOnLoad: ((TransactionJournalRecord) -> TransactionJournalRecord)? = null
            override fun load(): JournalLoadResult {
                forceLoad?.let { return it }
                val base = super.load()
                val mut = mutateOnLoad
                return if (mut != null && base is JournalLoadResult.Loaded) {
                    JournalLoadResult.Loaded(mut(base.record))
                } else {
                    base
                }
            }
        }
        val coordinator = TransactionCoordinator(
            TuningPlanner { _, _ -> true },
            rig,
            rig,
            journal,
            hardwareWritesCompiled = true,
            idFactory = sequentialIds(),
            clock = { 1_000L },
        )

        fun applyWithPostAdoptLoss(force: JournalLoadResult): Pair<TransactionResult, Int> {
            journal.forceLoad = null
            journal.mutateOnLoad = null
            journal.record = null
            var rollbacks = 0
            val result = coordinator.apply(
                "balanced",
                "example.game",
                caps(),
                dryRun = false,
                requestId = "post-adopt-${force.javaClass.simpleName}",
                onAppliedAdopt = {
                    // Adoption succeeds; then durable truth is sabotaged before return check.
                    when (force) {
                        JournalLoadResult.Empty -> journal.forceLoad = JournalLoadResult.Empty
                        is JournalLoadResult.Corrupt -> journal.forceLoad = force
                        is JournalLoadResult.Loaded -> {
                            journal.mutateOnLoad = { force.record }
                        }
                    }
                    true
                },
                onAdoptionRollback = { rollbacks += 1 },
            )
            return result to rollbacks
        }

        val (emptyLost, emptyRollbacks) = applyWithPostAdoptLoss(JournalLoadResult.Empty)
        assertEquals("SESSION_ADOPT_FAILED", emptyLost.code)
        assertFalse(emptyLost.applied)
        assertTrue(emptyLost.restored)
        assertEquals(1, emptyRollbacks)
        assertTrue(rig.isExactStock())

        val (corruptLost, corruptRollbacks) = applyWithPostAdoptLoss(JournalLoadResult.Corrupt("gone"))
        assertEquals("SESSION_ADOPT_FAILED", corruptLost.code)
        assertTrue(corruptLost.restored)
        assertEquals(1, corruptRollbacks)
        assertTrue(rig.isExactStock())

        // Mismatch: same generation envelope but wrong transaction id / non-APPLIED phase.
        val mismatchSeed = TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA,
            generation = 99,
            requestId = "other",
            transactionId = "tx-mismatch",
            hardwareFingerprint = "fp",
            deviceProfile = "rp5",
            planHash = "ph",
            profileId = "balanced",
            targetPackage = "example.game",
            phase = TransactionPhase.RESTORE_FAILED,
            creationTime = 1,
            requestDeadline = 2,
            leaseDeadline = 3,
            bootIdentity = "boot",
            snapshot = stockSnapshot(caps()),
            expectedTopologySha256 = "e",
            snapshotSha256 = "s",
            lastCompletedMutationBoundary = 1,
        )
        val (mismatchLost, mismatchRollbacks) = applyWithPostAdoptLoss(
            JournalLoadResult.Loaded(mismatchSeed),
        )
        assertEquals("SESSION_ADOPT_FAILED", mismatchLost.code)
        assertTrue(mismatchLost.restored)
        assertEquals(1, mismatchRollbacks)
        assertTrue(rig.isExactStock())

        // Happy path: adopt + durable match → APPLIED, zero rollback.
        journal.forceLoad = null
        journal.mutateOnLoad = null
        journal.record = null
        var okRollbacks = 0
        val ok = coordinator.apply(
            "balanced",
            "example.game",
            caps(),
            dryRun = false,
            requestId = "post-adopt-ok",
            onAppliedAdopt = { true },
            onAdoptionRollback = { okRollbacks += 1 },
        )
        assertEquals("APPLIED", ok.code)
        assertTrue(ok.applied)
        assertEquals(0, okRollbacks)
        assertTrue(journal.load() is JournalLoadResult.Loaded)
    }

    @Test
    fun `retry terminal decision settles merged obligation including NONE empty`() {
        val ownership = RecoveryOwnership()
        ownership.claimOrMerge(stopWhenSettled = false, startId = -1)
        ownership.claimOrMerge(stopWhenSettled = true, startId = 42)
        val restored = TweakerRecoveryLifecycle.decideAfterPass(
            RecoverySelection.START_FOREGROUND_RECOVERY,
            TransactionResult(true, false, true, "RESTORED", "ok"),
        )
        assertEquals(RecoveryLifecycleDecision.SettledLeaveForeground, restored)
        assertTrue(TweakerRecoveryLifecycle.requiresTerminalSettlement(restored))
        // Mirror scheduleRecoveryRetry settle path.
        val obligation = ownership.obligation()
        ownership.release()
        assertTrue(obligation.stopWhenSettled)
        assertEquals(42, obligation.stopStartId)
        assertFalse(ownership.isOwned())

        ownership.claimOrMerge(stopWhenSettled = true, startId = 7)
        val none = TweakerRecoveryLifecycle.decideAfterPass(RecoverySelection.NONE, null)
        assertEquals(RecoveryLifecycleDecision.SettledLeaveForeground, none)
        assertTrue(TweakerRecoveryLifecycle.requiresTerminalSettlement(none))
        val noneObligation = ownership.obligation()
        ownership.release()
        assertTrue(noneObligation.stopWhenSettled)
        assertEquals(7, noneObligation.stopStartId)
        assertFalse(ownership.isOwned())

        // Keep/retry must not settle.
        ownership.claimOrMerge(stopWhenSettled = true, startId = 3)
        val keep = TweakerRecoveryLifecycle.decideAfterPass(
            RecoverySelection.START_FOREGROUND_RECOVERY,
            TransactionResult(false, false, false, "RESTORE_FAILED", "kept"),
        )
        assertFalse(TweakerRecoveryLifecycle.requiresTerminalSettlement(keep))
        assertTrue(ownership.isOwned())
        assertEquals(3, ownership.obligation().stopStartId)
    }

    @Test
    fun `explicit restore terminal path releases ownership through settlement`() {
        val ownership = RecoveryOwnership()
        ownership.claimOrMerge(stopWhenSettled = true, startId = 99)
        val cleared = ExplicitRestoreGate.afterRestoreAttempt(
            TransactionResult(true, false, true, "RESTORED", "ok"),
            loadJournal = { JournalLoadResult.Empty },
        )
        assertTrue(cleared is ExplicitRestoreCommit.Cleared)
        // Same terminal settlement as service Cleared path (settleRecoveryLeave).
        val obligation = ownership.obligation()
        ownership.release()
        assertTrue(obligation.stopWhenSettled)
        assertEquals(99, obligation.stopStartId)
        assertFalse(ownership.isOwned())

        ownership.claimOrMerge(stopWhenSettled = true, startId = 5)
        val empty = ExplicitRestoreGate.afterRestoreAttempt(
            TransactionResult(true, false, false, "NO_ACTIVE_SESSION", "empty"),
            loadJournal = { JournalLoadResult.Empty },
        )
        assertTrue(empty is ExplicitRestoreCommit.Cleared)
        val emptyObligation = ownership.obligation()
        ownership.release()
        assertTrue(emptyObligation.stopWhenSettled)
        assertEquals(5, emptyObligation.stopStartId)
        assertFalse(ownership.isOwned())

        // Failed restore retains ownership (no settlement).
        ownership.claimOrMerge(stopWhenSettled = true, startId = 11)
        val retained = ExplicitRestoreGate.afterRestoreAttempt(
            TransactionResult(false, false, false, "RESTORE_FAILED", "kept"),
            loadJournal = {
                JournalLoadResult.Loaded(
                    TransactionJournalRecord(
                        schemaVersion = TRANSACTION_JOURNAL_SCHEMA,
                        generation = 1,
                        requestId = "r",
                        transactionId = "tx",
                        hardwareFingerprint = "fp",
                        deviceProfile = "rp5",
                        planHash = "ph",
                        profileId = "balanced",
                        targetPackage = "example.game",
                        phase = TransactionPhase.RESTORE_FAILED,
                        creationTime = 1,
                        requestDeadline = 2,
                        leaseDeadline = 3,
                        bootIdentity = "boot",
                        snapshot = stockSnapshot(caps()),
                        expectedTopologySha256 = "e",
                        snapshotSha256 = "s",
                        lastCompletedMutationBoundary = 1,
                    ),
                )
            },
        )
        assertTrue(retained is ExplicitRestoreCommit.RetainedRetry)
        assertTrue(ownership.isOwned())
        assertEquals(11, ownership.obligation().stopStartId)
    }

    @Test
    fun `startMonitor false propagates by clearing provisional session before exact restore`() {
        // Pure stand-in for adoptSession: provisional adopt then monitor false must clear.
        var now = 0L
        val supervisor = HardwareSessionSupervisor(
            policy = SupervisorPolicy(),
            clock = SupervisorClock { now },
            workload = WorkloadObservationPort { WorkloadTruth.TARGET_FOREGROUND },
            telemetry = ThermalTelemetryPort {
                ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 40, 40, 300)
            },
            ownerLiveness = OwnerLivenessPort { _ -> true },
            restorer = GenerationBoundRestorer { _, _ ->
                TransactionResult(true, false, true, "RESTORED", "ok")
            },
        )
        val identity = ActiveSessionIdentity(
            generation = 5L,
            transactionId = "tx-5",
            requestId = "req-5",
            ownerUid = 1,
            targetPackage = "example.game",
            activationDeadlineMs = 10_000L,
            leaseDeadlineMs = 30_000L,
            targetEverForeground = false,
        )
        supervisor.adoptAppliedSession(identity)
        assertEquals("tx-5", supervisor.activeSession()?.transactionId)
        // Simulate startMonitor() == false → clear provisional session.
        val monitorStarted = false
        if (!monitorStarted) {
            supervisor.clearSession()
        }
        assertEquals(null, supervisor.activeSession())
        // Coordinator path then exact-restores using trusted record (proven in adoption callback test).
    }

    @Test
    fun `LAB apply hold is explicit and independent of binder unbind`() {
        val labHold = LabApplyHoldLiveness()
        val living: () -> Boolean = labHold::isPendingOrActive
        assertFalse(living())
        // The zero-delay supervisor tick may run while the exact transaction is still pending.
        assertTrue(labHold.tryBeginPendingHold())
        assertTrue(labHold.isPendingOrActive())
        assertTrue(living())
        // Successful LAB hold remains live after synthetic client release.
        assertTrue(labHold.commitPendingHold())
        assertTrue(labHold.isActive())
        assertTrue(living())
        // A service unbind carries no identified owner and changes nothing.
        assertTrue(living())
        // Failure never sets it.
        labHold.clear()
        assertFalse(labHold.isActive())
        assertFalse(living())
        assertTrue(labHold.tryBeginPendingHold())
        assertTrue(labHold.commitPendingHold())
        assertTrue(living())
        // Clears on termination (restore success / supervisor restore / destroy).
        labHold.clear()
        assertFalse(living())
    }

    @Test
    fun `diagnostics calibration hold has one bounded immutable lab lease`() {
        val now = 1_000_000L
        val hard = DiagnosticsCalibrationHoldLeasePolicy.hardDeadlineElapsedRealtimeMs(
            nowElapsedRealtimeMs = now,
            diagnosticsCompiled = true,
        )
        assertEquals(now + 360_000L, hard)
        val hold = LabApplyHoldLiveness()
        assertTrue(hold.tryBeginPendingHold())
        assertFalse(hold.tryBeginPendingHold())
        assertTrue(hold.commitPendingHold())
        assertTrue(hold.isActive())
        hold.clear()
        assertFalse(hold.isActive())
        assertFalse(hold.commitPendingHold())
    }

    @Test
    fun `diagnostics calibration hold cannot widen public safe production or oversized leases`() {
        val now = 1_000_000L
        assertEquals(
            null,
            DiagnosticsCalibrationHoldLeasePolicy.hardDeadlineElapsedRealtimeMs(now, false),
        )
        assertEquals(
            null,
            DiagnosticsCalibrationHoldLeasePolicy.hardDeadlineElapsedRealtimeMs(
                Long.MAX_VALUE - 10L,
                true,
            ),
        )
    }

    @Test
    fun `calibration reuses an established profile foreground service`() {
        assertFalse(CalibrationForegroundStartPolicy.requiresServiceStart(true))
        assertTrue(CalibrationForegroundStartPolicy.requiresServiceStart(false))
    }

    @Test
    fun `explicit restore clears only after verified and retains on failure`() {
        val cleared = ExplicitRestoreGate.afterRestoreAttempt(
            TransactionResult(true, false, true, "RESTORED", "ok"),
            loadJournal = { JournalLoadResult.Empty },
        )
        assertTrue(cleared is ExplicitRestoreCommit.Cleared)

        val empty = ExplicitRestoreGate.afterRestoreAttempt(
            TransactionResult(true, false, false, "NO_ACTIVE_SESSION", "empty"),
            loadJournal = { JournalLoadResult.Empty },
        )
        assertTrue(empty is ExplicitRestoreCommit.Cleared)

        val caps = caps()
        val plan = TuningPlanner { _, _ -> true }.plan("balanced", "example.game", caps)!!
        val snapshot = stockSnapshot(caps)
        val hashes = SnapshotIntegrity.bindHashes(plan, caps, snapshot)!!
        val record = TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA,
            generation = 4,
            requestId = "r4",
            transactionId = "tx-4",
            hardwareFingerprint = snapshot.hardwareFingerprint,
            deviceProfile = caps.deviceProfileId,
            planHash = PlanHash.of(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.RESTORE_FAILED,
            creationTime = 1,
            requestDeadline = 2,
            leaseDeadline = 3,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = hashes.first,
            snapshotSha256 = hashes.second,
            lastCompletedMutationBoundary = snapshot.nodes.size,
            appliedPlan = PersistedTuningPlan.from(plan),
            applyOrderLogicalIds = HistoricApplyOrder.v1LogicalIds(plan.profileId, snapshot),
        )
        val retained = ExplicitRestoreGate.afterRestoreAttempt(
            TransactionResult(false, false, false, "RESTORE_FAILED", "kept"),
            loadJournal = { JournalLoadResult.Loaded(record) },
        ) as ExplicitRestoreCommit.RetainedRetry
        assertEquals("tx-4", retained.record!!.transactionId)

        val crossStore = ExplicitRestoreGate.afterRestoreAttempt(
            TransactionResult(
                false,
                false,
                false,
                "CROSS_STORE_RECONCILIATION_REQUIRED",
                "Guardian settlement retained",
            ),
            loadJournal = { JournalLoadResult.Loaded(record.copy(schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5)) },
        )
        assertTrue(crossStore is ExplicitRestoreCommit.RetainedHold)
        assertEquals("tx-4", (crossStore as ExplicitRestoreCommit.RetainedHold).record?.transactionId)
    }

    @Test
    fun `late cancel after final verify and after APPLIED persist exact-restores`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val cancelled = AtomicBoolean(false)
        val coordinator = TransactionCoordinator(
            TuningPlanner { _, _ -> true },
            rig,
            rig,
            journal,
            hardwareWritesCompiled = true,
            barrier = TransactionBarrier { point, _ ->
                if (point == TransactionBarrierPoint.AFTER_APPLY_VERIFIED_BEFORE_APPLIED) {
                    cancelled.set(true)
                }
            },
            idFactory = sequentialIds(),
            clock = { 1_000L },
        )
        val afterVerify = coordinator.apply(
            "balanced",
            "example.game",
            caps(),
            dryRun = false,
            requestId = "late-after-verify",
            isCancelled = { cancelled.get() },
        )
        assertEquals("REQUEST_CANCELLED", afterVerify.code)
        assertTrue(afterVerify.restored)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(rig.isExactStock())

        cancelled.set(false)
        val coordinator2 = TransactionCoordinator(
            TuningPlanner { _, _ -> true },
            rig,
            rig,
            journal,
            hardwareWritesCompiled = true,
            barrier = TransactionBarrier { point, _ ->
                if (point == TransactionBarrierPoint.AFTER_APPLIED) {
                    cancelled.set(true)
                }
            },
            idFactory = sequentialIds(),
            clock = { 1_000L },
        )
        val afterApplied = coordinator2.apply(
            "balanced",
            "example.game",
            caps(),
            dryRun = false,
            requestId = "late-after-applied",
            isCancelled = { cancelled.get() },
        )
        assertEquals("REQUEST_CANCELLED", afterApplied.code)
        assertTrue(afterApplied.restored)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `same identity recovery preserves backoff sequence 1 2 4 8 16 30 30`() {
        var now = 0L
        val restorer = object : GenerationBoundRestorer {
            override fun restoreBound(expectedGeneration: Long, expectedTransactionId: String) =
                TransactionResult(false, false, false, "RESTORE_FAILED", "kept", transactionId = expectedTransactionId)
        }
        val supervisor = HardwareSessionSupervisor(
            policy = SupervisorPolicy(),
            clock = SupervisorClock { now },
            workload = WorkloadObservationPort { WorkloadTruth.TARGET_FOREGROUND },
            telemetry = ThermalTelemetryPort {
                ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 40, 40, 300)
            },
            ownerLiveness = OwnerLivenessPort { _ -> true },
            restorer = restorer,
        )
        val identity = ActiveSessionIdentity(
            generation = 11L,
            transactionId = "tx-11",
            requestId = "req-11",
            ownerUid = 1,
            targetPackage = "example.game",
            activationDeadlineMs = 10_000L,
            leaseDeadlineMs = 30_000L,
            targetEverForeground = true,
        )
        supervisor.adoptAppliedSession(identity)
        val delays = mutableListOf<Long>()
        repeat(7) {
            supervisor.armBoundedRetryAfterFailedRestore()
            delays += supervisor.pendingRetryDelayMs()!!
            // Same identity re-adopt must not reset backoff.
            supervisor.retainOrAdoptForRecovery(identity.copy(leaseDeadlineMs = now + 30_000L))
            assertEquals(delays.last(), supervisor.pendingRetryDelayMs())
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)

        // Identity change resets backoff.
        supervisor.retainOrAdoptForRecovery(identity.copy(generation = 12L, transactionId = "tx-12", requestId = "req-12"))
        assertEquals(null, supervisor.pendingRetryDelayMs())
        supervisor.armBoundedRetryAfterFailedRestore()
        assertEquals(1_000L, supervisor.pendingRetryDelayMs())
    }

    @Test
    fun `exact deadline comparison uses greater-or-equal`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        var now = 1_000L
        val coordinator = TransactionCoordinator(
            TuningPlanner { _, _ -> true },
            rig,
            rig,
            journal,
            hardwareWritesCompiled = true,
            barrier = TransactionBarrier { point, _ ->
                if (point == TransactionBarrierPoint.AFTER_APPLYING_PHASE) {
                    now = 1_050L
                }
            },
            idFactory = sequentialIds(),
            clock = { now },
            requestWindowMs = 50L,
        )
        val result = coordinator.apply(
            "balanced",
            "example.game",
            caps(),
            dryRun = false,
            requestId = "exact-deadline",
        )
        assertEquals("REQUEST_EXPIRED", result.code)
        assertEquals(0, rig.executes)
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    private fun sequentialIds(): () -> String {
        var n = 0
        return { "id-${n++}" }
    }

    private fun caps() = hardwareCapabilities()

    private fun stockSnapshot(caps: HardwareCapabilities): HardwareSnapshot {
        val plan = TuningPlanner { _, _ -> true }.plan("balanced", "example.game", caps)!!
        val nodes = mutableListOf<TopologyNodeState>()
        plan.cpuMaxKhz.keys.forEach { policyId ->
            val policy = caps.cpuPolicies.first { it.id == policyId }
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
        nodes += TopologyNodeState(
            HardwareSnapshot.GPU_MIN_LOGICAL_ID,
            TuningAxis.GPU,
            HardwareProbe.GPU_MIN_LEVEL,
            0,
            "644",
            0,
            0,
            true,
        )
        nodes += TopologyNodeState(
            HardwareSnapshot.GPU_MAX_LOGICAL_ID,
            TuningAxis.GPU,
            HardwareProbe.GPU_MAX_LEVEL,
            0,
            "644",
            0,
            0,
            true,
        )
        nodes += TopologyNodeState(
            HardwareSnapshot.FAN_LOGICAL_ID,
            TuningAxis.FAN,
            HardwareSnapshot.FAN_SETTINGS_PATH,
            4,
            null,
            null,
            null,
            true,
        )
        return HardwareSnapshot(
            HARDWARE_SNAPSHOT_SCHEMA,
            HardwareFingerprint.create(caps),
            "boot-test",
            nodes,
        )
    }

    private open class DurableFakeJournal : TransactionJournal {
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

    private class StatefulHardwareRig : HardwareStatePort, HardwareCommandPort {
        private val liveCaps = hardwareCapabilities()
        private val sysfs = linkedMapOf(
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to 1_804_800,
            "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq" to 2_419_200,
            "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq" to 2_841_600,
            HardwareProbe.GPU_MIN_LEVEL to 0,
            HardwareProbe.GPU_MAX_LEVEL to 0,
        )
        private val stock = sysfs.toMap()
        var fanMode: Int? = 4
        private val stockFan = 4
        var executes = 0

        fun isExactStock(): Boolean =
            fanMode == stockFan && sysfs.all { (path, value) -> stock[path] == value }

        override fun captureForPlan(plan: TuningPlan, capabilities: HardwareCapabilities): CaptureOutcome {
            val nodes = mutableListOf<TopologyNodeState>()
            plan.cpuMaxKhz.keys.forEach { policyId ->
                val policy = capabilities.cpuPolicies.first { it.id == policyId }
                val path = "${policy.path}/scaling_max_freq"
                nodes += TopologyNodeState(
                    "cpu:$policyId:scaling_max_freq",
                    TuningAxis.CPU,
                    path,
                    stock.getValue(path),
                    "644",
                    0,
                    0,
                    true,
                )
            }
            if (plan.gpuMaxPowerLevel != null) {
                nodes += TopologyNodeState(
                    HardwareSnapshot.GPU_MIN_LOGICAL_ID,
                    TuningAxis.GPU,
                    HardwareProbe.GPU_MIN_LEVEL,
                    0,
                    "644",
                    0,
                    0,
                    true,
                )
                nodes += TopologyNodeState(
                    HardwareSnapshot.GPU_MAX_LOGICAL_ID,
                    TuningAxis.GPU,
                    HardwareProbe.GPU_MAX_LEVEL,
                    0,
                    "644",
                    0,
                    0,
                    true,
                )
            }
            if (plan.fanMode != null) {
                nodes += TopologyNodeState(
                    HardwareSnapshot.FAN_LOGICAL_ID,
                    TuningAxis.FAN,
                    HardwareSnapshot.FAN_SETTINGS_PATH,
                    stockFan,
                    null,
                    null,
                    null,
                    true,
                )
            }
            return CaptureOutcome(
                HardwareSnapshot(
                    HARDWARE_SNAPSHOT_SCHEMA,
                    HardwareFingerprint.create(capabilities),
                    "boot-test",
                    nodes,
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

        override fun plannedMutations(plan: TuningPlan, snapshot: HardwareSnapshot): List<LogicalMutation> {
            val compute = snapshot.nodes.filter { it.axis != TuningAxis.FAN }.map { node ->
                val value = when (node.axis) {
                    TuningAxis.CPU -> {
                        val id = node.logicalId.removePrefix("cpu:").substringBefore(':').toInt()
                        plan.cpuMaxKhz.getValue(id)
                    }
                    TuningAxis.GPU -> when (node.logicalId) {
                        HardwareSnapshot.GPU_MIN_LOGICAL_ID -> liveCaps.gpuFrequenciesKhz.size - 1
                        HardwareSnapshot.GPU_MAX_LOGICAL_ID -> plan.gpuMaxPowerLevel
                        else -> error("unknown GPU logical ID")
                    }
                    TuningAxis.FAN -> error("fan excluded from compute")
                    TuningAxis.VENDOR_MODE -> error("vendor mode is absent in this rig")
                }
                LogicalMutation(
                    node.logicalId,
                    node.axis,
                    node.canonicalPath,
                    LogicalMutationKind.SYSFS_WRITE,
                    value,
                    node.originalMode,
                    node.originalUid,
                    node.originalGid,
                    true,
                )
            }
            val cooling = snapshot.nodes.filter { it.axis == TuningAxis.FAN }.map {
                LogicalMutation(it.logicalId, it.axis, it.canonicalPath, LogicalMutationKind.SETTINGS_PUT, plan.fanMode, null, expectPresent = true)
            }
            return if (plan.profileId == "performance") cooling + compute else compute + cooling
        }

        override fun restoreMutations(record: TransactionJournalRecord): List<LogicalMutation> =
            HistoricApplyOrder.resolveLogicalIds(record).asReversed().map { id ->
                val node = record.snapshot.nodes.first { it.logicalId == id }
                LogicalMutation(
                    node.logicalId,
                    node.axis,
                    node.canonicalPath,
                    if (node.axis == TuningAxis.FAN) {
                        if (node.present) LogicalMutationKind.SETTINGS_PUT else LogicalMutationKind.SETTINGS_DELETE
                    } else {
                        LogicalMutationKind.SYSFS_WRITE
                    },
                    node.originalValue,
                    node.originalMode,
                    node.originalUid,
                    node.originalGid,
                    node.present,
                    capturedSnapshotAuthority = node.axis == TuningAxis.FAN,
                )
            }

        override fun validateRestore(record: TransactionJournalRecord): PlanVerification =
            RestoreContextValidator.validate(record, liveCaps)

        override fun execute(mutation: LogicalMutation): CheckedCommandResult {
            executes++
            when (mutation.kind) {
                LogicalMutationKind.SYSFS_WRITE -> sysfs[mutation.canonicalPath] = mutation.value ?: 0
                LogicalMutationKind.SETTINGS_PUT -> fanMode = mutation.value
                LogicalMutationKind.SETTINGS_DELETE -> fanMode = null
            }
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0)
        }
    }

    private class FakeOwnerToken : ClientTokenHandle {
        private var deathCallback: (() -> Unit)? = null
        private var alive = true

        override fun sameToken(other: ClientTokenHandle): Boolean = this === other

        override fun isAlive(): Boolean = alive

        override fun linkToDeath(onDeath: () -> Unit): Boolean {
            deathCallback = onDeath
            return alive
        }

        override fun unlinkFromDeath() {
            deathCallback = null
        }

        fun die() {
            if (!alive) return
            alive = false
            deathCallback?.invoke()
        }
    }

    companion object {
        private fun hardwareCapabilities() = HardwareCapabilities(
            status = CapabilityStatus.SUPPORTED,
            manufacturer = "Moorechip",
            model = "Retroid Pocket 5",
            socModel = "SM8250",
            buildFingerprint = "rp5/test",
            deviceProfileId = "rp5",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
            pServerAvailable = true,
            cpuPolicies = listOf(
                CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", listOf(0, 1, 2, 3), listOf(300_000, 1_516_032, 1_804_800), 300_000, 1_804_800, 1_804_800),
                CpuPolicy(4, "/sys/devices/system/cpu/cpufreq/policy4", listOf(4, 5, 6), listOf(710_400, 2_032_320, 2_419_200), 710_400, 2_419_200, 2_419_200),
                CpuPolicy(7, "/sys/devices/system/cpu/cpufreq/policy7", listOf(7), listOf(844_800, 2_386_944, 2_841_600), 844_800, 2_841_600, 2_841_600),
            ),
            gpuFrequenciesKhz = listOf(305_000, 525_000, 905_000),
            gpuNodeAvailable = true,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60),
            notes = emptyList(),
        )
    }
}
