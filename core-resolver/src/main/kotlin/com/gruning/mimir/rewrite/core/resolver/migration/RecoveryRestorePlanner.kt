package com.gruning.mimir.rewrite.core.resolver.migration

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.ProfileId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.migration.MigrationGameRecord
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_RESTORE_RECORDS
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveRestoreSnapshot
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedShelfItem
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreExcludedCategory
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestorePlan
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreRecordCapException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreResult
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreShelfRankOverflowException
import com.gruning.mimir.rewrite.core.model.recovery.RestoreFavoriteOutcome
import com.gruning.mimir.rewrite.core.model.recovery.RestoreHiddenOutcome
import com.gruning.mimir.rewrite.core.model.recovery.RestoreOverrideOutcome
import com.gruning.mimir.rewrite.core.model.recovery.RestoreShelfOutcome
import com.gruning.mimir.rewrite.core.model.recovery.RestoreSkipReason
import com.gruning.mimir.rewrite.core.model.recovery.RestoreSkipped
import com.gruning.mimir.rewrite.core.model.recovery.RestoreStorePlan
import com.gruning.mimir.rewrite.core.model.recovery.RestoreWillWrite
import com.gruning.mimir.rewrite.core.model.recovery.RestoreWriteDecision
import com.gruning.mimir.rewrite.core.model.recovery.ShelfSkipReason

/** Read-only local state the planner needs to enforce additive local-wins restore semantics. */
interface RecoveryRestoreLocalState {
    suspend fun libraryHasGames(): Boolean
    suspend fun hasFavorite(gameId: GameId): Boolean
    suspend fun hasHidden(gameId: GameId): Boolean
    suspend fun hasEmulatorOverride(gameId: GameId): Boolean
    suspend fun hasShelfItem(profileId: ProfileId, itemId: HomeShelfItemId): Boolean
    suspend fun maxShelfRank(profileId: ProfileId): Long?
    suspend fun isPackageInstalled(packageName: String): Boolean
}

/**
 * Apply-time write seams for restore. Each insert returns whether the row was accepted.
 *
 * The app re-checks local-wins inside its Room transaction and returns `false` when a planned
 * row lost a race so [RecoveryRestorePlanner.apply] tallies only actual accepted inserts.
 */
interface RecoveryRestoreSeams {
    suspend fun currentMaxShelfRank(profileId: ProfileId): Long?
    suspend fun insertFavorite(gameId: GameId, favoritedAtMillis: Long): Boolean
    suspend fun insertHidden(gameId: GameId, hiddenAtMillis: Long): Boolean
    suspend fun insertEmulatorOverride(gameId: GameId, emulatorId: String, setAtMillis: Long): Boolean
    suspend fun insertShelfItem(
        profileId: ProfileId,
        itemId: HomeShelfItemId,
        rank: Long,
        addedAtMillis: Long,
    ): Boolean
}

/**
 * Computes additive, local-wins restore plans from a typed archive projection.
 *
 * Identity re-derivation uses the same [MigrationMatcher] mint point as migration import. Archived
 * aliases, groups, locators, and source paths never become authority — they only inform matching.
 *
 * Callers must pass a [RecoveryArchiveRestoreSnapshot] that already cleared structural and semantic
 * validation plus the restore-record ceiling enforced by [RecoveryArchiveRestoreReader].
 */
