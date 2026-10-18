package com.gruning.mimir.rewrite.core.files

import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion

/**
 * MIMIR ADDITION — The file-operation queue, as a pure state model.
 *
 * **The queue is what makes the destructive half safe, so it is modelled as data rather than as
 * behaviour scattered through a coordinator.** Its safety rules are properties of a
 * value here, decidable in a plain JUnit test with no filesystem and no Compose:
 *
 *  - **Undo is a queue STATE, not a toast.** [FileQueueState.UndoAvailable] carries the
 *    [StagedDeletion] handle that actually reverses the delete. It has no timer, no auto-dismiss, and
 *    nothing in this file expires it — it leaves the queue when the player releases it or the session
 *    ends. The chip reads *"Undo available"*.
 *  - **Conflicts resolve explicitly.** [FileQueueState.AwaitingConflict] is a terminal-until-answered
 *    state offering exactly three choices, and
 *    [ManagedFileOperations] refuses to write anything while a conflict is unanswered.
 *  - **Newest first**, prepending each new entry to the queue.
 *
 * Deliberately holds no coroutines, no scope and no filesystem: the live instance lives in `app/`'s
 * `FileBrowserStore` (the same place the extraction scope already lives), and this is the vocabulary it
 * moves through. That split is why the safety properties are testable at all.
 */
object FileOperationQueue {

    /** The queue summary line, including its no-network claim. */
    fun summary(entries: List<FileQueueEntry>): String =
        "${entries.size} ${if (entries.size == 1) "operation" else "operations"} · nothing leaves this device"

    /** Newest first — the design's own ordering. */
    fun enqueue(entries: List<FileQueueEntry>, entry: FileQueueEntry): List<FileQueueEntry> =
        listOf(entry) + entries

    /** Replace one entry's state, leaving its position and identity alone. No-op for an unknown id. */
    fun withState(entries: List<FileQueueEntry>, id: Long, state: FileQueueState): List<FileQueueEntry> =
        entries.map { if (it.id == id) it.copy(state = state) else it }

    /** Drop an entry — the player dismissed it, or its Undo was released. */
    fun remove(entries: List<FileQueueEntry>, id: Long): List<FileQueueEntry> =
        entries.filterNot { it.id == id }

    /**
     * Every delete still holding Undo, oldest first.
     *
     * The list a session teardown must walk: each one owns a real journal on disk and a payload the
     * player can no longer see. Oldest first because that is the order they must be released in for
     * `.mimir-staging` to empty out predictably.
     */
    fun heldDeletions(entries: List<FileQueueEntry>): List<StagedDeletion> =
        entries.reversed().mapNotNull { (it.state as? FileQueueState.UndoAvailable)?.deletion }

    /**
     * The chip text for an entry, so one vocabulary drives the
     * queue card, any summary line, and the tests.
     */
    fun chipLabel(entry: FileQueueEntry): String = when (val state = entry.state) {
        // The placing half of an extraction says what it is doing in counts, never as a percentage that
        // could read 100% while hundreds of files are still being moved into place (E2 O6).
        is FileQueueState.Running ->
            if (state.cancelling) {
                "Cancelling…"
            } else if (state.isPlacing) {
                "Placing files ${state.placed}/${state.placeTotal}"
            } else {
                state.fraction?.let { "${(it.coerceIn(0f, 1f) * 100).toInt()}%" } ?: "Working…"
            }
        FileQueueState.AwaitingConflict -> "Name taken"
        is FileQueueState.UndoAvailable -> "Undo available"
        // "Moved", not "Undo available": the delete's chip advertises the Undo because the delete is not
        // finished until the player answers, and the chip is the only thing saying so. A move IS
        // finished; its chip should report the outcome, and the Undo button beside it is the offer.
        // A rename settles as the same state (its Undo renames back), and says what it was.
        is FileQueueState.ReverseAvailable -> if (state.reverse.previousName != null) "Renamed" else "Moved"
        is FileQueueState.Blocked -> "Not available"
        FileQueueState.Done -> "Done"
        // **COPY — pending review.** The reported defect was a completed progress bar with no move.
        // This word has not received copy review. It must be unmistakably distinct from "Done" at a
        // glance, since success text can make a player believe a move happened. "Nothing to move" and
        // "No change" were alternatives considered; this one states the outcome rather than explaining
        // it, matching every other chip here.
        FileQueueState.Skipped -> "Nothing moved"
        // The player's own Stop. Not "Failed": nothing went wrong, and the card's second line says what was kept.
        is FileQueueState.Cancelled -> "Cancelled"
        is FileQueueState.Failed -> "Failed"
    }
}


/**
 * The three conflict buttons, mapped onto the vendored
 * [ConflictResolution] so there is one vocabulary, not two.
 *
 * [ConflictResolution.ASK] has no choice here on purpose: it is the *unanswered* state, which the queue
 * models as [FileQueueState.AwaitingConflict], and [ManagedFileOperations] refuses to act on.
 */
enum class FileConflictChoice(val label: String, val resolution: ConflictResolution) {
    /** Both files survive; the incoming one gets a deterministic suffix ([ConflictNamePolicy]). */
    KEEP_BOTH("Keep both", ConflictResolution.RENAME),

    /** The existing file is replaced — staged and journalled, so an interruption still rolls back. */
    REPLACE("Replace", ConflictResolution.OVERWRITE),

    /** Leave the existing file alone and count this source as handled. */
    SKIP("Skip", ConflictResolution.SKIP),
}


/**
 * The module boundary: the vendored [FileOperation] vocabulary, mapped onto Mimir's
 * [JobKind] in exactly one place.
 *
 * This function is the whole cost of keeping `TransferTask.kt` vendored and unmodified. It is `when`ed
 * exhaustively on purpose — adding a member upstream should fail the build here rather than silently
 * fall through to a default.
 */
fun FileOperation.toJobKind(): JobKind = when (this) {
    FileOperation.COPY -> JobKind.COPY
    FileOperation.MOVE -> JobKind.MOVE
    FileOperation.DELETE -> JobKind.DELETE
    FileOperation.COMPRESS -> JobKind.COMPRESS
    FileOperation.EXTRACT -> JobKind.EXTRACT
}
