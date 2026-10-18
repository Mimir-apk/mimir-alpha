package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the wait for identification gives up.
 *
 * `MimirArtworkCoordinator` holds the artwork offer until the games it is about to fetch art for have
 * actually been identified. That wait needs an escape, and the FIRST escape written for it was a
 * 15-minute wall clock -- which was wrong on the recorded measurements and would have reproduced the
 * exact complaint it was written to fix.
 *
 * The numbers, from the Thor on 2026-09-17: identification of 66 games ran 17:33:45 to 17:57:43. **Twenty
 * four minutes, healthy, converging the whole way.** A 15-minute stopwatch fires at ~17:48 with games
 * still unidentified, the offer goes out against half-corrected names, and the fetch races the hash again
 * -- the thing the wait exists to prevent, now with a comment claiming it is prevented.
 *
 * So the rule is about PROGRESS, not elapsed time: a wait that is still converging is doing its job no
 * matter how long it has taken, and a wait whose count has not moved in five minutes is stuck no matter
 * how recently it started. A stopwatch cannot tell those apart. [identityWaitShouldGiveUp] can.
 *
 * The absolute ceiling at the call site (45 minutes) is a backstop for the case this rule cannot catch --
 * still converging, impossibly slowly -- and is deliberately far above the 24-minute measurement.
 */
class IdentityWaitStallTest {

    private val budget = IdentityStallBudgetMillis

    @Test
    fun `a long wait that is still converging never gives up`() {
        // 24 minutes in, the count moved 30 seconds ago: exactly the measured run, which the 15-minute
        // stopwatch would have abandoned.
        assertFalse(
            identityWaitShouldGiveUp(
                outstanding = 12,
                bestOutstanding = 12,
                millisSinceProgress = 30_000L,
            ),
        )
    }

    @Test
    fun `a count that has not moved for the whole budget gives up`() {
        assertTrue(
            identityWaitShouldGiveUp(
                outstanding = 12,
                bestOutstanding = 12,
                millisSinceProgress = budget,
            ),
        )
    }

    @Test
    fun `one millisecond short of the budget is not yet a stall`() {
        assertFalse(
            identityWaitShouldGiveUp(
                outstanding = 12,
                bestOutstanding = 12,
                millisSinceProgress = budget - 1L,
            ),
        )
    }

    /**
     * The reason the caller tracks the LOWEST count rather than the previous one. A re-read that ticks
     * upward -- a row promoted to primary, a regroup -- is not progress and must not be allowed to look
     * like it, or a stall could hide behind one forever.
     */
    @Test
    fun `a count that went backwards is not progress`() {
        assertTrue(
            identityWaitShouldGiveUp(
                outstanding = 14,
                bestOutstanding = 12,
                millisSinceProgress = budget,
            ),
        )
    }

    @Test
    fun `a count below the best seen is progress, however long the wait has run`() {
        assertFalse(
            identityWaitShouldGiveUp(
                outstanding = 11,
                bestOutstanding = 12,
                millisSinceProgress = budget * 10L,
            ),
        )
    }

    /**
     * Nothing outstanding is done, not stalled. The caller returns on zero before asking, so this only
     * guards against a future caller that asks first -- which would otherwise report a finished wait as a
     * failed one and log a false alarm.
     */
    @Test
    fun `zero outstanding is never a stall`() {
        assertTrue(
            "test premise: a stale timestamp alone would otherwise qualify",
            budget > 0L,
        )
        assertFalse(
            identityWaitShouldGiveUp(
                outstanding = 0,
                bestOutstanding = 0,
                millisSinceProgress = budget * 10L,
            ),
        )
    }

    /**
     * Five minutes, and the size is load-bearing rather than arbitrary: the wait can legitimately sit
     * behind ONE slow unit of work with the count frozen -- a measured 4.43 GB Conker image reads for
     * minutes from SD -- so the budget must clear that, and it must stay far below the 24 minutes a
     * healthy run of the example library takes or it becomes the stopwatch again by another name.
     */
    @Test
    fun `the stall budget clears a single slow hash and stays well under a healthy run`() {
        assertTrue("must outlast one multi-minute image hash", budget >= 4L * 60L * 1000L)
        assertTrue("must stay far below the 24m a healthy library took", budget <= 10L * 60L * 1000L)
    }
}
