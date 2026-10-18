package com.gruning.mimir.rewrite.core.metadata

import java.io.Closeable
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class VerifiedChdIdentity(
    val version: Int,
    val logicalBytes: Long,
    val rawSha1: String,
    val combinedSha1: String,
    val chainDepth: Int = 1,
    val compressionTags: List<Int> = emptyList(),
)

internal sealed interface ChdMetadataRead {
    data class Checksummed(val tag: Int, val bytes: ByteArray) : ChdMetadataRead
    data object NotChecksummed : ChdMetadataRead
    data object End : ChdMetadataRead
    data object IoRetryable : ChdMetadataRead
    data object ResourceRefused : ChdMetadataRead
    data object Unsupported : ChdMetadataRead
    data object Malformed : ChdMetadataRead
}

internal enum class ChdHunkRead {
    OK,
    IO_RETRYABLE,
    RESOURCE_REFUSED,
    UNSUPPORTED,
    MALFORMED,
}

internal interface ChdReadSession : Closeable {
    val version: Int
    val logicalBytes: Long
    val hunkBytes: Int
    val hunkCount: Int
    val rawSha1: String
    val combinedSha1: String

    fun readHunk(index: Int, destination: ByteArray): ChdHunkRead
    fun readMetadata(index: Int, maxBytes: Int): ChdMetadataRead
}

internal fun interface ChdVerifierBackend {
    fun open(path: String): ContainerIdentityResult<ChdReadSession>
}

/** Recomputes a CHD's raw and checksum-bearing metadata SHA-1s before exposing its catalog digest. */
internal object ChdPayloadVerifier {
    const val PARSER_VERSION = 3
    const val POLICY_STAMP = "libchdr-raw-and-metadata-v3"

    private const val MAX_LOGICAL_BYTES = 16L * 1024 * 1024 * 1024
    private const val MAX_HUNK_BYTES = 8 * 1024 * 1024
    private const val MAX_HUNKS = 1_000_000
    private const val MAX_METADATA_ENTRIES = 4_096
    private const val MAX_METADATA_ENTRY_BYTES = 1024 * 1024
    private const val MAX_METADATA_TOTAL_BYTES = 8 * 1024 * 1024

