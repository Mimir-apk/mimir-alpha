package com.gruning.mimir.tweaker

/**
 * Pure crash-replay adjudication for the independently authenticated journal and arm stores.
 *
 * This deliberately returns no command and performs no cleanup. A caller may only use a future,
 * separately admitted execution seam after it has acted on one of these fail-closed dispositions.
 */
internal enum class GuardianTransactionReconciliation {
    NO_RECORDS_RETAIN,
    LEGACY_RETAIN,
    JOURNAL_ONLY_RETAIN,
    ARMING_RETAIN,
    READY_RETAIN,
    SETTLED_RETAIN,
    BLOCKED_JOURNAL_CORRUPT,
    BLOCKED_ARM_CORRUPT,
    BLOCKED_MISSING_BINDING,
    BLOCKED_IDENTITY_MISMATCH,
    BLOCKED_PHASE_MISMATCH,
    BLOCKED_BOUNDARY_MISMATCH,
    BLOCKED_READY_MISMATCH,
    BLOCKED_MISSING_TERMINAL_RECEIPT,
    BLOCKED_TERMINAL_MISMATCH,
}

/**
 * The one idempotent step a schema-v5 crash boundary admits, proven from durable cross-store
 * evidence alone. Every step is zero-mutation: none issues a hardware command, and repeating any
 * of them against the state it was derived from is a no-op. Naming a step grants nothing by
 * itself — a separately admitted execution seam still owns whether and how it runs.
 */
internal enum class GuardianTransactionStep {
    /**
     * Journal is PREPARED/ARMING with no Guardian counterpart and no completed boundary:
     * nothing was armed and nothing was mutated, so the journal record may be discarded.
     */
    DISCARD_UNMUTATED_JOURNAL,
    /**
     * The arm store reached durable READY before the journal appended its READY digest.
     * Appending that exact session digest is append-only and repeatable.
     */
    APPEND_JOURNAL_READY,
    /**
     * Authenticated, chained Stock-confirmed + EXITED receipts are durable while the journal has
     * not yet recorded the terminal phase. The guardian has exited; only journal phase/evidence
     * writes remain, and they must follow the existing monotonic v5 phase rule.
     */
    ADVANCE_JOURNAL_SETTLED,
    /**
     * Journal is GUARDIAN_SETTLED against the exact terminal receipt already recorded:
     * Guardian-first cleanup may clear the arm store.
     */
    CLEAR_GUARDIAN_ARM,
    /**
     * Journal is GUARDIAN_SETTLED and the Guardian counterpart is already gone:
     * the journal-last clear is the remaining owed step.
     */
    CLEAR_SETTLED_JOURNAL,
}

/**
 * Typed crash-boundary outcome. [disposition] keeps the exact fail-closed classification that
 * [GuardianTransactionReconciler.reconcile] has always returned; it never grants authority on its
 * own. Only [Resume] names an admissible step, and only when durable evidence proves it.
 */
internal sealed interface GuardianTransactionOutcome {
    val disposition: GuardianTransactionReconciliation

    /** Both stores are retained exactly as they are; durable evidence admits no step. */
    data class Retain(override val disposition: GuardianTransactionReconciliation) : GuardianTransactionOutcome

    /** Fail-closed block; the boundary needs evidence this reconciler must never synthesize. */
    data class Blocked(override val disposition: GuardianTransactionReconciliation) : GuardianTransactionOutcome

    /** Exactly one idempotent, zero-mutation step is admissible at this boundary. */
    data class Resume(
        override val disposition: GuardianTransactionReconciliation,
        val step: GuardianTransactionStep,
    ) : GuardianTransactionOutcome
}

internal object GuardianTransactionReconciler {
    /**
     * Unchanged fail-closed disposition for every input this reconciler has ever accepted.
     * [adjudicate] is the same adjudication with the admissible step attached.
     */
    fun reconcile(
        journal: JournalLoadResult,
        arm: GuardianArmStoreLoad,
        terminal: GuardianTerminalLoad = GuardianTerminalLoad.Unavailable,
    ): GuardianTransactionReconciliation = adjudicate(journal, arm, terminal).disposition

