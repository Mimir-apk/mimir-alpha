package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the pure arithmetic behind [MimirThemeSceneBackdrop] -- the miniature bake size, the one-time software
 * box blur, and the cache key that keeps a miniature bake from ever colliding with a full-size shelf bake.
 * Plain JUnit: none of this touches a bitmap or a graphics stack.
 */
class MimirThemeSceneBackdropTest {
    // ── mimirSceneBackdropBakeSize ───────────────────────────────────────────────────────────────

    @Test
    fun bakeSizeDividesAndFloors() {
        assertEquals(IntSize(480, 270), mimirSceneBackdropBakeSize(1920, 1080, 4))
        assertEquals(IntSize(479, 269), mimirSceneBackdropBakeSize(1919, 1079, 4))
        assertEquals(IntSize(1, 2), mimirSceneBackdropBakeSize(5, 7, 3))
    }

    @Test
    fun bakeSizeNeverDropsBelowOneByOne() {
        assertEquals(IntSize(1, 1), mimirSceneBackdropBakeSize(3, 2, 4))
        assertEquals(IntSize(1, 1), mimirSceneBackdropBakeSize(0, 0, 4))
        assertEquals(IntSize(1, 1), mimirSceneBackdropBakeSize(-8, -8, 2))
        assertEquals(IntSize(1, 25), mimirSceneBackdropBakeSize(1, 100, 4))
    }

    @Test
    fun bakeSizeTreatsNonPositiveDownscaleAsOne() {
        assertEquals(IntSize(100, 50), mimirSceneBackdropBakeSize(100, 50, 1))
        assertEquals(IntSize(100, 50), mimirSceneBackdropBakeSize(100, 50, 0))
        assertEquals(IntSize(100, 50), mimirSceneBackdropBakeSize(100, 50, -3))
    }

    // ── boxBlurArgb ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun radiusZeroReturnsTheInputUnchanged() {
        val input = intArrayOf(BLACK, WHITE, 0x80123456.toInt(), BLACK, WHITE, 0x00ABCDEF)
        val snapshot = input.copyOf()
        val out = boxBlurArgb(input, width = 3, height = 2, radius = 0)
        assertArrayEquals(snapshot, out)
        assertArrayEquals(snapshot, input)
    }

    @Test
    fun singleWhitePixelSpreadsOneThirdToEachNeighbourPerPass() {
        // 5 x 1: the vertical pass over a single row is an identity (every tap clamps onto the row), so the
        // result is exactly one horizontal pass -- white 255 becomes 255 / 3 = 85 (0x55) on itself and both sides.
        val input = intArrayOf(BLACK, BLACK, WHITE, BLACK, BLACK)
        val out = boxBlurArgb(input, width = 5, height = 1, radius = 1)
        assertArrayEquals(intArrayOf(BLACK, GREY_55, GREY_55, GREY_55, BLACK), out)
    }

    @Test
    fun singleWhitePixelInThreeByThreeSpreadsToOneNinthEverywhere() {
        // Horizontal pass: the middle row becomes 85 / 85 / 85. Vertical pass: every column reads 0 / 85 / 0 with
        // clamped edge taps, so every one of the nine cells lands on round(85 / 3) = 28 (0x1C).
        val input = IntArray(9) { BLACK }.also { it[4] = WHITE }
        val out = boxBlurArgb(input, width = 3, height = 3, radius = 1)
        assertArrayEquals(IntArray(9) { GREY_1C }, out)
    }

    @Test
    fun edgeTapsClampOntoTheEdgePixel() {
        // White at the left edge, radius 1: the out-of-range tap re-reads the edge pixel, so the edge keeps
        // round(2 * 255 / 3) = 170 (0xAA), its neighbour round(255 / 3) = 85, the far pixel stays black.
        val out = boxBlurArgb(intArrayOf(WHITE, BLACK, BLACK), width = 3, height = 1, radius = 1)
        assertArrayEquals(intArrayOf(GREY_AA, GREY_55, BLACK), out)
    }

    @Test
    fun outputLengthEqualsInputLength() {
        val input = IntArray(7 * 5) { i -> (0xFF shl 24) or (i * 37 and 0xFFFFFF) }
        assertEquals(input.size, boxBlurArgb(input, width = 7, height = 5, radius = 2).size)
        assertEquals(input.size, boxBlurArgb(input, width = 7, height = 5, radius = 40).size)
    }

    @Test
    fun uniformImageStaysUniform() {
        val tone = 0xFF3A7BC8.toInt()
        val input = IntArray(6 * 4) { tone }
        val out = boxBlurArgb(input, width = 6, height = 4, radius = 3)
        assertArrayEquals(input, out)
    }

    @Test
    fun channelsAverageIndependentlyAndAlphaSurvives() {
        // 3 x 1, radius 1, a half-transparent red between two half-transparent blues: alpha is uniform and must
        // stay 0x80; red and blue mix by thirds with clamped edges, green stays zero.
        val red = 0x80FF0000.toInt()
        val blue = 0x800000FF.toInt()
        val out = boxBlurArgb(intArrayOf(blue, red, blue), width = 3, height = 1, radius = 1)
        assertArrayEquals(
            intArrayOf(
                0x8055_00AA.toInt(), // (0 + 0 + 255) / 3 red, (255 + 255 + 0) / 3 blue
                0x8055_00AA.toInt(), // (0 + 255 + 0) / 3 red, (255 + 0 + 255) / 3 blue
                0x8055_00AA.toInt(),
            ),
            out,
        )
    }

    @Test
    fun mismatchedBufferIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            boxBlurArgb(IntArray(5), width = 3, height = 2, radius = 1)
        }
    }

    // ── MimirShelfBackgroundCacheKey ─────────────────────────────────────────────────────────────

    @Test
    fun cacheKeysDifferingOnlyInDownscaleAreNotEqual() {
        assertNotEquals(key(downscale = 1), key(downscale = 4))
        assertNotEquals(key(downscale = 4), key(downscale = 8))
    }

    @Test
    fun cacheKeysDifferingOnlyInBlurRadiusAreNotEqual() {
        assertNotEquals(key(blurRadiusPx = 0), key(blurRadiusPx = 2))
    }

    @Test
    fun cacheKeyDefaultsDescribeTheFullSizeUnblurredShelfBake() {
        val legacy = MimirShelfBackgroundCacheKey(
            size = Size(100f, 200f),
            density = 1f,
            layoutDirection = LayoutDirection.Ltr,
            palette = MimirThemePalette.Default,
            showAtmosphericClouds = false,
        )
        assertEquals(legacy, key(downscale = 1, blurRadiusPx = 0))
        assertEquals(legacy.hashCode(), key(downscale = 1, blurRadiusPx = 0).hashCode())
    }

    private fun key(downscale: Int = 1, blurRadiusPx: Int = 0): MimirShelfBackgroundCacheKey =
        MimirShelfBackgroundCacheKey(
            size = Size(100f, 200f),
            density = 1f,
            layoutDirection = LayoutDirection.Ltr,
            palette = MimirThemePalette.Default,
            showAtmosphericClouds = false,
            downscale = downscale,
            blurRadiusPx = blurRadiusPx,
        )

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY_55 = 0xFF555555.toInt()
        const val GREY_AA = 0xFFAAAAAA.toInt()
        const val GREY_1C = 0xFF1C1C1C.toInt()
    }
}
