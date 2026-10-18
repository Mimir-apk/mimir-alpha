package com.gruning.mimir.rewrite.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette

/**
 * MIMIR color tokens — now a THIN FAÇADE over the `:core-ui` active theme palette.
 *
 * Historically a flat gold `object` (one palette). It is now a naming shim so the existing
 * `MimirColors.X` call sites across the app become theme-aware for FREE: each accent/surface/text token
 * reads the live [LocalMimirPalette] (provided by core-ui `MimirTheme(themeId)`), so switching the Norse
 * theme re-skins every surface with no call-site change. This is the idiomatic Compose pattern — exactly
 * how `MaterialTheme.colorScheme` is exposed: a `@Composable @ReadOnlyComposable` getter.
 *
 * The two-accent RULE (local-only policy) is preserved: [Gold] is the theme's
 * accent (decorative + interactive), [SuccessGreen] is SEMANTIC success — success only, never an accent
 * (its ROLE is invariant; its VALUE deepens on the light themes for contrast),
 * [WarningAmber] is semantic partial/not-ready status, and [Danger] is the destructive rose.
 *
 * NOTE: because these are composable getters, a `MimirColors.X` reference is only valid inside
 * composition. That is exactly where UI colors belong; any non-composable use is a real bug the compiler
 * now surfaces. The `:core-ui` DEFAULT palette values are byte-identical to the previous flat tokens, so
 * the default theme is visually unchanged.
 */
object MimirColors {

    // ── Brand / accent (per-theme GOLD family) — was #d4af69 flat; now the active theme's accent ──
    /** The theme's decorative/interactive accent (focus ring, eyebrows, primary action, progress). */
    val Gold: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.accent
    /** The theme's lighter accent — a focused subtitle/count. */
    val GoldBright: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.accentBright
    /** The theme's translucent accent for focus glows / ambient washes (explicit alpha, baked per theme). */
    val GoldGlow: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.accentGlow
    /** Hot detail-target accent (`--eok-ember-hot` in shipping). */
    val EmberHot: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.emberHot

    // ── Semantic success (GREEN) — success ONLY, never a generic accent ──
    /** Ready / offline-first / Placed status green. ROLE is theme-invariant; the VALUE is per-theme for contrast: the light themes (Folkvangr/Baldr) deepen it — the shared mint measured ~1.5:1 on
     *  their pale fields. Dark themes keep `rgb(127,214,168)` byte-identical. */
    val SuccessGreen: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.success
    /** Partial / needs-attention status. Per-theme since R15a — deep amber on the light themes. */
    val WarningAmber: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.warning

    // ── Surfaces (per-theme dark ramp, or light on Folkvangr/Baldr) ──
    /** App base field. */
    val SurfaceBase: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.surfaceBase
    /** Raised surface (cards, the settings rail background). */
    val SurfaceRaised: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.surfaceRaised
    /** Highest surface (a focused/selected row fill). */
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.surfaceHigh
    /** Hairline divider / card border. */
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.outline

    // ── Text (per-theme) ──
    /** Primary text. */
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.textPrimary
    /** Secondary / supporting text. */
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.textSecondary
    /** Muted / disabled text. */
    val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.textMuted

    // ── Danger (destructive confirms only) — per-theme (shipping parity, policy 2026-07-07) ──
    /** Destructive red — per-theme: `#ff6b6b` on dark themes, `#b02634` on the light themes. */
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalMimirPalette.current.danger
}
