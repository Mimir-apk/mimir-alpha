package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf seat solve's one pure-solver matrix
 * , over [MimirCertifiedViewports.all] — a focused test set,
 * nothing more: one matrix proving the count authority, the platform-row plan, the Float-noise guard, and
 * the vertical art-band fit all hold at every certified viewport, plus the two Float identities the spec
 * asks to be verified rather than trusted algebraically.
 */
class PlatformRowSeatSolveTest {

    /** Row-by-row solved plan, read straight from production code — not re-derived here. */
    private data class SolvedRow(
        val viewport: MimirCertifiedViewport,
        val geometry: LibraryShelfGeometry,
        val bandDp: Float,
        val windowRadius: Int,
        val plan: PlatformRowPlan,
        val guardRadius: Int,
    )

    private fun solve(viewport: MimirCertifiedViewport): SolvedRow {
        val box = viewport.solverInputDp
        val geometry = solveLibraryShelfGeometry(box.widthDp, box.heightDp)
        val bandDp = box.widthDp - 2f * geometry.horizontalGutterDp
        val windowRadius = geometry.homeSeatCount - 2
        val plan = solvePlatformRowPlan(
            bandDp = bandDp,
            homeSeatCount = geometry.homeSeatCount,
            artBandHeightDp = geometry.artBandHeightDp,
        )
        val guardRadius = coverflowBoundedWindowRadius(
            viewportWidthDp = bandDp,
            maxRadius = windowRadius,
            restWidthDp = plan.seatDp,
            focusWidthDp = plan.seatDp,
            gapDp = plan.gapDp,
            restScale = CoverflowMotionBookRestScale,
        )
        return SolvedRow(viewport, geometry, bandDp, windowRadius, plan, guardRadius)
    }

    /**
     * The two Float identities the ticket asks to be VERIFIED, not trusted algebraically: ρ*144 == 20 exactly
     * (so the cap regime reproduces today's Nova numbers bit-exactly), and the derived SeatCap sits above the
     * configured 144dp with an honest margin (144.41, not 144 — the AUTHORED constant is what the row actually
     * caps at; see coverflowBookSeatCapDp's own doc for why the two are deliberately different numbers).
     */
    @Test
    fun rhoTimes144EqualsTwentyExactlyInFloat() {
        assertEquals(20f, CoverflowBookGapToSeatRatio * 144f, 0f)
    }

    @Test
    fun derivedSeatCapAtTheStandardArtBandExceedsTheRuled144WithMargin() {
        val derived = coverflowBookSeatCapDp(CoverflowArtBandHeightDp.toFloat())
        assertTrue("derived cap $derived must exceed the configured 144dp", derived > 144f)
        assertEquals(144.41f, derived, 0.01f)
    }

    /** Home's seat count is bit-identical to the pre-solve threshold (`widthDp < 700f`) at all ten rows. */
    @Test
    fun mimirShelfSeatCountReproducesTheFormerSevenHundredBandAtEveryCertifiedViewport() {
        MimirCertifiedViewports.all.forEach { viewport ->
            val box = viewport.solverInputDp
            val expected = if (box.widthDp < 700f) HomeShelfCompactSeatCount else HomeShelfPrimarySeatCount
            assertEquals(
                "${viewport.name} (${box.widthDp}dp)",
                expected,
                mimirShelfSeatCount(box.widthDp),
            )
            // And Home's own full solver must agree — the count authority is wired in, not just parallel.
            val homeGeometry = solveHomeShelfGeometry(box.widthDp, box.heightDp)
            assertEquals(expected, homeGeometry.visibleSlotCount)
        }
    }

    /** Nova bit-exact: 3 tiles at 144dp with 20dp gaps, to the digit — the cap regime, untouched. */
    @Test
    fun novaSolvesBitExactToTodaysShippedNumbers() {
        val row = solve(MimirCertifiedViewports.RetroidPocketNova)
        assertEquals(3, row.plan.seatCount)
        assertEquals(144f, row.plan.seatDp, 0f)
        assertEquals(20f, row.plan.gapDp, 0f)
    }

