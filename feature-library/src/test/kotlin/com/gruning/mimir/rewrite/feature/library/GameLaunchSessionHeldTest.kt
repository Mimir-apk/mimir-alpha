package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Outcome-dropping audit finding O-3 (2026-08-12): a launch refused because another launch already holds
 * the session mutex had no honest [GameLaunchUiState] arm to land on. [GameLaunchUiState.SessionHeld] is
 * that arm; these are the pure-decision behavioural tests on its renderers: the shared
 * [gameLaunchStatusMessage] the Library detail card and the Home launch card both delegate to, and the short status
 * label, which the companion payload carries and nothing paints today.
 *
 * O15,  The expected words are written out here rather than read from the
 * constants, so a changed constant fails instead of agreeing with itself.
 */
class GameLaunchSessionHeldTest {
    private val gameId = GameId("group:ps1:resident-evil-2")
    private val platformLabel: (PlatformId) -> String = { "PlayStation" }

    @Test
    fun `a launch refused while another is still starting keeps today's sentence`() {
        assertEquals(StartingSentence, gameLaunchSessionHeldMessage(GameLaunchSessionHold.STARTING))
        assertEquals(
            StartingSentence,
            gameLaunchStatusMessage(GameLaunchUiState.SessionHeld(GameLaunchSessionHold.STARTING), platformLabel),
        )
    }

    @Test
    fun `a launch refused while a game or app is already running says it is still open`() {
        assertEquals(RunningSentence, gameLaunchSessionHeldMessage(GameLaunchSessionHold.RUNNING))
        assertEquals(
            RunningSentence,
            gameLaunchStatusMessage(GameLaunchUiState.SessionHeld(GameLaunchSessionHold.RUNNING), platformLabel),
        )
    }

    @Test
    fun `the short status label, not painted today, splits the same way`() {
        assertEquals("Launch busy", gameLaunchSessionHeldRailLabel(GameLaunchSessionHold.STARTING))
        assertEquals("Still open", gameLaunchSessionHeldRailLabel(GameLaunchSessionHold.RUNNING))
    }

    @Test
    fun libraryDetailCardSurfacesSessionHeldInsteadOfStaticPlatformLine() {
        SentenceFor.forEach { (hold, sentence) ->
            assertEquals(
                sentence,
                resolveLibraryDetailLaunchStatusLine(
                    platformStatusLine = "DuckStation",
                    launchState = GameLaunchUiState.SessionHeld(hold),
                    detailGameId = gameId,
                    targetGameId = gameId,
                    platformLabelFor = platformLabel,
                ),
            )
        }
    }

    @Test
    fun homeLaunchCardSurfacesSessionHeldInsteadOfStaticPlatformLine() {
        SentenceFor.forEach { (hold, sentence) ->
            assertEquals(
                sentence,
                resolveHomeLaunchCardStatusLine(
                    platformStatusLine = "DuckStation",
                    launchState = GameLaunchUiState.SessionHeld(hold),
                    sessionGameId = gameId,
                    targetGameId = gameId,
                    platformLabelFor = platformLabel,
                ),
            )
        }
    }

    @Test
    fun sessionHeldIgnoredForADifferentGamesCard() {
        assertEquals(
            "DuckStation",
            resolveHomeLaunchCardStatusLine(
                platformStatusLine = "DuckStation",
                launchState = GameLaunchUiState.SessionHeld(GameLaunchSessionHold.RUNNING),
                sessionGameId = GameId("group:ps1:other"),
                targetGameId = gameId,
                platformLabelFor = platformLabel,
            ),
        )
    }

    /**
     * The differential this arm exists to fix: before O-3, a session-mutex refusal either stayed on
     * [GameLaunchUiState.Idle] (the Home launch card) or was never set at all (every other site) — either
     * way the platform's static line kept showing, exactly as if nothing had happened. [Idle] is what the
     * pre-fix code path actually produced (see `HomeShelfCoordinator.launchFromLaunchCard`'s history); this
     * proves that specific state hides the refusal, and the new arm does not.
     */
    @Test
    fun differentialPreFixIdleHidesTheSessionHeldRefusal() {
        val platformStatusLine = "DuckStation"
        val preFixStatusLine = resolveHomeLaunchCardStatusLine(
            platformStatusLine = platformStatusLine,
            launchState = GameLaunchUiState.Idle,
            sessionGameId = gameId,
            targetGameId = gameId,
            platformLabelFor = platformLabel,
        )
        val postFixStatusLine = resolveHomeLaunchCardStatusLine(
            platformStatusLine = platformStatusLine,
            launchState = GameLaunchUiState.SessionHeld(GameLaunchSessionHold.RUNNING),
            sessionGameId = gameId,
            targetGameId = gameId,
            platformLabelFor = platformLabel,
        )

        assertEquals("DuckStation", preFixStatusLine)
        assertNotEquals(preFixStatusLine, postFixStatusLine)
        assertEquals(RunningSentence, postFixStatusLine)
    }

    private companion object {
        const val StartingSentence = "Mimir is already launching something else. Wait for it to finish, then try again."
        const val RunningSentence = "A game or app is still open. Close it first, then try again."
        val SentenceFor = mapOf(
            GameLaunchSessionHold.STARTING to StartingSentence,
            GameLaunchSessionHold.RUNNING to RunningSentence,
        )
    }
}
