package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeLaunchCardLaunchFeedbackTest {
    private val gameId = GameId("group:ps1:resident-evil-2")
    private val platformLabel: (PlatformId) -> String = { "PlayStation" }

    @Test
    fun homeLaunchCardSurfacesNeedsBiosInsteadOfStaticPlatformLine() {
        val status = resolveHomeLaunchCardStatusLine(
            platformStatusLine = "DuckStation",
            launchState = GameLaunchUiState.NeedsBios(listOf("scph5501.bin"), presenceUnknown = false),
            sessionGameId = gameId,
            targetGameId = gameId,
            platformLabelFor = platformLabel,
        )

        assertEquals(
            "This system needs scph5501.bin. Complete the emulator’s BIOS setup, then try again.",
            status,
        )
    }

    @Test
    fun homeLaunchCardKeepsPlatformLineWhenLaunchIdle() {
        assertEquals(
            "DuckStation",
            resolveHomeLaunchCardStatusLine(
                platformStatusLine = "DuckStation",
                launchState = GameLaunchUiState.Idle,
                sessionGameId = gameId,
                targetGameId = gameId,
                platformLabelFor = platformLabel,
            ),
        )
    }

    @Test
    fun homeLaunchCardIgnoresStaleLaunchStateForDifferentGame() {
        assertEquals(
            "DuckStation",
            resolveHomeLaunchCardStatusLine(
                platformStatusLine = "DuckStation",
                launchState = GameLaunchUiState.NeedsBios(listOf("scph5501.bin"), presenceUnknown = false),
                sessionGameId = GameId("group:ps1:other"),
                targetGameId = gameId,
                platformLabelFor = platformLabel,
            ),
        )
    }

    @Test
    fun differentialPreFixPlatformLineHidesNeedsBiosFailure() {
        val platformStatusLine = "DuckStation"
        val preFixStatusLine = platformStatusLine
        val postFixStatusLine = resolveHomeLaunchCardStatusLine(
            platformStatusLine = platformStatusLine,
            launchState = GameLaunchUiState.NeedsBios(listOf("scph5501.bin"), presenceUnknown = false),
            sessionGameId = gameId,
            targetGameId = gameId,
            platformLabelFor = platformLabel,
        )

        assertEquals("DuckStation", preFixStatusLine)
        assertNotEquals(preFixStatusLine, postFixStatusLine)
        assertEquals(
            "This system needs scph5501.bin. Complete the emulator’s BIOS setup, then try again.",
            postFixStatusLine,
        )
    }
}
