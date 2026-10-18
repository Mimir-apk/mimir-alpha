package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.library.ScanOutcome
import com.gruning.mimir.rewrite.core.model.library.ScanResult
import com.gruning.mimir.rewrite.core.model.library.SourceReconcileResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LastScanSummaryState] is the one place a finished scan's counts land.
 *
 * Both producers must map into it, because the four production scan paths split between them: a
 * single-root `addSource`/`rescan` yields a [ScanResult], and `rescanAll` yields a
 * [SourceReconcileResult]. Before this, three of those paths reduced the whole result to `.outcome`
 * and the fourth kept four bare integers, so the reason breakdown reached nothing.
 *
 * Kotlin `object`s live for the whole test process, so every assertion here reads the flow
 * immediately after the call that set it and each test uses values unique to itself. There is
 * deliberately no test that the initial value is null: it is, but asserting it would depend on this
 * class running before anything else in the JVM, which is not a property a test may rely on.
 */
class LastScanSummaryStateTest {

    @Test
    fun `a single-root scan lands with its reason breakdown intact`() {
        LastScanSummaryState.record(
            ScanResult(
                filesSeen = 2010,
                recognized = 1998,
                groups = 412,
                skipped = 12,
                outcome = ScanOutcome.Healthy,
                skippedByReason = mapOf("UNREADABLE" to 7, "BIOS" to 5),
            ),
        )

        val summary = checkNotNull(LastScanSummaryState.summary.value)
        assertEquals(2010, summary.filesSeen)
        assertEquals(1998, summary.recognized)
        assertEquals(412, summary.groups)
        assertEquals(12, summary.skipped)
        assertEquals(mapOf("UNREADABLE" to 7, "BIOS" to 5), summary.skippedByReason)
        // The invariant the copy depends on: a tally sentence can only be honest if the parts add up.
        assertEquals(summary.skipped, summary.skippedByReason.values.sum())
    }

    @Test
    fun `a rescan-all lands too, so the summary is never stale after one`() {
        LastScanSummaryState.record(
            SourceReconcileResult(
                sourcesChecked = 3,
                healthySources = 2,
                removedSources = 0,
                filesSeen = 5000,
                recognized = 4950,
                groups = 900,
                skipped = 50,
                skippedByReason = mapOf("ARCHIVE" to 30, "UNREADABLE" to 20),
                unavailableSources = 1,
            ),
        )

        val summary = checkNotNull(LastScanSummaryState.summary.value)
        assertEquals(5000, summary.filesSeen)
        assertEquals(50, summary.skipped)
        assertEquals(mapOf("ARCHIVE" to 30, "UNREADABLE" to 20), summary.skippedByReason)
    }

    @Test
    fun `the summary carries no outcome, because a multi-root reconcile has none to carry`() {
        LastScanSummaryState.record(
            SourceReconcileResult(
                sourcesChecked = 2,
                healthySources = 1,
                removedSources = 0,
                filesSeen = 77,
                recognized = 77,
                groups = 7,
                skipped = 0,
                unavailableSources = 1,
            ),
        )

        val summary = checkNotNull(LastScanSummaryState.summary.value)
        assertEquals(77, summary.filesSeen)
        // One root healthy and one unavailable has no single verdict. The type has no field to put
        // one in, deliberately -- collapsing it would be a claim the scan never made. This asserts
        // the SHAPE, so adding an `outcome` field would change the result contract.
        assertNull(
            "LastScanSummary must remain independent of artwork outcomes",
            LastScanSummary::class.java.declaredFields.firstOrNull { it.name == "outcome" },
        )
    }
}
