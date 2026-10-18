package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One-player-at-a-time music admission, as pure policy. A deliberate start of Mimir's music asks for
 * audio focus for whichever source plays (the theme bed or Your music), so the other app pauses; a passive start never takes audio from a
 * player; while Mimir's music plays, another app starting pauses it. The device side (the framework's focus arbitration, the Handler
 * thread) is pinned on the control's source and left to the Thor.
 */
class MimirTakesOverPolicyTest {
    private val clear = MusicPauseState()
    private val request = MusicPausePolicy.FocusDecision.REQUEST
    private val yieldToOther = MusicPausePolicy.FocusDecision.YIELD_TO_OTHER
    private val none = MusicPausePolicy.FocusDecision.NONE

    private val bedInput = FrontendMusicPolicyInput(
        appForeground = true,
        musicVolume = 0.5f,
        focus = AudioFocusState.Foreground,
        environmentBlocked = false,
        competingOwnedBed = false,
    )

    // ── 1. A deliberate start requests focus, for either source ───────────────────────────────────────────────────

    @Test
    fun `a deliberate start of the theme bed requests focus even with another app playing`() {
        assertEquals(request, MusicPausePolicy.bedFocusDecision(clear, gameInFront = false, deliberate = true, otherPlayerPlaying = true))
        assertEquals(request, MusicPausePolicy.bedFocusDecision(clear, gameInFront = false, deliberate = true, otherPlayerPlaying = false))
    }

    @Test
    fun `a deliberate start of Your music requests focus even with another app playing`() {
        assertEquals(request, MusicPausePolicy.focusDecision(clear, gameInFront = false, deliberate = true, otherPlayerPlaying = true))
    }

    @Test
    fun `a deliberate start ends another app's standing hold and nothing else, and is never the player's pause`() {
        val held = MusicPauseState(user = true, headphones = true, screenOff = true, schedule = true, call = true, otherApp = OtherAppHold.PERMANENT)
        val started = MusicPausePolicy.onDeliberateStart(held)
        assertEquals(OtherAppHold.NONE, started.otherApp)
        assertEquals("the rest is untouched", held.copy(otherApp = OtherAppHold.NONE), started)
        for (hold in OtherAppHold.entries) {
            assertEquals(OtherAppHold.NONE, MusicPausePolicy.onDeliberateStart(MusicPauseState(otherApp = hold)).otherApp)
        }
        assertFalse("a start is not a play press", MusicPausePolicy.onDeliberateStart(clear).user)
        assertEquals(clear, MusicPausePolicy.onDeliberateStart(clear))
        // So a request made after a start is not met by the standing yield.
        val yielded = MusicPausePolicy.onStartCourtesy(clear)
        assertEquals(none, MusicPausePolicy.bedFocusDecision(yielded, false, deliberate = true, otherPlayerPlaying = true))
        assertEquals(request, MusicPausePolicy.bedFocusDecision(MusicPausePolicy.onDeliberateStart(yielded), false, deliberate = true, otherPlayerPlaying = true))
    }

    @Test
    fun `turning Music on is a play, so it ends the player's pause and another app's hold and keeps the device's holds`() {
        val held = MusicPauseState(user = true, headphones = true, screenOff = true, schedule = true, call = true, otherApp = OtherAppHold.PERMANENT)
        val on = MusicPausePolicy.onMusicTurnedOn(held)
        assertFalse("the player's own earlier pause is gone", on.user)
        assertEquals(OtherAppHold.NONE, on.otherApp)
        assertEquals("the cable, the screen, the hours and a call are not the player's to clear here", held.copy(user = false, otherApp = OtherAppHold.NONE), on)
        // Control: the plain start (the folder pick) leaves the player's pause alone.
        assertTrue(MusicPausePolicy.onDeliberateStart(held).user)
        // So Music on plays, taking the audio over: with the pause kept nothing would be asked for.
        val paused = MusicPauseState(user = true)
        assertEquals(none, MusicPausePolicy.focusDecision(paused, gameInFront = false, deliberate = true, otherPlayerPlaying = true))
        assertEquals(request, MusicPausePolicy.focusDecision(MusicPausePolicy.onMusicTurnedOn(paused), gameInFront = false, deliberate = true, otherPlayerPlaying = true))
        assertEquals(clear, MusicPausePolicy.onMusicTurnedOn(paused))
    }

