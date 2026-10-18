package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.IdentityWrite
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryIdentityEnricherFilenameKeyTest {

    @Test
    fun `arcade and switch filename keys do not become durable identity`() = runBlocking {
        listOf(
            FilenameFixture(
                platform = "arcade",
                path = "/roms/robotbwl.zip",
                assetName = "arcade.idcatgz",
                rawCatalog = "NAME\trobotbwl\tRobot Bowl\n",
                origin = MetadataOrigin.MAME,
                keyType = IdKeyType.NAME,
                expectedRetryable = 0,
                expectedTimestamp = 12L,
            ),
            FilenameFixture(
                platform = "switch",
                path = "/roms/Game [01007EF00011E000].nsp",
                assetName = "switch.idcatgz",
                rawCatalog =
                    "TITLE_ID\t01007EF00011E000\tThe Legend of Zelda: Breath of the Wild\n",
                origin = MetadataOrigin.TITLE_CATALOG,
                keyType = IdKeyType.TITLE_ID,
                expectedRetryable = 1,
                expectedTimestamp = null,
            ),
        ).forEach { input ->
            val fixture = DaoFixture(game(input.platform, input.path))
            var payloadOpens = 0
            val compressed = gzip(input.rawCatalog.toByteArray())
            val idSource = BundledIdSource(
                provider = DatAssetProvider { name ->
                    compressed.takeIf { name == input.assetName }?.let(::ByteArrayInputStream)
                },
                manifest = listOf(
                    BundledIdAsset(
                        platformId = PlatformId(input.platform),
                        assetName = input.assetName,
                        origin = input.origin,
                        keyType = input.keyType,
                        expectedEntryCount = 1,
                        contentSha256 = sha256(input.rawCatalog.toByteArray()),
                        compressedSizeBytes = compressed.size.toLong(),
                    ),
                ),
            )

            val report = LibraryIdentityEnricher(
                chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
                dao = fixture.dao,
                datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
                idSource = idSource,
                opener = ContentOpener { _, _ ->
                    payloadOpens++
                    ByteArrayInputStream("unrelated payload".toByteArray())
                },
                nowMillis = { 12L },
            ).enrich(maxGames = 1, batch = 1, reconcile = false)

            assertEquals(input.platform, 1, report.attempted)
            assertEquals(input.platform, 0, report.matched)
            assertEquals(input.platform, input.expectedRetryable, report.retryable)
            assertEquals(input.platform, 0, payloadOpens)
            assertEquals(input.platform, null, fixture.write?.title)
            assertEquals(input.platform, null, fixture.write?.origin)
            assertEquals(input.platform, input.expectedTimestamp, fixture.write?.ts)
        }
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
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao
    }

    private data class FilenameFixture(
        val platform: String,
        val path: String,
        val assetName: String,
        val rawCatalog: String,
        val origin: MetadataOrigin,
        val keyType: IdKeyType,
        val expectedRetryable: Int,
        val expectedTimestamp: Long?,
    )

    private fun game(platform: String, path: String) = GameEntity(
        entryKey = "root::$path",
        gameId = "$platform-game",
        platformId = platform,
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
        relativePath = path.substringAfter("/roms/"),
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    private fun gzip(bytes: ByteArray): ByteArray =
        java.io.ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
        }.toByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
