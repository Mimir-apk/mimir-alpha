package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Viewport matrix for [mimirQuickRailTargetWidth], the
 * viewport-aware rail sizing solver, at every certified width. `MimirFinalShelfPrimitivesTest` already pins
 * this at the Odin (832.5dp, not the post-inset 833dp — see [MimirCertifiedViewports]' KDoc), the Nova
 * (640dp), the 538dp companion and 320dp floor; this file extends the same function to the RP5 width and the
 * compact-square/large-square rows that were never fed to it before.
 *
 * `mimirQuickRailTargetWidth` takes only a width, so this file dedupes fixture rows by width rather than
 * iterating all ten (several rows share a width class purely by coincidence of this fixture's construction —
 * none do here, but the dedup is defensive, not decorative).
 */
class MimirCertifiedViewportMatrixTest {

    @Test
    fun quickRailTargetWidthIsPinnedAtEveryCertifiedViewportWidth() {
        val report = StringBuilder()
        // fixture width -> (expected target dp, coverage note)
        val expected = listOf(
            // Odin: full-viewport width (832.5, not the post-inset 833) -- matches the existing
            // MimirFinalShelfPrimitivesTest pin's own choice of input; already pinned there.
            MimirCertifiedViewports.Odin.fullViewportDp!!.widthDp to 560.5f,
            MimirCertifiedViewports.RetroidPocketNova.fullViewportDp!!.widthDp to 368f, // already pinned
            MimirCertifiedViewports.Rp5Shelf.postInsetShelfDp!!.widthDp to 428f, // NEW
            // Equal-width seats replaced differentiated QUICK_LAUNCH/FILES widths on 2026-08-21,
            // making MinTargetRailWidth six seats at the
            // coin's-own-floor, 352dp -> 288dp; see MimirRunicQuickRailDefaults.MinTargetRailWidth's own
            // KDoc). All four rows below sit close enough to the floor that the re-derivation moves them.
            MimirCertifiedViewports.CompactSquare360.solverInputDp.widthDp to 288f, // NEW, structural floor
            MimirCertifiedViewports.CompactSquare411.solverInputDp.widthDp to 288f, // NEW, structural floor
            MimirCertifiedViewports.CompactSquare480.solverInputDp.widthDp to 288f, // NEW, structural floor
            MimirCertifiedViewports.CompactSquare720.solverInputDp.widthDp to 448f, // NEW
            MimirCertifiedViewports.OdinHeightCompanion538.postInsetShelfDp!!.widthDp to 288f, // re-pinned 2026-08-21
            // Re-pinned 2026-08-21: this row used to pass through UNCHANGED at the raw 320dp viewport
            // (the OLD 352dp floor was wider than the viewport itself, so minOf's raw-viewport cap won --
            // see this test's own retired comment below on that exact inconsistency). The NEW 288dp floor
            // is narrower than 320dp, so it now wins instead: this is a real narrowing, not a rounding
            // artifact.
            MimirCertifiedViewports.MinimumFloor320x300.postInsetShelfDp!!.widthDp to 288f, // re-pinned 2026-08-21
            MimirCertifiedViewports.LargeSquareBoundary1080.postInsetShelfDp!!.widthDp to 808f, // NEW
        )
        expected.forEach { (widthDp, expectedTargetDp) ->
            val target = mimirQuickRailTargetWidth(widthDp.dp)
            assertTrue(
                "target width at ${widthDp}dp: expected ${expectedTargetDp}dp, actual ${target.value}dp",
                kotlin.math.abs(target.value - expectedTargetDp) <= 0.01f,
            )
            // RETIRED 2026-08-21 (was: "NOT asserted here: 'target never drops below MinTargetRailWidth'
            // -- at the 320dp floor the raw-viewport cap wins, landing at 320dp, BELOW MinTargetRailWidth
            // (352dp)"). That inconsistency -- a "structural floor" wider than the narrowest viewport it
            // was supposed to floor -- is gone now that MinTargetRailWidth was re-derived to 288dp
            // alongside the equal-seat override (see its own KDoc): 288dp < 320dp, so the floor guarantee
            // now genuinely holds at every certified width, asserted directly below instead of narrated
            // around.
            assertTrue(
                "target width at ${widthDp}dp must never drop below MinTargetRailWidth",
                target >= MimirRunicQuickRailDefaults.MinTargetRailWidth,
            )
            assertTrue("target width at ${widthDp}dp must never exceed the raw viewport", target <= widthDp.dp)
            report.appendLine("width=%7.1f | target=%7.2fdp".format(widthDp, target.value))
        }
        println("=== mimirQuickRailTargetWidth, every certified viewport width ===")
        println(report.toString())
    }

    // Uneven Nova gaps came from mimirQuickRailSlotWidths pinning
    // QUICK_LAUNCH/FILES at their own differentiated floors while the four regular seats absorbed the
    // rest -- the class of bug this pin exists to prevent from ever shipping again: at every certified
    // viewport, all six seats must compute to the SAME width. This is the one assertion the design spec's
    // own missing-minTarget-floor defect (MimirFinalShelfPrimitivesTest) shows the absence of was exactly
    // what let three different seat widths ship in the first place.
    @Test
    fun quickRailSlotsAreEqualWidthAtEveryCertifiedViewport() {
        val report = StringBuilder()
        MimirCertifiedViewports.all.forEach { viewport ->
            val widthDp = viewport.solverInputDp.widthDp
            val target = mimirQuickRailTargetWidth(widthDp.dp)
            val widths = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, target)
            val distinct = widths.map { it.value }.distinct()
            assertTrue(
                "${viewport.name} (${widthDp}dp, target=${target.value}dp): every seat must be the same " +
                    "width, found $distinct",
                distinct.size == 1,
            )
            report.appendLine("%-70s | width=%7.1f | target=%7.2f | seat=%6.2fdp".format(
                viewport.name, widthDp, target.value, widths.first().value,
            ))
        }
        println("=== mimirQuickRailSlotWidths, equal-width pin, every certified viewport ===")
        println(report.toString())
    }
}
