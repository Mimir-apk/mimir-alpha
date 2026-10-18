package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * When the player starts Mimir's music on purpose it plays even over an app
 * that holds no audio focus (a game left running behind Mimir). Passive starts are unchanged, and a call still interrupts.
 */
class DeliberateTakeoverTest {
    // ── 2. A deliberate start plays over a player that holds no focus; a call still interrupts ─────────────────────

    private val passiveStart = FrontendMusicPolicyInput(
        appForeground = true,
        musicVolume = 0.5f,
        focus = AudioFocusState.Foreground,
        environmentBlocked = true,
        competingOwnedBed = false,
    )

    @Test
    fun `a deliberate start sounds over a stream that holds no focus, and a passive one stays behind the gate`() {
        assertEquals("passive: unchanged", 0f, FrontendMusicPolicy.targetVolume(passiveStart), 0f)
        assertEquals("deliberate: the player's choice plays", 0.5f, FrontendMusicPolicy.targetVolume(passiveStart.copy(environmentWaived = true)), 0f)
    }

    @Test
    fun `a call interrupts a deliberate start too`() {
        val call = passiveStart.copy(environmentWaived = true, callInterrupted = true)
        assertEquals(0f, FrontendMusicPolicy.targetVolume(call), 0f)
        assertEquals("a call and nothing else blocking", 0f, FrontendMusicPolicy.targetVolume(call.copy(environmentBlocked = false)), 0f)
    }

    @Test
    fun `a deliberate start still yields to another app's hold, the hours, a game in front and Mimir leaving`() {
        val playing = passiveStart.copy(environmentWaived = true)
        assertEquals(0.5f, FrontendMusicPolicy.targetVolume(playing), 0f)
        assertEquals("another app took the audio", 0f, FrontendMusicPolicy.targetVolume(playing.copy(otherAppHeld = true)), 0f)
        assertEquals("outside the music hours", 0f, FrontendMusicPolicy.targetVolume(playing.copy(scheduleBlocked = true)), 0f)
        assertEquals("a game is opening", 0f, FrontendMusicPolicy.targetVolume(playing.copy(focus = AudioFocusState.Suspended)), 0f)
        assertEquals("Mimir is not in front", 0f, FrontendMusicPolicy.targetVolume(playing.copy(appForeground = false)), 0f)
        assertEquals("Music is off", 0f, FrontendMusicPolicy.targetVolume(playing.copy(musicEnabled = false)), 0f)
        assertEquals("level zero", 0f, FrontendMusicPolicy.targetVolume(playing.copy(musicVolume = 0f)), 0f)
    }

    @Test
    fun `the take-over rises with a live deliberate start while the bed is wanted, and only then`() {
        assertTrue("wanted, deliberate", BedTakeover.next(current = false, bedWanted = true, deliberateLive = true))
        assertFalse("wanted, passive (a cold start, the return to Mimir)", BedTakeover.next(current = false, bedWanted = true, deliberateLive = false))
        assertFalse("not wanted yet, the flows have not landed", BedTakeover.next(current = false, bedWanted = false, deliberateLive = true))
    }

    @Test
    fun `the take-over stands while the bed stays wanted, and ends when it is not wanted`() {
        assertTrue("the mark is spent by the focus request; the bed still plays", BedTakeover.next(current = true, bedWanted = true, deliberateLive = false))
        assertFalse("Music off, level zero, Mimir left, a game opened", BedTakeover.next(current = true, bedWanted = false, deliberateLive = false))
        assertTrue("a fresh start waiting for its flows does not undo one standing", BedTakeover.next(current = true, bedWanted = false, deliberateLive = true))
    }

    @Test
    fun `a game launched inside the start's window ends the take-over, and Mimir leaving the front does too`() {
        // The window is still open (the mark is only spent by a focus request, which a bed that already holds focus never makes).
        assertFalse("a game is in front", BedTakeover.next(current = true, bedWanted = false, deliberateLive = true, gameInFront = true))
        assertFalse("even with the bed wanted and the start live", BedTakeover.next(current = true, bedWanted = true, deliberateLive = true, gameInFront = true))
        assertFalse("and it is not raised while a game is in front", BedTakeover.next(current = false, bedWanted = true, deliberateLive = true, gameInFront = true))
        assertFalse("Mimir is not in front", BedTakeover.next(current = true, bedWanted = true, deliberateLive = true, mimirInFront = false))
        assertFalse("nor raised from outside Mimir", BedTakeover.next(current = false, bedWanted = true, deliberateLive = true, mimirInFront = false))
    }

