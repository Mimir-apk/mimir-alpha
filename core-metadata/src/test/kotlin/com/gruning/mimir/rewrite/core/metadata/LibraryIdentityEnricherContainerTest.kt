package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.IdentityWrite
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryIdentityEnricherContainerTest {

    @Test
    fun `optical zip is terminal before any resident opener is invoked`() = runBlocking {
        listOf(
            "ps1",
            "wii",
            "nintendo_wii",
            "pcenginecd",
            "nec_pc_engine_cd_turbografx_cd",
            "segacd",
            "sega_mega_cd_sega_cd",
            "the_3do_company_3do",
        ).forEach { platform ->
            val fixture = DaoFixture(game(platform = platform, fileName = "$platform-disc.zip"))
            var sequentialOpens = 0
            var randomOpens = 0
            val opener = object : ContentOpener {
                override fun open(documentUri: String?, managedPath: String?): InputStream? {
                    sequentialOpens++
                    return null
                }

                override fun openRandomAccess(
                    documentUri: String?,
                    managedPath: String?,
                ): RandomAccessContent? {
                    randomOpens++
                    return null
                }
            }

            val report = enricher(fixture.dao, opener).enrich(
                maxGames = 1,
                batch = 1,
                reconcile = false,
            )

            assertEquals(platform, 1, report.attempted)
            assertEquals(platform, 0, report.matched)
            assertEquals(platform, 0, report.retryable)
            assertEquals(platform, 0, sequentialOpens)
            assertEquals(platform, 0, randomOpens)
            assertEquals(platform, 41L, fixture.write?.ts)
            assertNull(platform, fixture.write?.title)
        }
    }

    @Test
    fun `unseekable Switch package remains unresolved for capability replay`() = runBlocking {
        val fixture = DaoFixture(game(platform = "switch", fileName = "base.nsp"))
        var randomOpens = 0
        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): InputStream? =
                error("Switch package identity must not stream through the sequential seam")

            override fun openRandomAccess(
                documentUri: String?,
                managedPath: String?,
            ): RandomAccessContent? {
                randomOpens++
                return null
            }
        }

        val report = enricher(fixture.dao, opener).enrich(
            maxGames = 1,
            batch = 1,
            reconcile = false,
        )

        assertEquals(1, report.attempted)
        assertEquals(0, report.matched)
        assertEquals(1, report.retryable)
        assertEquals(1, randomOpens)
        assertNull(fixture.write)
    }

    @Test
    fun `unreadable resident remains retryable and is never stamped as a no-match`() = runBlocking {
        val fixture = DaoFixture(game(platform = "gba", fileName = "resident.gba"))
        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): InputStream? = null
        }

        val report = enricher(fixture.dao, opener).enrich(
            maxGames = 1,
            batch = 1,
            reconcile = false,
        )

        assertEquals(1, report.attempted)
        assertEquals(0, report.matched)
        assertEquals(1, report.retryable)
        assertNull(fixture.write)
    }

    @Test
    fun `typed archive identity reaches the authoritative candidate write`() = runBlocking {
        val fixture = DaoFixture(game(platform = "n64", fileName = "bundle.zip"))
        val enricher = enricher(fixture.dao, ContentOpener { _, _ -> null }).apply {
            archivePayloadResolver = { _, _, _ ->
                ArchivePayloadSelectionResult.Identified(
                    ArchivePayloadIdentity(
                        fingerprint = RomFingerprint(
                            datHash = ContentHash(sha1 = "11".repeat(20)),
                            exactRawSha1 = null,
                        ),
                        canonicalTitle = "Oracle Bundle",
                        collapseKey = "Oracle Bundle",
                        origin = MetadataOrigin.NO_INTRO,
                        descentDepth = 1,
                    ),
                )
            }
        }

        val report = enricher.enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.matched)
        assertEquals(0, report.retryable)
        assertEquals("Oracle Bundle", fixture.write?.title)
        assertEquals("Oracle Bundle", fixture.write?.collapse)
        assertEquals("NO_INTRO", fixture.write?.origin)
    }

    @Test
    fun `exact nested bundle selector reaches the enricher identity write`() = runBlocking {
        var state = 0x6a09_e667_f3bc_c909L
        val rom = ByteArray(8 * 1024 * 1024) {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            state.toByte()
        }
        val nested = zipOf(ZipFixtureEntry("Super Mario 64 (ESP) [!].z64", rom))
        val archive = zipOf(
            ZipFixtureEntry("readme.txt", ByteArray(25), stored = true),
            ZipFixtureEntry("Super Mario 64 - 51230/", ByteArray(0), directory = true),
            ZipFixtureEntry("Super Mario 64 - 51230/files/", ByteArray(0), directory = true),
            ZipFixtureEntry("Super Mario 64 - 51230/files/33226/", ByteArray(0), directory = true),
            ZipFixtureEntry(
                "Super Mario 64 - 51230/files/33226/51230.zip",
                nested,
                stored = true,
            ),
            ZipFixtureEntry("Super Mario 64 - 51230/images/cover.png", ByteArray(84_760)),
        )
        val romSha1 = FileHasher.hash(ByteArrayInputStream(rom)).sha1
        val fixture = DaoFixture(game(platform = "n64", fileName = "owner-shape.zip"))
        val enricher = enricher(fixture.dao, ContentOpener { _, _ -> null }).apply {
            archivePayloadResolver = { _, budget, _ ->
                ArchivePayloadSelector(
                    platformId = "n64",
                    residentSizeBytes = archive.size.toLong(),
                    openResident = { ByteArrayInputStream(archive) },
                    oracle = { _, fingerprint ->
                        if (fingerprint.datHash.sha1 == romSha1) {
                            ArchivePayloadOracleMatch(
                                canonicalTitle = "Super Mario 64",
                                collapseKey = "Super Mario 64",
                                origin = MetadataOrigin.NO_INTRO,
                            )
                        } else {
                            null
                        }
                    },
                    nanoTime = { 0L },
                ).select(budget)
            }
        }

        val report = enricher.enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.matched)
        assertEquals(0, report.retryable)
        assertEquals("Super Mario 64", fixture.write?.title)
        assertEquals("Super Mario 64", fixture.write?.collapse)
        assertEquals("NO_INTRO", fixture.write?.origin)
    }

    @Test
    fun `archive CHD header claim never becomes authoritative content SHA1`() = runBlocking {
        val fixture = DaoFixture(game(platform = "n64", fileName = "header-claim.zip"))
        val enricher = enricher(fixture.dao, ContentOpener { _, _ -> null }).apply {
            archivePayloadResolver = { _, _, _ ->
                ArchivePayloadSelectionResult.Identified(
                    ArchivePayloadIdentity(
                        fingerprint = RomFingerprint(
                            datHash = ContentHash(sha1 = "12".repeat(20)),
                            exactRawSha1 = null,
                            proof = RomFingerprintProof.CHD_HEADER_CLAIM,
                        ),
                        canonicalTitle = "Unverified Header Claim",
                        collapseKey = "Unverified Header Claim",
                        origin = MetadataOrigin.REDUMP,
                        descentDepth = 0,
                    ),
                )
            }
        }

        val report = enricher.enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(0, report.matched)
        assertNull(fixture.write?.title)
    }

    @Test
    fun `MAME zip route never invokes the DEFAULT archive selector`() = runBlocking {
        val fixture = DaoFixture(game(platform = "arcade", fileName = "set.zip"))
        var selectorInvoked = false
        val enricher = enricher(fixture.dao, ContentOpener { _, _ -> null }).apply {
            archivePayloadResolver = { _, _, _ ->
                selectorInvoked = true
                error("DEFAULT archive selector must not own MAME ZIP identity")
            }
        }

        val report = enricher.enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.attempted)
        assertEquals(0, report.retryable)
        assertEquals(false, selectorInvoked)
    }

    @Test
    fun `unsupported MAME set shape stamps terminal while corrupt outer zip remains retryable`() = runBlocking {
        val unsupportedFixture = DaoFixture(game(platform = "arcade", fileName = "set.zip"))
        val unsupportedArchive = zipOf(
            ZipFixtureEntry("ROM/A.BIN", byteArrayOf(1)),
            ZipFixtureEntry("rom/a.bin", byteArrayOf(2)),
        )
        val unsupported = enricher(
            unsupportedFixture.dao,
            ContentOpener { _, _ -> ByteArrayInputStream(unsupportedArchive) },
            payloadProvider = realMameAssets(),
        ).enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(0, unsupported.retryable)
        assertEquals(41L, unsupportedFixture.write?.ts)
        assertNull(unsupportedFixture.write?.title)

        val corruptFixture = DaoFixture(game(platform = "arcade", fileName = "broken.zip"))
        val corruptArchive = corruptFirstEntryPayload(
            zipOf(
                ZipFixtureEntry(
                    name = "rom.bin",
                    bytes = ByteArray(256) { it.toByte() },
                    stored = true,
                ),
            ),
        )
        val corrupt = enricher(
            corruptFixture.dao,
            ContentOpener { _, _ -> ByteArrayInputStream(corruptArchive) },
            payloadProvider = realMameAssets(),
        ).enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, corrupt.retryable)
        assertNull(corruptFixture.write)
    }

    @Test
    fun `known oversized loose MAME member stamps terminal before opening bytes`() = runBlocking {
        val fixture = DaoFixture(
            game(platform = "arcade", fileName = "oversized.bin").copy(
                sizeBytes = MameZipIdentityReader.MAX_ENTRY_BYTES + 1L,
            ),
        )
        var opens = 0
        val report = enricher(
            fixture.dao,
            ContentOpener { _, _ ->
                opens++
                error("known unsupported size must refuse before resident I/O")
            },
        ).enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(0, report.retryable)
        assertEquals(0, opens)
        assertEquals(41L, fixture.write?.ts)
        assertNull(fixture.write?.title)
    }

    @Test
    fun `typed archive terminal refusal stamps while environmental refusal stays retryable`() = runBlocking {
        val terminalFixture = DaoFixture(game(platform = "n64", fileName = "terminal.zip"))
        val terminal = enricher(terminalFixture.dao, ContentOpener { _, _ -> null }).apply {
            archivePayloadResolver = { _, _, _ ->
                ArchivePayloadSelectionResult.TerminalAbstain(
                    ArchivePayloadRefusalReason.NO_CATALOG_MATCH,
                )
            }
        }.enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(0, terminal.retryable)
        assertEquals(41L, terminalFixture.write?.ts)
        assertNull(terminalFixture.write?.title)

        val retryFixture = DaoFixture(game(platform = "n64", fileName = "retry.zip"))
        val retryable = enricher(retryFixture.dao, ContentOpener { _, _ -> null }).apply {
            archivePayloadResolver = { _, _, _ ->
                ArchivePayloadSelectionResult.Retryable(
                    ArchivePayloadRefusalReason.IO_UNAVAILABLE,
                )
            }
        }.enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, retryable.retryable)
        assertNull(retryFixture.write)
    }

    @Test
    fun `CHD native resident is closed when libchdr capability is unavailable`() = runBlocking {
        val fixture = DaoFixture(game(platform = "ps1", fileName = "disc.chd"))
        var nativeClosed = false
        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): InputStream? =
                error("CHD authority must use random and native seek seams")

            override fun openRandomAccess(
                documentUri: String?,
                managedPath: String?,
            ): RandomAccessContent = ByteArrayRandomAccess(validChdHeader())

            override fun openNativeSeekablePath(
                documentUri: String?,
                managedPath: String?,
            ): NativeSeekablePath = object : NativeSeekablePath {
                override val path = "/not-a-host-chd"
                override fun close() {
                    nativeClosed = true
                }
            }
        }

        val report = enricher(fixture.dao, opener).enrich(
            maxGames = 1,
            batch = 1,
            reconcile = false,
        )

        assertEquals(1, report.retryable)
        assertEquals(true, nativeClosed)
        assertNull(fixture.write)
    }

    @Test
    fun `differential CHD chain identity feeds the candidate pipeline and stamps honestly`() = runBlocking {
        val fixture = differentialFixture()
        val verifiedChains = mutableListOf<List<ChdVerificationNode>>()
        val enricher = enricher(fixture.dao, fixture.opener).apply {
            chainVerifier = { nodes, _ ->
                verifiedChains += nodes
                ChdChainVerification(
                    result = ContainerIdentityResult.Identified(
                        VerifiedChdIdentity(
                            version = 5,
                            logicalBytes = 4096L,
                            rawSha1 = fixture.childRawHex,
                            combinedSha1 = fixture.childCombinedHex,
                            chainDepth = 2,
                            compressionTags = listOf(0, 0, 0, 0),
                        ),
                    ),
                )
            }
        }

        val report = enricher.enrich(maxGames = 1, batch = 1, reconcile = false)

        // The chain identified, the empty catalog missed: an honest attempted-no-match stamp, never a
        // silent terminal abstention before discovery.
        assertEquals(1, report.attempted)
        assertEquals(0, report.matched)
        assertEquals(0, report.retryable)
        assertEquals(41L, fixture.write?.ts)
        assertNull(fixture.write?.title)
        assertEquals(ChdParentChainDiscovery.MAX_RESIDENTS + 1, fixture.recordedInventoryLimit)
        assertEquals(1, verifiedChains.size)
        val chain = verifiedChains.single()
        assertEquals(
            listOf("native:/roms/renamed-parent.chd", "native:/roms/child.chd"),
            chain.map(ChdVerificationNode::path),
        )
        assertEquals(fixture.parentCombinedHex, chain[0].header.combinedSha1)
        assertEquals(null, chain[0].header.parentSha1)
        assertEquals(fixture.parentCombinedHex, chain[1].header.parentSha1)
        assertEquals(
            listOf(
                "open:/roms/renamed-parent.chd",
                "open:/roms/child.chd",
                "close:/roms/child.chd",
                "close:/roms/renamed-parent.chd",
            ),
            fixture.holderEvents,
        )
    }

    @Test
    fun `one enrichment sweep shares one CHD parent inventory across multiple candidates`() = runBlocking {
        val fixture = multiDifferentialFixture()
        var verifications = 0
        val enricher = enricher(fixture.dao, fixture.opener).apply {
            chainVerifier = { nodes, _ ->
                verifications++
                ChdChainVerification(
                    result = ContainerIdentityResult.Identified(
                        VerifiedChdIdentity(
                            version = 5,
                            logicalBytes = 4096L,
                            rawSha1 = fixture.childCombinedHex,
                            combinedSha1 = fixture.childCombinedHex,
                            chainDepth = 2,
                            compressionTags = listOf(0, 0, 0, 0),
                        ),
                    ),
                )
            }
        }

        val report = enricher.enrich(maxGames = 2, batch = 2, reconcile = false)

        assertEquals(2, report.attempted)
        assertEquals(2, verifications)
        assertEquals(1, fixture.inventoryCalls)
    }

    @Test
    fun `differential CHD stays retryable when native chain capability is unavailable`() = runBlocking {
        val fixture = differentialFixture()

        val report = enricher(fixture.dao, fixture.opener)
            .enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.attempted)
        assertEquals(1, report.retryable)
        assertNull(fixture.write)
        // The host JVM has no libchdr; discovery still enumerated the chain and released every holder.
        assertEquals(
            listOf(
                "open:/roms/renamed-parent.chd",
                "open:/roms/child.chd",
                "close:/roms/child.chd",
                "close:/roms/renamed-parent.chd",
            ),
            fixture.holderEvents,
        )
    }

    @Test
    fun `differential CHD without a digest candidate stays parent-required retryable`() = runBlocking {
        val fixture = differentialFixture(includeParentResident = false)
        var verifications = 0
        val enricher = enricher(fixture.dao, fixture.opener).apply {
            chainVerifier = { _, _ ->
                verifications++
                error("no digest candidate exists, so nothing may be verified")
            }
        }

        val report = enricher.enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.attempted)
        assertEquals(1, report.retryable)
        assertNull(fixture.write)
        assertEquals(0, verifications)
        assertEquals(emptyList<String>(), fixture.holderEvents)
    }

    private fun enricher(
        dao: GameDao,
        opener: ContentOpener,
        payloadProvider: DatAssetProvider? = null,
    ) = LibraryIdentityEnricher(
        chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
        dao = dao,
        datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
        idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
        opener = opener,
        nowMillis = { 41L },
        payloadProvider = payloadProvider,
    )

    private fun realMameAssets(): DatAssetProvider {
        val directory = listOf(File("../app/src/main/assets/dat"), File("app/src/main/assets/dat"))
            .firstOrNull(File::isDirectory)
            ?: error("bundled asset directory is not visible from the test working directory")
        return DatAssetProvider { name -> File(directory, name).takeIf(File::isFile)?.inputStream() }
    }

    /** Two differential CHD primaries that share one parent catalog in a single sweep. */
    private inner class MultiDifferentialFixture {
        private val parentRaw = ByteArray(20) { (it + 1).toByte() }
        private val parentCombined = ByteArray(20) { (it + 41).toByte() }
        private val childOneRaw = ByteArray(20) { (it + 81).toByte() }
        private val childOneCombined = ByteArray(20) { (it + 121).toByte() }
        private val childTwoRaw = ByteArray(20) { (it + 161).toByte() }
        private val childTwoCombined = ByteArray(20) { (it + 201).toByte() }
        val childCombinedHex = childOneCombined.toHex()

        private val childOne = game(platform = "ps1", fileName = "child-one.chd")
        private val childTwo = game(platform = "ps1", fileName = "child-two.chd")
            .copy(entryKey = "root::child-two.chd", gameId = "ps1-game-two")
        private val parent = game(platform = "ps1", fileName = "renamed-parent.chd")
            .copy(entryKey = "root::renamed-parent.chd", isPrimaryMember = false)
        private val residents = listOf(childOne, childTwo, parent)

        var inventoryCalls = 0
        val holderEvents = mutableListOf<String>()
        private var resolvedCount = 0

        val dao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" ->
                    if (resolvedCount >= 2) emptyList<GameEntity>() else listOf(childOne, childTwo)
                "availableChdShapedResidents" -> {
                    inventoryCalls++
                    residents.sortedBy(GameEntity::entryKey)
                }
                "setIdentities" -> {
                    resolvedCount += 1
                    Unit
                }
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao

        private val headersByPath = mapOf(
            "/roms/child-one.chd" to chdV5Header(childOneRaw, childOneCombined, parentCombined),
            "/roms/child-two.chd" to chdV5Header(childTwoRaw, childTwoCombined, parentCombined),
            "/roms/renamed-parent.chd" to chdV5Header(parentRaw, parentCombined, null),
        )

        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): InputStream? =
                error("CHD chain authority must use random and native seek seams")

            override fun openRandomAccess(
                documentUri: String?,
                managedPath: String?,
            ): RandomAccessContent = ByteArrayRandomAccess(headersByPath.getValue(requireNotNull(managedPath)))

            override fun openNativeSeekablePath(
                documentUri: String?,
                managedPath: String?,
            ): NativeSeekablePath {
                val path = requireNotNull(managedPath)
                holderEvents += "open:$path"
                return object : NativeSeekablePath {
                    override val path = "native:$path"
                    override fun close() {
                        holderEvents += "close:$path"
                    }
                }
            }
        }
    }

    /** A primary differential child plus (optionally) a renamed non-primary parent resident. */
    private inner class DifferentialFixture(includeParentResident: Boolean) {
        private val parentRaw = ByteArray(20) { (it + 1).toByte() }
        private val parentCombined = ByteArray(20) { (it + 41).toByte() }
        private val childRaw = ByteArray(20) { (it + 81).toByte() }
        private val childCombined = ByteArray(20) { (it + 121).toByte() }
        val parentCombinedHex = parentCombined.toHex()
        val childRawHex = childRaw.toHex()
        val childCombinedHex = childCombined.toHex()

        private val child = game(platform = "ps1", fileName = "child.chd")
        private val parent = game(platform = "ps1", fileName = "renamed-parent.chd")
            .copy(entryKey = "root::renamed-parent.chd", isPrimaryMember = false)
        private val residents = if (includeParentResident) listOf(child, parent) else listOf(child)

        var write: IdentityWrite? = null
        var recordedInventoryLimit: Int? = null
        val holderEvents = mutableListOf<String>()
        private var resolved = false

        val dao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" ->
                    if (resolved) emptyList<GameEntity>() else listOf(child)
                "availableChdShapedResidents" -> {
                    recordedInventoryLimit = requireNotNull(args)[0] as Int
                    residents.sortedBy(GameEntity::entryKey)
                }
                "setIdentities" -> {
                    @Suppress("UNCHECKED_CAST")
                    write = (requireNotNull(args)[0] as List<IdentityWrite>).single()
                    resolved = true
                    Unit
                }
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao

        private val headersByPath = mapOf(
            "/roms/child.chd" to chdV5Header(childRaw, childCombined, parentCombined),
            "/roms/renamed-parent.chd" to chdV5Header(parentRaw, parentCombined, null),
        )

        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): InputStream? =
                error("CHD chain authority must use random and native seek seams")

            override fun openRandomAccess(
                documentUri: String?,
                managedPath: String?,
            ): RandomAccessContent = ByteArrayRandomAccess(headersByPath.getValue(requireNotNull(managedPath)))

            override fun openNativeSeekablePath(
                documentUri: String?,
                managedPath: String?,
            ): NativeSeekablePath {
                val path = requireNotNull(managedPath)
                holderEvents += "open:$path"
                return object : NativeSeekablePath {
                    override val path = "native:$path"
                    override fun close() {
                        holderEvents += "close:$path"
                    }
                }
            }
        }
    }

    private fun multiDifferentialFixture() = MultiDifferentialFixture()

    private fun differentialFixture(includeParentResident: Boolean = true) =
        DifferentialFixture(includeParentResident)

    private fun chdV5Header(
        raw: ByteArray,
        combined: ByteArray,
        parent: ByteArray?,
    ): ByteArray = ByteArray(132).also { bytes ->
        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes.putBe32(8, 124)
        bytes.putBe32(12, 5)
        bytes.putBe64(32, 4096L)
        bytes.putBe32(56, 4096)
        bytes.putBe32(60, 512)
        raw.copyInto(bytes, 64)
        combined.copyInto(bytes, 84)
        parent?.copyInto(bytes, 104)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") {
        "%02x".format(it.toInt() and 0xff)
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
                "membersOfGameBySize" -> listOf(game)
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

    private fun game(platform: String, fileName: String) = GameEntity(
        entryKey = "root::$fileName",
        gameId = "$platform-game",
        platformId = platform,
        contentKind = "GAME",
        displayName = fileName,
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
        managedPath = "/roms/$fileName",
        relativePath = fileName,
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    private fun validChdHeader(): ByteArray = ByteArray(132).also { bytes ->
        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes.putBe32(8, 124)
        bytes.putBe32(12, 5)
        bytes.putBe64(32, 4096L)
        bytes.putBe32(56, 4096)
        bytes.putBe32(60, 512)
        repeat(20) { index ->
            bytes[64 + index] = (index + 1).toByte()
            bytes[84 + index] = (index + 21).toByte()
        }
    }

    private fun ByteArray.putBe32(at: Int, value: Int) {
        repeat(4) { index -> this[at + index] = (value ushr ((3 - index) * 8)).toByte() }
    }

    private fun ByteArray.putBe64(at: Int, value: Long) {
        repeat(8) { index -> this[at + index] = (value ushr ((7 - index) * 8)).toByte() }
    }

    private data class ZipFixtureEntry(
        val name: String,
        val bytes: ByteArray,
        val stored: Boolean = false,
        val directory: Boolean = false,
    )

    private fun zipOf(vararg entries: ZipFixtureEntry): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { item ->
                val name = if (item.directory && !item.name.endsWith('/')) "${item.name}/" else item.name
                val entry = ZipEntry(name)
                if (item.stored || item.directory) {
                    val crc = CRC32().apply { update(item.bytes) }
                    entry.method = ZipEntry.STORED
                    entry.size = item.bytes.size.toLong()
                    entry.compressedSize = item.bytes.size.toLong()
                    entry.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(item.bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun corruptFirstEntryPayload(archive: ByteArray): ByteArray = archive.copyOf().also { bytes ->
        val nameLength = (bytes[26].toInt() and 0xff) or ((bytes[27].toInt() and 0xff) shl 8)
        val extraLength = (bytes[28].toInt() and 0xff) or ((bytes[29].toInt() and 0xff) shl 8)
        val payloadOffset = 30 + nameLength + extraLength
        bytes[payloadOffset] = (bytes[payloadOffset].toInt() xor 0x5a).toByte()
    }

    private class ByteArrayRandomAccess(
        private val bytes: ByteArray,
    ) : RandomAccessContent {
        override val length = bytes.size.toLong()

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            if (offset !in 0 until length) return 0
            val count = minOf(byteCount, bytes.size - offset.toInt())
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
            return count
        }

        override fun close() = Unit
    }
}
