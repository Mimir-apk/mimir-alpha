package com.gruning.mimir.rewrite.core.metadata

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.zip.ZipInputStream

/** DAT-oriented fingerprint plus byte-exact provenance when the untouched whole file was hashed. */
data class RomFingerprint(
    val datHash: ContentHash,
    val exactRawSha1: String?,
    val proof: RomFingerprintProof = RomFingerprintProof.CONTENT_HASH,
)

enum class RomFingerprintProof {
    CONTENT_HASH,
    TRANSFORMED_CONTENT_HASH,
    CHD_HEADER_CLAIM,
}

/**
 * Produces the [ContentHash] a DAT actually matches on — which is NOT always the whole-file hash. Different
 * dump formats fingerprint differently, and matching the sampled files against No-Intro / MAMERedump
 * requires reproducing each format's convention:
 *
 *  - **CHD** (`.chd`, magic `MComprHD`): the header exposes the combined SHA-1 that a `<disk>` DAT indexes,
 *    but that claim is only a candidate. Production authority is granted by [ChdPayloadVerifier] after
 *    libchdr recomputes the logical raw payload and agrees with the header's raw SHA-1.
 *  - **iNES** (`.nes`, magic `NES`): the 16-byte iNES header is non-canonical (tools write different
 *    mapper/padding bytes), so No-Intro's canonical (Headerless) hash is of the ROM *after* the header.
 *    Strip 16 bytes, then hash.
 *  - **everything else**: whole-file CRC32/MD5/SHA-1 ([FileHasher]) — the No-Intro convention for GB/GBC/
 *    GBA/NDS/3DS and for N64 `.z64` / Genesis `.bin` (already header-clean).
 *
 * Dispatch is magic-first (a `.chd` is a CHD whatever its platform folder), then the NES special-case, then
 * whole-file — all of it content-sniffed from the stream itself, never from [fileName]. Pure + offline;
 * streams once with a fixed head buffer. [platformId] is the resolver's classifier id.
 *
 * ## Zipped ROMs
 *
 * Most shipped sets store one ROM per `.zip` — measured on the sampled card: **2,067 zipped ROMs**,
 * including **all 1,067 GBA titles**, which have a bundled DAT they could never reach before this, because
 * a zip fingerprints as its own container (matching nothing). When [fileName] ends in `.zip`, this walks the
 * archive and, if **exactly one** entry carries a ROM extension known for [platformId], fingerprints THAT
 * entry's bytes exactly as it would a loose file of the same content. Any other shape — zero ROM-extension
 * entries, two or more, or a damaged archive — returns null: with two candidates there is no honest way to
 * choose, and a wrong pick is a wrong match. Directory entries never count as candidates.
 *
 * This mirrors the already-measured walk in `RetroAchievementsHash.kt` (commit `2b08176`), but is NOT a
 * copy of it — the two lanes differ in what they need from the entry:
 *  - RA's per-format rules are **extension- and size-derived** (SNES/PC-Engine copier headers keyed off
 *    size, N64 dispatch keyed off extension), so RA's zip walk must thread the ENTRY's name and size into
 *    those rules — using the container's would fold a copier header into the digest or refuse every zipped
 *    N64 dump (both measured bugs in a naive implementation).
 *  - This fingerprinter's own per-format rules are **content-sniffed** (CHD magic, N64 byte-order magic,
 *    Genesis `.smd` magic, NES `NES\x1a` magic) and never consult a name or a pre-declared size at all — so
 *    once the single ROM entry is found, [fingerprintStream] runs unmodified over its bytes. [fileName] is
 *    used ONLY to find that one entry (matching its extension against [ROM_EXTENSIONS]); nothing here reads
 *    `ZipEntry.size`, so the "streaming zip defers size to a trailing data descriptor" trap that RA's copier
 *    rule has to guard against does not arise here — there is nothing size-derived to feed it to.
 *
 * ## What `exactRawSha1` means for a zipped ROM: always null, deliberately
 *
 * [RomFingerprint.exactRawSha1] is a narrow, trusted claim: "hashing this file's bytes exactly as they sit
 * on disk, with zero transformation, produced this SHA-1" — [LibraryIdentityEnricher] persists it tagged
 * `raw-sha1:` and `GameDao.reconcileExactHashDuplicates` uses THAT tag to fold two different library rows
 * together as byte-identical duplicates, without re-reading either file. A zip entry's inflated bytes are
 * NOT the file on disk — the file on disk is the `.zip`, and reaching the entry required a lossless but
 * real transformation (CRC-verified decompression + entry selection), the same category of derivation as
 * the NES header strip or the N64 byte-swap, both of which already null this field. So the zip path forces
 * `exactRawSha1 = null` unconditionally, even on the default/whole-entry-hash branch that would otherwise
 * set it for a loose file. This does NOT lose the "same game, different packaging" case: a zipped and a
 * loose copy of the same ROM still collapse to one tile once both resolve against a DAT, via the existing
 * `datHash` → `collapseKey` path — the correct, already-existing channel for "these are the same game",
 * distinct from "these two files are the identical bytes I found lying on disk twice."
 *
 * Returns null only for a zip that doesn't resolve to exactly one honest ROM candidate; the loose-file path
 * below never returns null.
 */
