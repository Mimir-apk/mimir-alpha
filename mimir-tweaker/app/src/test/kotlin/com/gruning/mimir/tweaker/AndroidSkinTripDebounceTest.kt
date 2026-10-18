package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Debounce policy, 2026-08-26, after reported `performance` clock loss mid-game on 08-24: **keep the
 * Android skin channel armed, but stop it firing on a transient.**
 *
 * The measurement that drove it, captured on the Odin 3 under real load 2026-08-26: hottest SoC
 * zone **76.9 C**, fan at **100% / 9000 rpm**, and skin sensor `sys-therm-0` peaking at exactly
 * **49.0 C** against a vendor threshold table of LIGHT 48.0 / MODERATE 49.0 / **SEVERE 50.0** —
 * three severity states inside two degrees, on a plastic chassis whose exhaust air was reported as
 * ambient at full load. One degree of drift could reduce the clocks from 3.072 GHz to
 * 2.8416 GHz for the rest of the session, permanently, with nothing shown in the UI.
 *
 * The channel remains armed because it caught the earlier incident on
 * this same hardware — SEVERE with CPU peaking 98.4 C, never crossing the 95 C trip in force that
 * night. So this pins *sensitivity to transients*, not the channel's existence.
 */
class AndroidSkinTripDebounceTest {

    @Test
    fun `the skin channel needs far longer than the silicon channels`() {
        val policy = SupervisorPolicy()

        assertTrue(
            "a skin trip must not react on the same timescale as a 105 C silicon trip",
            policy.androidThermalTripDebounceSamples > policy.thermalTripDebounceSamples,
        )
        assertEquals(
            "45 samples at a 1 s tick is 45 seconds of sustained SEVERE",
            45_000L,
            policy.androidThermalTripDebounceSamples * policy.sampleIntervalMs,
        )
    }

    @Test
    fun `the corroboration floor separates the two real events on this hardware`() {
        val policy = SupervisorPolicy()

        // Measured mid-game: skin 52.2C while the hottest SoC zone was 80.3C. This case must not derate.
        assertTrue(
            "the false positive that cost him 230 MHz must now be below the floor",
            80 < policy.androidThermalCorroborationC,
        )
        // The incident this channel covers: SEVERE with CPU
        // peaking 98.4 C, missed by the temperature trips of the day. Must STILL derate.
        assertTrue(
            "the case this channel actually caught has to survive the fix",
            98 >= policy.androidThermalCorroborationC,
        )
        assertTrue(
            "corroboration is not a second independent trip -- silicon alone still needs 105 C",
            policy.androidThermalCorroborationC < policy.cpuTripCExclusive,
        )
    }

    @Test
    fun `the silicon and battery channels keep their fast path`() {
        val policy = SupervisorPolicy()

        assertEquals(
            "REPLAY: before 2026-08-26 every channel shared this number. Slowing the temperature " +
                "trips to match the skin one would trade silicon safety for comfort -- at 105 C " +
                "the correct response is fast.",
            3,
            policy.thermalTripDebounceSamples,
        )
        assertEquals(105, policy.cpuTripCExclusive)
        assertEquals(105, policy.gpuTripCExclusive)
        assertEquals(450, policy.batteryTripDeciCExclusive)
    }
}
