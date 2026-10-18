package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope

/**
 * Synthetic 2D geometry for a tile that may not currently be composed.
 *
 * The folio's content column is a `LazyColumn` of CHUNKED ROWS (each lazy item is one row of up to
 * [FileBrowserTomeColumns] tomes or [FileBrowserFileColumns] files) -- it only composes the visible
 * window, but directional focus still has to reach every tile in the listing, including ones scrolled
 * off-screen. Every tile is therefore registered with [FocusAuthority.registerVirtual] up front (see
 * [FileBrowserFocusTracker]), using this index-derived placeholder geometry so [FocusAuthority.moveFocus]
 * can resolve a real 2D neighbour by its own geometric scoring -- generalises slice 5-6's flat single-
 * column [FileBrowserFocusTracker] (which only ever needed `index * pitch` downward) to the tome/file
 * two-section grid.
 *
 * **This synthetic grid is the ONLY geometry this surface ever navigates by -- corrected 2026-08-14.**
 * The previous revision of this KDoc claimed a composed tile's REAL on-screen bounds, registered by
 * `Modifier.mimirFocusable`, "layer on top without needing this placeholder removed first". That is
 * backwards. `DefaultFocusAuthority`'s private `Entry.bounds` accessor resolves
 * `virtualBounds ?: physicalBounds`, so a virtual registration **shadows** the physical one for as long
 * as it exists -- and [FileBrowserFocusTracker] registers every tile virtually, for the whole time the
 * browser is open. Physical bounds are therefore never consulted here. That is a *safety* property (no
 * mixed synthetic/pixel regime -- one consistent space for every element in the scope), but it means this
 * model must be RIGHT rather than merely well-ordered: nothing downstream corrects it.
 *
 * ([FocusAuthority.registerVirtual]'s interface KDoc implies that the normal register call can
 * update physical bounds when an element becomes focused. The reference implementation instead
 * prioritizes virtual bounds. That documentation/implementation mismatch remains unresolved.)
 *
 * **Both sections are normalised onto one shared width** ([FileBrowserGridWidth]), so a column's synthetic
 * centre sits at the same *fraction* of the row as the tile does on screen -- the tome grid
 * ([FileBrowserTomeColumns] cells) and the file grid ([FileBrowserFileColumns] cells) both span the full
 * content column via `Modifier.weight(1f)`. Before this, both sections used one shared *cell pitch*, so a
 * 4-column tome row only reached 4/5 of a 5-column file row: the rightmost tome sat at 87.5% of the width
 * on screen but scored against the file at 70%, and the rightmost file column was not the NAV_DOWN target
 * of any tome at all. Row pitches are proportional to the normalised cell width (a file cell is roughly
 * square on screen at 98dp tall over ~94dp wide; a tome cover is taller than wide), not pixel-accurate --
 * only the ratio between along-axis and cross-axis distance feeds
 * [FocusAuthority.moveFocus]'s scoring, and that ratio is what has to be believable.
 *
 * The files section is offset strictly below every tome row, matching render order (tomes above files,
 * adoption spec §4) so a downward move out of the bottom tome row lands in the files section rather
 * than short-circuiting through it.
 *
 * **RC-12, 2026-08-21: these are now the AUTHORED CEILING, not "the" column count.** [FileBrowserGrid]'s
 * solver may reduce either below this value for a narrower viewport (never exceed it -- see that file's
 * own KDoc); every function below that used to default its `columns` parameter to one of these now
 * REQUIRES it, so the solved value threads through explicitly and a missed call site is a compile error
 * rather than a silent divergence between what paints and what the focus lattice believes.
 */
internal const val FileBrowserTomeColumns = 4
internal const val FileBrowserFileColumns = 5

/** The shared normalised width both sections span. Arbitrary magnitude; only ratios matter. */
private const val FileBrowserGridWidth = 20_000f
private const val FileBrowserTomeRowPitch = 7_000f
private const val FileBrowserFileRowPitch = 4_600f

/** The "Files · N" section heading and the gap around it, between the last tome row and the first file row. */
private const val FileBrowserSectionGapPitch = 1_300f
private const val FileBrowserCommitRowPitch = 2_300f

/**
 * The folio's content viewport, in dp -- what "one page" means for PAGE_LEFT / PAGE_RIGHT.
 *
 * **Grounded, not guessed.** The earlier paging implementation lacked an equivalent local device measurement. The authored reference is **833 x 468 dp, the Odin's own landscape
 * viewport at 369 dpi**, less the surface's own laid-out chrome.
 *
 * Each term names the CONSTRUCT in `FileBrowserSurface.kt` that owns it rather than a line number: that
 * file is edited in parallel with this one and its line numbers have moved repeatedly, while every value
 * below stayed put.
 *
 * ```
 * content viewport height = 468   viewport              (adoption spec §2)
 *                          - 52   FileBrowserHeader's `.height()`
 *                          -  1   the header divider Box's `.height()`
 *                          - 11   the content Row's `.padding(top = )`
 *                          - 10   the content LazyColumn's `contentPadding` bottom
 *                          - 44   FileBrowserFooterLegend's `.height()`
 *                          = 350 dp
 * ```
 *
 * **RC-12, 2026-08-21: superseded as the LIVE value by [FileBrowserGrid.contentViewportDp].** Every
 * production caller of [fileBrowserPageStride] now reads the solved grid's own `contentViewportDp`
 * (recomputed per measured viewport), not this constant. Retained as the AUTHORED Odin reference --
 * `FileBrowserGrid.Authored`'s own value is proven equal to this one by `FileBrowserFocusRegistrationTest`
 * (the derivation test below), and this constant is what that test and every stride-only unit test in
 * this module still build their fixtures from.
 */
internal const val FileBrowserContentViewportDp = 350f

/**
 * One file row's pitch AT FONT 1.0: the tile's own minimum height plus the gap to the next row.
 *
 * ```
 * file row pitch =  98   FileTile's minimum height (FileBrowserFileTileMinHeightDp)
 *                 +  9   the content LazyColumn's `verticalArrangement` spacing
 *                 = 107 dp
 * ```
 *
 * The AUTHORED reference ([FileBrowserGrid.Authored] and every solve that is not told a font scale): the tile grows with the type ([fileBrowserFileTileHeightDp], avoiding ragged rows at font 1.3), and [fileBrowserSolveGrid] takes its row pitch from that, so at a larger font the pitch is larger than this.
 */
internal const val FileBrowserFileRowPitchDp = 107f

