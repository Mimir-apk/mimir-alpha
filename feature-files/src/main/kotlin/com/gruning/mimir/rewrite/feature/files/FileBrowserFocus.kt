package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope

/**
 * Focus identity for the file browser.
 *
 * **One flat scope for the whole browser session**, not one scope per directory -- unchanged from
 * slice 5-6, and it survives per-directory focus memory (added 2026-08-14): that memory is a plain
 * `path -> FocusKey` map inside [FileBrowserController], NOT a scope stack, so there is still nothing to
 * push/pop per navigation step and still exactly one scope to pop on close.
 *
 * **Keys are identity-qualified, never index-qualified.** A tile's key is its backing [FileItem][
 * com.gruning.mimir.rewrite.core.files.FileItem]'s absolute path -- never "tile 3, row 2". An
 * index/position key would survive a listing refresh and silently re-target focus onto whatever now
 * sits at that grid cell, the exact defect class `FocusAuthority.clearFocus()`'s own KDoc calls out.
 *
 * **A separate key namespace for the PICK-mode pinned commit row, restored 2026-08-14.** Slice 5-6's
 * `actionKey` gave the "Choose this folder" row an identity distinct from any real path. The
 * design-adoption rebuild dropped it along with the row itself (adoption spec §4/§6 has no such row),
 * which made `onChoose` structurally unreachable from the settings "add ROMs source" flow -- a real
 * regression, not a deliberate drop. [commitKey] restores the same distinct-namespace idiom for
 * [FileBrowserMode.PICK]'s pinned "Use this folder" affordance; see [FileBrowserController]'s KDoc.
 */
internal object FileBrowserFocus {

    /** The scope every [FileBrowserController] instance uses unless the caller supplies its own. */
    val DefaultScope: FocusScope = FocusScope("files")

    /** A tile's key is its backing item's absolute path. */
    fun tileKey(path: String): FocusKey = FocusKey("files/tile$path")

    /**
     * The PICK-mode pinned commit affordance's key for the directory at [currentPath] -- namespaced
     * under a segment ("files/commit") no [tileKey] can ever produce, so a real file/directory can
     * never collide with it regardless of name (mirrors slice 5-6's `actionKey` reasoning exactly).
     * Namespaced BY [currentPath] (not a constant) so navigating into a subdirectory gets its own
     * distinct commit identity -- [FileBrowserFocusTracker] relies on that to unregister the parent
     * directory's stale commit key on every navigation, the same diffing it already does for tiles.
     */
    fun commitKey(currentPath: String): FocusKey = FocusKey("files/commit$currentPath")

    /**
     * F-2's escape hatch: the commit row's new sibling, "Back to `<origin folder name>`" beside the commit row. Exists only while a [FileBrowserPickSession]
     * is open (Extract/Move/Copy in…), and reaches [FileBrowserController.abandonDestinationPick] from
     * ANY depth, not only the volume root / origin `CANCEL` already reaches it from -- see
     * [FileBrowserController.destinationPickPhase]'s own KDoc for the gap this closes.
     *
     * **Namespaced BY [originPath], not by `currentPath` -- deliberately the opposite of [commitKey].**
     * The origin does not change for the life of one pick (only `currentPath` does, as the player
     * wanders), so this key is the SAME value across every directory visited before the row is
     * pressed. Two consequences, both wanted:
     *  - [FileBrowserFocusTracker]'s add-then-remove diff never retires-and-re-adds it on an ordinary
     *    navigation step (unlike [commitKey]) -- it is simply re-passed as "still desired" on every
     *    `load()` for the whole pick, and only drops out of the `desired` set the moment
     *    [FileBrowserController.backToOriginKeyForCurrentMode] stops answering non-null (the pick
     *    ended, or the player walked into a directory where the pinned row itself is suppressed).
     *  - A player who was focused on it, stepped deeper (Enter), and backed out (Up) to the SAME
     *    directory finds [FileBrowserController.focusByDirectory]'s memory still matches it, because
     *    the remembered value and the freshly-recomputed one are byte-identical.
     *
     * Segment ("files/backOrigin") is its own namespace, distinct from both [tileKey]'s and
     * [commitKey]'s, so it can never collide with a real path or with the commit row's own key even
     * when `originPath == currentPath` (the common case: SETTLED, per [FileBrowserDestinationPickPhase]).
     */
    fun backToOriginKey(originPath: String): FocusKey = FocusKey("files/backOrigin$originPath")
}

