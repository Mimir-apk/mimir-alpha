package com.gruning.mimir.rewrite.core.resolver.capacity

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class Ep03ResourceSample<T>(
    val result: T,
    val peakHeapBytes: Long,
    val elapsedMillis: Long,
)

internal object Ep03ResourceSampler {
    suspend fun <T> measure(block: suspend () -> T): Ep03ResourceSample<T> = coroutineScope {
        val peakHeap = AtomicLong(usedHeapBytes())
        val startedNanos = System.nanoTime()
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                peakHeap.accumulateAndGet(usedHeapBytes(), ::maxOf)
                delay(10)
            }
        }
        try {
            val result = block()
            peakHeap.accumulateAndGet(usedHeapBytes(), ::maxOf)
            Ep03ResourceSample(
                result = result,
                peakHeapBytes = peakHeap.get(),
                elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L,
            )
        } finally {
            sampler.cancelAndJoin()
        }
    }

    fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
