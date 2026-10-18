package com.gruning.mimir.rewrite.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameDaoIdentityPolicyDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "identity-policy-${System.nanoTime()}.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun legacyFilenameKeyReplayClearsOnlyRiskyPlatformOriginPairs() = runBlocking {
        val database = MimirDatabaseFactory.openPreparedDatabase(context, databaseName)
        try {
            val rows = listOf(
                game("arcade-mame", "arcade", "MAME"),
                game("switch-title", "switch", "TITLE_CATALOG"),
                game("ps3-title", "ps3", "TITLE_CATALOG"),
                game("ps3-redump", "ps3", "REDUMP"),
                game("wii-title", "wii", "TITLE_CATALOG"),
                game("arcade-no-intro", "arcade", "NO_INTRO"),
            )
            database.gameDao().upsertAll(rows)

            assertEquals(3, database.gameDao().clearLegacyFilenameKeyedIdentity())

            val byId = database.gameDao().allPrimaryGames().associateBy(GameEntity::gameId)
            listOf("arcade-mame", "switch-title", "ps3-title").forEach { id ->
                val row = requireNotNull(byId[id])
                assertEquals(null, row.canonicalTitle)
                assertEquals(null, row.collapseKey)
                assertEquals(null, row.identityOrigin)
                assertEquals(null, row.identityResolvedAtMillis)
                assertEquals("hash:$id", row.contentHash)
                assertEquals("group:$id", row.duplicateGroupId)
            }
            listOf("ps3-redump", "wii-title", "arcade-no-intro").forEach { id ->
                val row = requireNotNull(byId[id])
                assertEquals("Canonical $id", row.canonicalTitle)
                assertEquals("Collapse $id", row.collapseKey)
                assertEquals(99L, row.identityResolvedAtMillis)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun containerCapabilityReplayClearsOnlyAffectedIdentityProjection() = runBlocking {
        val database = MimirDatabaseFactory.openPreparedDatabase(context, databaseName)
        try {
            val affected = listOf(
                game("arcade-set", "arcade", "MAME", "set.zip"),
                game("switch-base", "switch", "TITLE_CATALOG", "base.nsp"),
                game("disc-chd", "ps1", "REDUMP", "disc.chd"),
                game("zipped-disc", "wii", "TITLE_CATALOG", "disc.zip"),
                game("canonical-zipped-disc", "nintendo_wii", "TITLE_CATALOG", "canonical.zip"),
            )
            val preserved = listOf(
                game("cartridge-zip", "gba", "NO_INTRO", "cart.zip"),
                game("plain-disc", "ps1", "REDUMP", "disc.bin"),
                game("wii-rvz", "wii", "TITLE_CATALOG", "disc.rvz"),
            )
            database.gameDao().upsertAll(affected + preserved)
            database.favoriteDao().set(FavoriteEntity("arcade-set", 77L))

            assertEquals(
                affected.size,
                database.gameIdentityDao().quarantineAndClearA1CContainerIdentity(101L),
            )
            assertEquals(true, database.favoriteDao().isFavorite("arcade-set"))

            val byId = database.gameDao().allPrimaryGames().associateBy(GameEntity::gameId)
            affected.forEach { original ->
                val row = requireNotNull(byId[original.gameId])
                assertEquals(null, row.canonicalTitle)
                assertEquals(null, row.collapseKey)
                assertEquals(null, row.identityOrigin)
                assertEquals(null, row.identityResolvedAtMillis)
                assertEquals(original.contentHash, row.contentHash)
                assertEquals(original.duplicateGroupId, row.duplicateGroupId)
                assertEquals(original.entryKey, row.entryKey)
                assertEquals(original.gameId, row.gameId)
            }
            preserved.forEach { original ->
                val row = requireNotNull(byId[original.gameId])
                assertEquals(original.canonicalTitle, row.canonicalTitle)
                assertEquals(original.collapseKey, row.collapseKey)
                assertEquals(original.identityOrigin, row.identityOrigin)
                assertEquals(original.identityResolvedAtMillis, row.identityResolvedAtMillis)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun containerCapabilityReplayReopensPlaylistWhenChdChildIsAffected() = runBlocking {
        val database = MimirDatabaseFactory.openPreparedDatabase(context, databaseName)
        try {
            val playlist = game("multi-disc", "ps1", "REDUMP", "game.m3u").copy(
                contentKind = "MULTI_DISC_PLAYLIST",
                duplicateGroupId = null,
                duplicateGroupKind = "MULTI_DISC",
            )
            val disc = game("multi-disc", "ps1", "REDUMP", "disc-1.chd").copy(
                entryKey = "root::disc-1.chd",
                contentKind = "DISC_IMAGE",
                isPrimaryMember = false,
                duplicateGroupId = "multi-disc",
                duplicateGroupKind = "MULTI_DISC",
                discNumber = 1,
            )
            database.gameDao().upsertAll(listOf(playlist, disc))

            assertEquals(
                2,
                database.gameIdentityDao().quarantineAndClearA1CContainerIdentity(102L),
            )

            listOf("root::game.m3u", "root::disc-1.chd").forEach { entryKey ->
                val row = requireNotNull(database.gameDao().byEntryKey(entryKey))
                assertEquals(null, row.canonicalTitle)
                assertEquals(null, row.collapseKey)
                assertEquals(null, row.identityOrigin)
                assertEquals(null, row.identityResolvedAtMillis)
            }
            assertEquals(true, requireNotNull(database.gameDao().byEntryKey("root::game.m3u")).isPrimaryMember)
        } finally {
            database.close()
        }
    }

    @Test
    fun containerCapabilityReplayQuarantinesHistoricalCatalogFoldWithoutInventingStateOwnership() =
        runBlocking {
            val database = MimirDatabaseFactory.openPreparedDatabase(context, databaseName)
            try {
                val first = game("arcade-a", "arcade", "MAME", "a.zip").copy(
                    canonicalTitle = "Shared Machine",
                    collapseKey = "shared-machine",
                    duplicateGroupId = null,
                    duplicateGroupKind = null,
                )
                val second = game("arcade-b", "arcade", "MAME", "b.zip").copy(
                    canonicalTitle = "Shared Machine",
                    collapseKey = "shared-machine",
                    duplicateGroupId = null,
                    duplicateGroupKind = null,
                )
                database.gameDao().upsertAll(listOf(first, second))
                database.favoriteDao().set(FavoriteEntity(second.gameId, 77L))
                assertEquals(
                    1,
                    database.gameIdentityDao().foldLiveCatalogGroup(
                        candidateGameIds = listOf(first.gameId, second.gameId),
                        platformId = "arcade",
                        collapseKey = "shared-machine",
                        baseTitle = "shared machine",
                        nowMillis = 100L,
                    ),
                )
                assertEquals(true, database.favoriteDao().isFavorite(first.gameId))
                assertEquals(false, database.favoriteDao().isFavorite(second.gameId))

                assertEquals(
                    2,
                    database.gameIdentityDao().quarantineAndClearA1CContainerIdentity(103L),
                )

                assertEquals(first.gameId, database.gameIdentityDao().resolveCurrentGameId(first.gameId))
                assertEquals(second.gameId, database.gameIdentityDao().resolveCurrentGameId(second.gameId))
                assertEquals(true, database.favoriteDao().isFavorite(first.gameId))
                assertEquals(false, database.favoriteDao().isFavorite(second.gameId))
                val primaries = database.gameDao().allPrimaryGames().associateBy(GameEntity::gameId)
                listOf(first.gameId, second.gameId).forEach { gameId ->
                    val row = requireNotNull(primaries[gameId])
                    assertEquals(null, row.duplicateGroupId)
                    assertEquals(null, row.duplicateGroupKind)
                    assertEquals(null, row.identityResolvedAtMillis)
                }
            } finally {
                database.close()
            }
        }

    @Test
    fun a1dReplayReopensOnlyTerminalChdAttemptsAndInventoriesAvailableResidents() = runBlocking {
        val database = MimirDatabaseFactory.openPreparedDatabase(context, databaseName)
        try {
            fun terminal(row: GameEntity) =
                row.copy(canonicalTitle = null, collapseKey = null, identityOrigin = null)
            val rows = listOf(
                terminal(game("terminal-chd", "ps1", "REDUMP", fileName = "differential.chd")),
                game("identified-chd", "ps1", "REDUMP", fileName = "verified.chd"),
                terminal(game("terminal-rom", "gba", "NO_INTRO", fileName = "game.gba")),
                terminal(game("multi-disc", "ps1", "REDUMP", fileName = "game.m3u")),
                terminal(game("multi-disc", "ps1", "REDUMP", fileName = "game-disc2.chd"))
                    .copy(isPrimaryMember = false),
                game("detached-chd", "ps1", "REDUMP", fileName = "detached.chd")
                    .copy(availability = "STORAGE_UNAVAILABLE"),
                // A SAF provider persisting opaque leaves: the .chd hint lives only in the document URI.
                terminal(game("saf-opaque-chd", "ps1", "REDUMP", fileName = "42"))
                    .copy(sourceDocumentUri = "content://provider/tree/r/document/roms%2Fparent.chd"),
            )
            database.gameDao().upsertAll(rows)

            // Inventory: AVAILABLE CHD-shaped rows only, non-primary included, entryKey-ordered.
            assertEquals(
                listOf(
                    "root::42",
                    "root::differential.chd",
                    "root::game-disc2.chd",
                    "root::verified.chd",
                ),
                database.gameDao().availableChdShapedResidents(limit = 5).map(GameEntity::entryKey),
            )
            assertEquals(
                listOf("root::42", "root::differential.chd"),
                database.gameDao().availableChdShapedResidents(limit = 2).map(GameEntity::entryKey),
            )

            // Replay: the terminal CHD attempts (URI-shaped included) and the m3u closure reopen;
            // identified rows, non-CHD terminals and the detached-but-identified CHD are untouched.
            assertEquals(4, database.gameDao().reopenTerminalChdAttempts())

            val byEntry = database.gameDao().allEntryKeys().associateWith { key ->
                database.gameDao().byEntryKey(key)
            }
            listOf(
                "root::differential.chd",
                "root::game.m3u",
                "root::game-disc2.chd",
                "root::42",
            ).forEach { key ->
                assertEquals(key, null, requireNotNull(byEntry[key])?.identityResolvedAtMillis)
            }
            listOf("root::verified.chd", "root::game.gba", "root::detached.chd").forEach { key ->
                assertEquals(key, 99L, requireNotNull(byEntry[key])?.identityResolvedAtMillis)
            }
            assertEquals("Canonical identified-chd", byEntry["root::verified.chd"]?.canonicalTitle)
        } finally {
            database.close()
        }
    }

    @Test
    fun zipCapabilityReplayReopensOnlyTerminalZipOwningGames() = runBlocking {
        val database = MimirDatabaseFactory.openPreparedDatabase(context, databaseName)
        try {
            val rows = listOf(
                game("terminal-zip", "n64", "NO_INTRO", fileName = "bundle.zip")
                    .copy(canonicalTitle = null, collapseKey = null, identityOrigin = null),
                game("identified-zip", "n64", "NO_INTRO", fileName = "identified.zip"),
                game("terminal-rom", "n64", "NO_INTRO", fileName = "plain.z64")
                    .copy(canonicalTitle = null, collapseKey = null, identityOrigin = null),
                game("zip-member-group", "n64", "NO_INTRO", fileName = "primary.m3u")
                    .copy(canonicalTitle = null, collapseKey = null, identityOrigin = null),
                game("zip-member-group", "n64", "NO_INTRO", fileName = "member.zip")
                    .copy(
                        canonicalTitle = null,
                        collapseKey = null,
                        identityOrigin = null,
                        isPrimaryMember = false,
                    ),
            )
            database.gameDao().upsertAll(rows)

            assertEquals(3, database.gameDao().reopenTerminalZipAttempts())
            val byEntry = database.gameDao().allEntryKeys().associateWith { key ->
                database.gameDao().byEntryKey(key)
            }
            listOf("root::bundle.zip", "root::primary.m3u", "root::member.zip").forEach { key ->
                assertEquals(key, null, requireNotNull(byEntry[key])?.identityResolvedAtMillis)
            }
            listOf("root::identified.zip", "root::plain.z64").forEach { key ->
                assertEquals(key, 99L, requireNotNull(byEntry[key])?.identityResolvedAtMillis)
            }
        } finally {
            database.close()
        }
    }

    private fun game(
        id: String,
        platform: String,
        origin: String,
        fileName: String = "$id.rom",
    ) = GameEntity(
        entryKey = "root::$fileName",
        gameId = id,
        platformId = platform,
        contentKind = "GAME",
        displayName = fileName,
        title = id,
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = 10L,
        contentHash = "hash:$id",
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = "/tmp/$fileName",
        relativePath = fileName,
        discNumber = 0,
        duplicateGroupId = "group:$id",
        isPrimaryMember = true,
        duplicateGroupKind = "EXACT_HASH",
        canonicalTitle = "Canonical $id",
        collapseKey = "Collapse $id",
        identityOrigin = origin,
        identityResolvedAtMillis = 99L,
    )
}
