package com.gruning.mimir.rewrite

import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryArchiveMidCopyCrashPrepareDeviceTest {
    @Test
    fun processDiesAfterFirstDescriptorWriteWithPrewriteJournalDurable() = runBlocking {
        RecoveryProcessRestartFixture.preparePublicationCrash(
            labContext(),
            RecoveryRestartBoundary.MID_COPY,
        )
    }
}

@RunWith(AndroidJUnit4::class)
class RecoveryArchiveMidCopyRestartReplayDeviceTest {
    @Test
    fun freshProcessReplaysExactStagedArchiveAndRemovesAllLabState() = runBlocking {
        RecoveryProcessRestartFixture.replayPublicationCrash(
            labContext(),
            RecoveryRestartBoundary.MID_COPY,
        )
    }
}

@RunWith(AndroidJUnit4::class)
class RecoveryArchivePreCloseCrashPrepareDeviceTest {
    @Test
    fun processDiesAfterFinalWriteBeforeSyncOrClose() = runBlocking {
        RecoveryProcessRestartFixture.preparePublicationCrash(
            labContext(),
            RecoveryRestartBoundary.PRE_CLOSE,
        )
    }
}

@RunWith(AndroidJUnit4::class)
class RecoveryArchivePreCloseRestartReplayDeviceTest {
    @Test
    fun freshProcessReconcilesCompleteUnclosedDestination() = runBlocking {
        RecoveryProcessRestartFixture.replayPublicationCrash(
            labContext(),
            RecoveryRestartBoundary.PRE_CLOSE,
        )
    }
}

@RunWith(AndroidJUnit4::class)
class RecoveryArchivePreJournalCrashPrepareDeviceTest {
    @Test
    fun processDiesAfterDescriptorCloseBeforeCompletedJournalStamp() = runBlocking {
        RecoveryProcessRestartFixture.preparePublicationCrash(
            labContext(),
            RecoveryRestartBoundary.PRE_JOURNAL,
        )
    }
}

@RunWith(AndroidJUnit4::class)
class RecoveryArchivePreJournalRestartReplayDeviceTest {
    @Test
    fun freshProcessReconcilesClosedDestinationWithoutCompletedStamp() = runBlocking {
        RecoveryProcessRestartFixture.replayPublicationCrash(
            labContext(),
            RecoveryRestartBoundary.PRE_JOURNAL,
        )
    }
}

@RunWith(AndroidJUnit4::class)
class RecoveryArchivePostStageCrashPrepareDeviceTest {
    @Test
    fun processDiesAfterStagingBeforeDocumentCreation() {
        RecoveryProcessRestartFixture.preparePostStageCrash(labContext())
    }
}

@RunWith(AndroidJUnit4::class)
class RecoveryArchivePostStageRestartSweepDeviceTest {
    @Test
    fun freshProcessSweepsAbandonedStagingWithoutPublication() {
        RecoveryProcessRestartFixture.replayPostStageCrash(labContext())
    }
}

private fun labContext(): Context =
    InstrumentationRegistry.getInstrumentation().targetContext

private enum class RecoveryRestartBoundary(
    val key: String,
) {
    MID_COPY("mid-copy"),
    PRE_CLOSE("pre-close"),
    PRE_JOURNAL("pre-journal"),
    POST_STAGE("post-stage"),
}

private object RecoveryProcessRestartFixture {
    const val RootName = "ep06a-recovery-restart-lab"
    const val JournalPreferences = "ep06a_recovery_restart_journal"
    const val ControlPreferences = "ep06a_recovery_restart_control"
    private const val ArchiveName = "archive.zip"

