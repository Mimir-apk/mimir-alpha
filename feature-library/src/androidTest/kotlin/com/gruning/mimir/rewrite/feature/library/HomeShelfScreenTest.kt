package com.gruning.mimir.rewrite.feature.library

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirRunicQuickRailDefaults
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class HomeShelfScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun referenceViewportAtFontScale13KeepsFixedBandsAndLongTitleContained() {
        val longTitle = "A Very Long Localized Game Title That Must Ellipsize Without Moving Any Shelf Band"
        val rows = rows(count = 7).toMutableList().also { rows ->
            rows[0] = rows[0].copy(title = longTitle)
        }
        val authority = DefaultFocusAuthority()

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = rows,
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = {},
                        onQuickAction = {},
                        modifier = Modifier.size(700.dp, 350.dp),
                    )
                }
            }
        }
        compose.waitForIdle()

        val root = bounds(HomeShelfRootTestTag)
        val title = bounds(HomeShelfTitleTestTag)
        val selectedSlot = bounds(HomeShelfSelectedSlotTestTag)
        val hint = bounds(HomeShelfHintToastTestTag)
        val rail = bounds(HomeShelfQuickRailTestTag)
        val titleText = compose.onNodeWithText(longTitle).fetchSemanticsNode().boundsInRoot
        val homeAction = compose.onNodeWithContentDescription("Home").fetchSemanticsNode().boundsInRoot
        val geometry = solveHomeShelfGeometry(700f, 350f)

        assertNear(700f, root.width)
        assertNear(350f, root.height)
        assertNear(geometry.slotBounds(0).left, selectedSlot.left)
        assertNear(geometry.gameRailBand.top, selectedSlot.top)
        assertNear(geometry.heroSide, selectedSlot.width)
        assertNear(geometry.centerAnchor, selectedSlot.center.x)
        assertNear(MimirRunicQuickRailDefaults.ActionSlotWidth.value, homeAction.width)
        assertNear(root.bottom, homeAction.bottom)
        assertTrue(root.containsBounds(title))
        assertTrue(title.containsBounds(titleText))
        assertTrue(root.containsBounds(selectedSlot))
        assertTrue(root.containsBounds(hint))
        assertTrue(root.containsBounds(rail))
        // The current larger-tile solver deliberately treats the corner title as a floating HUD; a long
        // localized title may overlap the art band, but it must remain contained in its own fixed band.
        assertFalse(selectedSlot.overlaps(hint))
        assertFalse(hint.overlaps(rail))
        compose.onNodeWithText(longTitle).assertExists()
        compose.onNodeWithText("1 / 7").assertExists()
        compose.onNodeWithText("Game 1 of 7").assertDoesNotExist()
        HomeShelfQuickActions.forEach { action ->
            compose.onNodeWithContentDescription(action.label).assertExists()
        }
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun statusChipMountsNoAchievementSeatAndTheHeaderHoldsStillAtALargeFont() {
        // the count lives on the launch card only. A status-bearing game used to mount a blank
        // 64 dp seat beside the chip; nothing may mount there now.
        val row = rows(count = 1).single().copy(supportingLabel = "gba")
        val authority = DefaultFocusAuthority()

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = listOf(row),
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = {},
                        onQuickAction = {},
                        launchStatusLines = mapOf(PlatformId("gba") to "Launch · RetroArch"),
                        modifier = Modifier.size(700.dp, 350.dp),
                    )
                }
            }
        }
        compose.waitForIdle()

        val title = compose.onNodeWithText(row.title).fetchSemanticsNode().boundsInRoot
        val status = bounds(HomeShelfStatusTestTag)
        assertTrue(
            "the chip sits to the right of the title with the row's 8 dp gap and nothing between",
            status.left - title.right <= MimirSpacing.sm.value + 1.1f,
        )
    }

    @Test
    fun liveResizeSolvesThorPrimaryAndCompanionWithoutRemeasuringSlotsOnFocus() {
        val rows = rows(count = 7)
        val authority = DefaultFocusAuthority()
        var viewport by mutableStateOf(DpSize(833.dp, 444.dp))

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    Box(Modifier.size(viewport.width, viewport.height)) {
                        HomeShelfScreen(
                            rows = rows,
                            authority = authority,
                            onLaunch = {},
                            onOpenLibrary = {},
                            onQuickAction = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val thorGeometry = solveHomeShelfGeometry(833f, 444f)
        assertNear(thorGeometry.slotBounds(0).left, bounds(HomeShelfSelectedSlotTestTag).left)
        assertNear(thorGeometry.heroSide, bounds(HomeShelfSelectedSlotTestTag).width)

        compose.runOnIdle { viewport = DpSize(538.dp, 444.dp) }
        compose.waitForIdle()
        val companionGeometry = solveHomeShelfGeometry(538f, 444f)
        assertNear(companionGeometry.slotBounds(0).left, bounds(HomeShelfSelectedSlotTestTag).left)
        assertNear(companionGeometry.heroSide, bounds(HomeShelfSelectedSlotTestTag).width)

        val slotBefore = bounds(HomeShelfSelectedSlotTestTag)
        val titleBefore = bounds(HomeShelfTitleTestTag)
        val hintBefore = bounds(HomeShelfHintToastTestTag)
        val railBefore = bounds(HomeShelfQuickRailTestTag)
        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_RIGHT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }
        assertEquals(rows[1].focusKey, authority.state.value.focused)
        compose.waitForIdle()
        assertBoundsNear(slotBefore, bounds(HomeShelfSelectedSlotTestTag))
        assertBoundsNear(titleBefore, bounds(HomeShelfTitleTestTag))
        assertBoundsNear(hintBefore, bounds(HomeShelfHintToastTestTag))
        assertBoundsNear(railBefore, bounds(HomeShelfQuickRailTestTag))
    }

    @Test
    fun emptyStateSeedsControllerFocusAndOpensLibrary() {
        val authority = DefaultFocusAuthority()
        var opened = false

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = emptyList(),
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = { opened = true },
                        onQuickAction = {},
                        modifier = Modifier.size(538.dp, 444.dp),
                    )
                }
            }
        }
        compose.waitForIdle()

        assertEquals(HomeShelfOpenLibraryKey, authority.state.value.focused)
        compose.onNodeWithTag(HomeShelfEmptyActionTestTag).assertExists().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun unresolvedExactReturnNeverSeedsOrPresentsItemZero() {
        val first = rows(count = 1).single().copy(title = "Wrong first row")
        val exact = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("exact-return")),
            rank = 1_024L,
            addedAtMillis = 2L,
            title = "Exact return row",
        )
        val authority = DefaultFocusAuthority()
        var renderedRows by mutableStateOf(listOf(first))
        var restorationFocusKey by mutableStateOf<FocusKey?>(exact.focusKey)

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = renderedRows,
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = {},
                        onQuickAction = {},
                        restorationFocusKey = restorationFocusKey,
                        modifier = Modifier.size(538.dp, 444.dp),
                    )
                }
            }
        }
        compose.waitForIdle()

        assertEquals(null, authority.state.value.focused)
        compose.onNodeWithText(first.title).assertDoesNotExist()
        compose.onNodeWithTag(homeShelfTileTestTag(first)).assertDoesNotExist()
        compose.onNodeWithTag(HomeShelfSelectedSlotTestTag).assertDoesNotExist()

        compose.runOnIdle { renderedRows = listOf(first, exact) }
        compose.waitForIdle()

        assertEquals(exact.focusKey, authority.state.value.focused)
        compose.onNodeWithText(first.title).assertDoesNotExist()
        compose.onNodeWithText(exact.title).assertDoesNotExist()
        compose.onNodeWithTag(HomeShelfSelectedSlotTestTag).assertDoesNotExist()

        compose.runOnIdle { restorationFocusKey = null }
        compose.waitForIdle()

        assertEquals(exact.focusKey, authority.state.value.focused)
        compose.onNodeWithText(exact.title).assertExists()
        compose.onNodeWithTag(HomeShelfSelectedSlotTestTag).assertExists()
    }

    @Test
    fun distantLogicalJumpCommitsDestinationWithoutRetainingOldArt() {
        val rows = rows(count = 50)
        val authority = DefaultFocusAuthority()
        compose.mainClock.autoAdvance = false

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = rows,
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = {},
                        onQuickAction = {},
                        modifier = Modifier.size(700.dp, 350.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val anchorBefore = bounds(HomeShelfSelectedSlotTestTag)

        compose.runOnIdle {
            authority.requestFocus(rows[37].focusKey)
            assertEquals(rows[37].focusKey, authority.state.value.focused)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertBoundsNear(anchorBefore, bounds(HomeShelfSelectedSlotTestTag))
        compose.onNodeWithText(rows[37].title).assertExists()
        compose.onNodeWithText(rows[0].title).assertDoesNotExist()
        compose.onNodeWithTag(homeShelfOutgoingArtTestTag(rows[0])).assertDoesNotExist()

        compose.mainClock.advanceTimeBy(HomeShelfOutgoingFadeMs.toLong() + 32L)
        compose.waitForIdle()
        compose.onNodeWithTag(homeShelfOutgoingArtTestTag(rows[0])).assertDoesNotExist()
    }

    @Test
    fun reducedMotionToggleAfterSettledHandoffDoesNotReplayOutgoingMedia() {
        val rows = rows(count = 7)
        val authority = DefaultFocusAuthority()
        var reducedMotion by mutableStateOf(false)

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = rows,
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = {},
                        onQuickAction = {},
                        reducedMotion = reducedMotion,
                        modifier = Modifier.size(538.dp, 444.dp),
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_RIGHT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }
        compose.waitForIdle()
        val outgoingTag = homeShelfOutgoingArtTestTag(rows[0])
        compose.onNodeWithTag(outgoingTag).assertDoesNotExist()

        compose.runOnIdle { reducedMotion = true }
        compose.waitForIdle()
        compose.onNodeWithTag(outgoingTag).assertDoesNotExist()

        compose.runOnIdle { reducedMotion = false }
        compose.waitForIdle()
        compose.onNodeWithTag(outgoingTag).assertDoesNotExist()
    }

    @Test
    fun squareNoArtSquareAndDecodeErrorNeverRetainPriorCenterPixels() {
        val redArt = squareArtFile("transition-red", AndroidColor.RED)
        val blueArt = squareArtFile("transition-blue", AndroidColor.BLUE)
        val rows = rows(count = 7).toMutableList().also {
            it[0] = it[0].copy(title = "Red art", tileIconUri = redArt)
            it[1] = it[1].copy(title = "No art", artworkUri = null, tileIconUri = null)
            it[2] = it[2].copy(title = "Blue art", tileIconUri = blueArt)
            it[3] = it[3].copy(title = "Decode error", tileIconUri = "file:///missing-transition-art.png")
        }
        val authority = DefaultFocusAuthority()
        val geometry = solveHomeShelfGeometry(538f, 444f)

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = rows,
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = {},
                        onQuickAction = {},
                        modifier = Modifier.size(538.dp, 444.dp),
                    )
                }
            }
        }
        compose.waitUntil(3_000L) { centerPixel(geometry).isDominantRed() }

        moveHomeRight(authority, rows)
        compose.waitForIdle()
        assertFalse("red art persisted under the no-art destination", centerPixel(geometry).isDominantRed())
        compose.onAllNodesWithTag(homeShelfTileTestTag(rows[0])).assertCountEquals(1)
        compose.onAllNodesWithTag(homeShelfTileTestTag(rows[1])).assertCountEquals(1)

        moveHomeRight(authority, rows)
        compose.waitUntil(3_000L) { centerPixel(geometry).isDominantBlue() }
        assertFalse("red art survived the A→B→C traversal", centerPixel(geometry).isDominantRed())

        moveHomeRight(authority, rows)
        compose.waitForIdle()
        assertFalse("blue art persisted under the decode-error fallback", centerPixel(geometry).isDominantBlue())
        compose.onNodeWithTag(homeShelfOutgoingArtTestTag(rows[2])).assertDoesNotExist()
        compose.onAllNodesWithTag(homeShelfTileTestTag(rows[3])).assertCountEquals(1)
    }

    @Test
    fun focusMotionKeepsOneBodyPerIdentityThroughRapidReversal() {
        val rows = rows(count = 7)
        val authority = DefaultFocusAuthority()
        compose.mainClock.autoAdvance = false

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = rows,
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = {},
                        onQuickAction = {},
                        modifier = Modifier.size(538.dp, 444.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_RIGHT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
            assertEquals("logical focus must change before motion", rows[1].focusKey, authority.state.value.focused)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val firstOutgoingTag = homeShelfOutgoingArtTestTag(rows[0])
        compose.onNodeWithTag(firstOutgoingTag).assertDoesNotExist()
        compose.onNodeWithText(rows[1].title).assertExists()
        compose.onNodeWithText(rows[0].title).assertDoesNotExist()
        compose.onAllNodesWithTag(homeShelfTileTestTag(rows[0])).assertCountEquals(1)
        compose.onAllNodesWithTag(homeShelfTileTestTag(rows[1])).assertCountEquals(1)
        val outgoingPresentation = compose.onNodeWithTag(homeShelfTileTestTag(rows[0])).fetchSemanticsNode().config
        assertTrue(
            "outgoing hero alpha must snap down on the focus commit",
            outgoingPresentation[HomeShelfPresentationAlphaSemantics] <= 0.83f,
        )
        assertTrue(
            "outgoing hero scale must snap down on the focus commit",
            outgoingPresentation[HomeShelfPresentationScaleSemantics] <= 1.01f,
        )
        assertFalse(bounds(homeShelfTileTestTag(rows[0])).overlaps(bounds(homeShelfTileTestTag(rows[1]))))

        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_LEFT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
            assertEquals("reversal must also change focus immediately", rows[0].focusKey, authority.state.value.focused)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        compose.onNodeWithTag(firstOutgoingTag).assertDoesNotExist()
        val replacementTag = homeShelfOutgoingArtTestTag(rows[1])
        compose.onNodeWithTag(replacementTag).assertDoesNotExist()
        compose.onNodeWithText(rows[0].title).assertExists()
        compose.onNodeWithText(rows[1].title).assertDoesNotExist()
        compose.onAllNodesWithTag(homeShelfTileTestTag(rows[0])).assertCountEquals(1)
        compose.onAllNodesWithTag(homeShelfTileTestTag(rows[1])).assertCountEquals(1)
        assertFalse(bounds(homeShelfTileTestTag(rows[0])).overlaps(bounds(homeShelfTileTestTag(rows[1]))))

        compose.mainClock.advanceTimeBy(HomeShelfOutgoingFadeMs.toLong() + 32L)
        compose.waitForIdle()
        compose.onNodeWithTag(replacementTag).assertDoesNotExist()
    }

    @Test
    fun windowedRingMovesPreMountedAlphaZeroBuffersThroughBothPaintedEdges() {
        val rows = rows(count = 8)
        val authority = DefaultFocusAuthority()
        compose.mainClock.autoAdvance = false

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = rows,
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = {},
                        onQuickAction = {},
                        modifier = Modifier.size(538.dp, 444.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { authority.requestFocus(rows[4].focusKey) }
        compose.mainClock.advanceTimeBy(HomeShelfOutgoingFadeMs.toLong() + 64L)

        // The next right-edge identity is already mounted at +3 and cannot be initialized on top of
        // row 6's painted +2 seat after the logical focus commit.
        assertNear(2f, visualSlot(rows[6]))
        assertNear(3f, visualSlot(rows[7]))
        assertTrue(presentationAlpha(rows[7]) <= 0.001f)
        compose.onAllNodesWithTag(homeShelfTileTestTag(rows[6])).assertCountEquals(1)
        compose.onAllNodesWithTag(homeShelfTileTestTag(rows[7])).assertCountEquals(1)

        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_RIGHT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }
        val rightEdge = mutableListOf<Pair<Float, Float>>()
        listOf(16L, 54L, 70L, 70L).forEach { delta ->
            compose.mainClock.advanceTimeBy(delta)
            val row6Slot = visualSlot(rows[6])
            val row7Slot = visualSlot(rows[7])
            rightEdge += row6Slot to row7Slot
            assertTrue("incoming right edge must remain one seat outside its neighbour", row7Slot > row6Slot)
            assertNear(1f, row7Slot - row6Slot, tolerance = 0.06f)
            assertTrue("new outer buffer must stay non-painting", presentationAlpha(rows[0]) <= 0.001f)
            compose.onAllNodesWithTag(homeShelfTileTestTag(rows[6])).assertCountEquals(1)
            compose.onAllNodesWithTag(homeShelfTileTestTag(rows[7])).assertCountEquals(1)
        }
        assertTrue(rightEdge.zipWithNext().all { (a, b) -> b.first <= a.first && b.second <= a.second })

        compose.mainClock.advanceTimeBy(HomeShelfOutgoingFadeMs.toLong() + 64L)
        assertNear(-3f, visualSlot(rows[2]))
        assertNear(-2f, visualSlot(rows[3]))
        assertTrue(presentationAlpha(rows[2]) <= 0.001f)

        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_LEFT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }
        val leftEdge = mutableListOf<Pair<Float, Float>>()
        listOf(16L, 54L, 70L, 70L).forEach { delta ->
            compose.mainClock.advanceTimeBy(delta)
            val row2Slot = visualSlot(rows[2])
            val row3Slot = visualSlot(rows[3])
            leftEdge += row2Slot to row3Slot
            assertTrue("incoming left edge must remain one seat outside its neighbour", row2Slot < row3Slot)
            assertNear(1f, row3Slot - row2Slot, tolerance = 0.06f)
            assertTrue("new outer buffer must stay non-painting", presentationAlpha(rows[1]) <= 0.001f)
        }
        assertTrue(leftEdge.zipWithNext().all { (a, b) -> b.first >= a.first && b.second >= a.second })
    }

    @Test
    fun completeEvenRingStartsBodyMotionWithoutASeamPreparationHoldAndReducedMotionSnaps() {
        val rows = rows(count = 6)
        val authority = DefaultFocusAuthority()
        var reducedMotion by mutableStateOf(false)
        compose.mainClock.autoAdvance = false

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = rows,
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = {},
                        onQuickAction = {},
                        reducedMotion = reducedMotion,
                        modifier = Modifier.size(538.dp, 444.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { authority.requestFocus(rows[0].focusKey) }
        compose.mainClock.advanceTimeBy(HomeShelfOutgoingFadeMs.toLong() + 64L)
        assertNear(1f, visualSlot(rows[1]), tolerance = 0.02f)

        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_RIGHT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }
        // First frame commits the new target; on the next painted frame the shared centre must already
        // be advancing. The old generic even-seam pre-fade held this value at +1 for 32 ms.
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val firstMovingSlot = visualSlot(rows[1])
        assertTrue(
            "destination body must move before it brightens in the old side seat: $firstMovingSlot",
            firstMovingSlot < 0.98f,
        )

        compose.mainClock.advanceTimeBy(HomeShelfOutgoingFadeMs.toLong() + 64L)
        compose.runOnIdle {
            reducedMotion = true
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_LEFT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }
        compose.mainClock.advanceTimeByFrame()
        assertNear(0f, visualSlot(rows[0]), tolerance = 0.001f)

        // Re-enable before the old 210 ms interval could have completed. No latent controller is
        // allowed to pull the snapped ring back toward row 1 and replay the canceled glide.
        compose.runOnIdle { reducedMotion = false }
        compose.mainClock.advanceTimeByFrame()
        assertNear(0f, visualSlot(rows[0]), tolerance = 0.001f)
        assertNear(1f, visualSlot(rows[1]), tolerance = 0.001f)
    }

    @Test
    fun focusRingRemainsAtTheHeroAnchorForEveryMotionAndReversalFrame() {
        val rows = rows(count = 7)
        val authority = DefaultFocusAuthority()
        compose.mainClock.autoAdvance = false

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = rows,
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = {},
                        onQuickAction = {},
                        modifier = Modifier.size(538.dp, 444.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { authority.requestFocus(rows[0].focusKey) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(300L)
        val fixedRing = bounds(HomeShelfStationaryRingTestTag)

        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_RIGHT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }
        listOf(16L, 54L, 70L, 70L).forEach { delta ->
            compose.mainClock.advanceTimeBy(delta)
            assertBoundsNear(fixedRing, bounds(HomeShelfStationaryRingTestTag))
            compose.onAllNodesWithTag(HomeShelfStationaryRingTestTag).assertCountEquals(1)
            rows.take(2).forEach { row ->
                compose.onAllNodesWithTag(homeShelfTileTestTag(row)).assertCountEquals(1)
            }
        }

        // Reverse while the bodies are still in flight. The chrome remains fixed and neither identity gains
        // an outgoing copy; the existing keyed bodies simply retarget from their currently painted position.
        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_LEFT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }
        repeat(4) {
            compose.mainClock.advanceTimeBy(70L)
            assertBoundsNear(fixedRing, bounds(HomeShelfStationaryRingTestTag))
            compose.onAllNodesWithTag(HomeShelfStationaryRingTestTag).assertCountEquals(1)
            compose.onNodeWithTag(homeShelfOutgoingArtTestTag(rows[0])).assertDoesNotExist()
            compose.onNodeWithTag(homeShelfOutgoingArtTestTag(rows[1])).assertDoesNotExist()
        }
    }

    @Test
    fun twoItemFixedLayoutMovesOneRingToTheSelectedTileWithoutSwappingBodies() {
        val square = squareArtFile("two-item-square", AndroidColor.RED)
        val rows = rows(count = 2).let { source ->
            listOf(
                source[0].copy(title = "Square", tileIconUri = square),
                source[1].copy(title = "No art", tileIconUri = null, artworkUri = null),
            )
        }
        val authority = DefaultFocusAuthority()
        compose.mainClock.autoAdvance = false

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MimirTheme {
                    HomeShelfScreen(
                        rows = rows,
                        authority = authority,
                        onLaunch = {},
                        onOpenLibrary = {},
                        onQuickAction = {},
                        modifier = Modifier.size(538.dp, 444.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { authority.requestFocus(rows[0].focusKey) }
        compose.mainClock.advanceTimeBy(300L)
        assertTrue(bounds(HomeShelfStationaryRingTestTag).overlaps(bounds(HomeShelfSelectedSlotTestTag)))

        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_RIGHT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }
        val rightwardCenters = mutableListOf<Float>()
        repeat(4) {
            compose.mainClock.advanceTimeBy(70L)
            rightwardCenters += bounds(HomeShelfStationaryRingTestTag).center.x
            rows.forEach { row ->
                compose.onAllNodesWithTag(homeShelfTileTestTag(row)).assertCountEquals(1)
            }
            compose.onAllNodesWithTag(HomeShelfStationaryRingTestTag).assertCountEquals(1)
        }
        assertTrue(rightwardCenters.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(bounds(HomeShelfStationaryRingTestTag).overlaps(bounds(HomeShelfSelectedSlotTestTag)))

        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_LEFT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }
        val leftwardCenters = mutableListOf<Float>()
        repeat(4) {
            compose.mainClock.advanceTimeBy(70L)
            leftwardCenters += bounds(HomeShelfStationaryRingTestTag).center.x
        }
        assertTrue(leftwardCenters.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(bounds(HomeShelfStationaryRingTestTag).overlaps(bounds(HomeShelfSelectedSlotTestTag)))
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun visualSlot(row: HomeShelfRow): Float =
        compose.onNodeWithTag(homeShelfTileTestTag(row))
            .fetchSemanticsNode().config[HomeShelfVisualSlotSemantics]

    private fun presentationAlpha(row: HomeShelfRow): Float =
        compose.onNodeWithTag(homeShelfTileTestTag(row))
            .fetchSemanticsNode().config[HomeShelfPresentationAlphaSemantics]

    private fun rows(count: Int): List<HomeShelfRow> = List(count) { index ->
        HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("game-$index")),
            rank = index * 1_024L,
            addedAtMillis = index.toLong(),
            title = "Game $index",
        )
    }

    private fun moveHomeRight(authority: DefaultFocusAuthority, rows: List<HomeShelfRow>) {
        compose.runOnIdle {
            dispatchToHomeShelf(
                authority = authority,
                rows = rows,
                action = ControllerAction.NAV_RIGHT,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }
        compose.waitForIdle()
    }

    private fun squareArtFile(name: String, color: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "$name.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file).toString()
    }

    private fun centerPixel(geometry: HomeShelfGeometry): androidx.compose.ui.graphics.Color {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val x = geometry.centerAnchor.roundToInt().coerceIn(0, pixels.width - 1)
        val y = (geometry.gameRailBand.top + geometry.heroSide / 2f)
            .roundToInt()
            .coerceIn(0, pixels.height - 1)
        return pixels[x, y]
    }

    private fun androidx.compose.ui.graphics.Color.isDominantRed(): Boolean =
        red > 0.75f && green < 0.25f && blue < 0.25f

    private fun androidx.compose.ui.graphics.Color.isDominantBlue(): Boolean =
        blue > 0.75f && red < 0.25f && green < 0.25f

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 1.1f) {
        assertTrue("expected $expected, actual $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    private fun assertBoundsNear(expected: Rect, actual: Rect) {
        assertNear(expected.left, actual.left)
        assertNear(expected.top, actual.top)
        assertNear(expected.right, actual.right)
        assertNear(expected.bottom, actual.bottom)
    }

    private fun Rect.containsBounds(other: Rect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}
