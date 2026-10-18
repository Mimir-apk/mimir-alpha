package com.gruning.mimir.rewrite.core.model.recovery

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

private const val IO_BUFFER_BYTES = 64 * 1024
private const val ZIP_EOCD_MIN_BYTES = 22
private const val ZIP_MAX_COMMENT_BYTES = 65_535
private const val ZIP_EOCD_SIGNATURE = 0x06054b50
private const val ZIP_CENTRAL_SIGNATURE = 0x02014b50
private const val ZIP_LOCAL_SIGNATURE = 0x04034b50
private const val ZIP_DATA_DESCRIPTOR_SIGNATURE = 0x08074b50
private const val ZIP_FLAG_DATA_DESCRIPTOR = 0x0008
private const val ZIP_FLAG_UTF8 = 0x0800
private const val ZIP_DOS_TIME = 1
private const val ZIP_DOS_DATE = 0x0021
private val RECOVERY_ZIP_LOCAL_TIME: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0, 2)

data class RecoveryArchiveDescriptorV1(
    val createdAtUtcMillis: Long,
    val producedByApp: String,
    val producedByVersionName: String,
    val producedByVersionCode: Long,
    val schemaVersion: Int,
    val excluded: List<RecoveryArchiveExclusionV1>,
    val snapshot: RecoveryArchiveSnapshotV1,
)

data class RecoveryArchivePayloadSource(
    val path: String,
    val contentClass: String,
    val rowCount: Long? = null,
    val file: File,
)

data class RecoveryArchiveWriteResult(
    val archive: File,
    val manifest: RecoveryArchiveManifestV1,
    val manifestSha256: String,
)

data class ValidatedRecoveryArchive(
    val archive: File,
    val manifest: RecoveryArchiveManifestV1,
    val manifestSha256: String,
    val archiveSize: Long,
)

class RecoveryArchiveCancelledException : RecoveryArchiveException("Recovery archive operation was cancelled.")

/**
 * Produces a new, internally staged archive. The destination must not exist. Payloads are read twice:
 * once to build the manifest and once while writing; any between-pass mutation aborts the archive.
 */
object RecoveryArchiveWriter {
    fun writeNew(
        destination: File,
        descriptor: RecoveryArchiveDescriptorV1,
        payloads: List<RecoveryArchivePayloadSource>,
        shouldContinue: () -> Boolean = { true },
    ): Result<RecoveryArchiveWriteResult> = runCatching {
        checkContinue(shouldContinue)
        val parent = destination.absoluteFile.parentFile
            ?: throw RecoveryArchiveException("Recovery archive destination has no parent directory.")
        if (!parent.isDirectory || Files.isSymbolicLink(parent.toPath())) {
            throw RecoveryArchiveException("Recovery archive parent is not a safe directory.")
        }
        if (destination.exists() || Files.isSymbolicLink(destination.toPath())) {
            throw RecoveryArchiveException("Recovery archive destination must be a new file.")
        }

        val prepared = preparePayloads(payloads, shouldContinue)
        val manifest = RecoveryArchiveManifestV1(
            createdAtUtcMillis = descriptor.createdAtUtcMillis,
            producedByApp = descriptor.producedByApp,
            producedByVersionName = descriptor.producedByVersionName,
            producedByVersionCode = descriptor.producedByVersionCode,
            schemaVersion = descriptor.schemaVersion,
            entries = prepared.map(PreparedPayload::entry),
            excluded = descriptor.excluded.sortedBy(RecoveryArchiveExclusionV1::what),
            snapshot = descriptor.snapshot,
        )
        val manifestBytes = RecoveryArchiveCodec.encode(manifest)
        val manifestSha256 = RecoveryArchiveCodec.sha256(manifestBytes)
        val manifestHashBytes = "$manifestSha256\n".toByteArray(StandardCharsets.US_ASCII)
        check(manifestHashBytes.size.toLong() == RECOVERY_MANIFEST_HASH_BYTES)

        val partial = File(
            parent,
            ".${destination.name}.${UUID.randomUUID()}.partial",
        )
        var promoted = false
        try {
            writeArchive(
                partial = partial,
                manifestBytes = manifestBytes,
                manifestHashBytes = manifestHashBytes,
                payloads = prepared,
                shouldContinue = shouldContinue,
            )
            syncDirectory(parent)
            RecoveryArchiveValidator.validate(partial, shouldContinue).getOrThrow()
            checkContinue(shouldContinue)
            try {
                Files.move(partial.toPath(), destination.toPath(), ATOMIC_MOVE)
            } catch (error: AtomicMoveNotSupportedException) {
                throw RecoveryArchiveException("Recovery staging filesystem cannot publish atomically.", error)
            }
            promoted = true
            syncDirectory(parent)
            RecoveryArchiveWriteResult(
                archive = destination,
                manifest = manifest,
                manifestSha256 = manifestSha256,
            )
        } catch (error: Throwable) {
            partial.delete()
            if (promoted) destination.delete()
            runCatching { syncDirectory(parent) }
            throw error
        }
    }

