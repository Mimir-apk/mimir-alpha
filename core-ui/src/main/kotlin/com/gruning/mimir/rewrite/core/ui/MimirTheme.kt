package com.gruning.mimir.rewrite.core.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily

/**
 * Mimir Alpha :core-ui — THE theme entry point.
 *
 * One call the whole app wraps its content in. It:
 *  1. resolves the [MimirThemePalette] for [themeId],
 *  2. binds it into a dark Material 3 [androidx.compose.material3.ColorScheme] so
 *     stock Material components pick up the theme's accent + surfaces, and
 *  3. exposes the palette + type scale via [LocalMimirPalette] / [LocalMimirTypeScale] so bespoke Mimir
 *     components (focus rings, media-art frames, pills, the shelf) read the FULL token set — including
 *     the things Material can't model, like the strict accent-vs-semantic-success split.
 *
 * The two-accent rule maps the identity ember to Material `primary` and the authored counter-hue to
 * `secondary`. Semantic success is not mapped into the Material scheme; consumers that need success read the
 * palette explicitly. `tertiary` remains in the ember family, so stock components never fall back to baseline
 * Material purple or flatten a theme into one hue.
 *
 * Dark identity (local-only policy): every release theme uses a dark scheme.
 *
 * @param themeId which Norse identity to bind. Defaults to [MimirThemeId.DEFAULT].
 * @param content the app content, themed.
 */
@Composable
fun MimirTheme(
    themeId: MimirThemeId = MimirThemeId.DEFAULT,
    content: @Composable () -> Unit,
) {
    // AP01: the user's appearance overrides (Default accent stop + per-theme focus-ring stop) map over
    // the authored palette here — the ONE resolution point, so every consumer (and live picker previews,
    // which write the same snapshot state) re-themes together.
    val palette = MimirThemePalette.forId(themeId).withAppearanceOverrides()

    val scheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        primaryContainer = palette.surfaceHigh,
        onPrimaryContainer = palette.textPrimary,
        secondary = palette.materials.counterAccent,
        onSecondary = palette.materials.onCounterAccent,
        secondaryContainer = palette.surfaceHigh,
        onSecondaryContainer = palette.textPrimary,
        // Third accent stays in the ember family; it is never semantic success green.
        tertiary = palette.accentBright,
        onTertiary = palette.onAccentBright,
        tertiaryContainer = palette.surfaceHigh,
        onTertiaryContainer = palette.textPrimary,
        background = palette.surfaceBase,
        onBackground = palette.textPrimary,
        surface = palette.surfaceRaised,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceHigh,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.outline,
        error = palette.danger,
        onError = palette.onDanger,
    )

    CompositionLocalProvider(
        LocalMimirPalette provides palette,
        LocalMimirTypeScale provides MimirTypeScale,
        LocalMimirThemeId provides themeId,
        // Calm shine: Utility's switch, published to every window that wears the theme, so no window needs its own
        // provider. Off unless the player turned it on; the gleam primitives read it (MediaArtFrame.kt).
        LocalMimirCalmShine provides MimirCalmShineSettings.enabled,
        // The player's backgrounds, by theme: every window that wears the theme -- both screens,
        // the panels over other apps -- reads the committed grounds here, so a shelf looks its own up by palette
        // (`MimirShelfBackground`, `mimirThemeSceneBackdrop`) and each gallery card shows its theme's image.
        LocalMimirSceneGrounds provides MimirAppearanceOverrides.grounds,
        // The product face reaches bare `Text(fontSize = …)` call sites — which never name a family —
        // only through the ambient text style. Without this, every un-tokenized call site would keep
        // rendering in the platform default while tokenized ones switched, which is the exact
        // inconsistency this shared text style removes.
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = MimirFonts.Sans),
    ) {
        MaterialTheme(colorScheme = scheme, typography = MimirTypography, content = content)
    }
}

/**
 * Material's own ramp, re-based onto the product face .
 *
 * Sizes and weights are Material's defaults on purpose: this lane's job here is the FAMILY. The Mimir
 * surfaces read [MimirTypeScale], not this — Material's ramp only backstops stock components, so
 * re-pitching it would change type nobody asked to change.
 */
private val MimirTypography: Typography = defaultTypographyIn(MimirFonts.Sans)

private fun defaultTypographyIn(family: FontFamily): Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )
}
