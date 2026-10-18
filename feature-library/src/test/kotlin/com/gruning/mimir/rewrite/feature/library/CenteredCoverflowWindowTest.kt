package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CenteredCoverflowWindowTest {

    @Test
    fun middleFocusUsesFiveItemWindowWithFocusInCenterSlot() {
        val window = centeredCoverflowWindow((0 until 9).toList(), focusedIndex = 4, radius = 2)

        assertEquals(listOf(2, 3, 4, 5, 6), window.visibleItems)
        assertEquals(2, window.focusedWindowIndex)
        assertEquals(4, window.focusedItemIndex)
    }

    @Test
    fun edgeFocusClampsWindowWithoutOppositeEndNeighbors() {
        val first = centeredCoverflowWindow((0 until 7).toList(), focusedIndex = 0, radius = 2)
        val last = centeredCoverflowWindow((0 until 7).toList(), focusedIndex = 6, radius = 2)

        assertEquals(listOf(0, 1, 2, 3, 4), first.visibleItems)
        assertEquals(0, first.focusedWindowIndex)
        assertEquals(listOf(2, 3, 4, 5, 6), last.visibleItems)
        assertEquals(4, last.focusedWindowIndex)
    }

    @Test
    fun smallShelvesStayWindowedAroundFocusWithoutDuplicates() {
        val window = centeredCoverflowWindow(listOf("a", "b", "c"), focusedIndex = 0, radius = 2)

        assertEquals(listOf("a", "b", "c"), window.visibleItems)
        assertEquals(0, window.focusedWindowIndex)
        assertEquals(0, window.focusedItemIndex)
    }

    @Test
    fun evenSizedShelvesExposeFocusedWindowIndexForVisualCentering() {
        // RCP-03 even-window rule (half = (visibleCount-1)/2): the focused item sits at the LEFT of an even
        // window so the layout reads [focused][right…] not [left…][focused] — natural left-to-right nav.
        val two = centeredCoverflowWindow(listOf("a", "b"), focusedIndex = 0, radius = 2)
        val four = centeredCoverflowWindow(listOf("a", "b", "c", "d"), focusedIndex = 1, radius = 2)

        // 2 items, focus "a": [a(centre)][b(right)] — the Dreamcast case (House of the Dead sits to the right).
        assertEquals(listOf("a", "b"), two.visibleItems)
        assertEquals(0, two.focusedWindowIndex)
        assertEquals(0, two.focusedItemIndex)
        // 4 items, focus "b": one neighbour left, two right — [a][b(centre)][c][d].
        assertEquals(listOf("a", "b", "c", "d"), four.visibleItems)
        assertEquals(1, four.focusedWindowIndex)
        assertEquals(1, four.focusedItemIndex)
    }

    @Test
    fun outOfRangeFocusIndexesClampBeforeWindowing() {
        val negative = centeredCoverflowWindow((0 until 5).toList(), focusedIndex = -1, radius = 2)
        val overflow = centeredCoverflowWindow((0 until 5).toList(), focusedIndex = 7, radius = 2)

        assertEquals(listOf(0, 1, 2, 3, 4), negative.visibleItems)
        assertEquals(0, negative.focusedWindowIndex)
        assertEquals(0, negative.focusedItemIndex)
        assertEquals(listOf(0, 1, 2, 3, 4), overflow.visibleItems)
        assertEquals(4, overflow.focusedWindowIndex)
        assertEquals(4, overflow.focusedItemIndex)
    }

    @Test
    fun circularWindowWrapsAtBothPlatformRingEdges() {
        val items = (0 until 7).toList()
        val first = circularCoverflowWindow(items, focusedIndex = 0, radius = 2)
        val last = circularCoverflowWindow(items, focusedIndex = 6, radius = 2)

        assertEquals(listOf(5, 6, 0, 1, 2), first.visibleItems)
        assertEquals(2, first.focusedWindowIndex)
        assertEquals(listOf(4, 5, 6, 0, 1), last.visibleItems)
        assertEquals(2, last.focusedWindowIndex)
    }

    @Test
    fun circularSmallRingMountsEachPlatformExactlyOnce() {
        val window = circularCoverflowWindow(listOf("a", "b", "c"), focusedIndex = 2, radius = 2)

        assertEquals(3, window.visibleItems.size)
        assertEquals(3, window.visibleItems.toSet().size)
        assertEquals(listOf("b", "c", "a"), window.visibleItems)
        assertEquals(1, window.focusedWindowIndex)
    }

    @Test
    fun circularFocusIndexesUseModuloInsteadOfClamping() {
        assertEquals(4, circularCoverflowWindow((0 until 5).toList(), -1, 2).focusedItemIndex)
        assertEquals(2, circularCoverflowWindow((0 until 5).toList(), 7, 2).focusedItemIndex)
    }
}
