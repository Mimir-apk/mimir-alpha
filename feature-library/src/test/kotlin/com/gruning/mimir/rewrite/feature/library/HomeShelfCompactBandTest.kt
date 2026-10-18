package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  On the RG the seats left 158.3 dp of empty
 * field above them (182.3 with the bar hidden); compact centres the band between the title and the hint lane.
 */
class HomeShelfCompactBandTest {

    @Test
    fun `the RG splits the spare height above and below the seats`() {
        mapOf(540.7f to 156.65f, 564.7f to 168.65f).forEach { (height, expectedTop) ->
            val g = solveHomeShelfGeometry(564.7f, height, compact = true)
            assertEquals(3, g.visibleSlotCount)
            assertEquals(158.9f, g.seatEnvelope, 0.05f)
            assertEquals("top at $height", expectedTop, g.gameRailBand.top, 0.05f)
            val above = g.gameRailBand.top - g.titleBand.bottom
            val below = g.hintToastBand.top - g.bloomOutset - g.gameRailBand.bottom
            assertEquals("even split at $height", above, below, 0.01f)
            assertTrue("clears the title with the ring's reach at $height", above >= 14f)
        }
    }

    @Test
    fun `seat size, rail, hint lane and title band do not move`() {
        listOf(540.7f, 564.7f).forEach { height ->
            val standard = solveHomeShelfGeometry(564.7f, height)
            val compact = solveHomeShelfGeometry(564.7f, height, compact = true)
            assertEquals(standard.seatEnvelope, compact.seatEnvelope, 0f)
            assertEquals(standard.heroSide, compact.heroSide, 0f)
            assertEquals(standard.quickRailBand, compact.quickRailBand)
            assertEquals(standard.hintToastBand, compact.hintToastBand)
            assertEquals(standard.navigationHintBand, compact.navigationHintBand)
            assertEquals(standard.titleBand.bottom, compact.titleBand.bottom, 0f)
        }
    }

    @Test
    fun `on every compact size the band stays between the title and the hint lane`() {
        listOf(320f to 300f, 360f to 360f, 411f to 411f, 480f to 480f, 564.7f to 540.7f, 564.7f to 564.7f, 720f to 720f)
            .forEach { (w, h) ->
                val g = solveHomeShelfGeometry(w, h, compact = true)
                val standard = solveHomeShelfGeometry(w, h)
                assertTrue("below the title at ${w}x$h", g.gameRailBand.top >= g.titleBand.bottom - 0.001f)
                assertTrue(
                    "above the hint lane at ${w}x$h",
                    g.gameRailBand.bottom <= g.hintToastBand.top - g.bloomOutset + 0.001f,
                )
                // Centring only ever lifts the band; it never pushes it down into the lane.
                assertTrue("never lower than standard at ${w}x$h", g.gameRailBand.top <= standard.gameRailBand.top + 0.001f)
            }
    }
}
