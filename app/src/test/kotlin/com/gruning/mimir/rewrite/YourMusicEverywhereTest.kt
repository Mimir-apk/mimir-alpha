package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.audio.AudioFocusState
import com.gruning.mimir.rewrite.core.audio.CallInterruptionState
import com.gruning.mimir.rewrite.core.audio.MimirAudioFocus
import com.gruning.mimir.rewrite.core.audio.MimirAudioPolicy
import com.gruning.mimir.rewrite.core.audio.PlayDecision
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's half of "Your music plays everywhere": what counts as a game in front, and what the bridge offers as another player. */
class YourMusicEverywhereTest {
    private val emulators = setOf("org.ppsspp.ppsspp", "com.retroarch")
    private fun tracker() = MusicGameFrontTracker { it in emulators }

    @Test
    fun `a known emulator package in front is a game, an ordinary app is not`() {
        val tracker = tracker()
        tracker.onMimirForegrounded(atMs = 1_000L)
        tracker.onForeign("org.ppsspp.ppsspp", observedAtMs = 2_000L)
        assertTrue(tracker.gameInFront(sessionGameLive = false, launchArmed = false))

        // Control: the same edge for an app that is not in the registry.
        val other = tracker()
        other.onMimirForegrounded(atMs = 1_000L)
        other.onForeign("com.google.android.youtube", observedAtMs = 2_000L)
        assertFalse(other.gameInFront(sessionGameLive = false, launchArmed = false))
    }

    @Test
    fun `Mimir coming back to the front ends it, and a replayed older edge never starts it`() {
        val tracker = tracker()
        tracker.onMimirForegrounded(atMs = 1_000L)
        tracker.onForeign("org.ppsspp.ppsspp", observedAtMs = 2_000L)
        tracker.onMimirForegrounded(atMs = 3_000L)
        assertFalse(tracker.gameInFront(sessionGameLive = false, launchArmed = false))

        // The monitor's flow replays its last edge to a late collector: an edge from before Mimir was last in front is history.
        tracker.onForeign("org.ppsspp.ppsspp", observedAtMs = 2_500L)
        assertFalse(tracker.gameInFront(sessionGameLive = false, launchArmed = false))
        tracker.onForeign("org.ppsspp.ppsspp", observedAtMs = 3_500L)
        assertTrue(tracker.gameInFront(sessionGameLive = false, launchArmed = false))
    }

    @Test
    fun `a Mimir launched game counts from the armed launch and while its session is live`() {
        val tracker = tracker()
        assertTrue(tracker.gameInFront(sessionGameLive = false, launchArmed = true))
        assertTrue(tracker.gameInFront(sessionGameLive = true, launchArmed = false))
        assertFalse(tracker.gameInFront(sessionGameLive = false, launchArmed = false))
    }

    @Test
    fun `only a dispatched, proven or handed over game session is live`() {
        for (phase in MimirSessionPhase.entries) {
            val live = phase in setOf(
                MimirSessionPhase.DISPATCHED,
                MimirSessionPhase.FOREGROUND_PROVEN,
                MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
            )
            assertEquals("$phase", live, musicSessionGameLive(snapshot(phase)))
        }
        assertFalse("no session", musicSessionGameLive(MimirSessionSnapshot()))
    }

