package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirAudioPolicyTest {

    @Test
    fun playsWhenEnabledIdleAndForeground() {
        val decision = MimirAudioPolicy.decide(
            enabled = true,
            volume = 0.6f,
            focus = AudioFocusState.Foreground,
            callState = CallInterruptionState.Idle,
        )

        assertEquals(PlayDecision.Play(0.6f), decision)
    }

    @Test
    fun suppressesWhenDisabled() {
        val decision = MimirAudioPolicy.decide(
            enabled = false,
            volume = 0.6f,
            focus = AudioFocusState.Foreground,
            callState = CallInterruptionState.Idle,
        )

        assertEquals(PlayDecision.Suppress, decision)
    }

    @Test
    fun suppressesWhenCallInterrupted() {
        val decision = MimirAudioPolicy.decide(
            enabled = true,
            volume = 0.6f,
            focus = AudioFocusState.Foreground,
            callState = CallInterruptionState.Interrupted,
        )

        assertEquals(PlayDecision.Suppress, decision)
    }

    @Test
    fun suppressesWhenFocusSuspendedForGameLaunch() {
        val decision = MimirAudioPolicy.decide(
            enabled = true,
            volume = 0.6f,
            focus = AudioFocusState.Suspended,
            callState = CallInterruptionState.Idle,
        )

        assertEquals(PlayDecision.Suppress, decision)
    }

    @Test
    fun stackedSuppressionReasonsStillSuppress() {
        val decision = MimirAudioPolicy.decide(
            enabled = false,
            volume = 0.6f,
            focus = AudioFocusState.Suspended,
            callState = CallInterruptionState.Interrupted,
        )

        assertEquals(PlayDecision.Suppress, decision)
    }

    @Test
    fun volumeIsClampedIntoValidRange() {
        val tooLoud = MimirAudioPolicy.decide(
            enabled = true,
            volume = 1.5f,
            focus = AudioFocusState.Foreground,
            callState = CallInterruptionState.Idle,
        )
        val negative = MimirAudioPolicy.decide(
            enabled = true,
            volume = -0.2f,
            focus = AudioFocusState.Foreground,
            callState = CallInterruptionState.Idle,
        )

        assertEquals(PlayDecision.Play(1f), tooLoud)
        assertEquals(PlayDecision.Play(0f), negative)
        assertTrue((tooLoud as PlayDecision.Play).volume <= 1f)
    }
}
