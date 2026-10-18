package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shelf-face ladder, tested without a device.
 *
 * One shelf art type ("Icon art") accepts any shape and the tile follows the pick. Existing picks
 * always beat automatic art. The first-boot tie-break case cannot be repeated on a device once it
 * has happened, so it is tested here as pure data.
 */
class ShelfFaceTiersTest {

    private val cover = "file:///media/Game.png"
    private val coverPick = "file:///files/custom-cover-art/game.png"
    private val articlePick = "file:///files/tome-cartridge-art/game.png"
    private val tile = "file:///files/sgdb2/game.png#v=1"
    private val portrait = "file:///files/sgdb2/game-portrait.png#v=2"
    private val support = "file:///media/Game-support.png"
    private val logo = "file:///media/Game-logo.png"

    private fun tiers(inputs: ShelfFaceInputs) = shelfFaceTiers(inputs)

    private fun assertFace(
        expectedUri: String?,
        expectedShape: ShelfFaceShape,
        expectedTier: ShelfFaceTier,
        actual: ResolvedShelfFace,
    ) {
        assertEquals(ShelfFace(expectedUri, expectedShape), actual.face)
        assertEquals(expectedTier, actual.tier)
    }

    // -- L1: his pick, by recorded kind, with today's tie-break when nothing is recorded -----------------

