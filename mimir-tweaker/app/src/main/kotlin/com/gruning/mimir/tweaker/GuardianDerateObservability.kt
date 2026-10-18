package com.gruning.mimir.tweaker

import android.util.Log

/**
 * Always-compiled seam for the guardian supervisor's thermal DERATE reason, ladder position, and
 * (WAVE2 Stage B) actuation outcome.
 *
 * Same defect class as [GuardianRestoreObservability] and [GuardianRepairObservability]:
 * [HardwareSessionSupervisor] computes a precise reason (`THERMAL_ANDROID` / `THERMAL_CPU` /
 * `THERMAL_GPU` / `THERMAL_BATTERY`) and an exact ladder rung for every
 * [SupervisorDecision.Derate]; without this seam that information (and, since Stage B, whether
 * the rung was actually signed and written) would be silently discarded.
 *
 * Stage A left the decision UNACTUATED, so a device capture showing unchanged
 * `policy6`/`max_pwrlevel` was the expected result of "no sanctioned write path exists yet", not
 * evidence of failure. Stage B actuates it (`HardwareSessionSupervisor.execute`'s `Derate` branch,
 * `TransactionCoordinator.applyDerate`), so [attempt] now carries the real settlement -- signed
 * and applied, signed but the write failed, or not signed at all ("remain", never `Restore`) --
 * and [describe] reports it truthfully instead of a hardcoded claim.
 *
 * Emits the reason, rung index, rung count, and outcome code plus internal identifiers
 * (generation / transactionId) ONLY — never user data, library content, or paths.
 *
 * [onDerateDecided] takes [diagnosticsCompiled] as a parameter rather than reading
 * `BuildConfig.DIAGNOSTICS_COMPILED` itself, for the same reason as the two seams it mirrors: the
 * flag is fixed per product flavor at compile time, so a parameter is what lets one JVM test
 * process exercise the Production (`false`) branch.
 *
 * [lastRecorded] is the test seam: it updates before the guarded [Log] call, so a host test can
 * assert the decision reached this point without depending on `Log` succeeding — wrapped in
 * `runCatching` because a diagnostic call must never throw or change behavior.
 */
/**
 * One derate that reached hardware. Carries what the player has to be told and nothing more.
 */
internal data class ActuatedDerate(
    val reason: String,
    /** 1-based resulting position on the ladder. */
    val rung: Int,
    /** The ladder's depth, NOT the steps moved by the decision that produced this. */
    val maxRung: Int,
    /** The NOMINAL profile id. A derate never renames the session's profile. */
    val profileId: String,
    val generation: Long,
    val transactionId: String,
    val atEpochMs: Long,
)

/**
 * Does [last] describe the session that is live right now?
 *
 * Gated daemon-side, on purpose: a derate from a session that has since ended must never decorate
 * the row of the one that replaced it, and deciding that here keeps the client trivial — the block
 * simply does not cross the wire.
 */
internal fun activeDerateForSession(
    last: ActuatedDerate?,
    active: ActiveSessionIdentity?,
): ActuatedDerate? = last?.takeIf {
    active != null && active.generation == it.generation && active.transactionId == it.transactionId
}

internal object GuardianDerateObservability {
    private const val TAG = "MimirTweakerService"

    /** Seams, so the holder is host-testable without Android or a live ladder. */
    internal var ladderMaxRung: Int = ThermalDerateLadder.maxRung
    internal var nowEpochMs: () -> Long = { System.currentTimeMillis() }

    @Volatile
    var lastRecorded: SupervisorDecision.Derate? = null
        private set

    /**
     * The last derate that actually MOVED HARDWARE, as opposed to the last one decided.
     *
     * **Why this is not [lastRecorded].** [lastRecorded] holds every decision, including the ones
     * that changed nothing: an unsigned rung degrades to "remain" (`THERMAL_DERATE_UNSIGNED_RUNG`)
     * and the ceilings stay exactly where they were. Publishing a reduced-caps claim off that would
     * tell the player their clocks had been cut when they had not — the same class of lie this
     * whole legibility landing exists to remove, pointing the other way.
     *
     * After reported `performance` clock loss mid-game, the Utility row continued
     * to show `performance` at its nominal 3.07 GHz caps, without explaining the reduction
     * or that the clocks would not return during that session. This holder is the truth that reaches the row.
     */
    @Volatile
    var lastActuated: ActuatedDerate? = null
        private set

