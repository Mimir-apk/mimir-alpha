package com.gruning.mimir.rewrite

import android.view.KeyEvent
import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPace
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * BM03 — the nav tick (sound + haptic) fires only for presses that visibly did something. The
 * maniac mash on the 5-item Settings rail played 24 ticks for 24 presses: every dead-end press
 * sounded like movement. The router now snapshots [InputRouter.navMoveProbe] (production: the
 * FocusAuthority's state) around the handler and ticks only when the snapshot changed — or when a
 * surface recorded a non-focus action on [MimirNavFeedback] (value steppers, reducer-focus
 * overlays, the Settings edge reveal). A null probe preserves the pre-BM03 tick-per-press
 * behavior so existing tests and previews are untouched.
 */
class InputRouterNavTickGateTest {

    private var clock = 1_000L
    private var ticks = 0
    private lateinit var router: InputRouter

    @Before fun setUp() {
        clock = 1_000L
        ticks = 0
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { clock }
        router = InputRouter(
            interactionRoot = InteractionRoot.PRIMARY,
            inputAuthority = DefaultInputAuthority(clock = { clock }),
            onNavTick = { ticks++ },
        )
    }

    @After fun tearDown() {
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { System.nanoTime() / 1_000_000 }
    }

    /** One full HAT press+release at the current clock, then advance well past every window. */
    private fun press() {
        router.onRawMotionSample(
            axes = mapOf(MotionEvent.AXIS_HAT_X to 1f, MotionEvent.AXIS_HAT_Y to 0f),
            eventTimeMs = clock,
        )
        clock += 100
        router.onRawMotionSample(
            axes = mapOf(MotionEvent.AXIS_HAT_X to 0f, MotionEvent.AXIS_HAT_Y to 0f),
            eventTimeMs = clock,
        )
        clock += 100
    }

    @Test
    fun `null probe preserves tick-per-press`() {
        router.handler = { true }
        repeat(3) { press() }
        assertEquals(3, ticks)
    }

    @Test
    fun `a press that moves nothing stays silent`() {
        router.handler = { true } // dead-end: consumes, changes nothing (contract rule 4)
        router.navMoveProbe = { "pinned-focus" }
        repeat(12) { press() }
        assertEquals("12 dead-end presses, zero ticks", 0, ticks)
    }

    @Test
    fun `a press that moves focus ticks`() {
        var focus = 0
        router.handler = { focus++; true }
        router.navMoveProbe = { focus }
        repeat(4) { press() }
        assertEquals(4, ticks)
    }

    @Test
    fun `a non-focus action recorded on the ledger ticks`() {
        router.handler = { MimirNavFeedback.consumed(); true } // e.g. a slider step
        router.navMoveProbe = { "pinned-focus" }
        repeat(4) { press() }
        assertEquals(4, ticks)
    }

    @Test
    fun `a pace-rejected press never ticks even if state would change`() {
        var focus = 0
        router.handler = { focus++; true }
        router.navMoveProbe = { focus }
        router.onRawMotionSample(
            axes = mapOf(MotionEvent.AXIS_HAT_X to 1f, MotionEvent.AXIS_HAT_Y to 0f),
            eventTimeMs = clock,
        )
        assertEquals(1, ticks)
        // Release + immediate re-press 30ms later: inside the 60ms floor, consumed and dropped.
        router.onRawMotionSample(
            axes = mapOf(MotionEvent.AXIS_HAT_X to 0f, MotionEvent.AXIS_HAT_Y to 0f),
            eventTimeMs = clock + 10,
        )
        router.onRawMotionSample(
            axes = mapOf(MotionEvent.AXIS_HAT_X to 1f, MotionEvent.AXIS_HAT_Y to 0f),
            eventTimeMs = clock + 30,
        )
        assertEquals("sub-floor press is dropped before the gate", 1, ticks)
    }

    @Test
    fun `held dpad ticks once per committed move and stays silent at the finite edge`() {
        var focus = 0
        router.handler = { action ->
            if (action == com.gruning.mimir.rewrite.core.input.ControllerAction.NAV_RIGHT) {
                focus = (focus + 1).coerceAtMost(2)
            }
            true
        }
        router.navMoveProbe = { focus }

        router.onRawKeyInput(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            down = true,
            eventTimeMs = clock,
        )
        assertEquals(1, focus)
        assertEquals(1, ticks)

        clock += 260
        router.onRawKeyInput(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            down = true,
            repeat = 1,
            eventTimeMs = clock,
        )
        assertEquals(2, focus)
        assertEquals(2, ticks)

        // The authority continues accepting held repeats at its 120 ms cruise, but the finite reducer
        // cannot move beyond index 2. The probe suppresses both sound and haptic at that true edge.
        repeat(4) { repeatIndex ->
            clock += 120
            router.onRawKeyInput(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                down = true,
                repeat = repeatIndex + 2,
                eventTimeMs = clock,
            )
        }
        assertEquals(2, focus)
        assertEquals("edge repeats must not produce phantom navigation cues", 2, ticks)
    }

    @Test
    fun `dpad burst inside the global floor neither moves nor sounds`() {
        var focus = 1
        router.handler = { action ->
            focus = when (action) {
                com.gruning.mimir.rewrite.core.input.ControllerAction.NAV_LEFT -> (focus - 1).coerceAtLeast(0)
                com.gruning.mimir.rewrite.core.input.ControllerAction.NAV_RIGHT -> (focus + 1).coerceAtMost(2)
                else -> focus
            }
            true
        }
        router.navMoveProbe = { focus }

        router.onRawKeyInput(KeyEvent.KEYCODE_DPAD_RIGHT, down = true, eventTimeMs = clock)
        assertEquals(2, focus)
        assertEquals(1, ticks)

        // A real opposite-direction edge delivered 30 ms later normalizes, but the 60 ms global pace
        // rejects it before the handler and before the cue gate.
        router.onRawKeyInput(KeyEvent.KEYCODE_DPAD_LEFT, down = true, eventTimeMs = clock + 30)
        assertEquals(2, focus)
        assertEquals(1, ticks)

        router.onRawKeyInput(KeyEvent.KEYCODE_DPAD_LEFT, down = false, eventTimeMs = clock + 40)
        // A fresh gesture outside both the 50 ms duplicate-coalescing window and the 60 ms global
        // actuation floor commits normally. The swallowed burst did not leak a movement or cue.
        router.onRawKeyInput(KeyEvent.KEYCODE_DPAD_LEFT, down = true, eventTimeMs = clock + 90)
        assertEquals(1, focus)
        assertEquals(2, ticks)
    }

    /** One full key press and release (a shoulder, a face button), then past every window. */
    private fun pressKey(keyCode: Int) {
        router.onRawKeyInput(keyCode, down = true, eventTimeMs = clock)
        clock += 30
        router.onRawKeyInput(keyCode, down = false, eventTimeMs = clock)
        clock += 150
    }

    @Test
    fun `a shoulder or face button that recorded a stepped value ticks like a dpad step`() {
        router.handler = { MimirNavFeedback.stepped(); true } // e.g. ADJUST's L1/R1 size step, X reset
        router.navMoveProbe = { "pinned-focus" }
        pressKey(KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(1, ticks)
        pressKey(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(2, ticks)
        pressKey(KeyEvent.KEYCODE_BUTTON_X)
        assertEquals(3, ticks)
    }

    @Test
    fun `a non-directional press that changed nothing stays silent, probe or no probe`() {
        router.handler = { true } // the value was at its limit: the surface recorded nothing
        repeat(2) { withProbe ->
            router.navMoveProbe = if (withProbe == 0) null else ({ "pinned-focus" })
            pressKey(KeyEvent.KEYCODE_BUTTON_L1)
            pressKey(KeyEvent.KEYCODE_BUTTON_R1)
            pressKey(KeyEvent.KEYCODE_BUTTON_X)
            pressKey(KeyEvent.KEYCODE_BUTTON_A)
            pressKey(KeyEvent.KEYCODE_BUTTON_B)
        }
        assertEquals("a null probe's tick-per-press is for directionals only", 0, ticks)
    }

    @Test
    fun `a non-directional press is deaf to the directional ledger`() {
        // Surfaces already call consumed() from shoulder and face-button handlers (the Tome folio's page moves, the equalizer's X reset); those stay as they sound.
        router.handler = { MimirNavFeedback.consumed(); true }
        router.navMoveProbe = { "pinned-focus" }
        pressKey(KeyEvent.KEYCODE_BUTTON_L1)
        pressKey(KeyEvent.KEYCODE_BUTTON_X)
        pressKey(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(0, ticks)
    }

    @Test
    fun `stepped on a directional press is not a second tick`() {
        var focus = 0
        router.handler = { focus++; MimirNavFeedback.stepped(); true }
        router.navMoveProbe = { focus }
        repeat(3) { press() }
        assertEquals("one tick per directional press, judged by the focus snapshot", 3, ticks)
    }
}
