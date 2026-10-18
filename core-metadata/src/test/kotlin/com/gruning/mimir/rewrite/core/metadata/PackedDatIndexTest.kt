package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.Deflater

class PackedDatIndexTest {
    private data class SourceRow(
        val sha1: String,
        val title: String? = null,
        val collapse: String? = null,
        val ambiguous: Boolean = false,
    )

    private data class Fixture(
        val bytes: ByteArray,
        val expected: PackedDatExpectation,
    )

    private val rows = listOf(
        SourceRow("00".repeat(20), "Alpha (USA)", "Alpha (USA)"),
        SourceRow("20".repeat(20), ambiguous = true),
        SourceRow("40".repeat(20), "Beta (USA)", "Beta"),
        SourceRow("80".repeat(20), "Middle 🌍", "Middle 🌍"),
        SourceRow("c0".repeat(20), "Beta (Japan)", "Beta"),
        SourceRow("ff".repeat(20), "Omega", "Omega"),
    )

    @Test
    fun `binary search resolves first middle last and accepts uppercase without moving provider buffer`() {
        val fixture = fixture(rows)
        val padded = ByteBuffer.allocate(fixture.bytes.size + 7).apply {
            position(3)
            put(fixture.bytes)
            flip()
            position(3)
            limit(3 + fixture.bytes.size)
        }
        val originalPosition = padded.position()
        val index = assertNotNullIndex(PackedDatIndex.open(padded, fixture.expected))

        assertEquals(originalPosition, padded.position())
        assertEquals(8, index.entryCount) // parsed rows, deliberately not the six unique SHA keys
        assertEquals("Alpha (USA)", index.bySha1(rows.first().sha1)?.canonicalTitle)
        assertEquals("Middle 🌍", index.bySha1(rows[3].sha1.uppercase())?.canonicalTitle)
        assertEquals("Omega", index.bySha1(rows.last().sha1)?.canonicalTitle)
        assertNull(index.bySha1("7f".repeat(20)))
        assertNull(index.bySha1("not-a-sha1"))
        assertNull(index.match(ContentHash(crc32 = "12345678")))
    }

    @Test
    fun `ambiguity fails closed while exact title and shared collapse payload survive`() {
        val fixture = fixture(rows)
        val index = assertNotNullIndex(PackedDatIndex.open(ByteBuffer.wrap(fixture.bytes), fixture.expected))

        assertNull(index.bySha1(rows[1].sha1))
        val betaUsa = index.bySha1(rows[2].sha1)
        val betaJapan = index.bySha1(rows[4].sha1)
        assertEquals("Beta (USA)", betaUsa?.canonicalTitle)
        assertEquals("Beta (Japan)", betaJapan?.canonicalTitle)
        assertEquals("Beta", betaUsa?.collapseKey)
        assertEquals(betaUsa?.collapseKey, betaJapan?.collapseKey)
    }

    @Test
    fun `source metadata counts and digest locks all fail closed on mismatch`() {
        val fixture = fixture(rows)
        val bytes = ByteBuffer.wrap(fixture.bytes)
        val expected = fixture.expected
        val mismatches = listOf(
            expected.copy(sourceId = "wrong.datgz"),
            expected.copy(platform = "wrong"),
            expected.copy(origin = MetadataOrigin.REDUMP),
            expected.copy(expectedHeaderName = "Headered trap"),
            expected.copy(expectedEntryCount = expected.expectedEntryCount + 1),
            expected.copy(expectedUniqueSha1Count = expected.expectedUniqueSha1Count + 1),
            expected.copy(expectedAmbiguousSha1Count = 0),
            expected.copy(expectedPackedSizeBytes = expected.expectedPackedSizeBytes + 1),
            expected.copy(inputContentSha256 = "ab".repeat(32)),
            expected.copy(semanticMapSha256 = "cd".repeat(32)),
        )

        mismatches.forEach { assertNull(PackedDatIndex.open(bytes, it)) }
    }

    @Test
    fun `corrupt truncated trailing and non-raw DEFLATE shards fail closed`() {
        val fixture = fixture(rows)

        val corruptMagic = fixture.bytes.copyOf().apply { this[0] = 'X'.code.toByte() }
        assertNull(PackedDatIndex.open(ByteBuffer.wrap(corruptMagic), fixture.expected))
        val truncated = fixture.bytes.copyOf(fixture.bytes.size - 1)
        assertNull(
            PackedDatIndex.open(
                ByteBuffer.wrap(truncated),
                fixture.expected.copy(expectedPackedSizeBytes = truncated.size.toLong()),
            ),
        )
        val trailing = fixture.bytes + 0
        assertNull(
            PackedDatIndex.open(
                ByteBuffer.wrap(trailing),
                fixture.expected.copy(expectedPackedSizeBytes = trailing.size.toLong()),
            ),
        )

        val corruptDeflate = fixture.bytes.copyOf()
        val deflateOffset = le(corruptDeflate).getLong(0x60).toInt()
        val compressedSize = le(corruptDeflate).getLong(0x68).toInt()
        repeat(compressedSize) { corruptDeflate[deflateOffset + it] = 0xff.toByte() }
        assertNull(PackedDatIndex.open(ByteBuffer.wrap(corruptDeflate), fixture.expected))

        val zlibWrapped = fixture(rows, rawDeflate = false)
        assertNull(PackedDatIndex.open(ByteBuffer.wrap(zlibWrapped.bytes), zlibWrapped.expected))
    }

