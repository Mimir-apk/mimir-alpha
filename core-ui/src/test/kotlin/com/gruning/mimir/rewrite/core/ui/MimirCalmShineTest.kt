package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Calm shine tones down gleam for sensitive players. Off keeps the prior appearance: three
 * game sweeps and sparkles
 * ([MimirFocusGameGlintLoopTest]), a button's or a platform tile's one plain sweep. On, a game's gleam is one crossing
 * ending in its sparkle, then still, and no other ring shines. The ring itself, its settle and its flare are not
 * touched by either; the only thing the switch reaches is how many times light crosses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimirCalmShineTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var glint: () -> Float
    private val settledOn = mutableStateOf<Any?>("game-a")
    private val calm = mutableStateOf(false)

    private fun hostGame(calmShine: Boolean) {
        calm.value = calmShine
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMimirCalmShine provides calm.value) {
                glint = rememberMimirFocusGameGleam(settledOn.value)
            }
        }
    }

    private fun hostPlain(calmShine: Boolean) {
        calm.value = calmShine
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMimirCalmShine provides calm.value) {
                glint = rememberMimirFocusSettleGlint(settledOn.value)
            }
        }
    }

    private class Trace(val sweepStarts: List<Long>, val sparkleStarts: List<Long>, val everLit: Boolean)

    /**
     * Steps the clock a frame at a time for [ms]; returns when (from the call) each sweep and each sparkle began, and
     * whether any light showed at all.
     */
    private fun traceOver(ms: Long): Trace {
        val sweeps = mutableListOf<Long>()
        val sparkles = mutableListOf<Long>()
        var everLit = false
        var was = glint()
        var t = 0L
        while (t < ms) {
            compose.mainClock.advanceTimeByFrame()
            t += FrameMs
            val now = glint()
            if (now != MimirFocusRingGlintIdle) everLit = true
            if (was >= MimirFocusRingGlintIdle && now < MimirFocusRingGlintIdle) sweeps += t
            if (was <= MimirFocusRingGlintIdle && now > MimirFocusRingGlintIdle) sparkles += t
            was = now
        }
        return Trace(sweeps, sparkles, everLit)
    }

    @Test
    fun `under calm shine a focused game gleams once, light crossing the ring and ending in its sparkle, then still`() {
        hostGame(calmShine = true)
        val trace = traceOver(7_000L)
        assertEquals("one sweep: ${trace.sweepStarts}", 1, trace.sweepStarts.size)
        assertEquals("one sparkle: ${trace.sparkleStarts}", 1, trace.sparkleStarts.size)
        assertTrue("it still waits for focus to settle", trace.sweepStarts[0] >= MimirFocusSettleDelayMs)
        val gap = trace.sparkleStarts[0] - trace.sweepStarts[0]
        assertTrue("the sparkle follows the one-second crossing: $gap", gap in 1_000L..1_000L + FrameMs * 4)
        assertEquals("still at the end", MimirFocusRingGlintIdle, glint(), 0f)
    }

    @Test
    fun `under calm shine a plain sweep never runs, so buttons, rows and platform tiles do not shine`() {
        hostPlain(calmShine = true)
        val trace = traceOver(6_000L)
        assertEquals(0, trace.sweepStarts.size)
        assertEquals(0, trace.sparkleStarts.size)
        assertFalse("no light ever showed", trace.everLit)
        assertEquals(MimirFocusRingGlintIdle, glint(), 0f)
    }

    @Test
    fun `with calm shine off a game still sweeps once and sparkles once, exactly as today`() {
        hostGame(calmShine = false)
        val trace = traceOver(7_000L)
        assertEquals("one sweep (walk-30): ${trace.sweepStarts}", 1, trace.sweepStarts.size)
        assertEquals("its sparkle: ${trace.sparkleStarts}", 1, trace.sparkleStarts.size)
        assertEquals(MimirFocusRingGlintIdle, glint(), 0f)
    }

    @Test
    fun `with calm shine off a plain sweep still crosses once with no sparkle, exactly as today`() {
        hostPlain(calmShine = false)
        val trace = traceOver(6_000L)
        assertEquals(1, trace.sweepStarts.size)
        assertEquals(0, trace.sparkleStarts.size)
        assertEquals(MimirFocusRingGlintIdle, glint(), 0f)
    }

    @Test
    fun `calm shine is off unless a host provides it`() {
        var read: Boolean? = null
        compose.setContent { read = LocalMimirCalmShine.current }
        assertEquals(false, read)
    }

    @Test
    fun `switched on mid-sweep, a plain sweep goes out at once and does not return`() {
        hostPlain(calmShine = false)
        assertEquals("the sweep is under way", 1, traceOver(400L).sweepStarts.size)
        assertTrue(glint() < MimirFocusRingGlintIdle)

        calm.value = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the light leaves with the flip", MimirFocusRingGlintIdle, glint(), 0f)

        val trace = traceOver(3_000L)
        assertEquals(0, trace.sweepStarts.size)
        assertFalse(trace.everLit)
    }

    @Test
    fun `switched on mid-gleam, a game's one crossing is already calm's and simply finishes`() {
        // A game crosses once, which is what calm leaves it, so the flip changes nothing mid-gleam.
        hostGame(calmShine = false)
        assertEquals("the game's sweep is under way", 1, traceOver(700L).sweepStarts.size)

        calm.value = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()

        val trace = traceOver(5_000L)
        assertEquals("no second crossing: ${trace.sweepStarts}", 0, trace.sweepStarts.size)
        assertEquals("the crossing ends in its sparkle", 1, trace.sparkleStarts.size)
        assertEquals(MimirFocusRingGlintIdle, glint(), 0f)
    }

    @Test
    fun `calm shine is a pass count and never a clock`() {
        // As it always was: at least one crossing, three for a game.
        assertEquals(1, mimirFocusGlintCrossings(passes = 1, calmPasses = 0, calm = false))
        assertEquals(1, mimirFocusGlintCrossings(passes = 0, calmPasses = 0, calm = false))
        assertEquals(3, mimirFocusGlintCrossings(passes = 3, calmPasses = 1, calm = false))
        // Under calm: what the caller asked calm to leave, which for a plain sweep is nothing.
        assertEquals(0, mimirFocusGlintCrossings(passes = 1, calmPasses = 0, calm = true))
        assertEquals(1, mimirFocusGlintCrossings(passes = 3, calmPasses = 1, calm = true))
        assertEquals(0, mimirFocusGlintCrossings(passes = 3, calmPasses = -1, calm = true))
        assertEquals(1, MimirFocusGameCalmGlintPasses)
        // The clocks calm leaves alone: the game's one crossing, its one second and its sparkle.
        assertEquals(1, MimirFocusGameGlintPasses)
        assertEquals(1_000, MimirFocusGameGlintMs)
        assertEquals(600, MimirFocusSparkleMs)
    }

    @Test
    fun `a default selection ring takes the plain sweep, which calm shine removes, and a game ring the game gleam`() {
        val ring = coreUiSource("MimirSelectionRing.kt")
        assertTrue(ring.contains("gleam: MimirRingGleam = MimirRingGleam.Sweep,"))
        val game = "gleam == MimirRingGleam.Game -> " +
            "rememberMimirFocusGameGleam(settledOn = settledOn, armed = armed)"
        assertTrue(ring.contains(game))
        assertTrue(ring.contains("else -> rememberMimirFocusSettleGlint(settledOn = settledOn, armed = armed)"))
        assertFalse("the ring never reads the switch itself", ring.contains("LocalMimirCalmShine.current"))
        val frame = coreUiSource("MediaArtFrame.kt")
        assertEquals("one read, where every gleam is made", 1, frame.split("LocalMimirCalmShine.current").size - 1)
        assertTrue(frame.contains("calmPasses = MimirFocusGameCalmGlintPasses,"))
        assertTrue(frame.contains("val LocalMimirCalmShine = compositionLocalOf { false }"))
    }

    private fun coreUiSource(name: String): String {
        val candidates = listOf(
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private companion object {
        const val FrameMs = 16L
    }
}