/**
 * One tome row's pitch -- **the number that corrects [fileBrowserPageStride]'s predecessor.**
 *
 * The old `FileBrowserPageRows = 3` was derived from the FILE pitch alone and then reused for tomes on
 * this argument: *"a tome row is TALLER than a file row, so fewer of them fit -- meaning three tome rows
 * is at most one screenful there as well."* **That argument is backwards, and it is the defect.** Fewer
 * rows per screen means three rows is MORE than a screen, not less. At the pitch derived below, three
 * tome rows span ~494 dp against a 350 dp viewport -- **a ~1.4-screen jump that skips a whole band of
 * tomes the player never saw**, which is precisely the failure mode the old KDoc claimed to prevent.
 *
 * ```
 * content column width = 833   viewport                 (adoption spec §2)
 *                       - 36   the content Row's `.padding(start =, end =)`
 *                       - 14   the content Row's `horizontalArrangement` spacing
 *                       -270   FileBrowserInspectorPanel's `.width()`
 *                       -  5   the content LazyColumn's `contentPadding` end
 *                       = 508 dp
 * tome cell width      = (508 - 3 x 12 TomeTileRow `spacedBy`) / 4 FileBrowserTomeColumns
 *                       = 118 dp
 * tome row pitch       = 118 x 1.14   the cover, at FileBrowserTomeAspectRatio (1 : 1.14)
 *                       +  4          TomeTile's Column `verticalArrangement` spacing
 *                       + 17          the child-count meta line (FileBrowserTomeAspectRatio's own KDoc)
 *                       +  9          the content LazyColumn's `verticalArrangement` spacing
 *                       = 164.5 dp
 * ```
 *
 * **An estimate whose slop cannot change the answer, which is why it is safe to use.** The meta line's
 * 17 dp is a text height at `fontScale = 1` and it is not drawn at all for a directory whose child count
 * could not be read, so the real pitch ranges roughly 147-165 dp. Every value in **117-175 dp** floors to
 * a two-row stride against a 350 dp viewport (350/3 = 116.7, 350/2 = 175), so the whole plausible range
 * lands on the same answer. Only a change big enough to leave that band -- a different cover ratio, a
 * second meta line, a resized inspector -- can move the stride, and that is exactly when it SHOULD move.
 *
 * **CONTRACT owed to `FileBrowserSurface.kt`:** every dp above is a literal in that file. If one changes,
 * these constants must be recomputed. `FileBrowserFocusRegistrationTest` restates the arithmetic so the
 * derivation cannot rot into a magic number -- but it restates the surface's literals rather than reading
 * them, so it cannot SEE an edit over there. **RC-12, 2026-08-21: the live enforcement of this contract is
 * now [FileBrowserGrid]'s solver** ([fileBrowserSolveGrid] recomputes `tomeCellWidthDp` /
 * `tomeRowPitchDp` from the SAME six literals every time, per measured viewport, rather than freezing them
 * into a constant), which is also why [FileBrowserGrid.Authored]'s `tomeRowPitchDp` is 164.52 -- the
 * arithmetically exact value this derivation already computes -- rather than this constant's hand-rounded
 * 164.5. This constant is retained as that authored reference, consumed by tests and by nothing in
 * production.
 */
internal const val FileBrowserTomeRowPitchDp = 164.5f

/**
 * [FileBrowserMode.PICK]'s pinned commit row: `FileBrowserCommitRow`'s
 * `.defaultMinSize(minHeight = 46.dp)` (its 13.5sp label inside 2 x 10 dp padding does not reach that
 * floor, so the minimum is the height) plus the same 9 dp inter-row gap.
 */
internal const val FileBrowserCommitRowPitchDp = 55f

/**
 * The dp pitch of every row [fileBrowserFocusRows] produces for this same partition, in the same order
 * -- what [fileBrowserPageStride] measures a screenful against.
 *
 * **This list and that one must stay the same length, and nothing but discipline enforces it.** Both are
 * built from the same `[commit?] + tomes + files` shape, and
 * `FileBrowserFocusRegistrationTest."the pitch list has exactly one entry per focus row"` pins the
 * agreement across empty sections, ragged final rows, and both modes.
 *
 * **The section headings are deliberately NOT modelled.** "Tomes · N" and "Files · N" are real
 * `LazyColumn` items (~22 dp with their gap) but not focus rows, so a jump that crosses the boundary
 * travels slightly further than this list accounts for. Checked rather than waved away: at every current
 * pitch the heading changes no stride the function returns (the closest case, three file rows at 321 dp,
 * still clears 350 dp with a heading added), so modelling it would add a row-type that never alters an
 * answer. If a heading grows or a pitch moves, re-check that claim rather than assuming it survives.
 */
internal fun fileBrowserRowPitchesDp(
    partition: FileBrowserPartition,
    hasCommitRow: Boolean,
    grid: FileBrowserGrid,
): List<Float> =
    listOfNotNull(FileBrowserCommitRowPitchDp.takeIf { hasCommitRow }) +
        List(fileBrowserTomeRowCount(partition.tomes.size, grid.tomeColumns)) { grid.tomeRowPitchDp } +
        List(fileBrowserSectionRowCount(partition.files.size, grid.fileColumns)) { grid.fileRowPitchDp }

/** Rows a section of [count] tiles occupies when laid out [columns] wide -- `chunked`'s own arithmetic. */
private fun fileBrowserSectionRowCount(count: Int, columns: Int): Int =
    if (count <= 0) 0 else (count + columns - 1) / columns

internal fun fileBrowserTomeRowCount(tomeCount: Int, columns: Int): Int =
    fileBrowserSectionRowCount(tomeCount, columns)

internal fun fileBrowserTomeVirtualBounds(index: Int, columns: Int): FocusBounds {
    val cellWidth = FileBrowserGridWidth / columns
    val col = index % columns
    val row = index / columns
    val left = col * cellWidth
    val top = row * FileBrowserTomeRowPitch
    return FocusBounds(left = left, top = top, right = left + cellWidth, bottom = top + FileBrowserTomeRowPitch)
}

internal fun fileBrowserFileVirtualBounds(
    index: Int,
    tomeCount: Int,
    tomeColumns: Int,
    fileColumns: Int,
): FocusBounds {
    val cellWidth = FileBrowserGridWidth / fileColumns
    val tomeRows = fileBrowserTomeRowCount(tomeCount, tomeColumns)
    val sectionTop = tomeRows * FileBrowserTomeRowPitch + (if (tomeCount > 0) FileBrowserSectionGapPitch else 0f)
    val col = index % fileColumns
    val row = index / fileColumns
    val left = col * cellWidth
    val top = sectionTop + row * FileBrowserFileRowPitch
    return FocusBounds(left = left, top = top, right = left + cellWidth, bottom = top + FileBrowserFileRowPitch)
}

