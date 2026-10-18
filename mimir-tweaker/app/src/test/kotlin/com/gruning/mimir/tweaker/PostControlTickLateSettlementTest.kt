package com.gruning.mimir.tweaker

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S9 (b): the post-control thermal watch bounded its wait on the transaction worker but supplied no late settlement. A
 * tick whose block outlived that wait lost its decision logging and its `shouldDisarm`, so repeated late ticks kept the
 * watch (and the foreground service behind it) armed. The tick now hands [PostControlTickSettlement] to the worker as
 * its late finish, the same pattern [MonitorTickSettlement] uses, so the worker runs it once with the tick's real result.
 *
 * Times are scaled 1:100 so the ordering that matters runs in under a second.
 */
class PostControlTickLateSettlementTest {
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

    private val decisions = AtomicInteger(0)
    private val decisionThread = AtomicReference<String?>(null)
    private val stops = AtomicInteger(0)
    private val settlement = PostControlTickSettlement(
        onDecision = {
            decisions.incrementAndGet()
            decisionThread.set(Thread.currentThread().name)
        },
        stopWatch = { stops.incrementAndGet() },
    )

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun cool() = PostControlThermalEvaluation(decision = PostControlThermalDecision.Idle, shouldDisarm = true)

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /** The tick exactly as the service issues it: the worker call with the settlement as its late finish, then inline. */
    private fun tick(waitMs: Long, block: () -> PostControlThermalEvaluation?): PostControlThermalEvaluation? {
        val answered = runCatching {
            worker.call(
                waitMs = waitMs,
                skipKey = "post-control",
                onLateFinish = { late: Result<PostControlThermalEvaluation?> -> settlement.settle(late.getOrNull()) },
                block = block,
            )
        }.getOrNull()
        settlement.settle(answered)
        return answered
    }

    @Test
    fun `a tick that outlives its wait is settled once by the worker, decision logged and watch disarmed`() {
        val answered = tick(waitMs = 300) {
            Thread.sleep(600) // sampling plus the cooling write outlive the bound
            cool()
        }

        assertNull("the caller left a running tick: it has no answer to settle", answered)
        assertEquals("the caller itself settled nothing", 0, decisions.get())

        assertTrue(awaitUntil { count.get() == 0 })
        Thread.sleep(50)
        assertEquals("the late decision is logged", 1, decisions.get())
        assertEquals("the late shouldDisarm is honoured", 1, stops.get())
        assertEquals("the worker, not the departed watch thread, ran it", workerName, decisionThread.get())
    }

    @Test
    fun `a tick that answers in time is settled once by its own caller and never again`() {
        val answered = tick(waitMs = 5_000) { cool() }

        assertEquals(cool(), answered)
        assertTrue(awaitUntil { count.get() == 0 })
        Thread.sleep(50)
        assertEquals(1, decisions.get())
        assertEquals(1, stops.get())
        assertTrue("the watch thread ran it, not the worker", decisionThread.get() != workerName)
    }

    @Test
    fun `a late tick that is not yet cool logs its decision and leaves the watch armed`() {
        tick(waitMs = 300) {
            Thread.sleep(600)
            PostControlThermalEvaluation(decision = PostControlThermalDecision.Idle, shouldDisarm = false)
        }

        assertTrue(awaitUntil { decisions.get() == 1 })
        Thread.sleep(50)
        assertEquals(0, stops.get())
    }

    @Test
    fun `a late tick that threw or never produced an evaluation settles nothing`() {
        tick(waitMs = 300) {
            Thread.sleep(600)
            null
        }
        assertTrue(awaitUntil { count.get() == 0 })
        Thread.sleep(50)
        assertEquals(0, decisions.get())
        assertEquals(0, stops.get())

        runCatching {
            worker.call<PostControlThermalEvaluation?>(
                waitMs = 300,
                skipKey = "post-control",
                onLateFinish = { late -> settlement.settle(late.getOrNull()) },
            ) {
                Thread.sleep(600)
                error("sampling failed")
            }
        }
        assertTrue(awaitUntil { count.get() == 0 })
        Thread.sleep(50)
        assertEquals(0, decisions.get())
        assertEquals(0, stops.get())
    }

    /** The service must keep handing the settlement to the worker, and keep nothing inline but the in-time answer. */
    @Test
    fun `the post-control tick supplies the settlement as its late finish and stops only its own watch`() {
        val service = source("MimirTweakerService.kt")
        val watch = service.substringAfter("private fun startPostControlWatch() {").substringBefore("private fun stopPostControlWatch() {")
        assertTrue(
            Regex("onTransactionWorkerSettlingLate\\(\\s*onLateFinish = \\{ late: Result<PostControlThermalEvaluation\\?> ->\\s*tickSettlement\\.settle\\(late\\.getOrNull\\(\\)\\)")
                .containsMatchIn(watch),
        )
        assertTrue("the in-time answer settles through the same unit", watch.contains("tickSettlement.settle(evaluation)"))
        assertTrue(
            "a stale late result must not disarm a replacement watch",
            watch.contains("if (postControlExecutor === executor) stopPostControlWatch()"),
        )
        assertTrue("no inline disarm may remain on the watch thread", !watch.contains("evaluation?.shouldDisarm"))
    }

    private fun source(fileName: String): String = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
        File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Tweaker source $fileName not found")
}
