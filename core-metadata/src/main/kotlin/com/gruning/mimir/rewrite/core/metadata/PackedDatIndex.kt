package com.gruning.mimir.rewrite.core.metadata

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Supplies one immutable, memory-mappable packed DAT shard.
 *
 * The returned buffer's current position and limit delimit the complete shard. The reader duplicates it,
 * so neither lookup nor validation changes the provider's buffer state. Android can implement this seam
 * with an APK asset [java.nio.MappedByteBuffer]; JVM tests can use [ByteBuffer.wrap].
 */
fun interface PackedDatAssetProvider {
    fun openPacked(assetName: String): ByteBuffer?
}

/** Manifest-side locks that a packed shard must reproduce exactly before it can answer a lookup. */
data class PackedDatExpectation(
    /** Stable logical source id (for example `snes.datgz`), independent of the physical `.midx` name. */
    val sourceId: String,
    val platform: String,
    val origin: MetadataOrigin,
    val expectedHeaderName: String,
    /** Parsed source-row count, not the number of unique SHA-1 keys. */
    val expectedEntryCount: Int,
    /** Physical/layout locks. These do not participate in the semantic catalogue stamp. */
    val expectedUniqueSha1Count: Int,
    val expectedAmbiguousSha1Count: Int,
    val expectedPackedSizeBytes: Long,
    /** SHA-256 of the decompressed source XML. */
    val inputContentSha256: String,
    /** SHA-256 of the canonical hash-to-title/collapse map described by [PackedDatIndex]. */
    val semanticMapSha256: String,
)

/** The exact identity payload retained by the packed shard. */
data class PackedDatMatch(
    val canonicalTitle: String,
    val collapseKey: String,
)

/**
 * Strict reader for the MIMIDX v1 packed DAT format.
 *
 * A shard has a fixed 192-byte little-endian header, sorted 24-byte SHA-1 rows, 8-byte identity payloads,
 * a `(stringCount + 1)` table of 32-bit offsets, and one raw-DEFLATE UTF-8 string pool. The high bit of a
 * SHA row's result word is an ambiguity sentinel; such a row always fails closed. The remaining 31 bits
 * address a payload row.
 *
 * [open] validates the complete shape, all stamped source metadata, both digest locks, strict UTF-8,
 * sorted/unique SHA-1 rows, every reference, the exact ambiguity count, and the DEFLATE stream. Any drift
 * returns null, leaving no XML/CRC/guess fallback inside this reader.
 *
 * The semantic map digest is SHA-256 over sorted SHA rows. Each row contributes its raw 20-byte SHA-1 and
 * then either byte `1` for ambiguity, or byte `0`, little-endian u32 title byte length + title UTF-8, then
 * little-endian u32 collapse-key byte length + collapse-key UTF-8. This binds lookup-visible semantics
 * independently of payload/string deduplication choices made by the emitter.
 */
