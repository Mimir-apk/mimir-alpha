package com.gruning.mimir.rewrite.core.metadata.online

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.metadata.ChdPayloadVerifier
import com.gruning.mimir.rewrite.core.metadata.ContainerIdentityResult
import com.gruning.mimir.rewrite.core.metadata.RomFingerprinter
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetroAchievementsNativeHashTest {

    @Test
    fun upstreamNintendoDsSeekFixtureMatchesPinnedDigest() {
        assertTrue("native reader must load on the device ABI", RetroAchievementsNativeHash.isAvailable())
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ra-native-fixture.nds")
        try {
            file.writeBytes(upstreamNintendoDsFixture())
            assertEquals(
                "56b30c276cba4affa886bd38e8e34d7e",
                RetroAchievementsHash.hashPath("ds", file.name, file.absolutePath),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun syntheticUncompressedChdReadsCanonicalSegaCdPayload() {
        assertTrue("native reader must load on the device ABI", RetroAchievementsNativeHash.isAvailable())
        val fixture = syntheticSegaCdChd()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ra-native-fixture.chd")
        try {
            file.writeBytes(fixture.chd)
            assertEquals(fixture.expectedHash, RetroAchievementsHash.hashPath("segacd", file.name, file.absolutePath))
        } finally {
            file.delete()
        }
    }

    @Test
    fun syntheticUncompressedChdEarnsOverallIdentityThroughLibchdr() = runBlocking {
        val fixture = syntheticSegaCdChd()
        val header = requireNotNull(
            RomFingerprinter.chdHeaderEvidence(fixture.chd, 132),
        )
        val file = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "athena-chd-identity-fixture.chd",
        )
        try {
            file.writeBytes(fixture.chd)
            val result = ChdPayloadVerifier.verify(file.absolutePath, header)
            assertTrue(
                "libchdr must recompute raw payload and checksum metadata before catalog authority",
                result is ContainerIdentityResult.Identified,
            )
            val identified = (result as ContainerIdentityResult.Identified).value
            assertEquals(header.rawSha1, identified.rawSha1)
            assertEquals(header.combinedSha1, identified.combinedSha1)
        } finally {
            file.delete()
        }
    }

    @Test
    fun syntheticVersionThreeChdUsesItsSoleSha1ThroughLibchdr() = runBlocking {
        val chd = syntheticLegacyV3Chd()
        val header = requireNotNull(RomFingerprinter.chdHeaderEvidence(chd, 132))
        val file = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "athena-chd-v3-identity-fixture.chd",
        )
        try {
            file.writeBytes(chd)
            val result = ChdPayloadVerifier.verify(file.absolutePath, header)
            assertTrue(
                "CHDv3 must recompute its payload instead of requiring the absent v4 rawsha1 field",
                result is ContainerIdentityResult.Identified,
            )
            val identified = (result as ContainerIdentityResult.Identified).value
            assertEquals(3, identified.version)
            assertEquals(header.rawSha1, identified.rawSha1)
            assertEquals(header.rawSha1, identified.combinedSha1)
        } finally {
            file.delete()
        }
    }

    /**
     * Optional supplied-media proof. The committed gate skips when no runner arguments are supplied; release
     * verification passes `raMediaPath` + `raPlatform` without hardcoding private filenames in source.
     */
    @Test
    fun ownerMediaArgumentProducesCanonicalIdentityWhenSupplied() {
        val arguments = InstrumentationRegistry.getArguments()
        val path = arguments.getString("raMediaPath") ?: return
        val platform = arguments.getString("raPlatform") ?: return
        val file = File(path)
        assertTrue("supplied-media argument must resolve to a readable file", file.isFile && file.canRead())
        val hash = RetroAchievementsHash.hashPath(platform, file.name, file.absolutePath)
        assertNotNull("upstream reader must identify the supplied media fixture", hash)
        assertTrue("canonical identity is a lowercase MD5", hash!!.matches(Regex("[0-9a-f]{32}")))
        arguments.getString("raExpectedHash")?.let { expected -> assertEquals(expected, hash) }
    }

    /** Exact generator and expected digest from pinned rcheevos' `test_hash_nds`. */
    private fun upstreamNintendoDsFixture(): ByteArray {
        val image = ByteArray(2 * 1024 * 1024)
        fillUpstreamPattern(image)
        val arm9Address = 65_536
        val arm9Size = 1_234_567
        val arm7Address = arm9Address + arm9Size
        val arm7Size = 654_321
        val iconAddress = arm7Address + arm7Size
        image.putLittleEndian32(0x20, arm9Address)
        image.putLittleEndian32(0x2C, arm9Size)
        image.putLittleEndian32(0x30, arm7Address)
        image.putLittleEndian32(0x3C, arm7Size)
        image.putLittleEndian32(0x68, iconAddress)
        return image
    }

    private fun fillUpstreamPattern(target: ByteArray) {
        var remaining = target.size
        var offset = 0
        var seed = target.size xor (target.size ushr 8) xor ((target.size - 1) * 25_387)
        while (remaining > 0) {
            var count: Int
            val value: Int
            when (seed and 0xFF) {
                0 -> {
                    count = ((seed shr 8) and 0x3F) and (remaining and 0x0F).inv()
                    if (count == 0) count = 1
                    value = 0
                }
                1 -> {
                    count = ((seed shr 8) and 0x07) + 1
                    value = (seed shr 16) and 0xFF
                }
                2 -> {
                    count = ((seed shr 8) and 0x03) + 1
                    value = ((seed shr 16) and 0xFF) xor 0xFF
                }
                3 -> {
                    count = ((seed shr 8) and 0x03) + 1
                    value = ((seed shr 16) and 0xFF) xor 0xA5
                }
                4 -> {
                    count = ((seed shr 8) and 0x03) + 1
                    value = ((seed shr 16) and 0xFF) xor 0xC3
                }
                5 -> {
                    count = ((seed shr 8) and 0x03) + 1
                    value = ((seed shr 16) and 0xFF) xor 0x96
                }
                6, 7 -> {
                    count = ((seed shr 8) and 0x03) + 1
                    value = ((seed shr 16) and 0xFF) xor 0x78
                }
                else -> {
                    count = 1
                    value = ((seed shr 8) xor (seed shr 16)) and 0xFF
                }
            }
            do {
                target[offset++] = value.toByte()
                remaining--
                count--
            } while (remaining > 0 && count > 0)
            seed = (seed * 0x41C64E6D + 12_345) and 0x7FFF_FFFF
        }
    }

    /** Minimal valid uncompressed CHDv5: one four-frame MODE1_RAW track plus CHT2 metadata. */
    private fun syntheticSegaCdChd(): ChdFixture {
        val unitBytes = 2_448
        val frames = 4
        val hunkBytes = unitBytes * frames
        val dataOffset = hunkBytes
        val metadataOffset = dataOffset + hunkBytes
        val metadata = "TRACK:1 TYPE:MODE1_RAW SUBTYPE:NONE FRAMES:4 PREGAP:0 PGTYPE:MODE1_RAW PGSUB:NONE POSTGAP:0\u0000"
            .toByteArray(Charsets.US_ASCII)
        val chd = ByteArray(metadataOffset + 16 + metadata.size)

        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(chd, 0)
        chd.putBigEndian32(8, 124)
        chd.putBigEndian32(12, 5)
        // compression[4] at 16..31 stays CHD_CODEC_NONE.
        chd.putBigEndian64(32, hunkBytes.toLong())
        chd.putBigEndian64(40, 124L)
        chd.putBigEndian64(48, metadataOffset.toLong())
        chd.putBigEndian32(56, hunkBytes)
        chd.putBigEndian32(60, unitBytes)
        // The uncompressed map stores the hunk's byte offset divided by hunk size.
        chd.putBigEndian32(124, 1)

        val payload = ByteArray(512) { ((it * 29 + 7) and 0xFF).toByte() }
        "SEGADISCSYSTEM  ".toByteArray(Charsets.US_ASCII).copyInto(payload, 0)
        payload.copyInto(chd, dataOffset + 16)
        val rawPayloadSha1 = MessageDigest.getInstance("SHA-1")
            .digest(chd.copyOfRange(dataOffset, dataOffset + hunkBytes))
        rawPayloadSha1.copyInto(chd, 64)
        val metadataRecord = "CHT2".toByteArray(Charsets.US_ASCII) +
            MessageDigest.getInstance("SHA-1").digest(metadata)
        val combinedSha1 = MessageDigest.getInstance("SHA-1")
            .digest(rawPayloadSha1 + metadataRecord)
        combinedSha1.copyInto(chd, 84)

        "CHT2".toByteArray(Charsets.US_ASCII).copyInto(chd, metadataOffset)
        // High byte carries CHD_MDFLAGS_CHECKSUM; low 24 bits carry metadata length.
        chd.putBigEndian32(metadataOffset + 4, (1 shl 24) or metadata.size)
        chd.putBigEndian64(metadataOffset + 8, 0L)
        metadata.copyInto(chd, metadataOffset + 16)

        val expected = MessageDigest.getInstance("MD5").digest(payload).joinToString("") { "%02x".format(it) }
        return ChdFixture(chd, expected)
    }

    /** Minimal valid uncompressed CHDv3: one map entry, the required cookie, and one raw hunk. */
    private fun syntheticLegacyV3Chd(): ByteArray {
        val headerBytes = 120
        val mapEntryBytes = 16
        val cookieBytes = 16
        val payload = ByteArray(4_096) { ((it * 17 + 11) and 0xFF).toByte() }
        val payloadOffset = headerBytes + mapEntryBytes + cookieBytes
        val chd = ByteArray(payloadOffset + payload.size)

        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(chd, 0)
        chd.putBigEndian32(8, headerBytes)
        chd.putBigEndian32(12, 3)
        // flags and CHDCOMPRESSION_NONE remain zero.
        chd.putBigEndian32(24, 1)
        chd.putBigEndian64(28, payload.size.toLong())
        // metadata offset, MD5, and parent digests remain zero.
        chd.putBigEndian32(76, payload.size)
        MessageDigest.getInstance("SHA-1").digest(payload).copyInto(chd, 80)

        chd.putBigEndian64(headerBytes, payloadOffset.toLong())
        chd.putBigEndian32(headerBytes + 8, 0)
        chd[headerBytes + 12] = ((payload.size ushr 8) and 0xff).toByte()
        chd[headerBytes + 13] = (payload.size and 0xff).toByte()
        chd[headerBytes + 14] = ((payload.size ushr 16) and 0xff).toByte()
        // MAP_ENTRY_FLAG_NO_CRC | V34_MAP_ENTRY_TYPE_UNCOMPRESSED.
        chd[headerBytes + 15] = 0x12
        "EndOfListCookie".toByteArray(Charsets.US_ASCII)
            .copyInto(chd, headerBytes + mapEntryBytes)
        payload.copyInto(chd, payloadOffset)
        return chd
    }

    private fun ByteArray.putLittleEndian32(offset: Int, value: Int) {
        for (index in 0 until 4) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun ByteArray.putBigEndian32(offset: Int, value: Int) {
        for (index in 0 until 4) this[offset + index] = (value ushr ((3 - index) * 8)).toByte()
    }

    private fun ByteArray.putBigEndian64(offset: Int, value: Long) {
        for (index in 0 until 8) this[offset + index] = (value ushr ((7 - index) * 8)).toByte()
    }

    private data class ChdFixture(val chd: ByteArray, val expectedHash: String)
}
