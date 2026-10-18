package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D34b (security): the trackpad's injection rules, exercised through a recording fake with manually completed strokes.
 * No framework: every rule is plain floats, tokens and callbacks.
 *
 *  1. injection needs a live session and a screen that is on, checked before EVERY segment of a press;
 *  2. a lift from an old session never releases a newer press;
 *  3. every command carries the token its caller captured; a stale one is dropped even after a new activation;
 *  4. a lift or end while a continuation is in flight still sends the final release;
 *  5. a continuation starts exactly where the previous segment ended (the fake enforces Android's rule) and only the
 *     new destination is clamped to display 0's live bounds.
 */
class MimirTrackpadInjectorTest {

    private data class Seg(
        val continues: Boolean,
        val from: Pair<Float, Float>,
        val to: Pair<Float, Float>,
        val willContinue: Boolean,
        /** Android's MotionEventInjector rule: a continuation must start where its previous segment ended. */
        val valid: Boolean,
    )

    private class FakeStrokes : TrackpadStrokes<Seg> {
        val dispatched = mutableListOf<Seg>()
        private val pending = ArrayDeque<(Boolean) -> Unit>()

        override fun stroke(
            previous: Seg?,
            fromX: Float,
            fromY: Float,
            toX: Float,
            toY: Float,
            durationMs: Long,
            willContinue: Boolean,
        ) = Seg(
            continues = previous != null,
            from = fromX to fromY,
            to = toX to toY,
            willContinue = willContinue,
            valid = previous == null || (fromX to fromY) == previous.to,
        )

        override fun dispatch(stroke: Seg, what: String, done: (completed: Boolean) -> Unit) {
            dispatched += stroke
            // Android cancels a continuation that does not start at the previous end.
            if (!stroke.valid) done(false) else pending += done
        }

        /** Completes the oldest stroke still in flight. */
        fun complete(completed: Boolean = true) = pending.removeFirst()(completed)

        val inFlight: Int get() = pending.size
    }

    private val strokes = FakeStrokes()
    private var size = 1000 to 2000
    private var screenOn = true
    private var locked = false
    private var margins = GestureMargins.None
    private val injector = MimirTrackpadInjector(
        strokes = strokes,
        displaySize = { size },
        screenOn = { screenOn },
        naturalScroll = { true },
        keyguardLocked = { locked },
        gestureMargins = { margins },
    )

    // ---- the session guard ----------------------------------------------------------------------------------------

    @Test
    fun aCallBeforeActivationInjectsNothing() {
        assertFalse(injector.click(1L))
        injector.doubleClick(1L)
        assertEquals(0L, injector.holdBegin(1L))
        injector.scrollBy(1L, 0f, 80f)
        injector.scrollEnd(1L)
        assertEquals(emptyList<Seg>(), strokes.dispatched)
        assertFalse(injector.active)
    }

    @Test
    fun aStaleCallAfterEndInjectsNothing() {
        val token = injector.activate()
        assertTrue(injector.click(token))
        assertEquals("a live session clicks", 1, strokes.dispatched.size)

        assertTrue(injector.end(token))
        assertFalse(injector.click(token))
        injector.doubleClick(token)
        assertEquals(0L, injector.holdBegin(token))
        injector.scrollBy(token, 0f, 80f)
        injector.scrollEnd(token)
        assertEquals("nothing after end", 1, strokes.dispatched.size)
        assertFalse(injector.active)
    }

    @Test
    fun aScreenThatIsOffInjectsNothing() {
        val token = injector.activate()
        screenOn = false
        injector.click(token)
        injector.holdBegin(token)
        injector.scrollBy(token, 0f, 80f)
        injector.scrollEnd(token)
        assertEquals(0, strokes.dispatched.size)
        assertFalse("the pointer is not drawn for a screen that is off", injector.active)
        screenOn = true
        injector.click(token)
        assertEquals(1, strokes.dispatched.size)
    }

    // ---- token isolation (item 3) -----------------------------------------------------------------------------------

    @Test
    fun endThenActivateThenAStaleClickIsDroppedAndAFreshOneGoes() {
        val first = injector.activate()
        injector.end(first)
        val second = injector.activate()
        assertTrue("a new activation has a new token", second != first)

        assertFalse("the stale token is dropped even though a session is live", injector.click(first))
        injector.doubleClick(first)
        assertEquals(0L, injector.holdBegin(first))
        injector.scrollBy(first, 0f, 80f)
        injector.scrollEnd(first)
        assertEquals(0, strokes.dispatched.size)

        assertTrue(injector.click(second))
        assertEquals(1, strokes.dispatched.size)
    }

    @Test
    fun aStaleTokenCannotMoveThePointerOrEndTheLiveSession() {
        val first = injector.activate()
        val second = injector.activate()
        val before = injector.pointer!!
        assertFalse(injector.move(first, 50f, 50f))
        assertEquals(before, injector.pointer)
        assertFalse("a stale end changes nothing", injector.end(first))
        assertTrue("the live session is still live", injector.click(second))
    }

    @Test
    fun anActivationEndsASessionThatWasStillOpenAndReleasesItsPress() {
        val first = injector.activate()
        injector.holdBegin(first)
        strokes.complete()
        val second = injector.activate()
        // The old press is released; the new session cannot touch it.
        assertEquals(2, strokes.dispatched.size)
        assertFalse(strokes.dispatched.last().willContinue)
        assertFalse(injector.click(first))
        assertTrue(injector.click(second))
    }

    // ---- hold isolation (item 2) ------------------------------------------------------------------------------------

    @Test
    fun aLiftFromAnOldSessionNeverReleasesTheNewHold() {
        val first = injector.activate()
        val holdA = injector.holdBegin(first)
        strokes.complete()
        injector.end(first)
        strokes.complete() // A's release lands
        assertEquals(2, strokes.dispatched.size)

        val second = injector.activate()
        val holdB = injector.holdBegin(second)
        assertTrue(holdB != 0L && holdB != holdA)
        strokes.complete()
        val beforeStaleLift = strokes.dispatched.size

        // A's lift arrives late.
        injector.holdEnd(first, holdA)
        assertEquals("B was not released", beforeStaleLift, strokes.dispatched.size)

        // B is still down: it can still be dragged, and only B's own lift releases it.
        injector.holdMove(second, holdB, 5f, 5f)
        assertEquals(beforeStaleLift + 1, strokes.dispatched.size)
        assertTrue(strokes.dispatched.last().willContinue)
        strokes.complete()
        injector.holdEnd(second, holdB)
        assertFalse(strokes.dispatched.last().willContinue)
        assertEquals(beforeStaleLift + 2, strokes.dispatched.size)
    }

    @Test
    fun aLiftNamingAnotherPressOrNoPressReleasesNothing() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        strokes.complete()
        injector.holdEnd(token, id + 1)
        injector.holdEnd(token, 0L)
        assertEquals(1, strokes.dispatched.size)
        injector.holdEnd(token, id)
        assertEquals(2, strokes.dispatched.size)
    }

    // ---- the pump checks session and screen before every segment (item 1) -------------------------------------------

    @Test
    fun aQueuedMoveThenScreenOffThenTheInFlightSegmentLandingSendsOnlyTheRelease() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        injector.holdMove(token, id, 10f, 0f) // queued behind the in-flight first segment
        assertEquals(1, strokes.dispatched.size)

        screenOn = false
        strokes.complete()
        assertEquals("the queued move was not sent", 2, strokes.dispatched.size)
        val release = strokes.dispatched.last()
        assertFalse("the only thing sent is the owed release", release.willContinue)
        assertTrue(release.valid)
        strokes.complete()
        assertEquals(0, strokes.inFlight)
    }

    @Test
    fun aRevokedSessionWithAQueuedMoveSendsOnlyTheRelease() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        injector.holdMove(token, id, 10f, 0f)
        injector.end(token)
        strokes.complete()
        assertEquals(2, strokes.dispatched.size)
        assertFalse(strokes.dispatched.last().willContinue)
    }

    @Test
    fun aScreenThatIsOffWithNoPressDownSendsNothingFromThePump() {
        val token = injector.activate()
        screenOn = false
        injector.holdEnd(token, 1L)
        assertEquals(0, strokes.dispatched.size)
    }

    @Test
    fun aHoldMoveAfterEndIsDroppedButTheOwedReleaseStillGoes() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        strokes.complete()
        injector.end(token)
        assertEquals(2, strokes.dispatched.size)
        assertFalse(strokes.dispatched.last().willContinue)
        injector.holdMove(token, id, 30f, 30f)
        strokes.complete()
        assertEquals("a stale hold move after end is dropped", 2, strokes.dispatched.size)
        assertEquals(0, strokes.inFlight)
    }

    // ---- the release is never lost ----------------------------------------------------------------------------------

    @Test
    fun aLiftWhileAContinuationIsInFlightStillSendsTheRelease() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        strokes.complete()
        injector.holdMove(token, id, 10f, 0f)
        assertEquals("the move is in flight", 2, strokes.dispatched.size)
        assertTrue(strokes.dispatched.last().willContinue)

        injector.holdEnd(token, id)
        strokes.complete()
        assertEquals("the in-flight move finishing sends the release", 3, strokes.dispatched.size)
        assertFalse("the release lets go", strokes.dispatched.last().willContinue)
        assertTrue(strokes.dispatched.last().continues)
        strokes.complete()

        // The hold is over: a fresh hold can start.
        injector.holdBegin(token)
        assertEquals(4, strokes.dispatched.size)
        assertFalse("a new press, not a continuation", strokes.dispatched.last().continues)
    }

    @Test
    fun aCancelledContinuationWithALiftOwedStillSendsTheRelease() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        strokes.complete()
        injector.holdMove(token, id, 10f, 0f)
        injector.holdEnd(token, id)
        strokes.complete(completed = false)
        assertEquals(3, strokes.dispatched.size)
        assertFalse(strokes.dispatched.last().willContinue)
    }

    @Test
    fun aLiftWhileTheFirstSegmentIsInFlightReleasesToo() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        injector.holdEnd(token, id)
        strokes.complete()
        assertEquals(2, strokes.dispatched.size)
        assertFalse(strokes.dispatched.last().willContinue)
    }

    @Test
    fun endWhileAContinuationIsInFlightReleasesWhenItLands() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        strokes.complete()
        injector.holdMove(token, id, 10f, 0f)
        injector.end(token)
        strokes.complete()
        assertEquals(3, strokes.dispatched.size)
        assertFalse(strokes.dispatched.last().willContinue)
    }

    @Test
    fun aReleaseIsSentOnceNoMatterHowManyLiftsArrive() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        strokes.complete()
        injector.holdEnd(token, id)
        injector.holdEnd(token, id)
        injector.end(token)
        strokes.complete()
        assertEquals(2, strokes.dispatched.size)
    }

    // ---- continuity and clamping (item 4) ---------------------------------------------------------------------------

    @Test
    fun aContinuationStartsWhereThePreviousSegmentEndedEvenAfterTheBoundsShrink() {
        val token = injector.activate()
        injector.move(token, 400f, 800f)
        val id = injector.holdBegin(token)
        strokes.complete()
        val pressedAt = strokes.dispatched.first().to
        assertTrue(pressedAt.first > 100f && pressedAt.second > 100f)

        // Rotation: display 0 becomes much smaller than where the press is.
        size = 300 to 300
        injector.holdMove(token, id, 5f, 5f)
        strokes.complete()
        injector.holdEnd(token, id)
        strokes.complete()

        assertTrue("Android would have cancelled a segment: ${strokes.dispatched}", strokes.dispatched.all { it.valid })
        val drag = strokes.dispatched[1]
        assertEquals("the drag starts at the press, verbatim", pressedAt, drag.from)
        assertTrue("the new destination is clamped to the live bounds", drag.to.first in 0f..299f && drag.to.second in 0f..299f)
        val release = strokes.dispatched.last()
        assertFalse("the release landed", release.willContinue)
        assertEquals("the release starts at the drag's end", drag.to, release.from)
        assertTrue(release.to.first in 0f..299f && release.to.second in 0f..299f)
        assertEquals(3, strokes.dispatched.size)
    }

    @Test
    fun aReleaseAfterTheBoundsShrinkStartsAtThePreviousEndAndLands() {
        val token = injector.activate()
        injector.move(token, 400f, 800f)
        val id = injector.holdBegin(token)
        strokes.complete()
        val pressedAt = strokes.dispatched.first().to
        size = 300 to 300
        injector.holdEnd(token, id)
        strokes.complete()
        val release = strokes.dispatched.last()
        assertFalse(release.willContinue)
        assertTrue(release.valid)
        assertEquals(pressedAt, release.from)
        assertTrue(release.to.first in 0f..299f && release.to.second in 0f..299f)
    }

    @Test
    fun everyDragSegmentChainsFromThePreviousEnd() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        strokes.complete()
        repeat(5) { step ->
            injector.holdMove(token, id, 10f + step, 3f)
            strokes.complete()
        }
        injector.holdEnd(token, id)
        strokes.complete()
        assertTrue(strokes.dispatched.all { it.valid })
        strokes.dispatched.zipWithNext { a, b -> assertEquals(a.to, b.from) }
        assertFalse(strokes.dispatched.last().willContinue)
    }

    // ---- full-screen pad (2026-10-06): the session outlives clicks, and an interruption only lets go of a press ----------

    @Test
    fun anInjectedClickAndAScrollNeverEndTheSession() {
        val token = injector.activate()
        assertTrue(injector.click(token))
        injector.scrollBy(token, 0f, 60f)
        injector.scrollEnd(token)
        assertTrue("the pad's activation is still live after a click and a scroll", injector.isLive(token))
        assertTrue(injector.click(token))
    }

    @Test
    fun releaseHoldLetsGoOfAHeldPressAndKeepsTheSessionLive() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        strokes.complete()
        injector.releaseHold()
        assertEquals("the owed finger-up went", 2, strokes.dispatched.size)
        assertFalse(strokes.dispatched.last().willContinue)
        strokes.complete()
        assertTrue("the service interruption does not end the pad's activation", injector.isLive(token))
        injector.holdEnd(token, id)
        assertEquals("a late lift for the released press sends nothing more", 2, strokes.dispatched.size)
        assertTrue(injector.click(token))
    }

    @Test
    fun releaseHoldWithNoPressDownSendsNothing() {
        val token = injector.activate()
        injector.releaseHold()
        assertEquals(0, strokes.dispatched.size)
        assertTrue(injector.isLive(token))
    }

    // ---- pad1 fix round: a lock is not a screen-off, and ends injection all the same ---------------------------------

    @Test
    fun aKeyguardOverAnInteractiveScreenDropsEveryInjection() {
        val token = injector.activate()
        locked = true
        assertFalse("screen on, keyguard up: no click", injector.click(token))
        injector.doubleClick(token)
        injector.scrollBy(token, 0f, 80f)
        injector.scrollEnd(token)
        assertEquals(0L, injector.holdBegin(token))
        assertEquals(0, strokes.dispatched.size)
        assertFalse(injector.isLive(token))
        assertFalse("the pointer is not drawn over a lock", injector.active)
        locked = false
        assertTrue("unlocked again, the same live session works", injector.click(token))
    }

    @Test
    fun aLockMidHoldSendsOnlyTheOwedRelease() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        injector.holdMove(token, id, 10f, 0f) // queued behind the in-flight first segment
        assertEquals(1, strokes.dispatched.size)
        locked = true
        strokes.complete()
        assertEquals("the queued move was not sent", 2, strokes.dispatched.size)
        assertFalse("only the final release goes", strokes.dispatched.last().willContinue)
        assertTrue(strokes.dispatched.last().valid)
        strokes.complete()
        assertEquals(0, strokes.inFlight)
    }

    @Test
    fun aLockThenAFingerUpReleasesInPlaceNotAtTheQueuedPosition() {
        val token = injector.activate()
        val id = injector.holdBegin(token)
        injector.holdMove(token, id, 10f, 0f) // queued behind the in-flight first segment
        locked = true
        injector.holdEnd(token, id) // the finger lifts while the device is already locked
        strokes.complete()
        assertEquals("the queued move was dropped; only the release went", 2, strokes.dispatched.size)
        val release = strokes.dispatched.last()
        assertFalse(release.willContinue)
        assertEquals("the release starts where the press is", strokes.dispatched.first().to, release.from)
        assertEquals("and ends there: it is stationary", release.from, release.to)
        strokes.complete()
        assertEquals(0, strokes.inFlight)
    }

    // ---- pad3: a stroke never STARTS in a system-gesture zone (an edge start is the Back gesture) ---------------------

    private fun zones() = TrackpadGestureZones.margins(density = 2f) // left/right 96 px, bottom 64 px on this 1000 x 2000 display

    @Test
    fun theNamedZonesAre48dpOnTheSidesAnd32dpAtTheBottomAndThePlatformMayWidenThem() {
        val floor = TrackpadGestureZones.margins(density = 2f)
        assertEquals(96f, floor.left, 0.01f)
        assertEquals(96f, floor.right, 0.01f)
        assertEquals(64f, floor.bottom, 0.01f)
        assertEquals(0f, floor.top, 0.01f)
        val wider = TrackpadGestureZones.margins(density = 2f, apiLeft = 120f, apiRight = 20f, apiBottom = 100f)
        assertEquals("the platform's inset wins where larger", 120f, wider.left, 0.01f)
        assertEquals("the floor wins where larger", 96f, wider.right, 0.01f)
        assertEquals(100f, wider.bottom, 0.01f)
    }

    @Test
    fun safeStrokeStartMovesInwardAndLeavesAnInteriorPointAlone() {
        val m = zones()
        assertEquals(96f to 1000f, safeStrokeStart(0f, 1000f, 1000, 2000, m))
        assertEquals(903f to 1000f, safeStrokeStart(999f, 1000f, 1000, 2000, m))
        assertEquals(500f to 1935f, safeStrokeStart(500f, 1999f, 1000, 2000, m))
        assertEquals(500f to 1000f, safeStrokeStart(500f, 1000f, 1000, 2000, m))
        assertEquals("a display too narrow for both zones starts in the middle", 49.5f to 1000f, safeStrokeStart(0f, 1000f, 100, 2000, m))
    }

    // ---- pad14: a swipe that ends while another is in flight never truncates it ---------------------------------------------

    @Test
    fun aTwoFingerScrollEndingDuringADrumBurstWaitsForItInsteadOfTruncatingItAndLosesNothing() {
        val token = injector.activate()
        // The drum's burst goes out: one swipe in flight.
        injector.scrollBy(token, 0f, -100f)
        injector.scrollEnd(token)
        assertEquals(1, strokes.dispatched.size)
        // The field's two-finger scroll ends before it has landed: the platform would cancel the burst, so this one waits.
        injector.scrollBy(token, 0f, -50f)
        injector.scrollEnd(token)
        assertEquals("never two swipes in flight: the second would cancel the first", 1, strokes.inFlight)
        assertEquals("and the second is not dispatched yet", 1, strokes.dispatched.size)
        strokes.complete()
        assertEquals("the moment the burst lands the waiting swipe goes", 2, strokes.dispatched.size)
        assertEquals("the burst was whole: -100 x 2.2", -220f, strokes.dispatched[0].to.second - strokes.dispatched[0].from.second, 0.01f)
        assertEquals("and the waiting one is its own steps only: -50 x 2.2", -110f, strokes.dispatched[1].to.second - strokes.dispatched[1].from.second, 0.01f)
    }

    @Test
    fun swipesThatWaitAreMergedSoNothingIsLostAndNothingIsSentTwice() {
        val token = injector.activate()
        injector.scrollBy(token, 0f, -100f)
        injector.scrollEnd(token)
        repeat(3) {
            injector.scrollBy(token, 0f, -20f)
            injector.scrollEnd(token)
        }
        assertEquals(1, strokes.dispatched.size)
        strokes.complete()
        assertEquals("one merged swipe, not three", 2, strokes.dispatched.size)
        assertEquals("of exactly what waited: 3 x -20 x 2.2", -132f, strokes.dispatched[1].to.second - strokes.dispatched[1].from.second, 0.01f)
        strokes.complete()
        assertEquals("nothing more is sent after it lands", 2, strokes.dispatched.size)
    }

    @Test
    fun aSwipeThatWaitsIsDroppedWithItsSessionAndAScrollCancelKeepsWhatAlreadyEnded() {
        val token = injector.activate()
        injector.scrollBy(token, 0f, -100f)
        injector.scrollEnd(token)
        injector.scrollBy(token, 0f, -50f)
        injector.scrollEnd(token)
        // A later cancel drops only the unsent accumulator, not a swipe that already ended and waits.
        injector.scrollBy(token, 0f, -30f)
        injector.scrollCancel(token)
        strokes.complete()
        assertEquals("the ended swipe still goes", 2, strokes.dispatched.size)
        assertEquals(-110f, strokes.dispatched[1].to.second - strokes.dispatched[1].from.second, 0.01f)
        strokes.complete()
        // And one that waits when its session ends is never sent.
        val second = injector.activate()
        injector.scrollBy(second, 0f, -100f)
        injector.scrollEnd(second)
        injector.scrollBy(second, 0f, -50f)
        injector.scrollEnd(second)
        injector.end(second)
        val before = strokes.dispatched.size
        strokes.complete()
        assertEquals("the session ended: nothing waits behind the landed swipe", before, strokes.dispatched.size)
    }

    // ---- pad14 fix round: one in-flight slot, and a callback is only ever its own swipe's ---------------------------------------

    private fun swipe(token: Long, dy: Float) {
        injector.scrollBy(token, 0f, dy)
        injector.scrollEnd(token)
    }

    @Test
    fun aStaleCallbackFromAPreviousActivationNeverClearsTheNewSwipesInFlightFlag() {
        val first = injector.activate()
        swipe(first, -100f)
        val second = injector.activate()
        swipe(second, -100f)
        assertEquals("A and B both dispatched (a new session starts clean)", 2, strokes.dispatched.size)
        strokes.complete(false) // the OLD swipe's callback lands late
        swipe(second, -40f)
        assertEquals("B is still in flight, so the next swipe waits instead of truncating it", 2, strokes.dispatched.size)
        strokes.complete()
        assertEquals("and goes when B itself lands", 3, strokes.dispatched.size)
    }

    @Test
    fun aCallbackOfAnOlderSwipeIdIsIgnoredEvenInTheSameSession() {
        val token = injector.activate()
        swipe(token, -100f)
        injector.click(token) // cancels A (the platform's rule) and retires it
        swipe(token, -60f) // B goes at once: the slot is the click's, not a swipe's
        assertEquals(3, strokes.dispatched.size)
        strokes.complete(false) // A's cancel arrives after B was dispatched
        swipe(token, -30f)
        assertEquals("B is still in flight: the next one waits", 3, strokes.dispatched.size)
    }

    @Test
    fun aClickWhileASwipeFliesAndAnotherWaitsLandsAndTheWaitingOneIsDroppedAndNothingCancelsTheClick() {
        val token = injector.activate()
        swipe(token, -100f)
        swipe(token, -50f) // waits
        injector.click(token)
        assertEquals("A and the click", 2, strokes.dispatched.size)
        strokes.complete(false) // A is cancelled by our click
        assertEquals("the waiting swipe is not sent on top of the click", 2, strokes.dispatched.size)
        assertEquals("only the click is in flight", 1, strokes.inFlight)
        strokes.complete()
        assertEquals("and it is never sent later either", 2, strokes.dispatched.size)
    }

    @Test
    fun aHoldStartedWhileASwipeWaitsWinsAndTheSwipeIsDropped() {
        val token = injector.activate()
        swipe(token, -100f)
        swipe(token, -50f) // waits
        assertTrue(injector.holdBegin(token) != 0L)
        assertEquals("A and the hold's first segment", 2, strokes.dispatched.size)
        strokes.complete(false) // A cancelled by the hold
        assertEquals("the waiting swipe does not cancel the hold", 2, strokes.dispatched.size)
        assertTrue("the hold's own segment is still the one in flight", strokes.dispatched.last().willContinue)
    }

    @Test
    fun aSwipeCancelledByTheSystemWithNothingNewerStillDrainsItsQueue() {
        val token = injector.activate()
        swipe(token, -100f)
        swipe(token, -50f)
        strokes.complete(false) // cancelled from outside: no newer dispatch of ours
        assertEquals("the waiting swipe goes", 2, strokes.dispatched.size)
    }

    @Test
    fun aSwipeWithNothingInFlightIsSentAtOnceAsAlways() {
        val token = injector.activate()
        injector.scrollBy(token, 0f, -100f)
        injector.scrollEnd(token)
        assertEquals(1, strokes.dispatched.size)
        strokes.complete()
        injector.scrollBy(token, 0f, -100f)
        injector.scrollEnd(token)
        assertEquals("a landed swipe never holds the next back", 2, strokes.dispatched.size)
    }

    @Test
    fun aScrollWithThePointerAtTheLeftEdgeStartsInsideTheZoneKeepingDirectionAndLength() {
        margins = zones()
        val token = injector.activate()
        injector.move(token, -5000f, 0f)
        assertEquals(0f, injector.pointer!!.x, 0.01f)
        injector.scrollBy(token, 0f, -100f)
        injector.scrollEnd(token)
        val stroke = strokes.dispatched.last()
        assertTrue("starts at or right of the 96 px zone: ${stroke.from}", stroke.from.first >= 96f)
        assertEquals("the same direction and length (-100 x 2.2)", -220f, stroke.to.second - stroke.from.second, 0.01f)
        assertEquals("the stroke only moved sideways to clear the zone", stroke.from.first, stroke.to.first, 0.01f)
    }

    @Test
    fun aScrollWithThePointerAtTheRightEdgeStartsInsideTheZone() {
        margins = zones()
        val token = injector.activate()
        injector.move(token, 5000f, 0f)
        injector.scrollBy(token, 0f, 80f)
        injector.scrollEnd(token)
        val stroke = strokes.dispatched.last()
        assertTrue("starts at or left of the zone: ${stroke.from}", stroke.from.first <= 999f - 96f)
        assertEquals(176f, stroke.to.second - stroke.from.second, 0.01f)
    }

    @Test
    fun aScrollWithThePointerAtTheBottomStartsAboveTheBottomZone() {
        margins = zones()
        val token = injector.activate()
        injector.move(token, 0f, 5000f)
        injector.scrollBy(token, 0f, -100f)
        injector.scrollEnd(token)
        val stroke = strokes.dispatched.last()
        assertTrue("starts above the 64 px bottom zone: ${stroke.from}", stroke.from.second <= 1999f - 64f)
        assertEquals("direction and length kept", -220f, stroke.to.second - stroke.from.second, 0.01f)
    }

    @Test
    fun aClickAtTheEdgeStaysAClickAtThePointer() {
        margins = zones()
        val token = injector.activate()
        injector.move(token, -5000f, 0f)
        assertTrue(injector.click(token))
        val tap = strokes.dispatched.last()
        assertEquals("the click is where the pointer is, edge and all", 0f, tap.from.first, 0.01f)
        assertEquals(tap.from, tap.to)
    }

    @Test
    fun aHoldPressedAtTheEdgeBeginsInsideTheZoneAndDragsWithThatOffset() {
        margins = zones()
        val token = injector.activate()
        injector.move(token, -5000f, 0f)
        val id = injector.holdBegin(token)
        val press = strokes.dispatched.first()
        assertTrue("the press goes down inside the zone's edge: ${press.from}", press.from.first >= 96f)
        strokes.complete()
        injector.holdMove(token, id, 50f, 0f) // the pointer moves from x=0 to x=50
        strokes.complete()
        injector.holdEnd(token, id)
        strokes.complete()
        assertTrue("every segment chains from the previous end", strokes.dispatched.all { it.valid })
        assertEquals("the drag carries the offset: pointer 50 + 96", 146f, strokes.dispatched[1].to.first, 0.01f)
        assertFalse(strokes.dispatched.last().willContinue)
    }

    @Test
    fun aStaleHandleAfterEndIsANoOpEvenWhenANewPadActivationIsLive() {
        val old = injector.activate()
        injector.end(old)
        val live = injector.activate()
        assertFalse("the old handle ends nothing", injector.end(old))
        assertFalse(injector.click(old))
        assertTrue("the new activation is untouched", injector.isLive(live))
    }

    @Test
    fun endingWhileAPressIsHeldReleasesItFirst() {
        val token = injector.activate()
        injector.holdBegin(token)
        strokes.complete()
        assertTrue(injector.end(token))
        assertEquals(2, strokes.dispatched.size)
        assertFalse("the release goes", strokes.dispatched.last().willContinue)
    }
}
