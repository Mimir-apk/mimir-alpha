package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.EnrichAttemptOutcome
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.ProviderEnrichmentState
import com.gruning.mimir.rewrite.feature.library.providerEnrichmentState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * once an Enrich has FINISHED for a game, neither the direct seat nor the Y-menu
 * row offers it again, even when a source had no logo or cover for it; a FAILED attempt keeps (or brings back) both. Every case here is a
 * game that ended with a logo or a cover missing, which is the one the old rule kept offering forever.
 */
class EnrichAttemptHidesEnrichTest {
    private val bare = GameRow(
        gameId = GameId("ps2/no-logo-anywhere"),
        title = "No Logo Anywhere",
        platformId = PlatformId.PS2,
        memberCount = 1,
        duplicateKind = null,
    )

    /** A provider record exists but the logo is missing: the case the 08-19 rule offered a retry for, forever. */
    private val partial = bare.copy(
        description = "A synopsis.",
        descriptionSource = "screenscraper:en",
        artUri = "file:///cover.png",
        flatArtUri = null,
    )

    @Test
    fun `an Enrich that finished with no logo hides the direct seat and the Y-menu row`() {
        for (finished in listOf(EnrichAttemptOutcome.MATCHED, EnrichAttemptOutcome.NO_MATCH, EnrichAttemptOutcome.NO_TITLE)) {
            val game = bare.copy(enrichAttempt = finished)
            assertEquals("$finished", ProviderEnrichmentState.ATTEMPTED, game.providerEnrichmentState())
            assertFalse("$finished: the direct seat", game.providerEnrichmentState().shouldOfferEnrich)
            assertFalse("$finished: the Y-menu row", game.tileMenuOffersEnrich())
            assertFalse("$finished: the Library door's menu", libraryHeroPickerMenuState(game).offerEnrich)
            assertFalse(
                "$finished: Home's menu for the same game",
                homeTileMenuState(libraryHeroPickerRow(game), game, removable = true).offerEnrich,
            )
            assertFalse(HomeTileMenuAction.ENRICH in homeTileMenuActions(libraryHeroPickerMenuState(game)))
        }
    }

    @Test
    fun `a game with a record but no logo hides the Y-menu row once an attempt finished`() {
        assertEquals(ProviderEnrichmentState.VERIFIED_PARTIAL, partial.providerEnrichmentState())
        for (finished in listOf(EnrichAttemptOutcome.MATCHED, EnrichAttemptOutcome.NO_MATCH)) {
            val game = partial.copy(enrichAttempt = finished)
            assertFalse("$finished", game.tileMenuOffersEnrich())
            assertFalse("$finished", libraryHeroPickerMenuState(game).offerEnrich)
        }
        // A record is itself proof that a run finished, so a legacy game with no attempt on record does not keep the retry either.
        assertFalse(partial.tileMenuOffersEnrich())
    }

    @Test
    fun `a failed attempt keeps both controls`() {
        val noRecord = bare.copy(enrichAttempt = EnrichAttemptOutcome.FAILED)
        assertEquals(ProviderEnrichmentState.NEVER_REQUESTED, noRecord.providerEnrichmentState())
        assertTrue("the direct seat", noRecord.providerEnrichmentState().shouldOfferEnrich)
        assertTrue("the Y-menu row", noRecord.tileMenuOffersEnrich())

        val withRecord = partial.copy(enrichAttempt = EnrichAttemptOutcome.FAILED)
        assertTrue("a record with a missing logo and a failed attempt gets the retry back", withRecord.tileMenuOffersEnrich())
        assertEquals(HomeTileMenuAction.ENRICH, homeTileMenuActions(libraryHeroPickerMenuState(withRecord)).first())
        assertEquals(
            "Home's menu agrees",
            HomeTileMenuAction.ENRICH,
            homeTileMenuActions(homeTileMenuState(libraryHeroPickerRow(withRecord), withRecord, removable = true)).first(),
        )
    }

    @Test
    fun `a game never enriched keeps the direct seat`() {
        assertTrue(bare.providerEnrichmentState().shouldOfferEnrich)
        assertTrue(bare.tileMenuOffersEnrich())
    }

    @Test
    fun `the projection hands the state the attempt, not just the record`() {
        assertEquals(
            ProviderEnrichmentState.ATTEMPTED,
            providerEnrichmentState(null, null, null, null, enrichAttempt = EnrichAttemptOutcome.NO_MATCH),
        )
    }

    // ── what a result means for the record ────────────────────────────────────────────────────────

