package com.gruning.mimir.rewrite.core.metadata.online

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bulk completion read against RetroAchievements' DOCUMENTED response shape (`API_GetUserCompletionProgress.php`: `Count`, `Total`, `Results[]` with
 * `GameID`, `Title`, `ImageIcon`, `ConsoleID`, `ConsoleName`, `MaxPossible`, `NumAwarded`, `NumAwardedHardcore`, `MostRecentAwardedDate`, `HighestAwardKind`,
 * `HighestAwardDate`), over two pages. Nothing here touches a network: a recording transport answers, and the counts-only diagnostic lines are captured.
 */
class RetroAchievementsBulkDocumentedShapeTest {

    /** Page one as the documentation shows it: a hack with an award and no set (MaxPossible 0), and a game with softcore and hardcore counts. */
    private val firstPage = """
        {
          "Count": 2,
          "Total": 3,
          "Results": [
            {
              "GameID": 20246,
              "Title": "~Hack~ Knuckles the Echidna in Sonic the Hedgehog",
              "ImageIcon": "/Images/074560.png",
              "ConsoleID": 1,
              "ConsoleName": "Mega Drive / Genesis",
              "MaxPossible": 0,
              "NumAwarded": 0,
              "NumAwardedHardcore": 0,
              "MostRecentAwardedDate": "2023-10-27T02:52:34+00:00",
              "HighestAwardKind": "beaten-hardcore",
              "HighestAwardDate": "2023-10-27T02:52:34+00:00"
            },
            {
              "GameID": 228,
              "Title": "Sonic the Hedgehog",
              "ImageIcon": "/Images/001234.png",
              "ConsoleID": 1,
              "ConsoleName": "Mega Drive / Genesis",
              "MaxPossible": 55,
              "NumAwarded": 31,
              "NumAwardedHardcore": 12,
              "MostRecentAwardedDate": "2024-03-02T18:00:05+00:00",
              "HighestAwardKind": null,
              "HighestAwardDate": null
            }
          ]
        }
    """.trimIndent()

    /** Page two: a game the user has only ever earned in hardcore, so RA gives no softcore figure for it. */
    private val secondPage = """
        {
          "Count": 1,
          "Total": 3,
          "Results": [
            {
              "GameID": 1,
              "Title": "Super Mario World",
              "ImageIcon": "/Images/000001.png",
              "ConsoleID": 3,
              "ConsoleName": "SNES/Super Famicom",
              "MaxPossible": 96,
              "NumAwarded": null,
              "NumAwardedHardcore": 40,
              "MostRecentAwardedDate": "2024-05-06T07:08:09+00:00",
              "HighestAwardKind": "completed",
              "HighestAwardDate": "2024-05-06T07:08:09+00:00"
            }
          ]
        }
    """.trimIndent()

    private val lines = mutableListOf<String>()
    private val urls = mutableListOf<String>()

    private fun client(respond: (offset: Int) -> String) = RetroAchievementsClient(
        RetroAchievementsSource(
            gateOpen = { true },
            auth = { RetroAchievementsApi.Auth("player", "secret-key", credentialRevision = "rev-1") },
            httpGet = { url ->
                urls += url
                respond(url.substringAfter("&o=").substringBefore('&').toInt()).let { RaHttpResult.Body(it) }
            },
        ),
        diagnostic = { lines += it },
    )

    @Test
    fun `a documented two-page response is read to its total with the hardcore-only game kept`() = runBlocking {
        val outcome = client { offset -> if (offset == 0) firstPage else secondPage }.userGames() as RaUserGamesOutcome.Read

        assertEquals(3, outcome.totalGames)
        assertEquals(listOf(20246, 228, 1), outcome.games.map { it.gameId })
        assertEquals("page two is asked for at the offset page one reached", listOf(0, 2), urls.map { it.substringAfter("&o=").toInt() })

        val sonic = outcome.games[1]
        assertEquals(31, sonic.earned)
        assertEquals(55, sonic.total)
        assertEquals("Mega Drive / Genesis", sonic.consoleName)
        assertEquals("/Images/001234.png", sonic.imageIconPath)
        assertEquals(Instant.parse("2024-03-02T18:00:05Z").toEpochMilli(), sonic.lastAwardedAtMillis)

        val mario = outcome.games[2]
        assertEquals("a game with only hardcore awards shows its hardcore count", 40, mario.earned)
        assertEquals(96, mario.total)

        // The hack carries an award but no achievements: read, and not something the Activity page can show.
        val hack = outcome.games[0]
        assertEquals(0, hack.total)
        assertFalse(hack.isShowable)
        assertTrue(sonic.isShowable)
        assertTrue(mario.isShowable)
    }

    @Test
    fun `each page leaves one counts-only line`() = runBlocking {
        client { offset -> if (offset == 0) firstPage else secondPage }.userGames()

        assertEquals(listOf("bulk page=0 count=2 total=3 valid=1", "bulk page=1 count=1 total=3 valid=1"), lines)
        val shape = Regex("""bulk page=\d+ count=\d+ total=\d+ valid=\d+""")
        assertTrue("counts only", lines.all { shape.matches(it) })
        assertTrue("no key, name or title in a line", lines.none { "secret-key" in it || "player" in it || "Sonic" in it })
    }

