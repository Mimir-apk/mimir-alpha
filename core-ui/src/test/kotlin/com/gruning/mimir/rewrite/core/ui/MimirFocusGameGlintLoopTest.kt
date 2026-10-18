package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A focused game's gleam ends at rest with the ring snapped (CP07). The sequence changed from three
 * 1 s sweeps, each ending in a silent sparkle, to one 1 s sweep ending in a slow 1.5 s sparkle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimirFocusGameGlintLoopTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var glint: () -> Float
    private val settledOn = mutableStateOf<Any?>("game-a")

    private fun hostGame() {
        compose.mainClock.autoAdvance = false
        compose.setContent { glint = rememberMimirFocusGameGleam(settledOn.value) }
    }

    private fun hostPlain() {
        compose.mainClock.autoAdvance = false
        compose.setContent { glint = rememberMimirFocusSettleGlint(settledOn.value) }
    }

    private class Trace(val sweepStarts: List<Long>, val sparkleStarts: List<Long>)

    /** Steps the clock a frame at a time for [ms]; returns when (from the call) each sweep and each sparkle began. */
    private fun traceOver(ms: Long): Trace {
        val sweeps = mutableListOf<Long>()
        val sparkles = mutableListOf<Long>()
        var was = glint()
        var t = 0L
        while (t < ms) {
            compose.mainClock.advanceTimeByFrame()
            t += FrameMs
            val now = glint()
            if (was >= MimirFocusRingGlintIdle && now < MimirFocusRingGlintIdle) sweeps += t
            if (was <= MimirFocusRingGlintIdle && now > MimirFocusRingGlintIdle) sparkles += t
            was = now
        }
        return Trace(sweeps, sparkles)
    }

    @Test
    fun `a game's gleam is one one-second sweep ending in the slow sparkle`() {
        assertEquals(1, MimirFocusGameGlintPasses)
        assertEquals(1_000, MimirFocusGameGlintMs)
        // The final sparkle lasts 1.5 s so it remains visible.
        assertEquals(600, MimirFocusSparkleMs)
        assertEquals(1_500, MimirFocusFinalSparkleMs)
        val lastSparkleEnds = MimirFocusSettleDelayMs + MimirFocusGameGlintPasses * MimirFocusGameGlintMs +
            (MimirFocusGameGlintPasses - 1) * MimirFocusSparkleMs + MimirFocusFinalSparkleMs
        assertEquals(2_660L, lastSparkleEnds)
    }

    @Test
    fun `a focused game sweeps once, sparkles once, then stays still`() {
        hostGame()
        val trace = traceOver(7_000L)
        assertEquals("one sweep: ${trace.sweepStarts}", 1, trace.sweepStarts.size)
        assertEquals("one sparkle: ${trace.sparkleStarts}", 1, trace.sparkleStarts.size)
        assertTrue("it waits for focus to settle", trace.sweepStarts[0] >= MimirFocusSettleDelayMs)
        // The sparkle starts on the frame after the sweep ends, so the gap carries a few frames of seam.
        val gap = trace.sparkleStarts[0] - trace.sweepStarts[0]
        assertTrue("the sparkle follows its one-second sweep: $gap", gap in 1_000L..1_000L + FrameMs * 4)
        assertEquals("still at the end", MimirFocusRingGlintIdle, glint(), 0f)
    }

    @Test
    fun `buttons and platform tiles keep one plain sweep with no sparkle`() {
        hostPlain()
        val trace = traceOver(6_000L)
        assertEquals(1, trace.sweepStarts.size)
        assertEquals(0, trace.sparkleStarts.size)
        assertEquals(MimirFocusRingGlintIdle, glint(), 0f)
    }

    @Test
    fun `moving on stops the gleam at once and the next game gets its own`() {
        hostGame()
        assertEquals("into the first game's sparkle", 1, traceOver(1_500L).sparkleStarts.size)

        settledOn.value = "game-b"
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the light leaves with the old game", MimirFocusRingGlintIdle, glint(), 0f)

        val trace = traceOver(7_000L)
        assertEquals("the next game's own: ${trace.sweepStarts}", 1, trace.sweepStarts.size)
        assertEquals(1, trace.sparkleStarts.size)
        assertTrue("and it too waits to settle", trace.sweepStarts[0] >= MimirFocusSettleDelayMs - FrameMs)
    }

    @Test
    fun `nothing selected never gleams`() {
        settledOn.value = null
        hostGame()
        val trace = traceOver(5_000L)
        assertEquals(0, trace.sweepStarts.size)
        assertEquals(0, trace.sparkleStarts.size)
    }

    @Test
    fun `every star has its turn, and all are gone before and after the sparkle`() {
        repeat(MimirFocusSparkleStars) { star ->
            assertEquals("star $star before", 0f, mimirFocusSparkleStarScale(0f, star), 0f)
            assertEquals("star $star after", 0f, mimirFocusSparkleStarScale(1f, star), 0f)
            val peak = (0..100).maxOf { mimirFocusSparkleStarScale(it / 100f, star) }
            assertTrue("star $star shines: $peak", peak > 0.95f && peak <= 1f)
        }
        // The first star is the corner the sweep leaves from; the last starts only once the first is past its peak.
        assertTrue(mimirFocusSparkleStarScale(0.1f, 0) > 0f)
        assertEquals(0f, mimirFocusSparkleStarScale(0.1f, MimirFocusSparkleStars - 1), 0f)
        // A star's arm stays inside the ring's 14 dp reach: its centre sits on the middle stroke (6 dp out).
        assertTrue(MimirFocusSparkleArm <= 8.dp)
    }

    private fun hostParkable(live: androidx.compose.runtime.State<Boolean>) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalMimirMotionLive provides live) {
                glint = rememberMimirFocusGameGleam(settledOn.value)
            }
        }
    }

    /** D24: a gleam already under way is cut to its end (idle) the moment the pane it is on parks, and draws nothing more. */
    @Test
    fun `a gleam under way snaps to its end when its pane parks`() {
        val live = mutableStateOf(true)
        hostParkable(live)
        var t = 0L
        while (glint() == MimirFocusRingGlintIdle && t < 3_000L) {
            compose.mainClock.advanceTimeByFrame()
            t += FrameMs
        }
        assertTrue("fixture: the gleam is under way at $t ms: ${glint()}", glint() != MimirFocusRingGlintIdle)

        live.value = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("cut to its end", MimirFocusRingGlintIdle, glint(), 0f)
        repeat(300) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals("and nothing moves again", MimirFocusRingGlintIdle, glint(), 0f)
        }
    }

    /** D24: a pane parked before the settle delay is up starts no gleam, and leaves no timer behind to start one. */
    @Test
    fun `a pane parked from the start never gleams`() {
        val live = mutableStateOf(false)
        hostParkable(live)
        repeat(300) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals(MimirFocusRingGlintIdle, glint(), 0f)
        }
    }

    private companion object {
        const val FrameMs = 16L
    }
}
