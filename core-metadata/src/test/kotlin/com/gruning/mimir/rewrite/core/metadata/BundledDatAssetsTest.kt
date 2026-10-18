package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Integrity of the REAL bundled assets: every packed source must reproduce its physical, header, count,
 * input and semantic locks, and a known game must resolve by its real fingerprint. No XML oracle ships.
 */
class BundledDatAssetsTest {

    private fun assetsDir(): File? =
        listOf(File("../app/src/main/assets/dat"), File("app/src/main/assets/dat"))
            .firstOrNull { it.isDirectory }

    @Test
    fun `every manifest DAT is present, validates its source invariants, and has entries`() {
        val dir = requireNotNull(assetsDir()) { "bundled DAT assets not visible from test CWD" }

        // Open EACH physical shard directly, including DS/3DS variants. PackedDatIndex validates the exact
        // source header, parsed count, input digest and recomputed lookup-semantic digest before opening.
        for (asset in BundledDatManifest.assets) {
            val f = File(dir, asset.assetName)
            assertTrue("missing asset ${asset.assetName}", f.exists())
            val bytes = f.readBytes()
            assertEquals("${asset.assetName} packed size changed", asset.packedSizeBytes, bytes.size.toLong())
            assertEquals("${asset.assetName} packed bytes changed", asset.packedSha256, sha256(bytes))
            val index = PackedDatIndex.open(ByteBuffer.wrap(bytes), asset.packedExpectation())
            assertTrue("${asset.assetName} failed its packed source invariants", index != null)
            assertEquals("${asset.assetName} semantic coverage changed", asset.expectedEntryCount, index?.entryCount)
            println(
                "MimirBundledDat: ${asset.platformId.value} (${asset.sourceId} -> ${asset.assetName}) " +
                    "${asset.expectedEntryCount} rows / ${asset.expectedUniqueSha1Count} SHA / " +
                    "${asset.expectedAmbiguousSha1Count} ambiguous [${asset.origin}]",
            )
        }

        // Spot-check a real Dreamcast game by its actual CHD sha1 (from the shipped DAT), via the source.
        val provider = PackedDatAssetProvider { name ->
            File(dir, name).takeIf { it.exists() }?.readBytes()?.let(ByteBuffer::wrap)
        }
        val hit = runBlocking {
            BundledDatSource(provider).lookup(
                MetadataQuery(
                    PlatformId.DREAMCAST,
                    contentHash = ContentHash(sha1 = "cf0ae03446ae76c9f5e14176a8f524a0661fe78b"),
                ),
            )
        }
        assertEquals("Sonic Adventure (USA)", hit?.canonicalTitle)
        assertEquals(MetadataOrigin.REDUMP, hit?.origin)

        // Keyed catalogues are part of the same startup latch. Lock their routing, rows and decompressed
        // semantic payload just as hard as the XML sources so a ps3tdb-only update cannot land inert.
        for (asset in BundledIdManifest.assets) {
            val f = File(dir, asset.assetName)
            assertTrue("missing id asset ${asset.assetName}", f.exists())
            assertEquals("${asset.assetName} compressed packaging changed", asset.compressedSizeBytes, f.length())
            val raw = f.inputStream().use { GZIPInputStream(it).use { gz -> gz.readBytes() } }
            val entries = IdCatalogParser.parse(ByteArrayInputStream(raw), asset.platformId.value)
            assertEquals("${asset.assetName} semantic coverage changed", asset.expectedEntryCount, entries.size)
            assertTrue("${asset.assetName} mixed or wrong key type", entries.all { it.keyType == asset.keyType })
            assertEquals("${asset.assetName} decompressed payload changed", asset.contentSha256, sha256(raw))
        }

        val keyedSource = BundledIdSource(
            DatAssetProvider { name ->
                File(dir, name).takeIf { it.exists() }?.inputStream()
            },
        )
        for (asset in BundledIdManifest.assets) {
            assertEquals(
                "${asset.assetName} failed its runtime integrity latch",
                asset.expectedEntryCount,
                keyedSource.entryCount(asset.platformId.value),
            )
        }
    }

