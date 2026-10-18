package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPaceMs
import java.io.File

/**
 * F-08's decision layer —
 *
 * **The engine is not here and is not re-decided here.** `core-files`' `FileSearchPolicy` owns the
 * contract (names only / current volume / nothing leaves the device), its five filter chips
 * ([FileSearchFilter]) and the player-facing sentence
 * ([com.gruning.mimir.rewrite.core.files.FILE_SEARCH_SCOPE_COPY]); `FileBrowserStore.search` owns the
 * volume-scoped cold `Flow`. This file owns only what a *surface* has to decide: which key holds focus,
 * what one press does, when a query is worth launching, and what the player is told while nothing has
 * arrived yet. Every function here is pure and JVM-assertable — `feature-files` has no Compose harness,
 * so anything that can only be proven by rendering is deliberately not decided in a composable.
 *
 * **A separate [FocusScope], not a second set of keys in the browse scope.** Two reasons, both
 * mechanical:
 *  1. `DefaultFocusAuthority.entries` is a `LinkedHashMap<FocusKey, Entry>` — **keyed by [FocusKey]
 *     globally, with the scope as a FIELD of the entry.** A search result for a file that is also a tile
 *     in the directory behind the search screen would therefore *overwrite* that tile's entry, silently
 *     moving it into the search scope; when search closed, the browse listing would be one tile short of
 *     a focus graph. [FileBrowserSearchFocus] puts every key in a namespace
 *     [FileBrowserFocus.tileKey] can never produce, exactly as [FileBrowserFocus.commitKey] already does
 *     for the pinned commit row.
 *  2. `DefaultFocusAuthority.popScope` restores `focusMemory[activeScope]`, so leaving search returns the
 *     player to the tile they were standing on with **no bookkeeping in this module at all** — returning to the same listing with its place intact is a property
 *     of the scope stack, not a feature this file implements.
 */
internal val FileBrowserSearchScope: FocusScope = FocusScope("files/search")

/**
 * Focus identity for the search screen. Same discipline as [FileBrowserFocus]: **identity-qualified,
 * never index-qualified**, so a result list that re-orders while the walk is still running can never
 * re-target focus onto whatever now sits at that position.
 *
 * Every key is namespaced under `files/search/`, a segment [FileBrowserFocus.tileKey]
 * (`files/tile<absolute path>`) cannot produce for any path — see this file's KDoc for why that
 * separation is load-bearing rather than tidy.
 */
internal object FileBrowserSearchFocus {

    /** The query field. Activating it opens the on-screen keyboard; it is never typed into directly. */
    val QueryKey: FocusKey = FocusKey("files/search/query")

    /** One filter chip. Keyed by the enum NAME, not by [FileSearchFilter.label], so re-wording a chip
     * cannot invalidate a focus key mid-session. */
    fun filterKey(filter: FileSearchFilter): FocusKey = FocusKey("files/search/filter/${filter.name}")

    /** One result row, keyed by the found file's absolute path. */
    fun resultKey(path: String): FocusKey = FocusKey("files/search/result$path")

    /** The absolute path behind [key], or null when [key] is not a result row. */
    fun resultPath(key: FocusKey?): String? =
        key?.value?.takeIf { it.startsWith(RESULT_PREFIX) }?.removePrefix(RESULT_PREFIX)?.takeIf(String::isNotBlank)

    private const val RESULT_PREFIX = "files/search/result"
}

