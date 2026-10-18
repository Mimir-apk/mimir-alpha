package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate

/**
 * A reusable indeterminate sweep: a low-alpha accent travelling a full-width band, for any surface that
 * must show "work is happening" honestly when no real total exists to render as a fraction.
 *
 * Shared implementation: [MimirLedgerRule]
 * (`MimirAchievementCard.kt`) already solves this exact problem one surface over and drives the SAME motion
 * ([SweepHalfWidth] fraction of width, [LedgerPulseMs] linear loop, [SweepAlpha] low-alpha accent) — but it
 * paints a THIN gradient stroke coincident with a decorative hairline-plus-diamond-node "ledger rule", where
 * this primitive paints a SOLID rounded band filling its own bounds (the onboarding settling screen's 6 dp
 * progress track has no rule to sit on). Forcing both through one Canvas would need a shape-mode flag
 * serving a caller ([MimirLedgerRule]) that must not regress its tuned achievement-card visual; sharing the
 * three motion constants already removes 100% of the real drift risk (position math and timing stay single-
 * sourced here), so [MimirLedgerRule]'s own Canvas is untouched — only the constants moved.
 *
 * **Reduced motion:** reserves its full [modifier] footprint but paints nothing — never a static, empty
 * band, which is the exact defect this primitive exists to fix. Same "keep the space, drop the visual
 * shell" shape as [MimirHintToastRail]'s idle band. A caller that instead wants "not present at all, nothing
 * reserved" (this repo's OTHER reduced-motion pattern) must branch before calling this composable at all,
 * rather than rely on it.
 */
@Composable
fun MimirIndeterminateBar(
    modifier: Modifier = Modifier,
    trackColor: Color,
    sweepColor: Color,
    reducedMotion: Boolean = mimirReducedMotion(),
) {
    if (reducedMotion) {
        Box(modifier)
        return
    }
    val transition = rememberInfiniteTransition(label = "mimirIndeterminateBarSweep")
    val sweep by transition.animateFloat(
        initialValue = -SweepHalfWidth,
        targetValue = 1f + SweepHalfWidth,
        animationSpec = infiniteRepeatable(
            animation = tween(LedgerPulseMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mimirIndeterminateBarSweepFraction",
    )
    // the gradient and its colour list were built on every frame of the sweep. The brush
    // is built once per size and colours (`drawWithCache`) over the band's own width, and each frame only translates it: the same
    // pixels, no allocation in the draw loop. `sweep` is read in the draw block, so a frame invalidates a draw and never recomposes.
    Spacer(
        modifier.drawWithCache {
            val half = size.width * SweepHalfWidth
            val brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, sweepColor.copy(alpha = SweepAlpha), Color.Transparent),
                startX = 0f,
                endX = 2f * half,
            )
            onDrawBehind {
                drawRect(color = trackColor)
                val x = size.width * sweep
                val left = (x - half).coerceAtLeast(0f)
                val right = (x + half).coerceAtMost(size.width)
                if (right > left) {
                    // The cached brush spans 0..2*half; moving the origin to the sweep's left edge puts its centre on x.
                    translate(left = x - half, top = 0f) {
                        drawRect(
                            brush = brush,
                            topLeft = Offset(left - (x - half), 0f),
                            size = Size(right - left, size.height),
                        )
                    }
                }
            }
        },
    )
}

/**
 * Shared with [MimirLedgerRule] — moved here, not duplicated, so the two surfaces' sweeps can never drift
 * apart in timing or width. [MimirLedgerRule] keeps referencing these same identifiers (same package, no
 * import needed); its own painting is otherwise unchanged.
 */
internal const val SweepHalfWidth = 0.18f
internal const val LedgerPulseMs = 1400
internal const val SweepAlpha = 0.45f
