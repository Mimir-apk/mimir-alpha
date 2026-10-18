package com.gruning.mimir.rewrite.core.resolver.library

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.model.library.ArtSource
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.entity.SourceReconcileRootEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceReconcileRunEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceRootEntity
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResumableReconcileDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun preP2PolicyLedgerIsDiscardedAndEveryFrozenRootRestarts() = runBlocking {
        val databaseName = "ep14-policy-restart-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(databaseName)
        try {
            seedRoots(database, listOf("A"))
            val runId = "legacy-v10-run"
            database.sourceReconcileDao().upsertRun(
                SourceReconcileRunEntity(
                    runId = runId,
                    policyStamp = "ep11-v1",
                    ownerToken = "dead-process",
                    startedAtMillis = 1L,
                    resumeCount = 1,
                    sourcesChecked = 1,
                    healthySources = 1,
                    removedSources = 0,
                    unavailableSources = 0,
                    failedSources = 0,
                    quarantinedSources = 0,
                    filesSeen = 1,
                    recognized = 1,
                    groups = 1,
                    skipped = 0,
                    resumedWithDrift = false,
                    driftedSources = 0,
                ),
            )
            database.sourceReconcileDao().upsertRoot(
                SourceReconcileRootEntity(
                    runId = runId,
                    rootId = "/virtual/A",
                    rootUri = "/virtual/A",
                    ordinal = 0,
                    plannedPrimary = true,
                    phase = "SETTLED",
                    outcome = "HEALTHY",
                    attempts = 1,
                    rootSeenMillis = 10L,
                    settledAtMillis = 2L,
                ),
            )
            val walker = CountingLister()

            val result = repository(
                database,
                walker,
                AtomicLong(20L)::getAndIncrement,
            ).reconcileSources()

            assertFalse(result.resumedFromCrash)
            assertEquals(0, result.skippedAlreadyReconciled)
            assertEquals(1, walker.calls["/virtual/A"])
            assertNull(database.sourceReconcileDao().activeRun())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun cancellationKeepsSettledPrefixAndResumeReturnsWholeReceipt() = runBlocking {
        val databaseName = "ep11-cancel-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(databaseName)
        try {
            seedRoots(database, listOf("A", "B"))
            val blocked = CompletableDeferred<Unit>()
            val firstWalker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult {
                    if (rootUri.endsWith("/B")) {
                        blocked.complete(Unit)
                        awaitCancellation()
                    }
                    return complete(rootUri)
                }
            }
            val first = async {
                repository(database, firstWalker, AtomicLong(20L)::getAndIncrement)
                    .reconcileSources()
            }
            withTimeout(5_000) { blocked.await() }
            first.cancelAndJoin()

            val active = requireNotNull(database.sourceReconcileDao().activeRun())
            val interrupted = database.sourceReconcileDao().rootsForRun(active.runId)
            assertEquals(listOf("SETTLED", "PENDING"), interrupted.map { it.phase })
            assertEquals(listOf("HEALTHY", null), interrupted.map { it.outcome })
            assertEquals(1, active.healthySources)

            val resumedWalker = CountingLister()
            val result = repository(
                database,
                resumedWalker,
                AtomicLong(40L)::getAndIncrement,
            ).reconcileSources()

            assertEquals(2, result.sourcesChecked)
            assertEquals(2, result.healthySources)
            assertEquals(2, result.filesSeen)
            assertEquals(2, result.recognized)
            assertEquals(2, result.groups)
            assertEquals(0, result.skipped)
            assertEquals(1, result.skippedAlreadyReconciled)
            assertTrue(result.resumedFromCrash)
            assertEquals(0, resumedWalker.calls["/virtual/A"] ?: 0)
            assertEquals(1, resumedWalker.calls["/virtual/B"])
            assertNull(database.sourceReconcileDao().activeRun())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun cancellationImmediatelyAfterRunningCommitResetsPendingWithoutCrashAttempt() = runBlocking {
        val databaseName = "ep11-running-boundary-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(databaseName)
        try {
            seedRoots(database, listOf("A"))
            val transactions = AtomicInteger()
            val error = runCatching {
                repository(
                    database = database,
                    walker = CountingLister(),
                    clock = AtomicLong(20L)::getAndIncrement,
                    runTransaction = { block ->
                        database.withTransaction { block() }
                        if (transactions.incrementAndGet() == 2) {
                            throw CancellationException("after RUNNING commit")
                        }
                    },
                ).reconcileSources()
            }.exceptionOrNull()

            assertTrue(error is CancellationException)
            val run = requireNotNull(database.sourceReconcileDao().activeRun())
            val root = database.sourceReconcileDao().rootsForRun(run.runId).single()
            assertEquals("PENDING", root.phase)
            assertEquals(0, root.attempts)

            val resumed = repository(
                database,
                CountingLister(),
                AtomicLong(40L)::getAndIncrement,
            ).reconcileSources()
            assertEquals(1, resumed.healthySources)
            assertEquals(0, resumed.quarantinedSources)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun cancellationAfterHealthyCommitStillRefreshesMediaAndKeepsSettledReceipt() = runBlocking {
        val databaseName = "ep11-post-commit-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(databaseName)
        try {
            seedRoots(database, listOf("A"))
            val transactions = AtomicInteger()
            val invalidations = AtomicInteger()
            val artSource = object : ArtSource {
                override fun artUriFor(
                    sourceDocumentUri: String?,
                    managedPath: String?,
                ): String? = null

                override fun invalidateAll() {
                    invalidations.incrementAndGet()
                }
            }
            val error = runCatching {
                repository(
                    database = database,
                    walker = CountingLister(),
                    clock = AtomicLong(20L)::getAndIncrement,
                    artSource = artSource,
                    runTransaction = { block ->
                        database.withTransaction { block() }
                        if (transactions.incrementAndGet() == 3) {
                            throw CancellationException("after root+ledger commit")
                        }
                    },
                ).reconcileSources()
            }.exceptionOrNull()

            assertTrue(error is CancellationException)
            assertEquals(1, invalidations.get())
            val run = requireNotNull(database.sourceReconcileDao().activeRun())
            val root = database.sourceReconcileDao().rootsForRun(run.runId).single()
            assertEquals("SETTLED", root.phase)
            assertEquals("HEALTHY", root.outcome)

            val resumedWalker = CountingLister()
            val resumed = repository(
                database,
                resumedWalker,
                AtomicLong(40L)::getAndIncrement,
            ).reconcileSources()
            assertEquals(1, resumed.healthySources)
            assertEquals(1, resumed.skippedAlreadyReconciled)
            assertEquals(0, resumedWalker.calls["/virtual/A"] ?: 0)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun sameUriMutationDuringMissingWalkRetriesWithoutDeletingReplacement() = runBlocking {
        val databaseName = "ep11-stale-missing-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(databaseName)
        try {
            seedRoots(database, listOf("A"))
            val mutations = SourceRootMutationCoordinator()
            val calls = AtomicInteger()
            val walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult {
                    if (calls.incrementAndGet() == 1) {
                        mutations.applyPresenceSnapshot(
                            absentRootUris = emptyList(),
                            presentRootUris = listOf(rootUri),
                            mutation = {
                                val replacement = requireNotNull(
                                    database.sourceRootDao().allOnce()
                                        .firstOrNull { it.rootId == rootUri },
                                )
                                database.sourceRootDao().upsert(
                                    replacement.copy(lastSeenMillis = 99L),
                                )
                            },
                        )
                        return SourceWalkResult.Missing("stale old-incarnation result")
                    }
                    return complete(rootUri)
                }
            }

            val result = repository(
                database = database,
                walker = walker,
                clock = AtomicLong(100L)::getAndIncrement,
                sourceRootMutations = mutations,
            ).reconcileSources()

            assertEquals(2, calls.get())
            assertEquals(1, result.healthySources)
            assertEquals(0, result.removedSources)
            assertEquals(0, result.unavailableSources)
            assertEquals(0, result.failedSources)
            assertNotNull(
                database.sourceRootDao().allOnce().firstOrNull { it.rootId == "/virtual/A" },
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun terminalReceiptCountsUnplannedRootAsDriftWithoutAdoptingIt() = runBlocking {
        val databaseName = "ep11-terminal-drift-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(databaseName)
        try {
            seedRoots(database, listOf("A", "B"))
            val calls = linkedMapOf<String, Int>()
            val walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult {
                    calls[rootUri] = (calls[rootUri] ?: 0) + 1
                    if (rootUri == "/virtual/A") {
                        database.sourceRootDao().upsert(
                            SourceRootEntity(
                                rootId = "/virtual/C",
                                rootUri = "/virtual/C",
                                locationKind = "MANAGED_PATH",
                                friendlyLabel = "C",
                                isPrimarySource = false,
                                lastSeenMillis = 15L,
                            ),
                        )
                    }
                    return complete(rootUri)
                }
            }

            val result = repository(
                database,
                walker,
                AtomicLong(20L)::getAndIncrement,
            ).reconcileSources()

            assertEquals(2, result.sourcesChecked)
            assertEquals(2, result.healthySources)
            assertTrue(result.resumedWithDrift)
            assertEquals(1, result.driftedSources)
            assertEquals(0, calls["/virtual/C"] ?: 0)
            assertTrue(database.sourceRootDao().allOnce().any { it.rootId == "/virtual/C" })
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun changedSettledWitnessAtTerminalRestartsTheWholeFrozenPlan() = runBlocking {
        val databaseName = "ep11-terminal-witness-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(databaseName)
        try {
            seedRoots(database, listOf("A", "B"))
            val calls = linkedMapOf<String, Int>()
            var mutated = false
            val walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult {
                    calls[rootUri] = (calls[rootUri] ?: 0) + 1
                    if (rootUri == "/virtual/B" && !mutated) {
                        mutated = true
                        val settledA = requireNotNull(
                            database.sourceRootDao().allOnce()
                                .firstOrNull { it.rootId == "/virtual/A" },
                        )
                        database.sourceRootDao().upsert(
                            settledA.copy(lastSeenMillis = 99L),
                        )
                    }
                    return complete(rootUri)
                }
            }

            val result = repository(
                database,
                walker,
                AtomicLong(20L)::getAndIncrement,
            ).reconcileSources()

            assertEquals(2, calls["/virtual/A"])
            assertEquals(2, calls["/virtual/B"])
            assertEquals(2, result.healthySources)
            assertEquals(0, result.skippedAlreadyReconciled)
            assertNull(database.sourceReconcileDao().activeRun())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun standaloneRescanInvalidatesPausedAggregateBeforeItsOwnCommit() = runBlocking {
        val databaseName = "ep11-standalone-invalidate-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(databaseName)
        try {
            seedRoots(database, listOf("A", "B"))
            val blocked = CompletableDeferred<Unit>()
            val first = async {
                repository(
                    database = database,
                    walker = object : SourceLister {
                        override suspend fun walk(rootUri: String): SourceWalkResult {
                            if (rootUri == "/virtual/B") {
                                blocked.complete(Unit)
                                awaitCancellation()
                            }
                            return complete(rootUri)
                        }
                    },
                    clock = AtomicLong(20L)::getAndIncrement,
                ).reconcileSources()
            }
            withTimeout(5_000) { blocked.await() }
            first.cancelAndJoin()
            assertNotNull(database.sourceReconcileDao().activeRun())

            val standalone = repository(
                database,
                CountingLister(),
                AtomicLong(40L)::getAndIncrement,
            ).rescan("/virtual/B")
            assertEquals(com.gruning.mimir.rewrite.core.model.library.ScanOutcome.Healthy, standalone.outcome)
            assertNull(database.sourceReconcileDao().activeRun())

            val freshWalker = CountingLister()
            val fresh = repository(
                database,
                freshWalker,
                AtomicLong(60L)::getAndIncrement,
            ).reconcileSources()
            assertEquals(2, fresh.healthySources)
            assertFalse(fresh.resumedFromCrash)
            assertEquals(0, fresh.skippedAlreadyReconciled)
            assertEquals(1, freshWalker.calls["/virtual/A"])
            assertEquals(1, freshWalker.calls["/virtual/B"])
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun cancellationDuringStalePlanInvalidationCannotLeaveRunningBehind() = runBlocking {
        val databaseName = "ep11-stale-cancel-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(databaseName)
        try {
            seedRoots(database, listOf("A"))
            val mutations = SourceRootMutationCoordinator()
            val transactions = AtomicInteger()
            val invalidationCommitted = CompletableDeferred<Unit>()
            val walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult {
                    mutations.applyPresenceSnapshot(
                        absentRootUris = emptyList(),
                        presentRootUris = listOf(rootUri),
                        mutation = {},
                    )
                    return complete(rootUri)
                }
            }
            val reconcile = async {
                repository(
                    database = database,
                    walker = walker,
                    clock = AtomicLong(20L)::getAndIncrement,
                    sourceRootMutations = mutations,
                    runTransaction = { block ->
                        database.withTransaction { block() }
                        if (transactions.incrementAndGet() == 3) {
                            invalidationCommitted.complete(Unit)
                            delay(150)
                        }
                    },
                ).reconcileSources()
            }

            withTimeout(5_000) { invalidationCommitted.await() }
            reconcile.cancelAndJoin()

            assertNull(database.sourceReconcileDao().activeRun())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun mixedTypedOutcomesSettleTruthfullyWithoutElectingAReplacementPrimary() = runBlocking {
        val databaseName = "ep11-mixed-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(databaseName)
        try {
            seedRoots(database, listOf("A", "B", "C", "D"))
            val walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult = when {
                    rootUri.endsWith("/A") -> SourceWalkResult.Missing("confirmed absent fixture")
                    rootUri.endsWith("/B") -> complete(rootUri)
                    rootUri.endsWith("/C") -> SourceWalkResult.Unavailable("detached fixture")
                    else -> SourceWalkResult.Failed("provider fixture")
                }
            }

            val result = repository(
                database,
                walker,
                AtomicLong(20L)::getAndIncrement,
            ).reconcileSources()

            assertEquals(4, result.sourcesChecked)
            assertEquals(1, result.healthySources)
            assertEquals(1, result.unavailableSources)
            assertEquals(1, result.failedSources)
            assertEquals(1, result.removedSources)
            assertEquals(
                setOf("/virtual/B", "/virtual/C", "/virtual/D"),
                database.sourceRootDao().allOnce().map { it.rootId }.toSet(),
            )
            assertTrue(database.sourceRootDao().allOnce().none { it.isPrimarySource })
            assertNull(database.sourceReconcileDao().activeRun())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun threeRealProcessDeathsQuarantineOnlyTheHostileRootAndReachTheTail() = runBlocking {
        val databaseName = "ep11-death-${UUID.randomUUID()}.db"
        val markerName = "$databaseName.marker"
        val marker = File(context.filesDir, markerName)
        marker.delete()
        context.deleteDatabase(databaseName)
        val seeded = openDatabase(databaseName)
        try {
            seedRoots(seeded, listOf("A", "B", "C", "D"))
        } finally {
            seeded.close()
        }

        repeat(3) { deathIndex ->
            killProbeAtReconcileRoot(databaseName, markerName, marker)
            val checkpoint = openDatabase(databaseName)
            try {
                val run = requireNotNull(checkpoint.sourceReconcileDao().activeRun())
                val roots = checkpoint.sourceReconcileDao().rootsForRun(run.runId)
                assertEquals(listOf("SETTLED", "SETTLED", "RUNNING", "PENDING"), roots.map { it.phase })
                assertEquals(deathIndex, roots.single { it.rootId == "/virtual/C" }.attempts)
                assertEquals(2, run.healthySources)
            } finally {
                checkpoint.close()
            }
        }

        val database = openDatabase(databaseName)
        try {
            val finalWalker = CountingLister()
            val result = repository(
                database,
                finalWalker,
                AtomicLong(40L)::getAndIncrement,
            ).reconcileSources()

            assertEquals(4, result.sourcesChecked)
            assertEquals(3, result.healthySources)
            assertEquals(1, result.failedSources)
            assertEquals(1, result.quarantinedSources)
            assertEquals(2, result.skippedAlreadyReconciled)
            assertTrue(result.resumedFromCrash)
            assertEquals(0, finalWalker.calls["/virtual/A"] ?: 0)
            assertEquals(0, finalWalker.calls["/virtual/B"] ?: 0)
            assertEquals(0, finalWalker.calls["/virtual/C"] ?: 0)
            assertEquals(1, finalWalker.calls["/virtual/D"])
            assertNull(database.sourceReconcileDao().activeRun())
            assertTrue(database.sourceRootDao().allOnce().any { it.rootId == "/virtual/C" })
        } finally {
            database.close()
            marker.delete()
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun killProbeAtReconcileRoot(
        databaseName: String,
        markerName: String,
        marker: File,
    ) {
        marker.delete()
        val stage = ScanProcessDeathService.Stage.DURING_RECONCILE_ROOT
        val intent = Intent(context, ScanProcessDeathService::class.java)
            .putExtra(ScanProcessDeathService.EXTRA_DATABASE_NAME, databaseName)
            .putExtra(ScanProcessDeathService.EXTRA_MARKER_NAME, markerName)
            .putExtra(ScanProcessDeathService.EXTRA_STAGE, stage.name)
        assertNotNull(context.startService(intent))
        withTimeout(30_000) {
            while (true) {
                val text = marker.takeIf(File::exists)?.readText().orEmpty()
                val state = text.substringAfter('|', "")
                if (state == stage.name) break
                if (state.startsWith("ERROR_")) throw AssertionError(state)
                delay(25)
            }
        }
        val probePid = marker.readText().substringBefore('|').toInt()
        assertTrue("probe must run in a secondary process", probePid != Process.myPid())
        context.stopService(intent)
        Process.killProcess(probePid)
        withTimeout(5_000) {
            while (File("/proc/$probePid").exists()) delay(25)
        }
    }

    private suspend fun seedRoots(database: MimirDatabase, names: List<String>) {
        database.withTransaction {
            names.forEachIndexed { index, name ->
                val rootUri = "/virtual/$name"
                database.sourceRootDao().upsert(
                    SourceRootEntity(
                        rootId = rootUri,
                        rootUri = rootUri,
                        locationKind = "MANAGED_PATH",
                        friendlyLabel = name,
                        isPrimarySource = index == 0,
                        lastSeenMillis = 10L,
                    ),
                )
            }
        }
    }

    private fun repository(
        database: MimirDatabase,
        walker: SourceLister,
        clock: () -> Long,
        sourceRootMutations: SourceRootMutationCoordinator = SourceRootMutationCoordinator(),
        artSource: ArtSource = ArtSource { _, _ -> null },
        runTransaction: suspend (suspend () -> Unit) -> Unit = { block ->
            database.withTransaction { block() }
        },
    ) = LibraryRepositoryImpl(
        gameDao = database.gameDao(),
        platformDao = database.platformDao(),
        sourceRootDao = database.sourceRootDao(),
        hiddenItemDao = database.hiddenItemDao(),
        favoriteDao = database.favoriteDao(),
        artSource = artSource,
        walker = walker,
        runScanTransaction = runTransaction,
        clock = clock,
        gameIdentityDao = database.gameIdentityDao(),
        sourceRootMutations = sourceRootMutations,
        sourceReconcileDao = database.sourceReconcileDao(),
    )

    private fun openDatabase(name: String): MimirDatabase =
        Room.databaseBuilder(context, MimirDatabase::class.java, name).build()

    private fun complete(rootUri: String): SourceWalkResult {
        val suffix = rootUri.substringAfterLast('/')
        return SourceWalkResult.Complete(
            listOf(
                WalkedFile(
                    displayName = "$suffix.gba",
                    relativePath = "GBA/$suffix.gba",
                    sizeBytes = 2L * 1024 * 1024,
                    documentUri = null,
                    filePath = "$rootUri/GBA/$suffix.gba",
                ),
            ),
        )
    }

    private inner class CountingLister : SourceLister {
        val calls = linkedMapOf<String, Int>()

        override suspend fun walk(rootUri: String): SourceWalkResult {
            calls[rootUri] = (calls[rootUri] ?: 0) + 1
            return complete(rootUri)
        }
    }
}
