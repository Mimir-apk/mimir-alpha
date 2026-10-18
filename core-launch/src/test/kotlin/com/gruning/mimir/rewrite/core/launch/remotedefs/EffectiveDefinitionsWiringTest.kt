package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import com.gruning.mimir.rewrite.core.launch.RetroArchCoreCatalog
import com.gruning.mimir.rewrite.core.launch.ShippingLaunchProfiles
import com.gruning.mimir.rewrite.core.launch.withComponentPreflight
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring: the places that used to read the compiled tables for "which activity" and "which core" now read the
 * effective definitions. Two promises, both pinned here against the REAL seed registry:
 *
 * 1. With no overlay the answers are bit-identical to the compiled derivation, for every seed and every platform.
 * 2. With an overlay the additions are appended, reach the component preflight and the launch profile, and never move
 *    an Auto default, widen dispatch support or extend force-stop authority.
 *
 * The reference below restates the derivation as it was before the wiring (from the seed tables alone), so it does not
 * share a line of code with the thing under test. Every test puts the process-wide view back to the compiled tables.
 */
class EffectiveDefinitionsWiringTest {
    private val real = ActiveDefinitions.bundled
    private val dreamcast = PlatformId("dreamcast")
    private val retroArchPackages = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")

    @After
    fun backToTheCompiledTables() {
        ActiveDefinitions.install(DefinitionsMerge.merge(real, null))
    }

    private fun install(vararg rows: String): EffectiveDefinitions {
        val built = Overlays.build(9, rows.toList())
        val parsed = OverlayParser.parse(built.bytes, real)
        assertTrue(parsed.toString().take(300), parsed is ParseResult.Accepted)
        val effective = DefinitionsMerge.merge(real, (parsed as ParseResult.Accepted).overlay)
        ActiveDefinitions.install(effective)
        return effective
    }

    private fun segment(packageName: String) = when (packageName) {
        "com.retroarch.aarch64" -> "aarch64"
        "com.retroarch.ra32" -> "ra32"
        else -> "universal"
    }

    /** `EmulatorSeedRegistry.candidatesFor` exactly as it was before the wiring: cores read from the seed maps. */
    private fun referenceCandidates(platformId: PlatformId, isInstalled: (String) -> Boolean): List<EmulatorProfile> =
        EmulatorSeedRegistry.seedsFor(platformId).flatMap { seed ->
            val retroArchCores = buildList {
                seed.retroArchCoreByPlatform[platformId]?.let(::add)
                addAll(seed.retroArchAdditionalCoresByPlatform[platformId].orEmpty())
            }.distinct()
            val installedRetroArchPackage = retroArchCores.takeIf { it.isNotEmpty() }?.let { retroArchPackages.firstOrNull(isInstalled) }
            val candidateCores: List<String?> = if (retroArchCores.isEmpty()) listOf(null) else retroArchCores
            candidateCores.map { core ->
                val persisted = core?.let { RetroArchCoreCatalog.persistedStem(platformId, it) }
                EmulatorProfile(
                    id = EmulatorId.fromNativeLauncherKey(
                        persisted?.let { "${platformId.value}.retroarch.${segment(seed.packageName)}.$it" } ?: seed.packageName,
                    ),
                    packageName = installedRetroArchPackage ?: seed.packageName,
                    displayName = seed.displayName + (core?.let { " / ${RetroArchCoreCatalog.displayName(platformId, it) ?: it}" } ?: ""),
                    retroArchCore = core,
                    installed = if (core != null) installedRetroArchPackage != null else isInstalled(seed.packageName),
                    capability = seed.capability,
                    locatorForm = seed.locatorForm,
                    supportedPlatforms = seed.platforms,
                    residentTaskWithoutIntentRefresh = seed.residentTaskWithoutIntentRefresh,
                )
            }
        }

    /** `ShippingLaunchProfiles.alternateActivitiesFor` exactly as it was before the wiring. */
    private fun referenceAlternates(platformId: PlatformId, emulator: EmulatorProfile): List<String> =
        if (!emulator.isRetroArchCore && platformId == PlatformId.DREAMCAST && emulator.packageName == "com.flycast.emulator") {
            listOf("com.reicast.emulator.MainActivity")
        } else {
            emptyList()
        }

