package com.gruning.mimir.rewrite.core.metadata

/** A key extracted from a file, ready for an [IdCatalog] lookup (or null if none applies). */
data class ExtractedId(val keyType: IdKeyType, val key: String)

/**
 * Reads the non-hash identity key a platform is matched by, cheaply and offline — no decryption, no full
 * decode:
 *  - **arcade**: the MAME rom-zip basename IS the canonical shortname (`.../robotbwl.zip` -> `robotbwl`).
 *  - **switch**: the 16-hex Title ID carried in the filename (the shipping build's proven method), folded
 *    to the base application id so an update/DLC still resolves to the game.
 *  - **gamecube / wii**: the 6-char disc Game ID at offset 0 of the raw disc header — present as ASCII at
 *    the very start of a `.iso`, and (uncompressed) near the head of a `.rvz`/`.wia` container.
 *  - **ps3**: the 9-character TITLE_ID in a local `PARAM.SFO`, or the strict PS3 title ID carried by an
 *    ISO9660 primary-volume identifier at sector 16, with a filename-serial candidate for ISO/package names.
 *  - **3ds**: the 4-char product code in partition 0's NCCH header, reached through the NCSD partition
 *    table (QA-17 — 16.14 GB of the sampled library was being hashed whole for want of this).
 *  - **ds**: the 4-char game code at offset 0x0C of the cart header, validated against the title field.
 *
 * Filename-based extraction takes the display name; header-based extraction takes the first bytes of the
 * file. Pure — the caller supplies the bytes / name.
 */
object IdExtractor {

    private val HEX16 = Regex("[0-9a-fA-F]{16}")
    private val GAME_ID = Regex("^[A-Z0-9]{4,6}$")
    private val PS3_FILENAME_TITLE_ID =
        Regex("(?<![A-Z0-9])([A-Z]{4}[0-9]{5})(?![A-Z0-9])", RegexOption.IGNORE_CASE)
    private val PS3_PARAM_TITLE_ID = Regex("[A-Z0-9]{9}")

    /** The identity key for [platformId] from a display file name (no header bytes needed). */
    fun fromName(platformId: String, fileName: String): ExtractedId? {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        return when (platformId) {
            "arcade" -> base.substringBeforeLast('.').trim().lowercase()
                .takeIf { it.isNotBlank() }?.let { ExtractedId(IdKeyType.NAME, it) }
            "switch" -> switchBaseTitleId(base)?.let { ExtractedId(IdKeyType.TITLE_ID, it) }
            "ps3" -> ps3TitleIdFromName(base)?.let { ExtractedId(IdKeyType.SERIAL, it) }
            else -> null
        }
    }

    /** The identity key for [platformId] from the file's head bytes (GameCube/Wii disc Game ID). */
    fun fromHeader(platformId: String, head: ByteArray, len: Int): ExtractedId? = when (platformId) {
        "gamecube", "wii" -> discGameId(head, len)?.let { ExtractedId(IdKeyType.GAME_ID, it) }
        "3ds" -> threeDsProductCode(head, len)?.let { ExtractedId(IdKeyType.GAME_ID, it) }
        "ds" -> dsGameCode(head, len)?.let { ExtractedId(IdKeyType.GAME_ID, it) }
        "ps3" -> (
            paramSfoTitleId(head, len)
                ?: ps3IsoVolumeTitleId(head, len)
            )?.let { ExtractedId(IdKeyType.SERIAL, it) }
        else -> null
    }

    /** The 16-hex Title ID from a Switch filename, folded to the base application id (…low 12 bits = 000). */
    fun switchBaseTitleId(fileName: String): String? {
        val ids = HEX16.findAll(fileName).map { it.value.uppercase() }.toList()
        if (ids.isEmpty()) return null
        // Prefer a base id (ends 000); else fold whichever we found to its base program id.
        val chosen = ids.firstOrNull { it.endsWith("000") } ?: ids.first()
        return chosen.take(13) + "000"
    }

    /** A PS3 TITLE_ID already present in a local filename, for example `[BLUS30443]`. */
    fun ps3TitleIdFromName(fileName: String): String? =
        PS3_FILENAME_TITLE_ID.find(fileName)?.groupValues?.get(1)?.uppercase()

