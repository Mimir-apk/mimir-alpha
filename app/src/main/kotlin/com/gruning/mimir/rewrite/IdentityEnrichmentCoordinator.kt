package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.gruning.mimir.rewrite.core.model.DisplayTitle
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.metadata.AthenaIdentityPolicy
import com.gruning.mimir.rewrite.core.metadata.AthenaContainerIdentityPolicy
import com.gruning.mimir.rewrite.core.metadata.BundledDatManifest
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.ProcessSourceRootMutations
import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import com.gruning.mimir.rewrite.feature.library.WarmupLedger
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reactive trigger for identity enrichment: it observes the live count of games the enricher hasn't
 * touched and enqueues the DURABLE [IdentityEnrichmentWorker] whenever that count is > 0 — so a library
 * scanned before this feature, a fresh setup scan, and a mid-session source add all get resolved. As the
 * worker persists `canonicalTitle` per game, the shelf's Room Flow re-emits and tiles update LIVE.
 *
 * WorkManager owns durability (survives a full app kill mid-hash) and single-flight (KEEP policy); this
 * coordinator only bridges the count Flow to an enqueue. Start it ONCE per process. Fully offline.
 */
/**
 * How long the first enrichment enqueue waits for the cold-start mask to settle before proceeding anyway.
 * The ledger only begins on a true cold start, so this bound is what keeps every other entry path working.
 */
private const val EnrichmentMaskHoldMs = 12_000L

object IdentityEnrichmentCoordinator {

    /** Durable enrichment enqueue/retry observation is process work; contain child failures here. */
    internal val scopeFailureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e("MimirIdentityEnrichment", "background enrichment work failed in $context", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + scopeFailureHandler)
    private val started = AtomicBoolean(false)

