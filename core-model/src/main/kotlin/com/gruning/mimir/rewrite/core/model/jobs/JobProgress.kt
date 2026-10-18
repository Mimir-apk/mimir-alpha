package com.gruning.mimir.rewrite.core.model.jobs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What long-running work is in flight right now, across every family, in one place.
 *
 * **Kind and counts, and deliberately nothing else.** The scan has
 * four internal phases; enrichment and scrape have none, being one long pass each. A shared phase
 * vocabulary would have flattened *Grouping* and *Saving* into one word for the only family that can
 * tell them apart, and given the other two a field with one possible value. So [ScanPhase] stays the
 * scan's own business and this channel carries what a reader actually needs to say something true:
 * which job, how far.
 *
 * Only fields needed by readers belong here: `FileQueueEntry.kind` turned out to be written everywhere and read
 * nowhere, so nothing goes in here before a reader needs it.
 *
 * **This is producer-side, and it is not the job model.** [FileQueueEntry] is. The panel
 * projects these into that model where the copy lives; nothing in `core-resolver` or a worker should
 * ever hold a player-facing string.
 *
 * **One entry per [JobKind].** Two scans cannot overlap — they are serialized — and a second scrape
 * replaces the first rather than racing it. A list rather than a single value because a scrape while
 * a scan runs is normal, and screen 2 selection when both a job and a game are active needs
 * to be able to see that both are.
 */
object JobProgressState {
    private val _running = MutableStateFlow<List<JobProgress>>(emptyList())

    /** Empty when nothing is running. Absence is stated, never drawn as an idle bar. */
    val running: StateFlow<List<JobProgress>> = _running.asStateFlow()

    /**
     * Publish or replace this kind's progress. Order is insertion order — the job that started first
     * stays first, so a surface does not reshuffle under the player as counts tick.
     */
    fun report(kind: JobKind, seen: Int, total: Int? = null) {
        val next = JobProgress(kind = kind, seen = seen, total = total)
        _running.update { current ->
            val at = current.indexOfFirst { it.kind == kind }
            if (at < 0) current + next else current.toMutableList().also { it[at] = next }
        }
    }

    /** This kind has finished, whatever the outcome. A row that outlives its job is a lie. */
    fun clear(kind: JobKind) {
        _running.update { current -> current.filterNot { it.kind == kind } }
    }
}

/**
 * One job's live position.
 *
 * @param seen work that has demonstrably happened. Never an estimate.
 * @param total the denominator when one genuinely exists, otherwise null.
 */
data class JobProgress(
    val kind: JobKind,
    val seen: Int,
    val total: Int? = null,
) {
    /**
     * 0..1, or **null when there is no honest fraction to draw**. Same contract as
     * [ScanProgress.fraction] and [FileQueueState.Running.fraction], and the same reason: a zero total
     * yields null rather than a division, because "nothing to do" is not "0% done".
     */
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (seen.toFloat() / it).coerceIn(0f, 1f) }
}
