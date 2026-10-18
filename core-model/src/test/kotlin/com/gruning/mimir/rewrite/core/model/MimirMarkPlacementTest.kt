package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's own placement of the image on the ᛗ button : one pure geometry, so the live coin, the picker's tiles and its
 * stage cannot disagree. Scale runs 0.60 to 1.60 of the 44 dp box, the image's centre stays within 12 dp of the coin's on each axis, and the
 * default is the centred 100% the button has always drawn. The face clip is not this function's: it is the callers', and unchanged.
 */
class MimirMarkPlacementTest {

    private val coin = MimirMarkReferenceCoinDp

    private fun assertRect(expected: FloatArray, actual: MimirMarkRect, delta: Float = 0.001f) {
        assertEquals("left", expected[0], actual.left, delta)
        assertEquals("top", expected[1], actual.top, delta)
        assertEquals("right", expected[2], actual.right, delta)
        assertEquals("bottom", expected[3], actual.bottom, delta)
    }

    // ---- defaults -------------------------------------------------------------------------------------------------------

    @Test
    fun `the default is centred at 100 percent, which is today's 44 dp box on the 56 dp coin`() {
        assertEquals(MimirMarkPlacement(1f, 0f, 0f), MimirMarkPlacement.Default)
        assertTrue(MimirMarkPlacement.Default.isDefault)
        // The old inset was (56 - 44) / 2 = 6 dp a side.
        assertRect(floatArrayOf(6f, 6f, 50f, 50f), mimirMarkBoxRect(coin))
        assertRect(floatArrayOf(6f, 6f, 50f, 50f), mimirMarkDrawRect(coin, 100, 100))
    }

    @Test
    fun `a coin of another size scales the whole rect, the way the picker's tiles scale the live button`() {
        assertRect(floatArrayOf(12f, 12f, 100f, 100f), mimirMarkDrawRect(112f, 256, 256))
        val tile = mimirMarkDrawRect(36f, 256, 256)
        assertEquals(36f * 44f / 56f, tile.width, 0.001f)
        assertEquals(18f, tile.centerX, 0.001f)
        assertEquals(18f, tile.centerY, 0.001f)
        // A placement scales with the coin: 5 dp on the 56 dp reference is 10 px on a 112 px coin.
        val moved = mimirMarkDrawRect(112f, 256, 256, MimirMarkPlacement(1f, 5f, -3f))
        assertEquals(56f + 10f, moved.centerX, 0.001f)
        assertEquals(56f - 6f, moved.centerY, 0.001f)
    }

