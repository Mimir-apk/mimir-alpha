package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host contract for [ManualFanTickObservability] (spec §3, B5): pins the four rendered-line
 * formats exactly, the reason-based classification [ManualFanTickObservability.onDegradedReason]
 * performs on behalf of `MimirTweakerService.fanControlDegraded`'s three call sites, and the
 * shared-key dedup that makes a Hold<->WriteDuty PI flip -- or a sustained degrade/failSafe/
 * writeFailed condition -- safe to log at WARN in every posture without flooding logcat.
 *
 * Kotlin `object`s live for the whole test process, so every dedup assertion below drives the
 * SAME shared `lastKey` slot the object exposes no other way to reset -- each test therefore
 * establishes its own "last line" via an initial call before asserting a subsequent one is or
 * isn't suppressed, exactly as [FanOwnershipObservabilityTest] does for [FanOwnershipObservability].
 */
class ManualFanTickObservabilityTest {

    // --- Pure rendering -----------------------------------------------------------------------

    @Test
    fun `describe functions render the four line shapes exactly`() {
        assertEquals(
            "manual tick=degraded reason=FAN_TELEMETRY_UNAVAILABLE",
            ManualFanTickObservability.describeDegraded("FAN_TELEMETRY_UNAVAILABLE"),
        )
        assertEquals(
            "manual tick=failSafe reason=FAN_OWNERSHIP_LOST",
            ManualFanTickObservability.describeFailSafe("FAN_OWNERSHIP_LOST"),
        )
        assertEquals(
            "manual tick=writeFailed status=FAILED",
            ManualFanTickObservability.describeWriteFailed("FAILED"),
        )
        assertEquals(
            "manual tick=healthy decision=Hold",
            ManualFanTickObservability.describeHealthy("Hold"),
        )
    }

    // --- onDegradedReason's classification -----------------------------------------------------

    @Test
    fun `onDegradedReason renders FAN_TELEMETRY_UNAVAILABLE as a degraded line`() {
        // Unique reason text (never used by another test in this file) so the dedup slot cannot
        // make this a false negative regardless of execution order.
        val reason = "FAN_TELEMETRY_UNAVAILABLE"
        assertTrue(ManualFanTickObservability.onDegradedReason(reason))
        assertEquals("manual tick=degraded reason=$reason", ManualFanTickObservability.lastRecorded)
    }

    @Test
    fun `onDegradedReason renders any other reason as a failSafe line`() {
        // A real FanControlDecision.FailSafe reason, distinct from the two hardcoded literals
        // onDegradedReason special-cases.
        val reason = "FAN_CONTROLLER_STALLED"
        assertTrue(ManualFanTickObservability.onDegradedReason(reason))
        assertEquals("manual tick=failSafe reason=$reason", ManualFanTickObservability.lastRecorded)
    }

    @Test
    fun `onDegradedReason never renders a line for FAN_DUTY_WRITE_FAILED -- the call site owns that line`() {
        // First move the shared key somewhere else so a false "true" couldn't be produced by
        // this call coincidentally matching whatever the last test left behind.
        ManualFanTickObservability.onHealthy("Hold-before-write-failed-probe")
        assertFalse(ManualFanTickObservability.onDegradedReason("FAN_DUTY_WRITE_FAILED"))
        // lastRecorded is untouched by the no-op classification -- it still reads whatever the
        // priming call above rendered, proving onDegradedReason truly emitted nothing here.
        assertEquals(
            ManualFanTickObservability.describeHealthy("Hold-before-write-failed-probe"),
            ManualFanTickObservability.lastRecorded,
        )
    }

    // --- Dedup: the seam itself -----------------------------------------------------------------

    @Test
    fun `the same non-healthy line twice is recorded every time but emitted once`() {
        val reason = "FAN_DEDUP_PROBE_1"
        assertTrue(ManualFanTickObservability.onDegradedReason(reason))
        assertEquals("manual tick=failSafe reason=$reason", ManualFanTickObservability.lastRecorded)

        // Same line: suppressed, so a sustained condition cannot flood logcat...
        assertFalse(ManualFanTickObservability.onDegradedReason(reason))
        // ...and lastRecorded still updates, so the suppression is not a blind spot.
        assertEquals("manual tick=failSafe reason=$reason", ManualFanTickObservability.lastRecorded)

        // A changed reason is a real edge and must emit.
        assertTrue(ManualFanTickObservability.onDegradedReason("FAN_DEDUP_PROBE_1_CHANGED"))
    }

    @Test
    fun `onWriteFailed dedups on status exactly like the other non-healthy lines`() {
        assertTrue(ManualFanTickObservability.onWriteFailed("FAN_DEDUP_PROBE_WRITE_STATUS_1"))
        assertFalse(ManualFanTickObservability.onWriteFailed("FAN_DEDUP_PROBE_WRITE_STATUS_1"))
        assertTrue(ManualFanTickObservability.onWriteFailed("FAN_DEDUP_PROBE_WRITE_STATUS_2"))
    }

    @Test
    fun `a healthy tick after a non-healthy one always emits`() {
        ManualFanTickObservability.onDegradedReason("FAN_DEDUP_PROBE_2")
        assertTrue(ManualFanTickObservability.onHealthy("Hold"))
        assertEquals("manual tick=healthy decision=Hold", ManualFanTickObservability.lastRecorded)
    }

    /**
     * The behaviour a naive "dedup on the full rendered text" design would get wrong: a PI
     * controller legitimately alternates `Hold` and a confirmed `WriteDuty` tick to tick, and
     * both are the SAME underlying fact -- "the controller made healthy progress this tick".
     * Keying dedup on the literal text (which differs between the two) would re-emit on every
     * flip forever, defeating the entire point of deduplication for the single most common
     * outcome this seam observes. [ManualFanTickObservability.onHealthy] shares one constant key
     * across every decision label for exactly this reason.
     */
    @Test
    fun `healthy after healthy -- even a different decision label -- does not re-emit`() {
        // Establish a non-healthy key first (this test class's shared `lastKey` slot otherwise
        // depends on execution order -- another test method may have left it at the healthy key
        // already, which would make the very next assertion a false negative).
        ManualFanTickObservability.onDegradedReason("FAN_DEDUP_PROBE_3")
        assertTrue(ManualFanTickObservability.onHealthy("Hold"))
        // Different rendered text (decision=WriteDuty vs decision=Hold), SAME healthy key --
        // must NOT emit.
        assertFalse(ManualFanTickObservability.onHealthy("WriteDuty"))
        // lastRecorded still reflects the latest call even though it was suppressed.
        assertEquals("manual tick=healthy decision=WriteDuty", ManualFanTickObservability.lastRecorded)
        // And back the other way -- also suppressed.
        assertFalse(ManualFanTickObservability.onHealthy("Hold"))
    }
}
