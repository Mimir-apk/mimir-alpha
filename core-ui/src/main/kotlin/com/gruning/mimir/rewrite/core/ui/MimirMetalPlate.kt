package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The struck-metal plate: a hero LEATHER ground, a METAL rim stroke
 * in [platformConsoleIconBrush], the glyph (or text) drawn in that same metal, the counter accent warming the top of the ground on a press, and no
 * gleam. It was the media transport plate's private drawing; it lives here now so the transport and the Bottom's header plates are one look, and
 * the transport draws THROUGH [drawMimirMetalPlate] pixel for pixel as it always did (MimirMetalPlatePixelIdentityTest holds that).
 *
 * The pad's bounded ember life (pad9) is an ADD-ON on top of the metal ([drawMetalPlateWithEmber]): a faint ember halo and a warm top rim while its
 * window is open, and at rest nothing but struck metal. No colour literal here: every colour is a palette or hero-material token, and black is only
 * the absence of light.
 */

/** The plate's rim, in the metal. */
val MimirMetalRimDp: Dp = 1.25.dp

/** The ground reads leather from its top stop at 27% of the plate's height to its bottom stop at 75%. */
const val MimirMetalGroundTopStop = 0.27f
const val MimirMetalGroundBottomStop = 0.75f

/** Resting metal strength; a press adds the spec's 20/255. */
const val MimirMetalRestAlpha = 0.82f
const val MimirMetalPressedBoost = 20f / 255f

/** The press tints the ground's top stop with the counter accent at this strength. */
const val MimirMetalPressedGroundAccentAlpha = 0.38f

/** The rim's metal strength at rest, or warmed (pressed, or lit). */
fun mimirMetalRimAlpha(warmed: Boolean): Float = if (warmed) minOf(1f, MimirMetalRestAlpha + MimirMetalPressedBoost) else MimirMetalRestAlpha

/**
 * Draws the plate's ground and rim into the whole of this scope. [rimBrush] is the metal (or the flat metal of a button that cannot act), drawn at
 * [rimAlpha]; [warmed] warms the top of the ground with the counter accent (a press, or a lit key). The corner is Mimir's card corner unless the
 * caller gives its own. Exactly the transport plate's drawing, in the same order, with the same numbers.
 */
fun DrawScope.drawMimirMetalPlate(
    palette: MimirThemePalette,
    rimBrush: Brush,
    rimAlpha: Float,
    warmed: Boolean,
    cornerPx: Float = MimirSpacing.radiusCard.toPx(),
) {
    val hero = palette.materials.hero
    val corner = CornerRadius(cornerPx)
    val groundTop = if (warmed) {
        palette.materials.counterAccent.copy(alpha = MimirMetalPressedGroundAccentAlpha).compositeOver(hero.coverTop)
    } else {
        hero.coverTop
    }
    drawRoundRect(
        brush = Brush.verticalGradient(
            MimirMetalGroundTopStop to groundTop,
            MimirMetalGroundBottomStop to hero.coverBottom,
        ),
        cornerRadius = corner,
    )
    drawMimirMetalRim(rimBrush, rimAlpha, cornerPx)
}

/** The plate's rim alone: a [MimirMetalRimDp] stroke just inside the edge, in [rimBrush] at [rimAlpha]. [drawMimirMetalPlate] ends with it. */
fun DrawScope.drawMimirMetalRim(rimBrush: Brush, rimAlpha: Float, cornerPx: Float) {
    val rim = MimirMetalRimDp.toPx()
    drawRoundRect(
        brush = rimBrush,
        topLeft = Offset(rim / 2f, rim / 2f),
        size = Size(size.width - rim, size.height - rim),
        cornerRadius = CornerRadius((cornerPx - rim / 2f).coerceAtLeast(0f)),
        alpha = rimAlpha,
        style = Stroke(rim),
    )
}


/*
 * Header depth: the RAISED plate, an opt-in for the pad window's header only. A soft cast
 * shadow below the plate (outside its bounds, in the 7 dp the header leaves round the 34 dp plate), a bevel inside the metal rim (a warm light on
 * the top and running down the sides, a dark edge on the bottom and up the sides) and a faint convex shading over the leather. Pressed, the plate SINKS: the shadow
 * collapses, the bevel inverts and the content nudges down [MimirRaisedPressNudgeDp]. Static and draw-phase only: no clock, nothing recomposes.
 * Off by default, so the transport buttons and every other plate draw exactly as they did.
 */

