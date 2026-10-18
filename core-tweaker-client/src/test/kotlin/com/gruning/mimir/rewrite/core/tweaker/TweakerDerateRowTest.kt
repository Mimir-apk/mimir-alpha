package com.gruning.mimir.rewrite.core.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Observed during a game: signed rung 1 reduced the prime cluster from 3.072 to 2.8416 GHz and
 * the GPU ceiling to 967 MHz, while Utility continued to show `performance` at nominal 3.07 GHz caps.
 * The derate ladder is monotonically non-decreasing, so those limits remain for that session.
 * The row must expose the reduced caps and their cause/duration while retaining the effective profile.
 */
class TweakerDerateRowTest {

    @Test
    fun `a derated profile says so, and an undernated one is untouched`() {
        val nominal = readyRow()
        val derated = readyRow(derate = derate("performance"))

        assertEquals(
            "REPLAY: the row shown while clocks were reduced",
            tweakerProfileDisplayCaps("performance"),
            nominal.valueLabelCaps,
        )
        assertTrue(derated.valueLabelCaps.startsWith(tweakerProfileDisplayCaps("performance")))
        assertTrue("the loss has to be visible", derated.valueLabelCaps.endsWith("· REDUCED"))
    }

    @Test
    fun `a derate from another profile never decorates this row`() {
        val row = readyRow(selected = "balanced", derate = derate("performance"))

        assertFalse(row.deratedNow)
        assertEquals(tweakerProfileDisplayCaps("balanced"), row.valueLabelCaps)
    }

    @Test
    fun `the profile id itself is never rewritten by a derate`() {
        val row = readyRow(derate = derate("performance"))

        assertEquals(
            "every consumer of this id -- launch policy, the stepper, the standing rearm, the " +
                "displayed-truth log -- depends on it staying nominal; the derate is an ADDITIVE " +
                "fact about the ceilings, not a different profile",
            "performance",
            row.effectiveProfileId,
        )
    }

    @Test
    fun `an unavailable row shows its availability, never a derate marker`() {
        val row = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
            selectedProfileId = "performance",
            selectableProfileIds = listOf("stock", "performance"),
            thermalDerate = derate("performance"),
        )

        assertFalse(row.deratedNow)
        assertEquals(TWEAKER_UTILITY_STOCK_ONLY_CAPS, row.valueLabelCaps)
    }

    private fun derate(profileId: String) = TweakerThermalDerate(
        reason = "THERMAL_ANDROID",
        rung = 1,
        maxRung = 3,
        profileId = profileId,
    )

    private fun readyRow(
        selected: String = "performance",
        derate: TweakerThermalDerate? = null,
    ) = TweakerUtilityRowState(
        availability = TweakerUtilityAvailability.READY,
        selectedProfileId = selected,
        selectableProfileIds = listOf("stock", "quiet", "balanced", "performance"),
        thermalDerate = derate,
    )
}
