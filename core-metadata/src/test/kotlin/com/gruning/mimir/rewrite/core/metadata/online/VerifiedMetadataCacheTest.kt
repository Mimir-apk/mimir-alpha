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

/**
 * [VerifiedMetadataCache]
 * is the provider-neutral persist function extracted out of the historical [ScreenScraperMetadataCache] so a
 * Steam-attributed row can be written without that object's name lying about what it now stores.
 * [ScreenScraperMetadataCacheTest] keeps pinning the ScreenScraper-only entry point unchanged; this file
 * pins the STEAM origin specifically.
 */
class VerifiedMetadataCacheTest {

    @Test fun `a verified Steam record persists under the steam store label`() = runBlocking {
        val dao = FakeDao()
        val record = MetadataRecord(
            canonicalTitle = "",
            origin = MetadataOrigin.STEAM,
            description = "The Penitent One awakens as Blasphemous 2.",
        )

        assertTrue(VerifiedMetadataCache.persistVerifiedRecord(dao, game(), record, nowMillis = 42L))

        val cached = dao.entries.single()
        assertEquals("game:game-1", cached.catalogKey)
        assertEquals("The Penitent One awakens as Blasphemous 2.", cached.description)
        assertEquals(VerifiedMetadataCache.STEAM_STORE_SOURCE, cached.source)
        assertEquals("steam:store", cached.source)
        // Lowercase is load-bearing: the read model's LOWER() comparisons depend on it.
        assertEquals(cached.source, cached.source?.lowercase())
    }

    @Test fun `an empty record canonicalTitle falls back to the game's own title, never invents one`() =
        runBlocking {
            val dao = FakeDao()
            val record = MetadataRecord(
                canonicalTitle = "",
                origin = MetadataOrigin.STEAM,
                description = "A synopsis.",
            )

            VerifiedMetadataCache.persistVerifiedRecord(dao, game(), record, nowMillis = 1L)

            assertEquals("Sonic Adventure", dao.entries.single().canonicalTitle)
        }

    @Test fun `origins outside the accepted set are refused, same as the historical ScreenScraper-only entry point`() =
        runBlocking {
            val dao = FakeDao()

            val wrote = VerifiedMetadataCache.persistVerifiedRecord(
                dao = dao,
                game = game(),
                record = MetadataRecord("Offline", MetadataOrigin.TITLE_CATALOG, description = "No"),
            )

            assertFalse(wrote)
            assertTrue(dao.entries.isEmpty())
        }

    @Test fun `ScreenScraperMetadataCache delegates to VerifiedMetadataCache without behavior drift`() =
        runBlocking {
            val dao = FakeDao()
            val record = MetadataRecord(
                canonicalTitle = "Sonic Adventure",
                origin = MetadataOrigin.SCREENSCRAPER,
                description = "A fast adventure.",
            )

            assertTrue(ScreenScraperMetadataCache.persistVerifiedRecord(dao, game(), record, nowMillis = 7L))

            assertEquals(ScreenScraperMetadataCache.ENGLISH_SOURCE, dao.entries.single().source)
        }

    @Test fun `a Steam row's blank description never resurrects under a mismatched existing source`() =
        runBlocking {
            // Defensive coverage only: the app-level caller never persists a blank Steam description (the
            // "no verified marker without a real description" rule lives at the call site), but this
            // confirms the cache function itself does not silently resurrect a stale synopsis under the
            // wrong label if that rule is ever bypassed.
            val dao = FakeDao().apply {
                entries += TitleMetadataCacheEntity(
                    catalogKey = "game:game-1",
                    platformId = "pc_games",
                    canonicalTitle = "Sonic Adventure",
                    region = null,
                    version = null,
                    cachedAtMillis = 1L,
                    description = "Existing Steam synopsis",
                    source = VerifiedMetadataCache.STEAM_STORE_SOURCE,
                )
            }

            VerifiedMetadataCache.persistVerifiedRecord(
                dao = dao,
                game = game(),
                record = MetadataRecord(canonicalTitle = "Sonic Adventure", origin = MetadataOrigin.STEAM),
                nowMillis = 2L,
            )

            assertNull(dao.entries.single().description)
        }

