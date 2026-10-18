package com.gruning.mimir.rewrite

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch preflight is the senior caller of the one Tweaker Binder token.
 *
 * 2026-10-01 01:43:03 on the Thor: a HOME-press resume refresh held the reservation and the preflight logged
 * `Skipped(reason=Tweaker owner is still settling)` 0.18 s before that refresh finished. 09:41:35 on the Nova, with
 * the performance overlay on ALWAYS: the overlay's standing telemetry observation held the reservation for as long
 * as the overlay was enabled, the preflight refused to wait for it, and the selected profile was never applied. The
 * preflight now asks the holder to hand the token over, exactly as a settlement does.
 */
class TweakerLaunchReservationWaitTest {

    /** A fake clock the wait advances, so the bound is exact and nothing sleeps. */
    private class Clock {
        var now = 0L
        val elapsed: () -> Long = { now }
        val wait: suspend (Long) -> Unit = { now += it }
    }

    @Test
    fun `a free reservation is taken at once and nothing waits`() = runTest {
        val clock = Clock()
        val reservation = TweakerSessionReservation()

        val admission = awaitLaunchReservation(reservation, elapsedRealtimeMs = clock.elapsed, wait = clock.wait)

        assertTrue(admission.acquired)
        assertEquals(0L, admission.waitedMs)
        assertFalse("the preflight now owns the token", reservation.tryBeginObservation())
        reservation.endOwner()
        assertTrue("endOwner frees it completely", reservation.tryBeginObservation())
    }

    @Test
    fun `a refresh that ends inside the bound hands the token to the launch`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation()) // the resume refresh
        val clock = Clock()
        val admission = awaitLaunchReservation(
            reservation,
            elapsedRealtimeMs = clock.elapsed,
            wait = { clock.wait(it); if (clock.now >= 180L) reservation.endObservation() },
        )

        assertTrue(admission.acquired)
        assertTrue("waited ${admission.waitedMs}", admission.waitedMs in 180L..205L)
        assertFalse(reservation.tryBeginObservation())
        reservation.endOwner()
        assertTrue(reservation.tryBeginObservation())
    }

    /** The defect: an observation that is never going to end by itself, until it is asked to. */
    @Test
    fun `the overlay's standing observation yields to the launch instead of making it skip`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation()) // PerformanceOverlayService's telemetry observation
        val clock = Clock()
        var observerClosed = false

        val admission = awaitLaunchReservation(
            reservation,
            elapsedRealtimeMs = clock.elapsed,
            // The overlay's loop: each 1 s beat it asks shouldYieldObservation and closes when told to.
            wait = { clock.wait(it); if (clock.now % 1_000L == 0L && !observerClosed && reservation.shouldYieldObservation()) {
                observerClosed = true
                reservation.endObservation()
            } },
        )

        assertTrue("the launch must own the token after the observer's next beat", admission.acquired)
        assertTrue(observerClosed)
        assertTrue("waited ${admission.waitedMs}", admission.waitedMs in 1_000L..1_050L)
        assertFalse("the observer cannot retake it while the launch prepares", reservation.tryBeginObservation())
        reservation.endOwner()
        assertTrue(reservation.tryBeginObservation())
    }

    @Test
    fun `an observer that never yields is the only way a launch is still refused, and it withdraws its request`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation())
        val clock = Clock()

        val admission = awaitLaunchReservation(reservation, timeoutMs = 2_500L, elapsedRealtimeMs = clock.elapsed, wait = clock.wait)

        assertFalse(admission.acquired)
        assertTrue(admission.waitedMs in 2_500L..2_525L)
        assertFalse("a refused launch must not leave a hand-over request standing", reservation.shouldYieldObservation())
        // When the observer does end later, the token is simply free: nothing was left half-transferred.
        reservation.endObservation()
        assertTrue(reservation.tryBeginObservation())
    }

    @Test
    fun `an owner that ends inside the bound lets the launch in`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginOwner()) // e.g. the previous game's return settlement
        val clock = Clock()

        val admission = awaitLaunchReservation(
            reservation,
            elapsedRealtimeMs = clock.elapsed,
            wait = { clock.wait(it); if (clock.now >= 300L) reservation.endOwner() },
        )

        assertTrue(admission.acquired)
        assertTrue(admission.waitedMs in 300L..325L)
    }

    @Test
    fun `an owner that outlasts the bound refuses the launch and is never asked to leave`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginOwner())
        val clock = Clock()

        val admission = awaitLaunchReservation(reservation, timeoutMs = 1_000L, elapsedRealtimeMs = clock.elapsed, wait = clock.wait)

        assertFalse(admission.acquired)
        assertFalse(reservation.mutationMayBeInFlight())
        // Still the original owner's: its own release frees the token.
        assertTrue(reservation.endOwner())
        assertTrue(reservation.tryBeginObservation())
    }

    @Test
    fun `a settlement cannot join the launch's preparation and tear its binding down`() = runTest {
        val reservation = TweakerSessionReservation()
        val clock = Clock()
        assertTrue(awaitLaunchReservation(reservation, elapsedRealtimeMs = clock.elapsed, wait = clock.wait).acquired)

        assertFalse("PREPARATION is not joinable", reservation.reserveOwnerForSettlement())
        assertTrue(reservation.endOwner())
    }

    @Test
    fun `a settlement request parked in the hand-over slot does not cost the launch its turn`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation())
        // One hand-over slot per observation: a settlement parks in it first.
        assertEquals(false, reservation.reserveOwnerForSettlement())
        val clock = Clock()

        val admission = awaitLaunchReservation(
            reservation,
            elapsedRealtimeMs = clock.elapsed,
            // The observation ends at 100 ms; the settlement then owns the token until 400 ms, and releases it.
            wait = {
                clock.wait(it)
                if (clock.now == 100L) reservation.endObservation()
                if (clock.now >= 400L) reservation.endOwner()
            },
        )

        assertTrue(admission.acquired)
        assertTrue("waited ${admission.waitedMs}", admission.waitedMs >= 400L)
    }

    @Test
    fun `the wait is the settlement hand-over budget`() {
        assertEquals(
            TweakerSessionReservation.SETTLEMENT_OBSERVER_YIELD_TIMEOUT_MS,
            TweakerBridge.LAUNCH_RESERVATION_WAIT_MS,
        )
    }
}
