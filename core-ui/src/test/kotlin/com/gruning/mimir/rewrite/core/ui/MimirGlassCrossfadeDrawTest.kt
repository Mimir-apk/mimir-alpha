package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The crossfade group's plan ([mimirGlassCrossfadeDraw]), the thing the draw block executes between the ends of the ramp, recorded call
 * by call: one group layer (SrcOver at 1, the one composite with what is behind), the sharp layer through a weight layer at exactly
 * [MimirGlassDrawWeights.sharp], the blurred copy through an additive ([BlendMode.Plus]) layer, each restored, the group restored last. A
 * draw that used other weights, composited the blur SrcOver again, or left the group open changes this record. Robolectric for the
 * Paint; no pixel is drawn (the pixels are the Thor's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimirGlassCrossfadeDrawTest {

    private fun record(weights: MimirGlassDrawWeights, paints: MimirGlassCrossfadePaints): List<String> {
        val calls = mutableListOf<String>()
        fun name(paint: Paint) = when (paint) {
            paints.group -> "group(${paint.blendMode}, alpha ${paint.alpha})"
            paints.sharpWeight -> "sharp(${paint.blendMode}, alpha ${paint.alpha})"
            paints.blurSum -> "blur(${paint.blendMode}, alpha ${paint.alpha})"
            else -> "unknown paint"
        }
        mimirGlassCrossfadeDraw(
            weights = weights,
            paints = paints,
            saveLayer = { calls += "saveLayer " + name(it) },
            drawSharp = { calls += "drawSharp" },
            drawBlurred = { calls += "drawBlurred" },
            restore = { calls += "restore" },
        )
        return calls
    }

    @Test
    fun `the group is sharp at 1 - p through its weight layer plus the blur additively, composited once`() {
        val paints = MimirGlassCrossfadePaints()
        for (p in listOf(0.2f, 0.5f, 0.8f)) {
            val weights = mimirGlassDrawWeights(mimirGlassBlurPlan(p, 50f))
            val calls = record(weights, paints)
            val sharpAlpha = paints.sharpWeight.alpha
            assertEquals("the sharp weight layer carries 1 - p at p=$p (8-bit)", 1f - p, sharpAlpha, 1f / 255f)
            assertEquals(
                listOf(
                    "saveLayer group(${BlendMode.SrcOver}, alpha 1.0)",
                    "saveLayer sharp(${BlendMode.SrcOver}, alpha $sharpAlpha)",
                    "drawSharp",
                    "restore",
                    "saveLayer blur(${BlendMode.Plus}, alpha 1.0)",
                    "drawBlurred",
                    "restore",
                    "restore",
                ),
                calls,
            )
        }
    }

    @Test
    fun `the paints are built once and only the sharp weight moves between frames`() {
        val paints = MimirGlassCrossfadePaints()
        record(mimirGlassDrawWeights(mimirGlassBlurPlan(0.25f, 50f)), paints)
        val first = paints.sharpWeight.alpha
        record(mimirGlassDrawWeights(mimirGlassBlurPlan(0.75f, 50f)), paints)
        assertEquals(0.75f, first, 1f / 255f)
        assertEquals(0.25f, paints.sharpWeight.alpha, 1f / 255f)
        assertEquals(BlendMode.Plus, paints.blurSum.blendMode)
        assertEquals(BlendMode.SrcOver, paints.group.blendMode)
        assertEquals(1f, paints.group.alpha, 0f)
        assertEquals(1f, paints.blurSum.alpha, 0f)
    }
}
