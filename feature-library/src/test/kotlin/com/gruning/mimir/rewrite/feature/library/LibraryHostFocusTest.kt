package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What the Library screen composes with of the authority's focus (owperf, 2026-10-15; see `LibraryHostFocus.kt`). The recomposition counts that show the
 * effect on the real screen are in [OpenWithRecompositionCountTest]; these pin the two rules it rests on, so a change to either fails here by name.
 */
class LibraryHostFocusTest {

    private val key = FocusKey("a/key")

    @Test
    fun `the screen hears which scope is up but not which key, for the card and the two pickers that ask the authority themselves`() {
        listOf(OpenWithScope, LaunchChoiceScope, GameDetailScope).forEach { scope ->
            assertEquals("$scope", FocusState(scope, null), libraryHostFocus(FocusState(scope, key)))
            assertEquals("$scope: a pad move inside it is the same answer", libraryHostFocus(FocusState(scope, key)), libraryHostFocus(FocusState(scope, FocusKey("another"))))
        }
    }

    @Test
    fun `the screen hears every key of the scopes it draws focus for and of the overlays that are handed the key`() {
        listOf(LibraryShelfScope, GamelistScope, LibrarySearchScope, LibrarySearchFilterPickerScope, NativeAddPickerScope, HideConfirmScope).forEach { scope ->
            val state = FocusState(scope, key)
            assertSame("$scope", state, libraryHostFocus(state))
        }
    }

    @Test
    fun `a scope with no key is left as it is`() {
        val state = FocusState(OpenWithScope, null)
        assertSame(state, libraryHostFocus(state))
    }

    @Test
    fun `the gamelist layer hears its own scope and one unowned answer for every other, so nothing on top of it re-runs it`() {
        val owned = FocusState(GamelistScope, key)
        assertSame(owned, libraryGamelistLayerFocus(owned))

        val others = listOf(
            FocusState(GameDetailScope, key),
            FocusState(OpenWithScope, key),
            FocusState(OpenWithScope, null),
            FocusState(LaunchChoiceScope, key),
            FocusState(LibrarySearchScope, key),
            FocusState(LibraryShelfScope, key),
            FocusState(FocusScope.Root, null),
        )
        others.forEach { assertSame("$it", LibraryGamelistLayerUnowned, libraryGamelistLayerFocus(it)) }
        assertNull("the unowned answer lights nothing", LibraryGamelistLayerUnowned.focused)
    }

    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = PlatformId.PS1,
        memberCount = 1,
        duplicateKind = null,
        isFavorite = false,
    )

    @Test
    fun `the departure tracker hands the same frame back while nothing in it changed, and a new one when something did`() {
        val tracker = GamelistDepartureTracker()
        val games = listOf(game("a"), game("b"), game("c"))

        val first = tracker.observe(games, centredIndex = 1, scope = "ps1", leaving = { false }, motionEnabled = true)
        tracker.commit()
        val second = tracker.observe(games, centredIndex = 1, scope = "ps1", leaving = { false }, motionEnabled = true)
        tracker.commit()
        // A focus step inside the same list is a pass too, and the frame is still the same one: the shelf takes it as a parameter.
        val stepped = tracker.observe(games, centredIndex = 2, scope = "ps1", leaving = { false }, motionEnabled = true)
        tracker.commit()

        assertSame("a pass that changed nothing the frame holds", first, second)
        assertSame("a focus step does not change what the frame holds", first, stepped)

        // A different list is a new lineage (the centre is re-created for it, as it always was).
        val other = tracker.observe(listOf(game("x"), game("y")), centredIndex = 0, scope = "ps1", leaving = { false }, motionEnabled = true)
        assertNotSame(first, other)
    }
}
