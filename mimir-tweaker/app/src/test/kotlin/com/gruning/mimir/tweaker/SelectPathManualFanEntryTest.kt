package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host contract for [ManualFanHandoffSource] (D1), [manualFanPlanEligible] (D2's
 * plan-only eligibility gate), and [selectPathFanEntryFallback] (D2's post-MANUAL-failure
 * AUTO-retry decision) -- plus source-text pins for [MimirTweakerService.selectSystemProfileOnWorker]
 * itself, which has no host-test seam for `transaction`/`journal` (see [FanManualReentryTest] /
 * [FanOwnershipRenewalTest] for the seams that DO exist -- both pure-function level, never a live
 * [MimirTweakerService] instance).
 *
 * D3's settle-route pins (`SCHEDULE_RENEWAL`) live in [AdvancedFanControlTest] alongside
 * [fanOwnershipSettleRoute]'s other routing pins; the renewal-begin pin lives in
 * [FanOwnershipRenewalTest] alongside [FanOwnershipRenewalPolicy]'s other pins .
 */
class SelectPathManualFanEntryTest {

    @Test
    fun `AUTO fallback clears the failed apply liveness before its new generation starts`() {
        val source = mainSourceText("MimirTweakerService.kt").lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
        val fallback = source.substringAfter("SelectPathFanEntryFallback.FALLBACK_TO_AUTO -> {")
            .substringBefore("SelectPathFanEntryFallback.RETURN_MANUAL_RESULT")
        val clear = fallback.indexOf("clearAbandonedApplyLiveness()")
        // Fan-fence fix (2026-10-14): the fallback goes through fallbackOrRefuse, which still auto-applies
        // unless the profile was admitted hot as its Manual plan only. The clear must still come first.
        val apply = fallback.indexOf("fallbackOrRefuse(")
        assertTrue("fallback must clear its predecessor's ApplyingWindow", clear in 0 until apply)
        val gate = source.substringAfter("fun fallbackOrRefuse(code: String): TransactionResult {").substringBefore("Log.w(")
        assertTrue("an un-waived fallback still auto-applies", gate.contains("if (!coolerRearmWaived) return autoApply()"))
        val helper = source.substringAfter("private fun clearAbandonedApplyLiveness()")
            .substringBefore("private fun")
        assertTrue(helper.contains("journal.load() == JournalLoadResult.Empty"))
        assertTrue(helper.contains("supervisor.activeSession() == null"))
        assertTrue(helper.contains("manualFanMonitorLiveness.disarm()"))
    }

    // --- D1: ManualFanHandoffSource / ManualFanHandoffStore -----------------------------------

    @Test
    fun `a FreshApply baseline captured for one request is not consumed by a different request's identity`() {
        val store = ManualFanHandoffStore()
        val baseline = ManualFanHandoffBaseline(
            profileId = "balanced",
            ownerRequestId = "R1",
            source = ManualFanHandoffSource.FreshApply,
            observedDuty = 25_000,
            entryDuty = 25_000,
            period = 50_000,
            cpuSafetyTempC = 45,
            minimumDutyPercent = 0,
            capturedElapsedRealtimeMs = 1_000,
        )
        assertTrue(store.prepare(baseline))

        val wrongRequestIdentity = ManualFanHandoffIdentity(
            profileId = "balanced",
            ownerRequestId = "R2",
            source = ManualFanHandoffSource.FreshApply,
        )
        assertNull(
            "an identity minted for a DIFFERENT public request must not consume this baseline " +
                "-- ownerRequestId is unique per request and is what FreshApply relies on for " +
                "protection with no generation/transactionId of its own",
            store.consume(wrongRequestIdentity),
        )
    }

