package com.gruning.mimir.rewrite

import androidx.compose.animation.core.TweenSpec
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirCrossSwapMs
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirCrossSwapSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * , the header still; since 2026-10-15 an even cross-fade rather than out,
 * then in. The arithmetic the scaffold's layers run, and the one gate that keeps a press from acting on a step the
 * player cannot see yet.
 */
class OnboardingStepGlideTest {
    private val travel = 22f

    @Test
    fun `an arriving step comes in from the trailing edge and settles in place`() {
        assertEquals(travel, onboardingStepTravelPx(arrival = 0f, departure = 0f, arrivalTravels = true, travelPx = travel))
        assertEquals(travel / 2, onboardingStepTravelPx(0.5f, 0f, true, travel))
        assertEquals(0f, onboardingStepTravelPx(1f, 0f, true, travel))
        assertEquals(0f, onboardingStepAlpha(arrival = 0f, departure = 0f))
        assertEquals(1f, onboardingStepAlpha(arrival = 1f, departure = 0f))
    }

    @Test
    fun `a departing step leaves toward the leading edge, the way a forward handover glides`() {
        assertEquals(-travel, onboardingStepTravelPx(arrival = 1f, departure = 1f, arrivalTravels = true, travelPx = travel))
        assertEquals(-travel / 2, onboardingStepTravelPx(1f, 0.5f, true, travel))
        assertEquals(0f, onboardingStepAlpha(arrival = 1f, departure = 1f))
        // The same direction the house glide gives a forward handover.
        assertTrue(onboardingGlideOffsetPx(forward = true, travelPx = 22, incoming = false, reducedMotion = false) < 0)
        assertTrue(onboardingGlideOffsetPx(forward = true, travelPx = 22, incoming = true, reducedMotion = false) > 0)
    }

    @Test
    fun `a step shown with nothing departing fades in without travel`() {
        assertEquals(0f, onboardingStepTravelPx(arrival = 0f, departure = 0f, arrivalTravels = false, travelPx = travel))
    }

    /**
     * A Nova report on 2026-09-30 identified a cut to blank before the next fade: THEME was the one step that did not glide out, so a pick
     * cut the gallery away and the next step (the favourites, or FINISH on an empty library) faded in over an empty
     * sky. Every step glides now unless motion is reduced, and the gallery reads the same two clocks a scaffold's
     * body does.
     */
    @Test
    fun `THEME glides like every other step, and nothing glides under reduced motion`() {
        val exit = flow.substringAfter("LaunchedEffect(step) {").substringBefore("val currentStep by rememberUpdatedState(step)")
        assertTrue(
            "every step cross-fades out on the swap clock, which is 0 ms when motion is reduced",
            exit.contains("departure.animateTo(1f, mimirCrossSwapSpec(arrivalReducedMotion))"),
        )
        assertFalse("no step is exempt", exit.contains("OnboardingStep.THEME"))
        val theme = flow.substringAfter("private fun OnboardingThemeScreen(").substringBefore("\n}\n")
        assertTrue(
            "the gallery fades with the step's arrival and departure",
            theme.contains("alpha = onboardingStepAlpha(arrival.value, handover.departure.value)"),
        )
        assertTrue("and travels with them", theme.contains("translationX = onboardingStepTravelPx("))
        assertTrue("the step owns the gallery's presence", theme.contains("presenceManagedExternally = true"))
        assertTrue("a gallery gliding out takes no pick", theme.contains("if (!departing) {"))
        assertTrue("and no Back", theme.contains("onDismiss = { if (!departing) onAdvance() }"))
    }

