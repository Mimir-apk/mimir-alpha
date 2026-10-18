package com.gruning.mimir.rewrite

import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hex encoder replaced `"%02x".format(byte)` on a path that addresses every art file on the reference device.
 *
 * A performance change to a FROZEN derivation is only allowed to be a performance change. These tests
 * are the proof of that and nothing else: they compare the new encoder against the exact expression it
 * replaced, and the frozen filenames against hashes computed outside the JVM entirely.
 *
 * Note what the existing `MediaStorageKeyTest` can and cannot see: its assertions are RELATIVE (a Shelf
 * key addresses the same file as the bare key), so they stay green even if the hex encoding changed on
 * both sides of the comparison. Only an absolute pin catches that, which is why the goldens below are
 * literal strings rather than a second call to the same code.
 */
class MimirHexDigestTest {

    @Test
    fun `every byte value encodes exactly as the String_format it replaced`() {
        for (v in 0..255) {
            val b = v.toByte()
            assertEquals(
                "byte $v (as signed: $b) must encode identically",
                "%02x".format(b),
                byteArrayOf(b).toMimirLowerHex(),
            )
        }
    }

    @Test
    fun `random digests encode exactly as the String_format it replaced`() {
        val random = Random(20260904)
        repeat(200) {
            val bytes = random.nextBytes(random.nextInt(1, 64))
            assertEquals(
                bytes.joinToString("") { "%02x".format(it) },
                bytes.toMimirLowerHex(),
            )
        }
    }

    @Test
    fun `an empty digest encodes to an empty string, as it did before`() {
        assertEquals(ByteArray(0).joinToString("") { "%02x".format(it) }, ByteArray(0).toMimirLowerHex())
    }

    @Test
    fun `a real SHA-256 encodes exactly as the String_format it replaced`() {
        listOf("arcade/mslug", "android/com.example.game", "a", "", "üñïçø∂é/ゲーム")
            .forEach { key ->
                val digest = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
                assertEquals(
                    "SHA-256 of ${'$'}key must encode identically",
                    digest.joinToString("") { "%02x".format(it) },
                    digest.toMimirLowerHex(),
                )
            }
    }

    /**
     * The addresses themselves, pinned to values computed OUTSIDE this codebase (python `hashlib`).
     * If this goes red, every cartridge pick on the reference device has just become unreachable, silently.
     */
    @Test
    fun `the frozen cartridge filenames are unmoved`() {
        assertEquals("8ceb744b739e72498bea4012.png",
            tomeCartridgeArtStorageName("3f2b1c9e-0a44-4e17-9a2f-77c1d0e5b8aa"))
        assertEquals("564f27b81bbcb252fba18ebf.png",
            tomeCartridgeArtStorageName("android/com.example.game"))
        assertEquals("cab36f7517776fb01d80c821.png",
            tomeCartridgeArtStorageName("arcade/mslug"))
    }
}
