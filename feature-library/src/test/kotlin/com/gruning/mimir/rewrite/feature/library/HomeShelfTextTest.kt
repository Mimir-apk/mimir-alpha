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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import java.util.UUID
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
 * The Home shelf's own words (localization step 2): its title, its empty state, a Tome's count and spoken name, the arrows that say more
 * items wait off screen, and the Tome folio's pills. English is the default folder and reads as it always did; Spanish is Latin American
 * (`es-419`), with the glossary's Tomo, Estante, Inicio and Biblioteca. Counts are plurals, so one is "1 game" and the rest "N games".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeShelfTextTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val english: Resources = context.resources
    private val spanish: Resources = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("es-419")) },
    ).resources

    private val platform = TomeId.Platform(com.gruning.mimir.rewrite.core.model.PlatformId("snes"))
    private val custom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000042"))

    @Test
    fun `a Tome's count reads one game, N games, or Empty in English`() {
        assertEquals("1 game", homeTomeMemberCountLabel(english, platform, 1))
        assertEquals("4 games", homeTomeMemberCountLabel(english, platform, 4))
        assertEquals("1 game", homeTomeMemberCountLabel(english, custom, 1))
        assertEquals("Empty", homeTomeMemberCountLabel(english, custom, 0))
    }

    @Test
    fun `a Tome's count reads in Spanish with the right plural`() {
        assertEquals("1 juego", homeTomeMemberCountLabel(spanish, platform, 1))
        assertEquals("4 juegos", homeTomeMemberCountLabel(spanish, platform, 4))
        assertEquals("Vacío", homeTomeMemberCountLabel(spanish, custom, 0))
    }

    @Test
    fun `a Tome seat's spoken name carries its title, the word Tome and its count`() {
        assertEquals("RPGs, Tome, 1 game", english.getString(R.string.library_home_tome_description, "RPGs", homeTomeMemberCountLabel(english, custom, 1)))
        assertEquals("RPGs, Tomo, 3 juegos", spanish.getString(R.string.library_home_tome_description, "RPGs", spanish.getQuantityString(R.plurals.library_home_games_count, 3, 3)))
        // Zero games is a count, not "Empty": the seat's name has always said "0 games".
        assertEquals("0 games", english.getQuantityString(R.plurals.library_home_games_count, 0, 0))
        assertEquals("0 juegos", spanish.getQuantityString(R.plurals.library_home_games_count, 0, 0))
    }

    @Test
    fun `the Home title and empty state read in both languages`() {
        assertEquals("Home shelf", english.getString(R.string.library_home_title))
        assertEquals("Editor’s choice is empty", english.getString(R.string.library_home_empty_title))
        assertEquals("Choose games in Library to keep them here.", english.getString(R.string.library_home_empty_body))
        assertEquals("Open library", english.getString(R.string.library_home_empty_action))
        assertEquals("Open library", english.getString(R.string.library_home_empty_action_description))
        assertEquals("Estante de Inicio", spanish.getString(R.string.library_home_title))
        assertEquals("Selección del editor vacía", spanish.getString(R.string.library_home_empty_title))
        assertEquals("Elige juegos en Biblioteca para tenerlos aquí.", spanish.getString(R.string.library_home_empty_body))
        assertEquals("Abrir biblioteca", spanish.getString(R.string.library_home_empty_action))
    }

    @Test
    fun `the off-screen arrows and the folio pills read in both languages`() {
        assertEquals("More items to the left", english.getString(R.string.library_home_more_items_left))
        assertEquals("More items to the right", english.getString(R.string.library_home_more_items_right))
        assertEquals("Hay más elementos a la izquierda", spanish.getString(R.string.library_home_more_items_left))
        assertEquals("Hay más elementos a la derecha", spanish.getString(R.string.library_home_more_items_right))
        assertEquals("Arrange", english.getString(R.string.library_home_folio_arrange_pill))
        assertEquals("Ordenar", spanish.getString(R.string.library_home_folio_arrange_pill))
        assertEquals("Empty Tome seat 3", english.getString(R.string.library_home_folio_empty_seat, 3))
        assertEquals("Espacio vacío 3 del Tomo", spanish.getString(R.string.library_home_folio_empty_seat, 3))
        assertEquals("This Tome is empty", english.getString(R.string.library_home_folio_empty_title))
        assertEquals("Add games from Home › Edit", english.getString(R.string.library_home_folio_empty_hint))
        assertEquals("Este Tomo está vacío", spanish.getString(R.string.library_home_folio_empty_title))
        assertEquals("Agrega juegos desde Inicio › Editar", spanish.getString(R.string.library_home_folio_empty_hint))
    }

    @Test
    fun `the arrange pill's Spanish word fits the pill's English width`() {
        // The pill is a fixed 72 dp: the Spanish word must not be longer than the English one it replaces.
        assertTrue(spanish.getString(R.string.library_home_folio_arrange_pill).length <= english.getString(R.string.library_home_folio_arrange_pill).length)
        assertFalse(english.getString(R.string.library_home_folio_arrange_pill) == spanish.getString(R.string.library_home_folio_arrange_pill))
    }
}

/**
 * The empty Home shelf on a device set to Español: the real [HomeShelfEmptyAction] and its two lines. Not a layout check; fitting the
 * longer words on the Thor and the Odin is a device visual check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "b+es+419-w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShelfSpanishRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Chrome text is a Text (caps while the shared seam still uppercases it) over a natural-case description, in the unmerged tree: either one finds it, case-blind. */
    private fun shows(text: String): Boolean = compose.onAllNodes(
        hasText(text, ignoreCase = true) or hasContentDescription(text, ignoreCase = true),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the empty shelf action and its lines read in Spanish`() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(853f, 480f),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(853.dp, 480.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        HomeShelfEmptyAction(
                            authority = DefaultFocusAuthority(),
                            focused = false,
                            geometry = solveHomeShelfGeometry(853f, 480f),
                            compactViewport = false,
                            onOpenLibrary = {},
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        assertTrue(shows("Selección del editor vacía"))
        assertTrue(shows("Elige juegos en Biblioteca para tenerlos aquí."))
        // The chip's word is chrome: its text may be drawn in caps over the natural-case description, so either finds it, case-blind.
        assertTrue(shows("Abrir biblioteca"))
        assertFalse("the English line must not leak through", shows("Editor’s choice is empty") || shows("Open library"))
    }
}
