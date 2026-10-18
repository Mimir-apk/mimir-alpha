package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four corner sparkles twinkle in sequence, not all at once. */
class MimirFocusSparkleSequenceTest {

    private fun lit(at: Float) = (0 until MimirFocusSparkleStars).filter { mimirFocusSparkleStarScale(at, it) > 0f }

    @Test
    fun `never more than two stars are lit, and a star at its peak is alone`() {
        (0..1000).map { it / 1000f }.forEach { at ->
            val stars = lit(at)
            assertTrue("$at lit $stars", stars.size <= 2)
            stars.forEach { star ->
                if (mimirFocusSparkleStarScale(at, star) > 0.95f) assertEquals("$at peak of $star shared", 1, stars.size)
            }
        }
    }

    @Test
    fun `the stars peak clockwise from the bottom-right`() {
        fun peakAt(star: Int) = (0..1000).maxBy { mimirFocusSparkleStarScale(it / 1000f, star) }
        // 0 bottom-right, 1 top-right, 3 top-left, 2 bottom-left.
        assertTrue(peakAt(0) < peakAt(1))
        assertTrue(peakAt(1) < peakAt(3))
        assertTrue(peakAt(3) < peakAt(2))
    }
}
