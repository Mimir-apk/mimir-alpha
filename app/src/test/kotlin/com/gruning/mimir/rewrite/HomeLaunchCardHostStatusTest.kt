package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeLaunchCardHostStatusTest {
    private val gameId = GameId("group:ps1:resident-evil-2")
    private val platformLabel: (PlatformId) -> String = { "PlayStation" }

    @Test
    fun hostStatusLineSurfacesNeedsBiosInsteadOfStaticPlatformLine() {
        val status = homeLaunchCardLiveStatusLine(
            platformStatusLine = "DuckStation",
            launchState = GameLaunchUiState.NeedsBios(listOf("scph5501.bin"), presenceUnknown = false),
            gameId = gameId,
            platformLabelFor = platformLabel,
        )

        assertEquals(
            "This system needs scph5501.bin. Complete the emulator’s BIOS setup, then try again.",
            status,
        )
    }

    @Test
    fun differentialPreFixHostPassesStaticPlatformLineOnly() {
        val platformStatusLine = "DuckStation"
        val preFixStatusLine = platformStatusLine
        val postFixStatusLine = homeLaunchCardLiveStatusLine(
            platformStatusLine = platformStatusLine,
            launchState = GameLaunchUiState.NeedsBios(listOf("scph5501.bin"), presenceUnknown = false),
            gameId = gameId,
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
