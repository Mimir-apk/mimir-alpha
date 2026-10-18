package com.gruning.mimir.rewrite.feature.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2-01 keeps the rail at SEVEN seats, and the rail is the one thing on this 538 x 468 dp panel whose
 * content grows sideways. The row centres its children and neither wraps nor scrolls, so an overflow
 * does not announce itself — it pushes the outer seats off both panel edges, which on a touch-only
 * surface means two controls that cannot be pressed.
 *
 * **What this test is, said plainly: ARITHMETIC, not a measurement.** Geometry changes require measured pixel bounds: a dimension is not settled until the pixel bounds
 * have been captured, and this settles nothing on glass — the seat's real bounds are OWED a capture on
 * the device. What it does buy is that the budget is checked against the tokens the layout really uses
 * rather than against a second copy of them, and that an eighth seat fails here instead of there.
 */
class CompanionQuickRailBudgetTest {

    @Test
    fun `the seven-seat rail fits the panel it is drawn on`() {
        // General + instruments + five pinned slots. The former collection seat is Q5.
        val seats = 7
        val width = companionQuickRailWidthDp(seats)
        val budget = companionQuickRailBudgetDp()
        assertTrue(
            "a $seats-seat rail measures $width dp against a $budget dp budget",
            width <= budget,
        )
    }

    @Test
    fun `the arithmetic is the arithmetic, so a changed token moves the answer`() {
        assertEquals(
            CompanionQuickSeatDp,
            companionQuickRailWidthDp(1),
            0.001f,
        )
        assertEquals(
            2f * CompanionQuickSeatDp + CompanionQuickSeatGapDp,
            companionQuickRailWidthDp(2),
            0.001f,
        )
        assertEquals("an empty rail measures nothing", 0f, companionQuickRailWidthDp(0), 0.001f)
    }

    @Test
    fun `the eighth seat is the one that does not fit, and this is where it must fail`() {
        // Not a prediction about a feature nobody asked for -- it is the statement that the budget is
        // real and nearly spent, which is the fact a future design change on this rail needs to have in hand.
        assertTrue(
            "adding an eighth seat overflows the panel and must be caught here",
            companionQuickRailWidthDp(8) > companionQuickRailBudgetDp(),
        )
    }

    @Test
    fun `every seat clears the touch floor on a surface with no controller`() {
        // §4's 48 dp minimum. All five pins borrow the existing seat geometry.
        assertTrue(
            "a rail seat is ${CompanionQuickSeatDp} dp",
            CompanionQuickSeatDp >= 48f,
        )
    }

    @Test
    fun `the reclaimed collection seat renders Q5 before the existing four pins`() {
        assertEquals(listOf(4, 0, 1, 2, 3), companionQuickLaunchSlotOrder(5))
        assertEquals(
            "short legacy lists keep their existing stored order",
            listOf(0, 1, 2, 3),
            companionQuickLaunchSlotOrder(4),
        )
    }
}
