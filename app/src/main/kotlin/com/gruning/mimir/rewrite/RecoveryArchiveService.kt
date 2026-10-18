package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_ARCHIVE_BYTES
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveCancelledException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveCoverageV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveDescriptorV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveExclusionV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveSnapshotV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveValidator
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveWriteResult
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveWriter
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.RecoveryArchiveDatabaseExporter
import com.gruning.mimir.rewrite.core.storage.RecoveryArchiveDatabaseRequest
import com.gruning.mimir.rewrite.core.storage.RecoveryArchiveV1SemanticValidator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal const val RecoveryArchiveSuggestedFileName = "mimir-recovery.zip"
internal const val RecoveryArchiveCoverageCopy =
    "Library database + setup. Not included: collections, art, credentials."

private const val RecoveryRootDirectory = "recovery-export"
private const val RecoverySnapshotDirectory = "snapshot"
internal const val RecoveryArchiveFileName = "archive.zip"
private const val RecoveryReadbackFileName = "published-readback.zip"
internal const val RecoveryIoBufferBytes = 64 * 1024
internal const val RecoveryMinimumFreeBytes = 64L * 1024L * 1024L
private const val PendingPrefs = "mimir_recovery_publication"
internal val RecoveryRootNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")

private data class PublicationAuthority(
    val runId: String,
    val destinationUri: String? = null,
)

class StagedRecoveryArchive internal constructor(
    val runId: String,
    val runRoot: File,
    val runDirectory: File,
    val result: RecoveryArchiveWriteResult,
    internal val rootName: String = RecoveryRootDirectory,
) {
    init {
        require(RecoveryRootNamePattern.matches(rootName))
    }
}

enum class RecoveryPublicationVerdict {
    VERIFIED,
    WRITTEN_UNVERIFIED,
    FAILED,
    CANCELLED,
}

data class RecoveryPublicationResult(
    val verdict: RecoveryPublicationVerdict,
    val destinationUri: String?,
    val destinationMayRemain: Boolean = false,
)

data class PendingRecoveryPublication(
    val runId: String,
    val destinationUri: String,
    val stagedArchivePath: String,
    val manifestSha256: String,
    /** Durable evidence that sink copy/close completed; intent alone must never imply a written file. */
    val writeCompleted: Boolean = false,
)

class RecoveryDocumentCreationRequest internal constructor(
    internal val runId: String,
    internal val authorizationId: String,
    val suggestedFileName: String,
)

class CreatedRecoveryDocument internal constructor(
    internal val runId: String,
    internal val authorizationId: String,
    val destinationUri: String,
)

interface RecoveryPublicationJournal {
    fun save(record: PendingRecoveryPublication): Boolean
    fun read(): PendingRecoveryPublication?
    fun clear(runId: String): Boolean
}

interface RecoveryPublicationSink : Closeable {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    /** False means the provider does not expose a syncable file descriptor; read-back decides truth. */
    fun sync(): Boolean
}

interface RecoveryPublicationPort {
    fun openSink(destinationUri: String): RecoveryPublicationSink?
    fun openSource(destinationUri: String): InputStream?
    fun deleteCreatedDocument(destinationUri: String): Boolean
}

internal class RecoveryArchiveEnvironment(
    val database: (Context) -> MimirDatabase,
    val rootName: String,
) {
    init {
        require(RecoveryRootNamePattern.matches(rootName))
    }
}

object RecoveryArchiveService {
    private val singleFlight = Mutex()
    private val publicationAuthorities = mutableMapOf<String, PublicationAuthority>()
    private val publicationAuthorityLock = Any()

    @SuppressLint("UsableSpace") // Recovery export stays conservative and never asks Android to evict caches.
    suspend fun stage(
        context: Context,
    ): Result<StagedRecoveryArchive> = stage(context, ProductionRecoveryEnvironment)

