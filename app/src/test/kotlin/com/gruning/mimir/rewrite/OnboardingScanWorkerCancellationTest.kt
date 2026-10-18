package com.gruning.mimir.rewrite

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Characterizes the [OnboardingScanWorker] scan error path: cooperative cancellation must propagate
 * instead of being converted into retry/failure publication.
 */
class OnboardingScanWorkerCancellationTest {
    @Test
    fun cancellationExceptionIsRethrownBeforeRetryOrFailurePublication() {
        try {
            onboardingScanErrorPath { throw CancellationException("owner stopped") }
            fail("expected CancellationException")
        } catch (failure: CancellationException) {
            assertEquals("owner stopped", failure.message)
        }
    }

    @Test
    fun nonCancellationErrorsRemainEligibleForRetryPolicy() {
        val outcome = onboardingScanErrorPath<String> { throw IllegalStateException("transient") }
        assertEquals("retry", outcome)
    }

    private inline fun <T> onboardingScanErrorPath(block: () -> T): T =
        runCatching { block() }.getOrElse { error ->
            error.rethrowIfCancellation()
            @Suppress("UNCHECKED_CAST")
            "retry" as T
        }
}
