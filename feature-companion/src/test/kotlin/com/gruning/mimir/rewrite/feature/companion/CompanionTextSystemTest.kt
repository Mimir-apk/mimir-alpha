package com.gruning.mimir.rewrite.feature.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompanionTextSystemTest {
    @Test
    fun `static Companion surfaces share the restrained runic header lane`() {
        assertEquals("ᛗ · TABS", CompanionRecentsHeader)
        assertEquals("ᛗ · QUICK LAUNCH", CompanionQuickLaunchHeader)
        assertEquals("ᛗ · PIN AN APP", CompanionQuickAssignHeader)
    }

    @Test
    fun `platform display copy prefers friendly producer truth and never exposes an id fallback`() {
        assertEquals(
            "Super Nintendo Entertainment System",
            companionPlatformDisplayLabel("Super Nintendo Entertainment System", "SNES"),
        )
        assertEquals("SNES", companionPlatformDisplayLabel(null, "SNES"))
        assertEquals(null, companionPlatformDisplayLabel(null, null))
        assertEquals(null, companionPlatformDisplayLabel("", ""))

        val visible = listOfNotNull(
            companionPlatformDisplayLabel("Super Nintendo Entertainment System", "SNES"),
            companionPlatformDisplayLabel(null, "SNES"),
        ).joinToString(" ").lowercase()
        assertFalse("platform id" in visible)
        assertFalse("unknown_platform" in visible)
    }
}
