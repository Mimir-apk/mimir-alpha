package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-06: the Tome collection and gamelist share one pin implementation: `LibraryFavoriteMarker`'s drawing moved
 * here so the Tome collection (which lives in `app`, and cannot see a `feature-library` `internal`)
 * can call the same component. These pin what "the same pin" MEANS, so a later edit cannot quietly
 * make the two surfaces differ again.
 *
 * **What these tests do NOT prove:** visual legibility. Legibility is a device judgement
 * and hardware validation establishes it. What is proved here is structural -- the envelope is the gamelist's
 * envelope, the mark carries its own ground rather than borrowing whatever is behind it, the ink is
 * the product-wide brand gold on all nine themes rather than a per-theme accent that can go dim, and
 * the presence transition stays compositor-cheap at any clock.
 *
 * Source characterization (the pattern `MimirNorseBookTest` already uses in this package): a
 * `@Composable`'s draw body is not sampleable from a plain JVM test in this module, so the parts that
 * are laws rather than numbers are pinned against the real file text.
 */
class MimirMannazPinTest {

    private val source: String by lazy { pinSource() }

    @Test
    fun `pin defaults are the envelope the gamelist authored`() {
        // These three are the gamelist's authored values, moved verbatim. feature-library's
        // LibraryFavoriteMarker* constants are aliases OF these, so this is the one place they live.
        assertEquals(32, MimirMannazPinWidthDp)
        assertEquals(24, MimirMannazPinHeightDp)
        assertEquals(0.88f, MimirMannazPinHiddenScale, 0f)
    }

    @Test
    fun `the plate's corner is one public constant, drawn by the pin at its own scale`() {
        // The shelf's disc badge stands on a plate of the same height and corner (`GameShelfCollapseBadge`), and reads this constant
        // rather than restating the pin's literal; the pin draws it in plate units, so it scales with the height like the rest.
        assertEquals(7, MimirMannazPinCornerDp)
        assertTrue("the pin draws its corner from the constant, scaled by the unit", source.contains("CornerRadius(MimirMannazPinCornerDp * unit)"))
        assertTrue("and it stays reachable from feature-library", source.contains("\nconst val MimirMannazPinCornerDp"))
    }

    @Test
    fun `width follows height at the authored four to three, exactly, at every size`() {
        // The draw body derives its unit from size.minDimension. If a caller could set width and
        // height independently, a plate wider than tall would keep the height as the unit but a plate
        // TALLER than wide would silently switch to the width -- re-proportioning the rune at the
        // exact moment someone shrinks the pin to fit a compact face. Deriving width makes the height
        // the minimum dimension by construction.
        assertEquals(32.dp, mimirMannazPinWidth(MimirMannazPinHeightDp.dp))
        assertEquals(16.dp, mimirMannazPinWidth(12.dp))
        assertEquals(24.dp, mimirMannazPinWidth(18.dp))
        val tall = mimirMannazPinWidth(9.dp)
        assertTrue("width must never fall below the height, or the unit regime flips", tall > 9.dp)
    }

    @Test
    fun `the presence clock defaults to the gamelist's and is overridable per caller`() {
        // The Tome collection's pin/unpin acknowledgement runs on its own acknowledgement clock
        // (HomeTomeMutationAckMs = 110ms). It
        // must be able to keep that WITHOUT forking the drawing -- which is only possible if the
        // clock is a parameter and the gamelist's value is the default.
        assertTrue(
            "MimirMannazPin must take a durationMs parameter defaulted to MimirMotion.FocusMs -- " +
                "hardcoding the clock would force any caller with a different approved clock to fork " +
                "the component, which is exactly what this hoist exists to prevent",
            source.contains("durationMs: Int = MimirMotion.FocusMs"),
        )
        assertTrue(
            "the animation must actually READ durationMs, not just accept it",
            source.contains("tween(durationMs, easing = MimirMotion.Easing)"),
        )
    }

