package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Music pause behavior as pure transitions: another app's focus (not while a game is in front), the screen, the headphones,
 * the player. Each test carries the control arm that flips exactly the input under test.
 */
class MusicPausePolicyTest {
    private val playing = MusicPauseState()

    @Test
    fun `another app taking focus pauses, and its return resumes`() {
        val lost = MusicPausePolicy.onFocus(playing, FocusSignal.LOSS_TRANSIENT, gameInFront = false)
        assertEquals(MusicPauseReason.OTHER_APP, lost.reason)

        val returned = MusicPausePolicy.onFocus(lost, FocusSignal.GAIN, gameInFront = false)
        assertNull(returned.reason)
    }

    @Test
    fun `focus loss does not pause while a game is in front, permanent or transient`() {
        for (signal in listOf(FocusSignal.LOSS, FocusSignal.LOSS_TRANSIENT)) {
            assertNull("$signal with a game in front", MusicPausePolicy.onFocus(playing, signal, gameInFront = true).reason)
            // Control: the same signal with no game in front pauses.
            assertEquals(MusicPauseReason.OTHER_APP, MusicPausePolicy.onFocus(playing, signal, gameInFront = false).reason)
        }
    }

    @Test
    fun `the exemption is read at each focus change, not remembered`() {
        // A loss during a game is ignored; the same loss after the game is gone pauses.
        val duringGame = MusicPausePolicy.onFocus(playing, FocusSignal.LOSS_TRANSIENT, gameInFront = true)
        assertNull(duringGame.reason)
        val afterGame = MusicPausePolicy.onFocus(duringGame, FocusSignal.LOSS_TRANSIENT, gameInFront = false)
        assertEquals(MusicPauseReason.OTHER_APP, afterGame.reason)
    }

    @Test
    fun `a short ducking prompt neither pauses nor needs a resume`() {
        assertEquals(playing, MusicPausePolicy.onFocus(playing, FocusSignal.LOSS_TRANSIENT_CAN_DUCK, gameInFront = false))
    }

    @Test
    fun `a permanent loss is never followed by a gain, so the other session being confirmed stopped ends it`() {
        val lost = MusicPausePolicy.onFocus(playing, FocusSignal.LOSS, gameInFront = false)
        assertEquals(OtherAppHold.PERMANENT, lost.otherApp)
        assertNull(MusicPausePolicy.onOtherSessionStopped(lost).reason)
    }

    @Test
    fun `a transient loss is NOT ended by a stopped session, because a phone call has no session at all`() {
        val lost = MusicPausePolicy.onFocus(playing, FocusSignal.LOSS_TRANSIENT, gameInFront = false)
        assertEquals(MusicPauseReason.OTHER_APP, MusicPausePolicy.onOtherSessionStopped(lost).reason)
        // Its own GAIN ends it.
        assertNull(MusicPausePolicy.onFocus(lost, FocusSignal.GAIN, gameInFront = false).reason)
    }

    @Test
    fun `a delayed or refused focus request waits for its gain`() {
        assertEquals(OtherAppHold.TRANSIENT, MusicPausePolicy.onFocusDelayed(playing).otherApp)
        assertEquals(OtherAppHold.TRANSIENT, MusicPausePolicy.onFocusRefused(playing).otherApp)
        assertEquals(OtherAppHold.PERMANENT, MusicPausePolicy.onStartCourtesy(playing).otherApp)
    }

    @Test
    fun `screen off pauses and screen on resumes only what screen off paused`() {
        val off = MusicPausePolicy.onScreen(playing, on = false)
        assertEquals(MusicPauseReason.SCREEN_OFF, off.reason)
        assertNull(MusicPausePolicy.onScreen(off, on = true).reason)
    }

