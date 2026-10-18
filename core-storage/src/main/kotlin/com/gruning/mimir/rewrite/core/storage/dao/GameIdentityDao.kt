package com.gruning.mimir.rewrite.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.gruning.mimir.rewrite.core.model.identity.GameIdentityRemapReason
import com.gruning.mimir.rewrite.core.model.identity.logicalGameLaunchRank
import com.gruning.mimir.rewrite.core.storage.entity.EmulatorOverrideEntity
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameIdAliasEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.HomeShelfItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.IdentityMaintenanceEntity
import com.gruning.mimir.rewrite.core.storage.entity.LaunchProofEntity
import com.gruning.mimir.rewrite.core.storage.entity.LogicalGameGroupEntity
import com.gruning.mimir.rewrite.core.storage.entity.RemapJournalEntity
import com.gruning.mimir.rewrite.core.storage.entity.TitleMetadataCacheEntity
import com.gruning.mimir.rewrite.core.storage.entity.TitleOverrideEntity
import com.gruning.mimir.rewrite.core.storage.scan.ExternalStorageDocumentPath
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Every app-owned store currently acknowledged by the v9 replay runner. */
const val REMAP_STORE_HOME_TOMES = 1L shl 0
const val REMAP_STORE_PLAY_TIME = 1L shl 1
const val REMAP_STORE_HOME_SURFACE = 1L shl 2
const val REMAP_STORE_HOME_RETURN = 1L shl 3
const val REMAP_STORE_COMPANION_RETURN = 1L shl 4
const val REMAP_STORE_CUSTOM_HERO = 1L shl 5
const val REMAP_STORE_ACTIVE_ART = 1L shl 6
const val REMAP_STORE_SAVED_ART = 1L shl 7
const val REMAP_STORE_ART_RESOLUTION = 1L shl 8
const val REMAP_STORE_ART_NO_MATCH = 1L shl 9
const val REMAP_STORE_TWEAKER_SELECTION = 1L shl 10
const val REMAP_ALL_STORES_MASK =
    REMAP_STORE_HOME_TOMES or
        REMAP_STORE_PLAY_TIME or
        REMAP_STORE_HOME_SURFACE or
        REMAP_STORE_HOME_RETURN or
        REMAP_STORE_COMPANION_RETURN or
        REMAP_STORE_CUSTOM_HERO or
        REMAP_STORE_ACTIVE_ART or
        REMAP_STORE_SAVED_ART or
        REMAP_STORE_ART_RESOLUTION or
        REMAP_STORE_ART_NO_MATCH or
        REMAP_STORE_TWEAKER_SELECTION

/** Narrow row used to reconstruct historical physical-group components without recursive SQL. */
data class GroupedIdentityLink(
    val entryKey: String,
    val sourceRootUri: String,
    val relativePath: String,
    val gameId: String,
    val duplicateGroupId: String?,
    val duplicateGroupKind: String?,
    val isPrimaryMember: Boolean,
    val locationKind: String,
    val availability: String,
    val contentKind: String,
    val discNumber: Int,
)

/** A live physical candidate for one logical group's current winner. */
data class LogicalWinnerCandidate(
    val gameId: String,
    val entryKey: String,
    val sourceRootUri: String,
    val relativePath: String,
    val locationKind: String,
    val isPrimaryMember: Boolean,
    val availability: String,
    val contentKind: String,
    val discNumber: Int,
)

data class CurrentGameAlias(
    val oldGameId: String,
    val currentGameId: String,
)

/** Legacy v8 ScreenScraper key still attached to a live physical game row. */
data class PhysicalGameMetadataKey(
    val gameId: String,
    val titleCatalogKey: String,
)

/** Result returned to scan/enrichment orchestration without exposing Room entities. */
data class LogicalGroupMutation(
    val logicalGameId: String,
    val previousGameId: String?,
    val currentGameId: String?,
    val journalRowsQueued: Int,
)

data class RootAvailabilityMutation(
    val markedUnavailable: Int,
    val restoredAvailable: Int,
)

/** The game id one indexed file carries and whether it is its group's shelf tile; read before and after a rescan. */
data class EntryGameIdentity(
    val entryKey: String,
    val gameId: String,
    val isPrimaryMember: Boolean,
)

/** The two columns a launch names a file by; [launchLocatorsOf] turns them into every `locator` a launch can record. */
data class GameLaunchLocators(
    val managedPath: String?,
    val sourceDocumentUri: String?,
)

/**
 * Every `launch_proofs.locator` that launching these files can have recorded, so a launch can be matched back to the file it
 * named. The recorded locator is whatever core-launch's `launchLocatorFrom` handed the emulator, which is not always a column:
 *
 *  - the managed path (a file the scan indexed by path);
 *  - the `content://` document uri (a `CONTENT_URI` seed, and `EITHER` when there is no path);
 *  - the `/storage/...` path that document uri names. A file indexed through a tree grant has `managedPath = null`, and a
 *    `FILE_PATH`/`EITHER` emulator (the PS1/PS2/GameCube/RetroArch class) is handed that derived path, which is a column of
 *    neither. It comes from [ExternalStorageDocumentPath], the same function `launchLocatorFrom` calls.
 *
 * **Not included: the generated multi-disc `.m3u`.** It is `<Documents/Mimir/Playlists>/<sha256(gameId)>.m3u`, built from the
 * id the launch was made under (the winner's, for a merged game) in a directory only the launcher knows, and it names every
 * disc of the game at once. It cannot be derived from one file, and no single split-off file can claim it, so it stays with
 * the id that kept the original, like any other launch nothing can place. In the batch carry path, a holder that stops
 * existing hands over all of its launches whole. In the quarantine split with no state keeper, they stay at the old id.
 */
private fun launchLocatorsOf(rows: List<GameLaunchLocators>): List<String> = rows.flatMap { row ->
    val documentUri = row.sourceDocumentUri?.takeIf { it.startsWith("content://") }
    listOfNotNull(
        row.managedPath?.takeIf(String::isNotBlank),
        documentUri,
        documentUri?.let(ExternalStorageDocumentPath::pathOf),
    )
}

/** One file a rescan re-identified: the same `entryKey`, a different content-derived game id. */
private data class ReidentifiedEntry(
    val entryKey: String,
    val oldGameId: String,
    val newGameId: String,
    val wasPrimaryMember: Boolean,
)

private val WINNER_COMPARATOR =
    compareBy<LogicalWinnerCandidate>(
        { if (it.availability == "AVAILABLE") 0 else 1 },
        { if (it.isPrimaryMember) 0 else 1 },
        { logicalGameLaunchRank(it.contentKind, it.discNumber) },
        { if (it.locationKind == "SAF_TREE_GRANT") 0 else 1 },
        LogicalWinnerCandidate::gameId,
        LogicalWinnerCandidate::sourceRootUri,
        LogicalWinnerCandidate::relativePath,
        LogicalWinnerCandidate::entryKey,
    )

private const val GAME_OWNED_BY_EXACT_ROOT_SQL =
    "g.sourceRootUri = :rootUri"

private const val ROW_OWNED_BY_EXACT_ROOT_SQL =
    "sourceRootUri = :rootUri"

private const val A1C_OPTICAL_PLATFORM_IDS_SQL =
    "'3do', 'atari_jaguar_cd', 'commodore_cd32', 'commodore_cdtv', 'dreamcast', " +
        "'gamecube', 'nec_pc_engine_cd_turbografx_cd', 'nec_pc_fx', 'nintendo_wii', " +
        "'nintendo_wii_u', 'pcenginecd', 'philips_cd_i', 'ps1', 'ps2', 'ps3', 'psp', " +
        "'saturn', 'segacd', 'sega_mega_cd_sega_cd', 'snk_neo_geo_cd', " +
        "'the_3do_company_3do', 'wii', 'xbox', 'xbox360'"

/** Alias is deliberately fixed to `affected` so the same predicate grounds both replay queries. */
private const val A1C_AFFECTED_ROW_SQL =
    "(affected.platformId IN ('arcade', 'switch') OR " +
        "(LOWER(affected.relativePath) LIKE '%.chd' OR LOWER(affected.displayName) LIKE '%.chd') OR " +
        "((LOWER(affected.relativePath) LIKE '%.zip' OR LOWER(affected.displayName) LIKE '%.zip') AND " +
        "affected.platformId IN ($A1C_OPTICAL_PLATFORM_IDS_SQL)))"

