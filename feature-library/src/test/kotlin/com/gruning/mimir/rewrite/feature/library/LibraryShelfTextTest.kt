package com.gruning.mimir.rewrite.feature.library

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Library shelf's own words (localization step 3): its title, the platform eyebrow, the mode tabs, the Add pill, the empty shelf, the
 * platform counts and the controller legend. English is the default folder and reads as it always did, in sentence case (policy
 * O3); Spanish is Latin American (`es-419`), with the glossary's Biblioteca and Agregar. Every controller token stays as it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibraryShelfTextTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val english: Resources = context.resources
    private val spanish: Resources = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("es-419")) },
    ).resources

    @Test
    fun `the title, the eyebrow and the mode tabs read in both languages`() {
        assertEquals("Library", english.getString(R.string.library_shelf_title))
        assertEquals("Choose a platform", english.getString(LibraryShelfMode.EMULATION.sectionLabelRes))
        assertEquals("Emulation", english.getString(LibraryShelfMode.EMULATION.labelRes))
        assertEquals("Android games", english.getString(LibraryShelfMode.ANDROID_GAMES.labelRes))
        assertEquals("Biblioteca", spanish.getString(R.string.library_shelf_title))
        assertEquals("Elige una plataforma", spanish.getString(LibraryShelfMode.EMULATION.sectionLabelRes))
        assertEquals("Emulación", spanish.getString(LibraryShelfMode.EMULATION.labelRes))
        assertEquals("Juegos de Android", spanish.getString(LibraryShelfMode.ANDROID_GAMES.labelRes))
        assertEquals("Juegos de Android", spanish.getString(LibraryShelfMode.ANDROID_GAMES.sectionLabelRes))
    }

    @Test
    fun `the mode strip still reports a tab by its identity, not by the words it draws`() {
        LibraryShelfMode.entries.forEach { mode ->
            assertEquals(mode, LibraryShelfMode.fromStripLabel(mode.stripLabel))
            // The identity is never the drawn word: a language change must not change what a tap reports.
            assertNotEquals(spanish.getString(mode.labelRes), mode.stripLabel)
        }
    }

    @Test
    fun `the Add pill and the empty shelf read in both languages`() {
        assertEquals("Add games", english.getString(R.string.library_shelf_add_games))
        assertEquals("Add game", english.getString(R.string.library_shelf_add_game))
        assertEquals("No platforms yet.", english.getString(R.string.library_shelf_empty))
        assertEquals("Agregar juegos", spanish.getString(R.string.library_shelf_add_games))
        assertEquals("Agregar juego", spanish.getString(R.string.library_shelf_add_game))
        assertEquals("Aún no hay plataformas.", spanish.getString(R.string.library_shelf_empty))
        // The English the picker still carries (a later step) is the same sentence as the pill's.
        assertEquals(english.getString(R.string.library_shelf_add_game), LibraryShelfMode.ANDROID_GAMES.nativeAddOwnerCopy().addLabel)
        assertEquals(english.getString(R.string.library_shelf_add_games), LibraryShelfMode.EMULATION.nativeAddOwnerCopy().addLabel)
    }

    @Test
    fun `a platform's game count is a plural in both languages`() {
        assertEquals("1 game", english.getQuantityString(R.plurals.library_shelf_games_count, 1, 1))
        assertEquals("12 games", english.getQuantityString(R.plurals.library_shelf_games_count, 12, 12))
        assertEquals("1 juego", spanish.getQuantityString(R.plurals.library_shelf_games_count, 1, 1))
        assertEquals("12 juegos", spanish.getQuantityString(R.plurals.library_shelf_games_count, 12, 12))
        assertEquals("1000000 juegos", spanish.getQuantityString(R.plurals.library_shelf_games_count, 1_000_000, 1_000_000))
    }

    @Test
    fun `the shelf legend keeps every token and translates the word beside it`() {
        assertEquals(
            listOf("◂ ▸" to "Plataformas", "L/R" to "Modo", "A" to "Abrir", "X" to "Favoritos", "START" to "Configuración"),
            shelfFooterHints(spanish, nativeMode = false),
        )
        assertEquals(
            listOf("◂ ▸" to "Apps", "L/R" to "Modo", "A" to "Abrir", "SELECT" to "Favorito", "Y" to "Medios", "START" to "Ocultar"),
            shelfFooterHints(spanish, nativeMode = true),
        )
        // The tokens are the same in both languages; only the words differ.
        listOf(false, true).forEach { native ->
            assertEquals(
                shelfFooterHints(english, native).map { it.first },
                shelfFooterHints(spanish, native).map { it.first },
            )
        }
    }

    @Test
    fun `a game list's legend keeps its tokens and its conditional hints in Spanish`() {
        val browsing = gamelistFooterHints(spanish, inFavorites = false, favoritesOnly = false, canSwitchFavoriteSegments = false)
        assertEquals(
            listOf(
                "A" to "Abrir", "B" to "Atrás", "◂▸" to "Juegos", "L/R" to "Plataformas", "SELECT" to "Favorito",
                "X" to "Mostrar favoritos", "Y" to "Medios", "START" to "Ocultar",
            ),
            browsing,
        )
        assertTrue(gamelistFooterHints(spanish, inFavorites = false, favoritesOnly = true, canSwitchFavoriteSegments = false).contains("X" to "Mostrar todo"))
        assertTrue(gamelistFooterHints(spanish, inFavorites = true, favoritesOnly = false, canSwitchFavoriteSegments = true).contains("L/R" to "Categoría"))
        assertFalse(gamelistFooterHints(spanish, inFavorites = true, favoritesOnly = false, canSwitchFavoriteSegments = true).any { it.first == "X" })
    }

    @Test
    fun `no legend word is stored in caps, so the shared seam alone decides what is drawn`() {
        listOf(english, spanish).forEach { res ->
            val words = shelfFooterHints(res, false) + shelfFooterHints(res, true) +
                gamelistFooterHints(res, inFavorites = false, favoritesOnly = false, canSwitchFavoriteSegments = true) +
                gamelistFooterHints(res, inFavorites = true, favoritesOnly = true, canSwitchFavoriteSegments = true)
            words.forEach { (_, word) -> assertTrue(word, word.isNotBlank() && word != word.uppercase()) }
        }
    }

    @Test
    fun `the hint rail button and the view toggle read in both languages`() {
        assertEquals("Hints", english.getString(R.string.library_hint_rail_caption))
        assertEquals("Controller hints", english.getString(R.string.library_hint_rail_description))
        assertEquals("Ayudas", spanish.getString(R.string.library_hint_rail_caption))
        assertEquals("Ayudas del control", spanish.getString(R.string.library_hint_rail_description))
        assertEquals("L/R · ᛚ List", gamelistViewModeHintLabel(english, GamelistViewMode.CAROUSEL))
        assertEquals("L/R · ᚲ Shelf", gamelistViewModeHintLabel(english, GamelistViewMode.LIST))
        assertEquals("L/R · ᛚ Lista", gamelistViewModeHintLabel(spanish, GamelistViewMode.CAROUSEL))
        assertEquals("L/R · ᚲ Estante", gamelistViewModeHintLabel(spanish, GamelistViewMode.LIST))
    }
}