    /**
     * Reads TITLE_ID from Sony's little-endian PSF/SFO table. Bounds, table count, key and payload length
     * are all validated before a byte is read; malformed or truncated metadata fails closed.
     */
    fun paramSfoTitleId(bytes: ByteArray, len: Int = bytes.size): String? {
        val limit = len.coerceIn(0, bytes.size)
        if (limit < SFO_HEADER_BYTES || !startsWith(bytes, SFO_MAGIC)) return null
        if (littleU32(bytes, 4, limit) != SFO_VERSION_1_1) return null
        val keyTable = littleU32(bytes, 8, limit) ?: return null
        val dataTable = littleU32(bytes, 12, limit) ?: return null
        val entries = littleU32(bytes, 16, limit) ?: return null
        val indexEnd = SFO_HEADER_BYTES.toLong() + entries.toLong() * SFO_INDEX_BYTES
        if (
            entries > SFO_MAX_ENTRIES || indexEnd > limit ||
            keyTable < indexEnd || keyTable >= limit || dataTable < keyTable || dataTable >= limit
        ) {
            return null
        }
        for (index in 0 until entries) {
            val at = SFO_HEADER_BYTES + index * SFO_INDEX_BYTES
            if (at < 0 || at + SFO_INDEX_BYTES > limit) return null
            val keyOffset = littleU16(bytes, at, limit) ?: return null
            val valueFormat = littleU16(bytes, at + 2, limit) ?: return null
            val dataLength = littleU32(bytes, at + 4, limit) ?: return null
            val dataMaxLength = littleU32(bytes, at + 8, limit) ?: return null
            val dataOffset = littleU32(bytes, at + 12, limit) ?: return null
            if (dataLength <= 0 || dataLength > dataMaxLength || dataMaxLength > SFO_MAX_VALUE_BYTES) continue
            val keyAt = checkedAdd(keyTable, keyOffset, limit) ?: return null
            val key = nulTerminatedAscii(bytes, keyAt, limit, SFO_MAX_KEY_BYTES) ?: continue
            if (key != "TITLE_ID") continue
            if (valueFormat != SFO_UTF8_STRING) return null
            val valueAt = checkedAdd(dataTable, dataOffset, limit) ?: return null
            if (dataLength > limit - valueAt) return null
            val decoded = String(bytes, valueAt, dataLength, Charsets.UTF_8)
            val raw = decoded.removeSuffix("\u0000")
            // Only the format's terminal NUL is structural. Whitespace or an embedded NUL must not turn
            // malformed evidence into a plausible title id.
            if ('\u0000' in raw) return null
            return raw.uppercase().takeIf { PS3_PARAM_TITLE_ID.matches(it) }
        }
        return null
    }

    /**
     * Reads a PS3 disc title id from the ISO9660 primary-volume descriptor.
     *
     * Sector 16 begins with descriptor type 1, `CD001`, version 1. The fixed 32-byte volume identifier at
     * offset 40 is space-padded; only a strict four-letter/five-digit PS3 id is admitted. Arbitrary ISO labels
     * remain no evidence, and a truncated/malformed descriptor fails closed.
     */
    fun ps3IsoVolumeTitleId(bytes: ByteArray, len: Int = bytes.size): String? {
        val limit = len.coerceIn(0, bytes.size)
        val descriptor = ISO9660_SECTOR_BYTES * ISO9660_PRIMARY_VOLUME_SECTOR
        val volumeIdAt = descriptor + ISO9660_VOLUME_ID_OFFSET
        if (limit < volumeIdAt + ISO9660_VOLUME_ID_BYTES) return null
        if ((bytes[descriptor].toInt() and 0xff) != ISO9660_PRIMARY_VOLUME_TYPE) return null
        if (!matchesAt(bytes, descriptor + 1, ISO9660_STANDARD_ID)) return null
        if ((bytes[descriptor + 6].toInt() and 0xff) != ISO9660_VERSION_1) return null
        val raw = String(
            bytes,
            volumeIdAt,
            ISO9660_VOLUME_ID_BYTES,
            Charsets.US_ASCII,
        ).trimEnd(' ')
        return raw.takeIf(PS3_DISC_TITLE_ID::matches)
    }

