package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundActivityLedgerTest {
    @Test
    fun `stopping prior activity does not clear resumed sibling activity`() {
        val ledger = ForegroundActivityLedger()

        ledger.resume("org.dolphinemu.dolphinemu", "MainActivity", 1)
        ledger.resume("org.dolphinemu.dolphinemu", "EmulationActivity", 2)
        ledger.end("org.dolphinemu.dolphinemu", "MainActivity", 1)

        assertEquals("org.dolphinemu.dolphinemu", ledger.currentPackage())
        assertTrue(ledger.containsPackage("org.dolphinemu.dolphinemu"))
    }

    @Test
    fun `package background clears every activity instance`() {
        val ledger = ForegroundActivityLedger()
        ledger.resume("org.dolphinemu.dolphinemu", "MainActivity", 1)
        ledger.resume("org.dolphinemu.dolphinemu", "EmulationActivity", 2)

        ledger.background("org.dolphinemu.dolphinemu")

        assertEquals(null, ledger.currentPackage())
        assertFalse(ledger.containsPackage("org.dolphinemu.dolphinemu"))
    }

    @Test
    fun `most recently resumed package owns foreground truth`() {
        val ledger = ForegroundActivityLedger()
        ledger.resume("first.game", "GameActivity", 1)
        ledger.resume("second.game", "GameActivity", 2)

        assertEquals("second.game", ledger.currentPackage())

        ledger.end("second.game", "GameActivity", 2)

        assertEquals("first.game", ledger.currentPackage())
    }
}
