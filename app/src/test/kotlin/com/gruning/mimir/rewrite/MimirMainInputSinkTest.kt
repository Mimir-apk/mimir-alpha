package com.gruning.mimir.rewrite

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * The two rules [MimirMainInputSink] exists to hold, both of which fail silently on a device.
 *
 * 1. **Registration is identity-guarded.** A successor `MainActivity`'s `onResume` can run before the
 *    outgoing instance's `onPause` — the same ordering `MainActivity.onDestroy` already guards for
 *    `ControllerMappingStore.onApplied` and `LaunchStore.dualScreenLaunchListener`. An unconditional
 *    clear in `onPause` would unregister the live instance, and the only symptom would be display 4
 *    quietly absorbing every key with a resumed Main sitting right there.
 * 2. **A missing sink absorbs.** The floor must consume rather than fall through to `super`, because
 *    there is nothing under it to receive a controller key and the ANR being fixed (Thor 2026-09-11
 *    09:34) is precisely an event with nowhere to land.
 *
 * The rule is exercised through [MimirMainInputSink.route] with a stand-in event: that generic function
 * IS the production rule — `routeKey`/`routeMotion` are one-line delegations to it, pinned as source by
 * [CompanionSecondaryHomeFocusTest] — and neither `KeyEvent` nor `MotionEvent` can be constructed
 * meaningfully in a JVM unit test.
 */
class MimirMainInputSinkTest {

    private val keysA: (KeyEvent) -> Boolean = { true }
    private val motionA: (MotionEvent) -> Boolean = { true }
    private val keysB: (KeyEvent) -> Boolean = { true }
    private val motionB: (MotionEvent) -> Boolean = { true }

    @Before fun clearSink() = reset()

    @After fun releaseSink() = reset()

    private fun reset() {
        MimirMainInputSink.keys = null
        MimirMainInputSink.motion = null
    }

    @Test
    fun `register publishes both lanes and release takes them back`() {
        MimirMainInputSink.register(keys = keysA, motion = motionA)
        assertSame(keysA, MimirMainInputSink.keys)
        assertSame(motionA, MimirMainInputSink.motion)

        MimirMainInputSink.release(keys = keysA, motion = motionA)
        assertNull(MimirMainInputSink.keys)
        assertNull(MimirMainInputSink.motion)
    }

    @Test
    fun `a successor's registration survives the outgoing instance's release`() {
        MimirMainInputSink.register(keys = keysA, motion = motionA)
        // Successor onResume lands first...
        MimirMainInputSink.register(keys = keysB, motion = motionB)
        // ...then the outgoing instance's onPause releases what IT registered.
        MimirMainInputSink.release(keys = keysA, motion = motionA)

        assertSame("the live instance's key lane was unregistered by its predecessor", keysB, MimirMainInputSink.keys)
        assertSame("the live instance's motion lane was unregistered by its predecessor", motionB, MimirMainInputSink.motion)
    }

    @Test
    fun `release is per-lane, so a half-replaced registration cannot strand the other half`() {
        MimirMainInputSink.register(keys = keysA, motion = motionA)
        MimirMainInputSink.keys = keysB

        MimirMainInputSink.release(keys = keysA, motion = motionA)

        assertSame(keysB, MimirMainInputSink.keys)
        assertNull(MimirMainInputSink.motion)
    }

    @Test
    fun `no resumed Main absorbs the event`() {
        assertEquals(
            MimirSecondaryInputRoute.ABSORBED_NO_MAIN,
            MimirMainInputSink.route<String>(sink = null, event = "event"),
        )
    }

    @Test
    fun `a resumed Main that declines still absorbs — the floor never falls through to super`() {
        assertEquals(
            MimirSecondaryInputRoute.ABSORBED_MAIN_DECLINED,
            MimirMainInputSink.route(sink = { _: String -> false }, event = "event"),
        )
    }

    @Test
    fun `a resumed Main that handles the event forwards it`() {
        var seen: String? = null
        val route = MimirMainInputSink.route(
            sink = { event: String ->
                seen = event
                true
            },
            event = "event",
        )
        assertEquals(MimirSecondaryInputRoute.FORWARDED_TO_MAIN, route)
        assertEquals("the sink must receive the original event, not a copy", "event", seen)
    }

    @Test
    fun `the sink is read once per dispatch, so it cannot go null mid-event`() {
        // route() takes the sink as a parameter precisely so the @Volatile field is read once. Clearing
        // the field from inside the sink must not change this dispatch's outcome.
        val route = MimirMainInputSink.route(
            sink = { _: String ->
                MimirMainInputSink.keys = null
                true
            },
            event = "event",
        )
        assertEquals(MimirSecondaryInputRoute.FORWARDED_TO_MAIN, route)
    }
}
