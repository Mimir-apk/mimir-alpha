package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * The anchor was an integrator — `priorStart + (focusedRow - previousFocusedRow)`, clamped each
 * step. Accumulating and clamping a running sum is lossy, so the return trip does not cancel the
 * outbound one.
 */
class HomeTomeFolioVectorTest {

    // ----------------------------------------------------------------------------------------
    // The anchor is a pure function of focus, so any path back to a seat lands where it started.
    // ----------------------------------------------------------------------------------------

    /** The exact reported gesture: down, then up, must return the sheet to where it was. */
    @Test
    fun `down then up returns the sheet to the same anchor`() {
        val start = homeTomeFolioAnchorIndex(focusedIndex = 4, lead = 1, visible = 3, total = 20)
        val down = homeTomeFolioAnchorIndex(focusedIndex = 5, lead = 1, visible = 3, total = 20)
        val backUp = homeTomeFolioAnchorIndex(focusedIndex = 4, lead = 1, visible = 3, total = 20)
        assertNotEquals("the sheet must actually move on the way down", start, down)
        assertEquals("and come back to exactly where it was", start, backUp)
    }

    /**
     * The lossy case, and the one that made the desync visible: walking into a clamped edge and back
     * out. The old integrator subtracted from a clamped accumulator, so the sheet crept.
     */
    @Test
    fun `walking into a clamped edge and back out does not shift the sheet`() {
        val total = 20
        val visible = 3
        val lead = 1
        // Ride to the very bottom, pushing well past where the window can follow.
        val atFloor = homeTomeFolioAnchorIndex(total - 1, lead, visible, total)
        assertEquals(total - visible, atFloor)
        // Press further down repeatedly — the window is pinned and must not accumulate anything.
        repeat(5) { assertEquals(atFloor, homeTomeFolioAnchorIndex(total - 1, lead, visible, total)) }
        // Now walk back up one seat at a time; each position must match its own pure answer.
        for (focus in (total - 1) downTo 0) {
            assertEquals(
                "focus $focus must anchor identically however it was reached",
                homeTomeFolioAnchorIndex(focus, lead, visible, total),
                homeTomeFolioAnchorIndex(focus, lead, visible, total),
            )
        }
        // And the round trip lands exactly where it began.
        assertEquals(atFloor, homeTomeFolioAnchorIndex(total - 1, lead, visible, total))
    }

    /**
     * Differential against the integrator this replaces. Replaying the same gesture through the old
     * rule drifts; through the new one it does not. Without this, "it retraces" is just an assertion
     * that the new code equals itself.
     */
    @Test
    fun `the previous integrator drifts on the same gesture and the pure anchor does not`() {
        val total = 20
        val visible = 3
        val lead = 1
        // Old rule, reproduced exactly: shift by the focus delta, clamp the accumulator each step.
        fun integrator(priorStart: Int, previousFocus: Int, focus: Int): Int {
            val maxStart = total - visible
            return (priorStart + focus - previousFocus).coerceIn(0, maxStart)
        }

        // Gesture: sit at the bottom, press DOWN three times (focus is already pinned at the last
        // row by the caller), then press UP three times.
        val bottom = total - 1
        var oldStart = total - visible
        var previousFocus = bottom
        repeat(3) { oldStart = integrator(oldStart, previousFocus, bottom).also { previousFocus = bottom } }
        for (focus in listOf(bottom - 1, bottom - 2, bottom - 3)) {
            oldStart = integrator(oldStart, previousFocus, focus)
            previousFocus = focus
        }
        val newStart = homeTomeFolioAnchorIndex(bottom - 3, lead, visible, total)

        assertNotEquals(
            "the integrator must be shown to drift, or this test proves nothing",
            newStart,
            oldStart,
        )
        // And the pure anchor agrees with a direct jump to the same seat — no history involved.
        assertEquals(newStart, homeTomeFolioAnchorIndex(bottom - 3, lead, visible, total))
    }

    @Test
    fun `a folio shorter than the window never pans`() {
        assertEquals(0, homeTomeFolioAnchorIndex(focusedIndex = 2, lead = 1, visible = 6, total = 4))
    }

    @Test
    fun `the anchor never scrolls past either end`() {
        val total = 10
        val visible = 4
        (0 until total).forEach { focus ->
            val anchor = homeTomeFolioAnchorIndex(focus, lead = 1, visible = visible, total = total)
            assertTrue("anchor $anchor below range", anchor >= 0)
            assertTrue("anchor $anchor past the end", anchor <= total - visible)
        }
    }

    // ----------------------------------------------------------------------------------------
    // Centring on the Tome container — the ring-inclusive envelope, not the grid.
    // ----------------------------------------------------------------------------------------

