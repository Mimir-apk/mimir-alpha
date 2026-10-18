package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Now Playing takeover's rules (slice L3), with no composition: the once-only entry glide and what sticks after a swipe away, Quit's two taps,
 * its confirm window, what each close outcome says and when the session is settled, and the clock, total and subtitle wording.
 */
class CompanionNowPlayingStateTest {

    @Before
    fun reset() = CompanionNowPlayingState.resetForTest()

    private fun session(
        op: String = "op-1",
        sequence: Long = 1L,
        phase: MimirSessionPhase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
        dispatchedAt: Long? = 1_000L,
        pkg: String? = "org.example.emulator",
        placement: MimirGamePlacement = MimirGamePlacement.MAIN,
    ) = MimirGameSession(
        operation = MimirGameSessionOperation(op),
        sequence = sequence,
        gameId = GameId("g"),
        title = "Chrono Trigger",
        platformId = PlatformId("snes"),
        returnAnchor = MimirGameReturnAnchor.Library(
            com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget.shelf(
                com.gruning.mimir.rewrite.feature.library.LibraryShelfMode.EMULATION,
                "shelf",
            ),
        ),
        startedAtEpochMs = 1,
        dispatchedAtEpochMs = dispatchedAt,
        targetPackageName = pkg,
        placement = placement,
        dualScreen = false,
        phase = phase,
    )