/**
 * Synthetic geometry for [FileBrowserMode.PICK]'s pinned commit affordance (policy 6, restored
 * 2026-08-14 -- see [FileBrowserController]'s KDoc): strictly ABOVE tome/file row 0 (negative `top`/
 * `bottom`, both <= row 0's `top = 0`) so [FocusAuthority.moveFocus]'s own `NAV_UP` scoring finds it as
 * the nearest candidate from column 0 of row 0, and column-0-aligned so `NAV_DOWN` from the commit row
 * lands deterministically on the top-LEFT tile rather than depending on which column happened to register
 * first.
 *
 * **Column-0-aligned, NOT full-width, and that is deliberate.** The row IS full width on screen, but
 * giving it full-width synthetic bounds puts its centre exactly between tome columns 1 and 2, which
 * scores those two identically -- `moveFocus`'s `minByOrNull` then resolves the tie by registration
 * order, i.e. by accident. Column-0 alignment makes NAV_DOWN deterministic. The row's real full-width
 * behaviour is expressed where it belongs, in the dispatch table: [fileBrowserCommand] consumes
 * NAV_LEFT/NAV_RIGHT outright while the affordance holds focus, because with no sibling in the row it has
 * no horizontal neighbour to move to.
 *
 * **F-2, 2026-08-18: no longer the UNIQUE candidate above row 0 whenever [fileBrowserBackToOriginVirtualBounds]
 * is also registered.** That sibling occupies column 1's overhead slot, so `moveFocus`'s NAV_UP from tome/file
 * COLUMN 0 still finds this row (nearest, crossAxis 0), but NAV_UP from columns 1..N-1 now finds the back-to-
 * origin key instead -- strictly nearer by the same scoring this KDoc already relies on. Verified against the
 * scorer's actual formula (`alongAxis + crossAxis * 3`), not assumed; see `FileBrowserFocusRegistrationTest`'s
 * two-affordance cases. Column 0 is unaffected: this remains its unique candidate.
 *
 * (Unlike every other registration here, this one is not shadowing a physical registration that matters
 * -- see the file KDoc: virtual bounds win for every element in this scope, composed or not.)
 */
internal fun fileBrowserCommitVirtualBounds(tomeColumns: Int): FocusBounds = FocusBounds(
    left = 0f,
    top = -FileBrowserCommitRowPitch,
    right = FileBrowserGridWidth / tomeColumns,
    bottom = 0f,
)

/**
 * Synthetic geometry for F-2's "Back to `<origin>`" affordance -- [FileBrowserFocus.backToOriginKey]'s
 * sibling of [fileBrowserCommitVirtualBounds] beside the commit key in the SAME pinned row: identical `top`/`bottom` band (strictly above tome/file row 0), one cell to
 * the RIGHT of the commit affordance's own column-0 cell.
 *
 * **Column-1-aligned for the identical reason [fileBrowserCommitVirtualBounds] is column-0-aligned**, not
 * a coincidence of reusing its math: a full-width or centred registration here would re-open the exact
 * ambiguous-NAV_DOWN tie that function's KDoc already refused once. Column-1 alignment is also what makes
 * NAV_DOWN from this row land on tome/file column 1 specifically (deterministic, never a registration-order
 * tiebreak) -- the mirror image of the commit row landing on column 0.
 *
 * **Never registered alone.** [FileBrowserController.backToOriginKeyForCurrentMode] answers non-null only
 * while [FileBrowserController.commitAvailable] does too (a destination pick's escape hatch cannot exist
 * where the pick's own commit cannot), so wherever this bounds function is consulted,
 * [fileBrowserCommitVirtualBounds] is registered in the same [FileBrowserFocusTracker.update] call.
 */
internal fun fileBrowserBackToOriginVirtualBounds(tomeColumns: Int): FocusBounds {
    val cellWidth = FileBrowserGridWidth / tomeColumns
    return FocusBounds(
        left = cellWidth,
        top = -FileBrowserCommitRowPitch,
        right = cellWidth * 2,
        bottom = 0f,
    )
}

/**
 * Maps a focused [FocusKey] to the index of the `LazyColumn` item that contains it -- the "Tomes ·  N"
 * label is item 0 when tomes exist, each tome row is one item, then the "Files · N" label, then each
 * file row. Used by [FileBrowserSurface] to scroll a focused-but-not-yet-composed tile into view. Pure:
 * no `LazyListState`, no Compose type, directly assertable against a hand-built [FileBrowserPartition].
 */
internal fun fileBrowserLazyItemIndex(
    partition: FileBrowserPartition,
    focusedKey: FocusKey?,
    grid: FileBrowserGrid,
): Int? {
    if (focusedKey == null) return null
    var index = 0
    if (partition.tomes.isNotEmpty()) {
        index += 1 // "Tomes · N" heading
        val tomeIndex = partition.tomes.indexOfFirst { FileBrowserFocus.tileKey(it.path) == focusedKey }
        if (tomeIndex >= 0) return index + tomeIndex / grid.tomeColumns
        index += fileBrowserTomeRowCount(partition.tomes.size, grid.tomeColumns)
    }
    if (partition.files.isNotEmpty()) {
        index += 1 // "Files · N" heading
        val fileIndex = partition.files.indexOfFirst { FileBrowserFocus.tileKey(it.path) == focusedKey }
        if (fileIndex >= 0) return index + fileIndex / grid.fileColumns
    }
    return null
}

/**
 * Which `LazyColumn` item the folio must reveal for [focusedKey] -- [fileBrowserLazyItemIndex] shifted
 * for the one item it does not model, or null when nothing needs revealing.
 *
 * [fileBrowserLazyItemIndex] describes the tomes/files-only layout, which is exactly right for
 * [FileBrowserMode.MANAGE] and one short for [FileBrowserMode.PICK], where the pinned commit row is a
 * real, always-first `LazyColumn` item ahead of everything it counts.
 *
 * **The commit row's own case is the point of extracting this.** It maps to item 0 explicitly rather
 * than falling through to null: its key matches nothing [fileBrowserLazyItemIndex] looks for, so before
 * this it produced no scroll at all, on the reasoning that it "is always the topmost item already".
 * Topmost is not the same as *visible* -- NAV_UP out of tome row 0, or a PAGE_LEFT from deep in a long
 * listing, lands focus on a commit row that is scrolled off the top of the viewport. That was masked
 * while every tile also ran `mimirFocusable`'s own `autoBringIntoView`; with that disabled (see
 * [FileBrowserRevealAction]) this surface owns the whole duty, so the case has to be real.
 *
 * @param commitKey the rendered commit row's key, or null when the row is not rendered at all -- the
 *   caller's `commitAvailable`, never `mode` alone (UI-05).
 * @param backKey F-2's "Back to origin" sibling, rendered in the SAME `LazyColumn` item as [commitKey]
 *   (one pinned row, two keys -- see [fileBrowserFocusRows]), so it resolves to the identical item index
 *   0 rather than a row of its own. Only ever non-null alongside a non-null [commitKey].
 */
internal fun fileBrowserScrollTargetIndex(
    partition: FileBrowserPartition,
    focusedKey: FocusKey?,
    commitKey: FocusKey?,
    grid: FileBrowserGrid,
    backKey: FocusKey? = null,
): Int? {
    if (focusedKey == null) return null
    if (focusedKey == commitKey || focusedKey == backKey) return 0
    val base = fileBrowserLazyItemIndex(partition, focusedKey, grid) ?: return null
    return if (commitKey != null) base + 1 else base
}