    // A1D device measurement: a real sub-GiB raw-class (LZMA/ZLIB/Huffman/FLAC) CHDv5 exceeds 120 s on
    // RP5-class hardware while its CD-class sibling verifies in ~40 s. 600 s covers the certified
    // sub-GiB fixture set on the slowest certified device with ~3.8x margin; multi-GiB raw-class media
    // may still refuse — honestly and retryably — until the budget is scaled by logical bytes.
    suspend fun verify(
        localPath: String,
        header: ChdHeaderEvidence,
        backend: ChdVerifierBackend = NativeChdVerifierBackend,
        budget: ChdVerificationBudget = ChdVerificationBudget.forResolve(),
    ): ContainerIdentityResult<VerifiedChdIdentity> {
        currentCoroutineContext().ensureActive()
        if (!budget.hasAdmissionFloor()) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
        }
        preflight(header)?.let { return it }
        if (header.parentSha1 != null) {
            return ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.PARENT_REQUIRED)
        }
        val opened = backend.open(localPath)
        if (budget.isExhausted()) {
            if (opened is ContainerIdentityResult.Identified) {
                opened.value.use { }
            }
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
        }
        val session = when (opened) {
            is ContainerIdentityResult.Identified -> opened.value
            is ContainerIdentityResult.PolicyAbstain -> return opened
            is ContainerIdentityResult.NeedsReview -> return opened
            is ContainerIdentityResult.RetryableFailure -> return opened
        }
        return try {
            session.use { opened ->
                if (
                    opened.version != header.version ||
                    opened.logicalBytes != header.logicalBytes ||
                    opened.rawSha1 != header.rawSha1 ||
                    opened.combinedSha1 != header.combinedSha1
                ) {
                    return@use ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                }
                if (
                    opened.logicalBytes !in 1..MAX_LOGICAL_BYTES ||
                    opened.hunkBytes !in 1..MAX_HUNK_BYTES ||
                    opened.hunkCount !in 1..MAX_HUNKS
                ) {
                    return@use ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.RESOURCE_REFUSED,
                    )
                }
                val expectedHunks = (opened.logicalBytes + opened.hunkBytes - 1L) / opened.hunkBytes
                if (expectedHunks != opened.hunkCount.toLong()) {
                    return@use ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                }
                val digest = MessageDigest.getInstance("SHA-1")
                val hunk = ByteArray(opened.hunkBytes)
                var remaining = opened.logicalBytes
                for (index in 0 until opened.hunkCount) {
                    currentCoroutineContext().ensureActive()
                    if (budget.isExhausted()) {
                        return@use ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.RESOURCE_REFUSED,
                        )
                    }
                    val hunkRead = opened.readHunk(index, hunk)
                    if (budget.isExhausted()) {
                        return@use ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.RESOURCE_REFUSED,
                        )
                    }
                    when (hunkRead) {
                        ChdHunkRead.OK -> Unit
                        ChdHunkRead.IO_RETRYABLE ->
                            return@use ContainerIdentityResult.RetryableFailure(
                                ContainerIdentityReason.IO_RETRYABLE,
                            )
                        ChdHunkRead.RESOURCE_REFUSED ->
                            return@use ContainerIdentityResult.RetryableFailure(
                                ContainerIdentityReason.RESOURCE_REFUSED,
                            )
                        ChdHunkRead.UNSUPPORTED ->
                            return@use ContainerIdentityResult.PolicyAbstain(
                                ContainerIdentityReason.UNSUPPORTED_CONTAINER,
                            )
                        ChdHunkRead.MALFORMED ->
                            return@use ContainerIdentityResult.RetryableFailure(
                                ContainerIdentityReason.MALFORMED,
                            )
                    }
                    val bytes = minOf(remaining, opened.hunkBytes.toLong()).toInt()
                    if (bytes <= 0) {
                        return@use ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                    }
                    digest.update(hunk, 0, bytes)
                    remaining -= bytes
                    if (budget.isExhausted()) {
                        return@use ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.RESOURCE_REFUSED,
                        )
                    }
                }
                val recomputedRaw = digest.digest()
                if (remaining != 0L || recomputedRaw.toHex() != opened.rawSha1) {
                    return@use ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                }
                val recomputedOverall = recomputeOverall(
                    session = opened,
                    recomputedRaw = recomputedRaw,
                    budget = budget,
                )
                val overall = when (recomputedOverall) {
                    is ContainerIdentityResult.Identified -> recomputedOverall.value
                    is ContainerIdentityResult.PolicyAbstain -> return@use recomputedOverall
                    is ContainerIdentityResult.NeedsReview -> return@use recomputedOverall
                    is ContainerIdentityResult.RetryableFailure -> return@use recomputedOverall
                }
                if (overall != opened.combinedSha1) {
                    return@use ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                }
                if (budget.isExhausted()) {
                    return@use ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.RESOURCE_REFUSED,
                    )
                }
                ContainerIdentityResult.Identified(
                    VerifiedChdIdentity(
                        version = opened.version,
                        logicalBytes = opened.logicalBytes,
                        rawSha1 = opened.rawSha1,
                        combinedSha1 = overall,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
        }
    }

    private fun preflight(
        header: ChdHeaderEvidence,
    ): ContainerIdentityResult<VerifiedChdIdentity>? {
        if (
            header.logicalBytes !in 1..MAX_LOGICAL_BYTES ||
            header.hunkBytes !in 1..MAX_HUNK_BYTES
        ) {
            return ContainerIdentityResult.RetryableFailure(
                ContainerIdentityReason.RESOURCE_REFUSED,
            )
        }
        val derivedHunkCount = ((header.logicalBytes - 1L) / header.hunkBytes) + 1L
        val declaredHunkCount = header.declaredHunkCount ?: derivedHunkCount
        if (declaredHunkCount !in 1..MAX_HUNKS.toLong()) {
            return ContainerIdentityResult.RetryableFailure(
                ContainerIdentityReason.RESOURCE_REFUSED,
            )
        }
        if (declaredHunkCount != derivedHunkCount) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
        }
        return null
    }

    private suspend fun recomputeOverall(
        session: ChdReadSession,
        recomputedRaw: ByteArray,
        budget: ChdVerificationBudget,
    ): ContainerIdentityResult<String> {
        if (session.version < 4) {
            return ContainerIdentityResult.Identified(recomputedRaw.toHex())
        }
        val records = ArrayList<ByteArray>()
        var metadataBytes = 0L
        var reachedEnd = false
        for (index in 0 until MAX_METADATA_ENTRIES) {
            currentCoroutineContext().ensureActive()
            if (budget.isExhausted()) {
                return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
            }
            val metadata = session.readMetadata(index, MAX_METADATA_ENTRY_BYTES)
            if (budget.isExhausted()) {
                return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
            }
            when (metadata) {
                is ChdMetadataRead.Checksummed -> {
                    if (metadata.bytes.size > MAX_METADATA_ENTRY_BYTES) {
                        return ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.RESOURCE_REFUSED,
                        )
                    }
                    metadataBytes += metadata.bytes.size.toLong()
                    if (metadataBytes > MAX_METADATA_TOTAL_BYTES.toLong()) {
                        return ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.RESOURCE_REFUSED,
                        )
                    }
                    val record = ByteArray(4 + SHA1_BYTES)
                    record.putBigEndian32(0, metadata.tag)
                    MessageDigest.getInstance("SHA-1")
                        .digest(metadata.bytes)
                        .copyInto(record, destinationOffset = 4)
                    records += record
                }
                ChdMetadataRead.NotChecksummed -> Unit
                ChdMetadataRead.End -> {
                    reachedEnd = true
                    break
                }
                ChdMetadataRead.IoRetryable ->
                    return ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.IO_RETRYABLE,
                    )
                ChdMetadataRead.ResourceRefused ->
                    return ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.RESOURCE_REFUSED,
                    )
                ChdMetadataRead.Unsupported ->
                    return ContainerIdentityResult.PolicyAbstain(
                        ContainerIdentityReason.UNSUPPORTED_CONTAINER,
                    )
                ChdMetadataRead.Malformed ->
                    return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
            }
        }
        if (!reachedEnd) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
        }
        records.sortWith(::compareUnsignedBytes)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(recomputedRaw)
        records.forEach(digest::update)
        return ContainerIdentityResult.Identified(digest.digest().toHex())
    }

    private fun compareUnsignedBytes(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val compared = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return left.size - right.size
    }

    private fun ByteArray.putBigEndian32(at: Int, value: Int) {
        for (index in 0 until 4) {
            this[at + index] = (value ushr ((3 - index) * 8)).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") {
        "%02x".format(Locale.ROOT, it.toInt() and 0xff)
    }

    private const val SHA1_BYTES = 20
}

