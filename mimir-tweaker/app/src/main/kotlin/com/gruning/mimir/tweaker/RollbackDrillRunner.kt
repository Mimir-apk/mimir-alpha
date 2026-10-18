package com.gruning.mimir.tweaker

internal data class RollbackDrillResult(
    val success: Boolean,
    val attempts: Int,
    val successes: Int,
    val code: String,
    val message: String,
    val settlementCode: String? = null,
    val observedSettlementWindowMs: Long = 0L,
    val settlementCleanRounds: Int = 0,
    val minimumCleanSettlementWindowMs: Long = 0L,
)

/** Typed outcome of the retired lab safety-evidence Binder path. */
internal data class LabEvidenceResult(
    val success: Boolean,
    val code: String,
    val message: String,
)

internal class RollbackDrillRunner(
    private val transaction: TransactionCoordinator,
    private val capabilities: () -> HardwareCapabilities,
    private val planner: TuningPlanner,
    @Suppress("UNUSED_PARAMETER") private val calibrationStore: CalibrationReportStore,
    private val hardwareWritesCompiled: Boolean,
    private val diagnosticsCompiled: Boolean = hardwareWritesCompiled,
) {
    fun run(profileId: String, packageName: String, drillCount: Int): RollbackDrillResult {
        if (!hardwareWritesCompiled || !diagnosticsCompiled) {
            return RollbackDrillResult(
                success = false,
                attempts = 0,
                successes = 0,
                code = "WRITE_GATE_DISABLED",
                message = "Rollback drills require the lab diagnostic posture.",
            )
        }
        if (drillCount < 3) {
            return RollbackDrillResult(
                success = false,
                attempts = 0,
                successes = 0,
                code = "INVALID_DRILL_COUNT",
                message = "At least three rollback drills are required.",
            )
        }
        planner.plan(profileId, packageName, capabilities())
            ?: return RollbackDrillResult(false, 0, 0, "UNKNOWN_PROFILE", "Unknown profile: $profileId")
        var successes = 0
        var observedSettlementWindowMs = 0L
        var lastSettlementCode: String? = null
        var lastSettlementCleanRounds = 0
        var minimumCleanSettlementWindowMs: Long? = null
        var lastRestore: TransactionResult? = null
        repeat(drillCount) { index ->
            val nextCapabilities = capabilities()
            val nextPlan = planner.plan(profileId, packageName, nextCapabilities)
                ?: return RollbackDrillResult(
                    success = false,
                    attempts = index,
                    successes = successes,
                    code = "UNKNOWN_PROFILE",
                    message = "Profile became unavailable before drill ${index + 1}: $profileId",
                    settlementCode = lastSettlementCode,
                    observedSettlementWindowMs = observedSettlementWindowMs,
                    settlementCleanRounds = lastSettlementCleanRounds,
                    minimumCleanSettlementWindowMs = minimumCleanSettlementWindowMs ?: 0L,
                )
            if (index > 0) {
                val restored = lastRestore
                val generation = restored?.generation
                val transactionId = restored?.transactionId
                if (generation == null || transactionId == null) {
                    return RollbackDrillResult(
                        success = false,
                        attempts = index,
                        successes = successes,
                        code = "STALE_GENERATION",
                        message = "The prior verified restore did not return an exact transaction identity.",
                        settlementCode = "STALE_GENERATION",
                        observedSettlementWindowMs = observedSettlementWindowMs,
                        settlementCleanRounds = lastSettlementCleanRounds,
                        minimumCleanSettlementWindowMs = minimumCleanSettlementWindowMs ?: 0L,
                    )
                }
                val settlement = transaction.observeRestoredStockSettlement(
                    expectedGeneration = generation,
                    expectedTransactionId = transactionId,
                    nextPlan = nextPlan,
                )
                observedSettlementWindowMs += settlement.observedWindowMs
                lastSettlementCode = settlement.code
                lastSettlementCleanRounds = settlement.consecutiveCleanRounds
                if (!settlement.settled) {
                    return RollbackDrillResult(
                        success = false,
                        attempts = index,
                        successes = successes,
                        code = settlement.code,
                        message = "Next drill blocked before mutation: ${settlement.message}",
                        settlementCode = settlement.code,
                        observedSettlementWindowMs = observedSettlementWindowMs,
                        settlementCleanRounds = settlement.consecutiveCleanRounds,
                        minimumCleanSettlementWindowMs = minimumCleanSettlementWindowMs ?: 0L,
                    )
                }
                minimumCleanSettlementWindowMs = minOf(
                    minimumCleanSettlementWindowMs ?: Long.MAX_VALUE,
                    settlement.consecutiveCleanWindowMs,
                )
            }
            val applied = transaction.apply(profileId, packageName, nextCapabilities, dryRun = false)
            if (!applied.applied) {
                // apply may already have performed and proven its exact restore. A
                // second restore would truthfully report NO_ACTIVE_SESSION, not a
                // cleanup failure. Otherwise admit only an explicit restored or
                // proven-no-active-session cleanup outcome.
                val cleanup = if (applied.restored) null else transaction.restore()
                val cleanupProven = applied.restored || cleanup?.restored == true ||
                    (cleanup?.success == true && cleanup.code == "NO_ACTIVE_SESSION")
                return RollbackDrillResult(
                    success = false,
                    attempts = index + 1,
                    successes = successes,
                    code = if (cleanupProven) applied.code else "RESTORE_FAILED",
                    message = if (cleanupProven) {
                        "Apply failed during drill ${index + 1}: ${applied.message}"
                    } else {
                        "Apply failed during drill ${index + 1}; cleanup restore also failed: ${cleanup?.message}"
                    },
                    settlementCode = lastSettlementCode,
                    observedSettlementWindowMs = observedSettlementWindowMs,
                    settlementCleanRounds = lastSettlementCleanRounds,
                    minimumCleanSettlementWindowMs = minimumCleanSettlementWindowMs ?: 0L,
                )
            }
            val restored = transaction.restore()
            if (!restored.restored) {
                return RollbackDrillResult(
                    success = false,
                    attempts = index + 1,
                    successes = successes,
                    code = "RESTORE_FAILED",
                    message = "Restore failed during drill ${index + 1}: ${restored.message}",
                    settlementCode = lastSettlementCode,
                    observedSettlementWindowMs = observedSettlementWindowMs,
                    settlementCleanRounds = lastSettlementCleanRounds,
                    minimumCleanSettlementWindowMs = minimumCleanSettlementWindowMs ?: 0L,
                )
            }
            lastRestore = restored
            successes++
        }
        val success = successes == drillCount
        // TW04-A: drills may report mechanically observed attempts; they must not promote calibration.
        return RollbackDrillResult(
            success = success,
            attempts = drillCount,
            successes = successes,
            code = if (success) "DRILLS_PASSED" else "DRILLS_FAILED",
            message = if (success) {
                "All rollback drills restored and verified."
            } else {
                "Only $successes of $drillCount rollback drills succeeded."
            },
            settlementCode = lastSettlementCode,
            observedSettlementWindowMs = observedSettlementWindowMs,
            settlementCleanRounds = lastSettlementCleanRounds,
            minimumCleanSettlementWindowMs = minimumCleanSettlementWindowMs ?: 0L,
        )
    }

    /**
     * Retired. Caller-supplied booleans/counters must never mutate or certify a calibration report.
     * AIDL keeps the method for source compatibility; the implementation is always a truthful refusal.
     */
    fun recordLabEvidence(
        @Suppress("UNUSED_PARAMETER") writesExercised: Boolean,
        @Suppress("UNUSED_PARAMETER") rollbackAttempts: Int,
        @Suppress("UNUSED_PARAMETER") rollbackSuccesses: Int,
        @Suppress("UNUSED_PARAMETER") rebootRestoreVerified: Boolean,
    ): LabEvidenceResult = LabEvidenceResult(
        success = false,
        code = "LAB_EVIDENCE_RETIRED",
        message = "Caller-attested lab safety evidence can no longer modify or certify a report.",
    )
}
