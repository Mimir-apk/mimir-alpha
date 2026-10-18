package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartedServiceLifetimeTest {
    @Test
    fun `pending admission is bounded consumed once and becomes active`() {
        val lifetime = StartedServiceLifetime()
        val pending = lifetime.beginPending(startId = 7, nowElapsedRealtimeMs = 1_000, graceMs = 10_000)

        assertEquals(StartedServiceLifetime.Phase.PENDING, pending.phase)
        assertEquals(11_000L, pending.pendingDeadlineElapsedRealtimeMs)
        assertEquals(
            StartedServiceLifetime.ApplyAdmission.Admitted(7),
            lifetime.admitApply(nowElapsedRealtimeMs = 10_999),
        )
        assertEquals(
            StartedServiceLifetime.ApplyAdmission.Busy,
            lifetime.admitApply(nowElapsedRealtimeMs = 10_999),
        )
        assertTrue(lifetime.markActive(expectedStartId = 7))
        assertEquals(StartedServiceLifetime.Phase.ACTIVE, lifetime.snapshot().phase)
    }

    @Test
    fun `apply without a valid pending start fails before ownership can begin`() {
        val lifetime = StartedServiceLifetime()

        assertEquals(
            StartedServiceLifetime.ApplyAdmission.NotStarted,
            lifetime.admitApply(nowElapsedRealtimeMs = 1_000),
        )
        lifetime.beginPending(startId = 3, nowElapsedRealtimeMs = 1_000, graceMs = 10_000)
        assertEquals(
            StartedServiceLifetime.ApplyAdmission.Expired,
            lifetime.admitApply(nowElapsedRealtimeMs = 11_000),
        )
        assertTrue(lifetime.snapshot().isIdle)
    }

    @Test
    fun `stale pending timeout cannot clear a newer start window`() {
        val lifetime = StartedServiceLifetime()
        val old = lifetime.beginPending(startId = 4, nowElapsedRealtimeMs = 1_000, graceMs = 10_000)
        val current = lifetime.beginPending(startId = 5, nowElapsedRealtimeMs = 2_000, graceMs = 10_000)

        assertFalse(
            lifetime.expirePending(
                expectedStartId = 4,
                expectedDeadlineElapsedRealtimeMs = checkNotNull(old.pendingDeadlineElapsedRealtimeMs),
                nowElapsedRealtimeMs = 11_000,
            ),
        )
        assertEquals(StartedServiceLifetime.Phase.PENDING, lifetime.snapshot().phase)
        assertEquals(5, lifetime.snapshot().phaseStartId)
        assertTrue(
            lifetime.expirePending(
                expectedStartId = 5,
                expectedDeadlineElapsedRealtimeMs = checkNotNull(current.pendingDeadlineElapsedRealtimeMs),
                nowElapsedRealtimeMs = 12_000,
            ),
        )
        assertTrue(lifetime.snapshot().isIdle)
        assertEquals(5, lifetime.snapshot().latestStartId)
    }

    @Test
    fun `new start id cannot replace an applying generation`() {
        val lifetime = StartedServiceLifetime()
        lifetime.beginPending(startId = 8, nowElapsedRealtimeMs = 1_000, graceMs = 10_000)
        assertEquals(
            StartedServiceLifetime.ApplyAdmission.Admitted(8),
            lifetime.admitApply(nowElapsedRealtimeMs = 2_000),
        )
        val whileApplying =
            lifetime.beginPending(startId = 9, nowElapsedRealtimeMs = 3_000, graceMs = 10_000)

        assertEquals(StartedServiceLifetime.Phase.APPLYING, whileApplying.phase)
        assertTrue(lifetime.markActive(expectedStartId = 8))
        assertEquals(StartedServiceLifetime.Phase.ACTIVE, lifetime.snapshot().phase)
        assertEquals(8, lifetime.snapshot().phaseStartId)
        assertEquals(9, lifetime.snapshot().latestStartId)
    }

    @Test
    fun `loaded or corrupt recovery remains nonterminal until explicitly settled`() {
        val lifetime = StartedServiceLifetime()
        lifetime.recordStart(11)
        lifetime.markRecovery(startId = 11)

        assertEquals(StartedServiceLifetime.Phase.RECOVERY, lifetime.snapshot().phase)
        assertFalse(lifetime.snapshot().isIdle)
        assertTrue(lifetime.settleActiveOrRecovery())
        assertTrue(lifetime.snapshot().isIdle)
        assertEquals(11, lifetime.snapshot().latestStartId)
    }

    @Test
    fun `stale recovery start returns to active without retiring replacement lifetime`() {
        val lifetime = StartedServiceLifetime()
        lifetime.beginPending(startId = 12, nowElapsedRealtimeMs = 1_000, graceMs = 10_000)
        assertEquals(
            StartedServiceLifetime.ApplyAdmission.Admitted(12),
            lifetime.admitApply(nowElapsedRealtimeMs = 2_000),
        )
        assertTrue(lifetime.markActive(expectedStartId = 12))

        lifetime.recordStart(13)
        lifetime.markRecovery(startId = 13)
        assertTrue(lifetime.retainActiveAfterStaleRecovery())

        val retained = lifetime.snapshot()
        assertEquals(StartedServiceLifetime.Phase.ACTIVE, retained.phase)
        assertEquals(13, retained.phaseStartId)
        assertEquals(13, retained.latestStartId)
        assertFalse(lifetime.retainActiveAfterStaleRecovery())
    }

    @Test
    fun `delayed Guardian start cannot overwrite active lifetime before journal validation`() {
        val lifetime = StartedServiceLifetime()
        lifetime.beginPending(startId = 12, nowElapsedRealtimeMs = 1_000, graceMs = 10_000)
        assertEquals(
            StartedServiceLifetime.ApplyAdmission.Admitted(12),
            lifetime.admitApply(nowElapsedRealtimeMs = 2_000),
        )
        assertTrue(lifetime.markActive(expectedStartId = 12))

        // onStart records Android's newer start obligation, but the bound Guardian path now
        // waits for exact journal validation on the serialized transaction worker before it
        // may call markRecovery().
        lifetime.recordStart(13)

        val retained = lifetime.snapshot()
        assertEquals(StartedServiceLifetime.Phase.ACTIVE, retained.phase)
        assertEquals(12, retained.phaseStartId)
        assertEquals(13, retained.latestStartId)
    }

    @Test
    fun `loaded and corrupt journals require started recovery in every posture`() {
        assertFalse(durableJournalRequiresStartedRecovery(JournalLoadResult.Empty))
        assertTrue(durableJournalRequiresStartedRecovery(JournalLoadResult.Corrupt("bad")))
        assertTrue(
            durableJournalRequiresStartedRecovery(
                JournalLoadResult.Loaded(journalRecord()),
            ),
        )
    }

    @Test
    fun `durable journal outranks active-session admission action`() {
        assertEquals(
            DurableStartedActionPrecedence.CONTINUE_REQUESTED_ACTION,
            durableStartedActionPrecedence(
                load = JournalLoadResult.Empty,
                hardwareWritesCompiled = true,
            ),
        )
        assertEquals(
            DurableStartedActionPrecedence.START_FOREGROUND_RECOVERY,
            durableStartedActionPrecedence(
                load = JournalLoadResult.Loaded(journalRecord()),
                hardwareWritesCompiled = true,
            ),
        )
        assertEquals(
            DurableStartedActionPrecedence.RETAIN_FOREGROUND_HOLD,
            durableStartedActionPrecedence(
                load = JournalLoadResult.Loaded(journalRecord()),
                hardwareWritesCompiled = false,
            ),
        )
        for (writesCompiled in listOf(false, true)) {
            assertEquals(
                DurableStartedActionPrecedence.RETAIN_FOREGROUND_HOLD,
                durableStartedActionPrecedence(
                    load = JournalLoadResult.Corrupt("bad"),
                    hardwareWritesCompiled = writesCompiled,
                ),
            )
        }
    }

    @Test
    fun `safe durable entry retains foreground hold with zero recovery worker commands`() {
        var recoveryWorkerCalls = 0
        var holdCalls = 0

        val handled = DurableStartedActionDispatch.dispatch(
            precedence = durableStartedActionPrecedence(
                load = JournalLoadResult.Loaded(journalRecord()),
                hardwareWritesCompiled = false,
            ),
            startForegroundRecovery = { recoveryWorkerCalls++ },
            retainForegroundHold = { holdCalls++ },
        )

        assertTrue(handled)
        assertEquals(0, recoveryWorkerCalls)
        assertEquals(1, holdCalls)
    }

    @Test
    fun `empty journal continues requested active-session action without recovery callback`() {
        var recoveryWorkerCalls = 0
        var holdCalls = 0

        val handled = DurableStartedActionDispatch.dispatch(
            precedence = durableStartedActionPrecedence(
                load = JournalLoadResult.Empty,
                hardwareWritesCompiled = false,
            ),
            startForegroundRecovery = { recoveryWorkerCalls++ },
            retainForegroundHold = { holdCalls++ },
        )

        assertFalse(handled)
        assertEquals(0, recoveryWorkerCalls)
        assertEquals(0, holdCalls)
    }

    @Test
    fun `foreground hold failure schedules retry without recovery commands`() {
        var foregroundAttempts = 0
        var retrySchedules = 0

        val established = DurableForegroundHoldGate.establishOrRetry(
            establishForeground = {
                foregroundAttempts++
                false
            },
            scheduleForegroundOnlyRetry = { retrySchedules++ },
        )

        assertFalse(established)
        assertEquals(1, foregroundAttempts)
        assertEquals(1, retrySchedules)
    }

    @Test
    fun `successful foreground hold does not schedule retry`() {
        var retrySchedules = 0

        assertTrue(
            DurableForegroundHoldGate.establishOrRetry(
                establishForeground = { true },
                scheduleForegroundOnlyRetry = { retrySchedules++ },
            ),
        )
        assertEquals(0, retrySchedules)
    }

    @Test
    fun `terminal gate stops only latest start after every obligation clears`() {
        val clear = terminalTruth()
        assertEquals(12, StartedServiceTerminalGate.stopStartIdOrNull(clear, latestStartId = 12))
        assertNull(StartedServiceTerminalGate.stopStartIdOrNull(clear, latestStartId = null))

        val blockers = listOf(
            clear.copy(journalEmpty = false),
            clear.copy(publicOwnerAbsent = false),
            clear.copy(supervisorSessionAbsent = false),
            clear.copy(monitorAbsent = false),
            clear.copy(recoveryOwnerAbsent = false),
            clear.copy(restoreRetryAbsent = false),
            clear.copy(foregroundRetryAbsent = false),
            clear.copy(lifetimeIdle = false),
            clear.copy(labHoldInactive = false),
            clear.copy(calibrationInactive = false),
            clear.copy(transactionWorkTerminal = false),
            clear.copy(postControlWatchInactive = false),
            clear.copy(fpsSessionIdle = false),
            clear.copy(postControlHoldInactive = false),
        )
        blockers.forEach { truth ->
            assertNull(StartedServiceTerminalGate.stopStartIdOrNull(truth, latestStartId = 12))
        }
    }

    /** The settlement thread's snapshot of the lifetime, folded into the truth the way the service folds it. */
    private fun truthFor(snapshot: StartedServiceLifetime.Snapshot) =
        terminalTruth().copy(lifetimeIdle = snapshot.isIdle)

    // Stop/start race: the lifetime is snapshotted on the settlement thread, and the stop is posted to main.
    // onStartCommand runs on main and records the start BEFORE it begins the pending phase, so a snapshot taken in
    // between reads IDLE with latestStartId == N+1 -- and stopSelfResult(N+1) would be accepted by Android. The
    // decision is therefore taken again on main; this pins the exact interleaving.
    @Test
    fun `a start recorded but not yet pending is never stopped once its pending phase begins`() {
        val lifetime = StartedServiceLifetime()
        lifetime.recordStart(6)

        // Settlement thread: idle, empty journal, no owner, no work -- the stop is planned for start 6.
        val atSnapshot = lifetime.snapshot()
        assertTrue(atSnapshot.isIdle)
        val planned = StartedServiceTerminalGate.stopStartIdOrNull(truthFor(atSnapshot), atSnapshot.latestStartId)
        assertEquals(6, planned)

        // Main thread: onStartCommand finishes and begins the pending phase before the post runs.
        lifetime.beginPending(startId = 6, nowElapsedRealtimeMs = 1_000, graceMs = 10_000)
        val now = lifetime.snapshot()

        assertFalse(
            "the stop was planned against IDLE and must not run once the start is pending",
            StartedServiceTerminalGate.mayStopNow(
                plannedStartId = checkNotNull(planned),
                truthNow = truthFor(now),
                latestStartIdNow = now.latestStartId,
            ),
        )
    }

    @Test
    fun `a newer start recorded after the plan refuses the stale stop id`() {
        val lifetime = StartedServiceLifetime()
        lifetime.recordStart(6)
        val atSnapshot = lifetime.snapshot()
        val planned = checkNotNull(
            StartedServiceTerminalGate.stopStartIdOrNull(truthFor(atSnapshot), atSnapshot.latestStartId),
        )

        lifetime.recordStart(7)
        val now = lifetime.snapshot()

        assertFalse(
            StartedServiceTerminalGate.mayStopNow(planned, truthFor(now), now.latestStartId),
        )
    }

    @Test
    fun `an FPS session that began after the plan refuses the stop`() {
        val lifetime = StartedServiceLifetime()
        lifetime.recordStart(6)
        val atSnapshot = lifetime.snapshot()
        val planned = checkNotNull(
            StartedServiceTerminalGate.stopStartIdOrNull(truthFor(atSnapshot), atSnapshot.latestStartId),
        )

        assertFalse(
            StartedServiceTerminalGate.mayStopNow(
                plannedStartId = planned,
                truthNow = truthFor(atSnapshot).copy(fpsSessionIdle = false),
                latestStartIdNow = 6,
            ),
        )
    }

    @Test
    fun `an unchanged idle service still stops the planned start`() {
        val lifetime = StartedServiceLifetime()
        lifetime.recordStart(6)
        val now = lifetime.snapshot()

        assertTrue(StartedServiceTerminalGate.mayStopNow(6, truthFor(now), now.latestStartId))
    }

    // without this field, every other obligation in
    // `terminalTruth()` is already satisfied moments after a genuine Restore (empty journal, no
    // owner, no session, monitor stopped, nothing else pending) -- the service would self-stop in
    // the SAME settlement pass that just armed PostControlThermalWatch, killing it before it ever
    // gets a tick. This test pins the default alone (a caller that never sets it -- every
    // pre-existing call site in this file -- must keep today's behavior unchanged).
    @Test
    fun `postControlWatchInactive defaults true so every pre-existing caller is unaffected`() {
        assertTrue(terminalTruth().postControlWatchInactive)
    }

    @Test
    fun `terminal stop confirms latest start before foreground removal`() {
        val calls = mutableListOf<String>()

        assertTrue(
            StartedServiceStopExecutor.stopLatest(
                latestStartId = 14,
                stopSelfResult = {
                    calls += "stop:$it"
                    true
                },
                leaveForeground = { calls += "foreground" },
            ),
        )
        assertEquals(listOf("stop:14", "foreground"), calls)
    }

    @Test
    fun `rejected stale stop keeps foreground for newer start`() {
        val calls = mutableListOf<String>()

        assertFalse(
            StartedServiceStopExecutor.stopLatest(
                latestStartId = 14,
                stopSelfResult = {
                    calls += "stop:$it"
                    false
                },
                leaveForeground = { calls += "foreground" },
            ),
        )
        assertEquals(listOf("stop:14"), calls)
    }

    @Test
    fun `service error with loaded journal dispatches recovery exactly once`() {
        val error = TransactionResult(
            success = false,
            applied = false,
            restored = false,
            code = "SERVICE_ERROR",
            message = "worker failed after durable prepare",
        )
        val observed = mutableListOf<PostTransactionDisposition>()

        val disposition = PostTransactionDispositionDispatch.dispatch(
            result = error,
            journal = JournalLoadResult.Loaded(journalRecord()),
            onDisposition = observed::add,
        )

        assertTrue(disposition is PostTransactionDisposition.RetainRecovery)
        assertEquals(1, observed.size)
        assertTrue(observed.single() is PostTransactionDisposition.RetainRecovery)
    }

    @Test
    fun `service error with corrupt journal dispatches hold exactly once`() {
        val error = TransactionResult(
            success = false,
            applied = false,
            restored = false,
            code = "SERVICE_ERROR",
            message = "journal unreadable",
        )
        val observed = mutableListOf<PostTransactionDisposition>()

        val disposition = PostTransactionDispositionDispatch.dispatch(
            result = error,
            journal = JournalLoadResult.Corrupt("bad"),
            onDisposition = observed::add,
        )

        assertEquals(PostTransactionDisposition.HoldCorrupt, disposition)
        assertEquals(listOf(PostTransactionDisposition.HoldCorrupt), observed)
    }

    private fun terminalTruth() = StartedServiceTerminalTruth(
        journalEmpty = true,
        publicOwnerAbsent = true,
        supervisorSessionAbsent = true,
        monitorAbsent = true,
        recoveryOwnerAbsent = true,
        restoreRetryAbsent = true,
        foregroundRetryAbsent = true,
        lifetimeIdle = true,
        labHoldInactive = true,
        calibrationInactive = true,
        transactionWorkTerminal = true,
    )

    private fun journalRecord() = TransactionJournalRecord(
        schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V1,
        generation = 1,
        requestId = "request",
        transactionId = "transaction",
        hardwareFingerprint = "fingerprint",
        deviceProfile = "rp5",
        planHash = "plan",
        profileId = "quiet",
        targetPackage = "example.game",
        phase = TransactionPhase.APPLIED,
        creationTime = 1,
        requestDeadline = 2,
        leaseDeadline = 3,
        bootIdentity = "boot",
        snapshot = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = "fingerprint",
            bootIdentity = "boot",
            nodes = emptyList(),
        ),
        expectedTopologySha256 = "topology",
        snapshotSha256 = "snapshot",
        lastCompletedMutationBoundary = 0,
    )
}
