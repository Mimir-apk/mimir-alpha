package com.gruning.mimir.rewrite.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities for the Mimir-rewrite local store. Each row is a flat, indexable projection of a
 * core-model type — the typed truth lives in :core-model; these are the persistence shape. Mappers in
 * `com.gruning.mimir.rewrite.core.storage.mapper` convert between them. Nothing here touches the
 * network; this is a pure local SQLite schema (posture manifest).
 *
 * Column naming mirrors the current native `library_entry` / registry columns where a 1:1 mapping
 * exists, so a future data migration from the shipping product is mechanical.
 */

/**
 * One indexed physical file — the persistence shape of `core-model` `ResidentFile`. Keyed by the
 * stable per-file `entryKey` (native `library_entry.entry_key`). `gameId` groups multi-disc /
 * cross-storage / region siblings; it is NOT unique.
 */
@Entity(
    tableName = "games",
    indices = [
        Index(value = ["gameId"]),
        Index(value = ["platformId"]),
        Index(value = ["contentHash"]),
        Index(value = ["duplicateGroupId"]),
        Index(
            value = ["sourceRootUri", "relativePath"],
            unique = true,
            name = "index_games_sourceRootUri_relativePath",
        ),
        Index(
            value = ["sourceRootUri", "entryKey"],
            name = "index_games_sourceRootUri_entryKey",
        ),
        // SD01 — the identity sweep. Two hot queries share this exact predicate: the enrichment DRIVER
        // (`unresolvedPrimaries`, once per chunk drain) and the PROGRESS COUNTER (`unresolvedPrimaryCount`,
        // a Flow that Room re-runs on every write to `games`, with at least two live collectors during a
        // pass). Unindexed, SQLite reported `SCAN games` for both and additionally
        // `USE TEMP B-TREE FOR ORDER BY` for the driver, over 5,538 rows, ~111 times per enrichment.
        // `sizeBytes` is the third column so the driver's `ORDER BY sizeBytes` is served by the index too —
        // that is what removes the temp B-tree. Measured on a captured database: both plans become
        // `SEARCH … USING INDEX` (the counter a COVERING index, never touching the table) for 108 KB.
        Index(
            value = ["isPrimaryMember", "identityResolvedAtMillis", "sizeBytes"],
            name = "index_games_identity_sweep",
        ),
    ],
)
data class GameEntity(
    @PrimaryKey val entryKey: String,
    val gameId: String,
    val platformId: String,
    val contentKind: String,
    val displayName: String,
    val title: String,
    val region: String?,
    val version: String?,
    val serial: String?,
    val titleCatalogKey: String?,
    val identitySource: String,
    val locationKind: String,
    val availability: String,
    val sizeBytes: Long,
    val contentHash: String?,
    val sourceRootUri: String,
    /** The exact per-file SAF `content://` DOCUMENT uri (not the tree root) — needed to open a SAF game on
     *  demand for hashing / metadata enrichment / a content-uri launch. Null for a managed-path file. */
    val sourceDocumentUri: String?,
    val managedPath: String?,
    val relativePath: String,
    val discNumber: Int,
    /** Dedup group this file belongs to (null = ungrouped/singleton). */
    val duplicateGroupId: String?,
    /** Whether this is the surviving/shelf-visible member of its dedup group. */
    val isPrimaryMember: Boolean,
    val duplicateGroupKind: String?,
    /** DAT/id-catalog resolved canonical title (null until the identity enricher runs); drives the tile. */
    val canonicalTitle: String? = null,
    /** Authoritative collapse key (clone-parent set) from the DAT — region variants share it. */
    val collapseKey: String? = null,
    /** Provenance of the resolved identity (a MetadataOrigin name, e.g. NO_INTRO/REDUMP/MAME/TITLE_CATALOG). */
    val identityOrigin: String? = null,
    /** When the enricher last resolved this game (epoch millis); null = never resolved. */
    val identityResolvedAtMillis: Long? = null,
)

/**
 * A platform Mimir knows about. `gamesCount > 0` is the shelf-visibility gate . `catalogLongId` carries the RetroArch catalog id when the
 * platform is RA-served (the catalog long-id IS the launch platform id).
 */
@Entity(tableName = "platforms")
data class PlatformEntity(
    @PrimaryKey val platformId: String,
    val displayName: String,
    val gamesCount: Int,
    val requiresBios: Boolean,
    val catalogLongId: String?,
)

/**
 * A user-designated library source root (SAF tree grant or managed path). "Remove never deletes"
 * : removing a root drops the registry row, not the files.
 */
