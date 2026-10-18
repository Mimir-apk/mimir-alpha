package com.gruning.mimir.rewrite.core.model.recovery

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.ProfileId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.migration.MigrationGameRecord

/** Bounded ceiling for owner-state rows considered during restore planning/apply. */
const val RECOVERY_MAX_RESTORE_RECORDS: Int = 50_000

/**
 * Conservative ceiling for every pass-one projection row retained in memory, including auxiliary
 * identity/hint tables that carry no restore intent on their own.
 */
const val RECOVERY_MAX_RESTORE_RETAINED_RECORDS: Int = 500_000

/** UTF-8 byte ceiling for all string fields accumulated during pass-one projection. */
const val RECOVERY_MAX_RESTORE_RETAINED_TEXT_BYTES: Long = 16L * 1024L * 1024L

/**
 * Owner-visible categories that Recovery Archive V1 may carry but EP-06B restore never writes.
 * The dry-run and apply result must always surface this list honestly.
 */
enum class RecoveryRestoreExcludedCategory(val storageKey: String, val label: String) {
    PLATFORMS("platforms", "Platforms"),
    LAUNCH_PROOFS("launch-proofs", "Launch proofs"),
    BIOS_PROFILES("bios-profiles", "BIOS profiles"),
    TITLE_METADATA_CACHE("title-metadata-cache", "Title metadata cache"),
    REMAP_JOURNAL("remap-journal", "Identity remap journal"),
    IDENTITY_MAINTENANCE("identity-maintenance", "Identity maintenance markers"),
    SOURCE_ROOT_AUTHORITY("source-root-authority", "Source roots, URI grants, and managed paths"),
    CREDENTIALS("credentials", "Provider credentials and account secrets"),
    ROM_BIOS_BYTES("rom-bios-bytes", "ROM, BIOS, and firmware bytes"),
    ARTWORK_MEDIA("artwork-media", "Downloaded art and media caches"),
    CUSTOM_MEDIA("custom-media", "Custom hero and media state"),
    HOME_COLLECTIONS("home-collections", "Home collections and Tomes"),
    QUICK_LAUNCH("quick-launch", "Quick Launch slots"),
    APPEARANCE_BRIGHTNESS("appearance-brightness", "Backdrop brightness and device state"),
    // The player-facing name is the elapsed store's own: Mimir measures launch-to-return, not the time a game ran, so no surface says "playtime"
    // (see MimirTimeCopy). The storage key is the V1 archive's id for the category, not copy, and stays as written.
    SESSION_TIME("playtime", "Session time"),
    LOGS("logs", "Logs and diagnostics"),
    ;

    companion object {
        val requiredDisclosures: List<RecoveryRestoreExcludedCategory> = entries
    }
}

open class RecoveryRestoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class RecoveryRestoreRecordCapException(
    val attemptedRecords: Int,
    val ceiling: Int = RECOVERY_MAX_RESTORE_RECORDS,
) : RecoveryRestoreException(
    "Recovery restore exceeds the supported record ceiling ($attemptedRecords > $ceiling).",
)

class RecoveryRestoreRetainedRecordCapException(
    val attemptedRecords: Int,
    val ceiling: Int = RECOVERY_MAX_RESTORE_RETAINED_RECORDS,
) : RecoveryRestoreException(
    "Recovery restore exceeds the supported retained-record ceiling ($attemptedRecords > $ceiling).",
)

class RecoveryRestoreRetainedTextCapException(
    val attemptedBytes: Long,
    val ceiling: Long = RECOVERY_MAX_RESTORE_RETAINED_TEXT_BYTES,
) : RecoveryRestoreException(
    "Recovery restore exceeds the supported retained-text ceiling ($attemptedBytes > $ceiling).",
)

class RecoveryRestoreShelfRankOverflowException(
    val lastAssignedRank: Long,
) : RecoveryRestoreException(
    "Recovery restore cannot assign another Home Shelf rank after $lastAssignedRank.",
)

