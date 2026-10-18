package com.gruning.mimir.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/** The Customize media keyboard's opaque sheet, tagged for the tests that ask where it starts. */
internal const val CustomMediaKeyboardSheetTag = "custom-media-keyboard-sheet"

/** The keyboard's own plate (its glass), tagged for the tests that ask how the sheet stands around it. */
internal const val CustomMediaKeyboardPlateTag = "custom-media-keyboard-plate"

/**
 * Where the keyboard's opaque sheet starts and how much air it keeps around the keyboard, in pixels.
 * [sheetTopPx] is from the top of the box the sheet stands in; [airPx] is the sheet's own top and bottom air around the keys.
 */
internal data class CustomMediaKeyboardSheetPlan(val sheetTopPx: Int, val airPx: Int)

/**
 * The sheet for a keyboard [keyboardPx] tall standing on the floor of a box [heightPx] tall, with the page's top row (Media, the type's chips, Search)
 * from [rowTopPx] to [rowBottomPx] in the same box.
 *
 * The sheet HUGS the keyboard (its edge is the keyboard's own top less the sheet's air: no empty band between the two plates). It starts
 * BELOW the row ([gapPx] clear of it) so the row stays whole and readable above it: with the full [airPx] around the keyboard where that
 * fits, else with the air squeezed toward [minAirPx], so a keyboard that is only a few dp too tall for the room still leaves the row alone. Where it does
 * not fit even then, the sheet covers the row entirely (it starts a gap above the row's top, or higher where the keyboard is taller still), with its full
 * air: its edge is never across the row, which cut the search bar and the chips in half. With the row's place not yet known the
 * sheet is the keyboard's own.
 */
internal fun customMediaKeyboardSheetPlan(
    heightPx: Int,
    keyboardPx: Int,
    rowTopPx: Float?,
    rowBottomPx: Float?,
    gapPx: Int,
    airPx: Int,
    minAirPx: Int,
): CustomMediaKeyboardSheetPlan {
    val fullTop = heightPx - keyboardPx - 2 * airPx
    if (rowTopPx == null || rowBottomPx == null) return CustomMediaKeyboardSheetPlan(fullTop.coerceAtLeast(0), airPx)
    val below = kotlin.math.ceil(rowBottomPx + gapPx).toInt()
    if (fullTop >= below) return CustomMediaKeyboardSheetPlan(fullTop, airPx)
    // The room under the row, less the keyboard, is shared as the sheet's air above and below it, as much as it will give down to the minimum.
    val squeezedAir = (heightPx - keyboardPx - below) / 2
    if (squeezedAir >= minAirPx) {
        val air = squeezedAir.coerceAtMost(airPx)
        return CustomMediaKeyboardSheetPlan(heightPx - keyboardPx - 2 * air, air)
    }
    return CustomMediaKeyboardSheetPlan(minOf(fullTop.toFloat(), rowTopPx - gapPx).toInt().coerceAtLeast(0), airPx)
}

/**
 * The keyboard on its sheet: the keyboard stands on the floor of the box it is given (centred, at its own width), and the sheet is drawn behind it
 * from [customMediaKeyboardSheetPlan]'s top down to the floor. The row's bounds are read where the box is laid out, so a row
 * that moves (a font change, a chip folding) moves the sheet's top with it.
 *
 * **One air on all four sides (sweep 10-09).** The sheet stood the plan's air (12 dp, squeezed toward the minimum under a tall keyboard: about 8 px on Main) above the
 * keyboard's panel, and as wide as the keyboard's whole box, which is the panel plus the keyboard's own 12 dp side inset: about 28 px at the sides. It now hugs the PANEL
 * ([panelWidth] says how wide the keyboard's panel is in a box of a given width) by the plan's air on every side, so the sheet's top, bottom, left and right edges are all
 * the one distance from the panel.
 *
 * Where the keyboard is a few dp too tall for the room under the row it is given that room as its height: the shared keyboard sets its keys to fit the
 * height it has (down to its own least), so the sheet can start under the row and still hug it, instead of covering the row over an empty band. It is only
 * given the room where the room is at least [compressFloorPx] (just above what its keys can be pressed to: the keyboard is never asked for less than it
 * can be); with less, the sheet covers the row and the keyboard stands at its own height, as tall as the panel allows.
 */
@Composable
internal fun CustomMediaKeyboardSheet(
    surface: Color,
    shape: Shape,
    rowTopPx: () -> Float?,
    rowBottomPx: () -> Float?,
    gapPx: Int,
    airPx: Int,
    minAirPx: Int,
    compressFloorPx: Int,
    /** The width of the keyboard's panel in a box [Dp] wide (the shared keyboard's own rule, [com.gruning.mimir.rewrite.core.ui.mimirKeyboardPanelWidth]). */
    panelWidth: (boxWidth: Dp) -> Dp,
    modifier: Modifier = Modifier,
    keyboard: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.clip(shape).background(surface).testTag(CustomMediaKeyboardSheetTag))
            Box(Modifier.testTag(CustomMediaKeyboardPlateTag)) { keyboard() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val roomBelowRow = rowBottomPx()?.let { constraints.maxHeight - kotlin.math.ceil(it + gapPx).toInt() - 2 * minAirPx }
        val keyboardMaxPx = (constraints.maxHeight - 2 * minAirPx).coerceAtLeast(0).let { whole ->
            if (roomBelowRow != null && roomBelowRow >= compressFloorPx) minOf(whole, roomBelowRow) else whole
        }
        val keyboardPlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = keyboardMaxPx),
        )
        val plan = customMediaKeyboardSheetPlan(
            heightPx = constraints.maxHeight,
            keyboardPx = keyboardPlaceable.height,
            rowTopPx = rowTopPx(),
            rowBottomPx = rowBottomPx(),
            gapPx = gapPx,
            airPx = airPx,
            minAirPx = minAirPx,
        )
        // The sheet stands the plan's air out from the keyboard's PANEL on the sides too, not out from the keyboard's whole box.
        val panelPx = panelWidth(keyboardPlaceable.width.toDp()).roundToPx().coerceIn(0, keyboardPlaceable.width)
        val sheetWidthPx = (panelPx + 2 * plan.airPx).coerceAtMost(constraints.maxWidth)
        val sheetPlaceable = measurables[0].measure(
            Constraints.fixed(sheetWidthPx, (constraints.maxHeight - plan.sheetTopPx).coerceAtLeast(0)),
        )
        val left = (constraints.maxWidth - keyboardPlaceable.width) / 2
        layout(constraints.maxWidth, constraints.maxHeight) {
            sheetPlaceable.place((constraints.maxWidth - sheetWidthPx) / 2, plan.sheetTopPx)
            keyboardPlaceable.place(left, (constraints.maxHeight - plan.airPx - keyboardPlaceable.height).coerceAtLeast(0))
        }
    }
}
