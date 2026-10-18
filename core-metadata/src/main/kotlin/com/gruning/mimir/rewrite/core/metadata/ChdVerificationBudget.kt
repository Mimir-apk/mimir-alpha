package com.gruning.mimir.rewrite.core.metadata

/**
 * One monotonic deadline shared across a CHD resolve: catalog header reads, enumeration, and every
 * verifier branch draw from the same remaining slice. A caller's absolute deadline clamps the default
 * aggregate; neither branch resets to the full window.
 */
internal class ChdVerificationBudget private constructor(
    private val nanoTime: () -> Long,
    private val deadlineNanos: Long,
) {
    /** Remaining nanoseconds until [deadlineNanos]; zero once elapsed or on subtraction overflow. */
    fun remainingNanos(): Long = MonotonicDeadline.remainingUntil(deadlineNanos, nanoTime())

    /** True when at least [MIN_ADMISSION_NANOS] remain — required before opening native holders. */
    fun hasAdmissionFloor(): Boolean = remainingNanos() >= MIN_ADMISSION_NANOS

    fun isExhausted(): Boolean = remainingNanos() == 0L

    companion object {
        const val DEFAULT_AGGREGATE_NANOS = 600L * 1_000_000_000L
        const val MIN_ADMISSION_NANOS = 5L * 1_000_000_000L

        fun forResolve(
            nanoTime: () -> Long = System::nanoTime,
            absoluteDeadlineNanos: Long? = null,
        ): ChdVerificationBudget {
            val now = nanoTime()
            val aggregateEnd = MonotonicDeadline.deadlineAfter(now, DEFAULT_AGGREGATE_NANOS)
            val end = when {
                absoluteDeadlineNanos != null && (
                    absoluteDeadlineNanos <= now ||
                        MonotonicDeadline.remainingUntil(absoluteDeadlineNanos, now) == 0L
                    ) -> now
                aggregateEnd <= now -> now
                absoluteDeadlineNanos != null && absoluteDeadlineNanos > now ->
                    minOf(absoluteDeadlineNanos, aggregateEnd)
                else -> aggregateEnd
            }
            return ChdVerificationBudget(nanoTime, end)
        }
    }
}
