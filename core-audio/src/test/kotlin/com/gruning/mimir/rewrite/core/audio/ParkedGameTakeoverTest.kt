package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A game PARKED behind Mimir (HOME, or switched away) keeps its session live, but it is not
 * in front while Mimir is. The player's own start then takes the audio: the other app pauses, the theme plays. When the game is back on screen
 * Mimir lets go. Observed on Thor with a kept Azahar game and YouTube Music playing.
 */
class ParkedGameTakeoverTest {
    private fun inFront(sessionLive: Boolean, mimirInFront: Boolean, evidence: Boolean) =
        MusicPausePolicy.gameInFront(sessionLive, mimirInFront) { evidence }

    @Test
    fun `a live session with Mimir on screen is a parked game, not a game in front`() {
        assertFalse("parked behind Mimir", inFront(sessionLive = true, mimirInFront = true, evidence = false))
        assertTrue("the same session with Mimir off the front", inFront(sessionLive = true, mimirInFront = false, evidence = false))
        assertFalse("no session, Mimir in front", inFront(sessionLive = false, mimirInFront = true, evidence = false))
        assertFalse("no session, Mimir away, nothing seen", inFront(sessionLive = false, mimirInFront = false, evidence = false))
    }

    @Test
    fun `evidence of a game in front stands whatever the session says`() {
        // A launch armed, an emulator package in front, or a live game on the second screen while Mimir stays on the first (the app hands that over as
        // evidence, because any Main resume clears the launch's Suspended and a Bottom game holds no Main lease): Mimir is in front and a game is too.
        assertTrue(inFront(sessionLive = true, mimirInFront = true, evidence = true))
        assertTrue(inFront(sessionLive = false, mimirInFront = true, evidence = true))
        assertTrue(inFront(sessionLive = false, mimirInFront = false, evidence = true))
    }

    @Test
    fun `the costly read is only made when the session term has not answered`() {
        var reads = 0
        val read = { reads++; false }
        assertTrue(MusicPausePolicy.gameInFront(sessionLive = true, mimirInFront = false, evidence = read))
        assertEquals("a live session behind Mimir answers by itself", 0, reads)
        assertFalse(MusicPausePolicy.gameInFront(sessionLive = true, mimirInFront = true, evidence = read))
        assertFalse(MusicPausePolicy.gameInFront(sessionLive = false, mimirInFront = true, evidence = read))
        assertEquals("a parked game, and no game, ask once each", 2, reads)
    }

    @Test
    fun `a parked game then a deliberate start asks for focus, ends the other app's hold, and the bed sounds`() {
        // YouTube Music plays; the bed yielded to it (the start courtesy), so a PERMANENT hold stands.
        val yielded = MusicPausePolicy.onStartCourtesy(MusicPauseState())
        val parked = inFront(sessionLive = true, mimirInFront = true, evidence = false)

        // Passive: still yields to the player, and takes nothing.
        assertEquals(
            MusicPausePolicy.FocusDecision.NONE,
            MusicPausePolicy.bedFocusDecision(yielded, parked, deliberate = false, otherPlayerPlaying = true),
        )
        // The player turns the music on: the hold ends and the request is made, whoever is playing.
        val started = MusicPausePolicy.onMusicTurnedOn(yielded)
        assertEquals("the other app's hold ends", OtherAppHold.NONE, started.otherApp)
        assertEquals(
            MusicPausePolicy.FocusDecision.REQUEST,
            MusicPausePolicy.bedFocusDecision(started, parked, deliberate = true, otherPlayerPlaying = true),
        )
        assertFalse("the granted request is not let go while the game stays parked", MusicPausePolicy.abandonsFocusForGame(focusHeld = true, gameInFront = parked))
        // The waiver rises, so the kept game's stream does not hold the bed behind the courtesy gate, and the bed sounds.
        val waived = BedTakeover.next(current = false, bedWanted = true, deliberateLive = true, gameInFront = parked, mimirInFront = true, otherAppHeld = false)
        assertTrue(waived)
        val input = FrontendMusicPolicyInput(
            appForeground = true,
            musicVolume = 0.5f,
            focus = AudioFocusState.Foreground,
            environmentBlocked = true,
            competingOwnedBed = false,
            environmentWaived = waived,
        )
        assertEquals("the bed plays over the parked game's stream", 0.5f, FrontendMusicPolicy.targetVolume(input), 0f)
    }

