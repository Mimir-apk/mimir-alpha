package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focus arrival flare. */
class MimirFocusFlareTest {

    @Test
    fun `a flare fires on a change onto a control, never on mount, on leaving, or under reduced motion`() {
        assertFalse("mount: born on this value", mimirFocusFlareArmed("continue", "continue", reducedMotion = false))
        assertTrue("focus moved here", mimirFocusFlareArmed("not-now", null, reducedMotion = false))
        assertTrue("focus moved here from a sibling key", mimirFocusFlareArmed("not-now", "continue", reducedMotion = false))
        assertFalse("focus left", mimirFocusFlareArmed(null, "continue", reducedMotion = false))
        assertFalse("reduced motion", mimirFocusFlareArmed("not-now", null, reducedMotion = true))
        // Ring migration (2026-09-28): a held pad re-keys every 120 ms; a flare on each step would pulse the whole way.
        assertFalse("mid-traverse", mimirFocusFlareArmed("not-now", "continue", reducedMotion = false, streaming = true))
        assertTrue("the traverse has ended", mimirFocusFlareArmed("not-now", "continue", reducedMotion = false, streaming = false))
    }

    @Test
    fun `at zero heat each stroke is exactly the ring it always was`() {
        for (p in listOf(0f, 0.25f, 0.5f, 1f)) {
            assertEquals(0.95f * p, mimirFocusRingStrokeAlpha(0.95f, 1f, p, 0f), 1e-6f)
            assertEquals(0.08f * p, mimirFocusRingStrokeAlpha(0.08f, 0.26f, p, 0f), 1e-6f)
        }
    }

    @Test
    fun `the first frame of a flare is the whole hot ring, even while progress is still at zero`() {
        assertEquals(0.26f, mimirFocusRingStrokeAlpha(0.08f, 0.26f, progress = 0f, flare = 1f), 1e-6f)
        assertEquals(0.48f, mimirFocusRingStrokeAlpha(0.18f, 0.48f, progress = 0f, flare = 1f), 1e-6f)
        // Cooling: halfway through, brighter than rest and never above the hot value.
        val half = mimirFocusRingStrokeAlpha(0.08f, 0.26f, progress = 1f, flare = 0.5f)
        assertTrue(half > 0.08f && half < 0.26f)
    }

    @Test
    fun `a pill can fix its gleam's reach instead of taking a quarter of its short side`() {
        val density = Density(1f)
        val size = Size(168f, 46f)
        val tile = with(density) { mimirFocusTileRingPaint(androidx.compose.ui.graphics.Color.Red, 23.dp, size) }
        val pill = with(density) { mimirFocusTileRingPaint(androidx.compose.ui.graphics.Color.Red, 23.dp, size, glintReach = 24.dp) }
        assertEquals(46f * 0.25f, tile.glintReach, 1e-4f)
        assertEquals(24f, pill.glintReach, 1e-4f)
        assertEquals(6f, pill.flareReach, 1e-4f)
    }
}