class RecoveryRestorePlanner(
    private val matcher: MigrationMatcher,
    private val shelfRankStep: Long = 1_024L,
) {
    init {
        require(shelfRankStep > 0L) { "shelfRankStep must be positive" }
    }

    suspend fun preview(
        snapshot: RecoveryArchiveRestoreSnapshot,
        local: RecoveryRestoreLocalState,
        isEmulatorRecognized: suspend (gameId: GameId, record: MigrationGameRecord, emulatorId: String) -> Boolean,
    ): RecoveryRestorePlan {
        enforceRecordCap(snapshot.restoreRecordCount())
        val pendingLibraryScan = !local.libraryHasGames()
        return RecoveryRestorePlan(
            favorites = planFavorites(snapshot, local, pendingLibraryScan),
            hidden = planHidden(snapshot, local, pendingLibraryScan),
            overrides = planOverrides(snapshot, local, isEmulatorRecognized, pendingLibraryScan),
            shelf = planShelf(snapshot, local, pendingLibraryScan),
            sourceRootHints = snapshot.sourceRootHints,
            notRestored = RecoveryRestoreExcludedCategory.requiredDisclosures,
            pendingLibraryScan = pendingLibraryScan,
            archiveRestoreRecordCount = snapshot.restoreRecordCount(),
        )
    }

    suspend fun apply(
        plan: RecoveryRestorePlan,
        seams: RecoveryRestoreSeams,
    ): RecoveryRestoreResult {
        enforceApplyCaps(plan)
        if (plan.pendingLibraryScan) {
            return plan.toPendingScanResult()
        }
        var favoritesRestored = 0
        plan.favorites.decisions.filterIsInstance<RestoreWillWrite<RestoreFavoriteOutcome>>()
            .forEach { write ->
                val outcome = write.outcome
                val gameId = outcome.matchedLocalGameId ?: return@forEach
                if (seams.insertFavorite(gameId, outcome.archived.favoritedAtMillis)) {
                    favoritesRestored++
                }
            }
        var hiddenRestored = 0
        plan.hidden.decisions.filterIsInstance<RestoreWillWrite<RestoreHiddenOutcome>>()
            .forEach { write ->
                val outcome = write.outcome
                val gameId = outcome.matchedLocalGameId ?: return@forEach
                if (seams.insertHidden(gameId, outcome.archived.hiddenAtMillis)) {
                    hiddenRestored++
                }
            }
        var overridesRestored = 0
        plan.overrides.decisions.filterIsInstance<RestoreWillWrite<RestoreOverrideOutcome>>()
            .forEach { write ->
                val outcome = write.outcome
                val gameId = outcome.matchedLocalGameId ?: return@forEach
                if (
                    seams.insertEmulatorOverride(
                        gameId = gameId,
                        emulatorId = outcome.emulatorId,
                        setAtMillis = outcome.archived.setAtMillis,
                    )
                ) {
                    overridesRestored++
                }
            }
        var shelfRestored = 0
        val shelfWrites = plan.shelf.decisions.filterIsInstance<RestoreWillWrite<RestoreShelfOutcome>>()
        if (shelfWrites.isNotEmpty()) {
            var nextRank = initialNextShelfRank(seams.currentMaxShelfRank(ProfileId.LOCAL))
            preflightShelfRankCapacity(nextRank, shelfWrites.size)
            shelfWrites.forEachIndexed { index, write ->
                val outcome = write.outcome
                val itemId = outcome.itemId ?: return@forEachIndexed
                val inserted =
                    seams.insertShelfItem(
                        profileId = ProfileId(outcome.archived.profileId),
                        itemId = itemId,
                        rank = nextRank,
                        addedAtMillis = outcome.archived.addedAtMillis,
                    )
                if (inserted) {
                    shelfRestored++
                    if (index < shelfWrites.lastIndex) {
                        nextRank = checkedNextShelfRank(nextRank)
                    }
                }
            }
        }

        return RecoveryRestoreResult(
            favoritesRestored = favoritesRestored,
            favoritesSkipped = plan.favorites.skipped - plan.favorites.unmatched +
                (plan.favorites.matched - favoritesRestored),
            favoritesUnmatched = plan.favorites.unmatched,
            hiddenRestored = hiddenRestored,
            hiddenSkipped = plan.hidden.skipped - plan.hidden.unmatched +
                (plan.hidden.matched - hiddenRestored),
            hiddenUnmatched = plan.hidden.unmatched,
            overridesRestored = overridesRestored,
            overridesSkipped = plan.overrides.skipped - plan.overrides.unmatched +
                (plan.overrides.matched - overridesRestored),
            overridesUnmatched = plan.overrides.unmatched,
            shelfRestored = shelfRestored,
            shelfSkipped = plan.shelf.skipped - plan.shelf.unmatched +
                (plan.shelf.matched - shelfRestored),
            shelfUnmatched = plan.shelf.unmatched,
            sourceRootHints = plan.sourceRootHints,
            notRestored = plan.notRestored,
            pendingLibraryScan = false,
        )
    }

    private suspend fun planFavorites(
        snapshot: RecoveryArchiveRestoreSnapshot,
        local: RecoveryRestoreLocalState,
        pendingLibraryScan: Boolean,
    ): RestoreStorePlan<RestoreFavoriteOutcome> {
        val decisions = snapshot.favorites.map { favorite ->
            if (pendingLibraryScan) {
                return@map RestoreSkipped(
                    pendingFavoriteOutcome(snapshot, favorite),
                    RestoreSkipReason.PENDING_LIBRARY_SCAN,
                )
            }
            val outcome = resolveFavorite(snapshot, favorite)
            val gameId = outcome.matchedLocalGameId
            when {
                gameId == null -> RestoreSkipped(outcome, RestoreSkipReason.UNMATCHED_GAME)
                local.hasFavorite(gameId) -> RestoreSkipped(outcome, RestoreSkipReason.LOCAL_EXISTS)
                else -> RestoreWillWrite(outcome)
            }
        }
        return RestoreStorePlan(decisions)
    }

    private suspend fun planHidden(
        snapshot: RecoveryArchiveRestoreSnapshot,
        local: RecoveryRestoreLocalState,
        pendingLibraryScan: Boolean,
    ): RestoreStorePlan<RestoreHiddenOutcome> {
        val decisions = snapshot.hidden.map { hidden ->
            if (!hidden.hidden) {
                val outcome = RestoreHiddenOutcome(
                    archived = hidden,
                    normalizedArchivedGameId = snapshot.identityIndex.normalizeArchivedGameId(
                        hidden.archivedGameId,
                    ),
                    migrationRecord = null,
                )
                return@map RestoreSkipped(outcome, RestoreSkipReason.HIDDEN_FALSE)
            }
            if (pendingLibraryScan) {
                return@map RestoreSkipped(
                    pendingHiddenOutcome(snapshot, hidden),
                    RestoreSkipReason.PENDING_LIBRARY_SCAN,
                )
            }
            val outcome = resolveHidden(snapshot, hidden)
            val gameId = outcome.matchedLocalGameId
            when {
                gameId == null -> RestoreSkipped(outcome, RestoreSkipReason.UNMATCHED_GAME)
                local.hasHidden(gameId) -> RestoreSkipped(outcome, RestoreSkipReason.LOCAL_EXISTS)
                else -> RestoreWillWrite(outcome)
            }
        }
        return RestoreStorePlan(decisions)
    }

    private suspend fun planOverrides(
        snapshot: RecoveryArchiveRestoreSnapshot,
        local: RecoveryRestoreLocalState,
        isEmulatorRecognized: suspend (GameId, MigrationGameRecord, String) -> Boolean,
        pendingLibraryScan: Boolean,
    ): RestoreStorePlan<RestoreOverrideOutcome> {
        val decisions = snapshot.emulatorOverrides.map { override ->
            if (pendingLibraryScan) {
                return@map RestoreSkipped(
                    pendingOverrideOutcome(snapshot, override),
                    RestoreSkipReason.PENDING_LIBRARY_SCAN,
                )
            }
            val outcome = resolveOverride(snapshot, override)
            val gameId = outcome.matchedLocalGameId
            when {
                gameId == null -> RestoreSkipped(outcome, RestoreSkipReason.UNMATCHED_GAME)
                local.hasEmulatorOverride(gameId) ->
                    RestoreSkipped(outcome, RestoreSkipReason.LOCAL_EXISTS)
                outcome.emulatorId.isBlank() -> RestoreWillWrite(outcome)
                !isEmulatorRecognized(gameId, outcome.migrationRecord!!, outcome.emulatorId) ->
                    RestoreSkipped(outcome, RestoreSkipReason.UNKNOWN_EMULATOR)
                else -> RestoreWillWrite(outcome)
            }
        }
        return RestoreStorePlan(decisions)
    }

    private suspend fun planShelf(
        snapshot: RecoveryArchiveRestoreSnapshot,
        local: RecoveryRestoreLocalState,
        pendingLibraryScan: Boolean,
    ): RestoreStorePlan<RestoreShelfOutcome> {
        var nextRank = if (pendingLibraryScan) {
            0L
        } else {
            initialNextShelfRank(local.maxShelfRank(ProfileId.LOCAL))
        }
        var rankExhausted = false
        val localDecisions = snapshot.shelfItems
            .filter { it.profileId == ProfileId.LOCAL.value }
            .sortedBy(RecoveryArchivedShelfItem::rank)
            .map { item ->
                when (item.itemKind) {
                    "EMULATED_GAME" -> {
                        if (pendingLibraryScan) {
                            RestoreSkipped(
                                pendingShelfOutcome(item),
                                RestoreSkipReason.PENDING_LIBRARY_SCAN,
                            )
                        } else if (rankExhausted) {
                            rankOverflowShelfDecision(item)
                        } else {
                            planEmulatedShelfItem(snapshot, local, item, nextRank).also { decision ->
                                if (decision is RestoreWillWrite<*>) {
                                    nextRank = advanceShelfRankOrExhaust(nextRank) { rankExhausted = true }
                                }
                            }
                        }
                    }
                    "ANDROID_GAME" -> {
                        if (pendingLibraryScan) {
                            RestoreSkipped(
                                pendingShelfOutcome(item),
                                RestoreSkipReason.PENDING_LIBRARY_SCAN,
                            )
                        } else if (rankExhausted) {
                            rankOverflowShelfDecision(item)
                        } else {
                            planAndroidGameShelfItem(local, item, nextRank).also { decision ->
                                if (decision is RestoreWillWrite<*>) {
                                    nextRank = advanceShelfRankOrExhaust(nextRank) { rankExhausted = true }
                                }
                            }
                        }
                    }
                    "ANDROID_APP" -> unsupportedShelfDecision(item)
                    else -> unsupportedShelfDecision(item)
                }
            }
        val foreignProfileDecisions = snapshot.shelfItems
            .filter { it.profileId != ProfileId.LOCAL.value }
            .map { item ->
                val outcome = RestoreShelfOutcome(
                    archived = item,
                    itemId = null,
                    skipReason = ShelfSkipReason.FOREIGN_PROFILE,
                )
                RestoreSkipped(outcome, RestoreSkipReason.FOREIGN_PROFILE)
            }
        return RestoreStorePlan(localDecisions + foreignProfileDecisions)
    }

    private fun rankOverflowShelfDecision(
        item: RecoveryArchivedShelfItem,
    ): RestoreWriteDecision<RestoreShelfOutcome> {
        val outcome = RestoreShelfOutcome(
            archived = item,
            itemId = null,
            skipReason = ShelfSkipReason.RANK_OVERFLOW,
        )
        return RestoreSkipped(outcome, RestoreSkipReason.RANK_OVERFLOW)
    }

    private fun unsupportedShelfDecision(
        item: RecoveryArchivedShelfItem,
    ): RestoreWriteDecision<RestoreShelfOutcome> {
        val outcome = RestoreShelfOutcome(
            archived = item,
            itemId = null,
            skipReason = ShelfSkipReason.UNSUPPORTED_ITEM_KIND,
        )
        return RestoreSkipped(outcome, RestoreSkipReason.UNSUPPORTED_ITEM_KIND)
    }

    private suspend fun planEmulatedShelfItem(
        snapshot: RecoveryArchiveRestoreSnapshot,
        local: RecoveryRestoreLocalState,
        item: RecoveryArchivedShelfItem,
        rank: Long,
    ): RestoreWriteDecision<RestoreShelfOutcome> {
        val normalized = snapshot.identityIndex.normalizeArchivedGameId(item.itemId)
        val record = snapshot.gameEvidence[normalized]?.toMigrationRecord()
        val matched = record?.let { matcher.resolve(it) }
        val gameId = (matched as? MatchOutcome.Matched)?.gameId
        val typedItemId = gameId?.let(HomeShelfItemId::Emulated)
        val outcome = RestoreShelfOutcome(
            archived = item,
            itemId = typedItemId,
            plannedRank = if (typedItemId != null) rank else null,
            skipReason = if (typedItemId == null) ShelfSkipReason.UNMATCHED_EMULATED_GAME else null,
        )
        return when {
            typedItemId == null -> RestoreSkipped(outcome, RestoreSkipReason.UNMATCHED_GAME)
            local.hasShelfItem(ProfileId.LOCAL, typedItemId) ->
                RestoreSkipped(outcome, RestoreSkipReason.LOCAL_EXISTS)
            else -> RestoreWillWrite(outcome)
        }
    }

    private suspend fun planAndroidGameShelfItem(
        local: RecoveryRestoreLocalState,
        item: RecoveryArchivedShelfItem,
        rank: Long,
    ): RestoreWriteDecision<RestoreShelfOutcome> {
        val packageName = item.itemId
        val typedItemId = HomeShelfItemId.AndroidGame(AndroidPackageName(packageName))
        val installed = local.isPackageInstalled(packageName)
        val outcome = RestoreShelfOutcome(
            archived = item,
            itemId = typedItemId,
            plannedRank = if (installed) rank else null,
            skipReason = if (!installed) ShelfSkipReason.PACKAGE_NOT_INSTALLED else null,
        )
        return when {
            !installed -> RestoreSkipped(outcome, RestoreSkipReason.PACKAGE_NOT_INSTALLED)
            local.hasShelfItem(ProfileId.LOCAL, typedItemId) ->
                RestoreSkipped(outcome, RestoreSkipReason.LOCAL_EXISTS)
            else -> RestoreWillWrite(outcome)
        }
    }

    private fun pendingFavoriteOutcome(
        snapshot: RecoveryArchiveRestoreSnapshot,
        favorite: com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedFavorite,
    ): RestoreFavoriteOutcome = RestoreFavoriteOutcome(
        archived = favorite,
        normalizedArchivedGameId = snapshot.identityIndex.normalizeArchivedGameId(
            favorite.archivedGameId,
        ),
        migrationRecord = null,
    )

    private fun pendingHiddenOutcome(
        snapshot: RecoveryArchiveRestoreSnapshot,
        hidden: com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedHidden,
    ): RestoreHiddenOutcome = RestoreHiddenOutcome(
        archived = hidden,
        normalizedArchivedGameId = snapshot.identityIndex.normalizeArchivedGameId(
            hidden.archivedGameId,
        ),
        migrationRecord = null,
    )

    private fun pendingOverrideOutcome(
        snapshot: RecoveryArchiveRestoreSnapshot,
        override: com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedEmulatorOverride,
    ): RestoreOverrideOutcome = RestoreOverrideOutcome(
        archived = override,
        normalizedArchivedGameId = snapshot.identityIndex.normalizeArchivedGameId(
            override.archivedGameId,
        ),
        migrationRecord = null,
        emulatorId = override.emulatorId,
    )

    private fun pendingShelfOutcome(item: RecoveryArchivedShelfItem): RestoreShelfOutcome =
        RestoreShelfOutcome(archived = item, itemId = null)

    private suspend fun resolveFavorite(
        snapshot: RecoveryArchiveRestoreSnapshot,
        favorite: com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedFavorite,
    ): RestoreFavoriteOutcome {
        val normalized = snapshot.identityIndex.normalizeArchivedGameId(favorite.archivedGameId)
        val record = snapshot.gameEvidence[normalized]?.toMigrationRecord()
        val matched = record?.let { matcher.resolve(it) }
        return RestoreFavoriteOutcome(
            archived = favorite,
            normalizedArchivedGameId = normalized,
            migrationRecord = record,
            matchedLocalGameId = (matched as? MatchOutcome.Matched)?.gameId,
        )
    }

    private suspend fun resolveHidden(
        snapshot: RecoveryArchiveRestoreSnapshot,
        hidden: com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedHidden,
    ): RestoreHiddenOutcome {
        val normalized = snapshot.identityIndex.normalizeArchivedGameId(hidden.archivedGameId)
        val record = snapshot.gameEvidence[normalized]?.toMigrationRecord()
        val matched = record?.let { matcher.resolve(it) }
        return RestoreHiddenOutcome(
            archived = hidden,
            normalizedArchivedGameId = normalized,
            migrationRecord = record,
            matchedLocalGameId = (matched as? MatchOutcome.Matched)?.gameId,
        )
    }

    private suspend fun resolveOverride(
        snapshot: RecoveryArchiveRestoreSnapshot,
        override: com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedEmulatorOverride,
    ): RestoreOverrideOutcome {
        val normalized = snapshot.identityIndex.normalizeArchivedGameId(override.archivedGameId)
        val record = snapshot.gameEvidence[normalized]?.toMigrationRecord()
        val matched = record?.let { matcher.resolve(it) }
        return RestoreOverrideOutcome(
            archived = override,
            normalizedArchivedGameId = normalized,
            migrationRecord = record,
            emulatorId = override.emulatorId,
            matchedLocalGameId = (matched as? MatchOutcome.Matched)?.gameId,
        )
    }

    private fun initialNextShelfRank(maxRank: Long?): Long {
        if (maxRank == null) return shelfRankStep
        return try {
            Math.addExact(maxRank, shelfRankStep)
        } catch (_: ArithmeticException) {
            throw RecoveryRestoreShelfRankOverflowException(maxRank)
        }
    }

    private fun preflightShelfRankCapacity(startRank: Long, writeCount: Int) {
        var rank = startRank
        repeat((writeCount - 1).coerceAtLeast(0)) {
            rank = checkedNextShelfRank(rank)
        }
    }

    private fun advanceShelfRankOrExhaust(
        current: Long,
        onExhausted: () -> Unit,
    ): Long =
        try {
            Math.addExact(current, shelfRankStep)
        } catch (_: ArithmeticException) {
            onExhausted()
            current
        }

    private fun checkedNextShelfRank(current: Long): Long =
        try {
            Math.addExact(current, shelfRankStep)
        } catch (_: ArithmeticException) {
            throw RecoveryRestoreShelfRankOverflowException(current)
        }

    private fun enforceRecordCap(count: Int) {
        if (count > RECOVERY_MAX_RESTORE_RECORDS) {
            throw RecoveryRestoreRecordCapException(count)
        }
    }

    private fun enforceApplyCaps(plan: RecoveryRestorePlan) {
        enforceRecordCap(plan.archiveRestoreRecordCount)
        enforceRecordCap(plan.totalDecisionCount)
    }

    private fun RecoveryRestorePlan.toPendingScanResult(): RecoveryRestoreResult =
        RecoveryRestoreResult(
            favoritesRestored = 0,
            favoritesSkipped = favorites.skipped - favorites.unmatched,
            favoritesUnmatched = favorites.unmatched,
            favoritesPending = favorites.pending,
            hiddenRestored = 0,
            hiddenSkipped = hidden.skipped - hidden.unmatched,
            hiddenUnmatched = hidden.unmatched,
            hiddenPending = hidden.pending,
            overridesRestored = 0,
            overridesSkipped = overrides.skipped - overrides.unmatched,
            overridesUnmatched = overrides.unmatched,
            overridesPending = overrides.pending,
            shelfRestored = 0,
            shelfSkipped = shelf.skipped - shelf.unmatched,
            shelfUnmatched = shelf.unmatched,
            shelfPending = shelf.pending,
            sourceRootHints = sourceRootHints,
            notRestored = notRestored,
            pendingLibraryScan = true,
        )
}
