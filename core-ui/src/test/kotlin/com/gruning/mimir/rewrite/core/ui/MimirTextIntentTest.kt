package com.gruning.mimir.rewrite.core.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MimirTextIntentTest {
    @Test
    fun `static chrome caps visually and preserves natural speech`() {
        val resolved = MimirTextIntent.StaticChrome("Open with").resolve()

        assertEquals("OPEN WITH", resolved.visualText)
        assertEquals("Open with", resolved.spokenText)
    }

    @Test
    fun `authored and body content never change casing`() {
        assertEquals("Pokémon Emerald", MimirTextIntent.Authored("Pokémon Emerald").resolve().visualText)
        assertEquals(
            "Could not reach ScreenScraper.",
            MimirTextIntent.Body("Could not reach ScreenScraper.").resolve().visualText,
        )
    }

    @Test
    fun `mixed copy caps controlled fragments only`() {
        val resolved = MimirTextIntent.Mixed(
            listOf(
                MimirTextFragment.Chrome("Searching "),
                MimirTextFragment.Authored("ScreenScraper"),
                MimirTextFragment.Chrome("… · 12 found"),
            ),
        ).resolve()

        assertEquals("SEARCHING ScreenScraper… · 12 FOUND", resolved.visualText)
        assertEquals("Searching ScreenScraper… · 12 found", resolved.spokenText)
    }

    @Test
    fun `visual override preserves branded and runic composition`() {
        val resolved = MimirTextIntent.StaticChrome(
            spokenText = "Customize media",
            visualOverride = "ᛗ · CUSTOMIZE MEDIA",
        ).resolve()

        assertEquals("ᛗ · CUSTOMIZE MEDIA", resolved.visualText)
        assertEquals("Customize media", resolved.spokenText)
    }

    @Test
    fun `static projection is locale root stable`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("IN USE", mimirStaticChrome("in use"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
