package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * fix the denominator before the bar gets a glide.
 *
 * `done` is computed as `total - resolvingCount` and regrouping SHRINKS `total` mid-run, so the raw
 * fraction can fall while nothing was undone. A bar that falls claims work un-happened, which the job
 * model forbids in as many words: *seen is work that has demonstrably happened*.
 */
class OnboardingMonotonicProgressTest {

    @Test
    fun `a fraction never falls within a phase`() {
        // The real shape: 40 of 61 (0.656), then regrouping cuts the total to 40 and done to 21.
        val before = 40f / 61f
        val afterRegroup = 21f / 40f
        assertEquals(
            "a shrinking denominator must not render as regress",
            before,
            onboardingMonotonicProgress(before, afterRegroup)!!,
            0.0001f,
        )
    }

    @Test
    fun `it still rises`() {
        assertEquals(0.8f, onboardingMonotonicProgress(0.5f, 0.8f)!!, 0.0001f)
    }

    @Test
    fun `losing the denominator beats the high-water mark`() {
        // Null total means the product no longer knows the fraction. The mark must not keep drawing a
        // bar it can no longer justify -- the same rule as "a null total draws no bar", one layer up.
        assertNull(onboardingMonotonicProgress(0.9f, null))
    }

    @Test
    fun `the first reading is taken as-is`() {
        assertEquals(0.25f, onboardingMonotonicProgress(null, 0.25f)!!, 0.0001f)
        assertNull(onboardingMonotonicProgress(null, null))
    }
}
