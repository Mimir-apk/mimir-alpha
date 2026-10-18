package com.gruning.mimir.rewrite.core.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerKeymapTest {

    @Test
    fun remappedFaceButtonProducesMappedAction() {
        val map = defaultControllerKeymap().toMutableMap().apply {
            this[PhysicalControl.FACE_X] = ControllerAction.TERTIARY
        }
        val authority = DefaultInputAuthority(keymap = { map })

        assertEquals(
            ControllerAction.TERTIARY,
            (authority.interpret(key(KeyEvent.KEYCODE_BUTTON_X)) as InputDecision.Normalized).action,
        )
    }

    @Test
    fun remappedDpadKeyAndHatTwinCoalesceToOneMappedAction() {
        var now = 1_000L
        val map = defaultControllerKeymap().toMutableMap().apply {
            this[PhysicalControl.DPAD_UP] = ControllerAction.SECONDARY
        }
        val authority = DefaultInputAuthority(clock = { now }, keymap = { map })

        val first = authority.interpret(key(KeyEvent.KEYCODE_DPAD_UP, eventTimeMs = now))
        now += 40L
        val twin = authority.interpret(
            motion(mapOf(MotionEvent.AXIS_HAT_Y to -1f), eventTimeMs = now),
        )

        assertEquals(ControllerAction.SECONDARY, (first as InputDecision.Normalized).action)
        assertTrue(twin is InputDecision.Swallowed)
        assertTrue((twin as InputDecision.Swallowed).reason.startsWith("dual_source_duplicate"))
    }

    @Test
    fun dpadMappedToNonDirectionalSwallowsAutoRepeat() {
        val map = defaultControllerKeymap().toMutableMap().apply {
            this[PhysicalControl.DPAD_RIGHT] = ControllerAction.TERTIARY
        }
        val authority = DefaultInputAuthority(keymap = { map })
        authority.interpret(key(KeyEvent.KEYCODE_DPAD_RIGHT))

        assertEquals(
            "key_repeat",
            (authority.interpret(key(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1)) as InputDecision.Swallowed).reason,
        )
    }

    @Test
    fun triggerArmLatchIsPerPhysicalControlWhenActionsMatch() {
        val map = defaultControllerKeymap().toMutableMap().apply {
            this[PhysicalControl.L2] = ControllerAction.CONFIRM
            this[PhysicalControl.R2] = ControllerAction.CONFIRM
        }
        val authority = DefaultInputAuthority(keymap = { map })

        val left = authority.interpret(motion(mapOf(MotionEvent.AXIS_LTRIGGER to 1f)))
        val right = authority.interpret(motion(mapOf(MotionEvent.AXIS_RTRIGGER to 1f)))

        assertEquals(ControllerAction.CONFIRM, (left as InputDecision.Normalized).action)
        assertEquals(ControllerAction.CONFIRM, (right as InputDecision.Normalized).action)
    }

    @Test
    fun keymapSwapResetsHeldBookkeeping() {
        var map = defaultControllerKeymap()
        val authority = DefaultInputAuthority(keymap = { map })
        authority.interpret(key(KeyEvent.KEYCODE_DPAD_LEFT))
        map = map.toMutableMap().apply {
            this[PhysicalControl.DPAD_LEFT] = ControllerAction.SECONDARY
        }
        authority.reset()
        authority.interpret(key(KeyEvent.KEYCODE_DPAD_LEFT, down = false))

        assertEquals(
            ControllerAction.SECONDARY,
            (authority.interpret(key(KeyEvent.KEYCODE_DPAD_LEFT)) as InputDecision.Normalized).action,
        )
    }

    @Test
    fun hardwareBackAlwaysCancelsRegardlessOfKeymap() {
        val map = PhysicalControl.entries.associateWith<PhysicalControl, ControllerAction?> { null }
        val authority = DefaultInputAuthority(keymap = { map })

        assertEquals(
            ControllerAction.CANCEL,
            (authority.interpret(key(KeyEvent.KEYCODE_BACK)) as InputDecision.Normalized).action,
        )
    }

    // REMOVED 2026-09-06 with InputMode: `textEntryModeHonorsRemappedCancelControl` exercised the
    // TEXT_ENTRY swallow, which no production path could ever reach because nothing assigned the mode.
    // Its live half -- that a remapped control still normalises to CANCEL -- is covered by the remapping
    // tests above, which do not need a mode to say so.

    @Test
    fun unboundNonDirectionalControlIsPassthrough() {
        val map = defaultControllerKeymap().toMutableMap().apply {
            this[PhysicalControl.FACE_X] = null
        }
        val authority = DefaultInputAuthority(keymap = { map })

        assertTrue(authority.interpret(key(KeyEvent.KEYCODE_BUTTON_X)) is InputDecision.Passthrough)
    }

    private fun key(
        keyCode: Int,
        down: Boolean = true,
        repeat: Int = 0,
        eventTimeMs: Long = 1_000L,
    ) = RawInputEvent(
        kind = RawInputEvent.Kind.KEY,
        keyCode = keyCode,
        down = down,
        repeat = repeat,
        eventTimeMs = eventTimeMs,
    )

    private fun motion(
        axes: Map<Int, Float>,
        eventTimeMs: Long = 1_000L,
    ) = RawInputEvent(
        kind = RawInputEvent.Kind.MOTION,
        axisValues = axes,
        eventTimeMs = eventTimeMs,
    )

    /**
     * The factory map is a CONSTANT, so it must be one instance rather than a fresh 15-entry map per
     * call. `DefaultInputAuthority` defaults its keymap provider to `{ defaultControllerKeymap() }`, and
     * `interpretKey`, `interpretMotion`, `interpretHat`, `releaseHatDirection` and `keyToAction` each
     * call that provider independently — so one motion sample allocated three or four of these. While
     * the Controller Mapping overlay is open every motion sample routes through an authority on that
     * default, and a held stick allocated hundreds of throwaway maps a second on the input thread.
     *
     * Identity is the only instrument that can see this: the maps were always EQUAL, which is exactly
     * why the allocation went unnoticed.
     */
    @Test
    fun factoryKeymapIsOneSharedInstanceNotAFreshMapPerCall() {
        assertSame(defaultControllerKeymap(), defaultControllerKeymap())
    }

    @Test
    fun sharingTheFactoryKeymapDoesNotLetACallerMutateItForEveryoneElse() {
        val copy = defaultControllerKeymap().toMutableMap()
        copy[PhysicalControl.FACE_A] = ControllerAction.CANCEL

        assertEquals(ControllerAction.CONFIRM, defaultControllerKeymap()[PhysicalControl.FACE_A])
    }
}
