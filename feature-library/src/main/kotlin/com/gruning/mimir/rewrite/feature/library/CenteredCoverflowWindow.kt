package com.gruning.mimir.rewrite.feature.library

internal data class CenteredCoverflowWindow<T>(
    val visibleItems: List<T>,
    val focusedWindowIndex: Int,
    val focusedItemIndex: Int,
)

internal fun <T> centeredCoverflowWindow(
    items: List<T>,
    focusedIndex: Int,
    radius: Int,
): CenteredCoverflowWindow<T> {
    if (items.isEmpty()) {
        return CenteredCoverflowWindow(
            visibleItems = emptyList(),
            focusedWindowIndex = 0,
            focusedItemIndex = 0,
        )
    }

    val total = items.size
    val safeFocus = focusedIndex.coerceIn(0, total - 1)
    val visibleCount = minOf(total, radius.coerceAtLeast(0) * 2 + 1)
    // (visibleCount - 1) / 2, NOT visibleCount / 2 — a no-op for every ODD window (1/3/5/7 = the normal
    // full-radius case) but it fixes EVEN windows: a 2-item list now reads [focused][right] (focus at the
    // left of the pair) instead of [left][focused], matching natural left-to-right navigation (RCP-03).
    val half = (visibleCount - 1) / 2
    val start = (safeFocus - half).coerceIn(0, total - visibleCount)
    val visibleIndexes = (start until start + visibleCount).toList()

    return CenteredCoverflowWindow(
        visibleItems = visibleIndexes.map { items[it] },
        focusedWindowIndex = visibleIndexes.indexOf(safeFocus).coerceAtLeast(0),
        focusedItemIndex = safeFocus,
    )
}

/**
 * A bounded render window over a circular identity ring. Every item appears at most once; small rings mount
 * their complete set, while large rings keep only the focused radius and wrap that window at both ends.
 */
internal fun <T> circularCoverflowWindow(
    items: List<T>,
    focusedIndex: Int,
    radius: Int,
): CenteredCoverflowWindow<T> {
    if (items.isEmpty()) {
        return CenteredCoverflowWindow(
            visibleItems = emptyList(),
            focusedWindowIndex = 0,
            focusedItemIndex = 0,
        )
    }

    val total = items.size
    val safeFocus = Math.floorMod(focusedIndex, total)
    val visibleCount = minOf(total, radius.coerceAtLeast(0) * 2 + 1)
    val leftCount = (visibleCount - 1) / 2
    val visibleIndexes = (0 until visibleCount).map { offset ->
        Math.floorMod(safeFocus - leftCount + offset, total)
    }
    return CenteredCoverflowWindow(
        visibleItems = visibleIndexes.map(items::get),
        focusedWindowIndex = visibleIndexes.indexOf(safeFocus).coerceAtLeast(0),
        focusedItemIndex = safeFocus,
    )
}
