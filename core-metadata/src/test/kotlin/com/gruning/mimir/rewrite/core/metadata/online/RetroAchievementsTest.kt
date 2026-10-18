package com.gruning.mimir.rewrite.core.metadata.online

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Reader
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the RetroAchievements contract this lane cannot verify on device without a live account: the hash
 * rules, the credential-carrying URL shapes, and the parse-tolerance that keeps a bad response from
 * becoming a false claim about the user's progress.
 */
class RetroAchievementsTest {

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun hash(platform: String, bytes: ByteArray, fileName: String = "game.rom") =
        RetroAchievementsHash.hash(platform, fileName, bytes.size.toLong(), ByteArrayInputStream(bytes))

    // ---- hash ladder -------------------------------------------------------

    @Test
    fun plainSystemsHashTheWholeFile() {
        val rom = ByteArray(4096) { (it % 251).toByte() }
        listOf(
            "gb", "gbc", "gba", "genesis",
            "sega_master_system_mark_iii", "sega_game_gear", "sega_32x", "sega_sg_1000",
            "atari_2600", "atari_jaguar", "atari_lynx",
            "bandai_wonderswan", "bandai_wonderswan_color",
            "coleco_colecovision", "gce_vectrex", "mattel_intellivision",
            "microsoft_msx", "microsoft_msx2",
            "snk_neo_geo_pocket", "snk_neo_geo_pocket_color",
            "nintendo_pokemon_mini", "nintendo_virtual_boy",
        ).forEach {
            assertEquals("$it must be a plain whole-file MD5", md5(rom), hash(it, rom))
        }
    }

    /**
     * REGRESSION GUARD: the iNES header is non-canonical, so RA hashes the ROM AFTER it. Hashing the whole
     * file would miss every headered NES dump — i.e. essentially all of them — while looking like it worked.
     */
    @Test
    fun nesSkipsTheInesHeaderButOnlyWhenPresent() {
        val body = ByteArray(2048) { (it % 97).toByte() }
        val headered = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12) + body
        assertEquals(md5(body), hash("nes", headered))

        val headerless = ByteArray(2048) { (it % 89).toByte() }
        assertEquals("no iNES magic → hash whole file", md5(headerless), hash("nes", headerless))

