package com.gruning.mimir.rewrite.core.resolver.home

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.ProfileId
import com.gruning.mimir.rewrite.core.model.home.AddToShelfResult
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfEntry
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.storage.dao.HomeShelfDao
import com.gruning.mimir.rewrite.core.storage.entity.HomeShelfItemEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShelfRepositoryImplTest {
    @Test
    fun `projection omits missing emulated pins and restores them at the saved rank`() = runBlocking {
        val dao = InMemoryHomeShelfDao().apply {
            seed(entity("EMULATED_GAME", "missing", rank = 1_024L, addedAt = 10L))
            seed(entity("ANDROID_GAME", "com.example.game", rank = 2_048L, addedAt = 20L))
            seed(entity("EMULATED_GAME", "visible", rank = 3_072L, addedAt = 30L))
        }
        val visibleGames = MutableStateFlow(listOf(game("visible")))
        val repository = repository(dao, visibleGames)

        val beforeRepair = repository.observe().first()
        assertEquals(
            listOf(HomeShelfItemId.AndroidGame::class, HomeShelfItemId.Emulated::class),
            beforeRepair.map { it.itemId::class },
        )
        assertEquals(listOf(2_048L, 3_072L), beforeRepair.map { it.rank })
        assertNotNull(dao.item("local", "EMULATED_GAME", "missing"))

        visibleGames.value = listOf(game("visible"), game("missing"))
        val afterRepair = repository.observe().first()
        assertEquals(listOf("missing", "com.example.game", "visible"), afterRepair.map(::rawId))
        assertEquals(listOf(1_024L, 2_048L, 3_072L), afterRepair.map { it.rank })
        assertEquals(10L, afterRepair.first().addedAtMillis)
    }

    @Test
    fun `durable membership truth retains an unavailable emulated identity`() = runBlocking {
        val dao = InMemoryHomeShelfDao().apply {
            seed(entity("EMULATED_GAME", "temporarily-missing", rank = 1_024L, addedAt = 10L))
        }
        val repository = repository(dao, MutableStateFlow(emptyList()))

        assertTrue(repository.observe().first().isEmpty())
        assertEquals(
            listOf(HomeShelfItemId.Emulated(GameId("temporarily-missing"))),
            repository.observeMemberships().first().map { it.itemId },
        )
    }

    @Test
    fun `unsupported persisted kinds fail closed`() = runBlocking {
        // ANDROID_APP used to be this test's stand-in for "unknown kind". It became a real namespace
        // in the domain model, so the fail-closed contract now needs a genuinely unknown
        // one — a row written by some future build that this build must ignore without discarding
        // its neighbours.
        val dao = InMemoryHomeShelfDao().apply {
            seed(entity("SOME_FUTURE_KIND", "com.example.future", rank = 1_024L, addedAt = 10L))
            seed(entity("ANDROID_GAME", "com.example.game", rank = 2_048L, addedAt = 20L))
        }

        val rows = repository(dao, MutableStateFlow(emptyList())).observe().first()

        assertEquals(listOf("com.example.game"), rows.map(::rawId))
        // Fail closed means "don't project", never "delete": the unreadable row stays on disk.
        assertNotNull(dao.item("local", "SOME_FUTURE_KIND", "com.example.future"))
    }

    @Test
    fun `android app rows project as a typed AndroidApp identity`() = runBlocking {
        val dao = InMemoryHomeShelfDao().apply {
            seed(entity("ANDROID_APP", "com.example.app", rank = 1_024L, addedAt = 10L))
            seed(entity("ANDROID_GAME", "com.example.game", rank = 2_048L, addedAt = 20L))
        }

        val rows = repository(dao, MutableStateFlow(emptyList())).observe().first()

        assertEquals(
            listOf(HomeShelfItemId.AndroidApp::class, HomeShelfItemId.AndroidGame::class),
            rows.map { it.itemId::class },
        )
        assertEquals(listOf("com.example.app", "com.example.game"), rows.map(::rawId))
    }

    @Test
    fun `the same package shelved as app and game stays two distinct memberships`() = runBlocking {
        val dao = InMemoryHomeShelfDao().apply {
            seed(entity("ANDROID_APP", "com.example.both", rank = 1_024L, addedAt = 10L))
            seed(entity("ANDROID_GAME", "com.example.both", rank = 2_048L, addedAt = 20L))
        }

        val rows = repository(dao, MutableStateFlow(emptyList())).observe().first()

        // Same raw text, two kinds, two rows — the composite primary key keeps them apart.
        assertEquals(
            listOf(HomeShelfItemId.AndroidApp::class, HomeShelfItemId.AndroidGame::class),
            rows.map { it.itemId::class },
        )
        assertEquals(2, rows.map { it.itemId }.toSet().size)
    }

    @Test
    fun `adding an android app fails closed until the kind CHECK constraint is widened`() = runBlocking {
        // `home_shelf_items` was created with CHECK(itemKind IN ('EMULATED_GAME','ANDROID_GAME')),
        // which Room's exported schema does not model. Rather than let SQLite reject the insert as an
        // opaque constraint error, the repository refuses up front and names the migration it needs.
        val repository = repository(InMemoryHomeShelfDao(), MutableStateFlow(emptyList()))

        val failure = runCatching {
            repository.add(ProfileId.LOCAL, HomeShelfItemId.AndroidApp(AndroidPackageName("com.example.app")))
        }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertTrue(failure!!.message!!.contains("CHECK(itemKind"))
    }

    @Test
    fun `typed mutations are idempotent profile scoped reorderable and removable`() = runBlocking {
        val dao = InMemoryHomeShelfDao()
        var now = 100L
        val repository = repository(dao, MutableStateFlow(emptyList()), clock = { now++ })
        val emulated = HomeShelfItemId.Emulated(GameId("same-id"))
        val android = HomeShelfItemId.AndroidGame(AndroidPackageName("same-id"))
        val guest = ProfileId("guest")

        val first = repository.add(ProfileId.LOCAL, emulated)
        val duplicate = repository.add(ProfileId.LOCAL, emulated)
        val otherKind = repository.add(ProfileId.LOCAL, android)
        val otherProfile = repository.add(guest, emulated)

        assertTrue(first is AddToShelfResult.Added)
        assertTrue(duplicate is AddToShelfResult.AlreadyPresent)
        assertEquals(first.membership, duplicate.membership)
        assertEquals(100L, duplicate.membership.addedAtMillis)
        assertEquals(2_048L, otherKind.membership.rank)
        assertEquals(1_024L, otherProfile.membership.rank)

        assertTrue(repository.move(ProfileId.LOCAL, android, before = emulated))
        assertEquals(
            listOf("ANDROID_GAME" to "same-id", "EMULATED_GAME" to "same-id"),
            dao.orderedItems("local").map { it.itemKind to it.itemId },
        )
        assertFalse(repository.move(ProfileId.LOCAL, android, before = emulated))
        assertTrue(repository.remove(ProfileId.LOCAL, emulated))
        assertFalse(repository.remove(ProfileId.LOCAL, emulated))
        assertNotNull(dao.item("guest", "EMULATED_GAME", "same-id"))
    }

    private fun repository(
        dao: HomeShelfDao,
        games: Flow<List<GameRow>>,
        clock: () -> Long = { 1L },
    ) = HomeShelfRepositoryImpl(
        homeShelfDao = dao,
        libraryRepository = libraryRepository(games),
        ioDispatcher = Dispatchers.Unconfined,
        clock = clock,
    )

    private fun libraryRepository(games: Flow<List<GameRow>>): LibraryRepository =
        Proxy.newProxyInstance(
            LibraryRepository::class.java.classLoader,
            arrayOf(LibraryRepository::class.java),
        ) { _, method, arguments ->
            if (method.name == "observeGames" && arguments?.singleOrNull() is Set<*>) {
                games
            } else {
                error("Unexpected LibraryRepository call: ${method.name}")
            }
        } as LibraryRepository

    private fun entity(kind: String, id: String, rank: Long, addedAt: Long) = HomeShelfItemEntity(
        profileId = "local",
        itemKind = kind,
        itemId = id,
        rank = rank,
        addedAtMillis = addedAt,
    )

    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = PlatformId.PS1,
        memberCount = 1,
        duplicateKind = null,
    )

    private fun rawId(entry: HomeShelfEntry): String = when (val itemId = entry.itemId) {
        is HomeShelfItemId.Emulated -> itemId.gameId.value
        is HomeShelfItemId.AndroidGame -> itemId.packageName.value
        is HomeShelfItemId.AndroidApp -> itemId.packageName.value
    }

    private class InMemoryHomeShelfDao : HomeShelfDao() {
        private val rows = linkedMapOf<Key, HomeShelfItemEntity>()
        private val revision = MutableStateFlow(0L)
        private var rowId = 0L

        fun seed(item: HomeShelfItemEntity) {
            rows[Key(item.profileId, item.itemKind, item.itemId)] = item
            revision.value += 1L
        }

        override fun observeItems(profileId: String): Flow<List<HomeShelfItemEntity>> =
            revision.map { orderedItems(profileId) }

        override suspend fun orderedItems(profileId: String): List<HomeShelfItemEntity> = rows.values
            .filter { it.profileId == profileId }
            .sortedWith(
                compareBy<HomeShelfItemEntity> { it.rank }
                    .thenBy { it.addedAtMillis }
                    .thenBy { it.itemKind }
                    .thenBy { it.itemId },
            )

        override suspend fun item(
            profileId: String,
            itemKind: String,
            itemId: String,
        ): HomeShelfItemEntity? = rows[Key(profileId, itemKind, itemId)]

        override suspend fun maxRank(profileId: String): Long? = rows.values
            .filter { it.profileId == profileId }
            .maxOfOrNull { it.rank }

        override suspend fun insertItem(item: HomeShelfItemEntity): Long {
            val key = Key(item.profileId, item.itemKind, item.itemId)
            if (key in rows) return -1L
            rows[key] = item
            revision.value += 1L
            return ++rowId
        }

        override suspend fun deleteItem(profileId: String, itemKind: String, itemId: String): Int {
            val removed = rows.remove(Key(profileId, itemKind, itemId)) ?: return 0
            revision.value += 1L
            return if (removed.itemId == itemId) 1 else 0
        }

        override suspend fun updateRank(
            profileId: String,
            itemKind: String,
            itemId: String,
            rank: Long,
        ): Int {
            val key = Key(profileId, itemKind, itemId)
            val current = rows[key] ?: return 0
            rows[key] = current.copy(rank = rank)
            revision.value += 1L
            return 1
        }

        private data class Key(val profileId: String, val itemKind: String, val itemId: String)
    }
}
