package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchProfile
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.storage.scan.ExternalStorageDocumentPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Competitor-tracker rows 1, 7-8, 11-13 and 26 on the launch-routing side. Pure JVM: no Intent is built
 * here (the Intent-level pins live in the app module's TrackerLaunchIntentTest).
 *
 * Nothing in this file pins a hard-coded user id or a present RetroArch core: Mimir cannot inspect another
 * app's private core directory (see [RetroArchCoreCatalog]), so those two are written up as gaps rather
 * than asserted here.
 */
class TrackerLaunchRoutingTest {

    private val retroActivity = "com.retroarch.browser.retroactivity.RetroActivityFuture"
    private val retroVariants = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")

    private fun gbaTarget(pkg: String, core: String = "mgba"): LaunchTarget = LaunchTarget(
        gameId = GameId("game:gba:sample"),
        platformId = PlatformId.GBA,
        emulator = EmulatorProfile(
            id = EmulatorId.fromNativeLauncherKey("gba.retroarch.$pkg.$core"),
            packageName = pkg,
            displayName = "RetroArch / $core",
            retroArchCore = core,
            installed = true,
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            locatorForm = LaunchLocatorForm.FILE_PATH,
            supportedPlatforms = setOf(PlatformId.GBA),
        ),
        locator = "/storage/6A7F-FCFE/ROMs/GBA/Sample.gba",
        locatorForm = LaunchLocatorForm.FILE_PATH,
        title = "Sample",
    )

    // ---- rows 12-13: the three RetroArch builds -----------------------------------------------------

    @Test
    fun everyRetroArchBuildLaunchesItsOwnPackageThroughTheSameActivityClassWithItsOwnPaths() {
        for (pkg in retroVariants) {
            val profile = requireNotNull(ShippingLaunchProfiles.profileFor(gbaTarget(pkg))) { pkg }

            assertEquals(pkg, profile.packageName)
            assertEquals(
                "the class name is the same in all three builds; only the package differs ($pkg)",
                retroActivity,
                profile.activityName,
            )
            assertEquals(LaunchPathStrategy.ABSOLUTE_PATH_EXTRA, profile.pathStrategy)
            assertEquals("ROM", profile.pathExtraKey)

            val core = requireNotNull(profile.stringExtras["LIBRETRO"])
            val config = requireNotNull(profile.stringExtras["CONFIGFILE"])
            assertTrue("$pkg core path must name its own build: $core", "/$pkg/" in core)
            assertTrue(core.endsWith("/mgba_libretro_android.so"))
            assertTrue("$pkg config must name its own build: $config", "/$pkg/" in config)
            assertTrue(config.endsWith("/retroarch.cfg"))
            if (pkg != "com.retroarch.aarch64") {
                assertFalse(
                    "a rebased path must not keep naming the aarch64 build ($pkg)",
                    "com.retroarch.aarch64" in core || "com.retroarch.aarch64" in config,
                )
            }
        }
    }

    @Test
    fun theComponentPreflightProbesTheInstalledBuildsOwnActivityForEveryVariant() {
        for (pkg in retroVariants) {
            val candidate = gbaTarget(pkg).emulator
            val probed = mutableListOf<Pair<String, String>>()

            val checked = candidate.withComponentPreflight(PlatformId.GBA) { probedPackage, activity ->
                probed += probedPackage to activity
                true
            }

            assertEquals(listOf(pkg to retroActivity), probed)
            assertEquals(true, checked.componentAvailable)
            assertEquals(retroActivity, ShippingLaunchProfiles.targetActivityFor(PlatformId.GBA, candidate))
            assertTrue(ShippingLaunchProfiles.isDispatchSupported(PlatformId.GBA, pkg))
        }
        assertFalse(ShippingLaunchProfiles.isDispatchSupported(PlatformId.GBA, "com.retroarch.fake"))
        assertNull(ShippingLaunchProfiles.profileFor(gbaTarget("com.retroarch.fake")))
    }

    @Test
    fun withSeveralRetroArchBuildsInstalledTheFixedOrderPicksOneAndNeverMixesBuilds() {
        fun servingPackages(installed: Set<String>): Set<String> =
            EmulatorSeedRegistry.candidatesFor(PlatformId.GBA) { it in installed }
                .filter { it.isRetroArchCore }
                .onEach { assertTrue("a RetroArch row is only installed when a build is", it.installed) }
                .map { it.packageName }
                .toSet()

        assertEquals(setOf("com.retroarch.aarch64"), servingPackages(retroVariants.toSet()))
        assertEquals(
            setOf("com.retroarch"),
            servingPackages(setOf("com.retroarch", "com.retroarch.ra32")),
        )
        assertEquals(setOf("com.retroarch.ra32"), servingPackages(setOf("com.retroarch.ra32")))
    }

    // ---- row 7: an update renames the launch activity -------------------------------------------------

    @Test
    fun aRenamedActivityMakesTheCandidateNonSelectableInsteadOfLaunchingIntoActivityNotFound() {
        val platforms = listOf(
            PlatformId.PS1,
            PlatformId.PSP,
            PlatformId.GAMECUBE,
            PlatformId.PS2,
            PlatformId.DREAMCAST,
            PlatformId.SWITCH,
            PlatformId.N64,
        )
        var checkedCandidates = 0
        for (platform in platforms) {
            val standalone = EmulatorSeedRegistry.candidatesFor(platform) { true }
                .filter { !it.isRetroArchCore && it.capability == EmulatorCapability.DIRECT_PAYLOAD }
                .filter { ShippingLaunchProfiles.targetActivityFor(platform, it) != null }

            for (candidate in standalone) {
                val expected = requireNotNull(ShippingLaunchProfiles.targetActivityFor(platform, candidate))

                val renamed = candidate.withComponentPreflight(platform) { _, _ -> false }
                assertEquals("${candidate.packageName} on ${platform.value}", false, renamed.componentAvailable)
                assertTrue("a renamed activity keeps the package recognised", renamed.installed)
                assertFalse(
                    "${candidate.packageName} must not be offered to Play once its activity is gone",
                    ShippingLaunchProfiles.isCandidateDispatchSupported(platform, renamed),
                )

                val present = candidate.withComponentPreflight(platform) { pkg, activity ->
                    pkg == candidate.packageName && activity == expected
                }
                assertEquals(true, present.componentAvailable)
                checkedCandidates += 1
            }
        }
        assertTrue("the matrix must actually cover standalone emulators ($checkedCandidates)", checkedCandidates >= 5)
    }

    @Test
    fun aRenamedActivityReachesThePlayerAsNeedsEmulatorNotAsASilentDeadLaunch() {
        val duck = EmulatorSeedRegistry.candidatesFor(PlatformId.PS1) { it == "com.github.stenzek.duckstation" }
            .first { it.packageName == "com.github.stenzek.duckstation" }
        val locator = LaunchLocatorResult("/storage/6A7F-FCFE/ROMs/PSX/Game.m3u", LaunchLocatorForm.FILE_PATH)
        val profile = LaunchProfile(gameId = GameId("game:ps1:sample"), platformId = PlatformId.PS1)

        val drifted = duck.withComponentPreflight(PlatformId.PS1) { _, _ -> false }
        assertTrue(engineFor(drifted, locator).resolve(profile) is LaunchReadiness.NeedsEmulator)

        val healthy = duck.withComponentPreflight(PlatformId.PS1) { _, activity ->
            activity == "com.github.stenzek.duckstation.EmulationActivity"
        }
        val ready = engineFor(healthy, locator).resolve(profile)
        assertTrue(ready is LaunchReadiness.Ready)
        val target = (ready as LaunchReadiness.Ready).target
        assertEquals("the playlist path reaches dispatch untouched", locator.locator, target.locator)
        assertEquals(LaunchLocatorForm.FILE_PATH, target.locatorForm)
    }

    // ---- row 8: package-id variants of one emulator ----------------------------------------------------

    @Test
    fun siblingBuildsOfOneEmulatorAreEachSeededAndDispatchable() {
        val families = mapOf(
            PlatformId.PSP to listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"),
            PlatformId.PS2 to listOf("xyz.aethersx2.android", "xyz.aethersx2.cturnip"),
            PlatformId.N64 to listOf(
                "org.mupen64plusae.v3.fzurita",
                "org.mupen64plusae.v3.fzurita.pro",
                "org.mupen64plusae.v3.alpha",
            ),
        )
        for ((platform, packages) in families) {
            for (pkg in packages) {
                assertTrue("$pkg is a known seed", pkg in EmulatorSeedRegistry.allSeedPackages())
                assertTrue(
                    "$pkg serves ${platform.value}",
                    EmulatorSeedRegistry.seedsFor(platform).any { it.packageName == pkg },
                )
                assertTrue(
                    "$pkg has a dispatch profile on ${platform.value}",
                    ShippingLaunchProfiles.isDispatchSupported(platform, pkg),
                )
            }
        }
    }

    // ---- rows 1 and 26 support: SAF documents on an SD card become real file paths ---------------------

    @Test
    fun aSafDocumentOnTheSdCardBecomesTheRealStoragePathFileEmulatorsNeed() {
        val playlist =
            "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs/" +
                "document/6A7F-FCFE%3AROMs%2FPSX%2FGame.m3u"
        assertEquals("/storage/6A7F-FCFE/ROMs/PSX/Game.m3u", ExternalStorageDocumentPath.pathOf(playlist))

        val downloads = "content://com.android.providers.downloads.documents/document/5"
        assertNull(
            "a provider with no filesystem path yields no locator, so Play reports a missing file",
            ExternalStorageDocumentPath.pathOf(downloads),
        )
        assertNotNull(ExternalStorageDocumentPath.pathOf(playlist))
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private fun engineFor(candidate: EmulatorProfile, locator: LaunchLocatorResult?): LaunchReadinessEngine =
        LaunchReadinessEngine(
            emulators = InstalledEmulatorProvider { listOf(candidate) },
            overrides = OpenWithOverrideLookup { null },
            bios = BiosPresenceProvider { _, _ -> emptyList() },
            locators = object : LaunchLocatorProvider {
                override fun locatorFor(
                    gameId: GameId,
                    launchMemberEntryKey: EntryKey?,
                    preferredDisc: Int,
                    preferredForm: LaunchLocatorForm,
                ): LaunchLocatorResult? = locator
            },
        )
}
