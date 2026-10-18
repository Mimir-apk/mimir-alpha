package com.gruning.mimir.rewrite.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.gruning.mimir.rewrite.core.storage.entity.BiosProfileEntity
import com.gruning.mimir.rewrite.core.storage.entity.EmulatorOverrideEntity
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.HomeShelfItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.LaunchProofEntity
import com.gruning.mimir.rewrite.core.storage.entity.PlatformEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceReconcileRootEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceReconcileRunEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceRootEntity
import com.gruning.mimir.rewrite.core.storage.entity.TitleMetadataCacheEntity
import com.gruning.mimir.rewrite.core.storage.entity.freshOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/**
 * Room DAOs. Reads that back the UI are `Flow` (reactive shelf/gamelist); one-shots are `suspend`.
 * Queries encode the console visibility rules directly so the UI never re-filters:
 *  - shelf platforms = only `gamesCount > 0`
 *  - shelf/gamelist games = primary members of their dedup group, not hidden
 *  - Browse restore = only genuinely user-hidden (reason USER_DISMISSED) primary/ungrouped games
 */

internal const val LOGICAL_GAME_MEMBER_PREDICATE_SQL =
    "(gameId = :gameId OR duplicateGroupId = :gameId OR " +
        "gameId IN (SELECT a.oldGameId FROM game_id_aliases a WHERE a.logicalGameId = " +
        "(SELECT seed.logicalGameId FROM game_id_aliases seed WHERE seed.oldGameId = :gameId LIMIT 1)) OR " +
        "duplicateGroupId IN (SELECT a.oldGameId FROM game_id_aliases a WHERE a.logicalGameId = " +
        "(SELECT seed.logicalGameId FROM game_id_aliases seed WHERE seed.oldGameId = :gameId LIMIT 1)))"

/**
 * The ids a visible root answers to: its own, plus every alias sibling of its logical game. A member belongs
 * to the row when its own id or its duplicate group is one of them.
 */
private const val LOGICAL_GAME_IDS_OF_ROW_SQL =
    "(SELECT g.gameId UNION SELECT candidate.oldGameId FROM game_id_aliases seed " +
        "JOIN game_id_aliases candidate ON candidate.logicalGameId = seed.logicalGameId " +
        "WHERE seed.oldGameId = g.gameId)"

/**
 * Correlated form used by game-row projections (`g` = visible root, `m` = candidate member).
 *
 * Written as two IN-lists over indexed columns, not as `m.gameId = g.gameId OR m.duplicateGroupId = … OR
 * EXISTS(alias…)`: that OR defeated `index_games_gameId`/`index_games_duplicateGroupId`, so every row's member
 * count scanned all of `games`. On the RG's 5529-game library (measured 2026-09-27) one SNES list took
 * 30-60 s and held both Room connections while identity resolution kept invalidating it. Same membership,
 * measured ~100x faster on that database with identical results.
 */
internal const val LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL =
    "(m.gameId IN $LOGICAL_GAME_IDS_OF_ROW_SQL OR m.duplicateGroupId IN $LOGICAL_GAME_IDS_OF_ROW_SQL)"

/** Prefer current-game provider state; retain catalog-key rows as a legacy/offline fallback. */
internal const val SCREEN_SCRAPER_METADATA_MATCH_SQL =
    "(t.catalogKey = 'game:' || g.gameId OR " +
        "t.catalogKey = NULLIF(TRIM(g.titleCatalogKey), ''))"

/**
 * Does a `title_overrides` row (`ov`) belong to the game a projection row (`g`) currently displays? Not
 * just direct/collapse-fold match (as `hasOverride`'s simpler `emulator_overrides` join uses) — also the
 * alias chain, so a rename set before a later identity remap (e.g. a hash newly proven on a previously
 * filename-only game) still surfaces under the game's new current id. `game_id_aliases` rows are never
 * deleted on remap (only `retiredAtMillis` is stamped), so this holds even once `ov.gameId`'s own physical
 * row no longer exists under that id.
 */
internal const val TITLE_OVERRIDE_MATCH_SQL =
    "(ov.gameId = g.gameId OR ov.gameId IN " +
        "(SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId) OR EXISTS (" +
        "SELECT 1 FROM game_id_aliases seed " +
        "JOIN game_id_aliases candidate ON candidate.logicalGameId = seed.logicalGameId " +
        "WHERE seed.oldGameId = g.gameId AND candidate.oldGameId = ov.gameId))"

internal const val SCREEN_SCRAPER_METADATA_ORDER_SQL =
    // The enclosing WHERE already limits rows to this game's current key or legacy catalog key.
    // Avoid an outer-query reference here: SQLite does not expose correlated names in a scalar
    // subquery's ORDER BY expression.
    "CASE WHEN SUBSTR(t.catalogKey, 1, 5) = 'game:' THEN 0 ELSE 1 END"

/**
 * The one shared set of `title_metadata_cache.source` values the read model treats as a COMPLETE, verified
 * provider record — every projection that surfaces `description`/`descriptionSource`/`providerCanonicalTitle`
 * filters through this, plus the hydration selector that decides which games still need an automatic
 * pass. `screenscraper:en` is ScreenScraper's English-locale label (a bare `screenscraper` row is
 * locale-unknown and deliberately excluded — see `core-metadata`'s `VerifiedMetadataCache` legacy-preserve
 * note); `steam:store` is the Steam storefront `short_description` (`ArtworkFetchController`'s
 * `fetchOnePcArtwork`, `pc_games`-only). `steamgriddb` is that same route's own marker-only
 * origin — no synopsis, but a finished identification the seat must still show as settled ("Settings", not
 * "Enrich") rather than never. `igdb` is the last-resort name-search synopsis — a CAPPED source
 * (`FRESH_DESCRIPTION_SQL` below is what enforces its 24h expiry on this same read path). Extracted so a
 * fourth provider is a one-line addition here instead of another 19-site hand edit — this constant is
 * used in every SQL string below that used to hardcode `'screenscraper:en'` as a bare literal.
 */
internal const val ACCEPTED_VERIFIED_METADATA_SOURCES_SQL = "('screenscraper:en', 'steam:store', 'steamgriddb', 'igdb')"

/**
 * Read-side expiry filtering: the read-side
 * half of the 24h cache rule for a CAPPED source (today: only `igdb`). `t.description` alone would keep
 * serving an expired IGDB synopsis forever on every SQL projection below — [freshByCatalogKey] already
 * guards a direct row read, but these six correlated-subquery sites bypass it entirely (see that method's
 * kdoc). SQLite evaluates `strftime('%s','now')` at each query run, and Room re-runs a `Flow` on any
 * invalidation of the joined tables plus every re-subscription (opening a platform), so an expired
 * synopsis disappears at the next emission — an already-composed screen keeps stale copy only until then,
 * which is the accepted residual, not silently hidden. `descriptionSource`/`providerCanonicalTitle` are
 * deliberately NOT threaded through this: they are Mimir's own bookkeeping about the press (was a provider
 * consulted, what did it call the game), not the Program Materials the 24h clause covers, so an expired
 * row still reads `VERIFIED_PARTIAL` with a "No synopsis" pill rather than snapping back to bare "Enrich"
 * every day.
 */
internal const val FRESH_DESCRIPTION_SQL =
    "CASE WHEN t.cacheExpiresAt IS NULL OR t.cacheExpiresAt > CAST(strftime('%s','now') AS INTEGER) * 1000 " +
        "THEN t.description ELSE NULL END"

/**
 * The post-onboarding automatic hydration worker's starter slice: primary games with no
 * [ACCEPTED_VERIFIED_METADATA_SOURCES_SQL]-recognized row yet. See
 * `GameDao.primaryGamesWithoutScreenScraperMetadata`'s kdoc for why a `pc_games` row with a verified
 * `steam:store` synopsis, OR one settled with only a `steamgriddb` marker, must be excluded
 * here exactly like a ScreenScraper-verified row on any other platform — otherwise this worker re-selects
 * it forever, since ScreenScraper itself can never verify it.
 */
internal const val PRIMARY_GAMES_WITHOUT_VERIFIED_METADATA_SQL = """
        SELECT g.* FROM games g
        WHERE g.isPrimaryMember = 1
          AND NOT EXISTS(
              SELECT 1 FROM title_metadata_cache t
              WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
          )
        ORDER BY COALESCE(g.canonicalTitle, g.title) ASC, g.gameId ASC
        LIMIT :limit
"""

internal const val GAME_MEMBERS_READ_SQL =
    "SELECT entryKey, discNumber, displayName, sizeBytes FROM games " +
        "WHERE $LOGICAL_GAME_MEMBER_PREDICATE_SQL " +
        "ORDER BY CASE WHEN discNumber > 0 THEN 0 ELSE 1 END, discNumber ASC, displayName ASC, " +
        "sourceRootUri ASC, relativePath ASC, entryKey ASC"

/**
 * The one physical file behind a dedup-collapsed game — the four columns a content hash needs and nothing
 * else.
 *
 * A SEPARATE query rather than four more columns on [GAME_MEMBERS_READ_SQL] or on the gamelist projection,
 * deliberately. Those two run per shelf/gamelist emission over every visible row; this one runs at most once
 * per focus COMMIT, for a single game. Widening either of them would put a path/uri read on the scroll path
 * to serve a lookup that only ever asks about the card the user has actually stopped on.
 *
 * Returns the group's PRIMARY member (`isPrimaryMember DESC`), then disc 1 of a multi-disc set, then the
 * stable physical order `(sourceRootUri, relativePath, entryKey)` — so the same game always hashes the same
 * file across key-codec changes. Matches [GAME_MEMBERS_READ_SQL]'s group reach.
 */
internal const val GAME_HASH_SOURCE_SQL =
    "SELECT entryKey, sourceRootUri, relativePath, platformId, " +
        "sourceDocumentUri, managedPath, sizeBytes FROM games " +
        "WHERE $LOGICAL_GAME_MEMBER_PREDICATE_SQL " +
        "ORDER BY CASE WHEN availability = 'AVAILABLE' THEN 0 ELSE 1 END, isPrimaryMember DESC, " +
        "CASE WHEN discNumber > 0 THEN 0 ELSE 1 END, discNumber ASC, " +
        "sourceRootUri ASC, relativePath ASC, entryKey ASC LIMIT 1"

/**
 * The managed path of ONE representative resident file for a game — R-23
 * : reading a PC-games shortcut's own small body for its
 * embedded Steam app id. A SEPARATE single-row query for the exact reason [GAME_HASH_SOURCE_SQL] is
 * (see its doc): this runs at most once, on an explicit opt-in Customize Media action for one game,
 * never on the shelf/gamelist scroll path [GAME_MEMBERS_READ_SQL] serves. Same tie-break ordering as
 * [GAME_HASH_SOURCE_SQL] so both agree on which physical member represents the group.
 */
internal const val GAME_MANAGED_PATH_SOURCE_SQL =
    "SELECT managedPath FROM games " +
        "WHERE $LOGICAL_GAME_MEMBER_PREDICATE_SQL " +
        "ORDER BY CASE WHEN availability = 'AVAILABLE' THEN 0 ELSE 1 END, isPrimaryMember DESC, " +
        "CASE WHEN discNumber > 0 THEN 0 ELSE 1 END, discNumber ASC, " +
        "sourceRootUri ASC, relativePath ASC, entryKey ASC LIMIT 1"

internal const val AVAILABLE_ARCADE_RESIDENTS_FOR_EXACT_SOURCE_SQL =
    "SELECT * FROM games WHERE sourceRootUri = :rootUri AND availability = 'AVAILABLE' " +
        "AND platformId = 'arcade' " +
        "ORDER BY relativePath ASC, entryKey ASC LIMIT :limit"

internal const val HOME_SHELF_ITEMS_FOR_PROFILE_SQL = """
        SELECT * FROM home_shelf_items
        WHERE profileId = :profileId
        ORDER BY rank ASC, addedAtMillis ASC, itemKind ASC, itemId ASC
"""

internal const val HOME_SHELF_MAX_RANK_SQL =
    "SELECT MAX(rank) FROM home_shelf_items WHERE profileId = :profileId"

const val HOME_SHELF_RANK_STEP = 1_024L
internal const val HOME_SHELF_EMULATED_GAME_KIND = "EMULATED_GAME"
internal const val HOME_SHELF_ANDROID_GAME_KIND = "ANDROID_GAME"