    suspend fun preparePublicationCrash(
        context: Context,
        boundary: RecoveryRestartBoundary,
    ) {
        require(boundary != RecoveryRestartBoundary.POST_STAGE)
        requireInertLab(context)
        clean(context)
        val staged = createStagedArchive(context)
        val destination = createDestination(context, staged)
        val control = control(context)
        assertTrue(
            control.edit()
                .putInt("prepare_pid", Process.myPid())
                .putString("planned_boundary", boundary.key)
                .putString("destination_uri", destination.uri.toString())
                .putLong("staged_archive_bytes", staged.result.archive.length())
                .commit(),
        )
        val delegate = AndroidRecoveryPublicationPort(context)
        val crashingPort = when (boundary) {
            RecoveryRestartBoundary.MID_COPY ->
                killAfterFirstWritePort(context, boundary, delegate)

            RecoveryRestartBoundary.PRE_CLOSE ->
                killAfterFinalWritePort(
                    context = context,
                    boundary = boundary,
                    delegate = delegate,
                    expectedBytes = staged.result.archive.length(),
                )

            RecoveryRestartBoundary.PRE_JOURNAL ->
                killAfterClosePort(context, boundary, delegate)

            RecoveryRestartBoundary.POST_STAGE -> error("Post-stage has no publication port.")
        }
        RecoveryArchiveService.publish(
            staged = staged,
            createdDocument = destination.created,
            port = crashingPort,
            journal = journal(context),
        )
        fail("The lab process survived its injected ${boundary.key} boundary.")
    }

    suspend fun replayPublicationCrash(
        context: Context,
        boundary: RecoveryRestartBoundary,
    ) {
        require(boundary != RecoveryRestartBoundary.POST_STAGE)
        requireInertLab(context)
        val control = control(context)
        val preparePid = control.getInt("prepare_pid", -1)
        val destinationUri = control.getString("destination_uri", null)
        val stagedArchiveBytes = control.getLong("staged_archive_bytes", -1L)
        assertEquals(boundary.key, control.getString("planned_boundary", null))
        assertEquals(boundary.key, control.getString("observed_boundary", null))
        assertTrue(preparePid > 0)
        assertNotEquals(preparePid, Process.myPid())
        assertNotNull(destinationUri)
        assertTrue(stagedArchiveBytes > 0L)

        val destinationBytes = documentSize(context, Uri.parse(destinationUri))
        if (boundary == RecoveryRestartBoundary.MID_COPY) {
            assertTrue(destinationBytes > 0L)
            assertTrue(destinationBytes < stagedArchiveBytes)
        } else {
            assertEquals(stagedArchiveBytes, destinationBytes)
        }

        val journal = journal(context)
        val pending = journal.read()
        assertNotNull(pending)
        assertFalse(pending!!.writeCompleted)
        assertEquals(destinationUri, pending.destinationUri)

        val replay = RecoveryArchiveService.recoverPendingPublication(
            runRoot = runRoot(context),
            port = AndroidRecoveryPublicationPort(context),
            journal = journal,
            expectedRootName = RootName,
        )
        assertEquals(RecoveryPublicationVerdict.VERIFIED, replay?.verdict)
        assertNull(journal.read())
        assertEquals(stagedArchiveBytes, documentSize(context, Uri.parse(destinationUri)))
        assertTrue(
            DocumentsContract.deleteDocument(
                context.contentResolver,
                Uri.parse(destinationUri),
            ),
        )
        cleanAndAssert(context)
    }

    fun preparePostStageCrash(context: Context) {
        requireInertLab(context)
        clean(context)
        val staged = createStagedArchive(context)
        assertTrue(
            control(context).edit()
                .putInt("prepare_pid", Process.myPid())
                .putString("planned_boundary", RecoveryRestartBoundary.POST_STAGE.key)
                .putString("staged_run_id", staged.runId)
                .putLong("staged_archive_bytes", staged.result.archive.length())
                .commit(),
        )
        markBoundaryAndKill(context, RecoveryRestartBoundary.POST_STAGE)
    }

    fun replayPostStageCrash(context: Context) {
        requireInertLab(context)
        val control = control(context)
        val preparePid = control.getInt("prepare_pid", -1)
        val runId = control.getString("staged_run_id", null)
        assertEquals(
            RecoveryRestartBoundary.POST_STAGE.key,
            control.getString("planned_boundary", null),
        )
        assertEquals(
            RecoveryRestartBoundary.POST_STAGE.key,
            control.getString("observed_boundary", null),
        )
        assertTrue(preparePid > 0)
        assertNotEquals(preparePid, Process.myPid())
        assertNotNull(runId)
        val runDirectory = File(runRoot(context), runId!!)
        assertTrue(runDirectory.isDirectory)
        assertTrue(File(runDirectory, ArchiveName).isFile)
        assertNull(journal(context).read())
        assertFalse(providerArchive(context).exists())
        assertTrue(runDirectory.setLastModified(60_000L))
        RecoveryArchiveService.sweepStaleStagingRoot(
            root = runRoot(context),
            olderThanUtcMillis = Long.MAX_VALUE,
            journal = journal(context),
        )
        assertFalse(runDirectory.exists())
        assertNull(journal(context).read())
        assertFalse(providerArchive(context).exists())
        cleanAndAssert(context)
    }