    private fun preparePayloads(
        payloads: List<RecoveryArchivePayloadSource>,
        shouldContinue: () -> Boolean,
    ): List<PreparedPayload> {
        if (payloads.isEmpty() || payloads.size > RECOVERY_MAX_PAYLOAD_ENTRIES) {
            throw RecoveryArchiveException("Recovery payload count is outside the supported range.")
        }
        val foldedPaths = HashSet<String>()
        var totalBytes = 0L
        return payloads.sortedBy(RecoveryArchivePayloadSource::path).map { source ->
            checkContinue(shouldContinue)
            validateRecoveryPath(source.path)
            if (!foldedPaths.add(source.path.lowercase(Locale.ROOT))) {
                throw RecoveryArchiveException("Recovery payload paths collide after case folding.")
            }
            val sourcePath = source.file.toPath()
            if (!Files.isRegularFile(sourcePath, NOFOLLOW_LINKS)) {
                throw RecoveryArchiveException("Recovery payload is not a regular file: ${source.path}")
            }
            val inspection = inspectFile(source.file, shouldContinue)
            val isTable = source.path.startsWith("db/") && source.path.endsWith(".jsonl")
            val minimumSize = if (isTable) 0L else 1L
            if (inspection.size !in minimumSize..RECOVERY_MAX_ENTRY_BYTES) {
                throw RecoveryArchiveException("Recovery payload size is outside the supported range: ${source.path}")
            }
            totalBytes = Math.addExact(totalBytes, inspection.size)
            if (totalBytes > RECOVERY_MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw RecoveryArchiveException("Recovery payloads exceed the total uncompressed limit.")
            }
            if (isTable) {
                if (source.rowCount == null || source.rowCount !in 0..RECOVERY_MAX_TABLE_ROWS) {
                    throw RecoveryArchiveException("Recovery table row count is invalid: ${source.path}")
                }
            } else if (source.rowCount != null) {
                throw RecoveryArchiveException("Only JSONL tables may declare row counts.")
            }
            PreparedPayload(
                source = source,
                expectedCrc = inspection.crc,
                entry = RecoveryArchiveEntryV1(
                    path = source.path,
                    uncompressedSize = inspection.size,
                    sha256 = inspection.sha256,
                    rowCount = source.rowCount,
                    contentClass = source.contentClass,
                ),
            )
        }
    }