internal const val PROMOTE_FAVORITE_GROUP_IF_ABSENT_SQL = """
        INSERT OR IGNORE INTO favorites (gameId, favoritedAtMillis)
        SELECT g.gameId, :favoritedAtMillis
        FROM games g
        WHERE g.isPrimaryMember = 1
          AND (
            g.gameId = :gameId OR EXISTS (
                SELECT 1 FROM games requested
                WHERE requested.gameId = :gameId
                  AND requested.duplicateGroupId = g.gameId
            )
          )
          AND NOT EXISTS (
            SELECT 1 FROM favorites f
            WHERE f.gameId = g.gameId OR f.gameId IN (
                SELECT member.gameId FROM games member WHERE member.duplicateGroupId = g.gameId
            )
          )
        ORDER BY g.gameId ASC
        LIMIT 1
"""

internal const val FAVORITE_GAME_ORDER_BY_SQL = """
        ORDER BY (SELECT MAX(f.favoritedAtMillis) FROM favorites f
            WHERE f.gameId = g.gameId OR f.gameId IN
                (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) DESC,
            g.gameId ASC
"""

internal const val EXACT_HASH_CANDIDATES_SQL = """
        SELECT g.* FROM games g
        WHERE g.isPrimaryMember = 1
          AND g.contentKind NOT IN ('MULTI_DISC_PLAYLIST', 'TRACK_MANIFEST')
          AND length(g.contentHash) = 49
          AND g.contentHash GLOB 'raw-sha1:*'
          AND substr(g.contentHash, 10) NOT GLOB '*[^0-9a-f]*'
          AND NOT EXISTS (
            SELECT 1 FROM games m
            WHERE m.entryKey != g.entryKey
              AND (m.gameId = g.gameId OR m.duplicateGroupId = g.gameId)
          )
          AND EXISTS (
            SELECT 1 FROM games d
            WHERE d.isPrimaryMember = 1
              AND d.contentKind NOT IN ('MULTI_DISC_PLAYLIST', 'TRACK_MANIFEST')
              AND d.platformId = g.platformId
              AND d.contentHash = g.contentHash
              AND d.gameId != g.gameId
              AND NOT EXISTS (
                SELECT 1 FROM games dm
                WHERE dm.entryKey != d.entryKey
                  AND (dm.gameId = d.gameId OR dm.duplicateGroupId = d.gameId)
              )
          )
        ORDER BY g.platformId, g.contentHash, g.gameId,
            g.sourceRootUri, g.relativePath, g.entryKey
"""

internal const val TERMINAL_GROUP_SEEDS_FOR_EXACT_SOURCE_SQL = """
        SELECT g.gameId, g.duplicateGroupId FROM games g
        WHERE g.sourceRootUri = :rootUri
          AND CASE
            WHEN g.duplicateGroupKind IN ('EXACT_HASH', 'CATALOG_TITLE_ID') THEN 1
            WHEN g.duplicateGroupId IS NULL THEN 0
            WHEN EXISTS (
              SELECT 1 FROM games t
              WHERE t.duplicateGroupKind IN ('EXACT_HASH', 'CATALOG_TITLE_ID')
                AND (t.gameId = g.gameId OR t.duplicateGroupId = g.gameId)
            ) THEN 1
            WHEN EXISTS (
              SELECT 1 FROM games t
              WHERE t.duplicateGroupKind IN ('EXACT_HASH', 'CATALOG_TITLE_ID')
                AND (
                  t.gameId = g.duplicateGroupId
                  OR t.duplicateGroupId = g.duplicateGroupId
                )
            ) THEN 1
            ELSE 0
          END = 1
"""

internal const val TERMINAL_GROUP_LINKS_FOR_IDS_SQL =
    "SELECT gameId, duplicateGroupId FROM games " +
        "WHERE duplicateGroupKind IN ('EXACT_HASH', 'CATALOG_TITLE_ID') " +
        "AND (gameId IN (:groupIds) OR duplicateGroupId IN (:groupIds))"

internal const val DISSOLVE_EXACT_GROUPS_SQL =
    "UPDATE games SET isPrimaryMember = 1, duplicateGroupId = NULL, duplicateGroupKind = NULL " +
        "WHERE duplicateGroupKind IN ('EXACT_HASH', 'CATALOG_TITLE_ID') " +
        "AND (gameId IN (:groupIds) OR duplicateGroupId IN (:groupIds))"

internal const val EXACT_LOGICAL_GAME_IDS_SQL =
    "SELECT DISTINCT gameId FROM games " +
        "WHERE duplicateGroupKind IN ('EXACT_HASH', 'CATALOG_TITLE_ID') " +
        "AND (gameId IN (:groupIds) OR duplicateGroupId IN (:groupIds))"

internal const val RESTORE_SUBORDINATE_GROUP_KINDS_SQL =
    "UPDATE games SET duplicateGroupId = gameId, duplicateGroupKind = (" +
        "SELECT m.duplicateGroupKind FROM games m " +
        "WHERE m.duplicateGroupId = games.gameId " +
        "AND m.duplicateGroupKind IS NOT NULL " +
        "AND m.duplicateGroupKind NOT IN ('EXACT_HASH', 'CATALOG_TITLE_ID') " +
        "ORDER BY m.sourceRootUri, m.relativePath, m.entryKey LIMIT 1) " +
        "WHERE isPrimaryMember = 1 AND gameId IN (:logicalGameIds) AND EXISTS (" +
        "SELECT 1 FROM games m WHERE m.duplicateGroupId = games.gameId " +
        "AND m.duplicateGroupKind IS NOT NULL " +
        "AND m.duplicateGroupKind NOT IN ('EXACT_HASH', 'CATALOG_TITLE_ID'))"

/**
 * Android API 30's portable SQLite floor is 999 bound variables. The group dissolve/select queries
 * expand the same Room list twice (`gameId IN (…) OR duplicateGroupId IN (…)`), so 500 ids become
 * 1,000 variables and fail on a conforming API-30 build. Keep deliberate air below that floor rather
 * than relying on the much higher limit present on newer AOSP/OEM SQLite builds.
 */
internal const val DOUBLE_IN_QUERY_CHUNK_SIZE = 400

/** Single-list queries stay below the same portable 999-variable floor. */
internal const val SINGLE_IN_QUERY_CHUNK_SIZE = 900

/** Exact v11 source ownership; the source/entry index serves both the equality and stable ordering. */
internal const val ENTRY_KEYS_FOR_EXACT_SOURCE_SQL = """
        SELECT entryKey FROM games
        WHERE sourceRootUri = :rootUri
        ORDER BY relativePath ASC, entryKey ASC
"""

data class TerminalGroupLink(
    val gameId: String,
    val duplicateGroupId: String?,
)

internal const val RESOLVED_COLLAPSE_PRIMARIES_SQL =
    "SELECT * FROM games WHERE isPrimaryMember = 1 AND collapseKey IS NOT NULL " +
        "AND (duplicateGroupKind IS NULL OR duplicateGroupKind != 'EXACT_HASH')"

internal data class ExactHashPlan(
    val winnerGameId: String,
    val loserGameIds: List<String>,
)

private val EXACT_HASH_INELIGIBLE_CONTENT_KINDS = setOf("MULTI_DISC_PLAYLIST", "TRACK_MANIFEST")

/** Pure deterministic planner behind the DAO transaction; SQL guards are repeated defensively here. */
internal fun planExactHashReconciliation(candidates: List<GameEntity>): List<ExactHashPlan> = candidates
    .asSequence()
    .filter {
        it.isPrimaryMember && it.contentKind !in EXACT_HASH_INELIGIBLE_CONTENT_KINDS &&
            it.contentHash?.matches(Regex("raw-sha1:[0-9a-f]{40}")) == true
    }
    .groupBy { it.platformId to requireNotNull(it.contentHash) }
    .toSortedMap(compareBy<Pair<String, String>>({ it.first }, { it.second }))
    .values
    .mapNotNull { sameHash ->
        val logicalGames = sameHash.groupBy { it.gameId }.values.map { sameGame ->
            sameGame.minWith(
                compareBy<GameEntity>(
                    { if (it.locationKind == "SAF_TREE_GRANT") 0 else 1 },
                    { it.gameId },
                    { it.sourceRootUri },
                    { it.relativePath },
                    { it.entryKey },
                ),
            )
        }
        if (logicalGames.size <= 1) return@mapNotNull null
        val ordered = logicalGames.sortedWith(
            compareBy<GameEntity>(
                { if (it.locationKind == "SAF_TREE_GRANT") 0 else 1 },
                { it.gameId },
                { it.sourceRootUri },
                { it.relativePath },
                { it.entryKey },
            ),
        )
        ExactHashPlan(ordered.first().gameId, ordered.drop(1).map { it.gameId })
    }

/** Result of the DAO's atomic insert-if-absent transaction. */
data class HomeShelfInsertResult(
    val item: HomeShelfItemEntity,
    val inserted: Boolean,
)

/** One Android package's latest successful dispatch, used by Home's mixed Last Played merge. */
data class AndroidGameLaunchRecencyProjection(
    val androidPackageName: String,
    val lastPlayedMillis: Long,
)

/** Returns a sparse rank strictly between its neighbors, or null when the range needs rebalancing. */
internal fun homeShelfRankBetween(previous: Long?, next: Long?): Long? {
    if (previous == null) {
        return next
            ?.takeIf { it >= Long.MIN_VALUE + HOME_SHELF_RANK_STEP }
            ?.minus(HOME_SHELF_RANK_STEP)
            ?: HOME_SHELF_RANK_STEP.takeIf { next == null }
    }
    if (next == null) {
        return previous
            .takeIf { it <= Long.MAX_VALUE - HOME_SHELF_RANK_STEP }
            ?.plus(HOME_SHELF_RANK_STEP)
    }
    if (previous >= next || previous + 1L == next) return null
    return ((previous and next) + ((previous xor next) shr 1))
        .takeIf { it > previous && it < next }
}

/**
 * Every visible group across ALL platforms in one light read, for onboarding's favourites picker.
 *
 * RG Rotate measurement (2026-09-27, 5529 games): the picker came up empty. It combined
 * [VISIBLE_GAME_ROWS_FOR_PLATFORM_SQL] across every platform, and that query's per-row correlated alias,
 * launch-proof and metadata subqueries ran 30 s and more while identity resolution rewrote `games`, so the
 * combine never emitted. The picker needs identity, title, platform and favourite state only. Member count,
 * launch history and provider metadata are constants here, and the hidden check walks the (small) hidden
 * table rather than every member of every row.
 *
 * Favourite state is NOT read here: `isFavorite` is a constant 0 and `favorites` is not in the query at all
 * (slow favorite feedback observed on RG Rotate, 2026-09-28). Room
 * re-runs a query whenever a table it names changes, so while this read named `favorites` every star re-ran
 * all 5,500 rows inside a transaction, in the same serial queue as the star's own write. The star comes from
 * [FAVORITE_GROUP_IDS_SQL] alone.
 */
internal const val FAVORITE_CANDIDATE_GAME_ROWS_SQL = """
        SELECT g.gameId AS gameId,
               COALESCE(g.canonicalTitle, g.title) AS title,
               g.platformId AS platformId,
               g.duplicateGroupKind AS duplicateGroupKind,
               1 AS memberCount,
               0 AS discCount,
               0 AS isFavorite,
               0 AS hasOverride,
               NULL AS lastPlayedMillis,
               NULL AS firstPlayedMillis,
               0 AS playCount,
               g.region AS region,
               g.version AS version,
               g.sizeBytes AS sizeBytes,
               g.sourceDocumentUri AS sourceDocumentUri,
               g.managedPath AS managedPath,
               NULL AS description,
               NULL AS descriptionSource,
               NULL AS providerCanonicalTitle,
               (SELECT ov.title FROM title_overrides ov WHERE ov.gameId = g.gameId
                    ORDER BY ov.setAtMillis DESC LIMIT 1) AS manualTitleOverride
        FROM games g
        WHERE g.isPrimaryMember = 1
          AND NOT EXISTS(
              SELECT 1 FROM hidden_items h JOIN games m ON m.gameId = h.gameId
              WHERE h.hidden = 1 AND (m.gameId = g.gameId OR m.duplicateGroupId = g.gameId)
          )
        GROUP BY g.gameId
        ORDER BY g.platformId ASC, COALESCE(g.canonicalTitle, g.title) ASC, g.gameId ASC
    """

