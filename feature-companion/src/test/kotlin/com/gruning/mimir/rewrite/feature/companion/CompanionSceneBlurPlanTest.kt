package com.gruning.mimir.rewrite.feature.companion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level blur-plan pin: the companion scene blur and the tome pager blur ride core-ui's
 * `mimirGlassBlurPlan` (through `Modifier.mimirGlassBackdropProgress`) at the lattice's own 24 dp peak, instead of a
 * raw `BlurEffect` of 24 dp x progress, which spent the tail of every ramp under
 * `MimirGlass.BlurDownsampledMinRadiusPx` -- where HWUI convolves at full resolution, 20-45 ms a frame on the Bottom.
 *
 * **Why a source test.** Nothing JVM-reachable can observe "this layer is blurred at this radius" -- the same reason
 * `SelectOverlayBackdropGlassTest` (feature-library) is written this way. The plan's own arithmetic is pinned in
 * core-ui's `MimirGlassBlurPlanTest`. Whether the tail now runs clean on the Thor is device-only.
 */
class CompanionSceneBlurPlanTest {

    private val source: String by lazy {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt")
        assertTrue("CompanionSurface.kt must be readable from the module dir (${file.absolutePath})", file.isFile)
        file.readText()
    }

    @Test
    fun theSceneBlurRidesThePlanAtTheLatticePeakOnTheOverlayClock() {
        // Q5 : the scene under the overlay is a snapshot while the glass is at its peak, core-ui's one freeze rule.
        assertEquals(1, occurrences(".mimirGlassBackdropProgress({ CompanionTomeBackdropBlurDp.dp }, { overlayBlur }, snapshot = { true })"))
    }

    /** RC-03 (audit 2026-10-14): the Tome's blur rides the plan on the Tome's own held glass clock, split from the page's `reveal`. */
    @Test
    fun theTomePagerBlurRidesThePlanAtTheLatticePeakOnTheTomesHeldGlassClock() {
        assertEquals(1, occurrences(".mimirGlassBackdropProgress(CompanionTomeBackdropBlurDp.dp) { tomeGlass() }"))
        assertEquals("the page's reveal no longer drives the blur", 0, occurrences(".mimirGlassBackdropProgress(CompanionTomeBackdropBlurDp.dp) { reveal }"))
    }

    @Test
    fun noRawBlurEffectIsLeftToRampThroughTheSmallRadiusRegime() {
        assertFalse("a raw BlurEffect ramp is back in CompanionSurface.kt", source.contains("BlurEffect("))
    }

    @Test
    fun thePeakIsStillTheRadiusCostedOnThisPanel() {
        assertEquals(24f, CompanionTomeBackdropBlurDp, 0f)
    }

    private fun occurrences(needle: String): Int = source.split(needle).size - 1
}
