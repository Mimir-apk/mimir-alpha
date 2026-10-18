package com.gruning.mimir.rewrite.core.storage.dao

import androidx.room.Room
import androidx.room.withTransaction
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.LaunchProofEntity
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A hide leaves the favourite where it is (a game restored from Hidden games comes back as it was,
 * star included). That is only safe because every read that shows or counts games filters hidden groups out, so a hidden
 * favourite is in no list and no count, while its star waits in `favorites` for the restore.
 *
 * Run against a real in-memory Room database and the DAO code Room generates, in the shape of [RekeyRoomStateHarnessTest],
 * because the favourites read's SQL lives in its `@Query` annotation and no constant-SQL test can reach it. The hide and
 * the restore are the writes `LibraryRepositoryImpl.setHidden` makes: the hidden row alone, in one transaction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class HiddenFavoriteReadModelTest {

    private lateinit var db: MimirDatabase

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MimirDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun `a hidden favourite keeps its star and is in no list and no count`() = runBlocking {
        seed()

        hide(MANA)

        val gameDao = db.gameDao()
        assertTrue("the star is still there", db.favoriteDao().isFavorite(MANA))
        assertEquals("Favorites", listOf(EARTH), gameDao.favoriteGameRows().first().ids())
        assertEquals("the platform's gamelist", listOf(EARTH), gameDao.visibleGameRowsForPlatform(SNES).first().ids())
        assertEquals("onboarding's favourites picker", listOf(EARTH), gameDao.favoriteCandidateGameRows().first().ids())
        assertEquals(
            "Home's batch read",
            listOf(EARTH),
            gameDao.visibleAvailableGameRowsByIds(listOf(MANA, EARTH)).first().ids(),
        )
        assertEquals("Last Played", emptyList<String>(), gameDao.recentlyPlayedGameRows(10).first().ids())
        assertEquals("the library's game count", 1, gameDao.observeVisibleGameCount().first())
        assertEquals(listOf(PlatformCountProjection(SNES, 1)), gameDao.observePlatformCounts().first())
        assertEquals("the source's game count", 1, gameDao.visibleGameCountForSource(ROOT))

        val hidden = gameDao.hiddenGameRows().first()
        assertEquals("the Hidden games list holds it", listOf(MANA), hidden.ids())
        assertEquals("starred, ready for the restore", 1, hidden.single().isFavorite)
        // The one favourite read with no hidden filter: it only marks rows the candidate read above returned, and it does
        // not name hidden_items, so a hide never re-runs it. Pinned so a filter added here is a decision, not a drift.
        assertTrue(MANA in gameDao.favoriteGroupIds().first())
    }

    @Test
    fun `restoring a hidden favourite brings it back starred, in its old place in Favorites`() = runBlocking {
        seed()
        hide(MANA)

        restore(MANA)

        val gameDao = db.gameDao()
        val favorites = gameDao.favoriteGameRows().first()
        assertEquals("starred before EarthBound, so second again, where it was", listOf(EARTH, MANA), favorites.ids())
        assertEquals(1, favorites.single { it.gameId == MANA }.isFavorite)
        assertEquals(1, gameDao.visibleGameRowsForPlatform(SNES).first().single { it.gameId == MANA }.isFavorite)
        assertEquals(listOf(MANA), gameDao.recentlyPlayedGameRows(10).first().ids())
        assertEquals(2, gameDao.observeVisibleGameCount().first())
        assertEquals(emptyList<String>(), gameDao.hiddenGameRows().first().ids())
    }

    @Test
    fun `the first emission after the hide already lacks the game, and none shows it without its star`() = runBlocking {
        seed()
        val favorites = CopyOnWriteArrayList<List<GameRowProjection>>()
        val gamelist = CopyOnWriteArrayList<List<GameRowProjection>>()
        val jobs = listOf(
            collectInto(db.gameDao().favoriteGameRows(), favorites),
            collectInto(db.gameDao().visibleGameRowsForPlatform(SNES), gamelist),
        )
        waitUntil { favorites.isNotEmpty() && gamelist.isNotEmpty() }

        hide(MANA)
        waitUntil { favorites.size >= 2 && gamelist.size >= 2 }
        jobs.forEach { it.cancelAndJoin() }

        listOf("Favorites" to favorites, "the platform's gamelist" to gamelist).forEach { (list, emissions) ->
            val before = emissions.first()
            assertEquals("$list starts with the game starred", 1, before.single { it.gameId == MANA }.isFavorite)
            assertTrue("$list: the first emission after the one write lacks it", emissions[1].none { it.gameId == MANA })
            assertTrue("$list never brings it back", emissions.drop(1).all { rows -> rows.none { it.gameId == MANA } })
            val starStates = emissions.flatten().filter { it.gameId == MANA }.map { it.isFavorite }
            assertTrue("$list never shows it without its star: $starStates", starStates.all { it == 1 })
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    /** Two starred SNES games: Mana starred first (so second in Favorites) and the only one ever played. */
    private suspend fun seed() {
        db.gameDao().upsertAll(listOf(game(MANA), game(EARTH)))
        db.favoriteDao().set(FavoriteEntity(gameId = MANA, favoritedAtMillis = 50L))
        db.favoriteDao().set(FavoriteEntity(gameId = EARTH, favoritedAtMillis = 100L))
        db.launchProofDao().record(
            LaunchProofEntity(
                gameId = MANA,
                androidPackageName = null,
                platformId = SNES,
                emulatorId = "retroarch",
                locator = "snes/mana.sfc",
                outcome = "intent_sent",
                timestampMillis = 10L,
            ),
        )
    }

    /** The hide `LibraryRepositoryImpl.setHidden` makes (its `hideGroup` row), and nothing else, in one transaction. */
    private suspend fun hide(gameId: String) = db.withTransaction {
        db.hiddenItemDao().upsert(
            HiddenItemEntity(
                gameId = gameId,
                hidden = true,
                reason = "USER_DISMISSED",
                duplicateGroupId = null,
                isPrimaryMember = true,
                hiddenAtMillis = 5L,
            ),
        )
    }

    /** The restore `LibraryRepositoryImpl.setHidden` makes: the hidden row cleared, in one transaction. */
    private suspend fun restore(gameId: String) = db.withTransaction { db.hiddenItemDao().clear(gameId) }

    private fun CoroutineScope.collectInto(
        flow: Flow<List<GameRowProjection>>,
        into: MutableList<List<GameRowProjection>>,
    ): Job = launch(Dispatchers.IO) { flow.collect { into += it } }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private fun List<GameRowProjection>.ids(): List<String> = map(GameRowProjection::gameId)

    private fun game(gameId: String) = GameEntity(
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
        sourceRootUri = ROOT,
        sourceDocumentUri = null,
        managedPath = null,
        relativePath = gameId,
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    private companion object {
        const val SNES = "snes"
        const val ROOT = "root"
        const val MANA = "snes:secret-of-mana"
        const val EARTH = "snes:earthbound"
    }
}
