package com.gruning.mimir.rewrite.core.audio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the frontend music comes from: the theme bed Mimir ships, or a folder the player chose. */
enum class MimirMusicSource { MIMIR, CUSTOM }

/** The order a track list plays in. Your music's player reads it; a theme bed is one piece and has no order. */
enum class MimirMusicOrder { IN_ORDER, SHUFFLE }

/**
 * The persisted UI-sound preference (on by default, subtle volume).
 *
 * Mirrors [com.gruning.mimir.rewrite.BackdropStore] 1:1: a dedicated SharedPreferences file, an
 * `object` wrapping a MutableStateFlow, `load(context)` once at startup, synchronous writes on every
 * setter. Local-only — never network.
 */
object AudioPreferences {
    private const val Tag = "AudioPreferences"
    private const val PREFS = "mimir_rewrite_audio"
    private const val KEY_ENABLED = "uiSoundEnabled"
    private const val KEY_VOLUME = "uiSoundVolume"
    private const val KEY_PREVIEW_AUDIO_ENABLED = "previewAudioEnabled"
    private const val KEY_PREVIEW_AUDIO_MIX = "previewAudioMix"
    private const val KEY_AMBIENT_VOLUME = "ambientSoundVolume"

    private const val DEFAULT_ENABLED = true
    private const val DEFAULT_VOLUME = 0.6f
    // Policy 2026-08-10, once the rail stopped lying. 0.22 was chosen while the bed was also
    // being scaled by the UI sound-effect volume, so it actually delivered 13.2% (~-38.1 dBFS with
    // the master's own -20.5 dBFS RMS) -- barely present on a handheld speaker. With that second
    // stage removed the number finally means what it says, and this value was selected by ear.
    private const val DEFAULT_AMBIENT_VOLUME = 0.5f

    // AU02: the preview clip's own audio behind the UI (Bifröst bed). ON by default — video mode is
    // already opt-in, and the sound is part of the preview the user opted into. The mix is RELATIVE and
    // deliberately soft (an ambient bed under the UI, ~2× under the cues); the system Media volume row
    // stays the master for both.
    private const val DEFAULT_PREVIEW_AUDIO_ENABLED = true
    private const val DEFAULT_PREVIEW_AUDIO_MIX = 0.35f

    // BM03 (): a separate
    // level for the NAVIGATION cue class only (tick / back / page / context-switch) on top of the
    // master UI-sound volume. 1.0 default = exactly today's loudness; 0 mutes nav cues while
    // confirms, toggles and errors keep sounding.
    private const val KEY_NAV_VOLUME = "navSoundVolume"
    private const val DEFAULT_NAV_VOLUME = 1f

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _volume = MutableStateFlow(DEFAULT_VOLUME)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _previewAudioEnabled = MutableStateFlow(DEFAULT_PREVIEW_AUDIO_ENABLED)
    val previewAudioEnabled: StateFlow<Boolean> = _previewAudioEnabled.asStateFlow()

    private val _previewAudioMix = MutableStateFlow(DEFAULT_PREVIEW_AUDIO_MIX)
    val previewAudioMix: StateFlow<Float> = _previewAudioMix.asStateFlow()

    private val _ambientVolume = MutableStateFlow(DEFAULT_AMBIENT_VOLUME)
    val ambientVolume: StateFlow<Float> = _ambientVolume.asStateFlow()

    private val _navVolume = MutableStateFlow(DEFAULT_NAV_VOLUME)
    val navVolume: StateFlow<Float> = _navVolume.asStateFlow()

    // Settings > Music . `ambientSoundVolume` above IS the music volume: the Utility
    // slider and the Music card's dial are one value, and no second music volume exists.
    //
    // Play-on-highlight is stored before anything reads it: it persists so the row can be real and a later
    // build finds the player's choice already made. Every other music setting below is read by the player.
    private const val KEY_MUSIC_ENABLED = "musicEnabled"
    private const val KEY_MUSIC_SOURCE = "musicSource"
    private const val KEY_MUSIC_ORDER = "musicOrder"
    private const val KEY_SONG_TITLES_ENABLED = "songTitlesEnabled"

