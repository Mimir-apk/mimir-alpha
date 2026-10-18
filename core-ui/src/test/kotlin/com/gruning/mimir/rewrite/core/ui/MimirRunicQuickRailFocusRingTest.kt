package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dock has one gliding selection-focus ring. The seats paint no disc; one [drawMimirFocusTileRing] travels between them.
 */
class MimirRunicQuickRailFocusRingTest {

    @Test
    fun arrivalFromTheShelfLandsOnTheSeatAndOnlySeatToSeatGlides() {
        // BM02: arrival snaps. A ring that is not on the dock at all lands on its seat.
        assertTrue(mimirQuickRailRingSnaps(presence = 0f, reducedMotion = false))
        // Seat to seat, and a ring still fading out from a departure, glide from where they are.
        assertFalse(mimirQuickRailRingSnaps(presence = 1f, reducedMotion = false))
        assertFalse(mimirQuickRailRingSnaps(presence = 0.4f, reducedMotion = false))
        // Reduced motion never glides.
        assertTrue(mimirQuickRailRingSnaps(presence = 1f, reducedMotion = true))
        assertTrue(mimirQuickRailRingSnaps(presence = 0f, reducedMotion = true))
    }

    @Test
    fun theRingScalesWithTheCoinSoTheFilesEndCapKeepsItsSmallerSeat() {
        val full = MimirRunicQuickRailDefaults.MaxActionDiameter
        val regular = mimirQuickRailFocusRingDiameter(mimirQuickRailItemDiameter(MimirQuickRailAction.HOME, full))
        val files = mimirQuickRailFocusRingDiameter(mimirQuickRailItemDiameter(MimirQuickRailAction.FILES, full))

        assertEquals(45f, regular.value, 0.01f)
        assertEquals(36f, files.value, 0.01f)
        assertTrue("D-3: the FILES end-cap stays the smaller seat", files < regular)
    }

    @Test
    fun theStrongStrokesStayInsideTheSeatEnvelopeAboveTheLabelBand() {
        val envelope = MimirRunicQuickRailDefaults.ActionBoxDiameter.value
        val ring = mimirQuickRailFocusRingDiameter(MimirRunicQuickRailDefaults.MaxActionDiameter).value
        // drawMimirFocusTileRing: the 95 % stroke is 3 dp wide at a 2 dp outset, the 18 % bloom 5 dp at 6 dp.
        val strongestReach = envelope / 2f + ring / 2f + 2f + 3f / 2f
        val bloomReach = envelope / 2f + ring / 2f + 6f + 5f / 2f

        assertTrue("the 95 % stroke reached $strongestReach dp of a $envelope dp envelope", strongestReach <= envelope)
        assertTrue("the 18 % bloom reached $bloomReach dp of a $envelope dp envelope", bloomReach <= envelope)
    }

    @Test
    fun theSmallestRingStillClearsTheMark() {
        val smallest = mimirQuickRailFocusRingDiameter(MimirRunicQuickRailDefaults.MinActionDiameter)
        // The 95 % stroke's inner edge sits at the ring's radius + 2 dp outset - half its 3 dp width.
        val strokeInnerRadius = smallest.value / 2f + 2f - 1.5f
        val markRadius = MimirRunicQuickRailDefaults.DrawnMarkSize.value / 2f

        assertTrue(
            "the ring's inner edge sat ${strokeInnerRadius - markRadius} dp off the mark",
            strokeInnerRadius - markRadius >= 4f,
        )
        assertEquals(48.dp, MimirRunicQuickRailDefaults.MinActionDiameter)
    }

    @Test
    fun theFocusWashIsTheGlassRowsFocusFillNotASolidDisc() {
        assertEquals(0.16f, MimirRunicQuickRailDefaults.FocusWashAlpha, 0f)
    }
}
