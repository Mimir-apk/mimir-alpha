package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Daemon half of the transaction-result contract, third of the set after
 * [HardwareCapabilitiesBundleContractTest] and [TelemetryBundleContractTest].
 *
 * This is the bundle carrying every apply, restore and derate outcome back to the UI, so a key
 * dropped between the two builds is how a control comes to advertise something the layer beneath
 * refused. Only [TweakerSessionContract.RESULT] is pinned here; the V4 session keys the service
 * layers on top read live supervisor state and have no callable seam, so they are enforced
 * client-side.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionResultBundleContractTest {

    @Test
    fun `result marshaller emits exactly its declared keys`() {
        val emitted = fullResult().toBundle().keySet()
        val declared = TweakerSessionContract.RESULT.toSet()

        assertEquals("result keys not declared in the contract", emptySet<String>(), emitted - declared)
        assertEquals("contract declares result keys never written", emptySet<String>(), declared - emitted)
    }

    @Test
    fun `a refusal that never reached a plan still answers with the full verdict`() {
        // Most failures return before a plan exists -- CALIBRATION_REQUIRED, INVALID_PACKAGE,
        // THERMAL_DERATE_UNSIGNED_RUNG. The client still has to be able to render the outcome, so
        // the verdict fields are the floor and everything plan-shaped drops out.
        val emitted = TransactionResult(
            success = false,
            applied = false,
            restored = false,
            code = "THERMAL_DERATE_UNSIGNED_RUNG",
            message = "rung is not signed for this profile",
        ).toBundle().keySet()

        assertEquals(TweakerSessionContract.RESULT_ALWAYS.toSet(), emitted)
    }

    @Test
    fun `every declared session key is classified for the client exactly once`() {
        val consumed = TweakerSessionContract.CONSUMED_BY_CLIENT.toSet()
        val notConsumed = TweakerSessionContract.NOT_CONSUMED_BY_CLIENT.toSet()

        assertEquals(
            "a key is both consumed and not consumed by the client",
            emptySet<String>(),
            consumed intersect notConsumed,
        )
        assertEquals(
            "every declared key must be classified as consumed or explicitly not consumed",
            TweakerSessionContract.ALL.toSet(),
            consumed + notConsumed,
        )
    }

    private fun fullResult() = TransactionResult(
        success = true,
        applied = true,
        restored = false,
        code = TransactionCoordinator.THERMAL_DERATE_APPLIED,
        message = "Thermal derate rung applied and verified.",
        plan = TuningPlan(
            profileId = "performance",
            packageName = "com.futuremark.dmandroid.application",
            cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 2_841_600),
            gpuMaxPowerLevel = 2,
            fanMode = 5,
            calibrated = true,
        ),
        transactionId = "tx-7",
        requestId = "req-7",
        generation = 43L,
        requestDeadlineElapsedRealtimeMs = 31_000L,
        hardLeaseDeadlineElapsedRealtimeMs = 901_000L,
    )
}
