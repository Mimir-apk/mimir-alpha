package com.gruning.mimir.rewrite.core.metadata

/**
 * Overflow-safe monotonic deadline arithmetic shared by CHD resolve budgets and worker slice caps.
 * Subtraction overflow or a backwards/invalid deadline must never mint fresh allowance; saturating
 * addition may refuse early but never extends beyond the requested duration.
 */
object MonotonicDeadline {
    /** [left] + [right], saturating at [Long.MAX_VALUE] / [Long.MIN_VALUE] on signed overflow. */
    fun saturatingAdd(left: Long, right: Long): Long {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE
        return left + right
    }

    /** Nanoseconds from [nowNanos] until [deadlineNanos]; zero once elapsed or on overflow. */
    fun remainingUntil(deadlineNanos: Long, nowNanos: Long): Long {
        if (deadlineNanos <= nowNanos) return 0L
        val remaining = deadlineNanos - nowNanos
        return if (remaining <= 0L) 0L else remaining
    }

    fun deadlineAfter(startNanos: Long, durationNanos: Long): Long = saturatingAdd(startNanos, durationNanos)

    fun hasTimeRemaining(deadlineNanos: Long, nowNanos: Long): Boolean =
        remainingUntil(deadlineNanos, nowNanos) > 0L
}
