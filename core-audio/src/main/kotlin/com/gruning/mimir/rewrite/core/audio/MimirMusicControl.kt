package com.gruning.mimir.rewrite.core.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Your music's control facade and its Android arbitration.
 *
 * **Who plays.** One Media3 player ([YourMusicPlayback], owned by the process and wrapped by [MimirMusicService]) plays Your music, in Mimir and out of it:
 * the track is never handed to another player, so moving between Mimir and other apps never restarts it. The engine
 * ([MimirFrontendMusic]) decides that it plays and at what level; what changes for a folder plan is only who may silence it
 * ([FrontendMusicPolicyInput.everywhere]). [MimirMusicService] is also what lets it keep playing with Mimir in the background (a
 * foreground service, a media session, a media notification). The theme beds stay on the platform engine and are Mimir-only.
 *
 * **Who may pause it** ([MusicPausePolicy]): another app taking audio focus (not while a game is in front), the screen going off,
 * the headphones being unplugged, and the player. Screen-on resumes only what screen-off paused; unplugged headphones never
 * auto-resume. A transient focus loss resumes on its GAIN. After a PERMANENT loss Your music resumes only when the other player is
 * confirmed paused or stopped (its media session reads so for 10 s, which needs notification access); without that access it stays
 * paused until the player presses play.
 *
 * **Mimir takes over with one music player at a time.** The theme bed and Your music are both Mimir's music, and
 * the same arbitration serves both. A DELIBERATE start (the player's play, turning Music on, choosing the source: [playPause],
 * [onMusicTurnedOn], [onSourceChosen], [onDeliberateStart]) asks for audio focus whichever source plays, so the app that was playing pauses itself. A passive start (a cold start,
 * the return to Mimir, screen-on, any reconcile) never takes audio from a player: Your music yields to one that is audible, and the bed
 * plays without focus unless the player started it. While Mimir's music plays, another app starting pauses it: through the focus loss
 * when Mimir holds focus, and, for the unfocused bed, through the stranger-audio signal ([MusicPausePolicy.yieldsToStartedPlayer]) and
 * the courtesy gate. Unchanged: "Play outside Mimir", letting go of focus for a game in front, call interruption, the music hours, and
 * no focus at all at level zero.
 *
 * **What the panel reads and drives:** [state], [musicPanelAvailable], [musicVolume], [mediaVolume], [musicCappedByMedia] and the
 * commands. The panel UI is not here. Another app's player is offered only through an [OtherPlayerSource] the app supplies (the
 * companion's media-session bridge, which needs the notification-access grant); [state] carries both players when both exist.
 *
 * **Volume.** Music is the PERCEIVED level M ([musicVolume]); Game & media is the STREAM_MUSIC level S ([mediaVolume]). Your music's
 * player gain is min(1, M / S) ([FrontendMusicPolicy.compensatedGain]), recomputed on every change of either, so lowering Game &
 * media keeps Your music where it is until S reaches M. It does not apply to another player, whose audio is inside the stream.
 */
object MimirMusicControl {
    private const val Tag = "MimirMusicControl"
    private const val VolumeChangedAction = "android.media.VOLUME_CHANGED_ACTION"
    private const val OtherRiseMs = 700L
    private const val OtherFallMs = 1_000L

    /** The look at an edge of the music hours is a second late, so it lands past the edge and not on it. */
    private const val ScheduleSlackMs = 1_000L

    /** The longest the clock goes unlooked-at while music hours are set. */
    private const val ScheduleLookMs = 10 * 60_000L

    /** A failed child logs loudly and never takes the process down; the facade is process-lifetime. */
    private val failureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e(Tag, "music control failed in $context", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failureHandler)

    // --- Written by the engine -------------------------------------------------------------------------------------------

    private val _everywhere = MutableStateFlow(false)

    /** The engine's plan is a folder: Your music is the player and plays everywhere. */
    internal val everywhere: StateFlow<Boolean> = _everywhere.asStateFlow()
    private val _sounding = MutableStateFlow(false)

    private val _bedWantsFocus = MutableStateFlow(false)

    private val _bedTakeover = MutableStateFlow(false)

    /**
     * The player started the theme bed on purpose and it has not been ended since ([BedTakeover]): the courtesy gate to other music does not hold it, so it sounds over an app that holds no audio focus. A call is not waived.
     */
    internal val bedTakeover: StateFlow<Boolean> = _bedTakeover.asStateFlow()

    /** Sets the waiver and says so in the log when it changes, so a device run can see the player's start outrank the courtesy gate, and the gate come back. */
    private fun setBedTakeover(value: Boolean) {
        if (_bedTakeover.value == value) return
        _bedTakeover.value = value
        Log.i(Tag, if (value) "the theme bed plays over other audio: the player started it" else "the theme bed meets the courtesy gate again")
    }

    internal fun setEverywhere(value: Boolean) {
        if (_everywhere.value == value) return
        _everywhere.value = value
        // Which source a PERMANENT hold belongs to decides how it ends ([MusicPausePolicy.otherPlayerQuiet]): look again.
        handler?.post { reconsiderPermanentHold() }
    }

    internal fun setSounding(value: Boolean) {
        _sounding.value = value
    }

    /**
     * The theme bed is the plan and is on its way to sound ([FrontendMusicPolicy.bedWantsToSound]): the engine's word, whatever
     * else is playing. It is what lets [reconcile] hold audio focus for the bed after the player starts it.
     */
    internal fun setBedWantsFocus(value: Boolean) {
        _bedWantsFocus.value = value
    }

    // --- Pause state -----------------------------------------------------------------------------------------------------

    private val pauseLock = Any()
    private val pauseState = MutableStateFlow(MusicPauseState())
    private val _pauseReason = MutableStateFlow<MusicPauseReason?>(null)

    /**
     * Why Mimir's own sound is held, or null: the ONE answer to "may Mimir's own sound play right now" (screen off, which a closed lid is;
     * headphones unplugged; another app or a call holding the audio; the player's own pause; and, last, the music hours). The game's
     * opening sound on the launch card follows this (a closed lid previously left the card's sound looping), and it
     * has no pause rules of its own. Public for that reader only; nothing outside writes it.
     *
     * It is the raw answer: a screen-off is in it whether or not "Pause when screen is off" is on, because a closed lid is a
     * closed lid for the launch card either way. Your music itself obeys [yoursPauseReason], which differs from this by that one setting.
     */
    val pauseReason: StateFlow<MusicPauseReason?> = _pauseReason.asStateFlow()

    private val _yoursPauseReason = MutableStateFlow<MusicPauseReason?>(null)

