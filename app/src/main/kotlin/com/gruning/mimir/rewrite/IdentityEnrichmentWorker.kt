package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.jobs.JobProgressState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.gruning.mimir.rewrite.core.metadata.BundledDatSource
import com.gruning.mimir.rewrite.core.metadata.BundledIdSource
import com.gruning.mimir.rewrite.core.metadata.AthenaContainerIdentityPolicy
import com.gruning.mimir.rewrite.core.metadata.LibraryCollapseRegrouper
import com.gruning.mimir.rewrite.core.metadata.ChdIdentitySource
import com.gruning.mimir.rewrite.core.metadata.LibraryIdentityEnricher
import com.gruning.mimir.rewrite.core.metadata.MonotonicDeadline
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.ProcessSourceRootMutations
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The one identity worker's honest active stage; grouping has no exact denominator. */
internal enum class IdentityEnrichmentStage { IDENTIFYING, GROUPING }

internal data class IdentityEnrichmentProgress(
    val stage: IdentityEnrichmentStage,
    val attempted: Int,
    val matched: Int,
    val remaining: Int,
)

/** A JVM-friendly projection of WorkManager's records; the newest active record is authoritative. */
internal data class IdentityEnrichmentWorkRecord(
    val active: Boolean,
    val stage: String?,
    val attempted: Int,
    val matched: Int,
    val remaining: Int,
)

internal fun identityEnrichmentProgressFor(
    records: List<IdentityEnrichmentWorkRecord>,
): IdentityEnrichmentProgress? = records.lastOrNull { it.active && it.stage != null }?.let { record ->
    val stage = runCatching { IdentityEnrichmentStage.valueOf(record.stage.orEmpty()) }.getOrNull()
        ?: return@let null
    IdentityEnrichmentProgress(
        stage = stage,
        attempted = record.attempted.coerceAtLeast(0),
        matched = record.matched.coerceAtLeast(0),
        remaining = record.remaining.coerceAtLeast(0),
    )
}

/**
 * Honest, in-memory, per-file identification progress for the CURRENTLY RUNNING worker slice. Fed
 * directly by [LibraryIdentityEnricher.enrich]'s `onGameAttempted` callback, which fires the instant EACH
 * game's own hash+decision finishes, strictly before that game's batch reaches Room. WorkManager's
 * `setProgress`/[currentProgress] stays as the persisted, cross-process-death fallback and the stage
 * (IDENTIFYING/GROUPING) signal — it is deliberately NOT replaced, only supplemented, since it survives what
 * this in-memory object cannot: process death and (if that ever changes) a different process for the worker.
 *
 * Same-process only by construction: [IdentityEnrichmentWorker] is a plain `CoroutineWorker` with no
 * `android:process` in `AndroidManifest.xml`, so it always runs in the app's own process — a JVM-visible
 * `MutableStateFlow` is exact here, not eventually-consistent the way a cross-process channel would be.
 *
 * Publishes one immutable attempted/total snapshot per worker run. The onboarding checked count reads
 * that pair directly, so unrelated visible-game and unresolved-row populations cannot cancel each other.
 * The compatibility [remaining] flow remains for older readers.
 *
 * Previewable/testable without a device: [start]/[publish]/[clear] are plain synchronous calls over a plain
 * [StateFlow] — see `IdentityEnrichmentLiveProgressTest`.
 */
internal data class IdentityEnrichmentLiveSnapshot(
    val runId: Long,
    val attempted: Int,
    val total: Int,
) {
    val remaining: Int get() = (total - attempted).coerceAtLeast(0)
}

internal object IdentityEnrichmentLiveProgress {
    private const val IdentityProgressLogTag = "MimirIdentityProgress"
    private val gate = Any()
    private var nextRunId = 0L
    private var activeRunId: Long? = null

    /**
     * Games that earlier slices of the sweep in flight already attempted. A 5529-game library
     * identified in two 7-minute worker slices, and each slice
     * restarted the count: 3150 of 5529, then 0 of 1576. A continuation now starts where the last slice
     * stopped and counts against `carried + its own unresolved` (3150 of 4726), so the one bar only climbs
     * and ends at its total. The denominator is recomputed rather than kept at 5529 because rows also resolve
     * without being attempted (5529 - 3150 is not 1576), and a kept denominator would stall short of the end.
     */
    private var sweepCarried = 0
    private var runCarried = 0
    private var startedAtMillis: Long = 0L
    private var firstPublishLogged = false

