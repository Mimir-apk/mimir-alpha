package com.gruning.mimir.rewrite.core.storage.dao

import com.gruning.mimir.rewrite.core.storage.entity.HomeShelfItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShelfDaoTest {
    @Test
    fun `add is idempotent and composite identity is profile and kind scoped`() = runBlocking {
        val dao = InMemoryHomeShelfDao()

        val first = dao.addIfAbsent("local", HOME_SHELF_EMULATED_GAME_KIND, "same-id", 10L)
        val duplicate = dao.addIfAbsent("local", HOME_SHELF_EMULATED_GAME_KIND, "same-id", 99L)
        val otherKind = dao.addIfAbsent("local", HOME_SHELF_ANDROID_GAME_KIND, "same-id", 20L)
        val otherProfile = dao.addIfAbsent("guest", HOME_SHELF_EMULATED_GAME_KIND, "same-id", 30L)

        assertTrue(first.inserted)
        assertFalse(duplicate.inserted)
        assertEquals(first.item, duplicate.item)
        assertEquals(10L, duplicate.item.addedAtMillis)
        assertEquals(1_024L, first.item.rank)
        assertEquals(2_048L, otherKind.item.rank)
        assertEquals(1_024L, otherProfile.item.rank)
        assertEquals(2, dao.orderedItems("local").size)
        assertEquals(1, dao.orderedItems("guest").size)
    }

    @Test
    fun `move preserves addedAt and unrelated sparse ranks`() = runBlocking {
        val dao = InMemoryHomeShelfDao()
        dao.addIfAbsent("local", HOME_SHELF_EMULATED_GAME_KIND, "a", 10L)
        dao.addIfAbsent("local", HOME_SHELF_EMULATED_GAME_KIND, "b", 20L)
        dao.addIfAbsent("local", HOME_SHELF_ANDROID_GAME_KIND, "c", 30L)

        assertTrue(
            dao.moveItem(
                "local",
                HOME_SHELF_ANDROID_GAME_KIND,
                "c",
                HOME_SHELF_EMULATED_GAME_KIND,
                "a",
            ),
        )
        val moved = dao.orderedItems("local")
        assertEquals(listOf("c", "a", "b"), moved.map { it.itemId })
        assertEquals(listOf(0L, 1_024L, 2_048L), moved.map { it.rank })
        assertEquals(30L, moved.first().addedAtMillis)
        assertFalse(
            dao.moveItem(
                "local",
                HOME_SHELF_ANDROID_GAME_KIND,
                "c",
                HOME_SHELF_EMULATED_GAME_KIND,
                "a",
            ),
        )

        assertTrue(dao.moveItem("local", HOME_SHELF_ANDROID_GAME_KIND, "c", null, null))
        assertEquals(listOf("a", "b", "c"), dao.orderedItems("local").map { it.itemId })
        assertEquals(30L, dao.item("local", HOME_SHELF_ANDROID_GAME_KIND, "c")?.addedAtMillis)
        assertFalse(dao.moveItem("local", HOME_SHELF_EMULATED_GAME_KIND, "missing", null, null))
        assertFalse(
            dao.moveItem(
                "local",
                HOME_SHELF_EMULATED_GAME_KIND,
                "a",
                HOME_SHELF_EMULATED_GAME_KIND,
                "missing",
            ),
        )
    }

    @Test
    fun `move rebalances transactionally when adjacent ranks have no gap`() = runBlocking {
        val dao = InMemoryHomeShelfDao()
        dao.addIfAbsent("local", HOME_SHELF_EMULATED_GAME_KIND, "a", 10L)
        dao.addIfAbsent("local", HOME_SHELF_EMULATED_GAME_KIND, "b", 20L)
        dao.addIfAbsent("local", HOME_SHELF_EMULATED_GAME_KIND, "c", 30L)
        dao.updateRank("local", HOME_SHELF_EMULATED_GAME_KIND, "a", 0L)
        dao.updateRank("local", HOME_SHELF_EMULATED_GAME_KIND, "b", 1L)
        dao.updateRank("local", HOME_SHELF_EMULATED_GAME_KIND, "c", 2L)

        assertTrue(
            dao.moveItem(
                "local",
                HOME_SHELF_EMULATED_GAME_KIND,
                "c",
                HOME_SHELF_EMULATED_GAME_KIND,
                "b",
            ),
        )
        val rows = dao.orderedItems("local")
        assertEquals(listOf("a", "c", "b"), rows.map { it.itemId })
        assertEquals(listOf(1_024L, 2_048L, 3_072L), rows.map { it.rank })
        assertEquals(listOf(10L, 30L, 20L), rows.map { it.addedAtMillis })
    }

    @Test
    fun `rank allocator handles edges midpoint and exhausted ranges`() {
        assertEquals(1_024L, homeShelfRankBetween(null, null))
        assertEquals(0L, homeShelfRankBetween(null, 1_024L))
        assertEquals(2_048L, homeShelfRankBetween(1_024L, null))
        assertEquals(2_048L, homeShelfRankBetween(1_024L, 3_072L))
        assertEquals(0L, homeShelfRankBetween(-1L, 1L))
        assertNull(homeShelfRankBetween(1L, 2L))
        assertNull(homeShelfRankBetween(Long.MAX_VALUE, null))
        assertNull(homeShelfRankBetween(null, Long.MIN_VALUE))
    }

    private class InMemoryHomeShelfDao : HomeShelfDao() {
        private val rows = linkedMapOf<Key, HomeShelfItemEntity>()
        private var rowId = 0L

        override fun observeItems(profileId: String): Flow<List<HomeShelfItemEntity>> =
            flow { emit(orderedItems(profileId)) }

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
            return ++rowId
        }

        override suspend fun deleteItem(profileId: String, itemKind: String, itemId: String): Int =
            if (rows.remove(Key(profileId, itemKind, itemId)) != null) 1 else 0

        override suspend fun updateRank(
            profileId: String,
            itemKind: String,
            itemId: String,
            rank: Long,
        ): Int {
            val key = Key(profileId, itemKind, itemId)
            val current = rows[key] ?: return 0
            rows[key] = current.copy(rank = rank)
            return 1
        }

        private data class Key(val profileId: String, val itemKind: String, val itemId: String)
    }
}
