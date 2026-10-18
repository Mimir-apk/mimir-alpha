package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** FUX-02 R3 — the glance pill's pure formatters. */
class MimirLiquidStatusPillTest {
    @Test
    fun batteryLabelCoversLevelAndUnknown() {
        // Charging is the drawn bolt glyph now, never label text.
        assertEquals("87%", mimirStatusPillBatteryLabel(87))
        assertEquals("—", mimirStatusPillBatteryLabel(null))
    }

    @Test
    fun clockLabelIsZeroPaddedTwentyFourHour() {
        assertEquals("09:05", mimirStatusPillClockLabel(9, 5))
        assertEquals("23:59", mimirStatusPillClockLabel(23, 59))
        assertEquals("00:00", mimirStatusPillClockLabel(0, 0))
    }
}
