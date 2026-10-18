package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.model.library.ScanResult
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchStateTest {

    @Test
    fun blankQueryAndAllGenrePreserveEligibleRepositoryOrderIncludingUnknownGenre() {
        val rows = listOf(
            game("ps1/unknown", "Unknown", PlatformId.PS1, genre = null),
            game("ps1/rpg", "Role", PlatformId.PS1, genre = "RPG"),
            game("snes/other", "Other", PlatformId.SNES, genre = "Action"),
        )

        assertEquals(
            listOf("ps1/unknown", "ps1/rpg"),
            reduceVisibleLibraryGames(
                orderedRows = rows,
                origin = LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
                criteria = LibrarySearchCriteria(query = "   "),
            ).ids(),
        )
    }

    @Test
    fun titleContainsIsTrimmedCaseInsensitiveAndComposesWithoutSorting() {
        val rows = listOf(
            game("ps1/third", "Super Metroid", PlatformId.PS1, genre = "Action"),
            game("ps1/first", "Metroid Prime", PlatformId.PS1, genre = "Action"),
            game("ps1/miss", "Chrono Trigger", PlatformId.PS1, genre = "RPG"),
        )

        assertEquals(
            listOf("ps1/third", "ps1/first"),
            reduceVisibleLibraryGames(
                orderedRows = rows,
                origin = LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
                criteria = LibrarySearchCriteria(query = "  mEtRoId "),
            ).ids(),
        )
    }

    @Test
    fun namedGenreUsesNormalizedExactEqualityAndExcludesUnknown() {
        val rows = listOf(
            game("ps1/exact-a", "A", PlatformId.PS1, genre = "Role   Playing"),
            game("ps1/exact-b", "B", PlatformId.PS1, genre = " role playing "),
            game("ps1/prefix", "C", PlatformId.PS1, genre = "Role Playing Game"),
            game("ps1/unknown", "D", PlatformId.PS1, genre = null),
        )

        assertEquals(
            listOf("ps1/exact-a", "ps1/exact-b"),
            reduceVisibleLibraryGames(
                orderedRows = rows,
                origin = LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
                criteria = LibrarySearchCriteria(
                    genre = LibrarySearchGenreFilter.exact("ROLE PLAYING"),
                ),
            ).ids(),
        )
    }

    @Test
    fun fixedPlatformOriginIgnoresPlatformCriterionAndRejectsForeignRows() {
        val rows = listOf(
            game("snes/foreign", "Foreign", PlatformId.SNES, genre = "Action"),
            game("ps1/local", "Local", PlatformId.PS1, genre = "Action"),
        )

        assertEquals(
            listOf("ps1/local"),
            reduceVisibleLibraryGames(
                orderedRows = rows,
                origin = LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
                criteria = LibrarySearchCriteria(
                    platform = LibrarySearchPlatformFilter.Exact(PlatformId.SNES),
                ),
            ).ids(),
        )
    }

    @Test
    fun platformCriterionAppliesOnlyToCrossPlatformFavoriteGames() {
        val rows = listOf(
            game("snes/non-favorite", "No", PlatformId.SNES, favorite = false),
            game("ps1/favorite", "PS", PlatformId.PS1, favorite = true),
            game("snes/favorite", "SNES", PlatformId.SNES, favorite = true),
        )

        assertEquals(
            listOf("snes/favorite"),
            reduceVisibleLibraryGames(
                orderedRows = rows,
                origin = LibrarySearchOrigin.FavoriteGames,
                criteria = LibrarySearchCriteria(
                    platform = LibrarySearchPlatformFilter.Exact(PlatformId.SNES),
                ),
            ).ids(),
        )
    }

    @Test
    fun regularFavoritesProjectionUsesTheSameQueryAndGenreReducer() {
        val rows = listOf(
            game("ps1/action", "Rune Action", PlatformId.PS1, genre = "Action", favorite = true),
            game("ps1/rpg-no", "Other", PlatformId.PS1, genre = "RPG", favorite = true),
            game("ps1/rpg-yes", "Rune Role", PlatformId.PS1, genre = "RPG", favorite = true),
            game("ps1/rpg-normal", "Rune Normal", PlatformId.PS1, genre = "RPG", favorite = false),
        )

        assertEquals(
            listOf("ps1/rpg-yes"),
            reduceVisibleLibraryGames(
                orderedRows = rows,
                origin = LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
                criteria = LibrarySearchCriteria(
                    query = "Rune",
                    genre = LibrarySearchGenreFilter.exact("rpg"),
                ),
                favoritesOnly = true,
            ).ids(),
        )
    }

    @Test
    fun combinedQueryGenreAndPlatformCriteriaCanContractToEmptyWithoutReordering() {
        val rows = listOf(
            game("ps1/query-genre", "Rune", PlatformId.PS1, genre = "RPG", favorite = true),
            game("snes/query-platform", "Rune", PlatformId.SNES, genre = "Action", favorite = true),
            game("snes/genre-platform", "Other", PlatformId.SNES, genre = "RPG", favorite = true),
        )

        assertTrue(
            reduceVisibleLibraryGames(
                orderedRows = rows,
                origin = LibrarySearchOrigin.FavoriteGames,
                criteria = LibrarySearchCriteria(
                    query = "Rune",
                    genre = LibrarySearchGenreFilter.exact("RPG"),
                    platform = LibrarySearchPlatformFilter.Exact(PlatformId.SNES),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun availableFacetsAreStableNormalizedAndIndependentOfInputOrder() {
        val rows = listOf(
            game("snes/rpg", "A", PlatformId.SNES, genre = " role   playing ", favorite = true),
            game("ps1/action", "B", PlatformId.PS1, genre = "Action", favorite = true),
            game("ps1/rpg", "C", PlatformId.PS1, genre = "Role Playing", favorite = true),
            game("gba/unknown", "D", PlatformId.GBA, genre = null, favorite = true),
            game("n64/not-favorite", "E", PlatformId.N64, genre = "Adventure", favorite = false),
        )

        val forward = deriveLibrarySearchFacets(rows, LibrarySearchOrigin.FavoriteGames)
        val reverse = deriveLibrarySearchFacets(rows.reversed(), LibrarySearchOrigin.FavoriteGames)

        assertEquals(forward, reverse)
        assertEquals(listOf("action", "role playing"), forward.genres.map { it.key.value })
        assertEquals(listOf("Action", "Role Playing"), forward.genres.map { it.label })
        assertEquals(
            listOf(PlatformId.GBA, PlatformId.PS1, PlatformId.SNES),
            forward.platforms,
        )
    }

    @Test
    fun fixedPlatformFacetsRespectFavoritesLaneAndNeverExposePlatformPicker() {
        val rows = listOf(
            game("ps1/action", "A", PlatformId.PS1, genre = "Action", favorite = false),
            game("ps1/rpg", "B", PlatformId.PS1, genre = "RPG", favorite = true),
            game("snes/rpg", "C", PlatformId.SNES, genre = "RPG", favorite = true),
        )

        val facets = deriveLibrarySearchFacets(
            orderedRows = rows,
            origin = LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
            favoritesOnly = true,
        )

        assertEquals(listOf("rpg"), facets.genres.map { it.key.value })
        assertTrue(facets.platforms.isEmpty())
    }

    @Test
    fun sessionCarriesTypedOriginFocusAndScrollIdentities() {
        val result = LibrarySearchResultIdentity.Game(GameId("ps1/game"))
        val session = LibrarySearchSession(
            origin = LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
            originFocus = LibrarySearchOriginFocusIdentity.Result(result),
            criteria = LibrarySearchCriteria(query = "game"),
            lastFocused = LibrarySearchFocusIdentity.Result(result),
            resultScrollAnchor = result,
        )

        assertEquals(result, session.resultScrollAnchor)
        assertThrows(IllegalArgumentException::class.java) {
            LibrarySearchResultIdentity.NativePackage(" ")
        }
    }

    @Test
    fun viewModelUsesGenreCriteriaForVisibleRowsAndFavoriteLanding() = runBlocking {
        val source = game(
            "ps1/rune-source",
            "Rune Source",
            PlatformId.PS1,
            genre = "Role Playing",
            favorite = false,
        )
        val nearerWrongGenre = game(
            "ps1/racing-favorite",
            "Racing Favorite",
            PlatformId.PS1,
            genre = "Racing",
            favorite = true,
        )
        val correctTarget = game(
            "ps1/rune-favorite",
            "Rune Favorite",
            PlatformId.PS1,
            genre = " role   playing ",
            favorite = true,
        )
        val repository = SearchLibraryRepository(listOf(source, nearerWrongGenre, correctTarget))
        val viewModel = LibraryViewModel(repository).apply { openPlatform(PlatformId.PS1) }

        viewModel.games.first { it.size == 3 }
        viewModel.setSearchQuery("r")
        viewModel.setSearchGenre(LibrarySearchGenreFilter.exact("ROLE PLAYING"))
        assertEquals(
            listOf(source.gameId, correctTarget.gameId),
            viewModel.games.first { it.size == 2 }.map(GameRow::gameId),
        )
        assertTrue(viewModel.toggleFavoritesOnly(source.gameId))

        assertEquals(correctTarget.gameId, viewModel.pendingGamelistFilterLanding.value?.targetGameId)
        assertEquals(
            listOf(correctTarget.gameId),
            viewModel.games.first { it.size == 1 }.map(GameRow::gameId),
        )
    }

    @Test
    fun viewModelPlatformFacetFiltersFavoriteGamesAndFreshEntriesResetAllCriteria() = runBlocking {
        val ps1 = game(
            "ps1/favorite",
            "PS Favorite",
            PlatformId.PS1,
            genre = "Action",
            favorite = true,
        )
        val snes = game(
            "snes/favorite",
            "SNES Favorite",
            PlatformId.SNES,
            genre = "RPG",
            favorite = true,
        )
        val viewModel = LibraryViewModel(SearchLibraryRepository(listOf(snes, ps1))).apply {
            openFavorites()
        }

        viewModel.games.first { it.size == 2 }
        assertEquals(listOf(PlatformId.PS1, PlatformId.SNES), viewModel.availableSearchFacets.value.platforms)
        viewModel.setSearchQuery("favorite")
        viewModel.setSearchPlatform(LibrarySearchPlatformFilter.Exact(PlatformId.PS1))
        assertEquals(listOf(ps1.gameId), viewModel.games.first { it.size == 1 }.map(GameRow::gameId))

        viewModel.setFavoriteSegment(FavoriteSegment.ANDROID_GAMES)
        assertEquals("", viewModel.searchQuery.value)
        assertEquals(LibrarySearchCriteria(), viewModel.searchCriteria.value)

        viewModel.setFavoriteSegment(FavoriteSegment.GAMES)
        viewModel.setSearchPlatform(LibrarySearchPlatformFilter.Exact(PlatformId.SNES))
        viewModel.openPlatform(PlatformId.PS1)
        assertEquals(LibrarySearchCriteria(), viewModel.searchCriteria.value)
        viewModel.setSearchPlatform(LibrarySearchPlatformFilter.Exact(PlatformId.SNES))
        assertEquals(
            LibrarySearchPlatformFilter.All,
            viewModel.searchCriteria.value.platform,
        )
    }

    @Test
    fun freshFavoritesPlatformSwitchAndCloseResetTheCompleteCriteria() = runBlocking {
        val ps1 = game(
            "ps1/favorite",
            "PS Favorite",
            PlatformId.PS1,
            genre = "Action",
            favorite = true,
        )
        val snes = game(
            "snes/favorite",
            "SNES Favorite",
            PlatformId.SNES,
            genre = "RPG",
            favorite = true,
        )
        val viewModel = LibraryViewModel(SearchLibraryRepository(listOf(ps1, snes)))
        viewModel.openPlatform(PlatformId.PS1)
        withTimeout(2_000L) { viewModel.games.first { it.size == 1 } }

        viewModel.openFavorites()
        withTimeout(2_000L) { viewModel.games.first { it.size == 2 } }
        viewModel.setSearchQuery("favorite")
        viewModel.setSearchGenre(LibrarySearchGenreFilter.exact("RPG"))
        viewModel.setSearchPlatform(LibrarySearchPlatformFilter.Exact(PlatformId.SNES))
        viewModel.openFavorites()
        assertEquals(LibrarySearchCriteria(), viewModel.searchCriteria.value)

        viewModel.openPlatform(PlatformId.PS1)
        withTimeout(2_000L) { viewModel.games.first { it.size == 1 } }
        viewModel.setSearchQuery("PS")
        viewModel.setSearchGenre(LibrarySearchGenreFilter.exact("Action"))
        assertTrue(
            viewModel.switchOpenPlatform(
                offset = 1,
                availablePlatforms = listOf(
                    PlatformRow(PlatformId.PS1, "PS1", 1),
                    PlatformRow(PlatformId.SNES, "SNES", 1),
                ),
            ),
        )
        assertEquals(LibrarySearchCriteria(), viewModel.searchCriteria.value)

        viewModel.setSearchQuery("SNES")
        viewModel.setSearchGenre(LibrarySearchGenreFilter.exact("RPG"))
        viewModel.closeGamelist()
        assertEquals(LibrarySearchCriteria(), viewModel.searchCriteria.value)
    }

    @Test
    fun dedicatedSearchSessionIsBoundedToEmulatedAndFavoriteGamesOrigins() = runBlocking {
        val ps1 = game(
            "ps1/search-origin",
            "Search Origin",
            PlatformId.PS1,
            genre = "Action",
            favorite = true,
        )
        val viewModel = LibraryViewModel(SearchLibraryRepository(listOf(ps1))).apply {
            openPlatform(PlatformId.PS1)
        }
        viewModel.games.first { it.singleOrNull()?.gameId == ps1.gameId }

        assertTrue(viewModel.openLibrarySearch(ps1.gameId))
        assertEquals(
            LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
            viewModel.librarySearchSession.value?.origin,
        )
        assertEquals(
            LibrarySearchOriginFocusIdentity.Result(LibrarySearchResultIdentity.Game(ps1.gameId)),
            viewModel.librarySearchSession.value?.originFocus,
        )
        assertTrue(!viewModel.openLibrarySearchPicker(LibrarySearchPicker.PLATFORM))

        viewModel.setSearchQuery("Origin")
        viewModel.setSearchGenre(LibrarySearchGenreFilter.exact("Action"))
        assertEquals(viewModel.searchCriteria.value, viewModel.librarySearchSession.value?.criteria)
        assertEquals(
            LibrarySearchOriginFocusIdentity.Result(LibrarySearchResultIdentity.Game(ps1.gameId)),
            viewModel.closeLibrarySearch(),
        )
        assertEquals(null, viewModel.librarySearchSession.value)

        viewModel.openFavorites(FavoriteSegment.ANDROID_GAMES)
        assertTrue(!viewModel.openLibrarySearch())
        assertEquals(null, viewModel.librarySearchSession.value)
    }

    @Test
    fun favoriteGamesSearchAllowsPlatformPickerAndFreshEntryClearsTransientSession() = runBlocking {
        val ps1 = game(
            "ps1/favorite-search",
            "Favorite Search",
            PlatformId.PS1,
            genre = "Action",
            favorite = true,
        )
        val viewModel = LibraryViewModel(SearchLibraryRepository(listOf(ps1))).apply {
            openFavorites(FavoriteSegment.GAMES)
        }
        viewModel.games.first { it.singleOrNull()?.gameId == ps1.gameId }

        assertTrue(viewModel.openLibrarySearch())
        assertEquals(LibrarySearchOrigin.FavoriteGames, viewModel.librarySearchSession.value?.origin)
        assertTrue(viewModel.openLibrarySearchPicker(LibrarySearchPicker.PLATFORM))
        assertEquals(LibrarySearchPicker.PLATFORM, viewModel.librarySearchPicker.value)

        viewModel.openPlatform(PlatformId.PS1)
        assertEquals(null, viewModel.librarySearchSession.value)
        assertEquals(null, viewModel.librarySearchPicker.value)
        assertEquals(LibrarySearchCriteria(), viewModel.searchCriteria.value)
    }

    @Test
    fun syntheticPickerKeysCannotCollideWithExactAllData() {
        val exactAllGenre = LibrarySearchGenreOption(
            key = LibrarySearchGenreKey.from("all")!!,
            label = "All",
        )

        assertTrue(librarySearchGenrePickerKey(null) != librarySearchGenrePickerKey(exactAllGenre))
        assertTrue(
            librarySearchPlatformPickerKey(null) !=
                librarySearchPlatformPickerKey(PlatformId("all")),
        )
    }

    @Test
    fun returnSnapshotRoundTripsValidatedFavoriteSearchState() {
        val ps1 = game(
            "ps1/favorite",
            "PS Favorite",
            PlatformId.PS1,
            genre = "Action",
            favorite = true,
        )
        val snes = game(
            "snes/favorite",
            "SNES Favorite",
            PlatformId.SNES,
            genre = "Role   Playing",
            favorite = true,
        )
        val games = listOf(ps1, snes)
        val facets = deriveLibrarySearchFacets(games, LibrarySearchOrigin.FavoriteGames)
        val selected = LibrarySearchResultIdentity.Game(snes.gameId)

        val snapshot = captureLibrarySearchReturnSnapshot(
            criteria = LibrarySearchCriteria(
                query = "  favorite ",
                genre = LibrarySearchGenreFilter.exact("ROLE PLAYING"),
                platform = LibrarySearchPlatformFilter.Exact(PlatformId.SNES),
            ),
            lastFocused = LibrarySearchFocusIdentity.Result(selected),
            lastTopFocused = LibrarySearchFocusIdentity.Platform,
            resultScrollAnchor = selected,
        )
        val restored = restoreLibrarySearchReturnSnapshot(
            snapshot = snapshot,
            legacyQuery = "ignored",
            origin = LibrarySearchOrigin.FavoriteGames,
            facets = facets,
            games = games,
        )

        assertEquals("role playing", snapshot.genreKey)
        assertEquals(PlatformId.SNES, snapshot.platformId)
        assertEquals(LibrarySearchReturnFocus.RESULT, snapshot.lastFocused)
        assertEquals(snes.gameId, snapshot.lastFocusedResultGameId)
        assertEquals(
            LibrarySearchCriteria(
                query = "  favorite ",
                genre = LibrarySearchGenreFilter.exact("role playing"),
                platform = LibrarySearchPlatformFilter.Exact(PlatformId.SNES),
            ),
            restored.criteria,
        )
        assertEquals(LibrarySearchFocusIdentity.Result(selected), restored.lastFocused)
        assertEquals(LibrarySearchFocusIdentity.Platform, restored.lastTopFocused)
        assertEquals(selected, restored.resultScrollAnchor)
    }

    @Test
    fun staleReturnSnapshotFallsBackToAllAndTheLastValidTopControl() {
        val live = game("ps1/live", "Live", PlatformId.PS1, genre = "Action")
        val restored = restoreLibrarySearchReturnSnapshot(
            snapshot = LibrarySearchReturnSnapshot(
                query = "live",
                genreKey = "missing genre",
                platformId = PlatformId.SNES,
                lastFocused = LibrarySearchReturnFocus.RESULT,
                lastFocusedResultGameId = GameId("ps1/removed"),
                lastTopFocused = LibrarySearchReturnFocus.PLATFORM,
                resultAnchorGameId = GameId("ps1/removed"),
            ),
            legacyQuery = "ignored",
            origin = LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
            facets = deriveLibrarySearchFacets(
                listOf(live),
                LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
            ),
            games = listOf(live),
        )

        assertEquals(
            LibrarySearchCriteria(query = "live"),
            restored.criteria,
        )
        assertEquals(LibrarySearchFocusIdentity.Query, restored.lastFocused)
        assertEquals(LibrarySearchFocusIdentity.Query, restored.lastTopFocused)
        assertNull(restored.resultScrollAnchor)
    }

    @Test
    fun clearFocusFallsBackWhenSanitizedCriteriaNoLongerRenderClear() {
        val restored = restoreLibrarySearchReturnSnapshot(
            snapshot = LibrarySearchReturnSnapshot(
                genreKey = "removed genre",
                lastFocused = LibrarySearchReturnFocus.CLEAR,
                lastTopFocused = LibrarySearchReturnFocus.GENRE,
            ),
            legacyQuery = "",
            origin = LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
            facets = LibrarySearchFacets.Empty,
            games = emptyList(),
        )

        assertEquals(LibrarySearchCriteria(), restored.criteria)
        assertEquals(LibrarySearchFocusIdentity.Genre, restored.lastFocused)
        assertEquals(LibrarySearchFocusIdentity.Genre, restored.lastTopFocused)
    }

    @Test
    fun legacyQueryOnlyReturnDefaultsToCarouselWithoutAStatefulSearchPayload() {
        val target = LibraryLaunchReturnTarget.gamelistRom(
            openPlatformId = PlatformId.PS1,
            romPlatformId = PlatformId.PS1,
            gameId = GameId("ps1/live"),
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.GAMES,
            searchQuery = "legacy",
            favoritesOnly = false,
        )
        val restored = restoreLibrarySearchReturnSnapshot(
            snapshot = target.searchSnapshot,
            legacyQuery = target.searchQuery,
            origin = LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
            facets = LibrarySearchFacets.Empty,
            games = emptyList(),
        )

        assertNull(target.searchSnapshot)
        assertEquals(GamelistViewMode.CAROUSEL, target.gamelistViewMode)
        assertEquals(LibrarySearchCriteria(query = "legacy"), restored.criteria)
        assertEquals(LibrarySearchFocusIdentity.Query, restored.lastFocused)
    }

    @Test
    fun shelfAndNativeReturnTargetsRejectDedicatedSearchSnapshots() {
        val snapshot = LibrarySearchReturnSnapshot(query = "not allowed")

        assertThrows(IllegalArgumentException::class.java) {
            LibraryLaunchReturnTarget(
                surface = LibraryLaunchReturnTarget.Surface.SHELF,
                shelfMode = LibraryShelfMode.EMULATION,
                shelfKey = "ps1",
                searchSnapshot = snapshot,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LibraryLaunchReturnTarget.gamelistNative(
                openPlatformId = LibraryViewModel.FAVORITES_PLATFORM_ID,
                nativePackageName = "com.example.app",
                shelfMode = LibraryShelfMode.ANDROID_GAMES,
                favoriteSegment = FavoriteSegment.ANDROID_APPS,
                searchQuery = "app",
            ).copy(searchSnapshot = snapshot)
        }
    }

    @Test
    fun settingsNavigationCapturesBeforeCloseAndResumesSearchOnlyOnExplicitOpen() = runBlocking {
        val first = game(
            "ps1/first",
            "First",
            PlatformId.PS1,
            genre = "Action",
        )
        val targetGame = game(
            "ps1/target",
            "Target",
            PlatformId.PS1,
            genre = "Action",
        )
        val viewModel = LibraryViewModel(SearchLibraryRepository(listOf(first, targetGame))).apply {
            openPlatform(PlatformId.PS1)
            toggleGamelistViewMode()
        }
        viewModel.games.first { it.size == 2 }
        assertTrue(viewModel.openLibrarySearch(targetGame.gameId))
        viewModel.setSearchGenre(LibrarySearchGenreFilter.exact("Action"))
        val result = LibrarySearchFocusIdentity.Result(
            LibrarySearchResultIdentity.Game(targetGame.gameId),
        )
        viewModel.rememberLibrarySearchFocus(LibrarySearchFocusIdentity.Genre)
        viewModel.rememberLibrarySearchFocus(result)

        val origin = viewModel.captureOriginReturnTarget(
            librarySearchGameResultKey(targetGame.gameId),
        )
        viewModel.closeLibrarySearchForExternalNavigation()
        val captured = viewModel.librarySearchReturnSnapshot.value
            ?: throw AssertionError("Expected dormant Search snapshot")

        assertEquals(LibrarySearchReturnFocus.RESULT, captured.lastFocused)
        assertEquals(targetGame.gameId, captured.lastFocusedResultGameId)
        assertEquals(targetGame.gameId, captured.resultAnchorGameId)
        assertEquals(LibrarySearchReturnFocus.GENRE, captured.lastTopFocused)
        assertEquals(GamelistViewMode.LIST, origin.gamelistViewMode)
        assertEquals(gameKey(targetGame), origin.focusKey)

        viewModel.openFavorites(FavoriteSegment.ANDROID_APPS)
        viewModel.restoreOriginReturnTargetState(origin)
        assertNull(viewModel.librarySearchSession.value)
        assertEquals(GamelistViewMode.LIST, viewModel.gamelistViewMode.value)
        assertEquals(
            LibrarySearchGenreFilter.exact("Action"),
            viewModel.searchCriteria.value.genre,
        )
        viewModel.games.first { it.size == 2 }

        assertTrue(viewModel.openLibrarySearch())
        assertEquals(result, viewModel.librarySearchSession.value?.lastFocused)
        assertEquals(
            LibrarySearchResultIdentity.Game(targetGame.gameId),
            viewModel.librarySearchSession.value?.resultScrollAnchor,
        )
        assertEquals(
            LibrarySearchFocusIdentity.Genre,
            viewModel.librarySearchLastTopFocus.value,
        )
    }

    @Test
    fun restoredStaleSearchStaysClosedUntilExplicitOpenThenSanitizes() = runBlocking {
        val live = game("ps1/live", "Live", PlatformId.PS1, genre = "Action")
        val viewModel = LibraryViewModel(SearchLibraryRepository(listOf(live)))
        val target = LibraryOriginReturnTarget(
            surface = LibraryOriginReturnTarget.Surface.GAMELIST,
            shelfMode = LibraryShelfMode.EMULATION,
            focusKey = LibrarySearchLauncherKey,
            openPlatformId = PlatformId.PS1,
            searchQuery = "",
            searchSnapshot = LibrarySearchReturnSnapshot(
                genreKey = "removed",
                lastFocused = LibrarySearchReturnFocus.CLEAR,
                lastTopFocused = LibrarySearchReturnFocus.GENRE,
            ),
            gamelistViewMode = GamelistViewMode.LIST,
        )

        viewModel.restoreOriginReturnTargetState(target)
        assertNull(viewModel.librarySearchSession.value)
        assertEquals(
            LibrarySearchGenreFilter.exact("removed"),
            viewModel.searchCriteria.value.genre,
        )
        viewModel.games.first { it.isEmpty() }

        assertTrue(viewModel.openLibrarySearch())
        assertEquals(LibrarySearchCriteria(), viewModel.searchCriteria.value)
        assertEquals(
            LibrarySearchFocusIdentity.Genre,
            viewModel.librarySearchSession.value?.lastFocused,
        )
        assertEquals(GamelistViewMode.LIST, viewModel.gamelistViewMode.value)
    }

    @Test
    fun shelfAndNativeRestoreClearDormantDedicatedSearchWithoutLosingViewMode() = runBlocking {
        val game = game("ps1/live", "Live", PlatformId.PS1, genre = "Action")
        val viewModel = LibraryViewModel(SearchLibraryRepository(listOf(game))).apply {
            openPlatform(PlatformId.PS1)
        }
        viewModel.games.first { it.isNotEmpty() }
        assertTrue(viewModel.openLibrarySearch())
        viewModel.closeLibrarySearch()
        assertTrue(viewModel.currentLibrarySearchReturnSnapshot() != null)

        viewModel.restoreLaunchReturnTargetState(
            LibraryLaunchReturnTarget.shelf(
                shelfMode = LibraryShelfMode.ANDROID_GAMES,
                shelfKey = "com.example.game",
                gamelistViewMode = GamelistViewMode.LIST,
            ),
        )
        assertNull(viewModel.currentLibrarySearchReturnSnapshot())
        assertNull(viewModel.librarySearchReturnSnapshot.value)
        assertNull(viewModel.librarySearchSession.value)
        assertEquals(GamelistViewMode.LIST, viewModel.gamelistViewMode.value)

        viewModel.restoreLaunchReturnTargetState(
            LibraryLaunchReturnTarget.gamelistNative(
                openPlatformId = LibraryViewModel.FAVORITES_PLATFORM_ID,
                nativePackageName = "com.example.app",
                shelfMode = LibraryShelfMode.ANDROID_GAMES,
                favoriteSegment = FavoriteSegment.ANDROID_APPS,
                searchQuery = "app",
                gamelistViewMode = GamelistViewMode.LIST,
            ),
        )
        assertNull(viewModel.currentLibrarySearchReturnSnapshot())
        assertNull(viewModel.librarySearchReturnSnapshot.value)
        assertNull(viewModel.librarySearchSession.value)
        assertEquals(GamelistViewMode.LIST, viewModel.gamelistViewMode.value)
    }

    private fun List<GameRow>.ids(): List<String> = map { it.gameId.value }

    private fun game(
        id: String,
        title: String,
        platformId: PlatformId,
        genre: String? = null,
        favorite: Boolean = false,
    ): GameRow = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = platformId,
        memberCount = 1,
        duplicateKind = null,
        genre = genre,
        isFavorite = favorite,
    )
}

private class SearchLibraryRepository(
    rows: List<GameRow>,
) : LibraryRepository {
    private val rows = MutableStateFlow(rows)

    override fun observeSummary(): Flow<LibrarySummary> = rows.map { current ->
        LibrarySummary(
            gameCount = current.size,
            platformCount = current.map(GameRow::platformId).distinct().size,
        )
    }

    override fun observePlatforms(): Flow<List<PlatformRow>> = rows.map { current ->
        current.groupBy(GameRow::platformId)
            .map { (platformId, games) -> PlatformRow(platformId, platformId.value, games.size) }
            .sortedBy { it.platformId.value }
    }

    override fun observeGames(platformId: PlatformId): Flow<List<GameRow>> =
        rows.map { current -> current.filter { it.platformId == platformId } }

    override fun observeGame(gameId: GameId): Flow<GameRow?> =
        rows.map { current -> current.firstOrNull { it.gameId == gameId } }

    override fun observeGameMembers(gameId: GameId): Flow<List<GameMemberRow>> = flowOf(emptyList())

    override fun observeFavorites(): Flow<List<GameRow>> =
        rows.map { current -> current.filter(GameRow::isFavorite) }

    override fun observeSources(): Flow<List<SourceRow>> = flowOf(emptyList())

    override fun observeResolvingCount(): Flow<Int> = flowOf(0)

    override fun observeHiddenGames(): Flow<List<GameRow>> = flowOf(emptyList())

    override suspend fun rescan(rootUri: String): ScanResult =
        ScanResult(filesSeen = 0, recognized = 0, groups = 0, skipped = 0)

    override suspend fun setFavorite(gameId: GameId, favorite: Boolean) {
        rows.value = rows.value.map { row ->
            if (row.gameId == gameId) row.copy(isFavorite = favorite) else row
        }
    }

    override suspend fun setHidden(gameId: GameId, hidden: Boolean) {
        if (hidden) rows.value = rows.value.filterNot { it.gameId == gameId }
    }

    override suspend fun setTitleOverride(gameId: GameId, title: String) {
        rows.value = rows.value.map { row ->
            if (row.gameId == gameId) row.copy(title = title, hasTitleOverride = true) else row
        }
    }

    override suspend fun clearTitleOverride(gameId: GameId) = Unit

    override suspend fun hasGame(gameId: GameId): Boolean = rows.value.any { it.gameId == gameId }
}
