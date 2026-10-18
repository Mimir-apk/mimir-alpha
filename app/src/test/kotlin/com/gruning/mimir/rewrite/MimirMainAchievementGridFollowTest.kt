package com.gruning.mimir.rewrite

import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind how Main's badge grid follows the pad ([FollowLitBadge]): what to reveal, and the glide that chases it. Plain functions, so a held or fast-repeated move
 * can be played out frame by frame without a screen: the glide only ever moves toward the target, never past it, and a target that moves mid-glide is a retarget, not a restart.
 */
class MimirMainAchievementGridFollowTest {

    private val viewport = 100f..400f
    private val air = 20f

    private fun delta(rowTop: Float, rowHeight: Float = 50f) = mainGridRevealDelta(rowTop, rowHeight, viewport.start, viewport.endInclusive, air)

    @Test
    fun `a row that shows whole with its air is left where it is`() {
        assertEquals("well inside", 0f, delta(rowTop = 200f), 0f)
        assertEquals("flush with the top air (its air starts at the viewport's start)", 0f, delta(rowTop = 120f), 0f)
        assertEquals("flush with the bottom air (its air ends at the viewport's end)", 0f, delta(rowTop = 330f), 0f)
    }

    @Test
    fun `a row below the viewport scrolls forward just far enough to show with its air, and one above scrolls back the same way`() {
        assertEquals("its bottom air reaches 25 px past the end", 25f, delta(rowTop = 355f), 0.001f)
        assertEquals("a row wholly below: its bottom air ends at 770, the viewport at 400", 370f, delta(rowTop = 700f), 0.001f)
        assertEquals("its top air reaches 15 px before the start", -15f, delta(rowTop = 105f), 0.001f)
        assertEquals("a row wholly above: its top air starts at -120, the viewport at 100", -220f, delta(rowTop = -100f), 0.001f)
    }

    @Test
    fun `a row too tall for the viewport with its air is aligned by its start`() {
        assertEquals("its air starts at 480, the viewport at 100", 380f, mainGridRevealDelta(rowTop = 500f, rowHeight = 400f, viewportStart = 100f, viewportEnd = 400f, outset = 20f), 0.001f)
    }

    @Test
    fun `a glide step goes toward the target, is never more than what is left and is never nothing`() {
        listOf(300f, 57f, 3f, 0.7f).forEach { remaining ->
            val forward = mainGridGlideStep(remaining, 16f)
            val back = mainGridGlideStep(-remaining, 16f)
            assertTrue("forward, toward: $forward", forward > 0f)
            assertEquals("the same back", -forward, back, 0f)
            assertTrue("never more than what remains: $forward vs $remaining", forward <= remaining)
            assertTrue("at least a pixel unless less than a pixel is left: $forward", forward >= minOf(MAIN_GRID_GLIDE_MIN_STEP_PX, remaining))
        }
        assertEquals(0f, mainGridGlideStep(0f, 16f), 0f)
    }

    @Test
    fun `a longer frame moves further but a lunge is bounded by what is left`() {
        val short = mainGridGlideStep(100f, 16f)
        val long = mainGridGlideStep(100f, MAIN_GRID_GLIDE_MAX_FRAME_MS)
        assertTrue("a longer frame moves further: $short vs $long", long > short)
        assertTrue("and still short of the target: $long", long < 100f)
    }

    /** Plays the glide frame by frame at 16 ms against a target that may move; returns the position after each frame. */
    private fun play(startAt: Float, targets: (frame: Int) -> Float, frames: Int): List<Float> {
        var at = startAt
        val out = mutableListOf(at)
        repeat(frames) { frame ->
            val remaining = targets(frame) - at
            if (abs(remaining) > MAIN_GRID_GLIDE_SETTLE_PX) at += mainGridGlideStep(remaining, 16f)
            out += at
        }
        return out
    }

    @Test
    fun `one row's reveal arrives in about a quarter of a second and ends on the target`() {
        val path = play(0f, { 57f }, 60)
        assertEquals("on the target", 57f, path.last(), MAIN_GRID_GLIDE_SETTLE_PX)
        val inFifteenFrames = path[15]
        assertTrue("most of it is done within about a quarter of a second: $inFifteenFrames", inFifteenFrames > 57f * 0.9f)
        assertTrue("it never passes the target", path.all { it <= 57f + 0.001f })
        assertTrue("and only ever advances", path.zipWithNext().all { (before, after) -> after >= before })
    }

    @Test
    fun `a target that moves a row ahead every four frames is chased smoothly, forward only, with no lurch and no overshoot`() {
        val row = 57f
        // Ten moves, one every four frames (about 64 ms), then the glide runs out.
        val targetAt = { frame: Int -> row * (frame / 4 + 1).coerceAtMost(10) }
        val path = play(0f, targetAt, 120)
        val steps = path.zipWithNext { before, after -> after - before }
        assertTrue("only ever forward: ${steps.min()}", steps.min() >= 0f)
        assertTrue("no frame moves a whole row: ${steps.max()}", steps.max() < row)
        assertEquals("ends on the last target, not past it", row * 10, path.last(), MAIN_GRID_GLIDE_SETTLE_PX)
        assertTrue("never beyond the last target", path.max() <= row * 10 + 0.001f)
    }