    @SuppressLint("UsableSpace")
    internal suspend fun stage(
        context: Context,
        environment: RecoveryArchiveEnvironment,
    ): Result<StagedRecoveryArchive> = withContext(Dispatchers.IO) {
        if (!singleFlight.tryLock()) {
            return@withContext Result.failure(
                RecoveryArchiveException("A recovery archive operation is already running."),
            )
        }
        val app = context.applicationContext
        val root = File(app.cacheDir, environment.rootName)
        val runId = UUID.randomUUID().toString()
        val runDirectory = File(root, runId)
        try {
            currentCoroutineContext().ensureActive()
            if (app.cacheDir.usableSpace < RecoveryMinimumFreeBytes) {
                throw RecoveryArchiveException("There is not enough free space to stage a recovery archive.")
            }
            if (!runDirectory.mkdirs()) {
                throw RecoveryArchiveException("Couldn’t create the recovery staging directory.")
            }
            val snapshotDirectory = File(runDirectory, RecoverySnapshotDirectory)
            val createdAt = System.currentTimeMillis()
            val snapshot = RecoveryArchiveDatabaseExporter.export(
                database = environment.database(app),
                stagingDirectory = snapshotDirectory,
                request = RecoveryArchiveDatabaseRequest(
                    producedByApp = "mimir-rewrite",
                    producedByVersionName = BuildConfig.VERSION_NAME,
                    producedAtUtcMillis = createdAt,
                ),
            ).getOrThrow()
            currentCoroutineContext().ensureActive()
            val payloadBytes = snapshot.payloads.sumOf { it.file.length() }
            val requiredWorkingBytes = Math.addExact(
                RecoveryMinimumFreeBytes,
                Math.multiplyExact(payloadBytes, 2L),
            )
            if (app.cacheDir.usableSpace < requiredWorkingBytes) {
                throw RecoveryArchiveException("There is not enough free space to finish the recovery archive.")
            }
            val archive = File(runDirectory, RecoveryArchiveFileName)
            val coroutineContext = currentCoroutineContext()
            val shouldContinue = { coroutineContext.isActive }
            val result = RecoveryArchiveWriter.writeNew(
                destination = archive,
                descriptor = RecoveryArchiveDescriptorV1(
                    createdAtUtcMillis = createdAt,
                    producedByApp = app.packageName,
                    producedByVersionName = BuildConfig.VERSION_NAME,
                    producedByVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    schemaVersion = snapshot.schemaVersion,
                    excluded = recoveryArchiveExclusions(),
                    snapshot = RecoveryArchiveSnapshotV1(
                        transactional = true,
                        quiescent = snapshot.quiescent,
                        journalMode = snapshot.journalMode,
                    ),
                ),
                payloads = snapshot.payloads,
                shouldContinue = shouldContinue,
            ).getOrThrow()
            val stagedStructural = RecoveryArchiveValidator.validate(
                result.archive,
                shouldContinue,
            ).getOrThrow()
            requireCurrentRecoverySemantics(
                RecoveryArchiveV1SemanticValidator.validate(
                    stagedStructural,
                    shouldContinue,
                ).getOrThrow(),
            )
            Result.success(
                StagedRecoveryArchive(
                    runId = runId,
                    runRoot = root,
                    runDirectory = runDirectory,
                    result = result,
                    rootName = environment.rootName,
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            deleteOwnedRunDirectory(root, runDirectory)
            throw cancelled
        } catch (cancelled: RecoveryArchiveCancelledException) {
            deleteOwnedRunDirectory(root, runDirectory)
            if (!currentCoroutineContext().isActive) {
                throw kotlinx.coroutines.CancellationException(
                    "Recovery archive staging was cancelled.",
                    cancelled,
                )
            }
            Result.failure(cancelled)
        } catch (error: Throwable) {
            deleteOwnedRunDirectory(root, runDirectory)
            Result.failure(error)
        } finally {
            singleFlight.unlock()
        }
    }

    suspend fun publish(
        staged: StagedRecoveryArchive,
        createdDocument: CreatedRecoveryDocument,
        port: RecoveryPublicationPort,
        journal: RecoveryPublicationJournal,
    ): RecoveryPublicationResult = withContext(Dispatchers.IO) {
        val destinationUri = createdDocument.destinationUri
        if (!consumePublicationAuthority(staged, createdDocument)) {
            return@withContext RecoveryPublicationResult(
                verdict = RecoveryPublicationVerdict.FAILED,
                destinationUri = destinationUri,
                destinationMayRemain = true,
            )
        }
        if (!isExactStagedArchive(staged)) {
            val remains = !deleteRecoveryDestination(port, destinationUri)
            return@withContext RecoveryPublicationResult(
                verdict = RecoveryPublicationVerdict.FAILED,
                destinationUri = destinationUri,
                destinationMayRemain = remains,
            )
        }
        if (!singleFlight.tryLock()) {
            val remains = !deleteRecoveryDestination(port, destinationUri)
            return@withContext RecoveryPublicationResult(
                verdict = RecoveryPublicationVerdict.FAILED,
                destinationUri = destinationUri,
                destinationMayRemain = remains,
            )
        }
        try {
            val existingPublication = try {
                journal.read()
            } catch (_: Throwable) {
                val remains = !deleteRecoveryDestination(port, destinationUri)
                return@withContext RecoveryPublicationResult(
                    verdict = RecoveryPublicationVerdict.FAILED,
                    destinationUri = destinationUri,
                    destinationMayRemain = remains,
                )
            }
            if (existingPublication != null) {
                val remains = !deleteRecoveryDestination(port, destinationUri)
                return@withContext RecoveryPublicationResult(
                    verdict = RecoveryPublicationVerdict.FAILED,
                    destinationUri = destinationUri,
                    destinationMayRemain = remains,
                )
            }
            val record = PendingRecoveryPublication(
                runId = staged.runId,
                destinationUri = destinationUri,
                stagedArchivePath = staged.result.archive.absolutePath,
                manifestSha256 = staged.result.manifestSha256,
            )
            if (!saveRecoveryJournal(journal, record)) {
                val remains = !deleteRecoveryDestination(port, destinationUri)
                return@withContext RecoveryPublicationResult(
                    verdict = RecoveryPublicationVerdict.FAILED,
                    destinationUri = destinationUri,
                    destinationMayRemain = remains,
                )
            }
            try {
                val operationContext = currentCoroutineContext()
                val shouldContinue = { operationContext.isActive }
                operationContext.ensureActive()
                val stagedStructural =
                    RecoveryArchiveValidator.validate(
                        staged.result.archive,
                        shouldContinue,
                    ).getOrThrow()
                requireCurrentRecoverySemantics(
                    RecoveryArchiveV1SemanticValidator.validate(
                        stagedStructural,
                        shouldContinue,
                    ).getOrThrow(),
                )
                val sink = port.openSink(destinationUri)
                    ?: throw RecoveryArchiveException(
                        "The selected provider did not open the created document.",
                    )
                sink.use { destination ->
                    FileInputStream(staged.result.archive).use { source ->
                        val buffer = ByteArray(RecoveryIoBufferBytes)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            destination.write(buffer, 0, read)
                        }
                    }
                    destination.sync()
                }
                if (!saveRecoveryJournal(journal, record.copy(writeCompleted = true))) {
                    throw RecoveryArchiveException(
                        "Couldn’t record completed recovery publication copy.",
                    )
                }

                val source = openRecoverySource(port, destinationUri)
                val verdict = if (source == null) {
                    RecoveryPublicationVerdict.WRITTEN_UNVERIFIED
                } else {
                    val readback = File(staged.runDirectory, RecoveryReadbackFileName)
                    try {
                        source.use { input -> copyRecoveryArchiveStream(input, readback) }
                        val validated = RecoveryArchiveValidator.validate(
                            readback,
                            shouldContinue,
                        ).getOrThrow()
                        requireCurrentRecoverySemantics(
                            RecoveryArchiveV1SemanticValidator.validate(
                                validated,
                                shouldContinue,
                            ).getOrThrow(),
                        )
                        if (validated.manifestSha256 != staged.result.manifestSha256) {
                            throw RecoveryArchiveException(
                                "Published recovery archive differs from the staged archive.",
                            )
                        }
                        RecoveryPublicationVerdict.VERIFIED
                    } finally {
                        readback.delete()
                    }
                }
                if (verdict == RecoveryPublicationVerdict.WRITTEN_UNVERIFIED) {
                    return@withContext RecoveryPublicationResult(
                        verdict = verdict,
                        destinationUri = destinationUri,
                        destinationMayRemain = true,
                    )
                }
                if (!clearRecoveryJournal(journal, staged.runId)) {
                    return@withContext RecoveryPublicationResult(
                        verdict = RecoveryPublicationVerdict.FAILED,
                        destinationUri = destinationUri,
                        destinationMayRemain = true,
                    )
                }
                RecoveryPublicationResult(verdict = verdict, destinationUri = destinationUri)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                val remains = !cleanupPendingPublication(
                    record,
                    port,
                    journal,
                )
                if (!currentCoroutineContext().isActive) {
                    throw cancelled
                }
                RecoveryPublicationResult(
                    RecoveryPublicationVerdict.CANCELLED,
                    destinationUri,
                    remains,
                )
            } catch (cancelled: RecoveryArchiveCancelledException) {
                val remains = !cleanupPendingPublication(
                    record,
                    port,
                    journal,
                )
                if (!currentCoroutineContext().isActive) {
                    throw kotlinx.coroutines.CancellationException(
                        "Recovery publication was cancelled.",
                        cancelled,
                    )
                }
                RecoveryPublicationResult(
                    RecoveryPublicationVerdict.CANCELLED,
                    destinationUri,
                    remains,
                )
            } catch (_: Throwable) {
                val remains = !cleanupPendingPublication(
                    record,
                    port,
                    journal,
                )
                RecoveryPublicationResult(
                    verdict = RecoveryPublicationVerdict.FAILED,
                    destinationUri = destinationUri,
                    destinationMayRemain = remains,
                )
            }
        } finally {
            singleFlight.unlock()
        }
    }

    /**
     * Issues an opaque one-run capability before the UI launches Android's CreateDocument contract.
     * A returned URI cannot be opened, truncated, or deleted by [publish] without this live capability.
     */
    fun beginDocumentCreation(
        staged: StagedRecoveryArchive,
    ): Result<RecoveryDocumentCreationRequest> = runCatching {
        if (!isExactStagedArchive(staged)) {
            throw RecoveryArchiveException("Recovery staging identity is not owned by this run.")
        }
        val authorizationId = UUID.randomUUID().toString()
        synchronized(publicationAuthorityLock) {
            publicationAuthorities.entries.removeAll { it.value.runId == staged.runId }
            publicationAuthorities[authorizationId] = PublicationAuthority(staged.runId)
        }
        RecoveryDocumentCreationRequest(
            runId = staged.runId,
            authorizationId = authorizationId,
            suggestedFileName = RecoveryArchiveSuggestedFileName,
        )
    }

    /**
     * Binds only the URI returned by the matching CreateDocument result. The authority is one-shot and
     * is consumed by [publish]; cancellation should call [cancelDocumentCreation].
     */
    fun acceptCreatedDocument(
        request: RecoveryDocumentCreationRequest,
        destinationUri: String,
    ): Result<CreatedRecoveryDocument> = runCatching {
        if (!isSafeCreatedDocumentUri(destinationUri)) {
            throw RecoveryArchiveException("Recovery destination is not a document-provider URI.")
        }
        val authorized = synchronized(publicationAuthorityLock) {
            val authority = publicationAuthorities[request.authorizationId]
            if (authority?.runId == request.runId && authority.destinationUri == null) {
                publicationAuthorities[request.authorizationId] = authority.copy(
                    destinationUri = destinationUri,
                )
                true
            } else {
                false
            }
        }
        if (!authorized) {
            throw RecoveryArchiveException("Recovery document request is stale or belongs to another run.")
        }
        CreatedRecoveryDocument(
            runId = request.runId,
            authorizationId = request.authorizationId,
            destinationUri = destinationUri,
        )
    }

    fun cancelDocumentCreation(request: RecoveryDocumentCreationRequest) {
        synchronized(publicationAuthorityLock) {
            if (publicationAuthorities[request.authorizationId]?.runId == request.runId) {
                publicationAuthorities.remove(request.authorizationId)
            }
        }
    }

    /**
     * Re-enters only a prior run's exact URI. Call this from the future Recovery UI before starting a new
     * publication; it performs no broad provider traversal and never touches user media.
     */
    suspend fun recoverPendingPublication(
        runRoot: File,
        port: RecoveryPublicationPort,
        journal: RecoveryPublicationJournal,
    ): RecoveryPublicationResult? = recoverPendingPublication(
        runRoot = runRoot,
        port = port,
        journal = journal,
        expectedRootName = RecoveryRootDirectory,
    )

    internal suspend fun recoverPendingPublication(
        runRoot: File,
        port: RecoveryPublicationPort,
        journal: RecoveryPublicationJournal,
        expectedRootName: String,
    ): RecoveryPublicationResult? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val pending = try {
            journal.read()
        } catch (_: Throwable) {
            return@withContext RecoveryPublicationResult(
                verdict = RecoveryPublicationVerdict.FAILED,
                destinationUri = null,
                destinationMayRemain = true,
            )
        } ?: return@withContext null
        val staged = exactPendingStagedArchive(runRoot, pending, expectedRootName)
        if (staged == null || !isSafeCreatedDocumentUri(pending.destinationUri)) {
            return@withContext RecoveryPublicationResult(
                verdict = RecoveryPublicationVerdict.FAILED,
                destinationUri = pending.destinationUri,
                destinationMayRemain = true,
            )
        }
        if (!singleFlight.tryLock()) {
            return@withContext RecoveryPublicationResult(
                verdict = RecoveryPublicationVerdict.FAILED,
                destinationUri = pending.destinationUri,
                destinationMayRemain = true,
            )
        }
        try {
            val operationContext = currentCoroutineContext()
            val shouldContinue = { operationContext.isActive }
            val stagedValid = staged.isFile && isCurrentRecoveryArchive(
                archive = staged,
                expectedManifestSha256 = pending.manifestSha256,
                shouldContinue = shouldContinue,
            )
            var completedWriteEvidence = pending.writeCompleted
            var destinationState = inspectPublishedRecovery(
                runRoot = runRoot,
                pending = pending,
                port = port,
                shouldContinue = shouldContinue,
            )
            if (destinationState == PublishedRecoveryState.VERIFIED) {
                return@withContext verifiedPendingResult(pending, journal)
            }
            if (stagedValid) {
                destinationState = try {
                    if (writeRecoveryDestination(staged, pending.destinationUri, port)) {
                        val completed = pending.copy(writeCompleted = true)
                        if (!saveRecoveryJournal(journal, completed)) {
                            val remains = !cleanupPendingPublication(pending, port, journal)
                            return@withContext RecoveryPublicationResult(
                                verdict = RecoveryPublicationVerdict.FAILED,
                                destinationUri = pending.destinationUri,
                                destinationMayRemain = remains,
                            )
                        }
                        completedWriteEvidence = true
                        inspectPublishedRecovery(
                            runRoot = runRoot,
                            pending = pending,
                            port = port,
                            shouldContinue = shouldContinue,
                        )
                    } else {
                        PublishedRecoveryState.UNREADABLE
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    cleanupPendingPublication(pending, port, journal)
                    throw cancelled
                } catch (cancelled: RecoveryArchiveCancelledException) {
                    cleanupPendingPublication(pending, port, journal)
                    throw cancelled
                } catch (_: Throwable) {
                    PublishedRecoveryState.INVALID
                }
                if (destinationState == PublishedRecoveryState.VERIFIED) {
                    return@withContext verifiedPendingResult(pending, journal)
                }
            }
            if (destinationState == PublishedRecoveryState.UNREADABLE) {
                if (completedWriteEvidence) {
                    RecoveryPublicationResult(
                        verdict = RecoveryPublicationVerdict.WRITTEN_UNVERIFIED,
                        destinationUri = pending.destinationUri,
                        destinationMayRemain = true,
                    )
                } else {
                    RecoveryPublicationResult(
                        verdict = RecoveryPublicationVerdict.FAILED,
                        destinationUri = pending.destinationUri,
                        destinationMayRemain = true,
                    )
                }
            } else {
                val remains = !cleanupPendingPublication(pending, port, journal)
                RecoveryPublicationResult(
                    verdict = RecoveryPublicationVerdict.FAILED,
                    destinationUri = pending.destinationUri,
                    destinationMayRemain = remains,
                )
            }
        } catch (cancelled: RecoveryArchiveCancelledException) {
            throw kotlinx.coroutines.CancellationException(
                "Recovery publication reconciliation was cancelled.",
                cancelled,
            )
        } finally {
            singleFlight.unlock()
        }
    }

    fun sweepStaleStaging(
        context: Context,
        olderThanUtcMillis: Long,
        journal: RecoveryPublicationJournal = AndroidRecoveryPublicationJournal(context),
    ) = sweepStaleStagingRoot(
        root = File(context.applicationContext.cacheDir, RecoveryRootDirectory),
        olderThanUtcMillis = olderThanUtcMillis,
        journal = journal,
    )

    internal fun sweepStaleStagingRoot(
        root: File,
        olderThanUtcMillis: Long,
        journal: RecoveryPublicationJournal,
    ) {
        if (!singleFlight.tryLock()) return
        try {
            val pendingRunId = try {
                journal.read()?.runId
            } catch (_: Throwable) {
                return
            }
            root.listFiles()
                .orEmpty()
                .filter { candidate ->
                    candidate.isDirectory &&
                        candidate.parentFile == root &&
                        runCatching { UUID.fromString(candidate.name) }.isSuccess &&
                        candidate.name != pendingRunId &&
                        candidate.lastModified() in 1 until olderThanUtcMillis
                }
                .forEach { deleteOwnedRunDirectory(root, it) }
        } finally {
            singleFlight.unlock()
        }
    }

    fun runRoot(context: Context): File =
        File(context.applicationContext.cacheDir, RecoveryRootDirectory)

    private val ProductionRecoveryEnvironment = RecoveryArchiveEnvironment(
        database = MimirDatabaseFactory::get,
        rootName = RecoveryRootDirectory,
    )

    private fun consumePublicationAuthority(
        staged: StagedRecoveryArchive,
        createdDocument: CreatedRecoveryDocument,
    ): Boolean {
        if (staged.runId != createdDocument.runId ||
            !isSafeCreatedDocumentUri(createdDocument.destinationUri)
        ) {
            return false
        }
        return synchronized(publicationAuthorityLock) {
            val authority = publicationAuthorities[createdDocument.authorizationId]
            if (authority?.runId == staged.runId &&
                authority.destinationUri == createdDocument.destinationUri
            ) {
                publicationAuthorities.remove(createdDocument.authorizationId)
                true
            } else {
                false
            }
        }
    }
}

class AndroidRecoveryPublicationJournal(
    context: Context,
    preferencesName: String = PendingPrefs,
) : RecoveryPublicationJournal {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    override fun save(record: PendingRecoveryPublication): Boolean =
        preferences.edit()
            .putString("run_id", record.runId)
            .putString("destination_uri", record.destinationUri)
            .putString("staged_archive_path", record.stagedArchivePath)
            .putString("manifest_sha256", record.manifestSha256)
            .putBoolean("write_completed", record.writeCompleted)
            .commit()

    override fun read(): PendingRecoveryPublication? {
        val keys = listOf(
            "run_id",
            "destination_uri",
            "staged_archive_path",
            "manifest_sha256",
            "write_completed",
        )
        if (keys.none(preferences::contains)) return null
        val runId = preferences.getString("run_id", null).orEmpty()
        val destination = preferences.getString("destination_uri", null).orEmpty()
        val path = preferences.getString("staged_archive_path", null).orEmpty()
        val hash = preferences.getString("manifest_sha256", null).orEmpty()
        val writeCompleted = preferences.getBoolean("write_completed", false)
        if (runCatching { UUID.fromString(runId) }.isFailure ||
            destination.isBlank() ||
            path.isBlank() ||
            !hash.matches(Regex("[0-9a-f]{64}"))
        ) {
            throw RecoveryArchiveException("Pending recovery publication journal is malformed.")
        }
        return PendingRecoveryPublication(runId, destination, path, hash, writeCompleted)
    }

    override fun clear(runId: String): Boolean {
        if (preferences.getString("run_id", null) != runId) return false
        return preferences.edit().clear().commit()
    }
}

class AndroidRecoveryPublicationPort(
    context: Context,
) : RecoveryPublicationPort {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override fun openSink(destinationUri: String): RecoveryPublicationSink? {
        val uri = parseCreatedDocumentUri(destinationUri) ?: return null
        val descriptor = resolver.openFileDescriptor(uri, "rwt") ?: return null
        val output = FileOutputStream(descriptor.fileDescriptor)
        return object : RecoveryPublicationSink {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                output.write(bytes, offset, length)
            }

            override fun sync(): Boolean = runCatching {
                output.flush()
                output.fd.sync()
            }.isSuccess

            override fun close() {
                var failure: Throwable? = null
                try {
                    output.close()
                } catch (error: Throwable) {
                    failure = error
                }
                try {
                    descriptor.close()
                } catch (error: Throwable) {
                    val prior = failure
                    if (prior == null) {
                        failure = error
                    } else {
                        prior.addSuppressed(error)
                    }
                }
                failure?.let { throw it }
            }
        }
    }

