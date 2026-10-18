package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FINISH, the one DONE page since the tail folded into it (policy 2026-09-28
 * Q3). It replaced this file's tail pins -- the four-screen `AnimatedContent`, its glide, and the tail's
 * distinct focus keys -- which had nothing left to guard. Same minimal-test posture as before: the pure motion
 * spec ([onboardingAccessMotionSpec]) is covered by [OnboardingAccessMotionSpecTest]; this file pins the wiring
 * a Robolectric harness can't reliably sample mid-flight (real file text).
 */
class OnboardingFinishMotionSourceTest {
    private val source: String by lazy { sourceFile("OnboardingFlow.kt") }

    /**
     * The legend is FINISH's one glass surface, and it enters once through the house glass entry on the
     * onboarding clock. Under reduced motion that clock is 0 ms and the progress is born at 1, so the panel is
     * never drawn at the entry's lifted, transparent start.
     */
    @Test
    fun `the legend enters through the glass panel entry on the reduced-motion-aware onboarding clock`() {
        val body = source.sliceBetween(
            "private fun OnboardingFinishBody(",
            "private fun OnboardingFinishLegendList(",
        )
        assertTrue(body.contains("mimirReducedMotion()"))
        assertTrue(
            "the progress must be born at rest under reduced motion",
            Regex("""Animatable\(if \((\w+)\) 1f else 0f\)""").containsMatchIn(body),
        )
        assertTrue(body.contains("onboardingAccessMotionSpec("))
        assertTrue(body.contains("MimirLiquidGlassPanel("))
        assertTrue(body.contains(".mimirGlassPanelEntry {"))
    }

    @Test
    fun `FINISH is one page, so nothing on it hands over and the header strip is drawn once`() {
        val screen = source.sliceBetween(
            "private fun OnboardingDoneScreen(",
            "/** The finishing check echoes the welcome mark",
        )
        assertFalse("FINISH has no pages to glide between", screen.contains("AnimatedContent("))
        assertEquals(
            "FINISH wears the header strip once, in the scaffold's seat, and not the ceremony lockup",
            1,
            Regex("OnboardingHeaderStrip\\(").findAll(screen).count(),
        )
        assertFalse(screen.contains("OnboardingHeaderLockup("))
        assertEquals(
            "one pill, Enter Mimir, bound to the completion key it always had",
            1,
            Regex("OnboardingPill\\(").findAll(screen).count(),
        )
        assertTrue(screen.contains("focusKey = OnboardingDoneKey,"))
        assertTrue(screen.contains("onActivate = onFinish,"))
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of silently degrading to a no-op pass. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        val fromAfter = substringAfter(after)
        assertTrue("anchor missing: $before", fromAfter.contains(before))
        return fromAfter.substringBefore(before)
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