    private val _progress = MutableStateFlow<IdentityEnrichmentLiveSnapshot?>(null)
    val progress: StateFlow<IdentityEnrichmentLiveSnapshot?> = _progress.asStateFlow()
    private val _remaining = MutableStateFlow<Int?>(null)

    /** Compatibility projection for existing readers; progress consumers use the atomic snapshot. */
    val remaining: StateFlow<Int?> = _remaining.asStateFlow()

    /**
     * Call once per worker run, before its first game is attempted. [continuesSweep] is true when this run
     * resumes a sweep an earlier slice left mid-way; it then counts on from that slice (see [sweepCarried]).
     */
    fun start(totalGames: Int, continuesSweep: Boolean = false): Long = synchronized(gate) {
        val runId = ++nextRunId
        val unresolved = totalGames.coerceAtLeast(0)
        if (!continuesSweep) sweepCarried = 0
        runCarried = sweepCarried
        activeRunId = runId
        startedAtMillis = System.currentTimeMillis()
        firstPublishLogged = false
        val snapshot = unresolved.takeIf { it > 0 }?.let {
            IdentityEnrichmentLiveSnapshot(runId = runId, attempted = runCarried, total = runCarried + it)
        }
        _progress.value = snapshot
        _remaining.value = snapshot?.remaining
        if (snapshot == null) {
            Log.i(IdentityProgressLogTag, "start: nothing unresolved, no fraction offered")
            JobProgressState.clear(JobKind.ENRICH)
        } else {
            Log.i(
                IdentityProgressLogTag,
                "start: $unresolved unresolved" + if (runCarried > 0) ", continuing after $runCarried" else "",
            )
            JobProgressState.report(JobKind.ENRICH, seen = snapshot.attempted, total = snapshot.total)
        }
        runId
    }

    /** Compatibility entry point. The worker uses the run-token overload to reject stale callbacks. */
    fun publish(attemptedSoFar: Int) {
        val runId = synchronized(gate) { activeRunId } ?: return
        publish(runId, attemptedSoFar)
    }

    /**
     * Keep each attempted/total pair attached to the exact run that produced these decisions. [attemptedSoFar]
     * counts from this run's own start; the carried slices are added here.
     */
    fun publish(runId: Long, attemptedSoFar: Int) = synchronized(gate) {
        if (activeRunId != runId) return@synchronized
        val current = _progress.value ?: return@synchronized
        val attempted = maxOf(current.attempted, (runCarried + attemptedSoFar).coerceIn(0, current.total))
        if (attempted == current.attempted) return@synchronized
        val updated = current.copy(attempted = attempted)
        _progress.value = updated
        _remaining.value = updated.remaining
        JobProgressState.report(JobKind.ENRICH, seen = updated.attempted, total = updated.total)
        if (!firstPublishLogged && attemptedSoFar > 0) {
            firstPublishLogged = true
            val elapsed = System.currentTimeMillis() - startedAtMillis
            Log.i(
                IdentityProgressLogTag,
                "first movement after " + elapsed + "ms (attempted=" + attempted +
                    " of " + updated.total + ")",
            )
        }
    }

    /**
     * A stale worker exit cannot clear a newer run's progress. [continuesSweep] is true only when the run ended
     * on its time slice with the sweep unfinished, so the next run counts on from here; every other exit
     * (finished, failed, cancelled) ends the sweep's count.
     */
    fun clear(runId: Long? = null, continuesSweep: Boolean = false) = synchronized(gate) {
        if (runId != null && activeRunId != runId) return@synchronized
        val current = _progress.value
        sweepCarried = if (continuesSweep) current?.attempted ?: sweepCarried else 0
        if (current != null) {
            val elapsed = System.currentTimeMillis() - startedAtMillis
            Log.i(
                IdentityProgressLogTag,
                "clear after " + elapsed + "ms (attempted=" + current.attempted +
                    " of " + current.total + ")",
            )
        }
        activeRunId = null
        firstPublishLogged = false
        _progress.value = null
        _remaining.value = null
        JobProgressState.clear(JobKind.ENRICH)
    }
}

