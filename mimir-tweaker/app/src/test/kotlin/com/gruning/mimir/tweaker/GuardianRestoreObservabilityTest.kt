package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host contract for [GuardianRestoreObservability] — the seam that stops the guardian supervisor's
 * restore reason from being discarded in Production. Before this seam existed,
 * `BuildConfig.DIAGNOSTICS_COMPILED` gated the entire restore-log block in `MimirTweakerService`,
 * including the plain reason line, so a real profile rollback on a shipping build could not be
 * attributed from logcat.
 *
 * [GuardianRestoreObservability.onRestoreDecided] takes `diagnosticsCompiled` as a parameter
 * instead of reading `BuildConfig.DIAGNOSTICS_COMPILED` itself, precisely so this one JVM test
 * process can exercise both the Production (`false`) and LAB (`true`) branch — the real flag is
 * fixed per product flavor at compile time and cannot be flipped at runtime within one test task.
 *
 * Each test uses a decision with unique generation/transactionId/reason values so assertions never
 * depend on `lastRecorded`'s state from a prior test in this JVM (Kotlin `object`s are singletons
 * for the life of the test process).
 */
class GuardianRestoreObservabilityTest {

    // --- Mandatory test 1: the seam receives the decision, exact reason preserved. ---
    @Test
    fun `restore decision reaches the seam with the exact reason preserved`() {
        val decision = SupervisorDecision.Restore(
            generation = 42L,
            transactionId = "txn-thermal-cpu",
            reason = "THERMAL_CPU",
        )

        GuardianRestoreObservability.onRestoreDecided(
            decision = decision,
            diagnosticsCompiled = true,
            labEvidenceEnabled = { false },
            logLabEvidence = {},
        )

        assertEquals(decision, GuardianRestoreObservability.lastRecorded)
        assertEquals("THERMAL_CPU", GuardianRestoreObservability.lastRecorded?.reason)
    }

    // --- Mandatory test 2: the whole point of the change. Diagnostics OFF is the Production
    // posture (BuildConfig.DIAGNOSTICS_COMPILED == false there) and the reason must still reach
    // the seam. This is the test the differential-revert proof targets. ---
    @Test
    fun `restore reason still reaches the seam with diagnostics DISABLED -- the Production case`() {
        val decision = SupervisorDecision.Restore(
            generation = 7L,
            transactionId = "txn-production-restore",
            reason = "TARGET_LOSS",
        )
        var labEvidenceCalls = 0

        GuardianRestoreObservability.onRestoreDecided(
            decision = decision,
            diagnosticsCompiled = false,
            labEvidenceEnabled = { false },
            logLabEvidence = { labEvidenceCalls++ },
        )

        assertEquals(decision, GuardianRestoreObservability.lastRecorded)
        assertEquals("TARGET_LOSS", GuardianRestoreObservability.lastRecorded?.reason)
        assertEquals(0, labEvidenceCalls)
    }

    // --- Fan-fence fix F4 (2026-10-14): every restore family reaches the ONE reason the companion
    // bundle reads. The supervisor's decision set it before; the service's own restores (the
    // recovery pass that settled gen 51 on the Odin) set nothing the bundle could see. ---
    @Test
    fun `F4 -- a service restore that restored Stock carries its cause into lastRestoreReason`() {
        GuardianRestoreObservability.onServiceRestore(
            cause = "STARTUP_RECOVERY_RESTORE",
            generation = 51L,
            transactionId = "txn-f4-service",
            restored = true,
            code = "RESTORED",
            settlement = "settled_both_durable_stores",
        )
        assertEquals("STARTUP_RECOVERY_RESTORE", GuardianRestoreObservability.lastRestoreReason)
    }

    @Test
    fun `P3-6 -- the generation travels with the reason from both restore families, and a new arm clears both`() {
        GuardianRestoreObservability.onServiceRestore(
            cause = "GUARDIAN_STARTUP_RECOVERY_RESTORE",
            generation = 51L,
            transactionId = "txn-p36-service",
            restored = true,
            code = "RESTORED",
        )
        assertEquals("GUARDIAN_STARTUP_RECOVERY_RESTORE", GuardianRestoreObservability.lastRestoreReason)
        assertEquals(51L, GuardianRestoreObservability.lastRestoreGeneration)
        GuardianRestoreObservability.onRestoreDecided(
            decision = SupervisorDecision.Restore(52L, "txn-p36-decided", "GUARDIAN_TERMINAL_RECEIVED"),
            diagnosticsCompiled = false,
            labEvidenceEnabled = { false },
            logLabEvidence = {},
        )
        assertEquals("GUARDIAN_TERMINAL_RECEIVED", GuardianRestoreObservability.lastRestoreReason)
        assertEquals(52L, GuardianRestoreObservability.lastRestoreGeneration)
        GuardianRestoreObservability.clearLastRestore()
        assertEquals(null, GuardianRestoreObservability.lastRestoreReason)
        assertEquals(null, GuardianRestoreObservability.lastRestoreGeneration)
    }

