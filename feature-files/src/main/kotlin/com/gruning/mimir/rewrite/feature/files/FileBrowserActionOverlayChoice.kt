package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import androidx.annotation.StringRes
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope

/**
 * **F-1 — the SELECT action overlay's row model.**  R-3
 * settles the door (SELECT opens it, Delete moves inside as a row); R-13 settles X/SECONDARY's fate (it
 * goes quiet in MANAGE once this ships). Both behaviors are preserved here.
 *
 * ## The partition — one clause, not a table of exceptions
 *
 * A row exists for a tile kind only if the verb acts on what that kind structurally is:
 *  - **MOVE/COPY act on a plain FILE.** [FileBrowserCommand.OfferTransfer]'s own gate, unchanged —
 *    `kind == FILE && transferChoiceAvailable`. A DIRECTORY is excluded on a MEASUREMENT, not a
 *    preference: QA drove a parent-into-descendant move in `core-files` and watched it run away — 9x
 *    amplification in 34 ms, no termination, no free-space check, no cancel — see that command's own
 *    KDoc. The refusal belongs in `core-files` and does not exist yet.
 *  - **EXTRACT acts on an ARCHIVE.** [FileBrowserTile.extractable]. Unconditional on
 *    `extractChoiceAvailable`, unlike MOVE/COPY — extract always has somewhere to go
 *    (`FileBrowserController.onExtract`'s safe default), so the row stays present and its OWN activation
 *    chooses the chooser or the direct path, exactly mirroring what `SECONDARY` used to choose. This is
 *    "moved", not "narrowed": nothing X could reach before is unreachable now.
 *  - **DELETE acts on identity, not content.** No kind check — present for every kind whenever
 *    `FileBrowserController.deleteAvailable`.
 *  - **RENAME acts on a name, so it applies to every kind -- files, archives AND folders.** It was absent
 *    from this enum while no engine, command or sink existed for it. Rename now provides Cancel and follows Move: a journalled rename in the engine, a sink gated by `FileBrowserController.renameAvailable`
 *    (MANAGE plus a real sink), and a name typed on Mimir's own keyboard. A folder renames because the
 *    runaway that keeps folders out of Move/Copy is a copy into itself, which a rename in place is not.
 *
 * Global fixed order — Move, Copy, Rename, Extract, Delete — filtered per kind, never reordered. Move/Copy
 * and Extract are mutually exclusive by tile kind, so their relative order never actually renders; Delete
 * is last always, on a spacing gap rather than a position rule (see the surface file).
 */
internal enum class FileBrowserActionOverlayRowKind { MOVE, COPY, RENAME, EXTRACT, DELETE }

/**
 * The rows this overlay draws for a tile of [kind], in fixed order, filtered by the SAME sink-availability
 * gates the dispatch table already reads. [deleteAvailable] and [transferChoiceAvailable] are not
 * re-derived here — they are the identical values `FileBrowserController` exposes and `fileBrowserCommand`
 * already gates SELECT and SECONDARY on, via [fileBrowserActionsAvailable].
 */
/**
 * **The tile kinds a transfer may carry — one set, read by both the dispatch table and the overlay's row
 * model, so the two can never answer differently.**
 *
 * DIRECTORY is absent deliberately and is not an oversight: `FileBrowserCommand.OfferTransfer`'s own KDoc
 * records the measured runaway that the folder refusal is made of. R-19 widened this to archives, nothing
 * more.
 */
internal val FileBrowserTransferableTileKinds: Set<FileBrowserTileKind> =
    setOf(FileBrowserTileKind.FILE, FileBrowserTileKind.ARCHIVE)

internal fun fileBrowserActionOverlayRows(
    kind: FileBrowserTileKind,
    deleteAvailable: Boolean,
    transferChoiceAvailable: Boolean,
    renameAvailable: Boolean = false,
): List<FileBrowserActionOverlayRowKind> = buildList {
    // **R-19, archives move and copy too.** A `.zip` is a file to the player, and
    // this overlay is the first surface that made the old files-only limit visible -- a menu offering
    // Extract but not Move reads as broken rather than deliberate. The transfer engine never cared: the
    // restriction was a dispatch condition, not an engine limit. DIRECTORY stays excluded, which is where
    // the measured runaway behind the original refusal actually lives.
    if (kind in FileBrowserTransferableTileKinds && transferChoiceAvailable) {
        add(FileBrowserActionOverlayRowKind.MOVE)
        add(FileBrowserActionOverlayRowKind.COPY)
    }
    // Every kind, directories included: see the RENAME paragraph in this file's header.
    if (renameAvailable) {
        add(FileBrowserActionOverlayRowKind.RENAME)
    }
    if (kind == FileBrowserTileKind.ARCHIVE) {
        add(FileBrowserActionOverlayRowKind.EXTRACT)
    }
    if (deleteAvailable) {
        add(FileBrowserActionOverlayRowKind.DELETE)
    }
}

/**
 * Focus identity for the overlay's rows. Its own [FocusScope], for the reason every chooser in this
 * browser states: `DefaultFocusAuthority.entries` is keyed by [FocusKey] globally, so a key shared with
 * the folio behind the overlay would evict that tile from the graph underneath it.
 */
internal object FileBrowserActionOverlayFocus {

    val Scope: FocusScope = FocusScope("files/actions")

    fun optionKey(kind: FileBrowserActionOverlayRowKind): FocusKey = FocusKey("$OPTION_PREFIX${kind.name}")

