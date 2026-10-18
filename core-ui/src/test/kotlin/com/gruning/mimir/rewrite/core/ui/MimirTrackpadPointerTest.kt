package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trackpad pointer's choices : Small 18 / Medium 26 / Large 34 dp, a slim arrow or a dot, white or the
 * theme's metal or the accent. The choices, their defaults, their sizes, where each shape's window goes for a click, and the
 * fills are pure and pinned here; the drawing is pinned beside the window in the app module.
 */
class MimirTrackpadPointerTest {

    @Test
    fun theDefaultIsSmallSlimArrowWhite() {
        val default = TrackpadPointerStyle.Default
        assertEquals(TrackpadPointerSize.SMALL, default.size)
        assertEquals(TrackpadPointerShape.SLIM_ARROW, default.shape)
        assertEquals(TrackpadPointerColor.WHITE, default.color)
        assertEquals(default, TrackpadPointerStyle())
    }

    @Test
    fun nothingStoredAndNothingKnownReadAsTheDefaults() {
        assertEquals(TrackpadPointerSize.SMALL, TrackpadPointerSize.fromStored(null))
        assertEquals(TrackpadPointerShape.SLIM_ARROW, TrackpadPointerShape.fromStored(null))
        assertEquals(TrackpadPointerColor.WHITE, TrackpadPointerColor.fromStored(null))
        // A name a later or an earlier build wrote (the classic arrow, say) is not an error, it is the default.
        assertEquals(TrackpadPointerShape.SLIM_ARROW, TrackpadPointerShape.fromStored("CLASSIC_ARROW"))
        assertEquals(TrackpadPointerSize.SMALL, TrackpadPointerSize.fromStored("HUGE"))
        assertEquals(TrackpadPointerColor.WHITE, TrackpadPointerColor.fromStored(""))
        TrackpadPointerSize.entries.forEach { assertEquals(it, TrackpadPointerSize.fromStored(it.name)) }
        TrackpadPointerShape.entries.forEach { assertEquals(it, TrackpadPointerShape.fromStored(it.name)) }
        TrackpadPointerColor.entries.forEach { assertEquals(it, TrackpadPointerColor.fromStored(it.name)) }
    }

    @Test
    fun theSizesAreEighteenTwentySixAndThirtyFourDp() {
        assertEquals(listOf(18, 26, 34), TrackpadPointerSize.entries.map { it.dp })
        assertEquals(listOf("Small", "Medium", "Large"), TrackpadPointerSize.entries.map { it.label })
    }

    @Test
    fun onlyTwoShapesAreOfferedAndTheClassicArrowIsNotOne() {
        assertEquals(listOf("Slim arrow", "Dot"), TrackpadPointerShape.entries.map { it.label })
        assertEquals(2, TrackpadPointerShape.entries.size)
    }

    @Test
    fun theColorsAreWhiteThemeMetalAndAccent() {
        assertEquals(listOf("White", "Theme metal", "Accent"), TrackpadPointerColor.entries.map { it.label })
    }

    @Test
    fun eachChoiceCyclesThroughItsOptionsAndWraps() {
        assertEquals(
            listOf(TrackpadPointerSize.MEDIUM, TrackpadPointerSize.LARGE, TrackpadPointerSize.SMALL),
            generateSequence(TrackpadPointerSize.SMALL) { it.next() }.drop(1).take(3).toList(),
        )
        assertEquals(TrackpadPointerShape.DOT, TrackpadPointerShape.SLIM_ARROW.next())
        assertEquals(TrackpadPointerShape.SLIM_ARROW, TrackpadPointerShape.DOT.next())
        assertEquals(
            listOf(TrackpadPointerColor.THEME_METAL, TrackpadPointerColor.ACCENT, TrackpadPointerColor.WHITE),
            generateSequence(TrackpadPointerColor.WHITE) { it.next() }.drop(1).take(3).toList(),
        )
    }

    // ---- the window --------------------------------------------------------------------------------------------------

