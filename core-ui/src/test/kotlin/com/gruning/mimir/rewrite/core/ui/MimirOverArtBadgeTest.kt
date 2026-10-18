package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The over-art badge is the one label in the app whose background is NOT a palette token — it sits on
 * provider grids, user uploads, scraped covers, and Mimir's own console line-art. So its legibility cannot be
 * asserted by inspection; it is derived here, from the live palettes, against the two extremes any image can
 * present.
 */
class MimirOverArtBadgeTest {

    /** WCAG 2.1 AA for normal-size text. The badge is small caps, so this is the floor, not a target. */
    private val AA = 4.5

    /** The worst backgrounds an arbitrary bitmap can put under the plate. */
    private val extremes = listOf("white" to Color.White, "black" to Color.Black)

    private fun worstCase(alpha: Float): Triple<Double, String, String> {
        var worst = Double.MAX_VALUE
        var theme = ""
        var under = ""
        MimirThemePalette.all.forEach { palette ->
            val plate = palette.surfaceBase.copy(alpha = alpha)
            extremes.forEach { (name, art) ->
                val ratio = contrastRatio(palette.textPrimary, plate.compositeOver(art))
                if (ratio < worst) {
                    worst = ratio
                    theme = palette.id.name
                    under = name
                }
            }
        }
        return Triple(worst, theme, under)
    }

    @Test
    fun badgeInkClearsAaOverBothPureWhiteAndPureBlackArtOnEveryShippingPalette() {
        MimirThemePalette.all.forEach { palette ->
            val colors = mimirOverArtBadgeColors(palette)
            extremes.forEach { (name, art) ->
                val composited = colors.plate.compositeOver(art)
                val ratio = contrastRatio(colors.ink, composited)
                assertTrue(
                    "${palette.id} badge ink over $name art = ${"%.2f".format(ratio)}:1, below AA $AA:1",
                    ratio >= AA,
                )
            }
        }
    }

    /**
     * Differential guard: a green assertion above only means something if a THINNER plate would go red. This
     * pins the actual cliff (~0.61) rather than letting the floor become decorative — if a palette change ever
     * makes 0.55 pass, the derivation in [MimirOverArtBadge]'s header is stale and must be recomputed.
     */
    @Test
    fun aDeliberatelyThinnerPlateWouldFailTheSameFloor() {
        val (thin, _, _) = worstCase(0.55f)
        assertTrue("a 0.55 plate scored $thin:1 — it should fail AA, so the floor has teeth", thin < AA)
    }

    @Test
    fun theShippedPlateAlphaKeepsHeadroomAboveTheCliff() {
        val (shipped, theme, under) = worstCase(MimirOverArtBadgePlateAlpha)
        // AAA (7:1), not AA — headroom so a future palette cannot silently cross the floor.
        assertTrue(
            "worst shipped case is ${"%.2f".format(shipped)}:1 ($theme over $under art); expected >= 7.0",
            shipped >= 7.0,
        )
        val (cliff, _, _) = worstCase(0.60f)
        assertTrue("0.60 should sit below AA; measured $cliff", cliff < AA)
    }

    @Test
    fun thePlateIsTranslucentSoTheArtUnderneathStaysVisible() {
        // A fully opaque chip would trivially pass every contrast check — and hide the art the request specified to
        // show. The badge must remain a plate ON the art, not a replacement for it.
        assertTrue(MimirOverArtBadgePlateAlpha < 1f)
        MimirThemePalette.all.forEach { palette ->
            val colors = mimirOverArtBadgeColors(palette)
            assertTrue("${palette.id} plate must be translucent", colors.plate.alpha < 1f)
            assertTrue("${palette.id} ink must be opaque", colors.ink.alpha == 1f)
        }
    }
}
