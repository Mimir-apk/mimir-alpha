package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

/**
 * The FAVORITES step seeds the first Home shelf and teaches SELECT through direct interaction.
 *
 * The load-bearing test here is [aGamePickedInTheStepIsWhatTheHomeShelfItselfReads]. The tester filed QA-25
 * about "favourite / pin / shelf" being three words, which is exactly the shape of a bug where they turn out
 * to be three *concepts* -- a step that writes one of them and a shelf that reads another would look correct
 * in every screenshot and still leave the shelf empty. That test pins the two ends of the chain together.
 *
 * Rebuilt into two pages on 2026-09-17 (policy: an instructive screen, then an "Open Tome" button,
 * then the grid). The rulings below are deliberately UNCHANGED by that: SELECT still toggles, A still
 * toggles, the pill still leaves on A only. What moved is which page each of those happens on, and these
 * tests now say so explicitly rather than assuming one screen holds everything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingFavoritesStepTest {
    /**
     * This class runs in the shared sdk 34 Robolectric sandbox, and the copy it reads can write Compose snapshot state outside any
     * composition. Apply those writes and run what they posted, or a later Compose test in the sandbox fails "Compose did not get
     * idle" (CompanionThemeSeedTest, MimirTrackpadPointerStyleTest).
     */
    @After
    fun applyPendingSnapshotWrites() {
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    private val res = onboardingTestResources()

    private fun game(id: String, title: String, favorite: Boolean = false) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId("snes"),
        memberCount = 1,
        duplicateKind = null,
        isFavorite = favorite,
    )

    private val games = listOf(game("a", "Chrono Trigger"), game("b", "Super Metroid"))

    /** A state already on the Tome page, which is where every game-level binding lives. */
    private fun onTome() = OnboardingFavoritesGridState().apply { openTome() }

    private fun dispatch(
        action: ControllerAction,
        rows: List<GameRow> = games,
        grid: OnboardingFavoritesGridState = onTome(),
        authority: DefaultFocusAuthority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) },
        onToggle: (GameRow) -> Unit = {},
        onAdvance: () -> Unit = {},
        // Fresh per press unless a test needs what one press leaves pending to reach the next.
        focusIntent: OnboardingFocusIntentState = OnboardingFocusIntentState(),
    ): Boolean {
        return dispatchOnboarding(
            authority = authority,
            step = OnboardingStep.FAVORITES,
            profile = OnboardingProfileState(),
            accessReview = OnboardingAccessReviewState(),
            focusIntent = focusIntent,
            library = OnboardingLibraryState(),
            access = OnboardingAccessUi(
                usageAvailable = true,
                usageGranted = true,
                homeRoleAvailable = true,
                homeRoleHeld = true,
            ),
            artworkAccount = null,
            settlement = OnboardingSettlementUi(
                phase = OnboardingSettlementPhase.READY,
                title = "Ready",
                detail = "Ready",
            ),
            action = action,
            favoriteGames = rows,
            favoritesGrid = grid,
            onToggleFavorite = onToggle,
            onAdvance = onAdvance,
            onCommitName = {},
            onRequestFilesAccess = {},
            onRequestUsageAccess = {},
            onRequestHomeRole = {},
            onChooseFolder = {},
            onOpenArtworkSetup = {},
            onOpenSteamGridDbKey = {},
            onRetrySettlement = {},
            onContinueLocally = {},
            onApplyTheme = {},
        )
    }

    @Test
    fun aGamePickedInTheStepIsWhatTheHomeShelfItselfReads() = runTest {
        // The step's toggle writes through `LibraryRepository.setFavorite`. The Home shelf's rows come from
        // `LibraryRepository.observeFavorites()` -- `HomeShelfCoordinator.kt:750`, read at the source, not
        // assumed from the name. This asserts those are the same concept, which is the whole premise of the
        // step: pick here, and the shelf you land on is no longer empty.
        val repository = FakeLibraryRepository()
        // Taken from whatever the fake actually seeds, and ASSERTED rather than skipped over: a test that
        // quietly returns when it finds no fixture is a test that passes forever without checking anything.
        val platform = repository.observePlatforms().first().firstOrNull()
        assertTrue("the fake must seed at least one platform for this test to mean anything", platform != null)
        val target = repository.observeGames(platform!!.platformId).first().firstOrNull()
        assertTrue("the fake must seed at least one game for this test to mean anything", target != null)
        requireNotNull(target)

        assertFalse(
            "precondition: the fixture game must start off the shelf",
            repository.observeFavorites().first().any { it.gameId == target.gameId },
        )

        repository.setFavorite(target.gameId, true)

        assertTrue(
            "a game favourited by the step must appear in the flow the Home shelf actually reads",
            repository.observeFavorites().first().any { it.gameId == target.gameId },
        )

        repository.setFavorite(target.gameId, false)
        assertFalse(
            "and SELECT again must take it back off -- the step promises a toggle, not a one-way add",
            repository.observeFavorites().first().any { it.gameId == target.gameId },
        )
    }

    // ── The two pages ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the step opens on the instructions, not on the boxes`() {
        val grid = OnboardingFavoritesGridState()
        assertEquals(OnboardingFavoritesPage.INTRO, grid.page)
    }

    @Test
    fun `Open Tome opens the Tome, and B closes it again`() {
        val grid = OnboardingFavoritesGridState()
        var advanced = false
        // Entry focus on the intro page is the Open Tome button, so CONFIRM lands on it.
        assertTrue(dispatch(ControllerAction.CONFIRM, grid = grid, onAdvance = { advanced = true }))
        assertEquals(OnboardingFavoritesPage.TOME, grid.page)
        assertFalse("opening the Tome is not leaving the step", advanced)

        assertTrue(dispatch(ControllerAction.CANCEL, grid = grid))
        assertEquals(OnboardingFavoritesPage.INTRO, grid.page)
    }

    @Test
    fun `leaving the Tome drops the ways of looking at it, so the next visit shows the whole library`() {
        // Coming back to a Tome still narrowed by a word typed minutes ago is how a library looks empty
        // for no visible reason.
        val grid = onTome().apply {
            search("metroid")
            show(PlatformId("snes"))
        }
        dispatch(ControllerAction.SYSTEM_BACK, grid = grid)
        assertEquals(OnboardingFavoritesPage.INTRO, grid.page)
        assertEquals("", grid.query)
        assertEquals(null, grid.platform)
    }

    @Test
    fun `the keyboard owns every routed press while it is open`() {
        // Without this gate a D-pad press meant for a letter moves the grid behind the scrim. The action
        // is consumed whether or not a host binding has mounted yet, so a press during the mount frame
        // cannot leak through to the page underneath.
        val grid = onTome().apply { openKeyboard() }
        var toggled = 0
        assertTrue(dispatch(ControllerAction.NAV_DOWN, grid = grid, onToggle = { toggled++ }))
        assertTrue(dispatch(ControllerAction.CONFIRM, grid = grid, onToggle = { toggled++ }))
        assertEquals("nothing reached the grid", 0, toggled)
        assertTrue("and the keyboard is still up", grid.keyboardOpen)

        assertTrue(dispatch(ControllerAction.SYSTEM_BACK, grid = grid))
        assertFalse("B closes the keyboard, not the Tome", grid.keyboardOpen)
        assertEquals(OnboardingFavoritesPage.TOME, grid.page)
    }

    // ── What the buttons mean, unchanged by the rebuild ───────────────────────────────────────────

    @Test
    fun `SELECT toggles the focused game and never leaves the step`() {
        var toggled: GameRow? = null
        var advanced = false
        val handled = dispatch(
            ControllerAction.SYSTEM_SELECT,
            onToggle = { toggled = it },
            onAdvance = { advanced = true },
        )

        assertTrue(handled)
        assertEquals("entry focus is the first game, so SELECT must toggle it", games[0], toggled)
        assertFalse("SELECT picks; it must not also advance", advanced)
    }

    @Test
    fun `A toggles too, because the welcome screen promised that it would`() {
        // Stated assumption, not an policy: the welcome copy says "press A to choose", and an inert A
        // on a screen made entirely of choosable rows reads as broken. A is deliberately not advertised in
        // this screen's body -- SELECT is -- so the habit the player leaves with is the one Home rewards.
        var toggled: GameRow? = null
        assertTrue(dispatch(ControllerAction.CONFIRM, onToggle = { toggled = it }))
        assertEquals(games[0], toggled)
    }

    @Test
    fun `the pill advances on A but is not a second way out on SELECT`() {
        // The pill lives on the INTRO page now, one DOWN below Open Tome. Both keys are registered with
        // real bounds because an unregistered requestFocus does not take, and the dispatch would then fall
        // back to the entry key and test the Open Tome button by accident.
        var advanced = 0
        var toggled = 0
        val grid = OnboardingFavoritesGridState()
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        authority.register(OnboardingFavoritesOpenTomeKey, OnboardingScope, FocusBounds(0f, 0f, 100f, 40f))
        authority.register(OnboardingFavoritesContinueKey, OnboardingScope, FocusBounds(0f, 50f, 100f, 90f))
        authority.requestFocus(OnboardingFavoritesContinueKey)
        assertEquals(
            "precondition: the test must actually be standing on the pill",
            OnboardingFavoritesContinueKey,
            authority.state.value.focused,
        )

        fun press(action: ControllerAction): Boolean = dispatch(
            action = action,
            grid = grid,
            authority = authority,
            onToggle = { toggled++ },
            onAdvance = { advanced++ },
        )

        assertTrue(press(ControllerAction.CONFIRM))
        assertEquals(1, advanced)
        assertEquals("there is no game under the pill to toggle", 0, toggled)

        assertTrue(press(ControllerAction.SYSTEM_SELECT))
        assertEquals("SELECT on the pill is swallowed, not a second exit", 1, advanced)
        assertEquals(0, toggled)
        assertEquals("and neither press opened the Tome", OnboardingFavoritesPage.INTRO, grid.page)
    }

    // ── The grid's focus graph ─────────────────────────────────────────────────

    /** A cell seat, at the arithmetic the grid actually lays out. */
    private fun seat(index: Int, columns: Int = 4): FocusBounds {
        val colPitch = OnboardingFavoritesFaceWidthDp + OnboardingFavoritesGridGapDp
        val rowPitch = OnboardingFavoritesFaceHeightDp + OnboardingFavoritesGridGapDp
        val left = (index % columns) * colPitch
        val top = (index / columns) * rowPitch
        return FocusBounds(left, top, left + OnboardingFavoritesFaceWidthDp, top + OnboardingFavoritesFaceHeightDp)
    }

    private val twelve = (0 until 12).map { game("g$it", "Game $it") }

    /**
     * An authority in the state a SCROLLED lazy grid actually leaves it in: the top row's cells have been
     * disposed and are no longer physically registered.
     */
    private fun scrolledAuthority(): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        authority.register(OnboardingFavoritesSearchKey, OnboardingScope, FocusBounds(0f, -60f, 300f, -20f))
        // Rows 1 and 2 are on screen; row 0 has scrolled off and unregistered itself.
        (4 until 12).forEach { i ->
            authority.register(onboardingFavoriteGameKey("g$i"), OnboardingScope, seat(i))
        }
        return authority
    }

    /**
     * The UP-navigation failure reported on 2026-09-18.
     *
     * A [LazyVerticalGrid] registers a cell only when it is PLACED, and `requestFocus` on an unregistered
     * key is a silent no-op. The screen scrolls the focused row to the top, so one DOWN is enough to
     * dispose the row above -- and the next UP asks for a key the authority no longer holds. Nothing
     * moves and nothing clicks; worse, the pending intent is never consumed, so the dispatch's idea of
     * where focus is walks away from the ring the player can see and the ring later jumps several rows.
     *
     * The fix is the virtual slot the authority already provides for lazy lists, as the Tome library grid
     * uses it. This test is the shape neither existing suite could express: the pure-mover tests compute
     * the right answer and never ask whether the authority can honour it, and the other dispatch tests
     * register every key by hand, which is precisely the state a scrolled grid is NOT in.
     */
    @Test
    fun `UP reaches a row whose cells the grid has already disposed`() {
        val authority = scrolledAuthority()
        val grid = onTome().apply { measured(4) }
        OnboardingFavoritesFocusRegistry().update(
            authority,
            onboardingFavoritesFocusSlots(twelve, columns = 4),
        )
        authority.requestFocus(onboardingFavoriteGameKey("g4"))
        assertEquals(
            "precondition: standing on the first cell of the second row",
            onboardingFavoriteGameKey("g4"),
            authority.state.value.focused,
        )

        assertTrue(dispatch(ControllerAction.NAV_UP, rows = twelve, grid = grid, authority = authority))

        assertEquals(
            "UP must land on the cell above even though nothing composed it",
            onboardingFavoriteGameKey("g0"),
            authority.state.value.focused,
        )
    }

    /**
     * The same press WITHOUT the virtual slots, which is what shipped and what manual QA exercised into.
     *
     * Kept as a test rather than deleted with the bug: it is the only thing that proves the registry above
     * is load-bearing, and it fails the moment someone removes it.
     */
    @Test
    fun `without virtual slots that same press does nothing at all`() {
        val authority = scrolledAuthority()
        val grid = onTome().apply { measured(4) }
        authority.requestFocus(onboardingFavoriteGameKey("g4"))

        assertTrue(dispatch(ControllerAction.NAV_UP, rows = twelve, grid = grid, authority = authority))

        assertEquals(
            "the request for an unregistered key is silently dropped -- this is the reported symptom",
            onboardingFavoriteGameKey("g4"),
            authority.state.value.focused,
        )
    }

    /**
     * Run 9, pressed in the reported order: UP from the top row to a search field that is not there, then A.
     *
     *
     * The field was zero wide, so the authority refused UP and the ring stayed on the game. The refused
     * target stayed pending past the landing's eight frames, and the dispatch trusted it over the ring, so
     * A opened the search keyboard. The landing now drops a target that never arrives, and A marks what the
     * player can see.
     *
     * The field is left unregistered on purpose: the layout fix removed THAT unregistered key, and this is
     * the guard for the next one.
     */
    @Test
    fun `after UP is refused and the landing gives up, A marks the game under the ring`() {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        val focusIntent = OnboardingFocusIntentState()
        val grid = onTome()
        games.forEachIndexed { index, row ->
            authority.register(onboardingFavoriteGameKey(row.gameId.value), OnboardingScope, seat(index))
        }
        authority.requestFocus(onboardingFavoriteGameKey("a"))

        assertTrue(dispatch(ControllerAction.NAV_UP, grid = grid, authority = authority, focusIntent = focusIntent))
        assertEquals(
            "precondition: the search field is not registered, so UP was refused and the ring stayed put",
            onboardingFavoriteGameKey("a"),
            authority.state.value.focused,
        )

        // What the Tome's landing effect does on the revision bump UP made: chase the target for eight frames.
        runBlocking(
            object : androidx.compose.runtime.MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = onFrame(0L)
            },
        ) {
            authority.landFocus(
                focusIntent = focusIntent,
                surface = OnboardingFocusSurface.FAVORITES,
                entryFocus = onboardingFavoriteGameKey("a"),
                validKeys = onboardingFavoritesFocusKeys(games),
            )
        }

        var toggled: GameRow? = null
        assertTrue(
            dispatch(
                ControllerAction.CONFIRM,
                grid = grid,
                authority = authority,
                focusIntent = focusIntent,
                onToggle = { toggled = it },
            ),
        )
        assertEquals("A marks the game the ring is on", games[0], toggled)
        assertFalse("and does not open the keyboard of a field nobody can see", grid.keyboardOpen)
    }

    /** Narrowing the search must never empty the focus graph, even for one frame. */
    @Test
    fun `re-filtering the grid keeps a slot for every game that survives`() {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        val registry = OnboardingFavoritesFocusRegistry()
        registry.update(authority, onboardingFavoritesFocusSlots(twelve, columns = 4))
        authority.requestFocus(onboardingFavoriteGameKey("g5"))

        // The player types, and only g5 still matches.
        val narrowed = twelve.filter { it.gameId.value == "g5" }
        registry.update(authority, onboardingFavoritesFocusSlots(narrowed, columns = 4))

        assertEquals(
            "the surviving game keeps its slot AND its focus -- incoming identities register before " +
                "departing ones are removed, so the graph is never momentarily empty",
            onboardingFavoriteGameKey("g5"),
            authority.state.value.focused,
        )
        assertEquals(
            listOf(onboardingFavoriteGameKey("g5")),
            authority.registeredKeysInScope(OnboardingScope),
        )
    }

    // ── Copy ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `copy counts what is chosen and never dares the player to press an unearned Continue`() {
        assertEquals("Skip for now", onboardingFavoritesCopy(res, 0).continueLabel)
        assertEquals("Continue", onboardingFavoritesCopy(res, 1).continueLabel)

        assertEquals("Nothing on your Home shelf yet.", onboardingFavoritesCopy(res, 0).chosenLine)
        assertEquals("1 game on your Home shelf.", onboardingFavoritesCopy(res, 1).chosenLine)
        assertEquals("4 games on your Home shelf.", onboardingFavoritesCopy(res, 4).chosenLine)
    }

    @Test
    fun `the button says what it opens, in the product's own word for it`() {
        assertEquals("Open Tome", onboardingFavoritesCopy(res, 0).openLabel)
    }

    @Test
    fun `the instructions teach A, and they are the ONLY place that can`() {
        // The Tome page carries no prose at all, so the teaching has to be complete here or it is nowhere.
        // This is the test that fails if someone trims the intro page the way the grid page was trimmed.
        // Since 2026-09-28 the teaching is the hint carousel's first card: the carousel replaced the how-line. The advertised mark button is A.
        val first = onboardingHintDeck(res, teachMButton = true, markFirst = true).first()
        assertEquals("A, the confirm button, is the taught control", "A", first.key)
        assertEquals(OnboardingHintScene.MARK, first.scene)
        assertTrue("it says what A does here", first.action.contains("Mark"))
        assertTrue("and what it is for", first.action.contains("shelf"))
        // The toggle is taught where it is done: over a marked game the Tome's own footer says "A Unmark".
        val overAMarkedGame = onboardingFavoritesTomeHint(
            res,
            focused = onboardingFavoriteGameKey("a"),
            filterKeyPresent = true,
            hasGames = true,
            focusedMarked = true,
        )
        assertTrue("the toggle must be taught, not just the add", overAMarkedGame.contains("A Unmark"))
        // One button taught, not two. SELECT still toggles (see the dispatch tests above), unadvertised.
        val copy = onboardingFavoritesCopy(res, 0)
        val allText = copy.body + first.action
        assertFalse("SELECT is no longer this screen's taught control", allText.contains("SELECT"))
    }

    @Test
    fun `the Tome page's hint names the same button the instructions taught`() {
        // The step says A on its instructions page. A footer naming another button would have the step
        // contradicting itself across its own two pages -- and an inline draft of this line once did, where
        // the assertion above could not see it. That is why the hint is a pure function.
        val overAGame = onboardingFavoritesTomeHint(
            res,
            focused = onboardingFavoriteGameKey("a"),
            filterKeyPresent = true,
            hasGames = true,
        )

        assertTrue(
            "the grid must teach A, exactly as the instructions page does",
            Regex("""\bA\s+Mark\b""").containsMatchIn(overAGame),
        )
        assertFalse("and must not name a second button for the same thing", overAGame.contains("SELECT"))
        assertTrue("and B is always the way back", overAGame.contains("B Back"))

        // The search field and the chip keep their own verbs: A is the confirm button on every key here.
        assertTrue(
            onboardingFavoritesTomeHint(res, OnboardingFavoritesSearchKey, true, true).contains("A Search"),
        )
        assertTrue(
            onboardingFavoritesTomeHint(res, OnboardingFavoritesPlatformFilterKey, true, true)
                .contains("A Platform"),
        )
        // A single-platform library has no filter at all, so the hint must never name one.
        assertFalse(
            "a hint naming a control that is not on the screen is worse than no hint",
            onboardingFavoritesTomeHint(res, OnboardingFavoritesPlatformFilterKey, false, true)
                .contains("platform", ignoreCase = true),
        )

        // Nothing to mark -- a search that matched nothing, or a library still arriving -- offers no verb
        // for marking rather than naming a button that would do nothing.
        assertEquals("B Back", onboardingFavoritesTomeHint(res, null, true, hasGames = false))
    }

    @Test
    fun `over a marked game the hint says A Unmark, still with A`() {
        // 2026-09-28: A takes a marked game off again, so the verb follows the
        // focused game's state. The button does not change -- one button taught, on both pages.
        val game = onboardingFavoriteGameKey("a")
        assertEquals("A Unmark · B Back", onboardingFavoritesTomeHint(res, game, true, true, focusedMarked = true))
        assertEquals("A Mark · B Back", onboardingFavoritesTomeHint(res, game, true, true, focusedMarked = false))
        // The search field and the chip keep their own verbs whatever the grid holds.
        assertEquals(
            "A Search · B Back",
            onboardingFavoritesTomeHint(res, OnboardingFavoritesSearchKey, true, true, focusedMarked = true),
        )
    }

    @Test
    fun `the step's noun matches the product's own, not the report's`() {
        // This screen uses the same favorite vocabulary as the other surfaces: the shipped strings say "Favorites" (CompanionPayloadAdapter.kt:116).
        val copy = onboardingFavoritesCopy(res, 2)
        val allText = copy.title + copy.body + copy.openLabel +
            copy.chosenLine + copy.continueLabel + copy.eyebrowVisual
        assertFalse("no second dialect of one noun", allText.contains("favourite", ignoreCase = true))
        assertTrue("the shelf is the thing being filled, and it is named", allText.contains("shelf", true))
    }

    /**
     * The defect the emulator re-verify found, pinned at its mechanism.
     *
     * The favourites list arrives asynchronously, so the Tome page's first composition offers only the
     * search key. [landOnboardingFocusNow]'s first line treats "already focused on a valid key" as
     * success — so when the rows appear, the search field is still valid and focus never moves to a game.
     *
     * This asserts the LANDING BEHAVIOUR that makes the screen's extra re-assert necessary, not the
     * re-assert itself. If `landOnboardingFocusNow` ever stops early-returning on an already-valid focus,
     * this fails and whoever changed it can delete the workaround rather than inherit it.
     */
    @Test
    fun `a grown key list does not move focus by itself, which is why the step re-asserts it`() {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        val focusIntent = OnboardingFocusIntentState()
        val search = OnboardingFavoritesSearchKey

        // Frame 1: the list has not arrived, so the search field is the only key and focus lands on it.
        authority.register(search, OnboardingScope, FocusBounds(0f, 0f, 100f, 50f))
        assertTrue(
            authority.landOnboardingFocusNow(
                focusIntent, OnboardingFocusSurface.FAVORITES, search, listOf(search),
            ),
        )
        assertEquals(search, authority.state.value.focused)

        // Frame 2: the rows arrive. The search key is still valid, so the landing reports success and the
        // player is left on a text field with the games one press away.
        val firstGame = onboardingFavoriteGameKey("a")
        authority.register(firstGame, OnboardingScope, FocusBounds(0f, 60f, 100f, 110f))
        val grown = listOf(search, firstGame)
        assertTrue(
            "the landing reports success...",
            authority.landOnboardingFocusNow(
                focusIntent, OnboardingFocusSurface.FAVORITES, firstGame, grown,
            ),
        )
        assertEquals(
            "...without having moved focus onto the first game -- the whole defect, in one assertion",
            search,
            authority.state.value.focused,
        )
    }

    /**
     * **Y opens the filter on the page that has one.**
     *
     * The first implementation put the binding on INTRO, which has two pills and no filter. That is what this test would have caught: it drives
     * the two pages separately, because "the FAVORITES step handles Y" and "the page with the filter on it
     * handles Y" are different claims and only the second matches the visible control.
     *
     * The INTRO half is not redundant. `openFilter` on that page would have set `filterOpen` with no
     * dropdown rendered, and `openTome()` does not clear it -- so the Tome would have opened with the list
     * already up, over a `returnFocus` pointing at a key that page does not own. A binding on the wrong
     * page is worse than a missing one.
     */
    @Test
    fun `Y opens the platform filter on the Tome, and does nothing on the page that has none`() {
        val twoPlatforms = listOf(
            game("a", "Chrono Trigger"),
            GameRow(
                gameId = GameId("b"),
                title = "Sonic",
                platformId = PlatformId("megadrive"),
                memberCount = 1,
                duplicateKind = null,
            ),
        )

        val tome = onTome()
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        val standingOn = onboardingFavoriteGameKey("a")
        authority.register(standingOn, OnboardingScope, FocusBounds(0f, 60f, 100f, 110f))
        authority.requestFocus(standingOn)

        assertTrue(
            "Y is handled on the Tome",
            dispatch(ControllerAction.TERTIARY, rows = twoPlatforms, grid = tome, authority = authority),
        )
        assertTrue("...and it opens the filter", tome.filterOpen)
        assertEquals(
            "...remembering the game the player was standing on, so closing comes back to it",
            standingOn,
            tome.takeReturnFocus(),
        )

        // INTRO: a fresh state is on INTRO by default, since only openTome() moves off it.
        val intro = OnboardingFavoritesGridState()
        assertFalse(
            "Y is not handled on the page with no filter",
            dispatch(ControllerAction.TERTIARY, rows = twoPlatforms, grid = intro),
        )
        assertFalse("...and nothing is left open behind it", intro.filterOpen)

        // One platform means no filter chip in onboardingFavoritesHeadKeys, so there is nothing to open
        // and Y must stay unhandled rather than raising a list the page offers no other way to reach.
        val singlePlatform = onTome()
        assertFalse(
            "Y is not handled when the library has only one platform",
            dispatch(ControllerAction.TERTIARY, rows = games, grid = singlePlatform),
        )
        assertFalse("...and no dropdown is left up", singlePlatform.filterOpen)
    }

}
