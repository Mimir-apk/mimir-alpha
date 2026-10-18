package com.gruning.mimir.rewrite.feature.settings

/**
 * The Settings › Music pane's read model.
 *
 * Plain data, like [LibraryReadModel]: this module never sees `core-audio`. The app projects the engine's
 * StateFlows into one of these and hands it down; a null model renders an honest loading state. Every
 * label here is player-facing copy, so storage identities (`MIMIR`, `CUSTOM`) never reach a row.
 */
data class MusicReadModel(
    // ── MUSIC PLAYER ──
    val musicEnabled: Boolean,
    /**
     * Mimir's music is sounding right now (the engine's word, not the switch): false while it is held by another app, a call, the cable, the screen, the music hours
     * or the player's pause, and when its level is zero. It is what the pane's status chip reads, so the chip says "Paused" for music that is waiting. Defaulted
     * false: a caller that does not know claims nothing.
     */
    val playing: Boolean = false,
    /** The track the bed is playing now, or null when nothing is (muted, backgrounded, or no bed). */
    val nowPlaying: MusicNowPlayingReadModel?,
    val source: MusicSourceValue,
    /**
     * Whether "Your music" can be chosen: a folder is chosen, readable, and holds tracks, so the choice is audible.
     * A choice that would change nothing audible is not offered.
     */
    val customSourceAvailable: Boolean,
    val order: MusicOrderValue,
    /** The music volume, 0..100. The same value Utility's "Music volume" slider moves (`ambientSoundVolume`). */
    val volumePercent: Int,
    val songTitlesEnabled: Boolean,
    // ── CUSTOM MUSIC ──
    /** The chosen folder and its index, or null when none is chosen. */
    val folder: MusicFolderReadModel?,
    // ── GAME MUSIC ──
    val launchSoundEnabled: Boolean,
    /** The launch sound's own level, 0..100, independent of the music volume. */
    val launchSoundVolumePercent: Int,
    val gameMusic: GameMusicDownloadReadModel,
    val playOnHighlight: Boolean,
    /**
     * Why the player is the theme's music although Your music is chosen (no folder, no tracks, folder unreadable or its
     * grant lost), in the player's words; null when nothing is wrong. Authored by the app from the engine's reason.
     */
    val customFallbackCopy: String? = null,
    /** Game music card: other music keeps playing when a game starts (the Tweaker stops the game taking audio focus). */
    val keepMusicInGames: Boolean = true,
    /**
     * Whether Mimir holds notification access, which is what lets the music panel see and control other apps' players. Read fresh
     * each time the app comes back to the front (the grant is made on another screen). Always shown, so no loading arm.
     */
    val otherPlayersAccessGranted: Boolean = false,
    /** The Equalizer (the one switch) is on; the Equalizer row says its preset, or Off. */
    val equalizerEnabled: Boolean = false,
    /**
     * The Equalizer is on but the global engine is not applying it (another app holds the system equalizer, or Android refused it), so only
     * Mimir's own music is shaped right now. False while it reaches all sound, and while it is off.
     */
    val equalizerMimirMusicOnly: Boolean = false,
    /** The equalizer's preset as a player reads it ("Rock", "Custom"). */
    val equalizerPresetLabel: String = "Flat",
    /**
     * What Your music hands the system mixer ("16-bit · 48 kHz"), for the Output row; null until the player has said, and always
     * null while a theme bed is what plays.
     */
    val outputValue: String? = null,
    /** "Fade between tracks": Your music dips out and in at each track change, on the one player. Off, the default, is gapless. */
    val fadeBetweenTracks: Boolean = false,
    /** "Music hours": the hours of the day the music may play in. */
    val hours: MusicHoursValue = MusicHoursValue.ALL_DAY,
    /** "Play outside Mimir": On is the policy that Your music plays in games and other apps; Off keeps it inside Mimir. */
    val playOutsideMimir: Boolean = true,
    /** "Pause when screen is off": Off lets Your music play through it. */
    val pauseOnScreenOff: Boolean = true,
    /** "Pause if headphones unplug": Off lets Your music play on from the speaker. */
    val pauseOnUnplug: Boolean = true,
) {
    /** The Launch sound row's one choice, read from the stored toggle and level ([MusicLaunchSoundValue.fromStored]). */
    val launchSound: MusicLaunchSoundValue
        get() = MusicLaunchSoundValue.fromStored(launchSoundEnabled, launchSoundVolumePercent)
}

/** "Title · Source" for the Now playing row and the song-title chip. */
data class MusicNowPlayingReadModel(
    val title: String,
    /** Where the track comes from: the theme's bed ("Mimir") or a folder's name. Never a path. */
    val source: String,
    /** A single bed loops; A restarts it and the row says so rather than promising a next track. */
    val singleTrack: Boolean,
    /** Your music's source format ("FLAC · 24/96 · 2,304 kbps"), the row's second line; null for a theme bed and until it is known. */
    val formatLine: String? = null,
)

