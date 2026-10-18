package com.gruning.mimir.rewrite.feature.library

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Solver-level viewport matrix: every solver and clearance/pin is asserted at every
 * [MimirCertifiedViewports] row rather than a copied subset. Each printed row is also the coverage map: a
 * reference viewport absent from the output is not measured here.
 */
class MimirCertifiedViewportMatrixTest {

    private fun assertNear(expected: Float, actual: Float, tolerance: Float, message: String) {
        assertTrue(
            "$message: expected $expected, actual $actual (tolerance $tolerance)",
            abs(expected - actual) <= tolerance,
        )
    }

    // ── 1. solveHomeShelfGeometry band invariants, every certified viewport ────────────────────────────
    //
    // Same invariant shape as HomeShelfGeometryTest.certifiedViewportsKeepFocusedArtBloomAndBandsInBounds,
    // run over the fixture instead of a hand-copied list. The one NEW cell this adds beyond that test's
    // existing coverage is 720x720 (compact-square's largest probe) -- absent from that hand list, present
    // here because it is a named row in the fixture.
    @Test
    fun solveHomeShelfGeometryKeepsEveryBandInBoundsAtEveryCertifiedViewport() {
        val report = StringBuilder()
        MimirCertifiedViewports.all.forEach { viewport ->
            val box = viewport.solverInputDp
            val geometry = solveHomeShelfGeometry(box.widthDp, box.heightDp)
            val focusedWithBloom = geometry.artBounds(slotIndex = 0, selected = true).outset(geometry.bloomOutset)

            assertTrue(
                "${viewport.name} (${box.widthDp}x${box.heightDp}): focused art + bloom must fit the viewport",
                geometry.viewport.contains(focusedWithBloom),
            )
            assertFalse(
                "${viewport.name}: focused art+bloom must not intersect the hint/toast band",
                focusedWithBloom.intersects(geometry.hintToastBand),
            )
            assertFalse(
                "${viewport.name}: game rail band must not intersect the hint/toast band",
                geometry.gameRailBand.intersects(geometry.hintToastBand),
            )
            assertFalse(
                "${viewport.name}: hint/toast band must not intersect the quick rail band",
                geometry.hintToastBand.intersects(geometry.quickRailBand),
            )
            assertTrue("${viewport.name}: title band must fit the viewport", geometry.viewport.contains(geometry.titleBand))
            assertTrue(
                "${viewport.name}: navigation hint band must fit the viewport",
                geometry.viewport.contains(geometry.navigationHintBand),
            )
            assertFalse(
                "${viewport.name}: title band must not intersect the navigation hint band",
                geometry.titleBand.intersects(geometry.navigationHintBand),
            )
            assertTrue(
                "${viewport.name}: hint/toast band must contain the navigation hint band",
                geometry.hintToastBand.contains(geometry.navigationHintBand),
            )
            assertTrue(
                "${viewport.name}: navigation hint band bottom must sit above the quick rail",
                geometry.navigationHintBand.bottom <= geometry.quickRailBand.top,
            )
            assertTrue(
                "${viewport.name}: hint/toast band must fit the viewport",
                geometry.viewport.contains(geometry.hintToastBand),
            )
            assertTrue(
                "${viewport.name}: quick rail band must fit the viewport",
                geometry.viewport.contains(geometry.quickRailBand),
            )
            assertTrue(
                "${viewport.name}: quick rail band must contain the quick action bounds",
                geometry.quickRailBand.contains(geometry.quickActionBounds),
            )
            assertTrue(
                "${viewport.name}: quick rail band must contain the focused quick-action paint bounds",
                geometry.quickRailBand.contains(geometry.quickActionFocusedPaintBounds),
            )
            assertTrue("${viewport.name}: quick action diameter must never exceed 60dp", geometry.quickActionDiameter <= 60f)
            assertTrue("${viewport.name}: quick action offset must never go negative", geometry.quickActionOffsetY >= 0f)
            (0 until geometry.visibleSlotCount).forEach { seat ->
                val art = geometry.artBounds(seat, selected = seat == 0)
                assertTrue("${viewport.name} seat $seat must fit the viewport", geometry.viewport.contains(art))
                if (seat > 0) {
                    assertFalse(
                        "${viewport.name} seat ${seat - 1} and seat $seat must not overlap",
                        geometry.slotBounds(seat - 1).intersects(geometry.slotBounds(seat)),
                    )
                }
            }

            report.appendLine(
                "%-70s | %6.1f x %-6.1f | seats=%d | quickDiam=%.2f".format(
                    viewport.name, box.widthDp, box.heightDp, geometry.visibleSlotCount, geometry.quickActionDiameter,
                ),
            )
        }
        println("=== solveHomeShelfGeometry band invariants, every certified viewport ===")
        println(report.toString())
    }

