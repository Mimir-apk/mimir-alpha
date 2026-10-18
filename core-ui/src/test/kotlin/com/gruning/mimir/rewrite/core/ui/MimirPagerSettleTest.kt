package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pager settle rule every finger-following pager shares, moved here from onboarding's carousel test (Thor Bottom revamp B3). */
class MimirPagerSettleTest {

    @Test
    fun `a drag lands one page away at most, a fling turns, a slow drag lands on the nearest`() {
        val fling = 400f
        assertEquals(11, mimirPagerSettlePage(from = 10, current = 10, velocityPx = -900f, flingVelocityPx = fling))
        assertEquals(9, mimirPagerSettlePage(from = 10, current = 10, velocityPx = 900f, flingVelocityPx = fling))
        assertEquals(11, mimirPagerSettlePage(from = 10, current = 11, velocityPx = 0f, flingVelocityPx = fling))
        assertEquals(10, mimirPagerSettlePage(from = 10, current = 10, velocityPx = -100f, flingVelocityPx = fling))
        assertEquals(11, mimirPagerSettlePage(from = 10, current = 13, velocityPx = 0f, flingVelocityPx = fling))
    }

    @Test
    fun `the fling threshold is inclusive and the same 400 dp for every pager`() {
        assertEquals(400.dp, MimirPagerFlingVelocity)
        assertEquals(1, mimirPagerSettlePage(from = 0, current = 0, velocityPx = -400f, flingVelocityPx = 400f))
        assertEquals(0, mimirPagerSettlePage(from = 0, current = 0, velocityPx = -399f, flingVelocityPx = 400f))
        assertEquals(-1, mimirPagerSettlePage(from = 0, current = 0, velocityPx = 400f, flingVelocityPx = 400f))
    }

    @Test
    fun `the settle is the overlay clock on the house easing, and snaps under reduced motion`() {
        assertEquals(MimirMotion.OverlayMs, mimirPagerSettleSpec(reducedMotion = false).durationMillis)
        assertEquals(0, mimirPagerSettleSpec(reducedMotion = true).durationMillis)
        assertEquals(MimirMotion.Easing, mimirPagerSettleSpec(reducedMotion = false).easing)
    }
}
