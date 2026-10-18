package com.gruning.mimir.rewrite.core.storage

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.gruning.mimir.rewrite.core.model.DisplayTitle
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.migration.MigrationBundleCodec
import com.gruning.mimir.rewrite.core.model.migration.MigrationEmulatorOverride
import com.gruning.mimir.rewrite.core.model.migration.MigrationGameRecord
import com.gruning.mimir.rewrite.core.model.migration.MigrationSourceRoot
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_COMPAT
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_CORE
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_DERIVED
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_ENTRY_BYTES
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_JSONL_LINE_BYTES
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_TABLE_ROWS
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivePayloadSource
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveTablesV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryJsonField
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryJsonLineCodec
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryJsonValue
import com.gruning.mimir.rewrite.core.model.recovery.recoveryJsonStringEncodedUpperBound
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive

/** The oldest Room schema Recovery Archive V1 will read. `internal` for the same reason as the ceiling
 * below: the semantic validator needs the range, and a validator restating it is how three copies of one
 * fact drifted apart in the first place. */
internal const val RECOVERY_SCHEMA_MIN_VERSION_V1 = 9

/**
 * The newest Room schema this exporter will read — **and the single value that silently broke every
 * export for a week.**
 *
 * It sat at 12 while [MimirDatabase] moved to 13 on 2026-08-11 (`6d829b53`, the `title_overrides`
 * table). Nothing in the build noticed. Every "Export recovery" press threw
 * `RecoveryArchiveException`, and the player was shown the raw exception text — a sentence written for
 * a developer, surfaced as if it were product copy.
 *
 * **Why the existing contract test did not catch it:** `RecoveryArchiveSchemaContractTest` enumerated
 * versions by hand — one test for 9, one for 10 and 11, one for 12. A new schema needed a human to
 * remember to add a case, and the suite went on passing while covering everything except the version
 * that actually ships. Coverage that must be extended by hand stops being coverage exactly when it
 * matters.
 *
 * `internal` rather than `private` so the drift itself is now pinned: a test asserts this equals the
 * newest committed Room schema, so the next bump fails the build instead of the export.
 */
internal const val RECOVERY_SCHEMA_MAX_VERSION_V1 = 14
private const val RECOVERY_WRITE_BUFFER_BYTES = 64 * 1024
private const val RECOVERY_COMPAT_RECORD_OVERHEAD_BYTES = 512L

private const val RECOVERY_COMPAT_GAME_SQL =
    """
    SELECT g.platformId, g.displayName, g.sizeBytes, g.relativePath, g.contentHash, g.serial,
           g.title, g.canonicalTitle, g.duplicateGroupKind,
           COALESCE(p.displayName, g.platformId) AS platformLabel
    FROM games g
    LEFT JOIN platforms p ON p.platformId = g.platformId
    WHERE (
        g.gameId = ? OR g.duplicateGroupId = ? OR
        g.gameId IN (
            SELECT a.oldGameId FROM game_id_aliases a
            WHERE a.logicalGameId = (
                SELECT seed.logicalGameId FROM game_id_aliases seed
                WHERE seed.oldGameId = ? LIMIT 1
            )
        ) OR
        g.duplicateGroupId IN (
            SELECT a.oldGameId FROM game_id_aliases a
            WHERE a.logicalGameId = (
                SELECT seed.logicalGameId FROM game_id_aliases seed
                WHERE seed.oldGameId = ? LIMIT 1
            )
        )
    )
    ORDER BY CASE WHEN g.availability = 'AVAILABLE' THEN 0 ELSE 1 END,
             g.isPrimaryMember DESC,
             CASE WHEN g.discNumber > 0 THEN 0 ELSE 1 END,
             g.discNumber ASC, g.displayName ASC,
             g.sourceRootUri ASC, g.relativePath ASC, g.entryKey ASC
    LIMIT 1
    """

data class RecoveryArchiveDatabaseRequest(
    val producedByApp: String,
    val producedByVersionName: String,
    val producedAtUtcMillis: Long,
)

data class RecoveryArchiveDatabaseSnapshot(
    val schemaVersion: Int,
    val journalMode: String,
    val quiescent: Boolean,
    val payloads: List<RecoveryArchivePayloadSource>,
)

