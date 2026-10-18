package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class MimirArtworkCoordinatorTest {

    @Test
    fun `a closed ScreenScraper gate with a saved account reports provider unavailable`() {
        assertEquals(
            GetArtUi.ProviderUnavailable,
            getArtUiForGateClosed(GetArtProvider.SCREENSCRAPER, connectedAccount = "member"),
        )
    }

    @Test
    fun `a closed gate without an account keeps the needs-account prompt`() {
        assertEquals(
            GetArtUi.NeedsAccount,
            getArtUiForGateClosed(GetArtProvider.SCREENSCRAPER, connectedAccount = null),
        )
    }

    @Test
    fun `a closed SteamGridDB gate remains a needs-account prompt when ScreenScraper is connected`() {
        assertEquals(
            GetArtUi.NeedsAccount,
            getArtUiForGateClosed(GetArtProvider.STEAMGRIDDB, connectedAccount = "member"),
        )
    }

    @Test
    fun `a failed single-game enrich does not auto-dismiss`() {
        assertFalse(getArtResultAutoDismisses(ArtworkFetchController.OneResult.Failed))
    }

    @Test
    fun `a matched or no-match single-game enrich still auto-dismisses`() {
        assertTrue(getArtResultAutoDismisses(ArtworkFetchController.OneResult.Matched))
        assertTrue(getArtResultAutoDismisses(ArtworkFetchController.OneResult.NoMatch))
    }

    @Test
    fun `a gate-closed result does not auto-dismiss`() {
        assertFalse(getArtResultAutoDismisses(ArtworkFetchController.OneResult.GateClosed))
    }

    @Test
    fun `a nothing-to-look-up result auto-dismisses like a no-match`() {
        // NoTitle is a "nothing to see" outcome (no provider was asked), so it
        // takes the same brief-note-then-vanish path NotFound takes, never Failed's stay-until-dismissed.
        assertTrue(getArtResultAutoDismisses(ArtworkFetchController.OneResult.NoTitle))
    }
}

