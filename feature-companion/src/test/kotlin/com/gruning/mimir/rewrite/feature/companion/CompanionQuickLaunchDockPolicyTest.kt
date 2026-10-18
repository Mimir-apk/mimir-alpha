package com.gruning.mimir.rewrite.feature.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionQuickLaunchDockPolicyTest {
    @Test
    fun `only a decisive vertical drag is a dock swipe`() {
        assertEquals(
            CompanionQuickLaunchDockGesture.SWIPE_UP,
            companionQuickLaunchDockGesture(deltaX = 0f, deltaY = -24f, touchSlop = 8f),
        )
        assertEquals(
            CompanionQuickLaunchDockGesture.SWIPE_DOWN,
            companionQuickLaunchDockGesture(deltaX = 0f, deltaY = 30f, touchSlop = 8f),
        )
        assertEquals(
            CompanionQuickLaunchDockGesture.TAP,
            companionQuickLaunchDockGesture(deltaX = 3f, deltaY = 4f, touchSlop = 8f),
        )
        assertEquals(
            CompanionQuickLaunchDockGesture.NONE,
            companionQuickLaunchDockGesture(deltaX = 20f, deltaY = 0f, touchSlop = 8f),
        )
    }

    @Test
    fun `dock never reserves scene padding in either state`() {
        assertEquals(0f, companionQuickLaunchDockBottomPaddingDp(expanded = true), 0f)
        assertEquals(0f, companionQuickLaunchDockBottomPaddingDp(expanded = false), 0f)
    }

    @Test
    fun `modal drag and host states remove the handle action`() {
        val available = companionQuickLaunchDockHandleEnabled(
            hideableDock = true,
            railVisible = true,
            interactionBlocked = false,
            railCovered = false,
            launchLocked = false,
            pickerOpen = false,
            pinActionsOpen = false,
            choiceOpen = false,
            instrumentsShown = false,
        )
        assertEquals(true, available)
        listOf(
            "interaction" to { companionQuickLaunchDockHandleEnabled(true, true, true, false, false, false, false, false, false) },
            "coverage" to { companionQuickLaunchDockHandleEnabled(true, true, false, true, false, false, false, false, false) },
            "picker" to { companionQuickLaunchDockHandleEnabled(true, true, false, false, false, true, false, false, false) },
            "choice" to { companionQuickLaunchDockHandleEnabled(true, true, false, false, false, false, false, true, false) },
            "instruments" to { companionQuickLaunchDockHandleEnabled(true, true, false, false, false, false, false, false, true) },
        ).forEach { (name, enabled) ->
            assertEquals("$name blocks the handle", false, enabled())
        }
    }
}
