package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The ladder's whole value is that N candidates cost ONE read, so every test here pins the single-pass
 * result against an independently-computed digest of the same bytes transformed the obvious way. If the
 * streaming carry logic is wrong these diverge; nothing else in the suite would notice.
 */
class RomDigestLadderTest {

    private fun hex(bytes: ByteArray, algorithm: String = "MD5"): String =
        MessageDigest.getInstance(algorithm).digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** A stream that hands back at most [chunk] bytes per read — forces mid-unit chunk boundaries. */
    private class ChoppyStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at >= data.size) -1 else data[at++].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= data.size) return -1
            val n = minOf(chunk, len, data.size - at)
            System.arraycopy(data, at, b, off, n)
            at += n
            return n
        }
    }

    @Test
    fun `one pass matches independently computed digests for every candidate`() {
        val rom = Random(7).nextBytes(300_000)
        val out = RomDigestLadder.digest(
            ByteArrayInputStream(rom),
            listOf(
                DigestSpec("raw", "MD5"),
                DigestSpec("skip16", "MD5", skipBytes = 16),
                DigestSpec("skip512", "MD5", skipBytes = 512),
                DigestSpec("rawSha1", "SHA-1"),
            ),
        )
        assertEquals(hex(rom), out["raw"])
        assertEquals(hex(rom.copyOfRange(16, rom.size)), out["skip16"])
        assertEquals(hex(rom.copyOfRange(512, rom.size)), out["skip512"])
        assertEquals(hex(rom, "SHA-1"), out["rawSha1"])
    }

    /**
     * The N64 gain, checked against the byte-swapper this repo already ships and already trusts for the
     * DAT lane — so the ladder cannot quietly invent a different byte order than `RomFingerprinter` uses.
     */
    @Test
    fun `swap candidates agree with RomFingerprinter's own byte swapper`() {
        val rom = Random(11).nextBytes(65_536 * 2)
        val out = RomDigestLadder.digest(
            ByteArrayInputStream(rom),
            listOf(
                DigestSpec("z64", "MD5"),
                DigestSpec("v64", "MD5", swapUnit = 2),
                DigestSpec("n64", "MD5", swapUnit = 4),
            ),
        )
        assertEquals(hex(rom), out["z64"])
        assertEquals(hex(RomFingerprinter.byteSwap(rom, 2)), out["v64"])
        assertEquals(hex(RomFingerprinter.byteSwap(rom, 4)), out["n64"])
    }

    /**
     * The trap in this class's own implementation: a chunk boundary landing mid-unit. A 64 KiB buffer
     * divides evenly by 2 and 4, so the carry path is never exercised by ordinary reads — a SAF stream
     * returning short reads is what finds it. Sizes chosen to be coprime-ish with 2 and 4.
     */
    @Test
    fun `swap survives chunk boundaries that fall mid-unit`() {
        val rom = Random(13).nextBytes(9_999 * 4)
        for (chunk in intArrayOf(1, 3, 5, 7, 13, 101, 1023)) {
            val out = RomDigestLadder.digest(
                ChoppyStream(rom, chunk),
                listOf(DigestSpec("v64", "MD5", swapUnit = 2), DigestSpec("n64", "MD5", swapUnit = 4)),
            )
            assertEquals("chunk=$chunk v64", hex(RomFingerprinter.byteSwap(rom, 2)), out["v64"])
            assertEquals("chunk=$chunk n64", hex(RomFingerprinter.byteSwap(rom, 4)), out["n64"])
        }
    }

    @Test
    fun `skip survives chunk boundaries landing inside the skipped header`() {
        val rom = Random(17).nextBytes(20_000)
        for (chunk in intArrayOf(1, 7, 100, 511, 513)) {
            val out = RomDigestLadder.digest(
                ChoppyStream(rom, chunk),
                listOf(DigestSpec("skip512", "MD5", skipBytes = 512)),
            )
            assertEquals("chunk=$chunk", hex(rom.copyOfRange(512, rom.size)), out["skip512"])
        }
    }

    /**
     * A file shorter than the header a candidate would strip has no honest digest under that rule — it
     * is OMITTED, not reported as a digest of nothing. Reporting one would be a confident hash of an
     * empty byte range, which is a real value that would match a real (wrong) catalogue entry.
     */
    @Test
    fun `a candidate whose header exceeds the file is omitted, not empty-digested`() {
        val tiny = Random(19).nextBytes(100)
        val out = RomDigestLadder.digest(
            ByteArrayInputStream(tiny),
            listOf(DigestSpec("raw", "MD5"), DigestSpec("skip512", "MD5", skipBytes = 512)),
        )
        assertEquals(hex(tiny), out["raw"])
        assertNull(out["skip512"])
        assertFalse(out.containsKey("skip512"))
    }

    @Test
    fun `a file exactly the length of the header yields no candidate for it`() {
        val exact = Random(23).nextBytes(512)
        val out = RomDigestLadder.digest(
            ByteArrayInputStream(exact),
            listOf(DigestSpec("skip512", "MD5", skipBytes = 512)),
        )
        assertNull(out["skip512"])
    }

    @Test
    fun `empty spec list reads nothing and yields nothing`() {
        var read = false
        val watcher = object : InputStream() {
            override fun read(): Int { read = true; return -1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read = true; return -1 }
        }
        assertTrue(RomDigestLadder.digest(watcher, emptyList()).isEmpty())
        assertFalse("must not touch the stream when there is nothing to compute", read)
    }

    @Test
    fun `skip combined with swap is rejected at construction`() {
        val failed = runCatching { DigestSpec("bad", "MD5", skipBytes = 16, swapUnit = 2) }.isFailure
        assertTrue("skip+swap must not silently produce a straddled digest", failed)
        assertTrue(runCatching { DigestSpec("bad", "MD5", swapUnit = 3) }.isFailure)
        assertTrue(runCatching { DigestSpec("bad", "MD5", skipBytes = -1) }.isFailure)
    }

    /** A trailing partial unit is digested unswapped rather than reversed short — pinned so it can't drift. */
    @Test
    fun `trailing partial unit is digested unswapped`() {
        val rom = byteArrayOf(1, 2, 3, 4, 5) // 5 bytes: one whole 4-unit plus a 1-byte tail
        val out = RomDigestLadder.digest(
            ByteArrayInputStream(rom),
            listOf(DigestSpec("n64", "MD5", swapUnit = 4)),
        )
        val expected = byteArrayOf(4, 3, 2, 1) + byteArrayOf(5)
        assertEquals(hex(expected), out["n64"])
    }
}
