package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.AppEntry
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplayState
import com.gruning.mimir.rewrite.core.companionnative.SecondaryTaskCloseState
import com.gruning.mimir.rewrite.core.companionnative.SecondaryTaskKind
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
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * The Bottom game is a tab now. These cover the projection the drawer reads and the one-game admission the
 * reopen must not break.
 */
class CompanionGameTabTest {

    @Test
    fun `a live bottom game session yields exactly one Game entry`() {
        val entries = companionDrawerEntries(
            state = SecondaryDisplayState(secondaryDisplayId = 4, recents = listOf(gameRow())),
            session = bottomGameSnapshot(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK),
            iconPaths = mapOf(EmulatorPackage to "/icons/retroarch.png"),
            running = null,
            selfPackage = SelfPackage,
        )

        assertEquals(1, entries.size)
        val tab = entries.single()
        assertEquals(EmulatorPackage, tab.packageName)
        assertEquals("Chrono Trigger", tab.label)
        assertEquals(CompanionGameTabKindLabel, tab.kindLabel)
        assertEquals("content://art/chrono", tab.artRef)
        assertEquals("/icons/retroarch.png", tab.iconPath)
        assertTrue(tab.closable)
    }

    @Test
    fun `a main game session yields no bottom tab`() {
        // A Main-placement game never reaches the secondary registry, so it has no row — asserted here
        // rather than assumed, because the drawer would otherwise offer a ✕ for the game on screen 1.
        val entries = companionDrawerEntries(
            state = SecondaryDisplayState(secondaryDisplayId = 4, recents = emptyList()),
            session = bottomGameSnapshot(
                MimirSessionPhase.DISPATCHED,
                placement = MimirGamePlacement.MAIN,
            ),
            iconPaths = emptyMap(),
            running = null,
            selfPackage = SelfPackage,
        )

        assertEquals(emptyList<Any>(), entries)
    }

    @Test
    fun `a game tab whose session has settled keeps its persisted name`() {
        // The whole point of the ledger row: the player swiped back to the shelf, the session settled,
        // and the tab is still there to be reopened.
        val entries = companionDrawerEntries(
            state = SecondaryDisplayState(secondaryDisplayId = 4, recents = listOf(gameRow())),
            session = MimirSessionSnapshot(),
            iconPaths = emptyMap(),
            running = null,
            selfPackage = SelfPackage,
        )

        val tab = entries.single()
        assertEquals("Chrono Trigger", tab.label)
        assertEquals(CompanionGameTabKindLabel, tab.kindLabel)
        assertTrue(tab.closable)
    }

    @Test
    fun `a game tab with no resolved title falls back to the emulator label`() {
        val entries = companionDrawerEntries(
            state = SecondaryDisplayState(
                secondaryDisplayId = 4,
                recents = listOf(gameRow().copy(gameTitle = null, gameArtRef = null)),
            ),
            session = MimirSessionSnapshot(),
            iconPaths = emptyMap(),
            running = null,
            selfPackage = SelfPackage,
        )

        assertEquals("RetroArch", entries.single().label)
        assertNull(entries.single().artRef)
    }

    @Test
    fun `a close in flight outranks the Game kind line`() {
        val closing = gameRow().copy(
            closeState = SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED,
            closeOperationId = java.util.UUID(0L, 9L),
            closeRequestedAtElapsedRealtimeMs = 10L,
        )
        val entries = companionDrawerEntries(
            state = SecondaryDisplayState(secondaryDisplayId = 4, recents = listOf(closing)),
            session = MimirSessionSnapshot(),
            iconPaths = emptyMap(),
            running = null,
            selfPackage = SelfPackage,
        )

        assertEquals("Close requested", entries.single().kindLabel)
        assertFalse(entries.single().closable)
    }

    @Test
    fun `the main screen running app is still appended beside a game tab`() {
        val entries = companionDrawerEntries(
            state = SecondaryDisplayState(secondaryDisplayId = 4, recents = listOf(gameRow())),
            session = bottomGameSnapshot(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK),
            iconPaths = emptyMap(),
            running = CompanionRunningApp("com.android.chrome", "Chrome"),
            selfPackage = SelfPackage,
        )

        assertEquals(listOf(EmulatorPackage, "com.android.chrome"), entries.map { it.packageName })
        assertEquals(listOf(CompanionGameTabKindLabel, "Main screen"), entries.map { it.kindLabel })
    }

    @Test
    fun `liveBottomGameFor only matches the emulator the game was dispatched to`() {
        val snapshot = bottomGameSnapshot(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)

        assertNotNull(snapshot.liveBottomGameFor(EmulatorPackage))
        assertNotNull(snapshot.liveBottomGameFor(EmulatorPackage.uppercase()))
        assertNull(snapshot.liveBottomGameFor("com.android.chrome"))
        assertNull(snapshot.liveBottomGameFor(""))
        // RECOVERING is an admitted-session mutex while cleanup runs, not a game to hand back.
        assertNull(
            bottomGameSnapshot(MimirSessionPhase.RECOVERING).liveBottomGameFor(EmulatorPackage),
        )
        assertNull(bottomGameSnapshot(MimirSessionPhase.ENDED).liveBottomGameFor(EmulatorPackage))
    }

