package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * The shelf's header rule: the hairline under the identity header (every theme but two) and the molten band Surtr and Niflheim
 * wear instead, with the motion specs, phase maths and geometry that drive it. Moved out of feature-library's MimirShelfChrome
 * unchanged, so a surface in another module (the Bottom's) draws the same rule; the shelf still draws it from here.
 */

/** The rule's own height, shared with the shelf's header geometry (`ShelfHeaderRuleHeightDp` in feature-library reads this). */
const val MimirHeaderRuleHeightDp = 3
private val HeaderRuleHeight = MimirHeaderRuleHeightDp.dp
private const val HeaderBreathePeriodMs = 7_000
internal const val HeaderBreatheLowAlpha = 0.65f
internal const val HeaderCorePulseLowAlpha = 0.55f

internal enum class HeaderCrestAlphaMotion {
    BREATHE,
    FLICKER,
}

internal data class HeaderMoltenMotionSpec(
    val primaryFlowPeriodMillis: Int,
    val coreFlowPeriodMillis: Int,
    val crestAlphaPeriodMillis: Int,
    val corePulsePeriodMillis: Int,
    val crestAlphaMotion: HeaderCrestAlphaMotion,
)

// Unified motion: Surtr keeps its crimson-to-gold palette (the MoltenRuleSpec colours
// are unchanged) but adopts Niflheim's slow breathe + gentle counter-scroll — the flicker is retired so both
// molten themes read with one calm motion language. (FLICKER stays wired below in case a theme wants it back.)
private val SurtrHeaderMotion = HeaderMoltenMotionSpec(
    primaryFlowPeriodMillis = 13_000,
    coreFlowPeriodMillis = 19_000,
    crestAlphaPeriodMillis = HeaderBreathePeriodMs,
    corePulsePeriodMillis = 4_500,
    crestAlphaMotion = HeaderCrestAlphaMotion.BREATHE,
)

private val NiflheimHeaderMotion = HeaderMoltenMotionSpec(
    primaryFlowPeriodMillis = 13_000,
    coreFlowPeriodMillis = 19_000,
    crestAlphaPeriodMillis = HeaderBreathePeriodMs,
    corePulsePeriodMillis = 4_500,
    crestAlphaMotion = HeaderCrestAlphaMotion.BREATHE,
)

internal fun headerMoltenMotionSpec(id: MimirThemeId): HeaderMoltenMotionSpec? =
    moltenRuleSpec(id)?.motion

internal fun headerAmbientMotionRuns(phase: AmbientMotionPhase): Boolean =
    phase != AmbientMotionPhase.IDLE

internal fun headerPhaseRemainingDurationMillis(phase: Float, periodMillis: Int): Int {
    require(periodMillis > 0)
    val remainingFraction = 1f - phase.coerceIn(0f, 1f)
    return (periodMillis * remainingFraction).roundToInt().coerceAtLeast(1)
}

internal fun headerBreatheAlpha(phase: Float): Float {
    val normalized = phase.coerceIn(0f, 1f)
    return if (normalized <= 0.5f) {
        val eased = FastOutSlowInEasing.transform(normalized * 2f)
        1f + (HeaderBreatheLowAlpha - 1f) * eased
    } else {
        val reverseEased = FastOutSlowInEasing.transform((1f - normalized) * 2f)
        1f + (HeaderBreatheLowAlpha - 1f) * reverseEased
    }
}

internal fun headerCorePulseAlpha(phase: Float): Float {
    val normalized = phase.coerceIn(0f, 1f)
    val eased = if (normalized <= 0.5f) {
        FastOutSlowInEasing.transform(normalized * 2f)
    } else {
        FastOutSlowInEasing.transform((1f - normalized) * 2f)
    }
    return HeaderCorePulseLowAlpha + (1f - HeaderCorePulseLowAlpha) * eased
}

internal fun headerSurtrFlickerAlpha(phase: Float): Float {
    val normalized = phase.coerceIn(0f, 1f)
    return when {
        normalized < 0.92f -> 1f
        normalized < 0.93f -> interpolateHeaderKeyframe(normalized, 0.92f, 0.93f, 1f, 0.4f)
        normalized < 0.94f -> interpolateHeaderKeyframe(normalized, 0.93f, 0.94f, 0.4f, 0.9f)
        normalized < 0.96f -> interpolateHeaderKeyframe(normalized, 0.94f, 0.96f, 0.9f, 0.55f)
        normalized < 0.97f -> interpolateHeaderKeyframe(normalized, 0.96f, 0.97f, 0.55f, 1f)
        else -> 1f
    }
}

private fun interpolateHeaderKeyframe(
    value: Float,
    start: Float,
    end: Float,
    startAlpha: Float,
    endAlpha: Float,
): Float {
    val fraction = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return startAlpha + (endAlpha - startAlpha) * fraction
}

internal data class HeaderHairlineGeometry(
    val topPx: Float,
    val heightPx: Float,
)

internal fun centeredHeaderHairlineGeometry(
    containerHeightPx: Float,
    requestedHeightPx: Float,
): HeaderHairlineGeometry {
    val containerHeight = containerHeightPx.coerceAtLeast(0f)
    val lineHeight = requestedHeightPx.coerceIn(0f, containerHeight)
    return HeaderHairlineGeometry(
        topPx = (containerHeight - lineHeight) / 2f,
        heightPx = lineHeight,
    )
}

private data class MoltenRuleSpec(
    val crestStops: Array<Pair<Float, Color>>,
    val coreStops: Array<Pair<Float, Color>>,
    val floorColor: Color,
    val motion: HeaderMoltenMotionSpec,
    val movingGlowHeightDp: Float,
    val movingGlowAlpha: Float,
    val coreLayerAlpha: Float,
    val glowBlendMode: BlendMode,
    val coreBlendMode: BlendMode,
)

/**
 * [MimirShelfArt] is the color authority for molten separators; since FUX-D it carries stops for every
 * theme so the Home dock can forge its material anywhere. The animated HEADER rule is a separate,
 * narrower contract: only Surtr and Niflheim ship its motion and blend presentation — every other theme
 * keeps the ordinary hairline, exactly as before the promotion.
 */
private fun moltenRuleSpec(id: MimirThemeId): MoltenRuleSpec? {
    val art = MimirShelfArt.moltenSeparatorFor(id)
    return when (id) {
        MimirThemeId.SURTR -> MoltenRuleSpec(
            crestStops = art.crestStops.toTypedArray(),
            coreStops = art.coreStops.toTypedArray(),
            floorColor = art.floor,
            motion = SurtrHeaderMotion,
            movingGlowHeightDp = 8f,
            movingGlowAlpha = 0.11f,
            coreLayerAlpha = 0.80f,
            glowBlendMode = BlendMode.Screen,
            coreBlendMode = BlendMode.Screen,
        )
        MimirThemeId.NIFLHEIM -> MoltenRuleSpec(
            crestStops = art.crestStops.toTypedArray(),
            coreStops = art.coreStops.toTypedArray(),
            floorColor = art.floor,
            motion = NiflheimHeaderMotion,
            movingGlowHeightDp = 10f,
            movingGlowAlpha = 0.08f,
            coreLayerAlpha = 1f,
            glowBlendMode = BlendMode.SrcOver,
            coreBlendMode = BlendMode.SrcOver,
        )
        MimirThemeId.DEFAULT,
        MimirThemeId.FREYR,
        MimirThemeId.MANI,
        MimirThemeId.HEL,
        MimirThemeId.YGGDRASIL,
        MimirThemeId.FOLKVANGR,
        MimirThemeId.BALDR -> null
    }
}

/**
 * The shelf header rule: the ordinary hairline, or for Surtr and Niflheim the full-bleed molten band (see the body). [bleed] is how
 * far the molten band reaches past the content gutter on each side; the hairline ignores it.
 */
@Composable
fun HeaderRule(
    modifier: Modifier = Modifier,
    palette: MimirThemePalette,
    bleed: Dp = 0.dp,
) {
    val molten = remember(palette.id) { moltenRuleSpec(palette.id) }
    if (molten == null) {
        Box(
            modifier
                .fillMaxWidth()
                .height(HeaderRuleHeight)
                .drawWithCache {
                    val geometry = centeredHeaderHairlineGeometry(
                        containerHeightPx = size.height,
                        requestedHeightPx = MimirSpacing.hairlineWidth.toPx(),
                    )
                    val ruleBrush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to palette.outline.copy(alpha = 0.18f),
                            0.25f to palette.accent.copy(alpha = 0.58f),
                            0.50f to palette.accentBright.copy(alpha = 0.48f),
                            0.75f to palette.accent.copy(alpha = 0.58f),
                            1.00f to palette.outline.copy(alpha = 0.18f),
                        ),
                        startX = 0f,
                        endX = size.width.coerceAtLeast(1f),
                    )
                    onDrawBehind {
                        drawRect(
                            brush = ruleBrush,
                            topLeft = Offset(0f, geometry.topPx),
                            size = Size(size.width, geometry.heightPx),
                        )
                    }
                },
        )
        return
    }
    // Shipping `.eok-molten-sep` (void/crimson): a FULL-BLEED themed molten band pinned under the header, carrying
    // an always-lit GLOW FLOOR (its `box-shadow: 0 0 18px` + blurred `::before`) and a SEAMLESS flowing gradient
    // (moltenFlowA). Three fixes vs the prior rewrite :
    //  1. GLOW FLOOR — the previous band had no glow, so the lone crest visibly swept then "reset". The constant
    //     bloom (below) keeps the line lit at all times, so the loop reset is imperceptible.
    //  2. FULL-BLEED — the band is drawn from `-bleed` to `width + bleed`, past the content gutter, so it "wraps
    //     around the screen" edge-to-edge instead of ending at the header content width.
    //  3. Both periodic shaders scroll exactly one tile, in opposite directions for the crest/core pair, so each
    //     reset lands on an identical frame. Surtr gets its short flicker; Niflheim keeps the long void breathe.
    // The shared process gate freezes all four retained phases at the exact idle boundary, matching shipping's
    // `animation-play-state: paused` behavior.
    val motionRunning = headerAmbientMotionRuns(LocalMimirAmbientMotionPhase.current)
    val primaryFlow = remember { Animatable(0f) }
    val coreFlow = remember { Animatable(0f) }
    val crestAlphaPhase = remember { Animatable(0f) }
    val corePulsePhase = remember { Animatable(0f) }
    LaunchedEffect(motionRunning, molten.motion) {
        if (motionRunning) {
            launch { runHeaderPhaseLoop(primaryFlow, molten.motion.primaryFlowPeriodMillis) }
            launch { runHeaderPhaseLoop(coreFlow, molten.motion.coreFlowPeriodMillis) }
            launch { runHeaderPhaseLoop(crestAlphaPhase, molten.motion.crestAlphaPeriodMillis) }
            launch { runHeaderPhaseLoop(corePulsePhase, molten.motion.corePulsePeriodMillis) }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(HeaderRuleHeight)
            .drawWithCache {
                val bleedPx = bleed.toPx()
                val left = -bleedPx
                val right = size.width + bleedPx
                val fullWidth = (right - left).coerceAtLeast(1f)
                val midY = size.height / 2f
                val floorPeak = 0.30f
                val floorOuterHeight = 14f * density
                val floorMiddleHeight = 8f * density
                val floorInnerHeight = 4f * density
                val movingGlowHeight = molten.movingGlowHeightDp * density
                val crestHeight = 2.2f * density
                val coreHeight = 1f * density

                val floorOuterBrush = Brush.horizontalGradient(
                    0.0f to Color.Transparent,
                    0.5f to molten.floorColor.copy(alpha = floorPeak * 0.42f),
                    1.0f to Color.Transparent,
                    startX = left,
                    endX = right,
                )
                val floorMiddleBrush = Brush.horizontalGradient(
                    0.0f to Color.Transparent,
                    0.5f to molten.floorColor.copy(alpha = floorPeak * 0.72f),
                    1.0f to Color.Transparent,
                    startX = left,
                    endX = right,
                )
                val floorInnerBrush = Brush.horizontalGradient(
                    0.0f to Color.Transparent,
                    0.5f to molten.floorColor.copy(alpha = floorPeak),
                    1.0f to Color.Transparent,
                    startX = left,
                    endX = right,
                )
                val crestBrush = Brush.horizontalGradient(
                    *molten.crestStops,
                    startX = left,
                    endX = right,
                    tileMode = TileMode.Repeated,
                )
                val coreBrush = Brush.horizontalGradient(
                    *molten.coreStops,
                    startX = left,
                    endX = right,
                    tileMode = TileMode.Repeated,
                )

                // Brushes and their color-stop arrays are built only when size, density, bleed, or theme changes.
                // The animation translates those cached shaders while compensating the rectangle geometry, so the
                // visible full-bleed bounds stay fixed and the draw loop allocates neither lists nor brushes.
                onDrawBehind {
                    // (a) TIGHT, always-lit glow floor. It extends outside the 3dp band without moving its centre.
                    drawRect(
                        brush = floorOuterBrush,
                        topLeft = Offset(left, midY - floorOuterHeight / 2f),
                        size = Size(fullWidth, floorOuterHeight),
                    )
                    drawRect(
                        brush = floorMiddleBrush,
                        topLeft = Offset(left, midY - floorMiddleHeight / 2f),
                        size = Size(fullWidth, floorMiddleHeight),
                    )
                    drawRect(
                        brush = floorInnerBrush,
                        topLeft = Offset(left, midY - floorInnerHeight / 2f),
                        size = Size(fullWidth, floorInnerHeight),
                    )

                    val crestAlpha = when (molten.motion.crestAlphaMotion) {
                        HeaderCrestAlphaMotion.BREATHE -> headerBreatheAlpha(crestAlphaPhase.value)
                        HeaderCrestAlphaMotion.FLICKER -> headerSurtrFlickerAlpha(crestAlphaPhase.value)
                    }
                    val primaryShift = primaryFlow.value * fullWidth
                    withTransform({ translate(left = -primaryShift) }) {
                        // (b) The blurred CSS pseudo-element becomes a restrained moving glow beneath the crest.
                        drawRect(
                            brush = crestBrush,
                            topLeft = Offset(left + primaryShift, midY - movingGlowHeight / 2f),
                            size = Size(fullWidth, movingGlowHeight),
                            alpha = molten.movingGlowAlpha * crestAlpha,
                            blendMode = molten.glowBlendMode,
                        )
                        drawRect(
                            brush = crestBrush,
                            topLeft = Offset(left + primaryShift, midY - crestHeight / 2f),
                            size = Size(fullWidth, crestHeight),
                            alpha = crestAlpha,
                        )
                    }

                    // (c) Shipping's 1dp white-hot core moves opposite the crest and pulses independently.
                    val coreShift = coreFlow.value * fullWidth
                    withTransform({ translate(left = coreShift) }) {
                        drawRect(
                            brush = coreBrush,
                            topLeft = Offset(left - coreShift, midY - coreHeight / 2f),
                            size = Size(fullWidth, coreHeight),
                            alpha = headerCorePulseAlpha(corePulsePhase.value) * molten.coreLayerAlpha,
                            blendMode = molten.coreBlendMode,
                        )
                    }
                }
            },
    )
}

private suspend fun runHeaderPhaseLoop(
    phase: Animatable<Float, *>,
    periodMillis: Int,
) {
    while (true) {
        if (phase.value >= 1f) phase.snapTo(0f)
        phase.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = headerPhaseRemainingDurationMillis(phase.value, periodMillis),
                easing = LinearEasing,
            ),
        )
        phase.snapTo(0f)
    }
}
