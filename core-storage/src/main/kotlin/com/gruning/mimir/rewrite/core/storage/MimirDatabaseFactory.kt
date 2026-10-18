package com.gruning.mimir.rewrite.core.storage

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailure
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailureReason
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal const val CREATE_HOME_SHELF_ITEMS_SQL =
    "CREATE TABLE IF NOT EXISTS `home_shelf_items` (" +
        "`profileId` TEXT NOT NULL, " +
        "`itemKind` TEXT NOT NULL, " +
        "`itemId` TEXT NOT NULL, " +
        "`rank` INTEGER NOT NULL, " +
        "`addedAtMillis` INTEGER NOT NULL, " +
        "PRIMARY KEY(`profileId`, `itemKind`, `itemId`), " +
        "CHECK(`itemKind` IN ('EMULATED_GAME', 'ANDROID_GAME')))"

internal const val CREATE_HOME_SHELF_PROFILE_RANK_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_home_shelf_items_profile_rank` " +
        "ON `home_shelf_items` (`profileId`, `rank`)"

/**
 * the identity sweep index. Column order matters: the two-column predicate comes first so both hot
 * queries can seek, and `sizeBytes` last so the driver's `ORDER BY sizeBytes` is served by the index rather
 * than a temp B-tree. Must match `GameEntity`'s declaration exactly or Room's post-migration schema
 * validation fails.
 */
internal const val CREATE_GAMES_IDENTITY_SWEEP_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_games_identity_sweep` " +
        "ON `games` (`isPrimaryMember`, `identityResolvedAtMillis`, `sizeBytes`)"

internal const val CREATE_GAMES_SOURCE_RELATIVE_INDEX_SQL =
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_games_sourceRootUri_relativePath` " +
        "ON `games` (`sourceRootUri`, `relativePath`)"

internal const val CREATE_GAMES_SOURCE_ENTRY_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_games_sourceRootUri_entryKey` " +
        "ON `games` (`sourceRootUri`, `entryKey`)"

internal const val CREATE_GAMES_V11_SQL =
    "CREATE TABLE `games_v11` (" +
        "`entryKey` TEXT NOT NULL, `gameId` TEXT NOT NULL, `platformId` TEXT NOT NULL, " +
        "`contentKind` TEXT NOT NULL, `displayName` TEXT NOT NULL, `title` TEXT NOT NULL, " +
        "`region` TEXT, `version` TEXT, `serial` TEXT, `titleCatalogKey` TEXT, " +
        "`identitySource` TEXT NOT NULL, `locationKind` TEXT NOT NULL, " +
        "`availability` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `contentHash` TEXT, " +
        "`sourceRootUri` TEXT NOT NULL, `sourceDocumentUri` TEXT, `managedPath` TEXT, " +
        "`relativePath` TEXT NOT NULL, `discNumber` INTEGER NOT NULL, `duplicateGroupId` TEXT, " +
        "`isPrimaryMember` INTEGER NOT NULL, `duplicateGroupKind` TEXT, `canonicalTitle` TEXT, " +
        "`collapseKey` TEXT, `identityOrigin` TEXT, `identityResolvedAtMillis` INTEGER, " +
        "PRIMARY KEY(`entryKey`))"

internal const val CREATE_LAUNCH_PROOFS_V7_SQL =
    "CREATE TABLE IF NOT EXISTS `launch_proofs_v7` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`gameId` TEXT, " +
        "`androidPackageName` TEXT, " +
        "`platformId` TEXT NOT NULL, " +
        "`emulatorId` TEXT NOT NULL, " +
        "`locator` TEXT NOT NULL, " +
        "`outcome` TEXT NOT NULL, " +
        "`timestampMillis` INTEGER NOT NULL)"

internal const val CREATE_LAUNCH_PROOFS_GAME_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_launch_proofs_gameId` ON `launch_proofs` (`gameId`)"

internal const val CREATE_LAUNCH_PROOFS_ANDROID_PACKAGE_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_launch_proofs_androidPackageName` " +
        "ON `launch_proofs` (`androidPackageName`)"

internal const val CREATE_BIOS_PROFILES_V12_SQL =
    "CREATE TABLE `bios_profiles_v12` (" +
        "`platformId` TEXT NOT NULL, " +
        "`emulatorScopeKey` TEXT NOT NULL, " +
        "`packageName` TEXT, " +
        "`fileName` TEXT NOT NULL, " +
        "`required` INTEGER NOT NULL, " +
        "`presence` TEXT NOT NULL, " +
        "`expectedHash` TEXT, " +
        "`expectedRelativePath` TEXT, " +
        "PRIMARY KEY(`platformId`, `emulatorScopeKey`, `fileName`))"

internal const val MIGRATE_BIOS_PROFILES_V11_TO_V12_INSERT_SQL =
    "INSERT INTO `bios_profiles_v12` (" +
        "`platformId`, `emulatorScopeKey`, `packageName`, `fileName`, `required`, `presence`, " +
        "`expectedHash`, `expectedRelativePath`) " +
        "SELECT `platformId`, '', NULL, `fileName`, `required`, 'UNKNOWN', `expectedHash`, " +
        "`expectedRelativePath` FROM `bios_profiles`"

