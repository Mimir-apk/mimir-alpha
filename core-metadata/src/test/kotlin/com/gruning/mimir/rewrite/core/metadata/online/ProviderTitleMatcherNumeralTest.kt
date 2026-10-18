package com.gruning.mimir.rewrite.core.metadata.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-11, reference Nova: `title:pc_games:blasphemous 2` reached SteamGridDB by name (plan=NameSearch) and
 * still ended NoMatch because the provider spells the sequel `Blasphemous II`. Roman and Arabic sequel
 * numerals are one identity here — but only the unambiguous ones.
 */
class ProviderTitleMatcherNumeralTest {
    @Test
    fun `roman and arabic sequel numerals match both ways`() {
        assertTrue(ProviderTitleMatcher.matches("Blasphemous 2", "Blasphemous II"))
        assertTrue(ProviderTitleMatcher.matches("Blasphemous II", "Blasphemous 2"))
        assertTrue(ProviderTitleMatcher.matches("Diablo IV", "Diablo 4"))
        assertTrue(ProviderTitleMatcher.matches("Final Fantasy VII", "Final Fantasy 7"))
        assertTrue(ProviderTitleMatcher.matches("Street Fighter III", "Street Fighter 3"))
    }

    @Test
    fun `a different sequel is still a different game`() {
        assertFalse(ProviderTitleMatcher.matches("Blasphemous 2", "Blasphemous"))
        assertFalse(ProviderTitleMatcher.matches("Blasphemous", "Blasphemous II"))
        assertFalse(ProviderTitleMatcher.matches("Blasphemous 2", "Blasphemous III"))
    }

    @Test
    fun `ambiguous single letters are never folded`() {
        // X is a letter in "Mega Man X" and the tenth game is "Mega Man 10": they must stay distinct.
        assertFalse(ProviderTitleMatcher.matches("Mega Man X", "Mega Man 10"))
        // A lone I or V is a word or an initial far more often than a sequel numeral.
        assertFalse(ProviderTitleMatcher.matches("I Am Alive", "1 Am Alive"))
        assertFalse(ProviderTitleMatcher.matches("GTA V", "GTA 5"))
    }
}
