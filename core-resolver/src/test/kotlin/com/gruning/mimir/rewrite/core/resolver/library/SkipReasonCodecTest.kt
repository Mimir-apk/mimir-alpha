package com.gruning.mimir.rewrite.core.resolver.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `REASON=count;REASON=count` format that lets a ledgered Rescan All carry its skip breakdown
 * (schema v14, 2026-08-30).
 *
 * It is a hand-rolled format in a persisted column, which is exactly the kind of thing that is
 * trusted and never checked. The property that matters most is the last one: a malformed pair is
 * DROPPED, never guessed at, because a receipt that invents a count is worse than one that admits it
 * lost a reason.
 */
class SkipReasonCodecTest {

    @Test
    fun `round-trips a real breakdown`() {
        val map = linkedMapOf("UNREADABLE" to 7, "BIOS" to 5, "ARCHIVE" to 30)
        assertEquals(map, decodeSkipReasons(encodeSkipReasons(map)))
    }

    @Test
    fun `an empty map encodes empty and an empty column decodes empty`() {
        assertEquals("", encodeSkipReasons(emptyMap()))
        assertEquals(emptyMap<String, Int>(), decodeSkipReasons(""))
        // Every pre-v14 row reads back through this path.
        assertEquals(emptyMap<String, Int>(), decodeSkipReasons("   "))
    }

    @Test
    fun `separators cannot appear inside a key or a value, so nothing needs escaping`() {
        // SkipReason.name is [A-Z_]+ and counts are non-negative integers. This test exists so that
        // stops being an assumption: if a reason ever gains a ';' or '=', this is what fails.
        val encoded = encodeSkipReasons(mapOf("UNREADABLE" to 7, "NOT_A_GAME" to 2))
        assertEquals("UNREADABLE=7;NOT_A_GAME=2", encoded)
        assertTrue(encoded.count { it == ';' } == 1)
    }

    @Test
    fun `a malformed pair is dropped, never guessed at`() {
        val decoded = decodeSkipReasons("UNREADABLE=7;GARBAGE;BIOS=notanumber;=4;ARCHIVE=3")
        assertEquals(
            "only the two well-formed pairs survive",
            mapOf("UNREADABLE" to 7, "ARCHIVE" to 3),
            decoded,
        )
    }

    @Test
    fun `a negative count is refused rather than carried`() {
        assertEquals(emptyMap<String, Int>(), decodeSkipReasons("UNREADABLE=-3"))
    }

    @Test
    fun `merging accumulates across roots and keeps first-seen order`() {
        val a = linkedMapOf("UNREADABLE" to 3, "BIOS" to 2)
        val b = linkedMapOf("UNREADABLE" to 1, "ARCHIVE" to 3)

        val merged = mergeSkipReasons(a, b)

        assertEquals(linkedMapOf("UNREADABLE" to 4, "BIOS" to 2, "ARCHIVE" to 3), merged)
        assertEquals(
            "order must be stable so a reader's rows do not reshuffle between passes",
            listOf("UNREADABLE", "BIOS", "ARCHIVE"),
            merged.keys.toList(),
        )
    }

    @Test
    fun `merging an empty right leaves the left untouched`() {
        val left = mapOf("BIOS" to 2)
        assertEquals(left, mergeSkipReasons(left, emptyMap()))
    }

    @Test
    fun `a duplicated reason inside one encoded string sums rather than overwrites`() {
        // Defensive: a future writer that appends instead of replacing must not silently lose counts.
        assertEquals(mapOf("BIOS" to 5), decodeSkipReasons("BIOS=2;BIOS=3"))
    }
}