    @Test
    fun `P3-6 -- a recovery restore that reconciles the Guardian's own receipt is a hand-back, judged before the restore`() {
        val source = serviceSource()
        val site = source.substring(source.indexOf("is JournalLoadResult.Loaded -> {", source.indexOf("private fun runRecoveryPass(")))
        val receipt = site.indexOf("val guardianSettled = (guardianTerminalReceipts?.loadUntil(0) as? GuardianTerminalLoad.Received)")
        val restore = site.indexOf("val result = restoreBoundStamped(")
        val cause = site.indexOf("cause = if (guardianSettled) \"GUARDIAN_STARTUP_RECOVERY_RESTORE\" else \"STARTUP_RECOVERY_RESTORE\",")
        assertTrue("the receipt is read BEFORE the restore, which may acknowledge it", receipt in 0 until restore)
        assertTrue("the cause is chosen from that read", restore in 0 until cause)
        assertTrue(
            "the receipt must name this very generation",
            site.substring(receipt, restore).contains("?.stock?.receipt?.generation == loaded.record.generation"),
        )
        assertTrue(GuardianHeartbeatRetirePolicy.isGuardianHandbackReason("GUARDIAN_STARTUP_RECOVERY_RESTORE"))
        assertTrue("a new arm ends the incident", source.contains("GuardianRestoreObservability.clearLastRestore()"))
    }

    @Test
    fun `F4 -- a service restore that did NOT restore leaves the reason alone`() {
        GuardianRestoreObservability.onRestoreDecided(
            decision = SupervisorDecision.Restore(52L, "txn-f4-prior", "GUARDIAN_TERMINAL_RECEIVED"),
            diagnosticsCompiled = false,
            labEvidenceEnabled = { false },
            logLabEvidence = {},
        )
        GuardianRestoreObservability.onServiceRestore(
            cause = "OWNER_DEATH_RESTORE",
            generation = 53L,
            transactionId = "txn-f4-held",
            restored = false,
            code = "CROSS_STORE_RECONCILIATION_REQUIRED",
        )
        assertEquals(
            "a retained hold did not return the hardware to Stock; the player copy must not say it did",
            "GUARDIAN_TERMINAL_RECEIVED",
            GuardianRestoreObservability.lastRestoreReason,
        )
    }

    @Test
    fun `F4 -- a supervisor decision sets the same reason the bundle reads`() {
        GuardianRestoreObservability.onRestoreDecided(
            decision = SupervisorDecision.Restore(54L, "txn-f4-decided", "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE"),
            diagnosticsCompiled = false,
            labEvidenceEnabled = { false },
            logLabEvidence = {},
        )
        assertEquals("GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE", GuardianRestoreObservability.lastRestoreReason)
        assertEquals(GuardianRestoreObservability.lastRecorded?.reason, GuardianRestoreObservability.lastRestoreReason)
    }

    // --- Mandatory test 3: LAB-only evidence logging remains gated and must NOT fire when
    // diagnostics are off -- even when LAB's own gate would otherwise allow it. Proves the
    // diagnostics gate still wins (the `&&` ordering), not just that one arm happens to be false. ---
    @Test
    fun `LAB evidence stays gated and does not fire when diagnostics are off`() {
        val decision = SupervisorDecision.Restore(
            generation = 9L,
            transactionId = "txn-lab-gate",
            reason = "OWNER_TOKEN_DIED",
        )
        var labEvidenceCalls = 0

        GuardianRestoreObservability.onRestoreDecided(
            decision = decision,
            diagnosticsCompiled = false,
            labEvidenceEnabled = { true },
            logLabEvidence = { labEvidenceCalls++ },
        )

        assertEquals(0, labEvidenceCalls)
    }

    // --- Supporting: completes branch coverage of the `diagnosticsCompiled && labEvidenceEnabled()`
    // condition -- proves the LAB path is not permanently dead, i.e. genuinely unchanged behavior. ---
    @Test
    fun `LAB evidence still fires when diagnostics are on and LAB itself allows it -- unchanged behavior`() {
        val decision = SupervisorDecision.Restore(
            generation = 11L,
            transactionId = "txn-lab-fires",
            reason = "HARD_LEASE_EXPIRED",
        )
        val labEvidenceMessages = mutableListOf<String>()

        GuardianRestoreObservability.onRestoreDecided(
            decision = decision,
            diagnosticsCompiled = true,
            labEvidenceEnabled = { true },
            logLabEvidence = { labEvidenceMessages.add(it) },
        )

        assertEquals(1, labEvidenceMessages.size)
        assertTrue(labEvidenceMessages.single().contains("event=guardian_restore reason=HARD_LEASE_EXPIRED"))
        assertTrue(labEvidenceMessages.single().contains("generation=11 transaction=txn-lab-fires"))
    }