    @Test
    fun `a live game on the second screen is on screen unless HOME parked it, and a Main game is the control's to read`() {
        val bottom = snapshot(MimirSessionPhase.FOREGROUND_PROVEN, MimirGamePlacement.BOTTOM)
        assertTrue("Mimir on the first screen, the game on the second", musicBottomGameOnScreen(bottom, bottomParked = false))
        assertFalse("HOME parked it", musicBottomGameOnScreen(bottom, bottomParked = true))
        assertFalse("not live", musicBottomGameOnScreen(snapshot(MimirSessionPhase.RETURNING, MimirGamePlacement.BOTTOM), bottomParked = false))
        assertFalse("a Main game parked behind Mimir is not on screen", musicBottomGameOnScreen(snapshot(MimirSessionPhase.FOREGROUND_PROVEN, MimirGamePlacement.MAIN), bottomParked = false))
        assertFalse("no session", musicBottomGameOnScreen(MimirSessionSnapshot(), bottomParked = false))
        // The probe hands it over as EVIDENCE (the control counts evidence with Mimir in front), next to the launch and the foreground package.
        val probe = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/YourMusicEverywhere.kt").takeIf { it.isFile }
            ?: java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/YourMusicEverywhere.kt")
        val wiring = probe.readText().substringAfter("onScreen = {").substringBefore("foreground = {")
        val bottomGame = "musicBottomGameOnScreen(snapshot, bottomParked = snapshot.gameSession?.let { MainGameParkingState.currentFor(it) } != null)"
        val launch = "launchArmed = MimirAudioFocus.state.value is AudioFocusState.Suspended"
        val handBack = "MusicGameHandBack.active()"
        assertTrue(wiring.contains(bottomGame) && wiring.contains(launch) && wiring.contains(handBack))
        assertTrue("one expression, the cheap terms in turn", wiring.indexOf(bottomGame) < wiring.indexOf(handBack) && wiring.indexOf(handBack) < wiring.indexOf(launch))
        assertFalse("the costly foreground read is not among the cheap terms", wiring.contains("foregroundPackageNow"))
    }

    /** The probe's `onScreen` as the app wires it (pinned above): a Bottom game, the hand-back, or the tracker with the launch armed. */
    private fun onScreenAsWired(snapshot: MimirSessionSnapshot, bottomParked: Boolean, handBackAt: Long, focus: AudioFocusState): Boolean =
        musicBottomGameOnScreen(snapshot, bottomParked) ||
            MusicGameHandBack.active(nowMs = handBackAt) ||
            tracker().gameInFront(sessionGameLive = false, launchArmed = focus is AudioFocusState.Suspended)

    @After
    fun endHandBack() = MusicGameHandBack.end()

    @Test
    fun `a Main game parked behind the switch button is not on screen while the gate reads Foreground, and a hand-back to it is`() {
        val parked = snapshot(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK, MimirGamePlacement.MAIN)
        MusicGameHandBack.end()
        assertFalse(
            "a Main lease, the gate Foreground (Main's resume let go of the launch's Suspended), no hand-back, no emulator in front",
            onScreenAsWired(parked, bottomParked = false, handBackAt = 5_000L, focus = AudioFocusState.Foreground),
        )
        assertTrue("before the resume let go of it the launch read as a game on screen (the old behaviour)", onScreenAsWired(parked, false, 5_000L, AudioFocusState.Suspended))
        // The Return begins the hand-back before it fronts the task: the game counts as on screen for the window, whatever the gate says.
        MusicGameHandBack.begin(nowMs = 5_000L)
        assertTrue(onScreenAsWired(parked, bottomParked = false, handBackAt = 5_001L, focus = AudioFocusState.Foreground))
        assertFalse("and not once the window has run out", onScreenAsWired(parked, bottomParked = false, handBackAt = 5_000L + MusicHandBackWindowMs, focus = AudioFocusState.Foreground))
        MusicGameHandBack.end()
        assertFalse("or once Main resumes or the fronting is refused", onScreenAsWired(parked, bottomParked = false, handBackAt = 5_001L, focus = AudioFocusState.Foreground))
    }

