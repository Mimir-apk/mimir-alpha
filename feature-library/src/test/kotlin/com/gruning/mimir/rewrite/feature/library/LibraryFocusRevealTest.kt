package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Focused-row visual-settlement contract.
 *
 * The regression these pin: a row straddling the viewport edge IS in `visibleItemsInfo`, so the old
 * membership test ("is focusedIndex between first and last visible index?") answered "already visible"
 * and scrolled nothing, leaving the focused row clipped. Every case below is expressed in the same
 * coordinate space the composable passes through from `LazyListLayoutInfo`.
 *
 * Viewport used throughout: start 0, end 1000. Rows are 200 tall unless stated.
 */
class LibraryFocusRevealTest {

    @Test
    fun `focused row below the reading seat carries the panel toward centre`() {
        val action = focusRevealAction(
            focusedIndex = 4,
            firstVisibleIndex = 0,
            visibleCount = 5,
            focusedItemOffset = 900,
            focusedItemSize = 200,
            viewportStart = 0,
            viewportEnd = 1000,
        )
        assertEquals(FocusRevealAction.ScrollBy(500f), action)
    }

    @Test
    fun `focused row above the reading seat carries the panel toward centre`() {
        val action = focusRevealAction(
            focusedIndex = 1,
            firstVisibleIndex = 1,
            visibleCount = 5,
            focusedItemOffset = -60,
            focusedItemSize = 200,
            viewportStart = 0,
            viewportEnd = 1000,
        )
        assertEquals(FocusRevealAction.ScrollBy(-460f), action)
    }

    @Test
    fun `a focused row already centred is left alone`() {
        val action = focusRevealAction(
            focusedIndex = 2,
            firstVisibleIndex = 0,
            visibleCount = 5,
            focusedItemOffset = 400,
            focusedItemSize = 200,
            viewportStart = 0,
            viewportEnd = 1000,
        )
        assertEquals(FocusRevealAction.None, action)
    }

    @Test
    fun `visible terminal rows request centre travel and let the scroll owner clamp the real edge`() {
        assertEquals(
            FocusRevealAction.ScrollBy(-400f),
            focusRevealAction(0, 0, 5, 0, 200, 0, 1000),
        )
        assertEquals(
            FocusRevealAction.ScrollBy(400f),
            focusRevealAction(4, 0, 5, 800, 200, 0, 1000),
        )
    }

    @Test
    fun `an uncomposed row gets one centered animated arrival`() {
        val action = focusRevealAction(
            focusedIndex = 20,
            firstVisibleIndex = 0,
            visibleCount = 5,
            focusedItemOffset = null,
            focusedItemSize = null,
            viewportStart = 0,
            viewportEnd = 1000,
            estimatedItemSize = 200,
        )
        assertEquals(FocusRevealAction.AnimateTo(20, -400), action)
    }

    @Test
    fun `an uncomposed row above uses the same one-arrival seat`() {
        val action = focusRevealAction(
            focusedIndex = 2,
            firstVisibleIndex = 10,
            visibleCount = 5,
            focusedItemOffset = null,
            focusedItemSize = null,
            viewportStart = 0,
            viewportEnd = 1000,
            estimatedItemSize = 200,
        )
        assertEquals(FocusRevealAction.AnimateTo(2, -400), action)
    }

    @Test
    fun `content replacement restores without visible animation`() {
        val action = focusRevealAction(
            focusedIndex = 2,
            firstVisibleIndex = 0,
            visibleCount = 8,
            focusedItemOffset = null,
            focusedItemSize = null,
            viewportStart = 0,
            viewportEnd = 1000,
            estimatedItemSize = 200,
            restoring = true,
        )
        assertEquals(FocusRevealAction.SeekTo(2, -400), action)
    }

    @Test
    fun `nothing laid out yet seeks without a correction pass`() {
        assertEquals(
            FocusRevealAction.SeekTo(7, 0),
            focusRevealAction(7, null, 0, null, null, 0, 1000),
        )
        assertEquals(
            FocusRevealAction.SeekTo(7, 0),
            focusRevealAction(7, 0, 0, null, null, 0, 1000),
        )
    }

    @Test
    fun `no focus means no scroll`() {
        assertEquals(
            FocusRevealAction.None,
            focusRevealAction(-1, 0, 5, null, null, 0, 1000),
        )
    }

    @Test
    fun `a row taller than the viewport reveals its leading edge first`() {
        // Degenerate but reachable (a long row on a short panel). Both clips are positive; leading wins,
        // so the row's start is what the user sees rather than an unreadable middle.
        val action = focusRevealAction(
            focusedIndex = 1,
            firstVisibleIndex = 0,
            visibleCount = 2,
            focusedItemOffset = -50,
            focusedItemSize = 1200,
            viewportStart = 0,
            viewportEnd = 1000,
        )
        assertEquals(FocusRevealAction.ScrollBy(-50f), action)
    }
}
