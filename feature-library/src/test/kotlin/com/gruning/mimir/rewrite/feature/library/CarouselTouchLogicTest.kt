package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions behind touch movement of the Main carousels (CarouselTouch.kt), as plain functions: where a gesture's axis
 * locks, where a release settles and how a finger's travel maps onto a coverflow's centre or the Home strip's window. The
 * gestures themselves, on real touch events, are in the CarouselTouch*Test classes beside this one.
 */
class CarouselTouchLogicTest {

    // ── The axis lock ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `travel inside the slop locks nothing`() {
        assertEquals(CarouselAxisLock.PENDING, carouselAxisLock(totalDx = 5f, totalDy = 3f, touchSlopPx = 8f))
        assertEquals(CarouselAxisLock.PENDING, carouselAxisLock(totalDx = 0f, totalDy = 0f, touchSlopPx = 8f))
    }

    @Test
    fun `a gesture that leaves the slop mostly across the row locks horizontal`() {
        assertEquals(CarouselAxisLock.HORIZONTAL, carouselAxisLock(totalDx = 12f, totalDy = 2f, touchSlopPx = 8f))
        assertEquals(CarouselAxisLock.HORIZONTAL, carouselAxisLock(totalDx = -12f, totalDy = -9f, touchSlopPx = 8f))
    }

    @Test
    fun `a vertical or diagonal gesture is never ours, so a scroll further up the tree gets it whole`() {
        assertEquals(CarouselAxisLock.VERTICAL, carouselAxisLock(totalDx = 2f, totalDy = 12f, touchSlopPx = 8f))
        assertEquals(CarouselAxisLock.VERTICAL, carouselAxisLock(totalDx = -3f, totalDy = -30f, touchSlopPx = 8f))
        // A tie goes to the scroll.
        assertEquals(CarouselAxisLock.VERTICAL, carouselAxisLock(totalDx = 10f, totalDy = 10f, touchSlopPx = 8f))
    }

    // ── The fling ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a slow release settles on the nearest item, with no fling`() {
        assertEquals(0f, carouselFlingProjectionTiles(0f), 0f)
        assertEquals(0f, carouselFlingProjectionTiles(CarouselFlingMinTilesPerSec - 0.01f), 0f)
        assertEquals(3, carouselSettleIndex(centerTiles = 2.6f, velocityTilesPerSec = 0.4f))
        assertEquals(2, carouselSettleIndex(centerTiles = 2.4f, velocityTilesPerSec = 0.4f))
        assertEquals(0, carouselSettleIndex(centerTiles = -0.3f, velocityTilesPerSec = 0f))
    }

    @Test
    fun `a fling carries on by its velocity over the friction, both ways, and never past the cap`() {
        assertEquals(10f / CarouselFlingFrictionPerSec, carouselFlingProjectionTiles(10f), 0.0001f)
        assertEquals(-10f / CarouselFlingFrictionPerSec, carouselFlingProjectionTiles(-10f), 0.0001f)
        assertEquals(CarouselFlingMaxTiles, carouselFlingProjectionTiles(400f), 0f)
        assertEquals(-CarouselFlingMaxTiles, carouselFlingProjectionTiles(-400f), 0f)
        // Lifted at 2.0 going up the line at 21 items a second: 2.0 + 5 (the cap) = 7.
        assertEquals(7, carouselSettleIndex(centerTiles = 2.0f, velocityTilesPerSec = 21f))
        assertEquals(-3, carouselSettleIndex(centerTiles = 2.0f, velocityTilesPerSec = -21f))
    }

    @Test
    fun `a finite row is held between its ends with a little give`() {
        assertEquals(-CarouselOverscrollTiles, carouselClampCenter(-4f, itemCount = 9), 0f)
        assertEquals(8f + CarouselOverscrollTiles, carouselClampCenter(40f, itemCount = 9), 0f)
        assertEquals(3.3f, carouselClampCenter(3.3f, itemCount = 9), 0f)
        assertEquals("one item has no room to move", CarouselOverscrollTiles, carouselClampCenter(5f, itemCount = 1), 0f)
    }

    @Test
    fun `a settle spring is never handed a speed it would overshoot its seat with`() {
        val omega = 22.36068f
        // Toward the seat and slow enough: kept.
        assertEquals(5f, carouselSafeSettleVelocity(velocitySlotsPerSec = 5f, distanceSlots = 1f), 0.0001f)
        // Toward the seat and too fast for a short way: held to omega x distance.
        assertEquals(omega * 0.3f, carouselSafeSettleVelocity(velocitySlotsPerSec = 20f, distanceSlots = 0.3f), 0.001f)
        assertEquals(-omega * 0.3f, carouselSafeSettleVelocity(velocitySlotsPerSec = -20f, distanceSlots = -0.3f), 0.001f)
        // Away from the seat, or nowhere to go: none.
        assertEquals(0f, carouselSafeSettleVelocity(velocitySlotsPerSec = 20f, distanceSlots = -0.3f), 0f)
        assertEquals(0f, carouselSafeSettleVelocity(velocitySlotsPerSec = 20f, distanceSlots = 0f), 0f)
        assertEquals(0f, carouselSafeSettleVelocity(velocitySlotsPerSec = 0f, distanceSlots = 2f), 0f)
    }

    // ── The coverflow drag ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a finger moves a coverflow's centre by exactly the seats its travel spans, and the seat map is its inverse`() {
        // The native row's own geometry: first step 191 dp (integer division), then 141 dp a seat.
        listOf(-640f, -400f, -191f, -90f, -1f, 0f, 1f, 90f, 191f, 300f, 640f).forEach { offsetDp ->
            val position = coverflowPositionForOffsetDp(offsetDp, firstNeighborDp = 191f, restPitchDp = 141f)
            val back = coverflowSlotOffsetDp(position, restWidthDp = 123, focusWidthDp = 224, gapDp = 18)
            assertEquals("offset $offsetDp dp", offsetDp, back, 0.6f)
        }
        // A uniform row (the gamelist, the platform shelf): one step everywhere.
        assertEquals(2.5f, coverflowPositionForOffsetDp(250f, 100f, 100f), 0.0001f)
        assertEquals(-0.5f, coverflowPositionForOffsetDp(-50f, 100f, 100f), 0.0001f)
        assertEquals(0f, coverflowPositionForOffsetDp(0f, 100f, 100f), 0f)
        assertEquals("a row with no measured spacing yet does not move", 0f, coverflowPositionForOffsetDp(80f, 0f, 0f), 0f)
    }

    @Test
    fun `the platform row's uniform seats invert against the shipping seat function`() {
        val seat = 142.74f
        val gap = 13.5f
        listOf(-300f, -50f, 0f, 156.24f, 500f).forEach { offsetDp ->
            val position = coverflowPositionForOffsetDp(offsetDp, firstNeighborDp = seat + gap, restPitchDp = seat + gap)
            assertEquals(
                offsetDp,
                coverflowSlotOffsetDp(position, restWidthDp = seat, focusWidthDp = seat, gapDp = gap),
                0.01f,
            )
        }
    }

    // ── The Home strip ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `dragging the strip left advances the window and dragging right brings it back, within a little give`() {
        assertEquals(1.5f, homeShelfDraggedWindowPosition(position = 1f, dxPx = -100f, stridePx = 200f, maxWindowStart = 6), 0.0001f)
        assertEquals(0.5f, homeShelfDraggedWindowPosition(position = 1f, dxPx = 100f, stridePx = 200f, maxWindowStart = 6), 0.0001f)
        assertEquals(-CarouselOverscrollTiles, homeShelfDraggedWindowPosition(0f, 900f, 200f, 6), 0f)
        assertEquals(6f + CarouselOverscrollTiles, homeShelfDraggedWindowPosition(6f, -900f, 200f, 6), 0f)
        assertEquals(2f, homeShelfDraggedWindowPosition(2f, -50f, 0f, 6), 0f)
    }

    @Test
    fun `the strip settles on a whole window start inside the strip`() {
        assertEquals(2, homeShelfSettleWindowStart(position = 1.6f, velocitySlotsPerSec = 0f, itemCount = 10, visibleSeatCount = 4))
        assertEquals(1, homeShelfSettleWindowStart(position = 1.4f, velocitySlotsPerSec = 0.5f, itemCount = 10, visibleSeatCount = 4))
        assertEquals("a fling past the end stops at the last window", 6, homeShelfSettleWindowStart(4.2f, 30f, 10, 4))
        assertEquals("and past the start at the first", 0, homeShelfSettleWindowStart(1.2f, -30f, 10, 4))
        assertEquals("a strip that fits has one window", 0, homeShelfSettleWindowStart(0.8f, 10f, 3, 4))
    }

    @Test
    fun `a focus inside the allowed range keeps the window where a touch put it, for every strip and every window`() {
        // The range is the pad's own rule read backwards: homeShelfWindowStart, given the window the touch chose as the
        // previous start, must hand that same start back for every focus in the range and for none outside it.
        for (seats in 3..5) {
            for (count in 1..14) {
                val maxStart = (count - seats).coerceAtLeast(0)
                for (start in 0..maxStart) {
                    val range = homeShelfFocusRangeForWindow(start, count, seats)
                    assertFalse("seats=$seats count=$count start=$start: the range must not be empty", range.isEmpty())
                    for (focus in 0 until count) {
                        val resolved = homeShelfWindowStart(previousStart = start, focusedIndex = focus, itemCount = count, visibleSeatCount = seats)
                        assertEquals(
                            "seats=$seats count=$count start=$start focus=$focus: in range=${focus in range}",
                            focus in range,
                            resolved == start,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the focused item is the one now at the ring's seat, held to the allowed seats`() {
        // Four seats, ten items, ring on the second seat of the first window (index 1).
        assertEquals(3, homeShelfFocusForWindow(currentSeat = 1, windowStart = 2, itemCount = 10, visibleSeatCount = 4))
        // The first window lets the ring sit on seat 0; an interior window does not (that seat gives way).
        assertEquals(0, homeShelfFocusForWindow(currentSeat = 0, windowStart = 0, itemCount = 10, visibleSeatCount = 4))
        assertEquals(4, homeShelfFocusForWindow(currentSeat = 0, windowStart = 3, itemCount = 10, visibleSeatCount = 4))
        // The last window keeps the last seat; an interior one holds the ring off it.
        assertEquals(9, homeShelfFocusForWindow(currentSeat = 3, windowStart = 6, itemCount = 10, visibleSeatCount = 4))
        assertEquals(5, homeShelfFocusForWindow(currentSeat = 3, windowStart = 3, itemCount = 10, visibleSeatCount = 4))
        // A strip that fits settles on the item the ring is on.
        assertEquals(2, homeShelfFocusForWindow(currentSeat = 2, windowStart = 0, itemCount = 3, visibleSeatCount = 4))
    }

    @Test
    fun `the mounted window is the logical window until a touch carries the strip away, then both and the way between`() {
        val items = List(12) { "item$it" }
        val logical = homeShelfBufferedWindow(items, windowStart = 2, visibleSeatCount = 4)
        assertEquals(
            logical.map { it.itemIndex to it.slotOffset },
            homeShelfTouchMountedWindow(items, windowStart = 2, mountStart = 2, visibleSeatCount = 4).map { it.itemIndex to it.slotOffset },
        )

        val away = homeShelfTouchMountedWindow(items, windowStart = 2, mountStart = 6, visibleSeatCount = 4)
        // From the logical window's buffer (index 1) to the painted window's buffer (index 10), nothing missing between.
        assertEquals((1..10).toList(), away.map { it.itemIndex })
        // Seats are still measured from the LOGICAL start, so the ring and the roles keep their meaning.
        assertEquals(away.map { it.itemIndex - 2 }, away.map { it.slotOffset })
        assertTrue(away.all { it.item == items[it.itemIndex] })

        val back = homeShelfTouchMountedWindow(items, windowStart = 6, mountStart = 2, visibleSeatCount = 4)
        assertEquals((1..10).toList(), back.map { it.itemIndex })
        assertEquals(back.map { it.itemIndex - 6 }, back.map { it.slotOffset })
        assertEquals(emptyList<HomeShelfVisibleSlot<String>>(), homeShelfTouchMountedWindow(emptyList<String>(), 0, 3, 4))
    }
}
