package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.IdentityWrite
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.InputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  In
 * [ChdIdentitySource.HEADER_DECLARED] a loose CHD is identified from its header's combined SHA-1 alone, and the
 * native seek path that libchdr decompresses through is never opened.
 *
 * The catalogue is the hermetic one-entry Redump fixture [LibraryIdentityEnricherPs3Test] uses: SHA-1
 * `812cef99…` over 24 bytes is "Fallback Game". PS3 is id-keyed, so the dump-name path steps aside and every
 * case here reaches the CHD route.
 */
class LibraryIdentityEnricherChdHeaderTest {

    @Test
    fun `a header digest the catalogue carries identifies without decompressing anything`() = runBlocking {
        val fixture = DaoFixture(chdGame())
        val opener = ChdOpener(chdHeader(combinedSha1 = FixtureSha1, logicalBytes = 24L))

        val report = enricher(fixture.dao, opener, ChdIdentitySource.HEADER_DECLARED)
            .enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.matched)
        assertEquals("Fallback Game", fixture.write?.title)
        assertEquals(MetadataOrigin.REDUMP.name, fixture.write?.origin)
        assertFalse("libchdr's native path must never be opened", opener.nativeOpened)
    }

    @Test
    fun `a header digest the catalogue lacks abstains at once, as the recompute's miss would`() = runBlocking {
        val fixture = DaoFixture(chdGame())
        val opener = ChdOpener(chdHeader(combinedSha1 = "d".repeat(40), logicalBytes = 24L))

        val report = enricher(fixture.dao, opener, ChdIdentitySource.HEADER_DECLARED)
            .enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(0, report.matched)
        assertEquals(0, report.retryable)
        // Stamped: the header does not change, so the abstention is idempotent.
        assertEquals(8L, fixture.write?.ts)
        assertNull(fixture.write?.title)
        assertFalse(opener.nativeOpened)
    }

    @Test
    fun `a differential child is identified by its own header without discovering its parent`() = runBlocking {
        // The DAO fixture fails on any call but the enricher's own queue and write, so a parent inventory
        // lookup would throw here.
        val fixture = DaoFixture(chdGame())
        val opener = ChdOpener(
            chdHeader(combinedSha1 = FixtureSha1, logicalBytes = 24L, parentSha1 = "e".repeat(40)),
        )

        val report = enricher(fixture.dao, opener, ChdIdentitySource.HEADER_DECLARED)
            .enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.matched)
        assertEquals("Fallback Game", fixture.write?.title)
        assertFalse(opener.nativeOpened)
    }

    @Test
    fun `the recompute source still goes to libchdr, so the mode is what skips it`() = runBlocking {
        val fixture = DaoFixture(chdGame())
        val opener = ChdOpener(chdHeader(combinedSha1 = FixtureSha1, logicalBytes = 24L))

        val report = enricher(fixture.dao, opener, ChdIdentitySource.RECOMPUTED_PAYLOAD)
            .enrich(maxGames = 1, batch = 1, reconcile = false)

        assertTrue("the recompute opens the native path", opener.nativeOpened)
        // There is no real CHD behind the fake path, so libchdr cannot confirm it: retry, never a title.
        assertEquals(0, report.matched)
        assertNull(fixture.write?.title)
    }

    private fun enricher(dao: GameDao, opener: ContentOpener, chdIdentity: ChdIdentitySource) =
        LibraryIdentityEnricher(
            dao = dao,
            datSource = BundledDatSource(
                PackedDatAssetProvider { name ->
                    if (name == "ps3-fixture.midx") ByteBuffer.wrap(FixturePacked) else null
                },
                FixtureManifest,
            ),
            idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
            opener = opener,
            chdIdentity = chdIdentity,
            nowMillis = { 8L },
        )

    private class ChdOpener(private val header: ByteArray) : ContentOpener {
        var nativeOpened = false

        override fun open(documentUri: String?, managedPath: String?): InputStream? =
            error("CHD identity reads the header through random access, never a stream")

        override fun openRandomAccess(documentUri: String?, managedPath: String?): RandomAccessContent =
            ByteArrayRandomAccess(header)

        override fun openNativeSeekablePath(documentUri: String?, managedPath: String?): NativeSeekablePath {
            nativeOpened = true
            return object : NativeSeekablePath {
                override val path = "/not-a-host-chd"
                override fun close() = Unit
            }
        }
    }

    private class DaoFixture(private val game: GameEntity) {
        var write: IdentityWrite? = null
        private var resolved = false

        val dao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" ->
                    if (resolved) emptyList<GameEntity>() else listOf(game)
                "setIdentities" -> {
                    @Suppress("UNCHECKED_CAST")
                    write = (requireNotNull(args)[0] as List<IdentityWrite>).single()
                    resolved = true
                    Unit
                }
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao
    }

    private class ByteArrayRandomAccess(private val bytes: ByteArray) : RandomAccessContent {
        override val length = bytes.size.toLong()

        override fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, byteCount: Int): Int {
            if (offset !in 0 until length) return 0
            val count = minOf(byteCount, bytes.size - offset.toInt())
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
            return count
        }

        override fun close() = Unit
    }

    private fun chdGame() = GameEntity(
        entryKey = "root::renamed-disc.chd",
        gameId = "ps3-game",
        platformId = "ps3",
        contentKind = "GAME",
        displayName = "renamed-disc.chd",
        title = "Before",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = 100L,
        contentHash = null,
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = "/roms/renamed-disc.chd",
        relativePath = "renamed-disc.chd",
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    /** A structurally valid CHD v5 header (the layout [RomFingerprinter.chdHeaderEvidence] reads). */
    private fun chdHeader(combinedSha1: String, logicalBytes: Long, parentSha1: String? = null): ByteArray =
        ByteArray(132).also { bytes ->
            "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(bytes)
            bytes.putBe32(8, 124)
            bytes.putBe32(12, 5)
            bytes.putBe64(32, logicalBytes)
            bytes.putBe32(56, 4096)
            bytes.putBe32(60, 512)
            repeat(20) { index -> bytes[64 + index] = (index + 1).toByte() }
            hex(combinedSha1).copyInto(bytes, 84)
            parentSha1?.let { hex(it).copyInto(bytes, 104) }
        }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.putBe32(at: Int, value: Int) {
        repeat(4) { index -> this[at + index] = (value ushr ((3 - index) * 8)).toByte() }
    }

    private fun ByteArray.putBe64(at: Int, value: Long) {
        repeat(8) { index -> this[at + index] = (value ushr ((7 - index) * 8)).toByte() }
    }

    private companion object {
        const val FixtureSha1 = "812cef99224fee32826887c102b48abeef38f995"

        val FixturePacked: ByteArray = Base64.getDecoder().decode(
            "TUlNSURYMQABAMAAAwAAABEAAAADAAAABgAAAAsAAAABAAAAAQAAAAAAAAABAAAAAQAAABgAAAAIAAAAAAAA" +
                "AMAAAAAAAAAA6AAAAAAAAAAAAQAAAAAAAAgBAAAAAAAAEAEAAAAAAAAPAAAAAAAAAA0AAAAAAAAAHwEAAAAA" +
                "AABfmTM0nRnCBjXxoEJA1ehXf8VZiUSBq9Dq+l+7b5ii9M/Oyuyfd9mQKBzen17PJBLyNYNzM28jfXcWdXXc" +
                "/cWfcHMzLWZpeHR1cmUuZGF0Z3pwczNSRURVTVBGaXh0dXJlIFBTMwAAAIEs75kiT+4ygmiHwQK0ir7vOPmV" +
                "AAAAAAAAAAAAAAAAAAAAAA0AAABzS8zJSUpMzlZwT8xNBQA=",
        )

        val FixtureManifest = listOf(
            BundledDatAsset(
                platformId = PlatformId("ps3"),
                sourceId = "ps3-fixture.datgz",
                assetName = "ps3-fixture.midx",
                origin = MetadataOrigin.REDUMP,
                expectedHeaderName = "Fixture PS3",
                legacyXmlCompressedSizeBytes = FixturePacked.size.toLong(),
                packedSizeBytes = FixturePacked.size.toLong(),
                expectedEntryCount = 1,
                expectedUniqueSha1Count = 1,
                expectedAmbiguousSha1Count = 0,
                contentSha256 = "5f9933349d19c20635f1a04240d5e8577fc559894481abd0eafa5fbb6f98a2f4",
                semanticMapSha256 = "cfcecaec9f77d990281cde9f5ecf2412f2358373336f237d77167575dcfdc59f",
                packedSha256 = "a10ee47a6cdf46e494af3281237c3b269d70d82ccb1c1543ea0b8a397980bfea",
            ),
        )
    }
}
