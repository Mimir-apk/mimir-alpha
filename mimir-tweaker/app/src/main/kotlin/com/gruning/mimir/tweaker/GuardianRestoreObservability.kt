package com.gruning.mimir.tweaker

import android.util.Log

/**
 * Always-compiled seam for the guardian supervisor's restore reason.
 *
 * [HardwareSessionSupervisor.tick] already computes a precise reason (`THERMAL_CPU`, `TARGET_LOSS`,
 * `OWNER_TOKEN_DIED`, missing-channel reasons, ...) for every [SupervisorDecision.Restore]. Before
 * this, [MimirTweakerService] only logged it under `BuildConfig.DIAGNOSTICS_COMPILED`, which is
 * `false` in Production — so a real profile rollback on a shipping build was unattributable from
 * logcat. Same defect class Wave 0 already fixed for scan skips: `ScanSkipLedger` /
 * `LibraryRepositoryImpl.logScanSkipBreakdown` in `core-resolver` kept a computed `SkipReason`
 * instead of discarding it into a bare count. This does the same for the restore reason.
 *
 * Emits the reason string plus internal identifiers (generation/transactionId) ONLY — never a
 * package name, path, or other private-library content. Restores are rare, so there is no log-spam
 * risk and no throttling.
 *
 * [onRestoreDecided] takes [diagnosticsCompiled] as a parameter rather than reading
 * `BuildConfig.DIAGNOSTICS_COMPILED` itself: that flag is fixed per product flavor at compile time,
 * so a parameter is what lets one JVM test process exercise both the Production (`false`) and LAB
 * (`true`) branch of the split below. [MimirTweakerService] passes the real compile-time value in.
 *
 * [lastRecorded] is the test seam: it updates before the guarded [Log] call, so a host test can
 * assert the reason reached this point without depending on `Log` succeeding — the same
 * `runCatching` reasoning as `logScanSkipBreakdown`: this module mixes plain JUnit and Robolectric
 * test classes, and a diagnostic call must never throw or change behavior either way.
 *
 * ## Restore cascades
 * `stopFanControl()` runs on every [SupervisorDecision.Restore] (see the call site in
 * [MimirTweakerService]) and, among other resets, nulls the last fan telemetry sample. If the
 * active session is not cleared immediately, a later tick's `fanControlDecision` can rediscover
 * exactly that self-inflicted null and issue a SECOND restore for a session that is already
 * restored. Observed on-device: a THERMAL_GPU restore, then `FAN_TELEMETRY_UNAVAILABLE` ~4.5s
 * later, same generation and transactionId. That second decision is real (the code path that
 * produces it is untouched by this fix) but its *reason* is a symptom of the first restore, not an
 * independent fault — reporting it as a bare, unlabeled `SUPERVISOR restore reason=...` line
 * invites exactly the wrong read (two independent faults instead of one fault plus its echo).
 *
 * [onRestoreDecided] detects this by comparing the incoming decision's (generation, transactionId)
 * against [lastRecorded] *before* overwriting it (see [cascadeReasonFor]). A match means this
 * decision was computed against a session already recorded as restored; the line is then labelled
 * `cascadeOf=<the reason that already completed the restore>` rather than suppressed — suppressing
 * would risk hiding a genuinely independent second fault on a session that stays active across
 * several ticks. The label chains: if a third restore lands on the same session, its `cascadeOf`
 * names the second restore's reason, not necessarily the first — a human or script can still walk
 * the chain backward through logcat to the original, non-cascade line. No control path changes:
 * `stopFanControl`, `fanControlDecision`'s decisions, and `supervisor.execute` are untouched; this
 * only changes what is logged.
 */
/**
 * a privileged transport dying MUST NOT be silent -- "the profile drops
 * to stock mid-game and the player is told nothing." [GuardianRestoreObservability] already
 * computes and records every restore reason (see [lastRecorded]); this classifies whether one of
 * them means "live telemetry stopped arriving" as opposed to every other restore cause (owner
 * left the game, lease expired, journal drift, ...), so a caller can surface the transport-death
 * case distinctly rather than a generic rollback. Pure and Android-free -- see
 * [HardwareSessionSupervisor.missingChannelRestore] for `MISSING_TELEMETRY_*` and
 * [HardwareSessionSupervisor.evaluateTelemetry]'s `targetUnavailable` branch for
 * `MISSING_TARGET_CONTRACT_TELEMETRY`, the two reason families this recognizes.
 */
