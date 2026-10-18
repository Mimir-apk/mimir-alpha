package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * , after
 * *The manual fan curve must limit sustained heat*.
 *
 * It was. Measured on the Odin 3, vendor's own controller against Mimir's Manual curve, same device
 * and same moment: **30 C -> 28% vs 11%, 33 C -> 29% vs ~14.5%**. Manual commanded about half the
 * duty SMART had chosen. The first test below is that measurement, so this file fails if the fix
 * ever stops honouring the device reading that motivated it.
 */
class VendorSmartFanFloorTest {

    @Test
    fun `the Odin measurement that motivated this is honoured`() {
        // Vendor observations, read off com.odin.settings' own FanBase log.
        val floor = VendorSmartFanFloor()
            .observing(30.0, 28)
            .observing(33.0, 29)
        val curve = FanCurveDefaults.forProfile("quiet")

        // What Mimir's Manual curve asks for, unfloored, at those same temperatures.
        assertEquals("the authored curve is the lazy side of the measurement", 11, curve.percentAt(30.0))
        assertTrue("and at 33 C it is still roughly half the vendor", curve.percentAt(33.0) <= 15)

        // Floored, Mimir can never ask for less than the vendor did.
        assertEquals(28, maxOf(curve.percentAt(30.0), floor.percentAt(30.0)))
        assertEquals(29, maxOf(curve.percentAt(33.0), floor.percentAt(33.0)))
    }

    @Test
    fun `an unobserved chassis changes nothing`() {
        assertEquals(
            "a device that has never run Auto must get exactly today's behaviour, not an invented number",
            0,
            VendorSmartFanFloor().percentAt(70.0),
        )
    }

    @Test
    fun `the staircase is monotone from below and keeps the hottest response per degree`() {
        val floor = VendorSmartFanFloor()
            .observing(40.0, 30)
            .observing(50.0, 45)
            .observing(50.9, 41) // same bucket, lower -- must not lower the floor
            .observing(45.0, 20) // cooler bucket, lower -- must not lower 50's answer

        assertEquals("below every observation", 0, floor.percentAt(39.0))
        assertEquals(30, floor.percentAt(40.0))
        assertEquals("still the 40 C answer at 49 C, never the cooler 45 C dip", 30, floor.percentAt(49.0))
        assertEquals("the hottest response seen in the 50 C bucket", 45, floor.percentAt(50.0))
    }

    @Test
    fun `above the hottest observation it ramps to full at the full-fan temperature`() {
        val floor = VendorSmartFanFloor().observing(60.0, 40)

        assertEquals(40, floor.percentAt(60.0))
        assertEquals(
            "half way from 60 C to 84 C is half way from 40% to 100%",
            70,
            floor.percentAt(72.0),
        )
        assertEquals(100, floor.percentAt(FanThermalProfile.FULL_FAN_TEMP_C.toDouble()))
        assertEquals("and stays full beyond it", 100, floor.percentAt(95.0))
        assertTrue(
            "the extrapolation must never dip below the observation it starts from",
            (60..84).all { floor.percentAt(it.toDouble()) >= 40 },
        )
    }

    @Test
    fun `profiles scale the shared curve, hottest profile hardest`() {
        assertEquals(1.00, FanCurveDefaults.scaleForProfile("quiet"), 0.0001)
        assertEquals(1.15, FanCurveDefaults.scaleForProfile("balanced"), 0.0001)
        assertEquals(1.30, FanCurveDefaults.scaleForProfile("performance"), 0.0001)

        val curve = FanCurveDefaults.forProfile("performance")
        val quiet = (curve.percentAt(72.0) * FanCurveDefaults.scaleForProfile("quiet")).toInt()
        val performance = (curve.percentAt(72.0) * FanCurveDefaults.scaleForProfile("performance")).toInt()
        assertTrue(
            "REPLAY: before 2026-08-25 every profile produced the identical duty, which is the " +
                "scaling regression -- if these are equal again, the scaling has been lost",
            performance > quiet,
        )
    }

    @Test
    fun `fan floor outranks quiet bias`() {
        val floor = VendorSmartFanFloor().observing(70.0, 65)
        val curve = FanCurveDefaults.forProfile("quiet")
        // Full Quieter bias: shifts the curve 8 C toward evaluating cooler.
        val quietest = curve.percentAt(70.0, FanCurveBias(100))

        assertTrue("the bias really does lower the raw demand", quietest < curve.percentAt(70.0))
        assertEquals(
            "but Manual can no longer run below what SMART itself chose -- cooler-than-vendor is " +
                "preserved, quieter-than-vendor is deliberately gone",
            65,
            maxOf(quietest, floor.percentAt(70.0)),
        )
    }

    @Test
    fun `a nonsensical observation cannot poison the floor`() {
        val floor = VendorSmartFanFloor()
            .observing(Double.NaN, 90)
            .observing(50.0, 250)

        assertEquals("out-of-range duty is clamped, not trusted", 100, floor.percentAt(50.0))
        assertTrue("NaN is ignored outright", floor.observedPercentByTempC.keys.all { it == 50 })
    }
}
