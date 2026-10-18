package com.gruning.mimir.rewrite

import android.view.KeyEvent
import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Nova's D-pad is a joystick hat, which Android hands over at the next frame; A is
 * a button, handed over at once. While a frame is late, RIGHT then A arrived as A then RIGHT, and A typed the letter
 * still focused. While the keyboard owns focus the router now holds a key's A until the frame has read the pad, then
 * dispatches in press order. The reordering itself is Android's and cannot happen on a JVM or through adb's key D-pad;
 * these pin the router's half, with events handed over in the order the Nova handed them over.
 */
class InputRouterKeyboardPadOrderTest {

    private var clock = 1_000L
    private val frames = mutableListOf<() -> Unit>()
    private val actions = mutableListOf<ControllerAction>()
    private var keyboardOwnsFocus = true
    private lateinit var router: InputRouter

    @Before fun setUp() {
        clock = 1_000L
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { clock }
        router = router(PadFrameScheduler { block -> frames += block })
    }

    @After fun tearDown() {
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { System.nanoTime() / 1_000_000 }
    }

    private fun router(scheduler: PadFrameScheduler?) = InputRouter(
        interactionRoot = InteractionRoot.PRIMARY,
        inputAuthority = DefaultInputAuthority(clock = { clock }),
        padFrameScheduler = scheduler,
    ).also { r ->
        r.handler = { action ->
            actions += action
            true
        }
        r.confirmWaitsForPadFrame = { keyboardOwnsFocus }
    }

    private fun a(atMs: Long, target: InputRouter = router) {
        clock = atMs
        target.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = atMs)
    }

    private fun release(atMs: Long) {
        clock = atMs
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = false, eventTimeMs = atMs)
    }

    private fun hatRight(atMs: Long) {
        router.onRawMotionSample(axes = mapOf(MotionEvent.AXIS_HAT_X to 1f, MotionEvent.AXIS_HAT_Y to 0f), eventTimeMs = atMs)
        router.onRawMotionSample(
            axes = mapOf(MotionEvent.AXIS_HAT_X to 0f, MotionEvent.AXIS_HAT_Y to 0f),
            eventTimeMs = atMs + 40,
        )
    }

    private fun frame() {
        val due = frames.toList()
        frames.clear()
        due.forEach { it() }
    }

    @Test
    fun `RIGHT then A, handed over as A then RIGHT, moves before it types`() {
        a(atMs = 1_100)
        assertTrue("A waits for the frame's pad input", actions.isEmpty())
        hatRight(atMs = 1_060) // pressed first, read by the frame's input stage
        frame()
        assertEquals(listOf(ControllerAction.NAV_RIGHT, ControllerAction.CONFIRM), actions)
    }

    @Test
    fun `A then RIGHT keeps its order`() {
        a(atMs = 1_100)
        hatRight(atMs = 1_140)
        assertEquals(listOf(ControllerAction.CONFIRM, ControllerAction.NAV_RIGHT), actions)
        frame()
        assertEquals("released once", listOf(ControllerAction.CONFIRM, ControllerAction.NAV_RIGHT), actions)
    }

    @Test
    fun `a lone A lands on the next frame`() {
        a(atMs = 1_100)
        assertEquals(1, frames.size)
        frame()
        assertEquals(listOf(ControllerAction.CONFIRM), actions)
    }

    @Test
    fun `a later press lets a waiting A go first`() {
        a(atMs = 1_100)
        clock = 1_180
        router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_DPAD_DOWN, down = true, eventTimeMs = 1_180)
        assertEquals(listOf(ControllerAction.CONFIRM, ControllerAction.NAV_DOWN), actions)
        a(atMs = 1_250)
        assertEquals("the next A waits again", listOf(ControllerAction.CONFIRM, ControllerAction.NAV_DOWN), actions)
        frame()
        assertEquals(listOf(ControllerAction.CONFIRM, ControllerAction.NAV_DOWN, ControllerAction.CONFIRM), actions)
    }

    @Test
    fun `the A's own release acts on nothing and leaves it waiting for the frame`() {
        a(atMs = 1_100)
        release(atMs = 1_180)
        assertTrue(actions.isEmpty())
        frame()
        assertEquals(listOf(ControllerAction.CONFIRM), actions)
    }

    @Test
    fun `on a long stall the A and its release arrive before the frame's RIGHT, and the ring still moves first`() {
        // The release is a key too, so it is handed over with the A, ahead of the hat motion the frame reads.
        a(atMs = 1_100)
        release(atMs = 1_180)
        hatRight(atMs = 1_060)
        frame()
        assertEquals(listOf(ControllerAction.NAV_RIGHT, ControllerAction.CONFIRM), actions)
    }

    @Test
    fun `outside the keyboard A is dispatched at once`() {
        keyboardOwnsFocus = false
        a(atMs = 1_100)
        assertEquals(listOf(ControllerAction.CONFIRM), actions)
        assertTrue("nothing waits for a frame", frames.isEmpty())
    }

    @Test
    fun `with no frame scheduler A is dispatched at once, as before`() {
        val plain = router(scheduler = null)
        a(atMs = 1_100, target = plain)
        assertEquals(listOf(ControllerAction.CONFIRM), actions)
    }

    @Test
    fun `reset drops an A still waiting`() {
        a(atMs = 1_100)
        router.reset()
        frame()
        assertTrue(actions.isEmpty())
    }
}
