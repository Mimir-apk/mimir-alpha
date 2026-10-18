package com.gruning.mimir.rewrite.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a tile's state word draws as (review 2026-10-13: "DEFAULT" and "SAVED" read "DE..." and "SA..." on a compact tile). One line when it fits; two words that do not fit
 * one line stack, one to a line, when each fits alone ("PREVIEW" over "UNAVAILABLE" on a tile too narrow for the pair); and a word that does not fit alone is left off, because
 * a single word has no boundary to cut at. Widths here are ten per character, so the table reads as numbers.
 */
class AppearanceTileBadgeTest {

    private val width: (String) -> Int = { it.length * 10 }

    @Test
    fun `a word that fits is drawn as it is`() {
        assertEquals("SAVED", tileBadgeText("SAVED", width, room = 50))
        assertEquals("DEFAULT", tileBadgeText("DEFAULT", width, room = 200))
    }

    @Test
    fun `a single word that does not fit is left off, never cut`() {
        assertNull(tileBadgeText("DEFAULT", width, room = 60))
        assertNull(tileBadgeText("CURRENT", width, room = 69))
    }

    @Test
    fun `two words that do not fit one line stack when each fits alone`() {
        assertEquals("PREVIEW UNAVAILABLE", tileBadgeText("PREVIEW UNAVAILABLE", width, room = 190))
        assertEquals("PREVIEW\nUNAVAILABLE", tileBadgeText("PREVIEW UNAVAILABLE", width, room = 110))
    }

    @Test
    fun `two words stay off when even the longer one does not fit alone`() {
        assertNull(tileBadgeText("PREVIEW UNAVAILABLE", width, room = 100))
    }
}