    @Test
    fun `screen on does not resume a track the player paused`() {
        val userPaused = MusicPausePolicy.onUserPlayPause(playing)
        val cycled = MusicPausePolicy.onScreen(MusicPausePolicy.onScreen(userPaused, on = false), on = true)
        assertEquals(MusicPauseReason.USER, cycled.reason)
        // Control: without the player's pause, the same cycle ends playing.
        assertNull(MusicPausePolicy.onScreen(MusicPausePolicy.onScreen(playing, on = false), on = true).reason)
    }

    @Test
    fun `screen on does not resume a track another app holds, or unplugged headphones paused`() {
        val held = MusicPausePolicy.onFocus(playing, FocusSignal.LOSS_TRANSIENT, gameInFront = false)
        val heldAcrossScreen = MusicPausePolicy.onScreen(MusicPausePolicy.onScreen(held, on = false), on = true)
        assertEquals(MusicPauseReason.OTHER_APP, heldAcrossScreen.reason)

        val unplugged = MusicPausePolicy.onNoisy(playing)
        val acrossScreen = MusicPausePolicy.onScreen(MusicPausePolicy.onScreen(unplugged, on = false), on = true)
        assertEquals(MusicPauseReason.HEADPHONES, acrossScreen.reason)
    }

    @Test
    fun `unplugged headphones pause and nothing but the player's play resumes`() {
        val unplugged = MusicPausePolicy.onNoisy(playing)
        assertEquals(MusicPauseReason.HEADPHONES, unplugged.reason)
        // Focus returning, the other player going quiet and the screen cycling all leave it paused.
        var state = MusicPausePolicy.onFocus(unplugged, FocusSignal.GAIN, gameInFront = false)
        state = MusicPausePolicy.onOtherSessionStopped(state)
        state = MusicPausePolicy.onScreen(MusicPausePolicy.onScreen(state, on = false), on = true)
        assertEquals(MusicPauseReason.HEADPHONES, state.reason)
        // The player's own play does.
        assertNull(MusicPausePolicy.onUserPlayPause(state).reason)
    }

    @Test
    fun `play while paused for any reason is a take-over that clears every hold`() {
        val everything = MusicPauseState(
            user = true,
            headphones = true,
            screenOff = true,
            otherApp = OtherAppHold.TRANSIENT,
        )
        assertNull(MusicPausePolicy.onUserPlayPause(everything).reason)
        // And while playing it is the player's pause.
        assertEquals(MusicPauseReason.USER, MusicPausePolicy.onUserPlayPause(playing).reason)
    }

    @Test
    fun `the reason shown is the player's first, then headphones, another app, the screen`() {
        assertEquals(MusicPauseReason.USER, MusicPauseState(user = true, headphones = true, screenOff = true).reason)
        assertEquals(MusicPauseReason.HEADPHONES, MusicPauseState(headphones = true, otherApp = OtherAppHold.TRANSIENT).reason)
        assertEquals(MusicPauseReason.OTHER_APP, MusicPauseState(otherApp = OtherAppHold.PERMANENT, screenOff = true).reason)
        assertEquals(MusicPauseReason.SCREEN_OFF, MusicPauseState(screenOff = true).reason)
    }

    @Test
    fun `focus is wanted unless the player, the headphones or the screen released it, and never requested over a game`() {
        assertTrue(MusicPausePolicy.wantsFocus(playing))
        // Another app's hold keeps the request: its GAIN is what resumes.
        assertTrue(MusicPausePolicy.wantsFocus(MusicPauseState(otherApp = OtherAppHold.TRANSIENT)))
        assertFalse(MusicPausePolicy.wantsFocus(MusicPauseState(user = true)))
        assertFalse(MusicPausePolicy.wantsFocus(MusicPauseState(headphones = true)))
        assertFalse(MusicPausePolicy.wantsFocus(MusicPauseState(screenOff = true)))

        assertTrue(MusicPausePolicy.mayRequestFocus(playing, gameInFront = false))
        assertFalse("taking focus from a game would pause the game", MusicPausePolicy.mayRequestFocus(playing, gameInFront = true))
        assertFalse(
            "a hold is ended by its own return, not by a retry",
            MusicPausePolicy.mayRequestFocus(MusicPauseState(otherApp = OtherAppHold.PERMANENT), gameInFront = false),
        )
    }

