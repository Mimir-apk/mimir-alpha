package com.gruning.mimir.rewrite.core.companionnative

/**
 * Where the coins sit, and how the single-display coin gets there.
 *
 * The Bottom's ᛗ coin (SECONDARY_COMPANION) goes where the player drops it and stays there, on every Mimir page and over every other app, replacing the earlier corner slot. It has ONE remembered position, persisted (the `button` kind under the
 * role's own key), no snap-back and no glide; with nothing saved it starts at [defaultBottomMButtonPosition]. The Bottom's pages reserve nothing for it: the corner it used to
 * rest in belongs to the page's own header controls (the Trackpad plate and the last-app tile).
 *
 * The single-display coin (PRIMARY_SINGLE) is unchanged: a drag settles it on the nearest edge ([snapMButtonToNearestEdge]), and it glides there ([MButtonRestGlides]).
 */

/**
 * a drag that ends on the single-display coin GLIDES the coin to its edge, instead of jumping. `false` is the previous instant move. One
 * constant, so the policy is one word to change. The coin stays tappable throughout (a new touch lands the glide where it was going).
 */
internal const val MButtonRestGlides = true

/**
 * The glide's clock: a pinned local copy of core-ui's `MimirMotion.OverlayMs` (this module cannot read core-ui), on the house easing the module's other animations use
 * ([MButtonRestGlideEasing]). The app's source pin holds it equal to `OverlayMs`.
 */
internal const val MButtonRestGlideMs = 180L

/** The house easing's control points (the same `PathInterpolator(0.25, 0.1, 0.25, 1)` the quick-menu swap and the press response run on). */
internal val MButtonRestGlideEasing = floatArrayOf(0.25f, 0.1f, 0.25f, 1f)

/**
 * How far from the top of the Bottom, in dp, the coin's default seat begins: just below the pages' header row (the row, its rule and the air under it end at about 70 dp on
 * the Thor's Bottom, at font scale 1.0 and 1.3), so a coin that has never been moved covers no header control. A pinned local copy of core-ui's
 * `MimirBottomMarkDefaultTopDp` (this module cannot read core-ui); the app's source pin holds it equal, and the header render tests hold the headers above it.
 */
internal const val MButtonBottomDefaultTopDp = 72

/**
 * The Bottom coin's default position when nothing is saved: the right edge, [safeInset] px in, [top] px down (just below the header row). Pixels in and out, so it runs on the
 * plain JVM; clamped to the display.
 */
internal fun defaultBottomMButtonPosition(
    bounds: Pair<Int, Int>,
    width: Int,
    height: Int,
    safeInset: Int,
    top: Int,
): Pair<Int, Int> = clampCompanionOverlayPosition(
    x = bounds.first - width - safeInset,
    y = top,
    bounds = bounds,
    width = width,
    height = height,
)
