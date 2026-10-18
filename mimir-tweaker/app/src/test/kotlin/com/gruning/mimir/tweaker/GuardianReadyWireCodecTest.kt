package com.gruning.mimir.tweaker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianReadyWireCodecTest {
    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(fixedPublic())
    private val canonical = receipt().canonicalBytes()
    private val signature = byteArrayOf(0x30, 0x01, 0x00)

    @Test
    fun `fixed READY golden frame is byte exact`() {
        val golden = requireNotNull(GuardianReadyWireCodec.encode(canonical, signature))
        assertEquals("AAABFQAAABxNSU1JUl9SRVNUT1JFX0dVQVJESUFOX1JFQURZAAAAAgAAAAhoZWxwZXIuMQAAAAZib290LjEAAAAAAAAABwAAAAlyZXF1ZXN0LjEAAAANdHJhbnNhY3Rpb24uMQAAACBhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQAAAEA0YzUwM2NhNjc3NjFlNWM0YWFlY2ZlOTk2MjQ0YzI1ZDhjMGI0MDkwMmQxMDg1Yzg1YjQ0NjhiZDU2NzU0OGM2AAAAQQSHJetzCqLi/4bD1+FFLAuU8KoOW2tZfq3SMHVR49zUymoCj8WEXOxlNhfudrISg9n5iNZKjsA7mNjA6dHV5A67AAAAAAAAAAEAAAADMAEA", Base64.getEncoder().encodeToString(golden))
        assertArrayEquals(golden, requireNotNull(GuardianReadyWireCodec.encode(canonical, signature)))
    }

    @Test
    fun `canonical READY and opaque signature round trip exactly`() {
        val payload = requireNotNull(GuardianReadyWireCodec.encode(canonical, signature))
        val decoded = requireNotNull(GuardianReadyWireCodec.decode(payload))
        assertArrayEquals(canonical, decoded.receipt.canonicalBytes())
        assertArrayEquals(signature, decoded.signature)
        assertArrayEquals(payload, requireNotNull(GuardianReadyWireCodec.encode(decoded.receipt.canonicalBytes(), decoded.signature)))
    }

    @Test
    fun `max signature is accepted and outer payload bound includes prefixes`() {
        val maxSignature = ByteArray(GuardianArmRecordCodec.MAX_SIGNATURE) { 0x5a }
        val payload = requireNotNull(GuardianReadyWireCodec.encode(canonical, maxSignature))
        assertEquals(2_568, GuardianReadyWireCodec.MAX_PAYLOAD_BYTES)
        assertEquals(GuardianArmRecordCodec.MAX_READY + GuardianArmRecordCodec.MAX_SIGNATURE + Int.SIZE_BYTES * 2, GuardianReadyWireCodec.MAX_PAYLOAD_BYTES)
        assertEquals(true, GuardianReadyWireCodec.MAX_PAYLOAD_BYTES <= 8_192)
        assertEquals(true, payload.size <= GuardianReadyWireCodec.MAX_PAYLOAD_BYTES)
        assertArrayEquals(maxSignature, requireNotNull(GuardianReadyWireCodec.decode(payload)).signature)
    }

    @Test
    fun `maximum READY payload fits the real M4R1 framed transport and chunk budget`() {
        val payload = ByteArray(GuardianReadyWireCodec.MAX_PAYLOAD_BYTES) { 0x5a }
        val framed = requireNotNull(GuardianReceiptChannelParser.encodeFrame(payload))
        val expectedFramedBytes = GuardianReadyWireCodec.MAX_PAYLOAD_BYTES + Int.SIZE_BYTES * 2
        val chunks = (framed.size + GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES - 1) /
            GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES

        assertEquals(expectedFramedBytes, framed.size)
        assertArrayEquals(payload, requireNotNull(GuardianReceiptChannelParser.decodeFrame(framed)))
        assertEquals(chunks, (GuardianReadyWireCodec.MAX_PAYLOAD_BYTES + 8 + GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES - 1) / GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES)
        assertTrue(chunks <= GuardianReceiptChannelRenderer.MAX_RECEIPT_CHUNKS)
    }

    @Test
    fun `decode rejects every truncation frame trailing and 8192 byte junk`() {
        val payload = requireNotNull(GuardianReadyWireCodec.encode(canonical, signature))
        (0 until payload.size).forEach { length -> assertNull("truncation at $length", GuardianReadyWireCodec.decode(payload.copyOf(length))) }
        assertNull(GuardianReadyWireCodec.decode(payload + 0))
        assertNull(GuardianReadyWireCodec.decode(ByteArray(8_192)))
        assertNull(GuardianReadyWireCodec.decode(ByteArray(8_192) { 0x5a }))
    }

    @Test
    fun `hostile outer lengths are padded so bounded length checks are reached`() {
        listOf(0, -1, GuardianArmRecordCodec.MAX_READY + 1, Int.MAX_VALUE).forEach { length ->
            assertNull("canonical length $length", GuardianReadyWireCodec.decode(rawPayload(length, ByteArray(1), 1, byteArrayOf(1))))
        }
        listOf(0, -1, GuardianArmRecordCodec.MAX_SIGNATURE + 1, Int.MAX_VALUE).forEach { length ->
            assertNull("signature length $length", GuardianReadyWireCodec.decode(rawPayload(canonical.size, canonical, length, byteArrayOf(1))))
        }
    }

    @Test
    fun `canonical length skew and inflated canonical trailing byte are rejected`() {
        val plusOne = rawPayload(canonical.size + 1, canonical + signature.copyOfRange(0, 1), signature.size - 1, signature.copyOfRange(1, signature.size))
        val minusOne = rawPayload(canonical.size - 1, canonical.copyOf(canonical.size - 1), signature.size + 1, canonical.copyOfRange(canonical.size - 1, canonical.size) + signature)
        assertEquals(requireNotNull(GuardianReadyWireCodec.encode(canonical, signature)).size, plusOne.size)
        assertEquals(requireNotNull(GuardianReadyWireCodec.encode(canonical, signature)).size, minusOne.size)
        assertNull(GuardianReadyWireCodec.decode(plusOne))
        assertNull(GuardianReadyWireCodec.decode(minusOne))
    }

    @Test
    fun `encode and decode reject noncanonical canonical layer trailing data`() {
        assertNull(GuardianReadyWireCodec.encode(canonical + 0, signature))
        assertNull(GuardianReadyWireCodec.decode(rawPayload(canonical.size + 1, canonical + 0, signature.size, signature)))
    }

    @Test
    fun `decode rejects schema one schema three and genuine other canonical types`() {
        listOf(1, 3).forEach { schema ->
            val changed = canonical.copyOf().also { bytes -> bytes[schemaOffset(bytes) + 3] = schema.toByte() }
            assertNull("schema $schema", GuardianReadyWireCodec.decode(rawPayload(changed.size, changed, signature.size, signature)))
        }
        crossTypeCanonicalBytes().forEach { bytes -> assertNull(GuardianReadyWireCodec.decode(rawPayload(bytes.size, bytes, signature.size, signature))) }
    }

    @Test
    fun `decode rejects malformed SEC1 and accepts reflected structurally valid key`() {
        val keyOffset = sessionKeyOffset(canonical)
        val badPrefix = canonical.copyOf().also { it[keyOffset] = 0x02 }
        val zeroPoint = canonical.copyOf().also { bytes ->
            bytes[keyOffset] = 0x04
            (keyOffset + 1 until keyOffset + GuardianSessionPublicKey.SEC1_UNCOMPRESSED_BYTES).forEach { bytes[it] = 0 }
        }
        val offCurve = canonical.copyOf().also { bytes -> bytes[keyOffset + GuardianSessionPublicKey.SEC1_UNCOMPRESSED_BYTES - 1] = (bytes[keyOffset + GuardianSessionPublicKey.SEC1_UNCOMPRESSED_BYTES - 1].toInt() xor 1).toByte() }
        listOf(badPrefix, zeroPoint, offCurve).forEach { malformed -> assertNull(GuardianReadyWireCodec.decode(rawPayload(malformed.size, malformed, signature.size, signature))) }
        listOf(64, 66).forEach { length ->
            val wrongLength = canonical.copyOf().also { it[sessionKeyOffset(canonical) - Int.SIZE_BYTES + 3] = length.toByte() }
            assertNull(GuardianReadyWireCodec.decode(rawPayload(wrongLength.size, wrongLength, signature.size, signature)))
        }
        assertNotNull(GuardianReadyWireCodec.decode(requireNotNull(GuardianReadyWireCodec.encode(canonical, signature))))
    }

    @Test
    fun `codec structurally decodes READY carrying the app authority public key`() {
        val appAuthority = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val reflectedReceipt = receipt().copy(sessionPublicKey = GuardianSessionPublicKey.fromPublicKey(appAuthority.public))
        val payload = requireNotNull(GuardianReadyWireCodec.encode(reflectedReceipt.canonicalBytes(), signature))

        assertArrayEquals(reflectedReceipt.canonicalBytes(), requireNotNull(GuardianReadyWireCodec.decode(payload)).receipt.canonicalBytes())
    }

    private fun crossTypeCanonicalBytes(): List<ByteArray> {
        val envelope = envelope()
        val envelopeDigest = envelope.digestSha256()
        val keyDigest = sessionKey.sha256()
        val stock = RestoreGuardianStockConfirmedReceipt(
            helperBuildIdentity = "helper.1", bootIdentity = "boot.1", generation = 7,
            requestId = "request.1", transactionId = "transaction.1", taskNonce = "a".repeat(32),
            envelopeSha256 = envelopeDigest, sessionKeySha256 = keyDigest,
            firstReadbackElapsedRealtimeMs = 1, secondReadbackElapsedRealtimeMs = 2,
            issuedElapsedRealtimeMs = 3,
        )
        return listOf(
            envelope.canonicalBytes(),
            RestoreGuardianHeartbeat(helperBuildIdentity = "helper.1", bootIdentity = "boot.1", generation = 7, requestId = "request.1", transactionId = "transaction.1", taskNonce = "a".repeat(32), envelopeSha256 = envelopeDigest, counter = 1, elapsedRealtimeMs = 1, hardDeadlineElapsedRealtimeMs = 10).canonicalBytes(),
            RestoreGuardianSessionPin(envelopeSha256 = envelopeDigest, sessionKeySha256 = keyDigest, readyReceiptSha256 = digest("ready"), readyIssuedElapsedRealtimeMs = 1, pinnedElapsedRealtimeMs = 2).canonicalBytes(),
            stock.canonicalBytes(),
            RestoreGuardianExitedReceipt(helperBuildIdentity = "helper.1", bootIdentity = "boot.1", generation = 7, requestId = "request.1", transactionId = "transaction.1", taskNonce = "a".repeat(32), envelopeSha256 = envelopeDigest, sessionKeySha256 = keyDigest, stockConfirmedReceiptSha256 = stock.digestSha256(), issuedElapsedRealtimeMs = 4).canonicalBytes(),
        )
    }

    private fun receipt() = RestoreGuardianReadyReceipt(
        helperBuildIdentity = "helper.1", bootIdentity = "boot.1", generation = 7, requestId = "request.1", transactionId = "transaction.1", taskNonce = "a".repeat(32), envelopeSha256 = digest("envelope"), sessionPublicKey = sessionKey, issuedElapsedRealtimeMs = 1,
    )

    private fun envelope() = RestoreGuardianEnvelope(
        helperBuildIdentity = "helper.1", tweakerCertificateSha256 = digest("certificate"), hardwareFingerprint = "hardware.1", deviceProfile = "rp5.1", bootIdentity = "boot.1", generation = 7, requestId = "request.1", transactionId = "transaction.1", taskNonce = "a".repeat(32), topologySha256 = digest("topology"), snapshotSha256 = digest("snapshot"), planSha256 = digest("plan"), hardDeadlineElapsedRealtimeMs = 10,
        restoreEntries = listOf(RestoreGuardianFanPutEntry(1, expectedStock = RestoreGuardianStockState.PresentFanMode(0), rendererAppliedIntermediates = listOf(1))),
    )

    private fun rawPayload(canonicalLength: Int, canonicalBytes: ByteArray, signatureLength: Int, signatureBytes: ByteArray): ByteArray = ByteArrayOutputStream().also { sink ->
        DataOutputStream(sink).use { out -> out.writeInt(canonicalLength); out.write(canonicalBytes); out.writeInt(signatureLength); out.write(signatureBytes) }
    }.toByteArray()

    private fun sessionKeyOffset(bytes: ByteArray): Int = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        input.skipField(); input.readInt(); input.skipField(); input.skipField(); input.readLong(); input.skipField(); input.skipField(); input.skipField(); input.skipField(); input.readInt(); bytes.size - input.available()
    }

    private fun schemaOffset(bytes: ByteArray): Int = Int.SIZE_BYTES + readIntAt(bytes, 0)
    private fun DataInputStream.skipField() { val size = readInt(); require(size >= 0); skipBytes(size).also { require(it == size) } }
    private fun readIntAt(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xff shl 24) or (bytes[offset + 1].toInt() and 0xff shl 16) or (bytes[offset + 2].toInt() and 0xff shl 8) or (bytes[offset + 3].toInt() and 0xff)
    private fun digest(value: String) = GuardianCanonicalCodec.sha256(value.toByteArray())
    private fun fixedPublic() = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhyXrcwqi4v+Gw9fhRSwLlPCqDltrWX6t0jB1UePc1MpqAo/FhFzsZTYX7nayEoPZ+YjWSo7AO5jYwOnR1eQOuw==")))
}