    // "RVZ\x01" / "WIA\x01" container magic. The `wia_disc_t` struct follows the 0x48-byte `wia_file_head_t`
    // directly (it is NOT pointed to by an offset — offset 0x24 is iso_file_size, not a pointer). Within
    // wia_disc_t, disc_type/compression/compression_level/chunk_size (4×u32 = 16 B) precede the verbatim
    // disc_header[0x80] — so the Game ID sits at 0x48 + 16 = 0x58. (Dolphin docs/WiaAndRvz.md.)
    // ── NDS cart header, see [dsGameCode] ────────────────────────────────────────────────────────
    private const val DS_TITLE_BYTES = 12
    private const val DS_GAME_CODE = 0x0C
    private const val DS_GAME_CODE_BYTES = 4

    // ── 3DS NCSD/NCCH, see [threeDsProductCode] ──────────────────────────────────────────────────
    private const val NCSD_MAGIC_OFFSET = 0x100
    private const val NCSD_PARTITION_TABLE = 0x120
    private const val NCSD_MEDIA_UNIT = 0x200L
    private const val NCCH_MAGIC_OFFSET = 0x100L
    private const val NCCH_PRODUCT_CODE = 0x150L
    private const val NCCH_PRODUCT_CODE_BYTES = 0x10
    private val NCSD_MAGIC = "NCSD".toByteArray(Charsets.US_ASCII)
    private val NCCH_MAGIC = "NCCH".toByteArray(Charsets.US_ASCII)

    /**
     * `CTR-P-AQEE` (retail) and `CTR-N-XXXX` (digital/eShop) are the two shapes retail dumps carry; the
     * trailing region letter is part of the key, because GameTDB and No-Intro both distinguish regions by
     * it. Anchored at the start so a product code embedded in surrounding padding cannot drift the match.
     */
    private val THREE_DS_PRODUCT_CODE = Regex("^CTR-[PN]-([A-Z0-9]{4})")

