package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.GameIdentity
import com.gruning.mimir.rewrite.core.model.DisplayTitle
import com.gruning.mimir.rewrite.core.model.IdentitySource
import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * Builds a [GameIdentity] from the evidence a scan has for a file. It layers evidence strongest-first
 * (the design constraint — never collapse on a weak signal):
 *
 *  - **content hash** → [IdentitySource.CONTENT_HASH], catalog key = the hash. Byte-exact identity.
 *  - **serial** (read from the image, e.g. PS `SLUS-xxxxx`) → [IdentitySource.NATIVE_IDENTITY].
 *  - **curated catalog hit** → a platform-scoped hard catalog identity.
 *  - **filename only** → [IdentitySource.FILENAME]: a display title + region/version tokens parsed from
 *    the name, but NO catalog key (so region siblings do NOT auto-collapse on filename alone — that is
 *    a catalog-slice job).
 *
 * The [GameId] (the dedup GROUP id) is derived so that files the scan can prove are the
 * SAME game share it:
 *  - hash present → `hash:<hash>` (byte-identical dumps merge).
 *  - serial present → `serial:<platform>:<serial>` (region/version siblings that share a serial merge).
 *  - else → `title:<platform>:<filenameIdentityKey>` (multi-disc siblings share it because only their disc
 *    ordinal is stripped; region, revision, title-id, and support tokens remain identity evidence).
 *
 * This is the SINGLE place identity is minted, so the resolver, the [DefaultGroupingKeyer], and the
 * repository all agree on the group key.
 */
class GameIdentityBuilder(
    /**
     * The canonical-title seam. Defaults to the explicit bundled offline [TitleCatalog]. A curated hit is a
     * hard catalog identity; a null result always falls back to conservative filename identity.
     */
    private val canonicalTitles: CanonicalTitleSource = TitleCatalog,
    private val nameResolver: GameNameResolver = GameNameResolver(),
) : IdentityResolver {

    override fun resolve(platformId: PlatformId, input: ResolutionInput): GameIdentity =
        build(platformId, input)

    /** Build the identity for a file on [platformId] from all evidence on [input]. */
    fun build(platformId: PlatformId, input: ResolutionInput): GameIdentity =
        build(platformId, input, nameResolver.resolve(input, platformId))

    /** Build from name facts already resolved by the admission pipeline. */
    fun build(platformId: PlatformId, input: ResolutionInput, name: ResolvedGameName): GameIdentity {
        val baseName = input.fileName.substringBeforeLast('.', input.fileName)
        val title = name.displayTitle
        val region = extractRegion(baseName)
        val version = name.version

        val hash = input.contentHash?.takeIf { it.isNotBlank() }
        val serial = input.serial?.takeIf { it.isNotBlank() }

        return when {
            hash != null -> GameIdentity(
                gameId = GameId("hash:$hash"),
                title = title,
                region = region,
                version = version,
                titleCatalogKey = hash,
                serial = serial,
                source = IdentitySource.CONTENT_HASH,
            )

            serial != null -> GameIdentity(
                gameId = GameId("serial:${platformId.value}:${serial.lowercase()}"),
                title = title,
                region = region,
                version = version,
                titleCatalogKey = serial,
                serial = serial,
                source = IdentitySource.NATIVE_IDENTITY,
            )

            else -> {
                val canonical = canonicalTitles.resolve(platformId, name.catalogBaseName)
                if (canonical != null) {
                    // A curated catalog match: every region / version / language variant of this game
                    // shares the canonical key, so they collapse to ONE tile (CATALOG_TITLE_ID) shown
                    // under the region-neutral canonical title (policy: canonical-title label).
                    GameIdentity(
                        gameId = GameId("catalog:${canonical.key}"),
                        title = canonical.displayTitle,
                        region = region,
                        version = version,
                        titleCatalogKey = canonical.key,
                        serial = null,
                        source = IdentitySource.FILENAME,
                    )
                } else {
                    // Filename-only: strip only the disc ordinal. Region/revision/title-id evidence remains,
                    // and no catalog key means a normalization coincidence cannot auto-fold two games.
                    GameIdentity(
                        gameId = GameId("title:${platformId.value}:${name.filenameIdentityKey}"),
                        title = title,
                        region = region,
                        version = version,
                        titleCatalogKey = null,
                        serial = null,
                        source = IdentitySource.FILENAME,
                    )
                }
            }
        }
    }

    /** A human display title from a base filename, using the same tidy every rendered title receives. */
    fun displayTitle(baseName: String): String =
        nameResolver.resolve(ResolutionInput(baseName, "")).displayTitle

    /** Extract a region token (USA / Europe / Japan / World / …) from the filename, or null. */
    fun extractRegion(baseName: String): String? {
        for (m in PAREN_CONTENT.findAll(baseName)) {
            val token = m.groupValues[1].trim()
            DisplayTitle.canonicalRegion(token)?.let { return it }
            token.split(',', '+')
                .asSequence()
                .mapNotNull(DisplayTitle::canonicalRegion)
                .firstOrNull()
                ?.let { return it }
        }
        return null
    }

    /** Extract a version/revision token ("Rev 1", "v1.1", "Beta") from the filename, or null. */
    fun extractVersion(baseName: String): String? {
        return nameResolver.resolve(ResolutionInput(baseName, "")).version
    }

    companion object {
        private val PAREN_CONTENT = Regex("""\(([^)]*)\)""")
    }
}