    @Test
    fun `LAB evidence does not fire when diagnostics are on but LAB itself declines`() {
        val decision = SupervisorDecision.Restore(
            generation = 13L,
            transactionId = "txn-lab-declines",
            reason = "LEASE_EXPIRED",
        )
        var labEvidenceCalls = 0

        GuardianRestoreObservability.onRestoreDecided(
            decision = decision,
            diagnosticsCompiled = true,
            labEvidenceEnabled = { false },
            logLabEvidence = { labEvidenceCalls++ },
        )

        assertEquals(0, labEvidenceCalls)
    }

    // --- Supporting: the pure formatter, checked directly -- reason/generation/transactionId only. ---
    @Test
    fun `describe is pure and preserves reason, generation, and transaction id exactly`() {
        val decision = SupervisorDecision.Restore(
            generation = 555L,
            transactionId = "txn-describe",
            reason = "TARGET_REPAIR_RATE_LIMITED",
        )

        val described = GuardianRestoreObservability.describe(decision)

        assertEquals(
            "SUPERVISOR restore reason=TARGET_REPAIR_RATE_LIMITED generation=555 transactionId=txn-describe",
            described,
        )
    }

    /**
     * TR-02's last gap, closed. The supervisor carries `expected`/`observed` on a drift Restore, but
     * until this the formatter dropped them, so the two numbers reached the tests and never reached
     * a device.
     *
     * The concrete cost: on 2026-08-22 the Odin livelocked on
     * `TARGET_CONTRACT_DRIFT_GPU_MIN` past generation 1244, and naming the pair — a written `3`
     * against a firmware-reset `6` — took a hand-run sysfs poll on the device, because this
     * line said only that *something* had drifted.
     */
    @Test
    fun `describe names the drifting pair so a device log can be read without a sysfs poll`() {
        val decision = SupervisorDecision.Restore(
            generation = 1244L,
            transactionId = "txn-gpu-min",
            reason = "TARGET_CONTRACT_DRIFT_GPU_MIN",
            expected = "3",
            observed = "6",
        )

        assertEquals(
            "SUPERVISOR restore reason=TARGET_CONTRACT_DRIFT_GPU_MIN generation=1244 " +
                "transactionId=txn-gpu-min expected=3 observed=6",
            GuardianRestoreObservability.describe(decision),
        )
    }

    /** A decision with no drifting pair must not grow an `expected=null observed=null` tail. */
    @Test
    fun `describe omits the drift suffix entirely when the decision carries no pair`() {
        val decision = SupervisorDecision.Restore(
            generation = 7L,
            transactionId = "txn-no-drift",
            reason = "OWNER_DEATH_RESTORE",
        )

        val described = GuardianRestoreObservability.describe(decision)

        assertEquals(
            "SUPERVISOR restore reason=OWNER_DEATH_RESTORE generation=7 transactionId=txn-no-drift",
            described,
        )
        assertFalse("a null pair must never be printed", described.contains("null"))
    }

    // --- New: restore-cascade observability (companion fix to the reason-observability seam
    // above). stopFanControl() nulls latestMonitorFanSample on every Restore; if the active
    // session is not cleared immediately, a later tick's fanControlDecision can rediscover that
    // self-inflicted null and issue a second Restore for a session already restored. Observed
    // on-device: THERMAL_GPU at generation=185, then FAN_TELEMETRY_UNAVAILABLE ~4.5s later, same
    // generation and transactionId. These tests assert the seam labels the second line as a
    // cascade of the first rather than reporting it as an unlabeled, misleading second fault. ---

    // --- Mandatory: the cascade must be distinguishable from an independent restore. This is one
    // of the two tests the differential-revert proof targets. ---
    @Test
    fun `a restore for an already-restored session is labelled as a cascade of the original reason`() {
        val original = SupervisorDecision.Restore(
            generation = 185L,
            transactionId = "txn-cascade-session",
            reason = "THERMAL_GPU",
        )
        val echo = SupervisorDecision.Restore(
            generation = 185L,
            transactionId = "txn-cascade-session",
            reason = "FAN_TELEMETRY_UNAVAILABLE",
        )

        GuardianRestoreObservability.onRestoreDecided(
            decision = original,
            diagnosticsCompiled = true,
            labEvidenceEnabled = { false },
            logLabEvidence = {},
        )
        assertEquals(null, GuardianRestoreObservability.lastCascadeOfReason)

        GuardianRestoreObservability.onRestoreDecided(
            decision = echo,
            diagnosticsCompiled = true,
            labEvidenceEnabled = { false },
            logLabEvidence = {},
        )

        assertEquals(echo, GuardianRestoreObservability.lastRecorded)
        assertEquals("THERMAL_GPU", GuardianRestoreObservability.lastCascadeOfReason)
        val described = GuardianRestoreObservability.describe(
            echo,
            GuardianRestoreObservability.lastCascadeOfReason,
        )
        assertTrue(described.contains("reason=FAN_TELEMETRY_UNAVAILABLE"))
        assertTrue(described.contains("cascadeOf=THERMAL_GPU"))
    }

