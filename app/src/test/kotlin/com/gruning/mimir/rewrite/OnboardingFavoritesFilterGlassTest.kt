package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RC-27 / RC-05 (audit 2026-10-14, O-27 / O-27e): the Favorites step's glass under the platform list. Robolectric cannot draw a blur,
 * so this pins the PROGRESS timeline the draw block reads, and that reading it recomposes nothing:
 *
 *  - opening ramps 0 to 1 on the overlay clock, never decreasing;
 *  - closing HOLDS at full strength for the dissolve (08-15: a modal dissolves fully before the glass under it clears), and only
 *    then clears: no frame has the glass below 1 while the list, fading on the same clock, is still more than 10% visible;
 *  - the page that wears the glass is composed a fixed number of times whatever the glass does: the progress is a lambda read in
 *    draw, not a State read in composition (the old `by animateFloatAsState` recomposed the whole step on every frame of the ramp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingFavoritesFilterGlassTest {

    @get:Rule
    val compose = createComposeRule()

    private val open = mutableStateOf(false)
    private var compositions = 0
    private lateinit var glass: () -> Float

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            glass = rememberOnboardingFavoritesFilterGlass(open = open.value)
            SideEffect { compositions++ }
            Box(Modifier.fillMaxSize())
        }
        step(0L)
    }

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** Glass progress after each 16 ms frame, from the moment [open] flips, for [frames] frames. */
    private fun record(frames: Int): List<Float> {
        val samples = mutableListOf<Float>()
        repeat(frames) {
            step(16L)
            samples += glass()
        }
        return samples
    }

    @Test
    fun `opening ramps up on the overlay clock and never steps back`() {
        mount()
        assertEquals(0f, glass(), 0f)
        Snapshot.withMutableSnapshot { open.value = true }
        val samples = record(16)
        assertTrue("it never decreases: $samples", samples.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("it is mid-ramp at 100 ms: ${samples[5]}", samples[5] in 0.05f..0.95f)
        assertEquals("and full once the clock has run", 1f, samples.last(), 0f)
    }

    @Test
    fun `closing holds the glass for the list's dissolve and only then clears it`() {
        mount()
        Snapshot.withMutableSnapshot { open.value = true }
        record(16)
        assertEquals(1f, glass(), 0f)

        Snapshot.withMutableSnapshot { open.value = false }
        var elapsed = 0
        val frames = mutableListOf<Pair<Int, Float>>()
        repeat(24) {
            step(16L)
            elapsed += 16
            frames += elapsed to glass()
        }
        frames.filter { it.first < MimirMotion.OverlayDissolveCoverMs }.forEach { (ms, g) ->
            assertEquals("the glass holds at full strength at $ms ms", 1f, g, 0f)
        }
        // The list fades out on the same house easing over OverlayMs. No frame has the glass below 1 while the list is still more than
        // 10% visible: the list is gone first, the glass clears under a bare scene.
        frames.filter { it.second < 1f }.forEach { (ms, _) ->
            val listAlpha = 1f - MimirMotion.Easing.transform((ms.toFloat() / MimirMotion.OverlayMs).coerceIn(0f, 1f))
            assertTrue("at $ms ms the glass has begun to clear while the list is still ${(listAlpha * 100).toInt()}% visible", listAlpha <= 0.1f)
        }
        assertTrue("the clear begins", frames.any { it.second < 1f })
        assertEquals("and finishes", 0f, frames.last().second, 0f)
        assertTrue("it never rises while clearing", frames.map { it.second }.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun `reading the glass recomposes nothing, however many frames it runs`() {
        mount()
        val before = compositions
        Snapshot.withMutableSnapshot { open.value = true }
        step(16L)
        val flipped = compositions
        assertEquals("the flag flips once: one recomposition", before + 1, flipped)
        record(14)
        assertEquals("the ramp's frames recompose nothing", flipped, compositions)
        Snapshot.withMutableSnapshot { open.value = false }
        step(16L)
        val closed = compositions
        record(24)
        assertEquals("nor does the hold and the clear", closed, compositions)
    }

    @Test
    fun `the step reads it in a draw block and holds no animated glass state of its own`() {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
        ).first(File::isFile).readText()
        val router = source.substringAfter("private fun OnboardingFavoritesScreen(").substringBefore("\n}\n")
        assertTrue(router.contains("val filterGlass = rememberOnboardingFavoritesFilterGlass(open = filter.filterOpen)"))
        assertTrue(router.contains("mimirGlassBackdropProgress(filterGlass)"))
        assertFalse("the Float overload recomposed the page per frame", router.contains("mimirGlassBackdrop(filterGlass)"))
        assertFalse(router.contains("filterGlass by animateFloatAsState"))
        val timeline = File("src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFavoritesGlass.kt").let { if (it.isFile) it else File("app/src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFavoritesGlass.kt") }.readText()
        assertTrue("the close holds before it clears", timeline.indexOf("mimirHold(MimirMotion.OverlayDissolveCoverMs)") in 0 until timeline.indexOf("glass.animateTo(0f"))
    }
}