internal fun isTelemetryTransportRestoreReason(reason: String): Boolean =
    reason.startsWith("MISSING_TELEMETRY_") || reason == "MISSING_TARGET_CONTRACT_TELEMETRY"

internal object GuardianRestoreObservability {
    private const val TAG = "MimirTweakerService"

    @Volatile
    var lastRecorded: SupervisorDecision.Restore? = null
        private set

    /**
     * Fan-fence fix F4 (reference Odin, 2026-10-14, `build/test-captures/fanfence/ROOT_CAUSE.md`): the one
     * reason the companion bundle reads (`AdvancedControlsPublicBundles.lastRestoreReason`), from
     * EVERY restore family -- the supervisor's ([onRestoreDecided]) and the service's own
     * ([onServiceRestore], when it actually restored). Until now the bundle read [lastRecorded]
     * alone, so a guardian hand-back settled by the recovery pass (`STARTUP_RECOVERY_RESTORE`)
     * reached logcat and never the player: `TweakerBridge: lastRestore=null` while the reference device sat
     * at 103 C on vendor floors. Reason token only; the bundle classifies it
     * (`lastRestoreIsTelemetryTransport`, `lastRestoreIsGuardianHandback`).
     */
    @Volatile
    var lastRestoreReason: String? = null
        private set

    /**
     * P3-6 (fence review, 2026-10-14): the session generation [lastRestoreReason] belongs to, so the
     * companion can key its once-per-incident latch on reason AND generation -- a second hand-back
     * with the same reason is a new incident, not a duplicate. Cleared with the reason by
     * [clearLastRestore] when a new session arms: a re-applied profile ends the incident, and a
     * companion that starts later must not announce it again.
     */
    @Volatile
    var lastRestoreGeneration: Long? = null
        private set

    /** A new Guardian envelope is armed: whatever restore came before is over. */
    fun clearLastRestore() {
        lastRestoreReason = null
        lastRestoreGeneration = null
    }

    /**
     * Non-null only when the restore recorded by the most recent [onRestoreDecided] call was
     * itself determined to be a cascade of the restore recorded just before it — see
     * [cascadeReasonFor]. Test seam, same reasoning as [lastRecorded]: a host test can assert
     * cascade detection directly rather than parsing the logged string.
     */
    @Volatile
    var lastCascadeOfReason: String? = null
        private set

    /**
     * Restore-time observability side effects for one observed [decision].
     *
     * Always runs, every posture: determines whether [decision] is a cascade of [lastRecorded]
     * (via [cascadeReasonFor], evaluated against the *previous* value before it is overwritten),
     * records [decision] into [lastRecorded] and the cascade verdict into [lastCascadeOfReason],
     * and emits one `runCatching`-guarded log line via [describe].
     *
     * Only when [diagnosticsCompiled] is true AND [labEvidenceEnabled] returns true: the unchanged
     * LAB qualification evidence line, built the same way it always was
     * ([LabQualificationEvidence.guardianRestore]) and handed to [logLabEvidence]. This half is
     * unchanged behavior — still diagnostic-only, never fires in Production, and carries the raw
     * [decision] exactly as before (evidence records are not cascade-annotated).
     */
    fun onRestoreDecided(
        decision: SupervisorDecision.Restore,
        diagnosticsCompiled: Boolean,
        labEvidenceEnabled: () -> Boolean,
        logLabEvidence: (String) -> Unit,
    ) {
        val cascadeOfReason = cascadeReasonFor(decision, lastRecorded)
        lastRecorded = decision
        lastRestoreReason = decision.reason
        lastRestoreGeneration = decision.generation
        lastCascadeOfReason = cascadeOfReason
        runCatching { Log.i(TAG, describe(decision, cascadeOfReason)) }
        if (diagnosticsCompiled && labEvidenceEnabled()) {
            logLabEvidence(LabQualificationEvidence.guardianRestore(decision))
        }
    }

