package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of a ring [done] of [total] fills, 0..1. A [total] of zero or less, or a negative [done], is an empty ring; a [done] past [total]
 * is a full one. Pure, so the arithmetic is pinned without a layout.
 */
fun mimirProgressFraction(done: Int, total: Int): Float =
    if (total <= 0 || done <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

/**
 * What a screen reader says for the ring: the counts as the sighted reader sees them ("12 of 40"), with the [subject] after them when given. The
 * counts are Main's (`mimirAchievementProgressLabel`): [done] is clamped into [0, total], so a ring that paints full never announces "41 of 40",
 * and a [total] of zero or less is not a count at all, so it says nothing (null).
 */
fun mimirProgressSpoken(done: Int, total: Int, subject: String? = null): String? {
    if (total <= 0) return null
    return "${done.coerceIn(0, total)} of $total" + if (subject.isNullOrBlank()) "" else " $subject"
}

/**
 * A small determinate progress ring ("12 of 40" achievements): a quiet track and an accent arc that starts at twelve o'clock and runs clockwise
 * for [mimirProgressFraction] of the circle. Every colour is a palette token. A full ring is the whole circle, an empty ring draws the track alone
 * (no cap dot), and the arc has round ends.
 *
 * It is a state, not an event, so it does not animate: no clock of [MimirMotion] is read, and a count that changes redraws at its new fraction.
 * (If a surface wants the arc to settle in on first arrival, [MimirMotion.OverlayMs] is the tier that fits; this piece does not take it.)
 *
 * One semantics node carries the counts as text ([mimirProgressSpoken], clamped as Main's achievement label is); the ring takes no focus and no taps.
 * A [total] of zero or less draws nothing at all (Main shows no label for it either), so the ring then occupies no space.
 */
@Composable
fun MimirProgressRing(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    size: Dp = 28.dp,
    stroke: Dp = 3.dp,
    subject: String? = null,
) {
    val fraction = mimirProgressFraction(done, total)
    // Main's suppression (`mimirAchievementProgressLabel`): no valid total, no ring, nothing drawn and nothing spoken. The caller owns the empty space.
    val spoken = mimirProgressSpoken(done, total, subject) ?: return
    val track = palette.textSecondary.copy(alpha = MimirProgressRingTrackAlpha)
    val arc = palette.accent
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = spoken },
    ) {
        val width = stroke.toPx().coerceAtMost(this.size.minDimension / 2f)
        val inset = width / 2f
        val box = Size(this.size.width - width, this.size.height - width)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = box,
            style = Stroke(width = width),
        )
        if (fraction > 0f) {
            drawArc(
                color = arc,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = box,
                style = Stroke(width = width, cap = if (fraction >= 1f) StrokeCap.Butt else StrokeCap.Round),
            )
        }
    }
}

/** The track's alpha over [MimirThemePalette.textSecondary]: quiet enough that the accent arc is the only thing that reads as progress. */
internal const val MimirProgressRingTrackAlpha = 0.30f
