package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTomeFolioModelsTest {
    private val ids = (0 until 20).map { HomeShelfItemId.Emulated(GameId("gba/game-$it")) }
    private val testTome = HomeShelfTome(
        TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000099")),
        "Carry Pose",
        emptyList(),
        ids.size,
    )

    private fun folioStateFromArrange(arrange: HomeTomeArrangeState): HomeTomeFolioUiState {
        val members = arrange.workingSlots.mapIndexedNotNull { index, itemId ->
            itemId?.let { HomeTomeMember(it, "Game $index") }
        }
        val memberSlots = arrange.workingSlots.map { itemId ->
            itemId?.let { id -> members.first { it.itemId == id } }
        }
        return HomeTomeFolioUiState(
            tome = testTome,
            members = members,
            memberSlots = memberSlots,
            focusedIndex = arrange.focusedIndex,
            mode = HomeTomeFolioMode.ARRANGE,
            transition = arrange.pageTransition,
            carriedItemId = arrange.carriedItemId,
            insertionIndex = arrange.focusedIndex.takeIf { arrange.carriedItemId != null },
            arrangeTransactionId = arrange.activeTransactionId,
            nextArrangeTransactionId = arrange.nextTransactionId,
        )
    }

    @Test
    fun compactFolioHeaderAndFooterHaveDisjointPaintSeats() {
        val layout = solveHomeTomeFolioHorizontalPartitions(
            width = 288f,
            outer = 12.96f,
            emblemWidth = 36f,
            titleInset = 8f,
            headerGap = 12f,
            footerGap = 8f,
            requestedCloseWidth = 40f,
        )

        assertTrue(layout.titleLeft + layout.titleWidth + 12f <= layout.metadataLeft)
        assertTrue(layout.metadataLeft + layout.metadataWidth <= 288f - 12.96f + 0.001f)
        assertTrue(layout.metadataWidth >= 288f * 0.40f)
        assertTrue(layout.footerLegendLeft + layout.footerLegendWidth + 8f <= layout.closeLeft)
        assertTrue(layout.closeLeft + layout.closeWidth <= 288f - 12.96f + 0.001f)
    }

    @Test
    fun verticalRailsStayDisjointOnCertifiedHeightsAndLargeText() {
        listOf(315f, 399.6f).forEach { panelHeight ->
            listOf(1f, 1.3f).forEach { fontScale ->
                val layout = solveHomeTomeFolioVerticalPartitions(panelHeight, fontScale)

                assertTrue(
                    layout.headerTop + layout.titleLineHeight + 2f + layout.supportLineHeight <=
                        layout.headerHeight + 0.001f,
                )
                assertTrue(layout.gridTop >= layout.headerHeight + 4f - 0.001f)
                assertTrue(layout.gridBottom <= layout.continuationTop + 0.001f)
                assertTrue(layout.continuationTop + 16f <= layout.footerTop + 0.001f)
                assertTrue(layout.footerTop + layout.footerHeight <= panelHeight + 0.001f)
            }
        }
    }

    @Test
    fun tomeMembersRejectAndroidApps() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeTomeMember(
                itemId = HomeShelfItemId.AndroidApp(AndroidPackageName("com.example.utility")),
                title = "Utility",
            )
        }
    }

    @Test
    fun tomeFaceIdentityStaysKindAware() {
        // The count copy ("1 game", "4 games", "Empty") is string resources now: HomeShelfTextTest pins it in both languages.
        assertEquals(1, nextHomeTomePreviewIndex(0, 4))
        assertEquals(0, nextHomeTomePreviewIndex(3, 4))
        assertEquals(0, nextHomeTomePreviewIndex(8, 1))
        assertEquals(MimirMotion.OverlayMs, HomeTomePreviewFadeMs)
    }

    // The folio legends are string resources now; their English and Spanish wording is pinned in HomeFooterHintsTextTest.

    @Test
    fun emptyFolioOwnsOneControllerFocusSubjectAndConfirmReturnsBack() {
        val tome = HomeShelfTome(
            TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000043")),
            "Empty",
            emptyList(),
            0,
        )
        val state = HomeTomeFolioUiState(tome = tome, members = emptyList())

        assertEquals(listOf(HomeTomeEmptyFocusKey), homeTomeVirtualFocusSlots(emptyList()).map { it.key })
        assertEquals(HomeTomeFolioActionRequest.Back, routeHomeTomeFolioAction(state, ControllerAction.CONFIRM))
    }

    @Test
    fun travelTouchMappingResolvesSeatsAcrossTheWindowAndGapsResolveNull() {
        val page = HomeTomeFolioPageSize
        val cell = 75f
        val gap = 10f
        assertEquals(0, homeTomeFolioSeatIndexAtTravel(37.5f, 70f, 0, page, cell, cell, gap, gap))
        assertEquals(3, homeTomeFolioSeatIndexAtTravel(300f, 50f, 0, page, cell, cell, gap, gap))
        assertEquals(17, homeTomeFolioSeatIndexAtTravel(462.5f, 207.5f, 0, page, cell, cell, gap, gap))
        // Continuous sheet: a traveled window over global rows 4.. maps seat 27 without pageIndex.
        assertEquals(
            27,
            homeTomeFolioSeatIndexAtTravel(
                37.5f,
                160f,
                3f,
                page * 2,
                cell,
                cell,
                gap,
                gap,
                visibleColumns = 3,
                rowWindowStart = 3,
                visibleRows = 3,
            ),
        )
        assertEquals(
            23,
            homeTomeFolioSeatIndexAtTravel(
                207.5f,
                37.5f,
                3f,
                page * 2,
                cell,
                cell,
                gap,
                gap,
                visibleColumns = 3,
                rowWindowStart = 3,
                visibleRows = 3,
            ),
        )
        // Inter-seat gaps and positions beyond the six visible columns resolve to null.
        assertNull(homeTomeFolioSeatIndexAtTravel(80f, 50f, 0, page, cell, cell, gap, gap))
        assertNull(homeTomeFolioSeatIndexAtTravel(37.5f, 80f, 0, page, cell, cell, gap, gap))
        assertNull(homeTomeFolioSeatIndexAtTravel(520f, 50f, 0, page, cell, cell, gap, gap))
        // A seat address past the padded seat count never resolves.
        assertNull(homeTomeFolioSeatIndexAtTravel(462.5f, 207.5f, 0, 7, cell, cell, gap, gap))
        // While the strip paints halfway between columns, hit-testing follows that exact transform.
        assertEquals(
            3,
            homeTomeFolioSeatIndexAtTravel(
                42.5f,
                37.5f,
                2.5f,
                page * 2,
                cell,
                cell,
                gap,
                gap,
                visibleColumns = 3,
            ),
        )
    }

    @Test
    fun gapReleaseResolvesTheNearestExplicitWellAndOutsideCancels() {
        val page = HomeTomeFolioPageSize
        val cell = 75f
        val gap = 10f
        assertEquals(0, homeTomeFolioNearestSeatAtTravel(80f, 37.5f, 0, page, cell, cell, gap, gap))
        assertEquals(3, homeTomeFolioNearestSeatAtTravel(290f, 37.5f, 0, page, cell, cell, gap, gap))
        // Continuous sheet release onto global row 3 / column 3 (seat 21) under the pointer.
        assertEquals(
            21,
            homeTomeFolioNearestSeatAtTravel(
                37.5f,
                37.5f,
                3f,
                page * 2,
                cell,
                cell,
                gap,
                gap,
                visibleColumns = 3,
                rowWindowStart = 3,
                visibleRows = 3,
            ),
        )
        assertNull(homeTomeFolioNearestSeatAtTravel(-1f, 37.5f, 0, page, cell, cell, gap, gap))
        assertEquals(
            3,
            homeTomeFolioNearestSeatAtTravel(
                42.5f,
                37.5f,
                2.5f,
                page * 2,
                cell,
                cell,
                gap,
                gap,
                visibleColumns = 3,
            ),
        )
        assertEquals(120L, HomeTomeTouchDropSettleMs)
    }

    @Test
    fun carriedGameCanOccupyAndCommitAnEmptyPaintedWell() {
        val lifted = reduceHomeTomeArrange(beginHomeTomeArrange(ids.take(4), 0), ControllerAction.CONFIRM).state
        val moved = retargetHomeTomeArrange(lifted, 17).state

        assertNull(moved.workingSlots[0])
        assertEquals(ids[0], moved.workingSlots[17])
        assertEquals(17, moved.focusedIndex)

        val dropped = reduceHomeTomeArrange(moved, ControllerAction.CONFIRM)
        assertEquals(moved.workingSlots, dropped.committedSlots)
        assertEquals(listOf(ids[1], ids[2], ids[3], ids[0]), dropped.committedOrder)
    }

    @Test
    fun arrangeStagesMovementAndOnlyADropProducesDurableOrder() {
        var update = reduceHomeTomeArrange(beginHomeTomeArrange(ids.take(8), 0), ControllerAction.CONFIRM)
        assertEquals(ids[0], update.state.carriedItemId)
        assertNull(update.committedOrder)

        update = reduceHomeTomeArrange(update.state, ControllerAction.NAV_RIGHT)
        assertEquals(ids[0], update.state.workingOrder[1])
        assertNull(update.committedOrder)

        update = reduceHomeTomeArrange(update.state, ControllerAction.CONFIRM)
        assertEquals(update.state.workingOrder, update.committedOrder)
        assertNull(update.state.carriedItemId)
    }

    @Test
    fun browseNavigationCrossesLeafBoundariesVerticallyInTheSameColumn() {
        val page = HomeTomeFolioPageSize
        // Horizontal movement is finite inside the current leaf.
        assertEquals(5, navigateHomeTomeFolio(5, 36, ControllerAction.NAV_RIGHT).targetIndex)
        assertEquals(page, navigateHomeTomeFolio(page, 36, ControllerAction.NAV_LEFT).targetIndex)

        // DOWN from the bottom row enters the next page in the same column.
        val down = navigateHomeTomeFolio(
            currentIndex = 17,
            itemCount = 36,
            action = ControllerAction.NAV_DOWN,
            transition = HomeTomePageTransition(settledPage = 0),
        )
        assertEquals(23, down.targetIndex)
        assertEquals(1, down.targetPage)
        assertEquals(0, down.transition.outgoingPage)
        assertEquals(17, navigateHomeTomeFolio(23, 36, ControllerAction.NAV_UP).targetIndex)

        // Within a page, Up/Down retain the ordinary six-seat row step.
        assertEquals(6, navigateHomeTomeFolio(12, 20, ControllerAction.NAV_UP).targetIndex)
        assertEquals(0, navigateHomeTomeFolio(6, 20, ControllerAction.NAV_UP).targetIndex)
        assertEquals(12, navigateHomeTomeFolio(6, 20, ControllerAction.NAV_DOWN).targetIndex)
        assertEquals(18, navigateHomeTomeFolio(12, 20, ControllerAction.NAV_DOWN).targetIndex)
        assertEquals(17, navigateHomeTomeFolio(11, 20, ControllerAction.NAV_DOWN).targetIndex)
        assertEquals(24, navigateHomeTomeFolio(18, 36, ControllerAction.NAV_DOWN).targetIndex)
    }

    @Test
    fun travelWindowMatchesHomeShelfLawAcrossOneTwoFourAndSixColumns() {
        val page = HomeTomeFolioPageSize
        val pageColumns = homeTomeFolioColumnCount(page * 2)
        val lastFocusedColumn = homeTomeFolioColumnOfIndex(page * 2 - 1)
        // Six visible: every page fits and vertical pagination never widens the strip.
        (0..5).forEach { column ->
            assertEquals(0, homeTomeFolioTravelStart(4, column, pageColumns, 6))
        }
        assertEquals(0, homeTomeFolioTravelStart(5, lastFocusedColumn, pageColumns, 6))
        assertEquals(0, homeTomeFolioTravelStart(4, 5, 6, 6))

        // Four visible (lead still 2).
        assertEquals(0, homeTomeFolioTravelStart(0, 2, pageColumns, 4))
        assertEquals(1, homeTomeFolioTravelStart(0, 3, pageColumns, 4))
        assertEquals(1, homeTomeFolioTravelStart(2, 2, pageColumns, 4))
        assertEquals(2, homeTomeFolioTravelStart(1, lastFocusedColumn, pageColumns, 4))

        // Two visible (lead shrinks to 1).
        assertEquals(0, homeTomeFolioTravelStart(0, 1, pageColumns, 2))
        assertEquals(1, homeTomeFolioTravelStart(0, 2, pageColumns, 2))
        assertEquals(0, homeTomeFolioTravelStart(1, 1, pageColumns, 2))
        assertEquals(4, homeTomeFolioTravelStart(3, lastFocusedColumn, pageColumns, 2))

        // One visible anchors start to the focused column, so the target is always mounted.
        assertEquals(0, homeTomeFolioTravelStart(0, 0, pageColumns, 1))
        assertEquals(5, homeTomeFolioTravelStart(0, 5, pageColumns, 1))
        assertEquals(3, homeTomeFolioTravelStart(5, 3, pageColumns, 1))
        assertEquals(lastFocusedColumn, homeTomeFolioTravelStart(lastFocusedColumn, lastFocusedColumn, pageColumns, 1))

        // Burst folding: a held sweep lands the same window as the final focus alone.
        var burst = 0
        (0..5).forEach { column -> burst = homeTomeFolioTravelStart(burst, column, pageColumns, 4) }
        assertEquals(homeTomeFolioTravelStart(0, 5, pageColumns, 4), burst)
        var reverse = burst
        (5 downTo 0).forEach { column -> reverse = homeTomeFolioTravelStart(reverse, column, pageColumns, 4) }
        assertEquals(0, reverse)

        assertEquals(5, homeTomeFolioColumnOfIndex(17))
        assertEquals(0, homeTomeFolioColumnOfIndex(page))
        assertEquals(2, homeTomeFolioRowOfIndex(17))
        assertEquals(24, homeTomeFolioIndexOf(0, 1, pageIndex = 1))
        assertEquals(HomeTomeFolioColumns, pageColumns)
        assertEquals(18, HomeTomeFolioPageSize)
        assertEquals(1, homeTomeFolioVerticalPageDirection(HomeTomePageTransition(1, outgoingPage = 0)))
        assertEquals(-1, homeTomeFolioVerticalPageDirection(HomeTomePageTransition(0, outgoingPage = 1)))
        assertEquals(0, homeTomeFolioVerticalPageDirection(HomeTomePageTransition(0)))
        assertTrue(homeTomeFolioPageMotionSettled(false, 0f, false))
        assertFalse(homeTomeFolioPageMotionSettled(false, 0.01f, false))
    }

    @Test
    fun touchPickupStationaryFingerArmsExactlyAtThresholdNotBefore() {
        val slop = 10f
        assertEquals(
            HomeTomeTouchPickup.PENDING,
            homeTomeTouchPickupDecision(
                elapsedMs = HomeTomeTouchPickupMs - 1L,
                distancePx = 0f,
                touchSlopPx = slop,
                pressedPointerCount = 1,
                stillPressed = true,
            ),
        )
        assertEquals(
            HomeTomeTouchPickup.ARMED,
            homeTomeTouchPickupDecision(
                elapsedMs = HomeTomeTouchPickupMs,
                distancePx = 0f,
                touchSlopPx = slop,
                pressedPointerCount = 1,
                stillPressed = true,
            ),
        )
    }

    @Test
    fun touchPickupArmsIdenticallyInBrowseAndArrangeAfterStationaryHold() {
        val slop = 10f
        assertEquals(
            HomeTomeTouchPickup.ARMED,
            homeTomeTouchPickupDecision(
                elapsedMs = HomeTomeTouchPickupMs,
                distancePx = 0f,
                touchSlopPx = slop,
                pressedPointerCount = 1,
                stillPressed = true,
            ),
        )
        assertEquals(
            HomeTomeTouchPickup.ARMED,
            homeTomeTouchPickupDecision(
                elapsedMs = HomeTomeTouchPickupMs + 20L,
                distancePx = slop - 1f,
                touchSlopPx = slop,
                pressedPointerCount = 1,
                stillPressed = true,
            ),
        )
    }

    @Test
    fun touchPickupCedesOrdinaryScrollUntilDeliberateLongPressOwnsTheGesture() {
        val slop = 10f
        assertEquals(
            HomeTomeTouchPickup.PENDING,
            homeTomeTouchPickupDecision(
                elapsedMs = 100L,
                distancePx = slop - 0.01f,
                touchSlopPx = slop,
                pressedPointerCount = 1,
                stillPressed = true,
            ),
        )
        assertEquals(
            HomeTomeTouchPickup.REJECTED,
            homeTomeTouchPickupDecision(
                elapsedMs = 100L,
                distancePx = slop,
                touchSlopPx = slop,
                pressedPointerCount = 1,
                stillPressed = true,
            ),
        )
        assertEquals(
            HomeTomeTouchPickup.REJECTED,
            homeTomeTouchPickupDecision(
                elapsedMs = HomeTomeTouchPickupMs - 1L,
                distancePx = slop + 1f,
                touchSlopPx = slop,
                pressedPointerCount = 1,
                stillPressed = true,
            ),
        )
        // Once the deliberate hold owns the pointer, subsequent movement belongs to drag.
        assertEquals(
            HomeTomeTouchPickup.ARMED,
            homeTomeTouchPickupDecision(
                elapsedMs = HomeTomeTouchPickupMs,
                distancePx = slop + 1f,
                touchSlopPx = slop,
                pressedPointerCount = 1,
                stillPressed = true,
            ),
        )
    }

    @Test
    fun unreadyPreLiftTimeoutEmitsNoLiftAndThereforeNoTomeWriteIntent() {
        assertNull(homeTomeTouchLiftIntent(liftSettled = false, seatIndex = 4, transactionId = 9L))
        assertEquals(
            HomeTomeArrangeIntent.Lift(seatIndex = 4, transactionId = 9L),
            homeTomeTouchLiftIntent(liftSettled = true, seatIndex = 4, transactionId = 9L),
        )
    }

    @Test
    fun tomeFocusMemoryRestoresStableItemThenNearestSeatThenFirstOccupied() {
        val slots = listOf<HomeShelfItemId?>(ids[0], null, ids[1], null, ids[2])
        assertEquals(
            2,
            homeTomeFolioRestoreFocusIndex(HomeTomeFolioFocusMemory(ids[1], seatIndex = 0), slots),
        )
        // Removed remembered item: seat 3 chooses the tied lower surviving seat deterministically.
        assertEquals(
            2,
            homeTomeFolioRestoreFocusIndex(HomeTomeFolioFocusMemory(ids[3], seatIndex = 3), slots),
        )
        assertEquals(
            3,
            homeTomeFolioRestoreFocusIndex(
                HomeTomeFolioFocusMemory(itemId = null, seatIndex = 3, wasEmptySeat = true),
                slots,
            ),
        )
        assertEquals(0, homeTomeFolioRestoreFocusIndex(null, slots))
    }

    @Test
    fun touchPickupRejectsEarlyReleaseAndSecondPointer() {
        val slop = 10f
        assertEquals(
            HomeTomeTouchPickup.REJECTED,
            homeTomeTouchPickupDecision(
                elapsedMs = 100L,
                distancePx = 0f,
                touchSlopPx = slop,
                pressedPointerCount = 1,
                stillPressed = false,
            ),
        )
        assertEquals(
            HomeTomeTouchPickup.REJECTED,
            homeTomeTouchPickupDecision(
                elapsedMs = 100L,
                distancePx = 0f,
                touchSlopPx = slop,
                pressedPointerCount = 2,
                stillPressed = true,
            ),
        )
    }

    @Test
    fun settlementUsesOnlyTheLatestPublishedDestination() {
        val target = HomeTomeFolioFocusTarget(3, FocusKey("home/tome/member-3"))

        assertSame(
            target,
            homeTomeFolioSettlementTarget(target, HomeTomeFolioScope, target.key),
        )
        assertNull(homeTomeFolioSettlementTarget(target, FocusScope("other"), target.key))
        assertNull(
            homeTomeFolioSettlementTarget(
                target,
                HomeTomeFolioScope,
                FocusKey("home/tome/member-2"),
            ),
        )
        assertNull(homeTomeFolioSettlementTarget(null, HomeTomeFolioScope, target.key))

        // A touch lift waits for the exact seat: wrong seat or unsettled focus never lifts.
        assertTrue(homeTomeTouchLiftReady(focusedIndex = 3, liftIndex = 3, focusSettled = true))
        assertFalse(homeTomeTouchLiftReady(focusedIndex = 3, liftIndex = 3, focusSettled = false))
        assertFalse(homeTomeTouchLiftReady(focusedIndex = 2, liftIndex = 3, focusSettled = true))
    }

    @Test
    fun theColumnWalkStopsAtWhicheverThresholdTheCallerHandsIt() {
        // The walk itself is unchanged in shape — the threshold is now the caller's to choose, because
        // the count is decided against what the surface can afford, not against a fixed constant.
        // At the collapse floor it reproduces the pre-floor law exactly (OWED-1 option C's mechanism).
        assertEquals(6, homeTomeFolioVisibleColumns(600f, 10f, HomeTomeFolioMinCellDp))
        assertEquals(5, homeTomeFolioVisibleColumns(290f, 10f, HomeTomeFolioMinCellDp))
        assertEquals(4, homeTomeFolioVisibleColumns(230f, 10f, HomeTomeFolioMinCellDp))
        assertEquals(1, homeTomeFolioVisibleColumns(40f, 10f, HomeTomeFolioMinCellDp))

        // At the readability target the same widths shed columns until a column is legible, not merely
        // operable. A genuinely wide box loses nothing (600dp pays six 91.7dp wells); 500dp pays six
        // 75dp wells but only five legible ones.
        assertEquals(6, homeTomeFolioVisibleColumns(600f, 10f, HomeTomeFolioWellFloorDp))
        assertEquals(6, homeTomeFolioVisibleColumns(500f, 10f, HomeTomeFolioMinCellDp))
        assertEquals(5, homeTomeFolioVisibleColumns(500f, 10f, HomeTomeFolioWellFloorDp))
        assertEquals(3, homeTomeFolioVisibleColumns(290f, 10f, HomeTomeFolioWellFloorDp))
        assertEquals(2, homeTomeFolioVisibleColumns(230f, 10f, HomeTomeFolioWellFloorDp))
        assertEquals(1, homeTomeFolioVisibleColumns(40f, 10f, HomeTomeFolioWellFloorDp))

        // Whatever threshold it is handed, the column it returns clears it — or it has run out of columns.
        listOf(HomeTomeFolioMinCellDp, HomeTomeFolioWellFloorDp).forEach { threshold ->
            val usable = 290f
            val solved = homeTomeFolioVisibleColumns(usable, 10f, threshold)
            val cell = (usable - 10f * (solved - 1)) / solved
            assertTrue(solved == 1 || cell >= threshold)
        }

        // Non-positive widths still degenerate to a single column rather than dividing by zero.
        assertEquals(1, homeTomeFolioVisibleColumns(0f, 10f, HomeTomeFolioWellFloorDp))
        assertEquals(1, homeTomeFolioVisibleColumns(-1f, 10f, HomeTomeFolioWellFloorDp))
    }

    @Test
    fun gridSolverSpendsSurplusWidthOnWellSizeUntilItReachesWhatTheHeightPays() {
        val gap = 8f
        // 600x220: the height pays 68dp, the target self-clamps there, and six 93.3dp columns clear it.
        // Height-bound, so this is bit-exact against the pre-floor law (see P1 below).
        val target = solveHomeTomeFolioGrid(usableWidth = 600f, usableHeight = 220f, columnGap = gap, rowGap = gap)
        assertEquals(6, target.visibleColumns)
        assertEquals(3, target.visibleRows)
        assertEquals(68f, target.cellSideDp, 0.001f)

        // 290x220 is the superseded mid-range case. The old law kept five 51.6dp columns because 51.6
        // still "functions"; the new one sheds down to three, because 68dp of height was already bought
        // and paid for and a three-column width cell (91.3dp) is the first that can spend it.
        val compactWidth = solveHomeTomeFolioGrid(usableWidth = 290f, usableHeight = 220f, columnGap = gap, rowGap = gap)
        assertEquals(3, compactWidth.visibleColumns)
        assertEquals(3, compactWidth.visibleRows)
        assertEquals(68f, compactWidth.cellSideDp, 0.001f)
        assertTrue(compactWidth.cellSideDp > 51.6f)

        // The count never overshoots what the height pays: the readability target caps at cellFromHeight,
        // so the solve stays height-bound and never sheds a column to buy width it cannot use.
        assertTrue(compactWidth.cellSideDp <= HomeTomeFolioWellFloorDp)
    }

    @Test
    fun compactGridSolverChoosesRowsBeforeCellSideAndNeverCollapsesToOneRow() {
        val gap = 8f
        val fullHeight = solveHomeTomeFolioGrid(usableWidth = 600f, usableHeight = 220f, columnGap = gap, rowGap = gap)
        assertEquals(6, fullHeight.visibleColumns)
        assertEquals(3, fullHeight.visibleRows)
        assertTrue(fullHeight.cellSideDp >= HomeTomeFolioMinCellDp)

        val compactHeight = solveHomeTomeFolioGrid(usableWidth = 290f, usableHeight = 100f, columnGap = gap, rowGap = gap)
        assertEquals(5, compactHeight.visibleColumns)
        assertEquals(2, compactHeight.visibleRows)
        assertTrue(compactHeight.cellSideDp >= HomeTomeFolioMinCellDp)
        val twoRowEnvelope = compactHeight.cellSideDp * 2f + gap
        assertTrue(twoRowEnvelope > 100f + 0.001f)

        val pathological = solveHomeTomeFolioGrid(usableWidth = 290f, usableHeight = 60f, columnGap = gap, rowGap = gap)
        assertEquals(2, pathological.visibleRows)
        assertEquals(HomeTomeFolioMinCellDp, pathological.cellSideDp, 0.001f)

        assertEquals(3, homeTomeFolioPreferredVisibleRows(220f, gap))
        assertEquals(2, homeTomeFolioPreferredVisibleRows(118f, gap))
        assertEquals(2, homeTomeFolioPreferredVisibleRows(60f, gap))
    }

    // ---- The readability floor, proven against both certified devices --------------------------
    //
    // Boxes are the ones the devices actually hand the Folio Box, measured 2026-08-21 on
    // 0.1.0-alpha.1 (versionCode 4) at fontScale 1.0 and confirmed sub-pixel against screenshots
    // . Note these are NOT
    // MimirCertifiedViewports.Odin.postInsetShelfDp (833x444) — that fixture does not match the live
    // box, which is the full viewport. The fixture drift is owed to the lane that owns it.

    private data class FolioSolverInput(val usableWidth: Float, val usableHeight: Float, val gap: Float)

    /**
     * The Folio input chain replayed in Float, so the bit-exactness claim is made in the arithmetic
     * that actually runs. Mirrors HomeTomeFolio.kt:243-274 — panel 0.90 of each axis, outer
     * 0.045*panelW, pad 0.03*P, gap 0.018*P, rawUsableWidth 0.94*P, usableHeight = pageHeight minus
     * two 14dp ring clearances. That call site is another lane's file; if its proportions move, this
     * helper must move with them.
     */
    private fun folioSolverInput(
        boxWidthDp: Float,
        boxHeightDp: Float,
        fontScale: Float = 1f,
    ): FolioSolverInput {
        val panelWidth = boxWidthDp * 0.90f
        val panelHeight = boxHeightDp * 0.90f
        val outer = panelWidth * 0.045f
        val pageWidth = panelWidth - outer * 2f
        val pad = pageWidth * 0.03f
        val gap = pageWidth * 0.018f
        val partitions = solveHomeTomeFolioVerticalPartitions(panelHeight = panelHeight, fontScale = fontScale)
        val pageHeight = partitions.gridBottom - partitions.gridTop
        return FolioSolverInput(
            usableWidth = pageWidth - pad * 2f,
            usableHeight = pageHeight - 14f * 2f,
            gap = gap,
        )
    }

    /** The pre-floor law, inlined: the identical walk, thresholded at the 48dp collapse floor. */
    private fun preFloorSolve(input: FolioSolverInput): HomeTomeFolioGridSolve {
        var columns = HomeTomeFolioColumns
        if (input.usableWidth <= 0f) {
            columns = 1
        } else {
            while (columns > 1) {
                val cell = (input.usableWidth - input.gap * (columns - 1)) / columns
                if (cell >= HomeTomeFolioMinCellDp) break
                columns--
            }
        }
        val visibleRows = homeTomeFolioPreferredVisibleRows(input.usableHeight, input.gap)
        val cellFromWidth = (input.usableWidth - input.gap * (columns - 1)) / columns
        val cellFromHeight = (input.usableHeight - input.gap * (visibleRows - 1)) / visibleRows
        return HomeTomeFolioGridSolve(
            visibleColumns = columns,
            visibleRows = visibleRows,
            cellSideDp = maxOf(HomeTomeFolioMinCellDp, minOf(cellFromWidth, cellFromHeight)),
        )
    }

    private fun solve(input: FolioSolverInput): HomeTomeFolioGridSolve = solveHomeTomeFolioGrid(
        usableWidth = input.usableWidth,
        usableHeight = input.usableHeight,
        columnGap = input.gap,
        rowGap = input.gap,
    )

    /**
     * Counterfactual: what the count would be if the authored floor were [floorDp]. This is the one
     * place a line of the law is restated in the test, because no test that only calls production can
     * ask "what if the constant were different". The first assertion of every sweep pins it back to
     * production at the authored value, so any drift in the real target formula fails here.
     */
    private fun columnsUnderFloor(input: FolioSolverInput, floorDp: Float): Int {
        val visibleRows = homeTomeFolioPreferredVisibleRows(input.usableHeight, input.gap)
        val cellFromHeight = (input.usableHeight - input.gap * (visibleRows - 1)) / visibleRows
        val target = minOf(floorDp, input.usableWidth, cellFromHeight)
            .coerceAtLeast(HomeTomeFolioMinCellDp)
        return homeTomeFolioVisibleColumns(input.usableWidth, input.gap, target)
    }

    private fun odinInput(fontScale: Float = 1f) =
        folioSolverInput(1920f / 2.30625f, 1080f / 2.30625f, fontScale)

    private fun novaInput(fontScale: Float = 1f) = folioSolverInput(640f, 480f, fontScale)

    @Test
    fun theOdinFolioIsBitExactAgainstThePreFloorLawAtEveryFontScale() {
        // postInsetPx=1920x1080 density=2.30625 -> 832.52x468.29dp. The Odin is HEIGHT-bound: its width
        // would pay 96.59dp per column, its height only pays 84.91, and the Folio centres the grid. That
        // is the whole proof — the target self-clamps at what the height pays, so it can never exceed the
        // six-column width cell, so the walk stops at six exactly where it always did.
        val odin = odinInput()
        val solved = solve(odin)

        assertEquals(preFloorSolve(odin), solved)
        assertEquals(6, solved.visibleColumns)
        assertEquals(3, solved.visibleRows)
        assertEquals(84.906f, solved.cellSideDp, 0.001f)

        val cellFromWidth = (odin.usableWidth - odin.gap * 5f) / 6f
        assertTrue(cellFromWidth > solved.cellSideDp)

        // Larger chrome shrinks cellFromHeight, which shrinks the target with it, so the lemma is
        // monotone rather than a coincidence of fontScale 1. Bit-exact at every scale, cell included.
        listOf(1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f, 2.5f).forEach { scale ->
            val scaled = odinInput(scale)
            assertEquals("fontScale $scale", preFloorSolve(scaled), solve(scaled))
            assertEquals("fontScale $scale", 6, solve(scaled).visibleColumns)
        }
    }

    @Test
    fun theNovaFolioSpendsItsSurplusWidthOnLegibleWells() {
        // postInsetPx=1280x960 density=2.0 -> 640x480dp. WIDTH-bound, which is why it is the only
        // certified device the floor can reach: six columns land the cell at 74.26dp, rejected on
        // hardware (Tome tiles were reported hard to read on Nova), while the height was already able to pay 90.31.
        val nova = novaInput()

        val before = preFloorSolve(nova)
        assertEquals(6, before.visibleColumns)
        assertEquals(74.256f, before.cellSideDp, 0.001f)

        val after = solve(nova)
        assertEquals(5, after.visibleColumns)
        assertEquals(3, after.visibleRows)
        assertEquals(90.310f, after.cellSideDp, 0.001f)
        assertTrue(after.cellSideDp >= HomeTomeFolioWellFloorDp)

        // The logical page never shrinks — 15 of the 18 seats mount at rest, the rest arrive by travel.
        assertEquals(15, after.visibleColumns * after.visibleRows)
        assertEquals(18, HomeTomeFolioPageSize)

        // Near fontScale 2 the Nova lawfully returns to six columns: height that poor cannot pay 90dp,
        // so the target self-clamps below the six-column width cell and the old law is recovered exactly.
        val huge = novaInput(2f)
        assertEquals(preFloorSolve(huge), solve(huge))
        assertEquals(6, solve(huge).visibleColumns)
    }

    @Test
    fun neitherCertifiedDeviceCanBeMovedByAnyFloorInsideTheMeasuredBracket() {
        val odin = odinInput()
        val nova = novaInput()

        // The counterfactual helper must agree with production at the authored value, or the sweeps
        // below are measuring something the app does not do.
        assertEquals(solve(odin).visibleColumns, columnsUnderFloor(odin, HomeTomeFolioWellFloorDp))
        assertEquals(solve(nova).visibleColumns, columnsUnderFloor(nova, HomeTomeFolioWellFloorDp))

        // The Odin is height-bound, so NO floor moves it — not even one far above the bracket.
        var floor = HomeTomeFolioMinCellDp
        while (floor <= 85f) {
            assertEquals("odin @ floor $floor", 6, columnsUnderFloor(odin, floor))
            floor += 0.5f
        }

        // The Nova is invariant across the bracket the floor was authored from: the rejected 74.26dp
        // cell (exclusive) up to the kept Odin cell 84.91. 80 is near the midpoint of that and is not
        // load-bearing — any value inside it lands the same 5x3 @90.31.
        floor = 75f
        while (floor <= 85f) {
            assertEquals("nova @ floor $floor", 5, columnsUnderFloor(nova, floor))
            floor += 0.5f
        }

        // And the mechanism honours the opposite boundary case exactly: a floor at the collapse threshold is
        // today's behaviour, bit for bit, on the device that would otherwise change.
        assertEquals(6, columnsUnderFloor(nova, HomeTomeFolioMinCellDp))
        assertEquals(preFloorSolve(nova).visibleColumns, columnsUnderFloor(nova, HomeTomeFolioMinCellDp))
    }

    @Test
    fun clampedRowWindowNeverAliasesPastLogicalRows() {
        assertEquals(0 to 2, homeTomeFolioClampedRowWindow(focusedRow = 0, visibleRows = 2))
        assertEquals(1 to 2, homeTomeFolioClampedRowWindow(focusedRow = 2, visibleRows = 2))
        assertEquals(0 to 3, homeTomeFolioClampedRowWindow(focusedRow = 1, visibleRows = 99))
        assertEquals(0 to 1, homeTomeFolioClampedRowWindow(focusedRow = 0, visibleRows = 0))
    }

    @Test
    fun rowWindowHitMappingResolvesOnlyPaintedLogicalRows() {
        val page = HomeTomeFolioPageSize
        val cell = 75f
        val gap = 10f
        assertEquals(
            6,
            homeTomeFolioSeatIndexAtTravel(
                positionX = 37.5f,
                positionY = 37.5f,
                travelStartColumn = 0f,
                seatCount = page,
                cellWidth = cell,
                cellHeight = cell,
                columnGap = gap,
                rowGap = gap,
                rowWindowStart = 1,
                visibleRows = 2,
            ),
        )
        assertEquals(
            12,
            homeTomeFolioSeatIndexAtTravel(
                positionX = 37.5f,
                positionY = 122.5f,
                travelStartColumn = 0f,
                seatCount = page,
                cellWidth = cell,
                cellHeight = cell,
                columnGap = gap,
                rowGap = gap,
                rowWindowStart = 1,
                visibleRows = 2,
            ),
        )
        assertNull(
            homeTomeFolioSeatIndexAtTravel(
                positionX = 37.5f,
                positionY = 200f,
                travelStartColumn = 0f,
                seatCount = page,
                cellWidth = cell,
                cellHeight = cell,
                columnGap = gap,
                rowGap = gap,
                rowWindowStart = 2,
                visibleRows = 1,
            ),
        )
        assertEquals(
            17,
            homeTomeFolioNearestSeatAtTravel(
                positionX = 462.5f,
                positionY = 122.5f,
                travelStartColumn = 0f,
                seatCount = page,
                cellWidth = cell,
                cellHeight = cell,
                columnGap = gap,
                rowGap = gap,
                rowWindowStart = 1,
                visibleRows = 2,
            ),
        )
    }

    @Test
    fun compactViewportsMayPaintSixByTwoOverTheLogicalEighteenSeatPage() {
        val gap = 8f
        val threeRowMin = HomeTomeFolioMinCellDp * 3f + gap * 2f
        val twoRowMin = HomeTomeFolioMinCellDp * 2f + gap
        assertEquals(3, homeTomeFolioPreferredVisibleRows(threeRowMin, gap))
        assertEquals(2, homeTomeFolioPreferredVisibleRows(twoRowMin, gap))
        assertEquals(2, homeTomeFolioPreferredVisibleRows(twoRowMin - 1f, gap))

        assertEquals(0, homeTomeFolioRowWindowStart(focusedRow = 0, visibleRows = 2))
        assertEquals(1, homeTomeFolioRowWindowStart(focusedRow = 1, visibleRows = 2))
        assertEquals(1, homeTomeFolioRowWindowStart(focusedRow = 2, visibleRows = 2))
    }

    @Test
    fun continuousSheetRowWindowUsesOneRowCadenceInBothDirections() {
        val total24 = homeTomeFolioTotalRows(24)
        val total40 = homeTomeFolioTotalRows(40)
        assertEquals(4, total24)
        assertEquals(7, total40)

        // Cold/top-pin seed: row0 → 0..2, row1 → 1..3.
        assertEquals(0, homeTomeFolioRowWindowStart(focusedRow = 0, visibleRows = 3, totalRows = total24))
        assertEquals(1, homeTomeFolioRowWindowStart(focusedRow = 1, visibleRows = 3, totalRows = total24))
        assertEquals(0 to 3, homeTomeFolioClampedRowWindow(0, 3, total24))
        assertEquals(1 to 3, homeTomeFolioClampedRowWindow(1, 3, total24))

        // DOWN row0→1 yields window rows 1..3 (destination pinned at TOP).
        assertEquals(
            1,
            homeTomeFolioRowWindowStart(
                previousStart = 0,
                previousFocusedRow = 0,
                focusedRow = 1,
                visibleRows = 3,
                totalRows = total24,
            ),
        )

        // On a four-row sheet, prior start 2 clamps to terminal start 1; UP shifts once to rows 0..2.
        assertEquals(
            0,
            homeTomeFolioRowWindowStart(
                previousStart = 2,
                previousFocusedRow = 2,
                focusedRow = 1,
                visibleRows = 3,
                totalRows = total24,
            ),
        )

        // Same-focus preserves the containing window.
        assertEquals(
            1,
            homeTomeFolioRowWindowStart(
                previousStart = 1,
                previousFocusedRow = 2,
                focusedRow = 2,
                visibleRows = 3,
                totalRows = total24,
            ),
        )

        // Terminal clamp relaxes: last rows share the final window.
        assertEquals(1, homeTomeFolioRowWindowStart(focusedRow = 3, visibleRows = 3, totalRows = total24))
        assertEquals(4, homeTomeFolioRowWindowStart(focusedRow = 6, visibleRows = 3, totalRows = total40))
        assertEquals(4, homeTomeFolioRowWindowStart(focusedRow = 5, visibleRows = 3, totalRows = total40))

        // Global index roundtrip and terminal crossing 17↔18 has no page-boundary dependence.
        assertEquals(2, homeTomeFolioRowOfIndex(17))
        assertEquals(3, homeTomeFolioRowOfIndex(18))
        assertEquals(17, homeTomeFolioIndexOf(column = 5, globalRow = 2))
        assertEquals(18, homeTomeFolioIndexOf(column = 0, globalRow = 3))
        assertEquals(
            3,
            homeTomeFolioRowWindowStart(
                previousStart = 2,
                previousFocusedRow = 2,
                focusedRow = 3,
                visibleRows = 3,
                totalRows = total40,
            ),
        )
        assertEquals(
            2,
            homeTomeFolioRowWindowStart(
                previousStart = 3,
                previousFocusedRow = 3,
                focusedRow = 2,
                visibleRows = 3,
                totalRows = total40,
            ),
        )
        (0 until 40).forEach { index ->
            val column = homeTomeFolioColumnOfIndex(index)
            val row = homeTomeFolioRowOfIndex(index)
            assertEquals(index, homeTomeFolioIndexOf(column, row))
        }
        // Compatibility page DTO addressing remains inert/available.
        assertEquals(18, homeTomeFolioIndexOf(0, 0, pageIndex = 1))
        assertEquals(24, homeTomeFolioIndexOf(0, 1, pageIndex = 1))

        val hydration = homeTomeFolioHydrationRowRange(rowWindowStart = 1, visibleRows = 3, totalRows = total40)
        assertEquals(0, hydration.first)
        assertEquals(4, hydration.last)
    }

    @Test
    fun reverseBurstUsesTheSameOneRowCadenceAndLandsOnTheLatestFocus() {
        val totalRows = homeTomeFolioTotalRows(40)
        var previousFocus = 0
        var windowStart = 0

        listOf(1, 2, 3, 2, 1, 0).forEach { focus ->
            val next = homeTomeFolioRowWindowStart(
                previousStart = windowStart,
                previousFocusedRow = previousFocus,
                focusedRow = focus,
                visibleRows = 3,
                totalRows = totalRows,
            )
            assertEquals(focus, next)
            previousFocus = focus
            windowStart = next
        }
        assertEquals(
            4,
            homeTomeFolioRowWindowStart(
                previousStart = 1,
                previousFocusedRow = 1,
                focusedRow = 4,
                visibleRows = 3,
                totalRows = totalRows,
            ),
        )
    }

    @Test
    fun sharedRingAndDestinationUseLivePaintedPoseWithoutSecondaryXYTween() {
        // Source contract: HomeTomeFolio ring translationX/Y bind paintedLeftPx/paintedTopPx
        // directly (no animateFloatAsState on ring X/Y). Destination tiles share that same live pose.
        val stripLeft = 120f
        val stride = 85f
        val left = homeTomeFolioPaintLeftX(
            stripLeftX = stripLeft,
            gridLeftPx = 40f,
            travelStartColumn = 1f,
            strideXPx = stride,
        )
        val top = homeTomeFolioStripCellTopY(
            logicalRow = 2,
            rowWindowStart = 1,
            cellHeightPx = 75f,
            rowGapPx = 10f,
        )
        assertEquals(40f + stripLeft - stride, left, 0.001f)
        assertEquals(85f, top, 0.001f)
        assertEquals(left, homeTomeFolioPaintLeftX(stripLeft, 40f, 1f, stride), 0.001f)
        assertEquals(top, homeTomeFolioStripCellTopY(2, 1, 75f, 10f), 0.001f)
    }

    @Test
    fun touchMappingFollowsVerticalWindowWithoutPageIndex() {
        val cell = 75f
        val gap = 10f
        // After shifting to global rows 3..5, the first painted row is seat 18.
        assertEquals(
            18,
            homeTomeFolioSeatIndexAtTravel(
                positionX = 37.5f,
                positionY = 37.5f,
                travelStartColumn = 0f,
                seatCount = 40,
                cellWidth = cell,
                cellHeight = cell,
                columnGap = gap,
                rowGap = gap,
                rowWindowStart = 3,
                visibleRows = 3,
            ),
        )
        assertEquals(
            20,
            homeTomeFolioNearestSeatAtTravel(
                positionX = 200f,
                positionY = 37.5f,
                travelStartColumn = 0f,
                seatCount = 40,
                cellWidth = cell,
                cellHeight = cell,
                columnGap = gap,
                rowGap = gap,
                rowWindowStart = 3,
                visibleRows = 3,
            ),
        )
    }

    @Test
    fun animatedRowHitKeepsPaintedCenterStableAcrossHalfStrideBothSides() {
        val cell = 75f
        val gap = 10f
        val stride = cell + gap
        // Global seat 6 (row 1, col 0). Paint translates by -A*stride; viewport center of that seat:
        // absoluteTop=stride, viewportTop = stride - A*stride, center = viewportTop + cell/2.
        fun viewportCenterForGlobalRow1(animatedStart: Float): Float =
            stride - animatedStart * stride + cell / 2f

        for (animated in floatArrayOf(0.49f, 0.51f)) {
            val viewportY = viewportCenterForGlobalRow1(animated)
            val hit = homeTomeFolioAnimatedRowHit(
                animatedRowWindowStart = animated,
                viewportRelativeY = viewportY,
                cellHeight = cell,
                rowGap = gap,
            )
            assertEquals(kotlin.math.round(animated).toInt(), hit.discreteRowWindowStart)
            assertEquals(
                6,
                homeTomeFolioSeatIndexAtTravel(
                    positionX = 37.5f,
                    positionY = hit.adjustedPositionY,
                    travelStartColumn = 0f,
                    seatCount = 40,
                    cellWidth = cell,
                    cellHeight = cell,
                    columnGap = gap,
                    rowGap = gap,
                    rowWindowStart = hit.discreteRowWindowStart,
                    visibleRows = 3,
                ),
            )
            assertEquals(
                6,
                homeTomeFolioNearestSeatAtTravel(
                    positionX = 37.5f,
                    positionY = hit.adjustedPositionY,
                    travelStartColumn = 0f,
                    seatCount = 40,
                    cellWidth = cell,
                    cellHeight = cell,
                    columnGap = gap,
                    rowGap = gap,
                    rowWindowStart = hit.discreteRowWindowStart,
                    visibleRows = 3,
                ),
            )
        }

        // Settled integer A must leave Y untouched and round to itself.
        val settled = homeTomeFolioAnimatedRowHit(
            animatedRowWindowStart = 2f,
            viewportRelativeY = 37.5f,
            cellHeight = cell,
            rowGap = gap,
        )
        assertEquals(2, settled.discreteRowWindowStart)
        assertEquals(37.5f, settled.adjustedPositionY, 0.0001f)
        assertEquals(
            12,
            homeTomeFolioSeatIndexAtTravel(
                positionX = 37.5f,
                positionY = settled.adjustedPositionY,
                travelStartColumn = 0f,
                seatCount = 40,
                cellWidth = cell,
                cellHeight = cell,
                columnGap = gap,
                rowGap = gap,
                rowWindowStart = settled.discreteRowWindowStart,
                visibleRows = 3,
            ),
        )
    }

    @Test
    fun artWellPolicyUsesExactSquareFillAndContainsNearSquarePortrait() {
        assertTrue(homeTomeFolioArtShouldFillWell(512f, 512f))
        assertFalse(homeTomeFolioArtShouldFillWell(540f, 500f))
        assertFalse(homeTomeFolioArtShouldFillWell(1920f, 1080f))
        assertFalse(homeTomeFolioArtShouldFillWell(600f, 800f))
        assertFalse(homeTomeFolioArtShouldFillWell(0f, 0f))

        assertEquals(0f to 0f, homeTomeFolioArtIntrinsicDimensions(128f, 64f, specified = false))
        assertEquals(512f to 512f, homeTomeFolioArtIntrinsicDimensions(512f, 512f, specified = true))

        assertTrue(homeTomeFolioArtUsesFillScale(physical = false, intrinsicWidth = 512f, intrinsicHeight = 512f))
        assertFalse(homeTomeFolioArtUsesFillScale(physical = false, intrinsicWidth = 540f, intrinsicHeight = 500f))
        assertFalse(homeTomeFolioArtUsesFillScale(physical = true, intrinsicWidth = 800f, intrinsicHeight = 600f))
        assertFalse(homeTomeFolioArtUsesFillScale(physical = false, intrinsicWidth = 1280f, intrinsicHeight = 720f))
        assertFalse(homeTomeFolioArtUsesFillScale(physical = false, intrinsicWidth = 0f, intrinsicHeight = 0f))

        assertTrue(homeTomeFolioArtShowsPlaceholder(artUri = null, painterReady = false))
        assertFalse(homeTomeFolioArtShowsPlaceholder(artUri = "file:///cover.png", painterReady = false))
        assertFalse(homeTomeFolioArtShowsPlaceholder(artUri = "file:///cover.png", painterReady = true))
        assertTrue(homeTomeFolioArtShowsPlaceholder(artUri = null, painterReady = true))
        assertTrue(homeTomeFolioArtShowsQuietBackdrop(artUri = "file:///cover.png", artReady = false))
        assertFalse(homeTomeFolioArtShowsQuietBackdrop(artUri = "file:///cover.png", artReady = true))
        assertFalse(homeTomeFolioArtShowsQuietBackdrop(artUri = null, artReady = false))

        // RESTATED 2026-08-25. These two lines used to pass the uri and the hand pick and let the helper
        // COMPARE them. That comparison was wrong for every face the hand pick did not produce -- a
        // downloaded disc came back "cover" -- so the kind is now carried by the face itself and these
        // assert the same two situations stated, not inferred. The cases the comparison got wrong are
        // pinned in HomeTomeWellFaceKindTest.
        assertTrue(
            homeTomeFolioMemberIsPhysical(MimirShelfFace("file:///cartridge.png", ShelfFaceShape.PHYSICAL)),
        )
        assertFalse(
            homeTomeFolioMemberIsPhysical(MimirShelfFace("file:///cover.png", ShelfFaceShape.LEDGE_COVER)),
        )
    }

    /**
     * ONE law for every Shelf surface — Home, Library, and inside a Tome (C2 puts all three in one state).
     *
     * The Home shelf tile is why this is extracted rather than duplicated: until 2026-08-25 it read the
     * cover and NOTHING ELSE, so a game whose only art was a disc wore a placeholder there while showing
     * that disc one screen away. A state whose surfaces each carry a private ladder is exactly the
     * confusion C1 exists to fix.
     *
     * **The ladder has been TWO tiers since 2026-09-22: the hand pick, then the cover.** The preceding changes are independent: C4 (2026-08-25) took the toggle's veto off the pick. The media-consistency change
     * stopped a disc standing in for a missing cover, because across a library that fallback is what made
     * a shelf paint boxes and cartridges side by side, decided game by game by whichever the provider
     * happened to carry. Removing the Cartridge Hero promotion settles WHICH single kind the automatic
     * tier is — the cover, the answer that switch already gave in its shipped OFF position.
     *
     * The 16-case walk that stood in its own test folds into the agreement loop at the end: with the
     * provider tier gone there are four inputs, not sixteen, and stating the property separately would
     * only restate `assertEquals(cover, ...)`. That the family has no provider tier LEFT to reach is
     * pinned in `HomeTomeWellFaceKindTest`, where the signature is.
     */
    @Test
    fun theShelfFaceLawIsOneLadderForEverySurface() {
        val cover = "file:///covers/zelda.png"
        val pick = "file:///picked/zelda.png"

        // C4: his pick wins, and there is no longer any default left that could have argued with it.
        assertEquals(pick, mimirShelfFaceUri(pick, cover))
        assertEquals(pick, mimirShelfFaceUri(pick, null))
        // With no pick, the cover -- the ONE automatic kind, so a shelf cannot mix kinds by construction.
        assertEquals(cover, mimirShelfFaceUri(null, cover))
        // Nothing at all stays nothing -- the caller keeps its honest placeholder.
        assertNull(mimirShelfFaceUri(null, null))

        // The kind is REPORTED by the tier that decided, never inferred from the uri. Only tier 1 can be
        // physical now; the check stays because a consumer that guesses squares a 2D box off at 1:1.
        assertTrue(mimirShelfFace(pick, ShelfFaceShape.PHYSICAL, cover).physical)
        assertFalse(
            "a picked COVER is a cover, though a pick may be either kind",
            mimirShelfFace(pick, ShelfFaceShape.LEDGE_COVER, cover).physical,
        )
        assertEquals("a picked SQUARE keeps its shape", ShelfFaceShape.SQUARE, mimirShelfFace(pick, ShelfFaceShape.SQUARE, cover).shape)
        assertFalse("the automatic tier is always a cover", mimirShelfFace(null, ShelfFaceShape.PHYSICAL, cover).physical)
        assertFalse("no face at all cannot carry a kind", mimirShelfFace(null, ShelfFaceShape.PHYSICAL, null).physical)

        // And the Tome well is the SAME ladder, not a parallel one: identical inputs, identical answers.
        listOf(pick, null).forEach { wired ->
            listOf(cover, null).forEach { art ->
                assertEquals(
                    "wired=$wired artwork=$art",
                    mimirShelfFaceUri(wired, art),
                    homeTomeWellArtUri(wired, art),
                )
            }
        }
    }

    @Test
    fun tomeWellArtLawPrefersHisPickThenTheCover() {
        val cover = "file:///covers/zelda.png"
        val physical = "file:///user/tome_cartridge/zelda.png"

        // C4, and a STRONGER pin than the two-position version it replaces: there is no switch left to
        // agree with the pick, so the pick has to win on its own.
        assertEquals(physical, homeTomeWellArtUri(physical, cover))
        assertEquals(physical, homeTomeWellArtUri(physical, null))
        // With no pick, the cover.
        assertEquals(cover, homeTomeWellArtUri(null, cover))
        // DL-4 stays REVERSED here : a well with no cover wears the placeholder
        // rather than borrowing art of the other kind. The provider tier that used to supply that art is
        // gone as well (2026-09-22), so there are now two independent reasons this is null.
        assertNull(homeTomeWellArtUri(null, null))
        // The member field defaults to absent, so every existing construction stays honest.
        assertNull(HomeTomeMember(ids[0], "Zelda").physicalMediaUri)
    }

    @Test
    fun carriedIdentityUsesTheSameVerticalPageEdgeLaw() {
        val carrying = reduceHomeTomeArrange(beginHomeTomeArrange(ids, 17), ControllerAction.CONFIRM).state
        val down = reduceHomeTomeArrange(carrying, ControllerAction.NAV_DOWN)
        assertEquals(23, down.navigation?.targetIndex)
        assertEquals(ids[17], down.state.workingSlots[23])
        assertEquals(1, down.state.pageTransition.settledPage)

        val cancelled = reduceHomeTomeArrange(down.state, ControllerAction.CANCEL)
        assertEquals(17, cancelled.state.focusedIndex)
        assertEquals(0, cancelled.state.pageTransition.settledPage)
        assertEquals(ids, cancelled.state.workingOrder)

        val up = reduceHomeTomeArrange(down.state, ControllerAction.NAV_UP)
        assertEquals(17, up.navigation?.targetIndex)
        assertEquals(ids[17], up.state.workingSlots[17])
        assertEquals(0, up.state.pageTransition.settledPage)
    }

    @Test
    fun cancelRestoresBaselineAndIdleXRequestsExit() {
        val baseline = beginHomeTomeArrange(ids.take(8), 0)
        val carrying = reduceHomeTomeArrange(baseline, ControllerAction.CONFIRM).state
        val moved = reduceHomeTomeArrange(carrying, ControllerAction.NAV_RIGHT).state
        val cancelled = reduceHomeTomeArrange(moved, ControllerAction.CANCEL)

        assertEquals(ids.take(8), cancelled.state.workingOrder)
        assertNull(cancelled.state.carriedItemId)
        assertFalse(cancelled.exitRequested)
        assertTrue(reduceHomeTomeArrange(cancelled.state, ControllerAction.SECONDARY).exitRequested)
    }

    @Test
    fun favoriteRemovalCancelsADeletedCarryWithoutInventingAnOrder() {
        val carrying = reduceHomeTomeArrange(beginHomeTomeArrange(ids.take(4), 1), ControllerAction.CONFIRM).state
        val surviving = setOf(ids[0], ids[2], ids[3])
        val reconciled = reconcileHomeTomeArrange(carrying, surviving)

        assertEquals(listOf(ids[0], ids[2], ids[3]), reconciled.baselineOrder)
        assertEquals(reconciled.baselineOrder, reconciled.workingOrder)
        assertNull(reconciled.carriedItemId)
    }

    @Test
    fun removingTheLastMemberCollapsesToTheTrueEmptyFolioState() {
        val carrying = reduceHomeTomeArrange(beginHomeTomeArrange(ids.take(1), 0), ControllerAction.CONFIRM).state

        val reconciled = reconcileHomeTomeArrange(carrying, emptySet())

        assertTrue(reconciled.workingSlots.isEmpty())
        assertNull(reconciled.carriedItemId)
        assertEquals(0, reconciled.focusedIndex)
    }

    @Test
    fun folioUsesFixedEighteenMemberPagesAndBoundedAdjacentWarmWindow() {
        assertEquals(2, homeTomePageCount(20))
        assertEquals(ids.take(HomeTomeFolioPageSize), homeTomePage(ids, 0))
        assertEquals(ids.drop(HomeTomeFolioPageSize), homeTomePage(ids, 1))
        assertEquals(ids, homeTomeWarmWindow(ids, 0))
        assertEquals(1, homeTomePageForIndex(19, ids.size))
    }

    @Test
    fun everyPagedMemberKeepsAUniqueVirtualFocusDestination() {
        val members = ids.mapIndexed { index, id -> HomeTomeMember(id, "Game $index") }
        val slots = homeTomeVirtualFocusSlots(members)

        assertEquals(ids.indices.toList(), slots.map { it.index })
        assertEquals(members.map { it.focusKey }, slots.map { it.key })
        assertEquals(slots.size, slots.map { it.bounds }.distinct().size)
        assertEquals(slots[0].bounds.centerX, slots[HomeTomeFolioPageSize].bounds.centerX)
        assertTrue(slots[HomeTomeFolioPageSize].bounds.centerY > slots[HomeTomeFolioPageSize - 1].bounds.centerY)
    }

    @Test
    fun sparseMemberAndEveryEmptyWellKeepTheirVisualFocusDestinations() {
        val member = HomeTomeMember(ids[0], "Game")
        val slots = List<HomeTomeMember?>(HomeTomeFolioPageSize - 1) { null } + member
        val tomeId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000044"))

        val focus = homeTomeVirtualFocusSeatSlots(tomeId, slots)

        assertEquals((0 until HomeTomeFolioPageSize).toList(), focus.map { it.index })
        assertEquals(homeTomeSeatFocusKey(tomeId, 0), focus.first().key)
        assertEquals(member.focusKey, focus.last().key)
    }

    @Test
    fun authoritativeRingSeatDoesNotMoveBeforeFocusAuthorityPublishesTheDestination() {
        val tomeId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000046"))
        val first = HomeTomeMember(ids[0], "First")
        val upper = HomeTomeMember(ids[1], "Upper")
        val memberSlots = List<HomeTomeMember?>(HomeTomeFolioPageSize) { index ->
            when (index) {
                0 -> upper
                HomeTomeFolioColumns -> first
                else -> null
            }
        }
        val slots = homeTomeVirtualFocusSeatSlots(tomeId, memberSlots)

        // The reducer may already request index 0 after D-pad Up; the published key still owns row 1.
        assertEquals(
            HomeTomeFolioColumns,
            homeTomeFolioAuthoritativeSeatIndex(
                slots = slots,
                activeScope = HomeTomeFolioScope,
                activeKey = first.focusKey,
            ),
        )
        assertEquals(
            0,
            homeTomeFolioAuthoritativeSeatIndex(
                slots = slots,
                activeScope = HomeTomeFolioScope,
                activeKey = upper.focusKey,
            ),
        )
        assertEquals(
            2,
            homeTomeFolioAuthoritativeSeatIndex(
                slots = slots,
                activeScope = HomeTomeFolioScope,
                activeKey = homeTomeSeatFocusKey(tomeId, 2),
            ),
        )
        assertNull(homeTomeFolioAuthoritativeSeatIndex(slots, FocusScope.Root, first.focusKey))
        assertNull(homeTomeFolioAuthoritativeSeatIndex(slots, HomeTomeFolioScope, FocusKey("stale")))
        assertNull(homeTomeFolioAuthoritativeSeatIndex(slots, HomeTomeFolioScope, HomeTomeEmptyFocusKey))
    }

    @Test
    fun folioSheetAndRingShareThePublishedSeatDuringAVerticalFocusHandoff() {
        val tomeId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000047"))
        val upper = HomeTomeMember(ids[0], "Upper")
        val lower = HomeTomeMember(ids[1], "Lower")
        val memberSlots = List<HomeTomeMember?>(HomeTomeFolioPageSize) { index ->
            when (index) {
                0 -> upper
                HomeTomeFolioColumns -> lower
                else -> null
            }
        }
        val slots = homeTomeVirtualFocusSeatSlots(tomeId, memberSlots)

        // Reducer already requested row 0; the still-published lower key keeps BOTH paint systems on row 1.
        assertEquals(
            HomeTomeFolioColumns,
            homeTomeFolioVisualSeatIndex(
                slots = slots,
                activeScope = HomeTomeFolioScope,
                activeKey = lower.focusKey,
                reducerFocusedIndex = 0,
            ),
        )
        // Once FocusAuthority publishes the destination, both travel together to row 0.
        assertEquals(
            0,
            homeTomeFolioVisualSeatIndex(
                slots = slots,
                activeScope = HomeTomeFolioScope,
                activeKey = upper.focusKey,
                reducerFocusedIndex = 0,
            ),
        )
        // Cold composition still has a deterministic reducer seed.
        assertEquals(
            7,
            homeTomeFolioVisualSeatIndex(
                slots = slots,
                activeScope = FocusScope.Root,
                activeKey = null,
                reducerFocusedIndex = 7,
            ),
        )
    }

    @Test
    fun browseArrangeAndCarryAllTraverseEmptyWells() {
        val sparse = listOf(ids[0], null, ids[1]) + List<HomeShelfItemId?>(HomeTomeFolioPageSize - 3) { null }
        val browse = navigateHomeTomeFolio(0, sparse.size, ControllerAction.NAV_RIGHT)
        assertEquals(1, browse.targetIndex)

        val idleMove = reduceHomeTomeArrange(beginHomeTomeArrangeSlots(sparse, 0), ControllerAction.NAV_RIGHT)
        assertEquals(1, idleMove.state.focusedIndex)
        assertNull(idleMove.state.workingSlots[1])

        val carrying = reduceHomeTomeArrange(beginHomeTomeArrangeSlots(sparse, 0), ControllerAction.CONFIRM).state
        val carryMove = reduceHomeTomeArrange(carrying, ControllerAction.NAV_RIGHT)
        assertEquals(1, carryMove.state.focusedIndex)
        assertEquals(ids[0], carryMove.state.workingSlots[1])
    }

    @Test
    fun staleFolioCompositionCannotRestoreThePreviousDirectionalTarget() {
        val old = homeTomeMemberFocusKey(ids[0])
        val current = homeTomeMemberFocusKey(ids[1])

        assertFalse(
            shouldRepairHomeTomeFolioFocus(
                effectKey = old,
                latestKey = current,
                activeScope = HomeTomeFolioScope,
                activeKey = current,
            ),
        )
        assertFalse(
            shouldRepairHomeTomeFolioFocus(
                effectKey = current,
                latestKey = current,
                activeScope = HomeTomeFolioScope,
                activeKey = current,
            ),
        )
        assertTrue(
            shouldRepairHomeTomeFolioFocus(
                effectKey = current,
                latestKey = current,
                activeScope = HomeTomeFolioScope,
                activeKey = old,
            ),
        )
        assertFalse(
            shouldRepairHomeTomeFolioFocus(
                effectKey = current,
                latestKey = current,
                activeScope = FocusScope.Root,
                activeKey = old,
            ),
        )
    }

    @Test
    fun emptyBrowseWellConsumesOpenAndEditWithoutLeakingFocus() {
        val member = HomeTomeMember(ids[0], "Game")
        val tome = HomeShelfTome(
            TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000046")),
            "Sparse",
            emptyList(),
            1,
        )
        val sparse = listOf<HomeTomeMember?>(member, null) + List(HomeTomeFolioPageSize - 2) { null }
        val state = HomeTomeFolioUiState(
            tome = tome,
            members = listOf(member),
            memberSlots = sparse,
            focusedIndex = 1,
        )

        assertEquals(HomeTomeFolioActionRequest.Consumed, routeHomeTomeFolioAction(state, ControllerAction.CONFIRM))
        assertEquals(HomeTomeFolioActionRequest.Consumed, routeHomeTomeFolioAction(state, ControllerAction.TERTIARY))
        assertEquals(homeTomeSeatFocusKey(tome.tomeId, 1), homeTomeFolioFocusKey(tome.tomeId, sparse, 1, state.mode))
    }

    @Test
    fun occupiedInsertionPreviewIsIndependentOfIntermediatePointerSamples() {
        val baseline = beginHomeTomeArrange(ids.take(4), 0)
        val lifted = reduceHomeTomeArrange(
            baseline,
            HomeTomeArrangeIntent.Lift(0, baseline.nextTransactionId),
        ).state
        val transactionId = requireNotNull(lifted.activeTransactionId)
        val direct = reduceHomeTomeArrange(lifted, HomeTomeArrangeIntent.Retarget(2, transactionId)).state
        val sampled = reduceHomeTomeArrange(
            reduceHomeTomeArrange(lifted, HomeTomeArrangeIntent.Retarget(1, transactionId)).state,
            HomeTomeArrangeIntent.Retarget(2, transactionId),
        ).state

        assertEquals(direct.workingSlots, sampled.workingSlots)
        assertEquals(listOf(ids[1], ids[2], ids[0], ids[3]), direct.workingOrder)
    }

    @Test
    fun arrangeRegistersEveryEmptyWellWithStableSpatialIdentity() {
        val tomeId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000045"))
        val member = HomeTomeMember(ids[0], "Game")
        val members = listOf<HomeTomeMember?>(member) + List(HomeTomeFolioPageSize - 1) { null }

        val arrangeSlots = homeTomeVirtualFocusSeatSlots(tomeId, members)

        assertEquals((0 until HomeTomeFolioPageSize).toList(), arrangeSlots.map { it.index })
        assertEquals(member.focusKey, arrangeSlots.first().key)
        assertEquals(homeTomeSeatFocusKey(tomeId, HomeTomeFolioPageSize - 1), arrangeSlots.last().key)
        assertEquals(arrangeSlots.size, arrangeSlots.map { it.key }.distinct().size)
    }

    @Test
    fun exitingArrangeFromAnEmptyWellPreservesTheExactSpatialSeat() {
        val sparse = listOf(ids[0], null, null, ids[1]) + List<HomeShelfItemId?>(HomeTomeFolioPageSize - 4) { null }
        val emptyFocused = retargetHomeTomeArrange(beginHomeTomeArrangeSlots(sparse, 0), 2).state

        val exited = reduceHomeTomeArrange(emptyFocused, ControllerAction.SECONDARY)

        assertTrue(exited.exitRequested)
        assertEquals(2, exited.state.focusedIndex)
        assertNull(exited.state.workingSlots[exited.state.focusedIndex])
    }

    @Test
    fun AndroidGameUsesTheSameSparseArrangeLawAsAnEmulatedFavorite() {
        val android = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game"))
        val sparse = listOf<HomeShelfItemId?>(android) + List(HomeTomeFolioPageSize - 1) { null }
        val carrying = reduceHomeTomeArrange(beginHomeTomeArrangeSlots(sparse, 0), ControllerAction.CONFIRM).state

        val moved = reduceHomeTomeArrange(carrying, ControllerAction.NAV_RIGHT)

        assertNull(moved.state.workingSlots[0])
        assertEquals(android, moved.state.workingSlots[1])
    }

    @Test
    fun firstInteractionResidueCancellationIsIdempotent() {
        val moved = retargetHomeTomePageTransition(HomeTomePageTransition(settledPage = 0), 1)
        assertEquals(0, moved.outgoingPage)
        val settled = cancelHomeTomePageResidue(moved)
        assertNull(settled.outgoingPage)
        assertSame(settled, cancelHomeTomePageResidue(settled))
    }

    @Test
    fun publicRoutingSeparatesEditArrangeBackAndGeneralActions() {
        val member = HomeTomeMember(ids[0], "Game")
        val tome = HomeShelfTome(TomeId.Custom(UUID.nameUUIDFromBytes("RPGs".toByteArray())), "RPGs", emptyList(), 1)
        val browse = HomeTomeFolioUiState(tome = tome, members = listOf(member))

        assertEquals(HomeTomeFolioActionRequest.Edit(member), routeHomeTomeFolioAction(browse, ControllerAction.TERTIARY))
        assertEquals(HomeTomeFolioActionRequest.ToggleArrange, routeHomeTomeFolioAction(browse, ControllerAction.SECONDARY))
        assertEquals(HomeTomeFolioActionRequest.Back, routeHomeTomeFolioAction(browse, ControllerAction.CANCEL))
        assertEquals(
            HomeTomeFolioActionRequest.General(ControllerAction.SYSTEM_MENU),
            routeHomeTomeFolioAction(browse, ControllerAction.SYSTEM_MENU),
        )
        // Activation waits for the painted ring: A/Y (and Arrange lift/drop) are consumed until
        // the exact focus destination is acknowledged; direction and Back stay live throughout.
        val unsettled = browse.copy(focusSettled = false)
        assertEquals(
            HomeTomeFolioActionRequest.Consumed,
            routeHomeTomeFolioAction(unsettled, ControllerAction.CONFIRM),
        )
        assertEquals(
            HomeTomeFolioActionRequest.Consumed,
            routeHomeTomeFolioAction(unsettled, ControllerAction.TERTIARY),
        )
        assertEquals(
            HomeTomeFolioActionRequest.Arrange(ControllerAction.CONFIRM),
            routeHomeTomeFolioAction(
                unsettled.copy(mode = HomeTomeFolioMode.ARRANGE),
                ControllerAction.CONFIRM,
            ),
        )
        assertTrue(
            routeHomeTomeFolioAction(unsettled, ControllerAction.NAV_RIGHT)
                is HomeTomeFolioActionRequest.Navigate,
        )
        assertEquals(
            HomeTomeFolioActionRequest.Back,
            routeHomeTomeFolioAction(unsettled, ControllerAction.CANCEL),
        )
        val arrange = browse.copy(mode = HomeTomeFolioMode.ARRANGE)
        assertEquals(
            HomeTomeFolioActionRequest.Arrange(ControllerAction.CANCEL),
            routeHomeTomeFolioAction(arrange, ControllerAction.CANCEL),
        )
        assertEquals(
            HomeTomeFolioActionRequest.Arrange(ControllerAction.SECONDARY),
            routeHomeTomeFolioAction(arrange, ControllerAction.SECONDARY),
        )
    }

    @Test
    fun aBaBurstNeverReusesStaleSettlementUntilTheNewTargetCallback() {
        val memberA = HomeTomeMember(ids[0], "Alpha")
        val memberB = HomeTomeMember(ids[1], "Beta")
        val tome = HomeShelfTome(
            TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000088")),
            "Burst",
            emptyList(),
            2,
        )
        val base = HomeTomeFolioUiState(tome = tome, members = listOf(memberA, memberB))

        // Settled at A: activation is open.
        val settledAtA = base.copy(
            focusedIndex = 0,
            focusSettled = true,
            settledFocusTarget = HomeTomeFolioFocusTarget(0, memberA.focusKey),
        )
        assertEquals(
            HomeTomeFolioActionRequest.Open(memberA),
            routeHomeTomeFolioAction(settledAtA, ControllerAction.CONFIRM),
        )

        // A -> B: the synchronous unsettle fired; A's stale key is still latched app-side.
        val travellingToB = settledAtA.copy(focusedIndex = 1, focusSettled = false)
        assertEquals(
            HomeTomeFolioActionRequest.Consumed,
            routeHomeTomeFolioAction(travellingToB, ControllerAction.CONFIRM),
        )

        // B -> A while the return motion is still running: the stale A acknowledgment must NOT
        // re-open activation before the new target-specific settlement callback fires.
        val returningToA = travellingToB.copy(focusedIndex = 0)
        assertFalse(returningToA.focusSettledForActivation)
        assertEquals(
            HomeTomeFolioActionRequest.Consumed,
            routeHomeTomeFolioAction(returningToA, ControllerAction.CONFIRM),
        )
        assertEquals(
            HomeTomeFolioActionRequest.Consumed,
            routeHomeTomeFolioAction(returningToA, ControllerAction.TERTIARY),
        )

        // Hostile shape: a latch that was never cleared but acknowledges the WRONG target stays
        // consumed on the key gate alone.
        val staleKeyForB = returningToA.copy(
            focusSettled = true,
            settledFocusTarget = HomeTomeFolioFocusTarget(1, memberB.focusKey),
        )
        assertFalse(staleKeyForB.focusSettledForActivation)
        assertEquals(
            HomeTomeFolioActionRequest.Consumed,
            routeHomeTomeFolioAction(staleKeyForB, ControllerAction.CONFIRM),
        )

        // Only the new target-specific settlement callback re-opens A.
        val resettledAtA = returningToA.copy(
            focusSettled = true,
            settledFocusTarget = HomeTomeFolioFocusTarget(0, memberA.focusKey),
        )
        assertTrue(resettledAtA.focusSettledForActivation)
        assertEquals(
            HomeTomeFolioActionRequest.Open(memberA),
            routeHomeTomeFolioAction(resettledAtA, ControllerAction.CONFIRM),
        )

        // Legacy seam (no key wired) still trusts the boolean alone, so existing constructions hold.
        assertTrue(base.copy(focusSettled = true).focusSettledForActivation)
    }

    @Test
    fun unsettledCarriedTouchDropStaysPendingBehindTheSameGate() {
        val carried = HomeTomeMember(ids[0], "Carried")
        val other = HomeTomeMember(ids[1], "Other")
        val tome = HomeShelfTome(
            TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000089")),
            "Carry",
            emptyList(),
            2,
        )
        val carrying = HomeTomeFolioUiState(
            tome = tome,
            members = listOf(carried, other),
            // The reducer's live preview moved the carried identity to seat 1. Its FocusKey is
            // intentionally unchanged; only the typed seat distinguishes old from new settlement.
            memberSlots = listOf(other, carried),
            focusedIndex = 1,
            mode = HomeTomeFolioMode.ARRANGE,
            carriedItemId = carried.itemId,
            arrangeTransactionId = 7L,
            nextArrangeTransactionId = 8L,
            focusSettled = false,
        )

        // The carried drop consults the SAME target-specific gate as controller confirm: an
        // unsettled destination never commits — the composable keeps the carry pending instead.
        assertFalse(carrying.focusSettledForActivation)
        assertEquals(
            HomeTomeFolioActionRequest.Arrange(ControllerAction.CONFIRM),
            routeHomeTomeFolioAction(carrying, ControllerAction.CONFIRM),
        )

        // A stale acknowledgment for a DIFFERENT seat is equally blocked mid-carry.
        val staleSeat = carrying.copy(
            focusSettled = true,
            settledFocusTarget = HomeTomeFolioFocusTarget(0, carried.focusKey),
        )
        assertFalse(staleSeat.focusSettledForActivation)
        assertEquals(
            HomeTomeFolioActionRequest.Arrange(ControllerAction.CONFIRM),
            routeHomeTomeFolioAction(staleSeat, ControllerAction.CONFIRM),
        )

        // Once the painted destination settles, the pending drop may commit.
        val settledDestination = carrying.copy(
            focusSettled = true,
            settledFocusTarget = HomeTomeFolioFocusTarget(1, carried.focusKey),
        )
        assertTrue(settledDestination.focusSettledForActivation)
        assertEquals(
            HomeTomeFolioActionRequest.Arrange(ControllerAction.CONFIRM),
            routeHomeTomeFolioAction(settledDestination, ControllerAction.CONFIRM),
        )
    }

    @Test
    fun arrangeLiftSeat15DropSeat20CommitsExactReorderOnContinuousSheet() {
        val sheetIds = (0 until 24).map { HomeShelfItemId.Emulated(GameId("gba/sheet-$it")) }
        val baseline = beginHomeTomeArrange(sheetIds, 15)
        assertEquals(15, baseline.focusedIndex)
        assertEquals(2, homeTomeFolioRowOfIndex(15))
        assertEquals(3, homeTomeFolioRowOfIndex(20))
        assertTrue(baseline.workingSlots.size >= 24)

        val lifted = reduceHomeTomeArrange(
            baseline,
            HomeTomeArrangeIntent.Lift(15, baseline.nextTransactionId),
        ).state
        val transactionId = requireNotNull(lifted.activeTransactionId)
        assertEquals(15, lifted.focusedIndex)
        assertEquals(sheetIds[15], lifted.carriedItemId)

        val retargeted = reduceHomeTomeArrange(
            lifted,
            HomeTomeArrangeIntent.Retarget(20, transactionId),
        ).state
        assertEquals(20, retargeted.focusedIndex)

        val dropped = reduceHomeTomeArrange(
            retargeted,
            HomeTomeArrangeIntent.Drop(20, transactionId),
        )
        assertEquals(20, dropped.state.focusedIndex)
        assertNull(dropped.state.carriedItemId)
        val slots = requireNotNull(dropped.committedSlots)
        assertEquals(sheetIds[15], slots[20])
        assertEquals(sheetIds.size, slots.filterNotNull().size)
        val destination = homeTomeFolioDropDestination(seatIndex = 20, transactionId = transactionId, generation = 1L)
        assertEquals(2, destination.column)
        assertEquals(3, destination.row)
    }

    @Test
    fun touchAndControllerShareOnePathIndependentReorderTransaction() {
        val baseline = beginHomeTomeArrange(ids.take(4), 0)
        val controllerLift = reduceHomeTomeArrange(baseline, ControllerAction.CONFIRM).state
        val controllerMove = reduceHomeTomeArrange(controllerLift, ControllerAction.NAV_RIGHT).state
        val controllerDrop = reduceHomeTomeArrange(controllerMove, ControllerAction.CONFIRM)

        val touchLift = reduceHomeTomeArrange(
            baseline,
            HomeTomeArrangeIntent.Lift(0, baseline.nextTransactionId),
        ).state
        val touchTransactionId = requireNotNull(touchLift.activeTransactionId)
        val touchMove = reduceHomeTomeArrange(
            touchLift,
            HomeTomeArrangeIntent.Retarget(1, touchTransactionId),
        ).state
        val touchDrop = reduceHomeTomeArrange(
            touchMove,
            HomeTomeArrangeIntent.Drop(1, touchTransactionId),
        )

        assertEquals(controllerDrop.committedSlots, touchDrop.committedSlots)
        assertEquals(listOf(ids[1], ids[0], ids[2], ids[3]), touchDrop.committedOrder)
        assertNull(touchDrop.state.carriedItemId)

        val duplicate = reduceHomeTomeArrange(
            touchDrop.state,
            HomeTomeArrangeIntent.Drop(1, requireNotNull(touchDrop.committedTransactionId)),
        )
        assertNull(duplicate.committedSlots)
        assertNull(duplicate.state.carriedItemId)
    }

    @Test
    fun staleTouchTokenCannotRetargetANewerCarry() {
        val baseline = beginHomeTomeArrange(ids.take(4), 0)
        val first = reduceHomeTomeArrange(
            baseline,
            HomeTomeArrangeIntent.Lift(0, baseline.nextTransactionId),
        ).state
        val firstId = requireNotNull(first.activeTransactionId)
        val cancelled = reduceHomeTomeArrange(first, HomeTomeArrangeIntent.Cancel(firstId)).state
        val second = reduceHomeTomeArrange(
            cancelled,
            HomeTomeArrangeIntent.Lift(0, cancelled.nextTransactionId),
        ).state

        val stale = reduceHomeTomeArrange(second, HomeTomeArrangeIntent.Retarget(3, firstId))

        assertEquals(second, stale.state)
        assertEquals(0, stale.state.focusedIndex)
    }

    @Test
    fun prewarmRejectsRemoteUris() {
        assertTrue(isHomeTomeLocalArtUri("content://covers/one"))
        assertTrue(isHomeTomeLocalArtUri("file:///covers/one.png"))
        assertFalse(isHomeTomeLocalArtUri("https://example.test/cover.png"))
    }

    @Test
    fun carryPoseIdentityIsInvariantAcrossAVerticalPageCrossing() {
        val carrying = reduceHomeTomeArrange(beginHomeTomeArrange(ids, 17), ControllerAction.CONFIRM).state
        val poseAt17 = homeTomeFolioCarryPose(folioStateFromArrange(carrying))!!

        val down = reduceHomeTomeArrange(carrying, ControllerAction.NAV_DOWN).state
        val poseAt23 = homeTomeFolioCarryPose(folioStateFromArrange(down))!!

        assertEquals(poseAt17.itemId, poseAt23.itemId)
        assertEquals(poseAt17.transactionId, poseAt23.transactionId)
        assertEquals(5, poseAt17.column)
        assertEquals(5, poseAt23.column)
        assertEquals(2, poseAt17.row)
        assertEquals(3, poseAt23.row)
        assertTrue(poseAt17.sameCarryAs(poseAt23))

        val up = reduceHomeTomeArrange(down, ControllerAction.NAV_UP).state
        val poseBackAt17 = homeTomeFolioCarryPose(folioStateFromArrange(up))!!

        assertTrue(poseAt23.sameCarryAs(poseBackAt17))
        assertEquals(5, poseBackAt17.column)
        assertEquals(2, poseBackAt17.row)
    }

    @Test
    fun carryPoseIsTravelFreeStripSpaceMatchingTheSeatLaw() {
        val carrying = reduceHomeTomeArrange(beginHomeTomeArrange(ids, 0), ControllerAction.CONFIRM).state
        listOf(0, 5, 6, 11, 12, 17, 23).forEach { seat ->
            val state = folioStateFromArrange(retargetHomeTomeArrange(carrying, seat).state)
            val pose = homeTomeFolioCarryPose(state)!!
            assertEquals(homeTomeFolioColumnOfIndex(seat), pose.column)
            assertEquals(homeTomeFolioRowOfIndex(seat), pose.row)
        }
    }

    @Test
    fun staleOrAbsentTransactionProducesNoCarryPose() {
        val browse = HomeTomeFolioUiState(
            tome = testTome,
            members = ids.mapIndexed { index, id -> HomeTomeMember(id, "Game $index") },
        )
        assertNull(homeTomeFolioCarryPose(browse))

        val carrying = reduceHomeTomeArrange(beginHomeTomeArrange(ids, 17), ControllerAction.CONFIRM).state
        val state = folioStateFromArrange(carrying)
        val transactionId = requireNotNull(state.arrangeTransactionId)
        assertNull(homeTomeFolioCarryPose(state, transactionId = transactionId + 1))
        assertNotNull(homeTomeFolioCarryPose(state, transactionId = transactionId))
    }

    @Test
    fun dropDestinationPosePersistsThroughSettlementLifecycle() {
        val destination = homeTomeFolioDropDestination(seatIndex = 17, transactionId = 9L, generation = 3L)
        assertEquals(17, destination.seatIndex)
        assertEquals(9L, destination.transactionId)
        assertEquals(3L, destination.generation)
        assertEquals(5, destination.column)
        assertEquals(2, destination.row)
    }

    private fun dropFrameFromArrange(
        arrange: HomeTomeArrangeState,
        authorityFocused: FocusKey? = null,
        authorityScope: FocusScope = HomeTomeFolioScope,
        focusSettled: Boolean = true,
    ): HomeTomeFolioDropFrame {
        val state = folioStateFromArrange(arrange).copy(focusSettled = focusSettled)
        return HomeTomeFolioDropFrame(
            mode = state.mode,
            arrangeTransactionId = state.arrangeTransactionId,
            carriedItemId = state.carriedItemId,
            focusedIndex = state.focusedIndex,
            focusSettledForActivation = state.focusSettledForActivation,
            focusTarget = state.focusTarget,
            authorityScope = authorityScope,
            authorityFocused = authorityFocused,
        )
    }

    @Test
    fun dropLiftAwaitDistinguishesNotObservedYetFromPublishedTransaction() {
        val destination = homeTomeFolioDropDestination(seatIndex = 3, transactionId = 1L, generation = 7L)
        val sourceKey = homeTomeMemberFocusKey(ids[0])
        val observation = beginHomeTomeFolioDropObservation(
            destination = destination,
            sourceIndex = 0,
            sourceFocusKey = sourceKey,
            liftTransactionObserved = false,
        )
        val notYetObserved = stepHomeTomeFolioDropObservation(
            observation = observation,
            frame = HomeTomeFolioDropFrame(
                mode = HomeTomeFolioMode.BROWSE,
                arrangeTransactionId = null,
                carriedItemId = null,
                focusedIndex = 0,
                focusSettledForActivation = true,
                focusTarget = null,
                authorityScope = HomeTomeFolioScope,
                authorityFocused = sourceKey,
            ),
            deltaMs = 16L,
        )
        assertNotNull(notYetObserved.observation)
        assertEquals(HomeTomeFolioDropPhase.AwaitingLiftTransaction, notYetObserved.observation!!.phase)

        val liftPublished = stepHomeTomeFolioDropObservation(
            observation = notYetObserved.observation!!,
            frame = HomeTomeFolioDropFrame(
                mode = HomeTomeFolioMode.ARRANGE,
                arrangeTransactionId = 1L,
                carriedItemId = ids[0],
                focusedIndex = 0,
                focusSettledForActivation = true,
                focusTarget = HomeTomeFolioFocusTarget(0, sourceKey),
                authorityScope = HomeTomeFolioScope,
                authorityFocused = sourceKey,
            ),
        )
        assertEquals(HomeTomeFolioDropPhase.AwaitingDestinationSettlement, liftPublished.observation!!.phase)
    }

    @Test
    fun dropObservationCommitsAfterDestinationAndAuthoritySettlement() {
        val destination = homeTomeFolioDropDestination(seatIndex = 3, transactionId = 1L, generation = 1L)
        val sourceKey = homeTomeMemberFocusKey(ids[0])
        val targetKey = homeTomeSeatFocusKey(testTome.tomeId, 3)
        var observation = beginHomeTomeFolioDropObservation(
            destination = destination,
            sourceIndex = 0,
            sourceFocusKey = sourceKey,
            liftTransactionObserved = true,
        )
        val atDestination = stepHomeTomeFolioDropObservation(
            observation = observation,
            frame = HomeTomeFolioDropFrame(
                mode = HomeTomeFolioMode.ARRANGE,
                arrangeTransactionId = 1L,
                carriedItemId = ids[0],
                focusedIndex = 3,
                focusSettledForActivation = true,
                focusTarget = HomeTomeFolioFocusTarget(3, targetKey),
                authorityScope = HomeTomeFolioScope,
                authorityFocused = sourceKey,
            ),
        )
        assertTrue(atDestination.observation!!.destinationObserved)
        assertNull(atDestination.effect)

        val dropped = stepHomeTomeFolioDropObservation(
            observation = atDestination.observation!!,
            frame = HomeTomeFolioDropFrame(
                mode = HomeTomeFolioMode.ARRANGE,
                arrangeTransactionId = 1L,
                carriedItemId = ids[0],
                focusedIndex = 3,
                focusSettledForActivation = true,
                focusTarget = HomeTomeFolioFocusTarget(3, targetKey),
                authorityScope = HomeTomeFolioScope,
                authorityFocused = targetKey,
            ),
        )
        assertEquals(HomeTomeFolioDropEffect.Drop(3, 1L), dropped.effect)
    }

    @Test
    fun dropObservationLiftTimeoutCancelsItsOwnedTransaction() {
        val destination = homeTomeFolioDropDestination(seatIndex = 1, transactionId = 4L, generation = 2L)
        var observation = beginHomeTomeFolioDropObservation(
            destination = destination,
            sourceIndex = 0,
            sourceFocusKey = homeTomeMemberFocusKey(ids[0]),
            liftTransactionObserved = false,
        )
        var step = HomeTomeFolioDropStep(observation = observation)
        var elapsed = 0L
        while (step.observation != null && elapsed < HomeTomeTouchLiftAwaitMs) {
            step = stepHomeTomeFolioDropObservation(
                observation = step.observation!!,
                frame = HomeTomeFolioDropFrame(
                    mode = HomeTomeFolioMode.BROWSE,
                    arrangeTransactionId = null,
                    carriedItemId = null,
                    focusedIndex = 0,
                    focusSettledForActivation = true,
                    focusTarget = null,
                    authorityScope = HomeTomeFolioScope,
                    authorityFocused = homeTomeMemberFocusKey(ids[0]),
                ),
                deltaMs = 32L,
            )
            elapsed += 32L
        }
        assertNull(step.observation)
        assertEquals(HomeTomeFolioDropEffect.CancelOwned(4L), step.effect)
    }

    @Test
    fun dropObservationUnexpectedDestinationCancelsItsOwnedTransaction() {
        val carrying = reduceHomeTomeArrange(beginHomeTomeArrange(ids, 0), ControllerAction.CONFIRM).state
        val moved = retargetHomeTomeArrange(carrying, 5).state
        val destination = homeTomeFolioDropDestination(seatIndex = 3, transactionId = 1L, generation = 1L)
        val observation = beginHomeTomeFolioDropObservation(
            destination = destination,
            sourceIndex = 0,
            sourceFocusKey = homeTomeMemberFocusKey(ids[0]),
            liftTransactionObserved = true,
        )
        val step = stepHomeTomeFolioDropObservation(
            observation = observation,
            frame = dropFrameFromArrange(moved, authorityFocused = homeTomeMemberFocusKey(ids[0])),
        )
        assertNull(step.observation)
        assertEquals(HomeTomeFolioDropEffect.CancelOwned(1L), step.effect)
    }

    @Test
    fun dropObservationAuthoritySupersessionCancelsItsOwnedTransaction() {
        val carrying = reduceHomeTomeArrange(beginHomeTomeArrange(ids, 0), ControllerAction.CONFIRM).state
        val moved = retargetHomeTomeArrange(carrying, 3).state
        val destination = homeTomeFolioDropDestination(seatIndex = 3, transactionId = 1L, generation = 1L)
        val observation = beginHomeTomeFolioDropObservation(
            destination = destination,
            sourceIndex = 0,
            sourceFocusKey = homeTomeMemberFocusKey(ids[0]),
            liftTransactionObserved = true,
        )
        val settledFrame = dropFrameFromArrange(
            moved,
            authorityFocused = requireNotNull(dropFrameFromArrange(moved).focusTarget).key,
            focusSettled = false,
        )
        val atDestination = stepHomeTomeFolioDropObservation(
            observation = observation,
            frame = settledFrame,
        )
        val superseded = stepHomeTomeFolioDropObservation(
            observation = atDestination.observation!!,
            frame = settledFrame.copy(authorityFocused = homeTomeMemberFocusKey(ids[5])),
        )
        assertNull(superseded.observation)
        assertEquals(HomeTomeFolioDropEffect.CancelOwned(1L), superseded.effect)
    }

    @Test
    fun pendingDropClearsOnlyForMatchingGenerationAfterStableFrame() {
        val destination = homeTomeFolioDropDestination(seatIndex = 3, transactionId = 1L, generation = 4L)
        assertFalse(
            shouldClearHomeTomeFolioPendingDrop(
                pending = destination,
                activeGestureGeneration = 3L,
                focusedIndex = 3,
                focusSettled = true,
                carriedCleared = true,
            ),
        )
        assertTrue(
            shouldClearHomeTomeFolioPendingDrop(
                pending = destination,
                activeGestureGeneration = 4L,
                focusedIndex = 3,
                focusSettled = true,
                carriedCleared = true,
            ),
        )
        assertEquals(
            destination,
            relinquishHomeTomeFolioPendingDrop(destination, gestureGeneration = 3L),
        )
        assertNull(relinquishHomeTomeFolioPendingDrop(destination, gestureGeneration = 4L))
    }

    @Test
    fun stableFrameClearMustRelinquishLivePendingNotStaleCapture() {
        val staleCapture = homeTomeFolioDropDestination(seatIndex = 3, transactionId = 1L, generation = 4L)
        val live = homeTomeFolioDropDestination(seatIndex = 5, transactionId = 2L, generation = 4L)
        assertNull(relinquishHomeTomeFolioPendingDrop(staleCapture, gestureGeneration = 4L))
        assertSame(live, relinquishHomeTomeFolioPendingDrop(live, gestureGeneration = 3L))
    }

    @Test
    fun gestureGenerationNClearsPendingBeforeNextGenerationBecomesActive() {
        val generationN = 8L
        val pendingN = homeTomeFolioDropDestination(
            seatIndex = 2,
            transactionId = 3L,
            generation = generationN,
        )
        val pendingNPlusOne = homeTomeFolioDropDestination(
            seatIndex = 5,
            transactionId = 4L,
            generation = generationN + 1,
        )
        assertNull(relinquishHomeTomeFolioPendingDrop(pendingN, gestureGeneration = generationN))
        assertSame(
            pendingNPlusOne,
            relinquishHomeTomeFolioPendingDrop(pendingNPlusOne, gestureGeneration = generationN),
        )
    }

    @Test
    fun dropLiftAwaitTimesOutAtExactBudgetWithArbitraryDeltas() {
        val destination = homeTomeFolioDropDestination(seatIndex = 1, transactionId = 2L, generation = 1L)
        val observation = beginHomeTomeFolioDropObservation(
            destination = destination,
            sourceIndex = 0,
            sourceFocusKey = homeTomeMemberFocusKey(ids[0]),
            liftTransactionObserved = false,
        )
        val browseFrame = HomeTomeFolioDropFrame(
            mode = HomeTomeFolioMode.BROWSE,
            arrangeTransactionId = null,
            carriedItemId = null,
            focusedIndex = 0,
            focusSettledForActivation = true,
            focusTarget = null,
            authorityScope = HomeTomeFolioScope,
            authorityFocused = homeTomeMemberFocusKey(ids[0]),
        )
        val afterSeven = stepHomeTomeFolioDropObservation(
            observation = observation,
            frame = browseFrame,
            deltaMs = 7L,
        )
        assertNotNull(afterSeven.observation)
        val afterRemainder = stepHomeTomeFolioDropObservation(
            observation = afterSeven.observation!!,
            frame = browseFrame,
            deltaMs = HomeTomeTouchLiftAwaitMs - 7L,
        )
        assertNull(afterRemainder.observation)
        assertEquals(HomeTomeFolioDropEffect.CancelOwned(2L), afterRemainder.effect)
    }

    @Test
    fun destinationSettlementUsesTheBoundedDropLawAndCancelAllowsTheNextLift() {
        val carrying = reduceHomeTomeArrange(beginHomeTomeArrange(ids.take(4), 0), ControllerAction.CONFIRM).state
        val transactionId = requireNotNull(carrying.activeTransactionId)
        val destination = homeTomeFolioDropDestination(
            seatIndex = 1,
            transactionId = transactionId,
            generation = 1L,
        )
        val observation = beginHomeTomeFolioDropObservation(
            destination = destination,
            sourceIndex = 0,
            sourceFocusKey = homeTomeMemberFocusKey(ids[0]),
            liftTransactionObserved = true,
        )
        val unsettled = dropFrameFromArrange(
            arrange = carrying,
            authorityFocused = homeTomeMemberFocusKey(ids[0]),
            focusSettled = false,
        )
        val beforeDeadline = stepHomeTomeFolioDropObservation(
            observation = observation,
            frame = unsettled,
            deltaMs = HomeTomeTouchDropSettleMs - 1L,
        )
        assertNotNull(beforeDeadline.observation)
        assertNull(beforeDeadline.effect)
        val timedOut = stepHomeTomeFolioDropObservation(
            observation = beforeDeadline.observation!!,
            frame = unsettled,
            deltaMs = 1L,
        )
        assertNull(timedOut.observation)
        assertEquals(HomeTomeFolioDropEffect.CancelOwned(transactionId), timedOut.effect)

        val cancelled = reduceHomeTomeArrange(
            carrying,
            HomeTomeArrangeIntent.Cancel(transactionId),
        ).state
        assertNull(cancelled.carriedItemId)
        val nextLift = reduceHomeTomeArrange(
            cancelled,
            HomeTomeArrangeIntent.Lift(1, cancelled.nextTransactionId),
        ).state
        assertEquals(ids[1], nextLift.carriedItemId)
        assertNotEquals(transactionId, nextLift.activeTransactionId)
    }

    @Test
    fun staleDropFinallyKeepsItsOwnTransactionAndCannotCancelTheReplacementLift() {
        val firstLift = reduceHomeTomeArrange(
            beginHomeTomeArrange(ids.take(4), 0),
            ControllerAction.CONFIRM,
        ).state
        val firstTransactionId = requireNotNull(firstLift.activeTransactionId)
        val oldDrop = HomeTomeTouchDropOwnership(
            transactionId = firstTransactionId,
            gestureGeneration = 8L,
            liftPublished = true,
        )
        val afterFirstCancel = reduceHomeTomeArrange(
            firstLift,
            requireNotNull(homeTomeTouchDropCancelEffect(oldDrop)).let {
                HomeTomeArrangeIntent.Cancel(it.transactionId)
            },
        ).state
        val replacementLift = reduceHomeTomeArrange(
            afterFirstCancel,
            HomeTomeArrangeIntent.Lift(1, afterFirstCancel.nextTransactionId),
        ).state
        val replacementTransactionId = requireNotNull(replacementLift.activeTransactionId)
        assertNotEquals(firstTransactionId, replacementTransactionId)

        // The old job's finally runs after generation 9 has lifted. Its immutable ownership still
        // emits transaction 1, which the reducer rejects rather than cancelling transaction 2.
        assertFalse(
            homeTomeTouchDropMayClearSharedOwnership(
                ownership = oldDrop,
                activeGestureGeneration = 9L,
            ),
        )
        // Supersession already claimed this old owner's one terminal cancellation.
        assertNull(homeTomeTouchDropCancelEffect(oldDrop))
        assertEquals(replacementTransactionId, replacementLift.activeTransactionId)
        assertNotNull(replacementLift.carriedItemId)
    }

    @Test
    fun unpublishedOldDropFinallyCannotCancelReplacementLiftReusingPredictedId() {
        val baseline = beginHomeTomeArrange(ids.take(4), 0)
        val predictedId = baseline.nextTransactionId
        val unpublishedOldDrop = HomeTomeTouchDropOwnership(
            transactionId = predictedId,
            gestureGeneration = 8L,
        )

        // Gesture N releases and is superseded before its Lift intent publishes. Therefore the
        // reducer still owns the same next ID, which gesture N+1 legitimately publishes.
        assertFalse(unpublishedOldDrop.liftPublished)
        val replacementLift = reduceHomeTomeArrange(
            baseline,
            HomeTomeArrangeIntent.Lift(1, predictedId),
        ).state
        assertEquals(predictedId, replacementLift.activeTransactionId)
        assertNotNull(replacementLift.carriedItemId)

        // The old cancelled job/finally runs after replacement publication. No cancellation exists
        // without the old job's own publication witness, even though the numeric ID now matches.
        assertNull(homeTomeTouchDropCancelEffect(unpublishedOldDrop))
        assertFalse(
            homeTomeTouchDropMayClearSharedOwnership(
                ownership = unpublishedOldDrop,
                activeGestureGeneration = 9L,
            ),
        )
        assertEquals(predictedId, replacementLift.activeTransactionId)
        assertNotNull(replacementLift.carriedItemId)
    }

    @Test
    fun gestureSupersessionRelinquishesOnlyMatchingGenerationPending() {
        val stale = homeTomeFolioDropDestination(seatIndex = 0, transactionId = 1L, generation = 2L)
        val live = homeTomeFolioDropDestination(seatIndex = 4, transactionId = 2L, generation = 3L)
        var pending: HomeTomeFolioDropDestination? = stale
        pending = relinquishHomeTomeFolioPendingDrop(pending, gestureGeneration = 2L)
        assertNull(pending)
        pending = live
        pending = relinquishHomeTomeFolioPendingDrop(pending, gestureGeneration = 2L)
        assertSame(live, pending)
    }

    @Test
    fun outsideReleaseCancelsOnlyStillOwnedTransaction() {
        assertEquals(
            HomeTomeFolioDropEffect.CancelOwned(9L),
            homeTomeFolioOutsideReleaseEffect(9L),
        )
        assertNull(homeTomeFolioOutsideReleaseEffect(null))
    }

    @Test
    fun stripCoordinateFoldingKeepsPointerAndSeatedPaintAligned() {
        val stride = 85f
        val cell = 75f
        val ring = 14f
        val travel = 2.5f
        val gridLeft = 40f
        val pointerX = 100f
        val stripLeft = homeTomeFolioStripCellLeftX(pointerX, travel, ring, cell, stride)
        val seatedStripLeft = homeTomeFolioStripSeatedLeftX(column = 3, stride)
        val paintedPointer = homeTomeFolioPaintLeftX(stripLeft, gridLeft, travel, stride)
        val paintedSeated = homeTomeFolioPaintLeftX(seatedStripLeft, gridLeft, travel, stride)
        assertEquals(paintedPointer, gridLeft + pointerX - ring - cell / 2f, 0.001f)
        assertEquals(paintedSeated, gridLeft + 3f * stride - travel * stride, 0.001f)
    }

    @Test
    fun carryPoseSurvivesCancelAndDropSettlementSymmetrically() {
        val baseline = beginHomeTomeArrange(ids, 17)
        val carrying = reduceHomeTomeArrange(baseline, ControllerAction.CONFIRM).state
        val down = reduceHomeTomeArrange(carrying, ControllerAction.NAV_DOWN).state

        val cancelled = reduceHomeTomeArrange(down, ControllerAction.CANCEL).state
        assertNull(homeTomeFolioCarryPose(folioStateFromArrange(cancelled)))
        assertEquals(ids, cancelled.workingOrder)

        val dropped = reduceHomeTomeArrange(down, ControllerAction.CONFIRM)
        assertNull(homeTomeFolioCarryPose(folioStateFromArrange(dropped.state)))
        assertEquals(ids[17], requireNotNull(dropped.committedSlots)[23])
    }

    @Test
    fun seatInvariantsStillFireAfterAValidPairHasBeenMemoized() {
        val members = ids.take(3).mapIndexed { index, id -> HomeTomeMember(id, "Game $index", null, null) }
        val slots: List<HomeTomeMember?> = members

        // Prove a good pair once, then again through the memo — same instances, same verdict.
        HomeTomeFolioUiState(tome = testTome, members = members, memberSlots = slots)
        HomeTomeFolioUiState(tome = testTome, members = members, memberSlots = slots)

        // A DIFFERENT pair must still be walked: the memo removes repeat work, never a check.
        val duplicated: List<HomeTomeMember?> = listOf(members[0], members[0], members[2])
        assertThrows(IllegalArgumentException::class.java) {
            HomeTomeFolioUiState(
                tome = testTome,
                members = listOf(members[0], members[2]),
                memberSlots = duplicated,
            )
        }
        // Slots that do not cover the member set are still rejected.
        assertThrows(IllegalArgumentException::class.java) {
            HomeTomeFolioUiState(
                tome = testTome,
                members = members,
                memberSlots = listOf(members[0], members[1]),
            )
        }
        // And a good pair still validates after a rejection, so a failed check cannot poison the memo.
        HomeTomeFolioUiState(tome = testTome, members = members, memberSlots = slots)
    }

    // ── Cross-page handoff: pre-render, coalescing, motion-derived settlement ─────────────────────

    @Test
    fun outgoingLeafIsPaintedForExactlyTheHandoffMotionWindow() {
        val handoff = retargetHomeTomePageTransition(HomeTomePageTransition(settledPage = 0), 1)

        fun paints(offset: Float, running: Boolean, reduced: Boolean = false) =
            homeTomeFolioPaintsOutgoingLeaf(
                outgoingPage = handoff.outgoingPage,
                settledPage = handoff.settledPage,
                pageOffset = offset,
                pageMotionRunning = running,
                reducedMotion = reduced,
            )

        // Travel in flight — the page we just left backs the destination while its art hydrates.
        assertTrue(paints(offset = 1f, running = true))
        assertTrue(paints(offset = 0.4f, running = true))
        // Still mid-travel but momentarily idle-at-a-nonzero value: a hole here is the blank frame.
        assertTrue(paints(offset = 0.4f, running = false))
        // Settled: exactly one leaf composed again, even though the model keeps its residue until the
        // next interaction clears it.
        assertFalse(paints(offset = 0f, running = false))
        // Reduced motion has no handoff window at all, so it never pays for a second leaf.
        assertFalse(paints(offset = 1f, running = true, reduced = true))
        // No handoff pending.
        assertFalse(
            homeTomeFolioPaintsOutgoingLeaf(
                outgoingPage = null,
                settledPage = 1,
                pageOffset = 1f,
                pageMotionRunning = true,
                reducedMotion = false,
            ),
        )
        // A degenerate transition that points at itself is not a handoff.
        assertFalse(
            homeTomeFolioPaintsOutgoingLeaf(
                outgoingPage = 2,
                settledPage = 2,
                pageOffset = 1f,
                pageMotionRunning = true,
                reducedMotion = false,
            ),
        )
    }

    @Test
    fun outgoingLeafCrossDissolvesInsteadOfCuttingToTheDestination() {
        assertEquals(1f, homeTomeFolioOutgoingLeafAlpha(1f), 0.0001f)
        assertEquals(1f, homeTomeFolioOutgoingLeafAlpha(-1f), 0.0001f)
        assertEquals(0.5f, homeTomeFolioOutgoingLeafAlpha(0.5f), 0.0001f)
        assertEquals(0f, homeTomeFolioOutgoingLeafAlpha(0f), 0.0001f)
        // Never over-opaque if the spring/tween momentarily overshoots its leaf.
        assertEquals(1f, homeTomeFolioOutgoingLeafAlpha(1.4f), 0.0001f)
    }

    @Test
    fun pageMotionStartAdvancesByDirectionAndSaturatesAtOneLeaf() {
        assertEquals(0f, homeTomeFolioPageMotionStart(0.6f, 0), 0.0001f)
        assertEquals(1f, homeTomeFolioPageMotionStart(0f, 1), 0.0001f)
        assertEquals(1f, homeTomeFolioPageMotionStart(0.6f, 1), 0.0001f)
        assertEquals(1f, homeTomeFolioPageMotionStart(0.9f, 1), 0.0001f)
        assertEquals(-0.4f, homeTomeFolioPageMotionStart(0.6f, -1), 0.0001f)
        assertEquals(-1f, homeTomeFolioPageMotionStart(-0.6f, -1), 0.0001f)
    }

    @Test
    fun composedPageOffsetHoldsStartUntilAnimatedGenerationAcknowledgesCrossing() {
        assertEquals(1f, homeTomeFolioComposedPageOffset(3L, 2L, liveOffset = 0.4f, startOffset = 1f), 0.0001f)
        assertEquals(0.4f, homeTomeFolioComposedPageOffset(3L, 3L, liveOffset = 0.4f, startOffset = 1f), 0.0001f)
        assertEquals(-0.5f, homeTomeFolioComposedPageOffset(5L, 5L, liveOffset = -0.5f, startOffset = -1f), 0.0001f)
        assertEquals(1f, homeTomeFolioComposedPageOffset(3L, 4L, liveOffset = 0f, startOffset = 1f), 0.0001f)
    }

    @Test
    fun crossingFrameStartSettlesAfterGenerationAcknowledgement() {
        val transitionGeneration = 3L
        val startOffset = homeTomeFolioPageMotionStart(liveOffset = 0f, direction = 1)
        assertEquals(1f, startOffset, 0.0001f)
        val crossingOffset = homeTomeFolioComposedPageOffset(
            transitionGeneration = transitionGeneration,
            animatedGeneration = 2L,
            liveOffset = 0f,
            startOffset = startOffset,
        )
        assertEquals(1f, crossingOffset, 0.0001f)
        assertFalse(
            homeTomeFolioPageMotionSettled(
                reducedMotion = false,
                pageOffset = crossingOffset,
                pageMotionRunning = false,
            ),
        )
        val acknowledgedOffset = homeTomeFolioComposedPageOffset(
            transitionGeneration = transitionGeneration,
            animatedGeneration = transitionGeneration,
            liveOffset = 0f,
            startOffset = startOffset,
        )
        assertEquals(0f, acknowledgedOffset, 0.0001f)
        assertTrue(
            homeTomeFolioPageMotionSettled(
                reducedMotion = false,
                pageOffset = acknowledgedOffset,
                pageMotionRunning = false,
            ),
        )
    }

    @Test
    fun settlementStaysClosedOnCrossingUntilOffsetReturnsToZero() {
        assertFalse(homeTomeFolioPageMotionSettled(false, pageOffset = 1f, pageMotionRunning = false))
        assertFalse(homeTomeFolioPageMotionSettled(false, pageOffset = 0f, pageMotionRunning = true))
        assertTrue(homeTomeFolioPageMotionSettled(false, pageOffset = 0f, pageMotionRunning = false))
    }

    @Test
    fun oneSheetContinuityAndReversalAdvanceWithoutSameSignCoalescing() {
        assertEquals(1f, homeTomeFolioPageMotionStart(0f, 1), 0.0001f)
        assertEquals(1f, homeTomeFolioPageMotionStart(1f, 1), 0.0001f)
        assertEquals(0f, homeTomeFolioPageMotionStart(1f, -1), 0.0001f)
        assertEquals(-1f, homeTomeFolioPageMotionStart(0f, -1), 0.0001f)
    }

    @Test
    fun outgoingLeafShiftIsOneFullTravelOppositeIncomingDirection() {
        assertEquals(-216f, homeTomeFolioOutgoingLeafShift(direction = 1, travelDistance = 216f), 0.0001f)
        assertEquals(216f, homeTomeFolioOutgoingLeafShift(direction = -1, travelDistance = 216f), 0.0001f)
        assertEquals(0f, homeTomeFolioOutgoingLeafShift(direction = 0, travelDistance = 216f), 0.0001f)
    }

    @Test
    fun outgoingLeafNetTravelCancelsWithParentGridAtCrossingStart() {
        val travelDistance = 216f
        val direction = 1
        val pageOffset = homeTomeFolioPageMotionStart(liveOffset = 0f, direction = direction)
        val parentTranslation = pageOffset * travelDistance
        val childTranslation = homeTomeFolioOutgoingLeafShift(direction, travelDistance)
        assertEquals(0f, parentTranslation + childTranslation, 0.0001f)
    }

    @Test
    fun pageMotionOwnsTheOverlayYUntilItsExactRestFrame() {
        assertTrue(homeTomeFolioPageOwnsOverlayY(pageOffset = 0.5f, pageMotionRunning = false))
        assertTrue(homeTomeFolioPageOwnsOverlayY(pageOffset = 0f, pageMotionRunning = true))
        assertFalse(homeTomeFolioPageOwnsOverlayY(pageOffset = 0f, pageMotionRunning = false))
    }

    @Test
    fun settlementTailIsDerivedFromTheMotionItWaitsOn() {
        // Page handoff: the SceneMs travel already outlasts the concurrent FocusMs seat tween, so any
        // extra pad is dead time on the input path.
        assertEquals(0L, homeTomeFolioSettleTailMillis(pageHandoff = true, focusMillis = 140, sceneMillis = 160))
        assertEquals(0L, homeTomeFolioSettleTailMillis(pageHandoff = true, focusMillis = 140, sceneMillis = 140))
        // Horizontal-only travel has no such cover — the seat tween is still waited out.
        assertEquals(140L, homeTomeFolioSettleTailMillis(pageHandoff = false, focusMillis = 140, sceneMillis = 160))
        // If the scene motion were ever made SHORTER than the seat tween the cover claim stops holding,
        // and the tail comes back rather than releasing input over still-moving cells.
        assertEquals(140L, homeTomeFolioSettleTailMillis(pageHandoff = true, focusMillis = 140, sceneMillis = 100))
    }

    @Test
    fun aCoalescedBurstStillLandsOnTheNewestPageAndOneOutgoingLeaf() {
        // 0 → 1 → 2 without settling: the destination is always the newest page, and exactly one page is
        // ever the backdrop, so a hold can never stack leaves.
        val first = retargetHomeTomePageTransition(HomeTomePageTransition(settledPage = 0), 1)
        val second = retargetHomeTomePageTransition(first, 2)

        assertEquals(2, second.settledPage)
        assertEquals(1, second.outgoingPage)
        assertEquals(first.generation + 1L, second.generation)
        assertEquals(1, homeTomeFolioVerticalPageDirection(second))

        // Same-page retarget is a no-op, so a repeat that does not cross cannot restart the motion.
        assertSame(second, retargetHomeTomePageTransition(second, 2))
    }

    @Test
    fun verticalPageTravelPreservesTheFocusedRowsPhysicalPosition() {
        assertEquals(216f, homeTomeFolioPageTravelDistance(100f, 8f, visibleRows = 3), 0.0001f)
        assertEquals(108f, homeTomeFolioPageTravelDistance(100f, 8f, visibleRows = 2), 0.0001f)
        assertEquals(0f, homeTomeFolioPageTravelDistance(100f, 8f, visibleRows = 1), 0.0001f)
    }
}