    @Test
    fun `choosing a music source is a play too, so a pause pressed on the other source does not keep it silent`() {
        // The player pressed pause on Your music, then chose a source in Settings: the choice plays, as turning Music on does.
        val paused = MusicPauseState(user = true)
        assertEquals(none, MusicPausePolicy.focusDecision(MusicPausePolicy.onDeliberateStart(paused), gameInFront = false, deliberate = true, otherPlayerPlaying = true))
        val chosen = MusicPausePolicy.onSourceChosen(paused)
        assertFalse("the player's own earlier pause is gone", chosen.user)
        assertEquals(clear, chosen)
        assertEquals(request, MusicPausePolicy.focusDecision(chosen, gameInFront = false, deliberate = true, otherPlayerPlaying = true))
        // Another app's hold ends with it, and the device's holds stay: the cable, the screen, the music hours and a call.
        val held = MusicPauseState(user = true, headphones = true, screenOff = true, schedule = true, call = true, otherApp = OtherAppHold.PERMANENT)
        assertEquals(held.copy(user = false, otherApp = OtherAppHold.NONE), MusicPausePolicy.onSourceChosen(held))
        assertEquals("unplugged headphones still hold it", MusicPauseReason.HEADPHONES, MusicPausePolicy.onSourceChosen(MusicPauseState(user = true, headphones = true)).reason)
        // The same clearing as turning Music on; the folder pick keeps the plain start.
        assertEquals(MusicPausePolicy.onMusicTurnedOn(held), MusicPausePolicy.onSourceChosen(held))
    }

    // ── 2. A passive start never takes focus from another player ──────────────────────────────────────────────────

    @Test
    fun `a passive start of the theme bed with another app playing yields and is silent`() {
        val decision = MusicPausePolicy.bedFocusDecision(clear, gameInFront = false, deliberate = false, otherPlayerPlaying = true)
        assertEquals(yieldToOther, decision)
        val yielded = MusicPausePolicy.onStartCourtesy(clear)
        assertEquals(OtherAppHold.PERMANENT, yielded.otherApp)
        // The bed obeys that hold: silent, whatever the rest says.
        assertTrue(MusicPausePolicy.heldByOtherApp(yielded))
        assertEquals(0f, FrontendMusicPolicy.targetVolume(bedInput.copy(otherAppHeld = MusicPausePolicy.heldByOtherApp(yielded))), 0f)
    }

    @Test
    fun `a passive start of the theme bed with nobody playing asks for no focus at all`() {
        assertEquals(none, MusicPausePolicy.bedFocusDecision(clear, gameInFront = false, deliberate = false, otherPlayerPlaying = false))
    }

    @Test
    fun `a passive start of Your music with another app playing yields, as it always did`() {
        assertEquals(yieldToOther, MusicPausePolicy.focusDecision(clear, gameInFront = false, deliberate = false, otherPlayerPlaying = true))
        assertEquals(request, MusicPausePolicy.focusDecision(clear, gameInFront = false, deliberate = false, otherPlayerPlaying = false))
    }

    // ── 3. While Mimir's music plays, another app starting pauses it ──────────────────────────────────────────────

    @Test
    fun `a focus loss while the bed holds focus pauses it, and a transient one ends with its gain`() {
        val lost = MusicPausePolicy.onFocus(clear, FocusSignal.LOSS, gameInFront = false)
        assertTrue(MusicPausePolicy.heldByOtherApp(lost))
        assertEquals(0f, FrontendMusicPolicy.targetVolume(bedInput.copy(otherAppHeld = true)), 0f)
        // Control: nothing held, the bed plays at the rail's level.
        assertEquals(0.5f, FrontendMusicPolicy.targetVolume(bedInput.copy(otherAppHeld = false)), 0.0001f)

        val transient = MusicPausePolicy.onFocus(clear, FocusSignal.LOSS_TRANSIENT, gameInFront = false)
        assertTrue(MusicPausePolicy.heldByOtherApp(transient))
        assertFalse(MusicPausePolicy.heldByOtherApp(MusicPausePolicy.onFocus(transient, FocusSignal.GAIN, gameInFront = false)))
        // A short ducking prompt neither pauses nor needs a resume.
        assertFalse(MusicPausePolicy.heldByOtherApp(MusicPausePolicy.onFocus(clear, FocusSignal.LOSS_TRANSIENT_CAN_DUCK, gameInFront = false)))
    }

