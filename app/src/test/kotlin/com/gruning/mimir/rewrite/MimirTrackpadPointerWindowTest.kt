package com.gruning.mimir.rewrite

import android.view.WindowManager
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerGeometry
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerShape
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D34b fix round (item 5): the pointer window must never take touches or focus, must not block the click it is
 * about to inject when it is the untrusted application-overlay fallback (Android blocks touches under an untrusted
 * overlay drawn above the maximum obscuring opacity, 0.8), and, for the arrow, must not cover the click point at all.
 *
 * The pointer's look is the player's choice , so the same rules are held for every size of both shapes. The dot's
 * window is centered on the click point and so does cover it: that is safe because the window takes no touches, the
 * accessibility overlay is a trusted overlay that Android never counts as an occluder, and the fallback sits at
 * [FallbackPointerAlpha], under the 0.8 maximum (Android 14 `InputDispatcher::canBeObscuredBy` and
 * `computeTouchOcclusionInfoLocked`: the rule reads the window's frame and layer alpha, not its pixels).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirTrackpadPointerWindowTest {

    private val untouchableAndUnfocusable =
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    private val types = listOf(
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    )

    @Test
    fun theApplicationOverlayFallbackStaysWithinTheMaximumObscuringOpacity() {
        val params = mimirTrackpadPointerParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 40)
        assertTrue("alpha ${params.alpha} must be at or below 0.8", params.alpha <= 0.8f)
        assertTrue("and still visible", params.alpha > 0.3f)
        assertEquals(0.75f, FallbackPointerAlpha, 0f)
    }

    @Test
    fun theAccessibilityOverlayKeepsFullOpacity() {
        assertEquals(1f, mimirTrackpadPointerParams(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 40).alpha, 0f)
    }

    @Test
    fun bothPointerWindowsAreNeverTouchableOrFocusable() {
        for (type in types) {
            val params = mimirTrackpadPointerParams(type, 40)
            assertEquals(type, params.type)
            assertEquals(untouchableAndUnfocusable, params.flags and untouchableAndUnfocusable)
        }
    }

    @Test
    fun everyShapeAndSizeKeepsTheWindowUntouchableAndTheFallbackUnderTheMaximumOpacity() {
        for (shape in TrackpadPointerShape.entries) {
            for (size in TrackpadPointerSize.entries) {
                for (density in listOf(1f, 2.75f)) {
                    val windowPx = TrackpadPointerGeometry.windowPx(shape, size, density)
                    for (type in types) {
                        val params = mimirTrackpadPointerParams(type, windowPx)
                        val what = "$shape ${size.dp} dp at $density on type $type"
                        assertEquals(what, windowPx, params.width)
                        assertEquals(what, windowPx, params.height)
                        assertEquals(what, untouchableAndUnfocusable, params.flags and untouchableAndUnfocusable)
                        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
                            assertEquals(what, FallbackPointerAlpha, params.alpha, 0f)
                            assertTrue("$what: alpha ${params.alpha} is at or below the 0.8 maximum", params.alpha <= 0.8f)
                        } else {
                            assertEquals(what, 1f, params.alpha, 0f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun theArrowsWindowNeverCoversTheClickPoint() {
        for (type in types) for ((x, y) in listOf(0 to 0, 100 to 200, 1919 to 1079)) {
            val (originX, originY) = mimirTrackpadPointerOrigin(TrackpadPointerShape.SLIM_ARROW, x.toFloat(), y.toFloat(), 50, type)
            // The window covers [origin, origin + size); the click point must lie outside that rectangle.
            assertTrue("x origin $originX must clear $x", originX > x)
            assertTrue("y origin $originY must clear $y", originY > y)
            assertTrue("and stay beside the tip", originX - x <= 4 && originY - y <= 4)
        }
    }

    @Test
    fun theFallbackDotsWindowFrameNeverContainsTheClickPoint() {
        val fallback = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        for (size in TrackpadPointerSize.entries) {
            for (density in listOf(1f, 2.75f)) {
                val windowPx = TrackpadPointerGeometry.windowPx(TrackpadPointerShape.DOT, size, density)
                for ((x, y) in listOf(0f to 0f, 100f to 200f, 1919f to 1079f, 640.4f to 360.6f, 5.9f to 7.2f)) {
                    val (originX, originY) = mimirTrackpadPointerOrigin(TrackpadPointerShape.DOT, x, y, windowPx, fallback)
                    // The frame the window manager checks is [origin, origin + windowPx) on each axis; the click is outside it.
                    val inFrame = x >= originX && x < originX + windowPx && y >= originY && y < originY + windowPx
                    assertFalse("${size.dp} dp at $density: the fallback dot's frame must not contain ($x, $y)", inFrame)
                    assertTrue("and it is the arrow's origin", (originX to originY) == mimirTrackpadPointerOrigin(TrackpadPointerShape.SLIM_ARROW, x, y, windowPx, fallback))
                }
            }
        }
    }

    @Test
    fun theAccessibilityOverlaysDotKeepsTheCenteredOriginAndThatFrameDoesContainTheClick() {
        val trusted = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val windowPx = TrackpadPointerGeometry.windowPx(TrackpadPointerShape.DOT, TrackpadPointerSize.SMALL, 2.75f)
        val (originX, originY) = mimirTrackpadPointerOrigin(TrackpadPointerShape.DOT, 640f, 360f, windowPx, trusted)
        assertEquals(640 - windowPx / 2, originX)
        assertEquals(360 - windowPx / 2, originY)
        assertTrue(640f >= originX && 640f < originX + windowPx)
    }

    @Test
    fun theDotsWindowIsCenteredOnTheClickPointForEverySize() {
        for (size in TrackpadPointerSize.entries) {
            val windowPx = TrackpadPointerGeometry.windowPx(TrackpadPointerShape.DOT, size, 2.75f)
            for ((x, y) in listOf(0 to 0, 100 to 200, 1919 to 1079)) {
                val (originX, originY) = mimirTrackpadPointerOrigin(TrackpadPointerShape.DOT, x.toFloat(), y.toFloat(), windowPx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                assertEquals("${size.dp} dp: the window's center column is the click's", x, originX + windowPx / 2)
                assertEquals("${size.dp} dp: the window's center row is the click's", y, originY + windowPx / 2)
                assertTrue("it does cover the click point, by design", x in originX until originX + windowPx)
            }
        }
    }
}
