package com.gruning.mimir.rewrite.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mimir-button picker's two stages and its Settings row, from the review of 2026-10-13: the strip stages leave the Default theme's sun out (its anchor lands on
 * a caption), the browse stage lays its three seats out for the room it has (a 360 dp square at font 1.3 left the third caption "ON A"), and the Settings row says
 * "Your mark" as the picker does.
 *
 * Pixels (the sun gone, the captions whole) are `MimirButtonAdjustRenderTest`'s in :app, with the real fonts; the sun's removal itself is `MimirSceneLightOffTest`'s in
 * :core-ui. What is here is the wiring and the pure rule that picks the layout.
 */
class MimirButtonPickerStageWiringTest {

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
        File("feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `both strip stages take the sun out of the scene, and the Background picker, which judges the scene, does not`() {
        val browse = source("MimirButtonPicker.kt").substringAfter("fun BoxScope.MimirButtonPickerStageLayout(").substringBefore("/** The stage's three captions")
        assertTrue("the browse stage", browse.contains("MimirShelfBackground(modifier = Modifier.fillMaxSize(), palette = palette, sceneLight = false)"))
        val adjust = source("MimirButtonPickerAdjust.kt")
        assertTrue("the adjust stage", adjust.contains("MimirShelfBackground(modifier = Modifier.fillMaxSize(), palette = palette, sceneLight = false)"))
        val background = source("BackgroundPicker.kt")
        assertFalse("the Background picker's stage and its Atmosphere tile show the whole scene", background.contains("sceneLight"))
        val gallery = source("ThemeGallery.kt")
        assertFalse("and so does the theme gallery", gallery.contains("sceneLight"))
    }

    @Test
    fun `the Settings row says Your mark, as the picker does`() {
        val detail = source("SettingsDetail.kt")
        assertTrue(detail.contains("""RowAction(if (buttonMark == null) "Rune" else "Your mark")"""))
        assertFalse("no outlier", detail.contains("""RowAction(if (buttonMark == null) "Rune" else "Your image")"""))
        assertEquals("Your mark 1", mimirButtonMarkLabel(0))
    }

    @Test
    fun `a 360 dp square is a narrow stage at every font and the Thor is not`() {
        // The stage's inside width is the viewport less the page's 16 dp a side, the stage border and the stage's own 12 dp a side.
        val compactInside = 360f - 32f - 2f - 24f
        val thorInside = 832f - 32f - 2f - 24f
        for (font in listOf(1f, 1.3f, 2f)) {
            assertTrue("the square at $font", stageSeatsAreNarrow(compactInside, font))
        }
        assertFalse("the Thor at 1.0", stageSeatsAreNarrow(thorInside, 1f))
        assertFalse("the Thor at 1.3", stageSeatsAreNarrow(thorInside, 1.3f))
    }

    @Test
    fun `a narrow stage's seats share the width in proportion to their coins and none is squeezed below its content`() {
        val inside = 360f - 32f - 2f - 24f
        val weights = stageSeatWeights(inside)
        assertEquals("the three seats and their two gaps fill the stage", inside - 2f * MimirStageSeatGapDp, weights.sum(), 0.01f)
        assertTrue("the real button's seat holds its coin", weights[0] >= 56f)
        assertTrue("the 2x copy is never squeezed", weights[1] >= 112f)
        assertTrue("the panel's seat holds its panel", weights[2] >= 88f)
        assertTrue("and the 2x copy's seat is the widest", weights[1] > weights[0] && weights[1] > weights[2])
        // A stage narrower than the three together keeps their proportions rather than going negative.
        val tight = stageSeatWeights(200f)
        assertTrue(tight.all { it > 0f })
        assertEquals(112f / 56f, tight[1] / tight[0], 0.001f)
    }

    @Test
    fun `a wrapped caption starts a new line at a dot and the whole caption stays the spoken name`() {
        val seat = source("MimirButtonPicker.kt").substringAfter("internal fun StageSeat(").substringBefore("/** The diameter of the coin face")
        assertTrue("split at the separator, so no line ends on a dangling dot", seat.contains("""caption.split(" · ")"""))
        assertTrue("through the project's word-boundary text, never a per-letter ellipsis", seat.contains("MimirWordEllipsisText("))
        assertTrue("and the whole caption is the one node a screen reader reads", seat.contains("contentDescription = caption"))
    }
}