    // ── 2. homeShelfTomeShortcutHintClearanceDp, sign-pinned at every certified viewport ───────────────
    //
    // Research §6b rule 3: "A seam where a fixed block meets a scaling anchor carries a clearance function,
    // sign-pinned at every certified viewport." Four of these ten cells were already pinned
    // (HomeShelfGeometryTest); this extends the same function to the six that were not. The negative cells
    // below are NOT a new defect this file found -- they are the pre-existing, already-documented OWED range
    // (HomeShelfScreen.kt's own doc comment on homeShelfTomeShortcutHintClearanceDp: "below ~597dp... the
    // hint still cannot clear") now measured at the specific certified widths that fall inside it, following
    // the same measured open-discrepancy pattern as HomeShelfGeometryTest's own
    // tomeShortcutHintStillCollidesBelowTheNewCrossoverAndTheGapIsNotSilentlyNarrowed pin for the 538dp row.
    @Test
    fun tomeShortcutHintClearanceIsSignPinnedAtEveryCertifiedViewport() {
        val report = StringBuilder()
        val expected = mapOf(
            // Already pinned elsewhere (HomeShelfGeometryTest) -- reproduced here as the same function fed
            // the same fixture row, not a re-derivation, so a drift in either place is caught by both.
            //
            // Odin here is 22.6770, NOT HomeShelfGeometryTest's 22.44 -- both are correct and deliberately
            // NOT reconciled to one number (see this file's own doc comment and MimirCertifiedViewports'
            // KDoc on the 832.5-vs-833 "two real boxes" resolution). This matrix uses solverInputDp, the
            // POST-INSET box (833dp wide) every other Odin pin in this file already uses, matching what a
            // real BoxWithConstraints actually hands the solver; HomeShelfGeometryTest's existing pin uses
            // the FULL viewport (832.5dp) for this one specific check, which this ticket does not renumber.
            // The 0.235dp gap between them changes no sign and crosses no threshold.
            MimirCertifiedViewports.Odin.name to 22.677f,
            MimirCertifiedViewports.RetroidPocketNova.name to 12.16f,
            MimirCertifiedViewports.Rp5Shelf.name to 10.3f,
            // Re-pinned 2026-08-21: the dock's structural floor (MimirRunicQuickRailDefaults.
            // MinTargetRailWidth) was re-derived from 352dp to 288dp alongside the equal-seat
            // override (QUICK_LAUNCH/FILES's differentiated
            // widths retired, see MinTargetRailWidth's own KDoc). A narrower rail floor means a narrower
            // conservative rail right edge at every width where that floor engages, so clearance improves
            // by exactly half the floor's own delta (64dp -> +32dp clearance) at every row still inside
            // it, and by half of a SMALLER delta (32dp -> +16dp) at the 320dp row, which the floor did not
            // reach under the OLD 352dp value (352dp > the 320dp viewport itself) but does reach now.
            MimirCertifiedViewports.OdinHeightCompanion538.name to 4.322f,
            // NEW cells below ~533dp (the new crossover, narrower than the pre-2026-08-21 ~597dp) fall
            // inside the documented-OWED collision range: the rail's structural floor is already engaged
            // at these widths, so the fixed-edge hint still reaches into the rail's worst-case span. Below
            // the crossover on purpose -- this is the OWED range being measured honestly, not the
            // collision being declared fixed.
            MimirCertifiedViewports.CompactSquare360.name to -84.0f,
            MimirCertifiedViewports.CompactSquare411.name to -58.5f,
            MimirCertifiedViewports.CompactSquare480.name to -24.0f,
            MimirCertifiedViewports.MinimumFloor320x300.name to -104.0f,
            // NEW cells above the crossover -- clear, like the Odin/Nova/RP5 rows above.
            MimirCertifiedViewports.CompactSquare720.name to 9.68f,
            // The RG Rotate's width, measured 2026-09-27. POSITIVE here because this pin calls the function
            // without `compact`, i.e. against the standard rail; the compact Home gate passes `compact = true`
            // and gets -57.5dp, which is why the mark is suppressed there (compact review HOME-3).
            MimirCertifiedViewports.CompactSquare564.name to 14.494f,
            MimirCertifiedViewports.RgRotate.name to 14.494f,
            MimirCertifiedViewports.LargeSquareBoundary1080.name to 144.0f,
        )

        MimirCertifiedViewports.all.forEach { viewport ->
            val box = viewport.solverInputDp
            val geometry = solveHomeShelfGeometry(box.widthDp, box.heightDp)
            val clearance = homeShelfTomeShortcutHintClearanceDp(geometry)
            val want = requireNotNull(expected[viewport.name]) { "no expected clearance pinned for ${viewport.name}" }
            assertNear(want, clearance, 0.05f, "${viewport.name} clearance")
            report.appendLine(
                "%-70s | width=%7.1f | clearance=%8.3fdp%s".format(
                    viewport.name, box.widthDp, clearance, if (clearance < 0f) "  <<< OWED collision" else "",
                ),
            )
        }
        println("=== homeShelfTomeShortcutHintClearanceDp, every certified viewport ===")
        println(report.toString())
    }

