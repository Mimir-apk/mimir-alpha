package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The tooled rune wall. The resting wall is a field of
 * MINIATURE runes tooled into the leather: each one a debossed mark (a dark groove along its upper edge, a light lip along its lower one, the
 * recess a shade darker than the hide, the drum rune's etching), at a very low contrast, densely tiled.
 *
 * It is drawn ONCE per size and theme into a bitmap one wall PERIOD tall ([bakeTooledRuneTile]) and every frame is only [drawImage] calls of that
 * tile (two at rest, so the wall can wrap while it drifts). Nothing at rest draws text. What still draws text, per frame and only while a touch
 * glows, is the glow overlay ([drawRuneGlow]): the few runes a finger, a trail sample or a tap flash is near, in the accent as before.
 *
 * The bounded shimmer (pad9) rides the tile as horizontal bands: each band's alpha swings a little and tints toward the ember a little, each out
 * of step with the next, only while the ember life's window is open. Reduced motion and rest draw the plain tile.
 */
internal object TooledRunes {
    /** The debossed rune: the groove (black, the absence of light), the recess body (black) and the lower lip (the theme's metal light). */
    const val GrooveAlpha = 0.30f
    const val BodyAlpha = 0.10f
    const val LipAlpha = 0.16f

    /** How far the groove and the lip sit from the rune's body, in dp (the groove a little less, as on the drum). */
    const val LipDp = 0.8f
    const val GrooveShare = 0.8f

    /** How many horizontal bands the shimmer and the scroll fade are drawn in (one tile draw each, only while they run). */
    const val Bands = 16

    /** The ember tint of a shimmering band, as a gain on the life's tint mix (the tile's own alpha is already low). */
    const val EmberBandGain = 2.0f
}

/** One baked tile: [image] is as wide as the field and one [period] tall; the wall's runes sit where [RuneWallLayout] puts them. */
internal class TooledRuneTile(val image: ImageBitmap, val key: TooledRuneKey)

/** What a tile was baked for: a new size, density or theme is a new tile, anything else is the same one. */
internal data class TooledRuneKey(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val fontScale: Float,
    val palette: MimirThemePalette,
)

/** Counts tile bakes, so a test can prove the wall is painted once per size and theme, never per frame. */
internal object TooledRuneBakes {
    @Volatile
    var count = 0
        private set

    fun note() {
        count += 1
    }
}

/** Holds the one tile of a field and re-bakes only when the key (size, density, theme) is a new one. */
internal class TooledRuneTileCache {
    private var tile: TooledRuneTile? = null

    fun get(key: TooledRuneKey, bake: () -> TooledRuneTile): TooledRuneTile {
        val held = tile
        if (held != null && held.key == key) return held
        return bake().also { tile = it }
    }
}

/**
 * Bakes the tooled wall for [wall] into a bitmap [TooledRuneKey.widthPx] wide and one wall period tall. A rune near the tile's top or bottom is
 * also drawn at the other end, so the wrapped wall never shows a cut glyph.
 */
internal fun bakeTooledRuneTile(
    key: TooledRuneKey,
    density: Density,
    layoutDirection: LayoutDirection,
    wall: RuneWallLayout,
    layouts: List<TextLayoutResult>,
): TooledRuneTile {
    TooledRuneBakes.note()
    val period = ceil(wall.period).toInt().coerceAtLeast(1)
    val width = key.widthPx.coerceAtLeast(1)
    val image = ImageBitmap(width, period)
    val palette = key.palette
    val lip = TooledRunes.LipDp * key.density
    CanvasDrawScope().draw(density, layoutDirection, Canvas(image), Size(width.toFloat(), period.toFloat())) {
        fun etch(layout: TextLayoutResult, x: Float, y: Float) {
            val at = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f)
            drawText(layout, color = palette.iconGradLight.copy(alpha = TooledRunes.LipAlpha), topLeft = at + Offset(0f, lip))
            drawText(layout, color = Color.Black.copy(alpha = TooledRunes.GrooveAlpha), topLeft = at - Offset(0f, lip * TooledRunes.GrooveShare))
            drawText(layout, color = Color.Black.copy(alpha = TooledRunes.BodyAlpha), topLeft = at)
        }
        for (i in 0 until wall.count) {
            val layout = layouts[wall.glyphs[i]]
            val x = wall.xs[i]
            val y = wall.ys[i]
            val half = layout.size.height / 2f + lip
            etch(layout, x, y)
            if (y - half < 0f) etch(layout, x, y + period)
            if (y + half > period) etch(layout, x, y - period)
        }
    }
    return TooledRuneTile(image, key)
}

/** The floor modulo, for a drift that may be negative. */
private fun wrapRows(value: Int, period: Int): Int = ((value % period) + period) % period

/**
 * Draws destination rows [top] until [bottom] of the wall from the tile, drifted [drift] px (a whole number) down, wrapping at the tile's end.
 * One [drawImage] call, two when the rows straddle the seam.
 */
private fun DrawScope.drawTileRows(tile: TooledRuneTile, drift: Int, top: Int, bottom: Int, alpha: Float, filter: ColorFilter?) {
    val period = tile.image.height
    val width = tile.image.width
    val length = bottom - top
    if (length <= 0 || alpha <= 0f) return
    val from = wrapRows(top - drift, period)
    val first = minOf(length, period - from)
    drawImage(
        image = tile.image,
        srcOffset = IntOffset(0, from),
        srcSize = IntSize(width, first),
        dstOffset = IntOffset(0, top),
        dstSize = IntSize(width, first),
        alpha = alpha,
        colorFilter = filter,
        filterQuality = FilterQuality.None,
    )
    if (first < length) {
        drawImage(
            image = tile.image,
            srcOffset = IntOffset(0, 0),
            srcSize = IntSize(width, length - first),
            dstOffset = IntOffset(0, top + first),
            dstSize = IntSize(width, length - first),
            alpha = alpha,
            colorFilter = filter,
            filterQuality = FilterQuality.None,
        )
    }
}