    // ── entry ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `only FOREGROUND_PROVEN is the entry phase, and a Main game that yielded is out there without being proven in front`() {
        assertTrue(nowPlayingEntryPhase(MimirSessionPhase.FOREGROUND_PROVEN))
        MimirSessionPhase.entries
            .filter { it != MimirSessionPhase.FOREGROUND_PROVEN }
            .forEach { assertFalse("$it proves nothing about the game being in front", nowPlayingEntryPhase(it)) }
        // Yielding is what Quit needs (the game is out there to close), and still no entry.
        assertTrue(nowPlayingGameOutPhase(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK))
        assertTrue(nowPlayingGameOutPhase(MimirSessionPhase.FOREGROUND_PROVEN))
        assertFalse(nowPlayingGameOutPhase(MimirSessionPhase.DISPATCHED))
        assertFalse(nowPlayingGameOutPhase(MimirSessionPhase.RETURNING))
    }

    @Test
    fun `the glide happens once, when the game is proven in front, not at dispatch and not when Main yields`() {
        var state = NowPlayingTakeoverState()
        listOf(MimirSessionPhase.DISPATCHED, MimirSessionPhase.YIELDED_TO_SECONDARY_TASK).forEach { phase ->
            nowPlayingOnSession(state, session(phase = phase)).let {
                assertFalse("$phase must not glide", it.glide)
                assertNull(it.state.openKey)
                state = it.state
            }
        }
        // Proven in front by its phase (a Bottom host): the glide, once, and the takeover is open for this session.
        val entry = nowPlayingOnSession(state, session(phase = MimirSessionPhase.FOREGROUND_PROVEN))
        assertTrue(entry.glide)
        assertEquals("op-1:1", entry.state.openKey)
        assertEquals("op-1:1", entry.state.autoEnteredKey)
        state = entry.state
        // Every later report of the same session, whatever its phase, never glides again.
        listOf(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK, MimirSessionPhase.FOREGROUND_PROVEN, MimirSessionPhase.RETURNING).forEach { phase ->
            val again = nowPlayingOnSession(state, session(phase = phase))
            assertFalse("$phase must not glide again", again.glide)
            state = again.state
        }
    }

    @Test
    fun `a Main game that yielded glides on the first foreground evidence for its package, and only once`() {
        val yielded = session(phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
        var state = nowPlayingOnSession(NowPlayingTakeoverState(), yielded).state
        val seen = nowPlayingOnForeground(state, yielded, "org.example.emulator", observedAtEpochMs = 2_000L)
        assertTrue("the emulator was seen in front: now it is proven", seen.glide)
        assertEquals("op-1:1", seen.state.openKey)
        assertEquals("op-1:1", seen.state.provenKey)
        state = seen.state
        // The monitor replays and repeats; neither glides again, and neither does a later report of the same session.
        assertFalse(nowPlayingOnForeground(state, yielded, "org.example.emulator", observedAtEpochMs = 3_000L).glide)
        assertFalse(nowPlayingOnSession(state, yielded).glide)
        // A swipe to the Shelf then sticks: the next evidence for the same launch never pulls the player back.
        val away = nowPlayingClose(state)
        assertFalse(nowPlayingOnForeground(away, yielded, "org.example.emulator", observedAtEpochMs = 4_000L).glide)
    }

    @Test
    fun `evidence that is not this launch's own proves nothing`() {
        val yielded = session(phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
        val fresh = NowPlayingTakeoverState()
        assertFalse("another package", nowPlayingOnForeground(fresh, yielded, "com.other.app", 2_000L).glide)
        assertFalse("Mimir itself", nowPlayingOnForeground(fresh, yielded, "com.gruning.mimir", 2_000L).glide)
        assertFalse("seen before this launch was dispatched (the monitor replays its last event)", nowPlayingOnForeground(fresh, yielded, "org.example.emulator", 999L).glide)
        assertFalse(
            "a session with no dispatch time has nothing to be matched to",
            nowPlayingOnForeground(fresh, session(dispatchedAt = null, phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK), "org.example.emulator", 2_000L).glide,
        )
        assertFalse(
            "no package named",
            nowPlayingOnForeground(fresh, session(pkg = null, phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK), "org.example.emulator", 2_000L).glide,
        )
        assertFalse("no session", nowPlayingOnForeground(fresh, null, "org.example.emulator", 2_000L).glide)
        assertFalse("a launch still preparing is no game", nowPlayingOnForeground(fresh, session(phase = MimirSessionPhase.PREPARING), "org.example.emulator", 2_000L).glide)
        assertEquals("nothing was recorded either", fresh, nowPlayingOnForeground(fresh, yielded, "com.other.app", 2_000L).state)
        // The match is by package, ignoring case and stray whitespace, at the dispatch instant or later.
        assertTrue(nowPlayingForegroundEvidence(yielded, " ORG.EXAMPLE.Emulator ", 1_000L))
    }

    @Test
    fun `a swipe to the Shelf sticks until the next launch`() {
        val first = nowPlayingOnSession(NowPlayingTakeoverState(), session(phase = MimirSessionPhase.FOREGROUND_PROVEN))
        assertTrue(first.glide)
        // The player leaves the Activity page: the takeover closes, and the consumed key stays.
        val away = nowPlayingClose(first.state)
        assertNull(away.openKey)
        assertEquals("op-1:1", away.autoEnteredKey)
        val later = nowPlayingOnSession(away, session(phase = MimirSessionPhase.FOREGROUND_PROVEN))
        assertFalse("the same launch never pulls the player back", later.glide)
        assertNull(later.state.openKey)
        // The NEXT launch is a new session key, and arms the glide again once it is proven in front.
        val next = nowPlayingOnSession(later.state, session(op = "op-2", sequence = 2L, phase = MimirSessionPhase.FOREGROUND_PROVEN))
        assertTrue(next.glide)
        assertEquals("op-2:2", next.state.openKey)
    }

    @Test
    fun `the Now playing tile opens the takeover by hand and the Activity pill closes it without re-arming the glide`() {
        val consumed = nowPlayingOnSession(NowPlayingTakeoverState(), session(phase = MimirSessionPhase.FOREGROUND_PROVEN)).state
        val closed = nowPlayingClose(consumed)
        val reopened = nowPlayingOpen(closed, "op-1:1")
        assertEquals("op-1:1", reopened.openKey)
        val closedAgain = nowPlayingClose(reopened)
        assertNull(closedAgain.openKey)
        assertFalse(nowPlayingOnSession(closedAgain, session(phase = MimirSessionPhase.FOREGROUND_PROVEN)).glide)
        assertEquals("a null key opens nothing", closedAgain, nowPlayingOpen(closedAgain, null))
    }

    @Test
    fun `no game session gives no takeover, and a gone or different session clears a stale open one`() {
        // A RUNNING_APP is never a game session, so there is nothing to report: no takeover, no glide.
        val none = nowPlayingOnSession(NowPlayingTakeoverState(autoEnteredKey = "op-1:1", openKey = "op-1:1"), null)
        assertFalse(none.glide)
        assertNull(none.state.openKey)
        assertEquals("the consumed key survives the session's end", "op-1:1", none.state.autoEnteredKey)
        // A session that is still preparing is not a live game either.
        assertFalse(nowPlayingOnSession(NowPlayingTakeoverState(), session(phase = MimirSessionPhase.PREPARING)).glide)
        // An open takeover for another session is stale and is cleared (the new session has not been proven in front yet).
        val stale = nowPlayingOnSession(
            NowPlayingTakeoverState(autoEnteredKey = "op-1:1", openKey = "op-1:1"),
            session(op = "op-2", sequence = 2L, phase = MimirSessionPhase.DISPATCHED),
        )
        assertFalse(stale.glide)
        assertNull(stale.state.openKey)
    }

    @Test
    fun `the process state glides once per launch and a remount that reports the same session does not repeat it`() {
        val live = session(phase = MimirSessionPhase.FOREGROUND_PROVEN)
        assertTrue(CompanionNowPlayingState.onSession(live))
        assertEquals("op-1:1", CompanionNowPlayingState.state.value.openKey)
        // A remounted window reports the same session again.
        assertFalse(CompanionNowPlayingState.onSession(live))
        CompanionNowPlayingState.close()
        assertNull(CompanionNowPlayingState.state.value.openKey)
        assertFalse(CompanionNowPlayingState.onSession(live))
        CompanionNowPlayingState.open("op-1:1")
        assertEquals("op-1:1", CompanionNowPlayingState.state.value.openKey)
        assertFalse(CompanionNowPlayingState.onSession(null))
        assertTrue(CompanionNowPlayingState.onSession(session(op = "op-9", sequence = 9L, phase = MimirSessionPhase.FOREGROUND_PROVEN)))
    }

    @Test
    fun `the process state glides a yielded Main game on its first foreground evidence and a remount does not repeat it`() {
        val yielded = session(phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
        assertFalse(CompanionNowPlayingState.onSession(yielded))
        assertFalse("another package", CompanionNowPlayingState.onForeground(yielded, "com.other.app", 5_000L))
        assertTrue(CompanionNowPlayingState.onForeground(yielded, "org.example.emulator", 5_000L))
        assertEquals("op-1:1", CompanionNowPlayingState.state.value.openKey)
        assertFalse(CompanionNowPlayingState.onForeground(yielded, "org.example.emulator", 6_000L))
        assertFalse(CompanionNowPlayingState.onSession(yielded))
    }

    // ── Quit: the taps ─────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `two taps quit, the first asks to confirm`() {
        val first = nowPlayingQuitOnTap(NowPlayingQuitPhase.IDLE)
        assertEquals(NowPlayingQuitPhase.CONFIRM, first.phase)
        assertFalse("the first tap closes nothing", first.performClose)
        val second = nowPlayingQuitOnTap(first.phase)
        assertEquals(NowPlayingQuitPhase.CLOSING, second.phase)
        assertTrue(second.performClose)
        // Nothing more happens while it closes or after it closed; a failure starts over at the confirm.
        assertEquals(NowPlayingQuitStep(NowPlayingQuitPhase.CLOSING, false), nowPlayingQuitOnTap(NowPlayingQuitPhase.CLOSING))
        assertEquals(NowPlayingQuitStep(NowPlayingQuitPhase.CLOSED, false), nowPlayingQuitOnTap(NowPlayingQuitPhase.CLOSED))
        assertEquals(NowPlayingQuitStep(NowPlayingQuitPhase.CONFIRM, false), nowPlayingQuitOnTap(NowPlayingQuitPhase.FAILED))
    }

    @Test
    fun `the confirm window is the house three seconds and expiring it goes back to Quit, never to a close`() {
        assertEquals(3_000L, NowPlayingQuitConfirmMs)
        assertEquals(MusicTitleChipHoldMs, NowPlayingQuitConfirmMs)
        assertEquals(NowPlayingQuitPhase.IDLE, nowPlayingQuitOnExpiry(NowPlayingQuitPhase.CONFIRM))
        assertEquals(NowPlayingQuitPhase.IDLE, nowPlayingQuitOnExpiry(NowPlayingQuitPhase.FAILED))
        // A close in flight, and a close that finished, are not on a clock.
        assertEquals(NowPlayingQuitPhase.CLOSING, nowPlayingQuitOnExpiry(NowPlayingQuitPhase.CLOSING))
        assertEquals(NowPlayingQuitPhase.CLOSED, nowPlayingQuitOnExpiry(NowPlayingQuitPhase.CLOSED))
        assertEquals(NowPlayingQuitPhase.IDLE, nowPlayingQuitOnExpiry(NowPlayingQuitPhase.IDLE))
    }

    @Test
    fun `only a verified stop says Closed, everything else says Couldn't close it`() {
        PrivilegedCloseOutcome.entries.forEach { outcome ->
            val phase = nowPlayingQuitPhaseFor(outcome)
            if (outcome == PrivilegedCloseOutcome.STOPPED) {
                assertEquals(NowPlayingQuitPhase.CLOSED, phase)
                assertEquals("Closed", nowPlayingQuitCopy(phase))
            } else {
                assertEquals("$outcome", NowPlayingQuitPhase.FAILED, phase)
                assertEquals("Couldn't close it", nowPlayingQuitCopy(phase))
            }
        }
        assertEquals("Quit", nowPlayingQuitCopy(NowPlayingQuitPhase.IDLE))
        // A chip never wraps (issue #265): the confirm is two words on the chip, and the whole sentence for a screen reader.
        assertEquals("Tap again", nowPlayingQuitCopy(NowPlayingQuitPhase.CONFIRM))
        assertEquals("Tap again to quit", nowPlayingQuitSpoken(NowPlayingQuitPhase.CONFIRM))
    }

    @Test
    fun `Quit is offered only with a ready authority, a named package and a game that has the screen`() {
        val live = session()
        assertTrue(nowPlayingQuitOffered(live, authorityReady = true))
        assertFalse("no authority, no button", nowPlayingQuitOffered(live, authorityReady = false))
        assertFalse("nothing to close", nowPlayingQuitOffered(session(pkg = null), authorityReady = true))
        assertFalse(nowPlayingQuitOffered(session(pkg = " "), authorityReady = true))
        assertFalse("a launch in flight", nowPlayingQuitOffered(session(phase = MimirSessionPhase.DISPATCHED), authorityReady = true))
        assertFalse("already leaving", nowPlayingQuitOffered(session(phase = MimirSessionPhase.RETURNING), authorityReady = true))
        assertTrue("either placement", nowPlayingQuitOffered(session(placement = MimirGamePlacement.BOTTOM, phase = MimirSessionPhase.FOREGROUND_PROVEN), authorityReady = true))
    }

    // ── Quit: the close and what settles ───────────────────────────────────────────────────────────────────────────────────────────

    /** Every port records what was asked of it; the defaults describe a game that is not parked and whose session is live until it is ended. */
    private class Rig(
        var live: Boolean = true,
        var parked: Boolean = false,
        var closeOutcome: PrivilegedCloseOutcome = PrivilegedCloseOutcome.STOPPED,
        var task: VendorTaskOutcome = VendorTaskOutcome.NoTask,
        var removeOk: Boolean = true,
        var releaseOk: Boolean = true,
    ) {
        val calls = mutableListOf<String>()
        val ports = NowPlayingQuitPorts(
            close = { pkg -> calls += "close:$pkg"; closeOutcome },
            sessionLive = { live },
            isParked = { parked },
            resolveTask = { pkg -> calls += "resolve:$pkg"; task },
            removeTask = { id -> calls += "remove:$id"; removeOk },
            releaseParked = { _ -> calls += "release"; if (releaseOk) live = false; releaseOk },
            endSession = { _ -> calls += "end"; live = false; true },
        )
    }

    @Test
    fun `a verified stop of a game that is not parked ends the session, and every other outcome settles nothing`() = runTest {
        PrivilegedCloseOutcome.entries.forEach { outcome ->
            val rig = Rig(closeOutcome = outcome)
            val result = nowPlayingQuitPerform(session(), rig.ports)
            assertEquals(outcome, result)
            if (outcome == PrivilegedCloseOutcome.STOPPED) {
                assertEquals(listOf("close:org.example.emulator", "end"), rig.calls)
                assertFalse("the session is really over", rig.live)
            } else {
                assertEquals("$outcome is not proof, so nothing is settled", listOf("close:org.example.emulator"), rig.calls)
                assertTrue(rig.live)
            }
        }
    }

    @Test
    fun `a parked game whose task is found is evicted through removeTask, then released, then reports Closed`() = runTest {
        val rig = Rig(parked = true, task = VendorTaskOutcome.Found(taskId = 77))
        val result = nowPlayingQuitPerform(session(), rig.ports)
        assertEquals(PrivilegedCloseOutcome.STOPPED, result)
        assertEquals(listOf("close:org.example.emulator", "resolve:org.example.emulator", "remove:77", "release"), rig.calls)
        assertFalse("the lease and the session are both gone, so the next launch is not blocked", rig.live)
    }

    @Test
    fun `a parked game that cannot be evicted says it could not close and leaves the session and the lease untouched`() = runTest {
        val rig = Rig(parked = true, task = VendorTaskOutcome.Found(taskId = 77), removeOk = false)
        assertEquals(PrivilegedCloseOutcome.FAILED, nowPlayingQuitPerform(session(), rig.ports))
        assertEquals(
            "no release after a failed eviction, and the task is asked about once more: it is still there",
            listOf("close:org.example.emulator", "resolve:org.example.emulator", "remove:77", "resolve:org.example.emulator"),
            rig.calls,
        )
        assertTrue("the session is untouched", rig.live)
        assertEquals("Couldn't close it", nowPlayingQuitCopy(nowPlayingQuitPhaseFor(PrivilegedCloseOutcome.FAILED)))
    }

    @Test
    fun `#259 a verified close whose eviction finds the task gone or cannot place it is Closed, and only a task still there is not`() = runTest {
        fun ports(rig: Rig, answers: List<VendorTaskOutcome>): NowPlayingQuitPorts {
            val queue = ArrayDeque(answers)
            return NowPlayingQuitPorts(
                close = { pkg -> rig.calls += "close:$pkg"; PrivilegedCloseOutcome.STOPPED },
                sessionLive = { rig.live },
                isParked = { true },
                resolveTask = { pkg -> rig.calls += "resolve:$pkg"; queue.removeFirst() },
                // The system removed the task between the lookup and the removal, so the second step has nothing to close.
                removeTask = { id -> rig.calls += "remove:$id"; false },
                releaseParked = { rig.calls += "release"; rig.live = false; true },
                endSession = { rig.calls += "end"; rig.live = false; true },
            )
        }

        val gone = Rig()
        assertEquals(
            PrivilegedCloseOutcome.STOPPED,
            nowPlayingQuitPerform(session(), ports(gone, listOf(VendorTaskOutcome.Found(taskId = 77), VendorTaskOutcome.NoTask))),
        )
        assertEquals(
            listOf("close:org.example.emulator", "resolve:org.example.emulator", "remove:77", "resolve:org.example.emulator", "release"),
            gone.calls,
        )
        assertFalse("the lease and the session are both gone", gone.live)

        // the close is verified, so a second lookup that cannot answer does not hold the game either.
        val unsure = Rig()
        assertEquals(
            PrivilegedCloseOutcome.STOPPED,
            nowPlayingQuitPerform(session(), ports(unsure, listOf(VendorTaskOutcome.Found(taskId = 77), VendorTaskOutcome.Unavailable("timeout")))),
        )
        assertTrue("released", unsure.calls.contains("release"))
        assertFalse(unsure.live)

        // A second lookup that still names the task is a task that could not be removed: not Closed, and nothing is settled.
        val stuck = Rig()
        assertEquals(
            PrivilegedCloseOutcome.FAILED,
            nowPlayingQuitPerform(session(), ports(stuck, listOf(VendorTaskOutcome.Found(taskId = 77), VendorTaskOutcome.Found(taskId = 77)))),
        )
        assertFalse("no release", stuck.calls.contains("release"))
        assertTrue(stuck.live)
    }

    @Test
    fun `#259 a parked game whose close is verified is Closed and released even when the task lookup cannot answer`() = runTest {
        // the proven close is what counts. The unanswered probe after it no longer holds a dual-screen game.
        val rig = Rig(parked = true, task = VendorTaskOutcome.Unavailable("no readback"))
        assertEquals(PrivilegedCloseOutcome.STOPPED, nowPlayingQuitPerform(session(), rig.ports))
        assertEquals(listOf("close:org.example.emulator", "resolve:org.example.emulator", "release"), rig.calls)
        assertFalse("the lease and the session are both released", rig.live)
    }

    @Test
    fun `#259 with no verified close an unanswered probe still keeps the game, the Lime3DS rule`() = runTest {
        // Every close outcome that is not a verified stop settles nothing, whatever the probe would say: the game stays retained.
        PrivilegedCloseOutcome.entries.filter { it != PrivilegedCloseOutcome.STOPPED }.forEach { outcome ->
            val rig = Rig(parked = true, closeOutcome = outcome, task = VendorTaskOutcome.Unavailable("no readback"))
            assertEquals(outcome.name, outcome, nowPlayingQuitPerform(session(), rig.ports))
            assertEquals(outcome.name, listOf("close:org.example.emulator"), rig.calls)
            assertTrue("$outcome keeps the session", rig.live)
        }

        // The Home paths: no gateway to close with leaves a dual-screen lease held, and an unanswered probe is never proof the task is gone.
        val dual = MainGameParkingLease(
            operation = MimirGameSessionOperation("op-1"),
            gameId = GameId("xenogears"),
            targetPackageName = "org.example.emulator",
            taskId = 4170,
            parkedAtElapsedMillis = 1L,
            homeParked = true,
            dualScreen = true,
        )
        var released = 0
        val ended = endHomeParkedGame(dual, quit = { PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE }, release = { released++ })
        assertFalse(ended)
        assertEquals(0, released)
        assertFalse(
            CompanionProcessRuntime.parkedGameProvenGone(VendorTaskOutcome.Unavailable("timeout"), PrivilegedPackageLiveness.UNKNOWN),
        )
    }

    @Test
    fun `a parked game whose task is already gone needs no eviction, only the release`() = runTest {
        val rig = Rig(parked = true, task = VendorTaskOutcome.NoTask)
        assertEquals(PrivilegedCloseOutcome.STOPPED, nowPlayingQuitPerform(session(), rig.ports))
        assertEquals(listOf("close:org.example.emulator", "resolve:org.example.emulator", "release"), rig.calls)
        assertFalse(rig.live)
    }

    @Test
    fun `a release that did not end the session is a failed close, not a Closed`() = runTest {
        val rig = Rig(parked = true, task = VendorTaskOutcome.NoTask, releaseOk = false)
        assertEquals(PrivilegedCloseOutcome.FAILED, nowPlayingQuitPerform(session(), rig.ports))
        assertTrue(rig.live)
    }

    @Test
    fun `a Quit for an earlier session never closes or ends the replacement launch of the same emulator`() = runTest {
        // The session this Quit was started for is no longer the live one (a new launch replaced it): nothing is closed at all.
        val replaced = Rig(live = false)
        assertEquals(PrivilegedCloseOutcome.FAILED, nowPlayingQuitPerform(session(), replaced.ports))
        assertTrue("not even the close ran", replaced.calls.isEmpty())
        // The session was replaced WHILE the close ran: the close is reported as it came, but nothing of the replacement is ended or released.
        val rig = Rig()
        val swapping = NowPlayingQuitPorts(
            close = { pkg -> rig.calls += "close:$pkg"; rig.live = false; PrivilegedCloseOutcome.STOPPED },
            sessionLive = { rig.live },
            isParked = { true },
            resolveTask = { rig.calls += "resolve"; VendorTaskOutcome.Found(taskId = 5) },
            removeTask = { rig.calls += "remove"; true },
            releaseParked = { rig.calls += "release"; true },
            endSession = { rig.calls += "end"; true },
        )
        assertEquals("the old session is gone, which is what Quit wanted", PrivilegedCloseOutcome.STOPPED, nowPlayingQuitPerform(session(), swapping))
        assertEquals("nothing was ended, released or evicted on the replacement's behalf", listOf("close:org.example.emulator"), rig.calls)
    }

        @Test
    fun `a replacement session that appears while the task is being resolved is never evicted by the old Quit`() = runTest {
        val rig = Rig(parked = true)
        val racing = NowPlayingQuitPorts(
            close = { pkg -> rig.calls += "close:$pkg"; PrivilegedCloseOutcome.STOPPED },
            sessionLive = { rig.live },
            isParked = { true },
            // The resolve is the suspend: a new launch of the same emulator takes the session while it runs, and the task it finds is the replacement's.
            resolveTask = { rig.calls += "resolve"; rig.live = false; VendorTaskOutcome.Found(taskId = 9) },
            removeTask = { rig.calls += "remove"; true },
            releaseParked = { rig.calls += "release"; true },
            endSession = { rig.calls += "end"; true },
        )
        // The old session is gone, which is what Quit wanted: STOPPED, with nothing evicted, released or ended on the replacement's behalf.
        assertEquals(PrivilegedCloseOutcome.STOPPED, nowPlayingQuitPerform(session(), racing))
        assertEquals(listOf("close:org.example.emulator", "resolve"), rig.calls)
    }

    /**
     * Ports for a parked Quit whose session may be replaced by a same-emulator relaunch WHILE a privileged call is being handed to the IO pool.
     *
     * A read of the session from the IO pool, once the relaunch has landed in that hop, sees the replacement; a read made on the caller before the
     * hop still sees the old session. That is the window a check on the caller cannot cover, and it is the only thing that tells a check made
     * before the hop from one made after it, so the ports key on the reading thread instead of counting reads.
     */
    private class HopRace(private val replacedIn: Hop?) {
        enum class Hop { CLOSE, REMOVAL }

        val calls = mutableListOf<String>()
        var live = true
        private var closed = false
        private fun onIoPool() = Thread.currentThread().name.startsWith("DefaultDispatcher")
        val ports = NowPlayingQuitPorts(
            close = { pkg -> calls += "close:$pkg"; closed = true; PrivilegedCloseOutcome.STOPPED },
            sessionLive = {
                val replaced = onIoPool() && when (replacedIn) {
                    Hop.CLOSE -> !closed
                    Hop.REMOVAL -> closed
                    null -> false
                }
                live && !replaced
            },
            isParked = { true },
            // The relaunch reuses the task: the id found for the old session is the id the new launch holds.
            resolveTask = { calls += "resolve"; VendorTaskOutcome.Found(taskId = 77) },
            removeTask = { id -> calls += "remove:$id"; true },
            releaseParked = { calls += "release"; live = false; true },
            endSession = { calls += "end"; live = false; true },
        )
    }

    @Test
    fun `a Quit that races a same-emulator relaunch reusing the task evicts nothing, and the relaunched task survives`() = runTest {
        // The task was found for the old session. The relaunch takes the session while the removal is handed to the IO pool, and holds that task.
        val race = HopRace(replacedIn = HopRace.Hop.REMOVAL)
        val result = nowPlayingQuitPerform(session(), race.ports)
        // The old session is gone, which is what Quit wanted: STOPPED, with the task, the lease and the replacement left alone.
        assertEquals(PrivilegedCloseOutcome.STOPPED, result)
        assertEquals(listOf("close:org.example.emulator", "resolve"), race.calls)
        assertTrue("the replacement is still the live session", race.live)
    }

    @Test
    fun `a plain Quit on the same ports still removes the task, so the late check does not block a real eviction`() = runTest {
        val race = HopRace(replacedIn = null)
        assertEquals(PrivilegedCloseOutcome.STOPPED, nowPlayingQuitPerform(session(), race.ports))
        assertEquals(listOf("close:org.example.emulator", "resolve", "remove:77", "release"), race.calls)
        assertFalse("the lease and the session are both gone", race.live)
    }

    @Test
    fun `a relaunch that takes the session while the close is handed to the IO pool is never closed either`() = runTest {
        val race = HopRace(replacedIn = HopRace.Hop.CLOSE)
        // The caller's own check still sees the old session, so this exercises the check on the IO side and not the one before the hop.
        assertEquals(PrivilegedCloseOutcome.FAILED, nowPlayingQuitPerform(session(), race.ports))
        assertTrue("nothing was closed, resolved or evicted on the replacement's behalf", race.calls.isEmpty())
    }

@Test
    fun `a close that throws is a failed close and a session with no package is never closed`() = runTest {
        val thrown = Rig()
        val throwing = NowPlayingQuitPorts(
            close = { error("transport died") },
            sessionLive = { true }, isParked = { false }, resolveTask = { VendorTaskOutcome.NoTask },
            removeTask = { true }, releaseParked = { true }, endSession = { thrown.calls += "end"; true },
        )
        assertEquals(PrivilegedCloseOutcome.FAILED, nowPlayingQuitPerform(session(), throwing))
        assertTrue(thrown.calls.isEmpty())
        val none = Rig()
        assertEquals(PrivilegedCloseOutcome.FAILED, nowPlayingQuitPerform(session(pkg = null), none.ports))
        assertTrue("nothing was guessed", none.calls.isEmpty())
    }

    @Test
    fun `a settle that throws is a failed close, never a Closed that did not happen`() = runTest {
        val throwing = NowPlayingQuitPorts(
            close = { PrivilegedCloseOutcome.STOPPED },
            sessionLive = { true }, isParked = { false }, resolveTask = { VendorTaskOutcome.NoTask },
            removeTask = { true }, releaseParked = { true }, endSession = { error("cleanup failed") },
        )
        assertEquals(PrivilegedCloseOutcome.FAILED, nowPlayingQuitPerform(session(), throwing))
    }

    // ── the words ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the clock counts from dispatch and reads h mm ss`() {
        assertEquals("1:24:07", nowPlayingClockText(1_000L, 1_000L + (1L * 3600 + 24 * 60 + 7) * 1000L))
        assertEquals("0:00:00", nowPlayingClockText(5_000L, 5_000L))
        assertEquals("26:05:09", nowPlayingClockText(0L, (26L * 3600 + 5 * 60 + 9) * 1000L))
        assertNull("no dispatch time, no clock", nowPlayingClockText(null, 10_000L))
        assertNull("a clock that moved back says nothing rather than a negative time", nowPlayingClockText(10_000L, 5_000L))
    }

    @Test
    fun `the tick lands on the clock's second boundary`() {
        assertEquals(1_000L, nowPlayingClockDelayMs(0L))
        assertEquals(1L, nowPlayingClockDelayMs(999L))
        assertEquals(1_000L, nowPlayingClockDelayMs(61_000L))
        assertEquals(953L, nowPlayingClockDelayMs(5_047_047L))
        (0L..4_000L step 137L).forEach { assertTrue(nowPlayingClockDelayMs(it) in 1L..1_000L) }
        assertEquals("a negative elapsed time never gives a negative wait", 1_000L, nowPlayingClockDelayMs(-5L))
    }

    @Test
    fun `the total says before this session, from the store's own total, and never playtime`() {
        val eighteenFourty = (18L * 60 + 40) * 60_000L
        assertEquals("18 h 40 min before this session", nowPlayingTotalText(eighteenFourty))
        assertEquals("40 min before this session", nowPlayingTotalText(40L * 60_000L))
        assertEquals("2 h before this session", nowPlayingTotalText(2L * 3_600_000L))
        assertEquals("Less than a minute before this session", nowPlayingTotalText(20_000L))
        assertNull("no total, no line", nowPlayingTotalText(null))
        assertNull("a zero total is dropped, not shown", nowPlayingTotalText(0L))
        assertTrue("play" !in nowPlayingTotalText(eighteenFourty)!!.lowercase())
    }

    @Test
    fun `the subtitle is the platform then the launcher, and neither is invented`() {
        assertEquals("Genesis · opens with RetroArch", nowPlayingSubtitle("Genesis", "RetroArch"))
        assertEquals("Genesis", nowPlayingSubtitle("Genesis", null))
        assertEquals("Genesis", nowPlayingSubtitle(" Genesis ", "  "))
        assertEquals("Opens with RetroArch", nowPlayingSubtitle(null, "RetroArch"))
        assertNull(nowPlayingSubtitle(null, null))
        assertNull(nowPlayingSubtitle("", ""))
    }

    @Test
    fun `the title is the library's, then the card's, then the session's, and never blank`() {
        val row = GameRow(
            gameId = GameId("g"), title = "Library Title", platformId = PlatformId("snes"), memberCount = 1, duplicateKind = null,
        )
        assertEquals("Library Title", nowPlayingTitle(row, "Card Title", "Session Title"))
        assertEquals("Card Title", nowPlayingTitle(null, "Card Title", "Session Title"))
        assertEquals("Session Title", nowPlayingTitle(null, null, "Session Title"))
        assertEquals("Session Title", nowPlayingTitle(null, " ", "Session Title"))
        assertEquals("Game", nowPlayingTitle(null, null, ""))
    }
}
