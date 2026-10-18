package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.library.ScanResult
import com.gruning.mimir.rewrite.core.model.library.SourceReconcileResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the most recent library scan actually found — the cheap half of policy D1-R10's
 * queue-and-item surface, landed ahead of the expensive half.
 *
 * **Why this exists at all.** `skippedByReason` has been produced at
 * `LibraryRepositoryImpl.kt:746,810` and read by nothing. Every scan computed an honest per-reason
 * breakdown and dropped it one hop from the surface: three of the four callers reduced a whole
 * [ScanResult] to `.outcome`, the fourth kept four bare integers, and `reconcileSources` lost the
 * map at its own aggregation. Policy **D1-R20**, 2026-08-30: one process-wide summary that
 * every scan path writes, so a reader is correct regardless of which scan ran.
 *
 * **It deliberately carries no outcome.** A single-root scan has one; a multi-root reconcile does
 * not, and collapsing "two roots healthy, one unavailable" into one verdict would be exactly the
 * fabricated measurement [ScanResult]'s own honesty rules exist to prevent. Health is the source
 * list's business; this type answers *what did the last pass see*, nothing more.
 *
 * **Privacy is by construction, not by care.** [LastScanSummary.skippedByReason]'s keys are
 * `SkipReason.name`, never a path or a title — `ScanResult.skippedByReason`'s own KDoc states that
 * contract, and it is what makes this safe to display. It is also inside policy **D1-R12**'s
 * boundary: counts by reason are not per-file identity, so nothing here is held for a pass or
 * written to disk.
 *
 * **The copy this is for, per policy D1-R14:** *"12 games still need art"* is a statement of
 * absence and is satisfiable from these counts. *"47 failures"* is a verdict and is not this type's
 * to make.
 *
 * **Process-scoped and deliberately not persisted.** It is the same shape as [CompanionState] — a
 * plain object holding a [StateFlow] — which is what lets the secondary display read it without
 * either window holding a reference to the other. **Owed:** a summary that survives process death
 * needs a store, and nothing here should be mistaken for one.
 *
 */
object LastScanSummaryState {
    private val _summary = MutableStateFlow<LastScanSummary?>(null)

    /** Null until a scan has completed in this process. Absence is stated, never faked as zeroes. */
    val summary: StateFlow<LastScanSummary?> = _summary.asStateFlow()

    /** One root: an `addSource` or a `rescan`. */
    fun record(result: ScanResult) {
        _summary.value = LastScanSummary(
            filesSeen = result.filesSeen,
            recognized = result.recognized,
            groups = result.groups,
            skipped = result.skipped,
            skippedByReason = result.skippedByReason,
        )
    }

    /** Every root: a `rescanAll`. */
    fun record(result: SourceReconcileResult) {
        _summary.value = LastScanSummary(
            filesSeen = result.filesSeen,
            recognized = result.recognized,
            groups = result.groups,
            skipped = result.skipped,
            skippedByReason = result.skippedByReason,
        )
    }
}

/**
 * The last scan's counts, with the reason breakdown that was previously discarded.
 *
 * Invariant inherited from both producers: [skippedByReason]'s values sum to exactly [skipped]. An
 * empty map alongside a non-zero [skipped] means the producer did not tally reasons — not that
 * there were none.
 */
data class LastScanSummary(
    val filesSeen: Int,
    val recognized: Int,
    val groups: Int,
    val skipped: Int,
    val skippedByReason: Map<String, Int>,
)
