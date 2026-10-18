package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWithCandidateResolutionTest {
    private val gameId = GameId("game:ps1:unwired")
    private val platformId = PlatformId.PS1

    @Test
    fun installedButUnwiredCandidateIsVisibleAndNonCommittable() = runBlocking {
        val candidate = EmulatorProfile(
            id = EmulatorId("com.example.unwired"),
            packageName = "com.example.unwired",
            displayName = "Unwired Emulator",
            installed = true,
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            locatorForm = LaunchLocatorForm.FILE_PATH,
            supportedPlatforms = setOf(platformId),
        )
        val controller = controller(candidate)

        val option = controller.openWithOptions(gameId, platformId).candidates.single()
        assertTrue(option.installed)
        assertFalse(option.dispatchSupported)
        assertFalse(option.selectable)
        assertFalse(option.isAutoPick)
        assertTrue(controller.resolveReadiness(gameId, platformId) is LaunchReadiness.NeedsEmulator)
    }

    @Test
    fun noContentShellDoesNotRequireARomLocator() = runBlocking {
        val candidate = EmulatorSeedRegistry.candidatesFor(PlatformId("ps3")) { it == "net.rpcsx" }
            .first { it.packageName == "net.rpcsx" }
        var locatorReads = 0
        val controller = controller(candidate) { locatorReads += 1; null }

        val readiness = controller.resolveReadiness(gameId, PlatformId("ps3"))

        assertTrue(readiness is LaunchReadiness.Ready)
        assertTrue((readiness as LaunchReadiness.Ready).target.locator.isEmpty())
        assertTrue(readiness.target.locatorForm == LaunchLocatorForm.NONE)
        assertTrue(locatorReads == 0)
    }

    @Test
    fun openWithRetainsEveryGbaCoreAsAnIndependentCandidate() = runBlocking {
        val gba = PlatformId.GBA
        val candidates = EmulatorSeedRegistry.candidatesFor(gba) {
            it == "com.retroarch.aarch64"
        }.filter { it.isRetroArchCore }
        val controller = controller(candidates)

        val options = controller.openWithOptions(GameId("game:gba:core-picker"), gba)

        assertEquals(
            listOf("mgba", "gpsp", "vba_next", "vbam", "mednafen_gba", "meteor", "skyemu"),
            options.candidates.map { it.emulatorId.value.substringAfterLast('.') },
        )
        assertEquals(7, options.candidates.map { it.emulatorId }.distinct().size)
        assertTrue(options.candidates.first().isAutoPick)
        assertTrue(options.candidates.all { it.selectable && it.requiresManualCoreProof })
    }


    @Test
    fun promotedRetroArchOpenShellIsVisibleButNotDispatchable() = runBlocking {
        val seed = EmulatorSeedRegistry.candidatesFor(PlatformId.GBA) {
            it == "com.retroarch.aarch64"
        }.first { it.retroArchCore == "mgba" }
        val promoted = seed.copy(
            capability = EmulatorCapability.OPEN_ONLY,
            locatorForm = LaunchLocatorForm.NONE,
            openShellInferred = false,
            installed = true,
            componentAvailable = true,
        )
        val controller = controller(promoted)

        val option = controller.openWithOptions(GameId("game:gba:open-only-escape"), PlatformId.GBA).candidates.single()
        assertTrue(option.installed)
        assertFalse(option.dispatchSupported)
        assertFalse(option.selectable)
    }

    @Test
    fun inferredOpenShellIsVisibleButNotAGameRoute() = runBlocking {
        val dolphin = EmulatorSeedRegistry.candidatesFor(PlatformId.GAMECUBE) {
            it == "org.dolphinemu.mmjr"
        }.first { it.packageName == "org.dolphinemu.mmjr" }
        val checked = dolphin.withComponentPreflight(
            platformId = PlatformId.GAMECUBE,
            activityAvailable = { _, _ -> error("payload component must not be probed") },
            packageLauncherAvailable = { it == "org.dolphinemu.mmjr" },
        )
        val controller = controller(checked)

        val option = controller.openWithOptions(gameId, PlatformId.GAMECUBE).candidates.single()
        assertTrue(option.installed)
        assertFalse(option.dispatchSupported)
        assertFalse(option.selectable)
        assertTrue(controller.resolveReadiness(gameId, PlatformId.GAMECUBE) is LaunchReadiness.NeedsEmulator)
    }

    private fun controller(
        candidate: EmulatorProfile,
        locator: suspend () -> LaunchLocatorResult? = {
            LaunchLocatorResult("/storage/emulated/0/ROMs/sample.iso", LaunchLocatorForm.FILE_PATH)
        },
    ) = controller(listOf(candidate), locator)

    private fun controller(
        candidates: List<EmulatorProfile>,
        locator: suspend () -> LaunchLocatorResult? = {
            LaunchLocatorResult("/storage/emulated/0/ROMs/sample.iso", LaunchLocatorForm.FILE_PATH)
        },
    ) = LaunchController(
        emulatorProvider = InstalledEmulatorProvider { candidates },
        locatorResolver = { _, _, _, _ -> locator() },
        overrideResolver = { null },
        overrideSetter = { _, _ -> Unit },
        overrideClearer = { Unit },
        biosResolver = { _, _, _ -> emptyList() },
        proofRecorder = { Unit },
        dispatcher = LaunchDispatcher { LaunchOutcome.IntentSent },
        ioDispatcher = Dispatchers.Unconfined,
    )
}
