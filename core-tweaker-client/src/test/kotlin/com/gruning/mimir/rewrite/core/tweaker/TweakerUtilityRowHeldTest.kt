package com.gruning.mimir.rewrite.core.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held row shows the profiles and refuses the press.
 *
 * Before this, a device whose daemon was mid-restore showed **no profiles at all** — the capability
 * bundle was discarded upstream and the row fell back to the discovered CPU ceiling. Measured on both
 * handhelds 2026-09-04; one had been in that state for over seventeen hours. This looked like profile support had disappeared.
 *
 * Two things have to hold together here, and they pull in opposite directions:
 *   - the profiles are REAL and must be visible, because the daemon is serving them;
 *   - none of them can be selected, because the daemon refuses every selection against an unsettled
 *     session with `SESSION_NOT_SETTLED`.
 */
class TweakerUtilityRowHeldTest {

    private fun row(
        held: Boolean,
        derate: TweakerThermalDerate? = null,
    ) = TweakerUtilityRowState(
        availability = TweakerUtilityAvailability.READY,
        selectedProfileId = "quiet",
        selectableProfileIds = listOf("stock", "quiet", "balanced", "performance"),
        heldByUnfinishedRestore = held,
        thermalDerate = derate,
    )

    @Test
    fun `a held row still lists every profile the daemon offered`() {
        assertEquals(
            listOf("stock", "quiet", "balanced", "performance"),
            row(held = true).selectableProfileIds,
        )
        assertEquals("quiet", row(held = true).effectiveProfileId)
    }

    @Test
    fun `a held row cannot be stepped, however many profiles it is showing`() {
        assertFalse(
            "offering the step is how a press becomes TWEAKER IS BUSY with no explanation",
            row(held = true).canStep,
        )
        assertTrue("and an unheld row is unaffected", row(held = false).canStep)
    }

    @Test
    fun `the row says HELD beside the profile it is really on`() {
        assertTrue(row(held = true).valueLabelCaps.endsWith(" · $TWEAKER_UTILITY_HELD_CAPS"))
        assertTrue(
            "the profile itself is not replaced by the word",
            row(held = true).valueLabelCaps.startsWith(tweakerProfileDisplayCaps("quiet")),
        )
        assertFalse(row(held = false).valueLabelCaps.contains(TWEAKER_UTILITY_HELD_CAPS))
    }

    /**
     * A derate is a SAFETY fact: reduced ceilings are in force right now. A hold is a fact about whether
     * a press does anything. Both can be true at once and neither may hide the other — least of all the
     * derate, which is the one the player needs to see.
     */
    @Test
    fun `a held row that is also derated says both, derate first`() {
        val derated = row(
            held = true,
            derate = TweakerThermalDerate(
                reason = "THERMAL",
                rung = 1,
                maxRung = 3,
                profileId = "quiet",
            ),
        )
        assertEquals(
            "${tweakerProfileDisplayCaps("quiet")} · $TWEAKER_UTILITY_DERATED_CAPS · $TWEAKER_UTILITY_HELD_CAPS",
            derated.valueLabelCaps,
        )
    }
}
