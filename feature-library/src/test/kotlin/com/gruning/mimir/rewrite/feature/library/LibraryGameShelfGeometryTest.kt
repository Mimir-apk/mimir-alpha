package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library shelf is the Home shelf , so its columns are Home's own and only its verticals are the body's.
 * [solveLibraryGameShelfGeometry] is read against [solveHomeShelfGeometry] on the same screen, never against a literal that could be
 * wrong twice.
 */
class LibraryGameShelfGeometryTest {

    private data class Screen(val name: String, val width: Float, val height: Float, val compact: Boolean)

    private val screens = listOf(
        Screen("Thor Main", 832.5f, 468.3f, compact = false),
        Screen("Odin", 897f, 505f, compact = false),
        Screen("Odin certified", 833f, 444f, compact = false),
        Screen("RP5", 700f, 350f, compact = false),
        Screen("RG square", 564.7f, 540.7f, compact = true),
        Screen("floor", 320f, 300f, compact = true),
    )

    private val lane = 60f
    private val titleLeft = LibraryGamelistBodyPaddingDp.value

    /** The Library body under a 96 dp header, the shape the gamelist Column hands the shelf. */
    private fun library(screen: Screen, bodyHeight: Float = screen.height - 96f) = solveLibraryGameShelfGeometry(
        widthDp = screen.width,
        heightDp = bodyHeight,
        screenHeightDp = screen.height,
        bottomLaneDp = lane,
        titleLeftDp = titleLeft,
        compact = screen.compact,
    )

    private fun home(screen: Screen) = solveHomeShelfGeometry(screen.width, screen.height, screen.compact)

    @Test
    fun `every column quantity is Home's, on every certified screen`() {
        screens.forEach { screen ->
            val library = library(screen)
            val home = home(screen)
            val at = screen.name
            assertEquals("$at seat envelope", home.seatEnvelope, library.seatEnvelope, 0f)
            assertEquals("$at slot gap", home.slotGap, library.slotGap, 0f)
            assertEquals("$at stride", home.slotStride, library.slotStride, 0f)
            assertEquals("$at gutter", home.gutter, library.gutter, 0f)
            assertEquals("$at content left", home.contentLeft, library.contentLeft, 0f)
            assertEquals("$at seat count", home.visibleSlotCount, library.visibleSlotCount)
            assertEquals("$at fully visible seats", home.fullyVisibleSlotCount, library.fullyVisibleSlotCount)
            assertEquals("$at bloom outset", home.bloomOutset, library.bloomOutset, 0f)
        }
    }

    @Test
    fun `a tall enough body draws Home's own hero and resting tile`() {
        screens.forEach { screen ->
            val library = library(screen, bodyHeight = screen.height)
            val home = home(screen)
            assertEquals("${screen.name} hero", home.heroSide, library.heroSide, 0.001f)
            assertEquals("${screen.name} resting tile", home.heroSide / HomeShelfSelectedScale, library.restingSide, 0.001f)
        }
    }

    @Test
    fun `the title block is Home's, starting where the header above it starts`() {
        screens.forEach { screen ->
            val library = library(screen)
            val home = home(screen)
            assertEquals("${screen.name} title top", 0f, library.titleBand.top, 0f)
            assertEquals("${screen.name} title height", home.titleBand.height, library.titleBand.height, 0f)
            assertEquals("${screen.name} title shares the header's left edge", titleLeft, library.titleBand.left, 0f)
            assertEquals("${screen.name} title runs to the matching right edge", screen.width - titleLeft, library.titleBand.right, 0.001f)
        }
    }

    @Test
    fun `the viewport is the body and the shelf is laid out as wide as the screen`() {
        screens.forEach { screen ->
            val library = library(screen)
            assertEquals(0f, library.viewport.left, 0f)
            assertEquals(0f, library.viewport.top, 0f)
            assertEquals(screen.width, library.viewport.right, 0f)
            assertEquals(screen.height - 96f, library.viewport.bottom, 0.001f)
            assertEquals(screen.width, library.gameRailBand.width, 0.001f)
        }
    }

    @Test
    fun `a standard screen seats the strip on the ledge above the controls lane`() {
        screens.filterNot(Screen::compact).forEach { screen ->
            val library = library(screen, bodyHeight = screen.height - 40f)
            val bodyHeight = screen.height - 40f
            assertEquals("${screen.name} strip stands on the ledge", bodyHeight - maxOf(lane, library.bloomOutset), library.gameRailBand.bottom, 0.001f)
            assertEquals("${screen.name} the band is one seat tall", library.seatEnvelope, library.gameRailBand.height, 0.001f)
        }
    }

    @Test
    fun `a compact screen with room to spare centres the strip between the title and the ledge`() {
        val screen = screens.first { it.name == "RG square" }
        val library = library(screen, bodyHeight = screen.height)
        val titleBottom = library.titleBand.bottom
        val ledge = screen.height - maxOf(lane, library.bloomOutset)
        val above = library.gameRailBand.top - titleBottom
        val below = ledge - library.gameRailBand.bottom
        assertTrue("room to spare on the square: $above above, $below below", above > 0f && below > 0f)
        assertEquals("the spare room is split evenly", above, below, 0.01f)
    }

    @Test
    fun `a body too short for the title and the strip shrinks the art, never the columns`() {
        val screen = screens.first { it.name == "Odin" }
        val tall = library(screen, bodyHeight = screen.height)
        val short = library(screen, bodyHeight = tall.titleBand.bottom + lane + 80f)
        assertTrue("the hero gave way: ${short.heroSide} < ${tall.heroSide}", short.heroSide < tall.heroSide)
        assertEquals("the hero is the room there is", 80f, short.heroSide, 0.001f)
        assertEquals("the resting tile keeps the hero's proportion", short.heroSide / HomeShelfSelectedScale, short.restingSide, 0.001f)
        assertEquals("the seat envelope is Home's", tall.seatEnvelope, short.seatEnvelope, 0f)
        assertEquals("the stride is Home's", tall.slotStride, short.slotStride, 0f)
        assertEquals("the seat count is Home's", tall.visibleSlotCount, short.visibleSlotCount)
        assertEquals("seats still stand on the ledge", short.viewport.bottom - lane, short.gameRailBand.bottom, 0.001f)
    }

    @Test
    fun `a body with no room at all still draws a tile worth tapping`() {
        val screen = screens.first { it.name == "RP5" }
        val library = library(screen, bodyHeight = 10f)
        assertEquals(64f, library.heroSide, 0f)
        assertTrue(library.restingSide > 0f)
        assertTrue("the band never inverts", library.gameRailBand.top <= library.gameRailBand.bottom)
    }

    @Test
    fun `the dock's bands are empty because the Library has its own bottom chrome`() {
        val library = library(screens.first())
        assertEquals(0f, library.quickRailBand.height, 0f)
        assertEquals(0f, library.navigationHintBand.height, 0f)
        assertEquals(0f, library.hintToastBand.height, 0f)
    }
}