    @Test
    fun `closing the tab of a main dual-screen game ends its session and reopens admission`() {
        // a MAIN-placed DS/3DS game closed from its Tabs row left the
        // launch guard believing a game was running. The ✕ must end the game dispatched to that package,
        // whatever display Mimir placed it on.
        listOf(PlatformId.DS, PlatformId.THREE_DS).forEach { platform ->
            val coordinator = MimirSessionCoordinator()
            val game = chronoTrigger().copy(platformId = platform)
            val target = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 0L)
            val operation = requireNotNull(coordinator.prepareHomeGame(game = game, returnTarget = target))
            assertTrue(coordinator.gameSettled(operation, game.gameId, dispatched(game)))
            assertTrue(coordinator.gameYielded(operation)) // MainActivity.onStop's hand-off edge
            val live = coordinator.state.value
            assertEquals(MimirGamePlacement.MAIN, live.gameSession?.placement)
            assertTrue(live.gameSession?.dualScreen == true)
            assertNull("the Bottom-only lookup does not see a Main game", live.liveBottomGameFor(EmulatorPackage))

            val ended = coordinator.endGameForTabClose(EmulatorPackage)

            assertEquals("$platform", operation, ended?.operation)
            assertEquals(MimirSessionPhase.ENDED, coordinator.state.value.gameSession?.phase)
            assertNotNull(
                "admission must reopen after the close",
                coordinator.prepareHomeGame(game = chronoTrigger(), returnTarget = target),
            )
        }
    }

    @Test
    fun `a close pinned to an earlier operation never ends the replacement session on the same emulator`() {
        // A Now Playing Quit that began for one session and completes after a new launch of the same emulator must end nothing:
        // matching the package alone cannot tell the two sessions apart, the operation can.
        val coordinator = MimirSessionCoordinator()
        val game = chronoTrigger().copy(platformId = PlatformId.DS)
        val target = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 0L)
        val operation = requireNotNull(coordinator.prepareHomeGame(game = game, returnTarget = target))
        assertTrue(coordinator.gameSettled(operation, game.gameId, dispatched(game)))
        val revision = coordinator.state.value.revision
        val other = MimirGameSessionOperation("some-earlier-operation")

        assertNull("a pin to another operation ends nothing", coordinator.endGameForTabClose(EmulatorPackage, other))
        assertEquals("and changes nothing", revision, coordinator.state.value.revision)
        assertEquals(operation, coordinator.state.value.gameSession?.operation)
        // The pin to the live operation ends it, as the unpinned close does.
        assertEquals(operation, coordinator.endGameForTabClose(EmulatorPackage, operation)?.operation)
        assertEquals(MimirSessionPhase.ENDED, coordinator.state.value.gameSession?.phase)
    }

    @Test
    fun `a tab close for another package leaves the main game untouched`() {
        val coordinator = MimirSessionCoordinator()
        val game = chronoTrigger().copy(platformId = PlatformId.DS)
        val target = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 0L)
        val operation = requireNotNull(coordinator.prepareHomeGame(game = game, returnTarget = target))
        assertTrue(coordinator.gameSettled(operation, game.gameId, dispatched(game)))
        val revision = coordinator.state.value.revision

        assertNull(coordinator.endGameForTabClose("com.android.chrome"))
        assertEquals(revision, coordinator.state.value.revision)
        assertNull(coordinator.prepareHomeGame(game = chronoTrigger(), returnTarget = target))
    }

    @Test
    fun `reopening the game tab cannot mint a second game operation`() {
        val coordinator = MimirSessionCoordinator()
        val game = chronoTrigger()
        val operation = requireNotNull(
            coordinator.prepareHomeGame(
                game = game,
                returnTarget = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 0L),
                placement = MimirGamePlacement.BOTTOM,
            ),
        )
        assertTrue(coordinator.gameSettled(operation, game.gameId, dispatched(game)))
        val afterDispatch = coordinator.state.value

        assertEquals(MimirSessionPhase.DISPATCHED, afterDispatch.gameSession?.phase)
        assertEquals(EmulatorPackage, afterDispatch.gameSession?.targetPackageName)
        assertNotNull(afterDispatch.liveBottomGameFor(EmulatorPackage))

        // The tap on the tab is a secondary-APP relaunch of the emulator; nothing on the reopen path may
        // admit a new game. Prove the admission guard the reopen would have to break.
        assertNull(
            coordinator.prepareHomeGame(
                game = game,
                returnTarget = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 0L),
                placement = MimirGamePlacement.BOTTOM,
            ),
        )
        assertEquals(afterDispatch.revision, coordinator.state.value.revision)
        assertEquals(operation, coordinator.state.value.gameSession?.operation)
    }

    @Test
    fun `the dispatch hook can name the bottom game tab`() {
        val coordinator = MimirSessionCoordinator()
        val named = mutableListOf<Triple<String, String?, String?>>()
        coordinator.onGameDispatched = { pkg ->
            coordinator.state.value.gameSession
                ?.takeIf { it.placement == MimirGamePlacement.BOTTOM }
                ?.let { named += Triple(pkg, it.title, it.gameId.value) }
        }
        val game = chronoTrigger()
        val operation = requireNotNull(
            coordinator.prepareHomeGame(
                game = game,
                returnTarget = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 0L),
                placement = MimirGamePlacement.BOTTOM,
            ),
        )
        assertTrue(coordinator.gameSettled(operation, game.gameId, dispatched(game)))

        assertEquals(listOf(Triple(EmulatorPackage, "Chrono Trigger", "chrono")), named)
        assertEquals("content://art/chrono", coordinator.state.value.gameSession?.artRef)
    }

    @Test
    fun `a bottom game launch is in flight only while it is preparing or dispatched`() {
        // Tabs liveness (Thor, 2026-09-12): a GAME row is filed at dispatch, before the emulator has a process,
        // so a launch that is still starting keeps its row. Nothing else may.
        val preparing = bottomGameSnapshot(MimirSessionPhase.PREPARING).let { snapshot ->
            // A preparing session has not resolved its emulator yet, so it holds every package.
            snapshot.copy(gameSession = snapshot.gameSession?.copy(targetPackageName = null))
        }
        assertTrue(preparing.bottomGameLaunchInFlightFor(EmulatorPackage))
        assertTrue(preparing.bottomGameLaunchInFlightFor("org.ppsspp.ppsspp"))

        val dispatched = bottomGameSnapshot(MimirSessionPhase.DISPATCHED)
        assertTrue(dispatched.bottomGameLaunchInFlightFor(EmulatorPackage))
        assertTrue(dispatched.bottomGameLaunchInFlightFor(EmulatorPackage.uppercase()))
        assertFalse(dispatched.bottomGameLaunchInFlightFor("org.ppsspp.ppsspp"))

        listOf(
            MimirSessionPhase.FOREGROUND_PROVEN,
            MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
            MimirSessionPhase.RETURNING,
            MimirSessionPhase.RECOVERING,
            MimirSessionPhase.ENDED,
            MimirSessionPhase.FAILED,
            MimirSessionPhase.STALE,
        ).forEach { phase ->
            assertFalse("$phase", bottomGameSnapshot(phase).bottomGameLaunchInFlightFor(EmulatorPackage))
        }
        assertFalse(
            "a Main game has no Bottom row to spare",
            bottomGameSnapshot(MimirSessionPhase.DISPATCHED, placement = MimirGamePlacement.MAIN)
                .bottomGameLaunchInFlightFor(EmulatorPackage),
        )
        // The Thor's case: the process that dispatched Minish Cap died, so this one holds no session at all.
        assertFalse(MimirSessionSnapshot().bottomGameLaunchInFlightFor(EmulatorPackage))
    }

    private fun gameRow() = AppEntry(
        packageName = EmulatorPackage,
        label = "RetroArch",
        displayId = 4,
        lastSeenAtMs = 100L,
        kind = SecondaryTaskKind.GAME,
        gameTitle = "Chrono Trigger",
        gameId = "chrono",
        gameArtRef = "content://art/chrono",
    )

    private fun chronoTrigger() = GameRow(
        gameId = GameId("chrono"),
        title = "Chrono Trigger",
        platformId = PlatformId.SNES,
        memberCount = 1,
        duplicateKind = null,
        artUri = "content://art/chrono",
    )

    private fun bottomGameSnapshot(
        phase: MimirSessionPhase,
        placement: MimirGamePlacement = MimirGamePlacement.BOTTOM,
    ) = MimirSessionSnapshot(
        revision = 1L,
        gameSession = MimirGameSession(
            operation = MimirGameSessionOperation("home:test"),
            sequence = 1L,
            gameId = GameId("chrono"),
            title = "Chrono Trigger",
            artRef = "content://art/chrono",
            platformId = PlatformId.SNES,
            returnAnchor = MimirGameReturnAnchor.Home(
                HomeLaunchReturnTarget(HomeShelfItemId.Emulated(GameId("chrono")), rank = 0L),
            ),
            startedAtEpochMs = 1_000L,
            targetPackageName = EmulatorPackage,
            placement = placement,
            dualScreen = false,
            phase = phase,
        ),
    )

    private fun dispatched(game: GameRow) = LaunchResult(
        readiness = LaunchReadiness.Ready(
            LaunchTarget(
                gameId = game.gameId,
                platformId = game.platformId,
                emulator = EmulatorProfile(
                    id = EmulatorId("retroarch-snes"),
                    packageName = EmulatorPackage,
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

    private companion object {
        const val EmulatorPackage = "com.retroarch"
        const val SelfPackage = "com.gruning.mimir.rewrite"
    }
}
