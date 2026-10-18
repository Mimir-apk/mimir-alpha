package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Mimir Alpha :core-ui — the reviewable design-system gallery.
 *
 * A device-free way to review the whole system: all shipping theme palettes as swatch cards (accent, surface
 * ramp, semantic-success, signature background), plus the [MediaArtFrame] with + without art, focused +
 * resting. Renders in the Android Studio / IntelliJ Compose preview — no emulator, no device.
 */

@Preview(name = "Mimir — shipping theme palette gallery", widthDp = 420, heightDp = 1500, showBackground = true, backgroundColor = 0xFF05070A)
@Composable
private fun ThemeGalleryPreview() {
    Column(
        Modifier
            .background(MimirThemePalette.Default.surfaceBase)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "MIMIR — shipping Norse themes",
            style = MimirTypeScale.Title,
            color = MimirThemePalette.BrandGold,
        )
        Text(
            // Corrected 2026-09-01 (found during visual review): this caption asserted the OPPOSITE of
            // `MimirThemeId`’s own KDoc, which states all nine themes
            // are dark and that Folkvangr/Baldr were re-authored as dark METALLIC. Both resolve
            // `metallicPresentation`, i.e. `fieldTone = DARK`, so `usesBrightField` is false for
            // each — the caption described the retired WebView build. §4: copy is product truth,
            // and a design gallery stating a wrong rule teaches it to everyone who opens it.
            "Identity ember + counter-light · green is success only · Folkvangr/Baldr are dark metallic",
            style = MimirTypeScale.Support,
            color = MimirThemePalette.Default.textSecondary,
        )
        MimirThemePalette.all.forEach { palette ->
            ThemeSwatchCard(palette)
        }
    }
}

@Composable
private fun ThemeSwatchCard(palette: MimirThemePalette) {
    // Wrap each card in its own theme so text/surfaces read the palette exactly as the app would.
    MimirTheme(palette.id) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(
                    Brush.verticalGradient(listOf(palette.signatureTop, palette.signatureBottom)),
                )
                .padding(MimirSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(palette.id.label, style = MimirTypeScale.Rail, color = palette.textPrimary)
                Text(
                    palette.presentation.finish.name,
                    style = MimirTypeScale.Pill,
                    color = palette.textMuted,
                )
            }
            Text(
                "Identity ember and counter-light stay distinct.",
                style = MimirTypeScale.Eyebrow,
                color = palette.accent,
            )
            // Color swatches: ember, authored counter-hue, surface ramp, semantic-success.
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
                Swatch(palette.accent)
                Swatch(palette.materials.counterAccent)
                Swatch(palette.surfaceBase)
                Swatch(palette.surfaceRaised)
                Swatch(palette.surfaceHigh)
                Swatch(palette.outline)
                Swatch(palette.success) // GREEN — success only; per-theme VALUE since R15a (deep on Folkvangr/Baldr).
            }
            // A focused vs resting mini media-art pair to show the ring in-theme.
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                MediaArtFrame(
                    title = palette.id.label,
                    platformLabel = "SNES",
                    focused = true,
                    palette = palette,
                    modifier = Modifier.width(88.dp),
                )
                MediaArtFrame(
                    title = "Resting",
                    platformLabel = "PS1",
                    focused = false,
                    palette = palette,
                    modifier = Modifier.width(88.dp),
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color),
    )
}

@Preview(name = "MediaArtFrame — fallback + focus", widthDp = 420, showBackground = true, backgroundColor = 0xFF080D12)
@Composable
private fun MediaArtFramePreview() {
    MimirTheme(MimirThemeId.DEFAULT) {
        Row(
            Modifier
                .background(MimirThemePalette.Default.surfaceBase)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Honest fallback, focused (gold ring).
            MediaArtFrame(
                title = "Chrono Trigger",
                platformLabel = "SNES",
                focused = true,
                modifier = Modifier.width(120.dp),
            )
            // Honest fallback, resting.
            MediaArtFrame(
                title = "Final Fantasy VII",
                platformLabel = "PS1",
                focused = false,
                modifier = Modifier.width(120.dp),
            )
            // "With art" path — caller-supplied art content (a solid swatch stands in for a real cover).
            MediaArtFrame(
                title = "Art wired",
                platformLabel = "GBA",
                focused = false,
                artRef = "media_art/demo.png",
                artContent = { _ ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(MimirThemePalette.BrandGold, MimirThemePalette.Default.surfaceHigh),
                                ),
                            ),
                    )
                },
                modifier = Modifier.width(120.dp),
            )
        }
    }
}
