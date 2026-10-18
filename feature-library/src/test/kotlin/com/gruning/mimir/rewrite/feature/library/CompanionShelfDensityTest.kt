package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice L4's pure half: the density ladder, the grid solver at each step, the stepper's bounds, the storage word, and the paging arithmetic. The render half
 * (a 538 x 468 dp Bottom, font scales 1.0 and 1.3) is [CompanionPlatformShelfRenderTest].
 *
 * The geometry below is the Thor Bottom's: a 506 dp page (538 less the shelf's 16 dp a side), a 12 dp gap, and the page height the render test measures.
 */
class CompanionShelfDensityTest {

    private val pageWidth = 506f
    private val gap = 12f
    private val pageHeight = 362f

    private fun solve(step: CompanionShelfDensity, count: Int = 43) =
        solveCompanionShelfGrid(pageWidth, pageHeight, step.columns, step.rows, gap, count)

    @Test
    fun `the ladder is Large 2x1, Standard 4x2 as today, and Dense 6x3 with no label`() {
        assertEquals(listOf("large", "standard", "dense"), CompanionShelfDensity.values().map { it.storageKey })
        assertEquals(2 to 1, CompanionShelfDensity.LARGE.columns to CompanionShelfDensity.LARGE.rows)
        assertEquals(4 to 2, CompanionShelfDensity.STANDARD.columns to CompanionShelfDensity.STANDARD.rows)
        assertEquals(6 to 3, CompanionShelfDensity.DENSE.columns to CompanionShelfDensity.DENSE.rows)
        assertEquals(listOf(2, 8, 18), CompanionShelfDensity.values().map { it.perPage })
        assertEquals(listOf("2×1", "4×2", "6×3"), CompanionShelfDensity.values().map { it.readout })
        assertEquals(listOf(true, true, false), CompanionShelfDensity.values().map { it.showsLabel })
        assertEquals("Standard is the default: today's four columns", CompanionShelfDensity.STANDARD, CompanionShelfDensity.Default)
    }

    @Test
    fun `the stepper stops at both ends and walks the whole ladder`() {
        assertNull("nothing is larger than Large", CompanionShelfDensity.LARGE.larger)
        assertNull("nothing is denser than Dense", CompanionShelfDensity.DENSE.denser)
        assertEquals(CompanionShelfDensity.STANDARD, CompanionShelfDensity.LARGE.denser)
        assertEquals(CompanionShelfDensity.LARGE, CompanionShelfDensity.STANDARD.larger)
        assertEquals(CompanionShelfDensity.DENSE, CompanionShelfDensity.STANDARD.denser)
        assertEquals(CompanionShelfDensity.STANDARD, CompanionShelfDensity.DENSE.larger)
        var step: CompanionShelfDensity? = CompanionShelfDensity.DENSE
        var presses = 0
        while (step?.larger != null) { step = step.larger; presses++ }
        assertEquals(CompanionShelfDensity.LARGE, step)
        assertEquals("two presses of - from Dense reach Large", 2, presses)
    }

    @Test
    fun `the storage word round-trips and anything else reads as Standard`() {
        CompanionShelfDensity.values().forEach { step ->
            assertEquals(step, CompanionShelfDensity.fromStorageKey(step.storageKey))
        }
        assertEquals(CompanionShelfDensity.STANDARD, CompanionShelfDensity.fromStorageKey(null))
        assertEquals(CompanionShelfDensity.STANDARD, CompanionShelfDensity.fromStorageKey(""))
        assertEquals(CompanionShelfDensity.STANDARD, CompanionShelfDensity.fromStorageKey("2"))
        assertEquals(CompanionShelfDensity.STANDARD, CompanionShelfDensity.fromStorageKey("LARGE ".uppercase()))
    }

    @Test
    fun `each step fills the page exactly with whole rows`() {
        CompanionShelfDensity.values().forEach { step ->
            val grid = solve(step)
            assertEquals(step.columns, grid.columns)
            assertEquals(step.rows, grid.rows)
            assertEquals("${step.name} fills the width", pageWidth, grid.columns * grid.cellWidth + (grid.columns - 1) * gap, 0.01f)
            assertEquals("${step.name} fills the page height", pageHeight, grid.rows * grid.cellHeight + (grid.rows - 1) * gap, 0.01f)
            assertEquals(pageHeight, grid.pageHeight, 0.01f)
        }
        // The design's table, for the 506 dp page: Large 247 wide, Standard 117.5, Dense 74.33.
        assertEquals(247f, solve(CompanionShelfDensity.LARGE).cellWidth, 0.01f)
        assertEquals(117.5f, solve(CompanionShelfDensity.STANDARD).cellWidth, 0.01f)
        assertEquals(74.333f, solve(CompanionShelfDensity.DENSE).cellWidth, 0.01f)
        assertEquals("Large is one row of the whole page", pageHeight, solve(CompanionShelfDensity.LARGE).cellHeight, 0.01f)
        assertEquals((pageHeight - gap) / 2f, solve(CompanionShelfDensity.STANDARD).cellHeight, 0.01f)
        assertEquals((pageHeight - 2 * gap) / 3f, solve(CompanionShelfDensity.DENSE).cellHeight, 0.01f)
    }

    @Test
    fun `every cell at every step is a 48 dp target`() {
        CompanionShelfDensity.values().forEach { step ->
            val grid = solve(step)
            assertTrue("${step.name} cell ${grid.cellWidth} x ${grid.cellHeight}", grid.cellWidth >= 48f && grid.cellHeight >= 48f)
        }
    }

