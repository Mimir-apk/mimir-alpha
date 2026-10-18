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
import org.junit.Test

/**
 * `openWithOptions` marks the emulator the launch would start ([OpenWithCandidate.isLaunchPick]), so lines
 * that say what a game opens with can name it. The rule is the launch's own: the stored override when usable, else Auto
 * directly (never the platform default), and with no override the platform default, else Auto.
 */
class OpenWithLaunchPickTest {
    private val gba = PlatformId.GBA
    private val gameId = GameId("game:gba:launch-pick")

    /** The seven RetroArch GBA cores, all installed and dispatchable; Auto's first pick is mGBA. */
    private val cores: List<EmulatorProfile> = EmulatorSeedRegistry.candidatesFor(gba) { it == "com.retroarch.aarch64" }
        .filter { it.isRetroArchCore }

    private fun core(name: String): EmulatorId = cores.first { it.retroArchCore == name }.id
    private val autoFirst get() = core("mgba")
    private val platformDefault get() = core("gpsp")
    private val override get() = core("vbam")
    private val unlisted = EmulatorId("gba.retroarch.aarch64.removed")

    private fun changed(name: String, change: (EmulatorProfile) -> EmulatorProfile) =
        cores.map { if (it.retroArchCore == name) change(it) else it }

    private fun controller(
        candidates: List<EmulatorProfile> = cores,
        storedOverride: EmulatorId? = null,
        defaultForPlatform: EmulatorId? = null,
    ) = LaunchController(
        emulatorProvider = InstalledEmulatorProvider { candidates },
        locatorResolver = { _, _, _, _ ->
            LaunchLocatorResult("/storage/emulated/0/ROMs/sample.gba", LaunchLocatorForm.FILE_PATH)
        },
        overrideResolver = { storedOverride },
        overrideSetter = { _, _ -> Unit },
        overrideClearer = { Unit },
        biosResolver = { _, _, _ -> emptyList() },
        proofRecorder = { Unit },
        dispatcher = LaunchDispatcher { LaunchOutcome.IntentSent },
        platformDefault = { defaultForPlatform },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun marked(controller: LaunchController): List<EmulatorId> = runBlocking {
        controller.openWithOptions(gameId, gba).candidates.filter { it.isLaunchPick }.map { it.emulatorId }
    }

    /** The emulator the launch itself resolves, through its own readiness path (Ready, or NeedsBios with a target). */
    private fun launched(controller: LaunchController): EmulatorId? = runBlocking {
        when (val readiness = controller.resolveReadiness(gameId, gba)) {
            is LaunchReadiness.Ready -> readiness.target.emulator.id
            is LaunchReadiness.NeedsBios -> readiness.target?.emulator?.id
            else -> null
        }
    }

    @Test
    fun `with no override the platform default is the launch pick`() {
        assertEquals(listOf(platformDefault), marked(controller(defaultForPlatform = platformDefault)))
    }

    @Test
    fun `with no override and no platform default Auto is the launch pick`() {
        assertEquals(listOf(autoFirst), marked(controller()))
    }

    @Test
    fun `a usable override is the launch pick`() {
        assertEquals(listOf(override), marked(controller(storedOverride = override, defaultForPlatform = platformDefault)))
    }

    @Test
    fun `an override that is not installed goes to Auto, not to the platform default`() {
        val controller = controller(
            candidates = changed("vbam") { it.copy(installed = false) },
            storedOverride = override,
            defaultForPlatform = platformDefault,
        )
        assertEquals(listOf(autoFirst), marked(controller))
    }

    @Test
    fun `an override that cannot be dispatched goes to Auto, not to the platform default`() {
        val controller = controller(
            candidates = changed("vbam") { it.copy(capability = EmulatorCapability.RECOGNIZE_ONLY) },
            storedOverride = override,
            defaultForPlatform = platformDefault,
        )
        assertEquals(listOf(autoFirst), marked(controller))
    }

    @Test
    fun `an override that is no longer listed goes to Auto, not to the platform default`() {
        assertEquals(listOf(autoFirst), marked(controller(storedOverride = unlisted, defaultForPlatform = platformDefault)))
    }

    @Test
    fun `nothing installed that can carry the game marks nothing`() {
        val controller = controller(candidates = cores.map { it.copy(installed = false) }, storedOverride = override)
        assertEquals(emptyList<EmulatorId>(), marked(controller))
    }

    @Test
    fun `the marked emulator is the one the launch resolves, in every case`() {
        val cases = listOf(
            "no override, platform default" to controller(defaultForPlatform = platformDefault),
            "no override, no platform default" to controller(),
            "usable override" to controller(storedOverride = override, defaultForPlatform = platformDefault),
            "uninstalled override" to controller(changed("vbam") { it.copy(installed = false) }, override, platformDefault),
            "undispatchable override" to controller(
                changed("vbam") { it.copy(capability = EmulatorCapability.RECOGNIZE_ONLY) },
                override,
                platformDefault,
            ),
            "unlisted override" to controller(storedOverride = unlisted, defaultForPlatform = platformDefault),
        )
        cases.forEach { (name, controller) ->
            assertEquals(name, listOfNotNull(launched(controller)), marked(controller))
        }
    }
}