/**
 * The ids of every visible group the player has favourited: the favourites picker's ONLY source of favourite
 * state, so its star repaints at once.
 *
 * A row is a favourite when a favourited game IS that row, or sits in that row's duplicate group -- the same
 * group-aware rule [VISIBLE_GAME_ROWS_FOR_PLATFORM_SQL] applies. The picker's big read does not name
 * `favorites` at all (see [FAVORITE_CANDIDATE_GAME_ROWS_SQL]), so a star re-runs only this handful of rows.
 */
internal const val FAVORITE_GROUP_IDS_SQL = """
        SELECT m.gameId FROM favorites f JOIN games m ON m.gameId = f.gameId
        UNION
        SELECT m.duplicateGroupId FROM favorites f JOIN games m ON m.gameId = f.gameId
        WHERE m.duplicateGroupId IS NOT NULL
    """

/**
 * The first successful launch of group `g`, as a scalar subquery for the read-model projections below.
 *
 * It reads exactly the rows the `lastPlayedMillis` and `playCount` subqueries beside it read: the group's own
 * `launch_proofs` plus those of its duplicate-group members, and only `intent_sent`. A failed dispatch is not a
 * play, so it is neither counted nor allowed to become the "first played". NULL when the group was never launched
 * (MIN over no rows), never 0. Index-backed by `index_launch_proofs_gameId` exactly like the MAX beside it.
 *
 * The projections that carry a launch history are the ones that carry this; [FAVORITE_CANDIDATE_GAME_ROWS_SQL] keeps
 * no launch history at all (a constant NULL here, as for `lastPlayedMillis`) so onboarding's picker stays light.
 */
internal const val FIRST_PLAYED_MILLIS_SQL = """(SELECT MIN(lp.timestampMillis) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent')"""

internal const val VISIBLE_GAME_ROWS_FOR_PLATFORM_SQL = """
        SELECT g.gameId AS gameId,
               COALESCE(g.canonicalTitle, g.title) AS title,
               g.platformId AS platformId,
               g.duplicateGroupKind AS duplicateGroupKind,
               (SELECT COUNT(*) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL) AS memberCount,
               (SELECT COUNT(DISTINCT m.discNumber) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL
                      AND m.discNumber > 0) AS discCount,
               (CASE WHEN EXISTS(SELECT 1 FROM favorites f
                    WHERE f.gameId = g.gameId OR f.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) THEN 1 ELSE 0 END) AS isFavorite,
               (CASE WHEN EXISTS(SELECT 1 FROM emulator_overrides o
                    WHERE o.gameId = g.gameId OR o.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) THEN 1 ELSE 0 END) AS hasOverride,
               (SELECT MAX(lp.timestampMillis) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS lastPlayedMillis,
               (SELECT COUNT(*) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS playCount,
               $FIRST_PLAYED_MILLIS_SQL AS firstPlayedMillis,
               g.region AS region,
               g.version AS version,
               g.sizeBytes AS sizeBytes,
               g.sourceDocumentUri AS sourceDocumentUri,
               g.managedPath AS managedPath,
               (SELECT t.canonicalTitle FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS providerCanonicalTitle,
               (SELECT $FRESH_DESCRIPTION_SQL FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS description,
               (SELECT t.source FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS descriptionSource,
               (SELECT ov.title FROM title_overrides ov
                    WHERE $TITLE_OVERRIDE_MATCH_SQL
                    ORDER BY ov.setAtMillis DESC
                    LIMIT 1) AS manualTitleOverride
        FROM games g
        WHERE g.platformId = :platformId
          AND g.isPrimaryMember = 1
          AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.hidden = 1
                AND (h.gameId = g.gameId OR h.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)))
        GROUP BY g.gameId
        ORDER BY lastPlayedMillis IS NULL,
                 lastPlayedMillis DESC,
                 COALESCE(manualTitleOverride, g.canonicalTitle, g.title) ASC,
                 g.gameId ASC
"""

internal const val GAME_ROW_BY_ID_SQL = """
        SELECT g.gameId AS gameId,
               COALESCE(g.canonicalTitle, g.title) AS title,
               g.platformId AS platformId,
               g.duplicateGroupKind AS duplicateGroupKind,
               (SELECT COUNT(*) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL) AS memberCount,
               (SELECT COUNT(DISTINCT m.discNumber) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL
                      AND m.discNumber > 0) AS discCount,
               (CASE WHEN EXISTS(SELECT 1 FROM favorites f
                    WHERE f.gameId = g.gameId OR f.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) THEN 1 ELSE 0 END) AS isFavorite,
               (CASE WHEN EXISTS(SELECT 1 FROM emulator_overrides o
                    WHERE o.gameId = g.gameId OR o.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) THEN 1 ELSE 0 END) AS hasOverride,
               (SELECT MAX(lp.timestampMillis) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS lastPlayedMillis,
               (SELECT COUNT(*) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS playCount,
               $FIRST_PLAYED_MILLIS_SQL AS firstPlayedMillis,
               g.region AS region,
               g.version AS version,
               g.sizeBytes AS sizeBytes,
               g.sourceDocumentUri AS sourceDocumentUri,
               g.managedPath AS managedPath,
               (SELECT t.canonicalTitle FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS providerCanonicalTitle,
               (SELECT $FRESH_DESCRIPTION_SQL FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS description,
               (SELECT t.source FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS descriptionSource,
               (SELECT ov.title FROM title_overrides ov
                    WHERE $TITLE_OVERRIDE_MATCH_SQL
                    ORDER BY ov.setAtMillis DESC
                    LIMIT 1) AS manualTitleOverride
        FROM games g
        WHERE g.gameId = :gameId
          AND g.isPrimaryMember = 1
        GROUP BY g.gameId
        LIMIT 1
"""

internal const val VISIBLE_AVAILABLE_GAME_ROWS_BY_IDS_SQL = """
        SELECT g.gameId AS gameId,
               COALESCE(g.canonicalTitle, g.title) AS title,
               g.platformId AS platformId,
               g.duplicateGroupKind AS duplicateGroupKind,
               (SELECT COUNT(*) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL) AS memberCount,
               (SELECT COUNT(DISTINCT m.discNumber) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL
                      AND m.discNumber > 0) AS discCount,
               (CASE WHEN EXISTS(SELECT 1 FROM favorites f
                    WHERE f.gameId = g.gameId OR f.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) THEN 1 ELSE 0 END) AS isFavorite,
               (CASE WHEN EXISTS(SELECT 1 FROM emulator_overrides o
                    WHERE o.gameId = g.gameId OR o.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) THEN 1 ELSE 0 END) AS hasOverride,
               (SELECT MAX(lp.timestampMillis) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS lastPlayedMillis,
               (SELECT COUNT(*) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS playCount,
               $FIRST_PLAYED_MILLIS_SQL AS firstPlayedMillis,
               g.region AS region,
               g.version AS version,
               g.sizeBytes AS sizeBytes,
               g.sourceDocumentUri AS sourceDocumentUri,
               g.managedPath AS managedPath,
               (SELECT t.canonicalTitle FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS providerCanonicalTitle,
               (SELECT $FRESH_DESCRIPTION_SQL FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS description,
               (SELECT t.source FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS descriptionSource,
               (SELECT ov.title FROM title_overrides ov
                    WHERE $TITLE_OVERRIDE_MATCH_SQL
                    ORDER BY ov.setAtMillis DESC
                    LIMIT 1) AS manualTitleOverride
        FROM games g
        WHERE g.gameId IN (:gameIds)
          AND g.isPrimaryMember = 1
          AND g.availability = 'AVAILABLE'
          AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.hidden = 1
                AND (h.gameId = g.gameId OR h.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)))
        GROUP BY g.gameId
        ORDER BY g.gameId ASC
"""

/**
 * the Home "Last Played" context — visible, available, dedup-collapsed
 * groups that have at least one launch proof, most recent first. Same rich projection as the platform
 * rows; the limit keeps the Home payload shelf-sized.
 */
internal const val RECENTLY_PLAYED_GAME_ROWS_SQL = """
        SELECT g.gameId AS gameId,
               COALESCE(g.canonicalTitle, g.title) AS title,
               g.platformId AS platformId,
               g.duplicateGroupKind AS duplicateGroupKind,
               (SELECT COUNT(*) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL) AS memberCount,
               (SELECT COUNT(DISTINCT m.discNumber) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL
                      AND m.discNumber > 0) AS discCount,
               (CASE WHEN EXISTS(SELECT 1 FROM favorites f
                    WHERE f.gameId = g.gameId OR f.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) THEN 1 ELSE 0 END) AS isFavorite,
               (CASE WHEN EXISTS(SELECT 1 FROM emulator_overrides o
                    WHERE o.gameId = g.gameId OR o.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) THEN 1 ELSE 0 END) AS hasOverride,
               (SELECT MAX(lp.timestampMillis) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS lastPlayedMillis,
               (SELECT COUNT(*) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS playCount,
               $FIRST_PLAYED_MILLIS_SQL AS firstPlayedMillis,
               g.region AS region,
               g.version AS version,
               g.sizeBytes AS sizeBytes,
               g.sourceDocumentUri AS sourceDocumentUri,
               g.managedPath AS managedPath,
               (SELECT t.canonicalTitle FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS providerCanonicalTitle,
               (SELECT $FRESH_DESCRIPTION_SQL FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS description,
               (SELECT t.source FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS descriptionSource,
               (SELECT ov.title FROM title_overrides ov
                    WHERE $TITLE_OVERRIDE_MATCH_SQL
                    ORDER BY ov.setAtMillis DESC
                    LIMIT 1) AS manualTitleOverride
        FROM games g
        WHERE g.isPrimaryMember = 1
          AND g.availability = 'AVAILABLE'
          AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.hidden = 1
                AND (h.gameId = g.gameId OR h.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)))
          AND EXISTS(SELECT 1 FROM launch_proofs lp
                WHERE (lp.gameId = g.gameId OR lp.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                  AND lp.outcome = 'intent_sent')
        GROUP BY g.gameId
        ORDER BY lastPlayedMillis DESC, g.gameId ASC
        LIMIT :limit
"""

internal const val RECENTLY_PLAYED_ANDROID_GAME_PROOFS_SQL = """
        SELECT androidPackageName AS androidPackageName,
               MAX(timestampMillis) AS lastPlayedMillis
        FROM launch_proofs
        WHERE androidPackageName IS NOT NULL
          AND outcome = 'intent_sent'
        GROUP BY androidPackageName
        ORDER BY lastPlayedMillis DESC, androidPackageName ASC
        LIMIT :limit
"""

internal const val HIDDEN_GAME_ROWS_SQL = """
        SELECT g.gameId AS gameId,
               COALESCE(g.canonicalTitle, g.title) AS title,
               g.platformId AS platformId,
               g.duplicateGroupKind AS duplicateGroupKind,
               (SELECT COUNT(*) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL) AS memberCount,
               (SELECT COUNT(DISTINCT m.discNumber) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL
                      AND m.discNumber > 0) AS discCount,
               (CASE WHEN EXISTS(SELECT 1 FROM favorites f
                    WHERE f.gameId = g.gameId OR f.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) THEN 1 ELSE 0 END) AS isFavorite,
               (CASE WHEN EXISTS(SELECT 1 FROM emulator_overrides o
                    WHERE o.gameId = g.gameId OR o.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) THEN 1 ELSE 0 END) AS hasOverride,
               (SELECT MAX(lp.timestampMillis) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS lastPlayedMillis,
               (SELECT COUNT(*) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS playCount,
               $FIRST_PLAYED_MILLIS_SQL AS firstPlayedMillis,
               g.region AS region,
               g.version AS version,
               g.sizeBytes AS sizeBytes,
               g.sourceDocumentUri AS sourceDocumentUri,
               g.managedPath AS managedPath,
               (SELECT t.canonicalTitle FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS providerCanonicalTitle,
               (SELECT $FRESH_DESCRIPTION_SQL FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS description,
               (SELECT t.source FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS descriptionSource,
               (SELECT ov.title FROM title_overrides ov
                    WHERE $TITLE_OVERRIDE_MATCH_SQL
                    ORDER BY ov.setAtMillis DESC
                    LIMIT 1) AS manualTitleOverride
        FROM games g
        JOIN hidden_items h ON h.hidden = 1
          AND (h.gameId = g.gameId OR h.gameId IN
              (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
        WHERE g.isPrimaryMember = 1
          AND h.hidden = 1
        GROUP BY g.gameId
        ORDER BY MAX(h.hiddenAtMillis) DESC, g.gameId ASC
"""

