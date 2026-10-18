package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * A caps micro-chip that is laid directly on ARTWORK Mimir does not control.
 *
 * Every other over-art label in the app sits on the tile's own chrome, so its contrast is decided by two
 * palette tokens and is verifiable by reading them. This one is not: the pixels underneath are a provider's
 * grid, a user's upload, a scraped ledge cover, or Mimir's own console line-art — anything from pure white to
 * pure black. So the chip does not tint the art or trust it; it lays an opaque-enough PLATE and prints palette
 * ink on that plate.
 *
 * WHY [MimirOverArtBadgePlateAlpha] IS 0.82 — this is a measured floor, not a taste call. Compositing
 * `surfaceBase * alpha` over the two worst backgrounds an image can present (pure white and pure black) and
 * scoring WCAG 2.1 contrast against `textPrimary` across all nine shipping palettes, the worst case is:
 *
 *   alpha 0.55 → 3.67:1 FAIL · 0.60 → 4.37:1 FAIL · 0.62 → 4.69:1 pass · 0.82 → 9.93:1 pass
 *
 * The binding constraint is Folkvangr over WHITE art: a dark metallic plate thinned by bright art. 0.82 sits well
 * clear of the ~0.61 cliff
 * and clears AAA (7:1) rather than merely AA (4.5:1), so a future palette can move without silently crossing
 * the floor. `MimirOverArtBadgeTest` re-derives all of this from the live palettes and fails if any theme
 * drops below AA — and pins that a deliberately thinner plate WOULD fail, so the floor is falsifiable rather
 * than decorative.
 *
 * The chip is DECORATIVE. It repeats, in visual form, a label the surrounding control already speaks, so it
 * clears its own semantics: adding a second spoken "DEFAULT" node next to an existing one is a screen-reader
 * regression, not an accessibility gain.
 */

/**
 * Plate opacity for a badge drawn over uncontrolled artwork. See the file header for the derivation; do not
 * lower this without re-running [MimirOverArtBadgeColors]'s test against every palette.
 */
const val MimirOverArtBadgePlateAlpha: Float = 0.82f

/** The plate/ink pair for an over-art badge. Pure, so the contrast floor is provable off-device. */
data class MimirOverArtBadgeColors(val plate: Color, val ink: Color)

/**
 * Resolve the over-art badge colors for [palette].
 *
 * `surfaceBase` is deliberately the plate rather than `surfaceRaised`: the chip must read as something laid
 * ON the art, distinct from the tile chrome that frames it, and on dark identities `surfaceBase` is the deeper
 * of the two. Both clear the contrast floor; this one separates better.
 */
fun mimirOverArtBadgeColors(palette: MimirThemePalette): MimirOverArtBadgeColors =
    MimirOverArtBadgeColors(
        plate = palette.surfaceBase.copy(alpha = MimirOverArtBadgePlateAlpha),
        ink = palette.textPrimary,
    )

/**
 * Draw [label] as a caps chip over artwork.
 *
 * @param label the spoken-form label; it is rendered in Mimir's caps chrome treatment.
 * @param visualOverride the exact painted string when the caps form is not a plain uppercase of [label].
 */
@Composable
fun MimirOverArtBadge(
    label: String,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    visualOverride: String? = null,
) {
    val colors = mimirOverArtBadgeColors(palette)
    MimirChromeText(
        text = label,
        visualOverride = visualOverride ?: label.uppercase(),
        style = MimirTypeScale.MicroStatus,
        color = colors.ink,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            // Decorative by contract — the owning control already speaks this label.
            .clearAndSetSemantics { }
            .clip(RoundedCornerShape(MimirSpacing.radiusPill))
            .background(colors.plate)
            .padding(horizontal = MimirSpacing.xs + 2.dp, vertical = 2.dp),
    )
}
