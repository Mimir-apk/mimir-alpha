package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamGridDbTransportPolicyTest {
    @After
    fun resetGates() {
        MimirSteamGridDbGate.setOpen(false)
        MimirConnectedArtGate.setOpen(false)
    }
    @Test
    fun `authenticated api authority is exact https default port and v2 path only`() {
        assertTrue(
            steamGridDbApiUrlAllowed(
                "https://www.steamgriddb.com/api/v2/grids/game/7?page=1",
            ),
        )
        assertTrue(
            steamGridDbApiUrlAllowed(
                "https://www.steamgriddb.com/api/v2/search/autocomplete/Resident+Evil+2",
            ),
        )
        listOf("100% Orange Juice", ".hack//G.U.").forEach { title ->
            val encoded = URLEncoder.encode(title, "UTF-8")
            assertTrue(
                steamGridDbApiUrlAllowed(
                    "https://www.steamgriddb.com/api/v2/search/autocomplete/$encoded",
                ),
            )
        }
        assertTrue(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/icons/game/7"))
        assertTrue(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/heroes/game/7"))
        // the exact-Steam-app-id lookup lane  — same
        // shape as the game-id lane, "steam" instead of "game" as the id-type segment.
        assertTrue(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/heroes/steam/2114740"))
        assertTrue(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/grids/steam/2114740"))
        assertTrue(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/icons/steam/2114740"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/heroes/steam/0"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/heroes/steam/-1"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/heroes/steamgames/7"))
        assertFalse(steamGridDbApiUrlAllowed("http://www.steamgriddb.com/api/v2/grids/game/7"))
        assertFalse(steamGridDbApiUrlAllowed("https://api.steamgriddb.com/api/v2/grids/game/7"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com.evil/api/v2/grids/game/7"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com:8443/api/v2/grids/game/7"))
        assertFalse(steamGridDbApiUrlAllowed("https://user@www.steamgriddb.com/api/v2/grids/game/7"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v3/grids/game/7"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/grids/game/7#secret"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/account"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/grids/game/0"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/grids/game/7/extra"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2/../../account"))
        assertFalse(steamGridDbApiUrlAllowed("https://www.steamgriddb.com/api/v2//../account"))
        assertFalse(
            steamGridDbApiUrlAllowed(
                "https://www.steamgriddb.com/api/v2/%2e%2e/%2e%2e/account",
            ),
        )
        assertFalse(
            steamGridDbApiUrlAllowed(
                "https://www.steamgriddb.com/api/v2/search/autocomplete/%252e%252e%252faccount",
            ),
        )
        assertFalse(
            steamGridDbApiUrlAllowed(
                "https://www.steamgriddb.com/api/v2/search/autocomplete/..%2F..%2Faccount",
            ),
        )
        assertFalse(
            steamGridDbApiUrlAllowed(
                "https://www.steamgriddb.com/api/v2/search/autocomplete/foo%5c..%5caccount",
            ),
        )
    }

    @Test
    fun `authenticated api redirects remain on the exact api authority and path`() {
        val current = "https://www.steamgriddb.com/api/v2/grids/game/7?page=0"
        assertEquals(
            "https://www.steamgriddb.com/api/v2/grids/game/7?page=1",
            steamGridDbApiRedirectTarget(current, "?page=1"),
        )
        assertNull(
            steamGridDbApiRedirectTarget(
                current,
                "https://cdn2.steamgriddb.com/api/v2/grids/game/7",
            ),
        )
        assertNull(steamGridDbApiRedirectTarget(current, "https://example.com/credential-catcher"))
        assertNull(steamGridDbApiRedirectTarget(current, "http://www.steamgriddb.com/api/v2/grids/game/7"))
        assertNull(steamGridDbApiRedirectTarget(current, "/account"))
        assertNull(steamGridDbApiRedirectTarget(current, "../../account"))
        assertNull(steamGridDbApiRedirectTarget(current, "/api/v2/../../account"))
        assertNull(
            steamGridDbApiRedirectTarget(
                current,
                "/api/v2/%2e%2e/%2e%2e/account",
            ),
        )
    }

    @Test
    fun `media authorities reject insecure lookalike credentialed and custom port urls`() {
        assertTrue(steamGridDbMediaUrlAllowed("https://cdn2.steamgriddb.com/icon.png"))
        assertTrue(customEnrichMediaUrlAllowed("https://www.screenscraper.fr/image.php?id=1"))
        assertFalse(steamGridDbMediaUrlAllowed("http://cdn2.steamgriddb.com/icon.png"))
        assertFalse(steamGridDbMediaUrlAllowed("https://cdn2.steamgriddb.com:8443/icon.png"))
        assertFalse(steamGridDbMediaUrlAllowed("https://user@cdn2.steamgriddb.com/icon.png"))
        assertFalse(steamGridDbMediaUrlAllowed("https://cdn2.steamgriddb.com/icon.png#secret"))
        assertFalse(steamGridDbMediaUrlAllowed("https://evilsteamgriddb.com/icon.png"))
        assertFalse(customEnrichMediaUrlAllowed("https://notscreenscraper.fr/image.php"))
    }

    @Test
    fun `reported content length accepts unknown and exact bound only`() {
        assertTrue(steamGridDbContentLengthAllowed(-1L, 4L))
        assertTrue(steamGridDbContentLengthAllowed(4L, 4L))
        assertFalse(steamGridDbContentLengthAllowed(0L, 4L))
        assertFalse(steamGridDbContentLengthAllowed(5L, 4L))
        assertFalse(steamGridDbContentLengthAllowed(-1L, 0L))
    }

    @Test
    fun `stream copy accepts exact bound and rejects an oversized response`() {
        val exact = ByteArrayOutputStream()
        assertEquals(
            4L,
            copySteamGridDbResponseBounded(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                exact,
                4L,
            ),
        )
        assertEquals(listOf<Byte>(1, 2, 3, 4), exact.toByteArray().toList())

        val oversized = ByteArrayOutputStream()
        assertNull(
            copySteamGridDbResponseBounded(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                oversized,
                4L,
            ),
        )

        val zeroThenData = object : ByteArrayInputStream(byteArrayOf(7, 8)) {
            private var yieldedZero = false

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!yieldedZero) {
                    yieldedZero = true
                    return 0
                }
                return super.read(buffer, offset, length)
            }
        }
        val afterZero = ByteArrayOutputStream()
        assertEquals(2L, copySteamGridDbResponseBounded(zeroThenData, afterZero, 2L))
        assertEquals(listOf<Byte>(7, 8), afterZero.toByteArray().toList())
    }

    @Test
    fun `failed atomic promotion preserves prior target and removes staging`() {
        val root = Files.createTempDirectory("sgdb-transport-fail").toFile()
        try {
            val target = root.resolve("active.png").apply { writeText("old") }
            val staged = root.resolve("candidate.part").apply { writeText("new") }

            assertFalse(commitSteamGridDbDownloadedFile(staged, target) { _, _ -> false })
            assertEquals("old", target.readText())
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `successful promotion replaces target only after staging is complete`() {
        val root = Files.createTempDirectory("sgdb-transport-pass").toFile()
        try {
            val target = root.resolve("active.png").apply { writeText("old") }
            val staged = root.resolve("candidate.part").apply { writeText("new") }

            assertTrue(
                commitSteamGridDbDownloadedFile(staged, target) { from, to ->
                    Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    true
                },
            )
            assertEquals("new", target.readText())
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `closed steamgrid gate performs zero api opens even with nonblank key path`() {
        var opens = 0
        val recorder = MimirProviderEgressOpener { _, _, _, _ ->
            opens++
            FakeTransportEgressResponse(200, "{}")
        }
        MimirSteamGridDbGate.setOpen(false)
        val url = "https://www.steamgriddb.com/api/v2/grids/game/7"
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAMGRIDDB_API,
                url,
                8_000,
                15_000,
                mapOf("Authorization" to "Bearer nonblank-key"),
                opener = recorder,
            ),
        )
        assertEquals(0, opens)
    }

    @Test
    fun `gate close between redirect hops refuses the follow-up open`() {
        var opens = 0
        val recorder = MimirProviderEgressOpener { _, _, _, _ ->
            opens++
            FakeTransportEgressResponse(200, "{}")
        }
        MimirSteamGridDbGate.setOpen(true)
        val start = "https://www.steamgriddb.com/api/v2/grids/game/7?page=0"
        val first = MimirProviderEgress.open(
            MimirProviderEgressRoute.STEAMGRIDDB_API,
            start,
            8_000,
            15_000,
            mapOf("Authorization" to "Bearer nonblank-key"),
            opener = recorder,
        )
        first?.close()
        assertEquals(1, opens)

        MimirSteamGridDbGate.setOpen(false)
        val redirect = steamGridDbApiRedirectTarget(start, "?page=1")
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAMGRIDDB_API,
                redirect!!,
                8_000,
                15_000,
                mapOf("Authorization" to "Bearer nonblank-key"),
                opener = recorder,
            ),
        )
        assertEquals(1, opens)
    }

    @Test
    fun `open api route forwards authorization header when gate is open`() {
        var captured: Map<String, String>? = null
        val recorder = MimirProviderEgressOpener { _, _, _, headers ->
            captured = headers
            FakeTransportEgressResponse(200, "{}")
        }
        MimirSteamGridDbGate.setOpen(true)
        val headers = mapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer nonblank-key",
        )
        MimirProviderEgress.open(
            MimirProviderEgressRoute.STEAMGRIDDB_API,
            "https://www.steamgriddb.com/api/v2/grids/game/7",
            8_000,
            15_000,
            headers,
            opener = recorder,
        )?.close()
        assertEquals(headers, captured)
    }

    // a favourited PC game's automatic Home-shelf tile fetch is given the
    // exact Steam app id instead of a fuzzy title search — the existing hero route,
    // extended to this lane. The routing decision itself (fetchMissing's `steamGridDbUrlSegmentFromAppId(...)
    // ?: searchFirstGameId(...)`) cannot be exercised end-to-end without either live network access or a
    // fake-transport seam threaded through fetchMissing — neither is exercised by these tests.
    // What IS pinned here, without any network call: (1) the routing function fetchMissing's `?:` uses as
    // its left operand resolves the steam segment when-and-only-when a cache key has a known app id, and
    // resolves null (falling through to search) otherwise — Kotlin's `?:` is a language-level short
    // circuit, so a non-null left operand is sufficient to prove searchFirstGameId's autocomplete call is
    // never evaluated for that key; and (2) the exact query URLs the fetcher builds from each segment are
    // byte-identical to the pre-2026-08-19 game/<sgdbId> shape and are admitted by the existing allowlist.
    @Test
    fun `cache key with a known steam app id resolves the steam segment, others fall through to search`() {
        assertEquals(
            "steam/2114740",
            steamGridDbUrlSegmentFromAppId("pc-game-1", mapOf("pc-game-1" to 2114740)),
        )
        // Absent from the map entirely (every pre-existing caller's default is emptyMap()).
        assertNull(steamGridDbUrlSegmentFromAppId("pc-game-1", emptyMap()))
        // Present in the map, but under a DIFFERENT cache key — must not leak across games.
        assertNull(steamGridDbUrlSegmentFromAppId("pc-game-1", mapOf("other-game" to 2114740)))
    }

    @Test
    fun `steam app id segment produces byte-identical query params to the game-id route and is allowlisted`() {
        val gridUrl = steamGridDbSquareGridQueryUrl("steam/2114740")
        val iconUrl = steamGridDbIconQueryUrl("steam/2114740")
        assertEquals(
            "https://www.steamgriddb.com/api/v2/grids/steam/2114740" +
                "?dimensions=512x512,1024x1024&styles=alternate,material,no_logo" +
                "&types=static&nsfw=false&humor=false",
            gridUrl,
        )
        assertEquals(
            "https://www.steamgriddb.com/api/v2/icons/steam/2114740" +
                "?dimensions=512,1024&styles=official,custom" +
                "&mimes=image/png&types=static&nsfw=false&humor=false",
            iconUrl,
        )
        assertTrue(steamGridDbApiUrlAllowed(gridUrl))
        assertTrue(steamGridDbApiUrlAllowed(iconUrl))
    }

    @Test
    fun `game id segment still produces exactly the url the title-search route shipped before`() {
        val gridUrl = steamGridDbSquareGridQueryUrl("game/12345")
        val iconUrl = steamGridDbIconQueryUrl("game/12345")
        assertEquals(
            "https://www.steamgriddb.com/api/v2/grids/game/12345" +
                "?dimensions=512x512,1024x1024&styles=alternate,material,no_logo" +
                "&types=static&nsfw=false&humor=false",
            gridUrl,
        )
        assertEquals(
            "https://www.steamgriddb.com/api/v2/icons/game/12345" +
                "?dimensions=512,1024&styles=official,custom" +
                "&mimes=image/png&types=static&nsfw=false&humor=false",
            iconUrl,
        )
        assertTrue(steamGridDbApiUrlAllowed(gridUrl))
        assertTrue(steamGridDbApiUrlAllowed(iconUrl))
    }

    /**
     * * the portrait grid lane asks the SAME `grids/steam/<appid>` route the square lane already uses, with
     * only `dimensions=` swapped to the vertical-capsule value -- no new host, no allowlist change.
     * [steamGridDbApiUrlAllowed] matches on `rawPath` alone (query params are outside the regex), so this
     * also proves the existing allowlist admits the portrait request without modification.
     */
    @Test
    fun `portrait segment produces the vertical-capsule dimensions query and is allowlisted, no new host`() {
        val gridUrl = steamGridDbPortraitGridQueryUrl("steam/2114740")
        assertEquals(
            "https://www.steamgriddb.com/api/v2/grids/steam/2114740" +
                "?dimensions=600x900&styles=alternate,material,no_logo" +
                "&types=static&nsfw=false&humor=false",
            gridUrl,
        )
        assertTrue(steamGridDbApiUrlAllowed(gridUrl))
    }

    private class FakeTransportEgressResponse(
        override val statusCode: Int,
        private val payload: String,
    ) : MimirProviderEgressResponse {
        override fun header(name: String): String? = null
        override val declaredLength: Long = -1L
        override fun body() = ByteArrayInputStream(payload.toByteArray())
        override fun close() = Unit
    }
}