    // ---- aspect ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a wide image fills the box's width and a tall one its height, aspect kept and centred`() {
        // 4:1 in a 44 box: 44 wide, 11 tall, centred on the coin.
        assertRect(floatArrayOf(6f, 28f - 5.5f, 50f, 28f + 5.5f), mimirMarkDrawRect(coin, 400, 100))
        assertRect(floatArrayOf(28f - 5.5f, 6f, 28f + 5.5f, 50f), mimirMarkDrawRect(coin, 100, 400))
        val wide = mimirMarkDrawRect(coin, 132, 36)
        assertEquals(132f / 36f, wide.width / wide.height, 0.001f)
    }

    @Test
    fun `the aspect fit follows the placement's centre and size`() {
        val p = MimirMarkPlacement(scale = MimirMarkScaleMin, offsetX = 4f, offsetY = 6f)
        val r = mimirMarkDrawRect(coin, 400, 100, p)
        // 0.60 of the 44 box is 26.4, centred at (32, 34).
        assertEquals(32f, r.centerX, 0.001f)
        assertEquals(34f, r.centerY, 0.001f)
        assertEquals(26.4f, r.width, 0.001f)
        assertEquals(26.4f / 4f, r.height, 0.001f)
    }

    @Test
    fun `a degenerate image or coin answers the box or nothing and never throws`() {
        assertRect(floatArrayOf(6f, 6f, 50f, 50f), mimirMarkDrawRect(coin, 0, 50))
        assertRect(floatArrayOf(6f, 6f, 50f, 50f), mimirMarkDrawRect(coin, 50, 0))
        assertRect(floatArrayOf(6f, 6f, 50f, 50f), mimirMarkDrawRect(coin, -4, -4))
        for (size in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val r = mimirMarkDrawRect(size, 10, 10)
            assertEquals("size $size", 0f, r.width, 0f)
            assertEquals("size $size", 0f, r.height, 0f)
        }
    }

    // ---- the clamps -----------------------------------------------------------------------------------------------------

    @Test
    fun `scale runs from 60 to 160 percent of the box and is held there`() {
        assertEquals(0.60f, MimirMarkScaleMin, 0f)
        assertEquals(1.60f, MimirMarkScaleMax, 0f)
        assertEquals(26.4f, mimirMarkDrawRect(coin, 10, 10, MimirMarkPlacement(scale = 0.60f)).width, 0.001f)
        assertEquals(70.4f, mimirMarkDrawRect(coin, 10, 10, MimirMarkPlacement(scale = 1.60f)).width, 0.001f)
        // Past either end it stays at the end.
        assertEquals(26.4f, mimirMarkDrawRect(coin, 10, 10, MimirMarkPlacement(scale = 0.01f)).width, 0.001f)
        assertEquals(26.4f, mimirMarkDrawRect(coin, 10, 10, MimirMarkPlacement(scale = -3f)).width, 0.001f)
        assertEquals(70.4f, mimirMarkDrawRect(coin, 10, 10, MimirMarkPlacement(scale = 9f)).width, 0.001f)
    }

    @Test
    fun `the image's centre stays within 12 dp of the coin's on each axis`() {
        assertEquals(12f, MimirMarkOffsetLimitDp, 0f)
        val far = mimirMarkDrawRect(coin, 10, 10, MimirMarkPlacement(offsetX = 99f, offsetY = -99f))
        assertEquals(28f + 12f, far.centerX, 0.001f)
        assertEquals(28f - 12f, far.centerY, 0.001f)
        val near = mimirMarkDrawRect(coin, 10, 10, MimirMarkPlacement(offsetX = -12f, offsetY = 12f))
        assertEquals(16f, near.centerX, 0.001f)
        assertEquals(40f, near.centerY, 0.001f)
        // The two axes are independent: one at its limit does not pull the other.
        val axis = mimirMarkDrawRect(coin, 10, 10, MimirMarkPlacement(offsetX = 50f, offsetY = 2f))
        assertEquals(40f, axis.centerX, 0.001f)
        assertEquals(30f, axis.centerY, 0.001f)
    }

    @Test
    fun `a value that is not a number is the field's default, and the others are kept`() {
        val bad = MimirMarkPlacement(scale = Float.NaN, offsetX = Float.POSITIVE_INFINITY, offsetY = 7f).sanitized()
        assertEquals(MimirMarkPlacement(1f, 0f, 7f), bad)
        assertEquals(MimirMarkPlacement.Default, MimirMarkPlacement(Float.NEGATIVE_INFINITY, Float.NaN, Float.NaN).sanitized())
        // The geometry reads the same rule, so a bad value draws as the default instead of throwing or going missing.
        assertRect(floatArrayOf(6f, 6f, 50f, 50f), mimirMarkDrawRect(coin, 10, 10, MimirMarkPlacement(Float.NaN, Float.NaN, Float.NaN)))
    }

    @Test
    fun `sanitizing is idempotent and an in range placement is untouched`() {
        val p = MimirMarkPlacement(1.25f, -3.5f, 8f)
        assertEquals(p, p.sanitized())
        val wild = MimirMarkPlacement(40f, -40f, 40f).sanitized()
        assertEquals(wild, wild.sanitized())
        assertEquals(MimirMarkPlacement(1.60f, -12f, 12f), wild)
        assertFalse(p.isDefault)
        assertTrue("a placement that sanitizes to the default is the default", MimirMarkPlacement(Float.NaN, Float.NaN, Float.NaN).isDefault)
    }

    @Test
    fun `the face clip is the callers' and its numbers are unchanged by a placement`() {
        assertEquals(26.125f, MimirMarkReferenceFaceRadiusDp, 0f)
        assertEquals(56f, MimirMarkReferenceCoinDp, 0f)
        assertEquals(44f, MimirMarkReferenceBoxDp, 0f)
        // At the largest size and the farthest offset the rect reaches past the face: the clip, not the rect, is what bounds the ink.
        val biggest = mimirMarkDrawRect(coin, 10, 10, MimirMarkPlacement(1.60f, 12f, 12f))
        assertTrue(biggest.right > coin)
    }

    // ---- the pad's and the finger's steps -------------------------------------------------------------------------------

    @Test
    fun `a pad press moves the image half a dp and the limit holds it`() {
        assertEquals(0.5f, MimirMarkNudgeDp, 0f)
        var p = MimirMarkPlacement.Default
        repeat(3) { p = p.movedBy(MimirMarkNudgeDp, 0f) }
        assertEquals(MimirMarkPlacement(1f, 1.5f, 0f), p)
        repeat(40) { p = p.movedBy(MimirMarkNudgeDp, MimirMarkNudgeDp) }
        assertEquals(MimirMarkPlacement(1f, 12f, 12f), p)
        repeat(100) { p = p.movedBy(-MimirMarkNudgeDp, -MimirMarkNudgeDp) }
        assertEquals(MimirMarkPlacement(1f, -12f, -12f), p)
        assertEquals("a bad delta moves nothing", p, p.movedBy(Float.NaN, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `L1 and R1 change the size by 5 percent and 20 presses either way land on whole lines`() {
        assertEquals(0.05f, MimirMarkScaleStep, 0f)
        var p = MimirMarkPlacement.Default
        p = p.steppedScale(larger = true)
        assertEquals(1.05f, p.scale, 0f)
        p = p.steppedScale(larger = true).steppedScale(larger = true)
        assertEquals(1.15f, p.scale, 0.00001f)
        repeat(3) { p = p.steppedScale(larger = false) }
        assertEquals("back to exactly 100%", 1f, p.scale, 0f)
        // Held at the ends.
        repeat(30) { p = p.steppedScale(larger = true) }
        assertEquals(MimirMarkScaleMax, p.scale, 0f)
        repeat(60) { p = p.steppedScale(larger = false) }
        assertEquals(MimirMarkScaleMin, p.scale, 0f)
        // Every size the pad can reach is a whole number of 5% lines.
        var walk = MimirMarkPlacement.Default
        repeat(8) {
            walk = walk.steppedScale(larger = true)
            val lines = walk.scale * 20f
            assertEquals(Math.round(lines).toFloat(), lines, 0.0001f)
        }
    }

    @Test
    fun `a size a pinch left between two lines moves to the nearest line in the direction pressed`() {
        val between = MimirMarkPlacement(scale = 1.037f)
        assertEquals(1.05f, between.steppedScale(larger = true).scale, 0.00001f)
        assertEquals(1.00f, between.steppedScale(larger = false).scale, 0.00001f)
    }

    @Test
    fun `a pinch sizes by its ratio, held in range, and a bad ratio changes nothing`() {
        assertEquals(1.5f, MimirMarkPlacement.Default.scaledBy(1.5f).scale, 0.00001f)
        assertEquals(MimirMarkScaleMax, MimirMarkPlacement.Default.scaledBy(50f).scale, 0f)
        assertEquals(MimirMarkScaleMin, MimirMarkPlacement.Default.scaledBy(0.001f).scale, 0f)
        for (bad in listOf(0f, -2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("factor $bad", MimirMarkPlacement.Default, MimirMarkPlacement.Default.scaledBy(bad))
        }
    }
}
