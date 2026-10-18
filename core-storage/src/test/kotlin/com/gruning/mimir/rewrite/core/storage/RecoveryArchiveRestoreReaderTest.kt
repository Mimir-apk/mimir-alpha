package com.gruning.mimir.rewrite.core.storage

import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_COMPAT
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_CORE
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_DERIVED
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_RESTORE_RECORDS
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_RESTORE_RETAINED_RECORDS
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_RESTORE_RETAINED_TEXT_BYTES
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveCoverageV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveDescriptorV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveExclusionV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivePayloadSource
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveSnapshotV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveTablesV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveValidator
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveWriter
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreRecordCapException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreRetainedRecordCapException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreRetainedTextCapException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreSchemaException
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryArchiveRestoreReaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `two-pass projection reads desired state and only needed game evidence`() {
        val gameId = "game/current"
        val archive = buildArchive(
            name = "two-pass",
            tableLines = mapOf(
                "db/favorites.jsonl" to listOf("""{"gameId":"$gameId","favoritedAtMillis":10}"""),
                "db/games.jsonl" to listOf(minimalGameLine(gameId = gameId, entryKey = "entry/$gameId")),
                "db/platforms.jsonl" to listOf(
                    """{"platformId":"gba","displayName":"Game Boy Advance","gamesCount":1,"requiresBios":false}""",
                ),
                "db/source_roots.jsonl" to listOf(
                    """{"rootId":"root/a","rootUri":"content://hint","locationKind":"SAF_TREE_GRANT",""" +
                        """"friendlyLabel":"SD card","isPrimarySource":true,"lastSeenMillis":1}""",
                ),
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()

        val snapshot = RecoveryArchiveRestoreReader.read(structural).getOrThrow()

        assertEquals(1, snapshot.favorites.size)
        assertEquals(gameId, snapshot.favorites.single().archivedGameId)
        assertEquals(1, snapshot.gameEvidence.size)
        assertEquals("Game Boy Advance", snapshot.gameEvidence[gameId]?.platformLabel)
        assertEquals(listOf("SD card"), snapshot.sourceRootHints.map { it.friendlyLabel })
    }

    @Test
    fun `archive-space alias and logical-group normalization selects current evidence`() {
        val historical = "game/historical"
        val current = "game/current"
        val logical = "logical/sonic"
        val archive = buildArchive(
            name = "alias-group",
            tableLines = mapOf(
                "db/favorites.jsonl" to listOf("""{"gameId":"$historical","favoritedAtMillis":10}"""),
                "db/game_id_aliases.jsonl" to listOf(
                    """{"oldGameId":"$historical","logicalGameId":"$logical","firstSeenMillis":1}""",
                ),
                "db/logical_game_groups.jsonl" to listOf(
                    """{"logicalGameId":"$logical","currentGameId":"$current","groupKind":"EXACT_HASH",""" +
                        """"memberless":false,"createdAtMillis":2,"updatedAtMillis":3}""",
                ),
                "db/games.jsonl" to listOf(
                    minimalGameLine(gameId = current, entryKey = "entry/$current", fileName = "Sonic.gba"),
                    minimalGameLine(gameId = historical, entryKey = "entry/$historical", fileName = "Old.gba"),
                ),
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()

        val snapshot = RecoveryArchiveRestoreReader.read(structural).getOrThrow()

        assertEquals(current, snapshot.identityIndex.normalizeArchivedGameId(historical))
        assertEquals("Sonic.gba", snapshot.gameEvidence[current]?.fileName)
        assertEquals(null, snapshot.gameEvidence[historical])
    }

    @Test
    fun `missing game evidence stays out of the projection map`() {
        val archive = buildArchive(
            name = "missing-evidence",
            tableLines = mapOf(
                "db/favorites.jsonl" to listOf("""{"gameId":"game/missing","favoritedAtMillis":1}"""),
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()

        val snapshot = RecoveryArchiveRestoreReader.read(structural).getOrThrow()

        assertTrue(snapshot.gameEvidence.isEmpty())
    }

    @Test
    fun `unsupported schema is refused before projection`() {
        val archive = buildArchive(name = "schema-8", schemaVersion = 8)
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()

        val result = RecoveryArchiveRestoreReader.read(structural)

        assertTrue(result.exceptionOrNull() is RecoveryRestoreSchemaException)
    }

    @Test
    fun `schemas 10 through 12 use the stable restore projection without bios facts`() {
        (10..12).forEach { schemaVersion ->
            val biosLine = if (schemaVersion == 12) {
                """{"platformId":"gba","emulatorScopeKey":"duckstation","packageName":"com.duck",""" +
                    """"fileName":"bios.bin","required":true,"presence":"UNKNOWN"}"""
            } else {
                """{"platformId":"gba","fileName":"bios.bin","required":false,"presence":"UNKNOWN"}"""
            }
            val archive = buildArchive(
                name = "schema-$schemaVersion",
                schemaVersion = schemaVersion,
                tableLines = mapOf(
                    "db/favorites.jsonl" to listOf(
                        """{"gameId":"game/current","favoritedAtMillis":10}""",
                    ),
                    "db/games.jsonl" to listOf(
                        minimalGameLine(
                            gameId = "game/current",
                            entryKey = "entry/game/current",
                        ),
                    ),
                    "db/bios_profiles.jsonl" to listOf(biosLine),
                ),
            )
            val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()

            RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()
            val snapshot = RecoveryArchiveRestoreReader.read(structural).getOrThrow()

            assertEquals(schemaVersion, snapshot.schemaVersion)
            assertEquals("game/current", snapshot.favorites.single().archivedGameId)
            assertEquals("game/current", snapshot.gameEvidence["game/current"]?.normalizedGameId)
        }
    }

    /**
     * **Derives the unsupported schema instead of naming it** — the third test in this family caught
     * doing the same thing.
     *
     * It read `schemaVersion = 13`, which was correct while 13 was unsupported and became a pin on the
     * defect the moment 13 shipped. A test that hardcodes "the next one" stops testing refusal and starts
     * forbidding the present, without failing to say so.
     */
    @Test
    fun `a schema newer than this reader supports is refused before projection`() {
        val archive = buildArchive(
            name = "schema-future",
            schemaVersion = RECOVERY_SCHEMA_MAX_VERSION_V1 + 1,
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()

        val result = RecoveryArchiveRestoreReader.read(structural)

        assertTrue(result.exceptionOrNull() is RecoveryRestoreSchemaException)
    }

    /**
     * **The behaviour that was actually broken, and that no test asserted.**
     *
     * The reader refused the newest supported schema outright, two gates before the validator the earlier change had
     * just corrected. Export therefore worked and restore of its own fresh archive did not — the shape a
     * backup feature can least afford, since the player only discovers it when they need the backup.
     */
    @Test
    fun `the newest supported schema is not refused`() {
        val archive = buildArchive(
            name = "schema-newest-supported",
            schemaVersion = RECOVERY_SCHEMA_MAX_VERSION_V1,
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()

        val result = RecoveryArchiveRestoreReader.read(structural)

        assertFalse(
            "Schema $RECOVERY_SCHEMA_MAX_VERSION_V1 is the version that ships. Refusing it means Mimir " +
                "cannot restore the archive it just wrote.",
            result.exceptionOrNull() is RecoveryRestoreSchemaException,
        )
    }

    @Test
    fun `schema 11 recovery choice follows physical tuple when canonical p2 order is opposite`() {
        val gameId = "game/p2-order"
        val physicalFirstKey = physicalResidentKey("aa", "same.gba")
        val physicalSecondKey = physicalResidentKey("z", "same.gba")
        assertTrue(physicalFirstKey > physicalSecondKey)
        val archive = buildArchive(
            name = "schema-11-p2-order",
            schemaVersion = 11,
            tableLines = mapOf(
                "db/favorites.jsonl" to listOf(
                    """{"gameId":"$gameId","favoritedAtMillis":1}""",
                ),
                "db/games.jsonl" to listOf(
                    physicalGameLine(gameId, "z", "same.gba", sizeBytes = 222),
                    physicalGameLine(gameId, "aa", "same.gba", sizeBytes = 111),
                ),
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()

        val snapshot = RecoveryArchiveRestoreReader.read(structural).getOrThrow()

        assertEquals(111L, snapshot.gameEvidence[gameId]?.sizeBytes)
    }

    @Test
    fun `record ceiling refuses before pass two`() {
        val favorites = (0 until RECOVERY_MAX_RESTORE_RECORDS + 1).map { index ->
            val gameId = "game/${index.toString().padStart(8, '0')}"
            """{"gameId":"$gameId","favoritedAtMillis":$index}"""
        }
        val archive = buildArchive(
            name = "record-cap",
            tableLines = mapOf("db/favorites.jsonl" to favorites),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()

        val result = RecoveryArchiveRestoreReader.read(structural)

        assertTrue(result.exceptionOrNull() is RecoveryRestoreRecordCapException)
    }

    @Test
    fun `record ceiling counts hidden true only and all shelf profiles while streaming`() {
        val favorites = (0 until RECOVERY_MAX_RESTORE_RECORDS).map { index ->
            val gameId = "game/fav-${index.toString().padStart(8, '0')}"
            """{"gameId":"$gameId","favoritedAtMillis":$index}"""
        }
        val hiddenTrue =
            """{"gameId":"game/hidden-true","hidden":true,"isPrimaryMember":true,"hiddenAtMillis":2}"""
        val archive = buildArchive(
            name = "record-cap-streaming",
            tableLines = mapOf(
                "db/favorites.jsonl" to favorites,
                "db/hidden_items.jsonl" to listOf(hiddenTrue),
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()

        val result = RecoveryArchiveRestoreReader.read(structural)

        assertTrue(result.exceptionOrNull() is RecoveryRestoreRecordCapException)
    }

    @Test
    fun `foreign profile shelf rows count toward the streaming record ceiling`() {
        val favorites = (0 until RECOVERY_MAX_RESTORE_RECORDS).map { index ->
            val gameId = "game/fav-${index.toString().padStart(8, '0')}"
            """{"gameId":"$gameId","favoritedAtMillis":$index}"""
        }
        val foreignShelf =
            """{"profileId":"foreign","itemKind":"EMULATED_GAME","itemId":"game/shelf",""" +
                """"rank":0,"addedAtMillis":3}"""
        val archive = buildArchive(
            name = "record-cap-foreign-shelf",
            tableLines = mapOf(
                "db/favorites.jsonl" to favorites,
                "db/home_shelf_items.jsonl" to listOf(foreignShelf),
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()

        val result = RecoveryArchiveRestoreReader.read(structural)

        assertTrue(result.exceptionOrNull() is RecoveryRestoreRecordCapException)
    }

    @Test
    fun `read refuses structurally valid semantic invalid archive without external semantic pass`() {
        val archive = buildArchive(
            name = "semantic-invalid",
            tableLines = mapOf(
                "db/favorites.jsonl" to listOf(
                    """{"gameId":"game/a","favoritedAtMillis":1,"secret":"leak"}""",
                ),
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()

        val result = RecoveryArchiveRestoreReader.read(structural)

        assertTrue(result.isFailure)
    }

    @Test
    fun `auxiliary alias rows count toward retained record ceiling without owner intent`() {
        val aliases = (0 until RECOVERY_MAX_RESTORE_RETAINED_RECORDS + 1).map { index ->
            val oldId = "game/old-${index.toString().padStart(8, '0')}"
            val logicalId = "logical/${index.toString().padStart(8, '0')}"
            """{"oldGameId":"$oldId","logicalGameId":"$logicalId","firstSeenMillis":1}"""
        }
        val archive = buildArchive(
            name = "auxiliary-record-cap",
            tableLines = mapOf("db/game_id_aliases.jsonl" to aliases),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()

        val result = RecoveryArchiveRestoreReader.read(structural)

        assertTrue(result.exceptionOrNull() is RecoveryRestoreRetainedRecordCapException)
    }

    @Test
    fun `retained text bytes refuse before auxiliary rows exhaust heap`() {
        val rowCount = 32
        val labelBytesPerRow = ((RECOVERY_MAX_RESTORE_RETAINED_TEXT_BYTES / rowCount) + 1L).toInt()
        val label = "x".repeat(labelBytesPerRow)
        val rows = (0 until rowCount).map { index ->
            sourceRootLine(rootId = "root/${index.toString().padStart(4, '0')}", friendlyLabel = label)
        }
        val archive = buildArchive(
            name = "retained-text-cap",
            tableLines = mapOf("db/source_roots.jsonl" to rows),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()

        val result = RecoveryArchiveRestoreReader.read(structural)

        assertTrue(result.exceptionOrNull() is RecoveryRestoreRetainedTextCapException)
    }

    @Test
    fun `pass two retained text refuses before large game evidence is fully retained`() {
        val neededCount = 48
        val titleBytesPerRow = ((RECOVERY_MAX_RESTORE_RETAINED_TEXT_BYTES / neededCount) + 1L).toInt()
        val largeTitle = "g".repeat(titleBytesPerRow)
        val favorites = (0 until neededCount).map { index ->
            val gameId = "game/${index.toString().padStart(4, '0')}"
            """{"gameId":"$gameId","favoritedAtMillis":$index}"""
        }
        val games = (0 until neededCount).map { index ->
            val gameId = "game/${index.toString().padStart(4, '0')}"
            largeGameLine(gameId = gameId, entryKey = "entry/$gameId", title = largeTitle)
        }
        val archive = buildArchive(
            name = "pass-two-retained-text-cap",
            tableLines = mapOf(
                "db/favorites.jsonl" to favorites,
                "db/games.jsonl" to games,
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()

        val result = RecoveryArchiveRestoreReader.read(structural)

        assertTrue(result.exceptionOrNull() is RecoveryRestoreRetainedTextCapException)
    }

    @Test
    fun `pass two candidate replacement releases prior retained text before reserving`() {
        val gameId = "game/replace"
        val archive = buildArchive(
            name = "pass-two-replacement-budget",
            tableLines = mapOf(
                "db/favorites.jsonl" to listOf("""{"gameId":"$gameId","favoritedAtMillis":1}"""),
                "db/games.jsonl" to listOf(
                    largeGameLine(
                        gameId = gameId,
                        entryKey = "entry/0001",
                        fileName = "Older.gba",
                        title = "a".repeat(8_192),
                        availability = "MISSING",
                        isPrimaryMember = false,
                    ),
                    largeGameLine(
                        gameId = gameId,
                        entryKey = "entry/0002",
                        fileName = "Newer.gba",
                        title = "b".repeat(8_192),
                        availability = "AVAILABLE",
                        isPrimaryMember = true,
                    ),
                ),
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()

        val snapshot = RecoveryArchiveRestoreReader.read(structural).getOrThrow()

        assertEquals("Newer.gba", snapshot.gameEvidence[gameId]?.fileName)
    }

    @Test
    fun `hidden false rows are ignored because they carry no restore intent`() {
        val archive = buildArchive(
            name = "hidden-false-ignored",
            tableLines = mapOf(
                "db/hidden_items.jsonl" to listOf(
                    """{"gameId":"game/a","hidden":false,"isPrimaryMember":true,"hiddenAtMillis":1}""",
                    """{"gameId":"game/b","hidden":true,"isPrimaryMember":true,"hiddenAtMillis":2}""",
                ),
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        RecoveryArchiveV1SemanticValidator.validate(structural).getOrThrow()

        val snapshot = RecoveryArchiveRestoreReader.read(structural).getOrThrow()

        assertEquals(listOf("game/b"), snapshot.hidden.map { it.archivedGameId })
        assertEquals(1, snapshot.restoreRecordCount())
    }

    @Test
    fun `projection optional text refuses a non-text primitive independently of semantic gate`() {
        val row = Json.parseToJsonElement("""{"canonicalTitle":42}""") as JsonObject
        val method = RecoveryArchiveRestoreReader::class.java.declaredMethods
            .single { it.name == "optionalText" }
            .apply { isAccessible = true }

        val error = reflectionCause(
            runCatching {
                method.invoke(RecoveryArchiveRestoreReader, row, "canonicalTitle")
            }.exceptionOrNull(),
        )

        assertTrue(error is RecoveryArchiveException)
        assertTrue(error?.message.orEmpty().contains("canonicalTitle is not text"))
    }

    @Test
    fun `projection table refuses an empty JSONL row independently of semantic gate`() {
        val zipBytes = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("table.jsonl"))
                zip.write("\n".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val zip = ZipInputStream(ByteArrayInputStream(zipBytes))
        check(zip.nextEntry != null)
        val shouldContinue: () -> Boolean = { true }
        val consume: (JsonObject) -> Unit = {}
        val method = RecoveryArchiveRestoreReader::class.java.declaredMethods
            .single { it.name == "readTable" }
            .apply { isAccessible = true }

        val error = reflectionCause(
            runCatching {
                method.invoke(RecoveryArchiveRestoreReader, zip, shouldContinue, consume)
            }.exceptionOrNull(),
        )

        assertTrue(error is RecoveryArchiveException)
        assertTrue(error?.message.orEmpty().contains("empty row"))
    }

    @Test
    fun `pass two refuses a trailing payload added after structural validation`() {
        val gameId = "game/pass-two-trailing"
        val archive = buildArchive(
            name = "pass-two-trailing",
            tableLines = mapOf(
                "db/favorites.jsonl" to listOf("""{"gameId":"$gameId","favoritedAtMillis":1}"""),
                "db/games.jsonl" to listOf(minimalGameLine(gameId, "entry/$gameId")),
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        appendUnlistedEntry(archive)
        val budgetClass = RecoveryArchiveRestoreReader::class.java.declaredClasses
            .single { it.simpleName == "ProjectionBudget" }
        val budget = budgetClass.getDeclaredConstructor().run {
            isAccessible = true
            newInstance()
        }
        val shouldContinue: () -> Boolean = { true }
        val method = RecoveryArchiveRestoreReader::class.java.declaredMethods
            .single { it.name == "readPassTwo" }
            .apply { isAccessible = true }

        val error = reflectionCause(
            runCatching {
                method.invoke(
                    RecoveryArchiveRestoreReader,
                    structural,
                    setOf(gameId),
                    emptyMap<String, String>(),
                    shouldContinue,
                    budget,
                )
            }.exceptionOrNull(),
        )

        assertTrue(error is RecoveryArchiveException)
        assertTrue(error?.message.orEmpty().contains("unlisted restore payload"))
    }

    private fun buildArchive(
        name: String,
        schemaVersion: Int = 9,
        tableLines: Map<String, List<String>> = emptyMap(),
    ): File {
        val root = temporary.newFolder(name)
        val payloadRoot = File(root, "payload").apply { mkdirs() }
        val payloads = RecoveryArchiveTablesV1.paths.map { path ->
            val lines = tableLines[path].orEmpty()
            val file = File(payloadRoot, path).apply {
                parentFile?.mkdirs()
                writeText(
                    lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"),
                )
            }
            RecoveryArchivePayloadSource(
                path = path,
                contentClass = when (path) {
                    "db/title_metadata_cache.jsonl" -> RECOVERY_CONTENT_DERIVED
                    else -> RECOVERY_CONTENT_CORE
                },
                rowCount = lines.size.toLong(),
                file = file,
            )
        }.toMutableList()
        payloads += RecoveryArchivePayloadSource(
            path = RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH,
            contentClass = RECOVERY_CONTENT_COMPAT,
            file = File(payloadRoot, RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH).apply {
                parentFile?.mkdirs()
                writeText(compatibilityJson())
            },
        )
        val archive = File(root, "archive.zip")
        RecoveryArchiveWriter.writeNew(
            destination = archive,
            descriptor = RecoveryArchiveDescriptorV1(
                createdAtUtcMillis = 1_753_270_000_000L,
                producedByApp = "com.gruning.mimir.rewrite",
                producedByVersionName = "0.1.0-alpha.1",
                producedByVersionCode = 3,
                schemaVersion = schemaVersion,
                excluded = RecoveryArchiveCoverageV1.requiredExclusions.sorted().map {
                    RecoveryArchiveExclusionV1(
                        what = it,
                        reason = "Excluded by the Recovery Archive V1 contract.",
                    )
                },
                snapshot = RecoveryArchiveSnapshotV1(
                    transactional = true,
                    quiescent = false,
                    journalMode = "wal",
                ),
            ),
            payloads = payloads,
        ).getOrThrow()
        return archive
    }

    private fun appendUnlistedEntry(archive: File) {
        val replacement = File(archive.parentFile, "archive-with-unlisted.zip")
        ZipInputStream(FileInputStream(archive)).use { input ->
            ZipOutputStream(FileOutputStream(replacement)).use { output ->
                val buffer = ByteArray(64 * 1024)
                var entry = input.nextEntry
                while (entry != null) {
                    output.putNextEntry(ZipEntry(entry.name))
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.closeEntry()
                    input.closeEntry()
                    entry = input.nextEntry
                }
                output.putNextEntry(ZipEntry("db/unlisted.jsonl"))
                output.write("{}\n".toByteArray())
                output.closeEntry()
            }
        }
        check(archive.delete())
        check(replacement.renameTo(archive))
    }

    private fun reflectionCause(error: Throwable?): Throwable? =
        if (error is InvocationTargetException) error.targetException else error

    private fun largeGameLine(
        gameId: String,
        entryKey: String,
        fileName: String = "Game.gba",
        platformId: String = "gba",
        sizeBytes: Long = 1_048_576L,
        title: String = fileName,
        availability: String = "AVAILABLE",
        isPrimaryMember: Boolean = true,
    ): String =
        """{"entryKey":"$entryKey","gameId":"$gameId","platformId":"$platformId",""" +
            """"contentKind":"GAME","displayName":"$fileName","title":"$title",""" +
            """"identitySource":"FILENAME","locationKind":"SAF_TREE_GRANT","availability":"$availability",""" +
            """"sizeBytes":$sizeBytes,"relativePath":"$fileName","discNumber":0,"isPrimaryMember":$isPrimaryMember}"""

    private fun minimalGameLine(
        gameId: String,
        entryKey: String,
        fileName: String = "Game.gba",
        platformId: String = "gba",
        sizeBytes: Long = 1_048_576L,
    ): String =
        """{"entryKey":"$entryKey","gameId":"$gameId","platformId":"$platformId",""" +
            """"contentKind":"GAME","displayName":"$fileName","title":"$fileName",""" +
            """"identitySource":"FILENAME","locationKind":"SAF_TREE_GRANT","availability":"AVAILABLE",""" +
            """"sizeBytes":$sizeBytes,"relativePath":"$fileName","discNumber":0,"isPrimaryMember":true}"""

    private fun physicalGameLine(
        gameId: String,
        sourceRootUri: String,
        relativePath: String,
        sizeBytes: Long,
    ): String {
        val entryKey = physicalResidentKey(sourceRootUri, relativePath)
        return """{"entryKey":"$entryKey","gameId":"$gameId","platformId":"gba",""" +
            """"contentKind":"GAME","displayName":"Same.gba","title":"Same",""" +
            """"identitySource":"FILENAME","locationKind":"SAF_TREE_GRANT","availability":"AVAILABLE",""" +
            """"sizeBytes":$sizeBytes,"sourceRootUri":"$sourceRootUri",""" +
            """"relativePath":"$relativePath","discNumber":0,"isPrimaryMember":true}"""
    }

    private fun sourceRootLine(
        rootId: String,
        friendlyLabel: String,
    ): String =
        """{"rootId":"$rootId","rootUri":"content://hint","locationKind":"SAF_TREE_GRANT",""" +
            """"friendlyLabel":"$friendlyLabel","isPrimarySource":false,"lastSeenMillis":1}"""

    private fun compatibilityJson(): String =
        "{\"bundleVersion\":1,\"kind\":\"mimir.migration.bundle\"," +
            "\"producedByApp\":\"com.gruning.mimir.rewrite\"," +
            "\"producedByVersion\":\"0.1.0-alpha.1\",\"producedAtMillis\":1753270000000," +
            "\"sourceRoots\":[],\"favorites\":[],\"hidden\":[],\"emulatorOverrides\":[]}"
}
