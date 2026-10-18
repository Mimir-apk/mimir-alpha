package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionReturnActivityStateTest {
    @Test
    fun `resumed host dispatches immediately only from host-started phase`() {
        assertTrue(ReturnHostExecutionState(phase = ReturnHostActivityPhase.HOST_STARTED).shouldDispatchTargetOnResume())
        assertFalse(ReturnHostExecutionState(phase = ReturnHostActivityPhase.INITIAL).shouldDispatchTargetOnResume())
        assertFalse(ReturnHostExecutionState(phase = ReturnHostActivityPhase.TARGET_DISPATCHED).shouldDispatchTargetOnResume())
    }

    @Test
    fun `recreation launches target at most once and keeps dispatch token`() {
        val operation = OperationId.create()
        val initial = ReturnHostExecutionState(phase = ReturnHostActivityPhase.HOST_STARTED)
        val firstClaim = initial.claimTargetLaunch()
        assertTrue(firstClaim.second)

        val recreated = firstClaim.first.targetDispatched(operation).copy()
        val secondClaim = recreated.claimTargetLaunch()

        assertFalse(secondClaim.second)
        assertTrue(secondClaim.first.targetLaunchAttempted)
        assertEquals(operation, secondClaim.first.targetDispatchOperationId)
        assertTrue(secondClaim.first.isCorrelatedWith(operation))
    }

    @Test
    fun `recreation emits return completion at most once and keeps settlement root`() {
        val host = OperationId.create()
        val settlement = OperationId.create()
        val covered = dispatchedState(host).claimHostCoverage(host).first
        val firstClaim = covered.claimHostReturn(host)
        assertTrue(firstClaim.second)

        val persisted = firstClaim.first.copy(
            phase = ReturnHostActivityPhase.RECLAIM_REQUESTED,
            settlementOperationId = settlement,
        )
        val secondClaim = persisted.claimHostReturn(host)

        assertFalse(secondClaim.second)
        assertEquals(settlement, secondClaim.first.settlementOperationId)
        assertTrue(secondClaim.first.isCorrelatedWith(host))
    }

    @Test
    fun `reclaim recreation validates live settlement and exact host parent`() {
        val fixture = reclaimFixture()
        val execution = ReturnHostExecutionState(
            phase = ReturnHostActivityPhase.RECLAIM_REQUESTED,
            targetLaunchAttempted = true,
            completionEmitted = true,
            settlementOperationId = fixture.reclaim.operationId,
        )
        val pending = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.ReclaimPending

        assertEquals(fixture.host.operationId, pending.lease.parentHostOperationId)
        assertFalse(fixture.runtime.recognizes(fixture.host.operationId))
        assertTrue(fixture.runtime.recognizes(fixture.reclaim.operationId))
        assertTrue(execution.isCorrelatedWith(fixture.host.operationId))

        val recognitionQueries = mutableListOf<OperationId>()
        assertTrue(
            execution.persistedRootIsCurrent(
                activityOperationId = fixture.host.operationId,
                restoreGuard = false,
                snapshot = fixture.runtime.snapshot.value,
                recognizesOperation = { operationId ->
                    recognitionQueries += operationId
                    fixture.runtime.recognizes(operationId)
                },
            ),
        )
        assertEquals(listOf(fixture.reclaim.operationId), recognitionQueries)
    }

    @Test
    fun `reclaim recreation rejects stale settlement and malformed host parent`() {
        val fixture = reclaimFixture()
        val execution = ReturnHostExecutionState(
            phase = ReturnHostActivityPhase.RECLAIM_REQUESTED,
            targetLaunchAttempted = true,
            completionEmitted = true,
            settlementOperationId = fixture.reclaim.operationId,
        )
        val staleSettlement = execution.copy(settlementOperationId = OperationId.create())

        assertFalse(
            staleSettlement.persistedRootIsCurrent(
                activityOperationId = fixture.host.operationId,
                restoreGuard = false,
                snapshot = fixture.runtime.snapshot.value,
                recognizesOperation = { fixture.runtime.recognizes(it) },
            ),
        )
        assertFalse(
            execution.persistedRootIsCurrent(
                activityOperationId = OperationId.create(),
                restoreGuard = false,
                snapshot = fixture.runtime.snapshot.value,
                recognizesOperation = { fixture.runtime.recognizes(it) },
            ),
        )
    }

    @Test
    fun `dedicated restore guard recreation validates its reclaim root`() {
        val fixture = reclaimFixture()
        val execution = ReturnHostExecutionState(
            phase = ReturnHostActivityPhase.RECLAIM_REQUESTED,
            completionEmitted = true,
            settlementOperationId = fixture.reclaim.operationId,
        )

        assertTrue(
            execution.persistedRootIsCurrent(
                activityOperationId = fixture.reclaim.operationId,
                restoreGuard = true,
                snapshot = fixture.runtime.snapshot.value,
                recognizesOperation = { fixture.runtime.recognizes(it) },
            ),
        )
        assertFalse(
            execution.persistedRootIsCurrent(
                activityOperationId = fixture.host.operationId,
                restoreGuard = true,
                snapshot = fixture.runtime.snapshot.value,
                recognizesOperation = { fixture.runtime.recognizes(it) },
            ),
        )
    }

    @Test
    fun `matching host stop settles coverage once and repeated stop is inert`() {
        val operation = OperationId.create()
        val dispatched = dispatchedState(operation)

        val stopped = dispatched.claimHostCoverage(operation)
        val repeatedStop = stopped.first.claimHostCoverage(operation)

        assertTrue(stopped.second)
        assertEquals(ReturnHostActivityPhase.HOST_COVERED, stopped.first.phase)
        assertEquals(operation, stopped.first.hostCoverageOperationId)
        assertFalse(repeatedStop.second)
        assertEquals(stopped.first, repeatedStop.first)
    }

    @Test
    fun `destroy during child reclaim reports settlement root`() {
        val host = OperationId.create()
        val reclaim = OperationId.create()
        val execution = ReturnHostExecutionState(
            phase = ReturnHostActivityPhase.RECLAIM_REQUESTED,
            completionEmitted = true,
            settlementOperationId = reclaim,
        )

        assertEquals(reclaim, returnHostDestructionOperationId(host, execution))
        assertEquals(host, returnHostDestructionOperationId(host, dispatchedState(host)))
    }

    @Test
    fun `fast finish emits one coverage and one reclaim`() {
        val scheduler = ManualScheduler()
        val runtime = CompanionRuntimeCoordinator(scheduler)
        assertTrue(runtime.displayAvailable(4))
        val host = runtime.launchRequested(
            packageName = "example.fast",
            displayId = 4,
            launchMode = "secondary_app",
            primaryOrigin = PrimaryReturnOrigin("library"),
        )!!
        assertTrue(runtime.hostStarted(host.operationId))
        assertTrue(runtime.targetDispatched(host.operationId))

        val covered = dispatchedState(host.operationId).claimHostCoverage(host.operationId)
        assertTrue(covered.second)
        assertTrue(runtime.hostCovered(host.operationId))
        val returned = covered.first.claimHostReturn(host.operationId)
        assertTrue(returned.second)
        val reclaim = runtime.hostReturned(host.operationId)

        assertNotNull(reclaim)
        assertNotEquals(host.operationId, reclaim!!.operationId)
        assertEquals(
            ReturnHostDispositionDecision.KEEP,
            returned.first.dispositionDecision(
                hostOperationId = host.operationId,
                snapshot = runtime.snapshot.value,
                recognizesOperation = { runtime.recognizes(it) },
            ),
        )
        val reclaimRequested = returned.first.copy(
            phase = ReturnHostActivityPhase.RECLAIM_REQUESTED,
            settlementOperationId = reclaim.operationId,
        )
        assertEquals(
            ReturnHostDispositionDecision.KEEP,
            reclaimRequested.dispositionDecision(
                hostOperationId = host.operationId,
                snapshot = runtime.snapshot.value,
                recognizesOperation = { runtime.recognizes(it) },
            ),
        )
        assertFalse(returned.first.claimHostReturn(host.operationId).second)
        assertFalse(returned.first.claimHostCoverage(host.operationId).second)
        assertNull(runtime.hostReturned(host.operationId))
        assertTrue(runtime.snapshot.value.disposition is SecondaryDisplayDisposition.ReclaimPending)
    }

    @Test
    fun `interruption lifecycle before target dispatch cannot settle`() {
        val operation = OperationId.create()
        val awaitingDispatch = ReturnHostExecutionState(
            phase = ReturnHostActivityPhase.HOST_STARTED,
            targetLaunchAttempted = true,
        )

        val unrelatedPause = awaitingDispatch.claimHostCoverage(operation)
        val unrelatedResume = unrelatedPause.first.claimHostReturn(operation)

        assertFalse(unrelatedPause.second)
        assertFalse(unrelatedResume.second)
        assertEquals(awaitingDispatch, unrelatedResume.first)
        assertFalse(awaitingDispatch.targetDispatched(operation).claimHostCoverage(operation).second)
        assertTrue(awaitingDispatch.targetDispatched(operation).targetPaused().claimHostCoverage(operation).second)
    }

    @Test
    fun `target must pause host before coverage and immediate return stays unconfirmed`() {
        val operation = OperationId.create()
        val dispatched = ReturnHostExecutionState(phase = ReturnHostActivityPhase.HOST_STARTED)
            .claimTargetLaunch().first
            .targetDispatched(operation)

        assertFalse(dispatched.targetPauseObserved)
        assertFalse(dispatched.claimHostCoverage(operation).second)
        val paused = dispatched.targetPaused()
        assertTrue(paused.targetPauseObserved)
        assertTrue(paused.isCorrelatedWith(operation))
        assertTrue(paused.claimHostCoverage(operation).second)
    }

    @Test
    fun `secondary coverage rejects Home screen lock and call shaped interruptions`() {
        val operation = OperationId.create()
        val paused = ReturnHostExecutionState(phase = ReturnHostActivityPhase.HOST_STARTED)
            .claimTargetLaunch().first
            .targetDispatched(operation)
            .targetPaused()

        assertFalse(paused.userLeft().claimHostCoverage(operation).second)
        assertFalse(
            paused.claimHostCoverage(
                operation,
                TransferHostCoverageEnvironment.Permissive.copy(displayOn = false),
            ).second,
        )
        assertFalse(
            paused.claimHostCoverage(
                operation,
                TransferHostCoverageEnvironment.Permissive.copy(keyguardLocked = true),
            ).second,
        )
        assertFalse(
            paused.claimHostCoverage(
                operation,
                TransferHostCoverageEnvironment.Permissive.copy(callInterruptionActive = true),
            ).second,
        )
        assertFalse(paused.screenOff().claimHostCoverage(operation).second)
        assertFalse(
            paused.claimHostCoverage(
                operation,
                TransferHostCoverageEnvironment.Permissive.copy(homeOrRecentsBecameVisible = true),
            ).second,
        )
    }

    @Test
    fun `secondary target launch departure consumes one expected leave hint`() {
        val operation = OperationId.create()
        val state = ReturnHostExecutionState(phase = ReturnHostActivityPhase.HOST_STARTED)
            .claimTargetLaunch().first
            .targetDispatched(operation)
            .userLeft()
            .targetPaused()

        assertFalse(state.userLeaveObserved)
        assertFalse(state.expectedTargetLeaveHint)
        assertTrue(state.claimHostCoverage(operation).second)
    }

    @Test
    fun `user departure before secondary dispatch invalidates host correlation`() {
        val operation = OperationId.create()
        val state = ReturnHostExecutionState(phase = ReturnHostActivityPhase.HOST_STARTED).userLeft()

        assertTrue(state.userLeaveObserved)
        assertFalse(state.isCorrelatedWith(operation))
    }

    @Test
    fun `interrupted launch claim without dispatch token fails correlation`() {
        val operation = OperationId.create()
        val interrupted = ReturnHostExecutionState(
            phase = ReturnHostActivityPhase.HOST_STARTED,
            targetLaunchAttempted = true,
        )

        assertFalse(interrupted.isCorrelatedWith(operation))
        assertFalse(interrupted.claimHostCoverage(operation).second)
        assertFalse(interrupted.claimHostReturn(operation).second)
    }

    @Test
    fun `stale operation token cannot cover or reclaim current host`() {
        val current = OperationId.create()
        val stale = OperationId.create()
        val dispatched = dispatchedState(current)

        val stalePause = dispatched.claimHostCoverage(stale)
        assertFalse(stalePause.second)
        assertEquals(dispatched, stalePause.first)
        assertFalse(dispatched.isCorrelatedWith(stale))

        val covered = dispatched.claimHostCoverage(current)
        assertTrue(covered.second)
        val staleResume = covered.first.claimHostReturn(stale)
        assertFalse(staleResume.second)
        assertEquals(covered.first, staleResume.first)
        assertTrue(covered.first.claimHostReturn(current).second)
    }

    @Test
    fun `silent target timeout without pause fail closes exact host root`() {
        val scheduler = ManualScheduler()
        val runtime = CompanionRuntimeCoordinator(scheduler)
        assertTrue(runtime.displayAvailable(4))
        val host = runtime.launchRequested(
            packageName = "example.silent",
            displayId = 4,
            launchMode = "secondary_app",
            primaryOrigin = PrimaryReturnOrigin("library"),
        )!!
        assertTrue(runtime.hostStarted(host.operationId))
        assertTrue(runtime.targetDispatched(host.operationId))
        val execution = dispatchedState(host.operationId)

        assertEquals(
            ReturnHostDispositionDecision.KEEP,
            execution.dispositionDecision(
                hostOperationId = host.operationId,
                snapshot = runtime.snapshot.value,
                recognizesOperation = { runtime.recognizes(it) },
            ),
        )

        scheduler.fireAll()

        val timedOut = runtime.snapshot.value
        val recovery = timedOut.disposition as SecondaryDisplayDisposition.RecoveryNeeded
        assertEquals(host.operationId, recovery.operationId)
        assertEquals(FailureCode.LAUNCH_DEADLINE_AFTER_DISPATCH, recovery.failure.code)
        assertEquals(
            ReturnHostDispositionDecision.FAIL_CLOSED,
            execution.dispositionDecision(
                hostOperationId = host.operationId,
                snapshot = timedOut,
                recognizesOperation = { runtime.recognizes(it) },
            ),
        )
    }

    @Test
    fun `superseded host A later resume fail closes A while B remains current`() {
        val scheduler = ManualScheduler()
        val runtime = CompanionRuntimeCoordinator(scheduler)
        assertTrue(runtime.displayAvailable(4))
        val hostA = runtime.launchRequested(
            packageName = "example.a",
            displayId = 4,
            launchMode = "secondary_app",
            primaryOrigin = PrimaryReturnOrigin("library/a"),
        )!!
        assertTrue(runtime.hostStarted(hostA.operationId))
        assertTrue(runtime.targetDispatched(hostA.operationId))
        val executionA = dispatchedState(hostA.operationId)

        val hostB = runtime.launchRequested(
            packageName = "example.b",
            displayId = 4,
            launchMode = "secondary_app",
            primaryOrigin = PrimaryReturnOrigin("library/b"),
        )!!
        val ownedByB = runtime.snapshot.value
        val launchB = ownedByB.disposition as SecondaryDisplayDisposition.LaunchPending
        assertEquals(hostB.operationId, launchB.lease.operationId)
        assertFalse(runtime.recognizes(hostA.operationId))
        assertTrue(runtime.recognizes(hostB.operationId))
        assertFalse(executionA.claimHostReturn(hostA.operationId).second)

        val recognitionQueries = mutableListOf<OperationId>()
        assertEquals(
            ReturnHostDispositionDecision.FAIL_CLOSED,
            executionA.dispositionDecision(
                hostOperationId = hostA.operationId,
                snapshot = ownedByB,
                recognizesOperation = { operationId ->
                    recognitionQueries += operationId
                    runtime.recognizes(operationId)
                },
            ),
        )

        assertEquals(listOf(hostA.operationId), recognitionQueries)
        assertEquals(ownedByB, runtime.snapshot.value)
        assertTrue(runtime.recognizes(hostB.operationId))
        assertEquals(
            ReturnHostDispositionDecision.KEEP,
            ReturnHostExecutionState().dispositionDecision(
                hostOperationId = hostB.operationId,
                snapshot = runtime.snapshot.value,
                recognizesOperation = { runtime.recognizes(it) },
            ),
        )
    }

    private fun dispatchedState(operationId: OperationId): ReturnHostExecutionState =
        ReturnHostExecutionState(
            phase = ReturnHostActivityPhase.HOST_STARTED,
            targetLaunchAttempted = true,
        ).targetDispatched(operationId).targetPaused()

    private fun reclaimFixture(): ReclaimFixture {
        val runtime = CompanionRuntimeCoordinator(ManualScheduler())
        assertTrue(runtime.displayAvailable(4))
        val host = runtime.launchRequested(
            packageName = "example.recreate",
            displayId = 4,
            launchMode = "secondary_app",
            primaryOrigin = PrimaryReturnOrigin("library/recreate"),
        )!!
        assertTrue(runtime.hostStarted(host.operationId))
        assertTrue(runtime.targetDispatched(host.operationId))
        assertTrue(runtime.hostCovered(host.operationId))
        val reclaim = runtime.hostReturned(host.operationId)!!
        return ReclaimFixture(runtime, host, reclaim)
    }

    private data class ReclaimFixture(
        val runtime: CompanionRuntimeCoordinator,
        val host: HostLease,
        val reclaim: ReclaimLease,
    )

    private class ManualScheduler : CompanionRuntimeScheduler {
        private val tasks = mutableListOf<Task>()

        override fun schedule(
            delayMillis: Long,
            operationId: OperationId,
            action: () -> Unit,
        ): RuntimeCancellation {
            val task = Task(operationId, action)
            tasks += task
            return RuntimeCancellation { task.cancelled = true }
        }

        fun fireAll() {
            tasks.toList().forEach { task ->
                if (!task.cancelled) task.action()
            }
        }

        private data class Task(
            val operationId: OperationId,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )
    }
}