    @Test
    fun `a seat is page-local and a position resolves only on the page it is asked about`() {
        val grid = solve(CompanionShelfDensity.STANDARD, count = 43) // 8 a page: pages 0..5, the last holding 3
        assertEquals(6, grid.pageCount)
        // Seats 8 and 0 sit in the same cell of their pages.
        assertEquals(grid.originOf(0), grid.originOf(8))
        assertEquals(grid.originOf(3), grid.originOf(11))
        (0 until 43).forEach { index ->
            val page = companionShelfPageOf(index, grid.perPage)
            assertEquals("seat $index did not round-trip on page $page", index, grid.seatAt(grid.centreOf(index), page))
        }
        // A finger far outside the page clamps to this page's own ends, never a neighbour's.
        assertEquals(8, grid.seatAt(Offset(-500f, -500f), 1))
        assertEquals(15, grid.seatAt(Offset(5000f, 5000f), 1))
        assertEquals("the last page is partial: the far corner is its last game", 42, grid.seatAt(Offset(5000f, 5000f), 5))
        assertEquals(40, grid.seatAt(Offset(-5f, -5f), 5))
        assertEquals("a page past the end is the last page", 42, grid.seatAt(Offset(5000f, 5000f), 99))
    }

    @Test
    fun `pages are whole rows and the last page may be partial`() {
        assertEquals(1, companionShelfPageCount(0, 8))
        assertEquals(1, companionShelfPageCount(8, 8))
        assertEquals(2, companionShelfPageCount(9, 8))
        assertEquals(6, companionShelfPageCount(43, 8))
        assertEquals(22, companionShelfPageCount(43, 2))
        assertEquals(3, companionShelfPageCount(43, 18))
        assertEquals(0, companionShelfPageOf(7, 8))
        assertEquals(1, companionShelfPageOf(8, 8))
        assertEquals(5, companionShelfPageOf(42, 8))
        // The last page of 43 at 18 a page holds 43 - 36 = 7 games: the first row full, the second with one.
        val grid = solve(CompanionShelfDensity.DENSE, count = 43)
        assertEquals(36, 2 * grid.perPage)
        assertEquals(42, grid.seatAt(Offset(5000f, 5000f), 2))
    }

    @Test
    fun `a step lands on the page that still holds the player's place`() {
        // Standard page 3 starts at seat 24; at Dense (18 a page) seat 24 is on page 1; at Large (2 a page) it is on page 12.
        assertEquals(1, companionShelfPageAfterStep(24, 18, 43))
        assertEquals(12, companionShelfPageAfterStep(24, 2, 43))
        // Never past the pages that exist, and never negative.
        assertEquals(2, companionShelfPageAfterStep(1_000, 18, 43))
        assertEquals(0, companionShelfPageAfterStep(-4, 18, 43))
        assertEquals(0, companionShelfPageAfterStep(5, 8, 0))
    }

    @Test
    fun `the strip says Page n of m, counted from 1, and the runes give way to words past twelve pages`() {
        assertEquals("Page 1 of 6", companionShelfPageDescription(0, 6))
        assertEquals("Page 6 of 6", companionShelfPageDescription(5, 6))
        assertEquals("a page past the end reads as the last", "Page 6 of 6", companionShelfPageDescription(9, 6))
        assertEquals("never zero pages", "Page 1 of 1", companionShelfPageDescription(0, 0))
        // 44 diamonds at 15 dp is 660 dp, more than the 506 dp page; Large with 87 games is 44 pages.
        assertTrue(companionShelfPageCount(87, CompanionShelfDensity.LARGE.perPage) > CompanionShelfRunesMaxPages)
        assertTrue(companionShelfPageCount(87, CompanionShelfDensity.STANDARD.perPage) <= CompanionShelfRunesMaxPages)
        assertTrue(CompanionShelfRunesMaxPages * 15f <= 506f)
    }

    @Test
    fun `the count says 1 game and N games`() {
        assertEquals("1 game", companionShelfGameCountLabel(1))
        assertEquals("87 games", companionShelfGameCountLabel(87))
        assertEquals("0 games", companionShelfGameCountLabel(0))
    }

    @Test
    fun `the icon seat is the platform's art, or its console line art, or nothing the product has not established`() {
        val uri = "file:///storage/emulated/0/media_art/genesis/icon.png"
        // Not proved yet: nothing, so console art never flashes before wired art.
        assertEquals(CompanionShelfIconKind.NONE, companionShelfIconKind(hydrated = false, wiredUri = uri, wired = true))
        assertEquals(CompanionShelfIconKind.NONE, companionShelfIconKind(hydrated = false, wiredUri = null, wired = false))
        // Wired art on disk: drawn, bare.
        assertEquals(CompanionShelfIconKind.WIRED, companionShelfIconKind(hydrated = true, wiredUri = uri, wired = true))
        // No wired art: the console line art. A deleted file is no art (R8a), so it is this too.
        assertEquals(CompanionShelfIconKind.CONSOLE, companionShelfIconKind(hydrated = true, wiredUri = null, wired = false))
        assertEquals(CompanionShelfIconKind.CONSOLE, companionShelfIconKind(hydrated = true, wiredUri = "  ", wired = true))
        assertEquals(CompanionShelfIconKind.CONSOLE, companionShelfIconKind(hydrated = true, wiredUri = uri, wired = false))
    }

    @Test
    fun `the popover's rows name the grid and the rung`() {
        assertEquals(listOf("2×1 · Large", "4×2 · Standard", "6×3 · Dense"), CompanionShelfDensity.values().map { it.rungLabel })
    }
}