    @Test
    fun `a step handover is one cross-fade clock, not an exit and an entry`() {
        // the swap tier's one OverlayMs on the house easing for the step left
        // and the step arriving, complementary, instead of 2026-09-28's 360 ms exit then 540 ms entry (a dip between them).
        assertEquals(MimirMotion.OverlayMs, MimirCrossSwapMs)
        val spec = mimirCrossSwapSpec<Float>(reducedMotion = false) as TweenSpec<Float>
        assertEquals(MimirMotion.OverlayMs, spec.durationMillis)
        assertEquals(0, spec.delay)
        assertEquals(0, (mimirCrossSwapSpec<Float>(reducedMotion = true) as TweenSpec<Float>).durationMillis)
        // The arriving step's alpha is p and the one leaving is 1 - p: complementary, so the pane never empties.
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { p ->
            val arriving = onboardingStepAlpha(arrival = p, departure = 0f)
            val leaving = onboardingStepAlpha(arrival = 1f, departure = p)
            assertEquals(1f, arriving + leaving, 0.0001f)
            assertTrue("coverage at $p", 1f - (1f - arriving) * (1f - leaving) >= 0.75f)
        }
    }

    @Test
    fun `the header seat stays still between two seated steps and fades in place only across a missing seat`() {
        val still = OnboardingStepHandover(StateOf(0.5f), arrivalTravels = true, headerArrives = false, headerDeparts = false)
        assertEquals(1f, onboardingHeaderSeatAlpha(arrival = 0.2f, departure = 0.5f, handover = still))
        val arriving = OnboardingStepHandover(StateOf(0f), true, headerArrives = true, headerDeparts = false)
        assertEquals(0.2f, onboardingHeaderSeatAlpha(0.2f, 0f, arriving))
        val departing = OnboardingStepHandover(StateOf(0.25f), true, headerArrives = false, headerDeparts = true)
        assertEquals(0.75f, onboardingHeaderSeatAlpha(1f, 0.25f, departing))

        assertFalse(onboardingStepHasHeaderSeat(OnboardingStep.WELCOME))
        assertFalse(onboardingStepHasHeaderSeat(OnboardingStep.THEME))
        OnboardingStep.entries.filterNot { it == OnboardingStep.WELCOME || it == OnboardingStep.THEME }.forEach {
            assertTrue(it.name, onboardingStepHasHeaderSeat(it))
        }
    }

    @Test
    fun `the glide state departs exactly while the navigation is ahead of the step on screen`() {
        val glide = OnboardingStepGlideState(OnboardingStep.WELCOME)
        assertFalse(glide.departing(OnboardingStep.WELCOME))
        assertTrue(glide.departing(OnboardingStep.PROFILE))
        glide.show(OnboardingStep.PROFILE)
        assertFalse(glide.departing(OnboardingStep.PROFILE))
    }

    @Test
    fun `a press while the step on screen glides out is dropped, so a quick second A cannot skip a step`() {
        val glide = OnboardingStepGlideState(OnboardingStep.WELCOME)
        var advanced = 0
        // The navigation has moved to PROFILE; WELCOME is still gliding out.
        assertTrue(dispatch(OnboardingStep.PROFILE, glide, ControllerAction.CONFIRM) { advanced++ })
        assertEquals(0, advanced)
        // Once the step on screen has caught up, the same press acts.
        glide.show(OnboardingStep.PROFILE)
        val welcome = OnboardingStepGlideState(OnboardingStep.WELCOME)
        assertTrue(dispatch(OnboardingStep.WELCOME, welcome, ControllerAction.CONFIRM) { advanced++ })
        assertEquals(1, advanced)
    }

    private fun dispatch(
        step: OnboardingStep,
        glide: OnboardingStepGlideState,
        action: ControllerAction,
        onAdvance: () -> Unit,
    ): Boolean = dispatchOnboarding(
        authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) },
        step = step,
        profile = OnboardingProfileState(),
        accessReview = OnboardingAccessReviewState(),
        focusIntent = OnboardingFocusIntentState(),
        library = OnboardingLibraryState(),
        access = OnboardingAccessUi(
            usageAvailable = true,
            usageGranted = false,
            homeRoleAvailable = true,
            homeRoleHeld = false,
        ),
        stepGlide = glide,
        artworkAccount = null,
        settlement = OnboardingSettlementUi(
            phase = OnboardingSettlementPhase.READY,
            title = "Ready",
            detail = "Ready",
        ),
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

    private class StateOf(override val value: Float) : androidx.compose.runtime.State<Float>

    private val flow: String by lazy {
        listOf(
            java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
        ).first(java.io.File::isFile).readText()
    }
}
