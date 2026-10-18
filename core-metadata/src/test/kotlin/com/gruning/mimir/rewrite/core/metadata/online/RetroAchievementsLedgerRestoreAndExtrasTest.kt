package com.gruning.mimir.rewrite.core.metadata.online

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The ledger's per-game-only file ([RetroAchievementsLedgerBulk.laneOnly]) and the writes around it: a backup restore MERGES into a file that only per-game reads
 * made (it used to be refused as "already there", silently dropping the backup), a complete read keeps the games per-game reads saw with nothing earned (decided
 * inside the lock), and a read whose empty games list was not believed changes only the points and recent unlocks. Plain JVM, real files.
 */
class RetroAchievementsLedgerRestoreAndExtrasTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root get() = File(temp.root, "ra_ledger")
    private fun ledger() = RetroAchievementsLedger(root)
    private fun bulkFile(account: String) = File(File(root, RetroAchievementsLedger.accountKey(account)!!), "bulk.json")

    private fun game(id: Int, earned: Int = 3, total: Int = 10, icon: String? = null, awarded: Long? = null) =
        RetroAchievementsUserGame(id, "Game $id", "Nintendo 64", earned, total, icon, awarded)

    private fun unlock(id: Int) =
        RetroAchievementsRecentUnlock(id, "Unlock $id", "desc", 5, "%05d".format(id), 1, "Game 1", "N64", 1_700_000_000_000L, hardcore = false)

    // Restore ---------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a restore merges into a file only per-game reads made, the per-game rows win and the backup's points are kept`() {
        val ledger = ledger()
        ledger.upsertBulkGames("player", listOf(game(1, earned = 0), game(2, earned = 4)), nowMillis = 10L)
        assertTrue(ledger.readBulk("player")!!.laneOnly)

        val backup = listOf(game(2, earned = 9), game(3, earned = 5), game(4, earned = 1))
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.insertBulkIfAbsent("player", backup, 700, nowMillis = 20L))

        val back = ledger.readBulk("player")!!
        assertEquals("the device's rows first, the backup's added after", listOf(1, 2, 3, 4), back.games.map { it.gameId })
        assertEquals("the device read game 2 itself: its row wins", 4, back.games.first { it.gameId == 2 }.earned)
        assertEquals(5, back.games.first { it.gameId == 3 }.earned)
        assertEquals(700, back.points)
        assertNull(back.recent)
        assertTrue("still a per-game file: a later complete read takes over", back.laneOnly)
    }

    @Test
    fun `the same backup twice changes nothing the second time`() {
        val ledger = ledger()
        ledger.upsertBulkGames("player", listOf(game(1, earned = 0)), nowMillis = 10L)
        val backup = listOf(game(3, earned = 5))
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.insertBulkIfAbsent("player", backup, 700, nowMillis = 20L))
        val file = bulkFile("player")
        val bytes = file.readBytes()

        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.insertBulkIfAbsent("player", backup, 700, nowMillis = 99L))

        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun `points already saved in a per-game file are not replaced by a backup's`() {
        val ledger = ledger()
        ledger.upsertBulkGames("player", listOf(game(1)), nowMillis = 10L)
        ledger.saveBulkExtras("player", points = 55, recent = null, nowMillis = 11L)

        ledger.insertBulkIfAbsent("player", listOf(game(3)), 700, nowMillis = 20L)

        assertEquals(55, ledger.readBulk("player")!!.points)
        assertEquals(listOf(1, 3), ledger.readBulk("player")!!.games.map { it.gameId })
    }

    @Test
    fun `a complete read is never merged into, even when it holds exactly what the per-game file held`() {
        val ledger = ledger()
        ledger.upsertBulkGames("player", listOf(game(1)), nowMillis = 10L)

        // The complete read says the same thing: it is still a complete read, so the file stops being per-game-only.
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulk("player", listOf(game(1)), null, null, nowMillis = 11L))
        assertFalse(ledger.readBulk("player")!!.laneOnly)

        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.insertBulkIfAbsent("player", listOf(game(1), game(2)), 700, nowMillis = 20L))
        assertEquals(listOf(1), ledger.readBulk("player")!!.games.map { it.gameId })
    }

    @Test
    fun `a restore onto nothing is saved as a read, and per-game rows then join it without making it per-game-only`() {
        val ledger = ledger()
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.insertBulkIfAbsent("player", listOf(game(5)), 300, nowMillis = 1L))
        assertFalse(ledger.readBulk("player")!!.laneOnly)

        ledger.upsertBulkGames("player", listOf(game(6, earned = 0)), nowMillis = 2L)

        assertFalse(ledger.readBulk("player")!!.laneOnly)
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.insertBulkIfAbsent("player", listOf(game(7)), 1, nowMillis = 3L))
        assertEquals(listOf(5, 6), ledger.readBulk("player")!!.games.map { it.gameId })
    }

    @Test
    fun `a file written before the flag existed counts as a complete read, and a complete read's file carries no flag`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 5, null, nowMillis = 1L)
        val text = bulkFile("player").readText()
        assertFalse("byte-for-byte what a complete read always wrote", text.contains("\"lane\""))
        assertFalse(ledger.readBulk("player")!!.laneOnly)
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.insertBulkIfAbsent("player", listOf(game(2)), 9, nowMillis = 2L))

        ledger.upsertBulkGames("other", listOf(game(1)), nowMillis = 1L)
        assertTrue(bulkFile("other").readText().contains("\"lane\":true"))
    }

    @Test
    fun `a merge past the ledger's bound is refused whole and leaves the file`() {
        val ledger = ledger()
        ledger.upsertBulkGames("player", listOf(game(1)), nowMillis = 1L)
        val bytes = bulkFile("player").readBytes()
        val tooMany = (2..RetroAchievementsLedger.MAX_BULK_GAMES + 1).map { game(it) }

        assertEquals(RetroAchievementsLedgerWrite.FAILED, ledger.insertBulkIfAbsent("player", tooMany, 1, nowMillis = 2L))

        assertTrue(bytes.contentEquals(bulkFile("player").readBytes()))
    }

    // Keeping what per-game reads saw -----------------------------------------------------------------------------------------------------

    @Test
    fun `a complete read keeps the saved games with nothing earned that it does not name, and drops the ones with earned counts`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1), game(2, earned = 6)), 10, null, nowMillis = 1L)
        ledger.upsertBulkGames("player", listOf(game(77, earned = 0), game(78, earned = 0)), nowMillis = 2L)

        assertEquals(
            RetroAchievementsLedgerWrite.WRITTEN,
            ledger.saveBulk("player", listOf(game(1, earned = 4), game(78, earned = 2)), 20, null, nowMillis = 3L, keepObserved = true),
        )

        val back = ledger.readBulk("player")!!
        assertEquals("1 and 78 as the read says; 77 kept; 2 had earned counts the read no longer lists", listOf(1, 78, 77), back.games.map { it.gameId })
        assertEquals(listOf(4, 2, 0), back.games.map { it.earned })
        assertEquals(20, back.points)
    }

    @Test
    fun `without the keep flag a complete read replaces the list whole, as it always did`() {
        val ledger = ledger()
        ledger.upsertBulkGames("player", listOf(game(77, earned = 0)), nowMillis = 1L)

        ledger.saveBulk("player", listOf(game(1)), null, null, nowMillis = 2L)

        assertEquals(listOf(1), ledger.readBulk("player")!!.games.map { it.gameId })
    }

    @Test
    fun `what is kept is decided from the file as it is when the save runs`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), null, null, nowMillis = 1L)
        // A per-game write lands after the caller began its read and before its save (the caller holds no copy of the file).
        ledger.upsertBulkGames("player", listOf(game(77, earned = 0)), nowMillis = 2L)

        ledger.saveBulk("player", listOf(game(1, earned = 8)), null, null, nowMillis = 3L, keepObserved = true)

        assertEquals(listOf(1, 77), ledger.readBulk("player")!!.games.map { it.gameId })
    }

    @Test
    fun `a keep flag on an account with no saved file saves just the read, and a refused save changes nothing`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), null, null, nowMillis = 1L, keepObserved = true)
        assertEquals(listOf(1), ledger.readBulk("player")!!.games.map { it.gameId })

        ledger.upsertBulkGames("player", listOf(game(77, earned = 0)), nowMillis = 2L)
        val bytes = bulkFile("player").readBytes()
        assertEquals(RetroAchievementsLedgerWrite.REFUSED, ledger.saveBulk("player", listOf(game(2)), null, null, nowMillis = 3L, mayWrite = { false }, keepObserved = true))
        assertTrue(bytes.contentEquals(bulkFile("player").readBytes()))
    }

    // Points and recent unlocks alone -----------------------------------------------------------------------------------------------------

    @Test
    fun `an extras save changes only the points and the recent unlocks`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1), game(2, earned = 0)), 100, listOf(unlock(1)), nowMillis = 1L)

        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulkExtras("player", points = 250, recent = listOf(unlock(7), unlock(8)), nowMillis = 2L))

        val back = ledger.readBulk("player")!!
        assertEquals(listOf(game(1), game(2, earned = 0)), back.games)
        assertEquals(250, back.points)
        assertEquals(listOf(7, 8), back.recent!!.map { it.achievementId })
        assertFalse("a complete file stays complete", back.laneOnly)
    }

    @Test
    fun `an extras save leaves a field alone when it is null, and the same answer twice writes nothing`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 100, listOf(unlock(1)), nowMillis = 1L)

        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulkExtras("player", points = null, recent = listOf(unlock(9)), nowMillis = 2L))
        assertEquals(100, ledger.readBulk("player")!!.points)
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulkExtras("player", points = 300, recent = null, nowMillis = 3L))
        assertEquals(listOf(9), ledger.readBulk("player")!!.recent!!.map { it.achievementId })
        val file = bulkFile("player")
        val bytes = file.readBytes()

        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.saveBulkExtras("player", points = 300, recent = listOf(unlock(9)), nowMillis = 99L))
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.saveBulkExtras("player", points = null, recent = null, nowMillis = 99L))
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun `an extras save on an account with nothing saved makes a per-game file with no games, and a blank or refused one writes nothing`() {
        val ledger = ledger()
        assertEquals(RetroAchievementsLedgerWrite.REFUSED, ledger.saveBulkExtras("player", 5, null, nowMillis = 1L, mayWrite = { false }))
        assertFalse(bulkFile("player").exists())
        assertEquals(RetroAchievementsLedgerWrite.FAILED, ledger.saveBulkExtras("  ", 5, null, nowMillis = 1L))

        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulkExtras("player", 640, null, nowMillis = 2L))

        val back = ledger.readBulk("player")!!
        assertEquals(emptyList<RetroAchievementsUserGame>(), back.games)
        assertEquals(640, back.points)
        assertTrue(back.laneOnly)
        // A backup can still be merged into it.
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.insertBulkIfAbsent("player", listOf(game(3)), 9, nowMillis = 3L))
        assertEquals(640, ledger.readBulk("player")!!.points)
        assertEquals(listOf(3), ledger.readBulk("player")!!.games.map { it.gameId })
    }
}