internal const val CREATE_LOGICAL_GAME_GROUPS_SQL =
    "CREATE TABLE IF NOT EXISTS `logical_game_groups` (" +
        "`logicalGameId` TEXT NOT NULL, " +
        "`currentGameId` TEXT NOT NULL, " +
        "`groupKind` TEXT NOT NULL, " +
        "`memberless` INTEGER NOT NULL, " +
        "`createdAtMillis` INTEGER NOT NULL, " +
        "`updatedAtMillis` INTEGER NOT NULL, " +
        "PRIMARY KEY(`logicalGameId`))"

internal const val CREATE_LOGICAL_GAME_CURRENT_INDEX_SQL =
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_logical_game_groups_currentGameId` " +
        "ON `logical_game_groups` (`currentGameId`)"

internal const val CREATE_GAME_ID_ALIASES_SQL =
    "CREATE TABLE IF NOT EXISTS `game_id_aliases` (" +
        "`oldGameId` TEXT NOT NULL, " +
        "`logicalGameId` TEXT NOT NULL, " +
        "`firstSeenMillis` INTEGER NOT NULL, " +
        "`retiredAtMillis` INTEGER, " +
        "PRIMARY KEY(`oldGameId`))"

internal const val CREATE_GAME_ID_ALIASES_LOGICAL_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_game_id_aliases_logicalGameId` " +
        "ON `game_id_aliases` (`logicalGameId`)"

internal const val CREATE_REMAP_JOURNAL_SQL =
    "CREATE TABLE IF NOT EXISTS `remap_journal` (" +
        "`journalId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`logicalGameId` TEXT NOT NULL, " +
        "`fromGameId` TEXT NOT NULL, " +
        "`toGameId` TEXT NOT NULL, " +
        "`reason` TEXT NOT NULL, " +
        "`createdAtMillis` INTEGER NOT NULL, " +
        "`storesCompletedMask` INTEGER NOT NULL, " +
        "`consumedAtMillis` INTEGER, " +
        "`lastError` TEXT)"

internal const val CREATE_REMAP_JOURNAL_PENDING_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_remap_journal_consumedAtMillis_journalId` " +
        "ON `remap_journal` (`consumedAtMillis`, `journalId`)"

internal const val CREATE_IDENTITY_MAINTENANCE_SQL =
    "CREATE TABLE IF NOT EXISTS `identity_maintenance` (" +
        "`maintenanceKey` TEXT NOT NULL, " +
        "`completedAtMillis` INTEGER NOT NULL, " +
        "PRIMARY KEY(`maintenanceKey`))"

internal const val CREATE_SOURCE_RECONCILE_RUNS_SQL =
    "CREATE TABLE IF NOT EXISTS `source_reconcile_runs` (" +
        "`runId` TEXT NOT NULL, " +
        "`policyStamp` TEXT NOT NULL, " +
        "`ownerToken` TEXT NOT NULL, " +
        "`startedAtMillis` INTEGER NOT NULL, " +
        "`resumeCount` INTEGER NOT NULL, " +
        "`sourcesChecked` INTEGER NOT NULL, " +
        "`healthySources` INTEGER NOT NULL, " +
        "`removedSources` INTEGER NOT NULL, " +
        "`unavailableSources` INTEGER NOT NULL, " +
        "`failedSources` INTEGER NOT NULL, " +
        "`quarantinedSources` INTEGER NOT NULL, " +
        "`filesSeen` INTEGER NOT NULL, " +
        "`recognized` INTEGER NOT NULL, " +
        "`groups` INTEGER NOT NULL, " +
        "`skipped` INTEGER NOT NULL, " +
        "`resumedWithDrift` INTEGER NOT NULL, " +
        "`driftedSources` INTEGER NOT NULL, " +
        "PRIMARY KEY(`runId`))"

internal const val CREATE_SOURCE_RECONCILE_ROOTS_SQL =
    "CREATE TABLE IF NOT EXISTS `source_reconcile_roots` (" +
        "`runId` TEXT NOT NULL, " +
        "`rootId` TEXT NOT NULL, " +
        "`rootUri` TEXT NOT NULL, " +
        "`ordinal` INTEGER NOT NULL, " +
        "`plannedPrimary` INTEGER NOT NULL, " +
        "`phase` TEXT NOT NULL, " +
        "`outcome` TEXT, " +
        "`attempts` INTEGER NOT NULL, " +
        "`rootSeenMillis` INTEGER, " +
        "`settledAtMillis` INTEGER, " +
        "PRIMARY KEY(`runId`, `rootId`))"

internal const val CREATE_SOURCE_RECONCILE_ROOT_ID_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_source_reconcile_roots_rootId` " +
        "ON `source_reconcile_roots` (`rootId`)"

/**
 * v13 — the manual title override (cosmetic rename, never fed into recognition).
 * Same shape as `emulator_overrides`: keyed on `gameId` alone, no foreign key, so it survives collapse and
 * disc-swap. Deliberately never written by a scan or enrichment pass — see [TitleOverrideEntity] — so this
 * table existing is the whole mechanism behind "a rename survives a re-scan and a later scrape".
 */
internal const val CREATE_TITLE_OVERRIDES_SQL =
    "CREATE TABLE IF NOT EXISTS `title_overrides` " +
        "(`gameId` TEXT NOT NULL, `title` TEXT NOT NULL, `setAtMillis` INTEGER NOT NULL, " +
        "PRIMARY KEY(`gameId`))"

