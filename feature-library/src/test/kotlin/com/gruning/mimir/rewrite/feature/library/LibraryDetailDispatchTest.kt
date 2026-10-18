package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDetailDispatchTest {

    @Test
    fun remountRebuildsRetainedGamelistAndDetailScopesIdempotently() {
        val authority = DefaultFocusAuthority()
        val row = nativeRow("com.example.retained", isGame = true)
        val origin = nativeShelfKey(row.packageName)
        authority.register(
            origin,
            GamelistScope,
            FocusBounds(0f, 0f, 100f, 100f),
        )
        val session = LibraryDetailSession(
            target = LibraryDetailTarget.AndroidGame(row),
            originScope = GamelistScope,
            originFocusKey = origin,
        )

        assertTrue(reconcileLibraryEntryScope(authority, PlatformId.PS1, session))
        assertEquals(GameDetailScope, authority.state.value.scope)
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)

        assertTrue(reconcileLibraryEntryScope(authority, PlatformId.PS1, session))
        assertEquals(GameDetailScope, authority.state.value.scope)
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)
    }

    @Test
    fun controllerBackAndSystemBackRestoreTheExactOriginatingGame() = runBlocking {
        listOf(ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK).forEach { backAction ->
            val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
                openPlatform(PlatformId.PS1)
            }
            val games = viewModel.games.first { it.size >= 2 }
            val origin = games.last()
            val authority = DefaultFocusAuthority().apply {
                pushScope(GamelistScope)
                games.forEachIndexed { index, game ->
                    register(
                        gameKey(game),
                        GamelistScope,
                        FocusBounds(index * 100f, 0f, index * 100f + 90f, 90f),
                    )
                }
                requestFocus(gameKey(origin))
            }

            assertTrue(dispatch(authority, viewModel, ControllerAction.CONFIRM))
            assertEquals(GameDetailScope, authority.state.value.scope)
            registerVisibleDetailActions(authority)
            authority.requestFocus(LibraryDetailOpenWithKey)

            assertTrue(dispatch(authority, viewModel, backAction))
            assertEquals(GamelistScope, authority.state.value.scope)
            assertEquals(gameKey(origin), authority.state.value.focused)
            assertNull(viewModel.openGameDetail.value)
        }
    }

    @Test
    fun androidGameOpensTypedDetailWithOnlyPlayAndOnlyPlayLaunches() {
        val row = nativeRow("com.example.game", isGame = true)
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { cycleShelfMode(1) }
        val authority = authorityAt(LibraryShelfScope, nativeShelfKey(row.packageName))
        var opened: LibraryDetailSession? = null
        val launches = mutableListOf<String>()

        val openHandled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = launches::add,
            nativeGameRows = listOf(row),
            onOpenLibraryDetail = { opened = it },
        )

        assertTrue(openHandled)
        val session = opened
        assertNotNull(session)
        val target = session!!.target as LibraryDetailTarget.AndroidGame
        assertEquals(row.packageName, target.row.packageName)
        assertEquals(LibraryShelfScope, session.originScope)
        assertEquals(nativeShelfKey(row.packageName), session.originFocusKey)
        assertEquals(GameDetailScope, authority.state.value.scope)
        // Favourite is off the card's row : an Android game's card is Play and nothing else.
        assertEquals(listOf(LibraryDetailPlayKey), authority.registeredKeysInScope(GameDetailScope))
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)
        assertEquals(emptyList<String>(), launches)

        // SELECT is Achievements, and an Android game has none: it is answered and does nothing (it no longer toggles a favourite).
        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.SYSTEM_SELECT,
                onExit = {},
                onRescanFolder = {},
                onLaunchNativePackage = launches::add,
                activeDetailTarget = target,
                detailActionState = LibraryDetailActionState(target, isOnShelf = false),
            ),
        )
        assertEquals(emptyList<String>(), launches)
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)

        dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = launches::add,
            activeDetailTarget = target,
            detailActionState = LibraryDetailActionState(target, isOnShelf = true),
        )

        assertEquals(listOf(row.packageName), launches)
    }

    @Test
    fun nonGameNativeRowRejectsDetailBeforeCallbackOrScopeChange() {
        // ANDROID_APPS retired 2026-08-21: apps no longer have their own shelf lane, so this test now pins
        // the generic fallback inside the ANDROID_GAMES CONFIRM branch instead -- toLibraryDetailTargetOrNull()
        // fails closed for any row whose isGame is false (NativeShelfModels.kt), and CONFIRM on such a row
        // falls back to a direct launch rather than opening typed detail. nativeGameRows normally holds only
        // isGame=true rows; this exercises the defensive fallback the dispatcher still carries for that case.
        val row = nativeRow("com.example.app", isGame = false)
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { cycleShelfMode(1) }
        val authority = authorityAt(LibraryShelfScope, nativeShelfKey(row.packageName))
        val launches = mutableListOf<String>()
        var detailOpened = false

        val handled = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = launches::add,
            nativeGameRows = listOf(row),
            onOpenLibraryDetail = { detailOpened = true },
        )

        assertTrue(handled)
        assertFalse(detailOpened)
        assertEquals(LibraryShelfScope, authority.state.value.scope)
        assertEquals(nativeShelfKey(row.packageName), authority.state.value.focused)
        assertEquals(listOf(row.packageName), launches)
        assertFalse(requireNotNull(viewModel.pendingLaunchReturnTarget.value).restoreLaunchCard)
    }

    @Test
    fun primaryLaunchCardRegistersAndDispatchesPlayAndBackControllerChain() {
        val row = nativeRow("com.example.game", isGame = true)
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { cycleShelfMode(1) }
        val authority = authorityAt(LibraryShelfScope, nativeShelfKey(row.packageName))
        val launches = mutableListOf<String>()
        var opened: LibraryDetailSession? = null
        var detailState: LibraryDetailActionState? = null

        assertTrue(dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = launches::add,
            nativeGameRows = listOf(row),
            onOpenLibraryDetail = { session ->
                opened = session
                detailState = LibraryDetailActionState(session.target, isOnShelf = false)
            },
        ))

        val session = opened
        assertNotNull(session)
        val target = session!!.target as LibraryDetailTarget.AndroidGame
        assertEquals(row.packageName, target.row.packageName)
        assertEquals(LibraryShelfScope, session.originScope)
        assertEquals(nativeShelfKey(row.packageName), session.originFocusKey)
        assertEquals(GameDetailScope, authority.state.value.scope)
        assertEquals(emptyList<String>(), launches)
        assertEquals(listOf(LibraryDetailPlayKey), authority.registeredKeysInScope(GameDetailScope))
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)

        fun dispatchDetail(action: ControllerAction): Boolean = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = action,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = launches::add,
            activeDetailTarget = target,
            detailActionState = requireNotNull(detailState),
        )

        // The row is Play alone: LEFT and RIGHT have nowhere to go, and neither launches.
        assertTrue(dispatchDetail(ControllerAction.NAV_RIGHT))
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)
        assertTrue(dispatchDetail(ControllerAction.NAV_LEFT))
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)
        assertEquals(emptyList<String>(), launches)

        assertTrue(dispatchDetail(ControllerAction.CONFIRM))
        assertEquals(listOf(row.packageName), launches)
        assertTrue(requireNotNull(viewModel.pendingLaunchReturnTarget.value).restoreLaunchCard)

        assertTrue(dispatchDetail(ControllerAction.CANCEL))
        assertEquals(LibraryShelfScope, authority.state.value.scope)
        assertEquals(nativeShelfKey(row.packageName), authority.state.value.focused)
        assertEquals(emptyList<FocusKey>(), authority.registeredKeysInScope(GameDetailScope))
    }

    @Test
    fun emulatedGameOpensPrimaryLaunchCardWithActionFocusBeforePlay() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val game = viewModel.games.first { it.isNotEmpty() }.first()
        val authority = authorityAt(GamelistScope, gameKey(game))
        var opened: LibraryDetailSession? = null

        assertTrue(dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onOpenLibraryDetail = { opened = it },
        ))

        assertEquals(game.gameId, (opened?.target as? LibraryDetailTarget.Emulated)?.game?.gameId)
        assertEquals(gameKey(game), opened?.originFocusKey)
        assertEquals(GameDetailScope, authority.state.value.scope)
        assertEquals(
            listOf(
                LibraryDetailTitleEditKey,
                LibraryDetailPlayKey,
                LibraryDetailOpenWithKey,
                LibraryDetailAchievementsKey,
            ),
            authority.registeredKeysInScope(GameDetailScope),
        )
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)
        assertEquals(game.gameId, viewModel.openGameDetail.value?.gameId)
        assertEquals(GameLaunchUiState.Idle, viewModel.launchState.value)
    }

    /**
     * The rename glyph  is the FIRST stop, reached with one NAV_LEFT from Play, as on Home's card. The order is Home's order now, by the one
     * dispatcher: Rename, Play, Open with, Achievements, and Restart between Play and Open with while the game is parked (no Favorite on the row).
     */
    @Test
    fun emulatedActionTraversalIsHomesOrderAndRestartJoinsItWhileParked() {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val target = LibraryDetailTarget.Emulated(ladderGameRow())
        for ((parked, expected) in listOf(
            false to listOf(LibraryDetailTitleEditKey, LibraryDetailPlayKey, LibraryDetailOpenWithKey, LibraryDetailAchievementsKey),
            true to listOf(
                LibraryDetailTitleEditKey,
                LibraryDetailPlayKey,
                LibraryDetailRestartKey,
                LibraryDetailOpenWithKey,
                LibraryDetailAchievementsKey,
            ),
        )) {
            val authority = libraryDetailLadderAuthority(parked = parked)
            authority.requestFocus(LibraryDetailTitleEditKey)
            assertEquals("parked=$parked registers the row", expected, authority.registeredKeysInScope(GameDetailScope).filter { it in expected })

            expected.drop(1).forEach { next ->
                assertTrue(dispatchLadder(authority, viewModel, ControllerAction.NAV_RIGHT, target))
                assertEquals("parked=$parked RIGHT", next, authority.state.value.focused)
            }
            // The end of the row: RIGHT stays on Achievements, as it always did.
            assertTrue(dispatchLadder(authority, viewModel, ControllerAction.NAV_RIGHT, target))
            assertEquals(LibraryDetailAchievementsKey, authority.state.value.focused)

            expected.asReversed().drop(1).forEach { previous ->
                assertTrue(dispatchLadder(authority, viewModel, ControllerAction.NAV_LEFT, target))
                assertEquals("parked=$parked LEFT", previous, authority.state.value.focused)
            }
            assertEquals(LibraryDetailTitleEditKey, authority.state.value.focused)
        }
    }

    // ── Vertical DPAD ladder to the rename mark () — mirrors the deterministic
    // ladder HomeLaunchCard.kt's dispatchHomeLaunchCard already has (HomeLaunchCardTest.kt
    // focusedDescriptionReturnsToEditWhenUpReachesTheBeginning / titleEditDownEntersAScrollableDescription-
    // BeforePlay / titleEditDownWithNoDescriptionLandsOnPlay). Every assertion here drives the production
    // LibraryDispatch.kt GameDetailScope NAV_UP/NAV_DOWN branches through the real dispatchToLibrary entry
    // point, never a synthetic shortcut, and never calls authority.moveFocus — only requestFocus, per the
    // discipline this codebase's own BUG-A postmortem requires (physical Description bounds must never be
    // scored against Play/Shelf/OpenWith/Achievements/Edit's virtual bounds in one moveFocus candidate set).

    @Test
    fun playNavUpReachesEditWhenDescriptionNotScrollable() {
        val authority = libraryDetailLadderAuthority()
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val target = LibraryDetailTarget.Emulated(ladderGameRow())

        // CHANGED EDGE (was: no-op, a dead end one rung short of the mark — the observed dead end).
        dispatchLadder(authority, viewModel, ControllerAction.NAV_UP, target)

        assertEquals(LibraryDetailTitleEditKey, authority.state.value.focused)
    }

    @Test
    fun playNavUpReachesDescriptionWhenScrollable() {
        val authority = libraryDetailLadderAuthority()
        registerLadderDescription(authority)
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val target = LibraryDetailTarget.Emulated(ladderGameRow())

        // UNCHANGED: this arm already worked through the pre-existing generic scrollable fallback; newly
        // pinned here rather than rewritten, since no test previously covered it.
        dispatchLadder(
            authority,
            viewModel,
            ControllerAction.NAV_UP,
            target,
            HomeLaunchDescriptionScrollState(scrollable = true),
        )

        assertEquals(LibraryDetailDescriptionKey, authority.state.value.focused)
    }

    @Test
    fun descriptionNavUpReachesEditWhenScrollExhausted() {
        val authority = libraryDetailLadderAuthority()
        registerLadderDescription(authority)
        authority.requestFocus(LibraryDetailDescriptionKey)
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val target = LibraryDetailTarget.Emulated(ladderGameRow())

        // CHANGED EDGE (was: no-op — the previous NAV_UP branch's `canScrollUp` guard had an empty else).
        dispatchLadder(
            authority,
            viewModel,
            ControllerAction.NAV_UP,
            target,
            HomeLaunchDescriptionScrollState(scrollable = true, canScrollUp = false),
        )

        assertEquals(LibraryDetailTitleEditKey, authority.state.value.focused)
    }

    @Test
    fun descriptionNavDownReachesPlayWhenScrollExhausted() {
        val authority = libraryDetailLadderAuthority()
        registerLadderDescription(authority)
        authority.requestFocus(LibraryDetailDescriptionKey)
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val target = LibraryDetailTarget.Emulated(ladderGameRow())

        // UNCHANGED: DOWN out of Description already exited to Play; newly pinned here.
        dispatchLadder(
            authority,
            viewModel,
            ControllerAction.NAV_DOWN,
            target,
            HomeLaunchDescriptionScrollState(scrollable = true, canScrollDown = false),
        )

        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)
    }

    @Test
    fun editNavDownReachesDescriptionWhenScrollable() {
        val authority = libraryDetailLadderAuthority()
        registerLadderDescription(authority)
        authority.requestFocus(LibraryDetailTitleEditKey)
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val target = LibraryDetailTarget.Emulated(ladderGameRow())

        // NEW EDGE (was: no branch handled TitleEdit as a NAV_DOWN source at all — a true dead end, a
        // navigation dead end). Mirror of Play's NAV_UP branch above.
        dispatchLadder(
            authority,
            viewModel,
            ControllerAction.NAV_DOWN,
            target,
            HomeLaunchDescriptionScrollState(scrollable = true),
        )

        assertEquals(LibraryDetailDescriptionKey, authority.state.value.focused)
    }

    @Test
    fun editNavDownReachesPlayWhenDescriptionNotScrollable() {
        val authority = libraryDetailLadderAuthority()
        authority.requestFocus(LibraryDetailTitleEditKey)
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val target = LibraryDetailTarget.Emulated(ladderGameRow())

        // NEW EDGE: same dead end as above, no scrollable description to visit first.
        dispatchLadder(authority, viewModel, ControllerAction.NAV_DOWN, target)

        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)
    }

    /**
     * Sweep 10-09 (UP from ACHIEVEMENTS found nothing): the rest of the button row reaches the rename mark as Play's rung does. Restart (the parked game's button) keeps
     * reaching a scrollable Description first (the pre-existing generic fallback), and with nothing to scroll continues the ladder to the mark.
     */
    @Test
    fun restartUpReachesTheRenameMarkWhenDescriptionIsNotScrollableAndTheDescriptionWhenItIs() {
        val authority = libraryDetailLadderAuthority(parked = true)
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val target = LibraryDetailTarget.Emulated(ladderGameRow())
        authority.requestFocus(LibraryDetailRestartKey)

        dispatchLadder(authority, viewModel, ControllerAction.NAV_UP, target)
        assertEquals(LibraryDetailTitleEditKey, authority.state.value.focused)

        authority.requestFocus(LibraryDetailRestartKey)
        registerLadderDescription(authority)
        dispatchLadder(
            authority,
            viewModel,
            ControllerAction.NAV_UP,
            target,
            HomeLaunchDescriptionScrollState(scrollable = true),
        )
        assertEquals(LibraryDetailDescriptionKey, authority.state.value.focused)
    }

    @Test
    fun openWithAndAchievementsUpReachTheRenameMarkInTheSingleRow() {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val target = LibraryDetailTarget.Emulated(ladderGameRow())
        for (key in listOf(LibraryDetailOpenWithKey, LibraryDetailAchievementsKey)) {
            val authority = libraryDetailLadderAuthority()
            authority.requestFocus(key)
            dispatchLadder(authority, viewModel, ControllerAction.NAV_UP, target)
            assertEquals("UP from $key reaches the rename mark", LibraryDetailTitleEditKey, authority.state.value.focused)
        }
    }

    /** The stacked card's second row still steps UP to the row above it (the geometric neighbour comes first); only a first-row key continues to the mark. */
    @Test
    fun aStackedSecondRowStepsUpToTheRowAboveBeforeTheLadderContinues() {
        val authority = DefaultFocusAuthority()
        registerHomeLaunchCardActions(
            authority,
            emulated = true,
            stacked = true,
            actionRowWidth = 300f,
            contract = LibraryLaunchCardFocusContract,
        )
        authority.pushScope(GameDetailScope)
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val target = LibraryDetailTarget.Emulated(ladderGameRow())

        authority.requestFocus(LibraryDetailAchievementsKey)
        dispatchLadder(authority, viewModel, ControllerAction.NAV_UP, target)
        assertTrue(
            "UP from the second row lands on the first row, not on the mark: ${authority.state.value.focused}",
            authority.state.value.focused == LibraryDetailPlayKey || authority.state.value.focused == LibraryDetailOpenWithKey,
        )

        authority.requestFocus(LibraryDetailOpenWithKey)
        dispatchLadder(authority, viewModel, ControllerAction.NAV_UP, target)
        assertEquals("and UP from the first row continues to the mark", LibraryDetailTitleEditKey, authority.state.value.focused)
    }

    /** Edit is not registered for a non-emulated (Android game) target, so both new branches are gated on
     * `emulated` — vertical nav must stay a no-op, as in the existing behavior, instead of requesting an
     * unregistered key (which is a safe [FocusAuthority.requestFocus] no-op, but must never be relied on). */
    @Test
    fun nonEmulatedDetailVerticalNavStaysInertWithoutTitleEditKey() {
        val authority = libraryDetailLadderAuthority(emulated = false)
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        val target = LibraryDetailTarget.AndroidGame(nativeRow("com.example.nonemulated", isGame = true))

        dispatchLadder(authority, viewModel, ControllerAction.NAV_UP, target)
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)

        dispatchLadder(authority, viewModel, ControllerAction.NAV_DOWN, target)
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)
    }

    private fun libraryDetailLadderAuthority(emulated: Boolean = true, parked: Boolean = false): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority()
        registerHomeLaunchCardActions(
            authority,
            emulated = emulated,
            hasExtraAction = parked,
            contract = LibraryLaunchCardFocusContract,
        )
        authority.pushScope(GameDetailScope)
        authority.requestFocus(LibraryDetailPlayKey)
        return authority
    }

    /** Simulates the physical registration [HomeLaunchCardDescription] performs via `mimirFocusable` once
     * composed — the same synthetic stand-in HomeLaunchCardTest.kt uses for the identical Home-side key. */
    private fun registerLadderDescription(authority: DefaultFocusAuthority) {
        authority.registerVirtual(LibraryDetailDescriptionKey, GameDetailScope, FocusBounds(0f, -100f, 368f, -20f))
    }

    private fun dispatchLadder(
        authority: DefaultFocusAuthority,
        viewModel: LibraryViewModel,
        action: ControllerAction,
        target: LibraryDetailTarget,
        scrollState: HomeLaunchDescriptionScrollState = HomeLaunchDescriptionScrollState(),
        onScroll: (Int) -> Unit = {},
    ): Boolean = dispatchToLibrary(
        authority = authority,
        viewModel = viewModel,
        action = action,
        onExit = {},
        onRescanFolder = {},
        activeDetailTarget = target,
        detailDescriptionScrollState = scrollState,
        onDetailDescriptionScroll = onScroll,
    )

    private fun ladderGameRow() = GameRow(
        gameId = GameId("gba/vertical-ladder"),
        title = "Vertical Ladder Fixture",
        platformId = PlatformId.GBA,
        memberCount = 1,
        duplicateKind = null,
    )

    /**
     * ONE bounds solver for both doors: the Library's lattice is Home's, key for key, in every shape the card paints (one row, two rows; with and without Restart),
     * and nothing of the old hand-written 96/240/368/512 map remains.
     */
    @Test
    fun theLibraryLatticeIsHomesLatticeUnderTheLibraryKeys() {
        val homeToLibrary = mapOf(
            HomeLaunchCardTitleEditKey to LibraryDetailTitleEditKey,
            HomeLaunchCardPlayKey to LibraryDetailPlayKey,
            HomeLaunchCardRestartKey to LibraryDetailRestartKey,
            HomeLaunchCardOpenWithKey to LibraryDetailOpenWithKey,
            HomeLaunchCardAchievementsKey to LibraryDetailAchievementsKey,
        )
        for (stacked in listOf(false, true)) {
            for (parked in listOf(false, true)) {
                for (width in listOf(300f, 376f, 530f)) {
                    val home = homeLaunchCardActionBounds(stacked, width, parked)
                    val library = homeLaunchCardActionBounds(stacked, width, parked, LibraryLaunchCardFocusContract)
                    val name = "stacked=$stacked parked=$parked width=$width"
                    assertEquals(name, home.map { homeToLibrary.getValue(it.key) to it.value }, library.toList())
                }
            }
        }
        val single = homeLaunchCardActionBounds(stacked = false, contract = LibraryLaunchCardFocusContract)
        assertEquals(FocusBounds(0f, 0f, 96f, 44f), single.getValue(LibraryDetailPlayKey))
        assertEquals(FocusBounds(-96f, 0f, -8f, 44f), single.getValue(LibraryDetailTitleEditKey))
    }

    @Test
    fun primaryLaunchCardRequiresASecondConfirmToActivateFocusedPlay() {
        val row = nativeRow("com.example.two.step", isGame = true)
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { cycleShelfMode(1) }
        val authority = authorityAt(LibraryShelfScope, nativeShelfKey(row.packageName))
        val launches = mutableListOf<String>()
        var opened: LibraryDetailSession? = null

        assertTrue(dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = launches::add,
            nativeGameRows = listOf(row),
            onOpenLibraryDetail = { opened = it },
        ))

        val target = requireNotNull(opened).target
        assertEquals(emptyList<String>(), launches)
        assertEquals(GameDetailScope, authority.state.value.scope)
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)

        assertTrue(dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = launches::add,
            activeDetailTarget = target,
            detailActionState = LibraryDetailActionState(target, isOnShelf = false),
        ))

        assertEquals(listOf(row.packageName), launches)
    }

    @Test
    fun emulatorReturnRestoresLaunchCardOnPlayAndOneBackExits() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
        }
        val game = viewModel.games.first { it.isNotEmpty() }.first()
        val authority = authorityAt(GamelistScope, gameKey(game))
        var session: LibraryDetailSession? = null
        var detailState: LibraryDetailActionState? = null

        assertTrue(dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onOpenLibraryDetail = { opened ->
                session = opened
                detailState = LibraryDetailActionState(opened.target, isOnShelf = false)
            },
        ))
        val opened = requireNotNull(session)

        // Focus leaves Play for the next button on the row...
        assertTrue(dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.NAV_RIGHT,
            onExit = {},
            onRescanFolder = {},
            activeDetailTarget = opened.target,
            detailActionState = requireNotNull(detailState),
        ))
        assertEquals(LibraryDetailOpenWithKey, authority.state.value.focused)

        // ...and the external-return restoration used to leave this visible card in the underlying GamelistScope.
        authority.popScope()
        assertEquals(GamelistScope, authority.state.value.scope)
        assertTrue(restoreLibraryDetailSession(authority, opened))
        assertEquals(GameDetailScope, authority.state.value.scope)
        assertEquals(LibraryDetailPlayKey, authority.state.value.focused)

        assertTrue(dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CANCEL,
            onExit = {},
            onRescanFolder = {},
            activeDetailTarget = opened.target,
            detailActionState = requireNotNull(detailState),
        ))
        assertEquals(GamelistScope, authority.state.value.scope)
        assertEquals(gameKey(game), authority.state.value.focused)
        assertNull(viewModel.openGameDetail.value)
    }

    @Test
    fun primaryActionRowPhysicalRegistrationPreservesEachFocusAndExactBackReturn() {
        libraryDetailActionKeys.forEach { focusedAction ->
            val fixture = detailHandoverFixture(
                suffix = "loss-${focusedAction.value.substringAfterLast('/')}",
            )
            fixture.focus(focusedAction)
            registerVisibleDetailAction(fixture.authority, LibraryDetailPlayKey, 0f)
            fixture.assertFocused(focusedAction)
            registerVisibleDetailAction(fixture.authority, LibraryDetailOpenWithKey, 112f)
            fixture.assertFocused(focusedAction)

            assertTrue(fixture.dispatchBack())
            assertEquals(LibraryShelfScope, fixture.authority.state.value.scope)
            assertEquals(fixture.originKey, fixture.authority.state.value.focused)

            unregisterVisibleDetailActions(fixture.authority)
            assertEquals(fixture.originKey, fixture.authority.state.value.focused)
            assertEquals(emptyList<FocusKey>(), fixture.authority.registeredKeysInScope(GameDetailScope))
        }
    }

    @Test
    fun libraryBaseScopeEntryDoesNotDuplicateAnAlreadyRestoredDetailStack() {
        val row = nativeRow("com.example.restored", isGame = true)
        val authority = authorityAt(LibraryShelfScope, nativeShelfKey(row.packageName))
        assertTrue(enterLibraryDetail(authority, LibraryDetailTarget.AndroidGame(row), onOpen = {}))

        enterLibraryBaseScope(authority)

        assertEquals(GameDetailScope, authority.state.value.scope)
        exitLibraryDetail(authority, LibraryDetailTarget.AndroidGame(row))
        assertEquals(LibraryShelfScope, authority.state.value.scope)
        authority.popScope()
        assertEquals(FocusScope.Root, authority.state.value.scope)
    }

    @Test
    fun primaryActionRowPhysicalUnmountPreservesEachFocusAndExactBackReturn() {
        libraryDetailActionKeys.forEach { focusedAction ->
            val fixture = detailHandoverFixture(
                suffix = "reconnect-${focusedAction.value.substringAfterLast('/')}",
            )
            registerVisibleDetailActions(fixture.authority)
            fixture.focus(focusedAction)
            fixture.assertFocused(focusedAction)

            // The session-owned virtual row covers a composition remount, including removal of the focused key.
            fixture.authority.unregister(LibraryDetailPlayKey)
            fixture.assertFocused(focusedAction)
            fixture.authority.unregister(LibraryDetailOpenWithKey)
            fixture.assertFocused(focusedAction)

            assertTrue(fixture.dispatchBack())
            assertEquals(LibraryShelfScope, fixture.authority.state.value.scope)
            assertEquals(fixture.originKey, fixture.authority.state.value.focused)
            assertEquals(emptyList<FocusKey>(), fixture.authority.registeredKeysInScope(GameDetailScope))
        }
    }

    @Test
    fun nativeFavoritesSplitGameDetailFromAppDirectLaunch() {
        val game = nativeRow("com.example.game", isGame = true)
        val gameViewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openFavorites(FavoriteSegment.ANDROID_GAMES)
        }
        val gameAuthority = authorityAt(GamelistScope, nativeShelfKey(game.packageName))
        var opened: LibraryDetailSession? = null
        val launches = mutableListOf<String>()

        dispatchToLibrary(
            authority = gameAuthority,
            viewModel = gameViewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = launches::add,
            nativeFavoriteGameRows = listOf(game),
            onOpenLibraryDetail = { opened = it },
        )

        assertNotNull(opened)
        assertEquals(GamelistScope, opened?.originScope)
        assertEquals(GameDetailScope, gameAuthority.state.value.scope)
        assertEquals(emptyList<String>(), launches)

        val app = nativeRow("com.example.app", isGame = false)
        val appViewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openFavorites(FavoriteSegment.ANDROID_APPS)
        }
        val appAuthority = authorityAt(GamelistScope, nativeShelfKey(app.packageName))
        var appDetailOpened = false

        dispatchToLibrary(
            authority = appAuthority,
            viewModel = appViewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            onLaunchNativePackage = launches::add,
            nativeFavoriteAppRows = listOf(app),
            onOpenLibraryDetail = { appDetailOpened = true },
        )

        assertFalse(appDetailOpened)
        assertEquals(GamelistScope, appAuthority.state.value.scope)
        assertEquals(listOf(app.packageName), launches)
    }

    // ── The keys the card shares with Home's : Y always the media menu, SELECT = Achievements, Restart on the parked game's card ──

    /** Y on the Library card opens the media menu even when the game is offered Enrich (it leads the menu): it no longer fetches art directly. */
    @Test
    fun yAlwaysOpensTheMediaMenuAndNeverFetchesArtDirectly() {
        val game = ladderGameRow()
        assertTrue("the fixture is a game Enrich is offered for", game.providerEnrichmentState().shouldOfferEnrich)
        val authority = libraryDetailLadderAuthority()
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        var menuFor: GameRow? = null
        var fetchedFor: GameRow? = null

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.TERTIARY,
                onExit = {},
                onRescanFolder = {},
                onFetchArt = { fetchedFor = it },
                onOpenMediaMenuForFocused = { menuFor = it },
                activeDetailTarget = LibraryDetailTarget.Emulated(game),
            ),
        )

        assertEquals(game, menuFor)
        assertNull("Y does not fetch", fetchedFor)
    }

    /** SELECT opens Achievements on the Library card, as it does on Home's; it no longer toggles the favourite. */
    @Test
    fun selectOpensAchievementsAndTogglesNoFavourite() {
        val game = ladderGameRow()
        val authority = libraryDetailLadderAuthority()
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        var achievementsFor: GameRow? = null

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.SYSTEM_SELECT,
                onExit = {},
                onRescanFolder = {},
                onOpenAchievements = { achievementsFor = it },
                activeDetailTarget = LibraryDetailTarget.Emulated(game),
            ),
        )

        assertEquals(game, achievementsFor)
        assertNull("toggleFavorite posts its toast; none was posted", viewModel.parkedRefusalToast.value)
    }

    /** What the card does not own stays unhandled for the shell, as before the card was shared. */
    @Test
    fun actionsTheCardDoesNotOwnStayUnhandled() {
        val authority = libraryDetailLadderAuthority()
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        for (action in listOf(ControllerAction.SECONDARY, ControllerAction.SYSTEM_START)) {
            assertFalse(
                "$action is not the launch card's",
                dispatchToLibrary(
                    authority = authority,
                    viewModel = viewModel,
                    action = action,
                    onExit = {},
                    onRescanFolder = {},
                    activeDetailTarget = LibraryDetailTarget.Emulated(ladderGameRow()),
                ),
            )
        }
    }

    /** Y on an Android game's card opens that package's menu (where favourite lives for it), as Y on an emulated game's card opens the media menu. */
    @Test
    fun yOnAnAndroidGamesCardOpensItsTileMenu() {
        val row = nativeRow("com.example.android.y", isGame = true)
        val authority = libraryDetailLadderAuthority()
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        var opened: String? = null
        var mediaMenuOpened = false

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.TERTIARY,
                onExit = {},
                onRescanFolder = {},
                onOpenMediaMenuForFocused = { mediaMenuOpened = true },
                onOpenNativeAppTileMenu = { opened = it },
                activeDetailTarget = LibraryDetailTarget.AndroidGame(row),
            ),
        )

        assertEquals("com.example.android.y", opened)
        assertFalse("an Android game has no media menu of a GameRow", mediaMenuOpened)
    }

    /** A on Restart files the request and presses Play, as it does on Home: the request is then taken by the Library's refused-Play path. */
    @Test
    fun confirmOnRestartFilesTheRestartRequestAndPressesPlay() {
        val game = ladderGameRow()
        HomeLaunchCardResume.set(game.gameId)
        try {
            val authority = libraryDetailLadderAuthority(parked = true)
            authority.requestFocus(LibraryDetailRestartKey)
            val viewModel = LibraryViewModel(FakeLibraryRepository())

            assertTrue(dispatchLadder(authority, viewModel, ControllerAction.CONFIRM, LibraryDetailTarget.Emulated(game)))

            assertTrue("the Restart request is filed for the parked game", HomeLaunchCardResume.consumeRestart(game.gameId))
        } finally {
            HomeLaunchCardResume.set(null)
        }
    }

    /** A on Play on the same card files nothing: only the Restart button asks for a restart. */
    @Test
    fun confirmOnPlayOfAParkedGameFilesNoRestartRequest() {
        val game = ladderGameRow()
        HomeLaunchCardResume.set(game.gameId)
        try {
            val authority = libraryDetailLadderAuthority(parked = true)
            val viewModel = LibraryViewModel(FakeLibraryRepository())

            assertTrue(dispatchLadder(authority, viewModel, ControllerAction.CONFIRM, LibraryDetailTarget.Emulated(game)))

            assertFalse(HomeLaunchCardResume.consumeRestart(game.gameId))
        } finally {
            HomeLaunchCardResume.set(null)
        }
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

    private fun authorityAt(scope: FocusScope, key: FocusKey) = DefaultFocusAuthority().apply {
        pushScope(scope)
        register(key, scope, FocusBounds(0f, 0f, 100f, 100f))
        requestFocus(key)
    }

    private fun registerVisibleDetailActions(authority: DefaultFocusAuthority) {
        registerVisibleDetailAction(authority, LibraryDetailPlayKey, 0f)
        registerVisibleDetailAction(authority, LibraryDetailOpenWithKey, 112f)
    }

    private fun registerVisibleDetailAction(
        authority: DefaultFocusAuthority,
        key: FocusKey,
        left: Float,
    ) {
        authority.register(key, GameDetailScope, FocusBounds(left, 0f, left + 100f, 56f))
    }

    private fun unregisterVisibleDetailActions(authority: DefaultFocusAuthority) {
        authority.unregister(LibraryDetailPlayKey)
        authority.unregister(LibraryDetailOpenWithKey)
    }

    private fun detailHandoverFixture(
        suffix: String,
    ): DetailHandoverFixture {
        val row = nativeRow("com.example.handover.$suffix", isGame = true)
        val target = LibraryDetailTarget.AndroidGame(row)
        val originKey = nativeShelfKey(row.packageName)
        val authority = authorityAt(LibraryShelfScope, originKey)
        assertTrue(enterLibraryDetail(
            authority = authority,
            target = target,
            onOpen = {},
        ))
        // A second session-owned virtual key beside Play (an Android game's card registers Play alone), so these tests keep two actions to hand over between.
        authority.registerVirtual(LibraryDetailOpenWithKey, GameDetailScope, FocusBounds(112f, 0f, 212f, 56f))
        return DetailHandoverFixture(
            authority = authority,
            viewModel = LibraryViewModel(FakeLibraryRepository()),
            target = target,
            originKey = originKey,
        )
    }

    private fun nativeRow(packageName: String, isGame: Boolean) = NativeShelfRow(
        packageName = packageName,
        label = packageName.substringAfterLast('.'),
        launchActivity = null,
        isGame = isGame,
    )

    private data class DetailHandoverFixture(
        val authority: DefaultFocusAuthority,
        val viewModel: LibraryViewModel,
        val target: LibraryDetailTarget,
        val originKey: FocusKey,
    ) {
        fun focus(key: FocusKey) {
            authority.requestFocus(key)
        }

        fun assertFocused(key: FocusKey) {
            assertEquals(key, authority.state.value.focused)
        }

        fun dispatchBack(): Boolean = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.SYSTEM_BACK,
            onExit = {},
            onRescanFolder = {},
            activeDetailTarget = target,
            detailActionState = LibraryDetailActionState(target, isOnShelf = false),
        )
    }

    private companion object {
        val libraryDetailActionKeys = listOf(LibraryDetailPlayKey, LibraryDetailOpenWithKey)
    }
}
