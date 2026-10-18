package com.gruning.mimir.rewrite.core.companionnative

import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.model.MimirMarkReferenceBoxDp
import com.gruning.mimir.rewrite.core.model.MimirMarkReferenceCoinDp
import com.gruning.mimir.rewrite.core.model.MimirMarkReferenceFaceRadiusDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * the picker's small tiles show the custom ᛗ image trimmed and bigger, to match the live button. The picker
 * (`:feature-settings`) cannot reach this module, so the geometry both read lives in `:core-model`
 * (`MimirButtonMarkGeometry.kt`). This test is the other half of that tie: the live coin's own numbers ARE the shared ones, and
 * the live trim calls the shared rule. The picker's half is `MimirButtonPickerMarkCoinTest` in `:feature-settings`, which draws a
 * tile from the same numbers; so a tile and the live button cannot drift without one of the two failing.
 */
class CompanionMarkSharedGeometryTest {

    @Test
    fun `the live coin's size, box and face are the shared reference numbers`() {
        assertEquals("the button", MimirMarkReferenceCoinDp, MButtonSizeDp.toFloat(), 0f)
        assertEquals("the box", MimirMarkReferenceBoxDp, CompanionCustomMarkBoxDp, 0f)
        // The coin view strokes a 1.25 dp rim on the disc's edge and clips the image to the circle inside it.
        val faceRadiusDp = MButtonSizeDp / 2f - 1.25f - 1.25f / 2f
        assertEquals("the face the rim leaves", MimirMarkReferenceFaceRadiusDp, faceRadiusDp, 0.0001f)
        assertEquals(
            "the inset the coin draws the box with",
            (MimirMarkReferenceCoinDp - MimirMarkReferenceBoxDp) / 2f,
            CompanionCoinCustomMarkInsetDp,
            0f,
        )
    }

    @Test
    fun `the live trim calls the shared ink rule and the module keeps no copy of it`() {
        val image = source("CompanionButtonMarkImage.kt")
        val trim = image.substringAfter("internal fun trimToInk(").substringBefore("\n}\n")
        assertTrue("the ink bounds are the shared function", trim.contains("mimirMarkInkBounds("))
        assertTrue("and so is the crop arithmetic", trim.contains("mimirMarkTrimCrop("))
        assertFalse("no margin arithmetic left in the live trim", trim.contains("coerceAtLeast") || trim.contains("coerceAtMost"))

        val metrics = source("CompanionButtonMarkMetrics.kt")
        assertFalse("no second ink scan", metrics.contains("ushr 24"))
        assertFalse("no second alpha floor", metrics.contains("InkAlphaFloor"))
        assertFalse("no second trim margin", metrics.contains("TrimMarginPx"))
        assertTrue("the box is the shared value", metrics.contains("CompanionCustomMarkBoxDp = MimirMarkReferenceBoxDp"))
    }

    @Test
    fun `the live coin places the image with the shared placement geometry and keeps no fit of its own`() {
        // the player sizes and places their image. The picker's tiles draw the same `mimirMarkDrawRect`, so a placement reads the
        // same on the button as on its tile; a second fit in this module is the drift the shared function exists to prevent.
        // This is a SOURCE pin, like the rest of this file (the module's sources are what it tests; the shared rule's numbers are `MimirMarkPlacementTest`'s
        // in :core-model). The behaviour twins, which draw the coin and read pixels, are `CompanionButtonMarkDrawTest` in :app (the native coin moves, sizes
        // and clips the ink by the placement, and the rune is byte-unchanged) and `MimirButtonPickerMarkCoinTest` in :feature-settings (a tile's ink lands
        // where the shared rect says), and `MimirButtonPickerAdjustStageTest` in :app proves the stage's coins are the live button's.
        val coin = source("AndroidCompanionOverlayController.kt")
            .substringAfter("val inset = px(markInsetDp)").substringBefore("canvas.restoreToCount(layer)")
        assertTrue("the image's rect is the shared one", coin.contains("mimirMarkDrawRect("))
        assertTrue("and the metal's ramp runs the shared box", coin.contains("mimirMarkBoxRect("))
        assertTrue("with the coin's placement", coin.contains("placement"))
        assertFalse("no integer fit of its own", source("CompanionButtonMarkMetrics.kt").contains("fun companionFitMarkBounds"))
        assertEquals("the default placement is the box the coin always drew", MimirMarkReferenceBoxDp, CompanionCustomMarkBoxDp, 0f)
        assertEquals("an image saved before placement existed is drawn as it was", MimirMarkPlacement.Default, CompanionButtonMark.Image("a", "/p").placement)
    }

    private fun source(name: String): String = listOf(
        File("core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/$name"),
    ).first(File::isFile).readText()
}
