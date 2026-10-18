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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ViewModel side: the intro holds after admission and
 * the return-target publish and before dispatch, its finish carries the verdict, and the Library return target
 * (the anchor armLibraryLaunchReturn reads) survives the hold.
 */
class LibraryLaunchOpeningTest {
    @Test
    fun `the intro holds before dispatch and the Library return target survives it`() = runBlocking<Unit> {
        val game = game("held-game")
        val port = GatedPort()
        val opening = RecordingOpening(holdUntilReleased = true)
        val sessions = LibrarySessionEventLog()
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = port,
            gameSessionObserver = sessions.observer,
            launchOpening = opening,
        )

        viewModel.launch(game)
        assertEquals(game.gameId, awaitWithin("the intro starting") { opening.started.await() })
        assertTrue("the port must wait for the intro", port.started.tryReceive().isFailure)
        assertEquals(GameLaunchUiState.Launching, viewModel.launchState.value)
        assertEquals(game.gameId, viewModel.pendingLaunchReturnTarget.value?.gameId)

        opening.release.complete(Unit)
        assertEquals(game.gameId, awaitWithin("the dispatch after the intro") { port.started.receive() })
        port.result.complete(intentSent(game))
        assertEquals(true, awaitWithin("the intro finishing") { opening.finished.await() })

        assertEquals(game.gameId, viewModel.pendingLaunchReturnTarget.value?.gameId)
        assertTrue(sessions.events().any { it is LibraryGameSessionEvent.Settled })
    }

    @Test
    fun `a Library launch that is not ready finishes the intro as not launched`() = runBlocking<Unit> {
        val game = game("not-ready-game")
        val port = GatedPort()
        val opening = RecordingOpening(holdUntilReleased = false)
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = port,
            launchOpening = opening,
        )

        viewModel.launch(game)
        awaitWithin("the dispatch") { port.started.receive() }
        port.result.complete(LaunchResult(readiness = LaunchReadiness.NeedsEmulator(game.platformId)))

        assertEquals(false, awaitWithin("the intro finishing") { opening.finished.await() })
        assertNull(viewModel.pendingLaunchReturnTarget.value)
    }

    @Test
    fun `a Library launch that throws finishes the intro as not launched`() = runBlocking<Unit> {
        val game = game("thrown-game")
        val port = GatedPort()
        val opening = RecordingOpening(holdUntilReleased = false)
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = port,
            launchOpening = opening,
        )

        viewModel.launch(game)
        awaitWithin("the dispatch") { port.started.receive() }
        port.result.completeExceptionally(IllegalStateException("dispatch blew up"))

        assertEquals(false, awaitWithin("the intro finishing") { opening.finished.await() })
        assertNull(viewModel.pendingLaunchReturnTarget.value)
    }

    @Test
    fun `a failing intro never blocks the Library launch`() = runBlocking<Unit> {
        val game = game("broken-intro-game")
        val port = GatedPort()
        val opening = object : LibraryLaunchOpening {
            override suspend fun beforeDispatch(game: GameRow) {
                throw IllegalStateException("intro blew up")
            }
            override fun afterDispatch(scope: CoroutineScope, game: GameRow, launched: Boolean) = Unit
        }
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = port,
            launchOpening = opening,
        )

        viewModel.launch(game)
        assertEquals(game.gameId, awaitWithin("the dispatch") { port.started.receive() })
        port.result.complete(intentSent(game))
    }

    private class RecordingOpening(private val holdUntilReleased: Boolean) : LibraryLaunchOpening {
        val started = CompletableDeferred<GameId>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Boolean>()

        override suspend fun beforeDispatch(game: GameRow) {
            started.complete(game.gameId)
            if (holdUntilReleased) release.await()
        }

        override fun afterDispatch(scope: CoroutineScope, game: GameRow, launched: Boolean) {
            finished.complete(launched)
        }
    }

    private class GatedPort : LibraryLaunchPort {
        val started = Channel<GameId>(Channel.UNLIMITED)
        val result = CompletableDeferred<LaunchResult>()

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
            return result.await()
        }
    }

    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = PlatformId.SNES,
        memberCount = 1,
        duplicateKind = null,
    )

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
