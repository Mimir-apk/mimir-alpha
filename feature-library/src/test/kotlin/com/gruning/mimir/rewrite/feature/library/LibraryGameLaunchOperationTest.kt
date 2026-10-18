package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGameLaunchOperationTest {
    @Test
    fun rapidSecondPressCannotDispatchOrOverwriteFirstLaunchReturnTarget() = runBlocking {
        val first = game("first")
        val second = game("second")
        val port = ControlledLaunchPort(first, second)
        val sessions = LibrarySessionEventLog()
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = port,
            gameSessionObserver = sessions.observer,
        )

        viewModel.launch(first)
        assertEquals(first.gameId, viewModel.launchStateOwner.value)
        assertEquals(first.gameId, awaitWithin("the first launch reaching the port") { port.started.receive() })
        assertEquals(first.gameId, viewModel.pendingLaunchReturnTarget.value?.gameId)
        // Were the second press ever dispatched, its port call would come straight back and settle, so the wait
        // below can reveal it and cannot hang on it.
        port.complete(second)
        viewModel.launch(second)
        port.complete(first)
        // Round 5e: this absence check used to run straight after the second press, before a dispatch of it could
        // have reached the port, so it could only pass. It now runs once every admitted launch has come back.
        sessions.awaitAdmittedLaunchesSettled()

        assertTrue("the second press must never reach the port", port.started.tryReceive().isFailure)
        assertEquals(1, sessions.events().count { it is LibraryGameSessionEvent.Settled })
        assertEquals(first.gameId, viewModel.pendingLaunchReturnTarget.value?.gameId)
        val preparing = sessions.events().filterIsInstance<LibraryGameSessionEvent.Preparing>()
        assertEquals(1, preparing.size)
    }

    @Test
    fun delayedLaunchCompletionKeepsRuntimeFeedbackBoundToLaunchGame() = runBlocking {
        val first = game("delayed-failure")
        val second = game("new-detail")
        val port = ControlledLaunchPort(first)
        val sessions = LibrarySessionEventLog()
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = port,
            gameSessionObserver = sessions.observer,
        )

        viewModel.launch(first)
        assertEquals(first.gameId, viewModel.launchStateOwner.value)
        assertEquals(first.gameId, awaitWithin("the first launch reaching the port") { port.started.receive() })

        viewModel.openGameDetail(second)
        port.complete(first, LaunchResult(readiness = LaunchReadiness.NeedsEmulator(first.platformId)))
        sessions.awaitEvents("the delayed launch settling") { events ->
            events.any { it is LibraryGameSessionEvent.Settled }
        }

        assertEquals(first.gameId, viewModel.launchStateOwner.value)
        assertEquals(
            null,
            resolveLibraryDetailLaunchRuntimeStatusLine(
                launchState = viewModel.launchState.value,
                launchOwnerGameId = viewModel.launchStateOwner.value,
                targetGameId = second.gameId,
                platformLabelFor = { "SNES" },
            ),
        )
        assertEquals(
            "No installed emulator for SNES. Open Settings › Emulators to install one.",
            resolveLibraryDetailLaunchRuntimeStatusLine(
                launchState = viewModel.launchState.value,
                launchOwnerGameId = viewModel.launchStateOwner.value,
                targetGameId = first.gameId,
                platformLabelFor = { "SNES" },
            ),
        )
    }

    @Test
    fun completionRebindsLaunchStateOwnerAfterInterveningRefusedLaunch() = runBlocking {
        val first = game("producing-game")
        val second = game("refused-game")
        val port = ControlledLaunchPort(first)
        val sessions = LibrarySessionEventLog()
        val observer = LibraryGameSessionObserver { event ->
            sessions.observer.onEvent(event)
            event !is LibraryGameSessionEvent.Preparing || event.game.gameId == first.gameId
        }
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = port,
            gameSessionObserver = observer,
        )

        viewModel.launch(first)
        assertEquals(first.gameId, awaitWithin("the producing launch reaching the port") { port.started.receive() })

        viewModel.openGameDetail(second)
        viewModel.launch(second, retryAfterRelease = false)
        assertEquals(GameLaunchUiState.SessionHeld(GameLaunchSessionHold.STARTING), viewModel.launchState.value)
        assertEquals(second.gameId, viewModel.launchStateOwner.value)

        port.complete(first, LaunchResult(readiness = LaunchReadiness.NeedsEmulator(first.platformId)))
        sessions.awaitEvents("the producing launch settling") { events ->
            events.any { it is LibraryGameSessionEvent.Settled && it.game.gameId == first.gameId }
        }

        assertEquals(first.gameId, viewModel.launchStateOwner.value)
        assertEquals(
            null,
            resolveLibraryDetailLaunchRuntimeStatusLine(
                launchState = viewModel.launchState.value,
                launchOwnerGameId = viewModel.launchStateOwner.value,
                targetGameId = second.gameId,
                platformLabelFor = { "SNES" },
            ),
        )
        assertEquals(
            "No installed emulator for SNES. Open Settings › Emulators to install one.",
            resolveLibraryDetailLaunchRuntimeStatusLine(
                launchState = viewModel.launchState.value,
                launchOwnerGameId = viewModel.launchStateOwner.value,
                targetGameId = first.gameId,
                platformLabelFor = { "SNES" },
            ),
        )
    }

    @Test
    fun refusedLaunchWaitsForCompletionAndUiReadsOneAtomicStateOwnerSnapshot() = runBlocking {
        val first = game("completion-game")
        val second = game("refused-game")
        val port = ControlledLaunchPort(first)
        val sessions = LibrarySessionEventLog()
        val refusedPreparing = CountDownLatch(1)
        val observer = LibraryGameSessionObserver { event ->
            sessions.observer.onEvent(event)
            if (event is LibraryGameSessionEvent.Preparing && event.game.gameId == second.gameId) {
                refusedPreparing.countDown()
            }
            event !is LibraryGameSessionEvent.Preparing || event.game.gameId == first.gameId
        }
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = port,
            gameSessionObserver = observer,
        )
        val publishOwner = CountDownLatch(1)
        val finishCompletionPublication = CountDownLatch(1)
        viewModel.launchUiPublicationHook = { state ->
            if (state == GameLaunchUiState.NeedsEmulator(first.platformId)) {
                publishOwner.countDown()
                check(finishCompletionPublication.await(LibraryTestAwaitMs, TimeUnit.MILLISECONDS)) {
                    "the test never released completion A's launch UI publication"
                }
            }
        }

        viewModel.launch(first)
        assertEquals(first.gameId, awaitWithin("completion A reaching the launch port") { port.started.receive() })
        viewModel.openGameDetail(second)
        port.complete(first, LaunchResult(readiness = LaunchReadiness.NeedsEmulator(first.platformId)))

        val refusedFinished = CountDownLatch(1)
        val refusedFailure = AtomicReference<Throwable?>(null)
        try {
            check(publishOwner.await(LibraryTestAwaitMs, TimeUnit.MILLISECONDS)) {
                "completion A did not reach the state-owner publication seam"
            }
            // The separate compatibility flows have entered their former mixed-pair window. The UI snapshot
            // remains the last complete value until both compatibility values are ready to commit together.
            assertEquals(GameLaunchUiState.NeedsEmulator(first.platformId), viewModel.launchState.value)
            assertNull(viewModel.launchStateOwner.value)
            assertEquals(
                LibraryLaunchUiSnapshot(GameLaunchUiState.Idle, ownerGameId = null),
                viewModel.launchUiSnapshot.value,
            )

            Thread {
                try {
                    viewModel.launch(second, retryAfterRelease = false)
                } catch (error: Throwable) {
                    refusedFailure.set(error)
                } finally {
                    refusedFinished.countDown()
                }
            }.apply { isDaemon = true; start() }

            check(refusedPreparing.await(LibraryTestAwaitMs, TimeUnit.MILLISECONDS)) {
                "refused launch B did not reach admission"
            }
            assertFalse(
                "refused B must not publish while completion A owns the launch-state monitor",
                refusedFinished.await(100, TimeUnit.MILLISECONDS),
            )
        } finally {
            finishCompletionPublication.countDown()
        }

        sessions.awaitEvents("completion A settling") { events ->
            events.any { it is LibraryGameSessionEvent.Settled && it.game.gameId == first.gameId }
        }
        check(refusedFinished.await(LibraryTestAwaitMs, TimeUnit.MILLISECONDS)) {
            "refused launch B did not finish after completion A released the monitor"
        }
        refusedFailure.get()?.let { throw AssertionError("refused launch B failed", it) }

        val snapshot = viewModel.launchUiSnapshot.value
        assertEquals(
            LibraryLaunchUiSnapshot(
                state = GameLaunchUiState.SessionHeld(GameLaunchSessionHold.STARTING),
                ownerGameId = second.gameId,
            ),
            snapshot,
        )
        assertEquals(snapshot.state, viewModel.launchState.value)
        assertEquals(snapshot.ownerGameId, viewModel.launchStateOwner.value)
        assertEquals(
            "Mimir is already launching something else. Wait for it to finish, then try again.",
            resolveLibraryDetailLaunchRuntimeStatusLine(
                launchState = snapshot.state,
                launchOwnerGameId = snapshot.ownerGameId,
                targetGameId = second.gameId,
                platformLabelFor = { "SNES" },
            ),
        )
        assertNull(
            resolveLibraryDetailLaunchRuntimeStatusLine(
                launchState = snapshot.state,
                launchOwnerGameId = snapshot.ownerGameId,
                targetGameId = first.gameId,
                platformLabelFor = { "SNES" },
            ),
        )
    }

    @Test
    fun refusedLaunchPublishesTheRefusedGameAsLaunchStateOwner() = runBlocking {
        val refused = game("refused")
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchController = null,
            gameSessionObserver = LibraryGameSessionObserver { false },
        )

        viewModel.launch(refused, retryAfterRelease = false)

        assertEquals(GameLaunchUiState.SessionHeld(GameLaunchSessionHold.STARTING), viewModel.launchState.value)
        assertEquals(refused.gameId, viewModel.launchStateOwner.value)
    }

    @Test
    fun recreatedViewModelUsesANewerProcessWideLaunchOperation(): Unit = runBlocking {
        val first = game("first-view-model")
        val second = game("second-view-model")
        val firstPort = ControlledLaunchPort(first)
        val secondPort = ControlledLaunchPort(second)
        val firstSessions = LibrarySessionEventLog()
        val secondSessions = LibrarySessionEventLog()
        val firstViewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = firstPort,
            gameSessionObserver = firstSessions.observer,
        )

        firstViewModel.launch(first)
        assertEquals(first.gameId, awaitWithin("the first launch reaching its port") { firstPort.started.receive() })
        val firstOperation =
            firstSessions.events().filterIsInstance<LibraryGameSessionEvent.Preparing>().single().operation

        val recreatedViewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = secondPort,
            gameSessionObserver = secondSessions.observer,
        )
        recreatedViewModel.launch(second)
        assertEquals(second.gameId, awaitWithin("the second launch reaching its port") { secondPort.started.receive() })
        val secondOperation =
            secondSessions.events().filterIsInstance<LibraryGameSessionEvent.Preparing>().single().operation

        assertTrue(secondOperation.value > firstOperation.value)

        firstPort.complete(first)
        secondPort.complete(second)
        firstSessions.awaitEvents("the first launch settling") { events ->
            events.any { it is LibraryGameSessionEvent.Settled }
        }
        secondSessions.awaitEvents("the second launch settling") { events ->
            events.any { it is LibraryGameSessionEvent.Settled }
        }
    }

    /**
     * Round 5e integration gates, 2026-09-13T00:23:11Z: [rapidSecondPressCannotDispatchOrOverwriteFirstLaunchReturnTarget]
     * threw ConcurrentModificationException from `ArrayList$Itr.checkForComodification`, then passed 10 of 10
     * single-class re-runs. Its events were a `Collections.synchronizedList` that this thread iterated without the
     * list's lock while the launch dispatcher added Settled. Rather than wait for that timing again, this test lands the
     * dispatcher's add in the middle of the read.
     */
    @Test
    fun sessionEventsCanBeReadWhileTheLaunchDispatcherRecordsOne() = runBlocking {
        val game = game("read-while-recording")
        val port = ControlledLaunchPort(game)
        val sessions = LibrarySessionEventLog()
        val settledRecorded = CompletableDeferred<Unit>()
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = port,
            gameSessionObserver = LibraryGameSessionObserver { event ->
                sessions.observer.onEvent(event).also {
                    if (event is LibraryGameSessionEvent.Settled) settledRecorded.complete(Unit)
                }
            },
        )
        viewModel.launch(game)
        assertEquals(game.gameId, awaitWithin("the launch reaching the port") { port.started.receive() })

        val settledSeen = sessions.events().count { event ->
            if (event is LibraryGameSessionEvent.Preparing) {
                // Part-way through the read: release the port, and let the dispatcher record Settled before reading on.
                port.complete(game)
                awaitWithin("the launch dispatcher recording Settled") { settledRecorded.await() }
            }
            event is LibraryGameSessionEvent.Settled
        }

        assertEquals("a read taken before the launch settled holds no Settled", 0, settledSeen)
        assertEquals(
            "and the next read holds the Settled the dispatcher recorded",
            1,
            sessions.events().count { it is LibraryGameSessionEvent.Settled },
        )
    }

    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = PlatformId.SNES,
        memberCount = 1,
        duplicateKind = null,
    )

    private class ControlledLaunchPort(vararg games: GameRow) : LibraryLaunchPort {
        val started = Channel<GameId>(Channel.UNLIMITED)
        private val completions = games.associate { it.gameId to CompletableDeferred<LaunchResult>() }

        fun complete(game: GameRow, result: LaunchResult = intentSent(game)) {
            completions.getValue(game.gameId).complete(result)
        }

        override suspend fun openWithOptions(game: GameRow): OpenWithOptions =
            OpenWithOptions(hasOverride = false, candidates = emptyList())

        override suspend fun setEmulatorOverride(game: GameRow, candidate: OpenWithCandidate) = Unit
        override suspend fun clearEmulatorOverride(game: GameRow) = Unit

        override suspend fun launch(
            game: GameRow,
            preferredDisc: Int,
            launchMemberEntryKey: EntryKey?,
        ): LaunchResult {
            started.send(game.gameId)
            return completions.getValue(game.gameId).await()
        }

        private fun intentSent(game: GameRow) = LaunchResult(
            readiness = LaunchReadiness.Ready(
                LaunchTarget(
                    gameId = game.gameId,
                    platformId = game.platformId,
                    emulator = EmulatorProfile(
                        id = EmulatorId("snes-emulator"),
                        packageName = "com.example.emulator",
                        displayName = "Emulator",
                        installed = true,
                        capability = EmulatorCapability.DIRECT_PAYLOAD,
                        locatorForm = LaunchLocatorForm.CONTENT_URI,
                        supportedPlatforms = setOf(game.platformId),
                    ),
                    locator = "content://mimir/${game.gameId.value}",
                    locatorForm = LaunchLocatorForm.CONTENT_URI,
                    title = game.title,
                ),
            ),
            outcome = LaunchOutcome.IntentSent,
        )
    }
}