    @Test
    fun `the game back on screen makes Mimir let go, drops the waiver and silences the bed`() {
        val onScreen = inFront(sessionLive = true, mimirInFront = false, evidence = false)
        assertTrue(onScreen)
        assertTrue("the focus it holds is let go", MusicPausePolicy.abandonsFocusForGame(focusHeld = true, gameInFront = onScreen))
        assertEquals(
            "and none is asked for",
            MusicPausePolicy.FocusDecision.NONE,
            MusicPausePolicy.bedFocusDecision(MusicPauseState(), onScreen, deliberate = true, otherPlayerPlaying = true),
        )
        assertFalse(BedTakeover.next(current = true, bedWanted = true, deliberateLive = true, gameInFront = onScreen, mimirInFront = false))
        val input = FrontendMusicPolicyInput(
            appForeground = false,
            musicVolume = 0.5f,
            focus = AudioFocusState.Foreground,
            environmentBlocked = true,
            competingOwnedBed = false,
            environmentWaived = false,
        )
        assertEquals(0f, FrontendMusicPolicy.targetVolume(input), 0f)
    }

    @Test
    fun `Your music with a game parked is no longer exempt from another app's loss`() {
        // A parked game is not what took the focus: another app's loss pauses Your music as it does with no game (the exemption is for a game in front).
        val parked = inFront(sessionLive = true, mimirInFront = true, evidence = false)
        assertEquals(OtherAppHold.PERMANENT, MusicPausePolicy.onFocus(MusicPauseState(), FocusSignal.LOSS, parked).otherApp)
        val onScreen = inFront(sessionLive = true, mimirInFront = false, evidence = false)
        assertEquals(OtherAppHold.NONE, MusicPausePolicy.onFocus(MusicPauseState(), FocusSignal.LOSS, onScreen).otherApp)
    }

    @Test
    fun `the control composes the signal once and every decision reads it`() {
        val control = source("MimirMusicControl.kt")
        val gameInFront = control.substringAfter("private fun gameInFront(): Boolean").substringBefore("@OptIn")
        assertTrue(
            "the probe's session term and the evidence, with the control's own word on whether Main is resumed",
            gameInFront.contains("MusicPausePolicy.gameInFront(sessionLive = probe.sessionLive(), mimirInFront = mainResumed, evidence = { probe.onScreen() || probe.foreground() })"),
        )
        assertFalse("not the started edge, which comes after the game is up", gameInFront.contains("inMimir"))
        // The session is read in the two compositions only (the full one and the cheap one the media bed is told).
        assertEquals(2, Regex("""probe\.sessionLive\(\)""").findAll(control).count())
        assertTrue("the lift", control.contains("&& look.value"))
        assertTrue("the abandon", control.contains("MusicPausePolicy.abandonsFocusForGame(focusHeld, look.value)"))
        val reconcile = control.substringAfter("private fun reconcile(look: Lazy<Boolean> = gameLook()) {").substringBefore("private fun letGoOfFocus")
        assertTrue("the request", reconcile.contains("val game = look.value") && reconcile.contains("MusicPausePolicy.bedFocusDecision(pauseState.value, game,"))
        assertTrue("the log says what the request saw", reconcile.contains("deliberate=\$deliberateNow gameInFront=\$game"))
        val app = File("../app/src/main/kotlin/com/gruning/mimir/rewrite/YourMusicEverywhere.kt").readText()
        assertTrue(
            "the app hands over the session term and the evidence apart",
            app.contains("sessionLive = { musicSessionGameLive(MimirSessionAuthority.state.value) }") &&
                app.contains("foreground = { isGamePackage(monitor.foregroundPackageNow()) }") &&
                app.contains("tracker.gameInFront(sessionGameLive = false, launchArmed = MimirAudioFocus.state.value is AudioFocusState.Suspended)"),
        )
    }

