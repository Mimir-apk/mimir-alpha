package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.storage.ProcessSourceRootMutations
import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameIdentityDao

/**
 * Folds on-disk REGION/REVISION duplicates into one tile using the authoritative DAT collapse key the
 * enricher persisted — the dedup policy of collapsing region/version to one tile with losers hidden, now driven
 * by real clone-parent data instead of filename guessing.
 *
 * CRITICAL GUARD (verified against real data): a DAT clone-parent can lump genuinely DIFFERENT products
 * together — e.g. No-Intro files "Metroid Prime Hunters - First Hunt" (the demo) as a clone of the full
 * "Metroid Prime Hunters", so both carry the same collapse key. Folding on the collapse key alone would
 * hide the demo behind the full game. So a group only folds if its members also share the same NORMALIZED
 * BASE TITLE (region/version parens stripped) — a demo and a full game differ there and stay separate.
 *
 * Non-destructive: the loser keeps its gameId (favorites / "Open with" overrides / launch history keyed by
 * gameId survive); it is only marked non-primary so the shelf shows one tile. Recoverable via Browse.
 * Fully offline; metadata only.
 */
class LibraryCollapseRegrouper(
    private val dao: GameDao,
    private val identityDao: GameIdentityDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Deterministic interleaving seam: production leaves this empty. */
    private val afterSnapshot: suspend () -> Unit = {},
    private val sourceIndexMutations: SourceRootMutationCoordinator =
        ProcessSourceRootMutations.coordinator,
) {

    /** Fold same-title collapse-key variants. Returns the number of games hidden into another's tile. */
    suspend fun regroup(): Int = sourceIndexMutations.commitIndexMutation {
        regroupUnderMutationGate()
    }

    private suspend fun regroupUnderMutationGate(): Int {
        var folded = 0
        val snapshot = dao.resolvedCollapsePrimaries()
        afterSnapshot()
        snapshot
            .groupBy { it.platformId to it.collapseKey }
            .values.filter { it.size > 1 }
            .forEach { sameKey ->
                // Guard: within a collapse key, only fold members that share the same base title.
                sameKey.groupBy { baseTitle(it.canonicalTitle ?: it.title) }
                    .values.filter { it.size > 1 }
                    .forEach { variants ->
                        val distinctVariants = orderedCatalogFoldVariants(variants)
                        if (distinctVariants.size <= 1) return@forEach
                        folded += identityDao.foldLiveCatalogGroup(
                            candidateGameIds = distinctVariants.map { it.gameId },
                            platformId = distinctVariants.first().platformId,
                            collapseKey = requireNotNull(distinctVariants.first().collapseKey),
                            baseTitle = baseTitle(
                                distinctVariants.first().canonicalTitle ?: distinctVariants.first().title,
                            ),
                            nowMillis = nowMillis(),
                        )
                    }
            }
        return folded
    }

    internal companion object {
        private val PAREN_OR_BRACKET = Regex("\\s*[(\\[][^)\\]]*[)\\]]")

        /** Strip region/version parens & brackets, collapse whitespace, lower-case — variants share this. */
        internal fun baseTitle(title: String): String =
            title.replace(PAREN_OR_BRACKET, "").replace(Regex("\\s+"), " ").trim().lowercase()
    }
}

/** Stable catalog-fold input independent of the opaque physical resident-key codec. */
internal fun orderedCatalogFoldVariants(
    variants: List<com.gruning.mimir.rewrite.core.storage.entity.GameEntity>,
) = variants.sortedWith(
    compareBy(
        { if (it.locationKind == "SAF_TREE_GRANT") 0 else 1 },
        { it.gameId },
        { it.sourceRootUri },
        { it.relativePath },
        { it.entryKey },
    ),
).distinctBy { it.gameId }
