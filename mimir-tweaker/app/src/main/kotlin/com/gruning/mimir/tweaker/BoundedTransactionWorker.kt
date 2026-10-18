package com.gruning.mimir.tweaker

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The wire code a caller sees when the transaction worker did not answer in time; the work was or may still be running. */
internal const val SERVICE_BUSY_CODE = "SERVICE_BUSY"

/**
 * The transaction worker did not hand a caller its answer inside the caller's wait. Nothing about the hardware is
 * claimed: [started] false means the block never began and never will (a stale apply must not run minutes later), true
 * means it is, or was, running and may still finish, so the caller must treat its effect as UNCONFIRMED.
 *
 * An [IllegalStateException] on purpose: an uncaught one that reaches a Binder stub is marshalled to the client, which
 * reads it as "no answer"; a bare checked or unlisted exception would take the daemon process down.
 */
internal class TransactionWorkerBusyException(
    message: String,
    val started: Boolean,
) : IllegalStateException(message)

/** [SERVICE_BUSY_CODE] when the worker timed out, otherwise [fallback]: the one place a failure's wire code is chosen. */
internal fun transactionWorkerFailureCode(error: Throwable, fallback: String = "SERVICE_ERROR"): String =
    if (error is TransactionWorkerBusyException) SERVICE_BUSY_CODE else fallback

/**
 * A deadline for waiting on the one transaction worker, which used to be an unbounded `Future.get()`.
 *
 * Every Binder method, every supervisor tick and every post-control tick shares that single thread, and each waited on
 * it forever, so one wedged command stalled all of them at once, thermal supervision included. Now every caller stops
 * waiting at [call]'s `waitMs`:
 * - A Binder caller gets a [TransactionWorkerBusyException] (the service maps it to `SERVICE_BUSY`).
 * - A periodic tick passes a [skipKey]: while its previous call is still queued or running (its wait having lapsed) the
 *   next tick is skipped at once, without enqueuing, so a wedge costs one stale entry and never a pile of them, and
 *   the tick's thread keeps running to log it.
 * - A block that has not started when its caller gives up is withdrawn and never runs. One that already started is left
 *   alone: interrupting a hardware transaction half way settles nothing.
 * - An interrupted caller is a caller that gave up: it is handled exactly like a lapsed wait (withdrawn if the block never
 *   started, left to late settlement if it did) and its interrupt flag is restored, never swallowed. A caller that is
 *   ALREADY interrupted when it calls is a lapse at once, unless the flag is a stale one this worker itself handed back
 *   on that thread earlier (tracked per thread), which is cleared and the call proceeds.
 *
 * [workCount] counts work that is queued or running and is released by the block's own end, never by the caller's
 * departure, so terminal settlement cannot decide the service is idle while a mutation is still in flight.
 *
 * **Settlement after a caller has left.** A caller that gave up on a block that was already running tells its client
 * `SERVICE_BUSY` and must NOT then settle that block's effect from its own thread: the block is still mutating the
 * supervisor and the journal on the worker. It passes `onLateFinish` instead, and exactly one of two things happens,
 * decided by one compare-and-set on the call's state: the block ends first (the caller takes the answer and settles it
 * itself), or the caller leaves first (the worker runs `onLateFinish` with the block's real outcome, on the worker
 * thread, strictly after the block and before the call stops counting as work).
 */
