package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibraryDetailLaunchFeedbackTest {
    private val gameId = GameId("group:ps1:resident-evil-2")
    private val platformLabel: (PlatformId) -> String = { "PlayStation" }

    @Test
    fun detailRuntimeResolverSurfacesNeedsEmulatorForTheOwnedGame() {
        assertEquals(
            "No installed emulator for Dreamcast. Open Settings › Emulators to install one.",
            resolveLibraryDetailLaunchRuntimeStatusLine(
                launchState = GameLaunchUiState.NeedsEmulator(PlatformId("dreamcast")),
                launchOwnerGameId = gameId,
                targetGameId = gameId,
                platformLabelFor = { "Dreamcast" },
            ),
        )
    }

    @Test
    fun detailRuntimeResolverIsNullForStaleOrIdleLaunches() {
        assertEquals(
            null,
            resolveLibraryDetailLaunchRuntimeStatusLine(
                launchState = GameLaunchUiState.NeedsEmulator(PlatformId("dreamcast")),
                launchOwnerGameId = GameId("group:ps1:other"),
                targetGameId = gameId,
                platformLabelFor = { "Dreamcast" },
            ),
        )
        assertEquals(
            null,
            resolveLibraryDetailLaunchRuntimeStatusLine(
                launchState = GameLaunchUiState.Idle,
                launchOwnerGameId = gameId,
                targetGameId = gameId,
                platformLabelFor = { MimirCopy.StatusReady },
            ),
        )
    }

    @Test
    fun detailLaunchCardSurfacesMissingFileInsteadOfStaticPlatformLine() {
        val status = resolveLibraryDetailLaunchStatusLine(
            platformStatusLine = "DuckStation",
            launchState = GameLaunchUiState.MissingFile,
            detailGameId = gameId,
            targetGameId = gameId,
            platformLabelFor = platformLabel,
        )

        assertEquals(
            "Mimir can’t reach this game file. Restore it or reconnect its library folder, then try again.",
            status,
        )
    }

    @Test
    fun detailLaunchCardKeepsPlatformLineWhenLaunchIdle() {
        assertEquals(
            "DuckStation",
            resolveLibraryDetailLaunchStatusLine(
                platformStatusLine = "DuckStation",
                launchState = GameLaunchUiState.Idle,
                detailGameId = gameId,
                targetGameId = gameId,
                platformLabelFor = platformLabel,
            ),
        )
    }

    @Test
    fun detailLaunchCardIgnoresStaleLaunchStateForDifferentGame() {
        assertEquals(
            "DuckStation",
            resolveLibraryDetailLaunchStatusLine(
                platformStatusLine = "DuckStation",
                launchState = GameLaunchUiState.MissingFile,
                detailGameId = GameId("group:ps1:other"),
                targetGameId = gameId,
                platformLabelFor = platformLabel,
            ),
        )
    }

    @Test
    fun differentialPreFixPlatformLineHidesMissingFileFailure() {
        val platformStatusLine = "DuckStation"
        val preFixStatusLine = platformStatusLine
        val postFixStatusLine = resolveLibraryDetailLaunchStatusLine(
            platformStatusLine = platformStatusLine,
            launchState = GameLaunchUiState.MissingFile,
            detailGameId = gameId,
            targetGameId = gameId,
            platformLabelFor = platformLabel,
        )

        assertEquals("DuckStation", preFixStatusLine)
        assertNotEquals(preFixStatusLine, postFixStatusLine)
        assertEquals(
            "Mimir can’t reach this game file. Restore it or reconnect its library folder, then try again.",
            postFixStatusLine,
        )
    }
}
