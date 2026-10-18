package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compact review 2026-09-27 (ONB-2): the favourites Tome showed three whole rows on the RG's 564.7 dp square and
 * left the rest of its box empty under the last row. Compact centres the rows; standard screens keep top.
 */
class OnboardingFavoritesCompactRowsTest {

    @Test
    fun `standard screens keep the grid under the search band`() {
        listOf(300f, 370.5f, 467f, 900f).forEach { box ->
            assertEquals(0f, onboardingFavoritesGridTopOffsetDp(box, onboardingFavoritesRows(box), compact = false), 0f)
        }
    }

    @Test
    fun `the RG square splits the leftover above and below three rows`() {
        // Grid boxes measured on the emulator at 564.7 dp (bar hidden) and derived for the 24 dp bar.
        mapOf(467f to 48.25f, 443f to 36.25f).forEach { (box, expected) ->
            val rows = onboardingFavoritesRows(box)
            assertEquals(3, rows)
            assertEquals(370.5f, onboardingFavoritesGridViewportHeightDp(rows), 0.01f)
            assertEquals(expected, onboardingFavoritesGridTopOffsetDp(box, rows, compact = true), 0.01f)
        }
    }

    @Test
    fun `the centred grid never leaves its box`() {
        (100..900 step 7).map(Int::toFloat).forEach { box ->
            val rows = onboardingFavoritesRows(box)
            val offset = onboardingFavoritesGridTopOffsetDp(box, rows, compact = true)
            assertTrue("offset at $box", offset >= 0f)
            val viewport = onboardingFavoritesGridViewportHeightDp(rows)
            // One row that does not fit keeps its top edge; everything else stays inside the box.
            if (viewport <= box) assertTrue("fits at $box", offset + viewport <= box + 0.01f)
        }
    }
}
