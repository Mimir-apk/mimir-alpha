package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirHintKeycap
import com.gruning.mimir.rewrite.core.ui.MimirHintPillColors
import com.gruning.mimir.rewrite.core.ui.MimirHintPillDefaults
import com.gruning.mimir.rewrite.core.ui.MimirHintSwap
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRunicQuickRailDefaults
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.mimirHintPill
import com.gruning.mimir.rewrite.core.ui.mimirHintPillColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import com.gruning.mimir.rewrite.core.ui.mimirStaticChrome
import kotlinx.coroutines.delay
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import kotlin.math.roundToLong

/**
 * The controller-hint legend, collapsed into one themed Guidance-rune button so the carousel owns the screen. Tap the button to open the hint rail; tap again — or wait — and it collapses.
 *
 * It never opens by itself; the cold-launch self-reveal is retired. The button stays on the shelf and in the gamelist, wears the theme's accent, and carries a small caption under it the
 * way the dock labels its runes (`library_hint_rail_caption`). The previous standing hint line
 * under the platform band is gone.
 *
 * Touch-only, like the companion M-button: it is NOT in the focus graph, so a controller never lands on it.
 *
 * @param hints ordered (keycap, label) pairs, e.g. `"A" to "Launch"`.
 * @param contextKey a STABLE key for the current surface/context; a change re-inits the rail collapsed (e.g. a
 *   platform switch). Must be stable across recompositions — never pass the hint list itself.
 */
@Composable
internal fun HintRailButton(
    hints: List<Pair<String, String>>,
    contextKey: Any,
    modifier: Modifier = Modifier,
    /** OR-8: a counter the host bumps on L3; each change toggles the legend as a tap does. */
    revealRequest: Int = 0,
) {
    val palette = LocalMimirPalette.current
    val controllerLabels = LocalMimirControllerHintLabels.current
    val projectedHints = hints.mapNotNull { (key, label) ->
        controllerLabels.labelFor(key).takeIf(String::isNotBlank)?.let { it to label }
    }
    // Collapsed on every entry and every context change; only the Guidance rune below opens it.
    var expanded by remember(contextKey) { mutableStateOf(false) }
    // The request count at the last toggle; a first composition (or a context change) reads the current count as
    // already answered, so nothing opens by itself.
    var answeredReveal by remember(contextKey) { mutableStateOf(revealRequest) }
    LaunchedEffect(revealRequest, contextKey) {
        if (revealRequest != answeredReveal) {
            answeredReveal = revealRequest
            expanded = !expanded
        }
    }
    // Walk-32e: a legend wider than its row scrolls once through all its hints ([hintRailMarqueePassMs]); it stays open
    // until that pass is done.
    var marqueePassMs by remember(contextKey) { mutableLongStateOf(0L) }
    // Auto-collapse a beat after a manual open.
    LaunchedEffect(expanded, contextKey) {
        if (expanded) {
            delay(HintRailAutoCollapseMs)
            val pass = marqueePassMs - HintRailAutoCollapseMs
            if (pass > 0L) delay(pass)
            expanded = false
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val colors = remember(palette) { mimirHintPillColors(palette) }
    // The button's spoken name and state, resolved here in composition: the semantics block below is not a composable lambda.
    val guidanceDescription = stringResource(R.string.library_hint_rail_description)
    val shownDescription = stringResource(R.string.library_hint_rail_shown)
    val hiddenDescription = stringResource(R.string.library_hint_rail_hidden)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            HintRailVisibility(
                expanded = expanded,
                hints = projectedHints,
                colors = colors,
                onMarqueePassMs = { marqueePassMs = it },
            )
        }
        Spacer(Modifier.width(HintRailButtonGap))

        // Local Guidance trigger. Mannaz is reserved for the global M overlay, so Library cannot present two
        // controls with the same identity and different actions. This remains touch-only and outside focus.
        // The glow shadow is gone and the visible
        // mark is a small hint pill; the touch target is still the whole [HintButtonSize] box.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(HintButtonSize)
                    .testTag(HintRailGuidanceButtonTestTag)
                    .semantics {
                        contentDescription = guidanceDescription
                        stateDescription = if (expanded) shownDescription else hiddenDescription
                    }
                    .clickable(interactionSource = interaction, indication = null) { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                HintRailGuidanceBadge(colors = colors, accent = palette.accentBright)
            }
            // The dock's label look: RailAction, textSecondary, visual caps, no semantics of its own (the button
            // above carries the name).
            Text(
                text = mimirStaticChrome(stringResource(R.string.library_hint_rail_caption)),
                style = MimirTypeScale.RailAction.copy(fontSize = MimirRunicQuickRailDefaults.MinLabelFontSize),
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .testTag(HintRailGuidanceCaptionTestTag)
                    .clearAndSetSemantics { },
            )
        }
    }
}

/** The caption under the Guidance button, in the dock's one-word register. */
internal const val HintRailGuidanceCaptionTestTag = "hint-rail-guidance-caption"

internal const val HintRailGuidanceRune: String = "ᚨ"