/**
 * What the folio must do to seat the focused row at its **visual settlement seat** -- the gamelist's own
 * contract, restated here rather than invented.
 *
 * **The model's home, named exactly** (F-02): `FocusRevealAction` /
 * [focusRevealAction][fileBrowserRevealAction]'s reference, `feature-library`'s
 * `LibraryFocusReveal.kt:33-113`, applied by `LibraryScreen.kt:2760-2806`'s
 * `ScrollFocusedLazyItemIntoView`. Its focus could move several logical rows while the panel stayed visually fixed. Vertical focus
 * therefore carries content toward a stable reading seat, so the destination visibly settles instead
 * of changing only a ring or state. It is **copied, not imported**: `feature-files` deliberately does not depend on
 * `feature-library` (see this module's `build.gradle.kts`), and that helper is `internal` to it.
 *
 * **This REPLACES `fileBrowserScrollDelta`, deleted 2026-08-14 -- ledger of the duties it carried:**
 *  1. *"This surface's ONLY scroll system"* -- unchanged and still true. All three `mimirFocusable` call
 *     sites in `FileBrowserSurface` keep `autoBringIntoView = false`; the gamelist does exactly the same
 *     at every one of its own (`LibraryScreen.kt:2852` and `:3228`, `GamelistCarousel.kt:1035`), for the
 *     same stated reason -- `MimirFocusable.kt:43-44`'s own "must not run two scroll systems per focus
 *     move".
 *     Nothing here adds a second scroll owner, and the F-03 directory glide touches no `LazyListState`
 *     at all -- it is `graphicsLayer` alpha/translation only.
 *  2. *"Above-check first, so a row TALLER than the viewport seats its top instead of chasing its
 *     bottom"* -- carried by the `focusedItemSize >= viewportSize` branch below, which is the same
 *     decision (`itemOffset - viewportStart`) reached by the same reasoning.
 *  3. *Near-edge, zero-when-already-inside.* **Deliberately NOT carried -- this is the F-02 fix.** A
 *     near-edge delta is zero for every fully visible row, so N presses moved nothing and press N+1
 *     lurched: the *distance* was right after the earlier change, the *cadence* was still smooth-smooth-jump. The
 *     settlement seat is intentionally non-zero for a fully visible row, which is what makes every press
 *     carry the sheet by one row pitch.
 *  4. *Its four geometric cases* (inside / peeking below / hanging above / taller than the viewport) --
 *     each restated as a `fileBrowserRevealAction` case in `FileBrowserFocusRegistrationTest`.
 *
 * **A disclosed reversal of an earlier decision.** The deleted call site defended top-pinning an
 * uncomposed target: *"for a jump the player asked for, landing the target at the top IS the useful
 * framing."* The settlement model centres it instead ([AnimateTo] carries `centeredScrollOffset`). That
 * is a real reversal of a deliberate choice made yesterday, made only because F-02 names the gamelist as
 * the standard and the gamelist centres. The top-pinning alternative remains a one-line change.
 */
internal sealed interface FileBrowserRevealAction {

    /** The focused row is already at the settlement seat, or no scroll can be derived. */
    data object None : FileBrowserRevealAction

    /**
     * Initial composition or a whole-listing replacement -- **without motion.** A directory change owns
     * no visible travel from the previous listing, so easing one would animate through positions that
     * mean nothing (this tree's own UI-01: a rebuilt tree is not a transition). The state change is
     * acknowledged by the F-03 glide instead; see `FileBrowserSurface`'s navigation-glide region.
     */
    data class SeekTo(val index: Int, val scrollOffset: Int) : FileBrowserRevealAction

    /** One index-addressed travel to a destination that is not composed at all (a page jump, or a
     * restored focus memory far down the listing). Authoritative -- no measured follow-up pass, which
     * is what made the reference's predecessor visibly land twice. */
    data class AnimateTo(val index: Int, val scrollOffset: Int) : FileBrowserRevealAction

    /** The row is laid out but not at its seat. [delta] is signed content travel: negative toward the
     * start, positive toward the end. The scroll owner clamps at the first/last content edges, so the
     * terminals stay honest and focus travels to the real edge there. */
    data class ScrollBy(val delta: Float) : FileBrowserRevealAction
}

/**
 * Decide what the focused row needs in order to be visibly settled -- the pure half, exactly as the
 * reference splits it, so every edge case is assertable on the JVM and the composable stays a thin
 * applicator.
 *
 * **What a unit test here can and cannot prove.** It pins the NUMBER and the branch; it can never show
 * that the surface applies either, and `feature-files` has no Compose harness, so the *felt* cadence --
 * the whole point of F-02 -- is device-only.
 *
 * @param targetIndex the `LazyColumn` item index holding focus ([fileBrowserScrollTargetIndex]) -- an
 *   ITEM index, not a focus-row index, because headings and the commit row are items too.
 * @param focusedItemOffset / @param focusedItemSize null when that item is not in `visibleItemsInfo`.
 * @param estimatedItemSize used only to centre a target that is not laid out yet; see
 *   [fileBrowserFocusRowHeightDp] for where this surface's estimate comes from.
 */
internal fun fileBrowserRevealAction(
    targetIndex: Int,
    firstVisibleIndex: Int?,
    visibleCount: Int,
    focusedItemOffset: Int?,
    focusedItemSize: Int?,
    viewportStart: Int,
    viewportEnd: Int,
    estimatedItemSize: Int? = focusedItemSize,
    restoring: Boolean = false,
): FileBrowserRevealAction {
    if (targetIndex < 0) return FileBrowserRevealAction.None

    val viewportSize = (viewportEnd - viewportStart).coerceAtLeast(0)
    val estimatedSize = estimatedItemSize
        ?.takeIf { it > 0 }
        ?.coerceAtMost(viewportSize.coerceAtLeast(1))
    val centeredScrollOffset = if (viewportSize > 0 && estimatedSize != null) {
        -((viewportSize - estimatedSize) / 2)
    } else {
        0
    }

    // A new listing owns no visible transition from the prior one. Seat it before interaction and let
    // the next controller destination use the animated path.
    if (restoring) return FileBrowserRevealAction.SeekTo(targetIndex, centeredScrollOffset)

    // Nothing laid out yet (first composition, or an empty directory that just refilled).
    if (firstVisibleIndex == null || visibleCount <= 0) {
        return FileBrowserRevealAction.SeekTo(targetIndex, centeredScrollOffset)
    }

    if (focusedItemOffset == null || focusedItemSize == null) {
        return FileBrowserRevealAction.AnimateTo(targetIndex, centeredScrollOffset)
    }

    if (viewportSize <= 0) return FileBrowserRevealAction.None

    // A row taller than the viewport cannot be centred without hiding both boundaries -- preserve its
    // readable leading edge. This is the duty inherited from the deleted `fileBrowserScrollDelta`, whose
    // above-check-first ordering existed for exactly this case.
    if (focusedItemSize >= viewportSize) {
        val delta = focusedItemOffset - viewportStart
        return if (delta == 0) FileBrowserRevealAction.None else FileBrowserRevealAction.ScrollBy(delta.toFloat())
    }

    // The settlement seat: centred while there is content on both sides. `LazyListState` clamps the
    // first/last rows, so the terminals remain honest.
    val settledTop = viewportStart + (viewportSize - focusedItemSize) / 2
    val delta = focusedItemOffset - settledTop
    return if (delta == 0) FileBrowserRevealAction.None else FileBrowserRevealAction.ScrollBy(delta.toFloat())
}

