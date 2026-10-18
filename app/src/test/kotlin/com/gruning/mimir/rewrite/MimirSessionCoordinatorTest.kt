package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.LaunchTargetPreflightContext
import com.gruning.mimir.rewrite.core.launch.LaunchTargetReapResult
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryGameLaunchOperation
import com.gruning.mimir.rewrite.feature.library.LibraryGameSessionEvent
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirSessionCoordinatorTest {
    @Test
    fun mainGameSessionBlocksReverseLauncherSwapAcrossActivePhases() {
        listOf(
            MimirSessionPhase.PREPARING,
            MimirSessionPhase.DISPATCHED,
            MimirSessionPhase.FOREGROUND_PROVEN,
            MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
            MimirSessionPhase.RETURNING,
            MimirSessionPhase.RECOVERING,
        ).forEach { phase ->
            assertTrue(companionMainGameSwapBlocked(MimirGamePlacement.MAIN, phase))
            assertFalse(companionMainGameSwapBlocked(MimirGamePlacement.BOTTOM, phase))
        }
        assertFalse(companionMainGameSwapBlocked(MimirGamePlacement.MAIN, MimirSessionPhase.FAILED))
        assertFalse(companionMainGameSwapBlocked(null, MimirSessionPhase.DISPATCHED))
    }

    @Test
    fun gameHostCannotBeSwappedBackAsAnEmulatorLauncher() {
        assertTrue(
            companionGameHostSwapToPrimaryBlocked(
                com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
                    .LAUNCH_MODE_SECONDARY_GAME,
            ),
        )
        assertFalse(companionGameHostSwapToPrimaryBlocked("secondary_app"))
        assertFalse(companionGameHostSwapToPrimaryBlocked(null))
    }

    @Test
    fun gameDispatchArmsTheHookWithTheEmulatorPackage() {
        // G-2: the dispatch hook carries the owner package the companion re-derives NOW_PLAYING from.
        val coordinator = MimirSessionCoordinator()
        val armed = mutableListOf<String>()
        coordinator.onGameDispatched = armed::add
        val preparing = preparing(1L, game("chrono"))

        assertTrue(coordinator.gamePreparing(preparing))
        assertTrue(coordinator.gameSettled(settled(preparing)))
        assertEquals(listOf("com.retroarch"), armed)

        // An active dispatched game owns admission; a second operation cannot overwrite it.
        val second = preparing(2L, game("second"))
        assertFalse(coordinator.gamePreparing(second))
        assertFalse(coordinator.gameSettled(settled(preparing)))
        assertEquals(1, armed.size)
    }

    @Test
    fun gameSettledMarksDispatchedWhenReadinessIsNeedsBiosButTheLaunchWasSent() {
        //  A confirmed-missing BIOS keeps
        // `readiness` at NeedsBios (so the UI still shows the honest reason via
        // gameLaunchStatusMessage) even though the intent was sent — this must NOT read as a failed
        // launch, or the session/companion machinery (dual-screen yield, return anchors,
        // targetPackageName) silently disagrees with what actually happened on the device.
        val coordinator = MimirSessionCoordinator()
        val armed = mutableListOf<String>()
        coordinator.onGameDispatched = armed::add
        val preparing = preparing(1L, game("re2"))

        assertTrue(coordinator.gamePreparing(preparing))
        assertTrue(coordinator.gameSettled(settledNeedsBios(preparing)))

        val session = coordinator.state.value.gameSession
        assertEquals(MimirSessionPhase.DISPATCHED, session?.phase)
        assertEquals("com.github.stenzek.duckstation", session?.targetPackageName)
        assertNull(session?.failureReason)
        assertEquals(listOf("com.github.stenzek.duckstation"), armed)
    }

    @Test
    fun activeGameAdmissionRejectsNewerOperationWithoutOverwritingSession() {
        val coordinator = MimirSessionCoordinator()
        val first = preparing(1L, game("first"))
        val second = preparing(2L, game("second"))

        assertTrue(coordinator.gamePreparing(first))
        assertFalse(coordinator.gamePreparing(second))
        assertTrue(coordinator.gameSettled(settled(first)))

        val current = coordinator.state.value.gameSession
        assertEquals(libraryGameSessionOperation(first.operation), current?.operation)
        assertEquals(MimirSessionPhase.DISPATCHED, current?.phase)
    }

    @Test
    fun synchronousDispatchFailureKeepsTypedReasonAndImmediatelyReopensAdmission() {
        val coordinator = MimirSessionCoordinator()
        val first = preparing(1L, game("failed"))
        assertTrue(coordinator.gamePreparing(first))
        val failed = settled(first).copy(
            result = settled(first).result.copy(
                outcome = LaunchOutcome.Failed(
                    reason = LaunchDispatchFailure.ACTIVITY_NOT_STARTED,
                    targetEmulatorId = EmulatorId("retroarch-snes"),
                ),
            ),
        )

        assertTrue(coordinator.gameSettled(failed))
        assertEquals(MimirSessionPhase.FAILED, coordinator.state.value.gameSession?.phase)
        assertEquals(
            LaunchDispatchFailure.ACTIVITY_NOT_STARTED.name,
            coordinator.state.value.gameSession?.failureReason,
        )
        assertTrue(coordinator.gamePreparing(preparing(2L, game("next"))))
    }

    @Test
    fun genericPrimaryAppAdmissionCannotSuppressAnActiveGame() {
        val coordinator = MimirSessionCoordinator()
        val game = game("metroid")
        val preparing = preparing(1L, game)
        assertTrue(coordinator.gamePreparing(preparing))
        assertTrue(coordinator.gameSettled(settled(preparing)))
        assertTrue(coordinator.state.value.permitsGamePresentation(game.gameId))
        val activeRevision = coordinator.state.value.revision

        val app = CompanionRunningApp("com.example.clock", "Clock")
        assertFalse(
            coordinator.appPreparing(
                operationId = "primary:app-2",
                app = app,
                placement = MimirAppPlacement.PRIMARY,
                returnAnchor = PrimaryReturnOrigin("LIBRARY", revision = 4L),
            ),
        )
        assertEquals(activeRevision, coordinator.state.value.revision)
        assertEquals(MimirSessionPhase.DISPATCHED, coordinator.state.value.gameSession?.phase)
        assertNull(coordinator.state.value.primaryAppSession)
        assertTrue(coordinator.state.value.permitsGamePresentation(game.gameId))
    }

    @Test
    fun secondaryAppCoexistsWithActiveGameAndDoesNotSuppressPresentation() {
        val coordinator = MimirSessionCoordinator()
        val game = game("metroid")
        val preparing = preparing(1L, game)
        assertTrue(coordinator.gamePreparing(preparing))
        assertTrue(coordinator.gameSettled(settled(preparing)))

        assertTrue(
            coordinator.appPreparing(
                operationId = "secondary:clock",
                app = CompanionRunningApp("com.example.clock", "Clock"),
                placement = MimirAppPlacement.SECONDARY,
                returnAnchor = PrimaryReturnOrigin("LIBRARY"),
            ),
        )
        assertTrue(coordinator.appDispatched("secondary:clock"))

        assertEquals(MimirSessionPhase.DISPATCHED, coordinator.state.value.gameSession?.phase)
        assertEquals(MimirAppPlacement.SECONDARY, coordinator.state.value.secondaryAppSession?.placement)
        assertTrue(coordinator.state.value.permitsGamePresentation(game.gameId))
    }

    @Test
    fun gamePreparationPreservesActiveSecondaryApp() {
        val coordinator = MimirSessionCoordinator()
        val origin = PrimaryReturnOrigin("LIBRARY")
        assertTrue(
            coordinator.appPreparing(
                operationId = "secondary:notes",
                app = CompanionRunningApp("com.example.notes", "Notes"),
                placement = MimirAppPlacement.SECONDARY,
                returnAnchor = origin,
            ),
        )
        assertTrue(coordinator.appDispatched("secondary:notes"))

        val preparing = preparing(1L, game("zelda"))
        assertTrue(coordinator.gamePreparing(preparing))

        assertEquals("secondary:notes", coordinator.state.value.secondaryAppSession?.operationId)
        assertEquals(MimirSessionPhase.DISPATCHED, coordinator.state.value.secondaryAppSession?.phase)
    }

    @Test
    fun staleAppCallbackCannotOverwriteNewerAppSession() {
        val coordinator = MimirSessionCoordinator()
        val origin = PrimaryReturnOrigin("LIBRARY", revision = 7L)
        assertTrue(
            coordinator.appPreparing(
                "secondary:first",
                CompanionRunningApp("com.example.first", "First"),
                MimirAppPlacement.SECONDARY,
                origin,
            ),
        )
        assertTrue(
            coordinator.appPreparing(
                "secondary:second",
                CompanionRunningApp("com.example.second", "Second"),
                MimirAppPlacement.SECONDARY,
                origin,
            ),
        )

        assertFalse(coordinator.appYielded("secondary:first"))
        assertFalse(coordinator.appFailed("secondary:first", "late_failure"))
        assertEquals("secondary:second", coordinator.state.value.secondaryAppSession?.operationId)
        assertEquals(MimirSessionPhase.PREPARING, coordinator.state.value.secondaryAppSession?.phase)
    }

    @Test
    fun gameReturnRequiresExactOperationAndRetainsForegroundProof() {
        val coordinator = MimirSessionCoordinator()
        val preparing = preparing(8L, game("zelda"))
        coordinator.gamePreparing(preparing)
        coordinator.gameSettled(settled(preparing))

        val dispatchedRevision = coordinator.state.value.revision
        assertNull(coordinator.beginGameReturn(MimirGameSessionOperation("library:stale")))
        assertEquals(dispatchedRevision, coordinator.state.value.revision)
        val ticket = coordinator.beginGameReturn(libraryGameSessionOperation(preparing.operation))
        assertEquals(libraryGameSessionOperation(preparing.operation), ticket?.operation)
        val returningRevision = coordinator.state.value.revision
        assertEquals(ticket, coordinator.beginCurrentGameReturn())
        assertEquals(returningRevision, coordinator.state.value.revision)
        assertFalse(
            coordinator.completeGameReturn(
                libraryGameSessionOperation(LibraryGameLaunchOperation(9L)),
                foregroundProven = true,
            ),
        )
        assertTrue(
            coordinator.completeGameReturn(
                libraryGameSessionOperation(preparing.operation),
                foregroundProven = true,
            ),
        )
        assertEquals(MimirSessionPhase.ENDED, coordinator.state.value.gameSession?.phase)
        assertTrue(coordinator.state.value.gameSession?.foregroundProven == true)
    }

    @Test
    fun homeGameUsesNamespacedOperationAndTypedHomeReturnAnchor() {
        val coordinator = MimirSessionCoordinator()
        val game = game("home-game")
        val operation = MimirGameSessionOperation("home:test-operation")
        val target = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 42L)

        assertTrue(
            coordinator.gamePreparing(
                operation = operation,
                game = game,
                returnAnchor = MimirGameReturnAnchor.Home(target),
                startedAtEpochMs = 3_000L,
            ),
        )
        assertTrue(coordinator.gameSettled(operation, game.gameId, settled(preparing(1L, game)).result))

        val session = coordinator.state.value.gameSession
        assertEquals(operation, session?.operation)
        assertEquals(MimirGameReturnAnchor.Home(target), session?.returnAnchor)
        assertEquals(MimirSessionPhase.DISPATCHED, session?.phase)
    }

    @Test
    fun homePreparationRejectsSecondActiveGameAndAllowsAfterTerminalState() {
        val coordinator = MimirSessionCoordinator()
        val firstGame = game("first-home")
        val secondGame = game("second-home")
        val first = coordinator.prepareHomeGame(firstGame, homeTarget(firstGame), startedAtEpochMs = 1_000L)
        assertNotNull(first)
        val activeRevision = coordinator.state.value.revision

        assertNull(coordinator.prepareHomeGame(secondGame, homeTarget(secondGame), startedAtEpochMs = 2_000L))
        assertEquals(activeRevision, coordinator.state.value.revision)
        assertEquals(firstGame.gameId, coordinator.state.value.gameSession?.gameId)

        assertTrue(coordinator.gameFailed(requireNotNull(first), firstGame.gameId, "dispatch_failed"))
        assertNotNull(coordinator.prepareHomeGame(secondGame, homeTarget(secondGame), startedAtEpochMs = 3_000L))
    }

    @Test
    fun resumedHostWatchdogTerminalizesOnlyTheExactDispatchedOperation() {
        val coordinator = MimirSessionCoordinator(
            clockMillis = { 2_500L },
            monotonicMillis = { 7_500L },
        )
        val firstGame = game("first-watchdog")
        val returnHandle = HomeLaunchReturnHandle(
            target = homeTarget(firstGame),
            token = "watchdog-home-token",
        )
        val first = requireNotNull(
            coordinator.prepareHomeGame(
                game = firstGame,
                returnTarget = returnHandle.target,
                returnHandle = returnHandle,
                startedAtEpochMs = 1_000L,
            ),
        )
        val preflight = LaunchTargetPreflightContext(
            packageName = "com.retroarch",
            result = LaunchTargetReapResult.REQUEST_ACCEPTED,
        )
        assertTrue(
            coordinator.gameSettled(
                first,
                firstGame.gameId,
                settled(preparing(1L, firstGame)).result.copy(preflight = preflight),
            ),
        )
        assertEquals(2_500L, coordinator.state.value.gameSession?.dispatchedAtEpochMs)
        assertEquals(7_500L, coordinator.state.value.gameSession?.dispatchedAtElapsedMs)
        assertEquals(preflight, coordinator.state.value.gameSession?.preflight)

        assertNull(
            coordinator.gameDispatchWatchdogExpired(
                operation = MimirGameSessionOperation("home:stale"),
                expectedPackageName = "com.retroarch",
            ),
        )
        assertNull(
            coordinator.gameDispatchWatchdogExpired(
                operation = first,
                expectedPackageName = "org.example.wrong",
            ),
        )
        val cleanup = coordinator.gameDispatchWatchdogExpired(
            operation = first,
            expectedPackageName = "com.retroarch",
        )
        assertNotNull(cleanup)
        assertEquals(first, cleanup?.operation)
        assertEquals("com.retroarch", cleanup?.expectedPackageName)
        assertEquals(returnHandle, cleanup?.homeReturnHandle)
        assertEquals(MimirSessionPhase.RECOVERING, coordinator.state.value.gameSession?.phase)
        assertFalse(coordinator.state.value.gameSession?.foregroundProven == true)

        val secondGame = game("second-watchdog")
        assertNull(coordinator.prepareHomeGame(secondGame, homeTarget(secondGame), startedAtEpochMs = 3_000L))
        assertTrue(
            coordinator.gameDispatchWatchdogCleanupComplete(
                operation = first,
                expectedPackageName = "com.retroarch",
            ),
        )
        assertEquals(MimirSessionPhase.FAILED, coordinator.state.value.gameSession?.phase)
        val second = requireNotNull(
            coordinator.prepareHomeGame(secondGame, homeTarget(secondGame), startedAtEpochMs = 4_000L),
        )
        assertNull(coordinator.gameDispatchWatchdogExpired(first, "com.retroarch"))
        assertEquals(second, coordinator.state.value.gameSession?.operation)
        assertEquals(MimirSessionPhase.PREPARING, coordinator.state.value.gameSession?.phase)
    }

    @Test
    fun normalReturnTransitionCancelsTheDispatchOnlyWatchdogAuthority() {
        val coordinator = MimirSessionCoordinator()
        val game = game("normal-return")
        val operation = requireNotNull(
            coordinator.prepareHomeGame(game, homeTarget(game), startedAtEpochMs = 1_000L),
        )
        assertTrue(coordinator.gameSettled(operation, game.gameId, settled(preparing(1L, game)).result))
        assertNotNull(coordinator.beginGameReturn(operation))

        assertNull(coordinator.gameDispatchWatchdogExpired(operation, "com.retroarch"))
        assertEquals(MimirSessionPhase.RETURNING, coordinator.state.value.gameSession?.phase)
    }

    @Test
    fun staleOnStopYieldCannotDisplaceWatchdogRecoveryOwnership() {
        val coordinator = MimirSessionCoordinator()
        val game = game("recovering-yield-race")
        val operation = requireNotNull(
            coordinator.prepareHomeGame(game, homeTarget(game), startedAtEpochMs = 1_000L),
        )
        assertTrue(coordinator.gameSettled(operation, game.gameId, settled(preparing(1L, game)).result))
        assertNotNull(coordinator.gameDispatchWatchdogExpired(operation, "com.retroarch"))
        assertEquals(MimirSessionPhase.RECOVERING, coordinator.state.value.gameSession?.phase)

        assertFalse(coordinator.gameYielded(operation))
        assertEquals(MimirSessionPhase.RECOVERING, coordinator.state.value.gameSession?.phase)
        assertTrue(coordinator.gameDispatchWatchdogCleanupComplete(operation, "com.retroarch"))
        assertEquals(MimirSessionPhase.FAILED, coordinator.state.value.gameSession?.phase)
    }

    @Test
    fun yieldedPrimaryWindowCannotRearmOldWatchdogAndImmediateRelaunchIsAdmittedAfterReturn() {
        val coordinator = MimirSessionCoordinator()
        val firstGame = game("yielded-return")
        val first = requireNotNull(
            coordinator.prepareHomeGame(firstGame, homeTarget(firstGame), startedAtEpochMs = 1_000L),
        )
        assertTrue(coordinator.gameSettled(first, firstGame.gameId, settled(preparing(1L, firstGame)).result))

        assertTrue(coordinator.gameYielded(first))
        assertEquals(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK, coordinator.state.value.gameSession?.phase)
        assertNull(coordinator.gameDispatchWatchdogExpired(first, "com.retroarch"))

        assertNotNull(coordinator.beginGameReturn(first))
        assertTrue(coordinator.completeGameReturn(first, foregroundProven = true))
        val second = coordinator.prepareHomeGame(
            firstGame,
            homeTarget(firstGame),
            startedAtEpochMs = 2_000L,
        )
        assertNotNull(second)
        assertTrue(second != first)
    }

    @Test
    fun dispatchWatchdogGraceMatchesTheQuickReturnHonestyBoundary() {
        assertEquals(HomeExternalSessionElapsedThresholdMs, GameDispatchPrimaryGraceMs)
        assertEquals(3_000L, GameDispatchPrimaryGraceMs)
    }

    @Test
    fun homePreparationRejectsActivePrimaryAppButAllowsActiveSecondaryApp() {
        val coordinator = MimirSessionCoordinator()
        val origin = PrimaryReturnOrigin("HOME")
        val primary = requireNotNull(
            coordinator.preparePrimaryApp(
                CompanionRunningApp("com.example.primary", "Primary"),
                origin,
                startedAtEpochMs = 1_000L,
            ),
        )
        val game = game("home-game")
        val activeRevision = coordinator.state.value.revision

        assertNull(coordinator.prepareHomeGame(game, homeTarget(game), startedAtEpochMs = 2_000L))
        assertEquals(activeRevision, coordinator.state.value.revision)

        assertTrue(coordinator.appFailed(primary, "dispatch_failed"))
        assertTrue(
            coordinator.appPreparing(
                operationId = "secondary:notes",
                app = CompanionRunningApp("com.example.notes", "Notes"),
                placement = MimirAppPlacement.SECONDARY,
                returnAnchor = origin,
            ),
        )
        assertNotNull(coordinator.prepareHomeGame(game, homeTarget(game), startedAtEpochMs = 3_000L))
        assertEquals(MimirAppPlacement.SECONDARY, coordinator.state.value.secondaryAppSession?.placement)
    }

    @Test
    fun primaryPreparationRejectsActivePrimaryAndPreservesSecondary() {
        val coordinator = MimirSessionCoordinator()
        val origin = PrimaryReturnOrigin("LIBRARY")
        assertTrue(
            coordinator.appPreparing(
                operationId = "secondary:browser",
                app = CompanionRunningApp("com.example.browser", "Browser"),
                placement = MimirAppPlacement.SECONDARY,
                returnAnchor = origin,
            ),
        )
        assertTrue(coordinator.appDispatched("secondary:browser"))

        val primary = coordinator.preparePrimaryApp(
            CompanionRunningApp("com.example.primary", "Primary"),
            origin,
            startedAtEpochMs = 4_000L,
        )
        assertNotNull(primary)
        assertEquals(MimirAppPlacement.PRIMARY, coordinator.state.value.primaryAppSession?.placement)
        assertEquals("secondary:browser", coordinator.state.value.secondaryAppSession?.operationId)
        val activeRevision = coordinator.state.value.revision

        assertNull(
            coordinator.preparePrimaryApp(
                CompanionRunningApp("com.example.other", "Other"),
                origin,
                startedAtEpochMs = 5_000L,
            ),
        )
        assertEquals(activeRevision, coordinator.state.value.revision)

        assertTrue(coordinator.appFailed(requireNotNull(primary), "dispatch_failed"))
        assertNotNull(
            coordinator.preparePrimaryApp(
                CompanionRunningApp("com.example.other", "Other"),
                origin,
                startedAtEpochMs = 6_000L,
            ),
        )
    }

    @Test
    fun newerSecondarySessionCannotDisplacePrimaryReturnAuthority() {
        val coordinator = MimirSessionCoordinator()
        val origin = PrimaryReturnOrigin("LIBRARY")
        assertTrue(
            coordinator.appPreparing(
                "primary:reader",
                CompanionRunningApp("com.example.reader", "Reader"),
                MimirAppPlacement.PRIMARY,
                origin,
            ),
        )
        assertTrue(coordinator.appDispatched("primary:reader"))
        assertTrue(
            coordinator.appPreparing(
                "secondary:notes",
                CompanionRunningApp("com.example.notes", "Notes"),
                MimirAppPlacement.SECONDARY,
                origin,
            ),
        )
        assertTrue(coordinator.appDispatched("secondary:notes"))

        val ticket = coordinator.beginCurrentPrimaryAppReturn()
        assertEquals("primary:reader", ticket?.operationId)
        assertTrue(coordinator.completePrimaryAppReturn("primary:reader", foregroundProven = true))
        assertEquals(MimirSessionPhase.ENDED, coordinator.state.value.primaryAppSession?.phase)
        assertEquals(MimirSessionPhase.DISPATCHED, coordinator.state.value.secondaryAppSession?.phase)
    }

    @Test
    fun primaryPreparationRejectsActiveGameWithoutMutatingEitherSession() {
        val coordinator = MimirSessionCoordinator()
        val game = game("active-game")
        val operation = requireNotNull(
            coordinator.prepareHomeGame(game, homeTarget(game), startedAtEpochMs = 1_000L),
        )
        val activeRevision = coordinator.state.value.revision

        assertNull(
            coordinator.preparePrimaryApp(
                CompanionRunningApp("com.example.primary", "Primary"),
                PrimaryReturnOrigin("HOME"),
                startedAtEpochMs = 2_000L,
            ),
        )
        assertEquals(activeRevision, coordinator.state.value.revision)
        assertEquals(operation, coordinator.state.value.gameSession?.operation)
        assertNull(coordinator.state.value.appSession)
    }

    @Test
    fun primaryAppReturnRequiresExactOperationAndRecordsForegroundProof() {
        val coordinator = MimirSessionCoordinator()
        val app = CompanionRunningApp("com.example.native", "Native")
        assertTrue(
            coordinator.appPreparing(
                "primary-native:one",
                app,
                MimirAppPlacement.PRIMARY,
                PrimaryReturnOrigin("HOME", revision = 9L),
            ),
        )
        assertTrue(coordinator.appDispatched("primary-native:one"))

        val dispatchedRevision = coordinator.state.value.revision
        assertNull(coordinator.beginPrimaryAppReturn("primary-native:stale"))
        assertEquals(dispatchedRevision, coordinator.state.value.revision)
        val ticket = coordinator.beginPrimaryAppReturn("primary-native:one")
        assertEquals("primary-native:one", ticket?.operationId)
        val returningRevision = coordinator.state.value.revision
        assertEquals(ticket, coordinator.beginCurrentPrimaryAppReturn())
        assertEquals(returningRevision, coordinator.state.value.revision)
        assertFalse(coordinator.completePrimaryAppReturn("primary-native:stale", foregroundProven = true))
        assertTrue(coordinator.completePrimaryAppReturn("primary-native:one", foregroundProven = true))
        assertEquals(MimirSessionPhase.ENDED, coordinator.state.value.primaryAppSession?.phase)
        assertTrue(coordinator.state.value.primaryAppSession?.foregroundProven == true)
    }

    @Test
    fun hostCoveredSecondaryYieldRecordsForegroundProofBeforeYieldedPhase() {
        val operationId = OperationId.create()
        val lease = HostLease(
            operationId = operationId,
            packageName = "com.example.secondary",
            displayId = 4,
            launchMode = "secondary_app",
            primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
        )
        MimirCompanionRuntimeSessionBridge.onRuntimeEvent(
            "LaunchRequested",
            runtimeSnapshot(SecondaryDisplayDisposition.LaunchPending(lease, LaunchPhase.HOST_REQUESTED)),
        )
        MimirCompanionRuntimeSessionBridge.onRuntimeEvent(
            "TargetDispatched",
            runtimeSnapshot(SecondaryDisplayDisposition.LaunchPending(lease, LaunchPhase.TARGET_DISPATCHED)),
        )
        MimirCompanionRuntimeSessionBridge.onRuntimeEvent(
            "HostCovered",
            runtimeSnapshot(SecondaryDisplayDisposition.Yielded(lease, YieldEvidence.HOST_COVERED)),
        )

        val session = MimirSessionAuthority.state.value.secondaryAppSession
        assertEquals(secondaryAppSessionOperation(operationId), session?.operationId)
        assertTrue(session?.foregroundProven == true)
        assertEquals(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK, session?.phase)
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

    private fun homeTarget(game: GameRow) =
        HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 42L)

    private fun runtimeSnapshot(disposition: SecondaryDisplayDisposition) = CompanionRuntimeSnapshot(
        stateVersion = 1L,
        displayId = 4,
        disposition = disposition,
        rendererOwnerId = null,
        mirrorActive = disposition is SecondaryDisplayDisposition.MirrorPresented,
        launchLockOperationId = null,
        launchSettlement = null,
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

    /** a confirmed-missing BIOS still dispatches — readiness stays NeedsBios
     *  (carrying the same resolved target a Ready result would have) while outcome is IntentSent. */
    private fun settledNeedsBios(preparing: LibraryGameSessionEvent.Preparing) = LibraryGameSessionEvent.Settled(
        operation = preparing.operation,
        game = preparing.game,
        returnTarget = preparing.returnTarget,
        result = LaunchResult(
            readiness = LaunchReadiness.NeedsBios(
                platformId = preparing.game.platformId,
                missing = emptyList(),
                presenceUnknown = false,
                target = LaunchTarget(
                    gameId = preparing.game.gameId,
                    platformId = preparing.game.platformId,
                    emulator = EmulatorProfile(
                        id = EmulatorId("com.github.stenzek.duckstation"),
                        packageName = "com.github.stenzek.duckstation",
                        displayName = "DuckStation",
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
