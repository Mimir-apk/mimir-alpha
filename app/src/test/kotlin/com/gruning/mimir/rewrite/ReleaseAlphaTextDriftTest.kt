package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseAlphaTextDriftTest {
    @Test
    fun `cold start keeps static posture capped without touching dynamic warmup copy`() {
        assertEquals("ON THIS DEVICE ONLY", WelcomeBackPostureBadgeCopy)
    }

    @Test
    fun `ScreenScraper credential eyebrow uses one canonical label`() {
        assertEquals("SCREENSCRAPER ACCOUNT", ScreenScraperAccountEyebrowCopy)
    }

    @Test
    fun `retained troubleshooting headers use the static capped vocabulary`() {
        assertEquals("MIMIR", DiagnosticsBrandEyebrowCopy)
        assertEquals("POSTURE", DiagnosticsPostureEyebrowCopy)
        assertEquals("TECHNICAL DETAILS", DiagnosticsTechnicalDetailsEyebrowCopy)
    }
}
