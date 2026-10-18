package com.gruning.mimir.rewrite.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * What the frontend music is playing: the track's [title] and where it came from ([source]).
 *
 * The Music card reads it as "Title · Source", and the song-title chip shows [title]. [singleTrack] is true when there is no
 * next track to go to (the theme bed, or a folder of one track): A restarts it rather than skipping.
 */
data class MimirNowPlaying(
    val title: String,
    val source: MimirMusicSource,
    val singleTrack: Boolean = source == MimirMusicSource.MIMIR,
    /** The library id of the folder track on air (the key for its embedded art); null for the theme bed. */
    val trackId: String? = null,
    /** The folder track's artist tag (the album artist when it names no artist), once the library has read it; null for the theme bed and for a track whose tags name none. */
    val artist: String? = null,
)

/**
 * Process-wide frontend playback owner, for whichever [MimirFrontendBed] the caller has selected.
 *
 * The theme bed's MediaPlayer construction, start/pause, volume ramps, and release all live on one audio worker. The
 * Activity lifecycle changes one StateFlow; Home, Library, and Settings never recreate or seek the
 * player when routing between them. The player itself asks for no Android audio focus: [MimirMusicControl] does, and only when
 * the player starts the music on purpose ("Mimir takes over", so the other app pauses). Otherwise external music and calls win
 * through [MimirMediaBed], and through the hold the control sets when another app starts or takes focus (the policy's
 * [FrontendMusicPolicyInput.otherAppHeld]), while Hero Motion wins through the process-owned bed-token set.
 *
 * Your music (a folder) is not played here. This engine decides that a folder plays, and when, and hands the playlist and the
 * level to [YourMusicPlayback], the Media3 player owned by the process (wrapped in a session by [MimirMusicService]).
 */
object MimirFrontendMusic {
    private val lock = Any()
    private val appForeground = MutableStateFlow(false)
    private val bed = MutableStateFlow(MimirFrontendBed.Ambient)

    private val _nowPlaying = MutableStateFlow<MimirNowPlaying?>(null)

    /**
     * The track the player is on, for Settings > Music's "Now playing" row; null while the music is off or
     * before [install]. It names what is ACTUALLY playing, so while Your music falls back to the theme bed (see
     * [customFallback]) it reports the bed with [MimirMusicSource.MIMIR], and a folder's track with
     * [MimirMusicSource.CUSTOM]. Holding a value does not mean the app is foregrounded or the music audible: a media yield or a
     * background leaves it set, because the track has not changed.
     */
    val nowPlaying: StateFlow<MimirNowPlaying?> = _nowPlaying.asStateFlow()

    private val _customFallback = MutableStateFlow<CustomMusicFallback?>(null)

    /**
     * Why the music is the theme bed although the player chose Your music, or null when Your music is playing (or not chosen).
     * Settings > Music's Source row says it in words: a folder that cannot play is never a silent player.
     */
    val customFallback: StateFlow<CustomMusicFallback?> = _customFallback.asStateFlow()

    private val _trackStarted = MutableSharedFlow<MimirNowPlaying>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * One emission each time a track starts AUDIBLY after silence or a change: the cue for the UI's
     * song-title chip. Never at a bed's loop seam, which is the same track carrying on. No replay, so a
     * late collector is not shown a stale title. The engine emits whether or not
     * [AudioPreferences.songTitlesEnabled] is set; the chip's host reads that.
     */
    val trackStarted: SharedFlow<MimirNowPlaying> = _trackStarted.asSharedFlow()

    /**
     * Next track, for the "Now playing" row's A press.
     *
     * With one bed per theme there is no next track to go to, so this RESTARTS the bed: a short fade-out
     * (`FadeOutMs`), then the ordinary three-second entrance, and [trackStarted] fires for the restart. In Your music it
     * goes to the folder's next track in index order the same way (and restarts a folder of one).
     * The row's own copy says so; this is not a hidden no-op. A no-op while the music is off, not
     * installed, or silenced by the app being in the background or a media yield (there is nothing
     * audible to skip).
     */
    fun skipToNext() {
        engine?.skipToNext()
    }

    /**
     * Previous track, for Your music's panel and notification. Past [PreviousRestartsAfterMs] into a track it restarts that track;
     * sooner it goes to the track before (wrapping). A bed has one piece, so it restarts it. Like [skipToNext] it is a no-op
     * while nothing is audible; while Your music is paused it moves the cursor and stays paused.
     */
    fun skipToPrevious() {
        engine?.skipToPrevious()
    }

    @Volatile
    private var engine: MimirFrontendMusicEngine? = null

