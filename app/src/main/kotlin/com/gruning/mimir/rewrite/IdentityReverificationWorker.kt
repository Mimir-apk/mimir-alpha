package com.gruning.mimir.rewrite

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gruning.mimir.rewrite.core.metadata.BundledDatSource
import com.gruning.mimir.rewrite.core.metadata.BundledIdSource
import com.gruning.mimir.rewrite.core.metadata.ChdIdentitySource
import com.gruning.mimir.rewrite.core.metadata.LibraryIdentityEnricher
import com.gruning.mimir.rewrite.core.metadata.MonotonicDeadline
import com.gruning.mimir.rewrite.core.metadata.ReverificationAttemptedPrefix
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.CancellationException

/**
 * Whether background re-verification may read from storage right now.
 *
 * **This replaced `setRequiresDeviceIdle`, which on the reference device was a gate that never opened.**
 * Measured on the Thor 2026-09-17 with the pass already queued and the device plugged in:
 *
 *     Required constraints:    CHARGING BATTERY_NOT_LOW IDLE
 *     Satisfied constraints:   CHARGING BATTERY_NOT_LOW DEVICE_NOT_DOZING ...
 *     Unsatisfied constraints: IDLE
 *     Ready: false
 *     TopAppTimer{com.gruning.mimir.rewrite} started 740467ms ago
 *
 * Mimir runs as the Home app. It is permanently the top app, so the device never reaches JobScheduler's
 * idle state and the job sat unready indefinitely. Every unit test still passed and the queue log still
 * said 63 games were waiting; the work would simply never have happened. A constraint that cannot be
 * satisfied fails silently and looks exactly like a feature that works.
 *
 * So the OS constraint is gone and the intent behind it is stated directly instead. "Idle" was only ever
 * shorthand for *"not while he is playing"*, and unlike Doze that is something Mimir observes first-hand:
 * a game or an app the player launched is presenting, or it is not. Charging and battery-not-low remain,
 * because they are constraints this device genuinely does satisfy.
 *
 * A PARKED game deliberately does not block. Parking exists precisely for the player browsing Mimir with a
 * game suspended behind them; treating that as "busy" would rebuild the gate that never opens, one layer
 * up, on a device where a parked game can outlive a charge cycle.
 *
 * **That paragraph described an intention this function did not implement until 2026-09-18.** It tested
 * [ActivePresentationPhases], which contains YIELDED_TO_SECONDARY_TASK, so a parked game blocked -- and so
 * did a system file picker the player had already dismissed. [ReverificationBlockingPhases] carries the
 * device measurement that caught it.
 */
internal fun reverificationMayRun(snapshot: MimirSessionSnapshot): Boolean =
    listOf(
        snapshot.gameSession?.phase,
        snapshot.primaryAppSession?.phase,
        snapshot.secondaryAppSession?.phase,
    ).none { phase -> phase != null && phase in ReverificationBlockingPhases }

/**
 * The phases in which something is genuinely ON SCREEN and the card belongs to it.
 *
 * **Deliberately not [ActivePresentationPhases].** That set's own KDoc says what it is for: *"Every phase
 * a session holds the launch mutex in"*. Holding the launch mutex and occupying the screen are different
 * questions, and the first version of this gate answered the wrong one by borrowing its set.
 *
 * What that cost, measured on the Thor 2026-09-18 on the run after the SAF folder grant:
 *
 *     23:57:11.067  MimirSession: rev=4 event=app_yielded  secondaryApp=YIELDED_TO_SECONDARY_TASK
 *     23:58:20.881  MimirReverify: standing down: a session is presenting
 *     23:58:50.896  MimirReverify: standing down: a session is presenting
 *     23:58:51.530  MimirSession: rev=8 event=app_ended    secondaryApp=ENDED
 *     23:59:51.095  MimirReverify: (runs)
 *
 * The system file picker that onboarding opens for the folder grant registers as a SECONDARY app session
 * and yields the screen a third of a second later. The ledger then held it in YIELDED_TO_SECONDARY_TASK
 * for **100 seconds** -- until Mimir came back to the foreground and walked it through RETURNING to ENDED
 * -- and the gate refused two scheduled runs in that window for a picker the player had already dismissed.
 *
 * Two wasted retries is the small version of this. The large one is the case [reverificationMayRun]'s own
 * doc already promised and did not deliver: **a PARKED game.** Parking exists for the player browsing
 * Mimir with a game suspended behind them, it also lands in YIELDED_TO_SECONDARY_TASK, and on this device
 * a parked game can outlive a charge cycle. Under the old set, a parked game closed this gate for as long
 * as it stayed parked -- which is `setRequiresDeviceIdle`'s failure with different words.
 *
 * YIELDED_TO_SECONDARY_TASK means the session has HANDED THE SCREEN BACK. Nothing is being presented and
 * the card is free. RECOVERING stays: it is short, it is a cleanup mutex, and cleanup is exactly the sort
 * of thing that should not be racing a background hash for the same card.
 */
