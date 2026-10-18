package com.gruning.mimir.rewrite.feature.files

import androidx.annotation.StringRes
import com.gruning.mimir.rewrite.core.files.PlatformDestination
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope

/**
 * Platform-folder destination selection for Extract, Move and Copy.
 *
 * ## Why this exists rather than more rows on the two choosers
 *
 * The obvious shape was to grow [fileBrowserExtractOptions]' row list. It works for Extract and breaks
 * immediately on Move/Copy: that chooser's rows are VERBS, and a platform list crossed with two verbs is
 * every folder twice. The destination pick is already the one place all three verbs converge
 * (`FileBrowserPickSession`, one sealed type with one `purpose` driving one commit gate), so the list
 * belongs at the head of the pick, where each verb reaches it having already said which verb it is.
 *
 * That also means **this screen never has to know what it is picking a destination FOR.** It shows
 * folders and reports one back; `FileBrowserController.commitDestinationAt` projects the pick's own
 * purpose onto the right sink, exactly as the pinned commit row already does after a manual navigation.
 *
 * ## What it is NOT
 *
 * **Not a replacement for browsing.** [FileBrowserDestinationRowKind.BROWSE] is always the first row and
 * always present, keeping manual navigation at the top of the selection, but it is still the escape hatch, not the point: the list is every
 * platform folder Mimir can see, which is not the same as every folder a player might want. Manual
 * navigation stays exactly one press away, and when the list is empty the controller skips this screen
 * entirely rather than showing a door with nothing behind it.
 *
 * **Not a recogniser.** The list arrives already decided by `PlatformDestinationPolicy` — including its
 * order and which entry (if any) the classifier recognised. Nothing here re-sorts or re-judges it.
 */
internal enum class FileBrowserDestinationRowKind {
    /** A real platform folder on a real drive. [FileBrowserDestinationRow.path] is where it goes. */
    PLATFORM,

    /**
     * "Browse for a folder instead…" — hands the pick back to the browser, which is what happened
     * unconditionally before this screen existed.
     */
    BROWSE,
}

/**
 * One row of the destination list.
 *
 * @param label what the row says. The folder's own name for a platform row (blank for the browse row, whose words are
 *   `files_destination_browse`, drawn by [fileBrowserDestinationRowLabel]), never the raw platform id —
 *   `fileBrowserPlatformCode` can answer null for a recognised-but-uncodeable platform, and "ps2" on a
 *   folder actually called `PS2 Games` would be a second name for one place.
 * @param detail the line underneath: where the folder is (a path, drawn as it is; with [onOtherDrive] set, after the words that say
 *   it is on another drive). Blank is allowed and draws nothing rather than an empty line, matching the extract chooser's own rule.
 * @param onOtherDrive the folder is on a different drive than the subject: a copy across storage, not a rename. The words for that
 *   are the display's (`files_destination_other_drive`), so the row holds only the path.
 * @param path the destination. Null only for [FileBrowserDestinationRowKind.BROWSE], which has no
 *   destination yet — that is the whole meaning of the row.
 * @param recognised this is the platform the classifier named for the subject. At most one row carries
 *   it, and it is what the screen lands focus on.
 * @param fromLibrary the library sources games from this folder. Shown, because "your PS2 folder" and "a
 *   folder called ps2 that exists" are different claims and the player is the one who knows which they
 *   meant.
 */
internal data class FileBrowserDestinationRow(
    val kind: FileBrowserDestinationRowKind,
    val label: String,
    val detail: String,
    val path: String?,
    val recognised: Boolean = false,
    val fromLibrary: Boolean = false,
    val onOtherDrive: Boolean = false,
)

