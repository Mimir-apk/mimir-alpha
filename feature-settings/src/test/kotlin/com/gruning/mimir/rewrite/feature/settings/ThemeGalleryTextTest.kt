package com.gruning.mimir.rewrite.feature.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The theme gallery's own words (localization step 1: the THEME step of first-run setup draws this shared picker): the controller
 * footer and the nine mood tags. English is the default folder; Spanish keeps the controller token and translates the verb.
 * Both are stored as O3 (2026-10-17) has it: the footer is token + capitalized verb, the moods are sentence case, and nothing here
 * changes case in code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ThemeGalleryTextTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val english: Resources = context.resources
    private val spanish: Resources = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("es-419")) },
    ).resources

    @Test
    fun `the footer teaches A and B in English and keeps the dismiss word as given`() {
        assertEquals("A Select · B Back", themeGalleryControllerHint(english, "Back"))
        assertEquals("A Select · B Keep current", themeGalleryControllerHint(english, "Keep current"))
    }

    @Test
    fun `the Spanish footer keeps the controller tokens and translates the verb`() {
        assertEquals("A Elegir · B Atrás", themeGalleryControllerHint(spanish, "Atrás"))
        assertEquals("A Elegir · B Mantener actual", themeGalleryControllerHint(spanish, "Mantener actual"))
    }

    @Test
    fun `the English mood tags are the ones the cards always carried and stay compact`() {
        val expected = mapOf(
            MimirThemeId.DEFAULT to "Well · gold",
            MimirThemeId.FREYR to "Hall · gold",
            MimirThemeId.SURTR to "Ember · iron",
            MimirThemeId.MANI to "Moon · star",
            MimirThemeId.HEL to "Dread · blood",
            MimirThemeId.NIFLHEIM to "Ice · mist",
            MimirThemeId.YGGDRASIL to "Root · bronze",
            MimirThemeId.FOLKVANGR to "Ash · bone",
            MimirThemeId.BALDR to "Sky · sun",
        )
        MimirThemeId.entries.forEach { id ->
            assertEquals(expected.getValue(id), themeMood(english, id))
            assertTrue(themeMood(english, id).length <= 13)
        }
    }

    @Test
    fun `every theme has a Spanish mood tag of two words in sentence case`() {
        MimirThemeId.entries.forEach { id ->
            val mood = themeMood(spanish, id)
            assertTrue("$id: $mood", mood != mood.uppercase() && mood.first().isUpperCase() && mood.contains(" · "))
            assertTrue("$id has no Spanish tag", mood != themeMood(english, id))
        }
    }
}