@Entity(tableName = "source_roots")
data class SourceRootEntity(
    @PrimaryKey val rootId: String,
    /** SAF tree URI or absolute path. */
    val rootUri: String,
    val locationKind: String,
    /** Human-friendly path shown to the user (never a raw UUID/codename). */
    val friendlyLabel: String,
    /** Whether this root is the preferred/primary source in cross-storage collapse (SD wins). */
    val isPrimarySource: Boolean,
    val lastSeenMillis: Long,
)

/**
 * One durable, frozen multi-root reconciliation pass.
 *
 * The row stores the aggregate receipt accumulated by already-settled roots. Root publication remains
 * independently atomic; this ledger makes the ordered pass resumable without pretending the whole
 * library is one transaction.
 */
@Entity(tableName = "source_reconcile_runs")
data class SourceReconcileRunEntity(
    @PrimaryKey val runId: String,
    val policyStamp: String,
    /** Process-incarnation witness for diagnostics/restart detection, never a cross-process lease. */
    val ownerToken: String,
    val startedAtMillis: Long,
    val resumeCount: Int,
    /** Frozen plan size. This stays stable when the live source registry drifts. */
    val sourcesChecked: Int,
    val healthySources: Int,
    val removedSources: Int,
    val unavailableSources: Int,
    val failedSources: Int,
    val quarantinedSources: Int,
    val filesSeen: Int,
    val recognized: Int,
    val groups: Int,
    val skipped: Int,
    val resumedWithDrift: Boolean,
    val driftedSources: Int,
    /**
     * Why the [skipped] files were dropped across this whole pass: `REASON=count` pairs joined by `;`,
     * empty when the pass did not tally reasons.
     *
     * **Stored as text on purpose.** The value is a `Map<String, Int>` whose keys are `SkipReason.name`
     * — reasons and counts only, **never a file name, path or title**, which is what makes it safe to
     * persist and to show. A relational side table would buy queryability nobody needs for a receipt
     * that is written once and read whole.
     *
     * Added v14 (2026-08-30). Defaults to empty so every pre-v14 row reads back as "this pass did not
     * tally reasons", which is true of them.
     */
    val skippedByReason: String = "",
)

/**
 * One root in a frozen reconciliation pass. [ordinal] is captured once at pass creation and is the only
 * execution order after a restart. `PENDING`, `RUNNING`, and `SETTLED` are deliberately distinct:
 * unvisited later roots must never be mistaken for roots interrupted by process death.
 */
@Entity(
    tableName = "source_reconcile_roots",
    primaryKeys = ["runId", "rootId"],
    indices = [
        Index(
            value = ["rootId"],
            name = "index_source_reconcile_roots_rootId",
        ),
    ],
)
data class SourceReconcileRootEntity(
    val runId: String,
    val rootId: String,
    val rootUri: String,
    val ordinal: Int,
    val plannedPrimary: Boolean,
    val phase: String,
    val outcome: String?,
    /** Number of prior non-returning attempts discovered on a later invocation. */
    val attempts: Int,
    /** Equality witness for a settled live registration; null for a confirmed removal. */
    val rootSeenMillis: Long?,
    val settledAtMillis: Long?,
)

/**
 * Persisted hide/visibility state — the persistence shape of `core-model` `HiddenState`. Keyed by
 * `gameId` (hide is a group-level, non-destructive act). `reason` distinguishes a user-dismiss (shows
 * in Browse restore) from a dedup loser (does not).
 */
@Entity(tableName = "hidden_items")
data class HiddenItemEntity(
    @PrimaryKey val gameId: String,
    val hidden: Boolean,
    val reason: String?,
    val duplicateGroupId: String?,
    val isPrimaryMember: Boolean,
    val hiddenAtMillis: Long,
)

/**
 * A user-marked FAVORITE — pure frontend state : favoriting a
 * game never touches the file, the launch, or the shelf placement. Keyed by the GROUP `gameId` (like
 * [HiddenItemEntity] / [EmulatorOverrideEntity]) so it survives collapse/disc-swap and can't collide
 * across source roots. Presence of a row = favorited; the row is deleted to unfavorite.
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val gameId: String,
    val favoritedAtMillis: Long,
)

/**
 * One durable primary Home Shelf membership. Identity is the profile plus the typed namespace and its
 * value, so an emulated game and Android game with the same raw text remain distinct. There is no
 * foreign key: missing ROMs and temporarily uninstalled Android games must retain their rank.
 */
@Entity(
    tableName = "home_shelf_items",
    primaryKeys = ["profileId", "itemKind", "itemId"],
    indices = [
        Index(
            value = ["profileId", "rank"],
            name = "index_home_shelf_items_profile_rank",
        ),
    ],
)
data class HomeShelfItemEntity(
    val profileId: String,
    val itemKind: String,
    val itemId: String,
    val rank: Long,
    val addedAtMillis: Long,
)

