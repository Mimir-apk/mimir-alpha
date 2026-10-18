package com.gruning.mimir.rewrite.core.metadata.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Activity page's two cold-start reads: the user's completion progress (every game with progress, paged) and their points (the profile).
 * A recording transport stands in for the network, so every test also counts the calls, and none of them has a real key or URL.
 */
class RetroAchievementsBulkReadTest {

    private val auth = RetroAchievementsApi.Auth("player", "secret-key")

    private fun row(id: Int, awarded: Int, max: Int, console: String = "Super Nintendo Entertainment System") =
        """{"GameID":$id,"Title":"Game $id","ConsoleName":"$console","MaxPossible":$max,"NumAwarded":$awarded,"NumAwardedHardcore":0}"""

    private fun page(total: Int, vararg rows: String) =
        """{"Count":${rows.size},"Total":$total,"Results":[${rows.joinToString(",")}]}"""

    private fun client(
        calls: MutableList<String>,
        rejectedRevisions: MutableList<String?> = mutableListOf(),
        respond: (String) -> RaHttpResult,
    ) = RetroAchievementsClient(
        RetroAchievementsSource(
            gateOpen = { true },
            auth = { RetroAchievementsApi.Auth("player", "secret-key", credentialRevision = "rev-1") },
            httpGet = { url ->
                calls += RetroAchievementsApi.redact(url) + "?" + url.substringAfter('?').split('&').filter { it.startsWith("o=") || it.startsWith("c=") }.joinToString("&")
                respond(url)
            },
        ),
        onCredentialsRejected = { rejectedRevisions += it },
    )

