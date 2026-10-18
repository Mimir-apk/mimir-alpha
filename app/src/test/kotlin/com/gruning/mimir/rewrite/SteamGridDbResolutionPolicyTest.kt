package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamGridDbResolutionPolicyTest {
    @Test
    fun `square icon request names both accepted resolutions`() {
        assertEquals("512x512,1024x1024", SteamGridDbSquareDimensionsQuery)
        assertEquals("512,1024", SteamGridDbIconDimensionsQuery)
    }

    /**
     * pins the exact vertical-capsule dimension
     * string the portrait grid lane requests. UNKNOWN (not testable offline): whether SteamGridDB
     * actually serves 600x900 grids for every title is a provider-side runtime fact this offline test
     * cannot verify -- it pins only that this is the value the app requests.
     */
    @Test
    fun `portrait grid request names the vertical-capsule resolution`() {
        assertEquals("600x900", SteamGridDbPortraitDimensionsQuery)
    }
}
