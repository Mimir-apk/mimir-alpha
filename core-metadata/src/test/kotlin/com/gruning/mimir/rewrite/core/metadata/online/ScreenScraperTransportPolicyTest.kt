package com.gruning.mimir.rewrite.core.metadata.online

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenScraperTransportPolicyTest {
    @Test
    fun `credentialed api urls use exact https authority port and routes`() {
        listOf(
            "jeuInfos.php",
            "mediaSysteme.php",
            "ssinfraInfos.php",
            "ssuserInfos.php",
        ).forEach { endpoint ->
            assertTrue(
                screenScraperApiUrlAllowed(
                    "https://www.screenscraper.fr/api2/$endpoint?devid=D&devpassword=P",
                ),
            )
        }

        assertFalse(
            screenScraperApiUrlAllowed(
                "http://www.screenscraper.fr/api2/jeuInfos.php?devpassword=P",
            ),
        )
        assertFalse(
            screenScraperApiUrlAllowed(
                "https://screenscraper.fr/api2/jeuInfos.php?devpassword=P",
            ),
        )
        assertFalse(
            screenScraperApiUrlAllowed(
                "https://www.screenscraper.fr:8443/api2/jeuInfos.php?devpassword=P",
            ),
        )
        assertFalse(
            screenScraperApiUrlAllowed(
                "https://user@www.screenscraper.fr/api2/jeuInfos.php?devpassword=P",
            ),
        )
        assertFalse(
            screenScraperApiUrlAllowed(
                "https://www.screenscraper.fr/api2/jeuInfos.php?devpassword=P#secret",
            ),
        )
        assertFalse(
            screenScraperApiUrlAllowed(
                "https://www.screenscraper.fr/api2/../account?devpassword=P",
            ),
        )
        assertFalse(
            screenScraperApiUrlAllowed(
                "https://www.screenscraper.fr/api2/%2e%2e/account?devpassword=P",
            ),
        )
        assertFalse(
            screenScraperApiUrlAllowed(
                "https://www.screenscraper.fr/api2/unlisted.php?devpassword=P",
            ),
        )
    }

    @Test
    fun `credentialed api redirects remain on an exact api route`() {
        val current =
            "https://www.screenscraper.fr/api2/jeuInfos.php?devid=D&devpassword=P&gameid=7"
        assertEquals(
            "https://www.screenscraper.fr/api2/jeuInfos.php?page=2",
            screenScraperApiRedirectTarget(current, "?page=2"),
        )
        assertEquals(
            "https://www.screenscraper.fr/api2/ssinfraInfos.php?devid=D",
            screenScraperApiRedirectTarget(current, "ssinfraInfos.php?devid=D"),
        )
        assertNull(
            screenScraperApiRedirectTarget(
                current,
                "https://api.screenscraper.fr/api2/jeuInfos.php?devpassword=P",
            ),
        )
        assertNull(screenScraperApiRedirectTarget(current, "https://example.com/capture"))
        assertNull(screenScraperApiRedirectTarget(current, "/account"))
        assertNull(screenScraperApiRedirectTarget(current, "../account"))
    }

    @Test
    fun `declared response length accepts unknown empty and exact bound only`() {
        assertTrue(screenScraperContentLengthAllowed(-1L, 4L))
        assertTrue(screenScraperContentLengthAllowed(0L, 4L))
        assertTrue(screenScraperContentLengthAllowed(4L, 4L))
        assertFalse(screenScraperContentLengthAllowed(5L, 4L))
        assertFalse(screenScraperContentLengthAllowed(-1L, 0L))
    }

    @Test
    fun `stream reader accepts exact body and rejects oversized body`() {
        assertEquals(
            "1234",
            readScreenScraperUtf8Bounded(ByteArrayInputStream("1234".toByteArray()), 4L),
        )
        assertNull(
            readScreenScraperUtf8Bounded(ByteArrayInputStream("12345".toByteArray()), 4L),
        )
    }
}