    @Test
    fun `a first page with no games is a complete empty answer and its line says so`() = runBlocking {
        val outcome = client { """{"Count":0,"Total":0,"Results":[]}""" }.userGames() as RaUserGamesOutcome.Read

        assertTrue(outcome.games.isEmpty())
        assertEquals(0, outcome.totalGames)
        assertEquals(listOf("bulk page=0 count=0 total=0 valid=0"), lines)
        assertEquals("one call", 1, urls.size)
    }

    @Test
    fun `a row that only carries hardcore awards is read, whether the softcore field is null or absent`() {
        val nullField = RetroAchievementsApi.parseUserCompletionProgress(
            """{"Count":1,"Total":1,"Results":[{"GameID":7,"Title":"Hardcore Only","ConsoleName":"Game Boy","MaxPossible":20,"NumAwarded":null,"NumAwardedHardcore":9}]}""",
        )!!
        val absentField = RetroAchievementsApi.parseUserCompletionProgress(
            """{"Count":1,"Total":1,"Results":[{"GameID":7,"Title":"Hardcore Only","ConsoleName":"Game Boy","MaxPossible":20,"NumAwardedHardcore":9}]}""",
        )!!

        for (parsed in listOf(nullField, absentField)) {
            assertEquals("the row is not skipped (a skipped row made the whole read partial)", 0, parsed.skipped)
            assertEquals(9, parsed.results.single().numAwardedHardcore)
            assertEquals(9, parsed.results.single().numAwarded)
        }
    }

    @Test
    fun `a row with neither award count is still skipped and makes the read partial`() = runBlocking {
        val body = """{"Count":1,"Total":1,"Results":[{"GameID":7,"Title":"No Counts","ConsoleName":"Game Boy","MaxPossible":20}]}"""
        val parsed = RetroAchievementsApi.parseUserCompletionProgress(body)!!
        assertEquals(1, parsed.skipped)
        assertTrue(parsed.results.isEmpty())

        // A page whose every row was skipped has read nothing: the answer is unavailable, never an empty success.
        assertEquals(RaUserGamesOutcome.Unavailable, client { body }.userGames())
        assertEquals(listOf("bulk page=0 count=1 total=1 valid=0"), lines)
    }

    @Test
    fun `softcore and hardcore are one union, so the larger figure is the progress`() = runBlocking {
        val body = """{"Count":1,"Total":1,"Results":[{"GameID":7,"Title":"Mostly Hardcore","ConsoleName":"Game Boy","MaxPossible":20,"NumAwarded":3,"NumAwardedHardcore":9}]}"""
        val outcome = client { body }.userGames() as RaUserGamesOutcome.Read
        assertEquals(9, outcome.games.single().earned)
        assertNotNull(outcome.games.single())
        assertNull(outcome.games.single().lastAwardedAtMillis)
    }

    // ── "Match RA" : the hardcore count inside the union, and RA's own award word ───────────────────────────────────

    @Test
    fun `each game keeps its hardcore count INSIDE its earned count and carries the award RA reports`() = runBlocking {
        val outcome = client { offset -> if (offset == 0) firstPage else secondPage }.userGames() as RaUserGamesOutcome.Read
        val (hack, sonic, mario) = outcome.games

        assertEquals("the documented hack row names a beaten-hardcore award", RaAwardKind.BEATEN_HARDCORE, hack.awardKind)
        assertNull("a null HighestAwardKind is no award", sonic.awardKind)
        assertEquals(RaAwardKind.COMPLETED, mario.awardKind)

        assertEquals("31 earned across both modes, 12 of them in hardcore", 31, sonic.earned)
        assertEquals(12, sonic.earnedHardcore)
        assertEquals("a hardcore-only game: the union is the hardcore count, all of it hardcore", 40, mario.earned)
        assertEquals(40, mario.earnedHardcore)
        outcome.games.forEach { assertTrue("hardcore (${it.earnedHardcore}) never exceeds the union (${it.earned})", it.earnedHardcore <= it.earned) }
    }

    @Test
    fun `every award word RA documents is read and anything else is no award`() {
        fun kindOf(word: String): RaAwardKind? = RetroAchievementsApi.parseUserCompletionProgress(
            """{"Count":1,"Total":1,"Results":[{"GameID":7,"Title":"T","ConsoleName":"C","MaxPossible":20,"NumAwarded":3,"NumAwardedHardcore":1,"HighestAwardKind":$word}]}""",
        )!!.results.single().highestAwardKind.let { RaAwardKind.fromProvider(it) }

        assertEquals(RaAwardKind.BEATEN_SOFTCORE, kindOf("\"beaten-softcore\""))
        assertEquals(RaAwardKind.BEATEN_HARDCORE, kindOf("\"beaten-hardcore\""))
        assertEquals(RaAwardKind.COMPLETED, kindOf("\"completed\""))
        assertEquals(RaAwardKind.MASTERED, kindOf("\"mastered\""))
        assertNull("a word RA adds later is not guessed at", kindOf("\"legendary\""))
        assertNull(kindOf("\"\""))
        assertNull("a number is not a word", kindOf("5"))
        assertNull(kindOf("null"))
    }
}
