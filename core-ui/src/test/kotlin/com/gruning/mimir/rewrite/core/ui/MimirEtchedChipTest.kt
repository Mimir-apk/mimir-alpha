package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a screen reader says for the etched achievement-count chip: the numbers in words, never the bare "12/40". */
class MimirEtchedChipTest {

    @Test
    fun `a count is spoken as of`() {
        assertEquals("12 of 40 achievements", mimirAchievementChipDescription("12/40"))
        assertEquals("12 of 40 achievements", mimirAchievementChipDescription("12 / 40"))
        assertEquals("0 of 5 achievements", mimirAchievementChipDescription("0/5"))
    }

    @Test
    fun `anything that is not a count is spoken as it is`() {
        assertEquals("mastered achievements", mimirAchievementChipDescription("mastered"))
        assertEquals("1/2/3 achievements", mimirAchievementChipDescription("1/2/3"))
        assertEquals("/ achievements", mimirAchievementChipDescription("/"))
        assertEquals("12/ achievements", mimirAchievementChipDescription("12/"))
    }
}
