package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The music rows of 2026-10-07 as pure logic: Order's skip arithmetic, Fade between tracks' dip, Music hours' window and its pause
 * reason, Play outside Mimir's gate, and the two stops (screen off, unplugged headphones). Each test carries the control arm that
 * flips exactly the input under test.
 */
class MusicRowsPolicyTest {

    private fun minutes(hour: Int, minute: Int = 0) = hour * 60 + minute

    // ── Music hours: the window ──────────────────────────────────────────────────────────────────────

    @Test
    fun `all day is never held for the time of day, and every other preset is`() {
        for (minute in listOf(0, 359, 360, 720, 1380, 1439)) {
            assertTrue("minute $minute", MusicSchedulePolicy.isOpen(MimirMusicHours.ALL_DAY, minute))
        }
        assertFalse(MimirMusicHours.ALL_DAY.limited)
        MimirMusicHours.entries.filter { it != MimirMusicHours.ALL_DAY }.forEach { hours ->
            assertTrue("$hours is limited", hours.limited)
            assertTrue("$hours holds the music at some minute", (0 until 1440).any { !MusicSchedulePolicy.isOpen(hours, it) })
            assertTrue("$hours lets it play at some minute", (0 until 1440).any { MusicSchedulePolicy.isOpen(hours, it) })
        }
    }

    @Test
    fun `a window opens on its first minute and closes on its last`() {
        val daytime = MimirMusicHours.DAYTIME
        assertFalse(MusicSchedulePolicy.isOpen(daytime, minutes(6, 59)))
        assertTrue(MusicSchedulePolicy.isOpen(daytime, minutes(7)))
        assertTrue(MusicSchedulePolicy.isOpen(daytime, minutes(18, 59)))
        assertFalse(MusicSchedulePolicy.isOpen(daytime, minutes(19)))
        assertFalse(MusicSchedulePolicy.isOpen(daytime, minutes(23)))
    }

    @Test
    fun `a window that runs through midnight is open on both sides of it`() {
        // 10 PM to 6 AM: no preset runs through midnight yet, but the rule reads one.
        val start = minutes(22)
        val end = minutes(6)
        assertTrue(MusicSchedulePolicy.isOpen(start, end, minutes(22)))
        assertTrue(MusicSchedulePolicy.isOpen(start, end, minutes(23, 59)))
        assertTrue(MusicSchedulePolicy.isOpen(start, end, 0))
        assertTrue(MusicSchedulePolicy.isOpen(start, end, minutes(5, 59)))
        assertFalse(MusicSchedulePolicy.isOpen(start, end, minutes(6)))
        assertFalse(MusicSchedulePolicy.isOpen(start, end, minutes(12)))
        assertFalse(MusicSchedulePolicy.isOpen(start, end, minutes(21, 59)))
        // A minute outside the day wraps rather than failing.
        assertEquals(MusicSchedulePolicy.isOpen(start, end, minutes(23)), MusicSchedulePolicy.isOpen(start, end, minutes(23) + 1440))
    }

    @Test
    fun `the next edge is the window's end while it is open and its start while it is closed`() {
        val hour = 3_600_000L
        val daytime = MimirMusicHours.DAYTIME
        assertEquals("6 AM: the window opens in an hour", hour, MusicSchedulePolicy.millisUntilChange(daytime, 6 * hour))
        assertEquals("noon: it closes at 7 PM", 7 * hour, MusicSchedulePolicy.millisUntilChange(daytime, 12 * hour))
        assertEquals("8 PM: it opens at 7 AM tomorrow", 11 * hour, MusicSchedulePolicy.millisUntilChange(daytime, 20 * hour))
        assertEquals("on the opening minute the next edge is the close", 12 * hour, MusicSchedulePolicy.millisUntilChange(daytime, 7 * hour))
        assertNull("all day never turns over", MusicSchedulePolicy.millisUntilChange(MimirMusicHours.ALL_DAY, 12 * hour))
        // Through midnight: at 11 PM the 10 PM to 6 AM window closes in 7 hours; at noon it opens in 10.
        assertEquals(7 * hour, MusicSchedulePolicy.millisUntilChange(minutes(22), minutes(6), 23 * hour))
        assertEquals(10 * hour, MusicSchedulePolicy.millisUntilChange(minutes(22), minutes(6), 12 * hour))
    }

    @Test
    fun `the look just past an edge reads the new side of it`() {
        for (hours in MimirMusicHours.entries.filter { it.limited }) {
            val beforeOpen = MusicSchedulePolicy.millisUntilChange(hours, (hours.startMinute * 60_000L) - 1_000L)!!
            assertEquals("$hours: one second before it opens", 1_000L, beforeOpen)
            val openAt = hours.startMinute * 60_000L + 1_000L
            assertTrue(MusicSchedulePolicy.isOpen(hours, (openAt / 60_000L).toInt()))
        }
    }