    private fun writeArchive(
        partial: File,
        manifestBytes: ByteArray,
        manifestHashBytes: ByteArray,
        payloads: List<PreparedPayload>,
        shouldContinue: () -> Boolean,
    ) {
        FileOutputStream(partial).use { fileOutput ->
            val bounded = BoundedRecoveryArchiveOutputStream(fileOutput)
            val buffered = BufferedOutputStream(bounded, IO_BUFFER_BYTES)
            val zip = ZipOutputStream(buffered).apply { setLevel(6) }
            try {
                writeBytesEntry(zip, RECOVERY_MANIFEST_PATH, manifestBytes)
                writeBytesEntry(zip, RECOVERY_MANIFEST_HASH_PATH, manifestHashBytes)
                val buffer = ByteArray(IO_BUFFER_BYTES)
                payloads.forEach { payload ->
                    checkContinue(shouldContinue)
                    val zipEntry = recoveryZipEntry(payload.entry.path)
                    zip.putNextEntry(zipEntry)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val crc = CRC32()
                    var count = 0L
                    FileInputStream(payload.source.file).use { input ->
                        while (true) {
                            checkContinue(shouldContinue)
                            val read = input.read(buffer)
                            if (read < 0) break
                            count = Math.addExact(count, read.toLong())
                            if (count > payload.entry.uncompressedSize) {
                                throw RecoveryArchiveException(
                                    "Recovery payload changed while exporting: ${payload.entry.path}",
                                )
                            }
                            digest.update(buffer, 0, read)
                            crc.update(buffer, 0, read)
                            zip.write(buffer, 0, read)
                        }
                    }
                    if (count != payload.entry.uncompressedSize ||
                        digest.digest().toLowerHex() != payload.entry.sha256 ||
                        crc.value != payload.expectedCrc
                    ) {
                        throw RecoveryArchiveException(
                            "Recovery payload changed while exporting: ${payload.entry.path}",
                        )
                    }
                    zip.closeEntry()
                }
                zip.finish()
                zip.flush()
                buffered.flush()
                fileOutput.fd.sync()
            } finally {
                zip.close()
            }
        }
        if (partial.length() !in 1..RECOVERY_MAX_ARCHIVE_BYTES) {
            throw RecoveryArchiveException("Recovery archive file size is outside the supported range.")
        }
    }