/**
 * The rows for [destinations], in the order the policy already decided, with the browse escape FIRST.
 *
 * **The [destinations] order is not re-decided here** — only where the escape hatch sits relative to it.
 * `PlatformDestinationPolicy.destinations` sorts recognised first, then library-backed, then
 * merely-existing, and this maps that ordering 1:1 onto the rows after the browse row. A second ordering
 * rule for the platform rows in the presentation layer is how two orders start disagreeing; policy
 * 2026-08-18 only moved the ONE row that isn't part of that ordering.
 *
 * @param sameDriveLabel supplied rather than built here so the one place that knows the player's words for a drive stays the one
 *   place. The default is the folder's parent path. A folder on another drive carries its path and [FileBrowserDestinationRow.onOtherDrive].
 */
internal fun fileBrowserDestinationRows(
    destinations: List<PlatformDestination>,
    sameDriveLabel: (PlatformDestination) -> String = { it.path.substringBeforeLast('/', "") },
): List<FileBrowserDestinationRow> = buildList {
    // the escape hatch leads, ahead of every platform row.
    add(
        FileBrowserDestinationRow(
            kind = FileBrowserDestinationRowKind.BROWSE,
            label = "",
            detail = "",
            path = null,
        ),
    )
    destinations.forEach { destination ->
        add(
            FileBrowserDestinationRow(
                kind = FileBrowserDestinationRowKind.PLATFORM,
                label = destination.path.trimEnd('/').substringAfterLast('/'),
                detail = if (destination.onSubjectVolume) sameDriveLabel(destination) else destination.path,
                path = destination.path,
                recognised = destination.recognised,
                fromLibrary = destination.fromLibrary,
                onOtherDrive = !destination.onSubjectVolume,
            ),
        )
    }
}

/** The screen's eyebrow — the question it is asking, in the two chooser screens' own idiom. */
@StringRes
internal val FileBrowserDestinationEyebrow: Int = R.string.files_destination_eyebrow

/**
 * Focus identity for the destination list.
 *
 * **Its own [FocusScope]**, for the reason [FileBrowserExtractFocus.Scope] and
 * [FileBrowserTransferFocus.Scope] both give: `DefaultFocusAuthority.entries` is keyed by [FocusKey]
 * globally, so a key shared with the folio behind this screen would evict that tile from the graph.
 *
 * **Keyed by INDEX, not by kind**, and that is the one structural difference from the two screens this
 * mirrors. Their rows are an enum — at most one row per kind — so a kind IS an identity. Here every
 * platform row has the same kind and the list is as long as the player's drives make it, so a kind-keyed
 * identity would hand the authority one key for twenty rows. The index is stable for the life of one
 * open ([FileBrowserDestinationList.rows] is snapshotted), which is exactly as long as it needs to be.
 */
internal object FileBrowserDestinationFocus {

    val Scope: FocusScope = FocusScope("files/destination")

    fun optionKey(index: Int): FocusKey = FocusKey("$OPTION_PREFIX$index")

    /** Which row [key] presses, or null when it is not a row of this screen. */
    fun indexOf(key: FocusKey?): Int? =
        key?.value?.takeIf { it.startsWith(OPTION_PREFIX) }?.removePrefix(OPTION_PREFIX)?.toIntOrNull()

    private const val OPTION_PREFIX = "files/destination/"
}

/** Every focusable key the screen holds, in render order. */
internal fun fileBrowserDestinationKeys(rows: List<FileBrowserDestinationRow>): List<FocusKey> =
    rows.indices.map { FileBrowserDestinationFocus.optionKey(it) }

/**
 * Where focus lands when the screen opens: **the recognised platform when there is one, else the first
 * PLATFORM row — never [FileBrowserDestinationRowKind.BROWSE], even now that browse sits at row 0.**
 *
 * Preselection requires recognition. That is exactly what
 * `PlatformDestination.recognised` means and it is false far more often than true — a zipped PS2 game, or
 * anything sitting outside a platform-named folder, recognises nothing, and lands here on the first real
 * destination instead, exactly as it always has.
 *
 * **F-3, 2026-08-18 moved the browse row to index 0 and deliberately did NOT change what unsure-landing
 * means.** A literal "row 0" fallback would have silently repointed at the escape hatch on every unsure
 * open — the COMMON case, per the paragraph above — trading a one-press "here's a folder" screen for a
 * one-press "back to browsing" screen, with nothing else about the ticket asking for that. `openDestinationList`
 * never calls this with an empty [rows] behind an empty destination list — it returns before building rows
 * at all when the resolved list is empty — so a PLATFORM row is always present when this actually runs;
 * the plain `0` is kept only as a total-function safety net for a caller this file does not currently have.
 */