/**
 * Enrich-all's walk order: favourites, then most recently
 * played, then the rest alphabetically; hidden last regardless of the other predicates, so a long
 * interrupted run resumes into games the player is least likely to be looking at yet, and the games they
 * view get art first. The three predicates are the read model's own (favourites/last-played/hidden,
 * above), all index-backed (`games.duplicateGroupId`, `launch_proofs.gameId`). Deliberately a SEPARATE
 * query from [allPrimaryGames] rather than an added ORDER BY there: that query has scan-path callers
 * (`LibraryRepositoryImpl.kt`, `FileBrowserRomFolders.kt`, `IdentityEnrichmentCoordinator.kt`) this
 * ordering cost has no business landing on.
 */
internal const val PRIMARY_GAMES_FOR_ENRICHMENT_SQL = """
        SELECT g.* FROM games g
        WHERE g.isPrimaryMember = 1 AND g.availability = 'AVAILABLE'
        ORDER BY
          CASE WHEN EXISTS(SELECT 1 FROM hidden_items h WHERE h.hidden = 1
               AND (h.gameId = g.gameId OR h.gameId IN (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)))
               THEN 1 ELSE 0 END,
          CASE WHEN EXISTS(SELECT 1 FROM favorites f
               WHERE f.gameId = g.gameId OR f.gameId IN (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
               THEN 0 ELSE 1 END,
          (SELECT MAX(lp.timestampMillis) FROM launch_proofs lp
               WHERE (lp.gameId = g.gameId OR lp.gameId IN (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                 AND lp.outcome = 'intent_sent') IS NULL,
          (SELECT MAX(lp.timestampMillis) FROM launch_proofs lp
               WHERE (lp.gameId = g.gameId OR lp.gameId IN (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                 AND lp.outcome = 'intent_sent') DESC,
          COALESCE(g.canonicalTitle, g.title) ASC,
          g.gameId ASC
"""

@Dao
interface GameDao {

    @Upsert
    suspend fun upsertAll(games: List<GameEntity>)

    @Query("SELECT * FROM games WHERE entryKey = :entryKey")
    suspend fun byEntryKey(entryKey: String): GameEntity?

    /** Cheap existence probe for restore's pending-scan gate; deliberately not visibility-filtered. */
    @Query("SELECT EXISTS(SELECT 1 FROM games)")
    suspend fun anyGamesExist(): Boolean

    /** All files in one dedup group (multi-disc / cross-storage / region siblings), primary first. */
    @Query(
        "SELECT * FROM games WHERE $LOGICAL_GAME_MEMBER_PREDICATE_SQL " +
            "ORDER BY CASE WHEN availability = 'AVAILABLE' THEN 0 ELSE 1 END, " +
            "isPrimaryMember DESC, CASE WHEN discNumber > 0 THEN 0 ELSE 1 END, " +
            "discNumber ASC, displayName ASC, sourceRootUri ASC, relativePath ASC, entryKey ASC",
    )
    suspend fun membersOfGame(gameId: String): List<GameEntity>

    @Query(
        "SELECT platformId FROM games WHERE $LOGICAL_GAME_MEMBER_PREDICATE_SQL " +
            "ORDER BY sourceRootUri ASC, relativePath ASC, entryKey ASC LIMIT 1",
    )
    suspend fun platformIdForGame(gameId: String): String?

    /** Reactive detail-page members without exposing persistence entities above the storage boundary. */
    @Query(GAME_MEMBERS_READ_SQL)
    fun observeGameMembers(gameId: String): Flow<List<GameMemberProjection>>

    /** The single physical file a content hash should read for this game. See [GAME_HASH_SOURCE_SQL]. */
    @Query(GAME_HASH_SOURCE_SQL)
    suspend fun hashSourceForGame(gameId: String): GameHashSourceProjection?

    /** The single representative resident file's managed path. See [GAME_MANAGED_PATH_SOURCE_SQL]. */
    @Query(GAME_MANAGED_PATH_SOURCE_SQL)
    suspend fun managedPathForGame(gameId: String): String?

    /** Every surviving (primary) game, for the opt-in cover fetch to walk the owned library 1:1. */
    @Query("SELECT * FROM games WHERE isPrimaryMember = 1 AND availability = 'AVAILABLE'")
    suspend fun allPrimaryGames(): List<GameEntity>

    /**
     * R6-P2-03 lever C: the same rows as [allPrimaryGames], walked in Enrich-all's priority order
     * instead of scan order. See [PRIMARY_GAMES_FOR_ENRICHMENT_SQL] for the ordering rationale.
     */
    @Query(PRIMARY_GAMES_FOR_ENRICHMENT_SQL)
    suspend fun primaryGamesForEnrichment(): List<GameEntity>

    /**
     * One deterministic starter slice for post-onboarding, metadata-only hydration. A verified
     * ScreenScraper marker makes the row complete even when that title has no synopsis: onboarding must
     * never turn a provider miss into an unbounded retry drain. The explicit per-game Enrich action may
     * still force-refresh a legacy marker when the player asks for it.
     *
     * The function name is ScreenScraper-flavoured, but its selector (see
     * [PRIMARY_GAMES_WITHOUT_VERIFIED_METADATA_SQL]) now matches [ACCEPTED_VERIFIED_METADATA_SOURCES_SQL],
     * not just `screenscraper:en` — a `pc_games` row ScreenScraper structurally can never verify (no
     * system id for that platform) but that already carries a `steam:store` row, or only a
     * `steamgriddb` marker with no synopsis, is correctly excluded here too. Without that widening, this
     * automatic worker would re-select the same already-settled `pc_games` row on every run forever,
     * burning one of its bounded per-run slots on a game with nothing left to learn. Deliberately not
     * renamed — its one caller (`:app`'s `ConnectedMetadataHydrator`) is out of scope for this change;
     * only the selector SQL and this kdoc moved.
     */
    @Query(PRIMARY_GAMES_WITHOUT_VERIFIED_METADATA_SQL)
    suspend fun primaryGamesWithoutScreenScraperMetadata(limit: Int): List<GameEntity>

    /**
     * The shelf/gamelist projection for a platform: only the surviving (primary) member of each dedup
     * group, never a hidden item. Mirrors the folded effective library the console renders.
     */
    @Query(
        """
        SELECT g.* FROM games g
        WHERE g.platformId = :platformId
          AND g.isPrimaryMember = 1
          AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.hidden = 1
                AND (h.gameId = g.gameId OR h.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)))
        ORDER BY g.title ASC
        """,
    )
    fun visibleGamesForPlatform(platformId: String): Flow<List<GameEntity>>

    /** Distinct visible game count for a platform (drives the platform tile / shelf-visibility gate). */
    @Query(
        """
        SELECT COUNT(DISTINCT g.gameId) FROM games g
        WHERE g.platformId = :platformId
          AND g.isPrimaryMember = 1
          AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.hidden = 1
                AND (h.gameId = g.gameId OR h.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)))
        """,
    )
    suspend fun visibleGameCount(platformId: String): Int

    /**
     * Same-platform primary games sharing a proven byte-exact raw SHA-1. Prefix, exact length, and every
     * suffix character are checked in SQL so canonical DAT hashes and future namespaces cannot enter.
     */
    @Query(EXACT_HASH_CANDIDATES_SQL)
    suspend fun exactHashDuplicateCandidates(): List<GameEntity>

    /** Persist a per-entry raw exact hash; null deliberately invalidates stale evidence. */
    @Query("UPDATE games SET contentHash = :hash WHERE entryKey = :entryKey")
    suspend fun setContentHash(entryKey: String, hash: String?): Int

    /** Stamp only the pre-existing logical primary; subordinate disc/catalog member facts remain intact. */
    @Query(
        "UPDATE games SET duplicateGroupId = NULL, duplicateGroupKind = :groupKind " +
            "WHERE gameId = :winnerGameId AND isPrimaryMember = 1",
    )
    suspend fun stampGroupWinner(winnerGameId: String, groupKind: String): Int

    /** Hide only a logical game's current primary rows behind another game. */
    @Query(
        "UPDATE games SET isPrimaryMember = 0, duplicateGroupId = :winnerGameId, " +
            "duplicateGroupKind = :groupKind WHERE gameId = :loserGameId AND isPrimaryMember = 1",
    )
    suspend fun foldIntoGroup(loserGameId: String, winnerGameId: String, groupKind: String): Int

    /**
     * Exact-source rows whose logical node touches a terminal exact/catalog edge. Nonterminal
     * disc/playlist members are deliberate seeds: rescanning a root that owns only a subordinate
     * member must still dissolve an enclosing cross-root terminal fold.
     */
    @Query(TERMINAL_GROUP_SEEDS_FOR_EXACT_SOURCE_SQL)
    suspend fun terminalGroupSeedsForSource(rootUri: String): List<TerminalGroupLink>

    /**
     * One bounded expansion step through the undirected `gameId` ↔ `duplicateGroupId` terminal graph.
     * Callers chunk the repeated list below the portable bind floor.
     */
    @Query(TERMINAL_GROUP_LINKS_FOR_IDS_SQL)
    suspend fun terminalGroupLinksForIds(groupIds: List<String>): List<TerminalGroupLink>

    /** Dissolve only terminal exact/catalog rows; subordinate disc/playlist rows retain their facts. */
    @Query(DISSOLVE_EXACT_GROUPS_SQL)
    suspend fun dissolveExactGroups(groupIds: List<String>): Int

    /** Logical games inside a terminal cross-root group, captured before its parent relation is cleared. */
    @Query(EXACT_LOGICAL_GAME_IDS_SQL)
    suspend fun exactLogicalGameIds(groupIds: List<String>): List<String>

    /** Re-stamp a restored primary from its untouched multi-disc/catalog subordinate rows. */
    @Query(RESTORE_SUBORDINATE_GROUP_KINDS_SQL)
    suspend fun restoreSubordinateGroupKinds(logicalGameIds: List<String>): Int

