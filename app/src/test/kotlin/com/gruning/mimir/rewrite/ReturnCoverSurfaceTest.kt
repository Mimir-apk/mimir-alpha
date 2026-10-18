package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The return cover's EXIT (blurred background observed on AYN Thor after returning from Eden, 2026-10-14).
 *
 * The host used to compose the cover only while it was the top overlay, so it left composition on the very frame its request went
 * false and its one-frame beat and its `fadeOut` never ran: the restored screen was cut in. [MimirReturnCoverSurface] is composed
 * always and gated by `requested`; these tests hold it to the three things that makes true:
 *  - it is still there one frame after the request ends, fully opaque (the beat), and then fades over [MimirMotion.OverlayMs];
 *  - while it fades it takes no input: a tap goes to what is beneath and its Back handler is off;
 *  - while requested it takes both.
 * The pixels are the view hierarchy drawn into a software bitmap (the way this repo rasterises, see AchievementAwardMarksCaptureTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReturnCoverSurfaceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var requested by mutableStateOf(true)
    private var underTaps = 0

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { underTaps++ } })
                    MimirReturnCoverSurface(requested = requested)
                }
            }
        }
        step(0L)
    }

    private fun write(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
    }

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun frame() = step(FrameMs)

    private fun cover() = compose.onAllNodesWithTag(ReturnCoverTag, useUnmergedTree = true)

    /** Luma (0..1) of a pixel in the screen's top-left corner: the cover's themed ground over the white field beneath it. */
    private fun cornerLuma(): Float {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val p = bitmap.getPixel(8, 8)
        val r = (p shr 16 and 0xFF) / 255f
        val g = (p shr 8 and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    @Test
    fun `the cover is up on the request and opaque over what is beneath`() {
        cover().assertCountEquals(1)
        assertTrue("the themed ground, not the white field: ${cornerLuma()}", cornerLuma() < 0.2f)
    }

    @Test
    fun `the cover is still there a frame after its request ends, opaque, then fades over the overlay clock and goes`() {
        val opaque = cornerLuma()
        write { requested = false }
        // The one-frame beat (F4): the request has ended, the cover has not started to leave.
        frame()
        cover().assertCountEquals(1)
        assertEquals("opaque through the beat", opaque, cornerLuma(), 0.02f)

        // Half-way down the fade: the cover is still composed and the field beneath is part-way through.
        step(MimirMotion.OverlayMs / 2L)
        cover().assertCountEquals(1)
        val mid = cornerLuma()
        assertTrue("part-way through the fade, between the ground ($opaque) and the field (1.0): $mid", mid > opaque + 0.05f && mid < 0.98f)

        // Gone after the clock, and the field beneath is all there is.
        step(MimirMotion.OverlayMs.toLong() + 2 * FrameMs)
        cover().assertCountEquals(0)
        assertEquals("the white field", 1f, cornerLuma(), 0.02f)
    }

    @Test
    fun `while requested the cover takes the tap and the Back press`() {
        assertTrue("Back is held while requested", compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        compose.onRoot().performTouchInput { click() }
        step(FrameMs)
        assertEquals("the cover consumed the tap", 0, underTaps)
    }

    @Test
    fun `a fading cover takes no input - the tap reaches what is beneath and Back is not held`() {
        write { requested = false }
        frame()
        frame()
        cover().assertCountEquals(1)
        assertFalse("still showing but Back is not held", compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        compose.onRoot().performTouchInput { click() }
        step(FrameMs)
        assertEquals("the tap went through the fading cover", 1, underTaps)
    }

    @Test
    fun `asking again mid-fade brings the cover straight back and holds input again`() {
        write { requested = false }
        frame()
        frame()
        step(MimirMotion.OverlayMs / 3L)
        write { requested = true }
        frame()
        cover().assertCountEquals(1)
        assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        compose.onRoot().performTouchInput { click() }
        step(FrameMs)
        assertEquals(0, underTaps)
    }

    private companion object {
        const val FrameMs = 16L
    }
}
