package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibraryDispatchTest {

    @Test
    fun regularFavoriteToggleKeepsLastPlayedIdentityOrderAndFocusedGame() = runBlocking {
        val repository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(repository).apply { openPlatform(PlatformId.PS1) }
        val games = viewModel.games.first { it.size >= 3 }
        val beforeIds = games.map { it.gameId }
        val target = games[1]
        val targetKey = gameKeyForTest(target)
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            games.forEachIndexed { index, game ->
                registerVirtual(
                    gameKeyForTest(game),
                    GamelistScope,
                    FocusBounds(index * 100f, 0f, index * 100f + 80f, 100f),
                )
            }
            requestFocus(targetKey)
        }
        viewModel.focusMetadata(target)

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                // Key changed 2026-08-25 (Y -> SELECT); the behaviour pinned below did not.
                action = ControllerAction.SYSTEM_SELECT,
                onExit = {},
                onRescanFolder = {},
            ),
        )

        waitUntil { viewModel.games.value.firstOrNull { it.gameId == target.gameId }?.isFavorite == true }
        assertEquals(beforeIds, viewModel.games.value.map { it.gameId })
        assertEquals(targetKey, authority.state.value.focused)
        assertEquals(targetKey, authority.rememberedFocusIn(GamelistScope))
    }

    @Test
    fun regularGamelistNeverEntersAnEmptyFavoritesLaneButAlwaysEscapesIt() = runBlocking {
        val repository = FakeLibraryRepository()
        val favoriteId = GameId("ps1/final-fantasy-vii")
        val viewModel = LibraryViewModel(repository).apply { openPlatform(PlatformId.PS1) }

        viewModel.games.first { it.isNotEmpty() }
        assertFalse(viewModel.openPlatformHasFavorites.value)
        assertFalse(viewModel.toggleFavoritesOnly())
        assertFalse(viewModel.favoritesOnly.value)

        repository.setFavorite(favoriteId, true)
        waitUntil { viewModel.openPlatformHasFavorites.value }
        // Availability is raw platform truth, not the search-filtered visible list.
        viewModel.setSearchQuery("does-not-match")
        assertTrue(viewModel.toggleFavoritesOnly())
        assertTrue(viewModel.favoritesOnly.value)

        repository.setFavorite(favoriteId, false)
        waitUntil { !viewModel.openPlatformHasFavorites.value }
        assertTrue(viewModel.toggleFavoritesOnly())
        assertFalse(viewModel.favoritesOnly.value)
    }

    /**
     * **the favourites filter no longer owns L2/R2.**
     *
     * This test used to pin the opposite — trigger shoulders consumed, platform unchanged — and that WAS
     * deliberate, not an oversight. It was overturned after the press was measured dead on the Thor
     * against a negative control (the same keycode moved DS → N64 → 3DS unfiltered, minutes earlier).
     * The switch now happens and lands UNFILTERED; carrying the filter across would
     * arrive empty on 16 of the device's 20 platforms, which hold no favourites.
     *
     * **The old assertion could not have caught this change, and neither can a naive replacement.**
     * `switchOpenPlatform(1)` reads `platforms.value`, and that StateFlow has no `flowOn`, so its sharing
     * coroutine wants a Main dispatcher that JUnit does not have — it is permanently empty here and the
     * single-argument overload therefore returns false whatever the filter is doing. The old
     * `assertFalse` passed for that reason rather than for the reason it was written, and it kept passing
     * after the guard was removed. This drives the two-argument seam with the repository's own rows, the
     * pattern `GamelistOneGameHeroFocusCharacterizationTest` established for exactly this hazard.
     *
     * PAGE_RIGHT's view-mode toggle is unchanged and still pinned below.
     */
    @Test
    fun favoritesFilterNoLongerBlocksTriggerPlatformSwitchAndTheDestinationLandsUnfiltered() = runBlocking {
        val repository = FakeLibraryRepository()
        val favoriteId = GameId("ps1/final-fantasy-vii")
        repository.setFavorite(favoriteId, true)
        val viewModel = LibraryViewModel(repository).apply { openPlatform(PlatformId.PS1) }
        val games = viewModel.games.first { rows -> rows.any { it.gameId == favoriteId } }
        waitUntil { viewModel.openPlatformHasFavorites.value }
        val focused = games.first { it.gameId == favoriteId }
        val focusedKey = gameKeyForTest(focused)
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            registerVirtual(focusedKey, GamelistScope, FocusBounds(0f, 0f, 100f, 100f))
            requestFocus(focusedKey)
        }
        assertTrue(viewModel.toggleFavoritesOnly(focused.gameId))
        val platformBefore = viewModel.openPlatform.value
        assertEquals(GamelistViewMode.CAROUSEL, viewModel.gamelistViewMode.value)

        // PAGE_RIGHT still toggles the view mode and still leaves the platform alone.
        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.PAGE_RIGHT,
                onExit = {},
                onRescanFolder = {},
            ),
        )
        assertEquals(platformBefore, viewModel.openPlatform.value)
        assertEquals(GamelistViewMode.LIST, viewModel.gamelistViewMode.value)
        assertTrue("precondition: the filter must still be on", viewModel.favoritesOnly.value)

        // The switch itself, through the seam that is reachable under JUnit.
        val rows = repository.observePlatforms().first()
        assertTrue("the press must not be refused while the filter is on", viewModel.switchOpenPlatform(1, rows))
        assertNotEquals("it must actually move", platformBefore, viewModel.openPlatform.value)
        assertFalse("and it must arrive unfiltered", viewModel.favoritesOnly.value)
    }

    @Test
    fun normalAndFavoritesRestoreIndependentFocusedGamesAcrossRepeatedXSwitches() = runBlocking {
        val repository = FakeLibraryRepository().apply {
            setFavorite(GameId("ps1/final-fantasy-vii"), true)
            setFavorite(GameId("ps1/crash-bandicoot"), true)
        }
        val viewModel = LibraryViewModel(repository).apply { openPlatform(PlatformId.PS1) }
        val normalGames = viewModel.games.first { it.size >= 3 && it.count(GameRow::isFavorite) >= 2 }
        waitUntil { viewModel.openPlatformHasFavorites.value }
        val normalGame = normalGames.first { !it.isFavorite }
        val favoriteGames = normalGames.filter(GameRow::isFavorite)
        val favoriteGame = favoriteGames.last()
        val normalKey = gameKeyForTest(normalGame)
        val favoriteKey = gameKeyForTest(favoriteGame)
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            normalGames.forEachIndexed { index, game ->
                registerVirtual(
                    gameKeyForTest(game),
                    GamelistScope,
                    FocusBounds(index * 100f, 0f, index * 100f + 80f, 100f),
                )
            }
            requestFocus(normalKey)
        }
        fun toggle() = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.SECONDARY,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(toggle())
        waitUntil { viewModel.favoritesOnly.value && viewModel.games.value.all(GameRow::isFavorite) }
        // Deliberately choose a different Favorite; the next X captures this lane's own identity.
        authority.requestFocus(favoriteKey)
        assertTrue(toggle())
        waitUntil { !viewModel.favoritesOnly.value && viewModel.games.value.size == normalGames.size }
        assertEquals(normalKey, authority.state.value.focused)

        assertTrue(toggle())
        waitUntil { viewModel.favoritesOnly.value }
        assertEquals(favoriteKey, authority.state.value.focused)
        assertEquals(normalGame.gameId, viewModel.rememberedGamelistFocus(favoritesOnly = false))
        assertEquals(favoriteGame.gameId, viewModel.rememberedGamelistFocus(favoritesOnly = true))
    }

    @Test
    fun systemStartIsLeftForShellAtShelfRoot() {
        val authority = DefaultFocusAuthority().withRegistered(
            key = FocusKey("library/shelf/add"),
            scope = LibraryShelfScope,
        )

        assertFalse(dispatchStart(authority))
    }

    @Test
    fun systemStartIsSwallowedByNativeAddPickerModal() {
        val authority = DefaultFocusAuthority().withRegistered(
            key = NativeAddPickerSearchKey,
            scope = NativeAddPickerScope,
        )

        assertTrue(dispatchStart(authority))
    }

    @Test
    fun systemStartIsSwallowedByNativeHideModal() {
        val authority = DefaultFocusAuthority().withRegistered(
            key = NativeHideCancelKey,
            scope = NativeHideConfirmScope,
        )

        assertTrue(dispatchStart(authority))
    }

    @Test
    fun systemStartIsSwallowedByOpenWithModal() {
        val authority = DefaultFocusAuthority().withRegistered(
            key = OpenWithAutoKey,
            scope = OpenWithScope,
        )

        assertTrue(dispatchStart(authority))
    }

    @Test
    fun shelfSecondaryOpensHiddenFavoritesCollection() = runBlocking {
        val repository = FakeLibraryRepository()
        repository.setFavorite(GameId("ps1/final-fantasy-vii"), true)
        val viewModel = LibraryViewModel(repository)
        val authority = DefaultFocusAuthority().withRegistered(
            key = FocusKey("library/shelf/ps1"),
            scope = LibraryShelfScope,
        )

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.SECONDARY,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(handled)
        assertEquals(LibraryViewModel.FAVORITES_PLATFORM_ID, viewModel.openPlatform.value)
        assertEquals(FavoriteSegment.GAMES, viewModel.favoriteSegment.value)
        assertEquals(GamelistScope, authority.state.value.scope)
    }

    @Test
    fun shelfSecondaryOpensFavoritesAtCurrentNativeShelfSegment() {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            cycleShelfMode(1)
        }
        val authority = DefaultFocusAuthority().withRegistered(
            key = nativeShelfKey("com.example.game"),
            scope = LibraryShelfScope,
        )

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.SECONDARY,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(handled)
        assertEquals(LibraryViewModel.FAVORITES_PLATFORM_ID, viewModel.openPlatform.value)
        assertEquals(FavoriteSegment.ANDROID_GAMES, viewModel.favoriteSegment.value)
        assertEquals(GamelistScope, authority.state.value.scope)
    }

    @Test
    fun shelfRightWrapsFromLastToFirstEmulationPlatform() {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val rows = listOf(
            PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1),
            PlatformRow(PlatformId.SNES, "Super Nintendo", gameCount = 1),
            PlatformRow(PlatformId.GBA, "Game Boy Advance", gameCount = 1),
        )
        val lastKey = platformShelfKeyForTest(rows.last().platformId)
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(lastKey, LibraryShelfScope, FocusBounds(100f, 0f, 200f, 100f))
            register(platformShelfKeyForTest(rows.first().platformId), LibraryShelfScope, FocusBounds(0f, 0f, 100f, 100f))
            requestFocus(lastKey)
        }

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.NAV_RIGHT,
            onExit = {},
            onRescanFolder = {},
            platformRows = rows,
        )

        assertTrue(handled)
        assertEquals(platformShelfKeyForTest(rows.first().platformId), authority.state.value.focused)
    }

    @Test
    fun shelfLeftWrapsFromFirstToLastEmulationPlatform() {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val rows = listOf(
            PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1),
            PlatformRow(PlatformId.SNES, "Super Nintendo", gameCount = 1),
            PlatformRow(PlatformId.GBA, "Game Boy Advance", gameCount = 1),
        )
        val firstKey = platformShelfKeyForTest(rows.first().platformId)
        val lastKey = platformShelfKeyForTest(rows.last().platformId)
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(firstKey, LibraryShelfScope, FocusBounds(0f, 0f, 100f, 100f))
            register(lastKey, LibraryShelfScope, FocusBounds(100f, 0f, 200f, 100f))
            requestFocus(firstKey)
        }

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.NAV_LEFT,
            onExit = {},
            onRescanFolder = {},
            platformRows = rows,
        )

        assertTrue(handled)
        assertEquals(lastKey, authority.state.value.focused)
    }

    @Test
    fun shelfRightClampsAtTheLastNativeGame() {
        val rows = listOf(
            nativeRow("com.example.alpha", isGame = true),
            nativeRow("com.example.beta", isGame = true),
            nativeRow("com.example.gamma", isGame = true),
        )
        val lastKey = nativeShelfKey(rows.last().packageName)
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            cycleShelfMode(1)
        }
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(lastKey, LibraryShelfScope, FocusBounds(100f, 0f, 200f, 100f))
            register(nativeShelfKey(rows.first().packageName), LibraryShelfScope, FocusBounds(0f, 0f, 100f, 100f))
            requestFocus(lastKey)
        }

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.NAV_RIGHT,
            onExit = {},
            onRescanFolder = {},
            nativeGameRows = rows,
        )

        assertTrue(handled)
        assertEquals(lastKey, authority.state.value.focused)
    }

    @Test
    fun shelfRightRapidPressesTraverseBeyondVisibleEmulationWindow() {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val rows = (0 until 7).map { index ->
            PlatformRow(PlatformId("test_$index"), "Test $index", gameCount = 1)
        }
        val keys = rows.map { platformShelfKeyForTest(it.platformId) }
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            registerVirtualShelfRing(keys)
            requestFocus(keys.first())
        }

        repeat(6) { step ->
            val handled = dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.NAV_RIGHT,
                onExit = {},
                onRescanFolder = {},
                platformRows = rows,
            )

            assertTrue("right press ${step + 1}", handled)
            assertEquals(keys[step + 1], authority.state.value.focused)
        }
    }

    @Test
    fun shelfLeftRapidPressesTraverseBeyondVisibleNativeAppWindow() {
        val rows = (0 until 7).map { index ->
            nativeRow("com.example.app$index", isGame = false)
        }
        val keys = rows.map { nativeShelfKey(it.packageName) }
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            cycleShelfMode(1)
            cycleShelfMode(1)
        }
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            registerVirtualShelfRing(keys)
            requestFocus(keys.last())
        }

        repeat(6) { step ->
            val handled = dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.NAV_LEFT,
                onExit = {},
                onRescanFolder = {},
                nativeAppRows = rows,
            )

            assertTrue("left press ${step + 1}", handled)
            assertEquals(keys[keys.lastIndex - step - 1], authority.state.value.focused)
        }
    }

    @Test
    fun shelfClampsUsingRegisteredOrderWhenDispatchRowsAreEmpty() {
        // Production may omit native rows on this controller path. The reducer must still use the render's
        // complete registered order, while preserving the same finite edge policy as populated dispatch.
        val keys = (0 until 5).map { nativeShelfKey("com.example.app$it") }
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            cycleShelfMode(1) // EMULATION -> ANDROID_GAMES
        }
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            registerVirtualShelfRing(keys)
            requestFocus(keys.first())
        }

        // NAV_LEFT at the first tile is an accepted no-op, not a wrap or a platform-focus fallback.
        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.NAV_LEFT,
                onExit = {},
                onRescanFolder = {},
            ),
        )
        assertEquals(keys.first(), authority.state.value.focused)

        authority.requestFocus(keys.last())
        // NAV_RIGHT at the last tile is the symmetric accepted no-op.
        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.NAV_RIGHT,
                onExit = {},
                onRescanFolder = {},
            ),
        )
        assertEquals(keys.last(), authority.state.value.focused)
    }

    // ── CP04: off-ring self-heal  ──

    @Test
    fun shelfOffRingFocusSelfHealsToRingFirstKeyNeverTheGeometricFallback() {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val rows = listOf(
            PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1),
            PlatformRow(PlatformId.SNES, "Super Nintendo", gameCount = 1),
            PlatformRow(PlatformId.GBA, "Game Boy Advance", gameCount = 1),
        )
        val firstKey = platformShelfKeyForTest(rows.first().platformId)
        // A stale key that is NOT a member of the current EMULATION ring -- e.g. survives a mode-switch
        // registration gap or a ring-identity swap underneath it (BUG-3/BUG-4 class).
        val staleKey = FocusKey("library/native/stale-cross-mode")
        // Deliberately misleading decoy: registered to the geometric RIGHT of staleKey, so if the
        // self-heal fell through to authority.moveFocus() (the old `current < 0 -> return false`
        // behaviour), THIS is the key it would land on -- never a ring key.
        val decoyKey = FocusKey("library/shelf/decoy")
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            rows.forEachIndexed { index, row ->
                register(
                    platformShelfKeyForTest(row.platformId),
                    LibraryShelfScope,
                    FocusBounds(index * 100f, 0f, index * 100f + 80f, 100f),
                )
            }
            register(staleKey, LibraryShelfScope, FocusBounds(400f, 0f, 480f, 100f))
            register(decoyKey, LibraryShelfScope, FocusBounds(600f, 0f, 680f, 100f))
            requestFocus(staleKey)
        }

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.NAV_RIGHT,
            onExit = {},
            onRescanFolder = {},
            platformRows = rows,
        )

        assertTrue(handled)
        assertEquals(
            "off-ring focus must self-heal to the ring's first key, never the geometric fallback's decoy",
            firstKey,
            authority.state.value.focused,
        )
        // Write-through: both registration-independent memories carry the same stamped target.
        assertEquals(firstKey, viewModel.centeredShelfKey.value)
        assertEquals(firstKey, authority.rememberedFocusIn(LibraryShelfScope))
    }

    @Test
    fun shelfNullFocusInShelfScopeAlsoSelfHealsToRingFirstKey() {
        // Documents the deliberate widening beyond the original literal `current < 0` line (:1389): Home's
        // own moveHomeShelfEntryFocus self-heals a genuinely-null focus the same way (its `current = -1`
        // fallthrough covers "not resolvable" and "not in list" identically), and the ticket's own goal --
        // LEFT/RIGHT ring traversal never falls through to authority.moveFocus() again -- otherwise still
        // has a null-focus hole. See moveShelfListFocus's doc comment for the full reasoning.
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val rows = listOf(PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1))
        val firstKey = platformShelfKeyForTest(rows.first().platformId)
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(firstKey, LibraryShelfScope, FocusBounds(0f, 0f, 100f, 100f))
            // registering the ring's only key would auto-seed it (maybeSeedFocusOnRegister) -- clearFocus()
            // is the primitive that genuinely nulls live focus while leaving the registration intact.
            clearFocus()
        }
        assertNull(authority.state.value.focused)

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.NAV_RIGHT,
            onExit = {},
            onRescanFolder = {},
            platformRows = rows,
        )

        assertTrue(handled)
        assertEquals(firstKey, authority.state.value.focused)
    }

    // ── CP04: mode-switch focus survival ──

    @Test
    fun shelfModeSwitchImmediatelyFollowedByNavBurstStampsTargetWithoutGeometricFallback() {
        val platformRows = listOf(PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1))
        val nativeRows = listOf(
            nativeRow("com.example.alpha", isGame = true),
            nativeRow("com.example.beta", isGame = true),
        )
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val ps1Key = platformShelfKeyForTest(PlatformId.PS1)
        // Deliberately misleading decoy, positioned as ps1Key's geometric right neighbour -- if the burst
        // fell through to authority.moveFocus() from the stale pre-switch key, this is what it would pick.
        val decoyKey = FocusKey("library/shelf/decoy")
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(ps1Key, LibraryShelfScope, FocusBounds(0f, 0f, 80f, 100f))
            register(decoyKey, LibraryShelfScope, FocusBounds(200f, 0f, 280f, 100f))
            requestFocus(ps1Key)
        }

        // PAGE_RIGHT: EMULATION -> ANDROID_GAMES. In production the destination ring's tiles register on
        // the NEXT Compose recomposition (RegisterVirtualFocusRow's SideEffect) -- this unit test, with no
        // Compose runtime at all, honestly reproduces that gap: nativeRows' keys are never registered on
        // this authority, exactly as they are not yet registered in the real gap window.
        val modeSwitchHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.PAGE_RIGHT,
            onExit = {},
            onRescanFolder = {},
            platformRows = platformRows,
            nativeGameRows = nativeRows,
        )
        assertTrue(modeSwitchHandled)
        assertEquals(LibraryShelfMode.ANDROID_GAMES, viewModel.shelfMode.value)

        // Immediate burst: NAV_RIGHT with no intervening frame/recomposition.
        val burstHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.NAV_RIGHT,
            onExit = {},
            onRescanFolder = {},
            platformRows = platformRows,
            nativeGameRows = nativeRows,
        )

        assertTrue(burstHandled)
        // CP05 (2026-09-02) changed this expectation from ps1Key to null. Before CP05, the mode-switch
        // reducer left the OUTGOING mode's stale key as live focus across the gap, on the theory that an
        // unresolvable key is harmless -- Thor certification found that theory wrong (the pill's CONFIRM
        // arm has no membership check and the outgoing tile's own unregister can silently re-focus the
        // pill via DefaultFocusAuthority's nearest-sibling fallback, see LibraryDispatch.kt's
        // cycleShelfModeWithFocusStamp kdoc). `authority.clearFocus()` now runs in the same reducer step
        // as the flip, so live focus is null here rather than the stale ps1Key -- correcting THIS pin,
        // not weakening it. What the pin still proves, unchanged: the burst must not fall through to the
        // geometric engine and land on the decoy.
        assertEquals(
            "the burst must not fall through to the geometric fallback and land on the decoy",
            null,
            authority.state.value.focused,
        )
        // What IS provable off-device: the reducer stamped the correct destination target into the
        // ViewModel-level fallback memory, so the moment the real ring registers (device), the landing
        // LaunchedEffect lands on the right key when no render-level per-mode memory outranks it -- not
        // tile 0 of nothing and not a stale carry-over. Device certification must prove
        // the full loop; this proves the reducer half of it deterministically.
        //
        // Unchanged by CP05: `authority.rememberedFocusIn(LibraryShelfScope)` is still `expectedTarget`
        // here -- NOT because cycleShelfModeWithFocusStamp wrote it (CP05 deliberately removed that write,
        // see its kdoc), but because THIS test's own second dispatch, the NAV_RIGHT burst, lands in
        // moveShelfListFocus's pre-existing off-ring self-heal branch, which independently calls
        // `rememberFocusForScope` as part of its own write-through-then-request contract (CP04, unrelated
        // to and untouched by this fix). That independent write-through contract remains outside the mode-switch change.
        val expectedTarget = nativeShelfKey("com.example.alpha")
        assertEquals(expectedTarget, viewModel.centeredShelfKey.value)
        assertEquals(expectedTarget, authority.rememberedFocusIn(LibraryShelfScope))
    }

    @Test
    fun modeSwitchDoesNotArmAutoSeedAgainstTheDestinationRingsFirstTile() {
        // The regression this guards: if the reducer re-armed FocusAuthority's own auto-seed (by writing
        // the ring-first target into focus memory instead of leaving clearFocus()'s sentinel parked),
        // then the moment the destination ring's tiles register -- simulated here by `register(...)`,
        // standing in for Compose's SideEffect on the next recomposition -- `maybeSeedFocusOnRegister`
        // would grab the ring-first tile SYNCHRONOUSLY, before LibraryScreen's landing LaunchedEffect ever
        // gets a chance to apply render-level per-mode memory (policy 2026-09-02, "MEMORY WINS").
        // This must stay null here: leaving the choice to the (UI-only, not exercised by this JVM test)
        // landing effect is the whole point of parking the sentinel.
        val platformRows = listOf(PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1))
        val nativeRows = listOf(
            nativeRow("com.example.alpha", isGame = true),
            nativeRow("com.example.beta", isGame = true),
        )
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val ps1Key = platformShelfKeyForTest(PlatformId.PS1)
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(ps1Key, LibraryShelfScope, FocusBounds(0f, 0f, 80f, 100f))
            requestFocus(ps1Key)
        }

        dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.PAGE_RIGHT, // EMULATION -> ANDROID_GAMES
            onExit = {},
            onRescanFolder = {},
            platformRows = platformRows,
            nativeGameRows = nativeRows,
        )
        assertNull(authority.state.value.focused)

        // Simulate the destination ring's tiles registering on the next recomposition -- alpha first,
        // matching shelfRingKeys' ring-first order.
        authority.register(nativeShelfKey("com.example.alpha"), LibraryShelfScope, FocusBounds(0f, 0f, 80f, 100f))
        authority.register(nativeShelfKey("com.example.beta"), LibraryShelfScope, FocusBounds(90f, 0f, 170f, 100f))

        assertNull(
            "the reducer must not have armed auto-seed to the ring-first tile -- that decision belongs " +
                "to the landing effect alone, preserving remembered selection",
            authority.state.value.focused,
        )
    }

    // ── CP05: mode switch immediately followed by CONFIRM must not fire the Add-pill action ──
    //
    // Found by device observation on Thor: EMULATION -> ANDROID GAMES then CONFIRM opened the Add-game
    // picker 3/3; the reverse direction called onRescanFolder() 1/1, leaving the Library entirely. QA
    // proved by control that focus was NOT on the pill beforehand -- the burst itself relocated it there.
    // Mechanism [D from V source, per LibraryDispatch.kt's cycleShelfModeWithFocusStamp kdoc]: as the
    // outgoing ring tears down (Compose unregistering the tile the player was actually on), the pill is
    // the one node in LibraryShelfScope that survives every mode switch without unmounting, so
    // DefaultFocusAuthority.removeEntryAndFallback's nearest-remaining-sibling rule silently re-focuses
    // it -- and the CONFIRM "library/shelf/add" arm has no membership check (it cannot; the pill is not a
    // ring member by design), unlike every other arm CP04 guarded. Reproduced here by unregistering the
    // outgoing tile between the mode-switch dispatch and the CONFIRM dispatch, exactly as Compose's
    // teardown SideEffect does on-device in the ~23 ms gap QA measured (shoulder-press to directional-
    // input, getevent timestamps).

    @Test
    fun modeSwitchThenImmediateConfirmDoesNotOpenTheNativeAddPicker() {
        val platformRows = listOf(PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1))
        val nativeRows = listOf(nativeRow("com.example.alpha", isGame = true))
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val ps1Key = platformShelfKeyForTest(PlatformId.PS1)
        val addKey = FocusKey("library/shelf/add")
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(addKey, LibraryShelfScope, FocusBounds(0f, 0f, 80f, 40f))
            register(ps1Key, LibraryShelfScope, FocusBounds(0f, 50f, 80f, 150f))
            requestFocus(ps1Key)
        }
        var pickerOpened = false

        val switchHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.PAGE_RIGHT, // EMULATION -> ANDROID_GAMES
            onExit = {},
            onRescanFolder = {},
            onOpenNativeAddPicker = { pickerOpened = true },
            platformRows = platformRows,
            nativeGameRows = nativeRows,
        )
        assertTrue(switchHandled)
        assertEquals(LibraryShelfMode.ANDROID_GAMES, viewModel.shelfMode.value)
        // CP05: live focus must already be dropped by the reducer itself, before any teardown can run.
        assertNull(authority.state.value.focused)

        // Compose's teardown SideEffect unregisters the outgoing (now off-mode) tile some frames later.
        authority.unregister(ps1Key)
        // CP05's whole point: this must NOT have silently re-focused the pill.
        assertNotEquals(addKey, authority.state.value.focused)

        val confirmHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onOpenNativeAddPicker = { pickerOpened = true },
            platformRows = platformRows,
            nativeGameRows = nativeRows,
        )

        assertTrue(confirmHandled)
        assertFalse(
            "CONFIRM racing a mode switch must not open the Add-game picker the player never asked for",
            pickerOpened,
        )
    }

    @Test
    fun modeSwitchThenImmediateConfirmDoesNotRescanTheFolderAndLeaveTheLibrary() {
        // The reverse direction of the certified defect: ANDROID_GAMES -> EMULATION, CONFIRM opened the
        // Files/Drives browser via onRescanFolder(), leaving the Library entirely.
        val platformRows = listOf(PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1))
        val nativeRows = listOf(nativeRow("com.example.alpha", isGame = true))
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { cycleShelfMode(1) }
        val nativeKey = nativeShelfKey("com.example.alpha")
        val addKey = FocusKey("library/shelf/add")
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(addKey, LibraryShelfScope, FocusBounds(0f, 0f, 80f, 40f))
            register(nativeKey, LibraryShelfScope, FocusBounds(0f, 50f, 80f, 150f))
            requestFocus(nativeKey)
        }
        var rescanCalled = false

        val switchHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.PAGE_LEFT, // ANDROID_GAMES -> EMULATION
            onExit = {},
            onRescanFolder = { rescanCalled = true },
            platformRows = platformRows,
            nativeGameRows = nativeRows,
        )
        assertTrue(switchHandled)
        assertEquals(LibraryShelfMode.EMULATION, viewModel.shelfMode.value)
        assertNull(authority.state.value.focused)

        authority.unregister(nativeKey)
        assertNotEquals(addKey, authority.state.value.focused)

        val confirmHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = { rescanCalled = true },
            platformRows = platformRows,
            nativeGameRows = nativeRows,
        )

        assertTrue(confirmHandled)
        assertFalse(
            "CONFIRM racing a mode switch must not rescan the folder and leave the Library",
            rescanCalled,
        )
    }

    @Test
    fun modeSwitchIntoAnEmptyRingThenImmediateConfirmDoesNotFireThePillEither() {
        // The destination ring can be genuinely empty (no native games detected yet). LIB-02's
        // shelfModeLandingTarget legitimately lands the ASYNC render effect on the pill in that case --
        // but the SYNCHRONOUS reducer step must still not leave a stale live-focus artifact that a
        // same-tick CONFIRM could misread before that async landing ever runs.
        val platformRows = listOf(PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1))
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val ps1Key = platformShelfKeyForTest(PlatformId.PS1)
        val addKey = FocusKey("library/shelf/add")
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(addKey, LibraryShelfScope, FocusBounds(0f, 0f, 80f, 40f))
            register(ps1Key, LibraryShelfScope, FocusBounds(0f, 50f, 80f, 150f))
            requestFocus(ps1Key)
        }
        var pickerOpened = false

        dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.PAGE_RIGHT, // EMULATION -> ANDROID_GAMES, empty native ring
            onExit = {},
            onRescanFolder = {},
            onOpenNativeAddPicker = { pickerOpened = true },
            platformRows = platformRows,
            nativeGameRows = emptyList(),
        )
        assertNull(authority.state.value.focused)
        authority.unregister(ps1Key)
        assertNotEquals(addKey, authority.state.value.focused)

        val confirmHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onOpenNativeAddPicker = { pickerOpened = true },
            platformRows = platformRows,
            nativeGameRows = emptyList(),
        )

        assertTrue(confirmHandled)
        assertFalse(
            "a same-tick CONFIRM must not fire the pill even when the destination ring is empty",
            pickerOpened,
        )
    }

    // ── CP04: pre-action membership check ──

    @Test
    fun shelfConfirmOnValidPlatformTileOpensItsGamelist() {
        // Base-case coverage: no prior test exercised CONFIRM on an EMULATION shelf tile at all.
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val rows = listOf(PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1))
        val key = platformShelfKeyForTest(PlatformId.PS1)
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(key, LibraryShelfScope, FocusBounds(0f, 0f, 100f, 100f))
            requestFocus(key)
        }

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            platformRows = rows,
        )

        assertTrue(handled)
        assertEquals(PlatformId.PS1, viewModel.openPlatform.value)
        assertEquals(GamelistScope, authority.state.value.scope)
    }

    @Test
    fun shelfConfirmOnStaleRemovedPlatformKeyDoesNotOpenAGamelist() {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val currentRows = listOf(PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1))
        // A key for a platform that is no longer in the current shelf (e.g. its last game was removed) --
        // still registered from a stale render, still the live focused key.
        val removedKey = platformShelfKeyForTest(PlatformId.SNES)
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(removedKey, LibraryShelfScope, FocusBounds(0f, 0f, 100f, 100f))
            requestFocus(removedKey)
        }

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            platformRows = currentRows,
        )

        assertTrue(handled)
        assertNull(
            "a stale/removed platform key must not open a gamelist",
            viewModel.openPlatform.value,
        )
        assertEquals(LibraryShelfScope, authority.state.value.scope)
    }

    @Test
    fun shelfSelectOnStaleRemovedNativePackageDoesNotToggleFavorite() {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { cycleShelfMode(1) }
        val currentRows = listOf(nativeRow("com.example.alpha", isGame = true))
        val removedKey = nativeShelfKey("com.example.gone")
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(removedKey, LibraryShelfScope, FocusBounds(0f, 0f, 100f, 100f))
            requestFocus(removedKey)
        }
        val favorited = mutableListOf<String>()

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.SYSTEM_SELECT,
            onExit = {},
            onRescanFolder = {},
            onToggleNativeFavorite = { favorited += it },
            nativeGameRows = currentRows,
        )

        assertTrue(handled)
        assertTrue("a stale/removed package key must not toggle a favorite", favorited.isEmpty())
    }

    @Test
    fun favoritesShouldersCycleAvailableNativeSegments() {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openFavorites(FavoriteSegment.ANDROID_GAMES)
        }
        val authority = DefaultFocusAuthority().withRegistered(
            key = nativeShelfKey("com.example.game"),
            scope = GamelistScope,
        )

        val rightHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.PAGE_RIGHT,
            onExit = {},
            onRescanFolder = {},
            nativeFavoriteGameRows = listOf(nativeRow("com.example.game", isGame = true)),
            nativeFavoriteAppRows = listOf(nativeRow("com.example.app", isGame = false)),
        )
        val leftHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.PAGE_LEFT,
            onExit = {},
            onRescanFolder = {},
            nativeFavoriteGameRows = listOf(nativeRow("com.example.game", isGame = true)),
            nativeFavoriteAppRows = listOf(nativeRow("com.example.app", isGame = false)),
        )

        assertTrue(rightHandled)
        assertTrue(leftHandled)
        assertEquals(FavoriteSegment.ANDROID_GAMES, viewModel.favoriteSegment.value)
    }

    @Test
    fun confirmOnNativeFavoritesSegmentLaunchesFocusedPackage() {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openFavorites(FavoriteSegment.ANDROID_GAMES)
        }
        val authority = DefaultFocusAuthority().withRegistered(
            key = nativeShelfKey("com.example.game"),
            scope = GamelistScope,
        )
        var launchedPackage: String? = null

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = { launchedPackage = it },
        )

        assertTrue(handled)
        assertEquals("com.example.game", launchedPackage)
    }

    @Test
    fun confirmOnNativeShelfCapturesShelfReturnTargetBeforeLaunch() {
        val rows = listOf(nativeRow("com.example.game", isGame = true))
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            cycleShelfMode(1)
        }
        val authority = DefaultFocusAuthority().withRegistered(
            key = nativeShelfKey("com.example.game"),
            scope = LibraryShelfScope,
        )
        var launchedPackage: String? = null

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = { launchedPackage = it },
            nativeGameRows = rows,
        )

        val target = viewModel.pendingLaunchReturnTarget.value
            ?: throw AssertionError("Expected native shelf return target")
        assertTrue(handled)
        assertEquals("com.example.game", launchedPackage)
        assertEquals(LibraryLaunchReturnTarget.Surface.SHELF, target.surface)
        assertEquals(LibraryShelfMode.ANDROID_GAMES, target.shelfMode)
        assertEquals("com.example.game", target.shelfKey)
        assertNull(target.openPlatformId)
        assertNull(target.gameId)
    }

    @Test
    fun confirmOnNativeFavoritesCapturesGamelistReturnTarget() {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openFavorites(FavoriteSegment.ANDROID_APPS)
            setSearchQuery("mail")
        }
        val authority = DefaultFocusAuthority().withRegistered(
            key = nativeShelfKey("com.example.mail"),
            scope = GamelistScope,
        )

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = {},
            nativeFavoriteAppRows = listOf(nativeRow("com.example.mail", isGame = false)),
        )

        val target = viewModel.pendingLaunchReturnTarget.value
            ?: throw AssertionError("Expected native Favorites return target")
        assertTrue(handled)
        assertEquals(LibraryLaunchReturnTarget.Surface.GAMELIST, target.surface)
        assertEquals(LibraryViewModel.FAVORITES_PLATFORM_ID, target.openPlatformId)
        // ANDROID_APPS retired 2026-08-21: nativeReturnShelfMode() no longer has an APP lane to type-match,
        // so a Favorites/ANDROID_APPS launch now falls back to the live shelf mode -- EMULATION here, since
        // this viewModel never left it.
        assertEquals(LibraryShelfMode.EMULATION, target.shelfMode)
        assertEquals(FavoriteSegment.ANDROID_APPS, target.favoriteSegment)
        assertEquals("mail", target.searchQuery)
        assertEquals("com.example.mail", target.nativePackageName)
        assertNull(target.gameId)
    }

    @Test
    fun originReturnTargetKeepsNativeAddShelfFocusExact() {
        // ANDROID_APPS retired 2026-08-21: ANDROID_GAMES is now the only non-EMULATION shelf mode, so one
        // cycleShelfMode(1) reaches it (a second shift used to be needed to reach the now-gone APPS lane).
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            cycleShelfMode(1)
        }
        val addKey = FocusKey("library/shelf/add")

        val target = viewModel.captureOriginReturnTarget(addKey)
        viewModel.openFavorites(FavoriteSegment.GAMES)
        viewModel.restoreOriginReturnTargetState(target)

        assertEquals(LibraryOriginReturnTarget.Surface.SHELF, target.surface)
        assertEquals(LibraryShelfMode.ANDROID_GAMES, target.shelfMode)
        assertEquals(addKey, target.focusKey)
        assertNull(target.openPlatformId)
        assertNull(viewModel.openPlatform.value)
        assertEquals(LibraryShelfMode.ANDROID_GAMES, viewModel.shelfMode.value)
    }

    @Test
    fun originReturnTargetCapturesArtworkFocusAsOwningGameRow() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
            setSearchQuery("Final")
        }
        val focusedGame = firstPs1Game(viewModel)
        viewModel.focusMetadata(focusedGame)

        val target = viewModel.captureOriginReturnTarget(GamelistGetArtKey)

        assertEquals(LibraryOriginReturnTarget.Surface.GAMELIST, target.surface)
        assertEquals(PlatformId.PS1, target.openPlatformId)
        assertEquals(gameKeyForTest(focusedGame), target.focusKey)
        assertEquals("Final", target.searchQuery)
    }

    @Test
    fun confirmOnRomGamelistOpensDetailPage() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val focusedGame = viewModel.games.first { games ->
            games.any { it.gameId.value == "ps1/final-fantasy-vii" }
        }.first { it.gameId.value == "ps1/final-fantasy-vii" }
        val authority = DefaultFocusAuthority().withRegistered(
            key = FocusKey("library/gamelist/${focusedGame.gameId.value}"),
            scope = GamelistScope,
        )

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(handled)
        // A opens the rich game-detail page (Play launches from there), not a direct launch.
        assertEquals(focusedGame.gameId, viewModel.openGameDetail.value?.gameId)
        assertEquals(GameDetailScope, authority.state.value.scope)
    }

    @Test
    fun successfulRomLaunchCapturesGamelistReturnTarget() = runBlocking {
        val launchPort = RecordingLaunchPort { game -> intentSentResult(game) }
        val viewModel = LibraryViewModel(FakeLibraryRepository(), launchPort).apply {
            openPlatform(PlatformId.PS1)
            setSearchQuery("Final")
        }
        val focusedGame = firstPs1Game(viewModel)
        val authority = DefaultFocusAuthority().withRegistered(
            key = gameKeyForTest(focusedGame),
            scope = GamelistScope,
        )

        // A on the gamelist opens the detail page; Play (A again, now in GameDetailScope) launches.
        val opened = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
        )
        assertTrue(opened)
        assertEquals(GameDetailScope, authority.state.value.scope)

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(handled)
        waitUntil {
            viewModel.launchState.value == GameLaunchUiState.IntentSent &&
                viewModel.pendingLaunchReturnTarget.value != null
        }
        val target = viewModel.pendingLaunchReturnTarget.value
            ?: throw AssertionError("Expected ROM return target")
        assertEquals(LibraryLaunchReturnTarget.Surface.GAMELIST, target.surface)
        assertEquals(LibraryShelfMode.EMULATION, target.shelfMode)
        assertEquals(PlatformId.PS1, target.openPlatformId)
        assertEquals(PlatformId.PS1, target.romPlatformId)
        assertEquals(focusedGame.gameId, target.gameId)
        assertEquals("Final", target.searchQuery)
        assertFalse(target.favoritesOnly)
        assertNull(target.nativePackageName)
        assertTrue(target.restoreLaunchCard)
    }

    @Test
    fun watchdogCleanupRejectsAStaleOperationAndLateResultsCannotRearmTheUi() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val events = CopyOnWriteArrayList<LibraryGameSessionEvent>()
        val launchPort = RecordingLaunchPort { game ->
            release.await()
            intentSentResult(game)
        }
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = launchPort,
            gameSessionObserver = LibraryGameSessionObserver(events::add),
        ).apply {
            openPlatform(PlatformId.PS1)
        }
        val games = viewModel.games.first { it.size >= 2 }

        viewModel.launch(games[0])
        waitUntil { events.filterIsInstance<LibraryGameSessionEvent.Preparing>().size == 1 }
        val first = events.filterIsInstance<LibraryGameSessionEvent.Preparing>().single().operation
        viewModel.launch(games[1])
        assertEquals(1, events.filterIsInstance<LibraryGameSessionEvent.Preparing>().size)
        val stale = LibraryGameLaunchOperation(first.value + 1L)

        assertFalse(viewModel.cancelPendingLaunch(stale))
        assertEquals(GameLaunchUiState.Launching, viewModel.launchState.value)
        assertTrue(viewModel.cancelPendingLaunch(first))
        assertEquals(GameLaunchUiState.Idle, viewModel.launchState.value)
        assertNull(viewModel.pendingLaunchReturnTarget.value)

        release.complete(Unit)
        waitUntil { events.filterIsInstance<LibraryGameSessionEvent.Settled>().size == 1 }
        assertEquals(GameLaunchUiState.Idle, viewModel.launchState.value)
        assertNull(viewModel.pendingLaunchReturnTarget.value)
    }

    /**
     * Outcome-dropping audit finding O-3 / L2 (2026-08-12): `admitted == false` used to `return` with
     * `launchState` left completely untouched — press Play on the Library launch card while a prior
     * session is held and the screen does not change in any way. This pins the fix: the mutex refusal
     * now publishes the honest [GameLaunchUiState.SessionHeld] arm instead of leaving the stale state (or
     * the initial [GameLaunchUiState.Idle]) in place.
     */
    @Test
    fun sessionMutexRefusalPublishesSessionHeldInsteadOfLeavingTheScreenUntouched() = runBlocking {
        // launchController is irrelevant here: `admitted == false` returns before the port is ever
        // consulted, so this constructor (no port at all) proves the refusal never needed one.
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchController = null,
            gameSessionObserver = LibraryGameSessionObserver { false },
        ).apply {
            openPlatform(PlatformId.PS1)
        }
        val game = firstPs1Game(viewModel)

        assertEquals(GameLaunchUiState.Idle, viewModel.launchState.value)
        viewModel.launch(game)

        // An observer that cannot say what held it answers STARTING, today's sentence (O15).
        assertEquals(GameLaunchUiState.SessionHeld(GameLaunchSessionHold.STARTING), viewModel.launchState.value)
    }

    /**
     * O15: the refusal carries what the authority reports for the exact Preparing event it refused, so a game already
     * running reaches the card as RUNNING, with its sentence.
     */
    @Test
    fun sessionMutexRefusalCarriesTheHoldTheAuthorityReportsForThatExactEvent() = runBlocking {
        val asked = mutableListOf<LibraryGameSessionEvent.Preparing>()
        val refusedWhileRunning = object : LibraryGameSessionObserver {
            override fun onEvent(event: LibraryGameSessionEvent): Boolean = false

            override fun holdAfterRefusal(event: LibraryGameSessionEvent.Preparing): GameLaunchSessionHold {
                asked += event
                return GameLaunchSessionHold.RUNNING
            }
        }
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchController = null,
            gameSessionObserver = refusedWhileRunning,
        ).apply {
            openPlatform(PlatformId.PS1)
        }
        val game = firstPs1Game(viewModel)

        viewModel.launch(game)

        assertEquals(GameLaunchUiState.SessionHeld(GameLaunchSessionHold.RUNNING), viewModel.launchState.value)
        assertEquals("asked once, about the launch it refused", listOf(game), asked.map { it.game })
        assertEquals(
            "A game or app is still open. Close it first, then try again.",
            gameLaunchStatusMessage(viewModel.launchState.value) { "PlayStation" },
        )
    }

    /** Same refusal, but confirms it is a genuinely distinct, honest state — not just "not Idle". */
    @Test
    fun sessionMutexRefusalStateHasItsOwnHonestMessage() {
        GameLaunchSessionHold.entries.forEach { hold ->
            assertEquals(
                gameLaunchSessionHeldMessage(hold),
                gameLaunchStatusMessage(GameLaunchUiState.SessionHeld(hold)) { "PlayStation" },
            )
            assertNotEquals(
                gameLaunchStatusMessage(GameLaunchUiState.Idle) { "PlayStation" },
                gameLaunchStatusMessage(GameLaunchUiState.SessionHeld(hold)) { "PlayStation" },
            )
        }
    }

    /**
     * The other direction the brief calls out by name: an ordinary admitted launch must be completely
     * unaffected by the new arm. Same shape as [successfulRomLaunchCapturesGamelistReturnTarget], with an
     * observer that always admits (mirroring [NoOpLibraryGameSessionObserver]) to make the contrast with
     * the refusal test above explicit in one file — this reaches real dispatch and [IntentSent],
     * confirming the mutex-admitted path never sees [GameLaunchUiState.SessionHeld].
     */
    @Test
    fun ordinaryAdmittedLaunchReachesIntentSentUnaffectedByTheNewArm() = runBlocking {
        val launchPort = RecordingLaunchPort { game -> intentSentResult(game) }
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = launchPort,
            gameSessionObserver = LibraryGameSessionObserver { true },
        ).apply {
            openPlatform(PlatformId.PS1)
        }
        val game = firstPs1Game(viewModel)

        viewModel.launch(game)

        waitUntil { viewModel.launchState.value == GameLaunchUiState.IntentSent }
        assertFalse(viewModel.launchState.value is GameLaunchUiState.SessionHeld)
    }

    /**
     * O15 follow-up evidence (coordinator review, 2026-09-12): IntentSent can outlive its game. The M button's Home and its
     * Quick launch in place clear the return target (`clearPendingLaunchReturnTarget`) and leave the launch state; only the
     * restore that target feeds, the watchdog's cancel, or a Library navigation puts the state back to Idle. `app/`'s Main
     * quick-launch guard therefore asks the session ledger what IntentSent means before it refuses (`libraryLaunchGuardHold`).
     * This pins the Library's half of that stale case; the lifecycle itself is deliberately unchanged.
     */
    @Test
    fun clearingTheReturnTargetLeavesIntentSentWithNothingToRestoreFrom() = runBlocking {
        val launchPort = RecordingLaunchPort { game -> intentSentResult(game) }
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = launchPort,
            gameSessionObserver = LibraryGameSessionObserver { true },
        ).apply {
            openPlatform(PlatformId.PS1)
        }
        val game = firstPs1Game(viewModel)
        viewModel.launch(game)
        waitUntil { viewModel.launchState.value == GameLaunchUiState.IntentSent }
        assertTrue("premise: the launch published its return target", viewModel.pendingLaunchReturnTarget.value != null)

        viewModel.clearPendingLaunchReturnTarget()

        assertNull("nothing is left for the Library's restore to run from", viewModel.pendingLaunchReturnTarget.value)
        assertEquals("and the launch state stays", GameLaunchUiState.IntentSent, viewModel.launchState.value)
    }

    @Test
    fun restoreLaunchReturnTargetStateRestoresGamelistFiltersAndClosesKeyboard() {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
            setSearchQuery("old")
            openSearch()
        }
        val target = LibraryLaunchReturnTarget.gamelistRom(
            openPlatformId = PlatformId.PS1,
            romPlatformId = PlatformId.PS1,
            gameId = GameId("ps1/final-fantasy-vii"),
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.GAMES,
            searchQuery = "Final",
            favoritesOnly = true,
        )

        viewModel.restoreLaunchReturnTargetState(target)

        assertEquals(PlatformId.PS1, viewModel.openPlatform.value)
        assertEquals(LibraryShelfMode.EMULATION, viewModel.shelfMode.value)
        assertEquals("Final", viewModel.searchQuery.value)
        assertTrue(viewModel.favoritesOnly.value)
        assertFalse(viewModel.keyboardOpen.value)
        assertEquals(GameLaunchUiState.Idle, viewModel.launchState.value)
    }

    @Test
    fun aPendingLaunchTargetIsRestoringOnlyFromItsRestoreUntilItIsCleared() {
        // The pending target is set at launch and stays set for the whole game session; a launch that leaves Main interactive (a game on
        // the second screen) must not strip the motion of every Library change made meanwhile (2026-10-14).
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        viewModel.noteNativePackageLaunch("com.example.game")
        val target = requireNotNull(viewModel.pendingLaunchReturnTarget.value)
        assertNull("pending through the session, not restoring", viewModel.restoringLaunchReturnTarget())

        viewModel.restoreLaunchReturnTargetState(target)
        assertTrue("restoring from its restore", viewModel.restoringLaunchReturnTarget() === target)

        viewModel.clearPendingLaunchReturnTarget()
        assertNull("not restoring once cleared at the cover's lift", viewModel.restoringLaunchReturnTarget())

        viewModel.noteNativePackageLaunch("com.example.game")
        assertNull("a new launch of the same game is a new target, not the restored one", viewModel.restoringLaunchReturnTarget())
    }

    @Test
    fun restoreLaunchReturnTargetStateRestoresNativeFavoritesSegment() {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
            setSearchQuery("old")
            openSearch()
        }
        val target = LibraryLaunchReturnTarget.gamelistNative(
            openPlatformId = LibraryViewModel.FAVORITES_PLATFORM_ID,
            nativePackageName = "com.example.mail",
            shelfMode = LibraryShelfMode.ANDROID_GAMES,
            favoriteSegment = FavoriteSegment.ANDROID_APPS,
            searchQuery = "mail",
        )

        viewModel.restoreLaunchReturnTargetState(target)

        assertEquals(LibraryViewModel.FAVORITES_PLATFORM_ID, viewModel.openPlatform.value)
        assertEquals(LibraryShelfMode.ANDROID_GAMES, viewModel.shelfMode.value)
        assertEquals(FavoriteSegment.ANDROID_APPS, viewModel.favoriteSegment.value)
        assertEquals("mail", viewModel.searchQuery.value)
        assertFalse(viewModel.favoritesOnly.value)
        assertFalse(viewModel.keyboardOpen.value)
    }

    /**
     * The CAROUSEL view is the Home shelf  and has no seat under the strip: DOWN stays on the game even when a
     * media seat happens to be registered in the scope (the LIST view registers it). Enrich and Customize media are on Y and in the Edit menu.
     */
    @Test
    fun gamelistCarouselDownStaysOnTheGameBecauseTheShelfHasNothingUnderTheStrip() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        assertEquals(GamelistViewMode.CAROUSEL, viewModel.gamelistViewMode.value)
        val focusedGame = viewModel.games.first { games ->
            games.any { it.gameId.value == "ps1/final-fantasy-vii" }
        }.first { it.gameId.value == "ps1/final-fantasy-vii" }
        val gameFocusKey = FocusKey("library/gamelist/${focusedGame.gameId.value}")
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(gameFocusKey, GamelistScope, FocusBounds(0f, 0f, 100f, 100f))
            register(GamelistGetArtKey, GamelistScope, FocusBounds(0f, 200f, 100f, 300f))
            requestFocus(gameFocusKey)
        }
        fun dispatch(action: ControllerAction) = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = action,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue("DOWN is consumed", dispatch(ControllerAction.NAV_DOWN))
        assertEquals("and the game keeps focus: the shelf has no seat to walk down to", gameFocusKey, authority.state.value.focused)
        assertNull("no game is parked as the media seat's owner", viewModel.detailGame.value)
    }

    @Test
    fun pageShouldersToggleViewWhileTriggerPreservesViewMode() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val games = viewModel.games.first { it.size >= 2 }
        val anchor = games[1]
        val anchorKey = gameKeyForTest(anchor)
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            games.forEachIndexed { index, game ->
                register(
                    gameKeyForTest(game),
                    GamelistScope,
                    FocusBounds(0f, index * 100f, 100f, (index + 1) * 100f),
                )
            }
            requestFocus(anchorKey)
        }
        val platformBefore = viewModel.openPlatform.value
        assertEquals(GamelistViewMode.CAROUSEL, viewModel.gamelistViewMode.value)

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.PAGE_RIGHT,
                onExit = {},
                onRescanFolder = {},
            ),
        )
        assertEquals(GamelistViewMode.LIST, viewModel.gamelistViewMode.value)
        assertEquals(platformBefore, viewModel.openPlatform.value)
        assertEquals(anchorKey, authority.state.value.focused)
        assertEquals(anchor.gameId, viewModel.rememberedGamelistFocus(favoritesOnly = false))

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.PAGE_LEFT,
                onExit = {},
                onRescanFolder = {},
            ),
        )
        assertEquals(GamelistViewMode.CAROUSEL, viewModel.gamelistViewMode.value)
        assertEquals(platformBefore, viewModel.openPlatform.value)
        assertEquals(anchorKey, authority.state.value.focused)

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.TRIGGER_RIGHT,
                onExit = {},
                onRescanFolder = {},
            ),
        )
        assertEquals(GamelistViewMode.CAROUSEL, viewModel.gamelistViewMode.value)
    }

    @Test
    fun pageViewSwapFromMediaSeatRestoresTheOwningGameFocus() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val game = viewModel.games.first { it.isNotEmpty() }.first()
        val gameKey = gameKeyForTest(game)
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(gameKey, GamelistScope, FocusBounds(0f, 0f, 100f, 100f))
            register(GamelistGetArtKey, GamelistScope, FocusBounds(200f, 0f, 300f, 100f))
            requestFocus(GamelistGetArtKey)
        }
        viewModel.focusMetadata(game)

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.PAGE_RIGHT,
                onExit = {},
                onRescanFolder = {},
            ),
        )

        assertEquals(GamelistViewMode.LIST, viewModel.gamelistViewMode.value)
        assertEquals(gameKey, authority.state.value.focused)
        assertEquals(game.gameId, viewModel.rememberedGamelistFocus(favoritesOnly = false))
    }

    @Test
    fun listViewNavRightEntersMediaChipAndNavLeftRestoresOwningGame() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
            toggleGamelistViewMode()
        }
        assertEquals(GamelistViewMode.LIST, viewModel.gamelistViewMode.value)
        val focusedGame = viewModel.games.first { it.isNotEmpty() }.first()
        val gameFocusKey = FocusKey("library/gamelist/${focusedGame.gameId.value}")
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(gameFocusKey, GamelistScope, FocusBounds(0f, 0f, 100f, 100f))
            register(GamelistGetArtKey, GamelistScope, FocusBounds(200f, 0f, 300f, 100f))
            requestFocus(gameFocusKey)
        }
        fun dispatch(action: ControllerAction) = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = action,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(dispatch(ControllerAction.NAV_RIGHT))
        assertEquals(GamelistGetArtKey, authority.state.value.focused)
        assertEquals(focusedGame.gameId, viewModel.detailGame.value?.gameId)

        assertTrue(dispatch(ControllerAction.NAV_LEFT))
        assertEquals(gameFocusKey, authority.state.value.focused)
    }

    @Test
    fun confirmOnStableMediaSeatEnrichesOnceThenOpensHeroCustomization() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val game = viewModel.games.first { it.isNotEmpty() }.first()
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(GamelistGetArtKey, GamelistScope, FocusBounds(0f, 200f, 100f, 300f))
            requestFocus(GamelistGetArtKey)
        }
        val enriched = mutableListOf<GameRow>()
        val customized = mutableListOf<GameRow>()
        fun confirm() = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onFetchArt = { enriched += it },
            onCustomizeHero = { customized += it },
        )

        viewModel.focusMetadata(game)
        assertTrue(confirm())
        assertEquals(listOf(game), enriched)
        assertTrue(customized.isEmpty())

        val verified = game.copy(descriptionSource = "screenscraper")
        viewModel.focusMetadata(verified)
        assertTrue(confirm())
        assertEquals(listOf(game), enriched)
        assertEquals(listOf(verified), customized)
    }

    /**
     * RESTATED 2026-08-25. SELECT used to open achievements here ; it now marks the
     * favorite, because achievements already live in Settings. What this test is really for is the
     * SECOND half of its own name -- that SELECT never falls through into hide or into the detail card
     * -- and that property is untouched by which action the key carries.
     */
    @Test
    fun systemSelectOnEmulatedGamelistFavoritesWithoutHidingOrLaunching() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val focusedGame = viewModel.games.first { games ->
            games.any { it.gameId.value == "ps1/final-fantasy-vii" }
        }.first { it.gameId.value == "ps1/final-fantasy-vii" }
        val gameFocusKey = FocusKey("library/gamelist/${focusedGame.gameId.value}")
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(gameFocusKey, GamelistScope, FocusBounds(0f, 0f, 100f, 100f))
            requestFocus(gameFocusKey)
        }
        val opened = mutableListOf<GameRow>()
        val openedDetail = mutableListOf<LibraryDetailSession>()
        fun dispatch(action: ControllerAction) = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = action,
            onExit = {},
            onRescanFolder = {},
            onOpenAchievements = { opened += it },
            onOpenLibraryDetail = { openedDetail += it },
        )

        assertTrue(dispatch(ControllerAction.SYSTEM_SELECT))

        assertTrue(
            "achievements must NOT open on SELECT any more -- they are reached from Settings and from " +
                "the detail card's own Achievements action",
            opened.isEmpty(),
        )
        // The favorite WRITE itself is pinned by the two neighbouring tests, which collect the repo flow
        // properly. Asserting it from `games.value` here would be reading a StateFlow that has no
        // collector in this test -- a green that means "nothing emitted yet", not "nothing happened".
        assertTrue(openedDetail.isEmpty())
        assertNull(viewModel.pendingHideGame.value)
        assertEquals(GamelistScope, authority.state.value.scope)
        assertEquals(gameFocusKey, authority.state.value.focused)
    }

    @Test
    fun systemStartOnEmulatedGamelistEntersExistingHideConfirmation() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val focusedGame = firstPs1Game(viewModel)
        val gameFocusKey = gameKeyForTest(focusedGame)
        val authority = DefaultFocusAuthority().withRegistered(gameFocusKey, GamelistScope)

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.SYSTEM_START,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(handled)
        assertEquals(focusedGame.gameId, viewModel.pendingHideGame.value?.gameId)
        assertEquals(HideConfirmScope, authority.state.value.scope)
    }

    @Test
    fun nativeFavoriteSelectIsConsumedWithoutHideAndStartEntersNativeHideConfirmation() {
        val packageName = "com.example.game"
        val row = nativeRow(packageName, isGame = true)
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openFavorites(FavoriteSegment.ANDROID_GAMES)
        }
        val authority = DefaultFocusAuthority().withRegistered(nativeShelfKey(packageName), GamelistScope)
        val requestedHides = mutableListOf<String>()
        var achievementOpens = 0
        fun dispatch(action: ControllerAction) = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = action,
            onExit = {},
            onRescanFolder = {},
            onOpenAchievements = { achievementOpens++ },
            onRequestHideNativePackage = { requestedHides += it },
            nativeFavoriteGameRows = listOf(row),
        )

        assertTrue(dispatch(ControllerAction.SYSTEM_SELECT))
        assertTrue(requestedHides.isEmpty())
        assertEquals(0, achievementOpens)
        assertEquals(GamelistScope, authority.state.value.scope)

        assertTrue(dispatch(ControllerAction.SYSTEM_START))
        assertEquals(listOf(packageName), requestedHides)
        assertEquals(NativeHideConfirmScope, authority.state.value.scope)
    }

    /**
     * RESTATED 2026-08-25 — the "intentional" asymmetry this pinned no longer has anything to except.
     *
     * It existed because the gamelist's SELECT meant achievements, so the native shelf's SELECT could
     * not agree with it whatever it did. Both have since moved: SELECT marks a favorite everywhere and
     * hide is on START, where every gamelist has kept it all along. on the two modes
     * one shoulder-press apart: *"the opposite grammar one mode-switch from the library"*.
     *
     * Hide is still pinned — one line down, on the key that now owns it.
     */
    @Test
    fun nativeShelfSelectMarksAFavoriteAndStartOwnsHide() {
        val packageName = "com.example.game"
        fun viewModel() = LibraryViewModel(FakeLibraryRepository()).apply { cycleShelfMode(1) }

        val favorited = mutableListOf<String>()
        val selectAuthority = DefaultFocusAuthority()
            .withRegistered(nativeShelfKey(packageName), LibraryShelfScope)
        assertTrue(
            dispatchToLibrary(
                authority = selectAuthority,
                viewModel = viewModel(),
                action = ControllerAction.SYSTEM_SELECT,
                onExit = {},
                onRescanFolder = {},
                onToggleNativeFavorite = { favorited += it },
                onRequestHideNativePackage = { error("SELECT must not hide any more") },
            ),
        )
        assertEquals(listOf(packageName), favorited)
        assertEquals(
            "marking a favorite must not push the destructive confirm scope",
            LibraryShelfScope,
            selectAuthority.state.value.scope,
        )

        val requestedHides = mutableListOf<String>()
        val startAuthority = DefaultFocusAuthority()
            .withRegistered(nativeShelfKey(packageName), LibraryShelfScope)
        assertTrue(
            dispatchToLibrary(
                authority = startAuthority,
                viewModel = viewModel(),
                action = ControllerAction.SYSTEM_START,
                onExit = {},
                onRescanFolder = {},
                onRequestHideNativePackage = { requestedHides += it },
            ),
        )
        assertEquals("hide survives, on START", listOf(packageName), requestedHides)
        assertEquals(NativeHideConfirmScope, startAuthority.state.value.scope)
    }

    /**
     * What Y did in one dispatch: the games the media menu opened on and the games Enrich was asked for. A fresh view model on [mode],
     * the FF7 row focused in the gamelist, and the media seat registered only when [seatRegistered] (the LIST preview panel registers it;
     * the game shelf has no such seat).
     */
    private class YOutcome(val focused: GameRow, val mediaMenu: List<GameRow>, val fetched: List<GameRow>, val scope: FocusScope)

    private fun pressYInGamelist(mode: GamelistViewMode, seatRegistered: Boolean): YOutcome = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { openPlatform(PlatformId.PS1) }
        if (viewModel.gamelistViewMode.value != mode) viewModel.toggleGamelistViewMode()
        assertEquals(mode, viewModel.gamelistViewMode.value)
        val focusedGame = viewModel.games.first { games ->
            games.any { it.gameId.value == "ps1/final-fantasy-vii" }
        }.first { it.gameId.value == "ps1/final-fantasy-vii" }
        val key = FocusKey("library/gamelist/${focusedGame.gameId.value}")
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(key, GamelistScope, FocusBounds(0f, 0f, 100f, 100f))
            if (seatRegistered) register(GamelistGetArtKey, GamelistScope, FocusBounds(0f, 320f, 200f, 360f))
            requestFocus(key)
        }
        val mediaMenu = mutableListOf<GameRow>()
        val fetched = mutableListOf<GameRow>()
        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.TERTIARY,
                onExit = {},
                onRescanFolder = {},
                onFetchArt = { fetched += it },
                onOpenMediaMenuForFocused = { mediaMenu += it },
            ),
        )
        YOutcome(focusedGame, mediaMenu, fetched, authority.state.value.scope)
    }

    /**
     * The shortcut Y opens Customize media on the focused game, and which of the two things it opens is exact, not "one of them": in the
     * CAROUSEL (the game shelf, which has no media seat) it is always the menu and never Enrich, even for a game the provider has never
     * been asked about, because with no seat on screen there is nothing for the shortcut to disagree with. The pin asserts the CALLBACKS, so a future change that re-points Y at
     * navigation, or lets the shelf offer Enrich, fails here rather than on the reference device.
     */
    @Test
    fun gamelistTertiaryOpensCustomizeMediaOnTheFocusedGame() {
        val outcome = pressYInGamelist(GamelistViewMode.CAROUSEL, seatRegistered = false)
        assertTrue(
            "the fixture is a game the provider never answered for, so Enrich WOULD be offered if Y asked",
            outcome.focused.providerEnrichmentState().shouldOfferEnrich,
        )
        assertEquals(
            "Y opens Customize media on the focused game",
            listOf(outcome.focused.gameId),
            outcome.mediaMenu.map { it.gameId },
        )
        assertEquals("and does not ask the provider (Enrich is not offered on the shelf)", emptyList<GameRow>(), outcome.fetched)
        assertEquals(
            "and it must not have left the gamelist scope -- Y is not navigation",
            GamelistScope,
            outcome.scope,
        )
    }

    /** LIST with its media seat on screen: Y runs the seat's own branch, Enrich first while the provider has never answered. */
    @Test
    fun yInTheListWithTheMediaSeatOffersEnrichFirst() {
        val outcome = pressYInGamelist(GamelistViewMode.LIST, seatRegistered = true)
        assertTrue("the fixture is a game Enrich is offered on", outcome.focused.providerEnrichmentState().shouldOfferEnrich)
        assertEquals(listOf(outcome.focused.gameId), outcome.fetched.map { it.gameId })
        assertEquals("Enrich first, so no menu", emptyList<GameRow>(), outcome.mediaMenu)
        assertEquals(GamelistScope, outcome.scope)
    }

    /** LIST without its seat (compact paints no preview panel): Y opens Customize media outright, even where Enrich would be offered. */
    @Test
    fun yInTheListWithNoSeatOpensCustomizeMediaOutright() {
        val outcome = pressYInGamelist(GamelistViewMode.LIST, seatRegistered = false)
        assertTrue("the fixture is a game Enrich would be offered on with a seat", outcome.focused.providerEnrichmentState().shouldOfferEnrich)
        assertEquals(listOf(outcome.focused.gameId), outcome.mediaMenu.map { it.gameId })
        assertEquals(emptyList<GameRow>(), outcome.fetched)
    }

    /** Y on an ANDROID shelf tile opens that package's media menu, as it does on an emulation tile. */
    @Test
    fun nativeShelfTertiaryOpensThePackagesMediaMenu() {
        val packageName = "com.example.game"
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { cycleShelfMode(1) }
        val authority = DefaultFocusAuthority().withRegistered(nativeShelfKey(packageName), LibraryShelfScope)
        val opened = mutableListOf<String>()

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.TERTIARY,
                onExit = {},
                onRescanFolder = {},
                onToggleNativeFavorite = { error("Y is the media menu now, not the favorite") },
                onOpenNativeAppTileMenu = { opened += it },
            ),
        )
        assertEquals(listOf(packageName), opened)
    }

    @Test
    fun rightAndLeftMoveThroughTheFiniteVirtualCoverflowGameList() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val games = viewModel.games.first { it.size >= 2 }
        val ringKeys = games.map { FocusKey("library/gamelist/${it.gameId.value}") }
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            // The carousel registers the whole list virtually so requestFocus can land off-window.
            ringKeys.forEachIndexed { index, key ->
                registerVirtual(key, GamelistScope, FocusBounds(index * 100f, 0f, index * 100f + 100f, 100f))
            }
            requestFocus(ringKeys.first())
        }

        // RIGHT advances one game.
        dispatchToLibrary(authority, viewModel, ControllerAction.NAV_RIGHT, onExit = {}, onRescanFolder = {})
        assertEquals(ringKeys[1], authority.state.value.focused)

        // LEFT at index 0 clamps at the first game.
        authority.requestFocus(ringKeys.first())
        dispatchToLibrary(authority, viewModel, ControllerAction.NAV_LEFT, onExit = {}, onRescanFolder = {})
        assertEquals(ringKeys.first(), authority.state.value.focused)
    }

    @Test
    fun tappingGameCardFocusesItsVirtualRingKeyBeforeOpening() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val games = viewModel.games.first { it.size >= 2 }
        val ringKeys = games.map(::gameKey)
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            ringKeys.forEachIndexed { index, key ->
                registerVirtual(key, GamelistScope, FocusBounds(index * 100f, 0f, index * 100f + 100f, 100f))
            }
            requestFocus(ringKeys.first())
        }
        val tappedKey = ringKeys.last()
        var focusedWhenOpened: FocusKey? = null

        activateGamelistGameCard(authority, tappedKey) {
            focusedWhenOpened = authority.state.value.focused
        }

        assertEquals(tappedKey, authority.state.value.focused)
        assertEquals(tappedKey, focusedWhenOpened)
        assertEquals(ringKeys, authority.registeredKeysInScope(GamelistScope))
    }

    @Test
    fun confirmOnDetailProviderOpensPickerAndCancelReturnsToExactMetadataRow() = runBlocking {
        val launchPort = RecordingLaunchPort()
        val viewModel = LibraryViewModel(FakeLibraryRepository(), launchPort).apply {
            openPlatform(PlatformId.PS1)
        }
        val focusedGame = firstPs1Game(viewModel)
        val gameFocusKey = gameKeyForTest(focusedGame)
        val authority = openWithAuthority(gameFocusKey)
        viewModel.openGameDetail(focusedGame)

        val confirmHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(confirmHandled)
        assertEquals(OpenWithScope, authority.state.value.scope)
        waitUntil { viewModel.openWith.value != null }

        val cancelHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CANCEL,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(cancelHandled)
        assertEquals(GameDetailScope, authority.state.value.scope)
        assertEquals(LibraryDetailOpenWithKey, authority.state.value.focused)
        // The picker is the Open-with scope, popped above; the loaded options stay, so the card keeps naming its emulator .
        assertNotNull(viewModel.openWith.value)
    }

    /**
     * CONFIRM on the Library detail launch card's title-edit glyph opens the
     * rename overlay for the FOCUSED game — never a stale or wrong target.
     */
    @Test
    fun confirmOnTitleEditGlyphOpensRenameForTheFocusedGame() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val focusedGame = firstPs1Game(viewModel)
        val authority = openWithAuthority(gameKeyForTest(focusedGame)).apply {
            registerVirtual(LibraryDetailTitleEditKey, GameDetailScope, FocusBounds(-100f, 200f, -10f, 260f))
            requestFocus(LibraryDetailTitleEditKey)
        }
        viewModel.openGameDetail(focusedGame)

        var edited: GameRow? = null
        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onEditGameTitle = { target -> edited = target.game },
        )

        assertTrue(handled)
        assertEquals(focusedGame.gameId, edited?.gameId)
        // CONFIRM elsewhere on the card must NEVER open rename (the Play/OpenWith/Achievements CONFIRM
        // arms are unconditional `else` branches keyed on `authority.state.value.focused`).
        authority.requestFocus(LibraryDetailPlayKey)
        edited = null
        dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onEditGameTitle = { target -> edited = target.game },
        )
        assertNull(edited)
    }

    @Test
    fun detailOpenWithFollowsPlayInTheHorizontalActionOrder() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository(), RecordingLaunchPort()).apply {
            openPlatform(PlatformId.PS1)
        }
        val focusedGame = firstPs1Game(viewModel)
        val authority = openWithAuthority(gameKeyForTest(focusedGame)).apply {
            requestFocus(LibraryDetailPlayKey)
        }
        viewModel.openGameDetail(focusedGame)

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.NAV_RIGHT,
                onExit = {},
                onRescanFolder = {},
            ),
        )
        assertEquals(LibraryDetailOpenWithKey, authority.state.value.focused)

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.NAV_RIGHT,
                onExit = {},
                onRescanFolder = {},
            ),
        )
        assertEquals(LibraryDetailAchievementsKey, authority.state.value.focused)

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.NAV_LEFT,
                onExit = {},
                onRescanFolder = {},
            ),
        )
        assertEquals(LibraryDetailOpenWithKey, authority.state.value.focused)
    }

    @Test
    fun installedOpenWithCandidateCommitsAndReturnsToDetailMetadata() = runBlocking {
        val launchPort = RecordingLaunchPort()
        val viewModel = LibraryViewModel(FakeLibraryRepository(), launchPort).apply {
            openPlatform(PlatformId.PS1)
        }
        val focusedGame = firstPs1Game(viewModel)
        val gameFocusKey = gameKeyForTest(focusedGame)
        val installedKey = openWithFocusKey(launchPort.installed.emulatorId.value)
        val authority = openWithAuthority(gameFocusKey)
        viewModel.openGameDetail(focusedGame)
        dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
        )
        waitUntil { viewModel.openWith.value != null }
        authority.requestFocus(installedKey)

        val chooseHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(chooseHandled)
        assertEquals(GameDetailScope, authority.state.value.scope)
        assertEquals(LibraryDetailOpenWithKey, authority.state.value.focused)
        waitUntil { launchPort.chosenOverride == launchPort.installed.emulatorId }
        // Choosing pops the scope and writes the override; the options are NOT cleared (they are loaded again after the write), so the
        // card never falls back to Automatic in between .
        assertNotNull(viewModel.openWith.value)
    }

    @Test
    fun notInstalledOpenWithCandidateStaysInModalAndDoesNotCommit() = runBlocking {
        val launchPort = RecordingLaunchPort()
        val viewModel = LibraryViewModel(FakeLibraryRepository(), launchPort).apply {
            openPlatform(PlatformId.PS1)
        }
        val focusedGame = firstPs1Game(viewModel)
        val gameFocusKey = gameKeyForTest(focusedGame)
        val missingKey = openWithFocusKey(launchPort.notInstalled.emulatorId.value)
        val authority = openWithAuthority(gameFocusKey)
        viewModel.openGameDetail(focusedGame)
        dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
        )
        waitUntil { viewModel.openWith.value != null }
        authority.requestFocus(missingKey)

        val chooseHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(chooseHandled)
        assertEquals(OpenWithScope, authority.state.value.scope)
        assertEquals(missingKey, authority.state.value.focused)
        // Round 5e: the null below is final only because of this check. A commit (chooseEmulator or resetEmulator) pops the Open-with
        // scope on this thread right after it starts its dispatcher write, so a scope that is still OpenWithScope (asserted above)
        // proves no write was started. A bare null check straight after the press could only pass. (The options are loaded data now,
        // not the picker flag, so they no longer say anything about it.)
        assertTrue(viewModel.openWith.value?.candidates?.any { !it.installed } == true)
        assertNull(launchPort.chosenOverride)
    }

    @Test
    fun installedButUnwiredOpenWithCandidateStaysInModalAndDoesNotCommit() = runBlocking {
        val launchPort = RecordingLaunchPort()
        val viewModel = LibraryViewModel(FakeLibraryRepository(), launchPort).apply {
            openPlatform(PlatformId.PS1)
        }
        val focusedGame = firstPs1Game(viewModel)
        val unwiredKey = openWithFocusKey(launchPort.unwired.emulatorId.value)
        val authority = openWithAuthority(gameKeyForTest(focusedGame))
        viewModel.openGameDetail(focusedGame)
        dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
        )
        waitUntil { viewModel.openWith.value != null }
        authority.requestFocus(unwiredKey)

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.CONFIRM,
                onExit = {},
                onRescanFolder = {},
            ),
        )
        assertEquals(OpenWithScope, authority.state.value.scope)
        // As in the not-installed case: the scope still being OpenWithScope (asserted above) proves no override write was started,
        // which makes the null below final instead of early.
        assertTrue(
            "the press must not have closed the picker to start an override write",
            viewModel.openWith.value?.candidates?.isNotEmpty() == true,
        )
        assertNull(launchPort.chosenOverride)
        assertFalse(launchPort.unwired.selectable)
    }

    @Test
    fun removingLastRomFavoriteFallsThroughToNativeFavoriteSegment() {
        assertEquals(
            FavoriteSegment.ANDROID_GAMES,
            nativeFallbackFavoriteSegment(
                nativeFavoriteGameRows = listOf(nativeRow("com.example.game", isGame = true)),
                nativeFavoriteAppRows = listOf(nativeRow("com.example.app", isGame = false)),
            ),
        )
        assertEquals(
            FavoriteSegment.ANDROID_APPS,
            nativeFallbackFavoriteSegment(
                nativeFavoriteGameRows = emptyList(),
                nativeFavoriteAppRows = listOf(nativeRow("com.example.app", isGame = false)),
            ),
        )
        assertEquals(
            null,
            nativeFallbackFavoriteSegment(
                nativeFavoriteGameRows = emptyList(),
                nativeFavoriteAppRows = emptyList(),
            ),
        )
    }

    @Test
    fun unfavoritingNonLastVisibleGameSelectsStableSurvivorBeforeReactiveRemoval() = runBlocking {
        val repository = FakeLibraryRepository().apply {
            setFavorite(GameId("ps1/final-fantasy-vii"), true)
            setFavorite(GameId("ps1/crash-bandicoot"), true)
        }
        val viewModel = LibraryViewModel(repository).apply { openFavorites() }
        val games = viewModel.games.first { it.size == 2 }
        val removed = games.first()
        val survivor = games[1]
        val removedKey = gameKeyForTest(removed)
        val survivorKey = gameKeyForTest(survivor)
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            games.forEachIndexed { index, game ->
                registerVirtual(
                    gameKeyForTest(game),
                    GamelistScope,
                    FocusBounds(index * 100f, 0f, index * 100f + 80f, 100f),
                )
            }
            requestFocus(removedKey)
        }
        viewModel.focusMetadata(removed)

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                // Key changed 2026-08-25 (Y -> SELECT); the behaviour pinned below did not.
                action = ControllerAction.SYSTEM_SELECT,
                onExit = {},
                onRescanFolder = {},
            ),
        )

        assertEquals(survivorKey, authority.state.value.focused)
        assertEquals(survivorKey, authority.rememberedFocusIn(GamelistScope))
        assertEquals(survivor.gameId, viewModel.detailGame.value?.gameId)
        waitUntil { viewModel.games.value.none { it.gameId == removed.gameId } }
        assertEquals(survivorKey, authority.state.value.focused)
    }

    private fun dispatchStart(authority: FocusAuthority): Boolean =
        dispatchToLibrary(
            authority = authority,
            viewModel = LibraryViewModel(FakeLibraryRepository()),
            action = ControllerAction.SYSTEM_START,
            onExit = {},
            onRescanFolder = {},
        )

    private fun DefaultFocusAuthority.withRegistered(
        key: FocusKey,
        scope: FocusScope,
    ): DefaultFocusAuthority = apply {
        if (scope != FocusScope.Root) pushScope(scope)
        register(key, scope, FocusBounds(0f, 0f, 100f, 100f))
    }

    private fun DefaultFocusAuthority.registerVirtualShelfRing(keys: List<FocusKey>) {
        virtualFocusRowSlots(keys).forEach { slot ->
            registerVirtual(slot.key, LibraryShelfScope, slot.bounds)
        }
    }

    private fun nativeRow(packageName: String, isGame: Boolean): NativeShelfRow =
        NativeShelfRow(
            packageName = packageName,
            label = packageName.substringAfterLast('.'),
            launchActivity = null,
            isGame = isGame,
            isFavorite = true,
        )

    private fun platformShelfKeyForTest(id: PlatformId): FocusKey = FocusKey("library/shelf/${id.value}")

    private suspend fun firstPs1Game(viewModel: LibraryViewModel) =
        viewModel.games.first { games -> games.any { it.platformId == PlatformId.PS1 } }
            .first { it.platformId == PlatformId.PS1 }

    private fun gameKeyForTest(game: GameRow): FocusKey =
        FocusKey("library/gamelist/${game.gameId.value}")

    private fun openWithAuthority(gameFocusKey: FocusKey): DefaultFocusAuthority =
        DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(gameFocusKey, GamelistScope, FocusBounds(0f, 0f, 100f, 100f))
            requestFocus(gameFocusKey)
            register(LibraryDetailPlayKey, GameDetailScope, FocusBounds(0f, 200f, 100f, 260f))
            register(LibraryDetailOpenWithKey, GameDetailScope, FocusBounds(220f, 200f, 320f, 260f))
            register(LibraryDetailAchievementsKey, GameDetailScope, FocusBounds(330f, 200f, 430f, 260f))
            pushScope(GameDetailScope)
            requestFocus(LibraryDetailOpenWithKey)
            register(OpenWithAutoKey, OpenWithScope, FocusBounds(0f, 0f, 100f, 50f))
            register(
                openWithFocusKey(RecordingLaunchPort.INSTALLED_ID.value),
                OpenWithScope,
                FocusBounds(0f, 60f, 100f, 110f),
            )
            register(
                openWithFocusKey(RecordingLaunchPort.NOT_INSTALLED_ID.value),
                OpenWithScope,
                FocusBounds(0f, 120f, 100f, 170f),
            )
            register(
                openWithFocusKey(RecordingLaunchPort.UNWIRED_ID.value),
                OpenWithScope,
                FocusBounds(0f, 180f, 100f, 230f),
            )
        }

    private suspend fun waitUntil(timeoutMs: Long = 1_000, condition: () -> Boolean) {
        val started = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - started > timeoutMs) {
                fail("Condition was not met within ${timeoutMs}ms")
            }
            delay(10)
        }
    }

    private fun intentSentResult(game: GameRow): LaunchResult =
        LaunchResult(
            readiness = LaunchReadiness.Ready(
                LaunchTarget(
                    gameId = game.gameId,
                    platformId = game.platformId,
                    emulator = EmulatorProfile(
                        id = RecordingLaunchPort.INSTALLED_ID,
                        packageName = "com.duckstation",
                        displayName = "DuckStation",
                        installed = true,
                        capability = EmulatorCapability.DIRECT_PAYLOAD,
                        locatorForm = LaunchLocatorForm.CONTENT_URI,
                        supportedPlatforms = setOf(game.platformId),
                    ),
                    locator = "content://mimir/${game.gameId.value}",
                    locatorForm = LaunchLocatorForm.CONTENT_URI,
                    title = game.title,
                ),
            ),
            outcome = LaunchOutcome.IntentSent,
        )

    private class RecordingLaunchPort(
        private val launchResult: suspend (GameRow) -> LaunchResult = { game ->
            LaunchResult(LaunchReadiness.Unsupported(game.platformId))
        },
    ) : LibraryLaunchPort {
        val installed = OpenWithCandidate(
            emulatorId = INSTALLED_ID,
            label = "DuckStation",
            packageName = "com.github.stenzek.duckstation",
            installed = true,
            isCurrentOverride = false,
            isAutoPick = true,
        )
        val notInstalled = OpenWithCandidate(
            emulatorId = NOT_INSTALLED_ID,
            label = "Missing Emulator",
            packageName = "com.example.missing",
            installed = false,
            isCurrentOverride = false,
            isAutoPick = false,
        )
        val unwired = OpenWithCandidate(
            emulatorId = UNWIRED_ID,
            label = "Unwired Emulator",
            packageName = "com.example.unwired",
            installed = true,
            dispatchSupported = false,
            isCurrentOverride = false,
            isAutoPick = false,
        )
        /** Written on the view model's dispatcher, read on the test thread (round 5e: volatile, so the write is seen). */
        @Volatile
        var chosenOverride: EmulatorId? = null
        var resetCount = 0

        override suspend fun openWithOptions(game: GameRow): OpenWithOptions =
            OpenWithOptions(hasOverride = false, candidates = listOf(installed, notInstalled, unwired))

        override suspend fun setEmulatorOverride(
            game: GameRow,
            candidate: OpenWithCandidate,
        ) {
            chosenOverride = candidate.emulatorId
        }

        override suspend fun clearEmulatorOverride(game: GameRow) {
            resetCount += 1
        }

        /** Every (disc, member) pair this port was handed — 0/null meaning "the group default". */
        val launchedDiscs = mutableListOf<Int>()
        val launchedMembers = mutableListOf<EntryKey?>()

        override suspend fun launch(
            game: GameRow,
            preferredDisc: Int,
            launchMemberEntryKey: EntryKey?,
        ): LaunchResult {
            launchedDiscs += preferredDisc
            launchedMembers += launchMemberEntryKey
            return launchResult(game)
        }

        companion object {
            val INSTALLED_ID = EmulatorId("duckstation")
            val NOT_INSTALLED_ID = EmulatorId("missing.emu")
            val UNWIRED_ID = EmulatorId("unwired.emu")
        }
    }
}