    /**
     * The two restores the service performs *itself*, outside the supervisor tick.
     *
     * [onRestoreDecided] above covers exactly one path: the supervisor monitor. It is the only site
     * that calls it. But the service restores the hardware from two more places, and both were
     * completely silent in every posture:
     *
     *  - `OWNER_DEATH_RESTORE` — the owner's binder died, so `onPublicOwnerDeath` restores immediately.
     *  - `STARTUP_RECOVERY_RESTORE` — a restarted service found an APPLIED journal record and its
     *    recovery pass restored Stock. Note what typically *starts* that service: the reborn Mimir's
     *    own first bind.
     *
     * The cost of that silence was a wrong diagnosis. The Odin capture of 2026-08-10T02:43 recorded a
     * profile rollback with no `SUPERVISOR restore reason=` line and concluded, reasonably but
     * incorrectly, that the app must have requested the settlement — because absence of the only
     * restore line in the build was read as absence of a service restore. It is not: two of the three
     * restore families never emitted one. That inference sent a day of work at the wrong mechanism.
     *
     * Deliberately shares the `restore reason=` token with [describe], so one grep
     * (`logcat | grep "restore reason="`) catches every restore the service can perform, while the
     * leading subsystem word says which decided it. Same disclosure rule as above: reason plus
     * internal identifiers only, never a package name or any private-library content. Restores are rare;
     * no throttling needed.
     */
    fun onServiceRestore(
        cause: String,
        generation: Long,
        transactionId: String,
        restored: Boolean,
        code: String,
        journalCause: String? = null,
        settlement: String? = null,
    ) {
        lastServiceRestore = describeServiceRestore(
            cause, generation, transactionId, restored, code, journalCause, settlement,
        )
        // F4: carried into the companion's one reason field only when the hardware actually went
        // back to Stock -- the player copy says "returned to Stock", and a retained hold did not.
        if (restored) {
            lastRestoreReason = cause
            lastRestoreGeneration = generation
        }
        runCatching { Log.i(TAG, lastServiceRestore!!) }
    }

    /** Test seam, mirroring [lastRecorded]: set before the guarded [Log] call. */
    @Volatile
    var lastServiceRestore: String? = null
        private set

    /**
     * Pure formatting, unit-testable without touching [Log].
     *
     * [code] is **required, not defaulted**. Before 2026-08-15 this line carried `restored` and
     * nothing else, so `restored=false` said *that* a restore failed and nothing about *why* — even
     * though `settleOwnerBoundRestore` branches on `result.code` forty lines later. On 2026-08-15
     * that exact gap left the Odin pinned at stock for 25 minutes with the root cause unknowable
     * from logcat: `SERVICE restore reason=OWNER_DEATH_RESTORE generation=249 … restored=false`
     * was the whole record of the incident. A defaulted parameter would have let a future call site
     * silently reopen the gap; a required one makes the compiler ask. The earlier incident showed the same diagnostic gap: absence of a field sent
     * a day of work at the wrong mechanism.
     *
     * [journalCause] is the retained record's persisted [TransactionJournalRecord.lastFailure]:
     * the causal string `TransactionCoordinator.failAndRestore` writes *before* the first rollback
     * command. Passed only where a record is already in hand, so this adds no journal read of its
     * own. Redacted by [redactJournalCause] — see there for why it is not logged verbatim — and
     * omitted entirely when absent, so a line with no `journalCause=` means the journal held no
     * causal detail, not that the field was forgotten.
     *
     * [settlement] is the cross-store adjudication that decided this restore, carried out of
     * `RemoteGuardianProcessAuthority` as `"<disposition>:<appliedStep>:<completed>"` and already
     * present in [TransactionResult.message] — **it was simply never printed.**
     *
     * That omission cost a real incident. On 2026-08-30 the reference Nova came back
     * `restored=false code=CROSS_STORE_RECONCILIATION_REQUIRED`, which maps to
     * `ExplicitRestoreCommit.RetainedHold` — a hold, not a retry, so it does not self-clear — and
     * the daemon could not say WHICH adjudication produced it. The candidates are not
     * interchangeable: `JOURNAL_ONLY_RETAIN` means the guardian arm is gone and restore authority
     * with it; `BLOCKED_ARM_CORRUPT` means it is present and unreadable; `BLOCKED_READY_MISMATCH`
     * means it is present and disagrees. Those are three different repairs. The only route to the
     * answer was `RemoteGuardianProcessAuthority`'s own log, which sits behind
     * `BuildConfig.DIAGNOSTICS_COMPILED` and is therefore **compile-dead in PRODUCTION** — the
     * production posture.
     *
     * This is the same lesson as [code] above, one layer down: the field existed, the call site had
     * it in hand, and the line dropped it. Redacted through [redactJournalCause] for the same
     * reason and omitted when absent, so its absence means no settlement detail was supplied rather
     * than that it was forgotten.
     */
    fun describeServiceRestore(
        cause: String,
        generation: Long,
        transactionId: String,
        restored: Boolean,
        code: String,
        journalCause: String? = null,
        settlement: String? = null,
    ): String {
        val base = "SERVICE restore reason=$cause generation=$generation " +
            "transactionId=$transactionId restored=$restored code=$code"
        val withCause = redactJournalCause(journalCause)?.let { "$base journalCause=$it" } ?: base
        return redactJournalCause(settlement)?.let { "$withCause settlement=$it" } ?: withCause
    }