/** The ember tint for the shimmer's bands: the tile's silhouette in [tint], only its own alpha kept. */
internal fun tooledEmberFilter(tint: Color): ColorFilter = ColorFilter.tint(tint, BlendMode.SrcIn)

/**
 * Draws the tooled wall: the tile at [drift] (rounded to a whole px, the glow overlay uses the same), as one or two image draws at rest. While
 * the player scrolls ([scroll] above 0) its rows fade toward the field's top and bottom edges, as the runes always did; while the ember life's
 * window runs ([shimmerAmplitude] and [shimmerTintMix] above 0) its bands swing a little in strength and tint toward [tintFilter]'s ember, each
 * band out of step with the next. No text is drawn here.
 */
internal fun DrawScope.drawTooledWall(
    tile: TooledRuneTile,
    drift: Float,
    scroll: Float,
    shimmerPhase: Float,
    shimmerAmplitude: Float,
    shimmerTintMix: Float,
    tintFilter: ColorFilter,
) {
    val height = size.height.roundToInt()
    val shift = drift.roundToInt()
    clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
        if (scroll <= 0f && shimmerAmplitude <= 0f && shimmerTintMix <= 0f) {
            drawTileRows(tile, shift, 0, height, 1f, null)
            return@clipRect
        }
        val bands = TooledRunes.Bands
        for (b in 0 until bands) {
            val top = height * b / bands
            val bottom = height * (b + 1) / bands
            val fade = runeScrollAlphaScale(scroll, (top + bottom) / 2f, size.height)
            val strength = if (shimmerAmplitude > 0f) runeRestAlpha(shimmerPhase, b, shimmerAmplitude) / TrackpadVisuals.RestAlpha else 1f
            drawTileRows(tile, shift, top, bottom, (strength * fade).coerceIn(0f, 1f), null)
            if (shimmerTintMix > 0f) {
                val swing = 0.5f + 0.5f * sin(2f * PI.toFloat() * (shimmerPhase + b * TrackpadVisuals.ShimmerStagger))
                drawTileRows(tile, shift, top, bottom, (shimmerTintMix * TooledRunes.EmberBandGain * swing * fade).coerceIn(0f, 1f), tintFilter)
            }
        }
    }
}

/**
 * The glow sources of one frame: the fingers down, the live trail samples and the tap flash, each with its reach and strength, gathered into fixed
 * arrays (no allocation per frame). A source is exactly what the rune wall always tested every rune against.
 */
internal class RuneGlowSources {
    private val capacity = TrackpadPadFeedback.MaxContacts + TrackpadTrail.Size + 1
    val xs = FloatArray(capacity)
    val ys = FloatArray(capacity)
    val reach = FloatArray(capacity)
    val strength = FloatArray(capacity)
    var count = 0
        private set

    fun gather(feedback: TrackpadPadFeedback, nowMs: Long, reachPx: Float) {
        count = 0
        for (contact in feedback.contacts) {
            if (!contact.active) continue
            add(contact.x, contact.y, reachPx, 1f)
        }
        val trail = feedback.trail
        for (k in 0 until TrackpadTrail.Size) {
            val fade = trackpadFade(trail.ts[k], nowMs)
            if (fade > 0f) add(trail.xs[k], trail.ys[k], reachPx, fade * 0.8f)
        }
        val tapFade = trackpadFade(feedback.tapAtMs, nowMs)
        if (tapFade > 0f) add(feedback.tapX, feedback.tapY, reachPx * 0.8f, tapFade)
    }

    private fun add(x: Float, y: Float, reachPx: Float, strengthOf: Float) {
        xs[count] = x
        ys[count] = y
        reach[count] = reachPx
        strength[count] = strengthOf
        count += 1
    }

    /** The strongest glow any source gives a rune at ([x], [y]), 0 to 1. */
    fun glowAt(x: Float, y: Float): Float {
        var glow = 0f
        for (s in 0 until count) glow = maxOf(glow, trackpadGlow(hypot(x - xs[s], y - ys[s]), reach[s], strength[s]))
        return glow.coerceIn(0f, 1f)
    }
}

/**
 * The glow overlay: only the runes a finger, a trail sample or a tap flash is near, in [color], over the tooled wall. The falloff, the reach and
 * the peak are the rune wall's always (pad6): a rune right under a finger is [TrackpadVisuals.PeakAlpha], fading smoothly to nothing at the reach.
 * With nothing glowing it draws nothing at all. [drift] is the same whole-px drift the tile is drawn at, and [period] the tile's own height, so the glowing rune sits on its tooled one.
 */
internal fun DrawScope.drawRuneGlow(
    wall: RuneWallLayout,
    layouts: List<TextLayoutResult>,
    sources: RuneGlowSources,
    color: Color,
    drift: Float,
    period: Float,
    scroll: Float,
) {
    if (sources.count == 0) return
    val height = size.height
    clipRect(left = 0f, top = 0f, right = size.width, bottom = height) {
        for (i in 0 until wall.count) {
            val x = wall.xs[i]
            val y = if (drift == 0f) wall.ys[i] else runeDriftedY(wall.ys[i], drift, period)
            if (y > height) continue
            val lit = sources.glowAt(x, y)
            if (lit <= 0f) continue
            val layout = layouts[wall.glyphs[i]]
            drawText(
                layout,
                color = color.copy(alpha = TrackpadVisuals.PeakAlpha * lit * runeScrollAlphaScale(scroll, y, height)),
                topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f),
            )
        }
    }
}