    override fun openSource(destinationUri: String): InputStream? =
        parseCreatedDocumentUri(destinationUri)?.let(resolver::openInputStream)

    override fun deleteCreatedDocument(destinationUri: String): Boolean = runCatching {
        val uri = parseCreatedDocumentUri(destinationUri) ?: return@runCatching false
        DocumentsContract.deleteDocument(resolver, uri)
    }.getOrDefault(false)

    private fun parseCreatedDocumentUri(value: String): Uri? =
        if (!isSafeCreatedDocumentUri(value)) {
            null
        } else {
            runCatching { Uri.parse(value) }.getOrNull()
        }?.takeIf { uri ->
            DocumentsContract.isDocumentUri(appContext, uri)
        }
}

internal fun recoveryArchiveExclusions(): List<RecoveryArchiveExclusionV1> {
    val reasons = mapOf(
        RecoveryArchiveCoverageV1.ROM_BIOS_FIRMWARE_BYTES to
            "Mimir never copies ROM, BIOS, or firmware content into a recovery archive.",
        RecoveryArchiveCoverageV1.SOURCE_STORAGE_BYTES to
            "Source roots are represented as typed index rows and re-pick hints only.",
        RecoveryArchiveCoverageV1.DOWNLOADED_ART_MEDIA to
            "Downloaded media is re-derivable and unbounded; V1 exports typed references only.",
        RecoveryArchiveCoverageV1.CREDENTIALS to
            "V1 is unencrypted and device Keystore material is not portable; reconnect services after restore.",
        RecoveryArchiveCoverageV1.SAF_GRANTS to
            "Android grants are package/device scoped and must be granted again.",
        RecoveryArchiveCoverageV1.RAW_PREFERENCES to
            "Raw preferences can contain credentials and unstable implementation details.",
        RecoveryArchiveCoverageV1.RAW_DATABASE to
            "V1 exports typed rows, never SQLite, WAL, SHM, or freelist residue.",
        RecoveryArchiveCoverageV1.LOGS to
            "Logs and diagnostics are not user recovery state.",
        RecoveryArchiveCoverageV1.HOME_COLLECTIONS to
            "Custom Tome and collection state is outside the V1 Room-only boundary.",
        RecoveryArchiveCoverageV1.QUICK_LAUNCH to
            "Quick Launch preferences are outside the V1 Room-only boundary.",
        RecoveryArchiveCoverageV1.CUSTOM_MEDIA to
            "Uploaded and selected custom media is outside the V1 Room-only boundary.",
        RecoveryArchiveCoverageV1.APPEARANCE_BRIGHTNESS to
            "Appearance, brightness, and device controls are outside the V1 Room-only boundary.",
    )
    return RecoveryArchiveCoverageV1.requiredExclusions.sorted().map { key ->
        RecoveryArchiveExclusionV1(key, reasons.getValue(key))
    }
}

