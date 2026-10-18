package com.gruning.mimir.rewrite

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.ProfileId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.migration.MigrationGameRecord
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_COMPAT
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_CORE
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_DERIVED
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveCoverageV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveDescriptorV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveExclusionV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivePayloadSource
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveSnapshotV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveTablesV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveWriter
import com.gruning.mimir.rewrite.core.model.recovery.RestoreFavoriteOutcome
import com.gruning.mimir.rewrite.core.model.recovery.RestoreStorePlan
import com.gruning.mimir.rewrite.core.model.recovery.RestoreWillWrite
import com.gruning.mimir.rewrite.core.resolver.migration.MigrationMatcher
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.dao.HOME_SHELF_RANK_STEP
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryRestoreAndroidLabTest {
    private val instrumentationContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var sourceDatabase: MimirDatabase
    private lateinit var targetDatabase: MimirDatabase
    private lateinit var localGameId: String
    private lateinit var fixtureRecord: MigrationGameRecord

    @Before
    fun prepareLabState() {
        val context = instrumentationContext
        assertLabIsolation(context)
        clearOwnedLabArtifacts(context)
        context.deleteDatabase(SourceDatabaseName)
        context.deleteDatabase(TargetDatabaseName)
        assertDatabaseFilesAbsent(context, SourceDatabaseName)
        assertDatabaseFilesAbsent(context, TargetDatabaseName)

        fixtureRecord = MigrationGameRecord(
            platformId = "gba",
            fileName = "Ep06bRestore.gba",
            sizeBytes = 1_048_576L,
            relativePath = "Ep06bRestore.gba",
            contentHash = "d".repeat(64),
            serial = "EP06B",
            title = "Ep06b Restore",
            platformLabel = "Game Boy Advance",
        )
        localGameId = MigrationMatcher(gameExists = { false }).deriveGameId(fixtureRecord).value

        sourceDatabase = openLabDatabase(context, SourceDatabaseName)
        targetDatabase = openLabDatabase(context, TargetDatabaseName)
        populateSourceDatabase(sourceDatabase)
        populateTargetDatabase(targetDatabase)
        assertNotEquals(
            readSentinelStateBlocking(sourceDatabase).platformRequiresBios,
            readSentinelStateBlocking(targetDatabase).platformRequiresBios,
        )
        assertNotEquals(
            readSentinelStateBlocking(sourceDatabase).biosProfiles,
            readSentinelStateBlocking(targetDatabase).biosProfiles,
        )
        assertLabIsolation(context)
    }

    @After
    fun cleanLabState() {
        val context = instrumentationContext
        runCatching { sourceDatabase.close() }
        runCatching { targetDatabase.close() }
        context.deleteDatabase(SourceDatabaseName)
        context.deleteDatabase(TargetDatabaseName)
        assertDatabaseFilesAbsent(context, SourceDatabaseName)
        assertDatabaseFilesAbsent(context, TargetDatabaseName)
        clearOwnedLabArtifacts(context)
        assertLabIsolation(context)
    }

    @Test
    fun stagePlanApplyRestoresFourStoresWithExactCountsAndSparseShelfRank() = runBlocking {
        val context = instrumentationContext
        val archiveUri = publishSourceArchive(context)
        val environment = labRestoreEnvironment(context, importReadCounter = null)
        val staged = RecoveryRestoreService.stageImport(context, archiveUri, environment).getOrThrow()
        try {
            val prepared = planRestore(context, staged, environment).getOrThrow()
            assertEquals(1, prepared.plan.favorites.matched)
            assertEquals(1, prepared.plan.hidden.matched)
            assertEquals(1, prepared.plan.overrides.matched)
            assertEquals(1, prepared.plan.shelf.matched)
            assertEquals(4, prepared.plan.totalWouldWrite)

            val favoriteWrite = prepared.plan.favorites.decisions.single() as RestoreWillWrite
            assertEquals(GameId(localGameId), favoriteWrite.outcome.matchedLocalGameId)
            val hiddenWrite = prepared.plan.hidden.decisions.single() as RestoreWillWrite
            assertEquals(GameId(localGameId), hiddenWrite.outcome.matchedLocalGameId)
            val overrideWrite = prepared.plan.overrides.decisions.single() as RestoreWillWrite
            assertEquals(GameId(localGameId), overrideWrite.outcome.matchedLocalGameId)
            assertEquals("", overrideWrite.outcome.emulatorId)
            val shelfWrite = prepared.plan.shelf.decisions.single() as RestoreWillWrite
            assertEquals(HOME_SHELF_RANK_STEP, shelfWrite.outcome.plannedRank)
            assertEquals(
                HomeShelfItemId.Emulated(GameId(localGameId)),
                shelfWrite.outcome.itemId,
            )

            val baselineSentinels = readSentinelState(targetDatabase)
            val application = applyRestore(context, staged, prepared, environment).getOrThrow()
            assertEquals(1, application.result.favoritesRestored)
            assertEquals(1, application.result.hiddenRestored)
            assertEquals(1, application.result.overridesRestored)
            assertEquals(1, application.result.shelfRestored)
            assertEquals(4, application.result.totalRestored)
            assertNotNull(application.safetyArchive)
            assertTrue(
                "Mandatory safety archive must remain staged after restore",
                application.safetyArchive.result.archive.isFile,
            )

            val owner = readOwnerState(targetDatabase)
            assertEquals(listOf(localGameId to FavoriteAtMillis), owner.favorites)
            assertEquals(listOf(localGameId to HiddenAtMillis), owner.hidden)
            assertEquals(listOf(Triple(localGameId, "", OverrideAtMillis)), owner.overrides)
            assertEquals(
                listOf(Triple(ProfileId.LOCAL.value, localGameId, HOME_SHELF_RANK_STEP)),
                owner.shelf,
            )
            assertEquals(baselineSentinels, readSentinelState(targetDatabase))
        } finally {
            RecoveryRestoreService.discardImport(staged)
            deletePublishedArchive(context, archiveUri)
        }
    }

    @Test
    fun legacySchema9ArchiveApplyRestoresOwnerStateWithoutMutatingTargetBiosOrPlatformFlag() = runBlocking {
        val context = instrumentationContext
        val baselineSentinels = readSentinelState(targetDatabase)
        assertEquals(TargetPlatformRequiresBios.toLong(), baselineSentinels.platformRequiresBios)
        assertEquals(TargetSentinelBiosProfiles, baselineSentinels.biosProfiles)
        assertNotEquals(SourcePlatformRequiresBios.toLong(), baselineSentinels.platformRequiresBios)
        assertNotEquals(SourceArchiveBiosProfiles, baselineSentinels.biosProfiles)
        val archiveFile = buildSchema9RestoreArchive(context)
        val archiveUri = publishArchiveFile(context, archiveFile)
        val environment = labRestoreEnvironment(context, importReadCounter = null)
        val staged = RecoveryRestoreService.stageImport(context, archiveUri, environment).getOrThrow()
        try {
            val prepared = planRestore(context, staged, environment).getOrThrow()
            assertEquals(1, prepared.plan.favorites.matched)
            val application = applyRestore(context, staged, prepared, environment).getOrThrow()
            assertEquals(1, application.result.favoritesRestored)
            assertEquals(listOf(localGameId to FavoriteAtMillis), readOwnerState(targetDatabase).favorites)
            assertEquals(baselineSentinels, readSentinelState(targetDatabase))
        } finally {
            RecoveryRestoreService.discardImport(staged)
            deletePublishedArchive(context, archiveUri)
            archiveFile.delete()
            File(context.cacheDir, "ep06b-schema9-archive-payload").deleteRecursively()
        }
    }

    @Test
    fun sourceUriIsReadOnceAndNoPersistableGrantIsTaken() = runBlocking {
        val context = instrumentationContext
        val archiveUri = publishSourceArchive(context)
        val persistedGrantsBefore = context.contentResolver.persistedUriPermissions.size
        val readCounter = AtomicInteger(0)
        val environment = labRestoreEnvironment(context, importReadCounter = readCounter)
        val staged = RecoveryRestoreService.stageImport(context, archiveUri, environment).getOrThrow()
        try {
            val prepared = planRestore(context, staged, environment).getOrThrow()
            val application = applyRestore(context, staged, prepared, environment).getOrThrow()
            assertEquals(4, application.result.totalRestored)
            assertEquals(1, readCounter.get())
            assertEquals(persistedGrantsBefore, context.contentResolver.persistedUriPermissions.size)
        } finally {
            RecoveryRestoreService.discardImport(staged)
            deletePublishedArchive(context, archiveUri)
        }
    }

    @Test
    fun preparedReplayIsRefused() = runBlocking {
        val context = instrumentationContext
        val archiveUri = publishSourceArchive(context)
        val environment = labRestoreEnvironment(context, importReadCounter = null)
        val staged = RecoveryRestoreService.stageImport(context, archiveUri, environment).getOrThrow()
        try {
            val prepared = planRestore(context, staged, environment).getOrThrow()
            applyRestore(context, staged, prepared, environment).getOrThrow()

            val replay = applyRestore(context, staged, prepared, environment)
            assertTrue(replay.isFailure)
            assertTrue(
                replay.exceptionOrNull()?.message.orEmpty().contains("stale or was already consumed"),
            )
        } finally {
            RecoveryRestoreService.discardImport(staged)
            deletePublishedArchive(context, archiveUri)
        }
    }

    @Test
    fun replanCreatesFreshApprovalAndSecondApplyRestoresZero() = runBlocking {
        val context = instrumentationContext
        val archiveUri = publishSourceArchive(context)
        val environment = labRestoreEnvironment(context, importReadCounter = null)
        val staged = RecoveryRestoreService.stageImport(context, archiveUri, environment).getOrThrow()
        try {
            val firstPrepared = planRestore(context, staged, environment).getOrThrow()
            val firstApply = applyRestore(context, staged, firstPrepared, environment).getOrThrow()
            assertEquals(4, firstApply.result.totalRestored)

            val secondPrepared = planRestore(context, staged, environment).getOrThrow()
            assertNotEquals(firstPrepared.applyAuthorizationId, secondPrepared.applyAuthorizationId)
            val secondApply = applyRestore(context, staged, secondPrepared, environment).getOrThrow()
            assertEquals(0, secondApply.result.totalRestored)
            assertEquals(0, secondApply.result.favoritesRestored)
            assertEquals(0, secondApply.result.hiddenRestored)
            assertEquals(0, secondApply.result.overridesRestored)
            assertEquals(0, secondApply.result.shelfRestored)
        } finally {
            RecoveryRestoreService.discardImport(staged)
            deletePublishedArchive(context, archiveUri)
        }
    }

    @Test
    fun alteredPreparedPlanIsRefusedWhileExactApprovedPlanStillApplies() = runBlocking {
        val context = instrumentationContext
        val archiveUri = publishSourceArchive(context)
        val environment = labRestoreEnvironment(context, importReadCounter = null)
        val staged = RecoveryRestoreService.stageImport(context, archiveUri, environment).getOrThrow()
        try {
            val prepared = planRestore(context, staged, environment).getOrThrow()
            val archivedFavorite =
                (prepared.plan.favorites.decisions.single() as RestoreWillWrite).outcome.archived
            val forgedPlan = prepared.plan.copy(
                favorites = RestoreStorePlan(
                    decisions = prepared.plan.favorites.decisions +
                        RestoreWillWrite(
                            RestoreFavoriteOutcome(
                                archived = archivedFavorite,
                                normalizedArchivedGameId = "forged/game",
                                migrationRecord = fixtureRecord,
                                matchedLocalGameId = GameId(localGameId),
                            ),
                        ),
                ),
            )
            val forged = PreparedRecoveryRestore(
                plan = forgedPlan,
                runId = prepared.runId,
                importAuthorizationId = prepared.importAuthorizationId,
                applyAuthorizationId = prepared.applyAuthorizationId,
                manifestSha256 = prepared.manifestSha256,
            )
            val refused = applyRestore(context, staged, forged, environment)
            assertTrue(refused.isFailure)
            assertTrue(
                refused.exceptionOrNull()?.message.orEmpty().contains("stale or was already consumed"),
            )

            val ownerBeforeRetry = readOwnerState(targetDatabase)
            assertTrue(ownerBeforeRetry.isEmpty())

            val application = applyRestore(context, staged, prepared, environment).getOrThrow()
            assertEquals(4, application.result.totalRestored)
        } finally {
            RecoveryRestoreService.discardImport(staged)
            deletePublishedArchive(context, archiveUri)
        }
    }

    @Test
    fun wrongAuthorityManifestAndTamperRefuseBeforeAnyWrite() = runBlocking {
        val context = instrumentationContext
        val archiveUri = publishSourceArchive(context)
        val environment = labRestoreEnvironment(context, importReadCounter = null)
        val staged = RecoveryRestoreService.stageImport(context, archiveUri, environment).getOrThrow()
        try {
            val prepared = planRestore(context, staged, environment).getOrThrow()
            val baselineOwner = readOwnerState(targetDatabase)
            val baselineSentinels = readSentinelState(targetDatabase)
            assertTrue(baselineOwner.isEmpty())

            val wrongRun = prepared.withRunId(UUID.randomUUID().toString())
            assertRefusedWithZeroWrites(context, staged, wrongRun, environment, baselineOwner, baselineSentinels)

            val wrongImportAuth = prepared.withImportAuthorization(UUID.randomUUID().toString())
            assertRefusedWithZeroWrites(
                context,
                staged,
                wrongImportAuth,
                environment,
                baselineOwner,
                baselineSentinels,
            )

            val wrongApplyAuth = prepared.withApplyAuthorization(UUID.randomUUID().toString())
            assertRefusedWithZeroWrites(
                context,
                staged,
                wrongApplyAuth,
                environment,
                baselineOwner,
                baselineSentinels,
            )

            val wrongManifest = prepared.withManifestSha256("0".repeat(64))
            assertRefusedWithZeroWrites(
                context,
                staged,
                wrongManifest,
                environment,
                baselineOwner,
                baselineSentinels,
            )

            tamperStagedArchive(staged)
            val tamperedApply = applyRestore(context, staged, prepared, environment)
            assertTrue(tamperedApply.isFailure)
            assertOwnerAndSentinelsUnchanged(baselineOwner, baselineSentinels)

            val replanned = planRestore(context, staged, environment)
            assertTrue(replanned.isFailure)
            assertOwnerAndSentinelsUnchanged(baselineOwner, baselineSentinels)

            val wrongRootStaged = StagedRecoveryImport(
                runId = staged.runId,
                runRoot = File(context.cacheDir, WrongImportRootName).apply { mkdirs() },
                runDirectory = staged.runDirectory,
                archive = staged.archive,
                manifestSha256 = staged.manifestSha256,
                authorizationId = staged.authorizationId,
                rootName = staged.rootName,
            )
            val wrongRootPrepared = planRestore(context, wrongRootStaged, environment)
            assertTrue(wrongRootPrepared.isFailure)
            assertOwnerAndSentinelsUnchanged(baselineOwner, baselineSentinels)
        } finally {
            RecoveryRestoreService.discardImport(staged)
            deletePublishedArchive(context, archiveUri)
        }
    }

    @Test
    fun activeImportRunIsProtectedFromStaleSweep() = runBlocking {
        val context = instrumentationContext
        val archiveUri = publishSourceArchive(context)
        val environment = labRestoreEnvironment(context, importReadCounter = null)
        val staged = RecoveryRestoreService.stageImport(context, archiveUri, environment).getOrThrow()
        val staleRun = File(File(context.cacheDir, LabImportRootName), STALE_RUN_ID).apply {
            mkdirs()
            File(this, RecoveryArchiveFileName).writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            assertTrue(setLastModified(1L))
        }
        try {
            assertTrue(staged.runDirectory.isDirectory)
            RecoveryRestoreService.sweepStaleImports(
                context = context,
                olderThanUtcMillis = Long.MAX_VALUE,
                importRootName = LabImportRootName,
            )
            assertTrue("Active import staging must survive stale sweep", staged.runDirectory.isDirectory)
            assertFalse("Stale abandoned run must be swept", staleRun.exists())
        } finally {
            RecoveryRestoreService.discardImport(staged)
            deletePublishedArchive(context, archiveUri)
        }
    }

    @Test
    fun cancellationRemovesImportStagingAndWritesZero() = runBlocking {
        val context = instrumentationContext
        val archiveUri = publishSourceArchive(context)
        val gate = CountDownLatch(1)
        val release = CountDownLatch(1)
        val environment = labRestoreEnvironment(
            context = context,
            importReadCounter = null,
            importGate = gate,
            importRelease = release,
        )
        val baselineOwner = readOwnerState(targetDatabase)
        val baselineSentinels = readSentinelState(targetDatabase)
        val importJob = async(Dispatchers.Default) {
            RecoveryRestoreService.stageImport(context, archiveUri, environment)
        }
        assertTrue(
            "Import never reached the gate",
            withContext(Dispatchers.IO) { gate.await(15, TimeUnit.SECONDS) },
        )
        importJob.cancel(CancellationException("EP-06B restore import cancellation fixture"))
        release.countDown()
        val cancelled = runCatching { importJob.await() }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertFalse(File(context.cacheDir, LabImportRootName).exists())
        assertOwnerAndSentinelsUnchanged(baselineOwner, baselineSentinels)
        deletePublishedArchive(context, archiveUri)
    }

    @Test
    fun singleFlightRefusesConcurrentRestoreOperation() = runBlocking {
        val context = instrumentationContext
        val archiveUri = publishSourceArchive(context)
        val gate = CountDownLatch(1)
        val release = CountDownLatch(1)
        val environment = labRestoreEnvironment(
            context = context,
            importReadCounter = null,
            importGate = gate,
            importRelease = release,
        )
        val first = async(Dispatchers.Default) {
            RecoveryRestoreService.stageImport(context, archiveUri, environment)
        }
        assertTrue(
            withContext(Dispatchers.IO) { gate.await(15, TimeUnit.SECONDS) },
        )
        val concurrent = RecoveryRestoreService.stageImport(context, archiveUri, environment)
        assertTrue(concurrent.isFailure)
        assertTrue(
            concurrent.exceptionOrNull()?.message.orEmpty().contains("already running"),
        )
        release.countDown()
        val staged = first.await().getOrThrow()
        try {
            RecoveryRestoreService.discardImport(staged)
        } finally {
            deletePublishedArchive(context, archiveUri)
        }
    }

    @Test
    fun injectedRoomTransactionFailureRollsBackAcceptedWritesAndPreservesSentinels() = runBlocking {
        val context = instrumentationContext
        val archiveUri = publishSourceArchive(context)
        val baselineOwner = readOwnerState(targetDatabase)
        val baselineSentinels = readSentinelState(targetDatabase)
        val environment = labRestoreEnvironment(
            context = context,
            importReadCounter = null,
            runInTransaction = { database, block ->
                database.withTransaction {
                    block()
                    throw IllegalStateException("EP-06B injected Room transaction failure")
                }
            },
        )
        val staged = RecoveryRestoreService.stageImport(context, archiveUri, environment).getOrThrow()
        try {
            val prepared = planRestore(context, staged, environment).getOrThrow()
            val failed = applyRestore(context, staged, prepared, environment)
            assertTrue(failed.isFailure)
            assertOwnerAndSentinelsUnchanged(baselineOwner, baselineSentinels)
        } finally {
            RecoveryRestoreService.discardImport(staged)
            deletePublishedArchive(context, archiveUri)
        }
    }

    @Test
    fun cancellationAfterApplyBlockRollsBackAndRemovesImportStaging() = runBlocking {
        val context = instrumentationContext
        val archiveUri = publishSourceArchive(context)
        val baselineOwner = readOwnerState(targetDatabase)
        val baselineSentinels = readSentinelState(targetDatabase)
        val environment = labRestoreEnvironment(
            context = context,
            importReadCounter = null,
            runInTransaction = { database, block ->
                database.withTransaction {
                    block()
                    throw CancellationException("EP-06B injected cancellation after apply block")
                }
            },
        )
        val staged = RecoveryRestoreService.stageImport(context, archiveUri, environment).getOrThrow()
        try {
            val prepared = planRestore(context, staged, environment).getOrThrow()
            val cancelled = runCatching {
                applyRestore(context, staged, prepared, environment).getOrThrow()
            }.exceptionOrNull()
            assertTrue(cancelled is CancellationException)
            assertFalse(staged.runDirectory.exists())
            assertOwnerAndSentinelsUnchanged(baselineOwner, baselineSentinels)
        } finally {
            RecoveryRestoreService.discardImport(staged)
            deletePublishedArchive(context, archiveUri)
        }
    }

    private suspend fun assertRefusedWithZeroWrites(
        context: Context,
        staged: StagedRecoveryImport,
        prepared: PreparedRecoveryRestore,
        environment: RecoveryRestoreEnvironment,
        baselineOwner: OwnerStateSnapshot,
        baselineSentinels: SentinelStateSnapshot,
    ) {
        val refused = applyRestore(context, staged, prepared, environment)
        assertTrue(refused.isFailure)
        assertOwnerAndSentinelsUnchanged(baselineOwner, baselineSentinels)
    }

    private suspend fun assertOwnerAndSentinelsUnchanged(
        baselineOwner: OwnerStateSnapshot,
        baselineSentinels: SentinelStateSnapshot,
    ) {
        assertEquals(baselineOwner, readOwnerState(targetDatabase))
        assertEquals(baselineSentinels, readSentinelState(targetDatabase))
    }

    private suspend fun planRestore(
        context: Context,
        staged: StagedRecoveryImport,
        environment: RecoveryRestoreEnvironment,
    ) = RecoveryRestoreService.plan(
        context = context,
        staged = staged,
        environment = environment,
        hasGame = { gameId -> targetDatabase.gameDao().membersOfGame(gameId.value).isNotEmpty() },
        isEmulatorRecognized = { _, _, emulatorId -> emulatorId.isBlank() },
        isPackageInstalled = { false },
    )

    private suspend fun applyRestore(
        context: Context,
        staged: StagedRecoveryImport,
        prepared: PreparedRecoveryRestore,
        environment: RecoveryRestoreEnvironment,
    ) = RecoveryRestoreService.apply(
        context = context,
        staged = staged,
        prepared = prepared,
        environment = environment,
        isEmulatorRecognized = { _, emulatorId -> emulatorId.isBlank() },
        isPackageInstalled = { false },
    )

    private fun labRestoreEnvironment(
        context: Context,
        importReadCounter: AtomicInteger?,
        importGate: CountDownLatch? = null,
        importRelease: CountDownLatch? = null,
        runInTransaction: suspend (MimirDatabase, suspend () -> com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreResult) ->
            com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreResult =
            { database, block -> database.withTransaction { block() } },
    ): RecoveryRestoreEnvironment = RecoveryRestoreEnvironment(
        database = { targetDatabase },
        importRootName = LabImportRootName,
        safetyArchive = { appContext ->
            RecoveryArchiveService.stage(
                appContext,
                RecoveryArchiveEnvironment(
                    database = { targetDatabase },
                    rootName = LabSafetyExportRootName,
                ),
            )
        },
        openImportStream = { appContext, uri ->
            importGate?.let { gate ->
                gate.countDown()
                check(importRelease?.await(15, TimeUnit.SECONDS) == true) {
                    "EP-06B import gate timed out"
                }
            }
            importReadCounter?.incrementAndGet()
            appContext.contentResolver.openInputStream(uri)
        },
        runInTransaction = runInTransaction,
    )

    private suspend fun publishArchiveFile(context: Context, archiveFile: File): Uri {
        val authority = "${context.packageName}.recoverylab"
        val root = DocumentsContract.buildDocumentUri(
            authority,
            MimirLabRecoveryDocumentsProvider.RootDocumentId,
        )
        val uri = requireNotNull(
            DocumentsContract.createDocument(
                context.contentResolver,
                root,
                MimirLabRecoveryDocumentsProvider.RecoveryMimeType,
                RecoveryArchiveSuggestedFileName,
            ),
        )
        context.contentResolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output) { "Recovery lab provider rejected archive write" }
            archiveFile.inputStream().use { input -> input.copyTo(output) }
        }
        return uri
    }

    private fun buildSchema9RestoreArchive(context: Context): File {
        val gameLine = minimalArchiveGameLine(
            gameId = localGameId,
            entryKey = "entry:ep06b-restore",
            record = fixtureRecord,
        )
        val tableLines = mapOf(
            "db/favorites.jsonl" to listOf(
                """{"gameId":"$localGameId","favoritedAtMillis":$FavoriteAtMillis}""",
            ),
            "db/games.jsonl" to listOf(gameLine),
            "db/platforms.jsonl" to listOf(
                """{"platformId":"gba","displayName":"Game Boy Advance","gamesCount":1,"requiresBios":true}""",
            ),
            "db/bios_profiles.jsonl" to listOf(
                """{"platformId":"gba","fileName":"$ArchiveV9BiosFileName","required":true,"presence":"MISSING",""" +
                    """"expectedHash":"archive-v9-hash","expectedRelativePath":"bios/archive-v9.bin"}""",
            ),
        )
        val payloadRoot = File(context.cacheDir, "ep06b-schema9-archive-payload").apply {
            deleteRecursively()
            mkdirs()
        }
        val payloads = RecoveryArchiveTablesV1.paths.map { path ->
            val lines = tableLines[path].orEmpty()
            val file = File(payloadRoot, path).apply {
                parentFile?.mkdirs()
                writeText(lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"))
            }
            RecoveryArchivePayloadSource(
                path = path,
                contentClass = when (path) {
                    "db/title_metadata_cache.jsonl" -> RECOVERY_CONTENT_DERIVED
                    else -> RECOVERY_CONTENT_CORE
                },
                rowCount = lines.size.toLong(),
                file = file,
            )
        }.toMutableList()
        payloads += RecoveryArchivePayloadSource(
            path = RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH,
            contentClass = RECOVERY_CONTENT_COMPAT,
            file = File(payloadRoot, RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH).apply {
                parentFile?.mkdirs()
                writeText(compatibilityJson())
            },
        )
        val archiveFile = File(context.cacheDir, "ep06b-schema9-restore-archive.zip")
        archiveFile.delete()
        RecoveryArchiveWriter.writeNew(
            destination = archiveFile,
            descriptor = RecoveryArchiveDescriptorV1(
                createdAtUtcMillis = 1_753_270_000_000L,
                producedByApp = context.packageName,
                producedByVersionName = BuildConfig.VERSION_NAME,
                producedByVersionCode = BuildConfig.VERSION_CODE.toLong(),
                schemaVersion = 9,
                excluded = RecoveryArchiveCoverageV1.requiredExclusions.sorted().map {
                    RecoveryArchiveExclusionV1(
                        what = it,
                        reason = "Excluded by the Recovery Archive V1 contract.",
                    )
                },
                snapshot = RecoveryArchiveSnapshotV1(
                    transactional = true,
                    quiescent = false,
                    journalMode = "wal",
                ),
            ),
            payloads = payloads,
        ).getOrThrow()
        return archiveFile
    }

    private fun minimalArchiveGameLine(
        gameId: String,
        entryKey: String,
        record: MigrationGameRecord,
    ): String =
        """{"entryKey":"$entryKey","gameId":"$gameId","platformId":"${record.platformId}",""" +
            """"contentKind":"ROM","displayName":"${record.fileName}","title":"${record.title}",""" +
            """"serial":"${record.serial}","identitySource":"HEADER","locationKind":"SAF_TREE_GRANT",""" +
            """"availability":"AVAILABLE","sizeBytes":${record.sizeBytes},"contentHash":"${record.contentHash}",""" +
            """"relativePath":"${record.relativePath}","discNumber":0,"isPrimaryMember":true}"""

    private fun compatibilityJson(): String =
        "{\"bundleVersion\":1,\"kind\":\"mimir.migration.bundle\"," +
            "\"producedByApp\":\"$MIMIR_LAB_PACKAGE\"," +
            "\"producedByVersion\":\"0.1.0-alpha.1-lab\"," +
            "\"producedAtMillis\":1753270000000," +
            "\"sourceRoots\":[],\"favorites\":[],\"hidden\":[],\"emulatorOverrides\":[]}"

    private suspend fun publishSourceArchive(context: Context): Uri {
        val exportEnvironment = RecoveryArchiveEnvironment(
            database = { sourceDatabase },
            rootName = LabSourceExportRootName,
        )
        val stagedExport = RecoveryArchiveService.stage(context, exportEnvironment).getOrThrow()
        try {
            val destination = createProviderDestination(context, stagedExport)
            val result = RecoveryArchiveService.publish(
                staged = stagedExport,
                createdDocument = destination.created,
                port = AndroidRecoveryPublicationPort(context),
                journal = AndroidRecoveryPublicationJournal(context, LabJournalPreferences),
            )
            assertEquals(RecoveryPublicationVerdict.VERIFIED, result.verdict)
            return destination.uri
        } finally {
            stagedExport.runRoot.deleteRecursively()
        }
    }

    private fun createProviderDestination(
        context: Context,
        staged: StagedRecoveryArchive,
    ): LabDestination {
        val authority = "${context.packageName}.recoverylab"
        val root = DocumentsContract.buildDocumentUri(
            authority,
            MimirLabRecoveryDocumentsProvider.RootDocumentId,
        )
        val uri = requireNotNull(
            DocumentsContract.createDocument(
                context.contentResolver,
                root,
                MimirLabRecoveryDocumentsProvider.RecoveryMimeType,
                RecoveryArchiveSuggestedFileName,
            ),
        )
        val request = RecoveryArchiveService.beginDocumentCreation(staged).getOrThrow()
        val created = RecoveryArchiveService.acceptCreatedDocument(
            request,
            uri.toString(),
        ).getOrThrow()
        return LabDestination(uri, created)
    }

    private fun deletePublishedArchive(context: Context, uri: Uri) {
        runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    private fun tamperStagedArchive(staged: StagedRecoveryImport) {
        RandomAccessFile(staged.archive, "rw").use { file ->
            file.seek(file.length() - 1)
            file.write(0xFF)
        }
    }

    private fun openLabDatabase(context: Context, name: String): MimirDatabase =
        Room.databaseBuilder(context, MimirDatabase::class.java, name).build()

    private fun populateSourceDatabase(database: MimirDatabase) {
        val sqlite = database.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            insertPlatform(sqlite, requiresBios = SourcePlatformRequiresBios)
            insertGame(sqlite, localGameId)
            sqlite.execSQL(
                "INSERT INTO favorites(gameId,favoritedAtMillis) VALUES(?,?)",
                arrayOf(localGameId, FavoriteAtMillis),
            )
            sqlite.execSQL(
                "INSERT INTO hidden_items(gameId,hidden,reason,duplicateGroupId,isPrimaryMember,hiddenAtMillis) " +
                    "VALUES(?,?,?,?,?,?)",
                arrayOf(localGameId, 1, "USER_DISMISSED", null, 1, HiddenAtMillis),
            )
            sqlite.execSQL(
                "INSERT INTO emulator_overrides(gameId,emulatorId,setAtMillis) VALUES(?,?,?)",
                arrayOf(localGameId, "", OverrideAtMillis),
            )
            sqlite.execSQL(
                "INSERT INTO home_shelf_items(profileId,itemKind,itemId,rank,addedAtMillis) VALUES(?,?,?,?,?)",
                arrayOf(ProfileId.LOCAL.value, "EMULATED_GAME", localGameId, 0L, ShelfAddedAtMillis),
            )
            insertExcludedSentinels(
                sqlite = sqlite,
                gameId = localGameId,
                biosProfile = SourceArchiveBiosProfile,
            )
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
    }

    private fun populateTargetDatabase(database: MimirDatabase) {
        val sqlite = database.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            insertPlatform(sqlite, requiresBios = TargetPlatformRequiresBios)
            insertGame(sqlite, localGameId)
            insertExcludedSentinels(
                sqlite = sqlite,
                gameId = localGameId,
                biosProfile = TargetSentinelBiosProfile,
            )
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
    }

    private fun insertPlatform(sqlite: androidx.sqlite.db.SupportSQLiteDatabase, requiresBios: Int) {
        sqlite.execSQL(
            "INSERT INTO platforms(platformId,displayName,gamesCount,requiresBios,catalogLongId) " +
                "VALUES(?,?,?,?,?)",
            arrayOf("gba", "Game Boy Advance", 1, requiresBios, "game-boy-advance"),
        )
    }

    private fun insertGame(sqlite: androidx.sqlite.db.SupportSQLiteDatabase, gameId: String) {
        sqlite.execSQL(
            """
            INSERT INTO games(
                entryKey,gameId,platformId,contentKind,displayName,title,region,version,serial,
                titleCatalogKey,identitySource,locationKind,availability,sizeBytes,contentHash,
                sourceRootUri,sourceDocumentUri,managedPath,relativePath,discNumber,duplicateGroupId,
                isPrimaryMember,duplicateGroupKind,canonicalTitle,collapseKey,identityOrigin,
                identityResolvedAtMillis
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
            arrayOf(
                "entry:ep06b-restore",
                gameId,
                fixtureRecord.platformId,
                "ROM",
                fixtureRecord.fileName,
                fixtureRecord.title,
                "USA",
                "1.0",
                fixtureRecord.serial,
                "catalog:ep06b-restore",
                "HEADER",
                "SAF_TREE_GRANT",
                "AVAILABLE",
                fixtureRecord.sizeBytes,
                fixtureRecord.contentHash,
                "content://lab/source-root",
                "content://lab/source-document",
                null,
                fixtureRecord.relativePath,
                0,
                null,
                1,
                null,
                fixtureRecord.title,
                "catalog:ep06b-restore",
                "HEADER_PAYLOAD",
                11L,
            ),
        )
    }

    private fun insertExcludedSentinels(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        gameId: String,
        biosProfile: BiosProfileSnapshot,
    ) {
        sqlite.execSQL(
            "INSERT INTO source_roots(rootId,rootUri,locationKind,friendlyLabel,isPrimarySource,lastSeenMillis) " +
                "VALUES(?,?,?,?,?,?)",
            arrayOf(SentinelSourceRootId, "content://lab/sentinel-root", "SAF_TREE_GRANT", "Sentinel Root", 1, 77L),
        )
        sqlite.execSQL(
            "INSERT INTO launch_proofs(gameId,androidPackageName,platformId,emulatorId,locator,outcome," +
                "timestampMillis) VALUES(?,?,?,?,?,?,?)",
            arrayOf(gameId, null, "gba", "sentinel-emulator", "content://lab/sentinel-proof", "SENTINEL", 88L),
        )
        sqlite.execSQL(
            "INSERT INTO bios_profiles(platformId,emulatorScopeKey,packageName,fileName,required,presence," +
                "expectedHash,expectedRelativePath) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(
                biosProfile.platformId,
                biosProfile.emulatorScopeKey,
                biosProfile.packageName,
                biosProfile.fileName,
                if (biosProfile.required) 1 else 0,
                biosProfile.presence,
                biosProfile.expectedHash,
                biosProfile.expectedRelativePath,
            ),
        )
        sqlite.execSQL(
            """
            INSERT INTO title_metadata_cache(
                catalogKey,platformId,canonicalTitle,region,version,cachedAtMillis,canonicalSerial,
                description,boxArtUri,screenshotUris,source,license,lastValidatedMillis,cacheExpiresAt
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
            arrayOf(
                SentinelMetadataKey,
                "gba",
                "Sentinel Metadata",
                "USA",
                "1.0",
                90L,
                "SENTINEL",
                "sentinel-description",
                "content://lab/sentinel-art",
                "[]",
                "EP06B_SENTINEL",
                "TEST_ONLY",
                91L,
                92L,
            ),
        )
        sqlite.execSQL(
            "INSERT INTO remap_journal(logicalGameId,fromGameId,toGameId,reason,createdAtMillis," +
                "storesCompletedMask,consumedAtMillis,lastError) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf("lg:sentinel", "game:sentinel-old", gameId, "SENTINEL", 93L, 0, null, null),
        )
        sqlite.execSQL(
            "INSERT INTO identity_maintenance(maintenanceKey,completedAtMillis) VALUES(?,?)",
            arrayOf(SentinelMaintenanceKey, 94L),
        )
    }

    private fun PreparedRecoveryRestore.withRunId(runId: String): PreparedRecoveryRestore =
        PreparedRecoveryRestore(
            plan = plan,
            runId = runId,
            importAuthorizationId = importAuthorizationId,
            applyAuthorizationId = applyAuthorizationId,
            manifestSha256 = manifestSha256,
        )

    private fun PreparedRecoveryRestore.withImportAuthorization(
        importAuthorizationId: String,
    ): PreparedRecoveryRestore = PreparedRecoveryRestore(
        plan = plan,
        runId = runId,
        importAuthorizationId = importAuthorizationId,
        applyAuthorizationId = applyAuthorizationId,
        manifestSha256 = manifestSha256,
    )

    private fun PreparedRecoveryRestore.withApplyAuthorization(
        applyAuthorizationId: String,
    ): PreparedRecoveryRestore = PreparedRecoveryRestore(
        plan = plan,
        runId = runId,
        importAuthorizationId = importAuthorizationId,
        applyAuthorizationId = applyAuthorizationId,
        manifestSha256 = manifestSha256,
    )

    private fun PreparedRecoveryRestore.withManifestSha256(
        manifestSha256: String,
    ): PreparedRecoveryRestore = PreparedRecoveryRestore(
        plan = plan,
        runId = runId,
        importAuthorizationId = importAuthorizationId,
        applyAuthorizationId = applyAuthorizationId,
        manifestSha256 = manifestSha256,
    )

    private suspend fun readOwnerState(database: MimirDatabase): OwnerStateSnapshot =
        withContext(Dispatchers.IO) {
            val sqlite: SupportSQLiteDatabase = database.openHelper.readableDatabase
            OwnerStateSnapshot(
                favorites = sqlite.query(
                    "SELECT gameId, favoritedAtMillis FROM favorites ORDER BY gameId",
                ).use { cursor -> cursor.readPairs() },
                hidden = sqlite.query(
                    "SELECT gameId, hiddenAtMillis FROM hidden_items ORDER BY gameId",
                ).use { cursor -> cursor.readPairs() },
                overrides = sqlite.query(
                    "SELECT gameId, emulatorId, setAtMillis FROM emulator_overrides ORDER BY gameId",
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                Triple(
                                    cursor.getString(0),
                                    cursor.getString(1),
                                    cursor.getLong(2),
                                ),
                            )
                        }
                    }
                },
                shelf = sqlite.query(
                    "SELECT profileId, itemId, rank FROM home_shelf_items ORDER BY profileId, itemId",
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                        }
                    }
                },
            )
        }

    private fun readSentinelStateBlocking(database: MimirDatabase): SentinelStateSnapshot =
        runBlocking { readSentinelState(database) }

    private suspend fun readSentinelState(database: MimirDatabase): SentinelStateSnapshot =
        withContext(Dispatchers.IO) {
            val sqlite: SupportSQLiteDatabase = database.openHelper.readableDatabase
            SentinelStateSnapshot(
                sourceRootId = sqlite.stringScalar(
                    "SELECT rootId FROM source_roots WHERE rootId = ?",
                    arrayOf(SentinelSourceRootId),
                ),
                launchProofOutcome = sqlite.stringScalar(
                    "SELECT outcome FROM launch_proofs WHERE locator = ?",
                    arrayOf("content://lab/sentinel-proof"),
                ),
                platformRequiresBios = sqlite.longScalar(
                    "SELECT requiresBios FROM platforms WHERE platformId = ?",
                    arrayOf("gba"),
                ),
                biosProfiles = sqlite.biosProfilesList(
                    "SELECT platformId, emulatorScopeKey, packageName, fileName, required, presence, " +
                        "expectedHash, expectedRelativePath FROM bios_profiles " +
                        "ORDER BY platformId, emulatorScopeKey, fileName",
                ),
                metadataSource = sqlite.stringScalar(
                    "SELECT source FROM title_metadata_cache WHERE catalogKey = ?",
                    arrayOf(SentinelMetadataKey),
                ),
                remapReason = sqlite.stringScalar(
                    "SELECT reason FROM remap_journal WHERE logicalGameId = ?",
                    arrayOf("lg:sentinel"),
                ),
                maintenanceKey = sqlite.stringScalar(
                    "SELECT maintenanceKey FROM identity_maintenance WHERE maintenanceKey = ?",
                    arrayOf(SentinelMaintenanceKey),
                ),
            )
        }

    private fun Cursor.readPairs(): List<Pair<String, Long>> = buildList {
        while (moveToNext()) {
            add(getString(0) to getLong(1))
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.stringScalar(
        sql: String,
        args: Array<String>,
    ): String? = query(sql, args).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.longScalar(
        sql: String,
        args: Array<String>,
    ): Long = query(sql, args).use { cursor ->
        check(cursor.moveToFirst()) { "Expected scalar row for: $sql" }
        cursor.getLong(0)
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.biosProfilesList(
        sql: String,
        args: Array<String> = emptyArray(),
    ): List<BiosProfileSnapshot> = query(sql, args).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    BiosProfileSnapshot(
                        platformId = cursor.getString(0),
                        emulatorScopeKey = cursor.getString(1),
                        packageName = if (cursor.isNull(2)) null else cursor.getString(2),
                        fileName = cursor.getString(3),
                        required = cursor.getInt(4) != 0,
                        presence = cursor.getString(5),
                        expectedHash = if (cursor.isNull(6)) null else cursor.getString(6),
                        expectedRelativePath = if (cursor.isNull(7)) null else cursor.getString(7),
                    ),
                )
            }
        }
    }

    private fun clearOwnedLabArtifacts(context: Context) {
        context.deleteSharedPreferences(LabJournalPreferences)
        assertFalse(preferenceFile(context, LabJournalPreferences).exists())
        File(context.cacheDir, LabImportRootName).deleteRecursively()
        File(context.cacheDir, LabSourceExportRootName).deleteRecursively()
        File(context.cacheDir, LabSafetyExportRootName).deleteRecursively()
        File(context.cacheDir, WrongImportRootName).deleteRecursively()
        File(context.cacheDir, "ep06b-schema9-archive-payload").deleteRecursively()
        File(context.cacheDir, "ep06b-schema9-restore-archive.zip").delete()
        File(context.filesDir, "recovery-lab-provider").deleteRecursively()
    }

    private fun assertDatabaseFilesAbsent(context: Context, databaseName: String) {
        val databaseFile = context.getDatabasePath(databaseName)
        assertFalse(databaseFile.exists())
        assertFalse(File(databaseFile.path + "-wal").exists())
        assertFalse(File(databaseFile.path + "-shm").exists())
        assertFalse(File(databaseFile.path + "-journal").exists())
    }

    private fun assertLabIsolation(context: Context) {
        assertEquals(MIMIR_LAB_PACKAGE, context.packageName)
        assertTrue(context.applicationInfo.dataDir.endsWith("/$MIMIR_LAB_PACKAGE"))
        assertNotEquals(MimirDatabase.DB_NAME, SourceDatabaseName)
        assertNotEquals(MimirDatabase.DB_NAME, TargetDatabaseName)
        assertNotEquals(ProductionJournalPreferences, LabJournalPreferences)
        assertNotEquals(ProductionRecoveryExportRoot, LabSourceExportRootName)
        assertNotEquals(ProductionRecoveryExportRoot, LabSafetyExportRootName)
        assertNotEquals(ProductionRecoveryImportRoot, LabImportRootName)
        assertNotEquals(ProductionMediaRoot, "recovery-lab-provider")
    }

    private fun preferenceFile(context: Context, name: String): File =
        File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")

    private data class LabDestination(
        val uri: Uri,
        val created: CreatedRecoveryDocument,
    )

    private data class OwnerStateSnapshot(
        val favorites: List<Pair<String, Long>>,
        val hidden: List<Pair<String, Long>>,
        val overrides: List<Triple<String, String, Long>>,
        val shelf: List<Triple<String, String, Long>>,
    ) {
        fun isEmpty(): Boolean =
            favorites.isEmpty() && hidden.isEmpty() && overrides.isEmpty() && shelf.isEmpty()
    }

    private data class BiosProfileSnapshot(
        val platformId: String,
        val emulatorScopeKey: String,
        val packageName: String?,
        val fileName: String,
        val required: Boolean,
        val presence: String,
        val expectedHash: String?,
        val expectedRelativePath: String?,
    )

    private data class SentinelStateSnapshot(
        val sourceRootId: String?,
        val launchProofOutcome: String?,
        val platformRequiresBios: Long,
        val biosProfiles: List<BiosProfileSnapshot>,
        val metadataSource: String?,
        val remapReason: String?,
        val maintenanceKey: String?,
    )

    private companion object {
        const val SourceDatabaseName = "ep06b-restore-source-lab.db"
        const val TargetDatabaseName = "ep06b-restore-target-lab.db"
        const val TargetPlatformRequiresBios = 0
        const val SourcePlatformRequiresBios = 1
        const val ArchiveV9BiosFileName = "archive_v9_bios.bin"
        val TargetSentinelBiosProfile = BiosProfileSnapshot(
            platformId = "gba",
            emulatorScopeKey = "sentinel-emulator",
            packageName = "com.sentinel.emulator",
            fileName = "sentinel_bios.bin",
            required = false,
            presence = "UNKNOWN",
            expectedHash = null,
            expectedRelativePath = "bios/sentinel.bin",
        )
        val TargetSentinelBiosProfiles = listOf(TargetSentinelBiosProfile)
        val SourceArchiveBiosProfile = BiosProfileSnapshot(
            platformId = "gba",
            emulatorScopeKey = "source-archive-emulator",
            packageName = "com.source.archive.emulator",
            fileName = "source_archive_bios.bin",
            required = true,
            presence = "PRESENT",
            expectedHash = "source-archive-hash",
            expectedRelativePath = "bios/source-archive.bin",
        )
        val SourceArchiveBiosProfiles = listOf(SourceArchiveBiosProfile)
        const val LabImportRootName = "ep06b-recovery-import-lab"
        const val WrongImportRootName = "ep06b-recovery-import-wrong-lab"
        const val LabSourceExportRootName = "ep06b-recovery-export-source-lab"
        const val LabSafetyExportRootName = "ep06b-recovery-export-safety-lab"
        const val LabJournalPreferences = "ep06b_recovery_restore_publication_lab"
        const val ProductionJournalPreferences = "mimir_recovery_publication"
        const val ProductionRecoveryExportRoot = "recovery-export"
        const val ProductionRecoveryImportRoot = "recovery-import"
        const val ProductionMediaRoot = "identity-media"
        const val SentinelSourceRootId = "root:ep06b-sentinel"
        const val SentinelMetadataKey = "catalog:ep06b-sentinel"
        const val SentinelMaintenanceKey = "ep06b-restore-sentinel"
        const val STALE_RUN_ID = "00000000-0000-0000-0000-00000000906b"
        const val FavoriteAtMillis = 101L
        const val HiddenAtMillis = 102L
        const val OverrideAtMillis = 103L
        const val ShelfAddedAtMillis = 104L
    }
}
