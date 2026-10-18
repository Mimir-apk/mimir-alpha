package com.gruning.mimir.rewrite.core.metadata.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure rules the PC logo lane (`ScreenScraperMetadataSource.pcLogoUrl`) adds: which system NAMES it accepts, and how strictly it reads a title. The same rules through the whole lane,
 * with fake responses, are `ScreenScraperPcLogoTest`.
 */
class ScreenScraperPcLogoStrictTest {

    @Test
    fun `a Windows or DOS system name is a PC system, a word at a time and in any case`() {
        assertEquals(
            listOf(true, true, true, true, false, false, false),
            listOf("windows 3.x", "MS-DOS", "PC Windows", "PC Dos", "Dossier", "Windowsill", null)
                .map(ScreenScraperMetadataSource::isPcSystemName),
        )
    }

    @Test
    fun `the PC lane keeps the year and the parenthesised words that the shared matcher drops`() {
        assertTrue(
            "the shared matcher still reads the two Dooms as one title: the PC logo lane alone is stricter",
            ProviderTitleMatcher.matches("Doom (1993)", "Doom (2016)"),
        )
        assertTrue("and keeps its numeral folding", ProviderTitleMatcher.matches("Blasphemous 2", "Blasphemous II"))
        assertTrue(ScreenScraperMetadataSource.pcLogoTitleMatches("Blasphemous 2", "Blasphemous II"))
        assertTrue(ScreenScraperMetadataSource.pcLogoTitleMatches("Doom (2016)", "DOOM (2016)"))
        assertFalse(ScreenScraperMetadataSource.pcLogoTitleMatches("Doom (1993)", "Doom (2016)"))
        assertFalse("a year in one title only", ScreenScraperMetadataSource.pcLogoTitleMatches("Doom", "Doom (2016)"))
        assertFalse(ScreenScraperMetadataSource.pcLogoTitleMatches("Doom (Classic)", "Doom (Eternal)"))
        assertFalse("no edition suffix is dropped", ScreenScraperMetadataSource.pcLogoTitleMatches("Resident Evil 2 - DualShock", "Resident Evil 2"))
        assertFalse("an empty title matches nothing", ScreenScraperMetadataSource.pcLogoTitleMatches("", ""))
    }
}
