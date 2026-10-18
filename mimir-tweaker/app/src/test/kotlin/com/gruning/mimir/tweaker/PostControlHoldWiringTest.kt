package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * source-text pins for the three service
 * sites the hold depends on and that `MimirTweakerService` has no host-test seam for (the same
 * posture as [PostControlWatchDryRunTest]): the watch tick acts through the controller, a session
 * apply yields a live hold BEFORE it can capture, and a daemon start arms the watch over a stale
 * marker. The behaviour itself is covered in [PostControlHoldControllerTest] and
 * [PostControlHoldDeviceTest].
 */
class PostControlHoldWiringTest {

    @Test
    fun `the watch tick hands its sample to the hold controller behind the dry-run re-check`() {
        val fn = sliceBetween(service(), "private fun startPostControlWatch(", "private fun stopPostControlWatch(")
            .lineSequence().joinToString("\n") { it.substringBefore("//") }
        val guard = fn.indexOf("if (supervisor.activeSession() != null || dryRun()) return@onTransactionWorkerSettlingLate null")
        val tick = fn.indexOf("postControlHoldController().tick(sample)")
        assertTrue("the dry-run re-check must precede the controller tick", guard in 0 until tick)
        for (write in listOf("enterPostControlHold()", "correctPostControlHold()", "releasePostControlHold()", "forceCoolingSmartReassert()")) {
            assertTrue("the controller must be built on $write", fn.contains("directHardwareCommands?.$write"))
        }
    }

    /** Policy A, re-check leftover: the hold is asked again on the worker, before the yield and the capture. */
    @Test
    fun `a hold that enters between admission and the worker step is refused before the capture`() {
        val fn = sliceBetween(service(), "private fun runApplyWithSessionCommit(", "private fun ")
        val cancel = fn.indexOf("code = \"REQUEST_CANCELLED\"")
        val recheck = fn.indexOf("if (!dryRun() && postControlHoldController().isHeld()) {", cancel)
        val refuse = fn.indexOf("code = POST_CONTROL_HOLD_ACTIVE_CODE,", recheck)
        val yield = fn.indexOf("postControlHoldController().yieldToSession() == PostControlHoldYield.RELEASE_FAILED")
        assertTrue("the re-check runs on the worker, after the cancel check", cancel in 0 until recheck)
        assertTrue("a held hold refuses with the admission's own code", recheck in 0 until refuse)
        assertTrue("the re-check precedes the yield, so a held hold is never released here", refuse in 0 until yield)
        assertTrue("the admission asks first", fn.indexOf("if (!dryRun() && postControlHoldController().isHeld()) {") in 0 until cancel)
    }

    @Test
    fun `a session apply yields a live hold on the worker before the capture and declines when it cannot`() {
        val fn = sliceBetween(service(), "private fun runApplyWithSessionCommit(", "private fun ")
        val cancel = fn.indexOf("code = \"REQUEST_CANCELLED\"")
        val yield = fn.indexOf("postControlHoldController().yieldToSession() == PostControlHoldYield.RELEASE_FAILED")
        val decline = fn.indexOf("code = POST_CONTROL_HOLD_RELEASE_FAILED_CODE")
        val precedence = fn.indexOf("ceilingRecheckFailureCode()")
        val body = fn.indexOf("applyBody(operationCapabilities")
        assertTrue("yield after the cancel check", cancel in 0 until yield)
        assertTrue("a failed release declines the apply", yield in 0 until decline)
        assertTrue("yield before the precedence gate and the capture", decline in 0 until precedence && precedence < body)
        assertTrue("dry run never touches the fan", fn.contains("if (!dryRun() && postControlHoldController().yieldToSession()"))
    }

