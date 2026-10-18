package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalShelfBuildConfigTest {
    @Test
    fun `release alpha enables final shelf and preserves Home`() {
        assertTrue(BuildConfig.FINAL_SHELF_ENABLED)
        assertEquals(MimirDestination.HOME, normalizeMimirDestination(MimirDestination.HOME))
    }

    @Test
    fun `release never embeds the local SteamGridDB key`() {
        assertTrue(BuildConfig.STEAMGRIDDB_API_KEY.isBlank())
    }

    @Test
    fun `release cannot authorize the catalog proof hook`() {
        assertFalse(catalogRefreshAuthorized(BuildConfig.DEBUG, CatalogRefreshDebugAuthority))
    }

    @Test
    fun `release uses the post schema v11 monotonic identity`() {
        assertEquals(7, BuildConfig.VERSION_CODE)
        assertEquals("0.1.0-alpha.1-demo", BuildConfig.VERSION_NAME)
        assertFalse(BuildConfig.VERSION_NAME.contains("rewrite", ignoreCase = true))
    }
}