    // ── 3. Home/Library dock bottom-gap parity, every STANDARD certified viewport ──────────────────────
    //
    // Extends homeAndLibraryDocksShareTheSameBottomGap (HomeShelfGeometryTest), which today only compares
    // (700,350), (833,444) and (1080,1080). This is TRUE BY CONSTRUCTION for every STANDARD viewport --
    // solveLibraryShelfGeometry's non-compact branch reads the exact same homeShelfQuickRailBottomGapDp(h)
    // helper Home does -- but "by construction" was never actually asserted at the Nova or the 538dp
    // companion until now. The Nova matters most: it is the device the "dock jumped between Home and
    // Library" defect  was reported on, and it was never in this pin's viewport
    // list before this file.
    @Test
    fun homeAndLibraryDocksShareTheSameBottomGapAtEveryStandardCertifiedViewport() {
        val report = StringBuilder()
        MimirCertifiedViewports.standard.forEach { viewport ->
            val box = viewport.solverInputDp
            val homeGeometry = solveHomeShelfGeometry(box.widthDp, box.heightDp)
            val homeBottomGap = box.heightDp - homeGeometry.quickRailBand.bottom
            val libraryBottomGap = solveLibraryShelfGeometry(box.widthDp, box.heightDp).bottomPaddingDp
            assertNear(
                homeBottomGap,
                libraryBottomGap,
                0.01f,
                "${viewport.name} (${box.widthDp}x${box.heightDp}) dock bottom gap must match between Home and Library",
            )
            report.appendLine("%-70s | gap=%.3fdp".format(viewport.name, homeBottomGap))
        }
        println("=== Home/Library dock bottom-gap parity, every STANDARD certified viewport ===")
        println(report.toString())
    }

    // ── 4. The SAME pin, COMPACT_SQUARE side ────────────────────────────────────────────────────────────
    //
    // solveLibraryShelfGeometry's compactSquare branch never calls homeShelfQuickRailBottomGapDp at all --
    // it hard-codes bottomPaddingDp = 6f regardless of height -- while solveHomeShelfGeometry has no
    // COMPACT_SQUARE branch and always solves the height-scaled gap. So the #3 pin's claim structurally
    // CANNOT hold on this posture; this is not a numeric coincidence to chase, it is two different formulas
    // by design. Pinned as a measured open parity difference as
    // HomeShelfGeometryTest.tomeShortcutHintStillCollidesBelowTheNewCrossoverAndTheGapIsNotSilentlyNarrowed
    // so a future edit to either formula is measured against the real state, not silently reported as
    // "still matches" when it does not. Whether COMPACT_SQUARE should be exempt from the parity claim, or
    // Library's compact branch should read the shared helper, remains an open production-design decision beyond this test.
    @Test
    fun homeAndLibraryDocksMatchAtEveryCompactSquareCertifiedViewportMeasuredNotAssumed() {
        val report = StringBuilder()
        val expectedGap = mapOf(
            MimirCertifiedViewports.MinimumFloor320x300.name to 0.0f,
            MimirCertifiedViewports.CompactSquare360.name to 0.0f,
            MimirCertifiedViewports.CompactSquare411.name to 4.5675f,
            MimirCertifiedViewports.CompactSquare480.name to 11.52f,
            MimirCertifiedViewports.CompactSquare720.name to 12.0f,
            MimirCertifiedViewports.CompactSquare564.name to 12.0f,
            MimirCertifiedViewports.RgRotate.name to 12.0f,
        )
        var divergent = 0
        MimirCertifiedViewports.compactSquare.forEach { viewport ->
            val box = viewport.solverInputDp
            val homeGeometry = solveHomeShelfGeometry(box.widthDp, box.heightDp)
            val homeBottomGap = box.heightDp - homeGeometry.quickRailBand.bottom
            val libraryBottomGap = solveLibraryShelfGeometry(box.widthDp, box.heightDp).bottomPaddingDp
            val want = requireNotNull(expectedGap[viewport.name]) { "no expected gap pinned for ${viewport.name}" }
            assertNear(want, homeBottomGap, 0.01f, "${viewport.name} Home gap (measured, not the claim)")
            // Library's compact branch reads Home's dock gap on RG Rotate.
            assertNear(want, libraryBottomGap, 0.01f, "${viewport.name} Library compact-square gap is Home's gap")
            if (abs(homeBottomGap - libraryBottomGap) > 0.01f) divergent += 1
            report.appendLine(
                "%-70s | home=%6.3fdp | library=%6.3fdp | diff=%6.3fdp".format(
                    viewport.name, homeBottomGap, libraryBottomGap, homeBottomGap - libraryBottomGap,
                ),
            )
        }
        println("=== Home/Library dock bottom-gap, COMPACT_SQUARE certified viewports (measured) ===")
        println(report.toString())
        assertEquals("the Home and Library docks sit at the same height on every compact square", 0, divergent)
    }

