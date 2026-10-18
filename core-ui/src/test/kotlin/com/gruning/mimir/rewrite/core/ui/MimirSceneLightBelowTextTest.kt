package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the Default theme's sun sat behind
 * the end of the privacy line and the action pages' where-lines. On 4:3 and square screens the orb moves lower and
 * further right, below the text column, so it never sits behind words; wide screens keep it exactly where it is.
 *
 * The Nova numbers below are read off device captures
 * (2026-09-28, 1280x960 px at density 2):
 * the last body line sits at y 361-368 dp and its descenders end by ~380; the centred NEXT/SKIP pair spans y 415-460
 * and ends at x 447; the field is 640x480.
 */
class MimirSceneLightBelowTextTest {

    private val novaWidth = 640f
    private val novaHeight = 480f
    private val novaLastLineBottomDp = 380f
    private val novaPillPairRightDp = 447f
    private val novaPillRowTopDp = 415f

    @Test
    fun `wide fields keep the default anchor, byte for byte`() {
        // Odin / Thor main display, RP5-class shelf, QA emulator: the 2026-09-03 anchor, pinned as literals so a
        // formula edit fails here rather than on a device.
        assertSun(wellSunGeometry(832.5f, 468.3f, belowText = false), cx = 656.12f, cy = 377.45f, r = 78.67f)
        assertSun(wellSunGeometry(700f, 350f, belowText = false), cx = 549.50f, cy = 282.10f, r = 58.80f)
        assertSun(wellSunGeometry(914.3f, 411.4f, belowText = false), cx = 715.44f, cy = 331.59f, r = 69.12f)
        // Nova before the placement change: sun bounds x430–591, y306–467.
        assertSun(wellSunGeometry(novaWidth, novaHeight, belowText = false), cx = 510.40f, cy = 386.88f, r = 80.64f)
    }

    @Test
    fun `on the Nova the orb sits below the text column and beside the pill pair`() {
        val sun = wellSunGeometry(novaWidth, novaHeight, belowText = true)
        assertSun(sun, cx = 559.36f, cy = 467.90f, r = 80.64f)
        assertTrue("top edge ${sun.top} must clear the last line's descenders", sun.top >= novaLastLineBottomDp)
        assertTrue("left edge ${sun.left} must clear the centred pill pair", sun.left >= novaPillPairRightDp)
        assertTrue("the orb must still show above the pill row", sun.top < novaPillRowTopDp)
        assertEquals("tangent to the right edge, nothing cut there", novaWidth, sun.right, 0.01f)
        assertTrue("the field's bottom cuts the disc", sun.bottom > novaHeight)
    }

    @Test
    fun `every 4x3 and square field takes the same fractions of its own sun`() {
        listOf(
            720f to 720f, // compact-square probe
            564.7f to 564.7f, 564.7f to 540.7f, // RG Rotate, bar hidden / shown
            1080f to 1080f, // large-square boundary (STANDARD posture, square aspect)
            320f to 300f, // the shelf's minimum floor
            640f to 480f,
        ).forEach { (w, h) ->
            val sun = wellSunGeometry(w, h, belowText = true)
            val short = minOf(w, h)
            assertEquals("$w x $h radius is the default size", short * 0.168f, sun.radius, 0.001f)
            assertEquals("$w x $h right edge is tangent", w, sun.right, 0.01f)
            assertEquals("$w x $h centre sits 0.15 r above the bottom", 0.15f, (h - sun.cy) / sun.radius, 0.001f)
            // 57.5% of the disc's height shows; the default anchor showed all of it.
            assertEquals("$w x $h visible height", 1.15f, (h - sun.top) / sun.radius, 0.001f)
            val ruled = wellSunGeometry(w, h, belowText = false)
            assertTrue("$w x $h moved lower", sun.cy > ruled.cy)
            assertTrue("$w x $h moved further right", sun.cx > ruled.cx)
        }
    }

    @Test
    fun `the classifier splits 4x3 and square from the certified wide set by aspect alone`() {
        listOf(
            640f to 480f, 720f to 720f, 564.7f to 564.7f, 564.7f to 540.7f, 1080f to 1080f, 320f to 300f,
            538f to 444f, 1240f to 1080f, 480f to 640f,
        ).forEach { (w, h) ->
            assertTrue("$w x $h", mimirViewportProfile(w, h).isFourThreeOrSquare)
        }
        listOf(
            832.5f to 468.3f, 833f to 444f, 700f to 350f, 914.3f to 411.4f, 360f to 640f, 600f to 1000f, 1920f to 1080f,
        ).forEach { (w, h) ->
            assertFalse("$w x $h", mimirViewportProfile(w, h).isFourThreeOrSquare)
        }
        // The Compact override changes posture, never the aspect this reads.
        assertFalse(mimirViewportProfile(1920f, 1080f, MimirViewportMode.COMPACT).isFourThreeOrSquare)
        assertTrue(mimirViewportProfile(640f, 480f, MimirViewportMode.COMPACT).isFourThreeOrSquare)
        // No provider: the unmeasured default keeps the default anchor.
        assertFalse(MimirViewportProfile(MimirViewportPosture.STANDARD, 0f, 0f).isFourThreeOrSquare)
        assertFalse(MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, 0f, 0f).isFourThreeOrSquare)
    }

    @Test
    fun `a below-text bake is its own raster, never a palette swap of the default one`() {
        val ruled = MimirShelfBackgroundCacheKey(
            size = Size(1280f, 960f),
            density = 2f,
            layoutDirection = LayoutDirection.Ltr,
            palette = MimirThemePalette.forId(MimirThemeId.entries.first()),
            showAtmosphericClouds = true,
        )
        val moved = ruled.copy(sceneLightBelowText = true)
        assertFalse("every existing surface bakes at the default anchor", ruled.sceneLightBelowText)
        assertNotEquals(ruled, moved)
        assertFalse(ruled.differsOnlyInPaletteFrom(moved))
        // Independent of the quiet light: the RG carries both.
        assertNotEquals(moved, ruled.copy(quietSceneLight = true))
    }

    @Test
    fun `the painter anchors on the one geometry function`() {
        val painter = sceneSource()
            .substringAfter("fun DrawScope.drawWellSceneV2")
            .substringBefore("internal fun DrawScope.drawGoldenHall")
        assertTrue(painter.contains("wellSunGeometry(size.width, size.height, belowText = lightBelowText)"))
        assertFalse(
            "a second anchor would let the wide and the 4:3 sun drift apart",
            painter.contains("size.width * 0.760f"),
        )
        assertFalse(painter.contains("size.height * 0.756f"))
    }

    private fun assertSun(sun: WellSunGeometry, cx: Float, cy: Float, r: Float) {
        assertEquals("cx", cx, sun.cx, 0.05f)
        assertEquals("cy", cy, sun.cy, 0.05f)
        assertEquals("radius", r, sun.radius, 0.05f)
    }

    private fun sceneSource(): String {
        val candidates = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirSceneAtmosphereV2.kt"),
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirSceneAtmosphereV2.kt"),
        )
        return candidates.first(File::isFile).readText()
    }
}
