package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settle gleam. The ring never regrows
 * (CP07); one band of light crosses its bright stroke once the selection holds still.
 */
class MimirFocusSettleGlintTest {

    @Test
    fun onlyASettledSelectionGleamsAndReducedMotionNever() {
        assertTrue(mimirFocusGlintArmed(settledOn = "tile", reducedMotion = false))
        assertFalse(mimirFocusGlintArmed(settledOn = null, reducedMotion = false))
        assertFalse(mimirFocusGlintArmed(settledOn = "tile", reducedMotion = true))
    }

    @Test
    fun theBandIsOutOfSightAtBothEndsOfItsPass() {
        val width = 150f
        val height = 200f
        val outset = 3f
        val reach = 37.5f

        val start = mimirFocusGlintShiftPx(0f, width, height, outset, reach)
        val end = mimirFocusGlintShiftPx(1f, width, height, outset, reach)

        // The band spans x + y in [2 shift, 2 shift + 2 reach]; the stroke's rect spans [-2 outset, w + h + 2 outset].
        assertEquals("the band ends where the rect begins", -2f * outset, 2f * start + 2f * reach, 0.001f)
        assertEquals("the band begins where the rect ends", width + height + 2f * outset, 2f * end, 0.001f)
        // And travel outside 0..1 is clamped rather than extrapolated past either end.
        assertEquals(start, mimirFocusGlintShiftPx(-1f, width, height, outset, reach), 0f)
        assertEquals(end, mimirFocusGlintShiftPx(2f, width, height, outset, reach), 0f)
    }

    @Test
    fun theGleamIsOnePassWithinTheHouseTransientBoundAndIdleDrawsNothing() {
        // The ring and its gleam slowed product-wide: the gleam changed from 2x to 2.5x, then 3x.
        assertEquals(MimirMotion.OverlayMs * 3, MimirFocusGlintMs)
        assertEquals(1f, MimirFocusRingGlintIdle, 0f)
        assertEquals(MimirFocusRingGlintIdle, MimirFocusRingNoGlint(), 0f)
    }
}
