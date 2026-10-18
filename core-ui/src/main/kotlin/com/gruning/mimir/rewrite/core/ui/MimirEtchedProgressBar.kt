package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

/**
 * How long the fill takes to arrive: bounded, twice the house overlay clock. Zero (a snap) when the system's animations are off, and zero while the pane
 * is parked (D24, [LocalMimirMotionLive]): nothing animates for a pane nobody can see.
 */
const val MimirEtchedProgressArrivalMs: Int = MimirMotion.OverlayMs * 2

/** The arrival's duration for these conditions: [MimirEtchedProgressArrivalMs], or 0 (a snap) under reduced motion or while parked. */
fun mimirEtchedProgressDurationMs(reducedMotion: Boolean, live: Boolean): Int =
    if (reducedMotion || !live) 0 else MimirEtchedProgressArrivalMs

/** The fill the bar is drawing right now (0..1), for tests and tooling; it is not an accessibility property. */
val MimirEtchedProgressShownSemantics = SemanticsPropertyKey<Float>("MimirEtchedProgressShown")
private var SemanticsPropertyReceiver.mimirEtchedProgressShown by MimirEtchedProgressShownSemantics

/**
 * A metal-etched progress bar: the struck-metal plate's leather trough and metal rim ([drawMimirMetalPlate], the family the pad slab and the media plates
 * wear), a fill in the same metal, and hairline ticks etched at each tenth. Every colour is a palette token.
 *
 * The fill animates ONCE, on arrival, over [mimirEtchedProgressDurationMs]; after that a changed [fraction] snaps, and at rest nothing runs, so the bar
 * costs no idle frames. The animated value is read in the draw block only, so a frame of the arrival recomposes nothing.
 *
 * Decorative for accessibility: the panel that holds it announces the numbers it stands for.
 */
@Composable
fun MimirEtchedProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = mimirReducedMotion(),
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    val live = LocalMimirMotionLive.current
    val target = fraction.coerceIn(0f, 1f)
    val initiallyAnimated = mimirEtchedProgressDurationMs(reducedMotion, live.value) > 0
    val shown = remember { Animatable(if (initiallyAnimated) 0f else target) }
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(target, reducedMotion) {
        val ms = mimirEtchedProgressDurationMs(reducedMotion, live.value)
        if (!arrived && ms > 0) {
            arrived = true
            // D24: parking during the fill cancels it (no frame is requested for a pane nobody can see) and the fill snaps to where it was going.
            runWhileMotionLive(live, onParked = { shown.snapTo(target) }) {
                shown.animateTo(target, tween(ms, easing = MimirMotion.Easing))
            }
        } else {
            arrived = true
            shown.snapTo(target)
        }
    }
    Box(
        modifier
            .semantics { mimirEtchedProgressShown = shown.value }
            .drawWithCache {
                val metal = platformConsoleIconBrush(palette)
                onDrawBehind {
                    val corner = size.height / 2f
                    drawMimirMetalPlate(palette, metal, mimirMetalRimAlpha(warmed = false), warmed = false, cornerPx = corner)
                    val inset = MimirMetalRimDp.toPx() * 2f
                    val innerW = (size.width - inset * 2f).coerceAtLeast(0f)
                    val innerH = (size.height - inset * 2f).coerceAtLeast(0f)
                    val fillW = innerW * shown.value.coerceIn(0f, 1f)
                    if (fillW > 0f) {
                        drawRoundRect(
                            brush = metal,
                            topLeft = Offset(inset, inset),
                            size = Size(fillW, innerH),
                            cornerRadius = CornerRadius((innerH / 2f).coerceAtLeast(0f)),
                            alpha = MimirMetalRestAlpha,
                        )
                    }
                    // Hairline ticks, etched at each tenth: the mark of struck metal, in the field's own ink.
                    val tick = MimirSpacing.hairlineWidth.toPx()
                    for (i in 1..9) {
                        val x = inset + innerW * i / 10f
                        drawLine(
                            color = palette.surfaceBase.copy(alpha = 0.45f),
                            start = Offset(x, inset + innerH * 0.25f),
                            end = Offset(x, inset + innerH * 0.75f),
                            strokeWidth = tick,
                        )
                    }
                }
            },
    )
}
