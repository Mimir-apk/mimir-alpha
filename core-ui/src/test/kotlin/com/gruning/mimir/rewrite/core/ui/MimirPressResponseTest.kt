package com.gruning.mimir.rewrite.core.ui

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [mimirPressResponse] draws a held control in to [MimirPressScale] on `MimirMotion.FocusMs`, both ways, and not at all
 * under reduced motion. Measured on pixels, on a paused clock: a 100 dp white square on black is the control, and the scale is the square root of the ratio of the
 * white it draws to the white it draws at rest (area goes as the scale squared, and it counts the edge's partial pixels, so it reads well below a pixel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirPressResponseTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val source = MutableInteractionSource()

    @After
    fun restoreAnimators() {
        durationScale(1f)
    }

    private fun durationScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun mount(content: @Composable () -> Unit = { Control(Modifier.mimirPressResponse(source)) }) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.requiredSize(200.dp).background(Color.Black).testTag(Frame), contentAlignment = Alignment.Center) { content() } }
        // Let the effect subscribe to the source before a test emits into it (a source has no replay).
        frames(100)
    }

    @Composable
    private fun Control(modifier: Modifier) {
        Box(modifier.size(100.dp).background(Color.White))
    }

    private fun frames(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** The drawn scale of the square: the root of the white it covers over the white it covers at rest (100 x 100 px at mdpi), inside the black frame. */
    private fun drawnScale(): Float {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val pixels = full.asImageBitmap().toPixelMap()
        val frame = compose.onNodeWithTag(Frame).fetchSemanticsNode().boundsInWindow
        var white = 0f
        for (y in frame.top.toInt() until frame.bottom.toInt()) for (x in frame.left.toInt() until frame.right.toInt()) white += pixels[x, y].red
        return sqrt(white / 10_000f)
    }

    private fun press(offset: Offset = Offset.Zero): PressInteraction.Press = PressInteraction.Press(offset).also { check(source.tryEmit(it)) }

    private val beat = MimirMotion.FocusMs.toLong()

    @Test
    fun `the token is 0 97, and the response runs the focus beat`() {
        assertEquals(0.97f, MimirPressScale, 0f)
        assertEquals(140, MimirMotion.FocusMs)
    }

    @Test
    fun `a press scales to the token by the focus beat and a release returns it by the same`() {
        mount()
        assertEquals("rest", 1f, drawnScale(), 0.003f)

        val held = press()
        frames(48)
        val early = drawnScale()
        assertTrue("underway, not snapped: $early", early < 0.999f && early > MimirPressScale + 0.004f)
        frames(beat + 32L)
        assertEquals("held: the token, once the beat is over", MimirPressScale, drawnScale(), 0.004f)

        source.tryEmit(PressInteraction.Release(held))
        frames(48)
        val rising = drawnScale()
        assertTrue("underway back: $rising", rising > MimirPressScale + 0.001f && rising < 0.999f)
        frames(beat + 32L)
        assertEquals("released: whole again", 1f, drawnScale(), 0.004f)
    }

    @Test
    fun `a cancel releases the scale at once, so a press that became a drag never leaves it stuck in`() {
        mount()
        val held = press()
        frames(beat + 32L)
        assertEquals(MimirPressScale, drawnScale(), 0.004f)
        source.tryEmit(PressInteraction.Cancel(held))
        frames(beat + 32L)
        assertEquals("cancelled: whole again", 1f, drawnScale(), 0.004f)
    }

    @Test
    fun `a cancel part way in does not finish the draw-in first, a tap does`() {
        mount()
        val swiped = press()
        frames(48)
        source.tryEmit(PressInteraction.Cancel(swiped))
        // The draw-in was a third of the way; the cancel turns it straight back, so it never reaches the token.
        frames(beat + 32L)
        assertEquals("a swipe flicks nothing in: whole", 1f, drawnScale(), 0.004f)

        val tapped = press()
        frames(48)
        source.tryEmit(PressInteraction.Release(tapped))
        // The tap lets the draw-in finish, then returns: at the end of the draw-in the control is at the token.
        frames(beat - 48L)
        assertEquals("a tap is seen: the whole dip", MimirPressScale, drawnScale(), 0.006f)
        frames(beat + 32L)
        assertEquals("and returns", 1f, drawnScale(), 0.004f)
    }

    @Test
    fun `a press and release that arrive together, as a clickable in a scroller reports a quick tap, still show the whole dip`() {
        mount()
        val tap = press()
        source.tryEmit(PressInteraction.Release(tap))
        frames(beat + 16L)
        assertEquals("the dip is seen", MimirPressScale, drawnScale(), 0.006f)
        frames(beat + 32L)
        assertEquals("and returns", 1f, drawnScale(), 0.004f)
    }

    @Test
    fun `two presses hold the scale until both are over`() {
        mount()
        val first = press(Offset(1f, 1f))
        val second = press(Offset(2f, 2f))
        frames(beat + 32L)
        source.tryEmit(PressInteraction.Release(first))
        frames(beat + 32L)
        assertEquals("one is still down", MimirPressScale, drawnScale(), 0.004f)
        source.tryEmit(PressInteraction.Cancel(second))
        frames(beat + 32L)
        assertEquals(1f, drawnScale(), 0.004f)
    }

    @Test
    fun `reduced motion draws no scale at all`() {
        durationScale(0f)
        mount()
        val held = press()
        frames(beat + 64L)
        assertEquals("a press under reduced motion does not move the control", 1f, drawnScale(), 0.003f)
        source.tryEmit(PressInteraction.Release(held))
        frames(beat + 64L)
        assertEquals(1f, drawnScale(), 0.003f)
    }

    @Test
    fun `a bare tap detector reports its press, and dragging out of it cancels`() {
        val tapSource = MutableInteractionSource()
        mount {
            Control(
                Modifier
                    .pointerInput(Unit) { detectTapGestures(onPress = { mimirReportPress(tapSource, it) }) }
                    .mimirPressResponse(tapSource)
                    .testTag(ControlTag),
            )
        }
        compose.onNodeWithTag(ControlTag).performTouchInput { down(center) }
        frames(beat + 32L)
        assertEquals("a finger down: pressed", MimirPressScale, drawnScale(), 0.004f)
        compose.onNodeWithTag(ControlTag).performTouchInput { up() }
        frames(beat + 32L)
        assertEquals("a tap's release: whole", 1f, drawnScale(), 0.004f)

        compose.onNodeWithTag(ControlTag).performTouchInput { down(center) }
        frames(beat + 32L)
        compose.onNodeWithTag(ControlTag).performTouchInput { moveBy(Offset(0f, 400f)) }
        frames(beat + 32L)
        assertEquals("a finger dragged out of the control: whole, though it is still down", 1f, drawnScale(), 0.004f)
        compose.onNodeWithTag(ControlTag).performTouchInput { up() }
    }

    @Test
    fun `placed after its tap node the hit box is the whole control even while it is held in`() {
        val tapSource = MutableInteractionSource()
        var taps = 0
        mount {
            Control(
                Modifier
                    .pointerInput(Unit) { detectTapGestures(onPress = { mimirReportPress(tapSource, it) }, onTap = { taps++ }) }
                    .mimirPressResponse(tapSource)
                    .testTag(ControlTag),
            )
        }
        // A finger 1 px inside the corner, held through the whole draw-in, still lifts as a tap: the layer does not move the pointer's bounds.
        compose.onNodeWithTag(ControlTag).performTouchInput { down(Offset(1f, 1f)) }
        frames(beat + 32L)
        compose.onNodeWithTag(ControlTag).performTouchInput { moveTo(Offset(1.5f, 1.5f)) }
        frames(32L)
        compose.onNodeWithTag(ControlTag).performTouchInput { up() }
        frames(32L)
        assertEquals("the tap went ahead", 1, taps)
    }

    private companion object {
        const val Frame = "press-response-frame"
        const val ControlTag = "press-response-control"
    }
}
