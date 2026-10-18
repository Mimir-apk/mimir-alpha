package com.gruning.mimir.rewrite.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Mimir currently owns audio focus for its own UI sounds.
 *
 * [Suspended] is the emulator-launch state because games own audio:
 * Mimir abandons focus and stays silent while one runs.
 *
 * **This KDoc used to end "nothing calls them from production code yet". That has been false for some
 * time and is corrected here (QA-20 investigation, 2026-09-16), because it is the kind of stale sentence
 * that sends the next reader looking for an unwired state machine instead of at the wiring.** The
 * transitions have four production call sites:
 *
 *  - [onGameLaunchArmed] — `LaunchController`, as the injected `gameLaunchArmed` default (its comment pins the
 *    ordering against the launch sound), and `MainActivity.onStop`, when Main stops under a parked game being
 *    fronted again (the Bottom's Return, HOME's Resume) or while a Main lease stands (Android's Recents fronts the game
 *    with no restoring mark): the game is in front, as at a launch.
 *  - [onReturnedToMimir] — `MainActivity.onResume`, on EVERY resume of Main (a game parked behind Main by HOME or by
 *    the switch button is not on screen, so it does not hold Mimir silent; the three teardown calls beside it
 *    stay behind `!parkedMainGame`), `MainActivity`'s game-dispatch watchdog cleanup, and
 *    `CompanionProcessRuntime.settleBottomGameCleanup` (bottom-screen game cleanup, checked on exact operation
 *    currency so a stale game's cleanup cannot restore audio for a newer one).
 *
 * Three pure policies read the state and all three silence on [Suspended]: [MimirAudioPolicy] for UI
 * sounds, [FrontendMusicPolicy] for frontend music, [MediaBedPolicy] for the media bed.
 */
sealed interface AudioFocusState {
    data object Foreground : AudioFocusState
    data object Suspended : AudioFocusState
}

/**
 * Runtime holder for [AudioFocusState] — mirrors [com.gruning.mimir.rewrite.BackdropStore]'s
 * `object` + `MutableStateFlow` shape (load-once singleton, synchronous transition writes).
 * Starts [AudioFocusState.Foreground]: Mimir owns focus until something explicitly suspends it.
 */
object MimirAudioFocus {
    private val _state = MutableStateFlow<AudioFocusState>(AudioFocusState.Foreground)
    val state: StateFlow<AudioFocusState> = _state.asStateFlow()

    /** Mimir is the foreground app and may play its own UI sounds. */
    fun onForegrounded() {
        _state.value = AudioFocusState.Foreground
    }

    /** An emulator launch has been armed — abandon focus, stay silent until [onReturnedToMimir]. */
    fun onGameLaunchArmed() {
        _state.value = AudioFocusState.Suspended
    }

    /** Control returned to Mimir after a game/emulator session ended. */
    fun onReturnedToMimir() {
        _state.value = AudioFocusState.Foreground
    }
}