    @Test
    fun `the Bottom's Return begins the hand-back before it fronts the task and ends it on every cancelled restoring`() {
        val controller = source("CompanionDisplayController.kt")
        val job = controller.substringAfter("val yieldedRoot = withContext(Dispatchers.Main.immediate) {").substringBefore("override fun launchRestoreGuard")
        val begin = job.indexOf("MusicGameHandBack.beginAndWake()")
        assertTrue("begun", begin > 0)
        assertEquals("once", 1, Regex("""MusicGameHandBack\.beginAndWake\(\)""").findAll(controller).count())
        assertTrue("after the Bottom has yielded and the null-root exit, before the task is fronted", job.indexOf("if (yieldedRoot == null)") < begin && begin < job.indexOf("privilegedUtility.focusTask(parkedLease.taskId)"))
        assertTrue("it says so in the log", job.contains("return to a parked game: hand-back begins"))
        // Every way the restoring mark is given back ends the hand-back first, the way HOME's refused() does.
        val cancels = Regex("""MainGameParkingState\.cancelRestoring\(parkedLease\)""").findAll(controller).map { it.range.first }.toList()
        assertEquals("the three give-backs of the Return", 3, cancels.size)
        val ends = Regex("""MusicGameHandBack\.endAndWake\(\)""").findAll(controller).map { it.range.first }.toList()
        assertEquals("one end for each", cancels.size, ends.size)
        cancels.zip(ends).forEach { (cancel, end) ->
            assertTrue("the end comes right before the give-back", end < cancel && controller.substring(end, cancel).lines().size <= 2)
        }
        // The same helpers HOME's Resume uses: one beginning, one ending.
        assertTrue(source("MainGameHomePark.kt").contains("handBackBegin = { MusicGameHandBack.beginAndWake() }"))
        assertFalse("the Return does not hand-write the two calls", controller.contains("MusicGameHandBack.begin()") || controller.contains("MusicGameHandBack.end()"))
    }

    private fun parkedSession(placement: MimirGamePlacement) = MimirGameSession(
        operation = MimirGameSessionOperation("game:recents"),
        sequence = 1L,
        gameId = GameId("zelda"),
        title = "Zelda",
        platformId = PlatformId.SNES,
        returnAnchor = MimirGameReturnAnchor.Home(HomeLaunchReturnTarget(HomeShelfItemId.Emulated(GameId("zelda")), rank = 0L)),
        startedAtEpochMs = 1L,
        targetPackageName = "io.github.lime3ds.android",
        placement = placement,
        dualScreen = placement == MimirGamePlacement.BOTTOM,
        phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    )

    @Test
    fun `Android's Recents fronting a parked game stops Main with a lease and no restoring mark, and that arms the gate`() {
        MainGameParkingState.homeParkedPublisher = {}
        MainGameParkingState.leaseObserver = {}
        MainGameParkingState.current()?.let(MainGameParkingState::clear)
        try {
            val session = parkedSession(MimirGamePlacement.MAIN)
            assertEquals(MainGameParkingArmResult.ARMED, MainGameParkingState.armForHome(session, taskId = 4170))
            val lease = requireNotNull(MainGameParkingState.current())
            assertFalse("a Main lease", lease.bottom)

            // The ᛗ coin opens Recents and the player picks the game: nothing marked the lease restoring, so the stop clears nothing, and the lease stands.
            assertFalse(MainGameParkingState.clearRestoringOnPrimaryStop(session.operation))
            assertEquals(lease, MainGameParkingState.current())
            assertTrue("that is a cover: the gate arms", mainStopArmsAudioGate(restoringCleared = false, lease = MainGameParkingState.current()))
            MimirAudioFocus.onReturnedToMimir()
            MimirAudioFocus.onGameLaunchArmed()
            assertEquals(AudioFocusState.Suspended, MimirAudioFocus.state.value)
            assertEquals(
                "so the Bottom's volume preview tick stays quiet over the game",
                PlayDecision.Suppress,
                MimirAudioPolicy.decide(true, 0.4f, MimirAudioFocus.state.value, CallInterruptionState.Idle),
            )
            // Every resume of Main lets go of it, and the tick is back.
            MimirAudioFocus.onReturnedToMimir()
            assertEquals(PlayDecision.Play(0.4f), MimirAudioPolicy.decide(true, 0.4f, MimirAudioFocus.state.value, CallInterruptionState.Idle))

            // A Return or a HOME Resume marks the lease restoring first: the stop clears it, and that arms the gate with no lease left to read.
            assertTrue(MainGameParkingState.beginRestoring(lease))
            assertTrue(MainGameParkingState.clearRestoringOnPrimaryStop(session.operation))
            assertNull(MainGameParkingState.current())
            assertTrue(mainStopArmsAudioGate(restoringCleared = true, lease = MainGameParkingState.current()))
        } finally {
            MainGameParkingState.current()?.let(MainGameParkingState::clear)
            MainGameParkingState.homeParkedPublisher = com.gruning.mimir.rewrite.feature.library.HomeLaunchCardResume::set
            MainGameParkingState.leaseObserver = {}
            MimirAudioFocus.onForegrounded()
        }
    }

