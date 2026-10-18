package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * on WELCOME's first appearance the verse shows alone for 2.5 s,
 * Begin is focused from the start but paints with the rest (2026-10-15), and any press or touch ends it without being swallowed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingWelcomeIntroTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the verse holds alone for the specified two and a half seconds`() {
        assertEquals(2_500L, OnboardingWelcomeVerseAloneMs)
        assertTrue("the verse's own fade fits inside the hold", OnboardingWelcomeVerseAloneMs > OnboardingIntroShineMs)
    }

    @Test
    fun `any press ends the intro, and A still begins`() {
        val intro = OnboardingWelcomeIntroState()
        var advanced = false
        dispatchWelcome(intro, ControllerAction.NAV_DOWN) { advanced = true }
        assertTrue("a D-pad press ends the intro", intro.settled)
        assertFalse("and does not leave WELCOME", advanced)

        val fresh = OnboardingWelcomeIntroState()
        dispatchWelcome(fresh, ControllerAction.CONFIRM) { advanced = true }
        assertTrue(fresh.settled)
        assertTrue("A during the intro lands on Begin, not only on the skip", advanced)
    }

    @Test
    fun `Begin is focused during the hold, and the intro settles on its own after it`() {
        val intro = OnboardingWelcomeIntroState()
        val authority = DefaultFocusAuthority()
        compose.mainClock.autoAdvance = false
        setWelcome(intro, authority)

        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertFalse("still inside the verse's hold at 1 s", intro.settled)
        assertEquals("onboarding/welcome/begin", authority.state.value.focused?.value)

        compose.mainClock.advanceTimeBy(OnboardingWelcomeVerseAloneMs + OnboardingIntroShineMs + 200L)
        compose.waitForIdle()
        assertTrue("the rest has arrived and the intro is settled", intro.settled)
    }

    @Test
    fun `a touch anywhere ends the intro`() {
        val intro = OnboardingWelcomeIntroState()
        compose.mainClock.autoAdvance = false
        setWelcome(intro, DefaultFocusAuthority())
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        assertFalse(intro.settled)

        compose.onRoot().performTouchInput { down(topLeft) }
        compose.mainClock.advanceTimeBy(32L)
        compose.waitForIdle()
        assertTrue(intro.settled)
    }

    private fun dispatchWelcome(intro: OnboardingWelcomeIntroState, action: ControllerAction, onAdvance: () -> Unit) {
        dispatchOnboarding(
            authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) },
            step = OnboardingStep.WELCOME,
            profile = OnboardingProfileState(),
            accessReview = OnboardingAccessReviewState(),
            focusIntent = OnboardingFocusIntentState(),
            welcomeIntro = intro,
            library = OnboardingLibraryState(),
            access = OnboardingAccessUi(usageAvailable = true, usageGranted = true, homeRoleAvailable = true, homeRoleHeld = true),
            artworkAccount = null,
            settlement = readySettlement,
            action = action,
            onAdvance = onAdvance,
            onCommitName = {},
            onRequestFilesAccess = {},
            onRequestUsageAccess = {},
            onRequestHomeRole = {},
            onChooseFolder = {},
            onOpenArtworkSetup = {},
            onOpenSteamGridDbKey = {},
            onRetrySettlement = {},
            onContinueLocally = {},
            onApplyTheme = {},
        )
    }

    private fun setWelcome(intro: OnboardingWelcomeIntroState, authority: DefaultFocusAuthority) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(832.5f.dp).requiredHeight(468.3f.dp)) {
                        OnboardingFlow(
                            authority = authority,
                            step = OnboardingStep.WELCOME,
                            profile = OnboardingProfileState(),
                            accessReview = OnboardingAccessReviewState(),
                            focusIntent = OnboardingFocusIntentState(),
                            access = OnboardingAccessUi(
                                usageAvailable = true,
                                usageGranted = true,
                                homeRoleAvailable = true,
                                homeRoleHeld = true,
                            ),
                            library = OnboardingLibraryState(),
                            libraryGameCount = 0,
                            libraryPlatformCount = 0,
                            welcomeIntro = intro,
                            artworkAccount = null,
                            steamGridDbKeyPresent = false,
                            settlement = readySettlement,
                            currentThemeId = MimirThemeId.DEFAULT,
                            onAdvance = {},
                            onCommitName = {},
                            onRequestFilesAccess = {},
                            onRequestUsageAccess = {},
                            onRequestHomeRole = {},
                            onChooseFolder = {},
                            onOpenArtworkSetup = {},
                            onOpenSteamGridDbKey = {},
                            onRetrySettlement = {},
                            onContinueLocally = {},
                            onApplyTheme = {},
                        )
                    }
                }
            }
        }
    }

    private val readySettlement = OnboardingSettlementUi(
        phase = OnboardingSettlementPhase.READY,
        title = "Ready",
        detail = "Ready",
    )
}
