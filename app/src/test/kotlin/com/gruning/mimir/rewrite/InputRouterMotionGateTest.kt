package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.InputAuthority
import com.gruning.mimir.rewrite.core.input.InputDecision
import com.gruning.mimir.rewrite.core.input.RawInputEvent
import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputRouterMotionGateTest {

    @org.junit.Before
    fun fastForwardDirectionalPace() {
        var paceNow = 0L
        com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.clock = { paceNow += 300L; paceNow }
        com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.reset()
    }
    @Test
    fun everyNormalizedControllerActionPulsesWhileDirectionalBurstStaysSeparate() {
        val authority = ScriptedInputAuthority()
        val motionGate = RecordingMotionGate()
        val handled = mutableListOf<ControllerAction>()
        var directionalBursts = 0
        val router = InputRouter(
            interactionRoot = InteractionRoot.PRIMARY,
            inputAuthority = authority,
            onControllerNavigation = { directionalBursts += 1 },
            interactionMotionGate = motionGate,
        ).apply {
            handler = { action ->
                handled += action
                false
            }
        }

        ControllerAction.entries.forEach { action ->
            authority.nextDecision = InputDecision.Normalized(action, reason = "test")
            assertTrue(router.onRawKeyInput(keyCode = 0, down = true))
        }

        assertEquals(ControllerAction.entries, handled)
        assertEquals(
            List(ControllerAction.entries.size) { InteractionSource.CONTROLLER },
            motionGate.pulses,
        )
        assertEquals(ControllerAction.entries.count(ControllerAction::isDirectional), directionalBursts)
    }

    @Test
    fun swallowedAndPassthroughSamplesNeverPulse() {
        val authority = ScriptedInputAuthority()
        val motionGate = RecordingMotionGate()
        val router = InputRouter(interactionRoot = InteractionRoot.PRIMARY, inputAuthority = authority, interactionMotionGate = motionGate)

        listOf("key-up", "repeat", "duplicate", "dead-zone", "text-mode").forEach { reason ->
            authority.nextDecision = InputDecision.Swallowed(reason)
            assertTrue(router.onRawKeyInput(keyCode = 0, down = false))
        }
        authority.nextDecision = InputDecision.Passthrough("not-controller-input")
        assertFalse(router.onRawKeyInput(keyCode = 0, down = true))

        assertEquals(emptyList<InteractionSource>(), motionGate.pulses)
    }

    private class ScriptedInputAuthority : InputAuthority {
        var nextDecision: InputDecision = InputDecision.Passthrough("unset")

        override fun interpret(event: RawInputEvent): InputDecision = nextDecision

        override fun reset() = Unit
    }

    private class RecordingMotionGate : InteractionMotionGate {
        override val phase: StateFlow<AmbientMotionPhase> = MutableStateFlow(AmbientMotionPhase.IDLE)
        val pulses = mutableListOf<InteractionSource>()

        override fun pulse(source: InteractionSource, root: InteractionRoot?) {
            pulses += source
        }

        override fun hold(root: RootLease, source: InteractionSource): InteractionLease =
            error("not used")

        override fun registerRoot(kind: InteractionRoot, initiallyVisible: Boolean): RootLease =
            error("not used")
    }
}
