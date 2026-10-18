package com.gruning.mimir.rewrite.core.companionnative

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import kotlin.math.min

/**
 * OR-11: the primary quick menu's key-focus ring, one View laid over the whole card above the coins.
 *
 * Drawing it on top, in the card's own coordinates, is what keeps it out of the clipping the coin slots would impose: the
 * coin sits at the top of its slot with no headroom, and a ring drawn by the coin itself would be cut at the slot edge.
 * The ring hugs the coin's disc (it is the lit coin's one edge: the coin draws no rim while it is ringed) and reaches under 4 dp outside the coin box, the gap to the label under it, so it never lands on a label.
 *
 * It is not a focus target and takes no touch: the focus is an index the controller keeps, and every row stays a plain
 * View that is not focusable. Colour is the player's ring colour ([CompanionOverlayTheme.focusRingArgb]) lit from the top
 * and shaded from the bottom like the app's own ring.
 */
internal class CompanionMenuFocusRingView(
    context: Context,
    ringArgb: Int,
    private val marks: List<View>,
    private val host: ViewGroup,
) : View(context) {
    private val stops = quickMenuRingStops(ringArgb)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = Rect()

    /**
     * The coin wearing the ring, or null for none. The lit coin hands its rim to the ring ([CompanionMenuCoinView.ringed]) so the ring is its one edge; every other coin keeps
     * its rim. The ring and the coin's rim change on the same line, so they can never both show.
     */
    var litIndex: Int? = null
        set(value) {
            if (field == value) return
            field = value
            marks.forEachIndexed { index, mark -> (mark as? CompanionMenuCoinView)?.ringed = index == value }
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mark = litIndex?.let { index -> marks.getOrNull(index) } ?: return
        if (mark.width <= 0 || mark.height <= 0) return
        rect.set(0, 0, mark.width, mark.height)
        host.offsetDescendantRectToMyCoords(mark, rect)
        val density = resources.displayMetrics.density
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val radius = min(rect.width(), rect.height()) / 2f + QuickMenuRingOutsetDp * density
        paint.strokeWidth = QuickMenuRingStrokeDp * density
        paint.shader = LinearGradient(
            0f,
            cy - radius,
            0f,
            cy + radius,
            intArrayOf(
                argbWithAlpha(stops.top, QuickMenuRingAlpha),
                argbWithAlpha(stops.base, QuickMenuRingAlpha),
                argbWithAlpha(stops.bottom, QuickMenuRingAlpha),
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
    }
}
