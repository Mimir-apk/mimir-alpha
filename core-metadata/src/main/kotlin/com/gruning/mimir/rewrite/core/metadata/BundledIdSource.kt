package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** One bundled keyed-identity catalog: its platform, `assets/dat/<assetName>`, and provenance. */
data class BundledIdAsset(
    val platformId: PlatformId,
    val assetName: String,
    val origin: MetadataOrigin,
    /** The only identity axis admitted by this compact catalogue. */
    val keyType: IdKeyType,
    /** Exact parsed TSV row count; hard coverage lock, never derived from the payload under test. */
    val expectedEntryCount: Int,
    /** SHA-256 of the decompressed TSV; gzip container metadata cannot move the catalogue latch. */
    val contentSha256: String,
    /** Exact bytes shipped, used only as a packaging/provisioning drift check. */
    val compressedSizeBytes: Long,
)

/**
 * The curated keyed-identity catalogs shipped as `assets/dat/<slug>.idcat.gz` — the non-hash complement to
 * [BundledDatManifest], reaching the platforms the DAT path can't hash-match: arcade (MAME shortname),
 * switch (Title ID from filename), and GameCube/Wii (disc Game ID). Compact TSV built by `tools/fetch-dats.sh`
 * from open sources (MAME name list, titledb, GameTDB). Metadata only.
 */
object BundledIdManifest {
    val assets: List<BundledIdAsset> = listOf(
        // `.idcatgz` (NOT `.idcat.gz`) so AGP doesn't auto-gunzip the `*.gz` asset at packaging.
        BundledIdAsset(
            PlatformId.ARCADE,
            "arcade.idcatgz",
            MetadataOrigin.MAME,
            IdKeyType.NAME,
            34_845,
            "55f41a801173e4ce4527d406e845ba99876da2a83a5c71ff3b7244e0245f5c1a",
            334_759,
        ),
        BundledIdAsset(
            PlatformId.SWITCH,
            "switch.idcatgz",
            MetadataOrigin.TITLE_CATALOG,
            IdKeyType.TITLE_ID,
            // Seven titles contain embedded newlines; physical-line tools report 18,834, while the TSV
            // parser correctly yields 18,827 keyed rows. Lock the runtime truth, not `wc -l`.
            18_827,
            "7324a654ad67bb5c78a1c257e15730b557308625a38dbb8cd17cea99c0335e48",
            330_510,
        ),
        // GameTDB covers GameCube + Wii under one unique 6-char Game ID scheme → one shared catalog.
        BundledIdAsset(
            PlatformId.GAMECUBE,
            "gametdb.idcatgz",
            MetadataOrigin.TITLE_CATALOG,
            IdKeyType.GAME_ID,
            10_161,
            "9616809716bedce8df9af55b268b12ba3f9997508936805feea5afd82abc85b3",
            103_729,
        ),
        BundledIdAsset(
            PlatformId.WII,
            "gametdb.idcatgz",
            MetadataOrigin.TITLE_CATALOG,
            IdKeyType.GAME_ID,
            10_161,
            "9616809716bedce8df9af55b268b12ba3f9997508936805feea5afd82abc85b3",
            103_729,
        ),
        // GameTDB's PS3 catalogue maps the local PARAM.SFO TITLE_ID to title. It is primary to the
        // whole-image Redump hash because reading nine local characters is preferable to hashing ~8 GB.
        BundledIdAsset(
            PlatformId("ps3"),
            "ps3tdb.idcatgz",
            MetadataOrigin.TITLE_CATALOG,
            IdKeyType.SERIAL,
            10_766,
            "83faa4017fb1a9c89b442d29698a7be290988d144f04911805140ca5d080287f",
            122_827,
        ),
        // GameTDB's 3DS
        // catalogue maps the four-character product code -- the `ACFE` of `CTR-P-ACFE`, read by
        // `IdExtractor.threeDsProductCode` from partition 0's NCCH header ~16 KB in -- to its English
        // title. Measured on the reference device, 3DS was 16.14 GB across 26 files, 43% of every byte the
        // identity pass read, because it had no keyed route at all.
        //
        // Not No-Intro, and that was checked rather than assumed: the No-Intro 3DS (Encrypted) set this
        // repo already ships as `3ds.midx` carries NO serials -- 2,161 `<game>` elements with only `name`
        // and `cloneof`.
        //
        // The product code is also coarser than a file hash in exactly the useful direction: the sampled
        // "(alternate dump 1)" of Castlevania yields the same `ACFE` as the primary dump, so two dumps of
        // one game collapse to one title here where two different SHA-1s could not.
        // DS is 2.53 GB across 88 files -- the largest FILE count left on the whole-file
        // hash path, and file count is what a player watches the identification counter move through.
        // Same GameTDB family and same 4-char key scheme as 3ds above; the code sits at 0x0C of the cart
        // header, so unlike 3DS this costs no extra read at all.
        BundledIdAsset(
            PlatformId("ds"),
            "ds.idcatgz",
            MetadataOrigin.TITLE_CATALOG,
            IdKeyType.GAME_ID,
            8_460,
            // Payload digest; the length below is the gzip file. See the note on 3ds.idcatgz.
            "704db4d8f059c9ec5c398505b0be5875e5bb1c87c3bc3e6e9b1293a2c20d222c",
            111_021,
        ),
        BundledIdAsset(
            PlatformId("3ds"),
            "3ds.idcatgz",
            MetadataOrigin.TITLE_CATALOG,
            IdKeyType.GAME_ID,
            4_779,
            // The sha is over the DECOMPRESSED payload while the length below is the gzip file --
            // the convention ps3tdb above already follows (122,827 is its gzip size, 83faa401 its
            // payload hash). Worth stating, because the two are easy to quote the wrong way round.
            "5b77c0d1423326c16ed0a56c59ba8318f03e21af45191fc5757b8fe1fc395f96",
            49_331,
        ),
    )
}

