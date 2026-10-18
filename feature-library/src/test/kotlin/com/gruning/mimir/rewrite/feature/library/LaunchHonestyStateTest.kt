package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cert 2026-10-08, F1 / F2 / F4 / F8 as the launch card sees them: the words, and the state that carries them.
 */
class LaunchHonestyStateTest {
    private val platformLabel: (PlatformId) -> String = { "PlayStation" }

    private fun target(game: GameRow, displayName: String = "DuckStation") = LaunchTarget(
        gameId = game.gameId,
        platformId = game.platformId,
        emulator = EmulatorProfile(
            id = EmulatorId("com.example.emu"),
            packageName = "com.example.emu",
            displayName = displayName,
            installed = true,
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            locatorForm = LaunchLocatorForm.FILE_PATH,
            supportedPlatforms = setOf(game.platformId),
        ),
        locator = "/storage/emulated/0/Roms/${game.gameId.value}.chd",
        locatorForm = LaunchLocatorForm.FILE_PATH,
        title = game.title,
    )

    private class Port(private val result: suspend (GameRow) -> LaunchResult) : LibraryLaunchPort {
        override suspend fun openWithOptions(game: GameRow) = OpenWithOptions(false, emptyList())
        override suspend fun setEmulatorOverride(game: GameRow, candidate: com.gruning.mimir.rewrite.core.launch.OpenWithCandidate) = Unit
        override suspend fun clearEmulatorOverride(game: GameRow) = Unit
        override suspend fun launch(game: GameRow, preferredDisc: Int, launchMemberEntryKey: EntryKey?) = result(game)
    }

