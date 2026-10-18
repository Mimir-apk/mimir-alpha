package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicDeadlineTest {

    @Test
    fun `saturating add never extends beyond requested duration`() {
        assertEquals(Long.MAX_VALUE, MonotonicDeadline.saturatingAdd(Long.MAX_VALUE - 1, 2))
        assertEquals(Long.MIN_VALUE, MonotonicDeadline.saturatingAdd(Long.MIN_VALUE + 1, -2))
        assertEquals(15L, MonotonicDeadline.saturatingAdd(10L, 5L))
    }

    @Test
    fun `remaining until never mints allowance for expired extreme deadlines`() {
        assertEquals(0L, MonotonicDeadline.remainingUntil(Long.MIN_VALUE, 0L))
        assertEquals(0L, MonotonicDeadline.remainingUntil(0L, Long.MAX_VALUE))
        assertEquals(0L, MonotonicDeadline.remainingUntil(1L, Long.MAX_VALUE))
        assertEquals(0L, MonotonicDeadline.remainingUntil(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0L, MonotonicDeadline.remainingUntil(100L, 100L))
    }

    @Test
    fun `remaining until handles Long MIN MAX subtraction overflow conservatively`() {
        assertEquals(0L, MonotonicDeadline.remainingUntil(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(0L, MonotonicDeadline.remainingUntil(100L, Long.MIN_VALUE))
    }

    @Test
    fun `worker slice deadline saturates and refuses at Long MAX`() {
        val sliceNanos = 7L * 60L * 1_000_000_000L
        val startedAt = Long.MAX_VALUE - 1_000L
        val deadline = MonotonicDeadline.deadlineAfter(startedAt, sliceNanos)
        assertEquals(Long.MAX_VALUE, deadline)
        assertFalse(MonotonicDeadline.hasTimeRemaining(deadline, Long.MAX_VALUE))
        assertEquals(0L, MonotonicDeadline.remainingUntil(deadline, Long.MAX_VALUE))
    }

    @Test
    fun `has time remaining is false once deadline is reached`() {
        val deadline = 500L
        assertTrue(MonotonicDeadline.hasTimeRemaining(deadline, 100L))
        assertFalse(MonotonicDeadline.hasTimeRemaining(deadline, 500L))
        assertFalse(MonotonicDeadline.hasTimeRemaining(deadline, 600L))
    }
}