class PackedDatIndex private constructor(
    private val bytes: ByteBuffer,
    private val shaOffset: Int,
    private val uniqueSha1Count: Int,
    private val payloadOffset: Int,
    private val stringOffsets: IntArray,
    private val stringPool: ByteArray,
    /** Payload rows, i.e. distinct (title, collapse key) identities. Drives the lazy name index. */
    private val payloadCount: Int,
    /** Parsed source rows. Deliberately not the unique-key count. */
    val entryCount: Int,
) {
    /** Resolves SHA-1 only. A malformed query, miss, or ambiguity sentinel returns null. */
    fun bySha1(sha1: String): PackedDatMatch? {
        val key = decodeSha1(sha1) ?: return null
        var low = 0
        var high = uniqueSha1Count - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val rowOffset = shaOffset + middle * SHA_RECORD_BYTES
            when (compareSha1(bytes, rowOffset, key)) {
                in Int.MIN_VALUE until 0 -> low = middle + 1
                in 1..Int.MAX_VALUE -> high = middle - 1
                else -> {
                    val result = bytes.getInt(rowOffset + SHA1_BYTES)
                    if (result and AMBIGUITY_MASK != 0) return null
                    return payload(result and PAYLOAD_INDEX_MASK)
                }
            }
        }
        return null
    }

    /** Packed DATs intentionally have no CRC path: the final source corpus has SHA-1 for every row. */
    fun match(hash: ContentHash): PackedDatMatch? = hash.sha1?.let(::bySha1)

    /**
     * Resolves a ROM's own file name (extension already removed) against this shard's dump names, WITHOUT
     * reading a byte of the ROM. Returns null on a miss and — deliberately — on any ambiguity.
     *
     * The index is built on first use from the payload rows [open] has already validated, so the invariant
     * that only a fully verified shard may answer a lookup holds here exactly as it does for [bySha1]. It
     * holds one interned stem key per distinct title and an index array per key; candidate titles are
     * decoded from the pool only when a key actually matches.
     *
     * Narrowing, in order, all of it fail-closed:
     *  1. the normalized stem AND the disc number must match exactly ([DatNameKey]);
     *  2. an un-qualified file never matches a beta/demo/prototype dump — if those are the only candidates
     *     the lookup FAILS rather than resolving to one, because a plainly-named file being a prototype is
     *     far less likely than the catalogue simply not carrying that release;
     *  3. when several remain and the file names regions, those sharing a region win;
     *  4. when several STILL remain, the one dump whose tags are exactly the file's own wins
     *     ([DatNameKey.tagSignature]; version 2, `Jurassic Park (USA)` is the `(USA)`
     *     dump, not its `(Rev 1)`);
     *  5. anything other than exactly one survivor returns null, and the caller hashes.
     */
    fun byName(fileBaseName: String, discFallback: Int? = null): PackedDatMatch? {
        val key = DatNameKey.parse(fileBaseName)
        if (key.isBlank) return null
        // A name that carries no disc number of its own may be told which one to stand for. The ONLY
        // caller that does is a multi-disc group's primary -- typically an `.m3u` playlist, whose name is
        // the set rather than any disc in it. For that row "no disc" is not a missing fact to fail closed
        // on; it is what a playlist name correctly looks like. Everything else still passes null and keeps
        // the strict rule that a disc number one side carries and the other does not never matches.
        val disc = key.disc ?: discFallback
        val rows = nameIndex[coordinate(key.stem, disc)] ?: return null

        var candidates = rows.map { it to DatNameKey.parse(payload(it).canonicalTitle) }
        if (!key.qualified) {
            candidates = candidates.filterNot { it.second.qualified }
            if (candidates.isEmpty()) return null
        }
        if (candidates.size > 1 && key.regions.isNotEmpty()) {
            val shared = candidates.filter { it.second.regions.any(key.regions::contains) }
            if (shared.isNotEmpty()) candidates = shared
        }
        candidates.singleOrNull()?.let { return payload(it.first) }
        return candidates.filter { it.second.tagSignature == key.tagSignature }.singleOrNull()?.let { payload(it.first) }
    }

    /** Every dump name in this shard, for the self-resolution property test. */
    internal fun dumpNames(): Sequence<String> = (0 until payloadCount).asSequence().map { payload(it).canonicalTitle }

    /**
     * Stem+disc buckets over every payload row. Built once, lazily: a shard nobody looks up by name never
     * pays for it, and the map is dropped with the shard.
     */
    private val nameIndex: Map<String, IntArray> by lazy {
        val buckets = HashMap<String, MutableList<Int>>(payloadCount)
        for (index in 0 until payloadCount) {
            val key = DatNameKey.parse(payload(index).canonicalTitle)
            if (key.isBlank) continue
            buckets.getOrPut(coordinate(key.stem, key.disc)) { mutableListOf() } += index
        }
        buckets.mapValues { (_, rows) -> rows.toIntArray() }
    }

    // normalizeKey emits only lowercase alphanumerics and spaces, so "|" cannot occur in a stem.
    private fun coordinate(stem: String, disc: Int?): String = "$stem|${disc ?: -1}"

    private fun payload(index: Int): PackedDatMatch {
        val offset = payloadOffset + index * PAYLOAD_RECORD_BYTES
        val titleId = bytes.getInt(offset)
        val collapseId = bytes.getInt(offset + Int.SIZE_BYTES)
        return PackedDatMatch(string(titleId), string(collapseId))
    }

    private fun string(id: Int): String {
        val start = stringOffsets[id]
        val end = stringOffsets[id + 1]
        return String(stringPool, start, end - start, StandardCharsets.UTF_8)
    }

    companion object {
        const val FIXED_HEADER_BYTES = 192
        const val VERSION = 1
        const val FLAG_SHA1_ONLY = 1
        const val FLAG_RAW_DEFLATE_UTF8 = 1 shl 1
        const val FLAGS = FLAG_SHA1_ONLY or FLAG_RAW_DEFLATE_UTF8
        const val SHA_RECORD_BYTES = 24
        const val PAYLOAD_RECORD_BYTES = 8

        private const val SHA1_BYTES = 20
        private const val AMBIGUITY_MASK = Int.MIN_VALUE
        private const val PAYLOAD_INDEX_MASK = Int.MAX_VALUE
        private const val MAX_RAW_STRING_BYTES = 64 * 1024 * 1024
        private val MAGIC = byteArrayOf(
            'M'.code.toByte(),
            'I'.code.toByte(),
            'M'.code.toByte(),
            'I'.code.toByte(),
            'D'.code.toByte(),
            'X'.code.toByte(),
            '1'.code.toByte(),
            0,
        )

        /**
         * Opens and fully validates a shard. Null is the only failure surface, including malformed bytes,
         * a stale manifest lock, invalid UTF-8, and corrupt/trailing/truncated raw DEFLATE data.
         */
        fun open(buffer: ByteBuffer, expected: PackedDatExpectation): PackedDatIndex? =
            try {
                parse(buffer, expected)
            } catch (_: PackedDatFormatException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun parse(buffer: ByteBuffer, expected: PackedDatExpectation): PackedDatIndex {
            requireExpectation(expected)
            val bytes = buffer.duplicate().slice().order(ByteOrder.LITTLE_ENDIAN)
            format(bytes.remaining().toLong() == expected.expectedPackedSizeBytes)
            format(bytes.remaining() >= FIXED_HEADER_BYTES)
            for (i in MAGIC.indices) format(bytes.get(i) == MAGIC[i])
            format(bytes.u16(0x08) == VERSION)
            format(bytes.u16(0x0a) == FIXED_HEADER_BYTES)
            format(bytes.u32(0x0c) == FLAGS.toLong())

            val sourceIdBytes = bytes.count(0x10)
            val platformBytes = bytes.count(0x14)
            val originBytes = bytes.count(0x18)
            val headerNameBytes = bytes.count(0x1c)
            val parsedEntryCount = bytes.count(0x20)
            val uniqueSha1Count = bytes.count(0x24)
            val ambiguousSha1Count = bytes.count(0x28)
            val payloadCount = bytes.count(0x2c)
            val stringCount = bytes.count(0x30)
            format(bytes.u32(0x34) == SHA_RECORD_BYTES.toLong())
            format(bytes.u32(0x38) == PAYLOAD_RECORD_BYTES.toLong())
            format(bytes.u32(0x3c) == 0L)

            val metadataOffset = bytes.offset(0x40)
            val shaOffset = bytes.offset(0x48)
            val payloadOffset = bytes.offset(0x50)
            val stringOffsetsOffset = bytes.offset(0x58)
            val stringDeflateOffset = bytes.offset(0x60)
            val compressedStringBytes = bytes.count64(0x68)
            val rawStringBytes = bytes.count64(0x70)
            val fileBytes = bytes.count64(0x78)

            format(uniqueSha1Count == expected.expectedUniqueSha1Count)
            format(ambiguousSha1Count == expected.expectedAmbiguousSha1Count)
            format(fileBytes.toLong() == expected.expectedPackedSizeBytes)
            format(rawStringBytes <= MAX_RAW_STRING_BYTES)

            val metadataBytes = checkedAdd(sourceIdBytes, platformBytes, originBytes, headerNameBytes)
            val metadataEnd = checkedAdd(metadataOffset, metadataBytes)
            val shaEnd = checkedAdd(shaOffset, checkedMultiply(uniqueSha1Count, SHA_RECORD_BYTES))
            val payloadEnd = checkedAdd(payloadOffset, checkedMultiply(payloadCount, PAYLOAD_RECORD_BYTES))
            val stringOffsetsEnd = checkedAdd(
                stringOffsetsOffset,
                checkedMultiply(checkedAdd(stringCount, 1), Int.SIZE_BYTES),
            )
            format(metadataOffset == FIXED_HEADER_BYTES)
            format(shaOffset == align8(metadataEnd))
            format(payloadOffset == align8(shaEnd))
            format(stringOffsetsOffset == align8(payloadEnd))
            format(stringDeflateOffset == align8(stringOffsetsEnd))
            format(metadataOffset % 8 == 0)
            format(shaOffset % 8 == 0)
            format(payloadOffset % 8 == 0)
            format(stringOffsetsOffset % 8 == 0)
            format(stringDeflateOffset % 8 == 0)
            validateZeroPadding(bytes, metadataEnd, shaOffset)
            validateZeroPadding(bytes, shaEnd, payloadOffset)
            validateZeroPadding(bytes, payloadEnd, stringOffsetsOffset)
            validateZeroPadding(bytes, stringOffsetsEnd, stringDeflateOffset)
            format(fileBytes == checkedAdd(stringDeflateOffset, compressedStringBytes))
            format(fileBytes == bytes.remaining())
            format(parsedEntryCount >= uniqueSha1Count)
            format(ambiguousSha1Count <= uniqueSha1Count)
            format(payloadCount <= uniqueSha1Count - ambiguousSha1Count)
            format((uniqueSha1Count - ambiguousSha1Count == 0) == (payloadCount == 0))
            format((payloadCount == 0) == (stringCount == 0))

            val metadata = bytes.copy(metadataOffset, metadataBytes)
            var metadataCursor = 0
            fun nextMetadata(length: Int): String {
                val value = strictUtf8(metadata, metadataCursor, length)
                metadataCursor += length
                return value
            }
            val sourceId = nextMetadata(sourceIdBytes)
            val platform = nextMetadata(platformBytes)
            val origin = nextMetadata(originBytes)
            val headerName = nextMetadata(headerNameBytes)
            format(metadataCursor == metadata.size)
            format(sourceId == expected.sourceId)
            format(platform == expected.platform)
            format(origin == expected.origin.name)
            format(headerName == expected.expectedHeaderName)
            format(parsedEntryCount == expected.expectedEntryCount)
            format(sourceId.isNotEmpty() && platform.isNotEmpty() && origin.isNotEmpty() && headerName.isNotEmpty())

            val inputDigest = bytes.copy(0x80, 32)
            val semanticDigest = bytes.copy(0xa0, 32)
            format(MessageDigest.isEqual(inputDigest, decodeSha256(expected.inputContentSha256)))
            format(MessageDigest.isEqual(semanticDigest, decodeSha256(expected.semanticMapSha256)))

            val stringPool = inflateRaw(
                bytes.copy(stringDeflateOffset, compressedStringBytes),
                rawStringBytes,
            )
            val stringOffsets = IntArray(checkedAdd(stringCount, 1)) { index ->
                bytes.getInt(stringOffsetsOffset + index * Int.SIZE_BYTES).also { format(it >= 0) }
            }
            format(stringOffsets.first() == 0)
            format(stringOffsets.last() == stringPool.size)
            for (index in 0 until stringCount) {
                val start = stringOffsets[index]
                val end = stringOffsets[index + 1]
                format(end > start)
                strictUtf8(stringPool, start, end - start)
                if (index > 0) format(compareStrings(stringPool, stringOffsets, index - 1, index) < 0)
            }

            val referencedPayloads = BooleanArray(payloadCount)
            var observedAmbiguities = 0
            for (index in 0 until uniqueSha1Count) {
                val offset = shaOffset + index * SHA_RECORD_BYTES
                if (index > 0) format(compareShaRows(bytes, offset - SHA_RECORD_BYTES, offset) < 0)
                val result = bytes.getInt(offset + SHA1_BYTES)
                if (result and AMBIGUITY_MASK != 0) {
                    format(result and PAYLOAD_INDEX_MASK == 0)
                    observedAmbiguities++
                } else {
                    format(result < payloadCount)
                    referencedPayloads[result] = true
                }
            }
            format(observedAmbiguities == ambiguousSha1Count)
            format(referencedPayloads.all { it })

            val referencedStrings = BooleanArray(stringCount)
            for (index in 0 until payloadCount) {
                val offset = payloadOffset + index * PAYLOAD_RECORD_BYTES
                val titleId = bytes.getInt(offset)
                val collapseId = bytes.getInt(offset + Int.SIZE_BYTES)
                format(titleId in 0 until stringCount)
                format(collapseId in 0 until stringCount)
                if (index > 0) {
                    val previous = offset - PAYLOAD_RECORD_BYTES
                    val previousTitleId = bytes.getInt(previous)
                    val previousCollapseId = bytes.getInt(previous + Int.SIZE_BYTES)
                    val titleOrder = compareStrings(stringPool, stringOffsets, previousTitleId, titleId)
                    format(
                        titleOrder < 0 ||
                            (titleOrder == 0 &&
                                compareStrings(stringPool, stringOffsets, previousCollapseId, collapseId) < 0),
                    )
                }
                referencedStrings[titleId] = true
                referencedStrings[collapseId] = true
            }
            format(referencedStrings.all { it })
            format(
                MessageDigest.isEqual(
                    semanticDigest,
                    semanticDigest(bytes, shaOffset, uniqueSha1Count, payloadOffset, stringOffsets, stringPool),
                ),
            )

            return PackedDatIndex(
                bytes = bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
                shaOffset = shaOffset,
                uniqueSha1Count = uniqueSha1Count,
                payloadOffset = payloadOffset,
                stringOffsets = stringOffsets,
                stringPool = stringPool,
                payloadCount = payloadCount,
                entryCount = parsedEntryCount,
            )
        }

        private fun semanticDigest(
            bytes: ByteBuffer,
            shaOffset: Int,
            uniqueSha1Count: Int,
            payloadOffset: Int,
            stringOffsets: IntArray,
            stringPool: ByteArray,
        ): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            val length = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            fun addString(id: Int) {
                val start = stringOffsets[id]
                val byteCount = stringOffsets[id + 1] - start
                length.clear()
                length.putInt(byteCount)
                digest.update(length.array())
                digest.update(stringPool, start, byteCount)
            }
            for (index in 0 until uniqueSha1Count) {
                val rowOffset = shaOffset + index * SHA_RECORD_BYTES
                digest.update(bytes.copy(rowOffset, SHA1_BYTES))
                val result = bytes.getInt(rowOffset + SHA1_BYTES)
                if (result and AMBIGUITY_MASK != 0) {
                    digest.update(1)
                } else {
                    digest.update(0)
                    val payload = payloadOffset + result * PAYLOAD_RECORD_BYTES
                    addString(bytes.getInt(payload))
                    addString(bytes.getInt(payload + Int.SIZE_BYTES))
                }
            }
            return digest.digest()
        }

        private fun inflateRaw(compressed: ByteArray, expectedSize: Int): ByteArray {
            val inflater = Inflater(true)
            try {
                inflater.setInput(compressed)
                val output = ByteArrayOutputStream(expectedSize.coerceAtMost(64 * 1024))
                val chunk = ByteArray(8 * 1024)
                var total = 0
                while (!inflater.finished()) {
                    val count = try {
                        inflater.inflate(chunk)
                    } catch (_: DataFormatException) {
                        throw PackedDatFormatException()
                    }
                    if (count == 0) {
                        format(!inflater.needsDictionary() && !inflater.needsInput())
                    } else {
                        total = checkedAdd(total, count)
                        format(total <= expectedSize)
                        output.write(chunk, 0, count)
                    }
                }
                format(total == expectedSize)
                format(inflater.remaining == 0)
                return output.toByteArray()
            } finally {
                inflater.end()
            }
        }

        private fun strictUtf8(bytes: ByteArray, offset: Int, length: Int): String =
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString()
            } catch (_: RuntimeException) {
                throw PackedDatFormatException()
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw PackedDatFormatException()
            }

        private fun requireExpectation(expected: PackedDatExpectation) {
            format(expected.sourceId.isNotEmpty())
            format(expected.platform.isNotEmpty())
            format(expected.expectedHeaderName.isNotEmpty())
            format(expected.expectedEntryCount >= 0)
            format(expected.expectedUniqueSha1Count >= 0)
            format(expected.expectedAmbiguousSha1Count in 0..expected.expectedUniqueSha1Count)
            format(expected.expectedPackedSizeBytes >= FIXED_HEADER_BYTES)
            decodeSha256(expected.inputContentSha256)
            decodeSha256(expected.semanticMapSha256)
        }

        private fun decodeSha1(value: String): ByteArray? = decodeHex(value, SHA1_BYTES)

        private fun decodeSha256(value: String): ByteArray =
            decodeHex(value, 32) ?: throw PackedDatFormatException()

        private fun decodeHex(value: String, byteCount: Int): ByteArray? {
            if (value.length != byteCount * 2) return null
            val out = ByteArray(byteCount)
            for (index in out.indices) {
                val high = value[index * 2].digitToIntOrNull(16) ?: return null
                val low = value[index * 2 + 1].digitToIntOrNull(16) ?: return null
                out[index] = ((high shl 4) or low).toByte()
            }
            return out
        }

        private fun compareShaRows(bytes: ByteBuffer, left: Int, right: Int): Int {
            for (index in 0 until SHA1_BYTES) {
                val difference = (bytes.get(left + index).toInt() and 0xff) -
                    (bytes.get(right + index).toInt() and 0xff)
                if (difference != 0) return difference
            }
            return 0
        }

        private fun compareSha1(bytes: ByteBuffer, row: Int, key: ByteArray): Int {
            for (index in key.indices) {
                val difference = (bytes.get(row + index).toInt() and 0xff) -
                    (key[index].toInt() and 0xff)
                if (difference != 0) return difference
            }
            return 0
        }

        private fun compareStrings(pool: ByteArray, offsets: IntArray, leftId: Int, rightId: Int): Int {
            var left = offsets[leftId]
            var right = offsets[rightId]
            val leftEnd = offsets[leftId + 1]
            val rightEnd = offsets[rightId + 1]
            while (left < leftEnd && right < rightEnd) {
                val difference = (pool[left].toInt() and 0xff) - (pool[right].toInt() and 0xff)
                if (difference != 0) return difference
                left++
                right++
            }
            return (leftEnd - left) - (rightEnd - right)
        }

        private fun ByteBuffer.u16(offset: Int): Int = getShort(offset).toInt() and 0xffff

        private fun ByteBuffer.u32(offset: Int): Long = getInt(offset).toLong() and 0xffff_ffffL

        private fun ByteBuffer.count(offset: Int): Int {
            val value = u32(offset)
            format(value <= Int.MAX_VALUE)
            return value.toInt()
        }

        private fun ByteBuffer.offset(offset: Int): Int = count64(offset)

        private fun ByteBuffer.count64(offset: Int): Int {
            val value = getLong(offset)
            format(value >= 0L && value <= Int.MAX_VALUE)
            return value.toInt()
        }

        private fun ByteBuffer.copy(offset: Int, length: Int): ByteArray {
            format(offset >= 0 && length >= 0 && offset.toLong() + length <= remaining())
            return ByteArray(length).also { target ->
                duplicate().apply {
                    position(offset)
                    get(target)
                }
            }
        }

        private fun checkedAdd(vararg values: Int): Int {
            var result = 0L
            for (value in values) {
                format(value >= 0)
                result += value
                format(result <= Int.MAX_VALUE)
            }
            return result.toInt()
        }

        private fun checkedMultiply(left: Int, right: Int): Int {
            format(left >= 0 && right >= 0)
            val result = left.toLong() * right
            format(result <= Int.MAX_VALUE)
            return result.toInt()
        }

        private fun align8(value: Int): Int = checkedAdd(value, (8 - value % 8) % 8)

        private fun validateZeroPadding(bytes: ByteBuffer, start: Int, end: Int) {
            format(start <= end)
            for (offset in start until end) format(bytes.get(offset).toInt() == 0)
        }

        private fun format(condition: Boolean) {
            if (!condition) throw PackedDatFormatException()
        }
    }
}

private class PackedDatFormatException : RuntimeException()