    /**
     * A successful walk calls this before upsert so either side of a cross-root exact/catalog fold
     * invalidates it. Exact-root rows seed an iterative, bounded graph expansion; unrelated terminal
     * groups are never materialized. Chunking is part of correctness: Room expands the graph, logical
     * game, and dissolve lists twice, while the subordinate restore expands its list once.
     */
    @Transaction
    suspend fun prepareSourceForRescan(rootUri: String): Int {
        val ids = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        suspend fun discover(links: List<TerminalGroupLink>) {
            links.forEachIndexed { index, link ->
                if (index % 256 == 0) currentCoroutineContext().ensureActive()
                if (ids.add(link.gameId)) pending.addLast(link.gameId)
                link.duplicateGroupId?.let { parent ->
                    if (ids.add(parent)) pending.addLast(parent)
                }
            }
        }

        discover(terminalGroupSeedsForSource(rootUri))
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val frontier = ArrayList<String>(DOUBLE_IN_QUERY_CHUNK_SIZE)
            while (frontier.size < DOUBLE_IN_QUERY_CHUNK_SIZE && pending.isNotEmpty()) {
                frontier += pending.removeFirst()
            }
            discover(terminalGroupLinksForIds(frontier))
        }
        currentCoroutineContext().ensureActive()
        if (ids.isEmpty()) return 0
        val sortedIds = ids.sorted()
        val logicalGameIds = linkedSetOf<String>()
        sortedIds.chunked(DOUBLE_IN_QUERY_CHUNK_SIZE).forEach { groupIds ->
            currentCoroutineContext().ensureActive()
            logicalGameIds += exactLogicalGameIds(groupIds)
        }
        var dissolved = 0
        sortedIds.chunked(DOUBLE_IN_QUERY_CHUNK_SIZE).forEach { groupIds ->
            currentCoroutineContext().ensureActive()
            dissolved += dissolveExactGroups(groupIds)
        }
        logicalGameIds
            .sorted()
            .chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
            .forEach { gameIds ->
                currentCoroutineContext().ensureActive()
                restoreSubordinateGroupKinds(gameIds)
            }
        return dissolved
    }

    /** Stable, platform-scoped exact reconciliation in one Room transaction. */
    @Transaction
    suspend fun reconcileExactHashDuplicates(): Int {
        var folded = 0
        planExactHashReconciliation(exactHashDuplicateCandidates()).forEach { plan ->
            stampGroupWinner(plan.winnerGameId, "EXACT_HASH")
            plan.loserGameIds.forEach { loserGameId ->
                if (foldIntoGroup(loserGameId, plan.winnerGameId, "EXACT_HASH") > 0) folded++
            }
        }
        return folded
    }

    /**
     * The dedup-collapsed game ROWS for a platform: one row per visible group (primary member),
     * carrying its title, group kind, and the total member count behind it (the "N discs/versions"
     * badge). This is the exact shape the [com.gruning.mimir.rewrite.core.model.library.GameRow] the UI
     * consumes needs — computed in SQL so the UI never folds.
     *
     * Read from ONE snapshot, hence [Transaction], for the reason [favoriteCandidateGameRows] gives. A large
     * platform outgrows one 2 MiB CursorWindow: on the RG Rotate  the SNES list was
     * 1.45 MB after the collapse regrouper folded its variants and over 2 MiB before. A refill outside a
     * transaction re-runs the query on a new snapshot, and a fold in between crashes it with "Couldn't read
     * row N". Since the favourites wait was capped, a player can reach this list while
     * identification is still folding. The repository throttles re-reads, because the read holds Room's
     * write connection while it runs.
     */
    @Transaction
    @Query(VISIBLE_GAME_ROWS_FOR_PLATFORM_SQL)
    fun visibleGameRowsForPlatform(platformId: String): Flow<List<GameRowProjection>>

    /**
     * Every visible group on every platform, light enough for onboarding's picker. See [FAVORITE_CANDIDATE_GAME_ROWS_SQL].
     *
     * Read from ONE snapshot, hence [Transaction]. On RG Rotate (2026-09-28), this crashed with
     * "Couldn't read row 1271": on a 5,500-game library the result is about 2 MB, more than one 2 MiB
     * CursorWindow. Outside a transaction Android refills the window by re-running the query from a third of
     * the window's capacity back, on a NEW snapshot; the collapse regrouper had folded variants in between, the
     * result had shrunk, and the row the refill asked for no longer existed. Inside a transaction every refill
     * reads the same snapshot. The price is that the read holds Room's write connection while it runs, so the
     * repository throttles how often it re-reads (LibraryRepositoryImpl.observeFavoriteCandidates).
     */
    @Transaction
    @Query(FAVORITE_CANDIDATE_GAME_ROWS_SQL)
    fun favoriteCandidateGameRows(): Flow<List<GameRowProjection>>

    /** The favourited visible groups; see [FAVORITE_GROUP_IDS_SQL]. A few rows, so no transaction. */
    @Query(FAVORITE_GROUP_IDS_SQL)
    fun favoriteGroupIds(): Flow<List<String>>

    /** Exact dedup-collapsed row for a standalone detail page, including hidden groups. */
    @Query(GAME_ROW_BY_ID_SQL)
    fun observeGameRow(gameId: String): Flow<GameRowProjection?>

    /**
     * One reactive batch for Home Shelf emulated membership. Missing, unavailable, folded, and hidden
     * groups are omitted without mutating the membership table.
     */
    @Query(VISIBLE_AVAILABLE_GAME_ROWS_BY_IDS_SQL)
    fun visibleAvailableGameRowsByIds(gameIds: List<String>): Flow<List<GameRowProjection>>

    /** The Home "Last Played" context: launched groups, most recent first. */
    @Query(RECENTLY_PLAYED_GAME_ROWS_SQL)
    fun recentlyPlayedGameRows(limit: Int): Flow<List<GameRowProjection>>

    /**
     * The FAVORITED groups across ALL platforms — the favorites-only view. One row per favorited visible
     * group, most-recently-favorited first. Same rich projection as [visibleGameRowsForPlatform] (member
     * count, override, last-played), but group-aware favorite presence is the filter and platform is unconstrained.
     * Hidden groups are excluded — hiding a favorite takes it off every surface, favorites included.
     */
    @Query(
        """
        SELECT g.gameId AS gameId,
               COALESCE(g.canonicalTitle, g.title) AS title,
               g.platformId AS platformId,
               g.duplicateGroupKind AS duplicateGroupKind,
               (SELECT COUNT(*) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL) AS memberCount,
               (SELECT COUNT(DISTINCT m.discNumber) FROM games m
                    WHERE $LOGICAL_GAME_MEMBER_OF_ROW_PREDICATE_SQL
                      AND m.discNumber > 0) AS discCount,
               1 AS isFavorite,
               (CASE WHEN EXISTS(SELECT 1 FROM emulator_overrides o
                    WHERE o.gameId = g.gameId OR o.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)) THEN 1 ELSE 0 END) AS hasOverride,
               (SELECT MAX(lp.timestampMillis) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS lastPlayedMillis,
               (SELECT COUNT(*) FROM launch_proofs lp
                    WHERE (lp.gameId = g.gameId OR lp.gameId IN
                        (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
                      AND lp.outcome = 'intent_sent') AS playCount,
               $FIRST_PLAYED_MILLIS_SQL AS firstPlayedMillis,
               g.region AS region,
               g.version AS version,
               g.sizeBytes AS sizeBytes,
               g.sourceDocumentUri AS sourceDocumentUri,
               g.managedPath AS managedPath,
               (SELECT t.canonicalTitle FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS providerCanonicalTitle,
               (SELECT $FRESH_DESCRIPTION_SQL FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS description,
               (SELECT t.source FROM title_metadata_cache t
                    WHERE $SCREEN_SCRAPER_METADATA_MATCH_SQL
                      AND LOWER(t.source) IN $ACCEPTED_VERIFIED_METADATA_SOURCES_SQL
                    ORDER BY $SCREEN_SCRAPER_METADATA_ORDER_SQL
                    LIMIT 1) AS descriptionSource,
               (SELECT ov.title FROM title_overrides ov
                    WHERE $TITLE_OVERRIDE_MATCH_SQL
                    ORDER BY ov.setAtMillis DESC
                    LIMIT 1) AS manualTitleOverride
        FROM games g
        WHERE g.isPrimaryMember = 1
          AND EXISTS(SELECT 1 FROM favorites f
                WHERE f.gameId = g.gameId OR f.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId))
          AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.hidden = 1
                AND (h.gameId = g.gameId OR h.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)))
        GROUP BY g.gameId
        $FAVORITE_GAME_ORDER_BY_SQL
        """,
    )
    fun favoriteGameRows(): Flow<List<GameRowProjection>>

    /**
     * The USER-HIDDEN groups (for a restore surface) — one row per hidden group, most-recently hidden
     * first. Joined back to `games` for the primary's title/platform so the list reads like the gamelist.
     */
    @Query(HIDDEN_GAME_ROWS_SQL)
    fun hiddenGameRows(): Flow<List<GameRowProjection>>

    /** Total dedup-collapsed visible game count across ALL platforms (the summary "N games"). */
    @Query(
        """
        SELECT COUNT(DISTINCT g.gameId) FROM games g
        WHERE g.isPrimaryMember = 1
          AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.hidden = 1
                AND (h.gameId = g.gameId OR h.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)))
        """,
    )
    fun observeVisibleGameCount(): Flow<Int>

    /** Distinct platform ids that currently have at least one visible game (drives the shelf gate). */
    @Query(
        """
        SELECT DISTINCT g.platformId FROM games g
        WHERE g.isPrimaryMember = 1
          AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.hidden = 1
                AND (h.gameId = g.gameId OR h.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)))
        """,
    )
    suspend fun platformsWithVisibleGames(): List<String>

    /**
     * Per-platform visible group counts, as rows — one pass over the folded library. Backs the shelf
     * platform rows + the platform tiles without N per-platform round-trips.
     */
    @Query(
        """
        SELECT g.platformId AS platformId, COUNT(DISTINCT g.gameId) AS gameCount
        FROM games g
        WHERE g.isPrimaryMember = 1
          AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.hidden = 1
                AND (h.gameId = g.gameId OR h.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)))
        GROUP BY g.platformId
        """,
    )
    fun observePlatformCounts(): Flow<List<PlatformCountProjection>>

    /** Visible group count for one exact, mandatory v11 source owner. */
    @Query(
        """
        SELECT COUNT(DISTINCT g.gameId) FROM games g
        WHERE g.sourceRootUri = :rootUri
          AND g.isPrimaryMember = 1
          AND NOT EXISTS(SELECT 1 FROM hidden_items h WHERE h.hidden = 1
                AND (h.gameId = g.gameId OR h.gameId IN
                    (SELECT m.gameId FROM games m WHERE m.duplicateGroupId = g.gameId)))
        """,
    )
    suspend fun visibleGameCountForSource(rootUri: String): Int

    /** A last-seen SAF or managed-path source can still be absent after an SD detach. */
    @Query(
        "SELECT sr.rootUri FROM source_roots sr JOIN games g ON g.sourceRootUri = sr.rootUri " +
            "GROUP BY sr.rootUri HAVING " +
            "SUM(CASE WHEN g.availability = 'STORAGE_UNAVAILABLE' THEN 1 ELSE 0 END) = COUNT(*)",
    )
    fun observeStorageUnavailableSourceRoots(): Flow<List<String>>

    /**
     * The row indexed at one absolute path, or null — **the file manager's side of a move.**
     *
     * `LibraryRepository.relocate` is handed
     * two plain paths, because that is all a file operation knows. `entryKey` cannot be computed from
     * them — it is `physicalResidentKey(sourceRootUri, relativePath)` and the caller knows neither
     * component — so the row has to be found by the one column that IS the absolute path.
     *
     * **Unindexed, and deliberately not indexed for this.** It runs once per moved file, against a table
     * measured at about 5.5k rows; an index on `managedPath` would be paid on every scan
     * insert to save one scan per player-initiated move. `LIMIT 1` because `managedPath` is unique in
     * practice (two rows at one path would already be a defect) without claiming a constraint the schema
     * does not declare.
     */
    @Query("SELECT * FROM games WHERE managedPath = :path LIMIT 1")
    suspend fun gameByManagedPath(path: String): GameEntity?

    /** How many physical rows share one `gameId` — a multi-disc set or a duplicate group when > 1. */
    @Query("SELECT COUNT(*) FROM games WHERE gameId = :gameId")
    suspend fun memberCount(gameId: String): Int

    @Query("DELETE FROM games WHERE entryKey = :entryKey")
    suspend fun deleteByEntryKey(entryKey: String)

    /** Exact physical membership for one mandatory v11 source owner. */
    @Query(ENTRY_KEYS_FOR_EXACT_SOURCE_SQL)
    suspend fun entryKeysForSource(rootUri: String): List<String>

    @Query("DELETE FROM games WHERE entryKey IN (:entryKeys)")
    suspend fun deleteByEntryKeys(entryKeys: List<String>): Int

    /**
     * Prune one successfully walked source without materializing keys from unrelated roots. Deletes are
     * chunked below Android API 30's portable 999-bind floor; an enclosing scan transaction makes all
     * chunks atomic.
     */
    @Transaction
    suspend fun pruneEntriesMissingFromSource(
        rootUri: String,
        currentKeys: Set<String>,
    ): Int {
        val staleKeys = entryKeysForSource(rootUri).filterNot(currentKeys::contains)
        var deleted = 0
        staleKeys.chunked(SINGLE_IN_QUERY_CHUNK_SIZE).forEach { keys ->
            currentCoroutineContext().ensureActive()
            deleted += deleteByEntryKeys(keys)
        }
        return deleted
    }

    /** Every persisted entry key — diagnostics, recovery tests, and whole-library evidence only. */
    @Query("SELECT entryKey FROM games")
    suspend fun allEntryKeys(): List<String>

    /** The smallest primary game that carries a SAF document uri — used to prove on-demand hashing cheaply. */
    @Query(
        "SELECT * FROM games WHERE isPrimaryMember = 1 AND sourceDocumentUri IS NOT NULL " +
            "ORDER BY sizeBytes ASC, sourceRootUri ASC, relativePath ASC, entryKey ASC LIMIT 1",
    )
    suspend fun smallestHashablePrimary(): GameEntity?

    /** As above but restricted to a platform set — used to pick a real ROM (not a disc-playlist) to identify. */
    @Query(
        "SELECT * FROM games WHERE isPrimaryMember = 1 AND sourceDocumentUri IS NOT NULL " +
            "AND platformId IN (:platforms) " +
            "ORDER BY sizeBytes ASC, sourceRootUri ASC, relativePath ASC, entryKey ASC LIMIT 1",
    )
    suspend fun smallestHashablePrimaryIn(platforms: List<String>): GameEntity?

    /** Distinct platforms that have at least one hashable primary — used to probe identity coverage. */
    @Query("SELECT DISTINCT platformId FROM games WHERE isPrimaryMember = 1 AND sourceDocumentUri IS NOT NULL")
    suspend fun distinctHashablePlatforms(): List<String>

    /** All hashable members on [platform], smallest first — for multi-track discs (hash tracks until a hit). */
    @Query(
        "SELECT * FROM games WHERE sourceDocumentUri IS NOT NULL AND platformId = :platform " +
            "ORDER BY sizeBytes ASC, sourceRootUri ASC, relativePath ASC, entryKey ASC",
    )
    suspend fun hashableMembers(platform: String): List<GameEntity>

    // ── Identity enrichment (the LibraryIdentityEnricher pass) ───────────────────────────────────
    /** Primary members not yet run through the identity enricher, smallest first (cheap ones resolve first). */
    @Query(
        "SELECT * FROM games WHERE isPrimaryMember = 1 AND identityResolvedAtMillis IS NULL " +
            "ORDER BY sizeBytes ASC, entryKey ASC LIMIT :limit",
    )
    suspend fun unresolvedPrimaries(limit: Int): List<GameEntity>

    /**
     * Keyset page for the crash-resumable enrichment sweep. A retryable smallest resident must not starve
     * every larger resident forever; the app persists this `(sizeBytes, entryKey)` cursor per policy stamp.
     */
    @Query(
        "SELECT * FROM games WHERE isPrimaryMember = 1 AND identityResolvedAtMillis IS NULL AND " +
            "(sizeBytes > :afterSizeBytes OR (sizeBytes = :afterSizeBytes AND entryKey > :afterEntryKey)) " +
            "ORDER BY sizeBytes ASC, entryKey ASC LIMIT :limit",
    )
    suspend fun unresolvedPrimariesAfter(
        afterSizeBytes: Long,
        afterEntryKey: String,
        limit: Int,
    ): List<GameEntity>

    /** All members of a game, smallest first — the enricher hashes a disc's tracks until one identifies. */
    @Query(
        "SELECT * FROM games WHERE $LOGICAL_GAME_MEMBER_PREDICATE_SQL " +
            "ORDER BY sizeBytes ASC, sourceRootUri ASC, relativePath ASC, entryKey ASC",
    )
    suspend fun membersOfGameBySize(gameId: String): List<GameEntity>

    /** Write a resolved identity onto EVERY member of a game (null title = resolved-but-no-match). */
    @Query(
        "UPDATE games SET canonicalTitle = :title, collapseKey = :collapse, identityOrigin = :origin, " +
            "identityResolvedAtMillis = :ts WHERE gameId = :gameId",
    )
    suspend fun setIdentity(gameId: String, title: String?, collapse: String?, origin: String?, ts: Long)

    /**
     * SD01b: one transaction per enrichment chunk — a chunk of identity writes lands as ONE table
     * invalidation instead of N. Per-row writes made every observed Flow (counts, shelf summaries,
     * gamelists) re-query per game; at 5.5k games that write-storm, not hashing, was the identify
     * pass's floor (Room's arch_disk_io pool pinned while the hashers idled).
     */
    @Transaction
    suspend fun setIdentities(updates: List<IdentityWrite>) {
        for (u in updates) setIdentity(u.gameId, u.title, u.collapse, u.origin, u.ts)
    }

    /** SD02: how many visible primaries are currently on an absent volume (the banner count). */
    @Query("SELECT COUNT(*) FROM games WHERE isPrimaryMember = 1 AND availability = 'STORAGE_UNAVAILABLE'")
    suspend fun storageUnavailableCount(): Int

    /** Clear the resolve stamp on games that DIDN'T match, so the enricher re-attempts them (after a fix). */
    @Query("UPDATE games SET identityResolvedAtMillis = NULL WHERE canonicalTitle IS NULL")
    suspend fun clearUnmatchedIdentity(): Int

    /**
     * Athena A1D parent-discovery inventory: every AVAILABLE CHD-shaped resident, primary and non-primary,
     * across every root. The name only locates bytes — selection and identity stay digest-bound. The
     * document-URI leaf is matched too because CHD routing falls back to it when a SAF provider persists
     * opaque relative leaves. Ordered by physical location so the resident budget is deterministic across
     * key codecs; callers pass budget+1 to detect overflow rather than silently truncating.
     */
    @Query(
        "SELECT * FROM games WHERE availability = 'AVAILABLE' AND " +
            "(LOWER(relativePath) LIKE '%.chd' OR LOWER(displayName) LIKE '%.chd' " +
            "OR LOWER(sourceDocumentUri) LIKE '%.chd') " +
            "ORDER BY sourceRootUri ASC, relativePath ASC, entryKey ASC LIMIT :limit",
    )
    suspend fun availableChdShapedResidents(limit: Int): List<GameEntity>

    /**
     * EP18 MAME dependency audit inventory: every current arcade resident owned by one exact v11 source
     * root. Primary state and logical grouping are irrelevant because dependency atoms belong to physical
     * residents. Callers request budget+1 so overflow is refused rather than silently truncated.
     */
    @Query(AVAILABLE_ARCADE_RESIDENTS_FOR_EXACT_SOURCE_SQL)
    suspend fun availableArcadeResidentsForSource(
        rootUri: String,
        limit: Int,
    ): List<GameEntity>

    /**
     * CHD capability replay: reopen exactly the games whose enrichment ended attempted-with-no-match
     * while the game owns a CHD-shaped resident — the parent-required terminal-abstention era. Identified
     * rows, catalog folds, aliases and user state are untouched; a `.m3u` primary reopens through its `.chd`
     * member because members share the gameId. Availability is deliberately ignored: a detached volume's
     * terminal attempt reopens into retryable so the returning storage resolves it.
     */
    @Query(
        "UPDATE games SET identityResolvedAtMillis = NULL WHERE identityResolvedAtMillis IS NOT NULL " +
            "AND canonicalTitle IS NULL AND gameId IN (SELECT affected.gameId FROM games affected " +
            "WHERE LOWER(affected.relativePath) LIKE '%.chd' OR LOWER(affected.displayName) LIKE '%.chd' " +
            "OR LOWER(affected.sourceDocumentUri) LIKE '%.chd')",
    )
    suspend fun reopenTerminalChdAttempts(): Int

    /**
     * Athena DEFAULT-route ZIP capability replay. Reopen attempted-with-no-match games owning a
     * ZIP-shaped resident so the payload-selector/oracle pass can re-evaluate sidecars and one bounded
     * nested archive. Identified rows and every user-state table remain untouched.
     */
    @Query(
        "UPDATE games SET identityResolvedAtMillis = NULL WHERE identityResolvedAtMillis IS NOT NULL " +
            "AND canonicalTitle IS NULL AND gameId IN (SELECT affected.gameId FROM games affected " +
            "WHERE LOWER(affected.relativePath) LIKE '%.zip' OR LOWER(affected.displayName) LIKE '%.zip' " +
            "OR LOWER(affected.sourceDocumentUri) LIKE '%.zip')",
    )
    suspend fun reopenTerminalZipAttempts(): Int

    /** Live count of primaries the enricher hasn't touched — the coordinator drains whenever this is > 0. */
    @Query("SELECT COUNT(*) FROM games WHERE isPrimaryMember = 1 AND identityResolvedAtMillis IS NULL")
    fun unresolvedPrimaryCount(): Flow<Int>

    /**
     * How many of [gameIds] the enricher has not finished with yet.
     *
     * Distinct from [unresolvedPrimaryCount] in the one way that matters: that one counts the WHOLE table,
     * so it reads 0 both when everything is done and when nothing has been inserted yet. A caller waiting for a particular game set cannot distinguish those cases, and on a first run it always asks too early.
     * This counts the rows the caller actually named, so an empty answer can only mean they are done.
     *
     * Resolved means ATTEMPTED, not matched: a game the catalogue had nothing for still gets a
     * `identityResolvedAtMillis`, so a library with unmatched titles still reaches zero.
     */
    @Query("SELECT COUNT(*) FROM games WHERE gameId IN (:gameIds) AND identityResolvedAtMillis IS NULL")
    suspend fun unresolvedCountAmong(gameIds: List<String>): Int

    // ── Background re-verification of name-resolved identities ──────────────────────────────────
    /**
     * Resolved primaries that never persisted a whole-file SHA-1, smallest first, minus everything the
     * re-verification pass has already attempted.
     *
     * **Read the `NOT EXISTS` before you simplify it.** The obvious selector is `contentHash IS NULL`
     * alone, and it does not terminate. A re-verified game does not always end up with a hash:
     * `RomFingerprinter` returns `exactRawSha1 = null` BY DESIGN for a zip entry, for a CHD, and for a
     * NES ROM whose iNES header was stripped, because in each case the bytes that were hashed are not
     * the bytes on disk. Those rows come back null after a successful, complete verification, so a pass
     * keyed on `contentHash IS NULL` alone would re-read the same files on every run, forever. The
     * marker is the predicate precisely because the marker is the thing this pass always writes.
     *
     * Ordering is `sizeBytes ASC` for the same reason [unresolvedPrimariesAfter] uses it: the cheap
     * confirmations land first, so an interrupted pass has still bought something.
     */
    @Query(
        "SELECT * FROM games WHERE isPrimaryMember = 1 AND identityResolvedAtMillis IS NOT NULL " +
            "AND contentHash IS NULL AND NOT EXISTS (SELECT 1 FROM identity_maintenance m " +
            "WHERE m.maintenanceKey = :attemptedPrefix || entryKey) " +
            "AND NOT EXISTS (SELECT 1 FROM identity_maintenance u " +
            "WHERE u.maintenanceKey = :unreadablePrefix || entryKey " +
            "AND u.completedAtMillis > :unreadableSince) " +
            "ORDER BY sizeBytes ASC, entryKey ASC LIMIT :limit",
    )
    suspend fun unverifiedResolvedPrimaries(
        attemptedPrefix: String,
        unreadablePrefix: String,
        unreadableSince: Long,
        limit: Int,
    ): List<GameEntity>

    /** The same population, counted, so a worker can decline to start rather than open the database twice. */
    @Query(
        "SELECT COUNT(*) FROM games WHERE isPrimaryMember = 1 AND identityResolvedAtMillis IS NOT NULL " +
            "AND contentHash IS NULL AND NOT EXISTS (SELECT 1 FROM identity_maintenance m " +
            "WHERE m.maintenanceKey = :attemptedPrefix || entryKey)",
    )
    suspend fun unverifiedResolvedPrimaryCount(attemptedPrefix: String): Int

    /**
     * Like [stampMaintenance] but REFRESHES an existing row instead of ignoring it.
     *
     * The unreadable cooldown needs the newest timestamp, not the first one: a card that has been absent
     * for a week should still be retried one cooldown after the LAST attempt, not once and never again.
     */
    @Query(
        "INSERT OR REPLACE INTO identity_maintenance (maintenanceKey, completedAtMillis) " +
            "VALUES (:key, :completedAtMillis)",
    )
    suspend fun refreshMaintenance(key: String, completedAtMillis: Long)

    /**
     * Stamp a maintenance marker. `OR IGNORE` because a marker records that something HAPPENED; the first
     * write is the true one and a re-run must not move its timestamp.
     */
    @Query(
        "INSERT OR IGNORE INTO identity_maintenance (maintenanceKey, completedAtMillis) " +
            "VALUES (:key, :completedAtMillis)",
    )
    suspend fun stampMaintenance(key: String, completedAtMillis: Long)

    /** Clear ALL resolved identity — forces a full re-enrichment (dev/diagnostics). */
    @Query("UPDATE games SET canonicalTitle = NULL, collapseKey = NULL, identityOrigin = NULL, identityResolvedAtMillis = NULL")
    suspend fun clearAllIdentity(): Int

    /**
     * Athena A1B one-time authority-policy replay.
     *
     * Before key provenance was retained, filename-only Arcade/Switch/PS3 catalog hits used the same origin
     * as payload-bound evidence. Reopen exactly those projections so the current engine can abstain or replace
     * them with header/hash authority. Stable ids, hashes, grouping, aliases and user stores are untouched.
     */
    @Query(
        "UPDATE games SET canonicalTitle = NULL, collapseKey = NULL, identityOrigin = NULL, " +
            "identityResolvedAtMillis = NULL WHERE " +
            "(platformId = 'arcade' AND identityOrigin = 'MAME') OR " +
            "(platformId = 'switch' AND identityOrigin = 'TITLE_CATALOG') OR " +
            "(platformId = 'ps3' AND identityOrigin = 'TITLE_CATALOG')",
    )
    suspend fun clearLegacyFilenameKeyedIdentity(): Int

    /** Resolved primaries eligible for catalog folding; exact groups are terminal in the flat schema. */
    @Query(RESOLVED_COLLAPSE_PRIMARIES_SQL)
    suspend fun resolvedCollapsePrimaries(): List<GameEntity>

    /**
     * The smallest hashable member (primary OR disc) on [platform] above 4 KB — the size floor skips tiny
     * `.m3u` playlists so a multi-disc game resolves to its real `.chd` disc, not its 58-byte manifest.
     */
    @Query(
        "SELECT * FROM games WHERE sourceDocumentUri IS NOT NULL AND platformId = :platform " +
            "AND sizeBytes > 4096 " +
            "ORDER BY sizeBytes ASC, sourceRootUri ASC, relativePath ASC, entryKey ASC LIMIT 1",
    )
    suspend fun smallestHashableMember(platform: String): GameEntity?
}