    /**
     * Why Your music itself is paused: [pauseReason] less a screen-off that "Pause when screen is off" (Off) lets it play
     * through. The engine's policy and the panel read this one.
     */
    internal val yoursPauseReason: StateFlow<MusicPauseReason?> = _yoursPauseReason.asStateFlow()

    private val _scheduleBlocked = MutableStateFlow(false)

    /**
     * The time of day is outside the music hours. A flag of its own because it holds the theme bed as well as Your music, and the
     * single reason in [yoursPauseReason] gives way to any real hold (see [MusicPauseState.reason]).
     */
    internal val scheduleBlocked: StateFlow<Boolean> = _scheduleBlocked.asStateFlow()

    private val _otherAppHeld = MutableStateFlow(false)

    /**
     * Another app (or a call) holds the audio ([MusicPausePolicy.heldByOtherApp]). The theme bed obeys this and nothing else of the
     * pause state: [yoursPauseReason] names the player's pause or the unplugged cable first, which the bed has no way to clear.
     */
    internal val otherAppHeld: StateFlow<Boolean> = _otherAppHeld.asStateFlow()

    /** The pause state as Your music must obey it right now: the raw state, less a screen-off the player let it play through. */
    private fun yoursPause(state: MusicPauseState = pauseState.value): MusicPauseState =
        MusicPausePolicy.forYours(state, AudioPreferences.pauseOnScreenOff.value)

    // --- Public facade ---------------------------------------------------------------------------------------------------

    /** Your music is the source, it is playable and the Music switch is on. The service and the notification ask follow this. */
    val yourMusicActive: StateFlow<Boolean> = combine(_everywhere, AudioPreferences.musicEnabled) { everywhere, enabled ->
        everywhere && enabled
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    private val otherPlayerSource = MutableStateFlow<OtherPlayerSource?>(null)
    private val _otherPlayer = MutableStateFlow<OtherPlayerSnapshot?>(null)
    private val _otherAccess = MutableStateFlow(false)

    /**
     * How many tracks Your music plays from (the folder's root tracks, as the player is given them). The panel disables Previous and
     * Next at one: a single track has nothing to skip to. Zero while no folder is indexed. It reaches the panel as [YourMusicState.canPrevious] and [YourMusicState.canNext].
     */
    private val yourMusicTrackCount: StateFlow<Int> =
        MimirMusicLibrary.playlist.map { FrontendMusicSourceResolver.generalTracks(it.tracks).size }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /** The pause is another app's permanent hold that only the player's play can end (no notification access to confirm it stopped). */
    private val pauseNeedsPlay = combine(pauseState, _otherAccess, MusicPausePolicy::holdNeedsPlay).distinctUntilChanged()

    private class PanelAux(
        val volume: Float,
        val other: OtherPlayerSnapshot?,
        val trackCount: Int,
        val otherAccess: Boolean,
        val pauseNeedsPlay: Boolean,
        val format: TrackFormat?,
        val timing: YourMusicTiming?,
    )

    private class PanelAuxCore(
        val volume: Float,
        val other: OtherPlayerSnapshot?,
        val trackCount: Int,
        val otherAccess: Boolean,
        val pauseNeedsPlay: Boolean,
    )

    private val inputs = combine(
        yourMusicActive,
        _sounding,
        MimirFrontendMusic.nowPlaying,
        _yoursPauseReason,
        combine(
            combine(AudioPreferences.ambientVolume, _otherPlayer, yourMusicTrackCount, _otherAccess, pauseNeedsPlay, ::PanelAuxCore),
            YourMusicFormatTracker.format,
            YourMusicTimingFeed.timing,
        ) { core, format, timing -> PanelAux(core.volume, core.other, core.trackCount, core.otherAccess, core.pauseNeedsPlay, format, timing) },
    ) { active, sounding, nowPlaying, pause, aux ->
        MusicControlInputs(
            yourMusicActive = active,
            yourMusicSounding = sounding,
            title = nowPlaying?.takeIf { it.source == MimirMusicSource.CUSTOM }?.title,
            trackId = nowPlaying?.takeIf { it.source == MimirMusicSource.CUSTOM }?.trackId,
            artist = nowPlaying?.takeIf { it.source == MimirMusicSource.CUSTOM }?.artist,
            bedTitle = nowPlaying?.takeIf { it.source == MimirMusicSource.MIMIR }?.title,
            pause = pause,
            musicVolume = aux.volume,
            other = aux.other,
            yourMusicTrackCount = aux.trackCount,
            otherAccess = aux.otherAccess,
            pauseNeedsPlay = aux.pauseNeedsPlay,
            format = aux.format.takeIf { nowPlaying?.source == MimirMusicSource.CUSTOM },
            timing = aux.timing.takeIf { nowPlaying?.source == MimirMusicSource.CUSTOM },
        )
    }

    private var lastPlayed = MusicSource.YOUR_MUSIC

    /** What the music panel shows. See [MusicControlState]. */
    val state: StateFlow<MusicControlState> =
        inputs.map(::deriveMusicControlState)
            .map { derived ->
                // One collector, one thread at a time: the held value needs no lock.
                lastPlayed = musicLastPlayed(lastPlayed, derived)
                derived.copy(lastPlayed = lastPlayed)
            }
            .stateIn(scope, SharingStarted.Eagerly, MusicControlState())

    /**
     * The M-tap rule's input: Your music is the active source and is playing or paused (by the player, another app, the screen or
     * the headphones), OR another player's session is playing or paused. False when the music is off or stopped, when the theme bed
     * is what plays, and when no session is offered. The M button itself is unchanged.
     */
    val musicPanelAvailable: StateFlow<Boolean> =
        inputs.map(::musicPanelAvailable).distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    /** Supplies (or, with null, withdraws) the other-player bridge. Set by the app, which owns the notification-access grant. */
    fun setOtherPlayerSource(source: OtherPlayerSource?) {
        otherPlayerSource.value = source
    }

    /** Music volume: the existing music level ([AudioPreferences.ambientVolume]), applied as the player's gain. 0..1. */
    val musicVolume: StateFlow<Float> get() = AudioPreferences.ambientVolume

    private val _mediaVolume = MutableStateFlow(0f)

    /**
     * Android's STREAM_MUSIC (what games and other media play on), as a 0..1 fraction of its steps. Kept current from the system's
     * volume broadcasts. The panel's "Game & media" slider.
     */
    val mediaVolume: StateFlow<Float> = _mediaVolume.asStateFlow()

    /**
     * Game & media is below the Music level, so Your music's perceived level is held under what the player set (its gain is already at
     * full). The panel says so. Meaningless for another player, which the Music slider does not govern.
     */
    val musicCappedByMedia: StateFlow<Boolean> =
        combine(AudioPreferences.ambientVolume, _mediaVolume, FrontendMusicPolicy::musicCappedByMedia)
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Which player a command drives. [target] is the panel card the player pressed; with none, Your music when it is there, else
     * another player's session, else Your music (the engine's own play over nothing). [yourMusicOnly] (the service's media keys and
     * notification) is Your music or nothing: Mimir's session never drives another app.
     */
    private fun commandTarget(target: MusicSource?, yourMusicOnly: Boolean): MusicSource {
        val now = state.value
        return when {
            yourMusicOnly -> if (now.yours != null) MusicSource.YOUR_MUSIC else MusicSource.NONE
            target != null -> target
            now.yours != null -> MusicSource.YOUR_MUSIC
            now.other != null -> MusicSource.OTHER_PLAYER
            else -> MusicSource.YOUR_MUSIC
        }
    }

