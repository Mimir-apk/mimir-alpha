package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 *  one source of truth for every
 * viewport this tree's suites already certify against, hand-copied independently by `OnboardingScreenFitTest`
 * (app), `HomeShelfGeometryTest`/`HomeBackdropVisualsTest`/`ShelfHeadGeometryTest` (feature-library) and
 * `MimirViewportProfileTest`/`MimirFinalShelfPrimitivesTest` (core-ui) until now.
 *
 * **No cross-module test-fixture wiring exists in this build** (`testFixtures`/inter-module `testImplementation`
 * on another module's test source set) — verified by reading `core-ui/build.gradle.kts`,
 * `feature-library/build.gradle.kts`, `app/build.gradle.kts` before writing this file, no such dependency is
 * declared anywhere. This file is **duplicated verbatim**
 * into `core-ui`, `feature-library` and `app`'s test trees, differing ONLY in the package line and the
 * two imports this file needs when `MimirViewportPosture`/`mimirViewportProfile` are not already in scope
 * (core-ui's own copy needs neither import; feature-library's and app's copies do). Any other diff between
 * the three copies is drift — diff them against each other before trusting a claim sourced from just one.
 *
 * ## The two-box shape, and why the "832.5 vs 833" ambiguity was never a contradiction
 *
 * `OnboardingScreenFitTest.kt:125` uses Odin (832.5 x 468.3dp), while
 * `HomeShelfGeometryTest.kt:16-22` uses Odin (833 x 444dp), without a cross-reference explaining
 * the two boxes. Both are correct, for two DIFFERENT boxes on the same physical
 * device — resolved here by naming, not renumbering:
 * - The height delta (468.3 -> 444, 24.3dp) IS a real inset subtraction (system chrome the shelf's
 *   `BoxWithConstraints` never gets).
 * - The width delta (832.5 -> 833, +0.5dp) CANNOT be an inset — insets only ever subtract — so it is float
 *   rounding of the same width, not a second measurement.
 *
 * So every row below carries [MimirCertifiedViewport.fullViewportDp] (what onboarding's full-screen fit
 * harness measures) and [MimirCertifiedViewport.postInsetShelfDp] (the box a shelf solver —
 * `solveHomeShelfGeometry`/`solveLibraryShelfGeometry` — actually receives) as SEPARATE, both-true columns.
 * Existing exact-value pins calibrated against 833/444 or 832.5/468.3 are left as those literals when ported
 * — renumbering an input 0.5dp is a production-behavior question, not a test-only one, and is explicitly out
 * of this ticket's scope.
 *
 * ## Kind: DEVICE vs SYNTHETIC_BOUNDARY
 *
 * Not every row is a shipped handheld. `538 x 444` and `320 x 300` are boundary/probe fixtures the existing
 * suites already use BY NAME repeatedly (never once as a stand-in for a real device) — they are kept as
 * first-class rows because "certified viewport" in this ticket's own text already covers the boundary
 * fixtures the research's own §6a table lists (the compact-square probe family), not literal shipped hardware
 * alone. [MimirCertifiedViewport.kind] marks the difference so a consumer can filter to real devices only.
 */
internal data class MimirDpBox(val widthDp: Float, val heightDp: Float) {
    init {
        require(widthDp > 0f && heightDp > 0f) { "box dimensions must be positive, was ${widthDp}x$heightDp" }
    }

    val aspect: Float get() = widthDp / heightDp
}

internal enum class MimirCertifiedViewportKind {
    /** A real, named handheld this product ships on. */
    DEVICE,

    /** A boundary/posture probe already used by name across multiple suites — not one physical device. */
    SYNTHETIC_BOUNDARY,
}

internal data class MimirCertifiedViewport(
    val name: String,
    val kind: MimirCertifiedViewportKind,
    /** The whole display, as `OnboardingScreenFitTest` measures it (no shelf chrome subtracted). Null when
     * this tree has never established one for this row. */
    val fullViewportDp: MimirDpBox?,
    /** The box a shelf solver actually receives, after insets/chrome are subtracted. Null when this tree has
     * never established one for this row. */
    val postInsetShelfDp: MimirDpBox?,
    /** Native panel resolution in px, landscape-normalized to match the dp pair's orientation. Null = UNKNOWN. */
    val resolutionPx: Pair<Int, Int>?,
    /** DisplayMetrics-declared density, dpi. Null = UNKNOWN. */
    val declaredDensityDpi: Int?,
    /** Millimetres per dp on this panel. Null = UNKNOWN. See each row's [provenance] for how derived. */
    val mmPerDp: Float?,
    /** Physical panel diagonal, inches. Null = UNKNOWN. */
    val physicalDiagonalIn: Float?,
    val posture: MimirViewportPosture,
    /** How every value in this row is known — device-read, spec-derived, or an explicitly-flagged assumption. */
    val provenance: String,
) {
    /** The box a solver-level matrix test should feed a shelf solver: the real post-inset box if this tree
     * has one, else the full viewport as the best available stand-in — matching what every existing solver
     * test already does for rows with no established post-inset box (never invented fresh by this file). */
    val solverInputDp: MimirDpBox
        get() = postInsetShelfDp ?: fullViewportDp
            ?: error("$name has neither a post-inset nor a full-viewport box on file")
}

internal object MimirCertifiedViewports {

    val Odin = MimirCertifiedViewport(
        name = "Odin",
        kind = MimirCertifiedViewportKind.DEVICE,
        fullViewportDp = MimirDpBox(832.5f, 468.3f),
        postInsetShelfDp = MimirDpBox(833f, 444f),
        resolutionPx = 1920 to 1080,
        declaredDensityDpi = 369,
        mmPerDp = 0.1595f,
        physicalDiagonalIn = 6.0f,
        posture = MimirViewportPosture.STANDARD,
        provenance = "Full viewport [V OnboardingScreenFitTest.kt:86-91,125]: the reference viewport " +
            "handheld, 1920x1080px @369dpi declared -> 832.5x468.3dp landscape. Post-inset shelf box " +
            "[V HomeShelfGeometryTest.kt:16-22 and many pins across that file] — see this file's own KDoc " +
            "for why 833x444 is a second real box, not a conflicting re-measurement of the same one. " +
            "mmPerDp/diagonal [derived] — the source documentation " +
            "flags the 6.0in 16:9 panel as ASSUMED from the dp figures and \"~6in\", not measured; if that " +
            "assumption is wrong this row's mmPerDp/diagonal move, the dp figures do not.",
    )

    // The QA emulator, read off emulator-5580 on 2026-09-22 during the Alpha pass. It earns a row for one
    // reason: at 411.4dp it is SHORTER than every other STANDARD viewport in this file — the Odin is
    // 468.3dp and the Nova 480dp — and height is what onboarding's no-scroll layout spends. Its aspect is
    // 914.3/411.4 = 2.22, far above CompactSquareMaximumAspect (1.12), so it is STANDARD and takes the
    // NO-SCROLL branch: every onboarding page must FIT in 411.4dp with nothing to hide an overflow.
    //
    // Before this row existed the suite's shortest STANDARD box was the Odin's 468.3dp, and WELCOME
    // overflowed this one by enough to slice the Begin pill in half on a real screen while every test
    // stayed green. That gap is the whole point of the row.
    //
    // It is a DEVICE row rather than a synthetic one because it is a real, reachable fixture the Alpha
    // pass drives; it is not a shipping target and no product claim rests on it.
    val QaEmulator = MimirCertifiedViewport(
        name = "QA emulator (sdk_gphone64_x86_64)",
        kind = MimirCertifiedViewportKind.DEVICE,
        fullViewportDp = MimirDpBox(914.3f, 411.4f),
        postInsetShelfDp = null,
        resolutionPx = 2400 to 1080,
        declaredDensityDpi = 420,
        mmPerDp = null,
        physicalDiagonalIn = null,
        posture = MimirViewportPosture.STANDARD,
        provenance = "Device-read 2026-09-22 off emulator-5580 during the Alpha candidate pass: " +
            "`wm size` -> 1080x2400 portrait-native, `wm density` -> 420, driven in landscape so the " +
            "viewport is 2400x1080px -> 914.3x411.4dp. Derived the same way the Odin and Nova rows are " +
            "(physical size converted at declared density, no inset subtraction) so all three compare. " +
            "mmPerDp/physicalDiagonalIn are null and not invented: an emulator has no panel, and this " +
            "file's own rule is that unmeasured physical quantities stay absent rather than guessed.",
    )

    val RetroidPocketNova = MimirCertifiedViewport(
        name = "Retroid Pocket Nova",
        kind = MimirCertifiedViewportKind.DEVICE,
        fullViewportDp = MimirDpBox(640f, 480f),
        postInsetShelfDp = null,
        resolutionPx = 1280 to 960,
        declaredDensityDpi = 320,
        mmPerDp = 0.1429f,
        physicalDiagonalIn = 4.5f,
        posture = MimirViewportPosture.STANDARD,
        provenance = "Full viewport [V OnboardingScreenFitTest.kt:126-137], device-read 2026-08-21: model " +
            "\"Retroid Pocket Nova\" (Moorechip \"kalama\"), Android 13/SDK 33, panel reported " +
            "960x1280px portrait-native @320dpi declared (355.6 real dpi reported), " +
            "landscape-rotated here to 1280x960 to match the landscape dp pair. " +
            "Post-inset shelf box is UNKNOWN in this tree; measurement is still needed. " +
            "Every existing solver test that calls " +
            "e.g. solveHomeShelfGeometry(640f, 480f) is using the FULL viewport as an UNVERIFIED stand-in " +
            "for the real post-inset box — carried forward here via [MimirCertifiedViewport.solverInputDp], " +
            "not newly invented by this fixture. mmPerDp is derived; diagonal reported directly " +
            "as 4.5in with a 4:3 aspect (reported, not measured here).",
    )

    /**
     * AYN Thor MAIN display (displayId=0), added to this fixture on 2026-09-21.
     *
     * **Deliberately NOT in [all]** -- see that list's own doc for why a row can be certified without
     * joining the matrix set.
     */
    val AynThorMain = MimirCertifiedViewport(
        name = "AYN Thor (main display)",
        kind = MimirCertifiedViewportKind.DEVICE,
        fullViewportDp = MimirDpBox(832.5f, 468.3f),
        postInsetShelfDp = null,
        resolutionPx = 1920 to 1080,
        declaredDensityDpi = 369,
        mmPerDp = null,
        physicalDiagonalIn = null,
        posture = MimirViewportPosture.STANDARD,
        provenance = "Device-read from device logcat, 2026-09-19 20:21:52.424 -- a system " +
            "WindowMetricsNotification line for displayId=0: densityDpi=369, displayWidth=1920, " +
            "displayHeight=1080, smallestScreenWidthDp=468, DisplayMetrics{density=2.30625}. The dp box is " +
            "derived exactly the way the Odin row's is (panel px / declared density): 1920/2.30625 = 832.5, " +
            "1080/2.30625 = 468.3 -- the Odin's full viewport to the decimal, so an Odin-calibrated fit " +
            "number describes this panel too. Closes the standing \"the Thor's exact dp are UNKNOWN in this " +
            "tree\" note (OnboardingScreenFitTest.kt) for the MAIN DISPLAY ONLY. " +
            "POST-INSET BOX REMAINS UNKNOWN and is deliberately null: the 55px top inset on that log line " +
            "belongs to ANOTHER app's window, and nothing in that capture states what Mimir's own window " +
            "receives. mmPerDp and diagonal are UNKNOWN -- not derived from a \"~6in\" assumption here. " +
            "NOT the Thor's second screen: displayId=4 is a separate 1240x1080 panel, reported at TWO " +
            "densities 0.2s apart in the same capture (320/sw540, then 369/sw468), and stays uncertified " +
            "until it is read deliberately. One transitional line at 20:21:52.786 reported displayId=0 " +
            "itself as 1240 wide -- unexplained, not adjudicated here, and the reason this row is named for " +
            "a panel rather than for \"what the Thor is\".",
    )

    val Rp5Shelf = MimirCertifiedViewport(
        name = "RP5-class shelf (device identity DISPUTED — see provenance, not adjudicated by this ticket)",
        kind = MimirCertifiedViewportKind.DEVICE,
        fullViewportDp = null,
        postInsetShelfDp = MimirDpBox(700f, 350f),
        resolutionPx = null,
        declaredDensityDpi = null,
        mmPerDp = null,
        physicalDiagonalIn = null,
        posture = MimirViewportPosture.STANDARD,
        provenance = "Post-inset box only [V, e.g. HomeShelfGeometryTest.kt:22,55,194, ShelfHeadGeometryTest" +
            ".kt:20, MimirFinalShelfPrimitivesTest.kt via width alone, many pins]. Device identity is " +
            "DISPUTED and remains UNKNOWN here: HomeBackdropVisualsTest.kt " +
            "(~line 303, \"RP5 700x350 exact pin\") calls this box " +
            "\"RP5\"; OnboardingScreenFitTest.kt:91 states \"Other handhelds' exact dp (RP5, Thor) are " +
            "UNKNOWN in this tree and are not invented here.\" Full viewport dp, resolution, density and " +
            "physical size are all UNKNOWN; measurement is still needed. " +
            "NOTE: this row's aspect (700/350 = 2.0) describes the POST-INSET BOX, not a real 2:1 device " +
            "panel — do not read it as the panel's physical aspect.",
    )

    private fun compactSquareRow(side: Float) = MimirCertifiedViewport(
        name = "compact-square ${side.toInt()}dp",
        kind = MimirCertifiedViewportKind.SYNTHETIC_BOUNDARY,
        fullViewportDp = MimirDpBox(side, side),
        postInsetShelfDp = MimirDpBox(side, side),
        resolutionPx = null,
        declaredDensityDpi = null,
        mmPerDp = null,
        physicalDiagonalIn = null,
        posture = MimirViewportPosture.COMPACT_SQUARE,
        provenance = "RG-Rotate-class posture probe, not one named device " +
            "[V OnboardingScreenFitTest.kt:138-141, MimirViewportProfileTest.kt:10-13]. No distinct " +
            "post-inset box has ever been modelled for this posture in this tree — both columns deliberately " +
            "carry the same box, not a placeholder for a missing measurement.",
    )

    val CompactSquare360 = compactSquareRow(360f)
    val CompactSquare411 = compactSquareRow(411f)
    val CompactSquare480 = compactSquareRow(480f)
    val CompactSquare720 = compactSquareRow(720f)

    /** The RG Rotate's own side, bar hidden (what emulator-5554 at `wm density 204` shows). Compact review 2026-09-27. */
    val CompactSquare564 = compactSquareRow(564.7f)

    /** RG Rotate is the compact-square reference device used by these tests. */
    val RgRotate = MimirCertifiedViewport(
        name = "RG Rotate",
        kind = MimirCertifiedViewportKind.DEVICE,
        fullViewportDp = MimirDpBox(564.7f, 564.7f),
        postInsetShelfDp = MimirDpBox(564.7f, 540.7f),
        resolutionPx = 720 to 720,
        declaredDensityDpi = 204,
        mmPerDp = null,
        physicalDiagonalIn = null,
        posture = MimirViewportPosture.COMPACT_SQUARE,
        provenance = "Device-read 2026-09-27: 720x720px, density " +
            "override 204 -> 720/1.275 = 564.7dp square, Android 12/SDK 31, one display. Post-inset box " +
            "ASSUMED, NOT MEASURED: a status bar was reported over the Home/Library title, and " +
            "24dp is the platform's usual bar, so the shelf box is taken as 564.7x540.7 until the RG's own " +
            "WindowInsets.statusBars is read. mmPerDp and diagonal are UNKNOWN.",
    )

    val OdinHeightCompanion538 = MimirCertifiedViewport(
        name = "538dp companion (Odin post-inset height, narrower width probe)",
        kind = MimirCertifiedViewportKind.SYNTHETIC_BOUNDARY,
        fullViewportDp = null,
        postInsetShelfDp = MimirDpBox(538f, 444f),
        resolutionPx = null,
        declaredDensityDpi = null,
        mmPerDp = null,
        physicalDiagonalIn = null,
        posture = MimirViewportPosture.STANDARD,
        provenance = "Not a named device — a narrower-width probe at the Odin's own post-inset height " +
            "[V HomeShelfGeometryTest.kt:121-127 (\"companion\"), HomeBackdropVisualsTest.kt:262,281, " +
            "MimirViewportProfileTest.kt:21, ShelfHeadGeometryTest.kt:21, MimirFinalShelfPrimitivesTest.kt " +
            "538dp target-width floor test]. It exists specifically to pin the STILL-OWED rail/hint " +
            "collision range below the 2026-08-19/20 rail-resize fix's crossover.",
    )

    val MinimumFloor320x300 = MimirCertifiedViewport(
        name = "320x300 minimum floor",
        kind = MimirCertifiedViewportKind.SYNTHETIC_BOUNDARY,
        fullViewportDp = null,
        postInsetShelfDp = MimirDpBox(320f, 300f),
        resolutionPx = null,
        declaredDensityDpi = null,
        mmPerDp = null,
        physicalDiagonalIn = null,
        posture = MimirViewportPosture.COMPACT_SQUARE,
        provenance = "solveHomeShelfGeometry's own declared minimum (`require(widthDp >= 320f && " +
            "heightDp >= 300f)`) [V HomeShelfGeometry.kt:178-179]; the 320dp width alone recurs as " +
            "MimirFinalShelfPrimitivesTest's \"320dp constrained\" rail fixture. LOAD-BEARING, NOT OBVIOUS: " +
            "mimirViewportProfile(320f, 300f) classifies this COMPACT_SQUARE (aspect 1.0667 <= 1.12, side " +
            "320 <= CompactSquareMaximumSideDp 760) even though every existing solver test that uses it " +
            "treats it as a plain box with no posture awareness at all — verified by this file's own " +
            "self-test below (mimirCertifiedViewportPostureMatchesTheClassifier).",
    )

    val LargeSquareBoundary1080 = MimirCertifiedViewport(
        name = "1080x1080 large-square boundary",
        kind = MimirCertifiedViewportKind.SYNTHETIC_BOUNDARY,
        fullViewportDp = null,
        postInsetShelfDp = MimirDpBox(1080f, 1080f),
        resolutionPx = null,
        declaredDensityDpi = null,
        mmPerDp = null,
        physicalDiagonalIn = null,
        posture = MimirViewportPosture.STANDARD,
        provenance = "Recurs across HomeShelfGeometryTest.kt (x2), HomeBackdropVisualsTest.kt, " +
            "MimirViewportProfileTest.kt as the proof that a square viewport is not automatically " +
            "COMPACT_SQUARE — 1080 exceeds CompactSquareMaximumSideDp (760f) [V MimirViewportProfile.kt:" +
            "23-24,31-32], so this stays STANDARD despite aspect 1.0.",
    )

    /**
     * The viewports every matrix, golden and parity suite iterates. Order is stable (declaration order) so
     * matrix reports read the same way run to run.
     *
     * Membership is not "every row this file names" -- it is every row that asks a solver a DISTINCT
     * question. Two named rows are deliberately outside it, and both reasons are worth reading before
     * adding another.
     *
     * [AynThorMain]: its box is the Odin's to the decimal (same panel, same declared
     * density), so it would duplicate a dozen pins without adding a question -- `HomeLaunchCardLayoutGolden`
     * already pins this exact box as the Odin's golden row. Worse, its [MimirCertifiedViewport.solverInputDp]
     * would be a full-PANEL stand-in (its own post-inset box being unknown) fed to shelf solvers where the
     * Odin row already supplies this chassis's REAL post-inset box, 24.3dp shorter -- so every pin it added
     * would be a true statement about a box no shelf on that device receives. It joins this list the day
     * the Thor's own post-inset box is device-read. Until then, suites that want the device by name
     * reference it directly, which is what `OnboardingAccessGlideOverflowTest` does.
     *
     * [QaEmulator] (row added 2026-09-22): it is a QA fixture, not a shipping target, and this list is what
     * every golden and parity suite treats as the product's certified set -- a pin calibrated against an
     * emulator would be a product claim about a device nobody ships. It also has no post-inset box, so its
     * [MimirCertifiedViewport.solverInputDp] would be the same full-PANEL stand-in objected to above, fed to
     * every shelf, browser and primitive solver the other copies of this file drive. The one DISTINCT
     * question it asks is a full-viewport one -- does every onboarding page FIT in 411.4dp, the shortest
     * STANDARD height on file -- and `OnboardingScreenFitTest`'s matrix asks it by name. The commit that
     * added the row left this list alone without saying so here, which read as an oversight until the
     * reasons above were written down; a second suite that wants the shortest STANDARD box takes it by name
     * the same way.
     */
    val all: List<MimirCertifiedViewport> = listOf(
        Odin, RetroidPocketNova, Rp5Shelf,
        CompactSquare360, CompactSquare411, CompactSquare480, CompactSquare720, CompactSquare564, RgRotate,
        OdinHeightCompanion538, MinimumFloor320x300, LargeSquareBoundary1080,
    )

    val standard: List<MimirCertifiedViewport> = all.filter { it.posture == MimirViewportPosture.STANDARD }
    val compactSquare: List<MimirCertifiedViewport> =
        all.filter { it.posture == MimirViewportPosture.COMPACT_SQUARE }
}

/** The fixture's own guard against drift from the classifier it quotes numbers against. */
class MimirCertifiedViewportsSelfTest {
    @Test
    fun everyRowsPostureMatchesTheProductionClassifier() {
        MimirCertifiedViewports.all.forEach { viewport ->
            val box = viewport.solverInputDp
            val classified = mimirViewportProfile(box.widthDp, box.heightDp).posture
            assertEquals(
                "${viewport.name} (${box.widthDp}x${box.heightDp}) fixture posture must match " +
                    "mimirViewportProfile's own classification, or this fixture has drifted from production",
                viewport.posture,
                classified,
            )
        }
    }

    @Test
    fun everyRowHasAtLeastOneBox() {
        MimirCertifiedViewports.all.forEach { viewport ->
            assertEquals(
                "${viewport.name} must resolve a solverInputDp without throwing",
                viewport.solverInputDp.widthDp > 0f,
                true,
            )
        }
    }
}
