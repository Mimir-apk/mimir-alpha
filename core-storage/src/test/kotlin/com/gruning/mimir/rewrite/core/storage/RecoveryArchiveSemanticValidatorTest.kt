package com.gruning.mimir.rewrite.core.storage

import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_COMPAT
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_CORE
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_CONTENT_DERIVED
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveCoverageV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveCancelledException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveDescriptorV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveExclusionV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivePayloadSource
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveSnapshotV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveTablesV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveValidator
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveWriter
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryArchiveSemanticValidatorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `schemas 9 through 12 accept the stable typed projection in primary-key order`() {
        (9..12).forEach { schemaVersion ->
            val archive = archive(
                "valid-$schemaVersion",
                schemaVersion = schemaVersion,
                favorites = listOf(
                    """{"gameId":"game/a","favoritedAtMillis":1}""",
                    """{"gameId":"game/b","favoritedAtMillis":2}""",
                ),
                biosProfiles = if (schemaVersion == 12) {
                    listOf(
                        """{"platformId":"gba","emulatorScopeKey":"duckstation","packageName":"com.duck",""" +
                            """"fileName":"bios.bin","required":true,"presence":"UNKNOWN"}""",
                    )
                } else {
                    emptyList()
                },
            )

            val report = RecoveryArchiveV1SemanticValidator.validate(archive).getOrThrow()

            assertFalse("schema=$schemaVersion", report.newerSchema)
        }
    }

    @Test
    fun `duplicate and descending primary keys fail the semantic pass`() {
        val duplicate = archive(
            "duplicate",
            favorites = listOf(
                """{"gameId":"game/a","favoritedAtMillis":1}""",
                """{"gameId":"game/a","favoritedAtMillis":2}""",
            ),
        )
        val descending = archive(
            "descending",
            favorites = listOf(
                """{"gameId":"game/b","favoritedAtMillis":1}""",
                """{"gameId":"game/a","favoritedAtMillis":2}""",
            ),
        )

        assertTrue(RecoveryArchiveV1SemanticValidator.validate(duplicate).isFailure)
        assertTrue(RecoveryArchiveV1SemanticValidator.validate(descending).isFailure)
    }

    @Test
    fun `missing unknown mistyped and explicit-null columns fail the semantic pass`() {
        val badRows = listOf(
            """{"gameId":"game/a"}""",
            """{"gameId":"game/a","favoritedAtMillis":1,"secret":"leak"}""",
            """{"gameId":"game/a","favoritedAtMillis":"1"}""",
            """{"gameId":"game/a","favoritedAtMillis":null}""",
            """{"favoritedAtMillis":1,"gameId":"game/a"}""",
        )

        badRows.forEachIndexed { index, row ->
            val candidate = archive("bad-row-$index", favorites = listOf(row))
            assertTrue(
                "row=$row",
                RecoveryArchiveV1SemanticValidator.validate(candidate).isFailure,
            )
        }
    }

    /**
     * **Derives the future instead of naming it**, which is the point of this edit.
     *
     * It read `schemaVersion = 13` — correct when written, and silently wrong the moment 13 became the
     * schema that ships. A test that hardcodes "the next one" stops testing the future and starts
     * testing the present, without failing to say so. Same flaw the contract test had, same fix.
     */
    @Test
    fun `future Room schema remains integrity-valid but is never semantically applied by V1`() {
        val archive = archive(
            "future-schema",
            schemaVersion = RECOVERY_SCHEMA_MAX_VERSION_V1 + 1,
        )

        val report = RecoveryArchiveV1SemanticValidator.validate(archive).getOrThrow()

        assertTrue(report.newerSchema)
    }

    /** The ceiling itself must be *applied*, not merely tolerated — the half the first fix missed. */
    @Test
    fun `the newest supported schema is semantically applied, not shrugged off as newer`() {
        val archive = archive(
            "newest-supported",
            schemaVersion = RECOVERY_SCHEMA_MAX_VERSION_V1,
        )

        val report = RecoveryArchiveV1SemanticValidator.validate(archive).getOrThrow()

        assertFalse(
            "Schema $RECOVERY_SCHEMA_MAX_VERSION_V1 is the version that ships; treating it as " +
                "\"from a newer build\" means Mimir cannot read its own fresh backup.",
            report.newerSchema,
        )
    }

    @Test
    fun `schema 12 rejects bios rows missing emulatorScopeKey`() {
        val archive = archive(
            name = "schema-12-missing-scope",
            schemaVersion = 12,
            biosProfiles = listOf(
                """{"platformId":"gba","packageName":"com.duck","fileName":"bios.bin",""" +
                    """"required":true,"presence":"UNKNOWN"}""",
            ),
        )

        assertTrue(RecoveryArchiveV1SemanticValidator.validate(archive).isFailure)
    }

    @Test
    fun `schema 12 rejects bios rows with v9-only columns`() {
        val archive = archive(
            name = "schema-12-wrong-shape",
            schemaVersion = 12,
            biosProfiles = listOf(
                """{"platformId":"gba","fileName":"bios.bin","required":true,"presence":"UNKNOWN"}""",
            ),
        )

        assertTrue(RecoveryArchiveV1SemanticValidator.validate(archive).isFailure)
    }

    @Test
    fun `schema 11 archive rejects v12-shaped bios rows`() {
        val archive = archive(
            name = "schema-11-v12-bios",
            schemaVersion = 11,
            biosProfiles = listOf(
                """{"platformId":"gba","emulatorScopeKey":"duckstation","packageName":"com.duck",""" +
                    """"fileName":"bios.bin","required":true,"presence":"UNKNOWN"}""",
            ),
        )

        assertTrue(RecoveryArchiveV1SemanticValidator.validate(archive).isFailure)
    }

    @Test
    fun `missing wrong-kind and credential-shaped compatibility payloads fail`() {
        val candidates = listOf(
            "{}",
            compatibilityJson().replace(
                "\"kind\":\"mimir.migration.bundle\"",
                "\"kind\":\"not.mimir\"",
            ),
            compatibilityJson().replace(
                "\"sourceRoots\":",
                "\"sspassword\":\"secret\",\"sourceRoots\":",
            ),
        )

        candidates.forEachIndexed { index, compatibility ->
            val candidate = archive(
                name = "bad-compat-$index",
                compatibility = compatibility,
            )
            assertTrue(
                RecoveryArchiveV1SemanticValidator.validate(candidate).isFailure,
            )
        }
    }

    @Test
    fun `duplicate members fail before last-member-wins table and compatibility decoding`() {
        val duplicateTableMember = archive(
            name = "duplicate-table-member",
            favorites = listOf(
                """{"gameId":"game/a","gameId":"game/b","favoritedAtMillis":1}""",
            ),
        )
        val duplicateCompatibilityMember = archive(
            name = "duplicate-compatibility-member",
            compatibility = compatibilityJson().replace(
                "\"favorites\":[]",
                "\"favorites\":[{" +
                    "\"platformId\":\"gba\",\"platformId\":\"snes\"," +
                    "\"fileName\":\"game.gba\",\"sizeBytes\":1," +
                    "\"title\":\"Game\",\"platformLabel\":\"GBA\"}]",
            ),
        )

        assertTrue(RecoveryArchiveV1SemanticValidator.validate(duplicateTableMember).isFailure)
        assertTrue(
            RecoveryArchiveV1SemanticValidator.validate(duplicateCompatibilityMember).isFailure,
        )
    }

    @Test
    fun `malformed UTF-8 in compatibility payload fails semantic validation`() {
        val prefix =
            "{\"bundleVersion\":1,\"kind\":\"mimir.migration.bundle\",\"producedByApp\":\""
        val suffix =
            "\",\"producedByVersion\":\"0.1.0-alpha.1\",\"producedAtMillis\":1753270000000," +
                "\"sourceRoots\":[],\"favorites\":[],\"hidden\":[],\"emulatorOverrides\":[]}"
        val malformed = prefix.toByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28) +
            suffix.toByteArray()
        val candidate = archive(
            name = "malformed-compatibility-utf8",
            compatibilityBytes = malformed,
        )

        assertTrue(RecoveryArchiveV1SemanticValidator.validate(candidate).isFailure)
    }

    @Test
    fun `semantic cancellation is observed during a structurally valid archive pass`() {
        val archive = archive(
            name = "semantic-cancel",
            favorites = listOf(
                """{"gameId":"game/a","favoritedAtMillis":1}""",
                """{"gameId":"game/b","favoritedAtMillis":2}""",
            ),
        )
        val structural = RecoveryArchiveValidator.validate(archive).getOrThrow()
        var checks = 0

        val result = RecoveryArchiveV1SemanticValidator.validate(structural) {
            ++checks < 8
        }

        assertTrue(result.exceptionOrNull() is RecoveryArchiveCancelledException)
    }

    private fun archive(
        name: String,
        favorites: List<String> = emptyList(),
        biosProfiles: List<String> = emptyList(),
        schemaVersion: Int = 9,
        compatibility: String = compatibilityJson(),
        compatibilityBytes: ByteArray? = null,
    ): File {
        val root = temporary.newFolder(name)
        val payloadRoot = File(root, "payload").apply { mkdirs() }
        val payloads = RecoveryArchiveTablesV1.paths.map { path ->
            val file = File(payloadRoot, path).apply {
                parentFile?.mkdirs()
                if (path == "db/favorites.jsonl") {
                    writeText(
                        favorites.joinToString(
                            separator = "\n",
                            postfix = if (favorites.isEmpty()) "" else "\n",
                        ),
                    )
                } else if (path == "db/bios_profiles.jsonl") {
                    writeText(
                        biosProfiles.joinToString(
                            separator = "\n",
                            postfix = if (biosProfiles.isEmpty()) "" else "\n",
                        ),
                    )
                } else {
                    writeBytes(byteArrayOf())
                }
            }
            RecoveryArchivePayloadSource(
                path = path,
                contentClass = if (path == "db/title_metadata_cache.jsonl") {
                    RECOVERY_CONTENT_DERIVED
                } else {
                    RECOVERY_CONTENT_CORE
                },
                rowCount = when (path) {
                    "db/favorites.jsonl" -> favorites.size.toLong()
                    "db/bios_profiles.jsonl" -> biosProfiles.size.toLong()
                    else -> 0
                },
                file = file,
            )
        }.toMutableList()
        payloads += RecoveryArchivePayloadSource(
            path = RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH,
            contentClass = RECOVERY_CONTENT_COMPAT,
            file = File(payloadRoot, RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH).apply {
                parentFile?.mkdirs()
                writeBytes(compatibilityBytes ?: compatibility.toByteArray())
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

    private fun compatibilityJson(): String =
        "{\"bundleVersion\":1,\"kind\":\"mimir.migration.bundle\"," +
            "\"producedByApp\":\"com.gruning.mimir.rewrite\"," +
            "\"producedByVersion\":\"0.1.0-alpha.1\",\"producedAtMillis\":1753270000000," +
            "\"sourceRoots\":[],\"favorites\":[],\"hidden\":[],\"emulatorOverrides\":[]}"
}