/** The Guidance mark in the theme's accent: the rune in accentBright, a hairline of it round the pill. */
@Composable
private fun HintRailGuidanceBadge(colors: MimirHintPillColors, accent: Color) {
    Box(
        modifier = Modifier
            .size(HintBadgeMarkSize)
            .mimirHintPill(colors)
            .border(MimirSpacing.hairlineWidth, accent.copy(alpha = HintBadgeAccentRimAlpha), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = HintRailGuidanceRune,
            style = MimirTypeScale.RuneAction.copy(fontSize = MimirTypeStep.Heading),
            color = accent,
            maxLines = 1,
        )
    }
}

private const val HintBadgeAccentRimAlpha = 0.6f

@Composable
private fun HintRailVisibility(
    expanded: Boolean,
    hints: List<Pair<String, String>>,
    colors: MimirHintPillColors,
    onMarqueePassMs: (Long) -> Unit,
) {
    // The sheet tier, on one clock (policy A, 2026-09-30): the reveal and the collapse are both the house
    // MimirMotion.OverlayMs on MimirMotion.Easing, where they ran 260 and 220 ms on the carousel's easing.
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)) +
            expandHorizontally(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing), expandFrom = Alignment.End),
        exit = fadeOut(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)) +
            shrinkHorizontally(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing), shrinkTowards = Alignment.End),
    ) {
        val pill = Modifier
            .widthIn(max = HintRailMaxWidth)
            .height(HintRailHeight)
            .mimirHintPill(colors)
        if (mimirReducedMotion()) {
            // Nothing moves under reduced motion: the one row keeps its trailing ellipsis.
            QuietHintRail(
                hints = hints,
                colors = colors,
                modifier = pill
                    .padding(horizontal = HintRailHorizontalPadding)
                    .testTag(HintRailLegendTestTag),
            )
            return@AnimatedVisibility
        }
        // The single-row legend holds every hint and, when they are wider than the pill, glides through them
        // once and back to the first. The pill's padding scrolls with the hints, so at rest the first hint sits where it
        // always has and only the padding fades ([hintRailEdgeFade]); a legend that fits is laid out exactly as before.
        var windowWidthPx by remember { mutableIntStateOf(0) }
        var contentWidthPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val spacingPx = with(density) { HintRailMarqueeSpacing.roundToPx() }
        val velocityPxPerSecond = with(density) { HintRailMarqueeVelocity.toPx() }
        val passMs = hintRailMarqueePassMs(contentWidthPx, windowWidthPx, spacingPx, velocityPxPerSecond)
        LaunchedEffect(passMs) { onMarqueePassMs(passMs) }
        QuietHintRail(
            hints = hints,
            colors = colors,
            modifier = pill
                .testTag(HintRailLegendTestTag)
                // Read at draw time, so measuring the row never reshapes this chain under the running marquee.
                .hintRailEdgeFade { hintRailLegendOverflows(contentWidthPx, windowWidthPx) }
                .onSizeChanged { windowWidthPx = it.width }
                .basicMarquee(
                    iterations = 1,
                    initialDelayMillis = HintRailMarqueeDelayMs.toInt(),
                    spacing = MarqueeSpacing(HintRailMarqueeSpacing),
                    velocity = HintRailMarqueeVelocity,
                )
                .onSizeChanged { contentWidthPx = it.width }
                .padding(horizontal = HintRailHorizontalPadding),
        )
    }
}

/**
 * Softens the pill's two padding strips while its row scrolls, so hints glide in and out of it instead of being cut at
 * the edge. Only while the legend overflows; a legend that fits draws exactly as before.
 */
