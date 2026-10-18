package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compact review 2026-09-27 (SEARCH-1): the status HUD covered Library Search's "B  BACK" on the RG; update's sweep (10-09): and on Main. */
class HomeStatusPillYieldsToSearchTest {

    @Test
    fun `the HUD yields to Search at every size`() {
        assertFalse(homeStatusPillVisible(searchOpen = true))
        assertTrue(homeStatusPillVisible(searchOpen = false))
    }
}
