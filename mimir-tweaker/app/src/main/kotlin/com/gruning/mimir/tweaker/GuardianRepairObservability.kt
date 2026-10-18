package com.gruning.mimir.tweaker

import android.util.Log

/**
 * Always-compiled seam for the guardian supervisor's repair reason.
 *
 * [HardwareSessionSupervisor]'s `admitTargetRepair` already computes a precise reason (currently
 * always `TARGET_CONTRACT_DRIFT_CPU`, the sole repairable-drift case) for every
 * [SupervisorDecision.RepairCpuTarget], plus the attempt/settlement [TransactionResult] codes
 * [MimirTweakerService] collects around `supervisor.execute`. Before this, [MimirTweakerService]
 * only logged reason/attempt/settlement under `BuildConfig.DIAGNOSTICS_COMPILED`, which is `false`
 * in Production — so repairs were invisible on a shipping build even though the restore they can
 * eventually cause is not: once the repair budget is exhausted (the same method's
 * `maxTargetRepairAttemptsPerWindow` / `...PerBurst` policy), it returns
 * `SupervisorDecision.Restore(reason = "TARGET_REPAIR_RATE_LIMITED")` instead, which
 * [GuardianRestoreObservability] already surfaces in Production. Without this fix the resulting
 * restore was visible but the repairs that led to it were not — the causal chain was broken.
 * Identical defect class to the restore-reason fix this mirrors; see [GuardianRestoreObservability]
 * for the fuller defect-class writeup (same as Wave 0's scan-skip fix).
 *
 * Emits the reason/attempt/settlement codes plus internal identifiers (generation/transactionId)
 * ONLY — never user data, library content, or paths.
 *
 * [onRepairDecided] takes [diagnosticsCompiled] as a parameter rather than reading
 * `BuildConfig.DIAGNOSTICS_COMPILED` itself, for the same reason as
 * [GuardianRestoreObservability.onRestoreDecided]: the flag is fixed per product flavor at compile
 * time, so a parameter is what lets one JVM test process exercise the Production (`false`) branch.
 *
 * The LAB qualification evidence firing condition, `diagnosticsCompiled && labEvidenceEnabled()`,
 * is the same real-world condition [MimirTweakerService] evaluated before this seam existed: at
 * the one real call site, [labEvidenceEnabled] is always `::labQualificationEvidenceEnabled`,
 * whose own body already requires `BuildConfig.DIAGNOSTICS_COMPILED`. ANDing the same real flag in
 * again here changes nothing about when the line fires in Production — `diagnosticsCompiled` and
 * the flag inside [labEvidenceEnabled] are the same value at the real call site, so the AND is a
 * tautology there. It exists so a host test can independently prove the diagnostics gate wins even
 * when a fake [labEvidenceEnabled] lambda would otherwise say yes — the same proof
 * [GuardianRestoreObservabilityTest] already applies to restores.
 *
 * [lastRecorded] is the test seam: it updates before the guarded [Log] call, so a host test can
 * assert the reason reached this point without depending on `Log` succeeding — wrapped in
 * `runCatching` because this module mixes plain JUnit and Robolectric test classes and a
 * diagnostic call must never throw or change behavior either way.
 */
internal object GuardianRepairObservability {
    private const val TAG = "MimirTweakerService"

    @Volatile
    var lastRecorded: SupervisorDecision.RepairCpuTarget? = null
        private set

    /**
     * Repair-time observability side effects for one observed [decision], plus the
     * [attempt]/[settlement] results [MimirTweakerService] collects around `supervisor.execute`.
     *
     * Always runs, every posture: records [decision] into [lastRecorded] and emits one
     * `runCatching`-guarded log line via [describe].
     *
     * Only when [diagnosticsCompiled] is true AND [labEvidenceEnabled] returns true: the unchanged
     * LAB qualification evidence line, built the same way it always was
     * ([LabQualificationEvidence.targetRepair]) and handed to [logLabEvidence].
     */
    fun onRepairDecided(
        decision: SupervisorDecision.RepairCpuTarget,
        attempt: TransactionResult?,
        settlement: TransactionResult?,
        diagnosticsCompiled: Boolean,
        labEvidenceEnabled: () -> Boolean,
        logLabEvidence: (String) -> Unit,
    ) {
        lastRecorded = decision
        runCatching { Log.i(TAG, describe(decision, attempt, settlement)) }
        if (diagnosticsCompiled && labEvidenceEnabled()) {
            logLabEvidence(LabQualificationEvidence.targetRepair(decision, attempt, settlement))
        }
    }

    /**
     * Pure formatting — reason/attempt/settlement codes plus internal identifiers only.
     * Unit-testable without touching [Log].
     */
    fun describe(
        decision: SupervisorDecision.RepairCpuTarget,
        attempt: TransactionResult?,
        settlement: TransactionResult?,
    ): String =
        "SUPERVISOR repair reason=${decision.reason} " +
            "attempt=${attempt?.code} settlement=${settlement?.code} " +
            "generation=${decision.generation} transactionId=${decision.transactionId}"
}
