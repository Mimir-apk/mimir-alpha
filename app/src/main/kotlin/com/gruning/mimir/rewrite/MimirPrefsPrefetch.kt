package com.gruning.mimir.rewrite

import android.content.Context

/**
 * R01 S4 (adversarial review SPINE-6/RFG-04 + the PFI-2 mitigation): MainActivity.onCreate performs 8+
 * SERIAL synchronous SharedPreferences loads on the main thread before setContent, and
 * CompanionProcessRuntime.create() performs one more in Application.onCreate. `getSharedPreferences()`
 * itself is non-blocking — it starts the XML disk load on a system background thread; only the first VALUE
 * getter blocks. Touching every known prefs file HERE, first thing in Application.onCreate, gives those
 * loads the whole process-init + Activity-construction window of head start, so the serial `.load()` calls
 * find their data already parsed (or nearly so).
 *
 * The name list mirrors the stores' private constants ON PURPOSE (they are stable, and exposing them would
 * widen store APIs for an optimization): a drifted name only LOSES the prefetch — it can never change
 * behavior, because every store still opens its own file by its own constant.
 */
internal object MimirPrefsPrefetch {
    private val PrefsFiles = listOf(
        // FIRST: read synchronously moments later in CompanionProcessRuntime.create() (PFI-2).
        "mimir_companion_pending_operation", // CompanionPendingOperationStore
        // MainActivity.onCreate's serial store loads (SPINE-6/RFG-04).
        "mimir_rewrite_appearance", // ThemeStore + BackdropStore (shared file)
        "mimir_rewrite_profile", // ProfileNameStore
        "mimir_companion_shelf", // CompanionOrderStore
        "mimir_rewrite_platform_defaults", // PlatformEmulatorDefaultStore
        "mimir_rewrite_quick_launch", // QuickLaunchStore
        "mimir_rewrite_artwork_keys", // SteamGridDbKeyStore
        "mimir_rewrite_onboarding", // OnboardingStore
        "mimir_m_button", // MButtonSettingsStore
        "mimir_navigation_hints", // NavigationHintSettingsStore
        "mimir_controller_keymap", // ControllerMappingStore
        "mimir_controller_shortcuts", // ControllerShortcutStore
        // DELIBERATELY THE OLD NAME (R3-M, policy 2026-08-25). Must match TomeCartridgeArtStore.PreferencesName
        // byte-for-byte; a "tidied" copy here warms a file nothing reads.
        "mimir_companion_overlay", // role-specific M-button positions
        "mimir_home_tomes", // HomeTomeStore
        "mimir_home_collections", // read-only v1-v3 Tome import source
        "mimir_home_favorites_migration", // one-way legacy Home promotion marker
        "mimir_home_launch_return", // HomeLaunchReturnStore
        // UT01 conciliation: the Utility surface's stores.
        "mimir_rewrite_window_brightness", // AndroidWindowBrightnessPreferenceStore
        "mimir_rewrite_utility", // AndroidUtilityPreferenceStore
        // GL-RP5 rev B: runtime-measured gamelist cover aspects (feature-library MeasuredCoverAspects).
        "mimir_rewrite_gamelist_aspects_v2", // MeasuredCoverAspects
        // ART02: per-game verified ScreenScraper resolutions (read on every art fetch).
        "mimir_rewrite_art_resolutions", // ArtResolutionStore
        // AU02: UI-sound + preview-audio preferences (read synchronously in MainActivity.onCreate).
        "mimir_rewrite_audio", // AudioPreferences
        // the launched-game registry's restore reads this on IO right after the Application starts.
        "mimir_launched_games", // PrefsLaunchedGameDocumentMedium
    )

    /** Non-blocking by contract: only touches getSharedPreferences (which schedules the async load). */
    fun warm(context: Context) {
        val app = context.applicationContext
        PrefsFiles.forEach { name -> app.getSharedPreferences(name, Context.MODE_PRIVATE) }
    }
}