    @Test
    fun `another app starting while an unfocused bed plays pauses it, unless a game is in front or a hold is set`() {
        // The bed sounds without focus: the newcomer's request reached no Mimir listener, so Mimir yields by hand.
        assertTrue(MusicPausePolicy.yieldsToStartedPlayer(focusHeld = false, ownMusicSounding = true, state = clear, gameInFront = false))
        val yielded = MusicPausePolicy.onStartCourtesy(clear)
        assertTrue("the bed obeys the hold", MusicPausePolicy.heldByOtherApp(yielded))
        assertEquals(0f, FrontendMusicPolicy.targetVolume(bedInput.copy(otherAppHeld = true)), 0f)
        // Control arms.
        assertFalse("focus is held: the loss callback does the work", MusicPausePolicy.yieldsToStartedPlayer(true, true, clear, false))
        assertFalse("the bed is not sounding", MusicPausePolicy.yieldsToStartedPlayer(false, false, clear, false))
        assertFalse("a game is in front: the exemption stands", MusicPausePolicy.yieldsToStartedPlayer(false, true, clear, true))
        assertFalse("already held", MusicPausePolicy.yieldsToStartedPlayer(false, true, yielded, false))
    }

    @Test
    fun `the bed obeys another app's hold and none of Your music's own`() {
        assertFalse(MusicPausePolicy.heldByOtherApp(MusicPauseState(user = true)))
        assertFalse(MusicPausePolicy.heldByOtherApp(MusicPauseState(headphones = true)))
        assertFalse(MusicPausePolicy.heldByOtherApp(MusicPauseState(screenOff = true)))
        assertFalse("the hours reach the bed on their own flag", MusicPausePolicy.heldByOtherApp(MusicPauseState(schedule = true)))
        // The single reason outranks the other app with the player's pause or the cable, which would hide it; the bed reads the hold itself.
        val masked = MusicPauseState(headphones = true, otherApp = OtherAppHold.PERMANENT)
        assertEquals(MusicPauseReason.HEADPHONES, masked.reason)
        assertTrue(MusicPausePolicy.heldByOtherApp(masked))
        assertTrue(MusicPausePolicy.heldByOtherApp(MusicPauseState(call = true)))
        for (hold in OtherAppHold.entries.filter { it != OtherAppHold.NONE }) {
            assertTrue(MusicPausePolicy.heldByOtherApp(MusicPauseState(otherApp = hold)))
        }
        // Stale holds of Your music never keep the bed from asking.
        val stale = MusicPauseState(user = true, headphones = true, screenOff = true)
        assertEquals(request, MusicPausePolicy.bedFocusDecision(stale, false, deliberate = true, otherPlayerPlaying = false))
        assertEquals(MusicPauseState(), MusicPausePolicy.forBed(stale))
    }

    @Test
    fun `Your music still reads the hold through its pause reason, unchanged`() {
        val yours = bedInput.copy(everywhere = true, pauseReason = MusicPauseReason.OTHER_APP)
        assertEquals(0f, FrontendMusicPolicy.targetVolume(yours), 0f)
        // The bed's own flag does not reach the everywhere path.
        assertEquals(0.5f, FrontendMusicPolicy.targetVolume(bedInput.copy(everywhere = true, pauseReason = null, otherAppHeld = true)), 0.0001f)
    }

