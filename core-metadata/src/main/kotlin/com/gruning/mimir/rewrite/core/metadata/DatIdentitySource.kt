package com.gruning.mimir.rewrite.core.metadata

/**
 * Resolves a game's EXACT identity from a bundled DAT by its file fingerprint — the offline, authoritative
 * complement to the filename-based [BundledCatalogSource]. Keyed by [MetadataQuery.contentHash] (CRC32
 * then SHA-1), which is why it is consumed ON DEMAND (after a [ResidentHashProbe] pass), never at scan
 * time. A hit yields the DAT's canonical dump name; the [DatStore] additionally exposes the clone-parent
 * for authoritative region-collapse. Fully offline; metadata only — never content.
 */
class DatIdentitySource(
    private val store: DatStore,
    private val origin: MetadataOrigin = MetadataOrigin.NO_INTRO,
) : MetadataSource {

    override suspend fun lookup(query: MetadataQuery): MetadataRecord? {
        val hash = query.contentHash ?: return null
        val entry = store.match(hash) ?: return null
        return MetadataRecord(canonicalTitle = entry.canonicalName, origin = origin)
    }

    /** The authoritative collapse key for a fingerprint (parent set when a clone) — used by dedup. */
    fun collapseKeyFor(hash: ContentHash): String? = store.match(hash)?.let { store.collapseKey(it) }
}