@Dao
interface PlatformDao {

    @Upsert
    suspend fun upsertAll(platforms: List<PlatformEntity>)

    /** Shelf rows: only platforms that actually have games (memory shelf-only-platforms-with-games). */
    @Query("SELECT * FROM platforms WHERE gamesCount > 0 ORDER BY displayName ASC")
    fun shelfPlatforms(): Flow<List<PlatformEntity>>

    /**
     * Platform METADATA for every known platform (label + order), regardless of the denormalized
     * `gamesCount`. The shelf composes this with the LIVE per-platform count from the games table
     * ([GameDao.observePlatformCounts]) so it re-renders when identity enrichment collapses duplicates —
     * `gamesCount` here is written once at scan time and is NOT re-touched by enrichment, so the shelf must
     * NOT gate on it (the stale shelf-count defect after identification).
     */
    @Query("SELECT * FROM platforms ORDER BY displayName ASC")
    fun observeAll(): Flow<List<PlatformEntity>>

    @Query("SELECT * FROM platforms WHERE platformId = :platformId")
    suspend fun byId(platformId: String): PlatformEntity?

    @Query("UPDATE platforms SET requiresBios = :requiresBios WHERE platformId = :platformId")
    suspend fun setRequiresBios(platformId: String, requiresBios: Boolean): Int
}

