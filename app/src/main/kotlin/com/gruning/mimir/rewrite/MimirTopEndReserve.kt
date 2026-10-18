package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.OverlayButtonBounds

/** The top band where a badge counts as covering the corner chrome (the tallest top bar is under this). */
internal const val MimirTopEndReserveBandDp = 120f

/** Air between the reserved chrome and the badge. */
internal const val MimirTopEndReserveGapDp = 8f

/**
 * F10: the reserve [com.gruning.mimir.rewrite.core.ui.LocalMimirTopEndReserve] carries, in dp. The badge's resting
 * square counts only when it sits in the top band on the right half of the display; then everything from its left
 * edge to the display's right edge, plus a gap, is reserved. OR-2's snap keeps it on an edge, so this is the corner.
 */
internal fun mimirTopEndReserveDp(bounds: OverlayButtonBounds?, density: Float): Float {
    if (bounds == null || density <= 0f || bounds.displayWidth <= 0) return 0f
    if (bounds.top / density > MimirTopEndReserveBandDp) return 0f
    if (bounds.left < bounds.displayWidth / 2) return 0f
    return (bounds.displayWidth - bounds.left) / density + MimirTopEndReserveGapDp
}