/**
 * How long a settled query waits before the walk is launched.
 *
 * **Derived from this tree's own input pace, not picked.** `core-input`'s [MimirDirectionalPaceMs] (60 ms)
 * is the anti-burst floor on directional input — the fastest this app will accept two D-pad moves. A
 * character typed on `MimirKeyboard` costs at minimum one accepted move plus a CONFIRM, and a key that is
 * not the immediate neighbour costs two moves plus a CONFIRM. **Three pace windows is therefore the floor
 * of "the player is still typing"**: below it the debounce could never coalesce two keystrokes and would
 * be decoration; at it, every character typed at controller speed folds into one walk and the walk starts
 * as soon as the player pauses.
 *
 * Expressed as a multiple rather than as `180L` so it tracks the pace constant if that ever moves. It
 * happens to land on the same magnitude as `MimirNativeShelfRefreshDebounceMs` (also 180 ms), so this
 * introduces no new timing magnitude to the codebase.
 *
 * **This is the half of the search cancellation contract that matters most.** `FileBrowserStore.search` returns a COLD
 * flow, so an un-launched query costs nothing at all — but a launched one walks the volume. Without a
 * debounce, `Sonic` is five volume walks; with it, one.
 */
internal const val FileBrowserSearchDebounceMs: Long = 3 * MimirDirectionalPaceMs

/**
 * Whether [query] under [filter] is worth walking the volume for.
 *
 * **A blank query is only a search when a chip narrows it.** `fileSearchNameMatches` treats a blank query
 * as matching everything (deliberately — `FileSearchPolicy`'s own KDoc blesses *"an empty search box with
 * a filter chip selected still browses that kind"*), so blank + [FileSearchFilter.ALL] means *stream the
 * entire volume*: every file on the card, through a filter that rejects nothing. That is not a search the
 * player expressed, it is the absence of one, and it is precisely the walk this gate exists to refuse.
 *
 * Blank + any other chip DOES launch: "show me every archive on this card" is a real, useful request and
 * the chip is the query.
 *
 * **A minimum query LENGTH is deliberately not used as the gate.** Two-character ROM names are real
 * (`R4`, `FF`), and refusing them would be an invented threshold refusing a legitimate search — the
 * debounce already removes the per-keystroke cost that a length floor is usually reaching for.
 */
internal fun fileBrowserSearchLaunches(query: String, filter: FileSearchFilter): Boolean =
    query.isNotBlank() || filter != FileSearchFilter.ALL

/** What the search screen is currently doing. Drives the count line and the empty state — see
 * [fileBrowserSearchStatusLabel], which is the only thing that reads it. */
internal enum class FileBrowserSearchPhase {
    /** No query has been expressed ([fileBrowserSearchLaunches] is false). Nothing is walking. */
    IDLE,

    /** A walk is running. Results already emitted are shown; more may arrive. */
    RUNNING,

    /** The walk finished. What is on screen is everything there is. */
    SETTLED,
}

/**
 * The line under the results — the design's own `searchCountLabel`
 * (`Mimir Files.dc.html:784`: `filtered.length + ' matches on SD card'`), with two honesty corrections
 * the mock did not have to make.
 *
 * 1. **The volume is named, not assumed.** The mock hardcodes `'SD card'` because its fake filesystem has
 *    exactly one. [volumeLabel] comes from the caller's real crumb labels; when nothing names the volume
 *    the phrase degrades to *"on this volume"* rather than claiming a card that may be internal storage.
 * 2. **A running walk says so.** The mock filters a list it already holds, so its count is final the
 *    instant it is drawn. A real walk emits over time, and a settled-looking "3 matches" that becomes 40
 *    a second later is a lie about completeness — [FileBrowserSearchPhase.RUNNING] says *searching* while
 *    it is still true.
 *
 * [FileBrowserSearchPhase.IDLE] returns null: there is no count to state before a query exists, and the
 * caller draws the scope card's own prompt instead of a zero.
 */
