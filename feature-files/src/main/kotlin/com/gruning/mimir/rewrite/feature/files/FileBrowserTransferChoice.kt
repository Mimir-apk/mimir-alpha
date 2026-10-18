package com.gruning.mimir.rewrite.feature.files

import androidx.annotation.StringRes
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion

/**
 * **G2's decision layer — `Move into…` / `Copy into…`, the door.**
 *
 * Move, Copy and Extract are **separate controls**; Copy is available alongside Move.
 *
 * **This file provides the chooser entry point (G2).**
 * `FileBrowserStore.moveInManager` / `copyInManager` are *finished, staged, rollback-backed, conflict-
 * disciplined and tested*, with **zero production callers**. Nothing below re-implements any of it. This
 * is the row model and the focus identity for a two-row question, and nothing else.
 *
 * ## What slice 1 deliberately does NOT do
 *
 *  - **No smart platform row.** `Move into <CODE>` / `Copy into <CODE>` are slice 2 and are gated on a
 *    device measurement nobody has taken (M1): the chooser's `Column` has no scroll owner and no
 *    `LazyListState`, so a fourth row **clips silently**. [fileBrowserTransferOptions] answers exactly two
 *    rows, always, and `FileBrowserTransferChoiceTest` pins the count rather than trusting this sentence.
 *  - **No directories.** See [FileBrowserCommand.OfferTransfer]'s KDoc — the refusal that would be needed
 *    lives in `core-files` and does not exist.
 *  - **No acknowledgement and no library seam.** Slice 3. Until it lands, a completed move is invisible to
 *    the library; that integration remains deferred.
 *
 * ## The one thing a reader must not get wrong
 *
 * **CORRECTED 2026-08-16. This paragraph used to say a completed move had NO Undo, and it no longer
 * does.** The old reasoning was sound and is worth keeping as the reason the new shape looks the way it
 * does: `FileBrowserQueueAction.UNDO` needed a `FileQueueState.UndoAvailable`, that state cannot exist
 * without a `StagedDeletion` handle, and `ManagedFileOperations.move` deletes its own staging on success
 * — so there was no journal to replay and no honest button to draw.
 *
 * A completed move now settles as **`FileQueueState.ReverseAvailable`**, whose handle is the landed paths
 * and the folder they came from, and Undo sends them back as an ordinary move. Note what did NOT change:
 * there is still no staging journal for a finished move, so the reverse is a genuinely new transfer that
 * can collide, fail, or be refused. **A COPY still has no Undo, and should not** — nothing was taken
 * away, and "undoing" it would mean deleting a file the player asked to create.
 *
 * What a string in this file, in [fileBrowserTransferFooterHints], or on the commit row may therefore
 * claim: that the move can be walked back. What it may **not** claim: that the walk-back is free,
 * guaranteed, or that it restores a game to the shelf — a move that took a game out of the library
 * deleted its row, and the reverse move brings the file home but not the shelf entry, which returns at
 * the next scan. The chooser remains the deliberate step, and it is still the primary mitigation: the
 * player pressed X, read a titled screen naming the file, and chose a row that names the verb.
 */
internal enum class FileBrowserTransferKind {
    /** Relocate: the file leaves where it was. `FileBrowserStore.moveInManager`. */
    MOVE,

    /**
     * Duplicate: the file stays where it was. `FileBrowserStore.copyInManager`.
     *
     * Copy is offered as the less destructive alternative: it is the arm a player reaches for
     * when they are not sure, and it is why the chooser has two rows rather than one.
     */
    COPY,
    ;

    /**
     * What the pinned commit affordance commits while this verb's destination pick is open.
     *
     * **The projection is here, not in the controller, and that is what makes the wrong sink
     * unconstructible.** [FileBrowserController]'s `commitPurposeForCurrentMode` — the single gate — reads
     * the open pick's own purpose, and `fileBrowserCommand`'s CONFIRM arm turns that purpose into
     * [FileBrowserCommand.CommitMoveDestination] or [FileBrowserCommand.CommitCopyDestination]. Both trace
     * to this one expression, so there is no arrangement of controller state in which the player answers a
     * Move question and a Copy is performed.
     */
    val commitPurpose: FileBrowserCommitPurpose
        get() = when (this) {
            MOVE -> FileBrowserCommitPurpose.MOVE_DESTINATION
            COPY -> FileBrowserCommitPurpose.COPY_DESTINATION
        }
}

