package com.gruning.mimir.rewrite

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nova 2026-10-01 22:08-22:09: choosing a profile in Utility answered "Tweaker is busy" three times. Each Busy came exactly
 * 3.0 s after the key press (19.700 -> 22.708, 02.969 -> 05.981), which is the hand-over wait the mutation gives an
 * observer to yield, so an OBSERVATION was held that never yielded; the daemon was silent for the whole minute and the
 * overlay's own reopen attempts fell back to kernel reads, so nothing was polling it either.
 *
 * The overlay opens its long-lived observation through `withContext`, and the overlay cancels an in-flight open on every
 * mode step (`collectLatest`). `withContext` discards the result of a cancelled caller, so an observation the open had
 * already taken (the reservation, and the binding) was dropped with nobody left to close it. The overlay had been stepped
 * seven times in the six seconds before the first press.
 */
class TweakerObservationHandoffTest {

    private fun observationOn(reservation: TweakerSessionReservation) = TweakerTelemetryObservation(
        readTelemetry = { null },
        shouldYield = reservation::shouldYieldObservation,
        release = { reservation.endObservation() },
    )

    /**
     * Cancels the caller while [open] is mid-flight on a worker thread (a blocking Binder call cannot be interrupted),
     * then lets the open finish, and returns once the caller has ended.
     */
    private fun cancelWhileOpening(
        run: suspend (open: suspend () -> TweakerTelemetryObservation?) -> TweakerTelemetryObservation?,
        reservation: TweakerSessionReservation,
    ) {
        val taken = CountDownLatch(1)
        val letOpenFinish = CountDownLatch(1)
        runBlocking {
            val caller = launch(Dispatchers.Default) {
                run {
                    assertTrue(reservation.tryBeginObservation())
                    taken.countDown()
                    assertTrue("the open was never let finish", letOpenFinish.await(10, TimeUnit.SECONDS))
                    observationOn(reservation)
                }
            }
            assertTrue("the open never took the reservation", taken.await(10, TimeUnit.SECONDS))
            caller.cancel()
            letOpenFinish.countDown()
            caller.join()
        }
    }

    @Test
    fun `a plain withContext open that is cancelled in flight leaks the reservation`() {
        val reservation = TweakerSessionReservation()

        cancelWhileOpening(run = { open -> withContext(Dispatchers.IO) { open() } }, reservation = reservation)

        // The mechanism, as it was: nobody holds the observation and nobody can release it.
        assertFalse("the reservation was expected to leak without the hand-off", reservation.isIdle())
    }

    @Test
    fun `an open cancelled in flight closes the observation it had taken`() {
        val reservation = TweakerSessionReservation()

        cancelWhileOpening(run = { open -> handOffObservation(Dispatchers.IO, open) }, reservation = reservation)

        assertTrue("the cancelled open must not keep the reservation", reservation.isIdle())
    }

    @Test
    fun `a profile selection after a cancelled open is admitted instead of answering Busy`() = runBlocking {
        val reservation = TweakerSessionReservation()
        cancelWhileOpening(run = { open -> handOffObservation(Dispatchers.IO, open) }, reservation = reservation)

        var now = 0L
        val admitted = reservation.awaitExclusiveOwnerForMutation(
            elapsedRealtimeMs = { now },
            wait = { delay(1); now += it },
        )

        assertTrue(admitted)
        assertTrue(reservation.mutationMayBeInFlight())
    }

    @Test
    fun `an open that completes hands the observation over still open`() = runBlocking {
        val reservation = TweakerSessionReservation()

        val observation = handOffObservation(Dispatchers.IO) {
            assertTrue(reservation.tryBeginObservation())
            observationOn(reservation)
        }

        assertNotNull(observation)
        assertFalse("a delivered observation keeps the reservation until its owner closes it", reservation.isIdle())
        observation!!.close()
        assertTrue(reservation.isIdle())
    }

    @Test
    fun `an open that yields nothing hands over nothing`() = runBlocking {
        assertEquals(null, handOffObservation<TweakerTelemetryObservation>(Dispatchers.IO) { null })
    }

    /**
     * The shape the lead asked for: Utility's beat is reading telemetry under an observation when a profile press
     * arrives. The press parks for the hand-over, the beat ends, and the press owns the reservation: Active, not Busy.
     */
    @Test
    fun `a press arriving during a panel beat is handed the reservation when the beat ends`() = runBlocking {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation())

        var now = 0L
        var polls = 0
        val admitted = reservation.awaitExclusiveOwnerForMutation(
            elapsedRealtimeMs = { now },
            wait = {
                now += it
                polls += 1
                // The beat notices the parked mutation, skips its read, and releases.
                if (reservation.shouldYieldObservation()) reservation.endObservation()
            },
        )

        assertTrue(admitted)
        assertTrue("the hand-over took ${now} ms of the 3,000 ms budget", now < TweakerSessionReservation.SETTLEMENT_OBSERVER_YIELD_TIMEOUT_MS)
        assertTrue(polls >= 1)
    }

    @Test
    fun `an observation that never yields still ends the press as Busy after the hand-over budget`() = runBlocking {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation())

        var now = 0L
        val admitted = reservation.awaitExclusiveOwnerForMutation(
            elapsedRealtimeMs = { now },
            wait = { now += it },
        )

        assertFalse(admitted)
        assertTrue(now >= TweakerSessionReservation.SETTLEMENT_OBSERVER_YIELD_TIMEOUT_MS)
    }
}
