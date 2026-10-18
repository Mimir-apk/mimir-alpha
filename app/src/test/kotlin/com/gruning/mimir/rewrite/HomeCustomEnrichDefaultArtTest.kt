package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy CP-03 + Slice B settlement: the `Default` entry paints the art it would restore.
 */
class HomeCustomEnrichDefaultArtTest {

    @Test
    fun aPlatformIconDefaultsToMimirsOwnConsoleLineArt() {
        val art = homeCustomEnrichDefaultArt(
            subject = HomeCustomEnrichSubject.PLATFORM,
            kind = EnrichMediaKind.ICON,
            title = "Nintendo 64",
            platformIdValue = "n64",
            automaticArtUri = null,
        )
        assertEquals(HomeCustomEnrichDefaultArt.ConsoleLineArt("n64"), art)
    }

    @Test
    fun theLivePlatformPickerCarriesThatConsoleArtIntoItsState() {
        val picker = newPlatformCustomEnrichPicker(
            platformId = PlatformId("snes"),
            label = "Super Nintendo",
            catalog = EnrichSavedCatalog(activeAssetId = null, entries = emptyList()),
            catalogReady = true,
            sessionId = "test-platform-snes",
        )
        assertEquals(HomeCustomEnrichDefaultArt.ConsoleLineArt("snes"), picker.defaultArt)
        assertEquals(EnrichMediaKind.ICON, picker.kind)
        assertNull(picker.activeAssetId)
    }

    @Test
    fun aGameIconWithScrapedArtDefaultsToThatAutomaticArtNotTheCustomPick() {
        val art = homeCustomEnrichDefaultArt(
            subject = HomeCustomEnrichSubject.GAME,
            kind = EnrichMediaKind.ICON,
            title = "Chrono Trigger",
            platformIdValue = "snes",
            automaticArtUri = "file:///media/scraped/chrono.png",
        )
        assertEquals(HomeCustomEnrichDefaultArt.Automatic("file:///media/scraped/chrono.png"), art)
    }

    @Test
    fun aGameIconWithNoAutomaticArtDefaultsToTheCoverlessBook() {
        val art = homeCustomEnrichDefaultArt(
            subject = HomeCustomEnrichSubject.GAME,
            kind = EnrichMediaKind.ICON,
            title = "Unscraped Game",
            platformIdValue = "ps1",
            automaticArtUri = null,
        )
        assertEquals(HomeCustomEnrichDefaultArt.TitleBook("Unscraped Game", "ps1"), art)
    }

    @Test
    fun aBlankAutomaticUriIsTreatedAsNoArtRatherThanAnEmptyTile() {
        listOf("", "   ").forEach { blank ->
            val art = homeCustomEnrichDefaultArt(
                subject = HomeCustomEnrichSubject.GAME,
                kind = EnrichMediaKind.ICON,
                title = "Blank",
                platformIdValue = null,
                automaticArtUri = blank,
            )
            assertEquals(HomeCustomEnrichDefaultArt.TitleBook("Blank", null), art)
        }
    }

    @Test
    fun aPlatformWithoutAnIdKeepsNoAutomaticMediaInsteadOfDrawingAGenericConsole() {
        listOf(null, "", "  ").forEach { missing ->
            val art = homeCustomEnrichDefaultArt(
                subject = HomeCustomEnrichSubject.PLATFORM,
                kind = EnrichMediaKind.ICON,
                title = "Unknown",
                platformIdValue = missing,
                automaticArtUri = null,
            )
            assertSame(HomeCustomEnrichDefaultArt.Unavailable, art)
        }
    }

    @Test
    fun tomePhysicalDefaultsToTileIconThenArtworkUri() {
        val fromIcon = homeCustomEnrichDefaultArt(
            subject = HomeCustomEnrichSubject.GAME,
            kind = EnrichMediaKind.TOME_PHYSICAL,
            title = "Game",
            platformIdValue = "snes",
            automaticArtUri = "file:///artwork.png",
            tileIconUri = "file:///tile.png",
        )
        assertEquals(HomeCustomEnrichDefaultArt.Automatic("file:///tile.png"), fromIcon)

        val fromArtwork = homeCustomEnrichDefaultArt(
            subject = HomeCustomEnrichSubject.GAME,
            kind = EnrichMediaKind.TOME_PHYSICAL,
            title = "Game",
            platformIdValue = "snes",
            automaticArtUri = "file:///artwork.png",
            tileIconUri = null,
        )
        assertEquals(HomeCustomEnrichDefaultArt.Automatic("file:///artwork.png"), fromArtwork)
    }

