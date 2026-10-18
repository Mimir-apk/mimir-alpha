package com.gruning.mimir.rewrite.feature.companion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  The "+" lives in the cell straight after
 * the last tile; the page count never reserved that cell, so at 9, 18, 27... tiles it fell on a page that did not exist. The count is now tiles plus the "+",
 * and it is the same count for a drag's whole life (the drag state machine's own test pins a lifted tile turning to, and dropping on, the "+" page).
 */
class CompanionCreateTomePageTest {

    private fun surfaceSource() =
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt").readText().replace("\r\n", "\n")

    @Test
    fun `the plus always has a page, at the boundaries that used to lose it`() {
        // tiles -> (pages, the page the plus is on)
        val expected = listOf(
            0 to (1 to 0), 1 to (1 to 0), 8 to (1 to 0),
            9 to (2 to 1), 10 to (2 to 1), 17 to (2 to 1),
            18 to (3 to 2), 19 to (3 to 2), 26 to (3 to 2), 27 to (4 to 3),
        )
        for ((tiles, want) in expected) {
            val pages = companionLatticePageCount(tiles)
            val plusPage = companionCreateTomePage(tiles)
            assertEquals("pages at $tiles tiles", want.first, pages)
            assertEquals("the plus's page at $tiles tiles", want.second, plusPage)
            assertTrue("the plus's page exists at $tiles tiles", plusPage in 0 until pages)
        }
    }

    @Test
    fun `removing a Tome at ten tiles leaves nine and the plus on the second page`() {
        assertEquals(2, companionLatticePageCount(10))
        assertEquals(2, companionLatticePageCount(9))
        assertEquals("the plus is on page 2 (index 1), alone, not gone", 1, companionCreateTomePage(9))
    }

    @Test
    fun `the surface counts its pages with the shared function and its plus cell is after the last tile`() {
        val source = surfaceSource()
        assertTrue(source.contains("val pageCount = companionLatticePageCount(order.size)"))
        assertTrue(
            "the drag geometry reports the SAME count idle and lifted: the pager never changes count under a drag",
            source.contains("                pageCount = pageCount,\n                settledPage = pagerState.currentPage.coerceIn(0, pageCount - 1),"),
        )
        assertTrue("the plus is in the cell straight after the last tile and hides while dragging", source.contains("if (globalSlot == chunkOrder.size) {"))
        assertTrue(source.contains("visible = draggingId == null,"))
        assertFalse("the old tiles-only page count is gone", source.contains("((order.size + SlotsPerPage - 1) / SlotsPerPage).coerceAtLeast(1)\n        val pagerState"))
    }
}