    @Test
    fun `the completion url is exact and passes the transport allowlist`() {
        val url = RetroAchievementsApi.userCompletionProgressUrl(auth, offset = 500)
        assertTrue(url.contains("API_GetUserCompletionProgress.php"))
        assertTrue(url.contains("&c=500&o=500"))
        assertTrue(retroAchievementsApiUrlAllowed(url))
        // Extra or missing parameters are refused, like every other endpoint.
        assertFalse(retroAchievementsApiUrlAllowed("$url&x=1"))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("&o=500", "")))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("&o=500", "&o=-1")))
        // The loggable name carries no credential.
        assertEquals("API_GetUserCompletionProgress.php", RetroAchievementsApi.redact(url))
    }

    @Test
    fun `a completion page parses its rows and skips a malformed one`() {
        val parsed = RetroAchievementsApi.parseUserCompletionProgress(
            page(2, row(1, 3, 10), """{"GameID":0,"Title":"Bad","MaxPossible":5,"NumAwarded":1}""", row(2, 0, 4)),
        )!!
        assertEquals(2, parsed.total)
        assertEquals(listOf(1, 2), parsed.results.map { it.gameId })
        assertEquals("the page says it skipped one", 1, parsed.skipped)
        assertEquals(3, parsed.results[0].numAwarded)
        assertEquals(10, parsed.results[0].maxPossible)
        assertNull(RetroAchievementsApi.parseUserCompletionProgress("not json"))
        assertNull(RetroAchievementsApi.parseUserCompletionProgress("""{"Count":0,"Results":[]}"""))
    }

    @Test
    fun `points are read from the profile and an absent field is not zero`() {
        assertEquals(RetroAchievementsApi.UserPoints(1200, 300), RetroAchievementsApi.parseUserPoints("""{"User":"p","TotalPoints":1200,"TotalSoftcorePoints":300}"""))
        assertEquals("the softcore score is its own number", 300, RetroAchievementsApi.parseUserPoints("""{"TotalPoints":1200,"TotalSoftcorePoints":300}""")!!.softcore)
        assertEquals(RetroAchievementsApi.UserPoints(40, 0), RetroAchievementsApi.parseUserPoints("""{"TotalPoints":40}"""))
        assertNull(RetroAchievementsApi.parseUserPoints("""{"User":"p"}"""))
    }

    @Test
    fun `one page is one call and a paged user is read to the reported total`() = runBlocking {
        val single = mutableListOf<String>()
        val one = client(single) { RaHttpResult.Body(page(2, row(1, 3, 10), row(2, 0, 4))) }.userGames() as RaUserGamesOutcome.Read
        assertEquals(listOf("API_GetUserCompletionProgress.php?c=500&o=0"), single)
        assertEquals(listOf(1, 2), one.games.map { it.gameId })
        assertEquals(2, one.totalGames)

        val paged = mutableListOf<String>()
        val bodies = mapOf(
            "o=0" to page(5, row(1, 1, 5), row(2, 2, 5), row(3, 3, 5)),
            "o=3" to page(5, row(4, 4, 5), row(5, 5, 5)),
        )
        val all = client(paged) { url -> RaHttpResult.Body(bodies.getValue(url.substringAfter("&c=500&").substringBefore('&'))) }.userGames() as RaUserGamesOutcome.Read
        assertEquals(listOf("API_GetUserCompletionProgress.php?c=500&o=0", "API_GetUserCompletionProgress.php?c=500&o=3"), paged)
        assertEquals(listOf(1, 2, 3, 4, 5), all.games.map { it.gameId })
        assertEquals(listOf(1, 2, 3, 4, 5), all.games.map { it.earned })
    }

    @Test
    fun `a failed later page is a partial answer and never a success`() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = client(calls) { url ->
            if ("o=0" in url) RaHttpResult.Body(page(4, row(1, 1, 5), row(2, 2, 5))) else RaHttpResult.Unreachable
        }.userGames()
        // The pages that were read are reported (for diagnostics) but the answer is not a Read: nothing can publish or save it as everything.
        assertTrue(outcome is RaUserGamesOutcome.Partial)
        outcome as RaUserGamesOutcome.Partial
        assertEquals(listOf(1, 2), outcome.games.map { it.gameId })
        assertEquals(4, outcome.totalGames)
        assertEquals(2, calls.size)
    }

    @Test
    fun `a page with a malformed row is partial even when the reported total is reached`() = runBlocking {
        // RA says 2 games and the page counts 2 rows, but one row cannot be read: counting it must not make the read complete.
        val calls = mutableListOf<String>()
        val bad = """{"GameID":0,"Title":"Bad","MaxPossible":5,"NumAwarded":1}"""
        val outcome = client(calls) { RaHttpResult.Body(page(2, row(1, 3, 10), bad)) }.userGames()

        assertTrue(outcome is RaUserGamesOutcome.Partial)
        outcome as RaUserGamesOutcome.Partial
        assertEquals(listOf(1), outcome.games.map { it.gameId })
        assertEquals(2, outcome.totalGames)
        assertEquals(1, calls.size)
    }

    @Test
    fun `a malformed row on a later page makes the whole read partial, and pages are still read to the end`() = runBlocking {
        val calls = mutableListOf<String>()
        val bad = """{"GameID":0,"Title":"Bad","MaxPossible":5,"NumAwarded":1}"""
        val bodies = mapOf(
            "o=0" to page(4, row(1, 1, 5), row(2, 2, 5)),
            "o=2" to page(4, row(3, 3, 5), bad),
        )
        val outcome = client(calls) { url -> RaHttpResult.Body(bodies.getValue(url.substringAfter("&c=500&").substringBefore('&'))) }.userGames()

        assertTrue(outcome is RaUserGamesOutcome.Partial)
        assertEquals(listOf(1, 2, 3), (outcome as RaUserGamesOutcome.Partial).games.map { it.gameId })
        assertEquals(2, calls.size)
    }

    @Test
    fun `a page whose every row is malformed is unavailable, not an empty answer`() = runBlocking {
        val bad = """{"GameID":0,"Title":"Bad","MaxPossible":5,"NumAwarded":1}"""
        val outcome = client(mutableListOf()) { RaHttpResult.Body(page(1, bad)) }.userGames()
        assertEquals(RaUserGamesOutcome.Unavailable, outcome)
    }

    @Test
    fun `a pinned sign-in is used for every page and request, whatever the stored one becomes meanwhile`() = runBlocking {
        var stored = RetroAchievementsApi.Auth("alice", "alice-key", credentialRevision = "rev-a")
        val urls = mutableListOf<String>()
        val bodies = mapOf(
            "o=0" to page(4, row(1, 1, 5), row(2, 2, 5)),
            "o=2" to page(4, row(3, 3, 5), row(4, 4, 5)),
        )
        val client = RetroAchievementsClient(
            RetroAchievementsSource(
                gateOpen = { true },
                auth = { stored },
                httpGet = { url ->
                    urls += url
                    // The stored sign-in changes after the very first request, as if the user switched account during the read.
                    stored = RetroAchievementsApi.Auth("bob", "bob-key", credentialRevision = "rev-b")
                    when {
                        "API_GetUserCompletionProgress" in url -> RaHttpResult.Body(bodies.getValue(url.substringAfter("&c=500&").substringBefore('&')))
                        "API_GetUserProfile" in url -> RaHttpResult.Body("""{"User":"alice","TotalPoints":10,"TotalSoftcorePoints":0}""")
                        else -> RaHttpResult.Body("[]")
                    }
                },
            ),
        )
        val pin = RetroAchievementsAccount(username = "alice", apiKey = "alice-key", credentialRevision = "rev-a")

        val games = client.userGames(pin) as RaUserGamesOutcome.Read
        client.userPoints(pin)
        client.recentUnlocks(pin)

        assertEquals(listOf(1, 2, 3, 4), games.games.map { it.gameId })
        assertEquals(4, urls.size)
        assertTrue("every request carried the pinned key", urls.all { "alice-key" in it })
        assertTrue("and none the stored one", urls.none { "bob-key" in it })

        // Without a pin each request reads the stored sign-in, as it always did.
        urls.clear()
        client.userPoints()
        assertTrue(urls.single().contains("bob-key"))
    }

    @Test
    fun `a pinned read still stops when the opt-in closes, and a rejected pinned key reports its own revision`() = runBlocking {
        var open = true
        val calls = mutableListOf<String>()
        val rejected = mutableListOf<String?>()
        val client = RetroAchievementsClient(
            RetroAchievementsSource(
                gateOpen = { open },
                auth = { null },
                httpGet = { url -> calls += RetroAchievementsApi.redact(url); RaHttpResult.Rejected },
            ),
            onCredentialsRejected = { rejected += it },
        )
        val pin = RetroAchievementsAccount("alice", "alice-key", "rev-a")

        assertEquals(RaUserGamesOutcome.CredentialsRejected, client.userGames(pin))
        assertEquals(listOf<String?>("rev-a"), rejected)

        open = false
        calls.clear()
        assertEquals(RaUserGamesOutcome.Unavailable, client.userGames(pin))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a failed first page is unavailable, not partial`() = runBlocking {
        val outcome = client(mutableListOf()) { RaHttpResult.Unreachable }.userGames()
        assertEquals(RaUserGamesOutcome.Unavailable, outcome)
    }

    @Test
    fun `a rejected key is reported once through the callback and never as an empty list`() = runBlocking {
        val calls = mutableListOf<String>()
        val rejected = mutableListOf<String?>()
        val outcome = client(calls, rejected) { RaHttpResult.Rejected }.userGames()
        assertEquals(RaUserGamesOutcome.CredentialsRejected, outcome)
        assertEquals(listOf<String?>("rev-1"), rejected)
        assertEquals(1, calls.size)

        val pointsRejected = mutableListOf<String?>()
        assertEquals(RaPointsOutcome.CredentialsRejected, client(mutableListOf(), pointsRejected) { RaHttpResult.Rejected }.userPoints())
        assertEquals(listOf<String?>("rev-1"), pointsRejected)
    }

    @Test
    fun `points are one profile call`() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = client(calls) { RaHttpResult.Body("""{"User":"player","ULID":"x","TotalPoints":900,"TotalSoftcorePoints":100}""") }.userPoints()
        assertEquals("the two scores come apart and are never summed", RaPointsOutcome.Read(points = 900, softcorePoints = 100), outcome)
        assertEquals(1, calls.size)
        assertTrue(calls.single().startsWith("API_GetUserProfile.php"))
    }

    @Test
    fun `a console name maps to a Mimir platform and an unknown one to none`() {
        val client = client(mutableListOf()) { RaHttpResult.Unreachable }
        assertEquals("snes", client.platformIdForConsoleName("Super Nintendo Entertainment System"))
        assertNull(client.platformIdForConsoleName("Hubs"))
    }

    @Test
    fun `an empty page before the reported total is an incomplete answer and not a success`() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = client(calls) { url ->
            if ("o=0" in url) RaHttpResult.Body(page(5, row(1, 1, 5), row(2, 2, 5))) else RaHttpResult.Body(page(5))
        }.userGames()
        assertEquals(RaUserGamesOutcome.Partial(listOf(RetroAchievementsUserGame(1, "Game 1", "Super Nintendo Entertainment System", 1, 5), RetroAchievementsUserGame(2, "Game 2", "Super Nintendo Entertainment System", 2, 5)), 5), outcome)
        assertEquals(2, calls.size)
    }

    @Test
    fun `hitting the page cap before the total is an incomplete answer and not a success`() = runBlocking {
        val calls = mutableListOf<String>()
        // A user RA says has 100000 games: every page is full, so the read stops at the cap with most of them unread.
        val outcome = client(calls) { url ->
            val offset = url.substringAfter("&o=").substringBefore('&').toInt()
            RaHttpResult.Body(page(100_000, *(0 until 500).map { row(offset + it + 1, 1, 5) }.toTypedArray()))
        }.userGames()
        assertTrue(outcome is RaUserGamesOutcome.Partial)
        assertEquals(100_000, (outcome as RaUserGamesOutcome.Partial).totalGames)
        assertEquals(40 * 500, outcome.games.size)
        assertEquals(40, calls.size)
    }

    @Test
    fun `a user with no games at all is a complete empty answer`() = runBlocking {
        val outcome = client(mutableListOf()) { RaHttpResult.Body(page(0)) }.userGames() as RaUserGamesOutcome.Read
        assertTrue(outcome.games.isEmpty())
        assertEquals(0, outcome.totalGames)
    }
}
