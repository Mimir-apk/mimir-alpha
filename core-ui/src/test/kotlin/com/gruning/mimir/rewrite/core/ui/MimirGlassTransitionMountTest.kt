package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The mount-time hard cut — pinned as source, because it is invisible at every other gate.**
 *
 * `rememberMimirGlassProgress` shipped inert (`8523921a`) with the initialiser
 * `Animatable(if (visible) 1f else 0f)`. That reads as defensive and is the opposite: **the ordinary way
 * to present a glass panel in this tree is to mount it when it opens**, so `visible` is already `true` at
 * first composition. The Animatable would be born at `1f`, the `LaunchedEffect` would animate `1f -> 1f`,
 * and the panel would cut in exactly as it did before the transition existed.
 *
 * `AppBrightnessHost` states the mount rule in its own comment — *"the host is only in composition while
 * the panel is open"* — so the product's heaviest glass surface is precisely the case that would have
 * played nothing.
 *
 * **This is UI-01 wearing the new primitive's name**, and it is the reason this pin exists rather than a
 * comment: it compiles, it runs, it consumes the frame beat, it reports as "transition wired", and the
 * only way to catch it is to look at a device or to read this initialiser. No compiler check, no census,
 * and no unit test that lacks a Compose harness can see a transition that animates from its target to its
 * target.
 *
 * ## What this does NOT prove
 *
 * No Compose harness is configured in this module, so nothing here renders a frame or measures a ramp.
 * This pins the STRUCTURAL property as source text. **Whether the glass actually materialises is
 * device-only**.
 */
class MimirGlassTransitionMountTest {

    private fun source(): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirGlassTransition.kt")
        assertTrue(
            "MimirGlassTransition.kt not found at ${file.absolutePath} — if this file moved, move this " +
                "pin with it rather than deleting it.",
            file.exists(),
        )
        // Strip comments before asserting: every claim below is about CODE. The KDoc quotes the old
        // initialiser verbatim to explain why it was wrong, and a naive grep would match that prose and
        // report the defect present in a tree that fixed it.
        return file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    @Test
    fun `progress starts at zero so a mounted-and-visible surface still ramps`() {
        val code = source()
        assertTrue(
            "rememberMimirGlassProgress must initialise its Animatable at 0f. A surface that is mounted " +
                "already-open is the COMMON case in this tree, not the edge case, and any initialiser " +
                "that starts at the target makes the transition a hard cut wearing an animation's name.",
            code.contains("remember(identity) { Animatable(0f) }"),
        )
    }

    @Test
    fun `the target-seeking initialiser cannot come back`() {
        val code = source()
        assertFalse(
            "Animatable(if (visible) 1f else 0f) is the mount-time hard cut this primitive shipped with. " +
                "It looks like it preserves a settled surface's state across an identity change; what it " +
                "actually does is make every mount-per-open caller play nothing. If you need a settled " +
                "surface to survive an identity change, keep the identity CONSTANT at that call site — " +
                "which is what both scene-side sites do — rather than restoring this initialiser.",
            code.contains("Animatable(if (visible)"),
        )
    }

    @Test
    fun `the frame beat precedes the ramp`() {
        val code = source()
        val beat = code.indexOf("withFrameNanos")
        val ramp = code.indexOf("progress.animateTo")
        assertTrue("withFrameNanos missing — the frame beat is part of the contract, not an optimisation.", beat >= 0)
        assertTrue("progress.animateTo missing.", ramp >= 0)
        assertTrue(
            "The frame beat must come BEFORE animateTo. A subtree still rebuilding discards every " +
                "in-flight animation handle and each restarts already at its target; the beat is what " +
                "lets the rebuild finish first. fileBrowserArrivalGlide takes the same beat for the " +
                "same reason.",
            beat < ramp,
        )
    }

    @Test
    fun `blur is read at draw time, never as a Modifier blur value`() {
        val code = source()
        assertTrue(
            "The backdrop ramp must run inside graphicsLayer's block, which executes at DRAW time.",
            code.contains("graphicsLayer {") && code.contains("renderEffect ="),
        )
        assertFalse(
            "Modifier.blur(radius) takes a VALUE, so an animating radius is read at modifier-construction " +
                "time and recomposes the whole scene subtree every frame — at 120 Hz, twice as often as " +
                "this design was measured at. Keep the ramp inside graphicsLayer.",
            Regex("""\.blur\(""").containsMatchIn(code),
        )
    }
}
