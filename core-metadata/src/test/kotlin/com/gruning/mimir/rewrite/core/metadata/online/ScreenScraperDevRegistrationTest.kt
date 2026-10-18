package com.gruning.mimir.rewrite.core.metadata.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenScraperDevRegistrationTest {

    @Test
    fun configuredOnlyWhenBothIdAndPasswordPresent() {
        assertTrue(ScreenScraperDevRegistration.isConfigured("dev-id", "dev-pass"))
        assertFalse(ScreenScraperDevRegistration.isConfigured("", "dev-pass"))
        assertFalse(ScreenScraperDevRegistration.isConfigured("dev-id", ""))
        assertFalse(ScreenScraperDevRegistration.isConfigured("dev-id", "   "))
        assertFalse(ScreenScraperDevRegistration.isConfigured(null, null))
        assertFalse(ScreenScraperDevRegistration.isConfigured("", ""))
    }
}
