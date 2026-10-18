package com.gruning.mimir.rewrite.feature.library

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Library's Search overlay words (localization step 3): its title, its result count, the three fields and what they hold before
 * anything is chosen, the filter pickers' titles, the hints and the empty states. English is the default folder, in sentence case; Spanish is Latin American (`es-419`). The controller tokens in the hints are never translated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibrarySearchTextTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val english: Resources = context.resources
    private val spanish: Resources = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("es-419")) },
    ).resources

    @Test
    fun `the overlay's title, fields and pickers read in both languages`() {
        assertEquals("ᛋ · Search library", english.getString(R.string.library_search_title))
        assertEquals("ᛋ · Buscar en la biblioteca", spanish.getString(R.string.library_search_title))
        assertEquals("Query", english.getString(R.string.library_search_field_query))
        assertEquals("Genre", english.getString(R.string.library_search_field_genre))
        assertEquals("Platform", english.getString(R.string.library_search_field_platform))
        assertEquals("Búsqueda", spanish.getString(R.string.library_search_field_query))
        assertEquals("Género", spanish.getString(R.string.library_search_field_genre))
        assertEquals("Plataforma", spanish.getString(R.string.library_search_field_platform))
        assertEquals("Title or name", english.getString(R.string.library_search_query_placeholder))
        assertEquals("All genres", english.getString(R.string.library_search_all_genres))
        assertEquals("All platforms", english.getString(R.string.library_search_all_platforms))
        assertEquals("Todos los géneros", spanish.getString(R.string.library_search_all_genres))
        assertEquals("Todas las plataformas", spanish.getString(R.string.library_search_all_platforms))
        assertEquals("Select genre", english.getString(R.string.library_search_picker_genre))
        assertEquals("Elegir plataforma", spanish.getString(R.string.library_search_picker_platform))
        assertEquals("Clear filters", english.getString(R.string.library_search_clear_label))
        assertEquals("Borrar filtros", spanish.getString(R.string.library_search_clear_label))
    }

    @Test
    fun `the result count is a plural in both languages`() {
        assertEquals("0 results", english.getQuantityString(R.plurals.library_search_results, 0, 0))
        assertEquals("1 result", english.getQuantityString(R.plurals.library_search_results, 1, 1))
        assertEquals("12 results", english.getQuantityString(R.plurals.library_search_results, 12, 12))
        assertEquals("0 resultados", spanish.getQuantityString(R.plurals.library_search_results, 0, 0))
        assertEquals("1 resultado", spanish.getQuantityString(R.plurals.library_search_results, 1, 1))
        assertEquals("12 resultados", spanish.getQuantityString(R.plurals.library_search_results, 12, 12))
    }

    @Test
    fun `the hints keep their controller tokens and capitalise the word beside each`() {
        assertEquals("B Back", english.getString(R.string.library_search_hint_back))
        assertEquals("A Open", english.getString(R.string.library_search_hint_open))
        assertEquals("A Select · B Back", english.getString(R.string.library_search_hint_picker))
        assertEquals("B Atrás", spanish.getString(R.string.library_search_hint_back))
        assertEquals("A Abrir", spanish.getString(R.string.library_search_hint_open))
        assertEquals("A Elegir · B Atrás", spanish.getString(R.string.library_search_hint_picker))
    }

    @Test
    fun `the empty states read in both languages`() {
        assertEquals("No matches", english.getString(R.string.library_search_empty_matches_title))
        assertEquals("Change a filter or clear the search.", english.getString(R.string.library_search_empty_matches_body))
        assertEquals("No games to search", english.getString(R.string.library_search_empty_library_title))
        assertEquals("Sin coincidencias", spanish.getString(R.string.library_search_empty_matches_title))
        assertEquals("Cambia un filtro o borra la búsqueda.", spanish.getString(R.string.library_search_empty_matches_body))
        assertEquals("No hay juegos para buscar", spanish.getString(R.string.library_search_empty_library_title))
        assertTrue(spanish.getString(R.string.library_search_empty_library_body).isNotBlank())
    }
}

/**
 * The Search overlay on a device set to Español: the real [LibrarySearchOverlay] with nothing searched and no games. Not a layout check;
 * fitting the longer words on the Thor and the Odin is a device visual check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "b+es+419-w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibrarySearchSpanishRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Chrome text is a Text (caps while the shared seam still uppercases it) over a natural-case description, in the unmerged tree: either one finds it, case-blind. */
    private fun shows(text: String): Boolean = compose.onAllNodes(
        hasText(text, ignoreCase = true) or hasContentDescription(text, ignoreCase = true),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the overlay with nothing to search reads in Spanish`() {
        val snes = PlatformId("snes")
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(853f, 480f),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(853.dp, 480.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        LibrarySearchOverlay(
                            session = LibrarySearchSession(
                                origin = LibrarySearchOrigin.PlatformGames(snes),
                                originFocus = LibrarySearchOriginFocusIdentity.SearchLauncher,
                                criteria = LibrarySearchCriteria(),
                            ),
                            criteria = LibrarySearchCriteria(),
                            facets = LibrarySearchFacets.Empty,
                            games = emptyList(),
                            platforms = emptyList(),
                            picker = null,
                            authority = DefaultFocusAuthority(),
                            focused = null,
                            onOpenKeyboard = {},
                            onOpenPicker = {},
                            onSelectGenre = {},
                            onSelectPlatform = {},
                            onClear = {},
                            onActivateGame = {},
                            onDismissPicker = {},
                            drawsPickerLayer = false,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        assertTrue(shows("ᛋ · Buscar en la biblioteca"))
        assertTrue(shows("0 resultados"))
        assertTrue(shows("Título o nombre"))
        assertTrue(shows("Todos los géneros"))
        assertTrue(shows("No hay juegos para buscar"))
        assertFalse("the English line must not leak through", shows("No games to search") || shows("All genres") || shows("Title or name"))
    }
}
