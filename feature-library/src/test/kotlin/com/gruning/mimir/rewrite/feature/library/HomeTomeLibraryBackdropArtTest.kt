package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Tome collection's background uses the game's HERO art, excluding shelf cover and icon art.
 * [homeTomeLibraryBackdropArt] is the one selector the library paints and reports from; it reads
 * [HomeTomePreview.heroArtUri] -- the hero slot -- and nothing else.
 */
class HomeTomeLibraryBackdropArtTest {
    private val cover = "file:///media/chrono-cover.png"
    private val hero = "file:///media_art/chrono-hero.png#mv=3"

    private fun preview(
        heroArtUri: String? = null,
        backdropArtUri: String? = cover,
        artUri: String = cover,
        isPhysicalMedia: Boolean = false,
    ) = HomeTomePreview(
        artUri = artUri,
        platformId = PlatformId("snes"),
        shape = if (isPhysicalMedia) ShelfFaceShape.PHYSICAL else ShelfFaceShape.LEDGE_COVER,
        backdropArtUri = backdropArtUri,
        heroArtUri = heroArtUri,
    )

    @Test
    fun theHeroSlotIsTheBackground() {
        assertEquals(hero, homeTomeLibraryBackdropArt(preview(heroArtUri = hero)))
    }

    @Test
    fun aGameWithoutAHeroGetsTheThemeBackdropNotItsCover() {
        // null is the theme's own floor (VideoBackdrop paints signatureBottom for no art).
        assertNull(homeTomeLibraryBackdropArt(preview(heroArtUri = null)))
    }

    @Test
    fun theCoverTheTileIconAndPhysicalMediaNeverReachIt() {
        // The ladder the shelf's stage reads (backdropArtUri = hero, then the cover/icon) is unchanged and is NOT this.
        assertNull(homeTomeLibraryBackdropArt(preview(backdropArtUri = cover, artUri = cover)))
        assertNull(
            homeTomeLibraryBackdropArt(
                preview(artUri = "file:///tile-icons/chrono.png", backdropArtUri = null, isPhysicalMedia = false),
            ),
        )
        assertNull(
            homeTomeLibraryBackdropArt(
                preview(artUri = "file:///tome-cartridge-art/chrono.png", backdropArtUri = null, isPhysicalMedia = true),
            ),
        )
    }

    @Test
    fun theHeroWinsEvenWhenTheCoverIsTheBackdropRungOrPhysicalMedia() {
        assertEquals(hero, homeTomeLibraryBackdropArt(preview(heroArtUri = hero, backdropArtUri = cover)))
        assertEquals(hero, homeTomeLibraryBackdropArt(preview(heroArtUri = hero, isPhysicalMedia = true)))
    }

    @Test
    fun onlyAnAlreadyLocalHeroIsPainted() {
        assertNull(homeTomeLibraryBackdropArt(preview(heroArtUri = "https://example.test/hero.png")))
        assertEquals(
            "content://media/hero.png",
            homeTomeLibraryBackdropArt(preview(heroArtUri = "content://media/hero.png")),
        )
    }

    @Test
    fun noPreviewIsNoArt() {
        assertNull(homeTomeLibraryBackdropArt(null))
    }
}