    @Test
    fun `a player that starts while Your music holds no focus is yielded to, unless a game is in front or a hold is set`() {
        assertTrue(MusicPausePolicy.yieldsToStartedPlayer(focusHeld = false, ownMusicSounding = true, state = playing, gameInFront = false))
        assertFalse("focus is held: the loss callback does the work", MusicPausePolicy.yieldsToStartedPlayer(true, true, playing, false))
        assertFalse("nothing to pause", MusicPausePolicy.yieldsToStartedPlayer(false, false, playing, false))
        assertFalse("the game exemption stands", MusicPausePolicy.yieldsToStartedPlayer(false, true, playing, true))
        val held = MusicPauseState(otherApp = OtherAppHold.PERMANENT)
        assertFalse("already held", MusicPausePolicy.yieldsToStartedPlayer(false, true, held, false))
        // The yield is a permanent hold, so the newcomer's session being confirmed stopped is what releases it.
        val yielded = MusicPausePolicy.onStartCourtesy(playing)
        assertEquals(MusicPauseReason.OTHER_APP, yielded.reason)
        assertNull(MusicPausePolicy.onOtherSessionStopped(yielded).reason)
    }

    @Test
    fun `focus is let go while a game is in front and only then`() {
        assertTrue(MusicPausePolicy.abandonsFocusForGame(focusHeld = true, gameInFront = true))
        assertFalse("not held, nothing to let go", MusicPausePolicy.abandonsFocusForGame(focusHeld = false, gameInFront = true))
        assertFalse("no game, Your music keeps its focus", MusicPausePolicy.abandonsFocusForGame(focusHeld = true, gameInFront = false))
        // And it is never asked for again over the game, by any play (deliberate or not): the one request site checks this.
        assertFalse(MusicPausePolicy.mayRequestFocus(MusicPausePolicy.onUserPlayPause(MusicPauseState(user = true)), gameInFront = true))
    }

    private fun session(playing: Boolean) = OtherPlayerSnapshot("Song", "Artist", "YouTube", artwork = null, isPlaying = playing)

    @Test
    fun `only the media-session grant can confirm another player stopped, and playing or buffering is not stopped`() {
        // Granted: PAUSED (a snapshot that is not playing) and STOPPED/NONE (no snapshot) are stopped.
        assertTrue("paused", MusicPausePolicy.otherSessionStopped(sessionAccessGranted = true, session = session(playing = false)))
        assertTrue("stopped or none", MusicPausePolicy.otherSessionStopped(sessionAccessGranted = true, session = null))
        // Playing, and BUFFERING/CONNECTING/SKIPPING (the bridge maps all of them to playing), are not.
        assertFalse("playing or buffering", MusicPausePolicy.otherSessionStopped(sessionAccessGranted = true, session = session(playing = true)))
        // Not granted: nothing can tell, so it is never stopped, whatever the (absent) session says.
        assertFalse("no grant, no session", MusicPausePolicy.otherSessionStopped(sessionAccessGranted = false, session = null))
        assertFalse("no grant, paused", MusicPausePolicy.otherSessionStopped(sessionAccessGranted = false, session = session(playing = false)))
    }

    @Test
    fun `a permanent hold with no grant needs the player's play, and a granted or transient one does not`() {
        val permanent = MusicPausePolicy.onFocus(playing, FocusSignal.LOSS, gameInFront = false)
        assertTrue(MusicPausePolicy.holdNeedsPlay(permanent, sessionAccessGranted = false))
        assertFalse(MusicPausePolicy.holdNeedsPlay(permanent, sessionAccessGranted = true))
        assertFalse("its own GAIN ends a transient hold", MusicPausePolicy.holdNeedsPlay(MusicPauseState(otherApp = OtherAppHold.TRANSIENT), false))
        assertFalse(MusicPausePolicy.holdNeedsPlay(playing, sessionAccessGranted = false))
        // The start courtesy is a permanent hold too.
        assertTrue(MusicPausePolicy.holdNeedsPlay(MusicPausePolicy.onStartCourtesy(playing), sessionAccessGranted = false))
    }

