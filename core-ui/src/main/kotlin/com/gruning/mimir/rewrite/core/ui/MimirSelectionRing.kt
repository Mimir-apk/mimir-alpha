package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * One focus ring on every surface: Home's tile ring ([mimirFocusTileRing]) exposed as a modifier any element can wear:
 * the same three strokes, the same light, the same clock, in one colour, `tileFocusRing` .
 * It lifts the wiring onboarding's pills had grown privately -- progress on [mimirFocusRingSpec], the settle gleam,
 * the flare -- so no surface writes it again.
 */

/**
 * The shapes the ring takes. Each carries its element's OWN clip corner (the ring and clip share one corner symbol rather than separate literals); a pill and a circle measure theirs at draw.
 */
sealed interface MimirRingShape {
    /** An action pill: a capsule. Stands off by [MimirFilledControlRingGap] when the pill is filled in the ring's colour. */
    data object Pill : MimirRingShape

    /** A card or tile of art. [corner] is the card's clip token: [MimirBoxArtCorner], `radiusCard`, [MimirSwitchTileCorner]. */
    data class Card(val corner: Dp) : MimirRingShape

    /** A list row. [corner] is the row's clip token. */
    data class Row(val corner: Dp) : MimirRingShape

    /** A dense key: the bright stroke only, reaching [MimirDenseKeyRingReach]; no gleam and no flare. */
    data class Key(val corner: Dp) : MimirRingShape

    /** A circle or an icon: the radius is half the shorter side, measured at draw. */
    data object Circle : MimirRingShape
}

/**
 * What light plays on a settled ring: nothing, one plain sweep, or a game's three sweeps with a sparkle after each.
 * Under calm shine ([LocalMimirCalmShine]) a sweep is nothing and a game's gleam is one crossing; the ring decides
 * none of that itself, the primitives it takes its light from do.
 */
enum class MimirRingGleam { None, Sweep, Game }

/**
 * The selection ring. **Paint only:** it never registers focus and never handles a tap; chain [mimirFocusable] and
 * `mimirActivatable` beside it. It goes BEFORE the clip in the chain, because the ring is drawn outside the element:
 * `mimirFocusable -> mimirSelectionRing -> clip -> background -> content`. A site's own alpha layer, if it has one,
 * goes after the ring and uses [CompositingStrategy.ModulateAlpha]; the default fade composites through a buffer the
 * size of the element, which cuts every ring.
 *
 * - **Colour** is [MimirThemePalette.tileFocusRing]. Glass resolves it through `liquidGlassContentPalette`.
 * - **Clock:** [mimirFocusRingSpec], 420 ms at settle and 140 ms while the pad is held; a snap under reduced motion.
 * - **Draw-phase reads only:** progress, glint and flare are read inside the draw, so a focus change recomposes nothing.
 * - **Gap:** [MimirFilledControlRingGap] for a [filled] pill, row or card (gold on gold); 0 everywhere
 *   else. A filled element reserves [MimirFilledControlRingReach] at a clip edge, not [MimirFocusTileRingReach].
 * - **Resting outline:** a hairline half a dp inside the edge that fades out in step with the ring, alpha x (1 - progress),
 *   so the ring becomes the edge and nothing reads as a double line.
 * - **Wash:** the house 0.16 accent ([MimirFocusWashAlpha]) inside an unfilled pill or row, doubled on a flare's first
 *   frame. The modifier paints the element's resting [fill] itself, under the wash and OUTSIDE its rest dim, because a
 *   fill the site paints after the clip would cover the wash and sit inside the dim: pass the fill here and drop the
 *   site's `.background`. A filled pill passes its fill too (it takes no wash); only what a site draws after this
 *   modifier -- its label, its content -- dims at rest.
 * - **[active]** is a plate, never a second ring: a 1 dp in-bounds stroke at 0.55 and a 0.14 fill in [accent], for a
 *   row that is open but not focused (the Settings rail). The stroke fades as the ring arrives; the fill yields to the
 *   wash, so focused-and-active is the wash plus the ring.
 * - **No focus scale.** The ring is sized to layout bounds; a scaled element grows past its own ring.
 * - **[restingAlpha]** below 1 dims the element at rest on one ModulateAlpha layer placed after the ring.
 * - **[inset]** draws the whole treatment around the bounds deflated by that much: a capsule inside a row that sits
 *   between beams, or a row inside a glass panel that clips before its padding.
 *
 * @param identity the element's FocusKey or content id, never an index. It keys every animation here, and the gleam
 *   and the flare, so a recycled slot cannot carry another element's ring (the ghost trail).
 * @param armed the host's arrival: gleam and flare wait until the surface settles. It must read
 *   snapshot state (an `Animatable`'s value), because the gleam waits through a snapshot
 *   flow. [MimirFocusAlwaysArmed] for a host with no arrival.
 * @param flare true only on an action group of four or fewer pills or buttons.
 * @param gleam a game takes [MimirRingGleam.Game]; a button, a row or a platform tile one sweep; a key none. Calm
 *   shine ([LocalMimirCalmShine]) is applied inside the gleam primitives: a sweep then draws nothing, a game one
 *   crossing.
 * @param palette overridable so a theme preview rings in the palette it previews.
 * @param accent the wash and the plate's colour; the palette's accent unless a surface reads its accent differently
 *   (the rail's readable accent on a bright field).
 * @param outlineColor the resting hairline; the palette's outline, or a glass row's `glassRestingOutline`.
 */
