package com.gruning.mimir.rewrite.core.model.recovery

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryArchiveCapacityTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `manifest accepts exact total byte ceiling and rejects one byte more`() {
        val required = requiredManifestEntries(compatibilitySize = 1L)
        val exactFutureSizes = listOf(
            RECOVERY_MAX_ENTRY_BYTES,
            RECOVERY_MAX_ENTRY_BYTES,
            RECOVERY_MAX_ENTRY_BYTES,
            RECOVERY_MAX_ENTRY_BYTES - 1L,
        )
        val exact = manifest(
            required + exactFutureSizes.mapIndexed { index, size ->
                futureManifestEntry("future/total-$index.bin", size)
            },
        )

        assertEquals(
            RECOVERY_MAX_TOTAL_UNCOMPRESSED_BYTES,
            exact.entries.sumOf(RecoveryArchiveEntryV1::uncompressedSize),
        )
        assertTrue(RecoveryArchiveCodec.validateManifest(exact).isSuccess)

        val over = exact.copy(
            entries = exact.entries.map { entry ->
                if (entry.path == "future/total-3.bin") {
                    entry.copy(uncompressedSize = entry.uncompressedSize + 1L)
                } else {
                    entry
                }
            },
        )
        assertEquals(
            RECOVERY_MAX_TOTAL_UNCOMPRESSED_BYTES + 1L,
            over.entries.sumOf(RecoveryArchiveEntryV1::uncompressedSize),
        )
        assertTrue(RecoveryArchiveCodec.validateManifest(over).isFailure)
    }

    @Test
    fun `writer accepts exactly 256 payloads and refuses 257 without publication`() {
        val root = temporary.newFolder("entry-count")
        val required = requiredPayloadSources(root)
        val additive = (0 until RECOVERY_MAX_PAYLOAD_ENTRIES - required.size).map { index ->
            val file = File(root, "entry-$index.bin").apply { writeBytes(byteArrayOf(index.toByte())) }
            RecoveryArchivePayloadSource(
                path = "future/entry-${index.toString().padStart(3, '0')}.bin",
                contentClass = "capacity-fixture",
                file = file,
            )
        }
        val exactDestination = File(root, "exact-count.mimir-recovery.zip")
        val exact = RecoveryArchiveWriter.writeNew(
            destination = exactDestination,
            descriptor = descriptor(),
            payloads = required + additive,
        ).getOrThrow()

        assertEquals(RECOVERY_MAX_PAYLOAD_ENTRIES, exact.manifest.entries.size)
        assertTrue(exactDestination.isFile)

        val extraFile = File(root, "entry-extra.bin").apply { writeBytes(byteArrayOf(1)) }
        val overDestination = File(root, "over-count.mimir-recovery.zip")
        val over = RecoveryArchiveWriter.writeNew(
            destination = overDestination,
            descriptor = descriptor(),
            payloads = required + additive + RecoveryArchivePayloadSource(
                path = "future/entry-extra.bin",
                contentClass = "capacity-fixture",
                file = extraFile,
            ),
        )

        assertTrue(over.isFailure)
        assertFalse(overDestination.exists())
        assertNoPartialFiles(root)
    }

    @Test
    fun `manifest accepts exact row ceiling and rejects one row more`() {
        val exactEntries = requiredManifestEntries().map { entry ->
            if (entry.path == "db/games.jsonl") {
                entry.copy(rowCount = RECOVERY_MAX_TABLE_ROWS)
            } else {
                entry
            }
        }
        assertTrue(RecoveryArchiveCodec.validateManifest(manifest(exactEntries)).isSuccess)

        val overEntries = exactEntries.map { entry ->
            if (entry.path == "db/games.jsonl") {
                entry.copy(rowCount = RECOVERY_MAX_TABLE_ROWS + 1L)
            } else {
                entry
            }
        }
        assertTrue(RecoveryArchiveCodec.validateManifest(manifest(overEntries)).isFailure)
    }

    @Test
    fun `validator admits exact archive byte ceiling to ZIP parsing and refuses one byte more`() {
        val root = temporary.newFolder("archive-ceiling")
        val exact = File(root, "exact-size-not-a-zip.mimir-recovery.zip")
        sparseFile(exact, RECOVERY_MAX_ARCHIVE_BYTES)
        val exactResult = RecoveryArchiveValidator.validate(exact)

        assertTrue(exactResult.isFailure)
        assertTrue(
            exactResult.exceptionOrNull()?.message.orEmpty().contains("ZIP tail"),
        )

        val over = File(root, "oversize.mimir-recovery.zip")
        sparseFile(over, RECOVERY_MAX_ARCHIVE_BYTES + 1L)
        val overResult = RecoveryArchiveValidator.validate(over)

        assertTrue(overResult.isFailure)
        assertTrue(
            overResult.exceptionOrNull()?.message.orEmpty().contains("file size"),
        )
        assertEquals(RECOVERY_MAX_ARCHIVE_BYTES + 1L, over.length())
    }

    @Test
    fun `cancellation after staged bytes removes partial and never publishes`() {
        val root = temporary.newFolder("mid-write-cancel")
        val payload = File(root, "cancel.bin")
        writeDeterministicBytes(payload, 8 * 1024 * 1024)
        val destination = File(root, "cancelled.mimir-recovery.zip")
        var sawPartial = false
        var sawWrittenPartial = false

        val result = RecoveryArchiveWriter.writeNew(
            destination = destination,
            descriptor = descriptor(),
            payloads = requiredPayloadSources(root) + RecoveryArchivePayloadSource(
                path = "future/cancel.bin",
                contentClass = "capacity-fixture",
                file = payload,
            ),
            shouldContinue = {
                val partial = root.listFiles().orEmpty().firstOrNull { it.name.endsWith(".partial") }
                if (partial != null) {
                    sawPartial = true
                    if (partial.length() > 0L) {
                        sawWrittenPartial = true
                        false
                    } else {
                        true
                    }
                } else {
                    true
                }
            },
        )

        assertTrue(result.exceptionOrNull() is RecoveryArchiveCancelledException)
        assertTrue("cancellation must happen after the staged file exists", sawPartial)
        assertTrue("cancellation must happen after staged bytes reach the filesystem", sawWrittenPartial)
        assertFalse(destination.exists())
        assertNoPartialFiles(root)
    }

    @Test
    fun `opt in host proof streams exact max entry and refuses one byte more`() {
        assumeTrue(
            "Set MIMIR_RECOVERY_CAPACITY=1 to execute the near-limit streaming proof.",
            System.getenv("MIMIR_RECOVERY_CAPACITY") == "1",
        )
        val root = temporary.newFolder("max-entry")
        val maximum = File(root, "maximum.bin")
        sparseFile(maximum, RECOVERY_MAX_ENTRY_BYTES)
        val destination = File(root, "maximum.mimir-recovery.zip")
        System.gc()
        var maxSampledJvmUsedHeapBytes = usedHeapBytes()

        val written = RecoveryArchiveWriter.writeNew(
            destination = destination,
            descriptor = descriptor(),
            payloads = requiredPayloadSources(root) + RecoveryArchivePayloadSource(
                path = "future/maximum.bin",
                contentClass = "capacity-fixture",
                file = maximum,
            ),
            shouldContinue = {
                maxSampledJvmUsedHeapBytes = maxOf(
                    maxSampledJvmUsedHeapBytes,
                    usedHeapBytes(),
                )
                true
            },
        ).getOrThrow()

        assertEquals(
            RECOVERY_MAX_ENTRY_BYTES,
            written.manifest.entries.single { it.path == "future/maximum.bin" }.uncompressedSize,
        )
        assertTrue(destination.length() in 1..RECOVERY_MAX_ARCHIVE_BYTES)
        assertTrue(
            "streaming proof sampled $maxSampledJvmUsedHeapBytes used JVM heap bytes",
            maxSampledJvmUsedHeapBytes < RECOVERY_MAX_ENTRY_BYTES / 2L,
        )
        assertNoPartialFiles(root)

        val overMaximum = File(root, "over-maximum.bin")
        sparseFile(overMaximum, RECOVERY_MAX_ENTRY_BYTES + 1L)
        val refusedDestination = File(root, "over-maximum.mimir-recovery.zip")
        val refused = RecoveryArchiveWriter.writeNew(
            destination = refusedDestination,
            descriptor = descriptor(),
            payloads = requiredPayloadSources(root) + RecoveryArchivePayloadSource(
                path = "future/over-maximum.bin",
                contentClass = "capacity-fixture",
                file = overMaximum,
            ),
        )

        assertTrue(refused.isFailure)
        assertFalse(refusedDestination.exists())
        assertNoPartialFiles(root)
        println(
            "RECOVERY_CAPACITY_METRIC " +
                "payloadBytes=$RECOVERY_MAX_ENTRY_BYTES " +
                "maxSampledJvmUsedHeapBytes=$maxSampledJvmUsedHeapBytes " +
                "archiveBytes=${destination.length()}",
        )
    }

    @Test
    fun `opt in host proof writes and validates a near one gib incompressible archive`() {
        assumeTrue(
            "Set MIMIR_RECOVERY_CAPACITY_EXTREMES=1 to execute the archive-ceiling proof.",
            System.getenv("MIMIR_RECOVERY_CAPACITY_EXTREMES") == "1",
        )
        val root = temporary.newFolder("near-archive-ceiling")
        try {
            assumeTrue(
                "Extreme recovery proof requires at least 8 GiB usable host storage.",
                root.usableSpace >= 8L * 1024L * 1024L * 1024L,
            )
            val payloadBytes = 510L * 1024L * 1024L
            val first = File(root, "incompressible-a.bin")
            val second = File(root, "incompressible-b.bin")
            writeDeterministicBytes(first, payloadBytes, seed = 0x455030364831L)
            writeDeterministicBytes(second, payloadBytes, seed = 0x455030364832L)
            val destination = File(root, "near-ceiling.mimir-recovery.zip")
            System.gc()
            var maxSampledJvmUsedHeapBytes = usedHeapBytes()
            val startedNanos = System.nanoTime()
            val sampleHeapAndContinue = {
                maxSampledJvmUsedHeapBytes = maxOf(
                    maxSampledJvmUsedHeapBytes,
                    usedHeapBytes(),
                )
                true
            }

            val written = RecoveryArchiveWriter.writeNew(
                destination = destination,
                descriptor = descriptor(),
                payloads = requiredPayloadSources(root) + listOf(
                    RecoveryArchivePayloadSource(
                        path = "future/incompressible-a.bin",
                        contentClass = "capacity-fixture",
                        file = first,
                    ),
                    RecoveryArchivePayloadSource(
                        path = "future/incompressible-b.bin",
                        contentClass = "capacity-fixture",
                        file = second,
                    ),
                ),
                shouldContinue = sampleHeapAndContinue,
            ).getOrThrow()
            val validated = RecoveryArchiveValidator.validate(
                destination,
                sampleHeapAndContinue,
            ).getOrThrow()
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L

            assertEquals(written.manifestSha256, validated.manifestSha256)
            assertEquals(
                payloadBytes * 2L + 2L,
                written.manifest.entries.sumOf(RecoveryArchiveEntryV1::uncompressedSize),
            )
            assertTrue(
                "archive must be genuinely near the 1 GiB ceiling: ${destination.length()}",
                destination.length() in
                    (RECOVERY_MAX_ARCHIVE_BYTES - 16L * 1024L * 1024L)..RECOVERY_MAX_ARCHIVE_BYTES,
            )
            assertTrue(
                "streaming proof sampled $maxSampledJvmUsedHeapBytes used JVM heap bytes",
                maxSampledJvmUsedHeapBytes < 256L * 1024L * 1024L,
            )
            assertNoPartialFiles(root)
            println(
                "RECOVERY_CAPACITY_H1 " +
                    "inputBytes=${payloadBytes * 2L + 2L} " +
                    "archiveBytes=${destination.length()} " +
                    "manifestSha256=${written.manifestSha256} " +
                    "elapsedMillis=$elapsedMillis " +
                    "maxSampledJvmUsedHeapBytes=$maxSampledJvmUsedHeapBytes",
            )
        } finally {
            assertTrue(root.deleteRecursively())
            assertFalse(root.exists())
        }
    }

    @Test
    fun `opt in host proof writes and validates exact two gib total uncompressed`() {
        assumeTrue(
            "Set MIMIR_RECOVERY_CAPACITY_EXTREMES=1 to execute the total-stream ceiling proof.",
            System.getenv("MIMIR_RECOVERY_CAPACITY_EXTREMES") == "1",
        )
        val root = temporary.newFolder("exact-total-ceiling")
        try {
            assumeTrue(
                "Extreme recovery proof requires at least 8 GiB usable host storage.",
                root.usableSpace >= 8L * 1024L * 1024L * 1024L,
            )
            val payloadSizes = listOf(
                RECOVERY_MAX_ENTRY_BYTES,
                RECOVERY_MAX_ENTRY_BYTES,
                RECOVERY_MAX_ENTRY_BYTES,
                RECOVERY_MAX_ENTRY_BYTES - 2L,
            )
            val payloads = payloadSizes.mapIndexed { index, size ->
                val file = File(root, "total-$index.bin")
                sparseFile(file, size)
                RecoveryArchivePayloadSource(
                    path = "future/total-stream-$index.bin",
                    contentClass = "capacity-fixture",
                    file = file,
                )
            }
            val destination = File(root, "exact-total.mimir-recovery.zip")
            System.gc()
            var maxSampledJvmUsedHeapBytes = usedHeapBytes()
            val startedNanos = System.nanoTime()
            val sampleHeapAndContinue = {
                maxSampledJvmUsedHeapBytes = maxOf(
                    maxSampledJvmUsedHeapBytes,
                    usedHeapBytes(),
                )
                true
            }

            val written = RecoveryArchiveWriter.writeNew(
                destination = destination,
                descriptor = descriptor(),
                payloads = requiredPayloadSources(root) + payloads,
                shouldContinue = sampleHeapAndContinue,
            ).getOrThrow()
            val validated = RecoveryArchiveValidator.validate(
                destination,
                sampleHeapAndContinue,
            ).getOrThrow()
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L

            assertEquals(
                RECOVERY_MAX_TOTAL_UNCOMPRESSED_BYTES,
                written.manifest.entries.sumOf(RecoveryArchiveEntryV1::uncompressedSize),
            )
            assertEquals(written.manifestSha256, validated.manifestSha256)
            assertTrue(destination.length() in 1..RECOVERY_MAX_ARCHIVE_BYTES)
            assertTrue(
                "streaming proof sampled $maxSampledJvmUsedHeapBytes used JVM heap bytes",
                maxSampledJvmUsedHeapBytes < 256L * 1024L * 1024L,
            )
            assertNoPartialFiles(root)
            println(
                "RECOVERY_CAPACITY_H2 " +
                    "inputBytes=$RECOVERY_MAX_TOTAL_UNCOMPRESSED_BYTES " +
                    "archiveBytes=${destination.length()} " +
                    "manifestSha256=${written.manifestSha256} " +
                    "elapsedMillis=$elapsedMillis " +
                    "maxSampledJvmUsedHeapBytes=$maxSampledJvmUsedHeapBytes",
            )
        } finally {
            assertTrue(root.deleteRecursively())
            assertFalse(root.exists())
        }
    }

    private fun descriptor() = RecoveryArchiveDescriptorV1(
        createdAtUtcMillis = 1_753_270_000_000L,
        producedByApp = "com.gruning.mimir.rewrite",
        producedByVersionName = "0.1.0-alpha.1",
        producedByVersionCode = 3,
        schemaVersion = 9,
        excluded = RecoveryArchiveCoverageV1.requiredExclusions.sorted().map { key ->
            RecoveryArchiveExclusionV1(
                what = key,
                reason = "Excluded by the Recovery Archive V1 contract.",
            )
        },
        snapshot = RecoveryArchiveSnapshotV1(
            transactional = true,
            quiescent = false,
            journalMode = "wal",
        ),
    )

    private fun requiredPayloadSources(root: File): List<RecoveryArchivePayloadSource> {
        val payloadRoot = File(root, "required-${System.nanoTime()}").apply { mkdirs() }
        val tables = RecoveryArchiveTablesV1.paths.mapIndexed { index, path ->
            val file = File(payloadRoot, "table-$index.jsonl").apply { writeBytes(byteArrayOf()) }
            RecoveryArchivePayloadSource(
                path = path,
                contentClass = if (path == "db/title_metadata_cache.jsonl") {
                    RECOVERY_CONTENT_DERIVED
                } else {
                    RECOVERY_CONTENT_CORE
                },
                rowCount = 0L,
                file = file,
            )
        }
        val compatibility = File(payloadRoot, "migration-bundle.json").apply {
            writeText("{}")
        }
        return tables + RecoveryArchivePayloadSource(
            path = RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH,
            contentClass = RECOVERY_CONTENT_COMPAT,
            file = compatibility,
        )
    }

    private fun requiredManifestEntries(
        compatibilitySize: Long = 2L,
    ): List<RecoveryArchiveEntryV1> {
        val tables = RecoveryArchiveTablesV1.paths.map { path ->
            RecoveryArchiveEntryV1(
                path = path,
                uncompressedSize = 0L,
                sha256 = EMPTY_SHA256,
                rowCount = 0L,
                contentClass = if (path == "db/title_metadata_cache.jsonl") {
                    RECOVERY_CONTENT_DERIVED
                } else {
                    RECOVERY_CONTENT_CORE
                },
            )
        }
        return tables + RecoveryArchiveEntryV1(
            path = RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH,
            uncompressedSize = compatibilitySize,
            sha256 = ZERO_SHA256,
            contentClass = RECOVERY_CONTENT_COMPAT,
        )
    }

    private fun futureManifestEntry(path: String, size: Long) = RecoveryArchiveEntryV1(
        path = path,
        uncompressedSize = size,
        sha256 = ZERO_SHA256,
        contentClass = "capacity-fixture",
    )

    private fun manifest(entries: List<RecoveryArchiveEntryV1>) = RecoveryArchiveManifestV1(
        createdAtUtcMillis = 1_753_270_000_000L,
        producedByApp = "com.gruning.mimir.rewrite",
        producedByVersionName = "0.1.0-alpha.1",
        producedByVersionCode = 3,
        schemaVersion = 9,
        entries = entries.sortedBy(RecoveryArchiveEntryV1::path),
        excluded = descriptor().excluded,
        snapshot = descriptor().snapshot,
    )

    private fun sparseFile(file: File, size: Long) {
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(size)
        }
    }

    private fun writeDeterministicBytes(file: File, size: Int) {
        writeDeterministicBytes(file, size.toLong(), 0x4d494d4952L)
    }

    private fun writeDeterministicBytes(
        file: File,
        size: Long,
        seed: Long,
    ) {
        val random = Random(seed)
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        FileOutputStream(file).use { output ->
            while (remaining > 0L) {
                random.nextBytes(buffer)
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                output.write(buffer, 0, count)
                remaining -= count.toLong()
            }
            output.fd.sync()
        }
    }

    private fun usedHeapBytes(): Long = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

    private fun assertNoPartialFiles(root: File) {
        assertTrue(
            root.listFiles().orEmpty().none { it.name.endsWith(".partial") },
        )
    }

    private companion object {
        val EMPTY_SHA256: String = MessageDigest.getInstance("SHA-256")
            .digest(byteArrayOf())
            .joinToString("") { "%02x".format(it) }
        const val ZERO_SHA256: String =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
