package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The focus ring must never be drawn into a clipping layer.**
 *
 * ## Two separate clips caused it, and both are pinned here
 *
 * **1. `Modifier.alpha` clips.** `mimirFocusRing` used to end `.scale(scale).alpha(contentAlpha).border()`.
 * Read from shipped `ui-android:1.7.3` bytecode rather than assumed: `AlphaKt.alpha` returns the receiver
 * unchanged when `alpha == 1.0f`, and otherwise calls `graphicsLayer` with default-mask `126971` — bit 2
 * clear (alpha passed) and **bit 12 clear (clip passed)**, with `iconst_1` in the clip slot. `restingAlpha`
 * defaults to `0.88`, so **every unfocused element in the product was clipped to its own bounds**, and the
 * clip then disappeared entirely as the focus animation carried alpha to exactly `1.0` — the chain changed
 * SHAPE mid-animation, destroying and rebuilding the layer at the moment the ring was widest.
 *
 * **2. An alpha'd layer composites offscreen, which clips too.** `CompositingStrategy.Auto` maps to
 * `setUseCompositingLayer(false)` + `setHasOverlappingRendering(TRUE)` (`RenderNodeApi29`, same artifact),
 * and a render node with overlapping rendering and `alpha < 1` must composite into an offscreen buffer
 * **sized to its own bounds**. The focused ring is scaled `1.03` and therefore drawn OUTSIDE those bounds,
 * so it was cut off. `ModulateAlpha` maps to `setHasOverlappingRendering(false)` and modulates per drawing
 * instruction, allocating no buffer.
 *
 * That second mechanism is why the defect tracked the **dim** population exactly — pale support files,
 * machinery folders, hidden folders. An undimmed tile sits at alpha 1, never allocates the buffer, and
 * never clipped. The affected dim population is the set this mechanism predicts.
 *
 * ## What this does NOT prove
 *
 * No Compose harness runs here: nothing renders a ring or measures a pixel. This pins the structural
 * property as source text. **Whether the ring is still cut on a device is device-only.**
 */
class MimirFocusRingClipTest {

    private fun tokens(): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirTokens.kt").readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private fun ringBody(): String {
        val code = tokens()
        val start = code.indexOf("fun Modifier.mimirFocusRing(")
        assertTrue("mimirFocusRing not found — if it moved, move this pin with it.", start >= 0)
        val end = code.indexOf("fun Modifier.mimirSurfaceShadow(", start)
        return code.substring(start, if (end > start) end else code.length)
    }

    @Test
    fun `the focus ring does not use Modifier alpha, which clips`() {
        assertFalse(
            "Modifier.alpha(a) sets clip = true whenever a != 1f (verified in ui-android:1.7.3 bytecode). " +
                "With restingAlpha = 0.88 that clipped every unfocused element in the product, and the " +
                "clip vanished at exactly alpha 1.0 so the chain changed shape mid-animation. Set alpha " +
                "inside the same graphicsLayer block as the scale instead.",
            Regex("""\.alpha\(""").containsMatchIn(ringBody()),
        )
    }

    @Test
    fun `the focus ring does not use Modifier scale as a separate layer`() {
        assertFalse(
            "scale and alpha belong in ONE graphicsLayer. Two modifiers meant two layers on every " +
                "focusable element in the product, for values that are read at draw time anyway.",
            Regex("""\.scale\(""").containsMatchIn(ringBody()),
        )
    }

    @Test
    fun `the focus ring layer modulates alpha instead of compositing offscreen`() {
        val body = ringBody()
        assertTrue(
            "The ring's layer must set compositingStrategy = CompositingStrategy.ModulateAlpha. The " +
                "default Auto sets hasOverlappingRendering(true), and alpha < 1 then composites into a " +
                "bounds-sized offscreen buffer — which clips the 1.03-scaled ring drawn outside it.",
            body.contains("compositingStrategy = CompositingStrategy.ModulateAlpha"),
        )
        assertTrue(
            "scale and alpha must both be set inside that same graphicsLayer block.",
            body.contains("scaleX = scale") && body.contains("alpha = contentAlpha"),
        )
    }
}