    @Test
    fun `another app taking the audio ends the take-over for good, and the open window does not bring it back`() {
        assertFalse("held by another app (a focus loss, a newcomer, a call)", BedTakeover.next(current = true, bedWanted = true, deliberateLive = false, otherAppHeld = true))
        assertFalse("not raised by a window that is still open", BedTakeover.next(current = false, bedWanted = true, deliberateLive = true, otherAppHeld = true))
        // The control spends the mark when it sees the hold, so when the hold ends the window is closed and a passive reconcile cannot raise it.
        assertFalse("after the hold, with the mark spent", BedTakeover.next(current = false, bedWanted = true, deliberateLive = false))
    }

    @Test
    fun `the control asks about the game and the hold before raising the waiver, spends the mark on a hold or a newcomer, and serves the waiver to the engine`() {
        val control = source("MimirMusicControl.kt")
        val reconcile = control.substringAfter("private fun reconcile(look: Lazy<Boolean> = gameLook()) {").substringBefore("val game = look.value")
        assertTrue(reconcile.contains("val gameFront = (_bedTakeover.value || (bedWanted && deliberateLive)) && look.value"))
        assertTrue("a game outliving the start spends it", reconcile.contains("if (gameFront) deliberate.spend()"))
        for (input in listOf("gameInFront = gameFront", "mimirInFront = inMimir.value", "otherAppHeld = _otherAppHeld.value", "bedWanted = bedWanted", "deliberateLive = deliberateLive")) {
            assertTrue(input, reconcile.contains(input))
        }
        assertTrue("before the focus request spends the mark", reconcile.indexOf("BedTakeover.next") < reconcile.indexOf("letGoOfFocus(manager)"))
        val mutate = control.substringAfter("private fun mutatePause(").substringBefore("Log.i(Tag, \"pause: \$why")
        val held = mutate.substringAfter("if (_otherAppHeld.value) {").substringBefore("}")
        assertTrue("a hold ends the waiver", held.contains("setBedTakeover(false)"))
        assertTrue("and spends the start, so the window cannot raise it again", held.contains("deliberate.spend()"))
        assertTrue("both ends are in the log", control.contains("the theme bed plays over other audio: the player started it") && control.contains("the theme bed meets the courtesy gate again"))
        val engine = source("MimirFrontendMusic.kt")
        assertTrue(engine.contains("environmentWaived = control.bedTakeover"))
        assertTrue("the call rides on its own into the policy", engine.contains("callInterrupted = env.callInterrupted"))
        assertTrue(engine.contains("MimirMediaBed.callInterrupted"))
        val bed = source("MimirMediaBed.kt")
        assertTrue(bed.contains("_callInterrupted.value = callInterrupted"))
    }

    @Test
    fun `a stranger that starts without asking for focus ends the take-over even while the bed holds focus`() {
        // yieldsToStartedPlayer reaches only music that holds no focus: a bed that holds it would otherwise play over the newcomer, past the courtesy gate.
        assertFalse("the yield does not apply while focus is held", MusicPausePolicy.yieldsToStartedPlayer(focusHeld = true, ownMusicSounding = true, state = MusicPauseState(), gameInFront = false))
        val control = source("MimirMusicControl.kt")
        val publish = control.substringAfter("private val publishStranger = Runnable {").substringBefore("private val focusAttributes")
        val rise = publish.substringAfter("if (strangerHeading) {").substringBefore("}")
        assertTrue("any rise ends the take-over", rise.contains("setBedTakeover(false)"))
        assertTrue("and spends the start", rise.contains("deliberate.spend()"))
        assertTrue("outside the focus-less yield, before it", publish.indexOf("setBedTakeover(false)") < publish.indexOf("MusicPausePolicy.yieldsToStartedPlayer"))
        // The waiver gone, the courtesy gate is what holds the bed: the newcomer is a stranger there and blocks it.
        val passive = FrontendMusicPolicyInput(appForeground = true, musicVolume = 0.5f, focus = AudioFocusState.Foreground, environmentBlocked = true, competingOwnedBed = false)
        assertEquals(0f, FrontendMusicPolicy.targetVolume(passive.copy(environmentWaived = BedTakeover.next(true, bedWanted = true, deliberateLive = false, otherAppHeld = true))), 0f)
    }

    private fun source(name: String): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/$name").readText()
}