/**
 * One row of the transfer chooser.
 *
 * **No `destination` field, unlike [FileBrowserExtractOption], and the absence is the slice.** Every row
 * in slice 1 is a picker row: it answers "which verb?" and hands the browser back to the player to answer
 * "where?". A destination that is null on every row would be a field carrying no information; slice 2's
 * smart rows are what give it one, and they can add it then.
 *
 * @param labelRes the player-facing words (a string resource): "Move into…" / "Copy into…". Not "Place" —
 *   `PLACE` is the umbrella word for the *legend and the eyebrow*, chosen because it covers both verbs and
 *   claims neither ([fileBrowserFooterHints]); a ROW may never be vague, because a row is what gets
 *   pressed.
 */
internal data class FileBrowserTransferOption(
    val kind: FileBrowserTransferKind,
    @StringRes val labelRes: Int,
)

/**
 * The rows the transfer chooser draws — **exactly two, for every plain file, always.**
 *
 * **It takes no arguments, and that is the shape rather than an omission.** [fileBrowserExtractOptions]
 * has to be handed `ExtractDestinationOptions` because its rows depend on what the library could resolve;
 * these two rows depend on nothing at all. `Move into…` and `Copy into…` can always finish, because the
 * destination is supplied by the picker — which is this browser itself. That is §4.4 of the spec stated as
 * a signature: **the chooser is not identity-gated**; it opens for every plain FILE in MANAGE, recognised
 * or not. What platform recognition gates is slice 2's *smart* rows, not Move itself.
 *
 * **Move first, Copy second.** Landing focus is row 0. Row order is a feel question
 * and is still awaiting review; the order is one list literal.
 *
 * **Two rows is a ceiling, not today's coincidence.** The chooser's `Column` owns no scroll and no
 * `LazyListState`, and overflow clips with no signal — `FileBrowserExtractSurface`'s own KDoc says the
 * screen is *"a plain `Column` of at most two rows"*. Slice 2 raises the ceiling only behind M1.
 */
internal fun fileBrowserTransferOptions(): List<FileBrowserTransferOption> = listOf(
    FileBrowserTransferOption(kind = FileBrowserTransferKind.MOVE, labelRes = R.string.files_transfer_row_move),
    FileBrowserTransferOption(kind = FileBrowserTransferKind.COPY, labelRes = R.string.files_transfer_row_copy),
)

/**
 * Focus identity for the chooser's rows. Its own [FocusScope], for the reason
 * [FileBrowserExtractFocus.Scope] and [FileBrowserQueueFocus.Scope] both state:
 * `DefaultFocusAuthority.entries` is keyed by [FocusKey] **globally**, so a key shared with the folio
 * behind the chooser would evict that tile from the graph.
 *
 * **A fifth scope rather than a mode on the extract one.** The two choosers are never open together (each
 * `open*Chooser` is guarded on the other's state, and `SECONDARY` produces one command or the other, never
 * both), but sharing a scope would mean sharing the authority's `focusMemory` entry — so a remembered
 * `MOVE` row from the last press would validate perfectly against a *different* screen's rows. That is the
 * exact hazard [FileBrowserController.openExtractChooser] already seeds explicitly against, one screen
 * along.
 */
internal object FileBrowserTransferFocus {

    val Scope: FocusScope = FocusScope("files/transfer")

    fun optionKey(kind: FileBrowserTransferKind): FocusKey = FocusKey("$OPTION_PREFIX${kind.name}")

    /** Which option [key] presses, or null when it is not a chooser row. */
    fun optionOf(key: FocusKey?): FileBrowserTransferKind? {
        val name = key?.value?.takeIf { it.startsWith(OPTION_PREFIX) }?.removePrefix(OPTION_PREFIX)
            ?: return null
        return FileBrowserTransferKind.entries.firstOrNull { it.name == name }
    }

    /** Distinct from `files/extract/` by construction — see [Scope]. */
    private const val OPTION_PREFIX = "files/transfer/"
}

