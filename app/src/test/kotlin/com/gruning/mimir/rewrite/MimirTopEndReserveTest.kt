package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.OverlayButtonBounds
import org.junit.Assert.assertEquals
import org.junit.Test

/** F10: the corner chrome makes room for the M badge only where the badge actually is. */
class MimirTopEndReserveTest {

    // The Nova at 2x: 1280 px wide, the default badge 56 dp square, 14 dp in from the top and right.
    private fun badge(left: Int, top: Int) = OverlayButtonBounds(left, top, left + 112, top + 112, 1280, 960)

    @Test
    fun `the default top-right badge reserves its width from the right edge plus the gap`() {
        assertEquals(70f + MimirTopEndReserveGapDp, mimirTopEndReserveDp(badge(left = 1280 - 140, top = 28), 2f), 0.01f)
    }

    @Test
    fun `a badge on the left, low down, or absent reserves nothing`() {
        assertEquals(0f, mimirTopEndReserveDp(badge(left = 28, top = 28), 2f), 0f)
        assertEquals(0f, mimirTopEndReserveDp(badge(left = 1140, top = 600), 2f), 0f)
        assertEquals(0f, mimirTopEndReserveDp(null, 2f), 0f)
    }
}