internal val ReverificationBlockingPhases =
    ActivePresentationPhases - MimirSessionPhase.YIELDED_TO_SECONDARY_TASK

/**
 * Goes back and reads the games that were identified from their file NAME alone.
 *
 * [LibraryIdentityEnricher.reverifyNameResolved] is the mechanism and documents what it verifies and what
 * it deliberately does not. This class owns only the question of WHEN, and schedules it away from active play, for a reason worth stating plainly: this pass performs the very work that
 * was removed from first run. Identification on the reference device went from 22 minutes to 1.5 seconds
 * by not opening files, and the reading still has to happen eventually for the guard to mean anything.
 * Scheduling it wrong does not lose a feature -- it hands the player back the exact complaint the name-first
 * change was made to answer, only now at an unexpected moment.
 *
 * Hence three gates, each closing a different way of getting that wrong:
 *  - **enqueued only from the enrichment sweep's success path**, so identification is finished as an
 *    observed fact rather than an assumption;
 *  - **charging, and no session presenting**, so the SD card is never competing with a game the player is playing. The second half was `setRequiresDeviceIdle` until the Thor proved it never
 *    satisfies on a device where Mimir is the launcher -- [reverificationMayRun] carries that
 *    measurement;
 *  - **a bounded slice per run**, so a run that starts late still yields its pages back cleanly.
 */
class IdentityReverificationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val database = MimirDatabaseFactory.get(applicationContext)
        val dao = database.gameDao()
        val provider = AssetDatProvider(applicationContext)
        val enricher = LibraryIdentityEnricher(
            dao = dao,
            datSource = BundledDatSource(provider),
            idSource = BundledIdSource(provider),
            opener = AndroidContentOpener(applicationContext),
            // The pass that reads bytes; it keeps the recompute.
            chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
            identityDao = database.gameIdentityDao(),
            payloadProvider = provider,
            nanoTime = SystemClock::elapsedRealtimeNanos,
        )
        val deadline = MonotonicDeadline.deadlineAfter(
            SystemClock.elapsedRealtimeNanos(),
            MaxWorkerSliceNanos,
        )
        // Asked once up front as well as per file, so a run that starts mid-game opens nothing at all
        // rather than reading one disc before noticing. A flag rather than an early return because
        // doWork has an expression body.
        val standDown = !reverificationMayRun(MimirSessionAuthority.state.value)
        if (standDown) Log.i(TAG, "standing down: a session is presenting")
        var agreed = 0
        var conflicted = 0
        var noDatMatch = 0
        var skipped = 0
        var unreadable = 0
        var exhausted = false
        var published = true
        while (!standDown && SystemClock.elapsedRealtimeNanos() < deadline) {
            val report = enricher.reverifyNameResolved(
                absoluteDeadlineNanos = deadline,
                shouldYield = { !reverificationMayRun(MimirSessionAuthority.state.value) },
                onOutcome = ::logOutcome,
            )
            agreed += report.agreed
            conflicted += report.conflicted
            noDatMatch += report.noDatMatch
            skipped += report.skipped
            unreadable += report.unreadable
            published = report.published
            exhausted = report.exhausted
            // A refused publication means a source mutation landed mid-page; nothing was written and
            // nothing was marked, so looping here would just lose the same race again on a hot card.
            if (!published) break
            if (exhausted) break
            // Every conclusive outcome writes a marker, so the selector shrinks and this terminates. A
            // page of nothing but UNREADABLE writes no marker by design -- stop rather than spin on it.
            if (report.attempted == 0 || report.attempted == report.unreadable) break
            if (!reverificationMayRun(MimirSessionAuthority.state.value)) {
                Log.i(TAG, "standing down mid-run: a session started")
                break
            }
        }
        Log.i(
            TAG,
            "reverify: agreed $agreed, conflicts $conflicted, no-dat $noDatMatch, " +
                "skipped $skipped, unreadable $unreadable, exhausted $exhausted",
        )
        when {
            // Never success: nothing was attempted, so the debt is untouched and must come back.
            standDown -> Result.retry()
            !published -> Result.retry()
            exhausted -> Result.success()
            // Work remains and this slice is spent. retry() re-queues under the SAME constraints, so the
            // next slice still waits for a charger rather than starting wherever this one stopped.
            else -> Result.retry()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.e(TAG, "re-verification worker failed", failure)
        Result.retry()
    }

    private fun logOutcome(
        game: GameEntity,
        outcome: LibraryIdentityEnricher.ReverificationOutcome,
        observedTitle: String?,
    ) {
        when (outcome) {
            LibraryIdentityEnricher.ReverificationOutcome.CONFLICT ->
                // WARN, and it names both titles, because this is the only signal that exists when the
                // guard actually catches something. The identity is NOT changed -- see the pass -- so if
                // this line is never read, a wrong title simply stays wrong and nothing else breaks.
                Log.w(
                    TAG,
                    "CONFLICT ${game.platformId}: name says \"${game.canonicalTitle}\", " +
                        "bytes say \"$observedTitle\" (identity left as-is, hash recorded)",
                )

            LibraryIdentityEnricher.ReverificationOutcome.AGREED ->
                Log.i(TAG, "verified ${game.platformId}: ${game.canonicalTitle}")

            LibraryIdentityEnricher.ReverificationOutcome.NO_DAT_MATCH ->
                Log.i(TAG, "no DAT entry for the bytes of ${game.platformId}: ${game.canonicalTitle}")

            LibraryIdentityEnricher.ReverificationOutcome.NOT_NAME_RESOLVABLE,
            LibraryIdentityEnricher.ReverificationOutcome.UNREADABLE,
            -> Unit
        }
    }

    companion object {
        private const val TAG = "MimirReverify"
        private const val UNIQUE_WORK = "mimir-identity-reverification"

        /**
         * Deliberately long. A single CHD can take minutes to decompress, and a slice that cannot finish
         * one file would make no progress at all while still consuming a charge-and-idle window.
         */
        private const val MaxWorkerSliceNanos = 20L * 60L * 1_000_000_000L

        /**
         * Enqueue after the identity sweep has provably drained. Called from exactly one place.
         *
         * The count check is not an optimisation. Without it every completed sweep would queue a job that
         * wakes on the next charge only to find nothing to do, on every device whose library is already
         * fully content-verified -- which is most of them.
         */
        suspend fun enqueueAfterIdentitySweep(context: Context) {
            val app = context.applicationContext
            if (!identityEnrichmentAllowed(
                    firstRun = OnboardingStore.isFirstRun(app),
                    step = OnboardingStore.checkpoint(app).step,
                )
            ) {
                return
            }
            val pending = MimirDatabaseFactory.get(app).gameDao()
                .unverifiedResolvedPrimaryCount(ReverificationAttemptedPrefix)
            if (pending <= 0) return
            Log.i(TAG, "queued: $pending game(s) await content re-verification")
            WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_WORK,
                // KEEP: one durable job. A second sweep finishing must not restart a slice that is
                // already mid-file, and the job re-reads its own selector each page anyway.
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IdentityReverificationWorker>()
                    .setConstraints(
                        // No `setRequiresDeviceIdle`: see [reverificationMayRun] for the dumpsys output
                        // showing why it can never be satisfied on a device where Mimir IS the launcher.
                        Constraints.Builder()
                            .setRequiresCharging(true)
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    )
                    .build(),
            )
        }
    }
}
