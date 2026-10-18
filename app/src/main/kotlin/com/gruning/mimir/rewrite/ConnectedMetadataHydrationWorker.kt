package com.gruning.mimir.rewrite

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataCache
import com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A deliberately small, metadata-only starter pass after onboarding has already opened Home.
 *
 * This is not the artwork batch: it has no media downloader, SAF writer, local-art probe, progress
 * overlay, or library refresh. One verified ScreenScraper response may cache its provider title and
 * synopsis; remote media URLs are discarded by [ScreenScraperMetadataCache]. The hard one-shot cap
 * bounds provider spend to at most 24 lookup attempts with the source's current three-rung ladder.
 */
internal object ConnectedMetadataHydrator {
    const val MaxGamesPerRun = 8

    data class Report(
        val attempted: Int,
        val cached: Int,
        val stoppedEarly: Boolean,
    )

    suspend fun hydrate(context: Context): Report = withContext(Dispatchers.IO) {
        // This check deliberately precedes all context/DB access so an absent opt-in is a true no-op.
        if (!MimirConnectedArtGate.isOpen) return@withContext Report(0, 0, stoppedEarly = false)

        ArtworkConnectController.attach(context)
        val database = MimirDatabaseFactory.get(context)
        val games = boundedMetadataHydrationCandidates(
            database.gameDao().primaryGamesWithoutScreenScraperMetadata(MaxGamesPerRun),
        )
        var attempted = 0
        var cached = 0
        var stoppedEarly = false

        for (game in games) {
            val outcome = ArtworkConnectController.source.lookupOutcome(
                ArtworkFetchController.screenScraperMetadataQuery(game),
            )
            attempted++
            when (outcome) {
                is ScreenScraperMetadataSource.LookupOutcome.Loaded -> {
                    // Never ArtResolutionStore.record here. That record is what marks a game's local art as
                    // verified (the DB01 re-check reads its absence as "unattributed"), and this pass verifies
                    // the game's identity without looking at its art. Recording it made up to eight games per
                    // wipe skip the cover repair and the stale-logo removal until the next wipe. This pass must not
                    // mark games' art as verified. The art batch's own lookup
                    // records it once it has seen the art.
                    val persisted = runCatching {
                        ScreenScraperMetadataCache.persistVerifiedRecord(
                            dao = database.titleMetadataCacheDao(),
                            game = game,
                            record = outcome.record,
                            gameIdentityDao = database.gameIdentityDao(),
                            runTransaction = { block -> database.withTransaction { block() } },
                        )
                    }.getOrNull()
                    if (persisted == null) {
                        stoppedEarly = true
                        break
                    }
                    if (persisted) cached++
                }
                // Nothing cached either way, but for different reasons: Empty is the provider's answer,
                // PlatformUnsupported means no request left the device. Neither ends the slice --
                // an unmappable platform must not stop the mappable games queued behind it.
                ScreenScraperMetadataSource.LookupOutcome.Empty,
                ScreenScraperMetadataSource.LookupOutcome.PlatformUnsupported,
                -> Unit
                ScreenScraperMetadataSource.LookupOutcome.Unreachable,
                is ScreenScraperMetadataSource.LookupOutcome.Failed,
                ScreenScraperMetadataSource.LookupOutcome.GateClosed,
                -> {
                    stoppedEarly = true
                    break
                }
            }
        }
        Report(attempted = attempted, cached = cached, stoppedEarly = stoppedEarly)
    }
}

internal fun <T> boundedMetadataHydrationCandidates(candidates: List<T>): List<T> =
    candidates.take(ConnectedMetadataHydrator.MaxGamesPerRun)

/**
 * Durable scheduling only; the worker never retries or self-enqueues. A provider failure ends this one
 * bounded slice, while explicit per-game Enrich and Settings artwork remain user-owned actions.
 */
class ConnectedMetadataHydrationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ConnectedArtStore.restore(applicationContext)
        runCatching { ConnectedMetadataHydrator.hydrate(applicationContext) }
        return Result.success()
    }

    companion object {
        private const val UniqueWork = "mimir-connected-metadata-starter"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ConnectedMetadataHydrationWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UniqueWork,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
