package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compact review 2026-09-27 (HOME-1): on the RG's 564.7 dp square the Home title had 149.7 dp, because two 190 dp
 * wide-screen reserves (the status pill's and the status chip's) were subtracted from it.
 */
class HomeShelfCompactTitleTest {

    private val rgHeights = listOf(540.7f, 564.7f)

    @Test
    fun `the RG title band runs to the status HUD, clear of it`() {
        rgHeights.forEach { height ->
            val band = solveHomeShelfGeometry(564.7f, height, compact = true).titleBand
            assertEquals(17.506f, band.left, 0.01f)
            assertEquals(387.195f, band.right, 0.01f)
            // The HUD measured 401.6 dp from the left on the RG (home-01).
            assertTrue("clear of the HUD by at least 12 dp at $height", 401.6f - band.right >= 12f)
        }
    }

    @Test
    fun `compact changes only the title band's width and where the seats sit`() {
        rgHeights.forEach { height ->
            val standard = solveHomeShelfGeometry(564.7f, height)
            val compact = solveHomeShelfGeometry(564.7f, height, compact = true)
            assertEquals(standard.titleBand.left, compact.titleBand.left, 0f)
            assertEquals(standard.titleBand.top, compact.titleBand.top, 0f)
            // The backdrop's decode envelope is solved with the standard geometry and reads exactly these two.
            assertEquals(standard.titleBand.bottom, compact.titleBand.bottom, 0f)
            assertEquals(standard.hintToastBand, compact.hintToastBand)
            // The seat band is centred on compact (HOME-2, see HomeShelfCompactBandTest); its size is not.
            assertEquals(standard.gameRailBand.height, compact.gameRailBand.height, 0.001f)
            assertEquals(standard.quickRailBand, compact.quickRailBand)
            assertEquals(standard.navigationHintBand, compact.navigationHintBand)
            assertEquals(standard.visibleSlotCount, compact.visibleSlotCount)
            assertEquals(standard.seatEnvelope, compact.seatEnvelope, 0f)
        }
    }

    @Test
    fun `standard screens are unchanged`() {
        listOf(833f to 444f, 640f to 480f, 700f to 350f, 832.5f to 468.3f).forEach { (w, h) ->
            assertEquals(solveHomeShelfGeometry(w, h), solveHomeShelfGeometry(w, h, compact = false))
        }
    }
}
