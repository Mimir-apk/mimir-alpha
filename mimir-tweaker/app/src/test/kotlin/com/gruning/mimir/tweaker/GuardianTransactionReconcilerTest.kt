package com.gruning.mimir.tweaker

import java.security.KeyPairGenerator
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianTransactionReconcilerTest {
    private val appPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val available = GuardianTestKeyAuthority.create(hash("certificate"), appPair).available
    private val sessionPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(sessionPair.public)
    private val sessionSigner = EcdsaP256GuardianSessionSigner(sessionPair.private)

    @Test
    fun `executor blocks orphan arm presence when journal identity is absent`() {
        val journal = ExecutorJournal()
        val arm = ExecutorArmStore(
            load = GuardianArmStoreLoad.Corrupt,
            presence = GuardianArmStorePresence.PRESENT,
        )

        val result = GuardianTransactionReconciliationExecutor(journal, arm).reconcileOnce()

        assertEquals(
            GuardianTransactionReconciliation.BLOCKED_MISSING_BINDING,
            result.outcome.disposition,
        )
        assertEquals(null, result.appliedStep)
        assertEquals(0, journal.writes)
        assertEquals(0, journal.clears)
        assertEquals(0, arm.clears)
    }

    @Test
    fun `executor appends exact READY once and a replay is a no-op`() {
        val exact = envelope()
        val loadedReady = ready(exact)
        val journal = ExecutorJournal(record(exact, TransactionPhase.ARMING))
        val arm = ExecutorArmStore(loadedReady, GuardianArmStorePresence.PRESENT)
        val executor = GuardianTransactionReconciliationExecutor(journal, arm)

        val first = executor.reconcileOnce()
        val second = executor.reconcileOnce()

        assertEquals(GuardianTransactionStep.APPEND_JOURNAL_READY, first.appliedStep)
        assertTrue(first.completed)
        assertEquals(TransactionPhase.GUARDIAN_READY, journal.record?.phase)
        assertEquals(loadedReady.sessionKey.sha256(), journal.record?.guardianBinding?.readySessionSha256)
        assertEquals(null, second.appliedStep)
        assertEquals(1, journal.writes)
        assertEquals(0, journal.clears)
        assertEquals(0, arm.clears)
    }

    @Test
    fun `executor settles then clears Guardian first and journal last across bounded turns`() {
        val exact = envelope()
        val loadedReady = ready(exact)
        val terminal = terminal(exact, loadedReady)
        val journal = ExecutorJournal(
            record(
                exact,
                phase = TransactionPhase.APPLIED,
                readySessionSha256 = loadedReady.sessionKey.sha256(),
            ),
        )
        val arm = ExecutorArmStore(loadedReady, GuardianArmStorePresence.PRESENT)
        val executor = GuardianTransactionReconciliationExecutor(
            journal,
            arm,
            GuardianTerminalReceiptPort { terminal },
        )

        val settle = executor.reconcileOnce()
        val clearArm = executor.reconcileOnce()
        val clearJournal = executor.reconcileOnce()
        val stable = executor.reconcileOnce()

        assertEquals(GuardianTransactionStep.ADVANCE_JOURNAL_SETTLED, settle.appliedStep)
        assertEquals(TransactionPhase.GUARDIAN_SETTLED, journal.lastSettled?.phase)
        assertEquals(GuardianTransactionStep.CLEAR_GUARDIAN_ARM, clearArm.appliedStep)
        assertEquals(GuardianTransactionStep.CLEAR_SETTLED_JOURNAL, clearJournal.appliedStep)
        assertEquals(GuardianTransactionReconciliation.NO_RECORDS_RETAIN, stable.outcome.disposition)
        assertEquals(null, stable.appliedStep)
        assertEquals(1, arm.clears)
        assertEquals(1, journal.clears)
        assertEquals(null, journal.record)
    }

    @Test
    fun `executor discards only an unmutated journal with proven absent arm`() {
        val exact = envelope()
        val journal = ExecutorJournal(record(exact, TransactionPhase.PREPARED))
        val arm = ExecutorArmStore(GuardianArmStoreLoad.Empty, GuardianArmStorePresence.ABSENT)
        val executor = GuardianTransactionReconciliationExecutor(journal, arm)

        val first = executor.reconcileOnce()
        val second = executor.reconcileOnce()

        assertEquals(GuardianTransactionStep.DISCARD_UNMUTATED_JOURNAL, first.appliedStep)
        assertTrue(first.completed)
        assertEquals(GuardianTransactionReconciliation.NO_RECORDS_RETAIN, second.outcome.disposition)
        assertEquals(1, journal.clears)
        assertEquals(0, arm.clears)
    }

    @Test
    fun `phase by arm matrix retains exact authority and blocks every inconsistent pairing`() {
        val exact = envelope()
        val staged = staged(exact)
        val ready = ready(exact)

        TransactionPhase.entries.forEach { phase ->
            val terminalDigest = if (phase == TransactionPhase.GUARDIAN_SETTLED) hash("terminal") else null
            val readyDigest = when (phase) {
                TransactionPhase.GUARDIAN_READY,
                TransactionPhase.APPLYING,
                TransactionPhase.APPLIED,
                TransactionPhase.RESTORING,
                TransactionPhase.RESTORE_FAILED,
                TransactionPhase.GUARDIAN_SETTLED,
                -> sessionKey.sha256()
                TransactionPhase.PREPARED,
                TransactionPhase.ARMING,
                -> null
            }
            val journal = JournalLoadResult.Loaded(
                record(
                    exact,
                    phase = phase,
                    readySessionSha256 = readyDigest,
                    terminalReceiptSha256 = terminalDigest,
                ),
            )

            val emptyExpected = if (phase == TransactionPhase.GUARDIAN_SETTLED) {
                GuardianTransactionReconciliation.SETTLED_RETAIN
            } else {
                GuardianTransactionReconciliation.JOURNAL_ONLY_RETAIN
            }
            assertEquals(
                "$phase + Empty",
                emptyExpected,
                GuardianTransactionReconciler.reconcile(journal, GuardianArmStoreLoad.Empty),
            )
            assertEquals(
                "$phase + Corrupt",
                GuardianTransactionReconciliation.BLOCKED_ARM_CORRUPT,
                GuardianTransactionReconciler.reconcile(journal, GuardianArmStoreLoad.Corrupt),
            )
            assertEquals(
                "$phase + STAGED",
                if (phase == TransactionPhase.ARMING) {
                    GuardianTransactionReconciliation.ARMING_RETAIN
                } else {
                    GuardianTransactionReconciliation.BLOCKED_PHASE_MISMATCH
                },
                GuardianTransactionReconciler.reconcile(journal, staged),
            )
            val readyExpected = when (phase) {
                TransactionPhase.ARMING -> GuardianTransactionReconciliation.ARMING_RETAIN
                TransactionPhase.GUARDIAN_READY,
                TransactionPhase.APPLYING,
                TransactionPhase.APPLIED,
                TransactionPhase.RESTORING,
                TransactionPhase.RESTORE_FAILED,
                -> GuardianTransactionReconciliation.READY_RETAIN
                TransactionPhase.GUARDIAN_SETTLED ->
                    GuardianTransactionReconciliation.BLOCKED_MISSING_TERMINAL_RECEIPT
                TransactionPhase.PREPARED -> GuardianTransactionReconciliation.BLOCKED_PHASE_MISMATCH
            }
            assertEquals(
                "$phase + READY",
                readyExpected,
                GuardianTransactionReconciler.reconcile(journal, ready),
            )
        }

        assertEquals(
            GuardianTransactionReconciliation.NO_RECORDS_RETAIN,
            GuardianTransactionReconciler.reconcile(
                JournalLoadResult.Empty,
                GuardianArmStoreLoad.Empty,
            ),
        )
        assertEquals(
            GuardianTransactionReconciliation.BLOCKED_JOURNAL_CORRUPT,
            GuardianTransactionReconciler.reconcile(
                JournalLoadResult.Corrupt("tampered"),
                staged,
            ),
        )
    }

    @Test
    fun `ARMING resumes exact durable READY before journal READY digest append`() {
        val exact = envelope()
        val ready = ready(exact)
        val arming = record(exact, TransactionPhase.ARMING)

        assertEquals(
            GuardianTransactionReconciliation.ARMING_RETAIN,
            GuardianTransactionReconciler.reconcile(JournalLoadResult.Loaded(arming), ready),
        )
        assertEquals(
            GuardianTransactionReconciliation.BLOCKED_READY_MISMATCH,
            GuardianTransactionReconciler.reconcile(
                JournalLoadResult.Loaded(
                    arming.copy(
                        guardianBinding = arming.guardianBinding!!.copy(
                            readySessionSha256 = hash("foreign-session"),
                        ),
                    ),
                ),
                ready,
            ),
        )
    }

    @Test
    fun `settled requires exact arm session and authenticated chained Stock and EXITED receipts`() {
        val exact = envelope()
        val ready = ready(exact)
        val terminal = terminal(exact, ready)
        val stockDigest = terminal.stock.receipt.digestSha256()
        val settled = record(
            exact,
            phase = TransactionPhase.GUARDIAN_SETTLED,
            readySessionSha256 = ready.sessionKey.sha256(),
            terminalReceiptSha256 = stockDigest,
        )

        assertEquals(
            GuardianTransactionReconciliation.SETTLED_RETAIN,
            GuardianTransactionReconciler.reconcile(
                JournalLoadResult.Loaded(settled),
                ready,
                terminal,
            ),
        )
        assertEquals(
            GuardianTransactionReconciliation.BLOCKED_ARM_CORRUPT,
            GuardianTransactionReconciler.reconcile(
                JournalLoadResult.Loaded(settled),
                GuardianArmStoreLoad.Corrupt,
                terminal,
            ),
        )
        assertEquals(
            GuardianTransactionReconciliation.BLOCKED_IDENTITY_MISMATCH,
            GuardianTransactionReconciler.reconcile(
                JournalLoadResult.Loaded(settled),
                ready(exact.copy(requestId = "foreign-request")),
                terminal,
            ),
        )
        val invalidStock = GuardianTerminalLoad.Received(
            SignedRestoreGuardianStockConfirmedReceipt(terminal.stock.receipt, byteArrayOf(1)),
            terminal.exited,
        )
        assertEquals(
            GuardianTransactionReconciliation.BLOCKED_TERMINAL_MISMATCH,
            GuardianTransactionReconciler.reconcile(
                JournalLoadResult.Loaded(settled),
                ready,
                invalidStock,
            ),
        )
        assertEquals(
            GuardianTransactionReconciliation.BLOCKED_MISSING_TERMINAL_RECEIPT,
            GuardianTransactionReconciler.reconcile(
                JournalLoadResult.Loaded(
                    settled.copy(
                        guardianBinding = settled.guardianBinding!!.copy(terminalReceiptSha256 = null),
                    ),
                ),
                GuardianArmStoreLoad.Empty,
            ),
        )
    }

    @Test
    fun `schema v5 phase evidence contract rejects premature and missing digests`() {
        val exact = envelope()
        val readyDigest = sessionKey.sha256()
        val terminalDigest = hash("terminal")

        val valid = listOf(
            record(exact, TransactionPhase.PREPARED),
            record(exact, TransactionPhase.ARMING),
            record(exact, TransactionPhase.GUARDIAN_READY, readyDigest),
            record(exact, TransactionPhase.APPLYING, readyDigest),
            record(exact, TransactionPhase.APPLIED, readyDigest),
            record(exact, TransactionPhase.RESTORING, readyDigest),
            record(exact, TransactionPhase.RESTORE_FAILED, readyDigest),
            record(exact, TransactionPhase.GUARDIAN_SETTLED, readyDigest, terminalDigest),
        )
        valid.forEach {
            assertTrue("${it.phase}: ${GuardianJournalBindingValidator.validate(it)}", GuardianJournalBindingValidator.validate(it).isEmpty())
        }

        val invalid = listOf(
            record(exact, TransactionPhase.ARMING, readyDigest),
            record(exact, TransactionPhase.ARMING, terminalReceiptSha256 = terminalDigest),
            record(exact, TransactionPhase.GUARDIAN_READY),
            record(exact, TransactionPhase.APPLYING, readyDigest, terminalDigest),
            record(exact, TransactionPhase.GUARDIAN_SETTLED, readyDigest),
        )
        invalid.forEach {
            assertTrue("${it.phase} must fail", GuardianJournalBindingValidator.validate(it).isNotEmpty())
        }
    }

    @Test
    fun `every schema v5 crash boundary carries its exact idempotent step or none`() {
        val exact = envelope()
        val staged = staged(exact)
        val ready = ready(exact)

        TransactionPhase.entries.forEach { phase ->
            val terminalDigest = if (phase == TransactionPhase.GUARDIAN_SETTLED) hash("terminal") else null
            val readyDigest = when (phase) {
                TransactionPhase.PREPARED, TransactionPhase.ARMING -> null
                else -> sessionKey.sha256()
            }
            val journal = JournalLoadResult.Loaded(
                record(exact, phase, readyDigest, terminalDigest),
            )

            val expectedEmpty = when (phase) {
                // No Guardian counterpart was ever durable and no boundary completed.
                TransactionPhase.PREPARED,
                TransactionPhase.ARMING,
                -> GuardianTransactionOutcome.Resume(
                    GuardianTransactionReconciliation.JOURNAL_ONLY_RETAIN,
                    GuardianTransactionStep.DISCARD_UNMUTATED_JOURNAL,
                )
                // Guardian-first cleanup already completed; the journal-last clear is owed.
                TransactionPhase.GUARDIAN_SETTLED -> GuardianTransactionOutcome.Resume(
                    GuardianTransactionReconciliation.SETTLED_RETAIN,
                    GuardianTransactionStep.CLEAR_SETTLED_JOURNAL,
                )
                // READY evidence without its Guardian counterpart admits nothing.
                else -> GuardianTransactionOutcome.Retain(
                    GuardianTransactionReconciliation.JOURNAL_ONLY_RETAIN,
                )
            }
            assertEquals(
                "$phase + Empty",
                expectedEmpty,
                GuardianTransactionReconciler.adjudicate(journal, GuardianArmStoreLoad.Empty),
            )
            assertEquals(
                "$phase + Corrupt",
                GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_ARM_CORRUPT),
                GuardianTransactionReconciler.adjudicate(journal, GuardianArmStoreLoad.Corrupt),
            )
            assertEquals(
                "$phase + STAGED",
                if (phase == TransactionPhase.ARMING) {
                    // A staged envelope may still be held by a live guardian: no step.
                    GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.ARMING_RETAIN)
                } else {
                    GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_PHASE_MISMATCH)
                },
                GuardianTransactionReconciler.adjudicate(journal, staged),
            )
            val expectedReady = when (phase) {
                // Durable arm READY before the journal digest append is the one advance here.
                TransactionPhase.ARMING -> GuardianTransactionOutcome.Resume(
                    GuardianTransactionReconciliation.ARMING_RETAIN,
                    GuardianTransactionStep.APPEND_JOURNAL_READY,
                )
                TransactionPhase.GUARDIAN_READY,
                TransactionPhase.APPLYING,
                TransactionPhase.APPLIED,
                TransactionPhase.RESTORING,
                TransactionPhase.RESTORE_FAILED,
                -> GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.READY_RETAIN)
                TransactionPhase.GUARDIAN_SETTLED -> GuardianTransactionOutcome.Blocked(
                    GuardianTransactionReconciliation.BLOCKED_MISSING_TERMINAL_RECEIPT,
                )
                TransactionPhase.PREPARED -> GuardianTransactionOutcome.Blocked(
                    GuardianTransactionReconciliation.BLOCKED_PHASE_MISMATCH,
                )
            }
            assertEquals("$phase + READY", expectedReady, GuardianTransactionReconciler.adjudicate(journal, ready))

            // Every stepped boundary keeps the exact fail-closed disposition reconcile() has
            // always returned; a step never upgrades or loosens that classification.
            listOf(GuardianArmStoreLoad.Empty, GuardianArmStoreLoad.Corrupt, staged, ready).forEach { arm ->
                val outcome = GuardianTransactionReconciler.adjudicate(journal, arm)
                assertEquals(
                    "$phase disposition parity",
                    GuardianTransactionReconciler.reconcile(journal, arm),
                    outcome.disposition,
                )
                if (outcome is GuardianTransactionOutcome.Resume) {
                    assertTrue(
                        "$phase step disposition must stay a retain",
                        outcome.disposition in RETAIN_DISPOSITIONS,
                    )
                }
            }
        }

        assertEquals(
            GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.NO_RECORDS_RETAIN),
            GuardianTransactionReconciler.adjudicate(JournalLoadResult.Empty, GuardianArmStoreLoad.Empty),
        )
        assertEquals(
            GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_JOURNAL_CORRUPT),
            GuardianTransactionReconciler.adjudicate(JournalLoadResult.Corrupt("tampered"), staged),
        )
        assertEquals(
            GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_MISSING_BINDING),
            GuardianTransactionReconciler.adjudicate(JournalLoadResult.Empty, staged),
        )
    }

    @Test
    fun `authenticated terminal receipts advance an unsettled journal and forged evidence never does`() {
        val exact = envelope()
        val ready = ready(exact)
        val terminal = terminal(exact, ready)

        setOf(
            TransactionPhase.GUARDIAN_READY,
            TransactionPhase.APPLYING,
            TransactionPhase.APPLIED,
            TransactionPhase.RESTORING,
            TransactionPhase.RESTORE_FAILED,
        ).forEach { phase ->
            val journal = JournalLoadResult.Loaded(record(exact, phase, sessionKey.sha256()))
            assertEquals(
                "$phase advance",
                GuardianTransactionOutcome.Resume(
                    GuardianTransactionReconciliation.READY_RETAIN,
                    GuardianTransactionStep.ADVANCE_JOURNAL_SETTLED,
                ),
                GuardianTransactionReconciler.adjudicate(journal, ready, terminal),
            )

            // Forged Stock signature, an unexited guardian, and unavailable transport are all
            // equally not terminal truth: the boundary stays a bare retain with no step.
            listOf(
                GuardianTerminalLoad.Received(
                    SignedRestoreGuardianStockConfirmedReceipt(terminal.stock.receipt, byteArrayOf(9)),
                    terminal.exited,
                ),
                GuardianTerminalLoad.Received(terminal.stock, null),
                GuardianTerminalLoad.TimedOut,
                GuardianTerminalLoad.Ambiguous,
                GuardianTerminalLoad.Unavailable,
            ).forEach { hostile ->
                assertEquals(
                    "$phase hostile $hostile",
                    GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.READY_RETAIN),
                    GuardianTransactionReconciler.adjudicate(journal, ready, hostile),
                )
            }
        }

        // A foreign session key can never buy an advance, even with authentic-looking receipts.
        assertEquals(
            GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_READY_MISMATCH),
            GuardianTransactionReconciler.adjudicate(
                JournalLoadResult.Loaded(
                    record(exact, TransactionPhase.APPLYING, hash("foreign-session")),
                ),
                ready,
                terminal,
            ),
        )
    }

    @Test
    fun `settled cleanup is guardian first then journal last`() {
        val exact = envelope()
        val ready = ready(exact)
        val terminal = terminal(exact, ready)
        val settled = JournalLoadResult.Loaded(
            record(
                exact,
                TransactionPhase.GUARDIAN_SETTLED,
                readySessionSha256 = ready.sessionKey.sha256(),
                terminalReceiptSha256 = terminal.stock.receipt.digestSha256(),
            ),
        )

        assertEquals(
            GuardianTransactionOutcome.Resume(
                GuardianTransactionReconciliation.SETTLED_RETAIN,
                GuardianTransactionStep.CLEAR_GUARDIAN_ARM,
            ),
            GuardianTransactionReconciler.adjudicate(settled, ready, terminal),
        )
        assertEquals(
            GuardianTransactionOutcome.Resume(
                GuardianTransactionReconciliation.SETTLED_RETAIN,
                GuardianTransactionStep.CLEAR_SETTLED_JOURNAL,
            ),
            GuardianTransactionReconciler.adjudicate(settled, GuardianArmStoreLoad.Empty, terminal),
        )
        // A terminal digest that is not the exact recorded one blocks instead of cleaning up.
        assertEquals(
            GuardianTransactionOutcome.Blocked(GuardianTransactionReconciliation.BLOCKED_TERMINAL_MISMATCH),
            GuardianTransactionReconciler.adjudicate(
                JournalLoadResult.Loaded(
                    record(
                        exact,
                        TransactionPhase.GUARDIAN_SETTLED,
                        readySessionSha256 = ready.sessionKey.sha256(),
                        terminalReceiptSha256 = hash("foreign-terminal"),
                    ),
                ),
                ready,
                terminal,
            ),
        )
    }

    @Test
    fun `pre-mutation phases claiming a completed boundary are blocked with no step`() {
        val exact = envelope()
        val staged = staged(exact)
        val ready = ready(exact)
        val arms = listOf(GuardianArmStoreLoad.Empty, GuardianArmStoreLoad.Corrupt, staged, ready)

        listOf(TransactionPhase.PREPARED, TransactionPhase.ARMING, TransactionPhase.GUARDIAN_READY).forEach { phase ->
            val readyDigest = if (phase == TransactionPhase.GUARDIAN_READY) sessionKey.sha256() else null
            listOf(
                record(exact, phase, readyDigest, mutationBoundary = 1),
                record(exact, phase, readyDigest, restoreBoundary = 1),
            ).forEach { hostile ->
                arms.forEach { arm ->
                    assertEquals(
                        "$phase boundary",
                        GuardianTransactionOutcome.Blocked(
                            GuardianTransactionReconciliation.BLOCKED_BOUNDARY_MISMATCH,
                        ),
                        GuardianTransactionReconciler.adjudicate(JournalLoadResult.Loaded(hostile), arm),
                    )
                }
            }
        }
    }

    @Test
    fun `legacy v1 to v4 records never receive a step under any pairing`() {
        val exact = envelope()
        val arms = listOf(GuardianArmStoreLoad.Empty, GuardianArmStoreLoad.Corrupt, staged(exact), ready(exact))
        val terminals = listOf(GuardianTerminalLoad.Unavailable, terminal(exact, ready(exact)))

        listOf(
            TRANSACTION_JOURNAL_SCHEMA_V1,
            TRANSACTION_JOURNAL_SCHEMA_V2,
            TRANSACTION_JOURNAL_SCHEMA_V3,
            TRANSACTION_JOURNAL_SCHEMA_V4,
        ).forEach { schema ->
            TransactionPhase.entries.forEach { phase ->
                val legacy = record(exact, phase).copy(schemaVersion = schema, guardianBinding = null)
                arms.forEach { arm ->
                    terminals.forEach { terminal ->
                        assertEquals(
                            "v$schema $phase",
                            GuardianTransactionOutcome.Retain(GuardianTransactionReconciliation.LEGACY_RETAIN),
                            GuardianTransactionReconciler.adjudicate(
                                JournalLoadResult.Loaded(legacy),
                                arm,
                                terminal,
                            ),
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Why a terminal digest was refused — the ten reasons that used to be one `null`.
    //
    // After Nova recovery with pm clear on 2026-09-03, the refusal remained hard to diagnose because every refusal below
    // returned a bare `null` while the only surviving line said `READY_RETAIN:null:false` — which
    // could not even separate "no receipt" from "a receipt that failed to authenticate". These pin
    // that each refusal now NAMES itself, and — the half that matters more — that naming it changed
    // no disposition.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an authentic pair reports authentic and still yields the stock digest`() {
        GuardianTerminalDigestObservability.resetForTest()
        val envelope = envelope()
        val arm = ready(envelope)

        val digest = GuardianTransactionReconciler.authenticTerminalDigest(arm, terminal(envelope, arm))

        assertTrue("an authentic pair must still return a digest", digest != null)
        assertEquals(
            "terminalDigest cause=authentic",
            GuardianTerminalDigestObservability.lastRecorded,
        )
    }

    @Test
    fun `a stock receipt with no exit is named rather than silently refused`() {
        GuardianTerminalDigestObservability.resetForTest()
        val envelope = envelope()
        val arm = ready(envelope)
        val evidence = terminal(envelope, arm).copy(exited = null)

        assertEquals(null, GuardianTransactionReconciler.authenticTerminalDigest(arm, evidence))
        assertEquals(
            "terminalDigest cause=no_exited_receipt",
            GuardianTerminalDigestObservability.lastRecorded,
        )
    }

    @Test
    fun `a broken chain is distinguishable from every other refusal`() {
        GuardianTerminalDigestObservability.resetForTest()
        val envelope = envelope()
        val arm = ready(envelope)
        val good = terminal(envelope, arm)
        // An exited receipt that points at a DIFFERENT stock receipt: signatures still verify, the
        // chain does not. Before this change it was indistinguishable from a forged signature.
        val otherStock = terminal(envelope.copy(requestId = "request-8"), arm).stock
        val evidence = good.copy(
            stock = otherStock,
        )

        assertEquals(null, GuardianTransactionReconciler.authenticTerminalDigest(arm, evidence))
        assertTrue(
            "expected a named refusal, got ${GuardianTerminalDigestObservability.lastRecorded}",
            GuardianTerminalDigestObservability.lastRecorded?.startsWith("terminalDigest cause=") == true,
        )
        assertTrue(
            "a signature refusal and a chain break must not render the same",
            GuardianTerminalDigestObservability.lastRecorded !=
                "terminalDigest cause=no_exited_receipt",
        )
    }

    @Test
    fun `the seam deduplicates a stuck device instead of logging it forever`() {
        GuardianTerminalDigestObservability.resetForTest()
        val first = GuardianTerminalDigestObservability.onDigestResolved(
            GuardianTerminalDigestObservability.CAUSE_CHAIN_BROKEN,
        )
        val second = GuardianTerminalDigestObservability.onDigestResolved(
            GuardianTerminalDigestObservability.CAUSE_CHAIN_BROKEN,
        )
        val changed = GuardianTerminalDigestObservability.onDigestResolved(
            GuardianTerminalDigestObservability.CAUSE_SCOPE_MISMATCH,
        )

        assertTrue("the first occurrence must emit", first)
        assertTrue("a repeat must be suppressed — reconciliation runs on every restore", !second)
        assertTrue("a CHANGE must emit, or a device that moves state stays silent", changed)
        assertEquals(
            "terminalDigest cause=scope_mismatch",
            GuardianTerminalDigestObservability.lastRecorded,
        )
    }

    @Test
    fun `every cause renders distinctly, so two repairs never share a line`() {
        val causes = listOf(
            GuardianTerminalDigestObservability.CAUSE_AUTHENTIC,
            GuardianTerminalDigestObservability.CAUSE_NO_EXITED_RECEIPT,
            GuardianTerminalDigestObservability.CAUSE_STOCK_UNVERIFIED,
            GuardianTerminalDigestObservability.CAUSE_EXITED_UNVERIFIED,
            GuardianTerminalDigestObservability.CAUSE_CHAIN_BROKEN,
            GuardianTerminalDigestObservability.CAUSE_READBACK_NOT_POSTDATED,
            GuardianTerminalDigestObservability.CAUSE_SETTLE_GAP_TOO_SHORT,
            GuardianTerminalDigestObservability.CAUSE_STOCK_ISSUED_BEFORE_READBACK,
            GuardianTerminalDigestObservability.CAUSE_EXIT_ISSUED_BEFORE_STOCK,
            GuardianTerminalDigestObservability.CAUSE_BOOT_IDENTITY_MISMATCH,
            GuardianTerminalDigestObservability.CAUSE_SCOPE_MISMATCH,
            GuardianTerminalDigestObservability.CAUSE_STOCK_ENTRY_SET_MISMATCH,
        )
        val rendered = causes.map { GuardianTerminalDigestObservability.describe(it, null) }

        assertEquals("no two causes may render the same line", causes.size, rendered.toSet().size)
        assertEquals(
            "the stranded Nova reported the state, never the reason — that is what this fixes",
            12,
            causes.size,
        )
    }

    private fun staged(value: RestoreGuardianEnvelope): GuardianArmStoreLoad.LoadedStaged =
        GuardianArmStoreLoad.LoadedStaged(available.signEnvelope(value))

    private fun ready(value: RestoreGuardianEnvelope): GuardianArmStoreLoad.LoadedReady {
        val signedEnvelope = available.signEnvelope(value)
        val receipt = RestoreGuardianReadyReceipt(
            helperBuildIdentity = value.helperBuildIdentity,
            bootIdentity = value.bootIdentity,
            generation = value.generation,
            requestId = value.requestId,
            transactionId = value.transactionId,
            taskNonce = value.taskNonce,
            envelopeSha256 = value.digestSha256(),
            sessionPublicKey = sessionKey,
            issuedElapsedRealtimeMs = 10,
        )
        val signedReady = RestoreGuardianCrypto.signReadyReceipt(receipt, sessionSigner)
        val pin = RestoreGuardianSessionPin(
            envelopeSha256 = value.digestSha256(),
            sessionKeySha256 = sessionKey.sha256(),
            readyReceiptSha256 = GuardianCanonicalCodec.sha256(receipt.canonicalBytes()),
            readyIssuedElapsedRealtimeMs = receipt.issuedElapsedRealtimeMs,
            pinnedElapsedRealtimeMs = 11,
        )
        return GuardianArmStoreLoad.LoadedReady(
            signed = signedEnvelope,
            sessionKey = sessionKey,
            signedReady = signedReady,
            signedPin = available.signSessionPin(pin),
        )
    }

    private fun terminal(
        value: RestoreGuardianEnvelope,
        ready: GuardianArmStoreLoad.LoadedReady,
    ): GuardianTerminalLoad.Received {
        val first = ready.signedPin.pin.pinnedElapsedRealtimeMs
        val second = first + GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS
        val stockReceipt = RestoreGuardianStockConfirmedReceipt(
            helperBuildIdentity = value.helperBuildIdentity,
            bootIdentity = value.bootIdentity,
            generation = value.generation,
            requestId = value.requestId,
            transactionId = value.transactionId,
            taskNonce = value.taskNonce,
            envelopeSha256 = value.digestSha256(),
            sessionKeySha256 = ready.sessionKey.sha256(),
            verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(value.restoreEntries),
            firstReadbackElapsedRealtimeMs = first,
            secondReadbackElapsedRealtimeMs = second,
            issuedElapsedRealtimeMs = second + 1,
        )
        val stock = RestoreGuardianCrypto.signStockConfirmedReceipt(stockReceipt, sessionSigner)
        val exitedReceipt = RestoreGuardianExitedReceipt(
            helperBuildIdentity = value.helperBuildIdentity,
            bootIdentity = value.bootIdentity,
            generation = value.generation,
            requestId = value.requestId,
            transactionId = value.transactionId,
            taskNonce = value.taskNonce,
            envelopeSha256 = value.digestSha256(),
            sessionKeySha256 = ready.sessionKey.sha256(),
            verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(value.restoreEntries),
            stockConfirmedReceiptSha256 = stockReceipt.digestSha256(),
            issuedElapsedRealtimeMs = stockReceipt.issuedElapsedRealtimeMs + 1,
        )
        return GuardianTerminalLoad.Received(
            stock = stock,
            exited = RestoreGuardianCrypto.signExitedReceipt(exitedReceipt, sessionSigner),
        )
    }

    private fun envelope() = RestoreGuardianEnvelope(
        helperBuildIdentity = "helper-build-1",
        tweakerCertificateSha256 = hash("certificate"),
        hardwareFingerprint = "hardware-1",
        deviceProfile = "rp5",
        bootIdentity = "boot-1",
        generation = 7,
        requestId = "request-7",
        transactionId = "transaction-7",
        taskNonce = nonce("a"),
        topologySha256 = hash("topology"),
        snapshotSha256 = hash("snapshot"),
        planSha256 = hash("plan"),
        hardDeadlineElapsedRealtimeMs = 99,
        restoreEntries = listOf(RestoreGuardianFanDeleteEntry(applyOrdinal = 0)),
    )

    private fun record(
        envelope: RestoreGuardianEnvelope,
        phase: TransactionPhase,
        readySessionSha256: String? = null,
        terminalReceiptSha256: String? = null,
        mutationBoundary: Int = 0,
        restoreBoundary: Int = 0,
    ): TransactionJournalRecord = TransactionJournalRecord(
        schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5,
        generation = envelope.generation,
        requestId = envelope.requestId,
        transactionId = envelope.transactionId,
        hardwareFingerprint = envelope.hardwareFingerprint,
        deviceProfile = envelope.deviceProfile,
        planHash = envelope.planSha256,
        profileId = "balanced",
        targetPackage = "example.game",
        phase = phase,
        creationTime = 1,
        requestDeadline = 2,
        leaseDeadline = 3,
        bootIdentity = envelope.bootIdentity,
        snapshot = HardwareSnapshot(
            HARDWARE_SNAPSHOT_SCHEMA,
            envelope.hardwareFingerprint,
            envelope.bootIdentity,
            emptyList(),
        ),
        expectedTopologySha256 = envelope.topologySha256,
        snapshotSha256 = envelope.snapshotSha256,
        lastCompletedMutationBoundary = mutationBoundary,
        lastCompletedRestoreBoundary = restoreBoundary,
        guardianBinding = GuardianJournalBinding(
            envelopeSha256 = envelope.digestSha256(),
            taskNonce = envelope.taskNonce,
            helperBuildIdentity = envelope.helperBuildIdentity,
            tweakerCertificateSha256 = envelope.tweakerCertificateSha256,
            hardDeadlineElapsedRealtimeMs = envelope.hardDeadlineElapsedRealtimeMs,
            readySessionSha256 = readySessionSha256,
            terminalReceiptSha256 = terminalReceiptSha256,
        ),
    )

    private fun nonce(seed: String): String = seed.repeat(32)

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private class ExecutorJournal(initial: TransactionJournalRecord? = null) : TransactionJournal {
        var record: TransactionJournalRecord? = initial
        var writes = 0
        var clears = 0
        var lastSettled: TransactionJournalRecord? = null

        override fun load(): JournalLoadResult =
            record?.let(JournalLoadResult::Loaded) ?: JournalLoadResult.Empty

        override fun nextGeneration(): GenerationResult = GenerationResult.Failed("unused")

        override fun persist(record: TransactionJournalRecord): Boolean {
            writes++
            this.record = record
            if (record.phase == TransactionPhase.GUARDIAN_SETTLED) lastSettled = record
            return true
        }

        override fun clear(expectedTransactionId: String): Boolean {
            val current = record ?: return true
            if (current.transactionId != expectedTransactionId) return false
            clears++
            record = null
            return true
        }
    }

    private class ExecutorArmStore(
        private var load: GuardianArmStoreLoad,
        private var presence: GuardianArmStorePresence,
    ) : RestoreGuardianArmStore {
        var clears = 0

        override fun arm(signed: SignedRestoreGuardianEnvelope) = GuardianArmStoreWrite.Rejected
        override fun load(expected: RestoreGuardianExpectedIdentity): GuardianArmStoreLoad = load
        override fun pinSessionKey(
            expected: RestoreGuardianExpectedIdentity,
            sessionKey: GuardianSessionPublicKey,
            signedReady: SignedRestoreGuardianReadyReceipt,
            nowElapsedRealtimeMs: Long,
        ) = GuardianSessionPinWrite.Rejected
        override fun presence(): GuardianArmStorePresence = presence
        override fun clear(): Boolean {
            clears++
            load = GuardianArmStoreLoad.Empty
            presence = GuardianArmStorePresence.ABSENT
            return true
        }
    }

    private companion object {
        /** A named step may only ever accompany a retain — never a block, never a grant. */
        val RETAIN_DISPOSITIONS = setOf(
            GuardianTransactionReconciliation.JOURNAL_ONLY_RETAIN,
            GuardianTransactionReconciliation.ARMING_RETAIN,
            GuardianTransactionReconciliation.READY_RETAIN,
            GuardianTransactionReconciliation.SETTLED_RETAIN,
        )
    }
}
