package com.gruning.mimir.rewrite.core.resolver.migration

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.migration.MigrationGameRecord
import com.gruning.mimir.rewrite.core.resolver.GameIdentityBuilder
import com.gruning.mimir.rewrite.core.resolver.ResolutionInput

/** The result of trying to re-attach a bundle record to a game already in the rewrite's library. */
sealed interface MatchOutcome {
    val record: MigrationGameRecord

    /** The record resolved to a [gameId] that EXISTS in the rewrite DB — its markers can be applied. */
    data class Matched(val gameId: GameId, override val record: MigrationGameRecord) : MatchOutcome

    /** No game in the rewrite library derives this record's id — report it, never fail. */
    data class Unmatched(override val record: MigrationGameRecord) : MatchOutcome
}

/**
 * Re-attaches a [MigrationGameRecord] from a migration bundle to a game the rewrite has ALREADY scanned.
 *
 * The join is deliberately NOT a fragile path/opaque-key compare. Instead it runs the record's identity
 * evidence back through the ONE [GameIdentityBuilder] — the same mint point the live scan used — so the
 * derived `gameId` is byte-identical to what the scan produced for the SAME physical ROM. Because both apps
 * scan the same files on the SD card, the filename (and thus the derived id) is identical, so the common
 * case is a guaranteed match. The builder layers the strongest evidence available (contentHash → serial →
 * curated catalog title → normalized filename), so a favorite can additionally survive a differently-named
 * copy when curated-catalog / serial / hash evidence collapses region/version variants — but that is a
 * bonus; the guarantee is same-file identity.
 *
 * It NEVER re-scans and NEVER writes — it only probes existence via [gameExists]. A record whose derived
 * id isn't in the library is [MatchOutcome.Unmatched] (honestly reported), not an error.
 *
 * @param identityBuilder the id mint point. Defaults to the offline-catalog builder; the app may inject the
 *   same composed builder its scan used to maximize the match rate.
 * @param gameExists probe: does the rewrite library already contain this `gameId`? (backed by
 *   `GameDao.membersOfGame(...).isNotEmpty()` at the app layer; a set in tests).
 */
class MigrationMatcher(
    private val identityBuilder: GameIdentityBuilder = GameIdentityBuilder(),
    private val resolveCurrentGameId: suspend (GameId) -> GameId = { it },
    private val gameExists: suspend (GameId) -> Boolean,
) {
    suspend fun resolve(record: MigrationGameRecord): MatchOutcome {
        val gameId = resolveCurrentGameId(deriveGameId(record))
        return if (gameExists(gameId)) MatchOutcome.Matched(gameId, record) else MatchOutcome.Unmatched(record)
    }

    /** The `gameId` the live scan would have minted for this record — the single derivation, all evidence. */
    fun deriveGameId(record: MigrationGameRecord): GameId {
        val extension = record.fileName.substringAfterLast('.', "").lowercase()
        return identityBuilder.build(
            PlatformId(record.platformId),
            ResolutionInput(
                fileName = record.fileName,
                extension = extension,
                sizeBytes = record.sizeBytes,
                serial = record.serial,
                contentHash = record.contentHash,
            ),
        ).gameId
    }
}
