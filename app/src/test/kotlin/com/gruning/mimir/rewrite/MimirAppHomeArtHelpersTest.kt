package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirAppHomeArtHelpersTest {
    private val artChoices = listOf(
        HomeTileArtChoice.UseDefault,
        HomeTileArtChoice.Art(
            SteamGridDbTileFetcher.Candidate(
                url = "https://cdn/a.png",
                thumbUrl = "https://cdn/a-thumb.png",
            ),
        ),
        HomeTileArtChoice.Art(
            SteamGridDbTileFetcher.Candidate(
                url = "https://cdn/b.png",
                thumbUrl = "https://cdn/b-thumb.png",
            ),
        ),
    )

    @Test
    fun artPickerFocusedIndexClampsNullProviderToDefaultAndProviderCandidate() {
        assertEquals(
            0,
            homeArtPickerFocusedIndex(
                choices = artChoices,
                hasActivePick = true,
                uploadedActive = false,
                providerIndex = null,
            ),
        )
        assertEquals(
            2,
            homeArtPickerFocusedIndex(
                choices = artChoices,
                hasActivePick = true,
                uploadedActive = false,
                providerIndex = 1,
            ),
        )
        assertEquals(
            0,
            homeArtPickerFocusedIndex(
                choices = artChoices,
                hasActivePick = false,
                uploadedActive = false,
                providerIndex = 99,
            ),
        )
    }

    @Test
    fun postImportArtPickInsertsUploadedChoiceAfterDefaultAndClearsFooter() {
        val phase = HomeTileMenuPhase.ArtPick(
            choices = listOf(
                HomeTileArtChoice.UseDefault,
                HomeTileArtChoice.UploadedImage("/old.png"),
                HomeTileArtChoice.ImportImage,
            ) + artChoices,
            focusedIndex = 0,
            footerFocused = true,
        )

        val updated = homeArtPickAfterImport(phase, "/retained.png")

        assertEquals(
            listOf(
                HomeTileArtChoice.UseDefault,
                HomeTileArtChoice.UploadedImage("/retained.png"),
                HomeTileArtChoice.ImportImage,
            ) + artChoices,
            updated.choices,
        )
        assertEquals(1, updated.focusedIndex)
        assertFalse(updated.footerFocused)
    }

    @Test
    fun postImportHeroPickFiltersPriorUploadAndClearsFooter() {
        val phase = HomeTileMenuPhase.HeroPick(
            choices = listOf(
                HomeTileHeroChoice.UseDefault,
                HomeTileHeroChoice.UploadedImage("/old-hero.png"),
                HomeTileHeroChoice.ImportImage,
            ),
            focusedIndex = 0,
            footerFocused = true,
        )

        val updated = homeHeroPickAfterImport(phase, "/new-hero.png")

        assertEquals(
            listOf(
                HomeTileHeroChoice.UseDefault,
                HomeTileHeroChoice.UploadedImage("/new-hero.png"),
                HomeTileHeroChoice.ImportImage,
            ),
            updated.choices,
        )
        assertEquals(1, updated.focusedIndex)
        assertFalse(updated.footerFocused)
    }

    @Test
    fun cacheAndStoreIdsMatchAcrossHomeShelfItemIdVariants() {
        val emulated = HomeShelfItemId.Emulated(GameId("snes/chrono"))
        val androidGame = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game"))
        val androidApp = HomeShelfItemId.AndroidApp(AndroidPackageName("com.example.app"))

        val emulatedKey = homeTileMenuCacheKey(emulated)
        val gameKey = homeTileMenuCacheKey(androidGame)
        val appKey = homeTileMenuCacheKey(androidApp)

        assertEquals(SteamGridDbTileFetcher.emulatedKey(GameId("snes/chrono")), emulatedKey)
        assertEquals(SteamGridDbTileFetcher.androidKey("com.example.game"), gameKey)
        assertEquals(SteamGridDbTileFetcher.androidKey("com.example.app"), appKey)

        assertEquals(GameId(emulatedKey), homeTileHeroStoreId(emulated))
        assertEquals(GameId(gameKey), homeTileHeroStoreId(androidGame))
        assertEquals(GameId(appKey), homeTileHeroStoreId(androidApp))
        assertEquals(homeTileMenuCacheKey(emulated), homeTileHeroStoreId(emulated).value)
    }

    @Test
    fun libraryHeroPickerProjectsGameTruthWithoutHomeFavoriteMembership() {
        val game = GameRow(
            gameId = GameId("gba/mother3"),
            title = "Mother 3",
            platformId = PlatformId("gba"),
            memberCount = 1,
            duplicateKind = null,
            description = "A sequel.",
            descriptionSource = "local",
            artUri = "file:///hero.png",
            flatArtUri = "file:///flat.png",
        )

        val row = libraryHeroPickerRow(game)
        val state = libraryHeroPickerMenuState(game)

        assertEquals(HomeShelfItemId.Emulated(game.gameId), row.itemId)
        assertEquals("file:///hero.png", row.artworkUri)
        assertEquals(game, state.sourceGame)
        assertEquals(HomeTileMenuEntryPoint.LIBRARY_HERO, state.entryPoint)
        assertFalse(state.removable)
        // S-2 : lands on the top-level menu, not
        // the media sub-menu -- see LibraryHeroPickerTest for the full landing-phase/two-CONFIRM pin.
        assertEquals(HomeTileMenuPhase.Menu, state.phase)
        // No type is pre-seeded: Customize media opens on Icon art from this door too .
        assertEquals(null, state.customEnrichFocus)
        // this record has
        // a synopsis plus distinct cover/flat art, so it is VERIFIED_COMPLETE -- Enrich must stay off the
        // menu, unchanged from before that ruling.
        assertFalse(state.offerEnrich)
        assertFalse(HomeTileMenuAction.ENRICH in homeTileMenuActions(state))
    }
}
