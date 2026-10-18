package com.gruning.mimir.rewrite.core.metadata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomFingerprinterTest {

    /** [fileName] defaults to a plainly-non-zip name so every pre-existing (loose-file) test below keeps
     *  exercising exactly the path it did before [RomFingerprinter] learned to look inside a zip. */
    private fun fp(platform: String, bytes: ByteArray, fileName: String = "test.rom") =
        RomFingerprinter.fingerprint(platform, fileName, ByteArrayInputStream(bytes))

    private fun whole(bytes: ByteArray) = FileHasher.hash(ByteArrayInputStream(bytes))

    // ── iNES header strip ────────────────────────────────────────────────────
    private val inesHeader = ByteArray(16).also {
        it[0] = 0x4E; it[1] = 0x45; it[2] = 0x53; it[3] = 0x1A // "NES\x1a"
        it[4] = 8; it[5] = 0 // 8x16KB PRG, 0 CHR — arbitrary, part of the non-canonical header
    }
    private val romBody = ByteArray(4096) { (it % 251).toByte() }

    @Test
    fun `nes strips the 16-byte iNES header and hashes the ROM body`() {
        val file = inesHeader + romBody
        val got = fp("nes", file)!!
        val expected = whole(romBody) // No-Intro Headerless = hash of the body only
        assertEquals(expected.crc32, got.crc32)
        assertEquals(expected.sha1, got.sha1)
        assertEquals(4096L, got.sizeBytes)
    }

    @Test
    fun `a non-nes platform does NOT strip the header (whole-file)`() {
        val file = inesHeader + romBody
        val got = fp("gb", file)!! // gb never strips
        assertEquals(whole(file).crc32, got.crc32)
        assertNotEquals(whole(romBody).crc32, got.crc32)
    }

    @Test
    fun `nes without the iNES magic is hashed whole (no false strip)`() {
        val headerless = romBody // no NES magic
        val got = fp("nes", headerless)!!
        assertEquals(whole(headerless).crc32, got.crc32)
    }

    // ── CHD header SHA-1 candidate (v5) ───────────────────────────────────────
    private fun chdV5(sha1: ByteArray, logicalBytes: Long = 0x0011223344556677L): ByteArray {
        val h = ByteArray(124)
        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(h, 0)
        // header length @8 = 124, version @12 = 5 (big-endian)
        beU32(h, 8, 124); beU32(h, 12, 5)
        beU64(h, 32, logicalBytes) // logicalbytes @32 (v5)
        beU32(h, 56, 4096)
        beU32(h, 60, 2048)
        sha1.copyInto(h, 64) // declared raw payload sha1 — still provisional until libchdr recomputes it
        sha1.copyInto(h, 84) // combined sha1 @84 (v5) — what MAME <disk sha1> holds
        return h
    }

    @Test
    fun `chd v5 returns a provisional header claim, not a body hash`() {
        val sha1 = ByteArray(20) { (it + 1).toByte() } // 01 02 .. 14
        val got = fp("dreamcast", chdV5(sha1))!!
        assertEquals("0102030405060708090a0b0c0d0e0f1011121314", got.sha1)
        assertEquals(null, got.crc32) // disks carry no crc — sha1 is the identity
        assertEquals(0x0011223344556677L, got.sizeBytes)
        val evidence = RomFingerprinter.fingerprintWithProvenance(
            "dreamcast",
            "game.chd",
            ByteArrayInputStream(chdV5(sha1)),
        )!!
        assertEquals(RomFingerprintProof.CHD_HEADER_CLAIM, evidence.proof)
    }

    @Test
    fun `chd is detected regardless of platform folder`() {
        val sha1 = ByteArray(20) { 0x2A } // all 0x2a
        val got = fp("ps1", chdV5(sha1))!!
        assertEquals("2a".repeat(20), got.sha1)
    }

    // ── N64 byte-order normalization ─────────────────────────────────────────
    private val z64 = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + ByteArray(1020) { (it % 251).toByte() }

    @Test
    fun `n64 v64 (byteswapped) normalizes to z64 before hashing`() {
        val v64 = RomFingerprinter.byteSwap(z64, 2) // magic becomes 37 80 40 12
        assertEquals(whole(z64).crc32, fp("n64", v64)!!.crc32)
        assertEquals(whole(z64).sha1, fp("n64", v64)!!.sha1)
    }

    @Test
    fun `n64 little-endian normalizes to z64 before hashing`() {
        val n64 = RomFingerprinter.byteSwap(z64, 4) // magic becomes 40 12 37 80
        assertEquals(whole(z64).crc32, fp("n64", n64)!!.crc32)
    }

    @Test
    fun `n64 z64 (native big-endian) is hashed as-is`() {
        assertEquals(whole(z64).crc32, fp("n64", z64)!!.crc32)
        val evidence = RomFingerprinter.fingerprintWithProvenance("n64", "game.n64", ByteArrayInputStream(z64))!!
        assertEquals("untouched native z64 is byte-exact evidence", whole(z64).sha1, evidence.exactRawSha1)
    }

    // ── Genesis .smd deinterleave ────────────────────────────────────────────
    @Test
    fun `genesis smd deinterleaves to the linear bin before hashing`() {
        val bin = ByteArray(16384) { (it * 7 % 253).toByte() }
        val smd = binToSmd(bin)
        assertEquals(whole(bin).crc32, fp("megadrive", smd)!!.crc32)
        assertEquals(whole(bin).sha1, fp("megadrive", smd)!!.sha1)
        // a headerless .bin (no SMD magic) is hashed as-is
        assertEquals(whole(bin).crc32, fp("megadrive", bin)!!.crc32)
    }

    @Test
    fun `only untouched whole-file passes expose an exact raw sha1`() {
        val plain = RomFingerprinter.fingerprintWithProvenance("gb", "game.gb", ByteArrayInputStream(romBody))!!
        val headeredNes = RomFingerprinter.fingerprintWithProvenance(
            "nes",
            "game.nes",
            ByteArrayInputStream(inesHeader + romBody),
        )!!
        val swappedN64 = RomFingerprinter.fingerprintWithProvenance(
            "n64",
            "game.v64",
            ByteArrayInputStream(RomFingerprinter.byteSwap(z64, 2)),
        )!!
        val chd = RomFingerprinter.fingerprintWithProvenance(
            "dreamcast",
            "game.chd",
            ByteArrayInputStream(chdV5(ByteArray(20) { 1 })),
        )!!

        assertEquals(whole(romBody).sha1, plain.exactRawSha1)
        assertEquals(null, headeredNes.exactRawSha1)
        assertEquals(null, swappedN64.exactRawSha1)
        assertEquals(null, chd.exactRawSha1)
    }

    // ── Zipped ROMs (the DAT path reads single-ROM ZIP entries) ────────────────────────
    //
    // Mirrors the already-measured walk in RetroAchievementsHash.kt (2b08176: 2,067 real archives, 778/778
    // byte-exact extraction, 2 honest refusals), but exercised against THIS fingerprinter's own dispatch —
    // which is content-sniffed (magic bytes), not extension/size-derived like RA's copier-header rule. Every
    // fixture below is built with a plain ZipOutputStream and deliberately does NOT pre-declare entry
    // size/crc for a DEFLATED entry, so `ZipEntry.size` reads -1 on readback as streaming ZIP headers permit — see the sanity-check test, which pins that this suite is really exercising that
    // shape and not a fixture-only shortcut that would rot silently.

    /** Builds a zip's raw bytes from (entryName, data) pairs. [method] defaults to DEFLATED (the common
     *  real-world case) and — critically — does NOT call `entry.setSize`/`setCrc`, so [ZipOutputStream]
     *  defers both to a trailing data descriptor. Pass [ZipEntry.STORED] only when a test needs the entry's
     *  raw bytes to appear verbatim in the output (e.g. to locate-and-corrupt them). */
    private fun zipOf(vararg entries: Pair<String, ByteArray>, method: Int = ZipEntry.DEFLATED): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                if (method == ZipEntry.STORED) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = crc32Of(data)
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A zero-byte DIRECTORY entry (name ending "/") ahead of one real rom entry — the shape that recovers
     *  Star Fox 2 on the sampled card: a directory entry must never count as a second ROM candidate. */
    private fun zipWithDirEntry(dirName: String, romName: String, rom: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(if (dirName.endsWith("/")) dirName else "$dirName/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(romName))
            zip.write(rom)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun crc32Of(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /** Byte-array substring search via a lossless 1:1 char mapping (ISO-8859-1) — used only to locate a
     *  STORED entry's verbatim bytes inside the archive so a corruption test can flip exactly one of them. */
    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int =
        String(haystack, Charsets.ISO_8859_1).indexOf(String(needle, Charsets.ISO_8859_1))

    @Test
    fun `the zip fixture helper defers entry size to a data descriptor (sanity check on the gotcha)`() {
        val zip = ZipInputStream(ByteArrayInputStream(zipOf("game.gba" to ByteArray(100))))
        val entry = zip.nextEntry!!
        // Every fixture in this file is built the same way, so every "zipped == loose" assertion below is
        // proving itself against the -1-size shape — not a fixture that happens to carry a
        // known size some other, unmeasured way. Real archives on the sampled card never do this (0/2067),
        // but this fingerprinter never reads ZipEntry.size at all, so it doesn't matter either way.
        assertEquals(-1L, entry.size)
    }

    @Test
    fun `a zipped GBA rom fingerprints identically to the loose file`() {
        val rom = ByteArray(8192) { (it * 3 % 251).toByte() }
        val loose = fp("gba", rom, fileName = "game.gba")!!
        val zipped = fp("gba", zipOf("game.gba" to rom), fileName = "game.zip")!!
        assertEquals(loose.crc32, zipped.crc32)
        assertEquals(loose.sha1, zipped.sha1)
        assertEquals(loose.sizeBytes, zipped.sizeBytes)
    }

    @Test
    fun `every newly catalogued cartridge platform reaches the same hash from a zip`() {
        val extensions = listOf(
            "mastersystem" to "sms",
            "gamegear" to "gg",
            "sg1000" to "sg",
            "atari2600" to "a26",
            "atari2600" to "bin",
            "atari7800" to "a78",
            "atarilynx" to "lnx",
            "pcengine" to "pce",
            "ngp" to "ngp",
            "ngpc" to "ngc",
            "ngpc" to "ngpc",
            "virtualboy" to "vb",
            "wonderswan" to "ws",
            "wonderswancolor" to "wsc",
            "pokemini" to "min",
        )
        val rom = ByteArray(4096) { (it * 17 % 251).toByte() }

        for ((platform, extension) in extensions) {
            val loose = fp(platform, rom, fileName = "game.$extension")!!
            val zipped = fp(platform, zipOf("game.$extension" to rom), fileName = "game.zip")!!
            assertEquals("$platform .$extension crc", loose.crc32, zipped.crc32)
            assertEquals("$platform .$extension sha1", loose.sha1, zipped.sha1)
            assertEquals("$platform .$extension size", loose.sizeBytes, zipped.sizeBytes)
        }

        assertNull(fp("mastersystem", zipOf("game.rom" to rom), fileName = "game.zip"))
    }

    @Test
    fun `DAT fingerprint retains A78 and LNX headers to match the selected No-Intro variants`() {
        val body = ByteArray(2048) { (it * 19 % 253).toByte() }
        val a78Header = ByteArray(128).also {
            (byteArrayOf(0x01) + "ATARI7800".toByteArray(Charsets.US_ASCII)).copyInto(it)
        }
        val lnxHeader = ByteArray(64).also {
            ("LYNX".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)).copyInto(it)
        }

        val a78 = fp("atari7800", a78Header + body, fileName = "game.a78")!!
        val lnx = fp("atarilynx", lnxHeader + body, fileName = "game.lnx")!!
        assertEquals(whole(a78Header + body).sha1, a78.sha1)
        assertEquals(whole(lnxHeader + body).sha1, lnx.sha1)
        assertNotEquals(whole(body).sha1, a78.sha1)
        assertNotEquals(whole(body).sha1, lnx.sha1)
    }

    @Test
    fun `snes zip entry selection recognizes smc and swc, not just sfc`() {
        // The brief's own measured gotcha: an extension table with only .sfc silently misses 133 of the
        // sampled SNES zips (13%), because copier-format dumps ship as .smc/.swc (and occasionally .fig).
        val rom = ByteArray(1024) { (it % 211).toByte() }
        for (ext in listOf("sfc", "smc", "swc", "fig")) {
            val zipped = zipOf("game.$ext" to rom)
            val got = RomFingerprinter.fingerprint("snes", "game.zip", ByteArrayInputStream(zipped))
            assertEquals(".$ext must be recognised as the single SNES rom entry", whole(rom).sha1, got?.sha1)
        }
    }

    @Test
    fun `a zipped v64 (byteswapped) still normalizes to z64 before hashing, using the ENTRY's name`() {
        // The container is literally "Mario (Europe).zip" — RomFingerprinter has no "zip" format case, so
        // if selection somehow consulted the CONTAINER's extension it would never find a candidate at all.
        // The entry's own ".v64" is what selects it; once selected, the existing byte-order magic-sniff
        // (content, not name) drives the actual normalization — the same dispatch a loose .v64 gets.
        val v64 = RomFingerprinter.byteSwap(z64, 2)
        val zipped = zipOf("Mario (Europe).v64" to v64)
        val got = RomFingerprinter.fingerprint("n64", "Mario (Europe).zip", ByteArrayInputStream(zipped))!!
        assertEquals(whole(z64).crc32, got.crc32)
        assertEquals(whole(z64).sha1, got.sha1)
    }

    @Test
    fun `a zip with two rom-extension entries refuses rather than guessing`() {
        val zipped = zipOf("a.gba" to ByteArray(16) { 1 }, "b.gba" to ByteArray(16) { 2 })
        assertNull(RomFingerprinter.fingerprint("gba", "bundle.zip", ByteArrayInputStream(zipped)))
    }

    @Test
    fun `a rom-extension entry alongside an unrelated sidecar file is not refused as multi-entry`() {
        // Scene/set zips commonly carry a readme/nfo alongside the rom; only ROM-extension entries count
        // toward "how many candidates", so a sidecar must not trigger a false refusal.
        val rom = ByteArray(2048) { (it % 199).toByte() }
        val zipped = zipOf("game.gba" to rom, "readme.txt" to "hello".toByteArray())
        val got = RomFingerprinter.fingerprint("gba", "game.zip", ByteArrayInputStream(zipped))
        assertEquals(whole(rom).sha1, got?.sha1)
    }

    @Test
    fun `a directory entry does not count as a second candidate`() {
        val rom = ByteArray(4096) { (it % 200).toByte() }
        val zipped = zipWithDirEntry(dirName = "extras", romName = "game.gba", rom = rom)
        val got = RomFingerprinter.fingerprint("gba", "game.zip", ByteArrayInputStream(zipped))
        assertEquals(whole(rom).sha1, got?.sha1)
    }

    @Test
    fun `a platform with no zip-entry table entry refuses even a well-formed single-rom zip`() {
        // ps1 is CHD-only in this lane (see ROM_EXTENSIONS's KDoc) — no measured evidence real discs
        // double-wrap a .chd in a .zip, so it is deliberately absent from the table and must fail closed.
        val zipped = zipOf("game.bin" to ByteArray(64))
        assertNull(RomFingerprinter.fingerprint("ps1", "game.zip", ByteArrayInputStream(zipped)))
    }

    @Test
    fun `a corrupted zip entry returns null instead of throwing`() {
        val rom = ByteArray(4096) { (it * 13 % 197).toByte() }
        // STORED so the rom's bytes appear verbatim in the archive and can be located deterministically.
        val zipped = zipOf("game.gba" to rom, method = ZipEntry.STORED)
        val needle = rom.copyOfRange(0, 32)
        val at = indexOfBytes(zipped, needle)
        check(at >= 0) { "test fixture bug: could not locate the stored rom bytes to corrupt" }
        val corrupted = zipped.copyOf()
        corrupted[at + 5] = (corrupted[at + 5].toInt() xor 0xFF).toByte() // flip one data byte → bad CRC-32
        assertNull(RomFingerprinter.fingerprint("gba", "game.zip", ByteArrayInputStream(corrupted)))
    }

    @Test
    fun `zip entry size drives ContentHash sizeBytes, not the container's compressed size on disk`() {
        // Highly compressible so DEFLATE shrinks the container well below the rom's true size — if a size-
        // derived rule (or a future one) ever read the container's on-disk length instead of the entry's
        // true uncompressed size, this would catch it immediately.
        val rom = ByteArray(65536)
        val zipped = zipOf("game.gba" to rom)
        assertTrue("fixture must actually compress smaller, or this test proves nothing", zipped.size < rom.size)
        val got = RomFingerprinter.fingerprint("gba", "game.zip", ByteArrayInputStream(zipped))!!
        assertEquals(65536L, got.sizeBytes)
    }

    @Test
    fun `a zipped rom never claims exactRawSha1 - the zip, not the entry, is the file on disk`() {
        val rom = ByteArray(2048) { (it * 5 % 250).toByte() }
        val zipped = zipOf("game.gba" to rom)
        val evidence = RomFingerprinter.fingerprintWithProvenance("gba", "game.zip", ByteArrayInputStream(zipped))!!
        assertNull(evidence.exactRawSha1)
        // The DAT-matching hash is still fully correct — only the byte-identical-on-disk provenance claim
        // is suppressed, because the inflated entry bytes are not what is actually stored at this path.
        assertEquals(whole(rom).sha1, evidence.datHash.sha1)
    }

    @Test
    fun `archive N64 transform keeps checking cancellation after source buffering completes`() {
        val bytes = ByteArray(2 * 1024 * 1024) { index -> (index * 31).toByte() }.also {
            it[0] = 0x37
            it[1] = 0x80.toByte()
            it[2] = 0x40
            it[3] = 0x12
        }
        var sourceExhausted = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also {
                    if (pos >= count) sourceExhausted = true
                }
        }
        var postSourceChecks = 0

        val failure = runCatching {
            RomFingerprinter.fingerprintArchiveStream("n64", input) {
                if (sourceExhausted && postSourceChecks++ >= 1) {
                    throw CancellationException("cancel transform")
                }
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(postSourceChecks >= 2)
    }

    /** Inverse of RomFingerprinter.smdToBin: 512-byte header + per-16KB even/odd interleave. */
    private fun binToSmd(bin: ByteArray): ByteArray {
        val header = ByteArray(512).also { it[8] = 0xAA.toByte(); it[9] = 0xBB.toByte() }
        val body = ByteArray(bin.size)
        var base = 0
        while (base + 16384 <= bin.size) {
            for (k in 0 until 8192) {
                body[base + k] = bin[base + 2 * k]
                body[base + 8192 + k] = bin[base + 2 * k + 1]
            }
            base += 16384
        }
        return header + body
    }

    private fun beU32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun beU64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (56 - i * 8)).toByte()
    }
}