    private suspend fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val started = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - started > timeoutMs) fail("Condition was not met within ${timeoutMs}ms")
            delay(10)
        }
    }

    private suspend fun launched(displayName: String = "DuckStation"): Triple<LibraryViewModel, GameRow, LibraryGameLaunchOperation> {
        val events = CopyOnWriteArrayList<LibraryGameSessionEvent>()
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = Port { game ->
                LaunchResult(LaunchReadiness.Ready(target(game, displayName)), LaunchOutcome.IntentSent)
            },
            gameSessionObserver = LibraryGameSessionObserver(events::add),
        ).apply { openPlatform(PlatformId.PS1) }
        val game = viewModel.games.first { list -> list.any { it.platformId == PlatformId.PS1 } }
            .first { it.platformId == PlatformId.PS1 }
        viewModel.launch(game)
        waitUntil { viewModel.launchState.value == GameLaunchUiState.IntentSent }
        val operation = events.filterIsInstance<LibraryGameSessionEvent.Preparing>().single().operation
        return Triple(viewModel, game, operation)
    }

    private fun cardLine(viewModel: LibraryViewModel, game: GameRow): String? {
        val snapshot = viewModel.launchUiSnapshot.value
        return resolveLibraryDetailLaunchRuntimeStatusLine(
            launchState = snapshot.state,
            launchOwnerGameId = snapshot.ownerGameId,
            targetGameId = game.gameId,
            platformLabelFor = platformLabel,
            emulatorLabel = snapshot.emulatorLabel,
        )
    }

    // ---- F2: Starting <Emulator>… until the emulator takes the screen --------------------------------------------

    @Test
    fun theCardSaysStartingUntilTheEmulatorYields() = runBlocking {
        val (viewModel, game, operation) = launched()
        assertEquals("Starting DuckStation…", cardLine(viewModel, game))

        assertTrue(viewModel.noteEmulatorYielded(operation))
        // The state is untouched (the NOW_PLAYING promotion and the launch guards read it for the whole run);
        // only the words that said it was still starting are gone.
        assertEquals(GameLaunchUiState.IntentSent, viewModel.launchState.value)
        assertEquals("Launching…", cardLine(viewModel, game))
        assertFalse("a second yield has nothing left to clear", viewModel.noteEmulatorYielded(operation))
    }

    @Test
    fun aStaleOperationCannotClearTheStartingWords() = runBlocking {
        val (viewModel, game, operation) = launched()
        assertFalse(viewModel.noteEmulatorYielded(LibraryGameLaunchOperation(operation.value + 1L)))
        assertEquals("Starting DuckStation…", cardLine(viewModel, game))
    }

    @Test
    fun theRetroArchCoreIsNotPartOfTheEmulatorsName() = runBlocking {
        val (viewModel, game, _) = launched(displayName = "RetroArch 64 / mGBA")
        assertEquals("Starting RetroArch 64…", cardLine(viewModel, game))
        assertEquals("RetroArch 64", emulatorSentenceLabel("RetroArch 64 / mGBA"))
        assertNull(emulatorSentenceLabel("  "))
        assertNull(emulatorSentenceLabel(null))
    }

    @Test
    fun aLaunchThatFailedToDispatchNeverClaimsToBeStarting() = runBlocking {
        val events = CopyOnWriteArrayList<LibraryGameSessionEvent>()
        val viewModel = LibraryViewModel(
            repository = FakeLibraryRepository(),
            launchPort = Port { game ->
                LaunchResult(
                    LaunchReadiness.Ready(target(game)),
                    LaunchOutcome.Failed(LaunchDispatchFailure.ACTIVITY_NOT_STARTED),
                )
            },
            gameSessionObserver = LibraryGameSessionObserver(events::add),
        ).apply { openPlatform(PlatformId.PS1) }
        val game = viewModel.games.first { list -> list.any { it.platformId == PlatformId.PS1 } }
            .first { it.platformId == PlatformId.PS1 }
        viewModel.launch(game)
        waitUntil { viewModel.launchState.value is GameLaunchUiState.DispatchFailed }
        assertNull(viewModel.launchUiSnapshot.value.emulatorLabel)
        assertEquals("Android wouldn’t start the emulator.", cardLine(viewModel, game))
    }

    // ---- F1: the emulator never took the window ---------------------------------------------------------------------

    @Test
    fun theWatchdogCleanupLeavesTheClosedUnexpectedlySentenceOnTheCard() = runBlocking {
        val (viewModel, game, operation) = launched()

        assertTrue(viewModel.closePendingLaunchUnexpectedly(operation))

        assertEquals(GameLaunchUiState.ClosedUnexpectedly("DuckStation"), viewModel.launchState.value)
        assertEquals(game.gameId, viewModel.launchUiSnapshot.value.ownerGameId)
        assertEquals(
            "DuckStation closed unexpectedly. The game may not be supported, or the emulator needs setup.",
            cardLine(viewModel, game),
        )
        assertNull("the return anchor is consumed, as cancelPendingLaunch consumes it", viewModel.pendingLaunchReturnTarget.value)
        assertFalse(
            "a second cleanup for the same operation has nothing left to do",
            viewModel.closePendingLaunchUnexpectedly(operation),
        )
    }

    @Test
    fun theClosedUnexpectedlyCardIsNotAnInFlightLaunch() = runBlocking {
        val (viewModel, _, operation) = launched()
        viewModel.closePendingLaunchUnexpectedly(operation)
        // `launch()` refuses a re-press only while the state is Launching; a card that says the emulator closed
        // must let the player press Play again.
        assertFalse(viewModel.launchState.value == GameLaunchUiState.Launching)
        assertFalse(viewModel.launchState.value == GameLaunchUiState.IntentSent)
    }

    @Test
    fun aStaleOperationOrAMovedOnCardIsLeftAlone() = runBlocking {
        val (viewModel, _, operation) = launched()
        assertFalse(viewModel.closePendingLaunchUnexpectedly(LibraryGameLaunchOperation(operation.value + 1L)))
        assertEquals(GameLaunchUiState.IntentSent, viewModel.launchState.value)

        // The player moved on before the watchdog ran: the card is Idle, and Idle stays Idle.
        viewModel.restoreOriginReturnTargetState(
            LibraryOriginReturnTarget(
                surface = LibraryOriginReturnTarget.Surface.SHELF,
                shelfMode = LibraryShelfMode.EMULATION,
                focusKey = null,
                gamelistViewMode = viewModel.gamelistViewMode.value,
            ),
        )
        assertEquals(GameLaunchUiState.Idle, viewModel.launchState.value)
        assertTrue(viewModel.closePendingLaunchUnexpectedly(operation))
        assertEquals(GameLaunchUiState.Idle, viewModel.launchState.value)
    }

    @Test
    fun withoutAKnownEmulatorTheSentenceStillReads() {
        assertEquals(
            "The emulator closed unexpectedly. The game may not be supported, or the emulator needs setup.",
            gameLaunchStatusMessage(GameLaunchUiState.ClosedUnexpectedly(null), platformLabel),
        )
    }

    // ---- F4 / F8: a refusal's own sentence reaches the card ---------------------------------------------------------------

    private fun failed(reason: LaunchDispatchFailure, detail: String?) = LaunchResult(
        readiness = LaunchReadiness.Ready(target(GameRowFixtures.any())),
        outcome = LaunchOutcome.Failed(
            reason = reason,
            nativeMetadata = if (detail == null) emptyMap() else mapOf(LaunchOutcome.Failed.DETAIL_KEY to detail),
        ),
    )

    @Test
    fun aRefusalWithItsOwnSentenceShowsThatSentence() {
        val missing = GameLaunchUiState.from(
            failed(LaunchDispatchFailure.PLAYLIST_MEMBER_MISSING, "Disc 2 is missing: Disc 2.chd"),
        )
        assertEquals(
            GameLaunchUiState.DispatchFailed(LaunchDispatchFailure.PLAYLIST_MEMBER_MISSING, "Disc 2 is missing: Disc 2.chd"),
            missing,
        )
        assertEquals("Disc 2 is missing: Disc 2.chd", gameLaunchStatusMessage(missing, platformLabel))

        val melon = "This melonDS version can only open games from its own ROM folder. " +
            "Update melonDS, or add this folder in melonDS."
        assertEquals(
            melon,
            gameLaunchStatusMessage(
                GameLaunchUiState.from(failed(LaunchDispatchFailure.EMULATOR_NEEDS_OWN_ROM_FOLDER, melon)),
                platformLabel,
            ),
        )
    }

    @Test
    fun aRefusalWithoutASentenceFallsBackToItsStockCopy() {
        assertEquals(
            "A file this game’s playlist needs is missing.",
            gameLaunchStatusMessage(
                GameLaunchUiState.from(failed(LaunchDispatchFailure.PLAYLIST_MEMBER_MISSING, null)),
                platformLabel,
            ),
        )
        // A blank detail is no detail.
        assertNull(LaunchOutcome.Failed(LaunchDispatchFailure.ROM_PATH_MISSING, nativeMetadata = mapOf("detail" to " ")).detail)
        // Every other failure keeps the exact line it always had.
        assertEquals(
            "The game file couldn’t be found.",
            gameLaunchStatusMessage(
                GameLaunchUiState.from(failed(LaunchDispatchFailure.ROM_PATH_MISSING, null)),
                platformLabel,
            ),
        )
    }

    private object GameRowFixtures {
        fun any(): GameRow = runBlocking {
            FakeLibraryRepository().let { repository ->
                repository.observeGames(PlatformId.PS1).first { it.isNotEmpty() }.first()
            }
        }
    }
}