internal fun fileBrowserSearchStatusLabel(
    res: Resources,
    count: Int,
    phase: FileBrowserSearchPhase,
    volumeLabel: String?,
): String? {
    // Each sentence is one resource with the volume's name (or the words for "this volume") in its slot: nothing is joined here.
    val volume = volumeLabel?.trim()?.takeIf(String::isNotBlank)
    return when (phase) {
        FileBrowserSearchPhase.IDLE -> null
        FileBrowserSearchPhase.RUNNING ->
            if (volume != null) {
                res.getString(R.string.files_search_status_running, volume, count)
            } else {
                res.getString(R.string.files_search_status_running_here, count)
            }
        FileBrowserSearchPhase.SETTLED ->
            if (volume != null) {
                res.getQuantityString(R.plurals.files_search_status_matches, count, count, volume)
            } else {
                res.getQuantityString(R.plurals.files_search_status_matches_here, count, count)
            }
    }
}

/**
 * Empty search results resolve to a distinct settled state rather than an endless spinner.
 *
 * Three distinct nothings, three distinct sentences, because they want three different reactions from the
 * player: type something, wait, or change the query. A single "No results" would be wrong for two of
 * them, and a spinner would be wrong for all three (the walk has a real end; it is [SETTLED] that says
 * so).
 *
 * Returns null when there are results to draw instead.
 */
internal fun fileBrowserSearchEmptyMessage(res: Resources, count: Int, phase: FileBrowserSearchPhase): String? = when {
    count > 0 -> null
    phase == FileBrowserSearchPhase.IDLE -> res.getString(R.string.files_search_empty_idle)
    phase == FileBrowserSearchPhase.RUNNING -> res.getString(R.string.files_search_empty_running)
    else -> res.getString(R.string.files_search_empty_settled)
}

// ─────────────────────────────────────────────────────────────────────── Navigation ──

/**
 * The search screen's focus rows, in render order: the control row (query + one key per chip) first, then
 * one row per result.
 *
 * **The results are ONE COLUMN, not the design's five-wide tile grid — a disclosed, reversible visual
 * deviation.** The design renders search hits through the same `tile()` helper the browse folio uses
 * (`Mimir Files.dc.html:721`) but *replaces their meta line with the containing folder*
 * (`meta: this.friendly(x.p)`) — because where a hit lives is the whole point of a file-manager search.
 * At the design's own 833 dp viewport a five-wide file cell is ~94 dp across, which shows about six
 * characters of `SD card › Roms › gba`; the mock's fake paths are short enough to fit and a real one is
 * not. This tree's own controller-driven search precedent — `feature-library`'s `LibrarySearchOverlay`,
 * which the brief names as the model to reuse — renders its results as full-width rows for exactly that
 * reason. The design's *intent* (show the player where it is) is honoured; its *grid* is not. One row's
 * `Modifier` reverts this if the layout changes to a grid.
 */
internal fun fileBrowserSearchRows(results: List<FileItem>): List<List<FocusKey>> =
    listOf(fileBrowserSearchControlRow()) + results.map { listOf(FileBrowserSearchFocus.resultKey(it.path)) }

/** The control row: the query field, then one key per [FileSearchFilter], in the design's own chip order
 * (`['All','Games','Archives','BIOS','Saves']` — which is [FileSearchFilter]'s declaration order, so
 * `entries` IS the design's order and no second list can drift from it). */
internal fun fileBrowserSearchControlRow(): List<FocusKey> =
    listOf(FileBrowserSearchFocus.QueryKey) + FileSearchFilter.entries.map(FileBrowserSearchFocus::filterKey)

/** Every key the search screen registers, in render/tab order — [fileBrowserSearchRows] flattened. */
internal fun fileBrowserSearchKeys(results: List<FileItem>): List<FocusKey> =
    fileBrowserSearchRows(results).flatten()

