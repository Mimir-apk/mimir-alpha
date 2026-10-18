package com.gruning.mimir.rewrite.core.metadata.online

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancel must reach a call that is parked in a blocking read (observed: pressing Cancel had no immediate effect, and "Done · 0 written"
 * appeared seconds later when the network finally gave up). A socket read does not look at the interrupt flag; closing the connection
 * from another thread ends it, and that is what [runAbortingOnCancel] and [ConnectionAbort] do. No network: the connection is a fake whose
 * read parks on a latch and whose `disconnect()` releases it, like the real one.
 */
class CancellableBlockingCallTest {

    private class ParkedConnection : HttpURLConnection(URL("https://www.screenscraper.fr/api2/jeuInfos.php")) {
        val readerParked = CountDownLatch(1)
        private val released = CountDownLatch(1)
        val disconnects = AtomicInteger()

        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() {
            disconnects.incrementAndGet()
            released.countDown()
        }

        override fun getResponseCode(): Int {
            readerParked.countDown()
            released.await() // a dead network: only a disconnect ends this
            throw java.io.IOException("Socket closed")
        }
    }

    @Test
    fun `cancelling a coroutine parked in a blocking read closes the connection and ends promptly with the cancellation`() = runBlocking {
        val connection = ParkedConnection()
        val abort = ConnectionAbort()
        var seen: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                runAbortingOnCancel(abort = abort::abort) {
                    check(abort.attach(connection))
                    try {
                        connection.responseCode
                    } catch (_: java.io.IOException) {
                        null // the caller's own transport catch: the abort turns the read into this
                    }
                }
            } catch (cancelled: CancellationException) {
                seen = cancelled
                throw cancelled
            }
        }
        assertTrue("the read is parked", connection.readerParked.await(5, TimeUnit.SECONDS))

        withTimeout(5_000) { job.cancelAndJoin() }

        assertTrue("the caller saw a cancellation, not a transport failure it would count: $seen", seen is CancellationException)
        assertEquals("the connection was closed exactly by the abort", 1, connection.disconnects.get())
    }

    @Test
    fun `a call that finishes by itself is never aborted`() = runBlocking {
        val aborts = AtomicInteger()
        val result = withContext(Dispatchers.IO) { runAbortingOnCancel(abort = { aborts.incrementAndGet() }) { "body" } }
        assertEquals("body", result)
        assertEquals(0, aborts.get())
    }

    @Test
    fun `a block that fails after the abort reports the cancellation, not its transport error`() = runBlocking {
        val inside = CountDownLatch(1)
        val gate = CountDownLatch(1)
        var seen: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                runAbortingOnCancel(abort = { gate.countDown() }) {
                    inside.countDown()
                    gate.await()
                    throw java.io.IOException("Socket closed")
                }
            } catch (failure: Throwable) {
                seen = failure
                throw failure
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue("$seen", seen is CancellationException)
    }

    @Test
    fun `a connection attached after the abort is closed at once and must not be used`() {
        val abort = ConnectionAbort()
        abort.abort()
        val late = ParkedConnection()
        assertFalse(abort.attach(late))
        assertEquals(1, late.disconnects.get())
    }
}