@Dao
interface SourceRootDao {

    @Upsert
    suspend fun upsert(root: SourceRootEntity)

    @Query("SELECT * FROM source_roots ORDER BY isPrimarySource DESC, friendlyLabel ASC")
    fun all(): Flow<List<SourceRootEntity>>

    /** SD02: one-shot read for the storage monitor's mount reconcile. */
    @Query("SELECT * FROM source_roots")
    suspend fun allOnce(): List<SourceRootEntity>

    /** Remove-from-registry only — never deletes files (memory library-source-registry). */
    @Query("DELETE FROM source_roots WHERE rootId = :rootId")
    suspend fun remove(rootId: String)
}

/**
 * Durable progress for one explicit multi-root reconciliation pass.
 *
 * No foreign key is intentional: a confirmed-missing root is removed from [SourceRootDao] in the same
 * transaction that settles its ledger row, and the tombstone must survive until the pass completes.
 */
@Dao
interface SourceReconcileDao {

    @Query("SELECT * FROM source_reconcile_runs ORDER BY startedAtMillis ASC, runId ASC LIMIT 1")
    suspend fun activeRun(): SourceReconcileRunEntity?

    @Query(
        "SELECT * FROM source_reconcile_roots WHERE runId = :runId " +
            "ORDER BY ordinal ASC, rootId ASC",
    )
    suspend fun rootsForRun(runId: String): List<SourceReconcileRootEntity>

    @Upsert
    suspend fun upsertRun(run: SourceReconcileRunEntity)

    @Upsert
    suspend fun upsertRoot(root: SourceReconcileRootEntity)

    @Upsert
    suspend fun upsertRoots(roots: List<SourceReconcileRootEntity>)

    @Query("DELETE FROM source_reconcile_roots WHERE runId = :runId")
    suspend fun deleteRoots(runId: String)

    @Query("DELETE FROM source_reconcile_runs WHERE runId = :runId")
    suspend fun deleteRun(runId: String)

    @Query("DELETE FROM source_reconcile_roots")
    suspend fun deleteAllRoots()

    @Query("DELETE FROM source_reconcile_runs")
    suspend fun deleteAllRuns()

    /** Invalidates a paused pass before a standalone source mutation commits. */
    @Transaction
    suspend fun clearAll() {
        deleteAllRoots()
        deleteAllRuns()
    }

    /** Finalizes one pass without leaving orphan progress rows. */
    @Transaction
    suspend fun clearRun(runId: String) {
        deleteRoots(runId)
        deleteRun(runId)
    }
}

@Dao
interface HiddenItemDao {

    @Upsert
    suspend fun upsert(item: HiddenItemEntity)

    @Query(
        "SELECT * FROM hidden_items WHERE gameId = :gameId OR gameId IN " +
            "(SELECT m.gameId FROM games m WHERE m.duplicateGroupId = :gameId) " +
            "ORDER BY CASE WHEN gameId = :gameId THEN 0 ELSE 1 END, hiddenAtMillis DESC LIMIT 1",
    )
    suspend fun byGame(gameId: String): HiddenItemEntity?

    /**
     * Browse restore list: only genuinely user-hidden games. Auto-collapsed dedup losers
     * (duplicateGroupId set AND not primary) are excluded — a required group member is not a
     * restorable optional copy.
     */
    @Query(
        """
        SELECT * FROM hidden_items
        WHERE hidden = 1
          AND reason = 'USER_DISMISSED'
          AND NOT (duplicateGroupId IS NOT NULL AND isPrimaryMember = 0)
        ORDER BY hiddenAtMillis DESC
        """,
    )
    fun restorable(): Flow<List<HiddenItemEntity>>

    @Query(
        "DELETE FROM hidden_items WHERE gameId = :gameId OR gameId IN " +
            "(SELECT m.gameId FROM games m WHERE m.duplicateGroupId = :gameId)",
    )
    suspend fun clear(gameId: String)
}

@Dao
interface EmulatorOverrideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(override: EmulatorOverrideEntity)

    /** Winner override wins; otherwise use the most-recent folded-member override. */
    @Query(
        "SELECT * FROM emulator_overrides WHERE gameId = :gameId OR gameId IN " +
            "(SELECT m.gameId FROM games m WHERE m.duplicateGroupId = :gameId) " +
            "ORDER BY CASE WHEN gameId = :gameId THEN 0 ELSE 1 END, setAtMillis DESC LIMIT 1",
    )
    suspend fun forGame(gameId: String): EmulatorOverrideEntity?

    /** Every per-game override, for config backup/export. Blank emulatorId rows are "Auto" resets. */
    @Query("SELECT * FROM emulator_overrides")
    suspend fun all(): List<EmulatorOverrideEntity>

    /** Clear = reset to Auto (memory open-with-per-game-emulator: key="" resets to Auto). */
    @Query(
        "DELETE FROM emulator_overrides WHERE gameId = :gameId OR gameId IN " +
            "(SELECT m.gameId FROM games m WHERE m.duplicateGroupId = :gameId)",
    )
    suspend fun clear(gameId: String)
}

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(favorite: FavoriteEntity)

    /** Migration-only, group-aware insert that never rewrites an existing Favorite timestamp. */
    @Query(PROMOTE_FAVORITE_GROUP_IF_ABSENT_SQL)
    suspend fun promoteGroupIfAbsent(gameId: String, favoritedAtMillis: Long): Long

    @Query(
        "DELETE FROM favorites WHERE gameId = :gameId OR gameId IN " +
            "(SELECT m.gameId FROM games m WHERE m.duplicateGroupId = :gameId)",
    )
    suspend fun clear(gameId: String)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorites WHERE gameId = :gameId OR gameId IN " +
            "(SELECT m.gameId FROM games m WHERE m.duplicateGroupId = :gameId))",
    )
    suspend fun isFavorite(gameId: String): Boolean
}

/**
 * Durable, profile-scoped Home Shelf membership. Mutations are transactions so concurrent Add intents
 * converge on one composite-key row and reorder never exposes a partially rebalanced sequence.
 */
@Dao
abstract class HomeShelfDao {

    @Query(HOME_SHELF_ITEMS_FOR_PROFILE_SQL)
    abstract fun observeItems(profileId: String): Flow<List<HomeShelfItemEntity>>

    @Query(HOME_SHELF_ITEMS_FOR_PROFILE_SQL)
    abstract suspend fun orderedItems(profileId: String): List<HomeShelfItemEntity>

    @Query(
        "SELECT * FROM home_shelf_items WHERE profileId = :profileId " +
            "AND itemKind = :itemKind AND itemId = :itemId LIMIT 1",
    )
    abstract suspend fun item(
        profileId: String,
        itemKind: String,
        itemId: String,
    ): HomeShelfItemEntity?

    @Query(HOME_SHELF_MAX_RANK_SQL)
    abstract suspend fun maxRank(profileId: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertItem(item: HomeShelfItemEntity): Long

    @Query(
        "DELETE FROM home_shelf_items WHERE profileId = :profileId " +
            "AND itemKind = :itemKind AND itemId = :itemId",
    )
    abstract suspend fun deleteItem(profileId: String, itemKind: String, itemId: String): Int

    @Query(
        "UPDATE home_shelf_items SET rank = :rank WHERE profileId = :profileId " +
            "AND itemKind = :itemKind AND itemId = :itemId",
    )
    abstract suspend fun updateRank(
        profileId: String,
        itemKind: String,
        itemId: String,
        rank: Long,
    ): Int

    /** Appends exactly once, preserving the first row's rank and addedAt on duplicate calls. */
    @Transaction
    open suspend fun addIfAbsent(
        profileId: String,
        itemKind: String,
        itemId: String,
        addedAtMillis: Long,
    ): HomeShelfInsertResult {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(itemId.isNotBlank()) { "itemId must not be blank" }
        requireHomeShelfKind(itemKind)

        item(profileId, itemKind, itemId)?.let { existing ->
            return HomeShelfInsertResult(existing, inserted = false)
        }

        var maximum = maxRank(profileId)
        if (maximum != null && homeShelfRankBetween(maximum, next = null) == null) {
            rebalance(orderedItems(profileId))
            maximum = maxRank(profileId)
        }
        val candidate = HomeShelfItemEntity(
            profileId = profileId,
            itemKind = itemKind,
            itemId = itemId,
            rank = checkNotNull(homeShelfRankBetween(maximum, next = null)),
            addedAtMillis = addedAtMillis,
        )
        val inserted = insertItem(candidate) != -1L
        val stored = if (inserted) {
            candidate
        } else {
            checkNotNull(item(profileId, itemKind, itemId)) {
                "Home Shelf insert was ignored without a conflicting row"
            }
        }
        return HomeShelfInsertResult(stored, inserted)
    }

    /** Moves an existing item before another existing item, or to the tail when the target is null. */
    @Transaction
    open suspend fun moveItem(
        profileId: String,
        itemKind: String,
        itemId: String,
        beforeKind: String?,
        beforeId: String?,
    ): Boolean {
        requireHomeShelfKind(itemKind)
        require((beforeKind == null) == (beforeId == null)) {
            "beforeKind and beforeId must both be null or both be present"
        }
        beforeKind?.let(::requireHomeShelfKind)
        if (itemKind == beforeKind && itemId == beforeId) return false

        val current = orderedItems(profileId)
        val sourceIndex = current.indexOfFirst { it.itemKind == itemKind && it.itemId == itemId }
        if (sourceIndex < 0) return false

        val reordered = current.toMutableList()
        val moving = reordered.removeAt(sourceIndex)
        val destinationIndex = if (beforeKind == null) {
            reordered.size
        } else {
            reordered.indexOfFirst { it.itemKind == beforeKind && it.itemId == beforeId }
                .takeIf { it >= 0 }
                ?: return false
        }
        reordered.add(destinationIndex, moving)
        if (current.sameHomeShelfOrder(reordered)) return false

        val movedIndex = reordered.indexOf(moving)
        val sparseRank = homeShelfRankBetween(
            previous = reordered.getOrNull(movedIndex - 1)?.rank,
            next = reordered.getOrNull(movedIndex + 1)?.rank,
        )
        if (sparseRank != null) {
            check(updateRank(profileId, itemKind, itemId, sparseRank) == 1) {
                "Home Shelf item disappeared during reorder"
            }
        } else {
            rebalance(reordered)
        }
        return true
    }

    private suspend fun rebalance(items: List<HomeShelfItemEntity>) {
        items.forEachIndexed { index, item ->
            val rank = Math.multiplyExact(index.toLong() + 1L, HOME_SHELF_RANK_STEP)
            check(updateRank(item.profileId, item.itemKind, item.itemId, rank) == 1) {
                "Home Shelf item disappeared during rank rebalance"
            }
        }
    }
}

private fun requireHomeShelfKind(itemKind: String) {
    require(itemKind == HOME_SHELF_EMULATED_GAME_KIND || itemKind == HOME_SHELF_ANDROID_GAME_KIND) {
        "Unsupported Home Shelf item kind: $itemKind"
    }
}

private fun List<HomeShelfItemEntity>.sameHomeShelfOrder(other: List<HomeShelfItemEntity>): Boolean =
    size == other.size && indices.all { index ->
        this[index].itemKind == other[index].itemKind && this[index].itemId == other[index].itemId
    }

@Dao
interface LaunchProofDao {

