package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The player's background has no readability floor : the veil over the image is drawn at exactly the dim the player stored, however bright the
 * image. This reads it off the real bake's pixels -- [bakeSceneBackdropMiniature], the one body the shelf and the Bottom both run -- so a veil that is raised for a
 * bright image, or any floor under the stored dim, fails here whatever the source looks like (it used to be a pin on the source text, which a re-added forced dim
 * could have passed).
 *
 * **It is its own class, and it writes no Compose state.** `MimirSceneGroundTest` writes `MimirAppearanceOverrides` (snapshot state) to test `customizes()`; run inside
 * Robolectric's sandbox those global-snapshot writes were never applied, and every Compose-rule test that ran after it in the same JVM then failed with
 * AppNotIdleException. This class only builds bitmaps and palettes and reads pixels back, so it leaves nothing process-wide behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirSceneGroundVeilRenderTest {

    private fun ground(p90: Float, dim: MimirSceneGroundDim) =
        MimirSceneGround(id = "veil", path = "/data/appearance/backgrounds/veil.jpg", width = 16, height = 16, p90 = p90, dim = dim, blur = MimirSceneGroundBlur.OFF)

    /** A solid [color] square, the "image" a ground is made of. */
    private fun solid(color: Color, edge: Int = 16): ImageBitmap {
        val image = ImageBitmap(edge, edge)
        Canvas(image).drawRect(Rect(0f, 0f, edge.toFloat(), edge.toFloat()), Paint().apply { this.color = color })
        return image
    }

    /** The real bake of [ground] over [source] on [palette], read back at its centre as (r, g, b) in 0..255. No blur and no downscale: the image under the veil, nothing else. */
    private fun bakedCentre(source: ImageBitmap, ground: MimirSceneGround, palette: MimirThemePalette): IntArray {
        val edge = source.width
        val built = bakeSceneBackdropMiniature(
            bakeSize = IntSize(edge, edge),
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            stars = emptyList(),
            palette = palette,
            blurRadiusPx = 0,
            ground = ground,
            groundImageOf = { source },
        )
        val pixels = IntArray(edge * edge)
        built.bitmap.readPixels(pixels, 0, 0, edge, edge, 0, edge)
        val argb = pixels[(edge / 2) * edge + edge / 2]
        return intArrayOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }

    /** What an opaque [image] channel (0..255) becomes under a veil of colour channel [veil] (0..1) at [alpha]: the veil weighted by [alpha], the image by the rest. */
    private fun veiled(image: Int, veil: Float, alpha: Float): Int = (image * (1f - alpha) + veil * 255f * alpha).roundToInt()

    private fun assertVeiled(message: String, image: Int, palette: MimirThemePalette, dim: MimirSceneGroundDim, actual: IntArray) {
        val base = palette.surfaceBase
        val expected = intArrayOf(veiled(image, base.red, dim.alpha), veiled(image, base.green, dim.alpha), veiled(image, base.blue, dim.alpha))
        // Two of 255 for the 8-bit blend's own rounding; the steps between dims are 25 or more apart at white, so a wrong veil cannot hide in it.
        actual.indices.forEach { channel ->
            assertTrue("$message: channel $channel should be ${expected[channel]} (veil at ${dim.alpha}), was ${actual[channel]}", abs(actual[channel] - expected[channel]) <= 2)
        }
    }

    @Test fun `the veil draws at the stored dim over the brightest image, on every theme`() {
        // A white image (p90 1.0, the brightest a ground can be) under the LIGHT veil reads as white weighted 0.35 toward the theme's base. A veil raised for a
        // bright image would darken it past this.
        MimirThemeId.entries.forEach { themeId ->
            val palette = MimirThemePalette.forId(themeId)
            assertVeiled("white under LIGHT on $themeId", 255, palette, MimirSceneGroundDim.LIGHT, bakedCentre(solid(Color.White), ground(p90 = 1f, dim = MimirSceneGroundDim.LIGHT), palette))
        }
        // A dark image at the same dim is the same veil over black: the veil does not depend on the image.
        val palette = MimirThemePalette.forId(MimirThemeId.DEFAULT)
        assertVeiled("black under LIGHT", 0, palette, MimirSceneGroundDim.LIGHT, bakedCentre(solid(Color.Black), ground(p90 = 0f, dim = MimirSceneGroundDim.LIGHT), palette))
        assertEquals("the model keeps the choice as stored", MimirSceneGroundDim.LIGHT, ground(p90 = 1f, dim = MimirSceneGroundDim.LIGHT).dim)
    }

    @Test fun `every dim step draws its own veil over a bright image and each is darker than the one before`() {
        val palette = MimirThemePalette.forId(MimirThemeId.DEFAULT)
        val source = solid(Color.White)
        var previousBrightness = Int.MAX_VALUE
        MimirSceneGroundDim.entries.forEach { dim ->
            val pixel = bakedCentre(source, ground(p90 = 1f, dim = dim), palette)
            assertVeiled("white under $dim", 255, palette, dim, pixel)
            val brightness = pixel.sum()
            assertTrue("$dim (${pixel.toList()}) must be darker than the step before it", brightness < previousBrightness)
            previousBrightness = brightness
        }
    }
}
