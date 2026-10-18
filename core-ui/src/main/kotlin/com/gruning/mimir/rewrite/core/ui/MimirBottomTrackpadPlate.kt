package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Opens the full-screen trackpad (or its consent card while Mimir's Accessibility service is off) on the Bottom. The host
 * provides it once around everything the Bottom draws; null means there is no trackpad to offer (a phone, or a device that
 * cannot host one) and no header shows a plate. A composition local, so the lattice, shelf and Activity headers, which sit in
 * different modules and several layers below the Presentation, can all reach the same gateway.
 */
val LocalBottomTrackpadOpen = compositionLocalOf<(() -> Unit)?> { null }

const val BottomTrackpadPlateTag = "bottom-trackpad-plate"

/**
 * The Trackpad plate at the trailing edge of a Bottom header uses the Rune Pad Forge pointer-on-pad
 * icon instead of text to save space. It is a
 * glyph, at every font scale: a mouse-pointer arrow, filled with the theme's bright accent, over a small pad outline with a centre
 * divider, drawn from the palette in the draw phase with Canvas paths (no bitmap). It sits in the same ember plate as the pad's
 * own header plates (a warm top rim light, a faint ember glow inside and out, a slow breath at rest, brighter when pressed), a
 * 48 dp target. It carries no text and is named "Trackpad" for a screen reader.
 */
@Composable
fun BottomTrackpadPlate(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState()
    // pad9: bounded: it animates for a short window at arrival and after a press, then rests (see EmberLife); parked panes stand it down.
    val life = rememberEmberLife(LocalMimirMotionLive.current)
    val latestOpen by rememberUpdatedState(onOpen)
    Box(
        modifier = modifier
            .testTag(BottomTrackpadPlateTag)
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Trackpad"
                onClick(label = "Open the trackpad") { latestOpen(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = { life.arm(); mimirReportPress(source, it) }, onTap = { latestOpen() })
            }
            // After the tap and before the fill: the plate draws in whole while its hit box stays whole.
            .mimirPressResponse(source)
            // The visible plate is inset from the 48 dp target so its ember glow has room; the glow is not clipped.
            .padding(PlateInset)
            .mimirMetalPlate(palette, PlateCorner, lit = false, life = life, pressed = pressed),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(GlyphDp.dp)) { drawPointerOnPad(palette) }
    }
}

/**
 * The pointer-on-a-pad glyph, drawn in the design's 34-unit square scaled to the canvas: the pad's outline with its centre divider
 * (the two buttons' seam) and the pointer arrow over it, all in the header plates' metal, the arrow edged in the leather.
 */
internal fun DrawScope.drawPointerOnPad(palette: MimirThemePalette) {
    val unit = size.minDimension / GlyphUnits
    val stroke = PadStrokeUnits * unit
    // pad11: the glyph is drawn in the header plates' own metal, not in palette ink.
    val metal = platformConsoleIconBrush(palette)
    drawRoundRect(
        brush = metal,
        alpha = MimirMetalRestAlpha,
        topLeft = Offset(PadLeft * unit, PadTop * unit),
        size = Size(PadWidth * unit, PadHeight * unit),
        cornerRadius = CornerRadius(PadCorner * unit),
        style = Stroke(width = stroke),
    )
    drawLine(
        brush = metal,
        alpha = MimirMetalRestAlpha,
        start = Offset(DividerX * unit, DividerTop * unit),
        end = Offset(DividerX * unit, DividerBottom * unit),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    val arrow = Path().apply {
        moveTo(12f * unit, 9f * unit)
        lineTo(12f * unit, 22f * unit)
        lineTo(15.5f * unit, 18.5f * unit)
        lineTo(18f * unit, 24f * unit)
        lineTo(20f * unit, 23f * unit)
        lineTo(17.6f * unit, 17.6f * unit)
        lineTo(22f * unit, 17.4f * unit)
        close()
    }
    drawPath(arrow, brush = metal)
    // A thin edge of the leather ground keeps the arrow apart from the pad's lines.
    drawPath(arrow, color = palette.materials.hero.coverBottom, style = Stroke(width = 0.9f * unit, join = StrokeJoin.Round))
}

private val PlateInset = 4.dp
private val PlateCorner = MimirSpacing.radiusCard
private const val GlyphDp = 28f
private const val GlyphUnits = 34f
private const val PadStrokeUnits = 1.7f
private const val PadLeft = 3f
private const val PadTop = 7f
private const val PadWidth = 28f
private const val PadHeight = 20f
private const val PadCorner = 5f
private const val DividerX = 17f
private const val DividerTop = 21f
private const val DividerBottom = 27f