    @Test
    fun tomePhysicalWithoutWellArtFallsBackToTitleBookForGames() {
        val art = homeCustomEnrichDefaultArt(
            subject = HomeCustomEnrichSubject.GAME,
            kind = EnrichMediaKind.TOME_PHYSICAL,
            title = "Coverless",
            platformIdValue = "gba",
            automaticArtUri = null,
            tileIconUri = null,
        )
        assertEquals(HomeCustomEnrichDefaultArt.TitleBook("Coverless", "gba"), art)
    }

    @Test
    fun clipDefaultUsesPosterOnlyAndNeverFabricatesVideoArt() {
        val withPoster = homeCustomEnrichDefaultArt(
            subject = HomeCustomEnrichSubject.GAME,
            kind = EnrichMediaKind.CLIP,
            title = "Game",
            platformIdValue = "snes",
            automaticArtUri = "file:///cover.png",
            previewVideoUri = "file:///clip.mp4",
            clipPosterUri = "file:///poster.jpg",
        )
        assertEquals(
            HomeCustomEnrichDefaultArt.ClipAutomatic("file:///poster.jpg"),
            withPoster,
        )

        val withoutPoster = homeCustomEnrichDefaultArt(
            subject = HomeCustomEnrichSubject.GAME,
            kind = EnrichMediaKind.CLIP,
            title = "Game",
            platformIdValue = "snes",
            automaticArtUri = null,
            previewVideoUri = "file:///clip.mp4",
            clipPosterUri = null,
        )
        assertEquals(HomeCustomEnrichDefaultArt.ClipAutomatic(null), withoutPoster)
    }

    @Test
    fun clipWithoutAutomaticVideoIsExplicitlyAbsent() {
        assertSame(
            HomeCustomEnrichDefaultArt.Unavailable,
            homeCustomEnrichDefaultArt(
                subject = HomeCustomEnrichSubject.GAME,
                kind = EnrichMediaKind.CLIP,
                title = "Game",
                platformIdValue = "snes",
                automaticArtUri = "file:///cover.png",
                previewVideoUri = null,
            ),
        )
    }

    @Test
    fun heroDefaultUsesManagedHeroArtWhenPresentOtherwiseExplicitAbsence() {
        assertEquals(
            HomeCustomEnrichDefaultArt.Automatic("file:///hero.webp"),
            homeCustomEnrichDefaultArt(
                subject = HomeCustomEnrichSubject.GAME,
                kind = EnrichMediaKind.HERO,
                title = "Game",
                platformIdValue = "snes",
                automaticArtUri = "file:///cover.png",
                heroArtUri = "file:///hero.webp",
            ),
        )
        assertSame(
            HomeCustomEnrichDefaultArt.Unavailable,
            homeCustomEnrichDefaultArt(
                subject = HomeCustomEnrichSubject.GAME,
                kind = EnrichMediaKind.HERO,
                title = "Game",
                platformIdValue = "snes",
                automaticArtUri = "file:///cover.png",
                heroArtUri = null,
            ),
        )
    }

    @Test
    fun heroDefaultNeverReusesCoverArtAsAnInventedAutomaticHero() {
        assertSame(
            HomeCustomEnrichDefaultArt.Unavailable,
            homeCustomEnrichDefaultArt(
                subject = HomeCustomEnrichSubject.GAME,
                kind = EnrichMediaKind.HERO,
                title = "Game",
                platformIdValue = "snes",
                automaticArtUri = "file:///cover.png",
            ),
        )
    }

    @Test
    fun aStateBuiltWithoutADefaultArtKeepsNoAutomaticMedia() {
        val state = newHomeCustomEnrichState(
            sessionId = "s",
            kind = EnrichMediaKind.ICON,
            cacheKey = "game-1",
            gameTitle = "Some Game",
            catalog = EnrichSavedCatalog(activeAssetId = null, entries = emptyList()),
        )
        assertSame(HomeCustomEnrichDefaultArt.Unavailable, state.defaultArt)
    }

    @Test
    fun defaultArtIsPresentationOnlyAndDoesNotAlterTheGalleryOrTheActiveChoice() {
        val base = newHomeCustomEnrichState(
            sessionId = "s",
            kind = EnrichMediaKind.ICON,
            cacheKey = "platform/n64",
            gameTitle = "Nintendo 64",
            catalog = EnrichSavedCatalog(activeAssetId = null, entries = emptyList()),
        )
        val badged = base.copy(defaultArt = HomeCustomEnrichDefaultArt.ConsoleLineArt("n64"))

        assertEquals(base.assets.map { it.stableId }, badged.assets.map { it.stableId })
        assertEquals("auto", badged.assets.first().stableId)
        assertTrue(homeCustomEnrichAssetIsActive(badged, HomeCustomEnrichAsset.Automatic))
        assertEquals("Default", HomeCustomEnrichAsset.Automatic.label)
        assertEquals("DEFAULT", homeCustomEnrichPreviewStatus(badged, HomeCustomEnrichAsset.Automatic))
    }
}