    private fun writeBytesEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(recoveryZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun recoveryZipEntry(path: String): ZipEntry =
        ZipEntry(path).apply {
            method = ZipEntry.DEFLATED
            // ZipEntry.setTimeLocal() is present in the compile-SDK stubs but absent on Android 13.
            // Convert the canonical local DOS timestamp to an instant in the device zone instead.
            // ZipOutputStream converts it back to the same DOS fields without adding an extra field.
            time = RECOVERY_ZIP_LOCAL_TIME
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
}

/**
 * Integrity validation is local-header ordered and streaming. A raw second pass then proves that every
 * local record and central-directory row exactly matches that sequential ledger and deterministic ZIP
 * subset; central metadata never overrides streamed payload truth.
 */
object RecoveryArchiveValidator {
    fun validate(
        archive: File,
        shouldContinue: () -> Boolean = { true },
    ): Result<ValidatedRecoveryArchive> = runCatching {
        checkContinue(shouldContinue)
        if (!Files.isRegularFile(archive.toPath(), NOFOLLOW_LINKS)) {
            throw RecoveryArchiveException("Recovery archive is not a regular file.")
        }
        val archiveSize = archive.length()
        if (archiveSize !in 1..RECOVERY_MAX_ARCHIVE_BYTES) {
            throw RecoveryArchiveException("Recovery archive file size is outside the supported range.")
        }
        val tail = readZipTail(archive)
        ZipInputStream(BufferedInputStream(FileInputStream(archive), IO_BUFFER_BYTES)).use { zip ->
            val seen = HashSet<String>()
            val sequentialEntries = mutableListOf<SequentialZipEntry>()
            val manifestEntry = nextEntry(zip, RECOVERY_MANIFEST_PATH, seen)
            val manifestBytes = readEntryBytes(
                zip = zip,
                maximumBytes = RECOVERY_MAX_MANIFEST_BYTES,
                shouldContinue = shouldContinue,
            )
            zip.closeEntry()
            sequentialEntries += manifestEntry.toSequentialEntry()
            if (manifestEntry.size >= 0L && manifestEntry.size != manifestBytes.size.toLong()) {
                throw RecoveryArchiveException("Recovery manifest declared size does not match its bytes.")
            }
            val manifestSha256 = RecoveryArchiveCodec.sha256(manifestBytes)

            val manifestHashEntry = nextEntry(zip, RECOVERY_MANIFEST_HASH_PATH, seen)
            val manifestHashBytes = readEntryBytes(
                zip = zip,
                maximumBytes = RECOVERY_MANIFEST_HASH_BYTES,
                shouldContinue = shouldContinue,
            )
            zip.closeEntry()
            sequentialEntries += manifestHashEntry.toSequentialEntry()
            if (manifestHashEntry.size >= 0L &&
                manifestHashEntry.size != RECOVERY_MANIFEST_HASH_BYTES
            ) {
                throw RecoveryArchiveException("Recovery manifest hash entry has the wrong size.")
            }
            val expectedHashLine = "$manifestSha256\n".toByteArray(StandardCharsets.US_ASCII)
            if (!manifestHashBytes.contentEquals(expectedHashLine)) {
                throw RecoveryArchiveException("Recovery manifest hash does not match.")
            }

            val manifest = RecoveryArchiveCodec.parse(manifestBytes).getOrThrow()
            val expectedZipEntries = manifest.entries.size + 2
            if (tail.entryCount != expectedZipEntries) {
                throw RecoveryArchiveException("Recovery archive entry count does not match its ledger.")
            }

            manifest.entries.forEach { expected ->
                checkContinue(shouldContinue)
                val actualEntry = nextEntry(zip, expected.path, seen)
                val result = verifyPayload(zip, expected, shouldContinue)
                zip.closeEntry()
                sequentialEntries += actualEntry.toSequentialEntry()
                if (result.size != expected.uncompressedSize || result.sha256 != expected.sha256) {
                    throw RecoveryArchiveException("Recovery payload integrity failed: ${expected.path}")
                }
                if (actualEntry.size >= 0L && actualEntry.size != expected.uncompressedSize) {
                    throw RecoveryArchiveException("Recovery ZIP entry size differs from its ledger: ${expected.path}")
                }
                if (expected.rowCount != null && result.rowCount != expected.rowCount) {
                    throw RecoveryArchiveException("Recovery table row count does not match: ${expected.path}")
                }
            }
            if (zip.nextEntry != null) {
                throw RecoveryArchiveException("Recovery archive contains an unlisted payload.")
            }
            validateCentralDirectory(archive, tail, sequentialEntries)
            ValidatedRecoveryArchive(
                archive = archive,
                manifest = manifest,
                manifestSha256 = manifestSha256,
                archiveSize = archiveSize,
            )
        }
    }

    private fun nextEntry(
        zip: ZipInputStream,
        expectedPath: String,
        seen: MutableSet<String>,
    ): ZipEntry {
        val entry = zip.nextEntry
            ?: throw RecoveryArchiveException("Recovery archive is missing $expectedPath.")
        validateZipEntry(entry)
        if (entry.name != expectedPath) {
            throw RecoveryArchiveException(
                "Recovery archive entry order mismatch: expected $expectedPath, found ${entry.name}.",
            )
        }
        if (!seen.add(entry.name.lowercase(Locale.ROOT))) {
            throw RecoveryArchiveException("Recovery archive contains a duplicate entry.")
        }
        return entry
    }

    private fun validateZipEntry(entry: ZipEntry) {
        validateRecoveryPath(entry.name)
        if (entry.isDirectory) {
            throw RecoveryArchiveException("Recovery archive directories are not allowed.")
        }
        if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
            throw RecoveryArchiveException("Recovery archive compression method is unsupported.")
        }
        val localTimestamp = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(entry.time),
            ZoneId.systemDefault(),
        )
        if (localTimestamp != RECOVERY_ZIP_LOCAL_TIME) {
            throw RecoveryArchiveException("Recovery archive timestamp is not canonical.")
        }
    }

