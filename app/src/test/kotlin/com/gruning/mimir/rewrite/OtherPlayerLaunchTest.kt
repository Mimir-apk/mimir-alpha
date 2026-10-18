package com.gruning.mimir.rewrite

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the music panel's art tap opens and where the window grows from: the pure choices behind it. The launch itself (a pending
 * intent sent with an options bundle, or a startActivity) is Android and is walked on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OtherPlayerLaunchTest {
    @Test
    fun `the session's own activity wins, the launch intent is the fallback, and neither is nothing`() {
        assertEquals(OtherPlayerLaunchKind.SESSION_ACTIVITY, otherPlayerLaunchKind(hasSessionActivity = true, hasLaunchIntent = true))
        assertEquals(OtherPlayerLaunchKind.SESSION_ACTIVITY, otherPlayerLaunchKind(hasSessionActivity = true, hasLaunchIntent = false))
        assertEquals(OtherPlayerLaunchKind.LAUNCH_INTENT, otherPlayerLaunchKind(hasSessionActivity = false, hasLaunchIntent = true))
        assertEquals(OtherPlayerLaunchKind.NONE, otherPlayerLaunchKind(hasSessionActivity = false, hasLaunchIntent = false))
    }

    @Test
    fun `the window grows out of the art's own rectangle, in the view's pixels`() {
        assertEquals(ScaleUpOrigin(120, 80, 288, 288), scaleUpOrigin(Rect(120, 80, 408, 368), viewWidth = 960, viewHeight = 720))
    }

    @Test
    fun `an art that is partly off the view or has no size is held to a valid origin, and no bounds grows from the centre`() {
        assertEquals(ScaleUpOrigin(0, 0, 100, 100), scaleUpOrigin(Rect(-20, -10, 80, 90), viewWidth = 960, viewHeight = 720))
        assertEquals(ScaleUpOrigin(480, 360, 1, 1), scaleUpOrigin(null, viewWidth = 960, viewHeight = 720))
        assertEquals(ScaleUpOrigin(480, 360, 1, 1), scaleUpOrigin(Rect(50, 50, 50, 50), viewWidth = 960, viewHeight = 720))
        assertEquals(ScaleUpOrigin(0, 0, 1, 1), scaleUpOrigin(null, viewWidth = 0, viewHeight = 0))
    }
}
