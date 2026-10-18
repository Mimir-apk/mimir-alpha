package com.gruning.mimir.rewrite.core.storage.scan

/**
 * Decides whether two `source_roots` rows name the SAME physical directory.
 *
 * **Duplicate source registration inflated version counts.** `upsertSourceRoot` keys a root on `rootId = rootUri`, so the two
 * spellings of one folder —
 *
 *  - `content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs`
 *  - `/storage/6A7F-FCFE/ROMs`
 *
 * — never compared equal and both registered. Every ROM under that folder was then indexed twice, and
 * `memberCount`'s raw `COUNT(*)` reported the second copy of each file as a second *version*: 25
 * games wore a "2 versions" badge and not one of them had a variant. (The disc badge escaped only
 * because `discCount` counts `DISTINCT discNumber`.)
 *
 * **This is a COMPARISON KEY and nothing else.** It is never persisted, never written to `rootUri`, and
 * never used to rewrite an `entryKey` — entry keys embed the root URI string, and rekeying is the alias
 * subsystem's job, not this file's. All this does is let registration notice "I already have this
 * folder" and refresh the existing row instead of minting a rival.
 *
 * [FriendlyPath] cannot serve as this key even though it renders both spellings identically: it maps
 * EVERY removable volume to the words "SD card", so two different cards would collide. Volume identity
 * has to survive, which is why this returns a `/storage/<volume>/…` form rather than a label.
 */
object SourceRootIdentity {

    private const val EXTERNAL_STORAGE_TREE_PREFIX =
        "content://com.android.externalstorage.documents/tree/"