/**
 * Typed logical export only. No SQLite/WAL/SHM or SharedPreferences bytes ever enter this path.
 *
 * All table cursors, row counts, schema version, journal mode, and the compatibility bundle are read
 * under one Room transaction. Publication and SAF I/O happen only after this function returns.
 */
object RecoveryArchiveDatabaseExporter {
    private val singleFlight = Mutex()

    suspend fun export(
        database: MimirDatabase,
        stagingDirectory: File,
        request: RecoveryArchiveDatabaseRequest,
    ): Result<RecoveryArchiveDatabaseSnapshot> {
        if (!singleFlight.tryLock()) {
            return Result.failure(RecoveryArchiveException("A recovery archive export is already running."))
        }
        val createdFiles = mutableListOf<File>()
        return try {
            Result.success(
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    prepareStagingDirectory(stagingDirectory)
                    val sqlite = database.openHelper.writableDatabase
                    val schemaVersion = sqlite.version
                    if (schemaVersion !in
                        RECOVERY_SCHEMA_MIN_VERSION_V1..RECOVERY_SCHEMA_MAX_VERSION_V1
                    ) {
                        throw RecoveryArchiveException(
                            "Recovery Archive V1 supports Room schemas " +
                                "$RECOVERY_SCHEMA_MIN_VERSION_V1..$RECOVERY_SCHEMA_MAX_VERSION_V1; " +
                                "found $schemaVersion.",
                        )
                    }
                    val journalMode = sqlite.query("PRAGMA journal_mode").use { cursor ->
                        if (!cursor.moveToFirst()) {
                            throw RecoveryArchiveException("Couldn’t read the database journal mode.")
                        }
                        cursor.getString(0).orEmpty().ifBlank { "unknown" }
                    }
                    val tableSpecs = recoveryTableSpecsForSchema(schemaVersion)
                    val payloads = ArrayList<RecoveryArchivePayloadSource>(
                        tableSpecs.size + 1,
                    )
                    tableSpecs.forEach { spec ->
                        currentCoroutineContext().ensureActive()
                        val target = File(stagingDirectory, spec.path)
                        target.parentFile?.mkdirs()
                        createdFiles += target
                        val rowCount = writeTable(sqlite, spec, target)
                        payloads += RecoveryArchivePayloadSource(
                            path = spec.path,
                            contentClass = spec.contentClass,
                            rowCount = rowCount,
                            file = target,
                        )
                    }
                    val compatibilityFile = File(
                        stagingDirectory,
                        RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH,
                    )
                    compatibilityFile.parentFile?.mkdirs()
                    createdFiles += compatibilityFile
                    writeCompatibilityBundle(
                        sqlite = sqlite,
                        request = request,
                        target = compatibilityFile,
                    )
                    payloads += RecoveryArchivePayloadSource(
                        path = RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH,
                        contentClass = RECOVERY_CONTENT_COMPAT,
                        file = compatibilityFile,
                    )
                    RecoveryArchiveDatabaseSnapshot(
                        schemaVersion = schemaVersion,
                        journalMode = journalMode,
                        // EP-06A has no proven scan/import/identity-maintenance idle authority.
                        quiescent = false,
                        payloads = payloads,
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            createdFiles.asReversed().forEach(File::delete)
            stagingDirectory.resolve("db").delete()
            stagingDirectory.resolve("settings").delete()
            throw cancelled
        } catch (error: Throwable) {
            createdFiles.asReversed().forEach(File::delete)
            stagingDirectory.resolve("db").delete()
            stagingDirectory.resolve("settings").delete()
            Result.failure(error)
        } finally {
            singleFlight.unlock()
        }
    }

    private suspend fun writeTable(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        spec: RecoveryTableSpec,
        target: File,
    ): Long {
        preflightTableTextBytes(database, spec)
        val sql = buildString {
            append("SELECT ")
            append(spec.columns.joinToString(",") { "`${it.name}`" })
            append(" FROM `")
            append(spec.tableName)
            append("` ORDER BY ")
            append(spec.primaryKey.joinToString(",") { "`$it` ASC" })
        }
        var rows = 0L
        var totalBytes = 0L
        var previousPrimaryKey: List<String?>? = null
        FileOutputStream(target).use { fileOutput ->
            val buffered = BufferedOutputStream(fileOutput, RECOVERY_WRITE_BUFFER_BYTES)
            database.query(sql).use { cursor ->
                val columnIndices = spec.columns.map { cursor.getColumnIndexOrThrow(it.name) }
                val primaryIndices = spec.primaryKey.map(cursor::getColumnIndexOrThrow)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    rows++
                    if (rows > RECOVERY_MAX_TABLE_ROWS) {
                        throw RecoveryArchiveException(
                            "Recovery table ${spec.tableName} exceeds the row limit.",
                        )
                    }
                    val primaryKey = primaryIndices.map { index ->
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    }
                    if (primaryKey == previousPrimaryKey) {
                        throw RecoveryArchiveException(
                            "Recovery table ${spec.tableName} contains a duplicate primary key.",
                        )
                    }
                    previousPrimaryKey = primaryKey
                    val line = RecoveryJsonLineCodec.encode(
                        spec.columns.mapIndexed { index, column ->
                            RecoveryJsonField(
                                name = column.name,
                                value = cursor.recoveryValue(columnIndices[index], column),
                            )
                        },
                    )
                    totalBytes = Math.addExact(totalBytes, line.size.toLong())
                    if (totalBytes > RECOVERY_MAX_ENTRY_BYTES) {
                        throw RecoveryArchiveException(
                            "Recovery table ${spec.tableName} exceeds the payload limit.",
                        )
                    }
                    buffered.write(line)
                }
            }
            buffered.flush()
            fileOutput.fd.sync()
        }
        syncDirectory(target.parentFile)
        return rows
    }

    private suspend fun writeCompatibilityBundle(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        request: RecoveryArchiveDatabaseRequest,
        target: File,
    ) {
        fun recordFor(gameId: String): MigrationGameRecord? =
            sqlite.query(
                SimpleSQLiteQuery(
                    RECOVERY_COMPAT_GAME_SQL,
                    arrayOf(gameId, gameId, gameId, gameId),
                ),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val rawTitle = if (cursor.isNull(7)) cursor.getString(6) else cursor.getString(7)
                val groupKind = cursor.getStringOrNull(8)?.let { stored ->
                    DuplicateGroupKind.entries.firstOrNull { it.name == stored }
                }
                MigrationGameRecord(
                    platformId = cursor.getString(0),
                    fileName = cursor.getString(1),
                    sizeBytes = cursor.getLong(2),
                    relativePath = cursor.getStringOrNull(3),
                    contentHash = cursor.getStringOrNull(4),
                    serial = cursor.getStringOrNull(5),
                    title = DisplayTitle.canonicalForGroup(rawTitle, groupKind),
                    platformLabel = cursor.getString(9),
                )
            }

        FileOutputStream(target).use { fileOutput ->
            val output = BufferedOutputStream(fileOutput, RECOVERY_WRITE_BUFFER_BYTES)
            var bytesWritten = 0L

            fun write(text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                bytesWritten = Math.addExact(bytesWritten, bytes.size.toLong())
                if (bytesWritten > RECOVERY_MAX_ENTRY_BYTES) {
                    throw RecoveryArchiveException(
                        "Recovery compatibility bundle exceeds the payload limit.",
                    )
                }
                output.write(bytes)
            }

            fun writeRecord(record: Any) {
                ensureCompatibilityRecordBound(record)
                val encoded = when (record) {
                    is MigrationSourceRoot -> MigrationBundleCodec.json.encodeToString(
                        MigrationSourceRoot.serializer(),
                        record,
                    )
                    is MigrationGameRecord -> MigrationBundleCodec.json.encodeToString(
                        MigrationGameRecord.serializer(),
                        record,
                    )
                    is MigrationEmulatorOverride -> MigrationBundleCodec.json.encodeToString(
                        MigrationEmulatorOverride.serializer(),
                        record,
                    )
                    else -> error("Unsupported recovery compatibility record.")
                }
                if (encoded.toByteArray(Charsets.UTF_8).size > RECOVERY_MAX_JSONL_LINE_BYTES) {
                    throw RecoveryArchiveException(
                        "Recovery compatibility record exceeds the supported limit.",
                    )
                }
                write(encoded)
            }

            suspend fun writeGameRecords(sql: String) {
                write("[")
                var first = true
                sqlite.query(sql).use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val record = recordFor(cursor.getString(0)) ?: continue
                        if (!first) write(",")
                        writeRecord(record)
                        first = false
                    }
                }
                write("]")
            }

            write(
                "{\"bundleVersion\":1,\"kind\":\"mimir.migration.bundle\"," +
                    "\"producedByApp\":",
            )
            write(JsonPrimitive(request.producedByApp).toString())
            write(",\"producedByVersion\":")
            write(JsonPrimitive(request.producedByVersionName).toString())
            write(",\"producedAtMillis\":${request.producedAtUtcMillis},\"sourceRoots\":[")
            var firstRoot = true
            sqlite.query(
                "SELECT `friendlyLabel`, `rootUri`, `isPrimarySource` " +
                    "FROM `source_roots` ORDER BY `rootId` ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (!firstRoot) write(",")
                    writeRecord(
                        MigrationSourceRoot(
                            friendlyLabel = cursor.getString(0),
                            treeUriHint = cursor.getString(1),
                            isPrimary = cursor.getLong(2) == 1L,
                        ),
                    )
                    firstRoot = false
                }
            }
            write("],\"favorites\":")
            writeGameRecords("SELECT `gameId` FROM `favorites` ORDER BY `gameId` ASC")
            write(",\"hidden\":")
            writeGameRecords(
                "SELECT `gameId` FROM `hidden_items` WHERE `hidden` = 1 ORDER BY `gameId` ASC",
            )
            write(",\"emulatorOverrides\":[")
            var firstOverride = true
            sqlite.query(
                "SELECT `gameId`, `emulatorId` FROM `emulator_overrides` " +
                    "WHERE TRIM(`emulatorId`) <> '' ORDER BY `gameId` ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val game = recordFor(cursor.getString(0)) ?: continue
                    if (!firstOverride) write(",")
                    writeRecord(
                        MigrationEmulatorOverride(
                            game = game,
                            emulatorId = cursor.getString(1),
                        ),
                    )
                    firstOverride = false
                }
            }
            write("]}")
            output.flush()
            fileOutput.fd.sync()
        }
        syncDirectory(target.parentFile)
    }
}