    /** Which row [key] presses, or null when it is not one of this screen's rows. */
    fun optionOf(key: FocusKey?): FileBrowserActionOverlayRowKind? {
        val name = key?.value?.takeIf { it.startsWith(OPTION_PREFIX) }?.removePrefix(OPTION_PREFIX)
            ?: return null
        return FileBrowserActionOverlayRowKind.entries.firstOrNull { it.name == name }
    }

    private const val OPTION_PREFIX = "files/actions/"
}

/** Every focusable key the overlay holds, in render order. */
internal fun fileBrowserActionOverlayKeys(rows: List<FileBrowserActionOverlayRowKind>): List<FocusKey> =
    rows.map(FileBrowserActionOverlayFocus::optionKey)

/**
 * The open overlay, as one value — **and the tile is carried here, never re-read from the ring.**
 * [FileBrowserTransferChooser]'s rule, restated for the identical reason: by the time a row is activated
 * the overlay's own [FileBrowserActionOverlayFocus.Scope] is what holds focus, so the browse ring is
 * nowhere near the tile the press is about.
 *
 * @param path the tile SELECT was pressed on.
 * @param kind that tile's kind, snapshotted alongside the path so [fileBrowserActionOverlayRows] never
 *   has to be re-run against a listing that may have changed underneath the overlay.
 * @param rows the rows as resolved when the overlay opened.
 */
internal data class FileBrowserActionOverlayState(
    val path: String,
    val kind: FileBrowserTileKind,
    val rows: List<FileBrowserActionOverlayRowKind>,
)

/**
 * PLACE names the legend/category; rows name the exact operation. Move/Copy reuse
 * fileBrowserTransferOptions labels rather than a second copy. Extract and Delete wording
 * remains unreviewed.
 */
internal fun fileBrowserActionOverlayLabel(res: Resources, kind: FileBrowserActionOverlayRowKind): String =
    res.getString(fileBrowserActionOverlayLabelRes(kind))

@StringRes
internal fun fileBrowserActionOverlayLabelRes(kind: FileBrowserActionOverlayRowKind): Int = when (kind) {
    FileBrowserActionOverlayRowKind.MOVE -> fileBrowserTransferRowLabelRes(FileBrowserTransferKind.MOVE)
    FileBrowserActionOverlayRowKind.COPY -> fileBrowserTransferRowLabelRes(FileBrowserTransferKind.COPY)
    // Wording remains unreviewed. Mirrors the inspector's own existing "Extract…" button (FileBrowserSurface.kt).
    FileBrowserActionOverlayRowKind.RENAME -> R.string.files_overlay_row_rename
    FileBrowserActionOverlayRowKind.EXTRACT -> R.string.files_overlay_row_extract
    // Wording remains unreviewed. Plain, matches the verb everywhere else it already appears ("SELECT DELETE" today).
    FileBrowserActionOverlayRowKind.DELETE -> R.string.files_overlay_row_delete
}

@StringRes
private fun fileBrowserTransferRowLabelRes(kind: FileBrowserTransferKind): Int =
    fileBrowserTransferOptions().first { it.kind == kind }.labelRes

/**
 * The row's second line, or blank — which draws nothing, per the extract/discset choosers' own rule (a
 * reserved-but-empty line would make the row look like it lost its text).
 *
 * Delete announces the action and names where Undo lives without moving focus.
 * Exact wording remains unreviewed.
 */
internal fun fileBrowserActionOverlayDetail(res: Resources, kind: FileBrowserActionOverlayRowKind): String = when (kind) {
    // Wording remains unreviewed.
    FileBrowserActionOverlayRowKind.DELETE -> res.getString(R.string.files_overlay_delete_detail)
    FileBrowserActionOverlayRowKind.MOVE,
    FileBrowserActionOverlayRowKind.COPY,
    FileBrowserActionOverlayRowKind.RENAME,
    FileBrowserActionOverlayRowKind.EXTRACT,
    -> ""
}

/** The overlay's own normalised width. Arbitrary magnitude, like every grid in this module. */
private const val FileBrowserActionOverlayGridWidth = 20_000f

/** One row's vertical pitch. */
private const val FileBrowserActionOverlayRowPitch = 3_000f

/**
 * Synthetic geometry for the overlay's row at [index] — [fileBrowserTransferOptionBounds]'s exact shape,
 * for its exact reason: full-width rows in a scope containing nothing else, so a horizontal press has no
 * geometrically valid answer and [FileBrowserController]'s own overlay dispatch absorbs it as the hard
 * modal it is.
 */
internal fun fileBrowserActionOverlayOptionBounds(index: Int): FocusBounds {
    val top = index * FileBrowserActionOverlayRowPitch
    return FocusBounds(
        left = 0f,
        top = top,
        right = FileBrowserActionOverlayGridWidth,
        bottom = top + FileBrowserActionOverlayRowPitch,
    )
}

/**
 * The overlay's focus slots — one per row. A seventh tracker, register-then-retire, matching
 * [FileBrowserDiscSetFocusTracker] / [FileBrowserTransferFocusTracker] verbatim: the registrations must
 * exist only while the overlay is up and must ALL be gone when it closes — not optional, since
 * `DefaultFocusAuthority.requestFocus` is a silent no-op on an unregistered key.
 */
internal class FileBrowserActionOverlayFocusTracker {
    private val registered = linkedSetOf<FocusKey>()

    fun update(authority: FocusAuthority, scope: FocusScope, rows: List<FileBrowserActionOverlayRowKind>) {
        val desired = linkedSetOf<FocusKey>()
        rows.forEachIndexed { index, kind ->
            val key = FileBrowserActionOverlayFocus.optionKey(kind)
            authority.registerVirtual(key, scope, fileBrowserActionOverlayOptionBounds(index))
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
