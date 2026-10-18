package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * One quiet, code-native identity crest for a themed surface.
 *
 * The glyph vocabulary is shared with [MimirNorseBookCover]. This surface form intentionally drops the book's
 * metallic bevel so it reads as an etched watermark, never another badge or a rune wallpaper.
 */
internal fun DrawScope.drawMimirThemeSignature(
    palette: MimirThemePalette,
    centerX: Float,
    centerY: Float,
    radiusFraction: Float,
    alpha: Float,
) {
    val radius = (min(size.width, size.height) * radiusFraction)
        .coerceIn(6.dp.toPx(), 42.dp.toPx())
    val center = Offset(size.width * centerX, size.height * centerY)
    val opacity = alpha.coerceIn(0f, 1f)
    val ink = palette.materials.counterAccent.copy(alpha = opacity)
    val accent = palette.materials.readableAccent.copy(alpha = opacity * 0.82f)
    val stroke = (radius * 0.13f).coerceIn(0.8.dp.toPx(), 1.8.dp.toPx())

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Color = ink) {
        drawLine(
            color = color,
            start = Offset(center.x + radius * x0, center.y + radius * y0),
            end = Offset(center.x + radius * x1, center.y + radius * y1),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }

    fun ring(radiusScale: Float, color: Color = ink) {
        drawCircle(color, radius * radiusScale, center, style = Stroke(stroke))
    }

    fun rays(count: Int, inner: Float, outer: Float) {
        repeat(count) { index ->
            val radians = Math.toRadians(index * (360.0 / count))
            val dx = cos(radians).toFloat()
            val dy = sin(radians).toFloat()
            line(dx * inner, dy * inner, dx * outer, dy * outer)
        }
    }

    when (mimirNorseBookSignature(palette.id)) {
        MimirNorseBookSignature.WELL_STUD -> {
            line(0f, -1f, 0.72f, 0f)
            line(0.72f, 0f, 0f, 1f)
            line(0f, 1f, -0.72f, 0f)
            line(-0.72f, 0f, 0f, -1f)
        }
        MimirNorseBookSignature.GOLDEN_SUN -> {
            rays(8, 0.48f, 1f)
            ring(0.30f, accent)
        }
        MimirNorseBookSignature.FORGE_SPARK -> {
            line(-0.10f, 1f, 0.18f, 0.08f, accent)
            line(0.18f, 0.08f, -0.20f, -0.48f, accent)
            line(-0.20f, -0.48f, 0.12f, -1f, accent)
        }
        MimirNorseBookSignature.MOON_CRESCENT -> {
            val diameter = radius * 2f
            drawArc(
                color = ink,
                startAngle = 44f,
                sweepAngle = 272f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(diameter, diameter),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = accent,
                startAngle = 78f,
                sweepAngle = 204f,
                useCenter = false,
                topLeft = Offset(center.x - radius * 0.30f, center.y - radius * 0.76f),
                size = Size(radius * 1.52f, radius * 1.52f),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        MimirNorseBookSignature.DREAD_SEAL -> {
            ring(0.86f)
            line(0f, -0.62f, 0f, 0.62f, accent)
            line(-0.48f, -0.24f, 0.48f, 0.24f, accent)
        }
        MimirNorseBookSignature.VOID_EYE -> {
            val eye = Path().apply {
                moveTo(center.x - radius, center.y)
                quadraticTo(center.x, center.y - radius * 0.70f, center.x + radius, center.y)
                quadraticTo(center.x, center.y + radius * 0.70f, center.x - radius, center.y)
                close()
            }
            drawPath(eye, ink, style = Stroke(stroke))
            drawCircle(accent, radius * 0.20f, center)
        }
        MimirNorseBookSignature.WORLD_TREE -> {
            line(0f, 1f, 0f, -1f, accent)
            listOf(-0.45f, 0.05f, 0.48f).forEach { y ->
                line(0f, y, -0.62f, y - 0.38f)
                line(0f, y, 0.62f, y - 0.38f)
            }
        }
        MimirNorseBookSignature.SHIELD_BOSS -> {
            ring(0.88f)
            ring(0.46f, accent)
            rays(6, 0.50f, 0.90f)
        }
        MimirNorseBookSignature.SUMMER_SUN -> {
            rays(10, 0.60f, 1.02f)
            drawCircle(accent, radius * 0.38f, center)
        }
    }
}
