package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TomeCartridgeArtStoreTest {

    @Test
    fun rendererNamesAreDeterministicBoundedAndIdentitySpecific() {
        val first = tomeCartridgeArtStorageName("gba/zelda")
        assertEquals(first, tomeCartridgeArtStorageName("gba/zelda"))
        assertNotEquals(first, tomeCartridgeArtStorageName("gba/metroid"))
        assertTrue(first.matches(Regex("[0-9a-f]{24}\\.png")))
    }
}
