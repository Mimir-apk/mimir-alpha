package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OR-7 : one named emulator per platform, derived from the seed table. */
class EmulatorRecommendationTest {

    @Test
    fun `a standalone direct emulator is named ahead of RetroArch`() {
        assertEquals("DuckStation", EmulatorSeedRegistry.recommendedDisplayNameFor(PlatformId.PS1))
        assertEquals("Dolphin", EmulatorSeedRegistry.recommendedDisplayNameFor(PlatformId.GAMECUBE))
    }

    @Test
    fun `every name comes from a dispatchable seed for that platform`() {
        EmulatorSeedRegistry.SEEDS.flatMap { it.platforms }.distinct().forEach { platform ->
            val name = EmulatorSeedRegistry.recommendedDisplayNameFor(platform) ?: return@forEach
            assertTrue(
                platform.value + " names " + name,
                EmulatorSeedRegistry.dispatchSeedsFor(platform).any { it.displayName == name },
            )
        }
    }

    @Test
    fun `a platform with no seed names nothing`() {
        assertNull(EmulatorSeedRegistry.recommendedDisplayNameFor(PlatformId("no_such_platform")))
    }
}