private object NativeChdVerifierBackend : ChdVerifierBackend {
    override fun open(path: String): ContainerIdentityResult<ChdReadSession> {
        if (!ChdNativeBridge.isAvailable() || path.isBlank()) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.SEEK_UNAVAILABLE)
        }
        val opened = ChdNativeBridge.open(path)
        if (opened.handle == 0L) {
            return when (opened.status) {
                ChdNativeOpenStatus.PARENT_REQUIRED ->
                    ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.PARENT_REQUIRED)
                ChdNativeOpenStatus.RESOURCE_REFUSED ->
                    ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
                ChdNativeOpenStatus.IO_RETRYABLE ->
                    ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
                ChdNativeOpenStatus.UNSUPPORTED ->
                    ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER)
                ChdNativeOpenStatus.MALFORMED,
                ChdNativeOpenStatus.OK,
                -> ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
            }
        }
        return runCatching<ChdReadSession> { NativeChdReadSession(opened.handle) }.fold(
            onSuccess = { ContainerIdentityResult.Identified(it) },
            onFailure = {
                ChdNativeBridge.close(opened.handle)
                ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
            },
        )
    }
}

private enum class ChdNativeOpenStatus(val wire: Long) {
    OK(0),
    IO_RETRYABLE(1),
    PARENT_REQUIRED(2),
    UNSUPPORTED(3),
    RESOURCE_REFUSED(4),
    MALFORMED(5),
    ;

    companion object {
        fun fromWire(value: Long): ChdNativeOpenStatus =
            entries.firstOrNull { it.wire == value } ?: MALFORMED
    }
}

private data class ChdNativeOpenResult(
    val status: ChdNativeOpenStatus,
    val handle: Long,
)

private fun ByteArray.bigU32Long(at: Int): Long {
    require(at >= 0 && at + 4 <= size)
    return ((this[at].toLong() and 0xff) shl 24) or
        ((this[at + 1].toLong() and 0xff) shl 16) or
        ((this[at + 2].toLong() and 0xff) shl 8) or
        (this[at + 3].toLong() and 0xff)
}

private fun ByteArray.metadataRead(): ChdMetadataRead {
    if (isEmpty()) return ChdMetadataRead.Malformed
    return when (this[0].toInt() and 0xff) {
        0 -> ChdMetadataRead.End
        1 -> ChdMetadataRead.NotChecksummed
        2 -> {
            if (size < 9) return ChdMetadataRead.Malformed
            val length = bigU32Long(5).takeIf { it <= Int.MAX_VALUE }?.toInt()
                ?: return ChdMetadataRead.ResourceRefused
            if (size != 9 + length) return ChdMetadataRead.Malformed
            val tag = bigU32Long(1).toInt()
            ChdMetadataRead.Checksummed(tag, copyOfRange(9, size))
        }
        3 -> ChdMetadataRead.ResourceRefused
        5 -> ChdMetadataRead.IoRetryable
        6 -> ChdMetadataRead.Unsupported
        else -> ChdMetadataRead.Malformed
    }
}

