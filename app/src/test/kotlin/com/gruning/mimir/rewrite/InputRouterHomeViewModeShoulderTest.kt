package com.gruning.mimir.rewrite

import android.view.KeyEvent
import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Directional L/R Home mode selection. Individual shoulders apply immediately on key-down with no
 * simultaneous-chord capture, latch, rearm, or provisional mode revert.
 */
class InputRouterHomeViewModeShoulderTest {
    private var now = 1_000L
    private val actions = mutableListOf<ControllerAction>()
    private val selections = mutableListOf<Boolean>()
    private var ticks = 0
    private var enabled = true

    /** The coordinator's law: selecting the already-active edge reports no change. */
    private var activeRightMode = false

    private val router = InputRouter(
            interactionRoot = InteractionRoot.PRIMARY,
        inputAuthority = DefaultInputAuthority(clock = { now }),
        onNavTick = { ticks++ },
    ).apply {
        homeViewModeSelectEnabled = { enabled }
        onHomeViewModeSelect = { right ->
            selections += right
            if (activeRightMode == right) {
                false
            } else {
                activeRightMode = right
                true
            }
        }
        handler = { action -> actions += action; true }
    }

    @Test
    fun `L selects Editors Choice and R selects Pinned Tomes with one tick per real change`() {
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(listOf(true), selections)
        assertEquals(1, ticks)

        now += 60
        press(KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(listOf(true, false), selections)
        assertEquals(2, ticks)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `repeating the already-active edge is a consumed no-op with no tick`() {
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(1, ticks)

        now += 80
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(listOf(true, true), selections)
        assertEquals(1, ticks)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `key repeats while held stay swallowed and select exactly once`() {
        down(KeyEvent.KEYCODE_BUTTON_L1)
        now += 40
        down(KeyEvent.KEYCODE_BUTTON_L1, repeat = 1)
        now += 40
        down(KeyEvent.KEYCODE_BUTTON_L1, repeat = 2)
        up(KeyEvent.KEYCODE_BUTTON_L1)

        assertEquals(listOf(false), selections)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `disabled gate preserves ordinary shoulder behavior immediately and in raw order`() {
        enabled = false
        down(KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(listOf(ControllerAction.PAGE_LEFT), actions)

        now += 20
        down(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf(ControllerAction.PAGE_LEFT, ControllerAction.CONFIRM), actions)
        assertTrue(selections.isEmpty())
        assertEquals(0, ticks)
    }

    @Test
    fun `disabled gate leaves HAT edges untouched by shoulder handling`() {
        enabled = false
        down(KeyEvent.KEYCODE_BUTTON_R1)
        now += 20
        assertTrue(
            router.onRawMotionSample(
                axes = mapOf(MotionEvent.AXIS_HAT_X to 1f),
                eventTimeMs = now,
            ),
        )
        assertEquals(listOf(ControllerAction.PAGE_RIGHT, ControllerAction.NAV_RIGHT), actions)
        assertTrue(selections.isEmpty())
    }

    @Test
    fun `gate closing mid-hold still swallows the matching release`() {
        down(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(listOf(true), selections)

        enabled = false
        now += 50
        up(KeyEvent.KEYCODE_BUTTON_R1)
        // The swallowed press's release never reaches normal dispatch as an orphan key-up.
        assertTrue(actions.isEmpty())

        // The NEXT press honestly belongs to the closed gate's ordinary behavior.
        now += 50
        down(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(listOf(ControllerAction.PAGE_RIGHT), actions)
    }

    @Test
    fun `bare router selection responds on down without a chord scheduler`() {
        down(KeyEvent.KEYCODE_BUTTON_L1)
        // No scheduler, no window: the selection already happened synchronously on the DOWN.
        assertEquals(listOf(false), selections)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `simultaneous shoulders select twice and never open a Tome surface`() {
        down(KeyEvent.KEYCODE_BUTTON_L1)
        now += 40
        down(KeyEvent.KEYCODE_BUTTON_R1)
        now += 30
        up(KeyEvent.KEYCODE_BUTTON_L1)
        now += 20
        up(KeyEvent.KEYCODE_BUTTON_R1)

        assertEquals(listOf(false, true), selections)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `repeated shoulder taps inside the old chord window each select immediately`() {
        // Former chord window was 180ms with a 150ms rearm; 50ms alternations must all land.
        press(KeyEvent.KEYCODE_BUTTON_L1)
        now += 50
        press(KeyEvent.KEYCODE_BUTTON_R1)
        now += 50
        press(KeyEvent.KEYCODE_BUTTON_L1)
        now += 50
        press(KeyEvent.KEYCODE_BUTTON_R1)
        now += 50
        press(KeyEvent.KEYCODE_BUTTON_L1)

        assertEquals(listOf(false, true, false, true, false), selections)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `reset clears a held swallow without emitting anything`() {
        down(KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(1, selections.size)

        router.reset()
        now += 50
        router.onRawKeyInput(KeyEvent.KEYCODE_BUTTON_L1, down = false, eventTimeMs = now)
        assertTrue(actions.isEmpty())
        assertEquals(1, selections.size)
    }

    private fun press(keyCode: Int) {
        down(keyCode)
        now += 30
        up(keyCode)
    }

    private fun down(keyCode: Int, repeat: Int = 0) {
        assertTrue(router.onRawKeyInput(keyCode, down = true, repeat = repeat, eventTimeMs = now))
    }

    private fun up(keyCode: Int) {
        assertTrue(router.onRawKeyInput(keyCode, down = false, eventTimeMs = now))
    }
}
