package com.gruning.mimir.rewrite

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Compose render of [GetArtOverlay] (policy 2026-08-19): the overlay must name
 * whichever provider ENRICH actually ran, never a hardcoded "ScreenScraper" for a `pc_games` row
 * ScreenScraper can never identify.
 *
 * Unlike `ArtworkFetchController`'s DB/Keystore-bound routing (source-text characterized in
 * [PcEnrichSteamGridDbRoutingWiringTest] because no runtime seam reaches it from a JVM unit test),
 * [GetArtOverlay] is a plain composable taking [GetArtUi] and [GetArtProvider] directly, with no
 * coordinator/ViewModel dependency — this repo's own precedent for exactly this shape
 * (`SettingsLibraryReadFailureRenderTest`) shows it composes for real under Robolectric. So the actual
 * rendered text is asserted here, not merely pinned as source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GetArtOverlayProviderCopyTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(
        state: GetArtUi,
        provider: GetArtProvider,
        onConnect: () -> Unit = {},
        onAddSteamGridDbKey: () -> Unit = {},
    ) {
        compose.setContent {
            MimirTheme {
                GetArtOverlay(
                    state = state,
                    provider = provider,
                    onConnect = onConnect,
                    onAddSteamGridDbKey = onAddSteamGridDbKey,
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `Working renders the ScreenScraper sentence for the ScreenScraper provider`() {
        render(GetArtUi.Working, GetArtProvider.SCREENSCRAPER)

        compose.onNodeWithText("Looking up cover art, logo, and game details on ScreenScraper.")
            .assertExists()
        compose.onNodeWithText("Looking up art on SteamGridDB.").assertDoesNotExist()
    }

    @Test
    fun `Working renders the SteamGridDB sentence for the SteamGridDB provider`() {
        render(GetArtUi.Working, GetArtProvider.STEAMGRIDDB)

        compose.onNodeWithText("Looking up art on SteamGridDB.").assertExists()
        compose.onNodeWithText("Looking up cover art, logo, and game details on ScreenScraper.")
            .assertDoesNotExist()
    }

    @Test
    fun `NotFound renders the ScreenScraper sentence for the ScreenScraper provider, byte-identical to today`() {
        render(GetArtUi.NotFound, GetArtProvider.SCREENSCRAPER)

        // Reused verbatim from GetArtOverlay.kt -- not retyped -- the exact curly-apostrophe string this
        // fix must leave byte-identical for every non-pc_games platform.
        compose.onNodeWithText("Mimir couldn’t verify this game on ScreenScraper. Nothing was changed.")
            .assertExists()
        compose.onNodeWithText("SteamGridDB had no art for this game. Nothing was changed.")
            .assertDoesNotExist()
    }

    @Test
    fun `NotFound renders the shared SteamGridDB sentence for the SteamGridDB provider`() {
        render(GetArtUi.NotFound, GetArtProvider.STEAMGRIDDB)

        compose.onNodeWithText("SteamGridDB had no art for this game. Nothing was changed.")
            .assertExists()
        compose.onNodeWithText("Mimir couldn’t verify this game on ScreenScraper. Nothing was changed.")
            .assertDoesNotExist()
    }

    /**
     * Reported provider mismatch, 2026-09-11: a `pc_games` row with no `.steam` shortcut used to reach NotFound WITHOUT SteamGridDB
     * ever being asked, so the sentence above ("SteamGridDB had no art for this game") was untrue. That
     * row now searches by name; the one case that still ends without a lookup -- no shortcut AND no
     * searchable title -- gets its own page that says exactly that, and never the provider-miss sentence.
     */
    @Test
    fun `NotSearchable says nothing was looked up for the SteamGridDB provider, never that it had no art`() {
        render(GetArtUi.NotSearchable, GetArtProvider.STEAMGRIDDB)

        compose.onNodeWithContentDescription("Nothing to look up", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("This PC game has no Steam shortcut and no searchable title. Nothing was changed.")
            .assertExists()
        compose.onNodeWithText("SteamGridDB had no art for this game. Nothing was changed.")
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("No match this time", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `NotSearchable keeps a provider-neutral sentence for the ScreenScraper provider`() {
        // Unreachable in production today (the ScreenScraper route always has a raw base name to search
        // with), but the `when (provider)` is exhaustive and this arm must not borrow SteamGridDB's copy.
        render(GetArtUi.NotSearchable, GetArtProvider.SCREENSCRAPER)

        compose.onNodeWithText("This game has no searchable title. Nothing was changed.").assertExists()
        compose.onNodeWithText("This PC game has no Steam shortcut and no searchable title. Nothing was changed.")
            .assertDoesNotExist()
        compose.onNodeWithText("Mimir couldn’t verify this game on ScreenScraper. Nothing was changed.")
            .assertDoesNotExist()
    }

    @Test
    fun `Found renders the ScreenScraper sentence for the ScreenScraper provider, byte-identical to today`() {
        render(GetArtUi.Found, GetArtProvider.SCREENSCRAPER)

        // Byte-identical to before this fix -- ScreenScraper verifies a game record, not just artwork,
        // so "verified details" stays accurate and long-lived for that provider.
        compose.onNodeWithText("Mimir saved the verified details and any available artwork it could add.")
            .assertExists()
        compose.onNodeWithText("Mimir saved what it could find for this game.").assertDoesNotExist()
    }

    /**
     * SteamGridDB fetches art only, never a synopsis, so
     * "verified details" overclaimed for an art-only Found result. Replaces the old
     * `Found copy is provider-agnostic even for the SteamGridDB provider` test, which pinned exactly the
     * overclaiming behaviour this ruling removes.
     */
    @Test
    fun `Found renders the shared SteamGridDB sentence for the SteamGridDB provider`() {
        render(GetArtUi.Found, GetArtProvider.STEAMGRIDDB)

        compose.onNodeWithText("Mimir saved what it could find for this game.").assertExists()
        compose.onNodeWithText("Mimir saved the verified details and any available artwork it could add.")
            .assertDoesNotExist()
    }

    @Test
    fun `Failed copy is provider-agnostic even for the SteamGridDB provider`() {
        render(GetArtUi.Failed, GetArtProvider.STEAMGRIDDB)

        compose.onNodeWithText("Check your connection, then choose Enrich again.").assertExists()
    }

    @Test
    fun `NeedsAccount renders the ScreenScraper sentence for the ScreenScraper provider, byte-identical to today`() {
        render(GetArtUi.NeedsAccount, GetArtProvider.SCREENSCRAPER)

        // Reused verbatim from GetArtOverlay.kt -- not retyped -- the exact string every non-pc_games
        // platform must keep byte-identical: it is accurate and long-lived for them.
        compose.onNodeWithText(
            "Enrich needs a free ScreenScraper account for covers, logos, and game details. " +
                "Connect one in Settings › Appearance › Enrichment & artwork, then enrich any game.",
        ).assertExists()
        compose.onNodeWithText("Enrich needs a SteamGridDB key. Add one in Settings › Appearance › Enrichment & artwork.")
            .assertDoesNotExist()
    }

    /**
     * Provider routing, 2026-08-19: R-24's Enrich gate fix correctly sends a keyless `pc_games` row
     * to NeedsAccount, but the pre-fix sentence named ScreenScraper -- the one provider that structurally
     * cannot serve `pc_games` (it matches ROM files by hash and name; a GameNative shortcut is not one).
     * Same defect class reported on device: being told to go get a thing that cannot help.
     */
    @Test
    fun `NeedsAccount renders the shared SteamGridDB sentence for the SteamGridDB provider`() {
        render(GetArtUi.NeedsAccount, GetArtProvider.STEAMGRIDDB)

        compose.onNodeWithText("Enrich needs a SteamGridDB key. Add one in Settings › Appearance › Enrichment & artwork.")
            .assertExists()
        compose.onNodeWithText(
            "Enrich needs a free ScreenScraper account for covers, logos, and game details. " +
                "Connect one in Settings › Appearance › Enrichment & artwork, then enrich any game.",
        ).assertDoesNotExist()
    }

    // ── Header + button + routing (policy 2026-08-19, follow-up: the Connect pill must act where its
    // own sentence points). The header and button label are MimirChromeText, not plain Text -- per this
    // package's own established convention (see ArtworkPhaseSequenceGrammarDispatchTest.kt's file header),
    // its rendered contentDescription carries the natural-case string, not onNodeWithText's semantics query.

    @Test
    fun `NeedsAccount renders the ScreenScraper header and button, byte-identical to today`() {
        render(GetArtUi.NeedsAccount, GetArtProvider.SCREENSCRAPER)

        compose.onNodeWithContentDescription("Connect to enrich", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Connect", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Add a key to enrich", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Add key", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `NeedsAccount renders the shared header and button for the SteamGridDB provider`() {
        render(GetArtUi.NeedsAccount, GetArtProvider.STEAMGRIDDB)

        compose.onNodeWithContentDescription("Add a key to enrich", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Add key", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Connect to enrich", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Connect", useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * The defect this ruling closes, reproduced and then closed: the sentence named SteamGridDB but the
     * pill beneath it unconditionally opened the ScreenScraper login flow (`connectFromGetArt`) anyway.
     * Pins the AGREEMENT, not just the strings -- a provider whose copy says "Add a key" must invoke ONLY
     * the key-entry callback, never the ScreenScraper one, and vice versa below.
     */
    @Test
    fun `the SteamGridDB card's button routes to the key editor, never the ScreenScraper connect flow`() {
        var connected = false
        var addedKey = false
        render(
            GetArtUi.NeedsAccount,
            GetArtProvider.STEAMGRIDDB,
            onConnect = { connected = true },
            onAddSteamGridDbKey = { addedKey = true },
        )

        compose.onNodeWithContentDescription("Add key", useUnmergedTree = true).performClick()

        assertTrue("the SteamGridDB card's button must reach onAddSteamGridDbKey", addedKey)
        assertFalse("the SteamGridDB card's button must never reach the ScreenScraper connect flow", connected)
    }

    // ── The keyless-PC note : the sentence really reaches the screen, with the path and the key button ───────

    @Test
    fun `the keyless PC note renders the result and the SteamGridDB key sentence with the Settings path`() {
        render(GetArtUi.PcKeyNote(logoSaved = false), GetArtProvider.SCREENSCRAPER)

        compose.onNodeWithText(getArtPcKeyNoteCopy(logoSaved = false).body).assertExists()
        compose.onNodeWithText(
            "ScreenScraper had no logo for this game. PC logos and heroes need a SteamGridDB key. " +
                "Add one in Settings › Appearance › Enrichment & artwork.",
        ).assertExists()
        compose.onNodeWithContentDescription("No match this time", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Add key", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Not now", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the keyless PC note after a saved logo says so, then the same key sentence`() {
        render(GetArtUi.PcKeyNote(logoSaved = true), GetArtProvider.SCREENSCRAPER)

        compose.onNodeWithText(
            "Mimir saved a logo. PC covers and heroes need a SteamGridDB key. " +
                "Add one in Settings › Appearance › Enrichment & artwork.",
        ).assertExists()
        compose.onNodeWithContentDescription("Game enriched", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the plain NotFound card never carries the key sentence`() {
        render(GetArtUi.NotFound, GetArtProvider.SCREENSCRAPER)

        compose.onNodeWithText("Mimir couldn’t verify this game on ScreenScraper. Nothing was changed.").assertExists()
        compose.onNodeWithText(getArtPcKeyNoteCopy(logoSaved = false).body).assertDoesNotExist()
        compose.onNodeWithContentDescription("Add key", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the key note's Add key button reaches the key editor and never the ScreenScraper connect flow`() {
        var connected = false
        var addedKey = false
        render(
            GetArtUi.PcKeyNote(logoSaved = false),
            GetArtProvider.SCREENSCRAPER,
            onConnect = { connected = true },
            onAddSteamGridDbKey = { addedKey = true },
        )

        compose.onNodeWithContentDescription("Add key", useUnmergedTree = true).performClick()

        assertTrue(addedKey)
        assertFalse(connected)
    }

    @Test
    fun `the ScreenScraper card's button still routes to connect, never the SteamGridDB key editor`() {
        var connected = false
        var addedKey = false
        render(
            GetArtUi.NeedsAccount,
            GetArtProvider.SCREENSCRAPER,
            onConnect = { connected = true },
            onAddSteamGridDbKey = { addedKey = true },
        )

        compose.onNodeWithContentDescription("Connect", useUnmergedTree = true).performClick()

        assertTrue("the ScreenScraper card's button must still reach onConnect", connected)
        assertFalse("the ScreenScraper card's button must never reach the SteamGridDB key editor", addedKey)
    }
}

/**
 * Pure-function pin of [needsAccountCta] itself -- the single `when (provider)` that yokes
 * [GetArtUi.NeedsAccount]'s header, sentence, button label, and button action together (see its own KDoc
 * in `GetArtOverlay.kt`). No Robolectric/Compose needed: this is plain data-in, data-out, and it is the
 * cheapest possible pin of "the agreement" the policy asked for -- header/label/action cannot drift
 * apart from each other without failing one of these four assertions.
 */
class NeedsAccountCtaTest {

    @Test
    fun `ScreenScraper's header, label, and action are byte-identical to today and wired to onConnect`() {
        var connected = false
        var addedKey = false
        val cta = needsAccountCta(
            GetArtProvider.SCREENSCRAPER,
            onConnect = { connected = true },
            onAddSteamGridDbKey = { addedKey = true },
        )

        assertEquals("Connect to enrich", cta.header)
        assertEquals("Connect", cta.buttonLabel)
        cta.onClick()
        assertTrue(connected)
        assertFalse(addedKey)
    }

    @Test
    fun `SteamGridDB's header, label, and action are the shared pc_games copy, wired to onAddSteamGridDbKey`() {
        var connected = false
        var addedKey = false
        val cta = needsAccountCta(
            GetArtProvider.STEAMGRIDDB,
            onConnect = { connected = true },
            onAddSteamGridDbKey = { addedKey = true },
        )

        assertEquals("Add a key to enrich", cta.header)
        assertEquals("Add key", cta.buttonLabel)
        cta.onClick()
        assertTrue(addedKey)
        assertFalse(connected)
    }
}
