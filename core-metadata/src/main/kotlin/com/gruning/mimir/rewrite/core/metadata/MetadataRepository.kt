package com.gruning.mimir.rewrite.core.metadata

/**
 * The cache-first metadata orchestrator (contract). Resolution order:
 *   local `title_metadata_cache` → offline sources ([BundledCatalogSource], DAT identity) → (opt-in) online
 *   sources → write the result back to the cache.
 * Always offline-graceful: returns the best AVAILABLE record (possibly title-only) and NEVER blocks the
 * scan (enrichment is async/background). The concrete Room-backed + online implementation lands in S4; this
 * interface pins the seam now so the resolver + UI can depend on it today.
 */
interface MetadataRepository {
    /** Best available metadata for [query], cache-first; null only if nothing (not even a catalog title) resolves. */
    suspend fun resolve(query: MetadataQuery): MetadataRecord?
}

/**
 * A pure-offline [MetadataRepository]: tries each offline [MetadataSource] in order and returns the first
 * hit. No cache read/write, no network — the foundation composition until the Room-backed cache + online
 * enricher land in S4. Deterministic + synchronous-friendly (safe to call on the scan path).
 */
class OfflineMetadataRepository(
    private val sources: List<MetadataSource> = listOf(BundledCatalogSource()),
) : MetadataRepository {
    override suspend fun resolve(query: MetadataQuery): MetadataRecord? {
        for (source in sources) source.lookup(query)?.let { return it }
        return null
    }
}
