package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirProviderEgressTest {
    private val calls = mutableListOf<RecordedOpen>()

    @After
    fun reset() {
        MimirSteamGridDbGate.setOpen(false)
        MimirConnectedArtGate.setOpen(false)
        calls.clear()
    }

    @Test
    fun closedSteamGridGate_zeroOpensForEachRoute() {
        val recorder = installRecorder()
        MimirSteamGridDbGate.setOpen(false)
        MimirConnectedArtGate.setOpen(true)

        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAMGRIDDB_API,
                "https://www.steamgriddb.com/api/v2/grids/game/7",
                1_000,
                1_000,
                opener = recorder,
            ),
        )
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
                "https://cdn2.steamgriddb.com/icon.png",
                1_000,
                1_000,
                opener = recorder,
            ),
        )
        assertEquals(0, calls.size)
    }

    @Test
    fun closedGate_allowsOnlyTheExactUserTriggeredKeyValidationProbe() {
        val recorder = installRecorder(status = 200, body = "{}")
        MimirSteamGridDbGate.setOpen(false)

        val accepted = MimirProviderEgress.open(
            MimirProviderEgressRoute.STEAMGRIDDB_KEY_VALIDATION,
            SteamGridDbKeyValidationUrl,
            1_000,
            1_000,
            mapOf("Authorization" to "Bearer candidate"),
            opener = recorder,
        )
        assertNotNull(accepted)
        accepted?.close()
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAMGRIDDB_KEY_VALIDATION,
                "https://www.steamgriddb.com/api/v2/search/autocomplete/AnythingElse",
                1_000,
                1_000,
                opener = recorder,
            ),
        )
        assertEquals(1, calls.size)
    }

    @Test
    fun liveKeyValidation_acceptsOnlyProviderSuccess() = runBlocking {
        assertEquals(
            SteamGridDbKeyValidationResult.ACCEPTED,
            CustomHeroFetcher.validateSteamGridDbKey("candidate", installRecorder(status = 200)),
        )
        assertEquals("Bearer candidate", calls.last().requestHeaders["Authorization"])
        assertEquals(
            SteamGridDbKeyValidationResult.REJECTED,
            CustomHeroFetcher.validateSteamGridDbKey("candidate", installRecorder(status = 401)),
        )
        assertEquals(
            SteamGridDbKeyValidationResult.REJECTED,
            CustomHeroFetcher.validateSteamGridDbKey("candidate", installRecorder(status = 403)),
        )
        assertEquals(
            SteamGridDbKeyValidationResult.PROVIDER_UNAVAILABLE,
            CustomHeroFetcher.validateSteamGridDbKey("candidate", installRecorder(status = 500)),
        )
        assertEquals(
            SteamGridDbKeyValidationResult.UNREACHABLE,
            CustomHeroFetcher.validateSteamGridDbKey(
                "candidate",
                MimirProviderEgressOpener { _, _, _, _ -> null },
            ),
        )
        assertEquals(
            SteamGridDbKeyValidationResult.REJECTED,
            CustomHeroFetcher.validateSteamGridDbKey("   ", installRecorder(status = 200)),
        )
    }

    @Test
    fun crossProviderRefusal() {
        val recorder = installRecorder()
        MimirSteamGridDbGate.setOpen(true)
        MimirConnectedArtGate.setOpen(true)

        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAMGRIDDB_API,
                "https://www.screenscraper.fr/api2/jeuInfos.php",
                1_000,
                1_000,
                opener = recorder,
            ),
        )
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.SCREENSCRAPER_MEDIA,
                "https://cdn2.steamgriddb.com/icon.png",
                1_000,
                1_000,
                opener = recorder,
            ),
        )
        assertEquals(0, calls.size)
    }

    @Test
    fun urlAttacksNeverReachOpener() {
        val recorder = installRecorder()
        MimirSteamGridDbGate.setOpen(true)
        MimirConnectedArtGate.setOpen(true)

        listOf(
            "http://www.steamgriddb.com/api/v2/grids/game/7",
            "https://evil-steamgriddb.com/icon.png",
            "https://www.steamgriddb.com.evil/api/v2/grids/game/7",
        ).forEach { url ->
            assertNull(
                MimirProviderEgress.open(
                    MimirProviderEgressRoute.STEAMGRIDDB_API,
                    url,
                    1_000,
                    1_000,
                    opener = recorder,
                ),
            )
        }
        assertEquals(0, calls.size)
    }

    @Test
    fun openAuthorizesExactUrlAndHeadersOnce() {
        val recorder = installRecorder(status = 200, body = "{}")
        MimirSteamGridDbGate.setOpen(true)
        val url = "https://www.steamgriddb.com/api/v2/grids/game/7"
        val headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer test-key")

        val response = MimirProviderEgress.open(
            MimirProviderEgressRoute.STEAMGRIDDB_API,
            url,
            8_000,
            15_000,
            headers,
            opener = recorder,
        )

        assertNotNull(response)
        assertEquals(1, calls.size)
        assertEquals(url, calls.single().url)
        assertEquals(8_000, calls.single().connectTimeoutMs)
        assertEquals(15_000, calls.single().readTimeoutMs)
        assertEquals(headers, calls.single().requestHeaders)
        response?.close()
    }

    @Test
    fun gateCloseBetweenHopsRefusesSecondOpen() {
        var hop = 0
        val recorder = MimirProviderEgressOpener { url, _, _, _ ->
            hop++
            if (hop == 1) {
                FakeEgressResponse(302, body = "")
            } else {
                FakeEgressResponse(200, body = "{}")
            }
        }
        MimirSteamGridDbGate.setOpen(true)
        val start = "https://www.steamgriddb.com/api/v2/grids/game/7?page=0"
        val first = MimirProviderEgress.open(
            MimirProviderEgressRoute.STEAMGRIDDB_API,
            start,
            1_000,
            1_000,
            opener = recorder,
        )
        assertNotNull(first)
        first?.close()

        MimirSteamGridDbGate.setOpen(false)
        val redirect = steamGridDbApiRedirectTarget(start, "?page=1")
        assertNotNull(redirect)
        val second = MimirProviderEgress.open(
            MimirProviderEgressRoute.STEAMGRIDDB_API,
            redirect!!,
            1_000,
            1_000,
            opener = recorder,
        )
        assertNull(second)
        assertEquals(1, hop)
    }

    @Test
    fun gatesNeverAuthorizeEachOther() {
        val recorder = installRecorder(status = 200, body = "ok")
        MimirSteamGridDbGate.setOpen(true)
        MimirConnectedArtGate.setOpen(false)
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.SCREENSCRAPER_MEDIA,
                "https://www.screenscraper.fr/image.php?x=1",
                1_000,
                1_000,
                opener = recorder,
            ),
        )

        MimirSteamGridDbGate.setOpen(false)
        MimirConnectedArtGate.setOpen(true)
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
                "https://cdn2.steamgriddb.com/icon.png",
                1_000,
                1_000,
                opener = recorder,
            ),
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun closedGateBlocksEvenWhenHostWouldBeAllowed() {
        val recorder = installRecorder(status = 200, body = "ok")
        MimirSteamGridDbGate.setOpen(false)
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
                "https://cdn2.steamgriddb.com/icon.png",
                1_000,
                1_000,
                opener = recorder,
            ),
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun responseExposesStatusHeaderLengthBodyAndClose() {
        val recorder = installRecorder(status = 200, body = "payload", length = 7L, header = "application/json")
        MimirSteamGridDbGate.setOpen(true)
        val response = MimirProviderEgress.open(
            MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
            "https://cdn2.steamgriddb.com/icon.png",
            1_000,
            1_000,
            opener = recorder,
        ) ?: error("expected response")
        assertEquals(200, response.statusCode)
        assertEquals("application/json", response.header("Content-Type"))
        assertEquals(7L, response.declaredLength)
        assertEquals("payload", response.body().bufferedReader().readText())
        response.close()
        assertFalse((calls.single().response as FakeEgressResponse).connected)
    }

    @Test
    fun sourceRouteMismatch_zeroOpens() {
        val recorder = installRecorder(status = 200, body = "ok")
        MimirSteamGridDbGate.setOpen(true)
        MimirConnectedArtGate.setOpen(true)
        assertEquals(
            MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
            routeForSource(CustomHeroSource.STEAMGRIDDB),
        )
        assertEquals(
            MimirProviderEgressRoute.SCREENSCRAPER_MEDIA,
            routeForSource(CustomHeroSource.SCREENSCRAPER),
        )
        assertNull(
            MimirProviderEgress.open(
                route = MimirProviderEgressRoute.SCREENSCRAPER_MEDIA,
                url = "https://cdn2.steamgriddb.com/icon.png",
                connectTimeoutMs = 1_000,
                readTimeoutMs = 1_000,
                opener = recorder,
            ),
        )
        assertTrue(calls.isEmpty())
    }

    // ── STEAM_STORE_API : keyless storefront description lookup riding the
    // SteamGridDB opt-in gate. Mirrors the STEAMGRIDDB_API attack/refusal patterns above exactly, so a
    // regression here reads the same way a regression above would.

    @Test
    fun steamStoreOpensOnlyOnTheExactAppdetailsUrlWithSteamGridDbGateOpen() {
        val recorder = installRecorder(status = 200, body = """{"440":{"success":false}}""")
        MimirSteamGridDbGate.setOpen(true)

        val response = MimirProviderEgress.open(
            MimirProviderEgressRoute.STEAM_STORE_API,
            steamStoreAppDetailsUrl(440),
            1_000,
            1_000,
            opener = recorder,
        )

        assertNotNull(response)
        assertEquals(1, calls.size)
        assertEquals("https://store.steampowered.com/api/appdetails?appids=440", calls.single().url)
        response?.close()
    }

    @Test
    fun steamStoreClosedGateBlocksEvenTheExactAllowedUrl() {
        val recorder = installRecorder()
        MimirSteamGridDbGate.setOpen(false)

        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAM_STORE_API,
                steamStoreAppDetailsUrl(440),
                1_000,
                1_000,
                opener = recorder,
            ),
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun steamStoreUrlAttacksNeverReachOpener() {
        val recorder = installRecorder()
        MimirSteamGridDbGate.setOpen(true)
        MimirConnectedArtGate.setOpen(true)

        listOf(
            // Plain HTTP, never HTTPS.
            "http://store.steampowered.com/api/appdetails?appids=440",
            // Lookalike host (prefix attack).
            "https://evil-store.steampowered.com/api/appdetails?appids=440",
            // Suffix attack: the real host as a path segment on an attacker-controlled domain.
            "https://store.steampowered.com.evil.com/api/appdetails?appids=440",
            // Wrong path on the real host.
            "https://store.steampowered.com/api/appdetails/extra?appids=440",
            // Extra/injected query parameter.
            "https://store.steampowered.com/api/appdetails?appids=440&l=english",
            // Non-numeric / non-positive app id.
            "https://store.steampowered.com/api/appdetails?appids=abc",
            "https://store.steampowered.com/api/appdetails?appids=-1",
            "https://store.steampowered.com/api/appdetails?appids=0",
        ).forEach { url ->
            assertNull(
                "expected zero opens for <$url>",
                MimirProviderEgress.open(
                    MimirProviderEgressRoute.STEAM_STORE_API,
                    url,
                    1_000,
                    1_000,
                    opener = recorder,
                ),
            )
        }
        assertEquals(0, calls.size)
    }

    @Test
    fun steamStoreCrossProviderRefusal() {
        val recorder = installRecorder()
        MimirSteamGridDbGate.setOpen(true)
        MimirConnectedArtGate.setOpen(true)

        // The Steam storefront host must never be reachable through a differently-purposed route.
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAMGRIDDB_API,
                steamStoreAppDetailsUrl(440),
                1_000,
                1_000,
                opener = recorder,
            ),
        )
        // Nor may an unrelated host be reachable through the Steam-store route.
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAM_STORE_API,
                "https://www.steamgriddb.com/api/v2/grids/game/7",
                1_000,
                1_000,
                opener = recorder,
            ),
        )
        assertEquals(0, calls.size)
    }

    private fun installRecorder(
        status: Int = 200,
        body: String = "",
        length: Long = -1L,
        header: String? = null,
    ): MimirProviderEgressOpener = MimirProviderEgressOpener { url, connectTimeoutMs, readTimeoutMs, requestHeaders ->
        val response = FakeEgressResponse(
            statusCode = status,
            body = body,
            declaredLength = length,
            contentType = header,
        )
        calls += RecordedOpen(url, connectTimeoutMs, readTimeoutMs, requestHeaders, response)
        response
    }

    private data class RecordedOpen(
        val url: String,
        val connectTimeoutMs: Int,
        val readTimeoutMs: Int,
        val requestHeaders: Map<String, String>,
        val response: MimirProviderEgressResponse,
    )

    private class FakeEgressResponse(
        override val statusCode: Int,
        private val body: String,
        override val declaredLength: Long = -1L,
        private val contentType: String? = null,
    ) : MimirProviderEgressResponse {
        var connected = true
            private set

        override fun header(name: String): String? =
            contentType?.takeIf { name.equals("Content-Type", ignoreCase = true) }

        override fun body(): InputStream = ByteArrayInputStream(body.toByteArray())

        override fun close() {
            connected = false
        }
    }
}