    @Test
    fun `a Main stop with no parked game arms nothing, and a Bottom lease is not a Main game covering Main`() {
        assertFalse("no Main lease: the screen going off or a launcher app over Mimir, with no game parked", mainStopArmsAudioGate(restoringCleared = false, lease = null))
        val bottom = MainGameParkingLease(
            operation = MimirGameSessionOperation("game:bottom"),
            gameId = GameId("zelda"),
            targetPackageName = "io.github.lime3ds.android",
            taskId = 77,
            parkedAtElapsedMillis = 1L,
            homeParked = true,
            dualScreen = true,
            bottom = true,
            displayId = 4,
        )
        assertFalse("its game is on the second screen", mainStopArmsAudioGate(restoringCleared = false, lease = bottom))
        assertTrue(mainStopArmsAudioGate(restoringCleared = false, lease = bottom.copy(bottom = false, displayId = 0)))
        val production = source("MainActivity.kt").substringAfter("override fun onStop() {").substringBefore("override fun onNewIntent")
        assertTrue(production.contains("mainStopArmsAudioGate(frontedParkedGame, MainGameParkingState.currentForCurrentSession())"))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText() ?: error("$name not found from ${java.io.File(".").absolutePath}")
    }

    @Test
    fun `the bridge offers only an active session that is playing or paused`() {
        assertNull(otherPlayerSnapshotFor(CompanionMediaUiState.AccessRequired))
        assertNull(otherPlayerSnapshotFor(CompanionMediaUiState.NoActiveMedia))
        assertNull(otherPlayerSnapshotFor(CompanionMediaUiState.SessionUnavailable))
    }

    @Test
    fun `buffering and its kin read as playing, so a player's blip is never a stop, paused is paused and stopped is not offered`() {
        for (state in listOf(
            CompanionMediaPlaybackState.PLAYING,
            CompanionMediaPlaybackState.BUFFERING,
            CompanionMediaPlaybackState.CONNECTING,
            CompanionMediaPlaybackState.SKIPPING,
        )) {
            assertEquals("$state", true, otherPlayerPlayingFor(state))
        }
        assertEquals(false, otherPlayerPlayingFor(CompanionMediaPlaybackState.PAUSED))
        assertNull(otherPlayerPlayingFor(CompanionMediaPlaybackState.STOPPED))
        assertNull(otherPlayerPlayingFor(CompanionMediaPlaybackState.UNAVAILABLE))
    }

    @Test
    fun `only the access-required state means the grant is missing`() {
        assertFalse(sessionAccessGrantedFor(CompanionMediaUiState.AccessRequired))
        assertTrue(sessionAccessGrantedFor(CompanionMediaUiState.NoActiveMedia))
        assertTrue(sessionAccessGrantedFor(CompanionMediaUiState.SessionUnavailable))
    }

    private fun snapshot(phase: MimirSessionPhase, placement: MimirGamePlacement = MimirGamePlacement.MAIN) = MimirSessionSnapshot(
        revision = 1L,
        gameSession = MimirGameSession(
            operation = MimirGameSessionOperation("home:music"),
            sequence = 1L,
            gameId = GameId("chrono"),
            platformId = PlatformId.SNES,
            returnAnchor = MimirGameReturnAnchor.Home(
                HomeLaunchReturnTarget(HomeShelfItemId.Emulated(GameId("chrono")), rank = 0L),
            ),
            startedAtEpochMs = 1_000L,
            placement = placement,
            dualScreen = false,
            phase = phase,
        ),
    )
}
