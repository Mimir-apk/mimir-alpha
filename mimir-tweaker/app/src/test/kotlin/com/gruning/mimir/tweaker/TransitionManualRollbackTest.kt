package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fan-only transitions preserve the running profile; the old restore/re-apply path is retired. */
class TransitionManualRollbackTest {
    @Test
    fun `fan transition cannot restore reapply clear or release the compute session`() {
        val fn = transitionFn()
        for (forbidden in listOf("restoreBoundStamped(", "transaction.apply(", "runApplyWithSessionCommit(",
            "supervisor.clearSession(", "publicOwners.completeApply(", "stopMonitor()",
            "rejectStagedFanOwnership(", "settleSystemWideStockRuntime(")) {
            assertTrue("fan-only transition must not call $forbidden", !fn.contains(forbidden))
        }
    }

    @Test
    fun `contract amendment rejection must reconcile or fail before reporting updated`() {
        val fn = transitionFn()
        val guard = fn.indexOf("if (!supervisor.amendFanContract(")
        val reconcile = fn.indexOf("supervisor.reconcileAppliedContract(record)")
        val failure = fn.indexOf("publicFailure(\"FAN_CONTRACT_DESYNC\"", reconcile.coerceAtLeast(0))
        val success = fn.indexOf("code = \"FAN_CONTROL_UPDATED\"")
        assertTrue("amendment result must be checked", guard >= 0)
        assertTrue("rejected update must use durable contract", reconcile in guard until success)
        assertTrue("unreconciled contract must fail before success", failure in reconcile until success)
    }

    @Test
    fun `fan transition checks signed authority before the first handoff`() {
        val fn = transitionFn()
        val authority = fn.indexOf("transaction.fanOwnershipTransitionAdmitted(")
        val handoff = fn.indexOf("handFanToVendorSmartBound()")
        assertTrue(authority in 0 until handoff)
        assertTrue(fn.contains("transaction.transitionAppliedFanBound(active.generation, active.transactionId, targetFanMode)"))
    }

    @Test
    fun `a failed entry can return only the fan to its still durable Smart target`() {
        val fn = transitionFn().substringAfter("if (!result.success)")
        assertTrue(fn.contains("amended.fanMode == TuningPlanner.FAN_SMART"))
        assertTrue(fn.contains("handFanToVendorSmartBound()"))
        assertTrue(fn.contains("publicFailure(result.code, result.message"))
    }

    @Test
    fun `the amended contract preserves scope identity and lease instead of re-adopting`() {
        val fn = transitionFn()
        assertTrue(fn.contains("supervisor.amendFanContract(active.generation, active.transactionId, amended)"))
        assertTrue(!fn.contains("adoptAppliedSession("))
        assertTrue(!fn.contains("beginActiveTransition("))
    }

    private fun transitionFn(): String = sliceBetween(
        mainSourceText("MimirTweakerService.kt"),
        "private fun transitionFanControlModeOnWorker(",
        "private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(",
    ).lineSequence().joinToString("\n") { it.substringBefore("//") }

    private fun mainSourceText(name: String): String {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val file = File(root, name)
        if (!file.isFile) throw AssertionError("$name not found under $root")
        return file.readText()
    }

    private fun sliceBetween(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = text.indexOf(endMarker, start)
        require(end > start) { "end marker not found after start: $endMarker" }
        return text.substring(start, end)
    }
}
