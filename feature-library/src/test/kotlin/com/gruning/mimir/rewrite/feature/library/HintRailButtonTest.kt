package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.mimirHintPillColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HintRailButtonTest {
    @Test
    fun libraryHintTriggerOwnsGuidanceIdentityNotGlobalMannaz() {
        assertEquals("ᚨ", HintRailGuidanceRune)
        assertTrue(HintRailGuidanceRune != "ᛗ")
    }

    @Test
    fun navigationHintPreferenceControlsOnlyTheLibraryLegendGate() {
        assertTrue(libraryNavigationHintRailVisible(true, false, LibraryShelfScope))
        assertTrue(libraryNavigationHintRailVisible(true, false, GamelistScope))
        assertTrue(!libraryNavigationHintRailVisible(false, false, LibraryShelfScope))
        assertTrue(!libraryNavigationHintRailVisible(true, true, GamelistScope))
        assertTrue(!libraryNavigationHintRailVisible(true, false, FocusScope("detail")))
    }

    @Test
    fun narrowRailsKeepOnlyCompleteHintUnits() {
        val widths = listOf(80, 80, 80, 80, 80)

        assertEquals(
            5,
            hintRailVisibleUnitCount(widths, availableWidthPx = 448, gapPx = 12, overflowWidthPx = 10),
        )
        assertEquals(
            2,
            hintRailVisibleUnitCount(widths, availableWidthPx = 260, gapPx = 12, overflowWidthPx = 10),
        )
        assertEquals(
            0,
            hintRailVisibleUnitCount(widths, availableWidthPx = 8, gapPx = 12, overflowWidthPx = 10),
        )
    }

    /** The hint carousel moves within one row. */
    @Test
    fun aLegendWiderThanItsPillStaysOpenForOnePassThroughEveryHint() {
        // A legend that fits keeps the ordinary auto-collapse.
        assertEquals(0L, hintRailMarqueePassMs(contentWidthPx = 500, windowWidthPx = 500, spacingPx = 48, velocityPxPerSecond = 160f))
        assertEquals(0L, hintRailMarqueePassMs(contentWidthPx = 900, windowWidthPx = 0, spacingPx = 48, velocityPxPerSecond = 160f))
        // 1400 px of hints in an 1100 px pill at 80 dp/s on a 2x screen: the rest, (1400 + 48) px at 160 px/s, the hold.
        assertEquals(
            HintRailMarqueeDelayMs + 9_050L + HintRailMarqueeHoldMs,
            hintRailMarqueePassMs(contentWidthPx = 1400, windowWidthPx = 1100, spacingPx = 48, velocityPxPerSecond = 160f),
        )
        assertTrue("longer than a glance", HintRailMarqueeVelocity > 30.dp)
    }

    @Test
    fun theLegendMovesOnceAndNeverUnderReducedMotion() {
        val source = listOf(
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HintRailButton.kt"),
            java.io.File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HintRailButton.kt"),
        ).first(java.io.File::isFile).readText()
        val visibility = source.substringAfter("private fun HintRailVisibility(").substringBefore("private fun Modifier.hintRailEdgeFade(")
        assertTrue("one pass, which the auto-collapse waits for", visibility.contains("iterations = 1,"))
        assertTrue(
            "reduced motion keeps the still row, before any marquee",
            visibility.indexOf("if (mimirReducedMotion())") in 0 until visibility.indexOf(".basicMarquee("),
        )
    }

    /**
     * N5 (alpha audit 2026-09-30; policy A, the same day). The legend revealed on 260 ms and collapsed on 220 ms,
     * against a token whose KDoc caps hints at [MimirMotion.OverlayMs] (180). Both are the house sheet clock now:
     * [MimirMotion.OverlayMs] on [MimirMotion.Easing], four times over (the fade and the size, in and out), and the file
     * holds no literal duration -- and none of the two names that stood for them.
     */
    @Test
    fun theLegendRevealsAndCollapsesOnTheHouseSheetClockWithNoLiteralDuration() {
        val source = listOf(
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HintRailButton.kt"),
            java.io.File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HintRailButton.kt"),
        ).first(java.io.File::isFile).readText()
        val visibility = source.substringAfter("private fun HintRailVisibility(").substringBefore("val pill = Modifier")
        val house = "tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)"
        assertEquals("the fade and the size, in and out", 4, Regex(Regex.escape(house)).findAll(visibility).count())
        assertTrue(visibility.contains("enter = fadeIn($house) +"))
        assertTrue(visibility.contains("exit = fadeOut($house) +"))
        val literal = Regex("""tween\s*(?:<[^>]*>)?\s*\(\s*(?:durationMillis\s*=\s*)?\d+|delayMillis\s*=\s*\d+""")
        assertFalse("no raw duration in the legend", literal.containsMatchIn(source))
        assertFalse("the reveal's old constant is gone", source.contains("HintRailRevealMs"))
        assertFalse("and so is the collapse's", source.contains("HintRailCollapseMs"))
        assertEquals("the token the legend now rides", 180, MimirMotion.OverlayMs)
    }

    /**
     * the legend is the one quiet hint pill now, with OUTLINED keycaps. A keycap's
     * glyph is the pill's own ink on the pill's own body, so one pair carries both the labels and the keys, and
     * it still has to clear 7:1 on every authored palette -- the contract the old inverted keycaps held.
     */
    @Test
    fun everyAuthoredPaletteHintPillAndKeycapExceedSevenToOneContrast() {
        MimirThemePalette.all.forEach { palette ->
            val colors = mimirHintPillColors(palette)
            val pillContrast = contrastRatio(colors.body, colors.ink)
            val runeContrast = contrastRatio(colors.body, colors.rune)

            assertTrue("${palette.id} pill and keycap contrast was $pillContrast", pillContrast >= 7.0)
            assertTrue("${palette.id} Guidance rune contrast was $runeContrast", runeContrast >= 3.0)
            assertTrue(
                "${palette.id}: the pill must be an opaque body, or its contrast is unprovable",
                colors.body.alpha == 1f,
            )
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val first = relativeLuminance(a)
        val second = relativeLuminance(b)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val component = value.toDouble()
            return if (component <= 0.04045) component / 12.92 else ((component + 0.055) / 1.055).pow(2.4)
        }
        return channel(color.red) * 0.2126 + channel(color.green) * 0.7152 + channel(color.blue) * 0.0722
    }
}