    // ── Music hours: the pause reason ────────────────────────────────────────────────────────────────

    @Test
    fun `the music hours name SCHEDULE alone, and every real hold outranks them`() {
        val outside = MusicPausePolicy.onSchedule(MusicPauseState(), outsideHours = true)
        assertEquals(MusicPauseReason.SCHEDULE, outside.reason)
        assertTrue(outside.paused)
        assertNull(MusicPausePolicy.onSchedule(outside, outsideHours = false).reason)
        assertEquals(MusicPauseReason.USER, outside.copy(user = true).reason)
        assertEquals(MusicPauseReason.HEADPHONES, outside.copy(headphones = true).reason)
        assertEquals(MusicPauseReason.OTHER_APP, outside.copy(otherApp = OtherAppHold.TRANSIENT).reason)
        assertEquals(MusicPauseReason.SCREEN_OFF, outside.copy(screenOff = true).reason)
        assertEquals("SCHEDULE is the last reason", MusicPauseReason.SCHEDULE, MusicPauseReason.entries.last())
    }

    @Test
    fun `outside the hours Mimir does not want audio focus, and inside them it does`() {
        val outside = MusicPausePolicy.onSchedule(MusicPauseState(), outsideHours = true)
        assertFalse(MusicPausePolicy.wantsFocus(outside))
        assertFalse(MusicPausePolicy.mayRequestFocus(outside, gameInFront = false))
        assertTrue(MusicPausePolicy.wantsFocus(MusicPausePolicy.onSchedule(outside, outsideHours = false)))
    }

    @Test
    fun `a deliberate play during the off-hours clears them, and only the next edge brings them back`() {
        val outside = MusicPausePolicy.onSchedule(MusicPauseState(), outsideHours = true)
        val played = MusicPausePolicy.onUserPlayPause(outside)
        assertNull("the play overrides the hours", played.reason)
        assertTrue(MusicPausePolicy.wantsFocus(played))
        // The edge back in and out again re-sets them.
        val reopened = MusicPausePolicy.onSchedule(played, outsideHours = false)
        assertEquals(MusicPauseReason.SCHEDULE, MusicPausePolicy.onSchedule(reopened, outsideHours = true).reason)
    }