    // ── Round 4: the way back from a park, the Bottom tab, and the cost of asking ──────────────────────────────────────

    @Test
    fun `Main pausing, not stopping, is what puts a live session in front, so Mimir lets go before the game asks for focus`() {
        // Main is paused as the game's window comes over it, BEFORE the game's own onResume asks for audio focus; the started edge comes after.
        assertFalse("Main resumed, a session parked behind it", inFront(sessionLive = true, mimirInFront = true, evidence = false))
        assertTrue("Main paused, the same session", inFront(sessionLive = true, mimirInFront = false, evidence = false))
        assertTrue("the focus it holds is let go at that moment", MusicPausePolicy.abandonsFocusForGame(focusHeld = true, gameInFront = inFront(true, false, false)))
        val control = source("MimirMusicControl.kt")
        val api = control.substringAfter("fun onMainResumed(resumed: Boolean) {").substringBefore("/** Written by [onMainResumed]")
        assertTrue(api.contains("mainResumed = resumed") && api.contains("onGameFrontMayHaveChanged()"))
        assertTrue("written before the pass is posted", api.indexOf("mainResumed = resumed") < api.indexOf("onGameFrontMayHaveChanged()"))
        // The app feeds it from Main's onResume and onPause.
        val main = File("../app/src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt").readText()
        val resume = main.substringAfter("override fun onResume() {").substringBefore("override fun onPause")
        val pause = main.substringAfter("override fun onPause() {").substringBefore("override fun onStop")
        assertTrue(resume.contains("YourMusicEverywhere.onMainResumed()"))
        assertTrue(pause.contains("YourMusicEverywhere.onMainPaused()"))
        assertTrue("fed before the database-recovery return", resume.indexOf("YourMusicEverywhere.onMainResumed()") < resume.indexOf("return"))
    }

    @Test
    fun `a focus loss during the hand-back is a game's, not a permanent hold of another app`() {
        // Main is still resumed (the game's window is not up yet), a session is parked: only the hand-back evidence says "game".
        val duringHandBack = inFront(sessionLive = true, mimirInFront = true, evidence = true)
        assertEquals(OtherAppHold.NONE, MusicPausePolicy.onFocus(MusicPauseState(), FocusSignal.LOSS, duringHandBack).otherApp)
        assertEquals(OtherAppHold.NONE, MusicPausePolicy.onFocus(MusicPauseState(), FocusSignal.LOSS_TRANSIENT, duringHandBack).otherApp)
        // The same loss with no hand-back (the old late let-go) is recorded as another app's PERMANENT hold: Your music stayed silent through the game and after it.
        val late = inFront(sessionLive = true, mimirInFront = true, evidence = false)
        assertEquals(OtherAppHold.PERMANENT, MusicPausePolicy.onFocus(MusicPauseState(), FocusSignal.LOSS, late).otherApp)
    }

    private fun keptGame(): MimirKeptGameStreams<String> {
        val kept = MimirKeptGameStreams<String>(
            gameLikeSignature = { stream -> if (stream.startsWith("game")) mimirKeptGameSignature(android.media.AudioAttributes.USAGE_MEDIA, android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN) else null },
            isRealMedia = { it.startsWith("youtube") },
        )
        kept.onLaunchArmed(emptyList(), 0L)
        kept.onReturned(listOf("game-1"), strangerSessionPlaying = false, nowMs = 10_000L)
        return kept
    }

