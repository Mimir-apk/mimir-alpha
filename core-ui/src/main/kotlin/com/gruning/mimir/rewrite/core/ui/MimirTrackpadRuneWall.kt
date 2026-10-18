package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The trackpad's rune wall : a staggered grid of faint Elder Futhark runes that glow in the accent
 * under the finger and fade behind it, drawn in the DRAW phase only (nothing here is composed). pad12: the resting runes are miniatures tooled into the
 * leather, painted once per size into a cached tile (MimirTrackpadTooledWall.kt); the glow still draws live, on the few runes it touches.
 * Every clock is a named token; the glyphs are pre-measured once per size by the caller.
 */
internal object TrackpadVisuals {
    /** The hold ring appears only after this long, never on a tap (see [TrackpadGestureMachine.HoldVisualDelayMs]). */
    const val HoldVisualDelayMs = TrackpadGestureMachine.HoldVisualDelayMs

    /** A rune lit by a touch fades over this long behind the finger, and a tap's flash fades over the same. */
    const val GlowFadeMs = 300L

    /** The elder futhark, in the traditional order. */
    val Runes: List<String> = listOf(
        "ᚠ", "ᚢ", "ᚦ", "ᚨ", "ᚱ", "ᚲ", "ᚷ", "ᚹ", "ᚺ", "ᚾ", "ᛁ", "ᛃ",
        "ᛇ", "ᛈ", "ᛉ", "ᛊ", "ᛏ", "ᛒ", "ᛖ", "ᛗ", "ᛚ", "ᛜ", "ᛞ", "ᛟ",
    )

    /** The rune cell's side, and the glow's reach, in dp. pad12: the miniature wall is tiled densely, a cell 24 dp (was 42) holding a rune 11 sp (was 17), about 65 %. */
    const val CellDp = 24f
    const val RuneSp = 11f
    const val HoldRuneSp = 17f
    const val GlowReachDp = 70f
    const val RestAlpha = 0.11f
    const val PeakAlpha = 0.9f

    /** pad8: the resting shimmer's swing around [RestAlpha] (very low), how far each rune is out of step, and how far it tints toward the ember. */
    const val ShimmerAmplitude = 0.025f
    const val ShimmerStagger = 0.137f
    const val ShimmerTint = 0.35f

    /** pad8: the share of the field's height over which a drifting wall fades at each edge. */
    const val EdgeFadeFraction = 0.16f

    /** pad8: how far the wall drifts per px of scroll (under one, so the wall trails the content a little, a parallax), and how long it keeps fading out. */
    const val DriftGain = 0.45f
    const val ScrollFadeMs = 450L
}

/**
 * The hold ring's progress, 0 to 1: nothing for a tap (or any state the machine is not waiting to hold in), nothing until
 * the press has lasted [TrackpadVisuals.HoldVisualDelayMs], then filling to the actual long-press moment; full while held.
 */
internal fun trackpadHoldProgress(elapsedMs: Long, pending: Boolean, holding: Boolean): Float {
    if (holding) return 1f
    if (!pending || elapsedMs <= TrackpadVisuals.HoldVisualDelayMs) return 0f
    val span = (TrackpadGestureMachine.LongPressMs - TrackpadVisuals.HoldVisualDelayMs).coerceAtLeast(1L)
    return ((elapsedMs - TrackpadVisuals.HoldVisualDelayMs).toFloat() / span).coerceIn(0f, 1f)
}

/** Glow at [distance] from a source whose strength is [strength] (0 to 1) with reach [reach]: a smooth falloff to 0. */
internal fun trackpadGlow(distance: Float, reach: Float, strength: Float): Float {
    if (reach <= 0f || strength <= 0f) return 0f
    val t = (1f - distance / reach).coerceIn(0f, 1f)
    return t * t * strength
}

/** How strongly a mark made at [atMs] still glows at [nowMs]: 1 at once, 0 after [TrackpadVisuals.GlowFadeMs]. */
internal fun trackpadFade(atMs: Long, nowMs: Long): Float {
    if (atMs == Long.MIN_VALUE) return 0f
    return (1f - (nowMs - atMs).toFloat() / TrackpadVisuals.GlowFadeMs).coerceIn(0f, 1f)
}

/** The short trail behind a moving finger: fixed arrays, no allocation per move, a sample only every [MinStepPx]. */
internal class TrackpadTrail {
    val xs = FloatArray(Size)
    val ys = FloatArray(Size)
    val ts = LongArray(Size) { Long.MIN_VALUE }
    private var head = 0
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    fun add(x: Float, y: Float, nowMs: Long) {
        if (!lastX.isNaN() && hypot(x - lastX, y - lastY) < MinStepPx) return
        lastX = x
        lastY = y
        xs[head] = x
        ys[head] = y
        ts[head] = nowMs
        head = (head + 1) % Size
    }

