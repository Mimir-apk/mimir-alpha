package com.gruning.mimir.rewrite.feature.library

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A Library game list's own words (localization step 3): the header's title and count line, the Search labels, the Favorites tabs, the
 * empty states and the empty Library. English is the default folder and reads as it always did, in sentence case;
 * Spanish is Latin American (`es-419`). Counts are plurals, and a sentence around the player's own words is cut at its slot, never
 * built from pieces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibraryGamelistTextTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val english: Resources = context.resources
    private val spanish: Resources = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("es-419")) },
    ).resources

    private fun meta(res: Resources, count: Int, kind: GamelistItemKind = GamelistItemKind.GAME, favorites: Boolean = false, query: String = "", settled: Boolean = true) =
        gamelistHeaderMeta(res, searchQuery = query, favoritesOnly = favorites, count = count, kind = kind, countSettled = settled)

    @Test
    fun `the count line is none, one or N in English for games and apps`() {
        assertEquals("No games", meta(english, 0))
        assertEquals("1 game", meta(english, 1))
        assertEquals("17 games", meta(english, 17))
        assertEquals("No apps", meta(english, 0, GamelistItemKind.APP))
        assertEquals("1 app", meta(english, 1, GamelistItemKind.APP))
        assertEquals("3 apps", meta(english, 3, GamelistItemKind.APP))
    }

    @Test
    fun `the count line reads in Spanish with the right plural`() {
        assertEquals("Sin juegos", meta(spanish, 0))
        assertEquals("1 juego", meta(spanish, 1))
        assertEquals("17 juegos", meta(spanish, 17))
        assertEquals("Sin apps", meta(spanish, 0, GamelistItemKind.APP))
        assertEquals("1 app", meta(spanish, 1, GamelistItemKind.APP))
        assertEquals("3 apps", meta(spanish, 3, GamelistItemKind.APP))
    }

    @Test
    fun `the line sets the query, Favorites, the filters and the count apart by a middle dot`() {
        assertEquals("“M” · Favorites · Platformer · 1 game", gamelistHeaderMeta(english, "M", true, 1, GamelistItemKind.GAME, filterLabels = listOf("Platformer")))
        assertEquals("“M” · Favoritos · Platformer · 1 juego", gamelistHeaderMeta(spanish, "M", true, 1, GamelistItemKind.GAME, filterLabels = listOf("Platformer")))
        // While a platform switch settles the count is left out, in either language, rather than saying the platform is empty.
        assertEquals("Favorites", meta(english, 0, favorites = true, settled = false))
        assertEquals("Favoritos", meta(spanish, 0, favorites = true, settled = false))
    }

    @Test
    fun `a list's title is Favorites, the platform's own name, or Games`() {
        assertEquals("Favorites", platformLabel(english, emptyList(), LibraryViewModel.FAVORITES_PLATFORM_ID))
        assertEquals("Favoritos", platformLabel(spanish, emptyList(), LibraryViewModel.FAVORITES_PLATFORM_ID))
        assertEquals("Games", platformLabel(english, emptyList(), null))
        assertEquals("Juegos", platformLabel(spanish, emptyList(), null))
    }

    @Test
    fun `the Search labels and the Favorites only chip read in both languages`() {
        assertEquals("Search games", english.getString(R.string.library_gamelist_search_games))
        assertEquals("Search apps", english.getString(R.string.library_gamelist_search_apps))
        assertEquals("Search favorites", english.getString(R.string.library_gamelist_search_favorites))
        assertEquals("Buscar juegos", spanish.getString(R.string.library_gamelist_search_games))
        assertEquals("Buscar apps", spanish.getString(R.string.library_gamelist_search_apps))
        assertEquals("Buscar favoritos", spanish.getString(R.string.library_gamelist_search_favorites))
        assertEquals("ᛗ · Favorites only", english.getString(R.string.library_gamelist_favorites_only_chip))
        assertEquals("ᛗ · Solo favoritos", spanish.getString(R.string.library_gamelist_favorites_only_chip))
        assertEquals("Local library", english.getString(R.string.library_gamelist_local_library))
        assertEquals("Biblioteca local", spanish.getString(R.string.library_gamelist_local_library))
    }

    @Test
    fun `the Favorites tabs name their own rows in both languages`() {
        assertEquals(listOf("Games", "Android games", "Android apps"), FavoriteSegment.entries.map { english.getString(it.labelRes) })
        assertEquals(listOf("Juegos", "Juegos de Android", "Apps de Android"), FavoriteSegment.entries.map { spanish.getString(it.labelRes) })
    }

    @Test
    fun `a sentence around the player's own words is cut at its slot and keeps the words as typed`() {
        val en = libraryAroundAuthored(english, R.string.library_gamelist_empty_search, "Zelda")
        assertEquals(
            MimirTextIntent.Mixed(
                listOf(
                    MimirTextFragment.Chrome("No games match “"),
                    MimirTextFragment.Authored("Zelda"),
                    MimirTextFragment.Chrome("”."),
                ),
            ),
            en,
        )
        assertEquals("No games match “Zelda”.", en.spokenText)
        val es = libraryAroundAuthored(spanish, R.string.library_gamelist_empty_search, "Zelda")
        assertEquals("Ningún juego coincide con “Zelda”.", es.spokenText)
        assertEquals(MimirTextFragment.Authored("Zelda"), (es as MimirTextIntent.Mixed).fragments[1])
        // A query that holds a percent sign or the slot's neighbours is still drawn exactly as typed.
        assertEquals("No games match “100%”.", libraryAroundAuthored(english, R.string.library_gamelist_empty_search, "100%").spokenText)
    }

    @Test
    fun `the empty states read in both languages`() {
        assertEquals("No games on this platform yet.", english.getString(R.string.library_gamelist_empty_platform))
        assertEquals("No favorites on this platform yet.", english.getString(R.string.library_gamelist_empty_favorites_platform))
        assertEquals("No favorites yet — favorite a game to keep it here.", english.getString(R.string.library_gamelist_empty_favorites))
        assertEquals("No favorites match “Z”.", english.getString(R.string.library_gamelist_empty_favorites_search, "Z"))
        assertEquals("No favorite Android games yet.", english.getString(R.string.library_gamelist_empty_android_games))
        assertEquals("No games yet", english.getString(R.string.library_gamelist_empty_library_title))
        assertEquals("Aún no hay juegos en esta plataforma.", spanish.getString(R.string.library_gamelist_empty_platform))
        assertEquals("Aún no hay favoritos en esta plataforma.", spanish.getString(R.string.library_gamelist_empty_favorites_platform))
        assertEquals("Ningún favorito coincide con “Z”.", spanish.getString(R.string.library_gamelist_empty_favorites_search, "Z"))
        assertEquals("Aún no hay apps de Android favoritas.", spanish.getString(R.string.library_gamelist_empty_android_apps))
        assertEquals("Aún no hay juegos", spanish.getString(R.string.library_gamelist_empty_library_title))
        assertTrue(spanish.getString(R.string.library_gamelist_empty_library_body).contains("Mimir"))
    }
}
