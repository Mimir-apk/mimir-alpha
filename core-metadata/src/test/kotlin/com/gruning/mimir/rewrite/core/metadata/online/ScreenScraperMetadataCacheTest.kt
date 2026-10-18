package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.MetadataOrigin
import com.gruning.mimir.rewrite.core.metadata.MetadataRecord
import com.gruning.mimir.rewrite.core.storage.dao.TitleMetadataCacheDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.TitleMetadataCacheEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenScraperMetadataCacheTest {

    @Test fun `verified synopsis is cached under stable game identity without remote media urls`() = runBlocking {
        val dao = FakeDao()
        val game = game(titleCatalogKey = "catalog:sonic")
        val record = MetadataRecord(
            canonicalTitle = "Sonic Adventure",
            origin = MetadataOrigin.SCREENSCRAPER,
            description = "  A fast\n  adventure. ",
            boxArtUri = "https://www.screenscraper.fr/credentialed.png",
        )

        assertTrue(ScreenScraperMetadataCache.persistVerifiedRecord(dao, game, record, nowMillis = 42L))

        val cached = dao.entries.single()
        assertEquals("game:game-1", cached.catalogKey)
        assertEquals("A fast adventure.", cached.description)
        assertEquals(ScreenScraperMetadataCache.ENGLISH_SOURCE, cached.source)
        assertNull(cached.boxArtUri)
        assertEquals(42L, cached.lastValidatedMillis)
    }

    @Test fun `verified provider miss is distinguishable from never looked up`() = runBlocking {
        val dao = FakeDao()

        ScreenScraperMetadataCache.persistVerifiedRecord(
            dao = dao,
            game = game(titleCatalogKey = null),
            record = MetadataRecord("Unknown", MetadataOrigin.SCREENSCRAPER),
            nowMillis = 7L,
        )

        assertEquals("game:game-1", dao.entries.single().catalogKey)
        assertEquals(ScreenScraperMetadataCache.ENGLISH_SOURCE, dao.entries.single().source)
        assertNull(dao.entries.single().description)
    }

    @Test fun `later media-only response preserves an existing verified synopsis`() = runBlocking {
        val dao = FakeDao()
        val game = game(titleCatalogKey = null)
        ScreenScraperMetadataCache.persistVerifiedRecord(
            dao = dao,
            game = game,
            record = MetadataRecord(
                canonicalTitle = "Sonic Adventure",
                origin = MetadataOrigin.SCREENSCRAPER,
                description = "Existing synopsis",
            ),
            nowMillis = 1L,
        )

        ScreenScraperMetadataCache.persistVerifiedRecord(
            dao = dao,
            game = game,
            record = MetadataRecord(
                canonicalTitle = "Sonic Adventure",
                origin = MetadataOrigin.SCREENSCRAPER,
                boxArtUri = "https://www.screenscraper.fr/new.png",
                description = null,
            ),
            nowMillis = 2L,
        )

        assertEquals("Existing synopsis", dao.entries.single().description)
        assertEquals(2L, dao.entries.single().lastValidatedMillis)
    }

    @Test fun `first current-key media write preserves a legacy English catalog synopsis`() = runBlocking {
        val legacyKey = "catalog:sonic-adventure"
        val dao = FakeDao().apply {
            entries += TitleMetadataCacheEntity(
                catalogKey = legacyKey,
                platformId = "dreamcast",
                canonicalTitle = "Sonic Adventure",
                region = "USA",
                version = null,
                cachedAtMillis = 1L,
                description = "Legacy verified English synopsis",
                source = ScreenScraperMetadataCache.ENGLISH_SOURCE,
            )
        }

        ScreenScraperMetadataCache.persistVerifiedRecord(
            dao = dao,
            game = game(titleCatalogKey = legacyKey),
            record = MetadataRecord(
                canonicalTitle = "Sonic Adventure",
                origin = MetadataOrigin.SCREENSCRAPER,
                boxArtUri = "https://www.screenscraper.fr/new.png",
            ),
            nowMillis = 2L,
        )

        val current = requireNotNull(dao.entries.firstOrNull { it.catalogKey == "game:game-1" })
        assertEquals("Legacy verified English synopsis", current.description)
        assertEquals(ScreenScraperMetadataCache.ENGLISH_SOURCE, current.source)
    }

    @Test fun `media-only response cannot relabel a legacy locale-unknown synopsis as English`() = runBlocking {
        val dao = FakeDao().apply {
            entries += TitleMetadataCacheEntity(
                catalogKey = "game:game-1",
                platformId = "dreamcast",
                canonicalTitle = "Sonic Adventure",
                region = null,
                version = null,
                cachedAtMillis = 1L,
                description = "Descripción heredada",
                source = "screenscraper",
            )
        }

        ScreenScraperMetadataCache.persistVerifiedRecord(
            dao = dao,
            game = game(titleCatalogKey = null),
            record = MetadataRecord(
                canonicalTitle = "Sonic Adventure",
                origin = MetadataOrigin.SCREENSCRAPER,
                boxArtUri = "https://www.screenscraper.fr/new.png",
            ),
            nowMillis = 2L,
        )

        assertEquals(ScreenScraperMetadataCache.ENGLISH_SOURCE, dao.entries.single().source)
        assertNull(dao.entries.single().description)
    }

    @Test fun `non ScreenScraper records cannot overwrite provider state`() = runBlocking {
        val dao = FakeDao()
        val wrote = ScreenScraperMetadataCache.persistVerifiedRecord(
            dao = dao,
            game = game(titleCatalogKey = null),
            record = MetadataRecord("Offline", MetadataOrigin.TITLE_CATALOG, description = "No"),
        )

        assertFalse(wrote)
        assertTrue(dao.entries.isEmpty())
    }

    @Test fun `stale catalog keyed writer commits under the current logical winner`() = runBlocking {
        val dao = FakeDao()
        var transactionRan = false

        val wrote = ScreenScraperMetadataCache.persistVerifiedRecord(
            dao = dao,
            game = game(titleCatalogKey = "catalog:retired-a"),
            record = MetadataRecord(
                canonicalTitle = "Current title",
                origin = MetadataOrigin.SCREENSCRAPER,
                description = "Current synopsis",
            ),
            nowMillis = 8L,
            resolveCurrentGameId = { stale ->
                assertEquals("game-1", stale)
                "current-b"
            },
            runTransaction = { block ->
                transactionRan = true
                block()
            },
        )

        assertTrue(wrote)
        assertTrue(transactionRan)
        assertEquals("game:current-b", dao.entries.single().catalogKey)
    }

    private class FakeDao : TitleMetadataCacheDao {
        val entries = mutableListOf<TitleMetadataCacheEntity>()

        override suspend fun upsertAll(entries: List<TitleMetadataCacheEntity>) {
            entries.forEach { entry ->
                this.entries.removeAll { it.catalogKey == entry.catalogKey }
                this.entries += entry
            }
        }

        override suspend fun byCatalogKey(catalogKey: String): TitleMetadataCacheEntity? =
            entries.firstOrNull { it.catalogKey == catalogKey }
    }

    private fun game(titleCatalogKey: String?) = GameEntity(
        entryKey = "entry-1",
        gameId = "game-1",
        platformId = "dreamcast",
        contentKind = "GAME",
        displayName = "Sonic Adventure.chd",
        title = "Sonic Adventure",
        region = "USA",
        version = null,
        serial = "MK-51000",
        titleCatalogKey = titleCatalogKey,
        identitySource = "redump",
        locationKind = "MANAGED",
        availability = "AVAILABLE",
        sizeBytes = 1L,
        contentHash = null,
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = "/roms/Sonic Adventure.chd",
        relativePath = "Sonic Adventure.chd",
        discNumber = 1,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
        canonicalTitle = "Sonic Adventure",
        collapseKey = null,
        identityOrigin = "REDUMP",
        identityResolvedAtMillis = 1L,
    )
}