    // --- Mandatory: THE IMPORTANT ONE. A restore for a session that was NOT already restored must
    // still be reported as a real, unlabeled fault -- proving the cascade fix did not blind the
    // system to a genuine independent failure. Deliberately follows a DIFFERENT session's restore
    // in the same test, so the assertion cannot pass by accident of lastRecorded happening to be
    // null going in. ---
    @Test
    fun `an independent restore on a session that was not already restored is reported as a real fault`() {
        val unrelatedSessionRestore = SupervisorDecision.Restore(
            generation = 1L,
            transactionId = "txn-session-a",
            reason = "OWNER_TOKEN_DIED",
        )
        val independentFault = SupervisorDecision.Restore(
            generation = 2L,
            transactionId = "txn-session-b",
            reason = "FAN_TELEMETRY_UNAVAILABLE",
        )

        GuardianRestoreObservability.onRestoreDecided(
            decision = unrelatedSessionRestore,
            diagnosticsCompiled = true,
            labEvidenceEnabled = { false },
            logLabEvidence = {},
        )
        GuardianRestoreObservability.onRestoreDecided(
            decision = independentFault,
            diagnosticsCompiled = true,
            labEvidenceEnabled = { false },
            logLabEvidence = {},
        )

        assertEquals(independentFault, GuardianRestoreObservability.lastRecorded)
        assertEquals(null, GuardianRestoreObservability.lastCascadeOfReason)
        val described = GuardianRestoreObservability.describe(
            independentFault,
            GuardianRestoreObservability.lastCascadeOfReason,
        )
        assertTrue(described.contains("reason=FAN_TELEMETRY_UNAVAILABLE"))
        assertFalse(described.contains("cascadeOf="))
    }

    // --- Supporting: cascadeReasonFor as a pure function, all four branches. ---
    @Test
    fun `cascadeReasonFor returns the previous reason only when generation and transactionId both match`() {
        val previous = SupervisorDecision.Restore(
            generation = 7L,
            transactionId = "txn-x",
            reason = "TARGET_LOSS",
        )

        assertEquals(
            "TARGET_LOSS",
            GuardianRestoreObservability.cascadeReasonFor(
                decision = SupervisorDecision.Restore(7L, "txn-x", "ANY_REASON"),
                previous = previous,
            ),
        )
        assertEquals(
            null,
            GuardianRestoreObservability.cascadeReasonFor(
                decision = SupervisorDecision.Restore(8L, "txn-x", "ANY_REASON"),
                previous = previous,
            ),
        )
        assertEquals(
            null,
            GuardianRestoreObservability.cascadeReasonFor(
                decision = SupervisorDecision.Restore(7L, "txn-y", "ANY_REASON"),
                previous = previous,
            ),
        )
        assertEquals(
            null,
            GuardianRestoreObservability.cascadeReasonFor(
                decision = SupervisorDecision.Restore(7L, "txn-x", "ANY_REASON"),
                previous = null,
            ),
        )
    }

    // --- Supporting: describe's cascadeOf= suffix, exact string match. ---
    @Test
    fun `describe appends a cascadeOf label only when a cascade reason is supplied`() {
        val decision = SupervisorDecision.Restore(
            generation = 185L,
            transactionId = "txn-cascade-describe",
            reason = "FAN_TELEMETRY_UNAVAILABLE",
        )

        assertEquals(
            "SUPERVISOR restore reason=FAN_TELEMETRY_UNAVAILABLE generation=185 " +
                "transactionId=txn-cascade-describe cascadeOf=THERMAL_GPU",
            GuardianRestoreObservability.describe(decision, cascadeOfReason = "THERMAL_GPU"),
        )
    }

    // --- Service-executed restores: the two families that were silent in every posture. ---

    /**
     * The request-owner Binder died and the service restored immediately. Before 2026-08-11 this emitted
     * nothing, in any posture — so a real rollback looked, from logcat, exactly like no service
     * restore at all. That is what made the 2026-08-10T02:43 Odin capture attribute an owner-death
     * restore to an app-requested settlement.
     */
    @Test
    fun `an owner death restore is attributable`() {
        assertEquals(
            "SERVICE restore reason=OWNER_DEATH_RESTORE generation=42 " +
                "transactionId=txn-owner-death restored=true code=RESTORED",
            GuardianRestoreObservability.describeServiceRestore(
                cause = "OWNER_DEATH_RESTORE",
                generation = 42L,
                transactionId = "txn-owner-death",
                restored = true,
                code = "RESTORED",
            ),
        )
    }

