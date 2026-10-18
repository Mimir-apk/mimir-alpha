package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaAwardKind
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedger
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.model.migration.BUNDLE_KIND
import com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementAccount
import com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementGame
import com.gruning.mimir.rewrite.core.model.migration.MigrationBundleCodec
import com.gruning.mimir.rewrite.core.model.migration.MigrationBundleV1
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Update: the two scores ledger (hardcore points, softcore points, how many of a game's achievements were earned in hardcore) and RetroAchievements' award word
 * ("mastered", "beaten-softcore", ...) are in a backup and come back from a restore exactly as they were saved, and a backup written before they were carried still
 * restores with none of them claimed. Nothing here touches the network; the ledger is a temporary folder.
 */
class RetroAchievementsBackupFieldsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val games = listOf(
        RetroAchievementsUserGame(
            1, "Super Metroid", "Super Nintendo Entertainment System", earned = 40, total = 40, imageIconPath = "/Images/000001.png",
            lastAwardedAtMillis = 1_700_000_000_000L, earnedHardcore = 40, awardKind = RaAwardKind.MASTERED,
        ),
        RetroAchievementsUserGame(2, "Sonic the Hedgehog 2", "Mega Drive", earned = 30, total = 30, earnedHardcore = 12, awardKind = RaAwardKind.COMPLETED),
        RetroAchievementsUserGame(3, "An Obscure Game", "Game Boy", earned = 9, total = 10, earnedHardcore = 0, awardKind = RaAwardKind.BEATEN_SOFTCORE),
        RetroAchievementsUserGame(4, "Another Obscure Game", "Game Boy", earned = 5, total = 10, earnedHardcore = 5, awardKind = RaAwardKind.BEATEN_HARDCORE),
        RetroAchievementsUserGame(5, "A Game With Nothing Awarded", "Game Boy", earned = 1, total = 10, earnedHardcore = 1),
    )

    @Test
    fun `a backup and a restore bring back both scores, the hardcore counts and every award exactly`() {
        val source = RetroAchievementsLedger(File(temp.root, "source"))
        source.saveBulk("Alice", games, points = 1500, recent = null, nowMillis = 1L, softcorePoints = 300)

        // The file a backup writes, read back as another device would read it.
        val exported = achievementsForBackup(source, "Alice")
        val text = MigrationBundleCodec.encode(MigrationBundleV1(achievements = exported))
        val parsed = MigrationBundleCodec.parse(text).getOrThrow()

        val other = RetroAchievementsLedger(File(temp.root, "other"))
        assertEquals(5, restoreAchievementsFromBackup(other, parsed.achievements, nowMillis = 2L))
        val restored = other.readBulk("alice")!!
        assertEquals("the hardcore score", 1500, restored.points)
        assertEquals("the softcore score", 300, restored.softcorePoints)
        assertEquals("every game, with its hardcore count and its award", games, restored.games)
    }

    @Test
    fun `an award word this app does not know restores as no award, and a hardcore count past the earned one as the earned count`() {
        val account = MigrationAchievementAccount(
            username = "Alice",
            points = 10,
            games = listOf(MigrationAchievementGame(raGameId = 1, title = "Game", earned = 4, total = 10, earnedHardcore = 99, awardWord = "legendary")),
        )
        val other = RetroAchievementsLedger(File(temp.root, "other"))

        assertEquals(1, restoreAchievementsFromBackup(other, listOf(account), nowMillis = 2L))
        val game = other.readBulk("Alice")!!.games.single()
        assertNull("a word we do not know is no award, never a guess", game.awardKind)
        assertEquals("hardcore is inside earned", 4, game.earnedHardcore)
    }

    @Test
    fun `the backup's softcore score fills a device holding only per-game rows, and never replaces a score the device already holds`() {
        val account = MigrationAchievementAccount(
            username = "Alice",
            points = 1500,
            softcorePoints = 300,
            games = listOf(MigrationAchievementGame(raGameId = 1, title = "Game", earned = 4, total = 10)),
        )

        // A device that has read nothing but the Bottom's opened games: no scores yet, so the backup's are taken.
        val bare = RetroAchievementsLedger(File(temp.root, "bare"))
        bare.upsertBulkGames("Alice", listOf(RetroAchievementsUserGame(9, "Opened", "Game Boy", earned = 1, total = 5)), nowMillis = 1L)
        assertEquals(1, restoreAchievementsFromBackup(bare, listOf(account), nowMillis = 2L))
        assertEquals(300, bare.readBulk("Alice")!!.softcorePoints)
        assertEquals(1500, bare.readBulk("Alice")!!.points)

        // A device that already holds both scores keeps them (what it read itself is newer than a file).
        val holding = RetroAchievementsLedger(File(temp.root, "holding"))
        holding.saveBulkExtras("Alice", points = 50, recent = null, nowMillis = 1L, softcorePoints = 120)
        assertEquals(1, restoreAchievementsFromBackup(holding, listOf(account), nowMillis = 2L))
        assertEquals(120, holding.readBulk("Alice")!!.softcorePoints)
        assertEquals(50, holding.readBulk("Alice")!!.points)
    }

    @Test
    fun `a game the device already opened keeps its own counts and still takes the backup's award, and a second restore changes nothing`() {
        val account = MigrationAchievementAccount(
            username = "Alice",
            points = 1500,
            games = listOf(
                MigrationAchievementGame(raGameId = 1, title = "Super Metroid", earned = 40, total = 40, earnedHardcore = 40, awardWord = "mastered"),
                MigrationAchievementGame(raGameId = 2, title = "Sonic", earned = 9, total = 30, awardWord = "beaten-softcore"),
            ),
        )
        // The device has only per-game reads: game 1 read here with fewer earned and no award (a per-game read names none), game 3 not in the backup at all.
        val device = RetroAchievementsLedger(File(temp.root, "device"))
        device.upsertBulkGames(
            "Alice",
            listOf(
                RetroAchievementsUserGame(1, "Super Metroid", "SNES", earned = 38, total = 40, earnedHardcore = 30),
                RetroAchievementsUserGame(3, "Opened Here", "Game Boy", earned = 1, total = 5),
            ),
            nowMillis = 1L,
        )

        assertEquals(2, restoreAchievementsFromBackup(device, listOf(account), nowMillis = 2L))
        val games = device.readBulk("Alice")!!.games.associateBy { it.gameId }
        assertEquals("the device's own count stands", 38, games.getValue(1).earned)
        assertEquals("and its hardcore count", 30, games.getValue(1).earnedHardcore)
        assertEquals("but the award of the backup is not lost", RaAwardKind.MASTERED, games.getValue(1).awardKind)
        assertEquals("a game only the backup holds comes with its award", RaAwardKind.BEATEN_SOFTCORE, games.getValue(2).awardKind)
        assertNull("a game only the device holds is untouched", games.getValue(3).awardKind)

        // The same backup again: the device now holds every award, so nothing is written.
        val file = File(File(File(temp.root, "device"), RetroAchievementsLedger.accountKey("Alice")!!), "bulk.json")
        val bytes = file.readBytes()
        assertEquals(0, restoreAchievementsFromBackup(device, listOf(account), nowMillis = 3L))
        assertEquals(true, bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun `a backup written before the scores and awards were carried still restores, claiming none of them`() {
        val older = """
            {"bundleVersion":1,"kind":"$BUNDLE_KIND","achievements":[{"username":"Alice","points":1500,"games":[
            {"raGameId":1,"title":"Super Metroid","consoleName":"Super Nintendo Entertainment System","earned":12,"total":40,
             "lastAwardedAtMillis":1700000000000,"iconPath":"/Images/000001.png"}]}]}
        """.trimIndent()
        val parsed = MigrationBundleCodec.parse(older).getOrThrow()

        val other = RetroAchievementsLedger(File(temp.root, "other"))
        assertEquals(1, restoreAchievementsFromBackup(other, parsed.achievements, nowMillis = 2L))
        val restored = other.readBulk("Alice")!!
        assertEquals(1500, restored.points)
        assertNull("an older backup names no softcore score", restored.softcorePoints)
        assertEquals(
            RetroAchievementsUserGame(
                1, "Super Metroid", "Super Nintendo Entertainment System", earned = 12, total = 40, imageIconPath = "/Images/000001.png",
                lastAwardedAtMillis = 1_700_000_000_000L, earnedHardcore = 0, awardKind = null,
            ),
            restored.games.single(),
        )
    }
}
