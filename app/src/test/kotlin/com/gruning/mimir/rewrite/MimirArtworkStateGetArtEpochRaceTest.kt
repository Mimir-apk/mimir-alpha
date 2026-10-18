package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Two independent epoch-race properties are pinned separately so reverting either production hunk
 * alone turns exactly one test red.
 *
 * HUNK 1 — `fetchOne`'s gate-closed early return claims `getArtEpoch`.
 *   Reverting it (delete the `++getArtEpoch` line) turns
 *   `an in-flight fetch must not paint over a later gate-closed card` red:
 *   `expected:<NeedsAccount> but was:<null>` — game A wins the epoch comparison it should have lost,
 *   paints `Found` over B's card, and its own 1,400 ms auto-dismiss timer then nulls it.
 *
 * HUNK 2 — the `invalidateCachedArt` eviction moves ABOVE the epoch guard.
 *   Reverting it (move the call back inside the `Matched` arm, below the guard) turns
 *   `a matched fetch still evicts its own cached art after losing the epoch race` red:
 *   `the enrich wrote art, so the Library's cached list for that platform must be dropped` — the fetch
 *   succeeded and wrote art to disk, but the Library kept serving the pre-fetch list from
 *   `platformListCache` with nothing on screen to say so.
 *
 * Both tests drive the real `MimirArtworkState.fetchOne`, not a helper, and both observe state AFTER the
 * timer has been advanced. Neither can pass on source text alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class MimirArtworkStateGetArtEpochRaceTest {

    // Same reasoning as MimirArtworkStateFetchOneAutoDismissTest: the gates are plain mutable singletons
    // sharing Robolectric's per-worker classloader, so one left open leaks into the next test class.
    @After
    fun resetArtGates() {
        MimirConnectedArtGate.setOpen(false)
        MimirSteamGridDbGate.setOpen(false)
    }

    private fun state(scope: kotlinx.coroutines.CoroutineScope) = MimirArtworkState(
        context = RuntimeEnvironment.getApplication(),
        viewModel = LibraryViewModel(FakeLibraryRepository()),
        scope = scope,
    )

    private val romGame = GameRow(
        gameId = GameId("ps1/test-game"),
        title = "Test Game",
        platformId = PlatformId.PS1,
        memberCount = 1,
        duplicateKind = null,
    )

    private val pcGame = GameRow(
        gameId = GameId("pc_games/other-game"),
        title = "Other Game",
        platformId = PlatformId.PC_GAMES,
        memberCount = 1,
        duplicateKind = null,
    )

    @Test
    fun `an in-flight fetch must not paint over a later gate-closed card`() = runTest {
        // A is a ROM row with ScreenScraper connected, so it starts and reaches the network call.
        // B is a pc_games row with no SteamGridDB key, so it takes the synchronous gate-closed return.
        MimirConnectedArtGate.setOpen(true)
        MimirSteamGridDbGate.setOpen(false)
        val artwork = state(this)
        val releaseA = CompletableDeferred<Unit>()

        artwork.fetchOne(romGame, emptyList()) { _, _, _ ->
            releaseA.await()
            ArtworkFetchController.OneResult.Matched
        }
        // Let A reach its suspension point INSIDE the fetch — it must be genuinely in flight, not merely
        // queued, or this test would pin nothing.
        runCurrent()
        assertEquals("A must be in flight before B is dispatched", GetArtUi.Working, artwork.getArtUi)

        // Neither provider is available to B now (a ScreenScraper account alone reaches a pc_games row since 2026-10-06).
        MimirConnectedArtGate.setOpen(false)
        artwork.fetchOne(pcGame, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Matched }
        assertEquals(GetArtUi.NeedsAccount, artwork.getArtUi)
        assertEquals(GetArtProvider.STEAMGRIDDB, artwork.getArtProvider)

        // A now completes, having lost the card to B. It must not write display state.
        releaseA.complete(Unit)
        advanceUntilIdle()

        // The visible defect: A's terminal card rendered under B's provider label, then auto-dismissed,
        // leaving the owner with no card at all where B's "add a SteamGridDB key" prompt should be.
        assertEquals(
            "B's card owns the display; A lost the epoch and must not paint over it",
            GetArtUi.NeedsAccount,
            artwork.getArtUi,
        )
        assertEquals(GetArtProvider.STEAMGRIDDB, artwork.getArtProvider)
    }

    @Test
    fun `a matched fetch still evicts its own cached art after losing the epoch race`() = runTest {
        MimirConnectedArtGate.setOpen(true)
        MimirSteamGridDbGate.setOpen(false)
        val artwork = state(this)
        val releaseA = CompletableDeferred<Unit>()
        val invalidated = mutableListOf<GameRow>()

        artwork.fetchOne(
            row = romGame,
            candidateNames = emptyList(),
            onArtInvalidated = { invalidated += it },
        ) { _, _, _ ->
            releaseA.await()
            ArtworkFetchController.OneResult.Matched
        }
        runCurrent()

        // B takes the card away from A while A's fetch is still running.
        // Neither provider is available to B now (a ScreenScraper account alone reaches a pc_games row since 2026-10-06).
        MimirConnectedArtGate.setOpen(false)
        artwork.fetchOne(pcGame, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Matched }
        assertEquals(GetArtUi.NeedsAccount, artwork.getArtUi)

        releaseA.complete(Unit)
        advanceUntilIdle()

        // A's art landed on disk regardless of who owns the card. Skipping the eviction here is what left
        // the Library painting the pre-fetch tile for a game whose enrich had actually succeeded.
        assertTrue(
            "the enrich wrote art, so the Library's cached list for that platform must be dropped",
            invalidated.any { it.gameId == romGame.gameId },
        )
    }

    @Test
    fun `a no-match fetch evicts nothing`() = runTest {
        // Differential guard the other way: hoisting the eviction must not make it unconditional. Only a
        // Matched result wrote bytes; the other three outcomes have nothing to invalidate.
        MimirConnectedArtGate.setOpen(true)
        val artwork = state(this)
        val invalidated = mutableListOf<GameRow>()

        artwork.fetchOne(
            row = romGame,
            candidateNames = emptyList(),
            onArtInvalidated = { invalidated += it },
        ) { _, _, _ -> ArtworkFetchController.OneResult.NoMatch }
        advanceUntilIdle()

        assertEquals(emptyList<GameRow>(), invalidated)
    }

    @Test
    fun `an uncontested matched fetch still shows its own card and evicts`() = runTest {
        // Differential guard: the epoch claim added to the early return must not disturb the ordinary
        // single-fetch path, which is every real Enrich the owner performs.
        MimirConnectedArtGate.setOpen(true)
        val artwork = state(this)
        val invalidated = mutableListOf<GameRow>()

        artwork.fetchOne(
            row = romGame,
            candidateNames = emptyList(),
            onArtInvalidated = { invalidated += it },
        ) { _, _, _ -> ArtworkFetchController.OneResult.Matched }
        advanceUntilIdle()

        assertTrue(invalidated.any { it.gameId == romGame.gameId })
        // Matched auto-dismisses after its 1,400 ms timer, exactly as before this change.
        assertEquals(null, artwork.getArtUi)
        assertEquals(GetArtProvider.SCREENSCRAPER, artwork.getArtProvider)
    }
}
