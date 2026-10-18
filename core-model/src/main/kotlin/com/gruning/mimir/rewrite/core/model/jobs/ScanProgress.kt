package com.gruning.mimir.rewrite.core.model.jobs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which part of a scan is running, and how far it has got.
 *
 * **Producer-side only.** This carries a phase and counts, never words. Policy D1-R14 puts the
 * job surface's wording in the UI, so `core-resolver` emits facts and whoever renders them
 * owns the sentence. It maps into [FileQueueEntry] with `kind = `[JobKind.SCAN] at the boundary that
 * has copy, exactly as `FileOperation` maps to [JobKind] at the `core-files` boundary.
 *
 * **A scan cannot honestly draw one bar end to end, and policy D1-R21 decided what happens
 * instead.** The three phases differ in what is knowable:
 *
 * - [ScanPhase.WALKING] — the traversal has no total. Nothing knows how many files exist until it
 *   finishes, so [fraction] is null and [seen] ticks up. Null means *"no honest bar"*, never
 *   *"stalled"* — the same contract [FileQueueState.Running] carries.
 * - [ScanPhase.RECOGNISING] — a loop over a known total, one completed file at a time. This is the
 *   honesty contract's second permitted source of a real fraction, *"a completed, discrete unit of
 *   work"*, so the bar here is earned rather than modelled.
 * - [ScanPhase.GROUPING] and [ScanPhase.PERSISTING] — bulk work with nothing to count. Phase only.
 *
 * The consequence, stated because it is visible and deliberate: **the bar appears partway through a
 * scan and disappears again.** That is what honesty costs here, and it is preferable to a bar that
 * moves because something guessed.
 */
enum class ScanPhase {
    /** Traversing the source root. No total exists yet. */
    WALKING,

    /** Deciding platform/identity per file, over a known total. */
    RECOGNISING,

    /** Dedup and grouping the recognised batch. */
    GROUPING,

    /** Writing rows and reconciling the index. */
    PERSISTING,
}

/**
 * One progress observation.
 *
 * @param seen how many files this phase has finished with. Always a count of work that has
 *   demonstrably happened, never an estimate.
 * @param total the denominator when one genuinely exists, otherwise null. Null is the normal case
 *   for [ScanPhase.WALKING].
 */
data class ScanProgress(
    val phase: ScanPhase,
    val seen: Int,
    val total: Int? = null,
) {
    /**
     * 0..1, or **null when there is no honest fraction to draw** — which is the whole point of the
     * field being nullable. A zero total yields null rather than a division, because "no files" is
     * not "0% done".
     */
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (seen.toFloat() / it).coerceIn(0f, 1f) }
}

/**
 * The single process-scoped channel for live scan progress, written by the scan and read by whatever
 * is showing it.
 *
 * Same shape and the same reason as `LastScanSummaryState`, which holds the TERMINAL counts: this
 * one is transient and goes back to null when no scan is running, so an idle surface states absence
 * rather than showing a stale bar. The two are deliberately separate — a finished scan's breakdown
 * must outlive the scan, and a running scan's fraction must not outlive it.
 *
 * Lives in `core-model` because the scan lives in `core-resolver`, which cannot see `app`. That is
 * the same visibility problem D1-R11 step 1 solved for the job model itself.
 */
object ScanProgressState {
    private val _progress = MutableStateFlow<ScanProgress?>(null)

    /** Null whenever no scan is in flight. Absence is stated, not drawn as an empty bar. */
    val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()

    fun report(phase: ScanPhase, seen: Int, total: Int? = null) {
        _progress.value = ScanProgress(phase = phase, seen = seen, total = total)
        // The scan's phase detail lives here; the shared channel gets kind and counts only, per
        // D1-R22. One producer call keeps the two in step, so they can never disagree about whether a
        // scan is running.
        JobProgressState.report(JobKind.SCAN, seen = seen, total = total)
    }

    /** Called when a scan ends, whatever the outcome. A bar that survives its job is a lie. */
    fun clear() {
        _progress.value = null
        JobProgressState.clear(JobKind.SCAN)
    }
}
