package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch cover reads UsageStats every beat over a window that reaches back past the last poll, so the same resume is
 * read several times. The ledger is what makes that safe: each resume is handed over once, and nothing from before the
 * subscription began is handed over at all.
 */
class ActivityResumeLedgerTest {
    private val pkg = "com.armsx2"
    private val splash = "com.armsx2.BootSplashActivity"
    private val game = "com.armsx2.Main"

    @Test
    fun `a resume is handed over the first time it is read and not again`() {
        val ledger = ActivityResumeLedger(sinceEpochMs = 1_000L)

        assertTrue(ledger.firstSight(1_100L, pkg, splash))
        assertFalse("the next beat re-reads the same event", ledger.firstSight(1_100L, pkg, splash))
        assertFalse(ledger.firstSight(1_100L, pkg, splash))
    }

    @Test
    fun `two activities of one package are two resumes`() {
        val ledger = ActivityResumeLedger(sinceEpochMs = 1_000L)

        assertTrue(ledger.firstSight(1_100L, pkg, splash))
        assertTrue(ledger.firstSight(4_700L, pkg, game))
        assertTrue("the same activity resuming again later is a new resume", ledger.firstSight(9_000L, pkg, game))
    }

    @Test
    fun `nothing from before the subscription began is handed over`() {
        val ledger = ActivityResumeLedger(sinceEpochMs = 5_000L)

        assertFalse("Mimir's own resume, seconds before the dispatch", ledger.firstSight(4_999L, "com.gruning.mimir", "Main"))
        assertTrue("a resume at the very start counts", ledger.firstSight(5_000L, pkg, splash))
    }

    @Test
    fun `an event that UsageStats publishes late is still caught by the next beat`() {
        // The poll at t=1_200 ran past an event stamped 1_150 before UsageStats had published it. The next poll reads it,
        // because the window reaches back, and the ledger has never seen it, so it is handed over.
        val ledger = ActivityResumeLedger(sinceEpochMs = 1_000L)

        assertTrue(ledger.firstSight(1_100L, pkg, splash))
        // ... poll at 1_200 sees only the splash ...
        assertFalse(ledger.firstSight(1_100L, pkg, splash))
        // ... poll at 1_300 sees the splash again and the late event.
        assertFalse(ledger.firstSight(1_100L, pkg, splash))
        assertTrue(ledger.firstSight(1_150L, pkg, game))
    }

    @Test
    fun `a long session forgets its oldest events and still never repeats a recent one`() {
        val ledger = ActivityResumeLedger(sinceEpochMs = 0L)

        for (i in 1..200) assertTrue(ledger.firstSight(i.toLong(), pkg, "com.armsx2.A$i"))

        assertFalse("a recent event is still remembered", ledger.firstSight(200L, pkg, "com.armsx2.A200"))
    }
}
