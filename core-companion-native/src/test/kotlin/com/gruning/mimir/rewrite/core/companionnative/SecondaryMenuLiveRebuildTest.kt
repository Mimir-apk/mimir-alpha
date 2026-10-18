package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * after an accepted BOTTOM_TO_MAIN swap the ᛗ menu came back on the Bottom. The
 * Return-availability flip that follows the swap arrived while the collapse animation still had
 * `menuOpen == true`, and the live rebuild re-opened the menu. A collapse in flight must veto the rebuild.
 */
class SecondaryMenuLiveRebuildTest {
    @Test
    fun `an open secondary menu with no collapse in flight may be rebuilt live`() {
        assertTrue(secondaryMenuLiveRebuildAllowed(menuOpen = true, secondaryRole = true, collapseInFlight = false))
    }

    @Test
    fun `a collapsing menu is never rebuilt open`() {
        assertFalse(secondaryMenuLiveRebuildAllowed(menuOpen = true, secondaryRole = true, collapseInFlight = true))
    }

    @Test
    fun `a closed menu or a primary role is left alone`() {
        assertFalse(secondaryMenuLiveRebuildAllowed(menuOpen = false, secondaryRole = true, collapseInFlight = false))
        assertFalse(secondaryMenuLiveRebuildAllowed(menuOpen = true, secondaryRole = false, collapseInFlight = false))
    }
}