    @Test
    fun `catalogue stamp is order independent and advances on semantic identity`() {
        val first = BundledDatAsset(
            platformId = PlatformId("one"),
            sourceId = "one.datgz",
            assetName = "one.midx",
            origin = MetadataOrigin.NO_INTRO,
            expectedHeaderName = "One",
            legacyXmlCompressedSizeBytes = 100,
            packedSizeBytes = 60,
            expectedEntryCount = 10,
            expectedUniqueSha1Count = 10,
            expectedAmbiguousSha1Count = 0,
            contentSha256 = "a".repeat(64),
            semanticMapSha256 = "c".repeat(64),
            packedSha256 = "e".repeat(64),
        )
        val second = BundledDatAsset(
            platformId = PlatformId("two"),
            sourceId = "two.datgz",
            assetName = "two.midx",
            origin = MetadataOrigin.REDUMP,
            expectedHeaderName = "Two",
            legacyXmlCompressedSizeBytes = 200,
            packedSizeBytes = 120,
            expectedEntryCount = 20,
            expectedUniqueSha1Count = 20,
            expectedAmbiguousSha1Count = 0,
            contentSha256 = "b".repeat(64),
            semanticMapSha256 = "d".repeat(64),
            packedSha256 = "f".repeat(64),
        )

        assertEquals(catalogueStamp(listOf(first, second)), catalogueStamp(listOf(second, first)))
        assertNotEquals(catalogueStamp(listOf(first, second)), catalogueStamp(listOf(first.copy(sourceId = "renamed.datgz"), second)))
        assertNotEquals(catalogueStamp(listOf(first, second)), catalogueStamp(listOf(first.copy(platformId = PlatformId("rerouted")), second)))
        assertNotEquals(catalogueStamp(listOf(first, second)), catalogueStamp(listOf(first.copy(expectedHeaderName = "Variant"), second)))
        assertNotEquals(catalogueStamp(listOf(first, second)), catalogueStamp(listOf(first.copy(expectedEntryCount = 11), second)))
        assertNotEquals(catalogueStamp(listOf(first, second)), catalogueStamp(listOf(first.copy(contentSha256 = "0".repeat(64)), second)))
        assertNotEquals(catalogueStamp(listOf(first, second)), catalogueStamp(listOf(first.copy(semanticMapSha256 = "1".repeat(64)), second)))
        // A byte/layout-only repack must not invalidate already-enriched identities.
        assertEquals(
            catalogueStamp(listOf(first, second)),
            catalogueStamp(
                listOf(
                    first.copy(assetName = "one-v2.midx", packedSizeBytes = 61, packedSha256 = "2".repeat(64)),
                    second,
                ),
            ),
        )

        val id = BundledIdAsset(
            platformId = PlatformId("id"),
            assetName = "id.idcatgz",
            origin = MetadataOrigin.TITLE_CATALOG,
            keyType = IdKeyType.SERIAL,
            expectedEntryCount = 7,
            contentSha256 = "d".repeat(64),
            compressedSizeBytes = 300,
        )
        assertNotEquals(catalogueStamp(listOf(first, second)), catalogueStamp(listOf(first, second), listOf(id)))
        assertEquals(
            catalogueStamp(listOf(first, second), listOf(id)),
            catalogueStamp(listOf(second, first), listOf(id.copy(compressedSizeBytes = 301))),
        )
    }

    @Test
    fun `bundled identity manifest covers 44 blobs and 37 platform identities`() {
        val dir = requireNotNull(assetsDir()) { "bundled DAT assets not visible from test CWD" }
        val distinctIdBlobs = BundledIdManifest.assets.map { it.assetName }.distinct()
        // 20 since 604054a bundled the SNES DAT — SNES is 64% of the sampled library (3414 files)
        // and had no DAT at all, so it could never be identified by any code path.
        // 22 since ps3.datgz + xbox360.datgz landed; 40 after the specified 13 cartridge and five
        // usable CHD catalogue additions; 42 after additive Wii U hashes + PS3 title IDs; 43 after the
        // 3DS product-code catalogue (QA-17, 2026-09-16), which took 3DS off the
        // whole-file hash path it had been on for 16.14 GB of the sampled library; 44 after the
        // DS game-code catalogue, which took the remaining 88-file platform off it. Deliberately
        // hardcoded, NOT derived from the manifest — a lock
        // that recomputes what it checks passes no matter what shipped.
        assertEquals(44, BundledDatManifest.assets.size + distinctIdBlobs.size)

        val platformIds = (BundledDatManifest.assets.map { it.platformId.value } +
            BundledIdManifest.assets.map { it.platformId.value }).toSet()
        assertEquals(
            setOf(
                "nes", "gb", "gbc", "gba", "ds", "3ds", "n64", "megadrive", "ps1", "psp",
                "dreamcast", "gamecube", "wii", "arcade", "switch", "snes", "ps3", "xbox360",
                "mastersystem", "gamegear", "sg1000", "atari2600", "atari7800", "atarilynx",
                "pcengine", "ngp", "ngpc", "virtualboy", "wonderswan", "wonderswancolor", "pokemini",
                "ps2", "saturn", "segacd", "pcenginecd", "snk_neo_geo_cd",
                "nintendo_wii_u",
            ),
            platformIds,
        )

        val actualDatBlobs = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".midx") }
            .map { it.name }
            .toSet()
        assertEquals(BundledDatManifest.assets.map { it.assetName }.toSet(), actualDatBlobs)
        assertTrue("legacy XML DATs must not ship", dir.listFiles().orEmpty().none { it.name.endsWith(".datgz") })

        (BundledDatManifest.assets.map { it.assetName } + distinctIdBlobs).forEach { assetName ->
            val asset = File(dir, assetName)
            assertTrue("missing or empty asset $assetName", asset.isFile && asset.length() > 0L)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