internal fun fileBrowserDestinationLanding(rows: List<FileBrowserDestinationRow>): FocusKey? {
    if (rows.isEmpty()) return null
    val recognised = rows.indexOfFirst { it.recognised }
    if (recognised >= 0) return FileBrowserDestinationFocus.optionKey(recognised)
    val firstPlatform = rows.indexOfFirst { it.kind == FileBrowserDestinationRowKind.PLATFORM }
    return FileBrowserDestinationFocus.optionKey(if (firstPlatform >= 0) firstPlatform else 0)
}

/**
 * The open destination list, as one value — **and the subject is carried here, never re-read from the
 * ring.**
 *
 * [FileBrowserTransferChooser]'s requirement, and it is if anything sharper here: this screen is one
 * press from relocating a file, the pick underneath it already knows which file, and re-deriving the
 * subject from a focus ring that is currently pointing at a FOLDER row would be a category error as well
 * as a bug.
 *
 * @param subjectPath the archive or file the pick is about. Carried for the heading, and for nothing
 *   else — the commit reads the pick session, which is the authority on what is being placed.
 * @param rows snapshotted when the screen opened, so the indices [FileBrowserDestinationFocus] hands the
 *   authority cannot shift under a player's thumb while a background refresh lands.
 */
internal data class FileBrowserDestinationList(
    val subjectPath: String,
    val rows: List<FileBrowserDestinationRow>,
)

/** Row pitch and grid width for the virtual focus geometry — [fileBrowserTransferOptionBounds]' shape. */
private const val FileBrowserDestinationRowPitch = 63f
private const val FileBrowserDestinationGridWidth = 508f

/**
 * Virtual bounds for row [index] — full-width rows in one column, exactly
 * [fileBrowserTransferOptionBounds]' geometry and for its reasons.
 *
 * **The one difference is length, and it is why this screen scrolls and that one does not.** A transfer
 * chooser is two rows and can be a plain `Column`; this list is as long as the player's drives make it,
 * so the surface owns a `LazyListState` and follows the ring with [fileBrowserRevealAction] — the same
 * mechanism the browse folio uses. These bounds stay the *virtual* ones regardless: they are the model
 * `moveFocus` scores against, and a row that has scrolled out of sight is still a row the D-pad may
 * travel to, which is precisely what makes it scroll back into view.
 */
internal fun fileBrowserDestinationOptionBounds(index: Int): FocusBounds {
    val top = index * FileBrowserDestinationRowPitch
    return FocusBounds(
        left = 0f,
        top = top,
        right = FileBrowserDestinationGridWidth,
        bottom = top + FileBrowserDestinationRowPitch,
    )
}

/**
 * The destination list's focus slots — one per row.
 *
 * **A sixth tracker rather than a mode on any of the five**, matching [FileBrowserTransferFocusTracker]'s
 * reasoning unchanged: these registrations must exist only while this screen is open and must all be
 * retired when it closes, or the authority keeps twenty folder keys alive behind a folio that has never
 * heard of them.
 */
internal class FileBrowserDestinationFocusTracker {
    private val registered = linkedSetOf<FocusKey>()

    /** Add-then-remove, matching every other tracker in this module. */
    fun update(authority: FocusAuthority, scope: FocusScope, rows: List<FileBrowserDestinationRow>) {
        val desired = linkedSetOf<FocusKey>()
        rows.forEachIndexed { index, _ ->
            val key = FileBrowserDestinationFocus.optionKey(index)
            authority.registerVirtual(key, scope, fileBrowserDestinationOptionBounds(index))
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

    private fun retire(authority: FocusAuthority, key: FocusKey) {
        authority.unregisterVirtual(key)
        authority.unregister(key)
    }
}