/**
 * A per-game "Open with…" override — the persistence shape of the FE `settings.gameLaunchers[gameId]`
 * . Keyed by `gameId` so it survives collapse/disc-swap and does
 * NOT collide across source roots (the collision the FE fixed by appending a root segment).
 */
@Entity(tableName = "emulator_overrides")
data class EmulatorOverrideEntity(
    @PrimaryKey val gameId: String,
    /** The chosen EmulatorId value (package, or package#core for RetroArch). Empty string = explicit Auto reset. */
    val emulatorId: String,
    val setAtMillis: Long,
)

/**
 * A manual, cosmetic display-title correction — rename is presentation only and
 * never feeds recognition (`GameIdentityBuilder` never derives `gameId` from a title). Keyed by `gameId`
 * so it survives collapse/disc-swap exactly like [EmulatorOverrideEntity], its precedent.
 *
 * Deliberately its own table, never a `games.title`/`games.canonicalTitle` write: those columns are
 * overwritten wholesale on every re-scan (`GameDao.upsertAll`) and every re-enrichment/re-scrape
 * (`LibraryIdentityEnricher`), so a value stored there would not survive either — which is exactly the
 * complaint the feature exists to fix. A separate table only either write path never touches is what makes
 * "must never be overwritten by a later scrape, must survive a re-scan" true by construction, not by
 * remembering to special-case it in the scan/enrichment writers.
 */
@Entity(tableName = "title_overrides")
data class TitleOverrideEntity(
    @PrimaryKey val gameId: String,
    val title: String,
    val setAtMillis: Long,
)

/**
 * A durable launch attempt record. Exactly one typed target is populated: [gameId] for an emulated
 * [com.gruning.mimir.rewrite.core.model.LaunchProof], or [androidPackageName] for a native Android-game
 * proof. An emulated failure's lifetime is tied to its `locator` , so a re-placement makes the old failure stale.
 */
@Entity(
    tableName = "launch_proofs",
    indices = [
        Index(value = ["gameId"]),
        Index(value = ["androidPackageName"]),
    ],
)
data class LaunchProofEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String?,
    val androidPackageName: String?,
    val platformId: String,
    val emulatorId: String,
    val locator: String,
    /** "intent_sent" for success, else the LaunchDispatchFailure name. */
    val outcome: String,
    val timestampMillis: Long,
)

/**
 * A BIOS/firmware requirement + presence — the persistence shape of `core-model` `BiosProfile`.
 * Keyed by (platformId, emulatorScopeKey, fileName). [emulatorScopeKey] stores
 * [com.gruning.mimir.rewrite.core.model.EmulatorId.value]; `""` is reserved for migrated legacy
 * rows. [packageName] records provenance only and is not part of the primary key. NEVER a
 * source/download reference; presence-only (posture: no BIOS sourcing). `presence` may honestly be
 * "UNKNOWN" under scoped storage.
 */
@Entity(
    tableName = "bios_profiles",
    primaryKeys = ["platformId", "emulatorScopeKey", "fileName"],
)
data class BiosProfileEntity(
    val platformId: String,
    val emulatorScopeKey: String,
    val packageName: String?,
    val fileName: String,
    val required: Boolean,
    val presence: String,
    val expectedHash: String?,
    val expectedRelativePath: String?,
)

/**
 * Cached title metadata keyed by a catalog title-id / serial. A LOCAL cache: identity + canonical title
 * are resolved offline (bundled `TitleCatalog` / DATs); the enrichment columns ([description], [boxArtUri]
 * …) are filled ONLY when the opt-in online metadata layer is enabled — and always written back here so
 * the app re-reads them offline. [boxArtUri] is a LOCAL uri (the image is downloaded + cached on device),
 * never a remote URL held at render time. [cacheExpiresAt] is REQUIRED for any field from a source with a
 * caching ceiling (e.g. IGDB's 24h); null = no expiry (bundled catalog / No-Intro / Redump / ScreenScraper).
 * **Enforced on read** (the separate steam:store write-side TTL remains unresolved) — [freshOrNull] is the boundary
 * rule, and [com.gruning.mimir.rewrite.core.storage.dao.TitleMetadataCacheDao.freshByCatalogKey] is the
 * one guarded read chokepoint any freshness-sensitive caller must use; see its kdoc for why the raw
 * [com.gruning.mimir.rewrite.core.storage.dao.TitleMetadataCacheDao.byCatalogKey] must stay unguarded. The
 * shelf/gamelist SQL projections in `Daos.kt` are a second read surface this method does not cover — as of
 * R6-P3-01 they enforce the same rule for `description` only via `Daos.kt`'s `FRESH_DESCRIPTION_SQL`
 * (`canonicalTitle`/`descriptionSource` there stay unguarded on purpose; see that constant's kdoc).
 *
 * Metadata ONLY — never content. See [[metadata subsystem plan]].
 */