    /**
     * A canonical `/storage/…` form for [rootUri], or **null** when this root cannot be canonicalised
     * (a non-ExternalStorageProvider authority, a `home:` document, a relative path).
     *
     * Null means "no opinion", never "matches other unknowns" — see [namesSameDirectory].
     */
    fun canonicalOf(rootUri: String): String? {
        val trimmed = rootUri.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("content://")) {
            canonicalFromSafTree(trimmed)
        } else {
            canonicalFromFilePath(trimmed)
        }
    }

    /**
     * True when [left] and [right] name the same directory.
     *
     * Two roots that both fail to canonicalise fall back to an exact string comparison. They must NOT be
     * treated as equal on the strength of both being null — that would fold every unrecognised provider
     * into a single root and delete real sources (this tree has been bitten by nullness standing in for
     * a kind before).
     */
    fun namesSameDirectory(left: String, right: String): Boolean {
        if (left == right) return true
        val leftCanonical = canonicalOf(left) ?: return false
        val rightCanonical = canonicalOf(right) ?: return false
        return leftCanonical == rightCanonical
    }

    private fun canonicalFromSafTree(rootUri: String): String? {
        if (!rootUri.startsWith(EXTERNAL_STORAGE_TREE_PREFIX)) return null
        val encoded = rootUri.substringBefore('?').substringBefore('#')
            .substringAfter("/tree/", missingDelimiterValue = "")
            .substringBefore('/')
            .takeIf { it.isNotBlank() } ?: return null
        val documentId = percentDecodeUtf8(encoded) ?: return null
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        val volume = documentId.substring(0, separator).takeIf { it.isNotBlank() } ?: return null
        val relative = documentId.substring(separator + 1)
        val root = when (volume.lowercase()) {
            "primary" -> PRIMARY_ROOT
            // "home:" is a provider-private root with no filesystem path; refuse rather than guess.
            "home" -> return null
            else -> "/storage/$volume"
        }
        return joinCanonical(root, relative)
    }

    private fun canonicalFromFilePath(path: String): String? {
        if (!path.startsWith('/')) return null
        val normalized = path.trimEnd('/').ifEmpty { "/" }
        SDCARD_PREFIX.find(normalized)?.let { match ->
            return joinCanonical(PRIMARY_ROOT, normalized.removeRange(match.range))
        }
        EMULATED_PREFIX.find(normalized)?.let { match ->
            return joinCanonical(PRIMARY_ROOT, normalized.removeRange(match.range))
        }
        return normalized
    }

    /**
     * Joins a volume root and a relative path segment-wise. Segment-wise on purpose: a plain string
     * concatenation makes `/storage/X/ROMs` a prefix of `/storage/X/ROMs2`, and any later prefix-based
     * comparison would fold two distinct folders together.
     */
    private fun joinCanonical(root: String, relative: String): String {
        val segments = relative.split('/').filter { it.isNotBlank() && it != "." }
        val stack = ArrayDeque<String>()
        segments.forEach { segment ->
            if (segment == "..") stack.removeLastOrNull() else stack.addLast(segment)
        }
        return if (stack.isEmpty()) root else root + "/" + stack.joinToString("/")
    }

    /**
     * Percent-decodes [encoded] as UTF-8, or **null** when it is not decodable.
     *
     * Two traps, both of which produced a wrong BYTE and so a wrong directory identity:
     *
     * 1. **Un-escaped text is converted a RUN at a time, never one `Char` at a time.** This used to be
     *    `bytes.write(character.code)`, and `ByteArrayOutputStream.write(int)` keeps only bits 0-7 — so
     *    `あ` (U+3042) was appended as `0x42` = `B`, and `ゲ` (U+30B2) as a bare `0xB2` that decodes to
     *    U+FFFD. Not every provider percent-encodes, so a folder named in anything but ASCII got two
     *    canonical forms, registered twice, and doubled every ROM beneath it — the 2026-08-28 duplicate
     *    root defect, in a different spelling.
     *
     *    The per-character repair is not enough either: a `Char` is a UTF-16 code unit, so an astral
     *    character arrives as a surrogate PAIR, and `char.toString().toByteArray(UTF_8)` on each half
     *    emits `?` for both. Measured: `🎮` and `🎯` then BOTH become `??` and compare equal — one row
     *    for two folders, which loses a real source. Converting the whole run keeps the pair intact.
     *
     * 2. **A percent-escape is exactly two hex DIGITS.** `String.toIntOrNull(16)` also accepts a leading
     *    sign, so `%-1` parsed as `-1` and `write(-1)` appended `0xFF` — a byte in the output that was
     *    never in the input. Per-digit parsing refuses instead, and null here means "no opinion", so
     *    [namesSameDirectory] falls back to an exact string comparison.
     *
     * The final UTF-8 decode is deliberately left REPLACING rather than strict: a folder whose stored
     * name is not valid UTF-8 must still canonicalise to one stable string, or its two spellings would
     * stop matching and duplicate all over again.
     *
     * This is the one decoder behind `core-launch`'s `externalStoragePathFromDocumentUri` too (through
     * [ExternalStorageDocumentPath]) — a document id that resolves one way for identity and another way for
     * launch would be its own defect.
     */
    internal fun percentDecodeUtf8(encoded: String): String? {
        val bytes = java.io.ByteArrayOutputStream(encoded.length)
        var index = 0
        while (index < encoded.length) {
            if (encoded[index] == '%') {
                if (index + 2 >= encoded.length) return null
                val high = encoded[index + 1].digitToIntOrNull(16) ?: return null
                val low = encoded[index + 2].digitToIntOrNull(16) ?: return null
                bytes.write((high shl 4) + low)
                index += 3
            } else {
                val literalStart = index
                while (index < encoded.length && encoded[index] != '%') index++
                bytes.write(encoded.substring(literalStart, index).toByteArray(Charsets.UTF_8))
            }
        }
        return runCatching { bytes.toString(Charsets.UTF_8.name()) }.getOrNull()
    }

    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /** `/sdcard` and `/sdcard/...` -- but never `/sdcardish`. */
    private val SDCARD_PREFIX = Regex("""^/sdcard(?=/|$)""")

    /** `/storage/emulated/0` and below -- but never `/storage/emulated/01`. */
    private val EMULATED_PREFIX = Regex("""^/storage/emulated/0(?=/|$)""")
}