    @Test
    fun `ten seconds of a stopped session, continuously, confirm it`() {
        val streak = ConfirmedPausedStreak()
        assertFalse(streak.onSample(stopped = true, atMs = 1_000))
        assertFalse(streak.onSample(stopped = true, atMs = 10_999))
        assertTrue("exactly 10 s after the first stopped sample", streak.onSample(stopped = true, atMs = 11_000))
        assertEquals(10_000L, OtherPlayerConfirmedPausedMs)
    }

    @Test
    fun `a buffering blip does NOT resume, it throws the streak away and the ten seconds start again`() {
        val streak = ConfirmedPausedStreak()
        assertFalse(streak.onSample(stopped = true, atMs = 0))
        assertFalse(streak.onSample(stopped = true, atMs = 6_000))
        // The player buffers (reads as playing) for a moment...
        assertFalse(streak.onSample(stopped = false, atMs = 6_500))
        assertNull("no stop is under way", streak.remainingMs(6_500))
        // ...so what had run to 6 s counts for nothing: 10 s after the blip ended is the earliest.
        assertFalse(streak.onSample(stopped = true, atMs = 7_000))
        assertFalse("10 s after the FIRST sample would have confirmed it", streak.onSample(stopped = true, atMs = 10_000))
        assertFalse(streak.onSample(stopped = true, atMs = 16_999))
        assertTrue(streak.onSample(stopped = true, atMs = 17_000))
    }

    @Test
    fun `the streak says how long is left, and reset forgets it`() {
        val streak = ConfirmedPausedStreak()
        assertNull(streak.remainingMs(0))
        streak.onSample(stopped = true, atMs = 2_000)
        assertEquals(10_000L, streak.remainingMs(2_000))
        assertEquals(4_000L, streak.remainingMs(8_000))
        assertEquals("never negative", 0L, streak.remainingMs(20_000))
        streak.reset()
        assertNull(streak.remainingMs(20_000))
        assertFalse("a reset streak starts over", streak.onSample(stopped = true, atMs = 20_000))
    }

    @Test
    fun `any non-deliberate request yields to a playing player, even when Your music is already sounding`() {
        assertTrue(MusicPausePolicy.yieldsToPlayingOther(deliberate = false, otherPlayerPlaying = true))
        assertFalse(MusicPausePolicy.yieldsToPlayingOther(deliberate = true, otherPlayerPlaying = true))
        assertFalse(MusicPausePolicy.yieldsToPlayingOther(deliberate = false, otherPlayerPlaying = false))
    }

    @Test
    fun `returning from a game with another player playing, Your music pauses for it and requests no focus`() {
        // Your music played through the game without focus; the game is no longer in front and YouTube Music is playing.
        val decision = MusicPausePolicy.focusDecision(playing, gameInFront = false, deliberate = false, otherPlayerPlaying = true)
        assertEquals(MusicPausePolicy.FocusDecision.YIELD_TO_OTHER, decision)
        val yielded = MusicPausePolicy.onStartCourtesy(playing)
        assertEquals(MusicPauseReason.OTHER_APP, yielded.reason)
        // While held, nothing asks for focus; the confirmed-stopped rule is the only release.
        assertEquals(MusicPausePolicy.FocusDecision.NONE, MusicPausePolicy.focusDecision(yielded, false, false, true))
        assertNull(MusicPausePolicy.onOtherSessionStopped(yielded).reason)
    }

    @Test
    fun `returning from a game with no other player, Your music requests focus and plays on`() {
        assertEquals(
            MusicPausePolicy.FocusDecision.REQUEST,
            MusicPausePolicy.focusDecision(playing, gameInFront = false, deliberate = false, otherPlayerPlaying = false),
        )
    }