    private fun bedVolume(kept: MimirKeptGameStreams<String>, waived: Boolean): Float {
        val present = listOf("game-1")
        val blocked = mimirMediaBedEnvironmentBlocked(
            mimirMediaBedEnvironmentSample(
                musicActive = true,
                observedPlaybackCount = present.size,
                relevantPlaybackCount = present.size,
                keptGameStreams = kept.discount(present),
                ownedPlaybackCount = 0,
                callInterrupted = false,
            ),
        )
        return FrontendMusicPolicy.targetVolume(
            FrontendMusicPolicyInput(
                appForeground = true,
                musicVolume = 0.5f,
                focus = AudioFocusState.Foreground,
                environmentBlocked = blocked,
                competingOwnedBed = false,
                environmentWaived = waived,
            ),
        )
    }

    @Test
    fun `tapping the parked Bottom game's tab makes Mimir let go, so focus is released, the waiver ends and the bed goes silent`() {
        // A Bottom game HOME-parked and kept: the player turned Music on, so the bed plays over its stream under the waiver, holding focus.
        val kept = keptGame()
        val waived = BedTakeover.next(current = false, bedWanted = true, deliberateLive = true, gameInFront = false, mimirInFront = true)
        assertTrue(waived)
        assertEquals("the bed plays over the parked game", 0.5f, bedVolume(kept, waived), 0f)
        // The tab is tapped: the lease clears (no stop of Main, no Suspended), and the app's evidence for a live Bottom game with no lease says it is on screen.
        val onScreen = inFront(sessionLive = true, mimirInFront = true, evidence = true)
        assertTrue(onScreen)
        assertTrue("Mimir lets go of the focus it holds", MusicPausePolicy.abandonsFocusForGame(focusHeld = true, gameInFront = onScreen))
        val ended = BedTakeover.next(current = true, bedWanted = true, deliberateLive = false, gameInFront = onScreen, mimirInFront = true)
        assertFalse("the waiver ends", ended)
        // The media bed hears the same pass: the game's stream is not discounted while it is on screen, so the courtesy gate shuts and the bed is silent.
        assertTrue(kept.setFront(true))
        assertEquals("the bed is silent", 0f, bedVolume(kept, ended), 0f)
        // It is the game that went away again: the discount, unchanged, is back.
        assertTrue(kept.setFront(false))
        assertEquals(1, kept.discount(listOf("game-1")))
    }

    @Test
    fun `every lease change reaches the control, and the media bed is told at every pass by the cheap terms only`() {
        val control = source("MimirMusicControl.kt")
        val reconcile = control.substringAfter("private fun reconcile(look: Lazy<Boolean> = gameLook()) {").substringBefore("private fun letGoOfFocus")
        val first = reconcile.indexOf("MimirMediaBed.noteGameFront(gameOnScreenCheap())")
        assertTrue("told at the top of the pass, before the pass can let go and return", first > reconcile.indexOf("val manager = audioManager ?: return") && first < reconcile.indexOf("letGoOfFocus(manager)"))
        val cheap = control.substringAfter("private fun gameOnScreenCheap(): Boolean").substringBefore("/**")
        assertTrue(cheap.contains("evidence = probe.onScreen"))
        assertFalse("never the UsageStats read", cheap.contains("foreground"))
        val bed = source("MimirMediaBed.kt")
        assertTrue(bed.contains("fun noteGameFront(front: Boolean) {") && bed.contains("keptGame.setFront(front)"))
        // The launch and return notes set the same flag by Suspended alone, so the next pass re-asserts what is on screen.
        val edges = control.substringAfter("MimirAudioFocus.state.collect { focus ->").substringBefore("scope.launch {\n            // Focus is wanted or not")
        assertTrue(edges.contains("post(\"kept game edge\") { reconcile() }"))
        val parking = File("../app/src/main/kotlin/com/gruning/mimir/rewrite/MainGameParkingState.kt").readText()
        assertTrue("every mutation publishes, and the publish tells the observer", parking.contains("runCatching { leaseObserver() }"))
        assertEquals("a lease changes in four places, each publishing", 4, Regex("""publishHomeParked\(\)""").findAll(parking.substringAfter("private fun publishHomeParked()")).count())
        val music = File("../app/src/main/kotlin/com/gruning/mimir/rewrite/YourMusicEverywhere.kt").readText()
        assertTrue(music.contains("MainGameParkingState.leaseObserver = { MimirMusicControl.onGameFrontMayHaveChanged() }"))
    }

