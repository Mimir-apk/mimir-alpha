package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp

/**
 * How far a finger must fling, per second, to turn a page without crossing half of it: one rule for every pager in Mimir that follows
 * the finger (onboarding's card deck since 2026-09-28, the Bottom's lattice and its Activity strip since the Bottom pager update).
 * Resolved against `LocalDensity` where it is used.
 */
val MimirPagerFlingVelocity = 400.dp

/**
 * Where a drag lands. A fling past [flingVelocityPx] turns one page in its direction from the page the drag started on ([from]);
 * anything slower lands on the nearest page ([current], which the pager keeps as the nearest). Never more than one page from
 * [from]. A finger moving left (negative velocity) brings the next page.
 *
 * Promoted from onboarding's `onboardingHintSettlePage` so the Bottom's pagers settle exactly as Main's carousel does.
 */
fun mimirPagerSettlePage(from: Int, current: Int, velocityPx: Float, flingVelocityPx: Float): Int {
    val target = when {
        velocityPx <= -flingVelocityPx -> from + 1
        velocityPx >= flingVelocityPx -> from - 1
        else -> current
    }
    return target.coerceIn(from - 1, from + 1)
}

/**
 * The settle after the finger lets go: [MimirMotion.OverlayMs] on the house easing (shared by every pager). Following the finger is direct manipulation and is never reduced; only this settle snaps under reduced motion.
 */
fun mimirPagerSettleSpec(reducedMotion: Boolean): TweenSpec<Float> = tween(
    durationMillis = if (reducedMotion) 0 else MimirMotion.OverlayMs,
    easing = MimirMotion.Easing,
)
