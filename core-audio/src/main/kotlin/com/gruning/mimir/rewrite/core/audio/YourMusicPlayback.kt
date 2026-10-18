package com.gruning.mimir.rewrite.core.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.random.Random

/** What Your music's player tells the frontend engine. Called on the main thread; the engine hops to its own worker. */
internal interface YourMusicEvents {
    /** The player started sounding, or finished its pause. */
    fun onSounding(sounding: Boolean)

    /** The track on air is [index] of the playlist. [announce] is true when it has just become audible (the song-title chip's cue). */
    fun onTrack(index: Int, announce: Boolean)

    /** Several tracks in a row would not play: the folder is given up on and the theme bed takes over, with [reason]. */
    fun onFailed(reason: CustomMusicFallback)
}

/**
 * Your music's player: one Media3 [ExoPlayer] playing one playlist, the folder's tracks, gaplessly, repeating.
 *
 * **Who owns what.** This object owns the player, for the life of the process: it is built on the main looper the first time a
 * playlist needs it and is never released (but once: if float output's AudioTrack will not start, [rebuildWithoutFloat] replaces
 * it with one that has no float, and the service moves its session on), so a refused or a dead service never leaves Your music
 * without a player inside Mimir.
 * [MimirMusicService] only wraps it in its media session ([playerFor]). The engine ([MimirFrontendMusic]) decides WHAT plays and
 * WHEN it may (the policy's target level, the pause reasons), through [setPlaylist], [setTarget], [skip] and [clear]; every one of
 * them posts to the main looper, which is the player's application looper, so the player is never touched from another thread. The
 * theme beds stay on the platform engine.
 *
 * **What it does not do.** It takes no audio focus (the player is built with `handleAudioFocus = false`): [MimirMusicControl] asks
 * for and gives up focus, and [MusicPausePolicy] decides who may silence the music. A pause here is `pause()` and a resume is
 * `play()`, so the track resumes exactly where it stood and moving between Mimir and other apps never restarts it.
 *
 * **Gain.** The engine's target is already the compensated level min(1, M / S) ([FrontendMusicPolicy.compensatedGain]) with the
 * Hero Motion duck folded in. It reaches the player as its volume, ramped ([YourMusicGain]), so a pause, a skip and a level
 * change are never a click.
 */
internal object YourMusicPlayback {
    private const val Tag = "MimirYourMusicPlayer"

    /** Several tracks in a row that fail: the folder is given up on rather than retried forever. */
    private const val MaxFailures = 5
    private const val ArtistLabel = "Music on this device"
    private val ReleaseSettleMs = longArrayOf(250L, 1_500L)

    private val main = Handler(Looper.getMainLooper())

    /**
     * Mimir's claim on this player in [MimirMediaBed], so the stranger-audio heuristic and Hero Motion's environment check count
     * it as Mimir's own and not as another app's music. One token for the player's life; the engine lists it among its own.
     */
    val ownToken: Long by lazy { MimirMediaBed.nextOwnPlaybackToken() }

    @Volatile
    private var events: YourMusicEvents? = null