/** The cast shadow rests this far below the plate and blurs this far out, and sinks to the pressed pair when the plate is pressed. */
const val MimirRaisedShadowOffsetDp = 1.75f
const val MimirRaisedShadowBlurDp = 5f
const val MimirRaisedPressedShadowOffsetDp = 0.5f
const val MimirRaisedPressedShadowBlurDp = 2f

/** The shadow's core strength, the slab's own (black is the absence of light, not a theme colour). */
const val MimirRaisedShadowAlpha = 0.45f

/** The bevel's width, the strength of its light and dark edges, and the convex shading's top light and bottom dark. */
const val MimirRaisedBevelDp = 1.25f
const val MimirRaisedBevelLightAlpha = 0.5f
const val MimirRaisedBevelDarkAlpha = 0.5f
const val MimirRaisedConvexLightAlpha = 0.10f
const val MimirRaisedConvexDarkAlpha = 0.20f

/** A pressed plate's content sits this much lower. */
const val MimirRaisedPressNudgeDp = 1f

private const val RaisedShadowRings = 5

/** The cast shadow: a core the plate's size shifted down, and rings that soften it outward. Nothing is clipped; it is drawn outside the plate's bounds. */
internal fun DrawScope.drawRaisedPlateShadow(cornerPx: Float, pressed: Boolean) {
    val offset = (if (pressed) MimirRaisedPressedShadowOffsetDp else MimirRaisedShadowOffsetDp).dp.toPx()
    val blur = (if (pressed) MimirRaisedPressedShadowBlurDp else MimirRaisedShadowBlurDp).dp.toPx()
    val step = blur / RaisedShadowRings
    drawRoundRect(
        color = Color.Black.copy(alpha = MimirRaisedShadowAlpha),
        topLeft = Offset(0f, offset),
        size = Size(size.width, size.height),
        cornerRadius = CornerRadius(cornerPx),
    )
    for (i in RaisedShadowRings downTo 1) {
        val fall = 1f - (i - 1).toFloat() / RaisedShadowRings
        val grow = i * step
        drawRoundRect(
            color = Color.Black.copy(alpha = MimirRaisedShadowAlpha * fall * fall * 0.6f),
            topLeft = Offset(-grow, offset - grow),
            size = Size(size.width + 2f * grow, size.height + 2f * grow),
            cornerRadius = CornerRadius(cornerPx + grow),
            style = Stroke(width = step),
        )
    }
}

/**
 * The raised plate's depth, drawn over its ground and inside its rim: the convex shading, then the bevel. At rest the light is on the top and the dark on the bottom, each fading up or down the sides
 * (a pressed plate: [pressed] inverts the bevel, the inner shadow moving to the top, and halves and flips the convex shading).
 */
internal fun DrawScope.drawRaisedPlateDepth(palette: MimirThemePalette, cornerPx: Float, pressed: Boolean) {
    val rim = MimirMetalRimDp.toPx()
    val inner = Size(size.width - 2f * rim, size.height - 2f * rim)
    val innerCorner = CornerRadius((cornerPx - rim).coerceAtLeast(0f))
    val top = palette.iconGradLight.copy(alpha = MimirRaisedConvexLightAlpha)
    val bottom = Color.Black.copy(alpha = MimirRaisedConvexDarkAlpha)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = if (pressed) listOf(bottom.copy(alpha = bottom.alpha / 2f), top.copy(alpha = top.alpha / 2f)) else listOf(top, bottom),
        ),
        topLeft = Offset(rim, rim),
        size = inner,
        cornerRadius = innerCorner,
    )
    val bevel = MimirRaisedBevelDp.dp.toPx()
    val light = palette.accentBright.copy(alpha = MimirRaisedBevelLightAlpha)
    val dark = Color.Black.copy(alpha = MimirRaisedBevelDarkAlpha)
    val from = if (pressed) dark else light
    val to = if (pressed) light else dark
    drawRoundRect(
        brush = Brush.verticalGradient(0f to from, 0.45f to Color.Transparent, 0.55f to Color.Transparent, 1f to to),
        topLeft = Offset(rim + bevel / 2f, rim + bevel / 2f),
        size = Size(inner.width - bevel, inner.height - bevel),
        cornerRadius = CornerRadius((innerCorner.x - bevel / 2f).coerceAtLeast(0f)),
        style = Stroke(bevel),
    )
}

