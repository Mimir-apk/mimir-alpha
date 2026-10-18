package com.gruning.mimir.rewrite.core.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** OR-8 : L3 is a real control, bound to the controller-hint legend by default. */
class ControllerL3HintsTest {

    @Test
    fun `L3 is a physical control and shows hints by default`() {
        assertEquals(PhysicalControl.L3, physicalControlForKeyCode(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals(ControllerAction.SHOW_HINTS, defaultControllerKeymap()[PhysicalControl.L3])
    }
}