    private fun verifyPayload(
        zip: ZipInputStream,
        expected: RecoveryArchiveEntryV1,
        shouldContinue: () -> Boolean,
    ): PayloadVerification {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(IO_BUFFER_BYTES)
        val jsonl = if (expected.rowCount != null) JsonlVerifier() else null
        var count = 0L
        while (true) {
            checkContinue(shouldContinue)
            val read = zip.read(buffer)
            if (read < 0) break
            count = Math.addExact(count, read.toLong())
            if (count > expected.uncompressedSize) {
                throw RecoveryArchiveException("Recovery payload exceeds its declared size: ${expected.path}")
            }
            digest.update(buffer, 0, read)
            jsonl?.accept(buffer, read)
        }
        val rowCount = jsonl?.finish()
        return PayloadVerification(
            size = count,
            sha256 = digest.digest().toLowerHex(),
            rowCount = rowCount,
        )
    }
}

private class BoundedRecoveryArchiveOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {
    private var count = 0L

    override fun write(value: Int) {
        reserve(1)
        delegate.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IndexOutOfBoundsException()
        }
        reserve(length)
        delegate.write(bytes, offset, length)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    private fun reserve(length: Int) {
        count = Math.addExact(count, length.toLong())
        if (count > RECOVERY_MAX_ARCHIVE_BYTES) {
            throw RecoveryArchiveException("Recovery archive exceeds the supported file-size limit.")
        }
    }
}

private data class PreparedPayload(
    val source: RecoveryArchivePayloadSource,
    val expectedCrc: Long,
    val entry: RecoveryArchiveEntryV1,
)

private data class FileInspection(
    val size: Long,
    val sha256: String,
    val crc: Long,
)

private data class PayloadVerification(
    val size: Long,
    val sha256: String,
    val rowCount: Long?,
)

private data class SequentialZipEntry(
    val name: String,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
)

private data class ZipTail(
    val entryCount: Int,
    val centralOffset: Long,
    val centralSize: Long,
    val eocdOffset: Long,
)

private class JsonlVerifier {
    private val line = ByteArrayOutputStream()
    private var rows = 0L

    fun accept(bytes: ByteArray, length: Int) {
        repeat(length) { index ->
            val value = bytes[index].toInt() and 0xff
            if (value == '\r'.code) {
                throw RecoveryArchiveException("Recovery JSONL contains a non-canonical carriage return.")
            }
            line.write(value)
            if (line.size() > RECOVERY_MAX_JSONL_LINE_BYTES) {
                throw RecoveryArchiveException("Recovery JSONL line exceeds the supported limit.")
            }
            if (value == '\n'.code) {
                val raw = line.toByteArray()
                if (raw.size == 1) {
                    throw RecoveryArchiveException("Recovery JSONL contains an empty row.")
                }
                val text = strictUtf8(raw, raw.size - 1)
                val element = try {
                    RecoveryArchiveCodec.json.parseToJsonElement(text)
                } catch (error: SerializationException) {
                    throw RecoveryArchiveException("Recovery JSONL row is malformed.", error)
                }
                if (element !is JsonObject) {
                    throw RecoveryArchiveException("Recovery JSONL row must be a JSON object.")
                }
                rows = Math.addExact(rows, 1L)
                if (rows > RECOVERY_MAX_TABLE_ROWS) {
                    throw RecoveryArchiveException("Recovery JSONL row count exceeds the supported limit.")
                }
                line.reset()
            }
        }
    }

    fun finish(): Long {
        if (line.size() != 0) {
            throw RecoveryArchiveException("Recovery JSONL payload is not newline terminated.")
        }
        return rows
    }
}

