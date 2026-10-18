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
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.GameLaunchSessionHold
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryGameLaunchOperation
import com.gruning.mimir.rewrite.feature.library.LibraryGameSessionEvent
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.gameLaunchSessionHeldMessage
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O15,
 *
 * The phase classifier ([launchHoldFor]) and what an admission reports about a refusal, driven through the real
 * [MimirSessionCoordinator]. The expected sentences are written out, so a changed ruled sentence fails here too.
 */
class MimirSessionLaunchHoldTest {

    @Test
    fun `every phase is placed on the specified split`() {
        // phase to (its hold when Mimir still settles a dispatch, its hold when nothing in Mimir moves a dispatch on)
        val expected: Map<MimirSessionPhase, Pair<GameLaunchSessionHold?, GameLaunchSessionHold?>> = mapOf(
            MimirSessionPhase.PREPARING to (Starting to Starting),
            MimirSessionPhase.DISPATCHED to (Starting to Running),
            MimirSessionPhase.RECOVERING to (Starting to Starting),
            MimirSessionPhase.FOREGROUND_PROVEN to (Running to Running),
            MimirSessionPhase.YIELDED_TO_SECONDARY_TASK to (Running to Running),
            MimirSessionPhase.RETURNING to (Running to Running),
            MimirSessionPhase.ENDED to (null to null),
            MimirSessionPhase.FAILED to (null to null),
            MimirSessionPhase.STALE to (null to null),
        )
        assertEquals("every phase is placed", MimirSessionPhase.entries.toSet(), expected.keys)
        expected.forEach { (phase, holds) ->
            assertEquals("$phase, a dispatch Mimir still settles", holds.first, launchHoldFor(phase, dispatchSettlesItself = true))
            assertEquals("$phase, a dispatch nothing moves on", holds.second, launchHoldFor(phase, dispatchSettlesItself = false))
        }
    }

    @Test
    fun `a session holds the launch mutex in exactly the phases that have a hold`() {
        listOf(true, false).forEach { settles ->
            assertEquals(
                "dispatchSettlesItself=$settles",
                ActivePresentationPhases,
                MimirSessionPhase.entries.filter { launchHoldFor(it, settles) != null }.toSet(),
            )
        }
    }

    @Test
    fun `a game running on Main refuses a Main app with the running sentence`() {
        // The Thor, 2026-09-12: Zelda on Main, and a dock pin for a Main app tapped on the Bottom.
        val coordinator = MimirSessionCoordinator()
        val zelda = preparing(1L, game("zelda"))
        assertTrue(coordinator.gamePreparing(zelda))
        assertTrue(coordinator.gameSettled(settled(zelda)))
        assertTrue(
            "premise: Mimir handed Main to the game",
            coordinator.gameYielded(libraryGameSessionOperation(zelda.operation)),
        )

        val heard = heardRefusingAMainApp(coordinator)

        assertEquals(listOf(Running), heard)
        assertEquals(RunningSentence, gameLaunchSessionHeldMessage(heard.single()))
    }

    @Test
    fun `a game still preparing refuses with today's sentence`() {
        val coordinator = MimirSessionCoordinator()
        assertTrue(coordinator.gamePreparing(preparing(1L, game("starting"))))

        val heard = heardRefusingAMainApp(coordinator)

        assertEquals(listOf(Starting), heard)
        assertEquals(StartingSentence, gameLaunchSessionHeldMessage(heard.single()))
    }

    @Test
    fun `a game dispatched but not yet handed Main is still starting`() {
        val coordinator = MimirSessionCoordinator()
        val game = preparing(1L, game("hand-off"))
        assertTrue(coordinator.gamePreparing(game))
        assertTrue(coordinator.gameSettled(settled(game)))
        assertEquals("premise", MimirSessionPhase.DISPATCHED, coordinator.state.value.gameSession?.phase)

        assertEquals(listOf(Starting), heardRefusingAMainApp(coordinator))
    }

