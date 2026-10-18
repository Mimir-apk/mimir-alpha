package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.HomeTileMenuPhaseTransitionKey.ART_PICK
import com.gruning.mimir.rewrite.HomeTileMenuPhaseTransitionKey.CONFIRM_REMOVE
import com.gruning.mimir.rewrite.HomeTileMenuPhaseTransitionKey.CUSTOM_ENRICH_PICKER
import com.gruning.mimir.rewrite.HomeTileMenuPhaseTransitionKey.LOADING_ART
import com.gruning.mimir.rewrite.HomeTileMenuPhaseTransitionKey.MENU
import com.gruning.mimir.rewrite.HomeTileMenuPhaseTransitionKey.PROVIDER_UNAVAILABLE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review of 89d626d38: the tile menu's swap held every arriving page behind the 130 ms lead, so B from Customize media
 * showed a blank panel. A move back to a shallower page is a return (the swap tier's return clock); a move deeper keeps
 * the lead. The direction is decided from the host's page, once per page change.
 */
class HomeTileMenuSwapDirectionTest {

    @Test
    fun `B from a menu page back to the menu is a return, opening one is not`() {
        val direction = HomeTileMenuSwapDirection(MENU)
        assertFalse(direction.returningTo(MENU))
        assertFalse("opening Customize media moves deeper", direction.returningTo(CUSTOM_ENRICH_PICKER))
        assertTrue("B back to the menu is a return", direction.returningTo(MENU))
        assertFalse(direction.returningTo(CONFIRM_REMOVE))
        assertTrue(direction.returningTo(MENU))
    }

    @Test
    fun `Cancel from Customize media's pages back to Customize media is a return`() {
        val direction = HomeTileMenuSwapDirection(CUSTOM_ENRICH_PICKER)
        assertFalse(direction.returningTo(LOADING_ART))
        assertFalse("a loaded strip is a sibling, not a return", direction.returningTo(ART_PICK))
        assertTrue(direction.returningTo(CUSTOM_ENRICH_PICKER))
        assertFalse(direction.returningTo(PROVIDER_UNAVAILABLE))
        assertTrue(direction.returningTo(CUSTOM_ENRICH_PICKER))
    }

    @Test
    fun `the answer holds while the page does not change, across recompositions`() {
        val direction = HomeTileMenuSwapDirection(CUSTOM_ENRICH_PICKER)
        assertTrue(direction.returningTo(MENU))
        assertTrue("a recomposition on the same page keeps the return", direction.returningTo(MENU))
        assertFalse(direction.returningTo(CUSTOM_ENRICH_PICKER))
        assertFalse(direction.returningTo(CUSTOM_ENRICH_PICKER))
    }

    @Test
    fun `every page has a depth, and only the menu is at the top`() {
        HomeTileMenuPhaseTransitionKey.entries.forEach { key ->
            val depth = homeTileMenuPhaseDepth(key)
            assertTrue("$key at $depth", depth in 0..2)
            assertTrue("$key", (depth == 0) == (key == MENU))
        }
    }
}
