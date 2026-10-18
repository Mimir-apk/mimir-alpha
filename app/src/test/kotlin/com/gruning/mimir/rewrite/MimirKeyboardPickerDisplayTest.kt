package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/**
 * QA-07 .
 *
 * The keyboard row used a 450 ms "is this window still focused?" check to decide whether
 * `showInputMethodPicker()` had produced anything, and opened Android's keyboard settings when it
 * decided not. On a two-screen Thor that check is structurally blind: the picker takes focus on
 * the SECOND display, this window keeps focus, and the check therefore reports "never appeared" about a
 * window that is on screen and focused. The capture shows the consequence three times in twelve seconds —
 * picker focused at 12:22:32.449, Mimir declaring it absent at 12:22:32.793, Settings launched at
 * 12:22:32.807, picker's input channel disposed at 12:22:33.016.
 *
 * So the predicate that gates the fallback is the whole fix, and it is what is pinned here. A second
 * display must disable the timed check outright: closing a picker the player is looking at is far worse
 * than leaving a silent button, and no public API can see a window on another display.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class MimirKeyboardPickerDisplayTest {

    @Test
    fun oneDisplayLeavesTheAppearanceCheckUsable() {
        assertFalse(
            "with only the default display the picker can take focus here or nowhere, so the timed " +
                "check is sound and must stay enabled",
            mimirKeyboardPickerMayOpenOnAnotherDisplay(RuntimeEnvironment.getApplication()),
        )
    }

    @Test
    fun aSecondDisplayDisablesTheAppearanceCheck() {
        val bottom = ShadowDisplayManager.addDisplay("+land", 2)
        try {
            assertTrue(
                "the Thor's bottom screen is where the picker goes (isImeShowOnSecondDisplay); this " +
                    "window cannot observe it, so the fallback must not run",
                mimirKeyboardPickerMayOpenOnAnotherDisplay(RuntimeEnvironment.getApplication()),
            )
        } finally {
            ShadowDisplayManager.removeDisplay(bottom)
        }
    }

    @Test
    fun removingTheSecondDisplayRestoresTheCheck() {
        // Docking is not permanent. The predicate is read live at each press rather than remembered, so a
        // player who undocks gets the single-display behaviour back without restarting Mimir.
        val bottom = ShadowDisplayManager.addDisplay("+land", 2)
        assertTrue(mimirKeyboardPickerMayOpenOnAnotherDisplay(RuntimeEnvironment.getApplication()))
        ShadowDisplayManager.removeDisplay(bottom)
        assertFalse(mimirKeyboardPickerMayOpenOnAnotherDisplay(RuntimeEnvironment.getApplication()))
    }
}
