package com.gruning.mimir.rewrite

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityEnrichmentSweepPolicyTest {

    // `enrichmentBatchSize` and its pins were removed 2026-08-20: the per-file live progress probe
    // (IdentityEnrichmentLiveProgress, fed by LibraryIdentityEnricher's onGameAttempted callback) now
    // makes the on-screen fraction honest independent of batch/commit boundaries, so the dynamic
    // small-batch sizing this ticket shipped earlier the same day no longer earns its place -- see
    // IdentityEnrichmentWorker.kt's `enricher.enrich(...)` call, which now uses the enricher's own flat
    // default batch size again.

    @Test
    fun `p2 resident identity invalidates every pre-v11 keyset cursor stamp`() {
        assertTrue(IdentityEnrichmentWorker.identitySweepPolicyStamp.endsWith("|resident-key-p2"))
        assertNotEquals(
            com.gruning.mimir.rewrite.core.metadata.AthenaContainerIdentityPolicy.STAMP,
            IdentityEnrichmentWorker.identitySweepPolicyStamp,
        )
    }

    @Test
    fun `incomplete sweeps with unresolved rows keep retrying`() {
        assertTrue(enrichmentSweepShouldRetry(remaining = 5, exhausted = false, retryableThisRun = 5))
    }

    @Test
    fun `a complete sweep with only retryable rows finishes instead of looping`() {
        // A1D: a differential CHD whose parent the user has not attached stays PARENT_REQUIRED
        // retryable forever; a complete sweep must not spin WorkManager on it.
        assertFalse(enrichmentSweepShouldRetry(remaining = 5, exhausted = true, retryableThisRun = 5))
        // Deleted-mid-sweep retryable rows shrink the remainder below this run's retryable count.
        assertFalse(enrichmentSweepShouldRetry(remaining = 3, exhausted = true, retryableThisRun = 5))
    }

    @Test
    fun `rows inserted behind the cursor mid-sweep force one more full pass`() {
        // 5 unresolved but only 2 were attempted-and-retryable this run: 3 rows were never visited
        // (a source add landed behind the keyset cursor), so the worker retries rather than strand them.
        assertTrue(enrichmentSweepShouldRetry(remaining = 5, exhausted = true, retryableThisRun = 2))
    }

    @Test
    fun `skipped in-flight page after precheckpoint forces null cursor replay`() {
        // A checkpointed page whose resolves were cancelled before writes leaves rows behind the
        // cursor; tail exhaustion clears the cursor while remaining > retryableThisRun.
        assertTrue(enrichmentSweepShouldRetry(remaining = 3, exhausted = true, retryableThisRun = 1))
        assertFalse(enrichmentSweepShouldRetry(remaining = 3, exhausted = true, retryableThisRun = 3))
    }

    @Test
    fun `a drained library finishes regardless of sweep completeness`() {
        assertFalse(enrichmentSweepShouldRetry(remaining = 0, exhausted = true, retryableThisRun = 0))
        assertFalse(enrichmentSweepShouldRetry(remaining = 0, exhausted = false, retryableThisRun = 3))
    }

    @Test
    fun `stale publication refusal clears the durable keyset cursor before retry`() {
        assertTrue(identitySweepMustClearCursor(exhausted = false, restartFromBeginning = true))
        assertTrue(identitySweepMustClearCursor(exhausted = true, restartFromBeginning = false))
        assertFalse(identitySweepMustClearCursor(exhausted = false, restartFromBeginning = false))
    }

    @Test
    fun `source mutation enqueue retries without losing the revision`() = runBlocking {
        var attempts = 0
        val pauses = mutableListOf<Long>()

        deliverSourceMutationRevision(
            enqueue = {
                attempts++
                if (attempts < 3) error("transient WorkManager failure")
            },
            pause = pauses::add,
        )

        assertEquals(3, attempts)
        assertEquals(listOf(250L, 500L), pauses)
    }

    @Test
    fun `source mutation enqueue preserves cancellation`() = runBlocking {
        var paused = false
        var cancelled: Throwable? = null
        try {
            deliverSourceMutationRevision(
                enqueue = { throw CancellationException("owner stopped") },
                pause = { paused = true },
            )
        } catch (failure: CancellationException) {
            cancelled = failure
        }

        assertTrue(cancelled is CancellationException)
        assertFalse(paused)
    }

    @Test
    fun `source mutation enqueue retry backoff is bounded`() {
        assertEquals(250L, sourceMutationEnqueueRetryDelayMillis(0))
        assertEquals(8_000L, sourceMutationEnqueueRetryDelayMillis(5))
        assertEquals(8_000L, sourceMutationEnqueueRetryDelayMillis(Int.MAX_VALUE))
    }
}
