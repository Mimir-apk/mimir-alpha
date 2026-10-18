package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.GameLaunchSessionHold
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.LibraryGameLaunchOperation
import com.gruning.mimir.rewrite.feature.library.LibraryGameSessionEvent
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.gameLaunchSessionHeldMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O15 follow-up (coordinator review, 2026-09-12): the Main quick-launch route's guard on the Library's own launch
 * ([libraryLaunchGuardHold]).
 *
 * The guard used to refuse with "Still opening the previous app" whenever the Library's launch state was Launching or
 * IntentSent. IntentSent lasts for the whole run of a game the Library launched, so a Main pin tapped on the Bottom while
 * that game ran was told the game was still opening. It can also outlive the game: a return that skips the Library's
 * restore leaves it behind with no game session in the ledger (the stale case, below), and the guard then refused every
 * Main quick launch with nothing in the way. The ledger now says what the state means.
 */
class LibraryLaunchGuardTest {

    @Test
    fun `the guard stands aside unless the Library's own launch is in flight`() {
        val running = sessionsWith(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
        listOf(
            GameLaunchUiState.Idle,
            GameLaunchUiState.Unwired,
            GameLaunchUiState.MissingFile,
            GameLaunchUiState.SessionHeld(GameLaunchSessionHold.RUNNING),
        ).forEach { state ->
            assertNull("$state is no Library launch in flight", libraryLaunchGuardHold(state, running))
        }
    }

    @Test
    fun `a Library launch in flight is answered by what the ledger's game session is now`() {
        val expected = mapOf(
            MimirSessionPhase.PREPARING to GameLaunchSessionHold.STARTING,
            MimirSessionPhase.DISPATCHED to GameLaunchSessionHold.STARTING,
            MimirSessionPhase.RECOVERING to GameLaunchSessionHold.STARTING,
            MimirSessionPhase.FOREGROUND_PROVEN to GameLaunchSessionHold.RUNNING,
            MimirSessionPhase.YIELDED_TO_SECONDARY_TASK to GameLaunchSessionHold.RUNNING,
            MimirSessionPhase.RETURNING to GameLaunchSessionHold.RUNNING,
            MimirSessionPhase.ENDED to null,
            MimirSessionPhase.FAILED to null,
            MimirSessionPhase.STALE to null,
        )
        assertEquals("every phase is placed", MimirSessionPhase.entries.toSet(), expected.keys)
        listOf(GameLaunchUiState.Launching, GameLaunchUiState.IntentSent).forEach { state ->
            expected.forEach { (phase, hold) ->
                assertEquals("$state with the game $phase", hold, libraryLaunchGuardHold(state, sessionsWith(phase)))
            }
            assertNull("$state with no game session at all", libraryLaunchGuardHold(state, MimirSessionSnapshot()))
        }
    }

    /** Thor signature (a): Zelda launched from the Library onto Main, then a Main pin tapped on the Bottom. */
    @Test
    fun `a Library game running on Main is answered with the running sentence, not still opening`() {
        val coordinator = MimirSessionCoordinator()
        val zelda = preparing(1L, game("zelda"))
        assertTrue(coordinator.gamePreparing(zelda))
        assertTrue(coordinator.gameSettled(settled(zelda)))
        assertTrue(
            "premise: Main stopped under the emulator",
            coordinator.gameYielded(libraryGameSessionOperation(zelda.operation)),
        )

        val hold = libraryLaunchGuardHold(GameLaunchUiState.IntentSent, coordinator.state.value)

        assertEquals(GameLaunchSessionHold.RUNNING, hold)
        assertEquals(
            "A game or app is still open. Close it first, then try again.",
            gameLaunchSessionHeldMessage(checkNotNull(hold)),
        )
    }

    @Test
    fun `a Library game still starting keeps the guard's own words`() {
        val coordinator = MimirSessionCoordinator()
        val zelda = preparing(1L, game("zelda"))
        assertTrue(coordinator.gamePreparing(zelda))
        assertEquals(
            "being made",
            GameLaunchSessionHold.STARTING,
            libraryLaunchGuardHold(GameLaunchUiState.Launching, coordinator.state.value),
        )
        assertTrue(coordinator.gameSettled(settled(zelda)))
        assertEquals(
            "sent, and Main not handed over yet",
            GameLaunchSessionHold.STARTING,
            libraryLaunchGuardHold(GameLaunchUiState.IntentSent, coordinator.state.value),
        )
    }

    /**
     * The stale case. The M button's Home (`MimirNavigationCoordinator.applyMButtonHomeNavigation`) and its Quick launch in
     * place clear the Library's return target and leave its launch state; Main's return then ends the session, and the
     * Library's restore has no target to run from. The state stays IntentSent with no game session, and the guard used to
     * refuse this launch with "Still opening the previous app".
     */
    @Test
    fun `a launch state left behind by a return that skipped the Library's restore leaves the Main launch to admission`() {
        val coordinator = MimirSessionCoordinator()
        val zelda = preparing(1L, game("zelda"))
        val operation = libraryGameSessionOperation(zelda.operation)
        assertTrue(coordinator.gamePreparing(zelda))
        assertTrue(coordinator.gameSettled(settled(zelda)))
        assertTrue(coordinator.gameYielded(operation))
        assertNotNull(coordinator.beginGameReturn(operation))
        assertTrue(coordinator.completeGameReturn(operation, foregroundProven = true))
        assertFalse(
            "premise: with the return target cleared, the Library's restore has nothing to run from",
            libraryLaunchReturnHasViableAnchor(armed = false, target = null),
        )

        assertNull(libraryLaunchGuardHold(GameLaunchUiState.IntentSent, coordinator.state.value))
        assertNotNull(
            "and admission, the one owner of contention, lets the app start on Main",
            coordinator.preparePrimaryApp(
                CompanionRunningApp("com.apple.android.music", "Apple Music"),
                PrimaryReturnOrigin("HOME"),
            ),
        )
    }

    private fun sessionsWith(phase: MimirSessionPhase): MimirSessionSnapshot {
        val coordinator = MimirSessionCoordinator()
        check(coordinator.gamePreparing(preparing(1L, game("zelda"))))
        val snapshot = coordinator.state.value
        return snapshot.copy(gameSession = checkNotNull(snapshot.gameSession).copy(phase = phase))
    }

    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = PlatformId.SNES,
        memberCount = 1,
        duplicateKind = null,
    )