    @Test
    fun theArrowsWindowIsTheSizeInPixelsAndTheDotsIsSmallerAndEven() {
        for (density in listOf(1f, 2f, 2.75f, 3f)) {
            for (size in TrackpadPointerSize.entries) {
                val arrow = TrackpadPointerGeometry.windowPx(TrackpadPointerShape.SLIM_ARROW, size, density)
                val dot = TrackpadPointerGeometry.windowPx(TrackpadPointerShape.DOT, size, density)
                assertEquals("${size.dp} dp at $density", maxOf(16, (size.dp * density).toInt()), arrow)
                assertEquals("the dot's center is a pixel line at ${size.dp} dp, $density", 0, dot % 2)
                assertTrue("the dot is smaller than the arrow at ${size.dp} dp, $density: $dot vs $arrow", dot < arrow)
                assertTrue("and not by a vanishing amount", dot >= 12)
            }
        }
        // The three sizes stay distinct for both shapes, on the densest and the sparsest screen.
        for (shape in TrackpadPointerShape.entries) {
            for (density in listOf(1f, 3f)) {
                val sizes = TrackpadPointerSize.entries.map { TrackpadPointerGeometry.windowPx(shape, it, density) }
                assertEquals("$shape at $density: $sizes", sizes.sorted().distinct(), sizes)
            }
        }
    }

    @Test
    fun theEdgeScalesWithTheWindowAndNeverVanishes() {
        for (shape in TrackpadPointerShape.entries) {
            val small = TrackpadPointerGeometry.outlinePx(shape, 20)
            val large = TrackpadPointerGeometry.outlinePx(shape, 120)
            assertTrue("$shape: the edge is at least 1.5 px", small >= 1.5f)
            assertTrue("$shape: the edge grows with the window ($small, $large)", large > small)
        }
    }

    @Test
    fun theArrowsWindowSitsOnePixelDownRightOfTheClickSoItNeverCoversIt() {
        for ((x, y) in listOf(0f to 0f, 100f to 200f, 1919f to 1079f, 5.9f to 7.2f, 0.4f to 0.4f)) {
            val (originX, originY) = TrackpadPointerGeometry.origin(TrackpadPointerShape.SLIM_ARROW, x, y, 70)
            assertTrue("x origin $originX must clear $x", originX > x)
            assertTrue("y origin $originY must clear $y", originY > y)
            assertTrue("and stay beside the tip", originX - x <= 2f && originY - y <= 2f)
        }
        assertEquals(101 to 201, TrackpadPointerGeometry.origin(TrackpadPointerShape.SLIM_ARROW, 100f, 200f, 70))
    }

    @Test
    fun theDotsWindowIsCenteredOnTheClick() {
        for (windowPx in listOf(34, 50, 72)) {
            for ((x, y) in listOf(0f to 0f, 100f to 200f, 1919f to 1079f, 640.4f to 360.6f)) {
                val (originX, originY) = TrackpadPointerGeometry.origin(TrackpadPointerShape.DOT, x, y, windowPx)
                // The window's center is its origin plus half its (even) side; it is the click, to the pixel.
                assertEquals("x at $windowPx", Math.round(x), originX + windowPx / 2)
                assertEquals("y at $windowPx", Math.round(y), originY + windowPx / 2)
            }
        }
    }

    @Test
    fun aDotThatMayNotCoverTheClickTakesTheArrowsOffClickOriginAndItsFrameExcludesTheClick() {
        for (windowPx in listOf(34, 50, 72)) {
            for ((x, y) in listOf(0f to 0f, 100f to 200f, 1919f to 1079f, 640.4f to 360.6f, 5.9f to 7.2f)) {
                val (originX, originY) = TrackpadPointerGeometry.origin(TrackpadPointerShape.DOT, x, y, windowPx, mayCoverClick = false)
                assertEquals(
                    "the same origin as the arrow's at $windowPx",
                    TrackpadPointerGeometry.origin(TrackpadPointerShape.SLIM_ARROW, x, y, windowPx),
                    originX to originY,
                )
                // The frame [origin, origin + windowPx) holds neither coordinate of the click, so it never contains the click.
                assertTrue("x $x is left of the frame at $originX", x < originX)
                assertTrue("y $y is above the frame at $originY", y < originY)
                // The dot, drawn whole in its window, has its center as near the click as that allows: down-right of it.
                val centreX = originX + windowPx / 2f
                val centreY = originY + windowPx / 2f
                assertTrue("center x ${centreX - x} px from the click", centreX - x in (windowPx / 2f)..(windowPx / 2f + 2f))
                assertTrue("center y ${centreY - y} px from the click", centreY - y in (windowPx / 2f)..(windowPx / 2f + 2f))
            }
        }
    }

