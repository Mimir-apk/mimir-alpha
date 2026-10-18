package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * The seam through which [GameIdentityBuilder] obtains a canonical title for a file, so the identity
 * source can be swapped or COMPOSED without touching the builder or the grouper.
 *
 * The bundled [TitleCatalog] is the default, always-offline implementation. Later slices compose stronger
 * sources IN FRONT of it, cache-first — e.g. a `CachedCanonicalTitleSource` (reads `title_metadata_cache`),
 * a local No-Intro/Redump DAT source (hash → canonical), and the opt-in online enricher — each falling
 * through to the bundled catalog. A miss (null) always means the file keeps its conservative filename
 * identity: this seam can only ever make dedup MORE precise, never merge two different games.
 */
interface CanonicalTitleSource {
    /** The canonical title for a file on [platformId] with base name [baseName], or null if unknown. */
    fun resolve(platformId: PlatformId, baseName: String): CanonicalTitle?
}
