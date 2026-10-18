package com.gruning.mimir.tweaker

import java.io.File
import java.util.concurrent.ExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S9 "V10 serialization and truth". The three v10 ceiling calls ran directly on Binder threads: an unbounded privileged
 * write held the actuator's lock and blocked later ceiling queries, withdrawals and named-profile precedence before they
 * reached the bounded worker; a query could repair hardware; a query that threw answered NotHeld; and the profile's
 * ceiling check ran before its apply reached the worker, so a ceiling apply could land in the gap.
 *
 * The behaviour is pinned where it can be run (the failure mapping, the gate); the wiring in [MimirTweakerService],
 * which has no host-test seam, is pinned as source text.
 */
class CeilingWorkerSerializationTest {

    // ---- the failure a ceiling call answers when the worker could not run it ----

    @Test
    fun `a ceiling call the worker did not answer is SERVICE_BUSY, and says whether its effect is unknown`() {
        val started = CeilingWorkerFailure.refusal(TransactionWorkerBusyException("SERVICE_BUSY: running", started = true))
        assertEquals(SERVICE_BUSY_CODE, started.code)
        assertTrue(started.detail, started.detail.startsWith("UNCONFIRMED"))

        val withdrawn = CeilingWorkerFailure.refusal(TransactionWorkerBusyException("SERVICE_BUSY: queued", started = false))
        assertEquals(SERVICE_BUSY_CODE, withdrawn.code)
        assertTrue(withdrawn.detail, withdrawn.detail.contains("nothing was changed"))
    }

    @Test
    fun `a ceiling call that threw is an explicit UNCONFIRMED refusal, never NotHeld`() {
        val refused = CeilingWorkerFailure.refusal(ExecutionException(IllegalStateException("probe blew up")))

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE, refused.code)
        assertTrue(refused.detail, refused.detail.startsWith("UNCONFIRMED"))
        assertTrue(refused.detail, refused.detail.contains("IllegalStateException"))
        // The wire outcome a client reads as "unavailable", not as "no ceiling is held".
        assertEquals(TweakerDiscoveredCeilingContract.OUTCOME_REFUSED, (refused as CeilingOutcome).wireOutcome())
        assertNotEquals(TweakerDiscoveredCeilingContract.OUTCOME_NOT_HELD, refused.wireOutcome())
    }

    @Test
    fun `a worker timeout in the profile gate is the daemon being busy, not a ceiling state`() {
        val code = CeilingPrecedenceGate.refusalCode(
            evaluate = { throw TransactionWorkerBusyException("SERVICE_BUSY: still running", started = true) },
            log = {},
        )
        assertEquals(SERVICE_BUSY_CODE, code)
        assertEquals("The Tweaker is busy; the profile was not applied.", CeilingPrecedenceGate.refusalMessage(SERVICE_BUSY_CODE))
    }

    // ---- the wiring ----

    /** Every public refusal that comes out of the ceiling gate speaks through the gate's own sentence, never its own. */
    @Test
    fun `every profile-apply ceiling refusal takes its message from the gate and none hard-codes a claim`() {
        val service = source("MimirTweakerService.kt")
        assertEquals(
            "the V3, V4, V4-stock-select and in-block recheck refusals each name the gate's sentence",
            4,
            Regex("CeilingPrecedenceGate\\.refusalMessage\\(code\\)").findAll(service).count(),
        )
        assertFalse(
            "no profile refusal may promise the ceiling check changed nothing",
            service.contains("nothing was changed"),
        )
    }


    @Test
    fun `every v10 ceiling call runs on the transaction worker and none answers NotHeld on a throw`() {
        val service = source("MimirTweakerService.kt")
        listOf(
            "override fun applyDiscoveredCeilingV10(" to "ceilingOnTransactionWorker { discoveredCeiling.apply(axis, policyId, ceilingKhz) }",
            "override fun withdrawDiscoveredCeilingV10(" to "ceilingOnTransactionWorker { discoveredCeiling.withdraw() }",
            "override fun queryDiscoveredCeilingV10(" to "ceilingOnTransactionWorker { discoveredCeiling.query() }",
        ).forEach { (signature, call) ->
            val body = service.substringAfter(signature).substringBefore("\n        }\n")
            assertTrue("$signature must run its actuator call on the worker: $body", body.contains(call))
        }
        assertFalse(
            "a query that throws must never read as NotHeld",
            service.contains("runCatching { discoveredCeiling.query() }.getOrDefault(CeilingOutcome.NotHeld)"),
        )
        assertEquals(
            "the actuator's apply, withdraw and query are reached only through the worker",
            1,
            Regex("discoveredCeiling\\.apply\\(").findAll(service).count(),
        )
        val helper = service.substringAfter("private fun ceilingOnTransactionWorker(").substringBefore("\n        }\n")
        assertTrue(helper.contains("onTransactionWorker(block)"))
        assertTrue(helper.contains("CeilingWorkerFailure.refusal(error)"))
    }

    @Test
    fun `the profile's ceiling precedence runs on the worker, ahead of the apply`() {
        val service = source("MimirTweakerService.kt")
        val gate = service.substringAfter("private fun ceilingPrecedenceFailureCode()").substringBefore("\n    )\n")
        assertTrue(gate.contains("onTransactionWorker { discoveredCeiling.withdrawBeforeProfileApply() }"))
    }

    /**
     * The check/use race: a named profile passed the ceiling check on a Binder thread, a v10 apply landed before the
     * profile's block reached the worker, and the profile then captured the cap as stock. The apply's own block now
     * re-checks, on the worker and ahead of any capture.
     */
    @Test
    fun `the apply block rechecks the ceiling on the worker before it captures anything`() {
        val service = source("MimirTweakerService.kt")
        val block = service.substringAfter("private fun runApplyWithSessionCommit(").substringBefore("private fun thermalTelemetryForAdmission(")
        val recheck = block.indexOf("ceilingRecheckFailureCode()")
        val capture = block.indexOf("labPreMutationBaselineHandoff.captureBeforeApply(")
        val body = block.indexOf("val result = applyBody(")
        assertTrue("the apply block must recheck the ceiling", recheck >= 0)
        assertTrue("the recheck must run before any capture: $recheck vs $capture", recheck in 0 until capture)
        assertTrue("and before the apply body", recheck in 0 until body)
        assertTrue(
            "the refusal is a TransactionResult carrying the gate's own code and message",
            block.contains("CeilingPrecedenceGate.refusalMessage(code)"),
        )
        val recheckFn = service.substringAfter("private fun ceilingRecheckFailureCode()").substringBefore("\n    )\n")
        assertTrue(recheckFn.contains("discoveredCeiling.recordedCeilingBeforeCapture()"))
    }

    private fun source(fileName: String): String = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
        File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Tweaker source $fileName not found")
}
