package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.model.library.WiredFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Policy 2026-10-10 (one shelf face) moved a `pc_games` game's automatic SteamGridDB portrait out of
 * `GameRow.artUri` and into `GameRow.wiredFace`. These are the readers that still asked `artUri` alone: the "No cover"
 * pill, the Enrich offer, the detail art and the launch card. Each follows the cover the game actually has.
 */
class GameRowCoverArtTest {

    private val portrait = "file:///files/sgdb2/pc-portrait.png#v=2"
    private val square = "file:///files/sgdb2/pc.png#v=1"
    private val logo = "file:///games/media_art/Game-logo.png"
    private val cover = "file:///games/media_art/Game.png"

    private fun game(
        platform: PlatformId = PlatformId.PC_GAMES,
        artUri: String? = null,
        wired: WiredFace? = null,
        logoUri: String? = null,
        verified: Boolean = true,
    ) = GameRow(
        gameId = GameId("${platform.value}/game"),
        title = "Game",
        platformId = platform,
        memberCount = 1,
        duplicateKind = null,
        artUri = artUri,
        wiredFace = wired,
        flatArtUri = logoUri,
        description = "A synopsis".takeIf { verified },
        descriptionSource = "steamgriddb".takeIf { verified },
    )

    // -- the cover a game has --------------------------------------------------------------------------

    @Test
    fun `the automatic cover is the cover, whatever the wired face is`() {
        val row = game(artUri = cover, wired = WiredFace(portrait, ShelfFaceShape.LEDGE_COVER))
        assertEquals(cover, row.shelfCoverArtUri())
    }

    @Test
    fun `a cover or disc shaped wired face is the cover when there is no automatic one`() {
        assertEquals(portrait, game(wired = WiredFace(portrait, ShelfFaceShape.LEDGE_COVER)).shelfCoverArtUri())
        assertEquals(portrait, game(wired = WiredFace(portrait, ShelfFaceShape.PHYSICAL)).shelfCoverArtUri())
        assertEquals(
            "a blank automatic cover is absent, not a cover",
            portrait,
            game(artUri = "  ", wired = WiredFace(portrait, ShelfFaceShape.LEDGE_COVER)).shelfCoverArtUri(),
        )
    }

    @Test
    fun `a square is the Icon-art tile and never a cover`() {
        assertNull(game(wired = WiredFace(square, ShelfFaceShape.SQUARE)).shelfCoverArtUri())
        assertNull(game().shelfCoverArtUri())
    }

    // -- the Enrich offer and the "No cover" pill --------------------------------------------------------

    @Test
    fun `a pc game with only its portrait is not told it has no cover, and is not offered Enrich again`() {
        val withPortrait = game(wired = WiredFace(portrait, ShelfFaceShape.LEDGE_COVER), logoUri = logo)
        assertEquals(emptyList<String>(), withPortrait.providerScrapeGapPills())
        assertEquals(ProviderEnrichmentState.VERIFIED_COMPLETE, withPortrait.providerEnrichmentState())
        assertEquals(false, withPortrait.providerEnrichmentState().shouldOfferEnrich)
    }

    /** The differential: without the portrait the same game IS missing a cover, so the test above cannot pass by accident. */
    @Test
    fun `the same game without any cover still says so`() {
        val bare = game(logoUri = logo)
        assertEquals(listOf("No cover"), bare.providerScrapeGapPills())
        assertEquals(ProviderEnrichmentState.VERIFIED_PARTIAL, bare.providerEnrichmentState())
        val squareOnly = game(wired = WiredFace(square, ShelfFaceShape.SQUARE), logoUri = logo)
        assertEquals(listOf("No cover"), squareOnly.providerScrapeGapPills())
    }

    // -- the Library launch card reads the cover the way Home's card does -----------------------------------

    private fun cardCover(game: GameRow): String? = libraryLaunchCardUiState(
        target = LibraryDetailTarget.Emulated(game),
        metadata = LibraryDetailMetadata(),
        achievementProgress = null,
        statusLine = null,
        screenScraperConnected = false,
    ).coverArtUri

    @Test
    fun `the launch card of a pc game with only a portrait shows the portrait`() {
        assertEquals(portrait, cardCover(game(wired = WiredFace(portrait, ShelfFaceShape.LEDGE_COVER), logoUri = logo)))
    }

    /** Home's card falls back to the clear logo for `pc_games` (homeShelfCoverUri); the Library's must say the same. */
    @Test
    fun `the launch card of a pc game with only a square falls back to its logo, as Home's card does`() {
        assertEquals(logo, cardCover(game(wired = WiredFace(square, ShelfFaceShape.SQUARE), logoUri = logo)))
        assertEquals(logo, cardCover(game(logoUri = logo)))
    }

    @Test
    fun `other platforms have no logo fallback, and a real cover is never displaced`() {
        assertNull(cardCover(game(platform = PlatformId.SNES, wired = WiredFace(square, ShelfFaceShape.SQUARE), logoUri = logo)))
        assertEquals(cover, cardCover(game(platform = PlatformId.SNES, artUri = cover, logoUri = logo)))
        assertEquals(cover, cardCover(game(artUri = cover, wired = WiredFace(portrait, ShelfFaceShape.LEDGE_COVER), logoUri = logo)))
    }

    @Test
    fun `the card row carries the same cover Home's row does`() {
        val ui = libraryLaunchCardUiState(
            target = LibraryDetailTarget.Emulated(game(wired = WiredFace(square, ShelfFaceShape.SQUARE), logoUri = logo)),
            metadata = LibraryDetailMetadata(),
            achievementProgress = null,
            statusLine = null,
            screenScraperConnected = false,
        )
        assertEquals(logo, ui.row.artworkUri)
        assertEquals(homeShelfCoverUri(PlatformId.PC_GAMES, null, logo), ui.coverArtUri)
    }
}
