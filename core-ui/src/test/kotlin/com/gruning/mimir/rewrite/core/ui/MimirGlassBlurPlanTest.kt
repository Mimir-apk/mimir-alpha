package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirGlassBlurPlanTest {
    @Test
    fun `zero progress has no blur`() {
        assertEquals(MimirGlassBlurPlan(0f, 0f), mimirGlassBlurPlan(0f, 20f))
    }

    @Test
    fun `full progress has the maximum radius and full alpha`() {
        assertEquals(MimirGlassBlurPlan(20f, 1f), mimirGlassBlurPlan(1f, 20f))
    }

    @Test
    fun `the blur is one radius, the peak, and the progress is its strength`() {
        // 2026-10-14 (Odin 120 Hz): the radius used to sweep 7 px to the peak at full alpha, so the card went from sharp to
        // featureless in three frames. Now the radius never moves and the strength follows the progress.
        assertEquals(MimirGlassBlurPlan(14f, 0.5f), mimirGlassBlurPlan(0.5f, 14f))
        assertEquals(MimirGlassBlurPlan(20f, 0.25f), mimirGlassBlurPlan(0.25f, 20f))
        assertEquals(MimirGlassBlurPlan(55f, 0.1f), mimirGlassBlurPlan(0.1f, 55f))
    }

    @Test
    fun `a strength under a tenth of a pixel of the peak is no blur at all`() {
        assertEquals(MimirGlassBlurPlan(0f, 0f), mimirGlassBlurPlan(0.004f, 20f))
        assertEquals(MimirGlassBlurPlan(20f, 0.006f), mimirGlassBlurPlan(0.006f, 20f))
    }

    @Test
    fun `progress outside the range is clamped`() {
        assertEquals(MimirGlassBlurPlan(0f, 0f), mimirGlassBlurPlan(-0.5f, 20f))
        assertEquals(MimirGlassBlurPlan(20f, 1f), mimirGlassBlurPlan(1.5f, 20f))
    }

    @Test
    fun `the soften takes the whole clock at 120 Hz, in both directions, with no frame a step`() {
        // Frame measurements on the Odin (2026-10-14): sharpness 45 to 6 in 3 frames opening and 6.7 to 21.1 in ONE frame closing,
        // because the strength saturated at a 7 px radius, 15 % of the way in, which the front-loaded house ease reaches in ~22 ms.
        val frameMs = 1000f / 120f
        val frames = (MimirMotion.OverlayMs / frameMs).toInt() + 1
        val opening = (0..frames).map { i ->
            val fraction = (i * frameMs / MimirMotion.OverlayMs).coerceIn(0f, 1f)
            mimirGlassBlurPlan(MimirMotion.Easing.transform(fraction), 50f).blurAlpha
        }
        val closing = (0..frames).map { i ->
            val fraction = (i * frameMs / MimirMotion.OverlayMs).coerceIn(0f, 1f)
            mimirGlassBlurPlan(1f - MimirMotion.Easing.transform(fraction), 50f).blurAlpha
        }
        for ((name, run) in listOf("opening" to opening, "closing" to closing)) {
            val steps = run.zipWithNext { a, b -> kotlin.math.abs(b - a) }
            assertTrue("$name: no frame is a step (largest ${steps.max()})", steps.max() <= 0.15f)
            val fullAt = run.indexOfFirst { if (name == "opening") it >= 0.9f else it <= 0.1f }
            assertTrue("$name: the visible change is still going at ${fullAt * frameMs} ms, not done inside three frames", fullAt >= 8)
        }
    }

    @Test
    fun `the companion lattice peak rides the same plan at its own radius`() {
        // 24 dp is 55 px on the Bottom at progress 1; a tenth of the way in is the same radius at a tenth of the
        // strength (the plan holds ONE radius, so HWUI never convolves a small one at full resolution, 20-45 ms a frame on the Bottom).
        assertEquals(MimirGlassBlurPlan(55f, 1f), mimirGlassBlurPlan(1f, 55f))
        val tail = mimirGlassBlurPlan(0.1f, 55f)
        assertEquals(55f, tail.radiusPx, 0f)
        assertEquals(0.1f, tail.blurAlpha, 1e-6f)
    }

    @Test
    fun `the progress-only overload keeps the house radius for every existing glass surface`() {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirGlassTransition.kt")
        assertTrue("MimirGlassTransition.kt must be readable from the module dir", file.isFile)
        assertTrue(
            file.readText().contains(
                "fun Modifier.mimirGlassBackdropProgress(progress: () -> Float): Modifier =\n" +
                    "    mimirGlassBackdropProgress(MimirGlass.backdropBlurRadius, progress)",
            ),
        )
    }
}