    @Test
    fun `a result that answered or had nothing to ask is finished, a failure is not, and a closed gate is no attempt`() {
        val outcome = ::enrichAttemptOutcomeFor
        assertEquals(EnrichAttemptOutcome.MATCHED, outcome(ArtworkFetchController.OneResult.Matched))
        assertEquals(EnrichAttemptOutcome.NO_MATCH, outcome(ArtworkFetchController.OneResult.NoMatch))
        assertEquals(EnrichAttemptOutcome.NO_TITLE, outcome(ArtworkFetchController.OneResult.NoTitle))
        assertEquals(EnrichAttemptOutcome.FAILED, outcome(ArtworkFetchController.OneResult.Failed))
        // Nothing was asked: the record stays as it was, and the button was never hidden by it.
        assertNull(outcome(ArtworkFetchController.OneResult.GateClosed))
        assertNull(outcome(ArtworkFetchController.OneResult.Busy))
    }

    @Test
    fun `source text -- every per-game Enrich records its result after the claim is taken and before it is released`() {
        val fetch = source("ArtworkFetchController.kt")
        val entry = fetch.substringAfter("explicitPress: Boolean = false,\n    ): OneResult {").substringBefore("private suspend fun fetchOneArtworkClaimed(")
        assertTrue(entry.contains(".also { recordEnrichAttempt(context, gameId, it) }"))
        assertTrue(
            "recorded inside the try, so the claim is still held",
            entry.indexOf("recordEnrichAttempt(") in entry.indexOf("try {") until entry.indexOf("} finally {"),
        )
        val record = fetch.substringAfter("internal suspend fun recordEnrichAttempt(").substringBefore("internal suspend fun settlePcLogoOnlyPress(")
        assertTrue(record.contains("enrichAttemptOutcomeFor(result) ?: return"))
        assertTrue(record.contains("EnrichAttemptStore.record(gameId, outcome)"))
        assertTrue("the changed record re-projects the one game", record.contains("LibraryStore.repository(context).refreshArt(GameId(gameId))"))
    }

    @Test
    fun `source text -- the projection reads the record through the registered source`() {
        val application = source("MimirApplication.kt")
        assertTrue(application.contains("LibraryStore.enrichAttemptSource = EnrichAttemptSource { gameId -> EnrichAttemptStore.outcomeFor(gameId) }"))
        assertTrue(application.contains("EnrichAttemptStore.attach(this)"))
    }

    // ── the keyless PC note: copy, wording and what its buttons do ───────────────────────────────

    @Test
    fun `the keyless PC note says PC art needs a SteamGridDB key, and names the Settings path as Settings labels it`() {
        val path = "Settings › Appearance › Enrichment & artwork"
        assertEquals(path, GetArtPcKeyWhereCopy)
        assertEquals("the same path every other keyless-PC sentence uses", GameMusicConnectWhereCopy, GetArtPcKeyWhereCopy)
        for (saved in listOf(true, false)) {
            val copy = getArtPcKeyNoteCopy(saved)
            assertTrue("saved=$saved: names the key", copy.body.contains("SteamGridDB key"))
            assertTrue("saved=$saved: names where to add it", copy.body.endsWith("Add one in $path."))
        }
        assertEquals("Game enriched", getArtPcKeyNoteCopy(true).header)
        assertEquals("No match this time", getArtPcKeyNoteCopy(false).header)
        // ScreenScraper serves a PC game's logo and nothing else: a saved logo does not claim the logo still needs a key.
        assertTrue(getArtPcKeyNoteCopy(true).body.startsWith("Mimir saved a logo. PC covers and heroes need a SteamGridDB key."))
        assertTrue(getArtPcKeyNoteCopy(false).body.startsWith("ScreenScraper had no logo for this game. PC logos and heroes need a SteamGridDB key."))
    }

    @Test
    fun `Settings really labels that path`() {
        val settings = "feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings"
        val ia = repoFile("$settings/SettingsIa.kt")
        val detail = repoFile("$settings/SettingsDetail.kt")
        assertTrue("Settings > Appearance", ia.contains("label = \"Appearance\","))
        assertTrue("the section that holds the SteamGridDB row", detail.contains("SettingsSection(title = \"Enrichment & artwork\")"))
        assertTrue("the SteamGridDB row itself", detail.contains("title = \"SteamGridDB\","))
    }

