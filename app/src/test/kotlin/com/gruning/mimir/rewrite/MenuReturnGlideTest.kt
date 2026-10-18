package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.mimirOverlayArriveSpec
import com.gruning.mimir.rewrite.core.ui.mimirOverlayLeaveSpec
import com.gruning.mimir.rewrite.core.ui.mimirSwapTravelSpec
import java.io.File
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Returns glide the page returned to back into place on the return clock, the swap's resize never
 * snaps, the Tome editor rises like Customize media's pickers, Quick Launch arrives on the page tempo, and Get art's
 * steps swap instead of fading at once.
 */
class MenuReturnGlideTest {

    private fun source(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `a return's glide and resize ride the return clock, a forward swap's the arrival`() {
        val back = mimirSwapTravelSpec<Float>(reducedMotion = false, returning = true) as TweenSpec<Float>
        assertEquals(MimirPageMotion.ReturnMs, back.durationMillis)
        assertEquals("no lead on a return: no blank after B", 0, back.delay)
        val forward = mimirSwapTravelSpec<Float>(reducedMotion = false, returning = false) as TweenSpec<Float>
        assertTrue(forward.delay > 0)
        val settled = mimirSwapTravelSpec<Float>(reducedMotion = true, returning = true) as TweenSpec<Float>
        assertEquals("reduced motion stays a settled state", 0, settled.durationMillis)
    }

    @Test
    fun `every swap host that returns glides back`() {
        assertTrue(source("HomeTileMenuOverlay.kt").contains("slideInVertically(enterEasing) { if (returning) -surfaceGlidePx else surfaceGlidePx }"))
        assertTrue(source("HomeTileMenuOverlay.kt").contains("returnTravelPx = surfaceGlidePx,"))
        assertTrue(source("BrightnessOverlay.kt").contains("returnTravelPx = swapGlidePx,"))
        val tome = source("HomeTomeLibraryOverlay.kt")
        assertTrue(tome.contains("returnTravelPx = tomeSwapGlidePx,") && tome.contains("enterTravelPx = tomeSwapGlidePx,"))
    }

    @Test
    fun `Quick Launch arrives on the page tier, lead included, and Get art's steps swap`() {
        // The previous source pin required `fadeIn(tween(MimirPageMotion.ArriveMs, ...))`,
        // the page tempo with no lead, which stood the dock at 6% at 40 ms with a slot already focused. It takes the 130 ms
        // lead now, and the same spec function feeds the painting and the gate's probe.
        val enter = homeQuickLaunchEnterSpec(reducedMotion = false) as TweenSpec<Float>
        assertEquals(MimirPageMotion.ArriveMs, enter.durationMillis)
        assertEquals("the page tier's lead", MimirPageMotion.LeadMs, enter.delay)
        assertEquals(MimirMotion.Easing, enter.easing)
        val reduced = homeQuickLaunchEnterSpec(reducedMotion = true) as TweenSpec<Float>
        assertEquals("reduced motion stays a settled state", 0, reduced.durationMillis)
        assertEquals(0, reduced.delay)
        val exit = homeQuickLaunchExitSpec() as TweenSpec<Float>
        assertEquals("it still leaves on OverlayMs", MimirMotion.OverlayMs, exit.durationMillis)
        assertEquals(0, exit.delay)
        val host = source("MimirAppHost.kt")
        assertTrue(host.contains("else fadeIn(enterSpec),"))
        assertTrue(host.contains("exit = fadeOut(exitSpec),"))
        listOf("GetArtOverlay.kt", "ArtworkFetchOverlay.kt").forEach { name ->
            assertTrue(name, source(name).contains("transitionSpec = { mimirSwapTransform(stepReducedMotion) },"))
        }
    }

    @Test
    fun `Main's dock specs are the promoted overlay specs, so the Bottom's dock runs the same clocks`() {
        // A4: the Bottom's dock reuses mimirOverlayArriveSpec/mimirOverlayLeaveSpec; Main delegates to them and stays as it was.
        assertEquals(mimirOverlayArriveSpec(reducedMotion = false), homeQuickLaunchEnterSpec(reducedMotion = false))
        assertEquals(mimirOverlayArriveSpec(reducedMotion = true), homeQuickLaunchEnterSpec(reducedMotion = true))
        assertEquals(mimirOverlayLeaveSpec(), homeQuickLaunchExitSpec())
        val host = source("MimirAppHost.kt")
        assertTrue(host.contains("homeQuickLaunchEnterSpec(reducedMotion: Boolean): FiniteAnimationSpec<Float> =\n    mimirOverlayArriveSpec(reducedMotion)"))
        assertTrue(host.contains("homeQuickLaunchExitSpec(): FiniteAnimationSpec<Float> = mimirOverlayLeaveSpec()"))
    }
}