    /** Odin: 5 tiles filling its band, ~142.7dp (spec's own 142.65 used 832.5dp; solverInputDp is 833dp — the
     * two-box ambiguity `MimirCertifiedViewports.kt`'s own KDoc names — so this pins the number production
     * code actually computes, not the spec's hand-derivation). */
    @Test
    fun odinSolvesFiveTilesFillingItsBand() {
        val row = solve(MimirCertifiedViewports.Odin)
        assertEquals(5, row.plan.seatCount)
        assertEquals(142.74f, row.plan.seatDp, 0.02f)
        assertEquals(19.82f, row.plan.gapDp, 0.02f)
        val rowWidth = row.plan.seatCount * row.plan.seatDp + (row.plan.seatCount - 1) * row.plan.gapDp
        assertEquals("row must fill the band", row.bandDp, rowWidth, 0.02f)
    }

    /** RP5-class: 5 tiles at ~118.8dp, filling its 660dp band. */
    @Test
    fun rp5SolvesFiveTilesFillingItsBand() {
        val row = solve(MimirCertifiedViewports.Rp5Shelf)
        assertEquals(5, row.plan.seatCount)
        assertEquals(118.8f, row.plan.seatDp, 0.02f)
        assertEquals(660f, row.bandDp, 0.01f)
    }

    /**
     * The four probes that painted ONE tile before this fix (their radius-1 edge, at the fixed 144dp cap,
     * exceeded their own narrow half-band) come back to a full 3.
     */
    @Test
    fun theFourNarrowProbesComeBackToThreeTiles() {
        listOf(
            MimirCertifiedViewports.CompactSquare480,
            MimirCertifiedViewports.CompactSquare411,
            MimirCertifiedViewports.CompactSquare360,
            MimirCertifiedViewports.MinimumFloor320x300,
        ).forEach { viewport ->
            val row = solve(viewport)
            assertEquals("${viewport.name} must solve 3 seats", 3, row.plan.seatCount)
            assertEquals("${viewport.name} guard must not clip below the design radius", 1, row.guardRadius)
        }
    }

    /**
     * `coverflowBoundedWindowRadius` is a GUARD, asserted never to bind: at every certified viewport the
     * measured-viewport bound must return EXACTLY the design radius the seat solve was built for — never
     * fewer. See that function's own Float-overload doc for the ~3e-5dp worst-case rounding this protects
     * against (CS720/CS411 specifically).
     */
    @Test
    fun theGuardNeverBindsAtAnyCertifiedViewport() {
        MimirCertifiedViewports.all.forEach { viewport ->
            val row = solve(viewport)
            assertEquals(
                "${viewport.name}: guard clipped below the design radius — the solve overflowed the band",
                row.windowRadius,
                row.guardRadius,
            )
        }
    }

    /**
     * The harness form of the spec's own "188.0 <= 190" check (§3c): the focused tile's bloom-inflated,
     * focus-scaled visual height must fit inside the ACTUAL art band this viewport solves — not the standard
     * 190dp assumed by a flat cap. This is the only check that can see the compact-square vertical overflow a
     * flat 144dp cap would have produced at CS360/320x300 (found while grounding this spec against source;
     * `coverflowBookSeatCapDp` closes it by capping the seat to the REAL art band, not just the configured 144dp).
     */
    @Test
    fun theFocusedTileFitsItsOwnArtBandAtEveryCertifiedViewport() {
        MimirCertifiedViewports.all.forEach { viewport ->
            val row = solve(viewport)
            val seatHeight = row.plan.seatDp * CoverflowMotionBookFocusHeightDp / CoverflowMotionBookFocusWidthDp
            val focusedVisualHeight = (seatHeight + CoverflowMotionBloomOutsetDp * 2) * CoverflowMotionFocusScale
            assertTrue(
                "${viewport.name}: focused tile ($focusedVisualHeight) overflows its ${row.geometry.artBandHeightDp}dp art band",
                focusedVisualHeight <= row.geometry.artBandHeightDp + 0.01f,
            )
        }
    }

