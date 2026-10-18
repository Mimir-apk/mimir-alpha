package com.gruning.mimir.rewrite.feature.files

/**
 * RC-12: reduce the column count where the authored grid clips. Device-confirmed on the Nova (640x480dp): folder
 * labels break mid-word (`Audiobo/oks`, `Docume/nts`) because this folio was painting the Odin's authored
 * 4/5-column grid into a content column the Nova's own chrome only leaves 315dp wide.
 *
 * **One solver, one answer.** Columns, cell width, row pitch and the page-stride viewport are not four
 * independent constants -- the authored 164.5dp tome row pitch is a *function* of the authored 118dp tome
 * cell ([FileBrowserTomeAspectRatio] times the cell width, plus the meta line and the two inter-row gaps;
 * see [FileBrowserTomeRowPitchDp]'s own derivation). A fix that reduces the column count but leaves the
 * pitch frozen makes [fileBrowserPageStride] reason over rows that are painted ~17% shorter than the
 * lattice believes -- a page jump that overshoots the very grid it is supposed to be paging. [FileBrowserGrid]
 * is the single value every consumer -- render chunking, the ragged-row fillers, the focus row model, the
 * virtual bounds, the lazy item index, the row pitch list and tracker registration -- reads its geometry
 * from, so none of them can disagree about how many columns exist.
 *
 * **The column solver may only REDUCE, never exceed, the authored count** ([FileBrowserTomeColumns] /
 * [FileBrowserFileColumns] stay the ceiling), and floors at 1.
 */
internal data class FileBrowserGrid(
    val tomeColumns: Int,
    val fileColumns: Int,
    val tomeCellWidthDp: Float,
    val fileCellWidthDp: Float,
    val tomeRowPitchDp: Float,
    val fileRowPitchDp: Float,
    val contentViewportDp: Float,
    /**
     * The surface's own measured width, rounded exactly as [fileBrowserSolveGrid] rounds it.
     *
     * **Carried rather than recovered.** The search screen's clearance function
     * ([fileBrowserSearchQueryClearanceDp]) needs the whole surface width, not the folio's content
     * column, and the two differ by terms this solver takes as INPUTS -- `inspectorWidthDp` is a
     * parameter, so inverting `contentColumnWidthDp` back to a width would silently assume the default
     * 270dp and be wrong for any caller that passed something else. Passing the same grid makes that
 * possible without a second `BoxWithConstraints`.
     */
    val surfaceWidthDp: Float,
) {
    companion object {
        /**
         * The controller's pre-measurement default. [FileBrowserController] is constructed OUTSIDE
         * composition (`app/.../FileBrowserStore.kt`), so [FileBrowserController.load] can run before
         * [FileBrowserSurface] has measured anything at all. Seeding this to the Odin's own solved grid
         * -- not a copy of the old hand-authored constants -- means the FIRST frame behaves exactly as
         * today (same 833x468dp landscape viewport the adoption spec names), and it is PROVEN identical to the old
         * constants by `FileBrowserGridTest`'s solver-matrix case rather than merely asserted.
         */
        val Authored: FileBrowserGrid = fileBrowserSolveGrid(833f, 468f)
    }
}

/**
 * The narrowest cell this design already authors on this very surface -- the Odin's own 94.3dp FILE cell
 * at 5 columns (see [FileBrowserGrid.Authored]'s derivation) -- rounded DOWN to 90. Not a taste number:
 * the 4dp of slack is what keeps the 832.5/833dp viewport-width ambiguity (and any float rounding either
 * candidate introduces) from ever flipping the Odin's own column solve below its authored count.
 */
internal const val FileBrowserMinCellDp = 90f

// ── Fixed chrome subtracted from the measured surface to reach the content column width / viewport
// height. Every term names the FileBrowserSurface.kt construct that owns it, on
// FileBrowserFocusRegistration's own "named by construct, not line number" discipline -- these files are
// edited together and line numbers move, but a Row's own padding does not.

/** [FileBrowserSurface]'s content `Row` -- `.padding(start = 18.dp, end = 18.dp)`. */
internal const val FileBrowserContentRowHorizontalPaddingDp = 36f

/** The content `Row`'s own `horizontalArrangement = Arrangement.spacedBy(14.dp)`. */
internal const val FileBrowserContentRowGapDp = 14f