    // The per-platform music switch was removed (policy 10-01, Nova walk): its stored value is cleared at load.
    private const val LEGACY_KEY_PLATFORM_MUSIC_ENABLED = "platformMusicEnabled"
    private const val KEY_CUSTOM_MUSIC_TREE_URI = "customMusicTreeUri"
    private const val KEY_LAUNCH_SOUND_ENABLED = "launchSoundEnabled"
    private const val KEY_LAUNCH_SOUND_VOLUME = "launchSoundVolume"
    private const val KEY_GAME_MUSIC_ON_HIGHLIGHT = "gameMusicOnHighlight"
    private const val KEY_KEEP_MUSIC_IN_GAMES = "keepMusicInGames"

    // The music rows of 2026-10-07 (Order's own key is above). Each default is the behaviour that shipped before the row existed,
    // or the spec's own (the stops at screen-off and unplugged headphones), so a player who never opens the row hears what the spec
    // describes. The fade between tracks is Off : playback is gapless until the player asks for the dip.
    private const val KEY_MUSIC_HOURS = "musicHours"
    private const val KEY_FADE_BETWEEN_TRACKS = "fadeBetweenTracks"
    private const val KEY_PLAY_OUTSIDE_MIMIR = "playOutsideMimir"
    private const val KEY_PAUSE_ON_SCREEN_OFF = "pauseOnScreenOff"
    private const val KEY_PAUSE_ON_UNPLUG = "pauseOnUnplug"

    private val DEFAULT_MUSIC_HOURS = MimirMusicHours.ALL_DAY
    private const val DEFAULT_FADE_BETWEEN_TRACKS = false
    private const val DEFAULT_PLAY_OUTSIDE_MIMIR = true
    private const val DEFAULT_PAUSE_ON_SCREEN_OFF = true
    private const val DEFAULT_PAUSE_ON_UNPLUG = true

    private const val DEFAULT_MUSIC_ENABLED = true
    private val DEFAULT_MUSIC_SOURCE = MimirMusicSource.MIMIR
    private val DEFAULT_MUSIC_ORDER = MimirMusicOrder.SHUFFLE
    private const val DEFAULT_SONG_TITLES_ENABLED = true
    private const val DEFAULT_LAUNCH_SOUND_ENABLED = true

    // 1.0 is what the intro played at before this was a setting, so a player who never opens the row
    // hears no change.
    private const val DEFAULT_LAUNCH_SOUND_VOLUME = 1f

    // Off: A game's sound plays at launch only; this is the stored-but-unused
    // switch for "play on highlight".
    private const val DEFAULT_GAME_MUSIC_ON_HIGHLIGHT = false

    // On: policy 2026-10-01. A game Mimir launches cannot take audio focus, so the music already
    // playing (YouTube Music, Spotify, Your music) carries on. Only has an effect when the Tweaker offers it.
    private const val DEFAULT_KEEP_MUSIC_IN_GAMES = true

    private val _musicEnabled = MutableStateFlow(DEFAULT_MUSIC_ENABLED)

    /** Off silences the frontend music everywhere; the policy gates on it, so a fade carries the change. */
    val musicEnabled: StateFlow<Boolean> = _musicEnabled.asStateFlow()

    private val _musicSource = MutableStateFlow(DEFAULT_MUSIC_SOURCE)
    val musicSource: StateFlow<MimirMusicSource> = _musicSource.asStateFlow()

    private val _musicOrder = MutableStateFlow(DEFAULT_MUSIC_ORDER)

    /** In order or Shuffle: Your music's player walks its folder that way. Read live, so a change applies to the next track. */
    val musicOrder: StateFlow<MimirMusicOrder> = _musicOrder.asStateFlow()

    private val _musicHours = MutableStateFlow(DEFAULT_MUSIC_HOURS)

    /** The hours of the day the music may play in; [MimirMusicHours.ALL_DAY] holds it never. Outside them the pause reason is SCHEDULE. */
    val musicHours: StateFlow<MimirMusicHours> = _musicHours.asStateFlow()

    private val _fadeBetweenTracks = MutableStateFlow(DEFAULT_FADE_BETWEEN_TRACKS)

