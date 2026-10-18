package com.gruning.mimir.rewrite.core.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultInputAuthorityTest {
    private var now = 0L
    private val authority = DefaultInputAuthority(clock = { now })

    @Test
    fun keyThenHatDirectionCoalescesToOneGesture() {
        val key = authority.interpret(
            RawInputEvent(
                kind = RawInputEvent.Kind.KEY,
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                down = true,
            ),
        )
        now += 20L
        val hat = authority.interpret(
            RawInputEvent(
                kind = RawInputEvent.Kind.MOTION,
                axisValues = mapOf(MotionEvent.AXIS_HAT_X to 1f),
            ),
        )

        assertNormalized(ControllerAction.NAV_RIGHT, key)
        assertSwallowed("dual_source_duplicate_direction_NAV_RIGHT", hat)
    }

    @Test
    fun hatThenKeyDirectionCoalescesToOneGesture() {
        val hat = authority.interpret(
            RawInputEvent(
                kind = RawInputEvent.Kind.MOTION,
                axisValues = mapOf(MotionEvent.AXIS_HAT_X to 1f),
            ),
        )
        now += 20L
        val key = authority.interpret(
            RawInputEvent(
                kind = RawInputEvent.Kind.KEY,
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                down = true,
            ),
        )

        assertNormalized(ControllerAction.NAV_RIGHT, hat)
        assertSwallowed("dual_source_duplicate_direction_NAV_RIGHT", key)
    }

    @Test
    fun sameDirectionAfterCoalesceWindowIsAllowed() {
        val first = authority.interpret(
            RawInputEvent(
                kind = RawInputEvent.Kind.KEY,
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                down = true,
            ),
        )
        now += 80L
        val second = authority.interpret(
            RawInputEvent(
                kind = RawInputEvent.Kind.KEY,
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                down = true,
            ),
        )

        assertNormalized(ControllerAction.NAV_RIGHT, first)
        assertNormalized(ControllerAction.NAV_RIGHT, second)
    }

    @Test
    fun heldHatRepeatsOnlyAtHumanCadence() {
        val tuning = InputTuning(directionalRepeatMs = 220L, directionalRepeatDelayMs = 220L)
        val authority = DefaultInputAuthority(tuning = tuning, clock = { now })

        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(hatX(1f)))
        now = 80L
        assertSwallowed("axis_repeat_throttled_NAV_RIGHT", authority.interpret(hatX(1f)))
        now = 219L
        assertSwallowed("axis_repeat_throttled_NAV_RIGHT", authority.interpret(hatX(1f)))
        now = 220L
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(hatX(1f)))
        now = 440L
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(hatX(1f)))
    }

    @Test
    fun freshHatReversalWinsImmediatelyWithoutANeutralSample() {
        val tuning = InputTuning(directionalRepeatMs = 220L, directionalRepeatDelayMs = 220L)
        val authority = DefaultInputAuthority(tuning = tuning, clock = { now })

        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(hatX(1f)))
        now = 40L
        assertNormalized(ControllerAction.NAV_LEFT, authority.interpret(hatX(-1f)))
        now = 259L
        assertSwallowed("axis_repeat_throttled_NAV_LEFT", authority.interpret(hatX(-1f)))
        now = 260L
        assertNormalized(ControllerAction.NAV_LEFT, authority.interpret(hatX(-1f)))
    }

    @Test
    fun androidHeldDpadRepeatsAtHumanCadenceInsteadOfEveryRawEvent() {
        val tuning = InputTuning(directionalRepeatMs = 220L, directionalRepeatDelayMs = 220L)
        val authority = DefaultInputAuthority(tuning = tuning, clock = { now })

        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight()))

        now = 42L
        assertSwallowed(
            "directional_repeat_throttled_NAV_RIGHT",
            authority.interpret(dpadRight(repeat = 1)),
        )
        now = 91L
        assertSwallowed(
            "directional_repeat_throttled_NAV_RIGHT",
            authority.interpret(dpadRight(repeat = 2)),
        )
        now = 219L
        assertSwallowed(
            "directional_repeat_throttled_NAV_RIGHT",
            authority.interpret(dpadRight(repeat = 3)),
        )
        now = 220L
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight(repeat = 4)))
        now = 439L
        assertSwallowed(
            "directional_repeat_throttled_NAV_RIGHT",
            authority.interpret(dpadRight(repeat = 8)),
        )
        now = 440L
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight(repeat = 9)))
    }

    @Test
    fun freshReversalWinsImmediatelyAndStaleHeldDirectionCannotPingPongFocus() {
        val tuning = InputTuning(directionalRepeatMs = 220L, directionalRepeatDelayMs = 220L)
        val authority = DefaultInputAuthority(tuning = tuning, clock = { now })

        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight()))
        now = 40L
        assertSwallowed(
            "directional_repeat_throttled_NAV_RIGHT",
            authority.interpret(dpadRight(repeat = 1)),
        )

        now = 55L
        assertNormalized(ControllerAction.NAV_LEFT, authority.interpret(dpadLeft()))
        now = 70L
        assertSwallowed(
            "directional_repeat_inactive_NAV_RIGHT",
            authority.interpret(dpadRight(repeat = 2)),
        )
        now = 274L
        assertSwallowed(
            "directional_repeat_throttled_NAV_LEFT",
            authority.interpret(dpadLeft(repeat = 4)),
        )
        now = 275L
        assertNormalized(ControllerAction.NAV_LEFT, authority.interpret(dpadLeft(repeat = 5)))
    }

    @Test
    fun keyRepeatAndRearmedHatTwinsStillEmitOnlyAtRepeatCadence() {
        val tuning = InputTuning(directionalRepeatMs = 220L, directionalRepeatDelayMs = 220L)
        val authority = DefaultInputAuthority(tuning = tuning, clock = { now })

        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight()))
        now = 10L
        assertSwallowed(
            "dual_source_duplicate_direction_NAV_RIGHT",
            authority.interpret(hatX(1f)),
        )
        now = 30L
        assertSwallowed("axis_deadzone_or_not_rearmed", authority.interpret(hatX(0f)))
        now = 40L
        assertSwallowed(
            "directional_repeat_throttled_NAV_RIGHT",
            authority.interpret(dpadRight(repeat = 1)),
        )
        now = 45L
        assertSwallowed(
            "dual_source_duplicate_direction_NAV_RIGHT",
            authority.interpret(hatX(1f)),
        )
        now = 220L
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight(repeat = 5)))
        now = 225L
        assertSwallowed("axis_repeat_throttled_NAV_RIGHT", authority.interpret(hatX(1f)))
    }

    @Test
    fun hatNeutralAndResetBothReleaseTheRepeatStream() {
        val tuning = InputTuning(directionalRepeatMs = 220L, directionalRepeatDelayMs = 220L)
        val authority = DefaultInputAuthority(tuning = tuning, clock = { now })

        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(hatX(1f)))
        now = 20L
        assertSwallowed("axis_deadzone_or_not_rearmed", authority.interpret(hatX(0f)))
        now = 21L
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(hatX(1f)))

        authority.reset()
        now = 22L
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(hatX(1f)))
    }

    @Test
    fun keyUpDisarmsDirectionalRepeatButNeverBlocksTheNextFreshDown() {
        val tuning = InputTuning(directionalRepeatMs = 220L, directionalRepeatDelayMs = 220L)
        val authority = DefaultInputAuthority(tuning = tuning, clock = { now })

        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight()))
        now = 30L
        assertSwallowed("key_up", authority.interpret(dpadRight(down = false)))
        now = 250L
        assertSwallowed(
            "directional_repeat_unarmed_NAV_RIGHT",
            authority.interpret(dpadRight(repeat = 4)),
        )
        now = 300L
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight()))
    }

    @Test
    fun faceButtonRepeatRemainsSwallowedAfterDirectionalRepeatSupport() {
        assertNormalized(
            ControllerAction.CONFIRM,
            authority.interpret(
                RawInputEvent(
                    kind = RawInputEvent.Kind.KEY,
                    keyCode = KeyEvent.KEYCODE_BUTTON_A,
                    down = true,
                ),
            ),
        )
        now = 500L
        assertSwallowed(
            "key_repeat",
            authority.interpret(
                RawInputEvent(
                    kind = RawInputEvent.Kind.KEY,
                    keyCode = KeyEvent.KEYCODE_BUTTON_A,
                    down = true,
                    repeat = 8,
                ),
            ),
        )
    }

    private fun dpadRight(
        down: Boolean = true,
        repeat: Int = 0,
    ) = RawInputEvent(
        kind = RawInputEvent.Kind.KEY,
        keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
        down = down,
        repeat = repeat,
    )

    private fun dpadLeft(
        down: Boolean = true,
        repeat: Int = 0,
    ) = RawInputEvent(
        kind = RawInputEvent.Kind.KEY,
        keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
        down = down,
        repeat = repeat,
    )

    private fun hatX(value: Float) = RawInputEvent(
        kind = RawInputEvent.Kind.MOTION,
        axisValues = mapOf(MotionEvent.AXIS_HAT_X to value),
    )

    private fun assertNormalized(action: ControllerAction, decision: InputDecision) {
        assertTrue(decision is InputDecision.Normalized)
        assertEquals(action, (decision as InputDecision.Normalized).action)
    }

    private fun assertSwallowed(reason: String, decision: InputDecision) {
        assertTrue(decision is InputDecision.Swallowed)
        assertEquals(reason, (decision as InputDecision.Swallowed).reason)
    }

    // DB01 : the DEFAULT tuning is two-phase — the FIRST auto-repeat of a hold waits
    // the deliberate delay (260 ms), later repeats run at the brisk interval (120 ms), and a fresh hold
    // after release re-enters the delay phase so a deliberate tap can never double-step.
    @Test
    fun defaultTuningRepeatsInTwoPhases() {
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight()))
        now = 200L // before the 260 ms first-repeat delay → throttled
        assertSwallowed(
            "directional_repeat_throttled_NAV_RIGHT",
            authority.interpret(dpadRight(repeat = 1)),
        )
        now = 260L // the first repeat lands exactly at the delay
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight(repeat = 2)))
        now = 320L // 60 ms later — under the 120 ms interval → throttled
        assertSwallowed(
            "directional_repeat_throttled_NAV_RIGHT",
            authority.interpret(dpadRight(repeat = 3)),
        )
        now = 380L // 120 ms after the first repeat — brisk cadence flows
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight(repeat = 4)))

        // Release, then press again: the fresh hold re-enters the deliberate delay phase.
        authority.interpret(dpadRight(down = false))
        now = 600L
        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(dpadRight()))
        now = 700L // only 100 ms into the new hold — inside the fresh 260 ms delay
        assertSwallowed(
            "directional_repeat_throttled_NAV_RIGHT",
            authority.interpret(dpadRight(repeat = 1)),
        )
    }
}
