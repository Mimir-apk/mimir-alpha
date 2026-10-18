package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * pad12 (): the slab is hero leather with a metal edge, the resting wall is miniature runes tooled into it and painted ONCE into a
 * cached tile, the glow under a finger is the wall's own, and the pad stands on the theme's blurred backdrop. Checked on all nine shipping palettes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h500dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirPad12Test {

    @get:Rule
    val compose = createComposeRule()

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

    private val density = Density(2f)

    private fun render(width: Int, height: Int, block: DrawScope.() -> Unit): PixelMap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat()), block)
        return bitmap.toPixelMap()
    }

    private fun PixelMap.maxAlpha(x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var m = 0f
        for (y in y0 until y1) for (x in x0 until x1) m = maxOf(m, this[x, y].alpha)
        return m
    }


    private fun measurer(): TextMeasurer {
        var captured: TextMeasurer? = null
        compose.setContent { captured = rememberTextMeasurer() }
        compose.waitForIdle()
        return captured!!
    }

    private fun layoutsOf(measurer: TextMeasurer, sp: Float): List<TextLayoutResult> {
        val style = MimirTypeScale.RunicEyebrow.copy(fontSize = sp.sp)
        return TrackpadVisuals.Runes.map { measurer.measure(AnnotatedString(it), style) }
    }

    private fun keyFor(width: Int, height: Int, palette: MimirThemePalette) = TooledRuneKey(width, height, 2f, 1f, palette)

    private fun tileFor(measurer: TextMeasurer, width: Int, height: Int, palette: MimirThemePalette): Triple<TooledRuneTile, RuneWallLayout, List<TextLayoutResult>> {
        val wall = RuneWallLayout(width.toFloat(), height.toFloat(), TrackpadVisuals.CellDp * 2f)
        val layouts = layoutsOf(measurer, TrackpadVisuals.RuneSp * 2f)
        val tile = bakeTooledRuneTile(keyFor(width, height, palette), density, LayoutDirection.Ltr, wall, layouts)
        return Triple(tile, wall, layouts)
    }

    private val noTint = tooledEmberFilter(Color.Transparent)

    // ---- the tooled wall is miniature, debossed and very low in contrast -----------------------------------------------------

    @Test
    fun theWallIsMiniatureAndDenseNotTheOldOne() {
        assertTrue("a rune is 60 to 70 percent of the old 17 sp: ${TrackpadVisuals.RuneSp}", TrackpadVisuals.RuneSp / 17f in 0.6f..0.7f)
        assertTrue("and the cell is tighter, so the wall is denser: ${TrackpadVisuals.CellDp}", TrackpadVisuals.CellDp < 42f * 0.7f)
        assertEquals("the hold circle keeps its old size", 17f, TrackpadVisuals.HoldRuneSp, 0f)
        assertEquals("the glow's reach and peak are the wall's always", 70f, TrackpadVisuals.GlowReachDp, 0f)
        assertEquals(0.9f, TrackpadVisuals.PeakAlpha, 0f)
    }

    @Test
    fun theTileIsDebossedAndVeryLowContrastOnEveryTheme() {
        val m = measurer()
        for ((name, palette) in palettes) {
            val (tile, wall, _) = tileFor(m, 400, 300, palette)
            assertEquals("$name: as wide as the field", 400, tile.image.width)
            assertEquals("$name: one wall period tall", Math.ceil(wall.period.toDouble()).toInt(), tile.image.height)
            val map = tile.image.toPixelMap()
            var painted = 0
            var darkest = 1f
            var lightest = 0f
            var strongest = 0f
            for (y in 0 until map.height) for (x in 0 until map.width) {
                val c = map[x, y]
                if (c.alpha <= 0f) continue
                painted += 1
                strongest = maxOf(strongest, c.alpha)
                darkest = minOf(darkest, c.luminance())
                lightest = maxOf(lightest, c.luminance())
            }
            val total = map.width * map.height
            assertTrue("$name: runes are tooled in: $painted of $total", painted > 200)
            assertTrue("$name: the field is mostly bare leather: $painted of $total", painted < total * 0.3f)
            assertTrue("$name: very low contrast, no pixel stronger than ${strongest}", strongest <= 0.5f)
            assertTrue("$name: a dark groove ($darkest) and a lighter lip ($lightest) make it a deboss", darkest < 0.05f && lightest > darkest + 0.02f)
        }
    }

    @Test
    fun noRuneIsCutByTheTilesSeamAndTheFirstAndLastRowsAreWhole() {
        val m = measurer()
        val (tile, wall, _) = tileFor(m, 400, 300, MimirThemePalette.Default)
        val map = tile.image.toPixelMap()
        val rowHeight = TrackpadVisuals.CellDp * 2f * 0.9f
        assertTrue("the first row sits clear of the top edge, so its glyphs are whole: ${wall.ys.min()}", wall.ys.min() > 0f)
        assertTrue("the tile's own height is the wall's period: ${map.height}", map.height >= wall.period.toInt() && map.height <= wall.period.toInt() + 1)
        assertTrue("the top rows show their runes", map.maxAlpha(0, 0, map.width, rowHeight.toInt()) > 0f)
        assertTrue("and so do the last", map.maxAlpha(0, map.height - rowHeight.toInt(), map.width, map.height) > 0f)
    }

    // ---- painted once per size and theme --------------------------------------------------------------------------------------

    @Test
    fun theTileIsBakedOncePerSizeAndThemeAndNeverPerFrame() {
        val m = measurer()
        val wall = RuneWallLayout(400f, 300f, TrackpadVisuals.CellDp * 2f)
        val layouts = layoutsOf(m, TrackpadVisuals.RuneSp * 2f)
        val cache = TooledRuneTileCache()
        val before = TooledRuneBakes.count
        fun get(key: TooledRuneKey) = cache.get(key) { bakeTooledRuneTile(key, density, LayoutDirection.Ltr, wall, layouts) }
        val a = get(keyFor(400, 300, MimirThemePalette.Default))
        repeat(50) { get(keyFor(400, 300, MimirThemePalette.Default)) }
        assertEquals("fifty more frames' asks bake nothing", before + 1, TooledRuneBakes.count)
        assertTrue("and give the very same tile", get(keyFor(400, 300, MimirThemePalette.Default)) === a)
        get(keyFor(420, 300, MimirThemePalette.Default))
        assertEquals("a new size bakes one", before + 2, TooledRuneBakes.count)
        get(keyFor(420, 300, MimirThemePalette.Freyr))
        assertEquals("a new theme bakes one", before + 3, TooledRuneBakes.count)
    }

    @Test
    fun theRestDrawIsImagesOnlyAndTheGlowOverlayDrawsNothingWhenNothingGlows() {
        val wallSource = source("MimirTrackpadTooledWall.kt")
        val rest = wallSource.substring(wallSource.indexOf("private fun DrawScope.drawTileRows("), wallSource.indexOf("internal class RuneGlowSources"))
        assertFalse("drawing the tile (drawTileRows, tooledEmberFilter, drawTooledWall) draws no text", rest.contains("drawText"))
        assertTrue("it is drawImage", rest.contains("drawImage("))
        val pad = source("MimirTrackpadPad.kt")
        val field = pad.substring(pad.indexOf("private fun PadField("), pad.indexOf("internal const val MimirTrackpadPadFieldTag"))
        assertFalse("the field draws no text of its own at rest", Regex("""\bdrawText\(""").containsMatchIn(field))
        assertTrue("the tile is held across recompositions and keyed on size and theme", field.contains("remember { TooledRuneTileCache() }") && field.contains("tiles.get(tileKey)"))
        val m = measurer()
        val (_, wall, layouts) = tileFor(m, 400, 300, MimirThemePalette.Default)
        val sources = RuneGlowSources()
        sources.gather(TrackpadPadFeedback(), 1_000L, 140f)
        assertEquals("an idle pad has no glow source", 0, sources.count)
        val idle = render(400, 300) { drawRuneGlow(wall, layouts, sources, Color.White, 0f, wall.period, 0f) }
        assertEquals("so the overlay draws nothing", 0f, idle.maxAlpha(0, 0, 400, 300), 0f)
    }

    // ---- the glow is the wall's own ----------------------------------------------------------------------------------------

    @Test
    fun theGlowSourcesAreExactlyTheOldContactTrailAndTapTests() {
        val feedback = TrackpadPadFeedback()
        feedback.down(1, 100f, 100f, 1_000L)
        feedback.noteTap(1_000L)
        val sources = RuneGlowSources()
        sources.gather(feedback, 1_000L, 140f)
        assertEquals("the finger, its trail sample and the tap flash", 3, sources.count)
        assertEquals("under the finger: full", 1f, sources.glowAt(100f, 100f), 0.0001f)
        assertEquals("at the reach: nothing", 0f, sources.glowAt(100f + 140f, 100f), 0.0001f)
        assertEquals("halfway: the old falloff", trackpadGlow(70f, 140f, 1f), sources.glowAt(100f + 70f, 100f), 0.0001f)
        feedback.up(1)
        sources.gather(feedback, 1_000L + TrackpadVisuals.GlowFadeMs / 2, 140f)
        assertTrue("a lifted finger still glows a moment through its trail", sources.glowAt(100f, 100f) > 0f)
        sources.gather(feedback, 1_000L + TrackpadVisuals.GlowFadeMs, 140f)
        assertEquals("then nothing is left to glow", 0, sources.count)
    }

    @Test
    fun aFingerLightsTheRunesNearItInTheAccentAndNoneBeyondTheReach() {
        val m = measurer()
        val (_, wall, layouts) = tileFor(m, 400, 300, MimirThemePalette.Default)
        val feedback = TrackpadPadFeedback()
        feedback.down(1, 60f, 60f, 1_000L)
        val sources = RuneGlowSources()
        sources.gather(feedback, 1_000L, 140f)
        val lit = render(400, 300) { drawRuneGlow(wall, layouts, sources, Color.White, 0f, wall.period, 0f) }
        assertTrue("runes under the finger glow strongly: ${lit.maxAlpha(30, 30, 90, 90)}", lit.maxAlpha(30, 30, 90, 90) >= 0.3f)
        assertTrue("never above the peak", lit.maxAlpha(0, 0, 400, 300) <= TrackpadVisuals.PeakAlpha + 0.02f)
        assertEquals("no rune beyond the reach glows", 0f, lit.maxAlpha(260, 0, 400, 300), 0f)
        assertEquals(0f, lit.maxAlpha(0, 220, 400, 300), 0f)
    }

    // ---- drift and scroll fade, shimmer ------------------------------------------------------------------------------------

    @Test
    fun theTileDriftsAndWrapsExactly() {
        val m = measurer()
        val (tile, _, _) = tileFor(m, 400, 300, MimirThemePalette.Default)
        val rest = render(400, 300) { drawTooledWall(tile, 0f, 0f, 0f, 0f, 0f, noTint) }
        val drifted = render(400, 300) { drawTooledWall(tile, 37f, 0f, 0f, 0f, 0f, noTint) }
        for (y in 0 until 260 step 7) for (x in 0 until 400 step 5) {
            assertEquals("the wall moved 37 px down at ($x,$y)", rest[x, y].alpha, drifted[x, y + 37].alpha, 0.001f)
        }
        val period = tile.image.height
        val wrapped = render(400, 300) { drawTooledWall(tile, period.toFloat(), 0f, 0f, 0f, 0f, noTint) }
        for (y in 0 until 300 step 11) for (x in 0 until 400 step 5) {
            assertEquals("a whole period is the wall as it was", rest[x, y].alpha, wrapped[x, y].alpha, 0.001f)
        }
    }

    @Test
    fun scrollingFadesTheWallTowardTheEdgesAndRestLeavesItWhole() {
        val m = measurer()
        val (tile, _, _) = tileFor(m, 400, 300, MimirThemePalette.Default)
        val rest = render(400, 300) { drawTooledWall(tile, 0f, 0f, 0f, 0f, 0f, noTint) }
        val scrolling = render(400, 300) { drawTooledWall(tile, 0f, 1f, 0f, 0f, 0f, noTint) }
        assertTrue("the top band fades: ${scrolling.maxAlpha(0, 0, 400, 19)} of ${rest.maxAlpha(0, 0, 400, 19)}", scrolling.maxAlpha(0, 0, 400, 19) < rest.maxAlpha(0, 0, 400, 19) * 0.7f)
        assertTrue("so does the bottom band", scrolling.maxAlpha(0, 288, 400, 300) < rest.maxAlpha(0, 288, 400, 300) * 0.5f || rest.maxAlpha(0, 288, 400, 300) == 0f)
        assertEquals("the middle is as it was", rest.maxAlpha(0, 130, 400, 170), scrolling.maxAlpha(0, 130, 400, 170), 0.02f)
    }

    @Test
    fun theShimmerIsTheLifesAndRestOrReducedMotionDrawsThePlainTile() {
        val m = measurer()
        val palette = MimirThemePalette.Default
        val (tile, _, _) = tileFor(m, 400, 300, palette)
        val tint = tooledEmberFilter(palette.emberHot)
        val rest = render(400, 300) { drawTooledWall(tile, 0f, 0f, 0.3f, 0f, 0f, tint) }
        val plain = render(400, 300) { drawTooledWall(tile, 0f, 0f, 0.8f, 0f, 0f, tint) }
        for (y in 0 until 300 step 9) for (x in 0 until 400 step 7) {
            assertEquals("with the life at rest the phase changes nothing at ($x,$y)", rest[x, y].alpha, plain[x, y].alpha, 0f)
        }
        val shimmering = render(400, 300) { drawTooledWall(tile, 0f, 0f, 0.3f, TrackpadVisuals.ShimmerAmplitude, TrackpadVisuals.ShimmerTint, tint) }
        var differs = 0
        for (y in 0 until 300 step 3) for (x in 0 until 400 step 3) if (shimmering[x, y] != rest[x, y]) differs += 1
        assertTrue("in its window the bands breathe: $differs pixels differ", differs > 50)
    }

    // ---- the leather slab and the metal edge ---------------------------------------------------------------------------------

    @Test
    fun theSlabIsTheHeroLeatherWithAMetalEdgeOnEveryTheme() {
        val slab = source("MimirTrackpadSlab.kt")
        assertTrue("the plates' own ground stops and hero cover", slab.contains("MimirMetalGroundTopStop to hero.coverTop") && slab.contains("MimirMetalGroundBottomStop to hero.coverBottom"))
        val surface = slab.substring(slab.indexOf("internal fun Modifier.boundSlabSurface("), slab.indexOf("internal fun Modifier.boundSlabIron("))
        assertFalse("no longer the flat surface ramp", surface.contains("palette.surfaceHigh") || surface.contains("radialGradient"))
        assertTrue("the frame is the plates' metal brush at their strength", slab.contains("platformConsoleIconBrush(palette)") && slab.contains("alpha = MimirMetalRestAlpha"))
        for ((name, palette) in palettes) {
            val hero = palette.materials.hero
            assertTrue("$name: a leather ground exists", hero.coverTop.alpha > 0f && hero.coverBottom.alpha > 0f)
        }
    }

    // ---- the blurred theme backdrop ---------------------------------------------------------------------------------------------

    @Test
    fun thePadStandsOnTheThemesBlurredBackdropWithAFlatFallbackAndAScrim() {
        val pad = source("MimirTrackpadPad.kt")
        assertEquals("one backdrop", 1, Regex("""mimirThemeSceneBackdrop\(""").findAll(pad).count())
        val ground = pad.indexOf(".background(palette.surfaceBase)\n            .mimirThemeSceneBackdrop(palette, quietSceneLight = true, shineStars = false)")
        assertTrue("the flat ground first (the fallback), then the quiet speck-free scene the Bottom shelf bakes", ground >= 0)
        val scrim = pad.indexOf(".background(palette.surfaceBase.copy(alpha = palette.surfaceBase.alpha * PadBackdropScrimAlpha))")
        assertTrue("then the scrim of the theme's ground, before the padding", scrim > ground && pad.indexOf(".padding(MimirSpacing.sm)", scrim) > scrim)
        assertFalse("no per-frame blur of its own: the backdrop is blurred once, by the shared bake", pad.contains("RenderEffect") || pad.contains("Modifier.blur") || pad.contains(".blur("))
        assertFalse("and nothing animated: no shining stars", pad.contains("shineStars = true"))
        val atmosphere = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirAtmosphere.kt").readText()
        assertTrue("the backdrop bakes a miniature and blurs it once", atmosphere.contains("bakeSceneBackdropMiniature") && atmosphere.contains("boxBlurArgb"))
    }

    // ---- composed: nothing re-bakes, nothing animates at rest -------------------------------------------------------------

    private var padPalette by mutableStateOf(MimirThemePalette.Default)

    /** Draws the hosting view into a throwaway bitmap, which is what runs a draw cache (captureToImage waits on a redraw Robolectric never delivers). */
    private fun drawNow(view: android.view.View) {
        val root = view.rootView
        val bitmap = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
    }

    private class Host : MimirTrackpadHost {
        override val serviceConnected: Boolean = true
        override val speedStep: Int get() = TrackpadSpeed.Default
        override fun setSpeed(step: Int): Int = TrackpadSpeed.clamp(step)
        override fun begin(): MimirTrackpadHost = this
        override fun pointerMove(dx: Float, dy: Float) = Unit
        override fun click() = Unit
        override fun doubleClick() = Unit
        override fun scrollBy(dx: Float, dy: Float) = Unit
        override fun scrollEnd() = Unit
        override fun scrollCancel() = Unit
        override fun holdBegin() = Unit
        override fun holdMove(dx: Float, dy: Float) = Unit
        override fun holdEnd() = Unit
        override fun openAccessibilitySettings() = Unit
        override fun end() = Unit
    }

    @Test
    fun thePadPaintsItsWallOnceTouchesNeverRebakeItAndATrueThemeChangeBakesTheNewOne() {
        padPalette = MimirThemePalette.Default
        val before = TooledRuneBakes.count
        var host: android.view.View? = null
        compose.setContent {
            host = LocalView.current
            MimirTheme {
                CompositionLocalProvider(LocalMimirPalette provides padPalette) {
                    MimirTrackpadPad(session = Host(), keyboardWaiting = false, onKeyboard = {}, onClose = {}, palette = padPalette)
                }
            }
        }
        compose.waitForIdle()
        drawNow(host!!)
        val first = TooledRuneBakes.count
        assertTrue("the wall was painted at least once: ${first - before}", first > before)
        repeat(3) {
            compose.onNodeWithTag(MimirTrackpadPadFieldTag).performTouchInput { swipeUp() }
            compose.waitForIdle()
            drawNow(host!!)
        }
        assertEquals("touching, scrolling and the ember frames paint nothing again", first, TooledRuneBakes.count)
        padPalette = MimirThemePalette.Freyr
        compose.waitForIdle()
        drawNow(host!!)
        val second = TooledRuneBakes.count
        assertTrue("a theme change bakes the new theme's tile: ${second - first}", second > first)
        compose.onNodeWithTag(MimirTrackpadPadFieldTag).performTouchInput { swipeUp() }
        compose.waitForIdle()
        drawNow(host!!)
        assertEquals("and then it is cached again", second, TooledRuneBakes.count)
    }
}