    @Test
    fun `the key note's Add key opens the key editor, Not now and Back dismiss it, and each does only that`() {
        val authority = DefaultFocusAuthority().also {
            mountGetArtFocusScope(it)
            it.register(GetArtDismissKey, GetArtFocusScope, FocusBounds(0f, 0f, 100f, 100f))
            it.register(GetArtConnectKey, GetArtFocusScope, FocusBounds(100f, 0f, 200f, 100f))
            it.requestFocus(GetArtConnectKey)
        }
        var connected = 0
        var keyEditor = 0
        var dismissed = 0
        val press = { action: ControllerAction ->
            dispatchGetArt(
                authority = authority,
                state = GetArtUi.PcKeyNote(logoSaved = true),
                provider = GetArtProvider.SCREENSCRAPER,
                action = action,
                onConnect = { connected++ },
                onAddSteamGridDbKey = { keyEditor++ },
                onDismiss = { dismissed++ },
            )
        }

        press(ControllerAction.CONFIRM)
        assertEquals("Add key goes to the key editor", 1, keyEditor)
        assertEquals("and never to the ScreenScraper sign-in", 0, connected)
        assertEquals(0, dismissed)

        press(ControllerAction.NAV_LEFT)
        press(ControllerAction.CONFIRM)
        assertEquals("Not now dismisses", 1, dismissed)
        assertEquals(1, keyEditor)

        press(ControllerAction.CANCEL)
        assertEquals("Back dismisses", 2, dismissed)
    }

    @Test
    fun `the key note is one stable page, whichever way the result went`() {
        assertEquals(getArtPageKey(GetArtUi.PcKeyNote(true)), getArtPageKey(GetArtUi.PcKeyNote(false)))
        assertEquals(GetArtPageKey.PC_KEY_NOTE, getArtPageKey(GetArtUi.PcKeyNote(true)))
    }

    private fun source(name: String): String = repoFile("app/src/main/kotlin/com/gruning/mimir/rewrite/$name")

    /** A repo file by its path from the repository root; the tests run from the `app` module directory. */
    private fun repoFile(path: String): String {
        val file = java.io.File("..", path)
        check(file.isFile) { "$path not found from ${java.io.File(".").absolutePath}" }
        return file.readText()
    }
}

/** The per-game record on a real SharedPreferences file: write, read back, replace, and follow an identity alias. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class EnrichAttemptStoreTest {
    @Before
    fun fresh() {
        EnrichAttemptStore.detachForTest()
        RuntimeEnvironment.getApplication().getSharedPreferences("mimir_rewrite_enrich_attempts", 0).edit().clear().commit()
        EnrichAttemptStore.attach(RuntimeEnvironment.getApplication())
    }

    @After
    fun resetAliases() {
        GameIdentityAliasCache.install(emptyMap())
        EnrichAttemptStore.detachForTest()
    }

    @Test
    fun `a game nothing finished for reads null, and a record reads back as written`() {
        assertNull(EnrichAttemptStore.outcomeFor("ps2/never"))
        assertTrue("the first write is a change", EnrichAttemptStore.record("ps2/never", EnrichAttemptOutcome.NO_MATCH))
        assertEquals(EnrichAttemptOutcome.NO_MATCH, EnrichAttemptStore.outcomeFor("ps2/never"))
        assertNull("another game is untouched", EnrichAttemptStore.outcomeFor("ps2/other"))
    }

    @Test
    fun `the same answer is not a change, so the library is not re-projected for nothing`() {
        EnrichAttemptStore.record("ps2/same", EnrichAttemptOutcome.MATCHED)
        assertFalse(EnrichAttemptStore.record("ps2/same", EnrichAttemptOutcome.MATCHED))
    }

    @Test
    fun `a failure replaces a finished attempt, which is how the button comes back`() {
        EnrichAttemptStore.record("ps2/back", EnrichAttemptOutcome.MATCHED)
        assertTrue(EnrichAttemptStore.record("ps2/back", EnrichAttemptOutcome.FAILED))
        assertEquals(EnrichAttemptOutcome.FAILED, EnrichAttemptStore.outcomeFor("ps2/back"))
        assertFalse(EnrichAttemptStore.outcomeFor("ps2/back")!!.finished)
    }

    @Test
    fun `an old id and its current id share one record`() {
        GameIdentityAliasCache.install(mapOf("ps2/old-name" to "ps2/new-name"))
        EnrichAttemptStore.record("ps2/old-name", EnrichAttemptOutcome.NO_MATCH)
        assertEquals(EnrichAttemptOutcome.NO_MATCH, EnrichAttemptStore.outcomeFor("ps2/new-name"))
        assertEquals(EnrichAttemptOutcome.NO_MATCH, EnrichAttemptStore.outcomeFor("ps2/old-name"))
    }

    @Test
    fun `a blank id and a detached store are inert`() {
        assertFalse(EnrichAttemptStore.record("", EnrichAttemptOutcome.MATCHED))
        assertNull(EnrichAttemptStore.outcomeFor(""))
        EnrichAttemptStore.detachForTest()
        assertFalse(EnrichAttemptStore.record("ps2/detached", EnrichAttemptOutcome.MATCHED))
        assertNull(EnrichAttemptStore.outcomeFor("ps2/detached"))
    }
}