    @Test
    fun `no record and an owner-picked icon is the square, even over a cover pick (today's Home precedence)`() {
        val r = tiers(
            ShelfFaceInputs(
                coverPickUri = coverPick,
                physicalPickUri = articlePick,
                squareTileUri = tile,
                squareTileIsOwnersPick = true,
                hasAutomaticCover = true,
                automaticCoverUri = cover,
            ),
        )
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, r)
    }

    @Test
    fun `no record and legacy cover plus article picks resolve to the cover once`() {
        val r = tiers(ShelfFaceInputs(coverPickUri = coverPick, physicalPickUri = articlePick))
        assertFace(coverPick, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.PICK, r)
    }

    @Test
    fun `a legacy article pick alone is the physical face`() {
        val r = tiers(ShelfFaceInputs(physicalPickUri = articlePick, hasAutomaticCover = true, automaticCoverUri = cover))
        assertFace(articlePick, ShelfFaceShape.PHYSICAL, ShelfFaceTier.PICK, r)
    }

    @Test
    fun `a pick beats the automatic cover`() {
        val r = tiers(ShelfFaceInputs(coverPickUri = coverPick, hasAutomaticCover = true, automaticCoverUri = cover))
        assertFace(coverPick, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.PICK, r)
    }

    /**
     * The C4 gap the old Home stamp had: its cover guard read the AUTOMATIC cover only, so a cover he had picked
     * with no automatic cover beside it lost to an automatic square. His pick is above that tier now.
     */
    @Test
    fun `a cover pick beats an automatic square when there is no automatic cover`() {
        val r = tiers(ShelfFaceInputs(coverPickUri = coverPick, squareTileUri = tile, squareTileIsOwnersPick = false))
        assertFace(coverPick, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.PICK, r)
    }

    @Test
    fun `an owner-picked icon beats the automatic cover (CP06)`() {
        val r = tiers(
            ShelfFaceInputs(
                squareTileUri = tile,
                squareTileIsOwnersPick = true,
                hasAutomaticCover = true,
                automaticCoverUri = cover,
            ),
        )
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, r)
    }

    /**
     * His last choice is the NEWEST of his picks. A cover pick writes a record and an icon pick writes none, so the
     * record names only the last FACE pick; the times of the picks decide whether an icon came after it.
     */
    @Test
    fun `the newest pick wins, whatever the record says`() {
        val both = ShelfFaceInputs(
            recorded = WiredFaceKind.COVER,
            coverPickUri = coverPick,
            squareTileUri = tile,
            squareTileIsOwnersPick = true,
        )
        assertFace(
            tile,
            ShelfFaceShape.SQUARE,
            ShelfFaceTier.PICK,
            tiers(both.copy(squarePickedAtMillis = 2_000L, coverPickedAtMillis = 1_000L)),
        )
        assertFace(
            coverPick,
            ShelfFaceShape.LEDGE_COVER,
            ShelfFaceTier.PICK,
            tiers(both.copy(squarePickedAtMillis = 1_000L, coverPickedAtMillis = 2_000L)),
        )
        // Without a recorded kind, the same two orders.
        assertFace(
            coverPick,
            ShelfFaceShape.LEDGE_COVER,
            ShelfFaceTier.PICK,
            tiers(both.copy(recorded = null, squarePickedAtMillis = 1_000L, coverPickedAtMillis = 2_000L)),
        )
        assertFace(
            tile,
            ShelfFaceShape.SQUARE,
            ShelfFaceTier.PICK,
            tiers(both.copy(recorded = null, squarePickedAtMillis = 2_000L, coverPickedAtMillis = 1_000L)),
        )
    }

    /**
     * The icon is compared with the pick that would be SHOWN, not with the newest face pick: a newer article marker must not
     * let an older recorded cover stand against an icon picked after that cover (the cover and the article carry their own times).
     */
    @Test
    fun `a newer article marker does not let an older recorded cover beat a newer icon`() {
        val inputs = ShelfFaceInputs(
            recorded = WiredFaceKind.COVER,
            coverPickUri = coverPick,
            physicalPickUri = articlePick,
            squareTileUri = tile,
            squareTileIsOwnersPick = true,
            coverPickedAtMillis = 1_000L,
            squarePickedAtMillis = 2_000L,
            physicalPickedAtMillis = 3_000L,
        )
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, tiers(inputs))
        // The same marker times with the cover the newest: the cover that would be shown is newer than the icon, so it stands.
        assertFace(
            coverPick,
            ShelfFaceShape.LEDGE_COVER,
            ShelfFaceTier.PICK,
            tiers(inputs.copy(coverPickedAtMillis = 3_000L, physicalPickedAtMillis = 1_000L)),
        )
    }

    @Test
    fun `a recorded article is compared by its own time, not the cover's`() {
        val inputs = ShelfFaceInputs(
            recorded = WiredFaceKind.PHYSICAL,
            coverPickUri = coverPick,
            physicalPickUri = articlePick,
            squareTileUri = tile,
            squareTileIsOwnersPick = true,
            physicalPickedAtMillis = 1_000L,
            squarePickedAtMillis = 2_000L,
            coverPickedAtMillis = 3_000L,
        )
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, tiers(inputs))
        assertFace(
            articlePick,
            ShelfFaceShape.PHYSICAL,
            ShelfFaceTier.PICK,
            tiers(inputs.copy(physicalPickedAtMillis = 3_000L, coverPickedAtMillis = 1_000L)),
        )
    }

    @Test
    fun `with no record the pick the tie-break would show is the one the icon is compared with`() {
        // Both picks held, nothing recorded: the cover is what would show (C5-F3), so its time decides.
        val both = ShelfFaceInputs(
            coverPickUri = coverPick,
            physicalPickUri = articlePick,
            squareTileUri = tile,
            squareTileIsOwnersPick = true,
            squarePickedAtMillis = 2_000L,
        )
        assertFace(
            tile,
            ShelfFaceShape.SQUARE,
            ShelfFaceTier.PICK,
            tiers(both.copy(coverPickedAtMillis = 1_000L, physicalPickedAtMillis = 3_000L)),
        )
        assertFace(
            coverPick,
            ShelfFaceShape.LEDGE_COVER,
            ShelfFaceTier.PICK,
            tiers(both.copy(coverPickedAtMillis = 3_000L, physicalPickedAtMillis = 1_000L)),
        )
        // An article alone is compared by its own time.
        val articleOnly = both.copy(coverPickUri = null)
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, tiers(articleOnly.copy(physicalPickedAtMillis = 1_000L)))
        assertFace(articlePick, ShelfFaceShape.PHYSICAL, ShelfFaceTier.PICK, tiers(articleOnly.copy(physicalPickedAtMillis = 3_000L)))
    }

    @Test
    fun `with no times the icon wins over a recorded cover, as Home always did`() {
        val both = ShelfFaceInputs(
            recorded = WiredFaceKind.COVER,
            coverPickUri = coverPick,
            squareTileUri = tile,
            squareTileIsOwnersPick = true,
        )
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, tiers(both))
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, tiers(both.copy(squarePickedAtMillis = 1_000L)))
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, tiers(both.copy(coverPickedAtMillis = 1_000L)))
    }

    @Test
    fun `a recorded square is his last choice outright`() {
        val inputs = ShelfFaceInputs(
            recorded = WiredFaceKind.SQUARE,
            coverPickUri = coverPick,
            squareTileUri = tile,
            squareTileIsOwnersPick = true,
            squarePickedAtMillis = 1_000L,
            coverPickedAtMillis = 2_000L,
        )
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, tiers(inputs))
    }

    @Test
    fun `a recorded square names the tile even when the selection marker is missing, and replaces older picks`() {
        val r = tiers(
            ShelfFaceInputs(
                recorded = WiredFaceKind.SQUARE,
                coverPickUri = coverPick,
                squareTileUri = tile,
                squareTileIsOwnersPick = false,
            ),
        )
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, r)
    }

    @Test
    fun `a recorded kind decides the shape and the file is looked up where it is`() {
        // The face picker offers boxes and the article in one grid and saves both through the cover lane: a disc he
        // picked there is a PHYSICAL record whose file is in the cover store.
        val disc = tiers(ShelfFaceInputs(recorded = WiredFaceKind.PHYSICAL, coverPickUri = coverPick))
        assertFace(coverPick, ShelfFaceShape.PHYSICAL, ShelfFaceTier.PICK, disc)
        val box = tiers(ShelfFaceInputs(recorded = WiredFaceKind.COVER, physicalPickUri = articlePick))
        assertFace(articlePick, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.PICK, box)
    }

    @Test
    fun `a recorded square whose tile is gone falls through to the older picks he still holds`() {
        val r = tiers(ShelfFaceInputs(recorded = WiredFaceKind.SQUARE, coverPickUri = coverPick))
        assertFace(coverPick, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.PICK, r)
    }

    @Test
    fun `a recorded kind with no file falls through to his square rather than past it`() {
        val r = tiers(
            ShelfFaceInputs(
                recorded = WiredFaceKind.COVER,
                squareTileUri = tile,
                squareTileIsOwnersPick = true,
                hasAutomaticCover = true,
                automaticCoverUri = cover,
            ),
        )
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, r)
    }

    @Test
    fun `blank uris are absent, not picks`() {
        val r = tiers(
            ShelfFaceInputs(
                coverPickUri = "  ",
                physicalPickUri = "",
                squareTileUri = " ",
                squareTileIsOwnersPick = true,
                hasAutomaticCover = true,
                automaticCoverUri = cover,
            ),
        )
        assertFace(cover, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.AUTOMATIC_COVER, r)
    }

    // -- L2 / L3: the automatic tiers -------------------------------------------------------------------

    @Test
    fun `an automatic square loses to an automatic cover`() {
        val r = tiers(
            ShelfFaceInputs(
                squareTileUri = tile,
                squareTileIsOwnersPick = false,
                portraitTileUri = null,
                hasAutomaticCover = true,
                automaticCoverUri = cover,
            ),
        )
        assertFace(cover, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.AUTOMATIC_COVER, r)
    }

    @Test
    fun `an automatic square stands in when there is no cover, on any platform`() {
        val r = tiers(ShelfFaceInputs(squareTileUri = tile))
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.AUTOMATIC_TILE, r)
    }

    @Test
    fun `pc_games prefers the portrait sibling to the square`() {
        val both = tiers(ShelfFaceInputs(squareTileUri = tile, portraitTileUri = portrait))
        assertFace(portrait, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.AUTOMATIC_TILE, both)
        val squareOnly = tiers(ShelfFaceInputs(squareTileUri = tile))
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.AUTOMATIC_TILE, squareOnly)
    }

    @Test
    fun `a cover he placed himself outranks the automatic portrait`() {
        val r = tiers(
            ShelfFaceInputs(
                portraitTileUri = portrait,
                squareTileUri = tile,
                hasAutomaticCover = true,
                automaticCoverUri = cover,
            ),
        )
        assertFace(cover, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.AUTOMATIC_COVER, r)
    }

    /** The seam passes no uri; a cover that exists is reported as its tier and not as a "wired" face. */
    @Test
    fun `a real cover with no uri supplied is its tier and no face`() {
        val r = tiers(ShelfFaceInputs(hasAutomaticCover = true, squareTileUri = tile))
        assertFace(null, ShelfFaceShape.NONE, ShelfFaceTier.AUTOMATIC_COVER, r)
    }

    @Test
    fun `a stand-in that is not a real cover yields to a tile, and shows when there is none`() {
        // pc_games logo / Android launcher icon: the row's own art, but `hasAutomaticCover` is false.
        val withTile = tiers(ShelfFaceInputs(automaticCoverUri = logo, squareTileUri = tile))
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.AUTOMATIC_TILE, withTile)
        val bare = tiers(ShelfFaceInputs(automaticCoverUri = logo))
        assertFace(logo, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.STAND_IN, bare)
    }

    // -- L4 / L5 ----------------------------------------------------------------------------------------

    @Test
    fun `the provider support render is the last picture and is physical`() {
        val r = tiers(ShelfFaceInputs(supportUri = support))
        assertFace(support, ShelfFaceShape.PHYSICAL, ShelfFaceTier.SUPPORT, r)
        // ... and it outranks nothing.
        assertFace(
            cover,
            ShelfFaceShape.LEDGE_COVER,
            ShelfFaceTier.AUTOMATIC_COVER,
            tiers(ShelfFaceInputs(supportUri = support, hasAutomaticCover = true, automaticCoverUri = cover)),
        )
        assertFace(
            tile,
            ShelfFaceShape.SQUARE,
            ShelfFaceTier.AUTOMATIC_TILE,
            tiers(ShelfFaceInputs(supportUri = support, squareTileUri = tile)),
        )
    }

    @Test
    fun `nothing at all is nothing`() {
        val r = tiers(ShelfFaceInputs())
        assertEquals(ShelfFace.None, r.face)
        assertEquals(ShelfFaceTier.NONE, r.tier)
    }

    // -- the launch card's reading: no square anywhere ---------------------------------------------------

    private fun card(inputs: ShelfFaceInputs) = shelfFaceTiers(inputs.copy(supportUri = null), allowSquare = false)

    @Test
    fun `the card never wears a square, and a cover pick under a square pick still reaches it`() {
        val inputs = ShelfFaceInputs(
            coverPickUri = coverPick,
            squareTileUri = tile,
            squareTileIsOwnersPick = true,
            hasAutomaticCover = true,
            automaticCoverUri = cover,
        )
        assertFace(tile, ShelfFaceShape.SQUARE, ShelfFaceTier.PICK, tiers(inputs))
        assertFace(coverPick, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.PICK, card(inputs))
    }

    @Test
    fun `a game whose last choice was a square gives the card its automatic cover, not the older pick`() {
        val inputs = ShelfFaceInputs(
            recorded = WiredFaceKind.SQUARE,
            coverPickUri = coverPick,
            squareTileUri = tile,
            hasAutomaticCover = true,
            automaticCoverUri = cover,
        )
        assertFace(cover, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.AUTOMATIC_COVER, card(inputs))
    }

    @Test
    fun `a recorded square whose tile is gone leaves the card on the same older pick as the shelf`() {
        val inputs = ShelfFaceInputs(
            recorded = WiredFaceKind.SQUARE,
            coverPickUri = coverPick,
            hasAutomaticCover = true,
            automaticCoverUri = cover,
        )
        assertFace(coverPick, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.PICK, tiers(inputs))
        assertFace(coverPick, ShelfFaceShape.LEDGE_COVER, ShelfFaceTier.PICK, card(inputs))
    }

    @Test
    fun `a square alone gives the card nothing, and the automatic portrait still reaches it`() {
        assertEquals(ShelfFace.None, card(ShelfFaceInputs(squareTileUri = tile, squareTileIsOwnersPick = true)).face)
        assertFace(
            portrait,
            ShelfFaceShape.LEDGE_COVER,
            ShelfFaceTier.AUTOMATIC_TILE,
            card(ShelfFaceInputs(squareTileUri = tile, portraitTileUri = portrait)),
        )
    }

    @Test
    fun `no shape is NONE for a face that has a uri`() {
        val shapes = listOf(
            tiers(ShelfFaceInputs(coverPickUri = coverPick)),
            tiers(ShelfFaceInputs(squareTileUri = tile)),
            tiers(ShelfFaceInputs(supportUri = support)),
            tiers(ShelfFaceInputs(automaticCoverUri = logo)),
        )
        shapes.forEach { r ->
            assertEquals(false, r.face.uri == null)
            assertEquals(false, r.face.shape == ShelfFaceShape.NONE)
        }
        assertNull(tiers(ShelfFaceInputs()).face.uri)
    }
}
