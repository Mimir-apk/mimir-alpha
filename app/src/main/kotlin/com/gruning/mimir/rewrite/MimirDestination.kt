package com.gruning.mimir.rewrite

import androidx.compose.runtime.MutableState

/**
 * The rewrite's navigation destinations. Kept as a tiny enum + state-based switch (not
 * navigation-compose) on purpose: a NavHost adds a second back-stack / focus-owning system, and this
 * slice's whole thesis is ONE focus authority. When the destination count grows we lift this to
 * navigation-compose behind the same [com.gruning.mimir.rewrite.core.input.FocusAuthority].
 *
 * - DIAGNOSTICS is a retained historical value only; release navigation normalizes it to SETTINGS.
 * - SETTINGS is the first real migrated surface (feature-settings).
 * - HOME is the curated game-first shelf and the post-onboarding/default root.
 * - LIBRARY is the full collection surface: platforms → dedup-collapsed gamelist, D-pad navigable.
 * - BROWSE (M6, feature-browse) is the hidden-games RECOVER surface :
 *   only hidden games live here; restore returns a game to the shelf/gamelist. NOT a placement queue.
 *
 * Each surface owns its internal scope stack through FocusAuthority; shell return origins are explicit.
 */
enum class MimirDestination {
    // ONBOARDING (C4) is the first-run gate: on a fresh install the app opens here (welcome → optional
    // profile → REQUIRED library-folder pick → theme → shelf), never on the shelf. It owns ALL input while
    // open (START can't escape to Settings, Back can't drop to an empty shelf) and hands off to LIBRARY once
    // `OnboardingStore.markComplete` runs. Gated by `OnboardingStore.isFirstRun` at the initial destination.
    ONBOARDING,
    HOME,
    DIAGNOSTICS,
    SETTINGS,
    LIBRARY,
    BROWSE,
    // LG01 : the TOOLS destination is RETIRED. Its BIOS/Texture "helper" cards
    // described GETTING and PLACING files — acquisition-adjacent copy Mimir does not carry — and the
    // native placement bridge behind them was never built, so they were permanent "coming soon" stubs.
    // Tool-shaped features belong in
    // Utility. BIOS survives as passive DETECTION only (Settings › Emulators › BIOS files).
}

/** The single release-governance boundary for every requested app destination. */
internal fun normalizeMimirDestination(
    requested: MimirDestination,
    finalShelfEnabled: Boolean = BuildConfig.FINAL_SHELF_ENABLED,
): MimirDestination = when {
    // Unit 10 removes Diagnostics from normal-release navigation. Retaining the enum keeps restored
    // historical state parseable, while every entry point is normalized back to Settings.
    requested == MimirDestination.DIAGNOSTICS -> MimirDestination.SETTINGS
    !finalShelfEnabled && requested == MimirDestination.HOME -> MimirDestination.LIBRARY
    else -> requested
}

/** Normalizes both restored values and every subsequent runtime assignment. */
internal class FinalShelfDestinationState(
    private val backingState: MutableState<MimirDestination>,
    private val finalShelfEnabled: Boolean = BuildConfig.FINAL_SHELF_ENABLED,
) {
    var value: MimirDestination
        get() = normalizeMimirDestination(backingState.value, finalShelfEnabled)
        set(requested) {
            backingState.value = normalizeMimirDestination(requested, finalShelfEnabled)
        }
}
