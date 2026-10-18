package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Home shelf under a finger, on the real [HomeShelfScreen]: a finite, left-anchored strip. A drag carries the whole
 * strip one for one; a lift settles it on a whole window start and focuses the item then at the ring's seat, held inside the
 * seats the pad's own window rule allows ([homeShelfFocusRangeForWindow]); a tap is the tile's own (policy T-1); a
 * vertical drag is left alone; the pad and the finger move one strip, each from where the other left it; and fifty moves of a
 * drag recompose the shelf at most twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h560dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarouselTouchHomeShelfTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = List(12) { index ->
        HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("game-$index")),
            rank = index * 1_024L,
            addedAtMillis = index.toLong(),
            title = "Game $index",
            supportingLabel = "gba",
        )
    }
    private val authority = DefaultFocusAuthority()
    private val launched = mutableListOf<Int>()
    private val statusReads = StatusLineProbe()
    private val visibleSeats = solveHomeShelfGeometry(700f, 350f).visibleSlotCount
    private var verticalTravel = 0f
    private var slopPx = 0f
    private var stridePx = 0f

    @After
    fun restoreAnimators() = setCarouselAnimatorScale(1f)

    private fun mount(reducedMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                slopPx = LocalViewConfiguration.current.touchSlop
                Box(
                    Modifier
                        .size(700.dp, 350.dp)
                        .draggable(rememberDraggableState { delta -> verticalTravel += delta }, Orientation.Vertical),
                ) {
                    HomeShelfScreen(
                        rows = rows,
                        authority = authority,
                        onLaunch = { row -> launched += rows.indexOf(row) },
                        onOpenLibrary = {},
                        onQuickAction = {},
                        launchStatusLines = statusReads,
                        reducedMotion = reducedMotion,
                        modifier = Modifier.size(700.dp, 350.dp),
                    )
                }
            }
        }
        compose.touchFrames(3)
        authority.requestFocus(rows[0].focusKey)
        compose.touchFrames(2)
        compose.touchSettle(1_000L)
        assertEquals("the shelf holds input", HomeShelfScope, authority.state.value.scope)
        assertEquals("the fixture starts on the first game", 0, focusedIndex())
        stridePx = abs(centre(3).x - centre(2).x)
        assertTrue("two side tiles must stand apart: $stridePx", stridePx > 30f)
        launched.clear()
    }

    private fun tile(index: Int): SemanticsNodeInteraction = compose.onNodeWithTag(homeShelfTileTestTag(rows[index]))

    private fun isMounted(index: Int): Boolean =
        compose.onAllNodesWithTag(homeShelfTileTestTag(rows[index])).fetchSemanticsNodes().isNotEmpty()

    private fun slot(index: Int): Float = tile(index).fetchSemanticsNode().config[HomeShelfVisualSlotSemantics]

    private fun centre(index: Int): Offset = tile(index).fetchSemanticsNode().boundsInRoot.center

    private fun focusedIndex(): Int? = rows.indexOfFirst { it.focusKey == authority.state.value.focused }.takeIf { it >= 0 }

    /** The window start the strip rests on: the one game standing exactly in the first seat. */
    private fun restingWindowStart(): Int =
        rows.indices.first { isMounted(it) && abs(slot(it)) < 0.02f }

    /**
     * Drags from the tile of [from] by [slots] strides (positive: the finger goes left and the strip advances), rests,
     * lifts, and lets the clock run for [settleMs]. The finger travels the slop on top, which the strip does not follow.
     */
    private fun drag(slots: Float, from: Int = 2, settleMs: Long = 2_000L) {
        val start = centre(from)
        val total = abs(slots) * stridePx + slopPx
        val move = (if (slots > 0f) -total else total) / 12f
        compose.touchDown(start)
        repeat(12) { compose.touchMove(move) }
        compose.touchRest()
        compose.touchUp()
        if (settleMs > 0L) compose.touchSettle(settleMs)
    }

    private fun pad(action: ControllerAction) {
        dispatchToHomeShelf(
            authority = authority,
            rows = rows,
            action = action,
            onLaunch = {},
            onOpenLibrary = {},
            onQuickAction = {},
            onExit = {},
        )
        compose.touchFrames(2)
    }

    @Test
    fun `a drag carries the strip one for one with the finger, and focus waits for the lift`() {
        mount()
        val carried = 0.3f * stridePx
        val before = centre(3).x
        val total = carried + slopPx

        compose.touchDown(centre(2))
        repeat(12) { compose.touchMove(-total / 12f) }

        assertEquals("a tile in the side seats travels with the finger", before - carried, centre(3).x, 3f)
        assertEquals("and the strip's position is where the finger put it", -0.3f, slot(0), 0.04f)
        assertEquals("focus has not moved", 0, focusedIndex())
        assertTrue("nothing was launched", launched.isEmpty())

        compose.touchRest()
        compose.touchUp()
    }

    @Test
    fun `a drag settles on a whole window start and focuses the item the pad's own rule leaves standing there`() {
        mount()

        drag(1.6f)

        assertEquals("the strip rests on the nearest window start", 2, restingWindowStart())
        val expected = homeShelfFocusForWindow(currentSeat = 0, windowStart = 2, itemCount = rows.size, visibleSeatCount = visibleSeats)
        assertEquals("focus is the item at the ring's seat, held inside the allowed seats", expected, focusedIndex())
        assertTrue(expected in homeShelfFocusRangeForWindow(2, rows.size, visibleSeats))
        assertTrue("a drag launches nothing", launched.isEmpty())
    }

    @Test
    fun `a drag short of the halfway point comes back to the window it began on`() {
        mount()

        drag(0.3f)

        assertEquals(0, restingWindowStart())
        assertEquals(0, focusedIndex())
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `a fling carries the strip on past the finger and lands on one window, with focus inside its seats`() {
        mount()

        compose.touchDown(centre(2))
        repeat(5) { compose.touchMove(-0.45f * stridePx) }
        compose.touchUp()
        compose.touchSettle(3_000L)

        val start = restingWindowStart()
        assertTrue("the fling must carry well beyond the finger's own two strides: $start", start >= 5)
        val focused = focusedIndex()
        assertTrue(
            "focus ($focused) stands in the seats the window $start allows",
            focused != null && focused in homeShelfFocusRangeForWindow(start, rows.size, visibleSeats),
        )
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `a tap is the tile's own as it was, so another game is focused and launched and the focused one is launched`() {
        mount()

        compose.touchTap(centre(2))
        compose.touchFrames(2)
        assertEquals("a tap on another game launches it", listOf(2), launched.toList())
        assertEquals("and focuses it", 2, focusedIndex())

        compose.touchSettle(2_000L)
        compose.touchTap(centre(2))
        compose.touchFrames(2)
        assertEquals("a tap on the focused game launches it again", listOf(2, 2), launched.toList())
    }

    @Test
    fun `a vertical drag is left to whatever scrolls above and does not move the strip`() {
        mount()

        compose.touchDown(centre(2))
        repeat(10) { compose.touchMove(0f, -8f) }
        compose.touchUp()

        assertTrue("the scroll above received the drag: $verticalTravel", verticalTravel < -40f)
        assertEquals("the strip has not moved", 0f, slot(0), 0.001f)
        assertEquals(0, focusedIndex())
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `the pad and the finger move one strip in turn, each from where the other left it`() {
        mount()

        drag(1.6f)
        val afterFinger = checkNotNull(focusedIndex())
        val fingerStart = restingWindowStart()

        pad(ControllerAction.NAV_RIGHT)
        compose.touchSettle(2_000L)
        assertEquals("the pad moved on from where the finger left it", afterFinger + 1, focusedIndex())
        val padStart = homeShelfWindowStart(fingerStart, afterFinger + 1, rows.size, visibleSeats)
        assertEquals("and the strip rests where the pad's rule puts it", padStart, restingWindowStart())

        drag(-1.6f, from = checkNotNull(focusedIndex()))
        val backStart = restingWindowStart()
        assertTrue("the finger took the strip back from where the pad left it: $backStart", backStart < padStart)
        val focused = checkNotNull(focusedIndex())
        assertTrue(focused in homeShelfFocusRangeForWindow(backStart, rows.size, visibleSeats))

        pad(ControllerAction.NAV_LEFT)
        compose.touchSettle(2_000L)
        assertEquals(focused - 1, focusedIndex())
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `fifty moves of a drag recompose the shelf at most twice`() {
        mount()
        val live = statusReads.reads
        assertTrue("the probe must be live, or this proves nothing", live > 0)

        compose.touchDown(centre(2))
        repeat(50) { compose.touchMove(-1f) }
        val during = statusReads.reads - live

        assertTrue("fifty moves recomposed the shelf $during times", during <= 2)
        // The control: carrying it on to the next window and lifting recomposes the shelf, which the probe must see.
        repeat(6) { compose.touchMove(-0.4f * stridePx) }
        compose.touchRest()
        compose.touchUp()
        compose.touchSettle(2_000L)
        assertTrue("the probe sees the recomposition a lift causes", statusReads.reads > live + during)
    }

    @Test
    fun `with motion on, the lift springs to its window over the next frames`() {
        mount()

        drag(1.6f, settleMs = 0L)

        assertTrue("a frame after the lift the strip is still on its way: ${slot(2)}", abs(slot(2)) > 0.02f)
        compose.touchSettle(2_000L)
        assertEquals(2, restingWindowStart())
    }

    @Test
    fun `with motion reduced the lift settles at once and focus follows`() {
        mount(reducedMotion = true)

        drag(1.6f, settleMs = 0L)
        compose.touchFrames(3)

        assertEquals("settled without a spring", 0f, slot(2), 0.01f)
        assertTrue(checkNotNull(focusedIndex()) in homeShelfFocusRangeForWindow(2, rows.size, visibleSeats))
        assertTrue(launched.isEmpty())
    }
}

/** A launch-status map that counts how often the shelf asks it for the selected game's platform, and never has an answer. */
private class StatusLineProbe : Map<PlatformId, String> by mapOf(PlatformId("__probe__") to "") {
    var reads = 0

    override fun get(key: PlatformId): String? {
        reads++
        return null
    }
}