    /**
     * Your music dips out over the last second of a track and in over the first second of the next, on the one player (a true
     * crossfade needs two players at once, which "one player at a time" rules out). Off, the default, is gapless.
     */
    val fadeBetweenTracks: StateFlow<Boolean> = _fadeBetweenTracks.asStateFlow()

    private val _playOutsideMimir = MutableStateFlow(DEFAULT_PLAY_OUTSIDE_MIMIR)

    /** On: Your music plays in games and other apps . Off: it plays only while Mimir is in front. */
    val playOutsideMimir: StateFlow<Boolean> = _playOutsideMimir.asStateFlow()

    private val _pauseOnScreenOff = MutableStateFlow(DEFAULT_PAUSE_ON_SCREEN_OFF)

    /** On: Your music pauses when the screen goes off and carries on when it comes back. Off: it plays through. */
    val pauseOnScreenOff: StateFlow<Boolean> = _pauseOnScreenOff.asStateFlow()

    private val _pauseOnUnplug = MutableStateFlow(DEFAULT_PAUSE_ON_UNPLUG)

    /** On: Your music pauses when headphones are unplugged and waits for the player's play. Off: it plays on from the speaker. */
    val pauseOnUnplug: StateFlow<Boolean> = _pauseOnUnplug.asStateFlow()

    private val _songTitlesEnabled = MutableStateFlow(DEFAULT_SONG_TITLES_ENABLED)

    /** Whether the UI shows the song-title chip. The engine emits `trackStarted` regardless. */
    val songTitlesEnabled: StateFlow<Boolean> = _songTitlesEnabled.asStateFlow()

    private val _customMusicTreeUri = MutableStateFlow<String?>(null)

    /**
     * The folder the player chose for Custom music, as a tree URI string; null when none. Wiped with the
     * app's data, so a reinstall asks again. Written by `MimirMusicLibrary.setTree`, which is also what
     * makes the persistable grant outlive the process; set it through that, not directly.
     */
    val customMusicTreeUri: StateFlow<String?> = _customMusicTreeUri.asStateFlow()

    private val _launchSoundEnabled = MutableStateFlow(DEFAULT_LAUNCH_SOUND_ENABLED)

    /** Off: the opening intro plays without the game's own sound. */
    val launchSoundEnabled: StateFlow<Boolean> = _launchSoundEnabled.asStateFlow()

    private val _launchSoundVolume = MutableStateFlow(DEFAULT_LAUNCH_SOUND_VOLUME)

    /** The level the intro's ramp reaches for the game's sound, independent of the music's own volume. */
    val launchSoundVolume: StateFlow<Float> = _launchSoundVolume.asStateFlow()

    private val _gameMusicOnHighlight = MutableStateFlow(DEFAULT_GAME_MUSIC_ON_HIGHLIGHT)

    /** Stored only; default Off. Game sounds are launch-only by default. */
    val gameMusicOnHighlight: StateFlow<Boolean> = _gameMusicOnHighlight.asStateFlow()

    private val _keepMusicInGames = MutableStateFlow(DEFAULT_KEEP_MUSIC_IN_GAMES)

    /**
     * On: a game Mimir launches is stopped from taking audio focus, so other music keeps playing. Read at
     * each launch, never cached by the holder, so the row takes effect on the next game.
     */
    val keepMusicInGames: StateFlow<Boolean> = _keepMusicInGames.asStateFlow()

    // The Equalizer, stored whole as one string (see EqualizerSettingsCodec) so a choice is never half written. Its `enabled` is the
    // one switch: the global engine (SystemEqualizer) is wanted exactly while it is on.
    private const val KEY_EQUALIZER = "equalizer"

    // The second switch the Equalizer had until 2026-10-10 (the "System EQ", a boolean beside the string). It is read once, merged into
    // the one switch (EqualizerSwitchMigration) and removed in the same edit; nothing writes it any more.
    private const val LEGACY_KEY_SYSTEM_EQUALIZER = "systemEqualizer"
    private val equalizerLock = Any()

    @Volatile
    private var appContext: Context? = null

