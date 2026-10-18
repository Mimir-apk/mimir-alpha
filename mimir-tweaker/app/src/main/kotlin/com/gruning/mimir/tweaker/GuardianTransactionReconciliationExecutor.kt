package com.gruning.mimir.tweaker

/** Result of one bounded, zero-hardware cross-store reconciliation turn. */
internal data class GuardianReconciliationExecution(
    val outcome: GuardianTransactionOutcome,
    val appliedStep: GuardianTransactionStep? = null,
    val completed: Boolean = false,
)

/**
 * Executes at most one pure cross-store replay step. This class has no hardware or PServer port;
 * it can only append authenticated journal evidence or clear an exactly adjudicated store.
 */
internal class GuardianTransactionReconciliationExecutor(
    private val journal: TransactionJournal,
    private val armStore: RestoreGuardianArmStore,
    private val terminalReceipts: GuardianTerminalReceiptPort =
        GuardianTerminalReceiptPort { GuardianTerminalLoad.Unavailable },
) {
    @Synchronized
    fun reconcileOnce(): GuardianReconciliationExecution {
        val loadedJournal = runCatching { journal.load() }
            .getOrElse { JournalLoadResult.Corrupt("journal load failed") }
        if (loadedJournal is JournalLoadResult.Corrupt) {
            return stable(GuardianTransactionReconciler.adjudicate(loadedJournal, GuardianArmStoreLoad.Empty))
        }
        if (loadedJournal === JournalLoadResult.Empty) {
            return when (safePresence()) {
                GuardianArmStorePresence.ABSENT -> stable(
                    GuardianTransactionReconciler.adjudicate(
                        JournalLoadResult.Empty,
                        GuardianArmStoreLoad.Empty,
                    ),
                )
                GuardianArmStorePresence.PRESENT,
                GuardianArmStorePresence.UNREADABLE,
                -> stable(
                    GuardianTransactionOutcome.Blocked(
                        GuardianTransactionReconciliation.BLOCKED_MISSING_BINDING,
                    ),
                )
            }
        }

        val record = (loadedJournal as JournalLoadResult.Loaded).record
        if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA_V5) {
            return stable(
                GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.LEGACY_RETAIN),
            )
        }
        val expected = GuardianArmExpectedIdentityDerivation.from(record)
            ?: return stable(
                GuardianTransactionOutcome.Blocked(
                    GuardianTransactionReconciliation.BLOCKED_MISSING_BINDING,
                ),
            )
        val arm = runCatching { armStore.load(expected) }.getOrDefault(GuardianArmStoreLoad.Corrupt)
        val terminal = record.guardianBinding?.let { binding ->
            runCatching { terminalReceipts.loadUntil(binding.hardDeadlineElapsedRealtimeMs) }
                .getOrDefault(GuardianTerminalLoad.Ambiguous)
        } ?: GuardianTerminalLoad.Unavailable
        val outcome = GuardianTransactionReconciler.adjudicate(loadedJournal, arm, terminal)
        val resume = outcome as? GuardianTransactionOutcome.Resume ?: return stable(outcome)
        val completed = when (resume.step) {
            GuardianTransactionStep.DISCARD_UNMUTATED_JOURNAL ->
                safePresence() == GuardianArmStorePresence.ABSENT &&
                    runCatching { journal.clear(record.transactionId) }.getOrDefault(false)

            GuardianTransactionStep.APPEND_JOURNAL_READY -> {
                val ready = arm as? GuardianArmStoreLoad.LoadedReady
                val binding = record.guardianBinding
                ready != null && binding != null && runCatching {
                    journal.persist(
                        record.copy(
                            phase = TransactionPhase.GUARDIAN_READY,
                            guardianBinding = binding.copy(
                                readySessionSha256 = ready.sessionKey.sha256(),
                            ),
                        ),
                    )
                }.getOrDefault(false)
            }

            GuardianTransactionStep.ADVANCE_JOURNAL_SETTLED -> {
                val ready = arm as? GuardianArmStoreLoad.LoadedReady
                val received = terminal as? GuardianTerminalLoad.Received
                val binding = record.guardianBinding
                val terminalDigest = if (ready != null && received != null) {
                    GuardianTransactionReconciler.authenticTerminalDigest(ready, received)
                } else {
                    null
                }
                if (ready == null || binding == null || terminalDigest == null) {
                    false
                } else {
                    val restoring = if (
                        record.phase == TransactionPhase.RESTORING ||
                        record.phase == TransactionPhase.RESTORE_FAILED
                    ) {
                        record
                    } else {
                        record.copy(phase = TransactionPhase.RESTORING)
                    }
                    val restoringDurable = restoring === record ||
                        runCatching { journal.persist(restoring) }.getOrDefault(false)
                    restoringDurable && runCatching {
                        journal.persist(
                            restoring.copy(
                                phase = TransactionPhase.GUARDIAN_SETTLED,
                                guardianBinding = binding.copy(
                                    readySessionSha256 = ready.sessionKey.sha256(),
                                    terminalReceiptSha256 = terminalDigest,
                                ),
                            ),
                        )
                    }.getOrDefault(false)
                }
            }

            GuardianTransactionStep.CLEAR_GUARDIAN_ARM ->
                runCatching { armStore.clear() }.getOrDefault(false)

            GuardianTransactionStep.CLEAR_SETTLED_JOURNAL ->
                safePresence() == GuardianArmStorePresence.ABSENT &&
                    runCatching { journal.clear(record.transactionId) }.getOrDefault(false)
        }
        return GuardianReconciliationExecution(
            outcome = outcome,
            appliedStep = resume.step,
            completed = completed,
        )
    }

    private fun safePresence(): GuardianArmStorePresence =
        runCatching { armStore.presence() }.getOrDefault(GuardianArmStorePresence.UNREADABLE)

    private fun stable(outcome: GuardianTransactionOutcome) =
        GuardianReconciliationExecution(outcome = outcome)
}
