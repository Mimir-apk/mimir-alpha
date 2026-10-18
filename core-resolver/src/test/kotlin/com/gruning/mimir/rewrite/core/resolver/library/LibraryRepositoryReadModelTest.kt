package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.library.DailyLaunchCount
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import com.gruning.mimir.rewrite.core.storage.dao.FavoriteDao
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameMemberProjection
import com.gruning.mimir.rewrite.core.storage.dao.GameRowProjection
import com.gruning.mimir.rewrite.core.storage.dao.HiddenItemDao
import com.gruning.mimir.rewrite.core.storage.dao.LaunchProofDao
import com.gruning.mimir.rewrite.core.storage.dao.PlatformDao
import com.gruning.mimir.rewrite.core.storage.dao.SourceRootDao
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryReadModelTest {
    private val gameId = GameId("ps1/chrono-cross")
    private val projection = GameRowProjection(
        gameId = gameId.value,
        title = "Chrono Cross (USA) (Rev 1)",
        platformId = PlatformId.PS1.value,
        duplicateGroupKind = "MULTI_DISC",
        memberCount = 2,
        isFavorite = 1,
        hasOverride = 1,
        lastPlayedMillis = 4_000L,
        firstPlayedMillis = 1_000L,
        playCount = 3,
        region = "USA",
        version = "Rev 1",
        sizeBytes = 734_003_200L,
    )

    @Test
    fun `game rows preserve offline detail metadata and exact launch count`() = runBlocking {
        val repository = repository(
            visibleRows = listOf(projection),
            singleRow = projection,
        )

        val platformRow = repository.observeGames(PlatformId.PS1).first().single()
        val singleRow = repository.observeGame(gameId).first()

        listOf(platformRow, requireNotNull(singleRow)).forEach { row ->
            assertEquals("USA", row.region)
            assertEquals("Rev 1", row.version)
            assertEquals(734_003_200L, row.sizeBytes)
            assertEquals(3, row.playCount)
            assertEquals(4_000L, row.lastPlayedMillis)
            assertEquals(1_000L, row.firstPlayedMillis)
        }
    }

    @Test
    fun `a game never launched maps to a null first played, not zero`() = runBlocking {
        val neverLaunched = projection.copy(lastPlayedMillis = null, firstPlayedMillis = null, playCount = 0)

        val row = repository(visibleRows = listOf(neverLaunched)).observeGames(PlatformId.PS1).first().single()

        assertNull(row.firstPlayedMillis)
        assertNull(row.lastPlayedMillis)
    }

    @Test
    fun `launches per day reads its clock from the repository and its rows from the launch proofs`() = runBlocking {
        val now = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()
        val threeDaysBefore = Instant.parse("2026-09-29T12:00:00Z").toEpochMilli()
        val proofs = proxy<LaunchProofDao> { methodName ->
            when (methodName) {
                "intentSentTimestampsSince" -> flowOf(listOf(now, now, threeDaysBefore))
                else -> error("Unexpected LaunchProofDao call: $methodName")
            }
        }

        val week = inUtc { repository(launchProofDao = proofs, clock = { now }).observeLaunchesPerDay().first() }

        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 9, 26), week.first().date)
        assertEquals(DailyLaunchCount(LocalDate.of(2026, 9, 29), 1), week[3])
        assertEquals(DailyLaunchCount(LocalDate.of(2026, 10, 2), 2), week[6])
        assertEquals(3, week.sumOf { it.count })
    }

    @Test
    fun `with no launch history behind it, launches per day is a quiet week of seven days`() = runBlocking {
        val now = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()

        val week = inUtc { repository(clock = { now }).observeLaunchesPerDay().first() }

        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 10, 2), week.last().date)
        assertEquals(List(7) { 0 }, week.map { it.count })
    }

    @Test
    fun `single game emits null when the group no longer exists`() = runBlocking {
        assertNull(repository(singleRow = null).observeGame(gameId).first())
    }

    @Test
    fun `typed ID batch maps the visible available DAO projection`() = runBlocking {
        val row = repository(batchRows = listOf(projection))
            .observeGames(setOf(gameId))
            .first()
            .single()

        assertEquals(gameId, row.gameId)
        assertEquals("USA", row.region)
        assertEquals(3, row.playCount)
    }

    @Test
    fun `members map without losing disc identity display name or size`() = runBlocking {
        val expected = listOf(
            GameMemberRow(EntryKey("disc-1"), 1, "Chrono Cross (Disc 1).chd", 734_003_200L),
            GameMemberRow(EntryKey("disc-2"), 2, "Chrono Cross (Disc 2).chd", 728_760_320L),
        )
        val members = expected.map {
            GameMemberProjection(it.entryKey.value, it.discNumber, it.displayName, it.sizeBytes)
        }

        assertEquals(expected, repository(members = members).observeGameMembers(gameId).first())
    }

    @Test
    fun `migration Favorite promotion delegates exact identity and timestamp without normal replace`() = runBlocking {
        var promoted: Pair<String, Long>? = null
        var normalReplaceCalled = false
        val favoriteDao = object : FavoriteDao {
            override suspend fun set(favorite: FavoriteEntity) {
                normalReplaceCalled = true
            }

            override suspend fun promoteGroupIfAbsent(gameId: String, favoritedAtMillis: Long): Long {
                promoted = gameId to favoritedAtMillis
                return 1L
            }

            override suspend fun clear(gameId: String) = Unit
            override suspend fun isFavorite(gameId: String): Boolean = false
        }

        val inserted = repository(favoriteDao = favoriteDao)
            .promoteFavoriteIfAbsent(gameId, favoritedAtMillis = -7L)

        assertTrue(inserted)
        assertEquals(gameId.value to -7L, promoted)
        assertFalse(normalReplaceCalled)
    }

    @Test
    fun `migration Favorite promotion distinguishes existing group truth from a vanished game`() = runBlocking {
        var favoriteExists = true
        val favoriteDao = object : FavoriteDao {
            override suspend fun set(favorite: FavoriteEntity) = Unit
            override suspend fun promoteGroupIfAbsent(gameId: String, favoritedAtMillis: Long): Long = 0L
            override suspend fun clear(gameId: String) = Unit
            override suspend fun isFavorite(gameId: String): Boolean = favoriteExists
        }
        val repository = repository(favoriteDao = favoriteDao)

        assertTrue(repository.promoteFavoriteIfAbsent(gameId, -1L))
        favoriteExists = false
        assertFalse(repository.promoteFavoriteIfAbsent(gameId, -1L))
    }

    @Test
    fun `provider title cannot erase the disc marker from a lone physical disc`() {
        assertEquals(
            "Resident Evil 2 (Disc 2)",
            libraryDisplayTitle(
                scanTitle = "Resident Evil 2 (USA) (Disc 2)",
                providerCanonicalTitle = "Resident Evil 2",
                duplicateKind = null,
                memberCount = 1,
            ),
        )
    }

    @Test
    fun `provider title still refines ordinary rows and folded multi disc remains neutral`() {
        assertEquals(
            "The Legend of Zelda: The Minish Cap",
            libraryDisplayTitle(
                scanTitle = "Legend of Zelda, The - The Minish Cap (USA)",
                providerCanonicalTitle = "The Legend of Zelda: The Minish Cap",
                duplicateKind = null,
                memberCount = 1,
            ),
        )
        assertEquals(
            "Chrono Cross",
            libraryDisplayTitle(
                scanTitle = "Chrono Cross (USA) (Disc 1)",
                providerCanonicalTitle = "Chrono Cross (Disc 1)",
                duplicateKind = DuplicateGroupKind.MULTI_DISC,
                memberCount = 2,
            ),
        )
    }

    @Test
    fun `a projected manual title override reaches the mapped row's title and sets hasTitleOverride`() = runBlocking {
        val renamed = projection.copy(manualTitleOverride = "Player Chosen Title")
        val repository = repository(singleRow = renamed)

        val row = requireNotNull(repository.observeGame(gameId).first())

        assertEquals("Player Chosen Title", row.title)
        assertTrue(row.hasTitleOverride)
    }

    @Test
    fun `no manual title override leaves title on scan-provider precedence and hasTitleOverride false`() = runBlocking {
        val repository = repository(singleRow = projection)

        val row = requireNotNull(repository.observeGame(gameId).first())

        assertFalse(row.hasTitleOverride)
    }

    @Test
    fun `a manual title override outranks both provider and scan titles`() {
        assertEquals(
            "My Corrected Title",
            libraryDisplayTitle(
                scanTitle = "some smashed filename",
                providerCanonicalTitle = "Provider Says Something Else",
                duplicateKind = null,
                memberCount = 1,
                manualTitleOverride = "My Corrected Title",
            ),
        )
    }

    @Test
    fun `a manual title override is shown verbatim, not tidied by clutter or article rules`() {
        // DisplayTitle.clean would strip a leading numeric index / region-like parenthetical and could
        // move a leading article — none of that is appropriate for text the player deliberately typed.
        assertEquals(
            "007: The World Is Not Enough (Director's Cut)",
            libraryDisplayTitle(
                scanTitle = "ignored",
                providerCanonicalTitle = "ignored",
                duplicateKind = null,
                memberCount = 1,
                manualTitleOverride = "  007: The World Is Not Enough (Director's Cut)  ",
            ),
        )
    }

    @Test
    fun `a blank manual title override does not suppress the scan or provider title`() {
        assertEquals(
            "The Legend of Zelda: The Minish Cap",
            libraryDisplayTitle(
                scanTitle = "Legend of Zelda, The - The Minish Cap (USA)",
                providerCanonicalTitle = "The Legend of Zelda: The Minish Cap",
                duplicateKind = null,
                memberCount = 1,
                manualTitleOverride = "   ",
            ),
        )
    }

    @Test
    fun `a manual title override still wins on a folded multi disc group`() {
        assertEquals(
            "Player's Name For This Game",
            libraryDisplayTitle(
                scanTitle = "Chrono Cross (USA) (Disc 1)",
                providerCanonicalTitle = "Chrono Cross (Disc 1)",
                duplicateKind = DuplicateGroupKind.MULTI_DISC,
                memberCount = 2,
                manualTitleOverride = "Player's Name For This Game",
            ),
        )
    }

    private fun repository(
        visibleRows: List<GameRowProjection> = emptyList(),
        batchRows: List<GameRowProjection> = emptyList(),
        singleRow: GameRowProjection? = null,
        members: List<GameMemberProjection> = emptyList(),
        favoriteDao: FavoriteDao = unusedProxy(),
        launchProofDao: LaunchProofDao? = null,
        clock: () -> Long = System::currentTimeMillis,
    ): LibraryRepositoryImpl {
        val gameDao = proxy<GameDao> { methodName ->
            when (methodName) {
                "visibleGameRowsForPlatform" -> flowOf(visibleRows)
                "visibleAvailableGameRowsByIds" -> flowOf(batchRows)
                "observeGameRow" -> flowOf(singleRow)
                "observeGameMembers" -> flowOf(members)
                else -> error("Unexpected GameDao call: $methodName")
            }
        }
        return LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = unusedProxy(),
            sourceRootDao = unusedProxy(),
            hiddenItemDao = unusedProxy(),
            favoriteDao = favoriteDao,
            walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult =
                    SourceWalkResult.Complete(emptyList())
            },
            runScanTransaction = { block -> block() },
            ioDispatcher = Dispatchers.Unconfined,
            clock = clock,
            launchProofDao = launchProofDao,
        )
    }

    /** The repository cuts days in the device's default zone; pin it to UTC for the call, and put it back. */
    private inline fun <T> inUtc(block: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            return block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private inline fun <reified T> unusedProxy(): T = proxy { methodName ->
        error("Unexpected ${T::class.java.simpleName} call: $methodName")
    }

    private inline fun <reified T> proxy(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method.name)
        } as T
}
