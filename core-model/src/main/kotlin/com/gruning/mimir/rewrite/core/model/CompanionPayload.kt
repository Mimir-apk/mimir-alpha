package com.gruning.mimir.rewrite.core.model

import kotlinx.serialization.Serializable

/**
 * What the companion (secondary) display should show.
 *
 * **This payload is a read-model snapshot, not a command surface.** That sentence is the durable
 * part and it is still true: nothing here carries an action. Commands travel a separate channel —
 * `CompanionCommandState` → `CompanionCommandBridge` — which today has **exactly one verb**
 * (`OpenGamelistOnMain`) and requires an explicit contract for each added verb.
 *
 * This is not a display-only surface: [HOME] supports drag-to-reorder, quick-launch, folder open,
 * create-Tome, rename and discard, and platform-tile taps reach the primary display.
 *
 * **Surface selection:** the second screen has no
 * primary mode — it **states what is true now**. An emulator owns screen 1 → [NOW_PLAYING]; a job
 * is running → the job surface; neither → [HOME]; nothing is true → [IDLE]. Modes that merely
 * duplicate a screen the player is already looking at are retired; see the member docs below.
 *

 */
@Serializable
enum class CompanionMode {
    /** Now-Playing hero for the game currently running (X/Y art from the current gamelist game). */
    NOW_PLAYING,

    /** The 3DS-shelf HOME (HP-11 Slice 1): three static lanes — Emulation / Android Games / Android
     *  Apps. Replaces the retired focus-follow LIBRARY_MIRROR (a focus-following carousel). */
    HOME,

    /** Idle/home state — nothing playing, ambient theme sigil. */
    IDLE,

    /** An Android app the user sent to the MAIN display — via Swap (M quick-menu) or the tab ✕ on the app
     *  they're in. The companion shows the [CompanionPayload.runningApp]
     *  "On main screen" card instead of the shelf until the user returns to Mimir. Mutually exclusive with
     *  [NOW_PLAYING] — a game and a swapped app cannot both own the main display. */
    RUNNING_APP,

}

/**
 * A snapshot the primary sends to the companion display. Immutable value: the companion renders
 * exactly what it is handed and cannot mutate library state.
 *
 * @property mode what the companion should render.
 * @property gameId the game in focus / now-playing (null in [CompanionMode.IDLE]).
 * @property title display title for the now-playing hero.
 * @property platformId platform of the focused/now-playing game.
 * @property heroArtRef opaque reference to the hero art (e.g. a media_art path); the companion resolves it, Mimir does not fetch remotely.
 * @property heroArtIsLogo true only when [heroArtRef] is transparent wide clear-logo art. Renderers use
 *   a fixed Fit stage instead of inferring shape from a path or decoding bitmap dimensions.
 * @property themeSigilRef opaque reference to the active theme's companion sigil.
 * @property themeId active theme storage key ("" when none set); the secondary display owns its own composition root.
 * @property statsLabel top-right shelf stat line.
 * @property statusLabel quiet status label for the selected game/app.
 * @property statusReady true when [statusLabel] is the ONE green per-item state on the primary ("Ready") —
 *   the companion's semantic success channel reads this flag, not the label text (policy R1; replaces
 *   the interim B8 label string-match). Producer-computed so the two screens can never disagree.
 * @property launcherLabel the resolving emulator's display label for the selected game ("Opens with ·
 *   <label>", policy R13), or null when unresolved. B11 carries the field; B12 wires + renders it.
 * @property positionLabel optional "Game X of Y" / "X / Y" line.
 * @property shelfModeLabel current shelf lane label.
 * @property shelfRows display-only shelf/list mirror rows.
 * @property selectedShelfId currently focused shelf/list row id.
 * @property isLoading true during a return/relink window so the companion can show a loading state and "loading wins on return" (see companion contract).
 */
@Serializable
data class CompanionPayload(
    val mode: CompanionMode,
    val gameId: GameId? = null,
    val title: String? = null,
    val platformId: PlatformId? = null,
    /** display-ready friendly platform label ("Arcade") — the card must never show a raw id. */
    val platformLabel: String? = null,
    val heroArtRef: String? = null,
    val heroArtIsLogo: Boolean = false,
    val themeSigilRef: String? = null,
    val themeId: String = "",
    val statsLabel: String? = null,
    val statusLabel: String? = null,
    val statusReady: Boolean = false,
    val launcherLabel: String? = null,
    val positionLabel: String? = null,
    val shelfModeLabel: String? = null,
    val shelfRows: List<CompanionShelfRow> = emptyList(),
    val selectedShelfId: String? = null,
    val isLoading: Boolean = false,
    /** [CompanionMode.HOME] only: the three 3DS-shelf lanes. Shape-derived — never focus-derived, so the
     *  home stays STATIC while the primary shelf is browsed (policy 4, HP-11). */
    val homeLanes: List<CompanionHomeLane> = emptyList(),
    /** Set iff [mode] == [CompanionMode.RUNNING_APP]: the Android app now on the MAIN display (BG-03/04). */
    val runningApp: CompanionRunningApp? = null,
    /** Set iff [mode] == [CompanionMode.HOME_SHELF_DETAIL]: display-ready detail for the focused
     *  home-shelf row (FUX-E). Every string is pre-formatted by the adapter/push site; the companion
     *  renders and never formats. */
    val homeShelfDetail: CompanionHomeShelfDetail? = null,
) {
    companion object {
        /** The ambient idle payload (nothing playing). */
        fun idle(themeSigilRef: String? = null, themeId: String = ""): CompanionPayload =
            CompanionPayload(mode = CompanionMode.IDLE, themeSigilRef = themeSigilRef, themeId = themeId)
    }
}

