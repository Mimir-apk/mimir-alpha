package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the launch card's achievement count sits BESIDE the game's name, and wraps onto the line below when the name leaves it no room. The decision is one pure rule,
 * so the card's geometry and this table cannot disagree: beside only for a one-line name whose whole first line plus the chip fits the width; otherwise below. The name is never
 * narrowed for the chip, so the chip is never why a name wraps or ends in an ellipsis.
 */
class HomeLaunchCardTitleChipTest {

    private val line = 20
    private val gap = 4

    private fun beside(titleHeight: Int = line, firstLine: Int = 150, chip: Int = 60, max: Int = 300) =
        homeLaunchTitleChipBeside(titleHeightPx = titleHeight, lineHeightPx = line, firstLineWidthPx = firstLine, gapPx = gap, chipWidthPx = chip, maxWidthPx = max)

    @Test
    fun `a one-line name with room beside it takes the chip beside it`() {
        assertTrue(beside())
    }

    @Test
    fun `the chip is beside right up to the last pixel that fits and below one pixel past it`() {
        // 150 + 4 + 60 = 214
        assertTrue(beside(max = 214))
        assertFalse(beside(max = 213))
        assertTrue(beside(firstLine = 236, max = 300))
        assertFalse(beside(firstLine = 237, max = 300))
    }

    @Test
    fun `a name that has wrapped to two lines puts the chip below, whatever room is left`() {
        assertFalse(beside(titleHeight = line * 2, firstLine = 40, chip = 40, max = 600))
        // The line height is rounded in pixels, so a name a hair taller than one line is still one line.
        assertTrue(beside(titleHeight = line + 1))
        assertTrue(beside(titleHeight = line * 3 / 2))
        assertFalse(beside(titleHeight = line * 3 / 2 + 1))
    }

    @Test
    fun `no chip is never beside`() {
        assertFalse(beside(chip = 0))
    }

    @Test
    fun `the title line height is 20 sp`() {
        assertEquals(20f, HomeLaunchCardTitleLineHeight.value, 0f)
    }
}