    // ── 5. Seat count, every certified viewport ─────────────────────────────────────────────────────────
    //
    // Extends HomeShelfGeometryTest.certifiedViewportsSolveTheRuledSeatCountAndTileSize (Nova/Odin/RP5 only)
    // to the full fixture. This is deliberately NOT a generic "no certified device sits on a threshold"
    // knife-edge gate (research §6b rule 3's corollary) -- HomeShelfPrimaryWidthDp=700 was placed EXACTLY on
    // the RP5's width by so `700 < 700` keeps its four seats; a generic knife-edge
    // gate would flag the threshold choice as a defect. This protects the same threshold choice the opposite way: if a future
    // edit flips `<` to `<=`, the RP5 pin below fails loudly.
    @Test
    fun seatCountIsPinnedAtEveryCertifiedViewport() {
        val report = StringBuilder()
        val expectedSeats = mapOf(
            MimirCertifiedViewports.Odin.name to 4,
            MimirCertifiedViewports.RetroidPocketNova.name to 3,
            MimirCertifiedViewports.Rp5Shelf.name to 4, // the knife-edge: 700 < 700 is false
            MimirCertifiedViewports.CompactSquare360.name to 3,
            MimirCertifiedViewports.CompactSquare411.name to 3,
            MimirCertifiedViewports.CompactSquare480.name to 3,
            MimirCertifiedViewports.CompactSquare720.name to 4,
            MimirCertifiedViewports.CompactSquare564.name to 3,
            MimirCertifiedViewports.RgRotate.name to 3,
            MimirCertifiedViewports.OdinHeightCompanion538.name to 3,
            MimirCertifiedViewports.MinimumFloor320x300.name to 3,
            MimirCertifiedViewports.LargeSquareBoundary1080.name to 4,
        )
        MimirCertifiedViewports.all.forEach { viewport ->
            val box = viewport.solverInputDp
            val geometry = solveHomeShelfGeometry(box.widthDp, box.heightDp)
            val want = requireNotNull(expectedSeats[viewport.name]) { "no expected seat count for ${viewport.name}" }
            assertEquals("${viewport.name} (${box.widthDp}dp wide) seat count", want, geometry.visibleSlotCount)
            report.appendLine("%-70s | width=%7.1f | seats=%d".format(viewport.name, box.widthDp, geometry.visibleSlotCount))
        }
        println("=== Home Shelf seat count, every certified viewport ===")
        println(report.toString())
    }

