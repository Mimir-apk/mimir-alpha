package com.gruning.mimir.rewrite

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.gruning.mimir.rewrite.core.audio.MimirMusicControl
import com.gruning.mimir.rewrite.core.audio.MusicPauseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Why the launch card's opening sound is held: a closed lid can sleep the device while an unpaused
 * looping `MediaPlayer` continues sounding. [logName] is the `reason=` of the
 * `opening pause` line.
 */
internal enum class OpeningAudioHold(val logName: String) {
    /** Screen off, which a closed lid is. The music's own [MusicPauseReason.SCREEN_OFF]. */
    SCREEN_OFF("SCREEN_OFF"),

    /** Headphones unplugged ([MusicPauseReason.HEADPHONES]). */
    HEADPHONES("HEADPHONES"),

    /** Another app or a call holds the audio ([MusicPauseReason.OTHER_APP]). */
    OTHER_APP("OTHER_APP"),

    /** The player paused the music ([MusicPauseReason.USER]). */
    USER("USER"),

    /** The Bottom's window is parked or hidden behind another app, or dark: the card is not on screen to be heard over. */
    WINDOW_NOT_LIVE("window-not-live"),
}

/**
 * The one decision: whether the card's opening sound may sound now, or what holds it.
 *
 * The first four reasons are NOT decided here. They are [MimirMusicControl.pauseReason], the very answer Your music itself follows
 * (`MusicPauseState` and `MusicPausePolicy`, fed by the screen, headphone, audio-focus and player events), so the opening sound
 * and the music cannot disagree about a closed lid. The window's liveness is the one fact the music does not have: whether the
 * Bottom's window that shows the card is up and lit ([CompanionWindowLive]).
 *
 * `when` over the enum with no `else`: a new [MusicPauseReason] must be given a meaning here before this compiles.
 *
 * [MusicPauseReason.SCHEDULE] (the music hours) holds nothing: a game's launch sound is not the music, and the hours are the player's
 * wish about the music. Any real hold outranks it in the one reason ([MusicPauseState.reason]), so a closed lid still holds the card.
 */
internal fun openingAudioHold(musicPause: MusicPauseReason?, windowLive: Boolean): OpeningAudioHold? = when (musicPause) {
    MusicPauseReason.USER -> OpeningAudioHold.USER
    MusicPauseReason.HEADPHONES -> OpeningAudioHold.HEADPHONES
    MusicPauseReason.OTHER_APP -> OpeningAudioHold.OTHER_APP
    MusicPauseReason.SCREEN_OFF -> OpeningAudioHold.SCREEN_OFF
    MusicPauseReason.SCHEDULE, null -> if (windowLive) null else OpeningAudioHold.WINDOW_NOT_LIVE
}

/**
 * The one thing the gate does to a sound: let it run, or hold it where it is. A `MediaPlayer` in production, a fake in a test.
 *
 * Both calls THROW when the player cannot do it (a `MediaPlayer` in the wrong state throws `IllegalStateException`): the gate treats a
 * throw as a failure and ends the sound, so it never records a start or a hold that did not happen. Both are called on the main
 * looper only (see [runOnMainLooper]).
 */
internal interface OpeningAudioVoice {
    /** Starts the sound, or carries on from where [pause] held it. */
    fun resume()

    /** Holds the sound where it is. A paused sound cannot loop: nothing runs until [resume]. */
    fun pause()
}

/** [OpeningAudioVoice] over the intro's own player. Failures are the gate's to see, so none is swallowed here. */
internal class MediaPlayerOpeningVoice(private val player: MediaPlayer) : OpeningAudioVoice {
    override fun resume() = player.start()

    override fun pause() = player.pause()
}

// --- One thread for the player ------------------------------------------------------------------------------------------------
//
// A MediaPlayer is single-threaded in practice. Every call on the card's player (its construction, setDataSource and prepare, start,
// pause, setVolume, release, the ramps and the fades included) is made on the main looper: the intro's coroutines run there, and the
// gate's collectors run there too (OpeningAudioSources.watchScope). These helpers are for the calls that can be asked for from
// elsewhere (a removal on a worker thread): on the main looper they run in place, anywhere else they are posted to it, in order.