internal enum class RecoveryColumnKind {
    TEXT,
    INTEGER,
    BOOLEAN,
}

internal data class RecoveryColumnSpec(
    val name: String,
    val kind: RecoveryColumnKind,
    val nullable: Boolean = false,
)

internal data class RecoveryTableSpec(
    val tableName: String,
    val path: String,
    val contentClass: String,
    val primaryKey: List<String>,
    val columns: List<RecoveryColumnSpec>,
)

private fun text(name: String, nullable: Boolean = false) =
    RecoveryColumnSpec(name, RecoveryColumnKind.TEXT, nullable)

private fun integer(name: String, nullable: Boolean = false) =
    RecoveryColumnSpec(name, RecoveryColumnKind.INTEGER, nullable)

private fun boolean(name: String) = RecoveryColumnSpec(name, RecoveryColumnKind.BOOLEAN)

internal val RECOVERY_TABLE_SPECS_V9: List<RecoveryTableSpec> = listOf(
    RecoveryTableSpec(
        "games",
        "db/games.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("entryKey"),
        listOf(
            text("entryKey"),
            text("gameId"),
            text("platformId"),
            text("contentKind"),
            text("displayName"),
            text("title"),
            text("region", nullable = true),
            text("version", nullable = true),
            text("serial", nullable = true),
            text("titleCatalogKey", nullable = true),
            text("identitySource"),
            text("locationKind"),
            text("availability"),
            integer("sizeBytes"),
            text("contentHash", nullable = true),
            text("sourceRootUri", nullable = true),
            text("sourceDocumentUri", nullable = true),
            text("managedPath", nullable = true),
            text("relativePath"),
            integer("discNumber"),
            text("duplicateGroupId", nullable = true),
            boolean("isPrimaryMember"),
            text("duplicateGroupKind", nullable = true),
            text("canonicalTitle", nullable = true),
            text("collapseKey", nullable = true),
            text("identityOrigin", nullable = true),
            integer("identityResolvedAtMillis", nullable = true),
        ),
    ),
    RecoveryTableSpec(
        "platforms",
        "db/platforms.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("platformId"),
        listOf(
            text("platformId"),
            text("displayName"),
            integer("gamesCount"),
            boolean("requiresBios"),
            text("catalogLongId", nullable = true),
        ),
    ),
    RecoveryTableSpec(
        "source_roots",
        "db/source_roots.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("rootId"),
        listOf(
            text("rootId"),
            text("rootUri"),
            text("locationKind"),
            text("friendlyLabel"),
            boolean("isPrimarySource"),
            integer("lastSeenMillis"),
        ),
    ),
    RecoveryTableSpec(
        "hidden_items",
        "db/hidden_items.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("gameId"),
        listOf(
            text("gameId"),
            boolean("hidden"),
            text("reason", nullable = true),
            text("duplicateGroupId", nullable = true),
            boolean("isPrimaryMember"),
            integer("hiddenAtMillis"),
        ),
    ),
    RecoveryTableSpec(
        "emulator_overrides",
        "db/emulator_overrides.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("gameId"),
        listOf(text("gameId"), text("emulatorId"), integer("setAtMillis")),
    ),
    RecoveryTableSpec(
        "favorites",
        "db/favorites.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("gameId"),
        listOf(text("gameId"), integer("favoritedAtMillis")),
    ),
    RecoveryTableSpec(
        "home_shelf_items",
        "db/home_shelf_items.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("profileId", "itemKind", "itemId"),
        listOf(
            text("profileId"),
            text("itemKind"),
            text("itemId"),
            integer("rank"),
            integer("addedAtMillis"),
        ),
    ),
    RecoveryTableSpec(
        "launch_proofs",
        "db/launch_proofs.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("id"),
        listOf(
            integer("id"),
            text("gameId", nullable = true),
            text("androidPackageName", nullable = true),
            text("platformId"),
            text("emulatorId"),
            text("locator"),
            text("outcome"),
            integer("timestampMillis"),
        ),
    ),
    RecoveryTableSpec(
        "bios_profiles",
        "db/bios_profiles.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("platformId", "fileName"),
        listOf(
            text("platformId"),
            text("fileName"),
            boolean("required"),
            text("presence"),
            text("expectedHash", nullable = true),
            text("expectedRelativePath", nullable = true),
        ),
    ),
    RecoveryTableSpec(
        "title_metadata_cache",
        "db/title_metadata_cache.jsonl",
        RECOVERY_CONTENT_DERIVED,
        listOf("catalogKey"),
        listOf(
            text("catalogKey"),
            text("platformId"),
            text("canonicalTitle"),
            text("region", nullable = true),
            text("version", nullable = true),
            integer("cachedAtMillis"),
            text("canonicalSerial", nullable = true),
            text("description", nullable = true),
            text("boxArtUri", nullable = true),
            text("screenshotUris", nullable = true),
            text("source", nullable = true),
            text("license", nullable = true),
            integer("lastValidatedMillis", nullable = true),
            integer("cacheExpiresAt", nullable = true),
        ),
    ),
    RecoveryTableSpec(
        "logical_game_groups",
        "db/logical_game_groups.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("logicalGameId"),
        listOf(
            text("logicalGameId"),
            text("currentGameId"),
            text("groupKind"),
            boolean("memberless"),
            integer("createdAtMillis"),
            integer("updatedAtMillis"),
        ),
    ),
    RecoveryTableSpec(
        "game_id_aliases",
        "db/game_id_aliases.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("oldGameId"),
        listOf(
            text("oldGameId"),
            text("logicalGameId"),
            integer("firstSeenMillis"),
            integer("retiredAtMillis", nullable = true),
        ),
    ),
    RecoveryTableSpec(
        "remap_journal",
        "db/remap_journal.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("journalId"),
        listOf(
            integer("journalId"),
            text("logicalGameId"),
            text("fromGameId"),
            text("toGameId"),
            text("reason"),
            integer("createdAtMillis"),
            integer("storesCompletedMask"),
            integer("consumedAtMillis", nullable = true),
            text("lastError", nullable = true),
        ),
    ),
    RecoveryTableSpec(
        "identity_maintenance",
        "db/identity_maintenance.jsonl",
        RECOVERY_CONTENT_CORE,
        listOf("maintenanceKey"),
        listOf(text("maintenanceKey"), integer("completedAtMillis")),
    ),
)