/** The chosen custom-music folder as the pane shows it: a friendly path and the index's own figures. */
data class MusicFolderReadModel(
    /** Friendly, never a raw tree URI . */
    val friendlyPath: String,
    val trackCount: Int,
    /** "Scanned just now" / "Scanned 2 h ago", authored by the app from the index's own clock; null before a scan. */
    val lastScanLabel: String?,
    val scanning: Boolean,
    /** The index's last failure, verbatim from the engine, or null. */
    val error: String?,
)

/**
 * The game-music batch (the launch sound Enrich used to fetch, now opt-in here). The row renders exactly
 * one of these; the engine's block states are their own arms so the row never has to guess why it is gated.
 */
sealed interface GameMusicDownloadReadModel {
    /** Nothing running. [have] of [total] games already hold a sound; [requestsToday] is the account's day as last read. */
    data class Idle(val have: Int, val total: Int, val requestsToday: MusicRequestsTodayReadModel?) : GameMusicDownloadReadModel

    /**
     * A batch is running. [currentIndex] and [current] name the game being looked up (0 and null when none is in flight, and [current] null
     * for a game with no name), so the line moves while a slow lookup runs. [stopping] is Cancel pressed: the work is coming to a stop.
     */
    data class Running(
        val done: Int,
        val total: Int,
        val written: Int,
        val currentIndex: Int = 0,
        val current: String? = null,
        val stopping: Boolean = false,
    ) : GameMusicDownloadReadModel

    /**
     * The last batch's tally, the engine's own three figures. Pressing again resumes the games still missing one. [stopped] is a batch the
     * player stopped (the line says Stopped, never Done); [hint] is the one reason most failed games share, when the engine knows it.
     */
    data class Finished(
        val written: Int,
        val skipped: Int,
        val failed: Int,
        val stopped: Boolean = false,
        val hint: GameMusicFailureValue? = null,
    ) : GameMusicDownloadReadModel

    /** ScreenScraper is not connected; A goes to the connect row. */
    data object NotConnected : GameMusicDownloadReadModel

    /** An artwork download is running; the batch waits for it (the two share one account allowance). */
    data object EnrichRunning : GameMusicDownloadReadModel

    /** No count yet, or nothing to count: the engine reports total 0 for both. Never rendered as "0 of 0". */
    data object Unavailable : GameMusicDownloadReadModel
}

/**
 * Why most of a batch's failed games failed, in the words the card shows after its counts ([copy]). Each is a true statement about the cause:
 * the app maps its engine's reason onto one of these, and a batch whose cause it cannot name carries none.
 */
enum class GameMusicFailureValue(val copy: String) {
    NO_ANSWER("ScreenScraper didn’t answer — try again later"),
    DAILY_LIMIT("ScreenScraper’s daily limit is reached"),
    BUSY("ScreenScraper is busy — try again later"),
    SIGN_IN_REFUSED("ScreenScraper sign-in was refused — reconnect in $MusicConnectWhereCopy"),
    BUILD_REFUSED("ScreenScraper isn’t available in this build"),
    COULDNT_SAVE("Couldn’t save the sound"),
}

/** Where the ScreenScraper connection lives, spelled as Settings labels it. */
const val MusicConnectWhereCopy: String = "Settings › Appearance › Enrichment & artwork"

/**
 * The account's requests-today count as it was LAST READ, with how long ago. A fresh figure is an authenticated
 * call the pane must not make by being drawn, so the row shows the age ("read 12 min ago") and never a promise.
 */
data class MusicRequestsTodayReadModel(val count: Int, val ageLabel: String)

/** Where the player's music comes from. [label] is the only form a player ever reads. */
enum class MusicSourceValue(val label: String) {
    MIMIR("Mimir’s music"),
    CUSTOM("Music on this device"),
}

/** How a list of tracks is walked (Settings › Music › Order). Your music's player plays its folder this way. */
enum class MusicOrderValue(val label: String, val detail: String) {
    IN_ORDER("In order", "Tracks play one after another, as the folder lists them."),
    SHUFFLE("Shuffle", "Tracks play in a random order, each once before any repeats."),
}

/**
 * The hours of the day the music may play in (Settings › Music › Music hours). A short list of named windows: no spec fixed a
 * time editor, and the select shell gives every window pad and touch access. [detail] is the window in the words the picker shows;
 * it is the same window `MimirMusicHours` holds, which the app pins in a test.
 */
enum class MusicHoursValue(val label: String, val detail: String) {
    ALL_DAY("All day", "Music plays at any time of day."),
    DAYTIME("Daytime", "7:00 AM to 7:00 PM"),
    EVENING("Evening", "5:00 PM to 11:00 PM"),
    NOT_OVERNIGHT("Not overnight", "7:00 AM to 11:00 PM"),
}

/** The one phrase the remaining ENGINEERING row carries . Never on a NOW row. */
const val MusicComingNextCopy: String = "Coming next"

/** "On" / "Off" as a toggle-as-row's value (the `appearance/layout` precedent reads the same lane). */
fun musicToggleLabel(enabled: Boolean): String = if (enabled) "On" else "Off"

/** "50%", or "Off" at zero: the same words Utility's slider uses, so one level reads the same everywhere. */
fun musicPercentLabel(percent: Int): String =
    if (percent <= 0) "Off" else "${percent.coerceIn(0, 100)}%"
