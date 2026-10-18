package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirIcon

/**
 * What Appearance holds, in one line, for the rail entry and the pane's own header. It names the player's background
 * and the Mimir button, and it still names artwork services, which live in this pane. "Background" (the player's own
 * image behind the shelves) and "backdrops" (a game's hero art) are never a list apart: the second is
 * qualified as "game backdrops" and sits in its own sentence, so the two words cannot read as one thing.
 */
internal const val AppearanceBlurb =
    "Theme, background, the Mimir button, color, and focus. Also game backdrops and artwork services."

/**
 * Release-alpha Settings has seven ordered sections:
 * Profile, Library, Emulators, Appearance, Music, Controls, About.
 */
enum class SettingsCategory(
    val label: String,
    val blurb: String,
    /**
     * The rail mark. the icon set rolls out to **Settings first** — the screen
     * the audit brief names as reading developer-oriented.
     *
     * Every entry carries one, not just a sample. The design lane's step 5 proposed converting a SINGLE
     * row to prove the Compose face, which is right as a proof and wrong as a shipped rail: five bare
     * labels beside one marked row reads as an unfinished job rather than a demonstration.
     *
     * No mark is reused. CONTROLS takes CONTROLLER and EMULATORS takes ROM rather than both reaching for
     * the pad — a rail whose entries share a glyph is a rail that has stopped distinguishing them.
     */
    val mark: MimirIcon,
) {
    PROFILE(
        label = "Profile",
        blurb = "Your name and optional RetroAchievements connection.",
        mark = MimirIcon.PROFILE,
    ),
    LIBRARY(
        label = MimirCopy.RailLibrary,
        blurb = "Folders, rescans, hidden games, enrichment sources, and backup.",
        mark = MimirIcon.LIBRARY,
    ),
    EMULATORS(
        label = "Emulators",
        blurb = "Which emulator opens each system, plus per-game Open with choices.",
        mark = MimirIcon.ROM,
    ),
    APPEARANCE(
        label = "Appearance",
        blurb = AppearanceBlurb,
        mark = MimirIcon.DISPLAY,
    ),
    MUSIC(
        label = "Music",
        blurb = "The music player, your own music folder, and the sound under a game’s launch.",
        // Drawn for this rail (2026-09-30): no music glyph existed, and a rail mark is never reused.
        mark = MimirIcon.MUSIC,
    ),
    CONTROLS(
        label = "Controls",
        blurb = "Mimir button permissions and the Utility panel.",
        mark = MimirIcon.CONTROLLER,
    ),
    ABOUT(
        label = "About",
        blurb = "Version, legal acknowledgements, credits, and data sources.",
        mark = MimirIcon.INFO,
    ),
    ;

    companion object {
        /** The rail order, top-to-bottom. Also the D-pad up/down traversal order. */
        val rail: List<SettingsCategory> =
            listOf(PROFILE, LIBRARY, EMULATORS, APPEARANCE, MUSIC, CONTROLS, ABOUT)
    }
}

/** Every rail entry retains this physical target height; the rail scrolls instead of compressing. */
const val SettingsRailEntryHeightDp: Int = 48
