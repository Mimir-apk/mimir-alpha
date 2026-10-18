package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class IdMatchTest {

    // ── IdExtractor ──────────────────────────────────────────────────────────
    @Test
    fun `arcade key is the zip basename shortname`() {
        val id = IdExtractor.fromName("arcade", "691E:ROMs/arcade/robotbwl.zip")
        assertEquals(ExtractedId(IdKeyType.NAME, "robotbwl"), id)
    }

    @Test
    fun `switch title id is pulled from the filename and folded to the base app`() {
        assertEquals("0100ABCD1234E000", IdExtractor.switchBaseTitleId("Pokemon Legends [0100ABCD1234E000] [v0].nsp"))
        // an update id (…800) folds to the same base game
        assertEquals("0100ABCD1234E000", IdExtractor.switchBaseTitleId("Pokemon Update [0100ABCD1234E800].nsp"))
        assertNull(IdExtractor.switchBaseTitleId("No Title Id Here.nsp"))
    }

    @Test
    fun `gamecube game id from a raw iso header (offset 0)`() {
        val head = ByteArray(64).also { "GZLE01".toByteArray(Charsets.US_ASCII).copyInto(it, 0) }
        assertEquals(ExtractedId(IdKeyType.GAME_ID, "GZLE01"), IdExtractor.fromHeader("gamecube", head, 64))
    }

    @Test
    fun `wii game id from an rvz container header (copied disc header)`() {
        val head = ByteArray(128)
        byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'Z'.code.toByte(), 0x01).copyInto(head, 0)
        // disc_offset (u64 BE @ 0x24) = 0x48 → wia_disc_t there → game id at 0x48 + 16 = 0x58
        head[0x2B] = 0x48
        "RMCE01".toByteArray(Charsets.US_ASCII).copyInto(head, 0x58)
        assertEquals(ExtractedId(IdKeyType.GAME_ID, "RMCE01"), IdExtractor.fromHeader("wii", head, 128))
    }

    @Test
    fun `ps3 title id is parsed from bounded PARAM SFO and exact filename tokens`() {
        val sfo = paramSfo("TITLE_ID", "BLUS30443")
        assertEquals("BLUS30443", IdExtractor.paramSfoTitleId(sfo))
        assertEquals(ExtractedId(IdKeyType.SERIAL, "BLUS30443"), IdExtractor.fromHeader("ps3", sfo, sfo.size))
        assertEquals("DIENSPOOF", IdExtractor.paramSfoTitleId(paramSfo("TITLE_ID", "DIENSPOOF")))
        assertEquals("BLES01807", IdExtractor.ps3TitleIdFromName("Game [BLES01807].iso"))
        assertNull(IdExtractor.ps3TitleIdFromName("Game [DIENSPOOF].iso"))
        assertNull(IdExtractor.ps3TitleIdFromName("Game BLES-01807.iso"))
        assertNull(IdExtractor.ps3TitleIdFromName("ordinary-game-2026.iso"))
    }

    @Test
    fun `ps3 title id is parsed from a strict ISO9660 volume identifier`() {
        val iso = ps3IsoHead("BLUS30653")
        assertEquals("BLUS30653", IdExtractor.ps3IsoVolumeTitleId(iso))
        assertEquals(
            ExtractedId(IdKeyType.SERIAL, "BLUS30653"),
            IdExtractor.fromHeader("ps3", iso, iso.size),
        )
        assertNull(IdExtractor.ps3IsoVolumeTitleId(iso.copyOf(32_839)))
        assertNull(IdExtractor.ps3IsoVolumeTitleId(ps3IsoHead("GAME DISC")))
        assertNull(IdExtractor.ps3IsoVolumeTitleId(iso.copyOf().also { it[32_769] = 'X'.code.toByte() }))
        assertNull(IdExtractor.ps3IsoVolumeTitleId(iso.copyOf().also { it[32_774] = 2 }))
    }

    @Test
    fun `malformed PARAM SFO fails closed`() {
        val valid = paramSfo("TITLE_ID", "BLUS30443")
        assertNull(IdExtractor.paramSfoTitleId(valid.copyOf(24)))
        assertNull(IdExtractor.paramSfoTitleId(valid.copyOf().also { it[0] = 1 }))
        assertNull(IdExtractor.paramSfoTitleId(valid.copyOf().also { it[4] = 2 }))
        assertNull(IdExtractor.paramSfoTitleId(valid.copyOf().also { it[22] = 0 })) // non-string format
        assertNull(IdExtractor.paramSfoTitleId(valid.copyOf().also { it[8] = 0 })) // key table overlaps index
        assertNull(IdExtractor.paramSfoTitleId(paramSfo("TITLE", "BLUS30443")))
        assertNull(IdExtractor.paramSfoTitleId(paramSfo("TITLE_ID", "NOT-A-PS3-ID")))
        assertNull(IdExtractor.paramSfoTitleId(paramSfo("TITLE_ID", " BLUS30443")))
        assertNull(IdExtractor.paramSfoTitleId(paramSfo("TITLE_ID", "BLUS30443\u0000X")))
    }

    // ── IdCatalog + parser ───────────────────────────────────────────────────
    @Test
    fun `catalog parses TSV and looks up case-insensitively`() {
        val tsv = """
            # comment
            NAME	robotbwl	Robot Bowl
            NAME	pacman	Pac-Man

            bogus line with no tabs
        """.trimIndent()
        val cat = IdCatalog(IdCatalogParser.parse(tsv.byteInputStream(), "arcade"))
        assertEquals(2, cat.size)
        assertEquals("Robot Bowl", cat.lookup("arcade", IdKeyType.NAME, "ROBOTBWL")?.canonicalTitle)
        assertNull(cat.lookup("arcade", IdKeyType.NAME, "unknown"))
    }

    // ── BundledIdSource (hermetic) ───────────────────────────────────────────
    private fun gz(s: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    private fun paramSfo(key: String, value: String): ByteArray {
        val keyBytes = (key + '\u0000').toByteArray(Charsets.US_ASCII)
        val valueBytes = (value + '\u0000').toByteArray(Charsets.UTF_8)
        val keyAt = 36
        val dataAt = (keyAt + keyBytes.size + 3) and -4
        val out = ByteArray(dataAt + valueBytes.size.coerceAtLeast(16))
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))
        buffer.putInt(0x00000101)
        buffer.putInt(keyAt)
        buffer.putInt(dataAt)
        buffer.putInt(1)
        buffer.putShort(0)
        buffer.putShort(0x0204)
        buffer.putInt(valueBytes.size)
        buffer.putInt(valueBytes.size.coerceAtLeast(16))
        buffer.putInt(0)
        keyBytes.copyInto(out, keyAt)
        valueBytes.copyInto(out, dataAt)
        return out
    }

    private fun ps3IsoHead(volumeId: String): ByteArray {
        val output = ByteArray(32_840)
        val descriptor = 16 * 2_048
        output[descriptor] = 1
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(output, descriptor + 1)
        output[descriptor + 6] = 1
        ByteArray(32) { ' '.code.toByte() }.copyInto(output, descriptor + 40)
        volumeId.toByteArray(Charsets.US_ASCII).copyInto(output, descriptor + 40)
        return output
    }

    @Test
    fun `source resolves arcade by name, switch by title id, gamecube by game id`() {
        val arcadeRaw = "NAME\trobotbwl\tRobot Bowl\n".toByteArray()
        val switchRaw = "TITLE_ID\t0100ABCD1234E000\tPokemon Legends Z-A\n".toByteArray()
        val gameTdbRaw = "GAME_ID\tGZLE01\tLegend of Zelda: Twilight Princess\n".toByteArray()
        val ps3Raw = "SERIAL\tBLUS30443\tCall of Duty: Modern Warfare 2\n".toByteArray()
        val compressed = mapOf(
            "arcade.idcatgz" to gz(arcadeRaw),
            "switch.idcatgz" to gz(switchRaw),
            "gametdb.idcatgz" to gz(gameTdbRaw),
            "ps3tdb.idcatgz" to gz(ps3Raw),
        )
        val provider = DatAssetProvider { name ->
            compressed[name]?.let(::ByteArrayInputStream)
        }
        val manifest = listOf(
            idAsset("arcade", "arcade.idcatgz", MetadataOrigin.MAME, IdKeyType.NAME, arcadeRaw, compressed),
            idAsset(
                "switch",
                "switch.idcatgz",
                MetadataOrigin.TITLE_CATALOG,
                IdKeyType.TITLE_ID,
                switchRaw,
                compressed,
            ),
            idAsset(
                "gamecube",
                "gametdb.idcatgz",
                MetadataOrigin.TITLE_CATALOG,
                IdKeyType.GAME_ID,
                gameTdbRaw,
                compressed,
            ),
            idAsset(
                "ps3",
                "ps3tdb.idcatgz",
                MetadataOrigin.TITLE_CATALOG,
                IdKeyType.SERIAL,
                ps3Raw,
                compressed,
            ),
        )
        val src = BundledIdSource(provider, manifest)

        val arcade = src.resolve("arcade", "x/robotbwl.zip", null, 0) as BundledIdResolution.Identified
        assertEquals("Robot Bowl", arcade.match.record.canonicalTitle)
        assertEquals(MetadataOrigin.MAME, arcade.match.record.origin)
        assertEquals(BundledIdBinding.FILENAME, arcade.match.binding)

        val switch = src.resolve(
            "switch",
            "Pokemon Legends [0100ABCD1234E800] [v131072].nsp",
            null,
            0,
        ) as BundledIdResolution.Identified
        assertEquals("Pokemon Legends Z-A", switch.match.record.canonicalTitle) // update id folded to base
        assertEquals(BundledIdBinding.FILENAME, switch.match.binding)

        val isoHead = ByteArray(32).also { "GZLE01".toByteArray(Charsets.US_ASCII).copyInto(it, 0) }
        val gc = src.resolve("gamecube", null, isoHead, 32) as BundledIdResolution.Identified
        assertEquals("Legend of Zelda: Twilight Princess", gc.match.record.canonicalTitle)
        assertEquals(BundledIdBinding.EMBEDDED_HEADER, gc.match.binding)

        val ps3 = src.resolve(
            "ps3",
            null,
            paramSfo("TITLE_ID", "BLUS30443"),
            64,
        ) as BundledIdResolution.Identified
        assertEquals("Call of Duty: Modern Warfare 2", ps3.match.record.canonicalTitle)
        assertEquals(BundledIdBinding.EMBEDDED_HEADER, ps3.match.binding)

        // unknown key / unmapped platform → typed no-match.
        assertEquals(BundledIdResolution.NoMatch, src.resolve("arcade", "x/unknowngame.zip", null, 0))
        assertEquals(BundledIdResolution.NoMatch, src.resolve("ps1", "whatever", null, 0))
    }

    private fun idAsset(
        platform: String,
        assetName: String,
        origin: MetadataOrigin,
        keyType: IdKeyType,
        raw: ByteArray,
        compressed: Map<String, ByteArray>,
    ) = BundledIdAsset(
        platformId = com.gruning.mimir.rewrite.core.model.PlatformId(platform),
        assetName = assetName,
        origin = origin,
        keyType = keyType,
        expectedEntryCount = 1,
        contentSha256 = sha256(raw),
        compressedSizeBytes = requireNotNull(compressed[assetName]).size.toLong(),
    )

    private fun gz(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
