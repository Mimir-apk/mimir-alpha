package com.gruning.mimir.rewrite

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Utility after the ring migration : every rail row and both footer actions ring once, on the one
 * named corner, with the wash the family paints; the thumb keeps its rim; the rail and the footer clear the reach.
 * Source pins, the module's idiom.
 */
class UtilityRingTest {

    @Test
    fun `every row and action rings on the one corner, and the old border ring is gone`() {
        val source = overlaySource()
        assertEquals(6.dp, UtilityRowCorner)
        assertEquals(0, Regex("""RoundedCornerShape\(6\.dp\)""").findAll(source).count())
        // Five rows ring on the row corner; the footer actions are etched chips  and ring on the plate, as a Pill.
        assertEquals(5, Regex("""RoundedCornerShape\(UtilityRowCorner\)""").findAll(source).count())
        assertEquals(5, Regex("""shape = MimirRingShape\.Row\(UtilityRowCorner\)""").findAll(source).count())
        val code = source.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }.joinToString("\n")
        assertFalse(code.contains("palette.focusRing"))
        assertFalse(code.contains("MimirSpacing.focusRingWidth"))
        assertFalse("the 0.08 tint is the ring's wash now", code.contains("palette.accent.copy(alpha = 0.08f)"))
    }

    @Test
    fun `the disabled dims sit after the ring on a ModulateAlpha layer`() {
        val source = overlaySource()
        assertFalse(source.contains(".alpha("))
        assertEquals(3, Regex("""compositingStrategy = CompositingStrategy\.ModulateAlpha""").findAll(source).count())
        val action = utilityActionSource(source)
        // The ring is in the plate's modifier and the dim after it, so the ring is drawn outside the dimmed layer.
        assertTrue(action.indexOf(".mimirSelectionRing(") in 0 until action.indexOf(".graphicsLayer {"))
        assertTrue("the actions ring as a Pill on the plate, flaring on arrival", action.contains("shape = MimirRingShape.Pill,\n                    flare = true,"))
        assertFalse("an action is a chip, never a fill", action.contains(".background(") || action.contains(".clip(") || action.contains("filled = "))
    }

    @Test
    fun `the thumb keeps its rim and the row rings for it`() {
        val source = overlaySource()
        assertTrue(source.contains(".border(width = 1.dp, color = palette.surfaceRaised, shape = CircleShape)"))
        val slider = source.substringAfter("private fun UtilitySliderRow(").substringBefore("private fun UtilitySlider(")
        assertTrue(slider.contains("shape = MimirRingShape.Row(UtilityRowCorner)"))
    }

    @Test
    fun `the rail and the footer clear the ring's reach on the axis they clip`() {
        val source = overlaySource()
        assertTrue(source.contains("top = MimirFocusTileRingReach,\n                                bottom = maxOf(UtilityRailSafePaddingDp.dp, MimirFocusTileRingReach),"))
        // The footer keeps the reserve it had when Allow was a filled row (the ring stood off by the gap): more than a chip's Pill ring and its flare need, so it cuts nothing.
        assertTrue(source.contains("bottom = MimirFilledControlRingReach + MimirFocusFlareReach"))
        assertFalse(source.contains("bottom = MimirFocusTileRingReach + MimirFocusFlareReach"))
    }

    @Test
    fun `the footer keeps the ring's whole reach between its hint, Allow and Close`() {
        // Issue #269 review: the halo of a focused action ran into the hint's words and into the neighbouring chip, because the row reserved only the plates.
        val source = overlaySource()
        val footer = source.substringAfter("// ── FIXED footer: Close can never scroll away").substringBefore("TweakerSettlementLayer(")
        assertEquals("every gap in the footer row is the ring's whole reach", 0, Regex("""Spacer\(Modifier\.width\(8\.dp\)\)""").findAll(footer).count())
        assertEquals("the hint, Allow and Close stand one reach apart (and the Tweaker's toast and Close)", 4, Regex("""Spacer\(Modifier\.width\(UtilityActionRingReach\)\)""").findAll(footer).count())
        assertTrue(source.contains("internal val UtilityActionRingReach: Dp = MimirFocusTileRingReach + MimirFocusFlareReach"))
        assertTrue("the privileged-access message keeps the reach off its action too", source.contains("horizontalArrangement = Arrangement.spacedBy(UtilityActionRingReach),"))
    }

    private fun utilityActionSource(source: String): String =
        source.substringAfter("internal fun UtilityAction(").substringBefore("private fun percentLabel(")

    private fun overlaySource(): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
    ).first(File::isFile).readText()
}