    @Test
    fun `the costly foreground read is made once for a pass or a callback, and handed down`() {
        val control = source("MimirMusicControl.kt")
        // One definition, one call (inside the lazy look); every pass and callback takes a look and passes it on.
        assertEquals(2, Regex("""\bgameInFront\(\)""").findAll(control).count())
        assertEquals("the foreground read is in the one composition", 1, Regex("""probe\.foreground\(\)""").findAll(control).count())
        assertTrue(control.contains("private fun gameLook(): Lazy<Boolean> = lazy(LazyThreadSafetyMode.NONE) { gameInFront() }"))
        val focus = control.substringAfter("private fun onFocusChange(generation: Long, change: Int) {").substringBefore("/** A new request with a listener")
        assertTrue("the callback takes one look, uses it, and hands it to the pass", focus.contains("val look = gameLook()") && focus.contains("reconcile(look)"))
        val publish = control.substringAfter("private val publishStranger = Runnable {").substringBefore("private val focusAttributes")
        assertTrue(publish.contains("val look = gameLook()") && publish.contains("reconcile(look)") && publish.contains("look.value"))
        // The pass reads its look three times at most and asks the probe once; it never calls the composition itself.
        val reconcile = control.substringAfter("private fun reconcile(look: Lazy<Boolean> = gameLook()) {").substringBefore("private fun letGoOfFocus")
        assertFalse(reconcile.contains("gameInFront()"))
        assertEquals(3, Regex("""look\.value""").findAll(reconcile).count())
        // And it stays on the control's handler thread: the look is a plain lazy with no lock and no thread of its own.
        assertTrue(control.contains("lazy(LazyThreadSafetyMode.NONE)"))
    }

    // ── The switch-button park (ᛗ): for audio, a HOME park  ───────────────────────────────────────

    private fun mainActivity(): String = File("../app/src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt").readText()

    @Test
    fun `Main's resume lets go of the launch lock outside the parked-game guard, and a fronted parked game arms it again`() {
        val main = mainActivity()
        val resume = main.substringAfter("override fun onResume() {").substringBefore("private fun settleReturningGameSession(")
        // The call itself, at eight spaces (the top level of onResume), and not the mention of it in the comment above.
        val gate = resume.indexOf("\n        MimirAudioFocus.onReturnedToMimir()\n")
        assertTrue("the return edge is a top-level statement of the resume", gate > 0)
        // The three teardown calls stay behind the guard, which sits after the audio gate.
        val guarded = resume.substringAfter("if (!parkedMainGame) {\n            // Mimir is foreground again").substringBefore("// R6-P1-01")
        assertFalse("the audio gate is not in the guarded block", guarded.contains("onReturnedToMimir"))
        assertTrue(guarded.contains("CompanionSceneState.disarmGameOwner()") && guarded.contains("CompanionState.clearRetainedNowPlaying()"))
        assertTrue("a park by the switch button says so", resume.contains("if (parkedMainGame) Log.i(\"MimirCompanionRuntime\", \"ᛗ park: Mimir is in front, the parked game is kept\")"))

        // Main stopping under the game arms the gate again: on the stop that cleared the restoring lease (Return, Resume) and while a Main lease stands (Recents).
        val stop = main.substringAfter("override fun onStop() {").substringBefore("override fun onNewIntent")
        val rearm = stop.substringAfter("if (!isChangingConfigurations) {\n")
        assertTrue(rearm.contains("gameSession?.operation?.let(MainGameParkingState::clearRestoringOnPrimaryStop) == true"))
        val arm = "if (mainStopArmsAudioGate(frontedParkedGame, MainGameParkingState.currentForCurrentSession())) {"
        assertTrue("armed by the restoring clear or by a standing Main lease (Recents)", rearm.contains(arm))
        assertTrue(rearm.contains("MimirAudioFocus.onGameLaunchArmed()"))
        assertTrue(
            "armed only after the clear has been read",
            rearm.indexOf("clearRestoringOnPrimaryStop") < rearm.indexOf(arm) &&
                rearm.indexOf(arm) < rearm.indexOf("MimirAudioFocus.onGameLaunchArmed()"),
        )
        assertFalse("one rule for both leases: no homeParked branch around it", rearm.substringBefore("primaryTouchLease").contains("homeParked"))
        assertTrue(rearm.contains("parked game covered: re-armed"))
        assertTrue(rearm.contains("game re-armed on return"))
    }

