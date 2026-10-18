package com.gruning.mimir.tweaker

/**
 * Pure recovery outcome reducer. Keeps FGS + journal on unsettled restore; never
 * unconditionally leaveForeground/stopSelf while recovery remains owed.
 */
internal sealed class RecoveryLifecycleDecision {
    data object None : RecoveryLifecycleDecision()
    data object HoldCorrupt : RecoveryLifecycleDecision()
    /** Authenticated Guardian-bound truth is retained; do not spin the legacy restore loop. */
    data object HoldCrossStore : RecoveryLifecycleDecision()
    /** FGS must already be established; schedule/keep bounded retry. */
    data object KeepForegroundRetry : RecoveryLifecycleDecision()
    /** Journal cleared / no active session — safe to leave FGS and stop if requested. */
    data object SettledLeaveForeground : RecoveryLifecycleDecision()
}

internal object TweakerRecoveryLifecycle {
    fun decideAfterPass(
        selection: RecoverySelection,
        restoreResult: TransactionResult?,
    ): RecoveryLifecycleDecision {
        return when (selection) {
            // NONE/empty is settled terminal — leave FGS; never release ownership alone.
            RecoverySelection.NONE -> RecoveryLifecycleDecision.SettledLeaveForeground
            RecoverySelection.HOLD_CORRUPT_JOURNAL -> RecoveryLifecycleDecision.HoldCorrupt
            RecoverySelection.START_FOREGROUND_RECOVERY -> when (restoreResult?.code) {
                "RESTORED", "NO_ACTIVE_SESSION" -> RecoveryLifecycleDecision.SettledLeaveForeground
                "JOURNAL_CORRUPT" -> RecoveryLifecycleDecision.HoldCorrupt
                "CROSS_STORE_RECONCILIATION_REQUIRED", "GUARDIAN_ARM_RETAINED" ->
                    RecoveryLifecycleDecision.HoldCrossStore
                else -> RecoveryLifecycleDecision.KeepForegroundRetry
            }
        }
    }

    /** Retry/pass outcomes that must settle merged ownership and leave FGS. */
    fun requiresTerminalSettlement(decision: RecoveryLifecycleDecision): Boolean =
        decision == RecoveryLifecycleDecision.SettledLeaveForeground ||
            decision == RecoveryLifecycleDecision.None

    /**
     * Null-intent / sticky service restart remains recovery-capable when a journal exists.
     */
    fun selectionForServiceStart(
        intentAction: String?,
        hardwareWritesCompiled: Boolean,
        load: JournalLoadResult,
    ): RecoverySelection {
        val trigger = when (intentAction) {
            null -> RecoveryTrigger.SERVICE_CREATE
            else -> TweakerRecoveryPolicy.triggerForAction(intentAction) ?: RecoveryTrigger.SERVICE_CREATE
        }
        return TweakerRecoveryPolicy.selectRecovery(trigger, hardwareWritesCompiled, load)
    }

    /** Device-protected journal context selection for shared recovery readers. */
    fun journalContextLabel(usesDeviceProtectedStorage: Boolean): String =
        if (usesDeviceProtectedStorage) "device-protected" else "credential-protected"
}
