package com.gruning.mimir.tweaker

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3: the transaction worker used to be waited on with an unbounded `Future.get()`, so one stuck call stalled every
 * Binder method and every supervisor tick at once. Now a caller stops waiting (a Binder caller gets SERVICE_BUSY, a
 * tick skips and logs), a block that never started is withdrawn, and the in-flight count follows the BLOCK, not the
 * caller, so terminal settlement cannot call the service idle while a mutation is still running.
 */
class BoundedTransactionWorkerTest {
    private val workerName = "tx-test-worker"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, workerName).apply { isDaemon = true }
    }
    private val count = AtomicInteger(0)
    private val settled = AtomicInteger(0)
    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val latches = mutableListOf<CountDownLatch>()
    private val worker = BoundedTransactionWorker(
        executor = executor,
        workCount = count,
        workerThreadName = workerName,
        onSettled = { settled.incrementAndGet() },
        log = { logs += it },
    )

    private fun latch(): CountDownLatch = CountDownLatch(1).also(latches::add)

    @After
    fun tearDown() {
        latches.forEach(CountDownLatch::countDown)
        executor.shutdownNow()
    }

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /** Parks the one worker thread inside a block until [release] opens; returns once it is really running. */
    private fun wedgeWorker(release: CountDownLatch): Thread {
        val running = latch()
        val caller = Thread {
            runCatching {
                worker.call(waitMs = 60_000, skipKey = null) {
                    running.countDown()
                    release.await()
                }
            }
        }.apply { isDaemon = true; start() }
        assertTrue(running.await(3, TimeUnit.SECONDS))
        return caller
    }

    @Test
    fun `a call that finishes returns its value and releases the count`() {
        val value = worker.call(waitMs = 1_000, skipKey = null) { "answer" }

        assertEquals("answer", value)
        assertEquals(0, count.get())
        assertTrue("a finished block asks for terminal settlement", settled.get() >= 1)
    }

    @Test
    fun `a queued call that is not reached in time is withdrawn and never runs`() {
        val release = latch()
        wedgeWorker(release)
        val ran = AtomicBoolean(false)
        val started = System.nanoTime()

        val busy = runCatching {
            worker.call(waitMs = 100, skipKey = null) { ran.set(true) }
        }.exceptionOrNull()

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(busy is TransactionWorkerBusyException)
        assertFalse("never admitted, so nothing may have been written", (busy as TransactionWorkerBusyException).started)
        assertTrue(busy.message!!.startsWith(SERVICE_BUSY_CODE))
        assertTrue("gave up after ${elapsedMs}ms", elapsedMs < 2_000)

        release.countDown()
        assertTrue(awaitUntil { count.get() == 0 })
        Thread.sleep(50)
        assertFalse("a withdrawn block must not run after the wedge clears", ran.get())
    }

    @Test
    fun `a started call that outlives the wait is unconfirmed and stays counted until it ends`() {
        val release = latch()
        val running = latch()

        val busy = runCatching {
            worker.call(waitMs = 500, skipKey = null) {
                running.countDown()
                release.await()
            }
        }.exceptionOrNull()

        assertTrue(busy is TransactionWorkerBusyException)
        assertTrue("it had started, so its effect is unconfirmed", (busy as TransactionWorkerBusyException).started)
        assertEquals(
            "the caller left, but the mutation is still running: not idle",
            1,
            count.get(),
        )

        release.countDown()
        assertTrue(awaitUntil { count.get() == 0 })
        assertTrue(awaitUntil { settled.get() >= 1 })
    }

    // Worker settlement race: a caller that gives up on a RUNNING apply used to settle it from its own (Binder) thread
    // while the block was still mutating supervisor and journal state on the worker.
    @Test
    fun `a caller that leaves a running block never settles it, the worker does after the block`() {
        val release = latch()
        val running = latch()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val settledOn = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val countDuringSettlement = AtomicInteger(-1)

        val busy = runCatching {
            worker.call(
                waitMs = 200,
                skipKey = null,
                onLateFinish = { late: Result<String> ->
                    settledOn.set(Thread.currentThread().name)
                    countDuringSettlement.set(count.get())
                    events += "settle:${late.getOrNull()}"
                },
            ) {
                events += "block-start"
                running.countDown()
                release.await()
                events += "block-end"
                "real-result"
            }
        }.exceptionOrNull()

        assertTrue(busy is TransactionWorkerBusyException)
        assertTrue((busy as TransactionWorkerBusyException).started)
        assertEquals("the caller has left; nothing has settled while the block runs", listOf("block-start"), events.toList())

        release.countDown()
        assertTrue(awaitUntil { count.get() == 0 })

        assertEquals(listOf("block-start", "block-end", "settle:real-result"), events.toList())
        assertEquals("settlement runs on the worker, never on the caller's thread", workerName, settledOn.get())
        assertEquals("the call still counts as work while it settles", 1, countDuringSettlement.get())
    }

    @Test
    fun `a block that fails after its caller left hands the failure to the late settlement`() {
        val release = latch()
        val running = latch()
        val lateFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val boom = IllegalStateException("late boom")

        runCatching {
            worker.call(
                waitMs = 200,
                skipKey = null,
                onLateFinish = { late: Result<Unit> -> lateFailure.set(late.exceptionOrNull()) },
            ) {
                running.countDown()
                release.await()
                throw boom
            }
        }
        release.countDown()

        assertTrue(awaitUntil { count.get() == 0 })
        assertSame(boom, lateFailure.get())
    }

    @Test
    fun `a caller that got its answer settles it itself and the late settlement never runs`() {
        val late = AtomicInteger(0)

        val value = worker.call(waitMs = 1_000, skipKey = null, onLateFinish = { _: Result<String> -> late.incrementAndGet() }) {
            "answer"
        }

        assertEquals("answer", value)
        assertTrue(awaitUntil { count.get() == 0 })
        assertEquals(0, late.get())
    }

    @Test
    fun `a withdrawn call that never ran has no late settlement`() {
        val release = latch()
        wedgeWorker(release)
        val late = AtomicInteger(0)

        val busy = runCatching {
            worker.call(waitMs = 100, skipKey = null, onLateFinish = { _: Result<Unit> -> late.incrementAndGet() }) {}
        }.exceptionOrNull()
        release.countDown()
        assertTrue(awaitUntil { count.get() == 0 })

        assertFalse((busy as TransactionWorkerBusyException).started)
        assertEquals(0, late.get())
    }

    @Test
    fun `when the wait lapses as the block ends exactly one side settles`() {
        repeat(150) {
            val caller = AtomicInteger(0)
            val late = AtomicInteger(0)
            val outcome = runCatching {
                worker.call(waitMs = 4, skipKey = null, onLateFinish = { _: Result<Int> -> late.incrementAndGet() }) {
                    Thread.sleep(4)
                    1
                }
            }
            if (outcome.isSuccess) caller.incrementAndGet()
            // The late settlement runs before the call stops counting as work, so a zero count means it has run.
            assertTrue(awaitUntil { count.get() == 0 })
            val withdrawn = (outcome.exceptionOrNull() as? TransactionWorkerBusyException)?.started == false
            if (!withdrawn) {
                assertEquals("iteration $it: the answer was settled twice or not at all", 1, caller.get() + late.get())
            }
        }
    }

    /** How an interrupted caller's call ended, and whether its thread still carried the interrupt afterwards. */
    private class InterruptedCaller {
        val outcome = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val flagAfter = AtomicBoolean(false)
        val done = CountDownLatch(1)
        @Volatile var interruptSent = false
    }

    /** Runs [call] on its own thread, interrupts that thread once [armed] holds, and reports how the call ended. */
    private fun callThenInterrupt(armed: () -> Boolean, call: () -> Unit): InterruptedCaller {
        val result = InterruptedCaller()
        val thread = Thread {
            try {
                call()
            } catch (error: Throwable) {
                result.outcome.set(error)
            }
            // Wait (without a blocking call an interrupt would break) for the interrupt to have been sent, so the flag
            // is read after it whichever side of the call it landed on.
            while (!result.interruptSent) Thread.onSpinWait()
            result.flagAfter.set(Thread.currentThread().isInterrupted)
            result.done.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(awaitUntil(condition = armed))
        thread.interrupt()
        result.interruptSent = true
        assertTrue("the interrupted caller returned", result.done.await(3, TimeUnit.SECONDS))
        return result
    }

    // S3 closing review #8: only a lapsed wait handed work over, so an interrupted waiter left it QUEUED or RUNNING with
    // no late settlement, and V4 settled it on the caller.
    @Test
    fun `an interrupted caller of a queued call withdraws it and the call never runs`() {
        val release = latch()
        wedgeWorker(release)
        val ran = AtomicBoolean(false)
        val late = AtomicInteger(0)

        // count 2 = the wedge plus this call, queued behind it.
        val caller = callThenInterrupt(armed = { count.get() == 2 }) {
            worker.call(waitMs = 60_000, skipKey = null, onLateFinish = { _: Result<Unit> -> late.incrementAndGet() }) {
                ran.set(true)
            }
        }
        assertEquals("withdrawn at once: only the wedge is still counted", 1, count.get())
        release.countDown()
        assertTrue(awaitUntil { count.get() == 0 })
        Thread.sleep(50)

        val busy = caller.outcome.get()
        assertTrue("an interrupt is a departure: $busy", busy is TransactionWorkerBusyException)
        assertFalse("it never started, so it was withdrawn", (busy as TransactionWorkerBusyException).started)
        assertTrue("the caller's interrupt flag is handed back", caller.flagAfter.get())
        assertFalse("a withdrawn block must not run after the wedge clears", ran.get())
        assertEquals("a withdrawn call has no late settlement", 0, late.get())
    }

    @Test
    fun `an interrupted caller of a running call leaves it to the worker's late settlement exactly once`() {
        val release = latch()
        val running = latch()
        val settledOn = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val lateCalls = AtomicInteger(0)
        val lateValue = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val callerSettled = AtomicBoolean(false)

        val caller = callThenInterrupt(armed = { running.count == 0L }) {
            worker.call(
                waitMs = 60_000,
                skipKey = null,
                onLateFinish = { late: Result<String> ->
                    lateCalls.incrementAndGet()
                    settledOn.set(Thread.currentThread().name)
                    lateValue.set(late.getOrNull())
                },
            ) {
                running.countDown()
                release.await()
                "real-result"
            }
            callerSettled.set(true) // reached only if the caller took an answer
        }

        val busy = caller.outcome.get()
        assertTrue("an interrupt is a departure: $busy", busy is TransactionWorkerBusyException)
        assertTrue("it was running: its effect is unconfirmed", (busy as TransactionWorkerBusyException).started)
        assertTrue("the caller's interrupt flag is handed back", caller.flagAfter.get())
        assertFalse(callerSettled.get())
        assertEquals("still running, still counted, nothing settled yet", 1, count.get())
        assertEquals(0, lateCalls.get())

        release.countDown()
        assertTrue(awaitUntil { count.get() == 0 })

        assertEquals(1, lateCalls.get())
        assertEquals("real-result", lateValue.get())
        assertEquals(workerName, settledOn.get())
    }

    @Test
    fun `an interrupt that lands as the block ends is settled by exactly one side and never lost`() {
        repeat(100) { iteration ->
            val callerSettled = AtomicInteger(0)
            val late = AtomicInteger(0)
            val started = latch()
            val caller = callThenInterrupt(armed = { started.count == 0L }) {
                worker.call(waitMs = 60_000, skipKey = null, onLateFinish = { _: Result<Int> -> late.incrementAndGet() }) {
                    started.countDown()
                    Thread.sleep(2)
                    1
                }
                callerSettled.incrementAndGet()
            }
            assertTrue(awaitUntil { count.get() == 0 })
            val busy = caller.outcome.get() as? TransactionWorkerBusyException
            assertEquals(
                "iteration $iteration: the answer was settled twice or not at all",
                1,
                callerSettled.get() + late.get(),
            )
            assertTrue("iteration $iteration: the interrupt must not be swallowed", caller.flagAfter.get())
            if (busy != null) assertTrue(busy.started)
        }
    }

    // S9 (c), second round: the entry check used to clear ANY standing flag, which also discarded a FRESH cancellation that
    // landed before the call. Only a flag this worker itself handed back on that thread is stale.
    @Test
    fun `an interrupt that arrives before the call is a lapse, the block never runs and the flag stays standing`() {
        val outcome = java.util.concurrent.atomic.AtomicReference<Any?>(null)
        val ran = AtomicBoolean(false)
        val flagAfter = AtomicBoolean(false)
        val done = CountDownLatch(1)
        Thread {
            Thread.currentThread().interrupt() // a FRESH cancellation: nothing here ever handed this flag back
            outcome.set(runCatching { worker.call(waitMs = 5_000, skipKey = null) { ran.set(true); 42 } }.let { it.getOrNull() ?: it.exceptionOrNull() })
            flagAfter.set(Thread.currentThread().isInterrupted)
            done.countDown()
        }.apply { isDaemon = true; start() }

        assertTrue(done.await(3, TimeUnit.SECONDS))
        val busy = outcome.get()
        assertTrue("a fresh interrupt before entry must lapse the call: $busy", busy is TransactionWorkerBusyException)
        assertFalse("the block never started", (busy as TransactionWorkerBusyException).started)
        assertFalse("and never runs", ran.get())
        assertTrue("the flag is handed back, not swallowed", flagAfter.get())
        assertEquals("nothing was queued", 0, count.get())
    }

    @Test
    fun `a flag this worker handed back is stale, so the threads next call clears it and proceeds`() {
        val release = latch()
        val running = latch()
        val first = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val second = java.util.concurrent.atomic.AtomicReference<Any?>(null)
        val flagAfterSecond = AtomicBoolean(true)
        val done = CountDownLatch(1)
        val thread = Thread {
            first.set(
                runCatching {
                    worker.call(waitMs = 60_000, skipKey = null) {
                        running.countDown()
                        release.await()
                    }
                }.exceptionOrNull(),
            )
            second.set(runCatching { worker.call(waitMs = 5_000, skipKey = null) { 42 } }.let { it.getOrNull() ?: it.exceptionOrNull() })
            flagAfterSecond.set(Thread.currentThread().isInterrupted)
            done.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(running.await(3, TimeUnit.SECONDS))
        thread.interrupt()
        assertTrue(awaitUntil { first.get() != null })
        release.countDown()

        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(first.get() is TransactionWorkerBusyException)
        assertEquals("our own stale flag must not abandon healthy work", 42, second.get())
        assertFalse("and it is cleared once recognised", flagAfterSecond.get())
    }

    @Test
    fun `a fresh interrupt that was honoured and handed back does not cut the thread's next request either`() {
        val first = java.util.concurrent.atomic.AtomicReference<Any?>(null)
        val second = java.util.concurrent.atomic.AtomicReference<Any?>(null)
        val done = CountDownLatch(1)
        Thread {
            Thread.currentThread().interrupt()
            first.set(runCatching { worker.call(waitMs = 5_000, skipKey = null) { 1 } }.exceptionOrNull())
            second.set(runCatching { worker.call(waitMs = 5_000, skipKey = null) { 2 } }.let { it.getOrNull() ?: it.exceptionOrNull() })
            done.countDown()
        }.apply { isDaemon = true; start() }

        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(first.get() is TransactionWorkerBusyException)
        assertEquals(2, second.get())
    }

    @Test
    fun `a call that lapsed by interrupt hands the flag back, and the same thread's next call still gets its answer`() {
        val release = latch()
        val running = latch()
        val first = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val second = java.util.concurrent.atomic.AtomicReference<Any?>(null)
        val flagBetween = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val thread = Thread {
            first.set(
                runCatching {
                    worker.call(waitMs = 60_000, skipKey = null) {
                        running.countDown()
                        release.await()
                    }
                }.exceptionOrNull(),
            )
            flagBetween.set(Thread.currentThread().isInterrupted)
            second.set(runCatching { worker.call(waitMs = 5_000, skipKey = null) { 7 } }.let { it.getOrNull() ?: it.exceptionOrNull() })
            done.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(running.await(3, TimeUnit.SECONDS))
        thread.interrupt()
        // The second call queues behind the wedge, so let the wedge go once the first call has left.
        assertTrue(awaitUntil { first.get() != null })
        release.countDown()

        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue("an interrupt that ended a wait is handed back, never swallowed", flagBetween.get())
        assertTrue(first.get() is TransactionWorkerBusyException)
        assertEquals("but it does not poison the next request", 7, second.get())
    }

    @Test
    fun `an interrupt delivered during the wait still ends it`() {
        val release = latch()
        val running = latch()
        val outcome = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        val thread = Thread {
            outcome.set(
                runCatching {
                    worker.call(waitMs = 60_000, skipKey = null) {
                        running.countDown()
                        release.await()
                    }
                }.exceptionOrNull(),
            )
            done.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(running.await(3, TimeUnit.SECONDS))
        thread.interrupt()

        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue("f3127a6f3's semantics hold: an interrupt during the wait is a lapsed wait", outcome.get() is TransactionWorkerBusyException)
        release.countDown()
    }

    @Test
    fun `a tick whose previous call is still on the worker is skipped without queueing`() {
        val release = latch()
        val running = latch()
        val first = runCatching {
            worker.call(waitMs = 500, skipKey = "supervisor") {
                running.countDown()
                release.await()
            }
        }.exceptionOrNull()
        assertTrue(first is TransactionWorkerBusyException)
        val secondRan = AtomicBoolean(false)

        val second = runCatching {
            worker.call(waitMs = 100, skipKey = "supervisor") { secondRan.set(true) }
        }.exceptionOrNull()

        assertTrue(second is TransactionWorkerBusyException)
        assertTrue(second!!.message!!.contains("skipped"))
        assertEquals("a skipped tick is not enqueued, so it adds no work", 1, count.get())
        assertTrue(logs.any { it.contains("TICK_SKIPPED") && it.contains("supervisor") })

        release.countDown()
        assertTrue(awaitUntil { count.get() == 0 })
        assertFalse("the skipped tick must never run later", secondRan.get())
        assertEquals("ticks resume once the worker is free", "ok", worker.call(1_000, "supervisor") { "ok" })
    }

    @Test
    fun `a call made from the worker thread itself runs inline`() {
        val inner = worker.call(waitMs = 1_000, skipKey = null) {
            worker.call(waitMs = 1, skipKey = null) { Thread.currentThread().name }
        }

        assertEquals(workerName, inner)
    }

    @Test
    fun `a block that throws surfaces its failure and still releases the count`() {
        val boom = IllegalStateException("boom")

        val error = runCatching {
            worker.call<Unit>(waitMs = 1_000, skipKey = null) { throw boom }
        }.exceptionOrNull()

        assertTrue(error is ExecutionException)
        assertSame(boom, error!!.cause)
        assertEquals(0, count.get())
    }

    @Test
    fun `only a worker timeout maps to SERVICE_BUSY`() {
        val busy = TransactionWorkerBusyException("SERVICE_BUSY: x", started = false)

        assertEquals(SERVICE_BUSY_CODE, transactionWorkerFailureCode(busy))
        assertEquals("SERVICE_ERROR", transactionWorkerFailureCode(IllegalStateException("other")))
        assertEquals("FALLBACK", transactionWorkerFailureCode(IllegalStateException("other"), "FALLBACK"))
        assertTrue("a Binder stub marshals an IllegalStateException, never a bare Throwable", busy is IllegalStateException)
    }
}