object RomFingerprinter {
    /**
     * Version of [chdHeaderEvidence] as an identity parser. Stamped on every
     * [AthenaIdentityEvidence.CHD_HEADER_DECLARED_SHA1] artifact; bump it if the header reading changes.
     */
    const val CHD_HEADER_PARSER_VERSION: Int = 1


    private const val HEAD = 132 // enough for the CHD v5 sha1 (offset 84 + 20) and the iNES header (16)
    private val CHD_MAGIC = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'r'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte())
    private val INES_MAGIC = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) // "NES"

    /**
     * ROM extensions used ONLY to find the single real entry inside a zip — a loose file dispatches on
     * [platformId] alone (magic bytes; see [fingerprintStream]) and never consults this table.
     *
     * Scoped to platforms that actually reach this fingerprinter today: the cartridge systems in
     * `BundledDatManifest` — NOT gamecube/wii/switch/arcade, which `LibraryIdentityEnricher.idKeyedPlatforms`
     * resolves by id and never hands to [RomFingerprinter] at all, and NOT the CHD-only disc platforms,
     * whose DAT identity is already a
     * container-internal header — there is no measured evidence that real files double-wrap a `.chd` in a
     * `.zip`, and Redump's multi-track sets don't fit "one ROM per zip" regardless.
     *
     * `.smc`/`.swc`/`.fig` are included alongside `.sfc` for snes — a measured 13% of the sampled SNES zips
     * are copier-format dumps, and an entry table with only `.sfc` would silently refuse them. Note the
     * bundled SNES DAT is the No-Intro **headerless** convention (`BundledDatManifest`'s own comment: "all
     * `<rom>` entries are .sfc, sizes carry no +512 copier-header offset") and this fingerprinter has no
     * SNES-specific header strip — so a genuinely-headered `.smc`/`.swc` entry still won't MATCH the DAT
     * even once correctly extracted. That gap is pre-existing and identical for a loose headered file; this
     * table only makes the zip case reach the same honest outcome a loose file already gets, never worse.
     *
     * Cross-checked against `platforms/mimir-platform-registry.v2.json`'s per-platform
     * `contentContract.baseGameExtensions` (minus the `zip`/`7z` container markers themselves) and, where it
     * overlaps, against the already-measured `RetroAchievementsHash.ROM_EXTENSIONS` (`.z64`/`.n64`/`.v64` for
     * N64; `.md`/`.gen`/`.bin`/`.smd` for Genesis).
     */
    private val ROM_EXTENSIONS: Map<String, Set<String>> = mapOf(
        "nes" to setOf("nes"),
        "gb" to setOf("gb"),
        "gbc" to setOf("gbc", "gb"),
        "gba" to setOf("gba"),
        "snes" to setOf("sfc", "smc", "swc", "fig"),
        "n64" to setOf("z64", "n64", "v64"),
        "megadrive" to setOf("md", "gen", "bin", "smd"),
        "ds" to setOf("nds"),
        "3ds" to setOf("3ds"),
        "mastersystem" to setOf("sms"),
        "gamegear" to setOf("gg"),
        "sg1000" to setOf("sg"),
        "atari2600" to setOf("a26", "bin"),
        "atari7800" to setOf("a78"),
        "atarilynx" to setOf("lnx"),
        "pcengine" to setOf("pce"),
        "ngp" to setOf("ngp"),
        "ngpc" to setOf("ngc", "ngpc"),
        "virtualboy" to setOf("vb"),
        "wonderswan" to setOf("ws"),
        "wonderswancolor" to setOf("wsc"),
        "pokemini" to setOf("min"),
    )

    /** Compatibility surface for DAT consumers that do not need exact-hash provenance. */
    fun fingerprint(platformId: String, fileName: String, input: InputStream): ContentHash? =
        fingerprintWithProvenance(platformId, fileName, input)?.datHash

    /** Package-local archive selector seam. Names choose bytes to hash; they never become identity. */
    internal fun archiveExtensionsFor(platformId: String): Set<String>? = ROM_EXTENSIONS[platformId]

    /**
     * Fingerprint for DAT matching and disclose only a proven untouched whole-file SHA-1.
     *
     * [fileName] is consulted for exactly one thing: is this a `.zip`? A loose file's format is entirely
     * content-sniffed and never needs it. Closes [input] in every case — directly for a loose file, and via
     * the [ZipInputStream] it wraps for a zip.
     */
    fun fingerprintWithProvenance(platformId: String, fileName: String, input: InputStream): RomFingerprint? =
        if (extensionOf(fileName) == "zip") {
            fingerprintZipEntry(platformId, input)
        } else {
            input.use { stream -> fingerprintStream(platformId, stream) }
        }

    /**
     * Walks [input] as a zip looking for the single ROM entry [platformId] can identify, and fingerprints
     * it exactly as [fingerprintStream] would a loose file of the same bytes. Null for every shape that
     * isn't "exactly one honest candidate":
     *  - [platformId] has no entry in [ROM_EXTENSIONS] (not a platform this lane zip-matches at all).
     *  - zero entries carry a recognised ROM extension, or two or more do (no honest way to choose, and a
     *    wrong pick is a wrong DAT match — cheap to refuse: measured at 2 files out of 2,067 real archives).
     *  - the archive is damaged: `java.util.zip` verifies each entry's CRC-32 as it is consumed, so a
     *    corrupt zip throws rather than yielding plausible-looking bytes (measured at 29 of 2,067, 1.4%) —
     *    caught here and turned into an honest null, never a crash.
     *
     * [ZipInputStream] is forward-only, so "exactly one" can't be checked up front: this fingerprints the
     * first candidate it meets and keeps walking, discarding the result the instant a second one turns up.
     * Directory entries are skipped outright and never count as a candidate (recovers a ROM shipped
     * alongside an otherwise-empty directory entry).
     *
     * Owns and closes [input] via the [ZipInputStream] wrapping it.
     */
    private fun fingerprintZipEntry(platformId: String, input: InputStream): RomFingerprint? {
        val romExtensions = ROM_EXTENSIONS[platformId]
        if (romExtensions == null) {
            input.close()
            return null
        }
        ZipInputStream(input).use { zip ->
            var found: RomFingerprint? = null
            var romEntries = 0
            var entryCount = 0
            var expandedTotal = 0L
            val drainBuffer = ByteArray(ZIP_BUFFER_BYTES)
            try {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES || entry.name.toByteArray(Charsets.UTF_8).size > MAX_ZIP_NAME_BYTES) {
                        return null
                    }
                    if (entry.size > MAX_ZIP_ENTRY_BYTES) return null
                    val bounded = EntryBudgetInputStream(zip, MAX_ZIP_ENTRY_BYTES) { read ->
                        expandedTotal += read
                        if (expandedTotal > MAX_ZIP_TOTAL_BYTES) throw ZipBudgetExceeded()
                    }
                    if (!entry.isDirectory && extensionOf(entry.name) in romExtensions) {
                        romEntries++
                        // Two candidates and no honest way to pick between them.
                        if (romEntries > 1) return null
                        // The ENTRY's bytes, hashed by the exact same dispatch a loose file would get; the
                        // zip is a derivation, never "the untouched whole file" — see the class KDoc.
                        found = fingerprintStream(platformId, bounded).copy(exactRawSha1 = null)
                    }
                    while (bounded.read(drainBuffer) >= 0) {
                        // Drain under the same budgets so closeEntry never performs an unobserved expansion.
                    }
                    zip.closeEntry()
                    if (entry.size >= 0L && bounded.bytesRead != entry.size) return null
                    val compressed = entry.compressedSize
                    if (compressed == 0L && bounded.bytesRead > 0L) return null
                    if (
                        bounded.bytesRead >= MIN_ZIP_RATIO_CHECK_BYTES &&
                        compressed > 0L &&
                        bounded.bytesRead / compressed.coerceAtLeast(1L) > MAX_ZIP_RATIO
                    ) {
                        return null
                    }
                }
            } catch (_: IOException) {
                return null
            }
            return found.takeIf { romEntries == 1 }
        }
    }

    /**
     * The core per-format dispatch — provisional CHD header claim, N64 byte-order fix, Genesis `.smd`
     * deinterleave, NES header strip, else whole-file — over ONE already-open stream. Content-sniffed only;
     * never reads a name or a pre-declared size. Always returns a result, never null.
     *
     * Deliberately never closes [input]: a loose file is closed once by the top-level `use{}` in
     * [fingerprintWithProvenance], and a zip entry's stream is the shared [ZipInputStream] still walking
     * its siblings in [fingerprintZipEntry] — closing it here would end the whole archive read.
     */
    internal fun fingerprintArchiveStream(
        platformId: String,
        input: InputStream,
        checkpoint: () -> Unit = {},
    ): RomFingerprint = fingerprintStream(platformId, input, checkpoint).copy(exactRawSha1 = null)

    private fun fingerprintStream(
        platformId: String,
        input: InputStream,
        checkpoint: () -> Unit = {},
    ): RomFingerprint {
        val head = ByteArray(HEAD)
        var headLen = 0
        while (headLen < HEAD) {
            checkpoint()
            val r = input.read(head, headLen, HEAD - headLen)
            if (r < 0) break
            headLen += r
        }

        // CHD wins regardless of platform folder, but this is only its structurally validated header claim.
        if (startsWith(head, headLen, CHD_MAGIC)) {
            val chd = chdHeaderEvidence(head, headLen)
            if (chd != null) {
                return RomFingerprint(
                    datHash = ContentHash(sha1 = chd.combinedSha1, sizeBytes = chd.logicalBytes),
                    exactRawSha1 = null,
                    proof = RomFingerprintProof.CHD_HEADER_CLAIM,
                )
            }
            // Unknown CHD version: fall through to a whole-file hash (won't match a <disk> DAT, but honest).
        }

        // N64: No-Intro hashes the BigEndian (.z64) byte order. Normalize a byteswapped .v64 (16-bit) or
        // little-endian .n64 (32-bit) to .z64 before hashing so those files still match.
        if (platformId == "n64") {
            val unit = n64SwapUnit(head, headLen)
            if (unit > 0) {
                return hashTransformed(head, headLen, input, checkpoint) { bytes, check ->
                    byteSwap(bytes, unit, check)
                }
            }
        }

        // Genesis: a .smd is a 512-byte header + 16 KB even/odd-interleaved blocks. Redump/No-Intro catalog
        // the plain linear .bin — deinterleave a .smd before hashing. A .bin/.md has no header/interleave.
        if (platformId == "megadrive" && isSmd(head, headLen)) {
            return hashTransformed(head, headLen, input, checkpoint, ::smdToBin)
        }

        // NES: strip the non-canonical 16-byte iNES header, hash the ROM (matches the Headerless DAT).
        val skip = if (platformId == "nes" && startsWith(head, headLen, INES_MAGIC) && headLen >= 16) 16 else 0
        val reconstructed = SequenceInputStream(
            ByteArrayInputStream(head, skip, headLen - skip),
            NonClosingInputStream(input),
        )
        val hash = FileHasher.hash(CheckpointInputStream(reconstructed, checkpoint))
        return RomFingerprint(
            datHash = hash,
            exactRawSha1 = hash.sha1.takeIf { skip == 0 },
            proof = if (skip == 0) {
                RomFingerprintProof.CONTENT_HASH
            } else {
                RomFingerprintProof.TRANSFORMED_CONTENT_HASH
            },
        )
    }

    /** Read the full file (head + rest), apply [transform], hash the result. For the rare, bounded-size
     *  formats that need a whole-image byte transform (.v64/.n64 swap, .smd deinterleave). Does NOT close
     *  [input] — same contract as [fingerprintStream]; `readBytes()` reads to EOF of the current stream
     *  (the current zip entry, when called from the zip path) without closing it. */
    private fun hashTransformed(
        head: ByteArray,
        headLen: Int,
        input: InputStream,
        checkpoint: () -> Unit,
        transform: (ByteArray, () -> Unit) -> ByteArray,
    ): RomFingerprint {
        val remainingBudget = MAX_TRANSFORM_BYTES - headLen
        if (remainingBudget < 0) throw IOException("transformed image exceeds budget")
        val rest = input.readAtMost(remainingBudget)
            ?: throw IOException("transformed image exceeds budget")
        val full = ByteArray(headLen + rest.size)
        checkpoint()
        System.arraycopy(head, 0, full, 0, headLen)
        System.arraycopy(rest, 0, full, headLen, rest.size)
        return RomFingerprint(
            datHash = FileHasher.hash(
                CheckpointInputStream(
                    ByteArrayInputStream(transform(full, checkpoint)),
                    checkpoint,
                ),
            ),
            exactRawSha1 = null,
            proof = RomFingerprintProof.TRANSFORMED_CONTENT_HASH,
        )
    }

    /** 2 for a byteswapped .v64, 4 for a little-endian .n64, 0 for native .z64 / unknown (no swap needed). */
    private fun n64SwapUnit(head: ByteArray, len: Int): Int {
        if (len < 4) return 0
        val b = IntArray(4) { head[it].toInt() and 0xFF }
        return when {
            b[0] == 0x37 && b[1] == 0x80 && b[2] == 0x40 && b[3] == 0x12 -> 2 // .v64 (byteswapped)
            b[0] == 0x40 && b[1] == 0x12 && b[2] == 0x37 && b[3] == 0x80 -> 4 // .n64 (little-endian)
            else -> 0 // .z64 (80 37 12 40) or headered/unknown → hash as-is
        }
    }

    /** Reverse bytes within each [unit]-sized group (v64: 16-bit swap; n64: 32-bit word reverse → z64). */
    internal fun byteSwap(bytes: ByteArray, unit: Int): ByteArray =
        byteSwap(bytes, unit, checkpoint = {})

    private fun byteSwap(
        bytes: ByteArray,
        unit: Int,
        checkpoint: () -> Unit,
    ): ByteArray {
        checkpoint()
        val out = bytes.copyOf()
        var i = 0
        while (i + unit <= out.size) {
            if (i % TRANSFORM_CHECKPOINT_BYTES == 0) checkpoint()
            var lo = i
            var hi = i + unit - 1
            while (lo < hi) {
                val t = out[lo]; out[lo] = out[hi]; out[hi] = t
                lo++; hi--
            }
            i += unit
        }
        return out
    }

    /** A .smd (byte 8 = 0xAA, byte 9 = 0xBB after the block count) — the interleaved Genesis format. */
    private fun isSmd(head: ByteArray, len: Int): Boolean =
        len >= 10 && (head[8].toInt() and 0xFF) == 0xAA && (head[9].toInt() and 0xFF) == 0xBB

    /** Deinterleave a .smd to the linear .bin the DAT catalogs: drop the 512-byte header, then per 16 KB
     *  block interleave the first 8 KB (even output bytes) with the second 8 KB (odd output bytes). */
    internal fun smdToBin(bytes: ByteArray): ByteArray =
        smdToBin(bytes, checkpoint = {})

    private fun smdToBin(
        bytes: ByteArray,
        checkpoint: () -> Unit,
    ): ByteArray {
        if (bytes.size <= SMD_HEADER) return bytes
        checkpoint()
        val body = bytes.copyOfRange(SMD_HEADER, bytes.size)
        val out = ByteArray(body.size)
        var base = 0
        while (base + SMD_BLOCK <= body.size) {
            checkpoint()
            for (k in 0 until SMD_BLOCK / 2) {
                out[base + 2 * k] = body[base + k]
                out[base + 2 * k + 1] = body[base + SMD_BLOCK / 2 + k]
            }
            base += SMD_BLOCK
        }
        if (base < body.size) System.arraycopy(body, base, out, base, body.size - base) // trailing partial
        return out
    }

    private const val SMD_HEADER = 512
    private const val SMD_BLOCK = 16384
    private const val TRANSFORM_CHECKPOINT_BYTES = 64 * 1024

    private fun startsWith(buf: ByteArray, len: Int, magic: ByteArray): Boolean {
        if (len < magic.size) return false
        for (i in magic.indices) if (buf[i] != magic[i]) return false
        return true
    }

    /**
     * Reads and structurally validates a CHD header's claimed hashes. The combined SHA-1 is the `<disk
     * sha1>` lookup key, but this method never makes it authoritative; [ChdPayloadVerifier] must recompute
     * the logical raw payload first. Offsets are cross-confirmed against MAME chd.h and libchdr: v5 at 84,
     * v4 at 48, v3 at 80. Returns null for v1/v2, malformed, or truncated headers.
     */
    internal fun chdHeaderEvidence(head: ByteArray, len: Int): ChdHeaderEvidence? {
        val version = beU32(head, 12) ?: return null
        val layout = when (version) {
            3 -> ChdHeaderLayout(
                headerBytes = 120,
                logicalOffset = 28,
                hunkBytesOffset = 76,
                rawSha1Offset = 80,
                combinedSha1Offset = 80,
                parentSha1Offset = 100,
                compressionOffsets = listOf(20),
                declaredHunkCountOffset = 24,
            )
            4 -> ChdHeaderLayout(
                headerBytes = 108,
                logicalOffset = 28,
                hunkBytesOffset = 44,
                rawSha1Offset = 88,
                combinedSha1Offset = 48,
                parentSha1Offset = 68,
                compressionOffsets = listOf(20),
                declaredHunkCountOffset = 24,
            )
            5 -> ChdHeaderLayout(
                headerBytes = 124,
                logicalOffset = 32,
                hunkBytesOffset = 56,
                rawSha1Offset = 64,
                combinedSha1Offset = 84,
                parentSha1Offset = 104,
                compressionOffsets = listOf(16, 20, 24, 28),
                declaredHunkCountOffset = null,
            )
            else -> return null
        }
        if (len < layout.headerBytes || beU32(head, 8) != layout.headerBytes) return null
        val logicalBytes = beU64(head, layout.logicalOffset)?.takeIf { it > 0L } ?: return null
        val hunkBytes = beU32(head, layout.hunkBytesOffset)
            ?.takeIf { it in 1..MAX_CHD_HUNK_BYTES } ?: return null
        if (version == 5) {
            val unitBytes = beU32(head, 60)?.takeIf { it in 1..hunkBytes } ?: return null
            if (hunkBytes % unitBytes != 0) return null
        }
        val rawSha1 = sha1At(head, len, layout.rawSha1Offset) ?: return null
        val combinedSha1 = sha1At(head, len, layout.combinedSha1Offset) ?: return null
        val parentSha1 = sha1At(head, len, layout.parentSha1Offset, allowZero = true) ?: return null
        val compressionTags = layout.compressionOffsets.map { offset ->
            beU32(head, offset) ?: return null
        }.let { values ->
            values + List(4 - values.size) { 0 }
        }
        val derivedHunkCount = ((logicalBytes - 1L) / hunkBytes) + 1L
        val declaredHunkCount = layout.declaredHunkCountOffset?.let { offset ->
            (beU32(head, offset)?.toLong() ?: return null) and 0xffff_ffffL
        } ?: derivedHunkCount
        if (declaredHunkCount <= 0L) return null
        return ChdHeaderEvidence(
            version = version,
            logicalBytes = logicalBytes,
            hunkBytes = hunkBytes,
            rawSha1 = rawSha1,
            combinedSha1 = combinedSha1,
            parentSha1 = parentSha1.takeUnless(::isZeroSha1),
            compressionTags = compressionTags,
            declaredHunkCount = declaredHunkCount,
        )
    }

    private fun beU32(buf: ByteArray, at: Int): Int? {
        if (at + 4 > buf.size) return null
        return ((buf[at].toInt() and 0xFF) shl 24) or
            ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or
            (buf[at + 3].toInt() and 0xFF)
    }

    private fun beU64(buf: ByteArray, at: Int): Long? {
        if (at < 0 || at + 8 > buf.size || (buf[at].toInt() and 0x80) != 0) return null
        var value = 0L
        for (index in at until at + 8) value = (value shl 8) or (buf[index].toLong() and 0xff)
        return value
    }

    private fun sha1At(
        bytes: ByteArray,
        len: Int,
        offset: Int,
        allowZero: Boolean = false,
    ): String? {
        if (offset < 0 || len < offset + 20 || bytes.size < offset + 20) return null
        val value = buildString(40) {
            for (index in offset until offset + 20) {
                append(HEX[(bytes[index].toInt() ushr 4) and 0xf])
                append(HEX[bytes[index].toInt() and 0xf])
            }
        }
        return value.takeIf { allowZero || !isZeroSha1(it) }
    }

    private fun isZeroSha1(value: String): Boolean = value.all { it == '0' }

    /** Lowercase extension with no dot, ignoring any directory part. Empty when there is none. Used to spot
     *  a `.zip` container and, inside one, to match an entry's name against [ROM_EXTENSIONS]. */
    private fun extensionOf(name: String): String =
        name.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private val HEX = "0123456789abcdef".toCharArray()
    private const val MAX_CHD_HUNK_BYTES = 128 * 1024 * 1024
    private const val MAX_TRANSFORM_BYTES = 128 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 512
    private const val MAX_ZIP_NAME_BYTES = 512
    private const val MAX_ZIP_ENTRY_BYTES = 128L * 1024 * 1024
    private const val MAX_ZIP_TOTAL_BYTES = 256L * 1024 * 1024
    private const val MAX_ZIP_RATIO = 200L
    private const val MIN_ZIP_RATIO_CHECK_BYTES = 1024L * 1024
    private const val ZIP_BUFFER_BYTES = 32 * 1024

    private data class ChdHeaderLayout(
        val headerBytes: Int,
        val logicalOffset: Int,
        val hunkBytesOffset: Int,
        val rawSha1Offset: Int,
        val combinedSha1Offset: Int,
        val parentSha1Offset: Int,
        val compressionOffsets: List<Int>,
        val declaredHunkCountOffset: Int?,
    )
}