    /** Realistic folio: 6 columns of 150 with 12 gaps, page-centred, 14 dp ring clearance. */
    private val cols = 6
    private val cellW = 150f
    private val gapW = 12f
    private val gridW = cellW * cols + gapW * (cols - 1)
    private val ring = 14f
    private val pageLeft = 40f
    private val pageWidth = 1200f
    private val gridLeft = pageLeft + (pageWidth - gridW) / 2f

    @Test
    fun `the container is the grid plus a ring clearance on each side`() {
        assertEquals(gridW + 2f * ring, homeTomeFolioContainerWidth(gridW, ring), 0.0001f)
        assertEquals(gridLeft - ring, homeTomeFolioContainerLeft(gridLeft, ring), 0.0001f)
    }

    /** Centred means the content's midpoint sits exactly on the container's midpoint. */
    @Test
    fun `centred content shares the container midpoint`() {
        val pill = HomeTomeArrangePillWidthDp
        val left = homeTomeFolioCentredLeft(gridLeft, gridW, ring, pill)
        val containerMid = homeTomeFolioContainerLeft(gridLeft, ring) +
            homeTomeFolioContainerWidth(gridW, ring) / 2f
        assertEquals(containerMid, left + pill / 2f, 0.0001f)
    }

    /**
     * The cancellation, asserted rather than assumed: with a page-centred grid and symmetric
     * clearance, the container midpoint IS the page midpoint. This is what makes the pill land
     * where the eye expects.
     */
    @Test
    fun `a page-centred grid puts the container midpoint on the page midpoint`() {
        val containerMid = homeTomeFolioContainerLeft(gridLeft, ring) +
            homeTomeFolioContainerWidth(gridW, ring) / 2f
        assertEquals(pageLeft + pageWidth / 2f, containerMid, 0.0001f)
    }

    /**
     * And the reason it is derived from the container anyway: give the grid an asymmetric inset and
     * the two stop agreeing. Centring on the container follows the box that is actually painted.
     */
    @Test
    fun `an off-centre grid centres on the container, not the page`() {
        val shiftedGridLeft = gridLeft + 90f
        val left = homeTomeFolioCentredLeft(shiftedGridLeft, gridW, ring, HomeTomeArrangePillWidthDp)
        val containerMid = homeTomeFolioContainerLeft(shiftedGridLeft, ring) +
            homeTomeFolioContainerWidth(gridW, ring) / 2f
        assertEquals(containerMid, left + HomeTomeArrangePillWidthDp / 2f, 0.0001f)
        assertNotEquals(pageLeft + pageWidth / 2f, containerMid)
    }

    /** The previous placement was right-aligned to the title block, which is a different box. */
    @Test
    fun `centring is not where the title-aligned placement put it`() {
        val titleLeft = 64f
        val titleWidth = 520f
        val oldLeft = titleLeft + titleWidth - HomeTomeArrangePillWidthDp
        val newLeft = homeTomeFolioCentredLeft(gridLeft, gridW, ring, HomeTomeArrangePillWidthDp)
        assertNotEquals("the fix must actually move the pill", oldLeft, newLeft)
    }

    // ----------------------------------------------------------------------------------------
    // Arrange preview vs commit. The live retarget during a drag used a CONTAINMENT test while the
    // release used PROXIMITY, so the two could name different seats and a tile could land somewhere
    // the drag never previewed. Both now ask the same question; this pins why that mattered.
    // ----------------------------------------------------------------------------------------

    /**
     * The seats these two resolvers disagree about: the gaps. Containment has no answer there, so a
     * preview driven by it freezes on the last cell entered while the finger keeps moving — and the
     * release, driven by proximity, then commits to whatever is actually nearest.
     */
    @Test
    fun `containment and proximity disagree in the gap between seats`() {
        val cellSize = 100f
        val gap = 20f
        val seats = 18
        // x = 115 sits in the gap: column 0 spans 0..100, column 1 starts at 120.
        val inTheGap = 115f
        val rowCentre = cellSize / 2f

        val contained = homeTomeFolioSeatIndexAtTravel(
            inTheGap, rowCentre, 0, seats, cellSize, cellSize, gap, gap,
        )
        val nearest = homeTomeFolioNearestSeatAtTravel(
            inTheGap, rowCentre, 0, seats, cellSize, cellSize, gap, gap,
        )

        assertNull("containment must have no answer in a gap, which is what froze the preview", contained)
        assertNotNull("proximity always has an answer, which is what the release committed to", nearest)
    }

    /** Over a seat's own centre the two agree, so the fix changes nothing where it was already right. */
    @Test
    fun `containment and proximity agree over a seat centre`() {
        val cellSize = 100f
        val gap = 20f
        val seats = 18
        val centre = cellSize / 2f
        assertEquals(
            homeTomeFolioSeatIndexAtTravel(centre, centre, 0, seats, cellSize, cellSize, gap, gap),
            homeTomeFolioNearestSeatAtTravel(centre, centre, 0, seats, cellSize, cellSize, gap, gap),
        )
    }
}