    @Test
    fun `a game the dispatch watchdog is cleaning up is still starting`() {
        val coordinator = MimirSessionCoordinator()
        val game = preparing(1L, game("never-came-up"))
        assertTrue(coordinator.gamePreparing(game))
        assertTrue(coordinator.gameSettled(settled(game)))
        assertNotNull(coordinator.gameDispatchWatchdogExpired(libraryGameSessionOperation(game.operation), "com.retroarch"))
        assertEquals("premise", MimirSessionPhase.RECOVERING, coordinator.state.value.gameSession?.phase)

        assertEquals(listOf(Starting), heardRefusingAMainApp(coordinator))
    }

    @Test
    fun `a game on its way back to Mimir is still open`() {
        val coordinator = MimirSessionCoordinator()
        val game = preparing(1L, game("returning"))
        val operation = libraryGameSessionOperation(game.operation)
        assertTrue(coordinator.gamePreparing(game))
        assertTrue(coordinator.gameSettled(settled(game)))
        assertTrue(coordinator.gameYielded(operation))
        assertNotNull(coordinator.beginGameReturn(operation))
        assertEquals("premise", MimirSessionPhase.RETURNING, coordinator.state.value.gameSession?.phase)

        assertEquals(listOf(Running), heardRefusingAMainApp(coordinator))
    }

    @Test
    fun `an app Mimir started on Main is running once its start was accepted`() {
        val coordinator = MimirSessionCoordinator()
        val chrome = requireNotNull(coordinator.preparePrimaryApp(Chrome, Origin, startedAtEpochMs = 1_000L))
        assertEquals("while its start is still being made", listOf(Starting), heardRefusingAMainApp(coordinator))

        assertTrue(coordinator.appDispatched(chrome))

        assertEquals(
            "nothing moves a dispatched Main app on until the player comes back",
            listOf(Running),
            heardRefusingAMainApp(coordinator),
        )
        val heard = mutableListOf<GameLaunchSessionHold>()
        val game = game("home-game")
        assertNull(coordinator.prepareHomeGame(game, homeTarget(game), startedAtEpochMs = 2_000L, onHeld = { heard += it }))
        assertEquals("a Main game launch hears the same", listOf(Running), heard)
    }

    @Test
    fun `a transfer to Main is starting until the Main host proves it`() {
        val coordinator = MimirSessionCoordinator()
        val transfer = primaryAppSessionOperation(UUID.randomUUID())
        assertTrue(coordinator.appPreparing(transfer, YouTube, MimirAppPlacement.PRIMARY, Origin))
        assertTrue(coordinator.appDispatched(transfer))

        assertEquals("the display-0 host still owns this dispatch", listOf(Starting), heardRefusingAMainApp(coordinator))

        assertTrue(coordinator.appForegroundProven(transfer))

        assertEquals(listOf(Running), heardRefusingAMainApp(coordinator))
    }

    @Test
    fun `a running session wins over one still starting`() {
        // A Main app still starting, with a Bottom game already running beside it.
        val gameRunning = MimirSessionCoordinator()
        assertNotNull(gameRunning.preparePrimaryApp(Chrome, Origin, startedAtEpochMs = 1_000L))
        val bottom = game("bottom")
        val bottomOperation = requireNotNull(
            gameRunning.prepareHomeGame(
                bottom,
                homeTarget(bottom),
                startedAtEpochMs = 2_000L,
                placement = MimirGamePlacement.BOTTOM,
            ),
        )
        assertTrue(gameRunning.gameSettled(bottomOperation, bottom.gameId, dispatchedResult(bottom)))
        assertTrue(gameRunning.gameYielded(bottomOperation))

        assertEquals(listOf(Running), heardRefusingAMainApp(gameRunning))

        // A Main app already running, with a Bottom game still starting beside it.
        val appRunning = MimirSessionCoordinator()
        val chrome = requireNotNull(appRunning.preparePrimaryApp(Chrome, Origin, startedAtEpochMs = 1_000L))
        assertTrue(appRunning.appDispatched(chrome))
        val starting = game("starting")
        assertNotNull(
            appRunning.prepareHomeGame(
                starting,
                homeTarget(starting),
                startedAtEpochMs = 2_000L,
                placement = MimirGamePlacement.BOTTOM,
            ),
        )

        assertEquals(listOf(Running), heardRefusingAMainApp(appRunning))
    }

