package com.gruning.mimir.rewrite.feature.library

import android.content.res.Resources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShelfHeadGeometryTest {
    private val english: Resources = RuntimeEnvironment.getApplication().resources

    // The old rail-centering geometry (calculateShelfHeadOrnamentFrame) was removed with LIB-02c — the head
    // row is now a plain full-width Row ([label][beam][tabs] ——spring—— [ADD GAMES]) to match the reference layout, so there is no centering math left to unit-test. The fixed-lane budget still matters.
    @Test
    fun fixedLanesFitThorAndCertifiedLandscapeHeights() {
        assertEquals(441, libraryShelfFixedLaneBudgetDp())
        assertEquals(86, ShelfQuickRailLaneHeightDp)
        assertTrue(ShelfAddPillHeightDp >= 38)
        assertTrue(ShelfAddPillHeightDp <= ShelfHeadRowHeightDp)
        val viewports = listOf(
            960 to 540,
            1920 to 1080,
            833 to 444,
            538 to 444,
        )
        viewports.forEach { (viewportWidth, viewportHeight) ->
            assertTrue(libraryShelfFixedLaneBudgetDp() <= viewportHeight)
            assertTrue(420 <= viewportWidth - 40)
            assertTrue(coverflowFocusedVisualWidthDp(CoverflowMotionFocusWidthDp) <= viewportWidth)
        }
        // QA-25 : was "LIBRARY SHELF". "Shelf" named this screen, a platform
        // row inside it, and Home's favourites surface, so it could not tell a player which one they
        // were on. This screen IS the library.
        assertEquals("Library", english.getString(R.string.library_shelf_title))
    }

    @Test
    fun compactSquareShelfFitsTheRotateWithoutChangingLandscapeGeometry() {
        listOf(360f, 411f, 480f, 720f).forEach { side ->
            val geometry = solveLibraryShelfGeometry(side, side)
            assertTrue("$side square should use compact geometry", geometry.compactSquare)
            assertTrue("$side square fixed lanes", geometry.fixedLaneBudgetDp <= side)
            assertTrue(geometry.horizontalGutterDp >= 12f)
            assertTrue(geometry.artBandHeightDp >= 96f)
            assertTrue(geometry.tileScale in 0.62f..1f)
        }

        val rp5 = solveLibraryShelfGeometry(700f, 350f)
        assertTrue(!rp5.compactSquare)
        // 425, not 441: bottomPaddingDp now reads homeShelfQuickRailBottomGapDp(350f) = 0 -- at this height
        // Home's own rail-top clamp already sits flush against the viewport floor (dock jump fix, 2026-08-19),
        // so the shared source correctly gives Library zero slack here too instead of a stale flat 16dp.
        assertEquals(425f, rp5.fixedLaneBudgetDp, 0f)
        assertEquals(1f, rp5.tileScale, 0f)
    }

    @Test
    fun shelfAndGamelistLegendsUseCappedTruthfulControls() {
        assertEquals(
            listOf(
                // QA-25 : was "SHELF". Now named for what the arrows move among,
                // like the native shelf's "APPS" and the gamelist's "GAMES" below -- one grammar, and
                // "SHELF" no longer collides with Home's.
                "◂ ▸" to "Platforms",
                "L/R" to "Mode",
                "A" to "Open",
                "X" to "Favorites",
                "START" to "Settings",
            ),
            shelfFooterHints(english, nativeMode = false),
        )
        assertEquals(
            "the ANDROID shelf runs the same grammar as the gamelist it is one shoulder-press from",
            listOf(
                "◂ ▸" to "Apps",
                "L/R" to "Mode",
                "A" to "Open",
                "SELECT" to "Favorite",
                "Y" to "Media",
                "START" to "Hide",
            ),
            shelfFooterHints(english, nativeMode = true),
        )
        assertEquals(
            listOf(
                "A" to "Open",
                "B" to "Back",
                "◂▸" to "Games",
                "L/R" to "Platforms",
                // 2026-08-25: SELECT marks the favorite, Y reaches Settings. The last line is also a
                // CORRECTION -- it read "SELECT to HIDE" while the dispatcher had bound hide to START
                // all along, so the legend was naming a key that did not do the thing.
                "SELECT" to "Favorite",
                "X" to "Show favorites",
                "Y" to "Media",
                "START" to "Hide",
            ),
            gamelistFooterHints(
                english,
                inFavorites = false,
                favoritesOnly = false,
                canSwitchFavoriteSegments = false,
            ),
        )
        assertEquals(
            listOf(
                "A" to "Open",
                "B" to "Back",
                "◂▸" to "Games",
                "L/R" to "Platforms",
                "SELECT" to "Favorite",
                "Y" to "Media",
                "START" to "Hide",
            ),
            gamelistFooterHints(
                english,
                inFavorites = false,
                favoritesOnly = false,
                canSwitchFavoriteSegments = false,
                hasFavoritesInPlatform = false,
            ),
        )
        assertTrue(
            gamelistFooterHints(
                english,
                inFavorites = false,
                favoritesOnly = true,
                canSwitchFavoriteSegments = false,
                hasFavoritesInPlatform = false,
            ).contains("X" to "Show all"),
        )
    }
}
