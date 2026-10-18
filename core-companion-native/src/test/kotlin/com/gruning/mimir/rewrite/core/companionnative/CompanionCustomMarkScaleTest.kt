package com.gruning.mimir.rewrite.core.companionnative

import com.gruning.mimir.rewrite.core.model.MimirMarkInkAlphaFloor
import com.gruning.mimir.rewrite.core.model.MimirMarkRect
import com.gruning.mimir.rewrite.core.model.mimirMarkDrawRect
import com.gruning.mimir.rewrite.core.model.mimirMarkInkBounds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 *  That got 1.18x.  1.18x was too timid: the box is now 44 dp (1.47x the rune's 30 dp), the mask is trimmed of its own
 * padding first, and the image is clipped to the coin face so it can never cross the rim.
 *
 * The numbers behind it, and where they are and are not used. The pixels it paints are `CompanionButtonMarkDrawTest` in
 * `:app`; this module's own test owns the tokens and the two pure rules (the ink bounds and the aspect fit).
 */
class CompanionCustomMarkScaleTest {

    private val coinDp = MButtonSizeDp.toFloat()
    private val runeBoxDp = coinDp - 2f * CompanionCoinMarkInsetDp
    private val customBoxDp = coinDp - 2f * CompanionCoinCustomMarkInsetDp

    /** The coin face the image is clipped to: the rim is 1.25 dp stroked on the disc's edge, so the face ends at its inner edge. */
    private val faceRadiusDp = coinDp / 2f - 1.25f - 1.25f / 2f

    @Test
    fun `the rune keeps its shipping 30 dp box`() {
        assertEquals(13f, CompanionCoinMarkInsetDp, 0f)
        assertEquals(30f, runeBoxDp, 0f)
        assertEquals(CompanionCoinMarkInsetDp, companionCoinMarkInsetDp(customImage = false), 0f)
    }

    @Test
    fun `the player's image is clearly bigger, a 44 dp box that is about 1_47 times the rune's`() {
        assertEquals(44f, CompanionCustomMarkBoxDp, 0f)
        assertEquals(44f, customBoxDp, 0f)
        assertEquals(6f, CompanionCoinCustomMarkInsetDp, 0f)
        assertEquals(CompanionCustomMarkScale, customBoxDp / runeBoxDp, 0.0005f)
        assertTrue("scale ${CompanionCustomMarkScale}", CompanionCustomMarkScale in 1.45f..1.50f)
        assertTrue("clearly past the old 1.18", CompanionCustomMarkScale > 1.18f * 1.2f)
        assertEquals(CompanionCoinCustomMarkInsetDp, companionCoinMarkInsetDp(customImage = true), 0f)
    }

    @Test
    fun `the box's edges stand inside the coin face and its corners do not, so the face clip is what bounds the mark`() {
        assertEquals("the face the rim leaves", 26.125f, faceRadiusDp, 0.001f)
        // The middle of each side of the box is inside the face with room to spare ...
        assertTrue("edge ${customBoxDp / 2f} dp inside the face's $faceRadiusDp dp", customBoxDp / 2f < faceRadiusDp)
        // ... but a mask that inks its corner would reach past it: the clip rounds that corner, and is not optional.
        val corner = customBoxDp / 2f * Math.sqrt(2.0).toFloat()
        assertTrue("corner $corner dp is past the face's $faceRadiusDp dp", corner > faceRadiusDp)
    }

    @Test
    fun `only the Mimir button's coin takes the larger inset, decided by what was drawn, and clips only that image`() {
        val source = listOf(
            File("core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt"),
        ).first(File::isFile).readText()
        val factory = source.substringAfter("internal fun createMimirButtonCoin(").substringBefore("fun createMimirButtonPreviewView(")
        assertTrue(factory.contains("markInsetDp = companionCoinMarkInsetDp(customImage = mark is BitmapDrawable)"))
        // The menu's rune coins never name an inset: they keep the default.
        assertTrue(source.contains("CompanionMenuCoinView(context, theme, row.rune).apply"))
        assertTrue(source.contains("val inset = px(markInsetDp)"))
        assertFalse("no literal 13f inset left in the draw", source.contains("val inset = px(13f)"))

        val draw = source.substringAfter("val inset = px(markInsetDp)").substringBefore("canvas.restoreToCount(layer)")
        val clip = draw.indexOf("canvas.clipPath(facePath)")
        val pour = draw.indexOf("PorterDuff.Mode.SRC_IN")
        assertTrue("the image is clipped to the face", clip >= 0)
        assertTrue("inside the layer, before the metal is poured through it", draw.indexOf("canvas.saveLayer(") in 0 until clip && clip < pour)
        val beforeClip = draw.substring(draw.indexOf("canvas.saveLayer("), clip)
        assertTrue("only for the player's bitmap, never the rune's vector", beforeClip.contains("if (mark is BitmapDrawable) {"))
        assertTrue("the face is the circle the rim leaves", draw.contains("radius - rim / 2f"))
        assertTrue("and the image is placed by the one shared geometry, with the player's placement", draw.contains("mimirMarkDrawRect(") && draw.contains("placement"))
        assertTrue("the factory hands the coin the mark's placement", factory.contains("placement = (theme.mark as? CompanionButtonMark.Image)?.placement"))
    }

    // ---- the ink bounds ----------------------------------------------------------------------------------------------

    private fun argb(alpha: Int) = alpha shl 24 or 0xFFFFFF

    @Test
    fun `the ink bounds are the box around every pixel above the alpha floor, inclusive`() {
        val width = 8
        val height = 6
        val pixels = IntArray(width * height)
        pixels[2 * width + 3] = argb(255)
        pixels[4 * width + 6] = argb(MimirMarkInkAlphaFloor + 1)
        assertArrayEquals(intArrayOf(3, 2, 6, 4), mimirMarkInkBounds(pixels, width, height))
    }

    @Test
    fun `a mask with no ink has no bounds, and faint noise at or under the floor is padding`() {
        val pixels = IntArray(8 * 6)
        assertNull(mimirMarkInkBounds(pixels, 8, 6))
        pixels[10] = argb(MimirMarkInkAlphaFloor)
        pixels[20] = argb(1)
        assertNull("alpha at or under the floor is not ink", mimirMarkInkBounds(pixels, 8, 6))
    }

    @Test
    fun `a mask inked to its edges has bounds that are the whole image`() {
        val pixels = IntArray(5 * 4) { argb(255) }
        assertArrayEquals(intArrayOf(0, 0, 4, 3), mimirMarkInkBounds(pixels, 5, 4))
    }

    // ---- the aspect fit ----------------------------------------------------------------------------------------------
    // The fit is the shared geometry's now (mimirMarkDrawRect, :core-model; MimirMarkPlacementTest owns it). These are the same cases the coin's old
    // integer fit pinned, read at its 88 px box: a 112 px coin, the box standing 12 px in on each side.

    private fun fit(width: Int, height: Int): MimirMarkRect = mimirMarkDrawRect(112f, width, height)

    @Test
    fun `a square image fills the box`() {
        val fit = fit(68, 68)
        assertEquals(listOf(12f, 12f, 100f, 100f), listOf(fit.left, fit.top, fit.right, fit.bottom))
    }

    @Test
    fun `a wide image fills the width and is centred vertically, aspect kept`() {
        // 132 x 36 into 88 x 88: 88 wide, 24 tall, centred in the box's height.
        val fit = fit(132, 36)
        assertEquals(listOf(12f, 44f, 100f, 68f), listOf(fit.left, fit.top, fit.right, fit.bottom).map { Math.round(it).toFloat() })
        assertEquals(88f, fit.width, 0.01f)
        assertEquals(24f, fit.height, 0.1f)
    }

    @Test
    fun `a tall image fills the height and is centred horizontally, aspect kept`() {
        val fit = fit(36, 132)
        assertEquals(listOf(44f, 12f, 68f, 100f), listOf(fit.left, fit.top, fit.right, fit.bottom).map { Math.round(it).toFloat() })
    }

    @Test
    fun `the fit never leaves the box, and a degenerate image returns the box`() {
        for ((w, h) in listOf(1 to 500, 500 to 1, 3 to 7, 1_024 to 1_024, 130 to 256)) {
            val fit = fit(w, h)
            assertTrue("$w x $h inside", fit.left >= 12f - 0.01f && fit.top >= 12f - 0.01f && fit.right <= 100f + 0.01f && fit.bottom <= 100f + 0.01f && fit.right > fit.left && fit.bottom > fit.top)
        }
        val box = fit(0, 50)
        assertEquals(listOf(12f, 12f, 100f, 100f), listOf(box.left, box.top, box.right, box.bottom))
    }
}
