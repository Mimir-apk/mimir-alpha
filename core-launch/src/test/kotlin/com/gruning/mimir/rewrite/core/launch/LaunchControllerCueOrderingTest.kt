package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchControllerCueOrderingTest {
    private val platform = PlatformId.PS1
    private val game = GameId("game:ps1:cue-order")
    private val emulator = EmulatorProfile(
        id = EmulatorId("com.github.stenzek.duckstation"),
        packageName = "com.github.stenzek.duckstation",
        displayName = "DuckStation",
        installed = true,
        capability = EmulatorCapability.DIRECT_PAYLOAD,
        locatorForm = LaunchLocatorForm.FILE_PATH,
        supportedPlatforms = setOf(platform),
    )

    @Test
    fun successfulDispatchPlaysConfirmCueBeforeArmingGameAudio() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = controller(
            outcome = LaunchOutcome.IntentSent,
            confirmLaunchCue = { calls += "cue" },
            gameLaunchArmed = { calls += "armed" },
            calls = calls,
        )

        controller.launch(game, platform)

        assertEquals(listOf("dispatch", "cue", "armed"), calls)
    }

    @Test
    fun failedDispatchDoesNotPlayOrArm() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = controller(
            outcome = LaunchOutcome.Failed(
                reason = com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.ACTIVITY_NOT_STARTED,
            ),
            confirmLaunchCue = { calls += "cue" },
            gameLaunchArmed = { calls += "armed" },
            calls = calls,
        )

        controller.launch(game, platform)

        assertEquals(listOf("dispatch"), calls)
    }

    @Test
    fun successfulResultCarriesTheExactTargetPreflightContext() = runBlocking {
        val preflight = LaunchTargetPreflightContext(
            packageName = emulator.packageName,
            result = LaunchTargetReapResult.REQUEST_ACCEPTED,
        )
        val dispatcher = object : LaunchDispatcher {
            override fun dispatch(target: com.gruning.mimir.rewrite.core.model.LaunchTarget): LaunchOutcome =
                LaunchOutcome.IntentSent

            override fun preflightContext(): LaunchTargetPreflightContext = preflight
        }
        val controller = controller(
            outcome = LaunchOutcome.IntentSent,
            confirmLaunchCue = {},
            gameLaunchArmed = {},
            dispatcher = dispatcher,
        )

        assertEquals(preflight, controller.launch(game, platform).preflight)
    }

    private fun controller(
        outcome: LaunchOutcome,
        confirmLaunchCue: () -> Unit,
        gameLaunchArmed: () -> Unit,
        calls: MutableList<String> = mutableListOf(),
        dispatcher: LaunchDispatcher? = null,
    ) = LaunchController(
        emulatorProvider = InstalledEmulatorProvider { listOf(emulator) },
        locatorResolver = { _, _, _, _ ->
            LaunchLocatorResult("/storage/emulated/0/ROMs/PS1/sample.chd", LaunchLocatorForm.FILE_PATH)
        },
        overrideResolver = { null },
        overrideSetter = { _, _ -> Unit },
        overrideClearer = { Unit },
        biosResolver = { _, _, _ -> emptyList() },
        proofRecorder = { Unit },
        dispatcher = dispatcher ?: LaunchDispatcher {
            calls += "dispatch"
            outcome
        },
        ioDispatcher = Dispatchers.Unconfined,
        confirmLaunchCue = confirmLaunchCue,
        gameLaunchArmed = gameLaunchArmed,
    )
}
