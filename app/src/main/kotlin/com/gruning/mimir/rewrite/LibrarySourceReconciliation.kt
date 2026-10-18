package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.library.SourceReconcileResult

/** Whether reconciliation changed index truth enough to invalidate dependent launch/BIOS projections. */
internal fun sourceReconcileChangedLibrary(result: SourceReconcileResult): Boolean =
    result.healthySources > 0 || result.removedSources > 0

/** Honest, compact completion copy for the explicit Settings rescan action. */
internal fun sourceReconcileFeedback(result: SourceReconcileResult): String {
    if (result.sourcesChecked == 0) return "NO LIBRARY FOLDERS TO RESCAN"
    return buildString {
        append(result.sourcesChecked)
        append(" CHECKED · ")
        append(result.healthySources)
        append(" READY · ")
        append(result.unavailableSources)
        append(" UNAVAILABLE · ")
        append(result.failedSources)
        append(" FAILED · ")
        append(result.removedSources)
        append(" REMOVED")
    }
}
