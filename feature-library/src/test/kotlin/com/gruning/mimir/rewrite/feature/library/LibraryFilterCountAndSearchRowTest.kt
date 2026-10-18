package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirLiquidStatusPill
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import java.io.File
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
 * Two consistency fixes in the Library's Search.
 *  - With only a genre filter on, the gamelist header read "2 games" with nothing to say a filter was on. The count line names the filters that are on, the way it names Favorites
 *    ("Platformer · 2 games"), by the player-facing names the facets carry ("Platformer", not the stored key "platformer").
 *  - A Search result row read "Super Nintendo · PLATFORMER": the genre was uppercased, the platform beside it was not, and everywhere else in the app (the shelf's eyebrow, the list, the
 *    card) writes "Platformer". The row writes it like the rest. The overlay's fields and pickers write them the same way now (sentence case).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w900dp-h520dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryFilterCountAndSearchRowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val english = RuntimeEnvironment.getApplication().resources
    private val authority = DefaultFocusAuthority()
    private val snes = PlatformId("snes")
    private val platformer = LibrarySearchGenreKey.from("Platformer")!!
    private val facets = LibrarySearchFacets(
        genres = listOf(LibrarySearchGenreOption(platformer, "Platformer"), LibrarySearchGenreOption(LibrarySearchGenreKey.from("RPG")!!, "RPG")),
        platforms = listOf(snes),
    )

    // -- the count line ---------------------------------------------------------------------------

    @Test
    fun `a genre filter alone is named in the count line`() {
        val labels = gamelistActiveFilterLabels(LibrarySearchCriteria(genre = LibrarySearchGenreFilter.Exact(platformer)), facets, { "Super Nintendo" }, snes)
        assertEquals(listOf("Platformer"), labels)
        assertEquals("Platformer · 2 games", gamelistHeaderMeta(english, searchQuery = "", favoritesOnly = false, count = 2, kind = GamelistItemKind.GAME, filterLabels = labels))
    }

    @Test
    fun `the filters stand beside the query and Favorites, before the count`() {
        assertEquals(
            "“M” · Favorites · Platformer · 1 game",
            gamelistHeaderMeta(english, searchQuery = "M", favoritesOnly = true, count = 1, kind = GamelistItemKind.GAME, filterLabels = listOf("Platformer")),
        )
        assertEquals("Favorites · 2 games", gamelistHeaderMeta(english, searchQuery = "", favoritesOnly = true, count = 2, kind = GamelistItemKind.GAME))
        assertEquals("17 games", gamelistHeaderMeta(english, searchQuery = "", favoritesOnly = false, count = 17, kind = GamelistItemKind.GAME))
    }

    @Test
    fun `the genre is named as the facets write it, and a key with no facet is made presentable`() {
        val exact = LibrarySearchGenreFilter.Exact(LibrarySearchGenreKey.from("action adventure")!!)
        assertEquals(listOf("Action adventure"), gamelistActiveFilterLabels(LibrarySearchCriteria(genre = exact), LibrarySearchFacets.Empty, { "x" }, snes))
        val withFacet = LibrarySearchFacets(listOf(LibrarySearchGenreOption(exact.key, "Action-Adventure")), emptyList())
        assertEquals(listOf("Action-Adventure"), gamelistActiveFilterLabels(LibrarySearchCriteria(genre = exact), withFacet, { "x" }, snes))
    }

    @Test
    fun `a platform filter is named unless it is the platform the title already names`() {
        val gba = PlatformId("gba")
        val criteria = LibrarySearchCriteria(platform = LibrarySearchPlatformFilter.Exact(gba))
        assertEquals(listOf("Game Boy Advance"), gamelistActiveFilterLabels(criteria, facets, { "Game Boy Advance" }, snes))
        assertEquals("the title says it already", emptyList<String>(), gamelistActiveFilterLabels(criteria, facets, { "Game Boy Advance" }, gba))
        assertEquals("no filter, no label", emptyList<String>(), gamelistActiveFilterLabels(LibrarySearchCriteria(), facets, { "x" }, snes))
    }

    // -- the pictures -----------------------------------------------------------------------------

    private fun save(name: String, topDp: Float = 0f, heightDp: Float? = null) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val density = 2f
        val rootTop = compose.onRoot().fetchSemanticsNode().boundsInWindow.top.toInt().coerceAtLeast(0)
        val top = rootTop + (topDp * density).toInt()
        val height = ((heightDp ?: ((bitmap.height - top) / density)) * density).toInt().coerceAtMost(bitmap.height - top)
        val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
        val out = File(File("build/test-captures/sweepfix32/round3").apply { mkdirs() }, name)
        out.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the PNG was written: $out", out.isFile && out.length() > 1_000L)
    }

    @Test
    fun `the header with only a genre filter on says so, beside its tag and Search, short of the status pill`() {
        val meta = gamelistHeaderMeta(
            english,
            searchQuery = "",
            favoritesOnly = false,
            count = 2,
            kind = GamelistItemKind.GAME,
            filterLabels = gamelistActiveFilterLabels(LibrarySearchCriteria(genre = LibrarySearchGenreFilter.Exact(platformer)), facets, { "Super Nintendo" }, snes),
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(832.5f, 468.3f),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(832.5.dp, 468.3.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        Column(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistBodyPaddingDp, vertical = MimirSpacing.md)) {
                            GamelistHeader(
                                title = "Super Nintendo",
                                metaLabel = meta,
                                showSearch = true,
                                searchQuery = "",
                                searchPlaceholder = "Search games",
                                authority = authority,
                                searchControllerEnabled = true,
                                searchFocused = false,
                                onOpenSearch = {},
                                onClearSearch = {},
                                segments = emptyList(),
                                segmentCounts = emptyMap(),
                                activeSegment = FavoriteSegment.GAMES,
                                onSelectSegment = {},
                            )
                        }
                        MimirLiquidStatusPill(
                            clockLabel = "03:24",
                            batteryPercent = 100,
                            charging = false,
                            wifiConnected = true,
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val shown = compose.onAllNodes(hasText("games", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") { text -> text.text } }
        assertTrue("the count line names the genre: $shown", shown.contains("Platformer · 2 games"))
        save("filter-count-line.png", heightDp = 80f)
    }

    @Test
    fun `a Search result row writes the genre as the rest of the app does`() {
        val rpg = GameRow(gameId = GameId("g1"), title = "Chrono Trigger", platformId = snes, memberCount = 1, duplicateKind = null, genre = "RPG")
        val games = listOf(rpg, rpg.copy(gameId = GameId("g3"), title = "Super Metroid", genre = "Platformer"))
        val criteria = LibrarySearchCriteria(genre = LibrarySearchGenreFilter.Exact(platformer))
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
                                criteria = criteria,
                            ),
                            criteria = criteria,
                            facets = facets,
                            games = games,
                            platforms = listOf(PlatformRow(snes, "Super Nintendo", gameCount = 2)),
                            picker = null,
                            authority = authority,
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
        val texts = compose.onAllNodes(hasText("Super Nintendo", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") { text -> text.text } }
        println("SEARCHROW texts: $texts")
        assertTrue("a row reads <Super Nintendo  ·  Platformer>: $texts", texts.any { it == "Super Nintendo  ·  Platformer" })
        assertTrue("and <Super Nintendo  ·  RPG>: $texts", texts.any { it == "Super Nintendo  ·  RPG" })
        assertFalse("no row shouts the genre: $texts", texts.any { it.contains("PLATFORMER") })
        save("search-rows-genre-casing.png")
    }
}
