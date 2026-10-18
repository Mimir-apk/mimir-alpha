package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTomePhysicalMediaProjectionTest {
    @Test
    fun `provider support metadata never becomes an implicit Tome override`() {
        val preview = row(
            artworkUri = "file:///media/Zelda.png",
            physicalMediaUri = "file:///media/Zelda-support.png",
        ).toHomeTomePreviewOrNull()

        assertEquals("file:///media/Zelda.png", preview?.artUri)
        assertFalse(preview?.isPhysicalMedia == true)
    }

    @Test
    fun `Tome preview falls back honestly to cover when support art is unavailable`() {
        val preview = row(artworkUri = "file:///media/Zelda.png").toHomeTomePreviewOrNull()

        assertEquals("file:///media/Zelda.png", preview?.artUri)
        assertFalse(preview?.isPhysicalMedia == true)
    }

    @Test
    /**
     * RESTATED 2026-08-25 under C4. This asserted that the switch REVERSES a hand pick -- switch off, the
     * cover comes back -- and that is exactly what C4 forbids: an explicit pick replaces the current state regardless of the Cartridge Hero switch.
     * A face the player picked is never overruled by a default.
     *
     * What "reversible without deleting selection" was protecting is unchanged and still asserted below:
     * the pick's FILE is never deleted, so Automatic in the picker still restores the cover. What moved is
     * that a switch may no longer do it silently.
     */
    fun `an explicit cartridge pick wins with no switch to help it`() {
        val selected = "file:///files/tome-cartridge-art/zelda.png"

        val source = row(artworkUri = "file:///media/Zelda.png")
        val picked = source.toHomeTomePreviewOrNull(selected)

        // C4, and a STRONGER pin than the two-position version it replaces: there is no switch left to
        // agree with the pick, so the pick has to win on its own.
        assertEquals(selected, picked?.artUri)
        assertTrue(picked?.isPhysicalMedia == true)
        assertEquals("file:///media/Zelda.png", picked?.backdropArtUri)

        // No pick at all: the provider's downloaded render was the FL-7 tier the switch could select. The
        // switch is gone  and with it that tier, so the automatic answer is the
        // cover -- which is what the switch's shipped OFF position already gave.
        val downloaded = "file:///roms/zelda-support.png"
        val enriched = row(artworkUri = "file:///media/Zelda.png", physicalMediaUri = downloaded)
        assertEquals(
            "file:///media/Zelda.png",
            enriched.toHomeTomePreviewOrNull(null, downloaded)?.artUri,
        )
    }

    @Test
    fun `a Tome's backdrop is the game's hero art, and the cover only without one`() {
        // A Tome displays hero art rather than the game cover.
        val cover = "file:///media/Zelda.png"
        val downloaded = "file:///roms/gba/media_art/zelda-hero.png#mv=1"
        val withHero = row(artworkUri = cover).copy(heroArtUri = downloaded)
        assertEquals(downloaded, withHero.toHomeTomePreviewOrNull(null)?.backdropArtUri)
        val chosen = "file:///files/enrich/hero/zelda.png"
        assertEquals(
            "a hero picked in Customize media still wins",
            chosen,
            withHero.copy(chosenHeroUri = chosen).toHomeTomePreviewOrNull(null)?.backdropArtUri,
        )
        assertEquals("no hero at all: the cover", cover, row(artworkUri = cover).toHomeTomePreviewOrNull(null)?.backdropArtUri)
    }

    @Test
    fun `an opened Tome's focused game projects its hero art the same way`() {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
        ).first(File::isFile).readText()
        assertTrue(
            source.contains("backdropArtUri = resolvedRow.chosenHeroUri ?: resolvedRow.heroArtUri ?: resolvedRow.artworkUri,"),
        )
    }

    private fun row(
        artworkUri: String?,
        physicalMediaUri: String? = null,
    ) = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId("gba/zelda")),
        rank = 0L,
        addedAtMillis = 0L,
        title = "The Legend of Zelda",
        platformId = PlatformId.GBA,
        artworkUri = artworkUri,
        physicalMediaUri = physicalMediaUri,
    )
}