    @Test
    fun `a game parked behind the switch button reads Foreground for audio, so the player's start takes the audio and a loss is another app's`() {
        try {
            // The launch arms the gate; the park starts Main as HOME and Main's resume now lets it go (before this change it stayed Suspended).
            MimirAudioFocus.onGameLaunchArmed()
            MimirAudioFocus.onReturnedToMimir()
            val launchArmed = MimirAudioFocus.state.value is AudioFocusState.Suspended
            assertFalse("the park leaves nothing armed", launchArmed)
            // The app's evidence is the armed launch (and the hand-back, the Bottom tab, an emulator package in front): none of it stands.
            val parked = inFront(sessionLive = true, mimirInFront = true, evidence = launchArmed)
            assertFalse(parked)

            // YouTube Music plays on the Bottom and the bed yielded to it: passive takes nothing, a deliberate start asks.
            val yielded = MusicPausePolicy.onStartCourtesy(MusicPauseState())
            assertEquals(
                MusicPausePolicy.FocusDecision.NONE,
                MusicPausePolicy.bedFocusDecision(yielded, parked, deliberate = false, otherPlayerPlaying = true),
            )
            val started = MusicPausePolicy.onMusicTurnedOn(yielded)
            assertEquals(
                "a deliberate start with another player playing",
                MusicPausePolicy.FocusDecision.REQUEST,
                MusicPausePolicy.bedFocusDecision(started, parked, deliberate = true, otherPlayerPlaying = true),
            )
            // The Bottom app asks for focus: a LOSS reaches Mimir's listener and is that app's hold, not a game's.
            assertEquals(OtherAppHold.PERMANENT, MusicPausePolicy.onFocus(started, FocusSignal.LOSS, parked).otherApp)
            assertEquals(OtherAppHold.TRANSIENT, MusicPausePolicy.onFocus(started, FocusSignal.LOSS_TRANSIENT, parked).otherApp)

            // UI sounds and the bed follow the gate: both silent while it was Suspended (the defect), both live now.
            val parkedInput = FrontendMusicPolicyInput(
                appForeground = true,
                musicVolume = 0.5f,
                focus = MimirAudioFocus.state.value,
                environmentBlocked = false,
                competingOwnedBed = false,
            )
            assertEquals(0.5f, FrontendMusicPolicy.targetVolume(parkedInput), 0f)
            assertTrue(FrontendMusicPolicy.bedWantsToSound(parkedInput))
            assertEquals(PlayDecision.Play(0.4f), MimirAudioPolicy.decide(true, 0.4f, MimirAudioFocus.state.value, CallInterruptionState.Idle))
            val suspended = parkedInput.copy(focus = AudioFocusState.Suspended)
            assertEquals(0f, FrontendMusicPolicy.targetVolume(suspended), 0f)
            assertFalse(FrontendMusicPolicy.bedWantsToSound(suspended))
        } finally {
            MimirAudioFocus.onForegrounded()
        }
    }

