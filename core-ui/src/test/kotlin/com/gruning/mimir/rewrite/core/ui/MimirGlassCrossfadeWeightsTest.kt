package com.gruning.mimir.rewrite.core.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  The glass drew the sharp layer at full weight under a blur at weight p until the peak and dropped it in one frame; over the
 * cards' translucent scrim that was a hard cut to frost with a +10 luma flash on open, and its mirror on close. The draw weights are one
 * pure rule now ([mimirGlassDrawWeights]), driven here through the real eased open and close at 120 Hz: the sharp weight is 1 − p, the
 * blur weight is p, no frame steps, and the ends are the plain single-layer draws.
 *
 * Robolectric draws no GraphicsLayer and no blur, so the pixels of the group itself are the device's (the Thor confirms on the next
 * build). The compositing arithmetic the group relies on is modelled at the end as the control for the opaque sites, which must not
 * change, and for the translucent ones, which must fade instead of stacking.
 */
class MimirGlassCrossfadeWeightsTest {

    private val frameMs = 1000f / 120f
    private val radiusPx = 50f

    /** The progress and the weights of every frame of one eased ramp, open (0 to 1) or close (1 to 0), at 8 ms frames. */
    private fun ramp(opening: Boolean): List<Pair<Float, MimirGlassDrawWeights>> {
        val frames = (MimirMotion.OverlayMs / frameMs).toInt() + 1
        return (0..frames).map { i ->
            val fraction = (i * frameMs / MimirMotion.OverlayMs).coerceIn(0f, 1f)
            val eased = MimirMotion.Easing.transform(fraction)
            val p = if (opening) eased else 1f - eased
            p to mimirGlassDrawWeights(mimirGlassBlurPlan(p, radiusPx))
        }
    }

    @Test
    fun `the sharp weight follows 1 - p and the blur weight follows p on every frame, open and close`() {
        for (opening in listOf(true, false)) {
            for ((p, w) in ramp(opening)) {
                val plan = mimirGlassBlurPlan(p, radiusPx)
                assertEquals("blur at p=$p", plan.blurAlpha, w.blur, 1e-6f)
                assertEquals("sharp at p=$p", 1f - plan.blurAlpha, w.sharp, 1e-6f)
            }
        }
    }

    @Test
    fun `no frame is a step, in either weight, in either direction`() {
        for (opening in listOf(true, false)) {
            val name = if (opening) "opening" else "closing"
            val run = ramp(opening).map { it.second }
            val sharpSteps = run.zipWithNext { a, b -> abs(b.sharp - a.sharp) }
            val blurSteps = run.zipWithNext { a, b -> abs(b.blur - a.blur) }
            assertTrue("$name: the sharp layer moves in one frame (largest step ${sharpSteps.max()})", sharpSteps.max() <= 0.15f)
            assertTrue("$name: the blur moves in one frame (largest step ${blurSteps.max()})", blurSteps.max() <= 0.15f)
        }
    }

    @Test
    fun `the weights sum to one while both layers show, so the summed group is the exact fade`() {
        val crossfading = ramp(true).filter { (_, w) -> w.crossfade }
        assertTrue("the ramp has frames with both layers up", crossfading.size >= 8)
        for ((p, w) in crossfading) assertEquals("at p=$p", 1f, w.sharp + w.blur, 1e-6f)
    }

    @Test
    fun `at rest the frame is one plain layer and no group`() {
        val clear = mimirGlassDrawWeights(mimirGlassBlurPlan(0f, radiusPx))
        assertEquals(MimirGlassDrawWeights(sharp = 1f, blur = 0f), clear)
        assertFalse(clear.crossfade)
        val peak = mimirGlassDrawWeights(mimirGlassBlurPlan(1f, radiusPx))
        assertEquals(MimirGlassDrawWeights(sharp = 0f, blur = 1f), peak)
        assertFalse(peak.crossfade)
        // Under the plan's 0.1 px cut-off no blur is drawn, so no group either.
        assertFalse(mimirGlassDrawWeights(mimirGlassBlurPlan(0.001f, radiusPx)).crossfade)
        assertTrue(mimirGlassDrawWeights(mimirGlassBlurPlan(0.5f, radiusPx)).crossfade)
    }

    // ---- The control: the compositing arithmetic, premultiplied, one colour channel. ----

    /** A premultiplied pixel: [c] is the colour already multiplied by [a]. */
    private data class Px(val c: Float, val a: Float)

    private fun Px.over(dst: Px) = Px(c + dst.c * (1f - a), a + dst.a * (1f - a))
    private fun Px.times(w: Float) = Px(c * w, a * w)
    private fun Px.plus(other: Px) = Px(c + other.c, a + other.a)

