package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * QA 2026-09-11 (D6, S24): the Swap refusal toast was made from MainActivity, so it drew on display 0 over the game
 * while the player was looking at the ᛗ menu on display 4. Feedback for a Bottom command is made from a display
 * context of the Bottom; this is the choice of display, without the Android context around it.
 */
class CompanionFeedbackDisplayTest {

    @Test
    fun `the display the menu was pressed on wins`() {
        assertEquals(
            4,
            companionFeedbackDisplayId(menuDisplayId = 4, selectedSecondaryDisplayId = 5, activityDisplayId = 0),
        )
    }

    @Test
    fun `with no menu mounted the selected secondary display is used`() {
        assertEquals(
            4,
            companionFeedbackDisplayId(menuDisplayId = null, selectedSecondaryDisplayId = 4, activityDisplayId = 0),
        )
    }

    @Test
    fun `with no secondary display the Activity's own context is used`() {
        assertNull(
            companionFeedbackDisplayId(menuDisplayId = null, selectedSecondaryDisplayId = null, activityDisplayId = 0),
        )
    }

    @Test
    fun `an Activity already on the Bottom needs no display context`() {
        assertNull(
            companionFeedbackDisplayId(menuDisplayId = 4, selectedSecondaryDisplayId = 4, activityDisplayId = 4),
        )
    }
}
