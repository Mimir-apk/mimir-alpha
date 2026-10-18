package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player-facing headline retains the cause line. Its temporary debug wording may change,
 * but the distinction between rejection and uncertainty must remain.
 *
 * The permanent half of this is the DISTINCTION. `Rejected` (the daemon declined, and said why) and
 * `Uncertain` (the outcome is unknown) both rendered as one confirmed "FAN RESPONSE NOT APPLIED",
 * and that single string is what told the player four fan changes had failed when every one of them
 * had applied and verified on hardware. Whatever the final copy becomes, Uncertain must never again
 * claim the change did not happen.
 *
 * The temporary half is the detail text these pin the shape of, not the wording.
 */
class TweakerFanDebugMessageTest {

    @Test
    fun `the detail line is flattened, uppercased and bounded`() {
        assertEquals(
            "VERIFY_FAILED · MANUAL FAN ENTRY DUTY DRIFT",
            fanDebugDetailText("  verify_failed ·   manual fan\n entry   duty drift "),
        )
    }

    @Test
    fun `an overlong daemon string cannot push the row off the screen`() {
        val detail = fanDebugDetailText("x".repeat(400))

        assertTrue("must be clipped", detail.length <= 96)
        assertTrue("and must say it was clipped", detail.endsWith("…"))
    }

    @Test
    fun `a real daemon decline survives readably`() {
        // The exact string the Nova produced tonight, before the ordering fix.
        val detail = fanDebugDetailText(
            "FAN_CONTROL_UNAVAILABLE · Manual fan control is unavailable for this profile",
        )

        assertTrue("the code must survive the clip", detail.startsWith("FAN_CONTROL_UNAVAILABLE"))
        assertTrue(detail.length <= 96)
    }

    /**
     * The whole reason both strings exist. Nothing pinned the headlines before today, so a copy
     * edit could quietly re-collapse them and reproduce the defect that reported four successful
     * fan changes as failures.
     */
    @Test
    fun `unconfirmed never asserts a failure, and never reads as declined`() {
        assertTrue("the two outcomes must stay distinguishable", FAN_MESSAGE_DECLINED != FAN_MESSAGE_UNCONFIRMED)

        val unconfirmed = FAN_MESSAGE_UNCONFIRMED.uppercase()
        listOf("NOT APPLIED", "FAILED", "DECLINED", "REJECTED", "ERROR").forEach { claim ->
            assertFalse(
                "UNCERTAIN means the outcome is unknown -- it may have landed. \"$claim\" asserts it did not.",
                unconfirmed.contains(claim),
            )
        }
    }
}
