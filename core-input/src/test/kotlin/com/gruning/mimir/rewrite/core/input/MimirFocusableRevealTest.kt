package com.gruning.mimir.rewrite.core.input

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ring migration (2026-09-28): a focused row is revealed with its ring's reach, not flush with the viewport edge. */
class MimirFocusableRevealTest {

    @Test
    fun `no outset, or no size yet, reveals the element itself as before`() {
        assertNull(mimirRevealRect(outsetPx = 0f, width = 200, height = 48))
        assertNull(mimirRevealRect(outsetPx = 14f, width = 0, height = 48))
        assertNull(mimirRevealRect(outsetPx = 14f, width = 200, height = 0))
    }

    @Test
    fun `an outset reveals the bounds grown by the ring's reach on every side`() {
        assertEquals(Rect(-14f, -14f, 214f, 62f), mimirRevealRect(outsetPx = 14f, width = 200, height = 48))
    }
}