/** Where a catalog key was observed. Only [EMBEDDED_HEADER] is bound to payload bytes. */
enum class BundledIdBinding {
    FILENAME,
    EMBEDDED_HEADER,
}

/** One validated keyed-catalog hit with the exact extracted key and its byte-binding provenance intact. */
data class BundledIdMatch(
    val record: MetadataRecord,
    val extractedId: ExtractedId,
    val binding: BundledIdBinding,
)

/**
 * Typed keyed-catalog outcome. A filename/header disagreement must survive the adapter boundary; collapsing
 * it to one [MetadataRecord] is what allowed filename text to masquerade as payload identity.
 */
sealed interface BundledIdResolution {
    data class Identified(val match: BundledIdMatch) : BundledIdResolution

    data class Conflict(
        val embedded: BundledIdMatch,
        val filename: BundledIdMatch,
    ) : BundledIdResolution

    data object NoMatch : BundledIdResolution
}

/**
 * Resolves a game by a NON-hash key extracted from the file — the offline complement to [BundledDatSource]
 * for arcade / switch / gamecube / wii. [IdExtractor] pulls the shortname / Title ID / disc Game ID (from
 * the filename or the file's head bytes); the bundled [IdCatalog] maps it to a canonical title. Lazily
 * loads + caches one catalog per platform. Fully offline; metadata only — never a content locator.
 */
