package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * The difference was measured, not guessed: both are Box (3D) renders, and the GameCube file carries far
 * more transparent canvas. These pin the two pieces of arithmetic that turn that into a seat.
 */
class MimirArtInkTest {

    private fun canvas(
        width: Int,
        height: Int,
        inkLeft: Int,
        inkTop: Int,
        inkRight: Int,
        inkBottom: Int,
        haloAlpha: Int = 0,
    ): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val opaque = x in inkLeft..inkRight && y in inkTop..inkBottom
        val alpha = if (opaque) 0xFF else haloAlpha
        (alpha shl 24) or 0x00FF8800
    }

    @Test
    fun `the scan finds the opaque rectangle and reports its own aspect`() {
        val bounds = mimirScanArtInkBounds(
            argb = canvas(100, 100, inkLeft = 20, inkTop = 10, inkRight = 59, inkBottom = 89),
            width = 100,
            height = 100,
        )!!
        assertEquals(0.20f, bounds.left, 0.001f)
        assertEquals(0.10f, bounds.top, 0.001f)
        assertEquals(0.60f, bounds.right, 0.001f)
        assertEquals(0.90f, bounds.bottom, 0.001f)
        // 40 wide x 80 tall of ink -- the seat should be shaped 0.5, NOT the canvas's 1.0.
        assertEquals(0.5f, bounds.aspect, 0.001f)
        assertTrue(bounds.worthHugging)
    }

    /**
     * A resampled 3D render leaves near-zero alpha well outside the box it drew. Honouring those pixels
     * reproduces the untrimmed canvas almost exactly -- the defect wearing a tighter bounding box -- so
     * the floor is the mechanism here, not a tolerance.
     */
    @Test
    fun `the alpha floor ignores the resampling halo a 3D render leaves behind`() {
        val withHalo = canvas(100, 100, 30, 30, 69, 69, haloAlpha = 8)
        val honouringEveryPixel = mimirScanArtInkBounds(withHalo, 100, 100, alphaFloor = 1)!!
        val withFloor = mimirScanArtInkBounds(withHalo, 100, 100)!!

        assertEquals(1f, honouringEveryPixel.widthFraction, 0.001f)
        assertFalse(honouringEveryPixel.worthHugging)
        assertEquals(0.40f, withFloor.widthFraction, 0.001f)
        assertTrue(withFloor.worthHugging)
    }

    @Test
    fun `art that already fills its file is left alone`() {
        val full = mimirScanArtInkBounds(canvas(64, 64, 0, 0, 63, 63), 64, 64)!!
        assertFalse(full.worthHugging)
        // Fully transparent has no ink to hug, and says so rather than inventing a rectangle.
        assertNull(mimirScanArtInkBounds(IntArray(64 * 64), 64, 64))
        assertNull(mimirScanArtInkBounds(IntArray(0), 0, 0))
    }

    /**
     * Where the ink lands on the seat, given the transform. All four attempts at this shipped correct
     * arithmetic; what differed was whether the layout honoured it, so these assert the RESULT of the
     * transform rather than its intermediate numbers.
     */
    private fun painted(seatW: Float, seatH: Float, ink: MimirArtInkBounds): FloatArray {
        val d = mimirArtInkDraw(seatW, seatH, ink)
        val left = ink.left * seatW * d.scaleX + d.translationX
        val right = ink.right * seatW * d.scaleX + d.translationX
        val top = ink.top * seatH * d.scaleY + d.translationY
        val bottom = ink.bottom * seatH * d.scaleY + d.translationY
        return floatArrayOf(left, top, right - left, bottom - top)
    }

    /**
     * The seat the List preview solves for IS the ink's aspect, and there the trim has to be exact:
     * the art meets all four edges and nothing is cut.
     */
    @Test
    fun `on a seat shaped for the ink the art meets all four edges`() {
        val ink = mimirScanArtInkBounds(canvas(200, 200, 40, 20, 119, 179), 200, 200)!!
        // 80 x 160 of ink -> aspect 0.5. Seat solved to match.
        val seatW = 100f
        val seatH = seatW / ink.aspect
        val (left, top, width, height) = painted(seatW, seatH, ink).let {
            listOf(it[0], it[1], it[2], it[3])
        }
        assertEquals(0f, left, 0.01f)
        assertEquals(0f, top, 0.01f)
        assertEquals(seatW, width, 0.01f)
        assertEquals(seatH, height, 0.01f)
    }

    /**
     * And on a seat shaped for something else -- the carousel's platform-shaped cell -- the ink still
     * centres, still keeps its aspect, and still never overflows. That is what makes this safe to adopt
     * one surface at a time instead of converting every seat in the same commit.
     */
    @Test
    fun `on a seat shaped for something else the art centres and never overflows`() {
        val ink = mimirScanArtInkBounds(canvas(200, 200, 40, 20, 119, 179), 200, 200)!!
        for (seat in listOf(120f to 100f, 100f to 300f, 90f to 90f, 300f to 100f)) {
            val (seatW, seatH) = seat
            val p = painted(seatW, seatH, ink)
            val left = p[0]; val top = p[1]; val width = p[2]; val height = p[3]

            assertTrue("$seat left", left >= -0.01f)
            assertTrue("$seat top", top >= -0.01f)
            assertTrue("$seat width", width <= seatW + 0.01f)
            assertTrue("$seat height", height <= seatH + 0.01f)
            // Centred: the slack is split evenly, never dumped on one side.
            assertEquals("$seat centreX", seatW - (left + width), left, 0.01f)
            assertEquals("$seat centreY", seatH - (top + height), top, 0.01f)
            // Aspect survives -- FillBounds distorted it and the layer had to undo exactly that much.
            assertEquals("$seat aspect", ink.aspect, width / height, 0.01f)
        }
    }

    /**
     * The transform is applied about the TOP-LEFT. Every offset above is derived assuming it, and a
     * default-centred `graphicsLayer` would look almost right at a glance and be wrong everywhere.
     */
    @Test
    fun `the transform assumes a top-left pivot`() {
        assertEquals(0f, MimirArtInkDraw.transformOriginX, 0f)
        assertEquals(0f, MimirArtInkDraw.transformOriginY, 0f)
    }

    /**
     * The verdict split that a device caught on 2026-08-28. The first build folded "I could not measure
     * this" into "this has no margin" and wrote the negative to disk, so one broken decode permanently
     * settled a cover that is 48 % ink. Only two things reach this function, and neither is a failure.
     */
    @Test
    fun `a completed scan yields a verdict, and only a completed scan reaches one`() {
        val trimmable = mimirScanArtInkBounds(canvas(100, 100, 20, 2, 59, 97), 100, 100)!!
        assertTrue(mimirArtInkOutcome(trimmable) is MimirArtInkMeasurement.Measured)
        // Full-canvas art and fully transparent art are both ANSWERS, and both are remembered.
        val full = mimirScanArtInkBounds(canvas(64, 64, 0, 0, 63, 63), 64, 64)!!
        assertEquals(MimirArtInkMeasurement.NoInkToHug, mimirArtInkOutcome(full))
        assertEquals(MimirArtInkMeasurement.NoInkToHug, mimirArtInkOutcome(null))
        // Unmeasurable is not reachable from here at all -- it is the decoder's word, never the scan's.
        assertTrue(
            listOf(mimirArtInkOutcome(trimmable), mimirArtInkOutcome(full), mimirArtInkOutcome(null))
                .none { it == MimirArtInkMeasurement.Unmeasurable },
        )
    }
}
