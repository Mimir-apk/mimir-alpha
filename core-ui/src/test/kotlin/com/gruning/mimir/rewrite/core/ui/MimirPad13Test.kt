package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pad window's header plates (Back, Keys, Close and the speed gauge's
 * housing) are RAISED: a cast shadow under them, a bevel and convex shading, sinking when pressed. It is an opt-in; every other plate, the media
 * transport above all, is drawn exactly as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirPad13Test {

    private val palettes = listOf(
        "Default" to MimirThemePalette.Default,
        "Freyr" to MimirThemePalette.Freyr,
        "Surtr" to MimirThemePalette.Surtr,
        "Mani" to MimirThemePalette.Mani,
        "Hel" to MimirThemePalette.Hel,
        "Niflheim" to MimirThemePalette.Niflheim,
        "Yggdrasil" to MimirThemePalette.Yggdrasil,
        "Folkvangr" to MimirThemePalette.Folkvangr,
        "Baldr" to MimirThemePalette.Baldr,
    )

    private fun source(name: String) = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name").readText()

    /** A 100 x 40 px plate at (30, 30) of a 160 x 100 px canvas, at a density of 2 (so the shadow's 1.75 dp is 3.5 px). */
    private fun plate(palette: MimirThemePalette, raised: Boolean, pressed: Boolean): PixelMap {
        val bitmap = ImageBitmap(160, 100)
        CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(bitmap), Size(160f, 100f)) {
            val metal = platformConsoleIconBrush(palette)
            inset(30f) {
                drawMetalPlateWithEmber(palette, metal, 28f, warmed = pressed, ember = 0f, raised = raised, pressed = pressed)
            }
        }
        return bitmap.toPixelMap()
    }

    @Test
    fun aRaisedPlateCastsAShadowBelowItAndAFlatOneDoesNotOnEveryTheme() {
        for ((name, palette) in palettes) {
            val flat = plate(palette, raised = false, pressed = false)
            val raised = plate(palette, raised = true, pressed = false)
            assertEquals("$name: a flat plate casts nothing below it", 0f, flat[80, 72].alpha, 0f)
            assertEquals("$name: a flat plate casts nothing further down", 0f, flat[80, 78].alpha, 0f)
            assertTrue("$name: the raised plate's shadow is under it: ${raised[80, 72].alpha}", raised[80, 72].alpha > 0.2f)
            assertTrue("$name: and it is soft, still there a few px further out: ${raised[80, 78].alpha}", raised[80, 78].alpha > 0f)
            assertTrue("$name: and fading: ${raised[80, 78].alpha} under ${raised[80, 72].alpha}", raised[80, 78].alpha < raised[80, 72].alpha)
            assertEquals("$name: it does not climb the plate's sides much, none above the top edge", 0f, raised[80, 22].alpha, 0.2f)
            assertEquals("$name: it ends within the header's 7 dp round the plate (14 px): ${raised[80, 86].alpha}", 0f, raised[80, 86].alpha, 0f)
        }
    }

    @Test
    fun aRaisedPlateHasABrightTopEdgeAndADarkBottomEdgeOnEveryTheme() {
        for ((name, palette) in palettes) {
            val flat = plate(palette, raised = false, pressed = false)
            val raised = plate(palette, raised = true, pressed = false)
            assertTrue("$name: the top edge catches the light: ${raised[80, 34].luminance()} over ${flat[80, 34].luminance()}", raised[80, 34].luminance() > flat[80, 34].luminance())
            assertTrue("$name: the bottom edge is in shadow: ${raised[80, 66].luminance()} under ${flat[80, 66].luminance()}", raised[80, 66].luminance() < flat[80, 66].luminance())
            assertTrue("$name: so the top edge is brighter than the bottom: ${raised[80, 34].luminance()} vs ${raised[80, 66].luminance()}", raised[80, 34].luminance() > raised[80, 66].luminance())
            assertEquals("$name: the plate is the same size, the metal rim is still its edge", flat[80, 31].luminance(), raised[80, 31].luminance(), 0.0001f)
        }
    }

    @Test
    fun aPressedPlateSinksItsShadowCollapsesAndItsBevelInverts() {
        for ((name, palette) in palettes) {
            val rest = plate(palette, raised = true, pressed = false)
            val pressed = plate(palette, raised = true, pressed = true)
            assertTrue("$name: the shadow collapses: ${pressed[80, 76].alpha} under ${rest[80, 76].alpha}", pressed[80, 76].alpha < rest[80, 76].alpha)
            assertEquals("$name: to about half a dp (1 px) and a small blur, gone 8 px out", 0f, pressed[80, 78].alpha, 0f)
            assertTrue("$name: the inner shadow moves to the top: ${pressed[80, 34].luminance()} under ${rest[80, 34].luminance()}", pressed[80, 34].luminance() < rest[80, 34].luminance())
            assertTrue("$name: and the light to the bottom: ${pressed[80, 66].luminance()} over ${rest[80, 66].luminance()}", pressed[80, 66].luminance() > rest[80, 66].luminance())
            assertTrue("$name: pressed differs from rest", (0 until 100).any { pressed[80, it] != rest[80, it] })
        }
    }

    @Test
    fun theDepthIsAnOptInOnlyThePadWindowsHeaderTakesAndNoOtherPlateChanges() {
        val metal = source("MimirMetalPlate.kt")
        assertTrue("off by default", metal.contains("raised: Boolean = false") && !metal.contains("raised: Boolean = true"))
        assertEquals("the one shared plate drawing is not touched by the depth", 1, Regex("""fun DrawScope\.drawMimirMetalPlate\(""").findAll(metal).count())
        val pad = source("MimirTrackpadPad.kt")
        assertEquals("the pad window header's plates and the gauge housing are raised", 2, Regex("""raised = true""").findAll(pad).count())
        assertEquals("their content sinks with them", 2, Regex("""mimirRaisedPressNudge\(pressed\)""").findAll(pad).count())
        assertFalse("the Bottom shelf header's Trackpad plate is not raised", source("MimirBottomTrackpadPlate.kt").contains("raised"))
        val transport = File("../app/src/main/kotlin/com/gruning/mimir/rewrite/MediaTransportButtons.kt").readText()
        assertFalse("nor is the media transport", transport.contains("raised") || transport.contains("drawRaisedPlate"))
    }

    @Test
    fun theDepthIsStaticAndDrawPhaseOnlyWithNoClockOfItsOwn() {
        val metal = source("MimirMetalPlate.kt")
        val raised = metal.substring(metal.indexOf("internal fun DrawScope.drawRaisedPlateShadow("), metal.indexOf("internal fun DrawScope.drawMetalPlateWithEmber("))
        for (clock in listOf("withFrameNanos", "withInfiniteAnimationFrameNanos", "animate", "Animatable", "rememberInfiniteTransition", "LaunchedEffect", "SystemClock")) {
            assertFalse("the raised drawing has no $clock", raised.contains(clock))
        }
        assertFalse("no hex literal", Regex("""Color\(\s*0x""").containsMatchIn(raised))
        assertFalse("no blur effect, the shadow is stepped rings", raised.contains("RenderEffect") || raised.contains(".blur("))
        assertTrue("the press nudge reads the state in the layer block", raised.contains("graphicsLayer {") && raised.contains("pressed.value"))
        assertTrue("the shadow's strength is the slab's", MimirRaisedShadowAlpha == 0.45f)
        assertTrue("the shadow fits the 7 dp the header leaves round a plate", MimirRaisedShadowOffsetDp + MimirRaisedShadowBlurDp <= 7f)
        assertTrue("the bevel is 1 to 1.5 dp", MimirRaisedBevelDp in 1f..1.5f)
    }
}
