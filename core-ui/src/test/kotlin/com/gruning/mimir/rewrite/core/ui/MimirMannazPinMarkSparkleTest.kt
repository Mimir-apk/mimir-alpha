package com.gruning.mimir.rewrite.core.ui

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
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Marking a favorite gives its Mannaz rune the focused-game ring gleam -- one slow crossing, then the slow
 * four-star sparkle, both tuned across three device walks -- so the pin borrows that clock whole
 * ([rememberMimirFocusGameGleam]) and keys it on the MARK ([rememberMimirMannazPinMarkGleam]).
 *
 * These pin the trigger, the way [MimirFocusGameGlintLoopTest] pins the ring's clock: the seam is the same, a provider
 * hosted under a paused clock and read a frame at a time. The clock's own numbers, reduced motion and calm shine are
 * that test's and [MimirCalmShineTest]'s, not re-proved here.
 *
 * What these do NOT prove: how a 4-unit star and a 3-unit band read on a 24 dp pin. That is the device's to judge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirMannazPinMarkSparkleTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var glint: () -> Float
    private val present = mutableStateOf(false)

    private fun host(sparkleOnMark: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            glint = rememberMimirMannazPinMarkGleam(present = present.value, sparkleOnMark = sparkleOnMark)
        }
    }

    /** The favourite toggle as the pin sees it: the mark is written, and the next frame recomposes the pin with it. */
    private fun mark(on: Boolean) {
        present.value = on
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    private class Trace(val sweepStarts: List<Long>, val sparkleStarts: List<Long>, val everLit: Boolean)

    /**
     * Steps the clock a frame at a time for [ms]; returns when (from the call) each crossing and each sparkle began,
     * and whether any light showed at all.
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
    fun `a starred game scrolling into view is still, because the composition a pin is born with is not a mark`() {
        present.value = true
        host()
        val trace = traceOver(3_000L)
        assertFalse("no light: ${trace.sweepStarts} ${trace.sparkleStarts}", trace.everLit)
        assertEquals(MimirFocusRingGlintIdle, glint(), 0f)
    }

    @Test
    fun `marking a game plays the game's gleam once, settle then one slow crossing then the slow sparkle then still`() {
        host()
        mark(true)
        // The ring's own arithmetic (MimirFocusGameGlintLoopTest): 160 + 1000 + 1500, plus a few frames of seam.
        val ends = MimirFocusSettleDelayMs + MimirFocusGameGlintMs + MimirFocusFinalSparkleMs
        val trace = traceOver(ends + FrameMs * SeamFrames)
        assertEquals("one crossing: ${trace.sweepStarts}", 1, trace.sweepStarts.size)
        assertEquals("one sparkle: ${trace.sparkleStarts}", 1, trace.sparkleStarts.size)
        assertTrue("it waits to settle: ${trace.sweepStarts}", trace.sweepStarts[0] >= MimirFocusSettleDelayMs - FrameMs)
        assertTrue(
            "the plate is fully present (${MimirMotion.FocusMs} ms) before the light starts: ${trace.sweepStarts}",
            trace.sweepStarts[0] >= MimirMotion.FocusMs,
        )
        val gap = trace.sparkleStarts[0] - trace.sweepStarts[0]
        assertTrue(
            "the sparkle follows its one-second crossing: $gap",
            gap in MimirFocusGameGlintMs.toLong()..MimirFocusGameGlintMs + FrameMs * 4,
        )
        assertEquals("still by about $ends ms", MimirFocusRingGlintIdle, glint(), 0f)
        assertFalse("and it stays still", traceOver(3_000L).everLit)
    }

    @Test
    fun `un-marking mid-crossing stops the light at once`() {
        host()
        mark(true)
        val into = traceOver(MimirFocusSettleDelayMs + MimirFocusGameGlintMs / 2)
        assertEquals("into the crossing: ${into.sweepStarts}", 1, into.sweepStarts.size)
        assertTrue("mid-crossing", glint() < MimirFocusRingGlintIdle)
        mark(false)
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the light leaves with the mark", MimirFocusRingGlintIdle, glint(), 0f)
        assertFalse("and nothing follows", traceOver(3_000L).everLit)
    }

    @Test
    fun `a pin that is not a game's mark never lights, whatever its mark does`() {
        host(sparkleOnMark = false)
        mark(true)
        assertFalse(traceOver(3_000L).everLit)
        mark(false)
        mark(true)
        assertFalse(traceOver(3_000L).everLit)
        assertEquals(MimirFocusRingGlintIdle, glint(), 0f)
    }

    @Test
    fun `a game marked again sparkles again, because each mark is its own epoch`() {
        host()
        mark(true)
        assertEquals(1, traceOver(3_000L).sparkleStarts.size)
        mark(false)
        assertFalse(traceOver(500L).everLit)
        mark(true)
        val again = traceOver(3_000L)
        assertEquals("the second mark's crossing: ${again.sweepStarts}", 1, again.sweepStarts.size)
        assertEquals("the second mark's sparkle: ${again.sparkleStarts}", 1, again.sparkleStarts.size)
    }

    @Test
    fun `the pin itself composes under the theme and carries a mark through the whole gleam`() {
        // The real component, not the seam: proves the wiring composes, re-keys on a mark and runs the clock out
        // under a paused frame clock. It does not capture pixels, so it does not prove what the draw pass painted.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme { MimirMannazPin(present = present.value, sparkleOnMark = true) }
        }
        compose.mainClock.advanceTimeByFrame()
        mark(true)
        repeat(200) { compose.mainClock.advanceTimeByFrame() }
        mark(false)
        repeat(20) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun `the pin reads the mark gleam in the draw phase only, and keeps no clock of its own`() {
        val source = pinSource()
        assertEquals("one read of the provider", 1, Regex(Regex.escape("markGlint()")).findAll(source).count())
        val draw = source.sliceBetween("onDrawBehind {", ".clearAndSetSemantics")
        assertTrue("read in the draw lambda", draw.contains("val glint = markGlint()"))
        val cache = source.sliceBetween(".drawWithCache {", "onDrawBehind {")
        assertFalse(
            "a read in the cache block would rebuild every brush on every frame of the gleam",
            cache.contains("markGlint()"),
        )
        assertTrue(
            "the gleam is the game's clock, keyed on the mark epoch",
            source.contains("rememberMimirFocusGameGleam(settledOn = if (sparkleOnMark) mark.epoch else null)"),
        )
        // The presence tween is the pin's one clock; the gleam's numbers are the ring's, never re-typed here.
        assertEquals("one tween: the presence", 1, Regex("tween\\(").findAll(source).count())
        assertEquals("one Animatable: the presence", 1, Regex("Animatable\\(").findAll(source).count())
        assertFalse(source.contains("delay("))
        for (banned in listOf("focusable(", "focusTarget(", "FocusRequester")) {
            assertFalse("focus is the FocusAuthority's, never Compose's: $banned", source.contains(banned))
        }
    }

    @Test
    fun `the gleam's colours are the rune's gold under the ring's lifts, never a hex of their own`() {
        val source = pinSource()
        assertFalse(source.contains("Color(0x"))
        assertFalse(source.contains("0xFF"))
        assertTrue(source.contains("lerp(ink, Color.White, MimirFocusRingGlintLift)"))
        assertTrue(source.contains("lerp(ink, Color.White, MimirFocusSparkleLift)"))
        assertTrue("the stars are the ring's, drawn by the ring's helper", source.contains("drawMimirFocusSparkleStars("))
        assertTrue("the band moves as the ring's does", source.contains("mimirFocusGlintShiftPx("))
    }

    @Test
    fun `every star stays inside the plate, on the rune's four tips`() {
        // The pin sits on clipped card art, so a star past the plate's edge would be cut. The halo is smaller than
        // the arm (0.55 of it), so the arm bounds both.
        val arm = MimirMannazPinSparkleArmUnits
        assertTrue("top tips", MimirMannazRuneTopUnits - arm >= 0f)
        assertTrue("bottom tips", MimirMannazRuneBottomUnits + arm <= MimirMannazPinHeightDp)
        val centre = MimirMannazPinWidthDp / 2f
        assertTrue("left tips", centre - MimirMannazRuneHalfWidthUnits - arm >= 0f)
        assertTrue("right tips", centre + MimirMannazRuneHalfWidthUnits + arm <= MimirMannazPinWidthDp)
        // 4 dp at the authored height. Only the device says whether that reads.
        assertEquals(4f, arm * MimirMannazPinHeightDp / 24f, 0f)
    }

    @Test
    fun `the game marks sparkle, and the Tome's pinned mark keeps the default`() {
        val root = repoRoot()
        assertTrue("off by default", pinSource().contains("sparkleOnMark: Boolean = false,"))
        val marker = File(root, "feature-library/src/main/kotlin/$Library/LibraryFavoriteMarker.kt").readText()
        assertTrue(
            "the Library's marker (carousel cards and list rows) is a game's mark",
            marker.contains("MimirMannazPin(present = favorite, modifier = modifier, sparkleOnMark = true)"),
        )
        val onboarding = File(root, "app/src/main/kotlin/$App/OnboardingFlow.kt").readText()
        assertTrue(
            "onboarding's favourites cell is a game's mark",
            onboarding.window("present = row.isFavorite,").contains("sparkleOnMark = true,"),
        )
        val tome = File(root, "app/src/main/kotlin/$App/HomeTomeLibraryOverlay.kt").readText()
        assertFalse("the Tome's pinned mark is not a game; mark gleam applies to games only", tome.contains("sparkleOnMark"))
        val hint = File(root, "app/src/main/kotlin/$App/OnboardingHintCarousel.kt").readText()
        assertFalse("the hint demo's constant pin has nothing to mark", hint.contains("sparkleOnMark"))
    }

    /**
     * A window after [anchor], wide enough for one call's argument list with its comments (onboarding's runs to
     * about 700 characters); fails loudly on a missing anchor.
     */
    private fun String.window(anchor: String): String {
        val at = indexOf(anchor)
        assertTrue("anchor missing: $anchor", at >= 0)
        return substring(at, minOf(at + 1_000, length))
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun pinSource(): String =
        File(repoRoot(), "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirMannazPin.kt").readText()

    /** Deliberately fails rather than skipping when the tree is unreachable: a gate that assumes itself away reads green. */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "core-ui").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repository root not found from ${File(".").absolutePath}")
    }

    private companion object {
        const val FrameMs = 16L
        /** Frames of seam between the clock's phases; the ring's loop test allows four per seam. */
        const val SeamFrames = 10L
        const val Library = "com/gruning/mimir/rewrite/feature/library"
        const val App = "com/gruning/mimir/rewrite"
    }
}
