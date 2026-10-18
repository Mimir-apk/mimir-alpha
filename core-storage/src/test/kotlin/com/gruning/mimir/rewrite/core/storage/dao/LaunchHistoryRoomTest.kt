package com.gruning.mimir.rewrite.core.storage.dao

import androidx.room.Room
import com.gruning.mimir.rewrite.core.model.library.DailyLaunchCount
import com.gruning.mimir.rewrite.core.model.library.launchHistoryStartMillis
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.LaunchProofEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The two reads the Activity page's launch history stands on, run through a real in-memory Room database and the DAO code
 * Room generates (in the shape of [HiddenFavoriteReadModelTest]), because both live in `@Query` annotations that no
 * constant-SQL test reaches:
 *
 * - launches per local calendar day ([observeLaunchesPerDay] over [LaunchProofDao.intentSentTimestampsSince]);
 * - each game's first played ([GameRowProjection.firstPlayedMillis]), beside its last played and play count.
 *
 * Both define a launch as `GameRow.playCount` does: a `launch_proofs` row whose outcome is `intent_sent`. A failed dispatch
 * is none of the three. The days are cut in an explicit zone each test passes in, never the JVM's default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class LaunchHistoryRoomTest {

    private lateinit var db: MimirDatabase
    private val utc = ZoneId.of("UTC")
    private val berlin = ZoneId.of("Europe/Berlin")

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MimirDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // ── launches per day ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a week with no launches is seven zero days`() = runBlocking {
        val week = bars(now = "2026-10-02T15:30:00Z", zone = utc)

        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 9, 26), week.first().date)
        assertEquals(LocalDate.of(2026, 10, 2), week.last().date)
        assertEquals(List(7) { 0 }, week.map { it.count })
    }

    @Test
    fun `only intent_sent rows are launches, whether the target is an emulated game or an Android package`() = runBlocking {
        db.gameDao().upsertAll(listOf(game(MANA), game(EARTH)))
        db.launchProofDao().apply {
            record(proof(MANA, "2026-10-01T10:00:00Z"))
            record(proof(MANA, "2026-10-01T10:05:00Z", outcome = "BRIDGE_ERROR")) // a failed dispatch is not a play
            record(proof(EARTH, "2026-10-02T09:00:00Z"))
            record(proof(EARTH, "2026-10-02T09:01:00Z", outcome = "EMULATOR_NOT_INSTALLED"))
            record(androidProof("com.example.game", "2026-10-02T11:00:00Z"))
            record(androidProof("com.example.game", "2026-10-02T11:01:00Z", outcome = "BRIDGE_ERROR"))
        }

        val week = bars(now = "2026-10-02T15:30:00Z", zone = utc)

        assertEquals(listOf(0, 0, 0, 0, 0, 1, 2), week.map { it.count })
    }

    @Test
    fun `the bars count the launches playCount counts, plus the native Android launches no game row can see`() = runBlocking {
        db.gameDao().upsertAll(listOf(game(MANA), game(EARTH)))
        db.launchProofDao().apply {
            record(proof(MANA, "2026-10-01T10:00:00Z"))
            record(proof(MANA, "2026-10-02T10:00:00Z"))
            record(proof(EARTH, "2026-10-02T10:30:00Z"))
            record(proof(EARTH, "2026-10-02T10:31:00Z", outcome = "BRIDGE_ERROR"))
        }
        val playCounts = db.gameDao().visibleGameRowsForPlatform(SNES).first().sumOf { it.playCount }

        assertEquals("sum of every game's playCount", 3, playCounts)
        assertEquals("the bars over the same launches", playCounts, bars("2026-10-02T15:30:00Z", utc).sumOf { it.count })

        // One launch no playCount can hold: a native Android game has no game row at all, and it is a launch that happened.
        db.launchProofDao().record(androidProof("com.example.game", "2026-10-02T12:00:00Z"))

        assertEquals(
            "the playCounts are untouched",
            playCounts,
            db.gameDao().visibleGameRowsForPlatform(SNES).first().sumOf { it.playCount },
        )
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 3), bars("2026-10-02T15:30:00Z", utc).map { it.count })
    }

    // ── orphans: launches and favourites of a game that is no longer in the library (alpha cert F9) ──

    @Test
    fun `a game whose folder is removed stops counting on the bars, and counts again when the folder is added back`() = runBlocking {
        // Measured on Thor: library games had 16 launches this week; a test folder of 8 games was added, launched, then removed,
        // and the weekly count read 24.
        val kept = listOf("snes:kept-a", "snes:kept-b")
        val probes = listOf("snes:probe-a", "snes:probe-b", "snes:probe-c")
        libraryHas(kept)
        libraryHas(probes, PROBE_ROOT)
        kept.forEach { id -> repeat(2) { db.launchProofDao().record(proof(id, "2026-10-02T10:0$it:00Z")) } }
        probes.forEach { id -> repeat(2) { db.launchProofDao().record(proof(id, "2026-10-02T11:0$it:00Z")) } }
        assertEquals("his games plus the probes", 10, bars("2026-10-02T15:30:00Z", utc).sumOf { it.count })

        removeFolder(PROBE_ROOT)

        assertEquals("only the games that still exist", 4, bars("2026-10-02T15:30:00Z", utc).sumOf { it.count })
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 4), bars("2026-10-02T15:30:00Z", utc).map { it.count })
        assertEquals(
            "the launch history is KEPT, not deleted: it is only not counted",
            6,
            probes.sumOf { db.launchProofDao().historyForGame(it).first().size },
        )

        // Add the folder back: the same ids come back (Mimir's game ids are stable across a re-add) and their history with them.
        libraryHas(probes, PROBE_ROOT)

        assertEquals("the history is restored", 10, bars("2026-10-02T15:30:00Z", utc).sumOf { it.count })
    }

    @Test
    fun `an orphan launch is left off every day it fell on, not just today's`() = runBlocking {
        libraryHas(listOf(MANA))
        libraryHas(listOf(EARTH), PROBE_ROOT)
        db.launchProofDao().apply {
            record(proof(MANA, "2026-09-28T10:00:00Z"))
            record(proof(EARTH, "2026-09-28T10:30:00Z"))
            record(proof(EARTH, "2026-10-01T10:30:00Z"))
            record(proof(MANA, "2026-10-02T10:00:00Z"))
        }
        removeFolder(PROBE_ROOT)

        // The week is 26 Sep to 2 Oct. Counting the orphan it would be [0, 0, 2, 0, 0, 1, 1].
        assertEquals(listOf(0, 0, 1, 0, 0, 0, 1), bars("2026-10-02T15:30:00Z", utc).map { it.count })
    }

    @Test
    fun `an orphan is gone from Most launched, Last played, Recently played and the favourites, and its favourite is kept for the re-add`() =
        runBlocking {
            libraryHas(listOf(MANA))
            libraryHas(listOf(EARTH), PROBE_ROOT)
            db.favoriteDao().set(FavoriteEntity(gameId = MANA, favoritedAtMillis = 10L))
            db.favoriteDao().set(FavoriteEntity(gameId = EARTH, favoritedAtMillis = 20L))
            listOf(MANA, EARTH).forEach { id -> repeat(3) { db.launchProofDao().record(proofAt(id, 1_000L + it)) } }
            val gameDao = db.gameDao()
            assertEquals(listOf(EARTH, MANA), gameDao.favoriteGameRows().first().map { it.gameId })
            assertEquals(setOf(EARTH, MANA), gameDao.recentlyPlayedGameRows(Int.MAX_VALUE).first().map { it.gameId }.toSet())

            removeFolder(PROBE_ROOT)

            assertEquals("Favorites list", listOf(MANA), gameDao.favoriteGameRows().first().map { it.gameId })
            assertEquals("Favorites picker's star state", listOf(MANA), gameDao.favoriteGroupIds().first())
            assertEquals(
                "Last played / Recently played / Most launched / Been a while",
                listOf(MANA),
                gameDao.recentlyPlayedGameRows(Int.MAX_VALUE).first().map { it.gameId },
            )
            assertEquals("the visible launches of the games that remain", 3, gameDao.recentlyPlayedGameRows(Int.MAX_VALUE).first().sumOf { it.playCount })
            assertEquals("the favourite row itself is kept, so a re-add restores the star", true, db.favoriteDao().isFavorite(EARTH))
            assertEquals("and so is its launch history", 3, db.launchProofDao().historyForGame(EARTH).first().size)

            libraryHas(listOf(EARTH), PROBE_ROOT)

            assertEquals(listOf(EARTH, MANA), gameDao.favoriteGameRows().first().map { it.gameId })
            assertEquals(3, gameDao.recentlyPlayedGameRows(Int.MAX_VALUE).first().single { it.gameId == EARTH }.playCount)
        }

    @Test
    fun `a duplicate-group member and a game on unplugged storage are in the library, so their launches count`() = runBlocking {
        db.gameDao().upsertAll(
            listOf(
                game(HEAD),
                game(MEMBER, primary = false, groupId = HEAD),
                game(EARTH).copy(availability = "STORAGE_UNAVAILABLE"),
            ),
        )
        db.launchProofDao().apply {
            record(proof(MEMBER, "2026-10-02T10:00:00Z"))
            record(proof(EARTH, "2026-10-02T10:30:00Z"))
        }

        assertEquals(listOf(0, 0, 0, 0, 0, 0, 2), bars("2026-10-02T15:30:00Z", utc).map { it.count })
    }

    @Test
    fun `a hidden game's launches still count on the bars, though Last played leaves the game out`() = runBlocking {
        // a hidden game is still in the library and its launches happened, so launch totals keep them.
        libraryHas(listOf(MANA, EARTH))
        hide(EARTH)
        db.launchProofDao().apply {
            record(proof(MANA, "2026-10-02T10:00:00Z"))
            record(proof(EARTH, "2026-10-02T10:30:00Z"))
        }

        assertEquals(listOf(0, 0, 0, 0, 0, 0, 2), bars("2026-10-02T15:30:00Z", utc).map { it.count })
        assertEquals(listOf(MANA), db.gameDao().recentlyPlayedGameRows(Int.MAX_VALUE).first().map { it.gameId })
    }

    @Test
    fun `the bars flow re-emits without the launches of a folder removed while the page is up`() = runBlocking {
        libraryHas(listOf(MANA))
        libraryHas(listOf(EARTH), PROBE_ROOT)
        db.launchProofDao().apply {
            record(proof(MANA, "2026-10-02T10:00:00Z"))
            record(proof(EARTH, "2026-10-02T10:30:00Z"))
        }
        val emissions = Channel<List<DailyLaunchCount>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) {
            db.launchProofDao()
                .observeLaunchesPerDay(clock = { millis("2026-10-02T15:30:00Z") }, zone = { utc })
                .collect { emissions.send(it) }
        }

        val before = withTimeout(5_000) { emissions.receive() }
        removeFolder(PROBE_ROOT)
        val after = withTimeout(5_000) { emissions.receive() }
        collector.cancelAndJoin()

        assertEquals(2, before.sumOf { it.count })
        assertEquals("a write to `games` alone re-ran the read", 1, after.sumOf { it.count })
    }

    @Test
    fun `the timestamp read includes its lower bound, leaves out earlier rows, and takes no failed dispatch`() = runBlocking {
        libraryHas(listOf(MANA, EARTH))
        db.launchProofDao().apply {
            record(proofAt(MANA, 999L))
            record(proofAt(MANA, 1_000L))
            record(proofAt(MANA, 1_500L, outcome = "BRIDGE_ERROR"))
            record(proofAt(EARTH, 2_000L))
        }

        val stamps = db.launchProofDao().intentSentTimestampsSince(1_000L).first()

        assertEquals(listOf(1_000L, 2_000L), stamps.sorted())
    }

    @Test
    fun `the window includes its first instant and leaves out the millisecond before it`() = runBlocking {
        libraryHas(listOf(MANA))
        db.launchProofDao().apply {
            record(proof(MANA, "2026-09-25T23:59:59.999Z")) // one millisecond before the oldest day
            record(proof(MANA, "2026-09-26T00:00:00.000Z")) // the first instant of the oldest day
        }

        val week = bars(now = "2026-10-02T15:30:00Z", zone = utc)

        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0), week.map { it.count })
    }

    @Test
    fun `a spring-forward day keeps its own launches at both edges`() = runBlocking {
        libraryHas(listOf(MANA))
        // Berlin 2026-03-29 runs 2026-03-28T23:00Z to 2026-03-29T22:00Z: 23 hours.
        db.launchProofDao().apply {
            record(proof(MANA, "2026-03-28T22:59:59.999Z")) // the 28th, 23:59:59.999 CET
            record(proof(MANA, "2026-03-28T23:00:00.000Z")) // the 29th, 00:00 CET
            record(proof(MANA, "2026-03-29T21:59:59.999Z")) // the 29th, 23:59:59.999 CEST
            record(proof(MANA, "2026-03-29T22:00:00.000Z")) // the 30th, 00:00 CEST
        }

        val byDate = bars(now = "2026-03-31T12:00:00Z", zone = berlin).associate { it.date to it.count }

        assertEquals(1, byDate[LocalDate.of(2026, 3, 28)])
        assertEquals(2, byDate[LocalDate.of(2026, 3, 29)])
        assertEquals(1, byDate[LocalDate.of(2026, 3, 30)])
    }

    @Test
    fun `a fall-back day keeps its late evening on that day`() = runBlocking {
        libraryHas(listOf(MANA))
        // Berlin 2026-10-25 runs 2026-10-24T22:00Z to 2026-10-25T23:00Z: 25 hours.
        db.launchProofDao().apply {
            record(proof(MANA, "2026-10-24T22:00:00.000Z")) // the 25th, 00:00 CEST
            record(proof(MANA, "2026-10-25T01:30:00.000Z")) // the 25th, 02:30 CET (the second 02:30)
            record(proof(MANA, "2026-10-25T22:30:00.000Z")) // the 25th, 23:30 CET, over 24 hours after the day began
            record(proof(MANA, "2026-10-25T23:00:00.000Z")) // the 26th, 00:00 CET
        }

        val byDate = bars(now = "2026-10-27T10:00:00Z", zone = berlin).associate { it.date to it.count }

        assertEquals(3, byDate[LocalDate.of(2026, 10, 25)])
        assertEquals(1, byDate[LocalDate.of(2026, 10, 26)])
    }

    @Test
    fun `a launch after midnight on a flow that has been up since yesterday lands on the new day`() = runBlocking {
        val now = AtomicLong(millis("2026-10-02T23:59:00Z"))
        libraryHas(listOf(MANA))
        db.launchProofDao().record(proof(MANA, "2026-10-02T12:00:00Z"))
        val emissions = Channel<List<DailyLaunchCount>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) {
            db.launchProofDao().observeLaunchesPerDay(clock = now::get, zone = { utc }).collect { emissions.send(it) }
        }

        val before = withTimeout(5_000) { emissions.receive() }
        now.set(millis("2026-10-03T00:05:00Z"))
        db.launchProofDao().record(proof(MANA, "2026-10-03T00:05:00Z"))
        val after = withTimeout(5_000) { emissions.receive() }
        collector.cancelAndJoin()

        assertEquals(LocalDate.of(2026, 10, 2), before.last().date)
        assertEquals(1, before.last().count)
        assertEquals("the window rolled to the new day", LocalDate.of(2026, 10, 3), after.last().date)
        assertEquals(LocalDate.of(2026, 9, 27), after.first().date)
        assertEquals("the new launch is today's", 1, after.last().count)
        assertEquals("yesterday's launch is yesterday's", 1, after[5].count)
    }

    @Test
    fun `a zone change that moves the oldest day back by more than a day re-reads the launches it had not fetched`() =
        runBlocking {
            val tongatapu = ZoneId.of("Pacific/Tongatapu")
            val chatham = ZoneId.of("Pacific/Chatham")
            val now = AtomicLong(millis("2026-04-07T11:05:00Z"))
            val zone = AtomicReference(tongatapu)
            // Chatham is still on daylight time on 1 April (+13:45) and on standard time on the 7th (+12:45), Tongatapu is
            // +13:00 throughout, so at this instant the 7-day window starts 24 h 45 min EARLIER in Chatham. A lower bound
            // fixed from Tongatapu's window, even with a whole day of margin, would stop 45 minutes short of it.
            assertEquals(
                "tzdata no longer puts Chatham's window 24 h 45 min earlier; pick another pair of zones for this case",
                24L * 3_600_000L + 45L * 60_000L,
                launchHistoryStartMillis(now.get(), tongatapu) - launchHistoryStartMillis(now.get(), chatham),
            )
            libraryHas(listOf(MANA))
            // 00:15 on 1 April in Chatham, the oldest day of ITS window, and 23:30 on 31 March in Tongatapu.
            db.launchProofDao().record(proof(MANA, "2026-03-31T10:30:00Z"))
            val emissions = Channel<List<DailyLaunchCount>>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.IO) {
                db.launchProofDao().observeLaunchesPerDay(clock = now::get, zone = zone::get).collect { emissions.send(it) }
            }

            val before = withTimeout(5_000) { emissions.receive() }
            zone.set(chatham)
            db.launchProofDao().record(proof(MANA, "2026-04-07T11:05:00Z")) // a write, so the flow re-emits
            val after = withTimeout(5_000) { emissions.receive() }
            collector.cancelAndJoin()

            assertEquals("Tongatapu's window ends on the 8th", LocalDate.of(2026, 4, 8), before.last().date)
            assertEquals("and the 31 March launch is before it", 0, before.sumOf { it.count })
            assertEquals("Chatham's window starts on 1 April", LocalDate.of(2026, 4, 1), after.first().date)
            assertEquals("and ends on the 7th", LocalDate.of(2026, 4, 7), after.last().date)
            assertEquals("the launch that was never fetched is on the oldest bar", 1, after.first().count)
            assertEquals("the write that triggered the re-read is on today's", 1, after.last().count)
            assertEquals(2, after.sumOf { it.count })
        }

    // ── first played ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a game never launched has no first played, no last played and no plays, on every read`() = runBlocking {
        db.gameDao().upsertAll(listOf(game(MANA), game(EARTH)))
        db.favoriteDao().set(FavoriteEntity(gameId = MANA, favoritedAtMillis = 1L))
        hide(EARTH)
        // A failed dispatch is a recorded attempt, not a play: it must not make "first played" non-null either.
        db.launchProofDao().record(proofAt(MANA, 50L, outcome = "BRIDGE_ERROR"))

        // Never launched, so not in Last Played; the hidden one is in the Hidden list and readable by id, nowhere else.
        listOf(MANA to VISIBLE_NEVER_LAUNCHED_READS, EARTH to HIDDEN_READS).forEach { (id, expected) ->
            reads(id, expected).forEach { (read, row) ->
                assertNull("$id via $read: first", row.firstPlayedMillis)
                assertNull("$id via $read: last", row.lastPlayedMillis)
                assertEquals("$id via $read: plays", 0, row.playCount)
            }
        }
    }

    @Test
    fun `first played is the earliest intent_sent, ahead of an earlier failure, and agrees with last played and the count`() =
        runBlocking {
            db.gameDao().upsertAll(listOf(game(MANA), game(EARTH)))
            db.favoriteDao().set(FavoriteEntity(gameId = MANA, favoritedAtMillis = 1L))
            hide(EARTH)
            listOf(MANA, EARTH).forEach { id ->
                db.launchProofDao().apply {
                    record(proofAt(id, 100L, outcome = "BRIDGE_ERROR")) // earlier than any launch, and not one
                    record(proofAt(id, 500L))
                    record(proofAt(id, 300L)) // recorded out of order: MIN, not first-inserted
                    record(proofAt(id, 900L))
                    record(proofAt(id, 950L, outcome = "BRIDGE_ERROR")) // later than any launch, and not one
                }
            }

            listOf(MANA to VISIBLE_LAUNCHED_READS, EARTH to HIDDEN_READS).forEach { (id, expected) ->
                reads(id, expected).forEach { (read, row) ->
                    assertEquals("$id via $read: first", 300L, row.firstPlayedMillis)
                    assertEquals("$id via $read: last", 900L, row.lastPlayedMillis)
                    assertEquals("$id via $read: plays", 3, row.playCount)
                }
            }
        }

    @Test
    fun `a launch recorded against a member of the group is the group's first played`() = runBlocking {
        db.gameDao().upsertAll(listOf(game(HEAD), game(MEMBER, primary = false, groupId = HEAD)))
        db.launchProofDao().apply {
            record(proofAt(MEMBER, 200L))
            record(proofAt(HEAD, 700L))
        }

        val row = requireNotNull(db.gameDao().observeGameRow(HEAD).first())

        assertEquals(200L, row.firstPlayedMillis)
        assertEquals(700L, row.lastPlayedMillis)
        assertEquals(2, row.playCount)
    }

    @Test
    fun `the favourites picker's read carries no launch history, first played included`() = runBlocking {
        db.gameDao().upsertAll(listOf(game(MANA)))
        db.launchProofDao().record(proofAt(MANA, 500L))

        val row = db.gameDao().favoriteCandidateGameRows().first().single()

        assertNull(row.firstPlayedMillis)
        assertNull(row.lastPlayedMillis)
        assertEquals(0, row.playCount)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    /** [ids] are games in the library under [root] (the folder they were scanned from). */
    private suspend fun libraryHas(ids: List<String>, root: String = ROOT) =
        db.gameDao().upsertAll(ids.map { game(it, root = root) })

    /** What removing a library folder does to `games`: Settings › Library › Remove runs this exact prune with no current keys. */
    private suspend fun removeFolder(root: String) = db.gameDao().pruneEntriesMissingFromSource(root, emptySet())

    private suspend fun bars(now: String, zone: ZoneId): List<DailyLaunchCount> =
        db.launchProofDao().observeLaunchesPerDay(clock = { millis(now) }, zone = { zone }).first()

    /**
     * The reads that carry a game's launch history, for [gameId]: read name to the row it returned. [expected] is the
     * exact set of reads that must show this game (a hidden game is in two of them, a visible one in more), so a read that
     * stops returning the row fails here and does not quietly drop out of the loop.
     */
    private suspend fun reads(gameId: String, expected: Set<String>): Map<String, GameRowProjection> {
        val dao = db.gameDao()
        val found = mapOf(
            PLATFORM_LIST to dao.visibleGameRowsForPlatform(SNES).first().firstOrNull { it.gameId == gameId },
            SINGLE to dao.observeGameRow(gameId).first(),
            BATCH to dao.visibleAvailableGameRowsByIds(listOf(gameId)).first().firstOrNull(),
            LAST_PLAYED to dao.recentlyPlayedGameRows(10).first().firstOrNull { it.gameId == gameId },
            FAVORITES to dao.favoriteGameRows().first().firstOrNull { it.gameId == gameId },
            HIDDEN to dao.hiddenGameRows().first().firstOrNull { it.gameId == gameId },
        )
        val present = found.mapNotNull { (name, row) -> row?.let { name to it } }.toMap()
        assertEquals("the reads that show $gameId", expected, present.keys)
        return present
    }

    private suspend fun hide(gameId: String) = db.hiddenItemDao().upsert(
        HiddenItemEntity(
            gameId = gameId,
            hidden = true,
            reason = "USER_DISMISSED",
            duplicateGroupId = null,
            isPrimaryMember = true,
            hiddenAtMillis = 5L,
        ),
    )

    private fun proof(gameId: String, at: String, outcome: String = "intent_sent") =
        proofAt(gameId, millis(at), outcome)

    private fun proofAt(gameId: String, timestampMillis: Long, outcome: String = "intent_sent") = LaunchProofEntity(
        gameId = gameId,
        androidPackageName = null,
        platformId = SNES,
        emulatorId = "retroarch",
        locator = "snes/$gameId.sfc",
        outcome = outcome,
        timestampMillis = timestampMillis,
    )

    /** A native Android-game proof, as `AndroidGameLaunchProof.toEntity` writes it: no gameId, an explicit package. */
    private fun androidProof(packageName: String, at: String, outcome: String = "intent_sent") = LaunchProofEntity(
        gameId = null,
        androidPackageName = packageName,
        platformId = "android-games",
        emulatorId = packageName,
        locator = "package:$packageName",
        outcome = outcome,
        timestampMillis = millis(at),
    )

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun game(
        gameId: String,
        primary: Boolean = true,
        groupId: String? = null,
        root: String = ROOT,
    ) = GameEntity(
        entryKey = gameId,
        gameId = gameId,
        platformId = SNES,
        contentKind = "ROM",
        displayName = gameId,
        title = gameId,
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "SAF_TREE_GRANT",
        availability = "AVAILABLE",
        sizeBytes = 0L,
        contentHash = null,
        sourceRootUri = root,
        sourceDocumentUri = null,
        managedPath = null,
        relativePath = gameId,
        discNumber = 0,
        duplicateGroupId = groupId,
        isPrimaryMember = primary,
        duplicateGroupKind = null,
    )

    private companion object {
        const val SNES = "snes"
        const val ROOT = "root"
        const val PROBE_ROOT = "probe-root"
        const val MANA = "snes:secret-of-mana"
        const val EARTH = "snes:earthbound"
        const val HEAD = "snes:chrono-trigger"
        const val MEMBER = "snes:chrono-trigger-rev1"

        const val PLATFORM_LIST = "the platform list"
        const val SINGLE = "the single-game read"
        const val BATCH = "the batch read"
        const val LAST_PLAYED = "Last Played"
        const val FAVORITES = "Favorites"
        const val HIDDEN = "Hidden games"
        val VISIBLE_LAUNCHED_READS = setOf(PLATFORM_LIST, SINGLE, BATCH, LAST_PLAYED, FAVORITES)
        val VISIBLE_NEVER_LAUNCHED_READS = setOf(PLATFORM_LIST, SINGLE, BATCH, FAVORITES)
        val HIDDEN_READS = setOf(SINGLE, HIDDEN)
    }
}
