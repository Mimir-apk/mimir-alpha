package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope

/**
 * F-11's decision layer for the **Selection screen** (adoption spec §6, `Mimir Files.dc.html:158-168`).
 *
 * **The engines take lists today and there is no way to build one.** `FileBrowserStore.deleteInManager`
 * and `moveInManager` both accept a `List<String>`, `ManagedFileOperations` handles directories
 * recursively (`ManagedFileOperations.kt:419`, `513`), and every one of those paths is pinned by
 * `ManagedFileOperationsTest` — but nothing in `feature-files` can assemble a multi-selection, so the
 * list is always the one focused tile at best and, today, is never built at all. This file is the
 * missing assembler, as a pure reducer.
 *
 * **§3c is enforced at the door, not at the buttons.** [fileBrowserSelectionEnter] refuses in
 * [FileBrowserMode.PICK] and [FileBrowserSelection.toggled] refuses on an inactive selection, so a PICK
 * session cannot hold a non-empty selection at all — there is no state in which its Delete button could
 * be reached and then declined. That is the difference between *incapable* and *restricted*, and it is
 * the reason the mode split exists. Note that §3c forbids even the non-destructive bulk actions in PICK
 * (PICK remains limited to browse, extract and commit a folder), so the gate is on the
 * selection itself rather than on the individual actions.
 *
 * **What this file deliberately does not decide:** whether a selection survives a navigation. It can,
 * because every path here is absolute, and `deleteInManager`/`moveInManager` accept paths from anywhere.
 * The hazard — a player selects three files, walks into another folder, and presses Delete on a pane
 * whose subjects are no longer on screen — is real, and the design has it too (its `selected` holds bare
 * names across navigation). Clearing on navigate belongs in `FileBrowserController.load`, and remains an integration requirement rather than being half-solved here.
 */
internal data class FileBrowserSelection(
    /** Whether the player has entered selection mode. False is the browse folio's normal state. */
    val active: Boolean,
    /**
     * The chosen absolute paths, in the order they were picked.
     *
     * Insertion-ordered (`setOf` / `plus` yield a `LinkedHashSet`) so `deleteInManager(paths)` acts in a
     * stable, explainable order rather than a hash order that could differ between two runs on the same
     * selection.
     */
    val paths: Set<String>,
) {

    /**
     * The design's `showSelectionPane` (`Mimir Files.dc.html:764`): mode entered **and** something in it.
     * An empty selection shows no pane — there is nothing to act on yet.
     */
    val showsPane: Boolean get() = active && paths.isNotEmpty()

    /**
     * The design's `showInspector: !!f && !st.selectMode` (line 765). The inspector is about **one**
     * focused file; while a selection is being built it would be describing a subject the player is not
     * acting on. Suppressed on `active`, not on `showsPane`, so entering selection mode swaps the panel
     * immediately rather than after the first tick.
     */
    val suppressesInspector: Boolean get() = active

    /** Add or remove [path]. **A no-op on an inactive selection** — see this file's KDoc on §3c. */
    fun toggled(path: String): FileBrowserSelection = when {
        !active -> this
        path in paths -> copy(paths = paths - path)
        else -> copy(paths = paths + path)
    }

    /**
     * The design's Clear (line 769): leaves selection mode **and** empties it, in one press.
     *
     * Not two operations. `onClearSelection` sets `selectMode: false, selected: []` together, and an
     * "empty but still armed" state would leave the player in a mode with no pane and no way out of it
     * except the header toggle they may not have found.
     */
    fun cleared(): FileBrowserSelection = Inactive

    companion object {
        /** The browse folio's normal state: not selecting, nothing selected. */
        val Inactive: FileBrowserSelection = FileBrowserSelection(active = false, paths = emptySet())
    }
}

/**
 * Enter selection mode — **[FileBrowserMode.MANAGE] only**.
 *
 * Returns [FileBrowserSelection.Inactive] in PICK, which is the same value the caller already held, so a
 * PICK surface that wires this up regardless still cannot arm anything. Refusing here rather than at the
 * Delete button is the whole point: there is no reachable state in PICK where a destructive action has
 * to be declined, because the selection that would feed it cannot exist.
 */
internal fun fileBrowserSelectionEnter(mode: FileBrowserMode): FileBrowserSelection =
    if (mode == FileBrowserMode.MANAGE) {
        FileBrowserSelection(active = true, paths = emptySet())
    } else {
        FileBrowserSelection.Inactive
    }

/** The design's four selection-pane buttons (`Mimir Files.dc.html:163-166`), in its own order. */
internal enum class FileBrowserSelectionAction(val label: String) {
    /** `FileBrowserStore.moveInManager(paths, destination)` — needs a destination, so needs the Move
     * screen, which is unbuilt. Withheld unless the caller states that a destination can be chosen. */
    MOVE("Move…"),

    /** `FileBrowserStore.extractTo(archivePath, destination)`, once per extractable path. There is no
     * bulk-extract entry point; the loop belongs to the surface. */
    EXTRACT("Extract"),

    /** `FileBrowserStore.deleteInManager(paths)` — one call, the whole list, landing in the queue with
     * Undo held. */
    DELETE("Delete"),

