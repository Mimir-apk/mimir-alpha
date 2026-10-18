package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A local flat logo is backdrop-and-detail art; no gamelist view lays itself out by it. The shelf's seats are Home's and square, so the
 * lane tests that stood here (the wide artwork lane, the header breathing envelope) went with the cards that used them.
 */
class GamelistArtworkSelectionTest {

    @Test
    fun localFlatLogoIsRecognisedOnlyWhenItIsLocal() {
        val boxOnly = gameRow(
            id = "gba/box",
            artUri = "file:///games/box.png",
            flatArtUri = null,
        )
        val flatLogo = gameRow(
            id = "gba/logo",
            artUri = "file:///games/cover.png",
            flatArtUri = "file:///games/logo.png",
        )

        assertFalse(usesGamelistFlatLogo(boxOnly))
        assertTrue(usesGamelistFlatLogo(flatLogo))
    }

    @Test
    fun blankFlatLogoFailsClosedToPortraitBox() {
        val game = gameRow(
            id = "gba/blank-logo",
            artUri = "file:///games/cover.png",
            flatArtUri = "   ",
        )

        assertFalse(usesGamelistFlatLogo(game))
    }

    @Test
    fun remoteFlatLogoFailsClosedToLocalPortraitBox() {
        val game = gameRow(
            id = "gba/remote-logo",
            artUri = "file:///games/cover.png",
            flatArtUri = "https://example.com/logo.png",
        )

        assertFalse(usesGamelistFlatLogo(game))
    }

    private fun gameRow(
        id: String,
        artUri: String?,
        flatArtUri: String?,
    ) = GameRow(
        gameId = GameId(id),
        title = id.substringAfter('/'),
        platformId = PlatformId.GBA,
        memberCount = 1,
        duplicateKind = null,
        artUri = artUri,
        flatArtUri = flatArtUri,
    )
}