/**
 * Where one directional press lands, or null when it lands nowhere.
 *
 * **Explicit row arithmetic, NOT [com.gruning.mimir.rewrite.core.input.FocusAuthority.moveFocus]'s
 * geometric scoring — and that is a decision with a named in-tree precedent.**
 * `MimirKeyboardFocusBinding.rowNeighbor` (`core-input/MimirKeyboardFocus.kt:103`) does the same thing for
 * the same reason: geometry cannot express "the next control in this row" for a row of unequal-width
 * controls. The search control row is a ~35 %-width text field beside five pill chips; scoring NAV_UP
 * from a full-width result row against six candidates of six different widths resolves by whichever chip
 * happens to sit nearest the result row's synthetic centre — an answer that is stable but arbitrary, and
 * that changes the day a chip's width changes. It also avoids the mixed synthetic/pixel regime
 * [FileBrowserFocusTracker] warns about entirely, because nothing here consults bounds at all.
 *
 * The rules, all four deliberate:
 *  - **LEFT/RIGHT move within the control row and CLAMP at its ends.** No wrap: the keyboard wraps
 *    because a keyboard row is a ring of equivalent keys, whereas RIGHT off the last chip landing back on
 *    the query field would teleport the player across the screen.
 *  - **LEFT/RIGHT on a result are consumed** (return null → the caller consumes). A full-width row has no
 *    horizontal neighbour — the same rule [fileBrowserCommand] already applies to the pinned commit row,
 *    and for the same reason.
 *  - **DOWN from anywhere in the control row lands on the FIRST result**, not on a column-matched one:
 *    the results are one column, so there is only one answer.
 *  - **UP from the first result lands on the query field**, deterministically, rather than on whichever
 *    chip was last touched. The query is the primary control and the one the player is most likely to
 *    want; the chips are one RIGHT away from it. Remembering the last control-row key instead would make
 *    the same press do two different things on two identical screens.
 */
internal fun fileBrowserSearchNavigate(
    focused: FocusKey?,
    action: ControllerAction,
    results: List<FileItem>,
): FocusKey? {
    if (focused == null) return null
    val controls = fileBrowserSearchControlRow()
    val resultKeys = results.map { FileBrowserSearchFocus.resultKey(it.path) }
    val controlIndex = controls.indexOf(focused)
    val resultIndex = resultKeys.indexOf(focused)

    return when {
        controlIndex >= 0 -> when (action) {
            ControllerAction.NAV_LEFT -> controls.getOrNull(controlIndex - 1)
            ControllerAction.NAV_RIGHT -> controls.getOrNull(controlIndex + 1)
            ControllerAction.NAV_DOWN -> resultKeys.firstOrNull()
            else -> null
        }
        resultIndex >= 0 -> when (action) {
            ControllerAction.NAV_UP ->
                if (resultIndex == 0) FileBrowserSearchFocus.QueryKey else resultKeys[resultIndex - 1]
            ControllerAction.NAV_DOWN -> resultKeys.getOrNull(resultIndex + 1)
            else -> null
        }
        else -> null
    }
}

/**
 * Where one PAGE_LEFT / PAGE_RIGHT press lands in the result list, or null when it moves nothing.
 *
 * Uniform rows, so unlike [fileBrowserPageTarget] this needs no per-row pitch list — the stride is
 * [fileBrowserSearchPageStride], one screenful of them. A press from the control row is not a page at all
 * (there is one control row); it falls to the first result, matching NAV_DOWN, so a shoulder press at the
 * top of the screen still enters the list rather than doing nothing.
 *
 * Null at a clamped edge rather than the current key, for [fileBrowserPageTarget]'s stated reason: a held
 * shoulder button at the end of a list must not re-emit the same `FocusState` per repeat.
 */
internal fun fileBrowserSearchPageTarget(
    focused: FocusKey?,
    forward: Boolean,
    results: List<FileItem>,
    grid: FileBrowserGrid,
): FocusKey? {
    if (focused == null || results.isEmpty()) return null
    val resultKeys = results.map { FileBrowserSearchFocus.resultKey(it.path) }
    val index = resultKeys.indexOf(focused)
    if (index < 0) {
        // Focus is on the control row. Forward enters the list; backward has nowhere above to go.
        return if (forward && focused in fileBrowserSearchControlRow()) resultKeys.first() else null
    }
    val stride = fileBrowserSearchPageStride(grid)
    val target = (index + if (forward) stride else -stride).coerceIn(0, resultKeys.lastIndex)
    return if (target == index) null else resultKeys[target]
}

