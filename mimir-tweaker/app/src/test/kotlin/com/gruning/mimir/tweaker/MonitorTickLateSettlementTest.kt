package com.gruning.mimir.tweaker

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 closing review #7: the supervisor monitor's bounded wait dropped a tick whose sampling and restore outlived it
 * (10 s of sampling plus 55 s of restore against a 60 s bound). The restore finished, the supervisor cleared its
 * session, and nobody ran the cleanup: the public owner stayed, the monitor kept ticking, and the post-control thermal
 * watch that follows every Restore never started. The tick now hands [MonitorTickSettlement] to the worker as its late
 * settlement, so the worker runs it, once, with the tick's real result.
 *
 * Times are scaled 1:100 (sampling 100 ms, restore 550 ms, bound 600 ms) so the same ordering runs in under a second.
 */
class MonitorTickLateSettlementTest {
    private val workerName = "tx-test-worker"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, workerName).apply { isDaemon = true }
    }
    private val count = AtomicInteger(0)
    private val worker = BoundedTransactionWorker(
        executor = executor,
        workCount = count,
        workerThreadName = workerName,
        onSettled = {},
        log = {},
    )

    private val calibrationAborts = AtomicInteger(0)
    private val ownerClears = AtomicInteger(0)
    private val ownerClearThread = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val holdClears = AtomicInteger(0)
    private val lifetimeSettles = AtomicInteger(0)
    private val terminalRequests = AtomicInteger(0)
    private val monitorStops = AtomicInteger(0)
    private val postControlWatches = AtomicInteger(0)
    private val settlement = MonitorTickSettlement(
        abortCalibration = { calibrationAborts.incrementAndGet() },
        clearPublicOwnerForSession = { _, _ ->
            ownerClears.incrementAndGet()
            ownerClearThread.set(Thread.currentThread().name)
        },
        clearCurrentPublicOwner = {
            ownerClears.incrementAndGet()
            ownerClearThread.set(Thread.currentThread().name)
        },
        clearLabApplyHold = { holdClears.incrementAndGet() },
        settleLifetime = { lifetimeSettles.incrementAndGet() },
        requestTerminalSettlement = { terminalRequests.incrementAndGet() },
        stopMonitor = { monitorStops.incrementAndGet() },
        startPostControlWatch = { postControlWatches.incrementAndGet() },
    )

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun restored() = TransactionResult(
        success = true,
        applied = false,
        restored = true,
        code = "RESTORED",
        message = "restored",
        transactionId = "tx-1",
        generation = 7L,
    )

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /** The tick exactly as the service issues it: the worker call with the settlement as its late finish, then inline. */
    private fun tick(waitMs: Long, block: () -> TransactionResult): TransactionResult? {
        val answered = runCatching {
            worker.call(
                waitMs = waitMs,
                skipKey = "supervisor",
                onLateFinish = { late: Result<TransactionResult> -> settlement.settle(late.getOrNull()) },
                block = block,
            )
        }.getOrNull()
        settlement.settle(answered)
        return answered
    }

    private fun assertSettledExactlyOnce() {
        assertEquals(1, calibrationAborts.get())
        assertEquals(1, ownerClears.get())
        assertEquals(1, holdClears.get())
        assertEquals(1, lifetimeSettles.get())
        assertEquals(1, terminalRequests.get())
        assertEquals(1, monitorStops.get())
        assertEquals("a genuine Restore arms the post-control thermal watch", 1, postControlWatches.get())
    }

    @Test
    fun `a restore that outlives the tick wait is settled once by the worker, with the post-control watch`() {
        val answered = tick(waitMs = 600) {
            Thread.sleep(100) // sampling
            Thread.sleep(550) // restore
            restored()
        }

        assertNull("the caller left a running tick: it has no answer to settle", answered)
        assertEquals("the caller itself settled nothing", 0, monitorStops.get())

        assertTrue(awaitUntil { count.get() == 0 })
        Thread.sleep(50)
        assertSettledExactlyOnce()
        assertEquals("the worker, not the departed monitor thread, ran it", workerName, ownerClearThread.get())
    }

    @Test
    fun `a tick that answers in time is settled once by its own caller and never again`() {
        val answered = tick(waitMs = 5_000) { restored() }

        assertEquals(restored(), answered)
        assertTrue(awaitUntil { count.get() == 0 })
        Thread.sleep(50)
        assertSettledExactlyOnce()
        assertFalse("the monitor thread ran it, not the worker", ownerClearThread.get() == workerName)
    }

    @Test
    fun `a bare no-active-session tick clears the owner and stops the monitor but arms no watch`() {
        tick(waitMs = 5_000) {
            TransactionResult(success = false, applied = false, restored = false, code = "NO_ACTIVE_SESSION", message = "none")
        }

        assertEquals(1, ownerClears.get())
        assertEquals(1, monitorStops.get())
        assertEquals("nothing was Mimir-controlled, so nothing was abandoned", 0, postControlWatches.get())
    }

    @Test
    fun `a late tick that failed or restored nothing settles nothing`() {
        tick(waitMs = 100) {
            Thread.sleep(300)
            error("sampling blew up")
        }
        assertTrue(awaitUntil { count.get() == 0 })
        tick(waitMs = 100) {
            Thread.sleep(300)
            TransactionResult(success = true, applied = false, restored = false, code = "CONTINUE", message = "")
        }
        assertTrue(awaitUntil { count.get() == 0 })

        assertEquals(0, monitorStops.get())
        assertEquals(0, ownerClears.get())
        assertEquals(0, postControlWatches.get())
    }

    /** The service must keep handing the settlement to the worker, and keep no inline copy of it on the monitor thread. */
    @Test
    fun `the monitor cycle in the service settles through the shared settlement, late and inline`() {
        val service = source("MimirTweakerService.kt")
        val cycle = service.substringAfter("val monitorCycle = Runnable {").substringBefore("val future = executor.scheduleWithFixedDelay(")

        assertTrue(
            "the tick must pass the settlement as onLateFinish",
            Regex("onTransactionWorkerSettlingLate\\(\\s*onLateFinish = \\{ late: Result<TransactionResult\\?> ->\\s*monitorTickSettlement\\.settle\\(late\\.getOrNull\\(\\)\\)")
                .containsMatchIn(cycle),
        )
        assertTrue("and settle an answer it received itself", cycle.contains("monitorTickSettlement.settle(result)"))
        assertFalse("no inline cleanup may remain on the monitor thread", cycle.contains("startPostControlWatch()"))
        assertFalse(cycle.contains("settleActiveOrRecovery"))
    }

    private fun source(fileName: String): String = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
        File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Tweaker source $fileName not found")
}