    @Test
    fun `the Return to a parked game arms the gate and the hand-back, so the loss the game causes is the game's`() {
        try {
            MimirAudioFocus.onGameLaunchArmed()
            MimirAudioFocus.onReturnedToMimir()
            // Return pressed: the hand-back begins while Main is still resumed; the game's window then comes over it and Main stops, which arms the gate.
            val handBack = inFront(sessionLive = true, mimirInFront = true, evidence = true)
            assertTrue("a session behind a resumed Main is in front once the hand-back says so", handBack)
            assertTrue("and Mimir lets go of what it holds", MusicPausePolicy.abandonsFocusForGame(focusHeld = true, gameInFront = handBack))
            assertEquals(OtherAppHold.NONE, MusicPausePolicy.onFocus(MusicPauseState(), FocusSignal.LOSS, handBack).otherApp)
            MimirAudioFocus.onGameLaunchArmed()
            val fronted = inFront(sessionLive = true, mimirInFront = false, evidence = MimirAudioFocus.state.value is AudioFocusState.Suspended)
            assertTrue(fronted)
            assertEquals(0f, FrontendMusicPolicy.targetVolume(FrontendMusicPolicyInput(true, 0.5f, MimirAudioFocus.state.value, false, false)), 0f)
            // The game exits to Mimir: the return edge hands the audio back.
            MimirAudioFocus.onReturnedToMimir()
            assertEquals(AudioFocusState.Foreground, MimirAudioFocus.state.value)
        } finally {
            MimirAudioFocus.onForegrounded()
        }
    }

    @Test
    fun `arming the launch again at the Return keeps the kept game's discount, so it is not read as a stranger`() {
        // The park: the game's stream is budgeted at the return edge (one stream).
        val kept = keptGame()
        assertEquals(1, kept.discount(listOf("game-1")))

        // The Return arms the launch again with that stream still playing: it is the standing discount, not a stranger.
        kept.onLaunchArmed(listOf("game-1"), 20_000L)
        assertEquals("in front, nothing is discounted", 0, kept.discount(listOf("game-1")))
        assertEquals("and the stream is still the game's once Mimir is back", 1, kept.onReturned(listOf("game-1"), strangerSessionPlaying = false, nowMs = 30_000L))
        assertEquals(1, kept.discount(listOf("game-1")))

        // The Bottom app's own music (real media, not game-like) is never counted either way.
        val withBottomApp = keptGame()
        withBottomApp.onLaunchArmed(listOf("game-1", "youtube-1"), 20_000L)
        assertEquals(1, withBottomApp.onReturned(listOf("game-1", "youtube-1"), strangerSessionPlaying = true, nowMs = 30_000L))
        assertEquals(1, withBottomApp.discount(listOf("game-1", "youtube-1")))

        // A second game-like stream that appeared while the game was parked is a stranger: the budget stays one, it does not become two.
        val withStranger = keptGame()
        withStranger.onPlayback(listOf("game-1", "game-2"), 12_000L)
        withStranger.onLaunchArmed(listOf("game-1", "game-2"), 20_000L)
        assertEquals("one standing discount, one stranger", 1, withStranger.onReturned(listOf("game-1", "game-2"), strangerSessionPlaying = false, nowMs = 30_000L))
        assertEquals(1, withStranger.discount(listOf("game-1", "game-2")))
    }