/** Room v12 emulator-scoped BIOS presence exported for audit/preservation only. */
internal val RECOVERY_BIOS_PROFILES_V12 = RecoveryTableSpec(
    "bios_profiles",
    "db/bios_profiles.jsonl",
    RECOVERY_CONTENT_CORE,
    listOf("platformId", "emulatorScopeKey", "fileName"),
    listOf(
        text("platformId"),
        text("emulatorScopeKey"),
        text("packageName", nullable = true),
        text("fileName"),
        boolean("required"),
        text("presence"),
        text("expectedHash", nullable = true),
        text("expectedRelativePath", nullable = true),
    ),
)

internal val RECOVERY_TABLE_SPECS_V12: List<RecoveryTableSpec> =
    RECOVERY_TABLE_SPECS_V9.map { spec ->
        if (spec.tableName == "bios_profiles") RECOVERY_BIOS_PROFILES_V12 else spec
    }

/**
 * Room v13's cosmetic rename table — **renames survive a restore.**
 *
 * `RECOVERY_CONTENT_CORE`, not `DERIVED`, and the distinction is the whole reason this table is here: a
 * rename is something the player *typed*. No rescan rebuilds it and no scrape recovers it, so a backup
 * that omits it hands back a library with the player's own words missing and says nothing about it.
 *
 * Its archive path is **optional** rather than required — see `RecoveryArchiveTablesV1.optionalPaths`.
 * Archives created before this table was added omit it, and making the payload required would have made
 * this fix break restore for exactly the archives it was meant to protect.
 */