/** A pressed raised plate's content sits [MimirRaisedPressNudgeDp] lower; read in the layer block, so a press recomposes nothing. */
fun Modifier.mimirRaisedPressNudge(pressed: State<Boolean>): Modifier = this.graphicsLayer {
    translationY = if (pressed.value) MimirRaisedPressNudgeDp.dp.toPx() else 0f
}
/**
 * The plate with the ember life as an add-on: while [ember] (0 to 1) is above zero, a faint halo of the theme's tile glow outside the plate and a warm
 * line along its top rim; at 0 it is [drawMimirMetalPlate] and nothing else.
 */
internal fun DrawScope.drawMetalPlateWithEmber(
    palette: MimirThemePalette,
    metal: Brush,
    cornerPx: Float,
    warmed: Boolean,
    ember: Float,
    raised: Boolean = false,
    pressed: Boolean = false,
) {
    if (ember > 0f) {
        val step = HaloStep.dp.toPx()
        for (i in HaloRings downTo 1) {
            val fade = 1f - (i - 1).toFloat() / HaloRings
            val grow = i * step
            drawRoundRect(
                color = palette.tileGlow.copy(alpha = (palette.tileGlow.alpha * fade * ember * HaloGain).coerceIn(0f, 1f)),
                topLeft = Offset(-grow, -grow),
                size = Size(size.width + 2f * grow, size.height + 2f * grow),
                cornerRadius = CornerRadius(cornerPx + grow),
                style = Stroke(width = step),
            )
        }
    }
    if (raised) drawRaisedPlateShadow(cornerPx, pressed)
    drawMimirMetalPlate(palette, metal, mimirMetalRimAlpha(warmed), warmed, cornerPx)
    if (raised) drawRaisedPlateDepth(palette, cornerPx, pressed)
    if (ember > 0f) {
        val rim = MimirMetalRimDp.toPx()
        val from = cornerPx
        val to = (size.width - cornerPx).coerceAtLeast(from + 1f)
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, palette.accentBright.copy(alpha = (RimWarmth * ember).coerceIn(0f, 1f)), Color.Transparent),
                startX = from,
                endX = to,
            ),
            start = Offset(from, rim * 1.5f),
            end = Offset(to, rim * 1.5f),
            strokeWidth = rim,
        )
    }
}

/** How strongly the ember add-on shows at this moment of the life: nothing at rest (level 0), a gentle breath in its window. */
internal fun mimirMetalEmber(life: EmberLife?): Float {
    if (life == null) return 0f
    val level = life.level.floatValue
    if (level <= 0f) return 0f
    return level * (EmberAddOnFloor + (1f - EmberAddOnFloor) * emberWave(life.breath.floatValue))
}

/**
 * A header plate's ground, rim and ember add-on, behind its content. [lit] (a key with a field waiting) and [pressed] warm the top like a press.
 * Not clipped, so the halo can reach past the plate. The state is read in the draw block only, so nothing here recomposes. [raised] (pad13, off by default)
 * gives the plate depth: a cast shadow, a bevel and convex shading, sinking when pressed.
 */
fun Modifier.mimirMetalPlate(
    palette: MimirThemePalette,
    corner: Dp,
    lit: Boolean,
    life: EmberLife?,
    pressed: State<Boolean>? = null,
    raised: Boolean = false,
): Modifier = this.drawWithCache {
    val metal = platformConsoleIconBrush(palette)
    val cornerPx = corner.toPx()
    onDrawBehind {
        val down = pressed?.value == true
        drawMetalPlateWithEmber(palette, metal, cornerPx, warmed = lit || down, ember = mimirMetalEmber(life), raised = raised, pressed = down)
    }
}

private const val HaloRings = 3
private const val HaloStep = 1.6f
private const val HaloGain = 0.6f
private const val RimWarmth = 0.5f
private const val EmberAddOnFloor = 0.45f
