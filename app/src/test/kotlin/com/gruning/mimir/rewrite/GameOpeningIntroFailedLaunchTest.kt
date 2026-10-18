package com.gruning.mimir.rewrite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
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

/**
 * A failed launch must end its intro without hiding the failure behind it,
 * and must leave nothing set that stops the next launch's intro.
 *
 * A dispatched launch no longer ends on a blind 2.5 s grace; it waits for the game to take the screen
 * (see [GameOpeningIntroHandoffTest] for what it waits on). A launch that never dispatched still has nothing to wait for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameOpeningIntroFailedLaunchTest {
    @Before
    fun reset() {
        GameOpeningIntroState.hide()
    }

    @After
    fun tearDown() {
        GameOpeningIntroState.hide()
    }

    @Test
    fun `a failed launch does not wait for the game and a dispatched one does`() = runTest {
        var failedWaited = false
        val failed = plan("failed-game")
        GameOpeningIntroState.show(failed)
        finishGameOpeningIntro(
            this,
            launched = false,
            awaitHandoff = {
                failedWaited = true
                GameOpeningIntroHandoff.CAP
            },
            hostStopped = { false },
            restoreMenuMusic = {},
        )
        advanceTimeBy(GameOpeningIntroExitMs.toLong() + 1L)
        runCurrent()
        assertFalse("a launch that never dispatched has no game to wait for", failedWaited)
        assertNull(GameOpeningIntroState.current.value)

        var dispatchedWaited = false
        val dispatched = plan("dispatched-game")
        GameOpeningIntroState.show(dispatched)
        finishGameOpeningIntro(
            this,
            launched = true,
            awaitHandoff = {
                dispatchedWaited = true
                GameOpeningIntroHandoff.SESSION_SETTLED
            },
            hostStopped = { false },
            restoreMenuMusic = {},
        )
        runCurrent()
        assertTrue("a dispatched launch asks what the game is doing", dispatchedWaited)
        advanceTimeBy(GameOpeningIntroExitMs.toLong() + 1L)
        runCurrent()
    }

    @Test
    fun `a failed launch ends its intro within the exit fade and the next launch shows`() = runTest {
        val failed = plan("failed-game")
        GameOpeningIntroState.show(failed)
        GameOpeningIntroState.markLaunching(failed.gameIdValue)
        val cuts = GameOpeningIntroState.cutEpoch.value
        var musicRestored = 0

        finishGameOpeningIntro(
            this,
            launched = false,
            hostStopped = { false },
            restoreMenuMusic = { musicRestored += 1 },
        )
        advanceTimeBy(GameOpeningIntroExitMs.toLong() + 1L)
        runCurrent()

        assertNull(GameOpeningIntroState.current.value)
        assertFalse(GameOpeningIntroState.isLaunching())
        assertEquals("the failure fades as it always has; it is not a cut", cuts, GameOpeningIntroState.cutEpoch.value)
        assertEquals("Mimir never left, so the menu music comes back", 1, musicRestored)

        val next = plan("next-game")
        GameOpeningIntroState.show(next)
        assertEquals(next, GameOpeningIntroState.current.value)
    }

    @Test
    fun `a dispatched launch no longer ends on the old grace`() = runTest {
        val launched = plan("launched-game")
        GameOpeningIntroState.show(launched)

        // The game has not taken the screen and the ledger has said nothing. The old blind timer would have started
        // its audio fade at 2.5 s and been gone by 3.3 s.
        finishGameOpeningIntro(
            this,
            launched = true,
            awaitHandoff = { awaitCancellation() },
            hostStopped = { false },
            restoreMenuMusic = {},
        )
        advanceTimeBy(2_500L + GameOpeningIntroExitMs.toLong() + 1_000L)
        runCurrent()

        assertEquals(launched, GameOpeningIntroState.current.value)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `the failure exit clears the launching latch as hide does`() {
        val failed = plan("failed-game")
        GameOpeningIntroState.show(failed)
        GameOpeningIntroState.markLaunching(failed.gameIdValue)

        GameOpeningIntroState.hideIfCurrent(failed.gameIdValue)

        assertNull(GameOpeningIntroState.current.value)
        assertFalse(GameOpeningIntroState.isLaunching())
    }

    @Test
    fun `the same game shows again after its failed launch`() = runTest {
        val game = plan("same-game")
        GameOpeningIntroState.show(game)
        finishGameOpeningIntro(this, launched = false, hostStopped = { false }, restoreMenuMusic = {})
        advanceTimeBy(GameOpeningIntroExitMs.toLong() + 1L)
        runCurrent()
        assertNull(GameOpeningIntroState.current.value)

        GameOpeningIntroState.show(game)
        assertEquals(game, GameOpeningIntroState.current.value)
    }

    // Launch-return: Recents during the intro's hold, Mimir comes back as a new copy, and the Activity
    // that began the launch is destroyed before its dispatch finished. The dispatch still runs (NonCancellable) and then asks
    // for the intro to be finished on a scope that is already cancelled. The intro is process-wide, so it must still end.
    @Test
    fun `a dispatched launch whose owner is already cancelled still ends its intro and gives the music back`() = runTest {
        val abandoned = plan("abandoned-game")
        GameOpeningIntroState.show(abandoned)
        val owner = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        var musicRestored = 0
        var waited = false

        finishGameOpeningIntro(
            owner,
            launched = true,
            awaitHandoff = {
                waited = true
                GameOpeningIntroHandoff.GAME_HAS_SCREEN
            },
            hostStopped = { false },
            restoreMenuMusic = { musicRestored += 1 },
        )
        advanceTimeBy(GameOpeningIntroExitMs.toLong() + 1L)
        runCurrent()

        assertFalse("a dead owner does not wait for a game it can no longer follow", waited)
        assertNull("the intro must not stay over the new Mimir copy", GameOpeningIntroState.current.value)
        assertEquals("Mimir is on screen, so the menu music comes back", 1, musicRestored)
    }

    private fun plan(id: String) = GameOpeningIntroPlan(
        gameIdValue = id,
        title = "Opening Game",
        heroUri = "file:///hero.png",
        logoUri = null,
        audioUri = null,
    )
}
