package com.gruning.mimir.rewrite

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  [OnboardingProgressBarModeTest] pins the pure decision
 * ([onboardingProgressBarMode]); this class pins that `OnboardingSettlingScreen` actually wires each of its
 * three outcomes to the right composable, which a plain JVM test cannot exercise directly (it is Compose
 * UI, and the `app` module carries neither Robolectric nor a Compose test rule -- same reasoning as
 * `OnboardingSettlementCancellationTest`'s header comment). Source-pin idiom, same anchored-slice
 * discipline as `MimirAchievementCardPhaseSequenceGrammarTest`.
 */
class OnboardingSettlingProgressBarWiringTest {
    private val source: String by lazy { pinSource() }
    private val progressBarBlock: String by lazy {
        source.sliceBetween("when (progressBarMode) {", "\n                Row(Modifier.fillMaxWidth()) {")
    }

    @Test
    fun `DETERMINATE still renders today's fillMaxWidth fraction fill, unchanged`() {
        val block = progressBarBlock.sliceBetween(
            "OnboardingProgressBarMode.DETERMINATE ->",
            "OnboardingProgressBarMode.SWEEP ->",
        )
        assertTrue(block.contains(".background(palette.outline)"))
        assertTrue(block.contains(".fillMaxWidth(fraction)"))
        assertTrue(block.contains(".background(phaseColor)"))
    }

    @Test
    fun `SWEEP renders the extracted core-ui primitive at the same 6dp rounded footprint`() {
        val block = progressBarBlock.sliceBetween(
            "OnboardingProgressBarMode.SWEEP ->",
            "OnboardingProgressBarMode.HIDDEN ->",
        )
        assertTrue(
            "must call the extracted core-ui primitive, not a hand-rolled sweep",
            block.contains("MimirIndeterminateBar("),
        )
        assertTrue(block.contains(".height(6.dp)"))
        assertTrue(block.contains(".clip(RoundedCornerShape(3.dp))"))
        assertTrue(
            "trackColor must still be palette.outline -- the same neutral base the determinate track used",
            block.contains("trackColor = palette.outline"),
        )
        assertTrue(
            "sweepColor must be phaseColor -- same accent the (unreachable) determinate fill uses",
            block.contains("sweepColor = phaseColor"),
        )
    }

    @Test
    fun `HIDDEN reserves the same 6dp height and paints no background at all`() {
        val block = progressBarBlock.substringAfter("OnboardingProgressBarMode.HIDDEN ->")
        assertTrue(block.contains(".height(6.dp)"))
        assertTrue(
            "the HIDDEN arm must not paint a background -- a visible-but-frozen track is the exact defect " +
                "this regression fix addresses",
            !block.contains("background"),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to a no-op pass. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun pinSource(): String {
        val name = "OnboardingFlow.kt"
        val candidates = listOf(
            java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            java.io.File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("$name not found from ${java.io.File(".").absolutePath}")
    }
}
