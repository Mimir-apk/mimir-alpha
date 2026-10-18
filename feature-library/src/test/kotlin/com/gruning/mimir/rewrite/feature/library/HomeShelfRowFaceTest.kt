package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A Home row carries the face it paints, shape included. The shelf reads only that: no field it is derived from decides the
 * shape (`legacyShelfFace`, which did, is deleted).
 */
class HomeShelfRowFaceTest {

    private fun row(artworkUri: String? = null, tileIconUri: String? = null, faceUri: String? = null, face: ShelfFace? = null) =
        HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("g")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Game",
            artworkUri = artworkUri,
            tileIconUri = tileIconUri,
            faceUri = faceUri,
        ).let { built -> if (face == null) built else built.copy(face = face) }

    @Test
    fun `a row built without the resolver paints its own art as a ledge cover`() {
        assertEquals(ShelfFace("file:///art.png", ShelfFaceShape.LEDGE_COVER), row(artworkUri = "file:///art.png").face)
        assertSame(ShelfFace.None, row().face)
        assertSame(ShelfFace.None, row(artworkUri = "  ").face)
    }

    @Test
    fun `the shape is never derived from which field is set`() {
        // A tile icon and a face uri on the row do not make its face a square or a disc: the resolver passes the face.
        val row = row(artworkUri = "file:///art.png", tileIconUri = "file:///tile.png", faceUri = "file:///face.png")
        assertEquals(ShelfFace("file:///art.png", ShelfFaceShape.LEDGE_COVER), row.face)
    }

    @Test
    fun `an explicit face is the face, and a copy that changes another field keeps it`() {
        val chosen = ShelfFace("file:///box.png", ShelfFaceShape.PHYSICAL)
        val row = row(artworkUri = "file:///art.png", face = chosen)
        assertEquals(chosen, row.face)
        assertEquals(chosen, row.copy(tileIconUri = null, genre = "RPG").face)
    }
}