@Entity(tableName = "title_metadata_cache")
data class TitleMetadataCacheEntity(
    @PrimaryKey val catalogKey: String,
    val platformId: String,
    val canonicalTitle: String,
    val region: String?,
    val version: String?,
    val cachedAtMillis: Long,
    // ── enrichment columns (all NULLABLE → non-destructive ADD COLUMN in Migration 1→2) ──
    val canonicalSerial: String? = null,
    val description: String? = null,
    val boxArtUri: String? = null,      // LOCAL cached-image uri, not a remote URL
    val screenshotUris: String? = null, // CSV/JSON of local uris
    val source: String? = null,         // titlecatalog | nointro | redump | screenscraper:en | thegamesdb | igdb | steam:store | steamgriddb
    val license: String? = null,
    val lastValidatedMillis: Long? = null,
    val cacheExpiresAt: Long? = null,   // required for capped sources (IGDB +24h); null = no expiry
)

/**
 * The single boundary rule for [TitleMetadataCacheEntity.cacheExpiresAt]: `null` MUST continue to mean
 * *never expires* — that is still every OTHER row's case (ScreenScraper/Steam/SteamGridDB never set it);
 * `igdb` is the first source that DOES write a non-null value on every row it persists (an expiry is required when writing an IGDB row). Getting the null case backwards would invalidate every cached
 * row in the product. Only a non-null [TitleMetadataCacheEntity.cacheExpiresAt] can expire a
 * row, and only once [nowMillis] has reached it: `cacheExpiresAt <= nowMillis` reads as expired (the
 * boundary — exactly-equal-to-now counts as expired, the conservative choice for licensed data), returning
 * `null` in place of the row so a caller falls back to treating the game as uncached rather than serving
 * stale licensed data.
 *
 * A pure function of its inputs — no clock read here — so every caller controls its own `nowMillis` and
 * every call site is deterministically testable. See
 * [com.gruning.mimir.rewrite.core.storage.dao.TitleMetadataCacheDao.freshByCatalogKey] for the one
 * production chokepoint that calls this.
 */
fun TitleMetadataCacheEntity.freshOrNull(nowMillis: Long): TitleMetadataCacheEntity? =
    if (cacheExpiresAt != null && cacheExpiresAt <= nowMillis) null else this

/**
 * Stable lineage for a user-perceived game whose current physical winner may change after a rescan.
 *
 * [logicalGameId] is deliberately opaque and never derived from a filename. Existing product surfaces
 * continue to use [currentGameId]; the logical id is the durable spine that lets Room rekey those
 * surfaces without losing the previous winner.
 */
@Entity(
    tableName = "logical_game_groups",
    indices = [
        Index(
            value = ["currentGameId"],
            name = "index_logical_game_groups_currentGameId",
            unique = true,
        ),
    ],
)
data class LogicalGameGroupEntity(
    @PrimaryKey val logicalGameId: String,
    val currentGameId: String,
    val groupKind: String,
    val memberless: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * Historical physical ids belonging to one logical game. Rows are never deleted by scan/prune: a file
 * that returns under a deterministic old id can therefore rejoin its prior logical lineage.
 */
@Entity(
    tableName = "game_id_aliases",
    indices = [
        Index(
            value = ["logicalGameId"],
            name = "index_game_id_aliases_logicalGameId",
        ),
    ],
)
data class GameIdAliasEntity(
    @PrimaryKey val oldGameId: String,
    val logicalGameId: String,
    val firstSeenMillis: Long,
    val retiredAtMillis: Long?,
)

/**
 * Room commits one row with every cross-store identity change. App-owned SharedPreferences/media stores
 * acknowledge their bit only after a durable idempotent replay; [consumedAtMillis] is set after every
 * required bit is present. No user media is deleted by this journal.
 */
@Entity(
    tableName = "remap_journal",
    indices = [
        Index(
            value = ["consumedAtMillis", "journalId"],
            name = "index_remap_journal_consumedAtMillis_journalId",
        ),
    ],
)
data class RemapJournalEntity(
    @PrimaryKey(autoGenerate = true) val journalId: Long = 0L,
    val logicalGameId: String,
    val fromGameId: String,
    val toGameId: String,
    val reason: String,
    val createdAtMillis: Long,
    val storesCompletedMask: Long = 0L,
    val consumedAtMillis: Long? = null,
    val lastError: String? = null,
)

/**
 * Transactional one-shot maintenance markers. A marker is inserted only after its maintenance body
 * succeeds; process death rolls both the body and marker back together.
 */
@Entity(tableName = "identity_maintenance")
data class IdentityMaintenanceEntity(
    @PrimaryKey val maintenanceKey: String,
    val completedAtMillis: Long,
)
