package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * RC-32 (audit 2026-10-14, S-50): the Get art card seeded Connect on a visit that followed one which ended on Connect, and an A in
 * the one or two frames before the landing effect moved focus to Not now opened sign-in. The scope remembers the last key; the
 * card's keys are not registered when it is pushed. `mountGetArtFocusScope` now stamps the landing key before the push.
 */
class GetArtLandingSafeKeyTest {

    private val bounds = FocusBounds(0f, 0f, 100f, 100f)

    @Test
    fun `a card opened after a visit that ended on Connect lands on Not now, never Connect`() {
        val authority = DefaultFocusAuthority()

        // First visit: the player moves to Connect and leaves the card (Connect is the scope's memory).
        mountGetArtFocusScope(authority)
        authority.register(GetArtDismissKey, GetArtFocusScope, bounds)
        authority.register(GetArtConnectKey, GetArtFocusScope, FocusBounds(200f, 0f, 300f, 100f))
        authority.requestFocus(GetArtConnectKey)
        unmountGetArtFocusScope(authority)
        authority.unregister(GetArtDismissKey)
        authority.unregister(GetArtConnectKey)

        // Second visit: Connect registers first, the worst order.
        mountGetArtFocusScope(authority)
        val seen = mutableListOf(authority.state.value.focused)
        authority.register(GetArtConnectKey, GetArtFocusScope, FocusBounds(200f, 0f, 300f, 100f))
        seen += authority.state.value.focused
        authority.register(GetArtDismissKey, GetArtFocusScope, bounds)
        seen += authority.state.value.focused

        assertFalse("Connect is never the focused key while the card mounts: $seen", GetArtConnectKey in seen)
        assertEquals("Not now is what lands", GetArtDismissKey, authority.state.value.focused)
        assertEquals(getArtLandingKey(GetArtUi.NeedsAccount), authority.state.value.focused)
    }
}
