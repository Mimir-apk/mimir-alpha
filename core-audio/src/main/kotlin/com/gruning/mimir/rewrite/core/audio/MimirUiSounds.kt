package com.gruning.mimir.rewrite.core.audio

import android.content.Context

/**
 * Process-wide holder for the single [MimirAudio] engine, so UI call sites in any module can fire a
 * cue without threading a `Context` or an engine instance through their call graph
 * (`MimirAppCoordinator`'s back-dispatch resolver and `HomeShelfCoordinator`'s composables have
 * neither). ONE SoundPool for the whole app, with assets preloaded once.
 *
 * Mirrors the object-singleton shape of [AudioPreferences] and [MimirAudioFocus]. Calls may originate
 * on the main thread, but [MimirAudio] hands every SoundPool operation to its one audio worker.
 *
 * Every [play] is a safe no-op before [install] and after [release] — it never throws, so call sites
 * stay a bare one-liner with no guard of their own.
 */
object MimirUiSounds {
    @Volatile
    private var engine: MimirAudio? = null

    /**
     * Build the one engine and start preloading [soundResources]. Idempotent: a second call (e.g. an
     * Activity recreate re-running `onCreate`) keeps the existing engine — the SoundPool holds the
     * application context, so it survives Activity churn and must not be rebuilt/reloaded per-instance.
     */
    fun install(context: Context, soundResources: Map<MimirSoundEvent, Int>) {
        if (engine == null) {
            engine = MimirAudio(context.applicationContext, soundResources)
        }
    }

    /** Play [event] through the installed engine; a no-op (never throws) if not yet installed. */
    fun play(event: MimirSoundEvent) {
        engine?.play(event)
    }

    /** Whether [install] has run — for diagnostics only. */
    val isInstalled: Boolean
        get() = engine != null

    /** Release the underlying engine. Optional — process death reclaims the SoundPool anyway. */
    fun release() {
        engine?.release()
        engine = null
    }
}
