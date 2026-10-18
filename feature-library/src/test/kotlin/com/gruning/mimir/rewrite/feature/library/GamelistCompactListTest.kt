package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardHeight
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compact review 2026-09-27 (LIB-2, LIB-4), policy D3 "drop the panel": on the RG the LIST's 170 dp
 * preview panel left each row title 82 dp wide. Compact LIST paints rows only; RIGHT no longer moves focus.
 */
class GamelistCompactListTest {

    @Test
    fun `compact drops the preview panel and standard keeps it`() {
        assertFalse(gamelistListShowsPreviewPanel(compact = true))
        assertTrue(gamelistListShowsPreviewPanel(compact = false))
    }

    @Test
    fun `with the panel the insets are exactly what they were`() {
        val inset = 91.dp
        assertEquals(
            GamelistListBottomInsets(viewport = 0.dp, content = inset),
            gamelistListBottomInsets(showsPreviewPanel = true, keyboardOpen = false, bottomControlInset = inset),
        )
        assertEquals(
            GamelistListBottomInsets(viewport = 0.dp, content = MimirKeyboardHeight),
            gamelistListBottomInsets(showsPreviewPanel = true, keyboardOpen = true, bottomControlInset = inset),
        )
    }

    @Test
    fun `without the panel the viewport ends above the hint band and the inset is not added twice`() {
        val inset = 91.dp
        val closed = gamelistListBottomInsets(showsPreviewPanel = false, keyboardOpen = false, bottomControlInset = inset)
        assertEquals(inset, closed.viewport)
        assertEquals(0.dp, closed.content)

        // The keyboard's clearance is unchanged: viewport plus content is the keyboard's height.
        val open = gamelistListBottomInsets(showsPreviewPanel = false, keyboardOpen = true, bottomControlInset = inset)
        assertEquals(inset, open.viewport)
        assertEquals(MimirKeyboardHeight, open.viewport + open.content)
    }

    @Test
    fun `an inset taller than the keyboard never gives a negative content padding`() {
        val insets = gamelistListBottomInsets(
            showsPreviewPanel = false,
            keyboardOpen = true,
            bottomControlInset = MimirKeyboardHeight + 10.dp,
        )
        assertEquals(0.dp, insets.content)
    }

    @Test
    fun `compact Favorites ends its list above the M button, and standard Favorites is unchanged`() {
        // Observed: 11 favourites scrolled under the M button (ink 508.2 to 541.2 dp) on the RG square.
        // The envelope is the 16 dp pad plus the 48 dp button, plus the platform list's 8 dp clearance.
        assertEquals(72f, gamelistFavoritesBottomControlInsetDp(compact = true), 0f)
        assertEquals(0f, gamelistFavoritesBottomControlInsetDp(compact = false), 0f)
        // It clears the button envelope by the same margin the platform list keeps, minus the L1/R1 hint it lacks.
        assertEquals(
            gamelistListBottomControlInsetDp(hintTextHeightDp = 0f) - HintRailButtonGap.value,
            gamelistFavoritesBottomControlInsetDp(compact = true),
            0.001f,
        )
    }

    @Test
    fun `RIGHT on a compact LIST row, with no seat registered, stays on the row`() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply {
            openPlatform(PlatformId.PS1)
            toggleGamelistViewMode()
        }
        assertEquals(GamelistViewMode.LIST, viewModel.gamelistViewMode.value)
        val game = viewModel.games.first { it.isNotEmpty() }.first()
        val gameFocusKey = FocusKey("library/gamelist/${game.gameId.value}")
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(gameFocusKey, GamelistScope, FocusBounds(0f, 0f, 500f, 80f))
            requestFocus(gameFocusKey)
        }
        val detailBefore = viewModel.detailGame.value

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.NAV_RIGHT,
                onExit = {},
                onRescanFolder = {},
            ),
        )

        assertEquals(gameFocusKey, authority.state.value.focused)
        assertEquals("no seat, so RIGHT does not select the game for one", detailBefore, viewModel.detailGame.value)
    }
}