/**
 * One result row's pitch: its own PAINTED height plus the gap to the next row.
 *
 * ```
 * result row pitch = 62   FileBrowserSearchResultRowHeightDp — the row's real two-line metrics
 *                  +  9   the results LazyColumn's `verticalArrangement` spacing
 *                  = 71 dp
 * ```
 *
 * **F-S2: this was frozen at 55 (46 + 9), assuming a single-line row.** The row renders two lines and
 * measures 62dp on hardware, so every consumer below — the shoulder stride, the reveal-scroll's item
 * estimate, and the result-batching cadence — was reasoning over rows painted 16dp taller than the
 * lattice believed. See [FileBrowserSearchResultRowHeightDp] for the measurement and its derivation.
 */
internal val FileBrowserSearchResultRowPitchDp: Float =
    FileBrowserSearchResultRowHeightDp + FileBrowserRowGapDp

/**
 * How many result rows one shoulder press travels: one screenful, floored, never less than one.
 *
 * **F-S2: takes the SOLVED grid.** It used to divide one frozen constant by another — a viewport frozen
 * at the folio's frozen 350dp box, and a pitch frozen at a single-line row. RC-12's own lesson applies
 * verbatim here ("a solver that only touches columns is worse than the defect it fixes"): all four
 * consumers of these two numbers move together, or the ones left behind disagree with the ones that
 * moved.
 */
internal fun fileBrowserSearchPageStride(grid: FileBrowserGrid): Int =
    (fileBrowserSearchViewportDp(grid) / FileBrowserSearchResultRowPitchDp).toInt().coerceAtLeast(1)

// ─────────────────────────────────────────────────────────────────────── Dispatch ──

/**
 * What the search screen should do in response to one controller action — the search half of
 * [fileBrowserCommand], with the same total-function discipline: **every action produces exactly one
 * command, there is no "unhandled" case.** That is what lets [FileBrowserController.dispatch] keep
 * answering `true` while search is open, which is what keeps this surface a hard modal (see
 * `dispatchFolderBrowser`: a modal that returns false leaks the press to the destination underneath).
 */
internal sealed interface FileBrowserSearchCommand {
    /** Move focus to [key]. */
    data class Focus(val key: FocusKey) : FileBrowserSearchCommand

    /** Open the on-screen keyboard on the query field — the ONLY way a query is typed. */
    data object OpenKeyboard : FileBrowserSearchCommand

    /** Apply [filter]. Re-selecting the active chip is still this command; the caller idempotently
     * re-runs, which is the cheapest possible way to also make it a "retry". */
    data class SelectFilter(val filter: FileSearchFilter) : FileBrowserSearchCommand

    /** Leave search and reveal [path] in the browse listing — the design's own result behaviour
     * (`Mimir Files.dc.html:723`: `onTap: () => this.setState({ path: x.p, screen: 'browse' })`). */
    data class Reveal(val path: String) : FileBrowserSearchCommand

    /** Leave search for the listing the player came from. */
    data object Close : FileBrowserSearchCommand

    /** Swallowed on purpose — see this interface's KDoc. */
    data object Consumed : FileBrowserSearchCommand
}

/**
 * The search screen's entire input contract, as a pure function.
 *
 * `SECONDARY` (extract) is deliberately **[FileBrowserSearchCommand.Consumed] on a result, even an
 * archive.** Extraction routes through `FileBrowserStore`'s `runExtraction`, whose completion refreshes
 * `currentController` — i.e. the directory the player is standing IN, which during a search is not the
 * directory the archive lives in. Firing it from here would extract correctly and then refresh the wrong
 * listing, so the player would see nothing happen. Reveal first, then extract, is one extra press and is
 * honest; wiring extraction to a foreign directory requires deferred `app/` integration.
 */