    fun adjudicate(
        journal: JournalLoadResult,
        arm: GuardianArmStoreLoad,
        terminal: GuardianTerminalLoad = GuardianTerminalLoad.Unavailable,
    ): GuardianTransactionOutcome = when (journal) {
        JournalLoadResult.Empty -> when (arm) {
            GuardianArmStoreLoad.Empty ->
                GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.NO_RECORDS_RETAIN)
            GuardianArmStoreLoad.Corrupt ->
                GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_ARM_CORRUPT)
            is GuardianArmStoreLoad.LoadedStaged,
            is GuardianArmStoreLoad.LoadedReady,
            -> GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_MISSING_BINDING)
        }
        is JournalLoadResult.Corrupt ->
            GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_JOURNAL_CORRUPT)
        is JournalLoadResult.Loaded -> adjudicateLoaded(journal.record, arm, terminal)
    }

    private fun adjudicateLoaded(
        record: TransactionJournalRecord,
        arm: GuardianArmStoreLoad,
        terminal: GuardianTerminalLoad,
    ): GuardianTransactionOutcome {
        if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA_V5) {
            // Older records remain recovery-readable, but this new cross-store authority never
            // synthesizes a binding, advances a legacy phase, or clears their independent
            // Guardian counterpart. No step is ever admissible for them here.
            return GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.LEGACY_RETAIN)
        }
        val binding = record.guardianBinding
            ?: return GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_MISSING_BINDING)
        if (!record.boundariesConsistentWithPhase()) {
            // A pre-mutation v5 phase that claims a completed boundary is internally
            // inconsistent; no step and no retain may be derived from it.
            return GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_BOUNDARY_MISMATCH)
        }
        if (record.phase == TransactionPhase.GUARDIAN_SETTLED) {
            return settled(record, binding, arm, terminal)
        }
        return when (arm) {
            GuardianArmStoreLoad.Empty -> when (record.phase) {
                TransactionPhase.PREPARED,
                TransactionPhase.ARMING,
                ->
                    // No durable Guardian counterpart was ever reached and no boundary completed,
                    // so this record can only be discarded — never resumed into an apply.
                    GuardianTransactionOutcome.Resume(
                        GuardianTransactionReconciliation.JOURNAL_ONLY_RETAIN,
                        GuardianTransactionStep.DISCARD_UNMUTATED_JOURNAL,
                    )
                else ->
                    // READY evidence exists without its Guardian counterpart; exact restore
                    // authority is gone and nothing here may substitute for it.
                    GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.JOURNAL_ONLY_RETAIN)
            }
            GuardianArmStoreLoad.Corrupt ->
                GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_ARM_CORRUPT)
            is GuardianArmStoreLoad.LoadedStaged -> {
                if (!binding.matches(record, arm.signed.envelope)) {
                    GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_IDENTITY_MISMATCH)
                } else if (record.phase == TransactionPhase.ARMING) {
                    // A staged envelope may still be held by a live guardian. Liveness is not
                    // durable evidence, so no cleanup or hand-off step is admissible here.
                    GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.ARMING_RETAIN)
                } else {
                    GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_PHASE_MISMATCH)
                }
            }
            is GuardianArmStoreLoad.LoadedReady -> ready(record, binding, arm, terminal)
        }
    }

    private fun ready(
        record: TransactionJournalRecord,
        binding: GuardianJournalBinding,
        arm: GuardianArmStoreLoad.LoadedReady,
        terminal: GuardianTerminalLoad,
    ): GuardianTransactionOutcome {
        if (!binding.matches(record, arm.signed.envelope)) {
            return GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_IDENTITY_MISMATCH)
        }
        if (record.phase !in READY_ADMISSIBLE_PHASES) {
            return GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_PHASE_MISMATCH)
        }
        if (record.phase == TransactionPhase.ARMING) {
            // The arm store may reach durable READY before the journal appends its READY
            // digest. Exact loaded READY is sufficient to resume that append-only step.
            return if (
                binding.readySessionSha256 != null &&
                binding.readySessionSha256 != arm.sessionKey.sha256()
            ) {
                GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_READY_MISMATCH)
            } else {
                GuardianTransactionOutcome.Resume(
                    GuardianTransactionReconciliation.ARMING_RETAIN,
                    GuardianTransactionStep.APPEND_JOURNAL_READY,
                )
            }
        }
        if (binding.readySessionSha256 != arm.sessionKey.sha256()) {
            return GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_READY_MISMATCH)
        }
        // Unavailable/TimedOut/Ambiguous transport, an unexited guardian, and forged receipts are
        // all equally "not terminal truth": retain, and let the exact Guardian counterpart keep
        // ownership of this generation. Only fully authenticated terminal evidence admits a step.
        val evidence = terminal as? GuardianTerminalLoad.Received
            ?: return GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.READY_RETAIN)
        return if (authenticTerminalDigest(arm, evidence) != null) {
            // The guardian confirmed Stock and exited; only journal phase/evidence writes remain.
            GuardianTransactionOutcome.Resume(
                GuardianTransactionReconciliation.READY_RETAIN,
                GuardianTransactionStep.ADVANCE_JOURNAL_SETTLED,
            )
        } else {
            GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.READY_RETAIN)
        }
    }

    private fun settled(
        record: TransactionJournalRecord,
        binding: GuardianJournalBinding,
        arm: GuardianArmStoreLoad,
        terminal: GuardianTerminalLoad,
    ): GuardianTransactionOutcome {
        if (binding.readySessionSha256 == null || binding.terminalReceiptSha256 == null) {
            return GuardianTransactionOutcome.Blocked(
                GuardianTransactionReconciliation.BLOCKED_MISSING_TERMINAL_RECEIPT,
            )
        }
        return when (arm) {
            GuardianArmStoreLoad.Empty ->
                // Guardian-first cleanup already completed. The authenticated journal retains the
                // exact READY/terminal digests until its own journal-last clear is admitted.
                GuardianTransactionOutcome.Resume(
                    GuardianTransactionReconciliation.SETTLED_RETAIN,
                    GuardianTransactionStep.CLEAR_SETTLED_JOURNAL,
                )
            GuardianArmStoreLoad.Corrupt ->
                GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_ARM_CORRUPT)
            is GuardianArmStoreLoad.LoadedStaged -> {
                if (binding.matches(record, arm.signed.envelope)) {
                    GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_PHASE_MISMATCH)
                } else {
                    GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_IDENTITY_MISMATCH)
                }
            }
            is GuardianArmStoreLoad.LoadedReady -> {
                val envelope = arm.signed.envelope
                if (!binding.matches(record, envelope)) {
                    GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_IDENTITY_MISMATCH)
                } else if (binding.readySessionSha256 != arm.sessionKey.sha256()) {
                    GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_READY_MISMATCH)
                } else {
                    val evidence = terminal as? GuardianTerminalLoad.Received
                        ?: return GuardianTransactionOutcome.Blocked(
                            GuardianTransactionReconciliation.BLOCKED_MISSING_TERMINAL_RECEIPT,
                        )
                    if (binding.terminalReceiptSha256 == authenticTerminalDigest(arm, evidence)) {
                        // The exact recorded terminal receipt is proven; Guardian-first cleanup
                        // is the one remaining idempotent step.
                        GuardianTransactionOutcome.Resume(
                            GuardianTransactionReconciliation.SETTLED_RETAIN,
                            GuardianTransactionStep.CLEAR_GUARDIAN_ARM,
                        )
                    } else {
                        GuardianTransactionOutcome.Blocked(
                            GuardianTransactionReconciliation.BLOCKED_TERMINAL_MISMATCH,
                        )
                    }
                }
            }
        }
    }

    /**
     * SHA-256 of the Stock-confirmed receipt once both terminal receipts authenticate against the
     * exact arm session/envelope, chain to each other, and satisfy the pinned double-readback
     * timing. Null means the evidence proves nothing.
     */
    internal fun authenticTerminalDigest(
        arm: GuardianArmStoreLoad.LoadedReady,
        evidence: GuardianTerminalLoad.Received,
    ): String? {
        // Every refusal below NAMES itself. It used to return a bare `null` from ten different places,
        // which is why the stranded reference Nova needed a forensic session and then `pm clear` -- see
        // [GuardianTerminalDigestObservability]. The dispositions are byte-for-byte what they were;
        // only the silence is gone.
        fun refuse(cause: String, detail: String? = null): String? {
            GuardianTerminalDigestObservability.onDigestResolved(cause, detail)
            return null
        }
        val envelope = arm.signed.envelope
        val stock = evidence.stock
        val exited = evidence.exited
            ?: return refuse(GuardianTerminalDigestObservability.CAUSE_NO_EXITED_RECEIPT)
        // `runCatching{}.getOrDefault(false)` collapses two different faults into one refusal: a receipt
        // that genuinely does not authenticate, and a verify that never ran because it threw. Measured
        // on the Odin 2026-09-05, this refusal is the one that strands the device — and the log
        // could not say which of the two it was, which is the difference between a signing/envelope bug
        // and the permissions regression this site's own KDoc warns lands here.
        val stockVerify = runCatching {
            RestoreGuardianCrypto.verifyStockConfirmedReceipt(stock, arm.sessionKey, envelope)
        }
        if (stockVerify.getOrDefault(false) != true) {
            return refuse(
                GuardianTerminalDigestObservability.CAUSE_STOCK_UNVERIFIED,
                stockVerify.exceptionOrNull()?.let { it::class.java.simpleName }
                    ?: RestoreGuardianCrypto.stockConfirmedReceiptRefusal(stock, arm.sessionKey, envelope)
                    ?: "returned_false",
            )
        }
        if (
            !runCatching {
                RestoreGuardianCrypto.verifyExitedReceipt(exited, arm.sessionKey, envelope)
            }.getOrDefault(false)
        ) {
            return refuse(GuardianTerminalDigestObservability.CAUSE_EXITED_UNVERIFIED)
        }
        val stockReceipt = stock.receipt
        val exitedReceipt = exited.receipt
        val readbackAuthorityPostdated = when (stockReceipt.restoreScope) {
            GuardianTerminalRestoreScope.SAME_BOOT_FULL ->
                stockReceipt.firstReadbackElapsedRealtimeMs >= arm.signedPin.pin.pinnedElapsedRealtimeMs
            GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK ->
                stockReceipt.recoveryBootIdentity != envelope.bootIdentity
        }
        // The eight terms that used to be one `&&` (CORRECTED 2026-09-06, guardian map §4.6: this
        // said "seven" -- count the `when` branches below), evaluated in the same order and with
        // the same short-circuit, so the disposition cannot move -- only the reason becomes
        // sayable. A conjunction reports one `false`; each of these is a different repair, and two
        // of them are the ordinary consequence of a reboot between arm and restore rather than
        // corruption.
        val settleGapMs = stockReceipt.secondReadbackElapsedRealtimeMs -
            stockReceipt.firstReadbackElapsedRealtimeMs
        return when {
            exitedReceipt.stockConfirmedReceiptSha256 != stockReceipt.digestSha256() ->
                refuse(GuardianTerminalDigestObservability.CAUSE_CHAIN_BROKEN)
            !readbackAuthorityPostdated ->
                refuse(
                    GuardianTerminalDigestObservability.CAUSE_READBACK_NOT_POSTDATED,
                    stockReceipt.restoreScope.name,
                )
            settleGapMs < GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS ->
                refuse(
                    GuardianTerminalDigestObservability.CAUSE_SETTLE_GAP_TOO_SHORT,
                    settleGapMs.toString(),
                )
            stockReceipt.issuedElapsedRealtimeMs < stockReceipt.secondReadbackElapsedRealtimeMs ->
                refuse(GuardianTerminalDigestObservability.CAUSE_STOCK_ISSUED_BEFORE_READBACK)
            exitedReceipt.issuedElapsedRealtimeMs < stockReceipt.issuedElapsedRealtimeMs ->
                refuse(GuardianTerminalDigestObservability.CAUSE_EXIT_ISSUED_BEFORE_STOCK)
            exitedReceipt.recoveryBootIdentity != stockReceipt.recoveryBootIdentity ->
                refuse(GuardianTerminalDigestObservability.CAUSE_BOOT_IDENTITY_MISMATCH)
            exitedReceipt.restoreScope != stockReceipt.restoreScope ->
                refuse(GuardianTerminalDigestObservability.CAUSE_SCOPE_MISMATCH)
            exitedReceipt.verifiedStockEntrySetSha256 != stockReceipt.verifiedStockEntrySetSha256 ->
                refuse(GuardianTerminalDigestObservability.CAUSE_STOCK_ENTRY_SET_MISMATCH)
            else -> {
                GuardianTerminalDigestObservability.onDigestResolved(
                    GuardianTerminalDigestObservability.CAUSE_AUTHENTIC,
                )
                stockReceipt.digestSha256()
            }
        }
    }

    /** No boundary may be complete before schema-v5 mutation authority can exist at APPLYING. */
    private fun TransactionJournalRecord.boundariesConsistentWithPhase(): Boolean =
        when (phase) {
            TransactionPhase.PREPARED,
            TransactionPhase.ARMING,
            TransactionPhase.GUARDIAN_READY,
            -> lastCompletedMutationBoundary == 0 && lastCompletedRestoreBoundary == 0
            else -> true
        }

    private val READY_ADMISSIBLE_PHASES = setOf(
        TransactionPhase.ARMING,
        TransactionPhase.GUARDIAN_READY,
        TransactionPhase.APPLYING,
        TransactionPhase.APPLIED,
        TransactionPhase.RESTORING,
        TransactionPhase.RESTORE_FAILED,
    )

    private fun GuardianJournalBinding.matches(
        record: TransactionJournalRecord,
        envelope: RestoreGuardianEnvelope,
    ): Boolean =
        envelopeSha256 == envelope.digestSha256() &&
            taskNonce == envelope.taskNonce &&
            helperBuildIdentity == envelope.helperBuildIdentity &&
            tweakerCertificateSha256 == envelope.tweakerCertificateSha256 &&
            hardDeadlineElapsedRealtimeMs == envelope.hardDeadlineElapsedRealtimeMs &&
            record.generation == envelope.generation &&
            record.requestId == envelope.requestId &&
            record.transactionId == envelope.transactionId &&
            record.hardwareFingerprint == envelope.hardwareFingerprint &&
            record.deviceProfile == envelope.deviceProfile &&
            record.bootIdentity == envelope.bootIdentity &&
            record.expectedTopologySha256 == envelope.topologySha256 &&
            record.snapshotSha256 == envelope.snapshotSha256
    // No plan-hash equality, for the same reason RestoreGuardianExpectedIdentity carries none: a
    // lawful derate moves record.planHash off the arm-time value, and requiring them equal strands
    // the record at RESTORING forever. envelopeSha256 above already pins the arm-time plan, because
    // the envelope's canonical bytes include its planSha256 -- and the Guardian restores from the
    // captured SNAPSHOT (compared here) rather than from any plan, so restore correctness never
    // depended on this field.
}
