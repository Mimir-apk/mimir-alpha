package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SWAP up: which Bottom return host holds its finish for the Main transfer host, and for how long. */
class CompanionSwapUpGuardHoldTest {

    @Test
    fun `only the guard of the SWAP up reclaim holds for the transfer host`() {
        assertTrue(companionSwapUpGuardHoldsForHost(restoreGuard = true, restoreReason = "overlay_swap_to_primary"))
        assertFalse(companionSwapUpGuardHoldsForHost(restoreGuard = true, restoreReason = "overlay_swap_exchange"))
        assertFalse(companionSwapUpGuardHoldsForHost(restoreGuard = true, restoreReason = "overlay_edge_back"))
        assertFalse(companionSwapUpGuardHoldsForHost(restoreGuard = true, restoreReason = "secondary_menu_return_to_shelf"))
        assertFalse(companionSwapUpGuardHoldsForHost(restoreGuard = true, restoreReason = null))
    }

    @Test
    fun `a host launch, not a restore guard, never holds`() {
        assertFalse(companionSwapUpGuardHoldsForHost(restoreGuard = false, restoreReason = "overlay_swap_to_primary"))
    }

    @Test
    fun `the hold names the SWAP up reason and is bounded`() {
        assertEquals("overlay_swap_to_primary", CompanionSwapUpReclaimReason)
        assertEquals(1_000L, CompanionSwapUpGuardHoldMs)
    }
}