    /** The plan's strength at p: 0 under the 0.1 px cut-off, where both draws paint the sharp layer alone. */
    private fun strength(p: Float): Float = mimirGlassBlurPlan(p, radiusPx).blurAlpha

    /** The draw before 2026-10-15: below the peak the sharp layer SrcOver at full weight, then the blur SrcOver at p; at the peak the blur alone. */
    private fun stacked(sharp: Px, blur: Px, p: Float, ground: Px): Px {
        val a = strength(p)
        return when {
            a <= 0f -> sharp.over(ground)
            a < 1f -> blur.times(a).over(sharp.over(ground))
            else -> blur.over(ground)
        }
    }

    /** The draw now: sharp × (1 − p) plus blur × p summed in one group, the group composited once; one plain layer at either end. */
    private fun grouped(sharp: Px, blur: Px, w: MimirGlassDrawWeights, ground: Px): Px =
        if (!w.crossfade) (if (w.blur > 0f) blur else sharp).over(ground) else sharp.times(w.sharp).plus(blur.times(w.blur)).over(ground)

    private fun lerp(sharp: Px, blur: Px, p: Float, ground: Px): Px {
        val a = strength(p)
        return sharp.times(1f - a).plus(blur.times(a)).over(ground)
    }

    @Test
    fun `over an opaque surface the group is the old draw pixel for pixel away from the edges - the ACHIEVEMENTS host glass does not change`() {
        // The model has no edges. Within the blur's reach of the layer's edge the Decal blur is partly transparent, so the group's
        // mid-ramp alpha dips there: a smooth fade toward the peak's own edge, never a step. That band is the device's to confirm.
        val ground = Px(0.1f, 1f)
        val sharp = Px(0.9f, 1f) // an opaque surface: type on the SurfaceBase ground
        val blur = Px(0.4f, 1f)
        for (opening in listOf(true, false)) {
            for ((p, w) in ramp(opening)) {
                val old = stacked(sharp, blur, p, ground)
                val new = grouped(sharp, blur, w, ground)
                assertEquals("colour at p=$p", old.c, new.c, 1e-5f)
                assertEquals("alpha at p=$p", old.a, new.a, 1e-5f)
            }
        }
    }

    @Test
    fun `over a translucent surface the old draw stacked two scrims and cut, the group is the fade`() {
        val scrim = 0.52f
        val sharp = Px(0.9f * scrim, scrim) // bright type over the launch card's 0.52 scrim
        val blur = Px(0.2f * scrim, scrim) // the same, blurred: dimmer, the same cover
        val ground = Px(0.6f, 1f)
        val none = Px(0f, 0f)
        val frames = ramp(true)
        for ((p, w) in frames) {
            val expect = lerp(sharp, blur, p, ground)
            val got = grouped(sharp, blur, w, ground)
            assertEquals("the group is the lerp at p=$p", expect.c, got.c, 1e-5f)
            assertEquals("one scrim at p=$p, not two", scrim, grouped(sharp, blur, w, none).a, 1e-5f)
        }
        val oldRun = frames.map { (p, _) -> stacked(sharp, blur, p, ground).c }
        val newRun = frames.map { (_, w) -> grouped(sharp, blur, w, ground).c }
        val oldSteps = oldRun.zipWithNext { a, b -> abs(b - a) }
        val newSteps = newRun.zipWithNext { a, b -> abs(b - a) }
        // The old draw's largest step is its LAST frame, the one that reaches the peak: the cut the Thor video shows (grad 10 -> 0.7, luma
        // +10 in one frame), larger than any frame of the eased ramp before it. The group's largest step is a frame of the ease, and it
        // lands mid-ramp where the ease is steepest, not at the end.
        assertEquals("the old draw cut on the frame it reached the peak", oldSteps.lastIndex, oldSteps.indexOf(oldSteps.max()))
        assertTrue("the cut (${oldSteps.max()}) was larger than any frame of the fade (${newSteps.max()})", oldSteps.max() > newSteps.max())
        assertTrue("the group's last frame is no step (${newSteps.last()})", newSteps.last() < newSteps.max())
        // Near the peak the old draw covered the ground with about 0.77 (two scrims); the group holds the card's own 0.52.
        val (nearPeak, _) = frames.last { (p, _) -> p < 1f }
        val oldCover = stacked(sharp, blur, nearPeak, none).a
        assertTrue("two scrims stacked to $oldCover at p=$nearPeak", oldCover > 0.7f)
    }
}
