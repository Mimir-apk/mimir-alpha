package com.gruning.mimir.rewrite.core.audio

import android.media.AudioManager

/**
 * Whether a phone call currently has audio. Mirrors the existing read-only check at
 * `CompanionReturnActivity.kt:818` / `PrimaryTransferActivity.kt:347` — same signal logic, reused
 * here without editing those files. Never play UI audio while [Interrupted].
 */
enum class CallInterruptionState {
    Idle,
    Interrupted,
}

/** Pure classification of [AudioManager.getMode] — no framework calls, JVM-unit-testable. */
fun callInterruptionStateForMode(mode: Int): CallInterruptionState = when (mode) {
    AudioManager.MODE_RINGTONE,
    AudioManager.MODE_IN_CALL,
    AudioManager.MODE_IN_COMMUNICATION,
    -> CallInterruptionState.Interrupted
    else -> CallInterruptionState.Idle
}
