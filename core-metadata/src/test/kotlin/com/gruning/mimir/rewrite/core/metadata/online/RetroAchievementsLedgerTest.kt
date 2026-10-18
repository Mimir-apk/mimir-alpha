package com.gruning.mimir.rewrite.core.metadata.online

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The durable ledger on a real folder: what is kept per account, that the same answer twice changes nothing, that a damaged file falls back to the previous
 * generation or to nothing (never to a confident empty answer), and that no account ever reads another's rows. Plain JVM: no Android, no network.
 */
class RetroAchievementsLedgerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root get() = File(temp.root, "ra_ledger")
    private fun ledger(maxDetail: Int = 256) = RetroAchievementsLedger(root, maxDetail)

    private fun game(id: Int, earned: Int = 3, total: Int = 10) =
        RetroAchievementsUserGame(id, "Game $id", "Super Nintendo Entertainment System", earned, total, "/Images/%06d.png".format(id), 1_700_000_000_000L + id)

    private fun unlock(id: Int) =
        RetroAchievementsRecentUnlock(id, "Unlock $id", "desc $id", 5, "%05d".format(id), 1, "Game 1", "SNES", 1_700_000_000_000L, hardcore = id % 2 == 0)

    private fun progress(gameId: Int, earned: Int = 2, count: Int = 4) = RetroAchievementsProgress(
        gameId = gameId,
        title = "Game $gameId",
        achievements = (1..count).map {
            RetroAchievementsAchievement(
                id = gameId * 100 + it,
                title = "A$it",
                description = "d$it",
                points = it * 5,
                unlocked = it <= earned,
                unlockedHardcore = it == 1,
                badgeName = "%05d".format(it),
                earnedAt = if (it <= earned) "2026-01-0$it 10:00:00" else null,
                earnedHardcoreAt = if (it == 1) "2026-01-01 10:00:00" else null,
            )
        },
        imageTitlePath = "/Images/%06d.png".format(gameId),
        imageIngamePath = "/Images/%06d.png".format(gameId + 1),
        imageIconPath = "/Images/%06d.png".format(gameId + 2),
        consoleName = "Nintendo 64",
    )

    private fun bulkFile(account: String) = File(File(root, RetroAchievementsLedger.accountKey(account)!!), "bulk.json")

    // The key -------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the account key is stable, case-insensitive, one-way and absent for a blank name`() {
        val key = RetroAchievementsLedger.accountKey("Player One")!!
        assertEquals(32, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertEquals(key, RetroAchievementsLedger.accountKey("  player one "))
        assertNotEquals(key, RetroAchievementsLedger.accountKey("player two"))
        assertFalse(key.contains("player", ignoreCase = true))
        assertNull(RetroAchievementsLedger.accountKey("   "))
        assertNull(RetroAchievementsLedger.accountKey(null))
    }

    @Test
    fun `no file name or file content carries the username`() {
        val ledger = ledger()
        ledger.saveBulk("secretname", listOf(game(1)), 100, listOf(unlock(1)), nowMillis = 5L)
        ledger.saveResolution("secretname", RetroAchievementsLedgerResolution(localByRaGameId = mapOf(1 to "local-1")))
        ledger.saveDetail("secretname", progress(1), nowMillis = 5L)
        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.size >= 3)
        for (file in files) {
            assertFalse(file.path, file.path.contains("secretname", ignoreCase = true))
            assertFalse(file.path, file.readText().contains("secretname", ignoreCase = true))
        }
    }

    // Bulk ----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a saved bulk read comes back whole`() {
        val ledger = ledger()
        assertNull(ledger.readBulk("player"))
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulk("player", listOf(game(1), game(2, earned = 0)), 1234, listOf(unlock(1), unlock(2)), nowMillis = 77L))

        // A new instance over the same folder is a restart: nothing was held in memory.
        val back = RetroAchievementsLedger(root).readBulk("player")!!
        assertEquals(77L, back.savedAtMillis)
        assertEquals(1234, back.points)
        assertEquals(listOf(game(1), game(2, earned = 0)), back.games)
        assertEquals(listOf(unlock(1), unlock(2)), back.recent)
        assertEquals(2, ledger.bulkGameCount("player"))
    }

    @Test
    fun `the same bulk answer twice writes nothing the second time`() {
        val ledger = ledger()
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulk("player", listOf(game(1)), 10, listOf(unlock(1)), nowMillis = 100L))
        val file = bulkFile("player")
        val bytes = file.readBytes()
        file.setLastModified(1_000L)

        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.saveBulk("player", listOf(game(1)), 10, listOf(unlock(1)), nowMillis = 999L))
        assertTrue("the file is byte-identical", bytes.contentEquals(file.readBytes()))
        assertEquals("the file was not touched", 1_000L, file.lastModified())
        assertEquals("the time of the data stays", 100L, ledger.readBulk("player")!!.savedAtMillis)
        assertFalse("no previous generation for an unchanged answer", File(file.parentFile, "bulk.json.bak").exists())
    }

    @Test
    fun `a changed answer replaces the games and keeps one previous generation`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1, earned = 1)), 10, null, nowMillis = 100L)
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulk("player", listOf(game(1, earned = 2), game(2)), 10, null, nowMillis = 200L))
        val back = ledger.readBulk("player")!!
        assertEquals(200L, back.savedAtMillis)
        assertEquals(listOf(game(1, earned = 2), game(2)), back.games)
        assertTrue(File(bulkFile("player").parentFile, "bulk.json.bak").isFile)
    }

    @Test
    fun `points and recent unlocks that a read did not produce are kept, not erased`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 500, listOf(unlock(1)), nowMillis = 100L)
        // The games changed; the profile read and the recent read both failed this time.
        ledger.saveBulk("player", listOf(game(1, earned = 9)), points = null, recent = null, nowMillis = 200L)
        val back = ledger.readBulk("player")!!
        assertEquals(500, back.points)
        assertEquals(listOf(unlock(1)), back.recent)
        assertEquals(9, back.games.single().earned)
        // A real, empty recent window IS an answer.
        ledger.saveBulk("player", listOf(game(1, earned = 9)), points = null, recent = emptyList(), nowMillis = 300L)
        assertEquals(emptyList<RetroAchievementsRecentUnlock>(), ledger.readBulk("player")!!.recent)
    }

    @Test
    fun `a bulk read that never produced points leaves them unknown, not zero`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), points = null, recent = null, nowMillis = 1L)
        val back = ledger.readBulk("player")!!
        assertNull(back.points)
        assertNull(back.recent)
    }

    @Test
    fun `a damaged current file falls back to the previous generation`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1, earned = 1)), 10, null, nowMillis = 100L)
        ledger.saveBulk("player", listOf(game(1, earned = 2)), 10, null, nowMillis = 200L)
        bulkFile("player").writeText("{\"v\":1,\"savedAt\":2,\"games\":[{\"id\":1,")

        val back = ledger.readBulk("player")!!
        assertEquals("the previous generation", 1, back.games.single().earned)
        // The next save repairs the current file and does not clobber the good backup with the damaged one.
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulk("player", listOf(game(1, earned = 3)), 10, null, nowMillis = 300L))
        assertEquals(3, ledger.readBulk("player")!!.games.single().earned)
        // The good backup survived the repair: with the repaired file gone it is what is read.
        assertTrue(bulkFile("player").delete())
        assertEquals(1, RetroAchievementsLedger(root).readBulk("player")!!.games.single().earned)
    }

    @Test
    fun `a missing current file is restored from the backup with the time of the data it restores`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1, earned = 1)), 10, null, nowMillis = 100L)
        ledger.saveBulk("player", listOf(game(1, earned = 2)), 10, null, nowMillis = 200L)
        assertTrue(bulkFile("player").delete())
        // Reading finds the backup; saving the answer the backup holds repairs the file.
        assertEquals(100L, ledger.readBulk("player")!!.savedAtMillis)
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulk("player", listOf(game(1, earned = 1)), 10, null, nowMillis = 999L))
        assertTrue(bulkFile("player").isFile)
        assertEquals(100L, ledger.readBulk("player")!!.savedAtMillis)
    }

    @Test
    fun `a damaged file with no backup reads as nothing, never as an empty answer`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 10, null, nowMillis = 100L)
        bulkFile("player").writeText("not json at all")
        assertNull(ledger.readBulk("player"))
        assertEquals(0, ledger.bulkGameCount("player"))
        bulkFile("player").writeText("")
        assertNull(ledger.readBulk("player"))
    }

    @Test
    fun `an install that never had a ledger opens clean and every read creates nothing`() {
        // The state of every install that is updated over the top (adb install -r): no folder, no database, nothing to migrate.
        val ledger = ledger()
        assertNull(ledger.readBulk("player"))
        assertEquals(0, ledger.bulkGameCount("player"))
        assertTrue(ledger.readResolution("player").isEmpty)
        assertNull(ledger.readDetail("player", 7))
        assertTrue(ledger.detailGameIds("player").isEmpty())
        assertFalse(ledger.hasAccount("player"))
        assertFalse("reading made no folder", root.exists())
    }

    @Test
    fun `a file with fields this build does not know still reads, so a later build can add some`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 10, null, nowMillis = 100L)
        val file = bulkFile("player")
        file.writeText(file.readText().replaceFirst("{", "{\"someFutureField\":{\"a\":1},"))
        assertEquals(listOf(game(1)), ledger.readBulk("player")!!.games)
    }

    @Test
    fun `a file from a newer format is not read`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 10, null, nowMillis = 100L)
        val file = bulkFile("player")
        file.writeText(file.readText().replace("\"v\":1", "\"v\":2"))
        assertNull(ledger.readBulk("player"))
    }

    @Test
    fun `a tampered image path or badge name is dropped on read`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 10, listOf(unlock(1)), nowMillis = 100L)
        val file = bulkFile("player")
        file.writeText(
            file.readText()
                .replace("/Images/000001.png", "../../etc/passwd")
                .replace("\"00001\"", "\"not-digits\""),
        )
        val back = ledger.readBulk("player")!!
        assertNull(back.games.single().imageIconPath)
        assertNull(back.recent!!.single().badgeName)
    }

    @Test
    fun `no temp file is left behind by a write`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 1, null, nowMillis = 1L)
        ledger.saveBulk("player", listOf(game(2)), 1, null, nowMillis = 2L)
        ledger.saveResolution("player", RetroAchievementsLedgerResolution(localByRaGameId = mapOf(1 to "g")))
        ledger.saveDetail("player", progress(1), nowMillis = 3L)
        assertEquals(emptyList<String>(), root.walkTopDown().filter { it.name.endsWith(".tmp") }.map { it.name }.toList())
    }

    // Accounts ------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an account never reads another account's rows and gets its own back after a switch`() {
        val ledger = ledger()
        ledger.saveBulk("alice", listOf(game(1)), 111, null, nowMillis = 1L)
        ledger.saveDetail("alice", progress(1), nowMillis = 1L)
        ledger.saveResolution("alice", RetroAchievementsLedgerResolution(localByRaGameId = mapOf(1 to "a-game")))

        assertNull(ledger.readBulk("bob"))
        assertNull(ledger.readDetail("bob", 1))
        assertTrue(ledger.readResolution("bob").isEmpty)
        assertFalse(ledger.hasAccount("bob"))

        ledger.saveBulk("bob", listOf(game(2)), 222, null, nowMillis = 2L)
        assertEquals(listOf(game(2)), ledger.readBulk("bob")!!.games)
        // Back to A: untouched by B's write.
        assertEquals(listOf(game(1)), ledger.readBulk("alice")!!.games)
        assertEquals(111, ledger.readBulk("alice")!!.points)
        assertEquals("a-game", ledger.readResolution("alice").localByRaGameId[1])
        assertNotNull(ledger.readDetail("alice", 1))
        // The same person with another capitalisation is the same account.
        assertEquals(listOf(game(1)), ledger.readBulk("ALICE")!!.games)
    }

    @Test
    fun `forgetting one account leaves the others`() {
        val ledger = ledger()
        ledger.saveBulk("alice", listOf(game(1)), 1, null, nowMillis = 1L)
        ledger.saveBulk("bob", listOf(game(2)), 2, null, nowMillis = 1L)
        assertTrue(ledger.forget("alice"))
        assertNull(ledger.readBulk("alice"))
        assertNotNull(ledger.readBulk("bob"))
        assertTrue("forgetting nothing is fine", ledger.forget("carol"))
    }

    @Test
    fun `a blank account reads and writes nothing`() {
        val ledger = ledger()
        assertEquals(RetroAchievementsLedgerWrite.FAILED, ledger.saveBulk("  ", listOf(game(1)), 1, null, nowMillis = 1L))
        assertNull(ledger.readBulk(""))
        assertFalse(root.exists())
    }

    // Resolution ----------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a resolution round-trips and the same one twice writes nothing`() {
        val ledger = ledger()
        val resolution = RetroAchievementsLedgerResolution(
            verdicts = listOf(
                RetroAchievementsLedgerVerdict("uri|10|5", RetroAchievementsLedgerVerdictKind.MATCHED, 77, 100L),
                RetroAchievementsLedgerVerdict("uri2|10", RetroAchievementsLedgerVerdictKind.NOT_MATCHED, null, 200L),
                RetroAchievementsLedgerVerdict("uri3|10", RetroAchievementsLedgerVerdictKind.RECOGNIZED_NO_SET, null, 300L),
            ),
            localByRaGameId = mapOf(77 to "local-77"),
        )
        assertTrue(ledger.readResolution("player").isEmpty)
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveResolution("player", resolution))
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.saveResolution("player", resolution))
        assertEquals(resolution, RetroAchievementsLedger(root).readResolution("player"))
    }

    @Test
    fun `a match without its game id is not read back as a match`() {
        val ledger = ledger()
        ledger.saveResolution("player", RetroAchievementsLedgerResolution(verdicts = listOf(RetroAchievementsLedgerVerdict("k|1", RetroAchievementsLedgerVerdictKind.MATCHED, 5, 1L))))
        val file = File(File(root, RetroAchievementsLedger.accountKey("player")!!), "resolution.json")
        file.writeText(file.readText().replace("\"ra\":5", "\"ra\":0"))
        assertTrue(ledger.readResolution("player").verdicts.isEmpty())
    }

    @Test
    fun `a resolution keeps only its newest verdicts past the bound`() {
        val ledger = ledger()
        val many = (1..RetroAchievementsLedger.MAX_VERDICTS + 10).map {
            RetroAchievementsLedgerVerdict("k$it|1", RetroAchievementsLedgerVerdictKind.NOT_MATCHED, null, it.toLong())
        }
        ledger.saveResolution("player", RetroAchievementsLedgerResolution(verdicts = many))
        val back = ledger.readResolution("player").verdicts
        assertEquals(RetroAchievementsLedger.MAX_VERDICTS, back.size)
        assertEquals("k11|1", back.first().fileKey)
        assertEquals("k${RetroAchievementsLedger.MAX_VERDICTS + 10}|1", back.last().fileKey)
    }

    // Detail --------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a game's detail round-trips with its badges, flags, dates and console`() {
        val ledger = ledger()
        val one = progress(7, earned = 3, count = 5)
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveDetail("player", one, nowMillis = 42L))
        val back = RetroAchievementsLedger(root).readDetail("player", 7)!!
        assertEquals(one, back.progress)
        assertEquals(42L, back.savedAtMillis)
        assertEquals("Nintendo 64", back.progress.consoleName)
        assertEquals(3, back.progress.unlockedCount)
        assertEquals(setOf(7), ledger.detailGameIds("player"))
    }

    @Test
    fun `the same detail twice writes nothing and a changed one replaces it`() {
        val ledger = ledger()
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveDetail("player", progress(7, earned = 1), nowMillis = 10L))
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.saveDetail("player", progress(7, earned = 1), nowMillis = 99L))
        assertEquals(10L, ledger.readDetail("player", 7)!!.savedAtMillis)
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveDetail("player", progress(7, earned = 2), nowMillis = 99L))
        assertEquals(2, ledger.readDetail("player", 7)!!.progress.unlockedCount)
    }

    @Test
    fun `an answer with no achievements never replaces a real one`() {
        val ledger = ledger()
        ledger.saveDetail("player", progress(7), nowMillis = 1L)
        assertEquals(RetroAchievementsLedgerWrite.FAILED, ledger.saveDetail("player", progress(7).copy(achievements = emptyList()), nowMillis = 2L))
        assertEquals(4, ledger.readDetail("player", 7)!!.progress.totalCount)
    }

    @Test
    fun `detail past the per-account bound drops the least recently used game`() {
        val ledger = ledger(maxDetail = 3)
        (1..3).forEach { ledger.saveDetail("player", progress(it), nowMillis = it.toLong()) }
        // Give each file a distinct age, then read game 1 so it is the youngest.
        (1..3).forEach { File(File(File(root, RetroAchievementsLedger.accountKey("player")!!), "detail"), "g$it.json").setLastModified(1_000L * it) }
        ledger.readDetail("player", 1)
        ledger.saveDetail("player", progress(4), nowMillis = 4L)
        assertEquals(setOf(1, 3, 4), ledger.detailGameIds("player"))
    }

    // The guard, insert-if-absent and the bounds -----------------------------------------------------------------------------------

    @Test
    fun `a write the guard turns away changes nothing and says refused`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 10, null, nowMillis = 1L)
        val file = bulkFile("player")
        val before = file.readBytes()

        assertEquals(RetroAchievementsLedgerWrite.REFUSED, ledger.saveBulk("player", listOf(game(1), game(2)), 11, null, nowMillis = 2L, mayWrite = { false }))
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(listOf(game(1)), ledger.readBulk("player")!!.games)

        // With nothing saved yet, a refused write creates nothing at all, not even the folder.
        assertEquals(RetroAchievementsLedgerWrite.REFUSED, ledger.saveBulk("other", listOf(game(1)), 1, null, nowMillis = 3L, mayWrite = { false }))
        assertFalse(ledger.hasAccount("other"))
        // An answer equal to what is held is unchanged without ever asking the guard.
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.saveBulk("player", listOf(game(1)), 10, null, nowMillis = 9L, mayWrite = { error("not asked") }))
    }

    @Test
    fun `the guard is asked inside the lock, so a second writer waits behind it`() {
        val ledger = ledger()
        val asked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondDone = AtomicBoolean(false)
        val first = thread {
            ledger.saveBulk("player", listOf(game(1)), 1, null, nowMillis = 1L, mayWrite = { asked.countDown(); release.await(10, TimeUnit.SECONDS); true })
        }
        assertTrue(asked.await(10, TimeUnit.SECONDS))
        val second = thread {
            ledger.saveBulk("player", listOf(game(2)), 2, null, nowMillis = 2L)
            secondDone.set(true)
        }
        Thread.sleep(250)
        assertFalse("the second writer is still waiting for the lock the guard runs under", secondDone.get())
        release.countDown()
        first.join(10_000)
        second.join(10_000)
        assertTrue(secondDone.get())
        assertEquals("the later writer's answer is the one on disk", listOf(game(2)), ledger.readBulk("player")!!.games)
    }

    @Test
    fun `an insert-if-absent keeps the first answer, leaves a saved one untouched, and a live save still replaces it`() {
        val ledger = ledger()
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.insertBulkIfAbsent("player", listOf(game(1)), 5, nowMillis = 1L))
        val file = bulkFile("player")
        val before = file.readBytes()

        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.insertBulkIfAbsent("player", listOf(game(1), game(2)), 99, nowMillis = 2L))
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(5, ledger.readBulk("player")!!.points)

        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulk("player", listOf(game(1, earned = 8)), 50, null, nowMillis = 3L))
        assertEquals(50, ledger.readBulk("player")!!.points)

        // A damaged current file with a readable backup still counts as present: the backup is what a read serves.
        file.writeText("not json")
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.insertBulkIfAbsent("player", listOf(game(7)), 1, nowMillis = 4L))
    }

    @Test
    fun `a live save racing an insert-if-absent always leaves the live answer`() {
        repeat(40) { round ->
            val ledger = RetroAchievementsLedger(File(temp.root, "race$round"))
            val live = listOf(game(1, earned = 9))
            val start = CountDownLatch(1)
            val saver = thread { start.await(); ledger.saveBulk("player", live, 900, null, nowMillis = 2L) }
            val inserter = thread { start.await(); ledger.insertBulkIfAbsent("player", listOf(game(1, earned = 1)), 100, nowMillis = 1L) }
            start.countDown()
            saver.join(10_000)
            inserter.join(10_000)
            assertEquals("round $round", live, ledger.readBulk("player")!!.games)
            assertEquals(900, ledger.readBulk("player")!!.points)
        }
    }

    @Test
    fun `more games than the bound are refused and the old file is untouched`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 10, null, nowMillis = 1L)
        val file = bulkFile("player")
        val before = file.readBytes()

        val tooMany = (1..RetroAchievementsLedger.MAX_BULK_GAMES + 1).map { game(it) }
        assertEquals(RetroAchievementsLedgerWrite.FAILED, ledger.saveBulk("player", tooMany, 10, null, nowMillis = 2L))
        assertEquals(RetroAchievementsLedgerWrite.FAILED, ledger.insertBulkIfAbsent("fresh", tooMany, 10, nowMillis = 2L))

        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(listOf(game(1)), ledger.readBulk("player")!!.games)
        assertFalse(ledger.hasAccount("fresh"))
        assertEquals(emptyList<String>(), root.walkTopDown().filter { it.name.endsWith(".tmp") }.map { it.name }.toList())
    }

    @Test
    fun `an answer that would encode past what a read accepts is not written, and the old file stays readable`() {
        val ledger = ledger()
        ledger.saveBulk("player", listOf(game(1)), 10, null, nowMillis = 1L)
        val file = bulkFile("player")
        val before = file.readBytes()

        // Within every count and text cap, but three bytes a character: about ten megabytes of JSON, past the eight a read accepts.
        val wide = "\u3042".repeat(RetroAchievementsLedger.MAX_TITLE_CHARS)
        val heavy = (1..RetroAchievementsLedger.MAX_BULK_GAMES).map {
            RetroAchievementsUserGame(it, wide, wide, 1, 2, "/Images/%06d.png".format(it), 1_700_000_000_000L)
        }
        assertEquals(RetroAchievementsLedgerWrite.FAILED, ledger.saveBulk("player", heavy, 10, null, nowMillis = 2L))

        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(listOf(game(1)), ledger.readBulk("player")!!.games)
        assertEquals(emptyList<String>(), root.walkTopDown().filter { it.name.endsWith(".tmp") }.map { it.name }.toList())
    }

    @Test
    fun `long text is cut to its caps, recent unlocks to their window, and the cut answer is idempotent`() {
        val ledger = ledger()
        val long = "x".repeat(5000)
        val longGame = RetroAchievementsUserGame(1, long, long, 1, 2)
        val unlocks = (1..RetroAchievementsLedger.MAX_RECENT + 50).map { unlock(it).copy(title = long, description = long, gameTitle = long, consoleName = long) }
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveBulk("player", listOf(longGame), 1, unlocks, nowMillis = 1L))

        val back = ledger.readBulk("player")!!
        assertEquals(RetroAchievementsLedger.MAX_TITLE_CHARS, back.games.single().title.length)
        assertEquals(RetroAchievementsLedger.MAX_CONSOLE_CHARS, back.games.single().consoleName.length)
        assertEquals(RetroAchievementsLedger.MAX_RECENT, back.recent!!.size)
        assertEquals(RetroAchievementsLedger.MAX_TITLE_CHARS, back.recent!!.first().title.length)
        assertEquals(RetroAchievementsLedger.MAX_DESCRIPTION_CHARS, back.recent!!.first().description.length)
        assertEquals(RetroAchievementsLedger.MAX_CONSOLE_CHARS, back.recent!!.first().consoleName.length)

        val file = bulkFile("player")
        val bytes = file.readBytes()
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.saveBulk("player", listOf(longGame), 1, unlocks, nowMillis = 99L))
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun `a cut never splits a surrogate pair`() {
        val ledger = ledger()
        // 255 letters then an emoji (two UTF-16 units) puts the cut in the middle of the pair.
        val title = "a".repeat(RetroAchievementsLedger.MAX_TITLE_CHARS - 1) + "\uD83D\uDE00" + "tail"
        ledger.saveBulk("player", listOf(RetroAchievementsUserGame(1, title, "SNES", 1, 2)), 1, null, nowMillis = 1L)
        val kept = ledger.readBulk("player")!!.games.single().title
        assertEquals(RetroAchievementsLedger.MAX_TITLE_CHARS - 1, kept.length)
        assertFalse(Character.isHighSurrogate(kept.last()))
    }

    @Test
    fun `a detail past the achievement bound is refused, long text is cut, and the cut detail is idempotent`() {
        val ledger = ledger()
        assertEquals(RetroAchievementsLedgerWrite.FAILED, ledger.saveDetail("player", progress(1, count = RetroAchievementsLedger.MAX_DETAIL_ACHIEVEMENTS + 1), nowMillis = 1L))
        assertNull(ledger.readDetail("player", 1))

        val long = "d".repeat(4000)
        val base = progress(2)
        val heavy = base.copy(title = long, achievements = base.achievements.map { it.copy(title = long, description = long) })
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveDetail("player", heavy, nowMillis = 2L))
        val back = ledger.readDetail("player", 2)!!.progress
        assertEquals(RetroAchievementsLedger.MAX_TITLE_CHARS, back.title.length)
        assertEquals(RetroAchievementsLedger.MAX_DESCRIPTION_CHARS, back.achievements.first().description.length)
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.saveDetail("player", heavy, nowMillis = 3L))
    }

    @Test
    fun `a file key too long to be a key is not kept in the resolution`() {
        val ledger = ledger()
        val kept = RetroAchievementsLedgerVerdict("k".repeat(40), RetroAchievementsLedgerVerdictKind.NOT_MATCHED, null, 1L)
        val tooLong = RetroAchievementsLedgerVerdict("k".repeat(RetroAchievementsLedger.MAX_KEY_CHARS + 1), RetroAchievementsLedgerVerdictKind.NOT_MATCHED, null, 2L)
        ledger.saveResolution("player", RetroAchievementsLedgerResolution(listOf(kept, tooLong), mapOf(1 to "g".repeat(RetroAchievementsLedger.MAX_KEY_CHARS + 1), 2 to "local")))
        val back = ledger.readResolution("player")
        assertEquals(listOf(kept.fileKey), back.verdicts.map { it.fileKey })
        assertEquals(mapOf(2 to "local"), back.localByRaGameId)
    }

    @Test
    fun `a detail file holding another game's answer is not served for this one`() {
        val ledger = ledger()
        ledger.saveDetail("player", progress(7), nowMillis = 1L)
        val dir = File(File(root, RetroAchievementsLedger.accountKey("player")!!), "detail")
        File(dir, "g7.json").copyTo(File(dir, "g8.json"))
        assertNull(ledger.readDetail("player", 8))
    }
}