private val CATALOG_PAREN_OR_BRACKET = Regex("\\s*[(\\[][^)\\]]*[)\\]]")

/** Same guard used by the metadata planner, repeated at the transactional mutation boundary. */
internal fun normalizedCatalogBaseTitle(title: String): String =
    title.replace(CATALOG_PAREN_OR_BRACKET, "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()

/** Portable undirected components for every persisted collapsed group, including multi-disc groups. */
internal fun groupedIdentityComponents(links: List<GroupedIdentityLink>): List<List<String>> {
    val adjacency = linkedMapOf<String, MutableSet<String>>()
    links.forEach { link ->
        adjacency.getOrPut(link.gameId) { linkedSetOf() }
        link.duplicateGroupId
            ?.takeIf { it.isNotBlank() && it != link.gameId }
            ?.let { parent ->
                adjacency.getOrPut(link.gameId) { linkedSetOf() }.add(parent)
                adjacency.getOrPut(parent) { linkedSetOf() }.add(link.gameId)
            }
    }
    val visited = hashSetOf<String>()
    return adjacency.keys.sorted().mapNotNull { seed ->
        if (!visited.add(seed)) return@mapNotNull null
        val pending = ArrayDeque<String>().apply { add(seed) }
        val component = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            component += current
            adjacency[current].orEmpty().sorted().forEach { adjacent ->
                if (visited.add(adjacent)) pending.addLast(adjacent)
            }
        }
        component.sorted().takeIf { it.size > 1 }
    }
}

internal fun chooseLogicalWinner(
    links: List<GroupedIdentityLink>,
    component: Set<String>,
): String {
    val candidates = links.asSequence()
        .filter { it.gameId in component }
        .map {
            LogicalWinnerCandidate(
                gameId = it.gameId,
                entryKey = it.entryKey,
                sourceRootUri = it.sourceRootUri,
                relativePath = it.relativePath,
                locationKind = it.locationKind,
                isPrimaryMember = it.isPrimaryMember &&
                    (it.duplicateGroupId == null || it.duplicateGroupId == it.gameId),
                availability = it.availability,
                contentKind = it.contentKind,
                discNumber = it.discNumber,
            )
        }
        .sortedWith(WINNER_COMPARATOR)
        .toList()
    return candidates.firstOrNull()?.gameId ?: component.min()
}

internal suspend fun applyRootAvailabilitySnapshot(
    absentRootUris: List<String>,
    presentRootUris: List<String>,
    logicalIdsForRoots: suspend (List<String>) -> List<String>,
    markUnavailable: suspend (List<String>) -> Int,
    restoreAvailable: suspend (List<String>) -> Int,
    reconcile: suspend (List<String>) -> Unit,
): RootAvailabilityMutation {
    val present = presentRootUris.filter(String::isNotBlank).distinct()
    val absent = absentRootUris.filter(String::isNotBlank).distinct().filterNot(present::contains)
    val touched = (absent + present).distinct()
    if (touched.isEmpty()) return RootAvailabilityMutation(0, 0)
    val logicalIds = touched.chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
        .flatMap { logicalIdsForRoots(it) }
        .distinct()
    val markedUnavailable = absent.chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
        .sumOf { markUnavailable(it) }
    val restoredAvailable = present.chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
        .sumOf { restoreAvailable(it) }
    if (markedUnavailable > 0 || restoredAvailable > 0) reconcile(logicalIds)
    return RootAvailabilityMutation(markedUnavailable, restoredAvailable)
}

@Dao
abstract class GameIdentityDao {

    @Query(
        "SELECT entryKey, sourceRootUri, relativePath, gameId, duplicateGroupId, " +
            "duplicateGroupKind, isPrimaryMember, locationKind, " +
            "availability, contentKind, discNumber " +
            "FROM games WHERE duplicateGroupId IS NOT NULL OR duplicateGroupKind IS NOT NULL",
    )
    protected abstract suspend fun groupedIdentityLinks(): List<GroupedIdentityLink>

    @Query("SELECT * FROM game_id_aliases WHERE oldGameId IN (:gameIds)")
    protected abstract suspend fun aliasesForGameIdsBatch(gameIds: List<String>): List<GameIdAliasEntity>

    @Query("SELECT * FROM game_id_aliases WHERE logicalGameId IN (:logicalGameIds)")
    protected abstract suspend fun aliasesForLogicalIdsBatch(
        logicalGameIds: List<String>,
    ): List<GameIdAliasEntity>

    @Query("SELECT * FROM logical_game_groups WHERE logicalGameId IN (:logicalGameIds)")
    protected abstract suspend fun groupsByIdsBatch(logicalGameIds: List<String>): List<LogicalGameGroupEntity>

