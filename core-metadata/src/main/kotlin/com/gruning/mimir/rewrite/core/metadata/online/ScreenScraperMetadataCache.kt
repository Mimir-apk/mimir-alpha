package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.MetadataOrigin
import com.gruning.mimir.rewrite.core.metadata.MetadataRecord
import com.gruning.mimir.rewrite.core.storage.dao.GameIdentityDao
import com.gruning.mimir.rewrite.core.storage.dao.TitleMetadataCacheDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.TitleMetadataCacheEntity
import com.gruning.mimir.rewrite.core.storage.entity.freshOrNull

/**
 * Durable bridge from one already-verified provider response to the shared local metadata cache
 * (`title_metadata_cache`). This performs no network work and never persists provider media URLs.
 *
 * **One row per game, by design — and this is safe only because of a structural fact, not a general
 * multi-provider guarantee.** `TitleMetadataCacheEntity.catalogKey` is the table's `@PrimaryKey`
 * (`Entities.kt:323`), so a second provider's row would overwrite the first's outright, with no composite
 * key or namespacing. [MetadataOrigin.SCREENSCRAPER] never resolves for `pc_games` (no ScreenScraper
 * system id exists for that platform — `ScreenScraperMetadataSource.kt:67-69`,
 * `ArtworkFetchController.kt:297-299`), and [MetadataOrigin.STEAM] / [MetadataOrigin.STEAMGRIDDB] are only
 * ever looked up for `pc_games`. Steam and SteamGridDB DO target the same `pc_games` row — safe only
 * because exactly ONE lane writes both (`ArtworkFetchController.fetchAndPersistSteamGridDbGame`), in a
 * fixed precedence a later write cannot violate: a synopsis beats a marker, and a marker never overwrites
 * a synopsis (the no-downgrade rule below). [MetadataOrigin.IGDB] is the first origin
 * that DOES target every row every other accepted origin can also target — a ScreenScraper-mapped ROM row
 * AND a `pc_games` row — and it stays safe under the SAME structural fact for a different reason: IGDB is
 * only ever consulted, on the SAME press, AFTER the exact-key provider for that row already answered
 * "nothing" (`ArtworkFetchController.fetchAndPersistIgdbDescription`'s two call sites), so the two writes
 * this transaction can see for one row are always ordered — never concurrent — and the precedence rules below
 * enforce the precedence a later press must respect: an exact-key synopsis (`screenscraper:en` /
 * `steam:store`) always outranks IGDB's name-guess synopsis, and a fresh (unexpired) IGDB synopsis
 * outranks the SteamGridDB marker exactly like a `steam:store` synopsis already does. **The limit:** the
 * day a second NAME-GUESS provider is added for a row IGDB already covers, this two-tier precedence breaks
 * and needs a real design (composite `(catalogKey, source)` key, or a namespaced key) — do not extend the
 * accepted-origin `when` below without first re-deriving that every origin's write is still ordered by
 * that same precedence discipline.
 */
object VerifiedMetadataCache {

    /** Language provenance without a schema bump: legacy `screenscraper` rows are locale-unknown. */
    const val ENGLISH_SOURCE = "screenscraper:en"

    /** Steam storefront `short_description`, scoped to `pc_games` — see the class kdoc for the coexistence
     *  limit. Lowercase: the read model's `LOWER()` comparisons (`Daos.kt`) depend on it. */
    const val STEAM_STORE_SOURCE = "steam:store"

    /** SteamGridDB's own marker origin, scoped to `pc_games` — see the class kdoc for the
     *  coexistence precedence with [STEAM_STORE_SOURCE]. Lowercase, same note as [STEAM_STORE_SOURCE]. */
    const val STEAMGRIDDB_SOURCE = "steamgriddb"

    /** IGDB's last-resort name-search origin, a capped source: targets BOTH the
     *  ScreenScraper platforms and `pc_games`, consulted only after the exact-key provider for that row
     *  answered with nothing on the same press — see this class's kdoc for why one writer handling every origin per press in a fixed precedence is what keeps that safe. Lowercase, same note as the others. */
    const val IGDB_SOURCE = "igdb"

    /** Stable physical identity; provider/catalog identities may change when the current winner changes. */
    fun cacheKeyFor(game: GameEntity): String =
        "game:${game.gameId}"

