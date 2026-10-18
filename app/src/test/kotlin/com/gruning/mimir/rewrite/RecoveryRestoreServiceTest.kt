package com.gruning.mimir.rewrite

import android.content.ContextWrapper
import com.gruning.mimir.rewrite.RecoveryRestoreService.RestoreWriteDaos
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.ProfileId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.storage.dao.EmulatorOverrideDao
import com.gruning.mimir.rewrite.core.storage.dao.FavoriteDao
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.HOME_SHELF_RANK_STEP
import com.gruning.mimir.rewrite.core.storage.dao.HomeShelfDao
import com.gruning.mimir.rewrite.core.storage.dao.HiddenItemDao
import com.gruning.mimir.rewrite.core.storage.entity.EmulatorOverrideEntity
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.HomeShelfItemEntity
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryRestoreServiceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `favorite seam uses promoteGroupIfAbsent insert boolean not isFavorite`() = runBlocking {
        val daos = restoreDaos()
        val seams = RecoveryRestoreService.buildRestoreSeams(daos)

        assertTrue(seams.insertFavorite(GameId("game/a"), 10L))
        assertFalse(seams.insertFavorite(GameId("game/a"), 11L))
        assertEquals(1, (daos.favoriteDao as FakeFavoriteDao).rows.size)
    }

    @Test
    fun `hidden seam recomputes local duplicate group facts`() = runBlocking {
        val daos = restoreDaos()
        (daos.gameDao as FakeGameDao).members["game/a"] = listOf(
            gameEntity(gameId = "game/a", duplicateGroupId = "group/root"),
        )
        val seams = RecoveryRestoreService.buildRestoreSeams(daos)

        assertTrue(seams.insertHidden(GameId("game/a"), 42L))

        val hidden = (daos.hiddenItemDao as FakeHiddenDao).rows.values.single()
        assertEquals("USER_DISMISSED", hidden.reason)
        assertEquals("group/root", hidden.duplicateGroupId)
        assertTrue(hidden.isPrimaryMember)
    }

    @Test
    fun `blank Auto override is written as empty emulator id`() = runBlocking {
        val daos = restoreDaos()
        val seams = RecoveryRestoreService.buildRestoreSeams(daos)

        assertTrue(seams.insertEmulatorOverride(GameId("game/a"), "", 7L))

        assertEquals("", (daos.emulatorOverrideDao as FakeOverrideDao).rows.values.single().emulatorId)
    }

    @Test
    fun `shelf seam inserts explicit live sparse ranks`() = runBlocking {
        val daos = restoreDaos()
        (daos.homeShelfDao as FakeHomeShelfDao).maxRankValue = 5L
        val seams = RecoveryRestoreService.buildRestoreSeams(daos)

        assertTrue(
            seams.insertShelfItem(
                profileId = ProfileId.LOCAL,
                itemId = HomeShelfItemId.Emulated(GameId("game/a")),
                rank = 5L + HOME_SHELF_RANK_STEP,
                addedAtMillis = 9L,
            ),
        )

        val row = (daos.homeShelfDao as FakeHomeShelfDao).rows.values.single()
        assertEquals(1_029L, row.rank)
    }

    @Test
    fun `local-wins race returns false without overwriting existing rows`() = runBlocking {
        val daos = restoreDaos()
        (daos.hiddenItemDao as FakeHiddenDao).rows["game/a"] = HiddenItemEntity(
            gameId = "game/a",
            hidden = false,
            reason = null,
            duplicateGroupId = null,
            isPrimaryMember = true,
            hiddenAtMillis = 1L,
        )
        val seams = RecoveryRestoreService.buildRestoreSeams(daos)

        assertFalse(seams.insertHidden(GameId("game/a"), 99L))
        assertEquals(1L, (daos.hiddenItemDao as FakeHiddenDao).rows["game/a"]?.hiddenAtMillis)
    }

    @Test
    fun `apply seam remaps emulated shelf identity before persistence`() = runBlocking {
        val daos = restoreDaos(resolveCurrentGameId = { if (it == "game/retired") "game/a" else it })
        val seams = RecoveryRestoreService.buildRestoreSeams(daos)

        assertTrue(
            seams.insertShelfItem(
                profileId = ProfileId.LOCAL,
                itemId = HomeShelfItemId.Emulated(GameId("game/retired")),
                rank = HOME_SHELF_RANK_STEP,
                addedAtMillis = 12L,
            ),
        )

        val row = (daos.homeShelfDao as FakeHomeShelfDao).rows.values.single()
        assertEquals("game/a", row.itemId)
    }

    @Test
    fun `apply seam rejects emulator that is no longer recognized`() = runBlocking {
        val daos = restoreDaos(
            isEmulatorRecognized = { _, _ -> false },
        )
        val seams = RecoveryRestoreService.buildRestoreSeams(daos)

        assertFalse(seams.insertEmulatorOverride(GameId("game/a"), "removed.emulator", 13L))
        assertTrue((daos.emulatorOverrideDao as FakeOverrideDao).rows.isEmpty())
    }

    @Test
    fun `apply seam rejects android shelf package that is no longer installed`() = runBlocking {
        val daos = restoreDaos(isPackageInstalled = { false })
        val seams = RecoveryRestoreService.buildRestoreSeams(daos)

        assertFalse(
            seams.insertShelfItem(
                profileId = ProfileId.LOCAL,
                itemId = HomeShelfItemId.AndroidGame(
                    com.gruning.mimir.rewrite.core.model.home.AndroidPackageName("com.example.removed"),
                ),
                rank = HOME_SHELF_RANK_STEP,
                addedAtMillis = 14L,
            ),
        )
        assertTrue((daos.homeShelfDao as FakeHomeShelfDao).rows.isEmpty())
    }

    @Test
    fun `stale import runs are swept`() {
        val context = testContext()
        val root = File(context.cacheDir, LabImportRootName).apply { mkdirs() }
        val stale = File(root, "00000000-0000-0000-0000-000000000099").apply {
            mkdirs()
            setLastModified(1L)
        }

        RecoveryRestoreService.sweepStaleImports(
            context = context,
            olderThanUtcMillis = Long.MAX_VALUE,
            importRootName = LabImportRootName,
        )

        assertFalse(stale.exists())
    }

    @Test
    fun `mandatory safety archive failure refuses apply before transaction`() = runBlocking {
        var transactionStarted = false
        val result = RecoveryRestoreService.applyAfterSafetyArchive(
            safetyArchive = {
                Result.failure<StagedRecoveryArchive>(
                    IllegalStateException("injected safety archive failure"),
                )
            },
            applyTransaction = {
                transactionStarted = true
                error("transaction must not start when safety staging fails")
            },
        )

        assertTrue(result.isFailure)
        assertFalse(transactionStarted)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("mandatory safety archive"))
    }

    private fun testContext(): TestAppContext =
        TestAppContext(SpaciousCacheDir(temporary.newFolder("cache")))

    private fun restoreDaos(
        resolveCurrentGameId: suspend (String) -> String = { it },
        isEmulatorRecognized: suspend (GameId, String) -> Boolean = { _, emulatorId ->
            emulatorId.isBlank()
        },
        isPackageInstalled: (String) -> Boolean = { false },
    ): RestoreWriteDaos {
        val gameDao = FakeGameDao().apply {
            members["game/a"] = listOf(gameEntity(gameId = "game/a", duplicateGroupId = null))
        }
        return RestoreWriteDaos(
            gameDao = gameDao,
            favoriteDao = FakeFavoriteDao(),
            hiddenItemDao = FakeHiddenDao(),
            emulatorOverrideDao = FakeOverrideDao(),
            homeShelfDao = FakeHomeShelfDao(),
            resolveCurrentGameId = resolveCurrentGameId,
            isEmulatorRecognized = isEmulatorRecognized,
            isPackageInstalled = isPackageInstalled,
        )
    }

    private class TestAppContext(private val cache: File) : ContextWrapper(null) {
        override fun getApplicationContext(): android.content.Context = this
        override fun getCacheDir(): File = cache
    }

    private class SpaciousCacheDir(file: File) : File(file.path) {
        override fun getUsableSpace(): Long = Long.MAX_VALUE / 4
    }

    private class FakeGameDao : GameDao {
        val members = mutableMapOf<String, List<GameEntity>>()
        override suspend fun anyGamesExist(): Boolean = members.isNotEmpty()
        override suspend fun membersOfGame(gameId: String): List<GameEntity> = members[gameId].orEmpty()
        override suspend fun upsertAll(games: List<GameEntity>) = error("unused")
        override suspend fun byEntryKey(entryKey: String) = error("unused")
        override suspend fun platformIdForGame(gameId: String) = error("unused")
        override fun observeGameMembers(gameId: String) = error("unused")
        override suspend fun hashSourceForGame(gameId: String) = error("unused")
        override suspend fun managedPathForGame(gameId: String) = error("unused")
        override suspend fun allPrimaryGames() = error("unused")
        override suspend fun primaryGamesForEnrichment() = error("unused")
        override suspend fun primaryGamesWithoutScreenScraperMetadata(limit: Int) = error("unused")
        override suspend fun gameByManagedPath(path: String) = error("unused")
        override suspend fun memberCount(gameId: String) = error("unused")
        override suspend fun deleteByEntryKey(entryKey: String) = error("unused")
        override suspend fun entryKeysForSource(rootUri: String) = error("unused")
        override suspend fun deleteByEntryKeys(entryKeys: List<String>) = error("unused")
        override suspend fun allEntryKeys() = error("unused")
        override suspend fun smallestHashablePrimary() = error("unused")
        override suspend fun smallestHashablePrimaryIn(platforms: List<String>) = error("unused")
        override suspend fun distinctHashablePlatforms() = error("unused")
        override suspend fun hashableMembers(platform: String) = error("unused")
        override suspend fun smallestHashableMember(platform: String) = error("unused")
        override suspend fun unresolvedPrimaries(limit: Int) = error("unused")
        override suspend fun unresolvedPrimariesAfter(a: Long, b: String, c: Int) = error("unused")
        override suspend fun membersOfGameBySize(gameId: String) = error("unused")
        override suspend fun setIdentity(gameId: String, title: String?, collapse: String?, origin: String?, ts: Long) =
            error("unused")
        override suspend fun availableChdShapedResidents(limit: Int) = error("unused")
        override suspend fun availableArcadeResidentsForSource(rootUri: String, limit: Int) =
            error("unused")
        override suspend fun reopenTerminalChdAttempts() = error("unused")
        override suspend fun reopenTerminalZipAttempts() = error("unused")
        override suspend fun clearUnmatchedIdentity() = error("unused")
        override fun unresolvedPrimaryCount() = error("unused")
        override suspend fun unresolvedCountAmong(gameIds: List<String>) = error("unused")
        override suspend fun unverifiedResolvedPrimaries(
            attemptedPrefix: String,
            unreadablePrefix: String,
            unreadableSince: Long,
            limit: Int,
        ) = error("unused")
        override suspend fun unverifiedResolvedPrimaryCount(attemptedPrefix: String) = error("unused")
        override suspend fun stampMaintenance(key: String, completedAtMillis: Long) = error("unused")
        override suspend fun refreshMaintenance(key: String, completedAtMillis: Long) = error("unused")
        override suspend fun clearAllIdentity() = error("unused")
        override suspend fun clearLegacyFilenameKeyedIdentity() = error("unused")
        override suspend fun resolvedCollapsePrimaries() = error("unused")
        override suspend fun foldIntoGroup(loserGameId: String, winnerGameId: String, groupKind: String) = error("unused")
        override fun visibleGamesForPlatform(platformId: String) = error("unused")
        override suspend fun visibleGameCount(platformId: String) = error("unused")
        override suspend fun exactHashDuplicateCandidates() = error("unused")
        override suspend fun setContentHash(entryKey: String, hash: String?) = error("unused")
        override suspend fun storageUnavailableCount() = error("unused")
        override suspend fun stampGroupWinner(winnerGameId: String, groupKind: String) = error("unused")
        override suspend fun terminalGroupSeedsForSource(rootUri: String) = error("unused")
        override suspend fun terminalGroupLinksForIds(groupIds: List<String>) = error("unused")
        override suspend fun dissolveExactGroups(groupIds: List<String>) = error("unused")
        override suspend fun exactLogicalGameIds(groupIds: List<String>) = error("unused")
        override suspend fun restoreSubordinateGroupKinds(logicalGameIds: List<String>) = error("unused")
        override fun favoriteCandidateGameRows() = error("unused")
        override fun favoriteGroupIds() = error("unused")
        override fun visibleGameRowsForPlatform(platformId: String) = error("unused")
        override fun observeGameRow(gameId: String) = error("unused")
        override fun visibleAvailableGameRowsByIds(gameIds: List<String>) = error("unused")
        override fun recentlyPlayedGameRows(limit: Int) = error("unused")
        override fun hiddenGameRows() = error("unused")
        override fun observeVisibleGameCount() = error("unused")
        override suspend fun platformsWithVisibleGames() = error("unused")
        override fun observePlatformCounts() = error("unused")
        override suspend fun visibleGameCountForSource(rootUri: String) = error("unused")
        override fun observeStorageUnavailableSourceRoots() = flowOf(emptyList<String>())
        override fun favoriteGameRows() = error("unused")
    }

    private class FakeFavoriteDao : FavoriteDao {
        val rows = LinkedHashMap<String, FavoriteEntity>()
        override suspend fun promoteGroupIfAbsent(gameId: String, favoritedAtMillis: Long): Long {
            if (gameId in rows) return -1L
            rows[gameId] = FavoriteEntity(gameId, favoritedAtMillis)
            return 1L
        }
        override suspend fun set(favorite: FavoriteEntity) = error("unused")
        override suspend fun clear(gameId: String) = error("unused")
        override suspend fun isFavorite(gameId: String): Boolean = gameId in rows
    }

    private class FakeHiddenDao : HiddenItemDao {
        val rows = LinkedHashMap<String, HiddenItemEntity>()
        override suspend fun byGame(gameId: String): HiddenItemEntity? = rows[gameId]
        override suspend fun upsert(item: HiddenItemEntity) {
            rows[item.gameId] = item
        }
        override fun restorable() = error("unused")
        override suspend fun clear(gameId: String) = error("unused")
    }

    private class FakeOverrideDao : EmulatorOverrideDao {
        val rows = LinkedHashMap<String, EmulatorOverrideEntity>()
        override suspend fun forGame(gameId: String): EmulatorOverrideEntity? = rows[gameId]
        override suspend fun set(override: EmulatorOverrideEntity) {
            rows[override.gameId] = override
        }
        override suspend fun all() = rows.values.toList()
        override suspend fun clear(gameId: String) = error("unused")
    }

    private class FakeHomeShelfDao : HomeShelfDao() {
        val rows = LinkedHashMap<Triple<String, String, String>, HomeShelfItemEntity>()
        var maxRankValue: Long? = null
        override fun observeItems(profileId: String) = error("unused")
        override suspend fun orderedItems(profileId: String) = error("unused")
        override suspend fun item(profileId: String, itemKind: String, itemId: String) =
            rows[Triple(profileId, itemKind, itemId)]
        override suspend fun maxRank(profileId: String): Long? = maxRankValue
        override suspend fun insertItem(item: HomeShelfItemEntity): Long {
            val key = Triple(item.profileId, item.itemKind, item.itemId)
            if (key in rows) return -1L
            rows[key] = item
            return 1L
        }
        override suspend fun deleteItem(profileId: String, itemKind: String, itemId: String) = error("unused")
        override suspend fun updateRank(profileId: String, itemKind: String, itemId: String, rank: Long) =
            error("unused")
    }

    private fun gameEntity(gameId: String, duplicateGroupId: String?): GameEntity = GameEntity(
        entryKey = "entry/$gameId",
        gameId = gameId,
        platformId = "gba",
        contentKind = "GAME",
        displayName = "Game.gba",
        title = "Game",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "SAF_TREE_GRANT",
        availability = "AVAILABLE",
        sizeBytes = 1024L,
        contentHash = null,
        sourceRootUri = "content://recovery-test-root",
        sourceDocumentUri = null,
        managedPath = null,
        relativePath = "Game.gba",
        discNumber = 0,
        duplicateGroupId = duplicateGroupId,
        isPrimaryMember = true,
        duplicateGroupKind = null,
        canonicalTitle = null,
        collapseKey = null,
        identityOrigin = null,
        identityResolvedAtMillis = null,
    )

    private companion object {
        const val LabImportRootName = "recovery-import-test"
    }
}