@Composable
fun Modifier.mimirSelectionRing(
    focused: Boolean,
    identity: Any,
    shape: MimirRingShape,
    filled: Boolean = false,
    flare: Boolean = false,
    armed: () -> Boolean = MimirFocusAlwaysArmed,
    active: Boolean = false,
    restingOutline: Boolean = false,
    gleam: MimirRingGleam = MimirRingGleam.Sweep,
    restingAlpha: Float = 1f,
    fill: Brush? = null,
    inset: Dp = 0.dp,
    palette: MimirThemePalette = LocalMimirPalette.current,
    accent: Color = Color.Unspecified,
    outlineColor: Color = Color.Unspecified,
    /**
     * For an etched chip (pass the same value to `mimirEtchedChipSurface`): the bright stroke is 1.5 dp and sits flush on the edge, where the chip's own
     * rim is, and the rim takes the ring's colour as the ring arrives, so the focus reads as ONE band growing outward and never as two outlines
     * . See [mimirFocusTileRingPaint]'s `flush`.
     */
    edgeFlush: Boolean = false,
): Modifier = key(identity) {
    val reducedMotion = mimirReducedMotion()
    // The ring on the ring clock; the rest dim on the focus response, as every treatment before it.
    val progress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (reducedMotion) snap<Float>() else mimirFocusRingSpec<Float>(),
        label = "mimirSelectionRing",
    )
    val plate by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = if (reducedMotion) snap<Float>() else mimirFocusRingSpec<Float>(),
        label = "mimirSelectionPlate",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else restingAlpha,
        animationSpec = if (reducedMotion) snap<Float>() else tween(MimirFocusRingDurationMs, easing = MimirFocusEasing),
        label = "mimirSelectionRest",
    )
    val dense = shape is MimirRingShape.Key
    val settledOn = if (focused) identity else null
    // Calm shine is read inside the primitives, never chosen here: a Sweep then draws nothing, a Game one crossing.
    val glint = when {
        dense || gleam == MimirRingGleam.None -> MimirFocusRingNoGlint
        gleam == MimirRingGleam.Game -> rememberMimirFocusGameGleam(settledOn = settledOn, armed = armed)
        else -> rememberMimirFocusSettleGlint(settledOn = settledOn, armed = armed)
    }
    val heat = if (flare && !dense) {
        rememberMimirFocusFlare(settledOn = settledOn, armed = armed)
    } else {
        MimirFocusRingNoFlare
    }
    val ringColor = palette.tileFocusRing
    val washColor = if (accent.isSpecified) accent else palette.accent
    val hairlineColor = if (outlineColor.isSpecified) outlineColor else palette.outline
    val washed = mimirSelectionRingWashed(shape, filled)
    val gap = mimirSelectionRingGap(shape, filled)
    val glintReach = mimirSelectionGlintReach(shape)
    val restLayer = if (restingAlpha < 1f) {
        // The `[alpha layer, ModulateAlpha only]` slot, placed after the ring by construction.
        Modifier.graphicsLayer {
            alpha = contentAlpha
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    } else {
        Modifier
    }
    this
        .drawWithCache {
            // Built once per size, never per frame: a traverse redraws every ring on screen every frame.
            val insetPx = inset.toPx()
            val elementSize = Size(
                (size.width - insetPx * 2f).coerceAtLeast(0f),
                (size.height - insetPx * 2f).coerceAtLeast(0f),
            )
            val cornerPx = mimirSelectionRingCornerPx(shape, elementSize)
            val paint = mimirFocusTileRingPaint(
                color = ringColor,
                cornerRadius = cornerPx.toDp(),
                elementSize = elementSize,
                gap = gap,
                glintReach = glintReach,
                dense = dense,
                flush = edgeFlush,
            )
            val corner = CornerRadius(cornerPx)
            val hairline = Stroke(width = MimirSpacing.hairlineWidth.toPx())
            val plateStroke = Stroke(width = MimirSelectionPlateStrokeWidth.toPx())
            onDrawBehind {
                val p = progress
                val h = heat()
                translate(left = insetPx, top = insetPx) {
                    if (fill != null) {
                        drawRoundRect(brush = fill, size = elementSize, cornerRadius = corner)
                    }
                    val washAlpha = mimirSelectionWashAlpha(washed, p, h, plate)
                    if (washAlpha > 0f) {
                        drawRoundRect(color = washColor, size = elementSize, cornerRadius = corner, alpha = washAlpha)
                    }
                    val plateAlpha = mimirSelectionPlateStrokeAlpha(plate, p)
                    if (plateAlpha > 0f) {
                        drawMimirSelectionInsetStroke(elementSize, cornerPx, plateStroke, washColor, plateAlpha)
                    }
                    val outlineAlpha = mimirSelectionOutlineAlpha(restingOutline, p)
                    if (outlineAlpha > 0f) {
                        drawMimirSelectionInsetStroke(elementSize, cornerPx, hairline, hairlineColor, outlineAlpha)
                    }
                    drawMimirFocusTileRing(paint, p, glint(), h)
                }
            }
        }
        .then(restLayer)
}

