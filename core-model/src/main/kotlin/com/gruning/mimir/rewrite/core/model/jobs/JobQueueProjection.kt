package com.gruning.mimir.rewrite.core.model.jobs

/**
 * Converts producer [JobProgress] into the shared [FileQueueEntry] job model read by surfaces.
 *
 * **Copy-free by construction, not by discipline.** [FileQueueEntry] carries a title and a subtitle,
 * and the UI owns the job surface's wording. So this function does not
 * contain a single player-facing string — it takes a [JobCopy] and asks. A test asserts that every
 * string in the output came from the provider, so a later hand cannot quietly bake one in.
 *
 * **Running work only.** A finished job is not projected: nothing records when a job ended, so a
 * "just finished" row would need a clock this has no honest access to, and inventing one is the
 * fabricated measurement the whole model exists to avoid. A completed scan's counts live in
 * `LastScanSummaryState` and outlive the run on purpose — that is the terminal half, and it is a
 * different question from what is in flight.
 *
 * No render is implemented here: it needs a `CompanionMode` arm, a `CompanionPayload` field and
 * UI copy. The second-screen render contract remains unresolved.
 */
object JobQueueProjection {

    /**
     * Project every running job into the one model, in the order the channel holds them — the job
     * that started first stays first, so rows do not reshuffle under the player as counts tick.
     *
     * [FileQueueEntry.id] is derived from [JobKind], which is stable for a job's whole life. That
     * matters for the reason the model's own KDoc gives: an id that changed as a job progressed would
     * reorder or duplicate its card on every tick. One kind is one row; two scans cannot overlap.
     */
    fun project(running: List<JobProgress>, copy: JobCopy): List<FileQueueEntry> =
        running.map { progress ->
            val text = copy.describe(progress)
            FileQueueEntry(
                id = progress.kind.ordinal.toLong(),
                kind = progress.kind,
                title = text.title,
                subtitle = text.subtitle,
                // The null carries through untouched. A job with no honest denominator becomes a
                // Running with a null fraction, which every existing consumer already reads as
                // "no bar", never "stalled" -- the contract the model shipped with.
                state = FileQueueState.Running(fraction = progress.fraction),
            )
        }
}

/** Supplies the words. Implemented where copy lives, never in a producer or in this file. */
fun interface JobCopy {
    fun describe(progress: JobProgress): JobCopyText
}

/**
 * One row's words.
 *
 * Copy rules enforced by the implementing surface: titled by what is
 * true rather than a product noun, no colour verdict on a count with no established boundary, and
 * absence stated rather than filled — *"12 games still need art"*, never *"47 failures"*.
 */
data class JobCopyText(val title: String, val subtitle: String)
