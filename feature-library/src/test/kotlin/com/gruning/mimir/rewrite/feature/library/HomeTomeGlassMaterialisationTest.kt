package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening a Tome briefly exposed the Home shelf through its scrim.
 *
 * ## The mechanism, and it is a constant arguing with its own call site
 *
 * The Folio paints `mimirGlassScrimColor()`, which on any blur-capable device resolves to
 * `MimirGlass.ScrimAlphaBlurred` = **0.52f**. That constant's KDoc states the assumption it is priced
 * against, in its own words: *"The old recipe used 0.85 because it had to hide an unblurred scene by
 * brute force. With a real blur the scrim only needs to buy contrast, so the scene stays visible through
 * the glass — which is the entire point of the treatment."*
 *
 * **The Folio wears the thin scrim over a scene that is never blurred.** The product's scene-side ramp
 * (`MimirAppHost`'s one `mimirGlassBackdropProgress`) is gated on `mimirOverlayUsesGlass`, an allowlist
 * of `MimirOverlayKind` — and there is no Folio member of that enum at all, because the Folio is not an
 * overlay. It is composed INSIDE the HOME destination as `HomeShelfScreen`'s sibling. So it is half a
 * glass surface: panel and scrim, no backdrop, and no wiring that could ever have given it one.
 *
 * That is why the shelf is legible through it, and why it is *most* legible mid-fade: the opening is a
 * whole-scene alpha, so at fade 0.5 the composite is half folio and half fully-sharp, fully-bright
 * shelf. **"For a split second"** is the fade window; the fade is what surfaces it.
 *
 * The Tome LIBRARY (`MimirOverlayKind.HOME_TOME_LIBRARY`) IS on that allowlist and does blur the scene.
 * The Tome FOLIO — the larger surface, and the one the player actually opens — does not. Same feature,
 * two grammars.
 *
 * ## What this file pins, and what it cannot
 *
 * `feature-library` has no Compose harness (plain JUnit), so nothing here renders a frame and **none of
 * it can tell you whether the opening reads as glass on a device.** These pin the seam: that
 * the parameter exists, that it is inert when absent, that both halves ride ONE lambda, and that the
 * values are read in draw blocks rather than at composition scope. The felt result is device-only.
 *
 * The scene half is deliberately NOT here. It must be applied to `HomeShelfScreen`, this composable's
 * sibling, which only the host can do — and creating a second progress here to do it would be two clocks
 * on one surface, which this tree rules against by name (`BrightnessOverlay.kt:480`).
 */
class HomeTomeGlassMaterialisationTest {

    private val folio: String by lazy { source("HomeTomeFolio.kt") }

    @Test
    fun `the folio accepts a host-supplied glass clock and stays inert without one`() {
        assertTrue(
            "HomeTomeFolio must take the progress as a nullable lambda. A Boolean would force the folio " +
                "to own an Animatable, and the scene half runs on HomeShelfScreen -- a sibling this " +
                "composable cannot reach -- so the two would be separate clocks and would drift.",
            folio.contains("glassProgress: (() -> Float)? = null"),
        )
        assertTrue(
            "Null must leave the surface completely inert -- no entry, no lift, nothing started. RC-34's " +
                "pattern: an unwired host (tests, previews) compiles and composes identically, and the " +
                "app patch is what activates the change.",
            folio.contains("if (glassProgress == null) Modifier else Modifier.mimirGlassPanelEntry(glassProgress)"),
        )
    }

    /**
     * **Re-scoped 2026-08-28**, and the
     * property it pins is unchanged — only its owner moved.
     *
     * The original assertion was that the scrim RAMPS on the same lambda the panel rides, because
     * spelling it `.background(...)` snaps it to full opacity on the mount frame: a hard cut underneath a
     * ramp. That is still the law and it is still enforced — but the scrim was owned by this composable,
     * which means it also **died in one frame on the way out**, and on API 30 (`MimirGlass.supportsBlur`
     * false) the scrim is the entire transition, so that step WAS the Tome return.
     *
     * A cover cannot be owned by the thing it is covering for. `MimirAppHost` now paints it as this
     * composable's sibling on the same `folioGlass` lambda, where it outlives the departing panel and
     * unwinds with the blur. So this file pins the half that is still here (the panel), pins that the
     * scrim is genuinely GONE from here rather than duplicated, and `TomeFolioReturnCoverTest` (app) pins
     * that the host took it. Two scrims stacked would be the darkening hazard, so "gone" is an assertion,
     * not a formality.
     */
    @Test
    fun `the panel rides the host lambda and no scrim is left behind to die with it`() {
        assertFalse(
            "The Folio must not paint a scrim it cannot outlive -- the host owns it now, and a second " +
                "one here would stack two 0.52 layers over the same shelf.",
            folio.contains("mimirGlassScrimColor"),
        )

        val panel = folio.sliceBetween(
            after = "MimirLiquidGlassPanel(",
            before = "contentPadding = 0.dp,",
        )
        assertTrue(
            "The panel must take the house entry (alpha + the 0.96 -> 1.0 lift) from the SAME lambda. " +
                "Found: <${panel.trim()}>",
            panel.contains("mimirGlassPanelEntry(glassProgress)"),
        )

        // Code lines only. The kdoc above the parameter deliberately NAMES the primitive while
        // explaining why this file must not call it, and a census that cannot tell an explanation from
        // a call would forbid documenting the rule it enforces.
        val progressConstruction = folio.lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .filter { it.contains("rememberMimirGlassProgress") || it.contains("rememberMimirGlassReturnProgress") }
        assertEquals(
            "The folio may not construct a progress of its own. The scene half runs on HomeShelfScreen, " +
                "a sibling this composable cannot reach, so a clock created here could only ever be a " +
                "SECOND one -- the defect `BrightnessOverlay.kt:480` names by hand (\"two clocks on one " +
                "surface, and they drift\"). Found: $progressConstruction",
            emptyList<String>(),
            progressConstruction,
        )
    }

    @Test
    fun `progress is read only inside draw blocks, never at composition scope`() {
        // PERF-D. Every `glassProgress()` INVOCATION must sit inside a drawBehind or a graphicsLayer.
        // Reading a per-frame value at composition scope recomposes the whole folio every frame instead
        // of invalidating draw -- the exact cost `mimirGlassBackdropProgress`'s kdoc exists to avoid, and
        // on the largest subtree in the product.
        //
        // Since T-3 moved the scrim to the host, this file has NO invocation left at all: the lambda is
        // only ever PASSED (to `mimirGlassPanelEntry`, which invokes it inside its own graphicsLayer).
        // That is strictly stronger than the rule, not an exemption from it, so the loop below is allowed
        // to find nothing -- but the parameter must still be consumed, or the surface has silently lost
        // its materialisation and this test would be pinning an absence.
        assertTrue(
            "glassProgress must still be consumed by the panel entry, or the folio no longer materialises",
            folio.contains("mimirGlassPanelEntry(glassProgress)"),
        )
        val invocationLines = folio.lines()
            .withIndex()
            .filter { (_, line) ->
                line.contains("glassProgress()") && !line.trimStart().startsWith("*")
            }
        invocationLines.forEach { (index, line) ->
            val preceding = folio.lines().subList(maxOf(0, index - 6), index).joinToString("\n")
            assertTrue(
                "glassProgress() at line ${index + 1} is not inside a draw block. Found context:\n" +
                    "$preceding\n${line.trim()}",
                preceding.contains("drawBehind") || preceding.contains("graphicsLayer"),
            )
        }
    }

    /**
     * T-2. The RING channel has no semantics to assert against (`mimirFocusTileRing` is paint), so the
     * pin that it is genuinely wired — not merely decided — is that **every** ring on this surface reads
     * the departure. There are two: the authoritative member ring and the empty-tome teaching plate's.
     * Missing the second is the concrete way this ships the exact ghost it was written to prevent.
     */
    @Test
    fun `every ring on the surface goes out on the departing frame`() {
        val ringCallSites = Regex("mimirFocusTileRing\\(").findAll(folio).count()
        assertEquals(
            "if a third ring appears on this surface it must be routed through the departure too",
            2,
            ringCallSites,
        )
        assertTrue(
            "the member ring's VALUE is overridden on the departing frame, not merely retargeted -- a " +
                "retarget unwinds over MimirMotion.FocusMs and the recorded still would keep a nearly " +
                "full-alpha ring",
            folio.contains("val ringProgress: () -> Float = { if (departing) 0f else ringAnimatedProgress.value }"),
        )
        assertTrue(
            "the teaching plate's ring takes the same suppression",
            folio.contains("ringSuppressed = departing") &&
                folio.contains("val focusProgress: () -> Float = { if (ringSuppressed) 0f else animatedFocusProgress.value }"),
        )
    }

    @Test
    fun `the folio never animates its own departure`() {
        // Pixel-handoff invariant: "departing
        // compositions are never animated; departing pixels may be." A surface kept composed to fade
        // itself out is a popped scope still registering focus slots. The Folio's exit belongs to the
        // host, and it must be a recorded-pixel fade rather than a live subtree -- so nothing that
        // animates this composable away may appear in this file.
        assertFalse(
            "HomeTomeFolio must not grow its own exit animation. The departure is the host's, and the " +
                "house form is a recorded GraphicsLayer, not a live tree held alive by AnimatedVisibility.",
            folio.contains("AnimatedVisibility") || folio.contains("fadeOut("),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", substringAfter(after).contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
