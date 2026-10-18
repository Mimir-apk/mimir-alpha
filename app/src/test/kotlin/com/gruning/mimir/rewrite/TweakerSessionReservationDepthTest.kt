package com.gruning.mimir.rewrite

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OWNER reservation's holder depth — the repair for the race introduced.
 *
 * The ruling put the discovered-record read on the SETTLEMENT reservation, which is the kind that may
 * join an existing generic owner. That made adoption work for the first time on the Thor. It also made
 * a joiner's release indistinguishable from the owner's: `requestOwner` returned ACQUIRED for a join
 * without recording anything, and `endOwner` then reset the reservation to IDLE whoever called it. At
 * cold start `readDiscoveredSessionAtBind` joins `confirmProcessStartOnce`'s generic owner and its own
 * `finally` released that owner's reservation mid-query.
 *
 * These run on the JVM deliberately and BEFORE any device build. Three repairs for this race were
 * written and certified on the Thor last session, each worse than the last, each reverted; a device
 * cycle is the most expensive way to discover a state-machine mistake. Everything asserted here is a
 * property of the state machine alone, so nothing here needs hardware to be true.
 */
class TweakerSessionReservationDepthTest {

    /**
     * THE REGRESSION. The hazard verbatim: a generic owner is live, a settlement read joins it, and the
     * read finishes. Before the depth, the third line freed a reservation the owner was still using.
     */
    @Test
    fun `a settlement read that joins a generic owner does not release it`() {
        val reservation = TweakerSessionReservation()

        assertTrue("the launch path takes the generic owner", reservation.tryBeginOwner())
        assertTrue("the discovered read joins it", reservation.reserveOwnerForSettlement())

        reservation.endOwner()

        assertFalse(
            "the joiner's finally must not hand the owner's reservation away mid-query",
            reservation.tryBeginObservation(),
        )
        assertFalse(reservation.tryBeginOwner())
    }

    /** And the owner's own release, when it comes, still frees the transport for everyone else. */
    @Test
    fun `the reservation is free once the last holder leaves`() {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginOwner())
        assertTrue(reservation.reserveOwnerForSettlement())

