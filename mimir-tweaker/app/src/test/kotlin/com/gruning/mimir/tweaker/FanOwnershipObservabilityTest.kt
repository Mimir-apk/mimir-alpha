package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host contract for [FanOwnershipObservability]: pins the three rendered-line formats exactly
 * (a regression that changes the shape without meaning to fails here, not just on a device grep)
 * and the dedup that makes an always-on seam under a bounded retry safe to ship in Production --
 * same shape [GuardianTerminalLoadObservabilityTest] uses for [GuardianTerminalLoadObservability].
 *
 * Kotlin `object`s live for the whole test process, so the dedup test below uses detail/code
 * values unique to this file so its first `on*` call is always a real edge regardless of test
 * execution order.
 */
class FanOwnershipObservabilityTest {

    // --- Pure rendering -----------------------------------------------------------------------

    @Test
    fun `describeReassertAttempt renders generation transactionId code and message`() {
        assertEquals(
            "reassert generation=7 transactionId=tx-1 code=FAN_REASSERT_OK message=reasserted",
            FanOwnershipObservability.describeReassertAttempt(7L, "tx-1", "FAN_REASSERT_OK", "reasserted"),
        )
    }

    @Test
    fun `describeRenewDecision renders reason and both fan modes, including a null probe`() {
        assertEquals(
            "renew decision=RepairFanTarget reason=TARGET_CONTRACT_DRIFT_FAN liveFanMode=6 contractFan=4",
            FanOwnershipObservability.describeRenewDecision("TARGET_CONTRACT_DRIFT_FAN", 6, 4),
        )
        // A failed live-mode read (fanModeRaw() == null, or non-numeric) must render as the literal
        // "null" rather than being swallowed -- a silent field here is exactly the kind of blind
        // spot GuardianTerminalLoadObservability's own kdoc warns against.
        assertEquals(
            "renew decision=RepairFanTarget reason=TARGET_CONTRACT_DRIFT_FAN liveFanMode=null contractFan=null",
            FanOwnershipObservability.describeRenewDecision("TARGET_CONTRACT_DRIFT_FAN", null, null),
        )
    }

    @Test
    fun `describeSettleDeferred renders code and message with renewal pending`() {
        assertEquals(
            "ownership settle=deferred code=FAN_PROFILE_HANDOFF_FAILED renewal=pending message=handoff refused",
            FanOwnershipObservability.describeSettleDeferred("FAN_PROFILE_HANDOFF_FAILED", "handoff refused"),
        )
    }

    @Test
    fun `describeSettleLost renders code and message with no renewal-pending claim`() {
        assertEquals(
            "ownership settle=lost code=FAN_PROFILE_HANDOFF_FAILED message=session lost after restore",
            FanOwnershipObservability.describeSettleLost("FAN_PROFILE_HANDOFF_FAILED", "session lost after restore"),
        )
    }

    @Test
    fun `describeRenewAttempt renders attempt generation transactionId and code`() {
        assertEquals(
            "renew attempt=1 generation=7 transactionId=tx-1 code=FAN_HANDOFF_UNAVAILABLE",
            FanOwnershipObservability.describeRenewAttempt(7L, "tx-1", 1, "FAN_HANDOFF_UNAVAILABLE"),
        )
    }

    @Test
    fun `describeRenewalExhausted renders generation transactionId attempts and code`() {
        // Renewal exhaustion: logged once on the attempt that first marks a renewal exhausted.
        assertEquals(
            "renewal exhausted attempts=3 generation=7 transactionId=tx-1 code=FAN_HANDOFF_UNAVAILABLE",
            FanOwnershipObservability.describeRenewalExhausted(7L, "tx-1", 3, "FAN_HANDOFF_UNAVAILABLE"),
        )
    }

    @Test
    fun `describeReentryDeclined renders generation transactionId code and the stage-specific detail`() {
        assertEquals(
            "reentry generation=7 transactionId=tx-1 code=FAN_REASSERT_HANDOFF_UNAVAILABLE " +
                "detail=stage=handToVendorSmart liveFanMode=6 detail=injected",
            FanOwnershipObservability.describeReentryDeclined(
                7L,
                "tx-1",
                "FAN_REASSERT_HANDOFF_UNAVAILABLE",
                "stage=handToVendorSmart liveFanMode=6 detail=injected",
            ),
        )
    }

