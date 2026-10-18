package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single-game list focus regression observed on Odin3,2026-08-11: moving from the game to the
 * hero could strand focus until a platform switch.
 *
 * The harness replays what the LIST-mode render registers with the FocusAuthority — the bounded virtual
 * game column ([RegisterBoundedVirtualFocusColumn]'s window/tracker, used directly), the composed rows'
 * physical registrations, the header Search pill, and the preview panel's Enrich/Hero seat
 * ([GamelistGetArtKey]) — and what the platform-switch landing effect resolves
 * ([resolvePendingGamelistLanding] with the switch's null rememberedFocus). Controller stimulus goes
 * through the production [dispatchToLibrary]. Physical bounds are plausible screen rectangles; the
 * transitions under test are explicit requestFocus routes in the dispatch, not geometric scoring.
 */
class GamelistOneGameHeroFocusCharacterizationTest {

    // Fake corpus: N64 has two games, Dreamcast exactly one. TRIGGER_RIGHT from N64 lands on Dreamcast,
    // which is the captured single-game platform-switch stimulus.
    private val startPlatform = PlatformId.N64
    private val oneGamePlatform = PlatformId.DREAMCAST

    @Test
    fun `switching into a one-game list lands on its only game`() = runBlocking {
        val h = Harness()
        h.openListModeGamelistOn(startPlatform)

        h.switchPlatform(+1)
        h.settleAfterPlatformSwitch(oneGamePlatform)

        assertEquals(gameKey(h.onlyGame()), h.authority.state.value.focused)
    }

    @Test
    fun `one-game list, hero seat reached by RIGHT can be left again by LEFT`() = runBlocking {
        val h = Harness()
        h.openListModeGamelistOn(startPlatform)
        h.switchPlatform(+1)
        h.settleAfterPlatformSwitch(oneGamePlatform)
        val only = h.onlyGame()

        h.dispatch(ControllerAction.NAV_RIGHT)
        assertEquals(GamelistGetArtKey, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_LEFT)
        assertEquals(gameKey(only), h.authority.state.value.focused)
    }

    @Test
    fun `one-game list, hero seat reached by RIGHT can be left again by UP`() = runBlocking {
        val h = Harness()
        h.openListModeGamelistOn(startPlatform)
        h.switchPlatform(+1)
        h.settleAfterPlatformSwitch(oneGamePlatform)
        val only = h.onlyGame()

        h.dispatch(ControllerAction.NAV_RIGHT)
        assertEquals(GamelistGetArtKey, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_UP)
        assertEquals(gameKey(only), h.authority.state.value.focused)
    }

    @Test
    fun `one-game list, search pill reached by UP can be left again by DOWN`() = runBlocking {
        val h = Harness()
        h.openListModeGamelistOn(startPlatform)
        h.switchPlatform(+1)
        h.settleAfterPlatformSwitch(oneGamePlatform)
        val only = h.onlyGame()

        h.dispatch(ControllerAction.NAV_UP)
        assertEquals(LibrarySearchLauncherKey, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_DOWN)
        assertEquals(gameKey(only), h.authority.state.value.focused)
    }

    /**
     * Single-game D-pad stimulus. At n=1 DOWN from the only game resolves GEOMETRICALLY (the dispatch's
     * `else -> authority.moveFocus(action)` arm): the game row carries synthetic virtual bounds
     * (centre y=250) while the hero seat carries real pixel bounds below it, so the seat is the only
     * "down" neighbour — and a geometric arrival never runs `focusMetadata`, leaving `detailGame`
     * null from the platform switch. Every exit from the seat guards on `detailGame` (UP/LEFT) or is
     * a deliberate no-op (DOWN/RIGHT). At n>=2 the last row's virtual centre sits BELOW the seat's
     * pixel bounds, so DOWN is a dead edge there and the trap never arms — the n=1 specificity.
     */
    @Test
    fun `one-game list, hero seat reached by DOWN can be left again`() = runBlocking {
        val h = Harness()
        h.openListModeGamelistOn(startPlatform)
        h.switchPlatform(+1)
        h.settleAfterPlatformSwitch(oneGamePlatform)
        val only = h.onlyGame()

        h.dispatch(ControllerAction.NAV_DOWN)
        assertEquals(
            "precondition: geometric DOWN at n=1 lands on the hero seat",
            GamelistGetArtKey,
            h.authority.state.value.focused,
        )

        h.dispatch(ControllerAction.NAV_UP)
        assertEquals(
            "UP must return to the only game",
            gameKey(only),
            h.authority.state.value.focused,
        )
    }

    /**
     * Gate F1: the same null-metadata arrival must leave the seat ACTION-live, not just exit-live —
     * CONFIRM on the seat acts on the game the panel displays instead of silently doing nothing.
     */
    @Test
    fun `one-game list, hero seat reached by DOWN answers CONFIRM for the displayed game`() = runBlocking {
        val h = Harness()
        h.openListModeGamelistOn(startPlatform)
        h.switchPlatform(+1)
        h.settleAfterPlatformSwitch(oneGamePlatform)
        val only = h.onlyGame()

        h.dispatch(ControllerAction.NAV_DOWN)
        assertEquals(GamelistGetArtKey, h.authority.state.value.focused)

        val activated = mutableListOf<GameRow>()
        assertTrue(
            dispatchToLibrary(
                authority = h.authority,
                viewModel = h.viewModel,
                action = ControllerAction.CONFIRM,
                onExit = {},
                onRescanFolder = {},
                onFetchArt = { activated += it },
                onCustomizeHero = { activated += it },
            ),
        )
        assertEquals(
            "A on the seat must act on the displayed game, not fall through to nothing",
            listOf(only),
            activated,
        )
    }

    /** Gate F1, second read site: the PAGE view-mode toggle anchors from the seat's owner too. */
    @Test
    fun `one-game list, view toggle from the null-metadata seat re-anchors on the displayed game`() =
        runBlocking {
            val h = Harness()
            h.openListModeGamelistOn(startPlatform)
            h.switchPlatform(+1)
            h.settleAfterPlatformSwitch(oneGamePlatform)
            val only = h.onlyGame()

            h.dispatch(ControllerAction.NAV_DOWN)
            assertEquals(GamelistGetArtKey, h.authority.state.value.focused)

            h.dispatch(ControllerAction.PAGE_RIGHT)
            assertEquals(GamelistViewMode.CAROUSEL, h.viewModel.gamelistViewMode.value)
            assertEquals(gameKey(only), h.authority.state.value.focused)
        }

    /** Same null-metadata arrival, exiting LEFT — the seat's other guarded exit. */
    @Test
    fun `one-game list, hero seat reached by DOWN can be left again by LEFT`() = runBlocking {
        val h = Harness()
        h.openListModeGamelistOn(startPlatform)
        h.switchPlatform(+1)
        h.settleAfterPlatformSwitch(oneGamePlatform)
        val only = h.onlyGame()

        h.dispatch(ControllerAction.NAV_DOWN)
        assertEquals(GamelistGetArtKey, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_LEFT)
        assertEquals(gameKey(only), h.authority.state.value.focused)
    }

    /** Control: the identical hero-seat round trip on a multi-game list, which was observed working. */
    @Test
    fun `multi-game list control, hero seat round trip returns to the focused game`() = runBlocking {
        val h = Harness()
        h.openListModeGamelistOn(startPlatform)
        val first = h.viewModel.games.value.first()

        h.dispatch(ControllerAction.NAV_RIGHT)
        assertEquals(GamelistGetArtKey, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_LEFT)
        assertEquals(gameKey(first), h.authority.state.value.focused)
    }

    private inner class Harness {
        val repository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(repository)
        val authority = DefaultFocusAuthority()

        private val tracker = VirtualFocusRegistrationTracker()
        private var physicalRowKeys: List<FocusKey> = emptyList()

        suspend fun openListModeGamelistOn(platformId: PlatformId) {
            viewModel.openPlatform(platformId)
            awaitFirst("games loaded for ${platformId.value}", viewModel.games) { rows ->
                rows.isNotEmpty() && rows.all { it.platformId == platformId }
            }
            waitUntil("gamesLoadedForPlatform == ${platformId.value}") {
                viewModel.gamesLoadedForPlatform.value == platformId
            }
            viewModel.toggleGamelistViewMode()
            assertEquals(GamelistViewMode.LIST, viewModel.gamelistViewMode.value)

            authority.pushScope(GamelistScope)
            renderPass()
            authority.requestFocus(gameKey(viewModel.games.value.first()))
            renderPass()
            assertEquals(gameKey(viewModel.games.value.first()), authority.state.value.focused)
        }

        fun dispatch(action: ControllerAction) {
            assertTrue(
                dispatchToLibrary(
                    authority = authority,
                    viewModel = viewModel,
                    action = action,
                    onExit = {},
                    onRescanFolder = {},
                ),
            )
            renderPass()
        }

        /**
         * L2/R2 platform-switch stimulus. The dispatch's own TRIGGER route ends in
         * `switchOpenPlatform(delta, platforms.value)`, but the `platforms` StateFlow can never emit
         * under plain JUnit: unlike `games` (whose `flowOn(Dispatchers.Default)` is fused into its
         * stateIn sharing coroutine), `platforms` has no flowOn, so its sharing launches on the
         * ViewModel scope's Main dispatcher — absent on the JVM. Feed the same production switch the
         * repository's own platform rows instead; on device the subscribed flow supplies exactly these.
         */
        suspend fun switchPlatform(delta: Int) {
            val rows = repository.observePlatforms().first()
            assertTrue(viewModel.switchOpenPlatform(delta, rows))
            renderPass()
        }

        /** The switch's Room emission, ring swap, and one-shot landing (rememberedFocus null — LS:717). */
        suspend fun settleAfterPlatformSwitch(platformId: PlatformId) {
            assertEquals(platformId, viewModel.openPlatform.value)
            awaitFirst("switched games loaded for ${platformId.value}", viewModel.games) { rows ->
                rows.isNotEmpty() && rows.all { it.platformId == platformId }
            }
            waitUntil("gamesLoadedForPlatform == ${platformId.value}") {
                viewModel.gamesLoadedForPlatform.value == platformId
            }
            renderPass()
            val resolution = resolvePendingGamelistLanding(
                pending = PendingGamelistLanding(platformId = platformId, rememberedFocus = null),
                openPlatform = viewModel.openPlatform.value,
                gamesLoadedForPlatform = viewModel.gamesLoadedForPlatform.value,
                gameKeys = viewModel.games.value.map(::gameKey),
                focused = authority.state.value.focused,
            )
            resolution.target?.let(authority::requestFocus)
            renderPass()
        }

        fun onlyGame(): GameRow = viewModel.games.value.single()

        /**
         * One recomposition's worth of registration duty, exactly as GamelistView performs it:
         * bounded virtual column keyed on focused/selected index, composed rows' physical bounds
         * (add-before-remove, the tracker's own discipline), and the always-composed header Search
         * pill and preview-panel hero seat.
         */
        fun renderPass() {
            val games = viewModel.games.value
            val keys = games.map(::gameKey)
            val focused = authority.state.value.focused
            val focusedIndex = focused?.let(keys::indexOf) ?: -1
            val selectedIndex = viewModel.detailGame.value
                ?.let { selected -> games.indexOfFirst { it.gameId == selected.gameId } }
                ?: -1

            tracker.update(
                authority = authority,
                scope = GamelistScope,
                slots = boundedVirtualFocusWindow(keys, focusedIndex, selectedIndex).slots,
            )

            keys.forEachIndexed { index, key ->
                authority.register(key, GamelistScope, rowPhysicalBounds(index))
            }
            physicalRowKeys.filterNot { it in keys }.forEach(authority::unregister)
            physicalRowKeys = keys

            if (games.isNotEmpty()) {
                authority.register(LibrarySearchLauncherKey, GamelistScope, SearchPillBounds)
                authority.register(GamelistGetArtKey, GamelistScope, HeroSeatBounds)
            }
        }
    }

    /** Every wait is bounded and named, so a hang fails fast and points at its own line. */
    private suspend fun <T> awaitFirst(what: String, flow: Flow<T>, predicate: (T) -> Boolean): T =
        try {
            withTimeout(5_000) { flow.first(predicate) }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what", timeout)
        }

    private suspend fun waitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        // Monotonic clock: a wall-clock step (NTP) must neither fire a spurious timeout nor extend it.
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("timed out waiting for: $what")
            }
            delay(10)
        }
    }

    private companion object {
        /** Header pill above the list body. */
        val SearchPillBounds = FocusBounds(0f, 40f, 900f, 100f)

        /** The Enrich/Hero media chip inside the right-hand preview panel. */
        val HeroSeatBounds = FocusBounds(1600f, 600f, 1860f, 660f)

        /** Composed LazyColumn rows, stacked down the left of the viewport. */
        fun rowPhysicalBounds(index: Int) =
            FocusBounds(0f, 140f + index * 80f, 1200f, 200f + index * 80f)
    }
}
