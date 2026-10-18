package com.gruning.mimir.rewrite.core.model.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one channel every long-running family reports into, with kind and counts only.
 *
 * [JobProgressState] is a process singleton, so each test clears the kinds it uses first rather than
 * depending on JVM test order — the same discipline the guardian observability suites use.
 */
class JobProgressStateTest {

    @Before
    fun reset() {
        JobKind.entries.forEach(JobProgressState::clear)
    }

    @Test
    fun `nothing running is an empty list, not a row of zeroes`() {
        assertTrue("an idle channel states absence", JobProgressState.running.value.isEmpty())
    }

    @Test
    fun `a second report for the same kind replaces rather than duplicates`() {
        JobProgressState.report(JobKind.SCAN, seen = 100, total = 2010)
        JobProgressState.report(JobKind.SCAN, seen = 412, total = 2010)

        val running = JobProgressState.running.value
        assertEquals("one scan is one row, however often it ticks", 1, running.size)
        assertEquals(412, running.single().seen)
    }

    @Test
    fun `two families run side by side and keep their start order`() {
        // A scrape while a scan runs is normal, and D1-R18 needs a reader to be able to see both.
        JobProgressState.report(JobKind.SCAN, seen = 412, total = 2010)
        JobProgressState.report(JobKind.SCRAPE, seen = 88, total = 240)
        JobProgressState.report(JobKind.SCAN, seen = 900, total = 2010)

        val running = JobProgressState.running.value
        assertEquals(listOf(JobKind.SCAN, JobKind.SCRAPE), running.map { it.kind })
        assertEquals("a later tick must not reshuffle the rows under the player", 900, running.first().seen)
    }

    @Test
    fun `clearing one family leaves the others alone`() {
        JobProgressState.report(JobKind.SCAN, seen = 412, total = 2010)
        JobProgressState.report(JobKind.SCRAPE, seen = 88, total = 240)

        JobProgressState.clear(JobKind.SCAN)

        assertEquals(listOf(JobKind.SCRAPE), JobProgressState.running.value.map { it.kind })
    }

    @Test
    fun `a job with no denominator reports a count and no fraction`() {
        // The walk's state, and the scrape's first instant: work is happening, the total is unknown.
        JobProgressState.report(JobKind.SCAN, seen = 1204, total = null)

        val only = JobProgressState.running.value.single()
        assertEquals(1204, only.seen)
        assertNull("null is no honest bar, not a stalled one", only.fraction)
    }

    @Test
    fun `a zero total yields null rather than a division`() {
        JobProgressState.report(JobKind.ENRICH, seen = 0, total = 0)
        assertNull(JobProgressState.running.value.single().fraction)
    }

    @Test
    fun `a real denominator gives a real fraction, clamped`() {
        JobProgressState.report(JobKind.SCRAPE, seen = 120, total = 240)
        assertEquals(0.5f, checkNotNull(JobProgressState.running.value.single().fraction), 0.0001f)

        JobProgressState.report(JobKind.SCRAPE, seen = 500, total = 240)
        assertEquals(1f, checkNotNull(JobProgressState.running.value.single().fraction), 0.0001f)
    }
}
