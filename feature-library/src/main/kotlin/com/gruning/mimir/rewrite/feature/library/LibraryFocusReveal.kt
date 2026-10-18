package com.gruning.mimir.rewrite.feature.library

/**
 * Mimir Alpha :feature-library — the focused-row VISUAL-SETTLEMENT contract.
 *
 * THE BUG THIS EXISTS TO KILL. The previous rule asked only whether the focused index fell between
 * `visibleItemsInfo.first().index` and `.last().index`. But `visibleItemsInfo` includes **partially**
 * visible items — the row straddling the viewport edge is in that list. The later minimum-reveal repair
 * fixed clipping, but still treated every fully visible row as settled. The app focus could therefore
 * move several logical rows while the panel stayed visually fixed. The replacement contract is: vertical focus must carry the content toward a stable reading seat so the user sees the
 * destination settle, rather than observing only a ring/state change.
 *
 * Reproduced on RP5 in the Android-apps "Add app" picker: focus landed on the clipped bottom row with a
 * full gold focus ring and the list did not move.
 *
 * WHY THIS IS PURE. A decision left inline in a `LaunchedEffect` can only be exercised by an instrumented
 * run on a device. Extracting it — the same discipline `mimirShelfmarkProfile` and
 * `mimirAchievementProgressLabel` already follow — puts the edge cases on the JVM, where they cost
 * milliseconds and no hardware, and leaves the composable a thin applicator.
 *
 * (An earlier revision of this doc justified the extraction by claiming this module has no Compose
 * UI-test infrastructure. That was false and is retracted: `feature-library/src/androidTest` holds four
 * instrumented tests and `core-ui` holds four more. The extraction is still right — a JVM test is cheaper
 * and device-independent — but it is a preference, not a necessity.)
 *
 * Offsets are in the coordinate space of `LazyListItemInfo.offset`, and the viewport bounds are
 * `LazyListLayoutInfo.viewportStartOffset` / `viewportEndOffset` passed through unmodified. Content
 * padding is deliberately NOT added on top of those: the viewport offsets already describe the window in
 * that space, and adding `beforeContentPadding` again would double-count it.
 */
internal sealed interface FocusRevealAction {

    /** The focused row is already at the stable reading seat, or no scroll can be derived. */
    data object None : FocusRevealAction

    /** Initial/content-replacement restoration. This must occur before visible interaction, without motion. */
    data class SeekTo(val index: Int, val scrollOffset: Int) : FocusRevealAction

    /** One visible latest-wins travel to an uncomposed destination; there is no measured follow-up pass. */
    data class AnimateTo(val index: Int, val scrollOffset: Int) : FocusRevealAction

    /**
     * The row is laid out but not at its visual settlement seat. [delta] is the signed content travel:
     * negative moves toward the start, positive toward the end. The scroll owner clamps naturally at
     * the first and last content edges, where the focus is allowed to travel away from the centre.
     */
    data class ScrollBy(val delta: Float) : FocusRevealAction
}

/**
 * Decide what the focused row needs in order to be visibly settled.
 *
 * [focusedItemOffset] / [focusedItemSize] are null when the row is not in `visibleItemsInfo` at all.
 */
internal fun focusRevealAction(
    focusedIndex: Int,
    firstVisibleIndex: Int?,
    visibleCount: Int,
    focusedItemOffset: Int?,
    focusedItemSize: Int?,
    viewportStart: Int,
    viewportEnd: Int,
    estimatedItemSize: Int? = focusedItemSize,
    restoring: Boolean = false,
): FocusRevealAction {
    if (focusedIndex < 0) return FocusRevealAction.None

    val viewportSize = (viewportEnd - viewportStart).coerceAtLeast(0)
    val estimatedSize = estimatedItemSize
        ?.takeIf { it > 0 }
        ?.coerceAtMost(viewportSize.coerceAtLeast(1))
    val centeredScrollOffset = if (viewportSize > 0 && estimatedSize != null) {
        -((viewportSize - estimatedSize) / 2)
    } else {
        0
    }

    // A new list/filter/mode owns no visible transition from the prior content. Seat it before
    // interaction and let the next controller destination use the animated path.
    if (restoring) {
        return FocusRevealAction.SeekTo(focusedIndex, centeredScrollOffset)
    }

    // Nothing laid out yet (first composition, or a filter emptied and refilled the list).
    if (firstVisibleIndex == null || visibleCount <= 0) {
        return FocusRevealAction.SeekTo(focusedIndex, centeredScrollOffset)
    }

    if (focusedItemOffset == null || focusedItemSize == null) {
        // One index-addressed animation seats an uncomposed destination at the estimated reading
        // position. Its result is authoritative; a second measured correction would visibly land twice.
        return FocusRevealAction.AnimateTo(focusedIndex, centeredScrollOffset)
    }

    if (viewportSize <= 0) return FocusRevealAction.None

    // A row taller than the viewport cannot be centred without hiding both boundaries. Preserve its
    // readable leading edge instead.
    if (focusedItemSize >= viewportSize) {
        val delta = focusedItemOffset - viewportStart
        return if (delta == 0) FocusRevealAction.None
        else FocusRevealAction.ScrollBy(delta.toFloat())
    }

    // Stable console-list seat: centre while there is content on both sides. ScrollState clamps the
    // first/last rows, so terminals remain honest and their focus ring travels to the real edge.
    val settledTop = viewportStart + (viewportSize - focusedItemSize) / 2
    val delta = focusedItemOffset - settledTop
    return if (delta == 0) FocusRevealAction.None
    else FocusRevealAction.ScrollBy(delta.toFloat())
}