    @Test
    fun `a Bottom game launch is not held by a Main app, and hears nothing`() {
        val coordinator = MimirSessionCoordinator()
        val chrome = requireNotNull(coordinator.preparePrimaryApp(Chrome, Origin, startedAtEpochMs = 1_000L))
        assertTrue(coordinator.appDispatched(chrome))
        val heard = mutableListOf<GameLaunchSessionHold>()
        val game = game("bottom")

        assertNotNull(
            coordinator.prepareHomeGame(
                game,
                homeTarget(game),
                startedAtEpochMs = 2_000L,
                placement = MimirGamePlacement.BOTTOM,
                onHeld = { heard += it },
            ),
        )

        assertEquals(emptyList<GameLaunchSessionHold>(), heard)
    }

    @Test
    fun `the Library hears what held its own refused launch, and only its own`() {
        val coordinator = MimirSessionCoordinator()
        val running = preparing(1L, game("running"))
        assertTrue(coordinator.gamePreparing(running))
        assertTrue(coordinator.gameSettled(settled(running)))
        assertTrue(coordinator.gameYielded(libraryGameSessionOperation(running.operation)))
        val refusals = LibraryLaunchRefusals()
        val refused = preparing(2L, game("refused"))

        assertFalse(coordinator.gamePreparing(refused) { hold -> refusals.record(refused.operation, hold) })

        assertEquals(Running, refusals.holdFor(refused.operation))
        assertEquals(
            "a launch that was never refused keeps today's sentence",
            Starting,
            refusals.holdFor(LibraryGameLaunchOperation(3L)),
        )
    }

    /** Asks the ledger for a Main app, asserts it was refused, and returns what the refusal reported. */
    private fun heardRefusingAMainApp(coordinator: MimirSessionCoordinator): List<GameLaunchSessionHold> {
        val heard = mutableListOf<GameLaunchSessionHold>()
        assertNull(
            "premise: the Main app launch is refused",
            coordinator.preparePrimaryApp(AppleMusic, Origin, startedAtEpochMs = 9_000L, onHeld = { heard += it }),
        )
        return heard
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
        result = dispatchedResult(preparing.game),
    )

    private fun dispatchedResult(game: GameRow) = LaunchResult(
        readiness = LaunchReadiness.Ready(
            LaunchTarget(
                gameId = game.gameId,
                platformId = game.platformId,
                emulator = EmulatorProfile(
                    id = EmulatorId("retroarch-snes"),
                    packageName = "com.retroarch",
                    displayName = "RetroArch",
                    installed = true,
                    capability = EmulatorCapability.DIRECT_PAYLOAD,
                    locatorForm = LaunchLocatorForm.CONTENT_URI,
                    supportedPlatforms = setOf(game.platformId),
                ),
                locator = "content://mimir/${game.gameId.value}",
                locatorForm = LaunchLocatorForm.CONTENT_URI,
                title = game.title,
            ),
        ),
        outcome = LaunchOutcome.IntentSent,
    )

    private fun homeTarget(game: GameRow) = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 42L)

    private companion object {
        val Starting = GameLaunchSessionHold.STARTING
        val Running = GameLaunchSessionHold.RUNNING
        const val StartingSentence = "Mimir is already launching something else. Wait for it to finish, then try again."
        const val RunningSentence = "A game or app is still open. Close it first, then try again."
        val Origin = PrimaryReturnOrigin("HOME")
        val AppleMusic = CompanionRunningApp("com.apple.android.music", "Apple Music")
        val Chrome = CompanionRunningApp("com.android.chrome", "Chrome")
        val YouTube = CompanionRunningApp("com.google.android.youtube", "YouTube")
    }
}
