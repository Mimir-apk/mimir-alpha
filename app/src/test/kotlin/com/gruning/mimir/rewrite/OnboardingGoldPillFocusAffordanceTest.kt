package com.gruning.mimir.rewrite

import androidx.compose.ui.graphics.luminance
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.ctaMaterial
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release UX gate, focus-state finding (device report 2026-08-19): insufficient ring contrast — onboarding's gold pills (BEGIN,
 * CONTINUE, and the rest of `OnboardingPill(gold = true, ...)`). Same accent-on-accent focus-ring
 * collision already found and fixed on the keyboard's Done key (`MimirKeyboardFocusAffordanceTest`,
 * `keyboardDoneFocusedTop`/`Bottom`) and Brightness/Utility's "Allow"
 * (`UtilityPrimaryActionFocusAffordanceTest`, `utilityPrimaryActionFocusedFill`): `OnboardingPill`'s gold
 * fill IS `palette.id.ctaMaterial()`, and `mimirFocusRing` paints the FOCUSED ring in `palette.focusRing`.
 * On every theme where `focusRing` is left at its default (`accent`) — DEFAULT, FREYR, SURTR, MANI,
 * YGGDRASIL — `accent` is byte-identical to one of `ctaMaterial()`'s own three authored stops (DEFAULT:
 * both are `0xFFD4AF69`; SURTR's own comment: "the whole CTA/focus system is tuned to it"), so the ring
 * lands on a colour the fill already contains. `OnboardingPill` also passes `focusedScale = 1f`, so like
 * the other two sites it has no scale-growth fallback to lean on either.
 *
 * The first fix darkened the focused fill toward the pill's ink, and run 10  reported
 * the same insufficiently highlighted confirmation-button defect again. A darker pill reads as
 * pressed, not chosen, and the ring was still gold on gold. The pill now shows focus the way a tile does,
 * with the lit ring outside its edge, and its focused fill is LIGHTER than its resting one.
 *
 * These pins:
 *  1. the FOCUSED gold-pill fill differs from its own RESTING fill, every theme;
 *  2. the RESTING fill is untouched (still byte-identical to `palette.id.ctaMaterial()`);
 *  3. the FOCUSED fill is lighter than the resting one, stop by stop, every theme; and
 *  4. the pill draws the tile ring outside itself, clear of its fill.
 */
class OnboardingGoldPillFocusAffordanceTest {
    @Test
    fun `focused gold pill fill differs from its own resting fill, every theme`() {
        MimirThemePalette.all.forEach { palette ->
            assertNotEquals(
                "theme ${palette.id}: focused gold pill fill should differ from resting fill",
                onboardingGoldPillFill(palette, focused = false),
                onboardingGoldPillFill(palette, focused = true),
            )
        }
    }

    @Test
    fun `resting gold pill fill is untouched by the focus fix`() {
        MimirThemePalette.all.forEach { palette ->
            assertEquals(
                "theme ${palette.id}",
                palette.id.ctaMaterial(),
                onboardingGoldPillFill(palette, focused = false),
            )
        }
    }

    @Test
    fun `a focused gold pill is lighter than a resting one, never darker`() {
        // Run 10: the focused pill came out duller than the resting one beside it. Pinned per stop, so a
        // blend that brightens the dark end while dimming the light end cannot pass on the average. A stop
        // that is already white (Niflheim's first) has nowhere lighter to go, so per stop it is "never
        // darker", and the pill as a whole must still come out lighter.
        MimirThemePalette.all.forEach { palette ->
            val resting = onboardingGoldPillFill(palette, focused = false)
            val focused = onboardingGoldPillFill(palette, focused = true)
            focused.forEachIndexed { index, stop ->
                assertTrue(
                    "theme ${palette.id}: focused stop $index must not be darker than its resting stop",
                    stop.luminance() >= resting[index].luminance(),
                )
            }
            assertTrue(
                "theme ${palette.id}: the focused pill must come out lighter than the resting one",
                focused.sumOf { it.luminance().toDouble() } > resting.sumOf { it.luminance().toDouble() },
            )
        }
    }