    /**
     * Persist the verified provider state, including a null synopsis for [MetadataOrigin.SCREENSCRAPER] —
     * a `screenscraper:en` source plus null description means "provider verified this game and supplied no
     * synopsis"; no cache row means the game has never produced a verified provider record. Returns false
     * for any origin this cache does not accept (see the class kdoc for why only these are safe today).
     *
     * Callers for an origin whose "no data" outcome must NOT unstick an enrichment affordance (Steam's
     * `success:false` is an honest empty, never a provider failure) are responsible for not calling this
     * with a blank [MetadataRecord.description] in the first place — this function has no origin-specific
     * opinion on that; it persists whatever verified record it is given. Retired for `pc_games` by R6-P1-04
     * (the 2026-08-19 15:00 honest-third-state ruling superseded the rule first): a finished identification
     * with no synopsis settles as [MetadataOrigin.STEAMGRIDDB]'s marker instead of staying unstuck forever.
     */
    suspend fun persistVerifiedRecord(
        dao: TitleMetadataCacheDao,
        game: GameEntity,
        record: MetadataRecord,
        nowMillis: Long = System.currentTimeMillis(),
        gameIdentityDao: GameIdentityDao? = null,
        resolveCurrentGameId: suspend (String) -> String = { gameId ->
            gameIdentityDao?.resolveCurrentGameId(gameId) ?: gameId
        },
        runTransaction: suspend (suspend () -> Boolean) -> Boolean = { block -> block() },
    ): Boolean = runTransaction transaction@{
        val sourceLabel = when (record.origin) {
            MetadataOrigin.SCREENSCRAPER -> ENGLISH_SOURCE
            MetadataOrigin.STEAM -> STEAM_STORE_SOURCE
            MetadataOrigin.STEAMGRIDDB -> STEAMGRIDDB_SOURCE
            MetadataOrigin.IGDB -> IGDB_SOURCE
            else -> return@transaction false
        }
        val currentGameId = resolveCurrentGameId(game.gameId)
        val key = "game:$currentGameId"
        val current = dao.byCatalogKey(key)
        // v8 stored provider state under TitleCatalog's key. Singleton games never enter the v9 group
        // backfill, so preserve that row on their first current-key write instead of letting a
        // media-only response mask its verified English synopsis.
        val legacy = if (current == null) {
            game.titleCatalogKey
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != key }
                ?.let { dao.byCatalogKey(it) }
        } else {
            null
        }
        val existing = current ?: legacy
        // R6-P3-01 I3: a capped source with nothing to cap, or nothing to fill, carries nothing worth a
        // write -- refused before any exact-key/no-downgrade check runs (the label never carries a marker).
        if (record.origin == MetadataOrigin.IGDB &&
            (record.cacheExpiresAt == null || record.description.isNullOrBlank())
        ) {
            return@transaction false
        }
        // R6-P3-01 I1: IGDB is a NAME-GUESS synopsis -- weaker evidence than either exact-key label, so it
        // must never overwrite one that already settled this row with a real synopsis.
        if (record.origin == MetadataOrigin.IGDB) {
            val exactKeySettled = existing?.source?.equals(ENGLISH_SOURCE, ignoreCase = true) == true ||
                existing?.source?.equals(STEAM_STORE_SOURCE, ignoreCase = true) == true
            if (exactKeySettled && !existing?.description.isNullOrBlank()) return@transaction true
        }
        // The no-downgrade rule, scoped to the marker origin on purpose: once a game is settled with
        // a real synopsis under either accepted label, a later marker must never overwrite it, and
        // `steam:store` must never be relabelled `steamgriddb`. A legacy/offline row under a non-accepted
        // label (titlecatalog/nointro/redump/thegamesdb) does not count as settled here either -- the read
        // model's own allowlist would not surface it as verified.
        // a FRESH (unexpired) IGDB synopsis counts as settled too -- a marker must not
        // downgrade it back to "Enrich". An EXPIRED IGDB synopsis is the one label the marker MAY still
        // overwrite: [freshOrNull] returns null the moment `cacheExpiresAt` has passed, which is exactly
        // the boundary the read side (`Daos.kt`'s FRESH_DESCRIPTION_SQL) already enforces for display.
        if (record.origin == MetadataOrigin.STEAMGRIDDB) {
            val exactKeySettled = existing?.source?.equals(ENGLISH_SOURCE, ignoreCase = true) == true ||
                existing?.source?.equals(STEAM_STORE_SOURCE, ignoreCase = true) == true
            val freshIgdbSettled = existing?.source?.equals(IGDB_SOURCE, ignoreCase = true) == true &&
                !existing.description.isNullOrBlank() &&
                existing.freshOrNull(nowMillis) != null
            val settledLabel = exactKeySettled || freshIgdbSettled
            if (settledLabel && !existing?.description.isNullOrBlank()) return@transaction true
        }
        val description = record.description
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val updated = TitleMetadataCacheEntity(
            catalogKey = key,
            platformId = game.platformId,
            canonicalTitle = record.canonicalTitle.takeIf(String::isNotBlank)
                ?: existing?.canonicalTitle
                ?: game.canonicalTitle
                ?: game.title,
            region = record.region ?: existing?.region ?: game.region,
            version = existing?.version ?: game.version,
            cachedAtMillis = nowMillis,
            canonicalSerial = record.serial ?: existing?.canonicalSerial ?: game.serial,
            // A later media-only response may preserve only a synopsis already stamped by this strict
            // English policy. Legacy `screenscraper` rows are locale-unknown (real RP5 evidence included
            // Spanish copy) and must never be relabelled English merely because a media response arrived.
            // Scoped to ScreenScraper's own English-locale label on purpose: a Steam-attributed row is
            // never touched by this fallback (its caller never writes a blank-description Steam record —
            // see the "no verified marker without a real description" rule above, retired for `pc_games`
            // only by the marker origin; still true for ScreenScraper/Steam themselves).
            description = description ?: existing?.description?.takeIf {
                existing.source.equals(ENGLISH_SOURCE, ignoreCase = true)
            },
            // Remote media URLs belong only to the download input. Preserve an existing LOCAL cache URI.
            boxArtUri = existing?.boxArtUri,
            screenshotUris = existing?.screenshotUris,
            source = sourceLabel,
            license = existing?.license,
            lastValidatedMillis = nowMillis,
            cacheExpiresAt = record.cacheExpiresAt,
        )
        dao.upsertAll(listOf(updated))
        true
    }
}