    /**
     * Derate-time observability for one observed [decision] and its actuation [attempt].
     *
     * Always runs, every posture: records [decision] into [lastRecorded] and emits one
     * `runCatching`-guarded log line via [describe]. Only when [diagnosticsCompiled] is true AND
     * [labEvidenceEnabled] returns true does the additional LAB qualification evidence line fire.
     */
    /** Test seam only: clears both holders so a case starts from a known state. */
    internal fun reset() {
        lastRecorded = null
        lastActuated = null
    }

    fun onDerateDecided(
        decision: SupervisorDecision.Derate,
        attempt: TransactionResult?,
        diagnosticsCompiled: Boolean,
        labEvidenceEnabled: () -> Boolean,
        logLabEvidence: (String) -> Unit,
    ) {
        lastRecorded = decision
        // Actuated ONLY. A later unactuated attempt -- unsigned rung, lost fence, rejected context
        // -- must neither overwrite nor CLEAR this: the hardware is still derated, and forgetting
        // that would put the nominal caps back on the row while the clocks stayed down.
        if (attempt?.success == true && attempt.applied) {
            lastActuated = ActuatedDerate(
                reason = decision.reason,
                rung = decision.rung,
                // `decision.rungs` is steps-moved-by-THIS-decision, not the ladder's depth. Reading
                // it as the denominator would print "rung 1 of 1" on a three-rung ladder.
                maxRung = ladderMaxRung,
                profileId = attempt.plan?.profileId.orEmpty(),
                generation = decision.generation,
                transactionId = decision.transactionId,
                atEpochMs = nowEpochMs(),
            )
        }
        runCatching { Log.i(TAG, describe(decision, attempt)) }
        if (diagnosticsCompiled && labEvidenceEnabled()) {
            runCatching { logLabEvidence(describe(decision, attempt)) }
        }
    }

    /**
     * Stable, greppable single line. `rung` is 1-based for humans; `rung=0` means the ladder
     * returned to nominal. `actuated=true` only when [attempt] both succeeded and wrote hardware
     * (`TransactionCoordinator.THERMAL_DERATE_APPLIED`); `actuated=false` covers every other
     * outcome, including an unsigned rung deliberately left at "remain" (spec S3.1/S5: never
     * `Restore`) -- `attemptCode` distinguishes exactly which.
     */
    fun describe(decision: SupervisorDecision.Derate, attempt: TransactionResult?): String {
        val actuated = attempt?.success == true && attempt.applied
        return "SUPERVISOR derate reason=${decision.reason}" +
            " rung=${decision.rung}/${decision.rungs}" +
            " generation=${decision.generation}" +
            " transactionId=${decision.transactionId}" +
            " actuated=$actuated" +
            " attemptCode=${attempt?.code}" +
            failureDetail(actuated, attempt)
    }

    /**
     * `attemptCode` alone is ambiguous on failure: THERMAL_DERATE_CONTEXT_REJECTED covers a dozen
     * distinct admission checks, and THERMAL_DERATE_JOURNAL_PERSIST_FAILED covers the projection
     * guard, the keystore signing path and a file I/O throw. Both codes cost real diagnosis time
     * on device on 2026-08-09, because `applyDerate` builds an exact reason string and then only
     * the code survived to the log. Appended on failure only, so the success line stays byte-stable
     * for anyone grepping it, and flattened so one event is always one line.
     */
    private fun failureDetail(actuated: Boolean, attempt: TransactionResult?): String {
        if (actuated) return ""
        val detail = attempt?.message?.takeIf { it.isNotBlank() } ?: return ""
        val flattened = detail.replace(Regex("\\s+"), " ").replace('"', '\'').trim()
        return " detail=\"$flattened\""
    }
}
