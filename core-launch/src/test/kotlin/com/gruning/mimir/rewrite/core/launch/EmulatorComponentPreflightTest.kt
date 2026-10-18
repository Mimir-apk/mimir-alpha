package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorComponentPreflightTest {

    @Test
    fun exactExportedComponentKeepsInstalledDirectCandidateSelectable() {
        val candidate = EmulatorSeedRegistry.candidatesFor(PlatformId.PS2) {
            it == "come.nanodata.armsx2"
        }.first { it.packageName == "come.nanodata.armsx2" }

        val checked = candidate.withComponentPreflight(PlatformId.PS2) { pkg, activity ->
            pkg == "come.nanodata.armsx2" && activity == "com.armsx2.MainActivity"
        }

        assertTrue(checked.installed)
        assertEquals(true, checked.componentAvailable)
        assertTrue(ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.PS2, checked))
    }

    /**
     * Dreamcast read "Not supported yet" on the Nova, whose Flycast v2.2 names its activity
     * reicast.
     */
    @Test
    fun flycastLaunchesThroughWhicheverOfItsTwoActivitiesTheInstalledBuildHas() {
        val seed = EmulatorSeedRegistry.candidatesFor(PlatformId.DREAMCAST) { it == "com.flycast.emulator" }
            .first { it.packageName == "com.flycast.emulator" }
        val probed = mutableListOf<String>()

        val nova = seed.withComponentPreflight(PlatformId.DREAMCAST) { pkg, activity ->
            probed += activity
            pkg == "com.flycast.emulator" && activity == "com.reicast.emulator.MainActivity"
        }

        assertEquals(listOf("com.flycast.emulator.MainActivity", "com.reicast.emulator.MainActivity"), probed)
        assertEquals(true, nova.componentAvailable)
        assertEquals("com.reicast.emulator.MainActivity", nova.installedActivityName)
        assertTrue(ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.DREAMCAST, nova))
        assertEquals(
            "com.reicast.emulator.MainActivity",
            ShippingLaunchProfiles.targetActivityFor(PlatformId.DREAMCAST, nova),
        )
        val dispatch = ShippingLaunchProfiles.profileFor(dreamcastTarget(nova))!!
        assertEquals("com.reicast.emulator.MainActivity", dispatch.activityName)
        assertEquals(
            "the same intent as the newer build: only the class drifted",
            LaunchPathStrategy.PLAIN_PATH_DATA,
            dispatch.pathStrategy,
        )

        val thor = seed.withComponentPreflight(PlatformId.DREAMCAST) { _, activity ->
            activity == "com.flycast.emulator.MainActivity"
        }
        assertEquals(true, thor.componentAvailable)
        assertNull("the profile's own activity needs no record", thor.installedActivityName)
        assertEquals(
            "com.flycast.emulator.MainActivity",
            ShippingLaunchProfiles.profileFor(dreamcastTarget(thor))!!.activityName,
        )

        val neither = seed.withComponentPreflight(PlatformId.DREAMCAST) { _, _ -> false }
        assertEquals(false, neither.componentAvailable)
        assertFalse(ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.DREAMCAST, neither))
    }

    @Test
    fun anAlternateActivityIsNeverTriedForAPackageThatHasNone() {
        val candidate = EmulatorSeedRegistry.candidatesFor(PlatformId.PS2) {
            it == "come.nanodata.armsx2"
        }.first { it.packageName == "come.nanodata.armsx2" }
        val probed = mutableListOf<String>()

        val checked = candidate.withComponentPreflight(PlatformId.PS2) { _, activity -> probed += activity; false }

        assertEquals(listOf("com.armsx2.MainActivity"), probed)
        assertEquals(false, checked.componentAvailable)
        assertNull(checked.installedActivityName)
        assertTrue(ShippingLaunchProfiles.alternateActivitiesFor(PlatformId.PS2, candidate).isEmpty())
    }

    private fun dreamcastTarget(emulator: com.gruning.mimir.rewrite.core.model.EmulatorProfile) =
        com.gruning.mimir.rewrite.core.model.LaunchTarget(
            gameId = com.gruning.mimir.rewrite.core.model.GameId("game:dreamcast:sample"),
            platformId = PlatformId.DREAMCAST,
            emulator = emulator,
            locator = "/storage/sample/game.chd",
            locatorForm = LaunchLocatorForm.FILE_PATH,
            title = "Sample",
        )

    @Test
    fun componentDriftDoesNotErasePackageRecognitionButBlocksSelection() {
        val candidate = EmulatorSeedRegistry.candidatesFor(PlatformId.SWITCH) {
            it == "dev.legacy.eden_emulator"
        }.first { it.packageName == "dev.legacy.eden_emulator" }

        val checked = candidate.withComponentPreflight(PlatformId.SWITCH) { _, _ -> false }

        assertTrue("package remains recognized as installed", checked.installed)
        assertEquals(false, checked.componentAvailable)
        assertFalse(ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.SWITCH, checked))
        assertNull(resolveInstalledEmulator(listOf(checked), checked.id, PlatformId.SWITCH))
    }

    @Test
    fun recognitionOnlyAndAbsentPackagesDoNotRunComponentProbe() {
        var probes = 0
        val dolphin = EmulatorSeedRegistry.candidatesFor(PlatformId.GAMECUBE) {
            it == "org.dolphinemu.mmjr"
        }.first { it.packageName == "org.dolphinemu.mmjr" }
        val absentArms = EmulatorSeedRegistry.candidatesFor(PlatformId.PS2) { false }
            .first { it.packageName == "come.nanodata.armsx2" }

        val recognized = dolphin.withComponentPreflight(PlatformId.GAMECUBE) { _, _ -> probes += 1; true }
        val absent = absentArms.withComponentPreflight(PlatformId.PS2) { _, _ -> probes += 1; true }

        assertEquals(0, probes)
        assertEquals(false, recognized.componentAvailable)
        assertNull(absent.componentAvailable)
    }

    @Test
    fun installedRecognitionRowWithRealLauncherBecomesOpenOnlyFallback() {
        val dolphin = EmulatorSeedRegistry.candidatesFor(PlatformId.GAMECUBE) {
            it == "org.dolphinemu.mmjr"
        }.first { it.packageName == "org.dolphinemu.mmjr" }

        val checked = dolphin.withComponentPreflight(
            platformId = PlatformId.GAMECUBE,
            activityAvailable = { _, _ -> error("payload component must not be probed") },
            packageLauncherAvailable = { it == "org.dolphinemu.mmjr" },
        )

        assertEquals(EmulatorCapability.OPEN_ONLY, checked.capability)
        assertEquals(LaunchLocatorForm.NONE, checked.locatorForm)
        assertEquals(true, checked.componentAvailable)
        assertTrue(checked.openShellInferred)
        assertFalse(
            "runtime launcher promotion is open-shell only, not a direct-game route",
            ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.GAMECUBE, checked),
        )
        assertNull(
            resolveInstalledEmulator(
                candidates = listOf(checked),
                preferredEmulatorId = null,
                platformId = PlatformId.GAMECUBE,
            ),
        )
    }
}
