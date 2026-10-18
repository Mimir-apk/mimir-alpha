package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_ALL_STORES_MASK
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_STORE_ACTIVE_ART
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_STORE_ART_NO_MATCH
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_STORE_ART_RESOLUTION
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_STORE_COMPANION_RETURN
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_STORE_CUSTOM_HERO
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_STORE_HOME_RETURN
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_STORE_HOME_SURFACE
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_STORE_HOME_TOMES
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_STORE_PLAY_TIME as REMAP_STORE_EXTERNAL_SESSION_ELAPSED
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_STORE_SAVED_ART
import com.gruning.mimir.rewrite.core.storage.dao.REMAP_STORE_TWEAKER_SELECTION
import com.gruning.mimir.rewrite.core.storage.entity.RemapJournalEntity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Crash-resumable Phase B for the Room v9 identity transaction. No provider/network work occurs here. */
internal object GameIdentityRemapReplayer {
    private val mutex = Mutex()
    private const val CriticalStoresMask =
        REMAP_STORE_HOME_TOMES or
            REMAP_STORE_EXTERNAL_SESSION_ELAPSED or
            REMAP_STORE_HOME_SURFACE or
            REMAP_STORE_HOME_RETURN or
            REMAP_STORE_COMPANION_RETURN or
            REMAP_STORE_TWEAKER_SELECTION

    /**
     * One bounded document rewrite per state-adoption-critical store. Media/cooldown work remains on the
     * IO replay lane, so first v9 launch is not proportional to journal rows × ten preference commits.
     */
    suspend fun replayCriticalBeforeStateLoad(context: Context): Boolean = mutex.withLock {
        val application = context.applicationContext
        val dao = MimirDatabaseFactory.get(application).gameIdentityDao()
        var rows = dao.pendingJournalMissingMask(CriticalStoresMask)
        val terminalTargets = dao.resolveCurrentGameIds(rows.map(RemapJournalEntity::toGameId))
        rows.filter { row -> terminalTargets[row.toGameId] == row.fromGameId }.forEach { row ->
            dao.voidJournal(
                row.journalId,
                System.currentTimeMillis(),
                "voided: transition resolved back to source",
            )
        }
        rows = dao.pendingJournalMissingMask(CriticalStoresMask)
        val aliasStrings = dao.currentGameAliases().associate { it.oldGameId to it.currentGameId }
        GameIdentityAliasCache.install(aliasStrings)
        val aliases = aliasStrings.mapKeys { GameId(it.key) }.mapValues { GameId(it.value) }

        suspend fun phase(bit: Long, action: suspend () -> Boolean): Boolean {
            val journalIds = rows.asSequence()
                .filter { it.storesCompletedMask and bit == 0L }
                .map(RemapJournalEntity::journalId)
                .toList()
            if (journalIds.isEmpty()) return true
            val success = runCatching { action() }.getOrDefault(false)
            if (!success) {
                dao.noteJournalFailure(journalIds.first(), "critical store bit $bit did not commit")
                return false
            }
            return dao.acknowledgeJournalStoreRows(journalIds, bit) == journalIds.size
        }

        if (!phase(REMAP_STORE_HOME_TOMES) { HomeTomeStore.remapGameIds(application, aliases) }) {
            return@withLock false
        }
        // The Room v9 bit retains its shipped numeric identity; the local alias names its honest
        // dispatch-to-return meaning without rewriting an existing replay journal.
        if (!phase(REMAP_STORE_EXTERNAL_SESSION_ELAPSED) {
                HomeExternalSessionElapsedStore.remapGameIds(application, aliases)
            }
        ) {
            return@withLock false
        }
        if (!phase(REMAP_STORE_HOME_SURFACE) {
                HomeSurfaceStateStore.remapGameIds(application, aliases)
            }
        ) {
            return@withLock false
        }
        if (!phase(REMAP_STORE_HOME_RETURN) {
                HomeLaunchReturnStore.remapGameIds(application, aliases)
            }
        ) {
            return@withLock false
        }
        if (!phase(REMAP_STORE_COMPANION_RETURN) {
                CompanionPendingOperationStore.remapGameIds(application, aliasStrings)
            }
        ) {
            return@withLock false
        }
        if (!phase(REMAP_STORE_TWEAKER_SELECTION) {
                PerGameTweakerSelectionStore.remapGameIds(application, aliases)
            }
        ) {
            return@withLock false
        }
        true
    }

