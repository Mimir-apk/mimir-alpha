package com.gruning.mimir.rewrite.core.storage.dao

import com.gruning.mimir.rewrite.core.storage.entity.TitleMetadataCacheEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TitleMetadataCacheDao.freshByCatalogKey] is the one guarded chokepoint any freshness-sensitive read of
 * `title_metadata_cache` must use (2026-08-20) — see its kdoc, and [TitleMetadataCacheDao.byCatalogKey]'s,
 * for why the raw method must stay unguarded (write-merge callers carry an existing row's fields forward
 * regardless of expiry). This exercises the DEFAULT interface method exactly as any real Room-generated
 * implementation would run it: only [TitleMetadataCacheDao.byCatalogKey] is overridden below, so
 * `freshByCatalogKey` here is the production code path, not a reimplementation of it.
 */
class TitleMetadataCacheDaoFreshnessTest {

    @Test fun `a row with no expiry reads through unchanged`() = runBlocking {
        val dao = FakeDao(row(cacheExpiresAt = null))

        assertEquals("Sonic Adventure", dao.freshByCatalogKey(Key, nowMillis = 1_000L)?.canonicalTitle)
    }

    @Test fun `a row expiring in the future reads through unchanged`() = runBlocking {
        val dao = FakeDao(row(cacheExpiresAt = 2_000L))

        assertEquals("Sonic Adventure", dao.freshByCatalogKey(Key, nowMillis = 1_000L)?.canonicalTitle)
    }

    @Test fun `a row expired in the past reads as absent`() = runBlocking {
        val dao = FakeDao(row(cacheExpiresAt = 500L))

        assertNull(dao.freshByCatalogKey(Key, nowMillis = 1_000L))
    }

    @Test fun `an unknown catalog key reads as absent, same as byCatalogKey`() = runBlocking {
        val dao = FakeDao(row(cacheExpiresAt = null))

        assertNull(dao.freshByCatalogKey("game:nobody-here", nowMillis = 1_000L))
    }

    @Test fun `the injected clock -- not wall time -- decides freshness`() = runBlocking {
        val dao = FakeDao(row(cacheExpiresAt = 150L))

        assertEquals("Sonic Adventure", dao.freshByCatalogKey(Key, nowMillis = 100L)?.canonicalTitle)
        assertNull(dao.freshByCatalogKey(Key, nowMillis = 200L))
    }

    @Test fun `the production default parameter is a real clock -- 1970 is expired, year 2100 is not`() = runBlocking {
        val expiredUnderRealNow = FakeDao(row(cacheExpiresAt = 1L))
        val freshUnderRealNow = FakeDao(row(cacheExpiresAt = 4_102_444_800_000L)) // 2100-01-01 UTC

        assertNull(expiredUnderRealNow.freshByCatalogKey(Key))
        assertEquals("Sonic Adventure", freshUnderRealNow.freshByCatalogKey(Key)?.canonicalTitle)
    }

    @Test fun `byCatalogKey itself stays unguarded -- the raw row still comes back expired`() = runBlocking {
        val dao = FakeDao(row(cacheExpiresAt = 500L))

        assertEquals(500L, dao.byCatalogKey(Key)?.cacheExpiresAt)
    }

    private fun row(cacheExpiresAt: Long?) = TitleMetadataCacheEntity(
        catalogKey = Key,
        platformId = "dreamcast",
        canonicalTitle = "Sonic Adventure",
        region = "USA",
        version = null,
        cachedAtMillis = 1L,
        cacheExpiresAt = cacheExpiresAt,
    )

    private class FakeDao(private val stored: TitleMetadataCacheEntity?) : TitleMetadataCacheDao {
        override suspend fun upsertAll(entries: List<TitleMetadataCacheEntity>) {
            error("not exercised by this test")
        }

        override suspend fun byCatalogKey(catalogKey: String): TitleMetadataCacheEntity? =
            stored?.takeIf { it.catalogKey == catalogKey }
    }

    private companion object {
        const val Key = "game:g1"
    }
}
