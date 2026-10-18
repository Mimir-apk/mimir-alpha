package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The custom ᛗ mark's shared geometry (bigger on the live button, and the same on the picker's tiles): the ink
 * bounds of an alpha mask, the crop that trims a mask to them, and the reference numbers a coin of any size scales. Pure
 * arithmetic, so it is proved here once; `:core-companion-native` and `:feature-settings` each prove they read it.
 */
class MimirButtonMarkGeometryTest {

    private fun argb(alpha: Int) = alpha shl 24 or 0xFFFFFF

    /** A [width] x [height] mask, transparent except for the inclusive block [left]..[right] x [top]..[bottom]. */
    private fun blockMask(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): IntArray {
        val pixels = IntArray(width * height)
        for (y in top..bottom) for (x in left..right) pixels[y * width + x] = argb(255)
        return pixels
    }

    // ---- the ink bounds ------------------------------------------------------------------------------------------------

    @Test
    fun `a padded mask's ink bounds are the block of ink, inclusive`() {
        val pixels = blockMask(width = 20, height = 16, left = 5, top = 4, right = 9, bottom = 10)
        assertArrayEquals(intArrayOf(5, 4, 9, 10), mimirMarkInkBounds(pixels, 20, 16))
    }

    @Test
    fun `an all-transparent mask has no ink bounds, and faint noise at or under the floor is padding`() {
        val pixels = IntArray(8 * 6)
        assertNull(mimirMarkInkBounds(pixels, 8, 6))
        pixels[10] = argb(MimirMarkInkAlphaFloor)
        pixels[20] = argb(1)
        assertNull("alpha at or under the floor is not ink", mimirMarkInkBounds(pixels, 8, 6))
        pixels[30] = argb(MimirMarkInkAlphaFloor + 1)
        assertArrayEquals("one step over the floor is ink", intArrayOf(6, 3, 6, 3), mimirMarkInkBounds(pixels, 8, 6))
    }

    @Test
    fun `a one pixel mask has bounds when it is inked and none when it is not`() {
        assertArrayEquals(intArrayOf(0, 0, 0, 0), mimirMarkInkBounds(intArrayOf(argb(255)), 1, 1))
        assertNull(mimirMarkInkBounds(intArrayOf(0), 1, 1))
        assertNull("a zero-size mask has nothing to scan", mimirMarkInkBounds(IntArray(0), 0, 0))
    }

    // ---- the trim crop ----------------------------------------------------------------------------------------------------

    @Test
    fun `the crop is the ink plus the margin on each side, right and bottom exclusive`() {
        val ink = mimirMarkInkBounds(blockMask(20, 16, left = 5, top = 4, right = 9, bottom = 10), 20, 16)
        val crop = mimirMarkTrimCrop(ink, 20, 16)
        assertArrayEquals(intArrayOf(5 - MimirMarkTrimMarginPx, 4 - MimirMarkTrimMarginPx, 9 + 1 + MimirMarkTrimMarginPx, 10 + 1 + MimirMarkTrimMarginPx), crop)
        // 5 px of ink across and 7 down, 2 px of margin each side: a 9 x 11 crop.
        assertEquals(9, crop!![2] - crop[0])
        assertEquals(11, crop[3] - crop[1])
    }

    @Test
    fun `the margin never leaves the image, so ink at a corner crops tight to it`() {
        val atOrigin = mimirMarkInkBounds(blockMask(10, 10, left = 0, top = 0, right = 0, bottom = 0), 10, 10)
        assertArrayEquals(intArrayOf(0, 0, 1 + MimirMarkTrimMarginPx, 1 + MimirMarkTrimMarginPx), mimirMarkTrimCrop(atOrigin, 10, 10))
        val atFarCorner = mimirMarkInkBounds(blockMask(10, 10, left = 9, top = 9, right = 9, bottom = 9), 10, 10)
        assertArrayEquals(intArrayOf(9 - MimirMarkTrimMarginPx, 9 - MimirMarkTrimMarginPx, 10, 10), mimirMarkTrimCrop(atFarCorner, 10, 10))
    }

    @Test
    fun `a mask with no ink, a one pixel mask, and a mask already at its edges need no crop`() {
        assertNull("no ink: left as it came", mimirMarkTrimCrop(mimirMarkInkBounds(IntArray(8 * 6), 8, 6), 8, 6))
        assertNull("one inked pixel: the margin already covers the whole image", mimirMarkTrimCrop(mimirMarkInkBounds(intArrayOf(argb(255)), 1, 1), 1, 1))
        assertNull("ink to every edge", mimirMarkTrimCrop(mimirMarkInkBounds(IntArray(5 * 4) { argb(255) }, 5, 4), 5, 4))
        assertNull("ink within the margin of every edge", mimirMarkTrimCrop(mimirMarkInkBounds(blockMask(9, 9, 2, 2, 6, 6), 9, 9), 9, 9))
    }

    @Test
    fun `one inked pixel in a larger mask crops to the margin around it`() {
        val ink = mimirMarkInkBounds(blockMask(9, 9, 4, 4, 4, 4), 9, 9)
        val crop = mimirMarkTrimCrop(ink, 9, 9)
        assertNotNull(crop)
        assertArrayEquals(intArrayOf(2, 2, 7, 7), crop)
    }

    // ---- the reference numbers ------------------------------------------------------------------------------------------------

    @Test
    fun `the reference coin is the 56 dp button, the box 44 dp of it and the face 26_125 dp of radius`() {
        assertEquals(56f, MimirMarkReferenceCoinDp, 0f)
        assertEquals(44f, MimirMarkReferenceBoxDp, 0f)
        assertEquals(26.125f, MimirMarkReferenceFaceRadiusDp, 0f)
        assertEquals(44f / 56f, MimirMarkReferenceBoxDp / MimirMarkReferenceCoinDp, 0f)
        // The face is the circle the 1.25 dp rim leaves on the 28 dp radius: out to the rim's inner edge, less half its stroke.
        assertEquals(MimirMarkReferenceCoinDp / 2f - 1.25f - 1.25f / 2f, MimirMarkReferenceFaceRadiusDp, 0.0001f)
    }

    @Test
    fun `the box's edges stand inside the face and its corners do not, so the face clip is what bounds the mark`() {
        val halfBox = MimirMarkReferenceBoxDp / 2f
        assertTrue("edge $halfBox inside the face's $MimirMarkReferenceFaceRadiusDp", halfBox < MimirMarkReferenceFaceRadiusDp)
        assertTrue("the corner is past it", halfBox * Math.sqrt(2.0).toFloat() > MimirMarkReferenceFaceRadiusDp)
    }
}