    /** Begin observing the library; enqueues the durable enrichment worker when work exists. Idempotent. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        val database = MimirDatabaseFactory.get(ctx)
        val dao = database.gameDao()
        val identityDao = database.gameIdentityDao()
        // *batch retitling behind the cold-start mask.* Enrichment persists
        // `canonicalTitle` per game, the shelf's Room Flow re-emits, and tiles retitle LIVE -- correct
        // truth, but on a cold start it lands just after first paint and is one of the things that read as
        // the shelf "always fixing itself". Holding the first enqueue until the warm ledger settles moves
        // that churn behind the mask.
        //
        // BOUNDED, and that bound is load-bearing: the ledger only begins on a true cold start, so a
        // same-process re-create or any path that never runs it would otherwise starve enrichment forever.
        // On those paths this waits [EnrichmentMaskHoldMs] and proceeds exactly as before. Nothing is
        // skipped -- only deferred -- and the durable worker's own KEEP policy is untouched.
        scope.launch {
            withTimeoutOrNull(EnrichmentMaskHoldMs) { WarmupLedger.settled.first { it } }
            IdentityEnrichmentWorker.enqueueAfterSourceMutation(ctx)
        }
        scope.launch {
            var deliveredRevision = 0L
            while (true) {
                val revision = ProcessSourceRootMutations.coordinator.sourceMutations.first {
                    it > deliveredRevision
                }
                deliverSourceMutationRevision(
                    enqueue = { IdentityEnrichmentWorker.enqueueAfterSourceMutation(ctx) },
                    onFailure = { failure, retryDelayMillis ->
                        Log.w(
                            "MimirEnrich",
                            "source-change identity enqueue failed; retrying in ${retryDelayMillis}ms",
                            failure,
                        )
                    },
                )
                deliveredRevision = revision
            }
        }
        scope.launch {
            runCatching {
                BundledDatCatalogueLatch.reconcile(
                    context = ctx,
                    currentStamp = BundledDatManifest.catalogueStamp,
                    resetUnmatchedIdentity = {
                        resetUnmatchedIdentityForCatalogueChange(ctx) {
                            ProcessSourceRootMutations.coordinator.commitIndexMutation {
                                dao.clearUnmatchedIdentity()
                            }
                        }
                    },
                )
            }.onSuccess { result ->
                if (result.changed) {
                    Log.i(
                        "MimirEnrich",
                        "bundled DAT catalogue changed; cleared ${result.clearedUnmatched} unmatched stamps",
                    )
                }
            }.onFailure { error ->
                // Do not persist on failure: the next process start retries the latch.
                Log.w("MimirEnrich", "bundled DAT catalogue latch failed; will retry", error)
            }
            runCatching {
                IdentityAuthorityPolicyLatch.reconcile(
                    context = ctx,
                    currentStamp = AthenaIdentityPolicy.STAMP,
                    resetLegacyIdentity = {
                        resetLegacyIdentityForAuthorityPolicyChange(
                            ctx,
                        ) {
                            ProcessSourceRootMutations.coordinator.commitIndexMutation {
                                dao.clearLegacyFilenameKeyedIdentity()
                            }
                        }
                    },
                )
            }.onSuccess { result ->
                if (result.changed) {
                    Log.i(
                        "MimirEnrich",
                        "identity authority policy changed; reopened ${result.clearedLegacy} legacy projections",
                    )
                }
            }.onFailure { error ->
                // Do not persist on failure: the next process start retries the bounded policy replay.
                Log.w("MimirEnrich", "identity authority policy latch failed; will retry", error)
            }
            runCatching {
                IdentityContainerCapabilityLatch.reconcile(
                    context = ctx,
                    currentStamp = AthenaContainerIdentityPolicy.STAMP,
                    resetContainerIdentity = { storedStamp ->
                        resetContainerIdentityForCapabilityChange(
                            ctx,
                            clearContainerIdentity = {
                                ProcessSourceRootMutations.coordinator.commitIndexMutation {
                                    when (containerCapabilityReplayKind(storedStamp)) {
                                        ContainerCapabilityReplay.NARROW_CHD ->
                                            dao.reopenTerminalChdAttempts()
                                        ContainerCapabilityReplay.NARROW_ZIP ->
                                            dao.reopenTerminalZipAttempts()
                                        ContainerCapabilityReplay.FULL ->
                                            database.withTransaction {
                                                executeFullContainerCapabilityReplay(
                                                    quarantineA1C = {
                                                        identityDao.quarantineAndClearA1CContainerIdentity(
                                                            System.currentTimeMillis(),
                                                        )
                                                    },
                                                    reopenTerminalZip = dao::reopenTerminalZipAttempts,
                                                )
                                            }
                                    }
                                }
                            },
                        )
                    },
                )
            }.onSuccess { result ->
                if (result.changed) {
                    Log.i(
                        "MimirEnrich",
                        "container identity capability changed; reopened ${result.reopened} rows",
                    )
                }
            }.onFailure { error ->
                Log.w("MimirEnrich", "container capability latch failed; will retry", error)
            }
            runCatching {
                repairDiscTaggedCanonicalTitlesOrdered(
                    loadPrimaries = dao::allPrimaryGames,
                    persist = { primary, title ->
                        dao.setIdentity(
                            gameId = primary.gameId,
                            title = title,
                            collapse = primary.collapseKey,
                            origin = primary.identityOrigin,
                            ts = primary.identityResolvedAtMillis ?: System.currentTimeMillis(),
                        )
                    },
                )
            }.onSuccess { repaired ->
                if (repaired > 0) Log.i("MimirEnrich", "repaired $repaired disc-tagged multi-disc identities")
            }.onFailure { error ->
                Log.w("MimirEnrich", "legacy canonical-title repair failed; continuing enrichment", error)
            }
            // Both inputs, observed. This read `unresolvedPrimaryCount().distinctUntilChanged()` and
            // then consulted the onboarding step INSIDE the collector -- two conditions, one of them
            // watched. The step changes on its own schedule, and when it did the count had not moved, so
            // `distinctUntilChanged` swallowed the emission and the gate was never re-evaluated. This
            // scope is process-lifetime (see `scope` above), not lifecycle-bound, so nothing restarted
            // the collector either: the decision was taken once, against whatever step happened to be
            // current at the instant the scan finished inserting, and never revisited.
            //
            // Measured on the Thor 2026-09-17. The scan finished at 17:33:45 and the count went
            // 0 -> 66 while onboarding was still short of METADATA, so nothing enqueued. Identification
            // did not begin until 17:55:49 -- twenty-two minutes later, and only because resuming the
            // app recomposed `MimirOnboardingCoordinator`'s step-advance effect, which enqueued it from a
            // different code path entirely. It then finished in 113 seconds. The reported delay covered the whole
            // window; hashing had not been asked to start during the initial wait.
            //
            // `distinctUntilChanged` now applies to the DECISION rather than the count, so a false->true
            // transition fires no matter which input moved, and a true that stays true still enqueues
            // only once.
            identityEnrichmentTrigger(
                unresolvedPrimaryCount = dao.unresolvedPrimaryCount(),
                checkpoints = OnboardingStore.checkpoints(ctx),
                firstRun = { OnboardingStore.isFirstRun(ctx) },
            ).collect { shouldEnqueue -> if (shouldEnqueue) IdentityEnrichmentWorker.enqueue(ctx) }
        }
    }
}

/**
 * When a background identity sweep should be asked for: there is something unresolved AND onboarding has
 * reached a step where identifying is allowed.
 *
 * Extracted from the collector in [IdentityEnrichmentCoordinator] because the defect here was never in
 * the predicate -- [identityEnrichmentAllowed] was always correct -- it was in the WIRING, and a pure
 * predicate test cannot see wiring. The old shape was
 * `unresolvedPrimaryCount().distinctUntilChanged().collect { read the step inside }`: two conditions,
 * one of them observed. The step moves on its own schedule, and when it did the count had not changed,
 * so `distinctUntilChanged` dropped the emission and the decision was never retaken.
 *
 * Nothing rescued it from here, either. The coordinator's scope is process-lifetime, not lifecycle-bound,
 * so the collector does not restart when the app resumes.
 *
 * [firstRun] is a lambda rather than a value because it is read per emission, exactly as before: it can
 * flip while this flow is alive (onboarding completes), and capturing it once would pin the gate shut for
 * the life of the process.
 *
 * `distinctUntilChanged` now applies to the DECISION, so a false->true transition fires whichever input
 * moved, and a decision that stays true still enqueues only once.
 */
internal fun identityEnrichmentTrigger(
    unresolvedPrimaryCount: Flow<Int>,
    checkpoints: Flow<OnboardingCheckpoint>,
    firstRun: () -> Boolean,
): Flow<Boolean> =
    combine(unresolvedPrimaryCount, checkpoints) { count, checkpoint ->
        count > 0 && identityEnrichmentAllowed(firstRun = firstRun(), step = checkpoint.step)
    }.distinctUntilChanged()

/**
 * Persists one trailing source-change sweep without allowing a transient WorkManager/database failure to
 * kill the process-lifetime revision collector. StateFlow conflates newer revisions while this retries;
 * after this delivery succeeds, the collector immediately observes the latest outstanding revision.
 */
internal suspend fun deliverSourceMutationRevision(
    enqueue: suspend () -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) },
    onFailure: (Throwable, Long) -> Unit = { _, _ -> },
) {
    var failures = 0
    while (true) {
        try {
            enqueue()
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val retryDelayMillis = sourceMutationEnqueueRetryDelayMillis(failures)
            onFailure(failure, retryDelayMillis)
            pause(retryDelayMillis)
            failures++
        }
    }
}

