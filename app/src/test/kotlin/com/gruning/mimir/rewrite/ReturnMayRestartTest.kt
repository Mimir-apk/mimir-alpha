package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parking-return warning: pins the pure threshold behind the Return card's
 * "Return may restart the game." sub-label. Both timestamps are [android.os.SystemClock.elapsedRealtime] readings.
 */
class ReturnMayRestartTest {
    @Test
    fun `return may restart once the lease is at least ten minutes old, not before`() {
        assertTrue(returnMayRestart(parkedAtElapsedMillis = 0L, nowElapsedMillis = 600_000L))
        assertTrue(returnMayRestart(parkedAtElapsedMillis = 0L, nowElapsedMillis = 600_001L))
        assertFalse(returnMayRestart(parkedAtElapsedMillis = 0L, nowElapsedMillis = 599_999L))
    }
}