    @Test
    fun `Session keeps the generation and transactionId requires -- FreshApply carries nothing to validate`() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualFanHandoffSource.Session(0, "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualFanHandoffSource.Session(1, "")
        }
        // Does not throw -- a real construction, not merely a compile-time reference.
        val fresh: ManualFanHandoffSource = ManualFanHandoffSource.FreshApply
        assertNotNull(fresh)
        assertEquals(ManualFanHandoffSource.FreshApply, fresh)
    }

    // --- D2: manualFanPlanEligible (plan-only, NON-contract eligibility) ----------------------

    @Test
    fun `manualFanPlanEligible requires a qualifiable profile, the fan axis, the fan node, and a derived Manual plan`() {
        assertTrue(manualFanPlanEligible("balanced", capabilities()))
        assertFalse(
            "stock is not in PRODUCT_QUALIFIABLE_PROFILE_IDS",
            manualFanPlanEligible("stock", capabilities()),
        )
        assertFalse(
            "the fan axis must be supported",
            manualFanPlanEligible(
                "balanced",
                capabilities(supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU)),
            ),
        )
        assertFalse(
            "the fan node must be available",
            manualFanPlanEligible("balanced", capabilities(fanNodeAvailable = false)),
        )
    }

    @Test
    fun `manualFanPlanEligible reads no contract, session, or journal -- same two inputs, same answer`() {
        val body = sliceBetween(
            mainSourceText("AdvancedFanControl.kt"),
            "internal fun manualFanPlanEligible(",
            "internal enum class SelectPathFanEntryFallback {",
        )
        listOf("appliedTargetContract", "ActiveSessionIdentity", "journal.load").forEach { token ->
            assertFalse("manualFanPlanEligible must not read $token", body.contains(token))
        }
        val caps = capabilities()
        assertEquals(
            "no third (contract/session) input exists that could vary the answer for the same " +
                "two arguments",
            manualFanPlanEligible("balanced", caps),
            manualFanPlanEligible("balanced", caps),
        )
    }

    // --- D2: selectPathFanEntryFallback (post-MANUAL-failure AUTO retry decision) -------------

    @Test
    fun `MANUAL failing with an Empty journal falls back to AUTO`() {
        assertEquals(
            SelectPathFanEntryFallback.FALLBACK_TO_AUTO,
            selectPathFanEntryFallback(manualApplied = false, journalEmptyAfterManualFailure = true),
        )
    }

    @Test
    fun `MANUAL failing with a non-Empty journal returns the failure unchanged -- no AUTO retry`() {
        // SystemWideProfileTransition.execute already handles a non-Empty journal on a failed
        // apply (its own verifiedStock()/restore() tail) -- the select path must not race it.
        assertEquals(
            SelectPathFanEntryFallback.RETURN_MANUAL_RESULT,
            selectPathFanEntryFallback(manualApplied = false, journalEmptyAfterManualFailure = false),
        )
    }

    @Test
    fun `an applied MANUAL result is never retried, regardless of journal state`() {
        assertEquals(
            SelectPathFanEntryFallback.RETURN_MANUAL_RESULT,
            selectPathFanEntryFallback(manualApplied = true, journalEmptyAfterManualFailure = true),
        )
    }

    @Test
    fun `selectSystemProfileOnWorker calls selectPathFanEntryFallback inside the runApplyWithSessionCommit lambda`() {
        val fn = selectSystemProfileOnWorkerSource()
        val commitIndex = fn.indexOf("runApplyWithSessionCommit(")
        val fallbackIndex = fn.indexOf("selectPathFanEntryFallback(")
        assertTrue("runApplyWithSessionCommit(...) must be present", commitIndex >= 0)
        assertTrue("selectPathFanEntryFallback(...) must be present", fallbackIndex >= 0)
        assertTrue(
            "the fallback decision must be made inside the applyBody lambda, i.e. after admit",
            fallbackIndex > commitIndex,
        )
    }

    // --- D2: source-text pins on selectSystemProfileOnWorker itself ---------------------------

    @Test
    fun `the select-path hand-to-vendor-Smart write happens inside runApplyWithSessionCommit, after admit`() {
        val fn = selectSystemProfileOnWorkerSource()
        val commitIndex = fn.indexOf("runApplyWithSessionCommit(")
        val handToVendorIndex = fn.indexOf("directHardwareCommands?.handFanToVendorSmartForRenewal()")
        assertTrue("runApplyWithSessionCommit(...) must be present", commitIndex >= 0)
        assertTrue(
            "handFanToVendorSmartForRenewal() must be present in the select path",
            handToVendorIndex >= 0,
        )
        assertTrue(
            "the hand-to-vendor-Smart write must sit AFTER admit -- inside the trailing " +
                "applyBody lambda, never before it",
            handToVendorIndex > commitIndex,
        )
    }

    @Test
    fun `the select-path handoff identity and store are cleared in a finally block around the MANUAL attempt`() {
        val fn = selectSystemProfileOnWorkerSource()
        val manualApplyIndex = fn.indexOf("fanIntent = TuningPlanner.FanIntent.MANUAL_OVERRIDE")
        assertTrue("a MANUAL_OVERRIDE apply must be present", manualApplyIndex >= 0)
        val tail = fn.substring(manualApplyIndex)
        val finallyIndex = tail.indexOf("finally {")
        assertTrue("the MANUAL attempt must be wrapped in a finally block", finallyIndex >= 0)
        val finallyBlock = tail.substring(finallyIndex)
        assertTrue(
            "the handoff store must be cleared in that finally block, regardless of outcome",
            finallyBlock.contains("manualFanHandoffStore.clear()"),
        )
        assertTrue(
            "the identity supplier must be cleared in that finally block, regardless of outcome",
            finallyBlock.contains("manualFanHandoffIdentity = null"),
        )
    }

    // --- fixtures -------------------------------------------------------------------------------

    private fun capabilities(
        supportedAxes: Set<TuningAxis> = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        fanNodeAvailable: Boolean = true,
    ) = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "ayn",
        model = "odin3",
        socModel = "sm8750",
        buildFingerprint = "fp",
        deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        supportedAxes = supportedAxes,
        pServerAvailable = true,
        cpuPolicies = emptyList(),
        gpuFrequenciesKhz = emptyList(),
        gpuNodeAvailable = true,
        fanNodeAvailable = fanNodeAvailable,
        refreshRatesHz = listOf(60, 120),
        notes = emptyList(),
    )

    private fun selectSystemProfileOnWorkerSource(): String = sliceBetween(
        mainSourceText("MimirTweakerService.kt"),
        "private fun selectSystemProfileOnWorker(",
        "private fun settleSystemWideStockRuntime(",
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