    @Test
    fun `a daemon start arms the watch over a stale hold marker, right after the ceiling keeper`() {
        val text = service()
        val keeper = text.indexOf("        resumeDiscoveredCeilingKeeper()\n")
        // Searched from the keeper call: beginPostControlHoldRelease() calls resumePostControlHold() too, earlier in the file.
        val resume = text.indexOf("        resumePostControlHold()\n", keeper)
        assertTrue(keeper in 0 until resume)
        val fn = sliceBetween(text, "private fun resumePostControlHold(", "    /**")
        val markerCheck = fn.indexOf("if (!postControlHoldMarker.exists()) return")
        val unreleasable = fn.indexOf("val unreleasable = postControlHoldUnreleasableReason()")
        val line = fn.indexOf("postControlWatch=stale_hold_marker_unreleasable reason=\$unreleasable")
        val promoted = fn.indexOf("postControlWatch=running reason=hold_promoted")
        val arm = fn.indexOf("startPostControlWatch()")
        assertTrue("marker check, then releasability, then the one line, before any arming", markerCheck in 0 until unreleasable && unreleasable < line && line < arm)
        assertTrue("the unreleasable branch returns, never arms", fn.indexOf("return", line) in line until arm)
        assertTrue("the promotion's own start names itself instead of a stale marker", promoted in line until arm)
        assertFalse("the armed line must not precede the unreleasable return", fn.substring(0, line).contains("postControlWatch=armed"))
        // The reason reads nothing before unlock and names each case.
        val reason = sliceBetween(text, "private fun postControlHoldUnreleasableReason(", "    /**")
        for (needle in listOf("!BuildConfig.HARDWARE_WRITES_COMPILED -> \"readOnlyBuild\"", "!isUserUnlocked() -> \"locked\"",
            "BuildConfig.DIAGNOSTICS_COMPILED && !credentialStoresReady() -> \"locked\"", "dryRun() -> \"dryRun\"")) {
            assertTrue(needle, reason.contains(needle))
        }
        assertTrue("prefs are read last, behind the unlock and store guards", reason.indexOf("credentialStoresReady()") in 0 until reason.indexOf("dryRun()"))
        // An unreleasable marker is no obligation: the gate opens and nothing re-arms.
        val absent = sliceBetween(text, "private fun postControlHoldObligationAbsent(", "    /**")
        assertTrue(absent.contains("return postControlHoldUnreleasableReason() != null"))
    }

    @Test
    fun `a hold is a started obligation -- the action, the terminal truth, the re-arm, and the disarm settlement`() {
        val text = service()
        // onStartCommand serves the boot receiver's (and the service's own) action.
        val start = sliceBetween(text, "override fun onStartCommand(", "private fun ")
        assertTrue(start.contains("if (intent?.action == ACTION_POST_CONTROL_HOLD_RELEASE) beginPostControlHoldRelease()"))
        // Entering a hold promotes the service to a sticky started foreground lifetime; a cleared marker settles it.
        val creator = sliceBetween(text, "private fun postControlHoldController(", "private fun stopPostControlWatch(")
        assertTrue(creator.contains("onHoldObligation = ::promotePostControlHoldLifetime"))
        assertTrue(creator.contains("onObligationCleared = ::requestTerminalSettlement"))
        val promote = sliceBetween(text, "private fun promotePostControlHoldLifetime(", "private fun ")
        assertTrue(promote.indexOf("ensureForeground(") in 0 until promote.indexOf("startService(Intent(this, MimirTweakerService::class.java).setAction(ACTION_POST_CONTROL_HOLD_RELEASE))"))
        // The terminal truth carries the obligation (a live hold OR the marker), read from the cache on main.
        val truth = sliceBetween(text, "private fun terminalTruthNow(", "private fun ")
        assertTrue(truth.contains("postControlHoldInactive = postControlHoldObligationAbsent()"))
        val absent = sliceBetween(text, "private fun postControlHoldObligationAbsent(", "    /**")
        assertTrue(absent.contains("postControlThermalWatch.isHolding()"))
        assertTrue(absent.contains("postControlHoldMarker.isPresentCached()"))
        val gate = mainSourceText("StartedServiceLifetime.kt")
        assertTrue(gate.contains("truth.postControlHoldInactive"))
        // The settlement pass re-arms a watch that is missing over an obligation, and never stops the service instead.
        val settle = sliceBetween(text, "private fun evaluateTerminalSettlement(", "private fun ")
        val rearm = settle.indexOf("if (!truth.postControlHoldInactive && postControlFuture == null && truth.supervisorSessionAbsent)")
        val stop = settle.indexOf("StartedServiceTerminalGate.stopStartIdOrNull(")
        assertTrue(rearm in 0 until stop)
        assertTrue(settle.indexOf("startPostControlWatch()", rearm) in rearm until stop)
        // A stopped watch asks for settlement (the disarm is one caller), so a started lifetime whose last
        // obligation it was can end; the tick's own stop stays the one-liner PostControlTickLateSettlementTest pins.
        val stopFn = sliceBetween(text, "private fun stopPostControlWatch(", "private fun ")
        assertTrue(stopFn.indexOf("postControlExecutor = null") in 0 until stopFn.indexOf("requestTerminalSettlement()"))
        val watchFn = sliceBetween(text, "private fun startPostControlWatch(", "private fun stopPostControlWatch(")
        assertTrue(watchFn.contains("stopWatch = { if (postControlExecutor === executor) stopPostControlWatch() },"))
        // The boot receiver reads the marker and plans the action.
        val receiver = mainSourceText("TweakerBootReceiver.kt")
        assertTrue(receiver.contains("postControlHoldMarkerPresent(protectedContext.filesDir)"))
        assertTrue(receiver.contains("postControlHoldPresent && hardwareWritesCompiled &&"))
        assertTrue(receiver.contains("trigger != RecoveryTrigger.LOCKED_BOOT_COMPLETED -> TweakerBootDispatchPlan("))
    }

