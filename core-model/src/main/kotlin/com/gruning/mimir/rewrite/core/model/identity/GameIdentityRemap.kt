package com.gruning.mimir.rewrite.core.model.identity

import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.GameId

/**
 * Why a durable game identity changed its current physical representative.
 *
 * These are persistence wire names. Additions are allowed; renaming an existing entry would make an
 * already-written replay journal unreadable.
 */
enum class GameIdentityRemapReason {
    BACKFILL,
    EXACT_HASH_FOLD,
    CATALOG_TITLE_ID_FOLD,
    MULTI_DISC_FOLD,
    CROSS_STORAGE_FOLD,
    LOGICAL_GROUP_MERGE,
    WINNER_REMOVED,
    WINNER_FLIPPED,
    IDENTITY_RESURRECTED,
}

/**
 * One already-planned physical group. [winnerGameId] remains the product-facing identity while the
 * opaque logical group records lineage across future winner changes.
 */
data class LogicalGameFoldPlan(
    val winnerGameId: GameId,
    val memberGameIds: List<GameId>,
    val groupKind: DuplicateGroupKind,
) {
    init {
        require(memberGameIds.isNotEmpty()) { "A logical fold needs at least one physical game id" }
        require(winnerGameId in memberGameIds) { "The fold winner must be one of its members" }
    }
}

/** Typed hand-off from the Room transaction to the cross-store replay layer. */
data class GameIdentityRemap(
    val logicalGameId: String,
    val fromGameId: GameId,
    val toGameId: GameId,
    val reason: GameIdentityRemapReason,
) {
    init {
        require(logicalGameId.isNotBlank()) { "Logical game id must not be blank" }
        require(fromGameId != toGameId) { "A remap must change the product-facing game id" }
    }
}
