package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.resolver.CanonicalTitleSource
import com.gruning.mimir.rewrite.core.resolver.TitleCatalog

/**
 * The always-offline BASE of the metadata composition: resolves a canonical TITLE (no art / description)
 * from the bundled [TitleCatalog] via the [CanonicalTitleSource] seam. Zero network. Returns null when the
 * title is not catalogued — the caller keeps filename identity, or a stronger source (DAT / online) fills
 * it in. Reads [MetadataQuery.rawBaseName] directly; nothing leaves the device (`TitleCatalog.normalizeKey`
 * is idempotent, so an already-cleaned title still matches).
 */
class BundledCatalogSource(
    private val catalog: CanonicalTitleSource = TitleCatalog,
) : MetadataSource {

    override suspend fun lookup(query: MetadataQuery): MetadataRecord? {
        val baseName = query.rawBaseName ?: return null
        val canonical = catalog.resolve(query.platformId, baseName) ?: return null
        return MetadataRecord(canonicalTitle = canonical.displayTitle, origin = MetadataOrigin.TITLE_CATALOG)
    }
}
