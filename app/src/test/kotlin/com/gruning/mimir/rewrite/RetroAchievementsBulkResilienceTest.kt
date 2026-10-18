package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaRecentOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedger
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsRecentUnlock
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementAccount
import com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementGame
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The bulk read when RetroAchievements answers "no games" for an account that has progress, and the per-game reads that keep the ledger full when the bulk read
 * cannot. Logcat on the Thor showed `bulk outcome=complete games=0 persisted=0` at two cold starts: an empty complete read used to be saved as the truth, so nothing
 * survived a restart and the Bottom loaded game by game. Counting fakes stand in for the client; no network, no credential.
 */
class RetroAchievementsBulkResilienceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var signedIn: String? = "bulk-player"
    private var now = 1_000_000L
    private var gameCalls = 0
    private var recentCalls = 0
    private val logs = mutableListOf<String>()
    private var gamesAnswer: () -> RaUserGamesOutcome = { RaUserGamesOutcome.Read(emptyList(), 0) }
    private var pointsAnswer: () -> RaPointsOutcome = { RaPointsOutcome.Read(1500) }
    private var recentAnswer: () -> RaRecentOutcome = { RaRecentOutcome.Read(emptyList()) }
    private var book = RetroAchievementsLaneBook()

    private val ledger by lazy { RetroAchievementsLedger(File(temp.root, "ra_ledger")) }

    private val earnedGames = listOf(
        RetroAchievementsUserGame(1, "Super Metroid", SNES, earned = 12, total = 40, imageIconPath = "/Images/000001.png"),
        RetroAchievementsUserGame(2, "Sonic the Hedgehog 2", "Mega Drive", earned = 3, total = 30),
        RetroAchievementsUserGame(3, "An Obscure Game", "Game Boy", earned = 1, total = 10),
    )
    private val library = listOf(LocalGameKey("local-metroid", "snes", "Super Metroid (USA)"))
    private val game77Rom = LocalGameKey("local-77", "snes", "Game 77")
    private val platforms = mapOf(SNES to "snes", "Mega Drive" to "genesis", "Game Boy" to "gb")

    @Before
    fun reset() = CompanionActivityAchievements.resetForTest()

    private fun recentUnlock(id: Int) =
        RetroAchievementsRecentUnlock(id, "Unlock $id", "d", 5, "%05d".format(id), 1, "Super Metroid", SNES, 1_700_000_000_000L, hardcore = false)

    private fun loader(
        withLedger: Boolean = true,
        laneBook: RetroAchievementsLaneBook = book,
        localGames: suspend () -> List<LocalGameKey> = { library },
    ) = ActivityBulkLoader(
        book = laneBook,
        account = { signedIn },
        loadGames = { gameCalls += 1; gamesAnswer() },
        loadPoints = { pointsAnswer() },
        library = localGames,
        platformIdFor = platforms::get,
        publish = { account, snapshots, points -> CompanionActivityAchievements.publishBulk(account, snapshots, points) },
        clock = { now },
        workContext = EmptyCoroutineContext,
        publishContext = EmptyCoroutineContext,
        loadRecent = { recentCalls += 1; recentAnswer() },
        publishRecent = { account, unlocks -> CompanionActivityAchievements.publishRecent(account, unlocks) },
        publishPoints = { account, points -> CompanionActivityAchievements.publishPoints(account, points) },
        ledger = if (withLedger) ledger else null,
        publishHydrated = { account, snapshots, points, recent -> CompanionActivityAchievements.publishHydrated(account, snapshots, points, recent) },
        markSettled = CompanionActivityAchievements::markSettled,
        log = { logs += it },
    )

    private fun bulkFile(account: String) = File(File(File(temp.root, "ra_ledger"), RetroAchievementsLedger.accountKey(account)!!), "bulk.json")

    private fun progress(gameId: Int, unlocked: Int, count: Int = 10) = RetroAchievementsProgress(
        gameId = gameId,
        title = "Game $gameId",
        achievements = (1..count).map {
            RetroAchievementsAchievement(
                id = gameId * 100 + it,
                title = "A$it",
                description = "d",
                points = 5,
                unlocked = it <= unlocked,
                unlockedHardcore = false,
                earnedAt = if (it <= unlocked) "2026-02-0$it 09:30:00" else null,
            )
        },
        imageIconPath = "/Images/%06d.png".format(gameId),
        consoleName = SNES,
    )

    // "No games" for an account that has progress ---------------------------------------------------------------------------------------

    @Test
    fun `no games while the profile has points is not believed, so the games are not saved, wiped or published but the points and recent unlocks are`() = runTest {
        // A good start: a complete read of three games is on the page and in the ledger.
        gamesAnswer = { RaUserGamesOutcome.Read(earnedGames, 3) }
        val running = loader()
        assertEquals(ActivityBulkResult.LOADED, running.load())
        val held = CompanionActivityAchievements.snapshots.value
        logs.clear()

        // The next read says RetroAchievements has no games for this account, while the profile now has 2000 points and the strip has an unlock.
        gamesAnswer = { RaUserGamesOutcome.Read(emptyList(), 0) }
        pointsAnswer = { RaPointsOutcome.Read(2000) }
        recentAnswer = { RaRecentOutcome.Read(listOf(recentUnlock(7))) }
        now += 60_000L
        assertEquals(ActivityBulkResult.SUSPECT_EMPTY, running.load())

        val saved = ledger.readBulk("bulk-player")!!
        assertEquals("the games are untouched", earnedGames.map { it.gameId to it.earned }, saved.games.map { it.gameId to it.earned })
        assertEquals("the points are the fresh ones", 2000, saved.points)
        assertEquals(listOf(7), saved.recent!!.map { it.achievementId })
        assertEquals("the page keeps the games it holds", held, CompanionActivityAchievements.snapshots.value)
        assertEquals(2000, CompanionActivityAchievements.points.value?.points)
        assertEquals(listOf(7), CompanionActivityAchievements.recent.value?.unlocks?.map { it.achievementId })
        assertEquals(listOf("bulk outcome=suspect-empty games=0 persisted=3 why=points"), logs)
        assertEquals("the page stops saying checking", "bulk-player", CompanionActivityAchievements.settledAccount.value)
    }

    @Test
    fun `no games and the same points and unlocks as saved leaves the file byte-identical`() = runTest {
        gamesAnswer = { RaUserGamesOutcome.Read(earnedGames, 3) }
        val running = loader()
        assertEquals(ActivityBulkResult.LOADED, running.load())
        val file = bulkFile("bulk-player")
        val before = file.readBytes()
        logs.clear()

        gamesAnswer = { RaUserGamesOutcome.Read(emptyList(), 0) }
        now += 60_000L
        assertEquals(ActivityBulkResult.SUSPECT_EMPTY, running.load())

        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(listOf("bulk outcome=suspect-empty games=0 persisted=3 why=points"), logs)
    }

    @Test
    fun `no games while the profile has points and nothing is saved keeps no games but saves the points`() = runTest {
        assertEquals(ActivityBulkResult.SUSPECT_EMPTY, loader().load())

        val saved = ledger.readBulk("bulk-player")!!
        assertEquals("no game was invented", emptyList<RetroAchievementsUserGame>(), saved.games)
        assertEquals(1500, saved.points)
        assertTrue("a file with no read of its own: a backup can still merge into it", saved.laneOnly)
        assertTrue(CompanionActivityAchievements.snapshots.value.isEmpty())
        assertEquals(1500, CompanionActivityAchievements.points.value?.points)
        assertEquals(listOf("bulk outcome=suspect-empty games=0 persisted=0 why=points"), logs)
        assertEquals("bulk-player", CompanionActivityAchievements.settledAccount.value)
        assertEquals("the recent unlocks are still asked for once", 1, recentCalls)
    }

    @Test
    fun `no games and unknown points is still not believed when a per-game read already saw an unlock`() = runTest {
        pointsAnswer = { RaPointsOutcome.Unavailable }
        val epoch = book.accountEpoch()
        book.noteSettled(DerivedLane.of("local-metroid", "bulk-player", MimirAchievementLaneState.Loaded(progress(1, unlocked = 2))), epoch)

        assertEquals(ActivityBulkResult.SUSPECT_EMPTY, loader().load())
        assertEquals(emptyList<RetroAchievementsUserGame>(), ledger.readBulk("bulk-player")!!.games)
        assertNull("no points were read: unknown, never zero", ledger.readBulk("bulk-player")!!.points)
        assertNull(CompanionActivityAchievements.points.value)
        assertEquals(listOf("bulk outcome=suspect-empty games=0 persisted=0 why=unlock"), logs)
    }

    @Test
    fun `a per-game read that saw nothing earned proves nothing, and an unlock of an earlier account is forgotten`() = runTest {
        pointsAnswer = { RaPointsOutcome.Read(0) }
        val epoch = book.accountEpoch()
        book.noteSettled(DerivedLane.of("g", "bulk-player", MimirAchievementLaneState.Loaded(progress(1, unlocked = 0))), epoch)
        book.noteSettled(DerivedLane.of("h", "bulk-player", MimirAchievementLaneState.Loaded(progress(2, unlocked = 4))), epoch)
        book.accountChanged() // the account side changed: what was settled for the old one no longer counts

        assertEquals(ActivityBulkResult.LOADED, loader().load())
        assertEquals(listOf("bulk outcome=complete games=0 persisted=0"), logs)
    }

    @Test
    fun `no games and unknown points is not believed when the ledger already holds earned counts`() = runTest {
        pointsAnswer = { RaPointsOutcome.Unavailable }
        recentAnswer = { RaRecentOutcome.Unavailable }
        ledger.saveBulk("bulk-player", earnedGames, null, null, nowMillis = 5L)
        val bytes = bulkFile("bulk-player").readBytes()

        assertEquals(ActivityBulkResult.SUSPECT_EMPTY, loader().load())

        assertTrue("nothing new to save: the file is byte-identical", bytes.contentEquals(bulkFile("bulk-player").readBytes()))
        assertEquals(listOf("bulk outcome=suspect-empty games=0 persisted=3 why=ledger"), logs)
    }

    @Test
    fun `the first evidence is the one named when several hold`() = runTest {
        ledger.saveBulk("bulk-player", earnedGames, null, null, nowMillis = 5L)
        val epoch = book.accountEpoch()
        book.noteSettled(DerivedLane.of("local-metroid", "bulk-player", MimirAchievementLaneState.Loaded(progress(1, unlocked = 2))), epoch)

        assertEquals(ActivityBulkResult.SUSPECT_EMPTY, loader().load())
        assertTrue(logs.single().endsWith("why=points"))

        logs.clear()
        pointsAnswer = { RaPointsOutcome.Read(0) }
        assertEquals(ActivityBulkResult.SUSPECT_EMPTY, loader().load())
        assertTrue(logs.single().endsWith("why=unlock"))
    }

    @Test
    fun `a points or recent request that failed leaves the saved ones in a suspect read`() = runTest {
        gamesAnswer = { RaUserGamesOutcome.Read(earnedGames, 3) }
        recentAnswer = { RaRecentOutcome.Read(listOf(recentUnlock(7))) }
        val running = loader()
        assertEquals(ActivityBulkResult.LOADED, running.load())

        gamesAnswer = { RaUserGamesOutcome.Read(emptyList(), 0) }
        pointsAnswer = { RaPointsOutcome.Unavailable }
        recentAnswer = { RaRecentOutcome.Unavailable }
        assertEquals(ActivityBulkResult.SUSPECT_EMPTY, running.load())

        val saved = ledger.readBulk("bulk-player")!!
        assertEquals(1500, saved.points)
        assertEquals(listOf(7), saved.recent!!.map { it.achievementId })
        assertEquals(3, saved.games.size)
    }

    @Test
    fun `a suspect read that reports zero points keeps the saved total`() = runTest {
        gamesAnswer = { RaUserGamesOutcome.Read(earnedGames, 3) }
        pointsAnswer = { RaPointsOutcome.Read(120) }
        val running = loader()
        assertEquals(ActivityBulkResult.LOADED, running.load())

        val epoch = book.accountEpoch()
        book.noteSettled(DerivedLane.of("local-metroid", "bulk-player", MimirAchievementLaneState.Loaded(progress(1, unlocked = 2))), epoch)

        gamesAnswer = { RaUserGamesOutcome.Read(emptyList(), 0) }
        pointsAnswer = { RaPointsOutcome.Read(0) }
        assertEquals(ActivityBulkResult.SUSPECT_EMPTY, running.load())

        val saved = ledger.readBulk("bulk-player")!!
        assertEquals(120, saved.points)
    }

    @Test
    fun `no games, no points, nothing earned anywhere is a real empty answer and is saved as before`() = runTest {
        pointsAnswer = { RaPointsOutcome.Read(0) }

        assertEquals(ActivityBulkResult.LOADED, loader().load())

        assertEquals(emptyList<RetroAchievementsUserGame>(), ledger.readBulk("bulk-player")!!.games)
        assertEquals(listOf("bulk outcome=complete games=0 persisted=0"), logs)
        assertEquals("bulk-player", CompanionActivityAchievements.settledAccount.value)
    }

    @Test
    fun `a complete read keeps the games only a per-game read has seen with nothing earned`() = runTest {
        // The lane looked at game 77 (0 of 12) and game 78 (2 of 8); then a complete read lists only game 1. Game 77's ROM is in the library.
        val seen = loader()
        assertTrue(seen.noteLaneResult("bulk-player", progress(77, unlocked = 0, count = 12), book.accountEpoch()))
        seen.noteLaneResult("bulk-player", progress(78, unlocked = 2, count = 8), book.accountEpoch())
        seen.flushLaneResults()
        gamesAnswer = { RaUserGamesOutcome.Read(listOf(earnedGames[0]), 1) }
        pointsAnswer = { RaPointsOutcome.Read(0) }

        assertEquals(ActivityBulkResult.LOADED, loader(localGames = { library + game77Rom }).load())

        val saved = ledger.readBulk("bulk-player")!!.games.map { it.gameId to it.earned }
        assertEquals("the zero row stays; a game with earned counts RA no longer lists goes", listOf(1 to 12, 77 to 0), saved)
        assertEquals(listOf("bulk outcome=complete games=1 persisted=2"), logs)
        assertEquals(setOf(1, 77), CompanionActivityAchievements.snapshots.value.mapNotNull { it.raGameId }.toSet())
    }

    @Test
    fun `a per-game write that lands while a bulk read is under way is not erased by its save`() = runTest {
        gamesAnswer = { RaUserGamesOutcome.Read(listOf(earnedGames[0]), 1) }
        pointsAnswer = { RaPointsOutcome.Read(0) }
        // The read has looked at the ledger (it held nothing) and asked the network; now, before it saves, the lane writes game 77 (0 of 12).
        val running = loader(localGames = {
            ledger.upsertBulkGames("bulk-player", listOf(RetroAchievementsUserGame(77, "Game 77", SNES, earned = 0, total = 12)), nowMillis = 7L)
            library + game77Rom
        })

        assertEquals(ActivityBulkResult.LOADED, running.load())

        assertEquals("the save kept the row that landed in the gap", listOf(1, 77), ledger.readBulk("bulk-player")!!.games.map { it.gameId })
        assertEquals(setOf(1, 77), CompanionActivityAchievements.snapshots.value.mapNotNull { it.raGameId }.toSet())
    }

    // The page lists the library's opened games ---------------------------------------------------------------------------------------------

    @Test
    fun `a game with nothing earned shows only while its ROM is in the library, and one with earned counts shows either way`() = runTest {
        val rows = listOf(
            RetroAchievementsUserGame(77, "Game 77", SNES, earned = 0, total = 12),
            RetroAchievementsUserGame(78, "Game 78", SNES, earned = 0, total = 8),
            RetroAchievementsUserGame(79, "Game 79", SNES, earned = 3, total = 10),
        )
        ledger.upsertBulkGames("bulk-player", rows, nowMillis = 5L)
        // Game 77's ROM is still in the library; the ROMs of 78 and 79 were removed.
        assertTrue(loader(localGames = { library + game77Rom }).hydrate())

        val shown = CompanionActivityAchievements.snapshots.value.associateBy { it.raGameId }
        assertEquals("the removed ROM's 0 of 8 row is not shown", setOf(77, 79), shown.keys)
        assertEquals("a game of the library keeps its library identity", "local-77", shown.getValue(77).gameId)
        assertEquals("the removed ROM's 3 of 10 row still shows, under the provider's own key", "ra:79", shown.getValue(79).gameId)
        assertEquals("nothing is deleted from the ledger", listOf(77, 78, 79), ledger.readBulk("bulk-player")!!.games.map { it.gameId })

        // The ROM of game 78 comes back: its row shows again, with nothing re-read.
        CompanionActivityAchievements.resetForTest()
        assertTrue(loader(localGames = { library + game77Rom + LocalGameKey("local-78", "snes", "Game 78") }).hydrate())
        assertEquals(setOf(77, 78, 79), CompanionActivityAchievements.snapshots.value.mapNotNull { it.raGameId }.toSet())
    }

    // A backup restore onto a ledger the lane filled first ------------------------------------------------------------------------------------

    @Test
    fun `a backup restored after per-game reads filled the ledger is merged, not dropped`() = runTest {
        val running = loader()
        running.noteLaneResult("bulk-player", progress(42, unlocked = 0), book.accountEpoch())
        running.noteLaneResult("bulk-player", progress(43, unlocked = 2), book.accountEpoch())
        running.flushLaneResults()
        val backup = MigrationAchievementAccount(
            username = "bulk-player",
            points = 900,
            games = listOf(
                MigrationAchievementGame(raGameId = 43, title = "Game 43", consoleName = SNES, earned = 9, total = 10),
                MigrationAchievementGame(raGameId = 44, title = "Game 44", consoleName = SNES, earned = 5, total = 8),
            ),
        )

        assertEquals("the backup's games were taken", 2, restoreAchievementsFromBackup(ledger, listOf(backup), nowMillis = 77L))

        val saved = ledger.readBulk("bulk-player")!!
        assertEquals(listOf(42, 43, 44), saved.games.map { it.gameId })
        assertEquals("the device read game 43 itself: its count wins", 2, saved.games.first { it.gameId == 43 }.earned)
        assertEquals(5, saved.games.first { it.gameId == 44 }.earned)
        assertEquals(900, saved.points)
        assertEquals("importing twice changes nothing", 0, restoreAchievementsFromBackup(ledger, listOf(backup), nowMillis = 99L))

        // The next cold start shows the restored game; the removed ROM's 0 of 10 row is not listed.
        CompanionActivityAchievements.resetForTest()
        assertTrue(loader().hydrate())
        assertEquals(setOf(43, 44), CompanionActivityAchievements.snapshots.value.mapNotNull { it.raGameId }.toSet())
        assertEquals(900, CompanionActivityAchievements.points.value?.points)
    }

    // The per-game reads feed the ledger -------------------------------------------------------------------------------------------------

    @Test
    fun `a per-game result lands in the ledger and shows at the next cold start with no request`() = runTest {
        val running = loader()
        assertTrue("the first note asks for a flush", running.noteLaneResult("bulk-player", progress(42, unlocked = 3), book.accountEpoch()))
        assertFalse("a second note for a flush already asked for does not", running.noteLaneResult("bulk-player", progress(42, unlocked = 4), book.accountEpoch()))
        assertFalse("nothing is written before the flush", bulkFile("bulk-player").exists())

        assertEquals(1, running.flushLaneResults())

        val saved = ledger.readBulk("bulk-player")!!
        assertEquals(listOf(42), saved.games.map { it.gameId })
        assertEquals("the newest read of the game is the one kept", 4, saved.games.single().earned)
        assertEquals(10, saved.games.single().total)
        assertEquals(SNES, saved.games.single().consoleName)
        assertEquals("/Images/000042.png", saved.games.single().imageIconPath)

        // The next cold start: a fresh loader over the same folder, a fresh lane book, a library that knows the game's file, and no page held in memory.
        CompanionActivityAchievements.resetForTest()
        val laneBook = RetroAchievementsLaneBook()
        laneBook.noteMatchedRaId(42, "local-metroid")
        gameCalls = 0
        assertTrue(loader(laneBook = laneBook).hydrate())

        val shown = CompanionActivityAchievements.snapshots.value.single()
        assertEquals("local-metroid", shown.gameId)
        assertEquals(4, shown.unlocked)
        assertEquals(10, shown.total)
        assertEquals(42, shown.raGameId)
        assertEquals(SNES, shown.consoleName)
        assertEquals("bulk-player", CompanionActivityAchievements.settledAccount.value)
        assertEquals("no request was made to show it", 0, gameCalls)
    }

    @Test
    fun `a burst of per-game results is one ledger write and keeps the points and recent unlocks already saved`() = runTest {
        ledger.saveBulk("bulk-player", earnedGames, 1500, emptyList(), nowMillis = 5L)
        val running = loader()

        assertTrue(running.noteLaneResult("bulk-player", progress(1, unlocked = 20, count = 40), book.accountEpoch()))
        assertFalse(running.noteLaneResult("bulk-player", progress(50, unlocked = 1), book.accountEpoch()))
        assertFalse(running.noteLaneResult("bulk-player", progress(51, unlocked = 0), book.accountEpoch()))
        assertEquals("one write for the three", 1, running.flushLaneResults())

        val saved = ledger.readBulk("bulk-player")!!
        assertEquals(listOf(1, 2, 3, 50, 51), saved.games.map { it.gameId })
        assertEquals(20, saved.games.first { it.gameId == 1 }.earned)
        assertEquals(1500, saved.points)
        assertEquals(emptyList<Any>(), saved.recent)
        assertEquals("a flush with nothing queued writes nothing", 0, running.flushLaneResults())
        assertTrue("and the next note asks for a flush again", running.noteLaneResult("bulk-player", progress(52, unlocked = 1), book.accountEpoch()))
    }

    @Test
    fun `a per-game result a sign-in change overtook writes nothing, under either account`() = runTest {
        val running = loader()
        running.noteLaneResult("bulk-player", progress(42, unlocked = 3), book.accountEpoch())
        book.accountChanged()

        assertEquals(0, running.flushLaneResults())

        assertFalse(bulkFile("bulk-player").exists())
    }

    @Test
    fun `a per-game result whose account is no longer the signed-in one writes nothing`() = runTest {
        val running = loader()
        running.noteLaneResult("bulk-player", progress(42, unlocked = 3), book.accountEpoch())
        signedIn = "someone-else"

        assertEquals(0, running.flushLaneResults())

        assertFalse(bulkFile("bulk-player").exists())
        assertFalse(bulkFile("someone-else").exists())
    }

    @Test
    fun `a per-game result with nothing to keep, or no ledger, asks for no flush`() = runTest {
        val running = loader()
        assertFalse(running.noteLaneResult(null, progress(42, unlocked = 3), book.accountEpoch()))
        assertFalse(running.noteLaneResult("  ", progress(42, unlocked = 3), book.accountEpoch()))
        assertFalse(running.noteLaneResult("bulk-player", progress(0, unlocked = 3), book.accountEpoch()))
        assertFalse(running.noteLaneResult("bulk-player", progress(42, unlocked = 0, count = 0), book.accountEpoch()))
        assertFalse(loader(withLedger = false).noteLaneResult("bulk-player", progress(42, unlocked = 3), book.accountEpoch()))
        assertEquals(0, running.flushLaneResults())
        assertEquals(0, loader(withLedger = false).flushLaneResults())
        assertFalse(bulkFile("bulk-player").exists())
    }

    @Test
    fun `a settled per-game unlock is only known for the current account epoch`() {
        val epoch = book.accountEpoch()
        assertFalse(book.settledKnowsUnlock())
        book.noteSettled(DerivedLane.of("g", "bulk-player", MimirAchievementLaneState.Loaded(progress(1, unlocked = 0))), epoch)
        assertFalse("a game with nothing earned proves nothing", book.settledKnowsUnlock())
        book.noteSettled(DerivedLane.of("h", "bulk-player", MimirAchievementLaneState.Loaded(progress(2, unlocked = 1))), epoch)
        assertTrue(book.settledKnowsUnlock())
        book.accountChanged()
        assertFalse(book.settledKnowsUnlock())
    }

    private companion object {
        const val SNES = "Super Nintendo Entertainment System"
    }
}