    private val installedModes: List<Pair<String, (String) -> Boolean>> = listOf(
        "everything installed" to { _: String -> true },
        "nothing installed" to { _: String -> false },
        "only RetroArch universal" to { p: String -> p == "com.retroarch" },
        "only RetroArch 32-bit" to { p: String -> p == "com.retroarch.ra32" },
    )

    @Test
    fun with_no_overlay_the_candidate_lists_are_bit_identical_to_the_compiled_derivation_for_every_seed_and_platform() {
        assertEquals("the process view starts as the compiled tables", null, ActiveDefinitions.current.overlayOrdinal)
        var compared = 0
        for (platform in EmulatorSeedRegistry.allSeedPlatforms()) {
            for ((label, installed) in installedModes) {
                val expected = referenceCandidates(platform, installed)
                assertEquals("${platform.value} / $label", expected, EmulatorSeedRegistry.candidatesFor(platform, installed))
                compared += expected.size
            }
        }
        assertTrue("compared $compared candidates", compared > 200)
    }

    @Test
    fun with_no_overlay_alternates_and_launch_profiles_are_bit_identical_for_every_candidate() {
        var compared = 0
        for (platform in EmulatorSeedRegistry.allSeedPlatforms()) {
            for (candidate in referenceCandidates(platform) { true }) {
                assertEquals(
                    "${platform.value} ${candidate.id.value}",
                    referenceAlternates(platform, candidate),
                    ShippingLaunchProfiles.alternateActivitiesFor(platform, candidate),
                )
                assertEquals(
                    referenceAlternates(platform, candidate),
                    ShippingLaunchProfiles.bundledAlternateActivitiesFor(platform, candidate.packageName).takeUnless { candidate.isRetroArchCore }.orEmpty(),
                )
                compared++
            }
        }
        assertTrue("compared $compared candidates", compared > 80)
        // The one drift the compiled tables know: Flycast v2.2 answers only to the Reicast class.
        val flycast = EmulatorSeedRegistry.candidatesFor(dreamcast) { true }.first { it.packageName == "com.flycast.emulator" }
        assertEquals(listOf("com.reicast.emulator.MainActivity"), ShippingLaunchProfiles.alternateActivitiesFor(dreamcast, flycast))
        val target = LaunchTarget(GameId("g"), dreamcast, flycast.copy(installedActivityName = "com.reicast.emulator.MainActivity"), "/x/game.cdi", LaunchLocatorForm.FILE_PATH, "t")
        assertEquals("com.reicast.emulator.MainActivity", ShippingLaunchProfiles.profileFor(target)!!.activityName)
        assertEquals("com.flycast.emulator.MainActivity", ShippingLaunchProfiles.profileFor(target.copy(emulator = flycast))!!.activityName)
    }

    @Test
    fun the_bundled_view_is_built_from_the_compiled_tables_and_never_from_itself() {
        // The lazy bundled view and the merged view are separate objects: building one cannot read the other.
        assertEquals(EmulatorSeedRegistry.SEEDS.map { it.packageName }, real.seeds.map { it.packageName })
        assertEquals(EmulatorSeedRegistry.allDispatchPackages(), real.dispatchPackages)
        assertEquals(DefinitionsMerge.merge(real, null).stateKey, ActiveDefinitions.current.stateKey)
        assertEquals(0L, real.baselineOrdinal)
    }

