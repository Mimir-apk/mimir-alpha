package com.gruning.mimir.rewrite.core.launch

/**
 * Extension-detectable registry platforms that deliberately have no Android launch candidate.
 * Keeping this set typed and reviewed prevents silent coverage holes and speculative Open-With rows.
 */
object ExtensionLaunchCoverage {
    val unsupportedCanonicalPlatforms: Map<String, String> = linkedMapOf(
        "atari_jaguar_cd" to "No proof-backed Android Jaguar CD dispatch target.",
        "coleco_colecovision_adam" to "No proof-backed Android Coleco Adam dispatch target.",
        // X1 BOX (com.izzy2lost.x1box) seeded OPEN_ONLY against this platform
        // in EmulatorSeedRegistry, so it now has a dispatch target and must leave this list.
    )
}
