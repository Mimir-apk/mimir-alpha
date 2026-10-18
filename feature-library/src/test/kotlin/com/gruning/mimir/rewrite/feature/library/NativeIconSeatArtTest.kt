package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.Animatable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Favorites and add-picker icon seats: the seat's ground (fill and resting outline) is
 * the no-art fallback's seat, so it draws only for an UNWIRED icon (blank, or its file gone), or with a loaded one, fading in with it.
 * A wired icon that is loading or failed draws no ground at all.
 */
class NativeIconSeatArtTest {

    private fun art(wired: Boolean, alpha: Float, failed: Boolean = false) =
        NativeIconArt(wired = wired, painter = null, alpha = Animatable(alpha), failed = failed)

    @Test
    fun `an unwired icon keeps its ground whole, it carries the letter`() {
        assertEquals(1f, art(wired = false, alpha = 0f).groundAlpha(), 0f)
    }

    @Test
    fun `a wired icon that is loading draws no ground`() {
        assertEquals(0f, art(wired = true, alpha = 0f).groundAlpha(), 0f)
    }

    @Test
    fun `a wired icon that failed draws no ground`() {
        assertEquals(0f, art(wired = true, alpha = 0f, failed = true).groundAlpha(), 0f)
    }

    @Test
    fun `a loaded icon keeps its ground, and it follows the icon's reveal`() {
        assertEquals(1f, art(wired = true, alpha = 1f).groundAlpha(), 0f)
        assertEquals(0.4f, art(wired = true, alpha = 0.4f).groundAlpha(), 0f)
    }
}
