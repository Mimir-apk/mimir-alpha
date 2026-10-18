package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedServicesStoreTest {
    @Test
    fun `first migration preserves an existing ScreenScraper opt-in`() {
        assertTrue(resolveConnectedServicesOptIn(false, storedOptIn = false, legacyScreenScraperOptIn = true))
    }

    @Test
    fun `first migration never enables a user who had not connected`() {
        assertFalse(resolveConnectedServicesOptIn(false, storedOptIn = true, legacyScreenScraperOptIn = false))
    }

    @Test
    fun `completed migration never re-reads a later provider-specific change`() {
        assertTrue(resolveConnectedServicesOptIn(true, storedOptIn = true, legacyScreenScraperOptIn = false))
        assertFalse(resolveConnectedServicesOptIn(true, storedOptIn = false, legacyScreenScraperOptIn = true))
    }

    @Test
    fun `RA gate needs provider-neutral consent and session-usable credentials`() {
        assertTrue(retroAchievementsGateShouldOpen(true, true))
        assertFalse(retroAchievementsGateShouldOpen(false, true))
        assertFalse(retroAchievementsGateShouldOpen(true, false))
        assertFalse(retroAchievementsGateShouldOpen(false, false))
    }
}
