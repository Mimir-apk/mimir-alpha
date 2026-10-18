package com.gruning.mimir.rewrite.core.storage

import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveTablesV1
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryArchiveSchemaContractTest {
    @Test
    fun `Recovery Archive V1 allowlist exactly pins committed Room schema 9`() {
        val schemaFile = sequenceOf(
            File("schemas/com.gruning.mimir.rewrite.core.storage.MimirDatabase/9.json"),
            File("core-storage/schemas/com.gruning.mimir.rewrite.core.storage.MimirDatabase/9.json"),
        ).firstOrNull(File::isFile) ?: error("Could not locate committed Room schema 9.")
        val database = Json.parseToJsonElement(schemaFile.readText())
            .jsonObject.getValue("database").jsonObject
        assertEquals(9, database.getValue("version").jsonPrimitive.content.toInt())
        val entities = database.getValue("entities").jsonArray
        assertEquals(14, entities.size)
        assertEquals(
            entities.map { it.jsonObject.getValue("tableName").jsonPrimitive.content },
            RECOVERY_TABLE_SPECS_V9.map(RecoveryTableSpec::tableName),
        )
        assertEquals(
            RecoveryArchiveTablesV1.paths,
            RECOVERY_TABLE_SPECS_V9.map(RecoveryTableSpec::path),
        )

        entities.zip(RECOVERY_TABLE_SPECS_V9).forEach { (rawEntity, spec) ->
            val entity = rawEntity.jsonObject
            val fields = entity.getValue("fields").jsonArray
            val schemaColumns = fields.map {
                it.jsonObject.getValue("columnName").jsonPrimitive.content
            }
            assertEquals(spec.tableName, schemaColumns, spec.columns.map(RecoveryColumnSpec::name))
            fields.zip(spec.columns).forEach { (rawField, column) ->
                val affinity = rawField.jsonObject.getValue("affinity").jsonPrimitive.content
                val expectedAffinity = when (column.kind) {
                    RecoveryColumnKind.TEXT -> "TEXT"
                    RecoveryColumnKind.INTEGER,
                    RecoveryColumnKind.BOOLEAN,
                    -> "INTEGER"
                }
                assertEquals("${spec.tableName}.${column.name}", expectedAffinity, affinity)
                assertFalse("${spec.tableName}.${column.name} must never be BLOB", affinity == "BLOB")
                val notNull = rawField.jsonObject.getValue("notNull").jsonPrimitive.boolean
                assertEquals(
                    "${spec.tableName}.${column.name} nullability",
                    !column.nullable,
                    notNull,
                )
            }
            val primaryKey = entity.getValue("primaryKey").jsonObject
                .getValue("columnNames").jsonArray
                .map { it.jsonPrimitive.content }
            assertEquals(spec.tableName, primaryKey, spec.primaryKey)
        }
    }

    @Test
    fun `Room schemas 10 and 11 preserve the Recovery Archive V1 table projection`() {
        listOf(10, 11).forEach { version ->
            val schemaFile = sequenceOf(
                File("schemas/com.gruning.mimir.rewrite.core.storage.MimirDatabase/$version.json"),
                File("core-storage/schemas/com.gruning.mimir.rewrite.core.storage.MimirDatabase/$version.json"),
            ).firstOrNull(File::isFile) ?: error("Could not locate committed Room schema $version.")
            val database = Json.parseToJsonElement(schemaFile.readText())
                .jsonObject.getValue("database").jsonObject
            assertEquals(version, database.getValue("version").jsonPrimitive.content.toInt())
            val entitiesByTable = database.getValue("entities").jsonArray.associateBy {
                it.jsonObject.getValue("tableName").jsonPrimitive.content
            }
            assertTrue("v$version reconciliation ledger missing", "source_reconcile_runs" in entitiesByTable)
            assertTrue("v$version reconciliation roots missing", "source_reconcile_roots" in entitiesByTable)

            RECOVERY_TABLE_SPECS_V9.forEach { spec ->
                val entity = requireNotNull(entitiesByTable[spec.tableName]) {
                    "Recovery table ${spec.tableName} disappeared from Room schema $version."
                }.jsonObject
                val schemaColumns = entity.getValue("fields").jsonArray.map {
                    it.jsonObject.getValue("columnName").jsonPrimitive.content
                }
                assertEquals(spec.tableName, spec.columns.map(RecoveryColumnSpec::name), schemaColumns)
            }
        }
        assertTrue(
            "Transient reconciliation ledger must not enter Recovery Archive V1",
            "db/source_reconcile_runs.jsonl" !in RecoveryArchiveTablesV1.paths,
        )
        assertTrue(
            "Transient reconciliation roots must not enter Recovery Archive V1",
            "db/source_reconcile_roots.jsonl" !in RecoveryArchiveTablesV1.paths,
        )
    }

    /**
     * **The drift gate.** Derived, never enumerated — that distinction is the whole point of this test.
     *
     * Its siblings each pin one schema by number, so covering a new one required a human to remember.
     * Nobody did for 13, and `RECOVERY_SCHEMA_MAX_VERSION_V1` stayed at 12 while the database shipped
     * at 13 — every export threw, and the raw exception string reached the player as a Toast.
     *
     * This reads the schema directory instead of naming a version, so schema 14 fails **here**, at
     * build time, rather than on the reference device. Failing does not mean the new schema is unsafe; it means
     * someone must look, extend the projection coverage below, and then raise the ceiling deliberately.
     */
    @Test
    fun `the exporter ceiling tracks the newest committed Room schema`() {
        val schemaDirectory = sequenceOf(
            File("schemas/com.gruning.mimir.rewrite.core.storage.MimirDatabase"),
            File("core-storage/schemas/com.gruning.mimir.rewrite.core.storage.MimirDatabase"),
        ).firstOrNull(File::isDirectory) ?: error("Could not locate the committed Room schema directory.")
        val newestCommitted = schemaDirectory.listFiles().orEmpty()
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .maxOrNull() ?: error("No committed Room schema files found.")
        assertEquals(
            "Room ships schema $newestCommitted but Recovery Archive V1 stops at " +
                "$RECOVERY_SCHEMA_MAX_VERSION_V1, so every export throws and the player is shown the " +
                "raw exception. Extend the projection coverage for $newestCommitted, then raise the " +
                "ceiling -- do not raise it alone.",
            newestCommitted,
            RECOVERY_SCHEMA_MAX_VERSION_V1,
        )

        // The ceiling alone is NOT enough, and believing it was is how the first version of this fix
        // shipped a false claim. `recoveryTableSpecsForSchema` gates the export a second time, thirty
        // lines further down the same call, and threw for 13 with the ceiling already raised -- so
        // export stayed broken and only the error message changed. Assert the whole path opens.
        val specs = recoveryTableSpecsForSchema(newestCommitted)
        assertTrue(
            "Schema $newestCommitted passes the ceiling but has no table spec set, so export still " +
                "throws -- one gate further in than the one above.",
            specs.isNotEmpty(),
        )
    }

    /**
     * All recovery schema checks must reference shared bounds. There are five checks: the exporter,
     * two in the semantic validator and two in `RecoveryArchiveRestoreReader` before delegation.
     * Correcting only the first three left export working while restore rejected its own fresh archive.
     *
     * Scan recovery sources for comparisons of `schemaVersion` with a bare integer. Schema numerals
     * belong only in `RecoveryArchiveDatabaseExporter.kt`, which defines the bounds and per-version
     * table. Any other source must reference those bounds, including newly added files.
     */
    @Test
    fun `no recovery source restates a schema bound as a literal`() {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/storage"),
            File("core-storage/src/main/kotlin/com/gruning/mimir/rewrite/core/storage"),
        ).firstOrNull(File::isDirectory) ?: error("Could not locate the core-storage sources.")

        // The one file allowed to write schema numerals: it declares the bounds and maps each supported
        // schema to its table spec, which is per-version by nature and not a bound.
        val declaresTheBounds = "RecoveryArchiveDatabaseExporter.kt"
        val comparison = Regex("""schemaVersion\s*(!in|\bin\b|>=|<=|>|<|==)\s*\d""")

        val offenders = root.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".kt") && it.name != declaresTheBounds }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (comparison.containsMatchIn(line)) "${file.name}:${index + 1}: ${line.trim()}" else null
                }
            }

        assertEquals(
            "A recovery source compares schemaVersion against a literal. That is how the ceiling drifted " +
                "for a week and how the restore path was then left behind by its own fix. Reference " +
                "RECOVERY_SCHEMA_MIN_VERSION_V1..RECOVERY_SCHEMA_MAX_VERSION_V1 instead:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * **A new table payload must never reach backwards and invalidate an archive already written.**
     *
     * `requiredPaths` is enforced on read. Adding `title_overrides` to `paths` would have made every
     * backup the player already holds fail with *"omits required V1 payloads"* — a fix for losing data
     * that loses data. It belongs in `optionalPaths`, which is classified but never demanded.
     */
    @Test
    fun `payloads added after V1 are classified but never required`() {
        assertTrue(
            "title_overrides must be a known payload",
            "db/title_overrides.jsonl" in RecoveryArchiveTablesV1.allPaths,
        )
        assertFalse(
            "title_overrides must NOT be required -- every pre-2026-08-11 archive lacks it",
            "db/title_overrides.jsonl" in RecoveryArchiveTablesV1.requiredPaths,
        )
        assertEquals(
            "An archive written before this table existed must still list every required payload",
            emptySet<String>(),
            RecoveryArchiveTablesV1.requiredPaths -
                (RECOVERY_TABLE_SPECS_V12.map(RecoveryTableSpec::path).toSet() +
                    RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH),
        )
    }

    /** The cosmetic rename is the player's own words — CORE, never DERIVED, and never dropped silently. */
    @Test
    fun `Room schema 13 exports the cosmetic renames as core content`() {
        val spec = RECOVERY_TABLE_SPECS_V13.single { it.tableName == "title_overrides" }
        assertEquals(RECOVERY_TITLE_OVERRIDES_V13, spec)
        assertEquals(listOf("gameId"), spec.primaryKey)
        assertEquals(
            listOf("gameId", "title", "setAtMillis"),
            spec.columns.map(RecoveryColumnSpec::name),
        )
        assertEquals(
            "Everything schema 12 exported must still be exported at 13",
            RECOVERY_TABLE_SPECS_V12,
            RECOVERY_TABLE_SPECS_V13.filterNot { it.tableName == "title_overrides" },
        )
    }

    @Test
    fun `Room schema 13 preserves the Recovery Archive V1 table projection`() {
        val schemaFile = sequenceOf(
            File("schemas/com.gruning.mimir.rewrite.core.storage.MimirDatabase/13.json"),
            File("core-storage/schemas/com.gruning.mimir.rewrite.core.storage.MimirDatabase/13.json"),
        ).firstOrNull(File::isFile) ?: error("Could not locate committed Room schema 13.")
        val database = Json.parseToJsonElement(schemaFile.readText())
            .jsonObject.getValue("database").jsonObject
        assertEquals(13, database.getValue("version").jsonPrimitive.content.toInt())
        val entitiesByTable = database.getValue("entities").jsonArray.associateBy {
            it.jsonObject.getValue("tableName").jsonPrimitive.content
        }
        // Schema 13 added `title_overrides` and nothing else. The exporter reads a fixed table list, so
        // a new table cannot break an existing SELECT -- which is exactly why the ceiling was the only
        // thing standing between this build and a working export.
        assertTrue("v13 title_overrides missing", "title_overrides" in entitiesByTable)
        RECOVERY_TABLE_SPECS_V12.forEach { spec ->
            val entity = requireNotNull(entitiesByTable[spec.tableName]) {
                "Recovery table ${spec.tableName} disappeared from Room schema 13."
            }.jsonObject
            val schemaColumns = entity.getValue("fields").jsonArray.map {
                it.jsonObject.getValue("columnName").jsonPrimitive.content
            }
            assertEquals(spec.tableName, spec.columns.map(RecoveryColumnSpec::name), schemaColumns)
        }
    }

    @Test
    fun `Room schema 12 exports emulator-scoped bios_profiles for Recovery Archive V1`() {
        val schemaFile = sequenceOf(
            File("schemas/com.gruning.mimir.rewrite.core.storage.MimirDatabase/12.json"),
            File("core-storage/schemas/com.gruning.mimir.rewrite.core.storage.MimirDatabase/12.json"),
        ).firstOrNull(File::isFile) ?: error("Could not locate committed Room schema 12.")
        val database = Json.parseToJsonElement(schemaFile.readText())
            .jsonObject.getValue("database").jsonObject
        assertEquals(12, database.getValue("version").jsonPrimitive.content.toInt())
        val biosEntity = database.getValue("entities").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == "bios_profiles" }
        val schemaColumns = biosEntity.getValue("fields").jsonArray.map {
            it.jsonObject.getValue("columnName").jsonPrimitive.content
        }
        val biosSpec = RECOVERY_BIOS_PROFILES_V12
        assertEquals(biosSpec.columns.map(RecoveryColumnSpec::name), schemaColumns)
        assertEquals(
            biosSpec.primaryKey,
            biosEntity.getValue("primaryKey").jsonObject
                .getValue("columnNames").jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertEquals(
            RecoveryArchiveTablesV1.paths,
            RECOVERY_TABLE_SPECS_V12.map(RecoveryTableSpec::path),
        )
    }

    @Test
    fun `compatibility projection stays a separate required non-table payload`() {
        assertTrue(
            RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH !in
                RECOVERY_TABLE_SPECS_V9.map(RecoveryTableSpec::path),
        )
        assertEquals(
            RecoveryArchiveTablesV1.requiredPaths,
            (
                RECOVERY_TABLE_SPECS_V9.map(RecoveryTableSpec::path) +
                    RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH
                ).toSet(),
        )
        assertEquals(
            RECOVERY_TABLE_SPECS_V9.map(RecoveryTableSpec::path),
            RECOVERY_TABLE_SPECS_V12.map(RecoveryTableSpec::path),
        )
    }
}