/**
 * The listing as the ROWS the folio actually renders, in render/tab order: the PICK-mode commit
 * affordance as a row of its own at index 0, then the tome grid
 * chunked [FileBrowserTomeColumns] wide, then the file grid chunked [FileBrowserFileColumns] wide --
 * exactly the chunking `FileBrowserSurface` feeds its `LazyColumn`.
 *
 * This is the model bulk movement reasons over ([fileBrowserPageTarget]); the flat key order every other
 * caller wants is just its flattening ([fileBrowserKeys]). Keeping ONE source for both means the pinned
 * row can never be at index 0 for focus-seeding purposes but somewhere else for paging purposes.
 *
 * The commit row is deliberately a real row here rather than a special case bolted onto the paging
 * arithmetic: it is a genuine focus-graph participant, so it is a genuine paging destination,
 * and PAGE_LEFT from the top of the listing reaches it like any other row.
 *
 * **[backKey], when present, is a SECOND KEY beside the commit key IN THE SAME ROW.** [backKey] can only ever be non-null
 * while [commitKey] also is (see [FileBrowserController.backToOriginKeyForCurrentMode] -- a destination
 * pick's escape hatch cannot exist where the pick's own commit cannot), so the pinned row is either
 * absent, one key, or two -- never a bare [backKey] with no [commitKey] beside it. Kept in the SAME row
 * rather than a row of its own so index 0 stays index 0 ([fileBrowserScrollTargetIndex]'s
 * item-0 special case, and every pitch-list length below are all untouched by whether a pick is open).
 */
internal fun fileBrowserFocusRows(
    partition: FileBrowserPartition,
    grid: FileBrowserGrid,
    commitKey: FocusKey? = null,
    backKey: FocusKey? = null,
): List<List<FocusKey>> {
    val pinned = listOfNotNull(commitKey, backKey)
    return (if (pinned.isEmpty()) emptyList() else listOf(pinned)) +
        partition.tomes.map { FileBrowserFocus.tileKey(it.path) }.chunked(grid.tomeColumns) +
        partition.files.map { FileBrowserFocus.tileKey(it.path) }.chunked(grid.fileColumns)
}

/**
 * Keys for a [FileBrowserPartition], in render/tab order -- [fileBrowserFocusRows] flattened. When
 * [commitKey] is non-null (PICK mode -- see [FileBrowserController]) it is first, in the pinned action row at index 0. [backKey] follows it in the same row (F-2) when a destination pick is
 * open. Every tome, then every file, follows -- matching [FileBrowserPartition.orderedTiles].
 */
internal fun fileBrowserKeys(
    partition: FileBrowserPartition,
    grid: FileBrowserGrid,
    commitKey: FocusKey? = null,
    backKey: FocusKey? = null,
): List<FocusKey> = fileBrowserFocusRows(partition, grid, commitKey, backKey).flatten()

/**
 * How many rows one PAGE_LEFT / PAGE_RIGHT press travels from [rowIndex] -- **measured in dp, not
 * counted in rows.**
 *
 * The stride walks outward one row at a time, adding each row's own pitch, and stops before the
 * accumulated travel would exceed [viewportDp]. That makes it section-aware for free: three 107 dp file
 * rows fit one 350 dp screen, only two 164.5 dp tome rows do, and a jump that crosses the tome/file
 * boundary is measured against the rows it actually crosses rather than against whichever section it
 * started in.
 *
 * **Why a fixed row count could not work, even a per-section one.** The predecessor
 * (`FileBrowserPageRows = 3`, derived from the file pitch and reused for tomes) overshot a tome section
 * by ~1.4 screens -- see [FileBrowserTomeRowPitchDp] for the correction of that reasoning. A per-section
 * stride fixes the same-section cases but not the crossing: PAGE_LEFT from the first file row would jump
 * three rows *upward into the tome grid* at the file stride, 494 dp of travel, the same overshoot in the
 * other direction. Only measuring the rows crossed handles both.
 *
 * The travel is the distance from the top of [rowIndex] to the top of the target, so a forward step
 * accumulates the pitch of the row being LEFT (`rowIndex + stride`) and a backward step the pitch of the
 * row being entered (`rowIndex - stride - 1`).
 *
 * Always at least 1: a row taller than the whole viewport must still be escapable by one press (the
 * caller then clamps to the listing, and a stride that lands nowhere new returns null below rather than
 * re-emitting focus).
 */
