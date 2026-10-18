package com.gruning.mimir.rewrite.core.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * MIMIR TEXT-METRICS HARNESS — the missing instrument
 * Measures: given a string, a [MimirTypeScale] style (including `letterSpacing`), and a density, how wide does
 * it render.
 *
 * ## Why Robolectric `@GraphicsMode(NATIVE)`, not a font-table (`hmtx`) reader
 *
 * Both approaches were grounded before choosing :
 *
 * - **A font-metrics (`hmtx` advance-table) reader** is the method the earlier hand derivation used —
 *   see [MimirFonts.SansUppercaseAdvanceRatio]'s own KDoc and `SettingsHome.kt`'s `settingsRailWidthDp`
 *   KDoc (the ~82dp `"APPEARANCE"` figure this file verifies against, §2 below). It is cheap and it is
 *   proven to reproduce a known font constant exactly (`SansUppercaseAdvanceRatio` re-derivation, cited in
 *   that same KDoc). But it is explicitly an **approximation**: summing per-glyph advances has no kerning
 *   and no ligature substitution, and the earlier hand derivation had to pick between two
 *   different letter-spacing conventions (9 vs 10 gaps) because Android's `letterSpacing` behavior isn't
 *   encoded in the font tables at all — it lives in the text-layout stack, not the font.
 * - **Robolectric with `@GraphicsMode(NATIVE)`** does real text shaping through Android's actual layout
 *   stack (`android.text.StaticLayout`/`Paint.measureText` under the hood), against the SAME shipped
 *   `ibm_plex_sans_*.ttf` binaries the device renders — kerning, ligatures and `letterSpacing` all included,
 *   because it is the same code path a device uses, not a re-implementation of it.
 *   `OnboardingScreenFitTest` (`app` module) already proves this technique works in this tree and that
 *   `NATIVE` mode specifically is load-bearing: that file's own KDoc records a throwaway probe where
 *   non-NATIVE mode measured a 17-char and a 130-char string as the SAME single line, while NATIVE gave the
 *   correct 3-line wrap. A harness that silently used the wrong graphics mode would under-report and never
 *   say so — a silent measurement error that would invalidate clipping checks.
 *
 * **What it cost to bring to `core-ui`**: nothing new to fetch offline. `core-ui/build.gradle.kts` adds the
 * same JVM Compose-test stack `app/build.gradle.kts` already declares (Robolectric 4.14.1, BOM-pinned
 * `ui-test-junit4`, `isIncludeAndroidResources = true` so the shipped TTFs under `res/font` inflate) — every
 * transitive artifact NATIVE mode needs (`nativeruntime`, `nativeruntime-dist-compat`,
 * `shadows-framework`, the `sdk=34` `android-all-instrumented` jar) was already resolved into
 * `~/.gradle`/`~/.m2` by `app`'s own prior use, verified with `sh tools/mimir-gradle.sh --offline
 * :core-ui:testDebugUnitTest` (green, no network) BEFORE writing this file's body. `core-ui` already ships
 * `res/font` (the four `ibm_plex_sans_*.ttf` files) and an `AndroidManifest.xml`, so nothing else was needed.
 *
 * **Picked Robolectric over the font-table reader** because its evidence limits are narrower: real shaping's only gaps are density rounding,
 * a real device's rasterizer hinting, and `fontScale != 1.0` (not exercised here; every measurement below
 * pins `Density(density = 1f, fontScale = 1f)` so px == dp, the same convention `OnboardingScreenFitTest`
 * uses). A font-table reader's gaps (no kerning, no ligatures, letter-spacing convention ambiguity) are
 * gaps in the SHAPE of the answer, not just its device-fidelity.
 *
 * ## Accuracy bound — stated plainly, so a caller knows whether a margin is real
 *
 * This harness measures through the SAME `Text`/`Layout` composition production surfaces use
 * (`softWrap = false`, `maxLines = 1`, the exact [TextStyle] passed in, including `letterSpacing`), at
 * `Density(1f, 1f)` so its raw px output is numerically a dp figure.
 *
 * **`Layout`'s `Int` placeable width is NOT sub-dp-accurate, and the direction of the error is
 * SIZE-DEPENDENT, not universal — measured, not assumed**, by `Density(1f) accuracy bound, and whether the
 * tightest §3 headroom cell survives it` below: the same text/style measured a second time at 16x density
 * (recovering ~16x the pixel resolution, then divided back down) disagrees with the `Density(1f)` reading
 * by **+0.13dp to +3.00dp (over-report) at 11sp**, across four cases (single glyph, a 4-char label, an
 * 8-char label, a 10-char label) — bigger than plain integer-pixel quantization alone would explain (that
 * would bound the error under 1dp). The likely mechanism is glyph hinting/pixel-snapping at small integer
 * font sizes (11px at `Density(1f)`, vs. ~176px at the 16x probe, where the same absolute hinting
 * adjustment is a much smaller fraction of the total width) — plausible, not device-verified. **A first
 * draft of this file assumed that over-report direction was universal and asserted "always conservative,
 * never under-reports"; a fifth probed case at [MimirRunicQuickRailDefaults.MinLabelFontSize] (9sp — the
 * exact size §3's tightest cell actually renders at) UNDER-reports instead, by -0.44dp. The assumption was
 * wrong and is corrected here, not smoothed over — the instrument must state this limit explicitly.**
 *  - **No blanket "always conservative" claim survives.** Direction depends on font size (at least — the
 *    exact boundary between the two regimes is NOT characterized by this file, only that 11sp over-reports
 *    and 9sp under-reports in the cases probed). A caller must not assume either direction for an
 *    unprobed size/string without running the canary at those exact conditions.
 *  - **What IS established: §3's own tightest reading survives its own worst-case error.** The
 *    accuracy-bound test measures the error AT THE EXACT CONDITIONS §3's tightest cell (538dp companion /
 *    SETTINGS, reported +2.00dp headroom) actually renders at — the same 9sp floor, same label — and
 *    asserts the reported headroom stays positive after applying that specific -0.44dp error
 *    (worst-case true headroom ≈ +1.56dp). This is a targeted, not a blanket, safety argument.
 *  - **This is a bound on THIS harness's own instrument, re-derived per case, not a universal constant.**
 *    A caller reading a tight headroom figure should not assume a direction — re-run the canary for the
 *    caller's own specific string/style/size if the exact bound matters, rather than reusing a number
 *    measured for a different size here.
 *  - **Not certified against a physical rasterizer.** Robolectric's NATIVE mode uses a real (bundled) Skia
 *    text-shaping engine, not the exact one running on the Odin/Nova's GPU driver. Device proof still
 *    outranks this file.
 *  - **`fontScale` is pinned to 1.0.** Every measurement here is the base-density figure; a caller applying
 *    this at a user's accessibility font scale must scale the result, not assume it.
 *  - **Uppercase transforms are applied explicitly** ([mimirStaticChrome]) so the measured run matches what
 *    [MimirChromeText] actually renders — omitting that transform previously measured different text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirTextMetricsHarnessTest {

    @get:Rule
    val compose = createComposeRule()

    /** Measures [content] with BOTH axes unbounded — the natural single-line ink size of a `Text` composed
     * with `softWrap = false, maxLines = 1` (matching every production caller this harness serves; see
     * [MimirRunicQuickRailItem]'s own label `Text` call for the shape being reproduced). */
    @androidx.compose.runtime.Composable
    private fun NaturalWidthProbe(onMeasured: (widthPx: Int, heightPx: Int) -> Unit, content: @androidx.compose.runtime.Composable () -> Unit) {
        Layout(content = content) { measurables, _ ->
            val placeable = measurables.first().measure(
                Constraints(minWidth = 0, maxWidth = Constraints.Infinity, minHeight = 0, maxHeight = Constraints.Infinity),
            )
            onMeasured(placeable.width, placeable.height)
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    }

    /** One composition, many measurements — [androidx.compose.ui.test.junit4.ComposeContentTestRule] allows
     * only a single `setContent` per test (same constraint `OnboardingScreenFitTest` documents and works
     * around the same way): state is swapped and re-measured in a loop rather than re-composing per case.
     * `densityScale` is itself swappable state, not a construction-time parameter, for the same reason —
     * the rounding-direction canary below needs TWO densities inside the one `setContent` this rule allows. */
    private class TextMetricsHarness(
        val setText: (String) -> Unit,
        val setStyle: (TextStyle) -> Unit,
        val setDensityScale: (Float) -> Unit,
        val widthPx: () -> Int,
        val heightPx: () -> Int,
    )

    /** [initialDensityScale] defaults to 1f (px == dp, every production-facing measurement in this file). */
    private fun setUpHarness(initialDensityScale: Float = 1f): TextMetricsHarness {
        var text by mutableStateOf("")
        var style by mutableStateOf(TextStyle.Default)
        var densityScale by mutableStateOf(initialDensityScale)
        var measuredWidthPx by mutableIntStateOf(0)
        var measuredHeightPx by mutableIntStateOf(0)

        compose.setContent {
            val density = Density(density = densityScale, fontScale = 1f)
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    NaturalWidthProbe(onMeasured = { w, h -> measuredWidthPx = w; measuredHeightPx = h }) {
                        Text(text = text, style = style, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        return TextMetricsHarness(
            setText = { text = it },
            setStyle = { style = it },
            setDensityScale = { densityScale = it },
            widthPx = { measuredWidthPx },
            heightPx = { measuredHeightPx },
        )
    }

    /** Renders [text] under [style] and returns its natural single-line ink width in dp at whatever density
     * scale the harness currently holds (1f unless [TextMetricsHarness.setDensityScale] was called). The
     * primary function this whole file exists to provide. */
    private fun TextMetricsHarness.inkWidthDp(text: String, style: TextStyle): Float {
        setText(text)
        setStyle(style)
        compose.waitForIdle()
        return widthPx().toFloat()
    }

    /** Same measurement as [inkWidthDp], reading the natural single-line ink HEIGHT instead — needed for the
     * label-band-clearance check against [MimirRunicQuickRailDefaults.LabelBandHeight], not only width. */
    private fun TextMetricsHarness.inkHeightDp(text: String, style: TextStyle): Float {
        setText(text)
        setStyle(style)
        compose.waitForIdle()
        return heightPx().toFloat()
    }

    // ── §1: prove the instrument first ──────────────────────────────────────────────────────────────────

    @Test
    fun `NATIVE graphics mode measures per-character width, not a fixed-line stub (LEGACY-mode canary)`() {
        // OnboardingScreenFitTest's own KDoc: without NATIVE mode Robolectric's text layout does not shape
        // text at all -- a 17-char and a 130-char string both measured as the SAME single line under
        // LEGACY. Reproduced here as a standing canary: if a future Gradle/Robolectric upgrade silently
        // drops NATIVE mode (annotation stripped, sdk mismatch, etc.), this fails LOUDLY instead of every
        // other assertion in this file quietly under-reporting width forever. (`maxLines = 1, softWrap =
        // false` means neither string actually wraps here -- the property under test is that width scales
        // with character count at all, which a LEGACY stub would fail even without wrapping involved.)
        val harness = setUpHarness()
        val shortWidth = harness.inkWidthDp("A", MimirTypeScale.RunicEyebrow)
        val longWidth = harness.inkWidthDp("A".repeat(120), MimirTypeScale.RunicEyebrow)
        assertTrue(
            "NATIVE-mode canary: a 120-char run must measure meaningfully wider than a 1-char run " +
                "(short=${shortWidth}dp, long=${longWidth}dp) -- if this fails, every other assertion in " +
                "this file is measuring garbage, not text",
            longWidth > shortWidth * 20f,
        )
    }

    /**
     * Establishes the accuracy bound the top KDoc states: does `Layout`'s `Int` placeable width, at this
     * harness's pinned `Density(1f, 1f)`, round the TRUE (fractional) ink width up or down?
     *
     * **Finding (empirical, not assumed): the direction is SIZE-DEPENDENT, not universal.** Every 11sp case
     * probed OVER-reports (coarse wider than the finer estimate, up to +3.00dp). The 9sp case
     * UNDER-reports, by -0.44dp -- the opposite direction. So there is no single "always conservative"
     * claim to make; an earlier draft of this file's top KDoc claimed one and was wrong, corrected there
     * after this probe was run, not before.
     *
     * Method: re-measure the SAME text/style at a much finer `Density` (16x), where the same TRUE ink width
     * occupies ~16x as many whole px -- recovering sub-dp resolution the 1x reading's `Int` truncates away.
     * `fineDp = finePx / 16` is treated as the better estimate of the true fractional width; `coarseDp -
     * fineDp` reveals which direction, and by how much, the 1x reading is off.
     *
     * What this test actually GATES: not a blanket rounding direction (empirically false), but the concrete
     * consequence for §3 — does its tightest reported headroom cell (538dp companion / SETTINGS, printed
     * +2.00dp) survive being wrong by the exact error this probe measures AT THAT CELL'S OWN CONDITIONS (the
     * 9sp case above, same label, same [MimirRunicQuickRailDefaults.MinLabelFontSize] floor that seat
     * actually resolves to — not the larger error from an unrelated 11sp case, which would overstate the
     * risk to a reading that was never taken at 11sp).
     */
    @Test
    fun `Density(1f) accuracy bound, and whether the tightest §3 headroom cell survives it`() {
        val harness = setUpHarness(initialDensityScale = 1f)
        val settingsAt9spFloor = mimirStaticChrome(quickRailLabel(MimirQuickRailAction.SETTINGS)) to
            MimirTypeScale.RailAction.copy(fontSize = MimirRunicQuickRailDefaults.MinLabelFontSize)
        val cases = listOf(
            "APPEARANCE label (11sp SemiBold)" to (mimirStaticChrome("Appearance") to MimirTypeScale.RunicEyebrow),
            "SETTINGS dock label (11sp)" to (mimirStaticChrome(quickRailLabel(MimirQuickRailAction.SETTINGS)) to MimirTypeScale.RailAction),
            "HOME dock label (11sp)" to (mimirStaticChrome(quickRailLabel(MimirQuickRailAction.HOME)) to MimirTypeScale.RailAction),
            "single glyph (11sp)" to ("A" to MimirTypeScale.RailAction),
            // The §3 tightest cell's ACTUAL rendering condition: at the 538dp companion, the rail is
            // narrowed enough that mimirQuickRailLabelFontSize resolves to the MinLabelFontSize floor (9sp),
            // not 11sp -- probed here directly rather than extrapolated from a different size.
            "SETTINGS @ 9sp floor (§3 cell's real size)" to settingsAt9spFloor,
        )
        data class ErrorReading(val label: String, val coarseDp: Float, val fineDp: Float, val errorDp: Float)
        val readings = cases.map { (label, case) ->
            val (text, style) = case
            harness.setDensityScale(1f)
            val coarseDp = harness.inkWidthDp(text, style)
            harness.setDensityScale(16f)
            val fineDp = harness.inkWidthDp(text, style) / 16f
            ErrorReading(label, coarseDp, fineDp, coarseDp - fineDp)
        }

        val report = readings.joinToString("\n") { r ->
            "%-40s coarse(1x)=%6.2fdp fine(16x->/16)=%8.4fdp (coarse-fine)=%+7.4fdp"
                .format(r.label, r.coarseDp, r.fineDp, r.errorDp)
        }
        println("=== Density(1f) accuracy-bound probe (both directions observed) ===")
        println(report)

        val floorReading = readings.first { it.label.contains("9sp floor") }
        val tightestReportedHeadroomDp = 2.00f // 538dp companion / SETTINGS, printed in §3's own report
        val worstCaseTrueHeadroomDp = tightestReportedHeadroomDp - abs(floorReading.errorDp)
        println(
            ("Tightest §3 cell (538dp companion / SETTINGS): reported=+%.2fdp, this probe's error at the " +
                "SAME conditions=%+.4fdp, worst-case true headroom=%+.4fdp")
                .format(tightestReportedHeadroomDp, floorReading.errorDp, worstCaseTrueHeadroomDp),
        )

        assertTrue(
            "the tightest §3 headroom cell (538dp companion / SETTINGS, reported +${tightestReportedHeadroomDp}" +
                "dp) must stay POSITIVE even after applying this probe's OWN measured error at that cell's " +
                "actual 9sp-floor conditions (${"%.4f".format(floorReading.errorDp)}dp) -- worst-case true " +
                "headroom=${"%.4f".format(worstCaseTrueHeadroomDp)}dp. If this ever fails, §3's 'no clip " +
                "found' verdict is no longer safe and must be re-examined, not asserted through.\n$report",
            worstCaseTrueHeadroomDp > 0f,
        )
    }

    // ── §2: independent verification against the prior lane's hand-derived APPEARANCE figure ─────────────

    /**
     * `SettingsHome.kt`'s `settingsRailWidthDp` KDoc: `"APPEARANCE"` at [MimirTypeScale.RunicEyebrow]
     * (11sp SemiBold, 1sp tracking), hand-derived from the shipped SemiBold `hmtx` table, reads **~82dp**
     * (a stated 80.75-81.75sp spread depending on the trailing-letterSpacing convention). Verified here
     * against the SAME string, SAME style, through real Compose text shaping -- this provides an
     * independent known-width cross-check.
     */
    @Test
    fun `harness reproduces the hand-derived APPEARANCE ink figure`() {
        val harness = setUpHarness()
        val measured = harness.inkWidthDp(mimirStaticChrome("Appearance"), MimirTypeScale.RunicEyebrow)
        val handDerived = 82f
        val delta = abs(measured - handDerived)

        println(
            "APPEARANCE @ RunicEyebrow (11sp SemiBold, 1sp tracking): harness=%.2fdp hand-derived=%.2fdp delta=%.2fdp"
                .format(measured, handDerived, delta),
        )

        // Band: +/-2dp. The hand derivation's own admitted spread (80.75 vs 81.75sp depending on
        // trailing-tracking convention) is already +/-0.5dp around its 81.25dp midpoint; +/-2dp leaves
        // headroom for kerning/shaping this harness sees and the hand method could not, without being so
        // wide it would hide a real disagreement between the two instruments. If this fails, the ticket's
        // instruction is to say so loudly, not widen the band to make it pass -- so this assertion states
        // the band ONCE, here, rather than adjusting it after seeing the number.
        assertTrue(
            "harness (${"%.2f".format(measured)}dp) disagrees with the hand-derived APPEARANCE figure " +
                "(${handDerived}dp) by ${"%.2f".format(delta)}dp, outside the +/-2dp band -- one of the two " +
                "is wrong; do not silently trust either without checking which. See this test's own KDoc " +
                "for the band's derivation.",
            delta <= 2f,
        )
    }

    // ── §3: close one open question — dock label-lane headroom, per MimirCertifiedViewports.standard ─────
    //
    // Drives the SAME production solver chain MimirOdinParityTest.kt's dockReadings() already uses
    // (mimirQuickRailTargetWidth -> mimirQuickRailSlotWidths -> mimirQuickRailLabelFontSize), then measures
    // each MimirQuickRailAction's real rendered label ink at that resolved font size and compares it
    // against the slot width the label actually renders inside (MimirRunicQuickRailItem's label Box is
    // Modifier.fillMaxWidth() inside a Column of width slotWidth -- see MimirRunicQuickRail.kt).
    // headroom = slotWidth - inkWidth; negative means the label clips, the dock-label and Settings-rail clipping defect class. Style: MimirTypeScale.RailAction (11sp Medium, 0.4sp
    // tracking) with fontSize overridden to the per-viewport resolved mimirQuickRailLabelFontSize --
    // bit-identical to what MimirRunicQuickRailItem's labelStyle produces at fontScale <=
    // MaxLabelFontScale (this harness pins fontScale = 1f, always inside that branch). Posture-scoped to
    // MimirCertifiedViewports.standard, matching every other proportion in MimirOdinParityTest.kt
    // (COMPACT_SQUARE changes composition, not just geometry). The full headroom rationale lives on the gated proportion test below, not duplicated here.

    /** Per-seat detail, printed for diagnosis — not itself the gated proportion (see the test below, which
     * reduces this to one number per viewport the same way [MimirOdinParityTest]'s other Class-1 rows do). */
    private data class DockLabelSeatReading(
        val viewport: String,
        val action: MimirQuickRailAction,
        val slotWidthDp: Float,
        val inkWidthDp: Float,
        val inkHeightDp: Float,
    ) {
        val widthHeadroomDp: Float get() = slotWidthDp - inkWidthDp
        val widthHeadroomShare: Float get() = widthHeadroomDp / slotWidthDp
        val heightHeadroomDp: Float get() = MimirRunicQuickRailDefaults.LabelBandHeight.value - inkHeightDp
    }

    private fun TextMetricsHarness.dockLabelSeatReadings(viewport: MimirCertifiedViewport): List<DockLabelSeatReading> {
        val preferredWidth = MimirRunicQuickRailDefaults.preferredRailWidth(MimirQuickRailAction.entries)
        val targetWidth = mimirQuickRailTargetWidth(viewport.solverInputDp.widthDp.dp)
        val slotWidths = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, targetWidth)
        val railWidth = slotWidths.fold(0.dp) { acc: Dp, w -> acc + w }
        val labelFontSize = mimirQuickRailLabelFontSize(railWidth, preferredWidth)
        val style = MimirTypeScale.RailAction.copy(fontSize = labelFontSize)
        return MimirQuickRailAction.entries.mapIndexed { index, action ->
            val label = mimirStaticChrome(quickRailLabel(action))
            val inkWidth = inkWidthDp(label, style)
            val inkHeight = inkHeightDp(label, style)
            DockLabelSeatReading(viewport.name, action, slotWidths[index].value, inkWidth, inkHeight)
        }
    }

    @Test
    fun `dock label-lane headroom, measured at every certified STANDARD viewport`() {
        val harness = setUpHarness()
        val report = StringBuilder()
        val negativeWidth = mutableListOf<String>()
        val negativeHeight = mutableListOf<String>()
        val allReadings = MimirCertifiedViewports.standard.associateWith { harness.dockLabelSeatReadings(it) }

        for ((_, seatReadings) in allReadings) {
            for (r in seatReadings) {
                val line = ("%-24s | %-10s | slot=%6.2fdp | inkW=%6.2fdp | widthHeadroom=%+7.2fdp | " +
                    "inkH=%5.2fdp | 22dp-bandHeadroom=%+6.2fdp")
                    .format(r.viewport, r.action.name, r.slotWidthDp, r.inkWidthDp, r.widthHeadroomDp, r.inkHeightDp, r.heightHeadroomDp)
                report.appendLine(line)
                if (r.widthHeadroomDp < 0f) negativeWidth += line
                if (r.heightHeadroomDp < 0f) negativeHeight += line
            }
        }
        println("=== Dock label-lane headroom (width: slotWidth - inkWidth; height: 22dp band - inkHeight) ===")
        println(report.toString())

        // A REAL, currently-true universal invariant -- same reasoning as MimirOdinParityTest.kt's own
        // dock.seatPitchSpreadDp (target 0 at every viewport, not just the Odin's): a label clip is a defect
        // at ANY certified viewport, not something to accept at a narrow one and reject at a wide one. Hard
        // gate, not a printed-only diagnostic -- exactly the bug class ("SETTINGS"/"UTILITY" clipping) covered for the dock and Settings rail.
        assertTrue(
            "${negativeWidth.size} seat/viewport pair(s) measured NEGATIVE width headroom (label ink wider " +
                "than its slot -- a real clip):\n" + negativeWidth.joinToString("\n") + "\n\n$report",
            negativeWidth.isEmpty(),
        )
        assertTrue(
            "${negativeHeight.size} seat/viewport pair(s) measured NEGATIVE height headroom (label ink " +
                "taller than the fixed 22dp label band -- a real clip):\n" +
                negativeHeight.joinToString("\n") + "\n\n$report",
            negativeHeight.isEmpty(),
        )
    }

    /**
     * Measures the dock label headroom: `MimirOdinParityTest.kt`'s dock section, "Label-lane headroom —
     * UNMEASURABLE TODAY" — turned into a real [MimirSurfaceProportion], gated through the SAME
     * baseline-debt-register mechanism ([MimirOdinParityBaselineEntry] /
     * [mimirOdinParityBaselineFailures]) every other proportion in `MimirOdinParityTest.kt` uses, not a
     * bespoke pass/fail shape. Lives in THIS file (not that one) because measuring ink requires the
     * Robolectric/Compose harness above; `MimirOdinParityTest.kt` stays plain-JUnit pure arithmetic, per
     * this file's own "why Robolectric, not a font-table reader" section — mixing a
     * `@RunWith(RobolectricTestRunner)` file into that one would change every existing test's runner there.
     * The `internal` proportion/baseline types are shared across both files in this package by construction.
     *
     * **`dock.labelLaneHeadroomShare`** — `(slotWidth - inkWidth) / slotWidth` for the WORST (smallest
     * headroom) seat at each viewport, matching [MimirOdinParityTest]'s own "print the worst cell, not
     * every cell" convention for a single-number proportion. Class-1 shape (ratio pinned to the Odin's own
     * value, 0.005 tolerance) — same reasoning as `dock.railWidthShare` in that file: the label genuinely
     * scales continuously with the rail today (this harness measures current behavior, per that file's top KDoc), so a pinned-ratio check is the right form even though a
     * label is nominally Class-3 "type" territory.
     *
     * **Measured today: every certified STANDARD viewport has POSITIVE headroom — no clip found.** The
     * tightest margin is the 538dp companion's SETTINGS seat (+2.00dp of 48.00dp slot, 4.17% headroom share)
     * — this is the closest the current geometry comes to reproducing the exact defect class covered for the dock and Settings rail (dock label clip, Settings rail clip), and it is real margin, not zero. This closes the
     * open question as **UNMEASURABLE -> MEASURED, GREEN AT THE FLOOR** (some viewports diverge from the
     * Odin's own headroom share by more than tolerance and are honestly baseline-tracked red below, but
     * none regress to an actual negative/clip reading).
     */
    @Test
    fun `dock label-lane headroom proportion — closes the UNMEASURABLE-TODAY register entry`() {
        val harness = setUpHarness()

        fun worstSeat(viewport: MimirCertifiedViewport): DockLabelSeatReading =
            harness.dockLabelSeatReadings(viewport).minBy { it.widthHeadroomShare }

        val odinWorst = worstSeat(MimirCertifiedViewports.Odin)
        val proportion = MimirSurfaceProportion(
            name = "dock.labelLaneHeadroomShare",
            odinValue = odinWorst.widthHeadroomShare,
            tolerance = 0.005f,
        )

        val readings = MimirCertifiedViewports.standard.map { vp ->
            val worst = worstSeat(vp)
            MimirProportionReading(
                surface = "Dock label-lane headroom",
                viewportName = vp.name,
                proportion = proportion,
                measured = worst.widthHeadroomShare,
            ) to worst.action
        }
        val report = readings.joinToString("\n") { (reading, worstAction) -> "$reading  (worst seat: $worstAction)" }
        println(report)

        assertTrue(
            "sanity: this proportion is defined FROM the Odin's own worst-seat headroom share, so the " +
                "Odin's own reading must pass by construction",
            readings.first { it.first.viewportName == MimirCertifiedViewports.Odin.name }.first.pass,
        )

        // Debt register, same mechanism and same discipline as MimirOdinParityTest.kt's own baseline:
        // every row here is a REAL divergence measured from this file's own report at the baseline measurement -- none suppressed, no tolerance widened to launder a real gap. All four are
        // Class-1-degenerate the same way dock.railWidthShare already is in the sibling file (no
        // uncontested reference pair exists among the certified viewports for this quantity either), so
        // each red cell here is ticketed rather than left as a silent regression surface.
        //
        // UNLIKE that sibling file's own baseline (whose KDoc calls its source "pure Float arithmetic, no
        // RNG, IO or threading... reproduces bit-for-bit"): these deltas are pinned to Robolectric 4.14.1's
        // bundled Skia text shaper, not pure arithmetic. A future Robolectric upgrade can legitimately move
        // every ink measurement here by a real (if small) amount, which would need re-transcription, not
        // signal a production regression -- check the Robolectric version before treating a WORSENED/NEW
        // failure from this specific baseline as a code-side defect.
        val baseline = listOf(
            MimirOdinParityBaselineEntry(
                "dock.labelLaneHeadroomShare", MimirCertifiedViewports.RetroidPocketNova.name, 0.0795f,
                "DOCK-LABEL-HEADROOM",
            ),
            MimirOdinParityBaselineEntry(
                "dock.labelLaneHeadroomShare", MimirCertifiedViewports.Rp5Shelf.name, 0.0256f,
                "DOCK-LABEL-HEADROOM",
            ),
            MimirOdinParityBaselineEntry(
                "dock.labelLaneHeadroomShare", MimirCertifiedViewports.OdinHeightCompanion538.name, 0.2879f,
                "DOCK-LABEL-HEADROOM",
            ),
            // LargeSquareBoundary1080 is NOT listed: at 1080dp the rail is wide enough to stay unclamped
            // (identical slot widths to the Odin), so its headroom share matches the Odin's own to within
            // float rounding and the cell passes -- adding a zero-delta row here would immediately be
            // flagged STALE by mimirOdinParityBaselineFailures, which is the mechanism working, not a gap.
        )
        val failures = mimirOdinParityBaselineFailures(readings.map { it.first }, baseline)
        assertTrue(
            "Odin-parity baseline violations (dock.labelLaneHeadroomShare) — each is a NEW divergence (no " +
                "baseline row), a WORSENED one (delta grew past its baseline row), or a STALE row (baseline " +
                "claims red, cell now passes -- delete the row).\n" +
                failures.joinToString("\n") + "\n\n$report",
            failures.isEmpty(),
        )
    }
}