private fun Modifier.hintRailEdgeFade(active: () -> Boolean): Modifier = this
    .graphicsLayer {
        compositingStrategy = if (active()) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        if (!active() || size.width <= 0f) return@drawWithContent
        val edge = (HintRailHorizontalPadding.toPx() / size.width).coerceIn(0f, 0.25f)
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                edge to Color.Black,
                1f - edge to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * One row of complete (keycap, label) units, dropping whole units for a trailing ellipsis when the width runs out
 * ([hintRailVisibleUnitCount]). The caller decorates it: the Guidance pill in [HintRailVisibility]. Inside that pill's
 * marquee (walk-32e) it is measured without a width bound, so every unit shows and none is dropped.
 */
@Composable
private fun QuietHintRail(
    hints: List<Pair<String, String>>,
    colors: MimirHintPillColors,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MimirTypeScale.ControlHintCompact,
    ink: Color = colors.ink,
    unitGap: Dp = HintRailUnitGap,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val units = hints.mapIndexed { index, (key, label) ->
            subcompose("hint-$index") {
                HintChip(key = key, label = label, colors = colors, labelStyle = labelStyle, ink = ink)
            }.single().measure(loose)
        }
        val overflow = subcompose("hint-overflow") {
            Text(
                text = "…",
                style = labelStyle,
                color = ink,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }.single().measure(loose)
        val availableWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else {
            units.sumOf { it.width } + unitGap.roundToPx() * (units.size - 1).coerceAtLeast(0)
        }
        val gapPx = unitGap.roundToPx()
        val visibleCount = hintRailVisibleUnitCount(
            unitWidthsPx = units.map { it.width },
            availableWidthPx = availableWidth,
            gapPx = gapPx,
            overflowWidthPx = overflow.width,
        )
        val showOverflow = visibleCount < units.size && overflow.width <= availableWidth
        val visible = units.take(visibleCount)
        val contentWidth = visible.sumOf { it.width } +
            gapPx * (visible.size - 1).coerceAtLeast(0) +
            if (showOverflow) (if (visible.isEmpty()) 0 else gapPx) + overflow.width else 0
        val contentHeight = (visible.maxOfOrNull { it.height } ?: 0).coerceAtLeast(if (showOverflow) overflow.height else 0)
        val layoutWidth = contentWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(
            width = layoutWidth,
            height = layoutHeight,
        ) {
            var x = 0
            visible.forEach { placeable ->
                placeable.placeRelative(x, (layoutHeight - placeable.height) / 2)
                x += placeable.width + gapPx
            }
            if (showOverflow) overflow.placeRelative(x, (layoutHeight - overflow.height) / 2)
        }
    }
}

@Composable
private fun HintChip(
    key: String,
    label: String,
    colors: MimirHintPillColors,
    labelStyle: TextStyle,
    ink: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MimirHintKeycap(key = key, colors = colors, ink = ink)
        MimirChromeText(
            text = label,
            style = labelStyle,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun hintRailVisibleUnitCount(
    unitWidthsPx: List<Int>,
    availableWidthPx: Int,
    gapPx: Int,
    overflowWidthPx: Int,
): Int {
    if (unitWidthsPx.isEmpty() || availableWidthPx <= 0) return 0
    val safeGap = gapPx.coerceAtLeast(0)
    val fullWidth = unitWidthsPx.sum() + safeGap * (unitWidthsPx.size - 1)
    if (fullWidth <= availableWidthPx) return unitWidthsPx.size

    for (count in unitWidthsPx.size - 1 downTo 0) {
        val prefixWidth = unitWidthsPx.take(count).sum() + safeGap * (count - 1).coerceAtLeast(0)
        val total = prefixWidth + overflowWidthPx + if (count > 0) safeGap else 0
        if (total <= availableWidthPx) return count
    }
    return 0
}

/**
 * Walk-32e: how long a legend wider than its pill stays open: the rest before it moves, one pass through every hint and
 * round to the first (its content plus [HintRailMarqueeSpacing], at [HintRailMarqueeVelocity]), and a short hold. 0 when
 * the hints fit, which keeps the ordinary auto-collapse.
 */
internal fun hintRailMarqueePassMs(
    contentWidthPx: Int,
    windowWidthPx: Int,
    spacingPx: Int,
    velocityPxPerSecond: Float,
): Long {
    if (windowWidthPx <= 0 || contentWidthPx <= windowWidthPx || velocityPxPerSecond <= 0f) return 0L
    val travelPx = contentWidthPx + spacingPx.coerceAtLeast(0)
    return HintRailMarqueeDelayMs + (travelPx * 1000f / velocityPxPerSecond).roundToLong() + HintRailMarqueeHoldMs
}

/** How long the first hints rest before the row moves: the pill's own reveal, then a beat to read them. */
internal const val HintRailMarqueeDelayMs: Long = 1000L

/** The row's pace. Well above the marquee default (30 dp/s), whose one pass a glance would never see the end of. */
internal val HintRailMarqueeVelocity = 80.dp

/** Between the last hint and the first coming round again, beyond the padding that scrolls with the row. */
internal val HintRailMarqueeSpacing = 24.dp

/** How long the row rests on its first hints again before the legend closes. */
internal const val HintRailMarqueeHoldMs: Long = 600L

internal const val HintRailLegendTestTag = "hint-rail-legend"
internal const val HintRailGuidanceButtonTestTag = "hint-rail-guidance-button"

internal val HintButtonSize = 48.dp

/** The Guidance badge's visible mark, centred in the [HintButtonSize] touch target. */
private val HintBadgeMarkSize = 34.dp
private val HintRailHeight = 32.dp
private val HintRailMaxWidth = 720.dp
private val HintRailHorizontalPadding = 12.dp
private val HintRailUnitGap = 12.dp
internal val HintRailButtonGap = 10.dp
private const val HintRailAutoCollapseMs = 2600L

/**
 * Whether the legend's row is wider than the pill showing it, which is when it scrolls and so when its edges are softened ([hintRailEdgeFade]'s offscreen layer and mask). Both
 * widths are as `onSizeChanged` reports them: [windowWidthPx] the pill's own, [contentWidthPx] the row measured by the node after `basicMarquee` and BEFORE `.padding`, so the
 * padding is already inside it and is not added again. It is the comparison [hintRailMarqueePassMs] makes of the same two numbers, so the fade is on exactly when the row moves.
 * False while the pill is not measured yet.
 */
internal fun hintRailLegendOverflows(contentWidthPx: Int, windowWidthPx: Int): Boolean = windowWidthPx > 0 && contentWidthPx > windowWidthPx
