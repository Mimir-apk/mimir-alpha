package com.gruning.mimir.rewrite.core.model.jobs

/**
 * The one job model shared by file copy/move/delete, identity enrichment, artwork scrape, library
 * scan and duplicate scan. Producers retain their own progress and failure details but expose
 * this common shape to job surfaces.
 *
 * **Moved here from `core-files` on 2026-08-30, and the move is the reason it can be the one model.**
 * `core-resolver` — where the library scan lives — does not depend on `core-files`, so three of the five
 * families could not access the shared type. `core-model` is the pure Kotlin/JVM module
 * everything downstream already depends on. The types below carry no Android and no coroutines.
 *
 * **Nothing about behaviour changed in the move.** The only edit to the shipped shapes is
 * `FileQueueEntry.operation: FileOperation` becoming [FileQueueEntry.kind]: [JobKind] — see D1-R19 on that
 * field, and [JobKind] below.
 *
 * **The honesty contract travels with the model and is not an implementation detail.**
 * [FileQueueState.Running.fraction] is nullable because null means *"no honest fraction to draw"*, never
 * 0%. A conforming family that reports `412 of 2,010` must be counting work that has demonstrably
 * happened; where it cannot, it reports null and the surface draws no bar. Absence is stated,
 * never filled with a fabricated value.
 *

 *
 * Some KDoc below links types that live in `core-files` ([ManagedFileOperations], [FileBrowserStore],
 * [FileOperationQueue]). Those links do not resolve from here and are kept deliberately: the sentences
 * they sit in record why these shapes are what they are, and rewriting them to remove a link would lose
 * the provenance for nothing.
 */

/**
 * What sort of work a [FileQueueEntry] describes.
 *
 * **Mimir-authored, deliberately**. The shipped discriminator was the
 * vendored `FileOperation` enum, which could not come here (MIT third-party source, `NOTICE`-tracked) and
 * would have been wrong if it could: it names file operations, and four of the five families this model
 * serves are not file operations. `core-files` maps its vendored enum onto this at the module boundary,
 * in one function, and `TransferTask.kt` stays vendored and unmodified.
 */
enum class JobKind {
    /** Library scan — the job the player meets first, and the one with no progress channel today. */
    SCAN,

    /** Artwork / metadata scrape. */
    SCRAPE,

    /** Identity enrichment (hash / identify). */
    ENRICH,

    /** Duplicate scan. */
    DEDUPE,

    // The file-operation vocabulary, mapped from the vendored `FileOperation` at the core-files boundary.
    COPY,
    MOVE,
    DELETE,
    COMPRESS,
    EXTRACT,
}

/**
 * One row of the queue.
 *
 * @param id stable for this entry's whole life, so a state change never reorders or duplicates a card.
 *   Supplied by the caller (`FileBrowserStore` counts up) rather than generated here — a model that
 *   reads a clock is not a pure model, and upstream's own `TransferTask.id =
 *   System.currentTimeMillis()` collides for two operations queued in the same millisecond.
 * @param kind what sort of work this is. **Was `operation: FileOperation` until 2026-08-30, and that
 *   KDoc line read "the vendored [FileOperation] vocabulary, not a second enum" — policy D1-R19
 *   overturned it for two reasons. `FileOperation` is vendored MIT code (`TransferTask.kt`, upstream
 *   `SysAdminDoc/FileExplorer`) and moving it here would drag third-party source across the provenance
 *   boundary `NOTICE` maintains; and a library scan is not a COPY/MOVE/DELETE, so that enum was the
 *   wrong discriminator for four of the five families this model now serves. `core-files` maps its
 *   vendored enum onto [JobKind] in one function at the module boundary.**
 * @param title what the operation is acting on — a file name, or "3 files".
 * @param subtitle the design's second line: where it is going, or why it stopped.
 */
data class FileQueueEntry(
    val id: Long,
    val kind: JobKind,
    val title: String,
    val subtitle: String,
    val state: FileQueueState,
) {
    /** Whether this entry's Undo button exists — a staged delete, or a move that can be sent back. */
    val undoable: Boolean get() =
        state is FileQueueState.UndoAvailable || state is FileQueueState.ReverseAvailable

    /** Whether this entry is waiting on a Keep both / Replace / Skip answer. */
    val awaitingConflict: Boolean get() = state == FileQueueState.AwaitingConflict
}

/** The five states the design draws, plus an honest [Failed]. */
sealed interface FileQueueState {

