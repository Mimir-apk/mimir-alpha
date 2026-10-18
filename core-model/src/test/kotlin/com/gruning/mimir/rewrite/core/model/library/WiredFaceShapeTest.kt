package com.gruning.mimir.rewrite.core.model.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the shape of a shelf face travels with its uri. [WiredFace] carries a
 * [ShelfFaceShape], and `physical` stays as a derived property so a reader that has not learned the shape yet keeps
 * compiling and keeps painting the square lane for a disc.
 */
class WiredFaceShapeTest {

    @Test
    fun `the pre-shape constructor still maps a disc and a cover`() {
        assertEquals(ShelfFaceShape.PHYSICAL, WiredFace("file:///disc.png", physical = true).shape)
        assertEquals(ShelfFaceShape.LEDGE_COVER, WiredFace("file:///box.png", physical = false).shape)
        assertEquals(ShelfFaceShape.LEDGE_COVER, WiredFace("file:///box.png", false).shape)
    }

    @Test
    fun `physical is derived from the shape, and a square is painted in the square lane`() {
        assertTrue(WiredFace("u", ShelfFaceShape.PHYSICAL).physical)
        assertFalse(WiredFace("u", ShelfFaceShape.LEDGE_COVER).physical)
        // A reader that has not learned the shape must not paint a square tile at the platform's cover aspect.
        assertTrue(WiredFace("u", ShelfFaceShape.SQUARE).physical)
    }

    @Test
    fun `a wired face is never the shape NONE`() {
        val failure = runCatching { WiredFace("u", ShelfFaceShape.NONE) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `equality includes the shape, so a square and a box of the same file differ`() {
        assertFalse(WiredFace("u", ShelfFaceShape.SQUARE) == WiredFace("u", ShelfFaceShape.LEDGE_COVER))
        assertEquals(WiredFace("u", ShelfFaceShape.PHYSICAL), WiredFace("u", physical = true))
    }

    @Test
    fun `the seam is a SAM that is told whether a cover exists`() {
        val seen = mutableListOf<Pair<String, Boolean>>()
        val source = CoverOverrideSource { gameId, hasAutomaticCover ->
            seen += gameId to hasAutomaticCover
            if (hasAutomaticCover) null else WiredFace("file:///tile.png", ShelfFaceShape.SQUARE)
        }

        assertNull(source.shelfFaceOverrideFor("g", hasAutomaticCover = true))
        assertEquals(ShelfFaceShape.SQUARE, source.shelfFaceOverrideFor("g", hasAutomaticCover = false)?.shape)
        assertEquals(listOf("g" to true, "g" to false), seen)
    }
}
