package com.gruning.mimir.rewrite.core.storage.mapper

import com.gruning.mimir.rewrite.core.model.BiosPresence
import com.gruning.mimir.rewrite.core.model.BiosProfile
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.storage.entity.BiosProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiosProfileMapperTest {
    @Test
    fun `mapper round trip preserves emulator id and package provenance`() {
        val profile = BiosProfile(
            platformId = PlatformId.PS1,
            fileName = "scph5501.bin",
            required = true,
            presence = BiosPresence.PRESENT,
            emulatorId = EmulatorId("duckstation"),
            packageName = "com.duckstation.ps1",
            expectedHash = "abc123",
            expectedRelativePath = "bios/scph5501.bin",
        )

        val entity = profile.toEntity()
        assertEquals("ps1", entity.platformId)
        assertEquals("duckstation", entity.emulatorScopeKey)
        assertEquals("com.duckstation.ps1", entity.packageName)

        val roundTrip = entity.toBiosProfile()
        assertEquals(profile, roundTrip)
    }

    @Test
    fun `legacy empty scope maps to null emulator id`() {
        val entity = BiosProfileEntity(
            platformId = "ps1",
            emulatorScopeKey = "",
            packageName = null,
            fileName = "scph5501.bin",
            required = true,
            presence = "UNKNOWN",
            expectedHash = null,
            expectedRelativePath = null,
        )

        val profile = entity.toBiosProfile()
        assertNull(profile.emulatorId)
        assertNull(profile.packageName)
        assertEquals(BiosPresence.UNKNOWN, profile.presence)

        assertEquals(entity, profile.toEntity())
    }
}
