package com.gruning.mimir.rewrite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gruning.mimir.rewrite.core.model.library.ScanOutcome
import com.gruning.mimir.rewrite.core.model.library.LibrarySourceRole
import com.gruning.mimir.rewrite.core.resolver.library.LibraryStore
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.TimeUnit

/** Cooperative cancellation must propagate instead of being converted into retry/failure publication. */
internal fun Throwable.rethrowIfCancellation(): Nothing? {
    if (this is CancellationException) throw this
    return null
}

internal data class OnboardingTreeAccess(
    val read: Boolean,
    val write: Boolean,
)

/**
 * Whether Mimir can still read and write the folder onboarding scans. The folder is an Android
 * SAF tree URI or a plain path from Mimir's own browser. Plain paths are readable only while
 * All-files access is held, which is what lets the browser see them.
 */
internal fun currentOnboardingTreeAccess(context: Context, treeUri: String): OnboardingTreeAccess {
    if (onboardingRootIsPath(treeUri)) {
        val folder = File(treeUri)
        return onboardingPathRootAccess(
            allFilesAccess = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false),
            readable = folder.isDirectory && folder.canRead(),
            writable = folder.canWrite(),
        )
    }
    val uri = runCatching { Uri.parse(treeUri) }.getOrNull()
        ?: return OnboardingTreeAccess(read = false, write = false)
    val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
    return OnboardingTreeAccess(
        read = permission?.isReadPermission == true,
        write = permission?.isWritePermission == true,
    )
}

/** A root from Mimir's browser is an absolute path; one from Android's picker is a content:// tree URI. */
internal fun onboardingRootIsPath(root: String): Boolean = root.startsWith("/")

/** A path root is read through java.io.File, which sees shared storage only while All-files access is held. */
internal fun onboardingPathRootAccess(allFilesAccess: Boolean, readable: Boolean, writable: Boolean): OnboardingTreeAccess =
    OnboardingTreeAccess(read = allFilesAccess && readable, write = allFilesAccess && readable && writable)

internal fun persistOnboardingTreeAccess(context: Context, uri: Uri): OnboardingTreeAccess {
    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { context.contentResolver.takePersistableUriPermission(uri, read or write) }
        .recoverCatching { context.contentResolver.takePersistableUriPermission(uri, read) }
    return currentOnboardingTreeAccess(context, uri.toString())
}

/** Durable wrapper around the existing repository scan. The worker schedules the authority; it does not
 * duplicate recognition, persistence, deduplication, or identity logic. Every result is generation-guarded. */
class OnboardingScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val treeUri = inputData.getString(InputTreeUri) ?: return Result.failure()
        val generation = inputData.getString(InputGeneration) ?: return Result.failure()
        if (!OnboardingStore.markScanRunning(applicationContext, generation)) return Result.success()

        if (!currentOnboardingTreeAccess(applicationContext, treeUri).read) {
            OnboardingStore.markScanFailed(applicationContext, generation, MissingReadGrant)
            return Result.failure(failureData(MissingReadGrant))
        }

        return runCatching {
            val scan = LibraryStore.repository(applicationContext).addSource(
                treeUri,
                LibrarySourceRole.PRIMARY,
            )
            LastScanSummaryState.record(scan)
            NewGameEnrichPromptState.recordScan(applicationContext, scan)
            when (val outcome = scan.outcome) {
                ScanOutcome.Healthy -> {
                    OnboardingStore.markScanComplete(
                        context = applicationContext,
                        generation = generation,
                        filesSeen = scan.filesSeen,
                        recognized = scan.recognized,
                        groups = scan.groups,
                        skipped = scan.skipped,
                    )
                    Result.success()
                }
                is ScanOutcome.Failed -> {
                    OnboardingStore.markScanFailed(applicationContext, generation, outcome.reason)
                    Result.failure(failureData(outcome.reason))
                }
            }
        }.getOrElse { error ->
            error.rethrowIfCancellation()
            if (runAttemptCount < MaxRetries) {
                Result.retry()
            } else {
                val reason = error.message?.takeIf(String::isNotBlank) ?: ScanFailed
                OnboardingStore.markScanFailed(applicationContext, generation, reason)
                Result.failure(failureData(reason))
            }
        }
    }

    companion object {
        private const val UniqueWork = "mimir-onboarding-library-scan"
        private const val InputTreeUri = "tree_uri"
        private const val InputGeneration = "generation"
        private const val FailureReason = "failure_reason"
        // Stored as written (the checkpoint's scanFailure), so the keys OnboardingStoredText resolves to the player's language.
        internal const val MissingReadGrant = "Folder access was not retained. Choose the folder again."
        internal const val ScanFailed = "Mimir could not finish reading that folder."
        private const val MaxRetries = 2

        fun replace(context: Context, treeUri: String, generation: String) {
            enqueue(context, treeUri, generation, ExistingWorkPolicy.REPLACE)
        }

        fun ensureEnqueued(context: Context, treeUri: String, generation: String) {
            enqueue(context, treeUri, generation, ExistingWorkPolicy.KEEP)
        }

        private fun enqueue(
            context: Context,
            treeUri: String,
            generation: String,
            policy: ExistingWorkPolicy,
        ) {
            val input = Data.Builder()
                .putString(InputTreeUri, treeUri)
                .putString(InputGeneration, generation)
                .build()
            val request = OneTimeWorkRequestBuilder<OnboardingScanWorker>()
                .setInputData(input)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UniqueWork, policy, request)
        }

        private fun failureData(reason: String): Data =
            Data.Builder().putString(FailureReason, reason).build()
    }
}