    /** A restarted service found an APPLIED journal record and its recovery pass restored Stock. */
    @Test
    fun `a startup recovery restore is attributable`() {
        assertEquals(
            "SERVICE restore reason=STARTUP_RECOVERY_RESTORE generation=7 " +
                "transactionId=txn-recovery restored=false code=RESTORE_FAILED",
            GuardianRestoreObservability.describeServiceRestore(
                cause = "STARTUP_RECOVERY_RESTORE",
                generation = 7L,
                transactionId = "txn-recovery",
                restored = false,
                code = "RESTORE_FAILED",
            ),
        )
    }

    // --- 2026-08-15: why a failed restore failed. ---

    /**
     * THE regression pin for the stock collapse of 2026-08-15.
     *
     * The incident's entire logcat record was
     * `SERVICE restore reason=OWNER_DEATH_RESTORE generation=249 transactionId=6473af38-… restored=false`.
     * `restored=false` says a restore failed; it does not say why, and the daemon branches on
     * `result.code` forty lines later to decide whether anything will ever retry. That single absent
     * field is why the root cause was UNKNOWN while the device remained at stock for 25 minutes with
     * no in-app way out.
     *
     * Reconstructed here with the incident's real generation and transaction id, and the code the
     * trace names as the leading candidate, so the line this test pins is literally the line that
     * should have been printed that night.
     */
    @Test
    fun `a failed restore says why it failed`() {
        val line = GuardianRestoreObservability.describeServiceRestore(
            cause = "OWNER_DEATH_RESTORE",
            generation = 249L,
            transactionId = "6473af38-0000-0000-0000-000000000000",
            restored = false,
            code = "GUARDIAN_ARM_RETAINED",
        )

        assertEquals(
            "SERVICE restore reason=OWNER_DEATH_RESTORE generation=249 " +
                "transactionId=6473af38-0000-0000-0000-000000000000 restored=false " +
                "code=GUARDIAN_ARM_RETAINED",
            line,
        )
        assertTrue(
            "a failed restore line that omits code= is the 2026-08-15 defect verbatim",
            line.contains("code=GUARDIAN_ARM_RETAINED"),
        )
    }

    /**
     * The journal's persisted `lastFailure` — the string `failAndRestore` writes BEFORE the first
     * rollback command — reaches logcat when a record is already in hand. On 2026-08-15 this value
     * was sitting on disk in generation 249's record, unreadable (package not debuggable, no
     * `dump()`), and would have been destroyed by the app-data clear that was the documented remedy.
     */
    @Test
    fun `a retained journal cause reaches the log`() {
        assertEquals(
            "SERVICE restore reason=STARTUP_RECOVERY_RESTORE generation=249 " +
                "transactionId=txn-249 restored=false code=RESTORE_FAILED " +
                "journalCause=Readback_mismatch_for_cpu.0",
            GuardianRestoreObservability.describeServiceRestore(
                cause = "STARTUP_RECOVERY_RESTORE",
                generation = 249L,
                transactionId = "txn-249",
                restored = false,
                code = "RESTORE_FAILED",
                journalCause = "Readback mismatch for cpu.0",
            ),
        )
    }

    /**
     * Absent is absent. A record with no `lastFailure` must omit the field rather than print an
     * empty one — otherwise "the journal held no cause" and "the caller forgot to pass it" look
     * identical, which is the ambiguity this whole change exists to remove.
     */
    @Test
    fun `no journal cause prints no journalCause field`() {
        listOf(null, "", "   ").forEach { raw ->
            val line = GuardianRestoreObservability.describeServiceRestore(
                cause = "OWNER_DEATH_RESTORE",
                generation = 1L,
                transactionId = "txn-empty",
                restored = false,
                code = "RESTORE_FAILED",
                journalCause = raw,
            )
            assertFalse("raw=`$raw` must not emit an empty journalCause", line.contains("journalCause="))
        }
    }

    // --- The redactor: a journal cause is the one value here that is not closed-vocabulary. ---

    /**
     * `TransactionCoordinator` interpolates an arbitrary `Throwable` message into
     * `"Mutation exception: ${error.message}"`, and a failed sysfs write's message carries the node
     * path. This file's standing disclosure rule is reason plus internal identifiers only — never a
     * package name, path, or other owner-library content — so an absolute path must not survive.
     */
    @Test
    fun `an absolute path never reaches the log`() {
        val redacted = GuardianRestoreObservability.redactJournalCause(
            "Mutation exception: /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq: EACCES",
        )

        assertEquals("Mutation_exception:_<path>_EACCES", redacted)
        assertFalse("`$redacted` still carries a path", redacted!!.contains("/sys"))
    }

    /**
     * A package name is owner-library content by the same rule. `cpu.0` is NOT — it is a mutation
     * logical ID, one dot, and the single most useful token in most causes. The redactor must
     * separate them, or it either leaks packages or destroys the value it exists to preserve.
     */
    @Test
    fun `a package name is redacted but a mutation id survives`() {
        assertEquals(
            "Mutation_<pkg>_target_lost_at_cpu.0",
            GuardianRestoreObservability.redactJournalCause(
                "Mutation com.gruning.mimir.rewrite target lost at cpu.0",
            ),
        )
    }