    /** The full certified-viewport table, count/seat/gap, printed as diagnostic output — not itself an assertion. */
    @Test
    fun printTheCertifiedViewportTable() {
        val report = MimirCertifiedViewports.all.joinToString("\n") { viewport ->
            val row = solve(viewport)
            val rowWidth = row.plan.seatCount * row.plan.seatDp + (row.plan.seatCount - 1) * row.plan.gapDp
            "%-70s band=%8.3f home=%d n=%d seat=%9.4f gap=%8.4f share=%6.4f guard=%d/%d".format(
                viewport.name,
                row.bandDp,
                row.geometry.homeSeatCount,
                row.plan.seatCount,
                row.plan.seatDp,
                row.plan.gapDp,
                rowWidth / row.bandDp,
                row.guardRadius,
                row.windowRadius,
            )
        }
        println(report)
        assertTrue(report.isNotEmpty())
    }

    /**
     * Spec §5 — the static-strip trigger is EVEN count that already fits the window, at both certified
     * radii (1 = Nova-class, 2 = Odin/RP5-class). Odd counts and over-full counts stay a sliding ring.
     */
    @Test
    fun staticStripEligibilityMatchesTheSpecsEvenAndFitsRule() {
        // Radius 1 (3-tile window): 2 fits and is even -> static. 3 fits but is odd -> ring. 4 overflows -> ring.
        assertTrue(coverflowStaticStripEligible(itemCount = 2, windowRadius = 1))
        assertTrue(!coverflowStaticStripEligible(itemCount = 3, windowRadius = 1))
        assertTrue(!coverflowStaticStripEligible(itemCount = 4, windowRadius = 1))
        // Radius 2 (5-tile window): 2 and 4 fit and are even -> static. 3 and 5 fit but are odd -> ring.
        // 6 overflows -> ring.
        assertTrue(coverflowStaticStripEligible(itemCount = 2, windowRadius = 2))
        assertTrue(coverflowStaticStripEligible(itemCount = 4, windowRadius = 2))
        assertTrue(!coverflowStaticStripEligible(itemCount = 3, windowRadius = 2))
        assertTrue(!coverflowStaticStripEligible(itemCount = 5, windowRadius = 2))
        assertTrue(!coverflowStaticStripEligible(itemCount = 6, windowRadius = 2))
    }

    /**
     * Found while implementing spec §5: importing Home's LEFT-anchored integer window index
     * (`minOf(windowRadius, itemCount-1)`) into [ShelfCoverflow]'s CENTRE-anchored art band shifts the
     * whole static strip off-centre — up to ~81dp at itemCount==4/radius==2 (half a stride), and leaves the
     * right half of the band empty at itemCount==2. The centroid fix must sum to zero (symmetric by
     * construction) for every itemCount the static strip actually renders.
     */
    @Test
    fun staticStripPositionsAreSymmetricByConstruction() {
        listOf(2, 3, 4, 5).forEach { itemCount ->
            val positions = (0 until itemCount).map { coverflowStaticStripPosition(it, itemCount) }
            assertEquals(
                "itemCount=$itemCount positions $positions must sum to zero (centroid-anchored)",
                0f,
                positions.sum(),
                0.0001f,
            )
            // And the reject-integer-anchor case the fix replaces would NOT have summed to zero here.
        }
        // The two counts the static strip actually reaches: 2 (both radii) and 4 (radius 2 only).
        assertEquals(listOf(-0.5f, 0.5f), (0 until 2).map { coverflowStaticStripPosition(it, 2) })
        assertEquals(listOf(-1.5f, -0.5f, 0.5f, 1.5f), (0 until 4).map { coverflowStaticStripPosition(it, 4) })
    }
}