    @Insert
    suspend fun record(proof: LaunchProofEntity): Long

    /** Most-recent attempt for a game (the one a chip/badge would reflect). */
    @Query(
        "SELECT * FROM launch_proofs WHERE gameId = :gameId OR gameId IN " +
            "(SELECT m.gameId FROM games m WHERE m.duplicateGroupId = :gameId) " +
            "ORDER BY timestampMillis DESC LIMIT 1",
    )
    suspend fun latestForGame(gameId: String): LaunchProofEntity?

    @Query(
        "SELECT * FROM launch_proofs WHERE gameId = :gameId OR gameId IN " +
            "(SELECT m.gameId FROM games m WHERE m.duplicateGroupId = :gameId) " +
            "ORDER BY timestampMillis DESC",
    )
    fun historyForGame(gameId: String): Flow<List<LaunchProofEntity>>

    /** Latest successful native Android-game dispatch per package, ordered for Last Played. */
    @Query(RECENTLY_PLAYED_ANDROID_GAME_PROOFS_SQL)
    fun recentlyPlayedAndroidGames(limit: Int): Flow<List<AndroidGameLaunchRecencyProjection>>

    /**
     * The timestamp of every successful dispatch (`outcome = 'intent_sent'`) at or after [fromMillis], in no particular order: an emulated
     * game ([LaunchProofEntity.gameId]) or a native Android game ([LaunchProofEntity.androidPackageName]), the `intent_sent` test
     * `GameRow.playCount` applies minus its per-game scope. A launch of a game with no `games` row (its folder was removed) is an orphan and
     * is left out (see `LibraryRepository.observeLaunchesPerDay`); a HIDDEN game's launches count, they were played, though
     * [RECENTLY_PLAYED_GAME_ROWS_SQL] leaves hidden games out of its list. No day arithmetic: [observeLaunchesPerDay] buckets in Kotlin.
     */
    @Query(
        "SELECT lp.timestampMillis FROM launch_proofs lp WHERE lp.outcome = 'intent_sent' AND lp.timestampMillis >= :fromMillis " +
            "AND (lp.gameId IS NULL OR EXISTS(SELECT 1 FROM games g WHERE g.gameId = lp.gameId))",
    )
    fun intentSentTimestampsSince(fromMillis: Long): Flow<List<Long>>
}

@Dao
abstract class BiosProfileDao {

    @Upsert
    abstract suspend fun upsertAll(profiles: List<BiosProfileEntity>)

    /** Legacy platform-wide read retained for source compatibility during the EP-19B rollout. */
    @Query("SELECT * FROM bios_profiles WHERE platformId = :platformId")
    abstract suspend fun forPlatform(platformId: String): List<BiosProfileEntity>

    /** Required BIOS that are confirmed MISSING for a platform (a hard NeedsBios cause). */
    @Query("SELECT * FROM bios_profiles WHERE platformId = :platformId AND required = 1 AND presence = 'MISSING'")
    abstract suspend fun blockingMissingForPlatform(platformId: String): List<BiosProfileEntity>

    @Query(
        "DELETE FROM bios_profiles WHERE platformId = :platformId AND emulatorScopeKey = :emulatorScopeKey",
    )
    protected abstract suspend fun deleteForExactScope(platformId: String, emulatorScopeKey: String)

    @Query(
        "SELECT * FROM bios_profiles WHERE platformId = :platformId " +
            "AND emulatorScopeKey = :emulatorScopeKey AND packageName = :packageName",
    )
    abstract suspend fun forExactScope(
        platformId: String,
        emulatorScopeKey: String,
        packageName: String,
    ): List<BiosProfileEntity>

    @Query(
        "SELECT * FROM bios_profiles WHERE platformId = :platformId " +
            "AND emulatorScopeKey = :emulatorScopeKey AND packageName = :packageName " +
            "AND required = 1 AND presence = 'MISSING'",
    )
    abstract suspend fun blockingMissingForExactScope(
        platformId: String,
        emulatorScopeKey: String,
        packageName: String,
    ): List<BiosProfileEntity>

    /**
     * Atomically replaces every BIOS row for the exact `(platformId, emulatorScopeKey)` pair.
     * An empty [profiles] list still deletes stale rows for that scope.
     */
    @Transaction
    open suspend fun replaceExactScope(
        platformId: String,
        emulatorScopeKey: String,
        profiles: List<BiosProfileEntity>,
    ) {
        require(emulatorScopeKey.isNotEmpty()) {
            "replaceExactScope requires a non-empty emulatorScopeKey"
        }
        profiles.forEach { profile ->
            require(profile.platformId == platformId && profile.emulatorScopeKey == emulatorScopeKey) {
                "replaceExactScope profile disagrees with transaction arguments"
            }
        }
        deleteForExactScope(platformId, emulatorScopeKey)
        if (profiles.isNotEmpty()) {
            upsertAll(profiles)
        }
    }
}

@Dao
interface TitleMetadataCacheDao {

    @Upsert
    suspend fun upsertAll(entries: List<TitleMetadataCacheEntity>)

    /**
     * The raw stored row — [TitleMetadataCacheEntity.cacheExpiresAt] is NOT enforced here; an expired row
     * still comes back. This exists for write-merge callers (`VerifiedMetadataCache.persistVerifiedRecord`,
     * `core-metadata`'s `ScreenScraperMetadataCache.kt`) that read an existing row only to carry its fields
     * (`boxArtUri`, `license`, `screenshotUris`, …) forward into a fresh write. Enforcing expiry here would
     * make an expired `existing` read as null and silently DROP those carried-over fields from the new row
     * — a write-side change, which this table's read-enforcement (2026-08-20) is explicitly not allowed to
     * make. Any caller surfacing this row for DISPLAY or a freshness/re-fetch DECISION must go through
     * [freshByCatalogKey] instead, never this method directly.
     *
     * CLOSED (R6-P3-01) for `description` only: the shelf/gamelist read-model queries in this file
     * (`VISIBLE_GAME_ROWS_FOR_PLATFORM_SQL` and its siblings, all keyed off `SCREEN_SCRAPER_METADATA_MATCH_SQL`)
     * read `canonicalTitle`/`description`/`source` out of `title_metadata_cache` directly in correlated SQL,
     * bypassing both this method and [freshByCatalogKey] entirely. `description` is now projected through
     * `FRESH_DESCRIPTION_SQL` at all six sites (an inline `CASE` re-evaluated by SQLite on every query run),
     * so an expired capped-source row (`igdb`) reads as NULL there without a bind parameter — no migration,
     * no ViewModel change. STILL OWED: `canonicalTitle`/`descriptionSource` at those same sites stay
     * unguarded on purpose (deliberate, not an oversight — see `FRESH_DESCRIPTION_SQL`'s own kdoc for why:
     * they are Mimir's bookkeeping about the press, not the licensed Program Materials the 24h clause
     * covers). Threading a `nowMillis` bind parameter through those two fields would be the larger, riskier
     * change this note originally flagged; still needs its own scoped change if ever done.
     */
    @Query("SELECT * FROM title_metadata_cache WHERE catalogKey = :catalogKey")
    suspend fun byCatalogKey(catalogKey: String): TitleMetadataCacheEntity?

    /**
     * [byCatalogKey] with [TitleMetadataCacheEntity.cacheExpiresAt] enforced via
     * [com.gruning.mimir.rewrite.core.storage.entity.freshOrNull] — an expired row reads as absent. THE one
     * chokepoint for any future freshness-sensitive read of THIS row shape (display, or a "do I need to
     * re-fetch" decision); see [byCatalogKey]'s kdoc for why that raw method must stay unguarded, and for
     * the SQL shelf/gamelist projections' own (narrower, `description`-only) expiry enforcement via
     * `FRESH_DESCRIPTION_SQL`.
     *
     * [nowMillis] defaults to the production clock but is an explicit parameter so a caller (and every
     * test of this method) can inject its own — the same idiom `VerifiedMetadataCache.persistVerifiedRecord`
     * already uses for its own `nowMillis`.
     */
    suspend fun freshByCatalogKey(
        catalogKey: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): TitleMetadataCacheEntity? = byCatalogKey(catalogKey)?.freshOrNull(nowMillis)
}

/**
 * A dedup-collapsed game row projection — the SQL result of [GameDao.visibleGameRowsForPlatform]. NOT a
 * table; a read model computed by the folded query so the UI receives exactly one row per group with its
 * badge-driving member count. Maps 1:1 to `core-model` `GameRow`.
 */
data class GameRowProjection(
    val gameId: String,
    val title: String,
    val platformId: String,
    val duplicateGroupKind: String?,
    val memberCount: Int,
    /** Number of distinct physical disc ordinals; excludes playlists and split-track sidecars. */
    val discCount: Int = 0,
    /** 1 when the user favorited this group (favorites sort to the top). */
    val isFavorite: Int = 0,
    /** 1 when the user set an "Open with…" emulator override for this group. */
    val hasOverride: Int = 0,
    /** Epoch millis of the most-recent successful launch of this group, or null if never launched. */
    val lastPlayedMillis: Long? = null,
    /**
     * Epoch millis of the EARLIEST successful launch still on record for this group (the same `intent_sent` rows
     * [lastPlayedMillis] and [playCount] read), or null if none. Null on the favourites picker's read, which carries no
     * launch history.
     */
    val firstPlayedMillis: Long? = null,
    /** Number of successful launch dispatches (`intent_sent`) for this group. */
    val playCount: Int = 0,
    val region: String? = null,
    val version: String? = null,
    val sizeBytes: Long = 0L,
    /** The primary member's SAF `content://` document URI (a granted tree), or null for a plain-file source. */
    val sourceDocumentUri: String? = null,
    /** The primary member's plain-file path (a File-based source), or null for a SAF source. */
    val managedPath: String? = null,
    /** Cached local synopsis. The query never triggers provider work. */
    val description: String? = null,
    /** Provider marker even when the verified record carried no synopsis. */
    val descriptionSource: String? = null,
    /** Verified provider display title. It may refine presentation, never the stored game identity/group. */
    val providerCanonicalTitle: String? = null,
    /**
     * The player's manual rename, or null when none is set. Highest display precedence — a cosmetic
     * override  that never feeds recognition and is never touched by a scan or
     * re-enrichment write, so it survives both. See [com.gruning.mimir.rewrite.core.storage.entity.TitleOverrideEntity].
     */
    val manualTitleOverride: String? = null,
)

/** Minimal physical-member projection for the game-detail disc list. */
data class GameMemberProjection(
    val entryKey: String,
    val discNumber: Int,
    val displayName: String,
    val sizeBytes: Long,
)

/**
 * Where one game's bytes actually live — the result of [GAME_HASH_SOURCE_SQL].
 *
 * [sizeBytes] is the file's own size, which is what a size ceiling and a cache key must be built from: the
 * gamelist's `GameRow.sizeBytes` is the collapsed group's figure and can describe a different member.
 * Exactly one of [sourceDocumentUri] (a SAF grant) / [managedPath] (a plain file) is normally set.
 */
data class GameHashSourceProjection(
    val entryKey: String,
    val sourceRootUri: String,
    val relativePath: String,
    val platformId: String,
    val sourceDocumentUri: String?,
    val managedPath: String?,
    val sizeBytes: Long,
)

/** Per-platform visible group count projection (result of [GameDao.observePlatformCounts]). */
data class PlatformCountProjection(
    val platformId: String,
    val gameCount: Int,
)

/** SD01b: one game's resolved identity, carried into the chunked [GameDao.setIdentities] transaction. */
data class IdentityWrite(
    val gameId: String,
    val title: String?,
    val collapse: String?,
    val origin: String?,
    val ts: Long,
)