    // ── 6. solveLibraryShelfGeometry's own invariants, every certified viewport ────────────────────────
    //
    // ShelfHeadGeometryTest already pins fixedLaneBudgetDp <= viewportHeight for the compact-square family
    // (360/411/480/720, via the OTHER, static libraryShelfFixedLaneBudgetDp() constant, 441, checked against
    // (960,540)/(1920,1080)/(833,444)/(538,444) only -- RP5 is never in that list) and an exact instance
    // value (425dp) plus tileScale==1f for RP5 alone -- but NEVER compares that 425dp against RP5's own
    // 350dp height. This extends the real per-instance fixedLaneBudgetDp <= height check to every certified
    // viewport, with two deliberate exceptions found by running this test before writing its assertion, not
    // by asserting a claim untested:
    //
    // - RP5-class shelf (700x350): fixedLaneBudgetDp solves to the ALREADY-PINNED 425dp -- 75dp TALLER than
    //   its own 350dp height. This is not a new number; it is the first time that number has ever been
    //   compared against the viewport it is supposed to fit inside. This remains an open fit discrepancy;
    //   this may bear directly on RC-6 (Library Gamelist clips in list view mode).
    // - MinimumFloor320x300 (300dp tall, classifies COMPACT_SQUARE): the compact branch's `availableHero`
    //   floors at 140dp (`.coerceAtLeast(140f)`) before this height -- (300 - 181) = 119dp, below the floor
    //   -- so fixedLaneBudgetDp solves to 321dp, 21dp taller than its own 300dp viewport.
    //
    // Both need production changes to solveLibraryShelfGeometry; these tests
    // record the measured open discrepancies rather than silently
    // asserted as passing and not silently omitted.
    @Test
    fun solveLibraryShelfGeometryKeepsItsOwnBudgetAndFloorsAtEveryCertifiedViewport() {
        val report = StringBuilder()
        val expectedBudget = mapOf(
            MimirCertifiedViewports.Odin.name to 433.856f,
            MimirCertifiedViewports.RetroidPocketNova.name to 436.52f,
            MimirCertifiedViewports.Rp5Shelf.name to 425f, // already pinned, ShelfHeadGeometryTest -- OWED overflow, see above
            MimirCertifiedViewports.CompactSquare360.name to 360f,
            MimirCertifiedViewports.CompactSquare411.name to 411f,
            MimirCertifiedViewports.CompactSquare480.name to 480f,
            MimirCertifiedViewports.CompactSquare720.name to 496.4f,
            MimirCertifiedViewports.CompactSquare564.name to 496.4f,
            MimirCertifiedViewports.RgRotate.name to 496.4f,
            MimirCertifiedViewports.OdinHeightCompanion538.name to 433.856f,
            MimirCertifiedViewports.MinimumFloor320x300.name to 315f, // OWED: exceeds its own 300dp viewport (6dp less since the compact dock reads Home's gap)
            MimirCertifiedViewports.LargeSquareBoundary1080.name to 437f,
        )
        val exemptFromFitCheck = setOf(
            MimirCertifiedViewports.Rp5Shelf.name,
            MimirCertifiedViewports.MinimumFloor320x300.name,
        )
        MimirCertifiedViewports.all.forEach { viewport ->
            val box = viewport.solverInputDp
            val geometry = solveLibraryShelfGeometry(box.widthDp, box.heightDp)
            val want = requireNotNull(expectedBudget[viewport.name]) { "no expected budget for ${viewport.name}" }
            assertNear(want, geometry.fixedLaneBudgetDp, 0.01f, "${viewport.name} fixedLaneBudgetDp")

            if (viewport.name !in exemptFromFitCheck) {
                assertTrue(
                    "${viewport.name}: fixedLaneBudgetDp (${geometry.fixedLaneBudgetDp}) must fit its own " +
                        "${box.heightDp}dp viewport",
                    geometry.fixedLaneBudgetDp <= box.heightDp,
                )
            }
            assertTrue("${viewport.name}: horizontalGutterDp floor", geometry.horizontalGutterDp >= 12f)
            assertTrue("${viewport.name}: artBandHeightDp floor", geometry.artBandHeightDp >= 96f)
            assertTrue(
                "${viewport.name}: tileScale must stay in the configured 0.62..1 range",
                geometry.tileScale in 0.62f..1f,
            )
            report.appendLine(
                "%-70s | budget=%7.3f (viewport=%6.1f) | compactSquare=%-5s | tileScale=%.3f%s".format(
                    viewport.name, geometry.fixedLaneBudgetDp, box.heightDp, geometry.compactSquare,
                    geometry.tileScale,
                    if (geometry.fixedLaneBudgetDp > box.heightDp) "  <<< OWED overflow" else "",
                ),
            )
        }
        println("=== solveLibraryShelfGeometry budget + floors, every certified viewport ===")
        println(report.toString())
    }
}
