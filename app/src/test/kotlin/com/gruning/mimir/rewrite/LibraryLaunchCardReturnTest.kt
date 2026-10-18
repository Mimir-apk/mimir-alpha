package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.GamelistScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollState
import com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand
import com.gruning.mimir.rewrite.feature.library.LibraryDetailTarget
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryLaunchCardReturnTest {

    @Before
    @After
    fun clearProcessLease() {
        MainGameParkingState.current()?.let(MainGameParkingState::clear)
    }

    @Test
    fun parkedMainGameIsALegitimateLibraryReturn() {
        // R6-P0-03: Main draws only the Return cover for as long as parking lasts because
        // libraryLaunchReturnMayRestore only allows ENDED/FAILED/STALE. A parked game is
        // YIELDED_TO_SECONDARY_TASK, so the cover never lowers and PLAY/R6-P0-02's refusal
        // message are unreachable until the game actually ends.
        val game = game("threeds/lime3ds-parked")
        val target = launchReturnTarget(game, restoreLaunchCard = true)
        val session = MimirGameSession(
            operation = MimirGameSessionOperation("library:test-parked"),
            sequence = 1L,
            gameId = requireNotNull(target.gameId),
            platformId = requireNotNull(target.romPlatformId),
            returnAnchor = MimirGameReturnAnchor.Library(target),
            startedAtEpochMs = 1L,
            targetPackageName = "io.github.lime3ds.android",
            placement = MimirGamePlacement.MAIN,
            dualScreen = true,
            phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
        )

        assertEquals(
            MainGameParkingArmResult.ARMED,
            MainGameParkingState.arm(session, taskId = 4052),
        )

        assertTrue(libraryLaunchReturnMayRestore(target, session))
    }

    /**
     * Thor combined44 check 6 (2026-10-15, logcat-0359 04:42:26): HOME over a running Eden. The park's verdict landed at .150 and its lease
     * at .489, off the main thread; the Library return effect decided at the resume between the two, saw a YIELDED game with no lease, and
     * nothing re-decided when the lease came: "Welcome back" for 162 s, the card never restored. A pending park is a park.
     */
    @Test
    fun aPendingHomeParkIsALegitimateLibraryReturnBeforeItsLeaseLands() {
        val target = launchReturnTarget(game("switch/eden-parked"), restoreLaunchCard = true)
        val yielded = semanticSession(target, MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
        assertNull("the lease has not landed yet", MainGameParkingState.currentFor(yielded))
        assertFalse("without the park's word nothing restores, as the base had it", libraryLaunchReturnMayRestore(target, yielded))

        assertTrue(libraryLaunchReturnMayRestore(target, yielded, homeParkPending = true))
        assertTrue("whatever phase the ledger shows at that moment", libraryLaunchReturnMayRestore(target, semanticSession(target, MimirSessionPhase.DISPATCHED), homeParkPending = true))
        val other = launchReturnTarget(game("switch/some-other-game"), restoreLaunchCard = true)
        assertFalse("a park of some other game restores nothing here", libraryLaunchReturnMayRestore(target, semanticSession(other, MimirSessionPhase.YIELDED_TO_SECONDARY_TASK), homeParkPending = true))
    }

    @Test
    fun theLibraryReturnEffectAsksTheParkerWhereItDecides() {
        val source = listOf(
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MimirNavigationCoordinator.kt"),
            java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirNavigationCoordinator.kt"),
        ).first(java.io.File::exists).readText()
        val effect = source.substringAfter("fun MimirLibraryLaunchReturnEffect(").substringBefore("fun libraryLaunchReturnMayRestore(")
        val decision = effect.substringAfter("if (!libraryLaunchReturnMayRestore(").substringBefore("return@LaunchedEffect")
        assertTrue("the effect's one decision reads the pending park", decision.contains("homeParkPending = MainGameHomePark.pending(),"))
    }

    @Test
    fun watchdogConsumedTargetCannotStrandLibraryReturnCover() {
        val target = launchReturnTarget(game("gba/watchdog-return"), restoreLaunchCard = true)

        assertTrue(libraryLaunchReturnHasViableAnchor(armed = true, target = target))
        assertFalse(libraryLaunchReturnHasViableAnchor(armed = true, target = null))
        assertFalse(libraryLaunchReturnHasViableAnchor(armed = false, target = target))
    }

    @Test
    fun transientResumeDuringLibraryDispatchCannotRestoreLaunchCardBeforeSemanticReturn() {
        val game = game("snes/library-first-press")
        val target = launchReturnTarget(game, restoreLaunchCard = true)
        val outboundPhases = listOf(
            MimirSessionPhase.PREPARING,
            MimirSessionPhase.DISPATCHED,
            MimirSessionPhase.FOREGROUND_PROVEN,
            MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
            MimirSessionPhase.RETURNING,
            MimirSessionPhase.RECOVERING,
        )

        outboundPhases.forEach { phase ->
            assertFalse(
                "Library return restored during outbound phase $phase",
                libraryLaunchReturnMayRestore(target, semanticSession(target, phase)),
            )
        }
    }

    @Test
    fun completedLibraryReturnOrFailureCanRestoreTheLaunchCard() {
        val game = game("snes/library-returned")
        val target = launchReturnTarget(game, restoreLaunchCard = true)

        listOf(MimirSessionPhase.ENDED, MimirSessionPhase.FAILED, MimirSessionPhase.STALE).forEach { phase ->
            assertTrue(
                "Library return remained blocked after terminal phase $phase",
                libraryLaunchReturnMayRestore(target, semanticSession(target, phase)),
            )
        }
    }

    @Test
    fun romReturnRequiresTheExactSemanticSession() {
        val target = launchReturnTarget(game("snes/library-current"), restoreLaunchCard = true)
        val differentGameTarget = launchReturnTarget(
            game("snes/library-other"),
            restoreLaunchCard = true,
        )
        val differentAnchor = target.copy(searchQuery = "different-origin")

        assertFalse(libraryLaunchReturnMayRestore(target, gameSession = null))
        assertFalse(
            libraryLaunchReturnMayRestore(
                target,
                semanticSession(differentGameTarget, MimirSessionPhase.ENDED),
            ),
        )
        assertFalse(
            libraryLaunchReturnMayRestore(
                target,
                semanticSession(differentAnchor, MimirSessionPhase.ENDED),
            ),
        )
    }

    @Test
    fun nativeReturnKeepsItsExistingLifecycleContract() {
        val target = LibraryLaunchReturnTarget.shelf(
            shelfMode = LibraryShelfMode.ANDROID_GAMES,
            shelfKey = "com.example.game",
            restoreLaunchCard = true,
        )

        assertTrue(libraryLaunchReturnMayRestore(target, gameSession = null))
    }

    @Test
    fun descriptionSnapshotRejectsDisposedAndStaleWritersButContinuouslyAcceptsLiveMeasurement() {
        val settled = HomeLaunchDescriptionScrollState(measured = true, ownerToken = 7L, settledSequence = 4)

        assertEquals(true, acceptLibraryDetailDescriptionState(true, 7L, settled))
        assertEquals(false, acceptLibraryDetailDescriptionState(false, 7L, settled))
        assertEquals(false, acceptLibraryDetailDescriptionState(true, 8L, settled))
        assertEquals(true, acceptLibraryDetailDescriptionState(true, 7L, settled.copy(settledSequence = 1)))
        assertEquals(false, acceptLibraryDetailDescriptionState(true, 7L, settled.copy(measured = false)))
    }

    @Test
    fun descriptionRestoreIssuesOnceAfterCaptureAndRejectsAnotherOwner() {
        val snapshot = HomeLaunchDescriptionScrollState(
            measured = true,
            offset = 88,
            revision = 12L,
            ownerToken = 7L,
        )
        val initial = HomeLaunchDescriptionScrollCommand(ownerToken = 7L)
        val issued = issueLibraryDetailDescriptionRestoreCommand(snapshot, 7L, initial, alreadyIssued = false)

        assertEquals(1, issued?.sequence)
        assertEquals(88, issued?.restoreOffset)
        assertEquals(12L, issued?.expectedRevision)
        assertEquals(issued, issueLibraryDetailDescriptionRestoreCommand(snapshot, 7L, issued!!, true))
        assertEquals(null, issueLibraryDetailDescriptionRestoreCommand(snapshot, 8L, initial, false))
    }

    @Test
    fun cardReturnRebuildsItsGamelistSessionWhenCompositionWasRecreated() {
        val game = game("ps1/chrono-cross")
        val detailTarget = LibraryDetailTarget.Emulated(game)
        val returnTarget = launchReturnTarget(game, restoreLaunchCard = true)

        val liveTarget = liveLibraryLaunchReturnDetailTarget(
            target = returnTarget,
            games = listOf(game),
            nativeGames = emptyList(),
        )
        val session = resolveLibraryLaunchReturnDetailSession(
            target = returnTarget,
            currentSession = null,
            activeTarget = liveTarget,
        )

        assertEquals(detailTarget, session?.target)
        assertEquals(GamelistScope, session?.originScope)
        assertEquals(detailTarget.sourceFocusKey, session?.originFocusKey)
    }

    @Test
    fun externalReturnInstallsValidatedSessionBeforeRequestingDescriptionRestore() {
        val target = LibraryDetailTarget.Emulated(game("ps1/install-order"))
        val session = com.gruning.mimir.rewrite.feature.library.LibraryDetailSession(
            target = target,
            originScope = GamelistScope,
            originFocusKey = target.sourceFocusKey,
        )
        val calls = mutableListOf<String>()

        assertEquals(
            true,
            installLibraryLaunchReturnDetailSession(
                session = session,
                openDetail = { calls += "open:${it.target.itemId}" },
                refreshOpenWith = { calls += "openWith:${it.gameId}" },
                requestDescriptionRestore = { calls += "restore" },
            ),
        )
        assertEquals(
            listOf(
                "open:${session.target.itemId}",
                "openWith:${target.game.gameId}",
                "restore",
            ),
            calls,
        )
    }

    @Test
    fun androidGameReturnDoesNotInventEmulatorChoices() {
        val target = LibraryDetailTarget.AndroidGame(
            NativeShelfRow(
                packageName = "com.example.native",
                label = "Native Game",
                launchActivity = "com.example.native.MainActivity",
                isGame = true,
            ),
        )
        val session = com.gruning.mimir.rewrite.feature.library.LibraryDetailSession(
            target = target,
            originScope = LibraryShelfScope,
            originFocusKey = target.sourceFocusKey,
        )
        val calls = mutableListOf<String>()

        assertTrue(
            installLibraryLaunchReturnDetailSession(
                session = session,
                openDetail = { calls += "open" },
                refreshOpenWith = { calls += "openWith" },
                requestDescriptionRestore = { calls += "restore" },
            ),
        )
        assertEquals(listOf("open", "restore"), calls)
    }

    @Test
    fun settingsReturnAlwaysRequestsScrollRestoreAfterInstallingValidatedDetail() {
        val target = LibraryDetailTarget.Emulated(game("ps1/settings-restore"))
        val session = com.gruning.mimir.rewrite.feature.library.LibraryDetailSession(
            target = target,
            originScope = GamelistScope,
            originFocusKey = target.sourceFocusKey,
        )
        val command = HomeLaunchDescriptionScrollCommand(
            sequence = 2,
            restoreOffset = 64,
            expectedRevision = 5L,
            ownerToken = 9L,
        )
        val calls = mutableListOf<String>()

        val installed = installSettingsLibraryDetailSession(
            session = session,
            openDetail = { calls += "open" },
            requestDescriptionRestore = {
                calls += "restore"
                command
            },
        )

        assertEquals(command, installed)
        assertEquals(listOf("open", "restore"), calls)
    }

    @Test
    fun ordinaryGamelistReturnDoesNotInventALaunchCard() {
        val game = game("ps1/chrono-cross")

        val session = resolveLibraryLaunchReturnDetailSession(
            target = launchReturnTarget(game, restoreLaunchCard = false),
            currentSession = null,
            activeTarget = LibraryDetailTarget.Emulated(game),
        )

        assertNull(session)
    }

    @Test
    fun androidGameCardReturnRebuildsItsShelfSession() {
        val detailTarget = LibraryDetailTarget.AndroidGame(
            NativeShelfRow(
                packageName = "com.example.game",
                label = "Example Game",
                launchActivity = "com.example.game.MainActivity",
                isGame = true,
            ),
        )
        val returnTarget = LibraryLaunchReturnTarget.shelf(
            shelfMode = LibraryShelfMode.ANDROID_GAMES,
            shelfKey = detailTarget.row.packageName,
            restoreLaunchCard = true,
        )

        val liveTarget = liveLibraryLaunchReturnDetailTarget(
            target = returnTarget,
            games = emptyList(),
            nativeGames = listOf(detailTarget.row),
        )
        val session = resolveLibraryLaunchReturnDetailSession(
            target = returnTarget,
            currentSession = null,
            activeTarget = liveTarget,
        )

        assertEquals(detailTarget, session?.target)
        assertEquals(LibraryShelfScope, session?.originScope)
        assertEquals(detailTarget.sourceFocusKey, session?.originFocusKey)
    }

    @Test
    fun staleSessionCannotRestoreWhenTheLiveItemIsGone() {
        val game = game("ps1/removed")
        val detailTarget = LibraryDetailTarget.Emulated(game)
        val staleSession = com.gruning.mimir.rewrite.feature.library.LibraryDetailSession(
            target = detailTarget,
            originScope = GamelistScope,
            originFocusKey = detailTarget.sourceFocusKey,
        )

        val session = resolveLibraryLaunchReturnDetailSession(
            target = launchReturnTarget(game, restoreLaunchCard = true),
            currentSession = staleSession,
            activeTarget = null,
        )

        assertNull(session)
    }

    @Test
    fun aReturnClosesTheCardItCannotRestoreAndAnyCardATargetDoesNotAskFor() {
        // Observed on Odin: returning from Eden after it crashed on exit left a blurred background. A card left open from an earlier
        // session keeps the scene glass on, so a target that does not ask for the card must not land under one.
        val game = game("ps1/return-card")
        val asksForCard = launchReturnTarget(game, restoreLaunchCard = true)
        val noCard = launchReturnTarget(game, restoreLaunchCard = false)
        val restored = com.gruning.mimir.rewrite.feature.library.LibraryDetailSession(
            target = LibraryDetailTarget.Emulated(game),
            originScope = GamelistScope,
            originFocusKey = LibraryDetailTarget.Emulated(game).sourceFocusKey,
        )
        assertFalse("the card is restored: it stays", libraryLaunchReturnClosesOpenCard(asksForCard, restored, cardOpen = true))
        assertTrue("the card cannot be restored (the game is gone): closed", libraryLaunchReturnClosesOpenCard(asksForCard, null, cardOpen = false))
        assertTrue("no card asked for, one left open: closed", libraryLaunchReturnClosesOpenCard(noCard, null, cardOpen = true))
        assertFalse("no card asked for and none open: nothing to close", libraryLaunchReturnClosesOpenCard(noCard, null, cardOpen = false))
        assertTrue(
            "a shelf target that does not ask for the card closes a leftover one",
            libraryLaunchReturnClosesOpenCard(LibraryLaunchReturnTarget.shelf(LibraryShelfMode.EMULATION, "ps1"), null, cardOpen = true),
        )
    }

    private fun launchReturnTarget(
        game: GameRow,
        restoreLaunchCard: Boolean,
    ) = LibraryLaunchReturnTarget.gamelistRom(
        openPlatformId = game.platformId,
        romPlatformId = game.platformId,
        gameId = game.gameId,
        shelfMode = LibraryShelfMode.EMULATION,
        favoriteSegment = FavoriteSegment.GAMES,
        searchQuery = "",
        favoritesOnly = false,
        restoreLaunchCard = restoreLaunchCard,
    )

    private fun semanticSession(
        target: LibraryLaunchReturnTarget,
        phase: MimirSessionPhase,
    ) = MimirGameSession(
        operation = MimirGameSessionOperation("library:test-${phase.name.lowercase()}"),
        sequence = 1L,
        gameId = requireNotNull(target.gameId),
        platformId = requireNotNull(target.romPlatformId),
        returnAnchor = MimirGameReturnAnchor.Library(target),
        startedAtEpochMs = 1L,
        dualScreen = false,
        phase = phase,
    )

    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        title = "Chrono Cross",
        platformId = PlatformId.PS1,
        memberCount = 1,
        duplicateKind = null,
    )
}
