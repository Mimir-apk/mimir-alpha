package com.gruning.mimir.rewrite.core.metadata

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Android-runtime proof for the bounded ZIP stream semantics used by the production ContentResolver path. */
@RunWith(AndroidJUnit4::class)
class ArchivePayloadSelectorDeviceTest {

    @Test
    fun ownerShapedNestedBundleIdentifiesAtExactlyOneDescent() = runBlocking {
        val rom = payload(seed = 1, size = 8 * 1024 * 1024)
        val inner = zipOf(Entry("Super Mario 64 (ESP) [!].z64", rom))
        val outer = zipOf(
            Entry("readme.txt", ByteArray(25), stored = true),
            Entry("Super Mario 64 - 51230/files/33226/51230.zip", inner, stored = true),
            Entry("Super Mario 64 - 51230/images/cover.png", payload(2, 84_760)),
        )
        val expectedSha1 = FileHasher.hash(ByteArrayInputStream(rom)).sha1

        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = outer.size.toLong(),
            openResident = { ByteArrayInputStream(outer) },
            oracle = { _: PlatformId, fingerprint: RomFingerprint ->
                if (fingerprint.datHash.sha1 == expectedSha1) {
                    ArchivePayloadOracleMatch(
                        canonicalTitle = "Super Mario 64",
                        collapseKey = "n64:super-mario-64",
                        origin = MetadataOrigin.NO_INTRO,
                    )
                } else {
                    null
                }
            },
            nanoTime = { 0L },
        ).select(ChdVerificationBudget.forResolve(nanoTime = { 0L }))

        val identified = result as ArchivePayloadSelectionResult.Identified
        assertEquals("Super Mario 64", identified.identity.canonicalTitle)
        assertEquals(1, identified.identity.descentDepth)
        assertEquals(ArchivePayloadReach(1), identified.reach)
    }

    @Test
    fun shallowPayloadWinsWithoutOpeningAnEarlierWrapper() = runBlocking {
        val rom = payload(seed = 3, size = 4096)
        val outer = zipOf(
            Entry("first-wrapper.zip", payload(4, 4096), stored = true),
            Entry("selected.z64", rom),
        )
        val expectedSha1 = FileHasher.hash(ByteArrayInputStream(rom)).sha1
        var depthOneReached = false

        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = outer.size.toLong(),
            openResident = { ByteArrayInputStream(outer) },
            oracle = { _, fingerprint ->
                if (fingerprint.datHash.sha1 == expectedSha1) {
                    ArchivePayloadOracleMatch(
                        canonicalTitle = "Shallow Winner",
                        collapseKey = "n64:shallow-winner",
                        origin = MetadataOrigin.NO_INTRO,
                    )
                } else {
                    null
                }
            },
            nanoTime = { 0L },
            onCandidateReached = { depth -> if (depth == 1) depthOneReached = true },
        ).select(ChdVerificationBudget.forResolve(nanoTime = { 0L }))

        assertEquals(
            "Shallow Winner",
            (result as ArchivePayloadSelectionResult.Identified).identity.canonicalTitle,
        )
        assertFalse(depthOneReached)
    }

    private fun payload(seed: Int, size: Int): ByteArray {
        var state = (seed.toLong() shl 32) xor 0x9e3779b97f4a7c15UL.toLong()
        return ByteArray(size) {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            state.toByte()
        }
    }

    private data class Entry(
        val name: String,
        val bytes: ByteArray,
        val stored: Boolean = false,
    )

    private fun zipOf(vararg entries: Entry): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { item ->
                val entry = ZipEntry(item.name)
                if (item.stored) {
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
}