/** Every focusable key the chooser holds, in render order. */
internal fun fileBrowserTransferKeys(rows: List<FileBrowserTransferOption>): List<FocusKey> =
    rows.map { FileBrowserTransferFocus.optionKey(it.kind) }

/**
 * The open chooser, as one value — **and the file is carried here, never re-read from the focus ring.**
 *
 * [FileBrowserExtractChoiceState]'s requirement, and it applies unchanged: the rows are about ONE file,
 * resolved at the moment `SECONDARY` was pressed. Reading [FileBrowserController.focusedTile] when a row
 * is activated would move whatever the browse ring happens to be on — and in the general case that is not
 * even the file, because the chooser's own scope is active while it is open and the browse focus
 * underneath it is free to have been changed by anything that publishes into the browse scope meanwhile.
 *
 * **On this screen the stakes are higher than on the extract one**, which is why the rule is restated
 * rather than cross-referenced: a mis-targeted extract writes a stray folder the player can delete; a
 * mis-targeted move relocates a file they did not choose — and Undo does not cover that, because a player
 * who does not know a file moved has no reason to press it.
 *
 * @param path the file being moved or copied.
 * @param rows the rows as resolved when the chooser opened. Snapshotted for [FileBrowserExtractChoiceState
 *   .rows]' reason, even though [fileBrowserTransferOptions] is static today — the field is what makes a
 *   re-open after an abandoned pick show the *same* question, and slice 2's rows will not be static.
 */
internal data class FileBrowserTransferChooser(
    val path: String,
    val rows: List<FileBrowserTransferOption>,
)

/**
 * **The one destination pick in progress, whichever verb asked for it** — the state
 * [FileBrowserController] holds while the browser is being used to answer *"where?"*.
 *
 * ## Why one field and not two
 *
 * G2 gives the destination pick a second and a third verb. The obvious shape — a `transferPick` field
 * beside F-06's `destinationPick` — was rejected: it duplicates the phase computation, the abandon path,
 * the `loadAfterPickChange` arming and every teardown site, leaves `destinationPick != null` silently
 * meaning *"an EXTRACT pick"* in four places that each have to remember the other field exists, and makes
 * "both picks open at once" representable. A sealed type makes that state unconstructible and keeps
 * [FileBrowserController]'s commit gate literally one predicate, preserving the single commit predicate.
 *
 * ## Why it wraps [FileBrowserDestinationPick] rather than extending it
 *
 * F-06's record already carries everything an extract pick needs and `FileBrowserExtractWiringTest` reads
 * it by name (`controller.destinationPick?.archivePath`). Composing the existing record preserves that contract —
 * [FileBrowserController.destinationPick] stays readable and keeps meaning exactly *"the extract pick, if
 * that is what is open"*. Folding the two into one record remains deferred.
 *
 * ## What every arm must answer
 *
 * [purpose] is the whole point: it is what [FileBrowserController]'s single commit gate projects onto the
 * pinned row, and therefore what decides which of the three `Commit*Destination` commands `CONFIRM`
 * produces. A pick and its commit command cannot disagree, because they are the same value read twice.
 */
internal sealed interface FileBrowserPickSession {

    /** The file or archive being placed. */
    val subjectPath: String

    /** The directory the pick began in — restored when it is abandoned. */
    val originPath: String

    /** The browse back-stack as it was then, snapshotted whole. */
    val originStack: List<String>

    /** What the pinned commit affordance commits while this pick is open. */
    val purpose: FileBrowserCommitPurpose

    /** F-06's *"Extract in…"*, unchanged. Carries the chooser's resolved rows, which a re-open restores. */
    data class Extract(val pick: FileBrowserDestinationPick) : FileBrowserPickSession {
        override val subjectPath: String get() = pick.archivePath
        override val originPath: String get() = pick.originPath
        override val originStack: List<String> get() = pick.originStack
        override val purpose: FileBrowserCommitPurpose = FileBrowserCommitPurpose.EXTRACT_DESTINATION
    }

