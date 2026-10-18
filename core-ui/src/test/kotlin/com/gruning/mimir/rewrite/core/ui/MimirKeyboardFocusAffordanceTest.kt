package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard's etched-key and checkmark rules, held across every authored theme.
 *
 * **The Done key is the warm etched primary.** every gold or gradient primary becomes the dark plate with an accent rim and accent ink ([mimirEtchedChipSurface]
 * with `primary = true`), never a fill. It was a filled gold gradient (and so needed a fill shift on focus, because its ring and fill were the same hue). On the etched plate a focused key hands its rim to the ring, which is then its one edge, and there is no fill left for the ring to collide with.
 *
 * **The panel is opaque.** The keyboard's ground was 0.66 / 0.78 alpha, so the host's content read through between the keys (Chrome's suggestion list on the Bottom).
 */
class MimirKeyboardFocusAffordanceTest {
    private val source: String = listOf(
        File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt"),
    ).first(File::isFile).readText()

    private val code = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `the Done key is drawn on the etched primary plate, its rim handed to the ring when focused`() {
        assertTrue(
            "the accent key wears the etched chip surface, primary, cut to the key's own shape, focused when its ring is lit",
            code.contains("mimirEtchedChipSurface(palette, focused = decoration?.focused == true, primary = true, corner = KeyboardKeyRadius, edgeFlush = true)"),
        )
        assertFalse("no filled Done gradient is left", code.contains("keyboardDoneTop") || code.contains("keyboardDoneBottom") || code.contains("keyboardDoneFocused"))
        assertFalse("and no gold fill brush for an accent key", code.contains("accent && focused") || code.contains("accent -> listOf("))
    }

    @Test
    fun `a settled focused primary plate draws no rim and a resting one draws the accent rim whole`() {
        // The surface's own rule (mimirEtchedChipSurface): the rim is the resting alpha less what the ring has taken over (2026-10-14: the
        // hand-over is eased on the ring's clock, not one step), so a settled focused plate (hand-over 1) draws none and a resting primary
        // (hand-over 0, resting alpha 1) strikes it whole in the accent.
        assertEquals("focused and settled: no rim", 0f, mimirEtchedChipRimAlpha(restAlpha = 1f, handover = 1f), 0f)
        assertEquals("resting primary: the accent rim whole", 1f, mimirEtchedChipRimAlpha(restAlpha = 1f, handover = 0f), 0f)
        assertEquals("half way: half the rim", 0.5f, mimirEtchedChipRimAlpha(restAlpha = 1f, handover = 0.5f), 1e-6f)
        val surface = File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirEtchedChip.kt").takeIf(File::isFile)
            ?: File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirEtchedChip.kt")
        val rest = surface.readText().substringAfter("val restRimAlpha =").substringBefore("\n")
        assertTrue("a primary's resting rim is whole: $rest", rest.trim().startsWith("if (primary) 1f"))
    }

    @Test
    fun `the Done key's ink is the primary chip's ink, an accent, on every theme`() {
        MimirThemePalette.all.forEach { palette ->
            assertEquals("theme ${palette.id}", palette.accentBright, palette.etchedChipInk(primary = true))
        }
    }

    @Test
    fun `the keyboard panel's ground is the theme's opaque surfaces at full alpha`() {
        assertTrue(
            "the panel brush is the two surface tokens, unmodified",
            code.contains("Brush.verticalGradient(listOf(palette.surfaceRaised, palette.surfaceBase))"),
        )
        MimirThemePalette.all.forEach { palette ->
            assertEquals("theme ${palette.id}: surfaceRaised is opaque", 1f, palette.surfaceRaised.alpha, 0f)
            assertEquals("theme ${palette.id}: surfaceBase is opaque", 1f, palette.surfaceBase.alpha, 0f)
        }
    }
}
