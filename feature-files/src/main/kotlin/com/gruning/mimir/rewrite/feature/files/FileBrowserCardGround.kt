package com.gruning.mimir.rewrite.feature.files

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette

// A file card's ground: the colour its type names and the wash, rule and scanlines it is struck with. Moved out of FileBrowserSurface.kt unchanged (the surface is at its size budget).

/** The hue [hue] names in [palette], or null for the plain surface. `accent` plays in Mimir, `counterAccent`
 * is media, the leather's top colour is a package. */
internal fun fileBrowserCardColor(hue: FileBrowserCardHue, palette: MimirThemePalette): Color? = when (hue) {
    FileBrowserCardHue.NONE -> null
    FileBrowserCardHue.ACCENT -> palette.accent
    FileBrowserCardHue.COUNTER_ACCENT -> palette.materials.counterAccent
    FileBrowserCardHue.COVER -> palette.materials.hero.coverTop
}

/**
 * Strikes a card's ground: the plain [base] surface, a soft vertical wash from the style's hue into it, a
 * 2dp rule along the top edge, and scanlines for a document. Drawn BEHIND the content, so no text is ever
 * painted over by it -- the tint is a second cue, never a contrast problem.
 */
internal fun Modifier.fileBrowserCardGround(base: Color, style: FileBrowserCardStyle, palette: MimirThemePalette): Modifier {
    val wash = fileBrowserCardColor(style.wash, palette)
    val rule = fileBrowserCardColor(style.rule, palette)
    return this.drawBehind {
        if (wash != null) {
            drawRect(Brush.verticalGradient(listOf(lerp(base, wash, FileBrowserCardWashFraction), base)))
        } else {
            drawRect(base)
        }
        if (style.scanlines) {
            val lineColor = Color.Black.copy(alpha = 0.16f)
            val step = 3.dp.toPx()
            var y = 0f
            while (y < size.height) {
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }
        if (rule != null) {
            drawRect(rule, size = Size(size.width, FileBrowserCardRuleDp.dp.toPx()))
        }
    }
}
