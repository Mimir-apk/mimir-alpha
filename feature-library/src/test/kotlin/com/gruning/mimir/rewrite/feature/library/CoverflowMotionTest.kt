package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class CoverflowMotionTest {

    @Test
    fun focusedSlotAlwaysSitsOnCenterOffset() {
        assertEquals(0, coverflowSlotOffsetDp(0))
    }

    @Test
    fun fixedSeatsAreSymmetricAroundCenter() {
        assertEquals(-191, coverflowSlotOffsetDp(-1))
        assertEquals(191, coverflowSlotOffsetDp(1))
        assertEquals(-332, coverflowSlotOffsetDp(-2))
        assertEquals(332, coverflowSlotOffsetDp(2))
    }

    @Test
    fun slotOffsetCanExtendForPreloadWindowsWithoutChangingTheCenter() {
        assertEquals(-473, coverflowSlotOffsetDp(-3))
        assertEquals(473, coverflowSlotOffsetDp(3))
    }

    @Test
    fun fractionalSeatsStaySymmetricAndNeverDoubleOccupyCenter() {
        assertEquals(-95.5f, coverflowSlotOffsetDp(-0.5f), 0.001f)
        assertEquals(95.5f, coverflowSlotOffsetDp(0.5f), 0.001f)

        for (step in 0..100) {
            val progress = step / 100f
            val outgoing = coverflowSlotOffsetDp(-progress)
            val incoming = coverflowSlotOffsetDp(1f - progress)
            assertTrue("two tile bodies shared center at $progress", outgoing != 0f || incoming != 0f)
            assertTrue("tile order inverted at $progress", outgoing <= incoming)
        }
    }

    @Test
    fun twoItemRingTranslatesInParallelInsteadOfSwappingHeadOn() {
        for (step in 0..100) {
            val visualCenter = step / 100f
            val outgoing = coverflowRingVisualPosition(itemIndex = 0, visualCenter, itemCount = 2)
            val incoming = coverflowRingVisualPosition(itemIndex = 1, visualCenter, itemCount = 2)
            assertEquals("two-item pitch changed at $visualCenter", 1f, incoming - outgoing, 0.001f)
            assertTrue(
                "two-item identities co-occupied center at $visualCenter",
                abs(outgoing) > 0.001f || abs(incoming) > 0.001f,
            )
        }
    }

    @Test
    fun oddRingWrapIdentityNeverTraversesCenterAndIsInvisibleAtItsTeleport() {
        for ((count, boundaryIndex, minimumDistance) in listOf(
            Triple(3, 2, 1f),
            Triple(5, 3, 2f),
            Triple(7, 4, 3f),
        )) {
            for (step in 0..100) {
                val center = step / 100f
                val position = coverflowRingVisualPosition(boundaryIndex, center, count)
                assertTrue(
                    "$count-item wrap identity approached center at $center: $position",
                    abs(position) >= minimumDistance - 0.001f,
                )
                if (abs(abs(position) - count / 2f) < 0.001f) {
                    assertEquals(0f, coverflowOddRingSeamAlpha(position, count), 0f)
                }
            }
            assertEquals(1f, coverflowOddRingSeamAlpha(minimumDistance, count), 0f)
        }
    }

    @Test
    fun evenRingHidesBothEquivalentSeamEdgesOnlyWhileMotionIsActive() {
        val motion = CoverflowRingMotionState(initialCenter = 0f).apply {
            evenSeamActive = true
        }
        assertEquals(1f, motion.edgeAlphaTarget(position = -1.5f, itemCount = 4), 0f)
        assertEquals(0f, motion.edgeAlphaTarget(position = -2f, itemCount = 4), 0f)
        assertEquals(0f, motion.edgeAlphaTarget(position = 2f, itemCount = 4), 0f)
        motion.evenSeamActive = false
        assertEquals(1f, motion.edgeAlphaTarget(position = -2f, itemCount = 4), 0f)
        assertEquals(1f, motion.edgeAlphaTarget(position = 2f, itemCount = 4), 0f)
        assertEquals(1f, motion.edgeAlphaTarget(position = 1f, itemCount = 2), 0f)
    }

    @Test
    fun nearestRingTargetWrapsOneSeatAndRetargetsFromThePaintedFraction() {
        assertEquals(7f, nearestCoverflowRingTarget(referenceCenter = 6f, focusedIndex = 0, itemCount = 7), 0f)
        assertEquals(-1f, nearestCoverflowRingTarget(referenceCenter = 0f, focusedIndex = 6, itemCount = 7), 0f)
        assertEquals(0f, nearestCoverflowRingTarget(referenceCenter = 0.42f, focusedIndex = 0, itemCount = 7), 0f)
        assertEquals(1f, nearestCoverflowRingTarget(referenceCenter = 0.42f, focusedIndex = 1, itemCount = 7), 0f)
    }

    @Test
    fun burstTargetsUnwrapAgainstPriorIntentInsteadOfLaggingPaint() {
        var logicalTarget = 0f
        val targets = (1..4).map { focusedIndex ->
            nearestCoverflowRingTarget(logicalTarget, focusedIndex, itemCount = 7).also {
                logicalTarget = it
            }
        }
        assertEquals(listOf(1f, 2f, 3f, 4f), targets)
        assertEquals(3f, nearestCoverflowRingTarget(logicalTarget, focusedIndex = 3, itemCount = 7), 0f)

        logicalTarget = 6f
        assertEquals(7f, nearestCoverflowRingTarget(logicalTarget, focusedIndex = 0, itemCount = 7), 0f)
    }

    @Test
    fun movingArtBodiesKeepClearanceForNativeAndBookShelves() {
        assertMovingBodiesKeepClearance(
            restWidth = CoverflowMotionRestWidthDp,
            focusWidth = CoverflowMotionFocusWidthDp,
            gap = CoverflowMotionGapDp,
            bookHero = false,
        )
        assertMovingBodiesKeepClearance(
            restWidth = CoverflowMotionBookRestWidthDp,
            focusWidth = CoverflowMotionBookFocusWidthDp,
            gap = CoverflowMotionBookGapDp,
            bookHero = true,
        )
    }

    @Test
    fun bookAndNativeBloomClearTheirFirstNeighbor() {
        // RC-7 (found and flagged, not fixed, in `7f80b480`):
        // coverflowNeighborClearanceDp now takes the TRUE rest scale and derives both half-widths from the
        // same geometry PlatformHeroTile actually paints with (see its own KDoc for the three corrections).
        // Native's shipped restScale is NativeShelfCoverflowRestScale (0.56), not the shared
        // CoverflowMotionRestScale (0.62) -- that shared constant is GamelistCarousel.kt's GameCard value,
        // a different surface (see NativeShelfCoverflowRestScale's own doc). Exact pins, not just `> 0f`,
        // because the old approximate function silently passed this same `> 0f` check while reporting the
        // WRONG number (+4.64dp) for a clearance that was actually an undisclosed -3.8dp overlap -- a
        // same-sign assertion cannot catch a wrong-magnitude regression; an exact pin can.
        assertEquals(
            2.62f,
            coverflowNeighborClearanceDp(
                restWidthDp = CoverflowMotionRestWidthDp,
                focusWidthDp = CoverflowMotionFocusWidthDp,
                gapDp = CoverflowMotionGapDp,
                restScale = NativeShelfCoverflowRestScale,
            ),
            0.01f,
        )
        // RC-19 v2 grew the book row's rest tile, then the503dp sizing change grew it again
        // (restScale 0.58 -> 0.9337 -> 1.0, restWidthDp 84 -> 134 -> 144). Clearance moves +7.78 ->
        // +7.31 -> +7.54dp, comfortably positive at every rung -- no new overlap introduced.
        assertEquals(
            7.54f,
            coverflowNeighborClearanceDp(
                restWidthDp = CoverflowMotionBookRestWidthDp,
                focusWidthDp = CoverflowMotionBookFocusWidthDp,
                gapDp = CoverflowMotionBookGapDp,
                restScale = CoverflowMotionBookRestScale,
            ),
            0.01f,
        )
    }

    @Test
    fun focusAcknowledgementUsesTheFinalShelfMotionContract() {
        assertEquals(1.03f, CoverflowMotionFocusScale, 0f)
        assertEquals(140, CoverflowMotionDurationMs)
        assertEquals(32, CoverflowRingSeamFadeMs)
        assertEquals(140, CoverflowOutgoingDurationMs)
        assertEquals(0.68f, CoverflowOutgoingStartAlpha, 0f)
        assertEquals(0.72f, CoverflowTitleStartAlpha, 0f)
    }

    @Test
    fun baseSeatPoseInterpolatesWithTheMovingBody() {
        assertEquals(CoverflowBasePose(scale = 1f, alpha = 1f), coverflowBasePose(centered = true, bookHero = false))
        assertEquals(CoverflowBasePose(scale = 1f, alpha = 1f), coverflowBasePose(centered = true, bookHero = true))
        assertEquals(
            CoverflowBasePose(scale = CoverflowMotionRestScale, alpha = CoverflowMotionRestAlpha),
            coverflowBasePose(centered = false, bookHero = false),
        )
        assertEquals(
            CoverflowBasePose(scale = CoverflowMotionBookRestScale, alpha = CoverflowMotionRestAlpha),
            coverflowBasePose(centered = false, bookHero = true),
        )
        assertEquals(
            CoverflowBasePose(
                scale = CoverflowMotionRestScale + (1f - CoverflowMotionRestScale) * 0.5f,
                alpha = CoverflowMotionRestAlpha + (1f - CoverflowMotionRestAlpha) * 0.5f,
            ),
            coverflowBasePose(centerProgress = 0.5f, bookHero = false),
        )
    }

    @Test
    fun focusedArtRingAndBloomFitTheSharedArtBand() {
        assertTrue(
            coverflowFocusedVisualHeightDp(CoverflowMotionFocusHeightDp) <= CoverflowArtBandHeightDp,
        )
        assertTrue(
            coverflowFocusedVisualHeightDp(CoverflowMotionBookFocusHeightDp) <= CoverflowArtBandHeightDp,
        )
        assertTrue(coverflowFocusedVisualWidthDp(CoverflowMotionFocusWidthDp) <= 538f)
        assertTrue(coverflowFocusedVisualWidthDp(CoverflowMotionBookFocusWidthDp) <= 538f)
        assertEquals(
            CoverflowArtBandHeightDp + CoverflowTitleBandHeightDp,
            CoverflowHeroBandHeightDp,
        )
    }

    @Test
    fun extraMountedViewportSeatIsAlwaysAlphaZero() {
        assertEquals(1f, coverflowViewportAlphaTarget(-3f, paintedRadius = 3), 0f)
        assertEquals(1f, coverflowViewportAlphaTarget(3f, paintedRadius = 3), 0f)
        assertEquals(0f, coverflowViewportAlphaTarget(-4f, paintedRadius = 3), 0f)
        assertEquals(0f, coverflowViewportAlphaTarget(4f, paintedRadius = 3), 0f)
    }

    @Test
    fun windowedLinearSeatEqualsSlotPositionAtSettle() {
        // Nav-01 slice 3: the windowed gamelist derives each card's continuous seat from ONE gliding
        // absolute centre (`itemIndex - center`). At settle the centre equals the focused index, so the
        // seat equals the old discrete slot position EXACTLY — the parity the visual oracles sample.
        val focusedIndex = 8
        val settled = CoverflowLinearMotionState(focusedIndex.toFloat())
        assertEquals(0f, settled.visualPosition(focusedIndex), 1e-4f)
        assertEquals(1f, settled.visualPosition(focusedIndex + 1), 1e-4f)
        assertEquals(-2f, settled.visualPosition(focusedIndex - 2), 1e-4f)
    }

    @Test
    fun windowedLinearCentreTrailsTheTargetMidGlide() {
        // Mid-hold the centre lags the focused index (constant-velocity cruise), so the centred card leads
        // dead-centre and never brakes into the seat; only at settle (centre == index) is its seat zero.
        val focusedIndex = 20
        val midGlide = CoverflowLinearMotionState((focusedIndex - 1).toFloat())
        assertTrue("centred card leads while the centre trails", midGlide.visualPosition(focusedIndex) > 0f)
        val settled = CoverflowLinearMotionState(focusedIndex.toFloat())
        assertEquals(0f, settled.visualPosition(focusedIndex), 1e-4f)
    }

    //  Root
    // cause per the handed-down diagnosis, verified here: CoverflowMotion.kt's slot geometry never
    // referenced the measured viewport, so the shipped fixed radius (3, both rows) painted a tile fully
    // opaque past the edge of any viewport narrower than the one it was implicitly tuned against. This is
    // the missing check CoverflowMotionTest never had — only coverflowNeighborClearanceDp's WIDTH-INDEPENDENT
    // first-neighbour check existed before.
    //
    // These certified widths are NOT the raw device dp — LibraryScreen.kt's shelf Column applies
    // `ShelfHorizontalGutter` (20dp) padding on each side BEFORE ShelfCoverflow/NativeShelfCoverflow's own
    // BoxWithConstraints ever measures `maxWidth`, and the Nova (640x480dp) is standard posture (aspect
    // 1.33 > CompactSquareMaximumAspect 1.12), which keeps that 20dp gutter rather than the compact 12dp
    // one. So the value this fix actually reads is device width minus 40dp — confirmed by reading
    // LibraryScreen.kt's Column padding and `solveLibraryShelfGeometry`, not assumed.
    private val NovaMeasuredWidthDp = 600f // 640 - 2*20
    private val OdinMeasuredWidthDp = 792.5f // 832.5 - 2*20

    @Test
    fun boundedWindowRadiusFitsRadiusOneWithMarginOnBothCertifiedDevicesAfterRc19v2() {
        // ShelfCoverflow.kt caps maxRadius at CoverflowWindowRadius=1. The larger book-row tiles
        // must still fit one painted neighbour per side on both reference viewports with margin.
        // Painted edge = offset(1) + (144 * BookRestScale 1.0)/2 = 164 + 72 = 236dp.
        // Rest paints the full 144dp measured box; the earlier scale produced 226.2264dp.
        val trueRadiusOneEdgeDp = coverflowSlotOffsetDp(
            position = 1,
            restWidthDp = CoverflowMotionBookRestWidthDp,
            focusWidthDp = CoverflowMotionBookFocusWidthDp,
            gapDp = CoverflowMotionBookGapDp,
        ) + (CoverflowMotionBookFocusWidthDp * CoverflowMotionBookRestScale) / 2f
        assertEquals(236.0f, trueRadiusOneEdgeDp, 0.01f)

        val odinBookRadius = coverflowBoundedWindowRadius(
            viewportWidthDp = OdinMeasuredWidthDp,
            maxRadius = 1, // ShelfCoverflow.kt's private CoverflowWindowRadius, hand-copied -- not visible cross-file
            restWidthDp = CoverflowMotionBookRestWidthDp,
            focusWidthDp = CoverflowMotionBookFocusWidthDp,
            gapDp = CoverflowMotionBookGapDp,
            restScale = CoverflowMotionBookRestScale,
        )
        assertEquals("Odin must reach the full radius-1 design at the grown tile size", 1, odinBookRadius)
        assertTrue(
            "Odin's radius-1 edge must clear its measured half-width with real margin, not just fit",
            trueRadiusOneEdgeDp < OdinMeasuredWidthDp / 2f - 100f,
        )

        val novaBookRadius = coverflowBoundedWindowRadius(
            viewportWidthDp = NovaMeasuredWidthDp,
            maxRadius = 1, // ShelfCoverflow.kt's private CoverflowWindowRadius, hand-copied -- not visible cross-file
            restWidthDp = CoverflowMotionBookRestWidthDp,
            focusWidthDp = CoverflowMotionBookFocusWidthDp,
            gapDp = CoverflowMotionBookGapDp,
            restScale = CoverflowMotionBookRestScale,
        )
        assertEquals(
            "Nova must ALSO reach the full radius-1 design -- unlike the old radius-3 design, RC-19 v2 " +
                "does not narrow on the Nova",
            1,
            novaBookRadius,
        )
        assertTrue(
            "Nova's radius-1 edge must clear its measured half-width with real margin, not just fit",
            trueRadiusOneEdgeDp < NovaMeasuredWidthDp / 2f - 50f,
        )
    }

    @Test
    fun nativeRowBoundedRadiusRecoveredToTwoOnTheOdinByShrinkingRestScaleButStaysOneOnTheNova() {
        // Native row (NativeShelfCoverflow, no item-count gate at all): the DEFAULT rest/focus/gap constants
        // (123/224/18dp) place neighbours at 191/332/473dp -- coverflowSlotOffsetDp's own
        // `(focusWidthDp + restWidthDp) / 2` is INTEGER division (173, not 173.5), so these are exact, not
        // ".5" values. Unlike the book row, `restWidthDp` (123) UNDERSTATES this row's true painted width:
        // PlatformHeroTile lays the tile out at the full focusWidthDp (224) and scales it by restScale at
        // rest, so the real painted half-width is `224*restScale/2`, not `123/2=61.5dp`.
        //
        // originalNativeRestScale pins the value this row shipped with (original rest scale,0.62) as an explicit literal. [CoverflowMotionRestScale] itself is UNCHANGED --
        // it stays 0.62 (see its own doc: GamelistCarousel.kt's GameCard shares it, and this fix must not
        // silently re-tune that separate surface). The native-only radius2 fit change instead introduced [NativeShelfCoverflowRestScale] (0.56), a NATIVE-ONLY
        // value NativeShelfCoverflow.kt applies as an extra outer multiplier on top of the shared 0.62 pose.
        // Both numbers are pinned here so the before/after stays legible in one place instead of only in a
        // commit message.
        val originalNativeRestScale = 0.62f
        val preFixRadius2EdgeDp = abs(
            coverflowSlotOffsetDp(
                position = 2,
                restWidthDp = CoverflowMotionRestWidthDp,
                focusWidthDp = CoverflowMotionFocusWidthDp,
                gapDp = CoverflowMotionGapDp,
            ),
        ) + (CoverflowMotionFocusWidthDp * originalNativeRestScale) / 2f
        // 332 + 224*0.62/2=69.44 = 401.44dp -- past the Odin's measured half-width (396.25dp) by 5.19dp, even
        // though offset(2) alone (332dp) looks safely inside it. "A constant that happens to fit the Odin"
        // (the prior diagnosis's framing for why this whole class went unreported) was true for the book
        // row's radius 3, but was NEVER true for this row's radius 2 or 3 at the original 0.62 scale.
        assertEquals(401.44f, preFixRadius2EdgeDp, 0.01f)
        assertTrue(
            "the ORIGINAL 0.62-scale native radius-2 tile's TRUE painted edge must sit past the Odin's " +
                "measured half-width",
            preFixRadius2EdgeDp > OdinMeasuredWidthDp / 2f,
        )

        // Live behaviour, at the NATIVE-ONLY NativeShelfCoverflowRestScale (0.56) -- what NativeShelfCoverflow.kt
        // actually passes to coverflowBoundedWindowRadius, and what its extra outer multiplier actually paints.
        val odinNativeRadius = coverflowBoundedWindowRadius(
            viewportWidthDp = OdinMeasuredWidthDp,
            maxRadius = 3,
            restWidthDp = CoverflowMotionRestWidthDp,
            focusWidthDp = CoverflowMotionFocusWidthDp,
            gapDp = CoverflowMotionGapDp,
            restScale = NativeShelfCoverflowRestScale,
        )
        // The native-only rest-scale change bought radius 2 back on the certified Odin: 332 + 224*0.56/2=62.72 = 394.72dp,
        // a 1.53dp margin under the Odin's 396.25dp half-width -- not the razor-thin 0.41dp margin restScale
        // 0.57 would have left.
        assertEquals(2, odinNativeRadius)

        val preFixRadius3EdgeDp = abs(
            coverflowSlotOffsetDp(
                position = 3,
                restWidthDp = CoverflowMotionRestWidthDp,
                focusWidthDp = CoverflowMotionFocusWidthDp,
                gapDp = CoverflowMotionGapDp,
            ),
        ) + (CoverflowMotionFocusWidthDp * NativeShelfCoverflowRestScale) / 2f
        assertTrue(
            "radius-3's TRUE painted edge must still sit well past the Odin even at the shrunk rest scale",
            preFixRadius3EdgeDp > OdinMeasuredWidthDp / 2f,
        )

        // The NOVA does not move, and cannot via this lever: closing its gap at radius 2 needs the edge to
        // drop by 101.44dp (401.44 - NovaMeasuredWidthDp/2=300), but coverflowSlotOffsetDp(2)'s own SPACING
        // term alone (332dp, ignoring any painted width at all) already exceeds the Nova's 300dp half-width
        // by 32dp -- so even a fully invisible rest scale (recovering the other 69.44dp) could never reach
        // radius 2 on the Nova. Only cutting restWidthDp/gapDp roughly in half would, and Nova tiles are already small -- both size constraints cannot be satisfied, so this stays at 1,
        // reported rather than silently forced.
        val novaNativeRadius = coverflowBoundedWindowRadius(
            viewportWidthDp = NovaMeasuredWidthDp,
            maxRadius = 3,
            restWidthDp = CoverflowMotionRestWidthDp,
            focusWidthDp = CoverflowMotionFocusWidthDp,
            gapDp = CoverflowMotionGapDp,
            restScale = NativeShelfCoverflowRestScale,
        )
        assertEquals(1, novaNativeRadius)
    }

    @Test
    fun nativeShelfRestScaleIsIsolatedFromTheSharedPoseGamelistCarouselAlsoReads() {
        // Guard against re-doing the 2026-08-20 fix the EASY-BUT-WRONG way: lowering the shared
        // [CoverflowMotionRestScale] constant directly. That constant also drives GamelistCarousel.kt's
        // GameCard (bookHero = false there too, both its presentationAlpha and its graphicsLayer scaleX/Y) --
        // a separately-tuned, unrelated surface this task was never asked to touch. The fix must live ONLY in
        // [NativeShelfCoverflowRestScale], applied as NativeShelfCoverflow.kt's own extra outer multiplier.
        assertEquals(
            "CoverflowMotionRestScale is shared with GamelistCarousel.kt -- it must stay at the ORIGINAL " +
                "0.62 rest-alpha value, not be repurposed for this native-only radius fix",
            0.62f,
            CoverflowMotionRestScale,
            0f,
        )
        assertEquals(
            "NativeShelfCoverflowRestScale is the isolated native-only lever the 2026-08-20 fix actually uses",
            0.56f,
            NativeShelfCoverflowRestScale,
            0f,
        )
        // The book row's own rest scale is untouched by either of the above -- it moved for an unrelated
        // reason (larger book-row tiles), pinned at its own value: `1f / HomeShelfSelectedScale` (1.071), rounded to
        // 1.0 after the503dp sizing change -- see [CoverflowMotionBookRestScale]'s own doc.
        assertEquals(1.0f, CoverflowMotionBookRestScale, 0f)
    }

    // A bare cyclic ring can produce a 1-vs-2 side split; this checks coverflowRingVisualPosition
    // math independently of tile size and radius. At the old book-row constants, viewport-fit
    // bounding never shrank below radius2 at any reference width. The current design radius1
    // is explicit and is tested separately below.
    @Test
    fun ringVisualPositionTiesTheAntipodeToOneSideForEveryEvenItemCount() {
        // Literal repro of the captured device case: 4 platforms, focus on index 0, at rest
        // (visualCenter == focusedIndex, no motion in flight). Canonical positions must be {-1, 0, 1, 2} --
        // ONE left neighbour, TWO right -- reproducing "1 left / 2 right" independent of viewport or scale.
        val positions = (0 until 4).map { itemIndex ->
            coverflowRingVisualPosition(itemIndex = itemIndex, visualCenter = 0f, itemCount = 4)
        }
        assertEquals(listOf(0f, 1f, 2f, -1f), positions)
        assertEquals("left neighbours", 1, positions.count { it < 0f })
        assertEquals("right neighbours", 2, positions.count { it > 0f })

        // The tie-break is NOT a fixed "always right" rule -- which side gets the extra tile depends on
        // where the raw itemIndex-focusedIndex difference falls before wrapping, i.e. on the FOCUSED index
        // itself, not just item count. Focus on index 2 (same 4-item ring) flips it to 2 left / 1 right --
        // pinned so nobody reports the heavier side switching as a bug when focus simply moved.
        val positionsFocusedOnTwo = (0 until 4).map { itemIndex ->
            coverflowRingVisualPosition(itemIndex = itemIndex, visualCenter = 2f, itemCount = 4)
        }
        assertEquals(listOf(-2f, -1f, 0f, 1f), positionsFocusedOnTwo)
        assertEquals("left neighbours flip to 2 when focus is on index 2", 2, positionsFocusedOnTwo.count { it < 0f })
        assertEquals("right neighbours flip to 1 when focus is on index 2", 1, positionsFocusedOnTwo.count { it > 0f })

        // What never changes, for ANY focused index in an even 4-item ring: the 3 non-hero items split
        // 1-vs-2, never 1.5-vs-1.5 -- there is no focus position that reads symmetric. This follows directly from ring geometry: an even ring cannot centre a focused tile
        // AND paint a symmetric neighbour count, so the asymmetry stands rather than being hidden or doubled.
        (0 until 4).forEach { focusIdx ->
            val perFocusPositions = (0 until 4).map { itemIndex ->
                coverflowRingVisualPosition(itemIndex = itemIndex, visualCenter = focusIdx.toFloat(), itemCount = 4)
            }
            val left = perFocusPositions.count { it < 0f }
            val right = perFocusPositions.count { it > 0f }
            assertEquals("focus=$focusIdx: left+right must account for all 3 non-hero items", 3, left + right)
            assertTrue("focus=$focusIdx: never symmetric -- expected ring geometry (left=$left, right=$right)", left != right)
        }
    }

    @Test
    fun fourPlatformWindowPaintsOnlyThreeAtRadiusOneAsRuled() {
        // A radius1 window paints three tiles for a4-item ring, one neighbor per side. Earlier
        // symmetric-radius and mirrored-antipode variants hid or duplicated an identity.
        // ShelfCoverflow now keeps the ring math and changes only CoverflowWindowRadius to1.
        // modulo wrap still lands at {0, 1, 2, -1} (unchanged, pinned separately above in
        // `ringVisualPositionTiesTheAntipodeToOneSideForEveryEvenItemCount` -- that is pure ring math, not
        // touched by this ticket) but at radius 1 the antipode (position 2) is now OUTSIDE the paint
        // window, so it does not paint -- 3 tiles, not 4. This is the exact defect the regression report described: at
        // radius 2 the group's painted bounding box was asymmetric (1 left / 2 right) and swung up to
        // 103dp between adjacent focus positions ([coverflowRowRecenterOffsetDp]'s own doc); at radius 1
        // the split is symmetric BY CONSTRUCTION (see that same function's RC-19 v2 addendum), so nothing
        // needs correcting for N=4 specifically anymore, only for N=2.
        //
        // DO NOT revert `CoverflowWindowRadius` back toward 3 to "restore" 4-tiles-once -- that is exactly
        // the shape this policy retired.
        listOf(
            "Nova/RP5-class shelf bracket" to NovaMeasuredWidthDp,
            "Odin" to OdinMeasuredWidthDp,
        ).forEach { (label, viewportWidthDp) ->
            val radius = coverflowBoundedWindowRadius(
                viewportWidthDp = viewportWidthDp,
                maxRadius = 1, // ShelfCoverflow.kt's private CoverflowWindowRadius, hand-copied -- not visible cross-file
                restWidthDp = CoverflowMotionBookRestWidthDp,
                focusWidthDp = CoverflowMotionBookFocusWidthDp,
                gapDp = CoverflowMotionBookGapDp,
                restScale = CoverflowMotionBookRestScale,
            )
            assertEquals("$label: radius must be exactly 1 -- the RC-19 v2 design radius", 1, radius)

            val positions = (0 until 4).map { itemIndex ->
                coverflowRingVisualPosition(itemIndex = itemIndex, visualCenter = 0f, itemCount = 4)
            }
            assertEquals(listOf(0f, 1f, 2f, -1f), positions)
            val alphas = positions.map { position ->
                coverflowViewportAlphaTarget(targetPosition = position, paintedRadius = radius)
            }
            assertEquals(
                "$label: the antipode (position 2) is now the ONE stop excluded from paint",
                listOf(1f, 1f, 0f, 1f),
                alphas,
            )
            assertEquals(
                "$label: painted tile count == 3, one identity now unpainted while another stop is focused",
                3,
                alphas.count { it == 1f },
            )
            assertEquals("$label: left neighbours", 1, positions.count { it < 0f })
            // Position 2 (the antipode) no longer counts as a "right neighbour" for painting purposes --
            // it exists in the ring but sits outside radius 1, so only ONE right neighbour paints.
            assertEquals(
                "$label: right neighbours actually painted",
                1,
                positions.filter { it > 0f }.count { abs(it) <= radius.toFloat() },
            )
        }
    }

    @Test
    fun sixItemAntipodalTileNoLongerPaintsPastTheNovaHalfWidth() {
        // Literal repro of the cited mechanism (CoverflowMotion.kt:213-217): a 6-item ring rests with
        // evenSeamActive == false, so CoverflowRingMotionState.edgeAlphaTarget's `else 1f` branch keeps the
        // antipodal tile (position == 3) fully opaque regardless of viewport -- that part of the ring's
        // seam behaviour is untouched by this fix. The overflow lived one level up, in the FIXED radius fed
        // to coverflowViewportAlphaTarget.
        val motion = CoverflowRingMotionState(initialCenter = 0f)
        val position = coverflowRingVisualPosition(itemIndex = 3, visualCenter = 0f, itemCount = 6)
        assertEquals(3f, abs(position), 0.001f)
        assertEquals(1f, motion.edgeAlphaTarget(position, itemCount = 6), 0f)

        // Pre-fix (the shipped fixed radius 3): this tile was opaque...
        assertEquals(1f, coverflowViewportAlphaTarget(position, paintedRadius = 3), 0f)
        // ...and its own true painted edge sat past the Nova's measured half-width -- the reported overflow.
        val preFixEdgeDp = abs(
            coverflowSlotOffsetDp(
                position = position,
                restWidthDp = CoverflowMotionBookRestWidthDp,
                focusWidthDp = CoverflowMotionBookFocusWidthDp,
                gapDp = CoverflowMotionBookGapDp,
            ),
        ) + (CoverflowMotionBookFocusWidthDp * CoverflowMotionBookRestScale) / 2f
        assertTrue(preFixEdgeDp > NovaMeasuredWidthDp / 2f)

        // Post-fix: the measured-viewport-bounded radius at the Nova's width correctly zeroes it.
        val boundedRadius = coverflowBoundedWindowRadius(
            viewportWidthDp = NovaMeasuredWidthDp,
            maxRadius = 3,
            restWidthDp = CoverflowMotionBookRestWidthDp,
            focusWidthDp = CoverflowMotionBookFocusWidthDp,
            gapDp = CoverflowMotionBookGapDp,
            restScale = CoverflowMotionBookRestScale,
        )
        assertEquals(0f, coverflowViewportAlphaTarget(position, paintedRadius = boundedRadius), 0f)
    }

    @Test
    fun certifiedViewportsKeepEveryOpaqueBookSlotInsideTheMeasuredHalfWidth() {
        // Modelled on HomeShelfGeometryTest.certifiedViewportsKeepFocusedArtBloomAndBandsInBounds: assert
        // every PAINTED (alpha > 0) slot's own TRUE rendered edge stays inside the measured viewport, at
        // both certified widths (Nova, Odin) and across the item counts the diagnosis named (5 clean, 6
        // exactly antipodal, 7 doubly so).
        listOf(NovaMeasuredWidthDp, OdinMeasuredWidthDp).forEach { widthDp ->
            listOf(5, 6, 7).forEach { itemCount ->
                val radius = coverflowBoundedWindowRadius(
                    viewportWidthDp = widthDp,
                    maxRadius = 3,
                    restWidthDp = CoverflowMotionBookRestWidthDp,
                    focusWidthDp = CoverflowMotionBookFocusWidthDp,
                    gapDp = CoverflowMotionBookGapDp,
                    restScale = CoverflowMotionBookRestScale,
                )
                (0 until itemCount).forEach { itemIndex ->
                    val position = coverflowRingVisualPosition(itemIndex, visualCenter = 0f, itemCount = itemCount)
                    val alpha = coverflowViewportAlphaTarget(position, paintedRadius = radius)
                    if (alpha > 0f) {
                        val edgeDp = abs(
                            coverflowSlotOffsetDp(
                                position = position,
                                restWidthDp = CoverflowMotionBookRestWidthDp,
                                focusWidthDp = CoverflowMotionBookFocusWidthDp,
                                gapDp = CoverflowMotionBookGapDp,
                            ),
                        ) + (CoverflowMotionBookFocusWidthDp * CoverflowMotionBookRestScale) / 2f
                        assertTrue(
                            "$widthDp dp / $itemCount items: opaque slot at position $position " +
                                "(edge ${edgeDp}dp) must stay inside the half-viewport (${widthDp / 2f}dp)",
                            edgeDp <= widthDp / 2f + 0.01f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun certifiedViewportsKeepEveryOpaqueNativeSlotInsideTheMeasuredHalfWidth() {
        // The linear/native counterpart: no wrap, no count gate, so ANY count >= 7 with focus away from both
        // ends is the class that matters (the diagnosis: "Installed-app lists routinely exceed 7"). Uses
        // [NativeShelfCoverflowRestScale] (0.56), NOT the shared [CoverflowMotionRestScale] (0.62) --
        // NativeShelfCoverflow.kt's own extra outer multiplier is what makes the real paint match this bound;
        // the shared constant is left at its original value for GamelistCarousel.kt (see its doc).
        listOf(NovaMeasuredWidthDp, OdinMeasuredWidthDp).forEach { widthDp ->
            listOf(7, 12, 30).forEach { itemCount ->
                val radius = coverflowBoundedWindowRadius(
                    viewportWidthDp = widthDp,
                    maxRadius = 3,
                    restWidthDp = CoverflowMotionRestWidthDp,
                    focusWidthDp = CoverflowMotionFocusWidthDp,
                    gapDp = CoverflowMotionGapDp,
                    restScale = NativeShelfCoverflowRestScale,
                )
                val focusedIndex = itemCount / 2
                (0 until itemCount).forEach { itemIndex ->
                    val slotPosition = (itemIndex - focusedIndex).toFloat()
                    val alpha = coverflowViewportAlphaTarget(slotPosition, paintedRadius = radius)
                    if (alpha > 0f) {
                        val edgeDp = abs(
                            coverflowSlotOffsetDp(
                                position = slotPosition,
                                restWidthDp = CoverflowMotionRestWidthDp,
                                focusWidthDp = CoverflowMotionFocusWidthDp,
                                gapDp = CoverflowMotionGapDp,
                            ),
                        ) + (CoverflowMotionFocusWidthDp * NativeShelfCoverflowRestScale) / 2f
                        assertTrue(
                            "$widthDp dp / $itemCount items: opaque native slot at $slotPosition " +
                                "(edge ${edgeDp}dp) must stay inside the half-viewport (${widthDp / 2f}dp)",
                            edgeDp <= widthDp / 2f + 0.01f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun rowRecenterOffsetIsZeroForSymmetricSplits() {
        // A partial window (radius strictly inside the ring, never reaching the wrap seam) already paints
        // the same count on both sides -- no correction should apply.
        assertEquals(0f, coverflowRowRecenterOffsetDp(0, 0, 84, 144, 20, 0.58f))
        assertEquals(0f, coverflowRowRecenterOffsetDp(2, 2, 84, 144, 20, 0.58f))
        assertEquals(0f, coverflowRowRecenterOffsetDp(3, 3, 84, 144, 20, 0.58f))
    }

    @Test
    fun rowRecenterOffsetPinsThe82DpBookRowCorrectionForTheOneTileOffSplit() {
        // RC-18-era ring shape: a 4-platform ring at radius 2 always split 1-vs-2
        // (ringVisualPositionTiesTheAntipodeToOneSideForEveryEvenItemCount above) -- reproduced here as the
        // exact left/right radii ShelfCoverflow.kt derived from that split, BACK WHEN it painted radius 2 for
        // N=4. RC-19 v2  caps the row's own radius at 1, so
        // ShelfCoverflow.kt itself no longer reaches this 1-vs-2 case for any itemCount >= 3 (see
        // `fourPlatformWindowPaintsOnlyThreeAtRadiusOneAsRuled` and this function's own RC-19 v2 doc
        // addendum) -- kept here as a pure-function regression pin (still reachable at itemCount == 2 in a
        // DIFFERENT shape, `extent(0)` vs `extent(1)`, covered separately below) and because
        // [coverflowRowRecenterOffsetDp] itself is a general two-argument function, not a book-row-only one.
        // coverflowSlotOffsetDp's per-step distance beyond the first neighbour is the constant restWidthDp +
        // gapDp -- 144 + 20 = 164 after the503dp sizing change (134+20 before it, 84+20
        // originally) -- so the correction is exactly
        // half that, signed toward the SHORTER side, regardless of viewport.
        val bookOnePlusTwo = coverflowRowRecenterOffsetDp(
            leftRadius = 1,
            rightRadius = 2,
            restWidthDp = CoverflowMotionBookRestWidthDp,
            focusWidthDp = CoverflowMotionBookFocusWidthDp,
            gapDp = CoverflowMotionBookGapDp,
            restScale = CoverflowMotionBookRestScale,
        )
        assertEquals(-82f, bookOnePlusTwo, 0.01f)

        val bookTwoPlusOne = coverflowRowRecenterOffsetDp(
            leftRadius = 2,
            rightRadius = 1,
            restWidthDp = CoverflowMotionBookRestWidthDp,
            focusWidthDp = CoverflowMotionBookFocusWidthDp,
            gapDp = CoverflowMotionBookGapDp,
            restScale = CoverflowMotionBookRestScale,
        )
        assertEquals(82f, bookTwoPlusOne, 0.01f)
    }

    @Test
    fun bookRowPaintsExactlyThreeSymmetricTilesAtRadiusOneForEveryItemCountAtBothCertifiedViewports() {
        // The three-tile acceptance check reproduces ShelfCoverflow.kt's ACTUAL per-frame derivation --
        // coverflowBoundedWindowRadius capped at the row's design radius (1), then the farthest PAINTED
        // position on each side under the identical alpha gate the composable uses -- and asserts, for
        // itemCount 3 through 12 and every focused index, at both certified widths:
        //  1. the radius comes back exactly 1 (never shrinks -- both devices have real margin, pinned above
        //     in `boundedWindowRadiusFitsRadiusOneWithMarginOnBothCertifiedDevicesAfterRc19v2`),
        //  2. exactly 3 stops paint (the focused hero + one neighbour per side), never 2 or 4+,
        //  3. the split is always 1-left/1-right (a radius-1 window can never reach the ring's wrap seam,
        //     see coverflowRowRecenterOffsetDp's RC-19 v2 doc addendum), so
        //  4. the recentre correction is always exactly 0 -- the focused tile sits at TRUE centre, not just
        //     approximately, at every focus position. This is the "no more than 3dp swing" property the
        //     traverse must preserve; the OLD radius-2 shape swung up to 103dp between adjacent
        //     focus positions on a 4-item ring (measured 268.5dp / 372.5dp against a 320dp screen centre) -- this test proves that
        //     class of swing is now structurally impossible for itemCount >= 3, not merely smaller.
        listOf(NovaMeasuredWidthDp, OdinMeasuredWidthDp).forEach { widthDp ->
            val radius = coverflowBoundedWindowRadius(
                viewportWidthDp = widthDp,
                maxRadius = 1, // ShelfCoverflow.kt's private CoverflowWindowRadius, hand-copied -- not visible cross-file
                restWidthDp = CoverflowMotionBookRestWidthDp,
                focusWidthDp = CoverflowMotionBookFocusWidthDp,
                gapDp = CoverflowMotionBookGapDp,
                restScale = CoverflowMotionBookRestScale,
            )
            assertEquals("$widthDp dp: design radius must be exactly 1, never shrunk", 1, radius)

            (3..12).forEach { itemCount ->
                (0 until itemCount).forEach { focusedIndex ->
                    var left = 0
                    var right = 0
                    var painted = 0
                    (0 until itemCount).forEach { itemIndex ->
                        val position = coverflowRingVisualPosition(
                            itemIndex = itemIndex,
                            visualCenter = focusedIndex.toFloat(),
                            itemCount = itemCount,
                        )
                        if (abs(position) <= radius.toFloat()) {
                            painted++
                            if (position < 0f) left = maxOf(left, (-position).roundToInt())
                            if (position > 0f) right = maxOf(right, position.roundToInt())
                        }
                    }
                    val context = "$widthDp dp / $itemCount items / focus=$focusedIndex"
                    assertEquals("$context: painted count must be exactly 3", 3, painted)
                    assertEquals("$context: left neighbour radius", 1, left)
                    assertEquals("$context: right neighbour radius", 1, right)
                    val correction = coverflowRowRecenterOffsetDp(
                        leftRadius = left,
                        rightRadius = right,
                        restWidthDp = CoverflowMotionBookRestWidthDp,
                        focusWidthDp = CoverflowMotionBookFocusWidthDp,
                        gapDp = CoverflowMotionBookGapDp,
                        restScale = CoverflowMotionBookRestScale,
                    )
                    assertEquals(
                        "$context: recentre correction must be exactly 0 -- symmetric by construction",
                        0f,
                        correction,
                        0f,
                    )
                }
            }
        }
    }

    @Test
    fun rowRecenterOffsetNeverWidensThePaintedRowAtCertifiedViewports() {
        // The same leftRadius/rightRadius derivation ShelfCoverflow.kt performs (farthest PAINTED position
        // on each side under the identical alpha gate), swept across both certified widths and every item
        // count from 2 (the smallest real library -- both sides can have zero PAINTED NEIGHBOURS here,
        // bounded only by the focused tile's own edge) to 9, every focused index. Asserts the property
        // coverflowRowRecenterOffsetDp's own doc claims: the corrected row is never wider than the
        // uncorrected one, and never exceeds the half-viewport coverflowBoundedWindowRadius already
        // bounded it to.
        listOf(NovaMeasuredWidthDp, OdinMeasuredWidthDp).forEach { widthDp ->
            val radius = coverflowBoundedWindowRadius(
                viewportWidthDp = widthDp,
                maxRadius = 3,
                restWidthDp = CoverflowMotionBookRestWidthDp,
                focusWidthDp = CoverflowMotionBookFocusWidthDp,
                gapDp = CoverflowMotionBookGapDp,
                restScale = CoverflowMotionBookRestScale,
            )
            val halfPaintedWidthDp = (CoverflowMotionBookFocusWidthDp * CoverflowMotionBookRestScale) / 2f
            val halfFocusedWidthDp = coverflowFocusedVisualWidthDp(CoverflowMotionBookFocusWidthDp) / 2f
            (2..9).forEach { itemCount ->
                (0 until itemCount).forEach { focusedIndex ->
                    var left = 0
                    var right = 0
                    (0 until itemCount).forEach { itemIndex ->
                        val position = coverflowRingVisualPosition(
                            itemIndex = itemIndex,
                            visualCenter = focusedIndex.toFloat(),
                            itemCount = itemCount,
                        )
                        if (abs(position) <= radius.toFloat()) {
                            if (position < 0f) left = maxOf(left, (-position).toInt())
                            if (position > 0f) right = maxOf(right, position.toInt())
                        }
                    }
                    fun extent(r: Int): Float = if (r <= 0) {
                        // No painted neighbour on this side -- mirrors coverflowRowRecenterOffsetDp's own
                        // treatment: the nearest painted edge is the focused tile's own (bloom-scaled) edge.
                        halfFocusedWidthDp
                    } else {
                        abs(
                            coverflowSlotOffsetDp(
                                position = r,
                                restWidthDp = CoverflowMotionBookRestWidthDp,
                                focusWidthDp = CoverflowMotionBookFocusWidthDp,
                                gapDp = CoverflowMotionBookGapDp,
                            ),
                        ) + halfPaintedWidthDp
                    }
                    val leftExtent = extent(left)
                    val rightExtent = extent(right)
                    val uncorrectedMax = maxOf(leftExtent, rightExtent)
                    val correction = coverflowRowRecenterOffsetDp(
                        leftRadius = left,
                        rightRadius = right,
                        restWidthDp = CoverflowMotionBookRestWidthDp,
                        focusWidthDp = CoverflowMotionBookFocusWidthDp,
                        gapDp = CoverflowMotionBookGapDp,
                        restScale = CoverflowMotionBookRestScale,
                    )
                    // ShelfCoverflow.kt adds `recenterOffsetDp` directly onto the SIGNED coverflowSlotOffsetDp
                    // output for every tile (left tiles included, whose raw offset is already negative), so a
                    // negative correction (row shifts left) SHRINKS the left tile's unsigned distance-from-
                    // centre while it GROWS the right tile's -- and vice-versa. Mirror that sign here, not the
                    // naive (extent +/- correction) pairing, or this property test fails on its own algebra
                    // rather than on production code (caught by this exact assertion during development).
                    val correctedLeft = leftExtent - correction
                    val correctedRight = rightExtent + correction
                    val correctedMax = maxOf(correctedLeft, correctedRight)
                    assertTrue(
                        "$widthDp dp / $itemCount items / focus=$focusedIndex: corrected extent " +
                            "($correctedMax) must not exceed the uncorrected one ($uncorrectedMax)",
                        correctedMax <= uncorrectedMax + 0.01f,
                    )
                    assertTrue(
                        "$widthDp dp / $itemCount items / focus=$focusedIndex: corrected extent " +
                            "($correctedMax) must stay inside the half-viewport (${widthDp / 2f}dp)",
                        correctedMax <= widthDp / 2f + 0.01f,
                    )
                }
            }
        }
    }

    private fun assertMovingBodiesKeepClearance(
        restWidth: Int,
        focusWidth: Int,
        gap: Int,
        bookHero: Boolean,
    ) {
        for (step in 0..100) {
            val progress = step / 100f
            val outgoingPosition = -progress
            val incomingPosition = 1f - progress
            val separation = coverflowSlotOffsetDp(
                incomingPosition,
                restWidth,
                focusWidth,
                gap,
            ) - coverflowSlotOffsetDp(
                outgoingPosition,
                restWidth,
                focusWidth,
                gap,
            )
            val outgoingPose = coverflowBasePose(1f - progress, bookHero)
            val incomingPose = coverflowBasePose(progress, bookHero)
            val paintedHalfWidths = focusWidth * outgoingPose.scale * CoverflowMotionFocusScale / 2f +
                focusWidth * incomingPose.scale * CoverflowMotionFocusScale / 2f
            assertTrue(
                "painted bodies overlap at $progress: separation=$separation widths=$paintedHalfWidths",
                separation >= paintedHalfWidths,
            )
        }
    }
}
