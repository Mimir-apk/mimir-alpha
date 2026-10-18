package com.gruning.mimir.rewrite

import android.view.KeyEvent
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.InputTuning
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.model.library.ScanResult
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.feature.library.HomeShelfQuickRailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.dispatchToHomeShelf
import com.gruning.mimir.rewrite.feature.library.dispatchToLibrary
import com.gruning.mimir.rewrite.feature.library.homeShelfQuickRailKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalShelfHumanInputSequenceTest {

    @Test
    fun `human burst preserves synchronous traversal rail return and root focus memory`() {
        var now = 0L
        var destination = MimirDestination.HOME
        var requestedDestination: MimirDestination? = null
        val homeRows = (0 until 8).map { index ->
            HomeShelfRow(
                itemId = HomeShelfItemId.Emulated(GameId("human-$index")),
                rank = index * 1_024L,
                addedAtMillis = index.toLong(),
                title = "Human $index",
            )
        }
        val platforms = (0 until 5).map { index ->
            PlatformRow(
                platformId = PlatformId("human-platform-$index"),
                label = "Human Platform $index",
                gameCount = 1,
            )
        }
        val platformKeys = platforms.map { FocusKey("library/shelf/${it.platformId.value}") }
        val authority = DefaultFocusAuthority()
        // FUX-12c: the router paces ALL directional input globally. The pace rides this test's
        // simulated clock, and the tap cadence below is the ratified human cadence (280-500ms) —
        // every human-paced press still lands synchronously; only sub-cadence bursts are dropped.
        com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.clock = { now }
        com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.reset()
        val libraryViewModel = LibraryViewModel(StaticLibraryRepository(platforms))

        mountHome(authority, homeRows)
        authority.clearFocus()
        assertNull(authority.state.value.focused)

        val router = InputRouter(
            interactionRoot = InteractionRoot.PRIMARY,
            inputAuthority = DefaultInputAuthority(
                tuning = InputTuning(directionalRepeatMs = RepeatCadenceMs, directionalRepeatDelayMs = RepeatCadenceMs),
                clock = { now },
            ),
        )
        router.handler = { action ->
            when (destination) {
                MimirDestination.HOME -> dispatchToHomeShelf(
                    authority = authority,
                    rows = homeRows,
                    action = action,
                    onLaunch = {},
                    onOpenLibrary = { requestedDestination = MimirDestination.LIBRARY },
                    onQuickAction = { quickAction ->
                        if (quickAction == MimirQuickRailAction.LIBRARY) {
                            requestedDestination = MimirDestination.LIBRARY
                        }
                    },
                    onExit = {},
                )
                MimirDestination.LIBRARY -> {
                    if (
                        dispatchLibraryQuickRail(
                            authority = authority,
                            action = action,
                            homeEnabled = true,
                            onAction = { quickAction ->
                                if (quickAction == MimirQuickRailAction.HOME) {
                                    requestedDestination = MimirDestination.HOME
                                }
                            },
                        )
                    ) {
                        true
                    } else {
                        dispatchToLibrary(
                            authority = authority,
                            viewModel = libraryViewModel,
                            action = action,
                            onExit = { requestedDestination = MimirDestination.HOME },
                            onRescanFolder = {},
                            platformRows = platforms,
                        )
                    }
                }
                else -> false
            }
        }

        fun keyDown(keyCode: Int, repeat: Int = 0) {
            assertTrue(router.onRawKeyInput(keyCode = keyCode, down = true, repeat = repeat))
        }

        fun keyUp(keyCode: Int) {
            assertTrue(router.onRawKeyInput(keyCode = keyCode, down = false))
        }

        fun tap(keyCode: Int) {
            keyDown(keyCode)
            keyUp(keyCode)
            now += HumanTapGapMs
        }

        // A repeat arriving before its physical DOWN is consumed but cannot invent first-frame focus.
        keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1)
        assertNull(authority.state.value.focused)

        // Hold Right through two cadence repeats, then reverse before releasing either direction.
        keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(homeRows[0].focusKey, authority.state.value.focused)
        now = 100L
        keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1)
        assertEquals(homeRows[0].focusKey, authority.state.value.focused)
        now = 220L
        keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 2)
        assertEquals(homeRows[1].focusKey, authority.state.value.focused)
        now = 440L
        keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 3)
        assertEquals(homeRows[2].focusKey, authority.state.value.focused)

        // FUX-12 no-burst gate: a reversal 1ms after the last repeat is a BURST and must be dropped.
        now = 441L
        keyDown(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(homeRows[2].focusKey, authority.state.value.focused)
        // The same reversal at glide cadence lands synchronously.
        now = 660L
        keyDown(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(homeRows[1].focusKey, authority.state.value.focused)
        now = 880L
        keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 4)
        assertEquals(homeRows[1].focusKey, authority.state.value.focused)
        keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        keyUp(KeyEvent.KEYCODE_DPAD_LEFT)
        now += HumanTapGapMs

        var expectedIndex = 1
        repeat(40) { step ->
            val movingRight = step % 2 == 0
            tap(if (movingRight) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            expectedIndex += if (movingRight) 1 else -1
            assertEquals(homeRows[expectedIndex].focusKey, authority.state.value.focused)
        }
        val exactHomeFocus = homeRows[1].focusKey
        assertEquals(exactHomeFocus, authority.state.value.focused)

        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(HomeShelfQuickRailScope, authority.state.value.scope)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(
            homeShelfQuickRailKey(MimirQuickRailAction.LIBRARY),
            authority.state.value.focused,
        )
        tap(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(HomeShelfScope, authority.state.value.scope)
        assertEquals(exactHomeFocus, authority.state.value.focused)

        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(
            homeShelfQuickRailKey(MimirQuickRailAction.BRIGHTNESS),
            authority.state.value.focused,
        )
        tap(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(HomeShelfScope, authority.state.value.scope)
        assertEquals(exactHomeFocus, authority.state.value.focused)

        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        tap(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(
            homeShelfQuickRailKey(MimirQuickRailAction.LIBRARY),
            authority.state.value.focused,
        )
        tap(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(MimirDestination.LIBRARY, requestedDestination)

        unmountHome(authority, homeRows)
        destination = requestedDestination!!
        requestedDestination = null
        mountLibrary(authority, platformKeys)
        assertEquals(platformKeys.first(), authority.state.value.focused)

        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        val exactLibraryFocus = platformKeys[2]
        assertEquals(exactLibraryFocus, authority.state.value.focused)
        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(LibraryQuickRailScope, authority.state.value.scope)
        assertEquals(
            libraryQuickRailKey(MimirQuickRailAction.LIBRARY),
            authority.state.value.focused,
        )
        tap(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(
            libraryQuickRailKey(MimirQuickRailAction.HOME),
            authority.state.value.focused,
        )
        tap(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(MimirDestination.HOME, requestedDestination)

        unmountLibrary(authority, platformKeys)
        destination = requestedDestination!!
        requestedDestination = null
        mountHome(authority, homeRows)

        assertEquals(MimirDestination.HOME, destination)
        assertNull(requestedDestination)
        assertEquals(HomeShelfScope, authority.state.value.scope)
        assertEquals(exactHomeFocus, authority.state.value.focused)

        // The first physical input after warm return must act immediately; no focus "nudge" is permitted.
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(homeRows[2].focusKey, authority.state.value.focused)
    }

    private fun mountHome(
        authority: DefaultFocusAuthority,
        rows: List<HomeShelfRow>,
    ) {
        authority.pushScope(HomeShelfScope)
        rows.forEachIndexed { index, row ->
            authority.registerVirtual(row.focusKey, HomeShelfScope, shelfBounds(index))
        }
        MimirQuickRailAction.entries.forEachIndexed { index, action ->
            authority.register(homeShelfQuickRailKey(action), HomeShelfQuickRailScope, railBounds(index))
        }
    }

    private fun unmountHome(
        authority: DefaultFocusAuthority,
        rows: List<HomeShelfRow>,
    ) {
        while (authority.state.value.scope in setOf(HomeShelfScope, HomeShelfQuickRailScope)) {
            authority.popScope()
        }
        rows.forEach { authority.unregisterVirtual(it.focusKey) }
        MimirQuickRailAction.entries.forEach { authority.unregister(homeShelfQuickRailKey(it)) }
    }

    private fun mountLibrary(
        authority: DefaultFocusAuthority,
        platformKeys: List<FocusKey>,
    ) {
        authority.pushScope(LibraryShelfScope)
        platformKeys.forEachIndexed { index, key ->
            authority.registerVirtual(key, LibraryShelfScope, shelfBounds(index))
        }
        MimirQuickRailAction.entries.forEachIndexed { index, action ->
            authority.register(libraryQuickRailKey(action), LibraryQuickRailScope, railBounds(index))
        }
    }

    private fun unmountLibrary(
        authority: DefaultFocusAuthority,
        platformKeys: List<FocusKey>,
    ) {
        while (authority.state.value.scope in setOf(LibraryShelfScope, LibraryQuickRailScope)) {
            authority.popScope()
        }
        platformKeys.forEach(authority::unregisterVirtual)
        MimirQuickRailAction.entries.forEach { authority.unregister(libraryQuickRailKey(it)) }
    }

    private fun shelfBounds(index: Int): FocusBounds {
        val left = index * 1_000f
        return FocusBounds(left, 0f, left + 500f, 500f)
    }

    private fun railBounds(index: Int): FocusBounds {
        val left = index * 100f
        return FocusBounds(left, 1_000f, left + 64f, 1_064f)
    }

    private class StaticLibraryRepository(
        private val platforms: List<PlatformRow>,
    ) : LibraryRepository {
        override fun observeSummary(): Flow<LibrarySummary> =
            flowOf(LibrarySummary(gameCount = platforms.size, platformCount = platforms.size))

        override fun observePlatforms(): Flow<List<PlatformRow>> = flowOf(platforms)
        override fun observeGames(platformId: PlatformId): Flow<List<GameRow>> = flowOf(emptyList())
        override fun observeGame(gameId: GameId): Flow<GameRow?> = flowOf(null)
        override fun observeGameMembers(gameId: GameId): Flow<List<GameMemberRow>> = flowOf(emptyList())
        override fun observeFavorites(): Flow<List<GameRow>> = flowOf(emptyList())
        override fun observeSources(): Flow<List<SourceRow>> = flowOf(emptyList())
        override fun observeResolvingCount(): Flow<Int> = flowOf(0)
        override fun observeHiddenGames(): Flow<List<GameRow>> = flowOf(emptyList())
        override suspend fun rescan(rootUri: String): ScanResult = ScanResult(0, 0, 0, 0)
        override suspend fun setFavorite(gameId: GameId, favorite: Boolean) = Unit
        override suspend fun setHidden(gameId: GameId, hidden: Boolean) = Unit
        override suspend fun setTitleOverride(gameId: GameId, title: String) = Unit
        override suspend fun clearTitleOverride(gameId: GameId) = Unit
        override suspend fun hasGame(gameId: GameId): Boolean = false
    }

    private companion object {
        const val RepeatCadenceMs = 220L
        const val HumanTapGapMs = 300L
    }
}