/** The `LazyColumn`'s own `verticalArrangement` spacing, restated -- already an input to both row-pitch
 * derivations above ("+ 9 the content LazyColumn's `verticalArrangement` spacing"). Named here so the
 * pitch -> height subtraction in [fileBrowserFocusRowHeightDp] is not a bare literal. Same
 * `FileBrowserSurface.kt` contract as every other dp in this file. */
internal const val FileBrowserRowGapDp = 9f

/**
 * The laid-out height of the row holding [focusedKey], in dp -- this surface's `estimatedItemSize`.
 *
 * **Why not the reference's own estimate.** `ScrollFocusedLazyItemIntoView` averages every visible
 * item's size, which is exact for the gamelist (uniform rows) and wrong here: the folio's visible items
 * include the ~22 dp "Tomes · N" / "Files · N" headings, which drag the mean well below any real row and
 * would mis-centre an uncomposed target by a visible margin. This module already derives a per-row pitch
 * for paging ([fileBrowserRowPitchesDp]) from the adoption spec's stated viewport, so the honest estimate
 * is that pitch less the inter-row gap it includes.
 *
 * Returns null when nothing is focused or the key is not in this listing -- the caller then passes null
 * through and [fileBrowserRevealAction] falls back to a top-pinned seek, which is the reference's own
 * behaviour when it can derive no size either.
 *
 * @param backKey F-2's sibling of [commitKey] -- see [fileBrowserFocusRows]. Threaded through only so
 *   `focusedKey in it` can match it inside the SAME pinned row; it changes no pitch lookup, because a
 *   two-key pinned row is still exactly one row of [fileBrowserRowPitchesDp]'s output.
 */
internal fun fileBrowserFocusRowHeightDp(
    partition: FileBrowserPartition,
    focusedKey: FocusKey?,
    commitKey: FocusKey?,
    grid: FileBrowserGrid,
    backKey: FocusKey? = null,
): Float? {
    if (focusedKey == null) return null
    val rowIndex = fileBrowserFocusRows(partition, grid, commitKey, backKey).indexOfFirst { focusedKey in it }
    if (rowIndex < 0) return null
    val pitch = fileBrowserRowPitchesDp(partition, hasCommitRow = commitKey != null, grid).getOrNull(rowIndex) ?: return null
    return (pitch - FileBrowserRowGapDp).coerceAtLeast(1f)
}

/**
 * Registers a whole partition's worth of tiles as virtual focus slots, diffing against whatever this
 * tracker registered last time so a directory change never leaves a stale key behind. Add-then-remove
 * order matters -- see slice 5-6's [FileBrowserFocusTracker] precedent, unchanged here.
 *
 * **Scaling: unbounded, and that is now a DECISION rather than an omission (2026-08-14).** This registers
 * every tile in the current directory with no radius window. A shelf-style bounded window was considered
 * and REJECTED for this surface: both of the browser's bulk-movement paths need the whole listing present
 * in the focus graph at once. [FocusAuthority.moveFocus] can only land on a registered key, so a windowed
 * graph would make everything outside the window unreachable until some other mechanism re-centred the
 * window -- and [fileBrowserPageTarget]'s whole point is to jump a screenful of rows at a time, i.e.
 * straight past where any sensible window edge would sit. A window would have to be re-registered on
 * every focus change to stay correct, which trades one unbounded cost for a per-keypress one.
 *
 * What the size actually costs, measured rather than assumed (see
 * `FileBrowserControllerTest."closing a large directory does not walk focus through every tile on the way
 * out"`): the per-load registration is O(tiles) map writes and the per-move scan is O(tiles) -- both
 * cheap and unavoidable. The one genuinely super-linear hazard was in teardown, where unregistering the
 * focused key made `DefaultFocusAuthority` fall focus onto a sibling, which was then itself unregistered,
 * cascading a focus change per remaining tile. That is fixed in [FileBrowserController.close] by popping
 * the scope before clearing, not by shrinking the graph.
 */
internal class FileBrowserFocusTracker {
    private val registered = linkedSetOf<FocusKey>()

    /**
     * RC-12: the partition/keys this tracker last [update]d, remembered so [reproject] can redo the
     * identical iteration against a NEW [FileBrowserGrid] without touching [registered]'s diff machinery
     * at all -- see [reproject]'s own KDoc for why it must never retire.
     */
    private var lastPartition: FileBrowserPartition? = null
    private var lastCommitKey: FocusKey? = null
    private var lastBackKey: FocusKey? = null

    /**
     * @param grid RC-12: the solved geometry every bounds call below reads its column count from --
     *   [FileBrowserGrid.tomeColumns] / [FileBrowserGrid.fileColumns], never the authored ceiling
     *   constants directly, so a narrower viewport's reduced column count is what the focus lattice
     *   navigates by, not what it happens to paint fewer of.
     * @param commitKey when non-null (PICK mode -- [FileBrowserController.commitKeyForCurrentMode]),
     *   also registers the pinned commit affordance, diffed by the same add-then-remove pass as every
     *   tile below -- a directory change gives it a fresh, path-namespaced key
     *   ([FileBrowserFocus.commitKey]), so the outgoing directory's commit key is unregistered exactly
     *   like a tile that dropped out of the new listing, with no special-casing needed here.
     * @param backKey F-2's "Back to origin" sibling ([FileBrowserController.backToOriginKeyForCurrentMode]),
     *   registered beside [commitKey] in the SAME row when non-null. Unlike [commitKey] its identity is
     *   ORIGIN-namespaced, not current-directory-namespaced ([FileBrowserFocus.backToOriginKey]'s own
     *   KDoc), so it survives unchanged across every `update` call for the life of one pick -- the diff
     *   below simply keeps re-adding it to `desired` rather than retiring and re-registering it, and
     *   only drops it the moment a caller stops passing one in (the pick ended, or the pinned row itself
     *   is suppressed at the directory now open).
     */
    fun update(
        authority: FocusAuthority,
        scope: FocusScope,
        partition: FileBrowserPartition,
        grid: FileBrowserGrid,
        commitKey: FocusKey? = null,
        backKey: FocusKey? = null,
    ) {
        val desired = linkedSetOf<FocusKey>()
        commitKey?.let { key ->
            authority.registerVirtual(key, scope, fileBrowserCommitVirtualBounds(grid.tomeColumns))
            desired.add(key)
        }
        backKey?.let { key ->
            authority.registerVirtual(key, scope, fileBrowserBackToOriginVirtualBounds(grid.tomeColumns))
            desired.add(key)
        }
        partition.tomes.forEachIndexed { index, tile ->
            val key = FileBrowserFocus.tileKey(tile.path)
            authority.registerVirtual(key, scope, fileBrowserTomeVirtualBounds(index, grid.tomeColumns))
            desired.add(key)
        }
        partition.files.forEachIndexed { index, tile ->
            val key = FileBrowserFocus.tileKey(tile.path)
            authority.registerVirtual(
                key,
                scope,
                fileBrowserFileVirtualBounds(index, partition.tomes.size, grid.tomeColumns, grid.fileColumns),
            )
            desired.add(key)
        }
        registered
            .filterNot { it in desired }
            .forEach { key -> retire(authority, key) }

        registered.clear()
        registered.addAll(desired)

        lastPartition = partition
        lastCommitKey = commitKey
        lastBackKey = backKey
    }

