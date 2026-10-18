package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.platform.IdentityNamespace
import com.gruning.mimir.rewrite.core.platform.PlatformRegistryLoader
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The FAVORITES step measured at REAL layout on the reference handheld, because this step's defect
 * was a geometry defect and no pure function could have caught it.
 *
 * **Why this file exists instead of two more rows in [OnboardingScreenFitTest].** That suite drives every
 * onboarding page through one shared matrix with two blanket rulings: STANDARD viewports must compose NO
 * scrollable node, and compact-square ones must compose one. FAVORITES can satisfy neither. Its Tome page
 * is a grid over the player's whole library -- sixty-six boxes on the reference device -- so scrolling is
 * not a layout failure there, it is the feature; and its screens deliberately do not use
 * [OnboardingScaffold], because a lazy grid inside a `verticalScroll` parent gets unbounded height
 * constraints and Compose throws. Adding these scenarios to that matrix would not have measured FAVORITES,
 * it would have broken two passing tests and taught the next person to exclude the step again.
 *
 * **What went wrong, and why neither of that suite's instruments would have found it.** A device report on 2026-09-17 found too little space left for the game grid. The single-page version stacked header, eyebrow, title, body, count line and rail above the grid
 * and a pill below it -- 294dp of chrome on a 468dp-tall device -- leaving 174dp for a grid whose rows were
 * 208dp tall. The step whose entire job is filling the Home shelf could not show one complete row of it.
 *
 * That is invisible to a natural-height probe: the grid sits in a `weight(1f)`, and a weighted child
 * measured under `Constraints.Infinity` gets ZERO height, so the probe would have reported only the chrome
 * -- 294dp, comfortably "fitting" 468dp -- and passed. It is equally invisible to a no-scroll assertion,
 * which the page fails by design. The property that actually broke is the one asserted here: **how much
 * room is left for boxes after the page has had its say.**
 *
 * The reference viewport is [MimirCertifiedViewports.Odin] (1920x1080px at density 369 => 832.5 x 468.3dp
 * landscape). Measured on the AYN Thor over adb on 2026-09-17, `wm size` reports 1080x1920 and
 * `wm density` reports 369 -- the SAME logical viewport, so this row covers both measured handhelds
 * rather than only the one it is named after.
 *
 * Robolectric's text metrics approximate but do not certify a real device's font rasterizer, so this is
 * [D], not a substitute for an on-device pass. NATIVE graphics mode is mandatory: without it Robolectric
 * does not wrap text at all and every height here is meaningless.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingFavoritesViewportFitTest {
    private val res = onboardingTestResources()

    @get:Rule
    val compose = createComposeRule()

    private val odinWidthDp = MimirCertifiedViewports.Odin.fullViewportDp!!.widthDp
    private val odinHeightDp = MimirCertifiedViewports.Odin.fullViewportDp!!.heightDp

    /**
     * Enough games that the grid is taller than any viewport -- the scrolling case, which is the real one.
     * Forty-four at eight across is six rows, the last one short: two rows past the three that show, and a
     * ragged end.
     */
    private val library = (0 until 44).map {
        GameRow(
            gameId = GameId("g$it"),
            title = "Test Game Number $it",
            platformId = PlatformId(if (it % 2 == 0) "snes" else "ps1"),
            memberCount = 1,
            duplicateKind = null,
        )
    }

    private class FlowState {
        val authority = DefaultFocusAuthority()
        val profile = OnboardingProfileState()
        val libraryState = OnboardingLibraryState()
        val accessReview = OnboardingAccessReviewState()
        val focusIntent = OnboardingFocusIntentState()
        val favoritesGrid = OnboardingFavoritesGridState()
    }

    @androidx.compose.runtime.Composable
    private fun Flow(state: FlowState, games: List<GameRow>) {
        OnboardingFlow(
            authority = state.authority,
            step = OnboardingStep.FAVORITES,
            profile = state.profile,
            accessReview = state.accessReview,
            focusIntent = state.focusIntent,
            access = OnboardingAccessUi(
                usageAvailable = true,
                usageGranted = true,
                homeRoleAvailable = true,
                homeRoleHeld = true,
            ),
            library = state.libraryState,
            libraryGameCount = games.size,
            libraryPlatformCount = 2,
            favoriteGames = games,
            favoritesGrid = state.favoritesGrid,
            onToggleFavorite = {},
            artworkAccount = null,
            steamGridDbKeyPresent = false,
            settlement = OnboardingSettlementUi(
                phase = OnboardingSettlementPhase.READY,
                title = "Ready",
                detail = "Ready",
            ),
            currentThemeId = MimirThemeId.DEFAULT,
            onAdvance = {},
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

    /** What the page computes across at the reference handheld: its 832.5dp less the two 16dp gutters. */
    private val odinColumns get() = onboardingFavoritesColumns(odinWidthDp - 32f)

    /**
     * THE regression guard for the reported layout failures, stated as the quantity that actually broke.
     *
     * First it was room: the single-page version could not show one full row (2026-09-17). Then it was the
     * count: smaller art in a 5x3 grid, later widened to eight boxes across on this screen. So the bar is eight boxes across and three full rows down, measured
     * on the boxes as LAID OUT. Until run 9 the declared face and the drawn one disagreed by 7dp (see
     * [onboardingFavoritesGridViewportWidthDp]), and arithmetic over the declared face passed while the
     * screen showed something else.
     */
    @Test
    fun `the Tome page shows three full rows of eight boxes on the reference handheld`() {
        val state = FlowState()
        state.favoritesGrid.openTome()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(odinWidthDp.dp).requiredHeight(odinHeightDp.dp)) {
                        Flow(state, library)
                    }
                }
            }
        }
        compose.waitForIdle()

        // Three scrollable nodes, and the page is allowed exactly these three: the grid; the search field’s
        // own horizontal text scroll, so a long query can be read to its end rather than truncating; and the
        // platform filter’s rail, which is Customize Media’s `EnrichFilterRow` structure and is what keeps
        // the chip as wide as its words (see the control row in OnboardingFavoritesTomeScreen). Pinned rather
        // than assumed, because a FOURTH would mean the page body started scrolling and this measurement
        // would silently stop meaning what it says.
        //
        // Two until run 9 (2026-09-18), when the filter's missing rail turned out to be the defect.
        val scrollables = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(
            "the Tome page composes the grid, the search field’s text scroll and the filter rail, and nothing else",
            3,
            scrollables.size,
        )

        // Density is 1f in this harness, so px == dp numerically. The grid is the tallest of the three by an
        // order of magnitude -- the other two are single lines.
        val grid = scrollables.maxBy { it.boundsInRoot.height }.boundsInRoot

        // Merged tree: each cell merges its book cover's title, so these are the CELLS' bounds.
        fun cell(index: Int) = compose.onNodeWithText("Test Game Number $index").fetchSemanticsNode().boundsInRoot
        val first = cell(0)
        assertEquals(
            "a box is drawn at the width it declares, not squeezed by the ring clearance",
            OnboardingFavoritesFaceWidthDp,
            first.width,
            0.5f,
        )
        assertEquals(8, odinColumns)
        assertEquals("eight across: the eighth box is still on the first row", first.top, cell(7).top, 0.5f)
        assertTrue("and the ninth starts the second", cell(8).top > first.bottom)

        val thirdRow = cell(2 * odinColumns)
        val roomBelowThirdRowDp = grid.bottom - thirdRow.bottom
        println(
            "FAVORITES/Tome at Odin ${odinWidthDp}x${odinHeightDp}dp: grid viewport ${grid.height}dp, " +
                "face ${first.width}x${first.height}dp, room below the third row ${roomBelowThirdRowDp}dp",
        )
        assertTrue(
            "the third row of boxes ends ${roomBelowThirdRowDp}dp above the bottom of the grid's " +
                "${grid.height}dp viewport at ${odinWidthDp}x${odinHeightDp}dp. It needs at least the " +
                "${OnboardingFavoritesRingClearanceDp}dp its focus ring draws into, or the third row is cut " +
                "off or its ring is. Chrome above or below the grid has grown -- move it to the instructions " +
                "page rather than losing the full-width row.",
            roomBelowThirdRowDp >= OnboardingFavoritesRingClearanceDp,
        )
    }

    /**
     * A marked box says so in words, not only with the pin (2026-09-28): the pin
     * fades in on a 110 ms clock, and motion must never be the only carrier of information.
     */
    @Test
    fun `a marked box says Marked and an unmarked one says Not marked`() {
        val state = FlowState()
        state.favoritesGrid.openTome()
        val games = library.mapIndexed { index, row -> if (index == 0) row.copy(isFavorite = true) else row }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(odinWidthDp.dp).requiredHeight(odinHeightDp.dp)) {
                        Flow(state, games)
                    }
                }
            }
        }
        compose.waitForIdle()

        // Merged tree: each cell merges its book cover's title, so this is the CELL's node.
        fun stateOf(index: Int) = compose.onNodeWithText("Test Game Number $index").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("Marked", stateOf(0))
        assertEquals("Not marked", stateOf(1))
        assertEquals("Marked", onboardingFavoriteCellStateDescription(res, isFavorite = true))
        assertEquals("Not marked", onboardingFavoriteCellStateDescription(res, isFavorite = false))
    }

    /**
     * Run 9's defect, stated as the property that broke: the search field has to EXIST at the reference
     * handheld's width, as a key the authority holds and will land on.
     *
     *  The metallic platform chip filled the control row and left the search field zero
     * wide, and [com.gruning.mimir.rewrite.core.input.mimirFocusable] never registers a zero-sized node. So UP
     * from the top row of boxes asked for a key the authority did not hold. Every pure test of the mover
     * passed throughout, because the mover was right: it named a key the LAYOUT had silently deleted.
     *
     * A two-platform library, because only then is the chip on screen to do the squeezing.
     */
    @Test
    fun `the search field is laid out and reachable beside the platform filter on the reference handheld`() {
        val state = FlowState()
        state.favoritesGrid.openTome()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(odinWidthDp.dp).requiredHeight(odinHeightDp.dp)) {
                        Flow(state, library)
                    }
                }
            }
        }
        compose.waitForIdle()

        val registered = state.authority.registeredKeysInScope(OnboardingScope)
        assertTrue(
            "the library has two platforms, so the platform filter must be on screen or this tests nothing",
            OnboardingFavoritesPlatformFilterKey in registered,
        )
        assertTrue(
            "the search field never registered its key. mimirFocusable skips zero-sized nodes, so something " +
                "in the control row is taking all of its width -- run 9's defect, where UP from the top row " +
                "went to a field that was not there",
            OnboardingFavoritesSearchKey in registered,
        )

        // Merged tree: the field's clickable Row carries its label, so these are the FIELD's bounds.
        val fieldWidthDp = compose.onNodeWithText("Search")
            .fetchSemanticsNode().boundsInRoot.width
        println("FAVORITES/Tome at Odin: search field ${fieldWidthDp}dp wide")
        assertTrue("the search field is laid out ${fieldWidthDp}dp wide", fieldWidthDp > 0f)

        // What UP from the top row does: ask for the field, through the same request the dispatch uses.
        compose.runOnIdle {
            requestOnboardingFocus(
                authority = state.authority,
                focusIntent = state.focusIntent,
                surface = OnboardingFocusSurface.FAVORITES,
                target = OnboardingFavoritesSearchKey,
            )
        }
        assertEquals(
            "UP from the top row must land on the search field, not leave a request the authority refused",
            OnboardingFavoritesSearchKey,
            state.authority.state.value.focused,
        )
    }

    /**
     * The open list printed
     * each platform by the scanner's id, "snes" and "ps1". It now prints the name the Tome collection gives
     * it ([onboardingFavoritesPlatformLabel]).
     *
     * Rendered, because the list builds each row's words inline, where a function test cannot reach them.
     */
    @Test
    fun `the open platform list names each platform the way the library does`() {
        val state = FlowState()
        state.favoritesGrid.openTome()
        state.favoritesGrid.show(PlatformId("snes"))
        state.favoritesGrid.openFilter()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(odinWidthDp.dp).requiredHeight(odinHeightDp.dp)) {
                        Flow(state, library)
                    }
                }
            }
        }
        compose.waitForIdle()

        for (name in listOf("All", "Super Nintendo", "PlayStation")) {
            assertTrue(
                "the open list has no row reading \"$name\"",
                compose.onAllNodes(hasContentDescription(name), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        }
        for (id in listOf("snes", "ps1")) {
            assertTrue(
                "the filter still shows the platform's id \"$id\"",
                compose.onAllNodes(
                    hasContentDescription(id, substring = true) or hasText(id, substring = true),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    /**
     * A name is wider than an id, and the closed chip does not wrap. Its slot scrolls sideways instead of
     * pushing the row apart, so a name too long for it fails quietly: its end is simply out of sight. The
     * longest name the filter can print must end inside the slot: over every platform the registry knows,
     * which is every platform a scan can meet, by the id the library files it under.
     *
     * In a widened window, as the whole-row walks are, so the window's own edge cannot clip the slot.
     */
    @Test
    @Config(qualifiers = "w2000dp-h1200dp")
    fun `the longest platform name fits the closed filter on the reference handheld`() {
        val longest = platformWithTheLongestName()
        val games = library.mapIndexed { index, game ->
            if (index % 2 == 0) game.copy(platformId = longest) else game
        }
        val state = FlowState()
        state.favoritesGrid.openTome()
        state.favoritesGrid.show(longest)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(odinWidthDp.dp).requiredHeight(odinHeightDp.dp)) {
                        Flow(state, games)
                    }
                }
            }
        }
        compose.waitForIdle()

        val chip = compose.onNode(
            hasContentDescription(onboardingFavoritesPlatformFilterIntent(res, longest).spokenText),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        var rail = chip.parent
        while (rail != null && SemanticsActions.ScrollBy !in rail.config) rail = rail.parent
        val slot = requireNotNull(rail) { "the chip is no longer inside its scrolling slot" }.boundsInRoot
        val chipEnd = chip.positionInRoot.x + chip.size.width
        println(
            "FAVORITES/Tome at Odin: the longest name's chip runs ${chip.positionInRoot.x}..${chipEnd}dp " +
                "in its slot at ${slot.left}..${slot.right}dp",
        )
        assertTrue(
            "the chip for \"${onboardingFavoritesPlatformLabel(res, longest)}\" ends at ${chipEnd}dp, past its " +
                "slot's end at ${slot.right}dp, so the end of the name is scrolled out of sight",
            chipEnd <= slot.right + 0.5f,
        )
    }

    private fun platformWithTheLongestName(): PlatformId {
        val registry = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("mimir-platform-registry.v2.json"),
        ) { "the platform registry is not on the test classpath" }
            .use { PlatformRegistryLoader.load(it).requireRegistry() }
        return registry.platforms()
            .map { definition ->
                definition.aliases.firstOrNull { it.namespace == IdentityNamespace.REWRITE_LEGACY }
                    ?.normalizedValue?.let(::PlatformId)
                    ?: definition.id
            }
            .maxBy { onboardingFavoritesPlatformLabel(res, it).length }
    }

    /**
     * The open list reaches every platform in a long library, and shows the one focus is on.
     *
     * The Thor test device has ROM folders for twenty platforms, and the list could only ever be as tall as the
     * screen, nine rows at the Thor's size. Every row past the bottom edge was measured at zero height, and
     * [mimirFocusable] never registers a zero-sized node, so DOWN stopped at the last row on screen:
     * PlayStation 3 and Xbox 360, the two reported inaccessible platforms, could be neither seen nor picked
     * (emulator, preparing run 13).
     *
     * The twenty are the platforms of the measured folder sample, in the order the list shows them. Focus goes to the
     * last through the same request DOWN makes; that row must then be whole inside the list, and the list
     * inside the screen.
     */
    @Test
    @Config(qualifiers = "w2000dp-h1200dp")
    fun `the open list reaches every platform of a twenty-platform library and shows the focused one`() {
        val thor = listOf(
            "arcade", "dreamcast", "gb", "gba", "gbc", "gamecube", "megadrive", "nes", "3ds", "n64",
            "ds", "ps1", "ps2", "ps3", "psp", "saturn", "switch", "wii", "xbox", "xbox360",
        )
        val games = thor.mapIndexed { index, id ->
            GameRow(
                gameId = GameId("t$index"),
                title = "Thor Game $index",
                platformId = PlatformId(id),
                memberCount = 1,
                duplicateKind = null,
            )
        }
        val state = FlowState()
        state.favoritesGrid.openTome()
        state.favoritesGrid.openFilter()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(
                        Modifier.requiredWidth(odinWidthDp.dp).requiredHeight(odinHeightDp.dp).testTag("screen"),
                    ) {
                        Flow(state, games)
                    }
                }
            }
        }
        compose.waitForIdle()

        val options = onboardingFavoritesPlatformOptions(onboardingFavoritesPlatforms(games))
        assertEquals("precondition: All and the twenty", 21, options.size)
        val registered = state.authority.registeredKeysInScope(OnboardingScope)
        val unreachable = options.indices.map(::onboardingFavoritesPlatformOptionKey).filterNot { it in registered }
        assertTrue("these rows never registered, so no press can reach them: $unreachable", unreachable.isEmpty())

        val last = onboardingFavoritesPlatformOptionKey(options.lastIndex)
        compose.runOnIdle {
            requestOnboardingFocus(
                authority = state.authority,
                focusIntent = state.focusIntent,
                surface = OnboardingFocusSurface.FAVORITES,
                target = last,
            )
        }
        compose.waitForIdle()
        assertEquals("DOWN's request for the last row must land", last, state.authority.state.value.focused)

        val name = onboardingFavoritesPlatformLabel(res, options.last())
        assertEquals("precondition: the last row is his", "Xbox 360", name)
        val row = compose.onNode(hasContentDescription(name), useUnmergedTree = true).fetchSemanticsNode()
        var scroller = row.parent
        while (scroller != null && SemanticsActions.ScrollBy !in scroller.config) scroller = scroller.parent
        val list = requireNotNull(scroller) { "the open list does not scroll" }.boundsInRoot
        val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        val rowTop = row.positionInRoot.y
        val rowBottom = rowTop + row.size.height
        println("FAVORITES/Tome at Odin: \"$name\" at $rowTop..${rowBottom}dp in a list at ${list.top}..${list.bottom}dp")
        assertTrue(
            "the focused row runs $rowTop..${rowBottom}dp but the list shows ${list.top}..${list.bottom}dp: " +
                "focus is on a row nobody can see",
            rowTop >= list.top - 0.5f && rowBottom <= list.bottom + 0.5f,
        )
        assertTrue(
            "the list runs ${list.top}..${list.bottom}dp on a screen of ${screen.top}..${screen.bottom}dp",
            list.top >= screen.top - 0.5f && list.bottom <= screen.bottom + 0.5f,
        )
    }

    /**
     * Moving past the third row scrolls the focused box fully into view, ring and all.
     *
     * Found on the emulator right after 5x3 landed (2026-09-18): DOWN onto the fourth row bottom-aligned the
     * box one top-padding too low, so the box and its ring were cut off at the bottom of the grid. The scroll
     * subtracted the whole viewport, padding included, in coordinates that start after that padding.
     *
     * Since run 11 the focused row is seated at the TOP edge rather than the bottom one, so this also pins
     * where it lands: one ring clearance below the grid's top.
     */
    @Test
    fun `focusing a box below the third row scrolls it fully into view, ring clearance included`() {
        val state = FlowState()
        state.favoritesGrid.openTome()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(odinWidthDp.dp).requiredHeight(odinHeightDp.dp)) {
                        Flow(state, library)
                    }
                }
            }
        }
        compose.waitForIdle()

        // The first box of the fourth row: registered as a virtual slot, so the request takes even though
        // the row is barely on screen, which is exactly how DOWN reaches it.
        val fourthRow = 3 * odinColumns
        compose.runOnIdle { state.authority.requestFocus(onboardingFavoriteGameKey("g$fourthRow")) }
        compose.waitForIdle()
        assertEquals(
            "precondition: focus must actually be on the fourth row",
            onboardingFavoriteGameKey("g$fourthRow"),
            state.authority.state.value.focused,
        )

        val grid = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes()
            .maxBy { it.boundsInRoot.height }.boundsInRoot
        val box = compose.onNodeWithText("Test Game Number $fourthRow").fetchSemanticsNode().boundsInRoot
        val roomBelowDp = grid.bottom - box.bottom
        println("FAVORITES/Tome at Odin: fourth-row box ${box.height}dp tall, ${roomBelowDp}dp above the grid's bottom")
        assertEquals(
            "the focused box is cut off by the bottom of the grid",
            OnboardingFavoritesFaceHeightDp,
            box.height,
            1f,
        )
        assertTrue(
            "the focused box ends ${roomBelowDp}dp above the grid's bottom; its ring needs " +
                "${OnboardingFavoritesRingClearanceDp}dp there",
            roomBelowDp >= OnboardingFavoritesRingClearanceDp - 0.5f,
        )
        assertEquals(
            "the focused row is seated in the middle, with the row before it above (run 13)",
            OnboardingFavoritesRingClearanceDp + OnboardingFavoritesFaceHeightDp + OnboardingFavoritesGridGapDp,
            box.top - grid.top,
            1f,
        )
    }

    /**
     * What clipped was never the focused box -- the test above already held that -- but the rows at the
     * edges: the grid filled whatever height the page left it and scrolled only once focus reached a box that
     * was already cut off, so a strip of another row showed at the top or the bottom nearly all the time.
     * This walks the page the way DOWN and UP do, and at every stop asks the grid for the boxes it draws:
     * each one must be whole, and together they must be exactly three whole rows, with the focused one
     * between them until an end of the library puts it at an edge ([onboardingFavoritesTopRow]).
     *
     * At the harness's density of 1 and at the Thor's own 369/160, because a row that hides behind a 1dp
     * margin at one density can peek out through rounding at another. The window is widened for this test
     * only, so all eight columns are inside it at both densities.
     */
    @Test
    @Config(qualifiers = "w2000dp-h1200dp")
    fun `the grid shows whole rows only while the view follows focus, at the harness's density`() {
        walkTheGridShowingWholeRows(density = 1f)
    }

    @Test
    @Config(qualifiers = "w2000dp-h1200dp")
    fun `the grid shows whole rows only while the view follows focus, at the Thor's density`() {
        walkTheGridShowingWholeRows(density = 369f / 160f)
    }

    private fun walkTheGridShowingWholeRows(density: Float) {
        val state = FlowState()
        state.favoritesGrid.openTome()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = density, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(odinWidthDp.dp).requiredHeight(odinHeightDp.dp)) {
                        Flow(state, library)
                    }
                }
            }
        }
        compose.waitForIdle()
        val columns = odinColumns
        val lastRow = (library.size - 1) / columns

        // Each stop: the game focus goes to, and the rows that must then fill the grid.
        val walk = listOf(
            0 to listOf(0, 1, 2),
            // Run 13: row 1 is the middle of the first three, so the view holds and only the ring moves.
            columns to listOf(0, 1, 2),
            2 * columns to listOf(1, 2, 3),
            3 * columns to listOf(2, 3, 4),
            library.lastIndex to listOf(3, 4, 5),
            2 * columns to listOf(1, 2, 3),
            columns + 3 to listOf(0, 1, 2),
            0 to listOf(0, 1, 2),
        )
        assertEquals("precondition: the walk's last stop is on the last row", 5, lastRow)
        for ((index, rows) in walk) {
            val key = onboardingFavoriteGameKey("g$index")
            compose.runOnIdle { state.authority.requestFocus(key) }
            compose.waitForIdle()
            assertEquals("precondition: focus is on game $index", key, state.authority.state.value.focused)

            val grid = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes()
                .maxBy { it.boundsInRoot.height }.boundsInRoot
            val shown = boxesShownIn(grid, density)
            val shownRows = shown.keys.map { it / columns }.distinct().sorted()
            println(
                "FAVORITES/Tome at density $density, focus on game $index: rows $shownRows, " +
                    "${shown.size} boxes, grid ${grid.height / density}dp tall",
            )
            assertEquals("focus on game $index shows these rows", rows, shownRows)
            val expectedBoxes = rows.sumOf { row -> minOf(columns, library.size - row * columns) }
            assertEquals("every box of those rows is on screen", expectedBoxes, shown.size)
            val focused = shown.getValue(index)
            assertTrue(
                "the focused box's ring needs ${OnboardingFavoritesRingClearanceDp}dp above and below it " +
                    "inside the grid: ${focused.top - grid.top}px above, ${grid.bottom - focused.bottom}px below",
                focused.top - grid.top >= OnboardingFavoritesRingClearanceDp * density - 1f &&
                    grid.bottom - focused.bottom >= OnboardingFavoritesRingClearanceDp * density - 1f,
            )
        }

        // And the grid's OWN end, where a touch drag stops rather than where the page seats a row. It has to
        // show whole rows too, and it should be the very position the page seats at the end: the top row one
        // ring clearance down. That holds only while the bottom padding is the clearance the viewport's height
        // counts. The old 16dp put the end 2dp further on -- nothing cut, but the next D-pad press would have
        // nudged the view back by those 2dp.
        val scrollables = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true)
        val gridIndex = scrollables.fetchSemanticsNodes().withIndex().maxBy { it.value.boundsInRoot.height }.index
        scrollables[gridIndex].performScrollToIndex(library.lastIndex)
        compose.waitForIdle()
        val grid = scrollables.fetchSemanticsNodes()[gridIndex].boundsInRoot
        val atEnd = boxesShownIn(grid, density)
        assertEquals(
            "at the grid's own end, the last three rows, whole",
            listOf(3, 4, 5),
            atEnd.keys.map { it / columns }.distinct().sorted(),
        )
        assertEquals(
            "the grid's own end seats its top row where the page does, one ring clearance down",
            OnboardingFavoritesRingClearanceDp * density,
            atEnd.getValue(3 * columns).top - grid.top,
            1f,
        )
    }

    /**
     * Every box the grid draws any part of, by game index, each asserted WHOLE: inside the grid's bounds and
     * a full face tall. A box composed but entirely outside the grid (the lazy grid prefetches) is left out.
     */
    private fun boxesShownIn(grid: Rect, density: Float): Map<Int, Rect> {
        val faceHeightPx = OnboardingFavoritesFaceHeightDp * density
        val shown = mutableMapOf<Int, Rect>()
        val cells = compose.onAllNodes(hasText("Test Game Number ", substring = true)).fetchSemanticsNodes()
        for (node in cells) {
            val title = node.config.getOrNull(SemanticsProperties.Text)
                ?.map { it.text }
                ?.firstOrNull { it.startsWith("Test Game Number ") }
                ?: continue
            val index = title.removePrefix("Test Game Number ").toInt()
            val box = node.boundsInRoot
            val visible = minOf(box.bottom, grid.bottom) - maxOf(box.top, grid.top)
            if (visible <= 0.5f) continue
            assertTrue(
                "box $index is cut off by the grid: box $box, grid $grid -- part of a row is showing",
                box.top >= grid.top - 0.5f && box.bottom <= grid.bottom + 0.5f &&
                    kotlin.math.abs(box.height - faceHeightPx) <= 1.5f,
            )
            shown[index] = box
        }
        return shown
    }

    /** The column count the page computes must be eight here, and eight must fit. */
    @Test
    fun `the reference handheld lays the grid out eight boxes across`() {
        // 832.5dp less the screen's two 16dp gutters is the width the grid measures against.
        val contentWidthDp = odinWidthDp - 32f
        assertEquals(
            "the row must span the available width at this device viewport",
            8,
            onboardingFavoritesColumns(contentWidthDp),
        )
        assertEquals(OnboardingFavoritesReferenceColumns, onboardingFavoritesColumns(contentWidthDp))
        assertTrue(
            "eight boxes, their gutters and their ring clearance must fit inside the content width, or the " +
                "grid is clipped",
            onboardingFavoritesGridViewportWidthDp(8) <= contentWidthDp,
        )
        assertTrue(
            "and a ninth must not, or 'across the screen' left a box's worth of it empty",
            onboardingFavoritesGridViewportWidthDp(9) > contentWidthDp,
        )
    }

    /**
     * The other half of the split: the instructions page has to fit WITHOUT scrolling, so its prose and controls remain visible together.
     *
     * Measured the way [OnboardingScreenFitTest] measures every other prose page -- natural content height
     * under an unbounded probe -- because that is the instrument that catches a page whose trailing
     * controls get crushed rather than overflowing visibly.
     */
    @Test
    fun `the instructions page fits the reference handheld without scrolling`() {
        val state = FlowState()
        var naturalHeightPx by mutableIntStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    val density = LocalDensity.current
                    val widthPx = with(density) { odinWidthDp.dp.roundToPx() }
                    Layout(content = { Flow(state, library) }) { measurables, _ ->
                        val placeable = measurables.first().measure(
                            Constraints(
                                minWidth = widthPx,
                                maxWidth = widthPx,
                                minHeight = 0,
                                maxHeight = Constraints.Infinity,
                            ),
                        )
                        naturalHeightPx = placeable.height
                        layout(widthPx, placeable.height) { placeable.placeRelative(0, 0) }
                    }
                }
            }
        }
        compose.waitForIdle()

        println("FAVORITES/instructions natural height at ${odinWidthDp}dp wide: ${naturalHeightPx}dp")
        assertTrue(
            "the instructions page wants ${naturalHeightPx}dp but the reference handheld has " +
                "${odinHeightDp}dp. This page carries ALL of the step's teaching now, so it is the one " +
                "that grows -- trim the copy rather than letting the pills be crushed.",
            naturalHeightPx <= odinHeightDp,
        )
        assertTrue(
            "a natural height of zero means the probe measured nothing -- the harness is broken, not the page",
            naturalHeightPx > 0,
        )
    }

    /**
     * The 700x350 layout floor, measured 2026-09-28. Measured under that viewport's profile, so the MARK demonstration
     * sizes its picture the way the device does (none there: the words alone, [onboardingFavoritesHintSceneHeight]).
     * This page is included in the floor's fit matrix.
     */
    @Test
    fun `the instructions page fits the 700x350 floor without scrolling`() {
        val state = FlowState()
        var naturalHeightPx by mutableIntStateOf(0)
        val floorWidthDp = 700f
        val floorHeightDp = 350f
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1f),
                com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile provides
                    com.gruning.mimir.rewrite.core.ui.mimirViewportProfile(floorWidthDp, floorHeightDp),
            ) {
                MimirTheme {
                    val density = LocalDensity.current
                    val widthPx = with(density) { floorWidthDp.dp.roundToPx() }
                    Layout(content = { Flow(state, library) }) { measurables, _ ->
                        val placeable = measurables.first().measure(
                            Constraints(minWidth = widthPx, maxWidth = widthPx, minHeight = 0, maxHeight = Constraints.Infinity),
                        )
                        naturalHeightPx = placeable.height
                        layout(widthPx, placeable.height) { placeable.placeRelative(0, 0) }
                    }
                }
            }
        }
        compose.waitForIdle()
        println("FAVORITES/instructions natural height at 700x350: ${naturalHeightPx}dp")
        assertTrue(
            "the instructions page wants ${naturalHeightPx}dp but the 700x350 floor has ${floorHeightDp}dp",
            naturalHeightPx in 1..floorHeightDp.toInt(),
        )
    }
}