    private fun preparing(operation: Long, game: GameRow) = LibraryGameSessionEvent.Preparing(
        operation = LibraryGameLaunchOperation(operation),
        game = game,
        returnTarget = LibraryLaunchReturnTarget.gamelistRom(
            openPlatformId = game.platformId,
            romPlatformId = game.platformId,
            gameId = game.gameId,
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.GAMES,
            searchQuery = "",
            favoritesOnly = false,
        ),
        startedAtEpochMs = 1_000L + operation,
    )

    private fun settled(preparing: LibraryGameSessionEvent.Preparing) = LibraryGameSessionEvent.Settled(
        operation = preparing.operation,
        game = preparing.game,
        returnTarget = preparing.returnTarget,
        result = LaunchResult(
            readiness = LaunchReadiness.Ready(
                LaunchTarget(
                    gameId = preparing.game.gameId,
                    platformId = preparing.game.platformId,
                    emulator = EmulatorProfile(
                        id = EmulatorId("retroarch-snes"),
                        packageName = "com.retroarch",
                        displayName = "RetroArch",
                        installed = true,
                        capability = EmulatorCapability.DIRECT_PAYLOAD,
                        locatorForm = LaunchLocatorForm.CONTENT_URI,
                        supportedPlatforms = setOf(preparing.game.platformId),
                    ),
                    locator = "content://mimir/${preparing.game.gameId.value}",
                    locatorForm = LaunchLocatorForm.CONTENT_URI,
                    title = preparing.game.title,
                ),
            ),
            outcome = LaunchOutcome.IntentSent,
        ),
    )
}
