package com.gruning.mimir.rewrite.core.launch

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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the Thor carries two Azahar-family builds, `io.github.lime3ds.android` (the older
 * package, sideloaded 2026-05-20) and `org.azahar_emu.azahar` (2126.0-vanilla). With no stored choice a 3DS game must
 * open in `org.azahar_emu.azahar`, a stored per-game or per-platform choice must still win, and the two builds must
 * read differently wherever they are listed. Everything here runs the REAL registry candidates through the real
 * [LaunchController] precedence (this-call override, then per-game override, then platform default, then Auto).
 */
class AzaharBuildPreferenceTest {
    private val threeDs = PlatformId.THREE_DS
    private val gameId = GameId("game:3ds:metroid-samus-returns")

    private val vanilla = "org.azahar_emu.azahar"
    private val lime3ds = "io.github.lime3ds.android"
    private val azaharPlus = "io.github.azaharplus.android"
    private val citra = "org.citra.citra_emu"

    /** The registry's 3DS candidates with exactly [installed] present; [preflight] adds the real provider's check. */
    private fun candidates(vararg installed: String, preflight: Boolean = false): List<EmulatorProfile> =
        EmulatorSeedRegistry.candidatesFor(threeDs) { it in installed }.map { candidate ->
            if (preflight) {
                candidate.withComponentPreflight(
                    platformId = threeDs,
                    packageLauncherAvailable = { true },
                    activityAvailable = { _, _ -> true },
                )
            } else {
                candidate
            }
        }

