package com.gruning.mimir.rewrite

import android.app.Service
import android.view.Display
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.ui.mimirStaticChrome
import com.gruning.mimir.rewrite.feature.library.HomeShelfQuickRailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfTomeReturnState
import com.gruning.mimir.rewrite.feature.library.beginHomeShelfTomeReturn
import com.gruning.mimir.rewrite.feature.library.cancelHomeShelfTomeReturn
import com.gruning.mimir.rewrite.feature.library.homeShelfTomeFocusKey
import com.gruning.mimir.rewrite.feature.library.enterHomeLaunchCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MButtonRuntimeTest {
    @Test
    fun `M-menu MainActivity starts are pinned to the Main display`() {
        // QA 2026-09-11, D1: without a launch display Android used the FOCUSED display, so Quick launch and
        // the Utility fallback from the Bottom menu created a MainActivity task on display 4.
        org.junit.Assert.assertEquals(android.view.Display.DEFAULT_DISPLAY, mButtonAuthorityLaunchDisplayId())
    }

    @Test
    fun `tabs failure copy never prescribes Shizuku when built-in access may own the action`() {
        val copy = systemRecentsFailureCopy()

        assertTrue(copy.contains("Android app switching"))
        assertFalse(copy.contains("Shizuku", ignoreCase = true))
    }

    @Test
    fun `tabs opens immediately when Android app switching is ready`() {
        val gateway = MButtonRecentsProbe(PrivilegedUtilityStatus.READY)

        assertTrue(openSystemRecentsFromMButton(gateway).accepted)
        assertEquals(1, gateway.statusReads)
        assertEquals(0, gateway.accessRequests)
        assertEquals(1, gateway.openRequests)
    }

    @Test
    fun `tabs settles explicit built in consent once before opening`() {
        val gateway = MButtonRecentsProbe(
            initialStatus = PrivilegedUtilityStatus.PERMISSION_REQUIRED,
            grantOnRequest = true,
        )

        assertTrue(openSystemRecentsFromMButton(gateway).accepted)
        assertEquals(2, gateway.statusReads)
        assertEquals(1, gateway.accessRequests)
        assertEquals(1, gateway.openRequests)
    }

    @Test
    fun `tabs does not start or retry an unavailable authority`() {
        val gateway = MButtonRecentsProbe(PrivilegedUtilityStatus.SERVICE_STOPPED)

        assertFalse(openSystemRecentsFromMButton(gateway).accepted)
        assertEquals(1, gateway.statusReads)
        assertEquals(0, gateway.accessRequests)
        assertEquals(0, gateway.openRequests)
    }

    @Test
    fun `tabs does not open when built in consent reports success without ready posture`() {
        val gateway = MButtonRecentsProbe(
            initialStatus = PrivilegedUtilityStatus.PERMISSION_REQUIRED,
            consentRequestAccepted = true,
        )

        assertFalse(openSystemRecentsFromMButton(gateway).accepted)
        assertEquals(2, gateway.statusReads)
        assertEquals(1, gateway.accessRequests)
        assertEquals(0, gateway.openRequests)
    }

    @Test
    fun `tabs reports rejection when ready authority rejects the open request`() {
        val gateway = MButtonRecentsProbe(
            initialStatus = PrivilegedUtilityStatus.READY,
            openAccepted = false,
        )

        assertFalse(openSystemRecentsFromMButton(gateway).accepted)
        assertEquals(1, gateway.statusReads)
        assertEquals(0, gateway.accessRequests)
        assertEquals(1, gateway.openRequests)
    }

    @Test
    fun `library navigation hint visibility is separately bounded and defaults on`() {
        assertEquals(listOf("Off", "On"), NavigationHintVisibility.entries.map { it.displayLabel })
        assertEquals(NavigationHintVisibility.OFF, NavigationHintVisibility.OFF.step(-1))
        assertEquals(NavigationHintVisibility.ON, NavigationHintVisibility.OFF.step(1))
        assertEquals(NavigationHintVisibility.ON, NavigationHintVisibility.ON.step(1))
        assertEquals(NavigationHintVisibility.OFF, NavigationHintVisibility.ON.step(-1))
        assertEquals(NavigationHintVisibility.ON, resolveNavigationHintVisibility(null))
        assertEquals(NavigationHintVisibility.ON, resolveNavigationHintVisibility("future_or_corrupt"))
        assertEquals(NavigationHintVisibility.OFF, resolveNavigationHintVisibility("OFF"))
        assertFalse(NavigationHintVisibility.OFF.enabled)
        assertTrue(NavigationHintVisibility.ON.enabled)
    }

    @Test
    fun `visibility policy progresses from disabled through longer waits to always visible`() {
        assertEquals(
            listOf(
                "Disabled",
                "15 seconds",
                "30 seconds",
                "60 seconds",
                "120 seconds",
                "Always visible",
            ),
            MButtonVisibilityPolicy.entries.map(MButtonVisibilityPolicy::displayLabel),
        )
        assertEquals(
            MButtonVisibilityPolicy.HIDE_AFTER_15_SECONDS,
            MButtonVisibilityPolicy.DISABLED.step(1),
        )
        assertEquals(
            MButtonVisibilityPolicy.ALWAYS_VISIBLE,
            MButtonVisibilityPolicy.ALWAYS_VISIBLE.step(1),
        )
        assertEquals(
            MButtonVisibilityPolicy.HIDE_AFTER_120_SECONDS,
            MButtonVisibilityPolicy.ALWAYS_VISIBLE.step(-1),
        )
        assertFalse(MButtonVisibilityPolicy.DISABLED.buttonEnabled)
        assertTrue(MButtonVisibilityPolicy.ALWAYS_VISIBLE.buttonEnabled)
        assertNull(MButtonVisibilityPolicy.ALWAYS_VISIBLE.timeoutMillis)
    }

    @Test
    fun `Utility describes M as a product control without topology or overlay jargon`() {
        // Authored in sentence case; MimirChromeText paints the caps and speaks the natural form.
        assertEquals("ᛗ · Utility", UtilityHeader)
        assertEquals("ᛗ · UTILITY", mimirStaticChrome(UtilityHeader))
        assertEquals(
            "The Mimir button is off.",
            mButtonVisibilitySupportingText(MButtonVisibilityPolicy.DISABLED),
        )
        assertEquals(
            "The Mimir button stays visible.",
            mButtonVisibilitySupportingText(MButtonVisibilityPolicy.ALWAYS_VISIBLE),
        )
        assertEquals(
            "The Mimir button hides after inactivity. Touch the screen to bring it back.",
            mButtonVisibilitySupportingText(MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS),
        )
        assertEquals("Mimir button off", mButtonVisibilityStateDescription(MButtonVisibilityPolicy.DISABLED))
        assertEquals(
            "Mimir button hides after 30 seconds",
            mButtonVisibilityStateDescription(MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS),
        )
        listOf(MButtonVisibilityPolicy.DISABLED, MButtonVisibilityPolicy.ALWAYS_VISIBLE).forEach { policy ->
            val visibleCopy = mButtonVisibilitySupportingText(policy).lowercase()
            assertFalse("single-display" in visibleCopy)
            assertFalse("secondary" in visibleCopy)
            assertFalse("companion" in visibleCopy)
            assertFalse("overlay" in visibleCopy)
        }
    }

    @Test
    fun `idle deadline is latest wins for the active M and untimed policies never arm`() {
        val ledger = MButtonIdleDeadlineLedger()
        assertNull(ledger.arm(MButtonVisibilityPolicy.DISABLED))
        assertNull(ledger.arm(MButtonVisibilityPolicy.ALWAYS_VISIBLE))

        val stale = requireNotNull(ledger.arm(MButtonVisibilityPolicy.HIDE_AFTER_15_SECONDS))
        val current = requireNotNull(ledger.arm(MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS))

        assertFalse(
            ledger.accepts(stale, MButtonVisibilityPolicy.HIDE_AFTER_15_SECONDS, mButtonActive = true),
        )
        assertFalse(
            ledger.accepts(current, MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS, mButtonActive = false),
        )
        assertTrue(
            ledger.accepts(current, MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS, mButtonActive = true),
        )
        ledger.cancel()
        assertFalse(
            ledger.accepts(current, MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS, mButtonActive = true),
        )
    }

    @Test
    fun `legacy visibility migration is deterministic and a recognized policy wins`() {
        assertEquals(
            MButtonVisibilityPolicy.ALWAYS_VISIBLE,
            resolveMButtonVisibilityPolicy(storedPolicyName = null, legacyEnabled = true),
        )
        assertEquals(
            MButtonVisibilityPolicy.DISABLED,
            resolveMButtonVisibilityPolicy(storedPolicyName = null, legacyEnabled = false),
        )
        assertEquals(
            MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS,
            resolveMButtonVisibilityPolicy(
                storedPolicyName = MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS.name,
                legacyEnabled = false,
            ),
        )
        assertEquals(
            MButtonVisibilityPolicy.ALWAYS_VISIBLE,
            resolveMButtonVisibilityPolicy(storedPolicyName = "future_or_corrupt", legacyEnabled = true),
        )
        MButtonVisibilityPolicy.entries.forEach { policy ->
            assertEquals(policy != MButtonVisibilityPolicy.DISABLED, policy.buttonEnabled)
        }
    }

    @Test
    fun `single display requires explicit enable usage access and completed onboarding`() {
        val ready = MButtonEligibility(
            presentationDisplayId = null,
            primaryEnabled = true,
            usageAccessGranted = true,
            onboardingComplete = true,
        )

        val target = selectMButtonOverlayTarget(ready)
        assertEquals(CompanionOverlayRole.PRIMARY_SINGLE, target?.role)
        assertEquals(Display.DEFAULT_DISPLAY, target?.displayId)
        assertNull(selectMButtonOverlayTarget(ready.copy(primaryEnabled = false)))
        assertNull(selectMButtonOverlayTarget(ready.copy(usageAccessGranted = false)))
        assertNull(selectMButtonOverlayTarget(ready.copy(onboardingComplete = false)))
        assertNull(selectMButtonOverlayTarget(ready.copy(overlayPermissionGranted = false)))
    }

    @Test
    fun `enabled presentation display selects one secondary role and suppresses primary duplicate`() {
        listOf(false, true).forEach { enabled ->
            listOf(false, true).forEach { usage ->
                val target = selectMButtonOverlayTarget(
                    MButtonEligibility(
                        presentationDisplayId = 7,
                        primaryEnabled = enabled,
                        usageAccessGranted = usage,
                        onboardingComplete = true,
                    ),
                )
                if (enabled) {
                    assertEquals(CompanionOverlayRole.SECONDARY_COMPANION, target?.role)
                    assertEquals(7, target?.displayId)
                } else {
                    assertNull(target)
                }
            }
        }
    }

    @Test
    fun `only the eligible primary global M target retains process importance`() {
        assertEquals(Service.START_NOT_STICKY, MButtonKeepAliveRestartMode)
        assertFalse(shouldKeepMButtonProcessAlive(null))
        assertTrue(
            shouldKeepMButtonProcessAlive(
                com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget(
                    CompanionOverlayRole.PRIMARY_SINGLE,
                    Display.DEFAULT_DISPLAY,
                ),
            ),
        )
        assertFalse(
            shouldKeepMButtonProcessAlive(
                com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget(
                    CompanionOverlayRole.SECONDARY_COMPANION,
                    7,
                ),
            ),
        )
    }

    @Test
    fun `cold process defaults to an external primary panel route`() {
        assertTrue(PrimaryActivityObservationState.stopped.value)
    }

    @Test
    fun `Library suppresses primary M only while a full-width text-entry sheet owns its corner`() {
        assertFalse(
            suppressPrimaryMForLibraryTextEntry(
                searchKeyboardOpen = false,
                nativeAddKeyboardOpen = false,
            ),
        )
        assertTrue(
            suppressPrimaryMForLibraryTextEntry(
                searchKeyboardOpen = true,
                nativeAddKeyboardOpen = false,
            ),
        )
        assertTrue(
            suppressPrimaryMForLibraryTextEntry(
                searchKeyboardOpen = false,
                nativeAddKeyboardOpen = true,
            ),
        )
        assertTrue(
            suppressPrimaryMForLibraryTextEntry(
                searchKeyboardOpen = true,
                nativeAddKeyboardOpen = true,
            ),
        )
    }

    @Test
    fun `overlay and Tabs capabilities are not smuggled into Usage eligibility`() {
        // Selection intentionally has no overlay-permission or Shizuku field: it targets the primary role
        // after explicit enable + Usage access, then those two independent capabilities fail closed at dispatch.
        val fields = MButtonEligibility::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("overlayPermission" !in fields)
        assertTrue("tabsCapability" !in fields)
    }

    @Test
    fun `command tokens are unguessable one-shot and newer issue invalidates stale intent`() {
        val ledger = MButtonCommandLedger()
        val stale = ledger.issue(MButtonPrimaryCommand.HOME)
        val current = ledger.issue(MButtonPrimaryCommand.QUICK_LAUNCH)

        assertNull(ledger.consume("not-a-real-token"))
        assertNull(ledger.consume(stale))
        assertEquals(MButtonPrimaryCommand.QUICK_LAUNCH, ledger.consume(current))
        assertNull(ledger.consume(current))
    }

    @Test
    fun `external M destination is retained before Activity delivery and acknowledged by exact id`() {
        val requestId = MButtonNavigationCommands.publish(MButtonPrimaryCommand.UTILITY)
        try {
            assertEquals(
                MButtonPrimaryCommand.UTILITY,
                MButtonNavigationCommands.pending.value?.command,
            )
            MButtonNavigationCommands.acknowledge(java.util.UUID.randomUUID())
            assertEquals(requestId, MButtonNavigationCommands.pending.value?.id)
        } finally {
            MButtonNavigationCommands.acknowledge(requestId)
        }
        assertNull(MButtonNavigationCommands.pending.value)
    }

    @Test
    fun `primary Tabs routes only to system Recents and cannot fall through to secondary tabs`() {
        assertEquals(PrimaryMButtonDispatch.SYSTEM_RECENTS, primaryMButtonDispatch(QuickMenuItem.SYSTEM_TABS))
        assertEquals(PrimaryMButtonDispatch.UTILITY, primaryMButtonDispatch(QuickMenuItem.UTILITY))
        assertEquals(PrimaryMButtonDispatch.QUICK_LAUNCH, primaryMButtonDispatch(QuickMenuItem.QUICK_LAUNCH))
        assertEquals(PrimaryMButtonDispatch.REJECT, primaryMButtonDispatch(QuickMenuItem.SECONDARY_TABS))
        assertEquals(PrimaryMButtonDispatch.REJECT, primaryMButtonDispatch(QuickMenuItem.SECONDARY_RETURN))
        assertEquals(PrimaryMButtonDispatch.REJECT, primaryMButtonDispatch(QuickMenuItem.SECONDARY_SWAP))
    }

    @Test
    fun `primary-only commands release a secondary menu request as rejected`() {
        listOf(
            QuickMenuItem.SYSTEM_TABS,
            QuickMenuItem.QUICK_LAUNCH,
            QuickMenuItem.UTILITY,
        ).forEach { item ->
            assertFalse(secondaryQuickMenuFallbackAcceptance(item))
        }
    }

    @Test
    fun `global command mapping covers Quick Launch, Utility and Tweaker`() {
        assertEquals(
            MButtonPrimaryCommand.QUICK_LAUNCH,
            globalMButtonCommand(QuickMenuItem.QUICK_LAUNCH),
        )
        assertEquals(
            MButtonPrimaryCommand.UTILITY,
            globalMButtonCommand(QuickMenuItem.UTILITY),
        )
        assertNull(globalMButtonCommand(QuickMenuItem.SYSTEM_TABS))
        assertEquals(MButtonPrimaryCommand.TWEAKER, globalMButtonCommand(QuickMenuItem.TWEAKER))
        assertNull(globalMButtonCommand(QuickMenuItem.SECONDARY_TABS))
        assertNull(globalMButtonCommand(QuickMenuItem.SECONDARY_RETURN))
        assertNull(globalMButtonCommand(QuickMenuItem.SECONDARY_SWAP))
    }

    @Test
    fun `the Bottom menu's Utility is the Bottom window whatever is on top, and no other row or role is`() {
        // #267 : the route asks neither which window is on top of the Bottom (Mimir's Presentation, an
        // emulator's own second-screen window, a yielded app) nor whether the Presentation shows. It is the overlay window, always.
        assertTrue(bottomUtilityRoute(CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.UTILITY))
        assertFalse(bottomUtilityRoute(CompanionOverlayRole.PRIMARY_SINGLE, QuickMenuItem.UTILITY))
        QuickMenuItem.entries.filter { it != QuickMenuItem.UTILITY }.forEach { item ->
            assertFalse(item.name, bottomUtilityRoute(CompanionOverlayRole.SECONDARY_COMPANION, item))
        }
    }

    @Test
    fun `the Presentation draws the instruments layer only when the Utility window is not carrying the panel`() {
        assertTrue(bottomInstrumentsPanelShownInPresentation(openOnBottom = true, utilityWindowCarriesPanel = false))
        assertFalse("no second copy beneath the window", bottomInstrumentsPanelShownInPresentation(openOnBottom = true, utilityWindowCarriesPanel = true))
        assertFalse(bottomInstrumentsPanelShownInPresentation(openOnBottom = false, utilityWindowCarriesPanel = false))
        assertFalse(bottomInstrumentsPanelShownInPresentation(openOnBottom = false, utilityWindowCarriesPanel = true))
    }

    @Test
    fun `controller mapping global actions route through the same M command authority`() {
        assertEquals(
            MButtonPrimaryCommand.QUICK_LAUNCH,
            mappedGlobalMButtonCommand(ControllerAction.GLOBAL_QUICK_LAUNCH),
        )
        assertEquals(
            MButtonPrimaryCommand.UTILITY,
            mappedGlobalMButtonCommand(ControllerAction.GLOBAL_UTILITY),
        )
        assertEquals(
            MButtonPrimaryCommand.SETTINGS,
            mappedGlobalMButtonCommand(ControllerAction.GLOBAL_SETTINGS),
        )
        assertNull(mappedGlobalMButtonCommand(ControllerAction.CONFIRM))
    }

    @Test
    fun `Home command leaves a stale quick rail and restores the remembered tile`() {
        val authority = DefaultFocusAuthority()
        val tile = FocusKey("home/tile/dantes-inferno")
        val dock = FocusKey("home/rail/home")
        val bounds = FocusBounds(0f, 0f, 100f, 100f)
        authority.register(tile, HomeShelfScope, bounds)
        authority.register(dock, HomeShelfQuickRailScope, bounds)
        authority.pushScope(HomeShelfScope)
        authority.requestFocus(tile)
        authority.pushScope(HomeShelfQuickRailScope)

        restoreHomeContentFocusAfterMButton(authority)

        assertEquals(HomeShelfScope, authority.state.value.scope)
        assertEquals(tile, authority.state.value.focused)
    }

    @Test
    fun `launch card close restores Home focus before its outgoing pixels finish`() {
        val authority = DefaultFocusAuthority()
        val tile = FocusKey("home/tile/frame-zero")
        authority.register(tile, HomeShelfScope, FocusBounds(0f, 0f, 100f, 100f))
        authority.pushScope(HomeShelfScope)
        authority.requestFocus(tile)
        enterHomeLaunchCard(authority, emulated = true)

        restoreHomeCarouselForLaunchCardClose(authority)

        assertEquals(HomeShelfScope, authority.state.value.scope)
        assertEquals(tile, authority.state.value.focused)
    }

    @Test
    fun `primary M cancels Tome return before overlay close or navigation`() {
        val order = mutableListOf<String>()
        var state = beginHomeShelfTomeReturn(
            HomeShelfTomeReturnState(),
            homeShelfTomeFocusKey(
                com.gruning.mimir.rewrite.feature.library.TomeId.Custom(
                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000099"),
                ),
            ),
        )

        runPrimaryMRequestAfterTomeCancel(
            cancelTomeReturn = {
                state = cancelHomeShelfTomeReturn(state)
                order += "cancel-tome"
            },
            transaction = {
                order += "close-overlays"
                order += "navigate"
            },
        )

        assertEquals(listOf("cancel-tome", "close-overlays", "navigate"), order)
        assertNull(state.pending)
    }

    @Test
    fun `external primary M uses panel without foregrounding Activity`() {
        val calls = mutableListOf<String>()

        val accepted = dispatchMButtonPrimarySurface(
            role = CompanionOverlayRole.PRIMARY_SINGLE,
            primaryActivityStopped = true,
            command = MButtonPrimaryCommand.QUICK_LAUNCH,
            showPanel = {
                calls += "panel:$it"
                true
            },
            dispatchActivity = {
                calls += "activity:$it"
                true
            },
        )

        assertTrue(accepted)
        assertEquals(listOf("panel:QUICK_LAUNCH"), calls)
    }

    @Test
    fun `panel rejection falls back once and foreground Mimir keeps its Activity authority`() {
        val externalCalls = mutableListOf<String>()
        assertTrue(
            dispatchMButtonPrimarySurface(
                role = CompanionOverlayRole.PRIMARY_SINGLE,
                primaryActivityStopped = true,
                command = MButtonPrimaryCommand.UTILITY,
                showPanel = {
                    externalCalls += "panel"
                    false
                },
                dispatchActivity = {
                    externalCalls += "activity"
                    true
                },
            ),
        )
        assertEquals(listOf("panel", "activity"), externalCalls)

        val foregroundCalls = mutableListOf<String>()
        assertTrue(
            dispatchMButtonPrimarySurface(
                role = CompanionOverlayRole.PRIMARY_SINGLE,
                primaryActivityStopped = false,
                command = MButtonPrimaryCommand.UTILITY,
                showPanel = {
                    foregroundCalls += "panel"
                    true
                },
                dispatchActivity = {
                    foregroundCalls += "activity"
                    true
                },
            ),
        )
        assertEquals(listOf("activity"), foregroundCalls)
    }
}

private class MButtonRecentsProbe(
    initialStatus: PrivilegedUtilityStatus,
    private val grantOnRequest: Boolean = false,
    private val consentRequestAccepted: Boolean = grantOnRequest,
    private val openAccepted: Boolean = true,
) : MButtonSystemRecentsGateway {
    private var status = initialStatus
    var statusReads = 0
        private set
    var accessRequests = 0
        private set
    var openRequests = 0
        private set

    override fun systemRecentsAccessStatus(): PrivilegedUtilityStatus {
        statusReads += 1
        return status
    }

    override fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus): Boolean {
        error("M-button Tabs must not use the general access route")
    }

    override fun requestBuiltInSystemRecentsConsent(
        expectedStatus: PrivilegedUtilityStatus,
    ): Boolean {
        accessRequests += 1
        if (!consentRequestAccepted || status != expectedStatus) return false
        if (grantOnRequest) status = PrivilegedUtilityStatus.READY
        return consentRequestAccepted
    }

    override fun openSystemRecents(): Boolean {
        openRequests += 1
        return openAccepted
    }
}
