package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.PerGameTweakerSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PerGameTweakerSelectionStoreTest {
    @Test
    fun `record codec round trips one atomic explicit choice and rejects malformed input`() {
        val expected = record(PerGameTweakerSelection.BALANCED, 60, 20L, 3L)

        assertEquals(expected, decodePerGameTweakerConfiguration(encodePerGameTweakerConfiguration(expected)))
        assertNull(decodePerGameTweakerConfiguration("2|BALANCED|60|20|3"))
        assertNull(decodePerGameTweakerConfiguration("1|UNKNOWN|60|20|3"))
        assertNull(decodePerGameTweakerConfiguration("1|BALANCED|999|20|3"))
    }

    @Test
    fun `write revision is monotonic even when wall clock moves backwards`() {
        val prior = record(PerGameTweakerSelection.POWER_SAVING, null, 100L, 8L)
        val next = nextPerGameTweakerConfiguration(
            previous = prior,
            profile = PerGameTweakerSelection.MAX,
            frameLimitHz = 40,
            nowMillis = 50L,
        )

        assertEquals(100L, next.updatedAtMillis)
        assertEquals(9L, next.revision)
        assertEquals(PerGameTweakerSelection.MAX, next.profile)
        assertEquals(40, next.frameLimitHz)
    }

    @Test
    fun `newest atomic record follows an alias and exact ties stay canonical`() {
        val olderTarget = record(PerGameTweakerSelection.BALANCED, 60, 10L, 1L)
        val newerSource = record(PerGameTweakerSelection.MAX, 40, 20L, 1L)
        val moved = remapPerGameTweakerDocument(
            PerGameTweakerDocument(mapOf("A" to newerSource, "B" to olderTarget)),
            mapOf("A" to "B"),
        )
        assertEquals(mapOf("B" to newerSource), moved.records)

        val tiedSource = olderTarget.copy(profile = PerGameTweakerSelection.MAX)
        val tied = remapPerGameTweakerDocument(
            PerGameTweakerDocument(mapOf("A" to tiedSource, "B" to olderTarget)),
            mapOf("A" to "B"),
        )
        assertEquals(mapOf("B" to olderTarget), tied.records)
    }

    @Test
    fun `recurring winner aliases remain idempotent without reviving stale state`() {
        val chosen = record(PerGameTweakerSelection.MAX, 60, 30L, 4L)
        val onB = remapPerGameTweakerDocument(
            PerGameTweakerDocument(mapOf("A" to chosen)),
            mapOf("A" to "B"),
        )
        val onA = remapPerGameTweakerDocument(onB, mapOf("B" to "A"))
        val onBAgain = remapPerGameTweakerDocument(onA, mapOf("A" to "B"))

        assertEquals(mapOf("B" to chosen), onBAgain.records)
        assertSame(onBAgain, remapPerGameTweakerDocument(onBAgain, mapOf("A" to "B")))
    }

    @Test
    fun `two equal noncanonical sources choose one deterministically`() {
        val value = record(PerGameTweakerSelection.POWER_SAVING, null, 10L, 1L)
        val other = value.copy(profile = PerGameTweakerSelection.MAX)
        val mapped = remapPerGameTweakerDocument(
            PerGameTweakerDocument(linkedMapOf("C" to other, "A" to value)),
            mapOf("A" to "B", "C" to "B"),
        )

        assertEquals(value, mapped.records.getValue("B"))
    }

    private fun record(
        profile: PerGameTweakerSelection,
        frameLimitHz: Int?,
        updatedAtMillis: Long,
        revision: Long,
    ) = PerGameTweakerConfiguration(profile, frameLimitHz, updatedAtMillis, revision)

}
