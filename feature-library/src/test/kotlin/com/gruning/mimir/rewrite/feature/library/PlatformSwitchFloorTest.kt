package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import org.junit.Test

/**
 *
 *
 * Measured on the Odin: a destination's rows and art land ~460 ms after the press, and the card cell is a
 * different shape per platform — `pc_games` 0.72 against `ps1` 1.16 — so a burst stacks half-settled
 * resizes. **This is the alternative to animating layout**, because an animated resize moves a card under the player's thumb. A switch that cannot outrun its own settle has nothing to
 * animate away.
 */
class PlatformSwitchFloorTest {

    @Test
    fun `the first switch is always accepted`() {
        assertTrue(
            "nothing has been accepted yet -- and the subtraction would overflow without this case",
            libraryPlatformSwitchAccepted(
                nowMillis = 0L,
                lastAcceptedMillis = Long.MIN_VALUE,
                floorMillis = LibraryPlatformSwitchFloorMs,
            ),
        )
    }

    @Test
    fun `a burst inside the floor is refused`() {
        assertFalse(
            "this reproduces presses arriving faster than the destination can settle, " +
                "each one stacking another half-finished cell resize on the last",
            libraryPlatformSwitchAccepted(
                nowMillis = 5_000L,
                lastAcceptedMillis = 5_000L - (LibraryPlatformSwitchFloorMs - 1L),
                floorMillis = LibraryPlatformSwitchFloorMs,
            ),
        )
    }

    @Test
    fun `deliberate paced switching is never throttled`() {
        assertTrue(
            "the floor exists to stop a burst outrunning the settle, not to slow down someone browsing",
            libraryPlatformSwitchAccepted(
                nowMillis = 5_000L,
                lastAcceptedMillis = 5_000L - LibraryPlatformSwitchFloorMs,
                floorMillis = LibraryPlatformSwitchFloorMs,
            ),
        )
    }

    /**
     * Its OWN constant, not a reference to `LibraryGamelistEmptyGraceMs`. They answer different questions —
     * *how long may a switch be unsettled* versus *how soon may the next be accepted* — and one number
     * serving two duties is how a value silently acquires a second meaning. Both were 400 ms when this was
     * written; the floor has since been reduced and the grace has not moved. **That divergence is the
     * point**, so this pins them as separate rather than pinning either to a literal.
     */
    @Test
    fun `the floor is a value of its own`() {
        // , then **one page glide**.
        // Pin the DERIVATION, not the number: the floor exists to stop a burst outrunning the page
        // transition, so if that transition is ever retimed the floor must follow it and this test must not
        // need editing to notice.
        assertEquals(MimirMotion.ModeTransitionMs.toLong(), LibraryPlatformSwitchFloorMs)
        assertNotEquals(
            "the floor and the loader grace must be free to diverge",
            LibraryGamelistEmptyGraceMs,
            LibraryPlatformSwitchFloorMs,
        )
    }
}
