package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState

/**
 * The Files words that are chosen in code (localization step 4), kept out of the big screen files so their size budgets do not grow.
 * Each takes the [Resources] of the host (`filesResources()` in a composable, any locale in a test) and returns a whole string from a
 * resource: nothing here joins translated pieces. Names, paths and sizes are the player's own and arrive as arguments.
 */

/** The extraction question that stays in the fixed header while manual browsing reaches a virtual drive root: "Extract game.zip to…". */
internal fun fileBrowserDestinationContext(res: Resources, archivePath: String): String =
    res.getString(R.string.files_extract_destination_context, fileBrowserExtractArchiveLabel(archivePath))

/** A destination row's words: the folder's own name, or (the browse row has no folder yet) the escape hatch's sentence. */
internal fun fileBrowserDestinationRowLabel(res: Resources, row: FileBrowserDestinationRow): String =
    if (row.kind == FileBrowserDestinationRowKind.BROWSE) res.getString(R.string.files_destination_browse) else row.label

/** The line under a destination row: where the folder is, after the words that say so when it is on another drive. */
internal fun fileBrowserDestinationRowDetail(res: Resources, row: FileBrowserDestinationRow): String =
    if (row.onOtherDrive) res.getString(R.string.files_destination_other_drive, row.detail) else row.detail

/**
 * The Queue screen's header line: how many operations it holds, and that nothing leaves the device. One plural string, so the count and the
 * promise are not joined here; its English reads word for word as core-files' `FileOperationQueue.summary` (which is no longer drawn).
 */
internal fun fileBrowserQueueSummary(res: Resources, count: Int): String =
    res.getQuantityString(R.plurals.files_queue_summary, count, count)

/**
 * A queue card's chip, chosen from the card's state on display. Its English reads word for word as core-files' `FileOperationQueue.chipLabel`
 * (`FilesQueueTextTest` walks every state against it); the core-files strings are not drawn directly. A running card's
 * percentage is the engine's fraction, clamped to a whole percent exactly as core-files does, and a placing card says how many files have
 * landed, never a percentage that could read 100% while files are still moving.
 */
internal fun fileBrowserQueueChipLabel(res: Resources, state: FileQueueState): String = when (state) {
    is FileQueueState.Running -> when {
        state.cancelling -> res.getString(R.string.files_queue_chip_cancelling)
        state.isPlacing -> res.getString(R.string.files_queue_chip_placing, state.placed ?: 0, state.placeTotal ?: 0)
        else -> state.fraction?.let { res.getString(R.string.files_queue_chip_percent, (it.coerceIn(0f, 1f) * 100).toInt()) }
            ?: res.getString(R.string.files_queue_chip_working)
    }
    FileQueueState.AwaitingConflict -> res.getString(R.string.files_queue_chip_name_taken)
    is FileQueueState.UndoAvailable -> res.getString(R.string.files_queue_chip_undo_available)
    is FileQueueState.ReverseAvailable ->
        res.getString(if (state.reverse.previousName != null) R.string.files_queue_chip_renamed else R.string.files_queue_chip_moved)
    is FileQueueState.Blocked -> res.getString(R.string.files_queue_chip_not_available)
    FileQueueState.Done -> res.getString(R.string.files_queue_chip_done)
    FileQueueState.Skipped -> res.getString(R.string.files_queue_chip_nothing_moved)
    is FileQueueState.Cancelled -> res.getString(R.string.files_queue_chip_cancelled)
    is FileQueueState.Failed -> res.getString(R.string.files_queue_chip_failed)
}