        val fdsHeadered = byteArrayOf(0x46, 0x44, 0x53, 0x1A) + ByteArray(12) + body
        assertEquals("FDS shares the 16-byte header rule", md5(body), hash("nes", fdsHeadered))
    }

    @Test
    fun lynxSkipsSixtyFourBytesOnTheNulTerminatedMagic() {
        val body = ByteArray(1024) { (it % 71).toByte() }
        // "LYNX\0" — the NUL is the fifth byte; a space here would silently never match.
        val magic = "LYNX".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val headered = magic + ByteArray(64 - magic.size) + body
        assertEquals(md5(body), hash("atarilynx", headered))
    }

    @Test
    fun atari7800SkipsOneTwentyEightOnItsMagic() {
        val body = ByteArray(512) { (it % 13).toByte() }
        val magic = byteArrayOf(0x01) + "ATARI7800".toByteArray(Charsets.US_ASCII)
        val headered = magic + ByteArray(128 - magic.size) + body
        assertEquals(md5(body), hash("atari7800", headered))
    }

    /** SNES/PCE copier headers are detected by SIZE, not magic: 512 bytes past a whole number of blocks. */
    @Test
    fun copierHeaderIsDetectedBySizeForSnesAndPcEngine() {
        val snesBody = ByteArray(8 * 1024) { (it % 31).toByte() }
        val snesHeadered = ByteArray(512) { 0x7F } + snesBody
        assertEquals(md5(snesBody), hash("snes", snesHeadered))
        assertEquals("exact multiple of 8KiB → no header", md5(snesBody), hash("snes", snesBody))

        val pceBody = ByteArray(128 * 1024) { (it % 17).toByte() }
        val pceHeadered = ByteArray(512) { 0x5A } + pceBody
        assertEquals(md5(pceBody), hash("nec_pc_engine_turbografx_16", pceHeadered))
        assertEquals(md5(pceBody), hash("nec_pc_engine_supergrafx", pceHeadered))
    }

    /** The forward-only compatibility seam never guesses for a seek-based platform. */
    @Test
    fun seekSystemsReturnNullFromTheStreamingSeamRatherThanGuessing() {
        val rom = ByteArray(256) { 1 }
        listOf("ps1", "saturn", "segacd", "pcenginecd", "dreamcast", "ds", "psp").forEach {
            assertNull("$it must not produce a forward-only hash", hash(it, rom))
            assertTrue("$it has a separate seek reader", RetroAchievementsHash.supports(it))
        }
    }

    @Test
    fun n64NormalizesAllThreeRomByteOrdersFromTheirMagic() {
        val canonical = ByteArray(4096) { (it % 251).toByte() }.also {
            it[0] = 0x80.toByte()
            it[1] = 0x37
            it[2] = 0x12
            it[3] = 0x40
        }
        val v64 = canonical.copyOf().also { bytes ->
            bytes.indices.step(2).forEach { i ->
                val value = bytes[i]
                bytes[i] = bytes[i + 1]
                bytes[i + 1] = value
            }
        }
        val littleEndian = canonical.copyOf().also { bytes ->
            bytes.indices.step(4).forEach { i ->
                val a = bytes[i]
                val b = bytes[i + 1]
                bytes[i] = bytes[i + 3]
                bytes[i + 1] = bytes[i + 2]
                bytes[i + 2] = b
                bytes[i + 3] = a
            }
        }

        val expected = md5(canonical)
        assertEquals(expected, hash("n64", canonical, fileName = "mario.z64"))
        assertEquals(expected, hash("n64", v64, fileName = "mario.v64"))
        assertEquals(expected, hash("n64", littleEndian, fileName = "mario.n64"))
        assertNull("the extension cannot make junk an N64 ROM", hash("n64", ByteArray(4096), "junk.z64"))
    }

    @Test
    fun arcadeAndNeoGeoUseTheCaseSensitiveFilenameIdentity() {
        val ignoredContents = ByteArray(128) { 7 }
        val expected = md5("mslug".toByteArray())
        assertEquals(expected, hash("arcade", ignoredContents, "mslug.zip"))
        assertEquals(expected, hash("neo_geo", ignoredContents, "/roms/mslug.zip"))
        assertEquals(expected, hash("snk_neo_geo", ignoredContents, "C:\\roms\\mslug.zip"))
        assertFalse(expected == hash("arcade", ignoredContents, "MSLUG.zip"))
    }

    // ---- zipped ROMs -------------------------------------------------------

    /**
     * Builds a zip shaped like a real ROM archive. `null` content marks a directory entry.
     *
     * The detail that matters: entries are DEFLATED with size/CRC/compressed-size in the **local header**,
     * because that is what real ROM zips look like — of 2067 on the sampled card, zero deferred their sizes
     * to a trailing data descriptor. `ZipOutputStream` only writes them into the local header when all three
     * are known up front, so they are pre-computed here. A plain `putNextEntry(ZipEntry(name))` instead sets
     * general-purpose bit 3, which makes `ZipInputStream` report `size == -1` and would quietly test a shape
     * no shipped ROM set actually has — see [streamedZipOf], which pins that case on purpose.
     */
    private fun zipOf(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                if (body == null) {
                    zip.putNextEntry(ZipEntry(if (name.endsWith("/")) name else "$name/"))
                } else {
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            method = ZipEntry.DEFLATED
                            size = body.size.toLong()
                            crc = CRC32().apply { update(body) }.value
                            compressedSize = deflatedSize(body)
                        },
                    )
                    zip.write(body)
                }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Raw-deflate length, matching the Deflater `ZipOutputStream` uses so the local header validates. */
    private fun deflatedSize(body: ByteArray): Long {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(body)
        deflater.finish()
        var total = 0L
        val buffer = ByteArray(4096)
        while (!deflater.finished()) total += deflater.deflate(buffer)
        deflater.end()
        return total
    }

    /** A zip written in streaming mode: sizes live in a trailing data descriptor, so the local header has none. */
    private fun streamedZipOf(name: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(body)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun hashZip(platform: String, zip: ByteArray, fileName: String = "game.zip") =
        RetroAchievementsHash.hash(platform, fileName, zip.size.toLong(), ByteArrayInputStream(zip))

    /**
     * The whole point of the zip lane: a zipped ROM must land on the SAME hash as the loose file, or every
     * zipped set silently reads as "not matched". Verified against the sampled card first — of 789 SNES
     * titles present both ways, all 778 whose archive was readable extracted byte-identically.
     */
    @Test
    fun singleEntryZipHashesIdenticallyToTheLooseFile() {
        val rom = ByteArray(4096) { (it % 251).toByte() }
        val zipped = zipOf("Sonic (USA).gba" to rom)
        assertEquals(
            "a zipped ROM must hash exactly like the loose one",
            hash("gba", rom),
            hashZip("gba", zipped),
        )
        assertEquals(md5(rom), hashZip("gba", zipped))
    }

    /**
     * REGRESSION GUARD, and the subtle one. The SNES copier rule is SIZE-derived, and a compressed archive's
     * size says nothing about the ROM inside it. The rule must read [ZipEntry.size], never the container's.
     *
     * Grounded in real files: 133 SNES zips on the sampled card hold a copier-format .smc/.swc, 94 genuinely
     * headered — and for all 133 the CONTAINER size fails the `% 8 KiB == 512` test. Reading the container
     * size here would skip 0 bytes, fold the 512-byte copier header into the digest, and produce 94
     * confidently wrong hashes that still look like perfectly good MD5s.
     */
    @Test
    fun zippedSnesCopierHeaderUsesTheEntrySizeNotTheContainerSize() {
        val body = ByteArray(8 * 1024) { (it % 31).toByte() }
        val headered = ByteArray(512) { 0x7F } + body
        val zipped = zipOf("Chrono Trigger (USA).smc" to headered)

        // The container is compressed and/or framed, so it must NOT itself look like a headered ROM —
        // otherwise this test would pass for the wrong reason.
        assertFalse(
            "container size must not coincidentally satisfy the copier rule",
            zipped.size > 512 && zipped.size % (8 * 1024) == 512,
        )
        assertEquals("must skip the copier header found via the ENTRY size", md5(body), hashZip("snes", zipped))
        assertEquals("and match the loose headered file", hash("snes", headered), hashZip("snes", zipped))
    }

    /** A zipped ROM with no copier header must still take the plain path — the rule is per-entry, both ways. */
    @Test
    fun zippedSnesWithoutCopierHeaderHashesWhole() {
        val body = ByteArray(8 * 1024) { (it % 17).toByte() }
        assertEquals(md5(body), hashZip("snes", zipOf("Zelda (USA).sfc" to body)))
    }

    /**
     * With two ROMs there is no honest way to choose, so we refuse. Measured cost of this strictness on the
     * sampled 2067 zipped ROMs: 2 files — a romhack bundle and a PC-game bundle, neither of which holds a
     * single unambiguous ROM anyway.
     */
    @Test
    fun multiEntryZipReturnsNullRatherThanPickingOne() {
        val a = ByteArray(1024) { 1 }
        val b = ByteArray(1024) { 2 }
        assertNull("two ROMs = ambiguous", hashZip("gba", zipOf("A.gba" to a, "B.gba" to b)))
        assertNull("no ROM at all", hashZip("gba", zipOf("readme.txt" to a, "patch.ips" to b)))
        // Non-ROM siblings do not make a single ROM ambiguous.
        assertEquals(md5(a), hashZip("gba", zipOf("readme.txt" to b, "Game.gba" to a)))
    }

    /**
     * REGRESSION GUARD: a directory entry is packaging, not a candidate. One real SNES zip on the card is a
     * folder entry plus one .sfc — counting the folder would refuse a ROM that is in fact unambiguous.
     */
    @Test
    fun directoryEntriesAreSkipped() {
        val rom = ByteArray(2048) { (it % 13).toByte() }
        val zipped = zipOf("Star Fox 2 (USA)/" to null, "Star Fox 2 (USA)/Star Fox 2 (USA).sfc" to rom)
        assertEquals("a folder entry must not count as a second ROM", md5(rom), hashZip("snes", zipped))
    }

    /** The archive walk must select the N64 entry, then normalize its byte order from content magic. */
    @Test
    fun zippedN64DispatchesOnTheEntryNameNotTheArchiveName() {
        val z64 = ByteArray(1024) { (it % 7).toByte() }.apply {
            this[0] = 0x80.toByte()
            this[1] = 0x37
            this[2] = 0x12
            this[3] = 0x40
        }
        val v64 = z64.copyOf().also { bytes ->
            for (i in bytes.indices.step(2)) {
                val value = bytes[i]
                bytes[i] = bytes[i + 1]
                bytes[i + 1] = value
            }
        }
        val expected = md5(z64)
        assertEquals(expected, hashZip("n64", zipOf("Mario 64.z64" to z64), fileName = "Mario 64.zip"))
        assertEquals(expected, hashZip("n64", zipOf("Mario 64.v64" to v64), fileName = "Mario 64.zip"))
    }

    /**
     * A damaged archive must read as "not matched", never as a hash of whatever bytes came out. 29 of the
     * 2067 zipped ROMs on the sampled card are genuinely corrupt (the device's own `unzip -t` agrees), so
     * this is a live path. java.util.zip catches it via the entry's CRC-32; we must not let that throw
     * escape as a crash either.
     */
    @Test
    fun corruptArchiveReturnsNullInsteadOfThrowing() {
        val rom = ByteArray(4096) { (it % 251).toByte() }
        val good = zipOf("Sonic (USA).gba" to rom)

        // Damage the deflated PAYLOAD only, leaving every header — and so the recorded CRC-32 and the entry
        // name — intact. Located exactly rather than guessed, so this cannot degrade into "the name got
        // mangled so no ROM was found", which would pass for the wrong reason.
        fun u16(at: Int) = (good[at].toInt() and 0xFF) or ((good[at + 1].toInt() and 0xFF) shl 8)
        val payloadStart = 30 + u16(26) + u16(28) // local header + file name + extra field
        val bad = good.copyOf()
        for (i in payloadStart until minOf(payloadStart + 16, bad.size)) bad[i] = (bad[i].toInt() xor 0x5A).toByte()

        assertNull("a damaged archive has no honest hash", hashZip("gba", bad))
        assertNull("truncated archive likewise", hashZip("gba", good.copyOf(payloadStart + 8)))
    }

    /**
     * A zip written in streaming mode reports `size == -1`, and the size-derived rules cannot run without it.
     * SNES must then return null rather than assume "unheadered" — guessing wrong there would fold 512 bytes
     * of copier metadata into the digest. Platforms whose rule never reads the size are unaffected, which is
     * why GBA still hashes fine from the very same archive shape.
     *
     * No shipped ROM zip observed does this (0 of 2067), so this pins an edge rather than a hot path — but it
     * is the one shape where "use ZipEntry.size" has no answer, and silently guessing is the whole failure
     * mode this class exists to avoid.
     */
    @Test
    fun streamedZipWithUnknownEntrySizeRefusesTheSizeDerivedRules() {
        val body = ByteArray(8 * 1024) { (it % 31).toByte() }
        val headered = ByteArray(512) { 0x7F } + body
        assertNull(
            "unknown entry size must not be read as 'no copier header'",
            hashZip("snes", streamedZipOf("Chrono Trigger (USA).smc", headered)),
        )
        assertNull(hashZip("pcengine", streamedZipOf("Bonk.pce", headered)))
        assertEquals(
            "a rule that never reads the size is unaffected",
            md5(body),
            hashZip("gba", streamedZipOf("Sonic.gba", body)),
        )
    }

    /** Unsupported systems stay unsupported when zipped — the archive is not a way around the honesty rule. */
    @Test
    fun zippingDoesNotSmuggleInAnUnsupportedSystem() {
        val rom = ByteArray(1024) { 1 }
        assertNull(hashZip("ps1", zipOf("game.bin" to rom)))
        assertNull(hashZip("ds", zipOf("game.nds" to rom)))
    }

    /**
     * `supports()` is derived from the same table the zip walk uses, so this pins the exact set. If a
     * platform is added to one, this test makes the omission in the other loud rather than silent.
     */
    @Test
    fun supportedSystemsAreExactlyTheHashableOnes() {
        val expected = setOf(
            "amstrad_cpc", "arcade",
            "atari_2600", "atari_7800", "atari_jaguar", "atari_lynx",
            "bandai_wonderswan", "bandai_wonderswan_color",
            "coleco_colecovision", "gb", "gba", "gbc", "gce_vectrex", "genesis",
            "mattel_intellivision", "microsoft_msx", "microsoft_msx2", "n64",
            "nec_pc_engine_supergrafx", "nec_pc_engine_turbografx_16",
            "nec_pc_engine_cd_turbografx_cd", "nec_pc_fx",
            "neo_geo", "nes", "nintendo_pokemon_mini", "nintendo_virtual_boy", "nintendo_wii",
            "dreamcast", "ds", "gamecube", "ps1", "ps2", "psp", "saturn",
            "sega_32x", "sega_game_gear", "sega_master_system_mark_iii", "sega_sg_1000",
            "sega_mega_cd_sega_cd", "snes", "snk_neo_geo", "snk_neo_geo_cd",
            "snk_neo_geo_pocket", "snk_neo_geo_pocket_color", "the_3do_company_3do",
        )
        expected.forEach { assertTrue("$it must be supported", RetroAchievementsHash.supports(it)) }
        assertTrue("case must not matter", RetroAchievementsHash.supports("SNES"))
        val actual = RetroAchievementsConsoleMap.mappedProductPlatformIds
            .filterTo(linkedSetOf(), RetroAchievementsHash::supports)
        assertEquals(expected, actual)
        listOf("", "zip", "xbox")
            .forEach { assertFalse("$it must not be supported", RetroAchievementsHash.supports(it)) }
    }

    @Test
    fun allThirteenSeekReadersPinUpstreamConsoleIdsAndContainerBoundaries() {
        val supported = mapOf(
            "dreamcast" to (40 to "Sonic.gdi"),
            "ds" to (18 to "Mario.nds"),
            "gamecube" to (16 to "Metroid.gcm"),
            "nintendo_wii" to (19 to "Galaxy.iso"),
            "ps1" to (12 to "Ridge Racer.chd"),
            "ps2" to (21 to "Ace Combat.iso"),
            "psp" to (41 to "Lumines.pbp"),
            "saturn" to (39 to "Nights.cue"),
            "nec_pc_engine_cd_turbografx_cd" to (76 to "Dracula X.chd"),
            "nec_pc_fx" to (49 to "Team Innocent.cue"),
            "sega_mega_cd_sega_cd" to (9 to "Snatcher.bin"),
            "snk_neo_geo_cd" to (56 to "Metal Slug.chd"),
            "the_3do_company_3do" to (43 to "Road Rash.iso"),
        )
        assertEquals(13, supported.size)
        supported.forEach { (platform, rule) ->
            assertEquals(platform, rule.first, RetroAchievementsHash.nativeConsoleId(platform))
            assertTrue(platform, RetroAchievementsHash.supportsFile(platform, rule.second))
            assertTrue(platform, RetroAchievementsHash.requiresLocalPath(platform, rule.second))
        }

        mapOf(
            "dreamcast" to "game.cdi",
            "gamecube" to "game.rvz",
            "nintendo_wii" to "game.wbfs",
            "ps1" to "game.ecm",
            "psp" to "game.cso",
        ).forEach { (platform, file) ->
            assertFalse("$platform/$file needs a decoder we do not ship", RetroAchievementsHash.supportsFile(platform, file))
        }
    }

    /** `.7z` is opaque here and must never fall through to a plausible whole-container cartridge MD5. */
    @Test
    fun opaqueSevenZipCartridgeWrapperIsRejectedBeforeLooseHashing() {
        val rom = ByteArray(1024) { 1 }
        assertNull(hash("gba", rom, fileName = "game.7z"))
        assertFalse(RetroAchievementsHash.supportsFile("gba", "game.7z"))
        assertTrue("valid loose cartridges remain supported", RetroAchievementsHash.supportsFile("gba", "game.gba"))
        assertTrue("exact-one-member ZIP remains supported", RetroAchievementsHash.supportsFile("gba", "game.zip"))
    }

    // ---- URL shape + credential safety -------------------------------------

    private val auth = RetroAchievementsApi.Auth(username = "player one", apiKey = "s3cr3t/key+")

    @Test
    fun urlsCarryTheVerifiedParamsAndEncodeCredentials() {
        assertTrue(RetroAchievementsApi.consoleIdsUrl(auth).contains("API_GetConsoleIDs.php"))

        val list = RetroAchievementsApi.gameListUrl(auth, consoleId = 7)
        assertTrue("must request hashes", list.contains("h=1"))
        assertTrue("must filter to games with achievements", list.contains("f=1"))
        assertTrue(list.contains("i=7"))

        val progress = RetroAchievementsApi.gameProgressUrl(auth, gameId = 42)
        assertTrue(progress.contains("g=42"))
        assertTrue("username is url-encoded", progress.contains("u=player+one"))
        assertTrue("key is url-encoded", progress.contains("y=s3cr3t%2Fkey%2B"))

        val profile = RetroAchievementsApi.userProfileUrl(auth)
        assertTrue(profile.contains("API_GetUserProfile.php"))
        assertTrue("profile validates the paired username", profile.contains("u=player+one"))
        assertTrue("profile authenticates with the candidate key", profile.contains("y=s3cr3t%2Fkey%2B"))

        val hashLibrary = RetroAchievementsApi.hashLibraryUrl()
        assertEquals("https://retroachievements.org/dorequest.php?r=hashlibrary", hashLibrary)
        assertFalse("the whole hash library is keyless", hashLibrary.contains("y="))
        assertFalse("and userless", hashLibrary.contains("u="))
    }

    /** The key is a secret (RA's own docs). Anything loggable must not carry it. */
    @Test
    fun redactKeepsTheKeyOutOfLogs() {
        val url = RetroAchievementsApi.gameProgressUrl(auth, gameId = 42)
        val redacted = RetroAchievementsApi.redact(url)
        assertEquals("API_GetGameInfoAndUserProgress.php", redacted)
        assertFalse(redacted.contains("s3cr3t"))
        assertFalse(redacted.contains("player"))

        val renderedAuth = auth.toString()
        assertFalse("the internal auth value must not leak through toString", renderedAuth.contains("s3cr3t"))
        assertTrue(renderedAuth.contains("***"))
    }

    // ---- parsing -----------------------------------------------------------

    @Test
    fun hashIndexFlattensEveryHashOntoItsGame() {
        val a = "0123456789abcdef0123456789abcdef"
        val b = "abcdef0123456789abcdef0123456789"
        val c = "99aa0000000000000000000000000000"
        val body = """
            [{"ID":1,"Title":"Sonic","ConsoleID":7,"NumAchievements":2,"Hashes":["${a.uppercase()}","$b"]},
             {"ID":2,"Title":"Streets","ConsoleID":7,"NumAchievements":1,"Hashes":["${c.uppercase()}"]}]
        """.trimIndent()
        val index = RetroAchievementsApi.parseHashIndex(body, expectedConsoleId = 7)
        assertEquals(mapOf(a to 1, b to 1, c to 2), index)
    }

    @Test
    fun hashIndexRejectsEveryPartialOrConflictingShapeAsAWhole() {
        val hash = "0123456789abcdef0123456789abcdef"
        listOf(
            """[{"ID":1,"Title":"A","ConsoleID":7,"NumAchievements":1,"Hashes":["$hash"]},{"ID":2,"Title":"B","ConsoleID":7,"NumAchievements":1}]""",
            """[{"ID":1,"Title":"A","ConsoleID":7,"NumAchievements":1,"Hashes":["$hash"]},{"ID":2,"Title":"B","ConsoleID":7,"NumAchievements":1,"Hashes":["short"]}]""",
            """[{"ID":1,"Title":"A","ConsoleID":7,"NumAchievements":1,"Hashes":[" $hash"]}]""",
            """[{"ID":0,"Title":"A","ConsoleID":7,"NumAchievements":1,"Hashes":["$hash"]}]""",
            """[{"ID":1,"Title":"A","ConsoleID":7,"NumAchievements":1,"Hashes":["$hash"]},{"ID":2,"Title":"B","ConsoleID":7,"NumAchievements":1,"Hashes":["$hash"]}]""",
            """[{"ID":1,"Title":"A","ConsoleID":7,"NumAchievements":1,"Hashes":["$hash",42]}]""",
            """[{"ID":1,"Title":"A","ConsoleID":7,"NumAchievements":1,"Hashes":[]}]""",
            """[{"ID":1,"Title":"A","ConsoleID":8,"NumAchievements":1,"Hashes":["$hash"]}]""",
        ).forEach { body ->
            assertNull(
                "partial/conflicting f=1 must fail closed: $body",
                RetroAchievementsApi.parseHashIndex(body, expectedConsoleId = 7),
            )
        }

        assertEquals(
            "an identical duplicate does not create an identity conflict",
            mapOf(hash to 1),
            RetroAchievementsApi.parseHashIndex(
                """[{"ID":1,"Title":"A","ConsoleID":7,"NumAchievements":1,"Hashes":["$hash","${hash.uppercase()}"]}]""",
                expectedConsoleId = 7,
            ),
        )
    }

    @Test
    fun emptyHashRowsPreservePositiveMappingsButMarkTheIndexIncomplete() {
        val hash = "0123456789abcdef0123456789abcdef"
        val body =
            """[{"ID":1,"Title":"Mapped","ConsoleID":7,"NumAchievements":1,"Hashes":["$hash"]},""" +
                """{"ID":2,"Title":"No published files","ConsoleID":7,"NumAchievements":1,"Hashes":[]}]"""

        val usable = RetroAchievementsApi.parseUsableHashIndex(body, expectedConsoleId = 7)!!

        assertEquals(mapOf(hash to 1), usable.hashes)
        assertEquals(setOf(1, 2), usable.gameIds)
        assertFalse(usable.complete)
        assertNull("the strict parser must not turn this into completed negative truth", RetroAchievementsApi.parseHashIndex(body, 7))
    }

    @Test
    fun allEmptyHashRowsContainNoUsablePositiveClaim() {
        val body =
            """[{"ID":1,"Title":"No published files","ConsoleID":7,"NumAchievements":1,"Hashes":[]}]"""

        val usable = RetroAchievementsApi.parseUsableHashIndex(body, expectedConsoleId = 7)!!

        assertTrue(usable.hashes.isEmpty())
        assertEquals(setOf(1), usable.gameIds)
        assertFalse(usable.complete)
        assertNull(RetroAchievementsApi.parseHashIndex(body, expectedConsoleId = 7))
    }

    @Test
    fun hashIndexDiagnosticsNeverEchoProviderContent() {
        val titleCanary = "private-title-canary"
        val hashCanary = "private-hash-canary"
        val diagnostic = RetroAchievementsApi.hashIndexDiagnostic(
            """[{"ID":1,"Title":"$titleCanary","ConsoleID":7,"NumAchievements":1,"Hashes":["$hashCanary"]}]""",
            expectedConsoleId = 7,
        )

        assertEquals("rows=1 invalid[0]=hash_value", diagnostic)
        assertFalse(diagnostic.contains(titleCanary))
        assertFalse(diagnostic.contains(hashCanary))
    }

    @Test
    fun userProfileRequiresBothTheResolvedNameAndUlid() {
        val profile = RetroAchievementsApi.parseUserProfile(
            """{"User":"Player_One","ULID":"01JPLAYER000000000000000000"}""",
        )!!
        assertEquals("Player_One", profile.username)
        assertEquals("01JPLAYER000000000000000000", profile.ulid)

        assertNull(RetroAchievementsApi.parseUserProfile("""{"User":"Player_One"}"""))
        assertNull(RetroAchievementsApi.parseUserProfile("""{"ULID":"01JPLAYER000000000000000000"}"""))
        assertNull(RetroAchievementsApi.parseUserProfile("not-json"))
    }

    @Test
    fun wholeHashLibraryStreamsIntoSortedPackedMd5GameIndex() {
        val hashes = listOf(
            "ffffffffffffffffffffffffffffffff",
            "00000000000000000000000000000001",
            "80000000000000000000000000000000",
            "7fffffffffffffffffffffffffffffff",
        )
        val entries = hashes.mapIndexed { index, hash -> "\"$hash\":${index + 41}" }.joinToString(",")
        val packed = parsePackedHashLibrary(
            StringReader("""{"MD5List":{$entries},"Success":true}"""),
        )!!

        assertEquals(hashes.size, packed.size)
        assertEquals(
            "two primitive longs plus one game-id int per hash",
            hashes.size * 20,
            packed.packedBytes,
        )
        hashes.forEachIndexed { index, hash ->
            assertEquals("must retain the game id through unsigned sort", index + 41, packed[hash])
        }
        assertTrue("hex case is immaterial", "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" in packed)
        assertFalse("fffffffffffffffffffffffffffffffe" in packed)
        assertFalse("not-an-md5" in packed)
    }

    @Test
    fun measuredWholeLibraryPopulationRetainsTwentyBytesPerHash() {
        val count = 53_555
        val body = buildString(count * 40) {
            append("{\"Success\":true,\"MD5List\":{")
            repeat(count) { index ->
                if (index > 0) append(',')
                append('"')
                append(index.toString(16).padStart(32, '0'))
                append("\":")
                append(index + 1)
            }
            append("}}")
        }

        val packed = parsePackedHashLibrary(StringReader(body))!!
        assertEquals(count, packed.size)
        assertEquals(count * 20, packed.packedBytes)
        assertTrue("00000000000000000000000000000000" in packed)
        assertTrue((count - 1).toString(16).padStart(32, '0') in packed)
    }

    @Test
    fun wholeHashLibraryShapeDriftFailsClosed() {
        val hash = "0123456789abcdef0123456789abcdef"
        listOf(
            """{"Success":false,"MD5List":{"$hash":1}}""",
            """{"Success":true,"MD5List":{}}""",
            """{"Success":true}""",
            """{"Success":true,"MD5List":{"short":1}}""",
            """{"Success":true,"MD5List":{"$hash":"game"}}""",
            """{"Success":true,"MD5List":{"$hash":1},"NewShape":1}""",
            """{"Success":true,"MD5List":{"$hash":1}""",
        ).forEach { body ->
            assertNull("shape drift must not yield membership: $body", parsePackedHashLibrary(StringReader(body)))
        }
    }

    @Test
    fun wholeHashLibraryRejectsOneHashAssignedToTwoGameIds() {
        val hash = "0123456789abcdef0123456789abcdef"
        val body = """{"Success":true,"MD5List":{"$hash":1,"${hash.uppercase()}":2}}"""

        assertNull(parsePackedHashLibrary(StringReader(body)))
    }

    @Test
    fun wholeHashLibraryHasBoundedEntriesStringsAndNumberTokens() {
        val hash = "0123456789abcdef0123456789abcdef"
        assertNull(
            "field-name strings cannot grow without bound",
            parsePackedHashLibrary(
                StringReader("{\"${"x".repeat(257)}\":true,\"Success\":true,\"MD5List\":{\"$hash\":1}}"),
            ),
        )
        assertNull(
            "numeric tokens are bounded and must be positive game ids",
            parsePackedHashLibrary(StringReader("""{"Success":true,"MD5List":{"$hash":99999999999}}""")),
        )

        val overEntryCeiling = buildString((HASH_LIBRARY_MAX_ENTRIES + 1) * 40) {
            append("{\"Success\":true,\"MD5List\":{")
            repeat(HASH_LIBRARY_MAX_ENTRIES + 1) { index ->
                if (index > 0) append(',')
                append('"')
                append(index.toString(16).padStart(32, '0'))
                append("\":1")
            }
            append("}}")
        }
        assertNull(
            "the measured 53,555 entries fit, but an adversarial population is capped",
            parsePackedHashLibrary(StringReader(overEntryCeiling)),
        )
    }

    @Test
    fun wholeHashLibraryParserRethrowsCancellation() {
        val cancellingReader = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                throw CancellationException("reader cancelled")

            override fun close() = Unit
        }

        try {
            parsePackedHashLibrary(cancellingReader)
            throw AssertionError("reader cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("reader cancelled", expected.message)
        }
    }

    /**
     * Unlock truth is presence-based. RA omits DateEarned on a locked achievement; treating a
     * present-but-empty value as earned would invent progress the user has not made.
     */
    @Test
    fun progressCountsOnlyRealUnlocks() {
        val body = """
            {"ID":9,"Title":"Sonic","NumAchievements":4,
             "NumAwardedToUser":2,"NumAwardedToUserHardcore":1,"Achievements":{
              "1":{"ID":1,"Title":"A","Description":"a","Points":5,"DateEarned":"2026-01-02 03:04:05"},
              "2":{"ID":2,"Title":"B","Description":"b","Points":10},
              "3":{"ID":3,"Title":"C","Description":"c","Points":25,"DateEarned":"","DateEarnedHardcore":""},
              "4":{"ID":4,"Title":"D","Description":"d","Points":50,"DateEarnedHardcore":"2026-02-02 00:00:00"}
            }}
        """.trimIndent()
        val progress = RetroAchievementsApi.parseGameProgress(body)!!
        assertEquals(9, progress.gameId)
        assertEquals(4, progress.totalCount)
        assertEquals("only #1 and #4 are earned", 2, progress.unlockedCount)
        assertEquals(55, progress.earnedPoints)
        assertEquals(90, progress.totalPoints)
        assertTrue(progress.achievements.single { it.id == 4 }.unlockedHardcore)
        assertFalse("empty string is NOT an unlock", progress.achievements.single { it.id == 3 }.unlocked)
    }

    /** The per-game answer names the console in RA's own words; absent, blank or not a string it is simply no console, and never a reason to reject the answer. */
    @Test
    fun progressCarriesTheConsoleNameWhenThereIsOne() {
        fun body(console: String) = """
            {"ID":9,"Title":"Final Fantasy X",$console"NumAchievements":1,
             "NumAwardedToUser":1,"NumAwardedToUserHardcore":0,"Achievements":{
              "1":{"ID":1,"Title":"A","Description":"a","Points":5,"DateEarned":"2026-01-02"}
            }}
        """.trimIndent()
        assertEquals("PlayStation 2", RetroAchievementsApi.parseGameProgress(body("\"ConsoleName\":\" PlayStation 2 \","))!!.consoleName)
        assertNull("no field, no console", RetroAchievementsApi.parseGameProgress(body(""))!!.consoleName)
        assertNull("blank is no console", RetroAchievementsApi.parseGameProgress(body("\"ConsoleName\":\"  \","))!!.consoleName)
        assertNull("a number is no console", RetroAchievementsApi.parseGameProgress(body("\"ConsoleName\":21,"))!!.consoleName)
        assertEquals("and the answer is still read", 1, RetroAchievementsApi.parseGameProgress(body("\"ConsoleName\":null,"))!!.totalCount)
    }

    @Test
    fun progressRejectsMissingOrPartialResponsesButTrustsRowsOverDeclaredCounts() {
        val complete = """
            {"ID":9,"Title":"Sonic","NumAchievements":2,
             "NumAwardedToUser":1,"NumAwardedToUserHardcore":0,"Achievements":{
              "1":{"ID":1,"Title":"A","Description":"a","Points":5,"DateEarned":"2026-01-02"},
              "2":{"ID":2,"Title":"B","Description":"b","Points":10}
            }}
        """.trimIndent()

        assertNull(
            "Achievements is a required documented object",
            RetroAchievementsApi.parseGameProgress(
                """{"ID":9,"Title":"Sonic","NumAchievements":2,"NumAwardedToUser":1,"NumAwardedToUserHardcore":0}""",
            ),
        )
        assertNull(
            "one malformed row invalidates the whole progress response",
            RetroAchievementsApi.parseGameProgress(complete.replace("\"Points\":10", "\"Points\":\"bad\"")),
        )
        // RAWeb's declared counts come from job-updated columns and lag a fresh unlock; the rows win.
        val staleTotal = RetroAchievementsApi.parseGameProgress(
            complete.replace("\"NumAchievements\":2", "\"NumAchievements\":3"),
        )!!
        assertEquals("counts derive from the rows, not the declared total", 2, staleTotal.totalCount)
        val staleUnlocks = RetroAchievementsApi.parseGameProgress(
            complete.replace("\"NumAwardedToUser\":1", "\"NumAwardedToUser\":0"),
        )!!
        assertEquals("a just-earned unlock counts before RA's counter catches up", 1, staleUnlocks.unlockedCount)
        assertNull(
            "the declared counts are still required fields",
            RetroAchievementsApi.parseGameProgress(complete.replace("\"NumAwardedToUser\":1,", "")),
        )
        assertNull(
            "zero-of-zero is narrower as unavailable until RA documents a legitimate empty contract",
            RetroAchievementsApi.parseGameProgress(
                """{"ID":9,"Title":"Sonic","NumAchievements":0,"NumAwardedToUser":0,"NumAwardedToUserHardcore":0,"Achievements":{}}""",
            ),
        )
    }

    /** A broken response must read as "couldn't load", never as a confident empty result. */
    @Test
    fun malformedBodiesDegradeToNullNotToFalseZero() {
        listOf(null, "", "   ", "not json", "{", "[1,2,3]").forEach {
            assertNull("must not fabricate progress from: $it", RetroAchievementsApi.parseGameProgress(it))
        }
        assertNull(RetroAchievementsApi.parseHashIndex("nonsense"))
        assertEquals(emptyMap<String, Int>(), RetroAchievementsApi.parseHashIndex("[]"))
        assertEquals(emptyMap<Int, String>(), RetroAchievementsApi.parseConsoleIds("nonsense"))
    }

    // ---- console mapping ---------------------------------------------------

    @Test
    fun consoleNamesMapThroughPunctuationAndCaseDifferences() {
        assertEquals("genesis", RetroAchievementsConsoleMap.platformIdFor("Mega Drive"))
        assertEquals("genesis", RetroAchievementsConsoleMap.platformIdFor("mega-drive"))
        assertEquals("gba", RetroAchievementsConsoleMap.platformIdFor("Game Boy Advance"))
        assertEquals("atari_lynx", RetroAchievementsConsoleMap.platformIdFor("Atari Lynx"))
        assertEquals("ps1", RetroAchievementsConsoleMap.platformIdFor("PlayStation"))
        assertEquals("psp", RetroAchievementsConsoleMap.platformIdFor("PlayStation Portable"))
    }

    @Test
    fun discoverableJaguarCdMapsToRaButDoesNotClaimALocalIdentifier() {
        val live = mapOf(77 to "Atari Jaguar CD")

        assertTrue(RetroAchievementsConsoleMap.supportsPlatform("atari_jaguar_cd"))
        assertEquals("atari_jaguar_cd", RetroAchievementsConsoleMap.platformIdFor("Atari Jaguar CD"))
        assertEquals(listOf(77), RetroAchievementsConsoleMap.consoleIdsFor(live, "atari_jaguar_cd"))
        assertTrue(
            RetroAchievementsConsoleMap.platformAuthorityRows().any {
                it.rawPlatformId == "atari_jaguar_cd" &&
                    it.declaredCanonicalPlatformId == "atari_jaguar_cd" &&
                    it.providerKey == "atarijaguarcd"
            },
        )

        assertFalse("Jaguar CD has no certified local hash reader", RetroAchievementsHash.supports("atari_jaguar_cd"))
        assertFalse(RetroAchievementsHash.supportsFile("atari_jaguar_cd", "game.cue"))
        assertNull(RetroAchievementsHash.nativeConsoleId("atari_jaguar_cd"))
        assertEquals("the closed release support audit remains 45 mapped platforms", 45,
            RetroAchievementsConsoleMap.mappedProductPlatformIds.size)
        assertFalse("the supplemental discovery must not rewrite the closed 45-platform audit",
            "atari_jaguar_cd" in RetroAchievementsConsoleMap.mappedProductPlatformIds)
    }

    /** Every mapped complex platform now has a canonical local identifier. */
    @Test
    fun supportedButComplexConsolesRemainMapped() {
        mapOf(
            "PlayStation" to "ps1",
            "PlayStation 2" to "ps2",
            "PlayStation Portable" to "psp",
            "Sega CD" to "sega_mega_cd_sega_cd",
            "Dreamcast" to "dreamcast",
            "Nintendo DS" to "ds",
            "Nintendo DSi" to "ds",
            "Sega Saturn" to "saturn",
            "GameCube" to "gamecube",
            "Wii" to "nintendo_wii",
            "Neo Geo CD" to "snk_neo_geo_cd",
            "PC Engine CD" to "nec_pc_engine_cd_turbografx_cd",
            "PC-FX" to "nec_pc_fx",
            "3DO" to "the_3do_company_3do",
        ).forEach { (name, platformId) ->
            assertEquals(name, platformId, RetroAchievementsConsoleMap.platformIdFor(name))
            assertTrue(RetroAchievementsConsoleMap.supportsPlatform(platformId))
            assertTrue("$platformId must have its seek/disc identifier", RetroAchievementsHash.supports(platformId))
        }
    }

    @Test
    fun resolveKeepsEverySupportedConsoleEvenWhenItsIdentifierIsStillOwed() {
        val live = mapOf(1 to "Mega Drive", 12 to "PlayStation", 5 to "Game Boy Advance")
        assertEquals(mapOf(1 to "genesis", 12 to "ps1", 5 to "gba"), RetroAchievementsConsoleMap.resolve(live))
        assertEquals(listOf(1), RetroAchievementsConsoleMap.consoleIdsFor(live, "genesis"))
        assertEquals(
            "shipping alias must resolve to the canonical RA identity",
            listOf(1),
            RetroAchievementsConsoleMap.consoleIdsFor(live, "megadrive"),
        )
    }

    @Test
    fun sharedConsoleIdentitiesFanOutToEveryCurrentMimirPlatform() {
        val live = mapOf(
            7 to "Nintendo Entertainment System",
            81 to "Famicom Disk System",
            18 to "Nintendo DS",
            78 to "Nintendo DSi",
            8 to "PC Engine",
            14 to "Neo Geo Pocket",
            27 to "Arcade",
            29 to "MSX",
            53 to "WonderSwan",
        )
        assertEquals(listOf(7, 81), RetroAchievementsConsoleMap.consoleIdsFor(live, "nes"))
        assertEquals(listOf(18, 78), RetroAchievementsConsoleMap.consoleIdsFor(live, "ds"))
        assertEquals(listOf(8), RetroAchievementsConsoleMap.consoleIdsFor(live, "nec_pc_engine_supergrafx"))
        assertEquals(listOf(14), RetroAchievementsConsoleMap.consoleIdsFor(live, "snk_neo_geo_pocket_color"))
        assertEquals(listOf(27), RetroAchievementsConsoleMap.consoleIdsFor(live, "snk_neo_geo"))
        assertEquals(listOf(29), RetroAchievementsConsoleMap.consoleIdsFor(live, "microsoft_msx2"))
        assertEquals(listOf(53), RetroAchievementsConsoleMap.consoleIdsFor(live, "bandai_wonderswan_color"))
    }

    /** Exact combined labels exposed by RA's public Games menu and returned as console names. */
    @Test
    fun currentCombinedProviderLabelsResolveToTheirProductPlatforms() {
        val current = mapOf(
            1 to "Genesis/Mega Drive",
            2 to "NES/Famicom",
            3 to "SNES/Super Famicom",
            4 to "32X",
        )
        assertEquals(listOf(1), RetroAchievementsConsoleMap.consoleIdsFor(current, "genesis"))
        assertEquals(listOf(2), RetroAchievementsConsoleMap.consoleIdsFor(current, "nes"))
        assertEquals(listOf(3), RetroAchievementsConsoleMap.consoleIdsFor(current, "snes"))
        assertEquals(listOf(4), RetroAchievementsConsoleMap.consoleIdsFor(current, "sega_32x"))
        assertEquals(
            "scanner-emitted 32X id must reach the same console",
            listOf(4),
            RetroAchievementsConsoleMap.consoleIdsFor(current, "sega32x"),
        )
    }

    @Test
    fun currentMimirPlatformSetHasNoRaMappingGapsOrFalseClaims() {
        val expectedSupported = setOf(
            "amstrad_cpc", "arcade", "atari_2600", "atari_7800", "atari_jaguar", "atari_lynx",
            "bandai_wonderswan", "bandai_wonderswan_color", "coleco_colecovision", "dreamcast", "ds",
            "gamecube", "gb", "gba", "gbc", "gce_vectrex", "genesis", "mattel_intellivision",
            "microsoft_msx", "microsoft_msx2", "n64", "nec_pc_engine_cd_turbografx_cd",
            "nec_pc_engine_supergrafx", "nec_pc_engine_turbografx_16", "nec_pc_fx", "neo_geo", "nes",
            "nintendo_pokemon_mini", "nintendo_virtual_boy", "nintendo_wii", "ps1", "ps2", "psp", "saturn",
            "sega_32x", "sega_game_gear", "sega_master_system_mark_iii", "sega_mega_cd_sega_cd",
            "sega_sg_1000", "snes", "snk_neo_geo", "snk_neo_geo_cd", "snk_neo_geo_pocket",
            "snk_neo_geo_pocket_color", "the_3do_company_3do",
        )
        val expectedUnsupported = setOf(
            "3ds", "atari_5200", "atari_8_bit_family", "commodore_64", "commodore_amiga",
            "commodore_vic_20", "dos", "ps3", "scummvm", "sinclair_zx_spectrum", "switch", "tic_80",
            "xbox", "xbox360",
        )
        assertEquals(45, expectedSupported.size)
        assertEquals(expectedSupported, RetroAchievementsConsoleMap.mappedProductPlatformIds)
        expectedSupported.forEach { assertTrue(it, RetroAchievementsConsoleMap.supportsPlatform(it)) }
        expectedUnsupported.forEach { assertFalse(it, RetroAchievementsConsoleMap.supportsPlatform(it)) }
        assertEquals("the audited B21 ∪ R49 surface must stay closed", 59, expectedSupported.size + expectedUnsupported.size)

        // Current public labels from RA's Games menu, audited 2026-07-20. This fixture intentionally keeps
        // combined cosmetic names intact so a provider rename cannot pass behind friendlier legacy aliases.
        val currentPublicConsoleNames = listOf(
            "3DO Interactive Multiplayer", "Amstrad CPC", "Arcade", "Atari 2600", "Atari 7800",
            "Atari Jaguar", "Atari Lynx", "ColecoVision", "Dreamcast", "Famicom Disk System",
            "Game Boy", "Game Boy Advance", "Game Boy Color", "GameCube", "Game Gear", "Intellivision",
            "Master System", "Genesis/Mega Drive", "MSX", "Neo Geo CD", "Neo Geo Pocket", "Nintendo 64",
            "Nintendo DS", "Nintendo DSi", "NES/Famicom", "PC Engine/TurboGrafx-16",
            "PC Engine CD/TurboGrafx-CD", "PC-FX", "PlayStation", "PlayStation 2",
            "PlayStation Portable", "Pokemon Mini", "32X", "Sega CD", "Saturn", "SG-1000",
            "SNES/Super Famicom", "Vectrex", "Virtual Boy", "Wii", "WonderSwan",
        ).withIndex().associate { (index, name) -> index + 1 to name }
        expectedSupported.forEach { platformId ->
            assertTrue(
                "$platformId must resolve at least one current public RA console identity",
                RetroAchievementsConsoleMap.consoleIdsFor(currentPublicConsoleNames, platformId).isNotEmpty(),
            )
        }
    }
}
