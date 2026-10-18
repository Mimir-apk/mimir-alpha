package com.gruning.mimir.rewrite.core.metadata

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCancellationScopeTest {

    @Test
    fun ordinaryCloseDeregistersResourcesAcrossManyOpens() {
        val scope = ContentCancellationScope()
        val closed = AtomicInteger(0)
        repeat(32) {
            val handle = scope.register(Closeable { closed.incrementAndGet() })
            assertEquals(1, scope.activeRegistrationCount())
            handle.close()
            assertEquals(0, scope.activeRegistrationCount())
        }
        scope.close()
        assertEquals(32, closed.get())
    }

    @Test
    fun registerAfterCancelClosesImmediatelyWithoutRetainingRegistration() {
        val scope = ContentCancellationScope()
        val closed = AtomicBoolean(false)
        scope.cancel()
        val handle = scope.register(Closeable { closed.set(true) })
        assertTrue(closed.get())
        assertEquals(0, scope.activeRegistrationCount())
        handle.close()
        assertEquals(0, scope.activeRegistrationCount())
    }

    @Test
    fun cancelHookDismissalRemovesWithoutInvoking() {
        val scope = ContentCancellationScope()
        val invoked = AtomicBoolean(false)
        val handle = scope.registerOnCancel { invoked.set(true) }
        handle.close()
        scope.cancel()
        assertFalse(invoked.get())
    }

    @Test
    fun cancelAndRegisterRacesRemainIdempotent() {
        val scope = ContentCancellationScope()
        val pool = Executors.newFixedThreadPool(8)
        val invoked = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(128)
        repeat(64) {
            pool.submit {
                try {
                    start.await()
                    scope.registerOnCancel { invoked.incrementAndGet() }
                } finally {
                    done.countDown()
                }
            }
            pool.submit {
                try {
                    start.await()
                    scope.cancel()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        scope.close()
        assertEquals(64, invoked.get())
        assertEquals(0, scope.activeCancelHookCount())
        assertEquals(0, scope.activeRegistrationCount())
    }

    @Test
    fun cancelIsIdempotentAndClosesOutstandingRegistrationsOnce() {
        val scope = ContentCancellationScope()
        val closed = AtomicInteger(0)
        val first = scope.register(Closeable { closed.incrementAndGet() })
        val second = scope.register(Closeable { closed.incrementAndGet() })
        assertEquals(2, scope.activeRegistrationCount())
        scope.cancel()
        scope.cancel()
        assertEquals(0, scope.activeRegistrationCount())
        assertEquals(2, closed.get())
        first.close()
        second.close()
        assertEquals(2, closed.get())
    }

    @Test
    fun registerThenImmediateCancelClosesWithoutRetainingRegistration() {
        val scope = ContentCancellationScope()
        val closed = AtomicBoolean(false)
        val handle = scope.register(Closeable { closed.set(true) })
        scope.cancel()
        assertTrue(closed.get())
        assertEquals(0, scope.activeRegistrationCount())
        handle.close()
        scope.close()
    }

    @Test
    fun throwIfCancelledAfterScopeCancel() {
        val scope = ContentCancellationScope()
        scope.cancel()
        try {
            scope.throwIfCancelled()
            error("expected cancellation")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
