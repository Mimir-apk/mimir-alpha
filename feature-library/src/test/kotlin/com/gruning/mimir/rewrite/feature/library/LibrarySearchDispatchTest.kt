package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchDispatchTest {

    @Test
    fun cancelStackClosesKeyboardThenPickerThenSearchAndRestoresOriginGame() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val game = viewModel.games.first { it.isNotEmpty() }.first()
        val baseGameKey = gameKey(game)
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(baseGameKey, GamelistScope, bounds(0f))
            register(LibrarySearchLauncherKey, GamelistScope, bounds(100f))
            requestFocus(baseGameKey)
        }
        viewModel.focusMetadata(game)
        assertTrue(viewModel.openLibrarySearch(game.gameId))
        authority.pushScope(LibrarySearchScope)
        authority.registerSearchControls(game.gameId)
        authority.requestFocus(LibrarySearchQueryControlKey)

        assertTrue(dispatch(authority, viewModel, ControllerAction.CONFIRM))
        assertTrue(viewModel.keyboardOpen.value)
        assertTrue(dispatch(authority, viewModel, ControllerAction.CANCEL))
        assertEquals(LibrarySearchScope, authority.state.value.scope)
        assertEquals(LibrarySearchQueryControlKey, authority.state.value.focused)

        authority.requestFocus(LibrarySearchGenreFilterKey)
        assertTrue(dispatch(authority, viewModel, ControllerAction.CONFIRM))
        assertEquals(LibrarySearchFilterPickerScope, authority.state.value.scope)
        authority.register(
            librarySearchGenrePickerKey(null),
            LibrarySearchFilterPickerScope,
            bounds(0f),
        )
        authority.requestFocus(librarySearchGenrePickerKey(null))
        assertTrue(dispatch(authority, viewModel, ControllerAction.CANCEL))
        assertEquals(LibrarySearchScope, authority.state.value.scope)
        assertEquals(LibrarySearchGenreFilterKey, authority.state.value.focused)

        assertTrue(dispatch(authority, viewModel, ControllerAction.CANCEL))
        assertEquals(GamelistScope, authority.state.value.scope)
        assertEquals(baseGameKey, authority.state.value.focused)
        assertNull(viewModel.librarySearchSession.value)
    }

    @Test
    fun searchResultActivationHandsExactBaseIdentityToExistingDetailWithoutFirstGameFallback() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val games = viewModel.games.first { it.size >= 2 }
        val target = games.last()
        val targetBaseKey = gameKey(target)
        val targetSearchKey = librarySearchGameResultKey(target.gameId)
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            games.forEachIndexed { index, game ->
                register(gameKey(game), GamelistScope, bounds(index * 100f))
            }
            register(LibrarySearchLauncherKey, GamelistScope, bounds(900f))
            requestFocus(targetBaseKey)
        }
        viewModel.focusMetadata(target)
        assertTrue(viewModel.openLibrarySearch(target.gameId))
        authority.pushScope(LibrarySearchScope)
        authority.registerSearchControls(target.gameId)
        authority.requestFocus(targetSearchKey)
        val sessions = mutableListOf<LibraryDetailSession>()

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.CONFIRM,
                onExit = {},
                onRescanFolder = {},
                onOpenLibraryDetail = { sessions += it },
            ),
        )

        assertEquals(target.gameId, viewModel.openGameDetail.value?.gameId)
        assertEquals(GameDetailScope, authority.state.value.scope)
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)
        assertEquals(targetBaseKey, sessions.single().originFocusKey)
        assertEquals(targetBaseKey, authority.rememberedFocusIn(GamelistScope))
        assertNull(viewModel.librarySearchSession.value)
    }

    @Test
    fun filteredOutOriginClosesToSearchLauncherAndDirectionalEdgesClamp() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val game = viewModel.games.first { it.isNotEmpty() }.first()
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(gameKey(game), GamelistScope, bounds(0f))
            register(LibrarySearchLauncherKey, GamelistScope, bounds(100f))
            requestFocus(gameKey(game))
        }
        assertTrue(viewModel.openLibrarySearch(game.gameId))
        authority.pushScope(LibrarySearchScope)
        authority.registerSearchControls(game.gameId)
        authority.requestFocus(LibrarySearchQueryControlKey)

        assertTrue(dispatch(authority, viewModel, ControllerAction.NAV_LEFT))
        assertEquals(LibrarySearchQueryControlKey, authority.state.value.focused)
        viewModel.setSearchQuery("no title can match this")
        viewModel.games.first { it.isEmpty() }

        assertTrue(dispatch(authority, viewModel, ControllerAction.CANCEL))
        assertEquals(GamelistScope, authority.state.value.scope)
        assertEquals(LibrarySearchLauncherKey, authority.state.value.focused)
    }

    @Test
    fun everyTopControlReturnsToRememberedNonFirstResultAndFirstResultReturnsToThatTop() = runBlocking {
        val repository = FakeLibraryRepository().apply {
            setFavorite(com.gruning.mimir.rewrite.core.model.GameId("ps1/final-fantasy-vii"), true)
            setFavorite(com.gruning.mimir.rewrite.core.model.GameId("ps1/crash-bandicoot"), true)
        }
        val viewModel = LibraryViewModel(repository).apply {
            openFavorites(FavoriteSegment.GAMES)
        }
        val games = viewModel.games.first { it.size >= 2 }
        val firstResult = librarySearchGameResultKey(games.first().gameId)
        val rememberedResult = librarySearchGameResultKey(games[1].gameId)
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(LibrarySearchLauncherKey, GamelistScope, bounds(0f))
        }
        assertTrue(viewModel.openLibrarySearch())
        authority.pushScope(LibrarySearchScope)
        listOf(
            LibrarySearchQueryControlKey,
            LibrarySearchGenreFilterKey,
            LibrarySearchPlatformFilterKey,
            firstResult,
            rememberedResult,
        ).forEachIndexed { index, key ->
            authority.register(key, LibrarySearchScope, bounds(index * 100f))
        }

        val topMatrix = listOf(
            LibrarySearchQueryControlKey to LibrarySearchFocusIdentity.Query,
            LibrarySearchGenreFilterKey to LibrarySearchFocusIdentity.Genre,
            LibrarySearchPlatformFilterKey to LibrarySearchFocusIdentity.Platform,
        )
        topMatrix.forEach { (topKey, topIdentity) ->
            viewModel.rememberLibrarySearchFocus(
                LibrarySearchFocusIdentity.Result(
                    LibrarySearchResultIdentity.Game(games[1].gameId),
                ),
            )
            viewModel.rememberLibrarySearchFocus(topIdentity)
            authority.requestFocus(topKey)

            assertTrue(dispatch(authority, viewModel, ControllerAction.NAV_DOWN))
            assertEquals("down from ${topKey.value}", rememberedResult, authority.state.value.focused)

            // Up from result one returns to the actual prior top control, never an assumed terminal filter.
            authority.requestFocus(firstResult)
            assertTrue(dispatch(authority, viewModel, ControllerAction.NAV_UP))
            assertEquals("up to ${topKey.value}", topKey, authority.state.value.focused)
        }
    }

    @Test
    fun launcherDownRestoresPreparedNonFirstBaseGame() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val games = viewModel.games.first { it.size >= 3 }
        val prepared = games[2]
        val preparedKey = gameKey(prepared)
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            games.forEachIndexed { index, game ->
                register(gameKey(game), GamelistScope, bounds(index * 100f))
            }
            register(LibrarySearchLauncherKey, GamelistScope, bounds(900f))
            requestFocus(preparedKey)
        }
        viewModel.prepareLibrarySearchOrigin(prepared)
        authority.requestFocus(LibrarySearchLauncherKey)

        assertTrue(dispatch(authority, viewModel, ControllerAction.NAV_DOWN))
        assertEquals(preparedKey, authority.state.value.focused)
    }

    @Test
    fun exactGenreWithNoRowsClosesToMountedLauncherAndCanReopenForRecovery() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val game = viewModel.games.first { it.isNotEmpty() }.first()
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(gameKey(game), GamelistScope, bounds(0f))
            register(LibrarySearchLauncherKey, GamelistScope, bounds(100f))
            requestFocus(gameKey(game))
        }
        assertTrue(viewModel.openLibrarySearch(game.gameId))
        authority.pushScope(LibrarySearchScope)
        authority.registerSearchControls(game.gameId)
        authority.requestFocus(LibrarySearchGenreFilterKey)

        viewModel.setSearchGenre(LibrarySearchGenreFilter.exact("genre-with-no-rows"))
        viewModel.games.first { it.isEmpty() }
        assertTrue(
            libraryGamelistHeaderSearchVisible(
                dedicatedSearchEnabled = true,
                hasVisibleRows = false,
                query = viewModel.searchQuery.value,
            ),
        )

        assertTrue(dispatch(authority, viewModel, ControllerAction.CANCEL))
        assertEquals(GamelistScope, authority.state.value.scope)
        assertEquals(LibrarySearchLauncherKey, authority.state.value.focused)
        assertTrue(viewModel.openLibrarySearch())
    }

    @Test
    fun explicitControllerReopenPreseedsTheDormantResultBeforeItRegisters() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val games = viewModel.games.first { it.size >= 2 }
        val target = games[1]
        val targetSearchKey = librarySearchGameResultKey(target.gameId)
        assertTrue(viewModel.openLibrarySearch())
        viewModel.rememberLibrarySearchFocus(LibrarySearchFocusIdentity.Genre)
        viewModel.rememberLibrarySearchFocus(
            LibrarySearchFocusIdentity.Result(
                LibrarySearchResultIdentity.Game(target.gameId),
            ),
        )
        viewModel.closeLibrarySearch()

        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(LibrarySearchLauncherKey, GamelistScope, bounds(0f))
            requestFocus(LibrarySearchLauncherKey)
        }
        assertTrue(dispatch(authority, viewModel, ControllerAction.CONFIRM))
        assertEquals(LibrarySearchScope, authority.state.value.scope)
        assertNull(authority.state.value.focused)

        authority.register(targetSearchKey, LibrarySearchScope, bounds(100f))
        assertEquals(targetSearchKey, authority.state.value.focused)
        assertEquals(
            LibrarySearchFocusIdentity.Result(
                LibrarySearchResultIdentity.Game(target.gameId),
            ),
            viewModel.librarySearchSession.value?.lastFocused,
        )
    }

    private fun dispatch(
        authority: DefaultFocusAuthority,
        viewModel: LibraryViewModel,
        action: ControllerAction,
    ): Boolean = dispatchToLibrary(
        authority = authority,
        viewModel = viewModel,
        action = action,
        onExit = {},
        onRescanFolder = {},
    )

    private fun DefaultFocusAuthority.registerSearchControls(gameId: com.gruning.mimir.rewrite.core.model.GameId) {
        register(LibrarySearchQueryControlKey, LibrarySearchScope, bounds(0f))
        register(LibrarySearchGenreFilterKey, LibrarySearchScope, bounds(100f))
        register(librarySearchGameResultKey(gameId), LibrarySearchScope, bounds(200f))
        register(LibrarySearchClearKey, LibrarySearchScope, bounds(300f))
    }

    private fun bounds(top: Float): FocusBounds = FocusBounds(
        left = 0f,
        top = top,
        right = 100f,
        bottom = top + 80f,
    )
}
