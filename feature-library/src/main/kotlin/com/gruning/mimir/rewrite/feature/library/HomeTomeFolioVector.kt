package com.gruning.mimir.rewrite.feature.library

/**
 * The Tome folio's coordinate mechanism: one seat-to-vector mapping, and the anchor the sheet is
 * panned by, both stated in DP so the layout and the transform cannot drift apart.
 *
 * Before this, a seat's position was computed inline as `strideX * column` / `strideY * globalRow`
 * in places that merely happened to agree — the painted cell offset and the `graphicsLayer` pan.
 *
 * Proximity resolution deliberately does NOT live here. `homeTomeFolioNearestSeatAtTravel` in
 * `HomeTomeFolioModels.kt` already measures squared distance to painted seat centres, handles
 * fractional travel, and carries its own tests. An earlier cut of this file reimplemented it and was
 * deleted unused: two answers to "which seat is this point on" is exactly the drift this file exists
 * to remove.
 */
internal data class HomeTomeVector(val x: Float, val y: Float)

/** A seat's discrete address in the folio grid. */
internal data class HomeTomeSeat(val column: Int, val row: Int)

/** Seat-to-seat spacing, cell plus gap, on each axis. */
internal data class HomeTomeStride(val x: Float, val y: Float)

/** The exact top-left of a seat in sheet space, before the sheet is panned. */
internal fun homeTomeSeatVector(seat: HomeTomeSeat, stride: HomeTomeStride): HomeTomeVector =
    HomeTomeVector(x = stride.x * seat.column, y = stride.y * seat.row)

/**
 * Where the window starts on one axis — a PURE function of the focused index.
 *
 * The reported defect was that moving down then up advanced focus faster than the visible tiles.
 * The previous anchor was an integrator:
 *
 *     newStart = priorStart + (focusedRow - previousFocusedRow)   // then clamped
 *
 * Accumulating deltas and clamping the running sum at every step is lossy, so the return trip does
 * not cancel the outbound one. Walk into a clamped edge and back out, and the sheet has quietly
 * shifted relative to the focus that moved it — the tiles travel a different distance than the focus
 * did, which is precisely the desync being reported. It also meant the same seat could sit at two
 * different screen positions depending on how it was reached, so nothing downstream could trust a
 * coordinate.
 *
 * Deriving the anchor from the focused index alone makes the mapping total and reversible: the same
 * focus always yields the same window, so any path back to a seat lands exactly where that seat was.
 * The clamp still applies, but to the ANSWER rather than to an accumulator, so it cannot leak state
 * into the next step.
 */
internal fun homeTomeFolioAnchorIndex(
    focusedIndex: Int,
    lead: Int,
    visible: Int,
    total: Int,
): Int {
    val visibleSpan = visible.coerceAtLeast(1)
    if (total <= visibleSpan) return 0
    val maxStart = total - visibleSpan
    val safeLead = lead.coerceIn(0, (visibleSpan - 1).coerceAtLeast(0))
    return (focusedIndex - safeLead).coerceIn(0, maxStart)
}

/**
 * Where the focused column rides inside the window, preserving the authored look-behind of
 * [HomeTomeFolioTravelLead] so the forward feel is unchanged — only its path-dependence is gone.
 */
internal fun homeTomeFolioColumnLead(visibleColumns: Int): Int =
    HomeTomeFolioTravelLead.coerceIn(0, (visibleColumns - 1).coerceAtLeast(0))

/**
 * The focused row rides the middle of the window.
 *
 * Centre is the only lead that is symmetric, and symmetry is the whole point here: any off-centre
 * lead means travelling down covers a different distance than travelling back up, which is the
 * asymmetry being removed. With an odd row count the middle is exact; with an even one this biases
 * one row toward the top, which is stable and still identical in both directions.
 */
internal fun homeTomeFolioRowLead(visibleRows: Int): Int =
    ((visibleRows - 1) / 2).coerceAtLeast(0)

/**
 * The pan applied to the whole sheet, in the same space as [homeTomeSeatVector].
 *
 * Fractional, because the anchor is animated: mid-glide the sheet sits between two whole seats, and
 * every consumer must agree on where that is rather than each rounding it its own way.
 */
internal fun homeTomeFolioAnchorVector(
    anchorColumn: Float,
    anchorRow: Float,
    stride: HomeTomeStride,
): HomeTomeVector = HomeTomeVector(x = stride.x * anchorColumn, y = stride.y * anchorRow)

/**
 * The Tome container is the RING-INCLUSIVE envelope, not the grid.
 *
 * The grid is what holds the seats; the container is what is actually painted around it, and it
 * extends by [ringClearance] on every side so a focused tile's 14 dp ring reach is never clipped.
 * Anything meant to read as centred "in the Tome" has to be centred on this, because this is the
 * box the eye sees.
 */
internal fun homeTomeFolioContainerWidth(gridWidth: Float, ringClearance: Float): Float =
    gridWidth + ringClearance * 2f

internal fun homeTomeFolioContainerLeft(gridLeft: Float, ringClearance: Float): Float =
    gridLeft - ringClearance

/**
 * The left edge that centres [contentWidth] inside the Tome container.
 *
 * Worth stating why this is not simply the page centre, even though it evaluates to it today.
 * Substituting the layout's own definitions:
 *
 *     gridLeft       = pageLeft + (pageWidth - gridWidth) / 2
 *     containerLeft  = gridLeft - ringClearance
 *     containerWidth = gridWidth + 2 * ringClearance
 *
 *     centre = containerLeft + containerWidth / 2
 *            = pageLeft + (pageWidth - gridWidth) / 2 - ringClearance
 *                       + gridWidth / 2 + ringClearance
 *            = pageLeft + pageWidth / 2
 *
 * Both the gridWidth and the ringClearance terms cancel, so the container's centre IS the page
 * centre — but only because the grid is page-centred and the clearance is symmetric. Deriving it
 * from the container keeps that a consequence rather than an assumption: if the grid ever gains an
 * asymmetric inset, this follows it instead of silently drifting off-centre.
 */
internal fun homeTomeFolioCentredLeft(
    gridLeft: Float,
    gridWidth: Float,
    ringClearance: Float,
    contentWidth: Float,
): Float = homeTomeFolioContainerLeft(gridLeft, ringClearance) +
    (homeTomeFolioContainerWidth(gridWidth, ringClearance) - contentWidth) / 2f

/** Width of the ARRANGE pill, shared by its placement and its size so they cannot disagree. */
internal val HomeTomeArrangePillWidthDp = 72f