    /**
     * A fixed sentence with a slash inside a word is not a path. `failAndRestore`'s real detail
     * "Session adoption/monitor failed after APPLIED" must survive intact — a redactor that eats it
     * throws away a genuine cause to defend against a threat that is not there.
     */
    @Test
    fun `a relative slash inside a sentence is not a path`() {
        assertEquals(
            "Session_adoption/monitor_failed_after_APPLIED",
            GuardianRestoreObservability.redactJournalCause(
                "Session adoption/monitor failed after APPLIED",
            ),
        )
    }

    /**
     * Every other value on these lines is one space-free token. A cause with raw spaces or newlines
     * would break `key=value` reading for anything that splits the line — which is how these lines
     * are meant to be read — so whitespace collapses to `_` and the result is bounded.
     */
    @Test
    fun `a journal cause is space free and bounded`() {
        val redacted = GuardianRestoreObservability.redactJournalCause(
            "Apply verification failed:\n  " + "x".repeat(400),
        )!!

        assertFalse("`$redacted` must not contain whitespace", redacted.any { it.isWhitespace() })
        assertTrue("`$redacted` must declare its truncation", redacted.endsWith("~trunc"))
        assertEquals(160 + "~trunc".length, redacted.length)
    }

    // --- Which settlement branch fired: UNKNOWN in the 2026-08-15 trace. ---

    /**
     * `RetainedHold` cancels recovery retry and enters a cross-store hold, so **nothing on the
     * daemon retries**; `RetainedRetry` arms a bounded one. "Is anything still going to try?" is the
     * first question this incident raises, and the trace could only answer it with a leading [D]
     * because the three branches differ solely in in-memory side effects and none of them logged.
     */
    @Test
    fun `the settlement branch is attributable`() {
        assertEquals(
            "SERVICE restore settle=RETAINED_HOLD reason=OWNER_DEATH_RESTORE generation=249 " +
                "transactionId=txn-249 code=GUARDIAN_ARM_RETAINED journalPhase=RESTORING " +
                "journalCause=Failed_to_persist_mutation_boundary_3",
            GuardianRestoreObservability.describeRestoreSettlement(
                settle = "RETAINED_HOLD",
                cause = "OWNER_DEATH_RESTORE",
                generation = 249L,
                transactionId = "txn-249",
                code = "GUARDIAN_ARM_RETAINED",
                journalPhase = TransactionPhase.RESTORING,
                journalCause = "Failed to persist mutation boundary 3",
            ),
        )
    }

    /**
     * A clean settlement is logged too, positively. Silence already meant two different things on
     * this device — "nothing happened" and "everything happened and held" — and inferring a healthy
     * outcome from an absent line is exactly the read that misattributed the 2026-08-10 capture.
     */
    @Test
    fun `a cleared settlement is attested rather than inferred from silence`() {
        assertEquals(
            "SERVICE restore settle=CLEARED reason=EXPLICIT_OWNER_RESTORE generation=12 " +
                "transactionId=txn-clean code=RESTORED journalPhase=none",
            GuardianRestoreObservability.describeRestoreSettlement(
                settle = "CLEARED",
                cause = "EXPLICIT_OWNER_RESTORE",
                generation = 12L,
                transactionId = "txn-clean",
                code = "RESTORED",
                journalPhase = null,
                journalCause = null,
            ),
        )
    }

    /**
     * All three restore families share the `restore reason=` token, so one grep catches every restore
     * the service can perform while the leading subsystem word says which decided it. Losing that
     * shared token would silently reopen the attribution gap for whoever greps the old pattern.
     */
    @Test
    fun `every restore family is caught by one grep`() {
        val supervisor = GuardianRestoreObservability.describe(
            SupervisorDecision.Restore(
                generation = 1L,
                transactionId = "txn-supervisor",
                reason = "HARD_LEASE_EXPIRED",
            ),
        )
        val ownerDeath = GuardianRestoreObservability.describeServiceRestore(
            "OWNER_DEATH_RESTORE", 2L, "txn-a", true, "RESTORED",
        )
        val recovery = GuardianRestoreObservability.describeServiceRestore(
            "STARTUP_RECOVERY_RESTORE", 3L, "txn-b", true, "RESTORED",
        )

        listOf(supervisor, ownerDeath, recovery).forEach {
            assertTrue("`$it` must match the shared grep", it.contains("restore reason="))
        }
        assertTrue(supervisor.startsWith("SUPERVISOR "))
        assertTrue(ownerDeath.startsWith("SERVICE "))
        assertTrue(recovery.startsWith("SERVICE "))
    }