internal fun fileBrowserSearchCommand(
    action: ControllerAction,
    focused: FocusKey?,
    results: List<FileItem>,
    grid: FileBrowserGrid,
): FileBrowserSearchCommand = when (action) {
    ControllerAction.NAV_UP, ControllerAction.NAV_DOWN, ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT ->
        fileBrowserSearchNavigate(focused, action, results)
            ?.let(FileBrowserSearchCommand::Focus)
            ?: FileBrowserSearchCommand.Consumed

    ControllerAction.PAGE_LEFT, ControllerAction.PAGE_RIGHT ->
        fileBrowserSearchPageTarget(
            focused = focused,
            forward = action == ControllerAction.PAGE_RIGHT,
            results = results,
            grid = grid,
        )
            ?.let(FileBrowserSearchCommand::Focus)
            ?: FileBrowserSearchCommand.Consumed

    ControllerAction.CONFIRM -> when {
        focused == FileBrowserSearchFocus.QueryKey -> FileBrowserSearchCommand.OpenKeyboard
        else -> {
            val filter = FileSearchFilter.entries.firstOrNull { FileBrowserSearchFocus.filterKey(it) == focused }
            val path = FileBrowserSearchFocus.resultPath(focused)
            when {
                filter != null -> FileBrowserSearchCommand.SelectFilter(filter)
                path != null -> FileBrowserSearchCommand.Reveal(path)
                else -> FileBrowserSearchCommand.Consumed
            }
        }
    }

    ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> FileBrowserSearchCommand.Close

    else -> FileBrowserSearchCommand.Consumed
}

/**
 * Search hits as renderable tiles, **in emission order and never re-sorted.**
 *
 * [fileBrowserPartition] is deliberately not reused: it sorts by name and splits directories out, and
 * both are wrong for a streaming result list. A walk emits as it finds, so a sort would re-order the
 * whole list on every batch — the row under the player's focus would change identity between the press
 * and the release, which is the same class of defect [FileBrowserFocus]'s "identity-qualified, never
 * index-qualified" rule exists to prevent, arriving through the data instead of through the key. Emission
 * order is also the more useful order: it is depth-first from the volume root, so hits from one folder
 * arrive together.
 *
 * Directories are kept. `LocalFileRepository.search` emits any name match including folders, and
 * `fileSearchMatches` pins that a directory survives [FileSearchFilter.ALL] — that is the engine's
 * contract, and the design mock excluding them (its `all` collector skips `e.d`) is the mock's own
 * simplification, not a rule. A folder called `Pokemon` is a legitimate answer to a search for `pokemon`.
 */
