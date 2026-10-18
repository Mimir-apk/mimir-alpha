package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class BundledIdSourceIntegrityTest {

    @Test
    fun `valid locked catalog resolves and exposes exact count`() {
        val fixture = fixture("NAME\trobotbwl\tRobot Bowl\n")
        val source = source(fixture)

        assertEquals(1, source.entryCount("arcade"))
        val result = source.resolve("arcade", "robotbwl.zip", null, 0)
        result as BundledIdResolution.Identified
        assertEquals("Robot Bowl", result.match.record.canonicalTitle)
        assertEquals(BundledIdBinding.FILENAME, result.match.binding)
    }

    @Test
    fun `compressed size mismatch fails closed`() {
        val fixture = fixture("NAME\trobotbwl\tRobot Bowl\n")

        assertRejected(fixture.copy(asset = fixture.asset.copy(compressedSizeBytes = fixture.compressed.size + 1L)))
    }

    @Test
    fun `decompressed digest mismatch fails closed`() {
        val fixture = fixture("NAME\trobotbwl\tRobot Bowl\n")

        assertRejected(fixture.copy(asset = fixture.asset.copy(contentSha256 = "0".repeat(64))))
    }

    @Test
    fun `entry count mismatch fails closed`() {
        val fixture = fixture("NAME\trobotbwl\tRobot Bowl\n")

        assertRejected(fixture.copy(asset = fixture.asset.copy(expectedEntryCount = 2)))
    }

    @Test
    fun `mixed key type fails closed`() {
        val fixture = fixture("TITLE_ID\t0100000000000000\tNot an arcade shortname\n")

        assertRejected(fixture)
    }

    @Test
    fun `duplicate case folded key fails closed`() {
        val fixture = fixture(
            """
            NAME	robotbwl	Robot Bowl
            NAME	ROBOTBWL	Different title
            """.trimIndent() + "\n",
            expectedEntries = 2,
        )

        assertRejected(fixture)
    }

    @Test
    fun `corrupt gzip fails closed without escaping`() {
        val fixture = fixture("NAME\trobotbwl\tRobot Bowl\n")
        val corrupt = fixture.compressed.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0xff).toByte()
        }

        assertRejected(
            fixture.copy(
                compressed = corrupt,
                asset = fixture.asset.copy(compressedSizeBytes = corrupt.size.toLong()),
            ),
        )
    }

    @Test
    fun `missing payload fails closed and remains cached empty`() {
        val fixture = fixture("NAME\trobotbwl\tRobot Bowl\n")
        var opens = 0
        val source = BundledIdSource(
            provider = DatAssetProvider {
                opens++
                null
            },
            manifest = listOf(fixture.asset),
        )

        assertEquals(0, source.entryCount("arcade"))
        assertEquals(BundledIdResolution.NoMatch, source.resolve("arcade", "robotbwl.zip", null, 0))
        assertEquals(1, opens)
    }

    private fun assertRejected(fixture: Fixture) {
        val source = source(fixture)
        assertEquals(0, source.entryCount("arcade"))
        assertEquals(BundledIdResolution.NoMatch, source.resolve("arcade", "robotbwl.zip", null, 0))
    }

    private fun source(fixture: Fixture) = BundledIdSource(
        provider = DatAssetProvider { name ->
            fixture.compressed.takeIf { name == fixture.asset.assetName }?.let(::ByteArrayInputStream)
        },
        manifest = listOf(fixture.asset),
    )

    private fun fixture(rawText: String, expectedEntries: Int = 1): Fixture {
        val raw = rawText.toByteArray()
        val compressed = gzip(raw)
        return Fixture(
            compressed = compressed,
            asset = BundledIdAsset(
                platformId = PlatformId.ARCADE,
                assetName = "fixture.idcatgz",
                origin = MetadataOrigin.MAME,
                keyType = IdKeyType.NAME,
                expectedEntryCount = expectedEntries,
                contentSha256 = sha256(raw),
                compressedSizeBytes = compressed.size.toLong(),
            ),
        )
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
    }.toByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Fixture(
        val compressed: ByteArray,
        val asset: BundledIdAsset,
    )
}