    // True once the stored equalizer was read, by [load] or on demand by [ensureEqualizerLoaded].
    private var equalizerLoaded = false

    private val _equalizerChoice = MutableStateFlow(EqualizerSettings())

    /**
     * The one Equalizer as the player chose it: off and flat until they turn it on. This is what the editor shows and edits, and what
     * the global engine applies to all normal sound while its switch ([EqualizerSettings.enabled]) is on. Change it through
     * [MimirEqualizer].
     */
    val equalizerChoice: StateFlow<EqualizerSettings> = _equalizerChoice.asStateFlow()

    private val _systemEqualizerActive = MutableStateFlow(false)

    /**
     * True from the moment the global engine claims the mix (it holds control and is about to push the curve, see
     * [SystemEqualizerController]) until it stops applying it: read back as holding the curve, or the claim retracted when the effect
     * is refused, lost or released. Never stored: a new process starts false.
     */
    val systemEqualizerActive: StateFlow<Boolean> = _systemEqualizerActive.asStateFlow()

    private val _equalizer = MutableStateFlow(EqualizerSettings())

    /**
     * What Your music's own player applies: the [equalizerChoice], with its switch off while the global engine has the mix, so the
     * music is not equalized twice (the player follows this flow and nothing else). The moment the engine is off, lost or unavailable
     * the player's own stage is back, so the Equalizer still shapes Mimir's music.
     */
    val equalizer: StateFlow<EqualizerSettings> = _equalizer.asStateFlow()

    /** Called with the lock held, after the choice or the active flag changed. */
    private fun publishPlayerEqualizer() {
        _equalizer.value = _equalizerChoice.value.forInPlayer(_systemEqualizerActive.value)
    }

    /**
     * Applies [transform] to the equalizer choice and stores the result, atomically against another change. The context is the one
     * [load] was given: the facade has none of its own, as the music panel's volume has none.
     */
    internal fun updateEqualizer(transform: (EqualizerSettings) -> EqualizerSettings) {
        synchronized(equalizerLock) {
            val next = transform(_equalizerChoice.value)
            if (next == _equalizerChoice.value) return
            _equalizerChoice.value = next
            publishPlayerEqualizer()
            val context = appContext
            if (context == null) {
                Log.w(Tag, "equalizer changed before the preferences loaded; it is not stored")
            } else {
                prefs(context).edit().putString(KEY_EQUALIZER, EqualizerSettingsCodec.encode(next)).apply()
            }
        }
    }

    /**
     * The producer of the bypass flag: [SystemEqualizer] says whether the global engine has the whole mix, and Your music's player
     * stands down while it does. In memory only. Public for the app's tests; nothing else should call it.
     */
    fun setSystemEqualizerActive(active: Boolean) {
        synchronized(equalizerLock) {
            if (active == _systemEqualizerActive.value) return
            _systemEqualizerActive.value = active
            publishPlayerEqualizer()
        }
    }

    /**
     * Reads the stored Equalizer if [load] has not run yet, and adopts [context] for storing later changes. Your music's player calls
     * this when it is built, and the global engine when the process starts: a cold start through a media button builds the player
     * before the activity's [load], and neither may start flat or off with a choice on file. Idempotent: once the choice is read, a
     * later call changes nothing.
     */
    fun ensureEqualizerLoaded(context: Context) {
        synchronized(equalizerLock) {
            if (equalizerLoaded) return
            if (appContext == null) appContext = context.applicationContext
            readEqualizerLocked(prefs(context))
        }
    }

    /**
     * Reads the stored Equalizer, merging the second switch it used to have into the one. Called with the lock held. The legacy key is
     * removed in the same edit that writes the merged string, so a later start finds nothing to merge (the migration is idempotent); a
     * process that dies before the edit reaches the disk simply merges again, to the same answer.
     */
    private fun readEqualizerLocked(prefs: SharedPreferences) {
        val stored = EqualizerSettingsCodec.decode(prefs.getString(KEY_EQUALIZER, null))
        val legacy = if (prefs.contains(LEGACY_KEY_SYSTEM_EQUALIZER)) prefs.getBoolean(LEGACY_KEY_SYSTEM_EQUALIZER, false) else null
        val choice = EqualizerSwitchMigration.merge(stored, legacy)
        if (legacy != null) {
            val edit = prefs.edit().remove(LEGACY_KEY_SYSTEM_EQUALIZER)
            if (choice != stored) edit.putString(KEY_EQUALIZER, EqualizerSettingsCodec.encode(choice))
            edit.apply()
        }
        _equalizerChoice.value = choice
        publishPlayerEqualizer()
        equalizerLoaded = true
    }