    @Test
    fun theArrowsOriginDoesNotDependOnWhetherTheWindowMayCoverTheClick() {
        val trusted = TrackpadPointerGeometry.origin(TrackpadPointerShape.SLIM_ARROW, 100f, 200f, 50, mayCoverClick = true)
        val untrusted = TrackpadPointerGeometry.origin(TrackpadPointerShape.SLIM_ARROW, 100f, 200f, 50, mayCoverClick = false)
        assertEquals(trusted, untrusted)
    }

    // ---- the fill ----------------------------------------------------------------------------------------------------

    @Test
    fun whiteIsOneOpaqueWhiteForEveryTheme() {
        MimirThemePalette.all.forEach { palette ->
            val fill = trackpadPointerFill(TrackpadPointerColor.WHITE, palette)
            assertEquals(listOf(-1), fill.argb)
        }
    }

    @Test
    fun accentIsTheThemesBrightAccentWithTheOverridesApplied() {
        MimirThemePalette.all.forEach { palette ->
            assertEquals(listOf(palette.accentBright.toArgb()), trackpadPointerFill(TrackpadPointerColor.ACCENT, palette).argb)
        }
        try {
            MimirAppearanceOverrides.accentStop = MimirAccentStop.ROSE_GOLD
            val retinted = MimirAppearanceOverrides.effectivePalette(MimirThemeId.DEFAULT)
            assertEquals(MimirAccentStop.ROSE_GOLD.accentBright.toArgb(), trackpadPointerFill(TrackpadPointerColor.ACCENT, retinted).argb.single())
        } finally {
            MimirAppearanceOverrides.accentStop = MimirAccentStop.CLASSIC_GOLD
        }
    }

    @Test
    fun themeMetalIsTheRuneCoinsRampHeldAtItsThirdStop() {
        MimirThemePalette.all.forEach { palette ->
            val fill = trackpadPointerFill(TrackpadPointerColor.THEME_METAL, palette)
            // The coin's first three stops, at its own positions, then held: the deepest stop is not used.
            assertEquals(
                listOf(palette.iconGradLight, palette.iconGradHot, palette.iconGradMid, palette.iconGradMid).map { it.toArgb() },
                fill.argb,
            )
            assertEquals(listOf(0f, 0.40f, 0.62f, 1f), fill.positions)
        }
    }

    @Test
    fun theEdgeIsDarkOnEveryChoice() {
        val edge = TrackpadPointerOutlineArgb
        val alpha = edge ushr 24
        val luma = (0.299 * (edge shr 16 and 0xff) + 0.587 * (edge shr 8 and 0xff) + 0.114 * (edge and 0xff))
        assertTrue("the edge is nearly opaque ($alpha)", alpha >= 200)
        assertTrue("and dark ($luma)", luma < 40)
        // A white fill and the lightest metal both stand well off it.
        assertTrue(MimirThemePalette.all.all { luminanceOf(it.iconGradLight.toArgb()) > luma + 60 })
    }

    private fun luminanceOf(argb: Int): Double =
        0.299 * (argb shr 16 and 0xff) + 0.587 * (argb shr 8 and 0xff) + 0.114 * (argb and 0xff)

    // ---- the drawing allocates nothing -------------------------------------------------------------------------------

    @Test
    fun drawOnlyDrawsAndEveryAllocationIsInConfigure() {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirTrackpadPointer.kt"),
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirTrackpadPointer.kt"),
        ).first { it.isFile }.readText()
        val draw = source.substringAfter("    fun draw(canvas: Canvas) {")
        assertTrue("the draw body was found", draw.contains("canvas.drawPath(arrow, body)"))
        // Constructors are matched as whole words: `drawPath(` is a draw call, `Path(` is an allocation.
        listOf("Paint", "Path", "LinearGradient", "RectF", "Rect", "PointF", "Matrix").forEach {
            assertFalse("draw must not construct a $it", Regex("(?<![A-Za-z])$it\\(").containsMatchIn(draw))
        }
        listOf("Shader", "floatArrayOf", "intArrayOf", "toIntArray", "toFloatArray", "listOf", "Pair", "String").forEach {
            assertFalse("draw must not allocate: $it", draw.contains(it))
        }
        val configure = source.substringAfter("    fun configure(").substringBefore("    fun draw(canvas: Canvas) {")
        assertTrue("the shader is built where the style is applied", configure.contains("LinearGradient("))
    }
}