    @Test fun `a SteamGridDB identification with no synopsis settles the row, and never downgrades a stored synopsis`() =
        runBlocking {
            val dao = FakeDao()
            val marker = MetadataRecord(canonicalTitle = "", origin = MetadataOrigin.STEAMGRIDDB)

            // RED at efe53336 + the enum member: persistVerifiedRecord refuses the origin and returns false.
            assertTrue(
                "SteamGridDB identified the game; the press must leave a verified row behind (R6-P1-04)",
                VerifiedMetadataCache.persistVerifiedRecord(dao, game(), marker, nowMillis = 1L),
            )
            val settled = dao.entries.single()
            assertEquals("steamgriddb", settled.source)      // lowercase: the read model's LOWER() allowlist
            assertNull(settled.description)                  // a marker never invents a synopsis
            assertEquals("Sonic Adventure", settled.canonicalTitle)

            // A later synopsis upgrades the row; a later marker never downgrades it.
            VerifiedMetadataCache.persistVerifiedRecord(
                dao, game(), MetadataRecord("", MetadataOrigin.STEAM, description = "A synopsis."), nowMillis = 2L,
            )
            assertTrue(VerifiedMetadataCache.persistVerifiedRecord(dao, game(), marker, nowMillis = 3L))
            val kept = dao.entries.single()
            assertEquals("A synopsis.", kept.description)
            assertEquals("steam:store", kept.source)
        }

    // ── R6-P3-01 T3: the igdb label, I1 (exact-key precedence), and I2/I3 (marker interplay + no-expiry
    // refusal). One focused regression test. ─────────────────────────
    @Test fun `an IGDB synopsis writes under the igdb label, respects I1 exact-key precedence, and I2-I3 marker interplay`() =
        runBlocking {
            val dao = FakeDao()

            // I3: an IGDB record with no cacheExpiresAt carries nothing worth a write -- refused.
            val noExpiry = MetadataRecord(canonicalTitle = "", origin = MetadataOrigin.IGDB, description = "A guess.")
            assertFalse(VerifiedMetadataCache.persistVerifiedRecord(dao, game(), noExpiry, nowMillis = 1L))
            assertTrue(dao.entries.isEmpty())

            val igdb = MetadataRecord(
                canonicalTitle = "",
                origin = MetadataOrigin.IGDB,
                description = "The Penitent One returns.",
                cacheExpiresAt = 100_000L,
            )
            assertTrue(VerifiedMetadataCache.persistVerifiedRecord(dao, game(), igdb, nowMillis = 1L))
            val written = dao.entries.single()
            assertEquals("igdb", written.source)
            assertEquals(100_000L, written.cacheExpiresAt)
            assertEquals("The Penitent One returns.", written.description)

            // I1: a stored exact-key (steam:store) synopsis must never be overwritten by a later IGDB guess.
            val steam = MetadataRecord(
                canonicalTitle = "",
                origin = MetadataOrigin.STEAM,
                description = "Storefront copy.",
            )
            VerifiedMetadataCache.persistVerifiedRecord(dao, game(), steam, nowMillis = 2L)
            assertTrue(VerifiedMetadataCache.persistVerifiedRecord(dao, game(), igdb, nowMillis = 3L))
            assertEquals("Storefront copy.", dao.entries.single().description)
            assertEquals("steam:store", dao.entries.single().source)

            // I2: a marker must not downgrade a FRESH IGDB synopsis, but MAY overwrite an EXPIRED one.
            val settled = FakeDao()
            VerifiedMetadataCache.persistVerifiedRecord(settled, game(), igdb, nowMillis = 1L)
            val marker = MetadataRecord(canonicalTitle = "", origin = MetadataOrigin.STEAMGRIDDB)
            assertTrue(VerifiedMetadataCache.persistVerifiedRecord(settled, game(), marker, nowMillis = 50_000L))
            assertEquals("The Penitent One returns.", settled.entries.single().description)
            assertEquals("igdb", settled.entries.single().source)
            assertTrue(VerifiedMetadataCache.persistVerifiedRecord(settled, game(), marker, nowMillis = 200_000L))
            assertEquals("steamgriddb", settled.entries.single().source)
            assertNull(settled.entries.single().description)
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

    private fun game() = GameEntity(
        entryKey = "entry-1",
        gameId = "game-1",
        platformId = "pc_games",
        contentKind = "GAME",
        displayName = "Sonic Adventure.steam",
        title = "Sonic Adventure",
        region = "USA",
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "gamenative",
        locationKind = "MANAGED",
        availability = "AVAILABLE",
        sizeBytes = 1L,
        contentHash = null,
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = "/roms/Sonic Adventure.steam",
        relativePath = "Sonic Adventure.steam",
        discNumber = 1,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
        canonicalTitle = "Sonic Adventure",
        collapseKey = null,
        identityOrigin = "GAMENATIVE",
        identityResolvedAtMillis = 1L,
    )
}