/**
 * the three tests
 * above pin `getArtResultAutoDismisses`'s own truth table only — they never call [MimirArtworkState] at
 * all, so they cannot tell whether `fetchOne` actually consults the predicate. A regression that reverts
 * ONLY the call site (`MimirArtworkCoordinator.kt`'s `if (getArtResultAutoDismisses(result))` back to the
 * old inline `if (result != ArtworkFetchController.OneResult.GateClosed)`), leaving the now-orphaned
 * helper in place, compiles clean and leaves every test above green with the shipped defect restored.
 *
 * These tests instead call `MimirArtworkState.fetchOne` itself, with the network call injected (the
 * `fetchOneArtwork` parameter added for exactly this purpose — every real caller still uses the
 * `ArtworkFetchController::fetchOneArtwork` default), and observe `getArtUi` after the real 1,400ms timer
 * has been advanced. This is genuinely sensitive to the call site's wiring, not just the predicate: with
 * only the call site reverted to the old inline condition, `a failed single-game enrich survives its own
 * timer` turns red —
 * `java.lang.AssertionError: expected:<Failed> but was:<null>` — captured by reverting the call site
 * alone and re-running this exact test, then restoring the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class MimirArtworkStateFetchOneAutoDismissTest {

    // Robolectric shares its sandbox classloader across same-config test classes in one worker, and
    // MimirSteamGridDbGate/MimirConnectedArtGate are plain mutable singletons -- a gate left OPEN here
    // would leak into whatever test runs next in the same worker. Reset both after every test regardless
    // of which one a given test opened.
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

    private val game = GameRow(
        gameId = GameId("ps1/test-game"),
        title = "Test Game",
        platformId = PlatformId.PS1,
        memberCount = 1,
        duplicateKind = null,
    )

    @Test
    fun `a blank account id closes the connect flow without starting a request`() = runTest {
        val artwork = state(this)
        artwork.connectUi = ArtworkConnectUi.EnterId

        artwork.submitAccountId("")

        assertEquals(null, artwork.connectUi)
    }

    @Test
    fun `a failed single-game enrich survives its own timer`() = runTest {
        MimirConnectedArtGate.setOpen(true)
        val artwork = state(this)

        artwork.fetchOne(game, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Failed }
        advanceUntilIdle()

        assertEquals(GetArtUi.Failed, artwork.getArtUi)
    }

    @Test
    fun `a matched single-game enrich still auto-dismisses after its timer`() = runTest {
        MimirConnectedArtGate.setOpen(true)
        val artwork = state(this)

        artwork.fetchOne(game, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Matched }
        advanceUntilIdle()

        assertEquals(null, artwork.getArtUi)
    }

    // ── Provider threading (policy 2026-08-19): the overlay must name whichever provider ENRICH actually ran, decided from the
    // row's platform BEFORE the fetch starts -- never by the overlay querying the database. These two
    // tests are genuinely runtime-observable (unlike `ArtworkFetchController.fetchOneArtwork`'s own DB/
    // Keystore-bound SteamGridDB routing, which is characterized as source text in
    // `PcEnrichSteamGridDbRoutingWiringTest` instead): [MimirArtworkState.fetchOne] already exposes an
    // injectable `fetchOneArtwork` seam for exactly this reason, so the provider selection can be observed
    // without a real network/DB round trip.

    @Test
    fun `fetchOne selects the SteamGridDB provider for a pc_games row`() = runTest {
        // a pc_games row needs ONLY a
        // SteamGridDB key. ScreenScraper is deliberately left CLOSED here -- before this ruling the test
        // opened ScreenScraper instead, which only proved the provider LABEL was right once ScreenScraper
        // had already let the call through, never that SteamGridDB alone was enough to reach the fetch at
        // all (the exact device defect: a keyed pc_games row with no ScreenScraper account got
        // NeedsAccount, not visible when ScreenScraper is connected).
        MimirConnectedArtGate.setOpen(false)
        MimirSteamGridDbGate.setOpen(true)
        val artwork = state(this)
        val pcGame = game.copy(platformId = PlatformId.PC_GAMES)

        artwork.fetchOne(pcGame, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Matched }
        advanceUntilIdle()

        assertEquals(GetArtProvider.STEAMGRIDDB, artwork.getArtProvider)
        // Matched auto-dismisses to null; a stuck NeedsAccount would mean the gate never actually opened.
        assertEquals(null, artwork.getArtUi)
    }

    @Test
    fun `fetchOne keeps the ScreenScraper provider for every other platform`() = runTest {
        // Differential guard: platforms other than pc_games (here, the fixture's own PS1 row) must be
        // completely unaffected by the pc_games routing.
        MimirConnectedArtGate.setOpen(true)
        val artwork = state(this)

        artwork.fetchOne(game, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Matched }
        advanceUntilIdle()

        assertEquals(GetArtProvider.SCREENSCRAPER, artwork.getArtProvider)
    }

    @Test
    fun `a non-pc row still needs a ScreenScraper account even with a SteamGridDB key present`() = runTest {
        // Differential guard, the other direction: an open SteamGridDB gate must never leak into a
        // platform SteamGridDB does not serve.
        MimirConnectedArtGate.setOpen(false)
        MimirSteamGridDbGate.setOpen(true)
        val artwork = state(this)
        var fetchCalled = false

        artwork.fetchOne(game, emptyList()) { _, _, _ ->
            fetchCalled = true
            ArtworkFetchController.OneResult.Matched
        }
        advanceUntilIdle()

        assertEquals(GetArtUi.NeedsAccount, artwork.getArtUi)
        assertFalse("a non-pc row must never reach the fetch off SteamGridDB's gate alone", fetchCalled)
        // GetArtOverlay.kt's NeedsAccount copy is provider-specific : a non-pc
        // row's blocked gate must still read as ScreenScraper, never leak SteamGridDB's sentence.
        assertEquals(GetArtProvider.SCREENSCRAPER, artwork.getArtProvider)
    }

    @Test
    fun `a pc_games row with only ScreenScraper connected reaches the fetch, which asks ScreenScraper for its logo`() = runTest {
        // ScreenScraper cannot identify a GameNative shortcut, but an explicit press may fetch its pc_games logo by name.
        MimirConnectedArtGate.setOpen(true)
        MimirSteamGridDbGate.setOpen(false)
        val artwork = state(this)
        val pcGame = game.copy(platformId = PlatformId.PC_GAMES)
        var fetchCalled = false

        artwork.fetchOne(pcGame, emptyList()) { _, _, _ ->
            fetchCalled = true
            ArtworkFetchController.OneResult.Matched
        }
        advanceUntilIdle()

        assertTrue("ScreenScraper alone now reaches the fetch for a pc_games row", fetchCalled)
        // with no SteamGridDB key the result is the key note (it stays), not the brief Found card.
        assertEquals(GetArtUi.PcKeyNote(logoSaved = true), artwork.getArtUi)
        // ScreenScraper is the only provider this press could ask, so the card must not name SteamGridDB.
        assertEquals(GetArtProvider.SCREENSCRAPER, artwork.getArtProvider)
    }

    @Test
    fun `a failed ScreenScraper-only PC press keeps its failure card on screen, labelled ScreenScraper`() = runTest {
        MimirConnectedArtGate.setOpen(true)
        MimirSteamGridDbGate.setOpen(false)
        val artwork = state(this)
        val pcGame = game.copy(platformId = PlatformId.PC_GAMES)

        artwork.fetchOne(pcGame, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Failed }
        advanceUntilIdle()

        assertEquals("a transport or auth failure stays on screen with its failure wording", GetArtUi.Failed, artwork.getArtUi)
        assertEquals(GetArtProvider.SCREENSCRAPER, artwork.getArtProvider)
    }

    @Test
    fun `a real ScreenScraper-only PC miss keeps its key note on screen, labelled ScreenScraper`() = runTest {
        MimirConnectedArtGate.setOpen(true)
        MimirSteamGridDbGate.setOpen(false)
        val artwork = state(this)
        val pcGame = game.copy(platformId = PlatformId.PC_GAMES)

        artwork.fetchOne(pcGame, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.NoMatch }
        advanceUntilIdle()

        // the miss says PC art needs a SteamGridDB key, so it stays until dismissed.
        assertEquals(GetArtUi.PcKeyNote(logoSaved = false), artwork.getArtUi)
        assertEquals(GetArtProvider.SCREENSCRAPER, artwork.getArtProvider)
    }


    @Test
    fun `a pc_games press WITH a SteamGridDB key never gets the key note, and its result dismisses as before`() = runTest {
        MimirConnectedArtGate.setOpen(true)
        MimirSteamGridDbGate.setOpen(true)
        val artwork = state(this)
        val pcGame = game.copy(platformId = PlatformId.PC_GAMES)

        artwork.fetchOne(pcGame, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Matched }
        advanceUntilIdle()
        assertEquals("a keyed PC press is the ordinary brief Found card", null, artwork.getArtUi)

        artwork.fetchOne(pcGame, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.NoMatch }
        advanceUntilIdle()
        assertEquals("a keyed PC miss is the ordinary brief NotFound card", null, artwork.getArtUi)
    }

    @Test
    fun `a pc_games press keyed by SteamGridDB alone never gets the key note either`() = runTest {
        MimirConnectedArtGate.setOpen(false)
        MimirSteamGridDbGate.setOpen(true)
        val artwork = state(this)

        artwork.fetchOne(game.copy(platformId = PlatformId.PC_GAMES), emptyList()) { _, _, _ ->
            ArtworkFetchController.OneResult.NoMatch
        }
        advanceUntilIdle()

        assertEquals(null, artwork.getArtUi)
    }

    @Test
    fun `a keyless PC press that failed keeps its failure card, not the key note`() = runTest {
        MimirConnectedArtGate.setOpen(true)
        MimirSteamGridDbGate.setOpen(false)
        val artwork = state(this)

        artwork.fetchOne(game.copy(platformId = PlatformId.PC_GAMES), emptyList()) { _, _, _ ->
            ArtworkFetchController.OneResult.Failed
        }
        advanceUntilIdle()

        assertEquals(GetArtUi.Failed, artwork.getArtUi)
    }

    @Test
    fun `a non-PC press with only ScreenScraper never gets the key note`() = runTest {
        MimirConnectedArtGate.setOpen(true)
        MimirSteamGridDbGate.setOpen(false)
        val artwork = state(this)

        artwork.fetchOne(game, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.NoMatch }
        advanceUntilIdle()

        assertEquals(null, artwork.getArtUi)
    }

    @Test
    fun `a pc_games row with neither provider still needs an account, and names SteamGridDB`() = runTest {
        MimirConnectedArtGate.setOpen(false)
        MimirSteamGridDbGate.setOpen(false)
        val artwork = state(this)
        val pcGame = game.copy(platformId = PlatformId.PC_GAMES)
        var fetchCalled = false

        artwork.fetchOne(pcGame, emptyList()) { _, _, _ ->
            fetchCalled = true
            ArtworkFetchController.OneResult.Matched
        }
        advanceUntilIdle()

        assertEquals(GetArtUi.NeedsAccount, artwork.getArtUi)
        assertFalse("with neither provider the fetch is never reached", fetchCalled)
        // The label on the blocked card is SteamGridDB's, never the default ScreenScraper one, because the provider that
        // identifies a PC game is the one a closed gate asks the player for.
        assertEquals(GetArtProvider.STEAMGRIDDB, artwork.getArtProvider)
    }

    @Test
    fun `a pc_games row's NeedsAccount provider is not left over from an earlier ScreenScraper-labelled fetch`() = runTest {
        // Differential guard for the actual mechanism: getArtProvider is a single shared mutable field, so
        // a prior successful non-pc fetch (which legitimately sets it to SCREENSCRAPER) must not survive
        // into a later pc_games row's blocked-gate NeedsAccount card.
        MimirConnectedArtGate.setOpen(true)
        MimirSteamGridDbGate.setOpen(false)
        val artwork = state(this)

        artwork.fetchOne(game, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Matched }
        advanceUntilIdle()
        assertEquals(GetArtProvider.SCREENSCRAPER, artwork.getArtProvider)

        // Both gates closed now: a ScreenScraper account alone reaches a pc_games row since 2026-10-06.
        MimirConnectedArtGate.setOpen(false)
        val pcGame = game.copy(platformId = PlatformId.PC_GAMES)
        artwork.fetchOne(pcGame, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Matched }
        advanceUntilIdle()

        assertEquals(GetArtUi.NeedsAccount, artwork.getArtUi)
        assertEquals(GetArtProvider.STEAMGRIDDB, artwork.getArtProvider)
    }

    @Test
    fun `a NoTitle result paints the nothing-to-look-up card, never NotFound, then auto-dismisses`() = runTest {
        // a pc_games row with no `.steam` shortcut used to reach NotFound without
        // SteamGridDB ever being asked, and the card said "SteamGridDB had no art for this game". The
        // never-asked verdict now has its own OneResult and its own card; this pins the mapping through the
        // real fetchOne, with the network call injected.
        MimirConnectedArtGate.setOpen(false)
        MimirSteamGridDbGate.setOpen(true)
        val artwork = state(this)
        val pcGame = game.copy(platformId = PlatformId.PC_GAMES)

        artwork.fetchOne(pcGame, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.NoTitle }
        runCurrent()

        assertEquals(GetArtUi.NotSearchable, artwork.getArtUi)
        assertEquals(GetArtProvider.STEAMGRIDDB, artwork.getArtProvider)

        advanceUntilIdle()
        assertEquals("a nothing-to-look-up card is a brief note, like NotFound", null, artwork.getArtUi)
    }

    @Test
    fun `a genuine NoMatch still paints NotFound -- the provider-miss card is reserved for a real miss`() = runTest {
        // Differential guard: the new verdict must not have widened NotFound's meaning. A NoMatch from the
        // route now means SteamGridDB WAS asked (by exact id or by name) and had nothing acceptable.
        MimirConnectedArtGate.setOpen(false)
        MimirSteamGridDbGate.setOpen(true)
        val artwork = state(this)
        val pcGame = game.copy(platformId = PlatformId.PC_GAMES)

        artwork.fetchOne(pcGame, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.NoMatch }
        runCurrent()

        assertEquals(GetArtUi.NotFound, artwork.getArtUi)
    }

    // ── pc_games Connect routing (policy 2026-08-19, follow-up to the provider-copy fix above): the
    // NeedsAccount card's Connect pill must go where its own sentence points -- the SteamGridDB key entry
    // for a pc_games row, not the ScreenScraper login flow `connectFromGetArt` always opens. These two
    // tests runtime-pin `MimirArtworkState.addSteamGridDbKeyFromGetArt` itself; the call site that wires
    // it to `settings.setSteamGridDbKeyEditing(true)` (MimirAppHost.kt, no JVM-reachable seam) is instead
    // characterized as source text in `GetArtOverlayConnectWiringSourceTest`.

    @Test
    fun `addSteamGridDbKeyFromGetArt dismisses the card and opens the key editor`() = runTest {
        MimirConnectedArtGate.setOpen(false)
        MimirSteamGridDbGate.setOpen(false)
        val artwork = state(this)
        val pcGame = game.copy(platformId = PlatformId.PC_GAMES)
        artwork.fetchOne(pcGame, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Matched }
        advanceUntilIdle()
        assertEquals(GetArtUi.NeedsAccount, artwork.getArtUi)

        var keyEditorOpened = false
        artwork.addSteamGridDbKeyFromGetArt { keyEditorOpened = true }

        assertEquals(null, artwork.getArtUi)
        assertTrue("the SteamGridDB key editor must actually open", keyEditorOpened)
    }

    @Test
    fun `connectFromGetArt never opens the SteamGridDB key editor`() = runTest {
        // Differential guard, the other direction: the pre-existing ScreenScraper route must stay exactly
        // what it was -- it has no way to reach a caller-supplied key-editor callback at all.
        MimirConnectedArtGate.setOpen(false)
        MimirSteamGridDbGate.setOpen(false)
        val artwork = state(this)
        artwork.fetchOne(game, emptyList()) { _, _, _ -> ArtworkFetchController.OneResult.Matched }
        advanceUntilIdle()
        assertEquals(GetArtUi.NeedsAccount, artwork.getArtUi)

        artwork.connectFromGetArt()

        assertEquals(null, artwork.getArtUi)
        assertEquals(ArtworkConnectUi.EnterId, artwork.connectUi)
    }
}