    @Test
    fun `unsorted rows nonzero alignment padding and semantic payload drift fail closed`() {
        val fixture = fixture(rows)

        val unsorted = fixture.bytes.copyOf()
        val shaOffset = le(unsorted).getLong(0x48).toInt()
        val first = unsorted.copyOfRange(shaOffset, shaOffset + PackedDatIndex.SHA_RECORD_BYTES)
        val lastOffset = shaOffset + (rows.size - 1) * PackedDatIndex.SHA_RECORD_BYTES
        val last = unsorted.copyOfRange(lastOffset, lastOffset + PackedDatIndex.SHA_RECORD_BYTES)
        first.copyInto(unsorted, lastOffset)
        last.copyInto(unsorted, shaOffset)
        assertNull(PackedDatIndex.open(ByteBuffer.wrap(unsorted), fixture.expected))

        val padding = fixture.bytes.copyOf()
        val metadataEnd = PackedDatIndex.FIXED_HEADER_BYTES +
            le(padding).getInt(0x10) + le(padding).getInt(0x14) +
            le(padding).getInt(0x18) + le(padding).getInt(0x1c)
        val firstShaOffset = le(padding).getLong(0x48).toInt()
        if (metadataEnd < firstShaOffset) {
            padding[metadataEnd] = 1
            assertNull(PackedDatIndex.open(ByteBuffer.wrap(padding), fixture.expected))
        }

        val payloadDrift = fixture.bytes.copyOf()
        val payloadOffset = le(payloadDrift).getLong(0x50).toInt()
        le(payloadDrift).putInt(
            payloadOffset,
            le(payloadDrift).getInt(payloadOffset + PackedDatIndex.PAYLOAD_RECORD_BYTES),
        )
        assertNull(PackedDatIndex.open(ByteBuffer.wrap(payloadDrift), fixture.expected))

        val badSentinel = fixture.bytes.copyOf()
        val ambiguousResultOffset = shaOffset + PackedDatIndex.SHA_RECORD_BYTES + 20
        le(badSentinel).putInt(ambiguousResultOffset, Int.MIN_VALUE or 7)
        assertNull(PackedDatIndex.open(ByteBuffer.wrap(badSentinel), fixture.expected))
    }

    private fun assertNotNullIndex(value: PackedDatIndex?): PackedDatIndex {
        assertNotNull(value)
        return value!!
    }

