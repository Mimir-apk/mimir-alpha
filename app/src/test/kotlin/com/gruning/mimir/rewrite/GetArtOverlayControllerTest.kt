package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetArtOverlayControllerTest {

    @Test
    fun `NeedsAccount moves between real buttons and confirms only the focused provider action`() {
        val authority = mounted(withConnect = true)
        var connected = 0
        var dismissed = 0

        assertTrue(
            dispatchGetArt(
                authority = authority,
                state = GetArtUi.NeedsAccount,
                provider = GetArtProvider.SCREENSCRAPER,
                action = ControllerAction.NAV_LEFT,
                onConnect = { connected++ },
                onAddSteamGridDbKey = {},
                onDismiss = { dismissed++ },
            ),
        )
        assertEquals(GetArtDismissKey, authority.state.value.focused)

        dispatchGetArt(
            authority = authority,
            state = GetArtUi.NeedsAccount,
            provider = GetArtProvider.SCREENSCRAPER,
            action = ControllerAction.CONFIRM,
            onConnect = { connected++ },
            onAddSteamGridDbKey = {},
            onDismiss = { dismissed++ },
        )
        assertEquals(1, dismissed)
        assertEquals(0, connected)

        dispatchGetArt(
            authority = authority,
            state = GetArtUi.NeedsAccount,
            provider = GetArtProvider.SCREENSCRAPER,
            action = ControllerAction.NAV_RIGHT,
            onConnect = { connected++ },
            onAddSteamGridDbKey = {},
            onDismiss = { dismissed++ },
        )
        dispatchGetArt(
            authority = authority,
            state = GetArtUi.NeedsAccount,
            provider = GetArtProvider.SCREENSCRAPER,
            action = ControllerAction.CONFIRM,
            onConnect = { connected++ },
            onAddSteamGridDbKey = {},
            onDismiss = { dismissed++ },
        )
        assertEquals(1, connected)
    }

    @Test
    fun `SteamGridDB confirmation reaches only the key action`() {
        val authority = mounted(withConnect = true)
        authority.requestFocus(GetArtConnectKey)
        var connected = 0
        var keyEditor = 0

        dispatchGetArt(
            authority = authority,
            state = GetArtUi.NeedsAccount,
            provider = GetArtProvider.STEAMGRIDDB,
            action = ControllerAction.CONFIRM,
            onConnect = { connected++ },
            onAddSteamGridDbKey = { keyEditor++ },
            onDismiss = {},
        )

        assertEquals(0, connected)
        assertEquals(1, keyEditor)
    }

    @Test
    fun `ProviderUnavailable and Failed expose only dismiss and Back always dismisses`() {
        val unavailableAuthority = mounted(withConnect = false)
        var unavailableDismissed = 0
        dispatchGetArt(
            authority = unavailableAuthority,
            state = GetArtUi.ProviderUnavailable,
            provider = GetArtProvider.SCREENSCRAPER,
            action = ControllerAction.CONFIRM,
            onConnect = {},
            onAddSteamGridDbKey = {},
            onDismiss = { unavailableDismissed++ },
        )
        assertEquals(1, unavailableDismissed)

        val failedAuthority = mounted(withConnect = false)
        var failedDismissed = 0
        dispatchGetArt(
            authority = failedAuthority,
            state = GetArtUi.Failed,
            provider = GetArtProvider.SCREENSCRAPER,
            action = ControllerAction.CONFIRM,
            onConnect = {},
            onAddSteamGridDbKey = {},
            onDismiss = { failedDismissed++ },
        )
        dispatchGetArt(
            authority = failedAuthority,
            state = GetArtUi.Failed,
            provider = GetArtProvider.SCREENSCRAPER,
            action = ControllerAction.CANCEL,
            onConnect = {},
            onAddSteamGridDbKey = {},
            onDismiss = { failedDismissed++ },
        )
        assertEquals(2, failedDismissed)
    }

    @Test
    fun `navigation and confirmation cannot escape a different active scope`() {
        val authority = mounted(withConnect = true)
        val otherScope = FocusScope("other")
        val otherKey = FocusKey("other/key")
        authority.pushScope(otherScope)
        authority.register(otherKey, otherScope, FocusBounds(0f, 0f, 100f, 100f))
        authority.requestFocus(otherKey)
        var connected = 0
        var dismissed = 0

        dispatchGetArt(
            authority = authority,
            state = GetArtUi.NeedsAccount,
            provider = GetArtProvider.SCREENSCRAPER,
            action = ControllerAction.NAV_RIGHT,
            onConnect = { connected++ },
            onAddSteamGridDbKey = {},
            onDismiss = { dismissed++ },
        )
        dispatchGetArt(
            authority = authority,
            state = GetArtUi.NeedsAccount,
            provider = GetArtProvider.SCREENSCRAPER,
            action = ControllerAction.CONFIRM,
            onConnect = { connected++ },
            onAddSteamGridDbKey = {},
            onDismiss = { dismissed++ },
        )

        assertEquals(otherScope, authority.state.value.scope)
        assertEquals(otherKey, authority.state.value.focused)
        assertEquals(0, connected)
        assertEquals(0, dismissed)
    }

    @Test
    fun `discarding the prompt scope restores the prior focused surface`() {
        val authority = DefaultFocusAuthority()
        val baseKey = FocusKey("base/key")
        authority.register(baseKey, FocusScope.Root, FocusBounds(0f, 0f, 100f, 100f))
        authority.requestFocus(baseKey)
        mountGetArtFocusScope(authority)
        authority.register(GetArtDismissKey, GetArtFocusScope, FocusBounds(0f, 0f, 100f, 100f))
        authority.register(GetArtConnectKey, GetArtFocusScope, FocusBounds(100f, 0f, 200f, 100f))
        authority.requestFocus(GetArtConnectKey)

        unmountGetArtFocusScope(authority)

        assertEquals(FocusScope.Root, authority.state.value.scope)
        assertEquals(baseKey, authority.state.value.focused)
    }

    private fun mounted(withConnect: Boolean): DefaultFocusAuthority =
        DefaultFocusAuthority().also { authority ->
            mountGetArtFocusScope(authority)
            authority.register(GetArtDismissKey, GetArtFocusScope, FocusBounds(0f, 0f, 100f, 100f))
            if (withConnect) {
                authority.register(GetArtConnectKey, GetArtFocusScope, FocusBounds(100f, 0f, 200f, 100f))
            }
            authority.requestFocus(if (withConnect) GetArtConnectKey else GetArtDismissKey)
        }
}
