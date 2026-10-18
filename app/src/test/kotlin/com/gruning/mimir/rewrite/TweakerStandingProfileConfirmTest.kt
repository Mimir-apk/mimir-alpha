package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fix for a loss nobody observed.
 *
 * Device evidence (Odin, 2026-08-13, manual): walking Settings -> Library -> Add games and
 * hopping through the system folder picker ended the session from outside the app
 * (`SERVICE restore reason=STARTUP_RECOVERY_RESTORE ... restored=true`). The profile fell to Stock and
 * stayed there -- 5.5 minutes in one run, over 10 in another -- with **zero** bridge observations in
 * the whole capture, until the app was restarted by hand.
 *
 * The watch was not broken; it was never started. It stands down while the choice is honoured, and the
 * only things that start it run when something refreshes the projection -- so it was started from the
 * same observation path it existed to back up.
 */
class TweakerStandingProfileConfirmTest {

    @Test
    fun `an honoured choice keeps being confirmed while Mimir is resumed`() {
        assertTrue(
            "this is the whole defect: an externally-caused loss after this point is invisible " +
                "unless something keeps looking",
            standingWatchConfirms(StandingRearmDecision.HONOURED, mimirResumed = true),
        )
    }

    @Test
    fun `nothing is confirmed while Mimir is backgrounded`() {
        assertFalse(
            "a heartbeat costs a bind, and a player who is not looking at Mimir is not being misled",
            standingWatchConfirms(StandingRearmDecision.HONOURED, mimirResumed = false),
        )
    }

    @Test
    fun `decisions with nothing to protect never start a heartbeat`() {
        listOf(
            StandingRearmDecision.NOTHING_TO_ASSERT,
            StandingRearmDecision.NOT_OFFERED,
        ).forEach { decision ->
            assertFalse(
                "$decision has no standing intent to confirm; polling for it would be pure cost",
                standingWatchConfirms(decision, mimirResumed = true),
            )
        }
    }

    @Test
    fun `an active incident keeps the fast recovery cadence, not the slow confirm one`() {
        assertEquals(
            "a lost profile must be chased at the recovery interval",
            15_000L,
            standingWatchDelayMs(StandingRearmDecision.REARM),
        )
        assertEquals(
            "unreadable truth is still an open incident",
            15_000L,
            standingWatchDelayMs(StandingRearmDecision.TRUTH_UNKNOWN),
        )
    }

    @Test
    fun `the healthy case backs off to the slower confirm cadence`() {
        assertEquals(
            "confirming a healthy profile must not cost the same as recovering a lost one",
            60_000L,
            standingWatchDelayMs(StandingRearmDecision.HONOURED),
        )
        assertTrue(
            "the confirm cadence bounds how long an externally-caused loss can go unnoticed, so it " +
                "cannot be slower than the minutes this defect was measured to last",
            standingWatchDelayMs(StandingRearmDecision.HONOURED) <= 60_000L,
        )
    }

    @Test
    fun `confirming is slower than recovering, never the other way round`() {
        assertTrue(
            "an inverted pair would poll hardest in the healthy case",
            standingWatchDelayMs(StandingRearmDecision.HONOURED) >
                standingWatchDelayMs(StandingRearmDecision.REARM),
        )
    }
}
