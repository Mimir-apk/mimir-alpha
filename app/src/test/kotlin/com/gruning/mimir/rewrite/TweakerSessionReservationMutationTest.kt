package com.gruning.mimir.rewrite

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `mutationMayBeInFlight` — the guard that keeps the standing re-arm watcher out of a player's own
 * profile change.
 *
 * The watcher's projection refresh checks the launch guards but
 * originally not the reservation. Piggybacking a bound session cannot race the holder at the *service*,
 * but it can in the *projection*: the refresh rewrites capabilities/telemetry and can persist a
 * transitional Stock through `reconcileStoredSelection` while a Stock-first switch is mid-flight, then
 * dispatch a doomed re-arm on top of it. The player's own change outranks the watcher, always.
 */
class TweakerSessionReservationMutationTest {

    @Test
    fun `an idle reservation reports no mutation`() {
        assertFalse(TweakerSessionReservation().mutationMayBeInFlight())
    }

    /** A plain telemetry observer is not a mutation; the watcher may piggyback it freely. */
    @Test
    fun `a telemetry observation is not a mutation`() {
        val reservation = TweakerSessionReservation()

        assertTrue(reservation.tryBeginObservation())

        assertFalse(reservation.mutationMayBeInFlight())
    }

    @Test
    fun `a generic owner is not a mutation`() {
        val reservation = TweakerSessionReservation()

        assertTrue(reservation.tryBeginOwner())

        assertFalse(reservation.mutationMayBeInFlight())
    }

    @Test
    fun `an owning mutation is reported`() = runTest {
        val reservation = TweakerSessionReservation()

        assertTrue(
            reservation.awaitExclusiveOwnerForMutation(
                elapsedRealtimeMs = { 0L },
                wait = { delay(it) },
            ),
        )

        assertTrue(reservation.mutationMayBeInFlight())
    }

    /**
     * The case the guard actually exists for: a mutation has asked the live observer to yield and is
     * about to take the transport. The observation is still nominally the holder, so a check that only
     * looked at the current owner would wave the watcher straight into the handover.
     */
    @Test
    fun `an observer already asked to yield to a mutation counts as in flight`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation())
        assertFalse(reservation.mutationMayBeInFlight())

        val pending = launch {
            reservation.awaitExclusiveOwnerForMutation(
                timeoutMs = 60_000L,
                elapsedRealtimeMs = { 0L },
                wait = { delay(it) },
            )
        }
        runCurrent()

        assertTrue(
            "a pending mutation handover must fence the watcher",
            reservation.mutationMayBeInFlight(),
        )
        assertTrue(reservation.shouldYieldObservation())

        pending.cancel()
    }

    /** Once the mutation is done the fence lifts and the watcher may refresh again. */
    @Test
    fun `the fence lifts when the mutation releases`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(
            reservation.awaitExclusiveOwnerForMutation(
                elapsedRealtimeMs = { 0L },
                wait = { delay(it) },
            ),
        )
        assertTrue(reservation.mutationMayBeInFlight())

        reservation.endOwner()

        assertFalse(reservation.mutationMayBeInFlight())
    }
}
