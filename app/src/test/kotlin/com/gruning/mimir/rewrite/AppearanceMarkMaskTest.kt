package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mimir-button mark's processing maths, pinned on the JVM
 * without a graphics stack: the picture is decoded whole and its SUBJECT is fitted into the mark, and the mask the button
 * strikes its metal through is the image's own alpha, or an opaque image's luminance -- read the other way round when the
 * image's edges are mostly light (a dark logo on white), so the dark parts are the mark -- after an opaque image's
 * edge-connected, one-colour background has been cut out, when it has one.
 */
class AppearanceMarkMaskTest {

    private val opaqueWhite = 0xFFFFFFFF.toInt()
    private val opaqueBlack = 0xFF000000.toInt()
    private val clear = 0x00FFFFFF

    private fun alphaOf(argb: Int) = argb ushr 24

    /** An [edge] x [edge] picture: [background] everywhere, except where [paint] answers a colour. */
    private fun picture(edge: Int, background: Int, paint: (x: Int, y: Int) -> Int? = { _, _ -> null }): IntArray =
        IntArray(edge * edge) { index -> paint(index % edge, index / edge) ?: background }

    /** A [size]-px square in the middle of an [edge]-px picture. */
    private fun centred(edge: Int, size: Int, colour: Int): (Int, Int) -> Int? {
        val from = (edge - size) / 2
        return { x, y -> if (x in from until from + size && y in from until from + size) colour else null }
    }

    /** A thick O centred in an [edge]-px picture: [colour] where the radius is 10..20 px. */
    private fun ring(edge: Int, colour: Int): (Int, Int) -> Int? = { x, y ->
        val dx = x - edge / 2
        val dy = y - edge / 2
        if (dx * dx + dy * dy in 100..400) colour else null
    }

    private fun mask(pixels: IntArray, edge: Int): AppearanceMarkMask = checkNotNull(appearanceMarkMask(pixels, edge)) { "no mask" }

    @Test
    fun `the picture is decoded whole, at most 512 on its long edge, never up`() {
        assertEquals(CustomImageSize(512, 341), appearanceMarkDecodeSize(3_000, 2_000))
        assertEquals(CustomImageSize(341, 512), appearanceMarkDecodeSize(2_000, 3_000))
        assertEquals(CustomImageSize(512, 512), appearanceMarkDecodeSize(1_024, 1_024))
        assertEquals("a small picture is decoded as it is; the fit scales its subject", CustomImageSize(64, 64), appearanceMarkDecodeSize(64, 64))
        assertEquals(CustomImageSize(300, 200), appearanceMarkDecodeSize(300, 200))
        // A degenerate size never reaches the decoder as zero.
        assertEquals(CustomImageSize(1, 1), appearanceMarkDecodeSize(0, 40))
        assertEquals(16, AppearanceMarkProcessing.MarkInsetPx)
        assertEquals(512, AppearanceMarkProcessing.DecodeLongEdgePx)
    }