    @Query("SELECT * FROM logical_game_groups WHERE logicalGameId = :logicalGameId LIMIT 1")
    protected abstract suspend fun groupById(logicalGameId: String): LogicalGameGroupEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertGroup(group: LogicalGameGroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertAlias(alias: GameIdAliasEntity)

    @Query(
        "UPDATE game_id_aliases SET logicalGameId = :targetLogicalGameId " +
            "WHERE logicalGameId = :sourceLogicalGameId",
    )
    protected abstract suspend fun repointAliases(
        sourceLogicalGameId: String,
        targetLogicalGameId: String,
    ): Int

    @Query("DELETE FROM logical_game_groups WHERE logicalGameId = :logicalGameId")
    protected abstract suspend fun deleteGroup(logicalGameId: String): Int

    @Query(
        "UPDATE logical_game_groups SET currentGameId = :currentGameId, groupKind = :groupKind, " +
            "memberless = :memberless, updatedAtMillis = :updatedAtMillis " +
            "WHERE logicalGameId = :logicalGameId",
    )
    protected abstract suspend fun updateGroup(
        logicalGameId: String,
        currentGameId: String,
        groupKind: String,
        memberless: Boolean,
        updatedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE game_id_aliases SET retiredAtMillis = CASE WHEN oldGameId = :currentGameId " +
            "THEN NULL ELSE COALESCE(retiredAtMillis, :retiredAtMillis) END " +
            "WHERE logicalGameId = :logicalGameId",
    )
    protected abstract suspend fun markAliasWinner(
        logicalGameId: String,
        currentGameId: String,
        retiredAtMillis: Long,
    ): Int

    @Query("SELECT * FROM favorites WHERE gameId IN (:gameIds)")
    protected abstract suspend fun favoritesByIdsBatch(gameIds: List<String>): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE gameId IN (:gameIds)")
    protected abstract suspend fun deleteFavoritesBatch(gameIds: List<String>): Int

    @Query("SELECT * FROM hidden_items WHERE gameId IN (:gameIds)")
    protected abstract suspend fun hiddenByIdsBatch(gameIds: List<String>): List<HiddenItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertHidden(hidden: HiddenItemEntity)

    @Query("DELETE FROM hidden_items WHERE gameId IN (:gameIds)")
    protected abstract suspend fun deleteHiddenBatch(gameIds: List<String>): Int

    @Query("SELECT * FROM emulator_overrides WHERE gameId IN (:gameIds)")
    protected abstract suspend fun overridesByIdsBatch(gameIds: List<String>): List<EmulatorOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertOverride(override: EmulatorOverrideEntity)

    @Query("DELETE FROM emulator_overrides WHERE gameId IN (:gameIds)")
    protected abstract suspend fun deleteOverridesBatch(gameIds: List<String>): Int

    @Query("SELECT * FROM title_overrides WHERE gameId IN (:gameIds)")
    protected abstract suspend fun titleOverridesByIdsBatch(gameIds: List<String>): List<TitleOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertTitleOverride(override: TitleOverrideEntity)

    @Query("DELETE FROM title_overrides WHERE gameId IN (:gameIds)")
    protected abstract suspend fun deleteTitleOverridesBatch(gameIds: List<String>): Int

    @Query(
        "SELECT * FROM home_shelf_items WHERE itemKind = 'EMULATED_GAME' AND itemId IN (:gameIds)",
    )
    protected abstract suspend fun homeItemsByIdsBatch(gameIds: List<String>): List<HomeShelfItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertHomeItem(item: HomeShelfItemEntity)

    @Query(
        "DELETE FROM home_shelf_items WHERE itemKind = 'EMULATED_GAME' AND itemId IN (:gameIds)",
    )
    protected abstract suspend fun deleteHomeItemsBatch(gameIds: List<String>): Int

    @Query("UPDATE launch_proofs SET gameId = :toGameId WHERE gameId = :fromGameId")
    protected abstract suspend fun rekeyLaunchProofs(fromGameId: String, toGameId: String): Int

    /**
     * Moves only the launches that named one of [locators] (see [launchLocatorsOf]: a file path, a SAF document uri, or the
     * path that uri names), leaving every other launch of [fromGameId] where it is. The recorded locator is what lets a merged
     * game's history be told apart again.
     */
    @Query(
        "UPDATE launch_proofs SET gameId = :toGameId WHERE gameId = :fromGameId AND locator IN (:locators)",
    )
    protected abstract suspend fun rekeyLaunchProofsByLocatorBatch(
        fromGameId: String,
        toGameId: String,
        locators: List<String>,
    ): Int

    /** Every file's game id under one source root. The scan reads it before it writes and again afterwards. */
    @Query("SELECT entryKey, gameId, isPrimaryMember FROM games WHERE sourceRootUri = :rootUri")
    abstract suspend fun entryGameIdentitiesForRoot(rootUri: String): List<EntryGameIdentity>

    @Query("SELECT managedPath, sourceDocumentUri FROM games WHERE entryKey IN (:entryKeys)")
    protected abstract suspend fun launchLocatorsForEntriesBatch(entryKeys: List<String>): List<GameLaunchLocators>

    @Query("SELECT managedPath, sourceDocumentUri FROM games WHERE gameId = :gameId")
    protected abstract suspend fun launchLocatorsForGame(gameId: String): List<GameLaunchLocators>

    @Insert
    protected abstract suspend fun insertLaunchProof(proof: LaunchProofEntity): Long

    @Query("SELECT * FROM title_metadata_cache WHERE catalogKey IN (:catalogKeys)")
    protected abstract suspend fun metadataByKeysBatch(
        catalogKeys: List<String>,
    ): List<TitleMetadataCacheEntity>

    @Query(
        "SELECT gameId, titleCatalogKey FROM games WHERE gameId IN (:gameIds) " +
            "AND titleCatalogKey IS NOT NULL AND TRIM(titleCatalogKey) != ''",
    )
    protected abstract suspend fun metadataKeysForGameIdsBatch(
        gameIds: List<String>,
    ): List<PhysicalGameMetadataKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertMetadata(metadata: TitleMetadataCacheEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertJournal(journal: RemapJournalEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM identity_maintenance WHERE maintenanceKey = :key)")
    protected abstract suspend fun maintenanceCompleted(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMaintenance(marker: IdentityMaintenanceEntity): Long

    @Query(
        "SELECT * FROM remap_journal WHERE consumedAtMillis IS NULL ORDER BY journalId ASC LIMIT :limit",
    )
    abstract suspend fun pendingJournal(limit: Int = 64): List<RemapJournalEntity>

    @Query(
        "SELECT * FROM remap_journal WHERE consumedAtMillis IS NULL " +
            "AND (storesCompletedMask & :requiredMask) != :requiredMask ORDER BY journalId ASC",
    )
    abstract suspend fun pendingJournalMissingMask(requiredMask: Long): List<RemapJournalEntity>

    @Query("SELECT COUNT(*) FROM remap_journal WHERE consumedAtMillis IS NULL")
    abstract fun observePendingJournalCount(): Flow<Int>

    @Query(
        "UPDATE remap_journal SET storesCompletedMask = storesCompletedMask | :storeBit, lastError = NULL " +
            "WHERE journalId = :journalId AND consumedAtMillis IS NULL",
    )
    abstract suspend fun acknowledgeJournalStore(journalId: Long, storeBit: Long): Int

    @Query(
        "UPDATE remap_journal SET storesCompletedMask = storesCompletedMask | :storeBit, lastError = NULL " +
            "WHERE journalId IN (:journalIds) AND consumedAtMillis IS NULL",
    )
    protected abstract suspend fun acknowledgeJournalStoreBatch(
        journalIds: List<Long>,
        storeBit: Long,
    ): Int

    open suspend fun acknowledgeJournalStoreRows(journalIds: List<Long>, storeBit: Long): Int =
        journalIds.distinct().chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
            .sumOf { acknowledgeJournalStoreBatch(it, storeBit) }

    @Query(
        "UPDATE remap_journal SET lastError = :error " +
            "WHERE journalId = :journalId AND consumedAtMillis IS NULL",
    )
    abstract suspend fun noteJournalFailure(journalId: Long, error: String): Int

    @Query(
        "UPDATE remap_journal SET consumedAtMillis = :consumedAtMillis, lastError = NULL " +
            "WHERE journalId = :journalId AND consumedAtMillis IS NULL " +
            "AND (storesCompletedMask & :requiredMask) = :requiredMask",
    )
    abstract suspend fun consumeJournal(
        journalId: Long,
        requiredMask: Long,
        consumedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE remap_journal SET consumedAtMillis = :consumedAtMillis, lastError = :reason " +
            "WHERE journalId = :journalId AND consumedAtMillis IS NULL",
    )
    abstract suspend fun voidJournal(journalId: Long, consumedAtMillis: Long, reason: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM games WHERE gameId = :gameId)")
    abstract suspend fun isPhysicalGameIdLive(gameId: String): Boolean

    @Query(
        "SELECT COALESCE((" +
            "SELECT lg.currentGameId FROM game_id_aliases a " +
            "JOIN logical_game_groups lg ON lg.logicalGameId = a.logicalGameId " +
            "WHERE a.oldGameId = :gameId LIMIT 1" +
            "), :gameId)",
    )
    abstract suspend fun resolveCurrentGameId(gameId: String): String

    @Query(
        "SELECT a.oldGameId, lg.currentGameId FROM game_id_aliases a " +
            "JOIN logical_game_groups lg ON lg.logicalGameId = a.logicalGameId " +
            "WHERE a.oldGameId != lg.currentGameId",
    )
    abstract suspend fun currentGameAliases(): List<CurrentGameAlias>

    @Query(
        "SELECT a.oldGameId, lg.currentGameId FROM game_id_aliases a " +
            "JOIN logical_game_groups lg ON lg.logicalGameId = a.logicalGameId",
    )
    abstract fun observeCurrentGameAliases(): Flow<List<CurrentGameAlias>>

    @Query(
        "SELECT a.oldGameId, lg.currentGameId FROM game_id_aliases a " +
            "JOIN logical_game_groups lg ON lg.logicalGameId = a.logicalGameId " +
            "WHERE a.oldGameId IN (:gameIds)",
    )
    protected abstract suspend fun currentGameAliasesBatch(
        gameIds: List<String>,
    ): List<CurrentGameAlias>

    @Transaction
    open suspend fun resolveCurrentGameIds(gameIds: List<String>): Map<String, String> {
        val distinct = gameIds.filter(String::isNotBlank).distinct()
        val aliases = distinct.chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
            .flatMap { currentGameAliasesBatch(it) }
            .associate { it.oldGameId to it.currentGameId }
        return distinct.associateWith { aliases[it] ?: it }
    }

    @Transaction
    open suspend fun emulatorOverrideForCurrent(gameId: String): EmulatorOverrideEntity? {
        val currentGameId = resolveCurrentGameId(gameId)
        return overridesByIdsBatch(listOf(currentGameId)).firstOrNull()
    }

    @Transaction
    open suspend fun setEmulatorOverrideForCurrent(
        gameId: String,
        emulatorId: String,
        setAtMillis: Long,
    ) {
        upsertOverride(
            EmulatorOverrideEntity(
                gameId = resolveCurrentGameId(gameId),
                emulatorId = emulatorId,
                setAtMillis = setAtMillis,
            ),
        )
    }

    @Transaction
    open suspend fun clearEmulatorOverrideForCurrent(gameId: String): Int =
        deleteOverridesBatch(listOf(resolveCurrentGameId(gameId)))

    /**
     * Read the manual title override for whatever game [gameId] is CURRENTLY known as (bridging a stale
     * caller-held id through the alias chain), never a stale copy — mirrors [emulatorOverrideForCurrent].
     */
    @Transaction
    open suspend fun titleOverrideForCurrent(gameId: String): TitleOverrideEntity? {
        val currentGameId = resolveCurrentGameId(gameId)
        return titleOverridesByIdsBatch(listOf(currentGameId)).firstOrNull()
    }

    /** Set/replace the manual title override at the CURRENT game id — mirrors [setEmulatorOverrideForCurrent]. */
    @Transaction
    open suspend fun setTitleOverrideForCurrent(
        gameId: String,
        title: String,
        setAtMillis: Long,
    ) {
        upsertTitleOverride(
            TitleOverrideEntity(
                gameId = resolveCurrentGameId(gameId),
                title = title,
                setAtMillis = setAtMillis,
            ),
        )
    }

    /** "Reset to detected name" — mirrors [clearEmulatorOverrideForCurrent]. */
    @Transaction
    open suspend fun clearTitleOverrideForCurrent(gameId: String): Int =
        deleteTitleOverridesBatch(listOf(resolveCurrentGameId(gameId)))

    /** Final proof-write boundary: alias resolution and insert share the same Room transaction. */
    @Transaction
    open suspend fun recordLaunchProofForCurrent(proof: LaunchProofEntity): Long {
        val currentGameId = proof.gameId?.let { resolveCurrentGameId(it) }
        return insertLaunchProof(proof.copy(gameId = currentGameId))
    }

    private suspend fun rekeyLaunchProofsByLocators(
        fromGameId: String,
        toGameId: String,
        locators: Collection<String>,
    ): Int {
        if (fromGameId == toGameId) return 0
        return locators.filter(String::isNotBlank).distinct()
            .chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
            .sumOf { rekeyLaunchProofsByLocatorBatch(fromGameId, toGameId, it) }
    }

    private suspend fun launchLocatorsForEntries(entryKeys: List<String>): List<String> =
        entryKeys.distinct().chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
            .flatMap { launchLocatorsForEntriesBatch(it) }
            .let(::launchLocatorsOf)

    /**
     * **A rescan that gives the same file a different content-derived id keeps that game's launch history.**
     *
     * A scan upserts by `entryKey`, so a file the scan now names differently has its `gameId` overwritten in place and the
     * old id simply stops existing. Only a group has a logical lineage to follow ([reconcileScannedGroup] needs two
     * physical ids), so a game alone under its id left its `launch_proofs` behind at an id nothing reads: first played,
     * last played and the count all started again from nothing. [before] is [entryGameIdentitiesForRoot] read before the
     * scan wrote; this reads it again, and for every entry whose id changed:
     *
     *  - History lives at the id the old one now resolves to (the old id itself when it was never in a group).
     *  - If that holder still exists (another file keeps it), it keeps its history; only the launches that named a moved
     *    file go with that file.
     *  - If it no longer exists, its history goes to the new id of the file that was the group's shelf tile (else the
     *    first file by key), the id that carries on the original. When the files went to several new ids, a launch that
     *    named a file goes to that file's new id and every launch that can't be told apart goes to the carrying one.
     *
     * Launch history only: it moves no other per-game state and sets no physical group flag (that is
     * [reconcileScannedGroup], which stamps a group kind a single game must not wear). Nothing moves for a file whose id did
     * not change, and the second rescan finds nothing changed, so repeating it is a no-op. Run it after
     * [reconcileCurrentWinners], so a group whose other member took over is judged by where its history now is.
     *
     * @return the launch rows moved.
     */
    @Transaction
    open suspend fun carryLaunchHistoryAcrossReidentification(
        rootUri: String,
        before: List<EntryGameIdentity>,
    ): Int {
        if (before.isEmpty()) return 0
        val after = entryGameIdentitiesForRoot(rootUri).associateBy(EntryGameIdentity::entryKey)
        val moved = before.mapNotNull { was ->
            // A pruned file is not a re-identified one, and a new file has no history to carry.
            val now = after[was.entryKey] ?: return@mapNotNull null
            if (now.gameId == was.gameId) {
                null
            } else {
                ReidentifiedEntry(was.entryKey, was.gameId, now.gameId, was.isPrimaryMember)
            }
        }
        if (moved.isEmpty()) return 0

        val currentIds = moved.flatMap { listOf(it.oldGameId, it.newGameId) }.distinct()
            .chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
            .flatMap { currentGameAliasesBatch(it) }
            .associate { it.oldGameId to it.currentGameId }
        fun current(gameId: String): String = currentIds[gameId] ?: gameId

        var carried = 0
        moved.groupBy { current(it.oldGameId) }.toSortedMap().forEach { (holder, entries) ->
            val departures = entries.filter { current(it.newGameId) != holder }
                .groupBy { current(it.newGameId) }
                .toSortedMap()
            if (departures.isEmpty()) return@forEach
            val heir = if (physicalGameRowCount(holder) > 0) {
                null
            } else {
                current(
                    departures.values.flatten()
                        .minWith(compareBy<ReidentifiedEntry>({ !it.wasPrimaryMember }, { it.entryKey }))
                        .newGameId,
                )
            }
            departures.forEach { (destination, parts) ->
                if (destination == heir) return@forEach
                carried += rekeyLaunchProofsByLocators(
                    holder,
                    destination,
                    launchLocatorsForEntries(parts.map(ReidentifiedEntry::entryKey)),
                )
            }
            if (heir != null) carried += rekeyLaunchProofs(holder, heir)
        }
        return carried
    }

    @Query(
        "SELECT DISTINCT a.logicalGameId FROM game_id_aliases a " +
            "JOIN games g ON g.gameId = a.oldGameId WHERE $GAME_OWNED_BY_EXACT_ROOT_SQL",
    )
    abstract suspend fun logicalGameIdsTouchingRoot(rootUri: String): List<String>

    protected open suspend fun logicalGameIdsTouchingRootsBatch(
        rootUris: List<String>,
    ): List<String> {
        val logicalIds = linkedSetOf<String>()
        rootUris.filter(String::isNotBlank).distinct().forEach { rootUri ->
            logicalIds += logicalGameIdsTouchingRoot(rootUri)
        }
        return logicalIds.toList()
    }

    @Query(
        "UPDATE games SET availability = 'STORAGE_UNAVAILABLE' " +
            "WHERE $ROW_OWNED_BY_EXACT_ROOT_SQL AND availability = 'AVAILABLE'",
    )
    protected abstract suspend fun markRootStorageUnavailable(rootUri: String): Int

    @Query(
        "UPDATE games SET availability = 'AVAILABLE' " +
            "WHERE $ROW_OWNED_BY_EXACT_ROOT_SQL AND availability = 'STORAGE_UNAVAILABLE'",
    )
    protected abstract suspend fun restoreRootStorageAvailability(rootUri: String): Int

    protected open suspend fun markRootsStorageUnavailable(rootUris: List<String>): Int {
        var changed = 0
        rootUris.filter(String::isNotBlank).distinct().forEach { rootUri ->
            changed += markRootStorageUnavailable(rootUri)
        }
        return changed
    }

    protected open suspend fun restoreRootsStorageAvailability(rootUris: List<String>): Int {
        var changed = 0
        rootUris.filter(String::isNotBlank).distinct().forEach { rootUri ->
            changed += restoreRootStorageAvailability(rootUri)
        }
        return changed
    }

    @Query("SELECT DISTINCT logicalGameId FROM game_id_aliases WHERE oldGameId IN (:gameIds)")
    protected abstract suspend fun logicalGameIdsForPhysicalIdsBatch(
        gameIds: List<String>,
    ): List<String>

    @Query(
        "SELECT g.gameId, g.entryKey, g.sourceRootUri, g.relativePath, g.locationKind, " +
            "CASE WHEN g.isPrimaryMember = 1 AND " +
            "(g.duplicateGroupId IS NULL OR g.duplicateGroupId = g.gameId) THEN 1 ELSE 0 END " +
            "AS isPrimaryMember, g.availability, g.contentKind, g.discNumber FROM games g " +
            "JOIN game_id_aliases a ON a.oldGameId = g.gameId " +
            "WHERE a.logicalGameId = :logicalGameId",
    )
    protected abstract suspend fun winnerCandidates(
        logicalGameId: String,
    ): List<LogicalWinnerCandidate>

    @Query("SELECT COUNT(*) FROM games WHERE gameId = :gameId")
    protected abstract suspend fun physicalGameRowCount(gameId: String): Int

    @Query(EXACT_HASH_CANDIDATES_SQL)
    protected abstract suspend fun exactHashCandidates(): List<GameEntity>

    @Query(
        "SELECT * FROM games WHERE gameId IN (:gameIds) AND isPrimaryMember = 1 " +
            "AND platformId = :platformId AND collapseKey = :collapseKey " +
            "AND (duplicateGroupKind IS NULL OR duplicateGroupKind != 'EXACT_HASH')",
    )
    protected abstract suspend fun liveCatalogFoldCandidatesBatch(
        gameIds: List<String>,
        platformId: String,
        collapseKey: String,
    ): List<GameEntity>

    @Query(
        "SELECT DISTINCT a.logicalGameId FROM game_id_aliases a " +
            "JOIN logical_game_groups lg ON lg.logicalGameId = a.logicalGameId " +
            "JOIN games affected ON affected.gameId = a.oldGameId " +
            "WHERE lg.groupKind = 'CATALOG_TITLE_ID' AND $A1C_AFFECTED_ROW_SQL",
    )
    protected abstract suspend fun a1cAffectedCatalogLogicalGroupIds(): List<String>

    @Query(
        "SELECT DISTINCT g.gameId FROM games g " +
            "JOIN game_id_aliases a ON a.oldGameId = g.gameId " +
            "WHERE a.logicalGameId = :logicalGameId ORDER BY g.gameId",
    )
    protected abstract suspend fun liveGameIdsForLogicalGroup(logicalGameId: String): List<String>

    @Query(
        "UPDATE games SET isPrimaryMember = 1, duplicateGroupId = NULL, duplicateGroupKind = NULL " +
            "WHERE gameId = :gameId AND duplicateGroupKind = 'CATALOG_TITLE_ID'",
    )
    protected abstract suspend fun restoreCatalogFoldVisibility(gameId: String): Int

    /**
     * Clear the complete physical/logical closure, not only the path-shaped row that exposed the new
     * reader. This reopens a `.m3u` primary when its `.chd` child was affected and every live physical id
     * in a historical catalog fold before that fold is quarantined below.
     */
    @Query(
        "UPDATE games SET canonicalTitle = NULL, collapseKey = NULL, identityOrigin = NULL, " +
            "identityResolvedAtMillis = NULL WHERE identityResolvedAtMillis IS NOT NULL AND gameId IN (" +
            "SELECT affected.gameId FROM games affected WHERE $A1C_AFFECTED_ROW_SQL " +
            "UNION SELECT closureAlias.oldGameId FROM game_id_aliases closureAlias " +
            "WHERE closureAlias.logicalGameId IN (" +
            "SELECT DISTINCT seed.logicalGameId FROM game_id_aliases seed " +
            "JOIN games affected ON affected.gameId = seed.oldGameId " +
            "WHERE $A1C_AFFECTED_ROW_SQL))",
    )
    protected abstract suspend fun clearA1CContainerIdentityClosure(): Int

    @Query(
        "SELECT entryKey FROM games WHERE gameId = :winnerGameId " +
            "ORDER BY CASE WHEN availability = 'AVAILABLE' THEN 0 ELSE 1 END, " +
            "CASE WHEN contentKind = 'MULTI_DISC_PLAYLIST' THEN 0 " +
            "WHEN contentKind = 'TRACK_MANIFEST' THEN 1 WHEN discNumber > 0 THEN 2 ELSE 3 END, " +
            "CASE WHEN discNumber > 0 THEN discNumber ELSE 0 END ASC, " +
            "isPrimaryMember DESC, CASE WHEN locationKind = 'SAF_TREE_GRANT' THEN 0 ELSE 1 END, " +
            "sourceRootUri ASC, relativePath ASC, entryKey ASC LIMIT 1",
    )
    protected abstract suspend fun preferredWinnerEntryKey(winnerGameId: String): String?

    @Query(
        "UPDATE games SET " +
            "isPrimaryMember = CASE WHEN entryKey = :primaryEntryKey THEN 1 ELSE 0 END, " +
            "duplicateGroupId = CASE WHEN entryKey = :primaryEntryKey THEN NULL ELSE :winnerGameId END " +
            "WHERE gameId = :winnerGameId AND (" +
            "(entryKey = :primaryEntryKey AND (isPrimaryMember != 1 OR duplicateGroupId IS NOT NULL)) OR " +
            "(entryKey != :primaryEntryKey AND (isPrimaryMember != 0 OR duplicateGroupId IS NULL " +
            "OR duplicateGroupId != :winnerGameId)))",
    )
    protected abstract suspend fun normalizeCurrentWinnerRows(
        winnerGameId: String,
        primaryEntryKey: String,
    ): Int

    /**
     * Exactly one physical row represents the current id. Availability outranks the previous primary;
     * this matters when the GameId itself stays stable but its cross-root physical row changes.
     */
    protected open suspend fun ensureCurrentWinnerVisible(winnerGameId: String): Int {
        val entryKey = preferredWinnerEntryKey(winnerGameId) ?: return 0
        return normalizeCurrentWinnerRows(winnerGameId, entryKey)
    }

    /**
     * A healthy rescan can temporarily dissolve a terminal group and make a historical alias primary
     * again. Normalize the current id first, then fold every other aliased physical primary under it.
     * Only primary rows are touched: nested disc/playlist members keep their own parent facts and remain
     * reachable through the alias-aware member closure.
     */
    protected open suspend fun normalizeLogicalGroupVisibility(
        logicalGameId: String,
        winnerGameId: String,
        groupKind: String,
    ): Int {
        var changed = ensureCurrentWinnerVisible(winnerGameId)
        // A scan upsert restores the winner's local subgroup facts before this logical reconciliation
        // runs. Restamp the one normalized physical primary with the enclosing lineage's strongest
        // evidence; nested non-primary disc rows deliberately retain MULTI_DISC below it.
        changed += stampPhysicalWinner(winnerGameId, groupKind)
        aliasesForLogicalIdsBatch(listOf(logicalGameId))
            .asSequence()
            .map(GameIdAliasEntity::oldGameId)
            .filterNot { it == winnerGameId }
            .distinct()
            .sorted()
            .forEach { historicalGameId ->
                changed += foldPhysicalLoser(historicalGameId, winnerGameId, groupKind)
            }
        return changed
    }

    @Query(
        "UPDATE games SET duplicateGroupId = NULL, duplicateGroupKind = :groupKind " +
            "WHERE gameId = :winnerGameId AND isPrimaryMember = 1 AND (" +
            "duplicateGroupId IS NOT NULL OR duplicateGroupKind IS NULL OR duplicateGroupKind != :groupKind)",
    )
    protected abstract suspend fun stampPhysicalWinner(winnerGameId: String, groupKind: String): Int

    @Query(
        "UPDATE games SET isPrimaryMember = 0, duplicateGroupId = :winnerGameId, " +
            "duplicateGroupKind = :groupKind WHERE gameId = :loserGameId AND isPrimaryMember = 1",
    )
    protected abstract suspend fun foldPhysicalLoser(
        loserGameId: String,
        winnerGameId: String,
        groupKind: String,
    ): Int

    /**
     * First-open v9 graph backfill. Components are computed in Kotlin, and every component lands through
     * the same mutation path runtime folds use. A crash rolls the Room transaction back; a second call is
     * idempotent because aliases and transition journal rows are conflict-ignored.
     */
    @Transaction
    open suspend fun backfillExistingGroups(nowMillis: Long): Int {
        if (maintenanceCompleted(V9_GROUP_BACKFILL_KEY)) return 0
        // Claim inside the same transaction before reading the graph. A second app process waits on this
        // write and then receives IGNORE; a crash rolls both marker and backfill back together.
        if (insertMaintenance(
                IdentityMaintenanceEntity(
                    maintenanceKey = V9_GROUP_BACKFILL_KEY,
                    completedAtMillis = nowMillis,
                ),
            ) == -1L
        ) {
            return 0
        }
        val links = groupedIdentityLinks()
        val linksByGameId = links.groupBy(GroupedIdentityLink::gameId)
        var created = 0
        groupedIdentityComponents(links).forEach { component ->
            val componentLinks = component.flatMap { gameId -> linksByGameId[gameId].orEmpty() }
            val winner = chooseLogicalWinner(componentLinks, component.toSet())
            val kind = componentLinks.asSequence()
                .mapNotNull(GroupedIdentityLink::duplicateGroupKind)
                .minByOrNull(::groupKindPriority)
                ?: "EXACT_HASH"
            val before = aliasesForGameIds(component).map(GameIdAliasEntity::logicalGameId).toSet()
            reconcileGroup(
                winnerGameId = winner,
                memberGameIds = component,
                groupKind = kind,
                reason = GameIdentityRemapReason.BACKFILL,
                nowMillis = nowMillis,
                physicallyFold = false,
            )
            if (before.isEmpty()) created++
        }
        return created
    }

    /** Records a scan-local multi-disc/catalog/cross-storage group already persisted in `games`. */
    @Transaction
    open suspend fun reconcileScannedGroup(
        winnerGameId: String,
        memberGameIds: List<String>,
        groupKind: String,
        nowMillis: Long,
    ): LogicalGroupMutation = reconcileGroup(
        winnerGameId = winnerGameId,
        memberGameIds = memberGameIds,
        groupKind = groupKind,
        reason = reasonForFold(groupKind),
        nowMillis = nowMillis,
        physicallyFold = false,
    )

    /** One atomic exact/catalog fold: lineage, Room state, journal, and physical visibility move together. */
    @Transaction
    open suspend fun foldTerminalGroup(
        winnerGameId: String,
        loserGameIds: List<String>,
        groupKind: String,
        nowMillis: Long,
    ): LogicalGroupMutation = reconcileGroup(
        winnerGameId = winnerGameId,
        memberGameIds = listOf(winnerGameId) + loserGameIds,
        groupKind = groupKind,
        reason = reasonForFold(groupKind),
        nowMillis = nowMillis,
        physicallyFold = true,
    )

    /** Exact-hash planning stays unchanged; only the atomic persistence seam changes. */
    @Transaction
    open suspend fun reconcileExactHashDuplicates(nowMillis: Long): Int {
        var folded = 0
        planExactHashReconciliation(exactHashCandidates()).forEach { plan ->
            foldTerminalGroup(
                winnerGameId = plan.winnerGameId,
                loserGameIds = plan.loserGameIds,
                groupKind = "EXACT_HASH",
                nowMillis = nowMillis,
            )
            folded += plan.loserGameIds.size
        }
        return folded
    }

    /**
     * Catalog collapse planning and mutation share one Room transaction. The caller's candidate ids are
     * only a hint from its enrichment snapshot: every row is re-read and revalidated here, so a rescan
     * that pruned the proposed winner cannot leave surviving variants folded under an absent id.
     */
    @Transaction
    open suspend fun foldLiveCatalogGroup(
        candidateGameIds: List<String>,
        platformId: String,
        collapseKey: String,
        baseTitle: String,
        nowMillis: Long,
    ): Int {
        val candidates = candidateGameIds
            .filter(String::isNotBlank)
            .distinct()
            .chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
            .flatMap { gameIds ->
                liveCatalogFoldCandidatesBatch(
                    gameIds = gameIds,
                    platformId = platformId,
                    collapseKey = collapseKey,
                )
            }
            .filter { normalizedCatalogBaseTitle(it.canonicalTitle ?: it.title) == baseTitle }
            .groupBy(GameEntity::gameId)
            .values
            .map { sameGame ->
                sameGame.minWith(
                    compareBy<GameEntity>(
                        { if (it.availability == "AVAILABLE") 0 else 1 },
                        { if (it.locationKind == "SAF_TREE_GRANT") 0 else 1 },
                        GameEntity::sourceRootUri,
                        GameEntity::relativePath,
                        GameEntity::entryKey,
                    ),
                )
            }
            .sortedWith(
                compareBy<GameEntity>(
                    { if (it.availability == "AVAILABLE") 0 else 1 },
                    { if (it.locationKind == "SAF_TREE_GRANT") 0 else 1 },
                    GameEntity::gameId,
                    GameEntity::sourceRootUri,
                    GameEntity::relativePath,
                    GameEntity::entryKey,
                ),
            )
        if (candidates.size <= 1) return 0

        val winnerGameId = candidates.first().gameId
        val loserGameIds = candidates.drop(1).map(GameEntity::gameId)
        reconcileGroup(
            winnerGameId = winnerGameId,
            memberGameIds = listOf(winnerGameId) + loserGameIds,
            groupKind = "CATALOG_TITLE_ID",
            reason = GameIdentityRemapReason.CATALOG_TITLE_ID_FOLD,
            nowMillis = nowMillis,
            physicallyFold = true,
        )
        return loserGameIds.size
    }

    /**
     * A1C replay boundary for catalog folds made under superseded filename/container authority.
     *
     * The old fold already consolidated user state onto its then-current winner, so there is no exact
     * inverse. This transaction deliberately does not invent ownership: it preserves that consolidated
     * state on the existing winner, restores every live physical game as an independently addressable
     * singleton, and leaves non-live historical aliases with the old lineage. A later authoritative
     * payload match may fold those singletons again through the normal journaled path.
     */
    @Transaction
    open suspend fun quarantineAndClearA1CContainerIdentity(nowMillis: Long): Int {
        val affectedLogicalIds = a1cAffectedCatalogLogicalGroupIds().distinct().sorted()
        val clearedRows = clearA1CContainerIdentityClosure()

        affectedLogicalIds.forEach { logicalGameId ->
            val group = groupById(logicalGameId) ?: return@forEach
            val aliases = aliasesForLogicalIdsBatch(listOf(logicalGameId))
                .associateBy(GameIdAliasEntity::oldGameId)
            val liveGameIds = liveGameIdsForLogicalGroup(logicalGameId).distinct().sorted()
            val stateKeeper = group.currentGameId.takeIf(liveGameIds::contains)

            liveGameIds.forEach { gameId ->
                restoreCatalogFoldVisibility(gameId)
                if (gameId == stateKeeper) return@forEach

                val newLogicalGameId = "lg:${UUID.randomUUID()}"
                check(
                    insertGroup(
                        LogicalGameGroupEntity(
                            logicalGameId = newLogicalGameId,
                            currentGameId = gameId,
                            groupKind = "CATALOG_TITLE_ID",
                            memberless = false,
                            createdAtMillis = nowMillis,
                            updatedAtMillis = nowMillis,
                        ),
                    ) != -1L,
                ) { "Logical quarantine group id collision" }
                val prior = aliases[gameId]
                upsertAlias(
                    GameIdAliasEntity(
                        oldGameId = gameId,
                        logicalGameId = newLogicalGameId,
                        firstSeenMillis = prior?.firstSeenMillis ?: nowMillis,
                        retiredAtMillis = null,
                    ),
                )
                // The merge put this game's launches under the winner's id. The ones that named this game's own
                // file go back to it; the rest cannot be told apart and stay with the id that kept the original.
                rekeyLaunchProofsByLocators(
                    group.currentGameId,
                    gameId,
                    launchLocatorsOf(launchLocatorsForGame(gameId)),
                )
            }

            if (stateKeeper == null) {
                updateGroup(
                    logicalGameId = logicalGameId,
                    currentGameId = group.currentGameId,
                    groupKind = group.groupKind,
                    memberless = true,
                    updatedAtMillis = nowMillis,
                )
                markAliasWinner(logicalGameId, currentGameId = "", retiredAtMillis = nowMillis)
            } else {
                val prior = aliases[stateKeeper]
                upsertAlias(
                    GameIdAliasEntity(
                        oldGameId = stateKeeper,
                        logicalGameId = logicalGameId,
                        firstSeenMillis = prior?.firstSeenMillis ?: nowMillis,
                        retiredAtMillis = null,
                    ),
                )
                updateGroup(
                    logicalGameId = logicalGameId,
                    currentGameId = stateKeeper,
                    groupKind = group.groupKind,
                    memberless = false,
                    updatedAtMillis = nowMillis,
                )
                markAliasWinner(logicalGameId, stateKeeper, nowMillis)
            }
        }
        return clearedRows
    }

    /**
     * Finds already-known lineages after scan rows have been upserted. This revives a memberless group
     * when one of its deterministic historical ids returns; a pre-scan root join cannot see that row.
     */
    open suspend fun logicalGameIdsForPhysicalIds(gameIds: List<String>): List<String> =
        gameIds.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .toList()
            .chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
            .flatMap { logicalGameIdsForPhysicalIdsBatch(it) }
            .distinct()
            .sorted()

    /**
     * One volume snapshot, one transaction: apply departures and arrivals before selecting any logical
     * winner. This prevents a card swap/startup reconcile from emitting an intermediate all-unavailable
     * graph or rekeying against the previous snapshot. If a malformed caller lists one root in both
     * sets, mounted/present truth wins.
     */
    @Transaction
    open suspend fun reconcileRootAvailability(
        absentRootUris: List<String>,
        presentRootUris: List<String>,
        nowMillis: Long,
    ): RootAvailabilityMutation = applyRootAvailabilitySnapshot(
        absentRootUris = absentRootUris,
        presentRootUris = presentRootUris,
        logicalIdsForRoots = ::logicalGameIdsTouchingRootsBatch,
        markUnavailable = ::markRootsStorageUnavailable,
        restoreAvailable = ::restoreRootsStorageAvailability,
        reconcile = { logicalIds -> reconcileCurrentWinners(logicalIds, nowMillis) },
    )

    /**
     * Runs after a scan has persisted and pruned its touched root, but before that Room transaction
     * commits. It handles winner removal, flips, memberless groups, and deterministic resurrection.
     */
    @Transaction
    open suspend fun reconcileCurrentWinners(
        logicalGameIds: List<String>,
        nowMillis: Long,
    ): Int {
        var changed = 0
        logicalGameIds.distinct().sorted().forEach { logicalGameId ->
            val group = groupById(logicalGameId) ?: return@forEach
            val candidates = winnerCandidates(logicalGameId)
                .distinctBy { it.gameId to it.entryKey }
                .sortedWith(WINNER_COMPARATOR)
            val successor = candidates.firstOrNull()?.gameId
            if (successor == null) {
                if (!group.memberless) {
                    updateGroup(
                        logicalGameId = logicalGameId,
                        currentGameId = group.currentGameId,
                        groupKind = group.groupKind,
                        memberless = true,
                        updatedAtMillis = nowMillis,
                    )
                    markAliasWinner(logicalGameId, currentGameId = "", retiredAtMillis = nowMillis)
                    changed++
                }
                return@forEach
            }
            val visibilityChanged = normalizeLogicalGroupVisibility(
                logicalGameId = logicalGameId,
                winnerGameId = successor,
                groupKind = group.groupKind,
            )
            if (successor == group.currentGameId && !group.memberless) {
                if (visibilityChanged > 0) changed++
                return@forEach
            }

            val oldStillLive = physicalGameRowCount(group.currentGameId) > 0
            val reason = when {
                group.memberless -> GameIdentityRemapReason.IDENTITY_RESURRECTED
                oldStillLive -> GameIdentityRemapReason.WINNER_FLIPPED
                else -> GameIdentityRemapReason.WINNER_REMOVED
            }
            if (successor != group.currentGameId) {
                rekeyRoomState(
                    sourceGameIds = listOf(group.currentGameId),
                    targetGameId = successor,
                    preferredGameId = group.currentGameId,
                )
                insertJournal(
                    RemapJournalEntity(
                        logicalGameId = logicalGameId,
                        fromGameId = group.currentGameId,
                        toGameId = successor,
                        reason = reason.name,
                        createdAtMillis = nowMillis,
                    ),
                )
            }
            if (oldStillLive && successor != group.currentGameId) {
                foldPhysicalLoser(group.currentGameId, successor, group.groupKind)
            }
            updateGroup(
                logicalGameId = logicalGameId,
                currentGameId = successor,
                groupKind = group.groupKind,
                memberless = false,
                updatedAtMillis = nowMillis,
            )
            markAliasWinner(logicalGameId, successor, nowMillis)
            changed++
        }
        return changed
    }

    private suspend fun reconcileGroup(
        winnerGameId: String,
        memberGameIds: List<String>,
        groupKind: String,
        reason: GameIdentityRemapReason,
        nowMillis: Long,
        physicallyFold: Boolean,
    ): LogicalGroupMutation {
        val members = (listOf(winnerGameId) + memberGameIds)
            .filter(String::isNotBlank)
            .distinct()
        require(members.size > 1) { "A logical group requires at least two physical game ids" }

        val aliases = aliasesForGameIds(members)
        val existingLogicalIds = aliases.map(GameIdAliasEntity::logicalGameId).distinct()
        val existingGroups = groupsByIds(existingLogicalIds)
        // A scan-local subgroup can reappear inside an already-proven terminal lineage. Preserve the
        // strongest evidence across the complete absorbed component: rescanning an `.m3u` root may
        // restore its MULTI_DISC rows, but it must never downgrade the enclosing cross-root
        // CATALOG_TITLE_ID/EXACT_HASH relationship that keeps detach/removal replay deterministic.
        val effectiveGroupKind = (existingGroups.map(LogicalGameGroupEntity::groupKind) + groupKind)
            .minBy(::groupKindPriority)
        val survivor = existingGroups.minWithOrNull(
            compareBy<LogicalGameGroupEntity>(
                LogicalGameGroupEntity::createdAtMillis,
                LogicalGameGroupEntity::logicalGameId,
            ),
        )
        val logicalGameId = survivor?.logicalGameId ?: "lg:${UUID.randomUUID()}"
        val previousGameId = survivor?.currentGameId

        if (survivor == null) {
            check(
                insertGroup(
                    LogicalGameGroupEntity(
                        logicalGameId = logicalGameId,
                        currentGameId = winnerGameId,
                        groupKind = effectiveGroupKind,
                        memberless = false,
                        createdAtMillis = nowMillis,
                        updatedAtMillis = nowMillis,
                    ),
                ) != -1L,
            ) { "Logical group id collision" }
        }

        val absorbedGroups = existingGroups.filterNot { it.logicalGameId == logicalGameId }
        val absorbedAliases = aliasesForLogicalIds(absorbedGroups.map(LogicalGameGroupEntity::logicalGameId))
        val previouslyKnownIds = (aliases + absorbedAliases)
            .map(GameIdAliasEntity::oldGameId)
            .toSet()
        absorbedGroups.forEach { absorbed ->
            repointAliases(absorbed.logicalGameId, logicalGameId)
            deleteGroup(absorbed.logicalGameId)
        }

        val allPhysicalIds = (
            members +
                aliases.map(GameIdAliasEntity::oldGameId) +
                absorbedAliases.map(GameIdAliasEntity::oldGameId) +
                listOfNotNull(previousGameId) +
                absorbedGroups.map(LogicalGameGroupEntity::currentGameId)
            ).distinct()
        allPhysicalIds.forEach { physicalId ->
            val existing = aliases.firstOrNull { it.oldGameId == physicalId }
                ?: absorbedAliases.firstOrNull { it.oldGameId == physicalId }
            upsertAlias(
                GameIdAliasEntity(
                    oldGameId = physicalId,
                    logicalGameId = logicalGameId,
                    firstSeenMillis = existing?.firstSeenMillis ?: nowMillis,
                    retiredAtMillis = if (physicalId == winnerGameId) null else {
                        existing?.retiredAtMillis ?: nowMillis
                    },
                ),
            )
        }

        val sourceIds = allPhysicalIds.filterNot { it == winnerGameId }
        val preferredStateId = previousGameId
            ?.takeIf { it != winnerGameId }
            ?: winnerGameId
        rekeyRoomState(sourceIds, winnerGameId, preferredStateId)

        /*
         * Queue only a material transition. Replaying the same scan/backfill is a no-op, while a later
         * A→B recurrence must remain legal after B→A; text-pair uniqueness would silently discard it.
         */
        val journalSourceIds = buildSet {
            if (survivor == null) {
                addAll(members)
            } else {
                previousGameId?.takeIf { it != winnerGameId }?.let(::add)
                members.filterNot { it in previouslyKnownIds }.forEach(::add)
                absorbedGroups.map(LogicalGameGroupEntity::currentGameId).forEach(::add)
            }
        }.filterNot { it == winnerGameId }

        var queued = 0
        val journalReason = if (survivor?.memberless == true) {
            GameIdentityRemapReason.IDENTITY_RESURRECTED
        } else {
            reason
        }
        journalSourceIds.sortedWith(compareBy<String>({ it != preferredStateId }, { it })).forEach { sourceId ->
            if (insertJournal(
                    RemapJournalEntity(
                        logicalGameId = logicalGameId,
                        fromGameId = sourceId,
                        toGameId = winnerGameId,
                        reason = journalReason.name,
                        createdAtMillis = nowMillis,
                    ),
                ) != -1L
            ) {
                queued++
            }
        }

        updateGroup(
            logicalGameId = logicalGameId,
            currentGameId = winnerGameId,
            groupKind = effectiveGroupKind,
            memberless = false,
            updatedAtMillis = nowMillis,
        )
        markAliasWinner(logicalGameId, winnerGameId, nowMillis)

        if (physicallyFold) {
            stampPhysicalWinner(winnerGameId, effectiveGroupKind)
            members.filterNot { it == winnerGameId }.forEach { loser ->
                foldPhysicalLoser(loser, winnerGameId, effectiveGroupKind)
            }
        }
        normalizeLogicalGroupVisibility(logicalGameId, winnerGameId, effectiveGroupKind)
        return LogicalGroupMutation(logicalGameId, previousGameId, winnerGameId, queued)
    }

    private suspend fun rekeyRoomState(
        sourceGameIds: List<String>,
        targetGameId: String,
        preferredGameId: String,
    ) {
        val sources = sourceGameIds.filterNot { it == targetGameId }.distinct()
        if (sources.isEmpty()) return
        val allIds = (listOf(targetGameId) + sources).distinct()

        val favorites = allIds.chunked(SINGLE_IN_QUERY_CHUNK_SIZE).flatMap { favoritesByIdsBatch(it) }
        if (favorites.isNotEmpty()) {
            upsertFavorite(
                FavoriteEntity(
                    gameId = targetGameId,
                    favoritedAtMillis = favorites.maxOf(FavoriteEntity::favoritedAtMillis),
                ),
            )
        }

        val hidden = allIds.chunked(SINGLE_IN_QUERY_CHUNK_SIZE).flatMap { hiddenByIdsBatch(it) }
        if (hidden.isNotEmpty()) {
            val truth = hidden.filter(HiddenItemEntity::hidden).ifEmpty { hidden }
                .maxWith(compareBy(HiddenItemEntity::hiddenAtMillis, HiddenItemEntity::gameId))
            upsertHidden(
                truth.copy(
                    gameId = targetGameId,
                    duplicateGroupId = targetGameId,
                    isPrimaryMember = true,
                ),
            )
        }

        val overrides = allIds.chunked(SINGLE_IN_QUERY_CHUNK_SIZE).flatMap { overridesByIdsBatch(it) }
        val chosenOverride = overrides.firstOrNull { it.gameId == preferredGameId }
            ?: overrides.firstOrNull { it.gameId == targetGameId }
            ?: overrides.maxWithOrNull(
                compareBy<EmulatorOverrideEntity>(
                    EmulatorOverrideEntity::setAtMillis,
                    EmulatorOverrideEntity::gameId,
                ),
            )
        chosenOverride?.let { upsertOverride(it.copy(gameId = targetGameId)) }

        // Manual title overrides  get the identical treatment: without this, a
        // rename set before a remap stays keyed at the old id forever — clearTitleOverrideForCurrent only
        // ever deletes at the resolved CURRENT id, so it silently no-ops, and a second rename made after
        // the remap can leave the stale pre-remap row as the one that survives a later clear (it "wins" the
        // most-recently-set tie-break in TITLE_OVERRIDE_MATCH_SQL once the fresher row is gone). Consolidating
        // here — the same place every other per-game store is consolidated — means at most one row ever
        // exists per logical game, so a current-id-only clear is always correct.
        val titleOverrides = allIds.chunked(SINGLE_IN_QUERY_CHUNK_SIZE).flatMap { titleOverridesByIdsBatch(it) }
        val chosenTitleOverride = titleOverrides.firstOrNull { it.gameId == preferredGameId }
            ?: titleOverrides.firstOrNull { it.gameId == targetGameId }
            ?: titleOverrides.maxWithOrNull(
                compareBy<TitleOverrideEntity>(
                    TitleOverrideEntity::setAtMillis,
                    TitleOverrideEntity::gameId,
                ),
            )
        chosenTitleOverride?.let { upsertTitleOverride(it.copy(gameId = targetGameId)) }

        val homeItems = allIds.chunked(SINGLE_IN_QUERY_CHUNK_SIZE).flatMap { homeItemsByIdsBatch(it) }
        val chosenHomeByProfile = homeItems.groupBy(HomeShelfItemEntity::profileId).mapValues { (_, rows) ->
            rows.minWith(
                compareBy<HomeShelfItemEntity>(
                    HomeShelfItemEntity::rank,
                    HomeShelfItemEntity::addedAtMillis,
                    HomeShelfItemEntity::itemId,
                ),
            )
        }

        sources.chunked(SINGLE_IN_QUERY_CHUNK_SIZE).forEach { batch ->
            deleteFavoritesBatch(batch)
            deleteHiddenBatch(batch)
            deleteOverridesBatch(batch)
            deleteTitleOverridesBatch(batch)
            deleteHomeItemsBatch(batch)
        }
        chosenHomeByProfile.values.forEach { chosen ->
            upsertHomeItem(chosen.copy(itemId = targetGameId))
        }
        sources.forEach { source -> rekeyLaunchProofs(source, targetGameId) }

        val physicalMetadataKeys = allIds.chunked(SINGLE_IN_QUERY_CHUNK_SIZE)
            .flatMap { metadataKeysForGameIdsBatch(it) }
        val metadataKeys = (
            allIds.map { "game:$it" } +
                physicalMetadataKeys.map(PhysicalGameMetadataKey::titleCatalogKey)
            ).distinct()
        val metadata = metadataKeys.chunked(SINGLE_IN_QUERY_CHUNK_SIZE).flatMap { metadataByKeysBatch(it) }
        val preferredKeys = buildSet {
            add("game:$preferredGameId")
            physicalMetadataKeys
                .filter { it.gameId == preferredGameId }
                .mapTo(this, PhysicalGameMetadataKey::titleCatalogKey)
        }
        metadataCopyForIdentityRemap(
            metadata = metadata,
            targetGameId = targetGameId,
            preferredKeys = preferredKeys,
            preferPreviousWinner = preferredGameId != targetGameId,
        )?.let { upsertMetadata(it) }
    }

    private suspend fun aliasesForGameIds(gameIds: List<String>): List<GameIdAliasEntity> =
        gameIds.distinct().chunked(SINGLE_IN_QUERY_CHUNK_SIZE).flatMap { aliasesForGameIdsBatch(it) }

    private suspend fun aliasesForLogicalIds(logicalIds: List<String>): List<GameIdAliasEntity> =
        logicalIds.distinct().chunked(SINGLE_IN_QUERY_CHUNK_SIZE).flatMap { aliasesForLogicalIdsBatch(it) }

    private suspend fun groupsByIds(logicalIds: List<String>): List<LogicalGameGroupEntity> =
        logicalIds.distinct().chunked(SINGLE_IN_QUERY_CHUNK_SIZE).flatMap { groupsByIdsBatch(it) }
}

private fun reasonForFold(groupKind: String): GameIdentityRemapReason = when (groupKind) {
    "CATALOG_TITLE_ID" -> GameIdentityRemapReason.CATALOG_TITLE_ID_FOLD
    "MULTI_DISC" -> GameIdentityRemapReason.MULTI_DISC_FOLD
    "CROSS_STORAGE" -> GameIdentityRemapReason.CROSS_STORAGE_FOLD
    else -> GameIdentityRemapReason.EXACT_HASH_FOLD
}

private fun groupKindPriority(groupKind: String): Int = when (groupKind) {
    "EXACT_HASH" -> 0
    "CATALOG_TITLE_ID" -> 1
    "MULTI_DISC" -> 2
    "CROSS_STORAGE" -> 3
    else -> 4
}

private fun metadataRichness(metadata: TitleMetadataCacheEntity): Int =
    listOf(
        metadata.canonicalSerial,
        metadata.description,
        metadata.boxArtUri,
        metadata.screenshotUris,
        metadata.source,
        metadata.license,
    ).count { !it.isNullOrBlank() }

internal fun metadataCopyForIdentityRemap(
    metadata: List<TitleMetadataCacheEntity>,
    targetGameId: String,
    preferredKeys: Set<String>,
    preferPreviousWinner: Boolean,
): TitleMetadataCacheEntity? {
    val targetKey = "game:$targetGameId"
    val richnessOrder = compareBy<TitleMetadataCacheEntity>(
        ::metadataRichness,
        TitleMetadataCacheEntity::cachedAtMillis,
        TitleMetadataCacheEntity::catalogKey,
    )
    val preferred = metadata.asSequence()
        .filter { it.catalogKey in preferredKeys }
        .maxWithOrNull(richnessOrder)
    val source = when {
        preferPreviousWinner && preferred != null -> preferred
        metadata.any { it.catalogKey == targetKey } -> return null
        else -> metadata.maxWithOrNull(richnessOrder)
    }
    return source?.copy(catalogKey = targetKey)
}

private const val V9_GROUP_BACKFILL_KEY = "v9-logical-group-backfill"