    private fun fixture(sourceRows: List<SourceRow>, rawDeflate: Boolean = true): Fixture {
        require(sourceRows.zipWithNext().all { (left, right) -> left.sha1 < right.sha1 })
        val resolvedPairs = sourceRows.filterNot { it.ambiguous }.map { row ->
            requireNotNull(row.title) to requireNotNull(row.collapse)
        }
        val stringValues = resolvedPairs.flatMap { listOf(it.first, it.second) }
            .distinct()
            .sortedWith { left, right -> compareUnsignedUtf8(left, right) }
        val strings = stringValues.withIndex().associate { (index, value) -> value to index }
        val payloadValues = resolvedPairs.distinct().sortedWith { left, right ->
            compareUnsignedUtf8(left.first, right.first).takeIf { it != 0 }
                ?: compareUnsignedUtf8(left.second, right.second)
        }
        val payloads = payloadValues.withIndex().associate { (index, value) -> value to index }
        val results = sourceRows.map { row ->
            if (row.ambiguous) {
                Int.MIN_VALUE
            } else {
                requireNotNull(payloads[requireNotNull(row.title) to requireNotNull(row.collapse)])
            }
        }
        val stringBytes = stringValues.map { it.toByteArray(StandardCharsets.UTF_8) }
        val stringOffsets = IntArray(stringBytes.size + 1)
        val rawStrings = ByteArrayOutputStream().apply {
            stringBytes.forEachIndexed { index, value ->
                write(value)
                stringOffsets[index + 1] = size()
            }
        }.toByteArray()
        val compressedStrings = deflate(rawStrings, rawDeflate)

        val sourceId = "fixture.datgz"
        val platform = "fixture"
        val origin = MetadataOrigin.NO_INTRO
        val headerName = "Fixture Parent-Clone"
        val metadataParts = listOf(sourceId, platform, origin.name, headerName)
            .map { it.toByteArray(StandardCharsets.UTF_8) }
        val metadata = metadataParts.fold(ByteArray(0)) { all, part -> all + part }
        val metadataOffset = PackedDatIndex.FIXED_HEADER_BYTES
        val shaOffset = align8(metadataOffset + metadata.size)
        val payloadOffset = align8(shaOffset + sourceRows.size * PackedDatIndex.SHA_RECORD_BYTES)
        val stringOffsetsOffset = align8(payloadOffset + payloads.size * PackedDatIndex.PAYLOAD_RECORD_BYTES)
        val stringDeflateOffset = align8(stringOffsetsOffset + stringOffsets.size * Int.SIZE_BYTES)
        val fileBytes = stringDeflateOffset + compressedStrings.size
        val inputDigest = sha256("fixture source XML".toByteArray())
        val semanticDigest = semanticDigest(sourceRows)

        val bytes = ByteBuffer.allocate(fileBytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("MIMIDX1\u0000".toByteArray(StandardCharsets.US_ASCII))
        bytes.putShort(0x08, PackedDatIndex.VERSION.toShort())
        bytes.putShort(0x0a, PackedDatIndex.FIXED_HEADER_BYTES.toShort())
        bytes.putInt(0x0c, PackedDatIndex.FLAGS)
        metadataParts.forEachIndexed { index, value -> bytes.putInt(0x10 + index * 4, value.size) }
        bytes.putInt(0x20, sourceRows.size + 2) // parsed source rows can exceed unique SHA keys
        bytes.putInt(0x24, sourceRows.size)
        bytes.putInt(0x28, sourceRows.count { it.ambiguous })
        bytes.putInt(0x2c, payloads.size)
        bytes.putInt(0x30, strings.size)
        bytes.putInt(0x34, PackedDatIndex.SHA_RECORD_BYTES)
        bytes.putInt(0x38, PackedDatIndex.PAYLOAD_RECORD_BYTES)
        bytes.putInt(0x3c, 0)
        bytes.putLong(0x40, metadataOffset.toLong())
        bytes.putLong(0x48, shaOffset.toLong())
        bytes.putLong(0x50, payloadOffset.toLong())
        bytes.putLong(0x58, stringOffsetsOffset.toLong())
        bytes.putLong(0x60, stringDeflateOffset.toLong())
        bytes.putLong(0x68, compressedStrings.size.toLong())
        bytes.putLong(0x70, rawStrings.size.toLong())
        bytes.putLong(0x78, fileBytes.toLong())
        bytes.position(0x80)
        bytes.put(inputDigest)
        bytes.position(0xa0)
        bytes.put(semanticDigest)
        bytes.position(metadataOffset)
        bytes.put(metadata)
        sourceRows.forEachIndexed { index, row ->
            bytes.position(shaOffset + index * PackedDatIndex.SHA_RECORD_BYTES)
            bytes.put(hex(row.sha1))
            bytes.putInt(results[index])
        }
        payloadValues.forEachIndexed { id, pair ->
            bytes.putInt(payloadOffset + id * PackedDatIndex.PAYLOAD_RECORD_BYTES, requireNotNull(strings[pair.first]))
            bytes.putInt(
                payloadOffset + id * PackedDatIndex.PAYLOAD_RECORD_BYTES + 4,
                requireNotNull(strings[pair.second]),
            )
        }
        stringOffsets.forEachIndexed { index, offset -> bytes.putInt(stringOffsetsOffset + index * 4, offset) }
        bytes.position(stringDeflateOffset)
        bytes.put(compressedStrings)

        return Fixture(
            bytes.array(),
            PackedDatExpectation(
                sourceId = sourceId,
                platform = platform,
                origin = origin,
                expectedHeaderName = headerName,
                expectedEntryCount = sourceRows.size + 2,
                expectedUniqueSha1Count = sourceRows.size,
                expectedAmbiguousSha1Count = sourceRows.count { it.ambiguous },
                expectedPackedSizeBytes = fileBytes.toLong(),
                inputContentSha256 = inputDigest.toHex(),
                semanticMapSha256 = semanticDigest.toHex(),
            ),
        )
    }

    private fun semanticDigest(rows: List<SourceRow>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        rows.forEach { row ->
            digest.update(hex(row.sha1))
            if (row.ambiguous) {
                digest.update(1)
            } else {
                digest.update(0)
                digest.update(lengthPrefix(requireNotNull(row.title)))
                digest.update(lengthPrefix(requireNotNull(row.collapse)))
            }
        }
        return digest.digest()
    }

    private fun lengthPrefix(value: String): ByteArray {
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(4 + utf8.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(utf8.size)
            .put(utf8)
            .array()
    }

    private fun deflate(raw: ByteArray, nowrap: Boolean): ByteArray {
        val deflater = Deflater(9, nowrap)
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun align8(value: Int): Int = (value + 7) and -8

    private fun compareUnsignedUtf8(left: String, right: String): Int {
        val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
        val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
        for (index in 0 until minOf(leftBytes.size, rightBytes.size)) {
            val difference = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return leftBytes.size - rightBytes.size
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
