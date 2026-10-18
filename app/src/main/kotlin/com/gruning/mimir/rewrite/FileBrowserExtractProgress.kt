package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.InterruptedExtraction
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind

/*
 * What an extraction's queue card says, decided as plain functions so a JVM test can drive them.
 *
 * **An extraction is two jobs wearing one card** (E2 alpha-cert O6, 2026-10-08). Unpacking writes every
 * file into a hidden staging folder; PLACING then moves each one to where the player asked for it. The card
 * used to be driven by the first job alone, so it read "100%" the moment the bytes were unpacked and stayed
 * there for 90+ seconds while about 1,400 of 2,000 files were still waiting to be moved.
 *
 * The bar is weighted rather than restarted: unpacking fills the first half, placing the second. Both
 * halves are measurements of work that has happened -- bytes written, files moved -- never a guess, and the
 * card cannot reach 100% until the last file is in place and the state becomes Done.
 */

private const val UNPACKING_SHARE = 0.5f

/** The card while bytes are being unpacked: the first half of the bar, or no bar when there is no total. */
internal fun extractUnpackingState(done: Long, total: Long): FileQueueState.Running =
    FileQueueState.Running(
        if (total > 0) UNPACKING_SHARE * (done.toFloat() / total).coerceIn(0f, 1f) else null,
    )

/**
 * The card while files are being placed: the second half of the bar, and the chip counts files
 * ("Placing files 600/2000", see `FileOperationQueue.chipLabel`) instead of showing a percentage.
 */
internal fun extractPlacingState(placed: Int, total: Int): FileQueueState.Running =
    if (total <= 0) {
        FileQueueState.Running(null)
    } else {
        FileQueueState.Running(
            fraction = UNPACKING_SHARE + (1f - UNPACKING_SHARE) * (placed.toFloat() / total).coerceIn(0f, 1f),
            placed = placed.coerceIn(0, total),
            placeTotal = total,
        )
    }

/**
 * E2 P2: the failed cards for extractions a previous process did not finish. The card names the archive
 * and says how far it got and what was kept ([InterruptedExtraction.sentence]); the same words are the
 * [FileQueueState.Failed] reason, which is what the queue shows under the chip.
 *
 * Tagged [JobKind.COPY] like every extraction card -- `FileBrowserQueueHostWiringTest` pins that no card is
 * tagged EXTRACT.
 */
internal fun interruptedExtractionEntries(
    reports: List<InterruptedExtraction>,
    nextId: () -> Long,
): List<FileQueueEntry> = reports.map { report ->
    FileQueueEntry(
        id = nextId(),
        kind = JobKind.COPY,
        title = report.title,
        subtitle = report.sentence,
        state = FileQueueState.Failed(report.sentence),
    )
}
