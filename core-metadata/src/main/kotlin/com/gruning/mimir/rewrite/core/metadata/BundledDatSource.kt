package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.util.concurrent.ConcurrentHashMap

/**
 * The offline, AUTHORITATIVE metadata source: identifies a game from the bundled No-Intro / MAMERedump DATs
 * by its SHA-1 fingerprint. Complements the filename-based [BundledCatalogSource] — a hit
 * here yields the DAT's canonical dump name plus (via [collapseKeyFor]) the clone-parent that drives
 * authoritative region collapse. Consumed ON DEMAND, after a [ResidentHashProbe] pass, never at scan time.
 *
 * Each source set remains an independent immutable `.midx` shard and is opened LAZILY on first use. The
 * hash table stays read-only/memory-mapped on Android; only that shard's compact title pool is inflated.
 * A missing or malformed shard caches an honest empty result and is never guessed or retried in-process.
 *
 * Opening validates the source header/count/input digest and canonical semantic digest before answering.
 * There is deliberately no XML or CRC fallback: the frozen corpus has SHA-1 for every admitted source row.
 */
class BundledDatSource(
    private val provider: PackedDatAssetProvider,
    manifest: List<BundledDatAsset> = BundledDatManifest.assets,
) : MetadataSource {

    // A platform may have several DATs (DS/3DS = Encrypted + Decrypted); try each until one matches.
    private val byPlatform: Map<String, List<BundledDatAsset>> = manifest.groupBy { it.platformId.value }
    private val stores = ConcurrentHashMap<String, PackedStore>() // keyed by physical shard

    private fun storeForAsset(asset: BundledDatAsset): PackedDatIndex? =
        stores.computeIfAbsent(asset.assetName) {
            PackedStore(provider.openPacked(asset.assetName)?.let { PackedDatIndex.open(it, asset.packedExpectation()) })
        }.index

    override suspend fun lookup(query: MetadataQuery): MetadataRecord? {
        val hash = query.contentHash ?: return null
        for (asset in byPlatform[query.platformId.value].orEmpty()) {
            val entry = storeForAsset(asset)?.match(hash) ?: continue
            return MetadataRecord(canonicalTitle = entry.canonicalTitle, origin = asset.origin)
        }
        return null
    }

    /** Authoritative dedup collapse key for a fingerprint (the clone-parent set when it's a clone). */
    fun collapseKeyFor(platformId: PlatformId, hash: ContentHash): String? {
        for (asset in byPlatform[platformId.value].orEmpty()) {
            storeForAsset(asset)?.match(hash)?.let { return it.collapseKey }
        }
        return null
    }

    /**
     * Name-derived identity for a platform, from the same shards the hash path consults — no file read.
     *
     * A platform may ship several shards (Dreamcast carries both the MAMERedump `.chd` set and the Redump
     * per-track set). Unlike [lookup], which may take the first hit because a SHA-1 agreeing across two
     * shards is the same dump by construction, a NAME can legitimately appear in both with different
     * collapse parents. So every shard is consulted and they must agree on one identity; disagreement
     * returns null and the caller hashes.
     */
    fun resolveByName(
        platformId: PlatformId,
        fileBaseName: String,
        discFallback: Int? = null,
    ): BundledDatNameMatch? {
        var found: BundledDatNameMatch? = null
        for (asset in byPlatform[platformId.value].orEmpty()) {
            val match = storeForAsset(asset)?.byName(fileBaseName, discFallback) ?: continue
            val candidate = BundledDatNameMatch(
                canonicalTitle = match.canonicalTitle,
                collapseKey = match.collapseKey,
                origin = asset.origin,
                catalogStamp = asset.semanticMapSha256,
            )
            if (found == null) {
                found = candidate
            } else if (found.canonicalTitle != candidate.canonicalTitle ||
                found.collapseKey != candidate.collapseKey
            ) {
                return null
            }
            // Shards that AGREE keep the first one's stamp. Manifest order is fixed, so this is
            // deterministic, and agreement across shards is strictly stronger than the stamp records.
        }
        return found
    }

    /** Total entries across a platform's bundled DAT(s) (0 if none) — diagnostics / provenance readout. */
    fun entryCount(platformId: PlatformId): Int =
        byPlatform[platformId.value].orEmpty().sumOf { storeForAsset(it)?.entryCount ?: 0 }

    private data class PackedStore(val index: PackedDatIndex?)
}

/** One shard-agreed, name-derived identity. [origin] carries the curated corpus that vouched for it. */
data class BundledDatNameMatch(
    val canonicalTitle: String,
    val collapseKey: String,
    /** The curated corpus that vouched for the name — only NO_INTRO / REDUMP may carry authority. */
    val origin: MetadataOrigin,
    /** The answering shard's `semanticMapSha256`: which exact catalogue this identity came from. */
    val catalogStamp: String,
)
