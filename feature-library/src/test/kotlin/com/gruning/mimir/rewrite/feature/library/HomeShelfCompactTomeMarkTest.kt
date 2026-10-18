package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * compact Home's X · TOME mark, hidden since the earlier update widened the
 * dock, returns at the left end of the hint lane.
 */
class HomeShelfCompactTomeMarkTest {

    @Test
    fun `the RG seats the mark at the hint lane's left end`() {
        mapOf(540.7f to 412.7f, 564.7f to 436.7f).forEach { (height, top) ->
            val mark = homeShelfCompactTomeMarkBounds(solveHomeShelfGeometry(564.7f, height, compact = true))
            assertEquals(17.506f, mark.left, 0.01f)
            assertEquals(121.506f, mark.right, 0.01f)
            assertEquals("top at $height", top, mark.top, 0.05f)
            assertEquals(24f, mark.height, 0f)
        }
    }

    @Test
    fun `it clears the toast, the other hints, the focused tile and the dock`() {
        listOf(540.7f, 564.7f).forEach { height ->
            val g = solveHomeShelfGeometry(564.7f, height, compact = true)
            val mark = homeShelfCompactTomeMarkBounds(g)
            val toastLeft = (g.viewport.width - 196f) / 2f
            val toast = HomeShelfBounds(toastLeft, g.hintToastBand.top, toastLeft + 196f, g.hintToastBand.bottom)
            assertFalse("toast at $height", mark.intersects(toast))
            assertFalse("navigation and tile hints at $height", mark.intersects(g.navigationHintBand))
            val seat0 = g.slotBounds(0)
            val ringReach = HomeShelfBounds(seat0.left - 14f, seat0.top - 14f, seat0.right + 14f, seat0.bottom + 14f)
            assertFalse("focused seat 0's ring at $height", mark.intersects(ringReach))
            assertTrue("above the dock at $height", mark.bottom <= g.quickRailBand.top)
        }
    }
}
