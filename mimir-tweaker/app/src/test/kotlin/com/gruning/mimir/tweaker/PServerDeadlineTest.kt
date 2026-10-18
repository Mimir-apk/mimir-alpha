package com.gruning.mimir.tweaker

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S3, as corrected after the independent review: a PServer READ has a deadline and a PServer WRITE never does.
 *
 * A read used to have none, so one wedged vendor `cat` held its caller (usually the single transaction worker) forever;
 * it now lapses and is reported as unreadable. A write abandoned at a deadline would keep running on a helper thread
 * and could land after the daemon had read the node back, restored it, or released the Guardian's mutation fence --
 * nothing read afterwards would be authoritative -- so a write runs inline on its caller and waits until `pservice`
 * answers. Liveness while one is slow comes from the transaction worker's own bound, not from a timeout on the write.
 *
 * The real-Binder path cannot be driven on plain JVM (Parcel.obtain is stubbed), so the proof runs through the
 * transport seam, which sits under the same boundary. What the Binder path adds is only that its Parcels are
 * obtained, used and recycled INSIDE the call (see `PServerClient.binderTransact`).
 */
class PServerDeadlineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val latches = mutableListOf<CountDownLatch>()
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    private fun latch(): CountDownLatch = CountDownLatch(1).also(latches::add)

    private fun background(): java.util.concurrent.ExecutorService =
        Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }.also(executors::add)

    @After
    fun releaseEveryStuckThread() {
        latches.forEach(CountDownLatch::countDown)
        executors.forEach { it.shutdownNow() }
    }

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    // ---- PServerCallBound (reads only) ---------------------------------------------------------

    @Test
    fun `a call that finishes inside its deadline is done`() {
        val outcome = PServerCallBound(defaultDeadlineMs = 1_000).run { 42 }
        assertEquals(PServerCallOutcome.Done(42), outcome)
    }

    @Test
    fun `a call that outlives its deadline is left at the deadline, not when it returns`() {
        val release = latch()
        val bound = PServerCallBound(defaultDeadlineMs = 100)
        val started = System.nanoTime()

        val outcome = bound.run { release.await(); 1 }

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(PServerCallOutcome.TimedOut(100), outcome)
        assertTrue("left after ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `an abandoned call is counted until it really returns`() {
        val release = latch()
        val bound = PServerCallBound(defaultDeadlineMs = 50)

        bound.run { release.await() }
        assertEquals(1, bound.abandonedCount())

        release.countDown()
        assertTrue("the stuck call returned, so the count must clear", awaitUntil { bound.abandonedCount() == 0 })
    }

    @Test
    fun `calls are refused unsent once earlier ones are stuck past the cap`() {
        val release = latch()
        val bound = PServerCallBound(defaultDeadlineMs = 50, maxAbandoned = 1)
        bound.run { release.await() }
        val ran = AtomicBoolean(false)

        val refused = bound.run { ran.set(true); 1 }

        assertEquals(PServerCallOutcome.Refused(1), refused)
        assertFalse("a refused call must never be sent", ran.get())

        release.countDown()
        assertTrue(awaitUntil { bound.abandonedCount() == 0 })
        assertEquals(PServerCallOutcome.Done(7), bound.run { 7 })
    }

    @Test
    fun `the stuck count is exact under concurrent calls that finish at their deadline`() {
        // Each call ends about when its wait does, so a call is constantly "finishing in the instant it is abandoned".
        // The count must never go negative or stay positive once every call has returned, and no call may be refused.
        val bound = PServerCallBound(defaultDeadlineMs = 3, maxAbandoned = 10_000)
        val refused = AtomicInteger(0)
        val negative = AtomicInteger(0)
        val threads = (1..8).map {
            Thread {
                repeat(100) {
                    val outcome = bound.run { Thread.sleep(3) }
                    if (outcome is PServerCallOutcome.Refused) refused.incrementAndGet()
                    if (bound.abandonedCount() < 0) negative.incrementAndGet()
                }
            }.apply { start() }
        }
        threads.forEach { it.join(30_000) }

        assertTrue("every stuck call has returned, so the count clears", awaitUntil { bound.abandonedCount() == 0 })
        assertEquals(0, refused.get())
        assertEquals(0, negative.get())
    }

    @Test
    fun `a throwing call reports the cause`() {
        val boom = IllegalStateException("binder dead")
        val outcome = PServerCallBound(defaultDeadlineMs = 1_000).run<Int> { throw boom }
        assertEquals(PServerCallOutcome.Threw(boom), outcome)
    }

    // ---- PServerClient: a write never times out, a read does -------------------------------------

    private fun confirmed(command: String): PServerTransactResult {
        val prefix = PServerClient.EXIT_MARKER_PREFIX
        val start = command.indexOf(prefix) + prefix.length
        val nonce = command.substring(start, command.indexOf(':', start))
        return PServerTransactResult(accepted = true, output = "$prefix$nonce:0:0:")
    }

    /** A client whose transport blocks on [release]; reads lapse after 100 ms, which is far inside any wait below. */
    private fun stuckClient(
        release: CountDownLatch,
        sent: AtomicInteger,
        maxAbandonedReads: Int = PServerCallBound.MAX_ABANDONED_READS,
        onTransact: (String, Boolean) -> Unit = { _, _ -> },
    ) = PServerClient(
        transport = PServerBinderTransport { command, captureOutput ->
            sent.incrementAndGet()
            onTransact(command, captureOutput)
            release.await()
            if (captureOutput && command.contains(PServerClient.EXIT_MARKER_PREFIX)) {
                confirmed(command)
            } else {
                PServerTransactResult(accepted = true)
            }
        },
        callBound = PServerCallBound(defaultDeadlineMs = 100, maxAbandoned = maxAbandonedReads),
        readDeadlineMs = 100,
    )

    @Test
    fun `a checked write is never timed out -- it waits for pservice and is then CONFIRMED`() {
        val release = latch()
        val sent = AtomicInteger(0)
        val client = stuckClient(release, sent)
        val result = background().submit<CheckedCommandResult> { client.executeChecked("echo 1 > /sys/x", nonce = "n1_abcdef12") }

        // Well past the 100 ms read deadline, and nowhere near an answer: the write is still just waiting.
        try {
            result.get(600, TimeUnit.MILLISECONDS)
            throw AssertionError("a write must not return before pservice answers")
        } catch (_: TimeoutException) {
        }
        assertEquals("a waiting write is not replayed", 1, sent.get())

        release.countDown()
        val answered = result.get(3, TimeUnit.SECONDS)
        assertEquals(CheckedCommandStatus.CONFIRMED, answered.status)
        assertEquals(0, answered.exitCode)
    }

    @Test
    fun `a write that finishes late is never an abandoned call, so there is nothing running behind its caller`() {
        val release = latch()
        val sent = AtomicInteger(0)
        val bound = PServerCallBound(defaultDeadlineMs = 100)
        val client = PServerClient(
            transport = PServerBinderTransport { command, _ ->
                sent.incrementAndGet()
                release.await()
                confirmed(command)
            },
            callBound = bound,
            readDeadlineMs = 100,
        )
        val result = background().submit<CheckedCommandResult> { client.executeChecked("echo 1 > /sys/x", nonce = "n2_abcdef12") }
        assertTrue(awaitUntil { sent.get() == 1 })
        Thread.sleep(300)

        assertEquals("a write never enters the deadline pool", 0, bound.abandonedCount())
        release.countDown()
        assertEquals(CheckedCommandStatus.CONFIRMED, result.get(3, TimeUnit.SECONDS).status)
    }

    @Test
    fun `a write runs on its caller's own thread, so a fence held around it is held until it returns`() {
        val ranOn = AtomicReference<Thread>()
        val client = PServerClient(
            transport = PServerBinderTransport { command, _ ->
                ranOn.set(Thread.currentThread())
                confirmed(command)
            },
        )

        client.executeChecked("echo 1 > /sys/x", nonce = "n3_abcdef12")
        assertSame(Thread.currentThread(), ranOn.get())

        ranOn.set(null)
        client.execute("settings put system x 1", captureOutput = false)
        assertSame(Thread.currentThread(), ranOn.get())
    }

    @Test
    fun `a non-capturing execute is a write, so it never times out either`() {
        val release = latch()
        val sent = AtomicInteger(0)
        val client = stuckClient(release, sent)
        val result = background().submit<Result<String?>> { client.execute("settings put system x 1", captureOutput = false) }

        try {
            result.get(600, TimeUnit.MILLISECONDS)
            throw AssertionError("a write must not return before pservice answers")
        } catch (_: TimeoutException) {
        }

        release.countDown()
        assertTrue(result.get(3, TimeUnit.SECONDS).isSuccess)
        assertEquals(1, sent.get())
    }

    @Test
    fun `a checked write that throws is AMBIGUOUS, never FAILED, and is not replayed`() {
        val sent = AtomicInteger(0)
        val client = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                sent.incrementAndGet()
                throw IllegalStateException("binder died mid-transaction")
            },
        )

        val result = client.executeChecked("echo 1 > /sys/x", nonce = "n4_abcdef12")

        assertEquals(CheckedCommandStatus.AMBIGUOUS, result.status)
        assertTrue(result.detail, result.detail.contains("binder died mid-transaction"))
        assertEquals(1, sent.get())
    }

    @Test
    fun `reads past their deadline fail as a timeout and say the read may still run`() {
        val release = latch()
        val client = stuckClient(release, AtomicInteger(0))
        val started = System.nanoTime()

        val read = client.execute("cat /sys/x", captureOutput = true)
        val raw = client.executeRaw("printf x")

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(read.isFailure)
        assertTrue(read.exceptionOrNull() is PServerTransactTimeoutException)
        assertTrue(raw.isFailure)
        assertTrue(raw.exceptionOrNull() is PServerTransactTimeoutException)
        assertTrue(read.exceptionOrNull()!!.message!!.contains("may still run"))
        assertTrue("two timed-out reads returned after ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `reads are refused unsent once earlier reads are stuck, and the stuck reads never refuse a write`() {
        val release = latch()
        val sent = AtomicInteger(0)
        val client = stuckClient(release, sent, maxAbandonedReads = 1)
        assertTrue(client.execute("cat /sys/x", captureOutput = true).isFailure)
        assertEquals(1, sent.get())

        val refusedRead = client.executeRaw("printf x")
        assertTrue(refusedRead.exceptionOrNull() is PServerCallsWedgedException)
        assertEquals("the refused read never reached the transport", 1, sent.get())

        release.countDown()
        val write = client.executeChecked("echo 1 > /sys/x", nonce = "n5_abcdef12")

        assertEquals("the write was sent, not refused", 2, sent.get())
        assertEquals(CheckedCommandStatus.CONFIRMED, write.status)
    }

    @Test
    fun `a write that is waiting does not delay a read on another thread`() {
        val release = latch()
        val writeStarted = latch()
        val sent = AtomicInteger(0)
        val client = PServerClient(
            transport = PServerBinderTransport { command, captureOutput ->
                if (command.contains(PServerClient.EXIT_MARKER_PREFIX)) {
                    sent.incrementAndGet()
                    writeStarted.countDown()
                    release.await()
                    confirmed(command)
                } else {
                    PServerTransactResult(accepted = true, output = "42", bytes = "42".toByteArray())
                }
            },
            readDeadlineMs = 100,
        )
        val write = background().submit<CheckedCommandResult> { client.executeChecked("echo 1 > /sys/x", nonce = "n6_abcdef12") }
        assertTrue(writeStarted.await(3, TimeUnit.SECONDS))

        val read = client.execute("cat /sys/y", captureOutput = true)

        assertEquals("42", read.getOrNull())
        assertFalse("the write is still waiting", write.isDone)
        release.countDown()
        assertEquals(CheckedCommandStatus.CONFIRMED, write.get(3, TimeUnit.SECONDS).status)
    }

    @Test
    fun `a healthy read is unaffected and the stuck count clears once the service answers`() {
        val release = latch()
        val answering = AtomicBoolean(false)
        val bound = PServerCallBound(defaultDeadlineMs = 100)
        val client = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                if (!answering.get()) release.await()
                PServerTransactResult(accepted = true, output = "ok")
            },
            callBound = bound,
            readDeadlineMs = 100,
        )
        assertTrue(client.execute("cat /sys/x", captureOutput = true).isFailure)
        assertEquals(1, bound.abandonedCount())

        answering.set(true)
        release.countDown()
        assertTrue(awaitUntil { bound.abandonedCount() == 0 })

        assertEquals("ok", client.execute("cat /sys/x", captureOutput = true).getOrNull())
    }

    @Test
    fun `the Guardian mutation fence stays held until a slow write really returns`() {
        // GuardianFencedHardwareCommandPort runs the write inside fence.withApply, which holds the cross-process flock
        // for the length of the block. With the write deadline gone the block cannot return before the service answers,
        // so the fence cannot be released (and a restore cannot certify Stock) while a write is still in flight.
        val fence = GuardianProcessMutationFence(temporaryFolder.newFolder("fence").absoluteFile)
        val identity = GenerationFenceIdentity(
            bootIdentity = "boot-1",
            generation = 9,
            transactionId = "transaction-9",
            sessionId = "e".repeat(32),
        )
        assertEquals(GuardianFenceDisposition.CONFIRMED, fence.stage(identity))
        val inWrite = latch()
        val release = latch()
        val client = PServerClient(
            transport = PServerBinderTransport { command, _ ->
                inWrite.countDown()
                release.await()
                confirmed(command)
            },
            readDeadlineMs = 100,
        )
        val fenced = background().submit<GuardianFencedResult<CheckedCommandResult>> {
            fence.withApply(identity) { client.executeChecked("echo 1 > /sys/x", nonce = "n7_abcdef12") }
        }
        assertTrue(inWrite.await(3, TimeUnit.SECONDS))

        Thread.sleep(400) // four read deadlines, and the write is still waiting
        assertFalse("the write has not returned, so the fenced block has not", fenced.isDone)
        assertFalse("the flock is still held while the write is in flight", fence.state(identity).lockAcquired)

        release.countDown()
        val executed = fenced.get(3, TimeUnit.SECONDS) as GuardianFencedResult.Executed
        assertEquals(CheckedCommandStatus.CONFIRMED, executed.value.status)
        assertTrue("released only after the write returned", fence.state(identity).lockAcquired)
    }

    @Test
    fun `no write deadline or write stuck budget is left in the source`() {
        val bound = source("PServerCallBound.kt")
        val client = source("PServerClient.kt")
        listOf("WRITE_DEADLINE_MS", "MAX_ABANDONED_CALLS", "PServerCallKind", "writeDeadlineMs", "PSERVER_TIMED_OUT_PREFIX")
            .forEach { removed ->
                assertFalse("$removed must stay removed from PServerCallBound.kt", bound.contains(removed))
                assertFalse("$removed must stay removed from PServerClient.kt", client.contains(removed))
            }
        // A write is the inline path; only reads go through the deadline pool.
        assertTrue(client.contains("transactInline(wrapped, captureOutput = true)"))
    }

    private fun source(name: String): String = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/tweaker/$name"),
        File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$name"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Tweaker source $name not found")
}
