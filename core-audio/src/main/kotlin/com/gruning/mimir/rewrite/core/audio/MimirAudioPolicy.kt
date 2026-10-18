package com.gruning.mimir.rewrite.core.audio

/** What [MimirAudio] should do about a requested [MimirSoundEvent]. */
sealed interface PlayDecision {
    data class Play(val volume: Float) : PlayDecision
    data object Suppress : PlayDecision
}

/**
 * The pure suppression gate — no Android framework types in the signature, so this is the
 * JVM-unit-test surface (mirrors the FUX-F backdrop-request-reducer pattern: the reducer is tested
 * exhaustively, the real SoundPool plumbing around it only needs a device smoke test).
 *
 * Phase 1 has one binary focus state (see [AudioFocusState]), so this is a suppress/play gate, not a
 * duck/attenuate one — nothing in Phase 1 needs a partial-volume state.
 */
object MimirAudioPolicy {
    fun decide(
        enabled: Boolean,
        volume: Float,
        focus: AudioFocusState,
        callState: CallInterruptionState,
    ): PlayDecision {
        if (!enabled) return PlayDecision.Suppress
        if (callState == CallInterruptionState.Interrupted) return PlayDecision.Suppress
        if (focus is AudioFocusState.Suspended) return PlayDecision.Suppress
        return PlayDecision.Play(volume.coerceIn(0f, 1f))
    }
}
