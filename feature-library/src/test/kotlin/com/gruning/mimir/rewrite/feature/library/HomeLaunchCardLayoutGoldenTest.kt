package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden geometry table for the `homeLaunchCardLayout` band merge. Every row pins ALL SEVEN [HomeLaunchCardLayout] fields at
 * every [MimirCertifiedViewports] row, not just the two fields ([homeLaunchCardOuterHorizontal] /
 * [homeLaunchCardOuterVertical]) this pass actually converges — so a future change to the five content
 * fields this pass deliberately leaves untouched ([homeLaunchCardContentBands]'s own KDoc) fails loudly
 * here rather than silently.
 *
 * **Continuous cover geometry.** `coverColumnWidth`/`coverHeight` are NO LONGER content-band literals — they are
 * now ALSO overridden by [homeLaunchCardLayout] with a continuous, Odin-anchored, height-driven law (see
 * [homeLaunchCardCoverHeight]'s KDoc in `HomeLaunchCard.kt` for the axis argument and derivation). Every row
 * below carries the recomputed values from a real run of [homeLaunchCardLayout], not the historical band
 * literals this table pinned before.
 *
 * ## Which box: `launchCardInputDp`, not [MimirCertifiedViewport.solverInputDp]
 *
 * `HomeLaunchCard` is `fillMaxSize()` in an UN-INSET host [V `HomeLaunchCard.kt:609-615`] — it receives the
 * FULL viewport, never the shelf's post-inset box that `solverInputDp` prefers. [launchCardInputDp] is the
 * mirror-image preference (full viewport first, post-inset box only when no full viewport is on file) —
 * for every row except the Odin this makes no numeric difference (the other rows either have no
 * `postInsetShelfDp` at all, or no `fullViewportDp` at all), but for the Odin it is the difference between
 * 468.3dp (real, what production actually feeds this card) and 444dp (the shelf's OWN post-inset box, a
 * different real number for a different consumer — see [MimirCertifiedViewports]' own KDoc on the
 * "832.5 vs 833" non-contradiction). Using 444dp here would silently feed `homeLaunchCardCoverHeight` the
 * wrong input and produce 284.4330dp instead of the Odin's real 300.0dp — a 5.2% error from picking the
 * wrong box, not a rounding difference — see [theOdinsCoverHeightDependsOnPickingTheRightInputBox] below.
 * (Historical: before this ticket, the wrong box also flipped which piecewise `heightBand` arm applied —
 * 220dp instead of 300dp, a discrete "knife edge" rather than a continuous error; that banding is gone.)
 */
internal fun MimirCertifiedViewport.launchCardInputDp(): MimirDpBox =
    fullViewportDp ?: postInsetShelfDp ?: error("$name has neither box on file")

/** A tiny float-rounding allowance for the four fields ([homeLaunchCardOuterHorizontal] /
 * [homeLaunchCardOuterVertical] / [homeLaunchCardCoverColumnWidth] / [homeLaunchCardCoverHeight]) that are
 * genuinely computed via `dim * ratio` rather than returned as a production literal — this file's own
 * 4-decimal hand transcription of a real run can differ from the exact recomputed Float by up to the last
 * printed digit's rounding step, same reasoning `MimirOdinParityBaselineEpsilon` documents. `columnGap` is
 * the only field left asserted with no tolerance at all: a genuine bit-exact literal in production. */
private const val ComputedTermEpsilonDp = 0.01f

private fun assertLayoutMatches(
    viewportName: String,
    width: Float,
    height: Float,
    expectedOuterHorizontal: Float,
    expectedOuterVertical: Float,
    expectedCoverColumnWidth: Float,
    expectedCoverHeight: Float,
    expectedColumnGap: Float,
    expectedDescriptionMaxLines: Int,
    expectedCompact: Boolean,
) {
    val layout = homeLaunchCardLayout(width.dp, height.dp)
    assertEquals(
        "$viewportName outerHorizontal", expectedOuterHorizontal, layout.outerHorizontal.value, ComputedTermEpsilonDp,
    )
    assertEquals(
        "$viewportName outerVertical", expectedOuterVertical, layout.outerVertical.value, ComputedTermEpsilonDp,
    )
    assertEquals(
        "$viewportName coverColumnWidth", expectedCoverColumnWidth, layout.coverColumnWidth.value, ComputedTermEpsilonDp,
    )
    assertEquals(
        "$viewportName coverHeight", expectedCoverHeight, layout.coverHeight.value, ComputedTermEpsilonDp,
    )
    assertEquals("$viewportName columnGap", expectedColumnGap.dp, layout.columnGap)
    assertEquals("$viewportName descriptionMaxLines", expectedDescriptionMaxLines, layout.descriptionMaxLines)
    assertEquals("$viewportName compact", expectedCompact, layout.compact)
}

class HomeLaunchCardLayoutGoldenTest {

    /**
     * The full 7-field golden table, one row per [MimirCertifiedViewports] entry. Values transcribed from a
     * real run of `homeLaunchCardLayout` against each row's [launchCardInputDp]. `columnGap`/`descriptionMaxLines`/`compact` are bit-identical to the
     * pre-existing production values (still content-band literals);
     * `outerHorizontal`/`outerVertical`/`coverHeight` are three independent Odin-anchored proportional laws —
     * the first two on width (see [homeLaunchCardOuterHorizontal]'s KDoc), the third on height (see
     * [homeLaunchCardCoverHeight]'s KDoc in `HomeLaunchCard.kt`). `coverColumnWidth`  is now `minOf` of a HEIGHT term
     * (the same law as before) and a NEW WIDTH term anchored on the same Odin pair — see
     * `LaunchCardCoverColumnWidthCapRatio`'s KDoc in `HomeLaunchCard.kt` for the full derivation, the axis
     * binding algebra and the disclosed cost. Every row except the Odin (bit-exact on every pair, both terms
     * of `coverColumnWidth` included) moves from one or more convergence passes; `coverColumnWidth` itself
     * moves on every row EXCEPT `Rp5Shelf` (aspect 2.0, wider than the Odin's own 1.7778 — the height term
     * still wins there, unchanged from before this ticket).
     */
    @Test
    fun goldenTableAtEveryCertifiedViewport() {
        assertLayoutMatches(
            MimirCertifiedViewports.Odin.name, 832.5f, 468.3f,
            expectedOuterHorizontal = 44.0f, expectedOuterVertical = 24.0f,
            expectedCoverColumnWidth = 288.0f, expectedCoverHeight = 300.0f, expectedColumnGap = 36.0f,
            expectedDescriptionMaxLines = 6, expectedCompact = false,
        )
        assertLayoutMatches(
            MimirCertifiedViewports.RetroidPocketNova.name, 640.0f, 480.0f,
            expectedOuterHorizontal = 33.8258f, expectedOuterVertical = 18.4505f,
            expectedCoverColumnWidth = 221.4054f, expectedCoverHeight = 307.4952f, expectedColumnGap = 20.0f,
            expectedDescriptionMaxLines = 4, expectedCompact = true,
        )
        assertLayoutMatches(
            MimirCertifiedViewports.Rp5Shelf.name, 700.0f, 350.0f,
            expectedOuterHorizontal = 36.9970f, expectedOuterVertical = 20.1802f,
            expectedCoverColumnWidth = 215.2466f, expectedCoverHeight = 224.2152f, expectedColumnGap = 20.0f,
            expectedDescriptionMaxLines = 4, expectedCompact = true,
        )
        assertLayoutMatches(
            MimirCertifiedViewports.CompactSquare360.name, 360.0f, 360.0f,
            expectedOuterHorizontal = 19.0270f, expectedOuterVertical = 10.3784f,
            expectedCoverColumnWidth = 124.5405f, expectedCoverHeight = 230.6214f, expectedColumnGap = 14.0f,
            expectedDescriptionMaxLines = 3, expectedCompact = true,
        )
        assertLayoutMatches(
            MimirCertifiedViewports.CompactSquare411.name, 411.0f, 411.0f,
            expectedOuterHorizontal = 21.7225f, expectedOuterVertical = 11.8486f,
            expectedCoverColumnWidth = 142.1838f, expectedCoverHeight = 263.2928f, expectedColumnGap = 14.0f,
            expectedDescriptionMaxLines = 3, expectedCompact = true,
        )
        assertLayoutMatches(
            MimirCertifiedViewports.CompactSquare480.name, 480.0f, 480.0f,
            expectedOuterHorizontal = 25.3694f, expectedOuterVertical = 13.8378f,
            expectedCoverColumnWidth = 166.0541f, expectedCoverHeight = 307.4952f, expectedColumnGap = 14.0f,
            expectedDescriptionMaxLines = 3, expectedCompact = true,
        )
        // The RG Rotate, bar hidden and bar shown (compact review 2026-09-27); measured, not derived.
        assertLayoutMatches(
            MimirCertifiedViewports.CompactSquare564.name, 564.7f, 564.7f,
            expectedOuterHorizontal = 29.8460f, expectedOuterVertical = 16.2796f,
            expectedCoverColumnWidth = 195.3557f, expectedCoverHeight = 361.7553f, expectedColumnGap = 20.0f,
            expectedDescriptionMaxLines = 4, expectedCompact = true,
        )
        assertLayoutMatches(
            MimirCertifiedViewports.RgRotate.name, 564.7f, 540.7f,
            expectedOuterHorizontal = 29.8460f, expectedOuterVertical = 16.2796f,
            expectedCoverColumnWidth = 195.3557f, expectedCoverHeight = 346.3805f, expectedColumnGap = 20.0f,
            expectedDescriptionMaxLines = 4, expectedCompact = true,
        )
        assertLayoutMatches(
            MimirCertifiedViewports.CompactSquare720.name, 720.0f, 720.0f,
            expectedOuterHorizontal = 38.0541f, expectedOuterVertical = 20.7568f,
            expectedCoverColumnWidth = 249.0811f, expectedCoverHeight = 461.2428f, expectedColumnGap = 28.0f,
            expectedDescriptionMaxLines = 4, expectedCompact = false,
        )
        assertLayoutMatches(
            MimirCertifiedViewports.OdinHeightCompanion538.name, 538.0f, 444.0f,
            expectedOuterHorizontal = 28.4348f, expectedOuterVertical = 15.5099f,
            expectedCoverColumnWidth = 186.1189f, expectedCoverHeight = 284.4330f, expectedColumnGap = 20.0f,
            expectedDescriptionMaxLines = 4, expectedCompact = true,
        )
        assertLayoutMatches(
            MimirCertifiedViewports.MinimumFloor320x300.name, 320.0f, 300.0f,
            expectedOuterHorizontal = 16.9129f, expectedOuterVertical = 9.2252f,
            expectedCoverColumnWidth = 110.7027f, expectedCoverHeight = 192.1845f, expectedColumnGap = 14.0f,
            expectedDescriptionMaxLines = 3, expectedCompact = true,
        )
        assertLayoutMatches(
            MimirCertifiedViewports.LargeSquareBoundary1080.name, 1080.0f, 1080.0f,
            expectedOuterHorizontal = 57.0811f, expectedOuterVertical = 31.1351f,
            expectedCoverColumnWidth = 373.6216f, expectedCoverHeight = 691.8642f, expectedColumnGap = 36.0f,
            expectedDescriptionMaxLines = 6, expectedCompact = false,
        )
    }

    /** Every row above computed from [MimirCertifiedViewports.all] by name, so this table cannot silently
     * fall out of sync with the fixture (a new certified row with no matching call above would go
     * unnoticed by the test above, which never iterates the fixture itself — this sentinel catches that). */
    @Test
    fun goldenTableCoversEveryFixtureRow() {
        val coveredNames = setOf(
            MimirCertifiedViewports.Odin.name, MimirCertifiedViewports.RetroidPocketNova.name,
            MimirCertifiedViewports.Rp5Shelf.name, MimirCertifiedViewports.CompactSquare360.name,
            MimirCertifiedViewports.CompactSquare411.name, MimirCertifiedViewports.CompactSquare480.name,
            MimirCertifiedViewports.CompactSquare720.name, MimirCertifiedViewports.OdinHeightCompanion538.name,
            MimirCertifiedViewports.MinimumFloor320x300.name, MimirCertifiedViewports.LargeSquareBoundary1080.name,
            MimirCertifiedViewports.CompactSquare564.name, MimirCertifiedViewports.RgRotate.name,
        )
        MimirCertifiedViewports.all.forEach { viewport ->
            assertTrue(
                "${viewport.name} has no golden row in goldenTableAtEveryCertifiedViewport — add one",
                viewport.name in coveredNames,
            )
        }
    }

    /**
     * RC-3 continued (this ticket) — the `heightBand`/`minOf(heightBand, 176.dp)` clamp this test used to
     * characterize is GONE (`coverHeight` is now a continuous function of height alone), so the "knife
     * edge at 460dp" it proved no longer exists — there is no threshold left to clear or miss. What is
     * STILL load-bearing, and is what this test now proves instead: which of the Odin's two boxes
     * [MimirCertifiedViewport.launchCardInputDp] must pick still changes the answer, because `coverHeight`
     * is now sensitive to height at every value, not just around one old threshold.
     * [MimirCertifiedViewport.launchCardInputDp] (468.3dp, the real un-inset production input) gives the
     * Odin's own defining anchor, 300.0dp, bit-exact; the WRONG post-inset box (`solverInputDp`, 444dp —
     * a real box, just not this card's own input, per this file's own top-of-file KDoc) would silently
     * feed the law a different number and produce 284.4330dp instead — an 5.2% error from picking the
     * wrong box, not a rounding difference.
     */
    @Test
    fun theOdinsCoverHeightDependsOnPickingTheRightInputBox() {
        val odinHeight = MimirCertifiedViewports.Odin.launchCardInputDp().heightDp
        assertEquals(468.3f, odinHeight, 0.001f)

        val layout = homeLaunchCardLayout(832.5f.dp, odinHeight.dp)
        assertEquals(300.0f, layout.coverHeight.value, ComputedTermEpsilonDp)

        val wrongBoxHeight = MimirCertifiedViewports.Odin.postInsetShelfDp!!.heightDp
        assertEquals(444f, wrongBoxHeight, 0.001f)
        val wrongBoxLayout = homeLaunchCardLayout(832.5f.dp, wrongBoxHeight.dp)
        assertEquals(284.4330f, wrongBoxLayout.coverHeight.value, ComputedTermEpsilonDp)
    }

    /**
     * RC-3 continued (this ticket) — supersedes `heightBandCannotMoveTheWidthAtLeast520Arm` and
     * `elseArmHardcodesCoverHeightAndIgnoresHeightEntirely`, which characterized the exact defect this pass
     * fixes (the `>= 520.dp` arm's dead 176dp clamp, and the `else` arm's hardcoded 112dp). Both premises are
     * now FALSE — `coverHeight` moves continuously with height on every arm, including the two that used to
     * be dead — so this test proves the positive claim in their place: swept across the SAME height lists
     * those two tests used, on the SAME two widths (640dp = the Nova's own arm, 320dp = the `else` arm's
     * floor), `coverHeight` now strictly increases with height, never repeating a value.
     */
    @Test
    fun coverHeightNowMovesWithHeightOnEveryArmThatUsedToBeDead() {
        val heightsSpanningEveryOldBand = listOf(200f, 200.01f, 399.99f, 400f, 459.99f, 460f, 1080f)
        listOf(640f, 320f).forEach { width ->
            var previous: Float? = null
            heightsSpanningEveryOldBand.forEach { height ->
                val coverHeight = homeLaunchCardLayout(width.dp, height.dp).coverHeight.value
                previous?.let {
                    assertTrue(
                        "width=$width height=$height: coverHeight must strictly increase with height now " +
                            "(was $it, is $coverHeight) -- the old dead-clamp/hardcode this test used to " +
                            "pin no longer applies",
                        coverHeight > it,
                    )
                }
                previous = coverHeight
            }
        }
    }

    /**
     * RC-3 continued — Regression 1  supersedes `coverBoxNoLongerDependsOnWidthAtAll`, whose premise — "`coverColumnWidth` is
     * purely a function of HEIGHT, never width" — this ticket makes deliberately false: that height-only
     * anchor was the starved-text-column defect (see `LaunchCardCoverColumnWidthCapRatio`'s KDoc in
     * `HomeLaunchCard.kt` for the full derivation). `coverHeight` keeps the OLD proof unchanged below (still
     * true, still width-free); `coverColumnWidth` now proves the NEW two-constraint law instead —
     * `minOf(height * heightRatio, width * widthRatio)`, both ratios fixed points of the SAME Odin triple
     * (832.5dp wide, 468.3dp tall, 288dp column), so at the Odin's own aspect the two terms are already
     * numerically equal and `minOf` is a no-op there — the Odin stays bit-exact by construction, not by
     * re-derivation.
     */
    @Test
    fun coverHeightStaysWidthFreeButCoverColumnWidthNowTakesTheTighterOfTwoOdinAnchoredTerms() {
        val odinHeight = 468.3f
        // coverHeight: unaffected by this ticket, still purely a function of height.
        val narrow = homeLaunchCardLayout(540f.dp, odinHeight.dp)
        val wide = homeLaunchCardLayout(1080f.dp, odinHeight.dp)
        assertEquals(narrow.coverHeight, wide.coverHeight)
        assertEquals(300.0f, narrow.coverHeight.value, ComputedTermEpsilonDp)

        // coverColumnWidth at the Odin's OWN box: both terms coincide exactly, so minOf changes nothing there.
        val odin = homeLaunchCardLayout(832.5f.dp, odinHeight.dp)
        assertEquals(288.0f, odin.coverColumnWidth.value, ComputedTermEpsilonDp)

        // A panel NARROWER than the Odin's own aspect (832.5/468.3 = 1.7778) now takes the WIDTH term and
        // genuinely diverges from the old width-independent value -- 540/468.3 = 1.153 aspect, well under
        // 1.7778, the same regime the starved Nova/538-companion/COMPACT_SQUARE rows fall into.
        assertTrue(
            "540x468.3 (aspect 1.153, narrower than the Odin's 1.7778) must now take the WIDTH term and " +
                "diverge from the Odin's own 288.0dp column",
            narrow.coverColumnWidth.value < 288.0f - ComputedTermEpsilonDp,
        )
        // A panel WIDER than the Odin's own aspect keeps the OLD height-anchored value untouched -- 1080/
        // 468.3 = 2.306 aspect, past 1.7778, so the width term never wins here.
        assertEquals(288.0f, wide.coverColumnWidth.value, ComputedTermEpsilonDp)
    }

    /**
     * The Odin geometry must remain unchanged, with 0.0dp delta per term, as proved below. `outerHorizontal` is now ALSO a computed ratio at every width, including the Odin's
     * (the piecewise version's literal-constant arm is gone) -- it stays bit-exact at 44.0dp only because
     * `LaunchCardOuterHorizontalRatio` is `44f / 832.5f`, so multiplying back by `832.5f` is float32's
     * `x * (y / x) == y` identity for this exact pair; asserted here with NO tolerance, not because a literal
     * branch makes tolerance unnecessary, but because that identity has been verified to hold. `outerVertical`
     * carries the same tiny, documented float allowance as the rest of this file (it was already a computed
     * ratio at every width, including the Odin's, before this pass).
     */
    @Test
    fun theOdinsOuterPaddingIsUnmoved() {
        val odin = MimirCertifiedViewports.Odin.launchCardInputDp()
        assertEquals(44.dp, homeLaunchCardOuterHorizontal(odin.widthDp.dp))
        assertEquals(24.0f, homeLaunchCardOuterVertical(odin.widthDp.dp).value, ComputedTermEpsilonDp)
    }

    /**
     * The Odin geometry must stay at a 288dp column and 300dp cover height. The anchors below
     * reproduce both values exactly and the assertions prove that invariance.
     * `homeLaunchCardCoverHeight`/`homeLaunchCardCoverColumnWidth` stay bit-exact at 300.0dp/288.0dp only
     * because `LaunchCardCoverHeightRatio`/`LaunchCardCoverColumnWidthRatio` are `300f / 468.3f` /
     * `288f / 468.3f`, so multiplying back by `468.3f` is the same float32 `x * (y / x) == y` identity
     * [theOdinsOuterPaddingIsUnmoved] above relies on for the width-anchored pair -- asserted here with NO
     * tolerance, verified to hold rather than assumed by analogy. `homeLaunchCardCoverColumnWidth` now also
     * takes a `width` parameter (Regression 1, this ticket) -- fed the Odin's own width here, so `minOf`
     * resolves against the SAME fixed point on both terms; see
     * [coverHeightStaysWidthFreeButCoverColumnWidthNowTakesTheTighterOfTwoOdinAnchoredTerms] above for the
     * off-Odin divergence proof.
     */
    @Test
    fun theOdinsCoverArtBoxIsUnmoved() {
        val odin = MimirCertifiedViewports.Odin.launchCardInputDp()
        assertEquals(300.dp, homeLaunchCardCoverHeight(odin.heightDp.dp))
        assertEquals(288.dp, homeLaunchCardCoverColumnWidth(odin.widthDp.dp, odin.heightDp.dp))
    }

    /**
     * Monotonicity for the height-anchored pair, mirroring [outerHorizontalNeverShrinksAsWidthGrows]'s own
     * dense sweep for the width-anchored pair -- a positive-slope line through the origin only ever
     * increases, but swept anyway rather than left to algebra alone, per this file's own established
     * convention for these proportional laws. `coverColumnWidth` is now `minOf(heightTerm, widthTerm)`
     * (Regression 1, this ticket) -- fixing `width` at 1200dp (never narrower than any certified viewport)
     * keeps the width term constant across the sweep, and `minOf(nonDecreasing, constant)` is itself
     * non-decreasing, so the ORIGINAL height-only sweep still proves what it always proved for this term.
     */
    @Test
    fun coverHeightAndCoverColumnWidthNeverShrinkAsHeightGrows() {
        val sweepWidth = 1200f.dp
        var previousHeight = homeLaunchCardCoverHeight(250f.dp).value
        var previousWidth = homeLaunchCardCoverColumnWidth(sweepWidth, 250f.dp).value
        var height = 251f
        while (height <= 1200f) {
            val currentHeight = homeLaunchCardCoverHeight(height.dp).value
            val currentWidth = homeLaunchCardCoverColumnWidth(sweepWidth, height.dp).value
            assertTrue(
                "coverHeight must be non-decreasing: height=$height gave $currentHeight, " +
                    "height=${height - 1} gave $previousHeight",
                currentHeight >= previousHeight - 0.001f,
            )
            assertTrue(
                "coverColumnWidth must be non-decreasing: height=$height gave $currentWidth, " +
                    "height=${height - 1} gave $previousWidth",
                currentWidth >= previousWidth - 0.001f,
            )
            previousHeight = currentHeight
            previousWidth = currentWidth
            height += 1f
        }
    }

    /**
     * Regression 1 (this ticket) — the NEW half of monotonicity `coverColumnWidth` gained: non-decreasing in
     * WIDTH too, at a fixed height, mirroring the sweep above. Height fixed at 468.3dp (the Odin's own,
     * mid-range for this fixture) so the height term stays constant across the sweep and
     * `minOf(constant, nonDecreasing)` is itself non-decreasing.
     */
    @Test
    fun coverColumnWidthNeverShrinksAsWidthGrowsEither() {
        val sweepHeight = 468.3f.dp
        var previous = homeLaunchCardCoverColumnWidth(300f.dp, sweepHeight).value
        var width = 301f
        while (width <= 1200f) {
            val current = homeLaunchCardCoverColumnWidth(width.dp, sweepHeight).value
            assertTrue(
                "coverColumnWidth must be non-decreasing: width=$width gave $current, width=${width - 1} gave $previous",
                current >= previous - 0.001f,
            )
            previous = current
            width += 1f
        }
    }

    /**
     * `launchCard.chromeShare`'s RC-3 red cells (feature-library `MimirOdinParityTest.kt`) now FULLY
     * converge under the single proportional law (the 2026-08-21 law also converges the Nova and RP5 pins) -- recomputed from this file's own golden table, not restated as a bare claim.
     * `chromeShare(width) = outerHorizontal(width)*2/width` reduces algebraically to the constant `2 * ratio`
     * regardless of `width`, so EVERY certified width lands within float32 rounding of the Odin's own ratio
     * (`2 * 44 / 832.5`) -- there is no residual left to ratchet down, unlike the piecewise law this pass
     * replaced, which could only get the Nova/RP5-class-shelf rows partway there.
     */
    @Test
    fun chromeShareConvergesFullyUnderThePureLaw() {
        val odinRatio = 2f * 44f / 832.5f
        fun chromeShare(width: Float) = 2f * homeLaunchCardOuterHorizontal(width.dp).value / width

        listOf(320f, 380f, 480f, 538f, 640f, 700f, 720f, 832.5f, 960f, 1080f).forEach { width ->
            assertEquals(
                "chromeShare($width) must match the Odin's own ratio -- a pure proportional law holds this " +
                    "share constant by construction, so nothing here should exceed float32 rounding noise",
                odinRatio,
                chromeShare(width),
                0.0005f,
            )
        }

        assertTrue("Nova (640dp) must now converge inside ClassOneTolerance", chromeShare(640f).let {
            kotlin.math.abs(it - odinRatio) <= 0.005f
        })
        assertTrue("RP5-class shelf (700dp) must now converge inside ClassOneTolerance", chromeShare(700f).let {
            kotlin.math.abs(it - odinRatio) <= 0.005f
        })
    }

    /**
     * Arithmetic proves geometric collision; appearance needs pixel evidence. This
     * is the collision proof, not a rendering claim. Every certified viewport's content width (what
     * [homeLaunchCardTextColumnWidth] hands the metadata column) stays positive after the new
     * [homeLaunchCardOuterHorizontal] is subtracted twice, at every certified viewport this fixture has,
     * both postures.
     *
     * RC-3 Defect B : now feeds each viewport its OWN
     * real [homeLaunchCardUsesWideSkeleton] result rather than always asking the (Wide-subtracting) branch
     * -- this is exactly the guard that would fail loudly if a future change ever let a COMPACT_SQUARE
     * viewport reach the Wide skeleton's narrower text column (see that function's own KDoc for why 360/
     * 411/480/320x300 must not).
     */
    @Test
    fun noContentWidthGoesNegativeAtAnyCertifiedViewport() {
        MimirCertifiedViewports.all.forEach { viewport ->
            val box = viewport.launchCardInputDp()
            val layout = homeLaunchCardLayout(box.widthDp.dp, box.heightDp.dp)
            val wideSkeleton = homeLaunchCardUsesWideSkeleton(box.widthDp.dp, box.heightDp.dp, layout)
            val textColumnWidth = homeLaunchCardTextColumnWidth(box.widthDp.dp, layout, wideSkeleton)
            assertTrue(
                "${viewport.name}: homeLaunchCardTextColumnWidth must stay positive, was $textColumnWidth",
                textColumnWidth.value > 0f,
            )
        }
    }

    /**
     * `homeLaunchCardOuterHorizontal` must never shrink as width grows -- trivially true for a pure
     * proportional law (a positive-slope line through the origin only ever increases), but swept densely
     * across the full certified domain anyway, per the ticket's own "assert it anyway" instruction, not just
     * at the fixture's own rows, since a monotonicity violation could hide between two certified widths.
     */
    @Test
    fun outerHorizontalNeverShrinksAsWidthGrows() {
        var previous = homeLaunchCardOuterHorizontal(300f.dp).value
        var width = 301f
        while (width <= 1200f) {
            val current = homeLaunchCardOuterHorizontal(width.dp).value
            assertTrue(
                "outerHorizontal must be non-decreasing: width=$width gave $current, width=${width - 1} gave $previous",
                current >= previous - 0.001f,
            )
            previous = current
            width += 1f
        }
    }
}
