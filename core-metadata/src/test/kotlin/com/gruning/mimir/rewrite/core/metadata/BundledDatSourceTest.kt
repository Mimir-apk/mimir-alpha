package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hermetic: exercises packed validation, cache and multi-platform routing without shipping XML. */
class BundledDatSourceTest {

    private val gbPacked = Base64.getDecoder().decode(
        "TUlNSURYMQABAMAAAwAAABAAAAACAAAACAAAAAoAAAACAAAAAgAAAAAAAAACAAAAAgAAABgAAAAIAAAAAAAA" +
            "AMAAAAAAAAAA6AAAAAAAAAAYAQAAAAAAACgBAAAAAAAAOAEAAAAAAAAdAAAAAAAAACcAAAAAAAAAVQEAAAAA" +
            "AAAQ1dLPBGgl2NmMyZbe2kIrPXlO/l9KrGBGeCj+M+vWo45k/G2Ko07QKzctw+IfNoQp5jbkBpn6HPQSm2PQ" +
            "Z+v8Z2ItZml4dHVyZS5kYXRnemdiTk9fSU5UUk9GaXh0dXJlIEdCAAAAABERERERERERERERERERERERERER" +
            "AAAAACIiIiIiIiIiIiIiIiIiIiIiIiIiAQAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAFAAAACcAAAAAAAAAC8jP" +
            "Ts3Nz1PQVXDKKU1V0AgNdtQMgIsFpaZAhAA=",
    )
    private val dreamcastPacked = Base64.getDecoder().decode(
        "TUlNSURYMQABAMAAAwAAABAAAAAJAAAABgAAABEAAAABAAAAAQAAAAAAAAABAAAAAQAAABgAAAAIAAAAAAAAAMAAAAAAAAAA8AAAAAAAAAAIAQAAAAAAABABAAAAAAAAGAEAAAAAAAAXAAAAAAAAABUAAAAAAAAALwEAAAAAAABWPOMbjf7TgWfQVlT3+8Jwhaau4+rfPMgAddjVu93x+oI7JLb80YWxL1QLtT7/Grx3ymUEkEdw4OxdqgUjKfbTZGMtZml4dHVyZS5kYXRnemRyZWFtY2FzdFJFRFVNUEZpeHR1cmUgRHJlYW1jYXN0zwrgNEaudsn14UF2qPUkoGYf54sAAAAAAAAAAAAAAAAAAAAAFQAAAAvOz8tMVnBMKUvNKyktSlXQCA121AQA",
    )

    private val gbAsset = asset(
        platform = PlatformId.GB,
        sourceId = "gb-fixture.datgz",
        assetName = "gb-fixture.midx",
        origin = MetadataOrigin.NO_INTRO,
        header = "Fixture GB",
        bytes = gbPacked,
        entries = 2,
        inputDigest = "10d5d2cf046825d8d98cc996deda422b3d794efe5f4aac60467828fe33ebd6a3",
        semanticDigest = "8e64fc6d8aa34ed02b372dc3e21f368429e636e40699fa1cf4129b63d067ebfc",
    )
    private val dreamcastAsset = asset(
        platform = PlatformId.DREAMCAST,
        sourceId = "dc-fixture.datgz",
        assetName = "dc-fixture.midx",
        origin = MetadataOrigin.REDUMP,
        header = "Fixture Dreamcast",
        bytes = dreamcastPacked,
        entries = 1,
        inputDigest = "563ce31b8dfed38167d05654f7fbc27085a6aee3eadf3cc80075d8d5bbddf1fa",
        semanticDigest = "823b24b6fcd185b12f540bb53eff1abc77ca6504904770e0ec5daa052329f6d3",
    )
    private val missingAsset = asset(
        platform = PlatformId.PS1,
        sourceId = "missing.datgz",
        assetName = "missing.midx",
        origin = MetadataOrigin.REDUMP,
        header = "Missing",
        bytes = byteArrayOf(1),
        entries = 1,
        inputDigest = "00".repeat(32),
        semanticDigest = "11".repeat(32),
    )
    private val provider = PackedDatAssetProvider { name ->
        when (name) {
            gbAsset.assetName -> ByteBuffer.wrap(gbPacked)
            dreamcastAsset.assetName -> ByteBuffer.wrap(dreamcastPacked)
            else -> null
        }
    }
    private val source = BundledDatSource(provider, listOf(gbAsset, dreamcastAsset, missingAsset))

    @Test
    fun `cart lookup by sha1 returns canonical name and No-Intro origin`() = runBlocking {
        val result = source.lookup(
            MetadataQuery(PlatformId.GB, contentHash = ContentHash(sha1 = "22".repeat(20))),
        )
        assertEquals("Pokemon - Red (USA)", result?.canonicalTitle)
        assertEquals(MetadataOrigin.NO_INTRO, result?.origin)
    }

    @Test
    fun `disc lookup by sha1 returns canonical name and Redump origin`() = runBlocking {
        val result = source.lookup(
            MetadataQuery(
                PlatformId.DREAMCAST,
                contentHash = ContentHash(sha1 = "cf0ae03446ae76c9f5e14176a8f524a0661fe78b"),
            ),
        )
        assertEquals("Sonic Adventure (USA)", result?.canonicalTitle)
        assertEquals(MetadataOrigin.REDUMP, result?.origin)
    }

    @Test
    fun `collapseKeyFor folds a clone onto its exact parent`() {
        assertEquals(
            "Pokemon - Blue (USA)",
            source.collapseKeyFor(PlatformId.GB, ContentHash(sha1 = "22".repeat(20))),
        )
    }

    @Test
    fun `unmapped platform crc-only query and unknown sha all resolve to null`() = runBlocking {
        assertNull(source.lookup(MetadataQuery(PlatformId.SWITCH, contentHash = ContentHash(sha1 = "11".repeat(20)))))
        assertNull(source.lookup(MetadataQuery(PlatformId.GB, contentHash = ContentHash(crc32 = "12345678"))))
        assertNull(source.lookup(MetadataQuery(PlatformId.GB, contentHash = ContentHash(sha1 = "ff".repeat(20)))))
    }

    @Test
    fun `a mapped platform whose shard is absent caches an empty result`() {
        assertEquals(0, source.entryCount(PlatformId.PS1))
    }

    private fun asset(
        platform: PlatformId,
        sourceId: String,
        assetName: String,
        origin: MetadataOrigin,
        header: String,
        bytes: ByteArray,
        entries: Int,
        inputDigest: String,
        semanticDigest: String,
    ) = BundledDatAsset(
        platformId = platform,
        sourceId = sourceId,
        assetName = assetName,
        origin = origin,
        expectedHeaderName = header,
        legacyXmlCompressedSizeBytes = bytes.size.toLong(),
        packedSizeBytes = bytes.size.toLong(),
        expectedEntryCount = entries,
        expectedUniqueSha1Count = entries,
        expectedAmbiguousSha1Count = 0,
        contentSha256 = inputDigest,
        semanticMapSha256 = semanticDigest,
        packedSha256 = "00".repeat(32),
    )
}
