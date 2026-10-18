package com.gruning.mimir.rewrite.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiosRequirementRegistryTest {
    @Test
    fun `PS1 stays required for DuckStation RetroArch and an unresolved emulator`() {
        listOf(true, false).forEach { emulatorRequiresBios ->
            val row = BiosRequirementRegistry.rowFor(
                "ps1",
                "PlayStation",
                emulatorRequiresBios = emulatorRequiresBios,
            )
            assertTrue(row?.required == true)
        }
    }

    @Test
    fun `NDS HLE remains optional when its selected emulator does not require BIOS`() {
        val optional = BiosRequirementRegistry.rowFor("nds", "Nintendo DS", emulatorRequiresBios = false)

        assertFalse(optional?.required == true)
    }
}
