package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerV4ActivationRequest
import com.gruning.mimir.rewrite.core.launch.LaunchAttemptId
import com.gruning.mimir.rewrite.core.tweaker.TweakerFpsOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TweakerV4LaunchLifecycleTest {
    @Test
    fun `process start confirmation is query only and runs once`() = runTest {
        val owner = FakeOwner().apply {
            confirmOutcome = outcome(TweakerV4OwnerPortDisposition.STOCK)
        }
        var finished = 0
        val lifecycle = lifecycle(owner)

        assertTrue(lifecycle.confirmProcessStartOnce({ true }) { finished++ })
        assertFalse(lifecycle.confirmProcessStartOnce({ true }) { finished++ })
        assertTrue(lifecycle.mayOwnTransport())
        assertEquals(0, owner.confirmCalls)
        runCurrent()

        assertEquals(1, owner.confirmCalls)
        assertEquals(1, finished)
        assertEquals(0, owner.activateCalls)
        assertEquals(0, owner.renewCalls)
        assertEquals(0, owner.settleCalls)
    }

    @Test
    fun `blocked process start releases reservation before the following observation`() = runTest {
        val reservation = TweakerSessionReservation()
        val owner = FakeOwner().apply {
            confirmOutcome = outcome(TweakerV4OwnerPortDisposition.BLOCKED)
        }
        val callbackOrder = mutableListOf<String>()
        var releaseCalls = 0
        var finishedCalls = 0
        var cachedCapabilities: String? = null
        assertTrue(reservation.tryBeginOwner())
        val lifecycle = lifecycle(
            owner = owner,
            onTransportReleased = {
                releaseCalls++
                callbackOrder += "released"
                reservation.endOwner()
            },
        )

        assertTrue(
            lifecycle.confirmProcessStartOnce(validatePair = { true }) {
                finishedCalls++
                callbackOrder += "finished"
                if (reservation.tryBeginObservation()) {
                    cachedCapabilities = "fresh-capabilities"
                    reservation.endObservation()
                }
            },
        )
        assertFalse(lifecycle.confirmProcessStartOnce({ true }) { finishedCalls++ })
        runCurrent()

        assertEquals(1, owner.confirmCalls)
        assertEquals(1, releaseCalls)
        assertEquals(1, finishedCalls)
        assertEquals(listOf("released", "finished"), callbackOrder)
        assertEquals("fresh-capabilities", cachedCapabilities)
        assertFalse(lifecycle.mayOwnTransport())
        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }

    @Test
    fun `ticket performs zero work until post intent handoff then activates once`() = runTest {
        val owner = FakeOwner().apply { activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE) }
        val outcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
        val lifecycle = lifecycle(owner)
        val epoch = lifecycle.beginPreparation(AttemptId)

        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L)))
        assertEquals(0, owner.activateCalls)

        val handoff = lifecycle.handOffAfterIntentSent(
            attemptId = AttemptId,
            packageName = PackageName,
            validate = { true },
            startDebugLab = { false },
            onRuntimeOutcome = outcomes::add,
        )

        assertEquals(TweakerV4LaunchHandoff.Started("balanced"), handoff)
        // Local handoff returned before the process-owned job ran.
        assertEquals(0, owner.activateCalls)
        runCurrent()
        assertEquals(1, owner.activateCalls)
        assertEquals("balanced", owner.requests.single().profileId)
        assertEquals(PackageName, owner.requests.single().packageName)
        assertEquals(listOf(TweakerV4LaunchRuntimeOutcome.Active("balanced")), outcomes)

        assertTrue(
            lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, outcomes::add)
                is TweakerV4LaunchHandoff.Rejected,
        )
        assertEquals(1, owner.activateCalls)
        lifecycle.settle()
    }

    @Test
    fun `wrong package and expiration consume ticket without owner work`() = runTest {
        val owner = FakeOwner()
        var now = 1_000L
        val lifecycle = lifecycle(owner, now = { now })

        val wrongEpoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(wrongEpoch, product(expiresAt = 2_000L)))
        assertTrue(
            lifecycle.handOffAfterIntentSent(AttemptId, "org.other.emulator", { true }, { false }, {})
                is TweakerV4LaunchHandoff.Rejected,
        )

        val expiredEpoch = lifecycle.beginPreparation(SecondAttemptId)
        assertTrue(lifecycle.schedule(expiredEpoch, product(SecondAttemptId, expiresAt = 2_000L)))
        now = 2_000L
        assertTrue(
            lifecycle.handOffAfterIntentSent(SecondAttemptId, PackageName, { true }, { false }, {})
                is TweakerV4LaunchHandoff.Rejected,
        )
        runCurrent()
        assertEquals(0, owner.activateCalls)
    }

    @Test
    fun `abandoned or overtaken preparation can never publish a late ticket`() = runTest {
        val owner = FakeOwner()
        val lifecycle = lifecycle(owner)
        val oldEpoch = lifecycle.beginPreparation(AttemptId)
        lifecycle.retireAttempt(AttemptId)
        assertFalse(lifecycle.schedule(oldEpoch, product(expiresAt = 5_000L)))

        val nextEpoch = lifecycle.beginPreparation(SecondAttemptId)
        assertTrue(lifecycle.schedule(nextEpoch, product(SecondAttemptId, expiresAt = 5_000L)))
        assertEquals(
            TweakerV4LaunchHandoff.Started("balanced"),
            lifecycle.handOffAfterIntentSent(SecondAttemptId, PackageName, { true }, { false }, {}),
        )
        // The handoff closed the generation; a detached result from it cannot republish.
        assertFalse(lifecycle.schedule(nextEpoch, product(SecondAttemptId, expiresAt = 5_000L)))
        runCurrent()
        assertEquals(1, owner.activateCalls)
    }

    @Test
    fun `stale same-package callback cannot consume the newer launch ticket`() = runTest {
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
        }
        val lifecycle = lifecycle(owner)
        val firstEpoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(firstEpoch, product(expiresAt = 5_000L)))
        val secondEpoch = lifecycle.beginPreparation(SecondAttemptId)
        assertTrue(lifecycle.schedule(secondEpoch, product(SecondAttemptId, expiresAt = 5_000L)))

        assertTrue(
            lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
                is TweakerV4LaunchHandoff.Rejected,
        )
        assertTrue(lifecycle.hasPendingLaunch())
        assertEquals(
            TweakerV4LaunchHandoff.Started("balanced"),
            lifecycle.handOffAfterIntentSent(SecondAttemptId, PackageName, { true }, { false }, {}),
        )
        runCurrent()

        assertEquals(1, owner.activateCalls)
        assertFalse(lifecycle.hasPendingLaunch())
        lifecycle.settle()
    }

    @Test
    fun `revoked admission fails asynchronously without activating`() = runTest {
        val owner = FakeOwner()
        val outcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
        val lifecycle = lifecycle(owner)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L)))

        assertTrue(
            lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { false }, { false }, outcomes::add)
                is TweakerV4LaunchHandoff.Started,
        )
        runCurrent()

        assertEquals(0, owner.activateCalls)
        val failure = outcomes.single() as TweakerV4LaunchRuntimeOutcome.Failed
        assertFalse(failure.stockConfirmed)
    }

    @Test
    fun `non-active activation outcomes are terminally settled before presentation`() = runTest {
        val stockOwner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.STOCK)
            settleOutcome = outcome(TweakerV4OwnerPortDisposition.STOCK)
        }
        val stockOutcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
        lifecycle(stockOwner).also { lifecycle ->
            val epoch = lifecycle.beginPreparation(AttemptId)
            assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L)))
            lifecycle.handOffAfterIntentSent(
                AttemptId,
                PackageName,
                { true },
                { false },
                stockOutcomes::add,
            )
        }
        runCurrent()
        val stockFailure = stockOutcomes.single() as TweakerV4LaunchRuntimeOutcome.Failed
        assertTrue(stockFailure.stockConfirmed)
        assertEquals(1, stockOwner.settleCalls)

        listOf(
            TweakerV4OwnerPortDisposition.BLOCKED,
            TweakerV4OwnerPortDisposition.UNCERTAIN,
        ).forEachIndexed { index, disposition ->
            val owner = FakeOwner().apply {
                activateOutcome = outcome(disposition)
                settleOutcome = outcome(TweakerV4OwnerPortDisposition.UNCERTAIN)
            }
            val outcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
            val attemptId = LaunchAttemptId.fromTestValue("non-active-$index")
            val lifecycle = lifecycle(owner)
            val epoch = lifecycle.beginPreparation(attemptId)
            assertTrue(lifecycle.schedule(epoch, product(attemptId, expiresAt = 5_000L)))
            lifecycle.handOffAfterIntentSent(
                attemptId,
                PackageName,
                { true },
                { false },
                outcomes::add,
            )
            runCurrent()
            val failure = outcomes.single() as TweakerV4LaunchRuntimeOutcome.Failed
            assertFalse(failure.stockConfirmed)
            assertEquals(1, owner.settleCalls)
        }
    }

    @Test
    fun `renewal starts at cadence and settlement stops future ticks`() = runTest {
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            renewOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            settleOutcome = outcome(TweakerV4OwnerPortDisposition.STOCK)
        }
        val lifecycle = lifecycle(owner)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 50_000L)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()

        advanceTimeBy(9_999L)
        runCurrent()
        assertEquals(0, owner.renewCalls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, owner.renewCalls)

        lifecycle.settle()
        assertEquals(1, owner.settleCalls)
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(1, owner.renewCalls)
    }

    @Test
    fun `renewal failure restores Stock and reports the renewal phase`() = runTest {
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            renewOutcome = outcome(TweakerV4OwnerPortDisposition.UNCERTAIN)
            settleOutcome = outcome(TweakerV4OwnerPortDisposition.STOCK)
        }
        val outcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
        val lifecycle = lifecycle(owner)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 50_000L)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, outcomes::add)
        runCurrent()

        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(1, owner.renewCalls)
        assertEquals(1, owner.settleCalls)
        val failure = outcomes.last() as TweakerV4LaunchRuntimeOutcome.Failed
        assertEquals(TweakerV4LaunchFailurePhase.RENEWAL, failure.phase)
        assertTrue(failure.stockConfirmed)
    }

    @Test
    fun `terminal settlement wins while renewal is in flight`() = runTest {
        val renewalEntered = CompletableDeferred<Unit>()
        val releaseRenewal = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            renewBlock = {
                renewalEntered.complete(Unit)
                withContext(NonCancellable) { releaseRenewal.await() }
                transportMayBeOwned = true
                outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            }
            settleBlock = {
                transportMayBeOwned = false
                outcome(TweakerV4OwnerPortDisposition.STOCK)
            }
        }
        val lifecycle = lifecycle(owner)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 50_000L)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, outcomes::add)
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        renewalEntered.await()

        val terminal = async { lifecycle.settle() }
        runCurrent()
        assertTrue(lifecycle.mayOwnTransport())
        releaseRenewal.complete(Unit)
        runCurrent()

        assertEquals(TweakerV4OwnerPortDisposition.STOCK, terminal.await().disposition)
        assertFalse(owner.transportMayBeOwned)
        assertEquals(listOf(TweakerV4LaunchRuntimeOutcome.Active("balanced")), outcomes)
    }

    @Test
    fun `terminal settlement reaches owner while activation is blocked`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = FakeOwner().apply {
            activateBlock = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                outcome(TweakerV4OwnerPortDisposition.STOCK)
            }
            settleOutcome = outcome(TweakerV4OwnerPortDisposition.PENDING)
        }
        val outcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
        val lifecycle = lifecycle(owner)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, outcomes::add)
        runCurrent()
        entered.await()

        val settlement = async { lifecycle.settle() }
        runCurrent()
        assertEquals(1, owner.settleCalls)
        assertFalse(settlement.isCompleted)

        release.complete(Unit)
        runCurrent()
        assertEquals(TweakerV4OwnerPortDisposition.PENDING, settlement.await().disposition)
        // Return settlement suppresses a late launch-status warning.
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `terminal request before activation commit prevents the owner call`() = runTest {
        val commitEntered = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val owner = FakeOwner()
        val lifecycle = lifecycle(
            owner = owner,
            beforeActivationCommit = {
                commitEntered.complete(Unit)
                withContext(NonCancellable) { releaseCommit.await() }
            },
        )
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()
        commitEntered.await()

        val settlement = async { lifecycle.settle() }
        runCurrent()
        assertTrue(lifecycle.mayOwnTransport())
        releaseCommit.complete(Unit)
        runCurrent()

        assertEquals(TweakerV4OwnerPortDisposition.STOCK, settlement.await().disposition)
        assertEquals(0, owner.activateCalls)
    }

    @Test
    fun `settlement remains transport-owning until a late active result is restored`() = runTest {
        val activateEntered = CompletableDeferred<Unit>()
        val releaseActivate = CompletableDeferred<Unit>()
        val owner = FakeOwner().apply {
            activateBlock = {
                activateEntered.complete(Unit)
                withContext(NonCancellable) { releaseActivate.await() }
                transportMayBeOwned = true
                outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            }
            settleBlock = {
                transportMayBeOwned = false
                outcome(TweakerV4OwnerPortDisposition.STOCK)
            }
        }
        val lifecycle = lifecycle(owner)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()
        activateEntered.await()

        val settlement = async { lifecycle.settle() }
        runCurrent()
        assertTrue(lifecycle.mayOwnTransport())
        releaseActivate.complete(Unit)
        runCurrent()

        assertEquals(TweakerV4OwnerPortDisposition.STOCK, settlement.await().disposition)
        assertFalse(owner.transportMayBeOwned)
        assertTrue(owner.settleCalls >= 2)
    }

    @Test
    fun `activation completing after settlement timeout still performs an exact late restore`() = runTest {
        val activateEntered = CompletableDeferred<Unit>()
        val releaseActivate = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
        val owner = FakeOwner().apply {
            activateBlock = {
                activateEntered.complete(Unit)
                withContext(NonCancellable) { releaseActivate.await() }
                transportMayBeOwned = true
                outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            }
            settleBlock = {
                transportMayBeOwned = false
                outcome(TweakerV4OwnerPortDisposition.STOCK)
            }
        }
        val lifecycle = lifecycle(owner)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, outcomes::add)
        runCurrent()
        activateEntered.await()

        val settlement = async { lifecycle.settle() }
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(TweakerV4OwnerPortDisposition.STOCK, settlement.await().disposition)
        assertTrue(lifecycle.mayOwnTransport())

        releaseActivate.complete(Unit)
        runCurrent()

        assertFalse(owner.transportMayBeOwned)
        assertFalse(lifecycle.mayOwnTransport())
        assertEquals(2, owner.settleCalls)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `pending terminal state is reconciled once after lifecycle jobs settle`() = runTest {
        val owner = FakeOwner().apply {
            transportMayBeOwned = true
            settleOutcome = outcome(TweakerV4OwnerPortDisposition.PENDING)
            reconcileOutcome = outcome(TweakerV4OwnerPortDisposition.STOCK)
        }
        val lifecycle = lifecycle(owner)

        val terminal = lifecycle.settle()

        assertEquals(TweakerV4OwnerPortDisposition.STOCK, terminal.disposition)
        assertEquals(2, owner.settleCalls)
        assertEquals(1, owner.reconcileCalls)
    }

    @Test
    fun `launch warnings distinguish rejection stock renewal and unresolved truth`() {
        assertEquals(null, TweakerV4LaunchHandoff.NotScheduled.warningOrNull())
        assertEquals(null, TweakerV4LaunchHandoff.Started("balanced").warningOrNull())
        assertEquals(
            "TUNING UNCHANGED · GAME CONTINUES",
            TweakerV4LaunchHandoff.Rejected("expired").warningOrNull(),
        )
        assertEquals(
            "STOCK · GAME CONTINUES UNTUNED",
            TweakerV4LaunchRuntimeOutcome.Failed(
                profileId = "balanced",
                reason = "rejected",
                stockConfirmed = true,
            ).warningOrNull(),
        )
        assertEquals(
            "TUNING UNCHANGED · GAME CONTINUES",
            TweakerV4LaunchRuntimeOutcome.Failed(
                profileId = "balanced",
                reason = "admission changed",
                noNewSessionConfirmed = true,
            ).warningOrNull(),
        )
        assertEquals(
            "STOCK · TUNING ENDED, GAME CONTINUES",
            TweakerV4LaunchRuntimeOutcome.Failed(
                profileId = "balanced",
                reason = "lease ended",
                phase = TweakerV4LaunchFailurePhase.RENEWAL,
                stockConfirmed = true,
            ).warningOrNull(),
        )
        assertEquals(
            "TWEAKER STATUS UNCERTAIN — GAME CONTINUES; STOCK RESTORE IS UNCONFIRMED",
            TweakerV4LaunchRuntimeOutcome.Failed(
                profileId = "balanced",
                reason = "query failed",
            ).warningOrNull(),
        )
    }

    @Test
    fun `debug lab ticket starts only after handoff and is one shot`() = runTest {
        val owner = FakeOwner()
        var labCalls = 0
        val outcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
        val lifecycle = lifecycle(owner)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, debugLab(expiresAt = 5_000L)))
        assertEquals(0, labCalls)

        lifecycle.handOffAfterIntentSent(
            attemptId = AttemptId,
            packageName = PackageName,
            validate = { true },
            startDebugLab = {
                labCalls++
                true
            },
            onRuntimeOutcome = outcomes::add,
        )
        assertEquals(0, labCalls)
        runCurrent()
        assertEquals(1, labCalls)
        assertEquals(listOf(TweakerV4LaunchRuntimeOutcome.DebugLabStarted("balanced")), outcomes)
        assertEquals(0, owner.activateCalls)
    }

    @Test
    fun `fps only ticket applies after dispatch and restores on return`() = runTest {
        val owner = FakeOwner()
        val fps = FakeFps().apply { activateOutcome = fpsActive() }
        val outcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
        val lifecycle = lifecycle(owner, fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        val ticket = product(expiresAt = 5_000L).copy(
            profileId = "stock",
            activateProfile = false,
            systemProfileId = "stock",
            frameLimitHz = 60,
        )
        assertTrue(lifecycle.schedule(epoch, ticket))

        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, outcomes::add)
        runCurrent()

        assertEquals(0, owner.activateCalls)
        assertEquals(1, fps.activateCalls)
        assertEquals(listOf(TweakerV4LaunchRuntimeOutcome.Active("stock")), outcomes)
        assertTrue(lifecycle.mayOwnTransport())

        assertEquals(TweakerV4OwnerPortDisposition.STOCK, lifecycle.settle().disposition)
        assertEquals(1, fps.settleCalls)
        assertEquals(1, fps.releaseCalls)
        assertFalse(lifecycle.mayOwnTransport())
    }

    @Test
    fun `composite return restores fps before profile even when both are active`() = runTest {
        val events = mutableListOf<String>()
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            settleBlock = {
                events += "profile-restore"
                transportMayBeOwned = false
                outcome(TweakerV4OwnerPortDisposition.STOCK)
            }
        }
        val fps = FakeFps(events).apply { activateOutcome = fpsActive() }
        val lifecycle = lifecycle(owner, fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L).copy(frameLimitHz = 60)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()

        lifecycle.settle()

        assertEquals(listOf("fps-apply", "fps-restore", "profile-restore"), events)
    }

    @Test
    fun `renewal failure restores fps before profile`() = runTest {
        val events = mutableListOf<String>()
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            renewOutcome = outcome(TweakerV4OwnerPortDisposition.UNCERTAIN)
            settleBlock = {
                events += "profile-restore"
                transportMayBeOwned = false
                outcome(TweakerV4OwnerPortDisposition.STOCK)
            }
        }
        val fps = FakeFps(events).apply { activateOutcome = fpsActive() }
        val lifecycle = lifecycle(owner, fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 50_000L).copy(frameLimitHz = 60)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()

        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(listOf("fps-apply", "fps-restore", "profile-restore"), events)
        assertEquals(1, owner.renewCalls)
        assertFalse(lifecycle.mayOwnTransport())
    }

    @Test
    fun `hanging fps apply cannot prevent bounded profile settlement`() = runTest {
        val applyEntered = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            settleBlock = {
                transportMayBeOwned = false
                outcome(TweakerV4OwnerPortDisposition.STOCK)
            }
        }
        val fps = FakeFps().apply {
            activateBlock = {
                applyEntered.complete(Unit)
                withContext(NonCancellable) { releaseApply.await() }
                fpsActive()
            }
            settleBlock = {
                // Mirrors the real adapter's cancellable dispatcher hop. Late cleanup must survive the
                // already-cancelled activation job that owns the exact apply result.
                kotlinx.coroutines.yield()
                TweakerFpsLaunchPortOutcome(TweakerFpsLaunchPortDisposition.STOCK, "stock")
            }
        }
        val lifecycle = lifecycle(owner, fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L).copy(frameLimitHz = 60)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()
        applyEntered.await()

        val terminal = async { lifecycle.settle() }
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()

        assertEquals(TweakerV4OwnerPortDisposition.UNCERTAIN, terminal.await().disposition)
        assertEquals(1, owner.settleCalls)
        assertTrue(lifecycle.mayOwnTransport())

        releaseApply.complete(Unit)
        runCurrent()
        assertEquals(1, fps.settleCalls)
        assertFalse(lifecycle.mayOwnTransport())
    }

    @Test
    fun `throwing fps restore still attempts profile settlement and retains debt`() = runTest {
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            settleOutcome = outcome(TweakerV4OwnerPortDisposition.STOCK)
        }
        val fps = FakeFps().apply {
            activateOutcome = fpsActive()
            settleBlock = { error("restore transport failed") }
        }
        val lifecycle = lifecycle(owner, fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L).copy(frameLimitHz = 60)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()

        assertEquals(TweakerV4OwnerPortDisposition.UNCERTAIN, lifecycle.settle().disposition)
        assertEquals(1, owner.settleCalls)
        assertTrue(lifecycle.mayOwnTransport())
    }

    @Test
    fun `throwing fps apply rolls active profile back`() = runTest {
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            settleOutcome = outcome(TweakerV4OwnerPortDisposition.STOCK)
        }
        val fps = FakeFps().apply {
            activateBlock = { error("apply transport failed") }
        }
        val outcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
        val lifecycle = lifecycle(owner, fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L).copy(frameLimitHz = 60)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, outcomes::add)
        runCurrent()

        assertEquals(1, fps.settleCalls)
        assertEquals(1, owner.settleCalls)
        assertTrue(outcomes.single() is TweakerV4LaunchRuntimeOutcome.Failed)
        assertFalse(lifecycle.mayOwnTransport())
    }

    @Test
    fun `pre mutation fps uncertainty does not create retained launch debt`() = runTest {
        val owner = FakeOwner()
        val fps = FakeFps().apply {
            activateOutcome = TweakerFpsLaunchPortOutcome(
                disposition = TweakerFpsLaunchPortDisposition.UNCERTAIN,
                reason = "bind failed before apply",
                mutationMayBeActive = false,
            )
        }
        val lifecycle = lifecycle(owner, fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(
            lifecycle.schedule(
                epoch,
                product(expiresAt = 5_000L).copy(
                    profileId = "stock",
                    activateProfile = false,
                    systemProfileId = "stock",
                    frameLimitHz = 60,
                ),
            ),
        )
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()

        assertFalse(lifecycle.mayOwnTransport())
        val nextEpoch = lifecycle.beginPreparation(SecondAttemptId)
        assertTrue(lifecycle.schedule(nextEpoch, product(SecondAttemptId, 5_000L)))
    }

    @Test
    fun `post mutation fps uncertainty retains debt and blocks a newer launch`() = runTest {
        val fps = FakeFps().apply {
            activateOutcome = TweakerFpsLaunchPortOutcome(
                disposition = TweakerFpsLaunchPortDisposition.UNCERTAIN,
                reason = "apply result lost",
                mutationMayBeActive = true,
            )
            settleOutcome = TweakerFpsLaunchPortOutcome(
                disposition = TweakerFpsLaunchPortDisposition.RETAINED,
                reason = "restore truth retained",
            )
        }
        val lifecycle = lifecycle(FakeOwner(), fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(
            lifecycle.schedule(
                epoch,
                product(expiresAt = 5_000L).copy(
                    profileId = "stock",
                    activateProfile = false,
                    systemProfileId = "stock",
                    frameLimitHz = 60,
                ),
            ),
        )
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()

        assertTrue(lifecycle.mayOwnTransport())
        val nextEpoch = lifecycle.beginPreparation(SecondAttemptId)
        assertFalse(lifecycle.schedule(nextEpoch, product(SecondAttemptId, 5_000L)))
    }

    @Test
    fun `fps rejection rolls an active profile back instead of retaining a partial configuration`() = runTest {
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            settleOutcome = outcome(TweakerV4OwnerPortDisposition.STOCK)
        }
        val fps = FakeFps().apply {
            activateOutcome = TweakerFpsLaunchPortOutcome(
                TweakerFpsLaunchPortDisposition.REJECTED,
                "unsupported",
            )
        }
        val outcomes = mutableListOf<TweakerV4LaunchRuntimeOutcome>()
        val lifecycle = lifecycle(owner, fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L).copy(frameLimitHz = 60)))

        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, outcomes::add)
        runCurrent()

        assertEquals(1, owner.activateCalls)
        assertEquals(1, owner.settleCalls)
        assertTrue(outcomes.single() is TweakerV4LaunchRuntimeOutcome.Failed)
    }

    @Test
    fun `retained fps restore blocks a newer launch ticket`() = runTest {
        val owner = FakeOwner()
        val active = fpsActive()
        val fps = FakeFps().apply {
            activateOutcome = active
            settleOutcome = TweakerFpsLaunchPortOutcome(
                TweakerFpsLaunchPortDisposition.RETAINED,
                "restore retained",
                active.owner,
            )
        }
        val lifecycle = lifecycle(owner, fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(
            lifecycle.schedule(
                epoch,
                product(expiresAt = 5_000L).copy(
                    profileId = "stock",
                    activateProfile = false,
                    systemProfileId = "stock",
                    frameLimitHz = 60,
                ),
            ),
        )
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()

        assertEquals(TweakerV4OwnerPortDisposition.UNCERTAIN, lifecycle.settle().disposition)
        val nextEpoch = lifecycle.beginPreparation(SecondAttemptId)
        assertFalse(lifecycle.schedule(nextEpoch, product(SecondAttemptId, 5_000L)))
        assertTrue(lifecycle.mayOwnTransport())
    }

    @Test
    fun `live fps apply arms the launch obligation and return settlement restores it`() = runTest {
        val owner = FakeOwner()
        val active = fpsActive()
        val fps = FakeFps().apply { activateOutcome = active }
        val lifecycle = lifecycle(owner, fps = fps)

        val outcome = lifecycle.applyLiveFps(
            packageName = PackageName,
            targetFps = 60,
            expectedSystemProfileId = "stock",
            callerClaimedReservation = true,
        )

        assertEquals(TweakerLiveFpsOutcome.Applied(checkNotNull(active.owner)), outcome)
        assertEquals(1, fps.activateCalls)
        assertEquals(PackageName, fps.lastActivatePackageName)
        assertEquals(60, fps.lastActivateTargetFps)
        assertEquals("stock", fps.lastActivateExpectedSystemProfileId)
        assertEquals(false, fps.lastActivateProfileOwnerActive)
        assertEquals(0, owner.activateCalls)
        assertTrue(lifecycle.mayOwnTransport())
        val nextEpoch = lifecycle.beginPreparation(SecondAttemptId)
        assertFalse(lifecycle.schedule(nextEpoch, product(SecondAttemptId, 5_000L)))

        assertEquals(TweakerV4OwnerPortDisposition.STOCK, lifecycle.settle().disposition)
        assertEquals(1, fps.settleCalls)
        assertEquals(1, fps.releaseCalls)
        assertFalse(lifecycle.mayOwnTransport())
    }

    @Test
    fun `live fps change restores the prior obligation before applying the new one`() = runTest {
        val events = mutableListOf<String>()
        val fps = FakeFps(events).apply { activateOutcome = fpsActive() }
        val lifecycle = lifecycle(FakeOwner(), fps = fps)
        assertTrue(
            lifecycle.applyLiveFps(PackageName, 60, "stock", callerClaimedReservation = true)
                is TweakerLiveFpsOutcome.Applied,
        )

        val changed = lifecycle.applyLiveFps(PackageName, 40, "stock", callerClaimedReservation = false)

        assertTrue(changed is TweakerLiveFpsOutcome.Applied)
        assertEquals(listOf("fps-apply", "fps-restore", "fps-apply"), events)
        assertEquals(0, fps.releaseCalls)
        assertTrue(lifecycle.mayOwnTransport())
    }

    @Test
    fun `live fps removal restores now and releases the idle transport`() = runTest {
        val fps = FakeFps().apply { activateOutcome = fpsActive() }
        val lifecycle = lifecycle(FakeOwner(), fps = fps)
        assertTrue(
            lifecycle.applyLiveFps(PackageName, 60, "stock", callerClaimedReservation = true)
                is TweakerLiveFpsOutcome.Applied,
        )

        val removed = lifecycle.restoreLiveFps(callerClaimedReservation = false)

        assertEquals(TweakerLiveFpsOutcome.Stock, removed)
        assertEquals(1, fps.settleCalls)
        assertEquals(1, fps.releaseCalls)
        assertFalse(lifecycle.mayOwnTransport())
    }

    @Test
    fun `live fps rejection leaves an active profile session untouched`() = runTest {
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            renewOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
        }
        val fps = FakeFps()
        val lifecycle = lifecycle(owner, fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()
        owner.transportMayBeOwned = true
        owner.currentOutcomeOverride = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
        fps.activateOutcome = TweakerFpsLaunchPortOutcome(
            TweakerFpsLaunchPortDisposition.REJECTED,
            "Selected frame limit is no longer supported",
        )

        val outcome = lifecycle.applyLiveFps(PackageName, 60, "balanced", callerClaimedReservation = false)

        assertTrue(outcome is TweakerLiveFpsOutcome.Rejected)
        assertEquals(true, fps.lastActivateProfileOwnerActive)
        assertEquals(0, owner.settleCalls)
        assertEquals(0, fps.releaseCalls)
        assertTrue(lifecycle.mayOwnTransport())
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, owner.renewCalls)
    }

    @Test
    fun `live fps apply is rejected while a launch ticket is pending`() = runTest {
        val fps = FakeFps().apply { activateOutcome = fpsActive() }
        val lifecycle = lifecycle(FakeOwner(), fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 5_000L)))

        val outcome = lifecycle.applyLiveFps(PackageName, 60, "stock", callerClaimedReservation = true)

        assertTrue(outcome is TweakerLiveFpsOutcome.Busy)
        assertEquals(0, fps.activateCalls)
    }

    @Test
    fun `live fps apply rejects a stale transport ownership claim`() = runTest {
        val fps = FakeFps().apply { activateOutcome = fpsActive() }
        val lifecycle = lifecycle(FakeOwner(), fps = fps)

        assertTrue(
            lifecycle.applyLiveFps(PackageName, 60, "stock", callerClaimedReservation = false)
                is TweakerLiveFpsOutcome.Busy,
        )
        assertEquals(0, fps.activateCalls)

        assertTrue(
            lifecycle.applyLiveFps(PackageName, 60, "stock", callerClaimedReservation = true)
                is TweakerLiveFpsOutcome.Applied,
        )
        assertTrue(
            lifecycle.applyLiveFps(PackageName, 40, "stock", callerClaimedReservation = true)
                is TweakerLiveFpsOutcome.Busy,
        )
        assertEquals(1, fps.activateCalls)
    }

    @Test
    fun `retained live fps restore keeps debt and blocks the next launch ticket`() = runTest {
        val active = fpsActive()
        val fps = FakeFps().apply {
            activateOutcome = active
            settleOutcome = TweakerFpsLaunchPortOutcome(
                TweakerFpsLaunchPortDisposition.RETAINED,
                "restore retained",
                active.owner,
            )
        }
        val lifecycle = lifecycle(FakeOwner(), fps = fps)
        assertTrue(
            lifecycle.applyLiveFps(PackageName, 60, "stock", callerClaimedReservation = true)
                is TweakerLiveFpsOutcome.Applied,
        )

        assertEquals(TweakerV4OwnerPortDisposition.UNCERTAIN, lifecycle.settle().disposition)
        val nextEpoch = lifecycle.beginPreparation(SecondAttemptId)
        assertFalse(lifecycle.schedule(nextEpoch, product(SecondAttemptId, 5_000L)))
        assertTrue(lifecycle.mayOwnTransport())
        assertEquals(0, fps.releaseCalls)
    }

    @Test
    fun `renewal failure during an in flight live fps apply retains debt instead of claiming stock`() = runTest {
        val applyEntered = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        val owner = FakeOwner().apply {
            activateOutcome = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
            renewOutcome = outcome(TweakerV4OwnerPortDisposition.UNCERTAIN)
            settleBlock = {
                transportMayBeOwned = false
                outcome(TweakerV4OwnerPortDisposition.STOCK)
            }
        }
        val fps = FakeFps().apply {
            activateBlock = {
                applyEntered.complete(Unit)
                withContext(NonCancellable) { releaseApply.await() }
                fpsActive()
            }
        }
        val lifecycle = lifecycle(owner, fps = fps)
        val epoch = lifecycle.beginPreparation(AttemptId)
        assertTrue(lifecycle.schedule(epoch, product(expiresAt = 50_000L)))
        lifecycle.handOffAfterIntentSent(AttemptId, PackageName, { true }, { false }, {})
        runCurrent()
        owner.transportMayBeOwned = true
        owner.currentOutcomeOverride = outcome(TweakerV4OwnerPortDisposition.ACTIVE)
        val live = async {
            lifecycle.applyLiveFps(PackageName, 60, "balanced", callerClaimedReservation = false)
        }
        runCurrent()
        applyEntered.await()

        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(1, owner.renewCalls)
        assertEquals(1, owner.settleCalls)
        assertEquals(0, fps.settleCalls)
        assertEquals(0, fps.releaseCalls)
        assertTrue(lifecycle.mayOwnTransport())

        owner.currentOutcomeOverride = null
        releaseApply.complete(Unit)
        runCurrent()
        assertTrue(live.await() is TweakerLiveFpsOutcome.Applied)

        assertEquals(TweakerV4OwnerPortDisposition.STOCK, lifecycle.settle().disposition)
        assertEquals(1, fps.settleCalls)
        assertEquals(1, fps.releaseCalls)
        assertFalse(lifecycle.mayOwnTransport())
    }

    @Test
    fun `terminal settlement retains debt while a live fps apply is still settling`() = runTest {
        val applyEntered = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        val fps = FakeFps().apply {
            activateBlock = {
                applyEntered.complete(Unit)
                withContext(NonCancellable) { releaseApply.await() }
                fpsActive()
            }
            settleBlock = {
                kotlinx.coroutines.yield()
                TweakerFpsLaunchPortOutcome(TweakerFpsLaunchPortDisposition.STOCK, "stock")
            }
        }
        val lifecycle = lifecycle(FakeOwner(), fps = fps)
        val live = async {
            lifecycle.applyLiveFps(PackageName, 60, "stock", callerClaimedReservation = true)
        }
        runCurrent()
        applyEntered.await()

        val terminal = async { lifecycle.settle() }
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()

        assertEquals(TweakerV4OwnerPortDisposition.UNCERTAIN, terminal.await().disposition)
        assertTrue(lifecycle.mayOwnTransport())

        releaseApply.complete(Unit)
        runCurrent()
        assertTrue(live.await() is TweakerLiveFpsOutcome.Superseded)
        assertEquals(1, fps.settleCalls)
        assertFalse(lifecycle.mayOwnTransport())
    }

    private fun kotlinx.coroutines.test.TestScope.lifecycle(
        owner: TweakerV4OwnerPort,
        fps: TweakerFpsLaunchPort = DisabledTweakerFpsLaunchPort,
        now: () -> Long = { testScheduler.currentTime },
        beforeActivationCommit: suspend () -> Unit = {},
        onTransportReleased: () -> Unit = {},
    ) = TweakerV4LaunchLifecycle(
        owner = owner,
        fps = fps,
        scope = backgroundScope,
        elapsedRealtimeMs = now,
        settlementJoinTimeoutMs = 1_000L,
        beforeActivationCommit = beforeActivationCommit,
        onTransportReleased = onTransportReleased,
    )

    private fun product(
        attemptId: LaunchAttemptId = AttemptId,
        expiresAt: Long,
    ) = PendingTweakerV4Launch.Product(
        attemptId = attemptId,
        profileId = "balanced",
        packageName = PackageName,
        expiresAtElapsedRealtimeMs = expiresAt,
    )

    private fun fpsActive() = TweakerFpsLaunchPortOutcome(
        TweakerFpsLaunchPortDisposition.ACTIVE,
        "active",
        TweakerFpsOwner(
            requestId = "mimir.fps.request",
            generation = 1L,
            transactionId = "mimir.fps.tx",
            packageName = PackageName,
            targetFps = 60,
        ),
    )

    private fun debugLab(expiresAt: Long) = PendingTweakerV4Launch.DebugLab(
        attemptId = AttemptId,
        profileId = "balanced",
        packageName = PackageName,
        expiresAtElapsedRealtimeMs = expiresAt,
    )

    private fun outcome(
        disposition: TweakerV4OwnerPortDisposition,
        reason: String = disposition.name.lowercase(),
    ) = TweakerV4OwnerPortOutcome(disposition, reason)

    private class FakeOwner : TweakerV4OwnerPort {
        var transportMayBeOwned = false
        var confirmCalls = 0
        var activateCalls = 0
        var renewCalls = 0
        var settleCalls = 0
        var reconcileCalls = 0
        val requests = mutableListOf<TweakerV4ActivationRequest>()
        var activateOutcome = TweakerV4OwnerPortOutcome(
            TweakerV4OwnerPortDisposition.STOCK,
            "stock",
        )
        var renewOutcome = TweakerV4OwnerPortOutcome(
            TweakerV4OwnerPortDisposition.STOCK,
            "stock",
        )
        var settleOutcome = TweakerV4OwnerPortOutcome(
            TweakerV4OwnerPortDisposition.STOCK,
            "stock",
        )
        var reconcileOutcome = TweakerV4OwnerPortOutcome(
            TweakerV4OwnerPortDisposition.STOCK,
            "stock",
        )
        var activateBlock: (suspend () -> TweakerV4OwnerPortOutcome)? = null
        var renewBlock: (suspend () -> TweakerV4OwnerPortOutcome)? = null
        var settleBlock: (suspend () -> TweakerV4OwnerPortOutcome)? = null
        var confirmOutcome = TweakerV4OwnerPortOutcome(
            TweakerV4OwnerPortDisposition.STOCK,
            "stock",
        )

        var currentOutcomeOverride: TweakerV4OwnerPortOutcome? = null

        override fun mayOwnTransport(): Boolean = transportMayBeOwned

        override fun currentOutcome(): TweakerV4OwnerPortOutcome = currentOutcomeOverride
            ?: TweakerV4OwnerPortOutcome(
                disposition = if (transportMayBeOwned) {
                    TweakerV4OwnerPortDisposition.PENDING
                } else {
                    TweakerV4OwnerPortDisposition.STOCK
                },
                reason = if (transportMayBeOwned) "pending" else "stock",
            )

        override suspend fun confirmAfterProcessStart(
            bindTimeoutMs: Long,
            queryTimeoutMs: Long,
        ): TweakerV4OwnerPortOutcome {
            confirmCalls++
            return confirmOutcome
        }

        override suspend fun activate(request: TweakerV4ActivationRequest): TweakerV4OwnerPortOutcome {
            activateCalls++
            requests += request
            return activateBlock?.invoke() ?: activateOutcome
        }

        override suspend fun renew(
            queryTimeoutMs: Long,
            operationTimeoutMs: Long,
        ): TweakerV4OwnerPortOutcome {
            renewCalls++
            return renewBlock?.invoke() ?: renewOutcome
        }

        override suspend fun settle(
            queryTimeoutMs: Long,
            operationTimeoutMs: Long,
        ): TweakerV4OwnerPortOutcome {
            settleCalls++
            return settleBlock?.invoke() ?: settleOutcome
        }

        override suspend fun reconcile(
            queryTimeoutMs: Long,
            operationTimeoutMs: Long,
        ): TweakerV4OwnerPortOutcome {
            reconcileCalls++
            return reconcileOutcome
        }
    }

    private class FakeFps(
        private val events: MutableList<String> = mutableListOf(),
    ) : TweakerFpsLaunchPort {
        var activateCalls = 0
        var settleCalls = 0
        var releaseCalls = 0
        var lastActivatePackageName: String? = null
        var lastActivateTargetFps: Int? = null
        var lastActivateExpectedSystemProfileId: String? = null
        var lastActivateProfileOwnerActive: Boolean? = null
        var activateOutcome = TweakerFpsLaunchPortOutcome(
            TweakerFpsLaunchPortDisposition.REJECTED,
            "rejected",
        )
        var settleOutcome = TweakerFpsLaunchPortOutcome(
            TweakerFpsLaunchPortDisposition.STOCK,
            "stock",
        )
        var activateBlock: (suspend () -> TweakerFpsLaunchPortOutcome)? = null
        var settleBlock: (suspend () -> TweakerFpsLaunchPortOutcome)? = null

        override suspend fun activate(
            packageName: String,
            targetFps: Int,
            expectedSystemProfileId: String,
            profileOwnerActive: Boolean,
            bindTimeoutMs: Long,
            queryTimeoutMs: Long,
            operationTimeoutMs: Long,
        ): TweakerFpsLaunchPortOutcome {
            activateCalls++
            lastActivatePackageName = packageName
            lastActivateTargetFps = targetFps
            lastActivateExpectedSystemProfileId = expectedSystemProfileId
            lastActivateProfileOwnerActive = profileOwnerActive
            events += "fps-apply"
            return activateBlock?.invoke() ?: activateOutcome
        }

        override suspend fun settle(
            packageName: String,
            expectedOwner: TweakerFpsOwner?,
            bindTimeoutMs: Long,
            operationTimeoutMs: Long,
        ): TweakerFpsLaunchPortOutcome {
            settleCalls++
            events += "fps-restore"
            return settleBlock?.invoke() ?: settleOutcome
        }

        override suspend fun releaseTransport() {
            releaseCalls++
        }
    }

    private companion object {
        const val PackageName = "dev.legacy.eden_emulator"
        val AttemptId = LaunchAttemptId.fromTestValue("attempt-1")
        val SecondAttemptId = LaunchAttemptId.fromTestValue("attempt-2")
    }
}
