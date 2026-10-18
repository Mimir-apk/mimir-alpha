package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6-P2-08 re-review finding A/C: a close request must be handled exactly once, no matter how many
 * collectors read [CompanionBottomFolderState.closeRequest] — a `StateFlow` replays its last value to
 * every new subscriber (a remounted `CompanionFolderPage`, or a brand-new `MimirCompanionPresentation`
 * after a display remount/reconnect), so without [CompanionBottomFolderState.claim] the SAME swipe would
 * be resolved again by whichever composable next reads it, closing a layer that swipe never touched.
 */
class CompanionBottomFolderStateTest {
    // `CompanionBottomFolderState` is a process-wide singleton and its handled-request state is not
    // reset between tests in the same JVM run — each test below uses its own disjoint id so the two
    // are independent regardless of run order.
    @Test
    fun `a claimed request is not delivered to a second collector`() {
        val id = 101L

        // The first collector to see this request (the Presentation instance that was actually open
        // when the swipe landed) claims it.
        assertTrue("first claim of a fresh id must succeed", CompanionBottomFolderState.claim(id))

        // A second collector for the SAME id — a remounted CompanionFolderPage seeing the value it was
        // handed as if it were new, or a brand-new Presentation replaying the StateFlow's last value —
        // must not be able to claim it again.
        assertFalse(
            "a second collector claiming the SAME id must be refused",
            CompanionBottomFolderState.claim(id),
        )
        assertFalse(
            "claiming the same id a third time must still be refused",
            CompanionBottomFolderState.claim(id),
        )
    }

    @Test
    fun `a genuinely new request after a claimed one still claims`() {
        val first = 201L
        val second = 202L

        assertTrue(CompanionBottomFolderState.claim(first))
        // A real second swipe mints a distinct, later id (CompanionBottomFolderState.requestClose is
        // monotonic) — that one must still be claimable even though `first` was already resolved.
        assertTrue(
            "a new id after an already-claimed one must still succeed",
            CompanionBottomFolderState.claim(second),
        )
        assertFalse(CompanionBottomFolderState.claim(second))
    }
}
