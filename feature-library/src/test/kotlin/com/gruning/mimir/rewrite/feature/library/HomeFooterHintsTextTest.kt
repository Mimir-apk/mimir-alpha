package com.gruning.mimir.rewrite.feature.library

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Home's footer hints (localization step 2): the pill that follows the focused tile, the permanent X shortcut, the launch card's Back
 * and the Tome folio's legend. English is the default folder and reads as it always did; Spanish keeps every controller token (A, B,
 * X, Y, L/R, D-PAD) and translates the verb beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeFooterHintsTextTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val english: Resources = context.resources
    private val spanish: Resources = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("es-419")) },
    ).resources

    private fun hintWords(res: Resources, kind: HomeTileHintKind, modeSelect: Boolean): List<Pair<String, String>> =
        homeTileFocusHintActions(kind, modeSelect).map { it.glyph to res.getString(it.labelRes) }

    @Test
    fun `the focused tile pill reads Edit, Open and Mode in English`() {
        assertEquals(listOf("Y" to "Edit", "L/R" to "Mode"), hintWords(english, HomeTileHintKind.GAME, modeSelect = true))
        assertEquals(listOf("A" to "Open", "L/R" to "Mode"), hintWords(english, HomeTileHintKind.TOME, modeSelect = true))
        assertEquals(listOf("Y" to "Edit"), hintWords(english, HomeTileHintKind.GAME, modeSelect = false))
    }

    @Test
    fun `the focused tile pill keeps its tokens and speaks Spanish`() {
        assertEquals(listOf("Y" to "Editar", "L/R" to "Modo"), hintWords(spanish, HomeTileHintKind.GAME, modeSelect = true))
        assertEquals(listOf("A" to "Abrir", "L/R" to "Modo"), hintWords(spanish, HomeTileHintKind.TOME, modeSelect = true))
    }

    @Test
    fun `the X shortcut and the launch card Back read in both languages`() {
        assertEquals("Tome", english.getString(R.string.library_home_hint_tome))
        assertEquals("X, open Tome Library", english.getString(R.string.library_home_hint_tome_shortcut_description))
        assertEquals("B Back", english.getString(R.string.library_home_launch_back_hint))
        assertEquals("Tomo", spanish.getString(R.string.library_home_hint_tome))
        assertEquals("X, abrir la Biblioteca de Tomos", spanish.getString(R.string.library_home_hint_tome_shortcut_description))
        assertEquals("B Atrás", spanish.getString(R.string.library_home_launch_back_hint))
    }

    @Test
    fun `the folio legends keep their tokens in caps and their words in sentence case in English`() {
        assertEquals("B Back", homeTomeFolioFooter(english, HomeTomeFolioMode.BROWSE, carrying = false, itemCount = 0))
        assertEquals("A Open · X Arrange · Y Edit · B Back", homeTomeFolioFooter(english, HomeTomeFolioMode.BROWSE, carrying = false))
        assertEquals("A Pick up · X Exit · B Cancel", homeTomeFolioFooter(english, HomeTomeFolioMode.ARRANGE, carrying = false))
        assertEquals("A Drop · D-PAD Move · B Cancel", homeTomeFolioFooter(english, HomeTomeFolioMode.ARRANGE, carrying = true))
        // X EXIT never shows while a tile is carried: the carry owns A and B only.
        assertFalse(homeTomeFolioFooter(english, HomeTomeFolioMode.ARRANGE, carrying = true).contains("X Exit"))
    }

    @Test
    fun `the folio legends keep their tokens in Spanish and their words in sentence case`() {
        assertEquals("B Atrás", homeTomeFolioFooter(spanish, HomeTomeFolioMode.BROWSE, carrying = false, itemCount = 0))
        assertEquals("A Abrir · X Ordenar · Y Editar · B Atrás", homeTomeFolioFooter(spanish, HomeTomeFolioMode.BROWSE, carrying = false))
        assertEquals("A Tomar · X Salir · B Cancelar", homeTomeFolioFooter(spanish, HomeTomeFolioMode.ARRANGE, carrying = false))
        val carrying = homeTomeFolioFooter(spanish, HomeTomeFolioMode.ARRANGE, carrying = true)
        assertEquals("A Soltar · D-PAD Mover · B Cancelar", carrying)
        assertFalse(carrying.contains("X Salir"))
        listOf(HomeTomeFolioMode.BROWSE, HomeTomeFolioMode.ARRANGE).forEach { mode ->
            val line = homeTomeFolioFooter(spanish, mode, carrying = false)
            assertTrue(line, line != line.uppercase())
        }
    }
}