    @Test
    fun an_overlay_core_is_appended_after_the_bundled_cores_for_that_platform_only_and_no_auto_default_moves() {
        val gb = PlatformId.GB
        val before = EmulatorSeedRegistry.allSeedPlatforms().associateWith { EmulatorSeedRegistry.candidatesFor(it) { true } }
        val autoBefore = before.mapValues { (platform, list) -> list.firstOrNull { ShippingLaunchProfiles.isCandidateDispatchSupported(platform, it) }?.id }

        install(Rows.core("com.retroarch.aarch64", gb.value, "zz_extra_core"))

        for ((platform, old) in before) {
            val now = EmulatorSeedRegistry.candidatesFor(platform) { true }
            if (platform != gb) {
                assertEquals("another platform is untouched: ${platform.value}", old, now)
            } else {
                val added = now.filter { it !in old }
                assertEquals(1, added.size)
                assertEquals("zz_extra_core", added.single().retroArchCore)
                assertEquals("gb.retroarch.aarch64.zz_extra_core", added.single().id.value)
                assertEquals("bundled candidates keep their order", old, now.filter { it in old })
                val lastBundledRetroArch = now.indexOfLast { it in old && it.isRetroArchCore }
                assertEquals("appended right after the bundled cores", lastBundledRetroArch + 1, now.indexOf(added.single()))
            }
            val auto = now.firstOrNull { ShippingLaunchProfiles.isCandidateDispatchSupported(platform, it) }?.id
            assertEquals("Auto default for ${platform.value}", autoBefore[platform], auto)
        }
    }

    @Test
    fun an_overlay_alternate_activity_reaches_the_component_preflight_and_the_launch_profile() {
        install(Rows.alt("com.flycast.emulator", "dreamcast", "com.reicast.emulator.NewMain"))
        val flycast = EmulatorSeedRegistry.candidatesFor(dreamcast) { true }.first { it.packageName == "com.flycast.emulator" }
        assertEquals(
            "the compiled alternate stays first, the overlay's is appended",
            listOf("com.reicast.emulator.MainActivity", "com.reicast.emulator.NewMain"),
            ShippingLaunchProfiles.alternateActivitiesFor(dreamcast, flycast),
        )

        // A build that only has the overlay's class: preflight finds it exported and enabled, and records it.
        val checked = flycast.withComponentPreflight(dreamcast, activityAvailable = { _, activity -> activity == "com.reicast.emulator.NewMain" })
        assertEquals(true, checked.componentAvailable)
        assertEquals("com.reicast.emulator.NewMain", checked.installedActivityName)

        // The launch profile is the proven one with only the class name swapped.
        val target = LaunchTarget(GameId("g"), dreamcast, checked, "/x/game.cdi", LaunchLocatorForm.FILE_PATH, "t")
        val plain = ShippingLaunchProfiles.profileFor(target.copy(emulator = flycast))!!
        val swapped = ShippingLaunchProfiles.profileFor(target)!!
        assertEquals(plain.copy(activityName = "com.reicast.emulator.NewMain"), swapped)

        // A class the preflight did not vouch for through the alternates never replaces the profile's own.
        val stray = ShippingLaunchProfiles.profileFor(target.copy(emulator = flycast.copy(installedActivityName = "evil.Other")))!!
        assertEquals(plain, stray)

        // And with no class on the device at all the package is unavailable, never launched into a missing class.
        assertEquals(false, flycast.withComponentPreflight(dreamcast, activityAvailable = { _, _ -> false }).componentAvailable)
    }

    @Test
    fun alternates_on_every_dispatchable_package_never_change_dispatch_support_auto_defaults_or_force_stop_authority() {
        val pairs = real.seeds
            .filter { it.capability != EmulatorCapability.RECOGNIZE_ONLY }
            .flatMap { seed -> seed.platforms.map { seed.packageName to it } }
            .filter { (_, platform) -> Regex("^[a-z0-9_]{1,64}$").matches(platform.value) }
            .take(RemoteDefinitionsLimits.MAX_ROWS)
        assertTrue("${pairs.size} pairs", pairs.size > 50)
        val platforms = EmulatorSeedRegistry.allSeedPlatforms()
        val before = platforms.associateWith { platform ->
            EmulatorSeedRegistry.candidatesFor(platform) { true }.map { c ->
                Triple(
                    c,
                    ShippingLaunchProfiles.isCandidateDispatchSupported(platform, c),
                    ShippingLaunchProfiles.targetActivityFor(platform, c) to ShippingLaunchProfiles.isPayloadCapable(platform, c),
                )
            }
        }
        val dispatchBefore = EmulatorSeedRegistry.allDispatchPackages()

        val effective = install(*pairs.map { (pkg, platform) -> Rows.alt(pkg, platform.value, "x.y.Alternate") }.toTypedArray())

        assertNotNull(effective.overlayOrdinal)
        for (platform in platforms) {
            val now = EmulatorSeedRegistry.candidatesFor(platform) { true }.map { c ->
                Triple(
                    c,
                    ShippingLaunchProfiles.isCandidateDispatchSupported(platform, c),
                    ShippingLaunchProfiles.targetActivityFor(platform, c) to ShippingLaunchProfiles.isPayloadCapable(platform, c),
                )
            }
            assertEquals("candidates, support, target activity and payload capability for ${platform.value}", before[platform], now)
        }
        assertEquals(dispatchBefore, EmulatorSeedRegistry.allDispatchPackages())
        assertEquals(dispatchBefore, ActiveDefinitions.current.dispatchPackages)
    }