private fun inspectFile(
    file: File,
    shouldContinue: () -> Boolean,
): FileInspection {
    val digest = MessageDigest.getInstance("SHA-256")
    val crc = CRC32()
    val buffer = ByteArray(IO_BUFFER_BYTES)
    var count = 0L
    FileInputStream(file).use { input ->
        while (true) {
            checkContinue(shouldContinue)
            val read = input.read(buffer)
            if (read < 0) break
            count = Math.addExact(count, read.toLong())
            if (count > RECOVERY_MAX_ENTRY_BYTES) {
                throw RecoveryArchiveException("Recovery payload exceeds the per-entry limit.")
            }
            digest.update(buffer, 0, read)
            crc.update(buffer, 0, read)
        }
    }
    return FileInspection(
        size = count,
        sha256 = digest.digest().toLowerHex(),
        crc = crc.value,
    )
}

private fun readEntryBytes(
    zip: ZipInputStream,
    maximumBytes: Long,
    shouldContinue: () -> Boolean,
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(IO_BUFFER_BYTES)
    var count = 0L
    while (true) {
        checkContinue(shouldContinue)
        val read = zip.read(buffer)
        if (read < 0) break
        count = Math.addExact(count, read.toLong())
        if (count > maximumBytes) {
            throw RecoveryArchiveException("Recovery archive metadata exceeds its supported limit.")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun readZipTail(file: File): ZipTail {
    RandomAccessFile(file, "r").use { input ->
        val fileLength = input.length()
        if (fileLength < ZIP_EOCD_MIN_BYTES) {
            throw RecoveryArchiveException("Recovery archive is truncated.")
        }
        val searchLength = minOf(
            fileLength,
            (ZIP_EOCD_MIN_BYTES + ZIP_MAX_COMMENT_BYTES).toLong(),
        ).toInt()
        val bytes = ByteArray(searchLength)
        input.seek(fileLength - searchLength)
        input.readFully(bytes)
        var offset = bytes.size - ZIP_EOCD_MIN_BYTES
        while (offset >= 0 && littleEndianInt(bytes, offset) != ZIP_EOCD_SIGNATURE) {
            offset--
        }
        if (offset < 0) {
            throw RecoveryArchiveException("Recovery archive has no complete ZIP tail.")
        }
        val commentLength = littleEndianShort(bytes, offset + 20)
        if (commentLength != 0 || offset + ZIP_EOCD_MIN_BYTES != bytes.size) {
            throw RecoveryArchiveException("Recovery archive ZIP tail is not at end of file.")
        }
        val diskNumber = littleEndianShort(bytes, offset + 4)
        val centralDisk = littleEndianShort(bytes, offset + 6)
        val entriesOnDisk = littleEndianShort(bytes, offset + 8)
        val totalEntries = littleEndianShort(bytes, offset + 10)
        val centralSize = littleEndianUnsignedInt(bytes, offset + 12)
        val centralOffset = littleEndianUnsignedInt(bytes, offset + 16)
        if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != totalEntries) {
            throw RecoveryArchiveException("Multi-disk recovery archives are not supported.")
        }
        if (entriesOnDisk == 0xffff ||
            centralSize == 0xffff_ffffL ||
            centralOffset == 0xffff_ffffL
        ) {
            throw RecoveryArchiveException("ZIP64 recovery archives are not supported.")
        }
        val absoluteEocdOffset = fileLength - searchLength + offset
        if (centralOffset + centralSize != absoluteEocdOffset) {
            throw RecoveryArchiveException("Recovery archive central-directory boundary is malformed.")
        }
        return ZipTail(
            entryCount = totalEntries,
            centralOffset = centralOffset,
            centralSize = centralSize,
            eocdOffset = absoluteEocdOffset,
        )
    }
}

private fun ZipEntry.toSequentialEntry(): SequentialZipEntry {
    if (crc < 0L || compressedSize < 0L || size < 0L) {
        throw RecoveryArchiveException("Recovery ZIP entry metadata did not settle after streaming.")
    }
    return SequentialZipEntry(
        name = name,
        method = method,
        crc = crc,
        compressedSize = compressedSize,
        uncompressedSize = size,
    )
}

private fun validateCentralDirectory(
    file: File,
    tail: ZipTail,
    sequential: List<SequentialZipEntry>,
) {
    if (sequential.size != tail.entryCount) {
        throw RecoveryArchiveException("Recovery ZIP central entry count is inconsistent.")
    }
    RandomAccessFile(file, "r").use { input ->
        input.seek(tail.centralOffset)
        var expectedLocalOffset = 0L
        sequential.forEach { expected ->
            if (readLittleEndianU32(input) != ZIP_CENTRAL_SIGNATURE.toLong()) {
                throw RecoveryArchiveException("Recovery ZIP central header is malformed.")
            }
            val versionMadeBy = readLittleEndianU16(input)
            val versionNeeded = readLittleEndianU16(input)
            val flags = readLittleEndianU16(input)
            val method = readLittleEndianU16(input)
            val dosTime = readLittleEndianU16(input)
            val dosDate = readLittleEndianU16(input)
            val crc = readLittleEndianU32(input)
            val compressedSize = readLittleEndianU32(input)
            val uncompressedSize = readLittleEndianU32(input)
            val nameLength = readLittleEndianU16(input)
            val extraLength = readLittleEndianU16(input)
            val commentLength = readLittleEndianU16(input)
            val diskStart = readLittleEndianU16(input)
            val internalAttributes = readLittleEndianU16(input)
            val externalAttributes = readLittleEndianU32(input)
            val localOffset = readLittleEndianU32(input)
            val nameBytes = ByteArray(nameLength).also(input::readFully)
            val centralResume = input.filePointer

            validateCanonicalZipHeader(
                versionMadeBy = versionMadeBy,
                versionNeeded = versionNeeded,
                flags = flags,
                method = method,
                dosTime = dosTime,
                dosDate = dosDate,
                extraLength = extraLength,
                commentLength = commentLength,
            )
            if (diskStart != 0 ||
                internalAttributes != 0 ||
                externalAttributes != 0L ||
                localOffset != expectedLocalOffset ||
                method != expected.method ||
                crc != expected.crc ||
                compressedSize != expected.compressedSize ||
                uncompressedSize != expected.uncompressedSize ||
                !nameBytes.contentEquals(expected.name.toByteArray(StandardCharsets.US_ASCII))
            ) {
                throw RecoveryArchiveException("Recovery ZIP central ledger differs from its entries.")
            }

            expectedLocalOffset = validateLocalZipRecord(
                input = input,
                localOffset = localOffset,
                expected = expected,
                expectedVersionNeeded = versionNeeded,
                expectedFlags = flags,
            )
            input.seek(centralResume)
        }
        if (input.filePointer != tail.centralOffset + tail.centralSize ||
            input.filePointer != tail.eocdOffset ||
            expectedLocalOffset != tail.centralOffset
        ) {
            throw RecoveryArchiveException("Recovery ZIP contains unledgered bytes.")
        }
    }
}

private fun validateCanonicalZipHeader(
    versionMadeBy: Int,
    versionNeeded: Int,
    flags: Int,
    method: Int,
    dosTime: Int,
    dosDate: Int,
    extraLength: Int,
    commentLength: Int,
) {
    val expectedVersion = when (method) {
        ZipEntry.STORED -> 10
        ZipEntry.DEFLATED -> 20
        else -> throw RecoveryArchiveException("Recovery ZIP compression method is unsupported.")
    }
    val expectedFlags = ZIP_FLAG_UTF8 or
        if (method == ZipEntry.DEFLATED) ZIP_FLAG_DATA_DESCRIPTOR else 0
    if (versionMadeBy != expectedVersion ||
        versionNeeded != expectedVersion ||
        flags != expectedFlags ||
        dosTime != ZIP_DOS_TIME ||
        dosDate != ZIP_DOS_DATE ||
        extraLength != 0 ||
        commentLength != 0
    ) {
        throw RecoveryArchiveException("Recovery ZIP header metadata is not canonical.")
    }
}

private fun validateLocalZipRecord(
    input: RandomAccessFile,
    localOffset: Long,
    expected: SequentialZipEntry,
    expectedVersionNeeded: Int,
    expectedFlags: Int,
): Long {
    input.seek(localOffset)
    if (readLittleEndianU32(input) != ZIP_LOCAL_SIGNATURE.toLong()) {
        throw RecoveryArchiveException("Recovery ZIP local header is malformed.")
    }
    val versionNeeded = readLittleEndianU16(input)
    val flags = readLittleEndianU16(input)
    val method = readLittleEndianU16(input)
    val dosTime = readLittleEndianU16(input)
    val dosDate = readLittleEndianU16(input)
    val localCrc = readLittleEndianU32(input)
    val localCompressedSize = readLittleEndianU32(input)
    val localUncompressedSize = readLittleEndianU32(input)
    val nameLength = readLittleEndianU16(input)
    val extraLength = readLittleEndianU16(input)
    val nameBytes = ByteArray(nameLength).also(input::readFully)
    if (versionNeeded != expectedVersionNeeded ||
        flags != expectedFlags ||
        method != expected.method ||
        dosTime != ZIP_DOS_TIME ||
        dosDate != ZIP_DOS_DATE ||
        extraLength != 0 ||
        !nameBytes.contentEquals(expected.name.toByteArray(StandardCharsets.US_ASCII))
    ) {
        throw RecoveryArchiveException("Recovery ZIP local header differs from its central ledger.")
    }
    val usesDescriptor = flags and ZIP_FLAG_DATA_DESCRIPTOR != 0
    if (usesDescriptor) {
        if (localCrc != 0L || localCompressedSize != 0L || localUncompressedSize != 0L) {
            throw RecoveryArchiveException("Recovery ZIP descriptor header is not canonical.")
        }
    } else if (localCrc != expected.crc ||
        localCompressedSize != expected.compressedSize ||
        localUncompressedSize != expected.uncompressedSize
    ) {
        throw RecoveryArchiveException("Recovery ZIP local sizes differ from its central ledger.")
    }
    val payloadEnd = Math.addExact(input.filePointer, expected.compressedSize)
    input.seek(payloadEnd)
    if (!usesDescriptor) return payloadEnd
    if (readLittleEndianU32(input) != ZIP_DATA_DESCRIPTOR_SIGNATURE.toLong() ||
        readLittleEndianU32(input) != expected.crc ||
        readLittleEndianU32(input) != expected.compressedSize ||
        readLittleEndianU32(input) != expected.uncompressedSize
    ) {
        throw RecoveryArchiveException("Recovery ZIP data descriptor is malformed.")
    }
    return input.filePointer
}

private fun readLittleEndianU16(input: RandomAccessFile): Int {
    val first = input.read()
    val second = input.read()
    if (first < 0 || second < 0) {
        throw RecoveryArchiveException("Recovery ZIP metadata is truncated.")
    }
    return first or (second shl 8)
}

private fun readLittleEndianU32(input: RandomAccessFile): Long {
    val first = readLittleEndianU16(input)
    val second = readLittleEndianU16(input)
    return first.toLong() or (second.toLong() shl 16)
}

private fun strictUtf8(bytes: ByteArray, length: Int): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString()
    } catch (error: Exception) {
        throw RecoveryArchiveException("Recovery JSONL contains invalid UTF-8.", error)
    }
}

private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
    ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
    ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

private fun littleEndianUnsignedInt(bytes: ByteArray, offset: Int): Long =
    littleEndianInt(bytes, offset).toLong() and 0xffff_ffffL

private fun checkContinue(shouldContinue: () -> Boolean) {
    if (!shouldContinue()) throw RecoveryArchiveCancelledException()
}

private fun syncDirectory(directory: File) {
    FileChannel.open(directory.toPath(), READ).use { channel -> channel.force(true) }
}