/**
 * One long-running job's position, as screen 2 receives it.
 *
 * [total] is null when no honest denominator exists — a library walk does not know how many files it
 * will find until it has found them. Null means **no bar**, never 0% and never stalled; it is the same
 * contract `FileQueueState.Running.fraction` carries, and it is why this crosses as a nullable Int
 * rather than a pre-computed percentage.
 */
@Serializable
data class CompanionJobProgress(
    val kind: String,
    val seen: Int,
    val total: Int? = null,
) {
    /** 0..1, or null when there is nothing honest to draw. A zero total is null, not a division. */
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (seen.toFloat() / it).coerceIn(0f, 1f) }
}

/**
 * A compact display-only row for the companion shelf mirror. It carries only what screen 2 paints:
 * label, count/status, and a few short detail lines. No launch action or mutable library command crosses here.
 */
@Serializable
data class CompanionShelfRow(
    val id: String,
    val code: String,
    val name: String,
    val countLabel: String,
    val detailLines: List<String> = emptyList(),
)

/**
 * An Android app the user sent to the MAIN display ("On main screen" card). Display-only like the
 * rest of the payload: the companion paints it and cannot act on it (the swap-back happens from the tab menu).
 */
@Serializable
data class CompanionRunningApp(
    val packageName: String,
    val label: String,
    val iconPath: String? = null,
)

/** One 3DS-shelf HOME lane (HP-11): a titled row of display-only tiles, with a teaching hint when empty. */
@Serializable
data class CompanionHomeLane(
    val id: String,
    val title: String,
    /** Rendered when [tiles] is empty (policy 6) — e.g. "Favorite an Android game to pin it here." */
    val hint: String,
    val tiles: List<CompanionHomeTile> = emptyList(),
)

@Serializable
enum class CompanionHomeTileKind { PLATFORM, ANDROID_APP, FOLDER }

/**
 * One HOME tile: a platform (label + "N games"), a favorited Android app/game (icon + label + kind), or a
 * FOLDER — a bound tome holding any mix of the two ([children]; empty for the leaf kinds).
 */
@Serializable
data class CompanionHomeTile(
    val id: String,
    val kind: CompanionHomeTileKind,
    val label: String,
    val countLabel: String? = null,
    val iconPath: String? = null,
    // a PLATFORM tile's user-picked SGB cover art (Y → change platform art on the Library shelf),
    // a local file path. When set the companion covers the tile with it instead of the drawn console emblem, so the
    // platform-art override reflects on screen 2 too. Null → the default console line-art. ANDROID_APP tiles keep
    // using [iconPath]; this is platform-only and defaulted, so older serialized payloads deserialize unchanged.
    val artPath: String? = null,
    val kindLabel: String? = null,
    val children: List<CompanionHomeTile> = emptyList(),
)

/**
 * Display-ready detail for the focused HOME shelf row (FUX-E two-zone card). Every field is a finished
 * string the adapter/push site computed from the read-model — the companion renders, never formats.
 * Emulated rows carry chips/last-played/size/discs; Android rows carry [androidTypeLabel] +
 * [androidPackage] and leave the emulated-only fields empty/null. All fields defaulted so older
 * serialized payloads deserialize unchanged.
 */
@Serializable
data class CompanionHomeShelfDetail(
    /** Short meta chips: genre / "Region X" / "vY" / "N discs" (same construction as the primary detail). */
    val chips: List<String> = emptyList(),
    /** "Last played" value — e.g. "Played today" / "Never". */
    val lastPlayedLabel: String = "",
    /** "Times played" value — "N" or "-". */
    val timesPlayedLabel: String = "",
    /** True when the row is favorited (renders a "Favorite · Yes" stat). */
    val favorite: Boolean = false,
    /** Formatted total size (SUM of disc members), or null when unknown/zero. */
    val sizeLabel: String? = null,
    /** "Disc N - name" lines, empty unless the game has >1 disc. */
    val discLines: List<String> = emptyList(),
    /** Android row type label ("Android game" …), null for emulated rows. */
    val androidTypeLabel: String? = null,
    /** Android package name, null for emulated rows. */
    val androidPackage: String? = null,
)