internal fun fileBrowserSearchTiles(
    items: List<FileItem>,
    // R6-P2-07: avoid repeated classification during incremental search. The search collector
    // publishes the WHOLE accumulated result list on every flush (`FileBrowserSearchSurface.kt`'s
    // `FileBrowserSearchCollector`: `found.toList()`), and this function used to re-run
    // `classifyPlatform` for every item on every flush -- total classification work grew
    // QUADRATICALLY with the number of batches, not linearly with the number of files (batch sizes
    // 1,2,3 cost 1+2+3=6 calls for 3 real files). Keyed by path, distinct from -- and never sharing an
    // instance with -- `FileBrowserPlatformClassifier`'s own bounded `ParentFolderHintCache`
    // (`app/FileBrowserPlatformClassifier.kt`), which memoizes a different question (a PARENT FOLDER's
    // platform hint) for a different caller (ordinary folder listing). A path is unique within one
    // result list (`LocalFileRepository.search` walks each real path at most once), so a hit here is
    // always the same tile a previous batch already built for that exact path -- safe to hand back
    // without calling `classifyPlatform` again. Callers that want the incremental behaviour hold one
    // cache across batches, but bounded to one WALK, not the whole open->close session (review
    // finding 2, 2026-09-15): `FileBrowserSearchSurface.kt`'s
    // `remember(controller, controller.searchQuery, controller.searchFilter)` -- see that call's own
    // KDoc for why the walk's query and filter, not just the controller, decide when this resets. A
    // caller that passes a fresh map every time -- the default, and every test in this file except the
    // one pinning this behaviour directly -- gets exactly the old per-call semantics, since every path
    // is a miss on an empty map.
    cache: MutableMap<String, FileBrowserTile> = mutableMapOf(),
    classifyPlatform: (FileItem) -> FileBrowserRecognition,
): List<FileBrowserTile> = items.map { item ->
    cache.getOrPut(item.path) {
        val machinery = fileBrowserIsMachineryFolder(item)
        val recognition = if (machinery) FileBrowserRecognition.None else classifyPlatform(item)
        FileBrowserTile(
            item = item,
            kind = when {
                item.isDirectory -> FileBrowserTileKind.DIRECTORY
                com.gruning.mimir.rewrite.core.files.LocalListingPolicy.isExtractableArchive(item) ->
                    FileBrowserTileKind.ARCHIVE
                else -> FileBrowserTileKind.FILE
            },
            machinery = machinery,
            platformIdValue = recognition.platformIdValue,
            gameLike = recognition.gameLike,
            bios = recognition.bios,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────── Reveal ──

/** The directory holding [path] — the listing a [FileBrowserSearchCommand.Reveal] must land in. */
internal fun fileBrowserSearchParentPath(path: String): String? =
    File(path).parent?.takeIf(String::isNotBlank)

/**
 * The back-stack a browser rooted at [rootPath] must hold to be standing in [directory], or null when
 * [directory] is not inside [rootPath] at all.
 *
 * The list is every ancestor from [rootPath] down to but NOT including [directory] — exactly what
 * [FileBrowserController]'s own `stack` holds, so one CANCEL from the revealed listing walks back up
 * naturally and the crumb trail is truthful. Revealing the root itself yields an empty stack, which is
 * what "at root" means.
 *
 * **Prefix matching is segment-aware, not `startsWith`.** `/storage/emulated/0` is not an ancestor of
 * `/storage/emulated/01`, and a raw prefix test says it is. Both sides have trailing slashes trimmed
 * first, because `StorageVolumeListing` mount points arrive in either form (the same normalisation
 * [fileBrowserBreadcrumbs] already applies, and for the same reason).
 *
 * Returning null rather than a best-effort stack is deliberate: a reveal that cannot be expressed inside
 * this browser's root must be refused, never approximated by dropping the player somewhere plausible.
 */
internal fun fileBrowserRevealStack(rootPath: String, directory: String): List<String>? {
    val root = rootPath.trimEnd('/')
    val target = directory.trimEnd('/')
    if (target == root) return emptyList()
    val relative = target.removePrefix("$root/").takeIf { it != target } ?: return null
    val stack = mutableListOf(rootPath)
    var accumulated = root
    val segments = relative.split('/').filter(String::isNotEmpty)
    // Every segment BUT the last: the last one IS the destination, not an ancestor of it.
    segments.dropLast(1).forEach { segment ->
        accumulated = "$accumulated/$segment"
        stack.add(accumulated)
    }
    return stack
}

// ─────────────────────────────────────────────────────────────────────── Result publishing ──

/**
 * Whether a partially-collected walk should publish what it has yet.
 *
 * **Why batching at all.** `FileBrowserStore.search` is a cold `Flow` that emits per matching file as it
 * walks. Writing each emission straight into Compose state is one recomposition of the whole result list
 * PER FILE FOUND — on a card where a broad query matches thousands, that is a recomposition storm on the
 * single path this tree is most performance-sensitive about. Batching is surface plumbing (how often the
 * screen redraws), not an engine cap: nothing here truncates, delays or discards a single result, so it
 * does not touch the "no ungrounded max-results constant" decision `FileSearchPolicy` states and defends.
 *
 * **Both terms are needed.** [FileBrowserSearchFlushItems] alone would leave a slow, sparse walk (a big
 * card, a narrow query) sitting on 23 found files indefinitely with nothing on screen.
 * [FileBrowserSearchFlushMs] alone would redraw on a fixed tick even when a burst of a thousand arrived
 * inside it. Together: a burst flushes on count, a trickle flushes on time.
 *
 * The caller always publishes once more when the flow completes, so the final state never depends on this.
 */
internal fun fileBrowserSearchShouldFlush(pending: Int, elapsedMs: Long, grid: FileBrowserGrid): Boolean =
    pending > 0 &&
        (pending >= fileBrowserSearchFlushItems(grid) || elapsedMs >= FileBrowserSearchFlushMs)

/** A full screenful of result rows, so a burst never draws a list shorter than the viewport twice.
 * Derived from [fileBrowserSearchPageStride] rather than picked, so it tracks the row pitch — and, since
 * F-S2, the SOLVED viewport too: this was a class-init `val` off two frozen constants, so a device with a
 * taller or shorter box batched at the Odin's cadence. */
internal fun fileBrowserSearchFlushItems(grid: FileBrowserGrid): Int = fileBrowserSearchPageStride(grid)

/** The redraw floor for a sparse walk. One debounce window ([FileBrowserSearchDebounceMs]) — the same
 * "the player has noticed a pause" interval, reused rather than a second invented cadence. */
internal const val FileBrowserSearchFlushMs: Long = FileBrowserSearchDebounceMs

// ─────────────────────────────────────────────────────────────────────── Virtual bounds ──

/**
 * Synthetic geometry for the search screen's focus slots.
 *
 * **These bounds are registration, not navigation.** Movement is decided entirely by
 * [fileBrowserSearchNavigate]'s row arithmetic and never consults them — but
 * `DefaultFocusAuthority.requestFocus` returns at `entries[key] ?: return` for an unregistered key, so
 * every control and every result must still BE in the graph, including the results scrolled out of
 * composition. They are laid out honestly anyway (control row first, results in one column below it) so
 * that if anything ever does fall back to geometry — a future press this module does not handle, a
 * scope-entry landing — it gets a sane answer rather than six overlapping rectangles.
 *
 * Magnitudes are arbitrary and only ratios matter, matching [fileBrowserTomeVirtualBounds]'s own posture.
 */
private const val FileBrowserSearchGridWidth = 20_000f
private const val FileBrowserSearchControlRowPitch = 3_000f
private const val FileBrowserSearchResultRowPitch = 2_400f

/** The query field: the leading ~35 % of the control row, matching its `weight` on screen. */
internal fun fileBrowserSearchQueryBounds(): FocusBounds = FocusBounds(
    left = 0f,
    top = 0f,
    right = FileBrowserSearchGridWidth * 0.35f,
    bottom = FileBrowserSearchControlRowPitch,
)

/** Chip [index] of [count]: the trailing 65 % of the control row, split evenly. */
internal fun fileBrowserSearchFilterBounds(index: Int, count: Int = FileSearchFilter.entries.size): FocusBounds {
    val start = FileBrowserSearchGridWidth * 0.35f
    val cell = (FileBrowserSearchGridWidth - start) / count.coerceAtLeast(1)
    val left = start + index * cell
    return FocusBounds(left = left, top = 0f, right = left + cell, bottom = FileBrowserSearchControlRowPitch)
}

/** Result [index]: full width, strictly below the control row, one row per result. */
internal fun fileBrowserSearchResultBounds(index: Int): FocusBounds {
    val top = FileBrowserSearchControlRowPitch + index * FileBrowserSearchResultRowPitch
    return FocusBounds(
        left = 0f,
        top = top,
        right = FileBrowserSearchGridWidth,
        bottom = top + FileBrowserSearchResultRowPitch,
    )
}
