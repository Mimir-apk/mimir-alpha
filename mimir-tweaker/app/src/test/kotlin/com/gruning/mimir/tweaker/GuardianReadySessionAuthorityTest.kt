package com.gruning.mimir.tweaker

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianReadySessionAuthorityTest {
    @Test fun `generated authority signs READY and only its public key verifies`() {
        val authority = requireNotNull(GuardianReadySessionAuthority.generate(::generator))
        val payload = byteArrayOf(9, 8, 7)
        val signature = requireNotNull(authority.authenticate(GuardianReceiptType.READY, payload))
        val ownKey = requireNotNull(authority.sessionPublicKey)
        val foreign = requireNotNull(GuardianReadySessionAuthority.generate(::generator))

        assertTrue(ownKey.verifier().verify(payload, signature))
        assertFalse(requireNotNull(foreign.sessionPublicKey).verifier().verify(payload, signature))
        authority.close()
        foreign.close()
    }

    @Test fun `READY authority rejects terminal receipt types and payload bounds`() {
        val authority = requireNotNull(GuardianReadySessionAuthority.generate(::generator))
        assertNull(authority.authenticate(GuardianReceiptType.STOCK_CONFIRMED, byteArrayOf(1)))
        assertNull(authority.authenticate(GuardianReceiptType.EXITED, byteArrayOf(1)))
        assertNull(authority.authenticate(GuardianReceiptType.READY, ByteArray(0)))
        assertNull(authority.authenticate(GuardianReceiptType.READY, ByteArray(GuardianArmRecordCodec.MAX_READY + 1)))
        authority.close()
    }

    @Test fun `authority public key is defensive and differs from app test key`() {
        val authority = requireNotNull(GuardianReadySessionAuthority.generate(::generator))
        val first = requireNotNull(authority.sessionPublicKey)
        val bytes = first.sec1Bytes()
        bytes[1] = (bytes[1].toInt() xor 1).toByte()
        assertEquals(first, requireNotNull(authority.sessionPublicKey))

        val appPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val appKey = GuardianSessionPublicKey.fromPublicKey(appPair.public)
        assertNotEquals(appKey, first)
        authority.close()
    }

    @Test fun `authority signs canonical READY that round trips through the frozen wire codec`() {
        val authority = requireNotNull(GuardianReadySessionAuthority.generate(::generator))
        val key = requireNotNull(authority.sessionPublicKey)
        val canonical = readyFor(key).canonicalBytes()
        val signature = requireNotNull(authority.authenticate(GuardianReceiptType.READY, canonical))
        val decoded = requireNotNull(GuardianReadyWireCodec.decode(requireNotNull(GuardianReadyWireCodec.encode(canonical, signature))))

        assertEquals(key, decoded.receipt.sessionPublicKey)
        assertTrue(decoded.receipt.sessionPublicKey.verifier().verify(decoded.receipt.canonicalBytes(), decoded.signature))
        authority.close()
    }

    @Test fun `generation is total for throwing RSA Ed25519 and non P256 factories`() {
        assertNull(GuardianReadySessionAuthority.generate { error("factory failure") })
        assertNull(GuardianReadySessionAuthority.generate { KeyPairGenerator.getInstance("RSA") })
        assertNull(GuardianReadySessionAuthority.generate { KeyPairGenerator.getInstance("Ed25519") })
        val nonP256Pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()
        assertNull(GuardianReadySessionAuthority.generate { FixedPairGenerator(nonP256Pair) })
    }

    @Test fun `close is idempotent and permanently fails closed`() {
        val authority = requireNotNull(GuardianReadySessionAuthority.generate(::generator))
        val publicBeforeClose = authority.sessionPublicKey
        authority.close()
        authority.close()
        assertEquals(publicBeforeClose, authority.sessionPublicKey)
        assertNull(authority.authenticate(GuardianReceiptType.READY, byteArrayOf(1)))
    }

    @Test fun `main production source has no authority construction site`() {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val callers = root.listFiles().orEmpty()
            .filter { it.name != "GuardianReadySessionAuthority.kt" && it.extension == "kt" }
            .filter { it.readText().contains("GuardianReadySessionAuthority.generate(") }
        assertTrue("unexpected authority construction: $callers", callers.isEmpty())
    }

    private fun readyFor(key: GuardianSessionPublicKey) = RestoreGuardianReadyReceipt(
        helperBuildIdentity = "helper",
        bootIdentity = "boot",
        generation = 1,
        requestId = "request",
        transactionId = "transaction",
        taskNonce = "0123456789abcdef0123456789abcdef",
        envelopeSha256 = "a".repeat(64),
        sessionPublicKey = key,
        issuedElapsedRealtimeMs = 1,
    )

    private fun generator(): KeyPairGenerator = KeyPairGenerator.getInstance("EC")

    /** Deliberately ignores the authority's requested curve to prove post-generation P-256 validation. */
    private class FixedPairGenerator(private val pair: KeyPair) : KeyPairGenerator("test") {
        override fun initialize(keysize: Int, random: SecureRandom) = Unit
        override fun initialize(params: AlgorithmParameterSpec, random: SecureRandom) = Unit
        override fun generateKeyPair(): KeyPair = pair
    }
}