    /**
     * Prepare the one process player. Idempotent across Activity recreation.
     *
     * [initialBed] is taken explicitly rather than defaulted so a caller that knows the theme cannot
     * silently start on the wrong piece and swap a moment later. Both beds are Ogg Vorbis, and that
     * is the loop-seam fix rather than a packaging preference: the retired V3 `.m4a` declared 2112
     * priming + 448 padding in `iTunSMPB` but carried no `elst` edit list, and Android trims from the
     * edit list, so that dead air decoded as audio — +1,536 frames = 32.0 ms injected at every loop
     * point. Vorbis returns the exact frame count.
     */
    fun install(context: Context, initialBed: MimirFrontendBed) {
        synchronized(lock) {
            if (engine != null) return
            bed.value = initialBed
            // The control owns the pause rules, the service and the facade; the engine reads its pause reason below.
            MimirMusicControl.attach(context.applicationContext)
            engine = MimirFrontendMusicEngine(
                appContext = context.applicationContext,
                bed = bed,
                appForeground = appForeground,
                nowPlaying = _nowPlaying,
                customFallback = _customFallback,
                trackStarted = _trackStarted,
            )
        }
    }

    /**
     * Choose which piece the frontend plays. Safe before [install] — the value is held and the engine
     * opens on it.
     *
     * A change while the bed is audible tears the player down and brings the new piece in through the
     * ordinary three-second entrance, so it arrives the way it does at app foreground rather than
     * cutting mid-phrase. That is only tolerable because a theme change is a
     * deliberate, committed act — the Appearance picker applies and closes — so this cannot churn
     * while someone browses the gallery.
     */
    fun setBed(next: MimirFrontendBed) {
        bed.value = next
    }

    fun onAppForegrounded() {
        // AudioManager can retain isMusicActive after our paused player disappears without publishing a
        // second playback-config callback. Re-sample on every real foreground edge so that stale own-tail
        // state cannot keep the composition muted; real external playback remains fail-closed.
        MimirMediaBed.refreshOnForeground()
        appForeground.value = true
        // Your music: start its service if it could not start in the background, and re-read whether a game is still in front.
        MimirMusicControl.onAppForegrounded()
    }

    fun onAppBackgrounded() {
        appForeground.value = false
        // "Play outside Mimir" Off keeps Your music inside Mimir: the control lets go of audio focus once Mimir is not in front.
        MimirMusicControl.onAppBackgrounded()
    }

    val isInstalled: Boolean
        get() = engine != null
}

/**
 * What the engine reads from [MimirMusicControl] for one policy decision. [pause] is the reason Your music itself obeys (the control's
 * [MimirMusicControl.yoursPauseReason]); [scheduleBlocked] is the music hours, which hold the theme bed too.
 */
private data class FrontendControlInputs(
    val everywhere: Boolean,
    val pause: MusicPauseReason?,
    val mediaLevel: Float,
    val scheduleBlocked: Boolean,
    val playOutsideMimir: Boolean,
    val otherAppHeld: Boolean = false,
    /** The player started the theme bed on purpose ([MimirMusicControl.bedTakeover]): the courtesy gate to other music does not hold it. */
    val bedTakeover: Boolean = false,
)

/** What the engine reads from [MimirMediaBed] for one policy decision. [callInterrupted] is a part of [blocked], carried on its own so a deliberate start waives the courtesy to other music and never a call. */
private data class FrontendEnvironment(val blocked: Boolean, val competing: Boolean, val callInterrupted: Boolean)

