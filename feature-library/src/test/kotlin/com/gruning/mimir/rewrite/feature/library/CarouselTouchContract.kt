package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What every coverflow the controller moves owes a finger: the gamelist, the Android shelf and the Library's platform shelf each run these same checks, through
 * real touch events on a paused clock, against the real composable.
 *
 *  - a horizontal drag carries the row one for one, and focus stays put until the finger lifts;
 *  - a lift settles on an item by the controller's own rounding (halfway), a fling carries on past the finger by friction,
 *    and either way focus then moves to the settled item through the one authority;
 *  - a tap is the tile's own, as it was (touch activates at a point), and a drag never opens a tile;
 *  - a vertical drag is left to whatever scrolls above;
 *  - a list rebuilt under a finger abandons the drag, and an overlay that takes input mid-drag leaves the row settled on the
 *    item it is keyed to, before and after the overlay closes;
 *  - the pad and the finger move one row, each from where the other left it;
 *  - fifty moves of a drag recompose the row at most twice;
 *  - with motion reduced the lift settles at once.
 */
abstract class CarouselTouchContract {

    @get:Rule
    val compose = createComposeRule()

    /** The finger's travel that a scroll above the row received. */
    protected var verticalTravel = 0f

    /** The touch slop of the host, in pixels (a pixel is a dp here). */
    protected var slopPx = 0f

    /** The indices the host was asked to open, in order. */
    protected val opened = mutableListOf<Int>()

    /** The item the fixture starts on: room to move both ways, and no wrap or clamp inside a fling. */
    protected val startAt = 2

    /** The one authority the carousel under test is mounted on. */
    protected val authority = DefaultFocusAuthority()

    /** Composes the carousel with every item present, focuses [startAt] and lets the row settle. */
    protected abstract fun mount()

    protected abstract fun focusedIndex(): Int?

    protected abstract fun requestFocusOn(index: Int)

    /** The tile of [index], which must be mounted. */
    protected abstract fun tile(index: Int): SemanticsNodeInteraction

    /** The continuous seat of [index] around the row's centre (0 is the hero seat). */
    protected abstract fun seat(index: Int): Float

    /** One step of the pad. */
    protected abstract fun pad(right: Boolean)

    /** Replaces the list with one that has lost its last item (never the focused one): a new identity for the row. */
    protected abstract fun rebuildItems()

    /** A count that rises once for each recomposition of the row. */
    protected abstract fun probe(): Int

    @After
    fun restoreAnimators() = setCarouselAnimatorScale(1f)

