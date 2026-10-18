package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.NativeOverlayInteractionSource
import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMotionLeaseRegistryTest {
    @Test
    fun overlappingNativeSourcesReleaseAllHoldsAndIdleAfterFullDeadline() {
        val clock = FakeMonotonicDelay()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = DefaultInteractionMotionGate(
            scope = scope,
            clock = clock,
            delayMillis = clock::delay,
        )
        val root = gate.registerRoot(InteractionRoot.NATIVE_WINDOW, initiallyVisible = true)
        val registry = NativeMotionLeaseRegistry { source ->
            val interactionSource = when (source) {
                NativeOverlayInteractionSource.M_BUTTON -> InteractionSource.NATIVE_M_BUTTON
                NativeOverlayInteractionSource.MENU -> InteractionSource.NATIVE_MENU
                NativeOverlayInteractionSource.EDGE_BACK -> InteractionSource.NATIVE_EDGE_BACK
            }
            gate.hold(root, interactionSource)
        }

        try {
            registry.onInteractionChanged(NativeOverlayInteractionSource.MENU, active = true)
            registry.onInteractionChanged(NativeOverlayInteractionSource.EDGE_BACK, active = true)

            registry.onInteractionChanged(NativeOverlayInteractionSource.MENU, active = false)
            assertEquals(AmbientMotionPhase.INTERACTING, gate.phase.value)

            registry.onInteractionChanged(NativeOverlayInteractionSource.EDGE_BACK, active = false)
            assertEquals(AmbientMotionPhase.RECENT, gate.phase.value)
            assertEquals(1, clock.pendingDelays)

            clock.advanceBy(DefaultInteractionMotionGate.IDLE_TIMEOUT_MILLIS)

            assertEquals(AmbientMotionPhase.IDLE, gate.phase.value)
            assertEquals(0, clock.pendingDelays)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun inactiveCallbackSupersedesAndClosesInFlightAcquisition() {
        val acquisitionStarted = CountDownLatch(1)
        val allowAcquisitionToFinish = CountDownLatch(1)
        val lease = CountingLease()
        val registry = NativeMotionLeaseRegistry {
            acquisitionStarted.countDown()
            assertTrue(allowAcquisitionToFinish.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            lease
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val activeCallback = executor.submit {
                registry.onInteractionChanged(NativeOverlayInteractionSource.MENU, active = true)
            }
            assertTrue(acquisitionStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            registry.onInteractionChanged(NativeOverlayInteractionSource.MENU, active = false)
            allowAcquisitionToFinish.countDown()
            activeCallback.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertEquals(1, lease.closeCount.get())
        } finally {
            allowAcquisitionToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun newerActiveCallbackOwnsLeaseWhenEarlierAcquisitionFinishesLate() {
        val firstAcquisitionStarted = CountDownLatch(1)
        val allowFirstAcquisitionToFinish = CountDownLatch(1)
        val acquisitionCount = AtomicInteger()
        val firstLease = CountingLease()
        val secondLease = CountingLease()
        val registry = NativeMotionLeaseRegistry {
            when (acquisitionCount.incrementAndGet()) {
                1 -> {
                    firstAcquisitionStarted.countDown()
                    assertTrue(allowFirstAcquisitionToFinish.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    firstLease
                }
                2 -> secondLease
                else -> error("Unexpected lease acquisition")
            }
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val firstCallback = executor.submit {
                registry.onInteractionChanged(NativeOverlayInteractionSource.EDGE_BACK, active = true)
            }
            assertTrue(firstAcquisitionStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            registry.onInteractionChanged(NativeOverlayInteractionSource.EDGE_BACK, active = true)
            allowFirstAcquisitionToFinish.countDown()
            firstCallback.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertEquals(1, firstLease.closeCount.get())
            assertEquals(0, secondLease.closeCount.get())

            registry.onInteractionChanged(NativeOverlayInteractionSource.EDGE_BACK, active = false)
            assertEquals(1, secondLease.closeCount.get())
        } finally {
            allowFirstAcquisitionToFinish.countDown()
            executor.shutdownNow()
        }
    }

    private class CountingLease : InteractionLease {
        val closeCount = AtomicInteger()

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class FakeMonotonicDelay : MonotonicClock {
        private data class Waiter(
            val deadlineMillis: Long,
            val continuation: CancellableContinuation<Unit>,
        )

        private val waiters = mutableListOf<Waiter>()
        private var nowMillis = 0L

        val pendingDelays: Int
            get() = waiters.count { it.continuation.isActive }

        override fun nowMillis(): Long = nowMillis

        suspend fun delay(durationMillis: Long) {
            suspendCancellableCoroutine { continuation ->
                val waiter = Waiter(nowMillis + durationMillis, continuation)
                waiters += waiter
                continuation.invokeOnCancellation { waiters.remove(waiter) }
            }
        }

        fun advanceBy(durationMillis: Long) {
            nowMillis += durationMillis
            val due = waiters.filter { it.deadlineMillis <= nowMillis }
            waiters.removeAll(due.toSet())
            due.forEach { waiter ->
                if (waiter.continuation.isActive) waiter.continuation.resume(Unit)
            }
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