internal val RECOVERY_TITLE_OVERRIDES_V13 = RecoveryTableSpec(
    "title_overrides",
    "db/title_overrides.jsonl",
    RECOVERY_CONTENT_CORE,
    listOf("gameId"),
    listOf(text("gameId"), text("title"), integer("setAtMillis")),
)

internal val RECOVERY_TABLE_SPECS_V13: List<RecoveryTableSpec> =
    RECOVERY_TABLE_SPECS_V12 + RECOVERY_TITLE_OVERRIDES_V13

/**
 * **v14 exports exactly what v13 exports, and that is a checked answer rather than a shortcut.**
 *
 * Schema 14 added one column: `source_reconcile_runs.skippedByReason`. That table is a transient
 * reconcile LEDGER — it is not owner state, and it has never appeared in any spec set here (grep
 * `source_reconcile` in this file: nothing). A player restoring from an archive wants their games,
 * favourites, hidden rows and title overrides back; they do not want the bookkeeping of a rescan that
 * finished months ago. So there is nothing new to project, and the set is deliberately aliased rather
 * than copied so a future column on an EXPORTED table cannot be absorbed silently.
 *
 * This is the "extend the projection coverage, then raise the ceiling" step that
 * `RecoveryArchiveSchemaContractTest` demands — done, not skipped. The ceiling was not raised alone.
 */