internal fun fileBrowserPageStride(
    rowPitchDp: List<Float>,
    rowIndex: Int,
    forward: Boolean,
    viewportDp: Float,
): Int {
    var travelled = 0f
    var stride = 0
    while (true) {
        val crossed = if (forward) rowIndex + stride else rowIndex - stride - 1
        val pitch = rowPitchDp.getOrNull(crossed) ?: break
        if (travelled + pitch > viewportDp) break
        travelled += pitch
        stride += 1
    }
    return stride.coerceAtLeast(1)
}

/**
 * Where one PAGE_LEFT / PAGE_RIGHT press should land: one screenful of rows back or forward from
 * whichever row currently holds focus ([fileBrowserPageStride]), clamped to the listing, or null when
 * the press cannot move anything (nothing focused, focus outside this listing, or already clamped
 * against the end).
 *
 * Returning null for "already at the end" rather than the current key is deliberate: the caller then
 * issues no `requestFocus` at all, so a held shoulder button at the bottom of a listing does not
 * re-emit the same [com.gruning.mimir.rewrite.core.input.FocusState] over and over. It mirrors
 * [com.gruning.mimir.rewrite.core.input.FocusAuthority.moveFocus] returning false at an edge.
 *
 * **Paging preserves the column INDEX, not the visual column position** -- and the two differ when a jump
 * crosses the tome/file boundary, because the sections have different column counts. That is the right
 * trade for a bulk jump (a page is "one screen further down the listing", not a spatial move) and it is
 * the opposite of what directional navigation does, which resolves by real geometry through
 * [fileBrowserTomeVirtualBounds] / [fileBrowserFileVirtualBounds]. A ragged final row is handled by
 * clamping the column to that row's last index, so paging can never land on a key that is not there.
 *
 * @param rowPitchDp one entry per row of [rows], in the same order -- [fileBrowserRowPitchesDp] builds
 *   it from the same partition [fileBrowserFocusRows] was built from. The two lists agreeing is a
 *   contract this function cannot check for itself; see that builder's KDoc.
 * @param grid RC-12: only [FileBrowserGrid.contentViewportDp] is read here, forwarded to
 *   [fileBrowserPageStride] -- the SAME grid [rows] and [rowPitchDp] were built from, or the stride
 *   measures a screenful against a viewport that does not match the rows it is walking.
 */
internal fun fileBrowserPageTarget(
    rows: List<List<FocusKey>>,
    focused: FocusKey?,
    forward: Boolean,
    rowPitchDp: List<Float>,
    grid: FileBrowserGrid,
): FocusKey? {
    if (focused == null || rows.isEmpty()) return null
    val rowIndex = rows.indexOfFirst { focused in it }
    if (rowIndex < 0) return null
    val stride = fileBrowserPageStride(rowPitchDp, rowIndex, forward, grid.contentViewportDp)
    val targetIndex = (rowIndex + if (forward) stride else -stride).coerceIn(0, rows.lastIndex)
    if (targetIndex == rowIndex) return null
    val column = rows[rowIndex].indexOf(focused)
    val targetRow = rows[targetIndex]
    return targetRow[column.coerceAtMost(targetRow.lastIndex)]
}

/**
 * The tile in [partition] whose key equals [focusedKey], or null when nothing in this listing is
 * focused (including "focus belongs to a different scope entirely", which callers signal by passing
 * null rather than making this function scope-aware).
 *
 * Pure and reused by both [FileBrowserController.focusedTile] (a plain, synchronous read for dispatch)
 * and the Compose surface (which instead derives [focusedKey] from a *collected*
 * `FocusAuthority.state`), so the two callers differ only in how reactively they obtain the key --
 * never in how it is resolved to a tile.
 */
internal fun focusedTileAt(
    partition: FileBrowserPartition,
    focusedKey: FocusKey?,
): FileBrowserTile? {
    if (focusedKey == null) return null
    return partition.orderedTiles().firstOrNull { FileBrowserFocus.tileKey(it.path) == focusedKey }
}
