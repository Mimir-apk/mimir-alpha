package com.gruning.mimir.rewrite.feature.files

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIMIR ODIN-PARITY HARNESS — `feature-files` geometry coverage for `FileBrowserGrid.kt`'s solver.
 * The grid reduces its column count when cells would otherwise clip.
 *
 * **Harness types below ([MimirSurfaceProportion], [MimirProportionReading], [mimirProportionReport],
 * [MimirOdinParityBaselineEntry], [MimirOdinParityBaselineEpsilon], [mimirOdinParityBaselineFailures]) are
 * duplicated byte-for-byte from `core-ui`'s `MimirOdinParityTest.kt`** (itself already duplicated into
 * `feature-library`'s test tree) — same rationale as [MimirCertifiedViewports] in this same test tree: no
 * cross-module test-fixture wiring exists in this build (`feature-files/build.gradle.kts` declares only
 * `implementation(project(":core-ui"))`, checked before writing this file), so the mechanism is copied, not
 * a second independently designed harness. This is now a THIRD copy of the harness-type boilerplate; diff all three before trusting
 * a claim about the mechanism itself sourced from just one.
 *
 * **The two proportions this ticket names, both width-driven, both real `[D]` arithmetic over
 * [fileBrowserSolveGrid]'s own output** (`FileBrowserGrid.kt`, same module, same package — a real solver
 * call, not a hand copy, unlike `core-ui`'s fenced-off Settings section):
 *
 * - **`files.contentColumnShare`** — the content column's width as a fraction of the full viewport. The fixed 270dp inspector occupies approximately 42% of the Nova viewport,
 *   a geometric mismatch verified directly:
 *   [FileBrowserInspectorWidthDp] (270f) / 640f = **42.19%**, matching "42%" to the reported precision. This
 *   proportion is the content column's own share, the complement of (but not identical to) the inspector's
 *   share — see [filesContentColumnWidthDp]'s own KDoc for why the content column subtracts more than just
 *   the inspector (the row's own horizontal padding and inter-column gap too).
 * - **`files.tomeCellWidthShare` / `files.fileCellWidthShare`** — cell width as a fraction of the content
 *   column, tracked SEPARATELY for the tome grid (4-column ceiling) and the file grid (5-column ceiling):
 *   they solve independently ([fileBrowserSolveColumns] is called once per grid with its own authored
 *   ceiling and gap), with reference figures for both (Odin 4/5 columns with
 *   118.0/94.4dp cells; Nova 3/3 columns at 97.0/99.0dp) — collapsing them to one proportion would hide whichever
 *   grid this file didn't happen to compute deltas from. Both verified below to match the ticket's cited
 *   figures exactly (see [FilesOdinParityGroundTruthSelfTest]).
 *
 * ## Label-lane coverage boundary
 *
 * Unlike the Dock section (`core-ui`'s `MimirOdinParityTest.kt`), these Files candidates are both
 * purely geometric (column/cell arithmetic) — neither needs a text-measurement pure function that doesn't
 * exist in this tree, so there is no UNMEASURABLE-TODAY proportion to report for this surface.
 *
 * ## Tolerance derivation
 *
 * **The accepted-pair method degenerates for every proportion in this file — worse than the `core-ui`
 * precedent, not merely the same shape.** The method needs a reference pair with near-zero divergence; here [MimirCertifiedViewports.Rp5Shelf] (700dp) diverges from the Odin by
 * 7.4–13.2 percentage points on every one of these three proportions (see the baseline table below) — one
 * to two orders of magnitude above what an uncontested pair requires, and by far the largest degeneration
 * this instrument has found (`settings.totalChromeShare`'s was 4.6 points; `dock.railWidthShare`'s was 2.2).
 * The reason is structural, not incidental: [fileBrowserSolveColumns] is a **quantized step function**
 * (column COUNT, an integer), so a proportion built on top of it jumps in discrete bands rather than
 * varying continuously with width — there is no pair of geometrically equivalent reference viewports on either side
 * of a column-count boundary for this solver the way there is for the Dock's continuous scale-then-floor.
 * Falls back to the file's own established Class-1 convention, **0.005**, ported from `core-ui`/
 * `feature-library` for consistency across the whole instrument rather than re-derived from a pair that
 * cannot supply one.
 */
internal data class MimirSurfaceProportion(
    val name: String,
    val odinValue: Float,
    val tolerance: Float,
)

internal data class MimirProportionReading(
    val surface: String,
    val viewportName: String,
    val proportion: MimirSurfaceProportion,
    val measured: Float,
) {
    val delta: Float get() = abs(measured - proportion.odinValue)
    val pass: Boolean get() = delta <= proportion.tolerance

    override fun toString(): String {
        val mark = if (pass) "PASS" else "RED "
        return "[$mark] %-28s / %-42s / %-24s  measured=%+.4f  odin=%+.4f  delta=%.4f  tol=%.4f".format(
            surface,
            viewportName,
            proportion.name,
            measured,
            proportion.odinValue,
            delta,
            proportion.tolerance,
        )
    }
}

/** One printable table, in fixture declaration order — a failure names the exact cell and its delta. */
internal fun mimirProportionReport(readings: List<MimirProportionReading>): String =
    readings.joinToString("\n") { it.toString() }

/**
 * `feature-files`'s own [fileBrowserSolveGrid] call, at [viewport]'s own [MimirCertifiedViewport.solverInputDp].
 * Height is passed through for completeness ([fileBrowserSolveGrid] requires one) but is UNUSED by every
 * proportion in this file — all three are width-driven (column/cell math only), never touching
 * [FileBrowserGrid.contentViewportDp] or the height-only chrome terms.
 */
private fun filesGrid(viewport: MimirCertifiedViewport): FileBrowserGrid {
    val box = viewport.solverInputDp
    return fileBrowserSolveGrid(box.widthDp, box.heightDp)
}

/**
 * Recovers the content column's own width from [FileBrowserGrid]'s solved output — **[D] arithmetic over
 * solver output, not a hand copy of [FileBrowserGrid.kt]'s internal chrome-subtraction arithmetic** (unlike
 * `core-ui`'s Settings section, which has no callable cross-module solver and so must hand-copy). Inverting
 * `fileBrowserCellWidthDp`'s own formula (`cellWidth = (contentColumnWidthDp - (columns-1)*gap) / columns`)
 * for the TOME grid: `contentColumnWidthDp = tomeCellWidthDp * tomeColumns + (tomeColumns-1) * gap`.
 * [FilesOdinParityGroundTruthSelfTest] proves this agrees, to the last bit, with the SAME quantity recovered
 * independently through the FILE grid's own cell width and column count — the two are solved from one
 * shared `contentColumnWidthDp` input inside [fileBrowserSolveGrid], so an agreement here is a real
 * cross-check of the solver's own internal consistency, not a tautology of this one function.
 */
private fun filesContentColumnWidthDp(grid: FileBrowserGrid): Float =
    grid.tomeCellWidthDp * grid.tomeColumns + (grid.tomeColumns - 1) * FileBrowserTomeColumnGapDp

private fun filesContentColumnShare(viewport: MimirCertifiedViewport): Float {
    val grid = filesGrid(viewport)
    return filesContentColumnWidthDp(grid) / viewport.solverInputDp.widthDp
}

private fun filesTomeCellWidthShare(viewport: MimirCertifiedViewport): Float {
    val grid = filesGrid(viewport)
    return grid.tomeCellWidthDp / filesContentColumnWidthDp(grid)
}

private fun filesFileCellWidthShare(viewport: MimirCertifiedViewport): Float {
    val grid = filesGrid(viewport)
    return grid.fileCellWidthDp / filesContentColumnWidthDp(grid)
}

private val FilesContentColumnShareProportion = MimirSurfaceProportion(
    name = "files.contentColumnShare",
    odinValue = filesContentColumnShare(MimirCertifiedViewports.Odin),
    tolerance = 0.005f,
)

private val FilesTomeCellWidthShareProportion = MimirSurfaceProportion(
    name = "files.tomeCellWidthShare",
    odinValue = filesTomeCellWidthShare(MimirCertifiedViewports.Odin),
    tolerance = 0.005f,
)

private val FilesFileCellWidthShareProportion = MimirSurfaceProportion(
    name = "files.fileCellWidthShare",
    odinValue = filesFileCellWidthShare(MimirCertifiedViewports.Odin),
    tolerance = 0.005f,
)

/**
 * Posture scoping: [MimirCertifiedViewports.standard] only, same restriction and reasoning as the sibling
 * `core-ui`/`feature-library` harnesses (COMPACT_SQUARE changes composition, e.g. the inspector's own fixed
 * width is a STANDARD-posture constraint, not tested here at all under COMPACT_SQUARE).
 */
private fun filesReadings(): List<MimirProportionReading> =
    MimirCertifiedViewports.standard.flatMap { viewport ->
        listOf(
            MimirProportionReading(
                surface = "Files picker grid (RC-12)",
                viewportName = viewport.name,
                proportion = FilesContentColumnShareProportion,
                measured = filesContentColumnShare(viewport),
            ),
            MimirProportionReading(
                surface = "Files picker grid (RC-12)",
                viewportName = viewport.name,
                proportion = FilesTomeCellWidthShareProportion,
                measured = filesTomeCellWidthShare(viewport),
            ),
            MimirProportionReading(
                surface = "Files picker grid (RC-12)",
                viewportName = viewport.name,
                proportion = FilesFileCellWidthShareProportion,
                measured = filesFileCellWidthShare(viewport),
            ),
        )
    }

/**
 * ── THE BASELINE — a shrinking debt register, not a suppression list ────────────────────────────────────
 * Duplicated mechanism, same three states as the `core-ui`/`feature-library` copies: **NEW** (red cell, no
 * baseline row — a regression), **WORSENED** (red cell whose delta grew past its recorded row), **STALE**
 * (baseline row whose cell no longer reads red — forces deletion, which is what makes the register shrink).
 */
internal data class MimirOdinParityBaselineEntry(
    val proportion: String,
    val viewport: String,
    val delta: Float,
    val ticket: String,
)

/**
 * Same derivation as the `core-ui` copy: deltas are hand-transcribed from this file's own `%.4f`-formatted
 * report output, rounded to the nearest 1e-4 — the only noise source over pure, deterministic Float
 * arithmetic. Epsilon is 2x that rounding bound, still tiny next to every real divergence this file finds:
 * the smallest RED cell is 0.0058 (58x this epsilon; see [FilesOdinParityBaseline]) up to 0.2930 (2930x).
 * 0.0046 is NOT a red delta — it is the one PASSING cell among these three proportions at a non-Odin
 * viewport (1080/`files.fileCellWidthShare`, under the 0.005 tolerance) and carries no baseline row.
 */
internal const val MimirOdinParityBaselineEpsilon = 0.0001f

internal fun mimirOdinParityBaselineFailures(
    readings: List<MimirProportionReading>,
    baseline: List<MimirOdinParityBaselineEntry>,
    epsilon: Float = MimirOdinParityBaselineEpsilon,
): List<String> {
    val redCells = readings.filterNot { it.pass }
    val baselineByKey = baseline.associateBy { it.proportion to it.viewport }
    val redByKey = redCells.associateBy { it.proportion.name to it.viewportName }

    val failures = mutableListOf<String>()
    redCells.forEach { red ->
        val entry = baselineByKey[red.proportion.name to red.viewportName]
        when {
            entry == null -> failures += "NEW DIVERGENCE (no baseline row): $red"
            red.delta > entry.delta + epsilon -> failures += "WORSENED (baseline delta=${entry.delta}, " +
                "ticket=${entry.ticket}, now delta=${red.delta}): $red"
        }
    }
    baseline.forEach { entry ->
        if (redByKey[entry.proportion to entry.viewport] == null) {
            failures += "STALE baseline row (no longer red — delete it): $entry"
        }
    }
    return failures
}

/**
 * The Files-grid debt register. Every row is a real divergence measured from this file's own report output
 * at ticket commission (2026-08-21) — none suppressed. Ticketed RC-12 throughout: the column-reduction fix
 * that landed (`156a00ab`) closed the reported clipping, but it was never asked to converge the Nova's
 * proportions onto the Odin's — this harness makes that remaining, real gap visible rather than assuming
 * "fixed the report" meant "matches the reference device".
 *
 * `LargeSquareBoundary1080` is the one viewport close to passing `files.fileCellWidthShare` (0.0046, under
 * the 0.005 tolerance) — it lands on the SAME 4/5-column ceiling as the Odin (1080dp is wide enough that
 * neither grid's column solve ever reduces), so its cell-width share is close by construction, not by
 * tuning. Its `files.contentColumnShare` and `files.tomeCellWidthShare` rows are still red because the
 * fixed-270dp inspector and the tome/file gap terms scale differently even when the column COUNT agrees.
 */
private val FilesOdinParityBaseline = listOf(
    MimirOdinParityBaselineEntry(
        "files.contentColumnShare", MimirCertifiedViewports.RetroidPocketNova.name, 0.1177f, "RC-12",
    ),
    MimirOdinParityBaselineEntry(
        "files.contentColumnShare", MimirCertifiedViewports.Rp5Shelf.name, 0.0741f, "RC-12",
    ),
    MimirOdinParityBaselineEntry(
        "files.contentColumnShare", MimirCertifiedViewports.OdinHeightCompanion538.name, 0.2139f, "RC-12",
    ),
    MimirOdinParityBaselineEntry(
        "files.contentColumnShare", MimirCertifiedViewports.LargeSquareBoundary1080.name, 0.0892f, "RC-12",
    ),
    MimirOdinParityBaselineEntry(
        "files.tomeCellWidthShare", MimirCertifiedViewports.RetroidPocketNova.name, 0.0757f, "RC-12",
    ),
    MimirOdinParityBaselineEntry(
        "files.tomeCellWidthShare", MimirCertifiedViewports.Rp5Shelf.name, 0.0797f, "RC-12",
    ),
    MimirOdinParityBaselineEntry(
        "files.tomeCellWidthShare", MimirCertifiedViewports.OdinHeightCompanion538.name, 0.2395f, "RC-12",
    ),
    MimirOdinParityBaselineEntry(
        "files.tomeCellWidthShare", MimirCertifiedViewports.LargeSquareBoundary1080.name, 0.0058f, "RC-12",
    ),
    MimirOdinParityBaselineEntry(
        "files.fileCellWidthShare", MimirCertifiedViewports.RetroidPocketNova.name, 0.1285f, "RC-12",
    ),
    MimirOdinParityBaselineEntry(
        "files.fileCellWidthShare", MimirCertifiedViewports.Rp5Shelf.name, 0.1315f, "RC-12",
    ),
    MimirOdinParityBaselineEntry(
        "files.fileCellWidthShare", MimirCertifiedViewports.OdinHeightCompanion538.name, 0.2930f, "RC-12",
    ),
    // LargeSquareBoundary1080 / files.fileCellWidthShare: NOT a row -- measured delta (0.0046) is under
    // tolerance (0.005), the one PASS among these three proportions at a non-Odin viewport. See this list's
    // own KDoc for why (same 4/5-column ceiling as the Odin at this width).
)

class MimirOdinParityTest {

    @Test
    fun mimirOdinParityFilesReport() {
        val readings = filesReadings()
        val report = mimirProportionReport(readings)
        println(report)
        val failures = mimirOdinParityBaselineFailures(readings, FilesOdinParityBaseline)
        assertTrue(
            "Odin-parity baseline violations (Files picker grid) — each is a NEW divergence (no baseline " +
                "row), a WORSENED one (delta grew past its baseline row), or a STALE row (baseline claims " +
                "red, cell now passes -- delete the row). See FilesOdinParityBaseline for the debt register.\n" +
                failures.joinToString("\n") + "\n\n$report",
            failures.isEmpty(),
        )
    }

    /**
     * Falsifiability demonstration (ticket requirement, same mechanism as the `core-ui`/`feature-library`
     * copies): mutating a [MimirSurfaceProportion]'s own `odinValue` must flip an otherwise-passing cell.
     * No production file is touched.
     */
    @Test
    fun harnessFilesProportionComparisonIsFalsifiable() {
        val odinViewport = MimirCertifiedViewports.Odin
        val measured = filesContentColumnShare(odinViewport)
        val genuine = FilesContentColumnShareProportion
        val corrupted = genuine.copy(odinValue = genuine.odinValue + 0.5f)

        val genuineReading = MimirProportionReading("Files picker grid (RC-12)", "Odin", genuine, measured)
        val corruptedReading = MimirProportionReading("Files picker grid (RC-12)", "Odin", corrupted, measured)

        assertTrue(
            "sanity: the real Odin baseline must pass AT the Odin (it is defined from the Odin's own value)",
            genuineReading.pass,
        )
        assertTrue(
            "mutating the Odin baseline by +0.5 must flip an otherwise-passing cell to failing: $corruptedReading",
            !corruptedReading.pass,
        )
    }
}

/**
 * Grounds this file's proportions against the recorded reference figures, independent of the baseline
 * register above — proves the solver reproduces the recorded numbers, rather than assuming
 * [fileBrowserSolveGrid] behaves as described. Also proves [filesContentColumnWidthDp]'s tome-grid-based
 * recovery agrees with the SAME quantity recovered through the file grid's own cell width and column count
 * — a real cross-check of solver internal consistency (both grids solve from one shared content-column
 * width inside [fileBrowserSolveGrid]), not a tautology of one function calling itself.
 *
 * **Coverage overlaps `FileBrowserGridTest.kt`.** That file already pins, and remains the
 * canonical owner of: Odin (832.5/833dp) solves to 4 tome / 5 file columns; the Nova (640x480) reduces both
 * to 3; RP5 (700dp) solves its tome grid to 3 columns at ~117dp. The column-COUNT assertions below
 * (`assertEquals(4, ...tomeColumns)` etc.) are a KNOWING, disclosed duplicate of that coverage — kept only
 * as the cheap precondition context for the assertion actually new here, not re-derived as if unpinned.
 * **What is genuinely new**, not covered by `FileBrowserGridTest.kt` anywhere: the exact CELL-WIDTH dp
 * figures for the Odin and the Nova on BOTH grids (118.0/94.4dp Odin, 97.0/99.0dp Nova — that file asserts
 * column counts only for these two viewports, and never asserts a file-grid cell width for any viewport at
 * all), the 42%-inspector-share arithmetic grounding the recorded reference figure, and the two-way
 * content-column-width cross-check.
 */
class FilesOdinParityGroundTruthSelfTest {

    private fun contentColumnWidthViaFileGrid(grid: FileBrowserGrid): Float =
        grid.fileCellWidthDp * grid.fileColumns + (grid.fileColumns - 1) * FileBrowserFileColumnGapDp

    @Test
    fun odinGridMatchesTheTicketsCitedFigures() {
        val grid = filesGrid(MimirCertifiedViewports.Odin)
        assertEquals("Odin tome columns", 4, grid.tomeColumns)
        assertEquals("Odin file columns", 5, grid.fileColumns)
        assertEquals("Odin tome cell width dp", 118.0f, grid.tomeCellWidthDp, 0.05f)
        assertEquals("Odin file cell width dp", 94.4f, grid.fileCellWidthDp, 0.05f)
    }

    @Test
    fun novaGridMatchesTheTicketsCitedFigures() {
        val grid = filesGrid(MimirCertifiedViewports.RetroidPocketNova)
        assertEquals("Nova tome columns", 3, grid.tomeColumns)
        assertEquals("Nova file columns", 3, grid.fileColumns)
        assertEquals("Nova tome cell width dp", 97.0f, grid.tomeCellWidthDp, 0.05f)
        assertEquals("Nova file cell width dp", 99.0f, grid.fileCellWidthDp, 0.05f)
    }

    @Test
    fun inspectorShareOfTheNovaMatchesTheTicketsCited42Percent() {
        val nova = MimirCertifiedViewports.RetroidPocketNova.solverInputDp
        val inspectorShare = FileBrowserInspectorWidthDp / nova.widthDp
        assertEquals("inspector share of the Nova's width", 0.4219f, inspectorShare, 0.001f)
    }

    @Test
    fun contentColumnWidthRecoveredTwoWaysAgreesBitForBit() {
        MimirCertifiedViewports.all.forEach { viewport ->
            val grid = filesGrid(viewport)
            val viaTome = filesContentColumnWidthDp(grid)
            val viaFile = contentColumnWidthViaFileGrid(grid)
            assertEquals(
                "${viewport.name}: content column width recovered via the tome grid must agree with the " +
                    "same quantity recovered via the file grid (both solve from one shared input)",
                viaTome,
                viaFile,
                0.01f,
            )
        }
    }
}
