package com.gruning.mimir.rewrite

import android.app.Application
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.companionnative.PadIdleGate
import com.gruning.mimir.rewrite.core.companionnative.idleWakeSamples
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Main's own wake lane . It used to decide a hat wake by the CURRENT sample of a MotionEvent alone, so a batch of [rest, RIGHT] after a hat wake was eaten whole; it
 * now runs the same sample-ordered [PadIdleGate] the Bottom's pads do, and hands the router only the samples the gate did not eat. `MainActivity.routeGenericMotionEventThroughAuthority`
 * is exactly `gate.motion(event.idleWakeSamples()) { permitted -> router.onMotionEvent(event, permitted) }` (pinned as source in [CompanionActivityPadIdleTest]); this drives that
 * pair over REAL MotionEvents, batched the way Android batches a frame's samples, into the REAL router.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainPadIdleLaneTest {

    private var now = 1_000_000L
    private var asleep = false
    private var wakes = 0
    private val actions = mutableListOf<ControllerAction>()
    private val frames = mutableListOf<() -> Unit>()
    private lateinit var router: InputRouter
    private lateinit var gate: PadIdleGate

    @Before
    fun setUp() {
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { now }
        router = InputRouter(
            interactionRoot = InteractionRoot.PRIMARY,
            inputAuthority = DefaultInputAuthority(clock = { now }),
            padFrameScheduler = PadFrameScheduler { block -> frames += block },
        ).also {
            it.handler = { action ->
                actions += action
                true
            }
            // As on Main with a keyboard up: an A waits for the frame's pad input.
            it.confirmWaitsForPadFrame = { true }
        }
        // As MainActivity builds it: the idle controller's answer (here a flag that is true until the first press asks), and the router reset as the wake's own step.
        gate = PadIdleGate(
            interaction = { asleep.also { asleep = false } },
            onWake = {
                wakes += 1
                router.reset()
            },
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { System.nanoTime() / 1_000_000 }
    }

    private fun runFrames() {
        val due = frames.toList()
        frames.clear()
        due.forEach { it() }
    }

    private fun coords(hatX: Float) = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_HAT_X, hatX) }

    /** One MotionEvent carrying these hat samples (1 = RIGHT, 0 = rest), oldest first and the last the current one, as Android batches a frame. */
    private fun batch(vararg hat: Float): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_MOVE, 1, properties, arrayOf(coords(hat.first())),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0,
        )
        for (index in 1 until hat.size) event.addBatch(now + index * 10L, arrayOf(coords(hat[index])), 0)
        return event
    }

    /** Main's motion lane over one event. */
    private fun lane(vararg hat: Float): Boolean {
        val event = batch(*hat)
        try {
            return gate.motion(event.idleWakeSamples()) { permitted -> router.onMotionEvent(event, permitted) }
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `a batch carries its samples oldest first, each as held or at rest`() {
        val event = batch(1f, 0f, 1f)
        try {
            assertEquals(listOf(true, false, true), event.idleWakeSamples().toList())
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `a hat wake then a batch of rest and RIGHT moves right once`() {
        asleep = true
        assertTrue("the wake press is eaten", lane(1f))
        assertEquals(1, wakes)
        assertEquals("and moves nothing", emptyList<ControllerAction>(), actions)
        now += 500L
        assertTrue(lane(0f, 1f))
        assertEquals("the rest released the wake, the RIGHT after it is a press of its own: one move", listOf(ControllerAction.NAV_RIGHT), actions)
        assertEquals("the rest released the wake press and reset nothing: the router was reset once, at the wake", 1, wakes)
    }

    @Test
    fun `a hat wake let go in the same batch gives no move, and neither does a wake held across it`() {
        asleep = true
        assertTrue("[RIGHT(wake), rest]", lane(1f, 0f))
        assertEquals("no move", emptyList<ControllerAction>(), actions)
        assertEquals("the router was reset at the wake only; nothing is left latched", 1, wakes)
        now += 500L
        assertTrue(lane(1f))
        assertEquals("the next press moves", listOf(ControllerAction.NAV_RIGHT), actions)
        actions.clear()
        lane(0f)
        now += 500L
        asleep = true
        assertTrue("[RIGHT(wake), RIGHT]: still the wake press", lane(1f, 1f))
        assertEquals(emptyList<ControllerAction>(), actions)
        assertEquals("a second wake, a second reset", 2, wakes)
        assertTrue("and its release is eaten too", lane(0f))
        assertEquals("the release resets nothing", 2, wakes)
        assertEquals(emptyList<ControllerAction>(), actions)
    }

    @Test
    fun `an A queued after a hat wake survives the batched neutral and dispatches on its frame`() {
        asleep = true
        assertTrue("the hat wake is eaten", lane(1f))
        assertEquals(1, wakes)
        // A separate press: the A goes to the router, which holds it for the frame's pad input (a keyboard owns focus).
        assertTrue(gate.key(down = true, up = false, keyCode = KeyEvent.KEYCODE_BUTTON_A) {
            router.onRawKeyInput(keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, eventTimeMs = now)
            true
        })
        assertEquals("the A waits for its frame", 1, frames.size)
        assertEquals(emptyList<ControllerAction>(), actions)
        // The hat's neutral is batched in after it: it ends the wake press and must not reset the router, or the queued A is lost.
        now += 20L
        assertTrue(lane(0f))
        assertEquals("the neutral reset nothing", 1, wakes)
        runFrames()
        assertEquals("the queued A dispatched", listOf(ControllerAction.CONFIRM), actions)
    }

    @Test
    fun `a wake then a rest then a new press in one batch moves once`() {
        asleep = true
        assertTrue(lane(1f, 0f, 1f))
        assertEquals("the wake sample is never replayed: only the press after the rest moves", listOf(ControllerAction.NAV_RIGHT), actions)
    }

    private fun stickEvent(x: Float, y: Float): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, x)
            setAxisValue(MotionEvent.AXIS_Y, y)
        }
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, properties, arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0)
    }

    private fun directionOf(x: Float, y: Float): Int {
        val event = stickEvent(x, y)
        try {
            return event.idleWakeSamples().direction(0)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `a stick's direction is its axes past the dead zone, whatever the size of the deflection or the jitter inside the zone`() {
        val right = directionOf(0.6f, 0.0f)
        assertTrue("past the dead zone is a direction", right != 0)
        assertEquals("a bigger deflection is the same direction", right, directionOf(0.95f, 0.0f))
        assertEquals("the other axis wandering inside the dead zone is not a change", right, directionOf(0.95f, 0.24f))
        assertEquals(right, directionOf(0.3f, -0.24f))
        assertTrue("past the dead zone on the other axis is a new direction", right != directionOf(0.95f, 0.3f))
        assertTrue("the opposite way is a different direction", right != directionOf(-0.95f, 0.0f))
        assertEquals("inside the dead zone is rest", 0, directionOf(0.24f, -0.24f))
        val hatEvent = batch(1f)
        val hatRight = try {
            hatEvent.idleWakeSamples().direction(0)
        } finally {
            hatEvent.recycle()
        }
        assertTrue("and the hat is a direction of its own, not the stick's", hatRight != 0 && hatRight != right)
    }

    @Test
    fun `a stick drifting inside its direction earns credit for two minutes and no more, a real change of direction earns again`() {
        var interactions = 0
        val counting = PadIdleGate(interaction = { interactions += 1; false }, onWake = {}, clock = { now })
        fun drift(x: Float, y: Float) {
            val event = stickEvent(x, y)
            try {
                counting.motion(event.idleWakeSamples()) { true }
            } finally {
                event.recycle()
            }
        }
        // An event every half second for three minutes; the deflection wanders and so does the other axis, always inside the zone.
        repeat(360) { step ->
            now += 500L
            drift(if (step % 2 == 0) 0.6f else 0.95f, if (step % 3 == 0) 0.1f else -0.2f)
        }
        assertEquals("240 events fall in the first two minutes; the rest of the hold earns nothing", 240, interactions)
        now += 500L
        drift(0.95f, 0.9f)
        assertEquals("the other axis going past the dead zone is a real change of direction", 241, interactions)
        now += 500L
        drift(0.0f, 0.0f)
        assertEquals("a rest earns nothing", 241, interactions)
        now += 500L
        drift(0.95f, 0.1f)
        assertEquals("and the deflection after it is a new hold", 242, interactions)
        repeat(100) {
            now += 500L
            drift(0.95f, 0.2f)
        }
        assertEquals("the same hold goes on, inside its first two minutes", 342, interactions)
    }
}