private class NativeChdReadSession(
    private var handle: Long,
) : ChdReadSession {
    private val info = ChdNativeBridge.info(handle)
        ?.takeIf { it.size == INFO_BYTES }
        ?: throw IllegalStateException("libchdr returned malformed identity info")

    override val version: Int = info.beU32(0)
    override val logicalBytes: Long = info.beU64(4)
    override val hunkBytes: Int = info.beU32(12)
    override val hunkCount: Int = info.beU32(16)
    override val rawSha1: String = info.copyOfRange(20, 40).toHex()
    override val combinedSha1: String = info.copyOfRange(40, 60).toHex()

    override fun readHunk(index: Int, destination: ByteArray): ChdHunkRead =
        if (handle == 0L) {
            ChdHunkRead.MALFORMED
        } else {
            ChdNativeBridge.readHunk(handle, index, destination)
        }

    override fun readMetadata(index: Int, maxBytes: Int): ChdMetadataRead =
        if (handle == 0L) {
            ChdMetadataRead.Malformed
        } else {
            ChdNativeBridge.metadata(handle, index, maxBytes)?.metadataRead()
                ?: ChdMetadataRead.Malformed
        }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) ChdNativeBridge.close(current)
    }

    private fun ByteArray.beU32(at: Int): Int {
        check(at >= 0 && at + 4 <= size)
        return ((this[at].toInt() and 0xff) shl 24) or
            ((this[at + 1].toInt() and 0xff) shl 16) or
            ((this[at + 2].toInt() and 0xff) shl 8) or
            (this[at + 3].toInt() and 0xff)
    }

    private fun ByteArray.beU64(at: Int): Long {
        check(at >= 0 && at + 8 <= size && (this[at].toInt() and 0x80) == 0)
        var value = 0L
        for (index in at until at + 8) value = (value shl 8) or (this[index].toLong() and 0xff)
        return value
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") {
        "%02x".format(Locale.ROOT, it.toInt() and 0xff)
    }

    private companion object {
        const val INFO_BYTES = 60
    }
}

private object ChdNativeBridge {
    private val loaded = runCatching {
        System.loadLibrary("mimir_ra_hash")
        true
    }.getOrDefault(false)

    fun isAvailable(): Boolean = loaded

    fun open(path: String): ChdNativeOpenResult {
        if (!loaded) return ChdNativeOpenResult(ChdNativeOpenStatus.UNSUPPORTED, 0L)
        val result = runCatching { nativeOpen(path) }.getOrNull()
            ?: return ChdNativeOpenResult(ChdNativeOpenStatus.MALFORMED, 0L)
        if (result.size != 2) return ChdNativeOpenResult(ChdNativeOpenStatus.MALFORMED, 0L)
        return ChdNativeOpenResult(
            status = ChdNativeOpenStatus.fromWire(result[0]),
            handle = result[1],
        )
    }

    fun info(handle: Long): ByteArray? =
        if (loaded && handle != 0L) runCatching { nativeInfo(handle) }.getOrNull() else null

    fun readHunk(handle: Long, index: Int, destination: ByteArray): ChdHunkRead {
        if (!loaded || handle == 0L) return ChdHunkRead.UNSUPPORTED
        val wire = runCatching { nativeReadHunk(handle, index, destination) }
            .getOrElse { return ChdHunkRead.IO_RETRYABLE }
        return ChdHunkRead.entries.getOrNull(wire) ?: ChdHunkRead.MALFORMED
    }

    fun metadata(handle: Long, index: Int, maxBytes: Int): ByteArray? =
        if (loaded && handle != 0L) {
            runCatching { nativeMetadata(handle, index, maxBytes) }.getOrNull()
        } else {
            null
        }

    fun close(handle: Long) {
        if (loaded && handle != 0L) runCatching { nativeClose(handle) }
    }

    private external fun nativeOpen(path: String): LongArray
    private external fun nativeInfo(handle: Long): ByteArray?
    private external fun nativeReadHunk(handle: Long, index: Int, destination: ByteArray): Int
    private external fun nativeMetadata(handle: Long, index: Int, maxBytes: Int): ByteArray?
    private external fun nativeClose(handle: Long)
}
