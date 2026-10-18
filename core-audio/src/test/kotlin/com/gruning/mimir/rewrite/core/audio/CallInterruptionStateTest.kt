package com.gruning.mimir.rewrite.core.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class CallInterruptionStateTest {

    @Test
    fun ringtoneModeIsInterrupted() {
        assertEquals(
            CallInterruptionState.Interrupted,
            callInterruptionStateForMode(AudioManager.MODE_RINGTONE),
        )
    }

    @Test
    fun inCallModeIsInterrupted() {
        assertEquals(
            CallInterruptionState.Interrupted,
            callInterruptionStateForMode(AudioManager.MODE_IN_CALL),
        )
    }

    @Test
    fun inCommunicationModeIsInterrupted() {
        assertEquals(
            CallInterruptionState.Interrupted,
            callInterruptionStateForMode(AudioManager.MODE_IN_COMMUNICATION),
        )
    }

    @Test
    fun normalModeIsIdle() {
        assertEquals(
            CallInterruptionState.Idle,
            callInterruptionStateForMode(AudioManager.MODE_NORMAL),
        )
    }
}
