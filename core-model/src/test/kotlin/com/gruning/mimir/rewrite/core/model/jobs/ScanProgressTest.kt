package com.gruning.mimir.rewrite.core.model.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ScanProgress.fraction] is where policy D1-R21 meets the honesty contract the job model
 * carries: **null is "no honest bar to draw", never "0%" and never "stalled"**.
 *
 * Every case below is one way a scan could have been made to look like it knew something it did not.
 */
class ScanProgressTest {

    @Test
    fun `no total means no fraction`() {
        // The walk's normal state: files are being found and nothing knows how many there are.
        val walking = ScanProgress(phase = ScanPhase.WALKING, seen = 1204, total = null)
        assertNull("a count without a denominator is not a fraction", walking.fraction)
    }

    @Test
    fun `a zero total yields null rather than a division`() {
        // An empty source is a real, fully traversed source -- but "no files" is not "0% done", and
        // it is certainly not a divide by zero.
        val empty = ScanProgress(phase = ScanPhase.RECOGNISING, seen = 0, total = 0)
        assertNull(empty.fraction)
    }

    @Test
    fun `a known total gives a real fraction`() {
        val half = ScanProgress(phase = ScanPhase.RECOGNISING, seen = 1005, total = 2010)
        assertEquals(0.5f, checkNotNull(half.fraction), 0.0001f)

        val done = ScanProgress(phase = ScanPhase.RECOGNISING, seen = 2010, total = 2010)
        assertEquals(1f, checkNotNull(done.fraction), 0.0001f)
    }

    @Test
    fun `a count past its total is clamped rather than reported above one`() {
        // Defensive: a producer that double-counted should show a full bar, never a 140% one.
        val over = ScanProgress(phase = ScanPhase.RECOGNISING, seen = 2814, total = 2010)
        assertEquals(1f, checkNotNull(over.fraction), 0.0001f)
    }

    @Test
    fun `the bulk phases carry a count and no bar`() {
        // GROUPING and PERSISTING have nothing to count against. They say which phase, and stop.
        assertNull(ScanProgress(ScanPhase.GROUPING, seen = 2010, total = null).fraction)
        assertNull(ScanProgress(ScanPhase.PERSISTING, seen = 2010, total = null).fraction)
    }

    @Test
    fun `clear leaves nothing behind`() {
        ScanProgressState.report(ScanPhase.RECOGNISING, seen = 412, total = 2010)
        assertEquals(412, checkNotNull(ScanProgressState.progress.value).seen)

        ScanProgressState.clear()
        assertNull("an idle surface states absence; it does not hold the last bar", ScanProgressState.progress.value)
    }
}
