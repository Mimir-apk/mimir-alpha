package com.gruning.mimir.rewrite.feature.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseCopyTest {
    @Test
    fun `hidden-game platform copy preserves registered labels and never raw slugs`() {
        assertEquals(
            "Super Nintendo Entertainment System",
            browsePlatformLabel("Super Nintendo Entertainment System"),
        )
        assertEquals("Game Boy Advance", browsePlatformLabel("Game Boy Advance"))
        assertEquals("Unknown platform", browsePlatformLabel(null))
    }
}
