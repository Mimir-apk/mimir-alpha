package com.gruning.mimir.tweaker

import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreGuardianSessionKeyTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    @Test fun `SEC1 session key validates round trips signs and defends bytes`() {
        val original = GuardianSessionPublicKey.fromPublicKey(pair.public)
        val sec1 = original.sec1Bytes()
        val reparsed = GuardianSessionPublicKey.fromSec1(sec1)
        assertEquals(original, reparsed)
        assertEquals(original.sha256(), reparsed.sha256())
        sec1[1] = (sec1[1].toInt() xor 1).toByte()
        assertEquals(original, GuardianSessionPublicKey.fromSec1(reparsed.sec1Bytes()))
        val signer: GuardianSessionSigner = EcdsaP256GuardianSessionSigner(pair.private)
        val payload = "receipt".toByteArray()
        assertTrue(reparsed.verifier().verify(payload, signer.sign(payload)))
    }

    @Test fun `malformed SEC1 lengths prefix coordinates and off curve are rejected`() {
        val sec1 = GuardianSessionPublicKey.fromPublicKey(pair.public).sec1Bytes()
        val prime = "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF".hexBytes()
        listOf(
            sec1.copyOf(64), sec1.copyOf().also { it[0] = 0x02 }, ByteArray(65).also { it[0] = 0x04 },
            sec1.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() },
            sec1.copyOf().also { prime.copyInto(it, destinationOffset = 1) },
            sec1.copyOf().also { prime.copyInto(it, destinationOffset = 33) },
        )
            .forEach { assertRejected { GuardianSessionPublicKey.fromSec1(it) } }
    }

    @Test fun `channel evidence requires every exact field and rejects forbidden owner`() {
        val expected = GuardianReadyChannelExpectation(1000, 1000, "600", "u:object_r:guardian:s0", true, setOf(2000))
        val evidence = GuardianReadyChannelEvidence(1000, 1000, "600", "u:object_r:guardian:s0", true)
        assertTrue(GuardianReadyChannelPolicy.matches(evidence, expected))
        assertFalse(GuardianReadyChannelPolicy.matches(evidence.copy(ownerUid = 1001), expected))
        assertFalse(GuardianReadyChannelPolicy.matches(evidence.copy(ownerGid = 1001), expected))
        assertFalse(GuardianReadyChannelPolicy.matches(evidence.copy(modeOctal = "644"), expected))
        assertFalse(GuardianReadyChannelPolicy.matches(evidence.copy(seLinuxLabel = "u:object_r:other:s0"), expected))
        assertFalse(GuardianReadyChannelPolicy.matches(evidence.copy(exactBytesConfirmed = false), expected))
        assertFalse(GuardianReadyChannelPolicy.matches(evidence.copy(ownerUid = 2000), expected.copy(ownerUid = 2000)))
        assertRejected { expected.copy(exactBytesConfirmed = false) }
    }

    @Test fun `channel labels reject every control character before an impossible stat expectation exists`() {
        val controls = listOf('\u0000', '\u0001', '\u001f', '\u007f', '\u0085', '\u009f')
        controls.forEach { control ->
            assertRejected {
                GuardianReadyChannelExpectation(1000, 1000, "600", "u:object_r:guardian${control}s0", true, emptySet())
            }
            assertRejected {
                GuardianReadyChannelEvidence(1000, 1000, "600", "u:object_r:guardian${control}s0", true)
            }
        }
    }

    private fun assertRejected(block: () -> Unit) { try { block(); throw AssertionError("expected rejection") } catch (_: IllegalArgumentException) {} }
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
