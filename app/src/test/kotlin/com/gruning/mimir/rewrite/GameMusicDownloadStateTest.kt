package com.gruning.mimir.rewrite

import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import com.gruning.mimir.rewrite.core.metadata.online.runAbortingOnCancel
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Music card's download as the state machine the UI reads: idle, running,
 * finished, stopped, resumed, blocked, and the process gate held for exactly as long as it runs. The batch behind it is
 * injected, so this drives the real [MimirArtworkState] with no network, library or sign-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class GameMusicDownloadStateTest {
    private var connected = true

    @After
    fun processGateIsFree() {
        assertEquals(ArtworkOperation.NONE, ArtworkOperationGate.process.active.value)
    }

    /** [TestScope.backgroundScope]: the block observer lives as long as the composition does, so it never ends by itself. */
    private fun TestScope.state() = MimirArtworkState(
        context = RuntimeEnvironment.getApplication(),
        viewModel = LibraryViewModel(FakeLibraryRepository()),
        scope = backgroundScope,
        gameMusicConnected = { connected },
    )

    private val unreachable: suspend (android.content.Context, (GameMusicTally) -> Unit) -> GameMusicTally =
        { _, _ -> error("the batch must not start") }

    @Test
    fun `before anything is counted the card is idle with nothing known`() = runTest {
        val artwork = state()
        assertEquals(GameMusicDownloadState.Idle(have = 0, total = 0), artwork.gameMusicState.value)
    }

    @Test
    fun `a download goes from running to finished, reports each game, and gives the gate back`() = runTest {
        val artwork = state()
        val hold = CompletableDeferred<Unit>()
        artwork.startGameMusicDownload { _, report ->
            report(GameMusicTally(done = 0, total = 3))
            report(GameMusicTally(done = 1, total = 3, written = 1))
            hold.await()
            report(GameMusicTally(done = 3, total = 3, written = 2, skipped = 1))
            GameMusicTally(done = 3, total = 3, written = 2, skipped = 1)
        }
        runCurrent()

        assertEquals(GameMusicDownloadState.Running(done = 1, total = 3, written = 1), artwork.gameMusicState.value)
        assertEquals("the gate is held for as long as it runs", ArtworkOperation.GAME_MUSIC, ArtworkOperationGate.process.active.value)

        hold.complete(Unit)
        runCurrent()

        assertEquals(GameMusicDownloadState.Finished(written = 2, skipped = 1, failed = 0), artwork.gameMusicState.value)
        assertEquals(ArtworkOperation.NONE, ArtworkOperationGate.process.active.value)
    }

    @Test
    fun `an Enrich cannot start while it runs, and a second press is ignored`() = runTest {
        val artwork = state()
        val hold = CompletableDeferred<Unit>()
        var starts = 0
        artwork.startGameMusicDownload { _, _ ->
            starts++
            hold.await()
            GameMusicTally()
        }
        runCurrent()

        assertNull("every enrich entry claims the same gate", ArtworkOperationGate.process.tryClaimEnrich())
        artwork.startGameMusicDownload { _, _ ->
            starts++
            GameMusicTally()
        }
        artwork.downloadMusicForGame("ps1/game") { _, _, _ ->
            starts++
            GameMusicTally()
        }
        runCurrent()
        assertEquals("one download at a time", 1, starts)

        hold.complete(Unit)
        runCurrent()
    }

    @Test
    fun `stopping ends as finished with what was done, frees the gate, and the next press starts again`() = runTest {
        val artwork = state()
        val hold = CompletableDeferred<Unit>()
        artwork.startGameMusicDownload { _, report ->
            report(GameMusicTally(done = 2, total = 5, written = 1, skipped = 1))
            hold.await()
            GameMusicTally()
        }
        runCurrent()

        artwork.cancelGameMusicDownload()
        runCurrent()

        assertEquals(
            "a stop is not a failure: the games it did not reach are simply still missing, and the card says it was stopped",
            GameMusicDownloadState.Finished(written = 1, skipped = 1, failed = 0, stopped = true),
            artwork.gameMusicState.value,
        )
        assertEquals(ArtworkOperation.NONE, ArtworkOperationGate.process.active.value)

        var resumedWith: GameMusicTally? = null
        artwork.startGameMusicDownload { _, report ->
            report(GameMusicTally(done = 3, total = 3, written = 3))
            GameMusicTally(done = 3, total = 3, written = 3).also { resumedWith = it }
        }
        runCurrent()
        assertNotNull(resumedWith)
        assertEquals(GameMusicDownloadState.Finished(written = 3, skipped = 0, failed = 0), artwork.gameMusicState.value)
    }

    @Test
    fun `something unexpected ends the card honestly, with the games it never reached counted as failed`() = runTest {
        val artwork = state()
        artwork.startGameMusicDownload { _, report ->
            report(GameMusicTally(done = 1, total = 4, written = 1))
            error("storage vanished")
        }
        runCurrent()

        assertEquals(GameMusicDownloadState.Finished(written = 1, skipped = 0, failed = 3), artwork.gameMusicState.value)
        assertEquals(ArtworkOperation.NONE, ArtworkOperationGate.process.active.value)
    }

    @Test
    fun `not connected blocks the row, starts nothing and claims nothing`() = runTest {
        connected = false
        val artwork = state()

        artwork.startGameMusicDownload(unreachable)
        artwork.downloadMusicForGame("ps1/game") { _, _, _ -> error("the batch must not start") }
        runCurrent()

        assertEquals(GameMusicDownloadState.Idle(have = 0, total = 0, block = GameMusicBlock.NotConnected), artwork.gameMusicState.value)
        assertEquals(ArtworkOperation.NONE, ArtworkOperationGate.process.active.value)
    }

    @Test
    fun `an Enrich in progress blocks the row, and the row frees when it ends`() = runTest {
        val artwork = state()
        artwork.refreshGameMusicCounts { GameMusicPlan(emptyList<Unit>(), have = 2, total = 5) }
        runCurrent()
        assertNull(artwork.gameMusicState.value.block)

        val enrich = ArtworkOperationGate.process.tryClaimEnrich()!!
        runCurrent()
        assertEquals(GameMusicBlock.EnrichRunning, artwork.gameMusicState.value.block)
        assertEquals("the counts stay while it is blocked", GameMusicDownloadState.Idle(2, 5, GameMusicBlock.EnrichRunning), artwork.gameMusicState.value)

        artwork.startGameMusicDownload(unreachable)
        runCurrent()
        assertEquals("a blocked press starts nothing", ArtworkOperation.ENRICH, ArtworkOperationGate.process.active.value)

        enrich.release()
        runCurrent()
        assertNull(artwork.gameMusicState.value.block)
    }

    @Test
    fun `the counts replace a finished card but never a running one`() = runTest {
        val artwork = state()
        val hold = CompletableDeferred<Unit>()
        artwork.startGameMusicDownload { _, report ->
            report(GameMusicTally(done = 1, total = 2, written = 1))
            hold.await()
            GameMusicTally(done = 2, total = 2, written = 2)
        }
        runCurrent()

        artwork.refreshGameMusicCounts { GameMusicPlan(emptyList<Unit>(), have = 9, total = 9) }
        runCurrent()
        assertEquals("a running card is its own truth", GameMusicDownloadState.Running(done = 1, total = 2, written = 1), artwork.gameMusicState.value)

        hold.complete(Unit)
        runCurrent()
        assertTrue(artwork.gameMusicState.value is GameMusicDownloadState.Finished)

        artwork.refreshGameMusicCounts { GameMusicPlan(emptyList<Unit>(), have = 9, total = 9) }
        runCurrent()
        assertEquals(GameMusicDownloadState.Idle(have = 9, total = 9), artwork.gameMusicState.value)
    }

    @Test
    fun `the per-game press is a batch of one in the same flow`() = runTest {
        val artwork = state()
        var asked: String? = null
        artwork.downloadMusicForGame("ps1/game-7") { _, gameId, report ->
            asked = gameId
            report(GameMusicTally(done = 0, total = 1))
            GameMusicTally(done = 1, total = 1, written = 1).also(report)
        }
        runCurrent()

        assertEquals("ps1/game-7", asked)
        assertEquals(GameMusicDownloadState.Finished(written = 1, skipped = 0, failed = 0), artwork.gameMusicState.value)
        assertEquals(ArtworkOperation.NONE, ArtworkOperationGate.process.active.value)
    }

    // ── combined49: Cancel did nothing for seconds, then the card said "Done · 0 written" ───────────────────────────────

    /** A run whose work cannot be interrupted: cancelled, it stays parked until [release] (a blocking network call). */
    private fun TestScope.parkedRun(
        artwork: MimirArtworkState,
        release: CompletableDeferred<Unit>,
        opening: GameMusicTally,
        afterRelease: suspend ((GameMusicTally) -> Unit) -> Unit = {},
    ) {
        artwork.startGameMusicDownload { _, report ->
            report(opening)
            withContext(NonCancellable) { release.await() }
            afterRelease(report)
            opening.copy(currentIndex = 0, current = null)
        }
        runCurrent()
    }

    private val opening = GameMusicTally(done = 2, total = 5, written = 1, skipped = 1, currentIndex = 3, current = "Chrono Trigger")

    @Test
    fun `while a game is looked up the card says which, so it never sits unchanged`() = runTest {
        val artwork = state()
        val release = CompletableDeferred<Unit>()
        parkedRun(artwork, release, opening)

        assertEquals(
            GameMusicDownloadState.Running(done = 2, total = 5, written = 1, currentIndex = 3, current = "Chrono Trigger"),
            artwork.gameMusicState.value,
        )
        release.complete(Unit)
        runCurrent()
    }

    @Test
    fun `Cancel answers at once with Stopping, while the work has not stopped and the gate is still held`() = runTest {
        val artwork = state()
        val release = CompletableDeferred<Unit>()
        parkedRun(artwork, release, opening)

        artwork.cancelGameMusicDownload()
        runCurrent()

        assertEquals(
            "the press is answered before the work has stopped",
            GameMusicDownloadState.Running(done = 2, total = 5, written = 1, currentIndex = 3, current = "Chrono Trigger", stopping = true),
            artwork.gameMusicState.value,
        )
        assertEquals("it has not stopped, so it still holds the gate", ArtworkOperation.GAME_MUSIC, ArtworkOperationGate.process.active.value)

        release.complete(Unit)
        runCurrent()
        assertEquals(
            "work that comes back whole after a stop was asked for is still a stop, never a clean run",
            GameMusicDownloadState.Finished(written = 1, skipped = 1, failed = 0, stopped = true),
            artwork.gameMusicState.value,
        )
    }

    @Test
    fun `a progress report that lands after the press keeps the card on Stopping, and a second press changes nothing`() = runTest {
        val artwork = state()
        val release = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        parkedRun(artwork, release, opening) { report ->
            report(GameMusicTally(done = 3, total = 5, written = 1, skipped = 2))
            withContext(NonCancellable) { hold.await() }
        }
        artwork.cancelGameMusicDownload()
        runCurrent()
        val stoppingNow = artwork.gameMusicState.value
        artwork.cancelGameMusicDownload()
        runCurrent()
        assertEquals("a second press is absorbed", stoppingNow, artwork.gameMusicState.value)

        release.complete(Unit)
        runCurrent()
        assertEquals(
            "a step that completed after the press does not take Stopping off the card",
            GameMusicDownloadState.Running(done = 3, total = 5, written = 1, stopping = true),
            artwork.gameMusicState.value,
        )
        hold.complete(Unit)
        runCurrent()
        assertTrue((artwork.gameMusicState.value as GameMusicDownloadState.Finished).stopped)
    }

    @Test
    fun `Cancel with nothing running does nothing`() = runTest {
        val artwork = state()
        artwork.cancelGameMusicDownload()
        runCurrent()
        assertEquals(GameMusicDownloadState.Idle(have = 0, total = 0), artwork.gameMusicState.value)
    }

    @Test
    fun `a whole run is not a stop, and carries the reason its failed games share`() = runTest {
        val artwork = state()
        artwork.startGameMusicDownload { _, report ->
            report(GameMusicTally(done = 4, total = 4, failed = 4, failure = GameMusicFailure.DAILY_LIMIT))
            GameMusicTally(done = 4, total = 4, failed = 4, failure = GameMusicFailure.DAILY_LIMIT)
        }
        runCurrent()
        assertEquals(
            GameMusicDownloadState.Finished(written = 0, skipped = 0, failed = 4, stopped = false, failure = GameMusicFailure.DAILY_LIMIT),
            artwork.gameMusicState.value,
        )
    }

    /**
     * The second reported symptom end to end: the work is parked in a blocking call that, like a socket read, only ends when
     * something closes it. Cancel must close it (the watcher in [runAbortingOnCancel]), and the card must read Stopped within
     * moments instead of when the network gives up.
     */
    @Test
    fun `a lookup parked on a dead network is aborted by Cancel, and the card reads Stopped in moments`() = runTest {
        val artwork = state()
        val parked = CountDownLatch(1)
        val inside = CountDownLatch(2)
        artwork.startGameMusicDownload { _, report ->
            runGameMusicBatch(
                items = listOf(1, 2, 3, 4),
                concurrency = 2,
                step = {
                    withContext(Dispatchers.IO) {
                        runAbortingOnCancel(abort = parked::countDown) {
                            inside.countDown()
                            parked.await()
                            GameMusicStepResult(GameMusicStepOutcome.FAILED, lookupFailed = true)
                        }
                    }
                },
                onProgress = report,
                publish = {},
                titleOf = { n -> "Game $n" },
            )
        }
        runCurrent()
        assertTrue("two lookups are parked in the blocking call", inside.await(10, TimeUnit.SECONDS))
        assertEquals("and the card names one of them", 2, (artwork.gameMusicState.value as GameMusicDownloadState.Running).currentIndex)

        artwork.cancelGameMusicDownload()
        runCurrent()
        assertTrue(
            when (val state = artwork.gameMusicState.value) {
                is GameMusicDownloadState.Running -> state.stopping
                is GameMusicDownloadState.Finished -> state.stopped
                else -> false
            },
        )

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (artwork.gameMusicState.value !is GameMusicDownloadState.Finished && System.nanoTime() < deadline) {
            runCurrent()
            Thread.sleep(10)
        }
        assertEquals(
            "the parked lookups were counted as neither written nor failed: they were stopped",
            GameMusicDownloadState.Finished(written = 0, skipped = 0, failed = 0, stopped = true),
            artwork.gameMusicState.value,
        )
        assertEquals(ArtworkOperation.NONE, ArtworkOperationGate.process.active.value)
    }
}