internal fun sourceMutationEnqueueRetryDelayMillis(consecutiveFailures: Int): Long =
    (250L shl consecutiveFailures.coerceIn(0, 5)).coerceAtMost(8_000L)

/**
 * A capability replay is narrow only when the stored stamp is the immediately superseded stamp for the
 * current capability. A skipped-version upgrade must take the full closure: for example A1C→current needs
 * both the parent-chain CHD replay introduced by A1D and the ZIP payload replay introduced here.
 */
internal fun containerCapabilityReplayIsNarrowChdReopen(storedStamp: String?): Boolean =
    containerCapabilityReplayKind(storedStamp) == ContainerCapabilityReplay.NARROW_CHD

internal enum class ContainerCapabilityReplay {
    NARROW_CHD,
    NARROW_ZIP,
    FULL,
}

internal fun containerCapabilityReplayKind(storedStamp: String?): ContainerCapabilityReplay = when (storedStamp) {
    AthenaContainerIdentityPolicy.PREVIOUS_A1D_STAMP -> ContainerCapabilityReplay.NARROW_ZIP
    else -> ContainerCapabilityReplay.FULL
}

/**
 * A full replay composes the historical A1C quarantine with the DEFAULT-route ZIP reopen. The caller owns
 * one Room transaction so no process death can persist only half of the capability closure.
 */
internal suspend fun executeFullContainerCapabilityReplay(
    quarantineA1C: suspend () -> Int,
    reopenTerminalZip: suspend () -> Int,
): Int = quarantineA1C() + reopenTerminalZip()

/**
 * Restamp legacy multi-disc groups that persisted one matched disc's title on every member. The injected
 * seams keep the migration policy pure-JVM testable without widening [com.gruning.mimir.rewrite.core.storage.dao.GameDao].
 */
internal suspend fun repairDiscTaggedCanonicalTitles(
    loadPrimaries: suspend () -> List<GameEntity>,
    persist: suspend (primary: GameEntity, canonicalTitle: String) -> Unit,
): Int {
    var repaired = 0
    loadPrimaries().forEach { primary ->
        val current = primary.canonicalTitle ?: return@forEach
        val kind = primary.duplicateGroupKind?.let { raw ->
            DuplicateGroupKind.entries.firstOrNull { it.name == raw }
        }
        val neutral = DisplayTitle.canonicalForGroup(current, kind)
        if (neutral == current) return@forEach
        persist(primary, neutral)
        repaired++
    }
    return repaired
}

/**
 * Orders the legacy read→rewrite unit with scans, availability changes, and background publication.
 *
 * Holding the gate across this bounded Room-only repair is intentional: a same-key replacement either
 * lands before the snapshot and is repaired as the current resident, or lands after the repair and clears
 * the obsolete identity fields. File I/O never occurs in this unit.
 */
internal suspend fun repairDiscTaggedCanonicalTitlesOrdered(
    sourceIndexMutations: SourceRootMutationCoordinator = ProcessSourceRootMutations.coordinator,
    loadPrimaries: suspend () -> List<GameEntity>,
    persist: suspend (primary: GameEntity, canonicalTitle: String) -> Unit,
): Int = sourceIndexMutations.commitIndexMutation {
    repairDiscTaggedCanonicalTitles(loadPrimaries, persist)
}