internal val RECOVERY_TABLE_SPECS_V14: List<RecoveryTableSpec> = RECOVERY_TABLE_SPECS_V13

internal fun recoveryTableSpecsForSchema(schemaVersion: Int): List<RecoveryTableSpec> =
    when (schemaVersion) {
        in 9..11 -> RECOVERY_TABLE_SPECS_V9
        12 -> RECOVERY_TABLE_SPECS_V12
        13 -> RECOVERY_TABLE_SPECS_V13
        14 -> RECOVERY_TABLE_SPECS_V14
        else -> throw RecoveryArchiveException(
            "Recovery Archive V1 has no table spec for Room schema $schemaVersion.",
        )
    }

private fun Cursor.recoveryValue(
    index: Int,
    column: RecoveryColumnSpec,
): RecoveryJsonValue? {
    if (isNull(index)) {
        if (!column.nullable) {
            throw RecoveryArchiveException(
                "Recovery table row has null in required column ${column.name}.",
            )
        }
        return null
    }
    val actualType = getType(index)
    return when (column.kind) {
        RecoveryColumnKind.TEXT -> {
            if (actualType != Cursor.FIELD_TYPE_STRING) {
                throw RecoveryArchiveException(
                    "Recovery table column ${column.name} is not stored as text.",
                )
            }
            RecoveryJsonValue.Text(getString(index))
        }
        RecoveryColumnKind.INTEGER -> {
            if (actualType != Cursor.FIELD_TYPE_INTEGER) {
                throw RecoveryArchiveException(
                    "Recovery table column ${column.name} is not stored as an integer.",
                )
            }
            RecoveryJsonValue.Integer(getLong(index))
        }
        RecoveryColumnKind.BOOLEAN -> {
            if (actualType != Cursor.FIELD_TYPE_INTEGER || getLong(index) !in 0L..1L) {
                throw RecoveryArchiveException(
                    "Recovery table column ${column.name} is not a canonical boolean.",
                )
            }
            RecoveryJsonValue.BooleanValue(getLong(index) == 1L)
        }
    }
}

