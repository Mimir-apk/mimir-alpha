package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import com.gruning.mimir.rewrite.core.launch.ShippingLaunchProfiles

/**
 * The newest overlay ordinal the shipped data already contains. Bumped by a release that folds an overlay in, in the
 * same commit that regenerates `tools/emulator-definitions/`. Zero: nothing has been folded in yet, so the first
 * published release (`defs-1`, a mirror of these tables) is stored when the player fetches it and changes nothing.
 */
const val BUNDLED_BASELINE_ORDINAL: Long = 0L

/**
 * The day the compiled tables last changed, as Settings shows it ("Built-in · 8 Oct 2026"). Bump it with the tables;
 * `DefinitionsDataFilesTest` fails when the published data no longer mirrors them, which is the prompt to do so.
 */
const val BUNDLED_DEFINITIONS_DATE: String = "2026-10-08"

/**
 * Reads the real bundled tables WITHOUT changing them: the seed registry (packages, platforms, capability, cores)
 * and the alternate activities [ShippingLaunchProfiles] already knows.
 *
 * It reads the compiled data directly and never a wired lookup ([ActiveDefinitions] builds on this, so the other
 * order would be a cycle).
 */
object RegistryBundledDefinitions {
    fun build(baselineOrdinal: Long = BUNDLED_BASELINE_ORDINAL): BundledDefinitions {
        val seeds = EmulatorSeedRegistry.SEEDS.map { seed ->
            val platforms = seed.retroArchCoreByPlatform.keys + seed.retroArchAdditionalCoresByPlatform.keys
            BundledSeed(
                packageName = seed.packageName,
                capability = seed.capability,
                platforms = seed.platforms,
                retroArchCores = platforms.associateWith { platform ->
                    buildList {
                        seed.retroArchCoreByPlatform[platform]?.let(::add)
                        addAll(seed.retroArchAdditionalCoresByPlatform[platform].orEmpty())
                    }.distinct()
                },
            )
        }
        val alternates = LinkedHashMap<PackagePlatform, List<String>>()
        for (seed in EmulatorSeedRegistry.SEEDS) {
            for (platform in seed.platforms) {
                val known = ShippingLaunchProfiles.bundledAlternateActivitiesFor(platform, seed.packageName)
                if (known.isNotEmpty()) alternates[PackagePlatform(seed.packageName, platform)] = known
            }
        }
        return BundledDefinitions(
            baselineOrdinal = baselineOrdinal,
            seeds = seeds,
            activityAlternates = alternates,
            dispatchPackages = EmulatorSeedRegistry.allDispatchPackages(),
        )
    }
}
