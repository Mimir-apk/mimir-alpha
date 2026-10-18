package com.gruning.mimir.rewrite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The standing re-arm answered `Busy` 1–2 ms after its dispatch (round 5c; found, not fixed; round 5e).
 *
 * The re-arm is dispatched from inside a projection refresh, and that refresh still holds the session
 * reservation as an observation. The reservation keeps ONE hand-over slot per observation. When a
 * settlement read is already parked in that slot, an exclusive mutation request is refused at once
 * (`TweakerSessionReservation.requestOwner` → REJECTED), and `mutateSystemProfile` returned `Busy`. On the
 * Thor on 2026-09-11 (`S06-logcat.txt`)
 * the read was the tuner cold-start reconcile's `DaemonTunerActuator.query()`: its daemon query landed
 * 46–62 ms after each `rearm … result=Busy`, on the thread that had queued it.
 *
 * The re-arm now waits out a refusal inside the hand-over budget it already had, so a short read no
 * longer costs the standing profile a failure, a backoff step, and the watch's 60 s confirm cadence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TweakerStandingRearmReservationTest {

    @Test
    fun `a re-arm that meets a settlement read queued on the refresh waits for the read instead of answering Busy`() =
        runTest {
            val reservation = TweakerSessionReservation()
            val clock = { testScheduler.currentTime }
            assertTrue(
                "the projection refresh that schedules the re-arm holds the observation",
                reservation.tryBeginObservation(),
            )
            val tunerRead = launch {
                assertTrue(reservation.awaitOwnerForSettlement(elapsedRealtimeMs = clock, wait = { delay(it) }))
                delay(TUNER_READ_MS)
                reservation.endOwner()
            }
            runCurrent()
            assertFalse(
                "the plain exclusive ask is refused at once while the read holds the hand-over slot: the 1-2 ms Busy",
                reservation.awaitExclusiveOwnerForMutation(elapsedRealtimeMs = clock, wait = { delay(it) }),
            )

            val rearm = async {
                awaitExclusiveOwnerForStandingRearm(
                    reservation = reservation,
                    launchOwnsTransport = { false },
                    elapsedRealtimeMs = clock,
                    wait = { delay(it) },
                )
            }
            runCurrent()
            advanceTimeBy(REFRESH_TAIL_MS)
            // The refresh unbinds and ends; the parked read takes the token, asks the daemon and leaves.
            reservation.endObservation()
            val result = rearm.await()
            tunerRead.join()

            assertTrue("the re-arm must get the reservation once the read is done, got $result", result.acquired)
            assertEquals(
                "the log line must name what refused the re-arm",
                "use=OBSERVATION owner=NONE handover=SETTLEMENT depth=0",
                result.refusedBy,
            )
            assertTrue("waited ${result.waitedMs} ms", result.waitedMs >= REFRESH_TAIL_MS + TUNER_READ_MS)
            assertTrue("waited ${result.waitedMs} ms", result.waitedMs <= HANDOVER_BUDGET_MS + POLL_MS)
            assertTrue("the re-arm holds the exclusive reservation now", reservation.mutationMayBeInFlight())
            assertTrue(reservation.endOwner())
        }

    @Test
    fun `a launch that takes the transport while the re-arm waits is never waited out`() = runTest {
        val reservation = TweakerSessionReservation()
        val clock = { testScheduler.currentTime }
        assertTrue("another owner holds the transport", reservation.tryBeginOwner())
        var launchOwnsTransport = false

        val rearm = async {
            awaitExclusiveOwnerForStandingRearm(
                reservation = reservation,
                launchOwnsTransport = { launchOwnsTransport },
                elapsedRealtimeMs = clock,
                wait = { delay(it) },
            )
        }
        runCurrent()
        advanceTimeBy(LAUNCH_AT_MS)
        launchOwnsTransport = true
        val result = rearm.await()

        assertFalse(result.acquired)
        assertEquals("use=OWNER owner=GENERIC handover=NONE depth=1", result.refusedBy)
        assertTrue(
            "the re-arm must stop when the launch takes the transport, not at its budget: waited ${result.waitedMs} ms",
            result.waitedMs in LAUNCH_AT_MS..(LAUNCH_AT_MS + POLL_MS),
        )
        assertFalse("nothing may be left parked on the reservation", reservation.mutationMayBeInFlight())
        assertTrue("the holder still owns the reservation", reservation.endOwner())
    }

    @Test
    fun `a re-arm gives up at the hand-over budget the re-arm worst case already counts`() = runTest {
        // Hardcoded rather than read from production, so a moved budget shows up here instead of hiding.
        assertEquals(HANDOVER_BUDGET_MS, TweakerSessionReservation.SETTLEMENT_OBSERVER_YIELD_TIMEOUT_MS)
        assertEquals(POLL_MS, TweakerSessionReservation.SETTLEMENT_OBSERVER_YIELD_POLL_MS)
        val reservation = TweakerSessionReservation()
        val clock = { testScheduler.currentTime }
        assertTrue("a holder that never lets go", reservation.tryBeginOwner())

        val result = awaitExclusiveOwnerForStandingRearm(
            reservation = reservation,
            launchOwnsTransport = { false },
            elapsedRealtimeMs = clock,
            wait = { delay(it) },
        )

        assertFalse(result.acquired)
        assertEquals("use=OWNER owner=GENERIC handover=NONE depth=1", result.refusedBy)
        assertTrue("gave up at ${result.waitedMs} ms", result.waitedMs >= HANDOVER_BUDGET_MS)
        assertTrue(
            "STANDING_REARM_WORST_CASE_MS counts one hand-over wait of 3,000 + 25 ms; waited ${result.waitedMs} ms",
            result.waitedMs <= HANDOVER_BUDGET_MS + POLL_MS,
        )
        assertFalse("nothing may be left parked on the reservation", reservation.mutationMayBeInFlight())
        assertTrue("the holder still owns the reservation", reservation.endOwner())
    }

    private companion object {
        const val HANDOVER_BUDGET_MS = 3_000L
        const val POLL_MS = 25L
        const val REFRESH_TAIL_MS = 40L
        const val TUNER_READ_MS = 60L
        const val LAUNCH_AT_MS = 200L
    }
}
