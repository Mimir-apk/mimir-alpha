package com.gruning.mimir.rewrite.core.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * How one piece of blocking work that may never return ended: [Done] with its value, [Threw] with what it threw, [TimedOut] when it did not finish in time and was abandoned,
 * [Refused] when too many earlier pieces are still stuck for another to be started.
 */
internal sealed interface AbandonableOutcome<out T> {
    data class Done<T>(val value: T) : AbandonableOutcome<T>

    data class Threw(val error: Throwable) : AbandonableOutcome<Nothing>

    data object TimedOut : AbandonableOutcome<Nothing>

    data object Refused : AbandonableOutcome<Nothing>
}

/**
 * Blocking work that a hostile file can hang for ever: `MediaMetadataRetriever` makes a native call that no coroutine cancellation and no thread interrupt reaches, so a
 * crafted container (or a provider that never answers) would hold its caller's slot, and with it every later caller, until the process dies.
 *
 * [run] puts the work on a thread of its own and waits [timeoutMs] for it. If it has not finished by then the caller moves on with [AbandonableOutcome.TimedOut] and its slot
 * is free again; **the thread cannot be stopped**, so it is left to finish whenever (if ever) the native call returns, its result thrown away. That leak is bounded: the
 * threads still stuck are counted ([stuck]) and, from [MaxStuck] of them, no new work is started ([AbandonableOutcome.Refused]) until one returns. The threads are
 * daemons on a pool of their own, so a stuck one neither keeps the process alive nor takes one of `Dispatchers.IO`'s (which the rest of the launcher shares).
 */
internal object AbandonableWork {
    /** How many abandoned pieces may still be running before no more are started: a folder of hostile files costs this many threads, not one per file. */
    const val MaxStuck = 4

    private val abandoned = AtomicInteger(0)
    private val ids = AtomicInteger(0)

    private val pool = ThreadPoolExecutor(
        0,
        Int.MAX_VALUE,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { task -> Thread(task, "mimir-music-io-" + ids.incrementAndGet()).apply { isDaemon = true } },
    )

    /** How many abandoned pieces are still running (blocked in a call that has not returned). */
    val stuck: Int get() = abandoned.get()

    /** Runs [block] on its own thread and waits at most [timeoutMs] for it. See the class note for what a timeout leaves behind. */
    suspend fun <T> run(timeoutMs: Long, block: () -> T): AbandonableOutcome<T> {
        if (abandoned.get() >= MaxStuck) return AbandonableOutcome.Refused
        val result = CompletableDeferred<Result<T>>()
        // 0 running, 1 abandoned by its caller, 2 finished. Whoever moves it off 0 first decides who does the bookkeeping.
        val state = AtomicInteger(0)
        try {
            pool.execute {
                val outcome = runCatching(block)
                // Abandoned already: nobody is waiting, the thread is free again, so it stops counting as stuck.
                if (!state.compareAndSet(0, 2)) abandoned.decrementAndGet()
                result.complete(outcome)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return AbandonableOutcome.Refused
        }
        val finished = try {
            withTimeoutOrNull(timeoutMs) { result.await() }
        } catch (cancelled: CancellationException) {
            // The caller moved on: the work is abandoned all the same, and still counts until it returns.
            abandon(state)
            throw cancelled
        }
        if (finished == null) {
            // The count goes up BEFORE the state moves, so the thread's decrement (after it sees "abandoned") can never run first.
            abandoned.incrementAndGet()
            if (!state.compareAndSet(0, 1)) {
                // It finished in the instant between the timeout and here: take its answer.
                abandoned.decrementAndGet()
                return outcomeOf(result.await())
            }
            return AbandonableOutcome.TimedOut
        }
        return outcomeOf(finished)
    }

    private fun abandon(state: AtomicInteger) {
        abandoned.incrementAndGet()
        if (!state.compareAndSet(0, 1)) abandoned.decrementAndGet()
    }

    private fun <T> outcomeOf(result: Result<T>): AbandonableOutcome<T> =
        result.fold({ AbandonableOutcome.Done(it) }, { AbandonableOutcome.Threw(it) })
}

/**
 * A mutex that gives up waiting: [withinGate] runs [block] holding the gate, or returns null without running it when the gate stayed taken for [waitMs]. The art decoder's
 * gate: one decode at a time, and a caller behind a decode that hung shows no art rather than wait for ever.
 */
internal class BoundedGate {
    private val mutex = Mutex()

    /** Whether the gate is taken right now. For tests. */
    val isTaken: Boolean get() = mutex.isLocked

    suspend fun <T> withinGate(waitMs: Long, block: suspend () -> T): Gated<T> {
        val got = withTimeoutOrNull(waitMs) { mutex.lock(); true } ?: return Gated.GaveUp
        try {
            return Gated.Ran(block())
        } finally {
            mutex.unlock()
        }
    }

    sealed interface Gated<out T> {
        data class Ran<T>(val value: T) : Gated<T>

        data object GaveUp : Gated<Nothing>
    }
}
