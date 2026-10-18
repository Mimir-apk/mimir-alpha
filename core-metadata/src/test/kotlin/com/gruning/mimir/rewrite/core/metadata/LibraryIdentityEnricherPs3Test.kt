package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.IdentityWrite
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryIdentityEnricherPs3Test {

    @Test
    fun `conflicting filename cannot outrank payload bound PARAM SFO`() = runBlocking {
        val fixture = DaoFixture(ps3Game("/games/Misnamed [BLUS30443]/PS3_GAME/USRDIR/EBOOT.BIN"))
        var primaryOpens = 0
        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): InputStream? {
                primaryOpens++
                return ByteArrayInputStream("must not hash".toByteArray())
            }

            override fun openRelated(
                documentUri: String?,
                managedPath: String?,
                relativePath: String,
            ): InputStream? = if (relativePath == "../PARAM.SFO") {
                ByteArrayInputStream(paramSfo("BLES01807"))
            } else null
        }
        val raw =
            "SERIAL\tBLUS30443\tDemon's Souls\n" +
                "SERIAL\tBLES01807\tPersona 5\n"
        val idBytes = gz(raw)
        val source = BundledIdSource(
            provider = DatAssetProvider { name ->
                if (name == "ps3tdb.idcatgz") ByteArrayInputStream(idBytes) else null
            },
            manifest = listOf(
                BundledIdAsset(
                    PlatformId("ps3"),
                    "ps3tdb.idcatgz",
                    MetadataOrigin.TITLE_CATALOG,
                    IdKeyType.SERIAL,
                    2,
                    sha256(raw.toByteArray()),
                    idBytes.size.toLong(),
                ),
            ),
        )

        val report = LibraryIdentityEnricher(
            chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
            dao = fixture.dao,
            datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
            idSource = source,
            opener = opener,
            nowMillis = { 6L },
        ).enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.matched)
        assertEquals(0, primaryOpens)
        assertEquals("Persona 5", fixture.write?.title)
    }

    @Test
    fun `PARAM SFO title id resolves before whole image hashing`() = runBlocking {
        val fixture = DaoFixture(ps3Game("/games/Example/PS3_GAME/USRDIR/EBOOT.BIN"))
        var primaryOpens = 0
        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): InputStream? {
                primaryOpens++
                return ByteArrayInputStream("must not hash".toByteArray())
            }

            override fun openRelated(
                documentUri: String?,
                managedPath: String?,
                relativePath: String,
            ): InputStream? = if (relativePath == "../PARAM.SFO") {
                ByteArrayInputStream(paramSfo("BLUS30443"))
            } else null
        }
        val idBytes = gz("SERIAL\tBLUS30443\tCall of Duty: Modern Warfare 2\n")
        val idManifest = listOf(
            BundledIdAsset(
                PlatformId("ps3"),
                "ps3tdb.idcatgz",
                MetadataOrigin.TITLE_CATALOG,
                IdKeyType.SERIAL,
                1,
                sha256("SERIAL\tBLUS30443\tCall of Duty: Modern Warfare 2\n".toByteArray()),
                idBytes.size.toLong(),
            ),
        )
        val provider = DatAssetProvider { name ->
            if (name == "ps3tdb.idcatgz") ByteArrayInputStream(idBytes) else null
        }
        val report = LibraryIdentityEnricher(
            chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
            dao = fixture.dao,
            datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
            idSource = BundledIdSource(provider, manifest = idManifest),
            opener = opener,
            nowMillis = { 7L },
        ).enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.matched)
        assertEquals(0, primaryOpens)
        assertEquals("Call of Duty: Modern Warfare 2", fixture.write?.title)
        assertEquals(MetadataOrigin.TITLE_CATALOG.name, fixture.write?.origin)
    }

    @Test
    fun `ISO9660 volume title id resolves from bounded primary head before whole image hashing`() = runBlocking {
        val isoHead = ps3IsoHead("BLUS30653")
        val fixture = DaoFixture(ps3Game("/games/Disc Image.iso").copy(sizeBytes = 6_649_675_776L))
        var primaryOpens = 0
        val opener = ContentOpener { _, _ ->
            primaryOpens++
            ByteArrayInputStream(isoHead)
        }
        val raw = "SERIAL\tBLUS30653\tShadows of the Damned\n"
        val idBytes = gz(raw)
        val source = BundledIdSource(
            provider = DatAssetProvider { name ->
                if (name == "ps3tdb.idcatgz") ByteArrayInputStream(idBytes) else null
            },
            manifest = listOf(
                BundledIdAsset(
                    PlatformId("ps3"),
                    "ps3tdb.idcatgz",
                    MetadataOrigin.TITLE_CATALOG,
                    IdKeyType.SERIAL,
                    1,
                    sha256(raw.toByteArray()),
                    idBytes.size.toLong(),
                ),
            ),
        )

        val report = LibraryIdentityEnricher(
            chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
            dao = fixture.dao,
            datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
            idSource = source,
            opener = opener,
            nowMillis = { 10L },
        ).enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.matched)
        assertEquals(1, primaryOpens)
        assertEquals("Shadows of the Damned", fixture.write?.title)
        assertEquals(MetadataOrigin.TITLE_CATALOG.name, fixture.write?.origin)
    }

    @Test
    fun `filename serial alone cannot write identity and no longer suppresses hash fallback`() = runBlocking {
        val bytes = "unrelated PS3 payload".toByteArray()
        val fixture = DaoFixture(ps3Game("/games/Unrelated [BLUS30443].iso"))
        var primaryOpens = 0
        val opener = ContentOpener { _, _ ->
            primaryOpens++
            ByteArrayInputStream(bytes)
        }
        val raw = "SERIAL\tBLUS30443\tDemon's Souls\n"
        val idBytes = gz(raw)
        val source = BundledIdSource(
            provider = DatAssetProvider { name ->
                if (name == "ps3tdb.idcatgz") ByteArrayInputStream(idBytes) else null
            },
            manifest = listOf(
                BundledIdAsset(
                    PlatformId("ps3"),
                    "ps3tdb.idcatgz",
                    MetadataOrigin.TITLE_CATALOG,
                    IdKeyType.SERIAL,
                    1,
                    sha256(raw.toByteArray()),
                    idBytes.size.toLong(),
                ),
            ),
        )

        val report = LibraryIdentityEnricher(
            chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
            dao = fixture.dao,
            datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
            idSource = source,
            opener = opener,
            nowMillis = { 9L },
        ).enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(0, report.matched)
        assertEquals(2, primaryOpens)
        assertEquals(null, fixture.write?.title)
        assertEquals(null, fixture.write?.origin)
    }

    @Test
    fun `missing PARAM SFO falls back to the Redump whole image hash`() = runBlocking {
        val bytes = "small hermetic PS3 image".toByteArray()
        val packed = Base64.getDecoder().decode(
            "TUlNSURYMQABAMAAAwAAABEAAAADAAAABgAAAAsAAAABAAAAAQAAAAAAAAABAAAAAQAAABgAAAAIAAAAAAAA" +
                "AMAAAAAAAAAA6AAAAAAAAAAAAQAAAAAAAAgBAAAAAAAAEAEAAAAAAAAPAAAAAAAAAA0AAAAAAAAAHwEAAAAA" +
                "AABfmTM0nRnCBjXxoEJA1ehXf8VZiUSBq9Dq+l+7b5ii9M/Oyuyfd9mQKBzen17PJBLyNYNzM28jfXcWdXXc" +
                "/cWfcHMzLWZpeHR1cmUuZGF0Z3pwczNSRURVTVBGaXh0dXJlIFBTMwAAAIEs75kiT+4ygmiHwQK0ir7vOPmV" +
                "AAAAAAAAAAAAAAAAAAAAAA0AAABzS8zJSUpMzlZwT8xNBQA=",
        )
        val manifest = listOf(
            BundledDatAsset(
                platformId = PlatformId("ps3"),
                sourceId = "ps3-fixture.datgz",
                assetName = "ps3-fixture.midx",
                origin = MetadataOrigin.REDUMP,
                expectedHeaderName = "Fixture PS3",
                legacyXmlCompressedSizeBytes = packed.size.toLong(),
                packedSizeBytes = packed.size.toLong(),
                expectedEntryCount = 1,
                expectedUniqueSha1Count = 1,
                expectedAmbiguousSha1Count = 0,
                contentSha256 = "5f9933349d19c20635f1a04240d5e8577fc559894481abd0eafa5fbb6f98a2f4",
                semanticMapSha256 = "cfcecaec9f77d990281cde9f5ecf2412f2358373336f237d77167575dcfdc59f",
                packedSha256 = "a10ee47a6cdf46e494af3281237c3b269d70d82ccb1c1543ea0b8a397980bfea",
            ),
        )
        var primaryOpens = 0
        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): InputStream {
                primaryOpens++
                return ByteArrayInputStream(bytes)
            }
        }
        val fixture = DaoFixture(ps3Game("/games/Fallback Game.iso"))
        val report = LibraryIdentityEnricher(
            chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
            dao = fixture.dao,
            datSource = BundledDatSource(
                PackedDatAssetProvider { name ->
                    if (name == "ps3-fixture.midx") ByteBuffer.wrap(packed) else null
                },
                manifest,
            ),
            idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
            opener = opener,
            nowMillis = { 8L },
        ).enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.matched)
        assertEquals(2, primaryOpens)
        assertEquals("Fallback Game", fixture.write?.title)
        assertEquals(MetadataOrigin.REDUMP.name, fixture.write?.origin)
    }

    private class DaoFixture(private val game: GameEntity) {
        var write: IdentityWrite? = null
        private var resolved = false
        val dao = Proxy.newProxyInstance(GameDao::class.java.classLoader, arrayOf(GameDao::class.java)) { _, method, args ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" ->
                    if (resolved) emptyList<GameEntity>() else listOf(game)
                "setIdentities" -> {
                    @Suppress("UNCHECKED_CAST")
                    write = (requireNotNull(args)[0] as List<IdentityWrite>).single()
                    resolved = true
                    Unit
                }
                "setContentHash" -> 1
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao
    }

    private fun ps3Game(path: String) = GameEntity(
        entryKey = "root::$path",
        gameId = "ps3-game",
        platformId = "ps3",
        contentKind = "GAME",
        displayName = path.substringAfterLast('/'),
        title = "Before",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = 24L,
        contentHash = null,
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = path,
        relativePath = path.substringAfter("/games/"),
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    private fun paramSfo(titleId: String): ByteArray {
        val key = "TITLE_ID\u0000".toByteArray(Charsets.US_ASCII)
        val value = "$titleId\u0000".toByteArray(Charsets.UTF_8)
        val keyAt = 36
        val dataAt = 48
        val out = ByteArray(64)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))
            putInt(0x00000101)
            putInt(keyAt)
            putInt(dataAt)
            putInt(1)
            putShort(0)
            putShort(0x0204)
            putInt(value.size)
            putInt(16)
            putInt(0)
        }
        key.copyInto(out, keyAt)
        value.copyInto(out, dataAt)
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

    private fun gz(value: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(value.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
