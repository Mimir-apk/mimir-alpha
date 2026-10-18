package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.MetadataOrigin
import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IgdbMetadataSource] guards, the token+games call sequence (the 24h cache pin), and the
 * 401/429 failure modes -- a recording fake [IgdbMetadataSource]'s `httpPost`, a fixed clock, and a
 * no-op `pause`. No network anywhere.
 */
class IgdbMetadataSourceTest {

    private data class Call(val url: String, val headers: Map<String, String>, val body: String)

    private fun source(
        gateOpen: Boolean = true,
        registration: IgdbCredentials? = IgdbCredentials("client-1", "secret-1"),
        clockMillis: Long = 1_000_000L,
        responses: MutableList<IgdbHttpResult?>,
        calls: MutableList<Call>,
    ): IgdbMetadataSource = IgdbMetadataSource(
        gateOpen = { gateOpen },
        registration = { registration },
        httpPost = { url, headers, body ->
            calls += Call(url, headers, body)
            if (responses.isNotEmpty()) responses.removeAt(0) else null
        },
        clock = { clockMillis },
        pause = {},
    )

    private fun query(
        platformId: PlatformId = PlatformId.PC_GAMES,
        candidateNames: List<String> = listOf("Blasphemous 2"),
    ) = MetadataQuery(platformId = platformId, candidateNames = candidateNames)

    @Test
    fun `gate false, missing registration, and an unmapped platform all return without any call`() =
        runBlocking {
            val gateClosedCalls = mutableListOf<Call>()
            val gateClosed = source(gateOpen = false, responses = mutableListOf(), calls = gateClosedCalls)
            assertEquals(IgdbMetadataSource.LookupOutcome.GateClosed, gateClosed.lookupOutcome(query()))
            assertTrue(gateClosedCalls.isEmpty())

            val noRegCalls = mutableListOf<Call>()
            val noRegistration = source(registration = null, responses = mutableListOf(), calls = noRegCalls)
            assertEquals(IgdbMetadataSource.LookupOutcome.GateClosed, noRegistration.lookupOutcome(query()))
            assertTrue(noRegCalls.isEmpty())

            val unmappedCalls = mutableListOf<Call>()
            val unmapped = source(responses = mutableListOf(), calls = unmappedCalls)
            assertEquals(
                IgdbMetadataSource.LookupOutcome.PlatformUnsupported,
                unmapped.lookupOutcome(query(platformId = PlatformId("ps1"))),
            )
            assertTrue(unmappedCalls.isEmpty())
        }

    @Test
    fun `happy path mints a token then queries games, and a second lookup reuses the cached token`() =
        runBlocking {
            val calls = mutableListOf<Call>()
            val tokenBody = """{"access_token":"tok-abc","expires_in":5011271,"token_type":"bearer"}"""
            val gamesBody =
                """[{"id":1,"name":"Blasphemous II","summary":"The Penitent One returns.","platforms":[6]}]"""
            val responses = mutableListOf<IgdbHttpResult?>(
                IgdbHttpResult(200, tokenBody),
                IgdbHttpResult(200, gamesBody),
            )
            val src = source(clockMillis = 1_000_000L, responses = responses, calls = calls)

            val outcome = src.lookupOutcome(query())
            val loaded = outcome as IgdbMetadataSource.LookupOutcome.Loaded
            assertEquals(MetadataOrigin.IGDB, loaded.record.origin)
            assertEquals("", loaded.record.canonicalTitle)
            assertEquals(null, loaded.record.providerGameId)
            assertEquals(1_000_000L + 86_400_000L, loaded.record.cacheExpiresAt)

            assertEquals(2, calls.size)
            assertEquals("https://id.twitch.tv/oauth2/token", calls[0].url)
            assertTrue(calls[0].body.contains("client_secret=secret-1"))
            assertFalse(calls[0].url.contains("secret-1"))

            assertEquals("https://api.igdb.com/v4/games", calls[1].url)
            assertEquals("client-1", calls[1].headers["Client-ID"])
            assertEquals("Bearer tok-abc", calls[1].headers["Authorization"])

            // A second lookup on the SAME source instance must not re-mint -- the cached token is still
            // fresh under the fixed clock.
            responses += IgdbHttpResult(200, gamesBody)
            src.lookupOutcome(query())
            assertEquals(3, calls.size)
            assertEquals("https://api.igdb.com/v4/games", calls[2].url)
        }