    // ── 4. A game in front: the existing rule ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a deliberate start during a game in front requests nothing, for either source, and the game is not taken from`() {
        assertEquals(none, MusicPausePolicy.bedFocusDecision(clear, gameInFront = true, deliberate = true, otherPlayerPlaying = true))
        assertEquals(none, MusicPausePolicy.bedFocusDecision(clear, gameInFront = true, deliberate = true, otherPlayerPlaying = false))
        assertEquals(none, MusicPausePolicy.focusDecision(clear, gameInFront = true, deliberate = true, otherPlayerPlaying = true))
        assertTrue(MusicPausePolicy.abandonsFocusForGame(focusHeld = true, gameInFront = true))
        // A loss during a game is ignored for both.
        assertFalse(MusicPausePolicy.heldByOtherApp(MusicPausePolicy.onFocus(clear, FocusSignal.LOSS, gameInFront = true)))
    }

    @Test
    fun `a call, an unfulfilled hold or the music hours stop the bed asking, the way they stop Your music`() {
        assertEquals(none, MusicPausePolicy.bedFocusDecision(MusicPauseState(call = true), false, deliberate = true, otherPlayerPlaying = false))
        assertEquals(none, MusicPausePolicy.bedFocusDecision(MusicPauseState(schedule = true), false, deliberate = true, otherPlayerPlaying = false))
        assertEquals(none, MusicPausePolicy.bedFocusDecision(MusicPauseState(otherApp = OtherAppHold.TRANSIENT), false, true, false))
        assertEquals(request, MusicPausePolicy.bedFocusDecision(MusicPauseState(otherApp = OtherAppHold.NEEDS_FOCUS), false, true, false))
    }

    // ── The deliberate mark ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a deliberate start stands for a short window, is spent by its request, and never outlives it`() {
        val mark = DeliberateStart(windowMs = 5_000L)
        assertFalse("nothing marked", mark.live(0L))
        mark.mark(10_000L)
        assertTrue(mark.live(10_000L))
        assertTrue("the flows that carry the want land within the window", mark.live(14_999L))
        assertTrue(mark.live(15_000L))
        assertFalse("a volume raised later is not a start", mark.live(15_001L))
        assertFalse("a clock that reads earlier is not live", mark.live(9_999L))
        mark.mark(20_000L)
        mark.spend()
        assertFalse("spent by the request", mark.live(20_001L))
        assertEquals("the default window", 5_000L, DeliberateStartWindowMs)
    }

    // ── The bed's way out of a PERMANENT hold (the one place the rule lives) ──────────────────────────────────────

    private fun session(playing: Boolean) = OtherPlayerSnapshot("Song", "Artist", "YouTube", artwork = null, isPlaying = playing)

    @Test
    fun `the bed lets go of a permanent hold when no other player is audible, Your music still needs the session or a play`() {
        // The bed has no play button: no grant, a quiet stretch, and it ends.
        assertTrue(MusicPausePolicy.otherPlayerQuiet(sessionAccessGranted = false, session = null, bedIsSource = true, strangerQuiet = true))
        assertFalse("another player is audible", MusicPausePolicy.otherPlayerQuiet(false, null, bedIsSource = true, strangerQuiet = false))
        // Your music: the heuristic never ends it (a player that merely buffers is not a player that stopped).
        assertFalse(MusicPausePolicy.otherPlayerQuiet(false, null, bedIsSource = false, strangerQuiet = true))
        // With the grant both read the session.
        assertTrue(MusicPausePolicy.otherPlayerQuiet(true, session(playing = false), bedIsSource = false, strangerQuiet = false))
        assertFalse(MusicPausePolicy.otherPlayerQuiet(true, session(playing = true), bedIsSource = false, strangerQuiet = true))
        assertFalse(MusicPausePolicy.otherPlayerQuiet(true, session(playing = true), bedIsSource = true, strangerQuiet = false))
        // And it runs through the same ten-second streak as Your music's.
        val streak = ConfirmedPausedStreak()
        assertFalse(streak.onSample(stopped = true, atMs = 1_000L))
        assertFalse(streak.onSample(stopped = true, atMs = 10_999L))
        assertTrue(streak.onSample(stopped = true, atMs = 11_000L))
        assertNull(MusicPausePolicy.onOtherSessionStopped(MusicPausePolicy.onStartCourtesy(clear)).reason)
    }

    @Test
    fun `the bed holds no request, so a hold only a gain could end ends with the request`() {
        assertEquals(clear, MusicPausePolicy.onBedFocusAbandoned(MusicPauseState(otherApp = OtherAppHold.TRANSIENT)))
        assertEquals(clear, MusicPausePolicy.onBedFocusAbandoned(MusicPauseState(otherApp = OtherAppHold.NEEDS_FOCUS)))
        val permanent = MusicPauseState(otherApp = OtherAppHold.PERMANENT)
        assertEquals("a permanent hold has its own end", permanent, MusicPausePolicy.onBedFocusAbandoned(permanent))
        assertEquals(MusicPauseState(call = true), MusicPausePolicy.onBedFocusAbandoned(MusicPauseState(call = true, otherApp = OtherAppHold.TRANSIENT)))
        // Your music's door is unchanged: a transient hold waits for a fresh grant.
        assertEquals(OtherAppHold.NEEDS_FOCUS, MusicPausePolicy.onFocusAbandoned(MusicPauseState(otherApp = OtherAppHold.TRANSIENT)).otherApp)
    }

    // ── What the bed wants focus for ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the bed wants to sound exactly when Music is on, audible, in Mimir's front, with no game and inside the hours`() {
        assertTrue(FrontendMusicPolicy.bedWantsToSound(bedInput))
        assertFalse("Music off", FrontendMusicPolicy.bedWantsToSound(bedInput.copy(musicEnabled = false)))
        assertFalse("level zero asks for no focus", FrontendMusicPolicy.bedWantsToSound(bedInput.copy(musicVolume = 0f)))
        assertFalse("Mimir is not in front", FrontendMusicPolicy.bedWantsToSound(bedInput.copy(appForeground = false)))
        assertFalse("a game session", FrontendMusicPolicy.bedWantsToSound(bedInput.copy(focus = AudioFocusState.Suspended)))
        assertFalse("outside the music hours", FrontendMusicPolicy.bedWantsToSound(bedInput.copy(scheduleBlocked = true)))
        assertFalse("Your music is the plan", FrontendMusicPolicy.bedWantsToSound(bedInput.copy(everywhere = true)))
        // What it deliberately leaves out: the environment and another app's hold, which are how Mimir learns someone else plays.
        assertTrue(FrontendMusicPolicy.bedWantsToSound(bedInput.copy(environmentBlocked = true, competingOwnedBed = true, otherAppHeld = true)))
    }

    // ── The control's wiring, pinned on its source ───────────────────────────────────────────────────────────────

    @Test
    fun `the control asks for focus for the bed only on a deliberate start, and Your music reads the same mark`() {
        val control = source("MimirMusicControl.kt")
        val reconcile = control.substringAfter("private fun reconcile() {").substringBefore("private fun letGoOfFocus")
        assertTrue(reconcile.contains("_bedWantsFocus.value && inMimir.value"))
        assertTrue(reconcile.contains("MusicPausePolicy.bedFocusDecision(pauseState.value, game, deliberateNow, _strangerAudio.value)"))
        assertTrue(reconcile.contains("MusicPausePolicy.focusDecision(state, game, deliberateNow, _strangerAudio.value)"))
        assertEquals("one request site serves both sources", 1, Regex("""manager\.requestAudioFocus\(newFocusRequest\(\)\)""").findAll(control).count())
        assertTrue("a game in front spends the mark", reconcile.contains("if (game) deliberate.spend()"))
        assertTrue(control.contains("fun onDeliberateStart()"))
        assertTrue(control.contains("MusicPausePolicy::onDeliberateStart"))
        assertTrue("Music on is a play", control.contains("fun onMusicTurnedOn()") && control.contains("MusicPausePolicy::onMusicTurnedOn"))
        assertTrue("choosing the source is a play", control.contains("fun onSourceChosen()") && control.contains("MusicPausePolicy::onSourceChosen"))
        assertTrue("a play press marks it", control.contains("if (yoursPause().paused) deliberate.mark(SystemClock.uptimeMillis()) else deliberate.spend()"))
        assertFalse("the boolean is gone", control.contains("deliberatePlay"))
        assertTrue("the bed's want wakes reconcile", control.contains("combine(yourMusicActive, _bedWantsFocus, audible, placed)"))
        assertTrue("the hold is published for the engine", control.contains("_otherAppHeld.value = MusicPausePolicy.heldByOtherApp(after)"))
        assertTrue("the bed's quiet stretch is watched", control.contains("MusicPausePolicy.otherPlayerQuiet("))
    }

    @Test
    fun `the engine tells the control when the bed wants sound and lets the hold silence it`() {
        val engine = source("MimirFrontendMusic.kt")
        assertTrue(engine.contains("FrontendMusicPolicy.bedWantsToSound(input)"))
        assertTrue(engine.contains("MimirMusicControl.setBedWantsFocus(it)"))
        assertTrue(engine.contains("bedPlan.value = next is FrontendMusicPlan.Bed"))
        assertTrue(engine.contains("otherAppHeld = control.otherAppHeld"))
        assertTrue(engine.contains("MimirMusicControl.otherAppHeld"))
    }

    private fun source(name: String): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/$name").readText()
}
