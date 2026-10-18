package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves A1B keeps filename candidates useful without mistaking them for byte-bound identity. */
class AthenaA1FilenameEvidenceTest {

    @Test
    fun `filename carried catalog keys remain provisional for deliberately unrelated payloads`() {
        val fixture = source()
        val unrelatedHead = "NOT THE CATALOGUED GAME".toByteArray()

        val resolutions = listOf(
            fixture.resolve("arcade", "pacman.zip", unrelatedHead, unrelatedHead.size),
            fixture.resolve(
                "switch",
                "Unrelated Payload [01007EF00011E000].nsp",
                unrelatedHead,
                unrelatedHead.size,
            ),
            fixture.resolve(
                "ps3",
                "Unrelated Payload [BLUS30443].iso",
                unrelatedHead,
                unrelatedHead.size,
            ),
        )

        resolutions.forEach { resolution ->
            resolution as BundledIdResolution.Identified
            assertEquals(BundledIdBinding.FILENAME, resolution.match.binding)
            assertEquals(
                AthenaIdentityDecision.Abstained(AthenaIdentityDecisionReason.INSUFFICIENT_AUTHORITY),
                AthenaIdentityEngine().decide(listOf(resolution.match.toAthenaCandidate())),
            )
        }
    }

    @Test
    fun `embedded game id remains payload bound despite a deceptive filename`() {
        val head = ByteArray(64).also {
            "GZLE01".toByteArray(Charsets.US_ASCII).copyInto(it)
        }

        val result = source().resolve("gamecube", "pacman.zip", head, head.size)
        result as BundledIdResolution.Identified
        assertEquals("The Legend of Zelda: The Wind Waker", result.match.record.canonicalTitle)
        assertEquals(BundledIdBinding.EMBEDDED_HEADER, result.match.binding)
        assertEquals(
            AthenaIdentityDecision.Identified(result.match.toAthenaCandidate()),
            AthenaIdentityEngine().decide(listOf(result.match.toAthenaCandidate())),
        )
    }

    @Test
    fun `ps3 filename and embedded sfo disagreement is typed and embedded authority wins`() {
        val fixture = source(
            ps3Rows =
                "SERIAL\tBLUS30443\tDemon's Souls\n" +
                    "SERIAL\tBLES01807\tPersona 5\n",
        )

        val resolution = fixture.resolve(
            "ps3",
            "Misnamed [BLUS30443].iso",
            paramSfo("TITLE_ID", "BLES01807"),
            64,
        )
        resolution as BundledIdResolution.Conflict
        assertEquals(
            "Persona 5",
            resolution.embedded.record.canonicalTitle,
        )
        assertEquals(
            "Demon's Souls",
            resolution.filename.record.canonicalTitle,
        )
        val embedded = resolution.embedded.toAthenaCandidate()
        val filename = resolution.filename.toAthenaCandidate()
        assertEquals(
            AthenaIdentityDecision.Identified(embedded),
            AthenaIdentityEngine().decide(listOf(filename, embedded)),
        )
    }

    private fun BundledIdMatch.toAthenaCandidate() = AthenaIdentityCandidate(
        canonicalTitle = record.canonicalTitle,
        collapseKey = null,
        origin = record.origin,
        evidence = when (binding) {
            BundledIdBinding.EMBEDDED_HEADER -> AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY
            BundledIdBinding.FILENAME -> AthenaIdentityEvidence.FILENAME_CATALOG_KEY_CANDIDATE
        },
    )

    private fun source(
        ps3Rows: String = "SERIAL\tBLUS30443\tDemon's Souls\n",
    ): BundledIdSource {
        val rows = mapOf(
            "arcade.idcatgz" to "NAME\tpacman\tPac-Man (Midway)\n",
            "switch.idcatgz" to
                "TITLE_ID\t01007EF00011E000\tThe Legend of Zelda: Breath of the Wild\n",
            "gametdb.idcatgz" to "GAME_ID\tGZLE01\tThe Legend of Zelda: The Wind Waker\n",
            "ps3tdb.idcatgz" to ps3Rows,
        ).mapValues { (_, value) -> value.toByteArray() }
        val compressed = rows.mapValues { (_, value) -> gzip(value) }
        val provider = DatAssetProvider { name ->
            compressed[name]?.let(::ByteArrayInputStream)
        }
        val manifest = listOf(
            asset("arcade", "arcade.idcatgz", MetadataOrigin.MAME, IdKeyType.NAME, rows, compressed),
            asset(
                "switch",
                "switch.idcatgz",
                MetadataOrigin.TITLE_CATALOG,
                IdKeyType.TITLE_ID,
                rows,
                compressed,
            ),
            asset(
                "gamecube",
                "gametdb.idcatgz",
                MetadataOrigin.TITLE_CATALOG,
                IdKeyType.GAME_ID,
                rows,
                compressed,
            ),
            asset(
                "ps3",
                "ps3tdb.idcatgz",
                MetadataOrigin.TITLE_CATALOG,
                IdKeyType.SERIAL,
                rows,
                compressed,
                expectedEntryCount = ps3Rows.lineSequence().count { it.isNotBlank() },
            ),
        )
        return BundledIdSource(provider, manifest)
    }

    private fun asset(
        platform: String,
        assetName: String,
        origin: MetadataOrigin,
        keyType: IdKeyType,
        rows: Map<String, ByteArray>,
        compressed: Map<String, ByteArray>,
        expectedEntryCount: Int = 1,
    ) = BundledIdAsset(
        platformId = PlatformId(platform),
        assetName = assetName,
        origin = origin,
        keyType = keyType,
        expectedEntryCount = expectedEntryCount,
        contentSha256 = sha256(requireNotNull(rows[assetName])),
        compressedSizeBytes = requireNotNull(compressed[assetName]).size.toLong(),
    )

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun paramSfo(key: String, value: String): ByteArray {
        val keyBytes = (key + '\u0000').toByteArray(Charsets.US_ASCII)
        val valueBytes = (value + '\u0000').toByteArray(Charsets.UTF_8)
        val keyAt = 36
        val dataAt = (keyAt + keyBytes.size + 3) and -4
        val output = ByteArray(dataAt + valueBytes.size.coerceAtLeast(16))
        ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))
            putInt(0x00000101)
            putInt(keyAt)
            putInt(dataAt)
            putInt(1)
            putShort(0)
            putShort(0x0204)
            putInt(valueBytes.size)
            putInt(valueBytes.size.coerceAtLeast(16))
            putInt(0)
        }
        keyBytes.copyInto(output, keyAt)
        valueBytes.copyInto(output, dataAt)
        return output
    }
}