    // ── image fit ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the subject is trimmed to its box and fitted centred inside the inset`() {
        // A 100 x 60 mask with a 10 x 40 bar of ink at (20, 5): the bar is scaled 5.6x to 56 x 224 and centred.
        val width = 100
        val height = 60
        val bar = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x in 20 until 30 && y in 5 until 45) opaqueWhite else clear
        }
        val fit = checkNotNull(appearanceMarkFit(bar, width, height))
        assertEquals(10, fit.subjectWidth)
        assertEquals(40, fit.subjectHeight)
        assertEquals(56, fit.fittedWidth)
        assertEquals(224, fit.fittedHeight)
        assertEquals(100, fit.left)
        assertEquals(16, fit.top)
        assertEquals(256 * 256, fit.argb.size)
        fun alpha(x: Int, y: Int) = alphaOf(fit.argb[y * 256 + x])
        assertEquals(255, alpha(128, 128))
        assertEquals(255, alpha(100, 16))
        assertEquals(255, alpha(155, 239))
        assertEquals("the inset stays clear above", 0, alpha(128, 8))
        assertEquals("and below", 0, alpha(128, 247))
        assertEquals("nothing left of the bar", 0, alpha(90, 128))
        assertEquals("nothing right of it", 0, alpha(165, 128))
        assertTrue("white ink throughout", fit.argb.all { it and 0x00FFFFFF == 0x00FFFFFF })
    }

    @Test
    fun `a subject larger than the mark is averaged down, and a wide one keeps its ends`() {
        // A 1000 x 100 bar filling its picture: 224 x 22, centred -- where the old centre crop kept the middle 100 px.
        val fit = checkNotNull(appearanceMarkFit(IntArray(1000 * 100) { opaqueWhite }, 1000, 100))
        assertEquals(224, fit.fittedWidth)
        assertEquals(22, fit.fittedHeight)
        assertEquals(16, fit.left)
        assertEquals(117, fit.top)
        assertEquals(255, alphaOf(fit.argb[128 * 256 + 16]))
        assertEquals(255, alphaOf(fit.argb[128 * 256 + 239]))
        assertEquals(0, alphaOf(fit.argb[128 * 256 + 15]))
        assertEquals(0, alphaOf(fit.argb[128 * 256 + 240]))
        // Scaled down by two, a one-pixel checker of ink and nothing averages to half ink: soft, not stepped.
        val checker = IntArray(448 * 448) { index -> if ((index % 448 + index / 448) % 2 == 0) opaqueWhite else clear }
        val down = checkNotNull(appearanceMarkFit(checker, 448, 448))
        assertEquals(224, down.fittedWidth)
        assertEquals("two of four source pixels are ink: 510 / 4, in whole codes", 127, alphaOf(down.argb[128 * 256 + 128]))
        assertNull("no subject at all", appearanceMarkFit(IntArray(64 * 64) { clear }, 64, 64))
        assertNull(appearanceMarkFit(IntArray(0), 0, 0))
    }

    // ── background cut-out ──────────────────────────────────────────────────────────────────

    @Test
    fun `a light ring on a blue field loses the field, and its enclosed centre keeps its luminance reading`() {
        val edge = 64
        val blue = 0xFF2040A0.toInt() // luma 64: dark, so the border reads light-becomes-ink
        val logo = picture(edge, blue, ring(edge, opaqueWhite))
        val mask = mask(logo, edge)
        assertEquals(AppearanceMarkMaskSource.LUMINANCE, mask.source)
        assertTrue(mask.backgroundRemoved)
        fun alpha(x: Int, y: Int) = alphaOf(mask.argb[y * edge + x])
        assertEquals("the field is gone, not a faint blue wash", 0, alpha(2, 2))
        assertEquals(0, alpha(32, 3))
        assertEquals("the ring is ink", 255, alpha(32, 47))
        assertEquals(255, alpha(17, 32))
        assertEquals(
            "the inside of the O is enclosed: never cut, never a solid disc -- what the luminance reading makes of blue",
            64,
            alpha(32, 32),
        )
        // The field's own reading, for comparison: with a border too busy to be cut, blue washes the whole mask at 64.
        val busy = picture(edge, blue) { x, y -> if ((x + y) % 2 == 0 && (x < 6 || y < 6 || x >= 58 || y >= 58)) opaqueBlack else ring(edge, opaqueWhite)(x, y) }
        val washed = mask(busy, edge)
        assertTrue(!washed.backgroundRemoved)
        assertEquals(64, alphaOf(washed.argb[10 * edge + 32]))
    }

    @Test
    fun `a dark ring on white loses the white and keeps its centre see-through`() {
        val edge = 64
        val logo = picture(edge, opaqueWhite, ring(edge, opaqueBlack))
        val mask = mask(logo, edge)
        assertEquals("decided on the picture as it was: light edges, dark is ink", AppearanceMarkMaskSource.LUMINANCE_DARK, mask.source)
        assertTrue(mask.backgroundRemoved)
        fun alpha(x: Int, y: Int) = alphaOf(mask.argb[y * edge + x])
        assertEquals(0, alpha(2, 2))
        assertEquals(255, alpha(32, 47))
        assertEquals("the white inside the O inverts to nothing, as before", 0, alpha(32, 32))
        assertEquals("exactly the ring is ink", logo.count { it == opaqueBlack }, mask.argb.count { alphaOf(it) >= 255 })
    }

    @Test
    fun `a photo, a border that is not one colour, or a picture with nothing to keep is left exactly as it was`() {
        val edge = 64
        // A vertical gradient: the ring has no one colour.
        val gradient = picture(edge, opaqueBlack) { _, y -> val v = y * 4; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        assertNull(appearanceMarkBackgroundCutout(gradient, edge))
        assertTrue(!mask(gradient, edge).backgroundRemoved)
        // A field that is the whole picture, or a subject that fills it: nothing is cut either way.
        assertNull(appearanceMarkBackgroundCutout(IntArray(edge * edge) { opaqueBlack }, edge))
        assertNull(appearanceMarkBackgroundCutout(picture(edge, opaqueWhite) { x, y -> if (x < 4 && y < 4) opaqueBlack else null }, edge))
        // A noisy border within the tolerance is one field; a subject 25 codes off it is not, and is kept whole.
        val grey = 0xFF808080.toInt()
        val noise = 0xFF888888.toInt()
        val subject = 0xFF999999.toInt()
        val noisy = picture(edge, grey) { x, y ->
            when {
                x in 24 until 40 && y in 24 until 40 -> subject
                (x * 7 + y * 13) % 3 == 0 -> noise
                else -> null
            }
        }
        val cut = checkNotNull(appearanceMarkBackgroundCutout(noisy, edge))
        assertTrue("noise within tolerance is the field", cut[2 * edge + 2] && cut[3 * edge + 3])
        assertTrue("the field's noise pixels too", cut.count { it } == edge * edge - 256)
        assertTrue("the subject 25 codes off is not", !cut[32 * edge + 32] && !cut[24 * edge + 24])
        assertEquals(24, MarkCutoutTolerance)
        assertEquals(0.9, MarkCutoutUniformFraction, 0.0)
    }

    @Test
    fun `the cut-out never reaches an enclosed region and passes through see-through corners`() {
        val edge = 64
        // A white picture with a black frame 6 px in: the outside white is the field; the white INSIDE the frame is
        // enclosed and stays (it inverts to nothing under the dark reading anyway).
        val framed = picture(edge, opaqueWhite) { x, y -> if ((x == 6 || x == 57 || y == 6 || y == 57) && x in 6..57 && y in 6..57) opaqueBlack else null }
        val cut = checkNotNull(appearanceMarkBackgroundCutout(framed, edge))
        assertTrue(cut[2 * edge + 2])
        assertTrue(!cut[6 * edge + 6])
        assertTrue("enclosed", !cut[32 * edge + 32])
        // Rounded corners: the see-through pixels are passed through, so a field behind them is still reached.
        val rounded = picture(edge, opaqueWhite) { x, y -> if (x + y < 6) 0x00000000 else if (x in 20 until 44 && y in 20 until 44) opaqueBlack else null }
        val cutRounded = checkNotNull(appearanceMarkBackgroundCutout(rounded, edge))
        assertTrue(cutRounded[0] && cutRounded[7 * edge + 0] && cutRounded[0 * edge + 7])
        assertTrue(!cutRounded[32 * edge + 32])
    }

    @Test
    fun `an opaque image's luminance becomes its alpha and its colour is dropped`() {
        val green = 0xFF00FF00.toInt()
        val midGrey = 0xFF808080.toInt()
        // Four samples in the middle of a dark picture: a dark border reads light-becomes-ink.
        val samples = mapOf((7 to 7) to opaqueWhite, (8 to 7) to opaqueBlack, (7 to 8) to midGrey, (8 to 8) to green)
        val pixels = picture(16, opaqueBlack) { x, y -> samples[x to y] }
        val mask = mask(pixels, 16)
        assertEquals(AppearanceMarkMaskSource.LUMINANCE, mask.source)
        fun alpha(x: Int, y: Int) = alphaOf(mask.argb[y * 16 + x])
        assertEquals("light becomes ink", 255, alpha(7, 7))
        assertEquals("dark becomes see-through", 0, alpha(8, 7))
        assertEquals("mid grey is half ink", 128, alpha(7, 8))
        assertEquals("Rec. 709 weights: green carries most of the brightness", 182, alpha(8, 8))
        mask.argb.forEach { assertEquals("the ink is white, so a thumbnail of the file reads on a dark tile", 0x00FFFFFF, it and 0x00FFFFFF) }
    }

    @Test
    fun `an image with real transparency keeps its own alpha whatever its colours`() {
        // 100 px: 20 fully see-through, 80 opaque BLACK. A dark logo on nothing: its alpha is the shape, so black is ink.
        val logo = IntArray(100) { index -> if (index < 20) 0x00FF0000 else opaqueBlack }
        val mask = mask(logo, 10)
        assertEquals(AppearanceMarkMaskSource.ALPHA, mask.source)
        assertEquals(0, alphaOf(mask.argb[0]))
        assertEquals("opaque black is ink when the image has an alpha channel that means something", 255, alphaOf(mask.argb[50]))
    }

    @Test
    fun `transparency wins over a light border and the alpha branch is never inverted`() {
        // A white shape cut out of nothing: the opaque pixels are WHITE, so a light border would flip a luminance reading.
        // Real transparency keeps its own alpha, so the white shape is the ink and nothing inverts.
        val edge = 32
        val logo = picture(edge, opaqueWhite) { x, y -> if (x < 8 || y < 8) 0x00FFFFFF else null }
        val mask = mask(logo, edge)
        assertEquals(AppearanceMarkMaskSource.ALPHA, mask.source)
        assertEquals("the see-through part stays see-through", 0, alphaOf(mask.argb[2 * edge + 2]))
        assertEquals("the opaque white shape is the ink", 255, alphaOf(mask.argb[20 * edge + 20]))
    }

    @Test
    fun `a photo whose only alpha is a rounded corner is still read by luminance`() {
        // 32 x 32, 5 px see-through (0.5%): under the 1% that makes an image "transparent". A dark photo with a light
        // subject: the corner stays see-through, the subject is ink.
        val edge = 32
        val photo = picture(edge, opaqueBlack) { x, y ->
            when {
                y == 0 && x < 5 -> 0x00000000
                x in 10 until 22 && y in 10 until 22 -> opaqueWhite
                else -> null
            }
        }
        val mask = mask(photo, edge)
        assertEquals(AppearanceMarkMaskSource.LUMINANCE, mask.source)
        assertEquals(0, alphaOf(mask.argb[0]))
        assertEquals(255, alphaOf(mask.argb[16 * edge + 16]))
    }

    @Test
    fun `a light photo with rounded corners is read the dark way and the corners stay see-through`() {
        // The corner's see-through pixels are not a border to measure: the visible ring is white, so the dark subject is ink.
        val edge = 32
        val photo = picture(edge, opaqueWhite) { x, y ->
            when {
                y == 0 && x < 5 -> 0x00000000
                x in 10 until 22 && y in 10 until 22 -> opaqueBlack
                else -> null
            }
        }
        val mask = mask(photo, edge)
        assertEquals(AppearanceMarkMaskSource.LUMINANCE_DARK, mask.source)
        assertEquals("a see-through pixel is no ink whichever way the image is read", 0, alphaOf(mask.argb[0]))
        assertEquals(255, alphaOf(mask.argb[16 * edge + 16]))
        assertEquals(0, alphaOf(mask.argb[20 * edge + 3]))
    }

    // ── the dark-logo rule  ───────────────────────────────────────────────────────────────────

    @Test
    fun `a dark square on white gives ink where the square is`() {
        val edge = 64
        val logo = picture(edge, opaqueWhite, centred(edge, 24, opaqueBlack))
        val mask = mask(logo, edge)
        assertEquals("light edges: the dark parts are the mark", AppearanceMarkMaskSource.LUMINANCE_DARK, mask.source)
        assertEquals("the square is ink", 255, alphaOf(mask.argb[32 * edge + 32]))
        assertEquals("the white ground is see-through", 0, alphaOf(mask.argb[2 * edge + 2]))
        assertEquals("exactly the square", 24 * 24, mask.argb.count { alphaOf(it) >= 255 })
        mask.argb.forEach { assertEquals("still white ink", 0x00FFFFFF, it and 0x00FFFFFF) }
    }

    @Test
    fun `a light glyph on dark is unchanged`() {
        val edge = 64
        val glyph = picture(edge, opaqueBlack, centred(edge, 24, opaqueWhite))
        val mask = mask(glyph, edge)
        assertEquals(AppearanceMarkMaskSource.LUMINANCE, mask.source)
        assertEquals(255, alphaOf(mask.argb[32 * edge + 32]))
        assertEquals(0, alphaOf(mask.argb[2 * edge + 2]))
        assertEquals(24 * 24, mask.argb.count { alphaOf(it) >= 255 })
    }

    @Test
    fun `a mixed or noisy border is decided by its median`() {
        val edge = 64
        // A deterministic scatter: a ring pixel is light when its hash falls under lightTenths of ten.
        fun noisyBorder(lightTenths: Int): IntArray = picture(edge, opaqueBlack) { x, y ->
            val ring = 5
            val onRing = x < ring || y < ring || x >= edge - ring || y >= edge - ring
            when {
                onRing -> if ((x * 31 + y * 17) % 10 < lightTenths) opaqueWhite else opaqueBlack
                x in 24 until 40 && y in 24 until 40 -> opaqueWhite
                else -> null
            }
        }
        // 70% of the border is light: read the dark way. The white subject is now the background, the black surround the mark.
        val flipped = mask(noisyBorder(lightTenths = 7), edge)
        assertEquals(AppearanceMarkMaskSource.LUMINANCE_DARK, flipped.source)
        assertEquals("the white subject is the background now", 0, alphaOf(flipped.argb[32 * edge + 32]))
        assertEquals("the black surround is the mark", 255, alphaOf(flipped.argb[15 * edge + 32]))

        // 30% of it is light: read as ever. The same subject is the ink.
        val kept = mask(noisyBorder(lightTenths = 3), edge)
        assertEquals(AppearanceMarkMaskSource.LUMINANCE, kept.source)
        assertEquals(255, alphaOf(kept.argb[32 * edge + 32]))
        assertEquals(0, alphaOf(kept.argb[15 * edge + 32]))

        // Only the outer ring is measured: a white interior inside a DARK ring is still a light-on-dark picture...
        val darkRing = picture(edge, opaqueWhite) { x, y -> if (x < 5 || y < 5 || x >= 59 || y >= 59) opaqueBlack else null }
        assertEquals(0, appearanceMarkBorderMedianLuma(darkRing, edge))
        // ...and a dark interior inside a LIGHT ring is a dark-on-light one.
        val lightRing = picture(edge, opaqueBlack) { x, y -> if (x < 5 || y < 5 || x >= 59 || y >= 59) opaqueWhite else null }
        assertEquals(255, appearanceMarkBorderMedianLuma(lightRing, edge))
    }

    @Test
    fun `an exactly even border keeps the light-becomes-ink reading`() {
        // 10 x 10: a 1 px ring of 36 pixels, 18 white and 18 black, inside a black picture with a 2 x 2 white centre.
        val edge = 10
        val ring = (0 until edge * edge).filter { it % edge == 0 || it % edge == edge - 1 || it / edge == 0 || it / edge == edge - 1 }
        assertEquals(36, ring.size)
        fun withWhiteRing(whiteCount: Int): IntArray {
            val white = ring.take(whiteCount).toSet()
            return IntArray(edge * edge) { index ->
                when {
                    index in white -> opaqueWhite
                    index % edge in 4..5 && index / edge in 4..5 -> opaqueWhite
                    else -> opaqueBlack
                }
            }
        }
        val even = withWhiteRing(18)
        assertEquals("the lower median of an even split is the dark half", 0, appearanceMarkBorderMedianLuma(even, edge))
        val mask = mask(even, edge)
        assertEquals("a tie is not a light border: today's reading stands", AppearanceMarkMaskSource.LUMINANCE, mask.source)
        assertEquals(255, alphaOf(mask.argb[4 * edge + 4]))
        // One pixel more light than dark tips it the other way.
        assertEquals(255, appearanceMarkBorderMedianLuma(withWhiteRing(19), edge))
        assertEquals(AppearanceMarkMaskSource.LUMINANCE_DARK, mask(withWhiteRing(19), edge).source)
    }

    @Test
    fun `the threshold is the mask's own midpoint`() {
        val edge = 32
        fun withBorderLuma(luma: Int): IntArray {
            val grey = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
            // A flat border of [luma] around a black interior holding one white block, so nothing is blank either way.
            return picture(edge, opaqueBlack) { x, y ->
                when {
                    x < 3 || y < 3 || x >= edge - 3 || y >= edge - 3 -> grey
                    x in 12 until 20 && y in 12 until 20 -> opaqueWhite
                    else -> null
                }
            }
        }
        assertEquals(
            "just under the midpoint is dark: light becomes ink",
            AppearanceMarkMaskSource.LUMINANCE,
            mask(withBorderLuma(MarkLightBorderLuma - 1), edge).source,
        )
        assertEquals(
            "at the midpoint it is light: dark becomes ink",
            AppearanceMarkMaskSource.LUMINANCE_DARK,
            mask(withBorderLuma(MarkLightBorderLuma), edge).source,
        )
    }

    @Test
    fun `an image with nothing to strike is refused rather than saved as a button with no mark`() {
        val edge = 64
        assertNull("an all-black JPEG", appearanceMarkMask(IntArray(edge * edge) { opaqueBlack }, edge))
        assertNull("a fully transparent PNG", appearanceMarkMask(IntArray(edge * edge) { 0x00000000 }, edge))
        assertNull(appearanceMarkMask(IntArray(0), 1))
        assertNull(
            "an all-white JPEG: its light border reads it the dark way, and that inverts to nothing",
            appearanceMarkMask(IntArray(edge * edge) { opaqueWhite }, edge),
        )
        assertNull(
            "and an all-white photo with rounded corners",
            appearanceMarkMask(picture(edge, opaqueWhite) { x, y -> if (y == 0 && x < 5) 0x00000000 else null }, edge),
        )
        // Half a percent of the pixels at ink strength is the least that is worth striking: 21 of 4096 are, 20 are not.
        assertNotNull(appearanceMarkMask(IntArray(edge * edge) { index -> if (index < 21) opaqueWhite else opaqueBlack }, edge))
        assertNull(appearanceMarkMask(IntArray(edge * edge) { index -> if (index < 20) opaqueWhite else opaqueBlack }, edge))
        // The same floor the other way round: a white picture with a tiny dark mark.
        assertNotNull(appearanceMarkMask(IntArray(edge * edge) { index -> if (index in 2_000 until 2_021) opaqueBlack else opaqueWhite }, edge))
        assertNull(appearanceMarkMask(IntArray(edge * edge) { index -> if (index in 2_000 until 2_020) opaqueBlack else opaqueWhite }, edge))
    }

    @Test
    fun `a ring is at least one pixel and only visible pixels count`() {
        // 2 x 2: 8% of 2 rounds to 0, so the ring is one pixel deep -- the whole picture.
        assertEquals(255, appearanceMarkBorderMedianLuma(intArrayOf(opaqueWhite, opaqueWhite, opaqueWhite, opaqueWhite), 2))
        assertNull("no visible pixel in the ring", appearanceMarkBorderMedianLuma(intArrayOf(0, 0, 0, 0), 2))
        assertTrue("the ring is the outer 8%", MarkBorderFraction == 0.08)
        assertEquals("the midpoint is the mask's own", 128, MarkLightBorderLuma)
    }
}