    @Test
    fun `a gold pill draws the selection ring on its own plate, before its etched surface`() {
        // Update: the gold pill is no longer filled in the ring's colour -- it is the PRIMARY etched chip -- so there is no accent-on-accent collision to stand off from.
        // The ring is drawn outside the bounds, so it has to come before the clip or the clip cuts it off, and before the surface so it hugs the plate itself (no inset)
        // and its wash is under the opaque metal.
        val onboardingPill = onboardingPillSource()
        val surface = onboardingPill.indexOf(".mimirEtchedChipSurface(palette, focused = focusAffordanceActive, primary = gold, edgeFlush = true)")
        val ring = onboardingPill.indexOf(".mimirSelectionRing(")
        val clip = onboardingPill.indexOf(".clip(shape)")
        assertTrue("OnboardingPill must draw the selection ring", ring >= 0)
        assertTrue("the surface is drawn after the ring", surface > ring)
        assertTrue("the ring must be drawn before the clip, or the clip cuts it off", surface < clip)
        assertFalse("the ring takes no inset: it is on the plate", onboardingPill.contains("inset ="))
        assertFalse("a pill with an etched surface is not filled in the ring's colour", onboardingPill.contains("filled = "))
        assertFalse("the pill keeps no tile ring of its own", onboardingPill.contains(".mimirFocusTileRing("))
        assertFalse("nor the border ring the ghost's edge line came from", onboardingPill.contains(".mimirFocusRing("))
    }

    /**
     * precedent (cited
     * by `UtilityPrimaryActionFocusAffordanceTest`): the three tests above pin
     * `onboardingGoldPillFill`'s own return values (the hint carousel's gold pills still wear that fill). Update: `OnboardingPill` no longer does -- its gold branch is a
     * warm etched chip and has no fill at all -- so the pin is that the pill source carries no gradient, no `fill`, and the etched surface warmed by `gold`.
     */
    @Test
    fun `OnboardingPill's gold branch is the primary etched chip with no fill`() {
        val onboardingPill = onboardingPillSource()

        assertTrue(
            "OnboardingPill's surface is the etched chip's, struck as a primary by the gold branch",
            onboardingPill.contains(".mimirEtchedChipSurface(palette, focused = focusAffordanceActive, primary = gold, edgeFlush = true)"),
        )
        assertFalse("a primary is never warmed (the selected look)", onboardingPill.contains("warmed = "))
        assertFalse("no gradient fill on the gold pill", onboardingPill.contains("Brush.linearGradient"))
        assertFalse("and no fill handed to the ring", onboardingPill.contains("fill = "))
        assertFalse("nor a background", onboardingPill.contains(".background("))
        assertFalse("the gold tint is gone with the fill", onboardingPill.contains("onboardingGoldPillFill("))
    }

    /**
     * A semantic collision between the focus-fill treatment and permission-sequence transitions
     * can survive a merge without a textual conflict, which is why this integration pin exists.
     *
     * The transition implementation added `OnboardingPill(interactive = false)` for the ACCESS sub-sequence's crossfades. Under
     * `AnimatedContent` the DEPARTING page stays composed for the whole `MimirMotion.OverlayMs`, and its
     * caller hands the pill `focused = focusedKey == OnboardingAccessContinueKey` — `true`, because
     * `OnboardingAccessContinueKey` is deliberately shared across pages. It gated only the RING on
     * `interactive`. Had the fill kept reading bare `focused`, the departing decorative pill would still have
     * painted the FOCUSED tint introduced by the focus-fill change, so every screen change would show two gold pills in
     * the focused colour and a single ring — a fresh selection-visibility failure
     * caused by the interaction of the two changes.
     *
     * So the ring and the tint are ONE decision and must be read off ONE predicate. This pins that: the
     * derived `focusAffordanceActive` exists, BOTH affordances consume it, and neither has drifted back to a
     * predicate of its own. A future edit that re-splits them reds here instead of on a physical device.
     */
    @Test
    fun `the gold tint and the focus ring are gated on one shared predicate`() {
        val onboardingPill = onboardingPillSource()

        assertTrue(
            "OnboardingPill must derive focusAffordanceActive = focused && interactive",
            onboardingPill.contains("val focusAffordanceActive = focused && interactive"),
        )
        assertTrue(
            "the focus ring must consume the shared predicate, not one of its own",
            onboardingPill.contains("focused = focusAffordanceActive"),
        )
        assertTrue(
            "and the ring's identity is the pill's own key, so a recycled slot cannot carry another pill's ring",
            onboardingPill.contains("identity = focusKey,"),
        )
        assertFalse(
            "the gold tint must not read bare `focused` -- a non-interactive pill would paint it",
            onboardingPill.contains("onboardingGoldPillFill(palette, focused)"),
        )
        assertFalse(
            "the ring must not re-derive the predicate inline; both affordances read the same val",
            onboardingPill.contains("focused = focused && interactive"),
        )
    }

    /** `OnboardingPill`'s own source text, sliced out of [onboardingFlowSource]. */
    private fun onboardingPillSource(): String = onboardingFlowSource()
        .substringAfter("private fun OnboardingPill(")
        .substringBefore("// (The ᛗ Mannaz badge")

    private fun onboardingFlowSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("OnboardingFlow.kt not found from ${File(".").absolutePath}")
    }
}