    /**
     * The branch [ExplicitRestoreGate.afterRestoreAttempt] actually took, for one settled restore.
     *
     * The 2026-08-15 trace could not prove which of `Cleared` / `RetainedHold` / `RetainedRetry`
     * fired — it recorded `RetainedHold` as the leading **[D]** and listed the branch under UNKNOWN —
     * because the three differ only in in-memory side effects and none of them logs. That
     * distinction is not cosmetic: `RetainedHold` takes `cancelRecoveryRetry()` and
     * `enterCrossStoreHold()`, so **nothing on the daemon retries**, while `RetainedRetry` arms a
     * bounded retry. "Is anything still going to try?" is the first question anyone reading this
     * incident asks, and the answer was not in the log.
     *
     * Emitted for every branch including [ExplicitRestoreCommit.Cleared], so a clean settlement is
     * positively attested rather than inferred from silence — silence already meant two different
     * things here once.
     *
     * Shares the `restore ` token with [describeServiceRestore] and [describe] so one grep still
     * catches the whole family, and adds `settle=` as the discriminator.
     */
    fun onRestoreSettled(
        settle: String,
        cause: String,
        generation: Long?,
        transactionId: String?,
        code: String,
        journalPhase: TransactionPhase?,
        journalCause: String?,
    ) {
        lastRestoreSettlement = describeRestoreSettlement(
            settle, cause, generation, transactionId, code, journalPhase, journalCause,
        )
        runCatching { Log.i(TAG, lastRestoreSettlement!!) }
    }

    /** Test seam, mirroring [lastServiceRestore]: set before the guarded [Log] call. */
    @Volatile
    var lastRestoreSettlement: String? = null
        private set

    /** Pure formatting for [onRestoreSettled]. Unit-testable without touching [Log]. */
    fun describeRestoreSettlement(
        settle: String,
        cause: String,
        generation: Long?,
        transactionId: String?,
        code: String,
        journalPhase: TransactionPhase?,
        journalCause: String?,
    ): String {
        val base = "SERVICE restore settle=$settle reason=$cause generation=$generation " +
            "transactionId=$transactionId code=$code journalPhase=${journalPhase?.name ?: "none"}"
        val redacted = redactJournalCause(journalCause)
        return if (redacted == null) base else "$base journalCause=$redacted"
    }

