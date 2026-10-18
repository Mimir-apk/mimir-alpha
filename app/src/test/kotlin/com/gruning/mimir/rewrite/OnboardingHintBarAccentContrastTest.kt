package com.gruning.mimir.rewrite

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Button glyphs in hint and action text use a restrained accent treatment.
 * [OnboardingHintBar]'s button glyph now carries [MimirThemePalette.accentBright] instead of
 * [MimirThemePalette.textPrimary].
 *
 * There is a live precedent for exactly this trap: a focus ring shipped invisible because `focusRing` was
 * byte-identical to the gold pill's own middle gradient stop on that theme. This is the text equivalent of
 * that check -- not a general design-system contrast suite, just the one collision this change could have
 * introduced: does the button glyph's new colour still read against the field it renders on, on every
 * authored theme, not only DEFAULT.
 */
class OnboardingHintBarAccentContrastTest {

    @Test
    fun `hint bar accent never collides with the field it renders on, every theme`() {
        MimirThemePalette.all.forEach { palette ->
            val ratio = wcagContrastRatio(palette.accentBright, palette.surfaceBase)
            assertTrue(
                "theme ${palette.id}: accentBright vs surfaceBase contrast was $ratio, need >= 4.5",
                ratio >= 4.5,
            )
        }
    }

    private fun wcagContrastRatio(first: Color, second: Color): Double {
        val lighter = maxOf(first.luminance(), second.luminance()).toDouble()
        val darker = minOf(first.luminance(), second.luminance()).toDouble()
        return (lighter + 0.05) / (darker + 0.05)
    }
}
