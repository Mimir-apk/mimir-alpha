package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background's processing maths : the stored copy is the centred window of the source with the main
 * display's aspect, scaled to at most the display and never up; the display itself is bounded at 1920 on its long edge;
 * the soft note fires under half that; and `p90` is the 90th-percentile WCAG relative luminance of the pixels it is
 * measured on.
 */
class AppearanceBackgroundProcessingTest {

    private val thor = AppearanceDisplaySize(1_920, 1_080)
    private val rotate = AppearanceDisplaySize(720, 720)

    @Test
    fun `a large image is cropped to the display's aspect and scaled to the display`() {
        // 4K 16:9 on the Thor: the whole picture, at exactly 1080p.
        assertEquals(
            AppearanceBackgroundPlan(1_920, 1_080, 0, 0, 1_920, 1_080, 3_840, 2_160),
            appearanceBackgroundPlan(3_840, 2_160, thor),
        )
        // A portrait 3000x4000 on the Thor: the centred 16:9 window (3000 x 1688 of the source) at 1080 tall, the
        // sides kept whole -- where the old scale-to-cover stored the whole 1440x1920 portrait to be cropped at draw.
        val portrait = appearanceBackgroundPlan(3_000, 4_000, thor)
        assertEquals(1_688, portrait.sourceWindowHeight)
        assertEquals(3_000, portrait.sourceWindowWidth)
        assertEquals(1_080, portrait.cropHeight)
        assertTrue("a pixel of rounding at most", portrait.cropWidth in 1_919..1_920)
        assertEquals(portrait.cropWidth, portrait.decodeWidth)
        assertEquals(0, portrait.cropLeft)
        assertEquals((portrait.decodeHeight - 1_080) / 2, portrait.cropTop)
        // 4K on the RG Rotate's square: the middle 2160 x 2160, at 720 x 720.
        assertEquals(
            AppearanceBackgroundPlan(1_280, 720, 280, 0, 720, 720, 2_160, 2_160),
            appearanceBackgroundPlan(3_840, 2_160, rotate),
        )
    }

    @Test
    fun `a small image is cropped to the aspect but never upscaled`() {
        // 640x480 on the Thor: the centred 16:9 window, 640 x 360, kept at its own pixels.
        assertEquals(AppearanceBackgroundPlan(640, 480, 0, 60, 640, 360, 640, 360), appearanceBackgroundPlan(640, 480, thor))
        // A tall 100x900 on the square: its 100 x 100 middle.
        assertEquals(AppearanceBackgroundPlan(100, 900, 0, 400, 100, 100, 100, 100), appearanceBackgroundPlan(100, 900, rotate))
        assertEquals(AppearanceBackgroundPlan(1, 1, 0, 0, 1, 1, 1, 1), appearanceBackgroundPlan(0, 10, thor))
    }

    @Test
    fun `an oversized display claim is bounded at 1920 on its long edge`() {
        assertEquals(AppearanceDisplaySize(1_920, 1_080), appearanceBoundedDisplay(AppearanceDisplaySize(3_840, 2_160)))
        assertEquals(AppearanceDisplaySize(1_920, 1_080), appearanceBoundedDisplay(thor))
        assertEquals(AppearanceDisplaySize(1, 1), appearanceBoundedDisplay(AppearanceDisplaySize(0, -4)))
        // Even a 4K display never asks for more than a 1920 copy.
        val plan = appearanceBackgroundPlan(7_680, 4_320, AppearanceDisplaySize(3_840, 2_160))
        assertEquals(1_920, plan.cropWidth)
        assertEquals(1_080, plan.cropHeight)
    }

    @Test
    fun `the stored copy is never larger than the display, never letterboxed and never upscaled`() {
        val displays = listOf(thor, rotate, AppearanceDisplaySize(1_280, 960), AppearanceDisplaySize(720, 1_280))
        val sources = listOf(3_840 to 2_160, 100 to 900, 5_000 to 100, 33 to 33, 1_279 to 961, 2_000 to 3_000)
        displays.forEach { display ->
            val bounded = appearanceBoundedDisplay(display)
            val displayAspect = bounded.width.toDouble() / bounded.height
            sources.forEach { (width, height) ->
                val plan = appearanceBackgroundPlan(width, height, display)
                val label = "$width x $height on ${display.width} x ${display.height}"
                assertTrue("$label: at most the display", plan.cropWidth <= bounded.width && plan.cropHeight <= bounded.height)
                assertTrue("$label: never up", plan.decodeWidth <= width && plan.decodeHeight <= height)
                assertTrue(
                    "$label: the crop lies inside the decode",
                    plan.cropLeft >= 0 && plan.cropTop >= 0 &&
                        plan.cropLeft + plan.cropWidth <= plan.decodeWidth && plan.cropTop + plan.cropHeight <= plan.decodeHeight,
                )
                val aspect = plan.cropWidth.toDouble() / plan.cropHeight
                val rounding = displayAspect / minOf(plan.cropWidth, plan.cropHeight)
                assertTrue("$label: the display's aspect ($aspect vs $displayAspect)", kotlin.math.abs(aspect - displayAspect) <= rounding + 1e-9)
                assertTrue("$label: centred", kotlin.math.abs((plan.decodeWidth - plan.cropWidth) - 2 * plan.cropLeft) <= 1)
            }
        }
    }

    @Test
    fun `the soft note fires under half the display's long edge`() {
        assertTrue(appearanceBackgroundLooksSoft(640, thor))
        assertFalse(appearanceBackgroundLooksSoft(960, thor))
        assertFalse(appearanceBackgroundLooksSoft(1_920, thor))
        assertTrue(appearanceBackgroundLooksSoft(320, rotate))
        assertFalse(appearanceBackgroundLooksSoft(0, thor))
    }

    @Test
    fun `p90 is the brightness of the brightest tenth`() {
        // 90 black pixels and 10 white: the 90th percentile lands on the first white pixel.
        val pixels = IntArray(100) { index -> if (index < 90) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(1f, appearanceLuminanceP90(pixels), 1e-6f)
        // 95 black and 5 white: the 90th percentile is still black.
        val mostlyBlack = IntArray(100) { index -> if (index < 95) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(0f, appearanceLuminanceP90(mostlyBlack), 1e-6f)
        // A uniform mid grey (sRGB 128) has the WCAG linear luminance of that code, alpha ignored.
        val grey = IntArray(64) { 0x00808080 }
        assertEquals(0.2158605, appearanceLuminanceP90(grey).toDouble(), 1e-4)
        assertEquals(0f, appearanceLuminanceP90(IntArray(0)), 0f)
    }

    @Test
    fun `p90 weights the channels as WCAG does`() {
        val pureGreen = IntArray(10) { 0xFF00FF00.toInt() }
        val pureBlue = IntArray(10) { 0xFF0000FF.toInt() }
        assertEquals(0.7152f, appearanceLuminanceP90(pureGreen), 1e-4f)
        assertEquals(0.0722f, appearanceLuminanceP90(pureBlue), 1e-4f)
    }
}