internal suspend fun copyRecoveryArchiveStream(input: InputStream, destination: File) {
    var count = 0L
    FileOutputStream(destination).use { fileOutput ->
        val output = BufferedOutputStream(fileOutput, RecoveryIoBufferBytes)
        BufferedInputStream(input, RecoveryIoBufferBytes).use { source ->
            val buffer = ByteArray(RecoveryIoBufferBytes)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                count = Math.addExact(count, read.toLong())
                if (count > RECOVERY_MAX_ARCHIVE_BYTES) {
                    throw RecoveryArchiveException("Published recovery archive exceeds the supported limit.")
                }
                output.write(buffer, 0, read)
            }
        }
        output.flush()
        fileOutput.fd.sync()
    }
}

private enum class PublishedRecoveryState {
    VERIFIED,
    INVALID,
    UNREADABLE,
}

private fun exactPendingStagedArchive(
    runRoot: File,
    pending: PendingRecoveryPublication,
    expectedRootName: String,
): File? = runCatching {
    if (!RecoveryRootNamePattern.matches(expectedRootName)) {
        return@runCatching null
    }
    UUID.fromString(pending.runId)
    val canonicalRoot = runRoot.canonicalFile
    if (canonicalRoot.name != expectedRootName ||
        Files.isSymbolicLink(runRoot.toPath())
    ) {
        return@runCatching null
    }
    val runDirectory = File(runRoot.absoluteFile, pending.runId)
    val expected = File(runDirectory, RecoveryArchiveFileName)
    if (Files.isSymbolicLink(runDirectory.toPath()) ||
        Files.isSymbolicLink(expected.toPath())
    ) {
        return@runCatching null
    }
    val expectedPath = expected.toPath().toAbsolutePath().normalize()
    val recordedPath = File(pending.stagedArchivePath).toPath().toAbsolutePath().normalize()
    expected.takeIf {
        expectedPath == recordedPath &&
            expected.canonicalFile.toPath().startsWith(canonicalRoot.toPath())
    }
}.getOrNull()

