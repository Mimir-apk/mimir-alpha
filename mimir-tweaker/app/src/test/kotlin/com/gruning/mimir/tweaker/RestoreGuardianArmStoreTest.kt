package com.gruning.mimir.tweaker

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreGuardianArmStoreTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val available = GuardianTestKeyAuthority.create(digest("certificate"), pair).available
    private val pinned = available.envelopeVerifier()
    private val signer = EcdsaP256Signer(pair.private)
    private val sessionPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(sessionPair.public)
    private val sessionSigner = EcdsaP256GuardianSessionSigner(sessionPair.private)

    @Test
    fun `checked in MGRA v2 is retained corrupt while v3 staged and ready goldens encode decode and verify`() {
        val app = fixedPublic(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEN5x0bvhbHZvBooufeTO0aulew7/t8rs+nrqvmJf8DH0eDTM1EQ+8hKz07q8pGB+ZClOI5Q3G1ZX5UGopuJ8MMg==",
        )
        val guardian = fixedPublic(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhyXrcwqi4v+Gw9fhRSwLlPCqDltrWX6t0jB1UePc1MpqAo/FhFzsZTYX7nayEoPZ+YjWSo7AO5jYwOnR1eQOuw==",
        )
        val value = envelope()
        val fixedAvailable = GuardianTestKeyAuthority.create(
            certificateDigest = digest("certificate"),
            certificatePublicKey = app,
        ).available
        val verifier = fixedAvailable.envelopeVerifier()
        val signed = SignedRestoreGuardianEnvelope(value, b64("MEUCIQDllJV7Y5qoN/GGj3YmouF2RN+MhRUC0oHhlfVnySb1wAIgNrH3qs++hidBsB1jJKmXVa2mdfCFtVNdPmYOKRF/F+4="))
        val session = GuardianSessionPublicKey.fromPublicKey(guardian)
        val ready = SignedRestoreGuardianReadyReceipt(
            receipt(value).copy(sessionPublicKey = session),
            b64("MEYCIQDTYCcJOoXnKfWZXz+ftJPr/KW7engvgDKy4bgb2VHY7AIhAPi626ohOBsR6qObGUK8KzWDsAYONl6k+Fpw9eZJSKZ2"),
        )
        val pin = SignedRestoreGuardianSessionPin(
            RestoreGuardianSessionPin(
                envelopeSha256 = value.digestSha256(), sessionKeySha256 = session.sha256(),
                readyReceiptSha256 = GuardianCanonicalCodec.sha256(ready.receipt.canonicalBytes()),
                readyIssuedElapsedRealtimeMs = 1, pinnedElapsedRealtimeMs = 2,
            ),
            b64("MEQCIGq/lKGXT+oCQk+2P+IfEXfcUhE/NIvBuI2NArfVOlZnAiAQ8/trEw3xPVWMxW0KbV6+cfKMVakSh2IlR6NLbVYmqA=="),
        )
        val legacyV2Staged = b64("TUdSQQAAAAIAAAABAAACgwAAAB9NSU1JUl9SRVNUT1JFX0dVQVJESUFOX0VOVkVMT1BFAAAAAgAAAAhoZWxwZXIuMQAAAEAwM2Q2NmRkMDg4MzVjMWNhM2YxMjhjY2VhY2QxZjMxYWM5NDE2MzA5NmIyMGY0NDVhZTg0Mjg1YmMwODMyZDcyAAAACmhhcmR3YXJlLjEAAAAFcnA1LjEAAAAGYm9vdC4xAAAAAAAAAAcAAAAJcmVxdWVzdC4xAAAADXRyYW5zYWN0aW9uLjEAAAAgYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEAAABAZTZlMmI4MjZlMzFmY2E1YzM2MTI1YzQ4ZjEzMGRjYjZmOTYxZTY5OGZmOGE4Nzc2YTFmMjkwY2YwODkyZThlNgAAAEAxNmEwZWViMDc5MWI2YzkyNDUxZmQyODRkZDlmNTk5ZTBhN2RiZTdmNmViZWE2ZTJkMmQwNmM3Zjc0YWVjMTEyAAAAQDY0ODc5ZjdkNmI5NjBhMDE5MDk3NjJkOTExYTMyZDQ1ODJjMjAwMTBjNTY0MWVlOTAyNzhiNjQ0YTllM2I1MjUAAAAAAAAnEAAAAAIAAAAFc3lzZnMAAAADAAAAFmNwdToxOnNjYWxpbmdfbWF4X2ZyZXEAAAABAB6EgAAAADgvc3lzL2RldmljZXMvc3lzdGVtL2NwdS9jcHVmcmVxL3BvbGljeTEvc2NhbGluZ19tYXhfZnJlcQAAAA1wcmVzZW50X3N5c2ZzABJPgAAAAAM2NDQAAAAAAAAAAAAAAAdmYW5fcHV0AAAAAgAAAAxmYW46ZmFuX21vZGUAAAABAAAAAgAAAAtwcmVzZW50X2ZhbgAAAAAAAABHMEUCIQDllJV7Y5qoN/GGj3YmouF2RN+MhRUC0oHhlfVnySb1wAIgNrH3qs++hidBsB1jJKmXVa2mdfCFtVNdPmYOKRF/F+4AAABbMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEN5x0bvhbHZvBooufeTO0aulew7/t8rs+nrqvmJf8DH0eDTM1EQ+8hKz07q8pGB+ZClOI5Q3G1ZX5UGopuJ8MMgAAAEAwM2Q2NmRkMDg4MzVjMWNhM2YxMjhjY2VhY2QxZjMxYWM5NDE2MzA5NmIyMGY0NDVhZTg0Mjg1YmMwODMyZDcyAAAAAA==")
        val readyRecord = b64("TUdSQQAAAAIAAAACAAACgwAAAB9NSU1JUl9SRVNUT1JFX0dVQVJESUFOX0VOVkVMT1BFAAAAAgAAAAhoZWxwZXIuMQAAAEAwM2Q2NmRkMDg4MzVjMWNhM2YxMjhjY2VhY2QxZjMxYWM5NDE2MzA5NmIyMGY0NDVhZTg0Mjg1YmMwODMyZDcyAAAACmhhcmR3YXJlLjEAAAAFcnA1LjEAAAAGYm9vdC4xAAAAAAAAAAcAAAAJcmVxdWVzdC4xAAAADXRyYW5zYWN0aW9uLjEAAAAgYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEAAABAZTZlMmI4MjZlMzFmY2E1YzM2MTI1YzQ4ZjEzMGRjYjZmOTYxZTY5OGZmOGE4Nzc2YTFmMjkwY2YwODkyZThlNgAAAEAxNmEwZWViMDc5MWI2YzkyNDUxZmQyODRkZDlmNTk5ZTBhN2RiZTdmNmViZWE2ZTJkMmQwNmM3Zjc0YWVjMTEyAAAAQDY0ODc5ZjdkNmI5NjBhMDE5MDk3NjJkOTExYTMyZDQ1ODJjMjAwMTBjNTY0MWVlOTAyNzhiNjQ0YTllM2I1MjUAAAAAAAAnEAAAAAIAAAAFc3lzZnMAAAADAAAAFmNwdToxOnNjYWxpbmdfbWF4X2ZyZXEAAAABAB6EgAAAADgvc3lzL2RldmljZXMvc3lzdGVtL2NwdS9jcHVmcmVxL3BvbGljeTEvc2NhbGluZ19tYXhfZnJlcQAAAA1wcmVzZW50X3N5c2ZzABJPgAAAAAM2NDQAAAAAAAAAAAAAAAdmYW5fcHV0AAAAAgAAAAxmYW46ZmFuX21vZGUAAAABAAAAAgAAAAtwcmVzZW50X2ZhbgAAAAAAAABHMEUCIQDllJV7Y5qoN/GGj3YmouF2RN+MhRUC0oHhlfVnySb1wAIgNrH3qs++hidBsB1jJKmXVa2mdfCFtVNdPmYOKRF/F+4AAABbMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEN5x0bvhbHZvBooufeTO0aulew7/t8rs+nrqvmJf8DH0eDTM1EQ+8hKz07q8pGB+ZClOI5Q3G1ZX5UGopuJ8MMgAAAEAwM2Q2NmRkMDg4MzVjMWNhM2YxMjhjY2VhY2QxZjMxYWM5NDE2MzA5NmIyMGY0NDVhZTg0Mjg1YmMwODMyZDcyAAAAAAAAAEEEhyXrcwqi4v+Gw9fhRSwLlPCqDltrWX6t0jB1UePc1MpqAo/FhFzsZTYX7nayEoPZ+YjWSo7AO5jYwOnR1eQOuwAAARUAAAAcTUlNSVJfUkVTVE9SRV9HVUFSRElBTl9SRUFEWQAAAAIAAAAIaGVscGVyLjEAAAAGYm9vdC4xAAAAAAAAAAcAAAAJcmVxdWVzdC4xAAAADXRyYW5zYWN0aW9uLjEAAAAgYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEAAABAZmU3OTQ0NzA4YjEwMjEyY2U1NGZiOGE2Y2JjMWJiMTJmN2M1NTE5OWQ2ZGViYjYyOWNlOWVkNzkyMmM3Y2YyNwAAAEEEhyXrcwqi4v+Gw9fhRSwLlPCqDltrWX6t0jB1UePc1MpqAo/FhFzsZTYX7nayEoPZ+YjWSo7AO5jYwOnR1eQOuwAAAAAAAAABAAAASDBGAiEA02AnCTqF5yn1mV8/n7ST6/ylu3p4L4AysuG4G9lR2OwCIQD4utuqITgbEeqjmxlCvCs1g7AGDjZepPhacPXmSUimdgAAAQYAAAAiTUlNSVJfUkVTVE9SRV9HVUFSRElBTl9TRVNTSU9OX1BJTgAAAAIAAABAZmU3OTQ0NzA4YjEwMjEyY2U1NGZiOGE2Y2JjMWJiMTJmN2M1NTE5OWQ2ZGViYjYyOWNlOWVkNzkyMmM3Y2YyNwAAAEBjMjMyNmM1OGI2ZmEzYTk0OTZlMTZiZTg4NzYzN2FlNmZhZTA1NzM1ZTFjMzRhMDJjNDM3YWNlZjBjYmU0MjYzAAAAQDY2ZjBjN2IzMDI5MDFlMTdhM2Y4ODFmYmM0NzgwNWE0N2ZlMWRhMTAzMzgwOWViMzI1OTRiZWQwMDJjMWQ0OGYAAAAAAAAAAQAAAAAAAAACAAAARjBEAiBqv5Shl0/qAkJPtj/iHxF33FIRPzSLwbiNjQK31TpWZwIgEPP7axMN8T1VjMVtCm1evnHyjFWpEodiJUejS21WJqg=")

        assertEquals(GuardianArmStoreLoad.Corrupt, GuardianArmRecordCodec.decodeAndVerify(legacyV2Staged, expected(value), verifier))
        assertEquals(GuardianArmStoreLoad.Corrupt, GuardianArmRecordCodec.decodeAndVerify(readyRecord, expected(value), verifier))
        val stagedV3 = GuardianArmRecordCodec.encodeStaged(signed, verifier)
        val readyV3 = GuardianArmRecordCodec.encodeReady(signed, verifier, session, ready, pin)
        val stagedV3Golden = b64("TUdSQQAAAAMAAAABAAACgwAAAB9NSU1JUl9SRVNUT1JFX0dVQVJESUFOX0VOVkVMT1BFAAAAAgAAAAhoZWxwZXIuMQAAAEAwM2Q2NmRkMDg4MzVjMWNhM2YxMjhjY2VhY2QxZjMxYWM5NDE2MzA5NmIyMGY0NDVhZTg0Mjg1YmMwODMyZDcyAAAACmhhcmR3YXJlLjEAAAAFcnA1LjEAAAAGYm9vdC4xAAAAAAAAAAcAAAAJcmVxdWVzdC4xAAAADXRyYW5zYWN0aW9uLjEAAAAgYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEAAABAZTZlMmI4MjZlMzFmY2E1YzM2MTI1YzQ4ZjEzMGRjYjZmOTYxZTY5OGZmOGE4Nzc2YTFmMjkwY2YwODkyZThlNgAAAEAxNmEwZWViMDc5MWI2YzkyNDUxZmQyODRkZDlmNTk5ZTBhN2RiZTdmNmViZWE2ZTJkMmQwNmM3Zjc0YWVjMTEyAAAAQDY0ODc5ZjdkNmI5NjBhMDE5MDk3NjJkOTExYTMyZDQ1ODJjMjAwMTBjNTY0MWVlOTAyNzhiNjQ0YTllM2I1MjUAAAAAAAAnEAAAAAIAAAAFc3lzZnMAAAADAAAAFmNwdToxOnNjYWxpbmdfbWF4X2ZyZXEAAAABAB6EgAAAADgvc3lzL2RldmljZXMvc3lzdGVtL2NwdS9jcHVmcmVxL3BvbGljeTEvc2NhbGluZ19tYXhfZnJlcQAAAA1wcmVzZW50X3N5c2ZzABJPgAAAAAM2NDQAAAAAAAAAAAAAAAdmYW5fcHV0AAAAAgAAAAxmYW46ZmFuX21vZGUAAAABAAAAAgAAAAtwcmVzZW50X2ZhbgAAAAAAAABHMEUCIQDllJV7Y5qoN/GGj3YmouF2RN+MhRUC0oHhlfVnySb1wAIgNrH3qs++hidBsB1jJKmXVa2mdfCFtVNdPmYOKRF/F+4AAABbMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEN5x0bvhbHZvBooufeTO0aulew7/t8rs+nrqvmJf8DH0eDTM1EQ+8hKz07q8pGB+ZClOI5Q3G1ZX5UGopuJ8MMgAAAEAwM2Q2NmRkMDg4MzVjMWNhM2YxMjhjY2VhY2QxZjMxYWM5NDE2MzA5NmIyMGY0NDVhZTg0Mjg1YmMwODMyZDcy")
        val readyV3Golden = b64("TUdSQQAAAAMAAAACAAACgwAAAB9NSU1JUl9SRVNUT1JFX0dVQVJESUFOX0VOVkVMT1BFAAAAAgAAAAhoZWxwZXIuMQAAAEAwM2Q2NmRkMDg4MzVjMWNhM2YxMjhjY2VhY2QxZjMxYWM5NDE2MzA5NmIyMGY0NDVhZTg0Mjg1YmMwODMyZDcyAAAACmhhcmR3YXJlLjEAAAAFcnA1LjEAAAAGYm9vdC4xAAAAAAAAAAcAAAAJcmVxdWVzdC4xAAAADXRyYW5zYWN0aW9uLjEAAAAgYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEAAABAZTZlMmI4MjZlMzFmY2E1YzM2MTI1YzQ4ZjEzMGRjYjZmOTYxZTY5OGZmOGE4Nzc2YTFmMjkwY2YwODkyZThlNgAAAEAxNmEwZWViMDc5MWI2YzkyNDUxZmQyODRkZDlmNTk5ZTBhN2RiZTdmNmViZWE2ZTJkMmQwNmM3Zjc0YWVjMTEyAAAAQDY0ODc5ZjdkNmI5NjBhMDE5MDk3NjJkOTExYTMyZDQ1ODJjMjAwMTBjNTY0MWVlOTAyNzhiNjQ0YTllM2I1MjUAAAAAAAAnEAAAAAIAAAAFc3lzZnMAAAADAAAAFmNwdToxOnNjYWxpbmdfbWF4X2ZyZXEAAAABAB6EgAAAADgvc3lzL2RldmljZXMvc3lzdGVtL2NwdS9jcHVmcmVxL3BvbGljeTEvc2NhbGluZ19tYXhfZnJlcQAAAA1wcmVzZW50X3N5c2ZzABJPgAAAAAM2NDQAAAAAAAAAAAAAAAdmYW5fcHV0AAAAAgAAAAxmYW46ZmFuX21vZGUAAAABAAAAAgAAAAtwcmVzZW50X2ZhbgAAAAAAAABHMEUCIQDllJV7Y5qoN/GGj3YmouF2RN+MhRUC0oHhlfVnySb1wAIgNrH3qs++hidBsB1jJKmXVa2mdfCFtVNdPmYOKRF/F+4AAABbMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEN5x0bvhbHZvBooufeTO0aulew7/t8rs+nrqvmJf8DH0eDTM1EQ+8hKz07q8pGB+ZClOI5Q3G1ZX5UGopuJ8MMgAAAEAwM2Q2NmRkMDg4MzVjMWNhM2YxMjhjY2VhY2QxZjMxYWM5NDE2MzA5NmIyMGY0NDVhZTg0Mjg1YmMwODMyZDcyAAAAQQSHJetzCqLi/4bD1+FFLAuU8KoOW2tZfq3SMHVR49zUymoCj8WEXOxlNhfudrISg9n5iNZKjsA7mNjA6dHV5A67AAABFQAAABxNSU1JUl9SRVNUT1JFX0dVQVJESUFOX1JFQURZAAAAAgAAAAhoZWxwZXIuMQAAAAZib290LjEAAAAAAAAABwAAAAlyZXF1ZXN0LjEAAAANdHJhbnNhY3Rpb24uMQAAACBhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQAAAEBmZTc5NDQ3MDhiMTAyMTJjZTU0ZmI4YTZjYmMxYmIxMmY3YzU1MTk5ZDZkZWJiNjI5Y2U5ZWQ3OTIyYzdjZjI3AAAAQQSHJetzCqLi/4bD1+FFLAuU8KoOW2tZfq3SMHVR49zUymoCj8WEXOxlNhfudrISg9n5iNZKjsA7mNjA6dHV5A67AAAAAAAAAAEAAABIMEYCIQDTYCcJOoXnKfWZXz+ftJPr/KW7engvgDKy4bgb2VHY7AIhAPi626ohOBsR6qObGUK8KzWDsAYONl6k+Fpw9eZJSKZ2AAABBgAAACJNSU1JUl9SRVNUT1JFX0dVQVJESUFOX1NFU1NJT05fUElOAAAAAgAAAEBmZTc5NDQ3MDhiMTAyMTJjZTU0ZmI4YTZjYmMxYmIxMmY3YzU1MTk5ZDZkZWJiNjI5Y2U5ZWQ3OTIyYzdjZjI3AAAAQGMyMzI2YzU4YjZmYTNhOTQ5NmUxNmJlODg3NjM3YWU2ZmFlMDU3MzVlMWMzNGEwMmM0MzdhY2VmMGNiZTQyNjMAAABANjZmMGM3YjMwMjkwMWUxN2EzZjg4MWZiYzQ3ODA1YTQ3ZmUxZGExMDMzODA5ZWIzMjU5NGJlZDAwMmMxZDQ4ZgAAAAAAAAABAAAAAAAAAAIAAABGMEQCIGq/lKGXT+oCQk+2P+IfEXfcUhE/NIvBuI2NArfVOlZnAiAQ8/trEw3xPVWMxW0KbV6+cfKMVakSh2IlR6NLbVYmqA==")
        assertTrue(stagedV3Golden.contentEquals(stagedV3))
        assertTrue(readyV3Golden.contentEquals(readyV3))
        // Checked-in deterministic v3 byte vectors: the body is the prior canonical vector
        // minus only the retired verdict field, with the version word advanced to v3.
        assertEquals("b22e0436439fa5339676b381aeb6adb7fd57a7767c28b9a20e965e7188ef2a1b", GuardianCanonicalCodec.sha256(stagedV3))
        assertEquals("5dd16429b673f0fad249b94518adc1e67a718f44f08afc6186aeff98f269e9a6", GuardianCanonicalCodec.sha256(readyV3))
        assertTrue(GuardianArmRecordCodec.decodeAndVerify(stagedV3Golden, expected(value), verifier) is GuardianArmStoreLoad.LoadedStaged)
        assertTrue(GuardianArmRecordCodec.decodeAndVerify(readyV3Golden, expected(value), verifier) is GuardianArmStoreLoad.LoadedReady)

        // P2: every strict prefix and every one-bit byte mutation of both exact v3
        // checked-in vectors must fail closed.
        mapOf("staged" to stagedV3Golden, "ready" to readyV3Golden).forEach { (label, exact) ->
            for (length in 0 until exact.size) {
                assertEquals(
                    "$label v3 truncation at $length",
                    GuardianArmStoreLoad.Corrupt,
                    GuardianArmRecordCodec.decodeAndVerify(exact.copyOf(length), expected(value), verifier),
                )
            }
            for (index in exact.indices) {
                val tampered = exact.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
                assertEquals(
                    "$label v3 xor at $index",
                    GuardianArmStoreLoad.Corrupt,
                    GuardianArmRecordCodec.decodeAndVerify(tampered, expected(value), verifier),
                )
            }
        }

        // P1: a v1 version-word-only synthetic, the checked-in v2 literal, and sampled
        // malformed v3 files remain byte-identical under load/arm/pin rejection.
        val legacyV1Staged = legacyV2Staged.copyOf().also {
            it[4] = 0
            it[5] = 0
            it[6] = 0
            it[7] = 1
        }
        assertTrue(legacyV1Staged.copyOfRange(0, 4).contentEquals(legacyV2Staged.copyOfRange(0, 4)))
        assertTrue(legacyV1Staged.copyOfRange(8, legacyV1Staged.size).contentEquals(legacyV2Staged.copyOfRange(8, legacyV2Staged.size)))
        val retainedCases = mapOf(
            "v1" to legacyV1Staged,
            "v2" to legacyV2Staged,
            "v3-truncated" to stagedV3Golden.copyOf(stagedV3Golden.size - 1),
            "v3-trailing" to (stagedV3Golden + 0),
            "v3-tampered" to stagedV3Golden.copyOf().also {
                val index = it.size / 2
                it[index] = (it[index].toInt() xor 0x01).toByte()
            },
        )
        retainedCases.forEach { (label, hostile) ->
            val directory = Files.createTempDirectory("guardian-retained-$label-").toFile()
            try {
                val recordFile = File(directory, "restore-guardian.arm")
                val stagingFile = File(directory, ".restore-guardian.arm.tmp")
                recordFile.writeBytes(hostile)
                val before = recordFile.readBytes()
                val store = FileRestoreGuardianArmStore(fixedAvailable, directory)
                assertEquals("$label load", GuardianArmStoreLoad.Corrupt, store.load(expected(value)))
                assertEquals("$label arm", GuardianArmStoreWrite.Rejected, store.arm(signed))
                assertEquals(
                    "$label pin",
                    GuardianSessionPinWrite.Rejected,
                    store.pinSessionKey(expected(value), session, ready, 2),
                )
                assertTrue("$label bytes retained", before.contentEquals(recordFile.readBytes()))
                assertFalse("$label must not create staging", stagingFile.exists())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `in-memory store atomically admits only a verified exact arm record`() {
        val value = envelope()
        val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
        val store = InMemoryRestoreGuardianArmStore(available)

        assertEquals(GuardianArmStoreWrite.Armed, store.arm(signed))
        assertEquals(GuardianArmStoreWrite.Rejected, store.arm(signed))
        val loaded = store.load(expected(value))
        assertTrue(loaded is GuardianArmStoreLoad.LoadedStaged)
        assertTrue((loaded as GuardianArmStoreLoad.LoadedStaged).signed.envelope.canonicalBytes().contentEquals(value.canonicalBytes()))
        val ready = RestoreGuardianCrypto.signReadyReceipt(receipt(value), sessionSigner)
        assertEquals(GuardianSessionPinWrite.Pinned, store.pinSessionKey(expected(value), sessionKey, ready, 2))
        val reloaded = store.load(expected(value))
        assertTrue(reloaded is GuardianArmStoreLoad.LoadedReady)
        assertEquals(sessionKey, (reloaded as GuardianArmStoreLoad.LoadedReady).sessionKey)
    }

    @Test
    fun `self minted session READY can load but proves no guardian execution`() {
        val value = envelope()
        val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
        val selfMintedReady = RestoreGuardianCrypto.signReadyReceipt(receipt(value), sessionSigner)
        val store = InMemoryRestoreGuardianArmStore(available)
        assertEquals(GuardianArmStoreWrite.Armed, store.arm(signed))
        assertEquals(
            GuardianSessionPinWrite.Pinned,
            store.pinSessionKey(expected(value), sessionKey, selfMintedReady, 2),
        )
        assertTrue(store.load(expected(value)) is GuardianArmStoreLoad.LoadedReady)
        assertFalse((available as Any) is GuardianSessionSigner)
        // LoadedReady is only possession of a valid session signature in this pure host seam.
        // It makes no claim that a Guardian process ran or that a protected writer published it.
    }

    @Test
    fun `signed sysfs arm record round trips only in canonical entry field order`() {
        val value = envelope()
        val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
        val canonicalRecord = GuardianArmRecordCodec.encode(signed, pinned)

        assertTrue(GuardianArmRecordCodec.decodeAndVerify(canonicalRecord, expected(value), pinned) is GuardianArmStoreLoad.LoadedStaged)

        val reorderedEnvelope = swapAdjacentDelimitedFields(
            value.canonicalBytes(),
            "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq",
            "present_sysfs",
        )
        assertEquals(
            GuardianArmStoreLoad.Corrupt,
            GuardianArmRecordCodec.decodeAndVerify(recordWithEnvelope(signed, reorderedEnvelope), expected(value), pinned),
        )
        assertEquals(
            GuardianArmStoreLoad.Corrupt,
            GuardianArmRecordCodec.decodeAndVerify(canonicalRecord.copyOf(canonicalRecord.size - 1), expected(value), pinned),
        )
        assertEquals(
            GuardianArmStoreLoad.Corrupt,
            GuardianArmRecordCodec.decodeAndVerify(canonicalRecord + 0, expected(value), pinned),
        )
    }

    /**
     * The stall this pins was device-reproduced on the shipping build 2026-08-09. The expected
     * identity used to carry `planSha256`, derived from the mutable `record.planHash`. A WAVE2
     * derate lawfully moves that hash, so after one actuated rung the derived identity no longer
     * matched the stored envelope: the arm loaded Corrupt, `restoreAndSettle` returned
     * GUARDIAN_READY_UNAVAILABLE before requesting any restore, the record was stranded at
     * RESTORING, and every later apply was refused SESSION_NOT_SETTLED until app data was cleared.
     *
     * The envelope's own plan hash is unchanged and still covered -- it is inside the canonical
     * bytes the digest and signature are taken over. What is gone is the requirement that the
     * RECORD's plan still equal it, which is the invariant the derate ladder retires.
     */
    @Test
    fun `arm identity pairs on immutable fields only, so applied state cannot strand it`() {
        val value = envelope()
        val store = InMemoryRestoreGuardianArmStore(available)
        assertEquals(GuardianArmStoreWrite.Armed, store.arm(RestoreGuardianCrypto.signEnvelope(value, signer)))

        assertTrue(store.load(expected(value)) is GuardianArmStoreLoad.LoadedStaged)

        // Every field that CAN strand a load is asserted in the fail-closed test below, and each
        // one is immutable for the life of a transaction. This test's real content is what is
        // absent: no plan hash participates, so no lawful change to applied state can make a
        // correctly-armed envelope unloadable. That absence is compiler-enforced now the field is
        // gone -- the behavioural proof that it fixes the stall is the device run, not this test.
    }

    @Test
    fun `load fails closed for different boot replay terminal truncation and byte tamper`() {
        val value = envelope()
        val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
        val store = InMemoryRestoreGuardianArmStore(available)
        assertEquals(GuardianArmStoreWrite.Armed, store.arm(signed))

        val identity = expected(value)
        listOf(
            identity.copy(helperBuildIdentity = "helper.2"), identity.copy(tweakerCertificateSha256 = digest("other-cert")),
            identity.copy(hardwareFingerprint = "hardware.2"), identity.copy(deviceProfile = "rp5.2"),
            identity.copy(bootIdentity = "boot.2"), identity.copy(generation = 8), identity.copy(requestId = "request.2"),
            identity.copy(transactionId = "transaction.2"), identity.copy(taskNonce = "b".repeat(32)),
            identity.copy(topologySha256 = digest("other-topology")), identity.copy(snapshotSha256 = digest("other-snapshot")),
            identity.copy(hardDeadlineElapsedRealtimeMs = 10_001),
        ).forEach { assertEquals(GuardianArmStoreLoad.Corrupt, store.load(it)) }
        assertEquals(
            GuardianArmStoreLoad.Corrupt,
            GuardianArmRecordCodec.decodeAndVerify(byteArrayOf(0x4d, 0x47), expected(value), pinned),
        )
        val encoded = GuardianArmRecordCodec.encode(signed, pinned)
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertEquals(GuardianArmStoreLoad.Corrupt, GuardianArmRecordCodec.decodeAndVerify(encoded, expected(value), pinned))
        val terminal = GuardianArmRecordCodec.encode(signed, pinned)
        terminal[11] = 2
        assertEquals(GuardianArmStoreLoad.Corrupt, GuardianArmRecordCodec.decodeAndVerify(terminal, expected(value), pinned))
    }

    @Test
    fun `file store uses exact cleanup and never overwrites corrupt or staged truth`() {
        val dir = Files.createTempDirectory("guardian-arm-store-").toFile()
        try {
            val store = FileRestoreGuardianArmStore(available, dir)
            val value = envelope()
            val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
            assertEquals(GuardianArmStoreWrite.Armed, store.arm(signed))
            assertTrue(store.load(expected(value)) is GuardianArmStoreLoad.LoadedStaged)
            assertTrue(store.clear())
            assertEquals(GuardianArmStoreLoad.Empty, store.load(expected(value)))

            File(dir, ".restore-guardian.arm.tmp").writeText("partial")
            assertEquals(GuardianArmStoreLoad.Corrupt, store.load(expected(value)))
            assertEquals(GuardianArmStoreWrite.Rejected, store.arm(signed))
            assertTrue(store.clear())
            assertFalse(File(dir, "restore-guardian.arm").exists())
            assertFalse(File(dir, ".restore-guardian.arm.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `file and memory stores persist an exact staged to ready transition`() {
        val value = envelope()
        val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
        val ready = RestoreGuardianCrypto.signReadyReceipt(receipt(value), sessionSigner)
        val directory = Files.createTempDirectory("guardian-ready-").toFile()
        try {
            listOf<RestoreGuardianArmStore>(InMemoryRestoreGuardianArmStore(available), FileRestoreGuardianArmStore(available, directory)).forEach { store ->
                assertEquals(GuardianArmStoreWrite.Armed, store.arm(signed))
                assertTrue(store.load(expected(value)) is GuardianArmStoreLoad.LoadedStaged)
                assertEquals(GuardianSessionPinWrite.Pinned, store.pinSessionKey(expected(value), sessionKey, ready, 2))
                val loaded = store.load(expected(value)) as GuardianArmStoreLoad.LoadedReady
                assertEquals(sessionKey, loaded.sessionKey)
                assertTrue(loaded.signedReady.receipt.canonicalBytes().contentEquals(ready.receipt.canonicalBytes()))
                assertEquals(GuardianSessionPinWrite.Rejected, store.pinSessionKey(expected(value), sessionKey, ready, 2))
                assertTrue(store.clear())
                assertEquals(GuardianArmStoreLoad.Empty, store.load(expected(value)))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `pin rejections preserve staged bytes and hostile ready records stay corrupt`() {
        val value = envelope()
        val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
        val ready = RestoreGuardianCrypto.signReadyReceipt(receipt(value), sessionSigner)
        val store = InMemoryRestoreGuardianArmStore(available)
        assertEquals(GuardianArmStoreWrite.Armed, store.arm(signed))
        val staged = GuardianArmRecordCodec.encodeStaged(signed, pinned)
        assertEquals(GuardianSessionPinWrite.Rejected, store.pinSessionKey(expected(value).copy(bootIdentity = "other"), sessionKey, ready, 2))
        assertTrue(staged.contentEquals(recordBytes(store, expected(value))))

        val secondKey = GuardianSessionPublicKey.fromPublicKey(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public)
        assertEquals(GuardianSessionPinWrite.Rejected, store.pinSessionKey(expected(value), secondKey, ready, 2))
        val wrongPop = SignedRestoreGuardianReadyReceipt(ready.receipt, ready.signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertEquals(GuardianSessionPinWrite.Rejected, store.pinSessionKey(expected(value), sessionKey, wrongPop, 2))
        assertEquals(GuardianSessionPinWrite.Rejected, store.pinSessionKey(expected(value), sessionKey, ready, 0))
        assertTrue(staged.contentEquals(recordBytes(store, expected(value))))

        val pin = RestoreGuardianCrypto.signSessionPin(
            RestoreGuardianSessionPin(
                envelopeSha256 = value.digestSha256(), sessionKeySha256 = sessionKey.sha256(),
                readyReceiptSha256 = GuardianCanonicalCodec.sha256(ready.receipt.canonicalBytes()),
                readyIssuedElapsedRealtimeMs = 1, pinnedElapsedRealtimeMs = 2,
            ), signer,
        )
        val complete = GuardianArmRecordCodec.encodeReady(signed, pinned, sessionKey, ready, pin)
        listOf(
            complete + 0,
            complete.copyOf().also { it[4] = 1 },
            complete.copyOf().also { it[8] = 99.toByte() },
            complete.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() },
        ).forEach { assertEquals(GuardianArmStoreLoad.Corrupt, GuardianArmRecordCodec.decodeAndVerify(it, expected(value), pinned)) }
    }

    @Test
    fun `caller mutation cannot alter persisted truth and staging residue is corrupt`() {
        val value = envelope()
        val signature = RestoreGuardianCrypto.signEnvelope(value, signer).signature
        val signed = SignedRestoreGuardianEnvelope(value, signature)
        val store = InMemoryRestoreGuardianArmStore(available)
        assertEquals(GuardianArmStoreWrite.Armed, store.arm(signed))
        signature[0] = (signature[0].toInt() xor 1).toByte()
        assertTrue(store.load(expected(value)) is GuardianArmStoreLoad.LoadedStaged)
        val directory = Files.createTempDirectory("guardian-residue-").toFile()
        try {
            val fileStore = FileRestoreGuardianArmStore(available, directory)
            assertEquals(GuardianArmStoreWrite.Armed, fileStore.arm(RestoreGuardianCrypto.signEnvelope(value, signer)))
            File(directory, ".restore-guardian.arm.tmp").writeText("residue")
            assertEquals(GuardianArmStoreLoad.Corrupt, fileStore.load(expected(value)))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `empty and corrupt file pin attempts do not change durable bytes`() {
        val value = envelope()
        val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
        val ready = RestoreGuardianCrypto.signReadyReceipt(receipt(value), sessionSigner)
        val directory = Files.createTempDirectory("guardian-no-overwrite-").toFile()
        try {
            val store = FileRestoreGuardianArmStore(available, directory)
            assertEquals(GuardianSessionPinWrite.Rejected, store.pinSessionKey(expected(value), sessionKey, ready, 2))
            val file = File(directory, "restore-guardian.arm")
            assertFalse(file.exists())
            val corrupt = GuardianArmRecordCodec.encodeStaged(signed, pinned) + 0
            file.writeBytes(corrupt)
            assertEquals(GuardianSessionPinWrite.Rejected, store.pinSessionKey(expected(value), sessionKey, ready, 2))
            assertTrue(corrupt.contentEquals(file.readBytes()))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `bound app signer pins while encoder exception rejects without altering staged bytes`() {
        val value = envelope()
        val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
        val ready = RestoreGuardianCrypto.signReadyReceipt(receipt(value), sessionSigner)
        val memory = InMemoryRestoreGuardianArmStore(available)
        assertEquals(GuardianArmStoreWrite.Armed, memory.arm(signed))
        assertEquals(GuardianSessionPinWrite.Pinned, memory.pinSessionKey(expected(value), sessionKey, ready, 2))
        assertTrue(memory.load(expected(value)) is GuardianArmStoreLoad.LoadedReady)

        val throwingMemory = InMemoryRestoreGuardianArmStore(available, readyRecordEncoder = { _, _, _, _, _ -> error("test encoder failure") })
        assertEquals(GuardianArmStoreWrite.Armed, throwingMemory.arm(signed))
        val memoryBeforeEncoder = recordBytes(throwingMemory, expected(value))
        assertEquals(GuardianSessionPinWrite.Rejected, throwingMemory.pinSessionKey(expected(value), sessionKey, ready, 2))
        assertTrue(memoryBeforeEncoder.contentEquals(recordBytes(throwingMemory, expected(value))))
        assertTrue(throwingMemory.load(expected(value)) is GuardianArmStoreLoad.LoadedStaged)

        val directory = Files.createTempDirectory("guardian-exception-containment-").toFile()
        try {
            val file = File(directory, "restore-guardian.arm")
            val fileStore = FileRestoreGuardianArmStore(available, directory)
            assertEquals(GuardianArmStoreWrite.Armed, fileStore.arm(signed))
            assertEquals(GuardianSessionPinWrite.Pinned, fileStore.pinSessionKey(expected(value), sessionKey, ready, 2))
            assertTrue(fileStore.load(expected(value)) is GuardianArmStoreLoad.LoadedReady)

            assertTrue(fileStore.clear())
            val throwingFile = FileRestoreGuardianArmStore(available, directory, readyRecordEncoder = { _, _, _, _, _ -> error("test encoder failure") })
            assertEquals(GuardianArmStoreWrite.Armed, throwingFile.arm(signed))
            val fileBeforeEncoder = file.readBytes()
            assertEquals(GuardianSessionPinWrite.Rejected, throwingFile.pinSessionKey(expected(value), sessionKey, ready, 2))
            assertTrue(fileBeforeEncoder.contentEquals(file.readBytes()))
            assertTrue(throwingFile.load(expected(value)) is GuardianArmStoreLoad.LoadedStaged)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `atomic move ambiguity reloads only exact ready as pinned`() {
        val value = envelope()
        val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
        val ready = RestoreGuardianCrypto.signReadyReceipt(receipt(value), sessionSigner)
        val directory = Files.createTempDirectory("guardian-ambiguous-").toFile()
        try {
            var moves = 0
            val store = FileRestoreGuardianArmStore(available, directory, atomicMove = { source, destination ->
                Files.move(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                if (++moves > 1) error("result lost after rename")
            })
            assertEquals(GuardianArmStoreWrite.Armed, store.arm(signed))
            assertEquals(GuardianSessionPinWrite.Pinned, store.pinSessionKey(expected(value), sessionKey, ready, 2))
            assertTrue(store.load(expected(value)) is GuardianArmStoreLoad.LoadedReady)

            val unsupportedDir = Files.createTempDirectory("guardian-unsupported-").toFile()
            try {
                var unsupportedMoves = 0
                val unsupported = FileRestoreGuardianArmStore(available, unsupportedDir, atomicMove = { source, destination ->
                    if (++unsupportedMoves == 1) Files.move(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    else throw AtomicMoveNotSupportedException(source.path, destination.path, "test")
                })
                assertEquals(GuardianArmStoreWrite.Armed, unsupported.arm(signed))
                assertEquals(GuardianSessionPinWrite.Rejected, unsupported.pinSessionKey(expected(value), sessionKey, ready, 2))
                assertTrue(unsupported.load(expected(value)) is GuardianArmStoreLoad.LoadedStaged)
            } finally { unsupportedDir.deleteRecursively() }
        } finally { directory.deleteRecursively() }
    }

    private fun recordBytes(store: InMemoryRestoreGuardianArmStore, expected: RestoreGuardianExpectedIdentity): ByteArray {
        val loaded = store.load(expected) as GuardianArmStoreLoad.LoadedStaged
        return GuardianArmRecordCodec.encodeStaged(loaded.signed, pinned)
    }

    private fun envelope() = RestoreGuardianEnvelope(
        helperBuildIdentity = "helper.1",
        tweakerCertificateSha256 = digest("certificate"),
        hardwareFingerprint = "hardware.1",
        deviceProfile = "rp5.1",
        bootIdentity = "boot.1",
        generation = 7,
        requestId = "request.1",
        transactionId = "transaction.1",
        taskNonce = "a".repeat(32),
        topologySha256 = digest("topology"),
        snapshotSha256 = digest("snapshot"),
        planSha256 = digest("plan"),
        hardDeadlineElapsedRealtimeMs = 10_000,
        restoreEntries = listOf(
            RestoreGuardianSysfsEntry(
                applyOrdinal = 3,
                logicalId = "cpu:1:scaling_max_freq",
                canonicalSysfsPath = "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq",
                expectedStock = RestoreGuardianStockState.PresentSysfs(1_200_000, "644", 0, 0),
                rendererAppliedIntermediates = listOf(2_000_000),
            ),
            RestoreGuardianFanPutEntry(2, expectedStock = RestoreGuardianStockState.PresentFanMode(0), rendererAppliedIntermediates = listOf(2)),
        ),
    )

    private fun expected(value: RestoreGuardianEnvelope) = RestoreGuardianExpectedIdentity(
        value.helperBuildIdentity, value.tweakerCertificateSha256, value.hardwareFingerprint, value.deviceProfile,
        value.bootIdentity, value.generation, value.requestId, value.transactionId, value.taskNonce,
        value.topologySha256, value.snapshotSha256, value.hardDeadlineElapsedRealtimeMs,
    )

    private fun receipt(value: RestoreGuardianEnvelope) = RestoreGuardianReadyReceipt(
        helperBuildIdentity = value.helperBuildIdentity, bootIdentity = value.bootIdentity,
        generation = value.generation, requestId = value.requestId, transactionId = value.transactionId,
        taskNonce = value.taskNonce, envelopeSha256 = value.digestSha256(),
        sessionPublicKey = sessionKey, issuedElapsedRealtimeMs = 1,
    )

    private fun digest(value: String) = GuardianCanonicalCodec.sha256(value.toByteArray())

    private fun recordWithEnvelope(signed: SignedRestoreGuardianEnvelope, envelope: ByteArray): ByteArray {
        val original = signed.envelope.canonicalBytes()
        require(envelope.size == original.size) { "test replacement must preserve canonical length" }
        val record = GuardianArmRecordCodec.encodeStaged(signed, pinned)
        val index = record.indexOf(original)
        require(index >= 0) { "canonical envelope absent from v3 record" }
        return record.copyOf().also { envelope.copyInto(it, index) }
    }

    private fun swapAdjacentDelimitedFields(bytes: ByteArray, first: String, second: String): ByteArray {
        val firstField = delimited(first)
        val secondField = delimited(second)
        val firstIndex = bytes.indexOf(firstField)
        require(firstIndex >= 0) { "first field absent" }
        val secondIndex = bytes.indexOf(secondField, firstIndex + firstField.size)
        require(secondIndex == firstIndex + firstField.size) { "fields are not adjacent" }
        return bytes.copyOfRange(0, firstIndex) + secondField + firstField + bytes.copyOfRange(secondIndex + secondField.size, bytes.size)
    }

    private fun delimited(value: String): ByteArray = ByteArrayOutputStream().also { sink ->
        DataOutputStream(sink).use { out -> out.string(value) }
    }.toByteArray()

    private fun ByteArray.indexOf(needle: ByteArray, start: Int = 0): Int {
        for (index in start..size - needle.size) {
            if (copyOfRange(index, index + needle.size).contentEquals(needle)) return index
        }
        return -1
    }

    private fun DataOutputStream.bytes(value: ByteArray) { writeInt(value.size); write(value) }
    private fun DataOutputStream.string(value: String) { bytes(value.toByteArray(Charsets.UTF_8)) }

    private fun fixedPublic(publicDer: String) = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(b64(publicDer)))
    private fun b64(value: String): ByteArray = Base64.getDecoder().decode(value)
}
