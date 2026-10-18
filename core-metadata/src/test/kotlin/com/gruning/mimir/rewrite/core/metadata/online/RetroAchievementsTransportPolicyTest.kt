package com.gruning.mimir.rewrite.core.metadata.online

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RetroAchievementsTransportPolicyTest {

    private val auth = RetroAchievementsApi.Auth(
        username = "player one",
        apiKey = "key +/?",
        credentialRevision = "local-revision-never-sent",
    )

    @Test
    fun `canonical authenticated API requests pass the exact route policy`() {
        assertTrue(retroAchievementsApiUrlAllowed(RetroAchievementsApi.consoleIdsUrl(auth)))
        assertTrue(retroAchievementsApiUrlAllowed(RetroAchievementsApi.gameListUrl(auth, 3)))
        assertTrue(retroAchievementsApiUrlAllowed(RetroAchievementsApi.gameProgressUrl(auth, 77)))
        assertTrue(retroAchievementsApiUrlAllowed(RetroAchievementsApi.userProfileUrl(auth)))
        listOf(
            RetroAchievementsApi.consoleIdsUrl(auth),
            RetroAchievementsApi.gameListUrl(auth, 3),
            RetroAchievementsApi.gameProgressUrl(auth, 77),
            RetroAchievementsApi.userProfileUrl(auth),
        ).forEach { url ->
            assertFalse("local activation revision must never enter a provider URL", url.contains("local-revision"))
        }
        assertTrue(
            retroAchievementsApiUrlAllowed(
                "https://api.retroachievements.org:443/API/API_GetConsoleIDs.php?g=1&y=key&a=1",
            ),
        )
    }

    @Test
    fun `authenticated API policy rejects every authority and route escape`() {
        val rejected = listOf(
            "http://retroachievements.org/API/API_GetConsoleIDs.php?y=key&a=1&g=1",
            "https://media.retroachievements.org/API/API_GetConsoleIDs.php?y=key&a=1&g=1",
            "https://retroachievements.org.evil.example/API/API_GetConsoleIDs.php?y=key&a=1&g=1",
            "https://user@retroachievements.org/API/API_GetConsoleIDs.php?y=key&a=1&g=1",
            "https://retroachievements.org:444/API/API_GetConsoleIDs.php?y=key&a=1&g=1",
            "https://retroachievements.org/API/API_GetConsoleIDs.php?y=key&a=1&g=1#fragment",
            "https://retroachievements.org/API/../dorequest.php?y=key&a=1&g=1",
            "https://retroachievements.org/API/%41PI_GetConsoleIDs.php?y=key&a=1&g=1",
            "https://retroachievements.org/API/API_GetConsoleIDs.php?y=key&a=1",
            "https://retroachievements.org/API/API_GetConsoleIDs.php?y=key&a=1&g=1&extra=1",
            "https://retroachievements.org/API/API_GetConsoleIDs.php?y=key&y=other&a=1&g=1",
            "https://retroachievements.org/API/API_GetConsoleIDs.php?y=&a=1&g=1",
            "https://retroachievements.org/API/API_GetGameList.php?y=key&i=0&h=1&f=1",
            "https://retroachievements.org/API/API_GetGameList.php?y=key&i=-1&h=1&f=1",
            "https://retroachievements.org/API/API_GetGameList.php?y=key&i=3&h=0&f=1",
            "https://retroachievements.org/API/API_GetGameInfoAndUserProgress.php?y=key&u=&g=77",
            "https://retroachievements.org/API/API_GetUserProfile.php?y=key",
        )

        rejected.forEach { url ->
            assertFalse(url, retroAchievementsApiUrlAllowed(url))
        }
    }

    @Test
    fun `redaction derives only the endpoint path even when a query contains slashes`() {
        val redirected =
            "https://retroachievements.org/API/API_GetConsoleIDs.php?y=secret/path&a=1&g=1"
        assertTrue(retroAchievementsApiUrlAllowed(redirected))
        assertEquals("API_GetConsoleIDs.php", RetroAchievementsApi.redact(redirected))
        assertFalse(RetroAchievementsApi.redact(redirected).contains("secret"))
        assertEquals("retroachievements", RetroAchievementsApi.redact("not a valid URL"))
    }

    @Test
    fun `hash library policy admits only its exact keyless request`() {
        assertTrue(retroAchievementsHashLibraryUrlAllowed(RetroAchievementsApi.hashLibraryUrl()))
        assertTrue(
            retroAchievementsHashLibraryUrlAllowed(
                "https://api.retroachievements.org:443/dorequest.php?r=hashlibrary",
            ),
        )

        listOf(
            "http://retroachievements.org/dorequest.php?r=hashlibrary",
            "https://media.retroachievements.org/dorequest.php?r=hashlibrary",
            "https://retroachievements.org.evil.example/dorequest.php?r=hashlibrary",
            "https://user@retroachievements.org/dorequest.php?r=hashlibrary",
            "https://retroachievements.org:444/dorequest.php?r=hashlibrary",
            "https://retroachievements.org/dorequest.php?r=hashlibrary#fragment",
            "https://retroachievements.org/dorequest.php?r=hashlibrary&y=secret",
            "https://retroachievements.org/dorequest.php?r=other",
            "https://retroachievements.org/API/dorequest.php?r=hashlibrary",
        ).forEach { url ->
            assertFalse(url, retroAchievementsHashLibraryUrlAllowed(url))
        }
    }

    @Test
    fun `authenticated redirects stay on the same approved route`() {
        val current = "https://retroachievements.org/API/API_GetConsoleIDs.php?y=key&a=1&g=1"

        assertEquals(
            "https://api.retroachievements.org/API/API_GetConsoleIDs.php?y=key&a=1&g=1",
            retroAchievementsApiRedirectTarget(
                current,
                "https://api.retroachievements.org/API/API_GetConsoleIDs.php?y=key&a=1&g=1",
            ),
        )
        assertEquals(
            "https://retroachievements.org/API/API_GetConsoleIDs.php?g=1&a=1&y=key",
            retroAchievementsApiRedirectTarget(current, "?g=1&a=1&y=key"),
        )
        assertNull(retroAchievementsApiRedirectTarget(current, "?a=1&g=1"))
        assertNull(
            retroAchievementsApiRedirectTarget(
                current,
                "https://media.retroachievements.org/API/API_GetConsoleIDs.php?y=key&a=1&g=1",
            ),
        )
        assertNull(
            retroAchievementsApiRedirectTarget(
                current,
                "https://retroachievements.org/API/API_GetGameList.php?y=key&i=3&h=1&f=1",
            ),
        )
        assertNull(
            retroAchievementsApiRedirectTarget(
                current,
                "https://retroachievements.org:444/API/API_GetConsoleIDs.php?y=key&a=1&g=1",
            ),
        )
    }

    @Test
    fun `hash library redirects stay on the exact keyless route`() {
        val current = RetroAchievementsApi.hashLibraryUrl()

        assertEquals(
            "https://api.retroachievements.org/dorequest.php?r=hashlibrary",
            retroAchievementsHashLibraryRedirectTarget(
                current,
                "https://api.retroachievements.org/dorequest.php?r=hashlibrary",
            ),
        )
        assertEquals(
            current,
            retroAchievementsHashLibraryRedirectTarget(current, "?r=hashlibrary"),
        )
        assertNull(retroAchievementsHashLibraryRedirectTarget(current, "?r=other"))
        assertNull(
            retroAchievementsHashLibraryRedirectTarget(
                current,
                "https://retroachievements.org/API/API_GetConsoleIDs.php?y=key&a=1&g=1",
            ),
        )
    }

    @Test
    fun `declared length policy admits unknown or bounded nonempty bodies only`() {
        assertTrue(retroAchievementsContentLengthAllowed(-1L, 4L))
        assertTrue(retroAchievementsContentLengthAllowed(1L, 4L))
        assertTrue(retroAchievementsContentLengthAllowed(4L, 4L))
        assertFalse(retroAchievementsContentLengthAllowed(-2L, 4L))
        assertFalse(retroAchievementsContentLengthAllowed(0L, 4L))
        assertFalse(retroAchievementsContentLengthAllowed(5L, 4L))
        assertFalse(retroAchievementsContentLengthAllowed(-1L, 0L))
    }

    @Test
    fun `authenticated response reader enforces its streamed UTF8 byte ceiling`() {
        assertEquals(
            "mímir",
            readRetroAchievementsUtf8Bounded(
                ByteArrayInputStream("mímir".toByteArray(Charsets.UTF_8)),
                "mímir".toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        )
        assertNull(
            readRetroAchievementsUtf8Bounded(
                ByteArrayInputStream("12345".toByteArray()),
                4L,
            ),
        )
        assertNull(readRetroAchievementsUtf8Bounded(ByteArrayInputStream(byteArrayOf()), 4L))
        assertEquals(
            "okay",
            readRetroAchievementsUtf8Bounded(
                ZeroThenDataInputStream("okay".toByteArray()),
                4L,
            ),
        )
    }

    @Test
    fun `streaming hash reader accepts the exact ceiling and rejects one extra byte`() {
        val exact = "four".toByteArray()
        assertArrayEquals(
            exact,
            RetroAchievementsBoundedInputStream(ByteArrayInputStream(exact), 4L).use(InputStream::readBytes),
        )

        try {
            RetroAchievementsBoundedInputStream(
                ByteArrayInputStream("fiver".toByteArray()),
                4L,
            ).use(InputStream::readBytes)
            fail("one byte beyond the transport ceiling must fail")
        } catch (_: IOException) {
            // Expected.
        }
    }

    @Test
    fun `hash parser cannot accept a valid prefix followed by bytes beyond the ceiling`() {
        val body =
            """{"Success":true,"MD5List":{"0123456789abcdef0123456789abcdef":77}}"""
        val exactBytes = body.toByteArray()
        val exact = InputStreamReader(
            RetroAchievementsBoundedInputStream(
                ByteArrayInputStream(exactBytes),
                exactBytes.size.toLong(),
            ),
            Charsets.UTF_8,
        ).use(::parsePackedHashLibrary)
        assertEquals(77, exact?.get("0123456789abcdef0123456789abcdef"))

        val oversized = exactBytes + byteArrayOf(' '.code.toByte())
        val rejected = InputStreamReader(
            RetroAchievementsBoundedInputStream(
                ByteArrayInputStream(oversized),
                exactBytes.size.toLong(),
            ),
            Charsets.UTF_8,
        ).use(::parsePackedHashLibrary)
        assertNull(rejected)
    }

    private class ZeroThenDataInputStream(
        bytes: ByteArray,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var returnedZero = false

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!returnedZero) {
                returnedZero = true
                return 0
            }
            return delegate.read(buffer, offset, length)
        }
    }
}
