package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFocusSequenceTest {

    private val pendingAccess = OnboardingAccessUi(
        usageAvailable = true,
        usageGranted = false,
        homeRoleAvailable = true,
        homeRoleHeld = false,
        filesGranted = false,
    )

    @Test
    fun firstDirectionalPressMovesAcrossAThreeControlSetupStep() {
        val usage = FocusKey("usage")
        val home = FocusKey("home")
        val continueKey = FocusKey("continue")
        val keys = listOf(usage, home, continueKey)

        assertEquals(
            home,
            onboardingFocusDestination(keys, continueKey, ControllerAction.NAV_UP),
        )
        assertEquals(
            usage,
            onboardingFocusDestination(keys, home, ControllerAction.NAV_UP),
        )
        assertEquals(
            home,
            onboardingFocusDestination(keys, usage, ControllerAction.NAV_DOWN),
        )
        assertEquals(
            continueKey,
            onboardingFocusDestination(keys, home, ControllerAction.NAV_DOWN),
        )
    }

    @Test
    fun optionalControlAndContinueAreDeterministicWithoutGeometry() {
        val optional = FocusKey("optional")
        val continueKey = FocusKey("continue")
        val keys = listOf(optional, continueKey)

        assertEquals(
            optional,
            onboardingFocusDestination(keys, continueKey, ControllerAction.NAV_UP),
        )
        assertEquals(
            continueKey,
            onboardingFocusDestination(keys, optional, ControllerAction.NAV_DOWN),
        )
    }

    @Test
    fun previousSurfaceFocusFallsBackToTheNewSurfaceEntry() {
        val usage = FocusKey("usage")
        val home = FocusKey("home")
        val continueKey = FocusKey("continue")
        val keys = listOf(usage, home, continueKey)

        val logicalFocus = onboardingLogicalFocus(
            keys = keys,
            authorityFocus = FocusKey("profile/not-now"),
            entryFocus = continueKey,
            pendingFocus = null,
        )

        assertEquals(continueKey, logicalFocus)
        assertEquals(home, onboardingFocusDestination(keys, logicalFocus, ControllerAction.NAV_UP))
    }

    @Test
    fun focusIntentSurvivesRegistrationRaceAndClearsAfterLanding() {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        val intent = OnboardingFocusIntentState()
        val target = FocusKey("onboarding/test/target")
        val keys = listOf(target)

        requestOnboardingFocus(authority, intent, OnboardingFocusSurface.PROFILE, target)
        assertEquals(target, intent.pendingTarget(OnboardingFocusSurface.PROFILE, keys))

        authority.register(
            key = target,
            scope = OnboardingScope,
            bounds = FocusBounds(left = 0f, top = 0f, right = 100f, bottom = 40f),
        )
        requestOnboardingFocus(authority, intent, OnboardingFocusSurface.PROFILE, target)

        assertEquals(target, authority.state.value.focused)
        assertNull(intent.pendingTarget(OnboardingFocusSurface.PROFILE, keys))
    }

    @Test
    fun newerPendingFocusBecomesTheLogicalCurrentBeforeLayout() {
        val first = FocusKey("first")
        val second = FocusKey("second")
        val keys = listOf(first, second)
        val intent = OnboardingFocusIntentState().apply {
            deferTarget(OnboardingFocusSurface.PROFILE_CONFIRM, second)
        }

        assertEquals(
            second,
            onboardingLogicalFocus(
                keys = keys,
                authorityFocus = FocusKey("profile/continue"),
                entryFocus = first,
                pendingFocus = intent.pendingTarget(OnboardingFocusSurface.PROFILE_CONFIRM, keys),
            ),
        )
    }

    @Test
    fun themeActionsQueueInHumanOrderBeforeTheGridRegisters() {
        val intent = OnboardingFocusIntentState().apply {
            deferAction(OnboardingFocusSurface.THEME, ControllerAction.NAV_RIGHT)
            deferAction(OnboardingFocusSurface.THEME, ControllerAction.NAV_RIGHT)
            deferAction(OnboardingFocusSurface.THEME, ControllerAction.NAV_DOWN)
            deferAction(OnboardingFocusSurface.THEME, ControllerAction.NAV_DOWN)
            deferAction(OnboardingFocusSurface.THEME, ControllerAction.CONFIRM)
            deferAction(OnboardingFocusSurface.THEME, ControllerAction.NAV_LEFT)
        }

        assertEquals(
            listOf(
                ControllerAction.NAV_RIGHT,
                ControllerAction.NAV_RIGHT,
                ControllerAction.NAV_DOWN,
                ControllerAction.NAV_DOWN,
                ControllerAction.CONFIRM,
            ),
            intent.pendingActions(OnboardingFocusSurface.THEME),
        )
    }

    @Test
    fun confirmEpochChangesAcrossSameFrameCloseAndReopen() {
        val profile = OnboardingProfileState()
        val intent = OnboardingFocusIntentState()
        val authority = DefaultFocusAuthority().apply {
            pushScope(OnboardingConfirmScope)
            register(OnboardingConfirmKeepKey, OnboardingConfirmScope, FocusBounds(0f, 60f, 100f, 100f))
            register(OnboardingConfirmContinueKey, OnboardingConfirmScope, FocusBounds(120f, 60f, 220f, 100f))
        }

        openProfileConfirm(profile, intent)
        val firstEpoch = profile.confirmEpoch
        assertEquals(
            OnboardingConfirmKeepKey,
            intent.pendingTarget(
                OnboardingFocusSurface.PROFILE_CONFIRM,
                listOf(OnboardingConfirmKeepKey, OnboardingConfirmContinueKey),
            ),
        )
        assertEquals(
            true,
            authority.landOnboardingFocusNow(
                focusIntent = intent,
                surface = OnboardingFocusSurface.PROFILE_CONFIRM,
                entryFocus = OnboardingConfirmKeepKey,
                validKeys = listOf(OnboardingConfirmKeepKey, OnboardingConfirmContinueKey),
            ),
        )
        authority.requestFocus(OnboardingConfirmContinueKey)
        assertEquals(OnboardingConfirmContinueKey, authority.state.value.focused)

        profile.confirmOpen = false
        openProfileConfirm(profile, intent)

        assertEquals(firstEpoch + 1, profile.confirmEpoch)
        assertEquals(
            OnboardingConfirmKeepKey,
            intent.pendingTarget(
                OnboardingFocusSurface.PROFILE_CONFIRM,
                listOf(OnboardingConfirmKeepKey, OnboardingConfirmContinueKey),
            ),
        )
        assertEquals(
            true,
            authority.landOnboardingFocusNow(
                focusIntent = intent,
                surface = OnboardingFocusSurface.PROFILE_CONFIRM,
                entryFocus = OnboardingConfirmKeepKey,
                validKeys = listOf(OnboardingConfirmKeepKey, OnboardingConfirmContinueKey),
            ),
        )
        assertEquals(OnboardingConfirmKeepKey, authority.state.value.focused)
        assertNull(
            intent.pendingTarget(
                OnboardingFocusSurface.PROFILE_CONFIRM,
                listOf(OnboardingConfirmKeepKey, OnboardingConfirmContinueKey),
            ),
        )
    }

    @Test
    fun eachAccessScreenExposesItsOwnActionDeferPairThenOnlyTerminalContinueOnceGranted() {
        // One screen at a time : each capability screen exposes just
        // its own action+defer pair, never another screen's — the old stacked-screen combined list is gone.
        assertEquals(
            listOf("onboarding/access/files", "onboarding/access/files/defer"),
            onboardingAccessScreenFocusKeys(OnboardingAccessScreenId.FILES, pendingAccess).map(FocusKey::value),
        )
        assertEquals(
            listOf("onboarding/access/usage", "onboarding/access/usage/defer"),
            onboardingAccessScreenFocusKeys(OnboardingAccessScreenId.USAGE, pendingAccess).map(FocusKey::value),
        )
        assertEquals(
            listOf("onboarding/access/homeRole", "onboarding/access/homeRole/defer"),
            onboardingAccessScreenFocusKeys(OnboardingAccessScreenId.HOME_ROLE, pendingAccess)
                .map(FocusKey::value),
        )

        val filesGranted = pendingAccess.copy(filesGranted = true)
        assertEquals(
            listOf("onboarding/access/continue"),
            onboardingAccessScreenFocusKeys(OnboardingAccessScreenId.FILES, filesGranted).map(FocusKey::value),
        )
    }

    @Test
    fun measuredRevealReturnsOnlyTheSignedCollisionDistance() {
        assertEquals(
            18,
            onboardingFocusRevealDeltaPx(
                targetTopPx = 180,
                targetBottomPx = 220,
                viewportTopPx = 0,
                viewportBottomPx = 210,
                marginPx = 8,
            ),
        )
        assertEquals(
            -18,
            onboardingFocusRevealDeltaPx(
                targetTopPx = 90,
                targetBottomPx = 130,
                viewportTopPx = 100,
                viewportBottomPx = 310,
                marginPx = 8,
            ),
        )
        assertEquals(
            0,
            onboardingFocusRevealDeltaPx(
                targetTopPx = 108,
                targetBottomPx = 302,
                viewportTopPx = 100,
                viewportBottomPx = 310,
                marginPx = 8,
            ),
        )
    }

    @Test
    fun measuredRevealTraversesEveryAccessScreenTargetDownAndUpOnRp5LandscapeAndOneToOne() {
        // One screen at a time now offers at most an action+defer pair (or just the terminal Continue once
        // granted) rather than eight stacked rows — reveal a representative screen's two targets plus the
        // granted-screen's single Continue target, the full shape any one access screen can present.
        val actionAndDeferKeys = onboardingAccessScreenFocusKeys(OnboardingAccessScreenId.FILES, pendingAccess)
        val terminalContinue = onboardingAccessScreenFocusKeys(
            OnboardingAccessScreenId.FILES,
            pendingAccess.copy(filesGranted = true),
        ).single()
        val keys = actionAndDeferKeys + terminalContinue
        val targets = keys.indices.map { index ->
            OnboardingRevealBounds(
                topPx = 180 + (index * 86),
                bottomPx = 220 + (index * 86),
            )
        }

        listOf(
            SymbolicOnboardingViewport(name = "RP5 landscape", widthPx = 960, heightPx = 540),
            SymbolicOnboardingViewport(name = "1:1", widthPx = 720, heightPx = 720),
        ).forEach { viewport ->
            assertTrue(
                "${viewport.name} shape must stay symbolic",
                if (viewport.name == "1:1") {
                    viewport.widthPx == viewport.heightPx
                } else {
                    viewport.widthPx > viewport.heightPx
                },
            )
            assertSymmetricMinimumReveal(
                viewport = viewport,
                keys = keys,
                targets = targets,
                marginPx = 8,
            )
        }
    }

    private fun assertSymmetricMinimumReveal(
        viewport: SymbolicOnboardingViewport,
        keys: List<FocusKey>,
        targets: List<OnboardingRevealBounds>,
        marginPx: Int,
    ) {
        val contentBottom = targets.last().bottomPx + marginPx
        val maxScroll = (contentBottom - viewport.heightPx).coerceAtLeast(0)
        var scroll = 0

        fun reveal(index: Int) {
            val target = targets[index]
            val delta = onboardingFocusRevealDeltaPx(
                targetTopPx = target.topPx,
                targetBottomPx = target.bottomPx,
                viewportTopPx = scroll,
                viewportBottomPx = scroll + viewport.heightPx,
                marginPx = marginPx,
            )
            scroll = (scroll + delta).coerceIn(0, maxScroll)
            assertTrue(
                "${viewport.name}: ${keys[index].value} top must be revealed",
                target.topPx - marginPx >= scroll,
            )
            assertTrue(
                "${viewport.name}: ${keys[index].value} bottom must be revealed",
                target.bottomPx + marginPx <= scroll + viewport.heightPx,
            )
        }

        var focused = keys.first()
        reveal(keys.indexOf(focused))
        keys.drop(1).forEach { expected ->
            focused = requireNotNull(
                onboardingFocusDestination(keys, focused, ControllerAction.NAV_DOWN),
            )
            assertEquals(expected, focused)
            reveal(keys.indexOf(focused))
        }
        keys.asReversed().drop(1).forEach { expected ->
            focused = requireNotNull(
                onboardingFocusDestination(keys, focused, ControllerAction.NAV_UP),
            )
            assertEquals(expected, focused)
            reveal(keys.indexOf(focused))
        }
    }

    private data class SymbolicOnboardingViewport(
        val name: String,
        val widthPx: Int,
        val heightPx: Int,
    )
}