class RecoveryRestoreSchemaException(message: String) : RecoveryRestoreException(message)

/** Archive-space alias and logical-group maps used only during projection — never persisted. */
data class RecoveryArchiveIdentityIndex(
    val aliasToLogical: Map<String, String>,
    val logicalToCurrent: Map<String, String>,
) {
    fun normalizeArchivedGameId(gameId: String): String {
        val logicalId = aliasToLogical[gameId] ?: return gameId
        return logicalToCurrent[logicalId] ?: gameId
    }
}

data class RecoveryArchivedFavorite(
    val archivedGameId: String,
    val favoritedAtMillis: Long,
)

data class RecoveryArchivedHidden(
    val archivedGameId: String,
    val hidden: Boolean,
    val hiddenAtMillis: Long,
)

data class RecoveryArchivedEmulatorOverride(
    val archivedGameId: String,
    val emulatorId: String,
    val setAtMillis: Long,
)

data class RecoveryArchivedShelfItem(
    val profileId: String,
    val itemKind: String,
    val itemId: String,
    val rank: Long,
    val addedAtMillis: Long,
)

/** Read-only game evidence used to re-derive a local [GameId] through [MigrationMatcher]. */
data class RecoveryArchivedGameEvidence(
    val normalizedGameId: String,
    val platformId: String,
    val fileName: String,
    val sizeBytes: Long,
    val relativePath: String,
    val contentHash: String?,
    val serial: String?,
    val title: String,
    val platformLabel: String,
) {
    fun toMigrationRecord(): MigrationGameRecord = MigrationGameRecord(
        platformId = platformId,
        fileName = fileName,
        sizeBytes = sizeBytes,
        relativePath = relativePath,
        contentHash = contentHash,
        serial = serial,
        title = title,
        platformLabel = platformLabel,
    )
}

data class RecoverySourceRootHint(
    val friendlyLabel: String,
    val isPrimary: Boolean,
)

/** Typed two-pass projection of the restore-eligible archive payload. */
data class RecoveryArchiveRestoreSnapshot(
    val schemaVersion: Int,
    val favorites: List<RecoveryArchivedFavorite>,
    val hidden: List<RecoveryArchivedHidden>,
    val emulatorOverrides: List<RecoveryArchivedEmulatorOverride>,
    val shelfItems: List<RecoveryArchivedShelfItem>,
    val identityIndex: RecoveryArchiveIdentityIndex,
    val gameEvidence: Map<String, RecoveryArchivedGameEvidence>,
    val sourceRootHints: List<RecoverySourceRootHint>,
) {
    fun restoreRecordCount(): Int =
        favorites.size +
            hidden.count(RecoveryArchivedHidden::hidden) +
            emulatorOverrides.size +
            shelfItems.size
}

sealed interface RestoreRowOutcome<T> {
    val archived: T
}

sealed interface RestoreIdentityOutcome<T> : RestoreRowOutcome<T> {
    val normalizedArchivedGameId: String
    val migrationRecord: MigrationGameRecord?
}

data class RestoreFavoriteOutcome(
    override val archived: RecoveryArchivedFavorite,
    override val normalizedArchivedGameId: String,
    override val migrationRecord: MigrationGameRecord?,
    val matchedLocalGameId: GameId? = null,
) : RestoreIdentityOutcome<RecoveryArchivedFavorite>

data class RestoreHiddenOutcome(
    override val archived: RecoveryArchivedHidden,
    override val normalizedArchivedGameId: String,
    override val migrationRecord: MigrationGameRecord?,
    val matchedLocalGameId: GameId? = null,
) : RestoreIdentityOutcome<RecoveryArchivedHidden>

data class RestoreOverrideOutcome(
    override val archived: RecoveryArchivedEmulatorOverride,
    override val normalizedArchivedGameId: String,
    override val migrationRecord: MigrationGameRecord?,
    val emulatorId: String,
    val matchedLocalGameId: GameId? = null,
) : RestoreIdentityOutcome<RecoveryArchivedEmulatorOverride>