    /** Test-only: forgets that the equalizer was read, so a test can stand where a cold start stands. */
    fun forgetEqualizerLoadedForTesting() {
        synchronized(equalizerLock) { equalizerLoaded = false }
    }

    /** Load the persisted choice once at startup (falls back to defaults on a first run). */
    fun load(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs(context)
        synchronized(equalizerLock) { readEqualizerLocked(prefs) }
        _enabled.value = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        _volume.value = prefs.getFloat(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0f, 1f)
        _previewAudioEnabled.value =
            prefs.getBoolean(KEY_PREVIEW_AUDIO_ENABLED, DEFAULT_PREVIEW_AUDIO_ENABLED)
        _previewAudioMix.value =
            prefs.getFloat(KEY_PREVIEW_AUDIO_MIX, DEFAULT_PREVIEW_AUDIO_MIX).coerceIn(0f, 1f)
        _ambientVolume.value =
            prefs.getFloat(KEY_AMBIENT_VOLUME, DEFAULT_AMBIENT_VOLUME).coerceIn(0f, 1f)
        _navVolume.value = prefs.getFloat(KEY_NAV_VOLUME, DEFAULT_NAV_VOLUME).coerceIn(0f, 1f)
        _musicEnabled.value = prefs.getBoolean(KEY_MUSIC_ENABLED, DEFAULT_MUSIC_ENABLED)
        _musicSource.value = parseMusicSource(prefs.getString(KEY_MUSIC_SOURCE, null))
        _musicOrder.value = parseMusicOrder(prefs.getString(KEY_MUSIC_ORDER, null))
        _songTitlesEnabled.value = prefs.getBoolean(KEY_SONG_TITLES_ENABLED, DEFAULT_SONG_TITLES_ENABLED)
        if (prefs.contains(LEGACY_KEY_PLATFORM_MUSIC_ENABLED)) prefs.edit().remove(LEGACY_KEY_PLATFORM_MUSIC_ENABLED).apply()
        _customMusicTreeUri.value = prefs.getString(KEY_CUSTOM_MUSIC_TREE_URI, null)?.takeIf { it.isNotBlank() }
        _launchSoundEnabled.value = prefs.getBoolean(KEY_LAUNCH_SOUND_ENABLED, DEFAULT_LAUNCH_SOUND_ENABLED)
        _launchSoundVolume.value =
            prefs.getFloat(KEY_LAUNCH_SOUND_VOLUME, DEFAULT_LAUNCH_SOUND_VOLUME).coerceIn(0f, 1f)
        _gameMusicOnHighlight.value =
            prefs.getBoolean(KEY_GAME_MUSIC_ON_HIGHLIGHT, DEFAULT_GAME_MUSIC_ON_HIGHLIGHT)
        _keepMusicInGames.value = prefs.getBoolean(KEY_KEEP_MUSIC_IN_GAMES, DEFAULT_KEEP_MUSIC_IN_GAMES)
        _musicHours.value = parseMusicHours(prefs.getString(KEY_MUSIC_HOURS, null))
        _fadeBetweenTracks.value = prefs.getBoolean(KEY_FADE_BETWEEN_TRACKS, DEFAULT_FADE_BETWEEN_TRACKS)
        _playOutsideMimir.value = prefs.getBoolean(KEY_PLAY_OUTSIDE_MIMIR, DEFAULT_PLAY_OUTSIDE_MIMIR)
        _pauseOnScreenOff.value = prefs.getBoolean(KEY_PAUSE_ON_SCREEN_OFF, DEFAULT_PAUSE_ON_SCREEN_OFF)
        _pauseOnUnplug.value = prefs.getBoolean(KEY_PAUSE_ON_UNPLUG, DEFAULT_PAUSE_ON_UNPLUG)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    fun setVolume(context: Context, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        prefs(context).edit().putFloat(KEY_VOLUME, clamped).apply()
        _volume.value = clamped
    }

    fun setNavVolume(context: Context, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        prefs(context).edit().putFloat(KEY_NAV_VOLUME, clamped).apply()
        _navVolume.value = clamped
    }

    fun setAmbientVolume(context: Context, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        prefs(context).edit().putFloat(KEY_AMBIENT_VOLUME, clamped).apply()
        _ambientVolume.value = clamped
    }

    fun setMusicEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUSIC_ENABLED, enabled).apply()
        _musicEnabled.value = enabled
    }

    fun setMusicSource(context: Context, source: MimirMusicSource) {
        prefs(context).edit().putString(KEY_MUSIC_SOURCE, source.name).apply()
        _musicSource.value = source
    }

    fun setMusicOrder(context: Context, order: MimirMusicOrder) {
        prefs(context).edit().putString(KEY_MUSIC_ORDER, order.name).apply()
        _musicOrder.value = order
    }

    fun setSongTitlesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SONG_TITLES_ENABLED, enabled).apply()
        _songTitlesEnabled.value = enabled
    }

    /** A blank value is a null: the key is removed, so "no folder" has exactly one stored shape. */
    fun setCustomMusicTreeUri(context: Context, treeUri: String?) {
        val value = treeUri?.takeIf { it.isNotBlank() }
        prefs(context).edit().also { editor ->
            if (value == null) editor.remove(KEY_CUSTOM_MUSIC_TREE_URI) else editor.putString(KEY_CUSTOM_MUSIC_TREE_URI, value)
        }.apply()
        _customMusicTreeUri.value = value
    }

    fun setLaunchSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LAUNCH_SOUND_ENABLED, enabled).apply()
        _launchSoundEnabled.value = enabled
    }

    fun setLaunchSoundVolume(context: Context, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        prefs(context).edit().putFloat(KEY_LAUNCH_SOUND_VOLUME, clamped).apply()
        _launchSoundVolume.value = clamped
    }

    fun setGameMusicOnHighlight(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GAME_MUSIC_ON_HIGHLIGHT, enabled).apply()
        _gameMusicOnHighlight.value = enabled
    }

    fun setKeepMusicInGames(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_MUSIC_IN_GAMES, enabled).apply()
        _keepMusicInGames.value = enabled
    }

    fun setMusicHours(context: Context, hours: MimirMusicHours) {
        prefs(context).edit().putString(KEY_MUSIC_HOURS, hours.name).apply()
        _musicHours.value = hours
    }

    fun setFadeBetweenTracks(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FADE_BETWEEN_TRACKS, enabled).apply()
        _fadeBetweenTracks.value = enabled
    }

    fun setPlayOutsideMimir(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PLAY_OUTSIDE_MIMIR, enabled).apply()
        _playOutsideMimir.value = enabled
    }

    fun setPauseOnScreenOff(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSE_ON_SCREEN_OFF, enabled).apply()
        _pauseOnScreenOff.value = enabled
    }

    fun setPauseOnUnplug(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSE_ON_UNPLUG, enabled).apply()
        _pauseOnUnplug.value = enabled
    }

    // An unknown or missing value is the default, not an error: a downgrade or a hand-edited file must
    // not leave the player with a Music card that cannot read its own choice.
    internal fun parseMusicSource(raw: String?): MimirMusicSource =
        MimirMusicSource.entries.firstOrNull { it.name == raw } ?: DEFAULT_MUSIC_SOURCE

    internal fun parseMusicOrder(raw: String?): MimirMusicOrder =
        MimirMusicOrder.entries.firstOrNull { it.name == raw } ?: DEFAULT_MUSIC_ORDER

    internal fun parseMusicHours(raw: String?): MimirMusicHours =
        MimirMusicHours.entries.firstOrNull { it.name == raw } ?: DEFAULT_MUSIC_HOURS

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
