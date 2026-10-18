package com.gruning.mimir.rewrite.core.companionnative

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who owns the Bottom's pad changes the epoch, so a route that keeps a press in progress across events (the Activity page's wake press) can tell the player took its release. */
class BottomPadInputEpochTest {

    @Test
    fun `the epoch advances when an owner registers and when it releases, and not for a stale release or an empty one`() {
        val keys: (KeyEvent) -> Boolean = { true }
        val motion: (MotionEvent) -> Boolean = { true }
        val start = BottomPadInput.ownerEpoch
        BottomPadInput.register(keys, motion)
        val registered = BottomPadInput.ownerEpoch
        assertTrue("an owner taking the pad is a change", registered != start)
        BottomPadInput.release({ false }, { false })
        assertEquals("a stale release unplugs nothing and changes nothing", registered, BottomPadInput.ownerEpoch)
        BottomPadInput.release(keys, motion)
        val released = BottomPadInput.ownerEpoch
        assertTrue("giving the pad back is a change", released != registered)
        BottomPadInput.release(keys, motion)
        assertEquals("releasing what is not registered changes nothing", released, BottomPadInput.ownerEpoch)
    }

    @Test
    fun `a hold boundary moves the hold epoch and never the owner epoch`() {
        val owner = BottomPadInput.ownerEpoch
        val hold = BottomPadInput.holdEpoch
        BottomPadInput.noteHoldBoundary()
        assertTrue(BottomPadInput.holdEpoch != hold)
        assertEquals("who owns the pad did not change, so a wake press in progress is not dropped", owner, BottomPadInput.ownerEpoch)
    }
}
