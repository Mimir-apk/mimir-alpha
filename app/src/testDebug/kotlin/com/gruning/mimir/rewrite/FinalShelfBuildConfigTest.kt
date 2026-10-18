package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalShelfBuildConfigTest {
    @Test
    fun `debug enables final shelf and preserves Home`() {
        assertTrue(BuildConfig.FINAL_SHELF_ENABLED)
        assertEquals(7, BuildConfig.VERSION_CODE)
        assertEquals(MimirDestination.HOME, normalizeMimirDestination(MimirDestination.HOME))
    }

    @Test
    fun `debug build may authorize the explicit catalog proof hook`() {
        assertTrue(catalogRefreshAuthorized(BuildConfig.DEBUG, CatalogRefreshDebugAuthority))
    }
}