private class MimirFrontendMusicEngine(
    private val appContext: Context,
    bed: MutableStateFlow<MimirFrontendBed>,
    appForeground: MutableStateFlow<Boolean>,
    private val nowPlaying: MutableStateFlow<MimirNowPlaying?>,
    private val customFallback: MutableStateFlow<CustomMusicFallback?>,
    private val trackStarted: MutableSharedFlow<MimirNowPlaying>,
) {
    private val workerThread = HandlerThread(WorkerName).apply { start() }
    private val worker = Handler(workerThread.looper)
    /** Frontend policy observation is process work; contain a failed child without losing playback state. */
    internal val scopeFailureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e("MimirFrontendMusic", "background music policy failed in $context", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + scopeFailureHandler)

    /**
     * One theme-bed player and the bed token that owns it. Your music is not played here: a folder plays on Media3's player
     * ([YourMusicPlayback], owned by the process and wrapped by [MimirMusicService]); this class decides what plays and when, and hands a folder to it.
     *
     * The loop runs TWO of these because `MediaPlayer.setLooping(true)` is not gapless. Measured on
     * an Odin 3: it costs 169-213 ms at every wrap, on a probe that is loop-exact by construction, so
     * the hole belongs to the player rather than the file. That is 5-6x the container-injected 32 ms
     * that leaving AAC removed — the shipped bed had an audible ~0.2 s hole at every loop point.
     *
     * So the seam hands over to an ALREADY-PREPARED player instead of seeking one back to zero.
     * Measured the same way, nine chained cycles took LESS wall-clock than nine durations, which a
     * real gap could not do.
     *
     * **The hand-over itself is no longer `setNextMediaPlayer` — see [onDeckCompleted].** That call
     * left a renderer inside `mediaserver` spinning at ~600 kHz forever from the first seam, about
     * two-thirds of all CPU the device burned at idle. The second deck and the reason for it are
     * unchanged; only the splice mechanism moved, from the framework's next-player path to an
     * explicit `start()` on the deck that was already prepared and buffered.
     */
    private class Deck(val player: MediaPlayer, val token: Long) {
        /**
         * Whether this deck has been declared to [MimirMediaBed].
         *
         * Ownership follows SOUNDING, not existing. A prepared-but-unstarted deck publishes no
         * playback configuration on this hardware — measured: two decks alive, `observed=1` — so
         * declaring it would leave two outstanding starts against one configuration. The bed's
         * exact-count rule then claims neither, reads its own bed as a stranger, and blocks. That
         * showed up as four start/release cycles before the music could settle.
         */
        var sounding: Boolean = false
    }

    // Worker-owned state below.

    /**
     * When a track is announced to the song-title chip. It is told when an ENTRY begins ([applyTargetVolume]'s start from
     * silence, which a bed change and a deliberate skip both go through) and asked from the fade ramp, never from a deck:
     * the prepared deck starts on every loop pass, so announcing there would announce every seam. See
     * [FrontendMusicTrackAnnouncer]. A folder's tracks are announced by [YourMusicPlayback] instead.
     */
    private val announcer = FrontendMusicTrackAnnouncer()
    private var current: Deck? = null
    private var upcoming: Deck? = null

    /** The theme bed is sounding (a deck is started). */
    private var playing = false
        set(value) {
            field = value
            publishSounding()
        }

    /** Your music is sounding on the Media3 player ([YourMusicEvents.onSounding]). */
    private var folderSounding = false
        set(value) {
            field = value
            publishSounding()
        }
    private var appliedVolume = 0f
    private var fadeGeneration = 0L
    /** What plays: null until the first choice (a folder's index may still be loading), then the theme bed or the folder's tracks. */
    private var plan: FrontendMusicPlan? = null

    /** [plan] is the theme bed (the worker writes it; the policy flow reads it). */
    private val bedPlan = MutableStateFlow(false)
    /** The folder track on air, or about to be (index order); always 0 for a bed. */
    private var cursor = 0
    private val folderFailure = MutableStateFlow<FolderPlaybackFailure?>(null)
    /** The track on air whatever the Music switch says; [nowPlaying] is this while the music is on. */
    private val currentTrack = MutableStateFlow<MimirNowPlaying?>(null)
    /** The level the policy last asked for, so a bed swap can re-enter at it rather than guess. */
    private var currentTarget = 0f

    /**
     * Every token this engine owns, read by the competing-bed check.
     *
     * Volatile because the policy flow reads it off the collector thread while the worker rotates
     * decks. A single token would make the bed treat its own next deck as a competitor and duck
     * itself permanently — there is always a next deck. Your music's player token is always listed: it is only ever counted
     * once [YourMusicPlayback] has claimed it, and leaving it out would duck Your music under itself.
     */
    @Volatile
    private var ownTokens: Set<Long> = setOf(YourMusicPlayback.ownToken)

    /** The facade reads one flag for "Your music has a track sounding": the bed's deck, or the folder's player. */
    private fun publishSounding() {
        MimirMusicControl.setSounding(playing || folderSounding)
    }

    /** Your music's player reports on the main thread; this engine's state is the worker's. */
    private val folderEvents = object : YourMusicEvents {
        override fun onSounding(sounding: Boolean) {
            worker.post { folderSounding = sounding }
        }

        override fun onTrack(index: Int, announce: Boolean) {
            worker.post {
                val chosen = plan as? FrontendMusicPlan.Folder ?: return@post
                cursor = index
                val track = chosen.nowPlayingAt(index, MimirMusicLibrary::labelOf) ?: return@post
                currentTrack.value = track
                if (announce) trackStarted.tryEmit(track)
            }
        }

        override fun onFailed(reason: CustomMusicFallback) {
            worker.post { failFolder(reason) }
        }
    }

    init {
        YourMusicPlayback.bind(folderEvents)
        // Deliberately NOT AudioPreferences.enabled / .volume: those are the UI sound-effect
        // controls (`uiSoundEnabled` and the click/nav loudness MimirAudio reads). The bed owns
        // ambientVolume, which is what the Utility rail displays, so what the rail says is now what
        // the player gets. See FrontendMusicPolicy's header for the two defects this closes.
        val frontendState = combine(
            appForeground,
            AudioPreferences.ambientVolume,
            MimirAudioFocus.state,
            AudioPreferences.musicEnabled,
            combine(
                combine(
                    MimirMusicControl.everywhere,
                    MimirMusicControl.yoursPauseReason,
                    MimirMusicControl.mediaVolume,
                    MimirMusicControl.scheduleBlocked,
                    AudioPreferences.playOutsideMimir,
                ) { everywhere, pause, mediaLevel, scheduleBlocked, outside ->
                    FrontendControlInputs(everywhere, pause, mediaLevel, scheduleBlocked, outside)
                },
                MimirMusicControl.otherAppHeld,
                MimirMusicControl.bedTakeover,
            ) { inputs, otherAppHeld, takeover -> inputs.copy(otherAppHeld = otherAppHeld, bedTakeover = takeover) },
        ) { appIsForeground, musicVolume, focus, musicEnabled, control ->
            FrontendMusicPolicyInput(
                appForeground = appIsForeground,
                musicVolume = musicVolume,
                focus = focus,
                environmentBlocked = false,
                competingOwnedBed = false,
                musicEnabled = musicEnabled,
                everywhere = control.everywhere,
                pauseReason = control.pause,
                mediaLevel = control.mediaLevel,
                scheduleBlocked = control.scheduleBlocked,
                playOutsideMimir = control.playOutsideMimir,
                otherAppHeld = control.otherAppHeld,
                environmentWaived = control.bedTakeover,
            )
        }
        val environment = combine(
            MimirMediaBed.environmentBlocked,
            MimirMediaBed.audibleOwnPlaybackTokens,
            MimirMediaBed.callInterrupted,
        ) { blocked, activeTokens, call ->
            FrontendEnvironment(blocked, frontendMusicHasCompetingBed(activeTokens, ownTokens), call)
        }
        scope.launch {
            // Whether the theme bed is on its way to sound, whatever else is playing: the control asks for audio focus on its behalf when the
            // player starts the music. Not the environment, which is how it learns that another app is already playing.
            combine(frontendState, bedPlan) { input, isBed -> isBed && FrontendMusicPolicy.bedWantsToSound(input) }
                .distinctUntilChanged()
                .collect { MimirMusicControl.setBedWantsFocus(it) }
        }
        scope.launch {
            combine(frontendState, environment) { input, env ->
                FrontendMusicPolicy.targetVolume(
                    input.copy(
                        environmentBlocked = env.blocked,
                        competingOwnedBed = env.competing,
                        callInterrupted = env.callInterrupted,
                    ),
                )
            }.distinctUntilChanged().collect { targetVolume ->
                worker.post { applyTargetVolume(targetVolume) }
            }
        }
        scope.launch {
            // What plays: the theme bed, or the chosen folder's tracks once it can supply them (see
            // FrontendMusicSourceResolver). Equal consecutive choices are dropped, and applyPlan re-checks anyway, since
            // the worker hop means the value can be stale by the time it lands.
            combine(
                bed,
                AudioPreferences.musicSource,
                MimirMusicLibrary.state,
                MimirMusicLibrary.playlist,
                // A failure and the player's pick from the Artist / Album tree ride together: either one changes what plays.
                combine(folderFailure, MimirMusicLibrary.selection) { failure, pick -> failure to pick },
            ) { theme, source, library, playlist, (failure, pick) ->
                FrontendMusicSourceResolver.resolve(source, theme, library, playlist, failure, pick)
            }.filterNotNull().distinctUntilChanged().collect { choice ->
                customFallback.value = choice.fallback
                worker.post { applyPlan(choice.plan) }
            }
        }
        scope.launch {
            // The Now playing row: what is sounding, not what the player picked.
            combine(currentTrack, AudioPreferences.musicEnabled) { track, enabled ->
                if (enabled) track else null
            }.collect { nowPlaying.value = it }
        }
        scope.launch {
            // Order (Settings › Music): the player walks its folder in order or shuffled; it applies from the next track.
            AudioPreferences.musicOrder.collect { order -> YourMusicPlayback.setShuffle(order == MimirMusicOrder.SHUFFLE) }
        }
        scope.launch {
            // Tags arriving (the library reads them after the music has started) rename the track on air: the Now playing row and the media session
            // follow in place, and nothing restarts. collectLatest + delay folds a burst of batches into one look about a second after the last.
            MimirMusicLibrary.tagsRevision.collectLatest {
                delay(TagLabelSettleMs)
                worker.post { refreshFolderLabels() }
            }
        }
    }

    /** The track on air may have been renamed by tags that have just been read. The plan, the cursor and the player are untouched. */
    private fun refreshFolderLabels() {
        val chosen = plan as? FrontendMusicPlan.Folder ?: return
        val shown = chosen.nowPlayingAt(cursor, MimirMusicLibrary::labelOf) ?: return
        if (currentTrack.value != shown) currentTrack.value = shown
        YourMusicPlayback.relabel(MimirMusicLibrary::labelOf, chosen.tracks)
    }

    /**
     * Next track. See [MimirFrontendMusic.skipToNext].
     *
     * Not routed through the bed flow: setting the bed to the value it already has is a no-op ([applyPlan] returns at
     * once), and tearing a live deck down without the fade would be an audible cut. So this is its own sequence, run on the
     * audio worker: supersede the current ramp, fade to silence over [FadeOutMs], release BOTH decks and their ownership
     * tokens through the ordinary release at the end of the fade, then start the same bed from its opening through
     * [applyTargetVolume] (the ordinary three-second entrance, which also begins a new announced entry). A folder's skip is
     * [YourMusicPlayback.skip]: the same fade out and entrance around a seek to the neighbouring track.
     */
    fun skipToNext() {
        worker.post { skip(forward = true) }
    }

    /** Previous track. See [MimirFrontendMusic.skipToPrevious]. */
    fun skipToPrevious() {
        worker.post { skip(forward = false) }
    }

    private fun skip(forward: Boolean) {
        if (plan is FrontendMusicPlan.Folder) {
            YourMusicPlayback.skip(forward)
            return
        }
        if (!FrontendMusicSkip.canSkip(playing = playing, targetVolume = currentTarget)) return
        Log.d(Tag, "skip token=${current?.token}")
        fadeGeneration += 1L
        startFade(
            generation = fadeGeneration,
            from = appliedVolume,
            to = 0f,
            durationMs = FadeOutMs,
            restartAfter = true,
        )
    }

    /**
     * Swap which piece plays, keeping [MimirMediaBed] ownership honest across the tear-down.
     *
     * Every stop MUST be published, for BOTH decks. The media bed correlates each token to a live
     * playback configuration; releasing a player without saying so leaves a claim pointing at a
     * configuration that no longer exists, and the courtesy gate then reads our own next start as a
     * stranger's — exactly the failure that muted the music when Hero Motion and the bed collided.
     */
    private fun applyPlan(next: FrontendMusicPlan) {
        // The folder the player just picked is now what plays (the scan is over): that pick was a deliberate start, whatever came before.
        if (next is FrontendMusicPlan.Folder) MimirMusicControl.onPlanIsFolder(next.tree)
        if (next == plan) return
        if (next.playsTheSameAs(plan)) {
            // A rescan that found the same tracks: nothing audible changes, so nothing restarts. The new index stamp is kept.
            val before = (plan as? FrontendMusicPlan.Folder)?.start
            plan = next
            // A new pick of a track in the queue that is already loaded moves to it (the same pick met again after a rescan does not).
            val pick = (next as? FrontendMusicPlan.Folder)?.start
            if (pick != null && pick != before) YourMusicPlayback.jumpTo(pick.trackId)
            return
        }
        plan = next
        bedPlan.value = next is FrontendMusicPlan.Bed
        cursor = (next as? FrontendMusicPlan.Folder)?.startIndex() ?: 0
        // Your music (a folder) plays everywhere; the theme bed stays Mimir-only. The policy and the facade both read this.
        MimirMusicControl.setEverywhere(next is FrontendMusicPlan.Folder)
        currentTrack.value = next.nowPlayingAt(cursor, MimirMusicLibrary::labelOf)
        // Invalidate any in-flight ramp first, or it will keep setting volume on a released player.
        fadeGeneration += 1L
        val hadDecks = current != null || upcoming != null
        discardDeck(current)
        discardDeck(upcoming)
        current = null
        upcoming = null
        playing = false
        appliedVolume = 0f
        if (next is FrontendMusicPlan.Folder) {
            Log.d(Tag, "plan=${next.logName()}")
            // The player takes the playlist whenever the service has it up; the level follows. Both are idempotent.
            YourMusicPlayback.setPlaylist(next.tree, next.tracks, MimirMusicLibrary::labelOf, next.start?.trackId)
            YourMusicPlayback.setTarget(currentTarget)
            return
        }
        YourMusicPlayback.clear()
        if (!hadDecks) {
            // Nothing to tear down. If the policy already asked for sound and nothing could start (the first choice, or a
            // folder that would not play and has just been given up on), start it now; otherwise the next start picks it up.
            Log.d(Tag, "plan=${next.logName()} (idle)")
            if (currentTarget > 0f) applyTargetVolume(currentTarget)
            return
        }
        Log.d(Tag, "plan=${next.logName()} swapped")
        // Re-enter at the level the policy last asked for. A bed that was silent stays silent, and a
        // bed that was audible comes back through the ordinary unhurried entrance.
        applyTargetVolume(currentTarget)
    }

    /**
     * [announce] is false only for the recovery re-entry after a seam whose prepared deck would not start (see
     * [FrontendMusicSeamOperations.reenterFromSilence]): the same track restarting is not a new track.
     */
    private fun applyTargetVolume(rawTarget: Float, announce: Boolean = true) {
        val target = rawTarget.coerceIn(0f, 1f)
        currentTarget = target
        if (plan is FrontendMusicPlan.Folder) {
            // Your music's level and its pause are the Media3 player's: this is the compensated gain, applied as its volume.
            YourMusicPlayback.setTarget(target)
            return
        }
        fadeGeneration += 1L
        val generation = fadeGeneration

        if (target > 0f && !playing) {
            val deck = current ?: createDeck()?.also { current = it }
            if (deck == null) return
            soundDeck(deck)
            val started = runCatching {
                deck.player.setVolume(appliedVolume, appliedVolume)
                deck.player.start()
            }.isSuccess
            if (!started) {
                discardDeck(deck)
                current = null
                return
            }
            playing = true
            // A track is entering from silence: after a start, a bed change, a deliberate skip, or the music coming back
            // from a yield. The chip is told when its fade first becomes audible, not here.
            announcer.entryBegan(announce)
            // Chain the take-over deck immediately: the seam must already have somewhere to go.
            chainNextDeck()
            Log.d(Tag, "start token=${deck.token}")
        }

        if (!playing) {
            appliedVolume = 0f
            return
        }
        startFade(
            generation = generation,
            from = appliedVolume,
            to = target,
            durationMs = if (target <= appliedVolume) FadeOutMs else FadeInMs,
        )
    }

    /**
     * Build one theme-bed deck. NOT looping — the loop comes from the hand-over, and a player that also loops
     * itself would reintroduce the very seek this exists to avoid.
     *
     * Ownership is taken BEFORE construction, as everywhere else in this process: some handheld
     * stacks publish a prepared player's configuration before start(), and a baseline captured after
     * that already contains it, leaving the player permanently unowned.
     */
    private fun createDeck(): Deck? {
        val token = MimirMediaBed.nextOwnPlaybackToken()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val chosen = plan as? FrontendMusicPlan.Bed ?: return null
        val player = runCatching {
            MediaPlayer.create(appContext, chosen.bed.audioResource, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE)
        }.onFailure { error -> Log.e(Tag, "player creation failed", error) }.getOrNull() ?: return null
        player.apply {
            isLooping = false
            setVolume(appliedVolume, appliedVolume)
            setOnErrorListener { failedPlayer, what, extra ->
                worker.post { onDeckFailed(failedPlayer, what, extra) }
                true
            }
        }
        val deck = Deck(player, token)
        player.setOnCompletionListener { worker.post { onDeckCompleted(deck) } }
        return deck
    }

    /** Your music cannot play this folder: hold it against the index so the resolver falls back to the theme bed with the reason. */
    private fun failFolder(reason: CustomMusicFallback) {
        val folder = plan as? FrontendMusicPlan.Folder ?: return
        Log.w(Tag, "folder playback failed ($reason); the theme bed takes over")
        folderFailure.value = FolderPlaybackFailure(folder.tree, folder.scannedAtMillis, reason)
    }

    private fun FrontendMusicPlan.logName(): String = when (this) {
        is FrontendMusicPlan.Bed -> "bed ${bed.name}"
        is FrontendMusicPlan.Folder -> "folder tracks=${tracks.size}"
    }

    /** Declare a deck to the bed at the moment it begins to sound, never before. */
    private fun soundDeck(deck: Deck) {
        if (deck.sounding) return
        deck.sounding = true
        ownTokens = ownTokens + deck.token
        MimirMediaBed.noteOwnPlaybackStarting(deck.token)
    }

    private fun discardDeck(deck: Deck?) {
        if (deck == null) return
        runCatching { deck.player.setOnCompletionListener(null) }
        runCatching { deck.player.setOnErrorListener(null) }
        runCatching { deck.player.stop() }
        runCatching { deck.player.release() }
        if (deck.sounding) {
            deck.sounding = false
            ownTokens = ownTokens - deck.token
            MimirMediaBed.noteOwnPlaybackStopped(deck.token)
        }
    }

    /**
     * The seam. The finished deck is retired, the deck already prepared behind it is STARTED and
     * becomes current, and a fresh one is prepared for the seam after that.
     *
     * **THE HAND-OVER IS EXPLICIT NOW, AND `setNextMediaPlayer` IS GONE —
     * measured on the Thor.** The framework's next-player path left a NuPlayer renderer inside
     * `/system/bin/mediaserver` spinning at ~600 000 messages a second, FOREVER, from the first
     * seam onward: 99.97% of one CPU core across a 25 s hands-off window, in 79 scheduling slices
     * with 61 of them ending runnable-preempted. It never blocked. That single thread was about
     * two-thirds of ALL the CPU this device burned while the shelf sat idle doing nothing.
     *
     * The evidence chain, so nobody has to re-derive it: `dumpsys media.player` named the client as
     * this package with `mime(audio/vorbis)`; force-stopping Mimir dropped mediaserver from 103.5%
     * of a core to 0.00%, twice; relaunching did NOT restart the spin, which ruled out startup; the
     * message counter went 8 512 → 3 256 390 → 9 415 233 across three consecutive 10 s buckets, and
     * this function's own `seam ->` log line landed inside the exact bucket where it exploded;
     * `dumpsys audio` showed a new player every 115.2 s, the loop length of the bed.
     *
     * **What is preserved and what is traded.** The replacement is still CREATED AND PREPARED a
     * whole piece ahead, so starting it here is a `start()` on a buffered player — not the
     * `seekTo(0)` this chain was built to avoid, which would reintroduce the seam this chain avoids.
     * What is given up is the framework's sample-exact splice. That is a deliberate trade of an
     * unmeasured, possibly inaudible gap against a permanently pinned core.
     */
    private fun onDeckCompleted(finished: Deck) {
        val promoted = upcoming
        FrontendMusicSeam.handOver(
            playing = playing,
            finishedIsCurrent = finished === current,
            hasUpcoming = promoted != null,
            operations = object : FrontendMusicSeamOperations {
                override fun declarePromoted() {
                    promoted?.let { deck -> soundDeck(deck) }
                }

                override fun retireFinished() {
                    discardDeck(finished)
                    current = promoted
                    upcoming = null
                }

                override fun startPromoted(): Boolean =
                    promoted != null && runCatching { promoted.player.start() }.isSuccess

                override fun chainNextDeck() = this@MimirFrontendMusicEngine.chainNextDeck()

                override fun noteSeam() {
                    // The same piece carrying on: a bed's seam is never announced to the chip.
                    Log.d(Tag, "seam -> token=${promoted?.token}")
                }

                override fun reportStartFailure() {
                    Log.e(Tag, "the prepared deck would not start; the bed falls silent rather than looping badly")
                }

                override fun discardPromoted() {
                    discardDeck(promoted)
                    current = null
                }

                override fun reenterFromSilence() {
                    playing = false
                    // The seam's own failure arm: the same track restarting is not a new track, so it stays unannounced.
                    applyTargetVolume(currentTarget, announce = false)
                }
            },
        )
    }

    /**
     * Prepare — but do NOT attach — the deck that will take over at the next seam.
     *
     * It takes no `after` parameter any more, and that absence is the fix: attaching it with
     * `setNextMediaPlayer` is what pinned a CPU core (see [onDeckCompleted]). Preparing it now
     * rather than at the seam is what keeps the hand-over cheap, and is unchanged — the next seam
     * is a whole piece away, and exactly two decks stay alive rather than three.
     */
    private fun chainNextDeck() {
        if (upcoming != null) return
        upcoming = createDeck()
        if (upcoming == null) Log.e(Tag, "could not prepare the next deck; the seam will be a cold start")
    }

    private fun onDeckFailed(failed: MediaPlayer, what: Int, extra: Int) {
        Log.e(Tag, "player error what=$what extra=$extra")
        val isCurrent = failed === current?.player
        val isUpcoming = failed === upcoming?.player
        if (!isCurrent && !isUpcoming) return
        fadeGeneration += 1L
        discardDeck(current)
        discardDeck(upcoming)
        current = null
        upcoming = null
        playing = false
        appliedVolume = 0f
    }

    private fun startFade(
        generation: Long,
        from: Float,
        to: Float,
        durationMs: Long,
        /** A deliberate skip: once the fade to silence has released the decks, start the same bed from its opening. */
        restartAfter: Boolean = false,
    ) {
        val steps = (durationMs / FadeStepMs).coerceAtLeast(1L).toInt()
        val ramp = object : Runnable {
            private var step = 0

            override fun run() {
                if (generation != fadeGeneration) return
                val activeDeck = current ?: return
                step += 1
                val progress = (step.toFloat() / steps.toFloat()).coerceIn(0f, 1f)
                appliedVolume = from + ((to - from) * progress)
                // BOTH decks: the take-over deck must already be at the right gain when the seam
                // arrives, or every loop point would step to a stale level.
                runCatching { activeDeck.player.setVolume(appliedVolume, appliedVolume) }
                upcoming?.let { runCatching { it.player.setVolume(appliedVolume, appliedVolume) } }
                // The one place a track is announced: the first time THIS entry's fade becomes audible. A loop seam never
                // begins an entry, so it can never get here with one pending.
                if (announcer.levelApplied(appliedVolume, AudibleFloor)) {
                    plan?.nowPlayingAt(cursor)?.let { trackStarted.tryEmit(it) }
                }
                ownTokens.forEach { token ->
                    // Your music's player token is the player's own to report; only the bed's decks are levelled here.
                    if (token != YourMusicPlayback.ownToken) {
                        MimirMediaBed.noteOwnBedLevel(
                            token = token,
                            audible = appliedVolume > AudibleFloor,
                            volume = appliedVolume,
                        )
                    }
                }
                if (step < steps) {
                    worker.postDelayed(this, FadeStepMs)
                    return
                }
                if (to <= 0f) {
                    // RELEASE, not pause -- and this is a defect fix, not tidiness.
                    //
                    // A paused MediaPlayer keeps `AudioManager.isMusicActive()` true while dropping
                    // out of `getActivePlaybackConfigurations()`. That is the exact shape
                    // MimirMediaBed treats as an unknowable stranger -- music active, zero
                    // configurations -- so it blocks. Blocked drives this target to 0, which is what
                    // paused the player, which is what keeps isMusicActive true. The bed silences
                    // itself permanently and nothing can lift it: measured on Odin 3, a background
                    // and foreground cycle (which re-samples through MimirMediaBed.refresh()) did not
                    // recover it, and only force-stopping the process did.
                    //
                    // Releasing costs a MediaPlayer rebuild on the next start and restarts the piece
                    // from its opening, which for a bed that always enters on a three-second fade is
                    // the intended entrance anyway.
                    discardDeck(current)
                    discardDeck(upcoming)
                    current = null
                    upcoming = null
                    playing = false
                    appliedVolume = 0f
                    // Re-sample once the track is actually gone. The configuration callback and
                    // isMusicActive do not settle in lockstep, and this is the edge where being one
                    // sample stale used to latch permanently. Two samples, because the first is
                    // measurably too early: on the emulator the callback at release still reported
                    // isMusicActive true, and the flag only cleared seconds later.
                    //
                    // Not when a skip is starting the bed again at once: those samples would land inside the new
                    // deck's entrance, which a bed swap (also a discard followed by an immediate start) never has.
                    // They are for a track that is GONE, so a skip that did not start anything posts them below.
                    val resampleSoon = {
                        ReleaseSettleMs.forEach { delay ->
                            worker.postDelayed({ MimirMediaBed.refresh() }, delay)
                        }
                    }
                    if (!restartAfter) resampleSoon()
                    Log.d(Tag, "release decks=${ownTokens.size}")
                    if (restartAfter) {
                        // A skip is the release plus a fresh start: the same bed from position zero, through the ordinary
                        // entrance, announced as the new entry it is. If the policy went silent meanwhile, currentTarget
                        // is 0 and this starts nothing; the music comes back through the normal start when it is allowed.
                        applyTargetVolume(currentTarget)
                        if (!playing) resampleSoon()
                    }
                } else {
                    Log.d(Tag, "audible token=${activeDeck.token} volume=${"%.3f".format(to)}")
                }
            }
        }
        worker.post(ramp)
    }

    private companion object {
        const val Tag = "MimirFrontendMusic"
        const val WorkerName = "MimirFrontendMusic"

        /** How long after the last tag batch the Now playing row and the media session look at the track on air again. */
        const val TagLabelSettleMs = 1_000L
        // three seconds. This is the bed arriving as the app comes to the
        // foreground, and half a second reads as the music being switched on rather than the room
        // already having been there. Deliberately asymmetric with FadeOutMs: yielding to a call,
        // external audio or Hero Motion should still be prompt, so only the entrance is unhurried.
        const val FadeInMs = 3_000L
        const val FadeOutMs = 260L
        const val FadeStepMs = 40L
        const val AudibleFloor = 0.01f
        /**
         * When to re-read the environment after releasing the track. AudioManager does not drop
         * `isMusicActive` in step with the configuration callback, so one prompt sample and one
         * late one; a stale read here is what used to latch the bed off permanently.
         */
        val ReleaseSettleMs = longArrayOf(250L, 1_500L)
    }
}
