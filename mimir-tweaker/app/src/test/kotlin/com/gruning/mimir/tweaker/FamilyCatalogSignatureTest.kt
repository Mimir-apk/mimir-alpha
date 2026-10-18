package com.gruning.mimir.tweaker

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyCatalogSignatureTest {
    @Test
    fun `RFC 8032 test vectors verify`() {
        // RFC 8032 section 7.1, TEST 1 (empty message) and TEST 2 (one byte).
        assertTrue(
            FamilyCatalogSignature.verify(
                hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
                ByteArray(0),
                hex(
                    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
                ),
            ),
        )
        assertTrue(
            FamilyCatalogSignature.verify(
                hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"),
                hex("72"),
                hex(
                    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
                ),
            ),
        )
    }

    @Test
    fun `a runtime generated signature verifies and any flipped byte fails`() {
        val pair = keyPair()
        val key = rawPublicKey(pair)
        val message = "{\"schema\":1,\"revision\":7,\"families\":[]}".toByteArray()
        val signature = sign(pair, message)

        assertTrue(FamilyCatalogSignature.verify(key, message, signature))

        for (index in message.indices) {
            val flipped = message.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertFalse("message byte $index", FamilyCatalogSignature.verify(key, flipped, signature))
        }
        for (index in signature.indices) {
            val flipped = signature.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertFalse("signature byte $index", FamilyCatalogSignature.verify(key, message, flipped))
        }
        for (index in key.indices) {
            val flipped = key.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertFalse("key byte $index", FamilyCatalogSignature.verify(flipped, message, signature))
        }
        assertFalse(FamilyCatalogSignature.verify(key, message + 0x0a.toByte(), signature))
        assertFalse(FamilyCatalogSignature.verify(rawPublicKey(keyPair()), message, signature))
    }

    @Test
    fun `wrong length key or signature fails closed without throwing`() {
        val pair = keyPair()
        val key = rawPublicKey(pair)
        val message = ByteArray(40) { it.toByte() }
        val signature = sign(pair, message)

        for (size in listOf(0, 1, 31, 33, 64)) {
            assertFalse("key size $size", FamilyCatalogSignature.verify(ByteArray(size) { key[it % key.size] }, message, signature))
        }
        for (size in listOf(0, 1, 63, 65, 128)) {
            assertFalse("signature size $size", FamilyCatalogSignature.verify(key, message, ByteArray(size) { signature[it % signature.size] }))
        }
        assertFalse(FamilyCatalogSignature.verify(ByteArray(32), message, ByteArray(64)))
    }

    @Test
    fun `a small order public key is never a trust anchor`() {
        // With the all-zero key and an all-zero signature, Tink's raw verifier accepts a quarter of all messages
        // (the classic small-order forgery); this message happens to be one. Our wrapper must not.
        val message = ByteArray(40) { it.toByte() }
        assertTrue(
            runCatching { com.google.crypto.tink.subtle.Ed25519Verify(ByteArray(32)).verify(ByteArray(64), message) }.isSuccess,
        )
        assertFalse(FamilyCatalogSignature.verify(ByteArray(32), message, ByteArray(64)))

        val p = "ff".repeat(31) + "7f"
        val yValues = listOf(
            "00".repeat(32),
            "01" + "00".repeat(31),
            "ec" + p.substring(2),
            "ed" + p.substring(2),
            "ee" + p.substring(2),
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
        )
        for (yHex in yValues) {
            val y = hex(yHex)
            assertEquals(32, y.size)
            for (signBit in listOf(0x00, 0x80)) {
                val key = y.copyOf().also { it[31] = (it[31].toInt() or signBit).toByte() }
                assertTrue("$yHex/$signBit", FamilyCatalogSignature.isSmallOrderPoint(key))
                assertNull(FamilyCatalogSignature.decodePublicKey(b64(key)))
                assertFalse(FamilyCatalogSignature.verify(key, message, ByteArray(64)))
            }
        }
        // A real key is not small order.
        assertFalse(FamilyCatalogSignature.isSmallOrderPoint(rawPublicKey(keyPair())))
        assertFalse(
            FamilyCatalogSignature.isSmallOrderPoint(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")),
        )
    }

    @Test
    fun `public key decoding accepts exactly 32 base64 bytes`() {
        val key = rawPublicKey(keyPair())
        assertArrayEquals(key, FamilyCatalogSignature.decodePublicKey(b64(key)))
        assertArrayEquals(key, FamilyCatalogSignature.decodePublicKey("  ${b64(key)}\n"))

        assertNull(FamilyCatalogSignature.decodePublicKey(""))
        assertNull(FamilyCatalogSignature.decodePublicKey("   "))
        assertNull(FamilyCatalogSignature.decodePublicKey(b64(key.copyOf(31))))
        assertNull(FamilyCatalogSignature.decodePublicKey(b64(key + 0x00.toByte())))
        assertNull(FamilyCatalogSignature.decodePublicKey("not base64 at all!"))
        assertNull(FamilyCatalogSignature.decodePublicKey(b64(key).replace('+', '-').replace('/', '_') + "?"))
    }

    @Test
    fun `signature file decoding takes one line of base64 for 64 bytes`() {
        val signature = sign(keyPair(), "x".toByteArray())
        val line = b64(signature)

        assertArrayEquals(signature, FamilyCatalogSignature.decodeSignatureFile(line.toByteArray()))
        assertArrayEquals(signature, FamilyCatalogSignature.decodeSignatureFile("$line\n".toByteArray()))
        assertArrayEquals(signature, FamilyCatalogSignature.decodeSignatureFile("$line\r\n".toByteArray()))

        assertNull(FamilyCatalogSignature.decodeSignatureFile(ByteArray(0)))
        assertNull(FamilyCatalogSignature.decodeSignatureFile("\n".toByteArray()))
        assertNull(FamilyCatalogSignature.decodeSignatureFile(b64(signature.copyOf(63)).toByteArray()))
        assertNull(FamilyCatalogSignature.decodeSignatureFile(b64(signature + 0x00.toByte()).toByteArray()))
        assertNull(FamilyCatalogSignature.decodeSignatureFile("${line.take(40)} ${line.drop(40)}".toByteArray()))
        assertNull(FamilyCatalogSignature.decodeSignatureFile((line + "\n" + line).toByteArray()))
        assertNull(FamilyCatalogSignature.decodeSignatureFile(ByteArray(FamilyCatalogSignature.MAX_SIGNATURE_FILE_BYTES + 1) { 'A'.code.toByte() }))
        assertNull(FamilyCatalogSignature.decodeSignatureFile(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x41, 0x41)))
        // The raw signature bytes themselves (not base64) are not a signature file.
        assertNull(FamilyCatalogSignature.decodeSignatureFile(signature))
    }

    @Test
    fun `end to end a generated key signature file and catalog bytes verify`() {
        val pair = keyPair()
        val catalog = ByteArray(2000) { (it * 31).toByte() }
        val sigFile = (b64(sign(pair, catalog)) + "\n").toByteArray()

        val key = FamilyCatalogSignature.decodePublicKey(b64(rawPublicKey(pair)))
        val signature = FamilyCatalogSignature.decodeSignatureFile(sigFile)
        assertNotNull(key)
        assertNotNull(signature)
        assertEquals(true, FamilyCatalogSignature.verify(key!!, catalog, signature!!))
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun rawPublicKey(pair: KeyPair): ByteArray = checkNotNull(ed25519RawPublicKey(pair.public.encoded))

    private fun sign(pair: KeyPair, message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update(message)
        sign()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
