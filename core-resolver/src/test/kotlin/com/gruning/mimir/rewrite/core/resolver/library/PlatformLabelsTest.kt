package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The name a player reads for a platform. A scan stores it on the library's platform row the first time it
 * meets the platform, and the Tome collection, Home, the companion and onboarding's filter all print it.
 */
class PlatformLabelsTest {

    /**
     * User-facing platform names are "PlayStation 3" and "Xbox 360".
     * The table had no row for these, and the id title-cased read "Ps3" and "Xbox360".
     */
    @Test
    fun `a platform the table has no row for takes the registry's name, not its id title-cased`() {
        assertEquals("PlayStation 3", PlatformLabels.labelFor(PlatformId("ps3")))
        assertEquals("Xbox 360", PlatformLabels.labelFor(PlatformId("xbox360")))
        assertEquals("Xbox", PlatformLabels.labelFor(PlatformId("xbox")))
    }

    /** The table's names are the product's own choices, and these three differ from the registry's. */
    @Test
    fun `the table still answers before the registry`() {
        assertEquals("NES", PlatformLabels.labelFor(PlatformId("nes")))
        assertEquals("PSP", PlatformLabels.labelFor(PlatformId("psp")))
        assertEquals("Arcade", PlatformLabels.labelFor(PlatformId("arcade")))
    }

    /**
     * A library id is the resolver's short id, and the registry files some platforms under another one:
     * `pcengine` is `nec_pc_engine_turbografx_16` there. Every such platform has a table row today, so only
     * the lookup itself can show that it asks by the library's id.
     */
    @Test
    fun `the registry is asked by the library's id, which is not always its own`() {
        assertEquals("PC Engine - TurboGrafx 16", PlatformLabels.registryName(PlatformId("pcengine")))
    }

    @Test
    fun `an id nobody knows is still title-cased, never shown raw`() {
        assertEquals("Some System", PlatformLabels.labelFor(PlatformId("some_system")))
    }

    @Test
    fun `a registry that cannot be read costs the name, not the library`() {
        assertNull(PlatformLabels.registryName(PlatformId("ps3")) { error("an unreadable registry") })
    }
}
