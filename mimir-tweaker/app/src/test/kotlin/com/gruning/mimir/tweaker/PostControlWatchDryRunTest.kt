package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dry-run safety fence: `startPostControlWatch` must decline before any hardware write on a
 * dry-run build -- it gated on `HARDWARE_WRITES_COMPILED` only, so a LAB dry-run build (a
 * DIAGNOSTICS build with the dry-run preference on, `dryRun()` defaulting true) could still reach
 * its tick's `forceCoolingSmartReassert()` -- a real full-PWM + mode-4 write while `dryRun()` is
 * true. The dry-run invariant itself also applies to neighbouring hardware-write paths -- the same one `SelectPathManualFanEntryTest`
 * (R2-2, the select path) and `FanManualReentryTest` (P4, reassert re-entry) already pin for their
 * own neighbouring functions. Source-text pin -- `startPostControlWatch` has no host-test seam
 * for `probe`/`directHardwareCommands` today (same gap those files document for their own
 * neighbours).
 */
class PostControlWatchDryRunTest {

    @Test
    fun `queued watch tick rechecks dry run on the hardware worker`() {
        val fn = postControlWatchFn().lineSequence().joinToString("\n") { it.substringBefore("//") }
        val worker = fn.indexOf("onTransactionWorker {")
        val guard = fn.indexOf("if (supervisor.activeSession() != null || dryRun()) return@onTransactionWorker null")
        val write = fn.indexOf("forceCoolingSmartReassert(")
        assertTrue(guard in worker until write)
    }

    @Test
    fun `enabling dry run is serialized and refused while real cooling or recovery is active`() {
        val fn = sliceBetween(mainSourceText("MimirTweakerService.kt"),
            "override fun setDryRun(", "override fun getCalibrationStatus(")
            .lineSequence().joinToString("\n") { it.substringBefore("//") }
        val worker = fn.indexOf("onTransactionWorker {")
        val write = fn.indexOf("putBoolean(KEY_DRY_RUN, enabled)")
        assertTrue(worker in 0 until write)
        for (guard in listOf("supervisor.activeSession() != null", "journal.load() != JournalLoadResult.Empty",
            "monitorFuture != null", "postControlFuture != null", "return@onTransactionWorker false")) {
            assertTrue("missing active-hardware fence: $guard", fn.indexOf(guard) in worker until write)
        }
    }

    @Test
    fun `startPostControlWatch declines on a dry run before forceCoolingSmartReassert can write`() {
        val fn = postControlWatchFn()
        val dryRunIndex = fn.indexOf("if (dryRun())")
        val forceIndex = fn.indexOf("forceCoolingSmartReassert(")
        assertTrue("startPostControlWatch must consult dryRun()", dryRunIndex >= 0)
        assertTrue(
            "forceCoolingSmartReassert must still be reachable from the tick",
            forceIndex >= 0,
        )
        assertTrue(
            "the dryRun() guard must sit before forceCoolingSmartReassert(, not after -- a " +
                "dry-run build must never reach the live full-PWM + mode-4 write",
            dryRunIndex in 0 until forceIndex,
        )
    }

    @Test
    fun `startPostControlWatch logs one line when it declines for dryRun`() {
        val fn = postControlWatchFn()
        assertTrue(
            "the dry-run decline must log postControlWatch=skipped reason=dryRun",
            fn.contains("postControlWatch=skipped reason=dryRun"),
        )
    }

    private fun postControlWatchFn(): String = sliceBetween(
        mainSourceText("MimirTweakerService.kt"),
        "private fun startPostControlWatch(",
        "private fun stopPostControlWatch(",
    )

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