    /** The host: the theme, a scroll above the row that reads vertical drags, and the slop. */
    @Composable
    protected fun TouchFrame(content: @Composable () -> Unit) {
        MimirTheme {
            slopPx = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier
                    .size(833.dp, 444.dp)
                    .draggable(rememberDraggableState { delta -> verticalTravel += delta }, Orientation.Vertical),
            ) {
                content()
            }
        }
    }

    protected fun tileCenter(index: Int): Offset = tile(index).fetchSemanticsNode().boundsInRoot.center

    protected fun pitchPx(index: Int): Float = abs(tileCenter(index + 1).x - tileCenter(index).x)

    /** Asks for focus on [index] until the authority holds it, then lets the row glide there and settle. */
    protected fun focusAndSettle(index: Int) {
        var tries = 0
        while (focusedIndex() != index && tries < 8) {
            requestFocusOn(index)
            compose.touchFrames(2)
            tries++
        }
        compose.touchSettle(2_000L)
        assertEquals("the fixture reached its starting item", index, focusedIndex())
        opened.clear()
    }

    /**
     * Drags from the focused item's tile by [fraction] of the pitch (positive: the finger goes left and the row advances),
     * rests, lifts, and lets the clock run for [settleMs]. The finger travels the slop on top, which the row does not follow.
     */
    protected fun dragFraction(fraction: Float, settleMs: Long = 2_000L) {
        val focused = checkNotNull(focusedIndex()) { "nothing is focused" }
        val from = tileCenter(focused)
        val total = abs(fraction) * pitchPx(focused) + slopPx
        val move = (if (fraction > 0f) -total else total) / 12f
        compose.touchDown(from)
        repeat(12) { compose.touchMove(move) }
        compose.touchRest()
        compose.touchUp()
        if (settleMs > 0L) compose.touchSettle(settleMs)
    }

    // ── The drag ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a horizontal drag carries the row one for one with the finger, and focus waits for the lift`() {
        mount()
        val from = tileCenter(startAt)
        val next = tileCenter(startAt + 1)
        val pitch = abs(next.x - from.x)
        assertTrue("the fixture must show two neighbours apart: $pitch", pitch > 40f)
        val carried = 0.3f * pitch
        val total = carried + slopPx

        compose.touchDown(from)
        repeat(12) { compose.touchMove(-total / 12f) }

        assertEquals("the tile under the finger travels with it", from.x - carried, tileCenter(startAt).x, 2.5f)
        assertEquals("and so does its neighbour", next.x - carried, tileCenter(startAt + 1).x, 2.5f)
        assertEquals("the centre is where the finger put it", -0.3f, seat(startAt), 0.04f)
        assertEquals("focus has not moved", startAt, focusedIndex())
        assertTrue("nothing was opened", opened.isEmpty())

        compose.touchRest()
        compose.touchUp()
    }

    @Test
    fun `a drag past the halfway point settles on the next item, which takes focus`() {
        mount()
        val before = tileCenter(startAt)

        dragFraction(0.62f)

        assertEquals("focus follows the settle", startAt + 1, focusedIndex())
        assertEquals("the next item rests in the hero seat", 0f, seat(startAt + 1), 0.01f)
        assertEquals("where the focused item sat", before.x, tileCenter(startAt + 1).x, 3f)
        assertTrue("a drag opens nothing", opened.isEmpty())
    }

    @Test
    fun `a drag short of the halfway point comes back to the item it began on`() {
        mount()
        val before = tileCenter(startAt)

        dragFraction(0.3f)

        assertEquals("the same item keeps focus", startAt, focusedIndex())
        assertEquals(0f, seat(startAt), 0.01f)
        assertEquals(before.x, tileCenter(startAt).x, 3f)
        assertTrue("a drag opens nothing", opened.isEmpty())
    }

    @Test
    fun `a drag to the right goes back one item the same way`() {
        mount()

        dragFraction(-0.62f)

        assertEquals(startAt - 1, focusedIndex())
        assertEquals(0f, seat(startAt - 1), 0.01f)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a fling carries on past the finger and settles on one item, with focus on it`() {
        mount()
        val pitch = pitchPx(startAt)

        compose.touchDown(tileCenter(startAt))
        // About twenty-eight items a second for five frames: far faster than a drag, and well past where the finger stops.
        repeat(5) { compose.touchMove(-0.45f * pitch) }
        compose.touchUp()
        compose.touchSettle(3_000L)

        val landed = focusedIndex()
        assertTrue("the fling must carry beyond the item the finger alone would reach: $landed", landed != null && landed >= startAt + 4)
        assertEquals("and rest on that item", 0f, seat(landed!!), 0.01f)
        assertTrue("a fling opens nothing", opened.isEmpty())
    }

    // ── The tap, which is the tile's own ─────────────────────────────────────────────────────────

    @Test
    fun `a tap is the tile's own as it was, so another item is focused and opened and the focused one is opened`() {
        mount()

        compose.touchTap(tileCenter(startAt + 1))
        compose.touchFrames(2)
        assertEquals("a tap on another item opens it", listOf(startAt + 1), opened.toList())
        assertEquals("and focuses it", startAt + 1, focusedIndex())

        compose.touchSettle(2_000L)
        compose.touchTap(tileCenter(startAt + 1))
        compose.touchFrames(2)
        assertEquals("a tap on the focused item opens it again", listOf(startAt + 1, startAt + 1), opened.toList())
    }

    // ── The axis ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a vertical drag is left to whatever scrolls above and does not move the row`() {
        mount()

        compose.touchDown(tileCenter(startAt))
        repeat(10) { compose.touchMove(0f, -8f) }
        compose.touchUp()

        assertTrue("the scroll above received the drag: $verticalTravel", verticalTravel < -40f)
        assertEquals("the row has not moved", 0f, seat(startAt), 0.001f)
        assertEquals(startAt, focusedIndex())
        assertTrue(opened.isEmpty())
    }

    // ── A row that is replaced, or covered, under a finger ──────────────────────────────────────

    @Test
    fun `a list rebuilt under a finger abandons the drag, so the lift selects nothing and focus stays put`() {
        mount()
        val pitch = pitchPx(startAt)

        compose.touchDown(tileCenter(startAt))
        repeat(6) { compose.touchMove(-0.1f * pitch) }
        rebuildItems()
        compose.touchFrames(3)
        // The same finger carries on over the new row: a drag the new row never began, which it must not act on.
        repeat(6) { compose.touchMove(-0.1f * pitch) }
        compose.touchRest()
        compose.touchUp()
        compose.touchSettle(2_000L)

        assertEquals("focus is where it was before the drag, not item 0", startAt, focusedIndex())
        assertEquals("the new row rests on it", 0f, seat(startAt), 0.01f)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `an overlay that takes input mid-drag leaves the row settled on the focused item, before and after it closes`() {
        mount()
        val pitch = pitchPx(startAt)

        compose.touchDown(tileCenter(startAt))
        // Far enough that the lift would have chosen the next item.
        repeat(10) { compose.touchMove(-0.08f * pitch) }
        authority.pushScope(FocusScope("test/overlay"))
        compose.touchFrames(2)
        compose.touchRest()
        compose.touchUp()
        compose.touchSettle(2_000L)

        assertEquals("refused, the row is back on the item it is keyed to", 0f, seat(startAt), 0.01f)

        authority.popScope()
        compose.touchFrames(3)
        compose.touchSettle(1_000L)
        assertEquals("the overlay closed onto the item it covered", startAt, focusedIndex())
        assertEquals(0f, seat(startAt), 0.01f)

        // Nothing is left engaged: the pad takes the row on as it always did.
        pad(right = true)
        compose.touchSettle(2_000L)
        assertEquals(startAt + 1, focusedIndex())
        assertEquals(0f, seat(startAt + 1), 0.01f)
        assertTrue(opened.isEmpty())
    }

    // ── The pad and the finger ───────────────────────────────────────────────────────────────────

    @Test
    fun `the pad and the finger move one row in turn, each from where the other left it`() {
        mount()

        dragFraction(0.62f)
        assertEquals("the finger moved it on one", startAt + 1, focusedIndex())

        pad(right = true)
        compose.touchSettle(2_000L)
        assertEquals("the pad moved it on from where the finger left it", startAt + 2, focusedIndex())
        assertEquals(0f, seat(startAt + 2), 0.01f)

        dragFraction(-0.62f)
        assertEquals("the finger took it back one from where the pad left it", startAt + 1, focusedIndex())

        pad(right = false)
        compose.touchSettle(2_000L)
        assertEquals(startAt, focusedIndex())
        assertEquals(0f, seat(startAt), 0.01f)
        assertTrue(opened.isEmpty())
    }

    // ── The budget ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `fifty moves of a drag recompose the row at most twice`() {
        mount()
        val live = probe()
        assertTrue("the probe must be live, or this proves nothing", live > 0)

        val pitch = pitchPx(startAt)

        compose.touchDown(tileCenter(startAt))
        repeat(50) { compose.touchMove(-1f) }
        val during = probe() - live

        assertTrue("fifty moves recomposed the row $during times", during <= 2)
        // The control: carrying it on to the next item and lifting recomposes the row, which the probe must see.
        repeat(6) { compose.touchMove(-0.15f * pitch) }
        compose.touchRest()
        compose.touchUp()
        compose.touchSettle(2_000L)
        assertTrue("the probe sees the recomposition a lift causes", probe() > live + during)
    }

    // ── Motion ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with motion on, the lift glides to its item over the next frames`() {
        mount()

        dragFraction(0.62f, settleMs = 0L)

        assertTrue("a frame after the lift the row is still on its way: ${seat(startAt + 1)}", abs(seat(startAt + 1)) > 0.02f)
        compose.touchSettle(2_000L)
        assertEquals(startAt + 1, focusedIndex())
        assertEquals(0f, seat(startAt + 1), 0.01f)
    }

    @Test
    fun `with motion reduced the lift settles at once and focus follows`() {
        setCarouselAnimatorScale(0f)
        mount()

        dragFraction(0.62f, settleMs = 0L)
        compose.touchFrames(3)

        assertEquals(startAt + 1, focusedIndex())
        assertEquals("settled without a glide", 0f, seat(startAt + 1), 0.01f)
        assertTrue(opened.isEmpty())
    }
}
