package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `awaitSettled` polls while work is active, and a WorkSpec that returned retry sits in
 * ENQUEUED-with-backoff — not `isFinished` — so that branch can hold for the caller's entire budget. At a
 * flat 120ms that was ~750 WorkManager binder round-trips per first-run on low-end handhelds.
 */
class SettlePollBackoffTest {

    @Test
    fun `backoff doubles from 120ms to a 960ms ceiling`() {
        assertEquals(120L, settlePollDelayMillis(0))
        assertEquals(240L, settlePollDelayMillis(1))
        assertEquals(480L, settlePollDelayMillis(2))
        assertEquals(960L, settlePollDelayMillis(3))
        assertEquals(960L, settlePollDelayMillis(4))
        assertEquals(960L, settlePollDelayMillis(50))
    }

    @Test
    fun `negative or absurd inputs stay clamped, never zero or negative`() {
        assertEquals(120L, settlePollDelayMillis(-1))
        assertEquals(120L, settlePollDelayMillis(Int.MIN_VALUE))
        assertEquals(960L, settlePollDelayMillis(Int.MAX_VALUE))
        // A zero/negative delay would spin the poll loop into a busy-wait.
        for (i in listOf(-5, 0, 1, 7, 1000)) {
            assertTrue("delay must stay positive at $i", settlePollDelayMillis(i) > 0L)
        }
    }

    @Test
    fun `the first second stays responsive`() {
        val firstFour = (0..3).sumOf { settlePollDelayMillis(it) }
        assertTrue(
            "a sweep that finishes quickly must still be observed quickly (got ${firstFour}ms)",
            firstFour <= 2_000L,
        )
    }

    @Test
    fun `a full budget of polling costs far fewer queries than the flat rate`() {
        var elapsed = 0L
        var polls = 0
        // The longest wait awaitSettled now sits in: the favourites step's stall budget.
        while (elapsed < IdentityStallBudgetMillis) {
            elapsed += settlePollDelayMillis(polls)
            polls++
        }
        val flatRatePolls = IdentityStallBudgetMillis / 120L
        assertTrue(
            "expected a large reduction vs the flat 120ms rate ($polls vs $flatRatePolls)",
            polls < flatRatePolls / 4,
        )
    }
}
