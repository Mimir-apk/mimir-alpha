package com.gruning.mimir.rewrite.core.launch.remotedefs

/**
 * The one view of emulator launch details that scan, resolution and launch read: the compiled tables plus, when the
 * player has fetched one, a verified overlay. Process-wide, because [com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry]
 * and [com.gruning.mimir.rewrite.core.launch.ShippingLaunchProfiles] are objects that every caller reaches statically.
 *
 * - With nothing installed the view is [DefinitionsMerge.merge] of the compiled tables and no overlay, which answers
 *   exactly what the compiled tables answered before this existed (pinned by `EffectiveDefinitionsWiringTest`).
 * - It has no network handle. Reading [current] is a volatile field read; installing a snapshot is the app's start-up
 *   load of the stored copy, or the Settings action's successful refresh. Nothing on a scan or launch path installs.
 */
object ActiveDefinitions {
    /**
     * The compiled tables as plain data. Built once and NEVER from [current]: the bundled side of the merge must not
     * read the merged side (that cycle is why [RegistryBundledDefinitions] reads the seed table and
     * `ShippingLaunchProfiles.bundledAlternateActivitiesFor` directly).
     */
    val bundled: BundledDefinitions by lazy { RegistryBundledDefinitions.build() }

    private val holderOnce = lazy { EffectiveDefinitionsHolder(DefinitionsMerge.merge(bundled, null)) }

    /** The holder the Settings refresher publishes to and listeners watch. */
    val holder: EffectiveDefinitionsHolder get() = holderOnce.value

    /** What is in force right now. */
    val current: EffectiveDefinitions get() = holder.current

    /** Puts [effective] in force. Returns true (and notifies listeners) only when launch behaviour actually changed. */
    fun install(effective: EffectiveDefinitions): Boolean = holder.publish(effective)
}
