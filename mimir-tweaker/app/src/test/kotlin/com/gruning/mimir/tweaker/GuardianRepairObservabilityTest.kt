package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host contract for [GuardianRepairObservability] — the seam that stops the guardian supervisor's
 * repair reason (and the attempt/settlement codes around it) from being discarded in Production.
 * Before this seam existed, `BuildConfig.DIAGNOSTICS_COMPILED` gated the plain
 * `SUPERVISOR repair reason=...` line in `MimirTweakerService`, identical to the restore-reason
 * defect [GuardianRestoreObservability] already fixes. Concretely: `TARGET_REPAIR_RATE_LIMITED` is
 * a *restore* reason reached by exhausting the repair budget
 * (`HardwareSessionSupervisor.admitTargetRepair`) — in Production you could see the resulting
 * restore but not the repairs that led to it, breaking the causal chain.
 *
 * [GuardianRepairObservability.onRepairDecided] takes `diagnosticsCompiled` as a parameter instead
 * of reading `BuildConfig.DIAGNOSTICS_COMPILED` itself, for the same reason
 * [GuardianRestoreObservabilityTest] documents: the real flag is fixed per product flavor at
 * compile time and cannot be flipped at runtime within one test task, so a parameter is what lets
 * this one JVM test process exercise the Production (`false`) branch.
 *
 * Each test uses a decision with unique generation/transactionId values so assertions never depend
 * on `lastRecorded`'s state from a prior test in this JVM (Kotlin `object`s are singletons for the
 * life of the test process).
 */
class GuardianRepairObservabilityTest {

    private fun repairDecision(generation: Long, transactionId: String) = SupervisorDecision.RepairCpuTarget(
        generation = generation,
        transactionId = transactionId,
        // The only reason HardwareSessionSupervisor.admitTargetRepair ever constructs today — see
        // the sole repairable-drift branch there.
        reason = "TARGET_CONTRACT_DRIFT_CPU",
    )

    private fun result(code: String, restored: Boolean = false) = TransactionResult(
        success = true,
        applied = true,
        restored = restored,
        code = code,
        message = "test",
    )

    // --- Mandatory test 1: the whole point of the change. Diagnostics OFF is the Production
    // posture (BuildConfig.DIAGNOSTICS_COMPILED == false there) and the reason must still reach
    // the seam. This is the test the differential-revert proof targets. ---
    @Test
    fun `repair reason still reaches the seam with diagnostics DISABLED -- the Production case`() {
        val decision = repairDecision(generation = 7L, transactionId = "txn-production-repair")
        val attempt = result("TARGET_REPAIR_APPLIED")
        val settlement = result("TARGET_REPAIR_SETTLED")

        GuardianRepairObservability.onRepairDecided(
            decision = decision,
            attempt = attempt,
            settlement = settlement,
            diagnosticsCompiled = false,
            labEvidenceEnabled = { false },
            logLabEvidence = {},
        )

        assertEquals(decision, GuardianRepairObservability.lastRecorded)
        assertEquals("TARGET_CONTRACT_DRIFT_CPU", GuardianRepairObservability.lastRecorded?.reason)
    }

    // --- Mandatory test 2: LAB-only evidence logging remains gated and must NOT fire when
    // diagnostics are off -- even when LAB's own gate would otherwise allow it. Proves the
    // diagnostics gate still wins (the `&&` ordering), not just that one arm happens to be false.
    // Mirrors GuardianRestoreObservabilityTest's equivalent proof for restores. ---
    @Test
    fun `LAB evidence stays gated and does not fire when diagnostics are off`() {
        val decision = repairDecision(generation = 9L, transactionId = "txn-lab-gate")
        var labEvidenceCalls = 0

        GuardianRepairObservability.onRepairDecided(
            decision = decision,
            attempt = null,
            settlement = null,
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
        val decision = repairDecision(generation = 11L, transactionId = "txn-lab-fires")
        val attempt = result("TARGET_REPAIR_APPLIED")
        val settlement = result("TARGET_REPAIR_SETTLED", restored = false)
        val labEvidenceMessages = mutableListOf<String>()

        GuardianRepairObservability.onRepairDecided(
            decision = decision,
            attempt = attempt,
            settlement = settlement,
            diagnosticsCompiled = true,
            labEvidenceEnabled = { true },
            logLabEvidence = { labEvidenceMessages.add(it) },
        )

        assertEquals(1, labEvidenceMessages.size)
        assertTrue(labEvidenceMessages.single().contains("event=target_repair reason=TARGET_CONTRACT_DRIFT_CPU"))
        assertTrue(labEvidenceMessages.single().contains("generation=11 transaction=txn-lab-fires"))
    }

    @Test
    fun `LAB evidence does not fire when diagnostics are on but LAB itself declines`() {
        val decision = repairDecision(generation = 13L, transactionId = "txn-lab-declines")
        var labEvidenceCalls = 0

        GuardianRepairObservability.onRepairDecided(
            decision = decision,
            attempt = null,
            settlement = null,
            diagnosticsCompiled = true,
            labEvidenceEnabled = { false },
            logLabEvidence = { labEvidenceCalls++ },
        )

        assertEquals(0, labEvidenceCalls)
    }

    // --- Supporting: the pure formatter, checked directly -- reason/attempt/settlement codes plus
    // identifiers only. ---
    @Test
    fun `describe is pure and preserves reason, attempt, settlement, generation, and transaction id exactly`() {
        val decision = repairDecision(generation = 555L, transactionId = "txn-describe")
        val attempt = result("TARGET_REPAIR_APPLIED")
        val settlement = result("TARGET_REPAIR_SETTLED")

        val described = GuardianRepairObservability.describe(decision, attempt, settlement)

        assertEquals(
            "SUPERVISOR repair reason=TARGET_CONTRACT_DRIFT_CPU " +
                "attempt=TARGET_REPAIR_APPLIED settlement=TARGET_REPAIR_SETTLED " +
                "generation=555 transactionId=txn-describe",
            described,
        )
    }

    // --- Supporting: null attempt/settlement (e.g. a repair decision logged before
    // supervisor.execute runs) must not throw and must render plainly. ---
    @Test
    fun `describe handles a null attempt and settlement without throwing`() {
        val decision = repairDecision(generation = 556L, transactionId = "txn-describe-null")

        val described = GuardianRepairObservability.describe(decision, attempt = null, settlement = null)

        assertEquals(
            "SUPERVISOR repair reason=TARGET_CONTRACT_DRIFT_CPU " +
                "attempt=null settlement=null " +
                "generation=556 transactionId=txn-describe-null",
            described,
        )
    }
}
