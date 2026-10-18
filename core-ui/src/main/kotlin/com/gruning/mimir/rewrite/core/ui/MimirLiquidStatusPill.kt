package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * FUX-04: the glance pill — REAL glyphs (wifi arcs, battery body with live
 * fill + charging bolt), percent, 24h clock — on a theme-aware surface pill: light themes read as the
 * white pill / dark ink of the reference; dark themes as the dark pill / light ink. Static except on
 * receiver ticks; wifi is state DISPLAY only.
 */
@Composable
fun MimirLiquidStatusPill(
    clockLabel: String,
    batteryPercent: Int?,
    charging: Boolean,
    wifiConnected: Boolean,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    // FUX-06: the ORIGINAL clean pill with a touch of Liquid Glass — the picker's material:
    // translucent control gradient + a glass sheen hairline along the top edge. Ink stays textPrimary.
    val ink = palette.textPrimary
    Row(
        modifier = modifier
            .clip(CircleShape)
            .drawBehind {
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(palette.materials.controlTop, palette.materials.controlBottom),
                    ),
                )
                drawLine(
                    color = palette.materials.glassSheen,
                    start = Offset(size.width * 0.12f, 0.75.dp.toPx()),
                    end = Offset(size.width * 0.88f, 0.75.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            .padding(horizontal = MimirSpacing.md, vertical = 6.dp)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Wifi: three arcs over a base dot; disconnected renders muted with only the dot lit.
        val wifiColor = if (wifiConnected) ink else palette.textMuted.copy(alpha = 0.55f)
        androidx.compose.foundation.Canvas(Modifier.size(width = MimirStatusPillWifiWidthDp.dp, height = 13.dp)) {
            val cx = size.width / 2f
            val baseY = size.height - 1.5.dp.toPx()
            val stroke = Stroke(width = 1.8.dp.toPx())
            fun arc(radius: Float, alpha: Float) = drawArc(
                color = wifiColor,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - radius, baseY - radius),
                size = Size(radius * 2f, radius * 2f),
                style = stroke,
                alpha = alpha,
            )
            arc(size.height - 2.dp.toPx(), if (wifiConnected) 1f else 0.5f)
            arc((size.height - 2.dp.toPx()) * 0.62f, if (wifiConnected) 1f else 0.5f)
            drawCircle(color = wifiColor, radius = 1.6.dp.toPx(), center = Offset(cx, baseY))
        }
        // Battery: rounded body + cap, fill tracks the level; a bolt overlays while charging.
        androidx.compose.foundation.Canvas(Modifier.size(width = MimirStatusPillBatteryWidthDp.dp, height = 11.dp)) {
            val capW = 2.dp.toPx()
            val bodyW = size.width - capW - 1.dp.toPx()
            val corner = CornerRadius(2.5.dp.toPx())
            drawRoundRect(
                color = ink,
                size = Size(bodyW, size.height),
                cornerRadius = corner,
                style = Stroke(width = 1.4.dp.toPx()),
            )
            drawRoundRect(
                color = ink,
                topLeft = Offset(bodyW + 1.dp.toPx(), size.height * 0.28f),
                size = Size(capW, size.height * 0.44f),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
            val level = (batteryPercent ?: 0).coerceIn(0, 100) / 100f
            val inset = 2.4.dp.toPx()
            drawRoundRect(
                color = ink,
                topLeft = Offset(inset, inset),
                size = Size((bodyW - inset * 2f) * level, size.height - inset * 2f),
                cornerRadius = CornerRadius(1.2.dp.toPx()),
            )
            if (charging) {
                val bolt = Path().apply {
                    moveTo(bodyW * 0.56f, -1.dp.toPx())
                    lineTo(bodyW * 0.30f, size.height * 0.60f)
                    lineTo(bodyW * 0.50f, size.height * 0.60f)
                    lineTo(bodyW * 0.42f, size.height + 1.dp.toPx())
                    lineTo(bodyW * 0.72f, size.height * 0.38f)
                    lineTo(bodyW * 0.52f, size.height * 0.38f)
                    close()
                }
                drawPath(bolt, color = palette.surfaceHigh)
                drawPath(bolt, color = ink, style = Stroke(width = 1.dp.toPx()))
            }
        }
        Text(text = mimirStatusPillBatteryLabel(batteryPercent), style = MimirTypeScale.Pill, color = ink)
        Text(text = clockLabel, style = MimirTypeScale.Pill, color = ink)
    }
}

/** The pill's wifi glyph and battery glyph widths, in dp: the fixed part of the pill's width ([mimirLiquidStatusPillWidthDp]). */
const val MimirStatusPillWifiWidthDp = 17f
const val MimirStatusPillBatteryWidthDp = 21f

/**
 * The widest this pill stands at this density and font scale, in dp: its horizontal padding, the two glyphs, the gaps between its four parts, and the widest battery label ("100%")
 * and clock ("00:00" / "88:88") set in the pill's own type. The pill's text is in sp, so it GROWS with the font (164 dp at 1.3x where it is about 146 dp at 1x), which is what the
 * Library's header, left of it on the same row, has to leave room for: a constant measured at 1x ran Search under the pill at 1.3x (combined33 sweep). [measurer] and [density] are the
 * caller's own, so what is measured is what the pill draws in that composition.
 */
fun mimirLiquidStatusPillWidthDp(measurer: TextMeasurer, density: Density): Float {
    fun textWidthDp(text: String): Float =
        measurer.measure(
            text = AnnotatedString(text),
            style = MimirTypeScale.Pill,
            softWrap = false,
            maxLines = 1,
            density = density,
        ).size.width / density.density
    val battery = textWidthDp(mimirStatusPillBatteryLabel(100))
    val clock = maxOf(textWidthDp(mimirStatusPillClockLabel(0, 0)), textWidthDp("88:88"))
    val fixed = 2f * MimirSpacing.md.value + MimirStatusPillWifiWidthDp + MimirStatusPillBatteryWidthDp + 3f * MimirSpacing.sm.value
    return fixed + battery + clock
}

/** "87%", "—" when unknown. Charging shows as the drawn bolt, not text. Pure for JVM pinning. */
fun mimirStatusPillBatteryLabel(levelPercent: Int?): String =
    levelPercent?.let { "$it%" } ?: "—"

/** 24h "HH:MM". Pure for JVM pinning. */
fun mimirStatusPillClockLabel(hour: Int, minute: Int): String =
    "%02d:%02d".format(hour, minute)
