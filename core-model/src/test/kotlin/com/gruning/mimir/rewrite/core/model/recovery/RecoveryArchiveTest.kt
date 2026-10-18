package com.gruning.mimir.rewrite.core.model.recovery

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryArchiveTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `writer stages validates and preserves canonical payload bytes`() {
        val root = temporary.newFolder("round-trip")
        val sources = payloadSources(root)
        val first = File(root, "first.mimir-recovery.zip")
        val second = File(root, "second.mimir-recovery.zip")

        val firstResult = RecoveryArchiveWriter.writeNew(first, descriptor(), sources).getOrThrow()
        val secondResult = RecoveryArchiveWriter.writeNew(second, descriptor(), sources).getOrThrow()

        assertTrue(first.isFile)
        assertEquals(firstResult.manifest, RecoveryArchiveValidator.validate(first).getOrThrow().manifest)
        assertEquals(firstResult.manifestSha256, secondResult.manifestSha256)
        assertArrayEquals(first.readBytes(), second.readBytes())
        val firstPayloads = payloadBytes(first)
        val secondPayloads = payloadBytes(second)
        assertEquals(firstPayloads.keys, secondPayloads.keys)
        firstPayloads.forEach { (path, bytes) ->
            assertArrayEquals(path, bytes, secondPayloads.getValue(path))
        }
    }

    @Test
    fun `cancellation removes partial archive and never promotes destination`() {
        val root = temporary.newFolder("cancel")
        val destination = File(root, "cancelled.mimir-recovery.zip")
        var checks = 0

        val result = RecoveryArchiveWriter.writeNew(
            destination = destination,
            descriptor = descriptor(),
            payloads = payloadSources(root),
            shouldContinue = { ++checks < 6 },
        )

        assertTrue(result.exceptionOrNull() is RecoveryArchiveCancelledException)
        assertFalse(destination.exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".partial") })
    }

    @Test
    fun `manifest rejects missing coverage and a future reader floor`() {
        val entries = manifestEntries()
        val missingCoverage = manifest(
            entries = entries,
            excluded = exclusions().dropLast(1),
        )
        assertTrue(RecoveryArchiveCodec.validateManifest(missingCoverage).isFailure)

        val future = manifest(entries = entries).copy(
            formatVersion = 2,
            minReaderFormatVersion = 2,
        )
        assertTrue(RecoveryArchiveCodec.validateManifest(future).isFailure)
    }

    @Test
    fun `validator accepts additive unknown entry and content class`() {
        val root = temporary.newFolder("future-additive")
        val sources = payloadSources(root).toMutableList()
        val extra = File(root, "future.json").apply { writeText("{\"future\":true}") }
        sources += RecoveryArchivePayloadSource(
            path = "future/new_state.json",
            contentClass = "future-class",
            file = extra,
        )
        val archive = File(root, "future.mimir-recovery.zip")

        val validated = RecoveryArchiveWriter.writeNew(archive, descriptor(), sources)
            .mapCatching { RecoveryArchiveValidator.validate(it.archive).getOrThrow() }
            .getOrThrow()

        assertTrue(validated.manifest.entries.any { it.path == "future/new_state.json" })
    }

    @Test
    fun `validator rejects a manifest hash mismatch`() {
        val root = temporary.newFolder("hash-mismatch")
        val entries = manifestEntries()
        val manifestBytes = RecoveryArchiveCodec.encode(manifest(entries))
        val archive = File(root, "bad-hash.zip")
        writeRawZip(
            archive = archive,
            entries = listOf(
                RECOVERY_MANIFEST_PATH to manifestBytes,
                RECOVERY_MANIFEST_HASH_PATH to "${"0".repeat(64)}\n".toByteArray(),
            ) + payloadBytesFor(entries),
        )

        assertTrue(RecoveryArchiveValidator.validate(archive).isFailure)
    }

    @Test
    fun `validator rejects declared size overrun before a deflated payload can expand`() {
        val root = temporary.newFolder("overrun")
        val payload = "x".repeat(128 * 1024).toByteArray()
        val entries = manifestEntries().map { entry ->
            if (entry.path == RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH) {
                entry.copy(
                    uncompressedSize = 1,
                    sha256 = sha256(payload),
                )
            } else {
                entry
            }
        }
        val manifestBytes = RecoveryArchiveCodec.encode(manifest(entries))
        val archive = File(root, "overrun.zip")
        writeRawZip(
            archive = archive,
            entries = listOf(
                RECOVERY_MANIFEST_PATH to manifestBytes,
                RECOVERY_MANIFEST_HASH_PATH to "${sha256(manifestBytes)}\n".toByteArray(),
            ) + payloadBytesFor(entries).map { (path, bytes) ->
                path to if (path == RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH) payload else bytes
            },
        )

        assertTrue(RecoveryArchiveValidator.validate(archive).isFailure)
    }

    @Test
    fun `validator rejects malformed JSONL and row-count drift`() {
        val root = temporary.newFolder("jsonl")
        val invalid = "{\"entryKey\":\"one\"}".toByteArray()
        val entries = manifestEntries().map { entry ->
            if (entry.path == "db/games.jsonl") {
                entry.copy(
                    uncompressedSize = invalid.size.toLong(),
                    sha256 = sha256(invalid),
                    rowCount = 1,
                )
            } else {
                entry
            }
        }
        val manifestBytes = RecoveryArchiveCodec.encode(manifest(entries))
        val archive = File(root, "invalid-jsonl.zip")
        writeRawZip(
            archive = archive,
            entries = listOf(
                RECOVERY_MANIFEST_PATH to manifestBytes,
                RECOVERY_MANIFEST_HASH_PATH to "${sha256(manifestBytes)}\n".toByteArray(),
            ) + payloadBytesFor(entries).map { (path, bytes) ->
                path to if (path == "db/games.jsonl") invalid else bytes
            },
        )

        assertTrue(RecoveryArchiveValidator.validate(archive).isFailure)
    }

    @Test
    fun `validator rejects traversal unlisted entries and noncanonical timestamps`() {
        val root = temporary.newFolder("structure")
        val entries = manifestEntries()
        val manifestBytes = RecoveryArchiveCodec.encode(manifest(entries))
        val archive = File(root, "unlisted.zip")
        writeRawZip(
            archive = archive,
            entries = listOf(
                RECOVERY_MANIFEST_PATH to manifestBytes,
                RECOVERY_MANIFEST_HASH_PATH to "${sha256(manifestBytes)}\n".toByteArray(),
            ) + payloadBytesFor(entries) + ("../unlisted" to byteArrayOf(1)),
        )

        assertTrue(RecoveryArchiveValidator.validate(archive).isFailure)

        val wrongTime = File(root, "wrong-time.zip")
        writeRawZip(
            archive = wrongTime,
            entries = listOf(
                RECOVERY_MANIFEST_PATH to manifestBytes,
                RECOVERY_MANIFEST_HASH_PATH to "${sha256(manifestBytes)}\n".toByteArray(),
            ) + payloadBytesFor(entries),
            timestamp = LocalDateTime.of(2026, 7, 23, 12, 0),
        )
        assertTrue(RecoveryArchiveValidator.validate(wrongTime).isFailure)
    }

    @Test
    fun `validator rejects every truncated archive tail and appended bytes`() {
        val root = temporary.newFolder("tail")
        val archive = File(root, "valid.zip")
        RecoveryArchiveWriter.writeNew(archive, descriptor(), payloadSources(root)).getOrThrow()
        val bytes = archive.readBytes()

        listOf(1, 8, 21, 22).forEach { removed ->
            val truncated = File(root, "truncated-$removed.zip")
            truncated.writeBytes(bytes.copyOf(bytes.size - removed))
            assertTrue("removed=$removed", RecoveryArchiveValidator.validate(truncated).isFailure)
        }
        val appended = File(root, "appended.zip")
        appended.writeBytes(bytes + byteArrayOf(1, 2, 3))
        assertTrue(RecoveryArchiveValidator.validate(appended).isFailure)
    }

    @Test
    fun `validator rejects a ZIP comment outside the deterministic subset`() {
        val root = temporary.newFolder("zip-comment")
        val entries = manifestEntries()
        val manifestBytes = RecoveryArchiveCodec.encode(manifest(entries))
        val archive = File(root, "commented.zip")
        writeRawZip(
            archive = archive,
            entries = listOf(
                RECOVERY_MANIFEST_PATH to manifestBytes,
                RECOVERY_MANIFEST_HASH_PATH to "${sha256(manifestBytes)}\n".toByteArray(),
            ) + payloadBytesFor(entries),
            comment = "not part of Recovery Archive V1",
        )

        assertTrue(RecoveryArchiveValidator.validate(archive).isFailure)
    }

    @Test
    fun `validator accepts the canonical STORED subset`() {
        val root = temporary.newFolder("stored")
        val entries = manifestEntries()
        val manifestBytes = RecoveryArchiveCodec.encode(manifest(entries))
        val archive = File(root, "stored.zip")
        writeRawZip(
            archive = archive,
            entries = listOf(
                RECOVERY_MANIFEST_PATH to manifestBytes,
                RECOVERY_MANIFEST_HASH_PATH to "${sha256(manifestBytes)}\n".toByteArray(),
            ) + payloadBytesFor(entries),
            method = ZipEntry.STORED,
        )

        assertTrue(RecoveryArchiveValidator.validate(archive).isSuccess)
    }

    @Test
    fun `validator rejects per-entry comments and extra fields`() {
        val root = temporary.newFolder("entry-metadata")
        val entries = manifestEntries()
        val manifestBytes = RecoveryArchiveCodec.encode(manifest(entries))
        val archiveEntries = listOf(
            RECOVERY_MANIFEST_PATH to manifestBytes,
            RECOVERY_MANIFEST_HASH_PATH to "${sha256(manifestBytes)}\n".toByteArray(),
        ) + payloadBytesFor(entries)

        val commented = File(root, "entry-comment.zip")
        writeRawZip(
            archive = commented,
            entries = archiveEntries,
            entryComment = "not canonical",
        )
        val extra = File(root, "entry-extra.zip")
        writeRawZip(
            archive = extra,
            entries = archiveEntries,
            entryExtra = byteArrayOf(0xfe.toByte(), 0xca.toByte(), 0, 0),
        )

        assertTrue(RecoveryArchiveValidator.validate(commented).isFailure)
        assertTrue(RecoveryArchiveValidator.validate(extra).isFailure)
    }

    @Test
    fun `validator rejects bytes inserted between local records and the central ledger`() {
        val root = temporary.newFolder("unledgered-gap")
        val archive = File(root, "canonical.zip")
        RecoveryArchiveWriter.writeNew(archive, descriptor(), payloadSources(root)).getOrThrow()
        val malformed = File(root, "gap.zip")

        malformed.writeBytes(insertBeforeCentralDirectory(archive.readBytes(), byteArrayOf(1, 2, 3)))

        assertTrue(RecoveryArchiveValidator.validate(malformed).isFailure)
    }

    @Test
    fun `manifest rejects malformed UTF-8 even when its byte hash matches`() {
        val root = temporary.newFolder("manifest-utf8")
        val manifestBytes = byteArrayOf(
            '{'.code.toByte(),
            '"'.code.toByte(),
            0xc3.toByte(),
            0x28,
            '"'.code.toByte(),
            ':'.code.toByte(),
            '1'.code.toByte(),
            '}'.code.toByte(),
        )
        val archive = File(root, "malformed-utf8.zip")
        writeRawZip(
            archive = archive,
            entries = listOf(
                RECOVERY_MANIFEST_PATH to manifestBytes,
                RECOVERY_MANIFEST_HASH_PATH to "${sha256(manifestBytes)}\n".toByteArray(),
            ),
        )

        assertTrue(RecoveryArchiveValidator.validate(archive).isFailure)
    }

    @Test
    fun `manifest rejects duplicate members before last-member-wins decoding`() {
        val root = temporary.newFolder("manifest-duplicate")
        val entries = manifestEntries()
        val canonical = RecoveryArchiveCodec.encode(manifest(entries)).toString(StandardCharsets.UTF_8)
        val duplicated = canonical.replace(
            "\"formatVersion\":1",
            "\"formatVersion\":1,\"formatVersion\":2",
        ).toByteArray(StandardCharsets.UTF_8)
        val archive = File(root, "duplicate-member.zip")
        writeRawZip(
            archive = archive,
            entries = listOf(
                RECOVERY_MANIFEST_PATH to duplicated,
                RECOVERY_MANIFEST_HASH_PATH to "${sha256(duplicated)}\n".toByteArray(),
            ) + payloadBytesFor(entries),
        )

        assertTrue(RecoveryArchiveValidator.validate(archive).isFailure)
    }

    private fun descriptor() = RecoveryArchiveDescriptorV1(
        createdAtUtcMillis = 1_753_270_000_000L,
        producedByApp = "com.gruning.mimir.rewrite",
        producedByVersionName = "0.1.0-alpha.1",
        producedByVersionCode = 3,
        schemaVersion = 9,
        excluded = exclusions(),
        snapshot = RecoveryArchiveSnapshotV1(
            transactional = true,
            quiescent = false,
            journalMode = "wal",
        ),
    )

    private fun exclusions(): List<RecoveryArchiveExclusionV1> =
        RecoveryArchiveCoverageV1.requiredExclusions.sorted().map { key ->
            RecoveryArchiveExclusionV1(key, "Excluded by the Recovery Archive V1 contract.")
        }

    private fun payloadSources(root: File): List<RecoveryArchivePayloadSource> {
        val payloadRoot = File(root, "payload").apply { mkdirs() }
        val tables = RecoveryArchiveTablesV1.paths.map { path ->
            val file = File(payloadRoot, path.substringAfterLast('/'))
            val rowCount = if (path == "db/games.jsonl") 1L else 0L
            file.writeBytes(
                if (rowCount == 1L) {
                    "{\"entryKey\":\"entry/one\",\"gameId\":\"game/one\"}\n".toByteArray()
                } else {
                    byteArrayOf()
                },
            )
            RecoveryArchivePayloadSource(
                path = path,
                contentClass = if (path == "db/title_metadata_cache.jsonl") {
                    RECOVERY_CONTENT_DERIVED
                } else {
                    RECOVERY_CONTENT_CORE
                },
                rowCount = rowCount,
                file = file,
            )
        }
        val compat = File(payloadRoot, "migration-bundle.json").apply {
            writeText("{\"bundleVersion\":1,\"kind\":\"mimir.migration.bundle\"}")
        }
        return tables + RecoveryArchivePayloadSource(
            path = RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH,
            contentClass = RECOVERY_CONTENT_COMPAT,
            file = compat,
        )
    }

    private fun manifestEntries(): List<RecoveryArchiveEntryV1> {
        val tableEntries = RecoveryArchiveTablesV1.paths.map { path ->
            val bytes = if (path == "db/games.jsonl") {
                "{\"entryKey\":\"entry/one\",\"gameId\":\"game/one\"}\n".toByteArray()
            } else {
                byteArrayOf()
            }
            RecoveryArchiveEntryV1(
                path = path,
                uncompressedSize = bytes.size.toLong(),
                sha256 = sha256(bytes),
                rowCount = if (bytes.isEmpty()) 0 else 1,
                contentClass = if (path == "db/title_metadata_cache.jsonl") {
                    RECOVERY_CONTENT_DERIVED
                } else {
                    RECOVERY_CONTENT_CORE
                },
            )
        }
        val compat = "{\"bundleVersion\":1,\"kind\":\"mimir.migration.bundle\"}".toByteArray()
        return (tableEntries + RecoveryArchiveEntryV1(
            path = RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH,
            uncompressedSize = compat.size.toLong(),
            sha256 = sha256(compat),
            contentClass = RECOVERY_CONTENT_COMPAT,
        )).sortedBy(RecoveryArchiveEntryV1::path)
    }

    private fun manifest(
        entries: List<RecoveryArchiveEntryV1>,
        excluded: List<RecoveryArchiveExclusionV1> = exclusions(),
    ) = RecoveryArchiveManifestV1(
        createdAtUtcMillis = 1_753_270_000_000L,
        producedByApp = "com.gruning.mimir.rewrite",
        producedByVersionName = "0.1.0-alpha.1",
        producedByVersionCode = 3,
        schemaVersion = 9,
        entries = entries.sortedBy(RecoveryArchiveEntryV1::path),
        excluded = excluded,
        snapshot = RecoveryArchiveSnapshotV1(true, false, "wal"),
    )

    private fun payloadBytesFor(
        entries: List<RecoveryArchiveEntryV1>,
    ): List<Pair<String, ByteArray>> = entries.map { entry ->
        val bytes = when {
            entry.path == "db/games.jsonl" ->
                "{\"entryKey\":\"entry/one\",\"gameId\":\"game/one\"}\n".toByteArray()
            entry.path.startsWith("db/") -> byteArrayOf()
            else -> "{\"bundleVersion\":1,\"kind\":\"mimir.migration.bundle\"}".toByteArray()
        }
        entry.path to bytes
    }

    private fun writeRawZip(
        archive: File,
        entries: List<Pair<String, ByteArray>>,
        timestamp: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0, 2),
        comment: String? = null,
        entryComment: String? = null,
        entryExtra: ByteArray? = null,
        method: Int = ZipEntry.DEFLATED,
    ) {
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.setLevel(6)
            comment?.let(zip::setComment)
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(
                    ZipEntry(path).apply {
                        this.method = method
                        setTimeLocal(timestamp)
                        this.comment = entryComment
                        extra = entryExtra
                        if (method == ZipEntry.STORED) {
                            val checksum = CRC32().apply { update(bytes) }
                            size = bytes.size.toLong()
                            compressedSize = bytes.size.toLong()
                            crc = checksum.value
                        }
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun insertBeforeCentralDirectory(
        archive: ByteArray,
        inserted: ByteArray,
    ): ByteArray {
        val eocdOffset = archive.size - 22
        check(readLittleEndianInt(archive, eocdOffset) == 0x06054b50)
        val centralOffset = readLittleEndianInt(archive, eocdOffset + 16)
        val result = archive.copyOf(archive.size + inserted.size)
        System.arraycopy(
            archive,
            centralOffset,
            result,
            centralOffset + inserted.size,
            archive.size - centralOffset,
        )
        System.arraycopy(inserted, 0, result, centralOffset, inserted.size)
        writeLittleEndianInt(
            result,
            eocdOffset + inserted.size + 16,
            centralOffset + inserted.size,
        )
        return result
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun writeLittleEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun payloadBytes(archive: File): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = ByteArrayOutputStream()
                zip.copyTo(output)
                if (entry.name != RECOVERY_MANIFEST_PATH &&
                    entry.name != RECOVERY_MANIFEST_HASH_PATH
                ) {
                    result[entry.name] = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
