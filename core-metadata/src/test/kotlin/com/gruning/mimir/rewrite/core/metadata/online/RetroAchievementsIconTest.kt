package com.gruning.mimir.rewrite.core.metadata.online

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The game icon the bulk completion read carries: the `ImageIcon` path is parsed and validated (a relative `/Images/<digits>.png` and nothing else), the one
 * URL it can become is exact, and the fetch is keyless, gated, bounded, and not an API call. Nothing here touches a network; a recording transport stands in.
 */
class RetroAchievementsIconTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

    private fun page(vararg rows: String) =
        """{"Count":${rows.size},"Total":${rows.size},"Results":[${rows.joinToString(",")}]}"""

    private fun row(id: Int, icon: String?): String {
        val iconField = if (icon == null) "" else ",\"ImageIcon\":" + icon
        return """{"GameID":$id,"Title":"Game $id","ConsoleName":"Game Boy","MaxPossible":10,"NumAwarded":3,"NumAwardedHardcore":0$iconField}"""
    }

    @Test
    fun `a good icon path is kept and an absolute or malicious one is rejected`() {
        val good = RetroAchievementsApi.parseUserCompletionProgress(page(row(1, "\"/Images/061592.png\"")))!!
        assertEquals("/Images/061592.png", good.results.single().imageIconPath)

        listOf(
            "https://evil.example/Images/061592.png",
            "http://media.retroachievements.org/Images/061592.png",
            "//evil.example/Images/1.png",
            "/Images/../secret.png",
            "/Images/1.png?token=abc",
            "/Images/1.png#frag",
            "/Images/1.PNG",
            "/Images/1.jpg",
            "/Images/12a.png",
            "/Images/.png",
            "Images/1.png",
            "/Other/1.png",
            "/Images/1.png\n",
            "/Images/1234567890123.png",
            "",
        ).forEach { bad ->
            val parsed = RetroAchievementsApi.parseUserCompletionProgress(page(row(1, "\"$bad\"".replace("\n", "\\n"))))!!
            assertNull("rejected: $bad", parsed.results.single().imageIconPath)
            // The row itself still counts: a bad icon never blanks a game's progress.
            assertEquals(3, parsed.results.single().numAwarded)
        }
        // A missing field and a non-string field are simply no icon.
        assertNull(RetroAchievementsApi.parseUserCompletionProgress(page(row(1, null)))!!.results.single().imageIconPath)
        assertNull(RetroAchievementsApi.parseUserCompletionProgress(page(row(1, """{"a":1}""")))!!.results.single().imageIconPath)
        assertNull(RetroAchievementsApi.parseUserCompletionProgress(page(row(1, "null")))!!.results.single().imageIconPath)
    }

    @Test
    fun `a valid path becomes exactly one https url on the media host and nothing else`() {
        assertEquals("https://media.retroachievements.org/Images/061592.png", RetroAchievementsIcons.url("/Images/061592.png"))
        assertEquals("061592.png", RetroAchievementsIcons.fileName("/Images/061592.png"))
        assertNull(RetroAchievementsIcons.url("https://media.retroachievements.org/Images/1.png"))
        assertNull(RetroAchievementsIcons.url(null))
        assertNull(RetroAchievementsIcons.fileName("/Images/../x.png"))

        val url = RetroAchievementsIcons.url("/Images/42.png")!!
        assertTrue(retroAchievementsIconUrlAllowed(url))
        listOf(
            "http://media.retroachievements.org/Images/42.png",
            "https://retroachievements.org/Images/42.png",
            "https://media.retroachievements.org.evil.example/Images/42.png",
            "https://user@media.retroachievements.org/Images/42.png",
            "https://media.retroachievements.org:8443/Images/42.png",
            "https://media.retroachievements.org/Images/42.png?y=key",
            "https://media.retroachievements.org/Images/42.png#x",
            "https://media.retroachievements.org/Images/42.jpg",
            "https://media.retroachievements.org/Badge/abc.png",
            "https://media.retroachievements.org/Badge/42_lock.png.exe",
            "https://media.retroachievements.org/Badge/42_unlock.png",
            "https://media.retroachievements.org/Badge/../Images/42.png",
        ).forEach { assertFalse("refused: $it", retroAchievementsIconUrlAllowed(it)) }
        // The credential-bearing API policy never accepts a media URL, and the icon policy never accepts an API URL.
        assertFalse(retroAchievementsApiUrlAllowed(url))
        assertFalse(retroAchievementsIconUrlAllowed(RetroAchievementsApi.userCompletionProgressUrl(RetroAchievementsApi.Auth("u", "k"), 0)))
    }

    @Test
    fun `the user game carries the validated icon path through the client`() = runBlocking {
        val client = RetroAchievementsClient(
            RetroAchievementsSource(
                gateOpen = { true },
                auth = { RetroAchievementsApi.Auth("player", "secret-key") },
                httpGet = { RaHttpResult.Body(page(row(1, "\"/Images/000123.png\""), row(2, "\"https://evil.example/x.png\""))) },
            ),
        )
        val read = client.userGames() as RaUserGamesOutcome.Read
        assertEquals(listOf("/Images/000123.png", null), read.games.map { it.imageIconPath })
    }

    @Test
    fun `an icon is one media request, no api call, and only through the opt-in gate`() = runBlocking {
        val apiCalls = mutableListOf<String>()
        val iconCalls = mutableListOf<String>()
        var open = true
        val source = RetroAchievementsSource(
            gateOpen = { open },
            auth = { RetroAchievementsApi.Auth("player", "secret-key") },
            httpGet = { url -> apiCalls += url; RaHttpResult.Unreachable },
            iconGet = { url -> iconCalls += url; png },
        )

        assertArrayEquals(png, source.iconBytes("/Images/061592.png"))
        assertEquals(listOf("https://media.retroachievements.org/Images/061592.png"), iconCalls)
        // The URL carries no credential and no username.
        assertFalse(iconCalls.single().contains("secret-key"))
        assertFalse(iconCalls.single().contains("player"))
        // Not one RetroAchievements API call for the art.
        assertEquals(emptyList<String>(), apiCalls)

        // A closed gate asks nothing; an invalid path asks nothing.
        open = false
        assertNull(source.iconBytes("/Images/061592.png"))
        open = true
        assertNull(source.iconBytes("https://evil.example/Images/1.png"))
        assertNull(source.iconBytes("/Images/../1.png"))
        assertEquals(1, iconCalls.size)
        assertEquals(emptyList<String>(), apiCalls)
    }

    @Test
    fun `a body that is not a png is not an icon`() = runBlocking {
        val source = RetroAchievementsSource(
            gateOpen = { true },
            auth = { null },
            iconGet = { "<html>not an image</html>".toByteArray() },
        )
        assertNull(source.iconBytes("/Images/1.png"))
        assertFalse(looksLikeRetroAchievementsPng(ByteArray(0)))
        assertTrue(looksLikeRetroAchievementsPng(png))
    }

    @Test
    fun `staging writes the icon atomically and a failed fetch leaves no file`() = runBlocking {
        val dir = temp.newFolder("ra_icons")
        val target = File(dir, "061592.png")
        val ok = RetroAchievementsClient(
            RetroAchievementsSource(gateOpen = { true }, auth = { null }, iconGet = { png }),
        )
        assertTrue(ok.stageIcon("/Images/061592.png", target))
        assertArrayEquals(png, target.readBytes())
        assertFalse(File(dir, "061592.png.partial").exists())

        val missing = File(dir, "7.png")
        val failing = RetroAchievementsClient(
            RetroAchievementsSource(gateOpen = { true }, auth = { null }, iconGet = { null }),
        )
        assertFalse(failing.stageIcon("/Images/7.png", missing))
        assertFalse(missing.exists())
        assertFalse(File(dir, "7.png.partial").exists())

        val closed = RetroAchievementsClient(
            RetroAchievementsSource(gateOpen = { false }, auth = { null }, iconGet = { png }),
        )
        assertFalse(closed.stageIcon("/Images/8.png", File(dir, "8.png")))
        assertFalse(File(dir, "8.png").exists())
    }

    @Test
    fun `the bounded reader refuses a body past the cap`() {
        assertNotNull(readRetroAchievementsBytesBounded(ByteArrayInputStream(ByteArray(100)), 100))
        assertNull(readRetroAchievementsBytesBounded(ByteArrayInputStream(ByteArray(101)), 100))
        assertNull(readRetroAchievementsBytesBounded(ByteArrayInputStream(ByteArray(0)), 100))
        assertEquals(256L * 1024L, RetroAchievementsIcons.MAX_BYTES)
    }

    // ── badges ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a badge name is digits only and anything else is rejected`() {
        assertEquals("00123", RetroAchievementsIcons.validatedBadgeName("00123"))
        listOf("", "12a", "../1", "1/2", "1.png", "1_lock", " 1", "1\n", "1234567890123", "https://evil.example/1").forEach { bad ->
            assertNull("rejected: $bad", RetroAchievementsIcons.validatedBadgeName(bad))
            assertNull("no path for: $bad", RetroAchievementsIcons.badgePath(bad, locked = false))
        }
        assertNull(RetroAchievementsIcons.validatedBadgeName(null))
    }

    @Test
    fun `an earned badge and a locked one are two different exact urls`() {
        assertEquals("/Badge/00123.png", RetroAchievementsIcons.badgePath("00123", locked = false))
        assertEquals("/Badge/00123_lock.png", RetroAchievementsIcons.badgePath("00123", locked = true))
        assertEquals("https://media.retroachievements.org/Badge/00123.png", RetroAchievementsIcons.url("/Badge/00123.png"))
        assertEquals("https://media.retroachievements.org/Badge/00123_lock.png", RetroAchievementsIcons.url("/Badge/00123_lock.png"))
        assertTrue(retroAchievementsIconUrlAllowed(RetroAchievementsIcons.url("/Badge/00123_lock.png")!!))
        assertTrue(retroAchievementsIconUrlAllowed(RetroAchievementsIcons.url("/Badge/00123.png")!!))
        // A badge never shares a file name with a game image, and earned and locked never share one with each other.
        assertEquals("badge_00123.png", RetroAchievementsIcons.fileName("/Badge/00123.png"))
        assertEquals("badge_00123_lock.png", RetroAchievementsIcons.fileName("/Badge/00123_lock.png"))
        assertEquals("00123.png", RetroAchievementsIcons.fileName("/Images/00123.png"))
        // The game-image validator still refuses a badge path: it is the ImageIcon contract.
        assertNull(RetroAchievementsIcons.validatedPath("/Badge/00123.png"))
    }

    @Test
    fun `the game progress carries the badge, the dates and the game images, each validated`() {
        val body = """
            {"ID":7,"Title":"Game","NumAchievements":2,"NumAwardedToUser":1,"NumAwardedToUserHardcore":1,
             "ImageTitle":"/Images/000010.png","ImageIngame":"https://evil.example/x.png","ImageIcon":"/Images/000012.png",
             "Achievements":{
              "1":{"ID":1,"Title":"First","Description":"Do it","Points":5,"BadgeName":"00042","DateEarned":"2023-05-01 12:34:56","DateEarnedHardcore":"2023-05-01 12:34:56"},
              "2":{"ID":2,"Title":"Second","Description":"","Points":10,"BadgeName":"../evil"}}}
        """.trimIndent()
        val progress = RetroAchievementsApi.parseGameProgress(body)!!
        assertEquals("/Images/000010.png", progress.imageTitlePath)
        assertNull("an absolute url is not an image path", progress.imageIngamePath)
        assertEquals("/Images/000012.png", progress.imageIconPath)
        val first = progress.achievements[0]
        assertEquals("00042", first.badgeName)
        assertEquals("2023-05-01 12:34:56", first.earnedAt)
        assertTrue(first.unlockedHardcore)
        val second = progress.achievements[1]
        assertNull("a non-digit badge name is dropped", second.badgeName)
        assertNull(second.earnedAt)
        assertFalse(second.unlocked)
    }

    @Test
    fun `a badge is one media request through the same gate and never an api call`() = runBlocking {
        val apiCalls = mutableListOf<String>()
        val mediaCalls = mutableListOf<String>()
        val source = RetroAchievementsSource(
            gateOpen = { true },
            auth = { RetroAchievementsApi.Auth("player", "secret-key") },
            httpGet = { url -> apiCalls += url; RaHttpResult.Unreachable },
            iconGet = { url -> mediaCalls += url; png },
        )
        assertArrayEquals(png, source.iconBytes("/Badge/00042_lock.png"))
        assertArrayEquals(png, source.iconBytes("/Badge/00042.png"))
        assertNull(source.iconBytes("/Badge/not-digits.png"))
        assertEquals(
            listOf("https://media.retroachievements.org/Badge/00042_lock.png", "https://media.retroachievements.org/Badge/00042.png"),
            mediaCalls,
        )
        assertEquals(emptyList<String>(), apiCalls)
    }
}