    private fun control(context: Context) =
        context.getSharedPreferences(ControlPreferences, Context.MODE_PRIVATE)

    private fun providerArchive(context: Context): File =
        File(providerRoot(context), RecoveryArchiveSuggestedFileName)

    private fun cleanAndAssert(context: Context) {
        clean(context)
        assertFalse(runRoot(context).exists())
        assertFalse(providerRoot(context).exists())
        assertFalse(preferenceFile(context, JournalPreferences).exists())
        assertFalse(preferenceFile(context, ControlPreferences).exists())
        requireInertLab(context)
    }

    private fun markBoundaryAndKill(
        context: Context,
        boundary: RecoveryRestartBoundary,
    ): Nothing {
        check(
            control(context).edit()
                .putString("observed_boundary", boundary.key)
                .commit(),
        )
        Process.killProcess(Process.myPid())
        throw AssertionError("Process.killProcess returned at the ${boundary.key} boundary.")
    }

    private fun killAfterFirstWritePort(
        context: Context,
        boundary: RecoveryRestartBoundary,
        delegate: RecoveryPublicationPort,
    ): RecoveryPublicationPort = object : RecoveryPublicationPort by delegate {
        override fun openSink(destinationUri: String): RecoveryPublicationSink? =
            delegate.openSink(destinationUri)?.let { sink ->
                object : RecoveryPublicationSink by sink {
                    private val killed = AtomicBoolean(false)

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        sink.write(bytes, offset, length)
                        if (killed.compareAndSet(false, true)) {
                            markBoundaryAndKill(context, boundary)
                        }
                    }
                }
            }
    }

    private fun killAfterFinalWritePort(
        context: Context,
        boundary: RecoveryRestartBoundary,
        delegate: RecoveryPublicationPort,
        expectedBytes: Long,
    ): RecoveryPublicationPort = object : RecoveryPublicationPort by delegate {
        override fun openSink(destinationUri: String): RecoveryPublicationSink? =
            delegate.openSink(destinationUri)?.let { sink ->
                object : RecoveryPublicationSink by sink {
                    private val killed = AtomicBoolean(false)
                    private var writtenBytes = 0L

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        sink.write(bytes, offset, length)
                        writtenBytes = Math.addExact(writtenBytes, length.toLong())
                        check(writtenBytes <= expectedBytes)
                        if (writtenBytes == expectedBytes && killed.compareAndSet(false, true)) {
                            markBoundaryAndKill(context, boundary)
                        }
                    }
                }
            }
    }

    private fun killAfterClosePort(
        context: Context,
        boundary: RecoveryRestartBoundary,
        delegate: RecoveryPublicationPort,
    ): RecoveryPublicationPort = object : RecoveryPublicationPort by delegate {
        override fun openSink(destinationUri: String): RecoveryPublicationSink? =
            delegate.openSink(destinationUri)?.let { sink ->
                object : RecoveryPublicationSink by sink {
                    private val killed = AtomicBoolean(false)

                    override fun close() {
                        sink.close()
                        if (killed.compareAndSet(false, true)) {
                            markBoundaryAndKill(context, boundary)
                        }
                    }
                }
            }
    }

    fun requireInertLab(context: Context) {
        check(context.packageName == MIMIR_LAB_PACKAGE)
        val productionDatabase = context.getDatabasePath(MimirDatabase.DB_NAME)
        assertFalse(productionDatabase.exists())
        assertFalse(File(productionDatabase.path + "-wal").exists())
        assertFalse(File(productionDatabase.path + "-shm").exists())
        assertFalse(preferenceFile(context, "mimir_recovery_publication").exists())
        assertFalse(File(context.cacheDir, "recovery-export").exists())
    }

    fun journal(context: Context): AndroidRecoveryPublicationJournal =
        AndroidRecoveryPublicationJournal(context, JournalPreferences)

    fun runRoot(context: Context): File = File(context.cacheDir, RootName)

    fun providerRoot(context: Context): File =
        File(context.filesDir, "recovery-lab-provider")

    fun preferenceFile(context: Context, name: String): File =
        File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")

    fun documentSize(context: Context, uri: Uri): Long =
        requireNotNull(
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null,
            ),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
        }

    fun clean(context: Context) {
        context.deleteSharedPreferences(JournalPreferences)
        context.deleteSharedPreferences(ControlPreferences)
        runRoot(context).deleteRecursively()
        providerRoot(context).deleteRecursively()
        assertFalse(preferenceFile(context, JournalPreferences).exists())
        assertFalse(preferenceFile(context, ControlPreferences).exists())
        assertFalse(runRoot(context).exists())
        assertFalse(providerRoot(context).exists())
    }

    fun createStagedArchive(context: Context): StagedRecoveryArchive {
        val root = runRoot(context)
        check(root.mkdirs())
        val runId = UUID.randomUUID().toString()
        val runDirectory = File(root, runId).apply { check(mkdirs()) }
        val payloadRoot = File(runDirectory, "restart-payload").apply { check(mkdirs()) }
        val payloads = RecoveryArchiveTablesV1.paths.map { path ->
            val file = File(payloadRoot, path.replace('/', '_')).apply {
                writeBytes(byteArrayOf())
            }
            RecoveryArchivePayloadSource(
                path = path,
                contentClass = if (path == "db/title_metadata_cache.jsonl") {
                    RECOVERY_CONTENT_DERIVED
                } else {
                    RECOVERY_CONTENT_CORE
                },
                rowCount = 0L,
                file = file,
            )
        }.toMutableList()
        val crashPayload = File(payloadRoot, "crash-payload.bin")
        crashPayload.writeBytes(deterministicBytes(512 * 1024))
        payloads += RecoveryArchivePayloadSource(
            path = "future/ep06a-mid-copy.bin",
            contentClass = "ep06a-test-only",
            file = crashPayload,
        )
        val compatibility = File(payloadRoot, "migration-bundle.json").apply {
            writeText(
                "{\"bundleVersion\":1,\"kind\":\"mimir.migration.bundle\"," +
                    "\"producedByApp\":\"$MIMIR_LAB_PACKAGE\"," +
                    "\"producedByVersion\":\"0.1.0-alpha.1-lab\"," +
                    "\"producedAtMillis\":1753270000000,\"sourceRoots\":[]," +
                    "\"favorites\":[],\"hidden\":[],\"emulatorOverrides\":[]}",
            )
        }
        payloads += RecoveryArchivePayloadSource(
            path = RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH,
            contentClass = RECOVERY_CONTENT_COMPAT,
            file = compatibility,
        )
        val result = RecoveryArchiveWriter.writeNew(
            destination = File(runDirectory, ArchiveName),
            descriptor = RecoveryArchiveDescriptorV1(
                createdAtUtcMillis = 1_753_270_000_000L,
                producedByApp = MIMIR_LAB_PACKAGE,
                producedByVersionName = BuildConfig.VERSION_NAME,
                producedByVersionCode = BuildConfig.VERSION_CODE.toLong(),
                schemaVersion = 9,
                excluded = RecoveryArchiveCoverageV1.requiredExclusions.sorted().map { key ->
                    RecoveryArchiveExclusionV1(
                        what = key,
                        reason = "Excluded by the Recovery Archive V1 contract.",
                    )
                },
                snapshot = RecoveryArchiveSnapshotV1(
                    transactional = true,
                    quiescent = false,
                    journalMode = "WAL",
                ),
            ),
            payloads = payloads,
        ).getOrThrow()
        return StagedRecoveryArchive(
            runId = runId,
            runRoot = root,
            runDirectory = runDirectory,
            result = result,
            rootName = RootName,
        )
    }

    fun createDestination(
        context: Context,
        staged: StagedRecoveryArchive,
    ): RestartDestination {
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
        return RestartDestination(uri, created)
    }

    private fun deterministicBytes(size: Int): ByteArray {
        var state = 0x5deece66dL
        return ByteArray(size) {
            state = state * 2_862_933_555_777_941_757L + 3_037_000_493L
            (state ushr 24).toByte()
        }
    }

    data class RestartDestination(
        val uri: Uri,
        val created: CreatedRecoveryDocument,
    )
}
