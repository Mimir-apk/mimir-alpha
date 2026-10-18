package com.gruning.mimir.rewrite

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenericMotionInteractionSourceTest {
    @Test
    fun `pointer scroll and hover wake before router consumption`() {
        assertEquals(
            InteractionSource.WHEEL,
            genericMotionInteractionSource(InputDevice.SOURCE_MOUSE, MotionEvent.ACTION_SCROLL),
        )
        assertEquals(
            InteractionSource.POINTER,
            genericMotionInteractionSource(InputDevice.SOURCE_MOUSE, MotionEvent.ACTION_HOVER_MOVE),
        )
    }

    @Test
    fun `controller motion is not mislabeled as pointer activity`() {
        assertNull(genericMotionInteractionSource(InputDevice.SOURCE_JOYSTICK, MotionEvent.ACTION_MOVE))
    }
}