data class RestoreShelfOutcome(
    override val archived: RecoveryArchivedShelfItem,
    val itemId: HomeShelfItemId?,
    val plannedRank: Long? = null,
    val skipReason: ShelfSkipReason? = null,
) : RestoreRowOutcome<RecoveryArchivedShelfItem>

enum class ShelfSkipReason {
    FOREIGN_PROFILE,
    PACKAGE_NOT_INSTALLED,
    UNMATCHED_EMULATED_GAME,
    UNSUPPORTED_ITEM_KIND,
    RANK_OVERFLOW,
}

sealed interface RestoreWriteDecision<out O : RestoreRowOutcome<*>> {
    val outcome: O
}

data class RestoreWillWrite<O : RestoreRowOutcome<*>>(
    override val outcome: O,
) : RestoreWriteDecision<O>

data class RestoreSkipped<O : RestoreRowOutcome<*>>(
    override val outcome: O,
    val reason: RestoreSkipReason,
) : RestoreWriteDecision<O>

enum class RestoreSkipReason {
    LOCAL_EXISTS,
    UNMATCHED_GAME,
    HIDDEN_FALSE,
    UNKNOWN_EMULATOR,
    FOREIGN_PROFILE,
    PACKAGE_NOT_INSTALLED,
    PENDING_LIBRARY_SCAN,
    UNSUPPORTED_ITEM_KIND,
    RANK_OVERFLOW,
}

data class RestoreStorePlan<O : RestoreRowOutcome<*>>(
    val decisions: List<RestoreWriteDecision<O>>,
) {
    val matched: Int get() = decisions.count { it is RestoreWillWrite<*> }
    val skipped: Int get() = decisions.count { it is RestoreSkipped<*> }
    val pending: Int
        get() = decisions.count {
            it is RestoreSkipped<*> && it.reason == RestoreSkipReason.PENDING_LIBRARY_SCAN
        }
    val unmatched: Int
        get() = decisions.count {
            it is RestoreSkipped<*> && it.reason == RestoreSkipReason.UNMATCHED_GAME
        }
}

data class RecoveryRestorePlan(
    val favorites: RestoreStorePlan<RestoreFavoriteOutcome>,
    val hidden: RestoreStorePlan<RestoreHiddenOutcome>,
    val overrides: RestoreStorePlan<RestoreOverrideOutcome>,
    val shelf: RestoreStorePlan<RestoreShelfOutcome>,
    val sourceRootHints: List<RecoverySourceRootHint>,
    val notRestored: List<RecoveryRestoreExcludedCategory>,
    val pendingLibraryScan: Boolean,
    val archiveRestoreRecordCount: Int,
) {
    val totalWouldWrite: Int
        get() = favorites.matched + hidden.matched + overrides.matched + shelf.matched

    val totalDecisionCount: Int
        get() = favorites.decisions.size +
            hidden.decisions.size +
            overrides.decisions.size +
            shelf.decisions.size
}

data class RecoveryRestoreResult(
    val favoritesRestored: Int,
    val favoritesSkipped: Int,
    val favoritesUnmatched: Int,
    val hiddenRestored: Int,
    val hiddenSkipped: Int,
    val hiddenUnmatched: Int,
    val overridesRestored: Int,
    val overridesSkipped: Int,
    val overridesUnmatched: Int,
    val shelfRestored: Int,
    val shelfSkipped: Int,
    val shelfUnmatched: Int,
    val favoritesPending: Int = 0,
    val hiddenPending: Int = 0,
    val overridesPending: Int = 0,
    val shelfPending: Int = 0,
    val sourceRootHints: List<RecoverySourceRootHint>,
    val notRestored: List<RecoveryRestoreExcludedCategory>,
    val pendingLibraryScan: Boolean,
) {
    val totalRestored: Int
        get() = favoritesRestored + hiddenRestored + overridesRestored + shelfRestored
}