internal class BoundedTransactionWorker(
    private val executor: ExecutorService,
    private val workCount: AtomicInteger,
    private val workerThreadName: String,
    private val onSettled: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()
    private val skipped = ConcurrentHashMap<String, AtomicInteger>()

    /** Set on a thread when THIS worker re-asserted that thread's interrupt flag; see the entry check in [call]. */
    private val restoredByUs = ThreadLocal<Boolean>()

    @Suppress("UNCHECKED_CAST")
    fun <T> call(
        waitMs: Long,
        skipKey: String?,
        onLateFinish: ((Result<T>) -> Unit)? = null,
        block: () -> T,
    ): T {
        // Already on the worker (a nested call): waiting on itself could only deadlock.
        if (Thread.currentThread().name == workerThreadName) return block()
        // A flag already standing on entry is one of two things, told apart by who set it. If THIS worker handed it back
        // on an earlier lapse on this thread ([restoredByUs]), it is stale: that cancellation was already honoured, and
        // leaving it would make `future.get` throw at once and abandon healthy work on a pooled Binder thread, so both
        // are cleared and the call proceeds. A flag nobody here set is a FRESH cancellation of THIS request: it is
        // honoured as a lapse (the block never starts) and left standing, now marked ours, so the next request on the
        // thread is not cut by it. Limitation: an external interrupt landing on top of our own restored flag, before the
        // next call, is indistinguishable from it and is treated as stale (rare, and the caller's wait is the real guard).
        val callerThread = Thread.currentThread()
        if (callerThread.isInterrupted) {
            if (restoredByUs.get() == true) {
                Thread.interrupted()
                restoredByUs.remove()
            } else {
                restoredByUs.set(true)
                val caller = skipKey ?: "binder"
                log("TX_WORKER_BUSY caller=$caller waitedMs=$waitMs started=false action=WITHDRAWN interrupted=true entry=true")
                throw TransactionWorkerBusyException(
                    "$SERVICE_BUSY_CODE: the caller was interrupted before the transaction worker was asked; nothing was started",
                    started = false,
                )
            }
        } else if (restoredByUs.get() == true) {
            // Somebody already cleared the flag we handed back: the marker no longer describes anything.
            restoredByUs.remove()
        }
        val flag = skipKey?.let { inFlight.getOrPut(it) { AtomicBoolean(false) } }
        if (flag != null && !flag.compareAndSet(false, true)) {
            noteSkip(skipKey)
            throw TransactionWorkerBusyException(
                "$SERVICE_BUSY_CODE: the previous $skipKey call is still on the transaction worker; this one is skipped",
                started = true,
            )
        }
        val state = AtomicInteger(QUEUED)
        workCount.incrementAndGet()
        val future = try {
            executor.submit(
                Callable<Any?> {
                    // The caller left while this was still queued: it was never admitted, so it never runs.
                    if (!state.compareAndSet(QUEUED, RUNNING)) return@Callable null
                    val outcome = try {
                        Result.success(block())
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                    try {
                        // The caller left while the block ran: it already answered SERVICE_BUSY and will never
                        // settle, so the settlement runs here, on the worker, after the block and still counted.
                        if (!state.compareAndSet(RUNNING, FINISHED) && onLateFinish != null) {
                            try {
                                onLateFinish(outcome)
                            } catch (error: Throwable) {
                                log("TX_WORKER_BUSY caller=${skipKey ?: "binder"} action=LATE_SETTLEMENT_FAILED error=$error")
                            }
                        }
                    } finally {
                        flag?.set(false)
                        workCount.decrementAndGet()
                        onSettled()
                    }
                    outcome.getOrThrow()
                },
            )
        } catch (error: Throwable) {
            flag?.set(false)
            workCount.decrementAndGet()
            onSettled()
            throw error
        }
        // A timeout and an interrupt are the same fact to the worker: the caller will not wait any longer. Both hand the
        // call over the same way (withdraw it if it never started, leave it to late settlement if it is running), and
        // an interrupt must never let the caller settle a block that can still run.
        var interrupted = false
        try {
            val answer = future.get(waitMs, TimeUnit.MILLISECONDS) as T
            skipKey?.let { skipped[it]?.set(0) }
            return answer
        } catch (_: TimeoutException) {
            // handed over below
        } catch (_: InterruptedException) {
            interrupted = true
        }
        val caller = skipKey ?: "binder"
        val reason = if (interrupted) "was interrupted" else "waited ${waitMs}ms"
        try {
            if (state.compareAndSet(QUEUED, ABANDONED)) {
                future.cancel(false)
                flag?.set(false)
                workCount.decrementAndGet()
                onSettled()
                log("TX_WORKER_BUSY caller=$caller waitedMs=$waitMs started=false action=WITHDRAWN interrupted=$interrupted")
                throw TransactionWorkerBusyException(
                    "$SERVICE_BUSY_CODE: the transaction worker did not start this call (the caller $reason); it was withdrawn",
                    started = false,
                )
            }
            if (!state.compareAndSet(RUNNING, LEFT_RUNNING)) {
                // The block ended in the instant since the wait did, and has already decided it was not left: what
                // remains on the worker is only bookkeeping that cannot block, so its answer is taken, not dropped.
                val answer = takeFinishedAnswer(future) as T
                skipKey?.let { skipped[it]?.set(0) }
                return answer
            }
            log("TX_WORKER_BUSY caller=$caller waitedMs=$waitMs started=true action=LEFT_RUNNING interrupted=$interrupted")
            throw TransactionWorkerBusyException(
                "$SERVICE_BUSY_CODE: the transaction worker was still running this call (the caller $reason); its effect is unconfirmed",
                started = true,
            )
        } finally {
            // The interrupt was this thread's own cancellation signal; hand it back rather than swallow it.
            if (interrupted) handBackInterrupt()
        }
    }

    /** Re-asserts the caller's interrupt flag and records that THIS worker did, so the thread's next call can tell it from a fresh one. */
    private fun handBackInterrupt() {
        restoredByUs.set(true)
        Thread.currentThread().interrupt()
    }

    /** The block has ended and only cannot-block bookkeeping remains; an interrupt landing in that sliver is kept, not lost. */
    private fun takeFinishedAnswer(future: java.util.concurrent.Future<*>): Any? {
        var interrupted = false
        try {
            while (true) {
                try {
                    return future.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) handBackInterrupt()
        }
    }

    /** Logs the first skip and every [SKIP_LOG_EVERY]th after it, so a long wedge is visible without a line per tick. */
    private fun noteSkip(key: String) {
        val count = skipped.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        if (count == 1 || count % SKIP_LOG_EVERY == 0) {
            log("TX_WORKER_BUSY caller=$key action=TICK_SKIPPED skipped=$count")
        }
    }

    private companion object {
        const val QUEUED = 0
        const val RUNNING = 1
        const val ABANDONED = 2
        /** The block ended while its caller was still waiting (or in the instant after): the caller owns the answer. */
        const val FINISHED = 3
        /** The caller left a running block: the worker owns the late settlement. */
        const val LEFT_RUNNING = 4
        const val SKIP_LOG_EVERY = 20
    }
}
