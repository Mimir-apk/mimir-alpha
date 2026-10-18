package com.gruning.mimir.rewrite.core.audio

/**
 * The Phase 1 UI sound bank (charter §3 item 2) — one signature set, no per-theme variants
 * . Each event maps 1:1 to a preloaded SoundPool sample in [MimirAudio].
 */
enum class MimirSoundEvent {
    NavFocusMove,
    ContextSwitch,
    ConfirmLaunch,
    BackCancel,
    Toggle,
    Error,
    PageTrigger,
}
