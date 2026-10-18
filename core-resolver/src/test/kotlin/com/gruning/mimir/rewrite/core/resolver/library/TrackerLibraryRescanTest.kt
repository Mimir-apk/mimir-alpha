package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.jobs.JobProgressState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import com.gruning.mimir.rewrite.core.model.jobs.ScanProgressState
import com.gruning.mimir.rewrite.core.model.jobs.ScanPhase
import com.gruning.mimir.rewrite.core.storage.dao.FavoriteDao
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameHashSourceProjection
import com.gruning.mimir.rewrite.core.storage.dao.GameMemberProjection
import com.gruning.mimir.rewrite.core.storage.dao.GameRowProjection
import com.gruning.mimir.rewrite.core.storage.dao.HiddenItemDao
import com.gruning.mimir.rewrite.core.storage.dao.PlatformCountProjection
import com.gruning.mimir.rewrite.core.storage.dao.PlatformDao
import com.gruning.mimir.rewrite.core.storage.dao.SourceRootDao
import com.gruning.mimir.rewrite.core.storage.dao.TerminalGroupLink
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.PlatformEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceRootEntity
import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.model.library.ScanOutcome
import com.gruning.mimir.rewrite.core.model.library.LibrarySourceRole
import com.gruning.mimir.rewrite.core.model.library.SourceRemovalResult
import com.gruning.mimir.rewrite.core.model.library.ArtSource
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Competitor-tracker rows 26 and 30-31: a rescan removes a moved file's stale row, a case-only rename
 * leaves exactly one row, a card that is not mounted yet (Pegasus #1125, Cocoon D#275, RetroHrai the earlier update)
 * purges nothing and a later mount recovers, and rescanning is idempotent.
 *
 * Same pure-JVM harness as [LibraryRepositoryScanTest] (in-memory DAO fakes, stub walker); the fakes
 * below are copied from it because they are private there.
 */
class TrackerLibraryRescanTest {

    private fun wf(name: String, relative: String) =
        WalkedFile(
            displayName = name,
            relativePath = relative,
            sizeBytes = 2L * 1024 * 1024,
            documentUri = null,
            filePath = "/$relative",
        )

    private fun newRepo(
        walker: SourceLister,
        gameDao: FakeGameDao,
        sourceDao: FakeSourceDao = FakeSourceDao(),
    ) = LibraryRepositoryImpl(
        gameDao = gameDao,
        platformDao = FakePlatformDao(),
        sourceRootDao = sourceDao,
        hiddenItemDao = FakeHiddenDao(),
        favoriteDao = FakeFavoriteDao(),
        walker = walker,
        runScanTransaction = { block -> block() },
        clock = { 1000L },
    )

    @Test
    fun `a moved file leaves no stale row and the row carries the new path`() = runBlocking {
        val walker = MutableStubWalker(
            root = "roms",
            files = listOf(wf("Golden Sun (USA).gba", "GBA/Golden Sun (USA).gba")),
        )
        val gameDao = FakeGameDao()
        val repo = newRepo(walker, gameDao)

        repo.rescan("roms")
        assertEquals("/GBA/Golden Sun (USA).gba", gameDao.stored.values.single().managedPath)

        // The player moves the ROM to another folder under the same root and rescans.
        walker.files = listOf(wf("Golden Sun (USA).gba", "Archive/GBA/Golden Sun (USA).gba"))
        repo.rescan("roms")

        assertEquals("one game, not a stale row plus a new one", 1, gameDao.stored.size)
        val row = gameDao.stored.values.single()
        assertEquals("Archive/GBA/Golden Sun (USA).gba", row.relativePath)
        assertEquals("/Archive/GBA/Golden Sun (USA).gba", row.managedPath)
        assertTrue(
            "nothing still points at the old location, so Launch can never be handed a dead path",
            gameDao.stored.values.none { it.managedPath == "/GBA/Golden Sun (USA).gba" },
        )
    }

    @Test
    fun `a case-only rename leaves exactly one row with the new spelling`() = runBlocking {
        val walker = MutableStubWalker(
            root = "roms",
            files = listOf(wf("Golden Sun (USA).gba", "GBA/Golden Sun (USA).gba")),
        )
        val gameDao = FakeGameDao()
        val repo = newRepo(walker, gameDao)

        repo.rescan("roms")
        walker.files = listOf(wf("golden sun (usa).gba", "GBA/golden sun (usa).gba"))
        repo.rescan("roms")

        assertEquals(1, gameDao.stored.size)
        val row = gameDao.stored.values.single()
        assertEquals("golden sun (usa).gba", row.displayName)
        assertEquals("/GBA/golden sun (usa).gba", row.managedPath)
    }

    @Test
    fun `rescanning an unchanged source twice is idempotent`() = runBlocking {
        val walker = MutableStubWalker(
            root = "roms",
            files = listOf(
                wf("Golden Sun (USA).gba", "GBA/Golden Sun (USA).gba"),
                wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba"),
            ),
        )
        val gameDao = FakeGameDao()
        val repo = newRepo(walker, gameDao)

        repo.rescan("roms")
        val first = gameDao.stored.keys.toSet()
        repo.rescan("roms")

        assertEquals(2, gameDao.stored.size)
        assertEquals("same keys, no duplicates", first, gameDao.stored.keys.toSet())
    }

    @Test
    fun `a card that is not mounted yet purges nothing and a late mount recovers and adds`() = runBlocking {
        val sdRoot = "/storage/6A7F-FCFE/ROMs"
        val goldenSun = wf("Golden Sun (USA).gba", "GBA/Golden Sun (USA).gba")
        val metroid = wf("Metroid Fusion (USA).gba", "GBA/Metroid Fusion (USA).gba")
        val mario = wf("Mario Kart (USA).gba", "GBA/Mario Kart (USA).gba")
        val walker = MutableOutcomeWalker(SourceWalkResult.Complete(listOf(goldenSun, metroid)))
        val gameDao = FakeGameDao()
        val repo = newRepo(walker, gameDao)

        repo.rescan(sdRoot)
        assertEquals(2, gameDao.stored.size)

        // Boot as home app: the card has not mounted, so the walk cannot reach the root.
        walker.outcome = SourceWalkResult.Unavailable("Source root does not exist: $sdRoot")
        val unmounted = repo.rescan(sdRoot)

        assertTrue("the scan reports it could not look", unmounted.outcome is ScanOutcome.Failed)
        assertEquals("nothing is purged while the card is absent", 2, gameDao.stored.size)

        // The card mounts a moment later and a new game has appeared on it.
        walker.outcome = SourceWalkResult.Complete(listOf(goldenSun, metroid, mario))
        val mounted = repo.rescan(sdRoot)

        assertEquals(ScanOutcome.Healthy, mounted.outcome)
        assertEquals("the late mount recovers the library and indexes the new game", 3, gameDao.stored.size)
        assertTrue(gameDao.stored.values.all { it.availability == "AVAILABLE" })
    }

    private class StubWalker(private val byRoot: Map<String, List<WalkedFile>>) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult =
            SourceWalkResult.Complete(byRoot[rootUri].orEmpty())
        // Plain-path root in the test → not a SAF tree (default isSafTree checks the content:// prefix).
    }

    /**
     * A lister that captures [ScanProgressState] at two moments the scan cannot otherwise be observed
     * at, without threads or timing.
     *
     * [duringWalk] is read from inside the progress callback the repository hands down, so it proves
     * the callback is wired and that the walk publishes a COUNT with no fraction. [afterRecognise] is
     * read from [readUtf8], which `M3uPlaylistResolver` calls **after** the recognise loop has
     * finished — so it observes the last RECOGNISING report without racing a [StateFlow] collector,
     * whose conflation would make an exact-sequence assertion flaky by construction.
     */
    private class ProgressCapturingWalker(
        private val root: String,
        private val files: List<WalkedFile>,
    ) : SourceLister {
        var duringWalk: com.gruning.mimir.rewrite.core.model.jobs.ScanProgress? = null
        var sharedDuringWalk: List<com.gruning.mimir.rewrite.core.model.jobs.JobProgress> = emptyList()
        var afterRecognise: com.gruning.mimir.rewrite.core.model.jobs.ScanProgress? = null

        override suspend fun walk(rootUri: String): SourceWalkResult =
            SourceWalkResult.Complete(if (rootUri == root) files else emptyList())

        override suspend fun walk(rootUri: String, onProgress: (Int) -> Unit): SourceWalkResult {
            onProgress(7)
            duringWalk = ScanProgressState.progress.value
            sharedDuringWalk = JobProgressState.running.value
            return walk(rootUri)
        }

        override suspend fun readUtf8(file: WalkedFile, maxBytes: Long): String? {
            if (afterRecognise == null) afterRecognise = ScanProgressState.progress.value
            return ""
        }
    }

    private class PlaylistStubWalker(
        private val root: String,
        private val files: List<WalkedFile>,
        private val bodies: Map<String, String>,
    ) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult =
            SourceWalkResult.Complete(if (rootUri == root) files else emptyList())

        override suspend fun readUtf8(file: WalkedFile, maxBytes: Long): String? =
            bodies[file.relativePath]?.takeIf { it.toByteArray().size.toLong() <= maxBytes }
    }

    private class RecordingRootWalker(
        private val expectedRoot: String,
        private val files: List<WalkedFile>,
    ) : SourceLister {
        val requestedRoots = mutableListOf<String>()

        override suspend fun walk(rootUri: String): SourceWalkResult {
            requestedRoots += rootUri
            return SourceWalkResult.Complete(if (rootUri == expectedRoot) files else emptyList())
        }
    }

    private class RecordingOutcomeRootWalker(private val outcome: SourceWalkResult) : SourceLister {
        val requestedRoots = mutableListOf<String>()

        override suspend fun walk(rootUri: String): SourceWalkResult {
            requestedRoots += rootUri
            return outcome
        }
    }

    /** A walker whose file list can change between rescans of one root (to test add/remove reconcile). */
    private class MutableStubWalker(private val root: String, var files: List<WalkedFile>) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult =
            SourceWalkResult.Complete(if (rootUri == root) files else emptyList())
    }

    private class MutableOutcomeWalker(var outcome: SourceWalkResult) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult = outcome
    }

    private class MutableByRootOutcomeWalker(
        private val outcomes: MutableMap<String, SourceWalkResult>,
    ) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult =
            outcomes[rootUri] ?: SourceWalkResult.Failed("Source root does not exist")
    }

    private class CountingArtSource : ArtSource {
        var invalidateAllCount = 0
        override fun artUriFor(sourceDocumentUri: String?, managedPath: String?): String? = null
        override fun invalidateAll() {
            invalidateAllCount += 1
        }
    }

    private class MutationFault {
        var failAt: String? = null
        var failure: RuntimeException? = null

        fun checkpoint(label: String) {
            if (label != failAt) return
            throw failure ?: InjectedScanFault(label)
        }
    }

    private class InjectedScanFault(label: String) : RuntimeException(label)

    private class SnapshotTransaction(
        private val gameDao: FakeGameDao,
        private val platformDao: FakePlatformDao,
        private val sourceDao: FakeSourceDao,
        private val fault: MutationFault,
    ) {
        suspend fun run(block: suspend () -> Unit) {
            val games = LinkedHashMap(gameDao.stored)
            val platforms = LinkedHashMap(platformDao.stored)
            val sources = LinkedHashMap(sourceDao.stored)
            try {
                block()
                fault.checkpoint("transaction.commit")
            } catch (error: Throwable) {
                gameDao.stored.clear()
                gameDao.stored.putAll(games)
                platformDao.stored.clear()
                platformDao.stored.putAll(platforms)
                sourceDao.stored.clear()
                sourceDao.stored.putAll(sources)
                throw error
            }
        }
    }

    private class FakeGameDao(
        private val mutationCheckpoint: (String) -> Unit = {},
    ) : GameDao {
        val stored = LinkedHashMap<String, GameEntity>()
        val entryKeySourceQueries = mutableListOf<String>()
        var allEntryKeyQueryCount = 0
        override suspend fun upsertAll(games: List<GameEntity>) {
            games.forEach { stored[it.entryKey] = it }
            mutationCheckpoint("games.upsert")
        }
        override suspend fun byEntryKey(entryKey: String): GameEntity? = stored[entryKey]
        override suspend fun anyGamesExist(): Boolean = stored.isNotEmpty()
        // LG01: the BIOS seeder resolves a game's platform through this, so its filesystem probe stays
        // OUT of Room row projection (SD01 lesson).
        override suspend fun platformIdForGame(gameId: String): String? =
            stored.values.firstOrNull { it.gameId == gameId }?.platformId
        override suspend fun membersOfGame(gameId: String): List<GameEntity> =
            stored.values.filter { it.gameId == gameId || it.duplicateGroupId == gameId }
        override fun observeGameMembers(gameId: String): Flow<List<GameMemberProjection>> =
            MutableStateFlow(emptyList())
        override suspend fun hashSourceForGame(gameId: String): GameHashSourceProjection? =
            stored.values.firstOrNull { it.gameId == gameId }?.let {
                GameHashSourceProjection(
                    entryKey = it.entryKey,
                    sourceRootUri = it.sourceRootUri,
                    relativePath = it.relativePath,
                    platformId = it.platformId,
                    sourceDocumentUri = it.sourceDocumentUri,
                    managedPath = it.managedPath,
                    sizeBytes = it.sizeBytes,
                )
            }
        override suspend fun managedPathForGame(gameId: String): String? =
            stored.values.firstOrNull { it.gameId == gameId }?.managedPath
        override fun visibleGamesForPlatform(platformId: String): Flow<List<GameEntity>> =
            MutableStateFlow(stored.values.filter { it.platformId == platformId && it.isPrimaryMember })
        override suspend fun visibleGameCount(platformId: String): Int =
            stored.values.count { it.platformId == platformId && it.isPrimaryMember }
        override suspend fun exactHashDuplicateCandidates(): List<GameEntity> = stored.values
            .filter { it.isPrimaryMember && it.contentHash?.matches(Regex("raw-sha1:[0-9a-f]{40}")) == true }
            .groupBy { it.platformId to it.contentHash }
            .values.filter { rows -> rows.map { it.gameId }.distinct().size > 1 }
            .flatten()
        override suspend fun setContentHash(entryKey: String, hash: String?): Int {
            val row = stored[entryKey] ?: return 0
            stored[entryKey] = row.copy(contentHash = hash)
            return 1
        }
        override suspend fun storageUnavailableCount(): Int =
            stored.values.count { it.isPrimaryMember && it.availability == "STORAGE_UNAVAILABLE" }
        override suspend fun stampGroupWinner(winnerGameId: String, groupKind: String): Int {
            val hits = stored.values.filter { it.gameId == winnerGameId && it.isPrimaryMember }
            hits.forEach {
                stored[it.entryKey] = it.copy(duplicateGroupId = null, duplicateGroupKind = groupKind)
            }
            return hits.size
        }
        override suspend fun terminalGroupSeedsForSource(rootUri: String): List<TerminalGroupLink> {
            val terminalRows = stored.values.filter {
                it.duplicateGroupKind in setOf("EXACT_HASH", "CATALOG_TITLE_ID")
            }
            return stored.values
                .filter { row ->
                    row.sourceRootUri == rootUri &&
                        terminalRows.any { terminal ->
                            terminal.gameId == row.gameId ||
                                terminal.duplicateGroupId == row.gameId ||
                                (
                                    row.duplicateGroupId != null &&
                                        (
                                            terminal.gameId == row.duplicateGroupId ||
                                                terminal.duplicateGroupId == row.duplicateGroupId
                                            )
                                    )
                        }
                }
                .map { TerminalGroupLink(it.gameId, it.duplicateGroupId) }
        }
        override suspend fun terminalGroupLinksForIds(groupIds: List<String>): List<TerminalGroupLink> =
            stored.values
                .filter {
                    it.duplicateGroupKind in setOf("EXACT_HASH", "CATALOG_TITLE_ID") &&
                        (it.gameId in groupIds || it.duplicateGroupId in groupIds)
                }
                .map { TerminalGroupLink(it.gameId, it.duplicateGroupId) }
        override suspend fun dissolveExactGroups(groupIds: List<String>): Int {
            val hits = stored.values.filter {
                it.duplicateGroupKind in setOf("EXACT_HASH", "CATALOG_TITLE_ID") &&
                    (it.gameId in groupIds || it.duplicateGroupId in groupIds)
            }
            hits.forEach {
                stored[it.entryKey] = it.copy(
                    isPrimaryMember = true,
                    duplicateGroupId = null,
                    duplicateGroupKind = null,
                )
            }
            mutationCheckpoint("games.dissolve-exact")
            return hits.size
        }
        override suspend fun exactLogicalGameIds(groupIds: List<String>): List<String> = stored.values
            .filter {
                it.duplicateGroupKind in setOf("EXACT_HASH", "CATALOG_TITLE_ID") &&
                    (it.gameId in groupIds || it.duplicateGroupId in groupIds)
            }
            .map { it.gameId }
            .distinct()
        override suspend fun restoreSubordinateGroupKinds(logicalGameIds: List<String>): Int {
            var restored = 0
            logicalGameIds.forEach { gameId ->
                val subordinateKind = stored.values.firstOrNull {
                    it.duplicateGroupId == gameId && it.duplicateGroupKind != null &&
                        it.duplicateGroupKind !in setOf("EXACT_HASH", "CATALOG_TITLE_ID")
                }?.duplicateGroupKind ?: return@forEach
                stored.values.filter { it.gameId == gameId && it.isPrimaryMember }.forEach {
                    stored[it.entryKey] = it.copy(duplicateGroupId = gameId, duplicateGroupKind = subordinateKind)
                    restored++
                }
            }
            mutationCheckpoint("games.restore-subordinate-groups")
            return restored
        }
        override fun favoriteCandidateGameRows(): Flow<List<GameRowProjection>> = flowOf(emptyList())
        override fun favoriteGroupIds(): Flow<List<String>> = flowOf(emptyList())

        override fun visibleGameRowsForPlatform(platformId: String): Flow<List<GameRowProjection>> =
            MutableStateFlow(emptyList())
        override fun observeGameRow(gameId: String): Flow<GameRowProjection?> = MutableStateFlow(null)
        override fun visibleAvailableGameRowsByIds(gameIds: List<String>): Flow<List<GameRowProjection>> =
            MutableStateFlow(emptyList())
        override fun recentlyPlayedGameRows(limit: Int): Flow<List<GameRowProjection>> =
            MutableStateFlow(emptyList())
        override fun hiddenGameRows(): Flow<List<GameRowProjection>> = MutableStateFlow(emptyList())
        override fun favoriteGameRows(): Flow<List<GameRowProjection>> = MutableStateFlow(emptyList())
        override fun observeVisibleGameCount(): Flow<Int> =
            MutableStateFlow(stored.values.count { it.isPrimaryMember })
        override suspend fun platformsWithVisibleGames(): List<String> =
            stored.values.filter { it.isPrimaryMember }.map { it.platformId }.distinct()
        override fun observePlatformCounts(): Flow<List<PlatformCountProjection>> =
            MutableStateFlow(emptyList())
        override suspend fun visibleGameCountForSource(rootUri: String): Int = 0
        override fun observeStorageUnavailableSourceRoots(): Flow<List<String>> = flowOf(
            stored.values.filter { it.availability == "STORAGE_UNAVAILABLE" }
                .mapNotNull { it.sourceRootUri }
                .distinct(),
        )
        override suspend fun allPrimaryGames(): List<GameEntity> = stored.values.filter { it.isPrimaryMember }
        override suspend fun primaryGamesForEnrichment() = stored.values.filter { it.isPrimaryMember }
        override suspend fun primaryGamesWithoutScreenScraperMetadata(limit: Int): List<GameEntity> =
            stored.values.filter { it.isPrimaryMember }.take(limit)
        override suspend fun gameByManagedPath(path: String): GameEntity? =
            stored.values.firstOrNull { it.managedPath == path }
        override suspend fun memberCount(gameId: String): Int =
            stored.values.count { it.gameId == gameId }
        override suspend fun deleteByEntryKey(entryKey: String) {
            stored.remove(entryKey)
            mutationCheckpoint("games.delete")
        }
        override suspend fun entryKeysForSource(rootUri: String): List<String> {
            entryKeySourceQueries += rootUri
            return stored.values
                .filter { row -> row.sourceRootUri == rootUri }
                .map(GameEntity::entryKey)
                .sorted()
        }
        override suspend fun deleteByEntryKeys(entryKeys: List<String>): Int {
            var deleted = 0
            entryKeys.forEach { entryKey ->
                if (stored.remove(entryKey) != null) deleted++
            }
            mutationCheckpoint("games.delete-batch")
            return deleted
        }
        override suspend fun allEntryKeys(): List<String> {
            allEntryKeyQueryCount++
            return stored.keys.toList()
        }
        override suspend fun smallestHashablePrimary(): GameEntity? =
            stored.values.filter { it.isPrimaryMember && it.sourceDocumentUri != null }.minByOrNull { it.sizeBytes }
        override suspend fun smallestHashablePrimaryIn(platforms: List<String>): GameEntity? =
            stored.values.filter { it.isPrimaryMember && it.sourceDocumentUri != null && it.platformId in platforms }
                .minByOrNull { it.sizeBytes }
        override suspend fun distinctHashablePlatforms(): List<String> =
            stored.values.filter { it.isPrimaryMember && it.sourceDocumentUri != null }.map { it.platformId }.distinct()
        override suspend fun hashableMembers(platform: String): List<GameEntity> =
            stored.values.filter { it.sourceDocumentUri != null && it.platformId == platform }.sortedBy { it.sizeBytes }
        override suspend fun smallestHashableMember(platform: String): GameEntity? =
            stored.values.filter { it.sourceDocumentUri != null && it.platformId == platform && it.sizeBytes > 4096 }
                .minByOrNull { it.sizeBytes }
        override suspend fun unresolvedPrimaries(limit: Int): List<GameEntity> =
            stored.values.filter { it.isPrimaryMember && it.identityResolvedAtMillis == null }
                .sortedBy { it.sizeBytes }.take(limit)
        override suspend fun unresolvedPrimariesAfter(
            afterSizeBytes: Long,
            afterEntryKey: String,
            limit: Int,
        ): List<GameEntity> =
            stored.values.filter {
                it.isPrimaryMember && it.identityResolvedAtMillis == null &&
                    (it.sizeBytes > afterSizeBytes ||
                        (it.sizeBytes == afterSizeBytes && it.entryKey > afterEntryKey))
            }.sortedWith(compareBy(GameEntity::sizeBytes, GameEntity::entryKey)).take(limit)
        override suspend fun membersOfGameBySize(gameId: String): List<GameEntity> =
            stored.values.filter { it.gameId == gameId || it.duplicateGroupId == gameId }.sortedBy { it.sizeBytes }
        override suspend fun setIdentity(gameId: String, title: String?, collapse: String?, origin: String?, ts: Long) {
            stored.values.filter { it.gameId == gameId }.forEach {
                stored[it.entryKey] = it.copy(canonicalTitle = title, collapseKey = collapse, identityOrigin = origin, identityResolvedAtMillis = ts)
            }
        }
        override suspend fun availableChdShapedResidents(limit: Int): List<GameEntity> =
            stored.values.filter {
                it.availability == "AVAILABLE" && (
                    it.relativePath.lowercase().endsWith(".chd") ||
                        it.displayName.lowercase().endsWith(".chd") ||
                        it.sourceDocumentUri.orEmpty().lowercase().endsWith(".chd")
                    )
            }.sortedBy(GameEntity::entryKey).take(limit)
        override suspend fun availableArcadeResidentsForSource(
            rootUri: String,
            limit: Int,
        ): List<GameEntity> =
            stored.values.filter {
                it.sourceRootUri == rootUri &&
                    it.availability == "AVAILABLE" &&
                    it.platformId == "arcade"
            }.sortedWith(compareBy(GameEntity::relativePath, GameEntity::entryKey)).take(limit)
        override suspend fun reopenTerminalChdAttempts(): Int {
            val chdGameIds = stored.values.filter {
                it.relativePath.lowercase().endsWith(".chd") ||
                    it.displayName.lowercase().endsWith(".chd") ||
                    it.sourceDocumentUri.orEmpty().lowercase().endsWith(".chd")
            }.map(GameEntity::gameId).toSet()
            val hits = stored.values.filter {
                it.identityResolvedAtMillis != null && it.canonicalTitle == null && it.gameId in chdGameIds
            }
            hits.forEach { stored[it.entryKey] = it.copy(identityResolvedAtMillis = null) }
            return hits.size
        }
        override suspend fun reopenTerminalZipAttempts(): Int {
            val zipGameIds = stored.values.filter {
                it.relativePath.lowercase().endsWith(".zip") ||
                    it.displayName.lowercase().endsWith(".zip") ||
                    it.sourceDocumentUri.orEmpty().lowercase().endsWith(".zip")
            }.map(GameEntity::gameId).toSet()
            val hits = stored.values.filter {
                it.identityResolvedAtMillis != null && it.canonicalTitle == null &&
                    it.gameId in zipGameIds
            }
            hits.forEach { stored[it.entryKey] = it.copy(identityResolvedAtMillis = null) }
            return hits.size
        }
        override suspend fun clearUnmatchedIdentity(): Int {
            val hits = stored.values.filter { it.canonicalTitle == null }
            hits.forEach { stored[it.entryKey] = it.copy(identityResolvedAtMillis = null) }
            return hits.size
        }
        override fun unresolvedPrimaryCount(): Flow<Int> =
            flowOf(stored.values.count { it.isPrimaryMember && it.identityResolvedAtMillis == null })
        override suspend fun unresolvedCountAmong(gameIds: List<String>): Int =
            stored.values.count { it.gameId in gameIds && it.identityResolvedAtMillis == null }
        override suspend fun unverifiedResolvedPrimaries(
            attemptedPrefix: String,
            unreadablePrefix: String,
            unreadableSince: Long,
            limit: Int,
        ) = emptyList<GameEntity>()

        override suspend fun unverifiedResolvedPrimaryCount(attemptedPrefix: String): Int = 0

        override suspend fun stampMaintenance(key: String, completedAtMillis: Long) = Unit

        override suspend fun refreshMaintenance(key: String, completedAtMillis: Long) = Unit

        override suspend fun clearAllIdentity(): Int {
            val all = stored.values.toList()
            all.forEach {
                stored[it.entryKey] = it.copy(canonicalTitle = null, collapseKey = null, identityOrigin = null, identityResolvedAtMillis = null)
            }
            return all.size
        }
        override suspend fun clearLegacyFilenameKeyedIdentity(): Int = 0
        override suspend fun resolvedCollapsePrimaries(): List<GameEntity> = stored.values.filter {
            it.isPrimaryMember && it.collapseKey != null && it.duplicateGroupKind != "EXACT_HASH"
        }
        override suspend fun foldIntoGroup(loserGameId: String, winnerGameId: String, groupKind: String): Int {
            val hits = stored.values.filter { it.gameId == loserGameId && it.isPrimaryMember }
            hits.forEach {
                stored[it.entryKey] = it.copy(
                    isPrimaryMember = false,
                    duplicateGroupId = winnerGameId,
                    duplicateGroupKind = groupKind,
                )
            }
            return hits.size
        }
    }

    private class FakePlatformDao(
        private val mutationCheckpoint: (String) -> Unit = {},
    ) : PlatformDao {
        val stored = LinkedHashMap<String, PlatformEntity>()
        override suspend fun upsertAll(platforms: List<PlatformEntity>) {
            platforms.forEach { stored[it.platformId] = it }
            mutationCheckpoint("platforms.upsert")
        }
        override fun shelfPlatforms(): Flow<List<PlatformEntity>> =
            MutableStateFlow(stored.values.filter { it.gamesCount > 0 })
        override fun observeAll(): Flow<List<PlatformEntity>> =
            MutableStateFlow(stored.values.sortedBy { it.displayName })
        override suspend fun byId(platformId: String): PlatformEntity? = stored[platformId]
        // LG01: the BIOS seeder writes requiresBios here, which is what finally makes
        // LaunchReadiness.NeedsBios reachable.
        override suspend fun setRequiresBios(platformId: String, requiresBios: Boolean): Int {
            val existing = stored[platformId] ?: return 0
            stored[platformId] = existing.copy(requiresBios = requiresBios)
            return 1
        }
    }

    private class FakeSourceDao(
        private val mutationCheckpoint: (String) -> Unit = {},
    ) : SourceRootDao {
        val stored = LinkedHashMap<String, SourceRootEntity>()
        override suspend fun upsert(root: SourceRootEntity) {
            stored[root.rootId] = root
            mutationCheckpoint("sources.upsert")
        }
        override fun all(): Flow<List<SourceRootEntity>> = MutableStateFlow(stored.values.toList())
        override suspend fun allOnce(): List<SourceRootEntity> = stored.values.toList()
        override suspend fun remove(rootId: String) {
            stored.remove(rootId)
            mutationCheckpoint("sources.remove")
        }
    }

    private class FakeHiddenDao : HiddenItemDao {
        val stored = LinkedHashMap<String, HiddenItemEntity>()
        override suspend fun upsert(item: HiddenItemEntity) { stored[item.gameId] = item }
        override suspend fun byGame(gameId: String): HiddenItemEntity? = stored[gameId]
        override fun restorable(): Flow<List<HiddenItemEntity>> = MutableStateFlow(stored.values.toList())
        override suspend fun clear(gameId: String) { stored.remove(gameId) }
    }

    private class FakeFavoriteDao : FavoriteDao {
        val stored = LinkedHashSet<String>()
        override suspend fun set(favorite: FavoriteEntity) { stored.add(favorite.gameId) }
        override suspend fun promoteGroupIfAbsent(gameId: String, favoritedAtMillis: Long): Long =
            if (stored.add(gameId)) 1L else 0L
        override suspend fun clear(gameId: String) { stored.remove(gameId) }
        override suspend fun isFavorite(gameId: String): Boolean = gameId in stored
    }
}
