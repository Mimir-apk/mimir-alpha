package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.MimirKeyboardInitialKeyId
import com.gruning.mimir.rewrite.core.input.createMimirKeyboardFocusBindingForTests
import com.gruning.mimir.rewrite.core.input.mimirKeyboardFocusKey
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardController
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Piece 3. CONFIRM on the reset key must reach [resetGameTitleOverride]'s side effect WITHOUT going through
 * `MimirKeyboardFocusBinding.activateFocused()` — its activator map only tracks the keyboard's own key-row
 * set and would silently drop reset's activator on the next shift/symbols reconcile (see the comment on
 * the GAME_TITLE_EDIT branch in `MimirAppInputDispatch.kt`). These tests pin the dispatch-level contract
 * directly, the same pure-function pattern `MimirKeyboardOverlayDispatchTest` already uses.
 */
class GameTitleEditResetDispatchTest {

    @Test
    fun confirmOnTheResetKeyFiresTheResetCallbackAndNeverReachesTheGenericKeyboardDispatch() {
        val authority = DefaultFocusAuthority()
        val host = testKeyboardHost(authority)
        host.binding.enter()
        authority.register(GameTitleEditResetKey, MimirKeyboardFocusScope, FocusBounds(0f, 0f, 80f, 20f))
        authority.requestFocus(GameTitleEditResetKey)

        var resetCount = 0
        var dismissCount = 0
        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.GAME_TITLE_EDIT,
            authority = authority,
            settingsKeyboardHost = host,
            artworkConnectKeyboardHost = null,
            homeTomeLibraryDraftOpen = false,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = null,
            action = ControllerAction.CONFIRM,
            onDismissProfileName = {},
            onDismissSteamGridDbKey = {},
            onDismissRetroAchievements = {},
            onDismissArtworkConnect = {},
            onDismissGameTitleEdit = { dismissCount += 1 },
            onResetGameTitle = { resetCount += 1 },
            onHomeTomeLibraryAction = {},
        )

        assertTrue(handled)
        assertEquals(1, resetCount)
        assertEquals(0, dismissCount)
    }

    @Test
    fun confirmOnAnOrdinaryKeyDoesNotFireResetAndStillReachesTheKeyboardDispatch() {
        val authority = DefaultFocusAuthority()
        val host = testKeyboardHost(authority)
        host.binding.enter()
        // Simulate an ordinary keyboard key (e.g. the Done key) actually registering and taking focus,
        // matching how a real KeyCap's mimirFocusable would in the running app — confirm here must never
        // be mistaken for the reset shortcut.
        val doneKey = mimirKeyboardFocusKey(MimirKeyboardInitialKeyId)
        authority.register(doneKey, MimirKeyboardFocusScope, FocusBounds(0f, 0f, 40f, 20f))
        authority.requestFocus(doneKey)
        assertEquals(doneKey, authority.state.value.focused)

        var resetCount = 0
        dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.GAME_TITLE_EDIT,
            authority = authority,
            settingsKeyboardHost = host,
            artworkConnectKeyboardHost = null,
            homeTomeLibraryDraftOpen = false,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = null,
            action = ControllerAction.CONFIRM,
            onDismissProfileName = {},
            onDismissSteamGridDbKey = {},
            onDismissRetroAchievements = {},
            onDismissArtworkConnect = {},
            onDismissGameTitleEdit = {},
            onResetGameTitle = { resetCount += 1 },
            onHomeTomeLibraryAction = {},
        )

        assertEquals(0, resetCount)
    }

    @Test
    fun cancelWhileResetIsFocusedStillDismissesTheWholeOverlayNotJustReset() {
        val authority = DefaultFocusAuthority()
        val host = testKeyboardHost(authority)
        host.binding.enter()
        authority.register(GameTitleEditResetKey, MimirKeyboardFocusScope, FocusBounds(0f, 0f, 80f, 20f))
        authority.requestFocus(GameTitleEditResetKey)

        var resetCount = 0
        var dismissCount = 0
        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.GAME_TITLE_EDIT,
            authority = authority,
            settingsKeyboardHost = host,
            artworkConnectKeyboardHost = null,
            homeTomeLibraryDraftOpen = false,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = null,
            action = ControllerAction.SYSTEM_BACK,
            onDismissProfileName = {},
            onDismissSteamGridDbKey = {},
            onDismissRetroAchievements = {},
            onDismissArtworkConnect = {},
            onDismissGameTitleEdit = { dismissCount += 1 },
            onResetGameTitle = { resetCount += 1 },
            onHomeTomeLibraryAction = {},
        )

        assertTrue(handled)
        assertEquals(0, resetCount)
        assertEquals(1, dismissCount)
    }

    @Test
    fun confirmOnTheResetKeyFromADifferentOverlayIsIgnored() {
        val authority = DefaultFocusAuthority()
        val host = testKeyboardHost(authority, MimirOverlayKind.PROFILE_NAME)
        host.binding.enter()
        authority.register(GameTitleEditResetKey, MimirKeyboardFocusScope, FocusBounds(0f, 0f, 80f, 20f))
        authority.requestFocus(GameTitleEditResetKey)

        var resetCount = 0
        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.GAME_TITLE_EDIT,
            authority = authority,
            settingsKeyboardHost = host,
            artworkConnectKeyboardHost = null,
            homeTomeLibraryDraftOpen = false,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = null,
            action = ControllerAction.CONFIRM,
            onDismissProfileName = {},
            onDismissSteamGridDbKey = {},
            onDismissRetroAchievements = {},
            onDismissArtworkConnect = {},
            onDismissGameTitleEdit = {},
            onResetGameTitle = { resetCount += 1 },
            onHomeTomeLibraryAction = {},
        )

        assertFalse(handled)
        assertEquals(0, resetCount)
    }

    private fun testKeyboardHost(
        authority: DefaultFocusAuthority,
        owner: MimirOverlayKind? = MimirOverlayKind.GAME_TITLE_EDIT,
    ): MimirKeyboardHost {
        val binding = createMimirKeyboardFocusBindingForTests(authority)
        return MimirKeyboardHost(
            controller = MimirKeyboardController(
                onEnter = binding::enter,
                onExit = binding::exit,
                onKeysChanged = binding::reconcileFocus,
            ),
            keyDecorator = { _, _ ->
                MimirKeyboardKeyDecoration(
                    focusModifier = androidx.compose.ui.Modifier,
                    focused = false,
                )
            },
            binding = binding,
            owner = owner?.name,
        )
    }
}
