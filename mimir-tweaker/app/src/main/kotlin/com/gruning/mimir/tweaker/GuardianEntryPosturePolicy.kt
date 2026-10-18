package com.gruning.mimir.tweaker

/**
 * A deliberately unwired, negative admission policy for a future authenticated guardian entry.
 *
 * This policy does not dispatch a runner, create a READY receipt, construct a controller, or
 * authorize hardware I/O. Its two non-inert outcomes name observation only; a future consumer
 * must independently prove authenticated capability and preserve its own fail-closed gate.
 */
internal enum class GuardianEntryPostureDisposition {
    SAFE_STAGE_OBSERVATION_ONLY,
    SAFE_WATCH_OBSERVATION_ONLY,
    INERT,
}

internal object GuardianEntryPosturePolicy {
    fun decide(
        posture: String?,
        mode: RestoreGuardianRunnerMode,
    ): GuardianEntryPostureDisposition = when {
        posture != TweakerPostureTruth.SAFE -> GuardianEntryPostureDisposition.INERT
        mode == RestoreGuardianRunnerMode.STAGE ->
            GuardianEntryPostureDisposition.SAFE_STAGE_OBSERVATION_ONLY
        mode == RestoreGuardianRunnerMode.GUARDIAN_WATCH ->
            GuardianEntryPostureDisposition.SAFE_WATCH_OBSERVATION_ONLY
        else -> GuardianEntryPostureDisposition.INERT
    }
}
