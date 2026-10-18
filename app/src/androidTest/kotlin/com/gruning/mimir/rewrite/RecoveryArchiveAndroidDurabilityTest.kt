package com.gruning.mimir.rewrite

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_ARCHIVE_BYTES
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveTablesV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveValidator
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.RecoveryArchiveRestoreReader
import com.gruning.mimir.rewrite.core.storage.RecoveryArchiveV1SemanticValidator
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryArchiveAndroidDurabilityTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: MimirDatabase
    private lateinit var labEnvironment: RecoveryArchiveEnvironment

    @Before
    fun prepareLabState() {
        check(context.packageName == "com.gruning.mimir.rewrite.lab")
        assertProductionDefaultsAbsent()
        clearRecoveryArtifacts()
        context.deleteDatabase(LabDatabaseName)
        assertDatabaseFilesAbsent()
        database = Room.databaseBuilder(
            context,
            MimirDatabase::class.java,
            LabDatabaseName,
        ).build()
        labEnvironment = RecoveryArchiveEnvironment(
            database = { database },
            rootName = LabRecoveryRootName,
        )
        clearDatabase()
        assertProductionDefaultsAbsent()
    }

    @After
    fun cleanLabState() {
        clearRecoveryArtifacts()
        if (::database.isInitialized) {
            clearDatabase()
            database.close()
        }
        context.deleteDatabase(LabDatabaseName)
        assertDatabaseFilesAbsent()
        assertProductionDefaultsAbsent()
    }

    @Test
    fun populatedRoomV12ExportsAllTablesAndPublishesVerifiedThroughDocumentsProvider() = runBlocking {
        populateDatabase(metadataRows = 96, descriptionBytes = 512)
        val staged = stageLabArchive().getOrThrow()
        try {
            val validated = RecoveryArchiveValidator.validate(staged.result.archive).getOrThrow()
            assertEquals(12, validated.manifest.schemaVersion)
            val semantic = RecoveryArchiveV1SemanticValidator.validate(validated).getOrThrow()
            assertEquals(12, semantic.schemaVersion)
            val restored = RecoveryArchiveRestoreReader.read(validated).getOrThrow()
            assertEquals(12, restored.schemaVersion)
            val tableEntries = validated.manifest.entries.filter {
                it.path.startsWith("db/") && it.path.endsWith(".jsonl")
            }
            assertEquals(RecoveryArchiveTablesV1.paths.size, tableEntries.size)
            assertTrue(tableEntries.all { (it.rowCount ?: 0L) > 0L })
            assertTrue(
                validated.manifest.entries.any {
                    it.path == RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH
                },
            )

            val destination = createDestination(staged)
            val journal = labJournal()
            val result = RecoveryArchiveService.publish(
                staged = staged,
                createdDocument = destination.created,
                port = AndroidRecoveryPublicationPort(context),
                journal = journal,
            )

            assertEquals(RecoveryPublicationVerdict.VERIFIED, result.verdict)
            assertFalse(result.destinationMayRemain)
            assertNull(journal.read())
            context.contentResolver.openInputStream(destination.uri)!!.use { input ->
                assertTrue(input.read() >= 0)
            }
            assertTrue(DocumentsContract.deleteDocument(context.contentResolver, destination.uri))
        } finally {
            staged.runRoot.deleteRecursively()
        }
    }

    @Test
    fun unreadableDestinationRemainsWrittenUnverifiedAndDurableReplayPromotesIt() = runBlocking {
        populateDatabase(metadataRows = 64, descriptionBytes = 768)
        val staged = stageLabArchive().getOrThrow()
        try {
            val destination = createDestination(staged)
            val journal = labJournal()
            val delegate = AndroidRecoveryPublicationPort(context)
            val first = RecoveryArchiveService.publish(
                staged = staged,
                createdDocument = destination.created,
                port = object : RecoveryPublicationPort by delegate {
                    override fun openSource(destinationUri: String): InputStream? = null
                },
                journal = journal,
            )

            assertEquals(RecoveryPublicationVerdict.WRITTEN_UNVERIFIED, first.verdict)
            assertTrue(first.destinationMayRemain)
            assertEquals(staged.runId, journal.read()?.runId)
            assertTrue(journal.read()?.writeCompleted == true)

            val replay = RecoveryArchiveService.recoverPendingPublication(
                runRoot = staged.runRoot,
                port = delegate,
                journal = labJournal(),
                expectedRootName = LabRecoveryRootName,
            )

            assertNotNull(replay)
            assertEquals(RecoveryPublicationVerdict.VERIFIED, replay?.verdict)
            assertNull(labJournal().read())
            assertTrue(DocumentsContract.deleteDocument(context.contentResolver, destination.uri))
        } finally {
            staged.runRoot.deleteRecursively()
        }
    }

    @Test
    fun structuredCancellationAfterDescriptorWriteDeletesDestinationAndJournal() {
        runBlocking {
            populateDatabase(metadataRows = 320, descriptionBytes = 2_048)
            val staged = stageLabArchive().getOrThrow()
            try {
                val destination = createDestination(staged)
                val journal = labJournal()
                val firstWrite = CountDownLatch(1)
                val releaseWrite = CountDownLatch(1)
                val delegate = AndroidRecoveryPublicationPort(context)
                val blockingPort = BlockingAfterFirstWritePort(
                    delegate = delegate,
                    firstWrite = firstWrite,
                    releaseWrite = releaseWrite,
                )
                val publish = async(Dispatchers.Default) {
                    RecoveryArchiveService.publish(
                        staged = staged,
                        createdDocument = destination.created,
                        port = blockingPort,
                        journal = journal,
                    )
                }

                assertTrue(
                    "publication never reached the descriptor write boundary",
                    withContext(Dispatchers.IO) {
                        firstWrite.await(15, TimeUnit.SECONDS)
                    },
                )
                publish.cancel(CancellationException("EP-06A device cancellation fixture"))
                releaseWrite.countDown()
                val cancelled = runCatching { publish.await() }.exceptionOrNull()

                assertTrue(cancelled is CancellationException)
                assertNull(journal.read())
                assertFalse(documentExists(destination.uri))
            } finally {
                staged.runRoot.deleteRecursively()
            }
        }
    }

    @Test
    fun descriptorCloseFailureNeverEstablishesCompletedWriteEvidence() = runBlocking {
        populateDatabase(metadataRows = 64, descriptionBytes = 768)
        val staged = stageLabArchive().getOrThrow()
        try {
            val destination = createDestination(staged)
            val journal = labJournal()
            val delegate = AndroidRecoveryPublicationPort(context)
            val result = RecoveryArchiveService.publish(
                staged = staged,
                createdDocument = destination.created,
                port = CloseFailingPort(delegate),
                journal = journal,
            )

            assertEquals(RecoveryPublicationVerdict.FAILED, result.verdict)
            assertFalse(result.destinationMayRemain)
            assertNull(journal.read())
            assertFalse(documentExists(destination.uri))
        } finally {
            staged.runRoot.deleteRecursively()
        }
    }

    @Test
    fun providerOpenWriteAndReadbackFailuresNeverLeaveCompletedEvidence() = runBlocking {
        populateDatabase(metadataRows = 64, descriptionBytes = 768)
        val staged = stageLabArchive().getOrThrow()
        try {
            val journal = labJournal()
            val delegate = AndroidRecoveryPublicationPort(context)

            val openFailure = createDestination(staged)
            val openResult = RecoveryArchiveService.publish(
                staged = staged,
                createdDocument = openFailure.created,
                port = object : RecoveryPublicationPort by delegate {
                    override fun openSink(destinationUri: String): RecoveryPublicationSink? = null
                },
                journal = journal,
            )
            assertEquals(RecoveryPublicationVerdict.FAILED, openResult.verdict)
            assertFalse(openResult.destinationMayRemain)
            assertNull(journal.read())
            assertFalse(documentExists(openFailure.uri))

            val writeFailure = createDestination(staged)
            val writeResult = RecoveryArchiveService.publish(
                staged = staged,
                createdDocument = writeFailure.created,
                port = WriteFailingPort(delegate),
                journal = journal,
            )
            assertEquals(RecoveryPublicationVerdict.FAILED, writeResult.verdict)
            assertFalse(writeResult.destinationMayRemain)
            assertNull(journal.read())
            assertFalse(documentExists(writeFailure.uri))

            val readbackFailure = createDestination(staged)
            val readbackResult = RecoveryArchiveService.publish(
                staged = staged,
                createdDocument = readbackFailure.created,
                port = object : RecoveryPublicationPort by delegate {
                    override fun openSource(destinationUri: String): InputStream =
                        ByteArrayInputStream("not-a-recovery-archive".toByteArray())
                },
                journal = journal,
            )
            assertEquals(RecoveryPublicationVerdict.FAILED, readbackResult.verdict)
            assertFalse(readbackResult.destinationMayRemain)
            assertNull(journal.read())
            assertFalse(documentExists(readbackFailure.uri))
        } finally {
            staged.runRoot.deleteRecursively()
        }
    }

    @Test
    fun abandonedPostStageRunIsSweptWithoutDestinationOrJournal() = runBlocking {
        populateDatabase(metadataRows = 32, descriptionBytes = 256)
        val staged = stageLabArchive().getOrThrow()
        assertTrue(staged.runDirectory.isDirectory)
        assertNull(labJournal().read())

        assertTrue(staged.runDirectory.setLastModified(60_000L))
        assertTrue(staged.runDirectory.lastModified() in 1 until Long.MAX_VALUE)
        RecoveryArchiveService.sweepStaleStagingRoot(
            root = staged.runRoot,
            olderThanUtcMillis = Long.MAX_VALUE,
            journal = labJournal(),
        )

        assertFalse(staged.runDirectory.exists())
        assertNull(labJournal().read())
        assertFalse(
            File(context.filesDir, "recovery-lab-provider/$RecoveryArchiveSuggestedFileName")
                .exists(),
        )
    }

    @Test
    fun androidFreeSpaceAndArchiveSizeGuardsRefuseBeforeUnsafeWork() = runBlocking {
        val lowSpace = LowSpaceContext(context)
        val refused = RecoveryArchiveService.stage(lowSpace, labEnvironment)
        assertTrue(refused.isFailure)
        assertTrue(
            refused.exceptionOrNull()?.message.orEmpty().contains("not enough free space"),
        )
        assertFalse(File(lowSpace.cacheDir, LabRecoveryRootName).exists())

        val oversized = File(context.cacheDir, OversizedArchiveName)
        try {
            RandomAccessFile(oversized, "rw").use { file ->
                file.setLength(RECOVERY_MAX_ARCHIVE_BYTES + 1L)
            }
            val validation = RecoveryArchiveValidator.validate(oversized)
            assertTrue(validation.isFailure)
            assertTrue(
                validation.exceptionOrNull()?.message.orEmpty().contains("file size"),
            )
        } finally {
            oversized.delete()
            assertFalse(oversized.exists())
        }
    }

    @Test
    fun concurrentStageIsRefusedWhileDescriptorPublicationOwnsSingleFlight() = runBlocking {
        populateDatabase(metadataRows = 320, descriptionBytes = 2_048)
        val staged = stageLabArchive().getOrThrow()
        try {
            val destination = createDestination(staged)
            val journal = labJournal()
            val firstWrite = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val blockingPort = BlockingAfterFirstWritePort(
                delegate = AndroidRecoveryPublicationPort(context),
                firstWrite = firstWrite,
                releaseWrite = releaseWrite,
            )
            val publish = async(Dispatchers.Default) {
                RecoveryArchiveService.publish(
                    staged = staged,
                    createdDocument = destination.created,
                    port = blockingPort,
                    journal = journal,
                )
            }
            assertTrue(
                withContext(Dispatchers.IO) {
                    firstWrite.await(15, TimeUnit.SECONDS)
                },
            )

            val concurrent = stageLabArchive()
            assertTrue(concurrent.isFailure)
            assertTrue(
                concurrent.exceptionOrNull()?.message.orEmpty().contains("already running"),
            )

            releaseWrite.countDown()
            val published = publish.await()
            assertEquals(RecoveryPublicationVerdict.VERIFIED, published.verdict)
            assertNull(journal.read())
            assertTrue(DocumentsContract.deleteDocument(context.contentResolver, destination.uri))
        } finally {
            staged.runRoot.deleteRecursively()
        }
    }

    private fun createDestination(staged: StagedRecoveryArchive): LabDestination {
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

    private fun documentExists(uri: Uri): Boolean =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)

    private suspend fun stageLabArchive(): Result<StagedRecoveryArchive> =
        RecoveryArchiveService.stage(context, labEnvironment)

    private fun labJournal(): AndroidRecoveryPublicationJournal =
        AndroidRecoveryPublicationJournal(context, LabJournalPreferences)

    private fun clearRecoveryArtifacts() {
        context.deleteSharedPreferences(LabJournalPreferences)
        assertFalse(preferenceFile(LabJournalPreferences).exists())
        assertFalse(File(preferenceFile(LabJournalPreferences).path + ".bak").exists())
        val runRoot = File(context.cacheDir, LabRecoveryRootName)
        runRoot.deleteRecursively()
        assertFalse(runRoot.exists())
        val providerRoot = File(context.filesDir, "recovery-lab-provider")
        providerRoot.deleteRecursively()
        assertFalse(providerRoot.exists())
        val oversized = File(context.cacheDir, OversizedArchiveName)
        oversized.delete()
        assertFalse(oversized.exists())
    }

    private fun assertDatabaseFilesAbsent() {
        val databaseFile = context.getDatabasePath(LabDatabaseName)
        assertFalse(databaseFile.exists())
        assertFalse(File(databaseFile.path + "-wal").exists())
        assertFalse(File(databaseFile.path + "-shm").exists())
        assertFalse(File(databaseFile.path + "-journal").exists())
    }

    private fun assertProductionDefaultsAbsent() {
        val productionDatabase = context.getDatabasePath(MimirDatabase.DB_NAME)
        assertFalse(productionDatabase.exists())
        assertFalse(File(productionDatabase.path + "-wal").exists())
        assertFalse(File(productionDatabase.path + "-shm").exists())
        assertFalse(preferenceFile(ProductionJournalPreferences).exists())
        assertFalse(File(context.cacheDir, ProductionRecoveryRootName).exists())
    }

    private fun preferenceFile(name: String): File =
        File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")

    private fun clearDatabase() {
        if (!::database.isInitialized) return
        val sqlite = database.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            listOf(
                "identity_maintenance",
                "remap_journal",
                "game_id_aliases",
                "logical_game_groups",
                "title_metadata_cache",
                "bios_profiles",
                "launch_proofs",
                "home_shelf_items",
                "favorites",
                "emulator_overrides",
                "hidden_items",
                "games",
                "source_roots",
                "platforms",
            ).forEach { table ->
                sqlite.execSQL("DELETE FROM `$table`")
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
    }

    private fun populateDatabase(metadataRows: Int, descriptionBytes: Int) {
        val sqlite = database.openHelper.writableDatabase
        val gameId = "game:ep06a"
        val logicalGameId = "lg:ep06a"
        sqlite.beginTransaction()
        try {
            sqlite.execSQL(
                "INSERT INTO platforms(platformId,displayName,gamesCount,requiresBios,catalogLongId) " +
                    "VALUES(?,?,?,?,?)",
                arrayOf("gba", "Game Boy Advance", 1, 0, "game-boy-advance"),
            )
            sqlite.execSQL(
                "INSERT INTO source_roots(rootId,rootUri,locationKind,friendlyLabel,isPrimarySource,lastSeenMillis) " +
                    "VALUES(?,?,?,?,?,?)",
                arrayOf("root:ep06a", "content://lab/root", "SAF_TREE_GRANT", "Recovery Lab", 1, 10L),
            )
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
                    "entry:ep06a",
                    gameId,
                    "gba",
                    "ROM",
                    "Recovery Fixture.gba",
                    "Recovery Fixture",
                    "USA",
                    "1.0",
                    "AGBE",
                    "catalog:ep06a",
                    "HEADER",
                    "SAF_TREE_GRANT",
                    "AVAILABLE",
                    1_048_576L,
                    "a".repeat(64),
                    "content://lab/root",
                    "content://lab/document/game",
                    null,
                    "Recovery Fixture.gba",
                    0,
                    null,
                    1,
                    null,
                    "Recovery Fixture",
                    "catalog:ep06a",
                    "HEADER_PAYLOAD",
                    11L,
                ),
            )
            sqlite.execSQL(
                "INSERT INTO hidden_items(gameId,hidden,reason,duplicateGroupId,isPrimaryMember,hiddenAtMillis) " +
                    "VALUES(?,?,?,?,?,?)",
                arrayOf(gameId, 1, "EP06A_FIXTURE", null, 1, 12L),
            )
            sqlite.execSQL(
                "INSERT INTO emulator_overrides(gameId,emulatorId,setAtMillis) VALUES(?,?,?)",
                arrayOf(gameId, "retroarch-gba", 13L),
            )
            sqlite.execSQL(
                "INSERT INTO favorites(gameId,favoritedAtMillis) VALUES(?,?)",
                arrayOf(gameId, 14L),
            )
            sqlite.execSQL(
                "INSERT INTO home_shelf_items(profileId,itemKind,itemId,rank,addedAtMillis) VALUES(?,?,?,?,?)",
                arrayOf("default", "EMULATED_GAME", gameId, 0, 15L),
            )
            sqlite.execSQL(
                "INSERT INTO launch_proofs(gameId,androidPackageName,platformId,emulatorId,locator,outcome," +
                    "timestampMillis) VALUES(?,?,?,?,?,?,?)",
                arrayOf(gameId, null, "gba", "retroarch-gba", "content://lab/document/game", "DISPATCHED", 16L),
            )
            sqlite.execSQL(
                "INSERT INTO bios_profiles(platformId,emulatorScopeKey,packageName,fileName,required,presence," +
                    "expectedHash,expectedRelativePath) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(
                    "gba",
                    "retroarch-gba",
                    "com.retroarch",
                    "gba_bios.bin",
                    0,
                    "UNKNOWN",
                    null,
                    "bios/gba_bios.bin",
                ),
            )
            repeat(metadataRows) { index ->
                sqlite.execSQL(
                    """
                    INSERT INTO title_metadata_cache(
                        catalogKey,platformId,canonicalTitle,region,version,cachedAtMillis,canonicalSerial,
                        description,boxArtUri,screenshotUris,source,license,lastValidatedMillis,cacheExpiresAt
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent(),
                    arrayOf(
                        "catalog:ep06a:$index",
                        "gba",
                        "Recovery Fixture $index",
                        "USA",
                        "1.0",
                        20L + index,
                        "AGB${index.toString().padStart(5, '0')}",
                        deterministicText(index, descriptionBytes),
                        "content://lab/art/$index",
                        "[]",
                        "EP06A_LAB",
                        "TEST_ONLY",
                        30L + index,
                        40L + index,
                    ),
                )
            }
            sqlite.execSQL(
                "INSERT INTO logical_game_groups(logicalGameId,currentGameId,groupKind,memberless," +
                    "createdAtMillis,updatedAtMillis) VALUES(?,?,?,?,?,?)",
                arrayOf(logicalGameId, gameId, "EXACT_HASH", 0, 50L, 51L),
            )
            sqlite.execSQL(
                "INSERT INTO game_id_aliases(oldGameId,logicalGameId,firstSeenMillis,retiredAtMillis) " +
                    "VALUES(?,?,?,?)",
                arrayOf(gameId, logicalGameId, 52L, null),
            )
            sqlite.execSQL(
                "INSERT INTO remap_journal(logicalGameId,fromGameId,toGameId,reason,createdAtMillis," +
                    "storesCompletedMask,consumedAtMillis,lastError) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(logicalGameId, "game:old", gameId, "EXACT_HASH_FOLD", 53L, 31, 54L, null),
            )
            sqlite.execSQL(
                "INSERT INTO identity_maintenance(maintenanceKey,completedAtMillis) VALUES(?,?)",
                arrayOf("ep06a-lab", 55L),
            )
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
    }

    private fun deterministicText(seed: Int, size: Int): String {
        var state = seed.toLong() + 0x5deece66dL
        return CharArray(size) {
            state = (state * 2_862_933_555_777_941_757L + 3_037_000_493L)
            Alphabet[((state ushr 24) % Alphabet.size).toInt()]
        }.concatToString()
    }

    private data class LabDestination(
        val uri: Uri,
        val created: CreatedRecoveryDocument,
    )

    private class BlockingAfterFirstWritePort(
        private val delegate: RecoveryPublicationPort,
        private val firstWrite: CountDownLatch,
        private val releaseWrite: CountDownLatch,
    ) : RecoveryPublicationPort by delegate {
        override fun openSink(destinationUri: String): RecoveryPublicationSink? =
            delegate.openSink(destinationUri)?.let { sink ->
                object : RecoveryPublicationSink by sink {
                    private val blocked = AtomicBoolean(false)

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        sink.write(bytes, offset, length)
                        if (blocked.compareAndSet(false, true)) {
                            firstWrite.countDown()
                            if (!releaseWrite.await(15, TimeUnit.SECONDS)) {
                                throw IOException("EP-06A cancellation fixture timed out")
                            }
                        }
                    }
                }
            }
    }

    private class CloseFailingPort(
        private val delegate: RecoveryPublicationPort,
    ) : RecoveryPublicationPort by delegate {
        override fun openSink(destinationUri: String): RecoveryPublicationSink? =
            delegate.openSink(destinationUri)?.let { sink ->
                object : RecoveryPublicationSink by sink {
                    override fun close() {
                        sink.close()
                        throw IOException("EP-06A injected descriptor close failure")
                    }
                }
            }
    }

    private class WriteFailingPort(
        private val delegate: RecoveryPublicationPort,
    ) : RecoveryPublicationPort by delegate {
        override fun openSink(destinationUri: String): RecoveryPublicationSink? =
            delegate.openSink(destinationUri)?.let { sink ->
                object : RecoveryPublicationSink by sink {
                    private val failed = AtomicBoolean(false)

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        sink.write(bytes, offset, length)
                        if (failed.compareAndSet(false, true)) {
                            throw IOException("EP-06A injected descriptor write failure")
                        }
                    }
                }
            }
    }

    private class LowSpaceContext(base: Context) : ContextWrapper(base) {
        private val lowSpaceCache = object : File(base.cacheDir, "ep06a-low-space") {
            override fun getUsableSpace(): Long = 0L
        }

        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = lowSpaceCache
    }

    private companion object {
        val Alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()
        const val LabDatabaseName = "ep06a-recovery-lab.db"
        const val LabJournalPreferences = "ep06a_recovery_publication_lab"
        const val LabRecoveryRootName = "ep06a-recovery-export-lab"
        const val ProductionJournalPreferences = "mimir_recovery_publication"
        const val ProductionRecoveryRootName = "recovery-export"
        const val OversizedArchiveName = "ep06a-oversized-archive.zip"
    }
}
