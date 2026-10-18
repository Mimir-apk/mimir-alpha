package com.gruning.mimir.rewrite.core.resolver.library

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.channels.BufferOverflow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RG Rotate measurement (2026-09-28): identification wrote `games` many times a second, and every write
 * re-ran the favourites picker's 5,500-row read. The throttle lets a burst cost one re-read.
 */
class AtMostEveryTest {

    @Test
    fun `the first value passes at once and a burst behind it folds into one`() = runTest {
        val seen = mutableListOf<Pair<Long, Int>>()
        // 100 writes over one second, into a conflated hand-off, as Room's invalidation channel is.
        flow { repeat(100) { emit(it); delay(10) } }
            .buffer(1, BufferOverflow.DROP_OLDEST)
            .atMostEvery(1_500)
            .collect { seen += currentTime to it }
        assertEquals(listOf(0L to 0, 1_500L to 99), seen)
    }

    @Test
    fun `values are never closer together than the period`() = runTest {
        val times = mutableListOf<Long>()
        flowOf(1, 2, 3).atMostEvery(1_500).collect { times += currentTime }
        assertEquals(listOf(0L, 1_500L, 3_000L), times)
    }

    @Test
    fun `the upstream waits while the collector sleeps, so it does no work it would throw away`() = runTest {
        var produced = 0
        val seen = mutableListOf<Int>()
        flow { repeat(3) { produced++; emit(it) } }
            .atMostEvery(1_500)
            .collect { seen += it; if (it == 0) assertEquals(1, produced) }
        assertEquals(listOf(0, 1, 2), seen)
    }
}