/** The content `LazyColumn`'s `contentPadding = PaddingValues(end = 5.dp, ...)`. */
internal const val FileBrowserContentListEndPaddingDp = 5f

/** `FileBrowserHeader`'s `.height(52.dp)`. */
internal const val FileBrowserHeaderHeightDp = 52f

/** The header divider `Box`'s `.height(1.dp)`. */
internal const val FileBrowserHeaderDividerHeightDp = 1f

/**
 * The 11 dp gap above every screen in the content row (`fileBrowserScreenTopGap`) but the folio's own listing, which has 14 dp of ring room inside its bounds instead (audit
 * 2026-10-14, F-18: the row itself used to pad 11 dp above that, and the two stacked). The solver still counts it, as it still counts the 10 dp below: the page stride is
 * an estimate of how many rows a screen holds, pinned as such, not a measurement of the listing's bounds.
 */
internal const val FileBrowserContentTopPaddingDp = 11f

/** The content `LazyColumn`'s `contentPadding = PaddingValues(..., bottom = 10.dp)`. */
internal const val FileBrowserContentBottomPaddingDp = 10f

/** `FileBrowserFooterLegend`'s `.height(44.dp)`. */
internal const val FileBrowserFooterHeightDp = 44f

/** `TomeTileRow`'s `horizontalArrangement = Arrangement.spacedBy(12.dp)`. */
internal const val FileBrowserTomeColumnGapDp = 12f

/** `FileTileRow`'s `horizontalArrangement = Arrangement.spacedBy(9.dp)`. */
internal const val FileBrowserFileColumnGapDp = 9f

/**
 * The child-count caption's line under a folder tile, in dp: **the one number the tile paints and the grid counts.**
 * Audit 2026-10-14 (F-18): the grid had counted 17 dp here since the caption was added, while the tile drew its 10 sp
 * word on the ambient style's 24 sp line (31 dp at font 1.3), so every tome row was 7 dp (14 dp at 1.3) taller than the
 * lattice and the second row's caption ended below the listing. The tile now sets the line to exactly this many dp (a
 * density-fixed line, not a scaling one: the word grows with the font, its line does not), so model and paint agree at
 * every font scale and the row pitch stays [FileBrowserTomeRowPitchDp]'s own 164.5.
 */
internal const val FileBrowserTomeMetaHeightDp = 17f

/**
 * `TomeTile`'s own `Column`'s `verticalArrangement` spacing (4dp) + the child-count meta line's text
 * height ([FileBrowserTomeMetaHeightDp]) + the content `LazyColumn`'s
 * `verticalArrangement` spacing (9dp) -- the three additive terms in the tome row pitch that are NOT a
 * function of the cell width. See [FileBrowserTomeRowPitchDp]'s own derivation.
 */
internal const val FileBrowserTomeMetaAndGapDp = 4f + FileBrowserTomeMetaHeightDp + 9f

/**
 * The dp cell width when [contentColumnWidthDp] is divided [columns] wide with [gapDp] between cells --
 * `Modifier.weight(1f)`'s own arithmetic, restated so the solver can evaluate it before anything composes.
 */
internal fun fileBrowserCellWidthDp(contentColumnWidthDp: Float, columns: Int, gapDp: Float): Float =
    (contentColumnWidthDp - (columns - 1) * gapDp) / columns

/**
 * The largest column count in `1..authoredColumns` whose [fileBrowserCellWidthDp] is still at least
 * [minCellDp] -- i.e. "how many columns of at least the minimum legible size fit", capped at the authored
 * ceiling and floored at 1. Walking DOWN from [authoredColumns] rather than up from 1 is what makes "may
 * only reduce, never exceed" structural rather than a separate check: the first count this loop accepts
 * is by construction the largest one that fits.
 */
internal fun fileBrowserSolveColumns(
    contentColumnWidthDp: Float,
    authoredColumns: Int,
    gapDp: Float,
    minCellDp: Float = FileBrowserMinCellDp,
): Int {
    for (columns in authoredColumns downTo 1) {
        if (fileBrowserCellWidthDp(contentColumnWidthDp, columns, gapDp) >= minCellDp) return columns
    }
    return 1
}