    /**
     * RC-12: re-registers every key this tracker currently holds against [grid]'s geometry -- same keys,
     * same [FileBrowserPartition] captured at the last [update], only the bounds move.
     *
     * **Never calls [retire] and never touches [registered]'s membership.** [FocusAuthority.registerVirtual]
     * on an already-registered key only overwrites its bounds (`DefaultFocusAuthority.registerVirtual`,
     * `core-input`) -- it cannot trigger `removeEntryAndFallback`, so this is safe to call from a
     * composition effect while a tile in this very set holds focus, with no `clearFocus()` of its own.
     * [retire], by contrast, calls `unregister`, whose fallback branch CAN run if the removed key is
     * focused -- which is exactly why [FileBrowserController.load] clears focus before that pass runs.
     * Reproject has no such guard and must not need one.
     *
     * A no-op when [update] has never run: nothing is registered yet for this to re-project, and the
     * controller's pre-measurement default ([FileBrowserGrid.Authored]) is what the FIRST [update] call
     * (from [FileBrowserController.load]) already registers against.
     */
    fun reproject(authority: FocusAuthority, scope: FocusScope, grid: FileBrowserGrid) {
        val partition = lastPartition ?: return
        lastCommitKey?.let { key ->
            authority.registerVirtual(key, scope, fileBrowserCommitVirtualBounds(grid.tomeColumns))
        }
        lastBackKey?.let { key ->
            authority.registerVirtual(key, scope, fileBrowserBackToOriginVirtualBounds(grid.tomeColumns))
        }
        partition.tomes.forEachIndexed { index, tile ->
            val key = FileBrowserFocus.tileKey(tile.path)
            authority.registerVirtual(key, scope, fileBrowserTomeVirtualBounds(index, grid.tomeColumns))
        }
        partition.files.forEachIndexed { index, tile ->
            val key = FileBrowserFocus.tileKey(tile.path)
            authority.registerVirtual(
                key,
                scope,
                fileBrowserFileVirtualBounds(index, partition.tomes.size, grid.tomeColumns, grid.fileColumns),
            )
        }
    }

    /** Unregisters everything this tracker ever registered. Idempotent. */
    fun clear(authority: FocusAuthority) {
        registered.forEach { key -> retire(authority, key) }
        registered.clear()
        lastPartition = null
        lastCommitKey = null
        lastBackKey = null
    }

    /**
     * Removes a departed key from the focus graph **completely** -- both registrations, not just this
     * tracker's own.
     *
     * **Mechanism B, fixed 2026-08-14.** `unregisterVirtual` alone is not a removal.
     * `DefaultFocusAuthority` DEMOTES rather than removes when the same key also carries physical
     * bounds (its `physicalBounds != null` branch), and at [FileBrowserController.load] the OUTGOING
     * directory's tiles are still composed -- so `Modifier.mimirFocusable` has already registered every
     * one of them with real WINDOW PIXELS. Dropping only the virtual half leaves the whole departed
     * directory alive in the scope, in the *other* coordinate regime: this module's grid is SYNTHETIC
     * (rows at multiples of [FileBrowserTomeRowPitch] = 7 000) while a pixel row sits at ~100. Every
     * ghost therefore scores "just above" every real target -- worked example, a stale 2 800 beating a
     * legitimate 4 650 -- so NAV_UP and NAV_LEFT out of a freshly-loaded directory land on a key from
     * the directory the player just left. [focusedTileAt] resolves that key to nothing, so the
     * inspector blanks and NO tile draws a ring: on the device it reads as the D-pad dying, and it is
     * asymmetric (DOWN, scoring the other way, still works).
     *
     * **Safe by construction, not by luck.** [FileBrowserController.load] calls
     * `FocusAuthority.clearFocus()` BEFORE `update`, so `_state.value.focused` is null throughout this
     * pass and `DefaultFocusAuthority.removeEntryAndFallback`'s focused-key branch cannot match --
     * there is no sibling-fallback cascade, which is the same hazard [FileBrowserController.close]'s
     * pop-before-clear ordering exists to avoid. [clear] is likewise called only after `popScope`.
     *
     * **Idempotent with the tile's own teardown.** `Modifier.mimirFocusable`'s `onDispose` calls
     * `unregister` for the same key when the departed tile finally leaves the tree; by then the entry
     * is gone and `DefaultFocusAuthority.unregister` returns at its own `entries[key] ?: return`.
     *
     * Order is `unregisterVirtual` then `unregister`: the first demotes a both-bounds entry to
     * physical-only, the second then finds no virtual half left and removes it outright. A
     * virtual-only entry (a tile that was never composed -- most of a long listing) is removed by the
     * first call, and the second is the no-op above.
     */
    private fun retire(authority: FocusAuthority, key: FocusKey) {
        authority.unregisterVirtual(key)
        authority.unregister(key)
    }
}

/**
 * The search screen's query field, chips and results each have real focus keys.
 *
 * **A second tracker rather than a mode on [FileBrowserFocusTracker], because they have different
 * lifetimes.** The browse tracker's registrations must survive the whole session and be diffed per
 * directory; these must exist only while search is open and must ALL be gone when it closes, or the
 * browse scope inherits a graph containing six controls and a thousand results that are no longer drawn.
 * Sharing one tracker would mean one `update` call that has to know which half it is refreshing.
 *
 * **What is registered, and why every one of them:**
 *  - the query field and all five chips, so every drawn control is reachable by D-pad or touch;
 *  - **every result, including the ones scrolled out of composition.** The results are a `LazyColumn`, so
 *    `Modifier.mimirFocusable`'s own physical registration exists only for the visible window;
 *    `requestFocus` on an unregistered key is a silent no-op (`DefaultFocusAuthority` returns at
 *    `entries[key] ?: return`), so without this a shoulder-press page jump past the visible window would
 *    do nothing at all.
 *
 * Unbounded, for [FileBrowserFocusTracker]'s stated reason and one more of its own: the page jump exists
 * precisely to travel past where any sensible window edge would sit.
 */
