package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The system ids that decide whether a platform can fetch art at all.
 *
 * A wrong id here does not fail loudly. ScreenScraper answers a well-formed request for the wrong
 * system, so the failure surfaces as a plausible cover for a different console -- worse than the
 * honest silhouette a missing id produces. These are pinned so an edit has to be deliberate.
 */
class ScreenScraperSystemMapTest {

    /**
     *
     * All three are launch-recognized but had no system id, so every title on them kept its
     * silhouette. Values read live from systemesListe.php with the development registration.
     */
    @Test
    fun `xbox, xbox 360 and ps3 resolve to their live-verified systems`() {
        assertEquals(32, ScreenScraperSystemMap.systemeIdFor(PlatformId("xbox")))
        assertEquals(33, ScreenScraperSystemMap.systemeIdFor(PlatformId("xbox360")))
        assertEquals(59, ScreenScraperSystemMap.systemeIdFor(PlatformId("ps3")))
    }

    /**
     * The specific way this goes wrong. Xbox (32) and Xbox 360 (33) are adjacent in ScreenScraper's
     * catalog and share multiplatform titles -- RE5 shipped on both -- so transposing them returns a
     * real cover for the wrong console rather than nothing at all.
     */
    @Test
    fun `xbox and xbox 360 are never collapsed onto one system`() {
        val xbox = ScreenScraperSystemMap.systemeIdFor(PlatformId("xbox"))
        val xbox360 = ScreenScraperSystemMap.systemeIdFor(PlatformId("xbox360"))

        assertNotEquals("xbox and xbox360 must not share a system id", xbox, xbox360)
    }

    /** Platform ids are matched case-insensitively; the classifier's casing must not decide art. */
    @Test
    fun `lookup is case insensitive`() {
        assertEquals(33, ScreenScraperSystemMap.systemeIdFor(PlatformId("XBOX360")))
        assertEquals(59, ScreenScraperSystemMap.systemeIdFor(PlatformId("Ps3")))
    }

    /**
     * Honest miss, not a guess. Xbox One is id 34 and deliberately unmapped while nothing can launch
     * it; an unknown platform must return null so the title keeps its silhouette.
     */
    @Test
    fun `an unmapped platform returns null instead of a neighbouring system`() {
        assertNull(ScreenScraperSystemMap.systemeIdFor(PlatformId("xboxone")))
        assertNull(ScreenScraperSystemMap.systemeIdFor(PlatformId("not_a_platform")))
    }
}
