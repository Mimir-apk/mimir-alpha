package com.gruning.mimir.rewrite.core.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The keyboard trackpad's pointer, as the player can choose it (Settings > Controls > Trackpad): a size, a
 * shape and a color. Core-ui owns the choices, their geometry and the one painter, so the pointer Mimir draws on Main (an
 * Android View in an overlay window, outside any composition) and the preview Settings draws are the same drawing.
 */
enum class TrackpadPointerSize(val dp: Int, val label: String) {
    SMALL(18, "Small"),
    MEDIUM(26, "Medium"),
    LARGE(34, "Large"),
    ;

    /** Tap cycles Small, Medium, Large and back to Small. */
    fun next(): TrackpadPointerSize = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The stored name, or Small for nothing stored or a name this build does not know. */
        fun fromStored(name: String?): TrackpadPointerSize = entries.firstOrNull { it.name == name } ?: SMALL
    }
}

/** Only two shapes are offered; the classic arrow the pad first shipped with is not one of them. */
enum class TrackpadPointerShape(val label: String) {
    /** A thin, sharp arrow. The click lands at its tip. */
    SLIM_ARROW("Slim arrow"),

    /** A small round dot. The click lands at its centre. */
    DOT("Dot"),
    ;

    fun next(): TrackpadPointerShape = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStored(name: String?): TrackpadPointerShape = entries.firstOrNull { it.name == name } ?: SLIM_ARROW
    }
}

/** What the pointer's fill is made of. Every choice keeps the dark edge, so it reads on any background. */
enum class TrackpadPointerColor(val label: String) {
    WHITE("White"),

    /** The metal the ᛗ coin's rune is struck in: the theme's console-icon ramp. */
    THEME_METAL("Theme metal"),

    /** The player's accent color, with the accent stop or temperature they chose. */
    ACCENT("Accent"),
    ;

    fun next(): TrackpadPointerColor = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStored(name: String?): TrackpadPointerColor = entries.firstOrNull { it.name == name } ?: WHITE
    }
}

/** The player's three choices. The default is the small, slim, white pointer, for a new install and for one that never chose. */
data class TrackpadPointerStyle(
    val size: TrackpadPointerSize = TrackpadPointerSize.SMALL,
    val shape: TrackpadPointerShape = TrackpadPointerShape.SLIM_ARROW,
    val color: TrackpadPointerColor = TrackpadPointerColor.WHITE,
) {
    companion object {
        val Default = TrackpadPointerStyle()
    }
}

/**
 * A pointer's fill: one color, or a vertical ramp from the top of the pointer to its bottom. [argb] and [positions] have
 * the same length; one entry is a flat color, more are a gradient.
 */
data class TrackpadPointerFill(val argb: List<Int>, val positions: List<Float>) {
    init {
        require(argb.isNotEmpty() && argb.size == positions.size) { "a fill has one position per color" }
    }

    companion object {
        fun solid(argb: Int) = TrackpadPointerFill(listOf(argb), listOf(0f))
    }
}

/** The dark edge every pointer keeps, whatever its fill and whatever is beneath it. */
const val TrackpadPointerOutlineArgb: Int = (230 shl 24) or (20 shl 16) or (20 shl 8) or 28

/**
 * The fill for [color] under [palette] (the palette the app's covers draw with, appearance overrides applied).
 *
 * Theme metal is the ᛗ coin's own ramp, the four `iconGrad*` stops at 0 / 0.40 / 0.62 / 1 that `CompanionMenuCoinView`
 * strikes the rune with, except that it holds the third stop to the bottom instead of falling into the deepest: the deepest
 * is a shadow tone for a 56 dp coin, and on a thin pointer's tail it would sink into the dark edge. Accent is `accentBright`,
 * the token the ᛗ rune and the Mimir-button picker's coin are inked with.
 */
fun trackpadPointerFill(color: TrackpadPointerColor, palette: MimirThemePalette): TrackpadPointerFill = when (color) {
    TrackpadPointerColor.WHITE -> TrackpadPointerFill.solid(Color.WHITE)
    TrackpadPointerColor.THEME_METAL -> TrackpadPointerFill(
        argb = listOf(
            palette.iconGradLight.toArgb(),
            palette.iconGradHot.toArgb(),
            palette.iconGradMid.toArgb(),
            palette.iconGradMid.toArgb(),
        ),
        positions = listOf(0f, 0.40f, 0.62f, 1f),
    )
    TrackpadPointerColor.ACCENT -> TrackpadPointerFill.solid(palette.accentBright.toArgb())
}

/**
 * Where a pointer's window goes and how big it is, for each shape. Pure, so the click-landing rules are testable without a
 * window manager.
 */
object TrackpadPointerGeometry {
    /** The window is never smaller than this, in pixels. */
    const val MinWindowPx = 16

    /** The dot's diameter as a share of the size choice: a dot the height of an arrow would read as a blob. */
    const val DotShare = 0.7f

    /** The dot is never smaller than this, in pixels. */
    const val MinDotPx = 12

    /** The edge's width, as a share of the window: the arrow's. It never falls below [MinOutlinePx]. */
    const val ArrowOutlineShare = 0.045f

    /** The dot's edge, as a share of its diameter. */
    const val DotOutlineShare = 0.08f

    const val MinOutlinePx = 1.5f

