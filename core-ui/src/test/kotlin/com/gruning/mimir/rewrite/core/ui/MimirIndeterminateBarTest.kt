package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MimirIndeterminateBar] is an EXTRACTION of
 * [MimirLedgerRule]'s sweep motion, not a substitution for it: [MimirLedgerRule]'s own Canvas is left
 * completely untouched (it paints a thin gradient stroke on a decorative hairline+diamond rule, a different
 * shape than this primitive's solid rounded band), and only the three motion constants
 * ([SweepHalfWidth]/[LedgerPulseMs]/[SweepAlpha]) moved to be shared.
 *
 * Source-pin idiom, same anchored-slice discipline as `MimirAchievementCardPhaseSequenceGrammarTest`'s
 * `MimirAchievementCardSourceTest`: neither file's Compose animation runs in a plain JVM test, so the
 * behaviour this test class can verify without a device is "the right code exists, in the right shape and
 * order" — not the rendered pixels.
 */
class MimirIndeterminateBarSourceTest {
    private val barSource: String by lazy { pinSource("MimirIndeterminateBar.kt") }
    private val cardSource: String by lazy { pinSource("MimirAchievementCard.kt") }

    // ---- the constants moved, they were not duplicated ----

    @Test
    fun `the three motion constants are declared exactly once, in MimirIndeterminateBar dot kt`() {
        assertEquals(
            "SweepHalfWidth must have exactly one declaration -- MimirLedgerRule now references this file's",
            1,
            Regex("""const val SweepHalfWidth\s*=""").findAll(barSource).count(),
        )
        assertEquals(
            "LedgerPulseMs must have exactly one declaration",
            1,
            Regex("""const val LedgerPulseMs\s*=""").findAll(barSource).count(),
        )
        assertEquals(
            "SweepAlpha must have exactly one declaration",
            1,
            Regex("""const val SweepAlpha\s*=""").findAll(barSource).count(),
        )
        assertFalse(
            "MimirAchievementCard.kt must no longer declare its own copy of SweepHalfWidth",
            cardSource.contains("private const val SweepHalfWidth"),
        )
        assertFalse(
            "MimirAchievementCard.kt must no longer declare its own copy of LedgerPulseMs",
            cardSource.contains("private const val LedgerPulseMs"),
        )
        assertFalse(
            "MimirAchievementCard.kt must no longer declare its own copy of SweepAlpha",
            cardSource.contains("private const val SweepAlpha"),
        )
    }

    @Test
    fun `the moved constants keep MimirLedgerRule's exact original values -- not re-tuned in the move`() {
        assertTrue(barSource.contains("SweepHalfWidth = 0.18f"))
        assertTrue(barSource.contains("LedgerPulseMs = 1400"))
        assertTrue(barSource.contains("SweepAlpha = 0.45f"))
    }

    // ---- MimirLedgerRule's own painting is untouched: same sweep math, same Canvas, still private ----

    @Test
    fun `MimirLedgerRule still drives its sweep with the shared constants, unchanged`() {
        assertTrue(
            "MimirLedgerRule must still be the card's own private composable -- not deleted or delegated " +
                "away in a way that changes its declaration",
            cardSource.contains("private fun MimirLedgerRule(palette: MimirThemePalette, pulse: Boolean, reducedMotion: Boolean)"),
        )
        val ruleBlock = cardSource
            .substringAfter("private fun MimirLedgerRule(palette: MimirThemePalette, pulse: Boolean, reducedMotion: Boolean) {")
            .substringBefore("\n// ─")
        // Anchored to the trailing comma/paren so a tampered multiplier (e.g. `-SweepHalfWidth * 2f`)
        // cannot still satisfy a looser `contains("...SweepHalfWidth")` substring check.
        assertTrue(ruleBlock.contains("initialValue = -SweepHalfWidth,"))
        assertTrue(ruleBlock.contains("targetValue = 1f + SweepHalfWidth,"))
        assertTrue(ruleBlock.contains("tween(LedgerPulseMs, easing = LinearEasing)"))
        assertTrue(ruleBlock.contains("palette.accent.copy(alpha = SweepAlpha)"))
        // Still a thin STROKED line (drawLine), never converted to a filled block (drawRect) -- that
        // conversion is exactly the shape change this primitive exists to avoid forcing onto the card.
        assertTrue(ruleBlock.contains("drawLine("))
    }

    // ---- MimirIndeterminateBar: reduced motion is checked BEFORE any animation is set up, and the
    //      caller's modifier (hence its reserved footprint) is still applied when hidden ----

    @Test
    fun `reduced motion returns before rememberInfiniteTransition is ever called`() {
        val guardIndex = barSource.indexOf("if (reducedMotion) {")
        val transitionIndex = barSource.indexOf("rememberInfiniteTransition(")
        assertTrue("the reducedMotion guard must exist", guardIndex >= 0)
        assertTrue("rememberInfiniteTransition must exist", transitionIndex >= 0)
        assertTrue(
            "the reducedMotion guard must appear BEFORE rememberInfiniteTransition is set up, so reduced " +
                "motion never starts an animation it then has to suppress",
            guardIndex < transitionIndex,
        )
    }

    @Test
    fun `reduced motion still applies the caller's modifier -- the footprint is reserved, not dropped`() {
        val guardBlock = barSource.substringAfter("if (reducedMotion) {").substringBefore("return\n    }")
        assertTrue(
            "hiding must still emit Box(modifier), not Box() / Box(Modifier) -- dropping the caller's " +
                "modifier here is exactly the layout-shift regression the brief warned against",
            guardBlock.contains("Box(modifier)"),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun pinSource(name: String): String {
        val candidates = listOf(
            java.io.File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("$name not found from ${java.io.File(".").absolutePath}")
    }
}
