package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nova 2026-10-01 21:49:34-21:49:51: with Utility open, one `rearmwatch observe` a second. The panel's beat is a telemetry
 * read; the full projection behind it is due on its own, longer clock.
 */
class TweakerPanelTickTest {

    private fun due(
        present: Boolean = true,
        unknown: Boolean = false,
        pairMayBind: Boolean = true,
        since: Long = 1_000L,
    ) = tweakerPanelProjectionDue(present, unknown, pairMayBind, since, intervalMs = 10_000L)

    @Test
    fun `an answered, fresh projection is not re-read every second`() {
        assertFalse(due(since = 0L))
        assertFalse(due(since = 1_000L))
        assertFalse(due(since = 9_999L))
    }

    @Test
    fun `the full projection is due when the interval passes, when nothing was read, or when the last ask went unanswered`() {
        assertTrue(due(since = 10_000L))
        assertTrue("never read", due(present = false))
        assertTrue("last ask unanswered", due(unknown = true))
        assertTrue("pair cannot bind from cached facts: the full refresh classifies it", due(pairMayBind = false))
        assertTrue("a clock that went backwards", due(since = -1L))
    }
}
