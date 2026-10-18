package com.gruning.mimir.rewrite.feature.library

/*
 * The Bottom platform shelf's density ladder and its paging arithmetic (slice L4 of the Thor Bottom shelf redesign).
 *
 * The player picks how many games a page holds with a 3DS-style - / + stepper in the shelf's header, and the choice is ONE global setting for every
 * platform (the app persists it beside the shelf's filed order; a data wipe puts it back to [STANDARD]). A step is a grid of whole rows: a page is
 * `columns x rows` seats, and the shelf pages vertically between pages instead of scrolling endlessly.
 *
 * Pure, so the numbers are tests rather than claims.
 */

/**
 * One rung of the ladder. [LARGE] is two big tiles, [STANDARD] is today's four columns in two rows, [DENSE] is six columns in three rows with no
 * label (a game with no art still shows the book, which carries its own title on the leather).
 */
enum class CompanionShelfDensity(
    /** What the app writes to its prefs. A stable word, never the ordinal, so reordering the enum can never move a player's choice. */
    val storageKey: String,
    val columns: Int,
    val rows: Int,
    /** Whether a tile draws its title under the art. Off at [DENSE], where the cell is too small to read it. */
    val showsLabel: Boolean,
) {
    LARGE("large", columns = 2, rows = 1, showsLabel = true),
    STANDARD("standard", columns = 4, rows = 2, showsLabel = true),
    DENSE("dense", columns = 6, rows = 3, showsLabel = false),
    ;

    /** Games on one full page. */
    val perPage: Int get() = columns * rows

    /** What the chip shows: the grid, "4×2". */
    val readout: String get() = "$columns×$rows"

    /** A popover row: the grid and its name, "4×2 · Standard". */
    val rungLabel: String get() = "$readout · ${storageKey.replaceFirstChar { it.uppercase() }}"

    /** The next rung toward bigger tiles (the popover's rung above), or null at the first. */
    val larger: CompanionShelfDensity? get() = values().getOrNull(ordinal - 1)

    /** The next rung toward more tiles per page (the popover's rung below), or null at the last. */
    val denser: CompanionShelfDensity? get() = values().getOrNull(ordinal + 1)

    companion object {
        /** The default, and the rung a missing, unknown or corrupt stored value reads as. */
        val Default = STANDARD

        fun fromStorageKey(raw: String?): CompanionShelfDensity = values().firstOrNull { it.storageKey == raw } ?: Default
    }
}

/** How many pages [count] games make at [perPage] a page. There is always at least one, so an empty shelf still has a page to draw nothing on. */
internal fun companionShelfPageCount(count: Int, perPage: Int): Int {
    if (perPage <= 0) return 1
    return ((count.coerceAtLeast(0) + perPage - 1) / perPage).coerceAtLeast(1)
}

/** The page that holds the game at [index] (a seat in the arranged order). */
internal fun companionShelfPageOf(index: Int, perPage: Int): Int =
    if (perPage <= 0) 0 else index.coerceAtLeast(0) / perPage

/** The page that holds [anchor] at [perPage] a page, kept inside the pages that exist: a density step lands on the page that still holds the player's place. */
internal fun companionShelfPageAfterStep(anchor: Int, perPage: Int, count: Int): Int =
    companionShelfPageOf(anchor, perPage).coerceIn(0, companionShelfPageCount(count, perPage) - 1)

/** The page diamonds up to this many pages; past it the strip says "Page n of m" in words, because 44 diamonds do not fit under a 506 dp page. */
internal const val CompanionShelfRunesMaxPages = 12

/** What the page strip says to a reader: the page (counted from 1) and how many there are. */
internal fun companionShelfPageDescription(currentPage: Int, pageCount: Int): String {
    val count = pageCount.coerceAtLeast(1)
    return "Page ${currentPage.coerceIn(0, count - 1) + 1} of $count"
}

/** Main's plural rule for a count: "1 game", "87 games". */
internal fun companionShelfGameCountLabel(count: Int): String = if (count == 1) "1 game" else "$count games"