/**
 * Solves columns, cell width, row pitch and the page-stride viewport TOGETHER from one measured surface
 * box -- see this file's own KDoc for why a solver that only touches columns is worse than the defect it
 * fixes.
 *
 * @param viewportWidthDp the folio surface's own measured width (`BoxWithConstraints.maxWidth.value` in
 *   production).
 * @param viewportHeightDp the folio surface's own measured height.
 * @param fileTileHeightDp how tall a file tile is at the font scale in force ([fileBrowserFileTileHeightDp]): the design's 98 dp at font 1.0, taller where the type is. The file row
 *   pitch is this plus the gap, so the page stride and the scroll estimates follow the rows that are really drawn. Defaults to the authored 98 dp, which is what every solve before
 *   the font scale was in it was.
 * @param inspectorWidthDp [FileBrowserInspectorWidthDp] by default -- the standing inspector's fixed
 *   270dp width is a fixed design constraint this solver takes as an INPUT, not a value it owns. Parameterised so a future design change that resizes the inspector needs no
 *   change here, only a different value passed in.
 *
 * **Rounds the measured width/height to the nearest whole dp before dividing** (`Math.round`, explicitly
 * -- NOT `kotlin.math.round`, which ties to even rather than up, and would silently make 832.5dp and
 * 833dp resolve to different cells). This is what collapses the Odin's own 832.5dp/833dp viewport-width
 * ambiguity: both are the same physical screen measured two different ways, and both must solve to the
 * identical grid. `FileBrowserGridTest`'s solver-matrix case proves the two inputs agree, rather than
 * asserting it.
 */
internal fun fileBrowserSolveGrid(
    viewportWidthDp: Float,
    viewportHeightDp: Float,
    inspectorWidthDp: Float = FileBrowserInspectorWidthDp,
    fileTileHeightDp: Float = FileBrowserFileTileMinHeightDp,
): FileBrowserGrid {
    val widthDp = Math.round(viewportWidthDp).toFloat()
    val heightDp = Math.round(viewportHeightDp).toFloat()

    val contentColumnWidthDp = widthDp -
        FileBrowserContentRowHorizontalPaddingDp -
        FileBrowserContentRowGapDp -
        inspectorWidthDp -
        FileBrowserContentListEndPaddingDp

    val tomeColumns = fileBrowserSolveColumns(contentColumnWidthDp, FileBrowserTomeColumns, FileBrowserTomeColumnGapDp)
    val fileColumns = fileBrowserSolveColumns(contentColumnWidthDp, FileBrowserFileColumns, FileBrowserFileColumnGapDp)

    val tomeCellWidthDp = fileBrowserCellWidthDp(contentColumnWidthDp, tomeColumns, FileBrowserTomeColumnGapDp)
    val fileCellWidthDp = fileBrowserCellWidthDp(contentColumnWidthDp, fileColumns, FileBrowserFileColumnGapDp)

    // The tome cover's height is a function of ITS OWN cell width (the design's fixed 1:1.14 aspect
    // ratio) -- so the row pitch must be re-derived here, never read off a frozen constant. The file
    // row's pitch is NOT a function of cell width: every FileTile carries the same minimum height
    // regardless of how wide its cell is (see FileBrowserFileRowPitchDp's own KDoc), so it stays a plain
    // number even as fileColumns changes -- but it is a function of the font scale, which is why it is
    // [fileTileHeightDp] here and not the authored constant.
    val tomeRowPitchDp = tomeCellWidthDp / FileBrowserTomeAspectRatio + FileBrowserTomeMetaAndGapDp

    val contentViewportDp = heightDp -
        FileBrowserHeaderHeightDp -
        FileBrowserHeaderDividerHeightDp -
        FileBrowserContentTopPaddingDp -
        FileBrowserContentBottomPaddingDp -
        FileBrowserFooterHeightDp

    return FileBrowserGrid(
        tomeColumns = tomeColumns,
        fileColumns = fileColumns,
        tomeCellWidthDp = tomeCellWidthDp,
        fileCellWidthDp = fileCellWidthDp,
        tomeRowPitchDp = tomeRowPitchDp,
        fileRowPitchDp = fileTileHeightDp + FileBrowserRowGapDp,
        contentViewportDp = contentViewportDp,
        surfaceWidthDp = widthDp,
    )
}
