package com.gruning.mimir.rewrite.core.metadata.online

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Activity page's recent-badge strip read (`API_GetUserRecentAchievements.php`): one request for the whole window, an exact-parameter URL on the transport
 * allowlist, a tolerant parse that trusts a badge name for nothing but digits, and a client that reports rejection once. A recording transport stands in for the
 * network, so every test also counts the calls, and none of them has a real key or URL.
 */
class RetroAchievementsRecentUnlocksTest {

    private val auth = RetroAchievementsApi.Auth("player", "secret-key")

    private fun unlock(id: Int, game: Int, date: String?, badge: String? = "$id", hardcore: Int = 0, title: String = "Achievement $id") = buildString {
        append("""{"AchievementID":$id,"Title":"$title","Description":"Do thing $id","Points":10,"GameID":$game,"GameTitle":"Game $game","ConsoleName":"SNES","HardcoreMode":$hardcore""")
        if (date != null) append(""","Date":"$date"""")
        if (badge != null) append(""","BadgeName":"$badge"""")
        append("}")
    }

    private fun client(
        calls: MutableList<String>,
        rejectedRevisions: MutableList<String?> = mutableListOf(),
        respond: (String) -> RaHttpResult,
    ) = RetroAchievementsClient(
        RetroAchievementsSource(
            gateOpen = { true },
            auth = { RetroAchievementsApi.Auth("player", "secret-key", credentialRevision = "rev-1") },
            httpGet = { url ->
                calls += RetroAchievementsApi.redact(url) + "?" + url.substringAfter('?').split('&').filter { it.startsWith("m=") }.joinToString("&")
                respond(url)
            },
        ),
        onCredentialsRejected = { rejectedRevisions += it },
    )

    @Test
    fun `the recent url is exact and passes the transport allowlist, and nothing else does`() {
        val url = RetroAchievementsApi.userRecentAchievementsUrl(auth)
        assertTrue(url.contains("API_GetUserRecentAchievements.php"))
        assertTrue("a thirty-day window: $url", url.endsWith("&m=43200"))
        assertEquals(43_200, RetroAchievementsApi.RECENT_UNLOCKS_WINDOW_MINUTES)
        assertTrue(retroAchievementsApiUrlAllowed(url))
        assertEquals("API_GetUserRecentAchievements.php", RetroAchievementsApi.redact(url))
        // Extra, missing or odd parameters are refused, like every other endpoint.
        assertFalse(retroAchievementsApiUrlAllowed("$url&x=1"))
        assertFalse(retroAchievementsApiUrlAllowed("$url&z=player"))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("&m=43200", "")))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("&m=43200", "&m=0")))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("&m=43200", "&m=-5")))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("&m=43200", "&m=1e3")))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("&m=43200", "&m=43201")))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("&m=43200", "&m=")))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("&u=player", "")))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("&u=player", "&u=")))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("y=secret-key&", "")))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("https://retroachievements.org", "https://evil.example")))
        assertFalse(retroAchievementsApiUrlAllowed(url.replace("API_GetUserRecentAchievements.php", "API_GetUserRecentAchievements.php/..")))
        assertFalse(retroAchievementsApiUrlAllowed("$url#frag"))
        // A smaller window, like the old default hour, is allowed (it is the same exact shape).
        assertTrue(retroAchievementsApiUrlAllowed(RetroAchievementsApi.userRecentAchievementsUrl(auth, minutes = 60)))
    }

    @Test
    fun `the answer is newest first, one row per achievement, and a malformed row is skipped`() {
        val body = "[" + listOf(
            unlock(1, 10, "2023-10-26 22:47:17"),
            unlock(2, 11, "2023-10-28 08:00:00", hardcore = 1),
            unlock(3, 10, "2023-10-27 12:00:00"),
            // The same achievement earned twice: the newest row stays.
            unlock(1, 10, "2023-10-20 01:00:00", hardcore = 1),
            """{"AchievementID":0,"Title":"No id","GameID":5}""",
            """{"AchievementID":9,"Title":"","GameID":5}""",
            """{"AchievementID":8,"Title":"No game","GameID":0}""",
            "7",
            unlock(4, 12, null),
        ).joinToString(",") + "]"
        val parsed = RetroAchievementsApi.parseRecentUnlocks(body)!!
        assertEquals(listOf(2, 3, 1, 4), parsed.map { it.achievementId })
        val first = parsed.first()
        assertEquals("Achievement 2", first.title)
        assertEquals("Do thing 2", first.description)
        assertEquals(10, first.points)
        assertEquals(11, first.gameId)
        assertEquals("Game 11", first.gameTitle)
        assertEquals("SNES", first.consoleName)
        assertTrue(first.hardcore)
        assertEquals(Instant.parse("2023-10-28T08:00:00Z").toEpochMilli(), first.awardedAtMillis)
        assertFalse(parsed[2].hardcore)
        assertEquals("the newest of the twice-earned one", Instant.parse("2023-10-26T22:47:17Z").toEpochMilli(), parsed[2].awardedAtMillis)
        assertNull("no date is no date, and sorts last", parsed.last().awardedAtMillis)
    }

    @Test
    fun `a badge name is trusted for digits only`() {
        val body = "[" + listOf(
            unlock(1, 1, "2023-10-26 22:47:17", badge = "00123"),
            unlock(2, 1, "2023-10-25 22:47:17", badge = "../../secret"),
            unlock(3, 1, "2023-10-24 22:47:17", badge = "12a"),
            unlock(4, 1, "2023-10-23 22:47:17", badge = null),
            unlock(5, 1, "2023-10-22 22:47:17", badge = "https://evil.example/x"),
        ).joinToString(",") + "]"
        val parsed = RetroAchievementsApi.parseRecentUnlocks(body)!!
        assertEquals(listOf("00123", null, null, null, null), parsed.map { it.badgeName })
        assertEquals("/Badge/00123.png", RetroAchievementsIcons.badgePath(parsed.first().badgeName, locked = false))
        assertNull(RetroAchievementsIcons.badgePath(parsed[1].badgeName, locked = false))
    }

    @Test
    fun `an empty window is a real empty answer and anything that is not an array is unusable`() {
        assertEquals(emptyList<RetroAchievementsApi.RecentUnlock>(), RetroAchievementsApi.parseRecentUnlocks("[]"))
        assertNull(RetroAchievementsApi.parseRecentUnlocks("not json"))
        assertNull(RetroAchievementsApi.parseRecentUnlocks(""))
        assertNull(RetroAchievementsApi.parseRecentUnlocks(null))
        assertNull(RetroAchievementsApi.parseRecentUnlocks("""{"Results":[]}"""))
    }

    @Test
    fun `a refresh is one call for the whole window`() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = client(calls) { RaHttpResult.Body("[" + unlock(1, 10, "2023-10-26 22:47:17") + "," + unlock(2, 11, "2023-10-27 22:47:17") + "]") }.recentUnlocks()
        outcome as RaRecentOutcome.Read
        assertEquals(listOf("API_GetUserRecentAchievements.php?m=43200"), calls)
        assertEquals(listOf(2, 1), outcome.unlocks.map { it.achievementId })
        assertEquals("Game 11", outcome.unlocks.first().gameTitle)
    }

    @Test
    fun `an unreachable or unusable answer is unavailable, and a rejected key is reported once`() = runBlocking {
        assertEquals(RaRecentOutcome.Unavailable, client(mutableListOf()) { RaHttpResult.Unreachable }.recentUnlocks())
        assertEquals(RaRecentOutcome.Unavailable, client(mutableListOf()) { RaHttpResult.NotFound }.recentUnlocks())
        assertEquals(RaRecentOutcome.Unavailable, client(mutableListOf()) { RaHttpResult.Body("not json") }.recentUnlocks())
        val calls = mutableListOf<String>()
        val rejected = mutableListOf<String?>()
        assertEquals(RaRecentOutcome.CredentialsRejected, client(calls, rejected) { RaHttpResult.Rejected }.recentUnlocks())
        assertEquals(listOf<String?>("rev-1"), rejected)
        assertEquals(1, calls.size)
    }

    @Test
    fun `an empty window reads as an empty list, not as unavailable`() = runBlocking {
        val outcome = client(mutableListOf()) { RaHttpResult.Body("[]") }.recentUnlocks()
        assertEquals(RaRecentOutcome.Read(emptyList()), outcome)
    }
}
