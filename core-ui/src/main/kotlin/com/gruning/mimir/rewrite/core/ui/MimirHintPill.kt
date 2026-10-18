package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One quiet, minimal hint surface shared across the product.
 *
 * Before this, the navigation hints spoke three dialects. Home's tile hint and navigation line sat in the
 * liquid-glass panel, a modal sheet with a sheen line and a cover gradient, carrying a one-line legend. Library's
 * legend was a tile-fill pill with a 68 % bright border and INVERTED keycaps (solid light keys), announced by a
 * 48 dp badge with a glow shadow. This is one surface for all of them:
 *  - body and ink are [mimirHintToastRailColors], the toast rail's own solver, already proven 7:1 on every
 *    authored palette with light themes included (MimirFinalShelfPrimitivesTest). Hints and toasts are one family;
 *  - a 1 dp rim at [MimirHintPillDefaults.RimAlpha] of the ink, and nothing else: no sheen, gradient or shadow;
 *  - keycaps are OUTLINED, not filled. A key reads as a key by its outline, and the legend stops being a row of
 *    bright blocks.
 */
object MimirHintPillDefaults {
    val Shape = RoundedCornerShape(MimirSpacing.radiusPill)

    /** The pill's rim: present enough to edge the body on a field of the same tone, never a frame. */
    const val RimAlpha: Float = 0.12f

    /** A keycap's outline. The key's glyph stays at full ink; only the outline is quiet. */
    const val KeyOutlineAlpha: Float = 0.46f

    val KeyHeight: Dp = 16.dp

    /** The large-glyph contrast floor the Guidance rune has to clear to keep the theme's focus colour. */
    const val RuneContrastFloor: Double = 3.0
}

@Immutable
data class MimirHintPillColors(
    val body: Color,
    val ink: Color,
    /** The Guidance rune's ink: the theme's focus colour where it reads on [body], otherwise [ink]. */
    val rune: Color,
)

fun mimirHintPillColors(palette: MimirThemePalette): MimirHintPillColors {
    val base = mimirHintToastRailColors(palette)
    val rune = if (mimirContrastRatio(palette.focusRing, base.background) >= MimirHintPillDefaults.RuneContrastFloor) {
        palette.focusRing
    } else {
        base.content
    }
    return MimirHintPillColors(body = base.background, ink = base.content, rune = rune)
}

/** The pill body. Callers add their own padding; the pill never sizes itself. */
fun Modifier.mimirHintPill(colors: MimirHintPillColors): Modifier =
    clip(MimirHintPillDefaults.Shape)
        .background(colors.body)
        .border(
            width = MimirSpacing.hairlineWidth,
            color = colors.ink.copy(alpha = MimirHintPillDefaults.RimAlpha),
            shape = MimirHintPillDefaults.Shape,
        )

/** An outlined keycap: a circle for one glyph, a pill for a longer label such as "L/R". */
@Composable
fun MimirHintKeycap(
    key: String,
    colors: MimirHintPillColors,
    modifier: Modifier = Modifier,
    /** The outline and glyph ink: [MimirHintPillColors.ink] inside a pill, a surface's own ink on a bare line. */
    ink: Color = colors.ink,
) {
    val wide = key.length > 1
    val shape = if (wide) MimirHintPillDefaults.Shape else CircleShape
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = MimirHintPillDefaults.KeyHeight)
            .height(MimirHintPillDefaults.KeyHeight)
            .border(
                width = MimirSpacing.hairlineWidth,
                color = ink.copy(alpha = MimirHintPillDefaults.KeyOutlineAlpha),
                shape = shape,
            )
            .padding(horizontal = if (wide) 5.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key,
            style = MimirTypeScale.MicroStatus,
            color = ink,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
