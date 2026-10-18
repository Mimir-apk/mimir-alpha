package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every transition is an unconditional setter, so these assertions hold regardless of the
 * singleton's state entering the test (no reset hook needed — see the plan's §5 note on
 * [MimirAudioFocus] mirroring [com.gruning.mimir.rewrite.BackdropStore]'s object-singleton shape).
 */
class MimirAudioFocusTest {

    @Test
    fun gameLaunchArmedSuspendsFocus() {
        MimirAudioFocus.onGameLaunchArmed()

        assertEquals(AudioFocusState.Suspended, MimirAudioFocus.state.value)
    }

    @Test
    fun returningToMimirRestoresForeground() {
        MimirAudioFocus.onGameLaunchArmed()
        MimirAudioFocus.onReturnedToMimir()

        assertEquals(AudioFocusState.Foreground, MimirAudioFocus.state.value)
    }

    @Test
    fun foregroundedRestoresForeground() {
        MimirAudioFocus.onGameLaunchArmed()
        MimirAudioFocus.onForegrounded()

        assertEquals(AudioFocusState.Foreground, MimirAudioFocus.state.value)
    }
}
