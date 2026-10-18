package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPermissionOverlayDispatchTest {
    @Test
    fun leftRightMovesBetweenDismissAndOpenSettingsKeys() {
        val authority = seededAuthority()
        authority.requestFocus(CompanionPermissionOpenSettingsKey)

        dispatchCompanionPermission(authority, ControllerAction.NAV_LEFT, onGrant = {}, onDismiss = {})
        assertEquals(CompanionPermissionDismissKey, authority.state.value.focused)

        dispatchCompanionPermission(authority, ControllerAction.NAV_RIGHT, onGrant = {}, onDismiss = {})
        assertEquals(CompanionPermissionOpenSettingsKey, authority.state.value.focused)
    }

    @Test
    fun confirmInvokesFocusedGrantOrDismiss() {
        val authority = seededAuthority()
        var grants = 0
        var dismisses = 0

        authority.requestFocus(CompanionPermissionOpenSettingsKey)
        dispatchCompanionPermission(
            authority = authority,
            action = ControllerAction.CONFIRM,
            onGrant = { grants += 1 },
            onDismiss = { dismisses += 1 },
        )
        assertEquals(1, grants)
        assertEquals(0, dismisses)

        authority.requestFocus(CompanionPermissionDismissKey)
        dispatchCompanionPermission(
            authority = authority,
            action = ControllerAction.CONFIRM,
            onGrant = { grants += 1 },
            onDismiss = { dismisses += 1 },
        )
        assertEquals(1, grants)
        assertEquals(1, dismisses)
    }

    @Test
    fun cancelAndSystemBackDismissWhileOtherActionsAreSwallowed() {
        val authority = seededAuthority()
        var dismisses = 0
        var grants = 0

        dispatchCompanionPermission(
            authority = authority,
            action = ControllerAction.CANCEL,
            onGrant = { grants += 1 },
            onDismiss = { dismisses += 1 },
        )
        assertEquals(1, dismisses)

        dismisses = 0
        dispatchCompanionPermission(
            authority = authority,
            action = ControllerAction.SYSTEM_BACK,
            onGrant = { grants += 1 },
            onDismiss = { dismisses += 1 },
        )
        assertEquals(1, dismisses)

        dispatchCompanionPermission(
            authority = authority,
            action = ControllerAction.NAV_UP,
            onGrant = { grants += 1 },
            onDismiss = { dismisses += 1 },
        )
        assertEquals(0, grants)
        assertTrue(dispatchCompanionPermission(authority, ControllerAction.NAV_UP, {}, {}))
    }

    @Test
    fun companionPermissionOverlaySitsAboveBrightnessInStack() {
        val stack = resolveMimirOverlayStack(
            MimirOverlaySnapshot(companionPermission = true, brightness = true),
        )
        assertEquals(MimirOverlayKind.COMPANION_PERMISSION, stack.top)
        assertTrue(MimirOverlayKind.BRIGHTNESS in stack.entries)
        assertTrue(stack.entries.indexOf(MimirOverlayKind.COMPANION_PERMISSION) <
            stack.entries.indexOf(MimirOverlayKind.BRIGHTNESS))
    }

    private fun seededAuthority(): DefaultFocusAuthority =
        DefaultFocusAuthority().also(::mountCompanionPermissionScope)
}