private fun preflightTableTextBytes(
    database: androidx.sqlite.db.SupportSQLiteDatabase,
    spec: RecoveryTableSpec,
) {
    val textColumns = spec.columns.filter { it.kind == RecoveryColumnKind.TEXT }
    if (textColumns.isEmpty()) return
    val aggregate = textColumns.joinToString(" + ") { column ->
        "COALESCE(LENGTH(CAST(`${column.name}` AS BLOB)), 0)"
    }
    val sql = "SELECT MAX($aggregate) FROM `${spec.tableName}`"
    val maximumRawTextBytes = database.query(sql).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
    }
    if (maximumRawTextBytes > RECOVERY_MAX_JSONL_LINE_BYTES) {
        throw RecoveryArchiveException(
            "Recovery table ${spec.tableName} contains a row too large to export safely.",
        )
    }
}

private fun ensureCompatibilityRecordBound(record: Any) {
    val upperBound = compatibilityRecordUpperBound(record)
    if (upperBound > RECOVERY_MAX_JSONL_LINE_BYTES) {
        throw RecoveryArchiveException(
            "Recovery compatibility record exceeds the supported limit.",
        )
    }
}

private fun compatibilityRecordUpperBound(record: Any): Long {
    var bytes = RECOVERY_COMPAT_RECORD_OVERHEAD_BYTES

    fun add(value: String?) {
        if (value != null) {
            bytes = Math.addExact(bytes, recoveryJsonStringEncodedUpperBound(value))
        }
    }

    when (record) {
        is MigrationSourceRoot -> {
            add(record.friendlyLabel)
            add(record.treeUriHint)
        }
        is MigrationGameRecord -> {
            add(record.platformId)
            add(record.fileName)
            add(record.relativePath)
            add(record.contentHash)
            add(record.serial)
            add(record.title)
            add(record.platformLabel)
        }
        is MigrationEmulatorOverride -> {
            bytes = Math.addExact(bytes, compatibilityRecordUpperBound(record.game))
            add(record.emulatorId)
        }
        else -> throw RecoveryArchiveException("Unsupported recovery compatibility record.")
    }
    return bytes
}

private fun Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun prepareStagingDirectory(directory: File) {
    if (Files.isSymbolicLink(directory.toPath())) {
        throw RecoveryArchiveException("Recovery staging directory cannot be a symbolic link.")
    }
    if (directory.exists()) {
        if (!directory.isDirectory || directory.list().orEmpty().isNotEmpty()) {
            throw RecoveryArchiveException("Recovery staging directory must be new or empty.")
        }
    } else if (!directory.mkdirs()) {
        throw RecoveryArchiveException("Couldn’t create the recovery staging directory.")
    }
}

private fun syncDirectory(directory: File?) {
    if (directory == null || !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) {
        throw RecoveryArchiveException("Recovery staging parent is not a safe directory.")
    }
    FileChannel.open(directory.toPath(), READ).use { channel -> channel.force(true) }
}
