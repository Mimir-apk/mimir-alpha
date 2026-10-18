package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The pad field as a bound slab of tooled leather: the
 * ground is the hero leather the metal plates are struck on (the same [MimirMetalGroundTopStop] to [MimirMetalGroundBottomStop] gradient of the
 * theme's hero cover colours), with a faint diagonal grain, an inner shadow at the bottom and a thin warm rim light on top; and an iron binding
 * of a frame struck in the signature metal (the plates' own rim brush) and a slotted screw in each of the four corners. Every colour is a palette
 * token; the shadows are the one use of black, which is not a theme colour but the absence of light.
 *
 * Two modifiers, because the rune wall is drawn BETWEEN them: the leather behind the runes, the iron over them.
 */
private val SlabCorner = 14.dp

/** The surface: hero leather, grain, inner bottom shadow, top rim light. Drawn once per size into cached paths. */
internal fun Modifier.boundSlabSurface(palette: MimirThemePalette, corner: Dp = SlabCorner): Modifier = drawWithCache {
    val radius = CornerRadius(corner.toPx())
    val hero = palette.materials.hero
    val fill = Brush.verticalGradient(
        MimirMetalGroundTopStop to hero.coverTop,
        MimirMetalGroundBottomStop to hero.coverBottom,
    )
    val shadow = Brush.verticalGradient(
        colorStops = arrayOf(0.0f to Color.Transparent, 0.72f to Color.Transparent, 1.0f to Color.Black.copy(alpha = SlabShadowAlpha)),
    )
    val rimBrush = Brush.verticalGradient(
        colorStops = arrayOf(0.0f to palette.accentBright.copy(alpha = SlabRimAlpha), 0.10f to Color.Transparent),
    )
    // The grain: one path of diagonal hairlines at a fixed spacing, built once per size.
    val grain = Path().apply {
        val gap = GrainGap.dp.toPx()
        val run = size.height
        var x = -run
        while (x < size.width) {
            moveTo(x, size.height)
            lineTo(x + run * GrainSlant, 0f)
            x += gap
        }
    }
    val grainStroke = Stroke(width = GrainWidth.dp.toPx())
    val grainColor = palette.textPrimary.copy(alpha = GrainAlpha)
    val rim = Stroke(width = 1.dp.toPx())
    onDrawBehind {
        drawRoundRect(brush = fill, cornerRadius = radius)
        drawPath(grain, grainColor, style = grainStroke)
        drawRoundRect(brush = shadow, cornerRadius = radius)
        drawRoundRect(brush = rimBrush, cornerRadius = radius, style = rim)
    }
}

/** The iron binding: the metal-edged frame (the plates' rim, in the same brush and strength) and four corner screws, in the theme's metal. Drawn over the runes. */
internal fun Modifier.boundSlabIron(palette: MimirThemePalette, corner: Dp = SlabCorner): Modifier = drawWithCache {
    val width = FrameWidth.dp.toPx()
    // The frame sits wholly inside the slab, as the plates' rim does: stroked at half its width in from the edge.
    val radius = CornerRadius((corner.toPx() - width / 2f).coerceAtLeast(0f))
    val frame = Stroke(width = width)
    val frameBrush = platformConsoleIconBrush(palette)
    val screws = slabScrews(size.width, size.height, ScrewInset.dp.toPx())
    val head = ScrewHead.dp.toPx()
    onDrawBehind {
        drawRoundRect(
            brush = frameBrush,
            topLeft = Offset(width / 2f, width / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - width, size.height - width),
            cornerRadius = radius,
            alpha = MimirMetalRestAlpha,
            style = frame,
        )
        screws.forEach { drawSlabScrew(palette, it.center, head, it.angleDeg) }
    }
}

/** One corner screw: where it sits and the angle its slot is cut at (a different one on each, as hand-set screws are). */
internal class SlabScrew(val center: Offset, val angleDeg: Float)

/** The four corner screws of a slab [width] by [height], each [inset] px in from both edges. Nothing sits at the mid-edges. */
internal fun slabScrews(width: Float, height: Float, inset: Float): List<SlabScrew> = listOf(
    SlabScrew(Offset(inset, inset), ScrewAngles[0]),
    SlabScrew(Offset(width - inset, inset), ScrewAngles[1]),
    SlabScrew(Offset(width - inset, height - inset), ScrewAngles[2]),
    SlabScrew(Offset(inset, height - inset), ScrewAngles[3]),
)

private val ScrewAngles = floatArrayOf(24f, 68f, 112f, 156f)

/**
 * A slotted screw head (): a round head in the theme's metal
 * with a radial highlight up and to the left, a tiny dark inset ring where it sits in the slab, and a slot cut across it at [angleDeg]
 * with a light lower lip. Metal tokens only; black is the shadow.
 */
internal fun DrawScope.drawSlabScrew(palette: MimirThemePalette, center: Offset, radius: Float, angleDeg: Float) {
    val ring = radius * 0.3f
    // The inset shadow ring: the hole the head sits in.
    drawCircle(
        color = Color.Black.copy(alpha = ScrewRingAlpha),
        radius = radius + ring / 2f,
        center = center,
        style = Stroke(width = ring),
    )
    // The head: a radial highlight up and to the left, falling to the deep metal.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(palette.iconGradLight, palette.iconGradMid, palette.iconGradDeep),
            center = center + Offset(-radius * 0.32f, -radius * 0.38f),
            radius = radius * 1.7f,
        ),
        radius = radius,
        center = center,
    )
    drawCircle(color = palette.iconGradDeep, radius = radius, center = center, style = Stroke(width = radius * 0.12f))
    // The slot: a dark groove through the middle at this screw's own angle, with a light lip along its lower edge.
    val angle = Math.toRadians(angleDeg.toDouble())
    val along = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
    val across = Offset(-along.y, along.x)
    val half = radius * 0.8f
    val groove = radius * 0.3f
    val lipShift = across * (groove * 0.62f)
    drawLine(
        color = palette.iconGradLight.copy(alpha = ScrewLipAlpha),
        start = center - along * half + lipShift,
        end = center + along * half + lipShift,
        strokeWidth = radius * 0.12f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = Color.Black.copy(alpha = ScrewSlotAlpha),
        start = center - along * half,
        end = center + along * half,
        strokeWidth = groove,
        cap = StrokeCap.Round,
    )
}

private const val ScrewInset = 13f
private const val ScrewHead = 6f
private const val ScrewRingAlpha = 0.5f
private const val ScrewSlotAlpha = 0.78f
private const val ScrewLipAlpha = 0.55f

private const val SlabShadowAlpha = 0.45f
private const val SlabRimAlpha = 0.35f
private const val GrainGap = 5f
private const val GrainWidth = 2f
private const val GrainSlant = 0.47f
private const val GrainAlpha = 0.015f
/** The frame is the plates' metal rim, a little wider for the slab's scale (the plates' is [MimirMetalRimDp]). */
private const val FrameWidth = 2f

