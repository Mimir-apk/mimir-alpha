package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class CompanionOverlayThemeAdapterTest {
    @Test
    fun everyMimirThemeProducesAnOpaqueDistinctTomeMaterial() {
        val themes = MimirThemeId.entries.map(::companionOverlayTheme)

        assertEquals(MimirThemeId.entries.size, themes.distinct().size)
        MimirThemeId.entries.zip(themes).forEach { (themeId, theme) ->
            listOf(
                theme.coverArgb,
                theme.coverEdgeArgb,
                theme.pageArgb,
                theme.pageEdgeArgb,
                theme.inkArgb,
                theme.mutedInkArgb,
                theme.accentArgb,
                theme.toolingArgb,
                theme.metalLightArgb,
                theme.metalHotArgb,
                theme.metalMidArgb,
                theme.metalDeepArgb,
            ).forEach { argb -> assertEquals(0xFF, argb ushr 24) }
            assertNotEquals(theme.coverArgb, theme.inkArgb)
            assertTrue(
                "$themeId primary ink must reach 4.5:1 over the page",
                contrastRatio(theme.inkArgb, theme.pageArgb) >= 4.5,
            )
            assertTrue(
                "$themeId muted ink must reach 4.5:1 over the page",
                contrastRatio(theme.mutedInkArgb, theme.pageArgb) >= 4.5,
            )
            assertTrue(
                "$themeId muted ink must reach 4.5:1 over the cover",
                contrastRatio(theme.mutedInkArgb, theme.coverArgb) >= 4.5,
            )
            assertTrue(
                "$themeId tooling must reach 3:1 over the cover",
                contrastRatio(theme.toolingArgb, theme.coverArgb) >= 3.0,
            )
        }
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val light = max(luminance(first), luminance(second))
        val dark = min(luminance(first), luminance(second))
        return (light + 0.05) / (dark + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(argb ushr 16 and 0xff) +
            0.7152 * linear(argb ushr 8 and 0xff) +
            0.0722 * linear(argb and 0xff)
    }
}