    suspend fun replayAll(context: Context): Boolean = mutex.withLock {
        val application = context.applicationContext
        val dao = MimirDatabaseFactory.get(application).gameIdentityDao()
        GameIdentityAliasCache.install(
            dao.currentGameAliases().associate { it.oldGameId to it.currentGameId },
        )
        var rows = dao.pendingJournal()
        while (rows.isNotEmpty()) {
            for (row in rows) {
                val terminalTarget = dao.resolveCurrentGameId(row.toGameId)
                if (terminalTarget == row.fromGameId) {
                    dao.voidJournal(
                        row.journalId,
                        System.currentTimeMillis(),
                        "voided: transition resolved back to source",
                    )
                    continue
                }
                if (!replayRow(application, row, terminalTarget)) return@withLock false
                if (dao.consumeJournal(
                        journalId = row.journalId,
                        requiredMask = REMAP_ALL_STORES_MASK,
                        consumedAtMillis = System.currentTimeMillis(),
                    ) != 1
                ) {
                    dao.noteJournalFailure(row.journalId, "fully acknowledged row could not be consumed")
                    return@withLock false
                }
            }
            rows = dao.pendingJournal()
        }
        true
    }

    private suspend fun replayRow(
        context: Context,
        row: RemapJournalEntity,
        targetGameId: String,
    ): Boolean {
        val dao = MimirDatabaseFactory.get(context).gameIdentityDao()
        val from = GameId(row.fromGameId)
        val to = GameId(targetGameId)
        var mask = row.storesCompletedMask

        suspend fun phase(bit: Long, action: suspend () -> Boolean): Boolean {
            if (mask and bit != 0L) return true
            val success = runCatching { action() }.getOrElse { error ->
                dao.noteJournalFailure(
                    row.journalId,
                    "${error::class.java.simpleName}: ${error.message.orEmpty()}".take(240),
                )
                false
            }
            if (!success) {
                dao.noteJournalFailure(row.journalId, "store bit $bit did not commit")
                return false
            }
            if (dao.acknowledgeJournalStore(row.journalId, bit) != 1) return false
            mask = mask or bit
            return true
        }

        if (!phase(REMAP_STORE_HOME_TOMES) { HomeTomeStore.remapGameId(context, from, to) }) return false
        if (!phase(REMAP_STORE_EXTERNAL_SESSION_ELAPSED) {
                HomeExternalSessionElapsedStore.remapGameId(context, from, to)
            }
        ) {
            return false
        }
        if (!phase(REMAP_STORE_HOME_SURFACE) {
                HomeSurfaceStateStore.remapGameId(context, from, to)
            }
        ) {
            return false
        }
        if (!phase(REMAP_STORE_HOME_RETURN) { HomeLaunchReturnStore.remapGameId(context, from, to) }) {
            return false
        }
        if (!phase(REMAP_STORE_COMPANION_RETURN) {
                CompanionPendingOperationStore.remapGameId(context, from.value, to.value)
            }
        ) {
            return false
        }
        if (!phase(REMAP_STORE_TWEAKER_SELECTION) {
                PerGameTweakerSelectionStore.remapGameId(context, from, to)
            }
        ) {
            return false
        }
        val mediaReplayed = GameIdentityMediaMutationGate.run(context, blocked = false) {
            val targetRecords = EnrichMediaKind.entries.associateWith { kind ->
                effectiveGameIdentityMediaSelectionRecord(context, kind, to.value)
            }
            val sourceRecords = EnrichMediaKind.entries.associateWith { kind ->
                effectiveGameIdentityMediaSelectionRecord(context, kind, from.value)
            }

            fun sourceWins(kind: EnrichMediaKind): Boolean {
                val source = sourceRecords.getValue(kind)
                val target = targetRecords.getValue(kind)
                return gameIdentityMediaSourceWins(source, target)
            }

            fun transition(kind: EnrichMediaKind): GameIdentityMediaSelection? =
                sourceRecords.getValue(kind).selection.takeIf { sourceWins(kind) }

            if (!phase(REMAP_STORE_CUSTOM_HERO) {
                    CustomHeroStore.remapGameId(
                        context = context,
                        fromGameId = from,
                        toGameId = to,
                        heroTransition = transition(EnrichMediaKind.HERO),
                        clipTransition = transition(EnrichMediaKind.CLIP),
                    )
                }
            ) {
                return@run false
            }
            if (!phase(REMAP_STORE_ACTIVE_ART) {
                    GameIdentityMediaReplay.remapActiveAndRetained(
                        context = context,
                        fromGameId = from,
                        toGameId = to,
                        iconTransition = transition(EnrichMediaKind.ICON),
                        physicalTransition = transition(EnrichMediaKind.TOME_PHYSICAL),
                        // Added with the COVER kind's remap path (2026-08-25). Without it a
                        // re-identified game kept its cover gallery and lost its chosen cover.
                        coverTransition = transition(EnrichMediaKind.COVER),
                    )
                }
            ) {
                return@run false
            }
            if (!phase(REMAP_STORE_SAVED_ART) {
                    EnrichMediaKind.entries.all { kind ->
                        EnrichSavedStore.remapCacheKeyLocked(
                            context,
                            kind,
                            from.value,
                            to.value,
                        ) && if (sourceWins(kind)) {
                            GameIdentityMediaSelectionStore.write(
                                context,
                                kind,
                                to.value,
                                sourceRecords.getValue(kind),
                            )
                        } else {
                            val target = targetRecords.getValue(kind)
                            target.selection == GameIdentityMediaSelection.NEVER_CHOSEN ||
                                GameIdentityMediaSelectionStore.write(
                                    context,
                                    kind,
                                    to.value,
                                    target,
                                )
                        }
                    }
                }
            ) {
                return@run false
            }
            true
        }
        if (!mediaReplayed) return false
        if (!phase(REMAP_STORE_ART_RESOLUTION) {
                ArtResolutionStore.remapGameId(context, from.value, to.value)
            }
        ) {
            return false
        }
        if (!phase(REMAP_STORE_ART_NO_MATCH) {
                ArtNoMatchStore.remapGameId(context, from.value, to.value)
            }
        ) {
            return false
        }
        return true
    }
}