    /** [FileBrowserSelection.cleared] — local state only, no engine. */
    CLEAR("Clear"),
}

/**
 * Which of the four the pane may draw for this selection.
 *
 * Every omission is this tree's standing refusal to draw a control that cannot succeed — the same rule
 * behind `FileBrowserController.commitAllowed` and `MimirPlatformShelfmark`'s "no plate rather than a
 * meaningless one":
 *
 *  - **EXTRACT** only when at least one selected tile is an archive. On a selection of three `.gba`
 *    files an Extract button has no possible outcome but a failure.
 *  - **MOVE** only when [moveDestinationAvailable]. The engine exists; the folder-picker screen that
 *    supplies its second argument does not, and `moveInManager(paths, destination)` cannot be called
 *    without one. Defaulted to `false` so a caller that has not built the picker gets the honest answer
 *    without having to know to ask for it.
 *  - **DELETE** whenever the selection is non-empty. `deleteInManager` needs nothing else, and the queue
 *    holds Undo, so this is complete as soon as a surface calls it.
 *  - **CLEAR** whenever the pane is drawn at all — it is the way out.
 *
 * @param tiles the rows the selection was built from, used only to answer "is any of these extractable".
 *   A path with no matching tile contributes nothing rather than being assumed extractable.
 */
internal fun fileBrowserSelectionActions(
    selection: FileBrowserSelection,
    tiles: List<FileBrowserTile>,
    moveDestinationAvailable: Boolean = false,
): List<FileBrowserSelectionAction> {
    if (!selection.showsPane) return emptyList()
    val selectedTiles = tiles.filter { it.path in selection.paths }
    return buildList {
        if (moveDestinationAvailable) add(FileBrowserSelectionAction.MOVE)
        if (selectedTiles.any { it.extractable }) add(FileBrowserSelectionAction.EXTRACT)
        add(FileBrowserSelectionAction.DELETE)
        add(FileBrowserSelectionAction.CLEAR)
    }
}

/**
 * The pane's heading — the design's `selectionLabel` (line 766): *"3 files · 45.2 MB"*.
 *
 * **The size clause is dropped whenever the selection holds a directory**, rather than under-reporting.
 * `FileItem.size` is a `File.length()` and answers 0 (or a directory-entry size) for a directory, so
 * summing a selection that contains a folder would print a confidently wrong number for the amount of
 * the player's card they are about to free. `FileItem.displaySize` already refuses the same question the
 * same way — it answers a child count for a directory and never a byte size. Counting the rows is still
 * honest, so the count always shows.
 *
 * Bytes are formatted with [FileItem.formatSize], the tree's own formatter, rather than the design's
 * hardcoded MB: a 4 GB disc image reported as "4096.0 MB" is worse than the design's own intent.
 */
internal fun fileBrowserSelectionLabel(
    selection: FileBrowserSelection,
    tiles: List<FileBrowserTile>,
): String {
    val count = selection.paths.size
    val countLabel = "$count ${if (count == 1) "file" else "files"}"
    val selectedTiles = tiles.filter { it.path in selection.paths }
    // Unknown, not zero: a path with no matching tile is a row this surface cannot measure either.
    if (selectedTiles.size != count) return countLabel
    if (selectedTiles.any { it.kind == FileBrowserTileKind.DIRECTORY }) return countLabel
    val bytes = selectedTiles.sumOf { it.item.size }
    return "$countLabel · ${FileItem.formatSize(bytes)}"
}

/**
 * Focus identity for the selection pane's buttons.
 *
 * Its own [FocusScope] for the same mechanical reason [FileBrowserSearchScope] and
 * [FileBrowserQueueFocus.Scope] have theirs: `DefaultFocusAuthority.entries` is keyed by [FocusKey]
 * globally, so a pane key that collided with a tile key would evict that tile from the folio's focus
 * graph. Every key is namespaced under `files/selection/`, which [FileBrowserFocus.tileKey] cannot
 * produce.
 *
 * **The tiles themselves need no new keys.** A tile is ticked by acting on the tile that already holds
 * focus, so selection adds four keys to the graph, not one per row — which also means entering and
 * leaving selection mode never rebuilds the folio's own focus registrations.
 */
internal object FileBrowserSelectionFocus {

    val Scope: FocusScope = FocusScope("files/selection")

    fun actionKey(action: FileBrowserSelectionAction): FocusKey =
        FocusKey("$ACTION_PREFIX${action.name}")

    /** Which action [key] presses, or null when it is not a selection-pane button. */
    fun actionOf(key: FocusKey?): FileBrowserSelectionAction? {
        val name = key?.value?.takeIf { it.startsWith(ACTION_PREFIX) }?.removePrefix(ACTION_PREFIX)
            ?: return null
        return FileBrowserSelectionAction.entries.firstOrNull { it.name == name }
    }

    private const val ACTION_PREFIX = "files/selection/"
}

/** Every focusable key the pane holds, in render order. Empty whenever the pane is not drawn. */
internal fun fileBrowserSelectionKeys(actions: List<FileBrowserSelectionAction>): List<FocusKey> =
    actions.map(FileBrowserSelectionFocus::actionKey)
