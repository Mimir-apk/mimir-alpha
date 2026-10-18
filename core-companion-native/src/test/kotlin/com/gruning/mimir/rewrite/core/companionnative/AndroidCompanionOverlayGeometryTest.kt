package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidCompanionOverlayGeometryTest {
    @Test
    fun freshButtonUsesShippingSizeAtTopRightSafeInset() {
        val thorDensity = 369f / 160f
        val size = dpToPx(MButtonSizeDp, thorDensity)
        val inset = dpToPx(MButtonSafeInsetDp, thorDensity)

        val position = defaultMButtonPosition(
            bounds = 1240 to 1080,
            width = size,
            height = size,
            safeInset = inset,
        )

        assertEquals(56, MButtonSizeDp)
        assertEquals(14, MButtonSafeInsetDp)
        assertEquals(129, size)
        assertEquals(32, inset)
        assertEquals(1079 to 32, position)
        assertEquals(inset, 1240 - position.first - size)
    }

    @Test
    fun persistedDragRatiosStillOverrideTheFreshDefault() {
        val restored = resolveCompanionOverlayPosition(
            bounds = 1000 to 600,
            width = 56,
            height = 56,
            persistedXRatio = 0.24f,
            persistedYRatio = 0.65f,
            defaultPosition = 930 to 14,
        )

        assertEquals(240 to 390, restored)
    }

    @Test
    fun persistedPositionReconcilesIntoChangedDisplayBounds() {
        val restored = resolveCompanionOverlayPosition(
            bounds = 400 to 200,
            width = 129,
            height = 129,
            persistedXRatio = 0.9f,
            persistedYRatio = 0.9f,
            defaultPosition = 239 to 32,
        )

        assertEquals(271 to 71, restored)
    }

    @Test
    fun defaultAndClampRemainValidWhenDisplayIsSmallerThanButtonAndInset() {
        val position = defaultMButtonPosition(
            bounds = 40 to 30,
            width = 56,
            height = 56,
            safeInset = 14,
        )

        assertEquals(0 to 0, position)
        assertEquals(
            0 to 0,
            clampCompanionOverlayPosition(
                x = Int.MAX_VALUE,
                y = Int.MIN_VALUE,
                bounds = 40 to 30,
                width = 56,
                height = 56,
            ),
        )
    }

    // Sweep 28 F3: the Bottom's open menu card opened flush with the right edge of the glass (it takes the coin's spot, and the coin rests on that edge).

    private val thorDensity = 369f / 160f
    private val bottomBounds = 1240 to 1080

    @Test
    fun theMenuCardKeepsTheOverlayInsetFromEveryEdgeOfTheBottom() {
        val inset = dpToPx(MButtonSafeInsetDp, thorDensity)
        val width = dpToPx(CompanionMenuWidthDp, thorDensity)
        val height = dpToPx(companionOverlayMenuHeightDp(5), thorDensity)

        // The coin's default spot is the right edge: the card used to open there with no margin at all.
        val atTheCoin = defaultMButtonPosition(bottomBounds, dpToPx(MButtonSizeDp, thorDensity), dpToPx(MButtonSizeDp, thorDensity), inset)
        val flush = clampCompanionOverlayPosition(atTheCoin.first, atTheCoin.second, bottomBounds, width, height)
        assertEquals("the old clamp left the card flush with the right edge", 0, bottomBounds.first - flush.first - width)

        val card = clampCompanionMenuCardPosition(atTheCoin.first, atTheCoin.second, bottomBounds, width, height, inset)
        assertEquals("now it keeps the coin's own margin", inset, bottomBounds.first - card.first - width)
        assertEquals("the vertical spot the coin gave it is kept", flush.second, card.second)

        assertEquals("left", inset, clampCompanionMenuCardPosition(-500, 300, bottomBounds, width, height, inset).first)
        assertEquals("top", inset, clampCompanionMenuCardPosition(300, -500, bottomBounds, width, height, inset).second)
        val corner = clampCompanionMenuCardPosition(Int.MAX_VALUE, Int.MAX_VALUE, bottomBounds, width, height, inset)
        assertEquals("right", inset, bottomBounds.first - corner.first - width)
        assertEquals("bottom", inset, bottomBounds.second - corner.second - height)
    }

    @Test
    fun aMenuCardAlreadyInsideTheMarginIsLeftWhereItIs() {
        val inset = dpToPx(MButtonSafeInsetDp, thorDensity)
        assertEquals(400 to 250, clampCompanionMenuCardPosition(400, 250, bottomBounds, 563, 340, inset))
    }

    @Test
    fun aCardTooBigForTheMarginOnAnAxisIsCentredOnIt() {
        assertEquals((100 - 90) / 2 to 20, clampCompanionMenuCardPosition(0, 0, 100 to 200, 90, 160, 20))
        assertEquals(0 to 0, clampCompanionMenuCardPosition(5, 5, 50 to 50, 80, 80, 14))
    }

    @Test
    fun theCoinsOwnClampIsUntouchedSoItStaysWhereItIsDropped() {
        val size = dpToPx(MButtonSizeDp, thorDensity)
        // A coin dropped against the right edge is allowed to touch it, exactly as before.
        assertEquals(bottomBounds.first - size to 400, clampCompanionOverlayPosition(5_000, 400, bottomBounds, size, size))
        assertEquals(0 to 0, clampCompanionOverlayPosition(-5, -5, bottomBounds, size, size))
    }
}
