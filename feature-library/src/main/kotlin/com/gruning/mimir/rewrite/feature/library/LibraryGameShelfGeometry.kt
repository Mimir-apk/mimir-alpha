package com.gruning.mimir.rewrite.feature.library

import kotlin.math.max
import kotlin.math.min

/** The least a hero is ever drawn at, so a body that cannot hold a title and a strip still paints a tile worth tapping. */
private const val LibraryShelfMinimumHeroSide = 64f

/**
 * The geometry of the Library gamelist's game shelf, in the body's own coordinates: x from the screen's left edge (the shelf is laid
 * out as wide as the screen, past the gamelist Column's padding, so its seats stand exactly where Home's do) and y from the top of the
 * body ([heightDp] tall), the slot the Column gives under its header.
 *
 * The Library shelf is the Home shelf , so the sizes are Home's own, solved by [solveHomeShelfGeometry] from
 * the same screen ([widthDp] by [screenHeightDp]): the seat count, the seat envelope, the gap, the gutter, the content edge, the
 * stride and the hero's size. Only the verticals are the body's:
 * - the title block sits at the top of the body, [titleLeftDp] in from the edge (the header above it starts there, so the two share
 *   a left edge), as tall as Home's (the eyebrow lane and one title line) and as wide as the body;
 * - the strip's seats stand on a ledge [bottomLaneDp] above the body's bottom, the room the bottom-right controls (the M button,
 *   the L1/R1 hint) and the ring's bloom need: it is the lane the old destination band and the compact bottom inset reserved. On
 *   a body with room to spare a compact screen centres the strip between the title and that lane, as Home's compact shelf does
 *   between its title and its hint lane; a standard screen keeps the strip seated on the ledge;
 * - a body too short for the title, the strip and the lane shrinks the ART, never the columns: the hero (and so the tile) is
 *   drawn at the room there is, and the seat envelope, the stride and the seat count stay Home's. Seats still stand on the ledge.
 *
 * The bands that belong to Home's dock (the quick rail, the hint and toast lanes) are empty here: the Library has its own bottom
 * chrome and the shelf reads none of them.
 */
internal fun solveLibraryGameShelfGeometry(
    widthDp: Float,
    heightDp: Float,
    screenHeightDp: Float,
    bottomLaneDp: Float,
    titleLeftDp: Float,
    compact: Boolean,
): GameShelfGeometry {
    require(widthDp.isFinite() && heightDp.isFinite() && screenHeightDp.isFinite()) { "viewport dimensions must be finite" }
    // Home's own solve, from the SCREEN's height (not the body's): the hero is capped at 0.48 of it and the title block is as tall as
    // it makes it, so a Library tile is the size of the Home tile on the same screen.
    val home = solveHomeShelfGeometry(
        widthDp = max(widthDp, 320f),
        heightDp = max(screenHeightDp, 300f),
        compact = compact,
    )
    val bloom = home.bloomOutset
    val titleBottom = home.titleBand.height
    val lane = max(bottomLaneDp, bloom)
    val gameBottom = max(heightDp - lane, titleBottom)
    val room = gameBottom - titleBottom
    val seat = home.seatEnvelope
    val heroSide = min(home.heroSide, max(room, LibraryShelfMinimumHeroSide))
    val restingSide = heroSide / HomeShelfSelectedScale
    val spare = room - seat
    val gameTop = if (compact && spare > 0f) titleBottom + spare / 2f else gameBottom - seat
    val titleRight = max(widthDp - titleLeftDp, titleLeftDp)
    // The Home dock's bands, emptied: zero-height lines on the body's bottom edge, so nothing that reads one finds a stale rectangle.
    val noBand = HomeShelfBounds(home.gutter, heightDp, widthDp - home.gutter, heightDp)
    return home.copy(
        viewport = HomeShelfBounds(0f, 0f, widthDp, heightDp),
        heroSide = heroSide,
        restingSide = restingSide,
        quickBottom = 0f,
        quickActionOffsetY = 0f,
        quickActionBounds = HomeShelfBounds(widthDp / 2f, heightDp, widthDp / 2f, heightDp),
        titleBand = HomeShelfBounds(titleLeftDp, 0f, titleRight, titleBottom),
        gameRailBand = HomeShelfBounds(0f, gameTop, widthDp, gameTop + seat),
        navigationHintBand = noBand,
        hintToastBand = noBand,
        quickRailBand = noBand,
    )
}
