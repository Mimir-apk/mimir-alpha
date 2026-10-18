package com.gruning.mimir.rewrite.core.tweaker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TweakerV4OwnerTest {
    @Test
    fun `new process starts unconfirmed and confirms stock with query only`() = runTest {
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
        }
        val owner = controller(transport)

        assertTrue(owner.state.value is TweakerV4OwnerState.Unconfirmed)
        assertEquals(
            TweakerV4OwnerOutcome.Stock,
            owner.confirmAfterProcessStart(bindTimeoutMs = 500L, queryTimeoutMs = 500L),
        )
        assertTrue(owner.state.value is TweakerV4OwnerState.Stock)
        assertEquals(1, transport.bindCalls)
        assertEquals(1, transport.queryCalls)
        assertEquals(0, transport.beginCalls)
        assertEquals(0, transport.applyCalls)
        assertEquals(0, transport.restoreCalls)
        assertEquals(1, transport.unbindCalls)
    }

    @Test
    fun `begin failure issues zero apply calls and returns unconfirmed`() = runTest {
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            beginResult = false
        }
        val owner = controller(transport)

        val result = owner.activate(request())

        assertTrue(result is TweakerV4OwnerOutcome.Uncertain)
        assertTrue(owner.state.value is TweakerV4OwnerState.Unconfirmed)
        assertEquals(1, transport.beginCalls)
        assertEquals(0, transport.applyCalls)
        assertEquals(1, transport.unbindCalls)
    }

    @Test
    fun `non-stock preflight blocks apply without replaying foreign identity`() = runTest {
        val foreign = owned("foreign-owner", generation = 91L)
        val transport = FakeOwnerTransport().apply {
            queries += TweakerV4SessionAssessment.Blocked(activeTruth(foreign))
        }
        val owner = controller(transport)

        val result = owner.activate(request())

        assertTrue(result is TweakerV4OwnerOutcome.Blocked)
        assertTrue(owner.state.value is TweakerV4OwnerState.Blocked)
        assertEquals(0, transport.beginCalls)
        assertEquals(0, transport.applyCalls)
        assertEquals(0, transport.cancelCalls)
        assertEquals(0, transport.restoreCalls)
        assertEquals(0, transport.unbindCalls)
        assertTrue(transport.isBound)
    }

    @Test
    fun `accepted owner restores exactly on terminal return`() = runTest {
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            applyHandler = { acceptedApply(it) }
            restoreHandler = { acceptedRestore() }
        }
        val controller = controller(transport)

        val activated = controller.activate(request()) as TweakerV4OwnerOutcome.Active
        assertTrue(controller.state.value is TweakerV4OwnerState.Active)

        val settled = controller.settle(queryTimeoutMs = 500L, operationTimeoutMs = 500L)

        assertEquals(TweakerV4OwnerOutcome.Stock, settled)
        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertEquals(1, transport.restoreCalls)
        assertTrue(transport.restoredOwners.single() === activated.owner)
        assertEquals(1, transport.unbindCalls)
    }

    @Test
    fun `reactivation cannot enter while restored stock is still unbinding`() = runTest {
        val unbindEntered = CompletableDeferred<Unit>()
        val releaseUnbind = CompletableDeferred<Unit>()
        var blockNextUnbind = true
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            applyHandler = { acceptedApply(it) }
            restoreHandler = { acceptedRestore() }
            unbindHandler = {
                if (blockNextUnbind) {
                    blockNextUnbind = false
                    unbindEntered.complete(Unit)
                    releaseUnbind.await()
                }
            }
        }
        val controller = controller(transport)
        assertTrue(controller.activate(request()) is TweakerV4OwnerOutcome.Active)

        val settlement = async {
            controller.settle(queryTimeoutMs = 500L, operationTimeoutMs = 500L)
        }
        unbindEntered.await()

        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertEquals(TweakerV4OwnerOutcome.Busy, controller.activate(request()))
        assertEquals(1, transport.bindCalls)
        assertEquals(1, transport.applyCalls)

        releaseUnbind.complete(Unit)
        assertEquals(TweakerV4OwnerOutcome.Stock, settlement.await())

        transport.queries += stockAssessment()
        assertTrue(controller.activate(request()) is TweakerV4OwnerOutcome.Active)
        assertEquals(2, transport.bindCalls)
        assertEquals(2, transport.applyCalls)
    }

    @Test
    fun `reactivation cannot enter while failed preflight is still unbinding`() = runTest {
        val unbindEntered = CompletableDeferred<Unit>()
        val releaseUnbind = CompletableDeferred<Unit>()
        var blockNextUnbind = true
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            beginResult = false
            applyHandler = { acceptedApply(it) }
            unbindHandler = {
                if (blockNextUnbind) {
                    blockNextUnbind = false
                    unbindEntered.complete(Unit)
                    releaseUnbind.await()
                }
            }
        }
        val controller = controller(transport)

        val failedActivation = async { controller.activate(request()) }
        unbindEntered.await()

        assertTrue(controller.state.value is TweakerV4OwnerState.Unconfirmed)
        assertEquals(TweakerV4OwnerOutcome.Busy, controller.activate(request()))
        assertEquals(1, transport.bindCalls)
        assertEquals(1, transport.beginCalls)
        assertEquals(0, transport.applyCalls)

        releaseUnbind.complete(Unit)
        assertTrue(failedActivation.await() is TweakerV4OwnerOutcome.Uncertain)

        transport.beginResult = true
        transport.queries += stockAssessment()
        assertTrue(controller.activate(request()) is TweakerV4OwnerOutcome.Active)
        assertEquals(2, transport.bindCalls)
        assertEquals(2, transport.beginCalls)
        assertEquals(1, transport.applyCalls)
    }

    @Test
    fun `cancelled activation keeps admission closed before teardown claims the lock`() = runTest {
        val attemptReady = CompletableDeferred<Unit>()
        val holdAttempt = CompletableDeferred<Unit>()
        val teardownReady = CompletableDeferred<Unit>()
        val releaseTeardown = CompletableDeferred<Unit>()
        var blockNextAttempt = true
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            queries += stockAssessment()
            applyHandler = { acceptedApply(it) }
        }
        val controller = TweakerV4Owner(
            transport = transport,
            elapsedRealtimeMs = transport.now,
            beforeAttemptCommit = {
                if (blockNextAttempt) {
                    blockNextAttempt = false
                    attemptReady.complete(Unit)
                    holdAttempt.await()
                }
            },
            beforeTeardownClaim = {
                teardownReady.complete(Unit)
                releaseTeardown.await()
            },
        )

        val cancelledActivation = async { controller.activate(request()) }
        attemptReady.await()
        cancelledActivation.cancel()
        teardownReady.await()

        assertTrue(controller.state.value is TweakerV4OwnerState.Unconfirmed)
        assertEquals(TweakerV4OwnerOutcome.Busy, controller.activate(request()))
        assertEquals(1, transport.bindCalls)
        assertEquals(0, transport.beginCalls)
        assertEquals(0, transport.applyCalls)

        releaseTeardown.complete(Unit)
        cancelledActivation.join()
        assertTrue(cancelledActivation.isCancelled)
        assertEquals(1, transport.unbindCalls)

        transport.queries += stockAssessment()
        assertTrue(controller.activate(request()) is TweakerV4OwnerOutcome.Active)
        assertEquals(2, transport.bindCalls)
        assertEquals(1, transport.applyCalls)
    }

    @Test
    fun `terminal cancellation at handoff still reaches authoritative stock`() = runTest {
        val terminalReady = CompletableDeferred<Unit>()
        val releaseTerminal = CompletableDeferred<Unit>()
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            applyHandler = { acceptedApply(it) }
            restoreHandler = { acceptedRestore() }
        }
        val controller = TweakerV4Owner(
            transport = transport,
            elapsedRealtimeMs = transport.now,
            beforeTerminalExecution = {
                terminalReady.complete(Unit)
                releaseTerminal.await()
            },
        )
        assertTrue(controller.activate(request()) is TweakerV4OwnerOutcome.Active)

        val settlement = async {
            controller.settle(queryTimeoutMs = 500L, operationTimeoutMs = 500L)
        }
        terminalReady.await()
        assertTrue(controller.state.value is TweakerV4OwnerState.Restoring)
        settlement.cancel()
        releaseTerminal.complete(Unit)
        settlement.join()

        assertTrue(settlement.isCancelled)
        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertEquals(1, transport.restoreCalls)
        assertEquals(1, transport.unbindCalls)
    }

    @Test
    fun `terminal cancellation after restore response still commits stock`() = runTest {
        val restoreReady = CompletableDeferred<Unit>()
        val releaseRestoreCommit = CompletableDeferred<Unit>()
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            applyHandler = { acceptedApply(it) }
            restoreHandler = { acceptedRestore() }
        }
        val controller = TweakerV4Owner(
            transport = transport,
            elapsedRealtimeMs = transport.now,
            beforeRestoreResultCommit = {
                restoreReady.complete(Unit)
                releaseRestoreCommit.await()
            },
        )
        assertTrue(controller.activate(request()) is TweakerV4OwnerOutcome.Active)

        val settlement = async {
            controller.settle(queryTimeoutMs = 500L, operationTimeoutMs = 500L)
        }
        restoreReady.await()
        settlement.cancel()
        releaseRestoreCommit.complete(Unit)
        settlement.join()

        assertTrue(settlement.isCancelled)
        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertEquals(1, transport.restoreCalls)
        assertEquals(1, transport.unbindCalls)
    }

    @Test
    fun `terminal intent cancels blocked apply and late active response never publishes active`() = runTest {
        val applyEntered = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            queries += stockAssessment()
            applyHandler = {
                applyEntered.complete(Unit)
                releaseApply.await()
                acceptedApply(it)
            }
            cancelHandler = { acceptedCancel(it) }
            restoreHandler = { acceptedRestore() }
        }
        val controller = controller(transport)
        val observed = mutableListOf<TweakerV4OwnerState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { observed += it }
        }

        val activation = async { controller.activate(request()) }
        applyEntered.await()
        val settlement = controller.settle(queryTimeoutMs = 500L, operationTimeoutMs = 500L)

        assertEquals(TweakerV4OwnerOutcome.Pending, settlement)
        assertTrue(controller.state.value is TweakerV4OwnerState.SettlingApply)
        assertEquals(1, transport.cancelCalls)
        assertEquals(0L, transport.cancelGeneration)
        assertEquals("", transport.cancelTransactionId)
        assertEquals(TweakerV4OwnerOutcome.Busy, controller.activate(request()))

        releaseApply.complete(Unit)
        assertEquals(TweakerV4OwnerOutcome.Stock, activation.await())
        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertFalse(observed.any { it is TweakerV4OwnerState.Active })
        assertEquals(1, transport.restoreCalls)
        assertEquals(1, transport.unbindCalls)
    }

    @Test
    fun `terminal after stock query prevents attempt creation and activation resume`() = runTest {
        val stockObserved = CompletableDeferred<Unit>()
        val releaseAttemptCommit = CompletableDeferred<Unit>()
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
        }
        val controller = TweakerV4Owner(
            transport = transport,
            elapsedRealtimeMs = transport.now,
            beforeAttemptCommit = {
                stockObserved.complete(Unit)
                releaseAttemptCommit.await()
            },
        )

        val activation = async { controller.activate(request()) }
        stockObserved.await()

        assertEquals(
            TweakerV4OwnerOutcome.Pending,
            controller.settle(queryTimeoutMs = 500L, operationTimeoutMs = 500L),
        )
        releaseAttemptCommit.complete(Unit)

        assertEquals(TweakerV4OwnerOutcome.Stock, activation.await())
        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertEquals(0, transport.beginCalls)
        assertEquals(0, transport.applyCalls)
        assertEquals(1, transport.unbindCalls)
    }

    @Test
    fun `terminal between apply response and publication cannot be overwritten by active`() = runTest {
        val resultReady = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var returnedOwner: TweakerV4OwnedSession? = null
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            applyHandler = {
                acceptedApply(it).also { accepted -> returnedOwner = accepted.owner }
            }
            cancelHandler = { acceptedCancel(it) }
            restoreHandler = { acceptedRestore() }
        }
        val controller = TweakerV4Owner(
            transport = transport,
            elapsedRealtimeMs = transport.now,
            beforeApplyResultCommit = {
                resultReady.complete(Unit)
                releaseCommit.await()
            },
        )
        val observed = mutableListOf<TweakerV4OwnerState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { observed += it }
        }

        val activation = async { controller.activate(request()) }
        resultReady.await()
        transport.queries += TweakerV4SessionAssessment.Blocked(
            activeTruth(requireNotNull(returnedOwner)),
        )

        assertEquals(
            TweakerV4OwnerOutcome.Pending,
            controller.settle(queryTimeoutMs = 500L, operationTimeoutMs = 500L),
        )
        releaseCommit.complete(Unit)

        assertEquals(TweakerV4OwnerOutcome.Stock, activation.await())
        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertFalse(observed.any { it is TweakerV4OwnerState.Active })
    }

    @Test
    fun `accepted apply that expires before publication restores without active`() = runTest {
        var now = 1_000L
        val resultReady = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val transport = FakeOwnerTransport(now = { now }).apply {
            queries += stockAssessment()
            applyHandler = { acceptedApply(it, leaseDeadline = 8_000L) }
            restoreHandler = { acceptedRestore() }
        }
        val controller = TweakerV4Owner(
            transport = transport,
            elapsedRealtimeMs = transport.now,
            beforeApplyResultCommit = {
                resultReady.complete(Unit)
                releaseCommit.await()
            },
        )
        val observed = mutableListOf<TweakerV4OwnerState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { observed += it }
        }

        val activation = async { controller.activate(request(operationTimeoutMs = 20_000L)) }
        resultReady.await()
        now = 8_000L
        releaseCommit.complete(Unit)

        assertEquals(TweakerV4OwnerOutcome.Stock, activation.await())
        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertFalse(observed.any { it is TweakerV4OwnerState.Active })
        assertEquals(1, transport.restoreCalls)
    }

    @Test
    fun `activation cancellation after apply response restores without publishing active`() = runTest {
        val resultReady = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            applyHandler = { acceptedApply(it) }
            restoreHandler = { acceptedRestore() }
        }
        val controller = TweakerV4Owner(
            transport = transport,
            elapsedRealtimeMs = transport.now,
            beforeApplyResultCommit = {
                resultReady.complete(Unit)
                releaseCommit.await()
            },
        )
        val observed = mutableListOf<TweakerV4OwnerState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { observed += it }
        }

        val activation = async { controller.activate(request()) }
        resultReady.await()
        activation.cancel()
        releaseCommit.complete(Unit)
        activation.join()

        assertTrue(activation.isCancelled)
        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertFalse(observed.any { it is TweakerV4OwnerState.Active })
        assertEquals(1, transport.restoreCalls)
        assertEquals(1, transport.unbindCalls)
    }

    @Test
    fun `terminal intent wins over a late successful renewal`() = runTest {
        val renewEntered = CompletableDeferred<Unit>()
        val releaseRenew = CompletableDeferred<Unit>()
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            applyHandler = { acceptedApply(it) }
            renewHandler = { current ->
                renewEntered.complete(Unit)
                releaseRenew.await()
                acceptedRenew(current, leaseDeadline = 9_000L)
            }
            restoreHandler = { acceptedRestore() }
        }
        val controller = controller(transport)
        assertTrue(controller.activate(request()) is TweakerV4OwnerOutcome.Active)

        val renewal = async {
            controller.renew(queryTimeoutMs = 500L, operationTimeoutMs = 500L)
        }
        renewEntered.await()
        val settlement = controller.settle(queryTimeoutMs = 500L, operationTimeoutMs = 500L)
        assertEquals(TweakerV4OwnerOutcome.Pending, settlement)
        assertFalse(controller.state.value is TweakerV4OwnerState.Active)

        releaseRenew.complete(Unit)
        assertEquals(TweakerV4OwnerOutcome.Stock, renewal.await())
        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertFalse(controller.state.value is TweakerV4OwnerState.Active)
    }

    @Test
    fun `cancelled renewal clears ownership work so terminal settlement can finish`() = runTest {
        val renewalReady = CompletableDeferred<Unit>()
        val releaseRenewalCommit = CompletableDeferred<Unit>()
        val transport = FakeOwnerTransport().apply {
            queries += stockAssessment()
            applyHandler = { acceptedApply(it) }
            renewHandler = { acceptedRenew(it, leaseDeadline = 9_000L) }
            restoreHandler = { acceptedRestore() }
        }
        val controller = TweakerV4Owner(
            transport = transport,
            elapsedRealtimeMs = transport.now,
            beforeRenewResultCommit = {
                renewalReady.complete(Unit)
                releaseRenewalCommit.await()
            },
        )
        assertTrue(controller.activate(request()) is TweakerV4OwnerOutcome.Active)

        val renewal = async {
            controller.renew(queryTimeoutMs = 500L, operationTimeoutMs = 500L)
        }
        renewalReady.await()
        renewal.cancel()
        releaseRenewalCommit.complete(Unit)
        renewal.join()

        assertTrue(renewal.isCancelled)
        assertTrue(controller.state.value is TweakerV4OwnerState.Active)
        assertEquals(
            TweakerV4OwnerOutcome.Stock,
            controller.settle(queryTimeoutMs = 500L, operationTimeoutMs = 500L),
        )
        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertEquals(1, transport.renewCalls)
        assertEquals(1, transport.restoreCalls)
    }

    @Test
    fun `renewed lease that expires before publication restores instead of active`() = runTest {
        var now = 1_000L
        val renewalReady = CompletableDeferred<Unit>()
        val releaseRenewalCommit = CompletableDeferred<Unit>()
        val transport = FakeOwnerTransport(now = { now }).apply {
            queries += stockAssessment()
            applyHandler = { acceptedApply(it) }
            renewHandler = { acceptedRenew(it, leaseDeadline = 9_000L) }
            restoreHandler = { acceptedRestore() }
        }
        val controller = TweakerV4Owner(
            transport = transport,
            elapsedRealtimeMs = transport.now,
            beforeRenewResultCommit = {
                renewalReady.complete(Unit)
                releaseRenewalCommit.await()
            },
        )
        val observed = mutableListOf<TweakerV4OwnerState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { observed += it }
        }
        assertTrue(controller.activate(request()) is TweakerV4OwnerOutcome.Active)

        val renewal = async {
            controller.renew(queryTimeoutMs = 500L, operationTimeoutMs = 500L)
        }
        renewalReady.await()
        now = 9_000L
        releaseRenewalCommit.complete(Unit)

        assertEquals(TweakerV4OwnerOutcome.Stock, renewal.await())
        assertTrue(controller.state.value is TweakerV4OwnerState.Stock)
        assertFalse(
            observed.filterIsInstance<TweakerV4OwnerState.Active>()
                .any { it.owner.leaseDeadlineElapsedRealtimeMs == 9_000L },
        )
        assertEquals(1, transport.restoreCalls)
    }

    @Test
    fun `renewal at lease boundary restores without a renew call`() = runTest {
        var now = 1_000L
        val transport = FakeOwnerTransport(now = { now }).apply {
            queries += stockAssessment()
            applyHandler = { acceptedApply(it, leaseDeadline = 8_000L) }
            restoreHandler = { acceptedRestore() }
        }
        val controller = TweakerV4Owner(transport, { now })
        assertTrue(controller.activate(request()) is TweakerV4OwnerOutcome.Active)

        now = 8_000L
        assertEquals(
            TweakerV4OwnerOutcome.Stock,
            controller.renew(queryTimeoutMs = 500L, operationTimeoutMs = 500L),
        )
        assertEquals(0, transport.renewCalls)
        assertEquals(1, transport.restoreCalls)
    }

    @Test
    fun `process start foreign active or restoring truth releases query transport`() = runTest {
        listOf(
            TweakerV4SessionState.ACTIVE,
            TweakerV4SessionState.RESTORING,
        ).forEach { foreignState ->
            val oldOwner = owned("old-process-owner", generation = 44L)
            val foreignTruth = activeTruth(oldOwner).copy(state = foreignState)
            val transport = FakeOwnerTransport().apply {
                queries += TweakerV4SessionAssessment.Blocked(foreignTruth)
            }
            val recreated = controller(transport)

            val result = recreated.confirmAfterProcessStart(500L, 500L)

            assertEquals(
                TweakerV4OwnerOutcome.Blocked(
                    reason = "Tweaker is not authoritatively Stock",
                    truth = foreignTruth,
                ),
                result,
            )
            assertTrue(recreated.state.value is TweakerV4OwnerState.Unconfirmed)
            assertFalse(transport.isBound)
            assertEquals(1, transport.bindCalls)
            assertEquals(1, transport.queryCalls)
            assertEquals(1, transport.unbindCalls)
            assertEquals(0, transport.beginCalls)
            assertEquals(0, transport.applyCalls)
            assertEquals(0, transport.cancelCalls)
            assertEquals(0, transport.renewCalls)
            assertEquals(0, transport.restoreCalls)
        }
    }

    @Test
    fun `released process-start block permits later Stock confirmation and activation`() = runTest {
        val oldOwner = owned("old-process-owner", generation = 44L)
        val transport = FakeOwnerTransport().apply {
            queries += TweakerV4SessionAssessment.Blocked(activeTruth(oldOwner))
            queries += stockAssessment()
            queries += stockAssessment()
            applyHandler = { acceptedApply(it) }
        }
        val recreated = controller(transport)

        assertTrue(recreated.confirmAfterProcessStart(500L, 500L) is TweakerV4OwnerOutcome.Blocked)
        assertTrue(recreated.state.value is TweakerV4OwnerState.Unconfirmed)
        assertEquals(
            TweakerV4OwnerOutcome.Stock,
            recreated.confirmAfterProcessStart(500L, 500L),
        )
        assertTrue(recreated.state.value is TweakerV4OwnerState.Stock)
        assertTrue(recreated.activate(request()) is TweakerV4OwnerOutcome.Active)
        assertTrue(recreated.state.value is TweakerV4OwnerState.Active)
        assertTrue(transport.isBound)
        assertEquals(3, transport.bindCalls)
        assertEquals(3, transport.queryCalls)
        assertEquals(2, transport.unbindCalls)
        assertEquals(1, transport.beginCalls)
        assertEquals(1, transport.applyCalls)
        assertEquals(0, transport.cancelCalls)
        assertEquals(0, transport.restoreCalls)
    }

    private fun controller(transport: FakeOwnerTransport): TweakerV4Owner =
        TweakerV4Owner(transport, transport.now)

    private fun request(operationTimeoutMs: Long = 2_500L) = TweakerV4ActivationRequest(
        profileId = "balanced",
        packageName = "dev.legacy.eden_emulator",
        bindTimeoutMs = 500L,
        queryTimeoutMs = 500L,
        operationTimeoutMs = operationTimeoutMs,
    )

    private class FakeOwnerTransport(
        val now: () -> Long = { 1_000L },
    ) : TweakerV4OwnerTransport {
        var isBound: Boolean = false
        var bindResult = true
        var api = 4
        var beginResult = true
        val queries = ArrayDeque<TweakerV4SessionAssessment>()
        var bindCalls = 0
        var unbindCalls = 0
        var queryCalls = 0
        var beginCalls = 0
        var applyCalls = 0
        var cancelCalls = 0
        var renewCalls = 0
        var restoreCalls = 0
        var cancelGeneration: Long? = null
        var cancelTransactionId: String? = null
        val restoredOwners = mutableListOf<TweakerV4OwnedSession>()
        private var attempts = 0

        var applyHandler: suspend (TweakerV4ApplyAttempt) -> TweakerV4OperationAssessment = {
            TweakerV4OperationAssessment.Rejected(
                TweakerV4Operation.APPLY,
                "NOT_CONFIGURED",
                "not configured",
                stockTruth(),
            )
        }
        var cancelHandler: suspend (TweakerV4ApplyAttempt) -> TweakerV4OperationAssessment = {
            acceptedCancel(it)
        }
        var renewHandler: suspend (TweakerV4OwnedSession) -> TweakerV4OperationAssessment = {
            acceptedRenew(it, it.leaseDeadlineElapsedRealtimeMs + 500L)
        }
        var restoreHandler: suspend (TweakerV4OwnedSession) -> TweakerV4OperationAssessment = {
            acceptedRestore()
        }
        var unbindHandler: suspend () -> Unit = {}

        override suspend fun bind(timeoutMs: Long): Boolean {
            bindCalls++
            isBound = bindResult
            return bindResult
        }

        override suspend fun unbind() {
            unbindCalls++
            unbindHandler()
            isBound = false
        }

        override suspend fun apiVersion(): Int = api

        override suspend fun query(apiVersion: Int, timeoutMs: Long): TweakerV4SessionAssessment {
            queryCalls++
            return queries.removeFirstOrNull()
                ?: TweakerV4SessionAssessment.Uncertain("No fake query result")
        }

        override fun createAttempt(
            profileId: String,
            packageName: String,
            timeoutMs: Long,
        ): TweakerV4ApplyAttempt = TweakerV4ApplyAttempt(
            requestId = "owner-${++attempts}",
            profileId = profileId,
            packageName = packageName,
            requestDeadlineElapsedRealtimeMs = now() + timeoutMs,
        )

        override suspend fun beginActiveSession(): Boolean {
            beginCalls++
            return beginResult
        }

        override suspend fun apply(
            apiVersion: Int,
            attempt: TweakerV4ApplyAttempt,
        ): TweakerV4OperationAssessment {
            applyCalls++
            return applyHandler(attempt)
        }

        override suspend fun cancelInFlight(
            apiVersion: Int,
            attempt: TweakerV4ApplyAttempt,
            timeoutMs: Long,
        ): TweakerV4OperationAssessment {
            cancelCalls++
            cancelGeneration = 0L
            cancelTransactionId = ""
            return cancelHandler(attempt)
        }

        override suspend fun renew(
            apiVersion: Int,
            owner: TweakerV4OwnedSession,
            timeoutMs: Long,
        ): TweakerV4OperationAssessment {
            renewCalls++
            return renewHandler(owner)
        }

        override suspend fun restore(
            apiVersion: Int,
            owner: TweakerV4OwnedSession,
            timeoutMs: Long,
        ): TweakerV4OperationAssessment {
            restoreCalls++
            restoredOwners += owner
            return restoreHandler(owner)
        }
    }

    private companion object {
        fun stockAssessment() = TweakerV4SessionAssessment.Stock(stockTruth())

        fun stockTruth() = TweakerV4SessionTruth(
            apiVersion = 4,
            state = TweakerV4SessionState.STOCK,
            mutationMayBeActive = false,
            requestId = null,
            generation = null,
            transactionId = null,
            requestDeadlineElapsedRealtimeMs = null,
            leaseDeadlineElapsedRealtimeMs = null,
            hardLeaseDeadlineElapsedRealtimeMs = null,
            leaseRenewable = false,
        )

        fun owned(
            requestId: String,
            generation: Long = 7L,
            requestDeadline: Long = 3_500L,
            leaseDeadline: Long = 8_000L,
            hardDeadline: Long = 10_000L,
        ) = TweakerV4OwnedSession(
            requestId = requestId,
            generation = generation,
            transactionId = "tx-$generation",
            requestDeadlineElapsedRealtimeMs = requestDeadline,
            leaseDeadlineElapsedRealtimeMs = leaseDeadline,
            hardLeaseDeadlineElapsedRealtimeMs = hardDeadline,
        )

        fun activeTruth(owner: TweakerV4OwnedSession) = TweakerV4SessionTruth(
            apiVersion = 4,
            state = TweakerV4SessionState.ACTIVE,
            mutationMayBeActive = true,
            requestId = owner.requestId,
            generation = owner.generation,
            transactionId = owner.transactionId,
            requestDeadlineElapsedRealtimeMs = owner.requestDeadlineElapsedRealtimeMs,
            leaseDeadlineElapsedRealtimeMs = owner.leaseDeadlineElapsedRealtimeMs,
            hardLeaseDeadlineElapsedRealtimeMs = owner.hardLeaseDeadlineElapsedRealtimeMs,
            leaseRenewable = true,
        )

        fun acceptedApply(
            attempt: TweakerV4ApplyAttempt,
            leaseDeadline: Long = 8_000L,
        ): TweakerV4OperationAssessment.Accepted {
            val owner = owned(
                requestId = attempt.requestId,
                requestDeadline = attempt.requestDeadlineElapsedRealtimeMs,
                leaseDeadline = leaseDeadline,
            )
            return TweakerV4OperationAssessment.Accepted(
                operation = TweakerV4Operation.APPLY,
                code = "APPLIED",
                truth = activeTruth(owner),
                owner = owner,
            )
        }

        fun acceptedRenew(
            current: TweakerV4OwnedSession,
            leaseDeadline: Long,
        ): TweakerV4OperationAssessment.Accepted {
            val renewed = owned(
                requestId = current.requestId,
                generation = current.generation,
                requestDeadline = current.requestDeadlineElapsedRealtimeMs,
                leaseDeadline = leaseDeadline,
                hardDeadline = current.hardLeaseDeadlineElapsedRealtimeMs,
            )
            return TweakerV4OperationAssessment.Accepted(
                operation = TweakerV4Operation.RENEW,
                code = "LEASE_RENEWED",
                truth = activeTruth(renewed),
                owner = renewed,
            )
        }

        fun acceptedCancel(attempt: TweakerV4ApplyAttempt) =
            TweakerV4OperationAssessment.Accepted(
                operation = TweakerV4Operation.CANCEL_IN_FLIGHT,
                code = "CANCEL_REQUESTED",
                truth = TweakerV4SessionTruth(
                    apiVersion = 4,
                    state = TweakerV4SessionState.APPLYING,
                    mutationMayBeActive = false,
                    requestId = attempt.requestId,
                    generation = null,
                    transactionId = null,
                    requestDeadlineElapsedRealtimeMs = null,
                    leaseDeadlineElapsedRealtimeMs = null,
                    hardLeaseDeadlineElapsedRealtimeMs = null,
                    leaseRenewable = false,
                ),
                owner = null,
            )

        fun acceptedRestore() = TweakerV4OperationAssessment.Accepted(
            operation = TweakerV4Operation.RESTORE,
            code = "RESTORED",
            truth = stockTruth(),
            owner = null,
        )
    }
}