/** A stroke drawn just inside the element's edge, in its own shape. */
private fun DrawScope.drawMimirSelectionInsetStroke(
    elementSize: Size,
    cornerPx: Float,
    stroke: Stroke,
    color: Color,
    alpha: Float,
) {
    val half = stroke.width / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(half, half),
        size = Size(
            (elementSize.width - stroke.width).coerceAtLeast(0f),
            (elementSize.height - stroke.width).coerceAtLeast(0f),
        ),
        cornerRadius = CornerRadius((cornerPx - half).coerceAtLeast(0f)),
        style = stroke,
        alpha = alpha,
    )
}

/**
 * The gap rule: [MimirFilledControlRingGap] when the fill shares the ring's colour -- a filled pill, row or card.
 * A gold row is the same collision as a gold pill (Utility's Allow, the empty shelf's Open Library, the file
 * queue's lead action): the bright inner stroke half a dp off a gold edge reads as the button's own rim.
 * Keys and circles never stand off (a key's ring is dense; no circle is filled in the ring's colour).
 */
internal fun mimirSelectionRingGap(shape: MimirRingShape, filled: Boolean): Dp = when {
    !filled -> 0.dp
    shape is MimirRingShape.Pill || shape is MimirRingShape.Row || shape is MimirRingShape.Card -> MimirFilledControlRingGap
    else -> 0.dp
}

/** Which shapes carry the house wash: an unfilled pill or row. Art and keys take nothing at rest. */
internal fun mimirSelectionRingWashed(shape: MimirRingShape, filled: Boolean): Boolean =
    !filled && (shape is MimirRingShape.Pill || shape is MimirRingShape.Row)

/** A pill or a row fixes its gleam's reach, as onboarding's pills did; art keeps the tile rule (a quarter of the short side). */
internal fun mimirSelectionGlintReach(shape: MimirRingShape): Dp = when (shape) {
    is MimirRingShape.Pill, is MimirRingShape.Row -> MimirSpacing.xl
    else -> Dp.Unspecified
}

/** The ring's corner in px: the element's own token, or half the shorter side for a pill and a circle. */
internal fun Density.mimirSelectionRingCornerPx(shape: MimirRingShape, elementSize: Size): Float = when (shape) {
    is MimirRingShape.Pill, is MimirRingShape.Circle -> minOf(elementSize.width, elementSize.height) / 2f
    is MimirRingShape.Card -> shape.corner.toPx()
    is MimirRingShape.Row -> shape.corner.toPx()
    is MimirRingShape.Key -> shape.corner.toPx()
}

/**
 * The fill inside the element: the ghost pill's wash law (0 at rest, [MimirFocusWashAlpha] settled, twice that on a
 * flare's first frame) when [washed], or the active plate's [MimirSelectionPlateFillAlpha], whichever is stronger.
 */
internal fun mimirSelectionWashAlpha(washed: Boolean, progress: Float, flare: Float, plate: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    val h = flare.coerceIn(0f, 1f)
    val wash = if (washed) MimirFocusWashAlpha * (maxOf(p, h) + h) else 0f
    return maxOf(wash, MimirSelectionPlateFillAlpha * plate.coerceIn(0f, 1f))
}

/** The active plate's in-bounds stroke: [MimirSelectionPlateStrokeAlpha], fading as the ring arrives. */
internal fun mimirSelectionPlateStrokeAlpha(plate: Float, progress: Float): Float =
    MimirSelectionPlateStrokeAlpha * plate.coerceIn(0f, 1f) * (1f - progress.coerceIn(0f, 1f))

/** The resting hairline: whole at rest, gone when the ring is, in step with it. */
internal fun mimirSelectionOutlineAlpha(restingOutline: Boolean, progress: Float): Float =
    if (restingOutline) 1f - progress.coerceIn(0f, 1f) else 0f

/** The rest dim every peer list carries (the old `mimirFocusRing` default), on one ModulateAlpha layer. */
const val MimirFocusRestingAlpha: Float = 0.88f

/** The active plate's fill: the Settings rail's ember plate , now the family's `active`. */
internal const val MimirSelectionPlateFillAlpha: Float = 0.14f

/** The active plate's in-bounds stroke alpha. */
internal const val MimirSelectionPlateStrokeAlpha: Float = 0.55f

/** The active plate's stroke width. */
internal val MimirSelectionPlateStrokeWidth: Dp = 1.dp