    /**
     * The settlement line joins the same family. It carries `settle=` instead of `reason=` first,
     * so it deliberately shares the looser `restore ` token — a reader grepping for the whole
     * restore story must not have to know two patterns.
     */
    @Test
    fun `the settlement line joins the same grep family`() {
        val settlement = GuardianRestoreObservability.describeRestoreSettlement(
            settle = "RETAINED_RETRY",
            cause = "REQUEST_CANCELLED",
            generation = 4L,
            transactionId = "txn-c",
            code = "RESTORE_FAILED",
            journalPhase = TransactionPhase.RESTORE_FAILED,
            journalCause = null,
        )

        assertTrue("`$settlement` must match the shared grep", settlement.contains("restore "))
        assertTrue(settlement.startsWith("SERVICE "))
    }

    /** The test seam records before the guarded Log call, mirroring lastRecorded. */
    @Test
    fun `the service restore seam records what it logged`() {
        GuardianRestoreObservability.onServiceRestore(
            cause = "OWNER_DEATH_RESTORE",
            generation = 99L,
            transactionId = "txn-seam",
            restored = true,
            code = "RESTORED",
        )

        assertEquals(
            "SERVICE restore reason=OWNER_DEATH_RESTORE generation=99 " +
                "transactionId=txn-seam restored=true code=RESTORED",
            GuardianRestoreObservability.lastServiceRestore,
        )
    }

    // --- Call sites: a required parameter forces a value, not the RIGHT value. ---

    /**
     * Everything above tests pure formatting. None of it can see whether `MimirTweakerService`
     * actually hands over `result.code` — making [GuardianRestoreObservability.describeServiceRestore]'s
     * `code` non-optional forces every call site to pass *something*, and a hardcoded `"RESTORE_FAILED"`
     * would satisfy the compiler while printing a constant instead of the truth. That is the same
     * class of hole as the missing field itself, so it gets its own pin.
     *
     * Source-text assertion, following this module's own precedent
     * ([ManifestControlPermissionContractTest] reads the build script and the manifest the same way).
     * `MimirTweakerService` is an Android `Service` with no host-instantiable seam for these two
     * private paths, so the alternative is no coverage at all.
     */
    @Test
    fun `every service restore call site logs the real result code`() {
        val source = serviceSource()
        val calls = callArguments(source, "GuardianRestoreObservability.onServiceRestore(")

        assertEquals(
            "expected exactly the three service-executed restore families; a new one needs its own review",
            3,
            calls.size,
        )
        calls.forEach { args ->
            // Spelling-independent: the point is that the argument READS a live TransactionResult,
            // not that the local happens to be called `result`. The third family (2026-08-22,
            // FAN_STAGING_REJECT_RESTORE) names its local `restored`, and rewriting it to satisfy
            // a string match would be the test steering the code rather than pinning it.
            assertTrue(
                "a service restore call site must pass a live result code: `$args`",
                Regex("code = [A-Za-z][A-Za-z0-9_]*\\.code").containsMatchIn(args),
            )
            assertFalse(
                "a service restore call site must never hardcode the code: `$args`",
                Regex("code = \"").containsMatchIn(args),
            )
        }
    }

    /**
     * The third family, added 2026-08-22 and reviewed under the tripwire above.
     *
     * `rejectStagedFanOwnership` rolls a **successfully applied** profile back when fan ownership
     * cannot settle, and it did so silently in every posture. That silence is not hypothetical:
     * the Nova ran a full nine-second apply-then-rollback on 2026-08-22 and produced no
     * `restore reason=` line at all, so the rollback had to be reconstructed from foreground-service
     * lifecycle events and a sysfs read. Exactly the failure mode this file's own kdoc describes
     * ("absence of the only restore line was read as absence of a service restore").
     */
    /**
     * The lease a session is armed under, pinned like every other observability seam in this file.
     * An unpinned formatter is how the missing `code=` field shipped in the first place.
     *
     * retired the `uncertified=`/`attended=` tokens this line used to
     * carry, along with the self-expiring lease they described — see
     * [GuardianSessionLeaseObservability]'s own kdoc. This block used to also carry
     * "a certified system session reports the twelve-hour hold and no attended lease" and "a
     * LAUNCH session never claims an attended lease"; both pinned a distinction `describe()` no
     * longer draws (it never branches on `scope`), so their duty is now fully subsumed by this one
     * test plus the SYSTEM-scope, lease-equals-hard-lease case below.
     */
    @Test
    fun `the session lease line states the terms in readable relative durations`() {
        assertEquals(
            "SESSION lease scope=SYSTEM leaseMs=360000 hardLeaseMs=43200000 " +
                "generation=7 transactionId=tx-7",
            GuardianSessionLeaseObservability.describe(
                scope = "SYSTEM",
                generation = 7L,
                transactionId = "tx-7",
                leaseDeadlineElapsedRealtimeMs = 1_360_000L,
                hardLeaseDeadlineElapsedRealtimeMs = 44_200_000L,
                nowElapsedRealtimeMs = 1_000_000L,
            ),
        )
    }

