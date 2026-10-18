package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.MimirCopy

/**
 * ⚠️ SAMPLE DATA — NOT WIRED TO ANY NATIVE BRIDGE ⚠️
 *
 * Every value in this file is hand-authored for the explicit Compose preview only. Runtime nulls render
 * honest loading states and never read this object. Nothing here reaches a device, launcher, or folder.
 *
 * The shape and copy are faithful to the current product so the review is meaningful:
 *  - approved vocabulary only (local-only policy): "N games", the approved status set,
 *    "Settings › Library" breadcrumb;
 *  - friendly paths : "SD card › Mimir › Roms", never a raw UUID
 *    or the internal codename;
 *  - the 8 named Norse themes , Default being the base.
 *
 * No banner is rendered (removed the debug jargon); [SettingsHomePreview] passes these
 * fixtures explicitly.
 */
object SettingsSample {

    // ── Profile ──────────────────────────────────────────────────────────────────────────────────
    /**
     * The local profile. The identity key is a STABLE local id ("local"), never the display name and
     * never the internal codename (`friendly-paths-hide-codename`).
     * A display name is OPTIONAL — everything works with none set.
     */
    const val profileStableKey: String = "local"
    /** Optional display name. Empty string models "no name set" — the panel shows a friendly fallback. */
    const val profileDisplayName: String = "Sparda"
    const val appVersion: String = "0.0.1"

    /**
     * The 8 named Norse themes plus the base Default . Only
     * the user-facing LABEL lives here — internal theme ids are the shipping product's concern, not the
     * rewrite settings surface's. Order matches the shipping picker.
     */
    val themes: List<String> = listOf(
        "Default",   // base gold (Golden Hall)
        "Freyr",     // gold
        "Surtr",     // crimson — the Forge
        "Máni",      // midnight — Máni's Vigil
        "Hel",       // nightmare — the Dread
        "Niflheim",  // void — Ginnungagap abyss
        "Yggdrasil", // terra — the world tree
        "Fólkvangr",     // love (light)
        "Baldr",     // baldr (light) — sunshine sky
    )
    const val selectedTheme: String = "Freyr"

    // ── Library ──────────────────────────────────────────────────────────────────────────────────
    /** A source folder Mimir reads games from, in place (index-in-place posture). */
    data class SampleSource(
        /** Friendly, human path — NEVER a raw UUID or codename . */
        val friendlyPath: String,
        val gamesCount: Int,
        /** One of the approved statuses; drives the status chip color (green ONLY for "Ready"). */
        val status: SampleStatus,
    )

    val sources: List<SampleSource> = listOf(
        SampleSource("SD card › Mimir › Roms", gamesCount = 1_284, status = SampleStatus.READY),
        SampleSource("Internal › Mimir › Roms", gamesCount = 372, status = SampleStatus.READY),
        SampleSource("USB drive › Retro", gamesCount = 46, status = SampleStatus.RECONNECT),
    )

    val totalGames: Int get() = sources.sumOf { it.gamesCount }
    const val totalPlatforms: Int = 17

    // ── Emulators ────────────────────────────────────────────────────────────────────────────────
    /** A per-platform emulator assignment (the launcher that opens this system by default). */
    data class SampleAssignment(
        val platform: String,
        /** The chosen emulator's friendly name, or null when nothing is assigned yet. */
        val emulator: String?,
        val status: SampleStatus,
    )

    val emulatorAssignments: List<SampleAssignment> = listOf(
        SampleAssignment("PlayStation", emulator = "DuckStation", status = SampleStatus.READY),
        SampleAssignment("Nintendo 64", emulator = "Mupen64Plus FZ", status = SampleStatus.READY),
        SampleAssignment("Sega Saturn", emulator = "Yaba Sanshiro 2", status = SampleStatus.READY),
        SampleAssignment("PlayStation 2", emulator = null, status = SampleStatus.NEEDS_EMULATOR),
        SampleAssignment("Dreamcast", emulator = "Flycast", status = SampleStatus.NEEDS_BIOS),
    )

    val platformsAssignedCount: Int get() = emulatorAssignments.count { it.emulator != null }
    val platformsTotalForEmulators: Int get() = emulatorAssignments.size

    // ── Approved status set (local-only policy) ────────────────────────────────────
    /**
     * The APPROVED status set and nothing else. Green is reserved for success ("Ready") — every other
     * status is neutral/gold or danger. No banned label ever appears here.
     */
    enum class SampleStatus(val label: String) {
        READY(MimirCopy.StatusReady),
        NEEDS_EMULATOR(MimirCopy.StatusNeedsEmulator),
        NEEDS_BIOS("Needs BIOS"),
        RECONNECT("Reconnect"),
        UNSUPPORTED(MimirCopy.StatusUnsupported),
        REPAIR("Repair"),
    }
}
