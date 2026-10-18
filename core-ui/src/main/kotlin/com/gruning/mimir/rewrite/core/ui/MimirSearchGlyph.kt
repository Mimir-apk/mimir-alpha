package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The magnifier's square: the one size every search control draws it at (the Activity filter's pill, the gamelist's search bar), so no search reads smaller than another. */
val MimirSearchGlyphSize: Dp = 16.dp

/** A drawn magnifier, so no font has to carry the glyph (a font-rendered one is tiny and asymmetric beside a label). One mark, one size, in [color]. */
@Composable
fun MimirSearchGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = MimirSearchGlyphSize) {
    Canvas(modifier.size(size)) {
        val stroke = 1.6.dp.toPx()
        val radius = this.size.minDimension * 0.32f
        val centre = Offset(this.size.width * 0.42f, this.size.height * 0.42f)
        drawCircle(color = color, radius = radius, center = centre, style = Stroke(width = stroke))
        val handleFrom = Offset(centre.x + radius * 0.72f, centre.y + radius * 0.72f)
        drawLine(color = color, start = handleFrom, end = Offset(this.size.width * 0.92f, this.size.height * 0.92f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