        reservation.endOwner()
        reservation.endOwner()

        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }

    /** Release order is not the caller's to coordinate: neither holder knows the other exists. */
    @Test
    fun `the owner releasing first leaves the joiner holding`() {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginOwner())
        assertTrue(reservation.reserveOwnerForSettlement())

        // Same two releases, opposite order — the owner unwinds before the read it never knew about.
        reservation.endOwner()
        assertFalse(reservation.tryBeginObservation())

        reservation.endOwner()
        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }

    /** Joins nest to whatever depth the callers actually reach, not to two. */
    @Test
    fun `three holders need three releases`() {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginOwner())
        assertTrue(reservation.reserveOwnerForSettlement())
        assertTrue(reservation.reserveOwnerForSettlement())

        reservation.endOwner()
        reservation.endOwner()
        assertFalse(reservation.tryBeginOwner())

        reservation.endOwner()
        assertTrue(reservation.tryBeginOwner())
        reservation.endOwner()
    }

    /**
     * A refused acquire counts nothing. This is the property that keeps the depth from turning a
     * rejection into a leak: an exclusive mutation is refused by a live generic owner, and the owner's
     * single release must still free the transport.
     */
    @Test
    fun `a rejected acquire adds no holder`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginOwner())

        assertFalse(
            reservation.awaitExclusiveOwnerForMutation(
                timeoutMs = 1L,
                elapsedRealtimeMs = { 0L },
                wait = { delay(it) },
            ),
        )

        reservation.endOwner()
        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }

    /**
     * A settlement may NOT join a live mutation, and the fence that says so must survive the attempt.
     * `mutationMayBeInFlight` reads `ownerKind`, so a join that renamed the owner to its own kind would
     * clear the standing re-arm watcher's fence out from under a player's own profile change.
     */
    @Test
    fun `a settlement cannot join a mutation and the fence survives the attempt`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(
            reservation.awaitExclusiveOwnerForMutation(
                elapsedRealtimeMs = { 0L },
                wait = { delay(it) },
            ),
        )
        assertTrue(reservation.mutationMayBeInFlight())

        assertFalse(reservation.reserveOwnerForSettlement())

        assertTrue("a refused join must not rename the owner", reservation.mutationMayBeInFlight())
        reservation.endOwner()
        assertFalse(reservation.mutationMayBeInFlight())
        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }

    /** A join must not rename a generic owner into a mutation either — the fence stays down. */
    @Test
    fun `a settlement joining a generic owner does not raise the mutation fence`() {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginOwner())
        assertTrue(reservation.reserveOwnerForSettlement())

        assertFalse(reservation.mutationMayBeInFlight())
    }

    /** An unbalanced release stays the no-op it has always been; it must never go negative. */
    @Test
    fun `a release with no owner is a no-op`() {
        val reservation = TweakerSessionReservation()

        reservation.endOwner()
        reservation.endOwner()

        assertTrue(reservation.tryBeginOwner())
        reservation.endOwner()
        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }

    /**
     * An owner handed the token by a yielding observer is the reservation's FIRST holder, not an extra
     * one: it never went through the acquire arm that counts.
     */
    @Test
    fun `an owner transferred from a yielding observation is a single holder`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation())

        val settlement = launch {
            reservation.awaitOwnerForSettlement(
                timeoutMs = 60_000L,
                elapsedRealtimeMs = { testScheduler.currentTime },
                wait = { delay(it) },
            )
        }
        runCurrent()
        assertTrue(reservation.shouldYieldObservation())

        reservation.endObservation()
        runCurrent()
        settlement.join()

        reservation.endOwner()

        assertTrue("one transfer, one release", reservation.tryBeginObservation())
        reservation.endObservation()
    }

    /**
     * The return path takes the token over wholesale. `transitionOwnerToObservation` collapses every
     * owner holder, so the single `endObservation` that follows is the whole release — this is the
     * shape `settleAfterReturnOwned` relies on, and the reason the transition does not decrement.
     */
    @Test
    fun `the transition to observation collapses every holder`() {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginOwner())
        assertTrue(reservation.reserveOwnerForSettlement())

        assertTrue(reservation.transitionOwnerToObservation())
        reservation.endObservation()

        assertTrue(reservation.tryBeginOwner())
        reservation.endOwner()
    }

    /**
     * The other half of the hazard: the shared Binder connection.
     *
     * `TweakerSession` is a single instance shared with `v4Owner`, and its `unbind()` is not refcounted
     * — it abandons the connection for every user. So a caller that may have JOINED someone else's
     * session must not tear that connection down, and the only thing that can tell it whether it may is
     * this release: true when the last holder left and the transport is genuinely free.
     */
    @Test
    fun `only the last release reports the transport free`() {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginOwner())
        assertTrue(reservation.reserveOwnerForSettlement())

        assertFalse(
            "a joiner must be told the transport is still in use, so it does not unbind",
            reservation.endOwner(),
        )
        assertTrue(
            "the last holder out owns the teardown",
            reservation.endOwner(),
        )
    }

    /** A sole owner is its own last holder, so it still owns the teardown. */
    @Test
    fun `a sole owner reports the transport free on release`() {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginOwner())

        assertTrue(reservation.endOwner())
    }

    /** An unbalanced release frees nothing and must never claim it did. */
    @Test
    fun `a release with no owner reports nothing freed`() {
        val reservation = TweakerSessionReservation()

        assertFalse(reservation.endOwner())

        assertTrue(reservation.tryBeginObservation())
        assertFalse("an observation is not an owner", reservation.endOwner())
        reservation.endObservation()
    }

    /**
     * The narrow door. `endObservation` hands the token over WITHOUT clearing `transferRequest` — only
     * `claimTransferredOwner` clears it — so for the length of the transfer window a second settlement
     * can join an owner that still has a request standing against it. If the dying waiter then reset the
     * reservation outright, it would free the transport under that joiner: the original hazard again,
     * reached through cancellation instead of through a `finally`.
     */
    @Test
    fun `a cancelled waiter does not free a reservation another caller joined`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation())

        val settlement = launch {
            reservation.awaitOwnerForSettlement(
                timeoutMs = 60_000L,
                elapsedRealtimeMs = { 0L },
                wait = { delay(it) },
            )
        }
        runCurrent()
        assertTrue(reservation.shouldYieldObservation())

        // The observer yields: the waiter is now the owner, but has not claimed the transfer yet.
        reservation.endObservation()
        // A second settlement read joins that owner inside the transfer window.
        assertTrue(reservation.reserveOwnerForSettlement())

        settlement.cancel()
        settlement.join()

        assertFalse(
            "the joiner is still mid-query; a dying waiter must not free the transport",
            reservation.tryBeginObservation(),
        )

        reservation.endOwner()
        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }

    /**
     * A waiter that was handed the token and then died unwinds the whole reservation rather than
     * leaving a holder behind — the depth must not survive a cancellation.
     */
    @Test
    fun `a cancelled waiter that was handed the token leaves nothing behind`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation())

        val settlement = launch {
            reservation.awaitOwnerForSettlement(
                timeoutMs = 60_000L,
                elapsedRealtimeMs = { 0L },
                wait = { delay(it) },
            )
        }
        runCurrent()
        assertTrue(reservation.shouldYieldObservation())

        reservation.endObservation()
        settlement.cancel()
        settlement.join()

        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }
}