/**
 * Runs identity enrichment + collapse as DURABLE background work — WorkManager persists the request, so if
 * the app is killed mid-hash (the big DS/3DS whole-file passes take a while) the job resumes on the next
 * process start rather than being lost. The enricher is idempotent (only unresolved games), so a resumed
 * run simply continues. No constraints — fully offline, no network. `retry()` on failure so a transient SAF
 * hiccup doesn't abandon the library.
 */
class IdentityEnrichmentWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var liveProgressRunId: Long? = null
        // True only for the time-sliced exit below, which the next run resumes from the kept cursor.
        var slicedOut = false
        return try {
            val database = MimirDatabaseFactory.get(applicationContext)
            val dao = database.gameDao()
            val provider = AssetDatProvider(applicationContext)
            val enricher = LibraryIdentityEnricher(
                dao = dao,
                datSource = BundledDatSource(provider),
                idSource = BundledIdSource(provider),
                opener = AndroidContentOpener(applicationContext),
                // first-run identification never decompresses a CHD.
                chdIdentity = ChdIdentitySource.HEADER_DECLARED,
                identityDao = database.gameIdentityDao(),
                payloadProvider = provider,
                nanoTime = SystemClock::elapsedRealtimeNanos,
                // One commit per batch; hashing stays on the background pool.
                runPublishTransaction = { block -> database.withTransaction { block() } },
                hashDispatcher = IdentityHashingDispatcher,
            )
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val sliceDeadlineNanos = MonotonicDeadline.deadlineAfter(startedAt, MaxWorkerSliceNanos)
            var attempted = 0
            var matched = 0
            var lastAttempted: Int
            var retryable = 0
            // The restart bit belongs to the source event, not every retry of this WorkSpec. Consuming it
            // again after WorkManager backoff would erase the same large sweep's newly persisted checkpoint.
            if (inputData.getBoolean(InputRestartSweep, false) && runAttemptCount == 0) {
                IdentityEnrichmentSweepCursorStore.clear(applicationContext)
            }
            val currentGeneration = ProcessSourceRootMutations.coordinator.currentIndexGeneration()
            val restoredCheckpoint = IdentityEnrichmentSweepCursorStore.read(
                applicationContext,
                identitySweepPolicyStamp,
                currentGeneration,
            )
            var cursor = restoredCheckpoint?.cursor
            var cursorGeneration = restoredCheckpoint?.generation
            var exhausted: Boolean
            // Sourced once here; the loop's own end-of-iteration re-read below already keeps it current
            // for `setProgress` and the loop condition, so reusing that value costs no extra query per page.
            var remaining = dao.unresolvedPrimaryCount().first()
            // The honest denominator for THIS worker run's live probe -- see IdentityEnrichmentLiveProgress's
            // KDoc. `remaining <= 0` is a no-op there (no fraction), matching the DB-derived fallback's own gate.
            // A restored checkpoint means an earlier slice of this same sweep stopped mid-way, so the live count
            // carries on from it rather than restarting.
            liveProgressRunId = IdentityEnrichmentLiveProgress.start(
                remaining,
                continuesSweep = restoredCheckpoint != null,
            )
            val progressRunId = requireNotNull(liveProgressRunId)
            do {
                // `attempted` (outer var) holds every game already accounted for from PRIOR chunks; it is
                // read here, once, into a val precisely so the per-file callback below -- fired concurrently
                // from up to `concurrency` hashing threads inside this `enrich()` call -- never reads a
                // mutable var that this same coroutine goes on to reassign a few lines down.
                val attemptedBeforeThisSlice = attempted
                // SD01b: reconcile once after the drain, not per 250-game slice (full-table hash scan).
                val report = withContext(IdentityHashingDispatcher) {
                    enricher.enrich(
                        maxGames = EnrichmentChunk,
                        concurrency = identityHashConcurrency(Runtime.getRuntime().availableProcessors()),
                        reconcile = false,
                        afterCursor = cursor,
                        afterGeneration = cursorGeneration,
                        absoluteDeadlineNanos = sliceDeadlineNanos,
                        onGameAttempted = { attemptedInSlice ->
                            IdentityEnrichmentLiveProgress.publish(
                                progressRunId,
                                attemptedBeforeThisSlice + attemptedInSlice,
                            )
                        },
                    )
                }
                lastAttempted = report.attempted
                attempted += report.attempted
                matched += report.matched
                retryable += report.retryable
                cursor = report.nextCursor
                cursorGeneration = report.nextGeneration
                exhausted = report.exhausted
                if (identitySweepMustClearCursor(exhausted, report.restartFromBeginning)) {
                    IdentityEnrichmentSweepCursorStore.clear(applicationContext)
                } else {
                    report.nextCursor?.let { nextCursor ->
                        val nextGeneration = requireNotNull(report.nextGeneration) {
                            "identity cursor is missing its source-generation witness"
                        }
                        IdentityEnrichmentSweepCursorStore.write(
                            applicationContext,
                            identitySweepPolicyStamp,
                            nextCursor,
                            nextGeneration,
                        )
                    }
                }
                remaining = dao.unresolvedPrimaryCount().first()
                setProgress(identityEnrichmentProgressData(IdentityEnrichmentStage.IDENTIFYING, attempted, matched, remaining))
            } while (
                remaining > 0 &&
                lastAttempted > 0 &&
                !exhausted &&
                MonotonicDeadline.hasTimeRemaining(sliceDeadlineNanos, SystemClock.elapsedRealtimeNanos())
            )
            val shouldRetry = enrichmentSweepShouldRetry(
                remaining = remaining,
                exhausted = exhausted,
                retryableThisRun = retryable,
            )
            // Preserve the generation-bound keyset cursor across a time-sliced retry. Exact reconciliation
            // and regrouping are ordered index mutations themselves, so running them before the drain would
            // invalidate our own checkpoint and force every large sweep back to its first page.
            val folded = if (shouldRetry) {
                0
            } else {
                // Grouping/dedupe is still active work after identity remaining reaches zero. Its total is
                // deliberately unknown, so consumers must render it indeterminate rather than 61/61 complete.
                // Clear the live probe FIRST: a stale IDENTIFYING remaining must never leak a numeric fraction
                // into a phase that is honestly indeterminate.
                IdentityEnrichmentLiveProgress.clear(progressRunId)
                setProgress(identityEnrichmentProgressData(IdentityEnrichmentStage.GROUPING, attempted, matched, remaining))
                enricher.reconcileNow()
                val regrouped = LibraryCollapseRegrouper(dao, database.gameIdentityDao()).regroup()
                check(GameIdentityRemapReplayer.replayAll(applicationContext)) {
                    "Identity fold committed but cross-store replay did not converge"
                }
                regrouped
            }
            Log.i(
                "MimirEnrich",
                "worker: matched $matched/$attempted, retryable $retryable, remaining $remaining, collapsed $folded dupes",
            )
            if (shouldRetry) {
                slicedOut = true
                Result.retry()
            } else {
                IdentityEnrichmentSweepCursorStore.clear(applicationContext)
                // The ONE place this can be enqueued from, and it is not a convenience. Re-verification
                // re-incurs exactly the hashing cost that name-first identification exists to avoid, so it
                // must never be in flight while identification still is. Reaching this line is the proof:
                // the sweep drained, reconciled, regrouped and replayed. Anything earlier -- an app start,
                // a scan, an onboarding step -- would be asserting that from outside instead of observing it.
                IdentityReverificationWorker.enqueueAfterIdentitySweep(applicationContext)
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e("MimirEnrich", "enrichment worker failed", failure)
            Result.retry()
        } finally {
            // Defensive catch-all for every exit this function has, including cancellation and the mid-loop
            // time-slice retry: no run's live probe may outlive that run. The GROUPING branch above already
            // clears it earlier for the common completed-successfully path; this covers the rest.
            liveProgressRunId?.let { IdentityEnrichmentLiveProgress.clear(it, continuesSweep = slicedOut) }
        }
        }

    companion object {
        /**
         * The persisted cursor contains a physical entry key. Bump this suffix whenever that key's
         * ordering/codec changes so an APK upgrade restarts safely instead of applying a legacy keyset
         * cursor to the new order.
         */
        private const val IdentitySweepPolicyStampSuffix = "|resident-key-p2"
        internal val identitySweepPolicyStamp =
            AthenaContainerIdentityPolicy.STAMP + IdentitySweepPolicyStampSuffix

        private const val UNIQUE_WORK = "mimir-identity-enrichment"
        private const val EnrichmentChunk = 250
        private const val MaxWorkerSliceNanos = 7L * 60L * 1_000_000_000L
        internal const val ProgressStage = "stage"
        internal const val ProgressAttempted = "attempted"
        internal const val ProgressMatched = "matched"
        internal const val ProgressRemaining = "remaining"
        private const val InputRestartSweep = "restart_source_sweep"

        /** Enqueue the (single, durable) enrichment job. KEEP = don't double-run if already scheduled. */
        fun enqueue(context: Context) {
            enqueue(context, ExistingWorkPolicy.KEEP)
        }

        /**
         * Guarantee one pass after catalogue stamps are cleared. Appending avoids cancelling a potentially
         * blocking whole-file hash while also closing KEEP's race where an almost-finished worker drops the
         * reset request.
         */
        suspend fun enqueueAfterIdentityReset(context: Context) {
            // Identity-policy/catalogue changes are migrations, not reactive library triggers: always enqueue
            // even if onboarding has not yet reached its normal enrichment step. A fresh install simply finds
            // no rows; an upgraded install must never clear rows and then skip the required trailing pass.
            val operation = enqueueOperation(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
            // WorkManager enqueue is asynchronous. The catalogue latch must not commit its new stamp
            // until the request is durably present in WorkManager's database.
            withContext(Dispatchers.IO) { operation.result.get() }
        }

        /**
         * A source scan can replace a retryable row without changing the unresolved count. Append a
         * generation-reset pass so KEEP cannot swallow the event behind an already-running worker.
         */
        suspend fun enqueueAfterSourceMutation(context: Context) {
            if (!identityEnrichmentAllowed(
                    firstRun = OnboardingStore.isFirstRun(context),
                    step = OnboardingStore.checkpoint(context).step,
                )
            ) {
                return
            }
            val operation = enqueueOperation(
                context = context,
                policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
                restartSweep = true,
            )
            withContext(Dispatchers.IO) { operation.result.get() }
        }

        /** Reads the existing unique worker's progress; this creates no work and never guesses a total. */
        internal suspend fun currentProgress(context: Context): IdentityEnrichmentProgress? {
            val infos = WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWork(UNIQUE_WORK)
                .get()
            return identityEnrichmentProgressFor(infos.map { info ->
                IdentityEnrichmentWorkRecord(
                    active = !info.state.isFinished,
                    stage = info.progress.getString(ProgressStage),
                    attempted = info.progress.getInt(ProgressAttempted, 0),
                    matched = info.progress.getInt(ProgressMatched, 0),
                    remaining = info.progress.getInt(ProgressRemaining, 0),
                )
            })
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            if (!identityEnrichmentAllowed(
                    firstRun = OnboardingStore.isFirstRun(context),
                    step = OnboardingStore.checkpoint(context).step,
                )
            ) {
                return
            }
            enqueueOperation(context, policy)
        }

        private fun enqueueOperation(
            context: Context,
            policy: ExistingWorkPolicy,
            restartSweep: Boolean = false,
        ) =
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                policy,
                OneTimeWorkRequestBuilder<IdentityEnrichmentWorker>()
                    .setInputData(
                        Data.Builder()
                            .putBoolean(InputRestartSweep, restartSweep)
                            .build(),
                    )
                    // A large library is identified in 7-minute slices, and the default backoff (30 s,
                    // exponential) put a dead half-minute, then a minute, between them: on an RG Rotate the
                    // favourites step sat still from 21:16:10 to 21:16:40. WorkManager's floor, linear.
                    .setBackoffCriteria(
                        BackoffPolicy.LINEAR,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                    .build(),
            )

        /**
         * Wait until the enricher has finished with [gameIds] specifically.
         *
         * [awaitSettled] cannot answer this. It returns as soon as the table holds no unresolved primary
         * — and on a first run that is TRUE BEFORE THE SCAN HAS INSERTED ANYTHING. Measured on a test device 2026-09-17: three worker passes logged `matched 0/0, remaining 0` at 17:33:45 because the
         * library was still empty, `awaitSettled` returned "settled" on them, and the artwork batch started
         * at 17:35:34 against 66 games whose identity did not exist yet. Identification actually finished
         * at 17:57:43 — so the fetch spent twenty-two minutes racing the hash it was supposed to follow,
         * and every game it looked up was looked up under a name the catalogue had not corrected yet.
         *
         * Counting a NAMED set closes that hole: an empty answer can only mean those rows are done.
         *
         * **Load-bearing assumption:** every id in [gameIds] is ALREADY a `games` row when the first poll
         * runs. `SELECT COUNT(*) … WHERE gameId IN (:ids) AND identityResolvedAtMillis IS NULL` reads a
         * row that does not exist yet as zero, i.e. as done — the same way `awaitSettled` fails. It holds
         * because the only caller passes `NewGameEnrichPending.gameIds`, which
         * `NewGameEnrichPromptState.recordAddedGameIds` builds from a COMPLETED scan's `addedGameIds`
         * (`NewGameEnrichPrompt.kt:78`); those rows are inserted before the pending record exists. A caller
         * that assembles ids any other way must count `IS NOT NULL` against `ids.size` instead.
         *
         * Returns true if the set reached zero, false if it gave up on a stall — see
         * [identityWaitShouldGiveUp]. Polls rather than observing a Flow, to keep the same shape (and the
         * same backoff) as [awaitSettled].
         */
        suspend fun awaitIdentified(
            context: Context,
            gameIds: Set<String>,
            stallBudgetMillis: Long = IdentityStallBudgetMillis,
            nowMillis: () -> Long = System::currentTimeMillis,
        ): Boolean {
            if (gameIds.isEmpty()) return true
            val dao = MimirDatabaseFactory.get(context.applicationContext).gameDao()
            // SQLite binds one variable per id and caps them (999 on older API levels), so a large
            // library is asked about in pieces rather than throwing at the worst possible moment.
            val chunks = gameIds.toList().chunked(500)
            var polls = 0
            var best = Int.MAX_VALUE
            var lastProgressAt = nowMillis()
            while (true) {
                val outstanding = withContext(Dispatchers.IO) {
                    chunks.sumOf { dao.unresolvedCountAmong(it) }
                }
                if (outstanding == 0) return true
                if (outstanding < best) {
                    best = outstanding
                    lastProgressAt = nowMillis()
                } else if (
                    identityWaitShouldGiveUp(
                        outstanding = outstanding,
                        bestOutstanding = best,
                        millisSinceProgress = nowMillis() - lastProgressAt,
                        stallBudgetMillis = stallBudgetMillis,
                    )
                ) {
                    return false
                }
                delay(settlePollDelayMillis(polls))
                polls++
            }
        }

        /**
         * Wait through duplicate regrouping, including any newly promoted unresolved primary rows.
         *
         * **TERMINATION IS NOT A PROPERTY OF THIS FUNCTION. Callers must bound it.**
         *
         * The `while (true)` below exits only when the unique work reaches a finished state or the
         * remaining count stops moving. A WorkSpec that returned `retry` sits in ENQUEUED-with-backoff,
         * which is not `isFinished`, so against a permanently-retryable row this loop does not return —
         * that is the P0-A defect, observed as a ~10-hour frozen first-run on RP5 and Thor. Three partial
         * mechanisms now overlap and none was designed with the others: the no-progress guard below, the
         * poll backoff in [settlePollDelayMillis], and a `withTimeoutOrNull` at the ONE current call site
         * in `MimirOnboardingCoordinator`. Only the last actually bounds this.
         *
         * A new call site without its own timeout silently reintroduces the hang. Do not add one.
         */

        /**
         * [awaitSettled], bounded the way its KDoc demands of every call site. The favourites step is the
         * second caller, and it shipped without a bound; see [awaitSettledUnlessStalled].
         */
        internal suspend fun awaitSettledUnlessStalled(context: Context): IdentitySettleOutcome {
            val dao = MimirDatabaseFactory.get(context.applicationContext).gameDao()
            return awaitSettledUnlessStalled(
                awaitSettled = { awaitSettled(context) },
                outstanding = { withContext(Dispatchers.IO) { dao.unresolvedPrimaryCount().first() } },
            )
        }

        suspend fun awaitSettled(context: Context) {
            val manager = WorkManager.getInstance(context.applicationContext)
            var lastCompletedRemaining = -1
            var activePolls = 0
            while (true) {
                val active = withContext(Dispatchers.IO) {
                    try {
                        manager.getWorkInfosForUniqueWork(UNIQUE_WORK).get()
                            .any { !it.state.isFinished }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                } ?: return
                if (active) {
                    // P0-A: a WorkSpec that returned retry sits in ENQUEUED-with-backoff, which is not
                    // `isFinished`, so this branch can hold for the caller's whole budget. A flat 120ms
                    // poll meant ~750 binder round-trips per first-run on low-end handhelds. Back off
                    // instead: still sub-second for the common case where work finishes quickly.
                    delay(settlePollDelayMillis(activePolls))
                    activePolls++
                    continue
                }
                activePolls = 0
                val remaining = MimirDatabaseFactory.get(context.applicationContext)
                    .gameDao()
                    .unresolvedPrimaryCount()
                    .first()
                if (remaining == 0) return
                // A completed pass that moved nothing has settled with retryable rows (A1D: a parent CHD
                // the user has not attached yet). Re-enqueueing the identical sweep would loop forever;
                // those rows retry on the next lifecycle trigger instead.
                if (remaining == lastCompletedRemaining) return
                lastCompletedRemaining = remaining
                enqueue(context)
                delay(120)
            }
        }
}
}

private fun identityEnrichmentProgressData(
stage: IdentityEnrichmentStage,
attempted: Int,
matched: Int,
remaining: Int,
): Data = Data.Builder()
.putString(IdentityEnrichmentWorker.ProgressStage, stage.name)
.putInt(IdentityEnrichmentWorker.ProgressAttempted, attempted)
.putInt(IdentityEnrichmentWorker.ProgressMatched, matched)
.putInt(IdentityEnrichmentWorker.ProgressRemaining, remaining)
.build()

/**
 * A sweep retries through WorkManager while it can still make forward progress: more pages, an early
 * time-slice exit, or unresolved rows this run never attempted (a source added mid-sweep inserts rows
 * BEHIND the keyset cursor; the exhausted cursor was cleared, so the retried run sweeps them). A COMPLETE
 * sweep whose entire remainder was attempted-and-retryable finishes as success — those rows wait for the
 * next bounded lifecycle trigger (process start, library change, capability replay), never an unbounded
 * immediate retry loop.
 */
/**
 * P0-A: poll backoff for [IdentityEnrichmentWorker.awaitSettled] while work is still active.
 *
 * Doubles from 120ms to a 960ms ceiling. The first four polls cover the first ~1s, so a sweep that
 * finishes promptly is still observed promptly; a sweep parked in ENQUEUED-with-backoff then costs ~1
 * query/second instead of ~8.
 */
/**
 * How long [IdentityEnrichmentWorker.awaitIdentified] tolerates the outstanding count not moving.
 *
 * Five minutes, and deliberately far larger than the work it guards. Measured on a test device
 * 2026-09-17, a whole 66-game library identified in 113 seconds once it was asked to start -- so any
 * five-minute silence here is not a slow library, it is a sweep that is not running.
 *
 * The margin is for the one case that legitimately freezes the count without being stuck: a single very
 * large image (the 4.43 GB Conker sample) hashing from SD while nothing else completes. That costs a couple of
 * minutes at most.
 *
 * Sized in PROGRESS rather than elapsed time on purpose. The first version of this escape was a
 * 15-minute stopwatch, and a stopwatch cannot tell "slow" from "stuck" -- see [identityWaitShouldGiveUp]
 * for what that would have done to the player's own run.
 */
internal const val IdentityStallBudgetMillis = 5L * 60L * 1000L

/**
 * Whether an identity wait has stopped making progress and should stop waiting.
 *
 * **The first version of this escape was a 15-minute wall clock, and it was wrong for a measured long identification run.** Identification of the 66-game sample took 24 minutes on 2026-09-17,
 * measured: 17:33:45 to 17:57:43. A 15-minute stopwatch fires at ~17:48 with games still unidentified,
 * the artwork offer arrives early against half-identified names, and the complaint the wait exists to fix
 * reproduces exactly — while the code claims to have fixed it. Slow is not stuck.
 *
 * [bestOutstanding] is the LOWEST count seen, not the previous one: the test is "has this ever got
 * better", so a transient re-read that ticks upward cannot reset the clock and hide a stall.
 *
 * Pure, so the rule itself is under test rather than only the plumbing around it.
 */
internal fun identityWaitShouldGiveUp(
outstanding: Int,
bestOutstanding: Int,
millisSinceProgress: Long,
stallBudgetMillis: Long = IdentityStallBudgetMillis,
): Boolean = outstanding > 0 &&
outstanding >= bestOutstanding &&
millisSinceProgress >= stallBudgetMillis

/**
 * How a bounded identity wait ended. [identified] is how many unresolved games were resolved while it waited:
 * zero when the library was already identified and a pass only re-tried rows that cannot resolve.
 */
internal data class IdentitySettleOutcome(val settled: Boolean, val identified: Int)

/**
 * Wait for [awaitSettled], but give up once [outstanding] has not fallen for [stallBudgetMillis].
 *
 * Bounded by PROGRESS, not a stopwatch, for the reason [identityWaitShouldGiveUp] gives: identification of a
 * large library is slow, not stuck (a 5529-game library took two 7-minute slices on the RG Rotate), and a
 * wall clock cannot tell the two apart. The unresolved count moves every 250-game chunk, so a live sweep
 * resets the clock long before the budget; a sweep that is not running does not.
 */
internal suspend fun awaitSettledUnlessStalled(
    awaitSettled: suspend () -> Unit,
    outstanding: suspend () -> Int,
    stallBudgetMillis: Long = IdentityStallBudgetMillis,
    pollMillis: Long = IdentityStallPollMillis,
    nowMillis: () -> Long = System::currentTimeMillis,
): IdentitySettleOutcome = coroutineScope {
    val settled = async { awaitSettled() }
    val atStart = outstanding()
    var best = atStart
    var lastProgressAt = nowMillis()
    while (true) {
        // Wakes the moment the settle returns instead of sleeping out the poll.
        withTimeoutOrNull(pollMillis) { settled.join() }
        if (settled.isCompleted) break
        val count = outstanding()
        if (count < best) {
            best = count
            lastProgressAt = nowMillis()
        } else if (
            identityWaitShouldGiveUp(
                outstanding = count,
                bestOutstanding = best,
                millisSinceProgress = nowMillis() - lastProgressAt,
                stallBudgetMillis = stallBudgetMillis,
            )
        ) {
            settled.cancel()
            return@coroutineScope IdentitySettleOutcome(settled = false, identified = (atStart - best).coerceAtLeast(0))
        }
    }
    settled.await()
    IdentitySettleOutcome(settled = true, identified = (atStart - outstanding()).coerceAtLeast(0))
}

/** How often [awaitSettledUnlessStalled] reads the unresolved count: plenty against a five-minute budget. */
internal const val IdentityStallPollMillis = 5_000L

internal fun settlePollDelayMillis(consecutiveActivePolls: Int): Long =
(120L shl consecutiveActivePolls.coerceIn(0, 3)).coerceAtMost(960L)

internal fun enrichmentSweepShouldRetry(
remaining: Int,
exhausted: Boolean,
retryableThisRun: Int,
): Boolean = remaining > 0 && (!exhausted || remaining > retryableThisRun)

/** A stale publication refusal leaves the current unresolved page behind the pre-I/O checkpoint. */
internal fun identitySweepMustClearCursor(
exhausted: Boolean,
restartFromBeginning: Boolean,
): Boolean = exhausted || restartFromBeginning

/**
 * Hashing threads for the identity sweep: their own pool, at Android's background priority. The sweep used to hash
 * on Dispatchers.Default, the pool the app's own coroutine work shares, at normal priority -- on the RG Rotate
 * () two hashing threads sat at 65-100 % CPU beside
 * the UI. Background priority lets the scheduler put frames first; the work itself is unchanged.
 */
internal val IdentityHashingDispatcher by lazy {
    val count = java.util.concurrent.atomic.AtomicInteger()
    java.util.concurrent.Executors.newFixedThreadPool(
        identityHashConcurrency(Runtime.getRuntime().availableProcessors()) + 1,
    ) { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "mimir-identity-hash-${count.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/**
 * How many files the sweep hashes at once: half the cores, 2 to 4. The old fixed 3 left most of an 8-core
 * handheld idle while hashing was CPU-bound (digest ~43 %, FUSE read ~56 % per thread); half keeps the other
 * cores for the UI and the FUSE daemon, and 4 keeps an SD card's reads from scattering.
 */
internal fun identityHashConcurrency(availableProcessors: Int): Int = (availableProcessors / 2).coerceIn(2, 4)

// FAVORITES joined 2026-09-27: it sits between THEME and DONE (QA-16 added it later) but was never allowed, so a
// sweep capped mid-library could not re-enqueue while the player was choosing favourites -- the very step
// that now waits for identification to finish before its Tome opens.
internal fun identityEnrichmentAllowed(firstRun: Boolean, step: OnboardingStep): Boolean =
!firstRun || step == OnboardingStep.METADATA || step == OnboardingStep.SETTLING ||
    step == OnboardingStep.THEME || step == OnboardingStep.FAVORITES || step == OnboardingStep.DONE