/**
 * A native `MEDIA_ERROR` the player reported through its `OnErrorListener` while it was being prepared: nothing was thrown, so without
 * this the wait for the prepare would never end. [what] and [extra] are the listener's own arguments.
 */
internal class OpeningAudioNativeError(val what: Int, val extra: Int) : Exception(openingAudioNativeReason(what, extra))

/** The `reason=` of the `opening audio failed` line for a native error the player reported with no throw. */
internal fun openingAudioNativeReason(what: Int, extra: Int): String = "media-error what=$what extra=$extra"

/**
 * Builds the opening sound's player from [newPlayer] and prepares it, ALL on the main looper (the player is created there, so its
 * listeners are delivered there too): it is prepared with `prepareAsync` and awaited, never `prepare()` on a worker thread and then
 * used on Main, so no call on the player is ever made off its owning thread and a release cannot race a start.
 *
 * Returns the prepared player with no listener left on it ([GameOpeningIntroState.attachPlayer] puts its own). Throws, with the player
 * released exactly once, when the sound cannot be set up or prepared: a call that throws, a native error reported through
 * `OnErrorListener` with nothing thrown ([OpeningAudioNativeError]), or the caller being cancelled. An error never leaves the launch
 * waiting for a prepare that will not come.
 */
internal suspend fun prepareOpeningPlayer(
    context: Context,
    audioUri: String,
    looping: Boolean,
    newPlayer: () -> MediaPlayer,
): MediaPlayer = withContext(Dispatchers.Main.immediate) {
    val player = newPlayer()
    try {
        suspendCancellableCoroutine<Unit> { continuation ->
            // Main-confined: both listeners and this block run on the main looper, so the flag needs no lock.
            var settled = false
            player.setOnPreparedListener {
                if (!settled) {
                    settled = true
                    continuation.resume(Unit)
                }
            }
            player.setOnErrorListener { _, what, extra ->
                if (!settled) {
                    settled = true
                    continuation.resumeWithException(OpeningAudioNativeError(what, extra))
                }
                true
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.setDataSource(context, Uri.parse(audioUri))
            player.isLooping = looping
            player.prepareAsync()
        }
    } catch (failure: Throwable) {
        releaseOnMainLooper(player)
        throw failure
    }
    player.setOnPreparedListener(null)
    player.setOnErrorListener(null)
    player
}

private fun postToMainLooper(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post(block)
}

internal fun onMainLooper(): Boolean = Looper.myLooper() === Looper.getMainLooper()

/** Runs [block] on the main looper: in place when already there, else posted. */
internal fun runOnMainLooper(block: () -> Unit) {
    if (onMainLooper()) block() else postToMainLooper(block)
}

/** Releases [player] on the main looper. A player that has already gone fails quietly. */
internal fun releaseOnMainLooper(player: MediaPlayer) {
    runOnMainLooper { runCatching { player.release() } }
}

/** Sets [player]'s volume on the main looper. False only when a call made in place failed (the player has gone); a posted one is not awaited. */
internal fun setVolumeOnMainLooper(player: MediaPlayer, volume: Float): Boolean {
    if (!onMainLooper()) {
        postToMainLooper { runCatching { player.setVolume(volume, volume) } }
        return true
    }
    return runCatching { player.setVolume(volume, volume) }.isSuccess
}

/**
 * Keeps ONE looping opening sound in line with [openingAudioHold], and says each decision once.
 *
 * It keeps no copy of the inputs, only what it last told the voice (never `isPlaying`), so every method is idempotent: a repeated
 * SCREEN_OFF, a repeated screen-on, a repeated window park each change nothing and log nothing, and a sound is never started twice
 * or stacked. The looping itself is the player's own (`isLooping`, gapless), and a paused player does not loop, so holding the
 * voice is what stops the restart that came every 15 s through the closed lid; there is no other restart to check.
 *
 * It starts nothing on its own: the first [adopt] starts the voice only when nothing holds it (a card opened while the screen is
 * off stays silent and logs the hold), and a later release of the hold resumes it. It records a start or a hold only AFTER the
 * voice's call returns; a call that throws ends the sound for good ([onVoiceFailed] releases the player) and is never recorded as
 * a success, and so does the player's own report that it cannot go on (a native error delivered with no throw: [fail]). Not
 * thread-safe by itself; the caller serializes (GameOpeningIntroState's lock) and calls from the main looper.
 */
internal class OpeningAudioGate(
    private val log: (String) -> Unit,
    private val onVoiceFailed: () -> Unit = {},
) {
    private enum class Phase { NEW, SOUNDING, HELD }

    private var voice: OpeningAudioVoice? = null
    private var phase = Phase.NEW

    val hasVoice: Boolean get() = voice != null

    /** True while the voice has been told to sound and not told to hold. */
    val isSounding: Boolean get() = voice != null && phase == Phase.SOUNDING

    /** Takes [candidate] with the inputs as they are NOW, so a start never precedes the decision. A voice already held is replaced, not stacked. */
    fun adopt(candidate: OpeningAudioVoice, musicPause: MusicPauseReason?, windowLive: Boolean) {
        voice = candidate
        phase = Phase.NEW
        evaluate(musicPause, windowLive)
    }

    /** The voice has gone (the card closed, a launch took it, the window ended): nothing more can start it. Returns the voice dropped, if any. */
    fun drop(): OpeningAudioVoice? {
        val dropped = voice
        voice = null
        phase = Phase.NEW
        return dropped
    }

    /**
     * Applies the inputs as the CALLER has just read them from their sources. The gate keeps no copy between calls, so a stale value
     * handed to it by a collector that was waiting on a lock cannot be applied over a newer one: whoever calls reads both inputs first.
     */
    fun refresh(musicPause: MusicPauseReason?, windowLive: Boolean) {
        evaluate(musicPause, windowLive)
    }

    private fun evaluate(musicPause: MusicPauseReason?, windowLive: Boolean) {
        val held = voice ?: return
        val hold = openingAudioHold(musicPause, windowLive)
        if (hold == null) {
            if (phase == Phase.SOUNDING) return
            val wasHeld = phase == Phase.HELD
            try {
                held.resume()
            } catch (failure: Exception) {
                failed(failure)
                return
            }
            phase = Phase.SOUNDING
            // The very first start is the caller's own `audio started` line; only the return from a hold is a decision to log.
            if (wasHeld) log("opening resume")
        } else {
            if (phase == Phase.HELD) return
            if (phase == Phase.SOUNDING) {
                try {
                    held.pause()
                } catch (failure: Exception) {
                    failed(failure)
                    return
                }
            }
            phase = Phase.HELD
            log("opening pause reason=${hold.logName}")
        }
    }

    /** The voice could not do what it was told: it is stopped (dropped, never reported as sounding or held) and its player released. */
    private fun failed(failure: Exception) = fail(failure.javaClass.simpleName)

    /**
     * The one ending of a voice that cannot go on, whether its call threw ([failed]) or its player said so through its own listeners
     * with nothing thrown (a native `MEDIA_ERROR`, or a looping sound that ran out). The voice is dropped, so the gate stops waiting
     * on a sound that is no longer there (never reported as sounding or held), and [onVoiceFailed] releases the player. A no-op once the
     * voice has gone, so a second report of the same end releases nothing again.
     */
    fun fail(reason: String) {
        if (voice == null) return
        voice = null
        phase = Phase.NEW
        log("opening audio failed reason=$reason")
        onVoiceFailed()
    }
}

/**
 * Where the gate reads the pause policy, and the scope its watcher runs on. Production reads [MimirMusicControl.pauseReason] on a
 * scope of its own, on the MAIN looper ([watchScope]: the watcher must run while the Bottom's window is parked and its frame clock
 * stopped, so it is not a composition effect, and every call on the player is made from one thread); a test hands it flows and an
 * unconfined or main-looper scope.
 */
internal class OpeningAudioSources(
    val musicPause: StateFlow<MusicPauseReason?>,
    val scope: CoroutineScope,
) {
    companion object {
        /** Main looper, immediate: a change made on the main thread is applied in place, one made elsewhere is posted to it. */
        internal val watchScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

        fun production(): OpeningAudioSources = OpeningAudioSources(MimirMusicControl.pauseReason, watchScope)
    }
}