    /**
     * Bounded, space-free, path- and package-free projection of a journal's `lastFailure`.
     *
     * `lastFailure` is the one string that explains a failed restore, and on 2026-08-15 it was
     * sitting on disk in generation 249's record while the incident was diagnosed without it. It is
     * also the only value in this file that is **not** drawn from a closed vocabulary: most of its
     * producers are fixed sentences (`"Readback mismatch for cpu.0"`,
     * `"Failed to persist mutation boundary 3"`), but
     * `TransactionCoordinator`'s `"Mutation exception: ${error.message}"` interpolates an arbitrary
     * `Throwable` message, and a failed sysfs write's message carries the node path. So it cannot
     * be logged verbatim under this file's standing disclosure rule (reason plus internal
     * identifiers only — never a package name, path, or other private-library content).
     *
     * Three transforms, in order, each reversing a specific way the raw string breaks that rule or
     * the log format:
     *  - **absolute paths** (a token starting `/` with a second `/`) → `<path>`. Relative tokens are
     *    left alone: `"Session adoption/monitor failed after APPLIED"` is a fixed sentence, not a path.
     *  - **package-shaped tokens** (three or more dot-separated segments) → `<pkg>`. Mutation
     *    logical IDs such as `cpu.0` have one dot and survive, which is the point — they are the
     *    most useful part of most causes.
     *  - **whitespace → `_`**, then a length cap with a `~trunc` marker. Every other value on these
     *    lines is one space-free token; a value with spaces would break `key=value` parsing for any
     *    reader that splits the line, which is how these lines are meant to be read.
     *
     * Returns null for null/blank so the caller omits the field rather than printing an empty one.
     */
    fun redactJournalCause(raw: String?): String? {
        val collapsed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val redacted = collapsed
            .split(Regex("\\s+"))
            .joinToString("_") { token ->
                when {
                    token.startsWith("/") && token.indexOf('/', 1) >= 0 -> "<path>"
                    PACKAGE_SHAPED.matches(token) -> "<pkg>"
                    else -> token
                }
            }
        return if (redacted.length <= MAX_JOURNAL_CAUSE_CHARS) {
            redacted
        } else {
            redacted.take(MAX_JOURNAL_CAUSE_CHARS) + "~trunc"
        }
    }

    /**
     * Three or more dot-separated identifier segments — `com.gruning.mimir.rewrite`, not `cpu.0`.
     * Trailing punctuation is tolerated so a package at the end of a sentence still matches.
     */
    private val PACKAGE_SHAPED = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){2,}[.,;:]?")

    /** Generous enough for every fixed-sentence cause in `TransactionCoordinator`; bounded anyway. */
    private const val MAX_JOURNAL_CAUSE_CHARS = 160

    /**
     * Pure: null unless [decision] shares both `generation` and `transactionId` with [previous] —
     * i.e. [decision] was computed against a session [previous] already recorded as restored. When
     * non-null, the result is [previous]'s reason. Unit-testable without touching [Log] or the
     * [lastRecorded] singleton.
     */
    fun cascadeReasonFor(
        decision: SupervisorDecision.Restore,
        previous: SupervisorDecision.Restore?,
    ): String? = previous?.reason?.takeIf {
        previous.generation == decision.generation && previous.transactionId == decision.transactionId
    }

    /**
     * Pure formatting — reason + internal identifiers, the drifting pair when the decision carries
     * one, plus an optional `cascadeOf=` label when [cascadeOfReason] is non-null. Unit-testable
     * without touching [Log].
     *
     * `expected=`/`observed=` close TR-02's own last gap. The supervisor began carrying both values
     * on [SupervisorDecision.Restore] in that ticket, but this formatter did not print them, so they
     * reached the tests and never reached a device. Not cosmetic: on 2026-08-22 the Odin
     * livelocked on `TARGET_CONTRACT_DRIFT_GPU_MIN` past generation 1244, and naming the two numbers
     * that differed — a written `3` against a firmware-reset `6` — took a hand-run sysfs poll
     * on the device, because this line said only that *something* had drifted.
     *
     * Both are null for decisions that carry no pair, and the suffix is then omitted entirely
     * rather than printed as `expected=null`.
     */
    fun describe(decision: SupervisorDecision.Restore, cascadeOfReason: String? = null): String {
        val base = "SUPERVISOR restore reason=${decision.reason} " +
            "generation=${decision.generation} transactionId=${decision.transactionId}"
        val drift = if (decision.expected != null || decision.observed != null) {
            " expected=${decision.expected} observed=${decision.observed}"
        } else {
            ""
        }
        return if (cascadeOfReason == null) "$base$drift" else "$base$drift cascadeOf=$cascadeOfReason"
    }
}
