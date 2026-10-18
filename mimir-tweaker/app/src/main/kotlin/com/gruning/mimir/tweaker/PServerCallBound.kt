package com.gruning.mimir.tweaker

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What one deadline-bounded PServer READ came back with. [TimedOut] is NOT "failed": the call may still be running
 * inside the service, so it means *unreadable right now*, and a caller must never treat it as an observed value.
 * [Refused] means the call was never sent.
 */
internal sealed interface PServerCallOutcome<out T> {
    data class Done<T>(val value: T) : PServerCallOutcome<T>
    data class Threw(val error: Throwable) : PServerCallOutcome<Nothing>
    data class TimedOut(val deadlineMs: Long) : PServerCallOutcome<Nothing>
    /** The wait was interrupted; the call may still be running, exactly like [TimedOut]. */
    data object Interrupted : PServerCallOutcome<Nothing>
    /** Too many earlier calls are still stuck past their own deadlines; this one was NOT sent. */
    data class Refused(val abandoned: Int) : PServerCallOutcome<Nothing>
}

/** Reported by the `Result`-shaped read paths when a call passed its deadline: it may still be running. */
internal class PServerTransactTimeoutException(val deadlineMs: Long) :
    java.util.concurrent.TimeoutException("PServer transact timed out after ${deadlineMs}ms; the read may still run (unreadable)")

/** Reported by the `Result`-shaped read paths when the call was refused unsent because earlier reads are stuck. */
internal class PServerCallsWedgedException(val abandoned: Int) :
    IllegalStateException("PServer has $abandoned reads stuck past their deadline; the read was not sent")

/**
 * A deadline for a PServer READ: a Binder `transact` cannot be cancelled from the client, and the vendor `pservice`
 * answers on one thread, so a single wedged `cat` used to hold its caller forever -- and the caller was usually the one
 * transaction worker every apply, restore and supervisor tick shares.
 *
 * **Reads only. A write is never given a deadline.** A write abandoned at a deadline keeps running on its helper
 * thread and may still land, so nothing the daemon read afterwards would be authoritative, the restore fence would be
 * released before the mutation finished, and a "timed-out write" could neither be called Stock nor be undone. A write
 * therefore runs inline on its caller and waits until `pservice` answers (see [PServerClient.executeChecked]); the
 * transaction worker's own bound (SERVICE_BUSY, ticks that skip and log) is what keeps the daemon live while one is slow.
 *
 * Shape follows TweakerSession's deadline pool rather than `BoundedRead`'s one-in-flight rule, because this client is
 * shared and healthy reads legitimately overlap (telemetry beside a fan monitor beside readback): every call gets its
 * own pooled daemon thread, the caller stops waiting AT the deadline, and what is capped is the number of reads
 * already abandoned at their deadlines ([maxAbandoned]). Past the cap a new read is [PServerCallOutcome.Refused]
 * unsent -- stacking more reads behind a wedge only deepens it. The cap clears itself as stuck reads return.
 *
 * **Exact counts.** A call's phase and the stuck count change together under one lock, so a call that finishes in the
 * instant its wait lapses is never counted as stuck, not even for a moment, and a concurrent caller can never be
 * refused unsent against a count that is one too high. The admission check reads that exact count. It is not a
 * reservation: calls that overlap while healthy are all admitted and may all time out together, so a burst can
 * overshoot the cap by at most the number of calls in it, and the cap then closes the door to the next one. Making it
 * a reservation would cap healthy concurrency instead, which this class exists not to do.
 *
 * **The callable owns everything it touches.** A caller that has left must never recycle a Parcel the abandoned thread
 * is still inside, so every Parcel is obtained, used and recycled inside [call] and only decoded values come back.
 * The abandoned thread is never interrupted.
 */
internal class PServerCallBound(
    private val defaultDeadlineMs: Long = READ_DEADLINE_MS,
    private val maxAbandoned: Int = MAX_ABANDONED_READS,
) {
    private val pool = ThreadPoolExecutor(
        0,
        Int.MAX_VALUE,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { runnable -> Thread(runnable, THREAD_NAME).apply { isDaemon = true } }

    /** One call's phase; read and written only under [lock]. */
    private class Phase {
        var value = PHASE_RUNNING
    }

    private val lock = Any()
    private var stuck = 0

    /** Reads abandoned and not yet returned. */
    fun abandonedCount(): Int = synchronized(lock) { stuck }

    fun <T> run(
        deadlineMs: Long = defaultDeadlineMs,
        call: () -> T,
    ): PServerCallOutcome<T> {
        synchronized(lock) {
            val already = stuck
            if (already >= maxAbandoned) return PServerCallOutcome.Refused(already)
        }
        val phase = Phase()
        val future = try {
            pool.submit(
                Callable {
                    try {
                        call()
                    } finally {
                        // Left behind at its deadline: this is the moment the abandoned call actually ends.
                        synchronized(lock) {
                            if (phase.value == PHASE_ABANDONED) stuck--
                            phase.value = PHASE_DONE
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            return PServerCallOutcome.Threw(error)
        }
        return try {
            PServerCallOutcome.Done(future.get(deadlineMs, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            abandon(phase, future, PServerCallOutcome.TimedOut(deadlineMs))
        } catch (error: ExecutionException) {
            PServerCallOutcome.Threw(error.cause ?: error)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            abandon(phase, future, PServerCallOutcome.Interrupted)
        } catch (error: Throwable) {
            PServerCallOutcome.Threw(error)
        }
    }

    /** Count the call as abandoned unless it finished in the instant since the wait ended, in which case take its answer. */
    private fun <T> abandon(
        phase: Phase,
        future: java.util.concurrent.Future<T>,
        left: PServerCallOutcome<Nothing>,
    ): PServerCallOutcome<T> {
        val abandonedNow = synchronized(lock) {
            if (phase.value == PHASE_RUNNING) {
                phase.value = PHASE_ABANDONED
                stuck++
                true
            } else {
                false
            }
        }
        if (abandonedNow) return left
        return try {
            PServerCallOutcome.Done(future.get())
        } catch (error: ExecutionException) {
            PServerCallOutcome.Threw(error.cause ?: error)
        } catch (error: Throwable) {
            PServerCallOutcome.Threw(error)
        }
    }

    companion object {
        /**
         * Longest a PServer READ (a sysfs `cat`, a receipt `printf`) is waited on. These take milliseconds; past this the
         * number is not worth holding a thread for, and it sits inside the Mimir client's own 2.5 s per-call bound.
         */
        const val READ_DEADLINE_MS = 2_000L

        /**
         * READS abandoned at their deadlines before new reads are refused unsent. Reads overlap freely (telemetry beside
         * a fan monitor beside readback) and are cheap to lose.
         */
        const val MAX_ABANDONED_READS = 8

        const val THREAD_NAME = "mimir-pserver-transact"
    }
}

private const val PHASE_RUNNING = 0
private const val PHASE_DONE = 1
private const val PHASE_ABANDONED = 2
