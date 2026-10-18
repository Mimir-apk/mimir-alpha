package com.gruning.mimir.rewrite.core.audio

/**
 *  the pure decision for the BACKDROP MEDIA BED:
 * the focused game's preview clip playing its OWN audio track behind the UI (the track arrives WITH the
 * clip download; there is no separate audio fetch). Mirrors [MimirAudioPolicy]'s shape: pure inputs, no
 * Android types, JVM-unit-tested; the plumbing around it only needs a device smoke test.
 *
 * The bed is AMBIENT audio, so its governance is stricter than a UI cue's:
 *  - [enabled]/[mix] — the persisted preview-audio preference ([AudioPreferences]); mix is RELATIVE
 *    (the system Media volume row stays the master — bed and cues both ride USAGE_MEDIA).
 *  - [focus] — [AudioFocusState.Suspended] (a game session armed/running) silences the bed exactly like
 *    the cues; games own audio (charter §3 item 4).
 *  - [environmentBlocked] — the bind-time COURTESY gate ([MimirMediaBed]): when ANOTHER app is already
 *    playing music, or a call is live, the bed stays silent rather than fighting it. Mimir takes no
 *    system audio focus for an ambient bed — silence is politer than ducking someone's podcast. (A call
 *    arriving MID-playback is handled by the backdrop's lifecycle pause, not this gate.)
 *
 * Returns the bed's target volume (0f = silent). The VIDEO is never gated here — a muted bed still shows
 * motion; only the audio lane is decided.
 */
object MediaBedPolicy {
    fun bedVolume(
        enabled: Boolean,
        mix: Float,
        focus: AudioFocusState,
        environmentBlocked: Boolean,
    ): Float {
        if (!enabled) return 0f
        if (focus is AudioFocusState.Suspended) return 0f
        if (environmentBlocked) return 0f
        return mix.coerceIn(0f, 1f)
    }
}
