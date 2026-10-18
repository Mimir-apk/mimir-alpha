package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RC-38 (audit 2026-10-14): what a native panel window draws on its way out. A white panel body over a black window, frames at
 * 0, 16, 90 and 170 ms and past [MimirMotion.OverlayMs]: the body is still drawn one frame after the exit begins, it is fading (white
 * between full and nothing) at the middle frames, and it is gone, with the fade reported exactly once, only after the clock has
 * run. The harness fails on the old behaviour, where the window was removed on the frame it was told to go: there was no fade to
 * observe, and the body was simply gone at the first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativePanelExitFadeRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var exiting by mutableStateOf(false)
    private var hidden = 0
    private var touches = 0

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** The mean red channel at the window's centre: 255 for the white body, 0 for the black window behind it. */
    private fun centre(): Int {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        var sum = 0
        var n = 0
        for (dx in -8..8 step 4) for (dy in -8..8 step 4) {
            sum += android.graphics.Color.red(bitmap.getPixel(bitmap.width / 2 + dx, bitmap.height / 2 + dy))
            n++
        }
        return sum / n
    }

    @Test
    fun `the body is still drawn one frame after the exit begins, fades, and is gone only after the clock`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NativePanelExitFade(exiting = exiting, onFullyHidden = { hidden++ }) {
                        Box(Modifier.fillMaxSize().background(Color.White).testTag("body"))
                    }
                }
            }
        }
        step(0L)
        val full = centre()
        assertTrue("the body is drawn at full strength before the exit (red=$full)", full >= 250)

        Snapshot.withMutableSnapshot { exiting = true }
        step(16L)
        compose.onNodeWithTag("body").assertExists()
        step(32L)
        val first = centre()
        assertTrue("one frame in, the body is still drawn (red=$first)", first > 120)
        assertTrue("and it has begun to fade (red=$first)", first < full)

        step(42L)
        val middle = centre()
        assertTrue("at 90 ms it is between full and nothing (red=$middle)", middle in 1 until first)
        assertEquals("not reported yet", 0, hidden)

        step(80L)
        compose.onNodeWithTag("body").assertExists()
        assertEquals("not reported before the clock has run (170 ms)", 0, hidden)

        step(MimirMotion.OverlayMs.toLong())
        step(32L)
        compose.onNodeWithTag("body").assertDoesNotExist()
        assertEquals("the end of the leave is reported once", 1, hidden)
        assertEquals("and the window behind is bare", 0, centre())
    }

    @Test
    fun `the body takes no touch while it leaves`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NativePanelExitFade(exiting = exiting, onFullyHidden = { hidden++ }) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.White)
                                .testTag("body")
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { touches++ },
                        )
                    }
                }
            }
        }
        step(0L)
        compose.onNodeWithTag("body").performTouchInput { down(center); up() }
        step(0L)
        assertEquals("before the exit the body answers a touch", 1, touches)
        Snapshot.withMutableSnapshot { exiting = true }
        step(16L)
        compose.onNodeWithTag("body").performTouchInput { down(center); up() }
        step(0L)
        assertEquals("while it leaves the shield takes the touch", 1, touches)
    }
}
