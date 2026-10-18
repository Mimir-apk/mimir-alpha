package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

/**
 * The FAVORITES step's TOME page: a grid of scraped boxes under ONE control row — a search field and,
 * when there is more than one platform, a filter that opens a list.
 *
 * The former single-page version was split into instructions and an Open Tome grid using the Tome
 * collection pattern and a search filter. Its initial 4x4 target became smaller 5x3 art, then a
 * full-width row holding as many boxes as fit at that size. The viewport follows focus to avoid clipping.
 *
 * This file pins the GEOMETRY. The step's behavioural rulings — SELECT toggles and never advances, A
 * toggles because the welcome screen promised it would, the pill leaves on A only, the copy teaches SELECT,
 * "Skip for now" vs "Continue", the product's own noun — live in [OnboardingFavoritesStepTest] and are
 * unchanged by the layout. A grid moves boxes around; it does not get to re-decide what a button means.
 *
 * Everything here is a pure function on purpose. Focus movement across a ragged grid is arithmetic, and
 * arithmetic pinned in a composable is arithmetic nobody can test without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingFavoritesGridTest {
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

    private fun game(id: String, platform: String, title: String = id) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId(platform),
        memberCount = 1,
        duplicateKind = null,
    )

    /** Seven games over three columns: rows of 3, 3, then a single-cell remainder. */
    private val seven = (0 until 7).map { game("g$it", "snes") }

    // ── Columns: one source of truth ──────────────────────────────────────────────────────────────

    @Test
    fun `column count is every box of the face size that fits the width`() {
        // The composable's GridCells.Fixed and the focus mover's UP/DOWN stride BOTH read this. If they
        // ever read different numbers, focus jumps a different distance than the eye does.
        //
        // 800.5f is the Thor: 1080x1920 at density 369 is 832.5dp wide in landscape, less the 16dp gutters.
        // Run 11 asked for the row to reach across it: eight boxes, the most that fit with their gaps and
        // ring clearance (nine would need 823dp).
        assertEquals(8, onboardingFavoritesColumns(800.5f))
        assertEquals(OnboardingFavoritesReferenceColumns, onboardingFavoritesColumns(800.5f))
        assertEquals(
            "no cap: a wider window gets more boxes at the same size, never bigger ones",
            13,
            onboardingFavoritesColumns(1200f),
        )
        assertEquals("a narrow window drops columns at the SAME box size", 4, onboardingFavoritesColumns(400f))
        assertEquals(2, onboardingFavoritesColumns(240f))
        // Never zero. The mover divides by this, and a width measured before first layout is 0.
        assertEquals(1, onboardingFavoritesColumns(0f))
    }

    @Test
    fun `row count is the whole rows that fit, and the viewport is exactly that many rows tall`() {
        // 374dp is the height the page left the grid on the Thor before run 11: three rows, and 3dp that
        // could only ever show a sliver of a fourth.
        assertEquals(3, onboardingFavoritesRows(374f))
        val threeRows = onboardingFavoritesGridViewportHeightDp(3)
        assertEquals(
            "three faces, two gaps, and a ring clearance above and below",
            3 * OnboardingFavoritesFaceHeightDp + 2 * OnboardingFavoritesGridGapDp +
                2 * OnboardingFavoritesRingClearanceDp,
            threeRows,
            0.01f,
        )
        assertTrue("the viewport fits the room it was counted from", threeRows <= 374f)
        assertEquals("exactly three rows' room is three rows", 3, onboardingFavoritesRows(threeRows))
        assertEquals("a dp short of it is two", 2, onboardingFavoritesRows(threeRows - 1f))
        // Never zero: a grid squeezed below one row still shows one row rather than nothing.
        assertEquals(1, onboardingFavoritesRows(0f))
        assertEquals(onboardingFavoritesGridViewportHeightDp(1), onboardingFavoritesGridViewportHeightDp(0), 0.01f)
    }

    @Test
    fun `the row after the last whole one starts below the viewport, so none of it shows`() {
        // A row seated at the top edge starts one ring clearance down. The next row after [rows] of them
        // starts one gap after the last one's bottom, and that has to be past the viewport's bottom edge --
        // the property the 16dp bottom padding broke.
        for (rows in 1..5) {
            val nextRowTop = OnboardingFavoritesRingClearanceDp +
                rows * (OnboardingFavoritesFaceHeightDp + OnboardingFavoritesGridGapDp)
            assertTrue(
                "with $rows rows the next one starts at ${nextRowTop}dp, inside a " +
                    "${onboardingFavoritesGridViewportHeightDp(rows)}dp viewport",
                nextRowTop > onboardingFavoritesGridViewportHeightDp(rows),
            )
        }
        // And the clearance still holds the ring: [mimirFocusTileRing] draws at outsets up to 10dp.
        assertTrue(OnboardingFavoritesRingClearanceDp >= 10f)
        assertTrue(OnboardingFavoritesRingClearanceDp < OnboardingFavoritesGridGapDp)
    }

    @Test
    fun `the focused row sits between the one before it and the one after, until the ends`() {
        // 44 games, eight across: rows 0..5, the last one holding four. Three rows show.
        fun top(index: Int) = onboardingFavoritesTopRow(index, itemCount = 44, columns = 8, rows = 3)
        assertEquals("the first row opens at the top: nothing precedes it", 0, top(0))
        assertEquals(
            "owner, run 13: DOWN into row 1 shows row 0 above it and row 2 below, not row 1 on top",
            0,
            top(8),
        )
        assertEquals(0, top(15))
        assertEquals("row 2 carries the view: rows 1, 2 and 3", 1, top(16))
        assertEquals("and row 3: rows 2, 3 and 4", 2, top(24))
        assertEquals(
            "row 4 would centre on row 3, which is also the last row that can be on top",
            3,
            top(32),
        )
        assertEquals("the ragged last row stops at the end instead of leaving empty room", 3, top(43))
        // A viewport with no middle keeps the focused row at the top edge.
        assertEquals("one row: the focused row itself", 2, onboardingFavoritesTopRow(16, 44, 8, 1))
        assertEquals("two rows: the focused row, with the next one under it", 2, onboardingFavoritesTopRow(16, 44, 8, 2))
        // Five rows have two above the focused one.
        assertEquals(1, onboardingFavoritesTopRow(24, itemCount = 44, columns = 8, rows = 5))
        // A library that fits entirely never scrolls.
        assertEquals(0, onboardingFavoritesTopRow(23, itemCount = 24, columns = 8, rows = 3))
        assertEquals(0, onboardingFavoritesTopRow(5, itemCount = 6, columns = 8, rows = 3))
        // Nothing focused among the games (the search field, an empty list): the top.
        assertEquals(0, onboardingFavoritesTopRow(-1, itemCount = 44, columns = 8, rows = 3))
        assertEquals(0, onboardingFavoritesTopRow(0, itemCount = 0, columns = 8, rows = 3))
        assertEquals(0, onboardingFavoritesTopRow(50, itemCount = 44, columns = 8, rows = 3))
    }

    @Test
    fun `the grid is exactly as wide as its boxes, so it can be centred rather than stretched`() {
        // The Tome collection holds its three wells in a centred 398dp column and leaves the rest of the
        // screen alone (HomeTomeLibraryGridContentWidthDp). Five of this grid's faces is 435dp, which is what
        // the Thor's 832dp centres. Stretching instead would give five 150dp covers: two rows on that
        // screen, not the three the request specified for.
        assertEquals(435f, onboardingFavoritesGridWidthDp(5), 0.01f)
        assertEquals(75f, onboardingFavoritesGridWidthDp(1), 0.01f)
        assertEquals("a zero column count must not produce a negative width", 75f, onboardingFavoritesGridWidthDp(0), 0.01f)
    }

    @Test
    fun `the grid's viewport holds the ring clearance as well as the boxes`() {
        // The clearance is content padding, which sits INSIDE the viewport. A viewport only as wide as the
        // boxes squeezes every box by 2 x clearance / columns -- the grid did exactly that until run 9,
        // declaring 112.5dp faces and drawing 105.5dp ones.
        assertEquals(
            onboardingFavoritesGridWidthDp(5) + 2 * OnboardingFavoritesRingClearanceDp,
            onboardingFavoritesGridViewportWidthDp(5),
            0.01f,
        )
        // And the column count pays for that clearance before it counts boxes: a width that holds five
        // boxes but not their clearance gets four, rather than five boxes squeezed by the padding.
        val fiveBoxesExactly = onboardingFavoritesGridWidthDp(5)
        assertEquals(4, onboardingFavoritesColumns(fiveBoxesExactly))
        assertEquals(5, onboardingFavoritesColumns(onboardingFavoritesGridViewportWidthDp(5)))
    }

    @Test
    fun `the control row is as wide as the boxes under it, but never too thin for its field`() {
        assertEquals(onboardingFavoritesGridWidthDp(5), onboardingFavoritesHeadBandWidthDp(5), 0.01f)
        assertEquals(onboardingFavoritesGridWidthDp(4), onboardingFavoritesHeadBandWidthDp(4), 0.01f)
        assertEquals(
            "a two-box grid is too thin a row for a readable search field",
            OnboardingFavoritesHeadBandMinWidthDp,
            onboardingFavoritesHeadBandWidthDp(2),
            0.01f,
        )
    }

    // ── The platform filter ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a single-platform library gets no filter, because a filter with one option is not a filter`() {
        val onePlatform = listOf(game("a", "snes"), game("b", "snes"))
        assertEquals(listOf(PlatformId("snes")), onboardingFavoritesPlatforms(onePlatform))
        assertFalse(onboardingFavoritesHasPlatformFilter(onboardingFavoritesPlatforms(onePlatform)))
        assertEquals(
            "the control row is then the search field alone",
            listOf("onboarding/favorites/search"),
            onboardingFavoritesHeadKeys(onboardingFavoritesPlatforms(onePlatform)).map { it.value },
        )
    }

    @Test
    fun `two platforms get one filter control, and its list is All plus each platform in library order`() {
        val mixed = listOf(game("a", "ps1"), game("b", "snes"), game("c", "ps1"))
        val platforms = onboardingFavoritesPlatforms(mixed)
        assertEquals(listOf(PlatformId("ps1"), PlatformId("snes")), platforms)
        assertTrue(onboardingFavoritesHasPlatformFilter(platforms))
        // ONE control, not a chip per platform. The rail it replaced was the second focusable row that
        // UP out of the grid kept getting lost in.
        assertEquals(
            listOf("onboarding/favorites/search", "onboarding/favorites/platformFilter"),
            onboardingFavoritesHeadKeys(platforms).map { it.value },
        )
        // Null is "All" and leads, so index 0 is always the un-filtered option.
        assertEquals(
            listOf(null, PlatformId("ps1"), PlatformId("snes")),
            onboardingFavoritesPlatformOptions(platforms),
        )
    }

    /**
     * Use library display names such as PlayStation 3 and Xbox 360 rather than scanner IDs.
     */
    @Test
    fun `the closed filter says which platform it is on, by name`() {
        assertEquals(
            "Platform \u00b7 All",
            onboardingFavoritesPlatformFilterIntent(res, null).spokenText,
        )
        assertEquals(
            "Platform \u00b7 Super Nintendo",
            onboardingFavoritesPlatformFilterIntent(res, PlatformId("snes")).spokenText,
        )
        assertEquals(
            "Platform \u00b7 Game Boy Advance",
            onboardingFavoritesPlatformFilterIntent(res, PlatformId("gba")).spokenText,
        )
        assertEquals(
            "Platform \u00b7 PlayStation 3",
            onboardingFavoritesPlatformFilterIntent(res, PlatformId("ps3")).spokenText,
        )
        assertEquals(
            "Platform \u00b7 Xbox 360",
            onboardingFavoritesPlatformFilterIntent(res, PlatformId("xbox360")).spokenText,
        )
    }

    /**
     * The two halves are different ROLES, and only the type carries that.
     *
     * A Chrome fragment is the product talking: `resolve()` uppercases it and [MimirAnimatedChromeText]
     * sweeps it. An Authored fragment is the player's own library talking, rendered verbatim. Build
     * "Platform \u00b7 Super Nintendo" as a single Chrome fragment -- which is precisely what the
     * plain-string caption this replaced produced -- and the platform is shouted as SUPER NINTENDO and swept
     * by the chrome animation. That is the product editing the player's data on screen.
     *
     * The assertion above cannot see any of it: both shapes spell the same sentence.
     */
    @Test
    fun `the platform value is authored, not chrome`() {
        val intent = onboardingFavoritesPlatformFilterIntent(res, PlatformId("snes"))
        assertEquals(
            listOf(
                MimirTextFragment.Chrome("Platform"),
                MimirTextFragment.Chrome(" \u00b7 "),
                MimirTextFragment.Authored("Super Nintendo"),
            ),
            (intent as MimirTextIntent.Mixed).fragments,
        )
    }

    @Test
    fun `the open list moves one step at a time and stops at both ends`() {
        // Clamped rather than wrapping, for the reason the grid clamps: a list this short reads as a jump
        // rather than a cycle, and holding DOWN to reach the last platform should arrive and stop.
        assertEquals(1, onboardingFavoritesFilterOptionDestination(3, 0, ControllerAction.NAV_DOWN))
        assertEquals(0, onboardingFavoritesFilterOptionDestination(3, 1, ControllerAction.NAV_UP))
        assertNull(onboardingFavoritesFilterOptionDestination(3, 0, ControllerAction.NAV_UP))
        assertNull(onboardingFavoritesFilterOptionDestination(3, 2, ControllerAction.NAV_DOWN))
        assertNull("a sideways press is not a move in a single column", onboardingFavoritesFilterOptionDestination(3, 1, ControllerAction.NAV_LEFT))
        assertNull("and an empty list cannot be indexed", onboardingFavoritesFilterOptionDestination(0, 0, ControllerAction.NAV_DOWN))
    }

    // ── The two filters ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the filter narrows to one platform and null means all of them`() {
        val mixed = listOf(game("a", "ps1"), game("b", "snes"), game("c", "ps1"))
        assertEquals(mixed, onboardingFavoritesVisibleGames(mixed, platform = null))
        assertEquals(
            listOf("a", "c"),
            onboardingFavoritesVisibleGames(mixed, PlatformId("ps1")).map { it.gameId.value },
        )
    }

    @Test
    fun `the search matches titles, ignoring case and surrounding space`() {
        val library = listOf(
            game("a", "snes", "Super Metroid"),
            game("b", "snes", "Chrono Trigger"),
            game("c", "ps1", "Metal Gear Solid"),
        )
        assertEquals(
            "a substring anywhere in the title, not just a prefix",
            listOf("a", "c"),
            onboardingFavoritesVisibleGames(library, null, "MET").map { it.gameId.value },
        )
        assertEquals(
            listOf("b"),
            onboardingFavoritesVisibleGames(library, null, "  chrono  ").map { it.gameId.value },
        )
        assertEquals(
            "a blank query is not a filter",
            library,
            onboardingFavoritesVisibleGames(library, null, "   "),
        )
        assertEquals(
            emptyList<GameRow>(),
            onboardingFavoritesVisibleGames(library, null, "castlevania"),
        )
    }

    @Test
    fun `the rail and the search narrow together rather than replacing each other`() {
        // A player who picked SNES and then typed "met" means both. A search that silently widened back to
        // every platform would be a filter undoing the filter beside it.
        val library = listOf(
            game("a", "snes", "Super Metroid"),
            game("c", "ps1", "Metal Gear Solid"),
        )
        assertEquals(
            listOf("a"),
            onboardingFavoritesVisibleGames(library, PlatformId("snes"), "met").map { it.gameId.value },
        )
    }

    // ── The two key lists ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the intro page offers the Tome and the way out, in that order`() {
        assertEquals(
            listOf("onboarding/favorites/openTome", "onboarding/favorites/continue"),
            onboardingFavoritesIntroKeys().map { it.value },
        )
    }

    @Test
    fun `the tome page is search, then the filter, then every visible game`() {
        // The mover is pure index arithmetic off this one ordering, so it is pinned here rather than
        // inferred at three call sites.
        val mixed = listOf(game("a", "ps1"), game("b", "snes"))
        val keys = onboardingFavoritesFocusKeys(mixed, onboardingFavoritesPlatforms(mixed))
        assertEquals(
            listOf(
                "onboarding/favorites/search",
                "onboarding/favorites/platformFilter",
                "onboarding/favorites/game/a",
                "onboarding/favorites/game/b",
            ),
            keys.map { it.value },
        )
        // There is NO Continue pill on this page. It lives on the intro page, and B is the way back --
        // which is what stops DOWN off the last row from dropping the player onto a button they did not
        // aim at, the bug this mover used to have to special-case.
        assertEquals(
            "the pill does not belong to the grid",
            emptyList<String>(),
            keys.map { it.value }.filter { it == "onboarding/favorites/continue" },
        )
    }

    // ── Movement across a ragged grid ─────────────────────────────────────────────────────────────

    private fun keys(games: List<GameRow>, platforms: List<PlatformId> = emptyList()) =
        onboardingFavoritesFocusKeys(games, platforms)

    private fun move(
        games: List<GameRow>,
        from: String,
        action: ControllerAction,
        columns: Int = 3,
        platforms: List<PlatformId> = emptyList(),
    ): String? {
        val all = keys(games, platforms)
        val current = all.first { it.value == from }
        return onboardingFavoritesFocusDestination(
            keys = all,
            current = current,
            action = action,
            columns = columns,
            headSize = onboardingFavoritesHeadKeys(platforms).size,
        )?.value
    }

    @Test
    fun `DOWN from a full row into a short one lands on that row's last cell, not past it`() {
        // Seven games, three columns: the last row holds only g6. DOWN from g4 (row 1, column 1) has no
        // cell beneath it. Falling through here is the classic ragged-grid bug -- the player presses DOWN
        // expecting the next row and leaves the grid entirely.
        assertEquals("onboarding/favorites/game/g6", move(seven, "onboarding/favorites/game/g4", ControllerAction.NAV_DOWN))
        assertEquals("onboarding/favorites/game/g5", move(seven, "onboarding/favorites/game/g2", ControllerAction.NAV_DOWN))
        // Column 0 has a real cell beneath it and must take the ordinary path.
        assertEquals("onboarding/favorites/game/g6", move(seven, "onboarding/favorites/game/g3", ControllerAction.NAV_DOWN))
    }

    @Test
    fun `the bottom row is the bottom of the page, because B is the way out and DOWN is not`() {
        assertNull(
            "nothing below the last row any more -- the pill moved to the intro page",
            move(seven, "onboarding/favorites/game/g6", ControllerAction.NAV_DOWN),
        )
        assertEquals(
            "onboarding/favorites/game/g3",
            move(seven, "onboarding/favorites/game/g6", ControllerAction.NAV_UP),
        )
    }

    @Test
    fun `LEFT and RIGHT clamp at a row's edges rather than wrapping`() {
        // A cursor that wraps from the end of one row to the start of the next reads as a jump on a
        // surface made of boxes.
        assertNull(move(seven, "onboarding/favorites/game/g3", ControllerAction.NAV_LEFT))
        assertNull(move(seven, "onboarding/favorites/game/g5", ControllerAction.NAV_RIGHT))
        assertNull("the short last row has nothing to its right", move(seven, "onboarding/favorites/game/g6", ControllerAction.NAV_RIGHT))
        assertEquals("onboarding/favorites/game/g4", move(seven, "onboarding/favorites/game/g3", ControllerAction.NAV_RIGHT))
        assertEquals("onboarding/favorites/game/g3", move(seven, "onboarding/favorites/game/g4", ControllerAction.NAV_LEFT))
    }

    @Test
    fun `at four columns the stride is four, which is the whole reason the screen reports what it laid out`() {
        val twelve = (0 until 12).map { game("h$it", "snes") }
        assertEquals(
            "onboarding/favorites/game/h4",
            move(twelve, "onboarding/favorites/game/h0", ControllerAction.NAV_DOWN, columns = 4),
        )
        assertEquals(
            "onboarding/favorites/game/h3",
            move(twelve, "onboarding/favorites/game/h7", ControllerAction.NAV_UP, columns = 4),
        )
        assertNull(
            "column 3 is the edge at four wide",
            move(twelve, "onboarding/favorites/game/h3", ControllerAction.NAV_RIGHT, columns = 4),
        )
    }

    // ── The rows above the grid ───────────────────────────────────────────────────────────────────

    @Test
    fun `UP out of the grid always lands on the search field, whatever column you left from`() {
        // A reported UP-navigation failure, 2026-09-17. The rail this replaced moved UP into the chip at the focused column, and
        // a rail wider than the screen puts most of its chips off-screen -- focus was not lost, it was
        // parked somewhere invisible, which reads as a screen that stopped responding.
        //
        // Every column, not one: a rule that holds for column 0 and not column 3 is the same bug.
        val platforms = listOf(PlatformId("ps1"), PlatformId("snes"))
        val twelve = (0 until 12).map { game("h$it", if (it % 2 == 0) "ps1" else "snes") }
        (0 until 4).forEach { column ->
            assertEquals(
                "UP from column $column of the top row",
                "onboarding/favorites/search",
                move(
                    twelve,
                    "onboarding/favorites/game/h$column",
                    ControllerAction.NAV_UP,
                    columns = 4,
                    platforms = platforms,
                ),
            )
        }
        assertNull(
            "and the control row is the top of the page",
            move(twelve, "onboarding/favorites/search", ControllerAction.NAV_UP, columns = 4, platforms = platforms),
        )
        assertEquals(
            "onboarding/favorites/game/h0",
            move(twelve, "onboarding/favorites/search", ControllerAction.NAV_DOWN, columns = 4, platforms = platforms),
        )
    }

    @Test
    fun `the control row is two keys wide and clamps at both ends`() {
        val platforms = listOf(PlatformId("ps1"), PlatformId("snes"))
        val mixed = listOf(game("a", "ps1"), game("b", "snes"))
        assertEquals(
            "onboarding/favorites/platformFilter",
            move(mixed, "onboarding/favorites/search", ControllerAction.NAV_RIGHT, platforms = platforms),
        )
        assertEquals(
            "onboarding/favorites/search",
            move(mixed, "onboarding/favorites/platformFilter", ControllerAction.NAV_LEFT, platforms = platforms),
        )
        assertNull(move(mixed, "onboarding/favorites/search", ControllerAction.NAV_LEFT, platforms = platforms))
        assertNull(move(mixed, "onboarding/favorites/platformFilter", ControllerAction.NAV_RIGHT, platforms = platforms))
        assertEquals(
            "DOWN from either control enters the grid at its first box",
            "onboarding/favorites/game/a",
            move(mixed, "onboarding/favorites/platformFilter", ControllerAction.NAV_DOWN, platforms = platforms),
        )
    }

    @Test
    fun `with no filter, the control row is the search field alone`() {
        assertEquals("onboarding/favorites/search", move(seven, "onboarding/favorites/game/g0", ControllerAction.NAV_UP))
        assertEquals("onboarding/favorites/search", move(seven, "onboarding/favorites/game/g1", ControllerAction.NAV_UP))
        assertEquals("onboarding/favorites/game/g0", move(seven, "onboarding/favorites/search", ControllerAction.NAV_DOWN))
    }

    // ── The empty page ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an empty grid cannot be made to crash or to trap`() {
        // Two ordinary ways to reach this: a library still arriving, and a search that matched nothing.
        // `coerceIn(0, -1)` THROWS, so every direction has to survive a zero game count -- a player must
        // never be able to crash setup by pressing DOWN while their library is still landing.
        val all = onboardingFavoritesFocusKeys(emptyList())
        assertEquals(listOf("onboarding/favorites/search"), all.map { it.value })
        ControllerAction.entries.filter { it.isDirectional }.forEach { action ->
            assertNull(
                "$action on an empty page must be a no-op, not an exception",
                onboardingFavoritesFocusDestination(
                    keys = all,
                    current = OnboardingFavoritesSearchKey,
                    action = action,
                    columns = 4,
                    headSize = 1,
                ),
            )
        }
    }
}
