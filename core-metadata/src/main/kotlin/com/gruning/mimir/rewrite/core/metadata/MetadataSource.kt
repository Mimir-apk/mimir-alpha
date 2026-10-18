package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * A lookup for one game's metadata, least-revealing evidence first. The PRIVACY contract lives here: a
 * source MUST prefer [contentHash] (then [serial]) and treat [rawBaseName] as a LAST resort turned into a
 * cleaned title — the raw filename / path is NEVER sent to a third party (see the subsystem plan's
 * data-minimization ladder). Offline sources use [rawBaseName] freely (nothing leaves the device).
 */
data class MetadataQuery(
    val platformId: PlatformId,
    val contentHash: ContentHash? = null,
    val serial: String? = null,
    /** The file's base name (no extension). Offline sources read it directly; the online path derives a
     *  CLEANED title from it and never sends the raw string. */
    val rawBaseName: String? = null,
    /** ART02 : candidate titles tried IN ORDER by online sources — the DAT
     *  canonicalTitle leads (region-stripped truth), then the cleaned scan title; [rawBaseName] is the
     *  implicit last rung. Every online response is system-verified before acceptance. */
    val candidateNames: List<String> = emptyList(),
    /** a previously VERIFIED provider game id (e.g. ScreenScraper `jeu.id`) persisted by the fetch
     *  layer — a re-fetch resolves precisely in one call and never re-runs the name ladder wrong. */
    val providerGameId: String? = null,
)

/** The file fingerprint a hash-first source queries by (no filename ever leaves the device on a hit). */
data class ContentHash(
    val crc32: String? = null,
    val md5: String? = null,
    val sha1: String? = null,
    val sizeBytes: Long? = null,
)

/** Where a resolved [MetadataRecord] came from — drives cache expiry + the honest provenance readout.
 *  [STEAM] is the Steam storefront (`store.steampowered.com/api/appdetails`) `short_description` lookup,
 *  scoped to `pc_games` — see [com.gruning.mimir.rewrite.core.metadata.online.VerifiedMetadataCache] for the
 *  one-row-per-game limit this coexists under. [STEAMGRIDDB] is that same class's art-identity provider for
 *  `pc_games` (R6-P1-04): a marker-only origin that never carries a synopsis, settling a row SteamGridDB
 *  identified so the Enrich seat can leave "Enrich" for "Settings" even with no description found. */
enum class MetadataOrigin { TITLE_CATALOG, NO_INTRO, REDUMP, MAME, SCREENSCRAPER, THEGAMESDB, IGDB, STEAM, STEAMGRIDDB }

/**
 * Resolved metadata for one game. [boxArtUri] / [flatArtUri] / [heroArtUri] / [physicalMediaUri] / [screenshotUris] are LOCAL cached-image uris
 * (the image is downloaded + cached on device by the art store), never remote URLs held at render time.
 * [cacheExpiresAt] is set for capped sources (IGDB +24h), null = no expiry — and once this record's fields
 * round-trip into `core-storage`'s `title_metadata_cache` (`ScreenScraperMetadataCache.kt:111` carries the
 * value straight over on write), a non-null, past [cacheExpiresAt] is **enforced on read** (2026-08-20):
 * `TitleMetadataCacheDao.freshByCatalogKey` is the guarded chokepoint that reads an expired row as absent,
 * per `com.gruning.mimir.rewrite.core.storage.entity.TitleMetadataCacheEntity`'s kdoc (which also names the
 * one read surface — the shelf/gamelist SQL projections — that enforcement does not yet cover). Metadata
 * ONLY — never a content/ROM locator.
 */
data class MetadataRecord(
    val canonicalTitle: String,
    val origin: MetadataOrigin,
    val region: String? = null,
    val serial: String? = null,
    val description: String? = null,
    val boxArtUri: String? = null,
    val flatArtUri: String? = null,
    /** A cartridge/disc/card render (`support-*` at ScreenScraper lookup time). The fetch lane stores it
     *  as a distinct LOCAL `-support` sibling; it never replaces or masquerades as box cover art. */
    val physicalMediaUri: String? = null,
    /** A REMOTE preview-video url at lookup time (CP05 video mode); the fetch downloads it to a LOCAL sibling
     *  and only the local file is ever played. Null when the game has no video or the caller didn't ask. */
    val previewVideoUri: String? = null,
    val screenshotUris: List<String> = emptyList(),
    val cacheExpiresAt: Long? = null,
    /** the provider's own id for the VERIFIED match (ScreenScraper `jeu.id`) — the fetch layer
     *  persists it per game so later fetches skip the name ladder entirely. Null for offline sources. */
    val providerGameId: String? = null,
    /** A title-screen-first Hero image, kept distinct from both the clear logo and box cover. */
    val heroArtUri: String? = null,
)

/**
 * One source of game metadata. Offline sources ([BundledCatalogSource], future DAT identity) return records
 * with NO network; the opt-in online enricher performs a guarded HTTP call and returns null when
 * disabled / offline / unresolved — it MUST NOT throw up the stack. Sources compose cache-first in a
 * [MetadataRepository].
 */
interface MetadataSource {
    suspend fun lookup(query: MetadataQuery): MetadataRecord?
}
