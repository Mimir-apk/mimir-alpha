package com.gruning.mimir.rewrite.feature.library

/** Which physical display a shelf evidence capture was taken on (spec §6.3). */
enum class DisplayRole { PRIMARY, COMPANION }

/**
 * SH02 §6.3 — the runtime viewport record that MUST accompany every shelf evidence capture, so a screenshot
 * or frame trace is never read without knowing the exact post-inset geometry it rendered into. Pure value;
 * the app host emits it (logcat, one line via [evidenceLine]) whenever the Home shelf's viewport settles.
 *
 * Field order and names are the spec's verbatim. [aspectRatio] is derived from the post-inset pixels by
 * [of] so the record can never carry an aspect that disagrees with its own dimensions.
 */
data class ShelfViewportEvidence(
    val displayRole: DisplayRole,
    val postInsetWidthPx: Int,
    val postInsetHeightPx: Int,
    val density: Float,
    val densityDpi: Int,
    val xDpi: Float,
    val yDpi: Float,
    val aspectRatio: Float,
    val refreshRateHz: Float,
) {
    /** One stable, grep-able line for logcat / evidence indexes. */
    fun evidenceLine(): String =
        "shelf-viewport role=$displayRole postInsetPx=${postInsetWidthPx}x$postInsetHeightPx " +
            "density=$density dpi=$densityDpi xDpi=$xDpi yDpi=$yDpi " +
            "aspect=${"%.4f".format(aspectRatio)} refreshHz=$refreshRateHz"

    companion object {
        /** Builds the record with [aspectRatio] derived from the post-inset pixels (0 height → 0 aspect). */
        fun of(
            displayRole: DisplayRole,
            postInsetWidthPx: Int,
            postInsetHeightPx: Int,
            density: Float,
            densityDpi: Int,
            xDpi: Float,
            yDpi: Float,
            refreshRateHz: Float,
        ): ShelfViewportEvidence = ShelfViewportEvidence(
            displayRole = displayRole,
            postInsetWidthPx = postInsetWidthPx,
            postInsetHeightPx = postInsetHeightPx,
            density = density,
            densityDpi = densityDpi,
            xDpi = xDpi,
            yDpi = yDpi,
            aspectRatio = if (postInsetHeightPx > 0) postInsetWidthPx.toFloat() / postInsetHeightPx else 0f,
            refreshRateHz = refreshRateHz,
        )
    }
}