class BundledIdSource(
    private val provider: DatAssetProvider,
    manifest: List<BundledIdAsset> = BundledIdManifest.assets,
) {
    private val byPlatform: Map<String, BundledIdAsset> = manifest.associateBy { it.platformId.value }
    private val catalogs = ConcurrentHashMap<String, IdCatalog>()

    private fun catalogFor(platformId: String): Pair<IdCatalog, MetadataOrigin>? {
        val asset = byPlatform[platformId] ?: return null
        val cat = catalogs.computeIfAbsent(platformId) {
            loadValidatedCatalog(asset) ?: IdCatalog(emptyList())
        }
        return cat to asset.origin
    }

    /**
     * Fail-closed runtime latch for keyed catalogs.
     *
     * The manifest's count/digest/size/key-type fields are release invariants, not documentation. A missing,
     * truncated, replaced, over-expanded, mixed-key or duplicate-key payload must never answer an identity
     * lookup merely because the TSV parser can recover plausible rows from it.
     */
    private fun loadValidatedCatalog(asset: BundledIdAsset): IdCatalog? = runCatching {
        if (asset.expectedEntryCount <= 0) return@runCatching null
        if (asset.compressedSizeBytes !in 1..MAX_COMPRESSED_BYTES.toLong()) return@runCatching null
        if (!SHA256.matches(asset.contentSha256)) return@runCatching null

        val compressed = provider.openRaw(asset.assetName)?.use { input ->
            input.readBounded(MAX_COMPRESSED_BYTES)
        } ?: return@runCatching null
        if (compressed.size.toLong() != asset.compressedSizeBytes) return@runCatching null

        val decompressed = GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
            gzip.readBounded(MAX_DECOMPRESSED_BYTES)
        } ?: return@runCatching null
        if (sha256(decompressed) != asset.contentSha256) return@runCatching null

        val entries = IdCatalogParser.parse(ByteArrayInputStream(decompressed), asset.platformId.value)
        if (entries.size != asset.expectedEntryCount) return@runCatching null
        if (entries.any { it.keyType != asset.keyType }) return@runCatching null
        if (entries.distinctBy { "${it.keyType}|${it.key.lowercase()}" }.size != entries.size) {
            return@runCatching null
        }
        IdCatalog(entries)
    }.getOrNull()

    /**
     * Resolve every catalogued key supplied by this observation while retaining where it came from.
     *
     * Embedded evidence is primary. When both sources yield the same key, the result is one embedded match.
     * Different catalogued keys are a typed [BundledIdResolution.Conflict], never an implicit filename win.
     */
    fun resolve(
        platformId: String,
        fileName: String?,
        head: ByteArray?,
        headLen: Int,
    ): BundledIdResolution {
        val (cat, origin) = catalogFor(platformId) ?: return BundledIdResolution.NoMatch
        val filenameMatch = fileName
            ?.let { IdExtractor.fromName(platformId, it) }
            ?.let { match(cat, origin, platformId, it, BundledIdBinding.FILENAME) }
        val embeddedMatch = head
            ?.let { IdExtractor.fromHeader(platformId, it, headLen) }
            ?.let { match(cat, origin, platformId, it, BundledIdBinding.EMBEDDED_HEADER) }

        return when {
            embeddedMatch != null && filenameMatch != null &&
                embeddedMatch.extractedId != filenameMatch.extractedId ->
                BundledIdResolution.Conflict(embedded = embeddedMatch, filename = filenameMatch)

            embeddedMatch != null -> BundledIdResolution.Identified(embeddedMatch)
            filenameMatch != null -> BundledIdResolution.Identified(filenameMatch)
            else -> BundledIdResolution.NoMatch
        }
    }

    /**
     * Resolves an already-parsed, payload-bound key without routing it back through a filename/header
     * extractor. Container readers use this after their own structural validation.
     */
    fun resolveEmbedded(
        platformId: String,
        extractedId: ExtractedId,
    ): BundledIdResolution {
        val (catalog, origin) = catalogFor(platformId) ?: return BundledIdResolution.NoMatch
        val match = match(
            catalog = catalog,
            origin = origin,
            platformId = platformId,
            extractedId = extractedId,
            binding = BundledIdBinding.EMBEDDED_HEADER,
        ) ?: return BundledIdResolution.NoMatch
        return BundledIdResolution.Identified(match)
    }

    /** Entries in a platform's bundled catalog (0 if none) — diagnostics / provenance readout. */
    fun entryCount(platformId: String): Int = catalogFor(platformId)?.first?.size ?: 0

    private fun match(
        catalog: IdCatalog,
        origin: MetadataOrigin,
        platformId: String,
        extractedId: ExtractedId,
        binding: BundledIdBinding,
    ): BundledIdMatch? {
        val entry = catalog.lookup(platformId, extractedId.keyType, extractedId.key) ?: return null
        return BundledIdMatch(
            record = MetadataRecord(canonicalTitle = entry.canonicalTitle, origin = origin),
            extractedId = extractedId,
            binding = binding,
        )
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val MAX_COMPRESSED_BYTES = 2 * 1024 * 1024
        const val MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
