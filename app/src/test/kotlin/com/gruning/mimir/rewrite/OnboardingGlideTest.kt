package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which way a screen goes when the player moves through a sequence.
 *
 * Onboarding previously faded without glide, so forward and backward transitions looked identical.
 *
 * [onboardingGlide] returns a `ContentTransform` whose enter/exit internals are not readable from a unit
 * test, which is why [onboardingGlideOffsetPx] exists as a separate pure function: the entire content of
 * *"back and forth"* is the sign of that number, and the sign is checkable.
 *
 * These assert RELATIONSHIPS rather than a tuning constant. [MimirMotion.GlideTravelDp] may change
 * after device calibration; pinning 22 would fail a retune without explaining
 * about whether back still looks different from forward.
 */
class OnboardingGlideTest {

    private val travel = 66 // MimirMotion.GlideTravelDp at density 3; any positive number works here.

    private fun offset(forward: Boolean, incoming: Boolean, reducedMotion: Boolean = false) =
        onboardingGlideOffsetPx(forward, travel, incoming = incoming, reducedMotion = reducedMotion)

    /**
     * The ruling itself. Going back must not produce the same motion as going forward -- if it does, the
     * transition is decoration and the player learns nothing from it.
     */
    @Test
    fun `back is the mirror of forward, not a repeat of it`() {
        assertEquals(-offset(forward = true, incoming = true), offset(forward = false, incoming = true))
        assertEquals(-offset(forward = true, incoming = false), offset(forward = false, incoming = false))
    }

    /**
     * Both screens must travel the SAME way across the display: the arriving one starts to the right of
     * where it will settle and the departing one ends to the left of where it was, so forward reads as one
     * surface sliding left rather than two surfaces passing through each other.
     *
     * This is the half that a sign error would break while the mirror test above still passed.
     */
    @Test
    fun `the arriving and departing screens move in the same direction`() {
        assertTrue(
            "forward: the arriving screen must start to the right of its resting place",
            offset(forward = true, incoming = true) > 0,
        )
        assertTrue(
            "forward: the departing screen must end to the left of where it sat",
            offset(forward = true, incoming = false) < 0,
        )
        assertTrue(
            "back: the arriving screen must start to the left of its resting place",
            offset(forward = false, incoming = true) < 0,
        )
        assertTrue(
            "back: the departing screen must end to the right of where it sat",
            offset(forward = false, incoming = false) > 0,
        )
    }

    /** Travel is symmetric: neither direction is further than the other. */
    @Test
    fun `forward and back travel the same distance`() {
        val distances = listOf(true, false).flatMap { forward ->
            listOf(true, false).map { incoming -> kotlin.math.abs(offset(forward, incoming)) }
        }
        assertEquals(setOf(travel), distances.toSet())
    }

    /**
     * Reduced motion means no movement, in every combination -- not merely movement short enough to miss.
     * The zero-length tween would hide a non-zero offset here, so nothing but this test can catch it.
     */
    @Test
    fun `reduced motion removes the travel entirely`() {
        listOf(true, false).forEach { forward ->
            listOf(true, false).forEach { incoming ->
                assertEquals(
                    "forward=$forward incoming=$incoming still travels under reduced motion",
                    0,
                    offset(forward, incoming, reducedMotion = true),
                )
            }
        }
    }

    /**
     * The token stays small enough to read as a settle rather than a swipe. A bound, not the value: the
     * point is that a retune cannot quietly turn a handover into a page push.
     */
    @Test
    fun `the travel token stays in handover range`() {
        assertTrue(
            "GlideTravelDp = ${MimirMotion.GlideTravelDp} reads as a swipe, not a handover",
            MimirMotion.GlideTravelDp in 8..40,
        )
    }
}
