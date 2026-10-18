package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.launch.LaunchResult
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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sentence for a launch the RetroArch core check refused, and that it reaches the player from the typed result:
 * the core's name (and, when the complete listing holds one, another core of the platform) rides in the outcome's
 * metadata and the sentence names it. The action comes first because the Home launch card's status band is two lines.
 */
class RetroArchCoreMissingMessageTest {
    private val platformLabel: (PlatformId) -> String = { "Game Boy Advance" }

    @Test
    fun `the action comes first and names the core`() {
        val state = GameLaunchUiState.from(refusal("mGBA"))

        assertEquals(
            GameLaunchUiState.DispatchFailed(LaunchDispatchFailure.RETROARCH_CORE_MISSING, "mGBA", null),
            state,
        )
        assertEquals(
            "RetroArch needs the mGBA core. Get it in RetroArch: Online Updater › Core Downloader.",
            gameLaunchStatusMessage(state, platformLabel),
        )
    }

    @Test
    fun `when RetroArch holds another core of the platform the sentence says to pick it`() {
        val state = GameLaunchUiState.from(refusal("Mupen64Plus-Next GLES2", installed = "Mupen64Plus-Next GLES3"))

        assertEquals(
            GameLaunchUiState.DispatchFailed(
                LaunchDispatchFailure.RETROARCH_CORE_MISSING,
                "Mupen64Plus-Next GLES2",
                "Mupen64Plus-Next GLES3",
            ),
            state,
        )
        assertEquals(
            "RetroArch has Mupen64Plus-Next GLES3 but not Mupen64Plus-Next GLES2. Pick it with Open with.",
            gameLaunchStatusMessage(state, platformLabel),
        )
    }

    @Test
    fun `the two sentences as the N64 label renders them`() {
        assertEquals(
            "RetroArch needs the Mupen64Plus-Next GLES3 core. Get it in RetroArch: Online Updater › Core Downloader.",
            gameLaunchStatusMessage(GameLaunchUiState.from(refusal("Mupen64Plus-Next GLES3")), platformLabel),
        )
        assertEquals(
            "RetroArch has Mupen64Plus-Next GLES2 but not Mupen64Plus-Next GLES3. Pick it with Open with.",
            gameLaunchStatusMessage(
                GameLaunchUiState.from(refusal("Mupen64Plus-Next GLES3", installed = "Mupen64Plus-Next GLES2")),
                platformLabel,
            ),
        )
    }

    @Test
    fun `a refusal with no core name still gives a distinct, actionable sentence`() {
        assertEquals(
            "RetroArch needs another core. Get it in RetroArch: Online Updater › Core Downloader.",
            gameLaunchStatusMessage(
                GameLaunchUiState.DispatchFailed(LaunchDispatchFailure.RETROARCH_CORE_MISSING),
                platformLabel,
            ),
        )
    }

    @Test
    fun `other dispatch failures carry no detail and keep their own sentences`() {
        val state = GameLaunchUiState.from(
            LaunchResult(
                readiness = LaunchReadiness.Ready(target()),
                outcome = LaunchOutcome.Failed(LaunchDispatchFailure.ACTIVITY_NOT_STARTED),
            ),
        )
        assertEquals(GameLaunchUiState.DispatchFailed(LaunchDispatchFailure.ACTIVITY_NOT_STARTED, null, null), state)
        assertEquals("Android wouldn’t start the emulator.", gameLaunchStatusMessage(state, platformLabel))
    }

    private fun refusal(label: String, installed: String? = null) = LaunchResult(
        readiness = LaunchReadiness.Ready(target()),
        outcome = LaunchOutcome.Failed(
            reason = LaunchDispatchFailure.RETROARCH_CORE_MISSING,
            targetEmulatorId = EmulatorId("gba.retroarch.aarch64.mgba"),
            nativeMetadata = buildMap {
                put("coreFile", "mgba_libretro_android.so")
                put("coreLabel", label)
                installed?.let { put("installedCoreLabel", it) }
            },
        ),
    )

    private fun target() = LaunchTarget(
        gameId = GameId("game:gba:sample"),
        platformId = PlatformId.GBA,
        emulator = EmulatorProfile(
            id = EmulatorId("gba.retroarch.aarch64.mgba"),
            packageName = "com.retroarch.aarch64",
            displayName = "RetroArch 64 / mGBA",
            retroArchCore = "mgba",
            installed = true,
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            locatorForm = LaunchLocatorForm.FILE_PATH,
            supportedPlatforms = setOf(PlatformId.GBA),
        ),
        locator = "/storage/emulated/0/ROMs/GBA/sample.gba",
        locatorForm = LaunchLocatorForm.FILE_PATH,
        title = "Sample",
    )
}