    /**
     * In flight. [fraction] is 0..1 and the design renders it as a percentage and a bar **when it is
     * non-null.**
     *
     * **Null means no observed fraction is available, rather than zero percent complete.** Three of
     * [ManagedFileOperations]' progress calls report a bookkeeping advance rather than an observed one —
     * a skipped source, a small file below the streaming threshold, and an atomic same-folder rename —
     * and a caller that turned every one of those into a determinate `done/total` was fabricating a
     * measurement the engine never made. `FileBrowserStore` enqueues `Running(fraction = null)` and only
     * ever promotes a card to a real fraction from a genuinely observed advance; an `onAccounted` call
     * keeps it null.
     *
     * **"Genuinely observed" has two sources, not one** — this sentence named only the first until F-7:
     * 1. [ManagedFileOperations]' streamed-byte `onProgress` calls, for a move or copy.
     * 2. **A completed, discrete unit of work**, for an operation that has no byte stream to watch:
     *    `stageDelete` fires once per path, and only *after* that path's `atomicMove` into staging has
     *    finished, so `staged / total` counts things that have demonstrably happened. That is a
     *    measurement, not a model of one — the distinction this whole field exists to keep.
     *
     * What stays forbidden is unchanged: a fraction that stands in for *"something is happening and I
     * do not know how much"*. Extraction's enqueue and its no-total fallback were the last two `0f`s
     * making that claim in this product, and both are `null` now. [FileOperationQueue.chipLabel] and
     * `feature-files`' card projection both already treat a null progress as "no bar, not a stalled one"
     * — this reuses that existing, documented contract (`FileBrowserQueueCard.progress`'s own KDoc; see
     * also `OnboardingFlow.kt`'s identical null-means-indeterminate precedent) rather than inventing a
     * second one.
     *
     * **Deliberately widened in place rather than split into a new sealed state.** A new direct subtype
     * of [FileQueueState] would force every exhaustive `when` over this hierarchy to add an arm —
     * correct in principle, but `FileBrowserQueue.kt`'s two exhaustive `when`s
     * (`fileBrowserQueueActions`, `fileBrowserQueueTone`) would also require corresponding changes. A
     * nullable field keeps `Running` the same single arm everywhere that matches on the TYPE (both of
     * `FileBrowserQueue.kt`'s `when`s, and [FileBrowserStore]'s `fileQueueDismissable`), and only forces a
     * change where `.fraction` is actually READ -- this file's own [chipLabel] (fixed) and
     * `FileBrowserQueue.kt:267`'s `(entry.state as? Running)?.fraction?.coerceIn(...)` (compiles unchanged
     * via Kotlin's nullable-safe-call flattening, and degrades to the card's existing null-progress
     * contract). A future sealed `Running` hierarchy must also update
     * `FileBrowserQueue.kt` to classify the indeterminate case explicitly instead of through this
     * cast's existing null-handling.
     */
    data class Running(
        val fraction: Float?,
        val placed: Int? = null,
        val placeTotal: Int? = null,
        /** The player pressed Cancel; the job is winding down to its next safe point. */
        val cancelling: Boolean = false,
    ) : FileQueueState {
        /**
         * Whether this is the **placing** phase of an extraction: every file is already unpacked into
         * staging and Mimir is now moving them, one by one, to where they belong. [placed] and
         * [placeTotal] are counts of work that has demonstrably happened, so they are as honest as
         * [fraction], and they are what keeps the card from reading 100% while files are still moving.
         */
        val isPlacing: Boolean get() = placed != null && placeTotal != null && placeTotal > 0
    }

    /**
     * A name already exists at the destination and **nothing has been written**. Offers *Keep both /
     * Replace / Skip*; see [FileConflictChoice].
     */
    data object AwaitingConflict : FileQueueState

    /**
     * The delete is done as far as the player is concerned, and completely reversible.
     *
     * [deletion] is the live handle — [ManagedFileOperations.undoDelete] takes it and puts the files
     * back, [ManagedFileOperations.finalizeDelete] takes it and makes the delete permanent. Holding the
     * handle IN the state is what makes "Undo is a queue state" true structurally: an entry in this
     * state cannot exist without the means to reverse itself.
     */
    data class UndoAvailable(val deletion: StagedDeletion) : FileQueueState

    /**
     * **The move is finished and can still be walked back.** Undo for a completed move remains available as an optional reverse action.
     *
     * Structurally the same promise as [UndoAvailable] — the state cannot exist without the means to
     * reverse itself ([reverse]) — and behaviourally a different one, which is why it is a different
     * state rather than a second constructor:
     *
     * | | [UndoAvailable] (delete) | this (move) |
     * |---|---|---|
     * | what happened | staged, not yet permanent | already done, on disk, at the destination |
     * | dismissing the card | **refused** — it would finalise a delete silently | **allowed** — the move is already final; Undo was only ever an offer |
     * | reversing it | `undoDelete`, restores from staging | a plain MOVE back, through the queue like any other |
     *
     * That second row is the trap this type exists to avoid. `UndoAvailable` refuses DISMISS because a
     * dismissed delete-card finalises the delete and disappears from teardown's own accounting; a move
     * card carries no such debt, and refusing DISMISS on it would strand a card the player is done with.
     */
    data class ReverseAvailable(val reverse: ReversibleMove) : FileQueueState

