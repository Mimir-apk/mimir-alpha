package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * one live blur under an open picker. [mimirGlassBackdropProgress]'s `snapshot` overload keeps the surface's last
 * recording while it is at its peak, so the glass blurs an image of the surface instead of re-recording it every frame. A layer cannot be
 * drawn here (Robolectric draws no GraphicsLayer and no blur), so the rule that decides it is pinned pure and the wiring as source; the
 * pixels are the emulator sweep's.
 */
class MimirGlassSurfaceFreezeTest {

    @Test
    fun `a frozen surface is not recorded again, but one that has never been recorded always is`() {
        assertTrue("live: record every draw", mimirGlassSurfaceNeedsRecording(frozen = false, recorded = true))
        assertTrue(mimirGlassSurfaceNeedsRecording(frozen = false, recorded = false))
        assertFalse("frozen with a recording: keep it", mimirGlassSurfaceNeedsRecording(frozen = true, recorded = true))
        assertTrue("frozen with nothing to keep: record once whatever is asked", mimirGlassSurfaceNeedsRecording(frozen = true, recorded = false))
    }

    @Test
    fun `the freeze starts at the peak and ends on the first clearing frame, for every caller (review 2026-10-15 #9)`() {
        assertFalse("the press dip and the open ramp record live", mimirGlassSurfaceFrozen(snapshot = true, progress = 0.01f))
        assertFalse(mimirGlassSurfaceFrozen(snapshot = true, progress = 0.99f))
        assertTrue("at the peak it is an image", mimirGlassSurfaceFrozen(snapshot = true, progress = 1f))
        assertFalse("the first frame the progress leaves 1 records afresh", mimirGlassSurfaceFrozen(snapshot = true, progress = 0.98f))
        assertFalse("a caller that wants no snapshot never freezes", mimirGlassSurfaceFrozen(snapshot = false, progress = 1f))
    }

    @Test
    fun `the draw block decides freezing from the one rule`() {
        val draw = source().substringAfter("onDrawWithContent {").substringBefore("sharp.record")
        assertTrue(draw.contains("frozen = mimirGlassSurfaceFrozen(snapshot = snapshot(), progress = level),"))
    }

    private fun source(): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirGlassTransition.kt"),
        File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirGlassTransition.kt"),
    ).first(File::isFile).readText()

    @Test
    fun `the two existing overloads keep their signatures and delegate with nothing snapshotted`() {
        val text = source()
        assertTrue(text.contains("fun Modifier.mimirGlassBackdropProgress(progress: () -> Float): Modifier ="))
        assertTrue(text.contains("fun Modifier.mimirGlassBackdropProgress(maxRadius: Dp, progress: () -> Float): Modifier ="))
        assertTrue(text.contains("fun Modifier.mimirGlassBackdropProgress(maxRadius: () -> Dp, progress: () -> Float): Modifier ="))
        assertTrue(text.contains("mimirGlassBackdropProgress(maxRadius, progress, snapshot = { false })"))
        assertTrue(text.contains("fun Modifier.mimirGlassBackdropProgress(maxRadius: () -> Dp, progress: () -> Float, snapshot: () -> Boolean): Modifier ="))
    }

    @Test
    fun `a frozen frame skips both recordings and still draws both layers`() {
        val draw = source().substringAfter("val rerecord = mimirGlassSurfaceNeedsRecording(").substringBefore("drawIntoCanvas")
        assertTrue(draw.contains("sharp.record { this@onDrawWithContent.drawContent() }"))
        assertTrue("the blurred copy is recorded with the surface, or once if it never was", draw.contains("if (rerecord || !blurredRecorded) {"))
        // The layers are drawn on every pass (the GraphicsLayer replay trap: a recorded layer nothing draws loses its recording): the sharp
        // one alone below the blur's cut-off, the frost alone at the peak (frozen or live, the same plain draw), and both inside the
        // crossfade group between (2026-10-15: summed at 1 − p and p, composited once).
        assertTrue(draw.contains("if (plan.blurAlpha <= 0f) {\n                        drawLayer(sharp)"))
        assertTrue(draw.contains("if (!weights.crossfade) {\n                        drawLayer(blurred)"))
        assertTrue(
            "the draw feeds the tested weights to the blur's alpha",
            draw.contains("val weights = mimirGlassDrawWeights(plan)") && draw.contains("blurred.alpha = weights.blur"),
        )
        // The group is the tested plan (MimirGlassCrossfadeDrawTest), fed those weights, drawing these two layers.
        val group = source().substringAfter("drawIntoCanvas { canvas ->").substringBefore("\n                    }\n")
        assertTrue(group.contains("mimirGlassCrossfadeDraw(") && group.contains("weights = weights,") && group.contains("paints = paints,"))
        assertTrue(group.contains("drawSharp = { drawLayer(sharp) }") && group.contains("drawBlurred = { drawLayer(blurred) }"))
    }
}
