package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * QA-17 follow-up (2026-09-16). DS was 2.53 GB across **88 files** on the reference device — the largest
 * file count left on the whole-file hash path after 3DS moved off it. File count matters as much as bytes
 * here: the enricher walks smallest-first, so these carts are the games a player actually watches the
 * counter move through.
 *
 * An NDS header has no magic string, so the structure is validated instead. These pin that validation, and
 * most of them are negative: the risk with a magic-less header is not failing to read a real cart, it is
 * confidently minting a key from four capital letters that happened to land at 0x0C.
 */
class DsGameCodeTest {

    private fun cart(
        title: String = "MPPINBALL",
        code: String = "AP2E",
        size: Int = 512,
    ): ByteArray {
        val b = ByteArray(size)
        title.toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        code.toByteArray(Charsets.US_ASCII).copyInto(b, 0x0C)
        return b
    }

    @Test
    fun readsTheGameCodeFromACartHeader() {
        val b = cart()
        assertEquals("AP2E", IdExtractor.dsGameCode(b, b.size))
        assertEquals(ExtractedId(IdKeyType.GAME_ID, "AP2E"), IdExtractor.fromHeader("ds", b, b.size))
    }

    @Test
    fun readsTheOwnersRealCartCodes() {
        // Verified against the device the same day: these three carts, these three codes, all three
        // present in the shipped catalogue.
        assertEquals("AP2E", IdExtractor.dsGameCode(cart("MPPINBALL", "AP2E"), 512))
        assertEquals("APTE", IdExtractor.dsGameCode(cart("TROZEI", "APTE"), 512))
        assertEquals("APDP", IdExtractor.dsGameCode(cart("POKEMON_DASH", "APDP"), 512))
    }

    @Test
    fun aTitleFieldWithJunkIsNotACartHeader() {
        // The whole point of checking the title: without it, any file with four capitals at 0x0C mints a
        // key, and a wrong key is a wrong game on the shelf.
        assertNull(IdExtractor.dsGameCode(cart(title = "abc"), 512))
        val binary = cart()
        binary[3] = 0xFF.toByte()
        assertNull(IdExtractor.dsGameCode(binary, binary.size))
    }

    @Test
    fun aTitleThatResumesAfterItsPaddingIsNotACartHeader() {
        // NUL padding runs to the end of the 12-byte field. A byte after it means this is some other
        // structure that merely starts with text.
        val b = cart(title = "ABC")
        b[8] = 'X'.code.toByte()
        assertNull(IdExtractor.dsGameCode(b, b.size))
    }

    @Test
    fun anEmptyTitleIsNotACartHeader() {
        val b = cart(title = "")
        assertNull(IdExtractor.dsGameCode(b, b.size))
    }

    @Test
    fun aMalformedGameCodeIsRefusedRatherThanCleanedUp() {
        assertNull(IdExtractor.dsGameCode(cart(code = "ap2e"), 512))
        assertNull(IdExtractor.dsGameCode(cart(code = "AP-E"), 512))
    }

    @Test
    fun aHeadTooShortToReachTheCodeYieldsNothing() {
        // Returning null routes the game to the hash, which is correct; asserting a key from bytes never
        // read is not.
        val b = cart()
        assertNull(IdExtractor.dsGameCode(b, 0x0C))
        assertNull(IdExtractor.dsGameCode(b, 0x0F))
    }

    @Test
    fun otherPlatformsDoNotPickUpTheDsBranch() {
        val b = cart()
        assertNull(IdExtractor.fromHeader("ps2", b, b.size))
        assertNull(IdExtractor.fromHeader("3ds", b, b.size))
    }
}