    @Test
    fun `stopping the watch while holding is never silent`() {
        val fn = sliceBetween(service(), "private fun stopPostControlWatch(", "private fun ")
        assertTrue(fn.contains("val holding = postControlThermalWatch.isHolding()"))
        assertTrue(fn.contains("postControlHoldMarker.isPresentCached()"))
        assertTrue(fn.contains("if (holding || markerPresent)"))
        assertTrue(fn.contains("POST_CONTROL_HOLD watch_stopped holding=\$holding marker=\$markerPresent"))
    }

    @Test
    fun `the port's hold writes are the existing fan shapes, each behind its own live-mode guard`() {
        val port = mainSourceText("AndroidTransactionPorts.kt")
        val enter = sliceBetween(port, "internal fun enterPostControlHold(", "private fun postControlHoldHandBack(")
        assertTrue(enter.indexOf("!= TuningPlanner.FAN_SMART") in 0 until enter.indexOf("settings put system fan_mode \${TuningPlanner.FAN_MANUAL}"))
        assertTrue(enter.indexOf("sleeper(FAN_ENTRY_VENDOR_REACTION_MS)") in 0 until enter.indexOf("echo \$period>\${nodes.dutyPath}"))
        assertTrue(enter.contains("mode != TuningPlanner.FAN_MANUAL || duty != period"))
        val release = sliceBetween(port, "internal fun releasePostControlHold(", "    /**")
        assertTrue(release.indexOf("!= TuningPlanner.FAN_MANUAL") in 0 until release.indexOf("handFanToVendorSmartForRenewal()"))
        val correct = sliceBetween(port, "internal fun correctPostControlHold(", "    /**")
        assertTrue(correct.indexOf("!= TuningPlanner.FAN_MANUAL") in 0 until correct.indexOf("echo \$period>\${nodes.dutyPath}"))
        assertTrue("a correction never writes the mode", !correct.contains("settings put system fan_mode"))
    }

    private fun service(): String = mainSourceText("MimirTweakerService.kt")

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
        val end = text.indexOf(endMarker, start + startMarker.length)
        require(end > start) { "end marker not found after start: $endMarker" }
        return text.substring(start, end)
    }
}
