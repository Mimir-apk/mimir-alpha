package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import kotlin.math.hypot
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
 * Observed in the Mimir-button picker: the stages are a strip, and the Default theme's sun anchors lower right of a strip -- on top of the
 * "ACTUAL SIZE" and "ON A BRIGHT GAME" captions. `MimirShelfBackground(sceneLight = false)` takes the sun and its ring dust out of that one scene;
 * true, the default, is every other surface exactly as it was.
 *
 * The first test draws the real Well painter and reads the sun's disc; the others pin that the flag is a different raster, travels the same road
 * as `quietSceneLight` (the key, then the bake reading the key), and leaves the stars and the nebula alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirSceneLightOffTest {

    private val width = 640
    private val height = 200
    private val palette = MimirThemePalette.forId(MimirThemeId.DEFAULT)

    private fun paint(sceneLight: Boolean, quiet: Boolean = false): IntArray {
        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1.5f, 1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
            drawThemeSceneV2(palette, quietSceneLight = quiet, sceneLight = sceneLight)
        }
        return IntArray(width * height).also { image.readPixels(it, 0, 0, width, height, 0, width) }
    }

    private fun luma(argb: Int): Double =
        0.2126 * ((argb shr 16) and 0xFF) + 0.7152 * ((argb shr 8) and 0xFF) + 0.0722 * (argb and 0xFF)

    private fun meanInSun(px: IntArray): Double {
        val sun = wellSunGeometry(width.toFloat(), height.toFloat(), belowText = false)
        var sum = 0.0
        var count = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (hypot(x - sun.cx, y - sun.cy) <= sun.radius * 0.8f) {
                sum += luma(px[y * width + x])
                count++
            }
        }
        check(count > 0) { "the sun's disc is on the strip" }
        return sum / count
    }

    @Test
    fun `without the scene light the sun is gone from the strip and with it the sun is as bright as ever`() {
        val on = meanInSun(paint(sceneLight = true))
        val off = meanInSun(paint(sceneLight = false))
        assertTrue("the Default sun is bright to begin with ($on)", on > 60.0)
        assertTrue("and the field under it is dark when the light is out ($off, against $on)", off < 30.0 && off < on / 3.0)
    }

    @Test
    fun `true is the painter exactly as it was, and the light off is not the quiet light`() {
        assertTrue("the default is every surface unchanged", paint(sceneLight = true).contentEquals(paint(sceneLight = true, quiet = false)))
        val quiet = meanInSun(paint(sceneLight = true, quiet = true))
        val off = meanInSun(paint(sceneLight = false))
        assertTrue("the quiet light is half, the light off is none ($quiet vs $off)", off < quiet)
    }

    @Test
    fun `the light off leaves the stars and the nebula where they were`() {
        val on = paint(sceneLight = true)
        val off = paint(sceneLight = false)
        val sun = wellSunGeometry(width.toFloat(), height.toFloat(), belowText = false)
        // Far from the sun and its ring dust the two rasters are the same pixels: only the light moved.
        var differing = 0
        var farPixels = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (hypot(x - sun.cx, y - sun.cy) > height.toFloat() * 1.4f) {
                farPixels++
                if (on[y * width + x] != off[y * width + x]) differing++
            }
        }
        assertTrue("some of the field is far from the light", farPixels > 1000)
        assertEquals("nothing but the sun and its dust changed", 0, differing)
    }

    @Test
    fun `a bake with the light off is its own raster, never a palette swap`() {
        val full = MimirShelfBackgroundCacheKey(
            size = Size(640f, 200f),
            density = 1.5f,
            layoutDirection = LayoutDirection.Ltr,
            palette = palette,
            showAtmosphericClouds = true,
        )
        val dark = full.copy(sceneLight = false)
        assertTrue("every existing surface bakes the light", full.sceneLight)
        assertNotEquals(full, dark)
        assertFalse(full.differsOnlyInPaletteFrom(dark))
    }

    @Test
    fun `the flag travels the quiet light's road and only the two stages turn it off`() {
        val atmosphere = source("MimirAtmosphere.kt")
        val shelf = atmosphere.substringAfter("fun MimirShelfBackground(").substringBefore("@Composable\nfun MimirThemeSceneBackdrop")
        assertTrue("a parameter, true by default", Regex("""sceneLight: Boolean = true,""").containsMatchIn(shelf))
        assertTrue("in the key", Regex("""sceneLight = sceneLight,\s+ground = ground,""").containsMatchIn(shelf))
        val bake = atmosphere.substringAfter("private fun bakeShelfBackgroundRaster(").substringBefore("return MimirShelfBackgroundCacheBuild(")
        assertTrue("the bake reads the key it is cached under", bake.contains("sceneLight = key.sceneLight,"))
        val miniature = atmosphere.substringAfter("internal fun bakeSceneBackdropMiniature(").substringBefore("val softened")
        assertFalse("the Bottom's and the compact browser's miniatures never see it", miniature.contains("sceneLight"))
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
        File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
    ).first(File::isFile).readText()
}