    private fun controller(
        candidates: List<EmulatorProfile>,
        storedOverride: EmulatorId? = null,
        defaultForPlatform: EmulatorId? = null,
    ) = LaunchController(
        emulatorProvider = InstalledEmulatorProvider { candidates },
        locatorResolver = { _, _, _, _ ->
            LaunchLocatorResult("content://com.android.externalstorage.documents/document/primary%3AROMs%2Fa.3ds", LaunchLocatorForm.CONTENT_URI)
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

    /** The package the launch itself would start, through its own readiness path. */
    private fun launchedPackage(controller: LaunchController): String? = runBlocking {
        when (val readiness = controller.resolveReadiness(gameId, threeDs)) {
            is LaunchReadiness.Ready -> readiness.target.emulator.packageName
            is LaunchReadiness.NeedsBios -> readiness.target?.emulator?.packageName
            else -> null
        }
    }

    /** The package the card's EMULATOR fact names (the Open With `isLaunchPick` row). */
    private fun cardPackage(controller: LaunchController): String? = runBlocking {
        controller.openWithOptions(gameId, threeDs).candidates.singleOrNull { it.isLaunchPick }?.packageName
    }

    private fun cardLabel(controller: LaunchController): String? = runBlocking {
        controller.openWithOptions(gameId, threeDs).candidates.singleOrNull { it.isLaunchPick }?.label
    }

    // -- the preference ---------------------------------------------------------------------

    @Test
    fun `both builds installed and no stored choice opens the official Azahar`() {
        val controller = controller(candidates(lime3ds, vanilla))

        assertEquals(vanilla, launchedPackage(controller))
        assertEquals("the card names the build the launch starts", vanilla, cardPackage(controller))
        val auto = runBlocking { controller.openWithOptions(gameId, threeDs) }.candidates.single { it.isAutoPick }
        assertEquals("the Recommended chip sits on the build Auto launches", vanilla, auto.packageName)
    }

    @Test
    fun `install order and the component preflight never move the preference`() {
        val controller = controller(candidates(vanilla, lime3ds, preflight = true))

        assertEquals(vanilla, launchedPackage(controller))
        assertEquals(vanilla, cardPackage(controller))
    }

    @Test
    fun `only the Lime3DS build installed opens it, under its own label`() {
        val controller = controller(candidates(lime3ds))

        assertEquals(lime3ds, launchedPackage(controller))
        assertEquals("Azahar (Lime3DS build)", cardLabel(controller))
    }

    @Test
    fun `only the official Azahar installed opens it under the plain name`() {
        val controller = controller(candidates(vanilla))

        assertEquals(vanilla, launchedPackage(controller))
        assertEquals("Azahar", cardLabel(controller))
    }

    // -- an explicit choice still wins -------------------------------------------------------

    @Test
    fun `a stored per-game choice of the Lime3DS build is kept when both are installed`() {
        val controller = controller(candidates(lime3ds, vanilla), storedOverride = EmulatorId(lime3ds))

        assertEquals(lime3ds, launchedPackage(controller))
        assertEquals(lime3ds, cardPackage(controller))
        val options = runBlocking { controller.openWithOptions(gameId, threeDs) }
        assertTrue(options.hasOverride)
        assertEquals(lime3ds, options.candidates.single { it.isCurrentOverride }.packageName)
        val ready = runBlocking { controller.resolveReadiness(gameId, threeDs) } as LaunchReadiness.Ready
        assertTrue("the target says an override was applied", ready.target.overrideApplied)
    }

    @Test
    fun `a stored per-platform choice of the Lime3DS build is kept when both are installed`() {
        val controller = controller(candidates(lime3ds, vanilla), defaultForPlatform = EmulatorId(lime3ds))

        assertEquals(lime3ds, launchedPackage(controller))
        assertEquals(lime3ds, cardPackage(controller))
    }

    @Test
    fun `a stored per-game choice beats a per-platform choice in either direction`() {
        val gameLime = controller(candidates(lime3ds, vanilla), EmulatorId(lime3ds), EmulatorId(vanilla))
        val gameVanilla = controller(candidates(lime3ds, vanilla), EmulatorId(vanilla), EmulatorId(lime3ds))

        assertEquals(lime3ds, launchedPackage(gameLime))
        assertEquals(vanilla, launchedPackage(gameVanilla))
    }

    @Test
    fun `a stored choice of a build that is no longer installed falls to the official Azahar`() {
        val controller = controller(candidates(vanilla), storedOverride = EmulatorId(lime3ds))

        assertEquals(vanilla, launchedPackage(controller))
    }

    // -- the two builds read differently ------------------------------------------------------

    @Test
    fun `with both builds listed their labels differ in Open With and in the registry`() {
        val options = runBlocking { controller(candidates(lime3ds, vanilla)).openWithOptions(gameId, threeDs) }
        val labels = options.candidates
            .filter { it.packageName == vanilla || it.packageName == lime3ds }
            .associate { it.packageName to it.label }

        assertEquals(setOf(vanilla, lime3ds), labels.keys)
        assertNotEquals(labels.getValue(vanilla), labels.getValue(lime3ds))
        assertEquals("Azahar", labels.getValue(vanilla))
        assertEquals("Azahar (Lime3DS build)", labels.getValue(lime3ds))
        assertEquals("Azahar", EmulatorSeedRegistry.displayNameFor(vanilla))
        assertEquals("Azahar (Lime3DS build)", EmulatorSeedRegistry.displayNameFor(lime3ds))
    }

    @Test
    fun `the 3DS recommendation for a bare platform still reads Azahar`() {
        assertEquals("Azahar", EmulatorSeedRegistry.recommendedDisplayNameFor(threeDs))
    }

    // -- AzaharPlus and the Citra entries behave as before --------------------------------------

    @Test
    fun `the other 3DS packages keep their relative order and names`() {
        val packages = EmulatorSeedRegistry.seedsFor(threeDs).map { it.packageName }

        assertEquals(
            listOf(
                vanilla,
                lime3ds,
                azaharPlus,
                citra,
                "org.citra.citra_emu.canary",
                "org.citra.emu",
                "io.github.mandarine3ds.mandarine",
                "com.panda3ds.pandroid",
            ),
            packages.filterNot { it.startsWith("com.retroarch") },
        )
        assertEquals("AzaharPlus", EmulatorSeedRegistry.displayNameFor(azaharPlus))
        assertEquals("Citra", EmulatorSeedRegistry.displayNameFor(citra))
        assertEquals("Citra Canary", EmulatorSeedRegistry.displayNameFor("org.citra.citra_emu.canary"))
        assertEquals("Citra MMJ", EmulatorSeedRegistry.displayNameFor("org.citra.emu"))
    }

    @Test
    fun `AzaharPlus stays recognition-only, never picked and never selectable`() {
        val plusOnly = controller(candidates(azaharPlus, preflight = true))
        val plusAndLime = controller(candidates(azaharPlus, lime3ds, preflight = true))
        val plusAndAll = controller(candidates(azaharPlus, lime3ds, vanilla, citra, preflight = true))

        assertNull("recognition alone is not an Auto target", cardPackage(plusOnly))
        assertNull(launchedPackage(plusOnly))
        assertEquals(lime3ds, cardPackage(plusAndLime))
        assertEquals(vanilla, cardPackage(plusAndAll))
        val plusRow = runBlocking { plusOnly.openWithOptions(gameId, threeDs) }
            .candidates.single { it.packageName == azaharPlus }
        assertTrue(plusRow.installed)
        assertFalse("AzaharPlus is not selectable in Open With", plusRow.selectable)
        assertFalse(plusRow.isAutoPick)
    }

    @Test
    fun `Citra still outranks AzaharPlus and still yields to either Azahar build`() {
        assertEquals(citra, cardPackage(controller(candidates(citra, azaharPlus, preflight = true))))
        assertEquals(lime3ds, cardPackage(controller(candidates(citra, lime3ds, preflight = true))))
        assertEquals(vanilla, cardPackage(controller(candidates(citra, vanilla, preflight = true))))
        assertEquals(citra, cardPackage(controller(candidates(citra, preflight = true))))
        assertFalse(
            "no Citra build is renamed by this change",
            EmulatorSeedRegistry.seedsFor(threeDs).filter { it.familyId == "citra" }
                .any { it.displayName.contains("Azahar") },
        )
    }
}