    /**
     * The SYSTEM case where lease and hard lease coincide -- every session's shape now, not just a
     * certified one's, since the lease policy made lease-equals-hard-lease unconditional for SYSTEM.
     */
    @Test
    fun `a SYSTEM session's lease line can state the twelve-hour hold`() {
        val line = GuardianSessionLeaseObservability.describe(
            scope = "SYSTEM",
            generation = 9L,
            transactionId = "tx-9",
            leaseDeadlineElapsedRealtimeMs = 43_200_000L,
            hardLeaseDeadlineElapsedRealtimeMs = 43_200_000L,
            nowElapsedRealtimeMs = 0L,
        )
        assertTrue("lease IS the hard lease", line.contains("leaseMs=43200000 hardLeaseMs=43200000"))
    }

    @Test
    fun `the fan-staging rollback names which fan reason rolled the profile back`() {
        val args = callArguments(serviceSource(), "GuardianRestoreObservability.onServiceRestore(")
            .single { it.contains("FAN_STAGING_REJECT_RESTORE") }

        assertTrue(
            "the rollback must carry the specific fan code, not just the family: `$args`",
            args.contains("FAN_STAGING_REJECT_RESTORE/\$code"),
        )
        assertTrue(
            "and it must be bound to the staged session's own identity: `$args`",
            args.contains("generation = staged.generation") &&
                args.contains("transactionId = staged.transactionId"),
        )
    }

    /**
     * The non-destructive read path for a `lastFailure` that is already on disk. The startup
     * recovery pass has the record in hand; passing its persisted cause is what turns an
     * otherwise-unreadable string (package not debuggable, no `dump()`) into a logcat line on the
     * next service start, without clearing anything.
     */
    @Test
    fun `startup recovery surfaces the journal cause it already holds`() {
        val args = callArguments(serviceSource(), "GuardianRestoreObservability.onServiceRestore(")
            .single { it.contains("STARTUP_RECOVERY_RESTORE") }

        assertTrue(
            "the recovery pass holds the record; it must log the cause the record carries: `$args`",
            args.contains("journalCause = loaded.record.lastFailure"),
        )
    }

    /**
     * The settlement branch must be logged from the settle path itself. Which branch fired decides
     * whether anything on the daemon will retry, and the 2026-08-15 trace could only record it as a
     * leading [D].
     */
    @Test
    fun `the settle path reports which branch it took`() {
        val source = serviceSource()
        val settle = source.substringAfter("private fun settleOwnerBoundRestore(")

        assertTrue(
            "settleOwnerBoundRestore must report its branch",
            settle.contains("GuardianRestoreObservability.onRestoreSettled("),
        )
        listOf("\"CLEARED\"", "\"RETAINED_HOLD\"", "\"RETAINED_RETRY\"").forEach {
            assertTrue("every ExplicitRestoreCommit branch needs a settle token: $it", settle.contains(it))
        }
    }

    /**
     * Each argument list for [marker], paren-balanced so it is independent of line breaks and
     * indentation — the brittleness a naive fixed-window source assertion would carry.
     */
    private fun callArguments(source: String, marker: String): List<String> = buildList {
        var from = source.indexOf(marker)
        while (from >= 0) {
            var depth = 0
            var i = from + marker.length - 1
            val open = i
            do {
                when (source[i]) {
                    '(' -> depth += 1
                    ')' -> depth -= 1
                }
                i += 1
            } while (depth > 0 && i < source.length)
            add(source.substring(open, i))
            from = source.indexOf(marker, i)
        }
    }

    private fun serviceSource(): String = generateSequence(File(System.getProperty("user.dir").orEmpty())) {
        it.parentFile
    }.flatMap { root ->
        sequenceOf(
            File(root, "src/main/kotlin/com/gruning/mimir/tweaker/MimirTweakerService.kt"),
            File(root, "app/src/main/kotlin/com/gruning/mimir/tweaker/MimirTweakerService.kt"),
            File(root, "mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/MimirTweakerService.kt"),
        )
    }.first { it.isFile }.readText()

    /** Same seam contract for the settlement line. */
    @Test
    fun `the settlement seam records what it logged`() {
        GuardianRestoreObservability.onRestoreSettled(
            settle = "CLEARED",
            cause = "OWNER_DEATH_RESTORE",
            generation = 101L,
            transactionId = "txn-settle-seam",
            code = "NO_ACTIVE_SESSION",
            journalPhase = null,
            journalCause = null,
        )

        assertEquals(
            "SERVICE restore settle=CLEARED reason=OWNER_DEATH_RESTORE generation=101 " +
                "transactionId=txn-settle-seam code=NO_ACTIVE_SESSION journalPhase=none",
            GuardianRestoreObservability.lastRestoreSettlement,
        )
    }
}
