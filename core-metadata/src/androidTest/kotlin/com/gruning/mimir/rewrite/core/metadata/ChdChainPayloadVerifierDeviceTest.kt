package com.gruning.mimir.rewrite.core.metadata

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsChdHashEngine
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsChdHashResult
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsChdResident
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsM3uChdResolution
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsM3uChdTargetResolver
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsM3uResident
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsNativeHash
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChdChainPayloadVerifierDeviceTest {

    @Test
    fun versionThreeDifferentialChildReadsInheritedHunkThroughVerifiedParent() = runBlocking {
        val fixture = syntheticDifferentialV3Pair()
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File(cache, "athena-chain-root.chd")
        val child = File(cache, "athena-chain-child.chd")
        try {
            root.writeBytes(fixture.root)
            child.writeBytes(fixture.child)
            val rootHeader = requireNotNull(
                RomFingerprinter.chdHeaderEvidence(fixture.root, CHD_HEADER_READ_BYTES),
            )
            val childHeader = requireNotNull(
                RomFingerprinter.chdHeaderEvidence(fixture.child, CHD_HEADER_READ_BYTES),
            )

            val result = ChdChainPayloadVerifier.verify(
                listOf(
                    ChdVerificationNode(root.absolutePath, rootHeader),
                    ChdVerificationNode(child.absolutePath, childHeader),
                ),
            )

            assertTrue(
                "libchdr must resolve the inherited hunk and independently verify both CHDs",
                result is ContainerIdentityResult.Identified,
            )
            val identity = (result as ContainerIdentityResult.Identified).value
            assertEquals(2, identity.chainDepth)
            assertEquals(fixture.childSha1, identity.rawSha1)
            assertEquals(fixture.childSha1, identity.combinedSha1)
        } finally {
            child.delete()
            root.delete()
        }
    }

    @Test
    fun threeLevelVersionThreeChainRecomputesEveryNode() = runBlocking {
        val hunkBytes = 4_096
        val rootFirst = ByteArray(hunkBytes) { ((it * 13 + 5) and 0xff).toByte() }
        val rootSecond = ByteArray(hunkBytes) { ((it * 19 + 9) and 0xff).toByte() }
        val middleSecond = ByteArray(hunkBytes) { ((it * 29 + 15) and 0xff).toByte() }
        val targetSecond = ByteArray(hunkBytes) { ((it * 37 + 21) and 0xff).toByte() }
        val rootSha1 = (rootFirst + rootSecond).sha1()
        val middleSha1 = (rootFirst + middleSecond).sha1()
        val targetSha1 = (rootFirst + targetSecond).sha1()
        val root = v3Chd(
            hunkBytes = hunkBytes,
            logicalSha1 = rootSha1,
            parentSha1 = null,
            entries = listOf(
                V3MapEntry.Uncompressed(rootFirst),
                V3MapEntry.Uncompressed(rootSecond),
            ),
        )
        val middle = v3Chd(
            hunkBytes = hunkBytes,
            logicalSha1 = middleSha1,
            parentSha1 = rootSha1,
            entries = listOf(
                V3MapEntry.ParentHunk(index = 0),
                V3MapEntry.Uncompressed(middleSecond),
            ),
        )
        val target = v3Chd(
            hunkBytes = hunkBytes,
            logicalSha1 = targetSha1,
            parentSha1 = middleSha1,
            entries = listOf(
                V3MapEntry.ParentHunk(index = 0),
                V3MapEntry.Uncompressed(targetSecond),
            ),
        )

        val identity = verifyChainFixture(
            "athena-v3-root.chd" to root,
            "athena-v3-middle.chd" to middle,
            "athena-v3-target.chd" to target,
        )

        assertEquals(3, identity.chainDepth)
        assertEquals(targetSha1.toHex(), identity.rawSha1)
        assertEquals(targetSha1.toHex(), identity.combinedSha1)
    }

    @Test
    fun versionFourDifferentialChainRecomputesRawAndChecksummedMetadata() = runBlocking {
        val hunkBytes = 4_096
        val rootFirst = ByteArray(hunkBytes) { ((it * 11 + 3) and 0xff).toByte() }
        val rootSecond = ByteArray(hunkBytes) { ((it * 17 + 27) and 0xff).toByte() }
        val childSecond = ByteArray(hunkBytes) { ((it * 23 + 33) and 0xff).toByte() }
        // Chain order is deliberately tag-DESCENDING with a non-checksummed tail: a verifier that
        // forgot to sort records (or to skip unchecksummed entries) cannot reproduce the header overall.
        val rootMetadata = listOf(
            ChdMetadataFixture(
                tag = METADATA_TAG_DESCENDING,
                bytes = "root descending first record".toByteArray(Charsets.US_ASCII),
                checksummed = true,
            ),
            ChdMetadataFixture(
                tag = METADATA_TAG_ASCENDING,
                bytes = "root sorted-first second record".toByteArray(Charsets.US_ASCII),
                checksummed = true,
            ),
            ChdMetadataFixture(
                tag = METADATA_TAG_UNCHECKSUMMED,
                bytes = "root unchecksummed entry".toByteArray(Charsets.US_ASCII),
                checksummed = false,
            ),
        )
        val childMetadata = listOf(
            ChdMetadataFixture(
                tag = METADATA_TAG_DESCENDING,
                bytes = "child descending first record".toByteArray(Charsets.US_ASCII),
                checksummed = true,
            ),
            ChdMetadataFixture(
                tag = METADATA_TAG_ASCENDING,
                bytes = "child sorted-first second record".toByteArray(Charsets.US_ASCII),
                checksummed = true,
            ),
        )
        val rootRaw = (rootFirst + rootSecond).sha1()
        val childRaw = (rootFirst + childSecond).sha1()
        val rootOverall = overallSha1(rootRaw, rootMetadata)
        val childOverall = overallSha1(childRaw, childMetadata)
        val root = v4Chd(
            hunkBytes = hunkBytes,
            rawSha1 = rootRaw,
            overallSha1 = rootOverall,
            parentOverallSha1 = null,
            metadata = rootMetadata,
            entries = listOf(
                V3MapEntry.Uncompressed(rootFirst),
                V3MapEntry.Uncompressed(rootSecond),
            ),
        )
        val child = v4Chd(
            hunkBytes = hunkBytes,
            rawSha1 = childRaw,
            overallSha1 = childOverall,
            parentOverallSha1 = rootOverall,
            metadata = childMetadata,
            entries = listOf(
                V3MapEntry.ParentHunk(index = 0),
                V3MapEntry.Uncompressed(childSecond),
            ),
        )

        val identity = verifyChainFixture(
            "athena-v4-root.chd" to root,
            "athena-v4-child.chd" to child,
        )

        assertEquals(2, identity.chainDepth)
        assertEquals(4, identity.version)
        assertEquals(childRaw.toHex(), identity.rawSha1)
        assertEquals(childOverall.toHex(), identity.combinedSha1)
    }

    @Test
    fun versionFiveDifferentialUncompressedChainRecomputesRawAndChecksummedMetadata() = runBlocking {
        val hunkBytes = 4_096
        val rootFirst = ByteArray(hunkBytes) { ((it * 41 + 7) and 0xff).toByte() }
        val rootSecond = ByteArray(hunkBytes) { ((it * 43 + 13) and 0xff).toByte() }
        val childSecond = ByteArray(hunkBytes) { ((it * 47 + 19) and 0xff).toByte() }
        val rootMetadata = listOf(
            ChdMetadataFixture(
                tag = METADATA_TAG,
                bytes = "v5 root checksummed metadata".toByteArray(Charsets.US_ASCII),
                checksummed = true,
            ),
        )
        val childMetadata = listOf(
            ChdMetadataFixture(
                tag = METADATA_TAG,
                bytes = "v5 child checksummed metadata".toByteArray(Charsets.US_ASCII),
                checksummed = true,
            ),
        )
        val rootRaw = (rootFirst + rootSecond).sha1()
        val childRaw = (rootFirst + childSecond).sha1()
        val rootOverall = overallSha1(rootRaw, rootMetadata)
        val childOverall = overallSha1(childRaw, childMetadata)
        val root = v5Chd(
            hunkBytes = hunkBytes,
            rawSha1 = rootRaw,
            overallSha1 = rootOverall,
            parentOverallSha1 = null,
            metadata = rootMetadata,
            hunks = listOf(V5Hunk.Stored(rootFirst), V5Hunk.Stored(rootSecond)),
        )
        val child = v5Chd(
            hunkBytes = hunkBytes,
            rawSha1 = childRaw,
            overallSha1 = childOverall,
            parentOverallSha1 = rootOverall,
            metadata = childMetadata,
            hunks = listOf(V5Hunk.Parent, V5Hunk.Stored(childSecond)),
        )

        val identity = verifyChainFixture(
            "athena-v5-root.chd" to root,
            "athena-v5-child.chd" to child,
        )

        assertEquals(2, identity.chainDepth)
        assertEquals(5, identity.version)
        assertEquals(childRaw.toHex(), identity.rawSha1)
        assertEquals(childOverall.toHex(), identity.combinedSha1)
    }

    @Test
    fun twoNodeDifferentialCdHashesThroughTheVerifiedBorrowAndClosesCleanly() = runBlocking {
        val fixture = differentialCdChain(depth = 2)
        val files = writeFixtureFiles("ra-two", fixture.images)
        try {
            assertEquals(
                "the original parentless route must continue to refuse the differential child",
                null,
                RetroAchievementsNativeHash.hash(9, files.last().absolutePath),
            )
            repeat(2) {
                assertEquals(
                    ContainerIdentityResult.Identified(fixture.expectedMd5),
                    hashVerifiedSegaCdChain(files, fixture.images),
                )
            }
        } finally {
            files.reversed().forEach(File::delete)
        }
    }

    @Test
    fun threeNodeDifferentialCdHashesThroughTheVerifiedBorrow() = runBlocking {
        val fixture = differentialCdChain(depth = 3)
        val files = writeFixtureFiles("ra-three", fixture.images)
        try {
            assertEquals(
                ContainerIdentityResult.Identified(fixture.expectedMd5),
                hashVerifiedSegaCdChain(files, fixture.images),
            )
        } finally {
            files.reversed().forEach(File::delete)
        }
    }

    @Test
    fun playlistRetargetCannotConsumeAnUnrelatedBorrowedChain() = runBlocking {
        val borrowedFixture = differentialCdChain(depth = 2, payloadSeed = 7)
        val standaloneFixture = differentialCdChain(depth = 2, payloadSeed = 43)
        val borrowedFiles = writeFixtureFiles("ra-borrowed", borrowedFixture.images)
        val standalone = writeFixtureFiles("ra-standalone", listOf(standaloneFixture.images.first())).single()
        val playlist = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "ra-mismatched-borrow.m3u",
        )
        try {
            playlist.writeText(standalone.absolutePath + "\n")
            val nodes = borrowedFiles.mapIndexed { index, file ->
                ChdVerificationNode(
                    path = file.absolutePath,
                    header = requireNotNull(
                        RomFingerprinter.chdHeaderEvidence(
                            borrowedFixture.images[index],
                            minOf(borrowedFixture.images[index].size, CHD_HEADER_READ_BYTES),
                        ),
                    ),
                )
            }
            val result = ChdChainPayloadVerifier.withVerifiedNativeChain(nodes) { borrowed ->
                RetroAchievementsNativeHash.hashChain(
                    consoleId = 9,
                    targetPath = playlist.absolutePath,
                    nativeChainHandle = borrowed.nativeHandle,
                )
            }

            assertEquals(
                "the m3u-resolved standalone path must use its own bytes, never the unrelated borrowed top",
                ContainerIdentityResult.Identified(standaloneFixture.expectedMd5),
                result,
            )
            assertTrue(standaloneFixture.expectedMd5 != borrowedFixture.expectedMd5)
        } finally {
            playlist.delete()
            standalone.delete()
            borrowedFiles.reversed().forEach(File::delete)
        }
    }

    @Test
    fun focusScopedRaEngineDiscoversReverifiesAndHashesTheDifferentialTarget() = runBlocking {
        val fixture = differentialCdChain(depth = 3)
        val files = writeFixtureFiles("ra-engine", fixture.images)
        val residents = files.mapIndexed { index, file ->
            RetroAchievementsChdResident(
                entryKey = "v11:ra-engine-$index",
                sourceRootUri = "file://ra-engine",
                relativePath = file.name,
                documentUri = null,
                managedPath = file.absolutePath,
            )
        }
        try {
            val engine = RetroAchievementsChdHashEngine(
                residentInventory = { residents },
                opener = ContentOpener { _, managedPath ->
                    managedPath?.let { File(it).inputStream() }
                },
            )

            assertEquals(
                RetroAchievementsChdHashResult.Hashed(fixture.expectedMd5),
                engine.hash(consoleId = 9, target = residents.last()),
            )
        } finally {
            files.reversed().forEach(File::delete)
        }
    }

    @Test
    fun freshM3uTargetMatchesDirectTwoAndThreeNodeDifferentialHashes() = runBlocking {
        listOf(2, 3).forEach { depth ->
            val fixture = differentialCdChain(depth = depth, payloadSeed = 17 + depth)
            val files = writeFixtureFiles("ra-m3u-$depth", fixture.images)
            val playlist = File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "ra-m3u-$depth.m3u",
            )
            val residents = files.mapIndexed { index, file ->
                RetroAchievementsChdResident(
                    entryKey = "v11:ra-m3u-$depth-$index",
                    sourceRootUri = "file://ra-m3u-$depth",
                    relativePath = file.name,
                    documentUri = null,
                    managedPath = file.absolutePath,
                    sizeBytes = file.length(),
                )
            }
            val opener = ContentOpener { _, managedPath ->
                managedPath?.let { File(it).inputStream() }
            }
            try {
                playlist.writeText(files.last().name + "\n")
                val resolution = RetroAchievementsM3uChdTargetResolver(opener).resolve(
                    playlist = RetroAchievementsM3uResident(
                        entryKey = "v11:ra-m3u-$depth-playlist",
                        sourceRootUri = "file://ra-m3u-$depth",
                        relativePath = playlist.name,
                        documentUri = null,
                        managedPath = playlist.absolutePath,
                        sizeBytes = playlist.length(),
                    ),
                    availableGroupChds = residents,
                )
                assertTrue(
                    "fresh M3U must select exactly its first current CHD resident, got $resolution",
                    resolution is RetroAchievementsM3uChdResolution.Selected,
                )
                val selected = (resolution as RetroAchievementsM3uChdResolution.Selected).target
                assertEquals(residents.last(), selected)

                val engine = RetroAchievementsChdHashEngine(
                    residentInventory = { residents },
                    opener = opener,
                )
                val direct = engine.hash(consoleId = 9, target = residents.last())
                val throughPlaylist = engine.hash(consoleId = 9, target = selected)

                assertEquals(RetroAchievementsChdHashResult.Hashed(fixture.expectedMd5), direct)
                assertEquals(direct, throughPlaylist)
            } finally {
                playlist.delete()
                files.reversed().forEach(File::delete)
            }
        }
    }

    /** Writes the fixtures root-to-target, verifies the chain, and always deletes the files. */
    private suspend fun verifyChainFixture(
        vararg fixtures: Pair<String, ByteArray>,
    ): VerifiedChdIdentity {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val files = fixtures.map { File(cache, it.first) }
        try {
            files.forEachIndexed { index, file -> file.writeBytes(fixtures[index].second) }
            val nodes = files.mapIndexed { index, file ->
                ChdVerificationNode(
                    path = file.absolutePath,
                    header = requireNotNull(
                        RomFingerprinter.chdHeaderEvidence(
                            fixtures[index].second,
                            minOf(fixtures[index].second.size, CHD_HEADER_READ_BYTES),
                        ),
                    ) { "fixture ${file.name} must parse as a CHD header claim" },
                )
            }
            val result = ChdChainPayloadVerifier.verify(nodes)
            assertTrue(
                "chain must fully verify, got $result",
                result is ContainerIdentityResult.Identified,
            )
            return (result as ContainerIdentityResult.Identified).value
        } finally {
            files.reversed().forEach(File::delete)
        }
    }

    /** sha1(raw digest || ascending-sorted (tag || sha1(data)) records); unchecksummed entries excluded. */
    private fun overallSha1(rawSha1: ByteArray, metadata: List<ChdMetadataFixture>): ByteArray {
        val records = metadata.filter(ChdMetadataFixture::checksummed).map { entry ->
            ByteArray(4 + 20).also { record ->
                record.putBigEndian32(0, entry.tag)
                entry.bytes.sha1().copyInto(record, 4)
            }
        }.sortedWith { left, right ->
            var compared = 0
            for (index in left.indices) {
                compared = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
                if (compared != 0) break
            }
            compared
        }
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(rawSha1)
        records.forEach(digest::update)
        return digest.digest()
    }

    private fun v4Chd(
        hunkBytes: Int,
        rawSha1: ByteArray,
        overallSha1: ByteArray,
        parentOverallSha1: ByteArray?,
        metadata: List<ChdMetadataFixture>,
        entries: List<V3MapEntry>,
    ): ByteArray {
        require(metadata.isNotEmpty())
        val mapBytes = entries.size * MAP_ENTRY_BYTES
        val payloadOffset = V4_HEADER_BYTES + mapBytes + MAP_ENTRY_BYTES
        val payloadBytes = entries.sumOf { entry ->
            when (entry) {
                is V3MapEntry.Uncompressed -> entry.bytes.size
                is V3MapEntry.ParentHunk -> 0
            }
        }
        val metadataOffset = payloadOffset + payloadBytes
        val chd = ByteArray(metadataOffset + metadata.sumOf { METADATA_HEADER_BYTES + it.bytes.size })
        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(chd, 0)
        chd.putBigEndian32(8, V4_HEADER_BYTES)
        chd.putBigEndian32(12, 4)
        chd.putBigEndian32(16, if (parentOverallSha1 == null) 0 else 1)
        chd.putBigEndian32(20, 0)
        chd.putBigEndian32(24, entries.size)
        chd.putBigEndian64(28, entries.size.toLong() * hunkBytes)
        chd.putBigEndian64(36, metadataOffset.toLong())
        chd.putBigEndian32(44, hunkBytes)
        overallSha1.copyInto(chd, 48)
        parentOverallSha1?.copyInto(chd, 68)
        rawSha1.copyInto(chd, 88)
        writeV34Map(chd, V4_HEADER_BYTES, payloadOffset, hunkBytes, entries)
        writeMetadataChain(chd, metadataOffset, metadata)
        return chd
    }

    private fun v5Chd(
        hunkBytes: Int,
        rawSha1: ByteArray,
        overallSha1: ByteArray,
        parentOverallSha1: ByteArray?,
        metadata: List<ChdMetadataFixture>,
        hunks: List<V5Hunk>,
        unitBytes: Int = 512,
    ): ByteArray {
        require(metadata.isNotEmpty())
        val mapOffset = V5_HEADER_BYTES
        val mapBytes = hunks.size * 4
        // Uncompressed v5 map entries address offset/hunkBytes, so stored hunks are hunk-aligned.
        val payloadOffset = ((mapOffset + mapBytes + hunkBytes - 1) / hunkBytes) * hunkBytes
        val payloadBytes = hunks.sumOf { hunk ->
            when (hunk) {
                is V5Hunk.Stored -> hunk.bytes.size
                V5Hunk.Parent -> 0
            }
        }
        val metadataOffset = payloadOffset + payloadBytes
        val chd = ByteArray(metadataOffset + metadata.sumOf { METADATA_HEADER_BYTES + it.bytes.size })
        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(chd, 0)
        chd.putBigEndian32(8, V5_HEADER_BYTES)
        chd.putBigEndian32(12, 5)
        // compression[0..3] stay zero: an uncompressed v5 map with 4-byte entries.
        chd.putBigEndian64(32, hunks.size.toLong() * hunkBytes)
        chd.putBigEndian64(40, mapOffset.toLong())
        chd.putBigEndian64(48, metadataOffset.toLong())
        chd.putBigEndian32(56, hunkBytes)
        chd.putBigEndian32(60, unitBytes)
        rawSha1.copyInto(chd, 64)
        overallSha1.copyInto(chd, 84)
        parentOverallSha1?.copyInto(chd, 104)
        var nextPayloadOffset = payloadOffset
        hunks.forEachIndexed { index, hunk ->
            when (hunk) {
                V5Hunk.Parent -> chd.putBigEndian32(mapOffset + index * 4, 0)
                is V5Hunk.Stored -> {
                    require(hunk.bytes.size == hunkBytes)
                    require(nextPayloadOffset % hunkBytes == 0) {
                        "v5 uncompressed map entries address hunk-aligned offsets"
                    }
                    chd.putBigEndian32(mapOffset + index * 4, nextPayloadOffset / hunkBytes)
                    hunk.bytes.copyInto(chd, nextPayloadOffset)
                    nextPayloadOffset += hunk.bytes.size
                }
            }
        }
        writeMetadataChain(chd, metadataOffset, metadata)
        return chd
    }

    private fun differentialCdChain(depth: Int, payloadSeed: Int = 7): DifferentialCdFixture {
        require(depth in 2..3)
        val unitBytes = 2_448
        val frames = 4
        val hunkBytes = unitBytes * frames
        val hunk = ByteArray(hunkBytes)
        val canonicalPayload = ByteArray(512) { ((it * 29 + payloadSeed) and 0xff).toByte() }
        "SEGADISCSYSTEM  ".toByteArray(Charsets.US_ASCII).copyInto(canonicalPayload, 0)
        canonicalPayload.copyInto(hunk, 16)
        val raw = hunk.sha1()
        val images = ArrayList<ByteArray>(depth)
        var parentOverall: ByteArray? = null
        repeat(depth) { index ->
            val metadataBytes = (
                "TRACK:1 TYPE:MODE1_RAW SUBTYPE:NONE FRAMES:4 PREGAP:0 " +
                    "PGTYPE:MODE1_RAW PGSUB:NONE POSTGAP:0" +
                    " ".repeat(index) +
                    "\u0000"
                ).toByteArray(Charsets.US_ASCII)
            val metadata = listOf(
                ChdMetadataFixture(
                    tag = CHT2_METADATA_TAG,
                    bytes = metadataBytes,
                    checksummed = true,
                ),
            )
            val overall = overallSha1(raw, metadata)
            images += v5Chd(
                hunkBytes = hunkBytes,
                rawSha1 = raw,
                overallSha1 = overall,
                parentOverallSha1 = parentOverall,
                metadata = metadata,
                hunks = if (index == 0) {
                    listOf(V5Hunk.Stored(hunk))
                } else {
                    listOf(V5Hunk.Parent)
                },
                unitBytes = unitBytes,
            )
            parentOverall = overall
        }
        val expected = MessageDigest.getInstance("MD5")
            .digest(canonicalPayload)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
        return DifferentialCdFixture(images, expected)
    }

    private fun writeFixtureFiles(prefix: String, images: List<ByteArray>): List<File> {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return images.mapIndexed { index, bytes ->
            File(cache, "$prefix-$index.chd").also { it.writeBytes(bytes) }
        }
    }

    private suspend fun hashVerifiedSegaCdChain(
        files: List<File>,
        images: List<ByteArray>,
    ): ContainerIdentityResult<String?> {
        val nodes = files.mapIndexed { index, file ->
            ChdVerificationNode(
                path = file.absolutePath,
                header = requireNotNull(
                    RomFingerprinter.chdHeaderEvidence(
                        images[index],
                        minOf(images[index].size, CHD_HEADER_READ_BYTES),
                    ),
                ),
            )
        }
        return ChdChainPayloadVerifier.withVerifiedNativeChain(nodes) { borrowed ->
            RetroAchievementsNativeHash.hashChain(
                consoleId = 9,
                targetPath = borrowed.targetPath,
                nativeChainHandle = borrowed.nativeHandle,
            )
        }
    }

    private fun writeV34Map(
        chd: ByteArray,
        headerBytes: Int,
        payloadOffset: Int,
        hunkBytes: Int,
        entries: List<V3MapEntry>,
    ) {
        var nextPayloadOffset = payloadOffset
        entries.forEachIndexed { index, entry ->
            val mapAt = headerBytes + index * MAP_ENTRY_BYTES
            when (entry) {
                is V3MapEntry.ParentHunk -> {
                    chd.putBigEndian64(mapAt, entry.index.toLong())
                    chd[mapAt + 15] = 0x15
                }
                is V3MapEntry.Uncompressed -> {
                    require(entry.bytes.size == hunkBytes)
                    chd.putBigEndian64(mapAt, nextPayloadOffset.toLong())
                    chd[mapAt + 12] = ((hunkBytes ushr 8) and 0xff).toByte()
                    chd[mapAt + 13] = (hunkBytes and 0xff).toByte()
                    chd[mapAt + 14] = ((hunkBytes ushr 16) and 0xff).toByte()
                    chd[mapAt + 15] = 0x12
                    entry.bytes.copyInto(chd, nextPayloadOffset)
                    nextPayloadOffset += entry.bytes.size
                }
            }
        }
        "EndOfListCookie".toByteArray(Charsets.US_ASCII)
            .copyInto(chd, headerBytes + entries.size * MAP_ENTRY_BYTES)
    }

    private fun writeMetadataChain(chd: ByteArray, at: Int, entries: List<ChdMetadataFixture>) {
        var offset = at
        entries.forEachIndexed { index, entry ->
            val next = if (index == entries.lastIndex) {
                0L
            } else {
                (offset + METADATA_HEADER_BYTES + entry.bytes.size).toLong()
            }
            chd.putBigEndian32(offset, entry.tag)
            chd.putBigEndian32(
                offset + 4,
                ((if (entry.checksummed) CHD_MDFLAGS_CHECKSUM else 0) shl 24) or entry.bytes.size,
            )
            chd.putBigEndian64(offset + 8, next)
            entry.bytes.copyInto(chd, offset + METADATA_HEADER_BYTES)
            offset += METADATA_HEADER_BYTES + entry.bytes.size
        }
    }

    private class ChdMetadataFixture(
        val tag: Int,
        val bytes: ByteArray,
        val checksummed: Boolean,
    )

    private sealed interface V5Hunk {
        data class Stored(val bytes: ByteArray) : V5Hunk
        data object Parent : V5Hunk
    }

    private fun syntheticDifferentialV3Pair(): DifferentialFixture {
        val hunkBytes = 4_096
        val rootFirst = ByteArray(hunkBytes) { ((it * 17 + 11) and 0xff).toByte() }
        val rootSecond = ByteArray(hunkBytes) { ((it * 23 + 7) and 0xff).toByte() }
        val childSecond = ByteArray(hunkBytes) { ((it * 31 + 3) and 0xff).toByte() }
        val rootLogical = rootFirst + rootSecond
        val childLogical = rootFirst + childSecond
        val rootSha1 = rootLogical.sha1()
        val childSha1 = childLogical.sha1()
        val root = v3Chd(
            hunkBytes = hunkBytes,
            logicalSha1 = rootSha1,
            parentSha1 = null,
            entries = listOf(
                V3MapEntry.Uncompressed(rootFirst),
                V3MapEntry.Uncompressed(rootSecond),
            ),
        )
        val child = v3Chd(
            hunkBytes = hunkBytes,
            logicalSha1 = childSha1,
            parentSha1 = rootSha1,
            entries = listOf(
                V3MapEntry.ParentHunk(index = 0),
                V3MapEntry.Uncompressed(childSecond),
            ),
        )
        return DifferentialFixture(root, child, childSha1.toHex())
    }

    private fun v3Chd(
        hunkBytes: Int,
        logicalSha1: ByteArray,
        parentSha1: ByteArray?,
        entries: List<V3MapEntry>,
    ): ByteArray {
        val mapBytes = entries.size * MAP_ENTRY_BYTES
        val payloadOffset = V3_HEADER_BYTES + mapBytes + MAP_ENTRY_BYTES
        val payloadBytes = entries.sumOf { entry ->
            when (entry) {
                is V3MapEntry.Uncompressed -> entry.bytes.size
                is V3MapEntry.ParentHunk -> 0
            }
        }
        val chd = ByteArray(payloadOffset + payloadBytes)
        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(chd, 0)
        chd.putBigEndian32(8, V3_HEADER_BYTES)
        chd.putBigEndian32(12, 3)
        chd.putBigEndian32(16, if (parentSha1 == null) 0 else 1)
        chd.putBigEndian32(24, entries.size)
        chd.putBigEndian64(28, entries.size.toLong() * hunkBytes)
        chd.putBigEndian32(76, hunkBytes)
        logicalSha1.copyInto(chd, 80)
        parentSha1?.copyInto(chd, 100)

        var nextPayloadOffset = payloadOffset
        entries.forEachIndexed { index, entry ->
            val mapAt = V3_HEADER_BYTES + index * MAP_ENTRY_BYTES
            when (entry) {
                is V3MapEntry.ParentHunk -> {
                    chd.putBigEndian64(mapAt, entry.index.toLong())
                    chd[mapAt + 15] = 0x15
                }
                is V3MapEntry.Uncompressed -> {
                    require(entry.bytes.size == hunkBytes)
                    chd.putBigEndian64(mapAt, nextPayloadOffset.toLong())
                    chd[mapAt + 12] = ((hunkBytes ushr 8) and 0xff).toByte()
                    chd[mapAt + 13] = (hunkBytes and 0xff).toByte()
                    chd[mapAt + 14] = ((hunkBytes ushr 16) and 0xff).toByte()
                    chd[mapAt + 15] = 0x12
                    entry.bytes.copyInto(chd, nextPayloadOffset)
                    nextPayloadOffset += entry.bytes.size
                }
            }
        }
        "EndOfListCookie".toByteArray(Charsets.US_ASCII)
            .copyInto(chd, V3_HEADER_BYTES + mapBytes)
        return chd
    }

    private fun ByteArray.sha1(): ByteArray = MessageDigest.getInstance("SHA-1").digest(this)

    private fun ByteArray.toHex(): String = joinToString(separator = "") {
        "%02x".format(it.toInt() and 0xff)
    }

    private fun ByteArray.putBigEndian32(offset: Int, value: Int) {
        for (index in 0 until 4) this[offset + index] = (value ushr ((3 - index) * 8)).toByte()
    }

    private fun ByteArray.putBigEndian64(offset: Int, value: Long) {
        for (index in 0 until 8) this[offset + index] = (value ushr ((7 - index) * 8)).toByte()
    }

    private sealed interface V3MapEntry {
        data class Uncompressed(val bytes: ByteArray) : V3MapEntry
        data class ParentHunk(val index: Int) : V3MapEntry
    }

    private data class DifferentialFixture(
        val root: ByteArray,
        val child: ByteArray,
        val childSha1: String,
    )

    private data class DifferentialCdFixture(
        val images: List<ByteArray>,
        val expectedMd5: String,
    )

    private companion object {
        const val V3_HEADER_BYTES = 120
        const val V4_HEADER_BYTES = 108
        const val V5_HEADER_BYTES = 124
        const val MAP_ENTRY_BYTES = 16
        const val METADATA_HEADER_BYTES = 16
        const val METADATA_TAG = 0x54455354 // 'TEST'
        const val METADATA_TAG_DESCENDING = 0x5a5a5a5a // 'ZZZZ'
        const val METADATA_TAG_ASCENDING = 0x41414141 // 'AAAA'
        const val METADATA_TAG_UNCHECKSUMMED = 0x4e4f434b // 'NOCK'
        const val CHT2_METADATA_TAG = 0x43485432 // 'CHT2'
        const val CHD_MDFLAGS_CHECKSUM = 0x01
        const val CHD_HEADER_READ_BYTES = 132
    }
}
