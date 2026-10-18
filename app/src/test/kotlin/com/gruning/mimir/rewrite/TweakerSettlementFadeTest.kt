package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.liquidGlassContentPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * the Tweaker settlement prompt ("APPLYING ... PLEASE WAIT": a 0.56 scrim and a glass
 * card) cut in and out in one frame inside the Utility panel, Main and Bottom alike. Frame by frame, on the paused clock, 16 ms a step: a
 * white field stands in for the panel, a corner outside the card reads the scrim over it.
 *
 * The prompt is still on screen, at an alpha above zero and below the settled one, the frame after it is released; it is gone only once
 * the overlay clock has run; and it arrives the same way. The class runs as API 30, the level at which the platform cannot blur (a
 * software canvas cannot draw the RenderNode layers a blur records); the blur itself is the sweep's, on the emulator at animator scale 5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TweakerSettlementFadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var prompt by mutableStateOf<TweakerSettlementPrompt?>(null)
    private val applying = TweakerSettlementPrompt("APPLYING EFFICIENCY…", "PLEASE WAIT", canCancel = true)
    private val description = "APPLYING EFFICIENCY…. PLEASE WAIT"
    private var cancels = 0

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TweakerSettlementLayer(
                        prompt = prompt,
                        width = 300.dp,
                        palette = LocalMimirPalette.current.liquidGlassContentPalette(),
                        onCancel = { cancels++ },
                        reducedMotion = false,
                    )
                }
            }
        }
        advance(16L)
    }

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        var left = ms
        while (left > 0L) {
            val step = minOf(16L, left)
            compose.mainClock.advanceTimeBy(step)
            Snapshot.sendApplyNotifications()
            left -= step
        }
        compose.waitForIdle()
    }

    private fun showPrompt(next: TweakerSettlementPrompt?) {
        Snapshot.withMutableSnapshot { prompt = next }
        Snapshot.sendApplyNotifications()
    }

    /** The mean of the corner's channels, 0 (black) to 1 (the white field): a corner is outside the card and under the scrim only. */
    private fun cornerBrightness(): Float {
        val decor = compose.activity.window.decorView
        val frame = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(frame))
        val pixel = frame.getPixel(6, 6)
        return ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / (3f * 255f)
    }

    private fun cardMounted(): Boolean = compose.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `released, the prompt is still on screen the next frame, fading, and gone once the overlay clock has run`() {
        mount()
        showPrompt(applying)
        advance(400L)
        val settled = cornerBrightness()
        assertTrue("the scrim darkens the field once settled: $settled", settled < 0.6f)
        assertTrue(cardMounted())

        showPrompt(null)
        advance(16L)
        val oneFrame = cornerBrightness()
        assertTrue("the card is still mounted one frame after release", cardMounted())
        assertTrue("and the scrim is still there, at an alpha above zero: $oneFrame vs settled $settled", oneFrame < 0.99f)

        advance(64L)
        val midFade = cornerBrightness()
        assertTrue("on its way out, not cut: $midFade vs settled $settled", midFade > settled + 0.005f && midFade < 0.99f)

        advance(300L)
        assertTrue("gone once the overlay clock has run: ${cornerBrightness()}", cornerBrightness() > 0.99f)
        assertTrue(!cardMounted())
    }

    @Test
    fun `arriving, the prompt fades in and does not appear whole in one frame`() {
        mount()
        assertTrue("nothing before the prompt: ${cornerBrightness()}", cornerBrightness() > 0.99f)

        showPrompt(applying)
        advance(16L)
        val oneFrame = cornerBrightness()

        advance(400L)
        val settled = cornerBrightness()
        assertTrue(settled < 0.6f)
        assertTrue("the first frame is not the settled one: $oneFrame vs settled $settled", oneFrame > settled + 0.005f)
    }

    @Test
    fun `while it leaves, a tap on the fading scrim cancels nothing`() {
        mount()
        showPrompt(applying)
        advance(400L)
        compose.onRoot().performTouchInput { click(Offset(6f, 6f)) }
        advance(16L)
        assertEquals("control: while the change is pending a tap on the scrim asks to cancel it", 1, cancels)

        showPrompt(null)
        advance(16L)
        assertTrue("the scrim is still on screen", cardMounted())
        compose.onRoot().performTouchInput { click(Offset(6f, 6f)) }
        advance(16L)

        assertEquals("the change has ended: its cancel is no longer on offer", 1, cancels)
    }
}
