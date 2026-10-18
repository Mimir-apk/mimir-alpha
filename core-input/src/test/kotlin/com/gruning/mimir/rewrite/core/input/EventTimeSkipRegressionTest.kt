package com.gruning.mimir.rewrite.core.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ML01 () — the eaten-press regression pins.
 *
 * THE MECHANISM: every timing window in the input stack (the 50ms same-keycode coalesce, the 60ms
 * directional pace, the 260/120ms repeat gates) used PROCESSING-time clocks. A janky frame queues
 * input on the main thread, so two presses made ~200ms apart at the thumb PROCESS back-to-back —
 * the windows then judged the second press a duplicate/pace violation and ATE it. These tests
 * simulate exactly that: event timestamps far apart, the processing clock frozen (the maximal
 * compression), and assert both presses land. The pace floor still holds at EVENT time, so real
 * key-jam/bounce (<60ms at the thumb) stays absorbed.
 */
class EventTimeSkipRegressionTest {

    private var wallClock = 1_000L
    private lateinit var authority: DefaultInputAuthority

    @Before fun setUp() {
        wallClock = 1_000L
        authority = DefaultInputAuthority(clock = { wallClock })
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { wallClock }
    }

    private fun keyDown(code: Int, eventTimeMs: Long) = RawInputEvent(
        kind = RawInputEvent.Kind.KEY, keyCode = code, down = true, eventTimeMs = eventTimeMs,
    )

    private fun keyUp(code: Int, eventTimeMs: Long) = RawInputEvent(
        kind = RawInputEvent.Kind.KEY, keyCode = code, down = false, eventTimeMs = eventTimeMs,
    )

    private fun hat(x: Float, eventTimeMs: Long) = RawInputEvent(
        kind = RawInputEvent.Kind.MOTION,
        axisValues = mapOf(MotionEvent.AXIS_HAT_X to x, MotionEvent.AXIS_HAT_Y to 0f),
        eventTimeMs = eventTimeMs,
    )

    @Test
    fun `jank compression - two KEY presses 200ms apart at the thumb both land with a frozen processing clock`() {
        // Press, release, press — real gaps 200ms, but the processing clock never advances (a 200ms+
        // stall means all three process in one burst). The coalesce window must judge at event time.
        val first = authority.interpret(keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, eventTimeMs = 10_000))
        authority.interpret(keyUp(KeyEvent.KEYCODE_DPAD_RIGHT, eventTimeMs = 10_080))
        val second = authority.interpret(keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, eventTimeMs = 10_200))

        assertTrue(first is InputDecision.Normalized)
        assertTrue("the second real press must not be eaten as a duplicate", second is InputDecision.Normalized)
    }

    @Test
    fun `jank compression - two HAT presses 200ms apart both land with a frozen processing clock`() {
        val first = authority.interpret(hat(1f, eventTimeMs = 10_000))
        authority.interpret(hat(0f, eventTimeMs = 10_080))
        val second = authority.interpret(hat(1f, eventTimeMs = 10_200))

        assertTrue(first is InputDecision.Normalized)
        assertTrue("the second real HAT press must not be eaten", second is InputDecision.Normalized)
    }

    @Test
    fun `jank compression - the pace accepts two presses 200ms apart at event time`() {
        assertTrue(MimirDirectionalPace.tryAccept(eventTimeMs = 10_000))
        assertTrue(
            "a real 200ms gap must pass the 60ms floor even when both dispatch in one burst",
            MimirDirectionalPace.tryAccept(eventTimeMs = 10_200),
        )
    }

    @Test
    fun `the pace floor still absorbs real key-jam at event time`() {
        assertTrue(MimirDirectionalPace.tryAccept(eventTimeMs = 10_000))
        assertFalse(
            "a real <60ms bounce is still absorbed (the anti-jam contract)",
            MimirDirectionalPace.tryAccept(eventTimeMs = 10_040),
        )
        assertTrue(MimirDirectionalPace.tryAccept(eventTimeMs = 10_120))
    }

    @Test
    fun `a negative event-time delta accepts without rewinding the stamp`() {
        assertTrue(MimirDirectionalPace.tryAccept(eventTimeMs = 50_000))
        // A stamp from a different base / an out-of-order device must never lock navigation out…
        assertTrue(MimirDirectionalPace.tryAccept(eventTimeMs = 10_000))
        // …and must never REWIND the window either (panel A1): the newest stamp stays authoritative,
        // so a burst right after the newest accept is still floored…
        assertFalse(MimirDirectionalPace.tryAccept(eventTimeMs = 50_030))
        // …while another out-of-order stamp still passes.
        assertTrue(MimirDirectionalPace.tryAccept(eventTimeMs = 10_030))
    }

    @Test
    fun `no-timestamp events keep the processing-clock behavior for existing tests`() {
        // Bare RawInputEvents (eventTimeMs = 0) fall back to the injected clock — the pre-ML01 model.
        val first = authority.interpret(keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, eventTimeMs = 0))
        wallClock += 30
        authority.interpret(keyUp(KeyEvent.KEYCODE_DPAD_RIGHT, eventTimeMs = 0))
        val second = authority.interpret(keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, eventTimeMs = 0))

        assertTrue(first is InputDecision.Normalized)
        assertTrue(
            "clock-based same-keycode coalesce still applies to untimed events",
            second is InputDecision.Swallowed,
        )
        assertEquals(
            "dual_source_duplicate_keycode_${KeyEvent.KEYCODE_DPAD_RIGHT}",
            (second as InputDecision.Swallowed).reason,
        )
    }

    @Test
    fun `repeat gates judge at event time - held HAT repeats keep their cadence under a frozen clock`() {
        // Hold RIGHT: fresh press at t=0, then repeat samples. The first repeat gates on the 260ms
        // deliberate delay, later ones on the 120ms interval — all at EVENT time.
        assertTrue(authority.interpret(hat(1f, eventTimeMs = 20_000)) is InputDecision.Normalized)
        assertTrue(authority.interpret(hat(1f, eventTimeMs = 20_100)) is InputDecision.Swallowed)
        assertTrue(authority.interpret(hat(1f, eventTimeMs = 20_270)) is InputDecision.Normalized)
        assertTrue(authority.interpret(hat(1f, eventTimeMs = 20_330)) is InputDecision.Swallowed)
        assertTrue(authority.interpret(hat(1f, eventTimeMs = 20_400)) is InputDecision.Normalized)
    }
}
