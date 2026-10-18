package com.gruning.mimir.rewrite.core.resolver.capacity

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.model.library.ScanOutcome
import com.gruning.mimir.rewrite.core.model.library.SourceFailureKind
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.resolver.capacity.Ep03GeneratedFixtures.Order
import com.gruning.mimir.rewrite.core.resolver.library.LibraryRepositoryImpl
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkLimits
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalker
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ep03CapacityDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun portableBatchingDissolves2001CrossRootGroupsAndSurvivesReopen(): Unit = runBlocking {
        withDatabase("bind-2001") { name, database ->
            val rows = ArrayList<GameEntity>(GROUP_COUNT * 2)
            repeat(GROUP_COUNT) { index ->
                val winnerId = "winner-$index"
                rows += game(
                    entryKey = "$ROOT_A::game-$index.nes",
                    gameId = winnerId,
                    root = ROOT_A,
                    primary = true,
                    groupId = null,
                    groupKind = "EXACT_HASH",
                )
                rows += game(
                    entryKey = "$ROOT_B::game-$index.nes",
                    gameId = "loser-$index",
                    root = ROOT_B,
                    primary = false,
                    groupId = winnerId,
                    groupKind = "EXACT_HASH",
                )
            }
            database.gameDao().upsertAll(rows)
            logSqliteLimits(database)

            assertEquals(GROUP_COUNT * 2, database.gameDao().prepareSourceForRescan(ROOT_B))
            assertEquals(GROUP_COUNT * 2, scalar(database, "SELECT COUNT(*) FROM games WHERE isPrimaryMember = 1"))
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM games WHERE duplicateGroupId IS NOT NULL"))

            database.close()
            val reopened = openDatabase(name)
            try {
                assertEquals(GROUP_COUNT * 2, reopened.gameDao().allEntryKeys().size)
                assertEquals("ok", integrityCheck(reopened))
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun pathologicalSingleGroupFanoutRestoresSubordinateFactsInPortableBatches(): Unit = runBlocking {
        withDatabase("fanout-1000") { _, database ->
            val rows = ArrayList<GameEntity>(1 + FANOUT * 2)
            rows += game(
                entryKey = "$ROOT_A::winner.m3u",
                gameId = "winner",
                root = ROOT_A,
                primary = true,
                groupId = null,
                groupKind = "CATALOG_TITLE_ID",
            )
            repeat(FANOUT) { index ->
                val loserId = "loser-$index"
                rows += game(
                    entryKey = "$ROOT_B::playlist-$index.m3u",
                    gameId = loserId,
                    root = ROOT_B,
                    primary = false,
                    groupId = "winner",
                    groupKind = "CATALOG_TITLE_ID",
                    contentKind = "MULTI_DISC_PLAYLIST",
                )
                rows += game(
                    entryKey = "$ROOT_B::disc-$index.chd",
                    gameId = loserId,
                    root = ROOT_B,
                    primary = false,
                    groupId = loserId,
                    groupKind = "MULTI_DISC",
                    contentKind = "DISC_MEMBER",
                    discNumber = 1,
                )
            }
            database.gameDao().upsertAll(rows)

            assertEquals(FANOUT + 1, database.gameDao().prepareSourceForRescan(ROOT_B))
            assertEquals(
                FANOUT,
                scalar(
                    database,
                    "SELECT COUNT(*) FROM games WHERE isPrimaryMember = 1 " +
                        "AND duplicateGroupKind = 'MULTI_DISC' AND duplicateGroupId = gameId",
                ),
            )
            assertEquals(
                FANOUT,
                scalar(
                    database,
                    "SELECT COUNT(*) FROM games WHERE isPrimaryMember = 0 " +
                        "AND duplicateGroupKind = 'MULTI_DISC' AND duplicateGroupId = gameId",
                ),
            )
            assertEquals("ok", integrityCheck(database))
        }
    }

    @Test
    fun terminalGraphClosureDissolvesHistoricalWinnerChainOnRoom(): Unit = runBlocking {
        withDatabase("terminal-chain") { _, database ->
            database.gameDao().upsertAll(
                listOf(
                    game("$ROOT_A::c.nes", "c", ROOT_A, true, null, "CATALOG_TITLE_ID"),
                    game("$ROOT_A::a.nes", "a", ROOT_A, false, "c", "CATALOG_TITLE_ID"),
                    game("$ROOT_B::b.m3u", "b", ROOT_B, false, "a", "CATALOG_TITLE_ID", "MULTI_DISC_PLAYLIST"),
                    game("$ROOT_B::b-disc.chd", "b", ROOT_B, false, "b", "MULTI_DISC", "DISC_MEMBER", 1),
                ),
            )

            assertEquals(3, database.gameDao().prepareSourceForRescan(ROOT_B))
            assertEquals(null, database.gameDao().byEntryKey(physicalResidentKey(ROOT_A, "c.nes"))?.duplicateGroupId)
            assertEquals(null, database.gameDao().byEntryKey(physicalResidentKey(ROOT_A, "a.nes"))?.duplicateGroupId)
            val restored = database.gameDao().byEntryKey(physicalResidentKey(ROOT_B, "b.m3u"))
            assertEquals(true, restored?.isPrimaryMember)
            assertEquals("b", restored?.duplicateGroupId)
            assertEquals("MULTI_DISC", restored?.duplicateGroupKind)
            assertEquals("ok", integrityCheck(database))
        }
    }

    @Test
    fun exactRootSeedsIgnoreColonNeighborAndExpandFromLegacySubordinate(): Unit = runBlocking {
        withDatabase("terminal-exact-root") { _, database ->
            val targetRoot = "/storage/card/Legacy"
            val neighboringRoot = "/storage/card/Legacy:"
            val subordinate = game(
                "$targetRoot::target-disc.chd",
                "logical",
                targetRoot,
                false,
                "logical",
                "MULTI_DISC",
                "DISC_MEMBER",
                1,
            ).copy(
                locationKind = "MANAGED_PATH",
                sourceDocumentUri = null,
                managedPath = "$targetRoot/target-disc.chd",
            )
            val terminalPrimary = game(
                "$ROOT_B::logical.m3u",
                "logical",
                ROOT_B,
                false,
                "winner",
                "CATALOG_TITLE_ID",
                "MULTI_DISC_PLAYLIST",
            )
            val winner = game(
                "$ROOT_A::winner.nes",
                "winner",
                ROOT_A,
                true,
                null,
                "CATALOG_TITLE_ID",
            )
            val neighbor = game(
                "$neighboringRoot::neighbor.nes",
                "neighbor",
                neighboringRoot,
                true,
                null,
                "EXACT_HASH",
            ).copy(
                locationKind = "MANAGED_PATH",
                sourceDocumentUri = null,
                managedPath = "$neighboringRoot/neighbor.nes",
            )
            database.gameDao().upsertAll(listOf(subordinate, terminalPrimary, winner, neighbor))

            assertEquals(2, database.gameDao().prepareSourceForRescan(targetRoot))
            assertEquals(null, database.gameDao().byEntryKey(winner.entryKey)?.duplicateGroupKind)
            assertEquals(
                "MULTI_DISC",
                database.gameDao().byEntryKey(terminalPrimary.entryKey)?.duplicateGroupKind,
            )
            assertEquals(
                "MULTI_DISC",
                database.gameDao().byEntryKey(subordinate.entryKey)?.duplicateGroupKind,
            )
            assertEquals(
                "EXACT_HASH",
                database.gameDao().byEntryKey(neighbor.entryKey)?.duplicateGroupKind,
            )
            val seedPlan = terminalSeedQueryPlan(database, targetRoot)
            assertTrue("seed plan=$seedPlan", seedPlan.any { "SEARCH" in it && "games" in it })
            assertTrue("seed plan=$seedPlan", seedPlan.none { it.startsWith("SCAN g") })
            assertTrue("seed plan=$seedPlan", seedPlan.none { it.startsWith("SCAN t") })
            assertTrue(
                "seed plan=$seedPlan",
                seedPlan.any {
                    "index_games_sourceRootUri_entryKey" in it ||
                        "index_games_sourceRootUri_relativePath" in it
                },
            )
            assertTrue("seed plan=$seedPlan", seedPlan.any { "MULTI-INDEX OR" in it })
            assertTrue("seed plan=$seedPlan", seedPlan.any { "index_games_gameId" in it })
            assertTrue(
                "seed plan=$seedPlan",
                seedPlan.any { "index_games_duplicateGroupId" in it },
            )
            val expansionPlan = terminalExpansionQueryPlan(database)
            assertTrue("expansion plan=$expansionPlan", expansionPlan.any { "MULTI-INDEX OR" in it })
            assertTrue("expansion plan=$expansionPlan", expansionPlan.any { "index_games_gameId" in it })
            assertTrue(
                "expansion plan=$expansionPlan",
                expansionPlan.any { "index_games_duplicateGroupId" in it },
            )
            assertEquals("ok", integrityCheck(database))
        }
    }

    @Test
    fun terminalComponentHandlesMixedCycleSelfLoopDanglingWinnerAndUnrelatedControl(): Unit =
        runBlocking {
            withDatabase("terminal-adversarial-graph") { _, database ->
                val targetRoot = "content://ep13/root/graph"
                val rows = listOf(
                    game("$targetRoot::a", "a", targetRoot, false, "b", "EXACT_HASH"),
                    game("$ROOT_A::b", "b", ROOT_A, false, "c", "CATALOG_TITLE_ID"),
                    game("$ROOT_B::c", "c", ROOT_B, false, "a", "EXACT_HASH"),
                    game("$targetRoot::self", "self", targetRoot, false, "self", "CATALOG_TITLE_ID"),
                    game(
                        "$targetRoot::dangling",
                        "dangling",
                        targetRoot,
                        false,
                        "missing",
                        "EXACT_HASH",
                    ),
                    game("$ROOT_A::unrelated", "unrelated", ROOT_A, true, null, "EXACT_HASH"),
                )
                database.gameDao().upsertAll(rows)

                assertEquals(5, database.gameDao().prepareSourceForRescan(targetRoot))
                rows.take(5).forEach { row ->
                    val dissolved = database.gameDao().byEntryKey(row.entryKey)
                    assertEquals(true, dissolved?.isPrimaryMember)
                    assertEquals(null, dissolved?.duplicateGroupId)
                    assertEquals(null, dissolved?.duplicateGroupKind)
                }
                assertEquals(
                    "EXACT_HASH",
                    database.gameDao().byEntryKey(rows.last().entryKey)?.duplicateGroupKind,
                )
                assertEquals("ok", integrityCheck(database))
            }
        }

    @Test
    fun terminalSecondDissolveBatchFaultRollsBackTheFirstBatchOnRoom(): Unit = runBlocking {
        withDatabase("terminal-dissolve-rollback") { _, database ->
            val root = "content://ep13/root/rollback"
            val rows = List(DOUBLE_GROUP_BATCH_SIZE + 1) { index ->
                val id = "node-${index.toString().padStart(4, '0')}"
                game(
                    entryKey = "$root::$id.nes",
                    gameId = id,
                    root = root,
                    primary = false,
                    groupId = null,
                    groupKind = "EXACT_HASH",
                )
            }
            database.gameDao().upsertAll(rows)
            val secondBatchId = rows.last().gameId
            database.openHelper.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER ep13_fail_second_dissolve BEFORE UPDATE ON games " +
                    "WHEN OLD.gameId = '$secondBatchId' " +
                    "BEGIN SELECT RAISE(ABORT, 'EP13 second dissolve batch fault'); END",
            )
            try {
                try {
                    database.gameDao().prepareSourceForRescan(root)
                    fail("Expected the second dissolve batch to abort")
                } catch (_: android.database.sqlite.SQLiteException) {
                    // The @Transaction wrapper must restore the first 400 updates.
                }
            } finally {
                database.openHelper.writableDatabase.execSQL(
                    "DROP TRIGGER IF EXISTS ep13_fail_second_dissolve",
                )
            }

            assertEquals(
                rows.size,
                scalar(
                    database,
                    "SELECT COUNT(*) FROM games WHERE duplicateGroupKind = 'EXACT_HASH' " +
                        "AND isPrimaryMember = 0",
                ),
            )
            assertEquals("ok", integrityCheck(database))
        }
    }

    @Test
    fun cancellationAfterEnteringSecondDissolveBatchRollsBackAndSurvivesReopen(): Unit =
        runBlocking {
            val name = "ep13-terminal-cancel-${UUID.randomUUID()}.db"
            val root = "content://ep13/root/cancel"
            val enteredSecondBatch = CountDownLatch(1)
            val releaseSecondBatch = CountDownLatch(1)
            val dissolveQueries = AtomicInteger()
            val callback = RoomDatabase.QueryCallback { sql, _ ->
                if (
                    sql.startsWith("UPDATE games SET isPrimaryMember = 1") &&
                    dissolveQueries.incrementAndGet() == 2
                ) {
                    enteredSecondBatch.countDown()
                    check(releaseSecondBatch.await(20, TimeUnit.SECONDS))
                }
            }
            val database = Room.databaseBuilder(context, MimirDatabase::class.java, name)
                .setQueryCallback(callback, Executor { command -> command.run() })
                .build()
            val scope = CoroutineScope(Dispatchers.Default)
            try {
                val rows = List(DOUBLE_GROUP_BATCH_SIZE + 1) { index ->
                    val id = "cancel-${index.toString().padStart(4, '0')}"
                    game(
                        entryKey = "$root::$id.nes",
                        gameId = id,
                        root = root,
                        primary = false,
                        groupId = null,
                        groupKind = "EXACT_HASH",
                    )
                }
                database.gameDao().upsertAll(rows)
                val deferred = scope.async { database.gameDao().prepareSourceForRescan(root) }
                assertTrue(
                    "second dissolve batch was not observed",
                    enteredSecondBatch.await(20, TimeUnit.SECONDS),
                )
                deferred.cancel()
                releaseSecondBatch.countDown()
                try {
                    deferred.await()
                    fail("Expected cancellation after entering dissolve batch two")
                } catch (_: CancellationException) {
                    // The next between-batch check aborts the Room transaction.
                }

                database.close()
                val reopened = openDatabase(name)
                try {
                    assertEquals(
                        rows.size,
                        scalar(
                            reopened,
                            "SELECT COUNT(*) FROM games WHERE duplicateGroupKind = 'EXACT_HASH' " +
                                "AND isPrimaryMember = 0",
                        ),
                    )
                    assertEquals("ok", integrityCheck(reopened))
                } finally {
                    reopened.close()
                }
            } finally {
                releaseSecondBatch.countDown()
                scope.cancel()
                if (database.isOpen) database.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun exactRootPruningTreatsManagedPrefixesLiterallyAndDeletesInPortableBatches(): Unit = runBlocking {
        withDatabase("exact-root-prune") { _, database ->
            val targetRoot = "/storage/ABCD-1234/ROMs"
            val siblingRoot = "/storage/ABCD-1234/ROMs2"
            val encodedRoot = "content://provider/tree/primary%3AROMs"
            val encodedSiblingRoot = "content://provider/tree/primary%3AROMs2"
            val oddRoots = listOf(
                "content://provider/tree/primary_Δ_日本",
                "content://provider/tree/primary::branch",
                "content://provider/tree/primary:;branch",
                "content://provider/tree/terminal:",
                "content://provider/tree/terminal",
            )
            val targetRows = List(PORTABLE_BATCH_PROBE_SIZE) { index ->
                game(
                    entryKey = "$targetRoot::NES/${index.toString().padStart(4, '0')}.nes",
                    gameId = "target-$index",
                    root = targetRoot,
                    primary = true,
                    groupId = null,
                    groupKind = null,
                ).copy(
                    locationKind = "MANAGED_PATH",
                    sourceDocumentUri = null,
                    managedPath = "$targetRoot/NES/$index.nes",
                )
            }
            val sibling = game(
                entryKey = "$siblingRoot::NES/control.nes",
                gameId = "sibling-control",
                root = siblingRoot,
                primary = true,
                groupId = null,
                groupKind = null,
            ).copy(
                locationKind = "MANAGED_PATH",
                sourceDocumentUri = null,
                managedPath = "$siblingRoot/NES/control.nes",
            )
            val encoded = game(
                entryKey = "$encodedRoot::encoded.nes",
                gameId = "encoded-control",
                root = encodedRoot,
                primary = true,
                groupId = null,
                groupKind = null,
            )
            val encodedSibling = game(
                entryKey = "$encodedSiblingRoot::encoded-sibling.nes",
                gameId = "encoded-sibling-control",
                root = encodedSiblingRoot,
                primary = true,
                groupId = null,
                groupKind = null,
            )
            val oddRows = oddRoots.mapIndexed { index, root ->
                game(
                    entryKey = "$root::odd-$index.nes",
                    gameId = "odd-$index",
                    root = root,
                    primary = true,
                    groupId = null,
                    groupKind = null,
                )
            }
            val legacyColonRoot = "/storage/ABCD-1234/Legacy:"
            val legacyColonSiblingRoot = "/storage/ABCD-1234/Legacy"
            val legacyColon = game(
                entryKey = "$legacyColonRoot::colon.nes",
                gameId = "legacy-colon",
                root = legacyColonRoot,
                primary = true,
                groupId = null,
                groupKind = null,
            ).copy(
                locationKind = "MANAGED_PATH",
                sourceDocumentUri = null,
                managedPath = "$legacyColonRoot/NES/colon.nes",
            )
            val legacyColonSibling = game(
                entryKey = "$legacyColonSiblingRoot::sibling.nes",
                gameId = "legacy-colon-sibling",
                root = legacyColonSiblingRoot,
                primary = true,
                groupId = null,
                groupKind = null,
            ).copy(
                locationKind = "MANAGED_PATH",
                sourceDocumentUri = null,
                managedPath = "$legacyColonSiblingRoot/NES/sibling.nes",
            )
            database.gameDao().upsertAll(
                targetRows + sibling + encoded + encodedSibling + oddRows +
                    legacyColon + legacyColonSibling,
            )

            val retainedKey = targetRows.last().entryKey
            assertEquals(
                PORTABLE_BATCH_PROBE_SIZE - 1,
                database.gameDao().pruneEntriesMissingFromSource(targetRoot, setOf(retainedKey)),
            )
            assertEquals(listOf(retainedKey), database.gameDao().entryKeysForSource(targetRoot))
            assertEquals(listOf(sibling.entryKey), database.gameDao().entryKeysForSource(siblingRoot))
            assertEquals(listOf(encoded.entryKey), database.gameDao().entryKeysForSource(encodedRoot))
            assertEquals(
                listOf(encodedSibling.entryKey),
                database.gameDao().entryKeysForSource(encodedSiblingRoot),
            )
            oddRows.forEachIndexed { index, row ->
                assertEquals(listOf(row.entryKey), database.gameDao().entryKeysForSource(oddRoots[index]))
            }
            assertEquals(
                listOf(legacyColon.entryKey),
                database.gameDao().entryKeysForSource(legacyColonRoot),
            )
            assertEquals(
                listOf(legacyColonSibling.entryKey),
                database.gameDao().entryKeysForSource(legacyColonSiblingRoot),
            )
            val queryPlan = exactRootQueryPlan(database, targetRoot)
            assertTrue("query plan=$queryPlan", queryPlan.any { "SEARCH" in it && "games" in it })
            assertTrue("query plan=$queryPlan", queryPlan.none { it.startsWith("SCAN") })
            assertTrue(
                "query plan=$queryPlan",
                queryPlan.any { "index_games_sourceRootUri_relativePath" in it },
            )
            assertEquals("ok", integrityCheck(database))
        }
    }

    @Test
    fun exactRootSecondDeleteBatchFaultRollsBackTheFirstBatchOnRoom(): Unit = runBlocking {
        withDatabase("exact-root-rollback") { _, database ->
            val root = "content://ep12/root/rollback"
            val rows = List(PORTABLE_BATCH_PROBE_SIZE) { index ->
                game(
                    entryKey = "$root::NES/${index.toString().padStart(4, '0')}.nes",
                    gameId = "rollback-$index",
                    root = root,
                    primary = true,
                    groupId = null,
                    groupKind = null,
                )
            }
            database.gameDao().upsertAll(rows)
            val secondBatchKey = rows[SINGLE_DELETE_BATCH_SIZE + 50].entryKey
            database.openHelper.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER ep12_fail_second_batch BEFORE DELETE ON games " +
                    "WHEN OLD.entryKey = '$secondBatchKey' " +
                    "BEGIN SELECT RAISE(ABORT, 'EP12 second batch fault'); END",
            )
            try {
                try {
                    database.gameDao().pruneEntriesMissingFromSource(root, emptySet())
                    fail("Expected the second delete batch to abort")
                } catch (_: android.database.sqlite.SQLiteException) {
                    // The @Transaction wrapper must restore the first 900 deletes.
                }
            } finally {
                database.openHelper.writableDatabase.execSQL(
                    "DROP TRIGGER IF EXISTS ep12_fail_second_batch",
                )
            }

            assertEquals(PORTABLE_BATCH_PROBE_SIZE, database.gameDao().entryKeysForSource(root).size)
            assertEquals("ok", integrityCheck(database))
        }
    }

    @Test
    fun generated1_8_32RootTopologiesPreserveExactPruneBoundaries(): Unit = runBlocking {
        listOf(1, 8, 32).forEach { rootCount ->
            withDatabase("root-topology-$rootCount") { _, database ->
                val walker = GeneratedSourceLister(ROOT_TOPOLOGY_ROWS, Order.FORWARD)
                val repository = repository(database, walker)
                val roots = List(rootCount) { index -> "content://ep12/root/$rootCount/$index" }

                roots.forEach { root ->
                    val first = repository.rescan(root)
                    assertEquals(ScanOutcome.Healthy, first.outcome)
                    assertEquals(ROOT_TOPOLOGY_ROWS, first.recognized)
                }

                val target = roots[rootCount / 2]
                walker.count = ROOT_TOPOLOGY_ROWS - 1
                walker.order = Order.REVERSE
                val pruned = repository.rescan(target)

                assertEquals(ScanOutcome.Healthy, pruned.outcome)
                assertEquals(ROOT_TOPOLOGY_ROWS - 1, pruned.recognized)
                roots.forEach { root ->
                    val expected = if (root == target) ROOT_TOPOLOGY_ROWS - 1 else ROOT_TOPOLOGY_ROWS
                    assertEquals(expected, database.gameDao().entryKeysForSource(root).size)
                }
                assertEquals(rootCount * ROOT_TOPOLOGY_ROWS - 1, database.gameDao().allEntryKeys().size)
                assertEquals("ok", integrityCheck(database))
            }
        }
    }

    @Test
    fun generatedSafProviderEnforces99999_100000_100001Boundary(): Unit = runBlocking {
        requireFullCapacityRun()
        val walker = SourceWalker(
            context,
            SourceWalkLimits(maxDepth = 1, maxEntries = CAPACITY_SIZE),
        )
        listOf(CAPACITY_SIZE - 1, CAPACITY_SIZE).forEach { count ->
            Ep03GeneratedDocumentsProvider.configure(count, Order.PERMUTED)
            val result = withTimeout(HARD_TIMEOUT_MILLIS) {
                walker.walk(Ep03GeneratedDocumentsProvider.TREE_URI.toString())
            }
            val files = (result as SourceWalkResult.Complete).files
            assertEquals(count, files.size)
            assertEquals(count, files.asSequence().map(WalkedFile::relativePath).distinct().count())
            assertEquals("000000.nes", files.first().relativePath)
            assertEquals(Ep03GeneratedFixtures.displayName(count - 1), files.last().relativePath)
        }

        Ep03GeneratedDocumentsProvider.configure(CAPACITY_SIZE + 1, Order.FORWARD)
        assertTrue(
            walker.walk(Ep03GeneratedDocumentsProvider.TREE_URI.toString()) is SourceWalkResult.Failed,
        )

        withDatabase("provider-limit") { _, database ->
            val root = Ep03GeneratedDocumentsProvider.TREE_URI.toString()
            val seeded = repository(database, GeneratedSourceLister(1, Order.FORWARD)).rescan(root)
            val oldSnapshot = database.gameDao().allEntryKeys()
            assertEquals(ScanOutcome.Healthy, seeded.outcome)
            assertEquals(1, seeded.recognized)
            assertEquals(listOf(physicalResidentKey(root, "000000.nes")), oldSnapshot)

            Ep03GeneratedDocumentsProvider.configure(CAPACITY_SIZE + 1, Order.FORWARD)
            val rejected = repository(database, walker).rescan(root)

            assertEquals(SourceFailureKind.FAILED, (rejected.outcome as ScanOutcome.Failed).kind)
            assertEquals(0, rejected.filesSeen)
            assertEquals(0, rejected.recognized)
            assertEquals(oldSnapshot, database.gameDao().allEntryKeys())
            assertEquals("ok", integrityCheck(database))
        }
    }

    @Test
    fun generated100kRecognitionRoomPublicationRescanAndReopenIsExact(): Unit = runBlocking {
        requireFullCapacityRun()
        settleHeapForCapacityMeasurement()
        val databaseName = "ep03-pipeline-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        var database = openDatabase(databaseName)
        try {
            val walker = GeneratedSourceLister(CAPACITY_SIZE, Order.FORWARD)
            val repository = repository(database, walker)
            val baselineFd = fileDescriptorCount()
            val baselineHeap = Ep03ResourceSampler.usedHeapBytes()
            val firstSample = Ep03ResourceSampler.measure {
                withTimeout(HARD_TIMEOUT_MILLIS) { repository.rescan(ROOT_CAPACITY) }
            }
            val first = firstSample.result
            assertEquals(CAPACITY_SIZE, first.filesSeen)
            assertEquals(CAPACITY_SIZE, first.recognized)
            assertEquals(CAPACITY_SIZE, first.groups)
            assertEquals(CAPACITY_SIZE, database.gameDao().entryKeysForSource(ROOT_CAPACITY).size)
            assertEquals(CAPACITY_SIZE, database.gameDao().visibleGameCount("nes"))
            val firstDigest = digest(database.gameDao().entryKeysForSource(ROOT_CAPACITY))

            walker.order = Order.REVERSE
            val rescanSample = Ep03ResourceSampler.measure {
                withTimeout(HARD_TIMEOUT_MILLIS) { repository.rescan(ROOT_CAPACITY) }
            }
            val second = rescanSample.result
            assertEquals(CAPACITY_SIZE, second.recognized)
            assertEquals(firstDigest, digest(database.gameDao().entryKeysForSource(ROOT_CAPACITY)))
            assertEquals("ok", integrityCheck(database))
            val databasePeakBytes = databaseFootprintBytes(databaseName)

            database.close()
            database = openDatabase(databaseName)
            assertEquals(CAPACITY_SIZE, database.gameDao().entryKeysForSource(ROOT_CAPACITY).size)
            assertEquals(firstDigest, digest(database.gameDao().entryKeysForSource(ROOT_CAPACITY)))
            assertEquals("ok", integrityCheck(database))
            val fdDelta = fileDescriptorCount() - baselineFd
            assertTrue("file-descriptor delta=$fdDelta", fdDelta <= 8)
            val maxHeap = Runtime.getRuntime().maxMemory()
            val peakHeap = maxOf(firstSample.peakHeapBytes, rescanSample.peakHeapBytes)
            assertTrue("database footprint=$databasePeakBytes", databasePeakBytes <= MAX_DATABASE_BYTES)
            assertTrue("first publication=${firstSample.elapsedMillis}ms", firstSample.elapsedMillis <= FIRST_BUDGET_MILLIS)
            assertTrue("no-change rescan=${rescanSample.elapsedMillis}ms", rescanSample.elapsedMillis <= RESCAN_BUDGET_MILLIS)
            settleHeapForCapacityMeasurement()
            val settledHeap = Ep03ResourceSampler.usedHeapBytes()
            assertTrue("settled heap=$settledHeap max=$maxHeap", settledHeap * 10L < maxHeap * 7L)
            val retainedDelta = (settledHeap - baselineHeap).coerceAtLeast(0L)
            Log.i(
                TAG,
                "EP03_100K firstMs=${firstSample.elapsedMillis} rescanMs=${rescanSample.elapsedMillis} " +
                    "peakHeapDiagnostic=$peakHeap settledHeap=$settledHeap maxHeap=$maxHeap " +
                    "retainedDelta=$retainedDelta " +
                    "databasePeak=$databasePeakBytes fdDelta=$fdDelta digest=$firstDigest",
            )
        } finally {
            if (database.isOpen) database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun generatedTwo100kRootsPruneOneAndPreserveSiblingEntryKeyDigest(): Unit = runBlocking {
        requireFullCapacityRun()
        settleHeapForCapacityMeasurement()
        val databaseName = "ep12-two-roots-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        var database = openDatabase(databaseName)
        try {
            val walker = GeneratedSourceLister(CAPACITY_SIZE, Order.FORWARD)
            val repository = repository(database, walker)
            val firstA = Ep03ResourceSampler.measure {
                withTimeout(HARD_TIMEOUT_MILLIS) { repository.rescan(ROOT_CAPACITY_A) }
            }
            val firstB = Ep03ResourceSampler.measure {
                withTimeout(HARD_TIMEOUT_MILLIS) { repository.rescan(ROOT_CAPACITY_B) }
            }
            assertEquals(CAPACITY_SIZE, firstA.result.recognized)
            assertEquals(CAPACITY_SIZE, firstB.result.recognized)
            assertEquals(CAPACITY_SIZE, database.gameDao().entryKeysForSource(ROOT_CAPACITY_A).size)
            val siblingDigest = digest(database.gameDao().entryKeysForSource(ROOT_CAPACITY_B))

            walker.count = CAPACITY_SIZE - 1
            walker.order = Order.REVERSE
            val rescanA = Ep03ResourceSampler.measure {
                withTimeout(HARD_TIMEOUT_MILLIS) { repository.rescan(ROOT_CAPACITY_A) }
            }
            assertEquals(CAPACITY_SIZE - 1, rescanA.result.recognized)
            assertEquals(CAPACITY_SIZE - 1, database.gameDao().entryKeysForSource(ROOT_CAPACITY_A).size)
            assertEquals(siblingDigest, digest(database.gameDao().entryKeysForSource(ROOT_CAPACITY_B)))
            assertEquals("ok", integrityCheck(database))

            database.close()
            database = openDatabase(databaseName)
            assertEquals(CAPACITY_SIZE - 1, database.gameDao().entryKeysForSource(ROOT_CAPACITY_A).size)
            assertEquals(siblingDigest, digest(database.gameDao().entryKeysForSource(ROOT_CAPACITY_B)))
            assertEquals("ok", integrityCheck(database))

            val peakHeap = maxOf(
                firstA.peakHeapBytes,
                firstB.peakHeapBytes,
                rescanA.peakHeapBytes,
            )
            val maxHeap = Runtime.getRuntime().maxMemory()
            val databaseBytes = databaseFootprintBytes(databaseName)
            settleHeapForCapacityMeasurement()
            val settledHeap = Ep03ResourceSampler.usedHeapBytes()
            assertTrue("settled heap=$settledHeap max=$maxHeap", settledHeap * 10L < maxHeap * 7L)
            assertTrue(
                "database footprint=$databaseBytes",
                databaseBytes <= MAX_TWO_ROOT_DATABASE_BYTES,
            )
            Log.i(
                TAG,
                "EP12_2X100K firstAMs=${firstA.elapsedMillis} firstBMs=${firstB.elapsedMillis} " +
                    "rescanAMs=${rescanA.elapsedMillis} peakHeapDiagnostic=$peakHeap " +
                    "settledHeap=$settledHeap maxHeap=$maxHeap databaseBytes=$databaseBytes " +
                    "siblingDigest=$siblingDigest",
            )
        } finally {
            if (database.isOpen) database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun repository(database: MimirDatabase, walker: SourceLister) = LibraryRepositoryImpl(
        gameDao = database.gameDao(),
        platformDao = database.platformDao(),
        sourceRootDao = database.sourceRootDao(),
        hiddenItemDao = database.hiddenItemDao(),
        favoriteDao = database.favoriteDao(),
        walker = walker,
        runScanTransaction = { block -> database.withTransaction { block() } },
        clock = { FIXED_TIMESTAMP },
    )

    private suspend fun withDatabase(
        label: String,
        block: suspend (String, MimirDatabase) -> Unit,
    ) {
        val name = "ep03-$label-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val database = openDatabase(name)
        try {
            block(name, database)
        } finally {
            if (database.isOpen) database.close()
            context.deleteDatabase(name)
        }
    }

    private fun openDatabase(name: String): MimirDatabase =
        Room.databaseBuilder(context, MimirDatabase::class.java, name).build()

    private fun game(
        entryKey: String,
        gameId: String,
        root: String,
        primary: Boolean,
        groupId: String?,
        groupKind: String?,
        contentKind: String = "GAME",
        discNumber: Int = 0,
    ): GameEntity {
        val prefix = "$root::"
        require(entryKey.startsWith(prefix)) { "capacity fixture key must identify its exact root" }
        val relativePath = entryKey.removePrefix(prefix)
        return GameEntity(
        entryKey = physicalResidentKey(root, relativePath),
        gameId = gameId,
        platformId = "nes",
        contentKind = contentKind,
        displayName = entryKey.substringAfterLast('/'),
        title = gameId,
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "SAF_TREE_GRANT",
        availability = "AVAILABLE",
        sizeBytes = 32_768L,
        contentHash = null,
        sourceRootUri = root,
        sourceDocumentUri = "content://ep03/${entryKey.hashCode()}",
        managedPath = null,
        relativePath = relativePath,
        discNumber = discNumber,
        duplicateGroupId = groupId,
        isPrimaryMember = primary,
        duplicateGroupKind = groupKind,
        )
    }

    private fun scalar(database: MimirDatabase, sql: String): Int =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun integrityCheck(database: MimirDatabase): String =
        database.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun exactRootQueryPlan(database: MimirDatabase, rootUri: String): List<String> =
        buildList {
            database.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT entryKey FROM games " +
                    "WHERE sourceRootUri = ? ORDER BY relativePath ASC, entryKey ASC",
                arrayOf(rootUri),
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(3))
            }
        }

    private fun terminalSeedQueryPlan(database: MimirDatabase, rootUri: String): List<String> =
        buildList {
            database.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT g.gameId, g.duplicateGroupId FROM games g " +
                    "WHERE g.sourceRootUri = ? " +
                    "AND (EXISTS (SELECT 1 FROM games t " +
                    "WHERE t.duplicateGroupKind IN ('EXACT_HASH', 'CATALOG_TITLE_ID') " +
                    "AND (t.gameId = g.gameId OR t.duplicateGroupId = g.gameId)) " +
                    "OR (g.duplicateGroupId IS NOT NULL AND EXISTS (SELECT 1 FROM games t " +
                    "WHERE t.duplicateGroupKind IN ('EXACT_HASH', 'CATALOG_TITLE_ID') " +
                    "AND (t.gameId = g.duplicateGroupId " +
                    "OR t.duplicateGroupId = g.duplicateGroupId))))",
                arrayOf(rootUri),
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(3))
            }
        }

    private fun terminalExpansionQueryPlan(database: MimirDatabase): List<String> =
        buildList {
            database.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT gameId, duplicateGroupId FROM games " +
                    "WHERE duplicateGroupKind IN ('EXACT_HASH', 'CATALOG_TITLE_ID') " +
                    "AND (gameId IN (?) OR duplicateGroupId IN (?))",
                arrayOf("winner", "winner"),
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(3))
            }
        }

    private suspend fun settleHeapForCapacityMeasurement() {
        repeat(2) {
            Runtime.getRuntime().gc()
            kotlinx.coroutines.delay(100)
        }
    }

    private fun logSqliteLimits(database: MimirDatabase) {
        val sqliteVersion = database.openHelper.readableDatabase.query("SELECT sqlite_version()").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        val compileOptions = buildList {
            database.openHelper.readableDatabase.query("PRAGMA compile_options").use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        Log.i(TAG, "EP03_SQLITE version=$sqliteVersion options=${compileOptions.joinToString()}")
    }

    private fun digest(keys: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        keys.sorted().forEach { key ->
            digest.update(key.toByteArray())
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun fileDescriptorCount(): Int =
        java.io.File("/proc/self/fd").list()?.size ?: 0

    private fun databaseFootprintBytes(databaseName: String): Long {
        val databaseFile = context.getDatabasePath(databaseName)
        return listOf(
            databaseFile,
            java.io.File("${databaseFile.path}-wal"),
            java.io.File("${databaseFile.path}-shm"),
        ).sumOf { file -> file.takeIf(java.io.File::exists)?.length() ?: 0L }
    }

    private fun requireFullCapacityRun() {
        val enabled = InstrumentationRegistry.getArguments().getString("ep03.capacity") == "true"
        assumeTrue("Run with -Pandroid.testInstrumentationRunnerArguments.ep03.capacity=true", enabled)
    }

    private class GeneratedSourceLister(
        var count: Int,
        var order: Order,
    ) : SourceLister {
        override suspend fun walk(rootUri: String): SourceWalkResult =
            SourceWalkResult.Complete(Ep03GeneratedFixtures.walkedFiles(count, order))
    }

    private companion object {
        const val TAG = "MimirEp03"
        const val ROOT_A = "content://ep03/root/A"
        const val ROOT_B = "content://ep03/root/B"
        const val ROOT_CAPACITY = "content://ep03/root/capacity"
        const val ROOT_CAPACITY_A = "content://ep12/root/capacity/A"
        const val ROOT_CAPACITY_B = "content://ep12/root/capacity/B"
        const val GROUP_COUNT = 2_001
        const val FANOUT = 1_000
        const val PORTABLE_BATCH_PROBE_SIZE = 1_001
        const val DOUBLE_GROUP_BATCH_SIZE = 400
        const val SINGLE_DELETE_BATCH_SIZE = 900
        const val ROOT_TOPOLOGY_ROWS = 12
        const val CAPACITY_SIZE = 100_000
        const val FIXED_TIMESTAMP = 1_753_248_000_000L
        const val HARD_TIMEOUT_MILLIS = 300_000L
        const val FIRST_BUDGET_MILLIS = 180_000L
        const val RESCAN_BUDGET_MILLIS = 90_000L
        const val MAX_DATABASE_BYTES = 400L * 1024L * 1024L
        const val MAX_TWO_ROOT_DATABASE_BYTES = 800L * 1024L * 1024L
    }
}