private const val LEGACY_PHYSICAL_IDENTITY_MAX_SCHEMA = 10
private const val LEGACY_PHYSICAL_IDENTITY_MIGRATION_REFUSAL =
    "MIMIR_LEGACY_PHYSICAL_IDENTITY_UNSAFE"
private const val LEGACY_PHYSICAL_IDENTITY_QUERY =
    "SELECT typeof(`entryKey`), CAST(`entryKey` AS BLOB), " +
        "typeof(`sourceRootUri`), CAST(`sourceRootUri` AS BLOB), " +
        "typeof(`relativePath`), CAST(`relativePath` AS BLOB), " +
        "typeof(`locationKind`), CAST(`locationKind` AS BLOB), " +
        "typeof(`managedPath`), CAST(`managedPath` AS BLOB) FROM `games`"

/**
 * Builds (and process-caches) the single [MimirDatabase] instance. The schema module owns DB
 * construction so the on-disk name + builder options live next to the schema; DI in higher modules asks
 * this factory for the instance rather than re-deriving Room wiring.
 *
 * Local-only file (`MimirDatabase.DB_NAME`, side-by-side-safe with the shipping product). NO network,
 * NO cloud sync (posture manifest).
 */
object MimirDatabaseFactory {

    @Volatile
    private var instance: MimirDatabase? = null

    private val openRefusal = DatabaseOpenRefusalLatch()

    private val missingMigrationPattern =
        Regex("""(?i)\bmigration from\s+(\d+)\s+to\s+(\d+)\s+was required but not found\b""")

    /** The process-wide singleton [MimirDatabase], created on first use (double-checked). */
    fun get(context: Context): MimirDatabase {
        openRefusal.throwIfRefused()
        return instance ?: synchronized(this) {
            openRefusal.throwIfRefused()
            instance ?: openCompatible(context.applicationContext)
                .also { instance = it }
        }
    }

    /**
     * Opens the production database before any background service or composition can borrow it.
     *
     * A newer on-disk schema is reported as a typed, Close-only startup refusal. Unrelated open failures
     * are rethrown: corruption, storage failure, and programming defects must never be mislabeled as a
     * downgrade or routed into an action that guesses at recovery.
     */
    fun startupFailure(context: Context): DatabaseOpenFailure? {
        return try {
            get(context.applicationContext)
            null
        } catch (refused: DatabaseOpenRefusedException) {
            refused.failure
        }
    }

    /**
     * Byte-preserving compatibility probe used by production and Android migration proof.
     *
     * Even an SQLite read-only open may update the owner's `-shm` WAL index, while the framework's
     * default corruption handler may delete the database. Probe a private snapshot instead: copy the
     * main file plus durable WAL/rollback-journal truth into a unique cache directory, let SQLite recover
     * only that disposable copy, and remove it afterward. The owner's database and sidecars are never
     * opened by this method.
     */
    internal fun inspectNewerSchema(
        appContext: Context,
        databaseName: String,
        supportedSchemaVersion: Int = MimirDatabaseFactory.supportedSchemaVersion(),
    ): DatabaseOpenFailure? =
        inspectDatabaseSnapshot(
            appContext,
            databaseName,
            inspectLegacyIdentity = false,
            supportedSchemaVersion = supportedSchemaVersion,
        )

    /**
     * Full startup compatibility probe. Schemas 1–10 are additionally audited for a one-to-one legacy
     * physical identity before Room can mutate the owner database during a future v11 migration.
     */
    internal fun inspectDatabaseCompatibility(
        appContext: Context,
        databaseName: String,
        supportedSchemaVersion: Int = MimirDatabaseFactory.supportedSchemaVersion(),
    ): DatabaseOpenFailure? =
        inspectDatabaseSnapshot(
            appContext,
            databaseName,
            inspectLegacyIdentity = true,
            supportedSchemaVersion = supportedSchemaVersion,
        )

    private fun inspectDatabaseSnapshot(
        appContext: Context,
        databaseName: String,
        inspectLegacyIdentity: Boolean,
        supportedSchemaVersion: Int,
    ): DatabaseOpenFailure? {
        val file = appContext.getDatabasePath(databaseName)
        if (!file.isFile) return null
        val snapshotDirectory = createProbeDirectory(appContext)
        return try {
            val snapshot = File(snapshotDirectory, "mimir-probe.db")
            file.copyTo(snapshot)
            listOf("-wal", "-journal").forEach { suffix ->
                val sidecar = File(file.path + suffix)
                if (sidecar.isFile) {
                    sidecar.copyTo(File(snapshot.path + suffix))
                }
            }
            SQLiteDatabase.openDatabase(
                snapshot.path,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                PreserveBytesDatabaseErrorHandler,
            ).use { database ->
                val foundVersion = database.rawQuery("PRAGMA user_version", null).use { cursor ->
                    check(cursor.moveToFirst()) { "SQLite did not return PRAGMA user_version" }
                    cursor.getInt(0)
                }
                newerSchemaFailure(foundVersion, supportedSchemaVersion)
                    ?: if (
                        inspectLegacyIdentity &&
                        foundVersion in 1..LEGACY_PHYSICAL_IDENTITY_MAX_SCHEMA &&
                        !legacyPhysicalIdentityIsSafe(database)
                    ) {
                        DatabaseOpenFailure(
                            reason = DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE,
                            foundSchemaVersion = foundVersion,
                            supportedSchemaVersion = supportedSchemaVersion,
                        )
                    } else {
                        null
                    }
            }
        } finally {
            check(snapshotDirectory.deleteRecursively()) {
                "Couldn’t remove the private database compatibility snapshot"
            }
        }
    }