private fun isCurrentRecoveryArchive(
    archive: File,
    expectedManifestSha256: String,
    shouldContinue: () -> Boolean,
): Boolean = try {
    val structural = RecoveryArchiveValidator.validate(archive, shouldContinue).getOrThrow()
    requireCurrentRecoverySemantics(
        RecoveryArchiveV1SemanticValidator.validate(structural, shouldContinue).getOrThrow(),
    )
    structural.manifestSha256 == expectedManifestSha256
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (cancelled: RecoveryArchiveCancelledException) {
    throw cancelled
} catch (_: Throwable) {
    false
}

private suspend fun inspectPublishedRecovery(
    runRoot: File,
    pending: PendingRecoveryPublication,
    port: RecoveryPublicationPort,
    shouldContinue: () -> Boolean,
): PublishedRecoveryState {
    val source = openRecoverySource(port, pending.destinationUri)
        ?: return PublishedRecoveryState.UNREADABLE
    val canonicalRoot = runRoot.canonicalFile
    if ((!canonicalRoot.exists() && !canonicalRoot.mkdirs()) ||
        !canonicalRoot.isDirectory ||
        Files.isSymbolicLink(canonicalRoot.toPath())
    ) {
        runCatching { source.close() }
        return PublishedRecoveryState.UNREADABLE
    }
    val readback = File(
        canonicalRoot,
        ".${pending.runId}.${UUID.randomUUID()}.$RecoveryReadbackFileName",
    )
    return try {
        val copied = try {
            source.use { copyRecoveryArchiveStream(it, readback) }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (cancelled: RecoveryArchiveCancelledException) {
            throw cancelled
        } catch (oversized: RecoveryArchiveException) {
            false
        } catch (_: Throwable) {
            return PublishedRecoveryState.UNREADABLE
        }
        if (copied && isCurrentRecoveryArchive(readback, pending.manifestSha256, shouldContinue)) {
            PublishedRecoveryState.VERIFIED
        } else {
            PublishedRecoveryState.INVALID
        }
    } finally {
        readback.delete()
    }
}

private suspend fun writeRecoveryDestination(
    stagedArchive: File,
    destinationUri: String,
    port: RecoveryPublicationPort,
): Boolean {
    val sink = port.openSink(destinationUri) ?: return false
    sink.use { destination ->
        FileInputStream(stagedArchive).use { source ->
            val buffer = ByteArray(RecoveryIoBufferBytes)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                destination.write(buffer, 0, read)
            }
        }
        destination.sync()
    }
    return true
}

private fun verifiedPendingResult(
    pending: PendingRecoveryPublication,
    journal: RecoveryPublicationJournal,
): RecoveryPublicationResult =
    if (clearRecoveryJournal(journal, pending.runId)) {
        RecoveryPublicationResult(
            verdict = RecoveryPublicationVerdict.VERIFIED,
            destinationUri = pending.destinationUri,
        )
    } else {
        RecoveryPublicationResult(
            verdict = RecoveryPublicationVerdict.FAILED,
            destinationUri = pending.destinationUri,
            destinationMayRemain = true,
        )
    }

private fun cleanupPendingPublication(
    pending: PendingRecoveryPublication,
    port: RecoveryPublicationPort,
    journal: RecoveryPublicationJournal,
): Boolean {
    val deleted = deleteRecoveryDestination(port, pending.destinationUri)
    if (deleted) {
        clearRecoveryJournal(journal, pending.runId)
    }
    return deleted
}

private fun saveRecoveryJournal(
    journal: RecoveryPublicationJournal,
    record: PendingRecoveryPublication,
): Boolean = try {
    journal.save(record)
} catch (_: Throwable) {
    false
}

private fun clearRecoveryJournal(
    journal: RecoveryPublicationJournal,
    runId: String,
): Boolean = try {
    journal.clear(runId)
} catch (_: Throwable) {
    false
}

private fun deleteRecoveryDestination(
    port: RecoveryPublicationPort,
    destinationUri: String,
): Boolean = try {
    port.deleteCreatedDocument(destinationUri)
} catch (_: Throwable) {
    false
}

private fun openRecoverySource(
    port: RecoveryPublicationPort,
    destinationUri: String,
): InputStream? = try {
    port.openSource(destinationUri)
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

internal fun deleteOwnedRunDirectory(root: File, runDirectory: File): Boolean {
    if (!isOwnedRunPath(root, runDirectory) ||
        runCatching { UUID.fromString(runDirectory.name) }.isFailure
    ) {
        return false
    }
    val deleted = runDirectory.deleteRecursively()
    if (deleted) {
        // Non-recursive by design: remove the owner root only when this was its final run.
        root.delete()
    }
    return deleted
}

internal fun isOwnedRunPath(root: File, candidate: File): Boolean = runCatching {
    val rootPath = root.canonicalFile.toPath()
    val candidatePath = candidate.canonicalFile.toPath()
    candidatePath.startsWith(rootPath) && candidatePath != rootPath
}.getOrDefault(false)

private fun isExactStagedArchive(staged: StagedRecoveryArchive): Boolean = runCatching {
    UUID.fromString(staged.runId)
    val runRoot = staged.runRoot.canonicalFile
    val runDirectory = staged.runDirectory.canonicalFile
    val archive = staged.result.archive.canonicalFile
    runRoot.name == staged.rootName &&
        !Files.isSymbolicLink(staged.runRoot.toPath()) &&
        !Files.isSymbolicLink(staged.runDirectory.toPath()) &&
        runDirectory == File(runRoot, staged.runId).canonicalFile &&
        archive == File(runDirectory, RecoveryArchiveFileName).canonicalFile &&
        Files.isRegularFile(archive.toPath(), NOFOLLOW_LINKS)
}.getOrDefault(false)

internal fun isSafeCreatedDocumentUri(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("content", ignoreCase = true) &&
        !uri.rawAuthority.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        uri.rawFragment == null
}.getOrDefault(false)

private fun requireCurrentRecoverySemantics(
    report: com.gruning.mimir.rewrite.core.storage.RecoveryArchiveSemanticReport,
) {
    if (report.newerSchema) {
        throw RecoveryArchiveException(
            "This recovery archive uses a newer Room schema and cannot be published by this build.",
        )
    }
}