    private val androidUris = object : YourMusicUris {
        override fun forPath(path: String): String = Uri.fromFile(File(path)).toString()

        override fun forDocument(tree: String, documentId: String): String =
            DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), documentId).toString()
    }

    // --- Main-thread state -----------------------------------------------------------------------------------------------

    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var tree: String? = null
    private var entries: List<YourMusicEntry> = emptyList()

    /** The track on air, or about to be: the player's own index while it has one, the last one it had while it is not built. */
    private var cursor = 0
    private var target = 0f
    private var applied = 0f
    private var sounding = false
    private var pausing = false

    /** The next time it becomes audible, the track is an arrival: a full entrance, announced to the chip. */
    private var announcePending = false
    private var failures = 0
    private var generation = 0

    /** The volume is down for the end of a track ("Fade between tracks") and has not yet come back up for the next. */
    private var dipped = false

    /** The ramp in flight, or null (see [YourMusicRamps]): the dip acts only while no pause or skip owns the volume. */
    private var rampKind: YourMusicRampKind? = null

    /** The track a skip is fading out toward. Made when the fade ends, or at once if a pause arrives first. */
    private var pendingMove: Int? = null
    private var dipPolling = false

    /** A label update of the current media item is in progress: the transition it may raise is not a new track. */
    private var relabeling = false
    private val dipPoll = object : Runnable {
        override fun run() {
            if (!dipPolling) return
            guarded("dip poll") { pollDip() }
            if (dipPolling) main.postDelayed(this, YourMusicDip.PollMs)
        }
    }

    fun bind(listener: YourMusicEvents) {
        events = listener
    }

    /**
     * Called on the main thread with the new player when [rebuildWithoutFloat] has replaced the old one. The service sets it to move
     * its media session onto the new player; main thread only, like everything that touches the player.
     */
    var onPlayerReplaced: ((ExoPlayer) -> Unit)? = null

    /** The application context the player is built with. Any thread; the control hands it over when the engine is installed. */
    fun install(context: Context) = onMain("install") {
        if (appContext == null) appContext = context.applicationContext
        // A playlist that arrived before the context did could not be built for: build now.
        if (player == null && entries.isNotEmpty()) {
            loadPlaylist()
            reconcile()
        }
    }

    // --- The engine's side (any thread) ----------------------------------------------------------------------------------

    /**
     * Play [tracks] of [tree] from the first one, or from [startTrackId] when the player picked one from the Artist / Album tree. The same folder with the same tracks
     * and no pick changes nothing.
     */
    fun setPlaylist(
        tree: String,
        tracks: List<MusicTrack>,
        labelOf: (MusicTrack) -> YourMusicLabel = { YourMusicLabel(musicTrackTitle(it.name)) },
        startTrackId: String? = null,
    ) = onMain("setPlaylist") {
        val next = yourMusicEntries(tree, tracks, androidUris, labelOf)
        // The same tracks in the same order change nothing, whatever they are called now: a tag that has just been read renames a track, it does not reload the player.
        if (startTrackId == null && tree == this.tree && next.playsTheSameAs(entries)) {
            entries = next
            return@onMain
        }
        this.tree = tree
        entries = next
        // A pick opens on the track pressed. Otherwise, shuffled, a new playlist opens on a random track; in order, on the first.
        cursor = next.indexOfFirst { it.mediaId == startTrackId }.takeIf { startTrackId != null && it >= 0 }
            ?: yourMusicStartIndex(next.size, AudioPreferences.musicOrder.value == MimirMusicOrder.SHUFFLE) { Random.nextInt(it) }
        failures = 0
        announcePending = true
        loadPlaylist()
        // The engine named the first track when it chose the folder; tell it which one this is, so Now playing is right before it sounds.
        if (cursor != 0) events?.onTrack(cursor, announce = false)
        reconcile()
    }

    /** Stop playing and forget the playlist (the theme bed is taking over, or the folder is gone). */
    fun clear() = onMain("clear") {
        tree = null
        entries = emptyList()
        cursor = 0
        failures = 0
        announcePending = false
        player?.let { it.stopNow(); it.clearMediaItems() }
        YourMusicFormatTracker.reset()
        haltSound()
        publishTiming()
    }

    /** The level the policy wants: zero is a pause, anything above is the player's volume. */
    fun setTarget(level: Float) = onMain("setTarget") {
        target = level.coerceIn(0f, 1f)
        reconcile()
    }

    /**
     * Tags have been read since the playlist was loaded: the entries take the new labels (title, artist, album) and nothing that plays changes. [tracks]
     * is the playlist's own list, in its order. The track on air has its media item brought up to date at once ([relabelCurrent]); every other track's is, when it begins.
     */
    fun relabel(labelOf: (MusicTrack) -> YourMusicLabel, tracks: List<MusicTrack>) = onMain("relabel") {
        if (entries.isEmpty() || tracks.size != entries.size) return@onMain
        entries = entries.mapIndexed { index, entry ->
            val track = tracks[index]
            if (track.id != entry.mediaId) return@mapIndexed entry
            val label = labelOf(track)
            if (label.title == entry.title && label.artist == entry.artist && label.album == entry.album) {
                entry
            } else {
                entry.copy(title = label.title, artist = label.artist, album = label.album)
            }
        }
        // The track on air takes its new label at once (the media session is what the lock screen and the notification read), not at the next transition.
        relabelCurrent()
    }

    /**
     * The track that has just begun carries its entry's label in the media session. Media3 updates a progressive source's metadata in place (the same
     * URI, so no re-prepare and no gap): playback goes on. The update can raise a transition of its own for the same item, which [relabeling] keeps
     * from being read as a new track.
     */
    private fun relabelCurrent() {
        val exo = player ?: return
        val index = exo.currentMediaItemIndex
        val entry = entries.getOrNull(index) ?: return
        val shown = exo.currentMediaItem?.takeIf { it.mediaId == entry.mediaId }?.mediaMetadata ?: return
        if (shown.title?.toString() == entry.title && shown.artist?.toString() == (entry.artist ?: ArtistLabel) && shown.albumTitle?.toString() == entry.album) return
        relabeling = true
        try {
            exo.replaceMediaItem(index, mediaItem(entry))
        } catch (error: RuntimeException) {
            Log.w(Tag, "relabel failed (${error.javaClass.simpleName})")
        } finally {
            relabeling = false
        }
    }

    /** In order or Shuffle (Settings › Music › Order). Applies from the next track on; the track on air plays on. */
    fun setShuffle(enabled: Boolean) = onMain("setShuffle") {
        val exo = player ?: return@onMain
        if (exo.shuffleModeEnabled != enabled) exo.shuffleModeEnabled = enabled
    }

    fun skip(forward: Boolean) = onMain("skip") {
        val count = entries.size
        if (count == 0) return@onMain
        val live = player
        val from = (live?.currentMediaItemIndex ?: cursor).coerceIn(0, count - 1)
        if (!sounding) {
            // Paused (nothing audible to fade): the press only moves the cursor, and the track starts from its opening when the
            // music is allowed again.
            moveTo(live, yourMusicSkipIndex(from, count, forward, live?.neighbour(forward)))
            announcePending = true
            return@onMain
        }
        if (target <= 0f) return@onMain
        fadeMove(live, yourMusicSkipDestination(from, count, forward, live?.currentPosition ?: 0L, live?.neighbour(forward)))
    }

    /**
     * The player pressed a track of the queue that is already loaded (a new pick from the Artist / Album tree whose album is what is playing): the track [trackId] is
     * on air next, through the skip's own fade out and entrance when something is sounding, and at once (staying paused) when nothing is. A track the queue does not
     * hold does nothing.
     */
    fun jumpTo(trackId: String) = onMain("jumpTo") {
        val to = entries.indexOfFirst { it.mediaId == trackId }
        if (to < 0) return@onMain
        val live = player
        if (!sounding || target <= 0f) {
            moveTo(live, to)
            announcePending = true
            return@onMain
        }
        fadeMove(live, to)
    }

    /** Fades the track on air out, moves to [to], and brings the level back in as an arrival: the one body of a skip and of a jump. */
    private fun fadeMove(live: ExoPlayer?, to: Int) {
        pausing = false
        // The skip's own fade out and entrance take the place of the dip, and the poll stays off until the new track is on: a look at
        // the old track's last second would start a dip over the skip's way out and lose the move that rides on it.
        stopDipPoll()
        dipped = false
        pendingMove = to
        ramp(YourMusicRampKind.SKIP, 0f, YourMusicGain.ExitMs) {
            val destination = pendingMove
            pendingMove = null
            if (destination != null) moveTo(live, destination)
            announcePending = true
            startDipPoll()
            ramp(YourMusicRampKind.LEVEL, target, YourMusicGain.rampMs(0f, target, entering = true, resuming = false))
        }
    }

    /** A skip that is still fading out when something else takes the volume makes its move now, so the press is not lost. */
    private fun settleSkip(exo: ExoPlayer?) {
        val destination = pendingMove ?: return
        pendingMove = null
        moveTo(exo, destination)
        announcePending = true
    }

    /**
     * Moves the track on air to [positionMs] (the panel's seek bar), clamped into the track once its length is known. The player is sounding or paused as it was; a seek never
     * starts it. Nothing is loaded, nothing happens.
     */
    fun seekTo(positionMs: Long) = onMain("seekTo") {
        val exo = player ?: return@onMain
        if (entries.isEmpty()) return@onMain
        val length = exo.duration
        val target = if (length != C.TIME_UNSET && length > 0L) positionMs.coerceIn(0L, length) else positionMs.coerceAtLeast(0L)
        runCatching { exo.seekTo(target) }.onFailure { Log.w(Tag, "seek failed", it) }
        publishTiming()
    }

    // --- The service's side (main thread) --------------------------------------------------------------------------------

    /**
     * The player, for the service to wrap in its session. Main thread only. Built here if no playlist has asked for it yet (the
     * service can start first), from the service's own context when [install] has not run.
     */
    fun playerFor(context: Context): ExoPlayer {
        if (appContext == null) appContext = context.applicationContext
        player?.let { return it }
        val exo = checkNotNull(buildPlayer()) { "no context to build the player with" }
        loadPlaylist()
        reconcile()
        return exo
    }

    // --- Main-thread work ------------------------------------------------------------------------------------------------

    private fun onMain(what: String, block: () -> Unit) {
        main.post { guarded(what, block) }
    }

    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            Log.e(Tag, "$what failed", error)
        }
    }

    private fun mediaItem(entry: YourMusicEntry): MediaItem = MediaItem.Builder()
        .setMediaId(entry.mediaId)
        .setUri(entry.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(entry.title)
                .setArtist(entry.artist ?: ArtistLabel)
                .apply { entry.album?.let { setAlbumTitle(it) } }
                .build(),
        )
        .build()

    private fun ExoPlayer.stopNow() {
        runCatching { stop() }.onFailure { Log.w(Tag, "stop failed", it) }
    }

    /**
     * The index the player itself would go to, in the order it plays in: Shuffle's order, and the wrap of repeat-all. Null when it
     * has none. A skip steps through this and not through the list's own order, or Next would ignore Shuffle.
     */
    private fun ExoPlayer.neighbour(forward: Boolean): Int? =
        (if (forward) nextMediaItemIndex else previousMediaItemIndex).takeIf { it != C.INDEX_UNSET }

    /** Builds the one player (main thread, the looper it is then used on) and adopts it. Null while there is no context yet. */
    private fun buildPlayer(): ExoPlayer? {
        val context = appContext ?: return null
        // Float output is on where the device plays float and has not refused it (YourMusicEqualizer.renderersFactory): a 24-bit file
        // keeps its depth, and the equalizer runs on the float output, since the sink skips its processor chain for float. Most files go
        // that way (the renderer asks every decoder for float), 16-bit ones too. Else the sink is the 16-bit one.
        // A cold start through a media button gets here before the activity's AudioPreferences.load: read the stored curve now.
        AudioPreferences.ensureEqualizerLoaded(context)
        YourMusicEqualizer.follow()
        val exo = ExoPlayer.Builder(context, YourMusicEqualizer.renderersFactory(context))
            // No focus handling here: the facade owns the request, the abandon-before-a-game and the pause rules.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            // Unplugged headphones are a pause reason of MusicPausePolicy, which also decides when (never) it resumes.
            .setHandleAudioBecomingNoisy(false)
            // Your music plays with the screen off (a media key, a Bluetooth button): the CPU stays up for it.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.repeatMode = Player.REPEAT_MODE_ALL
        // The player's own choice (Settings › Music › Order), read as the player is built; a later change comes through setShuffle.
        exo.shuffleModeEnabled = AudioPreferences.musicOrder.value == MimirMusicOrder.SHUFFLE
        exo.volume = 0f
        exo.addListener(listener)
        YourMusicFormatTracker.attach(exo, context)
        player = exo
        applied = 0f
        sounding = false
        pausing = false
        return exo
    }

    /**
     * The float output's AudioTrack would not start, and none has refused before ([FloatFallback]): the player is built again without
     * float output and put back at [index] and [positionMs], playing if it was, instead of the track being skipped. Once per process,
     * since the refusal is sticky; whatever the new player does after that takes the ordinary path. The volume, the ramps and the
     * sounding state carry over untouched, and the session moves on through [onPlayerReplaced]. Main thread.
     */
    private fun rebuildWithoutFloat(failed: ExoPlayer, index: Int, positionMs: Long) {
        if (player !== failed || entries.isEmpty()) return
        val keptVolume = applied
        val keptSounding = sounding
        val keptPausing = pausing
        YourMusicFormatTracker.reset()
        runCatching { failed.release() }.onFailure { Log.w(Tag, "the player that would not start did not release", it) }
        player = null
        val exo = buildPlayer() ?: return
        applied = keptVolume
        sounding = keptSounding
        pausing = keptPausing
        exo.volume = YourMusicGain.playerVolume(keptVolume)
        exo.setMediaItems(entries.map(::mediaItem), index.coerceIn(0, entries.size - 1), positionMs.coerceAtLeast(0L))
        exo.prepare()
        if (keptSounding) exo.play()
        trackSerial += 1L
        publishTiming()
        onPlayerReplaced?.invoke(exo)
        Log.i(Tag, "player rebuilt without float output at index=$index position=${positionMs}ms")
    }

    private fun loadPlaylist() {
        // Nothing to play needs no player: the first playlist builds it.
        val exo = player ?: (if (entries.isEmpty()) null else buildPlayer()) ?: return
        haltSound()
        if (entries.isEmpty()) {
            // A fresh player with nothing in it emits nothing: Media3 reads an empty timeline as "no notification" and would take the
            // service out of the foreground before the playlist the engine is about to send arrives.
            if (exo.mediaItemCount > 0) {
                exo.stopNow()
                exo.clearMediaItems()
            }
            YourMusicFormatTracker.reset()
            publishTiming()
            return
        }
        val index = cursor.coerceIn(0, entries.size - 1)
        exo.setMediaItems(entries.map(::mediaItem), index, 0L)
        exo.prepare()
        trackSerial += 1L
        publishTiming()
    }

    /** Silence at once, with no fade: a different playlist is about to be loaded, or the player is going away. */
    private fun haltSound() {
        generation += 1
        rampKind = null
        pendingMove = null
        pausing = false
        stopDipPoll()
        dipped = false
        applied = 0f
        val exo = player
        if (exo != null) {
            runCatching { exo.volume = 0f }
            if (sounding) runCatching { exo.pause() }
        }
        if (sounding) {
            sounding = false
            MimirMediaBed.noteOwnPlaybackStopped(ownToken)
            events?.onSounding(false)
            publishTiming()
        }
    }

    private fun reconcile() {
        if (entries.isEmpty()) return
        // No player yet or any more (the service has not run, or never will): the engine's own call builds it.
        val exo = player ?: run { loadPlaylist(); player } ?: return
        when (YourMusicGain.transport(target, sounding)) {
            YourMusicTransport.START -> start(exo)
            YourMusicTransport.RETARGET -> {
                pausing = false
                // A skip fading out reads the level when it lands on the new track: a change now is not lost by leaving it be.
                if (YourMusicRamps.mayStart(YourMusicRampKind.LEVEL, rampKind)) {
                    // The volume goes back to the policy's level, so no dip is left down. A pause that took over from a skip and
                    // was undone before it finished left the poll stopped: it runs again.
                    dipped = false
                    if (!dipPolling) startDipPoll()
                    ramp(YourMusicRampKind.LEVEL, target, YourMusicGain.rampMs(applied, target, entering = false, resuming = false))
                }
            }
            YourMusicTransport.PAUSE -> if (!pausing) {
                settleSkip(exo)
                pausing = true
                ramp(YourMusicRampKind.PAUSE, 0f, YourMusicGain.ExitMs) { finishPause(exo) }
            }
            YourMusicTransport.NONE -> Unit
        }
    }

    private fun start(exo: ExoPlayer) {
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        val arrival = announcePending
        // The claim is made before the player is audible, as everywhere in this process: a configuration that appears first
        // would read as a stranger's.
        MimirMediaBed.noteOwnPlaybackStarting(ownToken)
        applied = 0f
        exo.volume = 0f
        exo.play()
        sounding = true
        pausing = false
        events?.onSounding(true)
        publishTiming()
        // The ramp below brings the volume all the way up, so no dip is left down.
        dipped = false
        startDipPoll()
        ramp(YourMusicRampKind.LEVEL, target, YourMusicGain.rampMs(0f, target, entering = arrival, resuming = !arrival))
        Log.d(Tag, "start index=${exo.currentMediaItemIndex} arrival=$arrival")
    }

    private fun startDipPoll() {
        stopDipPoll()
        dipPolling = true
        main.postDelayed(dipPoll, YourMusicDip.PollMs)
    }

    private fun stopDipPoll() {
        dipPolling = false
        main.removeCallbacks(dipPoll)
    }

    /** One look at the track on air (main thread): its last second begins the way out, and the next track's start takes the way in. */
    private fun pollDip() {
        val exo = player ?: return
        if (!sounding) return
        // The dip yields to a pause or a skip in flight: it may not start over one, or the ending that rides on that ramp is lost.
        if (!YourMusicRamps.mayStart(YourMusicRampKind.DIP, inFlightRamp())) return
        val length = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        // Past the length it was told (a file whose header under-reports it) is "not known", so a dip that began on the strength of
        // that length ends instead of holding the volume down to the end of the track.
        val remaining = length?.let { it - exo.currentPosition }?.takeIf { it >= 0L }
        when (YourMusicDip.step(AudioPreferences.fadeBetweenTracks.value, entries.size, dipped, remaining)) {
            YourMusicDip.Step.FADE_OUT -> {
                dipped = true
                ramp(YourMusicRampKind.DIP, 0f, YourMusicDip.outMs(remaining ?: YourMusicDip.OutMs))
            }
            YourMusicDip.Step.FADE_IN -> fadeBackIn()
            YourMusicDip.Step.NONE -> Unit
        }
    }

    /** The way in after a dip: back to the policy's level over [YourMusicDip.InMs]. */
    private fun fadeBackIn() {
        dipped = false
        ramp(YourMusicRampKind.DIP, target, YourMusicDip.InMs)
    }

    /** The ramp in flight. A pause is in flight from the moment it is asked for, whatever the ramp state says. */
    private fun inFlightRamp(): YourMusicRampKind? = if (pausing) YourMusicRampKind.PAUSE else rampKind

    private fun finishPause(exo: ExoPlayer) {
        runCatching { exo.pause() }.onFailure { Log.w(Tag, "pause failed", it) }
        runCatching { exo.volume = 0f }
        pausing = false
        stopDipPoll()
        dipped = false
        applied = 0f
        sounding = false
        MimirMediaBed.noteOwnPlaybackStopped(ownToken)
        events?.onSounding(false)
        publishTiming()
        // A paused player can leave isMusicActive stale for a moment: re-read the environment once the sound is really gone.
        ReleaseSettleMs.forEach { delay -> main.postDelayed({ MimirMediaBed.refresh() }, delay) }
        Log.d(Tag, "paused index=${exo.currentMediaItemIndex}")
    }

    /** Moves [applied] to [to] over [durationMs] on the player's volume, then runs [onDone]. A newer ramp or a halt cancels this one. */
    private fun ramp(kind: YourMusicRampKind, to: Float, durationMs: Long, onDone: (() -> Unit)? = null) {
        generation += 1
        val mine = generation
        rampKind = kind
        val from = applied
        val steps = YourMusicGain.steps(durationMs)
        var step = 0
        val tick = object : Runnable {
            override fun run() = guarded("ramp") {
                if (mine != generation) return@guarded
                val exo = player ?: return@guarded
                step += 1
                applied = YourMusicGain.rampValue(from, to, step, steps)
                exo.volume = YourMusicGain.playerVolume(applied)
                MimirMediaBed.noteOwnBedLevel(ownToken, sounding && applied > YourMusicGain.AudibleFloor, applied)
                if (announcePending && applied >= YourMusicGain.AudibleFloor) {
                    announcePending = false
                    events?.onTrack(cursor, announce = true)
                }
                if (step < steps) {
                    main.postDelayed(this, YourMusicGain.StepMs)
                } else {
                    // Finished: nothing is in flight, unless the ending starts the next ramp.
                    rampKind = null
                    onDone?.invoke()
                }
            }
        }
        main.post(tick)
    }

    private fun moveTo(exo: ExoPlayer?, index: Int) {
        cursor = index
        if (exo != null) runCatching { exo.seekTo(index, 0L) }.onFailure { Log.w(Tag, "seek failed", it) }
        events?.onTrack(index, announce = false)
        publishTiming()
    }

    /** Publishes where the track on air is (main thread). Null with nothing loaded. Called wherever something moves the position: never on a tick. */
    /** Moves at every track change, repeat and playlist load, so [YourMusicTiming.trackKey] differs between any two playings of a track. Main thread only. */
    private var trackSerial = 0L

    private fun publishTiming() {
        val exo = player
        if (exo == null || entries.isEmpty()) {
            YourMusicTimingFeed.publish(null)
            return
        }
        val length = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        YourMusicTimingFeed.publish(
            YourMusicTiming(
                positionMs = exo.currentPosition.coerceAtLeast(0L),
                durationMs = length,
                updatedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                advancing = sounding,
                trackKey = "${exo.currentMediaItem?.mediaId ?: entries.getOrNull(cursor)?.mediaId.orEmpty()}#$trackSerial",
            ),
        )
    }

    private fun isGrantLoss(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) return true
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is SecurityException) return true
            cause = cause.cause
        }
        return false
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = guarded("transition") {
            if (relabeling) return@guarded
            val exo = player ?: return@guarded
            // A repeat of the same track, like a new one, starts the position over: the bar must say so either way, and a seek gesture begun on the track before is over.
            trackSerial += 1L
            publishTiming()
            // The volume was down for the end of the track that has just finished: the new one takes the way in, and its title is
            // announced when that first becomes audible (the ramp's tick), not now, while it is still silent.
            val wasDipped = dipped
            when (YourMusicRamps.afterTrackChange(dipped, inFlightRamp())) {
                YourMusicRamps.AfterTrackChange.FADE_BACK_IN -> {
                    announcePending = true
                    fadeBackIn()
                }
                // A pause is fading out, or a skip is: it owns the volume and carries on. The track that came is the one the next
                // start opens on, as an arrival.
                YourMusicRamps.AfterTrackChange.DROP_DIP -> {
                    dipped = false
                    announcePending = true
                }
                YourMusicRamps.AfterTrackChange.NOTHING -> Unit
            }
            if (entries.isEmpty() || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return@guarded
            val index = exo.currentMediaItemIndex.coerceIn(0, entries.size - 1)
            cursor = index
            // A track following the one before it, gaplessly, is a new track to the chip; a seek (a skip) announces itself.
            val arrived = !wasDipped && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && sounding &&
                applied > YourMusicGain.AudibleFloor
            events?.onTrack(index, announce = arrived)
            main.post { guarded("relabel") { relabelCurrent() } }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) failures = 0
            // READY is when the track's length is known: the bar can appear.
            if (playbackState == Player.STATE_READY) guarded("timing") { publishTiming() }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = guarded("discontinuity") {
            publishTiming()
        }

        override fun onPlayerError(error: PlaybackException) = guarded("player error") {
            val exo = player ?: return@guarded
            // Float output's AudioTrack would not start: the track plays on a player without float rather than being skipped. No failure
            // is counted, and the player is built again from the main looper's next turn, not from inside its own callback.
            if (YourMusicEqualizer.fallback.onPlayerError(error.errorCode, YourMusicEqualizer.builtWithFloat)) {
                Log.w(Tag, "the float AudioTrack would not start (code=${error.errorCodeName}); building the player without float output")
                val index = exo.currentMediaItemIndex
                val position = exo.currentPosition
                main.post { guarded("float fallback") { rebuildWithoutFloat(exo, index, position) } }
                return@guarded
            }
            failures += 1
            val reason = if (isGrantLoss(error)) CustomMusicFallback.GRANT_LOST else CustomMusicFallback.UNREADABLE
            Log.w(Tag, "track would not play (${reason.name}, code=${error.errorCodeName}) failures=$failures")
            when (val action = yourMusicFailure(failures, reason, MaxFailures)) {
                is YourMusicFailure.GiveUp -> {
                    haltSound()
                    events?.onFailed(action.reason)
                }
                YourMusicFailure.SkipToNext -> {
                    val next = yourMusicSkipIndex(exo.currentMediaItemIndex, entries.size, forward = true, playerNeighbour = exo.neighbour(forward = true))
                    moveTo(exo, next)
                    exo.prepare()
                }
            }
        }
    }
}
