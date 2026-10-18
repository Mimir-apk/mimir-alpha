package com.gruning.mimir.rewrite.core.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultInputAuthorityHumanMappingTest {
    private data class KeyMapping(
        val label: String,
        val keyCode: Int,
        val action: ControllerAction,
    )

    private data class AxisMapping(
        val label: String,
        val axis: Int,
        val value: Float,
        val action: ControllerAction,
    )

    private data class DirectionMapping(
        val label: String,
        val keyCode: Int,
        val axis: Int,
        val value: Float,
        val action: ControllerAction,
    )

    private val faceButtons = listOf(
        KeyMapping("A", KeyEvent.KEYCODE_BUTTON_A, ControllerAction.CONFIRM),
        KeyMapping("B", KeyEvent.KEYCODE_BUTTON_B, ControllerAction.CANCEL),
        KeyMapping("X", KeyEvent.KEYCODE_BUTTON_X, ControllerAction.SECONDARY),
        KeyMapping("Y", KeyEvent.KEYCODE_BUTTON_Y, ControllerAction.TERTIARY),
    )

    private val shoulders = listOf(
        KeyMapping("L1", KeyEvent.KEYCODE_BUTTON_L1, ControllerAction.PAGE_LEFT),
        KeyMapping("R1", KeyEvent.KEYCODE_BUTTON_R1, ControllerAction.PAGE_RIGHT),
    )

    private val triggerKeys = listOf(
        KeyMapping("L2 key", KeyEvent.KEYCODE_BUTTON_L2, ControllerAction.TRIGGER_LEFT),
        KeyMapping("R2 key", KeyEvent.KEYCODE_BUTTON_R2, ControllerAction.TRIGGER_RIGHT),
    )

    private val systemButtons = listOf(
        KeyMapping("Start", KeyEvent.KEYCODE_BUTTON_START, ControllerAction.SYSTEM_START),
        KeyMapping("Select", KeyEvent.KEYCODE_BUTTON_SELECT, ControllerAction.SYSTEM_SELECT),
    )

    private val directions = listOf(
        DirectionMapping(
            "D-pad up",
            KeyEvent.KEYCODE_DPAD_UP,
            MotionEvent.AXIS_HAT_Y,
            -1f,
            ControllerAction.NAV_UP,
        ),
        DirectionMapping(
            "D-pad down",
            KeyEvent.KEYCODE_DPAD_DOWN,
            MotionEvent.AXIS_HAT_Y,
            1f,
            ControllerAction.NAV_DOWN,
        ),
        DirectionMapping(
            "D-pad left",
            KeyEvent.KEYCODE_DPAD_LEFT,
            MotionEvent.AXIS_HAT_X,
            -1f,
            ControllerAction.NAV_LEFT,
        ),
        DirectionMapping(
            "D-pad right",
            KeyEvent.KEYCODE_DPAD_RIGHT,
            MotionEvent.AXIS_HAT_X,
            1f,
            ControllerAction.NAV_RIGHT,
        ),
    )

    private val triggerAxes = listOf(
        AxisMapping("L2 LTRIGGER axis", MotionEvent.AXIS_LTRIGGER, 1f, ControllerAction.TRIGGER_LEFT),
        AxisMapping("L2 BRAKE alias", MotionEvent.AXIS_BRAKE, 1f, ControllerAction.TRIGGER_LEFT),
        AxisMapping("R2 RTRIGGER axis", MotionEvent.AXIS_RTRIGGER, 1f, ControllerAction.TRIGGER_RIGHT),
        AxisMapping("R2 GAS alias", MotionEvent.AXIS_GAS, 1f, ControllerAction.TRIGGER_RIGHT),
    )

    private val allHumanKeys =
        faceButtons + shoulders + triggerKeys + systemButtons + directions.map {
            KeyMapping(it.label, it.keyCode, it.action)
        }

    @Test
    fun faceButtonDownsMapAtoConfirmBtoCancelXtoSecondaryAndYtoTertiary() {
        assertKeyMappings(faceButtons)
    }

    @Test
    fun shoulderDownsMapL1AndR1ToPageActions() {
        assertKeyMappings(shoulders)
    }

    @Test
    fun startAndSelectDownsMapToTheirSystemActions() {
        assertKeyMappings(systemButtons)
    }

    @Test
    fun dpadKeyDownsMapAllFourHumanDirections() {
        assertKeyMappings(directions.map { KeyMapping(it.label, it.keyCode, it.action) })
    }

    @Test
    fun hatAxesMapNegativeAndPositiveXAndYToAllFourDirections() {
        directions.forEach { mapping ->
            val decision = authority().interpret(motion(mapping.axis, mapping.value))

            assertNormalized(mapping.action, decision, mapping.label)
            assertEquals("${mapping.label} reason", "axis", (decision as InputDecision.Normalized).reason)
        }
    }

    @Test
    fun triggerKeysCanonicalAxesAndBrakeGasAliasesMapToL2AndR2() {
        assertKeyMappings(triggerKeys)
        triggerAxes.forEach { mapping ->
            val decision = authority().interpret(motion(mapping.axis, mapping.value))

            assertNormalized(mapping.action, decision, mapping.label)
            assertEquals("${mapping.label} reason", "axis_trigger", (decision as InputDecision.Normalized).reason)
        }
    }

    @Test
    fun keyUpForEveryHumanControlIsSwallowedAndDoesNotPoisonTheNextDown() {
        allHumanKeys.forEach { mapping ->
            val authority = authority()

            assertSwallowed("key_up", authority.interpret(key(mapping.keyCode, down = false)), "${mapping.label} UP")
            assertNormalized(mapping.action, authority.interpret(key(mapping.keyCode)), "${mapping.label} DOWN after UP")
        }
    }

    @Test
    fun orphanRepeatForEveryHumanControlIsSwallowedAndDoesNotPoisonTheFreshDown() {
        allHumanKeys.forEach { mapping ->
            val authority = authority()

            assertSwallowed(
                if (mapping.action.isDirectional) {
                    "directional_repeat_unarmed_${mapping.action}"
                } else {
                    "key_repeat"
                },
                authority.interpret(key(mapping.keyCode, repeat = 1)),
                "${mapping.label} repeat",
            )
            assertNormalized(
                mapping.action,
                authority.interpret(key(mapping.keyCode)),
                "${mapping.label} fresh DOWN after repeat",
            )
        }
    }

    @Test
    fun keyboardFlaggedTwinWithinWindowIsCoalescedForEveryHumanControl() {
        allHumanKeys.forEach { mapping ->
            var now = 100L
            val authority = authority { now }

            assertNormalized(mapping.action, authority.interpret(key(mapping.keyCode)), "${mapping.label} first source")
            now += 49L
            assertSwallowed(
                "dual_source_duplicate_keycode_${mapping.keyCode}",
                authority.interpret(key(mapping.keyCode, sourceIsKeyboard = true)),
                "${mapping.label} keyboard twin",
            )
        }
    }

    @Test
    fun sameKeycodeAtExactCoalescingBoundaryStartsANewGesture() {
        var now = 100L
        val authority = authority { now }

        assertNormalized(ControllerAction.CONFIRM, authority.interpret(key(KeyEvent.KEYCODE_BUTTON_A)), "first A")
        now += InputTuning().dualSourceCoalesceMs
        assertNormalized(
            ControllerAction.CONFIRM,
            authority.interpret(key(KeyEvent.KEYCODE_BUTTON_A, sourceIsKeyboard = true)),
            "A at exact coalescing boundary",
        )
    }

    @Test
    fun dpadKeyAndHatTwinCoalesceInEitherArrivalOrderForEveryDirection() {
        directions.forEach { mapping ->
            var now = 100L
            var authority = authority { now }

            assertNormalized(mapping.action, authority.interpret(key(mapping.keyCode)), "${mapping.label} key first")
            now += 20L
            assertSwallowed(
                "dual_source_duplicate_direction_${mapping.action}",
                authority.interpret(motion(mapping.axis, mapping.value)),
                "${mapping.label} hat twin second",
            )

            now = 100L
            authority = authority { now }
            assertNormalized(
                mapping.action,
                authority.interpret(motion(mapping.axis, mapping.value)),
                "${mapping.label} hat first",
            )
            now += 20L
            assertSwallowed(
                "dual_source_duplicate_direction_${mapping.action}",
                authority.interpret(key(mapping.keyCode)),
                "${mapping.label} key twin second",
            )
        }
    }

    @Test
    fun heldHatRepeatsAtHumanCadenceAndNeutralStartsAFreshGesture() {
        val tuning = InputTuning(directionalRepeatMs = 220L, directionalRepeatDelayMs = 220L)
        directions.forEach { mapping ->
            var now = 0L
            val authority = authority(tuning) { now }

            assertNormalized(mapping.action, authority.interpret(motion(mapping.axis, mapping.value)), "${mapping.label} first")
            now += 60L
            assertSwallowed(
                "axis_repeat_throttled_${mapping.action}",
                authority.interpret(motion(mapping.axis, mapping.value)),
                "${mapping.label} early held sample",
            )
            now = 219L
            assertSwallowed(
                "axis_repeat_throttled_${mapping.action}",
                authority.interpret(motion(mapping.axis, signed(mapping.value, tuning.axisRelease))),
                "${mapping.label} release boundary remains held",
            )
            now = 220L
            val firstRepeat = authority.interpret(motion(mapping.axis, mapping.value))
            assertNormalized(mapping.action, firstRepeat, "${mapping.label} first cadence repeat")
            assertEquals("${mapping.label} repeat reason", "axis_repeat", (firstRepeat as InputDecision.Normalized).reason)

            now = 439L
            assertSwallowed(
                "axis_repeat_throttled_${mapping.action}",
                authority.interpret(motion(mapping.axis, mapping.value)),
                "${mapping.label} second cadence boundary minus one",
            )
            now = 440L
            assertNormalized(
                mapping.action,
                authority.interpret(motion(mapping.axis, mapping.value)),
                "${mapping.label} second cadence repeat",
            )

            now = 450L
            assertSwallowed(
                "axis_deadzone_or_not_rearmed",
                authority.interpret(motion(mapping.axis, signed(mapping.value, tuning.axisRelease - 0.01f))),
                "${mapping.label} neutral release",
            )
            now = 451L
            assertNormalized(
                mapping.action,
                authority.interpret(motion(mapping.axis, mapping.value)),
                "${mapping.label} fresh press immediately after neutral",
            )
        }
    }

    @Test
    fun keyAndHatShareRepeatOwnershipAcrossARealReversalWithoutDoubleStepping() {
        val tuning = InputTuning(directionalRepeatMs = 220L, directionalRepeatDelayMs = 220L)
        var now = 0L
        val authority = authority(tuning) { now }

        assertNormalized(ControllerAction.NAV_RIGHT, authority.interpret(key(KeyEvent.KEYCODE_DPAD_RIGHT)), "key right")
        now = 10L
        assertSwallowed(
            "dual_source_duplicate_direction_NAV_RIGHT",
            authority.interpret(motion(MotionEvent.AXIS_HAT_X, 1f)),
            "right HAT twin",
        )

        now = 55L
        assertNormalized(ControllerAction.NAV_LEFT, authority.interpret(key(KeyEvent.KEYCODE_DPAD_LEFT)), "fresh key reversal")
        now = 70L
        assertSwallowed(
            "axis_repeat_inactive_NAV_RIGHT",
            authority.interpret(motion(MotionEvent.AXIS_HAT_X, 1f)),
            "stale held HAT cannot retake focus",
        )
        now = 75L
        assertSwallowed(
            "dual_source_duplicate_direction_NAV_LEFT",
            authority.interpret(motion(MotionEvent.AXIS_HAT_X, -1f)),
            "reversed HAT twin joins the key stream",
        )

        now = 274L
        assertSwallowed(
            "directional_repeat_throttled_NAV_LEFT",
            authority.interpret(key(KeyEvent.KEYCODE_DPAD_LEFT, repeat = 1)),
            "shared stream before cadence",
        )
        now = 275L
        assertNormalized(
            ControllerAction.NAV_LEFT,
            authority.interpret(key(KeyEvent.KEYCODE_DPAD_LEFT, repeat = 2)),
            "shared stream at cadence",
        )
        now = 280L
        assertSwallowed(
            "axis_repeat_throttled_NAV_LEFT",
            authority.interpret(motion(MotionEvent.AXIS_HAT_X, -1f)),
            "HAT twin cannot double-step after key repeat",
        )
    }

    @Test
    fun heldAnalogTriggersFireOnceAndRearmAtTheirReleaseThreshold() {
        val tuning = InputTuning()
        triggerAxes.forEach { mapping ->
            var now = 0L
            val authority = authority(tuning) { now }

            assertNormalized(mapping.action, authority.interpret(motion(mapping.axis, mapping.value)), "${mapping.label} first")
            now += 60L
            assertSwallowed(
                "axis_deadzone_or_not_rearmed",
                authority.interpret(motion(mapping.axis, mapping.value)),
                "${mapping.label} held",
            )
            now += 60L
            assertSwallowed(
                "axis_deadzone_or_not_rearmed",
                authority.interpret(motion(mapping.axis, tuning.triggerRelease)),
                "${mapping.label} release",
            )
            now += 60L
            assertNormalized(
                mapping.action,
                authority.interpret(motion(mapping.axis, mapping.value)),
                "${mapping.label} after release",
            )
        }
    }

    @Test
    fun analogTriggersRequireCrossingTheirActivationThreshold() {
        val tuning = InputTuning()
        triggerAxes.forEach { mapping ->
            val authority = authority(tuning)

            assertSwallowed(
                "axis_deadzone_or_not_rearmed",
                authority.interpret(motion(mapping.axis, tuning.triggerActivate)),
                "${mapping.label} at activation threshold",
            )
            assertNormalized(
                mapping.action,
                authority.interpret(motion(mapping.axis, tuning.triggerActivate + 0.01f)),
                "${mapping.label} above activation threshold",
            )
        }
    }

    @Test
    fun triggerKeyAndAxisTwinCoalesceInEitherArrivalOrderForL2AndR2() {
        triggerKeys.zip(triggerAxes.filter { "alias" !in it.label }).forEach { (keyMapping, axisMapping) ->
            var now = 100L
            var authority = authority { now }

            assertNormalized(keyMapping.action, authority.interpret(key(keyMapping.keyCode)), "${keyMapping.label} key first")
            now += 20L
            // TM-2: the key-first ordering is now closed by the union trigger latch rather than by the
            // 50ms cross-source dedupe, so the swallow reason changed. The latch reports the honest
            // reason -- the trigger axis has not re-armed -- and unlike the dedupe it holds at ANY
            // separation, which is what fixed the repeat regression reported on 2026-08-21. Behaviour is unchanged
            // here (still exactly one actuation); only the mechanism that produces it is stronger.
            assertSwallowed(
                "axis_deadzone_or_not_rearmed",
                authority.interpret(motion(axisMapping.axis, axisMapping.value)),
                "${keyMapping.label} axis twin second",
            )

            now = 100L
            authority = authority { now }
            assertNormalized(
                axisMapping.action,
                authority.interpret(motion(axisMapping.axis, axisMapping.value)),
                "${axisMapping.label} first",
            )
            now += 20L
            assertSwallowed(
                "dual_source_duplicate_direction_${keyMapping.action}",
                authority.interpret(key(keyMapping.keyCode, sourceIsKeyboard = true)),
                "${keyMapping.label} key twin second",
            )
        }
    }

    private fun assertKeyMappings(mappings: List<KeyMapping>) {
        mappings.forEach { mapping ->
            val decision = authority().interpret(key(mapping.keyCode))

            assertNormalized(mapping.action, decision, "${mapping.label} DOWN")
            assertEquals("${mapping.label} reason", "key_${mapping.keyCode}", (decision as InputDecision.Normalized).reason)
        }
    }

    private fun authority(
        tuning: InputTuning = InputTuning(),
        clock: () -> Long = { 0L },
    ): DefaultInputAuthority = DefaultInputAuthority(tuning = tuning, clock = clock)

    private fun key(
        keyCode: Int,
        down: Boolean = true,
        repeat: Int = 0,
        sourceIsKeyboard: Boolean = false,
    ) = RawInputEvent(
        kind = RawInputEvent.Kind.KEY,
        keyCode = keyCode,
        down = down,
        repeat = repeat,
        sourceIsKeyboard = sourceIsKeyboard,
    )

    private fun motion(axis: Int, value: Float) = RawInputEvent(
        kind = RawInputEvent.Kind.MOTION,
        axisValues = mapOf(axis to value),
    )

    private fun signed(direction: Float, magnitude: Float): Float = if (direction < 0f) -magnitude else magnitude

    private fun assertNormalized(
        expected: ControllerAction,
        decision: InputDecision,
        context: String,
    ) {
        assertTrue("$context should normalize, but was $decision", decision is InputDecision.Normalized)
        assertEquals(context, expected, (decision as InputDecision.Normalized).action)
    }

    private fun assertSwallowed(
        expectedReason: String,
        decision: InputDecision,
        context: String,
    ) {
        assertTrue("$context should be swallowed, but was $decision", decision is InputDecision.Swallowed)
        assertEquals(context, expectedReason, (decision as InputDecision.Swallowed).reason)
    }
}
