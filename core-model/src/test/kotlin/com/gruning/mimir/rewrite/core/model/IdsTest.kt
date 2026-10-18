package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Value-type identity invariants: never blank, typed equality, no ambiguous-string leakage. */
class IdsTest {

    @Test
    fun blankIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PlatformId("") }
        assertThrows(IllegalArgumentException::class.java) { PlatformId("   ") }
        assertThrows(IllegalArgumentException::class.java) { GameId("") }
        assertThrows(IllegalArgumentException::class.java) { EntryKey(" ") }
        assertThrows(IllegalArgumentException::class.java) { ProfileId("") }
        assertThrows(IllegalArgumentException::class.java) { EmulatorId("") }
    }

    @Test
    fun valueEqualityIsByWrappedString() {
        assertEquals(PlatformId("ps1"), PlatformId("ps1"))
        assertEquals(PlatformId.PS1, PlatformId("ps1"))
        assertNotEquals(PlatformId("ps1"), PlatformId("ps2"))
        assertEquals("ps1", PlatformId("ps1").toString())
    }

    @Test
    fun profileLocalKeyIsStableAndNotADisplayName() {
        // the storage key is the fixed "local", never a display name.
        assertEquals("local", ProfileId.LOCAL.value)
    }

    @Test
    fun catalogLongIdIsAValidPlatformId() {
        // mimir-launcher-model-and-cert: the catalog long-id IS the launch platform id; no aliasing.
        val longId = PlatformId("nec_pc_engine_turbografx_16")
        assertEquals("nec_pc_engine_turbografx_16", longId.value)
    }

    @Test
    fun emulatorIdIsTheNativeLauncherKeyNotPackageHashCore() {
        // Native launch key contract: EmulatorId MUST be the native launch/dispatch key
        // (launcherKey || id || packageName), e.g. "gba.retroarch.aarch64.mgba" — NOT "package#core".
        val ra = EmulatorId.fromNativeLauncherKey("gba.retroarch.aarch64.mgba")
        assertEquals("gba.retroarch.aarch64.mgba", ra.value)

        val standalone = EmulatorId.fromNativeLauncherKey("org.mupen64plusae.v3.fzurita")
        assertEquals("org.mupen64plusae.v3.fzurita", standalone.value)

        // Two native cores are distinct dispatch keys.
        assertNotEquals(ra, EmulatorId.fromNativeLauncherKey("gba.retroarch.aarch64.vba_next"))
        assertThrows(IllegalArgumentException::class.java) { EmulatorId.fromNativeLauncherKey("  ") }
    }
}
