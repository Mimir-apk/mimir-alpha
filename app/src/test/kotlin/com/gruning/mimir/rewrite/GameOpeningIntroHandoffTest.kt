package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A dispatched
 * launch's intro used to end on a blind 2.5 s timer. It now waits for the FIRST of: (a) the game has the screen,
 * (b) the dispatch watchdog's verdict, (c) a cap; and only (a) holds 0.2 s more and then removes the intro with no exit
 * animation, because Mimir is stopped by then and a fade begun would run when it resumes.
 *
 * Robolectric only so the `MimirOpening` log lines, which the device walk greps, can be asserted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class GameOpeningIntroHandoffTest {
    private val gameId = "handoff-game"

    @Before
    fun setUp() {
        ShadowLog.clear()
        GameOpeningIntroState.hide()
    }

    @After
    fun tearDown() {
        GameOpeningIntroState.hide()
    }

    // ---- what each ledger phase means to a launch that is waiting

    @Test
    fun `a stopped Mimir means the game has the screen whatever the ledger says`() {
        for (phase in MimirSessionPhase.values()) {
            assertEquals(
                "phase $phase",
                GameOpeningIntroHandoff.GAME_HAS_SCREEN,
                gameOpeningIntroHandoffOf(snapshot(phase), hostStopped = true, gameIdValue = gameId),
            )
        }
        assertEquals(
            GameOpeningIntroHandoff.GAME_HAS_SCREEN,
            gameOpeningIntroHandoffOf(MimirSessionSnapshot(), hostStopped = true, gameIdValue = gameId),
        )
    }

    @Test
    fun `a launch still being prepared or dispatched keeps waiting`() {
        assertNull(gameOpeningIntroHandoffOf(snapshot(MimirSessionPhase.PREPARING), false, gameId))
        assertNull(gameOpeningIntroHandoffOf(snapshot(MimirSessionPhase.DISPATCHED), false, gameId))
    }

    @Test
    fun `the yield edge and a proven foreground both mean the game has the screen`() {
        assertEquals(
            GameOpeningIntroHandoff.GAME_HAS_SCREEN,
            gameOpeningIntroHandoffOf(snapshot(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK), false, gameId),
        )
        assertEquals(
            GameOpeningIntroHandoff.GAME_HAS_SCREEN,
            gameOpeningIntroHandoffOf(snapshot(MimirSessionPhase.FOREGROUND_PROVEN), false, gameId),
        )
    }

    @Test
    fun `the watchdog claiming RECOVERING is the verdict, and so is every phase after a return or an end`() {
        for (phase in listOf(
            MimirSessionPhase.RECOVERING,
            MimirSessionPhase.FAILED,
            MimirSessionPhase.RETURNING,
            MimirSessionPhase.ENDED,
            MimirSessionPhase.STALE,
        )) {
            assertEquals(
                "phase $phase",
                GameOpeningIntroHandoff.SESSION_SETTLED,
                gameOpeningIntroHandoffOf(snapshot(phase), false, gameId),
            )
        }
    }

    @Test
    fun `a session for another game, or none, has nothing left for this launch to wait on`() {
        assertEquals(
            GameOpeningIntroHandoff.SESSION_SETTLED,
            gameOpeningIntroHandoffOf(snapshot(MimirSessionPhase.DISPATCHED, id = "another-game"), false, gameId),
        )
        assertEquals(
            GameOpeningIntroHandoff.SESSION_SETTLED,
            gameOpeningIntroHandoffOf(MimirSessionSnapshot(), false, gameId),
        )
    }

    // ---- the wait itself

    @Test
    fun `the yield edge ends the wait the moment it lands`() = runTest {
        val sessions = MutableStateFlow(snapshot(MimirSessionPhase.DISPATCHED))
        val stopped = MutableStateFlow(false)
        val result = async { awaitGameOpeningIntroHandoff(gameId, sessions, stopped) }

        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(result.isCompleted)

        sessions.value = snapshot(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
        runCurrent()

        assertEquals(GameOpeningIntroHandoff.GAME_HAS_SCREEN, result.await())
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun `a stop the ledger missed still ends the wait`() = runTest {
        // onStop ran while the phase was still PREPARING, so gameYielded did nothing and the session then read
        // DISPATCHED with Mimir already gone. The watchdog cannot expire (Mimir does not own the window).
        val sessions = MutableStateFlow(snapshot(MimirSessionPhase.DISPATCHED))
        val stopped = MutableStateFlow(false)
        val result = async { awaitGameOpeningIntroHandoff(gameId, sessions, stopped) }

        advanceTimeBy(400L)
        runCurrent()
        assertFalse(result.isCompleted)

        stopped.value = true
        runCurrent()

        assertEquals(GameOpeningIntroHandoff.GAME_HAS_SCREEN, result.await())
        assertEquals(400L, testScheduler.currentTime)
    }

    @Test
    fun `the watchdog's verdict ends the wait at the verdict`() = runTest {
        val sessions = MutableStateFlow(snapshot(MimirSessionPhase.DISPATCHED))
        val stopped = MutableStateFlow(false)
        val result = async { awaitGameOpeningIntroHandoff(gameId, sessions, stopped) }

        advanceTimeBy(GameDispatchPrimaryGraceMs)
        runCurrent()
        sessions.value = snapshot(MimirSessionPhase.RECOVERING)
        runCurrent()

        assertEquals(GameOpeningIntroHandoff.SESSION_SETTLED, result.await())
        assertEquals(GameDispatchPrimaryGraceMs, testScheduler.currentTime)
    }

    @Test
    fun `a game that has already yielded when the wait begins ends it at once`() = runTest {
        val result = async {
            awaitGameOpeningIntroHandoff(
                gameId,
                MutableStateFlow(snapshot(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)),
                MutableStateFlow(false),
            )
        }
        runCurrent()

        assertEquals(GameOpeningIntroHandoff.GAME_HAS_SCREEN, result.await())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `with nothing to read the wait ends at the cap and not a moment before`() = runTest {
        val result = async {
            awaitGameOpeningIntroHandoff(
                gameId,
                MutableStateFlow(snapshot(MimirSessionPhase.DISPATCHED)),
                MutableStateFlow(false),
            )
        }

        advanceTimeBy(GameOpeningIntroHandoffCapMs - 1L)
        runCurrent()
        assertFalse(result.isCompleted)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(GameOpeningIntroHandoff.CAP, result.await())
    }

    // ---- finishGameOpeningIntro on each ending

    @Test
    fun `a game that yields at one second takes the intro away by one point two with no exit animation`() = runTest {
        val rig = Rig(this)
        val plan = rig.showIntro()

        rig.finish()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(plan, GameOpeningIntroState.current.value)

        rig.gameTakesTheScreen()
        advanceTimeBy(GameOpeningIntroYieldHoldMs - 1L)
        runCurrent()
        assertEquals("still holding just short of 0.2 s", plan, GameOpeningIntroState.current.value)

        advanceTimeBy(1L)
        runCurrent()
        assertNull("gone by 1.2 s", GameOpeningIntroState.current.value)
        assertEquals(1_000L + GameOpeningIntroYieldHoldMs, testScheduler.currentTime)
        assertEquals("removed with no exit animation", rig.cutsBefore + 1, GameOpeningIntroState.cutEpoch.value)
        assertEquals("the game's own foreground edge brings the music back, not this", 0, rig.musicRestores)
        assertTrue(logs().toString(), logs().contains("handoff game=$gameId via=yield"))
        assertTrue(logs().toString(), logs().contains("end game=$gameId launched=true via=yield"))
    }

    @Test
    fun `a stop the ledger missed is held 0_2 s and cut the same way`() = runTest {
        val rig = Rig(this)
        val plan = rig.showIntro()

        rig.finish()
        advanceTimeBy(500L)
        runCurrent()
        rig.stopped.value = true // onStop, with the session still DISPATCHED
        advanceTimeBy(GameOpeningIntroYieldHoldMs - 1L)
        runCurrent()
        assertEquals(plan, GameOpeningIntroState.current.value)

        advanceTimeBy(1L)
        runCurrent()
        assertNull(GameOpeningIntroState.current.value)
        assertEquals(rig.cutsBefore + 1, GameOpeningIntroState.cutEpoch.value)
    }

    @Test
    fun `with no yield and no verdict the intro fades at the cap as it always has`() = runTest {
        val rig = Rig(this)
        val plan = rig.showIntro()

        rig.finish()
        advanceTimeBy(GameOpeningIntroHandoffCapMs - 1L)
        runCurrent()
        assertEquals("nothing moves before the cap", plan, GameOpeningIntroState.current.value)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals("the audio fades first, the picture intact", plan, GameOpeningIntroState.current.value)

        advanceTimeBy(GameOpeningIntroExitMs.toLong() - 1L)
        runCurrent()
        assertEquals(plan, GameOpeningIntroState.current.value)

        advanceTimeBy(1L)
        runCurrent()
        assertNull(GameOpeningIntroState.current.value)
        assertEquals("the cap fades, it does not cut", rig.cutsBefore, GameOpeningIntroState.cutEpoch.value)
        assertEquals("Mimir is on screen, so the menu music the intro switched off comes back", 1, rig.musicRestores)
        assertTrue(logs().toString(), logs().contains("handoff game=$gameId via=cap"))
        assertTrue(logs().toString(), logs().contains("end game=$gameId launched=true via=cap"))
    }

    @Test
    fun `the watchdog's verdict starts the fade at the verdict and not at the cap`() = runTest {
        val rig = Rig(this)
        rig.showIntro()

        rig.finish()
        advanceTimeBy(GameDispatchPrimaryGraceMs)
        runCurrent()
        rig.sessions.value = snapshot(MimirSessionPhase.RECOVERING)
        runCurrent()
        advanceTimeBy(GameOpeningIntroExitMs.toLong())
        runCurrent()

        assertNull(GameOpeningIntroState.current.value)
        assertEquals(GameDispatchPrimaryGraceMs + GameOpeningIntroExitMs, testScheduler.currentTime)
        assertTrue("well before the cap", testScheduler.currentTime < GameOpeningIntroHandoffCapMs)
        assertEquals("a fade, not a cut", rig.cutsBefore, GameOpeningIntroState.cutEpoch.value)
        assertEquals("the verdict leaves Mimir showing, so the menu music returns", 1, rig.musicRestores)
        assertTrue(logs().toString(), logs().contains("end game=$gameId launched=true via=verdict"))
    }

    @Test
    fun `a fade whose host stops during its audio fade is cut and does not run on the way back`() = runTest {
        val rig = Rig(this)
        rig.showIntro()

        rig.finish()
        advanceTimeBy(GameOpeningIntroHandoffCapMs)
        runCurrent()
        // A slow game finally covers Mimir while the audio fades. The ledger is not read again by then; the host is.
        rig.stopped.value = true
        advanceTimeBy(GameOpeningIntroExitMs.toLong())
        runCurrent()

        assertNull(GameOpeningIntroState.current.value)
        assertEquals("cut, because a fade would run on resume", rig.cutsBefore + 1, GameOpeningIntroState.cutEpoch.value)
        assertEquals("and no menu music under a game that has the screen", 0, rig.musicRestores)
    }

    @Test
    fun `a failed launch is unchanged and never waits`() = runTest {
        val rig = Rig(this)
        rig.showIntro()
        GameOpeningIntroState.markLaunching(gameId)

        rig.finish(launched = false)
        advanceTimeBy(GameOpeningIntroExitMs.toLong())
        runCurrent()

        assertNull(GameOpeningIntroState.current.value)
        assertFalse(GameOpeningIntroState.isLaunching())
        assertEquals(GameOpeningIntroExitMs.toLong(), testScheduler.currentTime)
        assertEquals(rig.cutsBefore, GameOpeningIntroState.cutEpoch.value)
        assertEquals(1, rig.musicRestores)
        assertTrue(logs().toString(), logs().contains("end game=$gameId launched=false"))
        assertTrue("no handoff line for a launch that never dispatched", logs().none { it.startsWith("handoff ") })
    }

    @Test
    fun `an intro replaced by a newer one is left alone by the launch that started it`() = runTest {
        val rig = Rig(this)
        rig.showIntro()
        rig.finish()
        val newer = plan("newer-game")
        GameOpeningIntroState.show(newer)

        rig.gameTakesTheScreen()
        advanceTimeBy(GameOpeningIntroYieldHoldMs + GameOpeningIntroExitMs)
        runCurrent()

        assertEquals(newer, GameOpeningIntroState.current.value)
        assertEquals(rig.cutsBefore, GameOpeningIntroState.cutEpoch.value)
    }

    @Test
    fun `a wait cancelled with its scope still takes the intro away, and gives the music back if Mimir is showing`() = runTest {
        val rig = Rig(this)
        rig.showIntro()
        rig.finish()
        runCurrent()

        coroutineContext.cancelChildren()
        runCurrent()

        assertNull(GameOpeningIntroState.current.value)
        assertEquals("Mimir is on screen, so it fades", rig.cutsBefore, GameOpeningIntroState.cutEpoch.value)
        assertEquals(1, rig.musicRestores)
    }

    @Test
    fun `a wait cancelled while Mimir is stopped cuts the intro instead of leaving a fade for the return`() = runTest {
        val rig = Rig(this)
        rig.showIntro()
        rig.finish()
        runCurrent()
        rig.stopped.value = true // onStop; the ledger's edge was missed and the finish is about to be cancelled

        coroutineContext.cancelChildren()
        runCurrent()

        assertNull(GameOpeningIntroState.current.value)
        assertEquals(rig.cutsBefore + 1, GameOpeningIntroState.cutEpoch.value)
        assertEquals(0, rig.musicRestores)
    }

    // ---- a return to Mimir never finds the intro

    @Test
    fun `an intro whose launch was dispatched is removed, without an exit, when Mimir starts again`() {
        val plan = plan(gameId)
        GameOpeningIntroState.show(plan)
        GameOpeningIntroState.markDispatched(gameId)
        val cuts = GameOpeningIntroState.cutEpoch.value

        assertTrue(GameOpeningIntroState.hideDispatchedForReturn())

        assertNull(GameOpeningIntroState.current.value)
        assertEquals(cuts + 1, GameOpeningIntroState.cutEpoch.value)
        assertFalse("nothing left to remove the second time", GameOpeningIntroState.hideDispatchedForReturn())
    }

    @Test
    fun `an intro still waiting on its launch is left alone when Mimir starts`() {
        val plan = plan(gameId)
        GameOpeningIntroState.show(plan)
        val cuts = GameOpeningIntroState.cutEpoch.value

        assertFalse(GameOpeningIntroState.hideDispatchedForReturn())

        assertEquals(plan, GameOpeningIntroState.current.value)
        assertEquals(cuts, GameOpeningIntroState.cutEpoch.value)
    }

    @Test
    fun `finishing a dispatched launch marks its intro so the return removes it even if the wait never ends`() = runTest {
        val plan = plan(gameId)
        GameOpeningIntroState.show(plan)
        finishGameOpeningIntro(
            this,
            launched = true,
            awaitHandoff = { awaitCancellation() },
            hostStopped = { false },
            restoreMenuMusic = {},
        )
        runCurrent()
        assertEquals(plan, GameOpeningIntroState.current.value)

        assertTrue(GameOpeningIntroState.hideDispatchedForReturn())
        assertNull(GameOpeningIntroState.current.value)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a new intro after a cut starts unmarked and shows normally`() {
        GameOpeningIntroState.show(plan(gameId))
        GameOpeningIntroState.markDispatched(gameId)
        GameOpeningIntroState.hideIfCurrentWithoutExit(gameId)

        val next = plan("next-game")
        GameOpeningIntroState.show(next)

        assertEquals(next, GameOpeningIntroState.current.value)
        assertFalse("a fresh intro has not been dispatched", GameOpeningIntroState.hideDispatchedForReturn())
        assertEquals(next, GameOpeningIntroState.current.value)
    }

    // ---- fixtures

    /** The launch of [gameId] as the four callers hand it over: Mimir on screen, the game DISPATCHED. */
    private inner class Rig(private val scope: TestScope) {
        val sessions = MutableStateFlow(snapshot(MimirSessionPhase.DISPATCHED))
        val stopped = MutableStateFlow(false)
        var musicRestores = 0
        var cutsBefore = GameOpeningIntroState.cutEpoch.value

        fun showIntro(): GameOpeningIntroPlan = plan(gameId).also {
            GameOpeningIntroState.show(it)
            cutsBefore = GameOpeningIntroState.cutEpoch.value
        }

        fun finish(launched: Boolean = true) = finishGameOpeningIntro(
            scope,
            launched = launched,
            awaitHandoff = { id -> awaitGameOpeningIntroHandoff(id, sessions, stopped) },
            hostStopped = { stopped.value },
            restoreMenuMusic = { musicRestores += 1 },
        )

        /** What MainActivity.onStop does: the ledger's yield edge, then the stopped flag. */
        fun gameTakesTheScreen() {
            sessions.value = snapshot(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
            stopped.value = true
        }
    }

    private fun logs(): List<String> = ShadowLog.getLogsForTag("MimirOpening").map { it.msg }

    private fun plan(id: String) = GameOpeningIntroPlan(
        gameIdValue = id,
        title = "Opening Game",
        heroUri = "file:///hero.png",
        logoUri = null,
        audioUri = null,
    )

    private fun snapshot(phase: MimirSessionPhase, id: String = gameId) = MimirSessionSnapshot(
        gameSession = MimirGameSession(
            operation = MimirGameSessionOperation("op-$id"),
            sequence = 1L,
            gameId = GameId(id),
            platformId = PlatformId.SNES,
            returnAnchor = MimirGameReturnAnchor.Home(
                HomeLaunchReturnTarget(itemId = HomeShelfItemId.Emulated(GameId(id)), rank = 1L),
            ),
            startedAtEpochMs = 1L,
            dualScreen = false,
            phase = phase,
        ),
    )
}