    private val RVZ_MAGIC = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'Z'.code.toByte(), 0x01)
    private val WIA_MAGIC = byteArrayOf('W'.code.toByte(), 'I'.code.toByte(), 'A'.code.toByte(), 0x01)
    private const val RVZ_GAME_ID_OFFSET = 0x58

    /**
     * The GameCube/Wii disc Game ID (e.g. "GZLE01") = game_code[0x00..0x03] + maker_code[0x04..0x05] of the
     * raw disc header. A `.iso`/`.gcm` starts with it directly. A `.rvz`/`.wia` copies the disc header
     * uncompressed inside `wia_disc_t`, with the Game ID at [RVZ_GAME_ID_OFFSET]. Returns the 6-char id, or null.
     */
    /**
     * 3DS is the single largest whole-file-hash cost in the sampled
     * library — 16.14 GB across 26 files, 43% of every byte the identity pass reads — because "3ds" is not
     * in `LibraryIdentityEnricher.idKeyedPlatforms` and therefore falls through to `RomFingerprinter`.
     * It has a payload-bound identifier a few kilobytes in, exactly like GameCube's disc Game ID.
     *
     * The key returned is the **four-character product code** (the `AQEE` in `CTR-P-AQEE`), not the NCSD
     * Media ID. Both plausible catalogue sources key on it: GameTDB's 3DS database uses the same
     * `<id>`-per-console scheme its GameCube/Wii (6-char) and PS3 (9-char) tables use, and No-Intro's 3DS
     * DAT carries the full `CTR-P-XXXX` serial. The Media ID is a fine identifier and has no catalogue.
     *
     * Every step is content-sniffed and table-driven, never assumed from the extension or from the usual
     * layout — the same discipline `RomFingerprinter` states for itself:
     *
     *  1. `NCSD` magic at 0x100, or this is not a 3DS card image.
     *  2. Partition 0's offset from the NCSD partition table at 0x120 (u32 LE, in 0x200-byte media units).
     *     The common answer is 0x4000, and a dump that says otherwise is followed rather than overruled.
     *  3. `NCCH` magic at that offset + 0x100, or the partition is not what the table claims.
     *  4. The 0x10-byte product code at + 0x150, of which `CTR-P-XXXX` / `CTR-N-XXXX` yields the key.
     *
     * Decrypted dumps (`*_dec`) keep the same NCSD/NCCH framing, which is why one reader serves both sets.
     */
    /**
     * The four-character game code from an NDS cart header — `APDP` for Pokémon Dash (Europe) — which is
     * exactly the key GameTDB's DS database uses.
     *
     * DS is 2.53 GB across **88 files** on the reference device, the largest
     * file count of any platform still on the whole-file hash path. Files matter here as much as bytes: the
     * enricher walks smallest-first, so 88 cart images are the games a player watches the counter move
     * through, and each one currently costs a full read.
     *
     * The NDS header has no magic string to sniff, so the structure is validated instead, on three fields
     * that are meaningless if this is not a cart header:
     *
     *  - 0x00 game title: 12 bytes of ASCII or NUL padding;
     *  - 0x0C game code: four A-Z0-9 characters;
     *  - 0x1E header CRC / 0x15C logo CRC would be the rigorous check, and are deliberately NOT used —
     *    a wrong key here is caught by the catalogue miss, which then falls through to the hash, so the
     *    cost of a loose check is a wasted lookup rather than a wrong identity.
     *
     * Unlike 3DS this needs no extra read: 0x10 is far inside the existing `HEAD_BYTES` budget.
     */
    fun dsGameCode(head: ByteArray, len: Int): String? {
        if (len < DS_GAME_CODE + DS_GAME_CODE_BYTES) return null
        if (!dsTitleFieldPlausible(head)) return null
        return asciiAt(head, DS_GAME_CODE, DS_GAME_CODE_BYTES)
    }

    /**
     * The 12-byte title at offset 0 is uppercase ASCII, digits, space, underscore or hyphen, NUL-padded.
     * Rejecting anything else is what stops a random file whose bytes 0x0C..0x0F happen to be four capital
     * letters from minting a confident game code.
     */
    private fun dsTitleFieldPlausible(head: ByteArray): Boolean {
        var sawPadding = false
        for (i in 0 until DS_TITLE_BYTES) {
            val c = head[i].toInt() and 0xFF
            if (c == 0x00) { sawPadding = true; continue }
            // Once padding starts the field is over; a byte after it means this is not a title field.
            if (sawPadding) return false
            val ok = c in 0x41..0x5A || c in 0x30..0x39 || c == 0x20 || c == 0x5F || c == 0x2D
            if (!ok) return false
        }
        return head[0].toInt() != 0x00
    }

    fun threeDsProductCode(head: ByteArray, len: Int): String? {
        if (len < NCSD_PARTITION_TABLE + 8) return null
        if (!magicAt(head, NCSD_MAGIC_OFFSET, NCSD_MAGIC)) return null
        val mediaUnits = le32(head, NCSD_PARTITION_TABLE) ?: return null
        if (mediaUnits <= 0L) return null
        val partition = mediaUnits * NCSD_MEDIA_UNIT
        // A partition offset past the head we were given is not a failure of the file, only of the budget:
        // returning null lets the caller fall back to the hash rather than assert a wrong key.
        val productCodeAt = partition + NCCH_PRODUCT_CODE
        if (productCodeAt + NCCH_PRODUCT_CODE_BYTES > len) return null
        if (!magicAt(head, (partition + NCCH_MAGIC_OFFSET).toInt(), NCCH_MAGIC)) return null
        val raw = latin1At(head, productCodeAt.toInt(), NCCH_PRODUCT_CODE_BYTES) ?: return null
        return THREE_DS_PRODUCT_CODE.find(raw)?.groupValues?.get(1)
    }

    fun discGameId(head: ByteArray, len: Int): String? {
        if (len < 6) return null
        // Raw image: game id is the first 6 ASCII chars.
        asciiAt(head, 0, 6)?.let { if (GAME_ID.matches(it)) return it }
        // RVZ/WIA container: the copied disc header's game id sits at a fixed offset past the file head.
        if (startsWith(head, RVZ_MAGIC) || startsWith(head, WIA_MAGIC)) {
            asciiAt(head, RVZ_GAME_ID_OFFSET, 6)?.let { if (GAME_ID.matches(it)) return it }
        }
        return null
    }

    private fun startsWith(buf: ByteArray, magic: ByteArray): Boolean {
        if (buf.size < magic.size) return false
        for (i in magic.indices) if (buf[i] != magic[i]) return false
        return true
    }

    private fun matchesAt(buf: ByteArray, at: Int, expected: ByteArray): Boolean {
        if (at < 0 || at + expected.size > buf.size) return false
        for (index in expected.indices) if (buf[at + index] != expected[index]) return false
        return true
    }

    private fun littleU16(buf: ByteArray, at: Int, limit: Int): Int? {
        if (at < 0 || at + 2 > limit) return null
        return (buf[at].toInt() and 0xff) or ((buf[at + 1].toInt() and 0xff) shl 8)
    }

    private fun littleU32(buf: ByteArray, at: Int, limit: Int): Int? {
        if (at < 0 || at + 4 > limit) return null
        val value = (buf[at].toLong() and 0xff) or
            ((buf[at + 1].toLong() and 0xff) shl 8) or
            ((buf[at + 2].toLong() and 0xff) shl 16) or
            ((buf[at + 3].toLong() and 0xff) shl 24)
        return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun checkedAdd(left: Int, right: Int, limit: Int): Int? {
        val sum = left.toLong() + right.toLong()
        return sum.takeIf { it in 0 until limit.toLong() }?.toInt()
    }

    private fun nulTerminatedAscii(buf: ByteArray, at: Int, limit: Int, maxBytes: Int): String? {
        if (at < 0 || at >= limit) return null
        val endLimit = (at + maxBytes).coerceAtMost(limit)
        var end = at
        while (end < endLimit && buf[end].toInt() != 0) {
            val c = buf[end].toInt() and 0xff
            if (c !in 0x20..0x7e) return null
            end++
        }
        if (end == endLimit || end == at) return null
        return String(buf, at, end - at, Charsets.US_ASCII)
    }

    /** Magic-byte check at an arbitrary offset; [startsWith]'s counterpart for headers that are not at 0. */
    private fun magicAt(buf: ByteArray, at: Int, magic: ByteArray): Boolean {
        if (at < 0 || at.toLong() + magic.size > buf.size) return false
        for (i in magic.indices) if (buf[at + i] != magic[i]) return false
        return true
    }

    /** Little-endian u32 as a Long, so a high-bit-set offset cannot come back negative. */
    private fun le32(buf: ByteArray, at: Int): Long? {
        if (at < 0 || at + 4 > buf.size) return null
        return (buf[at].toLong() and 0xFF) or
            ((buf[at + 1].toLong() and 0xFF) shl 8) or
            ((buf[at + 2].toLong() and 0xFF) shl 16) or
            ((buf[at + 3].toLong() and 0xFF) shl 24)
    }

    /**
     * A fixed-width field read as printable Latin-1 up to its first NUL, for fields that legitimately
     * contain punctuation. [asciiAt] deliberately accepts only A-Z0-9 and would reject `CTR-P-AQEE` on its
     * hyphens.
     */
    private fun latin1At(buf: ByteArray, at: Int, n: Int): String? {
        if (at < 0 || at.toLong() + n > buf.size) return null
        val sb = StringBuilder(n)
        for (i in at until at + n) {
            val c = buf[i].toInt() and 0xFF
            if (c == 0x00) break
            if (c < 0x20 || c > 0x7E) return null
            sb.append(c.toChar())
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun asciiAt(buf: ByteArray, at: Int, n: Int): String? {
        if (at < 0 || at + n > buf.size) return null
        val sb = StringBuilder(n)
        for (i in at until at + n) {
            val c = buf[i].toInt()
            if (c in 0x30..0x39 || c in 0x41..0x5A) sb.append(c.toChar()) else return null
        }
        return sb.toString()
    }

    private val SFO_MAGIC = byteArrayOf(0x00, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte())
    private const val SFO_HEADER_BYTES = 20
    private const val SFO_INDEX_BYTES = 16
    private const val SFO_VERSION_1_1 = 0x00000101
    private const val SFO_UTF8_STRING = 0x0204
    private const val SFO_MAX_ENTRIES = 4_096
    private const val SFO_MAX_KEY_BYTES = 64
    private const val SFO_MAX_VALUE_BYTES = 64 * 1024
    private const val ISO9660_SECTOR_BYTES = 2_048
    private const val ISO9660_PRIMARY_VOLUME_SECTOR = 16
    private const val ISO9660_PRIMARY_VOLUME_TYPE = 1
    private const val ISO9660_VERSION_1 = 1
    private const val ISO9660_VOLUME_ID_OFFSET = 40
    private const val ISO9660_VOLUME_ID_BYTES = 32
    private val ISO9660_STANDARD_ID = "CD001".toByteArray(Charsets.US_ASCII)
    private val PS3_DISC_TITLE_ID = Regex("[A-Z]{4}[0-9]{5}")
}
