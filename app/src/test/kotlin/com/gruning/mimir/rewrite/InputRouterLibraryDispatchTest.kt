package com.gruning.mimir.rewrite

import android.view.KeyEvent
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.model.library.ScanResult
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import com.gruning.mimir.rewrite.feature.library.dispatchToLibrary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputRouterLibraryDispatchTest {

    @org.junit.Before
    fun fastForwardDirectionalPace() {
        var paceNow = 0L
        com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.clock = { paceNow += 300L; paceNow }
        com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.reset()
    }

    @Test
    fun humanRightPressesAdvanceEmulationShelfThroughRouter() {
        var now = 0L
        val rows = (0 until 7).map { index ->
            PlatformRow(PlatformId("router_$index"), "Router $index", gameCount = 1)
        }
        val keys = rows.map { FocusKey("library/shelf/${it.platformId.value}") }
        val authority = DefaultFocusAuthority().withVirtualShelfRing(keys)
        val viewModel = LibraryViewModel(StaticLibraryRepository(platforms = rows))
        val router = InputRouter(interactionRoot = InteractionRoot.PRIMARY, inputAuthority = DefaultInputAuthority(clock = { now }))
        router.handler = { action ->
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = action,
                onExit = {},
                onRescanFolder = {},
                platformRows = rows,
            )
        }

        repeat(6) { step ->
            press(router, KeyEvent.KEYCODE_DPAD_RIGHT)
            now += HumanPressGapMs
            assertEquals(keys[step + 1], authority.state.value.focused)
        }
    }

    @Test
    fun emulationPlatformBurstWrapsAndTicksExactlyOncePerCommittedMove() {
        var now = 0L
        var ticks = 0
        val rows = (0 until 5).map { index ->
            PlatformRow(PlatformId("burst_$index"), "Burst $index", gameCount = 1)
        }
        val keys = rows.map { FocusKey("library/shelf/${it.platformId.value}") }
        val authority = DefaultFocusAuthority().withVirtualShelfRing(keys)
        val viewModel = LibraryViewModel(StaticLibraryRepository(platforms = rows))
        val router = InputRouter(
            interactionRoot = InteractionRoot.PRIMARY,
            inputAuthority = DefaultInputAuthority(clock = { now }),
            onNavTick = { ticks++ },
        ).apply {
            navMoveProbe = { authority.state.value }
            handler = { action ->
                dispatchToLibrary(
                    authority = authority,
                    viewModel = viewModel,
                    action = action,
                    onExit = {},
                    onRescanFolder = {},
                    platformRows = rows,
                )
            }
        }

        repeat(7) {
            press(router, KeyEvent.KEYCODE_DPAD_RIGHT)
            now += HumanPressGapMs
        }

        assertEquals(keys[2], authority.state.value.focused)
        assertEquals(7, ticks)
    }

    @Test
    fun humanLeftPressesAdvanceAndroidGamesShelfThroughRouter() {
        // ANDROID_APPS retired 2026-08-21: LibraryShelfMode is now EMULATION/ANDROID_GAMES only, so this
        // (mode-agnostic LEFT/RIGHT ring dispatch) test targets the remaining non-EMULATION native lane.
        var now = 0L
        val rows = (0 until 7).map { index ->
            NativeShelfRow(
                packageName = "com.example.router$index",
                label = "Router $index",
                launchActivity = null,
                isGame = true,
            )
        }
        val keys = rows.map { nativeShelfFocusKey(it.packageName) }
        val authority = DefaultFocusAuthority().withVirtualShelfRing(keys, initialFocus = keys.last())
        val viewModel = LibraryViewModel(StaticLibraryRepository()).apply {
            cycleShelfMode(1)
        }
        assertEquals(LibraryShelfMode.ANDROID_GAMES, viewModel.shelfMode.value)
        val router = InputRouter(interactionRoot = InteractionRoot.PRIMARY, inputAuthority = DefaultInputAuthority(clock = { now }))
        router.handler = { action ->
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = action,
                onExit = {},
                onRescanFolder = {},
                nativeGameRows = rows,
            )
        }

        repeat(6) { step ->
            press(router, KeyEvent.KEYCODE_DPAD_LEFT)
            now += HumanPressGapMs
            assertEquals(keys[keys.lastIndex - step - 1], authority.state.value.focused)
        }
    }

    private fun press(router: InputRouter, keyCode: Int) {
        assertTrue(router.onRawKeyInput(keyCode = keyCode, down = true))
        assertTrue(router.onRawKeyInput(keyCode = keyCode, down = false))
    }

    private fun DefaultFocusAuthority.withVirtualShelfRing(
        keys: List<FocusKey>,
        initialFocus: FocusKey = keys.first(),
    ): DefaultFocusAuthority = apply {
        pushScope(LibraryShelfScope)
        keys.forEachIndexed { index, key ->
            val left = index * 1000f
            registerVirtual(
                key = key,
                scope = LibraryShelfScope,
                bounds = FocusBounds(left = left, top = 0f, right = left + 500f, bottom = 1000f),
            )
        }
        requestFocus(initialFocus)
    }

    private fun nativeShelfFocusKey(packageName: String): FocusKey =
        FocusKey("library/native/$packageName")

    private class StaticLibraryRepository(
        private val platforms: List<PlatformRow> = emptyList(),
    ) : LibraryRepository {
        override fun observeSummary(): Flow<LibrarySummary> =
            flowOf(LibrarySummary(gameCount = platforms.sumOf { it.gameCount }, platformCount = platforms.size))

        override fun observePlatforms(): Flow<List<PlatformRow>> = flowOf(platforms)
        override fun observeGames(platformId: PlatformId): Flow<List<GameRow>> = flowOf(emptyList())
        override fun observeGame(gameId: GameId): Flow<GameRow?> = flowOf(null)
        override fun observeGameMembers(gameId: GameId): Flow<List<GameMemberRow>> =
            flowOf(emptyList())
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
        const val HumanPressGapMs = 80L
    }
}
