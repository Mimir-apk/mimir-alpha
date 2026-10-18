package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A busy transport is only a rejection if something was scheduled.
 *
 * Measured on Odin 3, 2026-08-08: a Stock launch logged `prepared=Skipped` and came back
 * `Rejected(reason=Tweaker transport is busy)`, while the identical no-op with a free transport
 * returned `NotScheduled`. Same situation, different answer depending purely on whether the transport
 * happened to be busy — a warning about a non-event, which produced the reported launch-time warning. No tuning was ever lost.
 */
class BusyTransportHandoffTest {

    @Test
    fun `nothing scheduled is not scheduled, whatever the transport is doing`() {
        assertEquals(
            "with no ticket, a busy transport costs nothing and must stay silent",
            TweakerV4LaunchHandoff.NotScheduled,
            busyTransportHandoff(hasPendingLaunch = false),
        )
    }

    @Test
    fun `a scheduled ticket lost to a busy transport is a real rejection`() {
        val handoff = busyTransportHandoff(hasPendingLaunch = true)
        assertEquals(
            "a ticket that existed and could not be handed off IS worth reporting",
            TweakerV4LaunchHandoff.Rejected("Tweaker transport is busy"),
            handoff,
        )
    }

    /**
     * The two cases must stay distinguishable. Collapsing them in either direction is a defect:
     * silence for a lost ticket hides real untuned play, and a warning for a no-op is the bug above.
     */
    @Test
    fun `the two cases never produce the same handoff`() {
        assertEquals(
            2,
            setOf(
                busyTransportHandoff(hasPendingLaunch = false),
                busyTransportHandoff(hasPendingLaunch = true),
            ).size,
        )
    }
}
