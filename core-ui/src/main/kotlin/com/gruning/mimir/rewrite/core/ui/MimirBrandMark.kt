package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The Mimir brand mark: the ᛗ Mannaz badge glyph and the two badge frames that wear it.
 *
 * These lived three times over (the app's brand lockup, the shelf chrome, the platform hero tile) and the badges lived in the app
 * and in feature-library, so a surface in a third module (the Bottom's) could only fork them. They are here once, unchanged, so
 * Main and the Bottom draw the one mark.
 */

/**
 * The runic wordmark line, `ᛗᛁᛗᛁᚱ · THE WELL OF MEMORY` (shipping `.eok-rune-line`). PROTECTED LORE. One string for every surface that
 * wears it: onboarding's and the cold-start welcome's wordmark (through the app's alias in `MimirBrandLockup.kt`) and the Bottom's
 * lattice header, which cannot see the app.
 */
const val MimirRuneEyebrow = "ᛗᛁᛗᛁᚱ · THE WELL OF MEMORY"

/**
 * Draws the ᛗ Mannaz BADGE glyph: the 64-unit reference geometry (two staves `M20,20 V50` / `M44,20 V50` and crossed diagonals
 * `M20,20 L44,36` / `M44,20 L20,36`, round caps) mapped onto the drawing box, in [ink].
 *
 * One body for the three identical copies it replaced (the brand lockup's, the shelf chrome's, the platform hero tile's). It maps
 * each axis on its own, and for a square box that is bit-for-bit the old `minDimension / 64` form every badge used; a zero-size box
 * draws nothing. The BOOK rune is a separate glyph, deliberately not this one, and stays where it is: feature-settings
 * `ThemeGallery.drawMannaz`, [drawMimirMannaz] in MimirNorseBook.kt, MediaArtFrame's book rune and MimirMannazPin.
 */
fun DrawScope.drawMimirBadgeMannaz(ink: Color) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    // Map the 0..64 author viewport onto the actual box.
    val sx = w / 64f
    val sy = h / 64f
    fun px(x: Float, y: Float) = Offset(x * sx, y * sy)
    val staveW = 4f * minOf(sx, sy)
    val diagW = 3.2f * minOf(sx, sy)

    // Two vertical staves.
    drawLine(ink, px(20f, 20f), px(20f, 50f), strokeWidth = staveW, cap = StrokeCap.Round)
    drawLine(ink, px(44f, 20f), px(44f, 50f), strokeWidth = staveW, cap = StrokeCap.Round)
    // Crossed diagonals (the ᛗ's central X between the posts).
    drawLine(ink, px(20f, 20f), px(44f, 36f), strokeWidth = diagW, cap = StrokeCap.Round)
    drawLine(ink, px(44f, 20f), px(20f, 36f), strokeWidth = diagW, cap = StrokeCap.Round)
}

/**
 * The ᛗ Mannaz identity badge (shipping `.eok-mark`): a rounded neutral navy→bronze frame with a
 * themed hairline and the accent-stroked Mannaz mark dead-centre. The return cover's badge: a dp corner, no studs.
 */
@Composable
fun MimirMannazBadge(palette: MimirThemePalette, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(palette.brandMarkFillTop, palette.brandMarkFillBottom)))
            .border(0.75.dp, palette.emberSoft, RoundedCornerShape(size * 0.28f))
            .drawBehind { drawMimirBadgeMannaz(palette.accent) },
    )
}

/**
 * THE Mimir ᛗ Mannaz mark tile — the shipping lockup / companion "M-button" (rounded-square, navy→bronze
 * fill, themed hairline, accent-stroked ᛗ rune + corner studs), 9/32 corner ratio at any size. Shared so
 * the identity header AND the collapsible hint button render the SAME mark 1:1. The header badge: a percent corner and the four studs.
 */
@Composable
fun MimirMarkBadge(
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 32.dp,
) {
    val shape = RoundedCornerShape(percent = 28) // 9/32 ≈ 0.28, resolution-free
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(shape)
            // Neutral dark navy→bronze frame fill (shipping `.eok-mark`, routed through core-ui tokens).
            .background(
                Brush.linearGradient(listOf(palette.brandMarkFillTop, palette.brandMarkFillBottom)),
            )
            // Themed hairline (was hardcoded gold — D-01 panel re-tints it to the accent).
            .border(0.5.dp, palette.emberSoft, shape)
            // The ᛗ Mannaz mark, stroked in the theme accent, + the four corner studs.
            .drawBehind {
                drawMimirBadgeMannaz(palette.accent)
                drawIdentityStuds(palette)
            },
    )
}

private fun DrawScope.drawIdentityStuds(palette: MimirThemePalette) {
    val r = 1.15f * density
    val inset = 4f * density
    val color = palette.accentBright.copy(alpha = 0.72f)
    drawCircle(color, r, Offset(inset, inset))
    drawCircle(color.copy(alpha = 0.58f), r, Offset(size.width - inset, inset))
    drawCircle(color.copy(alpha = 0.58f), r, Offset(inset, size.height - inset))
    drawCircle(color.copy(alpha = 0.58f), r, Offset(size.width - inset, size.height - inset))
}