    /** Mimir will not do this, and said so without touching anything. E.g. a `.rar`. */
    data class Blocked(val reason: String) : FileQueueState

    /** Finished. */
    data object Done : FileQueueState

    /**
     * **Handled, and moved nothing.** Reported 2026-08-18: progress reached 100% without moving any file.
     *
     * A distinct state rather than a flavour of [Done] or [Failed], because it is neither and the words
     * differ. Nothing went wrong — no error, no obstacle, no rollback — so [Failed] would be a lie in the
     * alarming direction. But nothing happened either, so [Done] is a lie in the reassuring one, which is
     * the reported defect: a card settling at 100% over a filesystem it never touched.
     *
     * Reached when the engine handled every source and transferred none of them — a Skip answer, or any
     * future path where `resolveTransferTarget` declines. **It draws no bar**, because [Running] is the
     * only state that carries a fraction and this one never was one.
     */
    data object Skipped : FileQueueState

    /**
     * **The player stopped it, and the card says what that left.** Cancel (copy, move, extract) takes effect
     * at the next safe point: a copy removes its half-written file, a move finishes or rolls back its current
     * item and never loses the source, an extract removes what THIS job placed (it knows, from its journal).
     * [kept] is plain words about what is on disk afterwards -- "Nothing was extracted." or "2 of 5 files were
     * moved and kept." -- and is shown under the chip like a [Failed] reason. Not [Failed]: nothing went wrong.
     */
    data class Cancelled(val kept: String) : FileQueueState

    /** Tried and failed. [reason] is player-facing. */
    data class Failed(val reason: String) : FileQueueState
}

/**
 * Everything needed to send a completed move back where it came from — [FileQueueState.ReverseAvailable]'s
 * handle.
 *
 * **The reverse is a plain move, not a special operation.** Running it back through the ordinary transfer
 * path is what makes it honest: it gets the same staging and rollback journal, the same conflict card if
 * something has since taken the old name, the same progress, and the same library relocation on
 * completion. A bespoke "restore" would be a second mover to keep correct, and the first thing it would
 * get wrong is the collision.
 *
 * @param landedPaths where the files actually are now — resolved by asking the filesystem after the move,
 *   never by assuming `destination + originalName`. A *Keep both* collision renames on arrival, and a
 *   handle built on the assumed name would move a file the player never touched, or nothing at all.
 * @param originFolder the folder they came from. A single folder, not one per file: a set of sources that
 *   did not all agree on a parent has no single "back", and the caller answers null rather than choosing
 *   one — the same rule `commonParentFolderName` already applies to the card's own subtitle.
 * @param departedFromLibrary the move took the game OUT of the library — its row was deleted, because
 *   that is what a scan would do. **Undo does not resurrect it**: the reverse move finds no row to
 *   relocate and the shelf entry returns only at the next scan of that root. Carried on the handle rather
 *   than re-derived, because by the time Undo is pressed the only other record of it is a sentence in a
 *   subtitle, and reading UI copy back as state is how a rename becomes a behaviour change.
 */
data class ReversibleMove(
    val landedPaths: List<String>,
    val originFolder: String,
    val departedFromLibrary: Boolean = false,
    /**
     * Set when this was a RENAME rather than a move: the name the file had before. Undo then renames the
     * single landed path back to this name in [originFolder], instead of moving it to another folder.
     */
    val previousName: String? = null,
    /**
     * Set with [previousName]: the files that were renamed ALONGSIDE the renamed one -- a disc's `.sbi` / `.sub` -- as
     * where each stands now, mapped to the name it had. Exactly these, recorded as they happened, are what Undo renames
     * back: finding the companions again by the new name would also pick up a file the rename never touched (a
     * `Better.sbi` that already belonged to a `Better.chd`).
     */
    val companionRenames: Map<String, String> = emptyMap(),
)

/** One path held aside by [ManagedFileOperations.stageDelete]. */
data class StagedDeletionEntry(val originalPath: String, val stagedPath: String)

/**
 * A delete that has happened from the player's point of view and is still fully reversible.
 *
 * The handle a queue entry carries while it shows **Undo available**. [operationRootPath] is the journal
 * directory under `.mimir-staging`; it is what [ManagedFileOperations.heldRoots] protects from recovery
 * sweeps and what [ManagedFileOperations.finalizeDelete] eventually removes.
 */
data class StagedDeletion(
    val operationRootPath: String,
    val entries: List<StagedDeletionEntry>,
) {
    val count: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()
}
