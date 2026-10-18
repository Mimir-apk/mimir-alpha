package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A ledge cover composed again (Home is mounted afresh on every return from the Library) is born at the
 * shape it last presented, instead of the neutral 3:4 and a 140 ms morph on every return.
 */
class MimirLedgeAspectMemoryTest {

    @Test
    fun `a cover's last presented aspect seeds its next composition`() {
        val model = "content://covers/zelda-${System.nanoTime()}.png"
        assertNull("a cover never presented has no seed: today's neutral start", MimirLedgeAspectMemory.seedFor(model))
        MimirLedgeAspectMemory.record(model, 1.39f)
        assertEquals(1.39f, MimirLedgeAspectMemory.seedFor(model)!!, 0f)
        assertNull(MimirLedgeAspectMemory.seedFor(null))
    }
}
