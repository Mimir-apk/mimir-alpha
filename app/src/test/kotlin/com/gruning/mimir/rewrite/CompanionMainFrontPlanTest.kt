package com.gruning.mimir.rewrite

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

/** SWAP back: which Main front step the eviction takes, and the flags its start carries. */
class CompanionMainFrontPlanTest {
    private val main = "com.gruning.mimir.rewrite.MainActivity"
    private val host = "com.gruning.mimir.rewrite.PrimaryTransferActivity"
    private val known = setOf(main, host)

    private fun plan(exchangeInFlight: Boolean = false, mainStopped: Boolean = true, top: String?) =
        companionMainFrontPlan(
            exchangeInFlight = exchangeInFlight,
            mainStopped = mainStopped,
            mainTaskTopActivityClass = top,
            knownTopActivityClasses = known,
        )

    @Test
    fun `a covered Main with the transfer host on top reorders the host, which keeps it in the task`() {
        assertEquals(CompanionMainFrontPlan.Reorder(host), plan(top = host))
    }

    @Test
    fun `a covered Main with MainActivity on top reorders MainActivity`() {
        assertEquals(CompanionMainFrontPlan.Reorder(main), plan(top = main))
    }

    @Test
    fun `an exchange in flight never brings Mimir forward`() {
        assertEquals(CompanionMainFrontPlan.Skip("exchange_in_flight"), plan(exchangeInFlight = true, top = host))
        assertEquals(CompanionMainFrontPlan.Skip("exchange_in_flight"), plan(exchangeInFlight = true, top = main))
    }

    @Test
    fun `a Main that has already resumed needs no front step`() {
        assertEquals(CompanionMainFrontPlan.Skip("main_already_resumed"), plan(mainStopped = false, top = main))
    }

    @Test
    fun `a missing Main task or an unknown top activity skips`() {
        assertEquals(CompanionMainFrontPlan.Skip("main_task_missing"), plan(top = null))
        assertEquals(CompanionMainFrontPlan.Skip("main_task_missing"), plan(top = " "))
        assertEquals(
            CompanionMainFrontPlan.Skip("main_task_top_unknown"),
            plan(top = "com.gruning.mimir.rewrite.SomeOtherActivity"),
        )
    }

    @Test
    fun `the front start carries NEW_TASK, REORDER_TO_FRONT, NO_ANIMATION and NO_USER_ACTION`() {
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION,
            companionMainFrontLaunchFlags(),
        )
        // The value the Thor's `START … flg=` line prints for this start.
        assertEquals(0x10070000, companionMainFrontLaunchFlags())
    }
}
