package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerThermalDerate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The once-per-incident latch for the derate message, mirroring the restore latch beside it.
 *
 * A rung ESCALATION must re-announce: rung 1 → rung 2 is a second, separate loss of performance,
 * and staying silent about it would repeat the original defect one level down.
 */
class TweakerDerateLegibilityTest {

    @Test
    fun `the first derate announces and the same one never repeats`() {
        val derate = derate(rung = 1)

        assertTrue(shouldSurfaceTweakerDerate(derate, lastSurfacedKey = null))
        assertFalse(
            "the row polls about once a minute; announcing every poll would be a nag, not news",
            shouldSurfaceTweakerDerate(derate, lastSurfacedKey = tweakerDerateKey(derate)),
        )
    }

    @Test
    fun `a deeper rung is a new loss and announces again`() {
        val first = derate(rung = 1)

        assertTrue(
            shouldSurfaceTweakerDerate(derate(rung = 2), lastSurfacedKey = tweakerDerateKey(first)),
        )
    }

    @Test
    fun `a different trip reason announces again`() {
        val android = derate(rung = 1, reason = "THERMAL_ANDROID")

        assertTrue(
            shouldSurfaceTweakerDerate(
                derate(rung = 1, reason = "THERMAL_CPU"),
                lastSurfacedKey = tweakerDerateKey(android),
            ),
        )
    }

    @Test
    fun `no derate says nothing`() {
        assertFalse(shouldSurfaceTweakerDerate(null, lastSurfacedKey = null))
        assertFalse(shouldSurfaceTweakerDerate(null, lastSurfacedKey = "THERMAL_ANDROID:1"))
    }

    private fun derate(rung: Int, reason: String = "THERMAL_ANDROID") = TweakerThermalDerate(
        reason = reason,
        rung = rung,
        maxRung = 3,
        profileId = "performance",
    )
}