    /**
     * G2's *"Move into…"* / *"Copy into…"*.
     *
     * **No rows are carried, unlike [Extract], and the asymmetry is real rather than an omission.** The
     * transfer chooser's rows are static ([fileBrowserTransferOptions] takes no arguments), so there is
     * nothing that could resolve differently while the player is away and nothing a snapshot would
     * protect. Extract's rows are snapshotted because they come from the library's ROM folders, which
     * `app/` refreshes off the main thread — re-resolving on the way back could legitimately answer
     * differently and hand the player a *different* pair of options than the one they were looking at.
     */
    data class Transfer(
        override val subjectPath: String,
        val kind: FileBrowserTransferKind,
        /**
         * The subject's TILE kind — FILE or ARCHIVE.
         *
         * **Carried since policy R-19 (2026-08-18), and it was safe to omit before that.** While
         * transfers were files-only, abandoning a pick could reopen the action overlay with a hardcoded
         * `FILE` and be right by construction. R-19 lets an archive be moved, so that constant became a
         * lie the moment it could be wrong: an abandoned archive pick would have reopened an overlay
         * missing its own Extract row.
         */
        val subjectTileKind: FileBrowserTileKind,
        override val originPath: String,
        override val originStack: List<String>,
    ) : FileBrowserPickSession {
        override val purpose: FileBrowserCommitPurpose get() = kind.commitPurpose
    }
}

// Transfer chooser focus geometry matches `fileBrowserExtractOptionBounds`.
// Folding the two into one generic helper remains deferred.

/** The chooser's own normalised width. Arbitrary magnitude, like every grid in this module. */
private const val FileBrowserTransferGridWidth = 20_000f

/** One option row's vertical pitch. */
private const val FileBrowserTransferRowPitch = 3_000f

/**
 * Synthetic geometry for the chooser's row at [index].
 *
 * **Full-width rows, one column, and that is the whole geometry.** The chooser is two rows
 * ([fileBrowserTransferOptions]), so there is nothing here for `moveFocus`'s scoring to get wrong:
 * NAV_UP/NAV_DOWN resolve the neighbour above/below, and NAV_LEFT/NAV_RIGHT find no candidate at all and
 * answer false.
 *
 * That is why this screen needs no consumption rule of the kind the browse table has for its own
 * full-width commit row ([fileBrowserCommitVirtualBounds]' NAV_LEFT/RIGHT case): that rule exists because
 * the commit row shares a scope with a multi-column tile grid, so a horizontal press had a geometrically
 * valid — and badly wrong — answer. Here the scope contains nothing but these rows, so a horizontal press
 * has no answer, and [FileBrowserController]'s own transfer dispatch absorbs it as the hard modal it is.
 */
internal fun fileBrowserTransferOptionBounds(index: Int): FocusBounds {
    val top = index * FileBrowserTransferRowPitch
    return FocusBounds(
        left = 0f,
        top = top,
        right = FileBrowserTransferGridWidth,
        bottom = top + FileBrowserTransferRowPitch,
    )
}

/**
 * The transfer chooser's focus slots — one per option row.
 *
 * **A fifth tracker rather than a mode on any of the four**, matching [FileBrowserExtractFocusTracker]'s
 * own reasoning exactly: these registrations must exist only while the chooser is open and must ALL be
 * gone when it closes, or the browse scope inherits focus slots for rows nobody is looking at. The virtual
 * registration is not optional even though the chooser is short and always composed —
 * `DefaultFocusAuthority.requestFocus` is a **silent** no-op on an unregistered key (BUG#01's mechanism),
 * so [FileBrowserController.openTransferChooser]'s landing would simply not happen, leaving a pushed scope
 * with no focused row and a screen whose D-pad does nothing.
 */
internal class FileBrowserTransferFocusTracker {
    private val registered = linkedSetOf<FocusKey>()

    /** Add-then-remove, matching all four trackers in `FileBrowserFocusRegistration.kt`. */
    fun update(authority: FocusAuthority, scope: FocusScope, rows: List<FileBrowserTransferOption>) {
        val desired = linkedSetOf<FocusKey>()
        rows.forEachIndexed { index, option ->
            val key = FileBrowserTransferFocus.optionKey(option.kind)
            authority.registerVirtual(key, scope, fileBrowserTransferOptionBounds(index))
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

    /** Both registrations — `FileBrowserFocusTracker.retire`'s mechanism B, which applies identically to
     * a composed chooser row. */
    private fun retire(authority: FocusAuthority, key: FocusKey) {
        authority.unregisterVirtual(key)
        authority.unregister(key)
    }
}
