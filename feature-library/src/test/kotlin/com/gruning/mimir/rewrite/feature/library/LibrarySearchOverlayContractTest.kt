package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchOverlayContractTest {

    @Test
    fun wideAndCompactGeometryKeepMinimumControlAndResultClearance() {
        val wide = resolveLibrarySearchGeometry(
            widthDp = 900f,
            heightDp = 540f,
            fontScale = 1f,
        )
        val narrow = resolveLibrarySearchGeometry(
            widthDp = 480f,
            heightDp = 520f,
            fontScale = 1f,
        )
        val short = resolveLibrarySearchGeometry(
            widthDp = 800f,
            heightDp = 420f,
            fontScale = 1f,
        )
        val largeText = resolveLibrarySearchGeometry(
            widthDp = 800f,
            heightDp = 520f,
            fontScale = 1.2f,
        )

        assertTrue(wide.topControlsSingleRow)
        assertFalse(wide.compact)
        assertFalse(narrow.topControlsSingleRow)
        assertTrue(narrow.compact)
        assertTrue(short.compact)
        assertTrue(largeText.compact)
        listOf(wide, narrow, short, largeText).forEach { geometry ->
            assertTrue(geometry.controlMinHeightDp >= 44f)
            assertTrue(geometry.resultMinHeightDp >= 50f)
            assertTrue(geometry.panelHorizontalInsetDp > 0f)
            assertTrue(geometry.panelVerticalInsetDp > 0f)
        }
    }

    /**
     * The search panel's own geometry and its two content-padding constants.
     *
     * This test once also pinned a primary-M reservation on this surface -- first a BOTTOM one, then a
     * trailing one that followed the orb. Both are gone: no surface reserves for the orb (policy
     * 2026-08-21). What survives here is the part that was always this surface's own law, and the bottom
     * padding is the plain 10dp of breathing room it was underneath the arithmetic.
     */
    @Test
    fun compactPanelGeometryAndContentPaddingsAreTheOdinsOwn() {
        // Odin-class: 833x444dp. NOTE the Odin is `compact` by this surface's own rule -- the predicate
        // is `widthDp < 600f || heightDp < 480f || fontScale > 1.1f`, and 444 < 480 -- so the panel takes
        // the 14/10 insets, not the 28/20 pair.
        val geometry = resolveLibrarySearchGeometry(widthDp = 833f, heightDp = 444f, fontScale = 1f)
        assertTrue(geometry.compact)
        assertEquals(14f, geometry.panelHorizontalInsetDp, 0f)
        assertEquals(10f, geometry.panelVerticalInsetDp, 0f)

        assertEquals(10f, LibrarySearchResultBottomPaddingDp, 0f)
        // No trailing padding: the rows end where the fields above them end (update's sweep, 10-09). A vertical list clips 30 dp out on its sides, so a ring reaches past the edge.
        assertEquals(0f, LibrarySearchResultEndPaddingDp, 0f)
    }

    @Test
    fun fixedPlatformAndFavoriteGamesExposeOnlyTheirApprovedTopControls() {
        assertEquals(
            listOf(LibrarySearchQueryControlKey, LibrarySearchGenreFilterKey),
            librarySearchTopControlKeys(
                LibrarySearchOrigin.PlatformGames(PlatformId.PS1),
            ),
        )
        assertEquals(
            listOf(
                LibrarySearchQueryControlKey,
                LibrarySearchGenreFilterKey,
                LibrarySearchPlatformFilterKey,
            ),
            librarySearchTopControlKeys(LibrarySearchOrigin.FavoriteGames),
        )
    }

    @Test
    fun searchAndBaseLauncherKeysAreGloballyDistinctAndCorrectlyScopedByPrefix() {
        val searchKeys = listOf(
            LibrarySearchQueryControlKey,
            LibrarySearchGenreFilterKey,
            LibrarySearchPlatformFilterKey,
            LibrarySearchClearKey,
            librarySearchGameResultKey(com.gruning.mimir.rewrite.core.model.GameId("ps1/test")),
        )

        assertEquals(searchKeys.size, searchKeys.distinct().size)
        assertTrue(searchKeys.all { it.value.startsWith("library/search/") })
        assertEquals("library/gamelist/search", LibrarySearchLauncherKey.value)
        assertFalse(LibrarySearchLauncherKey in searchKeys)
    }

    @Test
    fun compactThreeSixtyHeaderKeepsAVisibleControllerSearchSeat() {
        // 360dp viewport minus Library's 32dp horizontal shell padding on both sides.
        val geometry = resolveGamelistHeaderSearchGeometry(
            availableWidthDp = 296f,
            compact = true,
        )

        assertTrue(geometry.showSearch)
        assertTrue(geometry.searchWidthDp >= LibraryGamelistSearchReadabilityFloorDp)
        assertEquals(
            296f - LibraryGamelistStatusEndInsetDp,
            geometry.titleMaxWidthDp,
            0f,
        )
        assertTrue(LibraryGamelistSearchControlMinHeightDp >= 44f)
    }

    /**
     * Search-game clipping differed between Odin/Thor and Nova. Odin's post-inset shelf box (833x444dp,
     * `MimirCertifiedViewports.kt`) and Thor's Main measured box (832.5x444.4dp,
     * `ShelfViewportEvidenceTest.kt:11`) both land in the wide/shared-row (`compact = false`) branch once
     * Library's own 32dp horizontal shell padding on both sides (`MimirSpacing.xxl`) comes off the full
     * viewport width. Nova's smaller 640dp-wide viewport trips the compact branch instead -- and, before
     * this fix, was handed a WIDER search pill (136dp) than either Odin or Thor (120dp, the wide branch's
     * own independently-tuned, lower ceiling) -- the exact inversion the regression report described.
     */
    @Test
    fun gamelistSearchAtOdinAndThorIsNeverNarrowerThanCompactBranch() {
        val odin = resolveGamelistHeaderSearchGeometry(availableWidthDp = 833f - 64f, compact = false)
        val thorMain = resolveGamelistHeaderSearchGeometry(availableWidthDp = 832.5f - 64f, compact = false)
        val nova = resolveGamelistHeaderSearchGeometry(availableWidthDp = 640f - 64f, compact = true)

        // The wide/shared-row branch must never hand Search less room than the compact branch grants.
        assertTrue(odin.searchWidthDp >= nova.searchWidthDp)
        assertTrue(thorMain.searchWidthDp >= nova.searchWidthDp)

        // ...and it must not buy that back by starving the title/metadata cluster it shares the row with.
        assertTrue(odin.titleMaxWidthDp > 0f)
        assertTrue(thorMain.titleMaxWidthDp > 0f)
    }

    @Test
    fun dedicatedSearchLauncherRemainsVisibleForEmptyFilteredGamesButNativeLegacyDoesNot() {
        assertTrue(
            libraryGamelistHeaderSearchVisible(
                dedicatedSearchEnabled = true,
                hasVisibleRows = false,
                query = "",
            ),
        )
        assertFalse(
            libraryGamelistHeaderSearchVisible(
                dedicatedSearchEnabled = false,
                hasVisibleRows = false,
                query = "",
            ),
        )
    }

    @Test
    fun mutableCriteriaAndFocusKeepOneStableSearchTransitionIdentity() {
        val origin = LibrarySearchOrigin.PlatformGames(PlatformId.PS1)
        val base = LibrarySearchSession(
            origin = origin,
            originFocus = LibrarySearchOriginFocusIdentity.SearchLauncher,
        )
        val changed = base.copy(
            criteria = LibrarySearchCriteria(query = "Metroid"),
            lastFocused = LibrarySearchFocusIdentity.Result(
                LibrarySearchResultIdentity.Game(GameId("ps1/metroid")),
            ),
        )

        assertEquals(origin, librarySearchTransitionKey(base))
        assertEquals(librarySearchTransitionKey(base), librarySearchTransitionKey(changed))
        assertEquals(null, librarySearchTransitionKey(null))
    }

    @Test
    fun preseededSearchAndPickerScopesNeverFlashTheirFirstRegisteredResult() {
        val authority = DefaultFocusAuthority()
        val result = librarySearchGameResultKey(GameId("ps1/first"))
        authority.rememberFocusForScope(LibrarySearchScope, LibrarySearchQueryControlKey)
        authority.pushScope(LibrarySearchScope)
        authority.registerVirtual(result, LibrarySearchScope, bounds(0f))
        assertEquals(null, authority.state.value.focused)
        authority.register(LibrarySearchQueryControlKey, LibrarySearchScope, bounds(100f))
        assertEquals(LibrarySearchQueryControlKey, authority.state.value.focused)

        val selected = librarySearchGenrePickerKey(null)
        authority.rememberFocusForScope(LibrarySearchFilterPickerScope, selected)
        authority.pushScope(LibrarySearchFilterPickerScope)
        val firstExact = librarySearchGenrePickerKey(
            LibrarySearchGenreOption(LibrarySearchGenreKey.from("action")!!, "Action"),
        )
        authority.registerVirtual(firstExact, LibrarySearchFilterPickerScope, bounds(0f))
        assertEquals(null, authority.state.value.focused)
        authority.register(selected, LibrarySearchFilterPickerScope, bounds(100f))
        assertEquals(selected, authority.state.value.focused)
    }

    @Test
    fun focusedLauncherTouchUsesPreparedVisibleOriginInsteadOfLosingIt() {
        val first = game("ps1/first")
        val prepared = game("ps1/prepared")

        assertEquals(
            prepared.gameId,
            resolveLibrarySearchTouchOrigin(
                focused = LibrarySearchLauncherKey,
                games = listOf(first, prepared),
                detailGame = first,
                preparedGameId = prepared.gameId,
            ),
        )
    }

    @Test
    fun pickerSelectedKeyUsesCurrentExactCriterionBeforeScopePush() {
        val action = LibrarySearchGenreOption(
            key = LibrarySearchGenreKey.from("Action")!!,
            label = "Action",
        )
        val facets = LibrarySearchFacets(
            genres = listOf(action),
            platforms = listOf(PlatformId.PS1),
        )
        assertEquals(
            librarySearchGenrePickerKey(action),
            librarySearchSelectedPickerKey(
                picker = LibrarySearchPicker.GENRE,
                criteria = LibrarySearchCriteria(
                    genre = LibrarySearchGenreFilter.Exact(action.key),
                ),
                facets = facets,
            ),
        )
        assertEquals(
            librarySearchPlatformPickerKey(PlatformId.PS1),
            librarySearchSelectedPickerKey(
                picker = LibrarySearchPicker.PLATFORM,
                criteria = LibrarySearchCriteria(
                    platform = LibrarySearchPlatformFilter.Exact(PlatformId.PS1),
                ),
                facets = facets,
            ),
        )
        assertEquals(
            librarySearchPlatformPickerKey(null),
            librarySearchSelectedPickerKey(
                picker = LibrarySearchPicker.PLATFORM,
                criteria = LibrarySearchCriteria(
                    platform = LibrarySearchPlatformFilter.Exact(PlatformId.PS2),
                ),
                facets = facets,
            ),
        )
    }

    private fun bounds(top: Float) = FocusBounds(0f, top, 100f, top + 80f)

    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = PlatformId.PS1,
        memberCount = 1,
        duplicateKind = null,
    )
}
