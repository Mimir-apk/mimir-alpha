package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * A RetroAchievements medal struck in the Mimir book's own metalwork.
 *
 * The medal is NOT a generic trophy icon. It is drawn from [MimirHeroMaterial] — the same authored volume
 * the coverless-book cover uses — so it reads as hardware off the bound Norse book: [MimirHeroMaterial.hardware]
 * is the clasp/corner metal, the leather gradient backs the boss, and the rim catches the theme's glass sheen.
 * Every one of the 8 identities therefore gets its own medal for free, with no per-theme asset.
 *
 * There is no green here. An earned medal is an ACHIEVEMENT, not a success toast, and the color system
 * reserves green for success only (local-only policy); earned reads as struck,
 * polished metal, locked reads as the same medal unstruck and recessed.
 */
@Composable
fun MimirAchievementMedal(
    state: MimirMedalState,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    palette: MimirThemePalette = LocalMimirPalette.current,
    /** Seeds the per-platform leather tint, matching the coverless-book rule. Null = neutral. */
    platformIdValue: String? = null,
) {
    val hero = palette.materials.hero
    val tint = platformIdValue?.let { mimirPlatformLeatherTint(it) }
    Canvas(modifier.size(size)) {
        drawMimirMedal(
            state = state,
            hero = hero,
            sheen = palette.materials.glassSheen,
            accent = palette.accent,
            tint = tint,
        )
    }
}

/** Earned vs not. Hardcore is a distinct strike, not a color swap — RA treats it as the harder mode. */
enum class MimirMedalState {
    LOCKED,
    EARNED,
    EARNED_HARDCORE,
}

/**
 * Deterministic per-platform leather hue, mirroring [MediaArtBookCover]'s rule so a platform's medals and its
 * coverless books belong to one family. Pure so it can be pinned in a JVM test.
 */
fun mimirPlatformLeatherTint(platformIdValue: String): Color {
    val seed = platformIdValue.lowercase().fold(0) { acc, c -> (acc * 31 + c.code) and 0xFFFF }
    val hue = (seed % 360).toFloat()
    return Color.hsv(hue, 0.42f, 0.55f)
}

/**
 * Draws the medal. Split out of the composable so the geometry is exercised by a plain draw test.
 *
 * Construction: a leather-backed disc, a struck metal boss, eight radial facets (the book's corner-stud
 * motif carried round), a rim, and a sheen arc on the upper-left where the glass material lights it. The
 * hardcore strike adds an inner ring — a second binding, not a second hue.
 */
internal fun DrawScope.drawMimirMedal(
    state: MimirMedalState,
    hero: MimirHeroMaterial,
    sheen: Color,
    accent: Color,
    tint: Color?,
) {
    val earned = state != MimirMedalState.LOCKED
    val radius = kotlin.math.min(size.width, size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)

    // Locked keeps the full geometry but recedes — the user can see the shape of what they have not earned
    // yet, which is the point of showing it at all.
    val alpha = if (earned) 1f else 0.38f

    val leatherTop = if (tint != null) lerp(hero.coverTop, tint, 0.22f) else hero.coverTop
    val leatherBottom = hero.coverBottom
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(leatherTop, leatherBottom),
            startY = center.y - radius,
            endY = center.y + radius,
        ),
        radius = radius,
        center = center,
        alpha = alpha,
    )

    // The struck boss: the book's hardware metal, lit from the upper-left like the leather volume is.
    val metal = if (earned) hero.hardware else lerp(hero.hardware, hero.coverBottom, 0.55f)
    val bossRadius = radius * 0.66f
    drawCircle(
        brush = Brush.linearGradient(
            listOf(lerp(metal, Color.White, 0.30f), metal, lerp(metal, Color.Black, 0.28f)),
            start = Offset(center.x - bossRadius, center.y - bossRadius),
            end = Offset(center.x + bossRadius, center.y + bossRadius),
        ),
        radius = bossRadius,
        center = center,
        alpha = alpha,
    )

    // Eight facets — the corner-stud motif of the bound volume, carried around the boss.
    val facet = Path()
    repeat(8) { i ->
        val angle = (Math.PI / 4.0) * i
        val inner = bossRadius * 0.30f
        val outer = bossRadius * 0.92f
        facet.reset()
        facet.moveTo(
            center.x + (cos(angle) * inner).toFloat(),
            center.y + (sin(angle) * inner).toFloat(),
        )
        facet.lineTo(
            center.x + (cos(angle - 0.18) * outer).toFloat(),
            center.y + (sin(angle - 0.18) * outer).toFloat(),
        )
        facet.lineTo(
            center.x + (cos(angle + 0.18) * outer).toFloat(),
            center.y + (sin(angle + 0.18) * outer).toFloat(),
        )
        facet.close()
        drawPath(
            path = facet,
            color = lerp(metal, if (earned) Color.White else Color.Black, 0.16f),
            alpha = alpha * 0.8f,
        )
    }

    // Rim + the glass sheen arc that ties the medal to the liquid-glass material used by the card.
    drawCircle(
        color = lerp(metal, Color.Black, 0.42f),
        radius = radius - 0.5f,
        center = center,
        style = Stroke(width = radius * 0.11f),
        alpha = alpha,
    )
    if (earned) {
        rotate(degrees = -35f, pivot = center) {
            drawArc(
                color = sheen,
                startAngle = 180f,
                sweepAngle = 92f,
                useCenter = false,
                topLeft = Offset(center.x - radius * 0.86f, center.y - radius * 0.86f),
                size = Size(radius * 1.72f, radius * 1.72f),
                style = Stroke(width = radius * 0.10f),
                alpha = 0.75f,
            )
        }
    }

    // Hardcore = a second binding ring in the theme accent. A distinct STRIKE, never a green/gold swap.
    if (state == MimirMedalState.EARNED_HARDCORE) {
        drawCircle(
            color = accent,
            radius = bossRadius * 0.52f,
            center = center,
            style = Stroke(width = radius * 0.09f),
        )
    }
}

/**
 * The progress label that rides next to the hero title. Pure so the exact string is pinned by a JVM test.
 *
 * Honesty rules baked in:
 *  - Null total = the lookup has not resolved yet, NOT "no achievements" — the caller shows nothing at all
 *    rather than a confident "0".
 *  - A game with an achievement set the user has not started still reads "0/40", because zero-of-known is a
 *    real, earned-nothing state; it is different from unknown.
 */
fun mimirAchievementProgressLabel(unlocked: Int?, total: Int?): String? {
    if (total == null || unlocked == null || total <= 0) return null
    return "${unlocked.coerceIn(0, total)}/$total"
}
