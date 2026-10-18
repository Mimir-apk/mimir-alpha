package com.gruning.mimir.rewrite.core.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The language choice (Settings > Appearance > Language): which locale each choice stands for,
 * the stored keys, and the row's cycle order. Automatic is "no override" and must never name a locale.
 */
class MimirLanguageTest {

    @Test
    fun `automatic names no locale so the device language wins`() {
        assertNull(MimirLanguage.AUTOMATIC.languageTag)
        assertNull(MimirLanguage.AUTOMATIC.locale)
        assertNull(MimirLanguage.AUTOMATIC.nativeName)
    }

    @Test
    fun `english and latin american spanish name their locales`() {
        assertEquals(Locale.forLanguageTag("en"), MimirLanguage.ENGLISH.locale)
        val spanish = MimirLanguage.SPANISH_LATAM.locale
        assertNotNull(spanish)
        assertEquals("es", spanish!!.language)
        assertEquals("419", spanish.country)
        assertEquals("es-419", spanish.toLanguageTag())
    }

    @Test
    fun `each language is shown in its own language`() {
        assertEquals("English", MimirLanguage.ENGLISH.nativeName)
        assertEquals("Español (Latinoamérica)", MimirLanguage.SPANISH_LATAM.nativeName)
    }

    @Test
    fun `stored keys are pinned and round trip`() {
        assertEquals("automatic", MimirLanguage.AUTOMATIC.storageKey)
        assertEquals("en", MimirLanguage.ENGLISH.storageKey)
        assertEquals("es-419", MimirLanguage.SPANISH_LATAM.storageKey)
        MimirLanguage.entries.forEach { language ->
            assertSame(language, MimirLanguage.fromStorageKey(language.storageKey))
        }
    }

    @Test
    fun `an absent or unknown stored key is automatic`() {
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguage.fromStorageKey(null))
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguage.fromStorageKey(""))
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguage.fromStorageKey("fr"))
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguage.fromStorageKey("SPANISH_LATAM"))
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguage.DEFAULT)
    }

    @Test
    fun `the row cycles automatic then english then spanish then back`() {
        assertSame(MimirLanguage.ENGLISH, MimirLanguage.AUTOMATIC.next())
        assertSame(MimirLanguage.SPANISH_LATAM, MimirLanguage.ENGLISH.next())
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguage.SPANISH_LATAM.next())
    }

    @Test
    fun `reading a platform locale back maps to the nearest choice`() {
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguage.fromLocale(null))
        assertSame(MimirLanguage.ENGLISH, MimirLanguage.fromLocale(Locale.forLanguageTag("en")))
        assertSame(MimirLanguage.ENGLISH, MimirLanguage.fromLocale(Locale.forLanguageTag("en-GB")))
        assertSame(MimirLanguage.SPANISH_LATAM, MimirLanguage.fromLocale(Locale.forLanguageTag("es-419")))
        assertSame(MimirLanguage.SPANISH_LATAM, MimirLanguage.fromLocale(Locale.forLanguageTag("es-MX")))
        assertSame(MimirLanguage.SPANISH_LATAM, MimirLanguage.fromLocale(Locale.forLanguageTag("es")))
        assertSame(MimirLanguage.AUTOMATIC, MimirLanguage.fromLocale(Locale.forLanguageTag("fr")))
    }

    @Test
    fun `every choice survives a locale round trip`() {
        MimirLanguage.entries.forEach { language ->
            assertSame(language, MimirLanguage.fromLocale(language.locale))
        }
    }
}
