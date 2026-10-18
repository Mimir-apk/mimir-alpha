package com.gruning.mimir.rewrite

import android.view.KeyEvent
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPace
import com.gruning.mimir.rewrite.core.model.MimirMarkOffsetLimitDp
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.model.MimirMarkScaleMax
import com.gruning.mimir.rewrite.core.model.MimirMarkScaleMin
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerMark
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerScope
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerState
import com.gruning.mimir.rewrite.feature.settings.dispatchMimirButtonPicker
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerTransition
import com.gruning.mimir.rewrite.feature.settings.mimirButtonTileKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * The Mimir-button picker's ADJUST mode with navigation feedback (device review 2026-10-13, Odin 3):
 * the real picker dispatch behind the real [InputRouter], counting the ticks (`onNavTick` is the nav click and its haptic, one call). A step that changed the
 * placement ticks, whichever key made it; one that could not change it (the 12 dp edge, 60% and 160%, Reset on a centred image) is silent (BM03). A and B end
 * adjust mode and tick nothing, as A on the other Appearance pickers plays nothing.
 */
class MimirButtonAdjustFeedbackTest {

    private val star = MimirButtonPickerMark("aaaaaaaa", "Your mark 1", "/app/marks/aaaaaaaa.png")
    private val authority = DefaultFocusAuthority()
    private var clock = 1_000L
    private var ticks = 0
    private lateinit var state: MimirButtonPickerState
    private lateinit var router: InputRouter

    @Before fun setUp() {
        clock = 1_000L
        ticks = 0
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { clock }
        authority.pushScope(MimirButtonPickerScope)
        authority.register(mimirButtonTileKey(star.id), MimirButtonPickerScope, FocusBounds(0f, 0f, 10f, 10f))
        authority.requestFocus(mimirButtonTileKey(star.id))
        router = InputRouter(
            interactionRoot = InteractionRoot.PRIMARY,
            inputAuthority = DefaultInputAuthority(clock = { clock }),
            onNavTick = { ticks++ },
        )
        // Production wires the focus snapshot as the probe (MainActivity): a D-pad step in adjust moves no focus, so only the picker's own record can make it tick.
        router.navMoveProbe = { authority.state.value }
        router.handler = { action ->
            dispatchMimirButtonPicker(authority, state, action) { state = it.state }
        }
    }

    @After fun tearDown() {
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { System.nanoTime() / 1_000_000 }
    }

    /** Adjust mode open on [star] with the image at [placement]. */
    private fun adjusting(placement: MimirMarkPlacement) {
        val opened = mimirButtonPickerTransition(
            mimirButtonPickerInitialState(null, listOf(star)),
            ControllerAction.TERTIARY,
            mimirButtonTileKey(star.id),
        ).state
        assertNotNull("fixture: Y opened adjust", opened.adjust)
        state = opened.copy(adjust = opened.adjust!!.copy(working = placement))
    }

    /** One key press and release, then past every window. Returns the ticks it made. */
    private fun press(keyCode: Int): Int {
        val before = ticks
        router.onRawKeyInput(keyCode, down = true, eventTimeMs = clock)
        clock += 30
        router.onRawKeyInput(keyCode, down = false, eventTimeMs = clock)
        clock += 200
        return ticks - before
    }

    private val working get() = state.adjust!!.working

    @Test
    fun `a D-pad step that moves the image ticks, and a step at the 12 dp edge does not`() {
        adjusting(MimirMarkPlacement(scale = 1.2f, offsetX = 3f, offsetY = -1.5f))
        for (key in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP)) {
            assertEquals("key $key mid-range", 1, press(key))
        }
        assertEquals("focus never left the tile, so it was the picker's record that ticked", mimirButtonTileKey(star.id), authority.state.value.focused)

        val limit = MimirMarkOffsetLimitDp
        adjusting(MimirMarkPlacement(offsetX = limit, offsetY = limit))
        assertEquals("right at +12", 0, press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("down at +12", 0, press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("the image did not move", MimirMarkPlacement(offsetX = limit, offsetY = limit), working)
        assertEquals("left from +12 is a real step", 1, press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("up from +12 is a real step", 1, press(KeyEvent.KEYCODE_DPAD_UP))

        adjusting(MimirMarkPlacement(offsetX = -limit, offsetY = -limit))
        assertEquals("left at -12", 0, press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("up at -12", 0, press(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `L1 and R1 tick when they size the image, and are silent at 60 and 160 percent`() {
        adjusting(MimirMarkPlacement(scale = 1f))
        assertEquals("R1", 1, press(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals("1.05", 1.05f, working.scale, 0.00001f)
        assertEquals("L1", 1, press(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals("L1 again", 1, press(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals("0.95", 0.95f, working.scale, 0.00001f)

        adjusting(MimirMarkPlacement(scale = MimirMarkScaleMin))
        assertEquals("L1 at 60%", 0, press(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals("still 60%", MimirMarkScaleMin, working.scale, 0f)
        assertEquals("R1 from 60%", 1, press(KeyEvent.KEYCODE_BUTTON_R1))

        adjusting(MimirMarkPlacement(scale = MimirMarkScaleMax))
        assertEquals("R1 at 160%", 0, press(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals("still 160%", MimirMarkScaleMax, working.scale, 0f)
        assertEquals("L1 from 160%", 1, press(KeyEvent.KEYCODE_BUTTON_L1))
    }

    @Test
    fun `X ticks when it puts the image back, and is silent on one that is already centred at 100 percent`() {
        adjusting(MimirMarkPlacement(scale = 1.3f, offsetX = -4f, offsetY = 2f))
        assertEquals("X on a placed image", 1, press(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(MimirMarkPlacement.Default, working)
        assertEquals("X again: nothing to put back", 0, press(KeyEvent.KEYCODE_BUTTON_X))
    }

    @Test
    fun `A saves and B cancels without a tick, whatever the placement`() {
        adjusting(MimirMarkPlacement(scale = 1.3f, offsetX = -4f, offsetY = 2f))
        assertEquals("A", 0, press(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("A left adjust mode", null, state.adjust)

        adjusting(MimirMarkPlacement(scale = 1.3f, offsetX = -4f, offsetY = 2f))
        assertEquals("B", 0, press(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals("B left adjust mode", null, state.adjust)
    }

    @Test
    fun `L1, R1 and X in the tiles do not tick`() {
        state = mimirButtonPickerInitialState(null, listOf(star))
        assertEquals("L1", 0, press(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals("R1", 0, press(KeyEvent.KEYCODE_BUTTON_R1))
    }
}
