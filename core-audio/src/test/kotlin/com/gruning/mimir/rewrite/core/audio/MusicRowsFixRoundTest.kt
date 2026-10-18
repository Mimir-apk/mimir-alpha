package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Regression coverage for four music-row defects: the Music hours override that outlived a jump of the clock, the
 * transient hold that outlived its focus request, the dip that cancelled a skip's move, and the dip that cancelled a pause's ending.
 * Pure logic, plus pins on the Android-bound code that has to use it (the control and the player cannot run here).
 */
class MusicRowsFixRoundTest {

    private val newYork = ZoneId.of("America/New_York")
    private val daytime = MimirMusicHours.DAYTIME

    private fun at(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, second: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private fun ny(month: Int, day: Int, hour: Int, minute: Int = 0, second: Int = 0) = at(newYork, 2026, month, day, hour, minute, second)

    private fun look(watch: MusicScheduleWatch, whenMs: Long, zone: ZoneId = newYork, hours: MimirMusicHours = daytime, force: Boolean = false) =
        MusicSchedulePolicy.look(watch, hours, whenMs, zone, force)

    /** The evening of 7 October: the hours close at 7 PM, and the player presses play at 8 PM. Returns the watch with the override made. */
    private fun overriddenAtEight(): MusicScheduleWatch {
        val opening = look(MusicScheduleWatch(), ny(10, 7, 18), force = true)
        assertEquals("open at 6 PM", false, opening.outsideHours)
        val closing = look(opening.watch, ny(10, 7, 19, 0, 1))
        assertEquals("closes at 7 PM", true, closing.outsideHours)
        return MusicSchedulePolicy.overrideStarted(closing.watch, daytime, ny(10, 7, 20), newYork)
    }

    // ── P2 1: the override ends at an instant, not at a sample of open/closed ───────────────────────────

    @Test
    fun `a play at 8 PM holds until 7 AM, as an absolute instant`() {
        val watch = overriddenAtEight()
        val override = watch.override
        assertNotNull(override)
        assertEquals(ny(10, 7, 20), override!!.fromEpochMs)
        assertEquals(ny(10, 8, 7), override.untilEpochMs)
        listOf(ny(10, 7, 21), ny(10, 8, 0), ny(10, 8, 3, 30), ny(10, 8, 6, 59)).forEach { whenMs ->
            val seen = look(watch, whenMs)
            assertNull("nothing is set while the override lasts ($whenMs)", seen.outsideHours)
            assertEquals("and it stands", override, seen.watch.override)
        }
    }

    @Test
    fun `the override ends at its edge and the hours then close at 7 PM as usual`() {
        val morning = look(overriddenAtEight(), ny(10, 8, 7, 0, 1))
        assertEquals("released at 7 AM", false, morning.outsideHours)
        assertNull("the override has been spent", morning.watch.override)
        val evening = look(morning.watch, ny(10, 8, 19, 0, 1))
        assertEquals("held again at 7 PM", true, evening.outsideHours)
    }

    @Test
    fun `a jump from 8 PM to 8 PM the next day leaves no override and holds the music`() {
        val jumped = look(overriddenAtEight(), ny(10, 8, 20))
        assertEquals("the music is held again", true, jumped.outsideHours)
        assertNull("the override is gone", jumped.watch.override)
        // The control arm: with no record of the override (the edge detector alone, as before), 8 PM reads closed after closed and
        // nothing is ever set, so the music would have played on through the second night.
        assertNull(look(MusicScheduleWatch(lastOpen = false), ny(10, 8, 20)).outsideHours)
    }

    @Test
    fun `a jump over a whole day, or several, holds the music the same`() {
        val days = look(overriddenAtEight(), ny(10, 11, 22))
        assertEquals(true, days.outsideHours)
        assertNull(days.watch.override)
    }

    @Test
    fun `a clock set back before the override ends it, and a morning it lands in lets the music play`() {
        val back = look(overriddenAtEight(), ny(10, 7, 15))
        assertNull("the override has not begun at 3 PM", back.watch.override)
        assertEquals("3 PM is inside the hours", false, back.outsideHours)
        val earlyHours = look(overriddenAtEight(), ny(10, 7, 3))
        assertEquals("3 AM that day is closed and no override covers it", true, earlyHours.outsideHours)
    }

    @Test
    fun `midnight, the date change, looks and leaves the override alone`() {
        val midnight = look(overriddenAtEight(), ny(10, 8, 0, 0, 0))
        assertNull(midnight.outsideHours)
        assertNotNull(midnight.watch.override)
    }

    @Test
    fun `choosing a different window applies the answer and ends an override`() {
        val forced = look(overriddenAtEight(), ny(10, 7, 21), force = true)
        assertEquals("9 PM is closed under the new look at it", true, forced.outsideHours)
        assertNull(forced.watch.override)
        val allDay = look(overriddenAtEight(), ny(10, 7, 21), hours = MimirMusicHours.ALL_DAY, force = true)
        assertEquals(false, allDay.outsideHours)
        assertNull(allDay.watch.override)
    }

    @Test
    fun `a time zone change does not move the override's instant, and the new zone answers once it passes`() {
        val watch = overriddenAtEight()
        val until = watch.override!!.untilEpochMs
        // Flown west at 9 PM New York: 6 PM in Los Angeles, inside the hours. The override is over, the music plays.
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val west = look(watch, ny(10, 7, 21), zone = losAngeles)
        assertEquals(false, west.outsideHours)
        assertNull(west.watch.override)
        // Flown east: 2 AM in London, still closed. The override stands until the same instant.
        val london = ZoneId.of("Europe/London")
        val east = look(watch, ny(10, 7, 21), zone = london)
        assertNull(east.outsideHours)
        assertEquals(until, east.watch.override!!.untilEpochMs)
        // Past that instant it is 8:30 PM in Tokyo, closed: the zone's own answer holds the music.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val past = look(watch, until + 30 * 60_000L, zone = tokyo)
        assertEquals(true, past.outsideHours)
        assertNull(past.watch.override)
    }

    @Test
    fun `a day of 23 or 25 hours puts the edge where the wall clock does`() {
        // Spring forward, 8 March 2026: 2 AM becomes 3 AM. 7 AM that day is 11:00 UTC, an hour earlier than 7.5 h after 11:30 PM would say.
        val springNight = ny(3, 7, 23, 30)
        val spring = MusicSchedulePolicy.nextEdgeEpochMs(daytime, springNight, newYork)
        assertEquals(ny(3, 8, 7), spring)
        assertEquals(springNight + 7 * 3_600_000L + 30 * 60_000L - 3_600_000L, spring)
        // Fall back, 1 November 2026: 2 AM becomes 1 AM. 7 AM is 12:00 UTC, an hour later.
        val fallNight = ny(10, 31, 23, 30)
        val fall = MusicSchedulePolicy.nextEdgeEpochMs(daytime, fallNight, newYork)
        assertEquals(ny(11, 1, 7), fall)
        assertEquals(fallNight + 7 * 3_600_000L + 30 * 60_000L + 3_600_000L, fall)
    }

    @Test
    fun `an override made across a clock change ends at 7 AM by the wall clock`() {
        val start = ny(3, 7, 23, 30)
        val watch = MusicSchedulePolicy.overrideStarted(MusicScheduleWatch(lastOpen = false), daytime, start, newYork)
        assertNull("6:59 AM by the wall clock", look(watch, ny(3, 8, 6, 59)).outsideHours)
        val seven = look(watch, ny(3, 8, 7, 0, 1))
        assertEquals("released at 7 AM by the wall clock", false, seven.outsideHours)
        val fallWatch = MusicSchedulePolicy.overrideStarted(MusicScheduleWatch(lastOpen = false), daytime, ny(10, 31, 23, 30), newYork)
        assertNull(look(fallWatch, ny(11, 1, 6, 59)).outsideHours)
        assertEquals(false, look(fallWatch, ny(11, 1, 7, 0, 1)).outsideHours)
    }

    @Test
    fun `the next edge is the close while open and the open while closed, and never for all day`() {
        assertEquals(ny(10, 7, 19), MusicSchedulePolicy.nextEdgeEpochMs(daytime, ny(10, 7, 10), newYork))
        assertEquals(ny(10, 8, 7), MusicSchedulePolicy.nextEdgeEpochMs(daytime, ny(10, 7, 19), newYork))
        assertEquals(ny(10, 7, 7), MusicSchedulePolicy.nextEdgeEpochMs(daytime, ny(10, 7, 3), newYork))
        assertEquals(ny(10, 8, 7), MusicSchedulePolicy.nextEdgeEpochMs(daytime, ny(10, 7, 23, 59), newYork))
        assertNull(MusicSchedulePolicy.nextEdgeEpochMs(MimirMusicHours.ALL_DAY, ny(10, 7, 10), newYork))
        assertNull(MusicSchedulePolicy.overrideStarted(MusicScheduleWatch(), MimirMusicHours.ALL_DAY, ny(10, 7, 10), newYork).override)
    }

    @Test
    fun `with no override the edge detector is what it was`() {
        val inside = look(MusicScheduleWatch(), ny(10, 7, 10))
        assertEquals("the first look applies its answer", false, inside.outsideHours)
        assertNull(look(inside.watch, ny(10, 7, 11)).outsideHours)
        assertEquals(true, look(inside.watch, ny(10, 7, 19, 1)).outsideHours)
        // A night slept through, never overridden: the morning releases it.
        val night = look(look(inside.watch, ny(10, 7, 19, 1)).watch, ny(10, 8, 8))
        assertEquals(false, night.outsideHours)
    }

    @Test
    fun `the control looks at the clock on a time, a zone and a date change, and ends on instants`() {
        val control = source("MimirMusicControl.kt")
        listOf("ACTION_TIME_CHANGED", "ACTION_TIMEZONE_CHANGED", "ACTION_DATE_CHANGED").forEach {
            assertTrue("$it is registered", control.contains("addAction(Intent.$it)"))
        }
        assertTrue(control.contains("MusicSchedulePolicy.look(scheduleWatch"))
        assertTrue("a play over the off-hours starts the override", control.contains("MusicSchedulePolicy.overrideStarted("))
        assertTrue("a zone change is read before the look", control.contains("TimeZone.setDefault(null)"))
        assertFalse("the sampled open/closed field is gone", control.contains("lastScheduleOpen"))
    }

    // ── P2 2: the focus request and a transient hold go together, and the music never plays without the request ──

    private fun transientLoss() = MusicPausePolicy.onFocus(MusicPauseState(), FocusSignal.LOSS_TRANSIENT, gameInFront = false)

    private fun decision(state: MusicPauseState, game: Boolean = false) =
        MusicPausePolicy.focusDecision(state, gameInFront = game, deliberate = false, otherPlayerPlaying = false)

    @Test
    fun `a transient loss, then Mimir in the background, then back, asks for focus again and plays only once it is granted`() {
        val held = transientLoss()
        assertEquals(OtherAppHold.TRANSIENT, held.otherApp)
        assertEquals(MusicPauseReason.OTHER_APP, held.reason)
        // Left behind (what the code did before): the request is gone, so the hold has no GAIN to end it, and the return asks for nothing.
        assertEquals("the control arm: a hold that stayed would block the request on return", MusicPausePolicy.FocusDecision.NONE, decision(held))
        // Let go together with the request (background with Play outside Mimir Off): the music is STILL held, and may now ask afresh.
        val away = MusicPausePolicy.onFocusAbandoned(held)
        assertEquals(OtherAppHold.NEEDS_FOCUS, away.otherApp)
        assertTrue("silent: clearing the hold is not a resume", away.paused)
        assertEquals(MusicPauseReason.OTHER_APP, away.reason)
        assertTrue(MusicPausePolicy.forYours(away, pausesOnScreenOff = true).paused)
        assertTrue(MusicPausePolicy.wantsFocus(away))
        assertEquals(MusicPausePolicy.FocusDecision.REQUEST, decision(away))
        // The request is granted (GAIN): only now does it play.
        val granted = MusicPausePolicy.onFocus(away, FocusSignal.GAIN, gameInFront = false)
        assertNull(granted.reason)
        assertFalse(granted.paused)
    }

    @Test
    fun `a fresh request that a call defers or refuses leaves the music silent`() {
        val away = MusicPausePolicy.onFocusAbandoned(transientLoss())
        // The system queues the request behind the call: held again, with a GAIN to wait for.
        val deferred = MusicPausePolicy.onFocusDelayed(away)
        assertEquals(OtherAppHold.TRANSIENT, deferred.otherApp)
        assertTrue(deferred.paused)
        // The system refuses it: held until the player presses play.
        val refused = MusicPausePolicy.onFocusRefused(away)
        assertEquals(OtherAppHold.TRANSIENT, refused.otherApp)
        assertTrue(refused.paused)
        assertEquals("a refused request is not retried by itself", MusicPausePolicy.FocusDecision.NONE, decision(refused))
        // Only its GAIN plays.
        assertFalse(MusicPausePolicy.onFocus(deferred, FocusSignal.GAIN, gameInFront = false).paused)
    }

    @Test
    fun `a call holds the music, a game comes to the front, the music stays silent, the call ends, and it plays only after GAIN`() {
        // 1. A call takes the audio: a transient loss, the request still held.
        var state = transientLoss()
        assertTrue("silent for the call", MusicPausePolicy.forYours(state, pausesOnScreenOff = true).paused)
        // 2. A game comes to the front: Mimir lets go of its request (the game exemption), and the hold goes with it.
        assertTrue(MusicPausePolicy.abandonsFocusForGame(focusHeld = true, gameInFront = true))
        state = MusicPausePolicy.onFocusAbandoned(state)
        assertTrue("still silent: letting go of focus is not a resume", MusicPausePolicy.forYours(state, pausesOnScreenOff = true).paused)
        assertEquals(MusicPauseReason.OTHER_APP, state.reason)
        // The game in front: no request is made, so nothing could be granted.
        assertEquals(MusicPausePolicy.FocusDecision.NONE, decision(state, game = true))
        // 3. The call ends. No request is held, so no signal reaches Mimir: nothing changes, and it is still silent.
        assertTrue(state.paused)
        // 4. The game leaves the front: the music asks afresh.
        assertEquals(MusicPausePolicy.FocusDecision.REQUEST, decision(state, game = false))
        // 4a. The call is over: the request is granted, and only now does it play.
        val resumed = MusicPausePolicy.onFocus(state, FocusSignal.GAIN, gameInFront = false)
        assertNull(resumed.reason)
        // 4b. The call is still on: the request is deferred, so it stays silent until the GAIN.
        val deferred = MusicPausePolicy.onFocusDelayed(state)
        assertTrue(deferred.paused)
        assertNull(MusicPausePolicy.onFocus(deferred, FocusSignal.GAIN, gameInFront = false).reason)
    }

    @Test
    fun `a transient loss across a schedule close stays silent through the hours opening, and plays when the fresh request is granted`() {
        var state = MusicPausePolicy.onSchedule(transientLoss(), outsideHours = true)
        assertEquals("the other app is the louder reason", MusicPauseReason.OTHER_APP, state.reason)
        assertFalse("closed hours do not want focus, so the request is let go", MusicPausePolicy.wantsFocus(state))
        state = MusicPausePolicy.onFocusAbandoned(state)
        assertEquals("the hold is still there, and silent", MusicPauseReason.OTHER_APP, state.reason)
        state = MusicPausePolicy.onSchedule(state, outsideHours = false)
        assertTrue("the hours open and the music is still held until the request is granted", state.paused)
        assertEquals(MusicPausePolicy.FocusDecision.REQUEST, decision(state))
        assertNull(MusicPausePolicy.onFocus(state, FocusSignal.GAIN, gameInFront = false).reason)
        // The control arm: without the hold's conversion the same close and open leaves a hold with no request, and nothing is asked.
        val stuck = MusicPausePolicy.onSchedule(MusicPausePolicy.onSchedule(transientLoss(), outsideHours = true), outsideHours = false)
        assertEquals(MusicPauseReason.OTHER_APP, stuck.reason)
        assertEquals(MusicPausePolicy.FocusDecision.NONE, decision(stuck))
    }

    @Test
    fun `letting go of focus never lets a held music sound, and changes only a transient hold`() {
        // For every combination of flags: if the music was held before it let go of focus, it is held after.
        val flags = listOf(false, true)
        val holds = OtherAppHold.entries
        for (user in flags) for (headphones in flags) for (screenOff in flags) for (schedule in flags) for (hold in holds) {
            val before = MusicPauseState(user, headphones, screenOff, hold, schedule)
            val after = MusicPausePolicy.onFocusAbandoned(before)
            if (before.paused) assertTrue("held before, held after: $before", after.paused)
            if (hold != OtherAppHold.TRANSIENT) assertEquals("only a transient hold changes: $before", before, after)
        }
        val busy = MusicPauseState(user = true, headphones = true, screenOff = true, otherApp = OtherAppHold.TRANSIENT, schedule = true)
        assertEquals(busy.copy(otherApp = OtherAppHold.NEEDS_FOCUS), MusicPausePolicy.onFocusAbandoned(busy))
        val permanent = MusicPauseState(otherApp = OtherAppHold.PERMANENT)
        assertEquals("a permanent hold ends by the other player stopping", permanent, MusicPausePolicy.onFocusAbandoned(permanent))
        assertEquals(MusicPauseState(), MusicPausePolicy.onFocusAbandoned(MusicPauseState()))
        val needs = MusicPauseState(otherApp = OtherAppHold.NEEDS_FOCUS)
        assertEquals("letting go twice changes nothing", needs, MusicPausePolicy.onFocusAbandoned(needs))
        val delayed = MusicPausePolicy.onFocusDelayed(MusicPauseState())
        assertEquals("a delayed request is withdrawn with the request, and still holds", needs, MusicPausePolicy.onFocusAbandoned(delayed))
    }

    @Test
    fun `only a granted request ends a hold that needs a fresh one, and nothing else asks for focus under a game or a call`() {
        val needs = MusicPauseState(otherApp = OtherAppHold.NEEDS_FOCUS)
        assertEquals(MusicPauseReason.OTHER_APP, needs.reason)
        assertTrue(MusicPausePolicy.mayRequestFocus(needs, gameInFront = false))
        assertFalse("never under a game", MusicPausePolicy.mayRequestFocus(needs, gameInFront = true))
        assertFalse("not while the music is not wanted", MusicPausePolicy.mayRequestFocus(needs.copy(user = true), gameInFront = false))
        // The other holds still do not ask: their own return ends them.
        assertFalse(MusicPausePolicy.mayRequestFocus(MusicPauseState(otherApp = OtherAppHold.TRANSIENT), gameInFront = false))
        assertFalse(MusicPausePolicy.mayRequestFocus(MusicPauseState(otherApp = OtherAppHold.PERMANENT), gameInFront = false))
        // Nothing but a GAIN clears it: the other player stopping, the screen, the clock and a late loss leave it alone.
        assertEquals(needs, MusicPausePolicy.onOtherSessionStopped(needs))
        assertEquals(needs, MusicPausePolicy.onScreen(needs, on = true))
        assertEquals(needs, MusicPausePolicy.onSchedule(needs, outsideHours = false))
        assertNull(MusicPausePolicy.onFocus(needs, FocusSignal.GAIN, gameInFront = false).reason)
        // A player already audible is waited for, not asked.
        assertEquals(
            MusicPausePolicy.FocusDecision.YIELD_TO_OTHER,
            MusicPausePolicy.focusDecision(needs, gameInFront = false, deliberate = false, otherPlayerPlaying = true),
        )
    }

    @Test
    fun `the control lets go of the request through one door that keeps the hold`() {
        val control = source("MimirMusicControl.kt")
        val reconcile = control.substringAfter("private fun reconcile() {").substringBefore("private fun letGoOfFocus")
        assertFalse("reconcile never abandons without keeping the hold", reconcile.contains("abandonFocus(manager)"))
        assertEquals("not wanted, and a game in front", 2, Regex("letGoOfFocus\\(manager\\)").findAll(reconcile).count())
        assertTrue(control.contains("MusicPausePolicy::onFocusAbandoned"))
        assertTrue("a signal for a request that is gone is not taken", control.contains("ignored: no request is held"))
    }

    // ── P2 3 and 4: one owner of the volume ramp ───────────────────────────────────────────────────────

    private val kinds = YourMusicRampKind.entries

    @Test
    fun `the dip yields to a pause and to a skip, and a level change waits for a skip`() {
        for (inFlight in kinds + listOf(null)) {
            val dip = YourMusicRamps.mayStart(YourMusicRampKind.DIP, inFlight)
            assertEquals("dip over $inFlight", inFlight == null || inFlight == YourMusicRampKind.LEVEL || inFlight == YourMusicRampKind.DIP, dip)
            assertEquals("level over $inFlight", inFlight != YourMusicRampKind.SKIP, YourMusicRamps.mayStart(YourMusicRampKind.LEVEL, inFlight))
            assertTrue("a skip over $inFlight", YourMusicRamps.mayStart(YourMusicRampKind.SKIP, inFlight))
            assertTrue("a pause over $inFlight", YourMusicRamps.mayStart(YourMusicRampKind.PAUSE, inFlight))
        }
        assertFalse(YourMusicRamps.mayStart(YourMusicRampKind.DIP, YourMusicRampKind.PAUSE))
        assertFalse(YourMusicRamps.mayStart(YourMusicRampKind.DIP, YourMusicRampKind.SKIP))
    }

    /**
     * The player's decisions on a press of Next or Previous inside the last 1.25 s of a track with the dip on, composed from the same
     * functions the player uses. [pollWhileFading] is whether the dip's poll runs while the skip fades out (before the fix it did).
     */
    private fun pressInTheTail(forward: Boolean, positionMs: Long, remainingMs: Long, pollWhileFading: Boolean): Pair<Int, YourMusicRampKind?> {
        val count = 5
        val from = 2
        var inFlight: YourMusicRampKind? = null
        var dipped = false
        // The poll before the press: the tail begins the dip.
        if (YourMusicDip.step(true, count, dipped, remainingMs) == YourMusicDip.Step.FADE_OUT && YourMusicRamps.mayStart(YourMusicRampKind.DIP, inFlight)) {
            dipped = true
            inFlight = YourMusicRampKind.DIP
        }
        assertTrue("the dip had begun", dipped)
        // The press: a skip takes the volume, the dip is over, and the move is what the ramp's end will make.
        dipped = false
        inFlight = YourMusicRampKind.SKIP
        val destination = yourMusicSkipDestination(from, count, forward, positionMs, playerNeighbour = if (forward) 3 else 1)
        // A look at the old track's last second while the skip fades out.
        if (pollWhileFading &&
            YourMusicDip.step(true, count, dipped, remainingMs - 300L) == YourMusicDip.Step.FADE_OUT &&
            YourMusicRamps.mayStart(YourMusicRampKind.DIP, inFlight)
        ) {
            inFlight = YourMusicRampKind.DIP
        }
        // The skip's ending only runs if its ramp is still the one in flight.
        return destination to inFlight
    }

    @Test
    fun `Next inside the last 1_25 s with the dip on lands on the next track`() {
        val (destination, inFlight) = pressInTheTail(forward = true, positionMs = 100_000L, remainingMs = 1_100L, pollWhileFading = true)
        assertEquals(3, destination)
        assertEquals("the skip still owns the volume, so its move is made", YourMusicRampKind.SKIP, inFlight)
    }

    @Test
    fun `Previous inside the last 1_25 s restarts the track, and near its start goes back, with the dip on`() {
        val (restart, restartFlight) = pressInTheTail(forward = false, positionMs = 100_000L, remainingMs = 1_100L, pollWhileFading = true)
        assertEquals("past 3 s Previous restarts the same track", 2, restart)
        assertEquals(YourMusicRampKind.SKIP, restartFlight)
        // A 2 s track pressed in its last second is within the first 3 s: Previous goes back.
        val (back, backFlight) = pressInTheTail(forward = false, positionMs = 1_000L, remainingMs = 1_100L, pollWhileFading = true)
        assertEquals(1, back)
        assertEquals(YourMusicRampKind.SKIP, backFlight)
    }

    @Test
    fun `the control arm - a poll that is not held off would have taken the volume from the skip`() {
        // The poll is unconditional in the old code: it sees a dip that is not down in the tail, and starts one over the skip.
        assertEquals(YourMusicDip.Step.FADE_OUT, YourMusicDip.step(true, 5, dipped = false, remainingMs = 800L))
        assertFalse(YourMusicRamps.mayStart(YourMusicRampKind.DIP, YourMusicRampKind.SKIP))
    }

    @Test
    fun `Previous past three seconds restarts and anything else goes to the player's neighbour`() {
        assertEquals(4, yourMusicSkipDestination(4, 6, forward = false, positionMs = 3_001L, playerNeighbour = 3))
        assertEquals(3, yourMusicSkipDestination(4, 6, forward = false, positionMs = 3_000L, playerNeighbour = 3))
        assertEquals(5, yourMusicSkipDestination(4, 6, forward = true, positionMs = 90_000L, playerNeighbour = 5))
        assertEquals("no neighbour: the list's own order", 5, yourMusicSkipDestination(4, 6, forward = true, positionMs = 0L, playerNeighbour = null))
    }

    @Test
    fun `a track change under a pause fade drops the dip and lets the pause finish, and under nothing takes the way in`() {
        assertEquals(
            YourMusicRamps.AfterTrackChange.DROP_DIP,
            YourMusicRamps.afterTrackChange(dipped = true, inFlight = YourMusicRampKind.PAUSE),
        )
        assertEquals(
            YourMusicRamps.AfterTrackChange.DROP_DIP,
            YourMusicRamps.afterTrackChange(dipped = true, inFlight = YourMusicRampKind.SKIP),
        )
        // The control arms: the dip's own way in, with the dip's own ramp or none in flight, or with the level moving.
        listOf(YourMusicRampKind.DIP, YourMusicRampKind.LEVEL, null).forEach {
            assertEquals("$it", YourMusicRamps.AfterTrackChange.FADE_BACK_IN, YourMusicRamps.afterTrackChange(dipped = true, inFlight = it))
        }
        kinds.forEach { assertEquals(YourMusicRamps.AfterTrackChange.NOTHING, YourMusicRamps.afterTrackChange(dipped = false, inFlight = it)) }
    }

    @Test
    fun `a pause fade across a track boundary ends paused on the new track, with the player paused`() {
        // The state machine of the player for this case, step by step, through the decisions it takes from the policy.
        var dipped = true // the end of the old track had begun its dip
        var pausing = false
        var sounding = true
        var announcePending = false
        // A pause is asked for: it takes the volume (a pause over a dip is allowed) and the player pauses when its ramp ends.
        assertTrue(YourMusicRamps.mayStart(YourMusicRampKind.PAUSE, YourMusicRampKind.DIP))
        pausing = true
        // The old track ends under the fade.
        when (YourMusicRamps.afterTrackChange(dipped, if (pausing) YourMusicRampKind.PAUSE else null)) {
            YourMusicRamps.AfterTrackChange.FADE_BACK_IN -> error("the way in would have cancelled the pause's ending")
            YourMusicRamps.AfterTrackChange.DROP_DIP -> {
                dipped = false
                announcePending = true
            }
            YourMusicRamps.AfterTrackChange.NOTHING -> Unit
        }
        // Meanwhile the dip's poll looks at the new track, whose whole length is ahead: it may not act over the pause either.
        assertFalse(YourMusicRamps.mayStart(YourMusicRampKind.DIP, YourMusicRampKind.PAUSE))
        // The pause's ramp ends: finishPause pauses the player.
        pausing = false
        sounding = false
        assertFalse("paused", sounding)
        assertFalse(pausing)
        assertFalse(dipped)
        assertTrue("the next start is an entrance on the track that came", announcePending)
    }

    @Test
    fun `the player routes every volume ramp through one owner`() {
        val player = source("YourMusicPlayback.kt")
        // No ramp starts without a kind.
        assertEquals(
            emptyList<String>(),
            Regex("""\bramp\((?!kind: )(?!YourMusicRampKind\.)""").findAll(player).map { it.value }.toList(),
        )
        assertTrue(player.contains("YourMusicRamps.mayStart(YourMusicRampKind.DIP, inFlightRamp())"))
        assertTrue(player.contains("YourMusicRamps.afterTrackChange(dipped, inFlightRamp())"))
        assertTrue(player.contains("YourMusicRamps.mayStart(YourMusicRampKind.LEVEL, rampKind)"))
        // A skip cancels the poll outright before its way out, and starts it again once the move is made.
        val skip = player.substringAfter("fun skip(forward: Boolean)").substringBefore("private fun settleSkip")
        assertTrue(skip.indexOf("stopDipPoll()") in 0 until skip.indexOf("ramp(YourMusicRampKind.SKIP"))
        assertTrue(skip.indexOf("moveTo(live, destination)") < skip.indexOf("startDipPoll()"))
        // A pause takes a fading skip's move first, so the press is not lost, and then fades out to a real pause.
        val pause = player.substringAfter("YourMusicTransport.PAUSE ->").substringBefore("YourMusicTransport.NONE")
        assertTrue(pause.indexOf("settleSkip(exo)") in 0 until pause.indexOf("ramp(YourMusicRampKind.PAUSE"))
        assertTrue(pause.contains("finishPause(exo)"))
        assertTrue(player.substringAfter("private fun finishPause").substringBefore("private fun ramp").contains("exo.pause()"))
        // The ramp that finishes frees the owner before its ending runs.
        assertTrue(player.contains("rampKind = null\n                    onDone?.invoke()"))
    }

    private fun source(name: String): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/$name").readText()
}