    @Test
    fun `a repeated cover of Main leaves the kept game's discount unchanged`() {
        // Every Main stop with a parked game arms the gate, every resume lets go of it: the same stream counted on each cycle must not grow the budget.
        val kept = keptGame()
        var now = 10_000L
        repeat(5) { cycle ->
            now += 10_000L
            kept.onLaunchArmed(listOf("game-1"), now)
            assertEquals("cover $cycle: in front, nothing discounted", 0, kept.discount(listOf("game-1")))
            now += 10_000L
            assertEquals("cover $cycle: back, the same one stream", 1, kept.onReturned(listOf("game-1"), strangerSessionPlaying = false, nowMs = now))
            assertEquals(1, kept.discount(listOf("game-1")))
        }
        // An arm that lands while the gate is already armed (a second cover with no resume between) keeps the first count of the window.
        kept.onLaunchArmed(listOf("game-1"), now + 1_000L)
        kept.onLaunchArmed(listOf("game-1"), now + 2_000L)
        assertEquals(1, kept.onReturned(listOf("game-1"), strangerSessionPlaying = false, nowMs = now + 12_000L))

        // A second game-like stream that stood (another app's) stays a stranger across the covers: the budget is one, never two.
        val withStranger = keptGame()
        withStranger.onPlayback(listOf("game-1", "game-2"), 12_000L)
        var t = 20_000L
        repeat(4) {
            withStranger.onLaunchArmed(listOf("game-1", "game-2"), t)
            t += 10_000L
            assertEquals(1, withStranger.onReturned(listOf("game-1", "game-2"), strangerSessionPlaying = false, nowMs = t))
            assertEquals(1, withStranger.discount(listOf("game-1", "game-2")))
            t += 10_000L
        }

        // The game's stream closes while Main is covered and is back inside the grace (a route change): still one.
        val reopened = keptGame()
        reopened.onLaunchArmed(emptyList(), 20_000L)
        assertEquals(1, reopened.onReturned(listOf("game-1"), strangerSessionPlaying = false, nowMs = 20_000L + MimirKeptGameStreams.ReopenGraceMs - 1L))
        assertEquals(1, reopened.discount(listOf("game-1")))
    }

    @Test
    fun `the kept game's stream is budgeted at the park's return edge before the Bottom app has a stream`() {
        // Launch armed with nothing playing; the park's return edge sees the game's stream alone and budgets it. The Bottom app's real music arrives after.
        val kept = MimirKeptGameStreams<String>(
            gameLikeSignature = { stream -> if (stream.startsWith("game")) mimirKeptGameSignature(android.media.AudioAttributes.USAGE_MEDIA, android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN) else null },
            isRealMedia = { it.startsWith("youtube") },
        )
        kept.onLaunchArmed(emptyList(), 0L)
        assertEquals(1, kept.onReturned(listOf("game-1"), strangerSessionPlaying = false, nowMs = 5_000L))
        val present = listOf("game-1", "youtube-1")
        assertEquals("the Bottom app is another app's music, the game's stream is not", 1, kept.discount(present))
        val sample = mimirMediaBedEnvironmentSample(
            musicActive = true,
            observedPlaybackCount = present.size,
            relevantPlaybackCount = present.size,
            keptGameStreams = kept.discount(present),
            ownedPlaybackCount = 0,
            callInterrupted = false,
        )
        assertEquals("one stranger: the Bottom app", 1, sample.activePlaybackCount)
    }

    // ── The "ᛗ" limit is gone ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the switch-button park is no longer written down as a known limit`() {
        val policy = source("MusicPausePolicy.kt")
        assertFalse(policy.contains("Only a HOME park is a parked game"))
        assertFalse("the guard it blamed is gone", policy.contains("guards `onReturnedToMimir` with `!parkedMainGame`"))
        assertTrue("it says what stands in its place", policy.contains("A game parked behind Mimir by the switch button"))
        val focus = source("AudioFocusState.kt")
        assertFalse(focus.contains("guarded by `!parkedMainGame` so a game"))
        assertTrue(focus.contains("`MainActivity.onStop`") && focus.contains("on EVERY resume of Main"))
        val app = File("../app/src/main/kotlin/com/gruning/mimir/rewrite/YourMusicEverywhere.kt").readText()
        assertFalse(app.contains("a game parked behind Mimir by HOME"))
        assertTrue(app.contains("or by the switch button, is not on screen"))
    }

    private fun source(name: String): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/$name").readText()
}