    /** Whether any sample still glows at [nowMs]. */
    fun alive(nowMs: Long): Boolean = ts.any { trackpadFade(it, nowMs) > 0f }

    fun clear() {
        ts.fill(Long.MIN_VALUE)
        lastX = Float.NaN
        lastY = Float.NaN
    }

    companion object {
        const val Size = 32
        const val MinStepPx = 6f
    }
}

/**
 * The wall's cells for one size: staggered rows, every other row shifted half a cell, each cell a fixed rune. Every row is laid out,
 * including those a little below the field, so the whole wall can drift by up to [period] and wrap (pad8: the wall drifts while
 * the player scrolls). A cell below the field is not drawn, which with no drift is exactly the wall the field always showed.
 */
internal class RuneWallLayout(width: Float, height: Float, cell: Float) {
    val xs: FloatArray
    val ys: FloatArray
    val glyphs: IntArray
    val count: Int

    /** The distance after which the drifting wall repeats itself: every row, once. */
    val period: Float

    init {
        val rowHeight = cell * 0.9f
        val columns = (width / cell).toInt() + 1
        val rows = (height / rowHeight).toInt() + 1
        period = rows * rowHeight
        val list = ArrayList<Triple<Float, Float, Int>>(columns * rows)
        for (row in 0 until rows) {
            val shift = if (row % 2 == 1) cell / 2f else 0f
            for (column in 0 until columns) {
                val x = column * cell + cell / 2f + shift
                val y = row * rowHeight + rowHeight / 2f
                if (x <= width) list += Triple(x, y, (row * 7 + column * 3) % TrackpadVisuals.Runes.size)
            }
        }
        count = list.size
        xs = FloatArray(count) { list[it].first }
        ys = FloatArray(count) { list[it].second }
        glyphs = IntArray(count) { list[it].third }
    }
}

/**
 * Where a cell at [y] sits after the wall has drifted [drift]: wrapped into one [period], so the wall is endless. With no drift it is [y].
 */
internal fun runeDriftedY(y: Float, drift: Float, period: Float): Float {
    if (period <= 0f) return y
    val moved = (y + drift) % period
    return if (moved < 0f) moved + period else moved
}

/** 0 at the field's top and bottom edges, 1 inside: the fade a drifting rune takes at the edges. */
internal fun runeEdgeFade(y: Float, height: Float): Float {
    if (height <= 0f) return 1f
    val band = height * TrackpadVisuals.EdgeFadeFraction
    val near = minOf(y, height - y) / band
    val t = near.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** The wall's alpha factor at [y] while scrolling at [scroll] strength (0 to 1): untouched at rest, faded toward the edges while scrolling. */
internal fun runeScrollAlphaScale(scroll: Float, y: Float, height: Float): Float =
    if (scroll <= 0f) 1f else 1f - scroll * (1f - runeEdgeFade(y, height))

/** A rune's resting alpha with the slow shimmer: a very low swing around [TrackpadVisuals.RestAlpha], each rune a little out of step. */
internal fun runeRestAlpha(shimmerPhase: Float, index: Int, amplitude: Float): Float {
    if (amplitude <= 0f) return TrackpadVisuals.RestAlpha
    val step = (shimmerPhase + index * TrackpadVisuals.ShimmerStagger) % 1f
    return TrackpadVisuals.RestAlpha + amplitude * sin(2f * PI.toFloat() * step)
}

/** The hold's rune circle: [progress] of a ring of runes around the press fills clockwise from the top, with the ring itself. */
internal fun DrawScope.drawHoldCircle(
    center: Offset,
    radius: Float,
    progress: Float,
    layouts: List<TextLayoutResult>,
    ink: Color,
    ringStroke: Float,
) {
    drawArc(
        color = ink.copy(alpha = 0.9f),
        startAngle = -90f,
        sweepAngle = 360f * progress.coerceIn(0f, 1f),
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = ringStroke, cap = StrokeCap.Round),
    )
    val lit = (progress * HoldRunes).toInt()
    for (k in 0 until HoldRunes) {
        val angle = (-PI / 2.0 + 2.0 * PI * k / HoldRunes).toFloat()
        val layout = layouts[(k * 3) % layouts.size]
        val at = Offset(center.x + cos(angle) * radius * 1.45f, center.y + sin(angle) * radius * 1.45f)
        drawText(
            layout,
            color = ink.copy(alpha = if (k < lit) 0.95f else 0.25f),
            topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f),
        )
    }
}

private const val HoldRunes = 8