    @Test
    fun `a deliberate play requests focus even with another player playing`() {
        assertEquals(
            MusicPausePolicy.FocusDecision.REQUEST,
            MusicPausePolicy.focusDecision(playing, gameInFront = false, deliberate = true, otherPlayerPlaying = true),
        )
    }

    @Test
    fun `while a game is in front nothing is requested and nothing yields, both keep playing`() {
        assertEquals(MusicPausePolicy.FocusDecision.NONE, MusicPausePolicy.focusDecision(playing, true, false, true))
        assertEquals(MusicPausePolicy.FocusDecision.NONE, MusicPausePolicy.focusDecision(playing, true, true, true))
        assertEquals(MusicPausePolicy.FocusDecision.NONE, MusicPausePolicy.focusDecision(playing, true, false, false))
    }

    @Test
    fun `a deliberate play of Your music pauses the other player only while it plays and the grant is held`() {
        val paused = MusicPausePolicy.onUserPlayPause(playing)
        // Pressed while Your music is paused, over a playing session with the grant: the other player is paused.
        assertTrue(MusicPausePolicy.pausesOtherOnYoursPlay(paused, sessionAccessGranted = true, other = session(playing = true)))
        // Control arms, one input each.
        assertFalse("the session is already paused", MusicPausePolicy.pausesOtherOnYoursPlay(paused, true, session(playing = false)))
        assertFalse("no card, no controller", MusicPausePolicy.pausesOtherOnYoursPlay(paused, true, null))
        assertFalse("a press while Your music plays is its pause, not a play", MusicPausePolicy.pausesOtherOnYoursPlay(playing, true, session(playing = true)))
        assertFalse("its transport cannot play/pause", MusicPausePolicy.pausesOtherOnYoursPlay(paused, true, session(true).copy(canPlayPause = false)))
        // No notification grant: unchanged behaviour, whatever a stale snapshot says.
        assertFalse("no grant", MusicPausePolicy.pausesOtherOnYoursPlay(paused, sessionAccessGranted = false, other = session(playing = true)))
    }

    @Test
    fun `playing the other player while Your music sounds pauses Your music with the permanent hold, never a user pause`() {
        assertTrue(MusicPausePolicy.pausesYoursOnOtherPlay(sessionAccessGranted = true, other = session(playing = false), yoursSounding = true))
        // Control arms.
        assertFalse("Your music is not sounding", MusicPausePolicy.pausesYoursOnOtherPlay(true, session(playing = false), yoursSounding = false))
        assertFalse("the press would pause the other player", MusicPausePolicy.pausesYoursOnOtherPlay(true, session(playing = true), yoursSounding = true))
        assertFalse("no card", MusicPausePolicy.pausesYoursOnOtherPlay(true, null, yoursSounding = true))
        assertFalse("no grant: unchanged", MusicPausePolicy.pausesYoursOnOtherPlay(sessionAccessGranted = false, other = session(playing = false), yoursSounding = true))

        val held = MusicPausePolicy.onOtherPlayerPlayWhileYoursSounds(playing)
        assertEquals(OtherAppHold.PERMANENT, held.otherApp)
        assertFalse("not a user pause", held.user)
        assertEquals(MusicPauseReason.OTHER_APP, held.reason)
        // The SAME hold a permanent focus loss sets, so one resume rule applies in and out of games.
        assertEquals(MusicPausePolicy.onFocus(playing, FocusSignal.LOSS, gameInFront = false), held)
        // ...and it ends the same way: the other session confirmed stopped, a game or not (the hold is not read against games).
        assertNull(MusicPausePolicy.onOtherSessionStopped(held).reason)
        val streak = ConfirmedPausedStreak()
        assertFalse(streak.onSample(stopped = true, atMs = 0))
        assertTrue(streak.onSample(stopped = true, atMs = OtherPlayerConfirmedPausedMs))
    }
}
