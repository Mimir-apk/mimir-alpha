package com.gruning.mimir.rewrite.core.ui.theme

import androidx.compose.ui.unit.Dp
import com.gruning.mimir.rewrite.core.ui.MimirSpacing as CoreSpacing

/**
 * MIMIR spacing/shape tokens — a FORWARDING FAÇADE onto the single `:core-ui` scale.
 *
 * Spacing is theme-invariant, so these are pure forwards to the one core-ui source of truth (no drift).
 * Kept as a feature-settings `object` returning plain [Dp] — NOT a `typealias` — so this module's own
 * surfaces read one hermetic token home like [MimirColors]/[MimirType]. Field names match core-ui 1:1.
 * (Other feature modules import core-ui directly — feature-companion moved off this façade in B7.)
 */
object MimirSpacing {
    /** 4dp — hairline gaps, icon-to-label. */
    val xs: Dp = CoreSpacing.xs
    /** 8dp — tight intra-component spacing. */
    val sm: Dp = CoreSpacing.sm
    /** 12dp — default row inner padding. */
    val md: Dp = CoreSpacing.md
    /** 16dp — standard content gutter. */
    val lg: Dp = CoreSpacing.lg
    /** 24dp — section separation. */
    val xl: Dp = CoreSpacing.xl
    /** 32dp — screen edge padding / hero breathing room. */
    val xxl: Dp = CoreSpacing.xxl

    /** Corner radius for cards / rows. */
    val radiusCard: Dp = CoreSpacing.radiusCard
    /** Corner radius for pills / chips. */
    val radiusPill: Dp = CoreSpacing.radiusPill
    /** Focus-ring stroke width (the accent border). */
    val focusRingWidth: Dp = CoreSpacing.focusRingWidth
    /** Resting (unfocused) hairline width. */
    val hairlineWidth: Dp = CoreSpacing.hairlineWidth
    /** A raised card / section container's elevation. */
    val elevationCard: Dp = CoreSpacing.elevationCard
    /** A modal panel's elevation (select overlay, add-picker). */
    val elevationModal: Dp = CoreSpacing.elevationModal
    /** Minimum touch/focus target on a handheld. */
    val minTarget: Dp = CoreSpacing.minTarget
}
