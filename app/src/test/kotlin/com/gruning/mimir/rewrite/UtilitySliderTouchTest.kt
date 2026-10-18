package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
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
 * The one slider touch grammar on all three surfaces (Utility, the music panel, the equalizer editor): a value changes on a tap or
 * after a drag along the track has passed touch slop, and a swipe across the track scrolls the list the slider sits in instead of
 * moving it. These tests inject real down, move and up sequences through a slider in a vertically scrolling column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UtilitySliderTouchTest {
    @get:Rule
    val compose = createComposeRule()

    private val values = mutableListOf<Float>()
    private var focusRequests = 0
    private var ends = 0
    private lateinit var scroll: androidx.compose.foundation.ScrollState

    private fun install(target: BrightnessTarget = BrightnessTarget.NAV_SOUND) {
        compose.setContent {
            MimirTheme {
                scroll = rememberScrollState()
                var fraction by remember { mutableFloatStateOf(0.5f) }
                Column(Modifier.height(240.dp).verticalScroll(scroll)) {
                    Spacer(Modifier.height(60.dp))
                    UtilitySliderRow(
                        label = "Test slider",
                        valueLabel = "50%",
                        fraction = fraction,
                        target = target,
                        enabled = true,
                        focused = false,
                        palette = LocalMimirPalette.current,
                        focusModifier = Modifier,
                        onRequestFocus = { focusRequests++ },
                        onValueChange = {
                            values += it
                            fraction = it
                        },
                        onInteractionEnd = { ends++ },
                    )
                    // Enough list below for a swipe to have somewhere to scroll.
                    Spacer(Modifier.height(900.dp))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun slider() = compose.onNode(
        hasContentDescription("Test slider") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
    )

    @Test
    fun `a tap moves the value to where it landed and takes focus`() {
        install()
        slider().performTouchInput { click(Offset(width * 0.75f, centerY)) }
        compose.waitForIdle()
        assertEquals(1, values.size)
        assertEquals(0.75f, values.single(), 0.06f)
        assertEquals(values.single(), slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        assertEquals(1, focusRequests)
        assertEquals(1, ends)
        assertEquals("a tap does not scroll", 0, scroll.value)
    }

    @Test
    fun `a tap whose finger drifts inside touch slop is still a tap`() {
        install()
        slider().performTouchInput {
            down(Offset(width * 0.25f, centerY))
            moveBy(Offset(2f, 1f))
            moveBy(Offset(1f, 2f))
            up()
        }
        compose.waitForIdle()
        assertEquals(1, values.size)
        assertEquals(0.25f, values.single(), 0.06f)
        assertEquals(1, focusRequests)
    }

    @Test
    fun `a vertical swipe across the slider scrolls the list and moves neither the value nor the focus`() {
        install()
        slider().performTouchInput {
            down(center)
            // Far past touch slop (16 px at xhdpi), straight up.
            moveBy(Offset(0f, -40f))
            moveBy(Offset(0f, -80f))
            moveBy(Offset(0f, -80f))
            up()
        }
        compose.waitForIdle()
        assertTrue("the slider did not move: $values", values.isEmpty())
        assertEquals("and did not take the ring on the way past", 0, focusRequests)
        assertTrue("the list scrolled instead: ${scroll.value}", scroll.value > 0)
        assertEquals("the gesture still ended", 1, ends)
    }

    @Test
    fun `a swipe that is mostly vertical with some drift is still a scroll`() {
        install()
        slider().performTouchInput {
            down(center)
            moveBy(Offset(10f, -60f))
            moveBy(Offset(10f, -60f))
            up()
        }
        compose.waitForIdle()
        assertTrue("the slider did not move: $values", values.isEmpty())
        assertTrue(scroll.value > 0)
    }

    @Test
    fun `a drag along the track past touch slop moves the value to the finger and takes focus once`() {
        install()
        slider().performTouchInput {
            down(Offset(width * 0.3f, centerY))
            moveBy(Offset(width * 0.1f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
            up()
        }
        compose.waitForIdle()
        assertTrue("it moved: $values", values.isNotEmpty())
        assertEquals("it follows the finger to 80 percent", 0.8f, values.last(), 0.06f)
        assertTrue("and never lands at the down point on the way", values.none { it < 0.35f })
        assertEquals(1, focusRequests)
        assertEquals(1, ends)
        assertEquals("a horizontal drag does not scroll", 0, scroll.value)
    }

    @Test
    fun `the equalizer's gain slider takes the same touches and lands on whole dB positions`() {
        install(target = BrightnessTarget.EQ_GAIN)
        slider().performTouchInput { click(Offset(width * 0.7f, centerY)) }
        compose.waitForIdle()
        val landed = values.single()
        assertEquals("a whole dB position", landed, EqGain.fractionFor(EqGain.positionFor(landed)), 0f)
        assertEquals(0.7f, landed, 0.06f)
    }

    @Test
    fun `the touch rule decides tap, drag and scroll from the travel alone`() {
        assertEquals(SliderTouchKind.UNDECIDED, sliderTouchKind(3f, 4f, slop = 16f))
        assertEquals(SliderTouchKind.DRAG, sliderTouchKind(30f, 5f, slop = 16f))
        assertEquals(SliderTouchKind.DRAG, sliderTouchKind(-30f, 10f, slop = 16f))
        assertEquals(SliderTouchKind.SCROLL, sliderTouchKind(5f, 30f, slop = 16f))
        assertEquals(SliderTouchKind.SCROLL, sliderTouchKind(-5f, -30f, slop = 16f))
        assertEquals("a true diagonal is not clearly along the track", SliderTouchKind.SCROLL, sliderTouchKind(20f, 20f, slop = 16f))
    }

    /**
     * A lane switched off mid-drag (the Bottom's pane parked under the finger) stops following it. [detectSliderTouch]'s
     * `enabled` was asked only once, after the down.
     */
    @Test
    fun `a lane that is switched off mid-drag stops following the finger and still ends the gesture`() {
        var allowed = true
        val emitted = mutableListOf<Float>()
        var ended = 0
        compose.setContent {
            MimirTheme {
                Box(
                    Modifier
                        .testTag("enabled-lane")
                        .size(200.dp, 40.dp)
                        .pointerInput(Unit) {
                            detectSliderTouch(
                                onEngaged = {},
                                emitAt = { emitted += it },
                                onEnd = { ended++ },
                                enabled = { allowed },
                            )
                        },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("enabled-lane").performTouchInput {
            down(Offset(width * 0.1f, centerY))
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
        }
        val whileOn = emitted.size
        assertTrue("it followed the finger while it was on: $emitted", whileOn >= 2)
        allowed = false
        compose.onNodeWithTag("enabled-lane").performTouchInput {
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals("nothing more after it was switched off: $emitted", whileOn, emitted.size)
        assertEquals("and the gesture still ended, once", 1, ended)
    }
}
