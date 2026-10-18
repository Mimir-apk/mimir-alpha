package com.gruning.mimir.rewrite.core.metadata

/**
 * One entry from a logiqx/No-Intro/Redump/MAME DAT: a game whose dump is identified by [crc32] (the DAT's
 * primary rom hash) OR — for a `<disk>`/CHD entry — by [sha1] alone. [crc32] is NULL for disks: the logiqx
 * DTD defines `<disk>` with sha1/md5 but NO crc, so a `.chd`'s identity IS its disk SHA-1. [cloneOf] is the
 * No-Intro parent set name for a regional/revision clone — the key to collapsing region variants by
 * AUTHORITATIVE data instead of filename guessing. Metadata only (a canonical name + hashes) — never a
 * content locator.
 *
 * @property canonicalName the dump's catalogued name (e.g. "Pokémon - Red Version (USA, Europe)").
 * @property parentName the display name of its clone parent, if any (else it IS a parent).
 */
data class DatEntry(
    val platform: String,
    val crc32: String?,
    val canonicalName: String,
    val cloneOf: String? = null,
    val md5: String? = null,
    val sha1: String? = null,
    val sizeBytes: Long? = null,
    val parentName: String? = null,
)

/**
 * An indexed, in-memory DAT: look a game up by its file's CRC32 (primary) or SHA-1. Built once from parsed
 * [DatEntry]s. Collapse-friendly: [collapseKey] returns the parent's identity when a clone, so every
 * regional dump of one game maps to the same key WITHOUT filename normalization.
 */
class DatStore(entries: List<DatEntry>) {

    // A disk/CHD entry has NO crc — it lives in bySha1 only. Filtering null crcs here keeps every crc-less
    // disk from colliding onto one empty key (associateBy would otherwise fold them all together).
    //
    // Retain collisions so byte size can resolve a shared CRC without weakening the fail-closed rule.
    private val byCrc: Map<String, List<DatEntry>> =
        entries.filter { it.crc32 != null }
            .groupBy { it.crc32!!.lowercase() }
    private val bySha1: Map<String, List<DatEntry>> =
        entries.filter { it.sha1 != null }
            .groupBy { it.sha1!!.lowercase() }

    /** Total DAT entries indexed (crc-keyed roms + sha1-only disks alike). */
    val size: Int = entries.size

    fun byCrc32(crc32: String): DatEntry? = resolveCandidates(byCrc[crc32.lowercase()], null)

    fun bySha1(sha1: String): DatEntry? = resolveCandidates(bySha1[sha1.lowercase()], null)

    /** SHA-1 is strongest. CRC collisions resolve only by an exact positive size and otherwise fail closed. */
    fun match(hash: ContentHash): DatEntry? {
        hash.sha1?.let { sha1 ->
            val candidates = bySha1[sha1.lowercase()]
            resolveCandidates(candidates, hash.sizeBytes)?.let { return it }
            if (!candidates.isNullOrEmpty()) return null
        }
        return hash.crc32?.let { crc -> resolveCandidates(byCrc[crc.lowercase()], hash.sizeBytes) }
    }

    private fun resolveCandidates(candidates: List<DatEntry>?, sizeBytes: Long?): DatEntry? {
        if (candidates.isNullOrEmpty()) return null
        if (candidates.distinctBy(::collapseKey).size == 1) return candidates.first()
        if (sizeBytes == null || sizeBytes <= 0L) return null
        val exactSize = candidates.filter { it.sizeBytes == sizeBytes }
        return exactSize.firstOrNull()?.takeIf { exactSize.distinctBy(::collapseKey).size == 1 }
    }

    /**
     * A stable per-GAME collapse key for [entry] — the parent set name when it's a clone, else its own
     * name. Two regional dumps of one game (clones of the same parent) return the same key → they collapse
     * to one tile from authoritative DAT data.
     */
    fun collapseKey(entry: DatEntry): String = (entry.cloneOf ?: entry.canonicalName)
}
