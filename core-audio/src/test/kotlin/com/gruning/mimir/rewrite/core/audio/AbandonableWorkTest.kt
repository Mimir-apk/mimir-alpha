package com.gruning.mimir.rewrite.core.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Blocking work that can hang for ever (the earlier update review): the retriever's native call cannot be interrupted, so a hostile file must cost one abandoned thread and
 * never a slot, and the art decoder's gate must never be held for ever by it.
 */
class AbandonableWorkTest {
    private fun waitUntilNothingIsStuck() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (AbandonableWork.stuck > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("every abandoned piece returned once released", 0, AbandonableWork.stuck)
    }

    @Test
    fun `work that finishes gives its value and runs off the caller's thread`() {
        val caller = Thread.currentThread()
        val outcome = runBlocking { AbandonableWork.run(5_000L) { Thread.currentThread() to 42 } }
        val done = outcome as AbandonableOutcome.Done
        assertEquals(42, done.value.second)
        assertFalse("it ran on a thread of its own", done.value.first === caller)
        assertTrue("a daemon, so a stuck one never keeps the process alive", done.value.first.isDaemon)
        assertEquals(0, AbandonableWork.stuck)
    }

    @Test
    fun `work that throws is reported, not rethrown`() {
        val boom = IllegalStateException("boom")
        val outcome = runBlocking { AbandonableWork.run(5_000L) { throw boom } }
        assertSame(boom, (outcome as AbandonableOutcome.Threw).error)
        assertEquals(0, AbandonableWork.stuck)
    }

    @Test
    fun `work that never returns times out, the caller moves on, and the stuck thread is counted until it returns`() {
        val release = CountDownLatch(1)
        try {
            val started = System.nanoTime()
            val outcome = runBlocking { AbandonableWork.run(120L) { release.await(); "late" } }
            val tookMs = (System.nanoTime() - started) / 1_000_000L
            assertEquals(AbandonableOutcome.TimedOut, outcome)
            assertTrue("the caller was not held past the timeout by much: $tookMs ms", tookMs < 3_000L)
            assertEquals(1, AbandonableWork.stuck)
        } finally {
            release.countDown()
            waitUntilNothingIsStuck()
        }
    }

    @Test
    fun `from MaxStuck stuck pieces no more are started, and one returning opens it again`() {
        val release = CountDownLatch(1)
        try {
            repeat(AbandonableWork.MaxStuck) {
                assertEquals(AbandonableOutcome.TimedOut, runBlocking { AbandonableWork.run(60L) { release.await() } })
            }
            assertEquals(AbandonableWork.MaxStuck, AbandonableWork.stuck)
            var ran = false
            val refused = runBlocking { AbandonableWork.run(60L) { ran = true } }
            assertEquals(AbandonableOutcome.Refused, refused)
            assertFalse("a refused piece is never started", ran)
        } finally {
            release.countDown()
            waitUntilNothingIsStuck()
        }
        assertEquals("with nothing stuck it works again", 7, (runBlocking { AbandonableWork.run(5_000L) { 7 } } as AbandonableOutcome.Done).value)
    }

    @Test
    fun `a caller that is cancelled abandons its work, which still counts until it returns`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            runBlocking {
                val job = launch {
                    AbandonableWork.run(60_000L) {
                        started.countDown()
                        release.await()
                    }
                }
                assertTrue(withContextBlocking { started.await(5, TimeUnit.SECONDS) })
                job.cancelAndJoin()
            }
            assertEquals(1, AbandonableWork.stuck)
        } finally {
            release.countDown()
            waitUntilNothingIsStuck()
        }
    }

    private suspend fun <T> withContextBlocking(block: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    // ── the gate ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a caller behind a taken gate gives up after its wait and runs nothing`() {
        val gate = BoundedGate()
        runBlocking {
            val held = CompletableDeferred<Unit>()
            val inside = CompletableDeferred<Unit>()
            val holder = async(start = CoroutineStart.UNDISPATCHED) {
                gate.withinGate(5_000L) {
                    inside.complete(Unit)
                    held.await()
                    "first"
                }
            }
            inside.await()
            assertTrue(gate.isTaken)
            var ran = false
            val waited = gate.withinGate(80L) { ran = true }
            assertEquals(BoundedGate.Gated.GaveUp, waited)
            assertFalse(ran)
            held.complete(Unit)
            assertEquals(BoundedGate.Gated.Ran("first"), holder.await())
            assertFalse("released when the holder is done", gate.isTaken)
            assertEquals(BoundedGate.Gated.Ran("second"), gate.withinGate(80L) { "second" })
        }
    }

    @Test
    fun `a block that throws still releases the gate`() {
        val gate = BoundedGate()
        runBlocking {
            try {
                gate.withinGate(1_000L) { error("decode failed") }
            } catch (_: IllegalStateException) {
                // Rethrown to the caller, with the gate released.
            }
            assertFalse(gate.isTaken)
            assertEquals(BoundedGate.Gated.Ran(1), gate.withinGate(1_000L) { 1 })
        }
    }
}
