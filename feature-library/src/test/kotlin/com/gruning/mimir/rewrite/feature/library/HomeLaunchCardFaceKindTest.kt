package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch card's face and the face's KIND are two facts, and the card needs both.
 *
 * Until C1 they were one: `cartridgeArtUri` was non-null only when the card was meant to paint a disc, so
 * "is it a disc" could be read off "is it null". FL-2 then made that same field carry the face the shelf
 * resolved — which is usually a COVER. The encoding did not survive the change of meaning: a 2D box
 * arriving through the inherit path was still classified as physical media, and
 * [homeLaunchCardRenderedArtSize] paints physical media at [LibraryPhysicalMediaCoverAspect] — 1f, square.
 * A tall box squared off.
 *
 * These pin the two halves that a "just AND the flag" fix gets wrong in opposite directions.
 */
class HomeLaunchCardFaceKindTest {

    private fun state(uri: String?, physical: Boolean) = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("dirge")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Dirge of Cerberus",
            artworkUri = "file:///shelf/dirge-box.png",
            supportingLabel = "ps2",
            platformId = PlatformId("ps2"),
        ),
        platformLabel = "PlayStation 2",
        coverArtUri = "file:///covers/dirge-box.png",
        cartridgeArtUri = uri,
        cartridgeArtIsPhysical = physical,
    )

    @Test
    fun `a cover inherited from the shelf is not classified as physical media`() {
        val inheritedCover = state("file:///shelf/dirge-box.png", physical = false)
        assertFalse(
            "the shelf's resolved face is usually a box; classifying it physical squares it off at 1f",
            inheritedCover.physicalMediaCover,
        )
        assertEquals(
            "and the geometry must follow the platform's box aspect, not the disc aspect",
            homeLaunchCardRenderedArtSize(
                emulated = true,
                platformId = "ps2",
                maxWidth = 400.dp,
                maxHeight = 600.dp,
                physicalMedia = false,
            ),
            homeLaunchCardRenderedArtSize(
                emulated = true,
                platformId = "ps2",
                maxWidth = 400.dp,
                maxHeight = 600.dp,
                physicalMedia = inheritedCover.physicalMediaCover,
            ),
        )
    }

    @Test
    fun `a disc is still classified as physical media`() {
        assertTrue(
            "the kind must still be able to say yes -- a flag that is always false is not a fix",
            state("file:///art/dirge-support.png", physical = true).physicalMediaCover,
        )
    }

    /**
     * The opposite error, and the one a naive fix walks into: gating the ART on the kind as well as the
     * geometry. `artRef` used to read `cartridgeArtUri?.takeIf { physicalMediaCover }`, which was harmless
     * while the two meant the same thing. Once the kind is real, that `takeIf` DROPS a cover-kind face and
     * silently reverts a hand-picked cover to the provider box — a C4 violation ("a pick replaces state
     * media regardless of the switch").
     *
     * The face wins kind-agnostically. The kind drives geometry only.
     */
    @Test
    fun `a cover-kind face still paints, it only changes the geometry`() {
        val picked = state("file:///picks/dirge-cover.png", physical = false)
        assertEquals(
            "his pick is the face whatever kind it is",
            "file:///picks/dirge-cover.png",
            homeLaunchCardArtRef(picked),
        )
        assertEquals(
            "and with no face at all the card falls back to the cover, as before",
            "file:///covers/dirge-box.png",
            homeLaunchCardArtRef(state(null, physical = false)),
        )
        assertEquals(
            "a blank face is absent, not a face",
            "file:///covers/dirge-box.png",
            homeLaunchCardArtRef(state("   ", physical = false)),
        )
    }
}