    @Test
    fun an_overlay_that_tries_to_add_a_new_direct_launch_entry_is_rejected_whole_and_changes_nothing() {
        val good = Rows.core("com.retroarch.aarch64", "gb", "zz_extra_core")
        val unknownPackage = Rows.alt("com.example.newemu", "dreamcast", "com.example.newemu.MainActivity")
        val recognizeOnly = real.seeds.first { it.capability == EmulatorCapability.RECOGNIZE_ONLY }
        val recognizeOnlyAlt = Rows.alt(recognizeOnly.packageName, recognizeOnly.platforms.first().value, "x.y.Alternate")
        val servedElsewhere = Rows.alt("com.flycast.emulator", "gb", "x.y.Alternate")
        val newShape = """{"type":"direct_launch","package":"com.flycast.emulator","platform":"dreamcast","activity":"x.y.Main"}"""
        val withCapability = """{"type":"activity_alternate","package":"com.flycast.emulator","platform":"dreamcast","activity":"x.y.Main","capability":"DIRECT_PAYLOAD"}"""
        val withAction = """{"type":"activity_alternate","package":"com.flycast.emulator","platform":"dreamcast","activity":"x.y.Main","action":"android.intent.action.VIEW"}"""
        val withExtra = """{"type":"activity_alternate","package":"com.flycast.emulator","platform":"dreamcast","activity":"x.y.Main","extras":{"ROM":"/x"}}"""
        val cases = listOf(
            "unknown package" to (listOf(good, unknownPackage) to RejectCode.UNKNOWN_PACKAGE),
            "recognize-only package" to (listOf(good, recognizeOnlyAlt) to RejectCode.NOT_DISPATCHABLE),
            "platform the package does not serve" to (listOf(good, servedElsewhere) to RejectCode.PLATFORM_NOT_SERVED),
            "a new row shape" to (listOf(good, newShape) to RejectCode.BAD_ROW_TYPE),
            "a capability field" to (listOf(good, withCapability) to RejectCode.FORBIDDEN_FIELD),
            "an action field" to (listOf(good, withAction) to RejectCode.FORBIDDEN_FIELD),
            "an extras field" to (listOf(good, withExtra) to RejectCode.FORBIDDEN_FIELD),
        )
        val before = EmulatorSeedRegistry.allSeedPlatforms().associateWith { EmulatorSeedRegistry.candidatesFor(it) { true } }
        for ((name, case) in cases) {
            val (rows, code) = case
            val built = Overlays.build(9, rows)
            val parsed = OverlayParser.parse(built.bytes, real)
            assertTrue("$name: $parsed", parsed is ParseResult.Rejected && parsed.code == code)
        }
        // Nothing was installed by any of them, including the valid row that travelled with each bad one.
        assertEquals(null, ActiveDefinitions.current.overlayOrdinal)
        assertEquals(before, EmulatorSeedRegistry.allSeedPlatforms().associateWith { EmulatorSeedRegistry.candidatesFor(it) { true } })
        assertFalse(EmulatorSeedRegistry.allSeedPackages().contains("com.example.newemu"))
    }
}
