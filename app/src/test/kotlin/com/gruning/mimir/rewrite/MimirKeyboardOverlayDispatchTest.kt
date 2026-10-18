package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.createMimirKeyboardFocusBindingForTests
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.MimirKeyboardInitialKeyId
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardController
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirKeyboardOverlayDispatchTest {

    @Test
    fun activeSettingsKeyboardConsumesBackBeforeOverlayFallback() {
        val authority = DefaultFocusAuthority()
        var dismissed = 0
        val host = testKeyboardHost(authority, MimirOverlayKind.STEAMGRIDDB_KEY)
        host.binding.enter()

        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.STEAMGRIDDB_KEY,
            authority = authority,
            settingsKeyboardHost = host,
            artworkConnectKeyboardHost = null,
            homeTomeLibraryDraftOpen = false,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = null,
            action = ControllerAction.SYSTEM_BACK,
            onDismissProfileName = { dismissed += 1 },
            onDismissSteamGridDbKey = { dismissed += 1 },
            onDismissRetroAchievements = { dismissed += 1 },
            onDismissArtworkConnect = { dismissed += 1 },
            onDismissGameTitleEdit = {},
            onResetGameTitle = {},
            onHomeTomeLibraryAction = {},
        )

        assertTrue(handled)
        assertEquals(1, dismissed)
    }

    @Test
    fun staleSettingsHostDoesNotStealWhenTopOverlayDiffers() {
        val authority = DefaultFocusAuthority()
        val host = testKeyboardHost(authority, MimirOverlayKind.PROFILE_NAME)
        host.binding.enter()

        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.HOME_TOME_LIBRARY,
            authority = authority,
            settingsKeyboardHost = host,
            artworkConnectKeyboardHost = null,
            homeTomeLibraryDraftOpen = true,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = null,
            action = ControllerAction.SYSTEM_BACK,
            onDismissProfileName = {},
            onDismissSteamGridDbKey = {},
            onDismissRetroAchievements = {},
            onDismissArtworkConnect = {},
            onDismissGameTitleEdit = {},
            onResetGameTitle = {},
            onHomeTomeLibraryAction = {},
        )

        assertFalse(handled)
    }

    @Test
    fun staleProfileHostCannotHandleSteamGridDbTop() {
        val authority = DefaultFocusAuthority()
        var dismissed = 0
        val host = testKeyboardHost(authority, MimirOverlayKind.PROFILE_NAME)
        host.binding.enter()

        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.STEAMGRIDDB_KEY,
            authority = authority,
            settingsKeyboardHost = host,
            artworkConnectKeyboardHost = null,
            homeTomeLibraryDraftOpen = false,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = null,
            action = ControllerAction.SYSTEM_BACK,
            onDismissProfileName = { dismissed += 1 },
            onDismissSteamGridDbKey = { dismissed += 1 },
            onDismissRetroAchievements = {},
            onDismissArtworkConnect = {},
            onDismissGameTitleEdit = {},
            onResetGameTitle = {},
            onHomeTomeLibraryAction = {},
        )

        assertFalse(handled)
        assertEquals(0, dismissed)
    }

    @Test
    fun staleProfileHostCannotHandleRetroAchievementsTop() {
        val authority = DefaultFocusAuthority()
        var dismissed = 0
        val host = testKeyboardHost(authority, MimirOverlayKind.PROFILE_NAME)
        host.binding.enter()

        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.RETROACHIEVEMENTS_KEY,
            authority = authority,
            settingsKeyboardHost = host,
            artworkConnectKeyboardHost = null,
            homeTomeLibraryDraftOpen = false,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = null,
            action = ControllerAction.SYSTEM_BACK,
            onDismissProfileName = { dismissed += 1 },
            onDismissSteamGridDbKey = {},
            onDismissRetroAchievements = { dismissed += 1 },
            onDismissArtworkConnect = {},
            onDismissGameTitleEdit = {},
            onResetGameTitle = {},
            onHomeTomeLibraryAction = {},
        )

        assertFalse(handled)
        assertEquals(0, dismissed)
    }

    @Test
    fun closingKeyboardRestoresPriorHostFocus() {
        val authority = DefaultFocusAuthority()
        val hostKey = FocusKey("library/search")
        authority.register(hostKey, FocusScope.Root, com.gruning.mimir.rewrite.core.input.FocusBounds(0f, 0f, 80f, 40f))
        authority.requestFocus(hostKey)
        val keyboardHost = testKeyboardHost(authority)
        keyboardHost.binding.enter()
        keyboardHost.binding.exit()
        assertEquals(FocusScope.Root, authority.state.value.scope)
        assertEquals(hostKey, authority.state.value.focused)
    }

    @Test
    fun artworkConnectKeyboardRoutesWhenTopOverlayMatches() {
        val authority = DefaultFocusAuthority()
        var dismissed = 0
        val host = testKeyboardHost(authority, MimirOverlayKind.ARTWORK_CONNECT)
        host.binding.enter()

        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.ARTWORK_CONNECT,
            authority = authority,
            settingsKeyboardHost = null,
            artworkConnectKeyboardHost = host,
            homeTomeLibraryDraftOpen = false,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = null,
            action = ControllerAction.SYSTEM_BACK,
            onDismissProfileName = {},
            onDismissSteamGridDbKey = {},
            onDismissRetroAchievements = {},
            onDismissArtworkConnect = { dismissed += 1 },
            onDismissGameTitleEdit = {},
            onResetGameTitle = {},
            onHomeTomeLibraryAction = {},
        )

        assertTrue(handled)
        assertEquals(1, dismissed)
    }

    @Test
    fun artworkConnectCancelClosesBeforeActivatingUsernameDone() {
        val authority = DefaultFocusAuthority()
        var dismissed = 0
        var activated = 0
        val host = testKeyboardHost(authority, MimirOverlayKind.ARTWORK_CONNECT)
        host.binding.enter()
        host.binding.registerActivator(MimirKeyboardInitialKeyId) { activated += 1 }

        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.ARTWORK_CONNECT,
            authority = authority,
            settingsKeyboardHost = null,
            artworkConnectKeyboardHost = host,
            homeTomeLibraryDraftOpen = false,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = null,
            action = ControllerAction.CANCEL,
            onDismissProfileName = {},
            onDismissSteamGridDbKey = {},
            onDismissRetroAchievements = {},
            onDismissArtworkConnect = { dismissed += 1 },
            onDismissGameTitleEdit = {},
            onResetGameTitle = {},
            onHomeTomeLibraryAction = {},
        )

        assertTrue(handled)
        assertEquals("Artwork Connect Back must dismiss the overlay", 1, dismissed)
        assertEquals("Artwork Connect Back must not activate the username Done key", 0, activated)
    }

    @Test
    fun homeTomeDraftBackDelegatesToOverlayReducer() {
        val authority = DefaultFocusAuthority()
        var routed: ControllerAction? = null
        val host = testKeyboardHost(authority, MimirOverlayKind.HOME_TOME_LIBRARY)
        host.binding.enter()

        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.HOME_TOME_LIBRARY,
            authority = authority,
            settingsKeyboardHost = null,
            artworkConnectKeyboardHost = null,
            homeTomeLibraryDraftOpen = true,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = host,
            action = ControllerAction.SYSTEM_BACK,
            onDismissProfileName = {},
            onDismissSteamGridDbKey = {},
            onDismissRetroAchievements = {},
            onDismissArtworkConnect = {},
            onDismissGameTitleEdit = {},
            onResetGameTitle = {},
            onHomeTomeLibraryAction = { routed = it },
        )

        assertTrue(handled)
        assertEquals(ControllerAction.SYSTEM_BACK, routed)
    }

    @Test
    fun homeTomeConfirmDeleteYieldsKeyboardDispatchToOverlayHandler() {
        val authority = DefaultFocusAuthority()
        var routed = 0
        val host = testKeyboardHost(authority, MimirOverlayKind.HOME_TOME_LIBRARY)
        host.binding.enter()

        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.HOME_TOME_LIBRARY,
            authority = authority,
            settingsKeyboardHost = null,
            artworkConnectKeyboardHost = null,
            homeTomeLibraryDraftOpen = true,
            homeTomeLibraryConfirmDelete = true,
            homeTomeLibraryKeyboardHost = host,
            action = ControllerAction.SYSTEM_BACK,
            onDismissProfileName = {},
            onDismissSteamGridDbKey = {},
            onDismissRetroAchievements = {},
            onDismissArtworkConnect = {},
            onDismissGameTitleEdit = {},
            onResetGameTitle = {},
            onHomeTomeLibraryAction = { routed += 1 },
        )

        assertFalse(handled)
        assertEquals(0, routed)
    }
}

private fun testKeyboardHost(
    authority: DefaultFocusAuthority,
    owner: MimirOverlayKind? = null,
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