    private fun legacyPhysicalIdentityIsSafe(database: SQLiteDatabase): Boolean {
        if (!database.rawQuery("PRAGMA encoding", null).use { it.isUtf8Database() }) return false
        database.execSQL(
            "CREATE TEMP TABLE `mimir_legacy_identity_probe` (" +
                "`sourceRootUri` TEXT NOT NULL, `relativePath` TEXT NOT NULL, " +
                "`entryKey` TEXT NOT NULL UNIQUE, PRIMARY KEY(`sourceRootUri`, `relativePath`)) " +
                "WITHOUT ROWID",
        )
        val insert = database.compileStatement(
            "INSERT INTO `mimir_legacy_identity_probe` " +
                "(`sourceRootUri`, `relativePath`, `entryKey`) VALUES (?, ?, ?)",
        )
        return insert.use { statement ->
            database.rawQuery(LEGACY_PHYSICAL_IDENTITY_QUERY, null).use { cursor ->
                cursor.forEachLegacyPhysicalIdentity { _, mapping ->
                    try {
                        statement.clearBindings()
                        statement.bindString(1, mapping.sourceRootUri)
                        statement.bindString(2, mapping.relativePath)
                        statement.bindString(3, mapping.physicalEntryKey)
                        statement.executeInsert()
                        true
                    } catch (_: SQLiteConstraintException) {
                        false
                    }
                }
            }
        }
    }

    private fun Cursor.isUtf8Database(): Boolean {
        check(moveToFirst()) { "SQLite did not return PRAGMA encoding" }
        return getString(0) == "UTF-8"
    }

    private fun Cursor.forEachLegacyPhysicalIdentity(
        action: (LegacyPhysicalIdentityRow, LegacyPhysicalIdentityMapping) -> Boolean,
    ): Boolean {
        while (moveToNext()) {
            if (!hasTextAt(0) ||
                !hasNullOrTextAt(2) ||
                !hasTextAt(4) ||
                !hasTextAt(6) ||
                !hasNullOrTextAt(8)
            ) {
                return false
            }
            val entryKey = strictUtf8At(1) ?: return false
            val sourceRootUri = if (isNull(3)) null else strictUtf8At(3) ?: return false
            val relativePath = strictUtf8At(5) ?: return false
            val locationKind = strictUtf8At(7) ?: return false
            val managedPath = if (isNull(9)) null else strictUtf8At(9) ?: return false
            val row = LegacyPhysicalIdentityRow(
                entryKey = entryKey,
                sourceRootUri = sourceRootUri,
                relativePath = relativePath,
                locationKind = locationKind,
                managedPath = managedPath,
            )
            val mapping = mapLegacyPhysicalIdentity(row) ?: return false
            if (!action(row, mapping)) return false
        }
        return true
    }

    private fun Cursor.hasTextAt(typeColumn: Int): Boolean =
        getString(typeColumn) == "text" && getType(typeColumn + 1) == Cursor.FIELD_TYPE_BLOB

    private fun Cursor.hasNullOrTextAt(typeColumn: Int): Boolean {
        val declaredType = getString(typeColumn)
        val actualType = getType(typeColumn + 1)
        return (declaredType == "null" && actualType == Cursor.FIELD_TYPE_NULL) ||
            (declaredType == "text" && actualType == Cursor.FIELD_TYPE_BLOB)
    }