internal class FileBrowserSearchFocusTracker {
    private val registered = linkedSetOf<FocusKey>()

    /**
     * Registers the control row plus one slot per result, retiring anything from the previous publish
     * that the new one does not contain.
     *
     * Add-then-remove, matching [FileBrowserFocusTracker.update] exactly: a result that survives a
     * re-publish is re-registered before the departed ones are dropped, so the graph is never
     * momentarily empty in a scope that is currently active.
     */
    fun update(authority: FocusAuthority, scope: FocusScope, results: List<FileItem>) {
        val desired = linkedSetOf<FocusKey>()
        authority.registerVirtual(FileBrowserSearchFocus.QueryKey, scope, fileBrowserSearchQueryBounds())
        desired.add(FileBrowserSearchFocus.QueryKey)
        FileSearchFilter.entries.forEachIndexed { index, filter ->
            val key = FileBrowserSearchFocus.filterKey(filter)
            authority.registerVirtual(key, scope, fileBrowserSearchFilterBounds(index))
            desired.add(key)
        }
        results.forEachIndexed { index, item ->
            val key = FileBrowserSearchFocus.resultKey(item.path)
            authority.registerVirtual(key, scope, fileBrowserSearchResultBounds(index))
            desired.add(key)
        }
        registered.filterNot { it in desired }.forEach { key -> retire(authority, key) }
        registered.clear()
        registered.addAll(desired)
    }

    /** Unregisters everything this tracker ever registered. Idempotent. */
    fun clear(authority: FocusAuthority) {
        registered.forEach { key -> retire(authority, key) }
        registered.clear()
    }

    /** Both registrations, not just the virtual half — [FileBrowserFocusTracker.retire]'s mechanism B,
     * which applies identically here: a composed result row carries physical WINDOW PIXEL bounds from its
     * own `mimirFocusable`, and `unregisterVirtual` alone would DEMOTE it rather than remove it, leaving a
     * pixel-regime ghost in a scope whose every other member is synthetic. */
    private fun retire(authority: FocusAuthority, key: FocusKey) {
        authority.unregisterVirtual(key)
        authority.unregister(key)
    }
}

// ─────────────────────────────────────────────────────────── F-01 · the Queue screen's focus graph ──

/** The queue's own normalised width. Arbitrary magnitude, like every grid in this file; only ratios feed
 * [FocusAuthority.moveFocus]'s scoring. */
private const val FileBrowserQueueGridWidth = 20_000f

/** One card's vertical pitch — a title line, a subtitle, a chip and a button row. */
private const val FileBrowserQueueCardRowPitch = 3_400f

/**
 * One button's cell width — **FIXED, not a share of the row, and that is the whole point.**
 *
 * Cards carry different numbers of buttons (three conflict answers, two delete settlements, one Dismiss),
 * so dividing the row width by the card's own button count would put button 0 of a one-button card at the
 * horizontal centre and button 0 of a three-button card at one sixth of it. NAV_DOWN from the first would
 * then score the three-button card's MIDDLE button as nearest — landing the ring on `Replace` when the
 * player pressed down onto a conflict card. That is [fileBrowserCommitVirtualBounds]'s lesson exactly
 * ("column-0 alignment makes NAV_DOWN deterministic"), and it matters more here because the wrong default
 * is a destructive one.
 *
 * A fixed cell puts button `n` of every card at the same x, so vertical movement down a stack of mixed
 * cards preserves the column the player is in, and a card too short to have that column falls back to its
 * own nearest button by ordinary geometry.
 */
private const val FileBrowserQueueActionCellWidth = 6_000f

/**
 * Synthetic geometry for the button at [actionIndex] on the card at [cardIndex].
 *
 * [cardIndex] is the card's position in the WHOLE list, not its position among cards that have buttons —
 * a running card occupies a row on screen whether or not anything in it is focusable, and compacting it
 * out would make a NAV_DOWN across it score as if the two neighbours were adjacent.
 */
internal fun fileBrowserQueueActionBounds(cardIndex: Int, actionIndex: Int): FocusBounds {
    val left = actionIndex * FileBrowserQueueActionCellWidth
    val top = cardIndex * FileBrowserQueueCardRowPitch
    return FocusBounds(
        left = left,
        top = top,
        right = (left + FileBrowserQueueActionCellWidth).coerceAtMost(FileBrowserQueueGridWidth),
        bottom = top + FileBrowserQueueCardRowPitch,
    )
}

/**
 * The Queue screen's focus slots — one per button, registered virtually for the same reason the browse
 * folio's tiles are: the cards are a `LazyColumn`, so `Modifier.mimirFocusable`'s physical registration
 * exists only for the visible window, and `requestFocus` on an unregistered key is a silent no-op.
 *
 * **A third tracker rather than a mode on either existing one**, matching [FileBrowserSearchFocusTracker]'s
 * own reasoning: these registrations must exist only while the queue is open and must ALL be gone when it
 * closes, or the browse scope inherits a graph full of buttons for cards nobody is looking at.
 *
 * **Registering nothing is a legitimate outcome.** A queue of running transfers has no buttons at all
 * ([fileBrowserQueueKeys]), so this registers zero keys and the pushed scope holds no focus. That is not a
 * dead end — CANCEL leaves the queue without consulting focus; see [FileBrowserQueueFocus]'s KDoc.
 */
internal class FileBrowserQueueFocusTracker {
    private val registered = linkedSetOf<FocusKey>()

    /** Add-then-remove, matching both trackers above: a button that survives a queue update is
     * re-registered before the departed ones are dropped, so the graph is never momentarily empty in a
     * scope that is currently active. */
    fun update(authority: FocusAuthority, scope: FocusScope, cards: List<FileBrowserQueueCard>) {
        val desired = linkedSetOf<FocusKey>()
        cards.forEachIndexed { cardIndex, card ->
            card.actions.forEachIndexed { actionIndex, action ->
                val key = FileBrowserQueueFocus.actionKey(card.entryId, action)
                authority.registerVirtual(key, scope, fileBrowserQueueActionBounds(cardIndex, actionIndex))
                desired.add(key)
            }
        }
        registered.filterNot { it in desired }.forEach { key -> retire(authority, key) }
        registered.clear()
        registered.addAll(desired)
    }