    // ── The two stops ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Your music plays through a screen-off only when its stop is off, and the raw reason still names the lid`() {
        val off = MusicPausePolicy.onScreen(MusicPauseState(), on = false)
        assertEquals("the raw state keeps the screen-off, whatever the stop says", MusicPauseReason.SCREEN_OFF, off.reason)
        assertEquals(MusicPauseReason.SCREEN_OFF, MusicPausePolicy.forYours(off, pausesOnScreenOff = true).reason)
        assertNull(MusicPausePolicy.forYours(off, pausesOnScreenOff = false).reason)
        assertTrue(MusicPausePolicy.wantsFocus(MusicPausePolicy.forYours(off, pausesOnScreenOff = false)))
        assertFalse(MusicPausePolicy.wantsFocus(MusicPausePolicy.forYours(off, pausesOnScreenOff = true)))
    }

    @Test
    fun `the screen stop being off leaves every other hold in place`() {
        val held = MusicPauseState(screenOff = true, headphones = true, otherApp = OtherAppHold.PERMANENT, schedule = true)
        val yours = MusicPausePolicy.forYours(held, pausesOnScreenOff = false)
        assertFalse(yours.screenOff)
        assertTrue(yours.headphones)
        assertEquals(OtherAppHold.PERMANENT, yours.otherApp)
        assertTrue(yours.schedule)
        assertEquals(MusicPauseReason.HEADPHONES, yours.reason)
    }

    @Test
    fun `a press of play while Your music plays through a screen-off is the player's pause, not a take-over`() {
        val off = MusicPausePolicy.onScreen(MusicPauseState(), on = false)
        val yoursPaused = MusicPausePolicy.forYours(off, pausesOnScreenOff = false).paused
        assertFalse(yoursPaused)
        val pressed = MusicPausePolicy.onUserPlayPause(off, yoursPaused = yoursPaused)
        assertEquals(MusicPauseReason.USER, pressed.reason)
        // Control: with the stop on, the same press over the same state is the take-over that clears every hold.
        val takeOver = MusicPausePolicy.onUserPlayPause(off, yoursPaused = MusicPausePolicy.forYours(off, true).paused)
        assertNull(takeOver.reason)
    }

    @Test
    fun `a press of play with no explicit view behaves as before`() {
        assertEquals(MusicPauseReason.USER, MusicPausePolicy.onUserPlayPause(MusicPauseState()).reason)
        assertNull(MusicPausePolicy.onUserPlayPause(MusicPauseState(headphones = true)).reason)
    }

    // ── The target level ─────────────────────────────────────────────────────────────────────────────

    private val inMimir = FrontendMusicPolicyInput(
        appForeground = true,
        musicVolume = 0.5f,
        focus = AudioFocusState.Foreground,
        environmentBlocked = false,
        competingOwnedBed = false,
        everywhere = true,
    )

    @Test
    fun `outside the music hours both sources are silent, and inside them neither is`() {
        for (everywhere in listOf(true, false)) {
            val input = inMimir.copy(everywhere = everywhere)
            assertEquals("everywhere=$everywhere inside", 0.5f, FrontendMusicPolicy.targetVolume(input), 0.0001f)
            assertEquals("everywhere=$everywhere outside", 0f, FrontendMusicPolicy.targetVolume(input.copy(scheduleBlocked = true)), 0f)
        }
    }

    @Test
    fun `the hours hold the theme bed even when a real hold names the reason`() {
        // The pause reason is one value: a screen-off outranks the hours in it. The bed ignores pause reasons, so the hours reach it
        // through their own flag.
        val bed = inMimir.copy(everywhere = false, pauseReason = MusicPauseReason.SCREEN_OFF, scheduleBlocked = true)
        assertEquals(0f, FrontendMusicPolicy.targetVolume(bed), 0f)
    }

    @Test
    fun `Play outside Mimir controls whether Your music plays everywhere`() {
        val outsideMimir = inMimir.copy(appForeground = false, focus = AudioFocusState.Suspended, environmentBlocked = true)
        assertEquals("on (the default): plays on in a game or another app", 0.5f, FrontendMusicPolicy.targetVolume(outsideMimir), 0.0001f)
        assertEquals("off: silent outside Mimir", 0f, FrontendMusicPolicy.targetVolume(outsideMimir.copy(playOutsideMimir = false)), 0f)
        assertEquals("off: still plays inside Mimir", 0.5f, FrontendMusicPolicy.targetVolume(inMimir.copy(playOutsideMimir = false)), 0.0001f)
        assertTrue("play outside Mimir is enabled by default", inMimir.playOutsideMimir)
    }

    @Test
    fun `Play outside Mimir has no say over the theme bed, which is Mimir-only already`() {
        val bed = inMimir.copy(everywhere = false)
        assertEquals(FrontendMusicPolicy.targetVolume(bed), FrontendMusicPolicy.targetVolume(bed.copy(playOutsideMimir = false)), 0f)
    }

    // ── Order ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a skip follows the order the player plays in, and falls back to the list's own order`() {
        // Shuffle: the player says 7 follows 2 in a ten-track list.
        assertEquals(7, yourMusicSkipIndex(from = 2, count = 10, forward = true, playerNeighbour = 7))
        assertEquals(1, yourMusicSkipIndex(from = 2, count = 10, forward = false, playerNeighbour = 1))
        // No answer from the player: the list's order, wrapping.
        assertEquals(3, yourMusicSkipIndex(from = 2, count = 10, forward = true, playerNeighbour = null))
        assertEquals(0, yourMusicSkipIndex(from = 9, count = 10, forward = true, playerNeighbour = null))
        assertEquals(9, yourMusicSkipIndex(from = 0, count = 10, forward = false, playerNeighbour = null))
        // An index outside the list is no answer.
        assertEquals(3, yourMusicSkipIndex(from = 2, count = 10, forward = true, playerNeighbour = 10))
        assertEquals(3, yourMusicSkipIndex(from = 2, count = 10, forward = true, playerNeighbour = -1))
        assertEquals(0, yourMusicSkipIndex(from = 0, count = 0, forward = true, playerNeighbour = null))
    }

    @Test
    fun `a shuffled playlist opens on a track picked at random, and an ordered one on the first`() {
        assertEquals("in order: the first, whatever the dice say", 0, yourMusicStartIndex(10, shuffle = false) { 7 })
        assertEquals("shuffled: the pick", 7, yourMusicStartIndex(10, shuffle = true) { 7 })
        var asked = -1
        yourMusicStartIndex(6, shuffle = true) { count -> asked = count; 0 }
        assertEquals("the dice are rolled for the whole list", 6, asked)
        var rolled = false
        yourMusicStartIndex(6, shuffle = false) { rolled = true; 0 }
        assertEquals("in order, nothing is rolled", false, rolled)
        assertEquals("a pick outside the list is held inside it", 9, yourMusicStartIndex(10, shuffle = true) { 40 })
        assertEquals(0, yourMusicStartIndex(10, shuffle = true) { -3 })
        assertEquals("one track has nowhere else to start", 0, yourMusicStartIndex(1, shuffle = true) { 5 })
        assertEquals(0, yourMusicStartIndex(0, shuffle = true) { 5 })
    }

    // ── Fade between tracks ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the way out begins in a track's last second and only then`() {
        val step = { remaining: Long? -> YourMusicDip.step(enabled = true, trackCount = 5, dipped = false, remainingMs = remaining) }
        assertEquals(YourMusicDip.Step.NONE, step(180_000L))
        assertEquals(YourMusicDip.Step.NONE, step(YourMusicDip.OutMs + YourMusicDip.PollMs + 1))
        assertEquals(YourMusicDip.Step.FADE_OUT, step(YourMusicDip.OutMs + YourMusicDip.PollMs))
        assertEquals(YourMusicDip.Step.FADE_OUT, step(400L))
        assertEquals("a length not known yet fades nothing", YourMusicDip.Step.NONE, step(null))
    }

    @Test
    fun `the way in is taken when the next track's whole length is ahead, and not before`() {
        val step = { remaining: Long? -> YourMusicDip.step(enabled = true, trackCount = 5, dipped = true, remainingMs = remaining) }
        assertEquals("still inside the dipped track's tail", YourMusicDip.Step.NONE, step(900L))
        assertEquals(YourMusicDip.Step.NONE, step(YourMusicDip.OutMs + 2 * YourMusicDip.PollMs))
        assertEquals(YourMusicDip.Step.FADE_IN, step(YourMusicDip.OutMs + 2 * YourMusicDip.PollMs + 1))
        assertEquals(YourMusicDip.Step.FADE_IN, step(200_000L))
        assertEquals("a length that has become unknown ends the dip", YourMusicDip.Step.FADE_IN, step(null))
    }

    @Test
    fun `with the fade off, or one track, nothing dips and a dip that is down comes back up`() {
        for (dipped in listOf(false, true)) {
            val expected = if (dipped) YourMusicDip.Step.FADE_IN else YourMusicDip.Step.NONE
            assertEquals("off, dipped=$dipped", expected, YourMusicDip.step(false, 5, dipped, 300L))
            assertEquals("one track, dipped=$dipped", expected, YourMusicDip.step(true, 1, dipped, 300L))
            assertEquals("no tracks, dipped=$dipped", expected, YourMusicDip.step(true, 0, dipped, 300L))
        }
    }

    @Test
    fun `the way out reaches silence as the track ends, and is never shorter than a blink`() {
        assertEquals(1_000L, YourMusicDip.outMs(1_250L))
        assertEquals(1_000L, YourMusicDip.outMs(1_000L))
        assertEquals(640L, YourMusicDip.outMs(640L))
        assertEquals(200L, YourMusicDip.outMs(40L))
        assertEquals(200L, YourMusicDip.outMs(0L))
        assertEquals("two seconds of dip all told", 2_000L, YourMusicDip.OutMs + YourMusicDip.InMs)
    }

    // ── The stored choices ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a player who never opens the rows gets the spec's behaviour`() {
        assertEquals(MimirMusicHours.ALL_DAY, AudioPreferences.musicHours.value)
        assertFalse("gapless by default: the dip is the player's to turn on", AudioPreferences.fadeBetweenTracks.value)
        assertTrue("plays everywhere by default", AudioPreferences.playOutsideMimir.value)
        assertTrue("stops at screen-off (spec)", AudioPreferences.pauseOnScreenOff.value)
        assertTrue("stops when headphones are unplugged (spec)", AudioPreferences.pauseOnUnplug.value)
        assertEquals("shuffle (spec)", MimirMusicOrder.SHUFFLE, AudioPreferences.musicOrder.value)
    }

    @Test
    fun `a stored hours value that is unknown falls back to all day instead of failing`() {
        MimirMusicHours.entries.forEach { assertEquals(it, AudioPreferences.parseMusicHours(it.name)) }
        assertEquals(MimirMusicHours.ALL_DAY, AudioPreferences.parseMusicHours(null))
        assertEquals(MimirMusicHours.ALL_DAY, AudioPreferences.parseMusicHours(""))
        assertEquals(MimirMusicHours.ALL_DAY, AudioPreferences.parseMusicHours("midnight-oil-from-the-future"))
    }
}
