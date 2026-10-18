package com.gruning.mimir.tweaker

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S9 "Binder settlement": a failed V4 apply was settled on the Binder thread that answered it. Settling loads the
 * global journal and may enter RECOVERY under the supervisor's monitor, so a stuck supervisor write or a slow journal
 * held a Binder thread, and an unrelated journal could be read as RECOVERY from the wrong thread. It is now handed to
 * the transaction worker ([FailedApplySettlementHandoff]).
 */
class FailedApplySettlementHandoffTest {
    private val workerName = "tx-test-worker"
    private val uncaught = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, workerName).apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, error -> uncaught += error }
        }
    }
    private val count = AtomicInteger(0)
    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val worker = BoundedTransactionWorker(
        executor = executor,
        workCount = count,
        workerThreadName = workerName,
        onSettled = {},
        log = { logs += it },
    )
    private val latches = mutableListOf<CountDownLatch>()

    private fun latch() = CountDownLatch(1).also(latches::add)

    @After
    fun tearDown() {
        latches.forEach(CountDownLatch::countDown)
        executor.shutdownNow()
    }

    private fun handoff(waitMs: Long) = FailedApplySettlementHandoff(
        onWorker = { block -> worker.call(waitMs = waitMs, skipKey = null) { block() } },
        enqueue = { block ->
            count.incrementAndGet()
            executor.execute {
                try {
                    block()
                } finally {
                    count.decrementAndGet()
                }
            }
        },
        log = { logs += it },
    )

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun `a failed apply is settled on the transaction worker, never on the Binder thread`() {
        val ranOn = AtomicReference<String?>(null)
        val callerThread = Thread.currentThread().name

        handoff(waitMs = 5_000).settle { ranOn.set(Thread.currentThread().name) }

        assertEquals(workerName, ranOn.get())
        assertNotEquals(callerThread, ranOn.get())
    }

    @Test
    fun `a wait that lapses before the worker started the settlement queues it instead of losing it`() {
        val release = latch()
        val running = latch()
        Thread {
            runCatching {
                worker.call(waitMs = 60_000, skipKey = null) {
                    running.countDown()
                    release.await()
                }
            }
        }.apply { isDaemon = true; start() }
        assertTrue(running.await(3, TimeUnit.SECONDS))
        val ranOn = java.util.Collections.synchronizedList(mutableListOf<String>())
        val callerThread = Thread.currentThread().name

        // The worker is wedged, so this wait lapses with the settlement still QUEUED: it is withdrawn and never runs.
        handoff(waitMs = 100).settle { ranOn += Thread.currentThread().name }
        assertTrue("withdrawn, not yet run", ranOn.isEmpty())

        release.countDown()
        assertTrue("the queued settlement still runs", awaitUntil { ranOn.size == 1 })
        Thread.sleep(50)
        assertEquals("exactly once", 1, ranOn.size)
        assertEquals(workerName, ranOn.single())
        assertNotEquals(callerThread, ranOn.single())
    }

    @Test
    fun `a wait that lapses after the settlement started leaves it to the worker and does not run it twice`() {
        val release = latch()
        val running = latch()
        val runs = AtomicInteger(0)

        handoff(waitMs = 100).settle {
            runs.incrementAndGet()
            running.countDown()
            release.await()
        }
        assertTrue(running.await(3, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(awaitUntil { count.get() == 0 })
        Thread.sleep(50)

        assertEquals("the running settlement is neither re-queued nor repeated", 1, runs.get())
    }

    @Test
    fun `a settlement that throws does not replace the failed answer the caller is owed`() {
        handoff(waitMs = 5_000).settle { error("journal unreadable") }

        assertTrue(logs.any { it.contains("FAILED_APPLY_SETTLEMENT") })
    }

    // S9 round 2: when the wait lapsed with the settlement still queued, the raw callback was enqueued, and a later journal
    // or supervisor exception escaped the worker. It must be contained, logged once with its class, and held for recovery.
    @Test
    fun `a deferred settlement that throws is contained, held for recovery, and the worker runs the next transaction`() {
        val release = latch()
        val running = latch()
        Thread {
            runCatching {
                worker.call(waitMs = 60_000, skipKey = null) {
                    running.countDown()
                    release.await()
                }
            }
        }.apply { isDaemon = true; start() }
        assertTrue(running.await(3, TimeUnit.SECONDS))
        val held = AtomicInteger(0)

        // The worker is wedged, so the wait lapses with the settlement QUEUED and it is enqueued raw behind the wedge.
        handoff(waitMs = 100).settle(onFailed = { held.incrementAndGet() }) {
            throw IllegalStateException("journal blew up")
        }
        release.countDown()

        assertTrue("the deferred settlement ran and was held for recovery", awaitUntil { held.get() == 1 })
        assertTrue(awaitUntil { count.get() == 0 })
        assertTrue("nothing escaped the worker thread: $uncaught", uncaught.isEmpty())
        assertEquals("logged once, with its class", 1, logs.count { it.contains("java.lang.IllegalStateException") })
        // The worker is alive and the next transaction runs.
        assertEquals(7, worker.call(waitMs = 5_000, skipKey = null) { 7 })
        assertEquals("held once, not per attempt", 1, held.get())
    }

    @Test
    fun `a settlement that threw inline is held for recovery too, and one that succeeded is not`() {
        val held = AtomicInteger(0)
        handoff(waitMs = 5_000).settle(onFailed = { held.incrementAndGet() }) { error("supervisor wedged") }
        assertEquals(1, held.get())

        handoff(waitMs = 5_000).settle(onFailed = { held.incrementAndGet() }) { }
        assertEquals("a successful settlement never takes the fallback", 1, held.get())
    }

    @Test
    fun `a hold fallback that itself throws is contained`() {
        handoff(waitMs = 5_000).settle(onFailed = { error("hold failed") }) { error("settle failed") }

        assertTrue(logs.any { it.contains("hold failed class=java.lang.IllegalStateException") })
    }

    @Test
    fun `the service wires the failed-apply settlement into the existing recovery hold`() {
        val service = source("MimirTweakerService.kt")
        assertTrue(service.contains("failedApplySettlement.settle(onFailed = { holdAfterUnsettledFailedApply(ownerTokenId) })"))
        val hold = service.substringAfter("private fun holdAfterUnsettledFailedApply(").substringBefore("private fun reconcileStartedLifetimeAfterPublicApply(")
        assertTrue(hold.contains("startedServiceLifetime.markRecovery()"))
        assertTrue(hold.contains("publicOwners.completeApply(ownerTokenId, false)"))
    }

    /** The Binder method must route a failed apply through the handoff and keep nothing of it inline. */
    @Test
    fun `the V4 apply settles a failed result through the worker handoff`() {
        val service = source("MimirTweakerService.kt")
        val tail = service.substringAfter("// Every computed transaction result, including SERVICE_ERROR fallbacks, reaches")
            .substringBefore("result.toPublicBundle(")
        assertTrue(tail.contains("failedApplySettlement.settle("))
        assertTrue(
            "an applied result keeps its inline settlement, a failed one must not",
            Regex("if \\(result\\.applied\\) \\{\\s*settlePublicApplyResult\\(").containsMatchIn(tail),
        )
        assertTrue(service.contains("onWorker = { block -> onTransactionWorker(block) }"))
        assertFalse(service.contains("if (!settlementHandedToWorker) settlePublicApplyResult("))
    }

    private fun source(fileName: String): String = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
        File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Tweaker source $fileName not found")
}
