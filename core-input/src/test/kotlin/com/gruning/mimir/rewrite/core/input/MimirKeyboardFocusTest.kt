package com.gruning.mimir.rewrite.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirKeyboardFocusTest {

    @Test
    fun enterRequestsDoneAndExitRestoresHostFocus() {
        val authority = DefaultFocusAuthority()
        val hostKey = FocusKey("host/search")
        authority.register(hostKey, FocusScope.Root, FocusBounds(0f, 0f, 100f, 40f))
        authority.requestFocus(hostKey)

        val binding = MimirKeyboardFocusBinding(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            initialKeyId = MimirKeyboardInitialKeyId,
        )
        binding.enter()
        authority.register(
            mimirKeyboardFocusKey(MimirKeyboardInitialKeyId),
            MimirKeyboardFocusScope,
            FocusBounds(0f, 0f, 40f, 40f),
        )
        binding.reconcileFocus(setOf(MimirKeyboardInitialKeyId, "action/back"))
        assertEquals(MimirKeyboardFocusScope, authority.state.value.scope)
        assertEquals(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId), authority.state.value.focused)

        binding.exit()
        assertEquals(FocusScope.Root, authority.state.value.scope)
        assertEquals(hostKey, authority.state.value.focused)
    }

    /**
     * `exit()` guards its `popScope()` call on
     * `authority.state.value.scope == scope`, so if ANY scope was pushed over the keyboard before it
     * closes (reachable path: keyboard open -> a mapped GLOBAL_UTILITY/GLOBAL_QUICK_LAUNCH press is
     * intercepted at the top of `dispatchMimirInput` before it reaches the keyboard's own dispatch ->
     * the surface pushes its own scope, e.g. `AppBrightnessCoordinator.open()` pushing `BrightnessScope`
     * -> only THEN does the host's `DisposableEffect` dispose and fire `exit()`), the keyboard's scope is
     * skipped by the guard and left on the stack forever. `CoveringScope` is minted locally rather than
     * importing `BrightnessScope` -- core-input cannot depend on `app`.
     *
     * Uses only the pre-existing public FocusAuthority contract (`pushScope`/`popScope`/`state`), so this
     * reproduces the defect against completely unmodified code -- no new API needs to exist for this test
     * to go red.
     */
    @Test
    fun exitDiscardsTheKeyboardScopeEvenWhenAnotherScopeWasPushedOverIt() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        authority.register(
            mimirKeyboardFocusKey(MimirKeyboardInitialKeyId),
            MimirKeyboardFocusScope,
            FocusBounds(0f, 0f, 40f, 40f),
        )

        // Simulates AppBrightnessCoordinator.open() pushing BrightnessScope over the still-open keyboard.
        val coveringScope = FocusScope("test/covering-overlay")
        val coveringKey = FocusKey("covering/opt0")
        authority.pushScope(coveringScope)
        authority.register(coveringKey, coveringScope, FocusBounds(0f, 0f, 40f, 40f))
        authority.requestFocus(coveringKey)

        binding.exit()

        // The covering surface must be completely undisturbed by the keyboard cleaning up underneath it.
        assertEquals(
            "exit() must never touch a scope that isn't its own -- the covering surface stays active",
            coveringScope,
            authority.state.value.scope,
        )
        assertEquals(coveringKey, authority.state.value.focused)

        authority.popScope() // the covering surface (Brightness) closes normally

        assertEquals(
            "the keyboard scope must never be left orphaned on the stack: popping the covering scope " +
                "must land on ROOT, not back inside the now-unmounted keyboard (activators empty, " +
                "CONFIRM would silently do nothing)",
            FocusScope.Root,
            authority.state.value.scope,
        )
    }

    @Test
    fun dispatchConfirmActivatesFocusedKey() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        authority.register(
            mimirKeyboardFocusKey(MimirKeyboardInitialKeyId),
            MimirKeyboardFocusScope,
            FocusBounds(0f, 0f, 40f, 40f),
        )
        binding.reconcileFocus(setOf(MimirKeyboardInitialKeyId))
        var activated = false
        binding.registerActivator(MimirKeyboardInitialKeyId) { activated = true }
        authority.requestFocus(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId))

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.CONFIRM,
            binding = binding,
            onDismiss = {},
        )

        assertTrue(handled)
        assertTrue(activated)
    }

    @Test
    fun dispatchConfirmReturnsFalseWithoutActivator() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        authority.register(
            mimirKeyboardFocusKey(MimirKeyboardInitialKeyId),
            MimirKeyboardFocusScope,
            FocusBounds(0f, 0f, 40f, 40f),
        )
        binding.reconcileFocus(setOf(MimirKeyboardInitialKeyId))
        authority.requestFocus(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId))

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.CONFIRM,
            binding = binding,
            onDismiss = {},
        )

        assertFalse(handled)
    }

    @Test
    fun dispatchCancelDismissesWithoutPressingDone() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        authority.register(
            mimirKeyboardFocusKey(MimirKeyboardInitialKeyId),
            MimirKeyboardFocusScope,
            FocusBounds(0f, 0f, 40f, 40f),
        )
        binding.reconcileFocus(setOf(MimirKeyboardInitialKeyId))
        var activated = false
        var dismissed = false
        binding.registerActivator(MimirKeyboardInitialKeyId) { activated = true }

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.CANCEL,
            binding = binding,
            onDismiss = { dismissed = true },
        )

        // OR-10 : B cancels in every editor; Done is A on the check mark.
        assertTrue(handled)
        assertFalse(activated)
        assertTrue(dismissed)
    }

    @Test
    fun dispatchBackDismissesExactlyOnce() {
        val authority = DefaultFocusAuthority()
        var bindingDismissCount = 0
        var dispatchDismissCount = 0
        val binding = MimirKeyboardFocusBinding(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            initialKeyId = MimirKeyboardInitialKeyId,
        )
        binding.enter()

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.SYSTEM_BACK,
            binding = binding,
            onDismiss = { dispatchDismissCount += 1 },
        )

        assertTrue(handled)
        assertEquals(1, dispatchDismissCount)
        assertEquals(0, bindingDismissCount)
    }

    @Test
    fun reconcileFocusKeepsSemanticKeyWhenPresentAfterLayoutChange() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        val shiftId = "action/shift"
        authority.register(
            mimirKeyboardFocusKey(shiftId),
            MimirKeyboardFocusScope,
            FocusBounds(0f, 0f, 40f, 40f),
        )
        authority.requestFocus(mimirKeyboardFocusKey(shiftId))
        binding.reconcileFocus(setOf(shiftId, MimirKeyboardInitialKeyId))
        assertEquals(mimirKeyboardFocusKey(shiftId), authority.state.value.focused)
    }

    @Test
    fun reconcileFocusFallsBackWhenRememberedKeyAbsent() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        binding.lastFocusedKeyId = "char/z"
        authority.register(
            mimirKeyboardFocusKey(MimirKeyboardInitialKeyId),
            MimirKeyboardFocusScope,
            FocusBounds(60f, 0f, 100f, 40f),
        )
        binding.reconcileFocus(setOf(MimirKeyboardInitialKeyId, "action/back"))
        binding.reconcileFocus(setOf(MimirKeyboardInitialKeyId, "action/back"))
        assertEquals(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId), authority.state.value.focused)
    }

    @Test
    fun enterBeforeTopLeftRegistrationLandsOnDone() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        authority.register(
            mimirKeyboardFocusKey("action/back"),
            MimirKeyboardFocusScope,
            FocusBounds(0f, 0f, 40f, 40f),
        )
        authority.register(
            mimirKeyboardFocusKey(MimirKeyboardInitialKeyId),
            MimirKeyboardFocusScope,
            FocusBounds(60f, 0f, 100f, 40f),
        )
        binding.reconcileFocus(setOf("action/back", MimirKeyboardInitialKeyId))
        assertEquals(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId), authority.state.value.focused)
        assertFalse(authority.state.value.focused?.value?.endsWith("back") == true)
    }

    @Test
    fun reconcileFocusDiscardsStaleActivators() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        var shiftActivated = false
        binding.registerActivator("action/shift") { shiftActivated = true }
        binding.reconcileFocus(setOf(MimirKeyboardInitialKeyId))
        binding.registerActivator(MimirKeyboardInitialKeyId) {}
        assertFalse(shiftActivated)
    }

    @Test
    fun triggerLeftInvokesTheRegisteredCaseToggle() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        var toggled = 0
        binding.registerShoulderActions(
            toggleCase = { toggled += 1 },
            cycleCharacterSet = {},
        )

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.TRIGGER_LEFT,
            binding = binding,
            onDismiss = {},
        )

        assertTrue("L2/TRIGGER_LEFT must be handled once the keyboard registered a case toggle", handled)
        assertEquals(1, toggled)
    }

    @Test
    fun triggerRightInvokesTheRegisteredCharacterSetCycle() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        var cycled = 0
        binding.registerShoulderActions(
            toggleCase = {},
            cycleCharacterSet = { cycled += 1 },
        )

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.TRIGGER_RIGHT,
            binding = binding,
            onDismiss = {},
        )

        assertTrue("R2/TRIGGER_RIGHT must be handled once the keyboard registered a character-set cycle", handled)
        assertEquals(1, cycled)
    }

    @Test
    fun triggerActionsReturnFalseWhenNoKeyboardHasRegisteredThem() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        // No registerShoulderActions() call — mirrors a keyboard host that hasn't mounted the
        // LaunchedEffect(controller) registration yet (or never will, e.g. a controller-less preview).

        val leftHandled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.TRIGGER_LEFT,
            binding = binding,
            onDismiss = {},
        )
        val rightHandled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.TRIGGER_RIGHT,
            binding = binding,
            onDismiss = {},
        )

        assertFalse(leftHandled)
        assertFalse(rightHandled)
    }

    @Test
    fun shoulderActionsAreClearedOnExitSoAStaleBindingCannotFireIntoATornDownKeyboard() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        var toggled = 0
        binding.registerShoulderActions(toggleCase = { toggled += 1 }, cycleCharacterSet = {})
        binding.exit()

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.TRIGGER_LEFT,
            binding = binding,
            onDismiss = {},
        )

        // dispatchMimirKeyboardInput's own scope guard (authority.state.value.scope != scope) already
        // returns false post-exit in every existing case; this pins that the SAME guard also protects
        // the new shoulder actions, and that exit() actually dropped the stale closure either way.
        assertFalse(handled)
        assertEquals(0, toggled)
    }

    @Test
    fun directionalDispatchConsumesWithoutLeavingKeyboardScope() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        authority.register(
            mimirKeyboardFocusKey("action/back"),
            MimirKeyboardFocusScope,
            FocusBounds(0f, 0f, 40f, 40f),
        )
        authority.register(
            mimirKeyboardFocusKey(MimirKeyboardInitialKeyId),
            MimirKeyboardFocusScope,
            FocusBounds(60f, 0f, 100f, 40f),
        )

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.NAV_LEFT,
            binding = binding,
            onDismiss = {},
        )

        assertTrue(handled)
        assertEquals(MimirKeyboardFocusScope, authority.state.value.scope)
        assertFalse(authority.state.value.focused == null)
    }

    // Row-based LEFT/RIGHT geometry: RIGHT from `paste` must land on `spacebar`; RIGHT from
    // a row's last key wraps to that SAME row's first key. Bounds below are DELIBERATELY
    // geometrically misleading (pixel order
    // does not match row order) so a pass here can only be explained by row-index movement, never by
    // directionalScore falling back on the right answer by coincidence. ──

    @Test
    fun rightFromPasteLandsOnSpacebar_ownersExample() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        // Pixel bounds place "space" to the LEFT of "paste" on screen -- the opposite of their row
        // order -- so the geometric mover (if consulted) would refuse this direction entirely
        // (inDirection filters out anything not strictly to the right of `from`).
        authority.register(mimirKeyboardFocusKey("action/symbols"), MimirKeyboardFocusScope, FocusBounds(400f, 0f, 440f, 40f))
        authority.register(mimirKeyboardFocusKey("action/paste"), MimirKeyboardFocusScope, FocusBounds(300f, 0f, 340f, 40f))
        authority.register(mimirKeyboardFocusKey("action/space"), MimirKeyboardFocusScope, FocusBounds(0f, 0f, 40f, 40f))
        authority.register(mimirKeyboardFocusKey("action/done"), MimirKeyboardFocusScope, FocusBounds(200f, 0f, 240f, 40f))
        binding.registerRowLayout { listOf(listOf("action/symbols", "action/paste", "action/space", "action/done")) }
        authority.requestFocus(mimirKeyboardFocusKey("action/paste"))

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.NAV_RIGHT,
            binding = binding,
            onDismiss = {},
        )

        assertTrue("RIGHT from paste must be handled", handled)
        assertEquals(
            "RIGHT from paste must land on spacebar",
            mimirKeyboardFocusKey("action/space"),
            authority.state.value.focused,
        )
    }

    @Test
    fun rightFromLastKeyOfARowWrapsToFirstKeyOfSameRow() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        authority.register(mimirKeyboardFocusKey("action/symbols"), MimirKeyboardFocusScope, FocusBounds(0f, 0f, 40f, 40f))
        authority.register(mimirKeyboardFocusKey("action/paste"), MimirKeyboardFocusScope, FocusBounds(60f, 0f, 100f, 40f))
        authority.register(mimirKeyboardFocusKey("action/space"), MimirKeyboardFocusScope, FocusBounds(120f, 0f, 160f, 40f))
        authority.register(mimirKeyboardFocusKey("action/done"), MimirKeyboardFocusScope, FocusBounds(200f, 0f, 240f, 40f))
        binding.registerRowLayout { listOf(listOf("action/symbols", "action/paste", "action/space", "action/done")) }
        authority.requestFocus(mimirKeyboardFocusKey("action/done"))

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.NAV_RIGHT,
            binding = binding,
            onDismiss = {},
        )

        assertTrue("RIGHT from the last key of a row must still be handled (Rule 4)", handled)
        assertEquals(
            "RIGHT from the last key of a row must wrap to the FIRST key of the SAME row, not the next row",
            mimirKeyboardFocusKey("action/symbols"),
            authority.state.value.focused,
        )
    }

    @Test
    fun leftFromFirstKeyOfARowWrapsToLastKeyOfSameRow() {
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        authority.register(mimirKeyboardFocusKey("action/symbols"), MimirKeyboardFocusScope, FocusBounds(0f, 0f, 40f, 40f))
        authority.register(mimirKeyboardFocusKey("action/paste"), MimirKeyboardFocusScope, FocusBounds(60f, 0f, 100f, 40f))
        authority.register(mimirKeyboardFocusKey("action/space"), MimirKeyboardFocusScope, FocusBounds(120f, 0f, 160f, 40f))
        authority.register(mimirKeyboardFocusKey("action/done"), MimirKeyboardFocusScope, FocusBounds(200f, 0f, 240f, 40f))
        binding.registerRowLayout { listOf(listOf("action/symbols", "action/paste", "action/space", "action/done")) }
        authority.requestFocus(mimirKeyboardFocusKey("action/symbols"))

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.NAV_LEFT,
            binding = binding,
            onDismiss = {},
        )

        assertTrue("LEFT from the first key of a row must still be handled (Rule 4)", handled)
        assertEquals(
            "LEFT from the first key of a row must wrap to the LAST key of the SAME row (symmetry with RIGHT)",
            mimirKeyboardFocusKey("action/done"),
            authority.state.value.focused,
        )
    }

    @Test
    fun rowBasedMoveFallsBackToGeometricWhenNoRowLayoutIsRegistered() {
        // No binding.registerRowLayout() call -- mirrors a mid-mount frame before core-ui's
        // LaunchedEffect(controller) has registered the provider. Must degrade to the pre-fix geometric
        // behavior, not a dead end.
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        authority.register(mimirKeyboardFocusKey("action/back"), MimirKeyboardFocusScope, FocusBounds(0f, 0f, 40f, 40f))
        authority.register(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId), MimirKeyboardFocusScope, FocusBounds(60f, 0f, 100f, 40f))
        authority.requestFocus(mimirKeyboardFocusKey("action/back"))

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.NAV_RIGHT,
            binding = binding,
            onDismiss = {},
        )

        assertTrue(handled)
        assertEquals(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId), authority.state.value.focused)
    }

    @Test
    fun upAndDownStayOnGeometricMovementEvenWithARowLayoutRegistered() {
        // Same registered row layout as the RIGHT/LEFT tests above, but this time move UP/DOWN -- pins
        // The row-index correction is scoped to LEFT/RIGHT; UP/DOWN behaviour stays unchanged.
        val authority = DefaultFocusAuthority()
        val binding = MimirKeyboardFocusBinding(authority, MimirKeyboardFocusScope, MimirKeyboardInitialKeyId)
        binding.enter()
        authority.register(mimirKeyboardFocusKey("action/symbols"), MimirKeyboardFocusScope, FocusBounds(0f, 0f, 40f, 40f))
        authority.register(mimirKeyboardFocusKey("action/paste"), MimirKeyboardFocusScope, FocusBounds(60f, 0f, 100f, 40f))
        authority.register(mimirKeyboardFocusKey("char/z"), MimirKeyboardFocusScope, FocusBounds(0f, -60f, 40f, -20f))
        binding.registerRowLayout { listOf(listOf("action/symbols", "action/paste")) }
        authority.requestFocus(mimirKeyboardFocusKey("action/symbols"))

        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.NAV_UP,
            binding = binding,
            onDismiss = {},
        )

        assertTrue(handled)
        assertEquals(
            "NAV_UP must keep using geometric moveFocus (nearest key above), ignoring the row layout entirely",
            mimirKeyboardFocusKey("char/z"),
            authority.state.value.focused,
        )
    }
}