    @Test
    fun `a 401 remints once and retries to Loaded, a 429 fails with no further call`() = runBlocking {
        val gamesBody = """[{"id":1,"name":"Blasphemous II","summary":"Returns.","platforms":[6]}]"""

        val retryCalls = mutableListOf<Call>()
        val retryResponses = mutableListOf<IgdbHttpResult?>(
            IgdbHttpResult(200, """{"access_token":"tok-1","expires_in":5011271}"""),
            IgdbHttpResult(401, null),
            IgdbHttpResult(200, """{"access_token":"tok-2","expires_in":5011271}"""),
            IgdbHttpResult(200, gamesBody),
        )
        val retrySource = source(responses = retryResponses, calls = retryCalls)
        val retryOutcome = retrySource.lookupOutcome(query())
        assertTrue(retryOutcome is IgdbMetadataSource.LookupOutcome.Loaded)
        assertEquals(4, retryCalls.size)
        assertEquals("Bearer tok-2", retryCalls[3].headers["Authorization"])

        val rateLimitCalls = mutableListOf<Call>()
        val rateLimitResponses = mutableListOf<IgdbHttpResult?>(
            IgdbHttpResult(200, """{"access_token":"tok-1","expires_in":5011271}"""),
            IgdbHttpResult(429, null),
        )
        val rateLimitSource = source(responses = rateLimitResponses, calls = rateLimitCalls)
        val rateLimitOutcome = rateLimitSource.lookupOutcome(query())
        assertEquals(IgdbMetadataSource.LookupOutcome.Failed, rateLimitOutcome)
        assertEquals(2, rateLimitCalls.size)
    }

    /**
     * [IgdbMetadataSource.pace] used to stamp its last-call
     * time BEFORE pausing, so two fast round trips could each read a near-zero elapsed gap and both
     * wait only up to the spacing floor -- drifting under any real (non-zero-cost) round trip. A fixed
     * clock plus a recording `pause` pins the fix directly: the SECOND `games` POST (the ladder walks
     * two candidates because the first's response has no hits) must pause exactly
     * `MIN_CALL_SPACING_MILLIS(250) - elapsed`, where `elapsed` is simulated as 50ms between the two
     * calls. Nothing more is asserted here.
     */
    @Test
    fun `pace waits the spacing floor minus elapsed time before the second games call`() = runBlocking {
        var clockValue = 0L
        val pauses = mutableListOf<Long>()
        var gamesCalls = 0
        val source = IgdbMetadataSource(
            gateOpen = { true },
            registration = { IgdbCredentials("client-1", "secret-1") },
            httpPost = { url, _, _ ->
                when (url) {
                    "https://id.twitch.tv/oauth2/token" ->
                        IgdbHttpResult(200, """{"access_token":"tok-1","expires_in":5011271}""")
                    "https://api.igdb.com/v4/games" -> {
                        gamesCalls++
                        // 50ms elapses between the first games call answering and the second one being
                        // paced -- simulated here, at the one point the source has actually "gone out".
                        if (gamesCalls == 1) clockValue += 50L
                        IgdbHttpResult(200, "[]") // no hits -- the ladder walks to the next candidate
                    }
                    else -> null
                }
            },
            clock = { clockValue },
            pause = { ms -> pauses += ms },
        )

        source.lookupOutcome(query(candidateNames = listOf("Alpha", "Beta")))

        assertEquals(2, gamesCalls)
        assertEquals(listOf(200L), pauses) // 250 - 50 = 200; the first call paces from a null stamp (0).
    }
}
