package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingZeroViewportTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `standard A through zero to standard B retains FILES focus and uses B immediately`() {
        val harness = installHarness(StandardA)

        assertFilesPresent()
        assertScaffoldSize(StandardA)
        assertFalse("STANDARD A must not compose a vertical scroll axis", hasVerticalScrollAxis())
        val focusedBeforeZero = harness.authority.state.value.focused
        assertNotNull("the FILES screen must land FocusAuthority focus before the zero measurement", focusedBeforeZero)

        harness.resize(Zero)
        assertFilesPresent()
        assertScaffoldSize(StandardA)

        harness.resize(StandardB)
        assertFilesPresent()
        assertFalse("STANDARD B must not compose a vertical scroll axis", hasVerticalScrollAxis())
        assertEquals(
            "the focused FILES control must survive the held-zero frame",
            focusedBeforeZero,
            harness.authority.state.value.focused,
        )
        assertScaffoldSize(StandardB)
    }

    @Test
    fun `standard A through zero to compact-square B switches posture on B first frame`() {
        val harness = installHarness(StandardA)
        assertFilesPresent()
        assertScaffoldSize(StandardA)
        assertFalse("STANDARD A must not compose a vertical scroll axis", hasVerticalScrollAxis())

        harness.resize(Zero)
        assertFilesPresent()
        assertScaffoldSize(StandardA)
        assertFalse("held STANDARD posture must not flip while measured at zero", hasVerticalScrollAxis())

        harness.resize(CompactSquareB)
        assertFilesPresent()
        assertTrue("COMPACT_SQUARE B must compose its vertical scroll axis immediately", hasVerticalScrollAxis())
        assertScaffoldSize(CompactSquareB)
    }

    @Test
    fun `zero before the first valid measurement renders no scaffold then FILES appears`() {
        val harness = installHarness(Zero)

        compose.onNode(filesPin(), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingScaffoldViewportTag, useUnmergedTree = true).assertDoesNotExist()

        harness.resize(StandardA)
        assertFilesPresent()
        assertScaffoldSize(StandardA)
    }

    @Test
    fun `held zero does not move a nonzero compact focus-reveal scroll position`() {
        // Driven by BODY controls. Since the reserved slot (2026-09-24) the ACCESS pills sit outside the scrolling
        // body and never scroll it, so METADATA's provider actions are the scrolling controls used here.
        val harness = installHarness(CompactScrollA, OnboardingStep.METADATA)
        assertMetadataPresent()
        assertScaffoldSize(CompactScrollA)

        // Move down and back up so the recorded position is demonstrably produced by focus reveal, not by
        // an incidental initial scroll value. At this deliberately short compact viewport, Connect settles
        // between the scroll range's ends: nonzero, but with room for a bad zero-frame reveal to move it.
        compose.runOnIdle { harness.authority.requestFocus(OnboardingSteamGridDbKey) }
        compose.waitForIdle()
        compose.runOnIdle { harness.authority.requestFocus(OnboardingArtworkSetupKey) }
        compose.waitForIdle()
        compose.waitForIdle()
        assertEquals(
            "precondition: focus returned to the METADATA Connect action",
            OnboardingArtworkSetupKey,
            harness.authority.state.value.focused,
        )

        val beforeZero = verticalScrollSnapshot()
        assertTrue("precondition: compact METADATA must have a nonzero scroll range", beforeZero.maxValue > 0f)
        assertTrue("precondition: focus reveal must have scrolled away from the top", beforeZero.value > 0f)
        assertTrue(
            "precondition: the focused position must leave room for a bad zero-frame reveal to move it",
            beforeZero.value < beforeZero.maxValue,
        )

        harness.resize(Zero)
        compose.waitForIdle()
        assertMetadataPresent()
        assertScaffoldSize(CompactScrollA)

        val heldZero = verticalScrollSnapshot()
        assertEquals("held layout must retain the compact scroll range", beforeZero.maxValue, heldZero.maxValue, 0.01f)
        assertEquals(
            "a held zero must not run focus reveal against degenerate outer geometry",
            beforeZero.value,
            heldZero.value,
            0.01f,
        )
    }

    private data class Viewport(val widthDp: Float, val heightDp: Float)

    private data class VerticalScrollSnapshot(val value: Float, val maxValue: Float)

    private class Harness(
        val authority: DefaultFocusAuthority,
        val viewport: MutableState<Viewport>,
        val compose: androidx.compose.ui.test.junit4.ComposeContentTestRule,
    ) {
        fun resize(next: Viewport) {
            compose.runOnIdle { viewport.value = next }
            compose.waitForIdle()
        }
    }

    private fun installHarness(initialViewport: Viewport, step: OnboardingStep = OnboardingStep.ACCESS): Harness {
        val viewport = mutableStateOf(initialViewport)
        val authority = DefaultFocusAuthority()
        val accessReview = OnboardingAccessReviewState().apply {
            goToScreen(OnboardingAccessScreenId.FILES)
        }
        val profile = OnboardingProfileState()
        val library = OnboardingLibraryState()
        val focusIntent = OnboardingFocusIntentState()

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    FixedViewport(viewport.value) {
                        OnboardingFlow(
                            authority = authority,
                            step = step,
                            profile = profile,
                            accessReview = accessReview,
                            focusIntent = focusIntent,
                            access = PendingAccess,
                            library = library,
                            libraryGameCount = 0,
                            libraryPlatformCount = 0,
                            artworkAccount = null,
                            steamGridDbKeyPresent = false,
                            settlement = Settlement,
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
        compose.waitForIdle()
        return Harness(authority, viewport, compose)
    }

    /** Forces the child to receive the exact viewport constraints, including Constraints.fixed(0, 0). */
    @Composable
    private fun FixedViewport(viewport: Viewport, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val widthPx = with(density) { viewport.widthDp.dp.roundToPx() }
        val heightPx = with(density) { viewport.heightDp.dp.roundToPx() }
        Layout(
            content = {
                Box(
                    propagateMinConstraints = true,
                    content = { content() },
                )
            },
        ) { measurables, constraints ->
            val placeable = measurables.single().measure(Constraints.fixed(widthPx, heightPx))
            val layoutWidth = widthPx.coerceIn(constraints.minWidth, constraints.maxWidth)
            val layoutHeight = heightPx.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(layoutWidth, layoutHeight) {
                placeable.placeRelative(0, 0)
            }
        }
    }

    private fun assertFilesPresent() {
        compose.onNode(filesPin(), useUnmergedTree = true).assertExists()
    }

    private fun assertMetadataPresent() {
        compose.onNode(
            hasText(MetadataPin, substring = true) or hasContentDescription(MetadataPin, substring = true),
            useUnmergedTree = true,
        ).assertExists()
    }

    // Chrome text exposes its string as a content description, so match either, as OnboardingScreenFitTest does.
    private fun filesPin() =
        hasText(FilesPin, substring = true) or hasContentDescription(FilesPin, substring = true)

    private fun hasVerticalScrollAxis(): Boolean =
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun verticalScrollSnapshot(): VerticalScrollSnapshot {
        val ranges = compose
            .onAllNodes(hasScrollAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
        assertEquals("the compact scaffold must expose exactly one vertical scroll range", 1, ranges.size)
        val range = ranges.single()
        return VerticalScrollSnapshot(value = range.value(), maxValue = range.maxValue())
    }

    private fun assertScaffoldSize(expected: Viewport) {
        val bounds = compose
            .onNodeWithTag(OnboardingScaffoldViewportTag, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals(expected.widthDp, (bounds.right - bounds.left).value, 0.51f)
        assertEquals(expected.heightDp, (bounds.bottom - bounds.top).value, 0.51f)
    }

    private companion object {
        const val FilesPin = "Let Mimir use your game folders"
        const val MetadataPin = "Add game details and art"

        val StandardA = Viewport(914.3f, 411.4f)
        val StandardB = Viewport(832.5f, 468.3f)
        val CompactSquareB = Viewport(480f, 480f)
        val CompactScrollA = Viewport(300f, 300f)
        val Zero = Viewport(0f, 0f)

        val PendingAccess = OnboardingAccessUi(
            usageAvailable = true,
            usageGranted = false,
            homeRoleAvailable = true,
            homeRoleHeld = false,
            filesGranted = false,
        )

        val Settlement = OnboardingSettlementUi(
            phase = OnboardingSettlementPhase.IDENTIFYING,
            title = "Finishing your library",
            detail = "No artwork is downloaded.",
        )
    }
}
