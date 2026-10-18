package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two reported defects from the same evening, both of them a comparison or a read that
 * carried more than the question being asked.
 *
 * 1. Fan changes could be rejected before reaching the daemon -- `TweakerBridge.setFanControl` guarded its
 *    mutation with `authoritative != capability`, whole-object equality over a data class that
 *    includes `currentMode` and `currentCurveBias`. Those are the two fields a fan change EXISTS to
 *    mutate, so every successful change poisoned the next press: it was refused in the frontend
 *    with "Fan control session changed before selection" and never reached the daemon. Device
 *    evidence: six interactions on the Nova between 23:16:11 and 23:17:19 produced capability
 *    refreshes and not one `request mode=` line in the daemon's log.
 *
 * 2. Switching from Manual to Auto and back lost the previous bias (for example, cooler 2),
 *    confirmed in the same trace: `MANUAL bias=-30`, then `AUTO bias=0`, then back
 *    to `MANUAL bias=0`.
 */
class TweakerFanSessionAndBiasMemoryTest {

    @Test
    fun `a capability differing only in what a fan change mutates is the same session`() {
        val held = capability(mode = TweakerFanMode.AUTO, bias = 0)
        val afterTheChange = held.copy(currentMode = TweakerFanMode.MANUAL, currentCurveBias = -30)

        assertTrue(
            "the session is the same one -- same owner, generation, transaction, profile and " +
                "advertised surface -- and only the state the player just changed differs",
            afterTheChange.isSameSessionAs(held),
        )
        assertFalse(
            "REPLAY: `authoritative != capability`, the expression that stood until 2026-08-25, " +
                "must be false-equal here to reproduce the fan-session regression",
            afterTheChange == held,
        )
    }

    @Test
    fun `a genuinely different session is still refused`() {
        val held = capability(mode = TweakerFanMode.MANUAL, bias = -30)

        assertFalse("a new generation is a new session", held.copy(generation = 9).isSameSessionAs(held))
        assertFalse("a new transaction is a new session", held.copy(transactionId = "tx-2").isSameSessionAs(held))
        assertFalse("a new owner is a new session", held.copy(ownerRequestId = "mimir.system.other").isSameSessionAs(held))
        assertFalse("another profile is another session", held.copy(profileId = "performance").isSameSessionAs(held))
        assertFalse(
            "a different advertised control surface must refuse an attempt composed against the old one",
            held.copy(curveBiasRange = -50..50).isSameSessionAs(held),
        )
        assertFalse(held.copy(curveBiasStep = 5).isSameSessionAs(held))
        assertFalse(held.copy(modes = setOf(TweakerFanMode.AUTO)).isSameSessionAs(held))
        assertFalse(held.copy(curveSensorType = "CPU").isSameSessionAs(held))
    }

    @Test
    fun `returning to Manual restores the bias the player last applied`() {
        assertEquals(
            "cooler-2 must survive the round trip through Auto",
            -30,
            tweakerFanModeEntryBias(
                next = TweakerFanMode.MANUAL,
                rememberedManualBias = -30,
                // Zero BY CONTRACT while the mode is AUTO -- this is exactly the value the old
                // expression read back, and the whole defect.
                currentBias = 0,
            ),
        )
    }

    @Test
    fun `Auto always goes out at zero and Manual falls back to the live bias`() {
        assertEquals(
            "the daemon requires bias 0 in AUTO; a remembered value must never override that",
            0,
            tweakerFanModeEntryBias(TweakerFanMode.AUTO, rememberedManualBias = -30, currentBias = -30),
        )
        assertEquals(
            "with nothing remembered, the capability's own bias still governs",
            -10,
            tweakerFanModeEntryBias(TweakerFanMode.MANUAL, rememberedManualBias = null, currentBias = -10),
        )
    }

    private fun capability(mode: TweakerFanMode, bias: Int) = TweakerManualFanCapability(
        modes = setOf(TweakerFanMode.AUTO, TweakerFanMode.MANUAL),
        curveBiasRange = -100..100,
        curveBiasStep = 10,
        curveSensorType = "GPU",
        currentMode = mode,
        currentCurveBias = bias,
        ownerRequestId = "mimir.system.fan",
        generation = 7,
        transactionId = "tx-1",
        profileId = "quiet",
    )
}
