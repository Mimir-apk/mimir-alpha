package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.DigestSpec
import com.gruning.mimir.rewrite.core.metadata.RomDigestLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The regression lock for RA's per-console hash rules — the "RAHasher fixtures" residue.
 *
 * ## Why these constants are trustworthy, and what they are NOT
 *
 * Every expected digest below was computed by an **independent implementation** (python `hashlib`) over
 * the body bytes alone, then frozen here as a literal. Nothing in this file asks the class under test
 * what the answer is, so a rule that drifts — a skip count changed, a magic mis-tested, a size rule
 * inverted — fails loudly instead of re-baselining itself. That is the whole point: the failure mode
 * these rules have is **silent**. A wrong header skip still produces a perfectly well-formed MD5; it
 * simply stops matching anything, and "no achievements" is indistinguishable from "no achievements yet".
 *
 * Every body length is deliberately **distinct**, so no two fixtures share an expected digest. An earlier
 * draft reused 4096 bytes across cases and three vectors collided — a hasher that ignored headers
 * entirely would have passed. Distinct lengths mean each literal can only be reached by stripping
 * exactly the right number of bytes.
 *
 * **What this does NOT lock:** that Mimir's rules agree with RetroAchievements' *live* rules. That claim
 * can only come from the index — a hit there is definitional truth — and the index needs network and the
 * user's credentials, neither of which belongs in a unit test. This file locks the rules against drift;
 * conformance to RA is established by counting real-file hits against the published index, measured separately rather than asserted here.
 */
class RetroAchievementsHashFixtureTest {

    /** The same deterministic body the vector generator used: `b[i] = (i*31 + 17) & 0xFF`. */
    private fun body(n: Int) = ByteArray(n) { ((it * 31 + 17) and 0xFF).toByte() }

    private fun hash(platform: String, name: String, data: ByteArray): String? =
        RetroAchievementsHash.hash(platform, name, data.size.toLong(), ByteArrayInputStream(data))

    @Test
    fun `NES strips the 16-byte iNES header only when the magic is present`() {
        assertEquals(
            "1d2e0dc0f40c00c8961b1e4ce7bfa58b",
            hash("nes", "g.nes", "NES".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x1A) + ByteArray(12) + body(4096)),
        )
        // No magic: the 16 bytes are ROM, not header, and must be hashed.
        assertEquals("cc3c7a8e35a056317073de135d676b44", hash("nes", "g.nes", body(5000)))
    }

    @Test
    fun `SNES strips a copier header only when the size says one is there`() {
        // 512 + 3*8192 => size % 8192 == 512, the copier signature.
        assertEquals(
            "6b548b5de7c0c7560a6d77d8972626c4",
            hash("snes", "g.sfc", ByteArray(512) + body(8192 * 3)),
        )
        // A clean multiple of 8 KiB carries no header; hashing must not eat 512 real ROM bytes.
        assertEquals("ef170bbdfbee8d081312413336962f9e", hash("snes", "g.sfc", body(8192 * 2)))
    }

    @Test
    fun `Lynx and 7800 strip their own header lengths on their own magic`() {
        assertEquals(
            "c9d0f090e49870aba4f53a4b1e5ad7d2",
            hash("atarilynx", "g.lnx", "LYNX".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + ByteArray(59) + body(2048)),
        )
        assertEquals(
            "2974b86dabfde22837d80d0ee235d2f7",
            hash("atari7800", "g.a78", byteArrayOf(0x01) + "ATARI7800".toByteArray(Charsets.US_ASCII) + ByteArray(118) + body(3072)),
        )
    }

    @Test
    fun `PC Engine strips the 512-byte header when the current rcheevos size bit is set`() {
        assertEquals(
            "8b94ca5e71dc258ea33d31d73dd075f5",
            hash("pcengine", "g.pce", ByteArray(512) + body(131072)),
        )
    }

    @Test
    fun `a plain system hashes the whole file untouched`() {
        assertEquals("95ac0f6fcc0b651e36ab20c9a1fc8242", hash("gba", "g.gba", body(6000)))
    }

    /** Current rcheevos normalizes v64 and n64 byte order to the canonical big-endian z64 digest. */
    @Test
    fun `N64 hashes all three byte orders to one canonical digest`() {
        val z64 = body(4096 * 2).apply {
            this[0] = 0x80.toByte()
            this[1] = 0x37
            this[2] = 0x12
            this[3] = 0x40
        }
        val expected = "e501db935d7077bfa7fdf71f691a0e34"
        assertEquals(expected, hash("n64", "g.z64", z64))
        assertEquals(expected, hash("n64", "g.v64", swap(z64, 2)))
        assertEquals(expected, hash("n64", "g.n64", swap(z64, 4)))
    }

    /**
     * ...and the reason that null is worth closing: a candidate ladder reaches the SAME z64 digest from
     * all three byte orders in one read, so the coverage is available without asserting anything about
     * which order a file is in — the index adjudicates. This is the "N64 comes free" claim, pinned as an
     * arithmetic fact rather than left as an argument.
     */
    @Test
    fun `a candidate ladder reaches the z64 digest from all three byte orders`() {
        val z64 = body(4096 * 2).apply {
            this[0] = 0x80.toByte()
            this[1] = 0x37
            this[2] = 0x12
            this[3] = 0x40
        }
        val target = "e501db935d7077bfa7fdf71f691a0e34"
        val specs = listOf(
            DigestSpec("z64", "MD5"),
            DigestSpec("v64", "MD5", swapUnit = 2),
            DigestSpec("n64", "MD5", swapUnit = 4),
        )
        // Whichever order the file is actually in, one candidate lands on the catalogued digest.
        assertEquals(target, RomDigestLadder.digest(ByteArrayInputStream(z64), specs)["z64"])
        assertEquals(target, RomDigestLadder.digest(ByteArrayInputStream(swap(z64, 2)), specs)["v64"])
        assertEquals(target, RomDigestLadder.digest(ByteArrayInputStream(swap(z64, 4)), specs)["n64"])

        // And the raw digests of the swapped files are NOT the target — i.e. the swap is doing real work
        // and this test would not pass if every candidate were secretly the identity transform.
        val rawOfV64 = RomDigestLadder.digest(ByteArrayInputStream(swap(z64, 2)), listOf(DigestSpec("raw", "MD5")))
        assertNotEquals(target, rawOfV64["raw"])
    }

    private fun swap(bytes: ByteArray, unit: Int): ByteArray {
        val out = bytes.copyOf()
        var i = 0
        while (i + unit <= out.size) {
            for (k in 0 until unit / 2) {
                val t = out[i + k]; out[i + k] = out[i + unit - 1 - k]; out[i + unit - 1 - k] = t
            }
            i += unit
        }
        return out
    }
}
