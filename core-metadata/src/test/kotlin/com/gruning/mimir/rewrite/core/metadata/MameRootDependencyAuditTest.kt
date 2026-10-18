package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MameRootDependencyAuditTest {

    @Test
    fun `exact selected ZIP supplies machine authority while unrelated atoms stay not proven`() = runBlocking {
        val targetPayload = "synthetic target member".toByteArray()
        val splitPayload = "synthetic split dependency".toByteArray()
        val targetZip = zipOf("target.rom" to targetPayload)
        val splitZip = zipOf("split.rom" to splitPayload, "empty.prom" to ByteArray(0))
        val targetSignature = signature(targetPayload)
        val target = game("target", "root-a", "pacman.zip")
        val split = game("split", "root-a", "bios.zip")
        val openCounts = mutableMapOf<String, Int>()
        val builder = builder(
            bytes = mapOf("target" to targetZip, "split" to splitZip),
            exactSignature = targetSignature,
            exactExpandedBytes = targetPayload.size.toLong(),
            machineName = "pacman",
            onOpen = { key -> openCounts[key] = openCounts.getOrDefault(key, 0) + 1 },
        )

        val result = builder.buildAndEvaluate(
            snapshot = MameAuditSnapshot(target, listOf(split, target)),
            selectedBios = emptyMap(),
            deadlineNanos = Long.MAX_VALUE,
        )

        result as MameDependencyAuditResult.NotProven
        assertTrue(result.coverage.requiredRomAtoms > 0)
        assertTrue(result.coverage.missingRomAtoms > 0)
        assertEquals(0, result.coverage.missingDiskAtoms)
        assertEquals(mapOf("target" to 1, "split" to 1), openCounts)
    }

    @Test
    fun `loose selected resident can contribute atoms but cannot select a machine`() = runBlocking {
        val target = game("loose", "root-a", "member.bin")

        val result = builder(
            bytes = mapOf("loose" to "member".toByteArray()),
            exactSignature = "0".repeat(64),
            exactExpandedBytes = 1L,
            machineName = "pacman",
        ).buildAndEvaluate(
            snapshot = MameAuditSnapshot(target, listOf(target)),
            selectedBios = emptyMap(),
            deadlineNanos = Long.MAX_VALUE,
        )

        assertEquals(
            MameDependencyAuditResult.NeedsReview(MameAuditReason.MACHINE_UNRESOLVED),
            result,
        )
    }

    @Test
    fun `mixed root snapshot is refused before any cross-root atom can contribute`() = runBlocking {
        val targetPayload = "target".toByteArray()
        val target = game("target", "root-a", "pacman.zip")
        val foreign = game("foreign", "root-b", "neogeo.zip")

        val result = builder(
            bytes = mapOf(
                "target" to zipOf("target.rom" to targetPayload),
                "foreign" to zipOf("foreign.rom" to "foreign".toByteArray()),
            ),
            exactSignature = signature(targetPayload),
            exactExpandedBytes = targetPayload.size.toLong(),
            machineName = "pacman",
        ).buildAndEvaluate(
            snapshot = MameAuditSnapshot(target, listOf(target, foreign)),
            selectedBios = emptyMap(),
            deadlineNanos = Long.MAX_VALUE,
        )

        assertEquals(
            MameDependencyAuditResult.Blocked(MameAuditReason.ROOT_MISMATCH),
            result,
        )
    }

    @Test
    fun `unknown selected machine is typed before unrelated malformed inventory`() = runBlocking {
        val targetPayload = "synthetic target".toByteArray()
        val target = game("target", "root-a", "unknown.zip")
        val malformed = game("malformed", "root-a", "broken.zip")
        val builder = builder(
            bytes = mapOf(
                "target" to zipOf("target.rom" to targetPayload),
                "malformed" to ByteArray(0),
            ),
            exactSignature = signature(targetPayload),
            exactExpandedBytes = targetPayload.size.toLong(),
            machineName = "not_a_real_machine",
        )

        val built = builder.build(
            snapshot = MameAuditSnapshot(target, listOf(target, malformed)),
            deadlineNanos = Long.MAX_VALUE,
            preflight = { machineName ->
                builder.preflight(machineName, target.sourceRootUri, emptyMap())
            },
        )

        assertEquals(
            MameDependencyAuditResult.NeedsReview(MameAuditReason.MACHINE_UNRESOLVED),
            (built as MameInventoryBuildResult.Terminal).result,
        )
    }

    @Test
    fun `expired aggregate deadline refuses work before opening a resident`() = runBlocking {
        val target = game("target", "root-a", "pacman.zip")
        val result = builder(
            bytes = mapOf("target" to zipOf("target.rom" to "target".toByteArray())),
            exactSignature = "0".repeat(64),
            exactExpandedBytes = 1L,
            machineName = "pacman",
        ).buildAndEvaluate(
            snapshot = MameAuditSnapshot(target, listOf(target)),
            selectedBios = emptyMap(),
            deadlineNanos = 1L,
        )

        assertEquals(
            MameDependencyAuditResult.Blocked(MameAuditReason.RESOURCE_REFUSED),
            result,
        )
    }

    @Test
    fun `disk evidence merge preserves strongest observed state`() {
        assertEquals(
            MameObservedDiskState.VERIFIED,
            mergeMameDiskState(
                MameObservedDiskState.UNVERIFIED,
                MameObservedDiskState.VERIFIED,
            ),
        )
        assertEquals(
            MameObservedDiskState.VERIFIED,
            mergeMameDiskState(
                MameObservedDiskState.VERIFIED,
                MameObservedDiskState.PARENT_REQUIRED,
            ),
        )
        assertEquals(
            MameObservedDiskState.PARENT_REQUIRED,
            mergeMameDiskState(
                MameObservedDiskState.UNVERIFIED,
                MameObservedDiskState.PARENT_REQUIRED,
            ),
        )
    }

    @Test
    fun `stale source generation prevents evaluator invocation`() = runBlocking {
        val sourceMutations = SourceRootMutationCoordinator("ep18-stale-fixture")
        val selected = sourceMutations.snapshotSourceMutation { Unit }
        sourceMutations.commitSourceMutation { Unit }
        var evaluatorCalls = 0

        val result = evaluateMameInventoryIfCurrent(
            sourceMutations = sourceMutations,
            expectedGeneration = selected.generation,
        ) {
            evaluatorCalls++
            MameDependencyAuditResult.Proven(
                MameAuditCoverage(0, 0, 0, 0, 0, 0, 0, 0),
            )
        }

        assertEquals(MameDependencyAuditResult.Stale, result)
        assertEquals(0, evaluatorCalls)
    }

    @Test
    fun `verified CHD target supplies machine authority and closes native holder`() = runBlocking {
        val raw = ByteArray(20) { (it + 1).toByte() }
        val combined = ByteArray(20) { (it + 41).toByte() }
        val rawHex = raw.toHex()
        val combinedHex = combined.toHex()
        val target = game("target-chd", "root-a", "pacman.chd")
        val header = chdV5Header(raw = raw, combined = combined)
        var holderClosed = false
        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?) =
                error("CHD audit must use seekable seams")

            override fun openRandomAccess(
                documentUri: String?,
                managedPath: String?,
            ): RandomAccessContent = ByteArrayRandomAccess(header)

            override fun openNativeSeekablePath(
                documentUri: String?,
                managedPath: String?,
            ): NativeSeekablePath = object : NativeSeekablePath {
                override val path = "native:pacman.chd"
                override fun close() {
                    holderClosed = true
                }
            }
        }
        val builder = MameRootInventoryBuilder(
            opener = opener,
            setLookup = object : MameSetLookup {
                override val isAvailable = true
                override fun set(signature: String): MameSetRecord? = null
            },
            memberLookup = object : MameMemberLookup {
                override val memberIndexAvailable = true
                override fun containsMember(sha1: ByteArray, sizeBytes: Long) = false
            },
            diskLookup = object : MameDiskLookup {
                override fun classifyDisk(
                    combinedSha1: String,
                ): ContainerIdentityResult<MameDiskRecord> =
                    if (combinedSha1 == combinedHex) {
                        ContainerIdentityResult.Identified(
                            MameDiskRecord(
                                machineName = "pacman",
                                cloneRoot = "pacman",
                                requiredRomCount = 1,
                                requiredDiskCount = 1,
                                canonicalTitle = "Pac-Man",
                            ),
                        )
                    } else {
                        ContainerIdentityResult.PolicyAbstain(
                            ContainerIdentityReason.NO_CATALOG_MATCH,
                        )
                    }
            },
            dependencyCatalog = dependencyCatalog(),
            nanoTime = { 1L },
            standaloneChdVerifier = { path, evidence, _ ->
                assertEquals("native:pacman.chd", path)
                assertEquals(combinedHex, evidence.combinedSha1)
                ContainerIdentityResult.Identified(
                    VerifiedChdIdentity(
                        version = evidence.version,
                        logicalBytes = evidence.logicalBytes,
                        rawSha1 = rawHex,
                        combinedSha1 = combinedHex,
                    ),
                )
            },
        )

        val built = builder.build(
            snapshot = MameAuditSnapshot(target, listOf(target)),
            deadlineNanos = Long.MAX_VALUE,
        )

        val ready = built as MameInventoryBuildResult.Ready
        assertEquals("pacman", ready.observation.machineName)
        assertEquals(
            MameObservedDiskState.VERIFIED,
            ready.observation.inventory.diskAtoms[MameDiskAtom(combinedHex)],
        )
        assertTrue(holderClosed)
    }

    private suspend fun MameRootInventoryBuilder.buildAndEvaluate(
        snapshot: MameAuditSnapshot,
        selectedBios: Map<String, String>,
        deadlineNanos: Long,
    ): MameDependencyAuditResult = when (
        val built = build(
            snapshot = snapshot,
            deadlineNanos = deadlineNanos,
        )
    ) {
        is MameInventoryBuildResult.Ready -> evaluate(built.observation, selectedBios)
        is MameInventoryBuildResult.Terminal -> built.result
    }

    private fun builder(
        bytes: Map<String, ByteArray>,
        exactSignature: String,
        exactExpandedBytes: Long,
        machineName: String,
        onOpen: (String) -> Unit = {},
    ): MameRootInventoryBuilder {
        val record = MameSetRecord(
            memberCount = 1,
            totalBytes = exactExpandedBytes,
            machineName = machineName,
            cloneRoot = machineName,
            canonicalTitle = machineName,
        )
        val setLookup = object : MameSetLookup {
            override val isAvailable = true
            override fun set(signature: String): MameSetRecord? =
                record.takeIf { signature == exactSignature }
        }
        val memberLookup = object : MameMemberLookup {
            override val memberIndexAvailable = true
            override fun containsMember(sha1: ByteArray, sizeBytes: Long) = false
        }
        val diskLookup = object : MameDiskLookup {
            override fun classifyDisk(
                combinedSha1: String,
            ): ContainerIdentityResult<MameDiskRecord> =
                ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.NO_CATALOG_MATCH)
        }
        return MameRootInventoryBuilder(
            opener = ContentOpener { _, managedPath ->
                managedPath?.also(onOpen)?.let(bytes::get)?.let(::ByteArrayInputStream)
            },
            setLookup = setLookup,
            memberLookup = memberLookup,
            diskLookup = diskLookup,
            dependencyCatalog = dependencyCatalog(),
            nanoTime = { 1L },
        )
    }

    private fun game(
        key: String,
        root: String,
        relativePath: String,
    ) = GameEntity(
        entryKey = key,
        gameId = key,
        platformId = "arcade",
        contentKind = "GAME_FILE",
        displayName = relativePath.substringAfterLast('/'),
        title = relativePath.substringBeforeLast('.'),
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "TEST",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = 1L,
        contentHash = null,
        sourceRootUri = root,
        sourceDocumentUri = null,
        managedPath = key,
        relativePath = relativePath,
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    private fun dependencyCatalog() = MameDependencyCatalog(
        DatAssetProvider { name ->
            dependencyAsset().takeIf { name == MameDependencyManifest.ASSET_NAME }?.inputStream()
        },
    )

    private fun dependencyAsset(): File =
        listOf(File("../app/src/main/assets/dat"), File("app/src/main/assets/dat"))
            .firstOrNull(File::isDirectory)
            ?.resolve(MameDependencyManifest.ASSET_NAME)
            ?.takeIf(File::isFile)
            ?: error("bundled dependency asset is not visible from the test working directory")

    private fun chdV5Header(
        raw: ByteArray,
        combined: ByteArray,
    ): ByteArray = ByteArray(132).also { bytes ->
        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes.putBe32(8, 124)
        bytes.putBe32(12, 5)
        bytes.putBe64(32, 4096L)
        bytes.putBe32(56, 4096)
        bytes.putBe32(60, 512)
        raw.copyInto(bytes, 64)
        combined.copyInto(bytes, 84)
    }

    private fun ByteArray.putBe32(at: Int, value: Int) {
        repeat(4) { index ->
            this[at + index] = (value ushr ((3 - index) * 8)).toByte()
        }
    }

    private fun ByteArray.putBe64(at: Int, value: Long) {
        repeat(8) { index ->
            this[at + index] = (value ushr ((7 - index) * 8)).toByte()
        }
    }

    private class ByteArrayRandomAccess(
        private val bytes: ByteArray,
    ) : RandomAccessContent {
        override val length: Long = bytes.size.toLong()

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            if (offset >= bytes.size) return 0
            val count = minOf(byteCount, bytes.size - offset.toInt())
            bytes.copyInto(
                destination = destination,
                destinationOffset = destinationOffset,
                startIndex = offset.toInt(),
                endIndex = offset.toInt() + count,
            )
            return count
        }

        override fun close() = Unit
    }

    private fun signature(vararg payloads: ByteArray): String {
        val canonical = payloads
            .map { sha1(it) to it.size.toLong() }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString(separator = "") { "${it.first}:${it.second}\n" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.US_ASCII))
            .toHex()
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).toHex()

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
