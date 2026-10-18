package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InteractionMotionGateTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach(CoroutineScope::cancel)
    }

    @Test
    fun firstVisibleRootEntersRecentThenIdlesAtExactlyTwelveSeconds() {
        val fixture = fixture()

        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
        fixture.gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = true)

        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(1, fixture.clock.pendingDelays)
        fixture.clock.advanceBy(11_999)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceBy(1)
        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
        assertEquals(0, fixture.clock.pendingDelays)
    }

    @Test
    fun hiddenRootDoesNotWakeAndHiddenToVisibleResetsTheFullDeadline() {
        val fixture = fixture()
        val root = fixture.gate.registerRoot(InteractionRoot.COMPANION, initiallyVisible = false)

        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
        assertEquals(0, fixture.clock.pendingDelays)

        root.setVisible(true)
        fixture.clock.advanceBy(4_000)
        root.setVisible(false)
        root.setVisible(true)

        assertEquals(1, fixture.clock.pendingDelays)
        fixture.clock.advanceBy(11_999)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceBy(1)
        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
    }

    @Test
    fun repeatedPulsesReplaceTheDeadlineWithoutParallelTimers() {
        val fixture = fixture()
        fixture.gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = true)

        fixture.clock.advanceBy(4_000)
        fixture.gate.pulse(InteractionSource.CONTROLLER)
        assertEquals(1, fixture.clock.pendingDelays)

        fixture.clock.advanceBy(8_000)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(1, fixture.clock.pendingDelays)
        fixture.clock.advanceBy(3_999)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceBy(1)
        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
    }

    @Test
    fun rapidRecentReentryKeepsOneTimerUntilItMustRearmForTheLatestDeadline() {
        val fixture = fixture()
        fixture.gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = true)

        repeat(3) {
            fixture.clock.advanceBy(100)
            fixture.gate.pulse(InteractionSource.POINTER)
        }

        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(1, fixture.clock.startedDelays)
        assertEquals(1, fixture.clock.pendingDelays)

        fixture.clock.advanceBy(11_699)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceBy(1)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(2, fixture.clock.startedDelays)
        assertEquals(1, fixture.clock.pendingDelays)

        fixture.clock.advanceBy(299)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceBy(1)
        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
        assertEquals(0, fixture.clock.pendingDelays)
    }

    @Test
    fun heldGestureCannotIdleAndFinalReleaseStartsAFullRecentWindow() {
        val fixture = fixture()
        val root = fixture.gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = true)
        val gesture = fixture.gate.hold(root, InteractionSource.TOUCH)

        assertEquals(AmbientMotionPhase.INTERACTING, fixture.gate.phase.value)
        assertEquals(0, fixture.clock.pendingDelays)
        fixture.clock.advanceBy(20_000)
        assertEquals(AmbientMotionPhase.INTERACTING, fixture.gate.phase.value)

        gesture.close()
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(1, fixture.clock.pendingDelays)
        fixture.clock.advanceBy(11_999)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceBy(1)
        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
    }

    @Test
    fun overlappingRootsReleaseOnlyTheirOwnIdempotentGestureLeases() {
        val fixture = fixture()
        val primary = fixture.gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = true)
        val companion = fixture.gate.registerRoot(InteractionRoot.COMPANION, initiallyVisible = true)
        val primaryGesture = fixture.gate.hold(primary, InteractionSource.TOUCH)
        val companionGesture = fixture.gate.hold(companion, InteractionSource.TOUCH)

        primaryGesture.close()
        primaryGesture.close()
        primary.setVisible(false)
        assertEquals(AmbientMotionPhase.INTERACTING, fixture.gate.phase.value)
        assertEquals(0, fixture.clock.pendingDelays)

        companion.close()
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(1, fixture.clock.pendingDelays)
        companionGesture.close()
        assertEquals(1, fixture.clock.pendingDelays)
    }

    @Test
    fun staleRootCloseCannotAffectItsVisibleReplacementOrReplacementGesture() {
        val fixture = fixture()
        val oldRoot = fixture.gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = true)
        val oldGesture = fixture.gate.hold(oldRoot, InteractionSource.TOUCH)
        val replacement = fixture.gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = true)
        val replacementGesture = fixture.gate.hold(replacement, InteractionSource.TOUCH)

        assertNotEquals(oldRoot.id, replacement.id)
        oldRoot.close()
        oldRoot.setVisible(true)
        oldGesture.close()
        fixture.clock.advanceBy(20_000)

        assertEquals(AmbientMotionPhase.INTERACTING, fixture.gate.phase.value)
        assertEquals(0, fixture.clock.pendingDelays)
        replacementGesture.close()
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(1, fixture.clock.pendingDelays)
    }

    @Test
    fun hidingRootReleasesOnlyThatRootsMissingCancelGesture() {
        val fixture = fixture()
        val root = fixture.gate.registerRoot(InteractionRoot.NATIVE_WINDOW, initiallyVisible = true)
        val gesture = fixture.gate.hold(root, InteractionSource.NATIVE_OVERLAY_PANEL)

        root.setVisible(false)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(1, fixture.clock.pendingDelays)
        gesture.close()
        assertEquals(1, fixture.clock.pendingDelays)
    }

    @Test
    fun coalescedDeadlineWakeAtOldTargetRechecksNewerPulse() {
        val fixture = adversarialFixture()
        fixture.gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = true)
        val originalWaiter = fixture.clock.singleWaiterId()

        fixture.clock.advanceTo(DefaultInteractionMotionGate.IDLE_TIMEOUT_MILLIS)
        fixture.gate.pulse(InteractionSource.WHEEL)
        assertEquals(listOf(originalWaiter), fixture.clock.waitingIds)

        fixture.clock.fire(originalWaiter)
        val rearmedWaiter = fixture.clock.singleWaiterId()

        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(listOf(originalWaiter), fixture.clock.returnedFromDelayIds)
        assertEquals(listOf(rearmedWaiter), fixture.clock.waitingIds)
        fixture.clock.advanceTo((DefaultInteractionMotionGate.IDLE_TIMEOUT_MILLIS * 2) - 1)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceTo(DefaultInteractionMotionGate.IDLE_TIMEOUT_MILLIS * 2)
        fixture.clock.fire(rearmedWaiter)
        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
    }

    @Test
    fun repeatedHumanInputPulsesShareOneWorkerAndHonorTheLatestDeadline() {
        val fixture = adversarialFixture()
        fixture.gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = true)
        val originalWaiter = fixture.clock.singleWaiterId()

        fixture.clock.advanceTo(1_000)
        fixture.gate.pulse(InteractionSource.CONTROLLER)
        fixture.clock.advanceTo(2_000)
        fixture.gate.pulse(InteractionSource.POINTER)
        fixture.clock.advanceTo(3_000)
        fixture.gate.pulse(InteractionSource.NATIVE_MENU)

        assertEquals(listOf(originalWaiter), fixture.clock.waitingIds)
        assertEquals(emptyList<Int>(), fixture.clock.returnedFromDelayIds)

        fixture.clock.advanceTo(DefaultInteractionMotionGate.IDLE_TIMEOUT_MILLIS)
        fixture.clock.fire(originalWaiter)
        val rearmedWaiter = fixture.clock.singleWaiterId()

        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(listOf(originalWaiter), fixture.clock.returnedFromDelayIds)
        assertEquals(listOf(rearmedWaiter), fixture.clock.waitingIds)
        fixture.clock.advanceTo(14_999)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceTo(15_000)
        fixture.clock.fire(rearmedWaiter)
        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
    }

    @Test
    fun heldPointerInvalidatesCoalescedGenerationAndReleaseGetsAFullWindow() {
        val fixture = adversarialFixture()
        val root = fixture.gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = true)
        val preHoldWaiter = fixture.clock.singleWaiterId()

        fixture.clock.advanceTo(1_000)
        fixture.gate.pulse(InteractionSource.POINTER)
        assertEquals(listOf(preHoldWaiter), fixture.clock.waitingIds)

        fixture.clock.advanceTo(2_000)
        val pointer = fixture.gate.hold(root, InteractionSource.POINTER)
        assertEquals(AmbientMotionPhase.INTERACTING, fixture.gate.phase.value)

        fixture.clock.advanceTo(3_000)
        pointer.close()
        val releaseWaiter = fixture.clock.latestWaiterId()
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)

        fixture.clock.advanceTo(DefaultInteractionMotionGate.IDLE_TIMEOUT_MILLIS)
        fixture.clock.fire(preHoldWaiter)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(listOf(releaseWaiter), fixture.clock.waitingIds)

        fixture.clock.advanceTo(14_999)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceTo(15_000)
        fixture.clock.fire(releaseWaiter)
        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
    }

    @Test
    fun overlappingRootTouchesIgnoreOldCallbacksUntilTheLastFingerLifts() {
        val fixture = adversarialFixture()
        val primary = fixture.gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = true)
        val companion = fixture.gate.registerRoot(InteractionRoot.COMPANION, initiallyVisible = true)
        val primaryTouch = fixture.gate.hold(primary, InteractionSource.TOUCH)
        val companionTouch = fixture.gate.hold(companion, InteractionSource.TOUCH)
        val staleDeadlines = fixture.clock.waitingIds

        fixture.clock.advanceTo(DefaultInteractionMotionGate.IDLE_TIMEOUT_MILLIS)
        staleDeadlines.forEach(fixture.clock::fire)
        primaryTouch.close()
        primary.setVisible(false)
        fixture.gate.pulse(InteractionSource.CONTROLLER)

        assertEquals(AmbientMotionPhase.INTERACTING, fixture.gate.phase.value)
        assertEquals(emptyList<Int>(), fixture.clock.waitingIds)

        fixture.clock.advanceTo(30_000)
        companionTouch.close()
        val releaseDeadline = fixture.clock.singleWaiterId()

        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceTo(41_999)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceTo(42_000)
        fixture.clock.fire(releaseDeadline)
        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
    }

    @Test
    fun hideShowImmediatelyBeforeExpiryCoalescesAndRestartsTwelveSeconds() {
        val fixture = adversarialFixture()
        val companion = fixture.gate.registerRoot(InteractionRoot.COMPANION, initiallyVisible = true)
        val originalWaiter = fixture.clock.singleWaiterId()

        fixture.clock.advanceTo(11_999)
        companion.setVisible(false)
        companion.setVisible(true)
        assertEquals(listOf(originalWaiter), fixture.clock.waitingIds)

        fixture.clock.advanceTo(12_000)
        fixture.clock.fire(originalWaiter)
        val rearmedWaiter = fixture.clock.singleWaiterId()

        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(listOf(rearmedWaiter), fixture.clock.waitingIds)
        fixture.clock.advanceTo(23_998)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceTo(23_999)
        fixture.clock.fire(rearmedWaiter)
        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
    }

    /**
     * O18 . The motion policy keeps a root becoming visible as a wake: RECENT for the full 12,000 ms, IDLE
     * exactly then, no timer left. It is not an interaction, but it is the last wake, so the log line names the root that
     * woke the gate.
     */
    @Test
    fun rootBecomingVisibleWakesForTheFullWindow() {
        val logged = mutableListOf<InteractionMotionLogEvent>()
        val fixture = fixture(logger = InteractionMotionLogger { event -> logged += event })

        fixture.gate.pulse(InteractionSource.CONTROLLER, InteractionRoot.PRIMARY)
        fixture.clock.advanceBy(1_000)
        val companion = fixture.gate.registerRoot(InteractionRoot.COMPANION, initiallyVisible = true)

        fixture.clock.advanceBy(1_000)
        companion.setVisible(false)
        companion.setVisible(true)

        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        assertEquals(1, fixture.clock.pendingDelays)
        fixture.clock.advanceBy(11_999)
        assertEquals(AmbientMotionPhase.RECENT, fixture.gate.phase.value)
        fixture.clock.advanceBy(1)
        assertEquals(AmbientMotionPhase.IDLE, fixture.gate.phase.value)
        assertEquals(0, fixture.clock.pendingDelays)
        assertEquals(
            "the IDLE line names the last wake, 12,000 ms after it",
            InteractionMotionLogEvent(AmbientMotionPhase.IDLE, atMillis = 14_000, source = null, root = InteractionRoot.COMPANION),
            logged.last(),
        )
    }

    private fun fixture(logger: InteractionMotionLogger = InteractionMotionLogger {}): Fixture {
        val clock = FakeMonotonicDelay()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        return Fixture(
            clock = clock,
            gate = DefaultInteractionMotionGate(
                scope = scope,
                clock = clock,
                delayMillis = clock::delay,
                logger = logger,
            ),
        )
    }

    private fun adversarialFixture(): AdversarialFixture {
        val clock = AdversarialMonotonicDelay()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        return AdversarialFixture(
            clock = clock,
            gate = DefaultInteractionMotionGate(
                scope = scope,
                clock = clock,
                delayMillis = clock::delay,
            ),
        )
    }

    private data class Fixture(
        val clock: FakeMonotonicDelay,
        val gate: DefaultInteractionMotionGate,
    )

    private data class AdversarialFixture(
        val clock: AdversarialMonotonicDelay,
        val gate: DefaultInteractionMotionGate,
    )

    private class FakeMonotonicDelay : MonotonicClock {
        private data class Waiter(
            val deadlineMillis: Long,
            val continuation: CancellableContinuation<Unit>,
        )

        private val waiters = mutableListOf<Waiter>()
        private var nowMillis = 0L

        var startedDelays: Int = 0
            private set

        val pendingDelays: Int
            get() = waiters.count { it.continuation.isActive }

        override fun nowMillis(): Long = nowMillis

        suspend fun delay(durationMillis: Long) {
            if (durationMillis <= 0L) return
            startedDelays += 1
            suspendCancellableCoroutine { continuation ->
                val waiter = Waiter(nowMillis + durationMillis, continuation)
                waiters += waiter
                continuation.invokeOnCancellation { waiters.remove(waiter) }
            }
        }

        fun advanceBy(durationMillis: Long) {
            require(durationMillis >= 0L)
            nowMillis += durationMillis
            while (true) {
                val due = waiters.filter { it.deadlineMillis <= nowMillis }
                if (due.isEmpty()) return
                waiters.removeAll(due.toSet())
                due.forEach { waiter ->
                    if (waiter.continuation.isActive) waiter.continuation.resume(Unit)
                }
            }
        }
    }

    /** Retains canceled waiters so tests can deliver callbacks in hostile, out-of-order interleavings. */
    private class AdversarialMonotonicDelay : MonotonicClock {
        private data class Waiter(
            val id: Int,
            val deadlineMillis: Long,
            val continuation: Continuation<Unit>,
            var fired: Boolean = false,
        )

        private val waiters = mutableListOf<Waiter>()
        private val returnedWaiterIds = mutableListOf<Int>()
        private var nextId = 0
        private var nowMillis = 0L

        val waitingIds: List<Int>
            get() = waiters.filterNot(Waiter::fired).map(Waiter::id)

        val returnedFromDelayIds: List<Int>
            get() = returnedWaiterIds.toList()

        override fun nowMillis(): Long = nowMillis

        suspend fun delay(durationMillis: Long) {
            if (durationMillis <= 0L) return
            val waiterId = nextId++
            suspendCoroutine { continuation ->
                waiters += Waiter(
                    id = waiterId,
                    deadlineMillis = nowMillis + durationMillis,
                    continuation = continuation,
                )
            }
            returnedWaiterIds += waiterId
        }

        fun advanceTo(targetMillis: Long) {
            require(targetMillis >= nowMillis)
            nowMillis = targetMillis
        }

        fun fire(id: Int) {
            val waiter = waiters.single { it.id == id }
            check(!waiter.fired) { "Waiter $id already fired" }
            check(nowMillis >= waiter.deadlineMillis) {
                "Waiter $id is due at ${waiter.deadlineMillis}, now=$nowMillis"
            }
            waiter.fired = true
            waiter.continuation.resume(Unit)
        }

        fun singleWaiterId(): Int = waitingIds.single()

        fun latestWaiterId(): Int = waitingIds.last()
    }
}