    /** Unregisters everything this tracker registered. Idempotent. */
    fun clear(authority: FocusAuthority) {
        registered.forEach { key -> retire(authority, key) }
        registered.clear()
    }

    /** Both registrations — [FileBrowserFocusTracker.retire]'s mechanism B, which applies identically to a
     * composed queue button. */
    private fun retire(authority: FocusAuthority, key: FocusKey) {
        authority.unregisterVirtual(key)
        authority.unregister(key)
    }
}

// ────────────────────────────────────────── F-06 · the Extract chooser's focus graph ──

/** The chooser's own normalised width. Arbitrary magnitude, like every grid in this file. */
private const val FileBrowserExtractGridWidth = 20_000f

/** One option row's vertical pitch — a label line and a destination-name line. */
private const val FileBrowserExtractRowPitch = 3_000f

/**
 * Synthetic geometry for the chooser's row at [index].
 *
 * **Full-width rows, one column, and that is the whole geometry.** The chooser is three rows at most
 * ([fileBrowserExtractOptions] can add both destination rows), so there is nothing here for
 * `moveFocus`'s scoring to get wrong: NAV_UP/NAV_DOWN resolve the neighbour above/below, and
 * NAV_LEFT/NAV_RIGHT find no candidate at all and answer false.
 *
 * That is why this screen needs no consumption rule of the kind the browse table has for its own
 * full-width commit row ([fileBrowserCommitVirtualBounds]' NAV_LEFT/RIGHT case). That rule exists
 * because the commit row shares a scope with a multi-column tile grid, so a horizontal press had a
 * geometrically valid — and badly wrong — answer. Here the chooser's scope contains nothing but these
 * rows, so a horizontal press has no answer, and [FileBrowserController.dispatchExtract] absorbs it as
 * the hard modal it is.
 */
internal fun fileBrowserExtractOptionBounds(index: Int): FocusBounds {
    val top = index * FileBrowserExtractRowPitch
    return FocusBounds(
        left = 0f,
        top = top,
        right = FileBrowserExtractGridWidth,
        bottom = top + FileBrowserExtractRowPitch,
    )
}

/**
 * The Extract chooser's focus slots — one per option row.
 *
 * **A fourth tracker rather than a mode on any of the three above**, matching
 * [FileBrowserQueueFocusTracker]'s own reasoning exactly: these registrations must exist only while the
 * chooser is open and must ALL be gone when it closes, or the browse scope inherits focus slots for rows
 * nobody is looking at. The virtual registration is not optional here even though the chooser is short
 * and always composed — `DefaultFocusAuthority.requestFocus` is a silent no-op on an unregistered key,
 * so [FileBrowserController.openExtractChooser]'s landing would simply not happen, leaving a pushed
 * scope with no focused row and a screen whose D-pad does nothing.
 */
internal class FileBrowserExtractFocusTracker {
    private val registered = linkedSetOf<FocusKey>()

    /** Add-then-remove, matching all three trackers above. */
    fun update(authority: FocusAuthority, scope: FocusScope, rows: List<FileBrowserExtractOption>) {
        val desired = linkedSetOf<FocusKey>()
        rows.forEachIndexed { index, option ->
            val key = FileBrowserExtractFocus.optionKey(option.kind)
            authority.registerVirtual(key, scope, fileBrowserExtractOptionBounds(index))
            desired.add(key)
        }
        registered.filterNot { it in desired }.forEach { key -> retire(authority, key) }
        registered.clear()
        registered.addAll(desired)
    }

    /** Unregisters everything this tracker registered. Idempotent. */
    fun clear(authority: FocusAuthority) {
        registered.forEach { key -> retire(authority, key) }
        registered.clear()
    }

    /** Both registrations — [FileBrowserFocusTracker.retire]'s mechanism B, which applies identically to
     * a composed chooser row. */
    private fun retire(authority: FocusAuthority, key: FocusKey) {
        authority.unregisterVirtual(key)
        authority.unregister(key)
    }
}

// ──────────────────────────────────────────── F-01 / B-4 · where focus goes when its tile is deleted ──

/**
 * The nearest key to [departed] that is still in [survivingKeys], searched along the listing's own render
 * order — **what focus should land on after the tile under the ring is deleted.**
 *
 * **Why this function exists at all, stated precisely, because the record it corrects was wrong.**
 * `DefaultFocusAuthority.removeEntryAndFallback` does fall focus onto the nearest surviving sibling when a
 * focused entry is removed. That branch does not catch a delete here. [FileBrowserController.load] calls `FocusAuthority.clearFocus()` *before*
 * [FileBrowserFocusTracker.update] retires the departed key — deliberately, and for a good reason of its
 * own (that ordering is what stops the sibling-fallback CASCADE on a directory change; see
 * [FileBrowserFocusTracker.retire]'s "safe by construction"). The consequence is that by the time the
 * deleted tile is unregistered, `_state.value.focused` is already null, so the fallback branch cannot
 * match and never runs. Focus was therefore landed by `load`'s closing `requestFocus(keys.firstOrNull())`
 * — i.e. **index 0**, the top of the listing.
 *
 * That is not BUG#01: focus is non-null and the D-pad is alive. It is the navigation tax
 * [FileBrowserController.focusByDirectory] was added to remove, reappearing on a different path — delete
 * the 47th ROM in a directory and the ring jumps to the first one, every time.
 *
 * **Forward before backward.** The key that slid up into the deleted tile's place is the one the player's
 * eye is already on; falling backward first would move the ring away from the gap they just made. Ties
 * cannot occur — the walk checks `index + step` before `index - step` at every step.
 *
 * **Order, not geometry, and deliberately so.** The authority's own fallback scores by synthetic distance,
 * which for a grid resolves "nearest" as the tile above or below as readily as the one after. Render order
 * is what the player experienced, and it is the same order [FileBrowserController.load] already validates
 * its focus memory against.
 *
 * @param previousKeys the listing's keys in render order **as they were before the reload**.
 * @param departed the key that held focus then, or null if nothing did.
 * @param survivingKeys the reloaded listing's keys.
 * @return null when nothing is departed, when [departed] was not in [previousKeys] at all (focus was
 *   somewhere else entirely), or when the whole listing is gone — the caller then falls back to index 0,
 *   which for an emptied directory is the only honest answer left.
 */
internal fun fileBrowserSurvivingNeighbour(
    previousKeys: List<FocusKey>,
    departed: FocusKey?,
    survivingKeys: List<FocusKey>,
): FocusKey? {
    if (departed == null || survivingKeys.isEmpty()) return null
    val index = previousKeys.indexOf(departed)
    if (index < 0) return null
    if (departed in survivingKeys) return departed
    val surviving = survivingKeys.toHashSet()
    for (step in 1..previousKeys.size) {
        previousKeys.getOrNull(index + step)?.let { if (it in surviving) return it }
        previousKeys.getOrNull(index - step)?.let { if (it in surviving) return it }
    }
    return null
}