/**
 * Process integration: first replay is synchronous and precedes companion/Home state adoption; later
 * scan/enrichment journal writes are observed and retried off-main.
 */
internal object GameIdentityReplayRuntime {
    /** Journal replay is process work; contain a failed retry child without cancelling its siblings. */
    internal val scopeFailureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e("MimirIdentityReplay", "background replay work failed in $context", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + scopeFailureHandler)

    @Volatile
    private var started = false

    fun replayBeforeStateLoad(context: Context) {
        val startedAt = System.nanoTime()
        runBlocking(Dispatchers.IO) {
            if (!GameIdentityRemapReplayer.replayCriticalBeforeStateLoad(context)) {
                Log.e(
                    "MimirIdentityReplay",
                    "Critical identity replay remains pending; background retry will continue",
                )
            }
        }
        Log.i(
            "MimirIdentityReplay",
            "critical startup replay elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000L}",
        )
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val application = context.applicationContext
        scope.launch {
            MimirDatabaseFactory.get(application)
                .gameIdentityDao()
                .observePendingJournalCount()
                .distinctUntilChanged()
                .collect { pending ->
                    if (pending <= 0) return@collect
                    var attempt = 0L
                    while (currentCoroutineContext().isActive) {
                        if (GameIdentityRemapReplayer.replayAll(application)) return@collect
                        attempt++
                        delay((attempt * 500L).coerceAtMost(5_000L))
                    }
                }
        }
    }
}
