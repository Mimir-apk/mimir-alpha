package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rim of an `edgeFlush` etched chip takes the ring's colour as the ring arrives. Two layers carry
 * it: the chip's own metal rim, thinned, and a ring-coloured rim over it. Their coverage together must run straight from the rim at rest to the ring's
 * bright stroke, never dip on the way (a plain cross-fade of two whole layers sags to three quarters half way, which read as a gap in the outline).
 */
class MimirEtchedChipRimHandoverTest {

    private fun covered(under: Float, tint: Float) = 1f - (1f - under) * (1f - tint)

    @Test
    fun `the two rim layers together run straight from the rim at rest to the ring's stroke`() {
        for (rest in listOf(0.28f, 0.4f, 0.7f, 1f)) {
            for (step in 0..20) {
                val h = step / 20f
                val expected = rest + (MimirFocusRingBrightAlpha - rest) * h
                val actual = covered(mimirEtchedChipRimUnderAlpha(rest, h), mimirEtchedChipRimTintAlpha(h))
                assertEquals("rest $rest at hand-over $h", expected, actual, 1e-4f)
            }
        }
    }

    @Test
    fun `at the ends it is the old rim at rest and the ring's own stroke when handed over`() {
        for (rest in listOf(0.28f, 0.4f, 1f)) {
            assertEquals("at rest the metal rim alone, as before", rest, mimirEtchedChipRimUnderAlpha(rest, 0f), 1e-6f)
            assertEquals("and no tint", 0f, mimirEtchedChipRimTintAlpha(0f), 0f)
            assertEquals("handed over, the metal is gone", 0f, mimirEtchedChipRimUnderAlpha(rest, 1f), 1e-6f)
            assertEquals("and the tint is the ring's bright stroke", MimirFocusRingBrightAlpha, mimirEtchedChipRimTintAlpha(1f), 1e-6f)
        }
    }

    @Test
    fun `a cross-fade of the two layers would have dipped, and this does not`() {
        val rest = 1f
        val crossFadeHalfWay = covered(rest * 0.5f, rest * 0.5f)
        assertTrue("fixture: the plain cross-fade of a whole rim sags to three quarters ($crossFadeHalfWay)", crossFadeHalfWay < rest * 0.8f)
        val ours = covered(mimirEtchedChipRimUnderAlpha(rest, 0.5f), mimirEtchedChipRimTintAlpha(0.5f))
        assertTrue("the hand-over never falls under the rim at rest ($ours)", ours >= rest * 0.95f)
    }

    @Test
    fun `out-of-range hand-overs are clamped`() {
        assertEquals(mimirEtchedChipRimUnderAlpha(0.4f, 0f), mimirEtchedChipRimUnderAlpha(0.4f, -3f), 0f)
        assertEquals(mimirEtchedChipRimUnderAlpha(0.4f, 1f), mimirEtchedChipRimUnderAlpha(0.4f, 3f), 0f)
        assertEquals(MimirFocusRingBrightAlpha, mimirEtchedChipRimTintAlpha(2f), 0f)
    }
}