    @Test
    fun `a target that turns back is followed back without crossing it`() {
        val path = play(0f, { frame -> if (frame < 10) 200f else 80f }, 80)
        val peak = path.max()
        assertTrue("it went toward 200 first: $peak", peak > 40f)
        assertTrue("then came back and stopped on 80", abs(path.last() - 80f) <= MAIN_GRID_GLIDE_SETTLE_PX)
        assertTrue("the peak is short of the first target", peak < 200f)
        val afterTurn = path.drop(10)
        assertTrue("past the turn it never goes up again: ${afterTurn.zipWithNext { a, b -> b - a }.max()}", afterTurn.zipWithNext().all { (before, after) -> after <= before + 0.001f })
        assertTrue("and never crosses below the new target: ${afterTurn.min()}", afterTurn.min() >= 80f - MAIN_GRID_GLIDE_SETTLE_PX)
    }

    @Test
    fun `a frame's step is the slow glide while the lit tile is whole, and the faster glide - never a one-frame catch-up - while it is outside`() {
        val air = 20f
        assertEquals("15 px to go is inside the air: the tile is whole, the glide alone", mainGridGlideStep(15f, 16f), mainGridFrameStep(15f, 16f, air), 0f)
        val fast = mainGridFrameStep(77f, 16f, air)
        assertEquals("77 px to go leaves the tile 57 px past the edge: the faster glide's share, not the 57 px that would bring it to the edge", 77f * (1f - exp(-16f / MAIN_GRID_GLIDE_FAST_TAU_MS)), fast, 0.01f)
        assertTrue("faster than the slow glide: $fast vs ${mainGridGlideStep(77f, 16f)}", fast > mainGridGlideStep(77f, 16f))
        assertTrue("and well short of the catch-up: $fast", fast < 57f && fast < 0.45f * 77f)
        assertEquals("the same back", -fast, mainGridFrameStep(-77f, 16f, air), 0f)
        assertEquals("a row of the Thor (149 px) from showing: under 80 px in a 16 ms frame and 40 px in an 8 ms one", 61.6f, mainGridFrameStep(149f, 16f, 52.5f), 0.1f)
        assertTrue("under 40 px at 8 ms: ${mainGridFrameStep(149f, 8f, 52.5f)}", mainGridFrameStep(149f, 8f, 52.5f) < 40f)
        assertTrue("never more than what remains", abs(mainGridFrameStep(30f, 16f, 0f)) <= 30f + 0.001f)
        assertTrue("not even with no air at all", abs(mainGridFrameStep(30f, 16f, -5f)) <= 30f + 0.001f)
        assertEquals(0f, mainGridFrameStep(0f, 16f, air), 0f)
    }

    @Test
    fun `the frame clock measures between frames, keeps the measure across a retarget and never sizes a step by the idle gap`() = runBlocking {
        var now = 0L
        val clock = MainGridFrameClock { now }
        now += 8_000_000L
        assertEquals("the first frame is not a measurement: the nominal length", MAIN_GRID_GLIDE_NOMINAL_FRAME_MS, clock.nextFrameMs(), 0f)
        now += 8_000_000L
        assertEquals(8f, clock.nextFrameMs(), 0.001f)
        now += 9_000_000L
        assertEquals(9f, clock.nextFrameMs(), 0.001f)
        clock.rest()
        now += 500_000_000L
        assertEquals("after a rest the idle gap is not a frame: the last measure stands", 9f, clock.nextFrameMs(), 0.001f)
        now += 8_000_000L
        assertEquals(8f, clock.nextFrameMs(), 0.001f)
        now += 300_000_000L
        assertEquals("a hitch that long is not a frame either", 8f, clock.nextFrameMs(), 0.001f)
    }

    /** A grid as the follower sees it: rows [rowH] px tall, [gap] apart, in a viewport [viewportH] tall, scrolled by [scroll], with the lit row at [litRow]. */
    private class GridModel(val viewportH: Float, val rowH: Float, val gap: Float, val air: Float) {
        var scroll = 0f
        var litRow = 0
        fun delta(): Float = mainGridRevealDelta(litRow * (rowH + gap) - scroll, rowH, 0f, viewportH, air)
    }

    private class Move(val frame: Int, val deltaBefore: Float, val step: Float, val deltaAfter: Float, val firstOfGlide: Boolean)

