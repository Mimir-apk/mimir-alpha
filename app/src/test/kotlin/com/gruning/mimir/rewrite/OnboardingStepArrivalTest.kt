package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * , the header still; since 2026-10-15 an even
 * cross-fade ([com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs], both steps composed together) rather than out,
 * then in. Nothing reaches the departing step; the arriving one has focus from the start, takes a touch once the
 * cross-fade is done, and the header strip sits exactly where the previous step left it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingStepArrivalTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the departing step is untouchable beside the arriving one, which has focus and takes a touch once it is done`() {
        var step by mutableStateOf(OnboardingStep.PROFILE)
        val authority = DefaultFocusAuthority()
        val accessReview = OnboardingAccessReviewState()
        val profile = OnboardingProfileState()
        val glide = OnboardingStepGlideState(OnboardingStep.PROFILE)
        var advanced = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(832.5f.dp).requiredHeight(468.3f.dp)) {
                        OnboardingFlow(
                            authority = authority,
                            step = step,
                            profile = profile,
                            stepGlide = glide,
                            accessReview = accessReview,
                            focusIntent = OnboardingFocusIntentState(),
                            access = OnboardingAccessUi(
                                usageAvailable = true,
                                usageGranted = false,
                                homeRoleAvailable = true,
                                homeRoleHeld = false,
                            ),
                            library = OnboardingLibraryState(),
                            libraryGameCount = 0,
                            libraryPlatformCount = 0,
                            artworkAccount = null,
                            steamGridDbKeyPresent = false,
                            settlement = OnboardingSettlementUi(
                                phase = OnboardingSettlementPhase.IDENTIFYING,
                                title = "Finishing your library",
                                detail = "Mimir is finishing up.",
                            ),
                            currentThemeId = MimirThemeId.DEFAULT,
                            onAdvance = { advanced++ }, onCommitName = {}, onRequestFilesAccess = {},
                            onRequestUsageAccess = {},
                            onRequestHomeRole = {},
                            onChooseFolder = {}, onOpenArtworkSetup = {}, onOpenSteamGridDbKey = {},
                            onRetrySettlement = {}, onContinueLocally = {}, onApplyTheme = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val strip = hasTestTag(OnboardingHeaderStripTag)
        val profileSeat = compose.onNode(strip, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        val profileFocus = authority.state.value.focused
        compose.mainClock.autoAdvance = false
        step = OnboardingStep.ACCESS
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // Cross-fading: PROFILE is still the step the dispatch acts on, ACCESS is composed beside it, and a tap on PROFILE's
        // Continue reaches nothing.
        assertEquals(OnboardingStep.PROFILE, glide.displayedStep)
        val notNow = hasClickAction() and
            (hasText("Not now", ignoreCase = true) or hasContentDescription("Not now", ignoreCase = true))
        assertTrue("the step left is composed beside the arriving one", compose.onAllNodes(notNow).fetchSemanticsNodes().isNotEmpty())
        assertFalse(profile.confirmOpen)
        compose.onAllNodes(
            hasClickAction() and
                (hasText("Continue", ignoreCase = true) or hasContentDescription("Continue", ignoreCase = true)),
        ).onFirst().performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("a tap on the departing step must not act", 0, advanced)
        assertFalse("a tap on the departing step must not open its confirm", profile.confirmOpen)

        // ACCESS is the step on screen once the cross-fade has run, not before.
        var exitFrames = 0
        while (glide.displayedStep != OnboardingStep.ACCESS) {
            compose.mainClock.advanceTimeByFrame()
            exitFrames += 1
            // The cross-fade runs on the swap clock (a 16 ms frame each), plus three frames of settle.
            assertTrue("the cross-fade never finished", exitFrames <= MimirMotion.OverlayMs / 16 + 3)
        }
        val start = compose.mainClock.currentTime
        // landFocus retries on up to 8 frames; every one of them is inside the 180 ms arrival.
        var frames = 0
        while (authority.state.value.focused.let { it == null || it == profileFocus } ||
            authority.state.value.scope != OnboardingScope
        ) {
            compose.mainClock.advanceTimeByFrame()
            frames += 1
            assertTrue("focus never landed on ACCESS within 8 frames", frames <= 8)
        }
        compose.mainClock.advanceTimeByFrame()
        val elapsed = compose.mainClock.currentTime - start
        assertTrue("the check must run inside the arrival, but $elapsed ms had passed", elapsed < 180)

        assertNotNull(authority.state.value.focused)
        val accessSeat = compose.onNode(strip, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("the header must not move while the step under it glides in", profileSeat, accessSeat)

        val before = accessReview.currentScreen
        compose.onNode(
            hasClickAction() and
                (hasText("Continue", ignoreCase = true) or hasContentDescription("Continue", ignoreCase = true)),
        ).performClick()
        compose.mainClock.advanceTimeByFrame()
        assertNotEquals("a touch mid-arrival must act at once, not after it", before, accessReview.currentScreen)
    }
}

/** Wiring a harness cannot sample mid-flight: which layers read the clock, and where the clock lives. */
class OnboardingStepArrivalSourceTest {
    private val source: String by lazy {
        listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("OnboardingFlow.kt not found from ${File(".").absolutePath}")
    }

    @Test
    fun `one arrival clock keyed by the step, born settled on WELCOME and under reduced motion`() {
        assertTrue(source.contains("val stepArrival = remember(step) {"))
        assertTrue(
            source.contains(
                "Animatable(if (arrivalReducedMotion || step == OnboardingStep.WELCOME) 1f else 0f)",
            ),
        )
        // The arriving step and the leaving one ride the swap tier's one clock, so they are complementary frame for frame.
        assertTrue(source.contains("stepArrival.animateTo(1f, mimirCrossSwapSpec(arrivalReducedMotion))"))
        assertTrue(source.contains("departure.animateTo(1f, mimirCrossSwapSpec(arrivalReducedMotion))"))
        // the fade starts on the next frame, with no extra held frame before it.
        val effect = source.substringAfter("LaunchedEffect(stepArrival) {").substringBefore("stepArrival.animateTo(")
        assertFalse(effect.contains("withFrameNanos { }\n"))
        assertTrue(source.contains("(if (leaving) OnboardingStepArrivalSettled else stepArrival.asState()),"))
        assertTrue(source.contains("LocalOnboardingStepHandover provides (if (leaving) leavingHandover else arrivingHandover),"))
        // The screens are chosen per page: the step on screen, which lags the navigation by the cross-fade, and the step
        // the navigation moved to, composed together while they differ.
        assertTrue(source.contains("when (pageStep) {"))
        assertTrue(source.contains("listOf(displayedStep, step)"))
    }

    @Test
    fun `the body and the control slot glide in the layer phase and the header seat never travels`() {
        val column = source.substringAfter("private fun OnboardingScaffoldMeasuredColumn(")
            .substringBefore("private fun OnboardingNaturalBody(")
        assertEquals(
            "exactly the body box and the control slot fade with the handover, each inside graphicsLayer",
            2,
            Regex("""alpha = onboardingStepAlpha\(arrival\.value, handover\.departure\.value\)""")
                .findAll(column).count(),
        )
        assertEquals(
            "and travel on the same clock",
            2,
            Regex("""translationX = onboardingStepTravelPx\(""").findAll(column).count(),
        )
        // The body's clip rides the same layer, so the clipped box travels whole (no fixed cut line).
        val body = column.substringAfter("Spacer(Modifier.height(MimirSpacing.md))").substringBefore("onGloballyPositioned")
        assertTrue(body.contains("clip = true"))
        assertFalse(body.contains(".clip(RectangleShape)"))
        // The slot's layer must not use an offscreen buffer sized to itself: the gold pill's ring reaches past it.
        val slot = column.substringAfter("if (header != null) {").substringAfter("Column(")
            .substringBefore(".testTag(OnboardingControlSlotTag)")
        assertTrue(slot.contains("compositingStrategy = CompositingStrategy.ModulateAlpha"))
        val headerSeat = column.substringAfter("if (header != null) {").substringBefore("Spacer(")
        assertFalse("the header seat must never travel", headerSeat.contains("translationX"))
        assertTrue("it only fades in place", headerSeat.contains("onboardingHeaderSeatAlpha("))
        // The value is never read in composition, which would recompose the whole step every frame.
        assertFalse(Regex("""val \w+ = arrival\.value""").containsMatchIn(source))
    }

    @Test
    fun `FINISH's legend enters after the step has arrived, not over it`() {
        val body = source.substringAfter("private fun OnboardingFinishBody(")
            .substringBefore("private fun OnboardingFinishLegendList(")
        val wait = body.indexOf("snapshotFlow { arrival.value }.first { it >= 1f }")
        val enter = body.indexOf("legendProgress.animateTo(")
        assertTrue(wait >= 0 && enter > wait)
    }
}