/**
 * The historical ScreenScraper-only entry point, kept so every existing ScreenScraper call site and its
 * tests are untouched by the Steam addition. Delegates entirely to [VerifiedMetadataCache], which is the
 * actual (now provider-neutral) implementation — see its kdoc for the one-row-per-game limit. A new
 * Steam-sourced call goes through [VerifiedMetadataCache] directly rather than through this object, so this
 * name keeps meaning exactly what it says: the ScreenScraper lane's own persist call.
 */
object ScreenScraperMetadataCache {

    /** @see VerifiedMetadataCache.ENGLISH_SOURCE */
    const val ENGLISH_SOURCE = VerifiedMetadataCache.ENGLISH_SOURCE

    /** @see VerifiedMetadataCache.cacheKeyFor */
    fun cacheKeyFor(game: GameEntity): String = VerifiedMetadataCache.cacheKeyFor(game)

    /** @see VerifiedMetadataCache.persistVerifiedRecord */
    suspend fun persistVerifiedRecord(
        dao: TitleMetadataCacheDao,
        game: GameEntity,
        record: MetadataRecord,
        nowMillis: Long = System.currentTimeMillis(),
        gameIdentityDao: GameIdentityDao? = null,
        resolveCurrentGameId: suspend (String) -> String = { gameId ->
            gameIdentityDao?.resolveCurrentGameId(gameId) ?: gameId
        },
        runTransaction: suspend (suspend () -> Boolean) -> Boolean = { block -> block() },
    ): Boolean = VerifiedMetadataCache.persistVerifiedRecord(
        dao = dao,
        game = game,
        record = record,
        nowMillis = nowMillis,
        gameIdentityDao = gameIdentityDao,
        resolveCurrentGameId = resolveCurrentGameId,
        runTransaction = runTransaction,
    )
}