    /**
     * Review round: a press used to relaunch the glide, which put the old glide's step and a fresh one into the same frame (and sized the new one by a guessed frame length). On a
     * 120 Hz display (8 ms frames) with a new lit row every 60 ms (the pad's pace): ONE glide serves the whole burst, it moves the grid at most once per frame and never before the
     * frame after it was woken, never backward, by no more than a single step (the glide's share of what was left at the frame, on the faster time constant while the lit tile is
     * outside the viewport), no step of more than 0.54 of a row (80 px of the Thor's 149), and the lit tile is back inside within five frames of each press, before the next one.
     */
    @Test
    fun `at 8 ms frames with a new lit row on every press one glide serves the burst, once per frame, forward only, within a single step, and the lit tile is back inside within five frames`() = runBlocking {
        val air = 20f
        val model = GridModel(viewportH = 300f, rowH = 49f, gap = 8f, air = air)
        model.litRow = 4
        var nowNanos = 0L
        var frame = 0
        val pressFrames = (0 until 10).map { 3 + (it * 7.5f).toInt() }.toSet()
        fun tick() {
            frame += 1
            nowNanos += 8_000_000L
            if (frame in pressFrames) model.litRow += 1
        }
        val moves = mutableListOf<Move>()
        val clock = MainGridFrameClock {
            tick()
            nowNanos
        }
        var glides = 0
        var wokeAt = -1
        while (frame < 150) {
            if (abs(model.delta()) <= MAIN_GRID_GLIDE_SETTLE_PX) {
                tick()
                continue
            }
            glides += 1
            if (wokeAt < 0) wokeAt = frame
            var first = true
            followLitBadge(
                clock = clock,
                revealDelta = { model.delta() },
                scrollBy = { pixels ->
                    val before = model.delta()
                    model.scroll += pixels
                    moves += Move(frame, before, pixels, model.delta(), first)
                    first = false
                    pixels
                },
                airPx = air,
                snap = { false },
            )
        }
        assertTrue("the burst scrolled the grid", moves.size > 20)
        assertEquals("one glide served the whole burst: a press retargets it", 1, glides)
        assertTrue("it waited for a frame before its first step: woke at $wokeAt, first step at ${moves.first().frame}", moves.first().frame > wokeAt)
        assertEquals("at most one move in a frame", moves.size, moves.map { it.frame }.toSet().size)
        assertTrue("forward only", moves.all { it.step > 0f })
        moves.forEach { move ->
            // The first step of a glide is sized by the nominal 16 ms until a frame has been measured; every other by the 8 ms frame.
            val frameMs = if (move.firstOfGlide) 16f else 8f
            val tauMs = if (move.deltaBefore > air) 30f else 70f
            val bound = minOf(move.deltaBefore, maxOf(move.deltaBefore * (1f - exp(-frameMs / tauMs)), 1f))
            assertTrue("frame ${move.frame}: a step of ${move.step} px is within the single-step bound $bound", move.step <= bound + 0.001f)
            assertTrue("frame ${move.frame}: a step of ${move.step} is no more than 0.54 of a row (${0.54f * 57f})", move.step <= 0.54f * 57f)
        }
        // Each press (a new lit row at its frame) has the lit tile back inside - the ring's air or less to go - within five frames, which is before the next press.
        pressFrames.sorted().forEach { press ->
            val back = moves.firstOrNull { it.frame >= press && it.deltaAfter <= air + 0.001f }
            assertTrue("the press at frame $press has the lit tile back inside within five frames: first at ${back?.frame}", back != null && back.frame - press <= 5)
        }
        assertTrue("and it ended on the lit row", abs(model.delta()) <= MAIN_GRID_GLIDE_SETTLE_PX)
    }

    @Test
    fun `under reduced motion the follower moves straight to the target in one move`() = runBlocking {
        val model = GridModel(viewportH = 300f, rowH = 49f, gap = 8f, air = 20f)
        model.litRow = 9
        var frames = 0
        var calls = 0
        followLitBadge(
            clock = MainGridFrameClock {
                frames += 1
                frames * 16_000_000L
            },
            revealDelta = { model.delta() },
            scrollBy = { pixels ->
                calls += 1
                model.scroll += pixels
                pixels
            },
            airPx = 20f,
            snap = { true },
        )
        assertEquals("one move", 1, calls)
        assertEquals("and no frame waited for", 0, frames)
        assertTrue("on the target", abs(model.delta()) <= MAIN_GRID_GLIDE_SETTLE_PX)
    }

    @Test
    fun `the grid has one scroller for the pad, and the follow keeps the repo's focus and colour rules`() {
        fun source(name: String): String =
            (File("src/main/kotlin/com/gruning/mimir/rewrite/$name").takeIf { it.isFile } ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name")).readText()
        val page = source("MimirMainAchievementPage.kt")
        val follow = source("MimirMainAchievementGridFollow.kt")
        assertFalse("no jump to a row on the page: the follow is the only scroller", page.contains("scrollToItem(") || page.contains("animateScrollToItem("))
        assertTrue("the tiles do not ask their scroll parent to reveal them as well", page.contains("revealOnFocus = false"))
        assertTrue("the grid is followed by the one scroller", page.contains("FollowLitBadge("))
        assertFalse("a press does not relaunch the glide (it retargets the one in flight)", follow.contains("collectLatest"))
        assertFalse("the follow jumps to nothing either", follow.contains("scrollToItem(") || follow.contains("animateScrollToItem(") || follow.contains("animateScrollBy("))
        listOf(page, follow).forEach { text ->
            assertFalse("never Compose focus", text.contains("focusable()") || text.contains("focusTarget()"))
            assertFalse("no hex colour literal", Regex("Color\\(0x[0-9A-Fa-f]+\\)").containsMatchIn(text))
        }
    }
}
