package com.gruning.mimir.rewrite.core.metadata.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineProviderPlatformProjectionTest {
    @Test
    fun projectionIsStablePrimitiveAndCoversTheTwoScannerRegressionIds() {
        val rows = OnlineProviderPlatformProjection.rows()

        assertEquals(rows, OnlineProviderPlatformProjection.rows())
        assertEquals(rows.size, rows.distinct().size)
        assertTrue(rows.all { it.rawPlatformId.isNotBlank() })
        assertTrue(rows.all { it.declaredCanonicalPlatformId.isNotBlank() })
        assertTrue(rows.all { it.providerKey.isNotBlank() })
        assertTrue(
            rows.any {
                it.provider == OnlinePlatformProvider.RETRO_ACHIEVEMENTS &&
                    it.rawPlatformId == "sega32x" &&
                    it.declaredCanonicalPlatformId == "sega_32x"
            },
        )
        assertTrue(
            rows.any {
                it.provider == OnlinePlatformProvider.SCREEN_SCRAPER &&
                    it.rawPlatformId == "the_3do_company_3do" &&
                    it.declaredCanonicalPlatformId == "the_3do_company_3do" &&
                    it.providerKey == "29"
            },
        )
        assertTrue(OnlineProviderPlatformProjection.retroAchievementsSupports("sega32x"))
        assertEquals(29, OnlineProviderPlatformProjection.screenScraperSystemId("the_3do_company_3do"))
        assertFalse(OnlineProviderPlatformProjection.retroAchievementsSupports("switch"))
    }
}
