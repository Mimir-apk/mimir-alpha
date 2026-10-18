package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bottom backdrop coverage: the Bottom's Default-theme backdrop bakes the sun at the quiet
 * light and without the diffraction-spike layer, and Main's miniature (the compact browser) stays byte-for-byte what it was.
 *
 * This renders the real miniature -- [bakeSceneBackdropMiniature], the one body both the UI-thread and the off-thread bake run
 * -- at the Bottom's 1/4 scale and reads its pixels. The numbers each case prints (`BottomBackdrop ...`) are the ones cited in
 * the captured comparison.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirBottomBackdropBakeTest {

    // A Thor-Bottom-sized surface at density 3, baked at the backdrop's own default 1/4 and 2 px blur.
    private val surfaceW = 1200
    private val surfaceH = 1080
    private val scale = 4
    private val bakeSize = IntSize(surfaceW / scale, surfaceH / scale)
    private val bakeDensity = Density(density = 3f / scale, fontScale = 1f)
    private val palette = MimirThemePalette.forId(MimirThemeId.DEFAULT)
    private val stars = generateStarfield()

    private fun bake(quiet: Boolean, shine: Boolean): IntArray {
        val built = bakeSceneBackdropMiniature(
            bakeSize = bakeSize,
            density = bakeDensity,
            layoutDirection = LayoutDirection.Ltr,
            stars = stars,
            palette = palette,
            blurRadiusPx = 2,
            ground = null,
            groundImageOf = { null },
            quietSceneLight = quiet,
            shineStars = shine,
        )
        return pixels(built.bitmap)
    }

    private fun pixels(image: ImageBitmap): IntArray {
        val out = IntArray(image.width * image.height)
        image.readPixels(out, 0, 0, image.width, image.height, 0, image.width)
        return out
    }

    private fun luma(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Peak luma inside the sun's disc (the miniature's own px). */
    private fun sunPeak(px: IntArray): Double {
        val sun = wellSunGeometry(bakeSize.width.toFloat(), bakeSize.height.toFloat(), belowText = false)
        var peak = 0.0
        for (y in 0 until bakeSize.height) for (x in 0 until bakeSize.width) {
            if (hypot(x - sun.cx, y - sun.cy) <= sun.radius) peak = maxOf(peak, luma(px[y * bakeSize.width + x]))
        }
        return peak
    }

    private fun peak(px: IntArray): Double = px.maxOf(::luma)

    private fun log(line: String) = println("BottomBackdrop $line")

    @Test
    fun `the quiet bake lowers the sun's peak luminance`() {
        val full = bake(quiet = false, shine = false)
        val quiet = bake(quiet = true, shine = false)
        val fullSun = sunPeak(full)
        val quietSun = sunPeak(quiet)
        log("sun peak luma (0-255) full=${"%.1f".format(fullSun)} quiet=${"%.1f".format(quietSun)} " +
            "field peak full=${"%.1f".format(peak(full))} quiet=${"%.1f".format(peak(quiet))}")
        assertTrue("the Default sun is bright to begin with ($fullSun)", fullSun > 60.0)
        // Half the light: the sun's peak falls by at least a quarter of itself (the field under it does not dim).
        assertTrue("the sun's peak luma must drop: $fullSun -> $quietSun", quietSun < fullSun * 0.8)
        assertTrue(peak(quiet) < peak(full))
    }

    @Test
    fun `dropping the shine layer removes the specks and nothing else`() {
        val withShine = bake(quiet = true, shine = true)
        val withoutShine = bake(quiet = true, shine = false)

        // Where the five shining stars land on the miniature, and how far one speck can reach: spike half-length 6 dp
        // and halo 3.5 dp at the miniature's density, plus the 2 px box blur (taken three times by a separable 2-radius pass).
        val centres = wellShiningStarFractions().map { (fx, fy) -> fx * bakeSize.width to fy * bakeSize.height }
        val reach = 6f * (3f / scale) + 2f + 1f
        var changed = 0
        var changedOutside = 0
        var biggestDelta = 0.0
        for (y in 0 until bakeSize.height) for (x in 0 until bakeSize.width) {
            val i = y * bakeSize.width + x
            if (withShine[i] == withoutShine[i]) continue
            changed++
            biggestDelta = maxOf(biggestDelta, luma(withShine[i]) - luma(withoutShine[i]))
            if (centres.none { (cx, cy) -> hypot(x - cx, y - cy) <= reach + 1f }) changedOutside++
        }
        val atStars = centres.map { (cx, cy) ->
            val i = cy.roundToInt().coerceIn(0, bakeSize.height - 1) * bakeSize.width +
                cx.roundToInt().coerceIn(0, bakeSize.width - 1)
            luma(withShine[i]) - luma(withoutShine[i])
        }
        log("shine layer: stars=${centres.size} changedPx=$changed outsideStarReach=$changedOutside " +
            "peakDeltaLuma=${"%.2f".format(biggestDelta)} deltaAtStarCentres=${atStars.joinToString { "%.2f".format(it) }}")

        assertEquals(5, centres.size)
        assertTrue("the shine layer paints specks on the default bake", changed > 0)
        assertEquals("the layer touches only the pixels around the five stars", 0, changedOutside)
        assertTrue("each speck brightens its star's pixel", atStars.all { it > 0.0 })
        // The dropped bake is the one the Bottom shows: no pixel of it is brighter than the shine bake's anywhere.
        assertTrue(withoutShine.indices.all { luma(withoutShine[it]) <= luma(withShine[it]) + 1e-9 })
    }

    @Test
    fun `Main's miniature bake is unchanged by default`() {
        val explicit = bake(quiet = false, shine = true)
        val default = pixels(
            bakeSceneBackdropMiniature(
                bakeSize, bakeDensity, LayoutDirection.Ltr, stars, palette, 2, null, { null },
            ).bitmap,
        )
        assertTrue("the default arguments are the full-light, spiked bake Main has always had", explicit.contentEquals(default))
        assertFalse("and the Bottom's bake is a different raster", explicit.contentEquals(bake(quiet = true, shine = false)))
    }

    @Test
    fun `the Bottom's bake is its own raster in the cache`() {
        val main = MimirShelfBackgroundCacheKey(
            size = Size(surfaceW.toFloat(), surfaceH.toFloat()),
            density = 3f,
            layoutDirection = LayoutDirection.Ltr,
            palette = palette,
            showAtmosphericClouds = true,
            downscale = scale,
            blurRadiusPx = 2,
        )
        val bottom = main.copy(quietSceneLight = true, shineStars = false)
        assertTrue("Main's key keeps the spikes", main.shineStars)
        assertNotEquals(main, bottom)
        assertFalse("a Bottom bake is never a palette swap of Main's", main.differsOnlyInPaletteFrom(bottom))
        assertFalse(main.copy(shineStars = false).differsOnlyInPaletteFrom(main))
    }

}
