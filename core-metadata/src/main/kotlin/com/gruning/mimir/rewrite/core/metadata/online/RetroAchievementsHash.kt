package com.gruning.mimir.rewrite.core.metadata.online

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Computes the MD5 RetroAchievements actually matches a ROM on — which is **not** the file's MD5 for
 * several systems.
 *
 * RA's `rc_hash` skips non-canonical headers before hashing, because the same game dumped by two tools
 * differs only in header bytes. Verified against RA's own game-identification table (2026-07-18):
 *
 *  - **NES** — if the file starts with `NES\x1a`, skip 16 bytes (the iNES header), else hash whole.
 *  - **SNES** — if the size is exactly 512 bytes more than a multiple of 8 KiB, skip 512 (copier header).
 *  - **Atari 7800** — if the file starts with `\x01ATARI7800`, skip 128, else hash whole.
 *  - **Atari Lynx** — if the file starts with `LYNX\0`, skip 64, else hash whole.
 *  - **PC Engine** — if the file-size 512-bit is set, skip 512, matching current `rcheevos`.
 *  - **N64** — identify byte order from the ROM magic and normalize `.v64`/`.n64` to big-endian.
 *  - **Arcade / Neo Geo cartridge** — hash the case-sensitive filename without its extension.
 *  - everything else in [PLAIN] — plain whole-file MD5.
 *
 * The 13 random-access systems use pinned upstream `rcheevos` instead of reimplementing those algorithms:
 * 3DO, PS1/2/PSP, Saturn, Sega CD, PC Engine CD, PC-FX, Neo Geo CD, Dreamcast, GameCube/Wii and Nintendo DS.
 * CD CHDs are decoded locally by pinned `libchdr`. These formats require a real seekable path; Mimir never
 * copies a multi-gigabyte SAF document merely to manufacture one. File/container capability is therefore
 * checked separately with [supportsFile], and unsupported compressed formats fail before any lookup.
 *
 * ## Zipped ROMs
 *
 * Most shipped sets store one ROM per `.zip`, and RA hashes the ROM **inside** it — the archive is packaging,
 * not content. When [fileName] is a `.zip` this class walks the archive and, if **exactly one** entry carries
 * a ROM extension for that platform, runs the per-platform rule above over that entry's bytes. Any other
 * shape (zero ROM entries, two or more) returns null: with more than one candidate there is no honest way to
 * pick, and a wrong pick is a wrong match. Directory entries never count.
 *
 * Measured on the sampled RP5 card (2026-07-18, 2067 zipped ROMs) before this was written:
 *  - **The convention holds.** Of 789 SNES titles present as BOTH `X.zip` and a loose `X.sfc`, 759 hashed
 *    byte-identically. All 30 exceptions were explained, none of them by the extraction: 11 are archives that
 *    are genuinely damaged on the card (the device's own `unzip -t` reports "bad CRC" / "Inconsistent
 *    information"), and 19 are cases where the zip simply holds a *different dump* of the same title than the
 *    loose file — there the extracted bytes still match the CRC-32 the zipper recorded, so extraction was
 *    exact and only the two dumps differ. Across the 778 readable pairs, extraction was byte-exact 778/778.
 *  - **Refusing multi-entry costs almost nothing.** GBA 1067/1067 and SNES 997/1000 hold a single entry; one
 *    of the three SNES outliers is a directory entry plus one `.sfc` (so skipping directories recovers it),
 *    leaving exactly **2 files in 2067** refused — a romhack bundle and a PC game bundle, neither of which
 *    has a single unambiguous ROM anyway.
 *
 * The two things a zip changes, and both are load-bearing:
 *  1. **Size comes from the ENTRY, not the container.** The SNES/PC-Engine copier rules are size-derived, and
 *     a compressed container's size tells you nothing about the ROM. 133 SNES zips on that card hold a
 *     copier-format `.smc`/`.swc`, 94 of them genuinely headered — and for **all 133** the *container* size
 *     fails the `% 8 KiB == 512` test. Feeding the container size in would silently skip 0 bytes and hash the
 *     copier header along with the ROM, producing 94 confidently wrong hashes.
 *  2. **The candidate extension comes from the ENTRY, not the container.** `Mario.zip` holding `Mario.z64`
 *     must be admitted as one N64 ROM before its byte order is read from the ROM magic.
 *
 * A zip written in streaming mode (general-purpose bit 3) defers sizes to a trailing data descriptor, so
 * `ZipEntry.size` reads -1 from the local header [ZipInputStream] parses. None of the 2067 do this, but when
 * it happens the size-derived rules cannot run and this returns null rather than assuming an unheadered ROM.
 * `java.util.zip` also verifies each entry's CRC-32 as the data is consumed, so a damaged archive throws
 * rather than yielding plausible-looking bytes; that becomes null too. `.7z` is a different opaque
 * container that `java.util.zip` cannot read and is rejected before it can fall through to loose-ROM MD5.
 *
 * The whole class is pure + streaming (fixed buffer, never loads the ROM into memory — the zip path inflates
 * through the same buffer), so it is safe over SAF for large files and is unit-tested off-device. It reads
 * bytes and returns a hex digest — it never touches the network and never writes.
 */
internal object RetroAchievementsHash {

    /** Systems whose RA hash is a plain whole-file MD5. Keyed by Mimir's lowercase platform ids. */
    private val PLAIN = setOf(
        "amstradcpc",
        "atari2600", "atarijaguar",
        "colecovision",
        "gb", "gbc", "gba",
        "genesis", "mastersystem", "gamegear", "sg1000", "sega32x",
        "intellivision", "msx", "vectrex",
        "ngp",
        "virtualboy", "wonderswan", "pokemini",
    )

    private const val BUFFER = 1 shl 16 // 64 KiB, matching FileHasher

    private val INES_MAGIC = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) // "NES\x1a"
    private val FDS_MAGIC = byteArrayOf(0x46, 0x44, 0x53, 0x1A) // "FDS\x1a"
    // "LYNX\0" - the fifth byte is NUL. Spelled as an explicit byte so no raw NUL ever sits in
    // source: a literal one is invisible in a diff and silently breaks grep/sed over this file.
    private val LYNX_MAGIC = "LYNX".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    private val A7800_MAGIC = byteArrayOf(0x01) + "ATARI7800".toByteArray(Charsets.US_ASCII)

    private const val SNES_BLOCK = 8 * 1024
    private const val COPIER_HEADER = 512
    private val OPAQUE_ARCHIVE_EXTENSIONS = setOf("7z")

    /** Product registry ids → the compact rule ids used in this file. */
    private val PLATFORM_ALIASES = mapOf(
        "amstrad_cpc" to "amstradcpc",
        "atari_2600" to "atari2600",
        "atari_7800" to "atari7800",
        "atari_jaguar" to "atarijaguar",
        "atari_lynx" to "atarilynx",
        "bandai_wonderswan" to "wonderswan",
        "bandai_wonderswan_color" to "wonderswan",
        "coleco_colecovision" to "colecovision",
        "gce_vectrex" to "vectrex",
        "mattel_intellivision" to "intellivision",
        "microsoft_msx" to "msx",
        "microsoft_msx2" to "msx",
        "nec_pc_engine_supergrafx" to "pcengine",
        "nec_pc_engine_turbografx_16" to "pcengine",
        "neo_geo" to "arcade",
        "snk_neo_geo" to "arcade",
        "nintendo_pokemon_mini" to "pokemini",
        "nintendo_virtual_boy" to "virtualboy",
        "sega_32x" to "sega32x",
        "sega_game_gear" to "gamegear",
        "sega_master_system_mark_iii" to "mastersystem",
        "sega_sg_1000" to "sg1000",
        "snk_neo_geo_pocket" to "ngp",
        "snk_neo_geo_pocket_color" to "ngp",

        // Seek/disc product ids and the historical runtime ids still accepted at this boundary.
        "wii" to "nintendo_wii",
        "segacd" to "sega_mega_cd_sega_cd",
        "pcenginecd" to "nec_pc_engine_cd_turbografx_cd",
        "pcfx" to "nec_pc_fx",
        "neogeocd" to "snk_neo_geo_cd",
        "3do" to "the_3do_company_3do",

        // Earlier runtime ids remain valid at this module boundary during canonical rollout.
        "megadrive" to "genesis",
        "wonderswancolor" to "wonderswan",
        "ngpc" to "ngp",
    )

    /**
     * ROM extensions per platform, used **only** to find the one real ROM inside a zip — loose files are
     * dispatched on [platformId] and never consult this. Keys are exactly the platforms this class can hash,
     * so [supports] is derived from it and the two cannot drift apart.
     *
     * Includes every container extension for which the corresponding rule is implemented. N64's three byte
     * orders are all normalized; a bad magic or incomplete word still fails closed.
     */
    private val ROM_EXTENSIONS: Map<String, Set<String>> = mapOf(
        "amstradcpc" to setOf("dsk"),
        // Arcade is filename-hashed and deliberately never opens the ZIP as a ROM container.
        "arcade" to emptySet(),
        "gb" to setOf("gb"),
        "gbc" to setOf("gbc", "gb"),
        "gba" to setOf("gba"),
        "nes" to setOf("nes", "fds", "unf", "unif"),
        // sfc/smc/swc/fig all observed or standard; smc+swc are the copier formats the size rule exists for.
        "snes" to setOf("sfc", "smc", "swc", "fig"),
        "n64" to setOf("z64", "n64", "v64", "ndd", "d64"),
        "genesis" to setOf("md", "gen", "bin", "smd"),
        "mastersystem" to setOf("sms"),
        "gamegear" to setOf("gg"),
        "sg1000" to setOf("sg"),
        "sega32x" to setOf("32x", "bin", "md", "smd"),
        "atari2600" to setOf("a26", "bin"),
        "atari7800" to setOf("a78"),
        "atarijaguar" to setOf("abs", "bin", "cof", "j64", "jag", "rom"),
        "atarilynx" to setOf("lnx"),
        "pcengine" to setOf("pce"),
        "colecovision" to setOf("bin", "col", "rom"),
        "intellivision" to setOf("bin", "int", "rom"),
        "msx" to setOf("cas", "dsk", "mx1", "mx2", "rom"),
        "vectrex" to setOf("bin", "gam", "vec"),
        "ngp" to setOf("ngp", "ngc", "ngpc"),
        "virtualboy" to setOf("bin", "vb", "vboy"),
        "wonderswan" to setOf("ws", "wsc"),
        "pokemini" to setOf("min"),
    )

    private data class SeekReader(val consoleId: Int, val extensions: Set<String>)

    /**
     * Exact current `rcheevos` console numbers and the containers its reader can identify honestly.
     * Compressed formats needing a separate decoder (CDI/CSO/GCZ/RVZ/WBFS/WIA) stay out of this table.
     */
    private val SEEK_READERS: Map<String, SeekReader> = mapOf(
        "dreamcast" to SeekReader(40, setOf("cue", "gdi", "chd", "m3u")),
        "ds" to SeekReader(18, setOf("nds")),
        "gamecube" to SeekReader(16, setOf("iso", "gcm")),
        "nintendo_wii" to SeekReader(19, setOf("iso", "wad")),
        "ps1" to SeekReader(12, setOf("cue", "bin", "iso", "chd", "m3u")),
        "ps2" to SeekReader(21, setOf("cue", "bin", "iso", "chd", "m3u")),
        "psp" to SeekReader(41, setOf("iso", "pbp", "chd")),
        "saturn" to SeekReader(39, setOf("cue", "bin", "iso", "chd", "m3u")),
        "nec_pc_engine_cd_turbografx_cd" to SeekReader(76, setOf("cue", "chd", "m3u")),
        "nec_pc_fx" to SeekReader(49, setOf("cue", "chd", "m3u")),
        "sega_mega_cd_sega_cd" to SeekReader(9, setOf("cue", "bin", "iso", "chd", "m3u")),
        "snk_neo_geo_cd" to SeekReader(56, setOf("cue", "bin", "iso", "chd", "m3u")),
        "the_3do_company_3do" to SeekReader(43, setOf("cue", "bin", "iso", "chd", "m3u")),
    )

    /**
     * The RA hash for [platformId], or **null when the implementation cannot compute it reliably** — an unported
     * identifier, a file too short to carry the header its rule tests, or a zip that does not hold exactly
     * one ROM. Callers must distinguish capability with [supports] before interpreting null as a file miss.
     *
     * [fileName] and [sizeBytes] describe whatever [input] is. When that is a `.zip`, this switches to the
     * single-ROM-entry walk and both are replaced by the **entry's** name and uncompressed size — passing the
     * archive's own would break the N64 and copier-header rules. See the class KDoc for the measurements.
     *
     * Closes [input].
     */
    fun hash(platformId: String, fileName: String, sizeBytes: Long, input: InputStream): String? {
        val platform = canonicalHashPlatform(platformId)
        return input.use { stream ->
            if (platform == null) null
            else if (platform in SEEK_READERS) null // this forward-only seam cannot satisfy random access
            else if (platform == "arcade") hashArcadeName(fileName)
            else if (extensionOf(fileName) == "zip") hashSingleRomEntry(platform, stream)
            else if (extensionOf(fileName) in OPAQUE_ARCHIVE_EXTENSIONS) null
            else hashStream(platform, sizeBytes, stream)
        }
    }

    /** True when Mimir has ported RA's file-identification rule for [platformId]. */
    fun supports(platformId: String): Boolean = canonicalHashPlatform(platformId) != null

    /** True when the local reader also understands [fileName]'s container. */
    fun supportsFile(platformId: String, fileName: String): Boolean {
        val platform = canonicalHashPlatform(platformId) ?: return false
        if (platform != "arcade" && extensionOf(fileName) in OPAQUE_ARCHIVE_EXTENSIONS) return false
        val reader = SEEK_READERS[platform] ?: return true
        return extensionOf(fileName) in reader.extensions
    }

    /** True only for a supported random-access container that must be opened by path. */
    fun requiresLocalPath(platformId: String, fileName: String): Boolean {
        val platform = canonicalHashPlatform(platformId) ?: return false
        return platform in SEEK_READERS && supportsFile(platformId, fileName)
    }

    /** Canonical random-access identity, or null if the platform/container/path cannot be read honestly. */
    fun hashPath(platformId: String, fileName: String, localPath: String): String? {
        val platform = canonicalHashPlatform(platformId) ?: return null
        val reader = SEEK_READERS[platform] ?: return null
        if (extensionOf(fileName) !in reader.extensions) return null
        return RetroAchievementsNativeHash.hash(reader.consoleId, localPath)
    }

    internal fun nativeConsoleId(platformId: String): Int? =
        canonicalHashPlatform(platformId)?.let(SEEK_READERS::get)?.consoleId

    /**
     * The per-platform rule ladder over one already-open ROM stream. Never closes [stream] — the caller owns
     * it, and on the zip path it is the shared [ZipInputStream] that still has entries left to walk.
     *
     * [sizeBytes] describes **the ROM**, which inside a zip is the entry and not the archive.
     */
    private fun hashStream(
        platform: String,
        sizeBytes: Long,
        stream: InputStream,
    ): String? = when {
        platform in PLAIN -> skipOf(0, stream)
        platform == "nes" -> oneOfMagicSkip(stream, listOf(INES_MAGIC, FDS_MAGIC), 16)
        platform == "atari7800" -> magicSkip(stream, A7800_MAGIC, 128)
        platform == "atarilynx" -> magicSkip(stream, LYNX_MAGIC, 64)
        platform == "snes" -> skipOf(copierSkip(sizeBytes, SNES_BLOCK), stream)
        platform == "pcengine" -> skipOf(pcEngineHeaderSkip(sizeBytes), stream)
        platform == "n64" -> hashN64(stream)
        else -> null
    }

    /**
     * Hashes the single ROM entry inside a zip, or null when the archive is not that shape.
     *
     * [ZipInputStream] is forward-only, so "exactly one" cannot be checked before hashing: this hashes the
     * first ROM entry it meets and keeps walking, discarding the digest if a second one turns up. Walking on
     * is cheap — the remaining entries are only read as far as their headers.
     */
    private fun hashSingleRomEntry(platform: String, input: InputStream): String? {
        val romExtensions = ROM_EXTENSIONS[platform] ?: return null
        val zip = ZipInputStream(input)
        var digest: String? = null
        var romEntries = 0
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && extensionOf(entry.name) in romExtensions) {
                    romEntries++
                    // Two candidates and no honest way to choose between them.
                    if (romEntries > 1) return null
                    // Entry name + entry size, never the container's: both feed real rules. `size` is -1 when
                    // the zip defers it to a data descriptor, which copierSkip turns into an honest null.
                    digest = hashStream(platform, entry.size, zip)
                }
                zip.closeEntry()
            }
        } catch (_: IOException) {
            // Damaged archive (java.util.zip checks each entry's CRC-32 as it inflates) or a truncated read.
            // 29 of the 2067 ROMs on the sampled card are damaged, so this is a real path, not a formality.
            return null
        }
        return digest.takeIf { romEntries == 1 }
    }

    /** RA's Arcade identity is the case-sensitive basename, not the archive contents. */
    private fun hashArcadeName(fileName: String): String? {
        val basename = fileName.substringAfterLast('/').substringAfterLast('\\')
        val identity = basename.substringBeforeLast('.', basename).takeIf { it.isNotEmpty() } ?: return null
        return MessageDigest.getInstance("MD5").digest(identity.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** Normalizes current registry ids and the legacy ids accepted by the original RA slice. */
    private fun canonicalHashPlatform(platformId: String): String? {
        val raw = platformId.lowercase(Locale.US)
        val canonical = PLATFORM_ALIASES[raw] ?: raw
        return canonical.takeIf { it in ROM_EXTENSIONS || it in SEEK_READERS }
    }

    /** Lowercase extension with no dot, ignoring any directory part. Empty when there is none. */
    private fun extensionOf(name: String): String =
        name.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.US)

    /**
     * A copier header is present exactly when the size is [COPIER_HEADER] past a whole number of blocks, or
     * **null when the size is unknown** — a zip entry whose size lives in a trailing data descriptor reads
     * -1, and guessing "unheadered" there would hash 512 bytes of copier metadata into the digest.
     */
    private fun copierSkip(sizeBytes: Long, block: Int): Int? = when {
        sizeBytes < 0 -> null
        sizeBytes > COPIER_HEADER && sizeBytes % block == COPIER_HEADER.toLong() -> COPIER_HEADER
        else -> 0
    }

    /** Current rcheevos uses the 512 bit of the file size, which covers PCE and SuperGrafx dumps. */
    private fun pcEngineHeaderSkip(sizeBytes: Long): Int? = when {
        sizeBytes < 0 -> null
        sizeBytes and COPIER_HEADER.toLong() != 0L -> COPIER_HEADER
        else -> 0
    }

    /**
     * Reads enough of [stream] to test [magic]; skips [headerBytes] when it matches, hashes whole when it
     * does not. Returns null if the file is shorter than the header it claims to carry — a truncated file
     * has no honest hash.
     */
    private fun magicSkip(stream: InputStream, magic: ByteArray, headerBytes: Int): String? {
        return oneOfMagicSkip(stream, listOf(magic), headerBytes)
    }

    /** Same as [magicSkip], accepting any canonical header in [magics] (NES and FDS share one rule). */
    private fun oneOfMagicSkip(
        stream: InputStream,
        magics: List<ByteArray>,
        headerBytes: Int,
    ): String? {
        val head = ByteArray(headerBytes)
        val read = readFully(stream, head)
        val shortestMagic = magics.minOfOrNull { it.size } ?: return null
        if (read < shortestMagic) return null
        val matches = magics.any { magic ->
            read >= magic.size && magic.indices.all { head[it] == magic[it] }
        }
        if (!matches) return digestOf(head, read, stream)
        // Header confirmed but the file ended inside it: nothing left to identify the ROM by.
        if (read < headerBytes) return null
        return digestOf(ByteArray(0), 0, stream)
    }

    /**
     * N64's canonical identity is big-endian. The first byte is the format discriminator used by rcheevos:
     * 0x80 = z64, 0x37 = 16-bit byte-swapped v64, 0x40 = 32-bit little-endian n64, while 0xE8/0x22 are
     * 64DD forms that are already hashed as stored.
     */
    private fun hashN64(stream: InputStream): String? {
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER)
        var order: N64ByteOrder? = null
        var total = 0L
        while (true) {
            val read = readFully(stream, buffer)
            if (read == 0) break
            if (order == null) {
                order = when (buffer[0].toInt() and 0xFF) {
                    0x80, 0xE8, 0x22 -> N64ByteOrder.NATIVE
                    0x37 -> N64ByteOrder.SWAP_16
                    0x40 -> N64ByteOrder.SWAP_32
                    else -> return null
                }
            }
            when (val byteOrder = order ?: return null) {
                N64ByteOrder.NATIVE -> Unit
                N64ByteOrder.SWAP_16 -> if (!swapWords(buffer, read, 2)) return null
                N64ByteOrder.SWAP_32 -> if (!swapWords(buffer, read, 4)) return null
            }
            md5.update(buffer, 0, read)
            total += read
            if (read < buffer.size) break
        }
        return if (total > 0L) md5.digest().toHex() else null
    }

    private fun swapWords(buffer: ByteArray, length: Int, wordBytes: Int): Boolean {
        if (length % wordBytes != 0) return false
        var offset = 0
        while (offset < length) {
            var left = offset
            var right = offset + wordBytes - 1
            while (left < right) {
                val value = buffer[left]
                buffer[left] = buffer[right]
                buffer[right] = value
                left++
                right--
            }
            offset += wordBytes
        }
        return true
    }

    private enum class N64ByteOrder { NATIVE, SWAP_16, SWAP_32 }

    /** Skip [headerBytes] from the front, then digest the remainder. Null in propagates null out. */
    private fun skipOf(headerBytes: Int?, stream: InputStream): String? {
        if (headerBytes == null) return null
        if (headerBytes > 0) {
            val header = ByteArray(headerBytes)
            if (readFully(stream, header) < headerBytes) return null
        }
        return digestOf(ByteArray(0), 0, stream)
    }

    /** MD5 over [prefixLen] bytes of [prefix] followed by the rest of [stream]. */
    private fun digestOf(prefix: ByteArray, prefixLen: Int, stream: InputStream): String {
        val md5 = MessageDigest.getInstance("MD5")
        if (prefixLen > 0) md5.update(prefix, 0, prefixLen)
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            md5.update(buffer, 0, read)
        }
        return md5.digest().toHex()
    }

    private fun readFully(stream: InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val read = stream.read(into, total, into.size - total)
            if (read < 0) break
            if (read == 0) {
                val one = stream.read()
                if (one < 0) break
                into[total++] = one.toByte()
            } else {
                total += read
            }
        }
        return total
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
