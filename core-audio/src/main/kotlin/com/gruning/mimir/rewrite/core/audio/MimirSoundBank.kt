package com.gruning.mimir.rewrite.core.audio

/**
 * The UI sound bank's actual `res/raw` resource wiring, isolated in this module so callers
 * (`MainActivity`, coordinators, etc.) never need a second edit when assets change — only this map does.
 *
 * Assets are CC0 (Kenney "Interface Sounds", CC0 1.0) — see `core-audio/PROVENANCE.md` for the
 * per-asset receipt (source, license snapshot, fetch date, exact edits). Bundled in the APK; decoded
 * offline by [MimirAudio]'s SoundPool — no runtime fetch (local-only posture).
 *
 * `ContextSwitch` and `PageTrigger` intentionally point at the **same** `ui_context` sample: keep both events distinct in the enum, sharing one cue until a real page-turn consumer diverges.
 * A [MimirSoundEvent] absent from this map stays a safe no-op in [MimirAudio.play].
 */
object MimirSoundBank {
    val resources: Map<MimirSoundEvent, Int> = mapOf(
        MimirSoundEvent.NavFocusMove to R.raw.ui_nav_tick,
        MimirSoundEvent.BackCancel to R.raw.ui_back,
        MimirSoundEvent.ConfirmLaunch to R.raw.ui_confirm,
        MimirSoundEvent.ContextSwitch to R.raw.ui_context,
        MimirSoundEvent.PageTrigger to R.raw.ui_context,
        MimirSoundEvent.Toggle to R.raw.ui_toggle,
        MimirSoundEvent.Error to R.raw.ui_error,
    )
}
