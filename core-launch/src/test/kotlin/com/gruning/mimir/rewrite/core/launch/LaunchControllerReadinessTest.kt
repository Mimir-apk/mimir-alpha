package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.BiosPresence
import com.gruning.mimir.rewrite.core.model.BiosProfile
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchProof
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchControllerReadinessTest {

    private val gameId = GameId("group:ps1:readiness")
    private val platformId = PlatformId.PS1
    private val emulatorId = EmulatorId.fromNativeLauncherKey("com.github.stenzek.duckstation")

    @Test
    fun resolveReadinessReturnsNeedsEmulatorWithoutDispatchOrProof() = runBlocking {
        val effects = Effects()
        val controller = controller(
            candidates = listOf(emulator(installed = false)),
            effects = effects,
        )

        val readiness = controller.resolveReadiness(gameId, platformId)

        assertTrue(readiness is LaunchReadiness.NeedsEmulator)
        assertEquals(listOf(emulatorId), (readiness as LaunchReadiness.NeedsEmulator).candidates.map { it.id })
        assertEquals(0, effects.dispatches)
        assertTrue(effects.proofs.isEmpty())
        assertEquals(0, effects.dualScreenNotifications)
    }

    @Test
    fun resolveReadinessReturnsNeedsBiosWithoutDispatchOrProof() = runBlocking {
        val effects = Effects()
        val controller = controller(
            candidates = listOf(emulator(installed = true)),
            bios = listOf(
                BiosProfile(
                    platformId = platformId,
                    fileName = "scph5501.bin",
                    required = true,
                    presence = BiosPresence.MISSING,
                    emulatorId = emulatorId,
                    packageName = "com.github.stenzek.duckstation",
                ),
            ),
            effects = effects,
        )

        val readiness = controller.resolveReadiness(gameId, platformId)

        assertTrue(readiness is LaunchReadiness.NeedsBios)
        assertEquals("scph5501.bin", (readiness as LaunchReadiness.NeedsBios).missing.single().fileName)
        assertEquals(0, effects.dispatches)
        assertTrue(effects.proofs.isEmpty())
        assertEquals(0, effects.dualScreenNotifications)
    }

    @Test
    fun resolveReadinessReturnsReadyWithoutDispatchOrProof() = runBlocking {
        val effects = Effects()
        val controller = controller(
            candidates = listOf(emulator(installed = true)),
            effects = effects,
        )

        val readiness = controller.resolveReadiness(gameId, platformId)

        assertTrue(readiness is LaunchReadiness.Ready)
        assertEquals(0, effects.dispatches)
        assertTrue(effects.proofs.isEmpty())
        assertEquals(0, effects.dualScreenNotifications)
    }

    @Test
    fun launchDispatchesWhenBiosIsConfirmedMissingButStillReportsNeedsBios() = runBlocking {
        //  DuckStation's BIOS folder is
        // genuinely empty, so `readiness` must stay NeedsBios (the honest reason a UI shows via
        // gameLaunchStatusMessage), but the intent must still fire and a proof must still be recorded
        // — BOTH have to happen, neither may silently swallow the other.
        val effects = Effects()
        val controller = controller(
            candidates = listOf(emulator(installed = true)),
            bios = listOf(
                BiosProfile(
                    platformId = platformId,
                    fileName = "scph5501.bin",
                    required = true,
                    presence = BiosPresence.MISSING,
                    emulatorId = emulatorId,
                    packageName = "com.github.stenzek.duckstation",
                ),
            ),
            effects = effects,
        )

        val result = controller.launch(gameId, platformId)

        assertTrue("the honest BIOS reason must still be reported", result.readiness is LaunchReadiness.NeedsBios)
        assertTrue("the launch must actually be attempted", result.launched)
        assertEquals(1, effects.dispatches)
        assertEquals(1, effects.proofs.size)
        assertEquals(gameId, effects.proofs.single().gameId)
    }

    @Test
    fun launchStillDispatchesAndRecordsTheSharedReadyResolution() = runBlocking {
        val effects = Effects()
        val controller = controller(
            candidates = listOf(emulator(installed = true)),
            effects = effects,
        )

        val result = controller.launch(gameId, platformId)

        assertTrue(result.readiness is LaunchReadiness.Ready)
        assertTrue(result.launched)
        assertEquals(1, effects.dispatches)
        assertEquals(1, effects.proofs.size)
        assertEquals(gameId, effects.proofs.single().gameId)
    }

    private fun controller(
        candidates: List<EmulatorProfile>,
        bios: List<BiosProfile> = emptyList(),
        effects: Effects,
    ): LaunchController = LaunchController(
        emulatorProvider = InstalledEmulatorProvider { candidates },
        locatorResolver = { _, _, _, preferredForm ->
            LaunchLocatorResult("/storage/emulated/0/ROMs/PS1/game.chd", preferredForm)
        },
        overrideResolver = { null },
        overrideSetter = { _, _ -> Unit },
        overrideClearer = { _ -> Unit },
        biosResolver = { _, _, _ -> bios },
        proofRecorder = { effects.proofs += it },
        dispatcher = LaunchDispatcher {
            effects.dispatches += 1
            LaunchOutcome.IntentSent
        },
        platformDefault = { null },
        dualScreenLaunchNotifier = { effects.dualScreenNotifications += 1 },
        ioDispatcher = Dispatchers.Unconfined,
        clockMillis = { 123L },
        biosSeedBeforeRead = { _, _, _ -> null },
    )

    private fun emulator(installed: Boolean): EmulatorProfile = EmulatorProfile(
        id = emulatorId,
        packageName = "com.github.stenzek.duckstation",
        displayName = "DuckStation",
        installed = installed,
        capability = EmulatorCapability.DIRECT_PAYLOAD,
        locatorForm = LaunchLocatorForm.FILE_PATH,
        supportedPlatforms = setOf(platformId),
    )

    private class Effects {
        var dispatches: Int = 0
        var dualScreenNotifications: Int = 0
        val proofs: MutableList<LaunchProof> = mutableListOf()
    }
}
