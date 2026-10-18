package com.gruning.mimir.rewrite.core.metadata.online

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a per-game read leaves in the durable ledger ([RetroAchievementsLedger.upsertBulkGames] and [toUserGame]): the row joins the account's saved list without
 * touching the rest of it, the same row twice writes nothing, and the ledger's own bounds, account separation and last-moment guard apply. Plain JVM.
 */
class RetroAchievementsLedgerLaneUpsertTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root get() = File(temp.root, "ra_ledger")
    private fun ledger() = RetroAchievementsLedger(root)
    private fun bulkFile(account: String) = File(File(root, RetroAchievementsLedger.accountKey(account)!!), "bulk.json")

    private fun game(id: Int, earned: Int = 3, total: Int = 10, icon: String? = "/Images/%06d.png".format(id), awarded: Long? = 1_700_000_000_000L + id) =
        RetroAchievementsUserGame(id, "Game $id", "Nintendo 64", earned, total, icon, awarded)

    private fun progress(gameId: Int, unlocked: Int, count: Int = 4, console: String? = "Nintendo 64") = RetroAchievementsProgress(
        gameId = gameId,
        title = "Game $gameId",
        achievements = (1..count).map {
            RetroAchievementsAchievement(
                id = gameId * 100 + it,
                title = "A$it",
                description = "d$it",
                points = 5,
                unlocked = it <= unlocked,
                unlockedHardcore = it == 1 && unlocked > 0,
                earnedAt = if (it <= unlocked) "2026-01-0$it 10:00:00" else null,
                earnedHardcoreAt = if (it == 1 && unlocked > 0) "2026-01-01 10:00:00" else null,
            )
        },
        imageIconPath = "/Images/%06d.png".format(gameId + 2),
        consoleName = console,
    )

    // upsertBulkGames --------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a row joins an account with no saved read, and a restart finds it`() {
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger().upsertBulkGames("player", listOf(game(1)), nowMillis = 50L))

        val back = RetroAchievementsLedger(root).readBulk("player")!!
        assertEquals(listOf(game(1)), back.games)
        assertEquals(50L, back.savedAtMillis)
        assertNull("no points were ever read: unknown, never zero", back.points)
        assertNull(back.recent)
    }

    @Test
    fun `a row replaces its own game and leaves every other row, the points and the recent unlocks as they were`() {
        val ledger = ledger()
        val unlock = RetroAchievementsRecentUnlock(1, "U", "d", 5, "00001", 1, "Game 1", "N64", 1_700_000_000_000L, hardcore = false)
        ledger.saveBulk("player", listOf(game(1, earned = 2), game(2), game(3)), 900, listOf(unlock), nowMillis = 10L)

        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.upsertBulkGames("player", listOf(game(2, earned = 9), game(4)), nowMillis = 20L))

        val back = ledger.readBulk("player")!!
        assertEquals(listOf(1, 2, 3, 4), back.games.map { it.gameId })
        assertEquals("the replaced row keeps its place, the new one is added last", listOf(2, 9, 3, 3), back.games.map { it.earned })
        assertEquals(900, back.points)
        assertEquals(listOf(1), back.recent!!.map { it.achievementId })
        assertEquals(20L, back.savedAtMillis)
    }

    @Test
    fun `the same row twice writes nothing the second time`() {
        val ledger = ledger()
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.upsertBulkGames("player", listOf(game(1), game(2)), nowMillis = 100L))
        val file = bulkFile("player")
        val bytes = file.readBytes()
        assertTrue(file.setLastModified(5_000L))

        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.upsertBulkGames("player", listOf(game(2)), nowMillis = 999L))

        assertTrue("byte-identical", bytes.contentEquals(file.readBytes()))
        assertEquals(5_000L, file.lastModified())
        assertEquals(100L, ledger.readBulk("player")!!.savedAtMillis)
    }

    @Test
    fun `a fresher row that names no icon or award date keeps the saved ones, unless nothing is earned any more`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1), game(2)), null, null, nowMillis = 10L)

        ledger.upsertBulkGames(
            "player",
            listOf(game(1, earned = 5, icon = null, awarded = null), game(2, earned = 0, icon = null, awarded = null)),
            nowMillis = 20L,
        )

        val back = ledger.readBulk("player")!!.games.associateBy { it.gameId }
        assertEquals(5, back.getValue(1).earned)
        assertEquals("/Images/000001.png", back.getValue(1).imageIconPath)
        assertEquals(1_700_000_000_001L, back.getValue(1).lastAwardedAtMillis)
        assertEquals("/Images/000002.png", back.getValue(2).imageIconPath)
        assertNull("a game with nothing earned has no award date", back.getValue(2).lastAwardedAtMillis)
    }

    @Test
    fun `the guard is asked inside the lock and a refusal leaves the file alone`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 5, null, nowMillis = 10L)
        val bytes = bulkFile("player").readBytes()

        assertEquals(RetroAchievementsLedgerWrite.REFUSED, ledger.upsertBulkGames("player", listOf(game(2)), nowMillis = 20L, mayWrite = { false }))

        assertTrue(bytes.contentEquals(bulkFile("player").readBytes()))
    }

    @Test
    fun `one account's rows never reach another's`() {
        val ledger = ledger()
        ledger.upsertBulkGames("alice", listOf(game(1)), nowMillis = 10L)

        assertNull(ledger.readBulk("bob"))
        assertFalse(bulkFile("bob").exists())
        ledger.upsertBulkGames("bob", listOf(game(2)), nowMillis = 11L)
        assertEquals(listOf(1), ledger.readBulk("alice")!!.games.map { it.gameId })
        assertEquals(listOf(2), ledger.readBulk("bob")!!.games.map { it.gameId })
    }

    @Test
    fun `a blank account, an empty batch and a row that cannot be kept write nothing`() {
        val ledger = ledger()
        assertEquals(RetroAchievementsLedgerWrite.FAILED, ledger.upsertBulkGames("  ", listOf(game(1)), nowMillis = 1L))
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.upsertBulkGames("player", emptyList(), nowMillis = 1L))
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.upsertBulkGames("player", listOf(game(0), game(-3), game(4, earned = -1)), nowMillis = 1L))
        assertFalse(bulkFile("player").exists())
    }

    @Test
    fun `a list at the ledger's bound refuses a new game whole and keeps the saved list`() {
        val ledger = ledger()
        val full = (1..RetroAchievementsLedger.MAX_BULK_GAMES).map { game(it, icon = null, awarded = null) }
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulk("player", full, null, null, nowMillis = 1L))

        assertEquals(RetroAchievementsLedgerWrite.FAILED, ledger.upsertBulkGames("player", listOf(game(RetroAchievementsLedger.MAX_BULK_GAMES + 1)), nowMillis = 2L))
        assertEquals("a game already saved still updates", RetroAchievementsLedgerWrite.WRITTEN, ledger.upsertBulkGames("player", listOf(game(7, earned = 8, icon = null, awarded = null)), nowMillis = 3L))

        val back = ledger.readBulk("player")!!
        assertEquals(RetroAchievementsLedger.MAX_BULK_GAMES, back.games.size)
        assertEquals(8, back.games.first { it.gameId == 7 }.earned)
    }

    @Test
    fun `text is cut to the ledger's caps like any other row`() {
        val ledger = ledger()
        ledger.upsertBulkGames("player", listOf(game(1).copy(title = "T".repeat(5_000), consoleName = "C".repeat(500))), nowMillis = 1L)
        val row = ledger.readBulk("player")!!.games.single()
        assertEquals(RetroAchievementsLedger.MAX_TITLE_CHARS, row.title.length)
        assertEquals(RetroAchievementsLedger.MAX_CONSOLE_CHARS, row.consoleName.length)
    }

    // toUserGame -------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a per-game read becomes a row with its counts, console, icon and newest unlock date`() {
        val row = progress(gameId = 42, unlocked = 3).toUserGame()!!

        assertEquals(
            "the one hardcore unlock sits inside the 3 earned ones",
            RetroAchievementsUserGame(42, "Game 42", "Nintendo 64", 3, 4, "/Images/000044.png", Instant.parse("2026-01-03T10:00:00Z").toEpochMilli(), earnedHardcore = 1),
            row,
        )
        assertTrue(row.isShowable)
    }

    @Test
    fun `a game with nothing earned has no award date, and one without a console reads as blank`() {
        val row = progress(gameId = 9, unlocked = 0, console = null).toUserGame()!!

        assertEquals(0, row.earned)
        assertEquals(4, row.total)
        assertEquals("", row.consoleName)
        assertNull(row.lastAwardedAtMillis)
    }

    @Test
    fun `a read with no id or no achievements makes no row`() {
        assertNull(progress(gameId = 0, unlocked = 1).toUserGame())
        assertNull(progress(gameId = 5, unlocked = 0, count = 0).toUserGame())
    }
}