    // --- The seam itself: rendering, and the dedup that makes it safe under a bounded retry. ---

    @Test
    fun `an unchanged reassert line is recorded every time but emitted once`() {
        val transactionId = "tx-dedup-1"

        assertTrue(FanOwnershipObservability.onReassertAttempt(1L, transactionId, "FAN_REASSERT_OK", "ok"))
        assertEquals(
            "reassert generation=1 transactionId=tx-dedup-1 code=FAN_REASSERT_OK message=ok",
            FanOwnershipObservability.lastRecorded,
        )

        // Same line: suppressed, so a call site under the 4-per-30s/2-per-5s reassert budget
        // cannot flood logcat...
        assertFalse(FanOwnershipObservability.onReassertAttempt(1L, transactionId, "FAN_REASSERT_OK", "ok"))
        // ...and lastRecorded still updated, so the suppression is not a blind spot.
        assertEquals(
            "reassert generation=1 transactionId=tx-dedup-1 code=FAN_REASSERT_OK message=ok",
            FanOwnershipObservability.lastRecorded,
        )

        // A changed code is a real edge and must emit, even for the same generation/transaction.
        assertTrue(FanOwnershipObservability.onReassertAttempt(1L, transactionId, "STALE_GENERATION", "ok"))
        // As is a switch to a different describe* kind, even carrying related-looking fields.
        assertTrue(FanOwnershipObservability.onRenewDecision("TARGET_CONTRACT_DRIFT_FAN", 6, 4))
        assertFalse(FanOwnershipObservability.onRenewDecision("TARGET_CONTRACT_DRIFT_FAN", 6, 4))
        assertTrue(FanOwnershipObservability.onSettleDeferred("FAN_PROFILE_HANDOFF_FAILED", "dedup probe"))
        assertFalse(FanOwnershipObservability.onSettleDeferred("FAN_PROFILE_HANDOFF_FAILED", "dedup probe"))
        assertTrue(FanOwnershipObservability.onSettleLost("FAN_PROFILE_HANDOFF_FAILED", "dedup probe lost"))
        assertFalse(FanOwnershipObservability.onSettleLost("FAN_PROFILE_HANDOFF_FAILED", "dedup probe lost"))
        assertTrue(FanOwnershipObservability.onRenewAttempt(2L, "tx-dedup-2", 1, "FAN_HANDOFF_UNAVAILABLE"))
        assertFalse(FanOwnershipObservability.onRenewAttempt(2L, "tx-dedup-2", 1, "FAN_HANDOFF_UNAVAILABLE"))
        // A changed attempt count is a real edge even for the same generation/transaction/code.
        assertTrue(FanOwnershipObservability.onRenewAttempt(2L, "tx-dedup-2", 2, "FAN_HANDOFF_UNAVAILABLE"))
        assertTrue(
            FanOwnershipObservability.onReentryDeclined(
                3L,
                "tx-dedup-3",
                "FAN_REASSERT_HANDOFF_BUSY",
                "stage=handoffPrepare",
            ),
        )
        assertFalse(
            FanOwnershipObservability.onReentryDeclined(
                3L,
                "tx-dedup-3",
                "FAN_REASSERT_HANDOFF_BUSY",
                "stage=handoffPrepare",
            ),
        )
        // A changed stage detail is a real edge even for the same generation/transaction/code.
        assertTrue(
            FanOwnershipObservability.onReentryDeclined(
                3L,
                "tx-dedup-3",
                "FAN_REASSERT_HANDOFF_BUSY",
                "stage=telemetrySample",
            ),
        )
        // F4: the exhaustion line dedups like every other seam here -- the SAME (generation,
        // transactionId, attempts, code) suppresses; a changed attempts count (the caller's own
        // "log once, on the transition" gate means this would only legitimately recur for a LATER
        // exhaustion under a fresh attempts count) is a real edge.
        assertTrue(
            FanOwnershipObservability.onRenewalExhausted(4L, "tx-dedup-4", 3, "FAN_HANDOFF_UNAVAILABLE"),
        )
        assertFalse(
            FanOwnershipObservability.onRenewalExhausted(4L, "tx-dedup-4", 3, "FAN_HANDOFF_UNAVAILABLE"),
        )
        assertTrue(
            FanOwnershipObservability.onRenewalExhausted(4L, "tx-dedup-4", 6, "FAN_HANDOFF_UNAVAILABLE"),
        )
    }
}