    @Test
    fun `presence is a compositor-cheap alpha and scale transform with no layout input`() {
        // Invariant #7, and the contract the Tome collection's outgoing diamond marker was carrying
        // (app's HomeTomeLibraryHeaderStatusMotionTest pinned it there). It moves here with the
        // drawing: a pin that could reflow would displace a sibling on every toggle.
        val envelope = source.sliceBetween(".graphicsLayer {", ".drawWithCache {")
        assertTrue(envelope.contains("alpha = presence.value"))
        assertTrue(envelope.contains("scaleX = scale"))
        assertTrue(envelope.contains("scaleY = scale"))
        // Every layout-phase modifier is banned inside the animated envelope, not just the obvious
        // ones: the plate's real .width()/.height() sit BEFORE .graphicsLayer, so anything of that
        // kind appearing inside this slice is by definition animated geometry.
        for (layoutModifier in listOf(".size(", ".padding(", ".offset(", ".width(", ".height(", ".layout(")) {
            assertFalse(
                "the animated presence must not drive $layoutModifier -- that reintroduces a " +
                    "layout-affecting animation, and this mark's whole contract is that toggling it " +
                    "cannot reflow or displace the tile it sits on (invariant #7)",
                envelope.contains(layoutModifier),
            )
        }
        assertTrue(
            "the node must stay MOUNTED and animate to zero alpha, never mount/unmount on presence",
            source.contains("presence.animateTo("),
        )
    }

    @Test
    fun `the rune ink is the product-wide brand gold, not a per-theme accent`() {
        // This is the legibility mechanism the observed contrast defect depends on. The previous marker
        // (the Tome collection's 7dp diamond) is filled with palette.accentBright -- a per-theme
        // role. Pinned/favourite is ONE product-wide semantic mark; routing it through a theme
        // accent is what lets it vanish into a cover on the themes whose accent is dim.
        val body = source.sliceBetween(".drawWithCache {", ".clearAndSetSemantics")
        assertTrue(
            "the rune ink must be MimirThemePalette.BrandGoldBright",
            body.contains("val ink = MimirThemePalette.BrandGoldBright"),
        )
        assertFalse(
            "the rune must not be re-routed to palette.accent/accentBright -- that is the treatment " +
                "was reported as barely visible",
            body.contains("palette.accent"),
        )
    }

    @Test
    fun `the plate carries its own ground so contrast does not depend on what is behind it`() {
        // A bare stroked rune inherits whatever cover art sits under it. The plate is why this pin
        // reads on a book cover at all: a leather gradient plus a hardware rim, drawn BEFORE the ink.
        // The brushes are built once per size in the cache block (2026-09-30, with the mark gleam);
        // the draw calls stay in onDrawBehind, in their order.
        val built = source.sliceBetween(".drawWithCache {", "val ink =")
        assertTrue(
            "the pin must build its own leather ground (hero.coverTop -> hero.coverBottom)",
            built.contains("Brush.verticalGradient(listOf(hero.coverTop, hero.coverBottom))"),
        )
        val underTheRune = source.sliceBetween("onDrawBehind {", "drawMimirMannazRune(inkBrush")
        assertTrue(
            "the pin must draw its leather ground before the rune",
            underTheRune.contains("brush = leather"),
        )
        assertTrue(
            "the pin must draw its own hardware rim, or it dissolves into a light cover",
            underTheRune.contains("color = hero.hardware.copy(alpha = 0.72f)"),
        )
        // Ordering is carried by the slice itself, not by a separate assertion: `underTheRune` ends at
        // the ink pass, so anything found in it is provably drawn before the rune. (A third assertion
        // comparing indexOf against body.length was written here first and deleted -- indexOf is
        // always < length, including when it returns -1, so it could never have failed.)
    }

    @Test
    fun `the pin is reachable from outside core-ui`() {
        // The entire reason for the hoist: app's Tome collection cannot see a feature-library
        // internal. If any of these picked up a visibility modifier, the hoist stops solving the
        // problem it exists for -- silently, because feature-library would still compile.
        //
        // These carried no failure messages when first written; a differential probe that marked the
        // composable `internal` failed with a bare `java.lang.AssertionError`, which names neither the
        // symbol nor the consequence. Every one of them says what broke now.
        for (declaration in listOf(
            "\nfun MimirMannazPin(",
            "\nfun mimirMannazPinWidth(",
            "\nconst val MimirMannazPinWidthDp",
            "\nconst val MimirMannazPinHeightDp",
            "\nconst val MimirMannazPinHiddenScale",
        )) {
            assertTrue(
                "'${declaration.trim()}' must stay PUBLIC. The Tome collection lives in :app, which " +
                    "depends on :core-ui and :feature-library but can see neither module's internals -- " +
                    "an internal here would still compile everywhere and still render the gamelist " +
                    "correctly, while quietly making the shared-pin contract unimplementable.",
                source.contains(declaration),
            )
        }
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun pinSource(): String {
        val name = "MimirMannazPin.kt"
        val candidates = listOf(
            java.io.File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("$name not found from ${java.io.File(".").absolutePath}")
    }
}
