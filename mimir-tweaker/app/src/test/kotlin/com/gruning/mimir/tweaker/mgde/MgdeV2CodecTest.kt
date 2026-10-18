package com.gruning.mimir.tweaker.mgde

import com.gruning.mimir.tweaker.HardwareSnapshot
import com.gruning.mimir.tweaker.RestoreGuardianEnvelope
import com.gruning.mimir.tweaker.RestoreGuardianFanDeleteEntry
import com.gruning.mimir.tweaker.RestoreGuardianFanPutEntry
import com.gruning.mimir.tweaker.RestoreGuardianStockState
import com.gruning.mimir.tweaker.RestoreGuardianSysfsEntry
import com.gruning.mimir.tweaker.RestoreGuardianVendorModePutEntry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MgdeV2CodecTest {
    private val signature = der(ByteArray(32).also { it[0] = 1 }, ByteArray(32).also { it[0] = 2 })

    @Test
    fun `canonical sysfs fan put and fan delete envelopes round trip only as full wire`() {
        listOf(
            envelope(
                RestoreGuardianSysfsEntry(
                    0,
                    "cpu:0:scaling_max_freq",
                    "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                    RestoreGuardianStockState.PresentSysfs(1_200_000, "600", 0, 0),
                    listOf(2_000_000),
                ),
            ),
            envelope(
                RestoreGuardianFanPutEntry(
                    0,
                    expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                    rendererAppliedIntermediates = listOf(1),
                ),
            ),
            envelope(RestoreGuardianFanDeleteEntry(0)),
        ).forEach { envelope ->
            val wire = MgdeV2Codec.encode(envelope, signature)
            val decoded = MgdeV2Codec.decode(wire)
            assertNotNull(decoded)
            decoded as MgdeV2Record
            assertArrayEquals(envelope.canonicalBytes(), decoded.envelope)
            assertArrayEquals(signature, decoded.signature)
            assertArrayEquals(envelope.canonicalBytes(), decoded.decodedEnvelope.canonicalBytes())
        }
    }

    @Test
    fun `magic version every truncation trailing and total oversize reject`() {
        val wire = MgdeV2Codec.encode(envelope(), signature)
        for (length in 0 until wire.size) {
            assertNull("truncation=$length", MgdeV2Codec.decode(wire.copyOf(length)))
        }
        assertNull(MgdeV2Codec.decode(wire + 0))
        assertNull(MgdeV2Codec.decode(wire.copyOf().also { writeInt(it, 0, 0x4d474444) }))
        assertNull(MgdeV2Codec.decode(wire.copyOf().also { writeInt(it, 4, 1) }))
        assertNull(MgdeV2Codec.decode(ByteArray(MgdeV2Codec.MAX_RECORD + 1)))
    }

    @Test
    fun `zero negative overflow and max plus one envelope and signature lengths reject`() {
        val wire = MgdeV2Codec.encode(envelope(), signature)
        listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, MgdeV2Codec.MAX_ENVELOPE + 1).forEach {
            assertNull("envelope length=$it", MgdeV2Codec.decode(wire.copyOf().also { bytes -> writeInt(bytes, 8, it) }))
        }
        val signatureLengthAt = 12 + envelope().canonicalBytes().size
        listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, MgdeV2Codec.MAX_SIGNATURE + 1).forEach {
            assertNull("signature length=$it", MgdeV2Codec.decode(wire.copyOf().also { bytes -> writeInt(bytes, signatureLengthAt, it) }))
        }
        assertNull(MgdeV2Codec.decode(rawWire(ByteArray(MgdeV2Codec.MAX_ENVELOPE + 1), signature)))
        assertNull(MgdeV2Codec.decode(rawWire(envelope().canonicalBytes(), ByteArray(MgdeV2Codec.MAX_SIGNATURE + 1))))
    }

    @Test
    fun `invalid utf8 noncanonical envelope short input and envelope trailing reject`() {
        val canonical = envelope().canonicalBytes()
        val helper = "helper.1".toByteArray()
        val at = canonical.indexOfSubsequence(helper)
        assertTrue(at > 0)
        assertNull(
            MgdeV2Codec.decode(
                rawWire(canonical.copyOf().also { it[at] = 0xc0.toByte() }, signature),
            ),
        )
        assertNull(MgdeV2Codec.decode(rawWire(canonical + 0, signature)))
        assertNull(MgdeV2Codec.decode(rawWire(byteArrayOf(1), signature)))
        assertNull(MgdeV2Codec.decode(rawWire(canonical.copyOf().also { writeInt(it, 0, 0) }, signature)))
    }

    @Test
    fun `nested text entry and intermediate hostile lengths and counts reject`() {
        val canonical = envelope().canonicalBytes()
        val offsets = envelopeOffsets(canonical)
        listOf(0, -1, 257).forEach { length ->
            assertNull(
                "helper length=$length",
                MgdeV2Codec.decode(
                    rawWire(canonical.copyOf().also { writeInt(it, offsets.helperLength, length) }, signature),
                ),
            )
        }
        listOf(0, 33, -1, Int.MAX_VALUE).forEach { count ->
            assertNull(
                "entry count=$count",
                MgdeV2Codec.decode(
                    rawWire(canonical.copyOf().also { writeInt(it, offsets.entryCount, count) }, signature),
                ),
            )
        }
        listOf(-1, 0, 33, Int.MAX_VALUE).forEach { count ->
            assertNull(
                "intermediate count=$count",
                MgdeV2Codec.decode(
                    rawWire(canonical.copyOf().also { writeInt(it, offsets.intermediateCount, count) }, signature),
                ),
            )
        }
    }

    @Test
    fun `sysfs path 512 boundary is accepted and 513 is rejected symmetrically`() {
        fun path(size: Int) = "/sys/" + "a".repeat(size - 5)
        val atBound = envelope(
            RestoreGuardianSysfsEntry(
                0,
                "cpu:0:scaling_max_freq",
                path(512),
                RestoreGuardianStockState.PresentSysfs(1, "600", 0, 0),
                listOf(2),
            ),
        )
        assertNotNull(MgdeV2Codec.decode(MgdeV2Codec.encode(atBound, signature)))
        val overBound = atBound.copy(
            restoreEntries = listOf(
                (atBound.restoreEntries.single() as RestoreGuardianSysfsEntry).copy(
                    canonicalSysfsPath = path(513),
                ),
            ),
        )
        assertTrue(runCatching { MgdeV2Codec.encode(overBound, signature) }.isFailure)
    }

    @Test
    fun `decoded record deeply snapshots entries and renderer lists`() {
        val decoded = MgdeV2Codec.decode(
            MgdeV2Codec.encode(
                envelope(
                    RestoreGuardianFanPutEntry(
                        0,
                        expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                        rendererAppliedIntermediates = listOf(1, 2),
                    ),
                ),
                signature,
            ),
        )!!
        val first = decoded.decodedEnvelope
        @Suppress("UNCHECKED_CAST")
        (first.restoreEntries as MutableList).clear()
        val second = decoded.decodedEnvelope
        assertEquals(1, second.restoreEntries.size)
        @Suppress("UNCHECKED_CAST")
        ((second.restoreEntries.single() as RestoreGuardianFanPutEntry).rendererAppliedIntermediates as MutableList).clear()
        assertEquals(
            listOf(1, 2),
            (decoded.decodedEnvelope.restoreEntries.single() as RestoreGuardianFanPutEntry)
                .rendererAppliedIntermediates,
        )
    }

    // ---- the vendor performance-mode entry (vmode-guardian) -------------------------------------------------
    //
    // Built from vendor and fan entries only: a sysfs entry canonicalises its path through the host
    // filesystem, which refuses `/sys/...` on Windows, and these must mean the same thing everywhere.

    private fun vendorEntry(origin: Int = 0, applied: Int = 2, ordinal: Int = 1) =
        RestoreGuardianVendorModePutEntry(applyOrdinal = ordinal, origin = origin, applied = applied)

    @Test
    fun `fan only envelopes decode exactly as they always did`() {
        listOf(
            envelope(
                RestoreGuardianFanPutEntry(
                    0,
                    expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                    rendererAppliedIntermediates = listOf(1),
                ),
            ),
            envelope(RestoreGuardianFanDeleteEntry(0)),
        ).forEach { original ->
            val decoded = MgdeV2Codec.decode(MgdeV2Codec.encode(original, signature))!!
            assertEquals(original, decoded.decodedEnvelope)
            assertArrayEquals(original.canonicalBytes(), decoded.envelope)
            assertEquals(original.digestSha256(), decoded.decodedEnvelope.digestSha256())
        }
    }

    @Test
    fun `a vendor mode envelope round trips only as a full wire and the digest covers both modes`() {
        listOf(vendorEntry(origin = 0, applied = 2), vendorEntry(origin = 1, applied = 1)).forEach { entry ->
            val original = envelope(entry)
            val wire = MgdeV2Codec.encode(original, signature)
            val decoded = MgdeV2Codec.decode(wire)
            assertNotNull(decoded)
            decoded as MgdeV2Record
            assertArrayEquals(original.canonicalBytes(), decoded.envelope)
            assertEquals(original, decoded.decodedEnvelope)
            assertEquals(entry, decoded.decodedEnvelope.restoreEntries.single())
            assertEquals(original.digestSha256(), decoded.decodedEnvelope.digestSha256())
            for (length in 0 until wire.size) {
                assertNull("truncation=$length", MgdeV2Codec.decode(wire.copyOf(length)))
            }
            assertNull(MgdeV2Codec.decode(wire + 0))
        }
        val base = envelope(vendorEntry(origin = 0, applied = 2)).digestSha256()
        listOf(
            vendorEntry(origin = 1, applied = 2),
            vendorEntry(origin = 0, applied = 1),
            vendorEntry(origin = 0, applied = 2, ordinal = 2),
        ).forEach { altered ->
            assertFalse(
                "an altered vendor entry escaped the digest: $altered",
                base == envelope(altered).digestSha256(),
            )
        }
    }

    @Test
    fun `a vendor mode record decoded from the wire is deeply snapshotted`() {
        val decoded = MgdeV2Codec.decode(MgdeV2Codec.encode(envelope(vendorEntry()), signature))!!
        @Suppress("UNCHECKED_CAST")
        (decoded.decodedEnvelope.restoreEntries as MutableList).clear()
        assertEquals(vendorEntry(), decoded.decodedEnvelope.restoreEntries.single())
    }

    /** The header of a valid envelope up to its entry count, then a hand-written entry region. */
    private fun withEntryRegion(region: (DataOutputStream) -> Unit): ByteArray {
        val valid = envelope(vendorEntry()).canonicalBytes()
        val head = valid.copyOf(envelopeOffsets(valid).entryCount)
        return ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { out ->
                out.write(head)
                out.writeInt(1)
                region(out)
            }
        }.toByteArray()
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun vendorRegion(
        tag: String = "vendor_put",
        logical: String = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
        intermediates: List<Int> = listOf(2),
        stockTag: String = "present_vendor",
        origin: Int = 0,
    ): (DataOutputStream) -> Unit = { out ->
        out.text(tag)
        out.writeInt(1)
        out.text(logical)
        out.writeInt(intermediates.size)
        intermediates.forEach(out::writeInt)
        out.text(stockTag)
        out.writeInt(origin)
    }

    @Test
    fun `a hostile or malformed vendor mode entry never decodes`() {
        // The builder itself is sound: the exact canonical entry decodes.
        assertNotNull(MgdeV2Codec.decode(rawWire(withEntryRegion(vendorRegion()), signature)))

        mapOf(
            "stock tag of another axis" to vendorRegion(stockTag = "present_fan"),
            "vendor stock under a fan tag" to vendorRegion(tag = "fan_put"),
            "no applied mode" to vendorRegion(intermediates = emptyList()),
            "two applied modes" to vendorRegion(intermediates = listOf(2, 1)),
            "origin outside the known modes" to vendorRegion(origin = 3),
            "negative origin" to vendorRegion(origin = -1),
            "applied outside the known modes" to vendorRegion(intermediates = listOf(3)),
            "another axis's logical id" to vendorRegion(logical = HardwareSnapshot.FAN_LOGICAL_ID),
            "unknown tag" to vendorRegion(tag = "vendor_delete"),
        ).forEach { (label, region) ->
            assertNull(label, MgdeV2Codec.decode(rawWire(withEntryRegion(region), signature)))
        }
    }

    @Test
    fun `strict P256 DER accepts 1 31 32 byte magnitudes and necessary sign padding`() {
        listOf(1, 31, 32).forEach { size ->
            val positive = ByteArray(size).also { it[0] = 1 }
            assertTrue("positive magnitude=$size", MgdeV2Codec.isCanonicalP256Der(der(positive, positive)))
            val padded = ByteArray(size).also { it[0] = 0x80.toByte() }
            assertTrue("sign padded magnitude=$size", MgdeV2Codec.isCanonicalP256Der(der(padded, padded)))
        }
    }

    @Test
    fun `strict P256 DER rejects negative zero nonminimal redundant 33 byte long form oversize truncation trailing`() {
        val valid = der(byteArrayOf(1), byteArrayOf(2))
        val cases = listOf(
            byteArrayOf(0x30, 6, 2, 1, 0x80.toByte(), 2, 1, 1),
            byteArrayOf(0x30, 6, 2, 1, 0, 2, 1, 1),
            byteArrayOf(0x30, 7, 2, 2, 0, 1, 2, 1, 1),
            byteArrayOf(0x30, 7, 2, 2, 0, 0x7f, 2, 1, 1),
            rawDerInteger(ByteArray(33).also { it[0] = 1 }, byteArrayOf(1)),
            byteArrayOf(0x30, 0x81.toByte(), 6, 2, 1, 1, 2, 1, 1),
            byteArrayOf(0x30, 7, 2, 0x81.toByte(), 1, 1, 2, 1, 1),
            ByteArray(73).also { it[0] = 0x30; it[1] = 71 },
            valid.copyOf(valid.size - 1),
            valid + 0,
        )
        cases.forEachIndexed { index, bytes ->
            assertFalse("DER case=$index", MgdeV2Codec.isCanonicalP256Der(bytes))
            assertNull(MgdeV2Codec.decode(rawWire(envelope().canonicalBytes(), bytes)))
        }
    }

    private fun envelope(
        entry: com.gruning.mimir.tweaker.RestoreGuardianRestoreEntry =
            RestoreGuardianFanPutEntry(
                0,
                expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                rendererAppliedIntermediates = listOf(1),
            ),
    ) = RestoreGuardianEnvelope(
        helperBuildIdentity = "helper.1",
        tweakerCertificateSha256 = "a".repeat(64),
        hardwareFingerprint = "hardware.1",
        deviceProfile = "rp5",
        bootIdentity = "boot.1",
        generation = 1,
        requestId = "request.1",
        transactionId = "transaction.1",
        taskNonce = "b".repeat(32),
        topologySha256 = "c".repeat(64),
        snapshotSha256 = "d".repeat(64),
        planSha256 = "e".repeat(64),
        hardDeadlineElapsedRealtimeMs = 10,
        restoreEntries = listOf(entry),
    )

    private fun rawWire(envelope: ByteArray, signature: ByteArray): ByteArray =
        ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { out ->
                out.writeInt(MgdeV2Codec.MAGIC)
                out.writeInt(MgdeV2Codec.VERSION)
                out.writeInt(envelope.size)
                out.write(envelope)
                out.writeInt(signature.size)
                out.write(signature)
            }
        }.toByteArray()

    private fun der(r: ByteArray, s: ByteArray): ByteArray {
        fun integer(magnitude: ByteArray): ByteArray {
            val padded = magnitude[0].toInt() and 0x80 != 0
            return byteArrayOf(2, (magnitude.size + if (padded) 1 else 0).toByte()) +
                (if (padded) byteArrayOf(0) else byteArrayOf()) +
                magnitude
        }
        val body = integer(r) + integer(s)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun rawDerInteger(r: ByteArray, s: ByteArray): ByteArray {
        val body = byteArrayOf(2, r.size.toByte()) + r + byteArrayOf(2, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private data class EnvelopeOffsets(
        val helperLength: Int,
        val entryCount: Int,
        val intermediateCount: Int,
    )

    private fun envelopeOffsets(bytes: ByteArray): EnvelopeOffsets {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun skipText() {
            val size = input.int
            input.position(input.position() + size)
        }
        skipText()
        input.int
        val helper = input.position()
        skipText()
        repeat(4) { skipText() }
        input.long
        repeat(6) { skipText() }
        input.long
        val entries = input.position()
        input.int
        skipText()
        input.int
        skipText()
        val intermediates = input.position()
        return EnvelopeOffsets(helper, entries, intermediates)
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int =
        indices.firstOrNull { start ->
            start + needle.size <= size &&
                needle.indices.all { this[start + it] == needle[it] }
        } ?: -1
}
