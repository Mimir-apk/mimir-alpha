package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.ExtractionCancelOutcome
import com.gruning.mimir.rewrite.core.files.FileOperation
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.ReversibleMove
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * The running copy, move, extract and rename jobs a queue card's **Cancel** can stop, and the words a
 * stopped card uses.
 *
 * Kept out of [FileBrowserStore] (a singleton `object` that needs a `Context` and a Main dispatcher, and
 * is at its size budget) so the parts that decide things -- which job a press reaches, what a stopped card
 * says it left behind -- are plain values a JUnit test can drive.
 *
 * Main-confined, like the store: every call comes from the store's Main scope, so there is no locking.
 */
internal class FileBrowserJobControl {
    private val jobs = mutableMapOf<Long, Job>()
    private val cancelRequested = mutableSetOf<Long>()

    /**
     * Remember the coroutine running queue entry [id], and forget it the moment it ends.
     *
     * [onCancelled] runs when the job ended by cancellation. Usually the job's own body has already written
     * the card's final line by then and the callback finds nothing to do; it exists for the press that lands
     * before the body ever started, which would otherwise leave a card reading "Cancelling…" for good.
     */
    fun track(id: Long, job: Job, onCancelled: () -> Unit) {
        jobs[id] = job
        job.invokeOnCompletion { cause ->
            jobs.remove(id)
            cancelRequested.remove(id)
            if (cause is CancellationException) onCancelled()
        }
    }

    /**
     * The player pressed Cancel on [id]. True when a running job was found and told to stop; false for an
     * unknown or finished entry (a stale press), which changes nothing.
     */
    fun requestCancel(id: Long): Boolean {
        val job = jobs[id] ?: return false
        if (!cancelRequested.add(id)) return true
        job.cancel()
        return true
    }

    /**
     * [state] as it should be stored for [id]. A progress callback that lands after Cancel was pressed
     * would otherwise write a plain `Running` over the card's "Cancelling…" and the player would see the
     * press ignored.
     */
    fun keepCancelling(id: Long, state: FileQueueState): FileQueueState =
        if (state is FileQueueState.Running && id in cancelRequested && !state.cancelling) {
            state.copy(cancelling = true)
        } else {
            state
        }

    /** Whether Cancel has been pressed for [id] and the job has not yet wound down. */
    fun isCancelling(id: Long): Boolean = id in cancelRequested
}

/**
 * What a **stopped copy or move** left behind, as the card's second line.
 *
 * [landed] sources reached the destination before the stop (the engine reports each one as it commits, so
 * the number is exact even when the stop arrived while the last commit was finishing); [skipped] were left
 * alone by the player's own Skip. A move that was stopped never loses a source: whatever did not land is
 * still where it was, and the sentence says so.
 */
internal fun cancelledTransferState(
    operation: FileOperation,
    sourceCount: Int,
    landed: Int,
    skipped: Int,
    destinationFolder: String,
    companionsLeft: Int = 0,
): FileQueueState.Cancelled {
    val past = if (operation == FileOperation.MOVE) "moved" else "copied"
    val total = sourceCount.coerceAtLeast(landed + skipped)
    val text = when {
        landed == 0 && operation == FileOperation.MOVE -> "Nothing was moved. Everything is still where it was."
        landed == 0 -> "Nothing was copied. The unfinished copy was removed."
        landed + skipped >= total && companionsLeft > 0 ->
            "Too late to stop: everything you picked was already $past to $destinationFolder. " +
                if (companionsLeft == 1) {
                    "The 1 file that goes with it is still where it was."
                } else {
                    "The ${count(companionsLeft)} files that go with it are still where they were."
                }
        landed + skipped >= total ->
            "Too late to stop: everything was already $past to $destinationFolder."
        else -> {
            val rest = if (operation == FileOperation.MOVE) {
                "The rest are still where they were."
            } else {
                "The rest were not copied."
            }
            "${count(landed)} of ${count(total)} files were $past to $destinationFolder and kept. $rest"
        }
    }
    return FileQueueState.Cancelled(text)
}

/**
 * What a **stopped extraction** left behind. The engine reports what its rollback found through
 * `onCancelled` ([ExtractionCancelOutcome]); without one (the stop arrived before the engine started, or after it
 * had already finished) [allPlaced] says whether the last placing report reached the total.
 */
internal fun cancelledExtractionState(
    stopped: ExtractionCancelOutcome?,
    allPlaced: Boolean,
): FileQueueState.Cancelled = FileQueueState.Cancelled(
    when {
        stopped != null -> stopped.sentence
        allPlaced -> "Too late to stop: everything was already extracted."
        else -> "Nothing was extracted."
    },
)

/**
 * The settled state of a rename: always an Undo, because the old name is one fact the card carries
 * ([ReversibleMove.previousName]), and so is each companion that was renamed with it ([companionRenames]).
 * [undoable] is false for the rename-back an Undo itself performs, so undoing an undo does not loop.
 */
internal fun renameSettledState(
    from: File,
    landedPath: String,
    undoable: Boolean,
    companionRenames: Map<String, String> = emptyMap(),
): FileQueueState =
    if (undoable) {
        FileQueueState.ReverseAvailable(
            ReversibleMove(
                landedPaths = listOf(landedPath),
                originFolder = from.parent.orEmpty(),
                previousName = from.name,
                companionRenames = companionRenames,
            ),
        )
    } else {
        FileQueueState.Done
    }

/**
 * What a delete of a playlist says it left: its discs are not part of it. "The 3 discs it lists stay." -- policy
 * 2026-10-09. [playlists] is how many `.m3u` the delete included, [members] how many distinct listed discs stay.
 */
internal fun playlistStaysNote(playlists: Int, members: Int): String {
    val one = playlists == 1
    val pronoun = if (one) "it" else "they"
    val verb = if (one) "lists" else "list"
    return if (members == 1) "The disc $pronoun $verb stays." else "The ${count(members)} discs $pronoun $verb stay."
}

/** The card line for a rename, in past tense for its whole life -- the move card's rule (spec 6.4). */
internal fun renameSubtitle(oldName: String, newName: String): String = "Renamed $oldName to $newName"

private fun count(value: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(value)
