package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionPermissionOverlayLifecycleTest {
    @Test
    fun mountLandsOpenSettingsImmediately() {
        val authority = DefaultFocusAuthority()
        mountCompanionPermissionScope(authority)

        assertEquals(CompanionPermissionScope, authority.state.value.scope)
        assertEquals(CompanionPermissionOpenSettingsKey, authority.state.value.focused)
    }

    @Test
    fun mountRegistersDismissBeforeOpenSettingsForHorizontalNav() {
        val authority = DefaultFocusAuthority()
        mountCompanionPermissionScope(authority)

        dispatchCompanionPermission(authority, ControllerAction.NAV_LEFT, onGrant = {}, onDismiss = {})
        assertEquals(CompanionPermissionDismissKey, authority.state.value.focused)

        dispatchCompanionPermission(authority, ControllerAction.NAV_RIGHT, onGrant = {}, onDismiss = {})
        assertEquals(CompanionPermissionOpenSettingsKey, authority.state.value.focused)
    }

    @Test
    fun unmountRestoresPriorScopeAndFocusWithoutLeakingPermissionKeys() {
        val priorScope = FocusScope("home/shelf")
        val priorKey = FocusKey("home/shelf/game-0")
        val authority = DefaultFocusAuthority().apply {
            pushScope(priorScope)
            registerVirtual(priorKey, priorScope, companionPermissionDismissBounds())
            requestFocus(priorKey)
        }

        mountCompanionPermissionScope(authority)
        assertEquals(CompanionPermissionOpenSettingsKey, authority.state.value.focused)

        unmountCompanionPermissionScope(authority)

        assertEquals(priorScope, authority.state.value.scope)
        assertEquals(priorKey, authority.state.value.focused)
        assertFalse(
            authority.registeredKeysInScope(CompanionPermissionScope).contains(CompanionPermissionDismissKey),
        )
        assertFalse(
            authority.registeredKeysInScope(CompanionPermissionScope).contains(CompanionPermissionOpenSettingsKey),
        )
    }

    @Test
    fun unmountDiscardsPermissionScopeWhenCoveredBeforeCoverPops() {
        val homeScope = FocusScope("home")
        val homeKey = FocusKey("home/item")
        val coverScope = FocusScope("cover")
        val coverKey = FocusKey("cover/item")
        val authority = DefaultFocusAuthority().apply {
            pushScope(homeScope)
            registerVirtual(homeKey, homeScope, companionPermissionDismissBounds())
            requestFocus(homeKey)
        }

        mountCompanionPermissionScope(authority)
        authority.pushScope(coverScope)
        authority.registerVirtual(coverKey, coverScope, companionPermissionOpenSettingsBounds())
        authority.requestFocus(coverKey)

        unmountCompanionPermissionScope(authority)

        assertEquals(coverScope, authority.state.value.scope)
        assertEquals(coverKey, authority.state.value.focused)
        assertFalse(
            authority.registeredKeysInScope(CompanionPermissionScope).contains(CompanionPermissionDismissKey),
        )
        assertFalse(
            authority.registeredKeysInScope(CompanionPermissionScope).contains(CompanionPermissionOpenSettingsKey),
        )

        unmountCompanionPermissionScope(authority)
        assertEquals(coverScope, authority.state.value.scope)
        assertEquals(coverKey, authority.state.value.focused)

        authority.popScope()

        assertEquals(homeScope, authority.state.value.scope)
        assertEquals(homeKey, authority.state.value.focused)
    }

    @Test
    fun mountSkipsPushWhenPermissionScopeAlreadyActive() {
        val authority = DefaultFocusAuthority().apply {
            pushScope(CompanionPermissionScope)
        }
        val stackDepth = authority.state.value

        mountCompanionPermissionScope(authority)

        assertEquals(CompanionPermissionScope, authority.state.value.scope)
        assertEquals(CompanionPermissionOpenSettingsKey, authority.state.value.focused)
        assertEquals(stackDepth.scope, authority.state.value.scope)
    }

    @Test
    fun unmountDoesNotPopForeignScopeWhenPermissionScopeAlreadyLeft() {
        val utilityScope = FocusScope("utility/brightness")
        val authority = DefaultFocusAuthority().apply {
            pushScope(utilityScope)
        }
        mountCompanionPermissionScope(authority)
        authority.popScope()

        unmountCompanionPermissionScope(authority)

        assertEquals(utilityScope, authority.state.value.scope)
        assertFalse(
            authority.registeredKeysInScope(CompanionPermissionScope).contains(CompanionPermissionDismissKey),
        )
        assertFalse(
            authority.registeredKeysInScope(CompanionPermissionScope).contains(CompanionPermissionOpenSettingsKey),
        )
    }
}
