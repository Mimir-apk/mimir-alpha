package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp

/** What a measured boundary does when zero arrives before it has a valid viewport to hold. */
enum class MimirBeforeFirstValidViewport {
    RENDER_NOTHING,
    FATAL,
}

/** The three outcomes of validating one measured viewport at a strict layout boundary. */
enum class MimirMeasuredViewportStatus {
    VALID,
    TRANSIENT_ZERO,
    FATAL,
}

data class MimirMeasuredViewportDimensions(
    val width: Dp,
    val height: Dp,
)

/**
 * The boundary decision for one measurement.
 *
 * [dimensions] is null only for a zero before the first valid measurement when the boundary chose
 * [MimirBeforeFirstValidViewport.RENDER_NOTHING]. A held transient zero carries the last valid dimensions;
 * [FATAL][MimirMeasuredViewportStatus.FATAL] always carries the current dimensions so the strict downstream
 * solver can reject them without history masking the authored input.
 */
data class MimirMeasuredViewportDecision internal constructor(
    val status: MimirMeasuredViewportStatus,
    val dimensions: MimirMeasuredViewportDimensions?,
) {
    val shouldRender: Boolean get() = dimensions != null

    /** True only when a zero measurement is retaining a previously valid subtree. */
    val suppressDrawing: Boolean
        get() = status == MimirMeasuredViewportStatus.TRANSIENT_ZERO && dimensions != null

    val width: Dp get() = requireNotNull(dimensions) { "viewport decision has no renderable dimensions" }.width
    val height: Dp get() = requireNotNull(dimensions) { "viewport decision has no renderable dimensions" }.height
}

private class MimirMeasuredViewportHolder {
    var held: MimirMeasuredViewportDimensions? = null
}

/**
 * Validates a measured viewport without weakening the strict classifier or geometry solver downstream.
 *
 * The remembered holder is deliberately plain, not snapshot state: `BoxWithConstraints` content composes
 * during measure, and retaining the last valid dimensions must not schedule another composition. History is
 * consulted only for an exactly-zero dimension. Every finite positive measurement is either current [VALID]
 * input or current [FATAL] input; a previous viewport never replaces it.
 */
@Composable
fun rememberMeasuredViewport(
    maxWidth: Dp,
    maxHeight: Dp,
    inDomain: (widthDp: Float, heightDp: Float) -> Boolean,
    beforeFirstValid: MimirBeforeFirstValidViewport,
): MimirMeasuredViewportDecision {
    val holder = remember { MimirMeasuredViewportHolder() }
    val widthDp = maxWidth.value
    val heightDp = maxHeight.value

    require(!widthDp.isNaN() && !heightDp.isNaN()) { "viewport dimensions must not be NaN" }
    require(widthDp >= 0f && heightDp >= 0f) { "viewport dimensions must not be negative" }

    val current = MimirMeasuredViewportDimensions(maxWidth, maxHeight)
    if (!widthDp.isFinite() || !heightDp.isFinite()) {
        return MimirMeasuredViewportDecision(MimirMeasuredViewportStatus.FATAL, current)
    }

    if (widthDp == 0f || heightDp == 0f) {
        val held = holder.held
        if (held != null) {
            return MimirMeasuredViewportDecision(MimirMeasuredViewportStatus.TRANSIENT_ZERO, held)
        }
        return when (beforeFirstValid) {
            MimirBeforeFirstValidViewport.RENDER_NOTHING ->
                MimirMeasuredViewportDecision(MimirMeasuredViewportStatus.TRANSIENT_ZERO, null)
            MimirBeforeFirstValidViewport.FATAL ->
                MimirMeasuredViewportDecision(MimirMeasuredViewportStatus.FATAL, current)
        }
    }

    if (!inDomain(widthDp, heightDp)) {
        return MimirMeasuredViewportDecision(MimirMeasuredViewportStatus.FATAL, current)
    }

    holder.held = current
    return MimirMeasuredViewportDecision(MimirMeasuredViewportStatus.VALID, current)
}

/**
 * Seats a boundary's existing top-level child at the decided dimensions and suppresses its drawing only
 * while a zero measurement is holding the last valid viewport. The draw modifier is always installed for
 * renderable bounded decisions, so toggling the held state neither adds a layout node nor disposes content.
 * VALID and FATAL decisions call `drawContent()` directly and retain the boundary's prior unclipped drawing.
 */
fun Modifier.mimirMeasuredViewport(decision: MimirMeasuredViewportDecision): Modifier {
    require(decision.shouldRender) { "a viewport with no dimensions cannot render content" }
    return requiredSize(decision.width, decision.height)
        .drawWithContent {
            if (decision.suppressDrawing) {
                clipRect {
                    // Held content stays composed and laid out, but contributes no pixels to the zero frame.
                }
            } else {
                drawContent()
            }
        }
}
