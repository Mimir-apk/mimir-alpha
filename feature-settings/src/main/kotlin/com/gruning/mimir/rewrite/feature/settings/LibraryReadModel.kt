package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import com.gruning.mimir.rewrite.core.model.library.sourceRootNeedsAllFilesAccess

/**
 * The REAL library read-model the Settings Library panel binds to — the summary counts + the user's
 * source folders, collected by the app from the single
 * [com.gruning.mimir.rewrite.core.model.library.LibraryRepository]
 * (`observeSummary` / `observeSources`). Passed in from the host so `:feature-settings` depends only on
 * the pure `:core-model` contract, never on `:feature-library` or `:core-storage` (no module cycle).
 *
 * When this is provided the Library panel shows live counts + friendly source paths; before the first
 * emission the panel shows an honest loading state. Standalone previews pass explicit fixtures.
 *
 * @property summary total dedup-collapsed game count + count of platforms that have games.
 * @property sources the user's source folders (friendly paths + availability). Empty = no folders added.
 * @property hiddenGames the user-hidden groups (recoverable via a Restore row). Empty = nothing hidden.
 * @property platforms registered display labels for release-visible hidden-game shelfmarks.
 * @property allFilesAccess the "All files access" grant, read by the app off-composition and re-read on every
 *   foreground return; the Library pane's storage row says "Allowed for all files" while it is held.
 * @property appPackage Mimir's own package, the one app folder on shared storage that needs no grant; null exempts nothing.
 */
data class LibraryReadModel(
    val summary: LibrarySummary,
    val sources: List<SourceRow>,
    val hiddenGames: List<GameRow> = emptyList(),
    val platforms: List<PlatformRow> = emptyList(),
    val allFilesAccess: Boolean = false,
    val appPackage: String? = null,
) {
    private fun needsFileAccess(source: SourceRow): Boolean = sourceRootNeedsAllFilesAccess(source.rootUri, appPackage)

    /**
     * Whether Mimir can read [source] right now. Without "All files access" a shared-storage folder cannot be listed
     * (it opens and lists empty, which a rescan would take for a deletion), so it never reads "Available" then, whatever
     * its last scan said. A SAF folder is judged by its own grant and keeps its stored answer.
     */
    fun isReadable(source: SourceRow): Boolean = source.available && (allFilesAccess || !needsFileAccess(source))

    /** At least one folder is unreadable only because "All files access" is off: the Library pane says so and offers the route. */
    val foldersLockedByFileAccess: Boolean
        get() = !allFilesAccess && sources.any { needsFileAccess(it) }

    /** Every folder is locked that way, so a rescan has nothing it could read: RESCAN asks for the access instead of running. */
    val everyFolderLockedByFileAccess: Boolean
        get() = !allFilesAccess && sources.isNotEmpty() && sources.all { needsFileAccess(it) }

    /**
     * What the pane says about the lock: null when nothing is locked, the all-folders sentence when every folder is, and the
     * "some" sentence when only part of the library can no longer be read.
     */
    val fileAccessNote: String?
        get() = when {
            !foldersLockedByFileAccess -> null
            everyFolderLockedByFileAccess -> LibraryFilesAccessLostCopy
            else -> LibraryFilesAccessLostSomeCopy
        }
}

/**
 * Where the Library pane's focus lands when the "Remove library folder?" confirmation closes (audit 2026-10-15, F6: after Cancel it landed on ADD LIBRARY FOLDER, not on the
 * REMOVE row it had left).
 *
 * [onCancel] is the REMOVE row the player pressed: the folder is still listed, so Cancel (and B) put the ring back on it. [onRemoved] is the neighbour that takes its place once the
 * folder is gone: the folder after it, else the one before, else ADD LIBRARY FOLDER. Both are Library row ids, landed through the authority.
 */
data class LibrarySourceRemovalReturn(val onCancel: String, val onRemoved: String)

/** The focus a removal of [source] returns to, over the pane's listed [sources] (the primary folder has no REMOVE row and is not a neighbour). */
fun librarySourceRemovalReturn(sources: List<SourceRow>, source: SourceRow): LibrarySourceRemovalReturn {
    val additional = sources.filterNot(SourceRow::isPrimary)
    val index = additional.indexOfFirst { it.rootUri == source.rootUri }
    val neighbour = when {
        index >= 0 && index + 1 < additional.size -> additional[index + 1]
        index > 0 -> additional[index - 1]
        else -> null
    }
    return LibrarySourceRemovalReturn(
        onCancel = librarySourceRemovalRowId(source.rootUri),
        onRemoved = neighbour?.let { librarySourceRemovalRowId(it.rootUri) } ?: LibraryAddRowId,
    )
}
