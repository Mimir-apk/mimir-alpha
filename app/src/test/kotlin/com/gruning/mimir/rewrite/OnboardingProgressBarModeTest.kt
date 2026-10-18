package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 *  pins [onboardingProgressBarMode], the pure branch
 * selector `OnboardingSettlingScreen` uses to choose between the (currently unreachable) determinate fill,
 * today's real indeterminate sweep, and reduced motion's "render nothing, keep the footprint" hide.
 *
 * Plain-Kotlin on purpose: the decision itself is pure arithmetic/branching, independent of Compose
 * composition or an animation clock.
 */
class OnboardingProgressBarModeTest {

    // ---- null progress (today's real case: OnboardingSettlementUi.total is deliberately always null,
    //      commit 5e8b9cb6) -- must sweep, never sit on a static empty track ----

    @Test
    fun `null progress sweeps when motion is allowed`() {
        assertEquals(
            OnboardingProgressBarMode.SWEEP,
            onboardingProgressBarMode(progress = null, reducedMotion = false),
        )
    }

    @Test
    fun `null progress hides under reduced motion -- never a static empty track`() {
        assertEquals(
            OnboardingProgressBarMode.HIDDEN,
            onboardingProgressBarMode(progress = null, reducedMotion = true),
        )
    }

    // ---- progress == 0f takes the SAME path as null -- this is the ORIGINAL guard's `progress > 0f`,
    //      preserved exactly, not loosened to `>= 0f` ----

    @Test
    fun `zero progress is treated as no progress, not as determinate`() {
        assertEquals(
            OnboardingProgressBarMode.SWEEP,
            onboardingProgressBarMode(progress = 0f, reducedMotion = false),
        )
        assertEquals(
            OnboardingProgressBarMode.HIDDEN,
            onboardingProgressBarMode(progress = 0f, reducedMotion = true),
        )
    }

    // ---- a real positive fraction -- unreachable today (no total exists to produce one) but the ruling
    //      does not delete this path for a future worker that DOES have an exact total ----

    @Test
    fun `positive progress is determinate regardless of reduced motion`() {
        assertEquals(
            OnboardingProgressBarMode.DETERMINATE,
            onboardingProgressBarMode(progress = 0.42f, reducedMotion = false),
        )
        assertEquals(
            "a real fraction is honest content, not motion -- reduced motion must not hide it",
            OnboardingProgressBarMode.DETERMINATE,
            onboardingProgressBarMode(progress = 0.42f, reducedMotion = true),
        )
    }

    @Test
    fun `progress of exactly 1f is still determinate`() {
        assertEquals(
            OnboardingProgressBarMode.DETERMINATE,
            onboardingProgressBarMode(progress = 1f, reducedMotion = false),
        )
    }
}