    private fun Cursor.strictUtf8At(column: Int): String? =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(getBlob(column)))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private fun prepareV11IdentityMap(database: SupportSQLiteDatabase): Boolean {
        if (!database.query("PRAGMA encoding").use { it.isUtf8Database() }) return false
        database.execSQL("DROP TABLE IF EXISTS `mimir_v11_identity_map`")
        database.execSQL(
            "CREATE TEMP TABLE `mimir_v11_identity_map` (" +
                "`oldEntryKey` TEXT NOT NULL, `sourceRootUri` TEXT NOT NULL, " +
                "`relativePath` TEXT NOT NULL, `physicalEntryKey` TEXT NOT NULL UNIQUE, " +
                "PRIMARY KEY(`oldEntryKey`), UNIQUE(`sourceRootUri`, `relativePath`)) WITHOUT ROWID",
        )
        val insert = database.compileStatement(
            "INSERT INTO `mimir_v11_identity_map` " +
                "(`oldEntryKey`, `sourceRootUri`, `relativePath`, `physicalEntryKey`) " +
                "VALUES (?, ?, ?, ?)",
        )
        return insert.use { statement ->
            database.query(LEGACY_PHYSICAL_IDENTITY_QUERY).use { cursor ->
                cursor.forEachLegacyPhysicalIdentity { row, mapping ->
                    try {
                        statement.clearBindings()
                        statement.bindString(1, row.entryKey)
                        statement.bindString(2, mapping.sourceRootUri)
                        statement.bindString(3, mapping.relativePath)
                        statement.bindString(4, mapping.physicalEntryKey)
                        statement.executeInsert()
                        true
                    } catch (_: SQLiteConstraintException) {
                        false
                    }
                }
            }
        }
    }

    private fun SupportSQLiteDatabase.scalarLong(sql: String): Long =
        query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "SQLite did not return a scalar migration result" }
            cursor.getLong(0)
        }

    private fun migratePhysicalIdentityToV11(database: SupportSQLiteDatabase) {
        if (!prepareV11IdentityMap(database)) {
            throw IllegalStateException(LEGACY_PHYSICAL_IDENTITY_MIGRATION_REFUSAL)
        }
        val sourceCount = database.scalarLong("SELECT COUNT(*) FROM `games`")
        check(
            database.scalarLong("SELECT COUNT(*) FROM `mimir_v11_identity_map`") == sourceCount,
        ) {
            LEGACY_PHYSICAL_IDENTITY_MIGRATION_REFUSAL
        }
        check(
            database.scalarLong(
                "SELECT COUNT(*) FROM `mimir_v11_identity_map` WHERE `physicalEntryKey` IS NOT (" +
                    "'p2:' || LENGTH(`sourceRootUri`) || ':' || LENGTH(`relativePath`) || ':' || " +
                    "`sourceRootUri` || `relativePath`)",
            ) == 0L,
        ) {
            LEGACY_PHYSICAL_IDENTITY_MIGRATION_REFUSAL
        }

        database.execSQL(CREATE_GAMES_V11_SQL)
        database.execSQL(
            "INSERT INTO `games_v11` (" +
                "`entryKey`, `gameId`, `platformId`, `contentKind`, `displayName`, `title`, " +
                "`region`, `version`, `serial`, `titleCatalogKey`, `identitySource`, `locationKind`, " +
                "`availability`, `sizeBytes`, `contentHash`, `sourceRootUri`, `sourceDocumentUri`, " +
                "`managedPath`, `relativePath`, `discNumber`, `duplicateGroupId`, `isPrimaryMember`, " +
                "`duplicateGroupKind`, `canonicalTitle`, `collapseKey`, `identityOrigin`, " +
                "`identityResolvedAtMillis`) " +
                "SELECT m.`physicalEntryKey`, g.`gameId`, g.`platformId`, g.`contentKind`, " +
                "g.`displayName`, g.`title`, g.`region`, g.`version`, g.`serial`, g.`titleCatalogKey`, " +
                "g.`identitySource`, g.`locationKind`, g.`availability`, g.`sizeBytes`, g.`contentHash`, " +
                "m.`sourceRootUri`, g.`sourceDocumentUri`, g.`managedPath`, m.`relativePath`, " +
                "g.`discNumber`, g.`duplicateGroupId`, g.`isPrimaryMember`, g.`duplicateGroupKind`, " +
                "g.`canonicalTitle`, g.`collapseKey`, g.`identityOrigin`, g.`identityResolvedAtMillis` " +
                "FROM `games` g JOIN `mimir_v11_identity_map` m ON m.`oldEntryKey` = g.`entryKey`",
        )
        check(database.scalarLong("SELECT COUNT(*) FROM `games_v11`") == sourceCount) {
            LEGACY_PHYSICAL_IDENTITY_MIGRATION_REFUSAL
        }
        val preservedColumns = listOf(
            "gameId",
            "platformId",
            "contentKind",
            "displayName",
            "title",
            "region",
            "version",
            "serial",
            "titleCatalogKey",
            "identitySource",
            "locationKind",
            "availability",
            "sizeBytes",
            "contentHash",
            "sourceDocumentUri",
            "managedPath",
            "discNumber",
            "duplicateGroupId",
            "isPrimaryMember",
            "duplicateGroupKind",
            "canonicalTitle",
            "collapseKey",
            "identityOrigin",
            "identityResolvedAtMillis",
        )
        val preservedPredicates = preservedColumns.joinToString(" AND ") { column ->
            "(v.`$column` IS g.`$column` AND " +
                "typeof(v.`$column`) = typeof(g.`$column`))"
        }
        check(
            database.scalarLong(
                "SELECT COUNT(*) FROM `games` g " +
                    "JOIN `mimir_v11_identity_map` m ON m.`oldEntryKey` = g.`entryKey` " +
                    "LEFT JOIN `games_v11` v ON v.`entryKey` = m.`physicalEntryKey` " +
                    "WHERE v.`entryKey` IS NULL OR NOT (" +
                    "v.`entryKey` IS m.`physicalEntryKey` AND " +
                    "typeof(v.`entryKey`) = 'text' AND " +
                    "v.`sourceRootUri` IS m.`sourceRootUri` AND " +
                    "typeof(v.`sourceRootUri`) = 'text' AND " +
                    "v.`relativePath` IS m.`relativePath` AND " +
                    "typeof(v.`relativePath`) = 'text' AND $preservedPredicates)",
            ) == 0L,
        ) {
            LEGACY_PHYSICAL_IDENTITY_MIGRATION_REFUSAL
        }

        database.execSQL("DROP TABLE `games`")
        database.execSQL("ALTER TABLE `games_v11` RENAME TO `games`")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_games_gameId` ON `games` (`gameId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_games_platformId` ON `games` (`platformId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_games_contentHash` ON `games` (`contentHash`)")
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_games_duplicateGroupId` " +
                "ON `games` (`duplicateGroupId`)",
        )
        database.execSQL(CREATE_GAMES_IDENTITY_SWEEP_INDEX_SQL)
        database.execSQL(CREATE_GAMES_SOURCE_RELATIVE_INDEX_SQL)
        database.execSQL(CREATE_GAMES_SOURCE_ENTRY_INDEX_SQL)
        database.execSQL("DROP TABLE `mimir_v11_identity_map`")
    }

    private fun createProbeDirectory(appContext: Context): File {
        val marker = File.createTempFile("mimir-db-probe-", ".tmp", appContext.cacheDir)
        check(marker.delete() && marker.mkdir()) {
            "Couldn’t create a private database compatibility snapshot"
        }
        return marker
    }

    internal fun newerSchemaFailure(
        foundSchemaVersion: Int,
        supportedSchemaVersion: Int,
    ): DatabaseOpenFailure? =
        if (foundSchemaVersion > supportedSchemaVersion) {
            DatabaseOpenFailure(
                reason = DatabaseOpenFailureReason.NEWER_SCHEMA,
                foundSchemaVersion = foundSchemaVersion,
                supportedSchemaVersion = supportedSchemaVersion,
            )
        } else {
            null
        }

    internal fun classifyRoomOpenFailure(
        error: Throwable,
        supportedSchemaVersion: Int,
    ): DatabaseOpenFailure? {
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>(),
        )
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current.message?.contains(LEGACY_PHYSICAL_IDENTITY_MIGRATION_REFUSAL) == true) {
                return DatabaseOpenFailure(
                    reason = DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE,
                    foundSchemaVersion = LEGACY_PHYSICAL_IDENTITY_MAX_SCHEMA,
                    supportedSchemaVersion = supportedSchemaVersion,
                )
            }
            val match = current.message?.let(missingMigrationPattern::find)
            if (match != null) {
                val found = match.groupValues[1].toIntOrNull()
                val target = match.groupValues[2].toIntOrNull()
                if (found != null && target == supportedSchemaVersion) {
                    newerSchemaFailure(found, supportedSchemaVersion)?.let { return it }
                }
            }
            current = current.cause
        }
        return null
    }

    internal fun supportedSchemaVersion(): Int =
        ALL_MIGRATIONS.maxOfOrNull(Migration::endVersion) ?: 1

    /**
     * The schema migration is additive; the Kotlin graph backfill runs here because Android's portable
     * SQLite floor cannot express the historical undirected closure reliably. `get` does not return until
     * the Room transaction is complete, so no Home/library reader can observe a half-backfilled v9 store.
     */
    private fun prepareLogicalIdentity(database: MimirDatabase) {
        runBlocking(Dispatchers.IO) {
            database.gameIdentityDao().backfillExistingGroups(System.currentTimeMillis())
        }
    }

    private fun build(appContext: Context): MimirDatabase =
        openPreparedDatabase(appContext, MimirDatabase.DB_NAME)

    private fun openCompatible(appContext: Context): MimirDatabase {
        inspectDatabaseCompatibility(appContext, MimirDatabase.DB_NAME)?.let { failure ->
            openRefusal.refuse(failure)
        }
        return try {
            build(appContext)
        } catch (error: Throwable) {
            val failure = classifyRoomOpenFailure(error, supportedSchemaVersion())
                ?: throw error
            openRefusal.refuse(failure, error)
        }
    }

    /**
     * Opens one uncached database and completes the same first-open v9 identity preparation as production.
     * Keeping the operation named and package-visible lets migration instrumentation prove the real
     * open→migrate→backfill sequence without mutating the process singleton.
     */
    internal fun openPreparedDatabase(
        appContext: Context,
        databaseName: String,
    ): MimirDatabase =
        databaseBuilder(appContext, databaseName)
            .build()
            .also(::prepareLogicalIdentity)

    /**
     * One builder policy for production and migration instrumentation. A database newer than this binary
     * now fails closed: Room leaves it intact and reports the missing downgrade migration. Never restore
     * `fallbackToDestructiveMigrationOnDowngrade()` here.
     */
    internal fun databaseBuilder(
        appContext: Context,
        databaseName: String,
    ): RoomDatabase.Builder<MimirDatabase> =
        Room.databaseBuilder(appContext, MimirDatabase::class.java, databaseName)
            .openHelperFactory(PreserveBytesOpenHelperFactory)
            .addMigrations(*ALL_MIGRATIONS)

    internal val ALL_MIGRATIONS: Array<Migration>
        get() = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
        )

    private object PreserveBytesDatabaseErrorHandler : DatabaseErrorHandler {
        override fun onCorruption(database: SQLiteDatabase) {
            throw SQLiteDatabaseCorruptException(
                "Mimir refused to modify a database reported as corrupt",
            )
        }
    }

    private object PreserveBytesOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
        private val delegate = FrameworkSQLiteOpenHelperFactory()

        override fun create(
            configuration: SupportSQLiteOpenHelper.Configuration,
        ): SupportSQLiteOpenHelper {
            val callback = configuration.callback
            val preservingCallback = object : SupportSQLiteOpenHelper.Callback(callback.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(db)

                override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(db)

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = callback.onUpgrade(db, oldVersion, newVersion)

                override fun onDowngrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = callback.onDowngrade(db, oldVersion, newVersion)

                override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(db)

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    throw SQLiteDatabaseCorruptException(
                        "Mimir refused destructive database corruption recovery",
                    )
                }
            }
            val guarded = SupportSQLiteOpenHelper.Configuration
                .builder(configuration.context)
                .name(configuration.name)
                .callback(preservingCallback)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(false)
                .build()
            return delegate.create(guarded)
        }
    }

    /**
     * v1 → v2: add the (all-NULLABLE) enrichment columns to `title_metadata_cache`. Pure additive
     * `ALTER TABLE … ADD COLUMN` — NON-DESTRUCTIVE: existing rows/library are preserved (the library
     * lives in other tables and is untouched). Column names/types/nullability match
     * [com.gruning.mimir.rewrite.core.storage.entity.TitleMetadataCacheEntity] exactly so Room's
     * post-migration schema validation passes.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val t = "title_metadata_cache"
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `canonicalSerial` TEXT")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `description` TEXT")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `boxArtUri` TEXT")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `screenshotUris` TEXT")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `source` TEXT")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `license` TEXT")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `lastValidatedMillis` INTEGER")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `cacheExpiresAt` INTEGER")
        }
    }

    /**
     * v2 → v3: add the (nullable) `sourceDocumentUri` to `games` so a SAF game's exact document uri is
     * persisted — needed to open the file on demand for hashing / metadata enrichment / a content-uri
     * launch. Pure additive `ALTER TABLE … ADD COLUMN`; NON-DESTRUCTIVE (existing rows keep NULL). Matches
     * [com.gruning.mimir.rewrite.core.storage.entity.GameEntity.sourceDocumentUri] for schema validation.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `games` ADD COLUMN `sourceDocumentUri` TEXT")
        }
    }

    /**
     * v3 → v4: add the (all-NULLABLE) identity-enrichment columns to `games` — the DAT/id-catalog resolved
     * canonical title, the authoritative collapse key, its provenance, and the resolve timestamp. Pure
     * additive `ALTER TABLE … ADD COLUMN`; NON-DESTRUCTIVE (existing rows keep NULL until the enricher runs).
     * Names/types match [com.gruning.mimir.rewrite.core.storage.entity.GameEntity] for schema validation.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `games` ADD COLUMN `canonicalTitle` TEXT")
            db.execSQL("ALTER TABLE `games` ADD COLUMN `collapseKey` TEXT")
            db.execSQL("ALTER TABLE `games` ADD COLUMN `identityOrigin` TEXT")
            db.execSQL("ALTER TABLE `games` ADD COLUMN `identityResolvedAtMillis` INTEGER")
        }
    }

    /**
     * v4 → v5: add the `favorites` table (user-marked favorites). Pure additive CREATE TABLE;
     * NON-DESTRUCTIVE — the existing library/identity/hide/override tables are untouched. Column
     * names/types/nullability + the PK match [com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity]
     * exactly so Room's post-migration schema validation passes.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `favorites` " +
                    "(`gameId` TEXT NOT NULL, `favoritedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`gameId`))",
            )
        }
    }

    /**
     * v5 → v6: add typed, profile-scoped primary Home Shelf membership. This is additive and has no
     * foreign key by design, so every v5 library row and every temporarily unresolved pin survives.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_HOME_SHELF_ITEMS_SQL)
            db.execSQL(CREATE_HOME_SHELF_PROFILE_RANK_INDEX_SQL)
        }
    }

    /**
     * v6 → v7: make the ROM game target nullable and add an explicit Android-package target. SQLite
     * cannot relax NOT NULL in place, so rebuild only `launch_proofs`, preserving every row and id.
     */
    /**
     * v7 → v8: add `index_games_identity_sweep`. Purely additive — one `CREATE INDEX`, no table rebuild, no
     * row touched, nothing dropped. The library survives untouched.
     *
     * SD01B took identity throughput from ~6/s to 11.1/s and named the remaining ceiling as Room-side query
     * churn: the `arch_disk_io` pool stayed busy between chunk commits while hashers were ~idle. Both
     * `unresolvedPrimaries` (the enrichment driver, once per chunk drain) and `unresolvedPrimaryCount` (a
     * Flow re-run by Room on every write to `games`, with ≥2 live collectors during a pass) share the
     * predicate `isPrimaryMember = 1 AND identityResolvedAtMillis IS NULL`, and neither column was indexed.
     * On a captured 5,538-row database SQLite reported `SCAN games` for both, plus
     * `USE TEMP B-TREE FOR ORDER BY` for the driver — roughly 111 chunks per pass.
     *
     * With this index the same database plans as `SEARCH games USING COVERING INDEX` for the counter (it
     * stops reading the table at all) and `SEARCH games USING INDEX` for the driver, with the temp B-tree
     * gone. Measured cost: 108 KB.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_GAMES_IDENTITY_SWEEP_INDEX_SQL)
        }
    }

    /**
     * v8 → v9: schema-only creation of the durable logical lineage and cross-store replay journal.
     * Existing tables and rows are untouched here. [prepareLogicalIdentity] performs the idempotent Kotlin
     * graph backfill in one Room transaction immediately after open.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_LOGICAL_GAME_GROUPS_SQL)
            db.execSQL(CREATE_LOGICAL_GAME_CURRENT_INDEX_SQL)
            db.execSQL(CREATE_GAME_ID_ALIASES_SQL)
            db.execSQL(CREATE_GAME_ID_ALIASES_LOGICAL_INDEX_SQL)
            db.execSQL(CREATE_REMAP_JOURNAL_SQL)
            db.execSQL(CREATE_REMAP_JOURNAL_PENDING_INDEX_SQL)
            db.execSQL(CREATE_IDENTITY_MAINTENANCE_SQL)
        }
    }

    /**
     * v9 → v10: additive progress ledger for one per-root-atomic multi-source reconciliation pass.
     * No existing table or row changes, and no foreign key ties a confirmed-removal tombstone back to
     * the live source registry.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_SOURCE_RECONCILE_RUNS_SQL)
            db.execSQL(CREATE_SOURCE_RECONCILE_ROOTS_SQL)
            db.execSQL(CREATE_SOURCE_RECONCILE_ROOT_ID_INDEX_SQL)
        }
    }

    internal fun migrateBiosProfilesToV12(database: SupportSQLiteDatabase) {
        database.execSQL(CREATE_BIOS_PROFILES_V12_SQL)
        database.execSQL(MIGRATE_BIOS_PROFILES_V11_TO_V12_INSERT_SQL)
        database.execSQL("DROP TABLE `bios_profiles`")
        database.execSQL("ALTER TABLE `bios_profiles_v12` RENAME TO `bios_profiles`")
    }

    /**
     * v10 → v11: replace the ambiguous `root + "::" + relativePath` scalar with a canonical p2 key,
     * make exact source ownership mandatory, and index it directly. The owner-copy preflight provides
     * the typed diagnosis; this migration repeats the strict proof inside Room's transaction so a
     * changed or bypassed database rolls back rather than guessing.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migratePhysicalIdentityToV11(db)
        }
    }

    /**
     * v11 → v12: rebuild `bios_profiles` with emulator scope and package provenance. Every legacy
     * presence value becomes UNKNOWN because v11 rows cannot prove which emulator fork produced them.
     * `platforms.requiresBios` is preserved byte-for-byte.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrateBiosProfilesToV12(db)
        }
    }

    /**
     * v12 → v13: add the `title_overrides` table (manual cosmetic rename). Pure
     * additive `CREATE TABLE`; NON-DESTRUCTIVE — every existing table and row, including `games.title` and
     * `games.canonicalTitle`, is untouched. No row is seeded: a rename is opt-in per game.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_TITLE_OVERRIDES_SQL)
        }
    }

    /**
     * v13 → v14: `source_reconcile_runs` gains `skippedByReason`, so a multi-root Rescan All can carry
     * the per-reason skip breakdown its receipt already carried counts for.
     *
     * **Additive and NON-DESTRUCTIVE.** One `ALTER TABLE ADD COLUMN` with a `NOT NULL DEFAULT ''`; no
     * table is recreated, no row is rewritten, nothing is dropped. Every pre-v14 run row reads back as
     * an empty breakdown, which is not a lie about them — those passes genuinely did not tally reasons.
     *
     * Why it exists: `LibraryRepositoryImpl` OVERRIDES `reconcileSources`, and the ledgered override
     * builds its receipt from this entity. Fixing the reason map in `core-model`'s default interface
     * method (2026-08-30) never reached production, because production takes the override. Found by
     * review, not by the passing test that pinned the default.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `source_reconcile_runs` " +
                    "ADD COLUMN `skippedByReason` TEXT NOT NULL DEFAULT ''",
            )
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_LAUNCH_PROOFS_V7_SQL)
            db.execSQL(
                "INSERT INTO `launch_proofs_v7` " +
                    "(`id`, `gameId`, `androidPackageName`, `platformId`, `emulatorId`, `locator`, " +
                    "`outcome`, `timestampMillis`) " +
                    "SELECT `id`, `gameId`, NULL, `platformId`, `emulatorId`, `locator`, `outcome`, " +
                    "`timestampMillis` FROM `launch_proofs`",
            )
            db.execSQL("DROP TABLE `launch_proofs`")
            db.execSQL("ALTER TABLE `launch_proofs_v7` RENAME TO `launch_proofs`")
            db.execSQL(CREATE_LAUNCH_PROOFS_GAME_INDEX_SQL)
            db.execSQL(CREATE_LAUNCH_PROOFS_ANDROID_PACKAGE_INDEX_SQL)
        }
    }
}

internal class DatabaseOpenRefusedException(
    val failure: DatabaseOpenFailure,
    cause: Throwable? = null,
) : IllegalStateException(
    "Mimir refused database schema ${failure.foundSchemaVersion} " +
        "reason=${failure.reason}; this build supports ${failure.supportedSchemaVersion}",
    cause,
)

/**
 * Process-lifetime fail-closed latch shared by Activity startup and every direct database borrower.
 *
 * WorkManager can create workers before [android.app.Application.onCreate]. Keeping this authority at
 * the storage boundary means a background caller can discover and latch a newer schema, but it can never
 * bypass the owner-facing startup refusal by opening Room directly.
 */
internal class DatabaseOpenRefusalLatch {
    @Volatile
    private var failure: DatabaseOpenFailure? = null

    fun throwIfRefused() {
        failure?.let { throw DatabaseOpenRefusedException(it) }
    }

    fun refuse(
        discovered: DatabaseOpenFailure,
        cause: Throwable? = null,
    ): Nothing {
        val authoritative = synchronized(this) {
            failure ?: discovered.also { failure = it }
        }
        throw DatabaseOpenRefusedException(authoritative, cause)
    }
}
