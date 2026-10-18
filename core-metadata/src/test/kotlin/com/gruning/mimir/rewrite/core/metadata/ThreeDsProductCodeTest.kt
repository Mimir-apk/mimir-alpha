package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 3DS is 16.14 GB across 26 files in the sampled library — 43% of every
 * byte the identity pass reads — purely because it is not on the head-bytes route and falls through to
 * `RomFingerprinter`'s whole-file rule.
 *
 * These pin the NCSD/NCCH walk against **synthetic headers built here**, not against a claim about the
 * format. Every offset the reader uses appears in `header()` below as an explicit write, so a wrong
 * constant fails rather than silently reading padding, and the negative cases pin that each magic check
 * and each bound is load-bearing.
 */
class ThreeDsProductCodeTest {

    private val partitionOffsetBytes = 0x4000

    /**
     * A minimal but structurally honest 3DS card image head.
     *
     * NCSD: magic at 0x100, partition table at 0x120 holding partition 0's offset in 0x200-byte media
     * units. NCCH (partition 0): magic at +0x100, product code at +0x150.
     */
    private fun header(
        productCode: String = "CTR-P-AQEE",
        ncsdMagic: String = "NCSD",
        ncchMagic: String = "NCCH",
        partitionOffset: Int = partitionOffsetBytes,
        size: Int = 0x8000,
    ): ByteArray {
        val buf = ByteArray(size)
        ncsdMagic.toByteArray(Charsets.US_ASCII).copyInto(buf, 0x100)
        val mediaUnits = partitionOffset / 0x200
        buf[0x120] = (mediaUnits and 0xFF).toByte()
        buf[0x121] = ((mediaUnits shr 8) and 0xFF).toByte()
        buf[0x122] = ((mediaUnits shr 16) and 0xFF).toByte()
        buf[0x123] = ((mediaUnits shr 24) and 0xFF).toByte()
        if (partitionOffset + 0x160 <= size) {
            ncchMagic.toByteArray(Charsets.US_ASCII).copyInto(buf, partitionOffset + 0x100)
            productCode.toByteArray(Charsets.US_ASCII).copyInto(buf, partitionOffset + 0x150)
        }
        return buf
    }

    @Test
    fun readsTheProductCodeFromARetailCardImage() {
        val head = header()
        assertEquals("AQEE", IdExtractor.threeDsProductCode(head, head.size))
        assertEquals(
            ExtractedId(IdKeyType.GAME_ID, "AQEE"),
            IdExtractor.fromHeader("3ds", head, head.size),
        )
    }

    @Test
    fun acceptsTheDigitalProductCodePrefixToo() {
        val head = header(productCode = "CTR-N-JF3P")
        assertEquals("JF3P", IdExtractor.threeDsProductCode(head, head.size))
    }

    @Test
    fun followsThePartitionTableRatherThanAssumingTheUsualOffset() {
        // The common answer is 0x4000. A dump that says otherwise is followed, not overruled -- which is
        // the whole reason the table is read instead of the constant being hardcoded.
        val head = header(partitionOffset = 0x5000, size = 0x8000)
        assertEquals("AQEE", IdExtractor.threeDsProductCode(head, head.size))
    }

    @Test
    fun refusesWhenTheNcsdMagicIsAbsent() {
        // Content-sniffed, never taken from the extension: a file merely named .3ds proves nothing.
        val head = header(ncsdMagic = "XXXX")
        assertNull(IdExtractor.threeDsProductCode(head, head.size))
    }

    @Test
    fun refusesWhenThePartitionDoesNotCarryTheNcchMagicItClaims() {
        val head = header(ncchMagic = "XXXX")
        assertNull(IdExtractor.threeDsProductCode(head, head.size))
    }

    @Test
    fun refusesAProductCodeThatIsNotAThreeDsOne() {
        assertNull(IdExtractor.threeDsProductCode(header(productCode = "RVL-P-AQEE").let { it }, 0x8000))
        assertNull(IdExtractor.threeDsProductCode(header(productCode = "CTR-P-AQ").let { it }, 0x8000))
    }

    @Test
    fun refusesRatherThanGuessingWhenTheHeadIsTooShortToReach() {
        // The caller's read budget, not the file, is what fell short. Returning null is what lets
        // LibraryIdentityEnricher fall back to the hash instead of asserting a key it never saw.
        val head = header()
        assertNull(IdExtractor.threeDsProductCode(head, 0x200))
        assertNull(IdExtractor.threeDsProductCode(head, partitionOffsetBytes + 0x100))
    }

    @Test
    fun aPartitionOffsetOfZeroIsNotAHeader() {
        // 0 media units would point the NCCH read back into the NCSD header itself.
        val head = header(partitionOffset = 0)
        assertNull(IdExtractor.threeDsProductCode(head, head.size))
    }

    @Test
    fun otherPlatformsAreUnaffectedByTheNewBranch() {
        val head = header()
        assertNull(IdExtractor.fromHeader("nds", head, head.size))
        assertNull(IdExtractor.fromHeader("ps2", head, head.size))
        // A GameCube image is six ASCII chars at offset 0 and must keep resolving that way.
        val gc = ByteArray(0x200)
        "GALE01".toByteArray(Charsets.US_ASCII).copyInto(gc, 0)
        assertEquals(
            ExtractedId(IdKeyType.GAME_ID, "GALE01"),
            IdExtractor.fromHeader("gamecube", gc, gc.size),
        )
    }
}
