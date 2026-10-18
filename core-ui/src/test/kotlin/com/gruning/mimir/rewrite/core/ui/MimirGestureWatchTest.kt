package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gesture watch's classification: a lift is a release that is the finger's own; a cancel, a second finger, or no release at all is a gesture taken away. */
class MimirGestureWatchTest {

    @Test
    fun `a real release before the stop is a lift`() {
        val w = MimirGestureWatch()
        w.down(1)
        w.up()
        assertFalse(w.takenAway)
    }

    @Test
    fun `a stop with no release seen is a cancel, the fallback when the stop came first`() {
        val w = MimirGestureWatch()
        w.down(1)
        assertTrue(w.takenAway)
    }

    @Test
    fun `a consumed release is a cancel whether it reaches the watch before the stop or after it`() {
        // BEFORE: the cancel's synthetic release (consumed at the Initial pass) is queued ahead of onDragStopped. Order alone would have read it as a lift.
        val before = MimirGestureWatch()
        before.down(1)
        before.up(consumed = true)
        assertTrue("released first, and still a cancel", before.takenAway)
        // AFTER: the stop reads first (no release yet), then the same release follows.
        val after = MimirGestureWatch()
        after.down(1)
        assertTrue("the stop with no release yet", after.takenAway)
        after.up(consumed = true)
        assertTrue(after.takenAway)
    }

    @Test
    fun `a real release is a lift whatever its timestamp, because it reaches the Initial pass unconsumed`() {
        // A lift stamped in the same millisecond as the move before it carries no consumption; nothing about its time is read.
        val w = MimirGestureWatch()
        w.down(1)
        w.up(consumed = false)
        assertFalse(w.takenAway)
    }

    @Test
    fun `a second pointer is taken away and calls back once`() {
        var calls = 0
        val w = MimirGestureWatch { calls += 1 }
        w.down(1)
        w.down(2)
        w.down(2)
        w.up()
        assertTrue(w.takenAway)
        assertEquals(1, calls)
    }

    @Test
    fun `the next gesture starts clean`() {
        val w = MimirGestureWatch()
        w.down(1)
        w.down(2)
        w.up()
        w.down(1)
        w.up()
        assertFalse("a second finger of the last gesture does not follow it", w.takenAway)
    }
}