    /**
     * Play or pause [target]'s player (default: see [commandTarget]). For Your music, while it is paused for any reason this is an
     * explicit take-over: every hold clears. For another player it is that session's own play or pause.
     */
    fun playPause(target: MusicSource? = null, yourMusicOnly: Boolean = false) = post("playPause") {
        val which = commandTarget(target, yourMusicOnly)
        if (which == MusicSource.NONE) return@post
        if (which == MusicSource.OTHER_PLAYER) {
            // One player sounds at a time: a play on this card over sounding Your music pauses Your music first, with the hold a
            // permanent focus loss sets (not a user pause), so the 10 s confirmed-paused resume rule covers it in and out of games.
            val source = otherPlayerSource.value
            if (MusicPausePolicy.pausesYoursOnOtherPlay(_otherAccess.value, _otherPlayer.value, yoursSoundingNow())) {
                mutatePause("other player play pressed", MusicPausePolicy::onOtherPlayerPlayWhileYoursSounds)
            }
            source?.playPause()
            return@post
        }
        if (!yourMusicActive.value) return@post
        // Media3 lets Android demote an idle service; a play is the moment Mimir may be on top (an overlay tap over a game), so it is
        // also the moment to make sure the service is up and in the foreground.
        startServiceIfWanted()
        // One player sounds at a time: a play of Your music pauses the other player's session when it is playing (grant held only).
        if (MusicPausePolicy.pausesOtherOnYoursPlay(yoursPause(), _otherAccess.value, _otherPlayer.value)) {
            otherPlayerSource.value?.playPause()
        }
        // A deliberate play over a hold takes the music back at once; the start courtesy is only for starts Mimir chose.
        if (yoursPause().paused) deliberate.mark(SystemClock.uptimeMillis()) else deliberate.spend()
        val heldForHours = pauseState.value.schedule
        mutatePause("user play/pause") { MusicPausePolicy.onUserPlayPause(it, yoursPaused = yoursPause(it).paused) }
        // A play over the off-hours takes the music until the next edge of the hours, an instant fixed now (see MusicSchedulePolicy.look).
        if (heldForHours && !pauseState.value.schedule) {
            scheduleWatch = MusicSchedulePolicy.overrideStarted(
                scheduleWatch,
                AudioPreferences.musicHours.value,
                System.currentTimeMillis(),
                ZoneId.systemDefault(),
            )
        }
    }

    /** Your music is audibly playing now: on, the engine sounding, and no pause flag set. */
    private fun yoursSoundingNow(): Boolean = yourMusicActive.value && _sounding.value && !yoursPause().paused

    fun next(target: MusicSource? = null, yourMusicOnly: Boolean = false) = post("next") {
        when (commandTarget(target, yourMusicOnly)) {
            MusicSource.OTHER_PLAYER -> otherPlayerSource.value?.next()
            MusicSource.YOUR_MUSIC -> if (yourMusicActive.value) MimirFrontendMusic.skipToNext()
            MusicSource.NONE -> Unit
        }
    }

    fun previous(target: MusicSource? = null, yourMusicOnly: Boolean = false) = post("previous") {
        when (commandTarget(target, yourMusicOnly)) {
            MusicSource.OTHER_PLAYER -> otherPlayerSource.value?.previous()
            MusicSource.YOUR_MUSIC -> if (yourMusicActive.value) MimirFrontendMusic.skipToPrevious()
            MusicSource.NONE -> Unit
        }
    }

    /**
     * Moves Your music's track on air to [positionMs] (the seek bar's release). Your music only: another player's seek goes through its own session. Ignored while Your
     * music is not the active source; the player clamps the position into the track.
     */
    fun seekYourMusic(positionMs: Long) = post("seekYourMusic") {
        if (yourMusicActive.value) YourMusicPlayback.seekTo(positionMs)
    }

    /** Sets the music level (the player's gain), persisted with the other audio preferences. */
    fun setMusicVolume(fraction: Float) {
        val context = appContext ?: return
        AudioPreferences.setAmbientVolume(context, fraction)
    }

    /**
     * Sets Android's STREAM_MUSIC to [fraction] of its steps, without the system volume UI. A drag calls this per pointer event, so
     * the binder calls run on the control's worker and a burst collapses to its latest fraction instead of queueing behind the main
     * thread; [mediaVolume] follows once the stream has moved.
     */
    fun setMediaVolume(fraction: Float) {
        wantedMediaFraction = fraction.coerceIn(0f, 1f)
        val worker = handler ?: return
        worker.removeCallbacks(applyMediaVolume)
        worker.post(applyMediaVolume)
    }

    @Volatile
    private var wantedMediaFraction = 0f