    /**
     * The slim arrow, tip first, as (x, y) fractions of its box with the tip at (0, 0): the left edge runs straight down, the
     * head's other edge leaves the tip at about 40 degrees from it (the classic arrow's was nearly 57), and the tail is
     * 0.14 of the box wide. Seven corners, clockwise.
     */
    val SlimArrow: FloatArray = floatArrayOf(
        0.00f, 0.00f,
        0.00f, 0.84f,
        0.17f, 0.67f,
        0.30f, 1.00f,
        0.43f, 0.945f,
        0.30f, 0.62f,
        0.50f, 0.60f,
    )

    /** The side of the pointer's square window, in pixels. The dot's is even, so its centre is a pixel line. */
    fun windowPx(shape: TrackpadPointerShape, size: TrackpadPointerSize, density: Float): Int = when (shape) {
        TrackpadPointerShape.SLIM_ARROW -> max(MinWindowPx, (size.dp * density).toInt())
        TrackpadPointerShape.DOT -> {
            val diameter = max(MinDotPx, (size.dp * DotShare * density).roundToInt())
            (diameter + 1) / 2 * 2
        }
    }

    /** The width of the dark edge, which is drawn outside the fill so it never eats into it. */
    fun outlinePx(shape: TrackpadPointerShape, windowPx: Int): Float = max(
        MinOutlinePx,
        windowPx * if (shape == TrackpadPointerShape.DOT) DotOutlineShare else ArrowOutlineShare,
    )

    /**
     * The window's top-left for a click at ([x], [y]) on Main.
     *
     * Slim arrow: one pixel down and right of the click, so the window never covers the click point and the click lands on the
     * app beneath; the arrow's tip is drawn at the window's own corner.
     *
     * Dot: the window is centred on the click, so the dot's center is the click, but only where [mayCoverClick] says the window
     * may sit on the click: a trusted overlay (the accessibility overlay), which Android never counts as an occluder. On any
     * other window (the application-overlay fallback) the dot takes the arrow's off-click origin, so its frame never contains
     * the click whatever the opacity there adds up to; the dot is then drawn whole inside its window, which puts its center
     * `windowPx / 2 + 1` pixels down and right of the click, the nearest a window that excludes the click can bring it.
     */
    fun origin(
        shape: TrackpadPointerShape,
        x: Float,
        y: Float,
        windowPx: Int,
        mayCoverClick: Boolean = true,
    ): Pair<Int, Int> = when {
        shape == TrackpadPointerShape.DOT && mayCoverClick -> (x.roundToInt() - windowPx / 2) to (y.roundToInt() - windowPx / 2)
        else -> (floor(x).toInt() + 1) to (floor(y).toInt() + 1)
    }
}

/**
 * Draws the pointer. Everything that allocates (the path, the gradient shader, the paints' settings) happens in [configure];
 * [draw] only puts the two layers on the canvas, so a view's `onDraw` allocates nothing. Anti-aliased, with the dark edge drawn
 * first and the fill over it, so the edge shows only outside the fill.
 */
class MimirTrackpadPointerPainter {
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TrackpadPointerOutlineArgb }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrow = Path()
    private var shape = TrackpadPointerShape.SLIM_ARROW
    private var centre = 0f
    private var outerRadius = 0f
    private var innerRadius = 0f

    /** Lays the pointer out in a square window of [windowPx] pixels, filled with [fill]. */
    fun configure(shape: TrackpadPointerShape, windowPx: Int, fill: TrackpadPointerFill) {
        this.shape = shape
        val outline = TrackpadPointerGeometry.outlinePx(shape, windowPx)
        when (shape) {
            TrackpadPointerShape.SLIM_ARROW -> {
                // The edge is a stroke twice its visible width, centred on the outline of the arrow: the fill drawn over it
                // covers the inner half, and the tip's outer edge lands on the window's corner.
                val inner = windowPx - 2f * outline
                arrow.rewind()
                val corners = TrackpadPointerGeometry.SlimArrow
                for (i in corners.indices step 2) {
                    val px = outline + corners[i] * inner
                    val py = outline + corners[i + 1] * inner
                    if (i == 0) arrow.moveTo(px, py) else arrow.lineTo(px, py)
                }
                arrow.close()
                edge.style = Paint.Style.STROKE
                edge.strokeWidth = 2f * outline
                edge.strokeJoin = Paint.Join.ROUND
            }
            TrackpadPointerShape.DOT -> {
                centre = windowPx / 2f
                outerRadius = centre
                innerRadius = centre - outline
                edge.style = Paint.Style.FILL
            }
        }
        body.shader = null
        body.color = fill.argb.first()
        if (fill.argb.size > 1) {
            body.shader = LinearGradient(
                0f,
                0f,
                0f,
                windowPx.toFloat(),
                fill.argb.toIntArray(),
                fill.positions.toFloatArray(),
                Shader.TileMode.CLAMP,
            )
        }
    }

    fun draw(canvas: Canvas) {
        when (shape) {
            TrackpadPointerShape.SLIM_ARROW -> {
                canvas.drawPath(arrow, edge)
                canvas.drawPath(arrow, body)
            }
            TrackpadPointerShape.DOT -> {
                canvas.drawCircle(centre, centre, outerRadius, edge)
                canvas.drawCircle(centre, centre, innerRadius, body)
            }
        }
    }
}
