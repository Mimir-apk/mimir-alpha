package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.jobs.JobProgressState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Pins [IdentityEnrichmentLiveProgress] independently of Compose/WorkManager.
 * A plain object over StateFlow makes hashing progress previewable and testable without a device.
 */
class IdentityEnrichmentLiveProgressTest {

    // The object is a real singleton across this whole test class (and JVM) -- reset it around every test
    // so none of these can see another test's state.
    @Before
    @After
    fun resetSingletonState() {
        IdentityEnrichmentLiveProgress.clear()
    }

    @Test
    fun `publish reports real intermediate values, floored at zero`() {
        IdentityEnrichmentLiveProgress.start(5)
        assertEquals(5, IdentityEnrichmentLiveProgress.remaining.value)
        IdentityEnrichmentLiveProgress.publish(2)
        assertEquals(3, IdentityEnrichmentLiveProgress.remaining.value)
        IdentityEnrichmentLiveProgress.publish(5)
        assertEquals(0, IdentityEnrichmentLiveProgress.remaining.value)
        IdentityEnrichmentLiveProgress.publish(9) // an over-count from a stale replay must never go negative
        assertEquals(0, IdentityEnrichmentLiveProgress.remaining.value)
    }

    @Test
    fun `an out-of-order smaller attempted count never moves remaining back up`() {
        // concurrency = 3 hashing threads can deliver attempted counts out of wall-clock order.
        IdentityEnrichmentLiveProgress.start(10)
        IdentityEnrichmentLiveProgress.publish(7)
        assertEquals(3, IdentityEnrichmentLiveProgress.remaining.value)
        IdentityEnrichmentLiveProgress.publish(4) // an earlier, smaller count lands after the later one
        assertEquals(3, IdentityEnrichmentLiveProgress.remaining.value)
    }

    @Test
    fun `zero or negative total reports no fraction rather than a fabricated denominator`() {
        IdentityEnrichmentLiveProgress.start(0)
        assertNull(IdentityEnrichmentLiveProgress.remaining.value)
        IdentityEnrichmentLiveProgress.start(-3)
        assertNull(IdentityEnrichmentLiveProgress.remaining.value)
    }

    @Test
    fun `clear drops the live signal so consumers fall back to the DB-derived count`() {
        IdentityEnrichmentLiveProgress.start(10)
        IdentityEnrichmentLiveProgress.publish(3)
        IdentityEnrichmentLiveProgress.clear()
        assertNull(IdentityEnrichmentLiveProgress.remaining.value)
    }

    @Test
    fun newerRunResetsAtomicSnapshotAndIgnoresStaleCallbacksAndClear() {
        val oldRun = IdentityEnrichmentLiveProgress.start(totalGames = 10)
        IdentityEnrichmentLiveProgress.publish(oldRun, attemptedSoFar = 8)
        val newRun = IdentityEnrichmentLiveProgress.start(totalGames = 20)

        IdentityEnrichmentLiveProgress.publish(oldRun, attemptedSoFar = 10)
        IdentityEnrichmentLiveProgress.clear(oldRun)
        assertEquals(
            IdentityEnrichmentLiveSnapshot(newRun, attempted = 0, total = 20),
            IdentityEnrichmentLiveProgress.progress.value,
        )

        IdentityEnrichmentLiveProgress.publish(newRun, attemptedSoFar = 1)
        assertEquals(
            IdentityEnrichmentLiveSnapshot(newRun, attempted = 1, total = 20),
            IdentityEnrichmentLiveProgress.progress.value,
        )
    }

    // ── the same object, now also on the shared channel ────────────────────────────

    @Test
    fun `starting a run puts one ENRICH row on the shared channel`() {
        IdentityEnrichmentLiveProgress.start(totalGames = 40)

        val row = JobProgressState.running.value.single { it.kind == JobKind.ENRICH }
        assertEquals("nothing attempted yet", 0, row.seen)
        assertEquals(40, row.total)
    }

    @Test
    fun `the shared row counts UP from the same settled value remaining counts down from`() {
        IdentityEnrichmentLiveProgress.start(totalGames = 40)
        IdentityEnrichmentLiveProgress.publish(attemptedSoFar = 12)

        assertEquals(28, IdentityEnrichmentLiveProgress.remaining.value)
        val row = JobProgressState.running.value.single { it.kind == JobKind.ENRICH }
        // Derived from the settled `remaining`, not recomputed from attemptedSoFar -- so the two can
        // never disagree, which is the whole reason it is mirrored rather than published twice.
        assertEquals(12, row.seen)
        assertEquals(40, row.total)
    }

    @Test
    fun `a late smaller attempted count cannot move the shared row backwards either`() {
        // The monotonic guarantee exists because concurrent hashing threads deliver out of order. If
        // the mirror re-derived its own value it would lose that, and a bar would jump backwards.
        IdentityEnrichmentLiveProgress.start(totalGames = 40)
        IdentityEnrichmentLiveProgress.publish(attemptedSoFar = 30)
        IdentityEnrichmentLiveProgress.publish(attemptedSoFar = 12)

        assertEquals(10, IdentityEnrichmentLiveProgress.remaining.value)
        assertEquals(30, JobProgressState.running.value.single { it.kind == JobKind.ENRICH }.seen)
    }

    @Test
    fun `clearing the run takes the shared row with it`() {
        IdentityEnrichmentLiveProgress.start(totalGames = 40)
        IdentityEnrichmentLiveProgress.clear()

        assertNull(IdentityEnrichmentLiveProgress.remaining.value)
        assertNull(
            "a row that outlives its worker is a lie",
            JobProgressState.running.value.firstOrNull { it.kind == JobKind.ENRICH },
        )
    }

    @Test
    fun `a run with nothing to do reports no row at all`() {
        // start(0) is the existing "no live signal" path. It must not put an empty bar on the channel.
        IdentityEnrichmentLiveProgress.start(totalGames = 0)

        assertNull(IdentityEnrichmentLiveProgress.remaining.value)
        assertNull(JobProgressState.running.value.firstOrNull { it.kind == JobKind.ENRICH })
    }
}