data class ChdHeaderEvidence(
    val version: Int,
    val logicalBytes: Long,
    val hunkBytes: Int,
    val rawSha1: String,
    val combinedSha1: String,
    val parentSha1: String?,
    val compressionTags: List<Int> = emptyList(),
    val declaredHunkCount: Long? = null,
)

/**
 * Blocks [FileHasher.hash]'s internal close from reaching a stream [fingerprintStream] doesn't own. A loose
 * file is closed exactly once, by the top-level `use{}` in [RomFingerprinter.fingerprintWithProvenance]; a
 * zip entry's stream is the shared [ZipInputStream] mid-walk in `fingerprintZipEntry` — closing it early
 * would throw on the very next `nextEntry()` call and break "keep walking to rule out a second candidate".
 * `ByteArrayInputStream.close()` is already a documented no-op, so only the real, possibly-shared stream
 * needs this guard.
 */
internal class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun close() {
        // Deliberately not delegated — see the class KDoc.
    }
}

private class CheckpointInputStream(
    private val delegate: InputStream,
    private val checkpoint: () -> Unit,
) : InputStream() {
    override fun read(): Int {
        checkpoint()
        return delegate.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkpoint()
        return delegate.read(b, off, len)
    }

    override fun close() = delegate.close()
}

private class EntryBudgetInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
    private val onRead: (Long) -> Unit,
) : InputStream() {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = delegate.read(b, off, len)
        if (read > 0) account(read.toLong())
        return read
    }

    private fun account(read: Long) {
        bytesRead += read
        if (bytesRead > maxBytes) throw ZipBudgetExceeded()
        onRead(read)
    }
}

private class ZipBudgetExceeded : IOException()

private fun InputStream.readAtMost(maxBytes: Int): ByteArray? {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (read > maxBytes - total) return null
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}