    private val applyMediaVolume = Runnable {
        val manager = audioManager ?: return@Runnable
        val fraction = wantedMediaFraction
        runCatching {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val target = (fraction * max).roundToInt()
            // A drag reports every pointer event, most of them on the step the stream is already at: write only when the step changes.
            if (StreamVolumeWrite.shouldWrite(manager.getStreamVolume(AudioManager.STREAM_MUSIC), target)) {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
        }.onFailure { Log.w(Tag, "media volume change refused", it) }
        readMediaVolume()
    }

    /**
     * What the app knows about a game, in three parts the control composes with its own word on whether Main is resumed
     * ([MusicPausePolicy.gameInFront]). [sessionLive] is cheap: a Mimir game session exists (launched, on screen, handed to the second
     * screen, or PARKED behind Mimir by HOME). [onScreen] is cheap evidence: Mimir's launch is armed, a live game is on the second screen, a hand-back
     * to a parked game is under way, or the foreground monitor saw a game package. [foreground] is the costly one (UsageStats and an AppOps check): the package
     * in front right now is a known emulator package. It is asked only when nothing cheaper has answered. None is remembered: each is read at the moment a
     * decision needs it.
     */
    class GameFrontProbe(val sessionLive: () -> Boolean, val onScreen: () -> Boolean, val foreground: () -> Boolean)

    /**
     * Tells the control whether a game is in front, from what the app knows ([GameFrontProbe]). A live session alone is not "in front":
     * a game parked behind Mimir (HOME, or switched away) keeps its session, and with Mimir on screen it is not on screen itself. Set by
     * the app, which owns the session ledger and the foreground monitor.
     */
    fun setGameInFrontProbe(probe: GameFrontProbe?) {
        gameProbe = probe
    }

    /** The game state may have changed (a session started or ended, a foreign app moved in front, a parking lease changed): reconsider focus. */
    fun onGameFrontMayHaveChanged() = post("game front changed") { reconcile() }

    /**
     * Mimir's Main Activity is resumed, or paused (its onResume and onPause, not the started edge): the one word the game-in-front composition takes
     * on whether Mimir is on screen. A game coming back over Mimir (Resume from a HOME park, Recents, the quick switch) pauses Main BEFORE the
     * game's own onResume asks for audio focus, so Mimir lets go in time; the started edge ([onAppBackgrounded]) comes after the game is up.
     */
    fun onMainResumed(resumed: Boolean) {
        mainResumed = resumed
        onGameFrontMayHaveChanged()
    }

    /** Written by [onMainResumed] (any thread), read only by the game-in-front composition. */
    @Volatile
    private var mainResumed = false

    /**
     * The player started Mimir's music on purpose, of either source (choosing the source is [onSourceChosen], turning Music on is [onMusicTurnedOn]). Starting playback deliberately takes audio focus. The audio focus request that follows is not met by the courtesy: it is made whoever is playing, so that app
     * pauses itself, and another app's standing hold on Mimir's music ends. The mark is spent by that request, and runs out within
     * [DeliberateStartWindowMs] if the music is not wanted just then (Music off, level zero, a game in front). Passive starts must not
     * call this: they never take audio from a player. The play of Your music (panel, notification, media keys) is [playPause], which marks it.
     * Call it AFTER the preference is written; the flows that carry it to [reconcile] may land after this runs, and the mark waits for them.
     */
    fun onDeliberateStart() = startDeliberately("deliberate start", MusicPausePolicy::onDeliberateStart)

    /**
     * The player turned Music ON in Settings: a deliberate play ([onDeliberateStart]) that also ends the player's own earlier pause, so Music
     * on means it plays. Turning it Off is not a start and does not call this.
     */
    fun onMusicTurnedOn() = startDeliberately("music turned on", MusicPausePolicy::onMusicTurnedOn)

    /**
     * The player chose the music source in Settings: a deliberate play ([onDeliberateStart]) that, like turning Music on, also ends the player's
     * own earlier pause, so choosing Your music after pressing pause plays it, as the theme bed (which ignores that pause) always did. Call it
     * AFTER the preference is written. The cable, the screen, the music hours and a call still hold.
     */
    fun onSourceChosen() = startDeliberately("source chosen", MusicPausePolicy::onSourceChosen)

    /**
     * The player picked [tree] as the music folder (Settings > Music folder); this hands it to the library too ([MimirMusicLibrary.setTree]), so the
     * order of the three steps is the control's. Picking a folder is choosing Your music and playing it: the pick is recorded first, the library is given the folder, and only THEN, with Mimir's music as the source, the source is switched
     * to Your music through the setter Settings' source choice uses ([AudioPreferences.setMusicSource]). Switching it before the library has the new
     * folder would leave a moment in which the plan is the OLD folder, started passively. The pick is a deliberate start, marked when the plan flips
     * to the picked folder, however long the scan takes ([PickedFolder], [onPlanIsFolder]); it is not made before then, because until the folder has
     * tracks the plan is not the folder. A folder with no tracks leaves the source on Your music with the theme bed as its fallback.
     */
    fun onFolderPicked(context: Context, tree: String) {
        pickedFolder.pick(tree)
        MimirMusicLibrary.setTree(context, tree)
        if (AudioPreferences.musicSource.value != MimirMusicSource.CUSTOM) AudioPreferences.setMusicSource(context, MimirMusicSource.CUSTOM)
    }

    /** The engine's plan is now the folder [tree]: if it is the one the player just picked, that is the start, and picking the folder plays it. It ends what choosing a source ends ([MusicPausePolicy.onFolderChosen]): another app's hold and the
     * player's own earlier pause. The cable, the screen, the music hours and a call still hold. */
    internal fun onPlanIsFolder(tree: String) {
        if (pickedFolder.playedNow(tree)) startDeliberately("folder chosen", MusicPausePolicy::onFolderChosen)
    }

    private val pickedFolder = PickedFolder()

    private fun startDeliberately(why: String, ends: (MusicPauseState) -> MusicPauseState) {
        // Not attached: nothing can be playing, so there is nothing to take over from.
        if (handler == null) return
        post(why) {
            Log.i(Tag, "$why: Mimir's music takes the audio over from another app")
            deliberate.mark(SystemClock.uptimeMillis())
            mutatePause(why, ends)
            reconcile()
        }
    }

    /**
     * Mimir's main screen is in front. Written at the app's own edges, read by [reconcile]. Only "Play outside Mimir" (Off) looks at it:
     * the default wants Your music wherever it is.
     */
    private val inMimir = MutableStateFlow(false)

    private fun placementAllows(): Boolean = AudioPreferences.playOutsideMimir.value || inMimir.value

    internal fun onAppForegrounded() {
        inMimir.value = true
        post("app foregrounded") {
            // The stream level may have moved while nothing was listening for it; the gain follows it.
            readMediaVolume()
            // A boundary of the music hours may have passed while the process slept; the wall clock is looked at again.
            evaluateSchedule(force = false)
            reconcile()
            startServiceIfWanted()
        }
    }

    /** Mimir's main screen left the front (or a game's opening took the moment): with "Play outside Mimir" Off, focus is let go. */
    internal fun onAppBackgrounded() {
        inMimir.value = false
        post("app backgrounded") { reconcile() }
    }

    // --- Android ---------------------------------------------------------------------------------------------------------

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var audioManager: AudioManager? = null

    @Volatile
    private var handler: Handler? = null

    @Volatile
    private var gameProbe: GameFrontProbe? = null

    /** Watches the audio mode for a call while Your music is wanted (handler thread). Created at attach. */
    private var callWatch: MusicCallWatch? = null

    // Handler-thread state.
    private var focusHeld = false

    /** The player started the music on purpose a moment ago ([onDeliberateStart], [playPause]); spent by the focus request it leads to. */
    private val deliberate = DeliberateStart()

    /** Another app is playing music, settled (see [sampleOtherPlayers]); games and emulators are not music. It only gates Mimir's own focus decisions. */
    private val _strangerAudio = MutableStateFlow(false)
    private var strangerHeading = false

    /**
     * How long the other player's session has read paused or stopped (handler thread). A PERMANENT hold ends only when that has
     * lasted [OtherPlayerConfirmedPausedMs] without a break, and only with the media-session grant (see [reconsiderPermanentHold]).
     * Nothing the playback-configuration heuristic sees ends a hold: a player that goes quiet while it buffers is still playing.
     */
    private val confirmedPaused = ConfirmedPausedStreak()
    private val reconsiderPermanentHoldTask = Runnable { reconsiderPermanentHold() }

    private val publishStranger = Runnable {
        if (_strangerAudio.value == strangerHeading) return@Runnable
        _strangerAudio.value = strangerHeading
        // A newcomer that started playing after the player's start ends the take-over for good, whether or not Mimir holds focus: it may have asked for none,
        // and the yield below reaches only music that holds none (a bed that does hold it would otherwise play over it, past the courtesy gate).
        if (strangerHeading) {
            deliberate.spend()
            setBedTakeover(false)
        }
        // Only a rise matters here. The fall is not a resume: another player that went quiet for a moment (YouTube while it buffers or
        // switches screens, Nova 13:38) has not stopped, and Your music taking focus back paused it.
        val look = gameLook()
        if (strangerHeading && MusicPausePolicy.yieldsToStartedPlayer(focusHeld, _sounding.value, pauseState.value, look.value)) {
            // Your music kept playing without focus (a game took it and was exempt) and a real media player has now started: its
            // focus request reached no Mimir listener, so Mimir yields by hand instead of playing over it.
            Log.i(Tag, "another player started while Mimir's music holds no focus: it waits for it")
            mutatePause("other player started without focus", MusicPausePolicy::onStartCourtesy)
        }
        reconcile(look)
        // The theme bed's PERMANENT hold ends when no other player has been audible for a while ([MusicPausePolicy.otherPlayerQuiet]).
        reconsiderPermanentHold()
    }

    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /**
     * Whether a game is on screen now ([MusicPausePolicy.gameInFront]): direct evidence, or a live session with Mimir off the front. A
     * game parked behind Mimir is neither, so the player's own start takes the audio then. The cheap term is read first; the UsageStats
     * query only when it could change the answer.
     */
    private fun gameInFront(): Boolean = runCatching {
        val probe = gameProbe ?: return@runCatching false
        MusicPausePolicy.gameInFront(sessionLive = probe.sessionLive(), mimirInFront = mainResumed, evidence = { probe.onScreen() || probe.foreground() })
    }.getOrDefault(false)

    /**
     * [gameInFront] by the cheap terms only (no UsageStats): the media bed is told this at every pass, so a game fronted from its tab, from Recents or by
     * the quick switch, none of which arms a launch, stops being discounted ([MimirMediaBed.noteGameFront]).
     */
    private fun gameOnScreenCheap(): Boolean = runCatching {
        val probe = gameProbe ?: return@runCatching false
        MusicPausePolicy.gameInFront(sessionLive = probe.sessionLive(), mimirInFront = mainResumed, evidence = probe.onScreen)
    }.getOrDefault(false)

    /**
     * One look at [gameInFront], taken the first time a decision needs it and not again: a reconcile pass or a focus callback takes one and hands it
     * down, because the UsageStats read inside it is the costly part and was being made 1 to 3 times a pass. Handler thread only.
     */
    private fun gameLook(): Lazy<Boolean> = lazy(LazyThreadSafetyMode.NONE) { gameInFront() }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun attach(context: Context) {
        synchronized(pauseLock) {
            if (appContext != null) return
            appContext = context
        }
        // Your music's player is the process's, built with this context the first time a playlist needs it.
        YourMusicPlayback.install(context)
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager
        val thread = HandlerThread("MimirMusicControl").also { it.start() }
        val worker = Handler(thread.looper)
        handler = worker
        readMediaVolume()

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null && !power.isInteractive) {
            mutatePause("screen already off") { MusicPausePolicy.onScreen(it, on = false) }
        }
        registerSystemReceiver(
            context,
            worker,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        ) { intent ->
            readMediaVolume()
            val on = intent.action == Intent.ACTION_SCREEN_ON
            mutatePause("screen ${intent.action}") { MusicPausePolicy.onScreen(it, on = on) }
            // A device that slept through a boundary of the music hours wakes here.
            if (on) evaluateSchedule(force = false)
        }
        registerSystemReceiver(
            context,
            worker,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            },
        ) { intent ->
            // The process keeps the zone it started with until told: read the new one before looking at the wall clock.
            if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) TimeZone.setDefault(null)
            evaluateSchedule(force = false)
        }
        registerSystemReceiver(context, worker, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) {
            // "Pause if headphones unplug" Off: the event is not recorded, so nothing holds the music and nothing waits for a play.
            if (AudioPreferences.pauseOnUnplug.value) {
                mutatePause("headphones unplugged", MusicPausePolicy::onNoisy)
            } else {
                Log.i(Tag, "headphones unplugged: the player lets Your music play on")
            }
        }
        registerSystemReceiver(context, worker, IntentFilter(VolumeChangedAction)) { readMediaVolume() }
        runCatching { manager.registerAudioPlaybackCallback(playbackCallback, worker) }
            .onFailure { Log.e(Tag, "could not watch other audio; Your music will not wait for it at start", it) }
        worker.post { resampleOtherPlayers() }

        scope.launch { MimirMediaBed.activeOwnPlaybackTokens.collect { worker.post { resampleOtherPlayers() } } }
        scope.launch {
            // A game Mimir launched keeps its stream running behind Mimir: the media bed tells it from other music at the return (MimirKeptGameStreams).
            var gameInFrontBefore = false
            MimirAudioFocus.state.collect { focus ->
                val gameInFrontNow = focus is AudioFocusState.Suspended
                if (gameInFrontNow && !gameInFrontBefore) MimirMediaBed.noteGameLaunchArmed()
                if (!gameInFrontNow && gameInFrontBefore) {
                    MimirMediaBed.noteReturnedFromGame(strangerSessionPlaying = _otherAccess.value && _otherPlayer.value?.isPlaying == true)
                }
                gameInFrontBefore = gameInFrontNow
                // The launch and return notes set the media bed's front flag by Suspended alone; the next pass puts it back to what is on screen (a Bottom game still is).
                post("kept game edge") { reconcile() }
            }
        }
        scope.launch {
            // Focus is wanted or not; the level only matters at zero, so a slider tick is not a reconcile. Where Your music is allowed to
            // be ("Play outside Mimir") is part of whether focus is wanted.
            val audible = AudioPreferences.ambientVolume.map { it > 0f }.distinctUntilChanged()
            val placed = combine(AudioPreferences.playOutsideMimir, inMimir) { outside, inside -> outside || inside }.distinctUntilChanged()
            // The theme bed's own want (the engine's word) is part of it: the bed asks for focus when the player starts it.
            combine(yourMusicActive, _bedWantsFocus, audible, placed) { active, bed, loud, placement -> listOf(active, bed, loud, placement) }
                .collect { worker.post { reconcile() } }
        }
        scope.launch {
            // "Pause when screen is off" changed: what Your music obeys changes with it, and so does whether it wants focus.
            AudioPreferences.pauseOnScreenOff.collect {
                synchronized(pauseLock) { _yoursPauseReason.value = yoursPause().reason }
                worker.post { reconcile() }
            }
        }
        scope.launch {
            // The music hours: a different window is looked at again at once (an edge it is already past is applied), and the clock
            // is looked at again at its next edge. The first value is the stored one, which is the look at attach.
            AudioPreferences.musicHours.collect { worker.post { evaluateSchedule(force = true) } }
        }
        callWatch = MusicCallWatch(AndroidAudioModePort(manager, worker)) { inCall ->
            // A call holds the music whether or not Mimir holds focus (a game in front means it holds none).
            mutatePause("call mode inCall=$inCall") { MusicPausePolicy.onCall(it, inCall) }
        }
        scope.launch {
            // "Pause during calls": the audio mode is watched only while Your music is wanted, so there is no listener or wakeup otherwise.
            yourMusicActive.collect { active -> worker.post { callWatch?.setWanted(MusicCallPolicy.watches(active)) } }
        }
        scope.launch {
            // Never stopService() from here: a stop that lands before the service's onCreate ran startForeground kills the process
            // ("did not then call startForeground"). The service stops itself once it is in the foreground and Your music is gone.
            yourMusicActive.collect { active -> if (active) startServiceIfWanted() }
        }
        // The service going foreground is what lets a focus request succeed outside Mimir: a refused one is asked again here.
        scope.launch { MimirMusicService.foreground.collect { up -> if (up) worker.post { reconcile() } } }
        scope.launch {
            otherPlayerSource.flatMapLatest { it?.player ?: flowOf(null) }.collect {
                _otherPlayer.value = it
                worker.post {
                    resampleOtherPlayers()
                    reconsiderPermanentHold()
                }
            }
        }
        scope.launch {
            otherPlayerSource.flatMapLatest { it?.sessionAccessGranted ?: flowOf(false) }.collect {
                _otherAccess.value = it
                worker.post {
                    resampleOtherPlayers()
                    reconsiderPermanentHold()
                }
            }
        }
    }

    private fun registerSystemReceiver(context: Context, worker: Handler, filter: IntentFilter, onReceive: (Intent) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching { onReceive(intent) }.onFailure { Log.e(Tag, "receiver for ${intent.action} failed", it) }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, null, worker, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter, null, worker)
            }
        }.onFailure { Log.e(Tag, "could not register for ${filter.getAction(0)}", it) }
    }

    private fun post(what: String, block: () -> Unit) {
        val target = handler
        if (target == null) {
            Log.w(Tag, "$what ignored: the music control is not attached yet")
            return
        }
        target.post { runCatching(block).onFailure { Log.e(Tag, "$what failed", it) } }
    }

    private fun readMediaVolume() {
        val manager = audioManager ?: return
        runCatching {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            _mediaVolume.value = manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        }
    }

    // --- Pause transitions -----------------------------------------------------------------------------------------------

    /** One atomic transition of the pause flags, logged and followed by a reconcile. Callable from any thread. */
    private fun mutatePause(why: String, change: (MusicPauseState) -> MusicPauseState) {
        val before: MusicPauseState
        val after: MusicPauseState
        synchronized(pauseLock) {
            before = pauseState.value
            after = change(before)
            if (after == before) return
            pauseState.value = after
            _pauseReason.value = after.reason
            _yoursPauseReason.value = yoursPause(after).reason
            _scheduleBlocked.value = after.schedule
            _otherAppHeld.value = MusicPausePolicy.heldByOtherApp(after)
            // Another app took the audio after the player's start: it wins, and the bed meets the courtesy gate again when the hold ends. The start is spent
            // (every hold is set on the control's handler, where the mark lives), so the waiver cannot come back inside the start's window once the hold ends.
            if (_otherAppHeld.value) {
                setBedTakeover(false)
                deliberate.spend()
            }
        }
        Log.i(Tag, "pause: $why -> reason=${after.reason} $after")
        // A permanent loss is never followed by a gain. What ends it is the other player's session reading paused or stopped for 10 s
        // (reconsiderPermanentHold); with no grant, or while that player plays or buffers, Mimir does not take focus back (the panel
        // names the pause, and the player's play is the way out).
        handler?.post {
            reconcile()
            reconsiderPermanentHold()
        }
    }

    /**
     * The one release of a PERMANENT hold (after Nova 13:37:59-13:38:22, where a 5 s quiet from YouTube resumed
     * Your music over it and YouTube paused itself): with notification access, the other player's session must read PAUSED, STOPPED or
     * absent for [OtherPlayerConfirmedPausedMs] without a break ([ConfirmedPausedStreak]; buffering is playing, so a blip restarts the
     * count). Without access it never fires. Runs on the handler, after every pause transition and every change of the session or the
     * grant; a single delayed task re-checks at the deadline.
     */
    private fun reconsiderPermanentHold() {
        val worker = handler ?: return
        worker.removeCallbacks(reconsiderPermanentHoldTask)
        val now = SystemClock.uptimeMillis()
        val holding = pauseState.value.otherApp == OtherAppHold.PERMANENT
        // Your music: the other player's session confirmed paused. The theme bed has no play button, so it also lets go once no other player
        // has been audible for the same stretch; it then re-enters without taking focus.
        val strangerQuiet = !_strangerAudio.value && !strangerHeading
        val stopped = holding && MusicPausePolicy.otherPlayerQuiet(_otherAccess.value, _otherPlayer.value, bedIsSource = !_everywhere.value, strangerQuiet = strangerQuiet)
        if (confirmedPaused.onSample(stopped, now)) {
            confirmedPaused.reset()
            mutatePause("other player confirmed paused", MusicPausePolicy::onOtherSessionStopped)
            return
        }
        confirmedPaused.remainingMs(now)?.let { worker.postDelayed(reconsiderPermanentHoldTask, it.coerceAtLeast(1L)) }
    }

    // --- Music hours -----------------------------------------------------------------------------------------------------

    /** What the last looks at the wall clock found, and the player's override of the off-hours if one stands (handler thread). */
    private var scheduleWatch = MusicScheduleWatch()
    private val scheduleTask = Runnable { evaluateSchedule(force = false) }

    /**
     * Looks at the wall clock (handler thread) and sets the pause state's schedule flag at an EDGE of the music hours: on the way out of
     * them the music is held, on the way in it is released ([MusicSchedulePolicy.look]). Between edges nothing is set, so a play the
     * player pressed during the off-hours (which clears the flag, see [MusicPausePolicy.onUserPlayPause]) holds until the next edge
     * instead of being undone at the next look. That override ends at an absolute instant, so a clock that jumps past it (set by hand, a
     * new time zone or date, a night slept through) holds the music again instead of leaving the override standing. [force] applies the
     * current answer whatever the last look said: a different window was just chosen.
     *
     * Then one delayed look at the next edge, wall clock and not [SystemClock]: a device that sleeps through it looks again when the
     * screen comes on, when Mimir comes to the front, and when the clock, the date or the zone is changed. The delay is capped so a long
     * sleep of the handler's own clock cannot push the look far past it.
     */
    private fun evaluateSchedule(force: Boolean) {
        val worker = handler ?: return
        worker.removeCallbacks(scheduleTask)
        val hours = AudioPreferences.musicHours.value
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val look = MusicSchedulePolicy.look(scheduleWatch, hours, now, zone, force)
        scheduleWatch = look.watch
        look.outsideHours?.let { outside ->
            mutatePause("music hours ${hours.name} outside=$outside") { MusicPausePolicy.onSchedule(it, outsideHours = outside) }
        }
        val untilEdge = MusicSchedulePolicy.nextEdgeEpochMs(hours, now, zone)?.minus(now) ?: return
        worker.postDelayed(scheduleTask, (untilEdge + ScheduleSlackMs).coerceIn(1L, ScheduleLookMs))
    }

    // --- Audio focus -----------------------------------------------------------------------------------------------------

    /** Which request the focus callbacks belong to (handler thread): each request has a generation and a listener of its own. */
    private val focusGenerations = FocusRequestGeneration()

    /** The request that is, or last was, made (handler thread); it is what an abandon withdraws. */
    private var focusRequest: AudioFocusRequest? = null

    /**
     * A focus callback of the request made as generation [generation]. Runs on the control's handler. A callback of an earlier request
     * (one that was let go, with a signal already on its way) belongs to a request that no longer exists: it would set a hold that
     * nothing could end, so it is dropped even when a newer request has been granted since.
     */
    private fun onFocusChange(generation: Long, change: Int) {
        val signal = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusSignal.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> FocusSignal.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusSignal.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusSignal.LOSS_TRANSIENT_CAN_DUCK
            else -> return
        }
        if (!focusGenerations.isCurrent(generation)) {
            Log.i(Tag, "focus $signal ignored: it belongs to an earlier request")
            return
        }
        if (!focusHeld) {
            Log.i(Tag, "focus $signal ignored: no request is held")
            return
        }
        // A permanent loss drops Mimir's request in the framework; the others keep it queued.
        if (signal == FocusSignal.GAIN) focusHeld = true
        if (signal == FocusSignal.LOSS) focusHeld = false
        val look = gameLook()
        val game = look.value
        Log.i(Tag, "focus $signal gameInFront=$game")
        mutatePause("focus $signal") { MusicPausePolicy.onFocus(it, signal, game) }
        reconcile(look)
    }

    /** A new request with a listener of its own, tied to a new generation. */
    private fun newFocusRequest(): AudioFocusRequest {
        val generation = focusGenerations.issue()
        val listener = AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(generation, change) }
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(focusAttributes)
            // A phone call (or anything else exclusive) queues the request instead of failing it; its GAIN is the resume.
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(listener, handler ?: Handler(Looper.getMainLooper()))
            .build()
            .also { focusRequest = it }
    }

    /**
     * Brings focus into line with what Mimir's music wants. Runs on the control's handler, after every pause transition and whenever
     * Your music, the theme bed, its level or the game state changes. Idempotent.
     *
     * Two sources, one request: Your music (a folder) wants focus wherever "Play outside Mimir" lets it play; the theme bed wants it
     * while Mimir is in front and the engine has it on its way to sound ([FrontendMusicPolicy.bedWantsToSound]). They are never both
     * wanted, so a single request serves whichever plays. Your music asks at any start that no player is audible for; the bed asks
     * only at the player's own start ([MusicPausePolicy.bedFocusDecision]).
     */
    private fun reconcile(look: Lazy<Boolean> = gameLook()) {
        val manager = audioManager ?: return
        // The media bed hears whether a game is on screen at every pass, by the cheap terms: a tab-fronted or Recents-fronted game has no launch edge.
        MimirMediaBed.noteGameFront(gameOnScreenCheap())
        val state = yoursPause()
        val loud = AudioPreferences.ambientVolume.value > 0f
        val yoursWanted = yourMusicActive.value && loud && MusicPausePolicy.wantsFocus(state) && placementAllows()
        val bedWanted = !yoursWanted && loud && _bedWantsFocus.value && inMimir.value
        // The player's own start outranks the courtesy to a stream that holds no focus (a game left running); see BedTakeover. Looked at before anything below spends the mark.
        val deliberateLive = deliberate.live(SystemClock.uptimeMillis())
        // A game in front is asked only when it could change the answer (the UsageStats query is the costly check): the waiver is up, or a live start would raise it.
        val gameFront = (_bedTakeover.value || (bedWanted && deliberateLive)) && look.value
        // A start that a game outlived is spent, so the game closing inside the start's window does not bring the waiver back.
        if (gameFront) deliberate.spend()
        setBedTakeover(
            BedTakeover.next(
                current = _bedTakeover.value,
                bedWanted = bedWanted,
                deliberateLive = deliberateLive,
                gameInFront = gameFront,
                mimirInFront = inMimir.value,
                otherAppHeld = _otherAppHeld.value,
            ),
        )
        if (!yoursWanted && !bedWanted) {
            letGoOfFocus(manager)
            return
        }
        if (focusHeld) {
            if (MusicPausePolicy.abandonsFocusForGame(focusHeld, look.value)) {
                Log.i(Tag, "a game is in front: Mimir's music lets go of audio focus and plays on")
                letGoOfFocus(manager)
            }
            return
        }
        // The UsageStats query is the costly check, so it comes last, and is made once for the whole pass.
        val game = look.value
        // A press honoured by playing on through a game is spent: it must not turn a later return from the game into a "deliberate" take.
        if (game) deliberate.spend()
        val deliberateNow = deliberate.live(SystemClock.uptimeMillis())
        // Any request not from a deliberate play (return from a game, screen-on, any reconcile) first looks for another player: Mimir
        // never takes the audio from one, whether or not Your music is already sounding (Nova 12:46: it paused YouTube Music for good).
        val decision = if (yoursWanted) {
            MusicPausePolicy.focusDecision(state, game, deliberateNow, _strangerAudio.value)
        } else {
            // No request is held, so a hold only a GAIN could have ended is orphaned: the bed never waits for one.
            mutatePause("the theme bed holds no request", MusicPausePolicy::onBedFocusAbandoned)
            MusicPausePolicy.bedFocusDecision(pauseState.value, game, deliberateNow, _strangerAudio.value)
        }
        when (decision) {
            MusicPausePolicy.FocusDecision.NONE -> return
            MusicPausePolicy.FocusDecision.YIELD_TO_OTHER -> {
                Log.i(Tag, "other audio is already playing: Mimir's music waits for it instead of taking focus")
                mutatePause("start courtesy", MusicPausePolicy::onStartCourtesy)
                return
            }
            MusicPausePolicy.FocusDecision.REQUEST -> Log.i(Tag, "asking for audio focus for ${if (yoursWanted) "Your music" else "the theme bed"} deliberate=$deliberateNow gameInFront=$game")
        }
        when (val result = runCatching { manager.requestAudioFocus(newFocusRequest()) }.getOrNull()) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                deliberate.spend()
                focusHeld = true
                mutatePause("focus granted") { MusicPausePolicy.onFocus(it, FocusSignal.GAIN, gameInFront = false) }
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                deliberate.spend()
                focusHeld = true
                mutatePause("focus delayed", MusicPausePolicy::onFocusDelayed)
            }
            else -> {
                focusHeld = false
                if (!yoursWanted) {
                    // The bed has no service to start and no press to wait for: it plays on without focus, as it always did.
                    deliberate.spend()
                    Log.w(Tag, "audio focus request refused ($result); the theme bed plays on without it")
                    return
                }
                if (!MimirMusicService.foreground.value) {
                    // targetSdk 35 refuses a focus request unless Mimir is the top app or runs a foreground service. Not a verdict on
                    // Your music: get the service up and ask again when it is (the collector in attach), holding nothing meanwhile. A
                    // deliberate play keeps its mark (until it runs out) for that second ask.
                    Log.w(Tag, "audio focus request refused ($result) with no foreground service; starting it and asking again")
                    startServiceIfWanted()
                    return
                }
                deliberate.spend()
                Log.e(Tag, "audio focus request refused ($result); Your music holds until the player presses play")
                mutatePause("focus refused", MusicPausePolicy::onFocusRefused)
            }
        }
    }

    /**
     * Gives the focus request up for any reason. A transient hold (a call, another app) only that request's GAIN could have ended becomes
     * [OtherAppHold.NEEDS_FOCUS] ([MusicPausePolicy.onFocusAbandoned]): the music stays silent, and the next want asks afresh and plays only
     * when that request is GRANTED. Letting go of focus never lets a held music sound. The theme bed never asks afresh unless the player
     * starts it, so such a hold would never end: for the bed it ends here ([MusicPausePolicy.onBedFocusAbandoned]).
     */
    private fun letGoOfFocus(manager: AudioManager) {
        abandonFocus(manager)
        val abandoned: (MusicPauseState) -> MusicPauseState =
            if (_everywhere.value) MusicPausePolicy::onFocusAbandoned else MusicPausePolicy::onBedFocusAbandoned
        mutatePause("focus let go", abandoned)
    }

    private fun abandonFocus(manager: AudioManager) {
        if (!focusHeld) return
        focusHeld = false
        // Callbacks already on their way belong to the request that is going: they count for nothing from here on.
        focusGenerations.retire()
        val request = focusRequest ?: return
        runCatching { manager.abandonAudioFocusRequest(request) }
            .onFailure { Log.w(Tag, "could not abandon audio focus", it) }
    }

    // --- Other audio -----------------------------------------------------------------------------------------------------

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            sampleOtherPlayers(configs)
        }
    }

    private fun resampleOtherPlayers() {
        val manager = audioManager ?: return
        sampleOtherPlayers(runCatching { manager.activePlaybackConfigurations }.getOrNull().orEmpty())
    }

    /**
     * "Another app is playing music" ([anotherPlayerPlaying]). With the media-session grant it is the companion bridge: a non-Mimir
     * session that is playing. Without the grant it is a HEURISTIC, a count and not an identification (public Android names no
     * owner for a playback configuration): active USAGE_MEDIA playback tagged CONTENT_TYPE_MUSIC or CONTENT_TYPE_MOVIE
     * ([isRealMediaPlayback]), less the players Mimir owns ([MimirMediaBed.activeOwnPlaybackTokens]). Games and emulators do not
     * count either way (a backgrounded Dolphin keeps a UNKNOWN-typed player STARTED with no focus, indefinitely). It gates two
     * Mimir-side decisions only: whether Your music may take focus when it starts (it yields to a player already playing), and
     * whether another player that started without focus makes Your music wait. It never ends a PERMANENT hold ([reconsiderPermanentHold]
     * does, from the session alone). It rises after [OtherRiseMs] and falls after [OtherFallMs] of steadiness, so the
     * seam between two of Mimir's own decks, or the moment before one of its tokens is claimed, never reads as a stranger.
     * Handler thread only.
     */
    private fun sampleOtherPlayers(configs: List<AudioPlaybackConfiguration>) {
        val worker = handler ?: return
        val media = configs.count { config ->
            val attributes = config.audioAttributes
            isRealMediaPlayback(attributes.usage, attributes.contentType)
        }
        val ownPlayers = MimirMediaBed.activeOwnPlaybackTokens.value.size
        val raw = anotherPlayerPlaying(
            sessionAccessGranted = _otherAccess.value,
            sessionPlaying = _otherPlayer.value?.isPlaying == true,
            heuristicStrangers = strangerPlayers(media, ownPlayers),
        )
        if (raw == _strangerAudio.value) {
            worker.removeCallbacks(publishStranger)
            strangerHeading = raw
            return
        }
        if (raw == strangerHeading) return
        strangerHeading = raw
        worker.removeCallbacks(publishStranger)
        worker.postDelayed(publishStranger, if (raw) OtherRiseMs else OtherFallMs)
    }

    // --- The service -----------------------------------------------------------------------------------------------------

    private fun startServiceIfWanted() {
        val context = appContext ?: return
        // No short-circuit on MimirMusicService.running: it stays true until onDestroy, so an off/on inside a stop would skip the start.
        // A start on a service already in the foreground only redelivers onStartCommand, which re-asserts startForeground.
        if (!yourMusicActive.value) return
        try {
            context.startForegroundService(Intent(context, MimirMusicService::class.java))
        } catch (error: Exception) {
            // Android refuses a foreground-service start from the background. Playback itself is the engine's and carries on in
            // this process; the next time Mimir is in front, onAppForegrounded tries again.
            Log.e(Tag, "could not start the music service; Your music plays on without its notification and media session", error)
        }
    }
}
