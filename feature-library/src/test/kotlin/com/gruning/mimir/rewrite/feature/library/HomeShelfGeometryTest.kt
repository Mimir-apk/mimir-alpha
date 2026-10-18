package com.gruning.mimir.rewrite.feature.library

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShelfGeometryTest {
    //  Root cause: Library's Column read a flat ShelfBottomPaddingDp=16 constant while Home solved
    // its rail placement from a height-scaled clamp -- 8.856dp apart on the Odin's certified 833x444 fixture.
    // Library now reads homeShelfQuickRailBottomGapDp directly (LibraryScreen.kt's solveLibraryShelfGeometry).
    @Test
    fun homeAndLibraryDocksShareTheSameBottomGap() {
        // The certified Odin fixture (post-inset), matched against the measured pre-fix delta so a future
        // change to either formula that reopens the gap fails loudly instead of silently.
        assertEquals(8.856f, homeShelfQuickRailBottomGapDp(444f), 0.01f)
        // Compares the two FULL solvers end-to-end -- not "Library equals the helper it happens to call
        // today" -- so this stays a real cross-surface pin even if a future edit stops Home routing its own
        // quickRailBand through the shared helper.
        listOf(700f to 350f, 833f to 444f, 1080f to 1080f).forEach { (width, height) ->
            val homeGeometry = solveHomeShelfGeometry(width, height)
            val homeBottomGap = height - homeGeometry.quickRailBand.bottom
            val libraryBottomGap = solveLibraryShelfGeometry(width, height).bottomPaddingDp
            assertEquals(
                "$width x $height dock bottom gap must match between Home and Library",
                homeBottomGap,
                libraryBottomGap,
                0.01f,
            )
        }
    }

    @Test
    fun homeShelfOutgoingLayerUsesTheBoundedReferenceWindow() {
        assertTrue(HomeShelfOutgoingFadeMs in 140..220)
        assertEquals(0.68f, HomeShelfOutgoingStartAlpha, 0f)
    }

    /**
     * the achievement count lives on the launch card only, so Main's hero has no count seat. A
     * status-bearing game used to mount a blank 64 dp seat beside the chip (and reserve 190 dp of title width for
     * chip + seat); neither exists now. Pinned on the source, since the seat's constants are gone with it.
     */
    @Test
    fun homeShelfHasNoAchievementSeatMountOrReservation() {
        val source = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt"),
        ).first { it.isFile }.readText()
        listOf(
            "HomeShelfAchievementProgress",
            "HomeShelfAchievementSlot",
            "HomeShelfAchievementPresence",
            "HomeShelfAchievementTrailingReserve",
            "achievementCount",
            "hasAchievementCount",
            "AchievementProgressLabel(",
        ).forEach { gone -> assertFalse("HomeShelfScreen still mentions $gone", source.contains(gone)) }
    }

    /**
     * The title's width before and after the seat left, per surface: [titleBand] minus the trailing reserve. The 68 dp
     * the seat took (64 dp plus the chip row's 4 dp gap) is the title's again whenever a status chip shows.
     */
    @Test
    fun theTitleGetsTheSeatsWidthBackBesideAStatusChip() {
        assertEquals(122f, HomeShelfStatusChipTrailingReserve.value, 0.01f)
        assertEquals(190f - 68f, HomeShelfStatusChipTrailingReserve.value, 0.01f)
        listOf(
            Triple(832.5f, 468.3f, false), // Thor Main
            Triple(700f, 350f, false),
            Triple(564.7f, 540.7f, true), // the compact RG square
            Triple(320f, 300f, true), // the floor
        ).forEach { (width, height, compact) ->
            val band = solveHomeShelfGeometry(width, height, compact).titleBand.width
            val before = band - 190f
            val after = band - homeShelfTitleTrailingReserve(hasStatusChip = true).value
            println("HOME-GEO $width x $height compact=$compact titleBand=${band}dp  titleMaxWidth(with chip) before=${before}dp after=${after}dp  chipReserve 190->${homeShelfTitleTrailingReserve(hasStatusChip = true).value}dp  lockupGap 2->4dp")
            assertEquals("$width x $height gains exactly the seat's 68 dp", 68f, after - before, 0.01f)
        }
        assertEquals(0f, homeShelfTitleTrailingReserve(hasStatusChip = false).value, 0.01f)
    }

    @Test
    fun bottomLeftFooterContainsOnlyTheGameCountLane() {
        assertEquals(104f, HomeShelfGameCountPaintWidth.value, 0.01f)
        assertEquals(104f, HomeShelfTomeShortcutHintPaintWidth.value, 0.01f)
        assertEquals(18f, HomeShelfGameCountLaneHeight.value, 0.01f)
        listOf(
            320f to 300f,
            411f to 411f,
            700f to 350f,
            833f to 444f,
            1080f to 1080f,
        ).forEach { (width, height) ->
            val geometry = solveHomeShelfGeometry(width, height)
            assertTrue(
                "$width x $height game count must fit the quick-rail band",
                HomeShelfGameCountLaneHeight.value <= geometry.quickRailBand.height,
            )
            assertTrue(
                "$width x $height Tome hint must remain inside the viewport",
                geometry.viewport.right - geometry.gutter - HomeShelfTomeShortcutHintPaintWidth.value >= 0f,
            )
        }
    }

    // A Retroid Pocket Nova capture showed "X · TOME" against FILES with no separation. The hint
    // is anchored a fixed distance from the right edge while a centered rail narrows at half that
    // rate only after hitting physical bounds. mimirQuickRailTargetWidth narrows the target sooner;
    // see homeShelfTomeShortcutHintClearanceDp for the mechanism.
    @Test
    fun tomeShortcutHintClearanceIsPositiveAtEveryCertifiedDeviceAfterTheRailResizeFix() {
        val nova = solveHomeShelfGeometry(640f, 480f)
        assertTrue(
            "Retroid Pocket Nova (640x480dp) must now clear -- if this goes negative again, the rail's " +
                "target-width fix regressed",
            homeShelfTomeShortcutHintClearanceDp(nova) > 0f,
        )
        assertNear(12.16f, homeShelfTomeShortcutHintClearanceDp(nova))

        // Exact certified Odin dp (OnboardingScreenFitTest's own fixture): 1920x1080@369dpi -> 832.5x468.3dp.
        val odin = solveHomeShelfGeometry(832.5f, 468.3f)
        assertTrue(
            "Odin (832.5x468.3dp) must stay clear -- this, not a different code path, is WHY the " +
                "defect never showed on the Odin",
            homeShelfTomeShortcutHintClearanceDp(odin) > 0f,
        )
        // BYTE-IDENTICAL to the pre-fix number: the Odin's target resolves above RailWidth (see
        // MimirFinalShelfPrimitivesTest), so its rail -- and therefore this clearance -- never moved.
        assertNear(22.44f, homeShelfTomeShortcutHintClearanceDp(odin))

        // RP5 (700x350dp): not the reported device, but broken today too (-39.7dp, uncaptured until this
        // fix) and repaired as a side effect of the same rule -- the family, not just the one report.
        val rp5 = solveHomeShelfGeometry(700f, 350f)
        assertTrue(
            "RP5 (700x350dp) must also clear now -- this fix repairs the family, not one report",
            homeShelfTomeShortcutHintClearanceDp(rp5) > 0f,
        )
        assertNear(10.3f, homeShelfTomeShortcutHintClearanceDp(rp5))
    }

    // RE-MEASURED 2026-08-21, per this test's own instruction at the time ("if this goes non-negative,
    // the remaining OWED range either shrank (note it) or this pin is stale"): the 538dp companion's
    // clearance DID go non-negative, and the range shrank -- this is the "note it" case, not a stale pin.
    // Cause: the equal-seat-width change retired QUICK_
    // LAUNCH's/FILES's differentiated widths, and MimirRunicQuickRailDefaults.MinTargetRailWidth was
    // re-derived alongside it, 352dp -> 288dp (six seats at the coin's own 48dp hard floor, not four
    // regular seats plus two differently-floored ones -- see its own KDoc). A narrower structural floor
    // lets the rail narrow further before hitting its hard stop, which is exactly what widens the hint's
    // clearance at every width where that floor engages -- 538dp among them. The remaining OWED range
    // (where the hint still collides) is narrower than before, not gone: MimirCertifiedViewportMatrixTest
    // still measures negative clearance at 360/411/480dp. This test now pins the crossover's NEW far
    // edge instead of a still-colliding point -- what to DO about the narrower remaining range is still a
    // design call this function does not make, per its own doc comment.
    @Test
    fun tomeShortcutHintClearsAtTheCompanionViewportAfterTheStructuralFloorWasRederived() {
        val companion = solveHomeShelfGeometry(538f, 444f)
        assertTrue(
            "538dp now clears -- if this goes negative again, the MinTargetRailWidth re-derivation " +
                "regressed or this pin is stale",
            homeShelfTomeShortcutHintClearanceDp(companion) > 0f,
        )
        assertNear(4.322f, homeShelfTomeShortcutHintClearanceDp(companion))
    }

    @Test
    fun rememberedTileResettlesWhenTheDockOwnsFocus() {
        assertEquals(1.12f, homeShelfTileTargetScale(focused = true, selectedScale = 1.12f), 0f)
        assertEquals(1f, homeShelfTileTargetScale(focused = false, selectedScale = 1.12f), 0f)
    }

    @Test
    fun homeTitleReservesOnlyForVisibleTrailingTruth() {
        assertEquals(0f, homeShelfTitleTrailingReserve(hasStatusChip = false).value, 0.01f)
        assertEquals(122f, homeShelfTitleTrailingReserve(hasStatusChip = true).value, 0.01f)
        assertEquals(
            HomeShelfTomeMarkTrailingReserve.value,
            homeShelfTitleTrailingReserve(hasStatusChip = false, hasTomeMark = true).value,
            0.01f,
        )
    }

    @Test
    fun rp5UsesFourFixedLeftAnchoredSeats() {
        val geometry = solveHomeShelfGeometry(widthDp = 700f, heightDp = 350f)

        assertNear(geometry.slotBounds(0).left + geometry.slotBounds(0).width / 2f, geometry.centerAnchor)
        assertNear(HomeShelfSelectedScale, geometry.selectedScale)
        assertNear(geometry.contentLeft, geometry.slotBounds(0).left)
        assertNear(48f, geometry.quickActionDiameter)
        assertNear(25.9f, geometry.quickBottom)
        assertNear(4.1f, geometry.quickActionOffsetY)
        assertNear(350f - geometry.quickBottom, geometry.quickActionBounds.bottom)
        assertEquals(4, geometry.visibleSlotCount)
        assertEquals(geometry.visibleSlotCount, geometry.fullyVisibleSlotCount)
        assertNear(HomeShelfNavigationHintWidth, geometry.navigationHintBand.width)

        (0 until geometry.visibleSlotCount).forEach { seat ->
            val art = geometry.artBounds(seat, selected = seat == 0)
            assertTrue("seat $seat must be fully visible", geometry.viewport.contains(art))
            if (seat > 0) assertFalse(geometry.slotBounds(seat - 1).intersects(geometry.slotBounds(seat)))
        }
    }

    @Test
    fun certifiedViewportsKeepFocusedArtBloomAndBandsInBounds() {
        val fixtures = listOf(
            320f to 300f,
            360f to 360f,
            411f to 411f,
            480f to 480f,
            700f to 350f,
            833f to 444f,
            538f to 444f,
            640f to 360f,
            960f to 540f,
            1041f to 468f,
            640f to 480f,
            1080f to 1080f,
        )

        fixtures.forEach { (width, height) ->
            val geometry = solveHomeShelfGeometry(width, height)
            val focusedWithBloom = geometry.artBounds(slotIndex = 0, selected = true)
                .outset(geometry.bloomOutset)

            assertTrue("focused art + bloom must fit $width x $height", geometry.viewport.contains(focusedWithBloom))
            // The floating corner HUD title renders fully across the scene up to the pill reserve — only the hint band stays exclusive.
            assertFalse(focusedWithBloom.intersects(geometry.hintToastBand))
            assertFalse(geometry.gameRailBand.intersects(geometry.hintToastBand))
            assertFalse(geometry.hintToastBand.intersects(geometry.quickRailBand))
            assertTrue(geometry.viewport.contains(geometry.titleBand))
            // the mode hint lives at the RIGHT END of the hint/toast
            // lane — the one band vacant on every certified viewport. It must clear the REAL focus
            // ring paint ([HomeShelfFocusRingReach], larger than the scene bloomOutset) below the
            // strip, never touch the title/status zone or the quick rail, and keep its compact width.
            assertTrue(geometry.viewport.contains(geometry.navigationHintBand))
            assertFalse(geometry.titleBand.intersects(geometry.navigationHintBand))
            assertTrue(geometry.hintToastBand.contains(geometry.navigationHintBand))
            assertNear(HomeShelfNavigationHintHeight, geometry.navigationHintBand.height)
            assertTrue(
                "$width x $height hint band must clear the strip's ring paint",
                geometry.navigationHintBand.top >= geometry.gameRailBand.bottom + HomeShelfFocusRingReach,
            )
            assertTrue(geometry.navigationHintBand.bottom <= geometry.quickRailBand.top)
            assertNear(geometry.viewport.right - geometry.gutter, geometry.navigationHintBand.right)
            assertTrue(geometry.navigationHintBand.width <= HomeShelfNavigationHintWidth + 0.01f)
            assertTrue(geometry.viewport.contains(geometry.hintToastBand))
            assertTrue(geometry.viewport.contains(geometry.quickRailBand))
            assertTrue(geometry.quickRailBand.contains(geometry.quickActionBounds))
            assertTrue(geometry.quickRailBand.contains(geometry.quickActionFocusedPaintBounds))
            assertTrue(geometry.quickActionDiameter <= 60f)
            assertTrue(geometry.quickActionOffsetY >= 0f)
            // FUX-D molten-plinth seam: the FOCUSED coin (1.04 scale pivoting on the un-offset box
            // center, so the offset rides inside the scale) must stay above the crest hairline at the
            // rail's 64dp coin/label seam — the ledge breaking under a coin defeats the material.
            val focusedCoinBottom = 64f / 2f +
                1.04f * (geometry.quickActionOffsetY + geometry.quickActionDiameter / 2f)
            assertTrue(
                "$width x $height focused coin bottom $focusedCoinBottom crossed the plinth crest",
                focusedCoinBottom <= 64f - HomeShelfQuickRailCrestBand / 2f + 0.01f,
            )
            assertNear(HomeShelfQuickRailHeight, geometry.quickRailBand.height)
            assertEquals(geometry.visibleSlotCount, geometry.fullyVisibleSlotCount)
            // Both expressions resolve seat zero's centre. Their equivalent Float operation order may
            // differ by a few millionths of a dp across certified widths; that is not a geometry delta.
            assertNear(geometry.slotBounds(0).left + geometry.slotBounds(0).width / 2f, geometry.centerAnchor)
            (0 until geometry.visibleSlotCount).forEach { seat ->
                val art = geometry.artBounds(seat, selected = seat == 0)
                assertTrue(
                    "rendered seat $seat must fit $width x $height",
                    geometry.viewport.contains(art),
                )
            }
        }
    }

    @Test
    fun quickRailUsesSixtyDpVisualInsideSixtyFourDpPaintEnvelope() {
        val geometry = solveHomeShelfGeometry(widthDp = 960f, heightDp = 540f)

        assertNear(60f, geometry.quickActionDiameter)
        assertTrue(geometry.quickActionFocusedPaintBounds.width < 64f)
        assertTrue(geometry.quickRailBand.contains(geometry.quickActionFocusedPaintBounds))
    }

    @Test
    fun releaseWidthsKeepFourSeatsAndCompactWidthUsesThree() {
        listOf(700f to 350f, 833f to 444f).forEach { (width, height) ->
            val geometry = solveHomeShelfGeometry(width, height)
            assertEquals(4, geometry.visibleSlotCount)
            assertNear(geometry.contentLeft, geometry.slotBounds(0).left)
            assertNear(geometry.slotStride, geometry.slotBounds(1).left - geometry.slotBounds(0).left)
            assertNear(HomeShelfSelectedScale, geometry.selectedScale)
        }
        assertEquals(3, solveHomeShelfGeometry(538f, 444f).visibleSlotCount)
    }

    @Test
    fun fourSeatWindowRevealsTheNextFullTileOnTheEdgeLandingMove() {
        assertEquals(0, homeShelfWindowStart(0, focusedIndex = 0, itemCount = 10, visibleSeatCount = 4))
        assertEquals(1, homeShelfWindowStart(0, focusedIndex = 3, itemCount = 10, visibleSeatCount = 4))
        assertEquals(2, homeShelfWindowStart(0, focusedIndex = 4, itemCount = 10, visibleSeatCount = 4))
        assertEquals(6, homeShelfWindowStart(5, focusedIndex = 9, itemCount = 10, visibleSeatCount = 4))
        assertEquals(1, homeShelfWindowStart(6, focusedIndex = 2, itemCount = 10, visibleSeatCount = 4))

        // At 5 / 8 on RP5 the selected item moves off the terminal visible seat and item 6 is
        // fully revealed; at 8 / 8 there is no fabricated continuation or wrap.
        assertEquals(2, homeShelfWindowStart(1, focusedIndex = 4, itemCount = 8, visibleSeatCount = 4))
        assertEquals(4, homeShelfWindowStart(4, focusedIndex = 7, itemCount = 8, visibleSeatCount = 4))

        val window = homeShelfBufferedWindow((0 until 10).toList(), windowStart = 1, visibleSeatCount = 4)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), window.map { it.item })
        assertEquals(listOf(-1, 0, 1, 2, 3, 4), window.map { it.slotOffset })
    }

    @Test
    fun selectedWindowClampsAtFiniteEdgesWithoutOppositeEndNeighbors() {
        val items = (0 until 50).toList()

        assertEquals(
            listOf(45, 46, 47, 48, 49),
            centeredHomeShelfWindow(items, focusedIndex = 49, slotRadius = 2).map { it.item },
        )
        assertEquals(
            listOf(45, 46, 47, 48, 49),
            centeredHomeShelfWindow(items, focusedIndex = 50, slotRadius = 2).map { it.item },
        )
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            centeredHomeShelfWindow(items, focusedIndex = -1, slotRadius = 2).map { it.item },
        )
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            centeredHomeShelfWindow(items, focusedIndex = 0, slotRadius = 2).map { it.slotOffset },
        )
    }

    // FUX-01 §3.7 : sub-window shelves use a FIXED content-order layout — the
    // old pinned behavior ("b" left of "a" at focus 1, pair swapping sides per move) was the live defect.

    @Test
    fun zeroOneAndTwoItemWindowsStayStable() {
        assertTrue(centeredHomeShelfWindow(emptyList<Int>(), focusedIndex = 0, slotRadius = 2).isEmpty())
        assertEquals(listOf("only"), centeredHomeShelfWindow(listOf("only"), 0, 2).map { it.item })
        assertEquals(
            listOf(0),
            centeredHomeShelfWindow(listOf("only"), 0, 2).map { it.slotOffset },
        )
        assertEquals(listOf("a", "b"), centeredHomeShelfWindow(listOf("a", "b"), 1, 2).map { it.item })
        assertEquals(listOf(-1, 0), centeredHomeShelfWindow(listOf("a", "b"), 1, 2).map { it.slotOffset })
    }

    @Test
    fun finiteContinuationRunesFollowRealDirectionalContent() {
        assertEquals(HomeShelfContinuation(false, false), homeShelfContinuation(null, 7))
        assertEquals(HomeShelfContinuation(false, false), homeShelfContinuation(0, 1))
        assertEquals(HomeShelfContinuation(false, true), homeShelfContinuation(0, 7))
        assertEquals(HomeShelfContinuation(true, true), homeShelfContinuation(3, 7))
        assertEquals(HomeShelfContinuation(true, false), homeShelfContinuation(6, 7))
        assertEquals(HomeShelfContinuation(true, false), homeShelfContinuation(99, 7))
        assertEquals(HomeShelfContinuation(false, false), homeShelfContinuation(3, 7, active = false))
    }

    @Test
    fun subWindowLayoutIsContentOrderedAndFocusIndependent() {
        val pair = listOf("a", "b")
        val focusedOnA = centeredHomeShelfWindow(pair, focusedIndex = 0, slotRadius = 1)
        val focusedOnB = centeredHomeShelfWindow(pair, focusedIndex = 1, slotRadius = 1)
        // The layout NEVER re-centers on focus: "a" is always left of "b", offsets identical either way.
        assertEquals(focusedOnA, focusedOnB)
        assertEquals(listOf("a" to -1, "b" to 0), focusedOnA.map { it.item to it.slotOffset })

        // A trio at Thor's radius-1 window fills the three fixed slots in content order.
        val trio = centeredHomeShelfWindow(listOf("a", "b", "c"), focusedIndex = 2, slotRadius = 1)
        assertEquals(listOf("a" to -1, "b" to 0, "c" to 1), trio.map { it.item to it.slotOffset })
        assertEquals(listOf(0, 1, 2), trio.map { it.itemIndex })
    }

    @Test
    fun superWindowShelvesKeepFiniteContentOrder() {
        val items = listOf("a", "b", "c", "d")
        val window = centeredHomeShelfWindow(items, focusedIndex = 0, slotRadius = 1)
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), window.map { it.item to it.slotOffset })
    }

    @Test
    fun finiteWindowNeverMountsTheOppositeEdgeAsANeighbor() {
        val items = (0 until 8).toList()
        val first = centeredHomeShelfWindow(items, focusedIndex = 0, slotRadius = 3)
        val last = centeredHomeShelfWindow(items, focusedIndex = 7, slotRadius = 3)

        assertFalse(first.any { it.item == 7 })
        assertFalse(last.any { it.item == 0 })
        assertEquals((0..6).toList(), first.map { it.item })
        assertEquals((1..7).toList(), last.map { it.item })
    }

    @Test
    fun linearBufferMovesTheAlreadyMountedOuterIdentityIntoTheEdgeSeat() {
        val items = (0 until 8).toList()
        val before = linearHomeShelfBufferedWindow(items, centerIndex = 2, slotRadius = 2)
        val after = linearHomeShelfBufferedWindow(items, centerIndex = 3, slotRadius = 2)

        assertEquals(3, before.single { it.item == 5 }.slotOffset)
        assertEquals(2, after.single { it.item == 5 }.slotOffset)
        assertEquals(3, after.single { it.item == 6 }.slotOffset)
        assertEquals(-3, after.single { it.item == 0 }.slotOffset)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsViewportTooSmallForCertifiedBands() {
        solveHomeShelfGeometry(widthDp = 319f, heightDp = 350f)
    }

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.02f) {
        assertTrue("expected $expected, actual $actual", abs(expected - actual) <= tolerance)
    }

    /**
     * Policy 2026-08-21, after reporting Home tiles read "quite tiny" on the Retroid Pocket Nova:
     * three seats there instead of four. `heroSide` is bound by `seatEnvelope`, which is width-derived —
     * the height cap (`0.48 * heightDp`) never binds on any certified viewport — so four fixed seats on a
     * 640dp panel solve small, and dropping to three is the only lever that grows the tile without
     * touching the Odin, whose art dp is shared.
     *
     * This test exists because NOTHING pinned the Nova's seat count or tile size. Raising the threshold
     * 640f -> 700f passed the whole suite green, which is the missing real-viewport geometry coverage: no test measures Home at a real device width.
     *
     * The RP5 case is the boundary and is load-bearing: 700f is exactly its width, so `700 < 700` is false
     * and it KEEPS four seats. That is why the constant is 700 and not a rounder number — the threshold change affects
     * the Nova and no other certified device.
     */
    @Test
    fun certifiedViewportsSolveTheRuledSeatCountAndTileSize() {
        val nova = solveHomeShelfGeometry(640f, 480f)
        assertEquals("Nova (640dp) must drop to three seats", 3, nova.visibleSlotCount)
        assertEquals("Nova heroSide", 180.10f, nova.heroSide, 0.05f)

        val odin = solveHomeShelfGeometry(832.5f, 468.3f)
        assertEquals("Odin must keep four seats", 4, odin.visibleSlotCount)
        assertEquals("Odin heroSide must not move", 171.79f, odin.heroSide, 0.05f)

        val rp5 = solveHomeShelfGeometry(700f, 350f)
        assertEquals(
            "RP5 is the boundary: 700 < 700 is false, so it keeps four seats and this adjustment " +
                "changes no certified device but the Nova",
            4,
            rp5.visibleSlotCount,
        )
    }
}
